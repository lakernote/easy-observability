package com.laker.observability.example;

import com.laker.observability.trace.TraceIdContext;
import com.laker.observability.trace.SpanKind;
import com.laker.observability.trace.TraceBlock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.time.Duration;

/** 提供可直接触发并核对 Redis、Kafka 和远程 HTTP Trace 的真实示例入口。 */
@RestController
public class DemoIntegrationController {
    private final StringRedisTemplate redis;
    private final RestClient httpBingo;
    private final DemoKafkaService kafka;

    public DemoIntegrationController(StringRedisTemplate redis, RestClient httpBingo, DemoKafkaService kafka) {
        this.redis = redis;
        this.httpBingo = httpBingo;
        this.kafka = kafka;
    }

    @GetMapping("/api/demo/redis")
    public Map<String, Object> redis() {
        String traceId = TraceIdContext.currentTraceId();
        return TraceBlock.call("Redis INCR/SET/GET", SpanKind.REDIS, "redis localhost:16379", () -> {
            Long count = redis.opsForValue().increment("easy-observability:demo:redis");
            redis.opsForValue().set("easy-observability:demo:last-trace-id", traceId);
            // 真实 Redis BLPOP 等待约 1 秒，让本地示例稳定进入慢请求列表并保留完整调用树。
            String queueItem = redis.opsForList().leftPop("easy-observability:demo:queue", Duration.ofSeconds(1));
            return Map.of("traceId", traceId, "counter", count == null ? 0 : count,
                    "storedTraceId", redis.opsForValue().get("easy-observability:demo:last-trace-id"),
                    "queueItem", queueItem == null ? "empty (BLPOP timeout)" : queueItem);
        });
    }

    @GetMapping("/api/demo/remote")
    public Map<String, Object> remote() {
        String traceId = TraceIdContext.currentTraceId();
        String delayedResponse = httpBingo.get().uri("/delay/1").retrieve().body(String.class);
        Map<?, ?> echoedHeaders = httpBingo.get().uri("/headers").retrieve().body(Map.class);
        Object headers = echoedHeaders == null ? null : echoedHeaders.get("headers");
        return Map.of("traceId", traceId, "remoteUrl", "https://httpbingo.org",
                "remoteTraceHeader", headers == null ? "check response headers" : headers,
                "delayResponse", delayedResponse == null ? "" : delayedResponse);
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
}
