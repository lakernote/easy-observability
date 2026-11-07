package io.github.lakernote.meerkat.diagnostics.internal;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.JMException;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heap class histograms with a diff against the previous one, and live heap dumps. Both pause the application: a
 * histogram runs a full GC to count live objects, and a dump writes every live object to disk. Each runs one at a
 * time, and histograms at most once per {@link #HISTOGRAM_INTERVAL_MS}.
 */
public final class HeapDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(HeapDiagnostics.class);
    static final long HISTOGRAM_INTERVAL_MS = 10_000;
    public static final int MAX_ROWS = 500;
    static final int GROWTH_ROWS = 20;
    private static final Pattern ROW = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+).*$");
    private static final Pattern TOTAL = Pattern.compile("^\\s*Total\\s+(\\d+)\\s+(\\d+)\\s*$");
    private static final DateTimeFormatter DUMP_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final HistogramSource histogramSource;
    private final HeapDumper dumper;
    private final LongSupplier heapUsed;
    private final Path dumpDirectory;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final ReentrantLock histogramLock = new ReentrantLock();
    private final AtomicBoolean dumping = new AtomicBoolean();
    private Snapshot previous;
    private long lastHistogramNanos;
    private boolean histogramTaken;
    private long histogramIntervalNanos = HISTOGRAM_INTERVAL_MS * 1_000_000;

    /** @param histogramInterval shortest time between two histograms; null means 10 seconds */
    public HeapDiagnostics(Duration histogramInterval) {
        this(HeapDiagnostics::diagnosticCommandHistogram, HeapDiagnostics::hotSpotDump,
                () -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                Path.of(System.getProperty("java.io.tmpdir")), Clock.systemUTC(), System::nanoTime);
        if (histogramInterval != null && !histogramInterval.isNegative()) {
            this.histogramIntervalNanos = histogramInterval.toNanos();
        }
    }

    HeapDiagnostics(HistogramSource histogramSource, HeapDumper dumper, LongSupplier heapUsed, Path dumpDirectory,
                    Clock clock, LongSupplier nanoTime) {
        this.histogramSource = histogramSource;
        this.dumper = dumper;
        this.heapUsed = heapUsed;
        this.dumpDirectory = dumpDirectory;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    /**
     * Takes a class histogram of live objects and compares it with the previous one, which it then replaces. Returns
     * {@code null} when another histogram is running or the last one finished less than the histogram interval ago.
     *
     * @param limit rows returned, largest by bytes first, between 1 and {@link #MAX_ROWS}
     */
    public Histogram histogram(int limit) throws JMException {
        if (!histogramLock.tryLock()) return null;
        try {
            long now = nanoTime.getAsLong();
            if (histogramTaken && now - lastHistogramNanos < histogramIntervalNanos) return null;
            String text = histogramSource.histogram();
            lastHistogramNanos = nanoTime.getAsLong();
            histogramTaken = true;
            Snapshot snapshot = parse(text, clock.instant());
            Snapshot before = previous;
            previous = snapshot;
            log.info("Meerkat took a heap histogram: {} classes, {} bytes", snapshot.classCount, snapshot.totalBytes);
            return compare(snapshot, before, Math.max(1, Math.min(MAX_ROWS, limit)));
        } finally {
            histogramLock.unlock();
        }
    }

    public boolean hasPrevious() {
        histogramLock.lock();
        try {
            return previous != null;
        } finally {
            histogramLock.unlock();
        }
    }

    /** Parses {@code jmap -histo} / {@code GC.class_histogram} output, keeping the {@link #MAX_ROWS} largest rows. */
    static Snapshot parse(String text, Instant capturedAt) {
        List<Row> rows = new ArrayList<>();
        long totalInstances = 0;
        long totalBytes = 0;
        int classes = 0;
        boolean totalSeen = false;
        for (String line : text.split("\\R")) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                classes++;
                long instances = Long.parseLong(row.group(1));
                long bytes = Long.parseLong(row.group(2));
                if (!totalSeen) {
                    totalInstances += instances;
                    totalBytes += bytes;
                }
                rows.add(new Row(row.group(3), instances, bytes));
                continue;
            }
            Matcher total = TOTAL.matcher(line);
            if (total.matches()) {
                totalInstances = Long.parseLong(total.group(1));
                totalBytes = Long.parseLong(total.group(2));
                totalSeen = true;
            }
        }
        rows.sort(Comparator.comparingLong(Row::bytes).reversed());
        boolean complete = rows.size() <= MAX_ROWS;
        List<Row> kept = complete ? rows : List.copyOf(rows.subList(0, MAX_ROWS));
        return new Snapshot(capturedAt, totalInstances, totalBytes, classes, kept, complete);
    }

    private static Histogram compare(Snapshot current, Snapshot before, int limit) {
        Map<String, Row> old = new HashMap<>();
        if (before != null) for (Row row : before.rows) old.put(row.className(), row);
        List<HistogramRow> rows = new ArrayList<>(current.rows.size());
        for (Row row : current.rows) {
            Row was = old.get(row.className());
            // A class missing from a truncated previous snapshot had an unknown, smaller count.
            boolean known = before != null && (was != null || before.complete);
            rows.add(new HistogramRow(row.className(), row.instances(), row.bytes(),
                    known ? row.instances() - (was == null ? 0 : was.instances()) : null,
                    known ? row.bytes() - (was == null ? 0 : was.bytes()) : null,
                    before != null && was == null && before.complete));
        }
        List<HistogramRow> growth = rows.stream().filter(row -> row.deltaBytes() != null && row.deltaBytes() > 0)
                .sorted(Comparator.comparingLong(HistogramRow::deltaBytes).reversed()).limit(GROWTH_ROWS).toList();
        return new Histogram(current.capturedAt, current.totalInstances, current.totalBytes, current.classCount,
                before == null ? null : before.capturedAt,
                before == null ? null : current.totalInstances - before.totalInstances,
                before == null ? null : current.totalBytes - before.totalBytes,
                rows.subList(0, Math.min(limit, rows.size())), growth);
    }

    /**
     * Writes a dump of live objects to a new temporary file. The caller streams it and must close the returned
     * dump, which deletes the file. Returns {@code null} when another dump is in progress.
     *
     * @throws IllegalStateException when the temporary directory lacks space for the used heap
     */
    public HeapDump dumpHeap() throws IOException {
        if (!dumping.compareAndSet(false, true)) return null;
        Path directory = null;
        try {
            Files.createDirectories(dumpDirectory);
            long used = heapUsed.getAsLong();
            long usable = Files.getFileStore(dumpDirectory).getUsableSpace();
            if (usable < used + used / 10) {
                throw new IllegalStateException("Not enough disk space in " + dumpDirectory + " for a heap dump: "
                        + usable / (1024 * 1024) + " MB free, about " + used / (1024 * 1024) + " MB needed");
            }
            directory = Files.createTempDirectory(dumpDirectory, "meerkat-heap-");
            String name = "heap-" + DUMP_NAME.format(LocalDateTime.now(clock)) + ".hprof";
            Path file = directory.resolve(name);
            long started = System.nanoTime();
            dumper.dump(file.toString(), true);
            log.warn("Meerkat wrote a heap dump of {} bytes in {} ms for download", Files.size(file),
                    (System.nanoTime() - started) / 1_000_000);
            return new HeapDump(name, file, Files.size(file), this::release);
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(directory);
            dumping.set(false);
            throw failure;
        }
    }

    public boolean dumping() {
        return dumping.get();
    }

    private void release(Path file) {
        deleteQuietly(file.getParent());
        dumping.set(false);
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) return;
        try (var files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Already gone.
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException failure) {
            log.warn("Meerkat could not delete heap dump directory {}", directory, failure);
        }
    }

    private static String diagnosticCommandHistogram() throws JMException {
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        return String.valueOf(ManagementFactory.getPlatformMBeanServer().invoke(name, "gcClassHistogram",
                new Object[] {new String[0]}, new String[] {String[].class.getName()}));
    }

    private static void hotSpotDump(String file, boolean live) throws IOException {
        HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        if (bean == null) throw new UnsupportedOperationException("This JVM cannot write heap dumps");
        bean.dumpHeap(file, live);
    }

    @FunctionalInterface
    interface HistogramSource {
        String histogram() throws JMException;
    }

    @FunctionalInterface
    interface HeapDumper {
        void dump(String file, boolean live) throws IOException;
    }

    record Row(String className, long instances, long bytes) { }

    record Snapshot(Instant capturedAt, long totalInstances, long totalBytes, int classCount, List<Row> rows,
                    boolean complete) { }

    /**
     * A class row; deltas are against the previous histogram and null when there is none or the class was beyond
     * the rows kept from it. {@code added} marks classes absent from a complete previous histogram.
     */
    public record HistogramRow(String className, long instances, long bytes, Long deltaInstances, Long deltaBytes,
                               boolean added) { }

    /** {@code growth} lists the rows that grew most in bytes since {@code previousAt}. */
    public record Histogram(Instant capturedAt, long totalInstances, long totalBytes, int classCount,
                            Instant previousAt, Long deltaInstances, Long deltaBytes, List<HistogramRow> rows,
                            List<HistogramRow> growth) { }

    /** A heap dump file to stream; closing it deletes the file and allows the next dump. */
    public static final class HeapDump implements AutoCloseable {
        private final String fileName;
        private final Path file;
        private final long size;
        private final Consumer<Path> onClose;
        private final AtomicBoolean closed = new AtomicBoolean();

        HeapDump(String fileName, Path file, long size, Consumer<Path> onClose) {
            this.fileName = fileName;
            this.file = file;
            this.size = size;
            this.onClose = onClose;
        }

        public String fileName() {
            return fileName;
        }

        public Path file() {
            return file;
        }

        public long size() {
            return size;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) onClose.accept(file);
        }
    }
}
