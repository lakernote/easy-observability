package io.github.lakernote.observability.integration.transaction;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.metrics.OperationRecorder;
import io.github.lakernote.observability.trace.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Records Spring transaction boundaries without proxying application services.
 */
public final class TransactionTraceListener implements TransactionExecutionListener {
    private final OperationRecorder recorder;
    private final EasyObservabilityProperties properties;
    private final AtomicLong activeTransactions = new AtomicLong();
    private final Map<TransactionMetricKey, Timer> transactionTimers;
    private final LongSupplier nanoTime;
    private final ThreadLocal<Map<TransactionExecution, State>> states =
            ThreadLocal.withInitial(IdentityHashMap::new);

    public TransactionTraceListener(OperationRecorder recorder, MeterRegistry meterRegistry,
                                    EasyObservabilityProperties properties) {
        this(recorder, meterRegistry, properties, System::nanoTime);
    }

    TransactionTraceListener(OperationRecorder recorder, MeterRegistry meterRegistry,
                             EasyObservabilityProperties properties, LongSupplier nanoTime) {
        this.recorder = recorder;
        this.properties = properties;
        this.nanoTime = nanoTime;
        if (properties.getMetrics().isEnabled()) {
            meterRegistry.gauge("easy.transactions.active", activeTransactions, AtomicLong::get);
        }
        this.transactionTimers = registerTransactionTimers(meterRegistry, properties.getMetrics().isEnabled());
    }

    @Override
    public void beforeBegin(TransactionExecution execution) {
        if (ObservationSuppression.active()) return;
        try {
            String currentId = TraceIdContext.currentTraceId();
            boolean tracing = properties.getTrace().isEnabled();
            boolean needsTraceId = tracing && TraceContext.active();
            boolean ownsTraceId = needsTraceId && !TraceIdContext.isValidTraceId(currentId);
            String traceId = ownsTraceId ? TraceIdContext.getOrCreateTraceId()
                    : currentId == null ? "" : currentId;
            Thread currentThread = Thread.currentThread();
            State state = new State(traceId, ownsTraceId, tracing,
                    safeName(execution.getTransactionName()), execution.isReadOnly(), nanoTime.getAsLong(),
                    currentThread.getId(), currentThread.getName());
            if (tracing && TraceContext.active()) {
                // Begin before the transaction manager acquires its connection so connection work is nested here.
                state.span = TraceContext.startScopedSpan(state.name, SpanKind.TRANSACTION,
                        state.readOnly ? "read-only" : "write");
            }
            states.get().put(execution, state);
        } catch (Throwable ignored) {
            states.get().remove(execution);
        }
    }

    @Override
    public void afterBegin(TransactionExecution execution, Throwable failure) {
        State state = states.get().get(execution);
        if (state == null) return;
        if (failure != null) {
            finish(execution, state, false, failure, false);
            return;
        }
        try {
            state.startedNanos = nanoTime.getAsLong();
            state.threadId = Thread.currentThread().getId();
            state.threadName = Thread.currentThread().getName();
            state.resources = ThreadResourceSnapshot.capture(state.threadId,
                    properties.getTrace().isResourceSamplingEnabled());
            state.started = true;
            state.countedActive = true;
            activeTransactions.incrementAndGet();
        } catch (Throwable ignored) {
            // Instrumentation must never change the transaction result.
        }
    }

    @Override
    public void afterCommit(TransactionExecution execution, Throwable failure) {
        finish(execution, states.get().get(execution), failure == null, failure, true);
    }

    @Override
    public void afterRollback(TransactionExecution execution, Throwable failure) {
        finish(execution, states.get().get(execution), false, failure, true);
    }

    private void finish(TransactionExecution execution, State state, boolean success, Throwable failure,
                        boolean transactionStarted) {
        if (state == null) return;
        states.get().remove(execution);
        try {
            long durationMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong()
                    - (state.started ? state.startedNanos : state.createdNanos)));
            boolean slow = properties.getTransaction().getSlowThresholdMs() > 0
                    && durationMs >= properties.getTransaction().getSlowThresholdMs();
            boolean sameThread = state.threadId == Thread.currentThread().getId();
            if (state.tracing && sameThread && slow) TraceContext.markSlow("LONG_TRANSACTION");
            BoundedThrowableFormatter.FailureEvidence error = failure == null
                    ? success ? null : new BoundedThrowableFormatter.FailureEvidence(
                    "TransactionRollback", "Transaction rolled back", "")
                    : BoundedThrowableFormatter.capture(failure);
            if (state.tracing && sameThread && !success) {
                TraceContext.markError("TRANSACTION_ERROR", error);
            }
            if (sameThread && state.span != null) state.span.close();

            ThreadResourceSnapshot end = transactionStarted && sameThread
                    ? ThreadResourceSnapshot.capture(state.threadId,
                    properties.getTrace().isResourceSamplingEnabled())
                    : null;
            long cpuTimeMs = end == null || state.resources == null ? -1 : end.cpuTimeMsSince(state.resources);
            long allocatedBytes = end == null || state.resources == null
                    ? -1 : end.allocatedBytesSince(state.resources);
            recorder.record(new OperationRecord("TRANSACTION", state.traceId, state.name,
                    state.threadName == null ? Thread.currentThread().getName() : state.threadName,
                    state.threadId, success ? "success" : "failure", durationMs, cpuTimeMs, allocatedBytes,
                    state.readOnly ? "read-only" : "write", "", error == null ? "" : error.stackTrace(),
                    slow, !success, slow ? List.of("LONG_TRANSACTION") : List.of(),
                    error == null ? "" : error.type(), error == null ? "" : error.message(), Map.of(), Instant.now(),
                    state.span == null ? "" : state.span.spanId(),
                    state.span == null ? "" : state.span.parentSpanId()));
            Timer transactionTimer = transactionTimers.get(new TransactionMetricKey(success, state.readOnly));
            if (transactionTimer != null) transactionTimer.record(durationMs, TimeUnit.MILLISECONDS);

        } catch (Throwable ignored) {
            // Instrumentation must never change the transaction result.
        } finally {
            if (state.countedActive) activeTransactions.updateAndGet(value -> Math.max(0, value - 1));
            if (state.ownsTraceId && state.traceId.equals(TraceIdContext.currentTraceId())) TraceIdContext.clear();
            if (states.get().isEmpty()) states.remove();
        }
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "transaction" : name.length() > 200 ? name.substring(0, 200) : name;
    }

    private static Map<TransactionMetricKey, Timer> registerTransactionTimers(MeterRegistry registry,
                                                                              boolean enabled) {
        if (!enabled) return Map.of();
        Map<TransactionMetricKey, Timer> timers = new LinkedHashMap<>();
        for (boolean success : new boolean[]{true, false}) {
            for (boolean readOnly : new boolean[]{true, false}) {
                TransactionMetricKey key = new TransactionMetricKey(success, readOnly);
                timers.put(key, Timer.builder("easy.transactions")
                        .description("Spring transaction count and duration")
                        .tag("result", success ? "success" : "failure")
                        .tag("read_only", Boolean.toString(readOnly)).register(registry));
            }
        }
        return Map.copyOf(timers);
    }

    private record TransactionMetricKey(boolean success, boolean readOnly) {
    }

    private static final class State {
        private final String traceId;
        private final boolean ownsTraceId;
        private final String name;
        private final boolean readOnly;
        private final boolean tracing;
        private final long createdNanos;
        private long startedNanos;
        private long threadId;
        private String threadName;
        private ThreadResourceSnapshot resources;
        private TraceContext.ScopedSpan span;
        private boolean started;
        private boolean countedActive;

        private State(String traceId, boolean ownsTraceId, boolean tracing, String name, boolean readOnly,
                      long createdNanos, long threadId, String threadName) {
            this.traceId = traceId;
            this.ownsTraceId = ownsTraceId;
            this.tracing = tracing;
            this.name = name;
            this.readOnly = readOnly;
            this.createdNanos = createdNanos;
            this.threadId = threadId;
            this.threadName = threadName;
        }
    }
}
