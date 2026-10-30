package io.github.lakernote.observability.integration.transaction;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.*;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Records Spring transaction boundaries without proxying application services.
 */
public final class TransactionTraceListener implements TransactionExecutionListener {
    private final EasyObservabilityProperties properties;
    private final ThreadLocal<Map<TransactionExecution, State>> states = new ThreadLocal<>();

    public TransactionTraceListener(EasyObservabilityProperties properties) {
        this.properties = properties;
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
                    safeName(execution.getTransactionName()), execution.isReadOnly(), currentThread.getId());
            if (tracing && TraceContext.active()) {
                // Begin before the transaction manager acquires its connection so connection work is nested here.
                state.span = TraceContext.startScopedSpan(state.name, SpanKind.TRANSACTION,
                        state.readOnly ? "read-only" : "write");
            }
            Map<TransactionExecution, State> currentStates = states.get();
            if (currentStates == null) {
                currentStates = new IdentityHashMap<>();
                states.set(currentStates);
            }
            currentStates.put(execution, state);
        } catch (Throwable ignored) {
            removeState(execution);
        }
    }

    @Override
    public void afterBegin(TransactionExecution execution, Throwable failure) {
        State state = stateFor(execution);
        if (state == null) return;
        if (failure != null) {
            finish(execution, state, false, failure);
        } else {
            // A transaction can be completed on another thread by some transaction managers.
            state.threadId = Thread.currentThread().getId();
        }
    }

    @Override
    public void afterCommit(TransactionExecution execution, Throwable failure) {
        finish(execution, stateFor(execution), failure == null, failure);
    }

    @Override
    public void afterRollback(TransactionExecution execution, Throwable failure) {
        finish(execution, stateFor(execution), false, failure);
    }

    private void finish(TransactionExecution execution, State state, boolean success, Throwable failure) {
        if (state == null) return;
        removeState(execution);
        try {
            boolean sameThread = state.threadId == Thread.currentThread().getId();
            BoundedThrowableFormatter.FailureEvidence error = failure == null
                    ? success ? null : new BoundedThrowableFormatter.FailureEvidence(
                    "TransactionRollback", "Transaction rolled back", "")
                    : BoundedThrowableFormatter.capture(failure);
            if (state.tracing && sameThread && !success) {
                TraceContext.markError("TRANSACTION_ERROR", error);
            }
            if (sameThread && state.span != null) state.span.close();
        } catch (Throwable ignored) {
            // Instrumentation must never change the transaction result.
        } finally {
            if (state.ownsTraceId && state.traceId.equals(TraceIdContext.currentTraceId())) TraceIdContext.clear();
        }
    }

    private State stateFor(TransactionExecution execution) {
        Map<TransactionExecution, State> currentStates = states.get();
        return currentStates == null ? null : currentStates.get(execution);
    }

    private void removeState(TransactionExecution execution) {
        Map<TransactionExecution, State> currentStates = states.get();
        if (currentStates == null) return;
        currentStates.remove(execution);
        if (currentStates.isEmpty()) states.remove();
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "transaction" : name.length() > 200 ? name.substring(0, 200) : name;
    }

    private static final class State {
        private final String traceId;
        private final boolean ownsTraceId;
        private final String name;
        private final boolean readOnly;
        private final boolean tracing;
        private long threadId;
        private TraceContext.ScopedSpan span;

        private State(String traceId, boolean ownsTraceId, boolean tracing, String name, boolean readOnly,
                      long threadId) {
            this.traceId = traceId;
            this.ownsTraceId = ownsTraceId;
            this.tracing = tracing;
            this.name = name;
            this.readOnly = readOnly;
            this.threadId = threadId;
        }
    }
}
