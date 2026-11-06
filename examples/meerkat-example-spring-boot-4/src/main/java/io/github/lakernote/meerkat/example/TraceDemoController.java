package io.github.lakernote.meerkat.example;

import io.github.lakernote.meerkat.trace.TraceIdContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Trace diagnostics: one request per dependency and outcome, opened from the Trace page. */
@RestController
@RequestMapping("/api/demo/trace")
public class TraceDemoController {
    private final DemoDatabaseService database;
    private final StringRedisTemplate redis;
    private final RestClient httpBingo;
    private final DemoKafkaService kafka;
    private final DemoReportService reports;

    public TraceDemoController(DemoDatabaseService database, StringRedisTemplate redis, RestClient httpBingo,
                               DemoKafkaService kafka, DemoReportService reports) {
        this.database = database;
        this.redis = redis;
        this.httpBingo = httpBingo;
        this.kafka = kafka;
        this.reports = reports;
    }

    /** normal: insert + select in a transaction; slow: a 900 ms SQL statement; error: duplicate primary key. */
    @GetMapping("/db")
    public Map<String, Object> database(@RequestParam(defaultValue = "normal") String mode) {
        DemoSupport.requireMode(mode);
        String orderId = UUID.randomUUID().toString();
        if ("error".equals(mode)) {
            database.insertDuplicateOrder(orderId);
            return Map.of("unexpected", "duplicate key should fail");
        }
        if ("slow".equals(mode)) database.slowQuery(900);
        return database.createAndReadOrder(orderId, "demo-customer");
    }

    /** normal: INCR/SET/GET; slow: a 1 s BLPOP; error: a list command on a string key (WRONGTYPE). */
    @GetMapping("/redis")
    public Map<String, Object> redis(@RequestParam(defaultValue = "normal") String mode) {
        DemoSupport.requireMode(mode);
        String traceId = TraceIdContext.currentTraceId();
        if ("error".equals(mode)) {
            String key = "meerkat:demo:wrong-type:" + UUID.randomUUID();
            redis.opsForValue().set(key, "string value");
            return Map.of("unexpected", String.valueOf(redis.opsForList().rightPop(key)));
        }
        Long count = redis.opsForValue().increment("meerkat:demo:redis");
        redis.opsForValue().set("meerkat:demo:last-trace-id", traceId);
        if ("slow".equals(mode)) {
            String item = redis.opsForList().leftPop("meerkat:demo:queue", Duration.ofSeconds(1));
            return Map.of("traceId", traceId, "counter", count == null ? 0 : count,
                    "queueItem", item == null ? "empty (BLPOP timeout)" : item);
        }
        return Map.of("traceId", traceId, "counter", count == null ? 0 : count,
                "storedTraceId", String.valueOf(redis.opsForValue().get("meerkat:demo:last-trace-id")));
    }

    /** Calls httpbingo: /anything, /delay/1 or /status/500. */
    @GetMapping("/remote")
    public Map<String, Object> remote(@RequestParam(defaultValue = "normal") String mode) {
        DemoSupport.requireMode(mode);
        String path = switch (mode) {
            case "slow" -> "/delay/1";
            case "error" -> "/status/500";
            default -> "/anything/meerkat";
        };
        String response = httpBingo.get().uri(path).retrieve().body(String.class);
        return Map.of("traceId", TraceIdContext.currentTraceId(), "remoteUrl", path,
                "response", DemoSupport.excerpt(response));
    }

    /** Sends a message and waits for the listener; slow and error change what the consumer does. */
    @GetMapping("/kafka")
    public Map<String, Object> kafka(@RequestParam(defaultValue = "normal") String mode) {
        DemoSupport.requireMode(mode);
        return kafka.publishAndVerify(TraceIdContext.currentTraceId(), mode);
    }

    @GetMapping("/kafka/status")
    public Map<String, Object> kafkaStatus() {
        return kafka.lastConsumed();
    }

    /** Inserts inside a transaction and then fails, so the trace shows the SQL, the rollback and the cause. */
    @GetMapping("/transaction-rollback")
    public Map<String, Object> transactionRollback() {
        database.insertThenRollback(UUID.randomUUID().toString());
        return Map.of("unexpected", "transaction should have rolled back");
    }

    /** Loads 20 orders one by one (15 ms each, about 300 ms): the trace folds the identical SELECTs into one "count=20" line. */
    @GetMapping("/n-plus-one")
    public Map<String, Object> nPlusOne() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String id = UUID.randomUUID().toString();
            database.insertOrder(id, "n-plus-one");
            ids.add(id);
        }
        int found = 0;
        for (String id : ids) found += database.findOrder(id, 15).isEmpty() ? 0 : 1;
        return Map.of("traceId", TraceIdContext.currentTraceId(), "orders", found);
    }

    /** Business steps marked with {@code @TraceSpan} and {@code TraceBlock}; about 400 ms in total. */
    @GetMapping("/custom-steps")
    public Map<String, Object> customSteps() {
        return reports.monthlyReport("2026-11");
    }

    /** One request through H2, Redis, remote HTTP and Kafka. */
    @GetMapping("/full-flow")
    public Map<String, Object> fullFlow() {
        String traceId = TraceIdContext.currentTraceId();
        String orderId = UUID.randomUUID().toString();
        Map<String, Object> order = database.createAndReadOrder(orderId, "demo-customer");
        redis.opsForValue().set("meerkat:demo:order:" + orderId, "demo-customer");
        String cached = redis.opsForValue().get("meerkat:demo:order:" + orderId);
        String remote = httpBingo.get().uri("/anything/orders/{id}", orderId).retrieve().body(String.class);
        Map<String, Object> sent = kafka.publishOnly(traceId, orderId);
        return Map.of("traceId", traceId, "order", order, "redisValue", String.valueOf(cached),
                "remoteRequest", DemoSupport.excerpt(remote), "kafka", sent);
    }
}
