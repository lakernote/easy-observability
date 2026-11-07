package io.github.lakernote.meerkat.trace.internal;

import java.util.List;

/**
 * Immutable result of a finished root trace. {@code tree}, {@code nodes} and {@code logs} are only populated when the
 * trace was slow, failed, explicitly required its tree or was captured, so the common successful path never renders
 * anything.
 */
public record TraceCompletion(String tree, List<TraceNode> nodes, long costMs, boolean slow, boolean error,
                              List<String> reasons, String errorType, String errorMessage, String stackTrace,
                              List<TraceLog> logs) {
    public TraceCompletion(String tree, List<TraceNode> nodes, long costMs, boolean slow, boolean error,
                           List<String> reasons, String errorType, String errorMessage, String stackTrace) {
        this(tree, nodes, costMs, slow, error, reasons, errorType, errorMessage, stackTrace, List.of());
    }

    public TraceCompletion {
        logs = logs == null ? List.of() : List.copyOf(logs);
        tree = tree == null ? "" : tree;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        errorType = errorType == null ? "" : errorType;
        errorMessage = errorMessage == null ? "" : errorMessage;
        stackTrace = stackTrace == null ? "" : stackTrace;
    }
}
