package io.github.lakernote.meerkat.starter.boot3;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
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

@SpringBootTest(classes = MetricsStorageFailOpenE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "meerkat.trace.enabled=false"
        })
class MetricsStorageFailOpenE2ETest {
    private static final Path BLOCKING_FILE = createBlockingFile();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void metricsStorage(DynamicPropertyRegistry registry) {
        registry.add("meerkat.storage.directory",
                () -> BLOCKING_FILE.resolve("storage").toString());
    }

    @AfterAll
    static void deleteBlockingFile() throws IOException {
        Files.deleteIfExists(BLOCKING_FILE);
    }

    @Test
    void keepsTheHostApplicationAndMetricsEndpointsAvailableWhenLocalStorageFails() throws Exception {
        HttpResponse<String> probe = get("/probe");
        assertThat(probe.statusCode()).isEqualTo(200);
        assertThat(probe.body()).isEqualTo("ok");

        HttpResponse<String> page = get("/meerkat/metrics/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("指标趋势");

        HttpResponse<String> storage = get("/api/meerkat/metrics/storage");
        assertThat(storage.statusCode()).isEqualTo(200);
        assertThat(storage.body()).contains("\"persistent\":false");

        HttpResponse<String> resources = get("/api/meerkat/metrics/resources?period=7d");
        assertThat(resources.statusCode()).isEqualTo(200);
        assertThat(resources.body()).contains("\"period\":\"WEEK\"", "\"persistent\":false");

        HttpResponse<String> operations = get("/api/meerkat/metrics/operations?window=30d");
        assertThat(operations.statusCode()).isEqualTo(200);
        assertThat(operations.body()).contains("\"persistent\":false", "\"collecting\":false");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path createBlockingFile() {
        try {
            Path file = Files.createTempFile("meerkat-storage-parent", ".tmp");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ProbeController.class)
    static class TestApplication { }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String probe() {
            return "ok";
        }
    }
}
