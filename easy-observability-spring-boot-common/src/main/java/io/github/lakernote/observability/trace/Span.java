package io.github.lakernote.observability.trace;

import java.util.ArrayList;
import java.util.List;

final class Span {
    private final String spanId;
    private String name;
    private String tag;
    private final SpanKind kind;
    private final Span parent;
    private final String threadName;
    private final long threadId;
    private long startNanos;
    /** 大多数节点没有子节点；只有确实挂入下游调用时才分配子节点列表。 */
    private List<Span> children;
    private long endNanos;
    private boolean unfinishedAtTraceEnd;
    private String errorType = "";
    private boolean error;
    private boolean slow;

    Span(String spanId, String name, SpanKind kind, String tag, Span parent) {
        this.spanId = spanId == null ? "" : spanId;
        this.name = normalizeName(name);
        this.tag = normalizeTag(tag);
        this.kind = kind == null ? SpanKind.OTHER : kind;
        this.parent = parent;
        Thread thread = Thread.currentThread();
        this.threadName = thread.getName();
        this.threadId = thread.getId();
        this.startNanos = System.nanoTime();
    }

    void addChild(Span child) {
        if (children == null) children = new ArrayList<>();
        children.add(child);
    }

    void updateIdentity(String name, String tag) {
        if (name != null && !name.isBlank()) this.name = normalizeName(name);
        if (tag != null && !tag.isBlank()) this.tag = normalizeTag(tag);
    }

    void markError(BoundedThrowableFormatter.FailureEvidence failure) {
        error = true;
        if (failure != null && (!failure.type().isBlank())
                && (errorType.isBlank() || !failure.stackTrace().isBlank())) errorType = failure.type();
    }

    void markSlow() {
        slow = true;
    }

    void removeFromParent() {
        if (parent != null && parent.children != null) {
            parent.children.remove(this);
        }
    }

    String spanId() {
        return spanId;
    }

    String parentSpanId() {
        return parent == null ? "" : parent.spanId();
    }

    long startNanos() {
        return startNanos;
    }

    int subtreeSize() {
        int size = 1;
        if (children != null) {
            for (Span child : children) {
                size += child.subtreeSize();
            }
        }
        return size;
    }

    synchronized void stop() {
        if (endNanos == 0) {
            endNanos = System.nanoTime();
        }
    }

    void stopOpenDescendants() {
        if (children != null) {
            for (Span child : children) {
                child.stopOpenDescendants();
                child.stopAtTraceEnd();
            }
        }
    }

    private synchronized void stopAtTraceEnd() {
        if (endNanos == 0) {
            unfinishedAtTraceEnd = true;
            endNanos = System.nanoTime();
        }
    }

    /** 在 Observation 等监控初始化结束、业务代码即将执行时重置计时起点。 */
    void beginBusinessBoundary() {
        if (endNanos == 0) startNanos = System.nanoTime();
    }

    synchronized long costMs() {
        long end = endNanos == 0 ? System.nanoTime() : endNanos;
        return (end - startNanos) / 1_000_000;
    }

    TraceNode snapshotNode(long traceStartNanos) {
        List<TraceNode> childNodes = new ArrayList<>();
        if (children != null) {
            for (Span child : children) childNodes.add(child.snapshotNode(traceStartNanos));
        }
        long offsetMs = Math.max(0, (startNanos - traceStartNanos) / 1_000_000);
        return new TraceNode(kind.displayName(), bounded(name, 512), bounded(tag, 2048), costMs(), offsetMs, slow, error,
                unfinishedAtTraceEnd, bounded(errorType, 256), bounded(threadName, 256), threadId, childNodes);
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength);
    }

    void appendTree(TraceTree.RenderBuffer builder, String prefix, boolean last) {
        if (builder.truncated()) return;
        appendNode(builder, prefix, last);
        if (children == null || builder.truncated()) return;
        String childPrefix = prefix + (last ? "   " : "|  ");
        for (int i = 0; i < children.size() && !builder.truncated(); ) {
            int end = aggregateEnd(i);
            boolean childLast = end >= children.size();
            if (end - i > 1) {
                appendAggregatedLeaf(builder, children.subList(i, end), childPrefix, childLast);
            } else {
                children.get(i).appendTree(builder, childPrefix, childLast);
            }
            i = end;
        }
    }

    private void appendNode(TraceTree.RenderBuffer builder, String prefix, boolean last) {
        builder.append(prefix)
                .append(last ? "`- " : "|- ")
                .append('[').append(kind.displayName()).append("] ")
                .append(name);
        if (slow) builder.append(" [SLOW]");
        if (error) builder.append(errorType.isBlank() ? " [ERROR]" : " [ERROR ").append(errorType.isBlank() ? "" : errorType + "]");
        if (!tag.isBlank()) {
            builder.append(" tag=\"").append(tag).append("\"");
        }
        builder
                .append(" ")
                .append(costMs())
                .append("ms")
                .append(System.lineSeparator());
    }

    private int aggregateEnd(int start) {
        Span first = children.get(start);
        if (!first.canAggregate()) {
            return start + 1;
        }
        int end = start + 1;
        while (end < children.size() && first.sameAggregateKey(children.get(end))) {
            end++;
        }
        return end;
    }

    private static void appendAggregatedLeaf(TraceTree.RenderBuffer builder, List<Span> spans, String prefix,
                                             boolean last) {
        Span first = spans.get(0);
        long total = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (Span span : spans) {
            long costMs = span.costMs();
            total += costMs;
            min = Math.min(min, costMs);
            max = Math.max(max, costMs);
        }
        builder.append(prefix)
                .append(last ? "`- " : "|- ")
                .append('[').append(first.kind.displayName()).append("] ")
                .append(first.name);
        if (!first.tag.isBlank()) {
            builder.append(" tag=\"").append(first.tag).append("\"");
        }
        builder
                .append(" [count=").append(spans.size())
                .append(" total=").append(total).append("ms")
                .append(" min=").append(min).append("ms")
                .append(" max=").append(max).append("ms]")
                .append(System.lineSeparator());
    }

    private boolean canAggregate() {
        return !error && !slow && (children == null || children.isEmpty());
    }

    private boolean sameAggregateKey(Span other) {
        return other != null
                && other.canAggregate()
                && kind == other.kind
                && name.equals(other.name)
                && tag.equals(other.tag);
    }

    private static String normalizeName(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private static String normalizeTag(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
