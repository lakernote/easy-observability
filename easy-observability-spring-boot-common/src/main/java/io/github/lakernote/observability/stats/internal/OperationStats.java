package io.github.lakernote.observability.stats.internal;

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
 * <p>Each operation keeps a two-hour ring of 10-minute slices in memory. Every slice is one {@link AtomicLongArray}
 * holding errors, total and max duration plus a log-scale latency histogram from which P95 is estimated (within
 * about ±20%). Completed hours are appended to an {@link OperationStatsArchive} once a minute and the current hour
 * on shutdown, so the "today", 7-day and 30-day rankings survive restarts. The number of distinct operations is
 * capped; later operations are merged into one overflow row per category so memory stays bounded.</p>
 */
public final class OperationStats implements AutoCloseable {
    /** Name of the per-category row that absorbs operations beyond {@code max-keys}. */
    public static final String OVERFLOW_NAME = "（其他：超出统计数量上限）";
    static final int MAX_NAME_LENGTH = 512;
    static final long SLICE_MILLIS = 10 * 60 * 1000L;
    static final long HOUR_MILLIS = 60 * 60 * 1000L;
    static final int SLICES_PER_HOUR = (int) (HOUR_MILLIS / SLICE_MILLIS);
    /** Two hours, so a completed hour stays readable until the next flush. */
    static final int SLICES = 2 * SLICES_PER_HOUR;
    private static final long FLUSH_INTERVAL_SECONDS = 60;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OperationStats.class);

    static final int COUNT = 0;
    static final int ERRORS = 1;
    static final int TOTAL = 2;
    static final int MAX = 3;
    static final int FIRST_BUCKET = 4;
    /** Bucket {@code i} holds durations below {@code BUCKET_UPPER_NANOS[i]}; one extra bucket is unbounded. */
    private static final long[] BUCKET_UPPER_NANOS = bucketBounds();
    static final int CELLS = FIRST_BUCKET + BUCKET_UPPER_NANOS.length + 1;

    public enum Category { HTTP, SQL, REMOTE, REDIS, JOB, KAFKA_CONSUMER, KAFKA_PRODUCER }

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
                default -> throw new IllegalArgumentException("Unknown statistics window: " + value);
            };
        }
    }

    /**
     * {@code traceQuery} is the text to search for in the Trace Explorer; it matches what traces store for this
     * operation, which may differ from the display name. Empty for the overflow row.
     */
    public record Row(String name, String traceQuery, long count, long errors, double meanMs, double p95Ms,
                      double maxMs, double totalMs) { }

    public record CategorySnapshot(Category category, long count, long errors, double totalMs, List<Row> rows) { }

    /**
     * @param persistent whether day windows include history saved before this process started
     */
    public record Snapshot(Window window, Instant since, Instant generatedAt, int keys, int maxKeys,
                           boolean persistent, List<CategorySnapshot> categories) { }

    private final int maxKeys;
    private final LongSupplier clockMillis;
    private final ZoneId zone;
    private final OperationStatsArchive archive;
    private final long startedAtMillis;
    private final Map<Category, ConcurrentHashMap<String, Operation>> operations = new EnumMap<>(Category.class);
    private final AtomicInteger keys = new AtomicInteger();
    /** Hours up to and including this one were appended to the archive by this process. */
    private volatile long flushedThroughHour = Long.MIN_VALUE;
    private ScheduledExecutorService flusher;

    public OperationStats(int maxKeys) {
        this(maxKeys, null, System::currentTimeMillis, ZoneId.systemDefault());
    }

    /** @param archive day files to persist into, or {@code null} to keep statistics in memory only */
    public OperationStats(int maxKeys, OperationStatsArchive archive) {
        this(maxKeys, archive, System::currentTimeMillis, ZoneId.systemDefault());
    }

    OperationStats(int maxKeys, LongSupplier clockMillis) {
        this(maxKeys, null, clockMillis, ZoneOffset.UTC);
    }

    OperationStats(int maxKeys, OperationStatsArchive archive, LongSupplier clockMillis, ZoneId zone) {
        this.maxKeys = Math.max(1, maxKeys);
        this.archive = archive;
        this.clockMillis = clockMillis;
        this.zone = zone;
        this.startedAtMillis = clockMillis.getAsLong();
        for (Category category : Category.values()) operations.put(category, new ConcurrentHashMap<>());
    }

    /** Starts the background flush when an archive is configured. */
    public synchronized void start() {
        if (archive == null || flusher != null) return;
        flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "easy-observability-stats-flush");
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleWithFixedDelay(() -> {
            try {
                flush(false);
            } catch (RuntimeException failure) {
                log.warn("Easy Observability could not flush operation statistics: {}", failure.toString());
            }
        }, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Stops the background flush and saves the current, incomplete hour. */
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
        record(category, name, null, durationNanos, error);
    }

    /**
     * Records one completed call. {@code traceQuery} is used only when the operation is first seen; null means the
     * name itself is searchable in the Trace Explorer.
     */
    public void record(Category category, String name, String traceQuery, long durationNanos, boolean error) {
        if (category == null) return;
        long nanos = Math.max(0, durationNanos);
        Operation operation = operation(operations.get(category), normalizeName(name), traceQuery);
        Aggregate slice = operation.slice(Math.floorDiv(clockMillis.getAsLong(), SLICE_MILLIS));
        if (slice != null) slice.add(nanos, error);
    }

    /** Synchronized with {@link #flush} so an hour is never counted from both memory and the archive. */
    public synchronized Snapshot snapshot(Window window) {
        long now = clockMillis.getAsLong();
        long since;
        Map<Category, Map<String, OperationStatsArchive.Entry>> merged = OperationStatsArchive.emptyResult();
        boolean persistent = archive != null && archive.available();
        if (window == Window.HOUR) {
            long currentSlice = Math.floorDiv(now, SLICE_MILLIS);
            since = Math.max(startedAtMillis, (currentSlice - SLICES_PER_HOUR + 1) * SLICE_MILLIS);
            collect(merged, currentSlice - SLICES_PER_HOUR + 1, currentSlice);
        } else {
            LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone);
            LocalDate from = today.minusDays(window.days - 1L);
            long fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli();
            long currentHour = Math.floorDiv(now, HOUR_MILLIS);
            // Without an archive only the two in-memory hours exist.
            since = persistent ? fromMillis
                    : Math.max(fromMillis, Math.max(startedAtMillis, (currentHour - 1) * HOUR_MILLIS));
            if (persistent) merged = archive.read(from, today);
            // Unflushed hours come from memory; flushed ones are already in the archive.
            long firstHour = Math.max(currentHour - 1, persistent && flushedThroughHour != Long.MIN_VALUE
                    ? flushedThroughHour + 1 : Long.MIN_VALUE);
            for (long hour = firstHour; hour <= currentHour; hour++) {
                if (!sameOrAfter(hour, from)) continue;
                collect(merged, hour * SLICES_PER_HOUR, hour * SLICES_PER_HOUR + SLICES_PER_HOUR - 1);
            }
        }
        List<CategorySnapshot> categories = new ArrayList<>();
        for (Category category : Category.values()) {
            List<Row> rows = new ArrayList<>();
            long count = 0;
            long errors = 0;
            long totalNanos = 0;
            for (Map.Entry<String, OperationStatsArchive.Entry> entry : capped(merged.get(category)).entrySet()) {
                long[] cells = entry.getValue().cells();
                if (cells[COUNT] == 0) continue;
                rows.add(row(entry.getKey(), entry.getValue().traceQuery(), cells));
                count += cells[COUNT];
                errors += cells[ERRORS];
                totalNanos += cells[TOTAL];
            }
            rows.sort(Comparator.comparingDouble(Row::totalMs).reversed().thenComparing(Row::name));
            categories.add(new CategorySnapshot(category, count, errors, millis(totalNanos), List.copyOf(rows)));
        }
        return new Snapshot(window, Instant.ofEpochMilli(since), Instant.ofEpochMilli(now), keys.get(), maxKeys,
                persistent, List.copyOf(categories));
    }

    /** Clears memory and every saved day. */
    public synchronized void clear() {
        for (ConcurrentHashMap<String, Operation> map : operations.values()) map.clear();
        keys.set(0);
        if (archive != null) archive.clear();
    }

    /**
     * Appends completed hours, and the current one when {@code includeCurrentHour}, that this process has not
     * saved yet. Only the previous and current hour are still in memory.
     */
    synchronized void flush(boolean includeCurrentHour) {
        if (archive == null || !archive.available()) return;
        long now = clockMillis.getAsLong();
        long currentHour = Math.floorDiv(now, HOUR_MILLIS);
        long last = includeCurrentHour ? currentHour : currentHour - 1;
        long first = flushedThroughHour == Long.MIN_VALUE ? currentHour - 1
                : Math.max(flushedThroughHour + 1, currentHour - 1);
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone);
        for (long hour = first; hour <= last; hour++) {
            List<OperationStatsArchive.HourRow> rows = new ArrayList<>();
            for (Category category : Category.values()) {
                for (Map.Entry<String, Operation> entry : operations.get(category).entrySet()) {
                    long[] cells = entry.getValue().merge(hour * SLICES_PER_HOUR,
                            hour * SLICES_PER_HOUR + SLICES_PER_HOUR - 1);
                    if (cells[COUNT] > 0) {
                        rows.add(new OperationStatsArchive.HourRow(category, entry.getKey(),
                                entry.getValue().traceQuery, cells));
                    }
                }
            }
            if (!archive.append(hour * HOUR_MILLIS, rows, today)) return;
            flushedThroughHour = hour;
        }
    }

    private boolean sameOrAfter(long hour, LocalDate from) {
        return !LocalDate.ofInstant(Instant.ofEpochMilli(hour * HOUR_MILLIS), zone).isBefore(from);
    }

    private void collect(Map<Category, Map<String, OperationStatsArchive.Entry>> into, long firstSlice,
                         long lastSlice) {
        for (Category category : Category.values()) {
            for (Map.Entry<String, Operation> entry : operations.get(category).entrySet()) {
                long[] cells = entry.getValue().merge(firstSlice, lastSlice);
                if (cells[COUNT] > 0) {
                    OperationStatsArchive.add(into, category, entry.getKey(), entry.getValue().traceQuery, cells);
                }
            }
        }
    }

    /** Long windows may hold more names than memory does; keep the costliest and fold the rest into overflow. */
    private Map<String, OperationStatsArchive.Entry> capped(Map<String, OperationStatsArchive.Entry> entries) {
        if (entries.size() <= maxKeys) return entries;
        List<Map.Entry<String, OperationStatsArchive.Entry>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator.comparingLong(
                (Map.Entry<String, OperationStatsArchive.Entry> entry) -> entry.getValue().cells()[TOTAL]).reversed());
        Map<String, OperationStatsArchive.Entry> kept = new HashMap<>();
        long[] overflow = new long[CELLS];
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, OperationStatsArchive.Entry> entry = sorted.get(i);
            if (i < maxKeys - 1 && !OVERFLOW_NAME.equals(entry.getKey())) kept.put(entry.getKey(), entry.getValue());
            else mergeCells(overflow, entry.getValue().cells());
        }
        kept.put(OVERFLOW_NAME, new OperationStatsArchive.Entry("", overflow));
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

    private Operation operation(ConcurrentHashMap<String, Operation> map, String name, String traceQuery) {
        Operation existing = map.get(name);
        if (existing != null) return existing;
        // The check is racy by design: a few concurrent first-time names may exceed the cap slightly.
        if (keys.get() >= maxKeys) return map.computeIfAbsent(OVERFLOW_NAME, ignored -> new Operation(""));
        return map.computeIfAbsent(name, ignored -> {
            keys.incrementAndGet();
            return new Operation(traceQuery == null || traceQuery.isBlank() ? name : normalizeName(traceQuery));
        });
    }

    static String normalizeName(String name) {
        if (name == null || name.isBlank()) return "-";
        String clean = name.strip().replace('\r', ' ').replace('\n', ' ');
        return clean.length() <= MAX_NAME_LENGTH ? clean : clean.substring(0, MAX_NAME_LENGTH);
    }

    private static Row row(String name, String traceQuery, long[] cells) {
        long count = cells[COUNT];
        return new Row(name, traceQuery, count, cells[ERRORS], millis(cells[TOTAL] / (double) count),
                millis(Math.min(percentile(cells, 0.95), cells[MAX])), millis(cells[MAX]), millis(cells[TOTAL]));
    }

    /** Estimates a percentile as the geometric midpoint of the histogram bucket containing its rank. */
    static long percentile(long[] cells, double quantile) {
        long rank = Math.max(1, (long) Math.ceil(cells[COUNT] * quantile));
        long seen = 0;
        for (int bucket = 0; bucket < BUCKET_UPPER_NANOS.length; bucket++) {
            seen += cells[FIRST_BUCKET + bucket];
            if (seen >= rank) {
                long upper = BUCKET_UPPER_NANOS[bucket];
                long lower = bucket == 0 ? 0 : BUCKET_UPPER_NANOS[bucket - 1];
                return lower == 0 ? upper / 2 : (long) Math.sqrt((double) lower * upper);
            }
        }
        return cells[MAX];
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
        private final String traceQuery;
        private final AtomicReferenceArray<Aggregate> slices = new AtomicReferenceArray<>(SLICES);

        private Operation(String traceQuery) {
            this.traceQuery = traceQuery;
        }

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
    }

    private static final class Aggregate {
        private final long epoch;
        private final AtomicLongArray cells = new AtomicLongArray(CELLS);

        private Aggregate(long epoch) {
            this.epoch = epoch;
        }

        /** The count is derived from the histogram on read, saving one contended atomic per call. */
        private void add(long nanos, boolean error) {
            if (error) cells.getAndIncrement(ERRORS);
            cells.getAndAdd(TOTAL, nanos);
            long max = cells.get(MAX);
            while (nanos > max && !cells.compareAndSet(MAX, max, nanos)) max = cells.get(MAX);
            cells.getAndIncrement(FIRST_BUCKET + bucket(nanos));
        }

        private long[] read() {
            long[] values = new long[CELLS];
            for (int cell = 0; cell < CELLS; cell++) values[cell] = cells.get(cell);
            deriveCount(values);
            return values;
        }
    }
}
