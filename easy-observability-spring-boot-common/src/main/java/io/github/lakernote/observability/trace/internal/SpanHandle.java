package io.github.lakernote.observability.trace.internal;

/** A child span whose lifetime is owned by an integration callback rather than the current thread frame. */
public interface SpanHandle extends AutoCloseable {
    String spanId();

    String parentSpanId();

    default void markError(BoundedThrowableFormatter.FailureEvidence failure) { }

    default void markSlow() { }

    @Override
    void close();
}
