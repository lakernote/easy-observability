package io.github.lakernote.observability.stats.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.lakernote.observability.stats.internal.OperationStats.CELLS;
import static io.github.lakernote.observability.stats.internal.OperationStats.ERRORS;
import static io.github.lakernote.observability.stats.internal.OperationStats.FIRST_BUCKET;
import static io.github.lakernote.observability.stats.internal.OperationStats.MAX;
import static io.github.lakernote.observability.stats.internal.OperationStats.TOTAL;

/**
 * Append-only daily files of hourly operation aggregates, one {@code yyyyMMdd.tsv} per local day.
 *
 * <p>Each line is one operation in one hour: hour start, category, errors, total and max nanos, the non-empty
 * histogram buckets, name and trace query. The same operation and hour may appear several times (for example after
 * a restart within the hour); readers sum them. Files older than {@value #RETENTION_DAYS} days, or the oldest days
 * beyond the size budget, are deleted. Any write failure turns persistence off and statistics stay in memory.</p>
 */
public final class OperationStatsArchive {
    static final int RETENTION_DAYS = 31;
    static final String HEADER = "# easy-observability operation stats v1";
    private static final Logger log = LoggerFactory.getLogger(OperationStatsArchive.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String SUFFIX = ".tsv";

    private final Path directory;
    private final long maxBytes;
    private final ZoneId zone;
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile boolean available;
    private String closedDaysSignature;
    private Map<OperationStats.Category, Map<String, Entry>> closedDays;
    private long todaySize = -1;
    private LocalDate todayDate;
    private Map<OperationStats.Category, Map<String, Entry>> today;

    /** One operation's aggregate cells and the text that finds its traces. */
    record Entry(String traceQuery, long[] cells) { }

    /** One operation's cells for one hour, as collected from memory. */
    record HourRow(OperationStats.Category category, String name, String traceQuery, long[] cells) { }

    /** @param directory archive directory, or {@code null} to disable persistence */
    public OperationStatsArchive(Path directory, long maxBytes, ZoneId zone) {
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.zone = zone;
        if (this.directory == null) return;
        try {
            Files.createDirectories(this.directory);
            available = true;
        } catch (IOException | RuntimeException failure) {
            disable("create the statistics directory", failure);
        }
    }

    public boolean available() {
        return available;
    }

    /** Appends one hour of aggregates. Returns false when persistence is off, so the caller keeps the data. */
    synchronized boolean append(long hourStartMillis, List<HourRow> rows, LocalDate today) {
        if (!available) return false;
        if (rows.isEmpty()) return true;
        LocalDate date = date(hourStartMillis);
        Path file = file(date);
        try {
            boolean fresh = !Files.exists(file);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) writer.write(HEADER + "\n");
                StringBuilder line = new StringBuilder(256);
                for (HourRow row : rows) {
                    line.setLength(0);
                    line.append(hourStartMillis).append('\t').append(row.category().name()).append('\t')
                            .append(row.cells()[ERRORS]).append('\t').append(row.cells()[TOTAL]).append('\t')
                            .append(row.cells()[MAX]).append('\t');
                    boolean first = true;
                    for (int cell = FIRST_BUCKET; cell < CELLS; cell++) {
                        if (row.cells()[cell] == 0) continue;
                        if (!first) line.append(';');
                        line.append(cell - FIRST_BUCKET).append(':').append(row.cells()[cell]);
                        first = false;
                    }
                    line.append('\t').append(escape(row.name())).append('\t').append(escape(row.traceQuery()))
                            .append('\n');
                    writer.write(line.toString());
                }
            }
            enforceLimits(today);
            return true;
        } catch (IOException | RuntimeException failure) {
            disable("write operation statistics", failure);
            return false;
        }
    }

    /** Sums every archived operation recorded on the local days from {@code from} through {@code today}. */
    synchronized Map<OperationStats.Category, Map<String, Entry>> read(LocalDate from, LocalDate today) {
        Map<OperationStats.Category, Map<String, Entry>> result = emptyResult();
        if (!available) return result;
        try {
            TreeMap<LocalDate, Path> files = files();
            StringBuilder signature = new StringBuilder(from.toString());
            List<Path> closed = new ArrayList<>();
            for (Map.Entry<LocalDate, Path> file : files.entrySet()) {
                if (file.getKey().isBefore(from) || !file.getKey().isBefore(today)) continue;
                closed.add(file.getValue());
                signature.append('|').append(file.getKey()).append(':').append(Files.size(file.getValue()));
            }
            if (!signature.toString().equals(closedDaysSignature)) {
                Map<OperationStats.Category, Map<String, Entry>> merged = emptyResult();
                for (Path file : closed) parse(file, merged);
                closedDays = merged;
                closedDaysSignature = signature.toString();
            }
            Path todayFile = files.get(today);
            long size = todayFile == null ? -1 : Files.size(todayFile);
            if (!today.equals(todayDate) || size != todaySize) {
                Map<OperationStats.Category, Map<String, Entry>> parsed = emptyResult();
                if (todayFile != null) parse(todayFile, parsed);
                this.today = parsed;
                todayDate = today;
                todaySize = size;
            }
            merge(result, closedDays);
            merge(result, this.today);
        } catch (IOException | RuntimeException failure) {
            log.warn("Easy Observability could not read operation statistics from {}: {}", directory,
                    failure.toString());
        }
        return result;
    }

    synchronized void clear() {
        closedDays = null;
        closedDaysSignature = null;
        today = null;
        todayDate = null;
        todaySize = -1;
        if (!available) return;
        try {
            for (Path file : files().values()) Files.deleteIfExists(file);
        } catch (IOException | RuntimeException failure) {
            disable("delete operation statistics", failure);
        }
    }

    LocalDate date(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone);
    }

    private void enforceLimits(LocalDate today) throws IOException {
        TreeMap<LocalDate, Path> files = files();
        LocalDate oldestKept = today.minusDays(RETENTION_DAYS - 1L);
        long total = 0;
        for (Map.Entry<LocalDate, Path> file : new ArrayList<>(files.entrySet())) {
            if (file.getKey().isBefore(oldestKept)) {
                Files.deleteIfExists(file.getValue());
                files.remove(file.getKey());
            } else {
                total += Files.size(file.getValue());
            }
        }
        while (total > maxBytes && files.size() > 1) {
            Map.Entry<LocalDate, Path> oldest = files.pollFirstEntry();
            total -= Files.size(oldest.getValue());
            Files.deleteIfExists(oldest.getValue());
            if (warned.compareAndSet(false, true)) {
                log.warn("Easy Observability operation statistics exceed easy.observability.stats.max-storage-size; "
                        + "deleting the oldest days, so long ranges cover fewer days than requested");
            }
        }
    }

    private TreeMap<LocalDate, Path> files() throws IOException {
        TreeMap<LocalDate, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                try {
                    files.put(LocalDate.parse(name.substring(0, name.length() - SUFFIX.length()), FILE_DATE), file);
                } catch (DateTimeParseException ignored) {
                    // Not one of ours.
                }
            }
        }
        return files;
    }

    private Path file(LocalDate date) {
        return directory.resolve(FILE_DATE.format(date) + SUFFIX);
    }

    private static void parse(Path file, Map<OperationStats.Category, Map<String, Entry>> into) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 8) continue;
            try {
                OperationStats.Category category = OperationStats.Category.valueOf(fields[1]);
                long[] cells = new long[CELLS];
                cells[ERRORS] = Long.parseLong(fields[2]);
                cells[TOTAL] = Long.parseLong(fields[3]);
                cells[MAX] = Long.parseLong(fields[4]);
                if (!fields[5].isEmpty()) {
                    for (String bucket : fields[5].split(";")) {
                        int colon = bucket.indexOf(':');
                        int index = FIRST_BUCKET + Integer.parseInt(bucket.substring(0, colon));
                        if (index < FIRST_BUCKET || index >= CELLS) throw new IllegalArgumentException(bucket);
                        cells[index] += Long.parseLong(bucket.substring(colon + 1));
                    }
                }
                OperationStats.deriveCount(cells);
                add(into, category, unescape(fields[6]), unescape(fields[7]), cells);
            } catch (RuntimeException corrupt) {
                // A torn or foreign line must not hide the rest of the history.
            }
        }
    }

    private static void merge(Map<OperationStats.Category, Map<String, Entry>> into,
                              Map<OperationStats.Category, Map<String, Entry>> source) {
        if (source == null) return;
        source.forEach((category, entries) -> entries.forEach((name, entry) ->
                add(into, category, name, entry.traceQuery(), entry.cells())));
    }

    static void add(Map<OperationStats.Category, Map<String, Entry>> into, OperationStats.Category category,
                    String name, String traceQuery, long[] cells) {
        into.get(category).compute(name, (ignored, existing) -> {
            if (existing == null) return new Entry(traceQuery, cells.clone());
            OperationStats.mergeCells(existing.cells(), cells);
            return existing;
        });
    }

    static Map<OperationStats.Category, Map<String, Entry>> emptyResult() {
        Map<OperationStats.Category, Map<String, Entry>> result = new EnumMap<>(OperationStats.Category.class);
        for (OperationStats.Category category : OperationStats.Category.values()) result.put(category, new HashMap<>());
        return result;
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    static String unescape(String value) {
        if (value.indexOf('\\') < 0) return value;
        StringBuilder plain = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 == value.length()) {
                plain.append(c);
                continue;
            }
            char next = value.charAt(++i);
            plain.append(switch (next) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                default -> next;
            });
        }
        return plain.toString();
    }

    private void disable(String action, Throwable failure) {
        available = false;
        log.warn("Easy Observability could not {} in {}; operation statistics are kept in memory only: {}",
                action, directory, failure.toString());
    }
}
