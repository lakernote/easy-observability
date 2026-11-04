package io.github.lakernote.observability.starter.boot4;

import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.github.lakernote.observability.trace.TraceSpan;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hosts that already use Micrometer Tracing keep their own MDC trace ID next to the component's. */
@SpringBootTest(classes = MicrometerTracingCoexistenceE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.tracing.sampling.probability=1.0",
                "easy.observability.metrics.enabled=false"
        })
class MicrometerTracingCoexistenceE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void traceStorage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.trace.diagnostic-trace-file",
                () -> STORAGE_DIRECTORY.resolve("diagnostic-traces.jsonl").toString());
    }

    @AfterAll
    static void deleteStorage() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void componentAndMicrometerTraceIdsDoNotOverwriteEachOther() throws Exception {
        HttpResponse<String> response = get("/coexistence");

        assertThat(response.statusCode()).isEqualTo(500);
        String componentTraceId = response.headers().firstValue("X-Trace-Id").orElseThrow();
        Map<String, String> seen = parse(response.body());
        assertThat(seen.get("tracerBefore")).isNotBlank();
        assertThat(seen.get("componentBefore")).isEqualTo(componentTraceId);
        assertThat(seen.get("componentAfter")).isEqualTo(componentTraceId);
        assertThat(seen.get("componentInSpan")).isEqualTo(componentTraceId);
        assertThat(seen.get("micrometerMdcBefore")).isEqualTo(seen.get("tracerBefore"));
        assertThat(seen.get("micrometerMdcInSpan")).isEqualTo(seen.get("tracerBefore"));
        assertThat(seen.get("micrometerMdcAfter")).isEqualTo(seen.get("tracerBefore"));

        assertThat(awaitTrace(componentTraceId).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> awaitTrace(String traceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        HttpResponse<String> response;
        do {
            response = get("/api/easy-observability/traces/" + traceId + "?source=HTTP");
            if (response.statusCode() == 200) return response;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return response;
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : body.split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return values;
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("easy-observability-tracing-coexistence-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(CoexistenceController.class)
    static class TestApplication {
        @Bean
        NestedWork nestedWork() {
            return new NestedWork();
        }
    }

    @RestController
    static class CoexistenceController {
        private final Tracer tracer;
        private final NestedWork nestedWork;

        CoexistenceController(Tracer tracer, NestedWork nestedWork) {
            this.tracer = tracer;
            this.nestedWork = nestedWork;
        }

        @GetMapping(value = "/coexistence", produces = "text/plain")
        ResponseEntity<String> coexistence() {
            Map<String, String> seen = new LinkedHashMap<>();
            seen.put("tracerBefore", tracer.currentSpan() == null ? "" : tracer.currentSpan().context().traceId());
            seen.put("componentBefore", TraceIdContext.currentTraceId());
            seen.put("micrometerMdcBefore", MDC.get("traceId"));
            nestedWork.run(seen);
            seen.put("componentAfter", TraceIdContext.currentTraceId());
            seen.put("micrometerMdcAfter", MDC.get("traceId"));
            StringBuilder body = new StringBuilder();
            seen.forEach((key, value) -> body.append(key).append('=').append(value).append('\n'));
            // An error response makes the trace a diagnostic trace that the dashboard API can look up.
            return ResponseEntity.status(500).body(body.toString());
        }
    }

    static class NestedWork {
        @TraceSpan(name = "nested-work", kind = SpanKind.SERVICE)
        public void run(Map<String, String> seen) {
            seen.put("componentInSpan", TraceIdContext.currentTraceId());
            seen.put("micrometerMdcInSpan", MDC.get("traceId"));
        }
    }
}
