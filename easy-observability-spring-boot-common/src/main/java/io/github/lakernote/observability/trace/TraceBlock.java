package io.github.lakernote.observability.trace;

import java.util.function.Supplier;

/** Adds a span around one explicitly selected code block. */
public final class TraceBlock {
    private TraceBlock() {
    }

    /** Runs a value-returning block under a {@link SpanKind#CODE} span. */
    public static <T> T call(String spanName, Supplier<T> supplier) {
        return call(spanName, SpanKind.CODE, supplier);
    }

    public static <T> T call(String spanName, SpanKind kind, Supplier<T> supplier) {
        return call(spanName, kind, "", supplier);
    }

    public static <T> T call(String spanName, SpanKind kind, String tag, Supplier<T> supplier) {
        TraceContext.ScopedSpan span = startSpan(spanName, kind, tag);
        try {
            return supplier.get();
        } catch (Throwable failure) {
            markFailure(span, failure);
            throw TraceBlock.<RuntimeException>propagate(failure);
        } finally {
            closeSpan(span);
        }
    }

    /** Runs a block under a {@link SpanKind#CODE} span. */
    public static void run(String spanName, Runnable runnable) {
        run(spanName, SpanKind.CODE, runnable);
    }

    public static void run(String spanName, SpanKind kind, Runnable runnable) {
        run(spanName, kind, "", runnable);
    }

    public static void run(String spanName, SpanKind kind, String tag, Runnable runnable) {
        TraceContext.ScopedSpan span = startSpan(spanName, kind, tag);
        try {
            runnable.run();
        } catch (Throwable failure) {
            markFailure(span, failure);
            throw TraceBlock.<RuntimeException>propagate(failure);
        } finally {
            closeSpan(span);
        }
    }

    private static TraceContext.ScopedSpan startSpan(String spanName, SpanKind kind, String tag) {
        try {
            return TraceContext.startExplicitScopedSpan(spanName, kind, tag);
        } catch (Throwable monitoringFailure) {
            TraceContext.clear();
            return null;
        }
    }

    private static void markFailure(TraceContext.ScopedSpan span, Throwable failure) {
        if (span == null) return;
        try {
            span.markTraceError(TraceContext.CODE_ERROR_REASON, failure);
        } catch (Throwable monitoringFailure) {
            TraceContext.clear();
        }
    }

    private static void closeSpan(TraceContext.ScopedSpan span) {
        if (span == null) return;
        try {
            span.close();
        } catch (Throwable monitoringFailure) {
            TraceContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
