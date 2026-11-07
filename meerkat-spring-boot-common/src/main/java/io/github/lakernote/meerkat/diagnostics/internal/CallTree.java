package io.github.lakernote.meerkat.diagnostics.internal;

import io.github.lakernote.meerkat.diagnostics.internal.CpuProfiler.Frame;
import io.github.lakernote.meerkat.diagnostics.internal.CpuProfiler.HotMethod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sampled stacks merged into a call tree with a fixed node budget. A sample that needs a node beyond the budget is
 * counted at the deepest frame already in the tree, so totals stay correct while detail is lost.
 */
final class CallTree {
    static final String ROOT = "all";
    static final String DEEP_STACK = "[deeper frames omitted]";
    private final int maxNodes;
    private final int maxDepth;
    private final Node root = new Node(ROOT);
    private int nodes = 1;
    private long samples;
    private long truncatedSamples;

    CallTree(int maxNodes, int maxDepth) {
        this.maxNodes = maxNodes;
        this.maxDepth = maxDepth;
    }

    /**
     * Adds one stack, innermost frame first as returned by {@link Thread#getStackTrace()}. Stacks longer than the
     * depth limit keep their innermost frames below a synthetic {@link #DEEP_STACK} frame.
     */
    void add(String thread, StackTraceElement[] frames) {
        samples++;
        root.total++;
        Node node = root;
        boolean cut = false;
        if (thread != null) node = child(node, "[" + thread + "]");
        if (node != null && frames.length >= maxDepth) node = child(node, DEEP_STACK);
        int depth = Math.min(frames.length, maxDepth);
        for (int index = depth - 1; index >= 0 && node != null; index--) {
            Node next = child(node, frames[index].getClassName() + "." + frames[index].getMethodName());
            if (next == null) {
                cut = true;
                break;
            }
            node = next;
        }
        if (node == null) {
            node = root;
            cut = true;
        }
        node.self++;
        if (cut) truncatedSamples++;
    }

    /** Returns the existing or a new child counted with this sample, or {@code null} when the budget is spent. */
    private Node child(Node parent, String name) {
        Node child = parent.children.get(name);
        if (child == null) {
            if (nodes >= maxNodes) return null;
            child = new Node(name);
            parent.children.put(name, child);
            nodes++;
        }
        child.total++;
        return child;
    }

    long samples() {
        return samples;
    }

    long truncatedSamples() {
        return truncatedSamples;
    }

    int nodes() {
        return nodes;
    }

    /** The tree with children ordered by sample count, largest first. */
    Frame frames() {
        return frame(root);
    }

    private static Frame frame(Node node) {
        List<Frame> children = new ArrayList<>(node.children.size());
        node.children.values().stream().sorted(Comparator.comparingLong((Node child) -> child.total).reversed())
                .forEach(child -> children.add(frame(child)));
        return new Frame(node.name, node.total, node.self, children);
    }

    /** Methods with the most samples at the top of the stack. */
    List<HotMethod> hotMethods(int limit) {
        Map<String, Long> self = new HashMap<>();
        Deque<Node> pending = new ArrayDeque<>(root.children.values());
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node.self > 0) self.merge(node.name, node.self, Long::sum);
            pending.addAll(node.children.values());
        }
        return self.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> new HotMethod(entry.getKey(), entry.getValue(),
                        samples == 0 ? 0 : Math.round(entry.getValue() * 1000.0 / samples) / 10.0))
                .toList();
    }

    /** Brendan Gregg's collapsed format, one {@code frame;frame;frame count} line per leaf, for flamegraph.pl and speedscope. */
    String collapsed() {
        StringBuilder out = new StringBuilder();
        Deque<String> path = new ArrayDeque<>();
        for (Node child : root.children.values()) collapse(child, path, out);
        return out.toString();
    }

    private static void collapse(Node node, Deque<String> path, StringBuilder out) {
        path.addLast(node.name.replace(';', ':').replace(' ', '_'));
        if (node.self > 0) out.append(String.join(";", path)).append(' ').append(node.self).append('\n');
        for (Node child : node.children.values()) collapse(child, path, out);
        path.removeLast();
    }

    private static final class Node {
        private final String name;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private long total;
        private long self;

        private Node(String name) {
            this.name = name;
        }
    }
}
