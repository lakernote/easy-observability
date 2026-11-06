package io.github.lakernote.meerkat.integration.observation.internal;

import io.github.lakernote.meerkat.trace.internal.BoundedThrowableFormatter;
import io.github.lakernote.meerkat.trace.internal.RootTraceHandle;
import io.github.lakernote.meerkat.trace.internal.SpanHandle;
import io.github.lakernote.meerkat.trace.internal.ThreadResourceSnapshot;

/** Per-Observation bookkeeping stored on the Observation context between start and stop. */
final class ObservationState {
    final ObservationType type;
    final String operationName;
    final String startDetail;
    final String traceId;
    /** This Observation created the root trace and is responsible for completing it. */
    final boolean root;
    /** This Observation put the Trace ID into MDC and must remove it on the same thread. */
    final boolean installedTraceId;
    final long startedAtNanos;
    final long startThreadId;
    final String startThreadName;
    final ThreadResourceSnapshot resourcesAtStart;
    final SpanHandle span;
    final RootTraceHandle traceHandle;
    volatile BoundedThrowableFormatter.FailureEvidence failure;

    ObservationState(ObservationType type, String operationName, String startDetail, String traceId, boolean root,
                     boolean installedTraceId, long startThreadId, String startThreadName,
                     ThreadResourceSnapshot resourcesAtStart, SpanHandle span, RootTraceHandle traceHandle) {
        this.type = type;
        this.operationName = operationName;
        this.startDetail = startDetail;
        this.traceId = traceId;
        this.root = root;
        this.installedTraceId = installedTraceId;
        this.startedAtNanos = System.nanoTime();
        this.startThreadId = startThreadId;
        this.startThreadName = startThreadName;
        this.resourcesAtStart = resourcesAtStart;
        this.span = span;
        this.traceHandle = traceHandle;
    }

    boolean startedOnCurrentThread() {
        return startThreadId == Thread.currentThread().getId();
    }

    /** Resources are per-thread counters; finishing on another thread cannot subtract them, so report unknown. */
    ThreadResourceSnapshot resourcesAtStop() {
        if (!startedOnCurrentThread()) return new ThreadResourceSnapshot(startThreadId, -1, -1);
        return ThreadResourceSnapshot.capture(startThreadId);
    }
}
