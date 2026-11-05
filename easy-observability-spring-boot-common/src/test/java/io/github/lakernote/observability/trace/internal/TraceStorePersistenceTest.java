package io.github.lakernote.observability.trace.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStore;
import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStoreTestSupport;
import io.github.lakernote.observability.trace.internal.store.RecentTraceStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TraceStorePersistenceTest {
    private static final long MIN_HISTORY_FILE_BYTES = 1024 * 1024;

    @TempDir Path directory;

    @Test
    void persistsDiagnosticTraceTreeAndEntryResourcesAcrossRestart() {
        Path tracesFile = directory.resolve("traces.jsonl");
        BoundedHistoryWriter writer = new BoundedHistoryWriter();
        RecentTraceStore.TraceRecord trace = new RecentTraceStore.TraceRecord(1, "trace-123", "GET /orders/{id}",
                "GET /orders/{id}", 500, 845, true, true,
                List.of(new TraceNode("Http", "HTTP GET", "", 845, 0L, true, true, false, "HTTP_5XX", "", 77,
                        List.of(new TraceNode("Database", "SQL select", "", 2, 1L, false, false, false, "", "", 77, List.of())))),
                Instant.parse("2026-10-15T10:00:00Z"), 77, 51, 2048, "HTTP", "HTTP_5XX", "500", "", "", false, false);
        DiagnosticTraceStore diagnosticStore = new DiagnosticTraceStore(tracesFile, 1000,
                4 * 1024 * 1024, writer);
        diagnosticStore.restoreHistory();
        diagnosticStore.recordDiagnosticTrace(trace);
        writer.close();

        DiagnosticTraceStore restoredDiagnosticStore = new DiagnosticTraceStore(tracesFile, 1000, 4 * 1024 * 1024);
        restoredDiagnosticStore.restoreHistory();

        assertThat(restoredDiagnosticStore.findByTraceId("trace-123")).satisfies(saved -> {
            assertThat(saved).isNotNull();
            assertThat(TraceNodeFixtures.names(saved.treeNodes())).contains("HTTP GET", "SQL select");
            assertThat(saved.allocatedBytes()).isEqualTo(2048);
            assertThat(saved.cpuTimeMs()).isEqualTo(51);
            assertThat(saved.threadId()).isEqualTo(77);
            assertThat(saved.threadName()).isEmpty();
            assertThat(saved.errorType()).isEqualTo("HTTP_5XX");
            assertThat(saved.errorMessage()).isEqualTo("500");
        });
    }

    @Test
    void keepsDifferentEntrySourcesWithTheSamePropagatedTraceId() {
        Path tracesFile = directory.resolve("shared-trace.jsonl");
        BoundedHistoryWriter writer = new BoundedHistoryWriter();
        DiagnosticTraceStore diagnosticStore = new DiagnosticTraceStore(tracesFile, 1000,
                4 * 1024 * 1024, writer);
        diagnosticStore.restoreHistory();
        Instant httpFinished = Instant.parse("2026-10-21T10:00:01Z");
        Instant kafkaFinished = Instant.parse("2026-10-21T10:00:02Z");
        diagnosticStore.recordDiagnosticTrace(trace("shared", "HTTP", httpFinished));
        diagnosticStore.recordDiagnosticTrace(trace("shared", "KAFKA_CONSUMER", kafkaFinished));
        writer.close();

        DiagnosticTraceStore restored = new DiagnosticTraceStore(tracesFile, 1000, 4 * 1024 * 1024);
        restored.restoreHistory();

        assertThat(restored.findRecent(10)).extracting(RecentTraceStore.TraceRecord::sourceType)
                .containsExactly("KAFKA_CONSUMER", "HTTP");
        assertThat(restored.findByTraceId("shared").sourceType()).isEqualTo("HTTP");
        assertThat(restored.findByTraceId("shared", "KAFKA_CONSUMER").sourceType()).isEqualTo("KAFKA_CONSUMER");
    }

    @Test
    void disablesPersistenceAfterFirstWriteFailureWithoutLosingInMemoryTraces() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DiagnosticTraceStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        appender.start();
        try {
            Path blockedParent = directory.resolve("blocked-history");
            Files.writeString(blockedParent, "not a directory");
            Path tracesFile = blockedParent.resolve("traces.jsonl");
            DiagnosticTraceStore store = new DiagnosticTraceStore(tracesFile, 1000, 4 * 1024 * 1024);

            store.recordDiagnosticTrace(trace("first-failure", "HTTP", Instant.parse("2026-10-21T10:00:01Z")));
            store.recordDiagnosticTrace(trace("still-blocked", "HTTP", Instant.parse("2026-10-21T10:00:02Z")));
            Files.delete(blockedParent);
            Files.createDirectory(blockedParent);
            store.recordDiagnosticTrace(trace("after-repair", "HTTP", Instant.parse("2026-10-21T10:00:03Z")));

            assertThat(tracesFile).doesNotExist();
            assertThat(store.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                    .containsExactly("after-repair", "still-blocked", "first-failure");
            assertThat(store.storageStatus().persistenceEnabled()).isFalse();
            assertThat(store.storageStatus().fileBytes()).isZero();
            assertThat(appender.list).singleElement().satisfies(event ->
                    assertThat(event.getLevel()).isEqualTo(Level.WARN));
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
            appender.stop();
        }
    }

    @Test
    void keepsPersistenceDisabledWhenLaterTracesArriveConcurrently() throws Exception {
        Path blockedParent = directory.resolve("concurrent-blocked-history");
        Files.writeString(blockedParent, "not a directory");
        Path tracesFile = blockedParent.resolve("traces.jsonl");
        DiagnosticTraceStore store = new DiagnosticTraceStore(tracesFile, 1000, 4 * 1024 * 1024);
        store.recordDiagnosticTrace(trace("initial-failure", "HTTP", Instant.parse("2026-10-21T10:00:00Z")));
        Files.delete(blockedParent);
        Files.createDirectory(blockedParent);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                int traceIndex = index;
                writes.add(executor.submit(() -> store.recordDiagnosticTrace(trace(
                        "concurrent-" + traceIndex, "HTTP", Instant.parse("2026-10-21T10:01:00Z")))));
            }
            for (Future<?> write : writes) write.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(tracesFile).doesNotExist();
        assertThat(store.findRecent(100)).hasSize(33);
    }

    @Test
    void blockedBackgroundSerializationDoesNotBlockLaterTraceRecording() throws Exception {
        CountDownLatch serializationStarted = new CountDownLatch(1);
        CountDownLatch releaseSerialization = new CountDownLatch(1);
        AtomicBoolean blockFirstSerialization = new AtomicBoolean(true);
        BoundedHistoryWriter writer = new BoundedHistoryWriter();
        DiagnosticTraceStore store = DiagnosticTraceStoreTestSupport.storeWithWriteHook(
                directory.resolve("non-blocking-history.jsonl"), 1000, 4 * 1024 * 1024, writer,
                trace -> {
                    if (blockFirstSerialization.compareAndSet(true, false)) {
                        serializationStarted.countDown();
                        try {
                            releaseSerialization.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while simulating blocked trace serialization",
                                    interrupted);
                        }
                    }
                });
        ExecutorService businessExecutor = Executors.newSingleThreadExecutor();
        try {
            store.recordDiagnosticTrace(trace(
                    "blocked-writer", "HTTP", Instant.parse("2026-10-21T10:00:00Z")));
            assertThat(serializationStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> laterRecord = businessExecutor.submit(() -> store.recordDiagnosticTrace(trace(
                    "business-not-blocked", "HTTP", Instant.parse("2026-10-21T10:00:01Z"))));
            laterRecord.get(2, TimeUnit.SECONDS);

            assertThat(store.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                    .containsExactly("business-not-blocked", "blocked-writer");
        } finally {
            releaseSerialization.countDown();
            businessExecutor.shutdown();
            if (!businessExecutor.awaitTermination(5, TimeUnit.SECONDS)) businessExecutor.shutdownNow();
            writer.close();
        }
    }

    @Test
    void allowsAReplacementStoreToPersistAfterTheStoragePathIsRepaired() throws Exception {
        Path blockedParent = directory.resolve("restart-recovers-history");
        Files.writeString(blockedParent, "not a directory");
        Path tracesFile = blockedParent.resolve("traces.jsonl");
        DiagnosticTraceStore disabledStore = new DiagnosticTraceStore(tracesFile, 1000, 4 * 1024 * 1024);
        disabledStore.recordDiagnosticTrace(trace(
                "before-repair", "HTTP", Instant.parse("2026-10-21T10:00:00Z")));

        Files.delete(blockedParent);
        Files.createDirectory(blockedParent);
        disabledStore.recordDiagnosticTrace(trace(
                "old-instance-after-repair", "HTTP", Instant.parse("2026-10-21T10:00:01Z")));
        DiagnosticTraceStore replacementStore = new DiagnosticTraceStore(tracesFile, 1000, 4 * 1024 * 1024);
        replacementStore.restoreHistory();
        replacementStore.recordDiagnosticTrace(trace(
                "new-instance-after-repair", "HTTP", Instant.parse("2026-10-21T10:00:02Z")));

        DiagnosticTraceStore restoredStore = new DiagnosticTraceStore(tracesFile, 1000, 4 * 1024 * 1024);
        restoredStore.restoreHistory();
        assertThat(restoredStore.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("new-instance-after-repair");
        assertThat(disabledStore.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("old-instance-after-repair", "before-repair");
        assertThat(disabledStore.storageStatus().persistenceEnabled()).isFalse();
        assertThat(replacementStore.storageStatus().persistenceEnabled()).isTrue();
    }

    @Test
    void oversizedTrailingLineDoesNotHideEarlierValidTraceDuringTrimming() throws Exception {
        Path tracesFile = directory.resolve("oversized-line.jsonl");
        RecentTraceStore.TraceRecord valid = trace(
                "valid-before-oversized-line", "HTTP", Instant.parse("2026-10-21T10:00:00Z"));
        String validJson = DiagnosticTraceStoreTestSupport.json(valid);
        String oversizedMalformedLine = "x".repeat((int) MIN_HISTORY_FILE_BYTES + 128 * 1024);
        Files.writeString(tracesFile, validJson + "\n" + oversizedMalformedLine + "\n",
                StandardCharsets.UTF_8);

        DiagnosticTraceStore store = new DiagnosticTraceStore(tracesFile, 100, MIN_HISTORY_FILE_BYTES);
        store.restoreHistory();

        assertThat(store.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("valid-before-oversized-line");
        assertThat(Files.size(tracesFile)).isLessThanOrEqualTo(MIN_HISTORY_FILE_BYTES / 2);
        assertThat(store.storageStatus().persistenceEnabled()).isTrue();
    }

    @Test
    void trimsOversizedHistoryToNewestRecordsWithinCountAndByteLimits() throws Exception {
        Path tracesFile = directory.resolve("bounded-history.jsonl");
        String payload = "d".repeat(10 * 1024);
        StringBuilder jsonl = new StringBuilder();
        for (int index = 0; index < 140; index++) {
            jsonl.append(DiagnosticTraceStoreTestSupport.json(traceWithDetail(
                    "bounded-" + index, payload, Instant.parse("2026-10-21T10:00:00Z").plusSeconds(index))))
                    .append('\n');
        }
        Files.writeString(tracesFile, jsonl, StandardCharsets.UTF_8);
        assertThat(Files.size(tracesFile)).isGreaterThan(MIN_HISTORY_FILE_BYTES);

        DiagnosticTraceStore store = new DiagnosticTraceStore(tracesFile, 100, MIN_HISTORY_FILE_BYTES);
        store.restoreHistory();

        List<String> traceIds = store.findRecent(100).stream()
                .map(RecentTraceStore.TraceRecord::traceId)
                .toList();
        assertThat(traceIds).isNotEmpty().hasSizeLessThanOrEqualTo(100);
        assertThat(traceIds.get(0)).isEqualTo("bounded-139");
        assertThat(traceIds).doesNotContain("bounded-0");
        for (int index = 1; index < traceIds.size(); index++) {
            int previous = Integer.parseInt(traceIds.get(index - 1).substring("bounded-".length()));
            int current = Integer.parseInt(traceIds.get(index).substring("bounded-".length()));
            assertThat(current).isEqualTo(previous - 1);
        }
        assertThat(Files.size(tracesFile)).isLessThanOrEqualTo(MIN_HISTORY_FILE_BYTES / 2);
    }

    @Test
    void skipsMalformedLineWithoutChangingValidRecordOrder() throws Exception {
        Path tracesFile = directory.resolve("malformed-history.jsonl");
        RecentTraceStore.TraceRecord first = trace(
                "valid-first", "HTTP", Instant.parse("2026-10-21T10:00:00Z"));
        RecentTraceStore.TraceRecord second = trace(
                "valid-second", "HTTP", Instant.parse("2026-10-21T10:00:01Z"));
        Files.writeString(tracesFile, DiagnosticTraceStoreTestSupport.json(first) + "\n{not-json}\n"
                + DiagnosticTraceStoreTestSupport.json(second) + "\n", StandardCharsets.UTF_8);

        DiagnosticTraceStore store = new DiagnosticTraceStore(tracesFile, 100, MIN_HISTORY_FILE_BYTES);
        store.restoreHistory();

        assertThat(store.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("valid-second", "valid-first");
        assertThat(store.storageStatus().persistenceEnabled()).isTrue();
    }

    @Test
    void restoresUtf8CrLfHistoryWithAnUnterminatedFinalRecordInOrder() throws Exception {
        Path tracesFile = directory.resolve("utf8-crlf-history.jsonl");
        RecentTraceStore.TraceRecord first = traceWithDetail(
                "utf8-first", "第一条：数据库很慢", Instant.parse("2026-10-21T10:00:00Z"));
        RecentTraceStore.TraceRecord second = traceWithDetail(
                "utf8-second", "第二条：缓存失败", Instant.parse("2026-10-21T10:00:01Z"));
        RecentTraceStore.TraceRecord third = traceWithDetail(
                "utf8-third", "第三条：任务完成", Instant.parse("2026-10-21T10:00:02Z"));
        Files.writeString(tracesFile, DiagnosticTraceStoreTestSupport.json(first) + "\r\n"
                + DiagnosticTraceStoreTestSupport.json(second) + "\r\n" + DiagnosticTraceStoreTestSupport.json(third),
                StandardCharsets.UTF_8);

        DiagnosticTraceStore store = new DiagnosticTraceStore(tracesFile, 100, MIN_HISTORY_FILE_BYTES);
        store.restoreHistory();

        assertThat(store.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("utf8-third", "utf8-second", "utf8-first");
        assertThat(store.findByTraceId("utf8-first").method()).isEqualTo("第一条：数据库很慢");
        assertThat(store.findByTraceId("utf8-third").method()).isEqualTo("第三条：任务完成");
        assertThat(store.storageStatus().persistenceEnabled()).isTrue();
    }

    @Test
    void keepsOversizedNewTraceInMemoryAndPersistsFollowingSmallTrace() throws Exception {
        Path tracesFile = directory.resolve("oversized-new-record.jsonl");
        DiagnosticTraceStore store = new DiagnosticTraceStore(tracesFile, 100, MIN_HISTORY_FILE_BYTES);
        RecentTraceStore.TraceRecord oversized = traceWithDetail(
                "oversized-in-memory", "x".repeat((int) MIN_HISTORY_FILE_BYTES + 128 * 1024),
                Instant.parse("2026-10-21T10:00:00Z"));
        RecentTraceStore.TraceRecord small = traceWithDetail(
                "small-persisted", "small diagnostic", Instant.parse("2026-10-21T10:00:01Z"));

        store.recordDiagnosticTrace(oversized);
        assertThat(tracesFile).doesNotExist();
        store.recordDiagnosticTrace(small);

        assertThat(store.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("small-persisted", "oversized-in-memory");
        assertThat(store.storageStatus().persistenceEnabled()).isTrue();
        assertThat(tracesFile).exists();
        assertThat(Files.size(tracesFile)).isLessThan(MIN_HISTORY_FILE_BYTES);

        DiagnosticTraceStore restored = new DiagnosticTraceStore(tracesFile, 100, MIN_HISTORY_FILE_BYTES);
        restored.restoreHistory();
        assertThat(restored.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("small-persisted");
    }

    private static RecentTraceStore.TraceRecord trace(String traceId, String source, Instant finishedAt) {
        return new RecentTraceStore.TraceRecord(1, traceId, source, "", 500, 5,
                false, true, TraceNodeFixtures.named(source + " tree"), finishedAt, -1, -1, -1, source, "", "", "", "", false, false);
    }

    private static RecentTraceStore.TraceRecord traceWithDetail(String traceId, String detail, Instant finishedAt) {
        return new RecentTraceStore.TraceRecord(1, traceId, traceId, detail, 500, 5,
                false, true, TraceNodeFixtures.named("HTTP tree"), finishedAt, -1, -1, -1,
                "HTTP", "", "", "", "", false, false);
    }
}
