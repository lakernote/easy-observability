package io.github.lakernote.observability.trace;

import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.store.IncidentTraceStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryPersistenceTest {
    @TempDir Path directory;

    @Test
    void persistsSlowAndErrorResourcesAndTraceTreeAcrossRestart() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path tracesFile = directory.resolve("traces.jsonl");
        BoundedHistoryWriter writer = new BoundedHistoryWriter();
        OperationRecord operation = new OperationRecord("HTTP", "trace-123", "GET /orders/{id}",
                "http-nio-8080-exec-7", 77, "failure", 845, 51, 2048,
                "GET /orders/{id}", "", "", true, true, List.of("SLOW_HTTP"),
                "HTTP_5XX", "500", Map.of(), Instant.parse("2026-10-15T10:00:00Z"), "1", "");
        RecentTraceStore.TraceRecord trace = new RecentTraceStore.TraceRecord(1, "trace-123", "GET /orders/{id}",
                "GET /orders/{id}", 500, 845, true, true, "HTTP GET\n  SQL select", operation.finishedAt(),
                operation.threadId(), operation.cpuTimeMs(), operation.allocatedBytes(), List.of(operation));
        IncidentTraceStore archive = new IncidentTraceStore(mapper, tracesFile.toString(), 1000,
                4 * 1024 * 1024, writer);
        archive.restore();
        archive.record(trace);
        writer.close();

        IncidentTraceStore restoredTraces = new IncidentTraceStore(mapper, tracesFile.toString(), 1000, 4 * 1024 * 1024);
        restoredTraces.restore();

        assertThat(restoredTraces.find("trace-123")).satisfies(saved -> {
            assertThat(saved).isNotNull();
            assertThat(saved.tree()).contains("SQL select");
            assertThat(saved.operations()).singleElement().satisfies(item ->
                    assertThat(item).satisfies(restored -> {
                        assertThat(restored.allocatedBytes()).isEqualTo(2048);
                        assertThat(restored.cpuTimeMs()).isEqualTo(51);
                        assertThat(restored.threadId()).isEqualTo(77);
                        assertThat(restored.threadName()).isEqualTo("http-nio-8080-exec-7");
                        assertThat(restored.spanId()).isEqualTo("1");
                    }));
        });
    }

    @Test
    void keepsDifferentEntrySourcesWithTheSamePropagatedTraceId() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path tracesFile = directory.resolve("shared-trace.jsonl");
        BoundedHistoryWriter writer = new BoundedHistoryWriter();
        IncidentTraceStore archive = new IncidentTraceStore(mapper, tracesFile.toString(), 1000,
                4 * 1024 * 1024, writer);
        archive.restore();
        Instant httpFinished = Instant.parse("2026-10-21T10:00:01Z");
        Instant kafkaFinished = Instant.parse("2026-10-21T10:00:02Z");
        archive.record(trace("shared", "HTTP", httpFinished));
        archive.record(trace("shared", "KAFKA_CONSUMER", kafkaFinished));
        writer.close();

        IncidentTraceStore restored = new IncidentTraceStore(mapper, tracesFile.toString(), 1000, 4 * 1024 * 1024);
        restored.restore();

        assertThat(restored.recent(10)).extracting(RecentTraceStore.TraceRecord::sourceType)
                .containsExactly("KAFKA_CONSUMER", "HTTP");
        assertThat(restored.find("shared").sourceType()).isEqualTo("HTTP");
        assertThat(restored.find("shared", "KAFKA_CONSUMER").sourceType()).isEqualTo("KAFKA_CONSUMER");
    }

    private static RecentTraceStore.TraceRecord trace(String traceId, String source, Instant finishedAt) {
        return new RecentTraceStore.TraceRecord(1, traceId, source, "", 500, 5,
                false, true, source + " tree", finishedAt, -1, -1, -1, List.of(), source);
    }
}
