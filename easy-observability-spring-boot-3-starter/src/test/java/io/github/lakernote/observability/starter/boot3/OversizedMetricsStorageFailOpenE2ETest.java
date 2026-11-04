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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OversizedMetricsStorageFailOpenE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "easy.observability.metrics.max-storage-bytes=64MB",
                "easy.observability.web.allow-anonymous=true",
                "easy.observability.trace.enabled=false"
        })
class OversizedMetricsStorageFailOpenE2ETest {
    private static final String RRD_FILE_NAME = "common-metrics-v7-60s-30d.rrd";
    private static final long OVERSIZED_FILE_BYTES = 65L * 1024 * 1024;
    private static final Path STORAGE_DIRECTORY = createOversizedStorage();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void metricsStorage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.metrics.storage-directory", STORAGE_DIRECTORY::toString);
    }

    @AfterAll
    static void deleteStorage() throws IOException {
        Files.deleteIfExists(STORAGE_DIRECTORY.resolve(RRD_FILE_NAME));
        Files.deleteIfExists(STORAGE_DIRECTORY);
    }

    @Test
    void keepsHostAndDashboardAvailableWhenExistingMetricsHistoryExceedsLimit() throws Exception {
        assertThat(Files.size(STORAGE_DIRECTORY.resolve(RRD_FILE_NAME))).isEqualTo(OVERSIZED_FILE_BYTES);

        HttpResponse<String> probe = get("/probe");
        assertThat(probe.statusCode()).isEqualTo(200);
        assertThat(probe.body()).isEqualTo("ok");

        HttpResponse<String> page = get("/easy-observability/metrics/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("公共指标趋势");

        HttpResponse<String> storage = get("/api/easy-observability/metrics/storage");
        assertThat(storage.statusCode()).isEqualTo(200);
        assertThat(storage.body()).contains("\"initialized\":false");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path createOversizedStorage() {
        try {
            Path directory = Files.createTempDirectory("easy-observability-oversized-metrics");
            Path file = directory.resolve(RRD_FILE_NAME);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.position(OVERSIZED_FILE_BYTES - 1);
                channel.write(ByteBuffer.wrap(new byte[]{1}));
            }
            return directory;
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
