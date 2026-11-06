package io.github.lakernote.meerkat.metrics.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class ResourceMetricsRegistryTest {
    private final MockClock clock = new MockClock();
    private final ResourceHistory history = new ResourceHistory(null, clock::wallTime, java.time.ZoneOffset.UTC);
    private final ResourceMetricsRegistry registry =
            new ResourceMetricsRegistry(history, List.of("orders", " shop.cart. ", ""), clock);

    @AfterEach
    void close() {
        registry.close();
    }

    @Test
    void acceptsOnlyBuiltInMetersAndConfiguredPrefixes() {
        assertThat(registry.accepts("jvm.memory.used")).isTrue();
        assertThat(registry.accepts("hikaricp.connections.active")).isTrue();
        assertThat(registry.accepts("orders")).isTrue();
        assertThat(registry.accepts("orders.created")).isTrue();
        assertThat(registry.accepts("shop.cart.size")).isTrue();
        assertThat(registry.accepts("ordersx")).isFalse();
        assertThat(registry.accepts("http.server.requests")).isFalse();
        assertThat(registry.accepts("jvm.memory.usage.after.gc")).isFalse();
        assertThat(registry.accepts(null)).isFalse();

        Counter.builder("http.server.requests").register(registry).increment();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void splitsSeriesByTheMeaningfulTagAndSumsTheRest() {
        Gauge.builder("jvm.memory.used", () -> 100).tags("area", "heap", "id", "Eden").register(registry);
        Gauge.builder("jvm.memory.used", () -> 50).tags("area", "heap", "id", "Old").register(registry);
        Gauge.builder("jvm.memory.used", () -> 30).tags("area", "nonheap", "id", "Metaspace").register(registry);
        Gauge.builder("jvm.memory.max", () -> -1).tags("area", "nonheap", "id", "Metaspace").register(registry);
        Gauge.builder("jvm.memory.max", () -> 4L << 30).tags("area", "heap", "id", "Old").register(registry);
        Gauge.builder("jvm.memory.max", () -> -1).tags("area", "heap", "id", "Eden").register(registry);
        Gauge.builder("process.files.max", () -> Long.MAX_VALUE).register(registry);
        Gauge.builder("hikaricp.connections.active", () -> 3).tags("pool", "Main Pool/1").register(registry);
        Gauge.builder("executor.pool.max", () -> Integer.MAX_VALUE).tags("name", "applicationTaskExecutor")
                .register(registry);
        Gauge.builder("executor.pool.size", () -> 4).tags("name", "applicationTaskExecutor").register(registry);
        Gauge.builder("jvm.threads.live", () -> 7).tags("x", "a").register(registry);
        Gauge.builder("jvm.threads.live", () -> 5).tags("x", "b").register(registry);
        Gauge.builder("process.cpu.usage", () -> Double.NaN).register(registry);

        assertThat(registry.sample()).containsOnly(
                entry("jvm.memory.used:heap", 150.0),
                entry("jvm.memory.used:nonheap", 30.0),
                entry("jvm.memory.max:heap", (double) (4L << 30)),
                entry("hikaricp.connections.active:Main_Pool_1", 3.0),
                entry("executor.pool.size:applicationTaskExecutor", 4.0),
                entry("jvm.threads.live", 12.0));
    }

    @Test
    void reportsCountersAndTimersPerStep() {
        Counter orders = Counter.builder("orders.created").register(registry);
        Timer pauses = Timer.builder("jvm.gc.pause").tags("gc", "G1 Young").register(registry);
        DistributionSummary cart = DistributionSummary.builder("shop.cart.size").register(registry);
        LongTaskTimer imports = LongTaskTimer.builder("orders.import").register(registry);
        AtomicLong completed = new AtomicLong();
        FunctionCounter.builder("executor.completed", completed, AtomicLong::get).tags("name", "jobs")
                .register(registry);

        orders.increment(3);
        pauses.record(Duration.ofMillis(20));
        pauses.record(Duration.ofMillis(30));
        cart.record(2);
        cart.record(5);
        imports.start();
        completed.set(9);
        clock.add(ResourceMetricsRegistry.STEP);

        Map<String, Double> sample = registry.sample();
        assertThat(sample).contains(
                entry("orders.created", 3.0),
                entry("jvm.gc.pause.count", 2.0),
                entry("jvm.gc.pause.time", 50.0),
                entry("shop.cart.size.count", 2.0),
                entry("shop.cart.size.total", 7.0),
                entry("orders.import", 1.0),
                entry("executor.completed:jobs", 9.0));

        clock.add(ResourceMetricsRegistry.STEP);
        assertThat(registry.sample()).contains(entry("orders.created", 0.0), entry("jvm.gc.pause.count", 0.0));
    }

    @Test
    void capsGroupsPerMeterAndPublishesTheMinuteSample() {
        for (int i = 0; i < ResourceMetricsRegistry.MAX_GROUPS_PER_METER + 4; i++) {
            Gauge.builder("hikaricp.connections.idle", () -> 1).tags("pool", "pool-" + i).register(registry);
        }

        Map<String, Double> sample = registry.sample();
        assertThat(sample).hasSize(ResourceMetricsRegistry.MAX_GROUPS_PER_METER + 1)
                .containsEntry("hikaricp.connections.idle:other", 4.0);
        assertThat(ResourceMetricsRegistry.sanitize("a b/c:d=e;f".repeat(10))).hasSize(64)
                .matches("[A-Za-z0-9._-]+");

        clock.add(Duration.ofSeconds(90));
        registry.publish();
        ResourceHistory.Series series = history.series(OperationStats.Period.HOUR);
        assertThat(series.series().get("hikaricp.connections.idle:pool-0").get(59)).isEqualTo(1.0);
    }
}
