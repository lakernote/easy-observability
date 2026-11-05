package io.github.lakernote.observability.metrics.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongSupplier;

/**
 * Lock-free call statistics per operation (endpoint, SQL template, remote call, Redis command, ...).
 *
 * <p>Each operation keeps two rings in memory: one-minute counters for the last hour's curve, and two hours of
 * 10-minute slices holding a log-scale latency histogram from which P95 is estimated (within one √2-wide bucket). Completed
 * slices are appended to the {@link MetricsArchive} once a minute and the current one on shutdown, so rankings and
 * trends over days survive restarts. Histograms merge exactly, so averages and P95 stay correct over any range.
 * The number of distinct operations is capped; later ones are merged into one overflow row per category.</p>
 */
public final class OperationStats implements AutoCloseable {
    /** Name of the per-category row that absorbs operations beyond {@code max-operations}. */
    public static final String OVERFLOW_NAME = "（其他：超出统计数量上限）";
    static final int MAX_NAME_LENGTH = 512;
    static final long MINUTE_MILLIS = 60 * 1000L;
    static final long SLICE_MILLIS = 10 * MINUTE_MILLIS;
    static final long HOUR_MILLIS = 60 * MINUTE_MILLIS;
    /** Two hours of slices, so completed slices stay in memory until the next flush even after a slow one. */
    static final int SLICES = 12;
    private static final int HOUR_SLICES = (int) (HOUR_MILLIS / SLICE_MILLIS);
    /** A little more than one hour of minutes, so the oldest displayed minute is never being overwritten. */
    static final int MINUTES = 64;
    private static final long FLUSH_INTERVAL_SECONDS = 60;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OperationStats.class);

    static final int COUNT = 0;
    static final int ERRORS = 1;
    static final int TOTAL = 2;
    static final int MAX = 3;
    /** Entry-thread CPU nanoseconds and allocated bytes, summed over the {@link #MEASURED} calls that reported them. */
    static final int CPU = 4;
    static final int ALLOCATED = 5;
    static final int MEASURED = 6;
    static final int FIRST_BUCKET = 7;
    /** Bucket {@code i} holds durations below {@code BUCKET_UPPER_NANOS[i]}; one extra bucket is unbounded. */
    private static final long[] BUCKET_UPPER_NANOS = bucketBounds();
    static final int CELLS = FIRST_BUCKET + BUCKET_UPPER_NANOS.length + 1;

    public enum Category { HTTP, SQL, REMOTE, REDIS, JOB, KAFKA_CONSUMER, KAFKA_PRODUCER }

    /** Ranking windows. */
    public enum Window {
        HOUR(0), TODAY(1), WEEK(7), MONTH(30);

        private final int days;

        Window(int days) {
            this.days = days;
        }

        public static Window parse(String value) {
            if (value == null || value.isBlank()) return HOUR;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "1h", "hour" -> HOUR;
                case "today" -> TODAY;
                case "7d" -> WEEK;
                case "30d" -> MONTH;
                default -> throw new IllegalArgumentException("Unknown window: " + value);
            };
        }
    }

    /** Trend ranges and their resolution. */
    public enum Period {
        HOUR(MINUTE_MILLIS, 60), DAY(SLICE_MILLIS, 144), WEEK(HOUR_MILLIS, 7 * 24), MONTH(HOUR_MILLIS, 30 * 24);

        final long stepMillis;
        final int points;

        Period(long stepMillis, int points) {
            this.stepMillis = stepMillis;
            this.points = points;
        }

        public long stepMillis() {
            return stepMillis;
        }

        public int points() {
            return points;
        }

        public static Period parse(String value) {
            if (value == null || value.isBlank()) return HOUR;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "1h", "hour" -> HOUR;
                case "24h", "day" -> DAY;
                case "7d" -> WEEK;
                case "30d" -> MONTH;
                default -> throw new IllegalArgumentException("Unknown period: " + value);
            };
        }
    }

    /**
     * @param meanCpuMs entry-thread CPU time per call, or null when no call reported it (dependencies, async entries)
     * @param meanAllocatedBytes entry-thread heap allocation per call, or null like {@code meanCpuMs}
     */
    public record Row(String name, long count, long errors, double meanMs, double p95Ms, double maxMs,
                      double totalMs, Double meanCpuMs, Long meanAllocatedBytes) { }

    public record CategorySnapshot(Category category, long count, long errors, double totalMs, List<Row> rows) { }

    /**
     * @param persistent whether day windows include history saved before this process started
     * @param collecting whether calls are being recorded; they come from the trace instrumentation
     */
    public record Snapshot(Window window, Instant since, Instant generatedAt, int keys, int maxKeys,
                           boolean persistent, boolean collecting, List<CategorySnapshot> categories) { }

    /** One bucket of a trend. Averages, P95 and max are null when the bucket has no calls; P95 also per minute. */
    public record Point(long timestamp, long count, long errors, Double meanMs, Double p95Ms, Double maxMs) { }

    public record Trend(Period period, long stepMillis, Instant since, boolean persistent, List<Point> points) { }

    private final int maxKeys;
    private final LongSupplier clockMillis;
    private final ZoneId zone;
    private final MetricsArchive archive;
    private final long startedAtMillis;
    private final Map<Category, ConcurrentHashMap<String, Operation>> operations = new EnumMap<>(Category.class);
    private final AtomicInteger keys = new AtomicInteger();
    /** Slices up to and including this one were appended to the archive by this process. */
    private volatile long flushedThroughSlice = Long.MIN_VALUE;
    private volatile boolean collecting = true;
    private LocalDate compactedOn;
    private ScheduledExecutorService flusher;

    public OperationStats(int maxKeys) {
        this(maxKeys, null, System::currentTimeMillis, ZoneId.systemDefault());
    }

    /** @param archive day files to persist into, or {@code null} to keep statistics in memory only */
    public OperationStats(int maxKeys, MetricsArchive archive) {
        this(maxKeys, archive, System::currentTimeMillis, ZoneId.systemDefault());
    }

    OperationStats(int maxKeys, LongSupplier clockMillis) {
        this(maxKeys, null, clockMillis, ZoneOffset.UTC);
    }

    OperationStats(int maxKeys, MetricsArchive archive, LongSupplier clockMillis, ZoneId zone) {
        this.maxKeys = Math.max(1, maxKeys);
        this.archive = archive;
        this.clockMillis = clockMillis;
        this.zone = zone;
        this.startedAtMillis = clockMillis.getAsLong();
        for (Category category : Category.values()) operations.put(category, new ConcurrentHashMap<>());
    }

    /** Marks whether any instrumentation feeds these statistics, so the page can explain an empty ranking. */
    public void setCollecting(boolean collecting) {
        this.collecting = collecting;
    }

    /** Starts the background flush and daily compaction when an archive is configured. */
    public synchronized void start() {
        if (archive == null || flusher != null) return;
        flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "easy-observability-metrics-flush");
            thread.setDaemon(true);
            return thread;
        });
        flusher.execute(this::compactIfNewDay);
        flusher.scheduleWithFixedDelay(() -> {
            try {
                flush(false);
                compactIfNewDay();
            } catch (RuntimeException failure) {
                log.warn("Easy Observability could not flush operation metrics: {}", failure.toString());
            }
        }, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Stops the background flush and saves the current, incomplete slice. */
    @Override
    public void close() {
        ScheduledExecutorService running;
        synchronized (this) {
            running = flusher;
            flusher = null;
        }
        if (running != null) running.shutdownNow();
        flush(true);
    }

    /** Records one completed call. Null, blank and oversized names are normalized instead of rejected. */
    public void record(Category category, String name, long durationNanos, boolean error) {
        record(category, name, durationNanos, error, -1, -1);
    }

    /**
     * Records one completed entry call with the CPU time and allocation of the thread that ran it; pass -1 for
     * either when unknown, and the call then counts toward duration only.
     */
    public void record(Category category, String name, long durationNanos, boolean error, long cpuNanos,
                       long allocatedBytes) {
        if (category == null) return;
        long nanos = Math.max(0, durationNanos);
        boolean measured = cpuNanos >= 0 && allocatedBytes >= 0;
        long now = clockMillis.getAsLong();
        Operation operation = operation(operations.get(category), normalizeName(name));
        Aggregate slice = operation.slice(Math.floorDiv(now, SLICE_MILLIS));
        if (slice != null) slice.cells.add(nanos, error, measured, cpuNanos, allocatedBytes, true);
        Minute minute = operation.minute(Math.floorDiv(now, MINUTE_MILLIS));
        if (minute != null) minute.cells.add(nanos, error, measured, cpuNanos, allocatedBytes, false);
    }

    /** Synchronized with {@link #flush} so a slice is never counted from both memory and the archive. */
    public synchronized Snapshot snapshot(Window window) {
        long now = clockMillis.getAsLong();
        long currentSlice = Math.floorDiv(now, SLICE_MILLIS);
        long since;
        Map<Category, Map<String, long[]>> merged = MetricsArchive.emptyResult();
        boolean persistent = persistent();
        if (window == Window.HOUR) {
            since = Math.max(startedAtMillis, (currentSlice - HOUR_SLICES + 1) * SLICE_MILLIS);
            collectSlices(merged, currentSlice - HOUR_SLICES + 1, currentSlice, Long.MIN_VALUE);
        } else {
            LocalDate today = archiveDate(now);
            LocalDate from = today.minusDays(window.days - 1L);
            long fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli();
            since = persistent ? fromMillis : Math.max(fromMillis, Math.max(startedAtMillis, oldestSliceMillis(now)));
            if (persistent) merged = archive.rankOperations(from, today);
            collectSlices(merged, firstUnflushedSlice(currentSlice, persistent), currentSlice, fromMillis);
        }
        List<CategorySnapshot> categories = new ArrayList<>();
        for (Category category : Category.values()) {
            List<Row> rows = new ArrayList<>();
            long count = 0;
            long errors = 0;
            long totalNanos = 0;
            for (Map.Entry<String, long[]> entry : capped(merged.get(category)).entrySet()) {
                long[] cells = entry.getValue();
                if (cells[COUNT] == 0) continue;
                rows.add(row(entry.getKey(), cells));
                count += cells[COUNT];
                errors += cells[ERRORS];
                totalNanos += cells[TOTAL];
            }
            rows.sort(Comparator.comparingDouble(Row::totalMs).reversed().thenComparing(Row::name));
            categories.add(new CategorySnapshot(category, count, errors, millis(totalNanos), List.copyOf(rows)));
        }
        return new Snapshot(window, Instant.ofEpochMilli(since), Instant.ofEpochMilli(now), keys.get(), maxKeys,
                persistent, collecting, List.copyOf(categories));
    }

    /** The trend of one operation. */
    public synchronized Trend trend(Category category, String name, Period period) {
        if (category == null) throw new IllegalArgumentException("category is required");
        String key = normalizeName(name);
        return trends(period, category, key).get(category);
    }

    /** The trend of every category, summed over its operations. */
    public synchronized Map<Category, Trend> trends(Period period) {
        return trends(period, null, null);
    }

    /** Clears memory and the saved operation history. */
    public synchronized void clear() {
        for (ConcurrentHashMap<String, Operation> map : operations.values()) map.clear();
        keys.set(0);
        if (archive != null) archive.clearOperations();
    }

    /**
     * Appends completed slices, and the current one when {@code includeCurrent}, that this process has not saved
     * yet.
     */
    synchronized void flush(boolean includeCurrent) {
        if (!persistent()) return;
        long now = clockMillis.getAsLong();
        long current = Math.floorDiv(now, SLICE_MILLIS);
        long last = includeCurrent ? current : current - 1;
        LocalDate today = archiveDate(now);
        for (long slice = firstUnflushedSlice(current, true); slice <= last; slice++) {
            List<MetricsArchive.OperationRow> rows = new ArrayList<>();
            for (Category category : Category.values()) {
                for (Map.Entry<String, Operation> entry : operations.get(category).entrySet()) {
                    long[] cells = entry.getValue().merge(slice, slice);
                    if (cells[COUNT] > 0) rows.add(new MetricsArchive.OperationRow(category, entry.getKey(), cells));
                }
            }
            if (!archive.appendOperations(slice * SLICE_MILLIS, rows, today)) return;
            flushedThroughSlice = slice;
        }
    }

    void compactIfNewDay() {
        if (!persistent()) return;
        LocalDate today = archiveDate(clockMillis.getAsLong());
        if (today.equals(compactedOn)) return;
        archive.compact(today);
        compactedOn = today;
    }

    private Map<Category, Trend> trends(Period period, Category onlyCategory, String onlyName) {
        long now = clockMillis.getAsLong();
        long step = period.stepMillis;
        long firstBucket = Math.floorDiv(now, step) - period.points + 1;
        long fromMillis = firstBucket * step;
        boolean persistent = persistent();
        Map<Category, long[][]> grids = new EnumMap<>(Category.class);
        if (period == Period.HOUR) {
            forEachOperation(onlyCategory, onlyName, (category, name, operation) ->
                    operation.addMinutes(grid(grids, category, period), firstBucket));
        } else {
            long currentSlice = Math.floorDiv(now, SLICE_MILLIS);
            if (persistent) {
                archive.scanOperations(archiveDate(fromMillis), archiveDate(now), (start, category, name, cells) -> {
                    if (start < fromMillis || !matches(category, name, onlyCategory, onlyName)) return;
                    addCells(grid(grids, category, period), Math.floorDiv(start, step) - firstBucket, cells);
                });
            }
            long firstSlice = Math.max(firstUnflushedSlice(currentSlice, persistent), Math.floorDiv(fromMillis, SLICE_MILLIS));
            forEachOperation(onlyCategory, onlyName, (category, name, operation) -> {
                for (long slice = firstSlice; slice <= currentSlice; slice++) {
                    long[] cells = operation.merge(slice, slice);
                    if (cells[COUNT] == 0) continue;
                    addCells(grid(grids, category, period), Math.floorDiv(slice * SLICE_MILLIS, step) - firstBucket,
                            cells);
                }
            });
        }
        long since = persistent && period != Period.HOUR ? fromMillis
                : Math.max(fromMillis, period == Period.HOUR ? startedAtMillis
                : Math.max(startedAtMillis, oldestSliceMillis(now)));
        Map<Category, Trend> result = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            if (onlyCategory != null && category != onlyCategory) continue;
            long[][] grid = grids.get(category);
            List<Point> points = new ArrayList<>(period.points);
            for (int i = 0; i < period.points; i++) {
                points.add(point((firstBucket + i) * step, grid == null ? null : grid[i], period != Period.HOUR));
            }
            result.put(category, new Trend(period, step, Instant.ofEpochMilli(since), persistent,
                    List.copyOf(points)));
        }
        return result;
    }

    private static boolean matches(Category category, String name, Category onlyCategory, String onlyName) {
        return (onlyCategory == null || onlyCategory == category) && (onlyName == null || onlyName.equals(name));
    }

    private void forEachOperation(Category onlyCategory, String onlyName, OperationVisitor visitor) {
        for (Category category : Category.values()) {
            if (onlyCategory != null && category != onlyCategory) continue;
            if (onlyName != null) {
                Operation operation = operations.get(category).get(onlyName);
                if (operation != null) visitor.visit(category, onlyName, operation);
                continue;
            }
            operations.get(category).forEach((name, operation) -> visitor.visit(category, name, operation));
        }
    }

    @FunctionalInterface
    private interface OperationVisitor {
        void visit(Category category, String name, Operation operation);
    }

    private static long[][] grid(Map<Category, long[][]> grids, Category category, Period period) {
        // Minute buckets carry only the four summary cells; slices also carry the histogram.
        int width = period == Period.HOUR ? FIRST_BUCKET : CELLS;
        return grids.computeIfAbsent(category, ignored -> new long[period.points][width]);
    }

    private static void addCells(long[][] grid, long index, long[] cells) {
        if (index < 0 || index >= grid.length) return;
        long[] into = grid[(int) index];
        for (int cell = 0; cell < into.length; cell++) {
            into[cell] = cell == MAX ? Math.max(into[cell], cells[cell]) : into[cell] + cells[cell];
        }
    }

    private static Point point(long timestamp, long[] cells, boolean histogram) {
        if (cells == null || cells[COUNT] == 0) return new Point(timestamp, 0, 0, null, null, null);
        Double p95 = histogram ? millis(percentile(cells, 0.95)) : null;
        return new Point(timestamp, cells[COUNT], cells[ERRORS], millis(cells[TOTAL] / (double) cells[COUNT]), p95,
                millis(cells[MAX]));
    }

    private boolean persistent() {
        return archive != null && archive.available();
    }

    /** Slices before this one were either flushed by this process or have left memory. */
    private long firstUnflushedSlice(long currentSlice, boolean persistent) {
        long oldest = currentSlice - SLICES + 1;
        long flushed = flushedThroughSlice;
        return persistent && flushed != Long.MIN_VALUE ? Math.max(oldest, flushed + 1) : oldest;
    }

    private static long oldestSliceMillis(long now) {
        return (Math.floorDiv(now, SLICE_MILLIS) - SLICES + 1) * SLICE_MILLIS;
    }

    private LocalDate archiveDate(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), zone);
    }

    /** Adds slices {@code [first, last]} that start at or after {@code fromMillis}. */
    private void collectSlices(Map<Category, Map<String, long[]>> into, long first, long last, long fromMillis) {
        long firstSlice = Math.max(first, fromMillis == Long.MIN_VALUE ? first : Math.floorDiv(fromMillis + SLICE_MILLIS - 1, SLICE_MILLIS));
        if (firstSlice > last) return;
        for (Category category : Category.values()) {
            for (Map.Entry<String, Operation> entry : operations.get(category).entrySet()) {
                long[] cells = entry.getValue().merge(firstSlice, last);
                if (cells[COUNT] > 0) MetricsArchive.add(into, category, entry.getKey(), cells);
            }
        }
    }

    /** Long windows may hold more names than memory does; keep the costliest and fold the rest into overflow. */
    private Map<String, long[]> capped(Map<String, long[]> entries) {
        if (entries.size() <= maxKeys) return entries;
        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<String, long[]> entry) -> entry.getValue()[TOTAL]).reversed());
        Map<String, long[]> kept = new HashMap<>();
        long[] overflow = new long[CELLS];
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, long[]> entry = sorted.get(i);
            if (i < maxKeys - 1 && !OVERFLOW_NAME.equals(entry.getKey())) kept.put(entry.getKey(), entry.getValue());
            else mergeCells(overflow, entry.getValue());
        }
        kept.put(OVERFLOW_NAME, overflow);
        return kept;
    }

    static void mergeCells(long[] into, long[] cells) {
        for (int cell = 0; cell < CELLS; cell++) {
            into[cell] = cell == MAX ? Math.max(into[cell], cells[cell]) : into[cell] + cells[cell];
        }
    }

    static void deriveCount(long[] cells) {
        long count = 0;
        for (int cell = FIRST_BUCKET; cell < CELLS; cell++) count += cells[cell];
        cells[COUNT] = count;
    }

    private Operation operation(ConcurrentHashMap<String, Operation> map, String name) {
        Operation existing = map.get(name);
        if (existing != null) return existing;
        // The check is racy by design: a few concurrent first-time names may exceed the cap slightly.
        if (keys.get() >= maxKeys) return map.computeIfAbsent(OVERFLOW_NAME, ignored -> new Operation());
        return map.computeIfAbsent(name, ignored -> {
            keys.incrementAndGet();
            return new Operation();
        });
    }

    static String normalizeName(String name) {
        if (name == null || name.isBlank()) return "-";
        String clean = name.strip().replace('\r', ' ').replace('\n', ' ');
        return clean.length() <= MAX_NAME_LENGTH ? clean : clean.substring(0, MAX_NAME_LENGTH);
    }

    private static Row row(String name, long[] cells) {
        long count = cells[COUNT];
        long measured = cells[MEASURED];
        return new Row(name, count, cells[ERRORS], millis(cells[TOTAL] / (double) count),
                millis(percentile(cells, 0.95)), millis(cells[MAX]), millis(cells[TOTAL]),
                measured == 0 ? null : millis(cells[CPU] / (double) measured),
                measured == 0 ? null : Math.round(cells[ALLOCATED] / (double) measured));
    }

    /**
     * Estimates a percentile by interpolating its rank linearly inside the histogram bucket that holds it. The
     * recorded maximum caps the top of that bucket, so clustered latencies are not pushed up to the bucket bound.
     */
    static long percentile(long[] cells, double quantile) {
        long max = cells[MAX];
        long rank = Math.max(1, (long) Math.ceil(cells[COUNT] * quantile));
        long seen = 0;
        for (int bucket = 0; bucket < BUCKET_UPPER_NANOS.length; bucket++) {
            long inBucket = cells[FIRST_BUCKET + bucket];
            if (seen + inBucket >= rank) {
                long lower = bucket == 0 ? 0 : BUCKET_UPPER_NANOS[bucket - 1];
                long upper = Math.min(BUCKET_UPPER_NANOS[bucket], max);
                if (upper <= lower) return max;
                return lower + Math.round((upper - lower) * ((rank - seen) / (double) inBucket));
            }
            seen += inBucket;
        }
        return max;
    }

    static int bucket(long nanos) {
        int low = 0;
        int high = BUCKET_UPPER_NANOS.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (nanos < BUCKET_UPPER_NANOS[middle]) high = middle;
            else low = middle + 1;
        }
        return low;
    }

    /** 10 µs to about 42 s with two buckets per doubling, so each bucket spans a factor of √2. */
    private static long[] bucketBounds() {
        long[] bounds = new long[45];
        for (int i = 0; i < bounds.length; i++) bounds[i] = Math.round(10_000 * Math.pow(2, i / 2.0));
        return bounds;
    }

    private static double millis(double nanos) {
        return Math.round(nanos / 1_000) / 1_000.0;
    }

    private static final class Operation {
        private final AtomicReferenceArray<Aggregate> slices = new AtomicReferenceArray<>(SLICES);
        private final AtomicReferenceArray<Minute> minutes = new AtomicReferenceArray<>(MINUTES);

        /** Returns the aggregate for {@code slice}, or null when a newer slice already owns the ring position. */
        private Aggregate slice(long slice) {
            int index = (int) Math.floorMod(slice, (long) SLICES);
            while (true) {
                Aggregate existing = slices.get(index);
                if (existing != null && existing.epoch == slice) return existing;
                if (existing != null && existing.epoch > slice) return null;
                Aggregate fresh = new Aggregate(slice);
                if (slices.compareAndSet(index, existing, fresh)) return fresh;
            }
        }

        private Minute minute(long minute) {
            int index = (int) Math.floorMod(minute, (long) MINUTES);
            while (true) {
                Minute existing = minutes.get(index);
                if (existing != null && existing.epoch == minute) return existing;
                if (existing != null && existing.epoch > minute) return null;
                Minute fresh = new Minute(minute);
                if (minutes.compareAndSet(index, existing, fresh)) return fresh;
            }
        }

        /** Sums the slices whose epoch lies in {@code [first, last]}. */
        private long[] merge(long first, long last) {
            long[] merged = new long[CELLS];
            for (int i = 0; i < SLICES; i++) {
                Aggregate aggregate = slices.get(i);
                if (aggregate == null || aggregate.epoch < first || aggregate.epoch > last) continue;
                mergeCells(merged, aggregate.read());
            }
            return merged;
        }

        private void addMinutes(long[][] grid, long firstMinute) {
            for (int i = 0; i < MINUTES; i++) {
                Minute minute = minutes.get(i);
                if (minute != null) addCells(grid, minute.epoch - firstMinute, minute.read());
            }
        }
    }

    /** Atomic summary cells; slices also count the latency histogram, minutes keep only the summary. */
    private static final class Cells {
        private final AtomicLongArray values;

        private Cells(int width) {
            values = new AtomicLongArray(width);
        }

        private void add(long nanos, boolean error, boolean measured, long cpuNanos, long allocatedBytes,
                         boolean histogram) {
            // Slices derive the count from the histogram on read, saving one contended atomic per call.
            if (histogram) values.getAndIncrement(FIRST_BUCKET + bucket(nanos));
            else values.getAndIncrement(COUNT);
            if (error) values.getAndIncrement(ERRORS);
            values.getAndAdd(TOTAL, nanos);
            long max = values.get(MAX);
            while (nanos > max && !values.compareAndSet(MAX, max, nanos)) max = values.get(MAX);
            if (measured) {
                values.getAndAdd(CPU, cpuNanos);
                values.getAndAdd(ALLOCATED, allocatedBytes);
                values.getAndIncrement(MEASURED);
            }
        }

        private long[] read() {
            long[] copy = new long[values.length()];
            for (int cell = 0; cell < copy.length; cell++) copy[cell] = values.get(cell);
            return copy;
        }
    }

    private static final class Aggregate {
        private final long epoch;
        private final Cells cells = new Cells(CELLS);

        private Aggregate(long epoch) {
            this.epoch = epoch;
        }

        private long[] read() {
            long[] values = cells.read();
            deriveCount(values);
            return values;
        }
    }

    private static final class Minute {
        private final long epoch;
        private final Cells cells = new Cells(FIRST_BUCKET);

        private Minute(long epoch) {
            this.epoch = epoch;
        }

        private long[] read() {
            return cells.read();
        }
    }
}
