package io.github.lakernote.observability.trace.store;

import io.github.lakernote.observability.trace.BoundedHistoryWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 持久化已完成的慢/错入口 Trace 树及入口资源摘要，供进程重启后查询。 */
public final class IncidentTraceStore {
    private static final Logger log = LoggerFactory.getLogger(IncidentTraceStore.class);
    private final ObjectMapper mapper;
    private final Path file;
    private final int capacity;
    private final long maxBytes;
    private final Consumer<Runnable> writeExecutor;
    private final ConcurrentLinkedDeque<RecentTraceStore.TraceRecord> records = new ConcurrentLinkedDeque<>();
    private final Object lock = new Object();

    public IncidentTraceStore(ObjectMapper mapper, String file, int capacity, long maxBytes) {
        this(mapper, file, capacity, maxBytes, Runnable::run);
    }

    public IncidentTraceStore(ObjectMapper mapper, String file, int capacity, long maxBytes,
                        BoundedHistoryWriter writer) {
        this(mapper, file, capacity, maxBytes, writer::execute);
    }

    private IncidentTraceStore(ObjectMapper mapper, String file, int capacity, long maxBytes,
                         Consumer<Runnable> writeExecutor) {
        this.mapper = mapper.copy();
        this.file = Path.of(file).toAbsolutePath().normalize();
        this.capacity = Math.max(100, capacity);
        this.maxBytes = Math.max(1024 * 1024, maxBytes);
        this.writeExecutor = writeExecutor;
    }

    @PostConstruct
    public void restore() {
        // JSONL 按行恢复；单条记录损坏时跳过该行，尽量保留其他有效历史。
        synchronized (lock) {
            if (!Files.isRegularFile(file)) return;
            try {
                if (Files.size(file) > maxBytes) trim();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    try {
                        RecentTraceStore.TraceRecord trace = mapper.readValue(line, RecentTraceStore.TraceRecord.class);
                        if (RecentTraceStore.isEntrySource(trace.sourceType())) add(trace);
                    }
                    catch (Exception invalid) { log.debug("跳过损坏的 Easy Observability Trace 记录", invalid); }
                }
            } catch (Exception failure) { log.warn("无法恢复 Easy Observability Trace 历史：{}", file, failure); }
        }
    }

    public void record(RecentTraceStore.TraceRecord trace) {
        if (trace == null || !RecentTraceStore.isEntrySource(trace.sourceType())
                || (!trace.slow() && !trace.error())) return;
        add(trace);
        // Starter 默认通过有界后台队列写文件，避免磁盘 I/O 占用请求线程。
        writeExecutor.accept(() -> append(trace));
    }

    private void append(RecentTraceStore.TraceRecord trace) {
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                byte[] bytes = (mapper.writeValueAsString(trace) + "\n").getBytes(StandardCharsets.UTF_8);
                if (Files.exists(file) && Files.size(file) + bytes.length > maxBytes) trim();
                Files.write(file, bytes, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception failure) { log.warn("写入 Easy Observability Trace 历史失败", failure); }
        }
    }

    public List<RecentTraceStore.TraceRecord> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, capacity));
        List<RecentTraceStore.TraceRecord> result = new ArrayList<>(Math.min(safeLimit, 256));
        for (RecentTraceStore.TraceRecord trace : records) {
            if (!RecentTraceStore.isEntrySource(trace.sourceType())) continue;
            if (result.size() >= safeLimit) break;
            result.add(trace);
        }
        return List.copyOf(result);
    }

    public RecentTraceStore.TraceRecord find(String traceId) {
        return find(traceId, "");
    }

    public RecentTraceStore.TraceRecord find(String traceId, String sourceType) {
        if (traceId == null) return null;
        var matches = records.stream().filter(trace -> traceId.equals(trace.traceId())
                && RecentTraceStore.isEntrySource(trace.sourceType())
                && (sourceType == null || sourceType.isBlank()
                || sourceType.equalsIgnoreCase(trace.sourceType())));
        if (sourceType == null || sourceType.isBlank()) {
            return matches.filter(trace -> "HTTP".equals(trace.sourceType())).findFirst()
                    .orElseGet(() -> records.stream().filter(trace -> traceId.equals(trace.traceId())
                            && RecentTraceStore.isEntrySource(trace.sourceType())).findFirst().orElse(null));
        }
        return matches.findFirst().orElse(null);
    }

    private void add(RecentTraceStore.TraceRecord trace) {
        if (trace == null || !RecentTraceStore.isEntrySource(trace.sourceType())) return;
        records.removeIf(item -> trace.traceId().equals(item.traceId())
                && trace.sourceType().equalsIgnoreCase(item.sourceType())
                && trace.finishedAt().equals(item.finishedAt()));
        records.addFirst(trace);
        while (records.size() > capacity) records.pollLast();
    }

    private void trim() throws Exception {
        if (!Files.isRegularFile(file)) return;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        long bytes = 0;
        List<String> retained = new ArrayList<>();
        for (int index = lines.size() - 1; index >= 0 && retained.size() < capacity; index--) {
            String line = lines.get(index);
            long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes + size > maxBytes / 2) break;
            retained.add(line);
            bytes += size;
        }
        java.util.Collections.reverse(retained);
        // 先写临时文件再原子替换，截断过程中进程退出也不损坏原文件。
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, retained, StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
