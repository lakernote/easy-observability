package io.github.lakernote.observability.trace;

import java.util.List;

/** A bounded, structured node in a captured trace tree.
 * @param unfinishedAtTraceEnd true when this node was still open as its root trace ended
 */
public record TraceNode(String kind, String name, String tag, long durationMs, Long startOffsetMs,
                        boolean slow, boolean error, boolean unfinishedAtTraceEnd,
                        String errorType, String threadName, long threadId, List<TraceNode> children) {
    public TraceNode {
        kind = kind == null ? "OTHER" : kind;
        name = name == null ? "" : name;
        tag = tag == null ? "" : tag;
        errorType = errorType == null ? "" : errorType;
        threadName = threadName == null ? "" : threadName;
        children = children == null ? List.of() : List.copyOf(children);
    }
}
