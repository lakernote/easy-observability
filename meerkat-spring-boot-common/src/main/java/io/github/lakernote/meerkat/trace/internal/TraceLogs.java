package io.github.lakernote.meerkat.trace.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Limits for buffering log lines on running traces.
 * <p>Every running trace buffers its log lines because whether it will be kept (slow or failed) is only known when
 * it ends. Each trace is capped by line count and line length, and all running traces together share one byte budget
 * so a burst of concurrent, chatty requests cannot grow the heap without bound. Lines beyond a limit are counted and
 * reported as omitted.</p>
 */
public final class TraceLogs {
    public static final int DEFAULT_MAX_PER_TRACE = 100;
    public static final int DEFAULT_MAX_MESSAGE_CHARS = 1_000;
    public static final long DEFAULT_MAX_BUFFERED_BYTES = 16L * 1024 * 1024;
    /** Budget a trace takes from the shared pool at a time, so most traces touch the shared counter only twice. */
    static final long RESERVE_BLOCK_BYTES = 8 * 1024;
    private static final AtomicLong BUFFERED_BYTES = new AtomicLong();
    private static volatile Limits limits = new Limits(false, DEFAULT_MAX_PER_TRACE, DEFAULT_MAX_MESSAGE_CHARS,
            DEFAULT_MAX_BUFFERED_BYTES);

    private TraceLogs() {
    }

    /** Turns buffering on with the given limits; called when the log appender is installed. */
    public static void enable(int maxPerTrace, int maxMessageChars, long maxBufferedBytes) {
        limits = new Limits(true, Math.max(1, maxPerTrace), Math.max(80, maxMessageChars),
                Math.max(0, maxBufferedBytes));
    }

    public static void disable() {
        Limits current = limits;
        limits = new Limits(false, current.maxPerTrace, current.maxMessageChars, current.maxBufferedBytes);
    }

    static Limits limits() {
        return limits;
    }

    /** Bytes currently held by running traces; shown in tests and diagnostics. */
    public static long bufferedBytes() {
        return BUFFERED_BYTES.get();
    }

    static boolean tryReserve(long bytes, long maxBufferedBytes) {
        while (true) {
            long current = BUFFERED_BYTES.get();
            if (current + bytes > maxBufferedBytes) return false;
            if (BUFFERED_BYTES.compareAndSet(current, current + bytes)) return true;
        }
    }

    static void release(long bytes) {
        if (bytes > 0) BUFFERED_BYTES.addAndGet(-bytes);
    }

    /** Whether a line logged on this thread now would be kept; lets appenders skip formatting otherwise. */
    public static boolean wanted() {
        return limits.enabled && TraceContext.hasFrame();
    }

    /**
     * Records a line on the trace that is current on this thread. Cheap when no trace is running: one thread-local
     * read. The appender calls this on the logging thread, so it must never throw.
     */
    public static void record(String level, String logger, String message) {
        if (!limits.enabled) return;
        try {
            TraceContext.recordLog(level, logger, message);
        } catch (RuntimeException ignored) {
            // Logging must never fail because of trace bookkeeping.
        }
    }

    record Limits(boolean enabled, int maxPerTrace, int maxMessageChars, long maxBufferedBytes) {
    }
}
