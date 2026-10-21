package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceBlock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
        TraceBlock.call("Redis INCR + LPOP", SpanKind.REDIS, "redis localhost:16379", () -> {
            Long heartbeat = redis.opsForValue().increment("easy-observability:demo:scheduled-heartbeats");
            // 后台心跳应保持轻量；阻塞等待会每 30 秒自动制造一条慢 Job 事件。
            redis.opsForList().leftPop("easy-observability:demo:scheduled-queue");
            return heartbeat;
        });
    }

    /** 第二个独立任务用于让任务指标区展示多条原生 Observation 曲线。 */
    @Scheduled(initialDelayString = "${easy.observability.demo.schedule-metrics-initial-delay:10000}",
            fixedDelayString = "${easy.observability.demo.schedule-metrics-delay:45000}")
    public void countMetricsHeartbeat() {
        TraceBlock.call("Redis INCR metrics heartbeat", SpanKind.REDIS, "redis localhost:16379", () ->
                redis.opsForValue().increment("easy-observability:demo:scheduled-metrics-heartbeats"));
    }
}
