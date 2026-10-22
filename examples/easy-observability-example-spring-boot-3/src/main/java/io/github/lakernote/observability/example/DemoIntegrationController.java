package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.TraceIdContext;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceBlock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.time.Duration;
import java.util.UUID;

/** 提供能直接核对真实依赖 Trace 的演示入口。 */
@RestController
public class DemoIntegrationController {
    private final DemoDatabaseService database;
    private final StringRedisTemplate redis;
    private final RestClient httpBingo;
    private final DemoKafkaService kafka;

    public DemoIntegrationController(DemoDatabaseService database, StringRedisTemplate redis,
                                     RestClient httpBingo, DemoKafkaService kafka) {
        this.database = database;
        this.redis = redis;
        this.httpBingo = httpBingo;
        this.kafka = kafka;
    }

    @GetMapping("/api/demo/db")
    public Map<String, Object> database(@RequestParam(defaultValue = "normal") String mode) {
        requireMode(mode);
        String orderId = UUID.randomUUID().toString();
        if ("error".equals(mode)) {
            TraceBlock.run("JDBC duplicate-key failure", SpanKind.DATABASE, "H2 demo_orders",
                    () -> database.insertDuplicateOrder(orderId));
            return Map.of("unexpected", "duplicate key should fail");
        }
        if ("slow".equals(mode)) {
            return TraceBlock.call("JDBC simulated slow query", SpanKind.DATABASE, "H2 demo_orders", () -> {
                pause(900);
                return database.createAndReadOrder(orderId, "demo-customer");
            });
        }
        return database.createAndReadOrder(orderId, "demo-customer");
    }

    @GetMapping("/api/demo/redis")
    public Map<String, Object> redis(@RequestParam(defaultValue = "normal") String mode) {
        requireMode(mode);
        String traceId = TraceIdContext.currentTraceId();
        return TraceBlock.call("Redis " + mode, SpanKind.REDIS, "redis localhost:16379", () -> {
            if ("error".equals(mode)) {
                String key = "easy-observability:demo:wrong-type:" + UUID.randomUUID();
                redis.opsForValue().set(key, "string value");
                // Deliberately issue a list command against a string key to produce Redis WRONGTYPE.
                return Map.of("unexpected", String.valueOf(redis.opsForList().rightPop(key)));
            }
            Long count = redis.opsForValue().increment("easy-observability:demo:redis");
            redis.opsForValue().set("easy-observability:demo:last-trace-id", traceId);
            if ("slow".equals(mode)) {
                String queueItem = redis.opsForList().leftPop("easy-observability:demo:queue", Duration.ofSeconds(1));
                return Map.of("traceId", traceId, "counter", count == null ? 0 : count,
                        "queueItem", queueItem == null ? "empty (BLPOP timeout)" : queueItem);
            }
            return Map.of("traceId", traceId, "counter", count == null ? 0 : count,
                    "storedTraceId", redis.opsForValue().get("easy-observability:demo:last-trace-id"));
        });
    }

    @GetMapping("/api/demo/remote")
    public Map<String, Object> remote(@RequestParam(defaultValue = "normal") String mode) {
        requireMode(mode);
        String traceId = TraceIdContext.currentTraceId();
        String path = switch (mode) {
            case "normal" -> "/anything/easy-observability";
            case "slow" -> "/delay/1";
            case "error" -> "/status/500";
            default -> throw new IllegalArgumentException("mode must be normal, slow or error");
        };
        String response = httpBingo.get().uri(path).retrieve().body(String.class);
        return Map.of("traceId", traceId, "remoteUrl", path,
                "response", response == null ? "" : response.substring(0, Math.min(240, response.length())));
    }

    @GetMapping("/api/demo/kafka")
    public Map<String, Object> kafka() {
        return kafka.publishAndVerify(TraceIdContext.currentTraceId(), "normal");
    }

    @GetMapping("/api/demo/kafka/slow")
    public Map<String, Object> slowKafkaConsumer() {
        return kafka.publishAndVerify(TraceIdContext.currentTraceId(), "slow");
    }

    @GetMapping("/api/demo/kafka/error")
    public Map<String, Object> failedKafkaConsumer() {
        return kafka.publishAndVerify(TraceIdContext.currentTraceId(), "error");
    }

    @GetMapping("/api/demo/kafka/status")
    public Map<String, Object> kafkaStatus() {
        return kafka.lastConsumed();
    }

    private static void requireMode(String mode) {
        if (!"normal".equals(mode) && !"slow".equals(mode) && !"error".equals(mode)) {
            throw new IllegalArgumentException("mode must be normal, slow or error");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo delay interrupted", failure);
        }
    }
}
