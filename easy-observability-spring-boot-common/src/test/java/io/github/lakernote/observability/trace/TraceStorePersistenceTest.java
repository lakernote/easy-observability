package io.github.lakernote.observability.trace;

import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.store.DiagnosticTraceStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

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

    private static RecentTraceStore.TraceRecord trace(String traceId, String source, Instant finishedAt) {
        return new RecentTraceStore.TraceRecord(1, traceId, source, "", 500, 5,
                false, true, TraceNodeFixtures.named(source + " tree"), finishedAt, -1, -1, -1, source, "", "", "", "", false, false);
    }
}
