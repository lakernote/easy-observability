package io.github.lakernote.observability.starter.boot3;

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
                "easy.observability.web.allow-anonymous=true",
                "easy.observability.trace.enabled=false"
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
        registry.add("easy.observability.metrics.storage-directory",
                () -> BLOCKING_FILE.resolve("metrics").toString());
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

        HttpResponse<String> page = get("/easy-observability/metrics/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("公共指标趋势");

        HttpResponse<String> storage = get("/api/easy-observability/metrics/storage");
        assertThat(storage.statusCode()).isEqualTo(200);
        assertThat(storage.body()).contains("\"initialized\":false");

        HttpResponse<String> history = get("/api/easy-observability/metrics?period=1h");
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(history.body()).contains("\"period\":\"1h\"", "\"points\":[]");
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
            Path file = Files.createTempFile("easy-observability-storage-parent", ".tmp");
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
