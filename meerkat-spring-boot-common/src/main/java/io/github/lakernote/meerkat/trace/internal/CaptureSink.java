package io.github.lakernote.meerkat.trace.internal;

import java.util.List;

/** Receives a captured root trace once, with captured request data included in {@code capturedNodes}. */
@FunctionalInterface
public interface CaptureSink {
    void completed(TraceCompletion completion, List<TraceNode> capturedNodes);
}
