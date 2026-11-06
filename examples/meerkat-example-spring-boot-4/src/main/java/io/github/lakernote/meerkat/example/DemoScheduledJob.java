package io.github.lakernote.meerkat.example;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background jobs: each run is a JOB root trace and a row in the 定时任务 ranking. */
@Component
public class DemoScheduledJob {
    private final DemoDatabaseService database;
    private final StringRedisTemplate redis;

    public DemoScheduledJob(DemoDatabaseService database, StringRedisTemplate redis) {
        this.database = database;
        this.redis = redis;
    }

    /** Counts orders in H2; slower than the 250 ms job threshold, so every run is recorded as a slow job. */
    @Scheduled(initialDelayString = "${meerkat.demo.schedule-initial-delay:5000}",
            fixedDelayString = "${meerkat.demo.schedule-delay:30000}")
    public void orderReport() {
        database.countOrders();
        database.slowQuery(300);
    }

    /** A light Redis heartbeat; fails every run while Redis is not started. */
    @Scheduled(initialDelayString = "${meerkat.demo.schedule-initial-delay:5000}",
            fixedDelayString = "${meerkat.demo.schedule-delay:30000}")
    public void redisHeartbeat() {
        redis.opsForValue().increment("meerkat:demo:scheduled-heartbeats");
    }
}
