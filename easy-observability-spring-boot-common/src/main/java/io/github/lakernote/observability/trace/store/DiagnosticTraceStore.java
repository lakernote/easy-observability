package io.github.lakernote.observability.trace.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lakernote.observability.trace.BoundedHistoryWriter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/** Persists completed diagnostic traces so they remain available after a restart. */
public final class DiagnosticTraceStore {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticTraceStore.class);
    private final ObjectMapper objectMapper;
    private final Path historyFile;
    private final int maxHistorySize;
    private final long maxHistoryFileBytes;
    private final Consumer<Runnable> historyWriteExecutor;
    private final ConcurrentLinkedDeque<RecentTraceStore.TraceRecord> diagnosticTraces = new ConcurrentLinkedDeque<>();
    private final Object storageLock = new Object();
    private long clearGeneration;

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
            if (!Files.isRegularFile(historyFile)) return;
            try {
                if (Files.size(historyFile) > maxHistoryFileBytes) trimHistoryFile();
                for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
                    try {
                        RecentTraceStore.TraceRecord trace = objectMapper.readValue(line, RecentTraceStore.TraceRecord.class);
                        if (RecentTraceStore.isEntrySource(trace.sourceType())
                                && isDiagnosticTrace(trace)) addToIndex(trace);
                    }
                    catch (Exception invalid) { log.debug("跳过损坏的 Easy Observability Trace 记录", invalid); }
                }
            } catch (Exception failure) { log.warn("无法恢复 Easy Observability 诊断 Trace 历史：{}", historyFile, failure); }
        }
    }

    public void recordDiagnosticTrace(RecentTraceStore.TraceRecord trace) {
        if (trace == null || !RecentTraceStore.isEntrySource(trace.sourceType())
                || !isDiagnosticTrace(trace)) return;
        long writeGeneration;
        synchronized (storageLock) {
            addToIndex(trace);
            writeGeneration = clearGeneration;
        }
        // Starter 默认通过有界后台队列写文件，避免磁盘 I/O 占用请求线程。
        historyWriteExecutor.accept(() -> appendToFile(trace, writeGeneration));
    }

    private static boolean isDiagnosticTrace(RecentTraceStore.TraceRecord trace) {
        return trace.slow() || trace.error() || trace.highCpu() || trace.highAllocation();
    }

    private void appendToFile(RecentTraceStore.TraceRecord trace, long writeGeneration) {
        synchronized (storageLock) {
            if (writeGeneration != clearGeneration) return;
            try {
                Files.createDirectories(historyFile.getParent());
                byte[] bytes = (objectMapper.writeValueAsString(trace) + "\n").getBytes(StandardCharsets.UTF_8);
                if (Files.exists(historyFile) && Files.size(historyFile) + bytes.length > maxHistoryFileBytes) {
                    trimHistoryFile();
                }
                Files.write(historyFile, bytes, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception failure) { log.warn("写入 Easy Observability 诊断 Trace 历史失败", failure); }
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
        long bytes = 0;
        try {
            if (Files.isRegularFile(historyFile)) bytes = Files.size(historyFile);
        } catch (Exception ignored) {
        }
        return new StorageStatus(diagnosticTraces.size(), maxHistorySize, bytes, maxHistoryFileBytes);
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

    private void trimHistoryFile() throws Exception {
        if (!Files.isRegularFile(historyFile)) return;
        List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
        long bytes = 0;
        List<String> retained = new ArrayList<>();
        for (int index = lines.size() - 1; index >= 0 && retained.size() < maxHistorySize; index--) {
            String line = lines.get(index);
            long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes + size > maxHistoryFileBytes / 2) break;
            retained.add(line);
            bytes += size;
        }
        java.util.Collections.reverse(retained);
        // 先写临时文件再原子替换，截断过程中进程退出也不损坏原文件。
        Path temporary = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
        Files.write(temporary, retained, StandardCharsets.UTF_8);
        try { Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record StorageStatus(int traceCount, int maxTraceCount, long fileBytes, long maxFileBytes) { }
}
