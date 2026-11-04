package io.github.lakernote.observability.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.store.DiagnosticTraceStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class TraceStorePersistenceTest {
    @TempDir Path directory;

    @Test
    void persistsDiagnosticTraceTreeAndEntryResourcesAcrossRestart() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path tracesFile = directory.resolve("traces.jsonl");
        BoundedHistoryWriter writer = new BoundedHistoryWriter();
        RecentTraceStore.TraceRecord trace = new RecentTraceStore.TraceRecord(1, "trace-123", "GET /orders/{id}",
                "GET /orders/{id}", 500, 845, true, true,
                List.of(new TraceNode("Http", "HTTP GET", "", 845, 0L, true, true, false, "HTTP_5XX", "", 77,
                        List.of(new TraceNode("Database", "SQL select", "", 2, 1L, false, false, false, "", "", 77, List.of())))),
                Instant.parse("2026-10-15T10:00:00Z"), 77, 51, 2048, "HTTP", "HTTP_5XX", "500", "", "", false, false);
        DiagnosticTraceStore diagnosticStore = new DiagnosticTraceStore(mapper, tracesFile.toString(), 1000,
                4 * 1024 * 1024, writer);
        diagnosticStore.restoreHistory();
        diagnosticStore.recordDiagnosticTrace(trace);
        writer.close();

        DiagnosticTraceStore restoredDiagnosticStore = new DiagnosticTraceStore(mapper, tracesFile.toString(), 1000, 4 * 1024 * 1024);
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
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path tracesFile = directory.resolve("shared-trace.jsonl");
        BoundedHistoryWriter writer = new BoundedHistoryWriter();
        DiagnosticTraceStore diagnosticStore = new DiagnosticTraceStore(mapper, tracesFile.toString(), 1000,
                4 * 1024 * 1024, writer);
        diagnosticStore.restoreHistory();
        Instant httpFinished = Instant.parse("2026-10-21T10:00:01Z");
        Instant kafkaFinished = Instant.parse("2026-10-21T10:00:02Z");
        diagnosticStore.recordDiagnosticTrace(trace("shared", "HTTP", httpFinished));
        diagnosticStore.recordDiagnosticTrace(trace("shared", "KAFKA_CONSUMER", kafkaFinished));
        writer.close();

        DiagnosticTraceStore restored = new DiagnosticTraceStore(mapper, tracesFile.toString(), 1000, 4 * 1024 * 1024);
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
            DiagnosticTraceStore store = new DiagnosticTraceStore(
                    new ObjectMapper().findAndRegisterModules(), tracesFile.toString(), 1000, 4 * 1024 * 1024);

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
        DiagnosticTraceStore store = new DiagnosticTraceStore(
                new ObjectMapper().findAndRegisterModules(), tracesFile.toString(), 1000, 4 * 1024 * 1024);
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
    void allowsAReplacementStoreToPersistAfterTheStoragePathIsRepaired() throws Exception {
        Path blockedParent = directory.resolve("restart-recovers-history");
        Files.writeString(blockedParent, "not a directory");
        Path tracesFile = blockedParent.resolve("traces.jsonl");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DiagnosticTraceStore disabledStore = new DiagnosticTraceStore(
                mapper, tracesFile.toString(), 1000, 4 * 1024 * 1024);
        disabledStore.recordDiagnosticTrace(trace(
                "before-repair", "HTTP", Instant.parse("2026-10-21T10:00:00Z")));

        Files.delete(blockedParent);
        Files.createDirectory(blockedParent);
        disabledStore.recordDiagnosticTrace(trace(
                "old-instance-after-repair", "HTTP", Instant.parse("2026-10-21T10:00:01Z")));
        DiagnosticTraceStore replacementStore = new DiagnosticTraceStore(
                mapper, tracesFile.toString(), 1000, 4 * 1024 * 1024);
        replacementStore.restoreHistory();
        replacementStore.recordDiagnosticTrace(trace(
                "new-instance-after-repair", "HTTP", Instant.parse("2026-10-21T10:00:02Z")));

        DiagnosticTraceStore restoredStore = new DiagnosticTraceStore(
                mapper, tracesFile.toString(), 1000, 4 * 1024 * 1024);
        restoredStore.restoreHistory();
        assertThat(restoredStore.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("new-instance-after-repair");
        assertThat(disabledStore.findRecent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("old-instance-after-repair", "before-repair");
        assertThat(disabledStore.storageStatus().persistenceEnabled()).isFalse();
        assertThat(replacementStore.storageStatus().persistenceEnabled()).isTrue();
    }

    private static RecentTraceStore.TraceRecord trace(String traceId, String source, Instant finishedAt) {
        return new RecentTraceStore.TraceRecord(1, traceId, source, "", 500, 5,
                false, true, TraceNodeFixtures.named(source + " tree"), finishedAt, -1, -1, -1, source, "", "", "", "", false, false);
    }
}
