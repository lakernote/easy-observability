package io.github.lakernote.meerkat.metrics.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Per-minute resource samples (JVM memory, GC, CPU, threads, connection and thread pools, ...). The last
 * {@value #MEMORY_SAMPLES} samples stay in memory; every sample is also appended to the {@link MetricsArchive} when
 * it is available. Queries average the samples falling into each bucket of the requested period.
 */
public final class ResourceHistory {
    static final int MEMORY_SAMPLES = 120;

    /**
     * @param series values per key aligned with {@code timestamps}; null where no sample exists
     */
    public record Series(OperationStats.Period period, long stepMillis, Instant since, boolean persistent,
                         List<Long> timestamps, Map<String, List<Double>> series) { }

    private record Sample(long timestamp, Map<String, Double> values) { }

    private final MetricsArchive archive;
    private final LongSupplier clockMillis;
    private final ZoneId zone;
    private final Deque<Sample> samples = new ArrayDeque<>();

    public ResourceHistory(MetricsArchive archive) {
        this(archive, System::currentTimeMillis, ZoneId.systemDefault());
    }

    ResourceHistory(MetricsArchive archive, LongSupplier clockMillis, ZoneId zone) {
        this.archive = archive;
        this.clockMillis = clockMillis;
        this.zone = zone;
    }

    /** Records one sample taken at {@code timestampMillis}. */
    public synchronized void record(long timestampMillis, Map<String, Double> values) {
        if (values.isEmpty()) return;
        Map<String, Double> copy = Map.copyOf(values);
        samples.addLast(new Sample(timestampMillis, copy));
        while (samples.size() > MEMORY_SAMPLES) samples.removeFirst();
        if (persistent()) archive.appendResources(timestampMillis, copy, date(clockMillis.getAsLong()));
    }

    public synchronized Series series(OperationStats.Period period) {
        long now = clockMillis.getAsLong();
        long step = period.stepMillis();
        int points = period.points();
        long firstBucket = Math.floorDiv(now, step) - points + 1;
        long fromMillis = firstBucket * step;
        Map<String, double[]> sums = new TreeMap<>();
        boolean persistent = persistent();
        long firstInMemory = samples.isEmpty() ? Long.MAX_VALUE : samples.peekFirst().timestamp();
        if (persistent && firstInMemory > fromMillis) {
            archive.scanResources(date(fromMillis), date(now), (timestamp, values) -> {
                if (timestamp >= fromMillis && timestamp < firstInMemory) {
                    add(sums, Math.floorDiv(timestamp, step) - firstBucket, points, values);
                }
            });
        }
        for (Sample sample : samples) {
            if (sample.timestamp() >= fromMillis) {
                add(sums, Math.floorDiv(sample.timestamp(), step) - firstBucket, points, sample.values());
            }
        }
        List<Long> timestamps = new ArrayList<>(points);
        for (int i = 0; i < points; i++) timestamps.add((firstBucket + i) * step);
        Map<String, List<Double>> series = new LinkedHashMap<>();
        sums.forEach((key, cells) -> {
            List<Double> values = new ArrayList<>(points);
            for (int i = 0; i < points; i++) {
                double count = cells[points + i];
                values.add(count == 0 ? null : round(cells[i] / count));
            }
            series.put(key, values);
        });
        long since = persistent ? fromMillis : Math.max(fromMillis, Math.min(firstInMemory, now));
        return new Series(period, step, Instant.ofEpochMilli(since), persistent, timestamps, series);
    }

    private static void add(Map<String, double[]> sums, long index, int points, Map<String, Double> values) {
        if (index < 0 || index >= points) return;
        values.forEach((key, value) -> {
            if (value == null || !Double.isFinite(value)) return;
            // First half holds sums, second half sample counts.
            double[] cells = sums.computeIfAbsent(key, ignored -> new double[points * 2]);
            cells[(int) index] += value;
            cells[points + (int) index]++;
        });
    }

    private static double round(double value) {
        return Math.abs(value) >= 1000 ? Math.rint(value) : Math.round(value * 1000) / 1000.0;
    }

    private boolean persistent() {
        return archive != null && archive.available();
    }

    private LocalDate date(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), zone);
    }
}
