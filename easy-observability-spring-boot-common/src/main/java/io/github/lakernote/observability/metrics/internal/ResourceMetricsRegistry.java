package io.github.lakernote.observability.metrics.internal;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A Micrometer registry that Spring Boot binds like any other, so the JVM, process, Hikari, Tomcat and executor
 * meters it already configures are recorded without extra instrumentation. It accepts only a fixed list of meters
 * plus the configured {@code include} names, and once a minute hands their values to {@link ResourceHistory}.
 *
 * <p>Series are keyed by meter name, plus the pool, executor or memory-area tag where one matters; values of other
 * tagged series are summed. Counters and timers report the last minute, so {@code jvm.gc.pause.count} is GC pauses
 * per minute.</p>
 */
public final class ResourceMetricsRegistry extends StepMeterRegistry {
    static final Duration STEP = Duration.ofMinutes(1);
    static final int MAX_GROUPS_PER_METER = 16;
    static final int MAX_SERIES = 256;
    static final String OTHER_GROUP = "other";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResourceMetricsRegistry.class);

    /** Built-in meters and the tag that splits each into separate series, or {@code ""} to sum all. */
    static final Map<String, String> BUILT_IN = builtIn();

    private final ResourceHistory history;
    private final List<String> include;
    private final Map<String, Set<String>> groups = new HashMap<>();
    private volatile boolean publishFailureLogged;

    public ResourceMetricsRegistry(ResourceHistory history, List<String> include) {
        this(history, include, Clock.SYSTEM);
    }

    ResourceMetricsRegistry(ResourceHistory history, List<String> include, Clock clock) {
        super(new Config(), clock);
        this.history = history;
        this.include = include == null ? List.of() : include.stream()
                .filter(name -> name != null && !name.isBlank()).map(String::strip).toList();
        config().meterFilter(MeterFilter.denyUnless(id -> accepts(id.getName())));
        config().meterFilter(MeterFilter.maximumAllowableMetrics(1024));
    }

    /** Starts publishing once a minute on a daemon thread. */
    public void startPublishing() {
        start(new NamedThreadFactory("easy-observability-metrics-publish"));
    }

    boolean accepts(String name) {
        if (name == null) return false;
        if (BUILT_IN.containsKey(name)) return true;
        for (String prefix : include) {
            if (name.equals(prefix) || name.startsWith(prefix.endsWith(".") ? prefix : prefix + ".")) return true;
        }
        return false;
    }

    @Override
    protected void publish() {
        try {
            long minute = Math.floorDiv(clock.wallTime(), STEP.toMillis()) * STEP.toMillis();
            history.record(minute, sample());
        } catch (RuntimeException failure) {
            if (!publishFailureLogged) {
                publishFailureLogged = true;
                log.warn("Easy Observability could not record resource metrics: {}", failure.toString());
            }
        }
    }

    /** Reads every accepted meter once and sums values per series key. */
    synchronized Map<String, Double> sample() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Meter meter : getMeters()) {
            String name = meter.getId().getName();
            String key = key(meter.getId());
            if (key == null) continue;
            if (meter instanceof TimeGauge gauge) {
                put(values, key, name, gauge.value(TimeUnit.MILLISECONDS));
            } else if (meter instanceof Gauge gauge) {
                put(values, key, name, gauge.value());
            } else if (meter instanceof Counter counter) {
                put(values, key, name, counter.count());
            } else if (meter instanceof FunctionCounter counter) {
                put(values, key, name, counter.count());
            } else if (meter instanceof Timer timer) {
                put(values, key + ".count", name, timer.count());
                put(values, key + ".time", name, timer.totalTime(TimeUnit.MILLISECONDS));
            } else if (meter instanceof FunctionTimer timer) {
                put(values, key + ".count", name, timer.count());
                put(values, key + ".time", name, timer.totalTime(TimeUnit.MILLISECONDS));
            } else if (meter instanceof LongTaskTimer timer) {
                put(values, key, name, timer.activeTasks());
            } else if (meter instanceof DistributionSummary summary) {
                put(values, key + ".count", name, summary.count());
                put(values, key + ".total", name, summary.totalAmount());
            }
        }
        return values;
    }

    private static void put(Map<String, Double> values, String key, String name, double value) {
        if (!Double.isFinite(value)) return;
        // Unbounded limits are reported as -1, Integer.MAX_VALUE or Long.MAX_VALUE; none of them is plottable.
        if (name.endsWith(".max") && (value < 0 || value == Integer.MAX_VALUE || value >= Long.MAX_VALUE / 2)) return;
        if (!values.containsKey(key) && values.size() >= MAX_SERIES) return;
        values.merge(key, value, Double::sum);
    }

    private String key(Meter.Id id) {
        String name = sanitize(id.getName());
        String groupTag = BUILT_IN.getOrDefault(id.getName(), "");
        if (groupTag.isEmpty()) return name;
        String group = id.getTag(groupTag);
        if (group == null || group.isBlank()) return name;
        String clean = sanitize(group);
        Set<String> known = groups.computeIfAbsent(name, ignored -> new LinkedHashSet<>());
        if (!known.contains(clean)) {
            if (known.size() >= MAX_GROUPS_PER_METER) return name + ":" + OTHER_GROUP;
            known.add(clean);
        }
        return name + ":" + clean;
    }

    /** Keeps keys safe for the archive's {@code key=value;key=value} lines. */
    static String sanitize(String value) {
        StringBuilder clean = new StringBuilder(Math.min(value.length(), 64));
        for (int i = 0; i < value.length() && clean.length() < 64; i++) {
            char c = value.charAt(i);
            clean.append(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return clean.toString();
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.builder().percentilesHistogram(false).expiry(STEP).build()
                .merge(DistributionStatisticConfig.DEFAULT);
    }

    private static Map<String, String> builtIn() {
        Map<String, String> meters = new LinkedHashMap<>();
        for (String name : List.of("process.cpu.usage", "system.cpu.usage", "system.load.average.1m",
                "process.files.open", "process.files.max", "jvm.threads.live", "jvm.threads.daemon",
                "jvm.threads.peak", "jvm.classes.loaded", "jvm.gc.pause", "jvm.gc.memory.allocated",
                "tomcat.threads.busy", "tomcat.threads.current", "tomcat.threads.config.max",
                "tomcat.sessions.active.current", "jetty.threads.busy", "jetty.threads.current",
                "jetty.threads.config.max")) {
            meters.put(name, "");
        }
        for (String name : List.of("jvm.memory.used", "jvm.memory.committed", "jvm.memory.max")) {
            meters.put(name, "area");
        }
        for (String name : List.of("hikaricp.connections.active", "hikaricp.connections.idle",
                "hikaricp.connections.pending", "hikaricp.connections.max", "hikaricp.connections.timeout",
                "hikaricp.connections.acquire")) {
            meters.put(name, "pool");
        }
        for (String name : List.of("executor.active", "executor.queued", "executor.pool.size", "executor.pool.max",
                "executor.completed")) {
            meters.put(name, "name");
        }
        return Map.copyOf(meters);
    }

    private static final class Config implements StepRegistryConfig {
        @Override
        public String prefix() {
            return "easy-observability.resources";
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
