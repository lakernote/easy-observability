package io.github.lakernote.observability.stats.internal;

import io.github.lakernote.observability.stats.internal.OperationStats.Category;
import io.github.lakernote.observability.stats.internal.OperationStats.Row;
import io.github.lakernote.observability.stats.internal.OperationStats.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class OperationStatsArchiveTest {
    private static final long MINUTE = 60_000L;
    private static final long DAY = 24 * 60 * MINUTE;
    private static final long MB = 1024 * 1024;
    @TempDir
    Path directory;
    private final AtomicLong clock = new AtomicLong(Instant.parse("2026-11-05T10:05:00Z").toEpochMilli());

    @Test
    void flushesCompletedHoursOnceAndCountsEachCallOnce() throws Exception {
        OperationStats stats = stats(100);
        stats.record(Category.HTTP, "/orders · GET", ms(10), false);
        stats.flush(false);
        assertThat(Files.exists(directory.resolve("20261105.tsv"))).isFalse();

        clock.addAndGet(57 * MINUTE);
        stats.record(Category.HTTP, "/orders · GET", ms(30), true);
        stats.flush(false);
        stats.flush(false);

        List<String> lines = Files.readAllLines(directory.resolve("20261105.tsv"), StandardCharsets.UTF_8);
        assertThat(lines).first().isEqualTo(OperationStatsArchive.HEADER);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith(Instant.parse("2026-11-05T10:00:00Z").toEpochMilli() + "\tHTTP\t0\t");
        assertThat(row(stats, Window.TODAY, Category.HTTP))
                .extracting(Row::count, Row::errors, Row::maxMs).containsExactly(2L, 1L, 30.0);
        // 10:05 falls outside the six 10-minute slices ending at 11:02.
        assertThat(row(stats, Window.HOUR, Category.HTTP).count()).isEqualTo(1);
        assertThat(stats.snapshot(Window.TODAY).persistent()).isTrue();
        assertThat(stats.snapshot(Window.TODAY).since()).isEqualTo(Instant.parse("2026-11-05T00:00:00Z"));
    }

    @Test
    void restartsKeepTheDayAndSumRepeatedHours() {
        OperationStats first = stats(100);
        first.record(Category.SQL, "SELECT * FROM t WHERE id IN (?…)", "SELECT * FROM t WHERE id IN", ms(5), false);
        first.close();

        OperationStats second = stats(100);
        assertThat(row(second, Window.TODAY, Category.SQL))
                .extracting(Row::count, Row::traceQuery).containsExactly(1L, "SELECT * FROM t WHERE id IN");
        second.record(Category.SQL, "SELECT * FROM t WHERE id IN (?…)", ms(7), false);
        assertThat(row(second, Window.TODAY, Category.SQL).count()).isEqualTo(2);
        second.close();

        OperationStats third = stats(100);
        assertThat(row(third, Window.TODAY, Category.SQL))
                .extracting(Row::count, Row::maxMs, Row::totalMs).containsExactly(2L, 7.0, 12.0);
        assertThat(third.snapshot(Window.HOUR).categories()).allSatisfy(c -> assertThat(c.rows()).isEmpty());
    }

    @Test
    void weekAndMonthWindowsReachEarlierDays() {
        OperationStats first = stats(100);
        first.record(Category.REDIS, "GET", ms(1), false);
        first.close();

        clock.addAndGet(3 * DAY);
        OperationStats later = stats(100);
        later.record(Category.REDIS, "GET", ms(3), false);
        assertThat(row(later, Window.TODAY, Category.REDIS).count()).isEqualTo(1);
        assertThat(row(later, Window.WEEK, Category.REDIS).count()).isEqualTo(2);
        assertThat(later.snapshot(Window.WEEK).since()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
        later.close();

        clock.addAndGet(10 * DAY);
        OperationStats muchLater = stats(100);
        assertThat(muchLater.snapshot(Window.WEEK).categories()).allSatisfy(c -> assertThat(c.rows()).isEmpty());
        assertThat(row(muchLater, Window.MONTH, Category.REDIS).count()).isEqualTo(2);
    }

    @Test
    void longWindowsFoldNamesBeyondTheKeyLimitIntoOverflow() {
        OperationStats first = stats(2);
        first.record(Category.HTTP, "a", ms(30), false);
        first.record(Category.HTTP, "b", ms(20), false);
        first.close();
        clock.addAndGet(DAY);
        OperationStats second = stats(2);
        second.record(Category.HTTP, "c", ms(10), false);
        second.record(Category.HTTP, "d", ms(5), false);

        assertThat(category(second, Window.WEEK, Category.HTTP).rows())
                .extracting(Row::name, Row::count, Row::traceQuery)
                .containsExactlyInAnyOrder(tuple("a", 1L, "a"), tuple(OperationStats.OVERFLOW_NAME, 3L, ""));
    }

    @Test
    void deletesDaysPastRetentionAndOldestDaysBeyondTheSizeBudget() throws Exception {
        LocalDate today = LocalDate.parse("2026-11-05");
        for (String name : List.of("20261001.tsv", "20261010.tsv", "20261104.tsv", "notes.tsv")) {
            Files.writeString(directory.resolve(name), "x".repeat(600));
        }
        OperationStatsArchive archive = new OperationStatsArchive(directory, 500, ZoneOffset.UTC);
        long hour = Instant.parse("2026-11-05T10:00:00Z").toEpochMilli();

        assertThat(archive.append(hour, List.of(hourRow("a")), today)).isTrue();

        assertThat(directory.resolve("20261001.tsv")).doesNotExist();
        assertThat(directory.resolve("20261010.tsv")).doesNotExist();
        assertThat(directory.resolve("20261104.tsv")).doesNotExist();
        assertThat(directory.resolve("20261105.tsv")).exists();
        assertThat(directory.resolve("notes.tsv")).exists();
    }

    @Test
    void escapesSeparatorsAndSkipsTornLines() throws Exception {
        OperationStatsArchive archive = new OperationStatsArchive(directory, MB, ZoneOffset.UTC);
        LocalDate today = LocalDate.parse("2026-11-05");
        long hour = Instant.parse("2026-11-05T10:00:00Z").toEpochMilli();
        archive.append(hour, List.of(hourRow("tab\there\\back")), today);
        Files.writeString(directory.resolve("20261105.tsv"), "garbage\n1\tHTTP\tx\t1\t1\t0:1\tbad\tbad\n"
                + "1\tNOPE\t0\t1\t1\t0:1\tbad\tbad\n1\tHTTP\t0\t1\t1\t99:1\tbad\tbad\n1\tHTTP\t0\t1\t1\t0:1\tcut",
                java.nio.file.StandardOpenOption.APPEND);

        var entries = archive.read(today, today).get(Category.HTTP);
        assertThat(entries).containsOnlyKeys("tab\there\\back");
        assertThat(entries.get("tab\there\\back").cells()[OperationStats.COUNT]).isEqualTo(1);
    }

    @Test
    void clearDeletesSavedDays() {
        OperationStats stats = stats(100);
        stats.record(Category.JOB, "nightly", ms(1), false);
        stats.close();
        stats.clear();

        assertThat(directory.resolve("20261105.tsv")).doesNotExist();
        assertThat(stats(100).snapshot(Window.MONTH).categories()).allSatisfy(c -> assertThat(c.rows()).isEmpty());
    }

    @Test
    void unusableDirectoryKeepsStatisticsInMemory() throws Exception {
        Path notADirectory = Files.writeString(directory.resolve("file"), "x");
        OperationStatsArchive archive = new OperationStatsArchive(notADirectory.resolve("stats"), MB, ZoneOffset.UTC);
        OperationStats stats = new OperationStats(100, archive, clock::get, ZoneOffset.UTC);
        stats.record(Category.HTTP, "a", ms(1), false);
        stats.close();

        assertThat(archive.available()).isFalse();
        assertThat(stats.snapshot(Window.TODAY).persistent()).isFalse();
        assertThat(row(stats, Window.TODAY, Category.HTTP).count()).isEqualTo(1);
        assertThat(new OperationStatsArchive(null, MB, ZoneOffset.UTC).available()).isFalse();
    }

    private OperationStats stats(int maxKeys) {
        return new OperationStats(maxKeys, new OperationStatsArchive(directory, MB, ZoneOffset.UTC), clock::get,
                ZoneOffset.UTC);
    }

    private static OperationStatsArchive.HourRow hourRow(String name) {
        long[] cells = new long[OperationStats.CELLS];
        cells[OperationStats.TOTAL] = 5;
        cells[OperationStats.MAX] = 5;
        cells[OperationStats.FIRST_BUCKET] = 1;
        cells[OperationStats.COUNT] = 1;
        return new OperationStatsArchive.HourRow(Category.HTTP, name, name, cells);
    }

    private static Row row(OperationStats stats, Window window, Category category) {
        return category(stats, window, category).rows().get(0);
    }

    private static OperationStats.CategorySnapshot category(OperationStats stats, Window window, Category category) {
        return stats.snapshot(window).categories().stream().filter(c -> c.category() == category).findFirst()
                .orElseThrow();
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
