package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.TraceBlock;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceIdContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/** A single real request that crosses JDBC, Redis, HTTP and Kafka. */
@RestController
public class DemoRealFlowController {
    private final DemoDatabaseService database;
    private final StringRedisTemplate redis;
    private final RestClient httpBingo;
    private final DemoKafkaService kafka;

    public DemoRealFlowController(DemoDatabaseService database, StringRedisTemplate redis,
                                  RestClient httpBingo, DemoKafkaService kafka) {
        this.database = database;
        this.redis = redis;
        this.httpBingo = httpBingo;
        this.kafka = kafka;
    }

    @GetMapping("/api/demo/real-flow")
    public Map<String, Object> realFlow() {
        String traceId = TraceIdContext.currentTraceId();
        String orderId = UUID.randomUUID().toString();
        String customer = "demo-customer";
        Map<String, Object> storedOrder = database.createAndReadOrder(orderId, customer);

        String cachedValue = TraceBlock.call("Redis SET/GET", SpanKind.REDIS, "order cache", () -> {
            redis.opsForValue().set("easy-observability:demo:order:" + orderId, customer);
            return redis.opsForValue().get("easy-observability:demo:order:" + orderId);
        });
        String remoteBody = httpBingo.get().uri("/anything/orders/{id}", orderId)
                .retrieve().body(String.class);
        Map<String, Object> sent = kafka.publishOnly(traceId, orderId);
        return Map.of("traceId", traceId, "order", storedOrder, "redisValue", cachedValue,
                "remoteRequest", remoteBody == null ? "" : remoteBody.substring(0, Math.min(240, remoteBody.length())),
                "kafka", sent);
    }

    @GetMapping("/api/demo/transaction/error")
    public Map<String, Object> transactionRollback() {
        database.insertThenRollback(UUID.randomUUID().toString());
        return Map.of("unexpected", "transaction should have rolled back");
    }
}
