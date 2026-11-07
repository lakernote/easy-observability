package io.github.lakernote.meerkat.trace.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A bounded, structured node in a captured trace tree.
 * @param unfinishedAtTraceEnd true when this node was still open as its root trace ended
 * @param captures             request data recorded by traffic capture; always empty in stored diagnostic traces
 */
public record TraceNode(String kind, String name, String tag, long durationMs, long startOffsetMs,
                        boolean slow, boolean error, boolean unfinishedAtTraceEnd,
                        String errorType, String threadName, long threadId, List<TraceNode> children,
                        Map<String, String> captures) {
    public TraceNode(String kind, String name, String tag, long durationMs, long startOffsetMs,
                     boolean slow, boolean error, boolean unfinishedAtTraceEnd,
                     String errorType, String threadName, long threadId, List<TraceNode> children) {
        this(kind, name, tag, durationMs, startOffsetMs, slow, error, unfinishedAtTraceEnd, errorType, threadName,
                threadId, children, Map.of());
    }

    public TraceNode {
        kind = kind == null ? "OTHER" : kind;
        name = name == null ? "" : name;
        tag = tag == null ? "" : tag;
        errorType = errorType == null ? "" : errorType;
        threadName = threadName == null ? "" : threadName;
        children = children == null ? List.of() : List.copyOf(children);
        captures = captures == null || captures.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(captures));
    }
}
