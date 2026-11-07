package io.github.lakernote.meerkat.capture.internal;

import io.github.lakernote.meerkat.trace.internal.TraceLog;
import io.github.lakernote.meerkat.trace.internal.TraceNode;

import java.time.Instant;
import java.util.List;

/** One captured request with the full call tree and the data recorded on each node. */
public record CaptureRecord(String id, String sessionId, String traceId, String method, String path, int status,
                            long durationMs, boolean error, Instant finishedAt, String errorType,
                            String errorMessage, String errorStackTrace, List<TraceNode> nodes, List<TraceLog> logs) {
    public CaptureRecord(String id, String sessionId, String traceId, String method, String path, int status,
                         long durationMs, boolean error, Instant finishedAt, String errorType, String errorMessage,
                         String errorStackTrace, List<TraceNode> nodes) {
        this(id, sessionId, traceId, method, path, status, durationMs, error, finishedAt, errorType, errorMessage,
                errorStackTrace, nodes, List.of());
    }

    public CaptureRecord {
        logs = logs == null ? List.of() : List.copyOf(logs);
        errorType = errorType == null ? "" : errorType;
        errorMessage = errorMessage == null ? "" : errorMessage;
        errorStackTrace = errorStackTrace == null ? "" : errorStackTrace;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public Summary summary() {
        return new Summary(id, sessionId, traceId, method, path, status, durationMs, error, errorType, finishedAt);
    }

    public record Summary(String id, String sessionId, String traceId, String method, String path, int status,
                          long durationMs, boolean error, String errorType, Instant finishedAt) { }
}
