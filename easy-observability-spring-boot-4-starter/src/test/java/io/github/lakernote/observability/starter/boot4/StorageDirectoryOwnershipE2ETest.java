package io.github.lakernote.observability.starter.boot4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
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

/** Two application instances pointed at the same storage directory must not write the same files. */
class StorageDirectoryOwnershipE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @AfterAll
    static void deleteStorage() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void onlyTheFirstInstancePersistsAndTheDirectoryIsReleasedOnShutdown() throws Exception {
        try (ConfigurableApplicationContext owner = start()) {
            try (ConfigurableApplicationContext contender = start()) {
                assertThat(get(contender, "/slow/contender").statusCode()).isEqualTo(200);
                assertThat(get(owner, "/slow/owner").statusCode()).isEqualTo(200);

                assertThat(get(owner, "/api/easy-observability/traces/storage").body())
                        .contains("\"diagnosticPersistenceEnabled\":true");
                assertThat(get(owner, "/api/easy-observability/metrics/storage").body())
                        .contains("\"persistent\":true");
                assertThat(get(contender, "/api/easy-observability/traces/storage").body())
                        .contains("\"diagnosticPersistenceEnabled\":false");
                assertThat(get(contender, "/api/easy-observability/metrics/storage").body())
                        .contains("\"persistent\":false");
                // The contender still serves its in-memory history.
                assertThat(awaitTrace(contender, "contender")).isEqualTo(200);

                String archive = awaitArchiveContaining("owner");
                assertThat(archive).contains("\"traceId\":\"owner\"").doesNotContain("\"traceId\":\"contender\"");
            }
        }

        try (ConfigurableApplicationContext successor = start()) {
            assertThat(get(successor, "/api/easy-observability/traces/storage").body())
                    .contains("\"diagnosticPersistenceEnabled\":true");
            assertThat(awaitTrace(successor, "owner")).isEqualTo(200);
        }
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(TestApplication.class)
                .properties("server.port=0",
                        "easy.observability.storage.directory=" + STORAGE_DIRECTORY,
                        "easy.observability.trace.http-slow-threshold=1")
                .run();
    }

    private static int awaitTrace(ConfigurableApplicationContext context, String traceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        int status;
        do {
            status = get(context, "/api/easy-observability/traces/" + traceId + "?source=HTTP").statusCode();
            if (status == 200) return status;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return status;
    }

    private static String awaitArchiveContaining(String traceId) throws Exception {
        Path archive = STORAGE_DIRECTORY.resolve("traces.jsonl");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            if (Files.isRegularFile(archive)) {
                String content = Files.readString(archive);
                if (content.contains("\"traceId\":\"" + traceId + "\"")) return content;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return Files.isRegularFile(archive) ? Files.readString(archive) : "";
    }

    private static HttpResponse<String> get(ConfigurableApplicationContext context, String path) throws Exception {
        String port = context.getEnvironment().getProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Trace-Id", path.startsWith("/slow/") ? path.substring("/slow/".length()) : "query")
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("easy-observability-storage-ownership");
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
