package io.github.lakernote.observability.starter.boot4;

import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStore;
import io.github.lakernote.observability.trace.internal.store.RecentTraceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DiagnosticTraceStorageFailOpenE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "easy.observability.metrics.enabled=false",
                "easy.observability.trace.http-slow-threshold-ms=1",
                "easy.observability.trace.cpu-threshold-ms=0",
                "easy.observability.trace.allocated-memory-threshold=0B"
        })
class DiagnosticTraceStorageFailOpenE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    // A directory where the archive file belongs makes every archive write fail at runtime.
    private static final Path TRACE_FILE = STORAGE_DIRECTORY.resolve("traces.jsonl");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private DiagnosticTraceStore diagnosticTraceStore;

    @Autowired
    private RecentTraceStore recentTraceStore;

    @DynamicPropertySource
    static void diagnosticStorage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.storage.directory", STORAGE_DIRECTORY::toString);
    }

    @AfterAll
    static void cleanStoragePath() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void keepsServingAndRetainingTracesAfterPersistentStorageIsDisabled() throws Exception {
        HttpResponse<String> first = get("/slow/first", "storage-failure-first");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo("first");
        assertThat(first.headers().firstValue("X-Trace-Id")).contains("storage-failure-first");
        assertThat(awaitDiagnosticTrace("storage-failure-first")).isNotNull();
        assertThat(recentTraceStore.find("storage-failure-first", "HTTP")).isNotNull();
        assertThat(awaitPersistenceDisabled()).isTrue();

        Files.delete(TRACE_FILE);

        HttpResponse<String> second = get("/slow/second", "storage-failure-second");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo("second");
        assertThat(second.headers().firstValue("X-Trace-Id")).contains("storage-failure-second");
        assertThat(awaitDiagnosticTrace("storage-failure-second")).isNotNull();
        assertThat(recentTraceStore.find("storage-failure-second", "HTTP")).isNotNull();

        assertThat(TRACE_FILE).doesNotExist();
        assertThat(diagnosticTraceStore.findRecent(10))
                .extracting(RecentTraceStore.TraceRecord::traceId)
                .contains("storage-failure-first", "storage-failure-second");

        HttpResponse<String> traces = get("/api/easy-observability/traces?source=HTTP&limit=10",
                "storage-query");
        assertThat(traces.statusCode()).isEqualTo(200);
        assertThat(traces.body()).contains("storage-failure-first", "storage-failure-second");
        HttpResponse<String> storage = get("/api/easy-observability/traces/storage", "storage-status");
        assertThat(storage.statusCode()).isEqualTo(200);
        assertThat(storage.body()).contains("\"diagnosticPersistenceEnabled\":false");
        assertThat(TRACE_FILE).doesNotExist();
    }

    private boolean awaitPersistenceDisabled() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (diagnosticTraceStore.storageStatus().persistenceEnabled() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return !diagnosticTraceStore.storageStatus().persistenceEnabled();
    }

    private RecentTraceStore.TraceRecord awaitDiagnosticTrace(String traceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        RecentTraceStore.TraceRecord trace;
        while ((trace = diagnosticTraceStore.findByTraceId(traceId, "HTTP")) == null
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return trace;
    }

    private HttpResponse<String> get(String path, String traceId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Trace-Id", traceId)
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path createStorageDirectory() {
        try {
            Path directory = Files.createTempDirectory("easy-observability-trace-storage");
            Files.createDirectory(directory.resolve("traces.jsonl"));
            return directory;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(SlowController.class)
    static class TestApplication { }

    @RestController
    static class SlowController {
        @GetMapping("/slow/{id}")
        String slow(@PathVariable String id) throws InterruptedException {
            Thread.sleep(30);
            return id;
        }
    }
}
