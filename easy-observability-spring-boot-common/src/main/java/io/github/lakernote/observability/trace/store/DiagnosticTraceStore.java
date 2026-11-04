package io.github.lakernote.observability.trace.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lakernote.observability.trace.BoundedHistoryWriter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/** Persists completed diagnostic traces so they remain available after a restart. */
public final class DiagnosticTraceStore {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticTraceStore.class);
    private static final int FILE_BUFFER_BYTES = 16 * 1024;
    // A corrupt record must not turn a generously configured archive limit into one giant heap allocation.
    private static final long MAX_HISTORY_RECORD_BYTES = 16L * 1024 * 1024;
    private static final long MAX_TRIM_SCAN_BYTES = 64L * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final Path historyFile;
    private final int maxHistorySize;
    private final long maxHistoryFileBytes;
    private final Consumer<Runnable> historyWriteExecutor;
    private final ConcurrentLinkedDeque<RecentTraceStore.TraceRecord> diagnosticTraces = new ConcurrentLinkedDeque<>();
    private final Object storageLock = new Object();
    private long clearGeneration;
    private boolean persistenceEnabled = true;
    private boolean storageWarningLogged;
    private boolean oversizedRecordWarningLogged;

    public DiagnosticTraceStore(ObjectMapper mapper, String historyFile, int maxHistorySize, long maxHistoryFileBytes) {
        this(mapper, historyFile, maxHistorySize, maxHistoryFileBytes, Runnable::run);
    }

    public DiagnosticTraceStore(ObjectMapper mapper, String historyFile, int maxHistorySize, long maxHistoryFileBytes,
                        BoundedHistoryWriter writer) {
        this(mapper, historyFile, maxHistorySize, maxHistoryFileBytes, writer::execute);
    }

    private DiagnosticTraceStore(ObjectMapper mapper, String historyFile, int maxHistorySize, long maxHistoryFileBytes,
                         Consumer<Runnable> historyWriteExecutor) {
        this.objectMapper = mapper.copy();
        this.historyFile = Path.of(historyFile).toAbsolutePath().normalize();
        this.maxHistorySize = Math.max(100, maxHistorySize);
        this.maxHistoryFileBytes = Math.max(1024 * 1024, maxHistoryFileBytes);
        this.historyWriteExecutor = historyWriteExecutor;
    }

    @PostConstruct
    public void restoreHistory() {
        // JSONL 按行恢复；单条记录损坏时跳过该行，尽量保留其他有效历史。
        synchronized (storageLock) {
            try {
                if (!persistenceEnabled) return;
                if (!Files.isRegularFile(historyFile)) return;
                if (Files.size(historyFile) > maxHistoryFileBytes) trimHistoryFile();
                restoreHistoryLines();
            } catch (Exception failure) {
                disablePersistence("restore trace history", failure);
            }
        }
    }

    public void recordDiagnosticTrace(RecentTraceStore.TraceRecord trace) {
        if (trace == null || !RecentTraceStore.isEntrySource(trace.sourceType())
                || !isDiagnosticTrace(trace)) return;
        long writeGeneration;
        synchronized (storageLock) {
            addToIndex(trace);
            if (!persistenceEnabled) return;
            writeGeneration = clearGeneration;
        }
        // Starter 默认通过有界后台队列写文件，避免磁盘 I/O 占用请求线程。
        try {
            historyWriteExecutor.accept(() -> appendToFile(trace, writeGeneration));
        } catch (RuntimeException failure) {
            disablePersistence("schedule trace history write", failure);
        }
    }

    private static boolean isDiagnosticTrace(RecentTraceStore.TraceRecord trace) {
        return trace.slow() || trace.error() || trace.highCpu() || trace.highAllocation();
    }

    private void appendToFile(RecentTraceStore.TraceRecord trace, long writeGeneration) {
        synchronized (storageLock) {
            if (!persistenceEnabled || writeGeneration != clearGeneration) return;
            try {
                Files.createDirectories(historyFile.getParent());
                int maxRecordBytes = (int) maxHistoryRecordBytes();
                BoundedHistoryRecordBuffer record = new BoundedHistoryRecordBuffer(maxRecordBytes);
                try {
                    objectMapper.writeValue(record, trace);
                    record.write('\n');
                } catch (Exception failure) {
                    if (causedByOversizedRecord(failure)) {
                        warnOversizedRecord(maxRecordBytes);
                        return;
                    }
                    throw failure;
                }
                if (Files.exists(historyFile)
                        && Files.size(historyFile) > maxHistoryFileBytes - record.size()) {
                    long retainedBytes = Math.min(maxHistoryFileBytes / 2,
                            Math.max(0, maxHistoryFileBytes - record.size()));
                    trimHistoryFile(retainedBytes);
                }
                try (FileChannel target = FileChannel.open(historyFile, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    writeFully(target, ByteBuffer.wrap(record.bytes(), 0, record.size()));
                }
            } catch (Exception failure) {
                disablePersistence("write trace history", failure);
            }
        }
    }

    private void warnOversizedRecord(long maxRecordBytes) {
        if (oversizedRecordWarningLogged) return;
        oversizedRecordWarningLogged = true;
        log.warn("Skipping oversized Easy Observability trace history record; maxRecordBytes={}, path={}",
                maxRecordBytes, historyFile);
    }

    private static boolean causedByOversizedRecord(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HistoryRecordTooLargeException) return true;
        }
        return false;
    }

    private void disablePersistence(String operation, Exception failure) {
        synchronized (storageLock) {
            persistenceEnabled = false;
            if (storageWarningLogged) return;
            storageWarningLogged = true;
            log.warn("Could not {}; local trace history is disabled until the application restarts; path={}",
                    operation, historyFile, failure);
        }
    }

    /** Clears the diagnostic JSONL history and its in-memory index; queued earlier writes are discarded. */
    public int clearHistory() throws java.io.IOException {
        synchronized (storageLock) {
            clearGeneration++;
            int cleared = diagnosticTraces.size();
            diagnosticTraces.clear();
            Files.deleteIfExists(historyFile);
            Files.deleteIfExists(historyFile.resolveSibling(historyFile.getFileName() + ".tmp"));
            return cleared;
        }
    }

    public List<RecentTraceStore.TraceRecord> findRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, maxHistorySize));
        List<RecentTraceStore.TraceRecord> result = new ArrayList<>(Math.min(safeLimit, 256));
        for (RecentTraceStore.TraceRecord trace : diagnosticTraces) {
            if (!RecentTraceStore.isEntrySource(trace.sourceType())) continue;
            if (result.size() >= safeLimit) break;
            result.add(trace);
        }
        return List.copyOf(result);
    }

    public StorageStatus storageStatus() {
        synchronized (storageLock) {
            if (!persistenceEnabled) {
                return new StorageStatus(diagnosticTraces.size(), maxHistorySize, 0, maxHistoryFileBytes, false);
            }
            try {
                long bytes = Files.isRegularFile(historyFile) ? Files.size(historyFile) : 0;
                return new StorageStatus(diagnosticTraces.size(), maxHistorySize, bytes, maxHistoryFileBytes, true);
            } catch (Exception failure) {
                disablePersistence("inspect trace history", failure);
                return new StorageStatus(diagnosticTraces.size(), maxHistorySize, 0, maxHistoryFileBytes, false);
            }
        }
    }

    public RecentTraceStore.TraceRecord findByTraceId(String traceId) {
        return findByTraceId(traceId, "");
    }

    public RecentTraceStore.TraceRecord findByTraceId(String traceId, String sourceType) {
        if (traceId == null) return null;
        var matches = diagnosticTraces.stream().filter(trace -> traceId.equals(trace.traceId())
                && RecentTraceStore.isEntrySource(trace.sourceType())
                && (sourceType == null || sourceType.isBlank()
                || sourceType.equalsIgnoreCase(trace.sourceType())));
        if (sourceType == null || sourceType.isBlank()) {
            return matches.filter(trace -> "HTTP".equals(trace.sourceType())).findFirst()
                    .orElseGet(() -> diagnosticTraces.stream().filter(trace -> traceId.equals(trace.traceId())
                            && RecentTraceStore.isEntrySource(trace.sourceType())).findFirst().orElse(null));
        }
        return matches.findFirst().orElse(null);
    }

    private void addToIndex(RecentTraceStore.TraceRecord trace) {
        if (trace == null || !RecentTraceStore.isEntrySource(trace.sourceType())
                || !isDiagnosticTrace(trace)) return;
        diagnosticTraces.removeIf(item -> trace.traceId().equals(item.traceId())
                && trace.sourceType().equalsIgnoreCase(item.sourceType())
                && trace.finishedAt().equals(item.finishedAt()));
        diagnosticTraces.addFirst(trace);
        while (diagnosticTraces.size() > maxHistorySize) diagnosticTraces.pollLast();
    }

    private void restoreHistoryLines() throws IOException {
        int maxLineBytes = (int) Math.min(maxHistoryRecordBytes() - 1, Integer.MAX_VALUE - 8L);
        boolean oversizedLineLogged = false;
        try (InputStream input = Files.newInputStream(historyFile)) {
            byte[] inputBuffer = new byte[FILE_BUFFER_BYTES];
            ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(FILE_BUFFER_BYTES, maxLineBytes));
            boolean discardLine = false;
            int read;
            while ((read = input.read(inputBuffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    int value = inputBuffer[index] & 0xff;
                    if (value == '\n') {
                        if (discardLine) {
                            if (!oversizedLineLogged) {
                                log.debug("Skipping oversized Easy Observability trace history record: path={}, "
                                        + "maxRecordBytes={}", historyFile, maxLineBytes);
                                oversizedLineLogged = true;
                            }
                        } else {
                            restoreHistoryLine(line);
                        }
                        line.reset();
                        discardLine = false;
                    } else if (!discardLine) {
                        if (line.size() >= maxLineBytes) {
                            line.reset();
                            discardLine = true;
                        } else {
                            line.write(value);
                        }
                    }
                }
            }
            if (discardLine) {
                if (!oversizedLineLogged) {
                    log.debug("Skipping oversized Easy Observability trace history record: path={}, "
                            + "maxRecordBytes={}", historyFile, maxLineBytes);
                }
            } else if (line.size() > 0) {
                restoreHistoryLine(line);
            }
        }
    }

    private void restoreHistoryLine(ByteArrayOutputStream line) {
        try {
            RecentTraceStore.TraceRecord trace = objectMapper.readValue(
                    line.toByteArray(), RecentTraceStore.TraceRecord.class);
            if (RecentTraceStore.isEntrySource(trace.sourceType()) && isDiagnosticTrace(trace)) addToIndex(trace);
        } catch (Exception invalid) {
            log.debug("Skipping malformed Easy Observability trace record", invalid);
        }
    }

    private long maxHistoryRecordBytes() {
        return Math.min(maxHistoryFileBytes, MAX_HISTORY_RECORD_BYTES);
    }

    private void trimHistoryFile() throws Exception {
        trimHistoryFile(maxHistoryFileBytes / 2);
    }

    private void trimHistoryFile(long byteBudget) throws Exception {
        if (!Files.isRegularFile(historyFile)) return;
        Path temporary = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
        try (FileChannel source = FileChannel.open(historyFile, StandardOpenOption.READ)) {
            Deque<HistoryLine> retained = retainedHistoryLines(source, Math.max(0, byteBudget));
            try (FileChannel target = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer copyBuffer = ByteBuffer.allocate(FILE_BUFFER_BYTES);
                for (HistoryLine line : retained) {
                    copyRange(source, target, line.start(), line.length(), copyBuffer);
                    if (!line.terminated()) writeFully(target, ByteBuffer.wrap(new byte[]{'\n'}));
                }
            }
        }
        // 先写临时文件再原子替换，截断过程中进程退出也不损坏原文件。
        try { Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Deque<HistoryLine> retainedHistoryLines(FileChannel source, long byteBudget) throws IOException {
        HistoryRetention retained = new HistoryRetention(byteBudget);
        if (byteBudget <= 0) return retained.lines;
        long fileSize = source.size();
        if (fileSize == 0) return retained.lines;

        ByteBuffer lastByte = ByteBuffer.allocate(1);
        readFully(source, lastByte, fileSize - 1);
        boolean currentLineTerminated = lastByte.get(0) == '\n';
        long currentLineEnd = fileSize;
        long cursor = currentLineTerminated ? fileSize - 1 : fileSize;
        long configuredScanBytes = maxHistoryFileBytes > Long.MAX_VALUE / 2
                ? Long.MAX_VALUE : maxHistoryFileBytes * 2;
        long maxScanBytes = Math.min(configuredScanBytes, MAX_TRIM_SCAN_BYTES);
        long scanFloor = Math.max(0, fileSize - maxScanBytes);
        if (scanFloor > 0) scanFloor--;

        ByteBuffer scanBuffer = ByteBuffer.allocate(FILE_BUFFER_BYTES);
        while (cursor > scanFloor && !retained.full) {
            long chunkStart = Math.max(scanFloor, cursor - scanBuffer.capacity());
            int chunkBytes = (int) (cursor - chunkStart);
            scanBuffer.clear();
            scanBuffer.limit(chunkBytes);
            readFully(source, scanBuffer, chunkStart);
            for (int index = chunkBytes - 1; index >= 0; index--) {
                if (scanBuffer.get(index) != '\n') continue;
                long lineStart = chunkStart + index + 1;
                retained.offer(new HistoryLine(lineStart, currentLineEnd - lineStart, currentLineTerminated));
                currentLineEnd = lineStart;
                currentLineTerminated = true;
                if (retained.full) break;
            }
            cursor = chunkStart;
        }
        if (!retained.full && cursor == 0 && currentLineEnd > 0) {
            retained.offer(new HistoryLine(0, currentLineEnd, currentLineTerminated));
        }
        return retained.lines;
    }

    private static void readFully(FileChannel source, ByteBuffer buffer, long position) throws IOException {
        long offset = position;
        while (buffer.hasRemaining()) {
            int read = source.read(buffer, offset);
            if (read < 0) throw new EOFException("Trace history changed while it was being scanned");
            if (read == 0) throw new EOFException("Could not scan trace history");
            offset += read;
        }
        buffer.flip();
    }

    private static void copyRange(FileChannel source, FileChannel target, long start, long length,
                                  ByteBuffer buffer) throws IOException {
        long position = start;
        long remaining = length;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = source.read(buffer, position);
            if (read < 0) throw new EOFException("Trace history changed while it was being trimmed");
            if (read == 0) throw new EOFException("Could not copy trace history while trimming");
            buffer.flip();
            writeFully(target, buffer);
            position += read;
            remaining -= read;
        }
    }

    private static void writeFully(FileChannel target, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (target.write(buffer) == 0) throw new EOFException("Could not write trace history");
        }
    }

    private static final class BoundedHistoryRecordBuffer extends OutputStream {
        private final int maxBytes;
        private byte[] bytes;
        private int size;

        private BoundedHistoryRecordBuffer(int maxBytes) {
            this.maxBytes = maxBytes;
            this.bytes = new byte[Math.min(FILE_BUFFER_BYTES, maxBytes)];
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            bytes[size++] = (byte) value;
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, source.length);
            ensureCapacity(length);
            System.arraycopy(source, offset, bytes, size, length);
            size += length;
        }

        private void ensureCapacity(int additionalBytes) throws HistoryRecordTooLargeException {
            if (additionalBytes > maxBytes - size) throw new HistoryRecordTooLargeException();
            int required = size + additionalBytes;
            if (required <= bytes.length) return;
            int grown = Math.max(required, bytes.length + Math.max(1, bytes.length / 2));
            bytes = Arrays.copyOf(bytes, Math.min(maxBytes, grown));
        }

        private byte[] bytes() {
            return bytes;
        }

        private int size() {
            return size;
        }
    }

    private static final class HistoryRecordTooLargeException extends IOException {
        private HistoryRecordTooLargeException() {
            super("Trace history record exceeded its bounded buffer");
        }
    }

    private record HistoryLine(long start, long length, boolean terminated) {
        private long outputBytes() {
            return terminated ? length : length + 1;
        }
    }

    private final class HistoryRetention {
        private final Deque<HistoryLine> lines = new ArrayDeque<>();
        private final long byteBudget;
        private final long maxLineBytes;
        private long retainedBytes;
        private boolean full;

        private HistoryRetention(long byteBudget) {
            this.byteBudget = byteBudget;
            this.maxLineBytes = Math.min(byteBudget, maxHistoryRecordBytes());
        }

        private void offer(HistoryLine line) {
            long lineBytes = line.outputBytes();
            if (lineBytes > maxLineBytes) return;
            if (lines.size() >= maxHistorySize || retainedBytes > byteBudget - lineBytes) {
                full = true;
                return;
            }
            lines.addFirst(line);
            retainedBytes += lineBytes;
            full = lines.size() >= maxHistorySize || retainedBytes >= byteBudget;
        }
    }

    public record StorageStatus(int traceCount, int maxTraceCount, long fileBytes, long maxFileBytes,
                                boolean persistenceEnabled) {
        public StorageStatus(int traceCount, int maxTraceCount, long fileBytes, long maxFileBytes) {
            this(traceCount, maxTraceCount, fileBytes, maxFileBytes, true);
        }
    }
}
