package io.github.lakernote.observability.starter.boot4;

import io.github.lakernote.observability.trace.internal.store.TraceRecord;
import io.github.lakernote.observability.trace.internal.store.TraceStore;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trace archive is written by the component's own codec, so host JSON customizations must not change it,
 * while the dashboard API keeps following the host's JSON settings.
 */
@SpringBootTest(classes = TraceArchiveFormatE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jackson.property-naming-strategy=SNAKE_CASE",
                "easy.observability.metrics.enabled=false"
        })
class TraceArchiveFormatE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static final Path TRACE_DIRECTORY = STORAGE_DIRECTORY.resolve("traces");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    @DynamicPropertySource
    static void traceStorage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.storage.directory", STORAGE_DIRECTORY::toString);
    }

    @AfterAll
    static void deleteStorage() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void archiveKeepsItsOwnFormatWhileTheApiFollowsTheHostJsonSettings() throws Exception {
        traceStore.add(TraceRecord.builder()
                .id(1).traceId("archive-format-trace").sourceType("HTTP").name("GET /archive").method("GET")
                .status(500).durationMs(12).error(true)
                .threadId(7).threadName("worker").cpuTimeMs(1).allocatedBytes(2)
                .errorType("HTTP_5XX").errorMessage("boom 中文")
                .build());

        String line = awaitArchivedLine();
        assertThat(line)
                .startsWith("{\"id\":")
                .contains("\"traceId\":\"archive-format-trace\"", "\"finishedAt\":\"", "\"errorMessage\":\"boom 中文\"")
                .doesNotContain("trace_id", "finished_at");

        TraceStore restored = new TraceStore(TRACE_DIRECTORY, 7, 128L * 1024 * 1024, 10);
        try {
            restored.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (restored.storageStatus().indexing() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(restored.find("archive-format-trace", "HTTP").errorMessage()).isEqualTo("boom 中文");
        } finally {
            restored.close();
        }

        HttpResponse<String> api = get("/api/easy-observability/traces/archive-format-trace?source=HTTP");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).contains("\"trace_id\":\"archive-format-trace\"");
        HttpResponse<String> list = get("/api/easy-observability/traces?kind=error");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("\"trace_id\":\"archive-format-trace\"", "\"scan_limited\":false")
                .doesNotContain("tree_nodes", "error_stack_trace");
    }

    @Test
    void runsWithoutJackson2OnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("com.fasterxml.jackson.databind.ObjectMapper"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    private static String awaitArchivedLine() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(TRACE_DIRECTORY)) {
                try (var files = Files.list(TRACE_DIRECTORY)) {
                    for (Path file : files.toList()) {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        if (!lines.isEmpty()) return lines.get(0);
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Trace was not archived to " + TRACE_DIRECTORY);
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
