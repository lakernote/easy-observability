package io.github.lakernote.observability.metrics.history;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.step.StepRegistryConfig;
import io.micrometer.core.instrument.util.NamedThreadFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Micrometer step backend that persists Actuator meters in the bounded local RRD4J store. */
public final class Rrd4jStepMeterRegistry extends StepMeterRegistry {
    private static final StepRegistryConfig CONFIG = new StepRegistryConfig() {
        @Override
        public String prefix() {
            return "easy.observability.metrics.history.rrd4j";
        }

        @Override
        public String get(String key) {
            return null;
        }

        @Override
        public Duration step() {
            return Duration.ofSeconds(MetricHistoryStore.stepSeconds());
        }
    };

    private final MetricHistoryStore store;

    public Rrd4jStepMeterRegistry(MetricHistoryStore store) {
        this(store, Clock.SYSTEM, true);
    }

    Rrd4jStepMeterRegistry(MetricHistoryStore store, Clock clock, boolean start) {
        super(CONFIG, clock);
        this.store = store;
        if (start) start(new NamedThreadFactory("easy-observability-rrd4j-step"));
    }

    @Override
    protected void publish() {
        List<MetricSample> samples = getMeters().stream()
                .filter(meter -> useful(meter.getId().getName()))
                .map(Rrd4jStepMeterRegistry::snapshot)
                .filter(sample -> !sample.measurements().isEmpty())
                .toList();
        store.recordMetricsSnapshot(samples);
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.SECONDS;
    }

    private static MetricSample snapshot(Meter meter) {
        Map<String, String> tags = new LinkedHashMap<>();
        meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
        Map<String, Double> values = new LinkedHashMap<>();
        for (Measurement measurement : meter.measure()) {
            double value = measurement.getValue();
            if (Double.isFinite(value)) values.put(measurement.getStatistic().name(), value);
        }
        return new MetricSample(meter.getId().getName(), tags, values, stepNormalized(meter));
    }

    private static boolean stepNormalized(Meter meter) {
        return switch (meter.getId().getType()) {
            case COUNTER, TIMER, DISTRIBUTION_SUMMARY -> true;
            default -> false;
        };
    }

    private static boolean useful(String name) {
        return name.startsWith("jvm.") || name.startsWith("process.")
                || name.startsWith("system.") || name.startsWith("http.server.requests")
                || name.startsWith("http.client.")
                || name.equals("tasks.scheduled.execution") || name.equals("spring.kafka.listener")
                || name.startsWith("spring.kafka.container.") || name.startsWith("kafka.consumer.")
                || name.startsWith("kafka.producer.")
                || name.startsWith("spring.data.redis") || name.startsWith("redis.") || name.startsWith("lettuce.")
                || name.startsWith("cache.") || name.startsWith("elasticsearch.")
                || name.startsWith("hikaricp.") || name.startsWith("jdbc.connections.")
                || name.startsWith("tomcat.") || name.startsWith("logback.") || name.startsWith("executor.");
    }
}
