package io.github.lakernote.meerkat.metrics.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed set of Micrometer meters Meerkat records, and for each one how it is read, split and combined.
 *
 * <p>Series keys are {@code meter[:group][@stat]}: the group is the value of the meter's split tag, the statistic
 * is {@code max} for peaks and {@code count}/{@code total} for timers. Meter names never contain {@code :} or
 * {@code @}, and groups are sanitized, so a key always parses back to its meter and statistic, and therefore to the
 * rule that combines its values over longer time buckets.</p>
 */
final class ResourceMeters {
    /** Groups per meter beyond which further pools, executors or paths are summed into {@link #OTHER_GROUP}. */
    static final int MAX_GROUPS = 8;
    static final String OTHER_GROUP = "other";
    static final String MAX = "max";
    static final String COUNT = "count";
    static final String TOTAL = "total";
    /** Count limits at or above this, such as {@code Integer.MAX_VALUE}, mean "unbounded". */
    private static final double UNBOUNDED_COUNT = 1 << 30;
    /** Byte limits at or above this, such as {@code Long.MAX_VALUE}, mean "unbounded". */
    private static final double UNBOUNDED_BYTES = Long.MAX_VALUE / 2.0;
    private static final double NO_LIMIT = Double.POSITIVE_INFINITY;

    enum Kind {
        /** Read once a minute; averaged over longer buckets. */
        GAUGE,
        /** Saturation gauge read every 10 seconds; records the average and, as {@code @max}, the peak. */
        LEVEL,
        /** Capacity or position read once a minute; longer buckets keep the latest value. */
        LATEST,
        /**
         * Per-minute increase of a counter, number of events of a timer, or total amount of a distribution summary;
         * summed over buckets.
         */
        COUNTER,
        /** Per-minute {@code @count} and {@code @total} milliseconds, summed, and {@code @max} milliseconds. */
        TIMER
    }

    /** How values of one series key combine within a longer time bucket. */
    enum Rollup { AVG, SUM, MAX, LAST }

    /**
     * @param tag         tag whose value splits the meter into series, or {@code ""} to sum all tagged series
     * @param unboundedAt values at or above this, or negative, mean "no limit" and are skipped
     */
    record Spec(String name, String tag, Kind kind, double unboundedAt) {
        boolean skips(double value) {
            return !Double.isFinite(value) || unboundedAt != NO_LIMIT && (value < 0 || value >= unboundedAt);
        }
    }

    static final Map<String, Spec> SPECS = specs(
            // CPU and system
            level("process.cpu.usage", ""), level("system.cpu.usage", ""), gauge("system.load.average.1m", ""),
            latest("system.cpu.count", "", NO_LIMIT), latest("process.uptime", "", NO_LIMIT),
            gauge("process.files.open", ""), latest("process.files.max", "", UNBOUNDED_COUNT),
            gauge("disk.free", "path"), latest("disk.total", "path", NO_LIMIT),
            // Memory and GC
            gauge("jvm.memory.used", "area"), gauge("jvm.memory.committed", "area"),
            latest("jvm.memory.max", "area", UNBOUNDED_BYTES), gauge("jvm.buffer.memory.used", "id"),
            gauge("jvm.gc.live.data.size", ""), latest("jvm.gc.max.data.size", "", UNBOUNDED_BYTES),
            gauge("jvm.memory.usage.after.gc", ""), gauge("jvm.gc.overhead", ""),
            timer("jvm.gc.pause", "action"), counter("jvm.gc.memory.allocated", ""),
            counter("jvm.gc.memory.promoted", ""),
            // Threads
            gauge("jvm.threads.live", ""), gauge("jvm.threads.daemon", ""), gauge("jvm.threads.peak", ""),
            gauge("jvm.classes.loaded", ""),
            // Web server
            level("tomcat.threads.busy", ""), gauge("tomcat.threads.current", ""),
            latest("tomcat.threads.config.max", "", UNBOUNDED_COUNT),
            level("tomcat.connections.current", ""), gauge("tomcat.connections.keepalive.current", ""),
            latest("tomcat.connections.config.max", "", UNBOUNDED_COUNT),
            counter("tomcat.global.received", ""), counter("tomcat.global.sent", ""),
            gauge("tomcat.sessions.active.current", ""),
            level("jetty.threads.busy", ""), gauge("jetty.threads.current", ""),
            latest("jetty.threads.config.max", "", UNBOUNDED_COUNT), level("jetty.connections.current", ""),
            counter("jetty.connections.bytes.in", ""), counter("jetty.connections.bytes.out", ""),
            // Connection pools
            level("hikaricp.connections.active", "pool"), gauge("hikaricp.connections.idle", "pool"),
            level("hikaricp.connections.pending", "pool"), latest("hikaricp.connections.max", "pool", NO_LIMIT),
            counter("hikaricp.connections.timeout", "pool"), timer("hikaricp.connections.acquire", "pool"),
            timer("hikaricp.connections.usage", "pool"),
            // Executors; queued + queue.remaining is the queue capacity, so both are sampled together.
            level("executor.active", "name"), level("executor.queued", "name"),
            new Spec("executor.queue.remaining", "name", Kind.LEVEL, UNBOUNDED_COUNT),
            gauge("executor.pool.size", "name"), latest("executor.pool.core", "name", UNBOUNDED_COUNT),
            latest("executor.pool.max", "name", UNBOUNDED_COUNT), counter("executor.completed", "name"),
            counter(ExecutorRejections.METER, "name"),
            // HTTP responses per status class; see HttpStatusClasses for how the timer's tags are collapsed.
            counter(HttpStatusClasses.METER, HttpStatusClasses.TAG));

    private ResourceMeters() {
    }

    static Spec spec(String meterName) {
        return meterName == null ? null : SPECS.get(meterName);
    }

    /** The rule combining values of {@code key} within a longer bucket; unknown keys are averaged. */
    static Rollup rollup(String key) {
        int at = key.lastIndexOf('@');
        String stat = at < 0 ? "" : key.substring(at + 1);
        if (stat.equals(MAX)) return Rollup.MAX;
        if (stat.equals(COUNT) || stat.equals(TOTAL)) return Rollup.SUM;
        String base = at < 0 ? key : key.substring(0, at);
        int colon = base.indexOf(':');
        Spec spec = SPECS.get(colon < 0 ? base : base.substring(0, colon));
        if (spec == null) return Rollup.AVG;
        return switch (spec.kind()) {
            case LATEST -> Rollup.LAST;
            case COUNTER, TIMER -> Rollup.SUM;
            case GAUGE, LEVEL -> Rollup.AVG;
        };
    }

    /**
     * Folds {@code value} into a bucket held in {@code cells[index]}, with its sample count in
     * {@code cells[index + width]}. Values must arrive in time order for {@link Rollup#LAST}.
     */
    static void fold(Rollup rollup, double[] cells, int index, int width, double value) {
        double samples = cells[index + width];
        cells[index] = samples == 0 ? value : switch (rollup) {
            case AVG, SUM -> cells[index] + value;
            case MAX -> Math.max(cells[index], value);
            case LAST -> value;
        };
        cells[index + width] = samples + 1;
    }

    /** The folded value of a bucket, or {@code null} when it has no sample. */
    static Double result(Rollup rollup, double[] cells, int index, int width) {
        double samples = cells[index + width];
        if (samples == 0) return null;
        return rollup == Rollup.AVG ? cells[index] / samples : cells[index];
    }

    /** Upper bound of series keys one sample can hold; fixed because meters, statistics and groups are capped. */
    static int maxKeys() {
        int keys = 0;
        for (Spec spec : SPECS.values()) {
            int perGroup = switch (spec.kind()) {
                case LEVEL -> 2;
                case TIMER -> 3;
                default -> 1;
            };
            keys += perGroup * (spec.tag().isEmpty() ? 1 : MAX_GROUPS + 1);
        }
        return keys;
    }

    private static Spec gauge(String name, String tag) {
        return new Spec(name, tag, Kind.GAUGE, NO_LIMIT);
    }

    private static Spec level(String name, String tag) {
        return new Spec(name, tag, Kind.LEVEL, NO_LIMIT);
    }

    private static Spec latest(String name, String tag, double unboundedAt) {
        return new Spec(name, tag, Kind.LATEST, unboundedAt);
    }

    private static Spec counter(String name, String tag) {
        return new Spec(name, tag, Kind.COUNTER, NO_LIMIT);
    }

    private static Spec timer(String name, String tag) {
        return new Spec(name, tag, Kind.TIMER, NO_LIMIT);
    }

    private static Map<String, Spec> specs(Spec... specs) {
        Map<String, Spec> byName = new LinkedHashMap<>();
        for (Spec spec : List.of(specs)) {
            if (byName.put(spec.name(), spec) != null) throw new IllegalStateException(spec.name());
        }
        return Map.copyOf(byName);
    }
}
