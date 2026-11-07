package io.github.lakernote.meerkat.trace.internal;

/**
 * A captured value rendered only when the captured trace is snapshotted, such as a response body or result rows
 * that are still being read after their span has closed.
 */
@FunctionalInterface
public interface CaptureValue {
    /** Returns at most about {@code maxChars} characters; must not throw. */
    String render(int maxChars);
}
