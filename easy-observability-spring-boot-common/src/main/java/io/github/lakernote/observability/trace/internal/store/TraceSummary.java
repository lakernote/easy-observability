package io.github.lakernote.observability.trace.internal.store;

import io.github.lakernote.observability.trace.internal.TraceNode;

import java.time.Instant;
import java.util.List;

/** The list-row view of a stored trace; the call tree and stack trace are only loaded when a trace is opened. */
public record TraceSummary(String traceId, String sourceType, String name, String tag, int status,
                           long durationMs, boolean slow, boolean error, boolean highCpu, boolean highAllocation,
                           long cpuTimeMs, long allocatedBytes, int unfinishedSpanCount, String errorType,
                           String errorMessage, String threadName, Instant finishedAt) {
    private static final int MAX_ERROR_MESSAGE_CHARS = 300;

    static TraceSummary of(TraceRecord trace) {
        String message = trace.errorMessage();
        if (message.length() > MAX_ERROR_MESSAGE_CHARS) message = message.substring(0, MAX_ERROR_MESSAGE_CHARS) + "…";
        return new TraceSummary(trace.traceId(), trace.sourceType(), trace.name(), trace.tag(), trace.status(),
                trace.durationMs(), trace.slow(), trace.error(), trace.highCpu(), trace.highAllocation(),
                trace.cpuTimeMs(), trace.allocatedBytes(), unfinished(trace.treeNodes()), trace.errorType(),
                message, trace.threadName(), trace.finishedAt());
    }

    /** Rough heap footprint of a list row including its index entry; two bytes per character. */
    long estimatedBytes() {
        return 320 + 2L * (length(traceId) + length(sourceType) + length(name) + length(tag) + length(errorType)
                + length(errorMessage) + length(threadName));
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static int unfinished(List<TraceNode> nodes) {
        int count = 0;
        for (TraceNode node : nodes) count += (node.unfinishedAtTraceEnd() ? 1 : 0) + unfinished(node.children());
        return count;
    }
}
