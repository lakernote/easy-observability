package io.github.lakernote.observability.metrics.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rrd4j.ConsolFun;
import org.rrd4j.core.RrdDb;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class Rrd4jStepMeterRegistryTest {
    @TempDir
    Path tempDirectory;

    @Test
    void persistsActuatorMetricsAndSkipsStarterOwnedMeters() {
        TestClock clock = new TestClock(Instant.parse("2026-10-14T02:00:00Z"));
        MetricHistoryStore store = new MetricHistoryStore(new ObjectMapper(), tempDirectory.toString(), 15,
                64L * 1024 * 1024, 8L * 1024 * 1024, new StoreClock(clock), true);
        store.restore();
        Rrd4jStepMeterRegistry registry = new Rrd4jStepMeterRegistry(store, clock, false);

        Counter.builder("http.server.requests").tag("uri", "/orders").register(registry).increment(3);
        Counter.builder("application.custom.metric").tag("operation", "GET /orders").register(registry).increment();
        clock.advance(Duration.ofMinutes(1));
        registry.publish();

        assertThat(store.listSeries()).singleElement().satisfies(series -> {
            assertThat(series.name()).isEqualTo("http.server.requests");
            assertThat(series.stepNormalized()).isTrue();
        });
        registry.close();
    }

    @Test
    void persistsCompletedTimerStepsAsPerWindowCountsAndDurations() throws Exception {
        TestClock clock = new TestClock(Instant.parse("2026-10-14T02:00:00Z"));
        MetricHistoryStore store = new MetricHistoryStore(new ObjectMapper(), tempDirectory.toString(), 15,
                64L * 1024 * 1024, 8L * 1024 * 1024, new StoreClock(clock), true);
        store.restore();
        Rrd4jStepMeterRegistry registry = new Rrd4jStepMeterRegistry(store, clock, false);

        Timer timer = Timer.builder("http.server.requests").tag("uri", "/orders").tag("status", "200")
                .register(registry);
        timer.record(250, TimeUnit.MILLISECONDS);
        clock.advance(Duration.ofMinutes(1));
        registry.publish();

        timer.record(400, TimeUnit.MILLISECONDS);
        timer.record(200, TimeUnit.MILLISECONDS);
        clock.advance(Duration.ofMinutes(1));
        registry.publish();

        var series = store.listSeries();
        assertThat(series).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.stepNormalized()).isTrue();
            assertThat(descriptor.measurements()).contains("COUNT", "TOTAL_TIME");
        });
        List<MetricHistoryPoint> points = store.queryHistory("http.server.requests",
                Map.of("uri", "/orders", "status", "200"), 10);
        assertThat(points).hasSize(2);
        assertThat(points.get(0).measurements()).containsEntry("COUNT", 2D)
                .containsEntry("TOTAL_TIME", 0.6D);
        assertThat(points.get(1).measurements()).containsEntry("COUNT", 1D)
                .containsEntry("TOTAL_TIME", 0.25D);
        assertThat(points).allSatisfy(point -> assertThat(point.intervalSeconds()).isEqualTo(60));

        try (RrdDb database = new RrdDb(tempDirectory.resolve(seriesFileName()).toString())) {
            assertThat(database.getRrdDef().getArcDefs()).extracting(archive -> archive.getConsolFun())
                    .containsOnly(ConsolFun.AVERAGE);
        }
        registry.close();
    }

    @Test
    void replacesLegacyCumulativeSeriesWhenStepSemanticsBegin() {
        TestClock clock = new TestClock(Instant.parse("2026-10-14T02:00:00Z"));
        MetricHistoryStore store = new MetricHistoryStore(new ObjectMapper(), tempDirectory.toString(), 15,
                64L * 1024 * 1024, 8L * 1024 * 1024, new StoreClock(clock), true);
        store.restore();
        store.recordMetricsSnapshot(List.of(new MetricSample("tasks.scheduled.execution", Map.of(),
                Map.of("COUNT", 20D, "TOTAL_TIME", 12D))));
        clock.advance(Duration.ofMinutes(1));

        store.recordMetricsSnapshot(List.of(new MetricSample("tasks.scheduled.execution", Map.of(),
                Map.of("COUNT", 2D, "TOTAL_TIME", 0.4D), true)));

        assertThat(store.listSeries()).singleElement().satisfies(series -> assertThat(series.stepNormalized()).isTrue());
        assertThat(store.queryHistory("tasks.scheduled.execution", Map.of(), 10)).singleElement()
                .satisfies(point -> assertThat(point.measurements()).containsEntry("COUNT", 2D)
                        .containsEntry("TOTAL_TIME", 0.4D));
    }

    private String seriesFileName() {
        return tempDirectory.toFile().listFiles((directory, name) -> name.endsWith(".rrd"))[0].getName();
    }

    private static final class TestClock implements Clock {
        private Instant instant;

        private TestClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public long wallTime() {
            return instant.toEpochMilli();
        }

        @Override
        public long monotonicTime() {
            return instant.toEpochMilli() * 1_000_000;
        }

    }

    private static final class StoreClock extends java.time.Clock {
        private final TestClock clock;

        private StoreClock(TestClock clock) {
            this.clock = clock;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public java.time.Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return clock.instant;
        }
    }
}
