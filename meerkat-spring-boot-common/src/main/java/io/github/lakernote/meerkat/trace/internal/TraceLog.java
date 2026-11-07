package io.github.lakernote.meerkat.trace.internal;

/**
 * One application log line written while a trace was running.
 *
 * @param offsetMs milliseconds since the trace started
 * @param step     name of the span that was current when the line was logged
 */
public record TraceLog(long offsetMs, String level, String logger, String thread, String step, String message) {
    public TraceLog {
        offsetMs = Math.max(0, offsetMs);
        level = level == null ? "" : level;
        logger = logger == null ? "" : logger;
        thread = thread == null ? "" : thread;
        step = step == null ? "" : step;
        message = message == null ? "" : message;
    }

    /** Rough heap footprint, consistent with the trace store's estimate: object overhead plus two bytes per char. */
    long estimatedBytes() {
        return 96 + 2L * (level.length() + logger.length() + thread.length() + step.length() + message.length());
    }
}
