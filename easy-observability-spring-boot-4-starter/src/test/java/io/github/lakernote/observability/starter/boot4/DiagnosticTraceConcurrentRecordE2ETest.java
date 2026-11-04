package io.github.lakernote.observability.starter.boot4;

import io.github.lakernote.observability.trace.internal.BoundedHistoryWriter;
import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStore;
import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStoreTestSupport;
import io.github.lakernote.observability.trace.internal.store.RecentTraceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DiagnosticTraceConcurrentRecordE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "easy.observability.metrics.enabled=false",
                "easy.observability.trace.enabled=false",
                "easy.observability.web.enabled=false"
        })
class DiagnosticTraceConcurrentRecordE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static final Path HISTORY_FILE = STORAGE_DIRECTORY.resolve("diagnostic-traces.jsonl");
    private static final CountDownLatch SERIALIZATION_STARTED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_SERIALIZATION = new CountDownLatch(1);
    private static final CountDownLatch SERIALIZATION_FINISHED = new CountDownLatch(1);
    private static final AtomicBoolean BLOCK_FIRST_SERIALIZATION = new AtomicBoolean(true);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private BoundedHistoryWriter historyWriter;

    @DynamicPropertySource
    static void traceStorage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.trace.diagnostic-trace-file", HISTORY_FILE::toString);
    }

    @AfterEach
    void releaseSerializerAndCloseWriter() {
        RELEASE_SERIALIZATION.countDown();
        historyWriter.close();
    }

    @AfterAll
    static void deleteStorage() throws IOException {
        Files.deleteIfExists(HISTORY_FILE.resolveSibling(HISTORY_FILE.getFileName() + ".tmp"));
        Files.deleteIfExists(HISTORY_FILE);
        Files.deleteIfExists(STORAGE_DIRECTORY);
    }

    @Test
    void aBlockedArchiveSerializationDoesNotBlockAnotherBusinessRequest() throws Exception {
        HttpResponse<String> first = get("/record/first");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo("recorded-first");
        assertThat(SERIALIZATION_STARTED.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<HttpResponse<String>> second = getAsync("/record/second");
        try {
            HttpResponse<String> response = second.get(2, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("recorded-second");
        } finally {
            RELEASE_SERIALIZATION.countDown();
            try {
                second.get(5, TimeUnit.SECONDS);
            } catch (Exception incompleteRequest) {
                second.cancel(true);
            }
        }

        assertThat(SERIALIZATION_FINISHED.await(5, TimeUnit.SECONDS)).isTrue();
        HttpResponse<String> probe = get("/probe");
        assertThat(probe.statusCode()).isEqualTo(200);
        assertThat(probe.body()).isEqualTo("ok");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP_CLIENT.send(request(path), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> getAsync(String path) {
        return HTTP_CLIENT.sendAsync(request(path), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("easy-observability-concurrent-trace");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ProbeController.class)
    static class TestApplication {
        /** Internal stores are not replaceable beans, so the test swaps in a hooked instance after creation. */
        @Bean
        static BeanPostProcessor blockingDiagnosticTraceStore(ObjectProvider<BoundedHistoryWriter> writer) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DiagnosticTraceStore)) return bean;
                    return DiagnosticTraceStoreTestSupport.storeWithWriteHook(HISTORY_FILE.toString(), 1000,
                            4 * 1024 * 1024, writer.getObject(),
                            DiagnosticTraceConcurrentRecordE2ETest::blockFirstSerialization);
                }
            };
        }
    }

    @RestController
    static class ProbeController {
        private final DiagnosticTraceStore store;

        ProbeController(DiagnosticTraceStore store) {
            this.store = store;
        }

        @GetMapping("/record/{id}")
        String record(@PathVariable String id) {
            store.recordDiagnosticTrace(new RecentTraceStore.TraceRecord(
                    1, "trace-" + id, "/record/" + id, "GET", 200, 1,
                    true, false, List.of(), Instant.now(), Thread.currentThread().getId(),
                    0, 0, "HTTP", "", "", Thread.currentThread().getName(), "", false, false));
            return "recorded-" + id;
        }

        @GetMapping("/probe")
        String probe() {
            return "ok";
        }
    }

    private static void blockFirstSerialization(RecentTraceStore.TraceRecord trace) throws IOException {
        boolean firstSerialization = BLOCK_FIRST_SERIALIZATION.compareAndSet(true, false);
        try {
            if (firstSerialization) {
                SERIALIZATION_STARTED.countDown();
                try {
                    if (!RELEASE_SERIALIZATION.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release trace serialization");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Trace serialization was interrupted", interrupted);
                }
            }
        } finally {
            if (firstSerialization) SERIALIZATION_FINISHED.countDown();
        }
    }
}
