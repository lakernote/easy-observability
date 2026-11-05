package io.github.lakernote.observability.starter.boot4;

import io.github.lakernote.observability.trace.internal.store.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** WebFlux applications start normally; HTTP entry Trace and the dashboard are Servlet-only. */
class ReactiveApplicationE2ETest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private ConfigurableApplicationContext context;
    private Path storage;

    @AfterEach
    void close() throws IOException {
        if (context != null) context.close();
        if (storage != null) FileSystemUtils.deleteRecursively(storage);
    }

    @Test
    void reactiveRequestsAreServedWithoutHttpEntryTraces() throws Exception {
        storage = Files.createTempDirectory("easy-observability-reactive-");
        context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.REACTIVE)
                .properties("server.port=0",
                        "easy.observability.trace.http-slow-threshold=1ms",
                        "easy.observability.storage.directory=" + storage)
                .run();
        int port = context.getBean(Environment.class).getRequiredProperty("local.server.port", Integer.class);

        for (int i = 0; i < 3; i++) {
            HttpResponse<String> response = get(port, "/reactive");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
        }

        assertThat(context.getBean(TraceStore.class).size()).isZero();
        assertThat(get(port, "/easy-observability/").statusCode()).isEqualTo(404);
        assertThat(get(port, "/api/easy-observability/traces").statusCode()).isEqualTo(404);
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
    @Import(ReactiveController.class)
    static class TestApplication {
    }

    @RestController
    static class ReactiveController {
        @GetMapping(value = "/reactive", produces = "text/plain")
        Mono<String> reactive() {
            return Mono.delay(Duration.ofMillis(5)).map(ignored -> "ok");
        }
    }
}
