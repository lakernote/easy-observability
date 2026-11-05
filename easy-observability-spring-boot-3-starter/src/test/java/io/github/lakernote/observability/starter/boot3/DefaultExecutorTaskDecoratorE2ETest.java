package io.github.lakernote.observability.starter.boot3;

import io.github.lakernote.observability.trace.TraceIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.util.FileSystemUtils;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code @Async} work on Spring Boot's default executor joins the request Trace without disabling host decorators. */
class DefaultExecutorTaskDecoratorE2ETest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<Path> directories = new ArrayList<>();

    @AfterEach
    void close() throws IOException {
        contexts.forEach(ConfigurableApplicationContext::close);
        for (Path directory : directories) FileSystemUtils.deleteRecursively(directory);
    }

    @Test
    void defaultExecutorPropagatesTheRequestTrace() throws Exception {
        int port = start(TestApplication.class);

        Map<String, String> seen = call(port);

        assertThat(seen.get("worker")).isEqualTo(seen.get("request"));
        assertThat(trace(port, seen.get("request"))).contains("\"executor.task\"");
    }

    @Test
    void applicationDecoratorStaysActiveOnTheDefaultExecutor() throws Exception {
        int port = start(TestApplication.class, ApplicationDecoratorConfiguration.class);

        Map<String, String> seen = call(port);

        assertThat(seen.get("applicationDecorator")).isEqualTo("applied");
        // Spring Boot 3 applies a decorator only when it is unique, so the starter steps aside for the host's.
        assertThat(seen.get("worker")).isNotEqualTo(seen.get("request"));
        assertThat(trace(port, seen.get("request"))).doesNotContain("\"executor.task\"");
    }

    private int start(Class<?>... sources) throws IOException {
        Path storage = Files.createTempDirectory("easy-observability-task-decorator-");
        directories.add(storage);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(sources)
                .properties("server.port=0",
                        "easy.observability.metrics.enabled=false",
                        "easy.observability.storage.directory=" + storage)
                .run();
        contexts.add(context);
        return context.getBean(Environment.class).getRequiredProperty("local.server.port", Integer.class);
    }

    private static Map<String, String> call(int port) throws Exception {
        HttpResponse<String> response = get(port, "/async");
        assertThat(response.statusCode()).isEqualTo(500);
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : response.body().split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        assertThat(values.get("request")).isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow());
        return values;
    }

    private static String trace(int port, String traceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        HttpResponse<String> response;
        do {
            response = get(port, "/api/easy-observability/traces/" + traceId + "?source=HTTP");
            if (response.statusCode() == 200) return response.body();
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Trace " + traceId + " was not recorded: " + response.statusCode());
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableAsync
    @Import(AsyncController.class)
    static class TestApplication {
        @Bean
        AsyncWork asyncWork() {
            return new AsyncWork();
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    static class ApplicationDecoratorConfiguration {
        static final ThreadLocal<String> MARKER = new ThreadLocal<>();

        @Bean
        TaskDecorator applicationDecorator() {
            return task -> () -> {
                MARKER.set("applied");
                try {
                    task.run();
                } finally {
                    MARKER.remove();
                }
            };
        }
    }

    @RestController
    static class AsyncController {
        private final AsyncWork asyncWork;

        AsyncController(AsyncWork asyncWork) {
            this.asyncWork = asyncWork;
        }

        @GetMapping(value = "/async", produces = "text/plain")
        ResponseEntity<String> async() {
            Map<String, String> seen = new LinkedHashMap<>();
            seen.put("request", TraceIdContext.currentTraceId());
            seen.putAll(asyncWork.run().join());
            StringBuilder body = new StringBuilder();
            seen.forEach((key, value) -> body.append(key).append('=').append(value).append('\n'));
            // An error response makes the trace a diagnostic trace that the dashboard API can look up.
            return ResponseEntity.status(500).body(body.toString());
        }
    }

    static class AsyncWork {
        @Async
        public CompletableFuture<Map<String, String>> run() {
            Map<String, String> seen = new LinkedHashMap<>();
            seen.put("worker", String.valueOf(TraceIdContext.currentTraceId()));
            seen.put("applicationDecorator", String.valueOf(ApplicationDecoratorConfiguration.MARKER.get()));
            return CompletableFuture.completedFuture(seen);
        }
    }
}
