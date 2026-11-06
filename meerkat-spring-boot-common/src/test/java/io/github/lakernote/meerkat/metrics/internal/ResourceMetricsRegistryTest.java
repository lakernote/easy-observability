package io.github.lakernote.meerkat.metrics.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.lakernote.meerkat.metrics.internal.ResourceMeters.Rollup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class ResourceMetricsRegistryTest {
    private final MockClock clock = new MockClock();
    private final ResourceHistory history = new ResourceHistory(null, clock::wallTime, java.time.ZoneOffset.UTC);
    private final ExecutorRejections rejections = new ExecutorRejections();
    private final ResourceMetricsRegistry registry = new ResourceMetricsRegistry(history, rejections, clock);

    @AfterEach
    void close() {
        registry.close();
    }

    @Test
    void acceptsOnlyTheBuiltInMeters() {
        Counter.builder("http.client.requests").register(registry).increment();
        Counter.builder("orders.created").register(registry).increment();
        Gauge.builder("jvm.memory.used", () -> 1).register(registry);

        assertThat(registry.getMeters()).extracting(meter -> meter.getId().getName())
                .containsExactly("jvm.memory.used");
    }

    @Test
    void splitsSeriesByTheMeaningfulTagAndSkipsUnboundedLimits() {
        Gauge.builder("jvm.memory.used", () -> 100).tags("area", "heap", "id", "Eden").register(registry);
        Gauge.builder("jvm.memory.used", () -> 50).tags("area", "heap", "id", "Old").register(registry);
        Gauge.builder("jvm.memory.used", () -> 30).tags("area", "nonheap", "id", "Metaspace").register(registry);
        Gauge.builder("jvm.memory.max", () -> -1).tags("area", "nonheap", "id", "Metaspace").register(registry);
        Gauge.builder("jvm.memory.max", () -> 4L << 30).tags("area", "heap", "id", "Old").register(registry);
        Gauge.builder("jvm.memory.max", () -> -1).tags("area", "heap", "id", "Eden").register(registry);
        Gauge.builder("process.files.max", () -> Long.MAX_VALUE).register(registry);
        Gauge.builder("executor.pool.max", () -> Integer.MAX_VALUE).tags("name", "applicationTaskExecutor")
                .register(registry);
        Gauge.builder("executor.pool.size", () -> 4).tags("name", "applicationTaskExecutor").register(registry);
        Gauge.builder("disk.free", () -> 7L << 30).tags("path", "/data/app").register(registry);
        Gauge.builder("jvm.threads.live", () -> 7).tags("x", "a").register(registry);
        Gauge.builder("jvm.threads.live", () -> 5).tags("x", "b").register(registry);
        Gauge.builder("jvm.gc.live.data.size", () -> Double.NaN).register(registry);
        TimeGauge.builder("process.uptime", () -> 90, TimeUnit.SECONDS).register(registry);

        assertThat(registry.sample()).containsOnly(
                entry("jvm.memory.used:heap", 150.0),
                entry("jvm.memory.used:nonheap", 30.0),
                entry("jvm.memory.max:heap", (double) (4L << 30)),
                entry("executor.pool.size:applicationTaskExecutor", 4.0),
                entry("disk.free:/data/app", (double) (7L << 30)),
                entry("jvm.threads.live", 12.0),
                entry("process.uptime", 90_000.0));
    }

    @Test
    void recordsAverageAndPeakOfSaturationGaugesReadEveryFewSeconds() {
        AtomicInteger pending = new AtomicInteger();
        Gauge.builder("hikaricp.connections.pending", pending, AtomicInteger::get).tags("pool", "main")
                .register(registry);
        AtomicInteger remaining = new AtomicInteger(Integer.MAX_VALUE);
        Gauge.builder("executor.queue.remaining", remaining, AtomicInteger::get).tags("name", "jobs")
                .register(registry);

        for (int value : new int[] {0, 9, 3, 0, 0}) {
            pending.set(value);
            registry.sampleLevels();
        }
        pending.set(0);

        assertThat(registry.sample()).containsOnly(
                entry("hikaricp.connections.pending:main", 2.0),
                entry("hikaricp.connections.pending:main@max", 9.0));
        // The minute's readings are consumed; the next minute starts from the reading taken at publish time.
        assertThat(registry.sample()).containsEntry("hikaricp.connections.pending:main@max", 0.0);
    }

    @Test
    void reportsCountersPerStepAndTimerMaximaOverTheLastMinute() {
        Timer pauses = Timer.builder("jvm.gc.pause").tags("gc", "G1 Young", "action", "end of minor GC")
                .register(registry);
        Timer full = Timer.builder("jvm.gc.pause").tags("gc", "G1 Old", "action", "end of major GC")
                .register(registry);
        Timer concurrent = Timer.builder("jvm.gc.pause").tags("gc", "G1 Remark", "action", "end of minor GC")
                .register(registry);
        DistributionSummary bytesIn = DistributionSummary.builder("jetty.connections.bytes.in").register(registry);
        Counter allocated = Counter.builder("jvm.gc.memory.allocated").register(registry);
        AtomicLong completed = new AtomicLong();
        FunctionCounter.builder("executor.completed", completed, AtomicLong::get).tags("name", "jobs")
                .register(registry);

        pauses.record(Duration.ofMillis(20));
        pauses.record(Duration.ofMillis(30));
        concurrent.record(Duration.ofMillis(45));
        full.record(Duration.ofMillis(400));
        bytesIn.record(200);
        bytesIn.record(300);
        allocated.increment(1024);
        completed.set(9);
        clock.add(ResourceMetricsRegistry.STEP);

        Map<String, Double> sample = registry.sample();
        assertThat(sample).contains(
                entry("jvm.gc.pause:end_of_minor_GC@count", 3.0),
                entry("jvm.gc.pause:end_of_minor_GC@total", 95.0),
                entry("jvm.gc.pause:end_of_minor_GC@max", 45.0),
                entry("jvm.gc.pause:end_of_major_GC@count", 1.0),
                entry("jvm.gc.pause:end_of_major_GC@max", 400.0),
                entry("jetty.connections.bytes.in", 500.0),
                entry("jvm.gc.memory.allocated", 1024.0),
                entry("executor.completed:jobs", 9.0));

        clock.add(ResourceMetricsRegistry.STEP);
        assertThat(registry.sample()).contains(entry("jvm.gc.pause:end_of_minor_GC@count", 0.0),
                entry("jvm.gc.pause:end_of_minor_GC@max", 0.0), entry("jvm.gc.memory.allocated", 0.0));
    }

    @Test
    void timerMaximaAlwaysCoverTheWholeMinute() {
        Timer acquire = Timer.builder("hikaricp.connections.acquire").tags("pool", "main").register(registry);
        clock.add(Duration.ofSeconds(1));
        acquire.record(Duration.ofMillis(800));
        clock.add(Duration.ofSeconds(59));

        assertThat(registry.sample()).containsEntry("hikaricp.connections.acquire:main@max", 800.0);
    }

    @Test
    void capsGroupsPerMeterSoTheNumberOfSeriesIsBounded() {
        for (int i = 0; i < ResourceMeters.MAX_GROUPS + 4; i++) {
            Gauge.builder("hikaricp.connections.idle", () -> 1).tags("pool", "pool-" + i).register(registry);
        }

        Map<String, Double> sample = registry.sample();
        assertThat(sample).hasSize(ResourceMeters.MAX_GROUPS + 1)
                .containsEntry("hikaricp.connections.idle:other", 4.0);
        assertThat(ResourceMetricsRegistry.sanitize("a b/c:d=e;f@g".repeat(10))).hasSize(64)
                .matches("[A-Za-z0-9._/-]+");
        assertThat(ResourceMeters.maxKeys()).isLessThan(400);

        clock.add(Duration.ofSeconds(90));
        registry.publish();
        ResourceHistory.Series series = history.series(OperationStats.Period.HOUR);
        assertThat(series.series().get("hikaricp.connections.idle:pool-0").get(59)).isEqualTo(1.0);
    }

    @Test
    void countsHttpResponsesPerStatusClassWithoutTheDashboardsOwnRequests() {
        record(200, "/orders/{id}", 3);
        record(201, "/orders", 1);
        record(404, "/orders/{id}", 2);
        record(429, "/orders", 4);
        record(503, "/orders", 1);
        record(200, "/api/meerkat/metrics/resources", 5);
        Timer.builder("http.server.requests").tags("status", "UNKNOWN", "uri", "UNKNOWN").register(registry)
                .record(Duration.ofMillis(1));
        clock.add(ResourceMetricsRegistry.STEP);

        assertThat(registry.sample()).containsOnly(
                entry("http.server.requests:2xx", 4.0), entry("http.server.requests:4xx", 2.0),
                entry("http.server.requests:429", 4.0), entry("http.server.requests:5xx", 1.0),
                entry("http.server.requests:unknown", 1.0));
        assertThat(HttpStatusClasses.statusClass("30x")).isEqualTo("unknown");
        assertThat(HttpStatusClasses.statusClass("302")).isEqualTo("3xx");
    }

    private void record(int status, String uri, int times) {
        Timer timer = Timer.builder("http.server.requests")
                .tags("status", String.valueOf(status), "uri", uri, "method", "GET", "outcome", "x").register(registry);
        for (int i = 0; i < times; i++) timer.record(Duration.ofMillis(5));
    }

    @Test
    void countsTasksRejectedByThreadPoolsAndKeepsTheirPolicy() throws Exception {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            assertThat(rejections.postProcessAfterInitialization(executor, "jobs")).isSameAs(executor);
            rejections.postProcessAfterInitialization(executor, "jobs");
            executor.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            for (int i = 0; i < 3; i++) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.execute(() -> { }))
                        .isInstanceOf(org.springframework.core.task.TaskRejectedException.class);
            }
        } finally {
            release.countDown();
            executor.shutdown();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(org.springframework.core.task.TaskRejectedException.class);
        clock.add(ResourceMetricsRegistry.STEP);

        assertThat(registry.sample()).containsEntry("meerkat.executor.rejected:jobs", 3.0);
    }

    @Test
    void combinesEachKeyByItsRollup() {
        assertThat(ResourceMeters.rollup("jvm.memory.used:heap")).isEqualTo(Rollup.AVG);
        assertThat(ResourceMeters.rollup("executor.queued:jobs@max")).isEqualTo(Rollup.MAX);
        assertThat(ResourceMeters.rollup("jvm.gc.pause:end_of_minor_GC@count")).isEqualTo(Rollup.SUM);
        assertThat(ResourceMeters.rollup("hikaricp.connections.timeout:main")).isEqualTo(Rollup.SUM);
        assertThat(ResourceMeters.rollup("jvm.memory.max:heap")).isEqualTo(Rollup.LAST);
        assertThat(ResourceMeters.rollup("unknown.meter")).isEqualTo(Rollup.AVG);
    }
}
