package io.github.lakernote.meerkat.trace.internal;

/** Size limits of one captured request, read by every capture hook through its {@link CaptureTarget}. */
public record CaptureLimits(int maxBodyBytes, int maxResultRows, int maxValueChars, long maxTraceChars) {
    public CaptureLimits {
        maxBodyBytes = Math.max(0, maxBodyBytes);
        maxResultRows = Math.max(0, maxResultRows);
        maxValueChars = Math.max(64, maxValueChars);
        maxTraceChars = Math.max(0, maxTraceChars);
    }
}
