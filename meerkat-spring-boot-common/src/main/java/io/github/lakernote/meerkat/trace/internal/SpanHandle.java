package io.github.lakernote.meerkat.trace.internal;

/** A child span whose lifetime is owned by an integration callback rather than the current thread frame. */
public interface SpanHandle extends AutoCloseable {
    String spanId();

    String parentSpanId();

    default void markError(BoundedThrowableFormatter.FailureEvidence failure) { }

    default void markSlow() { }

    /** Returns where to record request data for this span, or {@code null} when the trace is not being captured. */
    default CaptureTarget captureTarget() {
        return null;
    }

    @Override
    void close();
}
