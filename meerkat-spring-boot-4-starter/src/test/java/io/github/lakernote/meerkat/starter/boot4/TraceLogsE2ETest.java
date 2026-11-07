package io.github.lakernote.meerkat.starter.boot4;

import io.github.lakernote.meerkat.integration.logging.internal.TraceLogInstaller;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Application log lines written during a kept trace show up in its detail, in order and with their step. */
class TraceLogsE2ETest {
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
    void failedRequestTraceCarriesItsLogLines() throws Exception {
        ConfigurableApplicationContext context = start();
        int port = port(context);
        assertThat(context.getBeansOfType(TraceLogInstaller.class)).hasSize(1);

        HttpResponse<String> response = get(port, "/logged");
        assertThat(response.statusCode()).isEqualTo(500);
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();

        String trace = trace(port, traceId);
        assertThat(trace).contains("\"logs\":[", "\"message\":\"loading order 7\"", "\"level\":\"WARN\"",
                "\"message\":\"stock is low for order 7\"", LogsController.class.getName());
        assertThat(trace.indexOf("loading order 7")).isLessThan(trace.indexOf("stock is low"));
    }

    @Test
    void canBeTurnedOff() throws Exception {
        ConfigurableApplicationContext context = start("meerkat.trace.logs.enabled=false");
        int port = port(context);
        // The appender is JVM-wide, so another application context cached by the test run may still feed logs;
        // what this context controls is whether it installs one.
        assertThat(context.getBeansOfType(TraceLogInstaller.class)).isEmpty();

        HttpResponse<String> response = get(port, "/logged");
        assertThat(trace(port, response.headers().firstValue("X-Trace-Id").orElseThrow())).contains("\"logs\":");
    }

    private ConfigurableApplicationContext start(String... extra) throws IOException {
        Path storage = Files.createTempDirectory("meerkat-trace-logs-");
        directories.add(storage);
        List<String> properties = new ArrayList<>(List.of("server.port=0", "meerkat.metrics.enabled=false",
                "meerkat.storage.directory=" + storage));
        properties.addAll(List.of(extra));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .properties(properties.toArray(String[]::new))
                .run();
        contexts.add(context);
        return context;
    }

    private static int port(ConfigurableApplicationContext context) {
        return context.getBean(Environment.class).getRequiredProperty("local.server.port", Integer.class);
    }

    private static String trace(int port, String traceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        HttpResponse<String> response;
        do {
            response = get(port, "/api/meerkat/traces/" + traceId + "?source=HTTP");
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
    @Import(LogsController.class)
    static class TestApplication {
    }

    @RestController
    static class LogsController {
        private static final Log LOG = LogFactory.getLog(LogsController.class);

        @GetMapping(value = "/logged", produces = "text/plain")
        ResponseEntity<String> logged() {
            LOG.info("loading order 7");
            LOG.warn("stock is low for order 7");
            return ResponseEntity.status(500).body("failed");
        }
    }
}
