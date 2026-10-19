package com.laker.observability.trace.store;

import com.laker.observability.trace.OperationRecord;
import com.laker.observability.trace.BoundedHistoryWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/** 有界 JSONL 慢错操作历史，保存线程 CPU、分配内存等诊断数据，并支持启动时恢复。 */
public final class IncidentOperationStore {
    private static final Logger log = LoggerFactory.getLogger(IncidentOperationStore.class);
    private final ObjectMapper mapper;
    private final Path file;
    private final int capacity;
    private final long maxBytes;
    private final Consumer<Runnable> writeExecutor;
    private final ConcurrentLinkedDeque<OperationRecord> records = new ConcurrentLinkedDeque<>();
    private final Object writeLock = new Object();

    public IncidentOperationStore(ObjectMapper mapper, String file, int capacity, long maxBytes) {
        this(mapper, file, capacity, maxBytes, Runnable::run);
    }

    public IncidentOperationStore(ObjectMapper mapper, String file, int capacity, long maxBytes,
                           BoundedHistoryWriter writer) {
        this(mapper, file, capacity, maxBytes, writer::execute);
    }

    private IncidentOperationStore(ObjectMapper mapper, String file, int capacity, long maxBytes,
                            Consumer<Runnable> writeExecutor) {
        this.mapper = mapper.copy();
        this.file = Path.of(file).toAbsolutePath().normalize();
        this.capacity = Math.max(100, capacity);
        this.maxBytes = Math.max(1024 * 1024, maxBytes);
        this.writeExecutor = writeExecutor;
    }

    @PostConstruct
    public void restore() {
        // 每行独立解析；损坏记录只记调试日志，不影响其余历史加载。
        synchronized (writeLock) {
            if (!Files.isRegularFile(file)) return;
            try {
                if (Files.size(file) > maxBytes) trimFile();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    try { addInMemory(mapper.readValue(line, OperationRecord.class)); }
                    catch (Exception invalid) { log.debug("跳过损坏的 Easy Observability incident 记录", invalid); }
                }
            } catch (IOException failure) {
                log.warn("无法恢复 Easy Observability slow/error 历史：{}", file, failure);
            }
        }
    }

    public void record(OperationRecord operation) {
        if (operation == null || (!operation.slow() && !operation.error())) return;
        addInMemory(operation);
        // 先更新内存查询结果；Starter 默认再通过有界后台队列落盘，不占用业务调用线程。
        writeExecutor.accept(() -> append(operation));
    }

    private void append(OperationRecord operation) {
        synchronized (writeLock) {
            try {
                Files.createDirectories(file.getParent());
                byte[] line = (mapper.writeValueAsString(operation) + "\n").getBytes(StandardCharsets.UTF_8);
                if (Files.exists(file) && Files.size(file) + line.length > maxBytes) trimFile();
                Files.write(file, line, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception failure) {
                log.warn("写入 Easy Observability slow/error 历史失败", failure);
            }
        }
    }

    public List<OperationRecord> recent(int limit, String kind) {
        int safeLimit = Math.max(1, Math.min(limit, capacity));
        List<OperationRecord> result = new ArrayList<>(Math.min(safeLimit, 256));
        for (OperationRecord record : records) {
            if ("slow".equalsIgnoreCase(kind) && !record.slow()) continue;
            if (("error".equalsIgnoreCase(kind) || "errors".equalsIgnoreCase(kind)) && !record.error()) continue;
            if (result.size() >= safeLimit) break;
            result.add(record);
        }
        return List.copyOf(result);
    }

    private void addInMemory(OperationRecord operation) {
        records.addFirst(operation);
        while (records.size() > capacity) records.pollLast();
    }

    private void trimFile() throws IOException {
        if (!Files.isRegularFile(file)) return;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        long bytes = 0;
        List<String> retained = new ArrayList<>();
        for (int index = lines.size() - 1; index >= 0 && retained.size() < capacity; index--) {
            String line = lines.get(index);
            long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes + lineBytes > maxBytes / 2) break;
            retained.add(line);
            bytes += lineBytes;
        }
        java.util.Collections.reverse(retained);
        // 保留最近记录并通过临时文件替换，避免截断时留下半个 JSONL 文件。
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, retained, StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
