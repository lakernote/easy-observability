package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceBlock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 定时执行真实 Redis 计数，用于展示 Job 根 Trace 及其 Redis 子调用。 */
@Component
public class DemoScheduledJob {
    private final StringRedisTemplate redis;

    public DemoScheduledJob(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Scheduled(initialDelayString = "${easy.observability.demo.schedule-initial-delay:5000}",
            fixedDelayString = "${easy.observability.demo.schedule-delay:30000}")
    public void countHeartbeat() {
        TraceBlock.call("Redis INCR + BLPOP", SpanKind.REDIS, "redis localhost:16379", () -> {
            Long heartbeat = redis.opsForValue().increment("easy-observability:demo:scheduled-heartbeats");
            redis.opsForList().leftPop("easy-observability:demo:scheduled-queue", Duration.ofSeconds(1));
            return heartbeat;
        });
    }
}
