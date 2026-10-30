package io.github.lakernote.observability.trace;

import java.util.List;

/** Small structured trace fixtures shared by store and controller tests. */
public final class TraceNodeFixtures {
    private TraceNodeFixtures() { }

    public static List<TraceNode> named(String name) {
        return List.of(new TraceNode("Other", name, "", 0, 0L, false, false, false, "", "", -1, List.of()));
    }

    public static List<String> names(List<TraceNode> nodes) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        collectNames(nodes, names);
        return List.copyOf(names);
    }

    private static void collectNames(List<TraceNode> nodes, List<String> names) {
        for (TraceNode node : nodes) {
            names.add(node.name());
            collectNames(node.children(), names);
        }
    }
}
