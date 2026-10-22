package io.github.lakernote.observability.integration.executor;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.BoundedThrowableFormatter;
import io.github.lakernote.observability.trace.OperationRecord;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.ThreadResourceSnapshot;
import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.github.lakernote.observability.trace.TraceOperationRecorder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Records per-task diagnostics in an active Trace without creating a Micrometer meter. */
public final class ExecutorTaskTrace {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExecutorTaskTrace.class);

    private final TraceOperationRecorder recorder;
    private final EasyObservabilityProperties properties;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public ExecutorTaskTrace(TraceOperationRecorder recorder, EasyObservabilityProperties properties) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public void observe(String executorName, String taskName, Runnable task) {
        Objects.requireNonNull(task, "task");
        observe(executorName, taskName, () -> {
            task.run();
            return null;
        });
    }

    public <T> T observe(String executorName, String taskName, Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        if (!properties.getTrace().isEnabled() || !TraceContext.active()) return task.get();
        TaskState state;
        try {
            state = start(executorName, taskName);
        } catch (RuntimeException instrumentationFailure) {
            logFailure(instrumentationFailure);
            return task.get();
        }
        Throwable failure = null;
        try {
            return task.get();
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            finish(state, failure);
        }
    }

    private TaskState start(String executorName, String taskName) {
        boolean traceActive = TraceContext.active();
        String name = safeName(taskName, "executor-task");
        String executor = safeName(executorName, "unknown");
        Thread thread = Thread.currentThread();
        ThreadResourceSnapshot resources = ThreadResourceSnapshot.capture(thread.getId(),
                properties.getTrace().isResourceSamplingEnabled());
        TraceContext.RootTraceHandle traceHandle = traceActive ? TraceContext.currentRootTraceHandle() : null;
        TraceContext.DetachedSpan span = TraceContext.startDetachedSpan(name, SpanKind.CODE, executor);
        return new TaskState(name, executor, TraceIdContext.currentTraceId(), traceActive, traceHandle, span,
                thread.getName(), thread.getId(), System.nanoTime(), resources);
    }

    private void finish(TaskState state, Throwable error) {
        try {
            long durationMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - state.startedAtNanos));
            ThreadResourceSnapshot after = ThreadResourceSnapshot.capture(state.threadId,
                    properties.getTrace().isResourceSamplingEnabled());
            long threshold = properties.getTrace().getExecutorTaskSlowThresholdMs();
            boolean slow = threshold > 0 && durationMs >= threshold;
            boolean failed = error != null;
            BoundedThrowableFormatter.FailureEvidence failure = failed
                    ? BoundedThrowableFormatter.capture(error)
                    : BoundedThrowableFormatter.FailureEvidence.EMPTY;
            if (state.traceHandle != null) {
                if (slow) state.traceHandle.markSlow("SLOW_EXECUTOR_TASK");
                if (failed) state.traceHandle.markError("EXECUTOR_TASK_ERROR", failure);
            }
            state.span.close();
            recorder.record(new OperationRecord("EXECUTOR_TASK", state.traceId, state.taskName,
                    state.threadName, state.threadId, failed ? "failure" : "success", durationMs,
                    after.cpuTimeMsSince(state.resourcesAtStart), after.allocatedBytesSince(state.resourcesAtStart),
                    state.executorName, "", failure.stackTrace(), slow, failed,
                    slow ? List.of("SLOW_EXECUTOR_TASK") : List.of(),
                    failed ? failure.type() : "", failed ? failure.message() : "", Map.of(), Instant.now(),
                    state.span.spanId(), state.span.parentSpanId()), state.traceActiveAtStart);
        } catch (RuntimeException failure) {
            logFailure(failure);
        }
    }

    private void logFailure(RuntimeException failure) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("Executor task Trace recording failed; task result is unchanged ({})",
                    failure.getClass().getName());
        }
    }

    private static String safeName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= 200 ? clean : clean.substring(0, 200);
    }

    private record TaskState(String taskName, String executorName, String traceId, boolean traceActiveAtStart,
                             TraceContext.RootTraceHandle traceHandle, TraceContext.DetachedSpan span,
                             String threadName, long threadId, long startedAtNanos,
                             ThreadResourceSnapshot resourcesAtStart) { }
}
