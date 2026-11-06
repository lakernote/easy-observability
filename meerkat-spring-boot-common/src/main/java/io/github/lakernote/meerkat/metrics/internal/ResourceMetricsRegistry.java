package io.github.lakernote.meerkat.metrics.internal;

import io.github.lakernote.meerkat.metrics.internal.ResourceMeters.Kind;
import io.github.lakernote.meerkat.metrics.internal.ResourceMeters.Spec;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.step.StepRegistryConfig;
import io.micrometer.core.instrument.util.NamedThreadFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A Micrometer registry that Spring Boot binds like any other, so the JVM, process, Hikari, Tomcat, executor and
 * disk meters it already configures are recorded without extra instrumentation. It accepts only the meters listed in
 * {@link ResourceMeters} and once a minute hands their values to {@link ResourceHistory}. Spring Boot's
 * {@code http.server.requests} timers are collapsed into response status classes by {@link HttpStatusClasses}, and
 * {@link ExecutorRejections} adds the tasks each thread pool rejected.
 *
 * <p>Saturation gauges (CPU, busy threads, active and pending connections, queued tasks) are also read every
 * {@value #LEVEL_SECONDS} seconds, so a burst that clears within the minute still shows as its peak. Counters and
 * timers report the last minute. Timer maxima cover the last 60 to 80 seconds: Micrometer keeps them in a ring of
 * {@value #MAX_BUFFERS} windows rotated every {@value #MAX_ROTATE_SECONDS} seconds.</p>
 */
public final class ResourceMetricsRegistry extends StepMeterRegistry {
    static final Duration STEP = Duration.ofMinutes(1);
    static final int LEVEL_SECONDS = 10;
    static final int MAX_ROTATE_SECONDS = 20;
    static final int MAX_BUFFERS = 4;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResourceMetricsRegistry.class);

    private final ResourceHistory history;
    private final Map<String, Set<String>> groups = new HashMap<>();
    /** Per level key: sum, sample count and max of the readings taken this minute. */
    private final Map<String, double[]> levels = new LinkedHashMap<>();
    private ScheduledExecutorService levelSampler;
    private volatile boolean failureLogged;

    /** {@code rejections} may be null, for example outside a Spring application context. */
    public ResourceMetricsRegistry(ResourceHistory history, ExecutorRejections rejections) {
        this(history, rejections, Clock.SYSTEM);
    }

    ResourceMetricsRegistry(ResourceHistory history, ExecutorRejections rejections, Clock clock) {
        super(new Config(), clock);
        this.history = history;
        config().meterFilter(new HttpStatusClasses());
        config().meterFilter(MeterFilter.denyUnless(id -> ResourceMeters.spec(id.getName()) != null));
        config().meterFilter(MeterFilter.maximumAllowableMetrics(1024));
        if (rejections != null) rejections.bindTo(this);
    }

    /** Starts publishing once a minute and reading saturation gauges every few seconds, on daemon threads. */
    public synchronized void startPublishing() {
        start(new NamedThreadFactory("meerkat-metrics-publish"));
        if (levelSampler != null) return;
        levelSampler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("meerkat-metrics-sample"));
        levelSampler.scheduleAtFixedRate(() -> {
            try {
                sampleLevels();
            } catch (RuntimeException failure) {
                logOnce(failure);
            }
        }, LEVEL_SECONDS, LEVEL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        synchronized (this) {
            if (levelSampler != null) levelSampler.shutdownNow();
            levelSampler = null;
        }
        super.stop();
    }

    @Override
    protected void publish() {
        try {
            long minute = Math.floorDiv(clock.wallTime(), STEP.toMillis()) * STEP.toMillis();
            history.record(minute, sample());
        } catch (RuntimeException failure) {
            logOnce(failure);
        }
    }

    /** Reads the saturation gauges once and adds the readings to this minute's average and peak. */
    synchronized void sampleLevels() {
        Map<String, Double> reading = new HashMap<>();
        for (Meter meter : getMeters()) {
            Spec spec = ResourceMeters.spec(meter.getId().getName());
            if (spec == null || spec.kind() != Kind.LEVEL || !(meter instanceof Gauge gauge)) continue;
            double value = gauge.value();
            if (spec.skips(value)) continue;
            String key = key(spec, meter.getId());
            reading.merge(key, value, Double::sum);
        }
        reading.forEach((key, value) -> {
            double[] level = levels.computeIfAbsent(key, ignored -> new double[] {0, 0, Double.NEGATIVE_INFINITY});
            level[0] += value;
            level[1]++;
            level[2] = Math.max(level[2], value);
        });
    }

    /** Reads every accepted meter and returns this minute's values per series key. */
    synchronized Map<String, Double> sample() {
        sampleLevels();
        Map<String, Double> values = new LinkedHashMap<>();
        levels.forEach((key, level) -> {
            values.put(key, level[0] / level[1]);
            values.put(key + "@" + ResourceMeters.MAX, level[2]);
        });
        levels.clear();
        for (Meter meter : getMeters()) {
            Spec spec = ResourceMeters.spec(meter.getId().getName());
            if (spec == null || spec.kind() == Kind.LEVEL) continue;
            String key = key(spec, meter.getId());
            switch (spec.kind()) {
                case GAUGE, LATEST -> {
                    if (meter instanceof TimeGauge gauge) sum(values, spec, key, gauge.value(TimeUnit.MILLISECONDS));
                    else if (meter instanceof Gauge gauge) sum(values, spec, key, gauge.value());
                }
                case COUNTER -> {
                    if (meter instanceof Counter counter) sum(values, spec, key, counter.count());
                    else if (meter instanceof Timer timer) sum(values, spec, key, timer.count());
                    else if (meter instanceof FunctionCounter counter) sum(values, spec, key, counter.count());
                    else if (meter instanceof DistributionSummary summary) {
                        sum(values, spec, key, summary.totalAmount());
                    }
                }
                case TIMER -> {
                    if (meter instanceof Timer timer) {
                        timer(values, spec, key, timer.count(), timer.totalTime(TimeUnit.MILLISECONDS));
                        double max = timer.max(TimeUnit.MILLISECONDS);
                        if (Double.isFinite(max)) values.merge(key + "@" + ResourceMeters.MAX, max, Math::max);
                    } else if (meter instanceof FunctionTimer timer) {
                        timer(values, spec, key, timer.count(), timer.totalTime(TimeUnit.MILLISECONDS));
                    }
                }
                default -> { }
            }
        }
        return values;
    }

    private static void timer(Map<String, Double> values, Spec spec, String key, double count, double totalMillis) {
        sum(values, spec, key + "@" + ResourceMeters.COUNT, count);
        sum(values, spec, key + "@" + ResourceMeters.TOTAL, totalMillis);
    }

    private static void sum(Map<String, Double> values, Spec spec, String key, double value) {
        if (!spec.skips(value)) values.merge(key, value, Double::sum);
    }

    private String key(Spec spec, Meter.Id id) {
        if (spec.tag().isEmpty()) return spec.name();
        String group = id.getTag(spec.tag());
        if (group == null || group.isBlank()) return spec.name();
        String clean = sanitize(group);
        Set<String> known = groups.computeIfAbsent(spec.name(), ignored -> new LinkedHashSet<>());
        if (!known.contains(clean)) {
            if (known.size() >= ResourceMeters.MAX_GROUPS) return spec.name() + ":" + ResourceMeters.OTHER_GROUP;
            known.add(clean);
        }
        return spec.name() + ":" + clean;
    }

    /** Keeps groups safe for series keys and the archive's {@code key=value;key=value} lines. */
    static String sanitize(String value) {
        StringBuilder clean = new StringBuilder(Math.min(value.length(), 64));
        for (int i = 0; i < value.length() && clean.length() < 64; i++) {
            char c = value.charAt(i);
            clean.append(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' || c == '_' || c == '/'
                    ? c : '_');
        }
        return clean.toString();
    }

    private void logOnce(RuntimeException failure) {
        if (!failureLogged) {
            failureLogged = true;
            log.warn("Meerkat could not record resource metrics: {}", failure.toString());
        }
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.builder().percentilesHistogram(false)
                .expiry(Duration.ofSeconds(MAX_ROTATE_SECONDS)).bufferLength(MAX_BUFFERS).build()
                .merge(DistributionStatisticConfig.DEFAULT);
    }

    private static final class Config implements StepRegistryConfig {
        @Override
        public String prefix() {
            return "meerkat.resources";
        }

        @Override
        public String get(String key) {
            return null;
        }

        @Override
        public Duration step() {
            return STEP;
        }
    }
}
