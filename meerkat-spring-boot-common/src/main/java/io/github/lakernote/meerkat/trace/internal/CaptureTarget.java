package io.github.lakernote.meerkat.trace.internal;

/**
 * Attaches captured request data to one span of a trace that is being captured. Obtained only while capture is
 * active, so integrations skip all capture work when they receive {@code null}.
 */
public final class CaptureTarget {
    private final TraceTree trace;
    private final Span span;

    CaptureTarget(TraceTree trace, Span span) {
        this.trace = trace;
        this.span = span;
    }

    static CaptureTarget of(TraceTree trace, Span span) {
        return trace == null || span == null || !trace.capturing() ? null : new CaptureTarget(trace, span);
    }

    /** False once the trace has completed; later values are ignored. */
    public boolean capturing() {
        return trace.capturing();
    }

    public CaptureLimits limits() {
        return trace.captureLimits();
    }

    /** @param value a {@link String}, a {@link CaptureValue} rendered at completion, or any object */
    public void capture(String key, Object value) {
        if (key == null || key.isBlank()) return;
        trace.captureSpan(span, key, value);
    }
}
