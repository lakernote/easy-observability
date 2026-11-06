package io.github.lakernote.meerkat.starter.boot4;

import io.github.lakernote.meerkat.trace.TraceIdContext;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import io.github.lakernote.meerkat.trace.internal.store.TraceRecord;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against real Kafka and Redis containers; skipped when Docker is unavailable. */
@EnabledIf("dockerAvailable")
@SpringBootTest(classes = MiddlewareContainersE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "meerkat.metrics.enabled=false",
                "meerkat.trace.kafka-consumer-slow-threshold=1ms",
                "spring.kafka.consumer.group-id=meerkat-containers-e2e",
                "spring.kafka.consumer.auto-offset-reset=earliest"
        })
class MiddlewareContainersE2ETest {
    private static final String TOPIC = "meerkat-containers-e2e";
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static KafkaContainer kafka;
    private static GenericContainer<?> redis;

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    @Autowired
    private Listener listener;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        kafka = new KafkaContainer("apache/kafka:4.1.0");
        redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        kafka.start();
        redis.start();
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("meerkat.storage.directory", STORAGE_DIRECTORY::toString);
    }

    @AfterAll
    static void stopContainers() throws IOException {
        if (kafka != null) kafka.stop();
        if (redis != null) redis.stop();
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void redisCommandsAndKafkaMessagesJoinTheRequestTrace() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/order")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(response.body()).isEqualTo("Ada");
        assertThat(listener.consumerTraceId.get(30, TimeUnit.SECONDS)).isEqualTo(traceId);

        TraceRecord http = await(traceId, "HTTP");
        assertThat(http).isNotNull();
        List<String> kinds = flatten(http.treeNodes()).stream().map(TraceNode::kind).toList();
        assertThat(kinds).as(http.treeNodes().toString()).contains("Redis", "Kafka");
        assertThat(kinds.stream().filter("Redis"::equals)).hasSize(2);

        TraceRecord consumer = await(traceId, "KAFKA_CONSUMER");
        assertThat(consumer).isNotNull();
        assertThat(consumer.tag()).contains("topic=" + TOPIC);
    }

    private TraceRecord await(String traceId, String source) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            TraceRecord trace = traceStore.find(traceId, source);
            if (trace != null) return trace;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private static List<TraceNode> flatten(List<TraceNode> nodes) {
        List<TraceNode> all = new ArrayList<>();
        for (TraceNode node : nodes) {
            all.add(node);
            all.addAll(flatten(node.children()));
        }
        return all;
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("meerkat-containers-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({OrderController.class, Listener.class})
    static class TestApplication {
    }

    @RestController
    static class OrderController {
        private final StringRedisTemplate redis;
        private final KafkaTemplate<Object, Object> kafka;

        OrderController(StringRedisTemplate redis, KafkaTemplate<Object, Object> kafka) {
            this.redis = redis;
            this.kafka = kafka;
        }

        @GetMapping(value = "/order", produces = "text/plain")
        ResponseEntity<String> order() throws Exception {
            redis.opsForValue().set("customer:42", "Ada");
            String customer = redis.opsForValue().get("customer:42");
            kafka.send(TOPIC, "order-1").get(10, TimeUnit.SECONDS);
            // An error response makes the trace a diagnostic trace that is kept in the store.
            return ResponseEntity.status(500).body(customer);
        }
    }

    @Component
    static class Listener {
        final CompletableFuture<String> consumerTraceId = new CompletableFuture<>();

        @KafkaListener(topics = TOPIC)
        void onMessage(ConsumerRecord<String, String> record) throws InterruptedException {
            consumerTraceId.complete(TraceIdContext.currentTraceId());
            // Exceeds the 1ms consumer threshold so the consumer trace is retained as slow.
            Thread.sleep(20);
        }
    }
}
