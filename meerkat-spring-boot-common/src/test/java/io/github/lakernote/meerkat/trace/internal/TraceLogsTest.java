package io.github.lakernote.meerkat.trace.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceLogsTest {
    @AfterEach
    void reset() {
        TraceContext.clear();
        TraceLogs.disable();
    }

    @Test
    void keepsLinesOfSlowTracesWithTheirStep() {
        TraceLogs.enable(10, 200, 1_000_000);
        long before = TraceLogs.bufferedBytes();
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        TraceLogs.record("INFO", "com.example.Orders", "loading order 7");
        try (ScopedSpan ignored = TraceContext.startScopedSpan("SELECT orders", SpanKind.DATABASE, "")) {
            assertThat(TraceLogs.wanted()).isTrue();
            TraceLogs.record("WARN", "com.example.Repo", "slow query");
        }
        assertThat(TraceLogs.bufferedBytes()).isGreaterThan(before);
        TraceContext.markSlow("SLOW_SQL");

        List<TraceLog> logs = complete();

        assertThat(logs).extracting(TraceLog::level).containsExactly("INFO", "WARN");
        assertThat(logs).extracting(TraceLog::step).containsExactly("GET /orders", "SELECT orders");
        assertThat(logs.get(0).thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(logs.get(0).logger()).isEqualTo("com.example.Orders");
        assertThat(TraceLogs.bufferedBytes()).isEqualTo(before);
    }

    @Test
    void dropsLinesOfFastSuccessfulTracesAndReleasesTheBudget() {
        TraceLogs.enable(10, 200, 1_000_000);
        long before = TraceLogs.bufferedBytes();
        TraceContext.startRoot("GET /fast", SpanKind.HTTP);
        TraceLogs.record("INFO", "x", "hello");

        assertThat(complete()).isEmpty();
        assertThat(TraceLogs.bufferedBytes()).isEqualTo(before);
    }

    @Test
    void capsLinesPerTraceAndTruncatesLongMessages() {
        TraceLogs.enable(2, 80, 1_000_000);
        TraceContext.startRoot("GET /chatty", SpanKind.HTTP);
        TraceLogs.record("INFO", "x", "a".repeat(500));
        TraceLogs.record("INFO", "x", null);
        TraceLogs.record("INFO", "x", "third");
        TraceLogs.record("INFO", "x", "fourth");
        TraceContext.markSlow("SLOW");

        List<TraceLog> logs = complete();

        assertThat(logs).hasSize(3);
        assertThat(logs.get(0).message()).hasSize(81).endsWith("…");
        assertThat(logs.get(1).message()).isEmpty();
        assertThat(logs.get(2).level()).isEqualTo("OMITTED");
        assertThat(logs.get(2).message()).startsWith("2 more log lines omitted");
    }

    @Test
    void sharedBudgetBoundsMemoryAcrossTraces() {
        TraceLogs.enable(100, 200, TraceLogs.bufferedBytes());
        TraceContext.startRoot("GET /budget", SpanKind.HTTP);
        TraceLogs.record("INFO", "x", "does not fit");
        TraceContext.markSlow("SLOW");

        List<TraceLog> logs = complete();

        assertThat(logs).singleElement().satisfies(line -> assertThat(line.level()).isEqualTo("OMITTED"));
    }

    @Test
    void ignoresLinesWhenDisabledOrOutsideATrace() {
        TraceLogs.disable();
        TraceContext.startRoot("GET /off", SpanKind.HTTP);
        assertThat(TraceLogs.wanted()).isFalse();
        TraceLogs.record("INFO", "x", "ignored");
        TraceContext.markSlow("SLOW");
        assertThat(complete()).isEmpty();

        TraceLogs.enable(10, 200, 1_000_000);
        assertThat(TraceLogs.wanted()).isFalse();
        TraceLogs.record("INFO", "x", "no trace");
    }

    @Test
    void discardedTracesReturnTheirBudget() {
        TraceLogs.enable(10, 200, 1_000_000);
        long before = TraceLogs.bufferedBytes();
        TraceContext.startRoot("GET /discard", SpanKind.HTTP);
        TraceLogs.record("INFO", "x", "buffered");
        RootTraceHandle handle = TraceContext.currentRootTraceHandle();
        assertThat(TraceLogs.bufferedBytes()).isGreaterThan(before);

        handle.trace().discard();

        assertThat(TraceLogs.bufferedBytes()).isEqualTo(before);
        TraceLogs.record("INFO", "x", "after completion");
        assertThat(handle.trace().logs()).isEmpty();
    }

    @Test
    void estimatesLineSize() {
        assertThat(new TraceLog(0, "INFO", "l", "t", "s", "m").estimatedBytes()).isPositive();
        assertThat(new TraceLog(0, null, null, null, null, null).estimatedBytes()).isPositive();
    }

    private static List<TraceLog> complete() {
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.stopRoot(Long.MAX_VALUE, "test", null, completion::set);
        return completion.get().logs();
    }
}
