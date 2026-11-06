package io.github.lakernote.meerkat.starter.boot4;

import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
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

@SpringBootTest(classes = TraceStorageFailOpenE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "meerkat.metrics.enabled=false",
                "meerkat.trace.http-slow-threshold=1",
                "meerkat.trace.cpu-threshold=0",
                "meerkat.trace.allocated-memory-threshold=0B"
        })
class TraceStorageFailOpenE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    // A regular file where the trace directory belongs makes the trace storage unusable.
    private static final Path TRACE_DIRECTORY = STORAGE_DIRECTORY.resolve("traces");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    @DynamicPropertySource
    static void diagnosticStorage(DynamicPropertyRegistry registry) {
        registry.add("meerkat.storage.directory", STORAGE_DIRECTORY::toString);
    }

    @AfterAll
    static void cleanStoragePath() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void keepsServingAndRetainingTracesWhenTraceStorageIsUnusable() throws Exception {
        assertThat(traceStore.storageStatus().persistent()).isFalse();

        for (String id : new String[]{"first", "second"}) {
            HttpResponse<String> response = get("/slow/" + id, "storage-failure-" + id);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(id);
            assertThat(response.headers().firstValue("X-Trace-Id")).contains("storage-failure-" + id);
        }
        assertThat(awaitTrace("storage-failure-second")).isTrue();

        HttpResponse<String> traces = get("/api/meerkat/traces?source=HTTP&limit=10", "storage-query");
        assertThat(traces.statusCode()).isEqualTo(200);
        assertThat(traces.body()).contains("storage-failure-first", "storage-failure-second");
        HttpResponse<String> detail = get("/api/meerkat/traces/storage-failure-first", "storage-detail");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("treeNodes");
        HttpResponse<String> storage = get("/api/meerkat/traces/storage", "storage-status");
        assertThat(storage.statusCode()).isEqualTo(200);
        assertThat(storage.body()).contains("\"persistent\":false");
        assertThat(Files.isRegularFile(TRACE_DIRECTORY)).isTrue();
    }

    private boolean awaitTrace(String traceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (traceStore.find(traceId, "HTTP") == null && System.nanoTime() < deadline) Thread.sleep(10);
        return traceStore.find(traceId, "HTTP") != null;
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
            Path directory = Files.createTempDirectory("meerkat-trace-storage");
            Files.writeString(directory.resolve("traces"), "not a directory");
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
