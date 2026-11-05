package io.github.lakernote.observability.starter.boot3;

import com.jayway.jsonpath.JsonPath;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceBlock;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LiveDiagnosticsE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "easy.observability.metrics.enabled=false")
class LiveDiagnosticsE2ETest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final CountDownLatch ENTERED = new CountDownLatch(1);
    static final CountDownLatch RELEASE = new CountDownLatch(1);

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) throws java.io.IOException {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("easy-observability-live");
        directory.toFile().deleteOnExit();
        registry.add("easy.observability.storage.directory", directory::toString);
    }

    @Test
    void showsTheRunningRequestWithItsCurrentStepAndStackUntilItFinishes() throws Exception {
        CompletableFuture<HttpResponse<String>> blocked = HTTP_CLIENT.sendAsync(request("/reports/7"),
                HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(ENTERED.await(10, TimeUnit.SECONDS)).isTrue();
            Map<String, Object> running = awaitRunning("/reports/7");

            assertThat(running.get("name")).isEqualTo("/reports/7 · GET");
            assertThat(running.get("source")).isEqualTo("HTTP");
            assertThat(running.get("async")).isEqualTo(false);
            assertThat(running.get("threadName")).asString().startsWith("http-nio-");
            assertThat((String) running.get("traceId")).isNotBlank();
            assertThat(JsonPath.<String>read(running, "$.currentStep.name")).isEqualTo("ReportService.render");
            assertThat(JsonPath.<String>read(running, "$.currentStep.kind")).isEqualTo("Service");
            assertThat(JsonPath.<List<String>>read(running, "$.stack"))
                    .anyMatch(line -> line.contains("LiveDiagnosticsE2ETest$ReportController.report"));

            String requests = get("/api/easy-observability/live/requests").body();
            assertThat(JsonPath.<List<String>>read(requests, "$.items[*].path"))
                    .noneMatch(path -> path.startsWith("/api/easy-observability"));

            HttpResponse<String> threads = get("/api/easy-observability/live/threads?sampleMs=200");
            assertThat(threads.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Integer>read(threads.body(), "$.sampleMillis")).isEqualTo(200);
            assertThat(JsonPath.<List<String>>read(threads.body(), "$.threads[*].name"))
                    .anyMatch(name -> name.equals(running.get("threadName")));
            assertThat(get("/api/easy-observability/live/threads?sampleMs=9000").statusCode()).isEqualTo(400);

            HttpResponse<String> dump = get("/api/easy-observability/live/threads/dump");
            assertThat(dump.statusCode()).isEqualTo(200);
            assertThat(dump.headers().firstValue("Content-Disposition")).get().asString()
                    .startsWith("attachment; filename=\"threads-");
            assertThat(dump.body()).startsWith("Full thread dump ")
                    .contains("\"" + running.get("threadName") + "\"", "LiveDiagnosticsE2ETest$ReportController.report");
        } finally {
            RELEASE.countDown();
        }
        assertThat(blocked.get(10, TimeUnit.SECONDS).body()).isEqualTo("report 7");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (findRunning("/reports/7") != null && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(findRunning("/reports/7")).isNull();
    }

    @Test
    void servesTheLivePageLinkedFromEveryView() throws Exception {
        HttpResponse<String> page = get("/easy-observability/live/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("实时诊断", "../assets/live.js");
        assertThat(get("/easy-observability/assets/live.js").statusCode()).isEqualTo(200);
        assertThat(get("/easy-observability/assets/live.css").statusCode()).isEqualTo(200);
        assertThat(get("/easy-observability/live").statusCode()).isEqualTo(308);
        assertThat(get("/easy-observability/").body()).contains("href=\"live/\"");
        assertThat(get("/easy-observability/stats/").body()).contains("href=\"../live/\"");
        assertThat(get("/easy-observability/metrics/").body()).contains("href=\"../live/\"");
    }

    private Map<String, Object> awaitRunning(String path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            Map<String, Object> running = findRunning(path);
            if (running != null && running.get("currentStep") != null) return running;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("request " + path + " not listed: " + get("/api/easy-observability/live/requests").body());
    }

    private Map<String, Object> findRunning(String path) throws Exception {
        List<Map<String, Object>> items = JsonPath.read(get("/api/easy-observability/live/requests").body(),
                "$.items[?(@.path == '" + path + "')]");
        return items.isEmpty() ? null : items.get(0);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP_CLIENT.send(request(path), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ReportController.class)
    static class TestApplication {
    }

    @RestController
    static class ReportController {
        @GetMapping("/reports/{id}")
        String report(@org.springframework.web.bind.annotation.PathVariable String id) {
            return TraceBlock.call("ReportService.render", SpanKind.SERVICE, () -> {
                ENTERED.countDown();
                try {
                    RELEASE.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "report " + id;
            });
        }
    }
}
