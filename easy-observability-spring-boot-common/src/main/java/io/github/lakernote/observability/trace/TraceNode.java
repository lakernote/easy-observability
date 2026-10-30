package io.github.lakernote.observability.trace;

import java.util.List;

/** A bounded, structured node in a captured trace tree. */
public record TraceNode(String kind, String name, String tag, long durationMs, Long startOffsetMs,
                        boolean slow, boolean error,
                        String errorType, String threadName, long threadId, List<TraceNode> children) {
    /** Keeps the previous public constructor compatible; legacy nodes have no known start offset. */
    public TraceNode(String kind, String name, String tag, long durationMs, boolean slow, boolean error,
                     String errorType, String threadName, long threadId, List<TraceNode> children) {
        this(kind, name, tag, durationMs, null, slow, error, errorType, threadName, threadId, children);
    }

    public TraceNode {
        kind = kind == null ? "OTHER" : kind;
        name = name == null ? "" : name;
        tag = tag == null ? "" : tag;
        errorType = errorType == null ? "" : errorType;
        threadName = threadName == null ? "" : threadName;
        children = children == null ? List.of() : List.copyOf(children);
    }
}
