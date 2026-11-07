package io.github.lakernote.meerkat.diagnostics.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.management.JMException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeapDiagnosticsTest {
    private static final String FIRST = """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:          1000          64000  [B (java.base@17)
               2:           500          12000  java.lang.String (java.base@17)
               3:            10            480  com.example.Cache
            Total          1510          76480
            """;
    private static final String SECOND = """
               1:          3000         192000  [B (java.base@17)
               2:           400           9600  java.lang.String (java.base@17)
               3:            50           2400  com.example.Leak
            Total          3450         204000
            """;

    @TempDir
    Path directory;

    private final AtomicReference<String> output = new AtomicReference<>(FIRST);
    private final AtomicLong nanos = new AtomicLong();

    private HeapDiagnostics diagnostics(HeapDiagnostics.HeapDumper dumper, long heapUsed) {
        return new HeapDiagnostics(output::get, dumper, () -> heapUsed, directory,
                Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC), nanos::get);
    }

    @Test
    void comparesEachHistogramWithThePreviousOne() throws JMException {
        HeapDiagnostics heap = diagnostics(null, 0);
        assertThat(heap.hasPrevious()).isFalse();

        HeapDiagnostics.Histogram first = heap.histogram(2);
        assertThat(first.totalInstances()).isEqualTo(1510);
        assertThat(first.totalBytes()).isEqualTo(76480);
        assertThat(first.classCount()).isEqualTo(3);
        assertThat(first.previousAt()).isNull();
        assertThat(first.deltaBytes()).isNull();
        assertThat(first.growth()).isEmpty();
        assertThat(first.rows()).extracting(HeapDiagnostics.HistogramRow::className).containsExactly("[B", "java.lang.String");
        assertThat(first.rows().get(0).deltaBytes()).isNull();
        assertThat(heap.hasPrevious()).isTrue();

        // Rate limited until the interval has passed.
        nanos.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(heap.histogram(10)).isNull();
        nanos.addAndGet(Duration.ofSeconds(6).toNanos());
        output.set(SECOND);
        HeapDiagnostics.Histogram second = heap.histogram(10);

        assertThat(second.previousAt()).isEqualTo(first.capturedAt());
        assertThat(second.deltaBytes()).isEqualTo(204000 - 76480);
        assertThat(second.deltaInstances()).isEqualTo(3450 - 1510);
        assertThat(second.rows()).hasSize(3);
        HeapDiagnostics.HistogramRow bytes = second.rows().get(0);
        assertThat(bytes.deltaInstances()).isEqualTo(2000);
        assertThat(bytes.deltaBytes()).isEqualTo(128000);
        assertThat(bytes.added()).isFalse();
        HeapDiagnostics.HistogramRow leak = second.rows().stream()
                .filter(row -> row.className().equals("com.example.Leak")).findFirst().orElseThrow();
        assertThat(leak.added()).isTrue();
        assertThat(leak.deltaBytes()).isEqualTo(2400);
        assertThat(second.growth()).extracting(HeapDiagnostics.HistogramRow::className)
                .containsExactly("[B", "com.example.Leak");
    }

    @Test
    void keepsTheLargestRowsAndLeavesUnknownDeltasEmpty() throws JMException {
        StringBuilder many = new StringBuilder();
        for (int index = 1; index <= HeapDiagnostics.MAX_ROWS + 10; index++) {
            many.append(String.format("%5d: %10d %10d  com.example.C%d%n", index, index, index * 16L, index));
        }
        HeapDiagnostics.Snapshot snapshot = HeapDiagnostics.parse(many.toString(), Instant.EPOCH);
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.rows()).hasSize(HeapDiagnostics.MAX_ROWS);
        assertThat(snapshot.classCount()).isEqualTo(HeapDiagnostics.MAX_ROWS + 10);
        assertThat(snapshot.rows().get(0).className()).isEqualTo("com.example.C510");
        assertThat(snapshot.totalInstances()).isEqualTo(510L * 511 / 2);

        output.set(many.toString());
        HeapDiagnostics heap = diagnostics(null, 0);
        heap.histogram(1);
        nanos.addAndGet(Duration.ofSeconds(11).toNanos());
        output.set(FIRST);
        HeapDiagnostics.Histogram next = heap.histogram(0);
        // Classes beyond the rows kept from the previous histogram have no known delta.
        assertThat(next.rows()).singleElement().satisfies(row -> {
            assertThat(row.deltaBytes()).isNull();
            assertThat(row.added()).isFalse();
        });
    }

    @Test
    void usesTheDiagnosticCommandOfTheRunningJvm() throws JMException {
        HeapDiagnostics heap = new HeapDiagnostics(Duration.ZERO);
        HeapDiagnostics.Histogram histogram = heap.histogram(5);
        assertThat(histogram.rows()).hasSize(5);
        assertThat(histogram.totalBytes()).isPositive();
        assertThat(heap.histogram(5).previousAt()).isNotNull();
        assertThat(new HeapDiagnostics(null).hasPrevious()).isFalse();
    }

    @Test
    void writesAHeapDumpAndDeletesItWhenClosed() throws IOException {
        HeapDiagnostics heap = diagnostics((file, live) -> {
            assertThat(live).isTrue();
            Files.writeString(Path.of(file), "JAVA PROFILE 1.0.2");
        }, 1024);

        HeapDiagnostics.HeapDump dump = heap.dumpHeap();
        assertThat(dump.fileName()).isEqualTo("heap-20260102-030405.hprof");
        assertThat(dump.size()).isEqualTo(18);
        assertThat(Files.readString(dump.file())).startsWith("JAVA PROFILE");
        assertThat(heap.dumping()).isTrue();
        assertThat(heap.dumpHeap()).isNull();

        dump.close();
        dump.close();
        assertThat(dump.file()).doesNotExist();
        assertThat(dump.file().getParent()).doesNotExist();
        assertThat(heap.dumping()).isFalse();
    }

    @Test
    void deletesDumpsLeftOverByEarlierRunsButNotRecentOnes() throws IOException {
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        Path stale = Files.createDirectory(directory.resolve("meerkat-heap-old"));
        Files.writeString(stale.resolve("heap.hprof"), "old");
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(now.minus(Duration.ofDays(2))));
        Path recent = Files.createDirectory(directory.resolve("meerkat-heap-recent"));
        Files.setLastModifiedTime(recent, java.nio.file.attribute.FileTime.from(now.minus(Duration.ofHours(1))));
        Path unrelated = Files.createDirectory(directory.resolve("other-app"));
        Files.setLastModifiedTime(unrelated, java.nio.file.attribute.FileTime.from(now.minus(Duration.ofDays(9))));

        try (HeapDiagnostics.HeapDump dump = diagnostics((file, live) -> Files.writeString(Path.of(file), "x"), 1)
                .dumpHeap()) {
            assertThat(dump.file()).exists();
        }

        assertThat(stale).doesNotExist();
        assertThat(recent).exists();
        assertThat(unrelated).exists();
    }

    @Test
    void refusesADumpWithoutEnoughDiskSpaceAndRecoversFromFailures() throws IOException {
        HeapDiagnostics full = diagnostics((file, live) -> { }, Long.MAX_VALUE / 2);
        assertThatThrownBy(full::dumpHeap).isInstanceOf(IllegalStateException.class).hasMessageContaining("disk space");
        assertThat(full.dumping()).isFalse();

        HeapDiagnostics failing = diagnostics((file, live) -> {
            Files.writeString(Path.of(file), "partial");
            throw new IOException("disk failed");
        }, 1);
        assertThatThrownBy(failing::dumpHeap).isInstanceOf(IOException.class);
        assertThat(failing.dumping()).isFalse();
        try (var leftovers = Files.list(directory)) {
            assertThat(leftovers).isEmpty();
        }
    }
}
