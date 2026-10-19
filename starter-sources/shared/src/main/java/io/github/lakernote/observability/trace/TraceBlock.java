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
        boolean spanStarted = startSpan(spanName, kind, tag);
        try {
            return supplier.get();
        } finally {
            stopSpan(spanStarted);
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
        boolean spanStarted = startSpan(spanName, kind, tag);
        try {
            runnable.run();
        } finally {
            stopSpan(spanStarted);
        }
    }

    private static boolean startSpan(String spanName, SpanKind kind, String tag) {
        try {
            TraceContext.addSpan(spanName, kind, tag);
            return true;
        } catch (Throwable monitoringFailure) {
            TraceContext.clear();
            return false;
        }
    }

    private static void stopSpan(boolean spanStarted) {
        if (!spanStarted) return;
        try {
            TraceContext.stopSpan();
        } catch (Throwable monitoringFailure) {
            TraceContext.clear();
        }
    }
}
