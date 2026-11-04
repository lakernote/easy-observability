package io.github.lakernote.observability.starter.boot3;

import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStore;
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

/**
 * The trace archive is written by the component's own codec, so host JSON customizations must not change it,
 * while the dashboard API keeps following the host's JSON settings.
 */
@SpringBootTest(classes = DiagnosticTraceArchiveFormatE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jackson.property-naming-strategy=SNAKE_CASE",
                "easy.observability.metrics.enabled=false"
        })
class DiagnosticTraceArchiveFormatE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static final Path HISTORY_FILE = STORAGE_DIRECTORY.resolve("diagnostic-traces.jsonl");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private DiagnosticTraceStore diagnosticTraceStore;

    @DynamicPropertySource
    static void traceStorage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.trace.diagnostic-trace-file", HISTORY_FILE::toString);
    }

    @AfterAll
    static void deleteStorage() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void archiveKeepsItsOwnFormatWhileTheApiFollowsTheHostJsonSettings() throws Exception {
        diagnosticTraceStore.recordDiagnosticTrace(new RecentTraceStore.TraceRecord(
                1, "archive-format-trace", "GET /archive", "GET", 500, 12, false, true, List.of(),
                Instant.parse("2026-10-21T10:00:00Z"), 7, 1, 2, "HTTP", "HTTP_5XX", "boom 中文", "worker", "",
                false, false));

        String line = awaitArchivedLine();
        assertThat(line)
                .startsWith("{\"id\":1,\"traceId\":\"archive-format-trace\"")
                .contains("\"finishedAt\":\"2026-10-21T10:00:00Z\"", "\"errorMessage\":\"boom 中文\"")
                .doesNotContain("trace_id", "finished_at");

        DiagnosticTraceStore restored = new DiagnosticTraceStore(HISTORY_FILE.toString(), 100, 4 * 1024 * 1024);
        restored.restoreHistory();
        assertThat(restored.findByTraceId("archive-format-trace", "HTTP").errorMessage()).isEqualTo("boom 中文");

        HttpResponse<String> api = get("/api/easy-observability/traces/archive-format-trace?source=HTTP");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).contains("\"trace_id\":\"archive-format-trace\"");
    }

    private static String awaitArchivedLine() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(HISTORY_FILE)) {
                List<String> lines = Files.readAllLines(HISTORY_FILE, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) return lines.get(0);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Trace was not archived to " + HISTORY_FILE);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("easy-observability-archive-format");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
