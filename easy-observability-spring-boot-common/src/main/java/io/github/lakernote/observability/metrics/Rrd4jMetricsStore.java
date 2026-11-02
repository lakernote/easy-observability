package io.github.lakernote.observability.metrics;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.FetchData;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Fixed-size local RRD history for the public runtime metric catalog. */
public final class Rrd4jMetricsStore {
    private static final String FILE_PREFIX = "common-metrics-v7-";
    private static final long HIGH_RESOLUTION_SECONDS = Duration.ofDays(1).toSeconds();
    private static final long MEDIUM_RESOLUTION_SECONDS = Duration.ofDays(7).toSeconds();

    private final Path directory;
    private final Path file;
    private final Path seriesMapFile;
    private final long stepSeconds;
    private final long maxStorageBytes;
    private final long retentionSeconds;
    private final Map<String, Map<String, Integer>> seriesSlots = new LinkedHashMap<>();
    private final Map<String, Boolean> overflowUsed = new LinkedHashMap<>();

    public Rrd4jMetricsStore(EasyObservabilityProperties.Metrics properties) {
        if (properties.getStorageDirectory() == null || properties.getStorageDirectory().isBlank()) {
            throw new IllegalArgumentException("easy.observability.metrics.storage-directory must not be blank");
        }
        this.directory = Path.of(properties.getStorageDirectory()).toAbsolutePath().normalize();
        this.seriesMapFile = directory.resolve("common-metrics-series.properties");
        this.stepSeconds = validateStep(properties.getStep());
        this.retentionSeconds = Duration.ofDays(validateRetention(properties.getRetentionDays())).toSeconds();
        if (properties.getMaxStorageBytes() == null) {
            throw new IllegalArgumentException("easy.observability.metrics.max-storage-bytes must not be null");
        }
        this.maxStorageBytes = properties.getMaxStorageBytes().toBytes();
        loadSeriesSlots();
        long expectedBytes = definition("metrics.rrd", 0, stepSeconds, retentionSeconds).getEstimatedSize();
        if (maxStorageBytes < expectedBytes) {
            throw new IllegalArgumentException("easy.observability.metrics.max-storage-bytes must be at least "
                    + expectedBytes + " bytes for the configured step, retention and fixed metric set");
        }
        this.file = directory.resolve(FILE_PREFIX + stepSeconds + "s-" + properties.getRetentionDays() + "d.rrd");
        initialize();
    }

    /** Returns a stable bounded series slot. Excess tag values share the family overflow line. */
    public synchronized int seriesSlotIndex(String family, String label) {
        MetricsCatalog.SeriesFamily definition = MetricsCatalog.family(family);
        String dimension = label == null || label.isBlank() ? "(未标注)" : label;
        Map<String, Integer> familySlots = seriesSlots.computeIfAbsent(family, ignored -> new LinkedHashMap<>());
        Integer slot = familySlots.get(dimension);
        if (slot == null) {
            if (familySlots.size() >= definition.limit()) {
                slot = definition.limit();
                if (!overflowUsed.getOrDefault(family, false)) {
                    overflowUsed.put(family, true);
                    persistSeriesSlots();
                }
            } else {
                slot = familySlots.size();
                familySlots.put(dimension, slot);
                persistSeriesSlots();
            }
        }
        return slot;
    }

    public synchronized void update(Map<String, Double> values) throws IOException {
        long timestamp = Instant.now().getEpochSecond();
        timestamp -= timestamp % stepSeconds;
        try (RrdDb database = open()) {
            Sample sample = database.createSample(timestamp);
            for (MetricsCatalog.DataSource source : dataSources()) {
                Double value = values.get(source.id());
                if (value != null && Double.isFinite(value)) sample.setValue(source.id(), value);
            }
            sample.update();
        }
        enforceStorageLimit();
    }

    public synchronized History fetch(String period) throws IOException {
        long end = Instant.now().getEpochSecond();
        long requested = periodSeconds(period);
        long from = Math.max(end - requested, end - retentionSeconds);
        long resolution = pointIntervalSeconds(period);
        try (RrdDb database = open()) {
            FetchData data = database.createFetchRequest(ConsolFun.AVERAGE, from, end, resolution).fetchData();
            long[] timestamps = data.getTimestamps();
            Map<String, double[]> series = new LinkedHashMap<>();
            for (MetricsCatalog.DataSource source : dataSources()) {
                series.put(source.id(), data.getValues(source.id()));
            }
            List<Point> points = new ArrayList<>(timestamps.length);
            for (int i = 0; i < timestamps.length; i++) {
                Map<String, Double> values = new LinkedHashMap<>();
                for (MetricsCatalog.DataSource source : dataSources()) {
                    double value = series.get(source.id())[i];
                    // MemoryUsage.max is -1 when a pool has no fixed limit. Some JVM/RRD
                    // combinations surface that as zero in archived samples; zero is not a
                    // meaningful memory ceiling, so keep the chart honest by omitting it.
                    if (source.id().startsWith("memory_") && source.id().endsWith("_max") && value <= 0) {
                        continue;
                    }
                    if (Double.isFinite(value)) values.put(source.id(), value);
                }
                points.add(new Point(timestamps[i], values));
            }
            return new History(period, stepSeconds, resolution, retentionSeconds, dataSources(), points);
        }
    }

    public StorageStatus status() throws IOException {
        long bytes = Files.exists(file) ? Files.size(file) : 0;
        return new StorageStatus(bytes, maxStorageBytes, stepSeconds, retentionSeconds, Files.exists(file));
    }

    private void initialize() {
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path candidate : files.filter(path -> path.getFileName().toString().startsWith("common-metrics-v"))
                        .filter(path -> !path.equals(file)).toList()) {
                    Files.deleteIfExists(candidate);
                }
            }
            if (Files.exists(file)) {
                long existingBytes = Files.size(file);
                if (existingBytes > maxStorageBytes) {
                    throw new IllegalArgumentException("Existing RRD history exceeds easy.observability.metrics.max-storage-bytes: "
                            + file);
                }
                try (RrdDb ignored = open()) { }
            } else {
                createDatabase();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not initialize RRD4J metrics history at " + file, failure);
        }
    }

    private void createDatabase() throws IOException {
        long now = Instant.now().getEpochSecond();
        long start = now - now % stepSeconds - stepSeconds;
        RrdDef definition = definition(file.toString(), start, stepSeconds, retentionSeconds);
        try (RrdDb ignored = RrdDb.getBuilder().setRrdDef(definition).build()) { }
        enforceStorageLimit();
    }

    private static RrdDef definition(String path, long start, long stepSeconds, long retentionSeconds) {
        RrdDef definition = new RrdDef(path, start, stepSeconds);
        long heartbeat = Math.max(stepSeconds * 2, 120);
        for (MetricsCatalog.DataSource source : MetricsCatalog.dataSources(Map.of(), true)) {
            definition.addDatasource(source.id(), DsType.GAUGE, heartbeat, Double.NaN, Double.NaN);
        }
        int mediumSteps = mediumArchiveSteps(stepSeconds);
        int coarseSteps = coarseArchiveSteps(stepSeconds);
        // Timers publish averages only in steps where an operation occurred. Allow each
        // archive to consolidate with one known step so sparse metrics survive long ranges.
        definition.addArchive(ConsolFun.AVERAGE, xffAllowOneKnownStep(1), 1,
                (int) Math.ceil((double) HIGH_RESOLUTION_SECONDS / stepSeconds));
        definition.addArchive(ConsolFun.AVERAGE, xffAllowOneKnownStep(mediumSteps), mediumSteps,
                (int) Math.ceil((double) MEDIUM_RESOLUTION_SECONDS / (stepSeconds * mediumSteps)));
        definition.addArchive(ConsolFun.AVERAGE, xffAllowOneKnownStep(coarseSteps), coarseSteps,
                (int) Math.ceil((double) retentionSeconds / (stepSeconds * coarseSteps)));
        return definition;
    }

    private static double xffAllowOneKnownStep(int archiveSteps) {
        return 1.0 - 1.0 / archiveSteps;
    }

    private long pointIntervalSeconds(String period) {
        return switch (period) {
            case "1h", "24h" -> stepSeconds;
            case "7d" -> stepSeconds * mediumArchiveSteps(stepSeconds);
            case "30d" -> stepSeconds * coarseArchiveSteps(stepSeconds);
            default -> throw new IllegalArgumentException("period must be 1h, 24h, 7d or 30d");
        };
    }

    private static int mediumArchiveSteps(long stepSeconds) {
        return Math.max(2, (int) Math.ceil(300.0 / stepSeconds));
    }

    private static int coarseArchiveSteps(long stepSeconds) {
        return Math.max(mediumArchiveSteps(stepSeconds) + 1, (int) Math.ceil(3_600.0 / stepSeconds));
    }

    private RrdDb open() throws IOException {
        return RrdDb.getBuilder().setPath(file.toString()).build();
    }

    private List<MetricsCatalog.DataSource> dataSources() {
        return MetricsCatalog.dataSources(seriesLabels(), false);
    }

    private synchronized Map<String, List<String>> seriesLabels() {
        Map<String, List<String>> labelsByFamily = new LinkedHashMap<>();
        for (MetricsCatalog.SeriesFamily family : MetricsCatalog.FAMILIES) {
            String[] labels = new String[family.limit() + 1];
            seriesSlots.getOrDefault(family.key(), Map.of()).forEach((label, slot) -> labels[slot] = label);
            if (overflowUsed.getOrDefault(family.key(), false)) labels[family.limit()] = "其他 " + family.legend();
            labelsByFamily.put(family.key(), Arrays.asList(labels));
        }
        return labelsByFamily;
    }

    private synchronized void loadSeriesSlots() {
        if (!Files.exists(seriesMapFile)) return;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(seriesMapFile)) {
            properties.load(reader);
            for (MetricsCatalog.SeriesFamily family : MetricsCatalog.FAMILIES) {
                overflowUsed.put(family.key(), Boolean.parseBoolean(
                        properties.getProperty("overflow." + family.key(), "false")));
            }
            properties.stringPropertyNames().stream().sorted().forEach(key -> {
                try {
                    if (!key.startsWith("series.")) return;
                    int separator = key.lastIndexOf('.');
                    String family = key.substring("series.".length(), separator);
                    int slot = Integer.parseInt(key.substring(separator + 1));
                    String label = properties.getProperty(key);
                    if (slot >= 0 && slot < MetricsCatalog.family(family).limit() && label != null) {
                        seriesSlots.computeIfAbsent(family, ignored -> new LinkedHashMap<>()).put(label, slot);
                    }
                } catch (IndexOutOfBoundsException | IllegalArgumentException ignored) {
                    // Ignore malformed or unknown metadata and keep valid family slots.
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read persisted metrics series metadata", failure);
        }
    }

    private synchronized void persistSeriesSlots() {
        Properties properties = new Properties();
        overflowUsed.forEach((family, overflow) -> properties.setProperty("overflow." + family, overflow.toString()));
        seriesSlots.forEach((family, labels) -> labels.forEach((label, slot) ->
                properties.setProperty("series." + family + "." + slot, label)));
        Path temp = seriesMapFile.resolveSibling(seriesMapFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            try (var writer = Files.newBufferedWriter(temp)) {
                properties.store(writer, "Bounded public metric series");
            }
            try {
                Files.move(temp, seriesMapFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, seriesMapFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not persist metric series metadata", failure);
        }
    }

    private void enforceStorageLimit() throws IOException {
        long bytes = Files.size(file);
        if (bytes > maxStorageBytes) {
            throw new IOException("RRD4J metrics file exceeded its configured size limit: " + bytes
                    + " > " + maxStorageBytes);
        }
    }

    private static long validateStep(Duration step) {
        if (step == null || step.isNegative() || step.isZero() || step.getNano() != 0
                || step.toSeconds() < 10 || step.toSeconds() > 3_600) {
            throw new IllegalArgumentException("easy.observability.metrics.step must be between 10s and 1h");
        }
        return step.toSeconds();
    }

    private static int validateRetention(int days) {
        if (days < 7 || days > 365) {
            throw new IllegalArgumentException("easy.observability.metrics.retention-days must be between 7 and 365");
        }
        return days;
    }

    private static long periodSeconds(String period) {
        return switch (period) {
            case "1h" -> Duration.ofHours(1).toSeconds();
            case "24h" -> Duration.ofDays(1).toSeconds();
            case "7d" -> Duration.ofDays(7).toSeconds();
            case "30d" -> Duration.ofDays(30).toSeconds();
            default -> throw new IllegalArgumentException("period must be 1h, 24h, 7d or 30d");
        };
    }

    public record Point(long timestamp, Map<String, Double> values) { }
    public record History(String period, long stepSeconds, long pointIntervalSeconds, long retentionSeconds,
                          List<MetricsCatalog.DataSource> series, List<Point> points) { }
    public record StorageStatus(long storageBytes, long maxStorageBytes, long stepSeconds,
                                long retentionSeconds, boolean initialized) { }
}
