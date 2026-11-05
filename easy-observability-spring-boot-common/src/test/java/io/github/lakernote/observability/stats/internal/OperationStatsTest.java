package io.github.lakernote.observability.stats.internal;

import io.github.lakernote.observability.stats.internal.OperationStats.Category;
import io.github.lakernote.observability.stats.internal.OperationStats.Row;
import io.github.lakernote.observability.stats.internal.OperationStats.Window;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class OperationStatsTest {
    private static final long MINUTE = 60_000L;
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final OperationStats stats = new OperationStats(100, clock::get);

    @Test
    void aggregatesCountErrorsMeanMaxAndTotalPerOperation() {
        stats.record(Category.HTTP, "/orders · GET", ms(10), false);
        stats.record(Category.HTTP, "/orders · GET", ms(30), true);
        stats.record(Category.HTTP, "/users · GET", ms(5), false);

        var http = category(Window.TODAY, Category.HTTP);
        assertThat(http.count()).isEqualTo(3);
        assertThat(http.errors()).isEqualTo(1);
        assertThat(http.totalMs()).isEqualTo(45.0);
        Row orders = http.rows().get(0);
        assertThat(orders.name()).isEqualTo("/orders · GET");
        assertThat(orders.count()).isEqualTo(2);
        assertThat(orders.errors()).isEqualTo(1);
        assertThat(orders.meanMs()).isEqualTo(20.0);
        assertThat(orders.maxMs()).isEqualTo(30.0);
        assertThat(orders.totalMs()).isEqualTo(40.0);
        assertThat(http.rows()).extracting(Row::name).containsExactly("/orders · GET", "/users · GET");
        assertThat(category(Window.TODAY, Category.SQL).rows()).isEmpty();
    }

    @Test
    void estimatesP95WithinHistogramResolutionAndNeverAboveMax() {
        for (int i = 1; i <= 100; i++) stats.record(Category.SQL, "select ?", ms(i), false);
        stats.record(Category.REDIS, "GET", 3_000, false);

        Row sql = category(Window.TODAY, Category.SQL).rows().get(0);
        assertThat(sql.p95Ms()).isCloseTo(95.0, within(95.0 * 0.2));
        assertThat(sql.p95Ms()).isLessThanOrEqualTo(sql.maxMs());
        Row redis = category(Window.TODAY, Category.REDIS).rows().get(0);
        assertThat(redis.p95Ms()).isLessThanOrEqualTo(redis.maxMs()).isEqualTo(0.003);
    }

    @Test
    void bucketsCoverSubMillisecondToTensOfSeconds() {
        assertThat(OperationStats.bucket(0)).isZero();
        assertThat(OperationStats.bucket(9_999)).isZero();
        assertThat(OperationStats.bucket(10_000)).isEqualTo(1);
        assertThat(OperationStats.bucket(ms(1))).isBetween(13, 14);
        assertThat(OperationStats.bucket(TimeUnit.HOURS.toNanos(1))).isEqualTo(45);
        assertThat(OperationStats.bucket(ms(1))).isLessThan(OperationStats.bucket(ms(2)));
    }

    @Test
    void hourWindowDropsSlicesOlderThanSixtyMinutesButTodayKeepsThem() {
        stats.record(Category.REMOTE, "GET api /a", ms(100), false);
        clock.addAndGet(30 * MINUTE);
        stats.record(Category.REMOTE, "GET api /a", ms(200), true);

        assertThat(category(Window.HOUR, Category.REMOTE).count()).isEqualTo(2);

        clock.addAndGet(40 * MINUTE);
        Row lastHour = category(Window.HOUR, Category.REMOTE).rows().get(0);
        assertThat(lastHour.count()).isEqualTo(1);
        assertThat(lastHour.maxMs()).isEqualTo(200.0);
        assertThat(category(Window.TODAY, Category.REMOTE).count()).isEqualTo(2);

        // 2023-11-14T22:13Z + 130 minutes is the next UTC day, which starts empty.
        clock.addAndGet(60 * MINUTE);
        assertThat(category(Window.HOUR, Category.REMOTE).rows()).isEmpty();
        assertThat(category(Window.TODAY, Category.REMOTE).rows()).isEmpty();
        stats.record(Category.REMOTE, "GET api /a", ms(1), false);
        assertThat(category(Window.HOUR, Category.REMOTE).count()).isEqualTo(1);
        assertThat(category(Window.TODAY, Category.REMOTE).count()).isEqualTo(1);
    }

    @Test
    void reportsWindowStartClampedToStartup() {
        long started = clock.get();
        assertThat(stats.snapshot(Window.HOUR).since()).isEqualTo(Instant.ofEpochMilli(started));
        clock.addAndGet(3 * 60 * MINUTE);
        var hour = stats.snapshot(Window.HOUR);
        assertThat(hour.since()).isAfter(Instant.ofEpochMilli(clock.get() - 61 * MINUTE))
                .isBeforeOrEqualTo(Instant.ofEpochMilli(clock.get() - 50 * MINUTE));
        // Without an archive the day windows cover only the two hours kept in memory.
        assertThat(stats.snapshot(Window.TODAY).since())
                .isEqualTo(Instant.ofEpochMilli(Math.floorDiv(clock.get(), 3_600_000L) * 3_600_000L - 3_600_000L));
        assertThat(stats.snapshot(Window.TODAY).persistent()).isFalse();
        assertThat(hour.generatedAt()).isEqualTo(Instant.ofEpochMilli(clock.get()));
    }

    @Test
    void mergesOperationsBeyondTheKeyLimitIntoOneOverflowRowPerCategory() {
        OperationStats limited = new OperationStats(2, clock::get);
        limited.record(Category.HTTP, "a", ms(1), false);
        limited.record(Category.SQL, "b", ms(1), false);
        limited.record(Category.HTTP, "c", ms(1), false);
        limited.record(Category.HTTP, "d", ms(1), false);
        limited.record(Category.SQL, "e", ms(1), false);
        limited.record(Category.HTTP, "a", ms(1), false);

        var snapshot = limited.snapshot(Window.TODAY);
        assertThat(snapshot.keys()).isEqualTo(2);
        assertThat(snapshot.maxKeys()).isEqualTo(2);
        assertThat(snapshot.categories().get(0).rows())
                .extracting(Row::name, Row::count)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("a", 2L),
                        org.assertj.core.groups.Tuple.tuple(OperationStats.OVERFLOW_NAME, 2L));
        assertThat(category(limited, Window.TODAY, Category.SQL).rows()).extracting(Row::name)
                .containsExactlyInAnyOrder("b", OperationStats.OVERFLOW_NAME);
        assertThat(category(limited, Window.TODAY, Category.SQL).rows())
                .filteredOn(row -> row.name().equals(OperationStats.OVERFLOW_NAME))
                .singleElement().extracting(Row::traceQuery).isEqualTo("");
    }

    @Test
    void keepsTheFirstTraceQueryAndDefaultsItToTheName() {
        stats.record(Category.REMOTE, "GET api /orders/{id}", "/orders/{id}", ms(1), false);
        stats.record(Category.REMOTE, "GET api /orders/{id}", "ignored", ms(1), false);
        stats.record(Category.HTTP, "/a · GET", ms(1), false);

        assertThat(category(Window.TODAY, Category.REMOTE).rows()).singleElement()
                .extracting(Row::traceQuery).isEqualTo("/orders/{id}");
        assertThat(category(Window.TODAY, Category.HTTP).rows()).singleElement()
                .extracting(Row::traceQuery).isEqualTo("/a · GET");
    }

    @Test
    void normalizesNamesAndIgnoresMissingCategory() {
        stats.record(null, "ignored", 1, false);
        stats.record(Category.JOB, null, 1, false);
        stats.record(Category.JOB, "  ", 1, false);
        stats.record(Category.JOB, "line\r\nbreak", -5, false);
        stats.record(Category.JOB, "x".repeat(2_000), 1, false);

        var job = category(Window.TODAY, Category.JOB);
        assertThat(job.rows()).extracting(Row::name)
                .contains("-", "line  break")
                .allSatisfy(name -> assertThat(name).hasSizeLessThanOrEqualTo(OperationStats.MAX_NAME_LENGTH));
        assertThat(job.rows()).filteredOn(row -> row.name().equals("-")).singleElement()
                .extracting(Row::count).isEqualTo(2L);
        assertThat(stats.snapshot(Window.TODAY).categories()).extracting(c -> c.count()).containsOnly(0L, 4L);
    }

    @Test
    void countsExactlyUnderConcurrentWriters() throws InterruptedException {
        int threads = 8;
        int perThread = 20_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int worker = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    stats.record(Category.REDIS, "GET", (i % 100) * 1_000L, i % 10 == 0);
                    if (worker == 0 && i % 5_000 == 0) clock.addAndGet(10 * MINUTE);
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) worker.join();

        Row all = category(Window.TODAY, Category.REDIS).rows().get(0);
        assertThat(all.count()).isEqualTo((long) threads * perThread);
        assertThat(all.errors()).isEqualTo((long) threads * perThread / 10);
        assertThat(all.maxMs()).isEqualTo(0.099);
    }

    @Test
    void clearRemovesAllOperationsAndFreesKeys() {
        stats.record(Category.HTTP, "a", ms(1), false);
        stats.clear();
        assertThat(stats.snapshot(Window.TODAY).keys()).isZero();
        assertThat(category(Window.TODAY, Category.HTTP).rows()).isEmpty();
    }

    @Test
    void parsesWindowNames() {
        assertThat(Window.parse(null)).isEqualTo(Window.HOUR);
        assertThat(Window.parse(" 1h ")).isEqualTo(Window.HOUR);
        assertThat(Window.parse("TODAY")).isEqualTo(Window.TODAY);
        assertThat(Window.parse("7d")).isEqualTo(Window.WEEK);
        assertThat(Window.parse("30d")).isEqualTo(Window.MONTH);
        assertThatThrownBy(() -> Window.parse("all")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Window.parse("week")).isInstanceOf(IllegalArgumentException.class);
    }

    private OperationStats.CategorySnapshot category(Window window, Category category) {
        return category(stats, window, category);
    }

    private static OperationStats.CategorySnapshot category(OperationStats stats, Window window, Category category) {
        return stats.snapshot(window).categories().stream().filter(c -> c.category() == category).findFirst()
                .orElseThrow();
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
