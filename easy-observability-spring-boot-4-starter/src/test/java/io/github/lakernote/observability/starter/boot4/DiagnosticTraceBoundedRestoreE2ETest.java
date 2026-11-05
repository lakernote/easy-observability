package io.github.lakernote.observability.starter.boot4;

import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStore;
import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStoreTestSupport;
import io.github.lakernote.observability.trace.internal.store.RecentTraceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DiagnosticTraceBoundedRestoreE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "easy.observability.metrics.enabled=false",
                "easy.observability.trace.enabled=false"
        })
class DiagnosticTraceBoundedRestoreE2ETest {
    private static final long MAX_HISTORY_BYTES = 1024 * 1024;
    private static final int HISTORY_LIMIT = 100;
    private static final int RECORD_COUNT = 140;
    private static final String OLDEST_TRACE_ID = "discarded-oldest-trace";
    private static final String LATEST_TRACE_ID = "retained-latest-trace";
    private static final PreparedHistory HISTORY = createOversizedHistory();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private DiagnosticTraceStore diagnosticTraceStore;

    @DynamicPropertySource
    static void diagnosticStorage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.storage.directory", () -> HISTORY.directory().toString());
        registry.add("easy.observability.trace.diagnostic-history-limit", () -> Integer.toString(HISTORY_LIMIT));
        registry.add("easy.observability.trace.diagnostic-max-size", () -> "1MB");
    }

    @AfterAll
    static void cleanStoragePath() throws IOException {
        FileSystemUtils.deleteRecursively(HISTORY.directory());
    }

    @Test
    void restoresOnlyTheNewestBoundedHistoryAndServesItThroughTheApi() throws Exception {
        assertThat(HISTORY.originalBytes()).isGreaterThan(MAX_HISTORY_BYTES);
        assertThat(Files.size(HISTORY.file())).isLessThanOrEqualTo(MAX_HISTORY_BYTES / 2);

        DiagnosticTraceStore.StorageStatus status = diagnosticTraceStore.storageStatus();
        assertThat(status.persistenceEnabled()).isTrue();
        assertThat(status.traceCount()).isBetween(1, HISTORY_LIMIT);
        assertThat(status.maxTraceCount()).isEqualTo(HISTORY_LIMIT);
        assertThat(status.fileBytes()).isLessThanOrEqualTo(MAX_HISTORY_BYTES / 2);

        List<RecentTraceStore.TraceRecord> restored = diagnosticTraceStore.findRecent(HISTORY_LIMIT);
        assertThat(restored.get(0).traceId()).isEqualTo(LATEST_TRACE_ID);
        assertThat(restored).extracting(RecentTraceStore.TraceRecord::traceId)
                .contains(LATEST_TRACE_ID)
                .doesNotContain(OLDEST_TRACE_ID);

        HttpResponse<String> traces = get("/api/easy-observability/traces?source=HTTP&limit=5");
        assertThat(traces.statusCode()).isEqualTo(200);
        assertThat(traces.body()).contains(LATEST_TRACE_ID).doesNotContain(OLDEST_TRACE_ID);

        HttpResponse<String> detail = get("/api/easy-observability/traces/" + LATEST_TRACE_ID + "?source=HTTP");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains(LATEST_TRACE_ID);

        HttpResponse<String> storage = get("/api/easy-observability/traces/storage");
        assertThat(storage.statusCode()).isEqualTo(200);
        assertThat(storage.body()).contains("\"diagnosticPersistenceEnabled\":true");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static PreparedHistory createOversizedHistory() {
        try {
            Path directory = Files.createTempDirectory("easy-observability-bounded-restore");
            Path file = directory.resolve("traces.jsonl");
            String detail = "d".repeat(10 * 1024);
            Instant startedAt = Instant.parse("2026-10-21T10:00:00Z");
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (int index = 0; index < RECORD_COUNT; index++) {
                    writer.write(DiagnosticTraceStoreTestSupport.json(
                            trace(index, detail, startedAt.plusSeconds(index))));
                    writer.newLine();
                }
            }
            long originalBytes = Files.size(file);
            if (originalBytes <= MAX_HISTORY_BYTES) {
                throw new IllegalStateException("Test history did not exceed its configured limit");
            }
            directory.toFile().deleteOnExit();
            file.toFile().deleteOnExit();
            return new PreparedHistory(directory, file, originalBytes);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static RecentTraceStore.TraceRecord trace(int index, String detail, Instant finishedAt) {
        String traceId = index == 0 ? OLDEST_TRACE_ID
                : index == RECORD_COUNT - 1 ? LATEST_TRACE_ID : "restore-trace-" + index;
        return new RecentTraceStore.TraceRecord(index + 1L, traceId, "GET /restore/" + index, detail,
                500, 5, false, true, List.of(), finishedAt, -1, -1, -1, "HTTP",
                IllegalStateException.class.getName(), "history failure", "", "", false, false);
    }

    private record PreparedHistory(Path directory, Path file, long originalBytes) { }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication { }
}
