package io.github.lakernote.meerkat.trace.internal;

/** Bounds the captured text of one trace snapshot, per value and in total. */
final class CaptureBudget {
    static final String OMITTED = "[omitted: capture size limit for this trace reached]";
    private final int maxValueChars;
    private long remainingChars;

    CaptureBudget(int maxValueChars, long maxTraceChars) {
        this.maxValueChars = Math.max(64, maxValueChars);
        this.remainingChars = Math.max(0, maxTraceChars);
    }

    String render(Object value) {
        if (remainingChars <= 0) return OMITTED;
        int limit = (int) Math.min(maxValueChars, remainingChars);
        String text;
        try {
            text = value instanceof CaptureValue lazy ? lazy.render(limit) : String.valueOf(value);
        } catch (RuntimeException | LinkageError failure) {
            text = "[capture failed: " + failure.getClass().getSimpleName() + "]";
        }
        if (text == null) text = "null";
        if (text.length() > limit) {
            text = text.substring(0, limit) + "… [truncated, " + text.length() + " chars]";
        }
        remainingChars -= text.length();
        return text;
    }
}
