package io.github.lakernote.meerkat.starter.boot3;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import io.github.lakernote.meerkat.trace.TraceSpan;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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

/** Hosts that use Micrometer Tracing see the same trace ID in both MDC keys, the response header and the tracer. */
@AutoConfigureObservability(metrics = false)
@SpringBootTest(classes = MicrometerTracingCoexistenceE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.tracing.sampling.probability=1.0",
                "meerkat.metrics.enabled=false"
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
        registry.add("meerkat.storage.directory", STORAGE_DIRECTORY::toString);
    }

    @AfterAll
    static void deleteStorage() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void componentAdoptsTheMicrometerTraceIdForRootRequests() throws Exception {
        HttpResponse<String> response = get("/coexistence", Map.of());

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
        assertThat(componentTraceId).isEqualTo(seen.get("tracerBefore"));

        assertThat(awaitTrace(componentTraceId).statusCode()).isEqualTo(200);
    }

    @Test
    void upstreamTraceparentDrivesBothTraceIds() throws Exception {
        String upstream = "0af7651916cd43dd8448eb211c80319c";
        HttpResponse<String> response = get("/coexistence",
                Map.of("traceparent", "00-" + upstream + "-b7ad6b7169203331-01"));

        Map<String, String> seen = parse(response.body());
        assertThat(response.headers().firstValue("X-Trace-Id")).hasValue(upstream);
        assertThat(seen.get("tracerBefore")).isEqualTo(upstream);
        assertThat(seen.get("componentBefore")).isEqualTo(upstream);
    }

    @Test
    void explicitTraceIdHeaderStillWinsForTheComponent() throws Exception {
        HttpResponse<String> response = get("/coexistence", Map.of("X-Trace-Id", "gateway-42"));

        assertThat(response.headers().firstValue("X-Trace-Id")).hasValue("gateway-42");
        assertThat(parse(response.body()).get("componentBefore")).isEqualTo("gateway-42");
    }

    private HttpResponse<String> awaitTrace(String traceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        HttpResponse<String> response;
        do {
            response = get("/api/meerkat/traces/" + traceId + "?source=HTTP", Map.of());
            if (response.statusCode() == 200) return response;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return response;
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        headers.forEach(request::header);
        return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
            return Files.createTempDirectory("meerkat-tracing-coexistence-");
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
