package io.github.lakernote.meerkat.trace.internal;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedThrowableFormatterTest {

    @Test
    void shouldBoundCauseDepthFrameCountAndCharacters() {
        Throwable failure = null;
        StackTraceElement[] frames = IntStream.range(0, 1_000)
                .mapToObj(index -> new StackTraceElement("Example" + index, "call", "Example.java", index + 1))
                .toArray(StackTraceElement[]::new);
        for (int depth = 19; depth >= 0; depth--) {
            failure = new IllegalStateException("cause-" + depth, failure);
            failure.setStackTrace(frames);
        }

        String formatted = BoundedThrowableFormatter.format(failure);

        assertThat(formatted)
                .startsWith(IllegalStateException.class.getName() + ": cause-0")
                .contains("cause-7", "additional causes omitted")
                .doesNotContain("cause-8")
                .hasSizeLessThanOrEqualTo(8_000);
        assertThat(occurrences(formatted, "\tat ")).isLessThanOrEqualTo(BoundedThrowableFormatter.MAX_STACK_FRAMES);
        assertThat(occurrences(formatted, IllegalStateException.class.getName()))
                .isLessThanOrEqualTo(BoundedThrowableFormatter.MAX_CAUSE_DEPTH);
    }

    @Test
    void shouldMarkCharacterAndCauseCycleTruncation() {
        RuntimeException huge = new RuntimeException("x".repeat(8_000 * 2));
        String hugeResult = BoundedThrowableFormatter.format(huge);
        String cycleResult = BoundedThrowableFormatter.format(new CyclicFailure());

        assertThat(hugeResult)
                .contains("throwable evidence truncated")
                .hasSizeLessThanOrEqualTo(8_000);
        assertThat(cycleResult).contains("cycle detected");
    }

    @Test
    void shouldCaptureStructureWithoutCopyingThrowableMessages() {
        IllegalStateException cause = new IllegalStateException("cause-secret");
        RuntimeException failure = new RuntimeException("driver-secret", cause);
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("Example", "call", "driver-secret.java", 42)
        });

        BoundedThrowableFormatter.FailureEvidence evidence =
                BoundedThrowableFormatter.captureWithoutMessages(failure, "Dependency call failed");

        assertThat(evidence.type()).isEqualTo(RuntimeException.class.getName());
        assertThat(evidence.message()).isEqualTo("Dependency call failed");
        assertThat(evidence.stackTrace())
                .contains(RuntimeException.class.getName(), IllegalStateException.class.getName(), "\tat Example.call(line 42)")
                .doesNotContain("driver-secret", "cause-secret", ".java");
    }

    private static int occurrences(String text, String pattern) {
        return text.split(java.util.regex.Pattern.quote(pattern), -1).length - 1;
    }

    private static final class CyclicFailure extends RuntimeException {
        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
