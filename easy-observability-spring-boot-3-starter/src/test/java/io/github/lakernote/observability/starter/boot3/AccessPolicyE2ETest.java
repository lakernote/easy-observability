package io.github.lakernote.observability.starter.boot3;

import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the access policy over real HTTP requests. */
class AccessPolicyE2ETest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String ROLE_HEADER = "X-Test-Role";

    @Nested
    @SpringBootTest(classes = DefaultPolicyApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "logging.level.io.github.lakernote.observability=OFF")
    class DefaultPolicy {
        private static final Path STORAGE = createStorage();

        @LocalServerPort
        int port;

        @DynamicPropertySource
        static void storage(DynamicPropertyRegistry registry) {
            registerStorage(registry, STORAGE);
        }

        @Test
        void allowsViewingAndDeniesClearing() throws Exception {
            HttpResponse<String> traces = send(port, "GET", "/api/easy-observability/traces", null);
            assertThat(traces.statusCode()).isEqualTo(200);
            assertThat(traces.body()).isEqualTo("[]");
            assertThat(send(port, "GET", "/api/easy-observability/metrics/storage", null).statusCode())
                    .isEqualTo(200);

            HttpResponse<String> permissions = send(port, "GET", "/api/easy-observability/permissions", null);
            assertThat(permissions.statusCode()).isEqualTo(200);
            assertThat(permissions.body()).isEqualTo("{\"canClear\":false}");

            assertThat(send(port, "POST", "/api/easy-observability/storage/clear", null).statusCode())
                    .isEqualTo(403);
        }
    }

    @Nested
    @SpringBootTest(classes = RoleBasedPolicyApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "logging.level.io.github.lakernote.observability=OFF")
    class CustomPolicy {
        private static final Path STORAGE = createStorage();

        @LocalServerPort
        int port;

        @DynamicPropertySource
        static void storage(DynamicPropertyRegistry registry) {
            registerStorage(registry, STORAGE);
        }

        @Test
        void appliesUserDefinedPolicy() throws Exception {
            assertThat(send(port, "GET", "/api/easy-observability/traces", null).statusCode()).isEqualTo(403);
            assertThat(send(port, "GET", "/api/easy-observability/metrics/storage", null).statusCode())
                    .isEqualTo(403);
            assertThat(send(port, "GET", "/api/easy-observability/permissions", null).statusCode())
                    .isEqualTo(403);
            assertThat(send(port, "POST", "/api/easy-observability/storage/clear", null).statusCode())
                    .isEqualTo(403);

            assertThat(send(port, "GET", "/api/easy-observability/traces", "developer").statusCode())
                    .isEqualTo(200);
            assertThat(send(port, "GET", "/api/easy-observability/permissions", "developer").body())
                    .isEqualTo("{\"canClear\":false}");
            assertThat(send(port, "POST", "/api/easy-observability/storage/clear", "developer").statusCode())
                    .isEqualTo(403);

            assertThat(send(port, "GET", "/api/easy-observability/permissions", "ops").body())
                    .isEqualTo("{\"canClear\":true}");
            HttpResponse<String> cleared = send(port, "POST", "/api/easy-observability/storage/clear", "ops");
            assertThat(cleared.statusCode()).isEqualTo(200);
            assertThat(cleared.body()).contains("clearedRecentTraceCount");
        }
    }

    private static HttpResponse<String> send(int port, String method, String path, String role)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (role != null) builder.header(ROLE_HEADER, role);
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Path createStorage() {
        try {
            Path directory = Files.createTempDirectory("easy-observability-access-policy");
            directory.toFile().deleteOnExit();
            return directory;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void registerStorage(DynamicPropertyRegistry registry, Path storage) {
        registry.add("easy.observability.metrics.storage-directory", () -> storage.resolve("metrics").toString());
        registry.add("easy.observability.trace.diagnostic-trace-file",
                () -> storage.resolve("traces.jsonl").toString());
    }

    /** Stands in for a role resolved by the application's own authentication. */
    private static String currentRole() {
        var attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest().getHeader(ROLE_HEADER);
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DefaultPolicyApplication { }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class RoleBasedPolicyApplication {
        @Bean
        ObservabilityAccessPolicy observabilityAccessPolicy() {
            return new ObservabilityAccessPolicy() {
                @Override
                public boolean canView() {
                    return "developer".equals(currentRole()) || "ops".equals(currentRole());
                }

                @Override
                public boolean canClear() {
                    return "ops".equals(currentRole());
                }
            };
        }
    }
}
