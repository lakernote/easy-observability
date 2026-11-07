package io.github.lakernote.meerkat.trace.internal;

import java.util.concurrent.atomic.AtomicBoolean;

/** A child span that is closed by an external lifecycle, such as a transaction completion callback. */
public final class DetachedSpan implements SpanHandle {
    static final DetachedSpan NOOP = new DetachedSpan(null, null);
    private final TraceTree trace;
    private final Span span;
    private final AtomicBoolean stopped = new AtomicBoolean();

    DetachedSpan(TraceTree trace, Span span) {
        this.trace = trace;
        this.span = span;
    }

    static DetachedSpan of(TraceTree trace, Span span) {
        return span == null ? NOOP : new DetachedSpan(trace, span);
    }

    @Override
    public String spanId() {
        return span == null ? "" : span.spanId();
    }

    @Override
    public String parentSpanId() {
        return span == null ? "" : span.parentSpanId();
    }

    @Override
    public void markError(BoundedThrowableFormatter.FailureEvidence failure) {
        if (span != null) trace.markSpanError(span, failure);
    }

    @Override
    public void markSlow() {
        if (span != null) trace.markSpanSlow(span);
    }

    @Override
    public CaptureTarget captureTarget() {
        return CaptureTarget.of(trace, span);
    }

    @Override
    public void close() {
        if (span != null && stopped.compareAndSet(false, true)) trace.stopSpan(span);
    }
}
