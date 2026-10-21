package io.github.lakernote.observability.trace.store;

import io.github.lakernote.observability.trace.OperationRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Keeps a small, recent in-memory view of completed traces for the bundled dashboard. */
public class RecentTraceStore {
    private static final int MAX_OPERATIONS_PER_TRACE = 200;
    private final ConcurrentLinkedDeque<TraceRecord> records = new ConcurrentLinkedDeque<>();
    private final AtomicInteger recordCount = new AtomicInteger();
    private final AtomicLong nextId = new AtomicLong();
    private final ConcurrentLinkedDeque<OperationRecord> operations = new ConcurrentLinkedDeque<>();
    private final ConcurrentMap<String, ConcurrentLinkedDeque<OperationRecord>> operationsByTraceId =
            new ConcurrentHashMap<>();
    private final AtomicInteger operationCount = new AtomicInteger();
    private final int capacity;
    private final int operationCapacity;

    public RecentTraceStore(int capacity) {
        this(capacity, Math.max(100, capacity * 8));
    }

    public RecentTraceStore(int capacity, int operationCapacity) {
        this.capacity = Math.max(1, capacity);
        this.operationCapacity = Math.max(1, operationCapacity);
    }

    public void add(String traceId, String name, String method, int status, long durationMs,
                    boolean slow, boolean error, String tree) {
        add(traceId, name, method, "", status, durationMs, slow, error, tree);
    }

    public void add(String traceId, String name, String method, String sourceType, int status, long durationMs,
                    boolean slow, boolean error, String tree) {
        if (!isEntrySource(sourceType)) return;
        records.addFirst(new TraceRecord(nextId.incrementAndGet(), traceId, name, method, status,
                Math.max(0, durationMs), slow, error, tree, Instant.now(), -1, -1, -1, List.of(), sourceType));
        recordCount.incrementAndGet();
        while (recordCount.get() > capacity) {
            if (records.pollLast() == null) break;
            recordCount.decrementAndGet();
        }
    }

    public List<TraceRecord> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, capacity));
        List<TraceRecord> result = new ArrayList<>(safeLimit);
        for (TraceRecord record : records) {
            if (!isEntrySource(record.sourceType())) continue;
            if (result.size() == safeLimit) break;
            OperationRecord root = firstRootOperation(record.traceId(), record.sourceType());
            // 列表只附带根操作，供入口统计按实际指标维度下钻；完整子操作仍按需在详情接口读取。
            result.add(root == null ? record : record.withDetails(root, List.of(root)));
        }
        return List.copyOf(result);
    }

    public TraceRecord find(String traceId) {
        return find(traceId, "");
    }

    public TraceRecord find(String traceId, String sourceType) {
        if (traceId == null || traceId.isBlank()) return null;
        var candidates = records.stream().filter(item -> traceId.equals(item.traceId())
                && isEntrySource(item.sourceType()));
        TraceRecord record = sourceType == null || sourceType.isBlank()
                ? candidates.filter(item -> "HTTP".equals(item.sourceType())).findFirst()
                    .orElseGet(() -> records.stream().filter(item -> traceId.equals(item.traceId())
                            && isEntrySource(item.sourceType())).findFirst().orElse(null))
                : candidates.filter(item -> sourceType.equalsIgnoreCase(item.sourceType())).findFirst().orElse(null);
        return record == null ? null : withOperations(record);
    }

    public void addOperation(OperationRecord operation) {
        if (operation == null) return;
        operations.addFirst(operation);
        operationCount.incrementAndGet();
        if (!operation.traceId().isBlank()) {
            operationsByTraceId.compute(operation.traceId(), (traceId, related) -> {
                ConcurrentLinkedDeque<OperationRecord> indexed = related == null
                        ? new ConcurrentLinkedDeque<>() : related;
                indexed.addFirst(operation);
                // A single request can issue thousands of JDBC observations. Keep its detail index bounded so
                // evicting old operations stays cheap and pathological traces cannot retain every child node.
                while (indexed.size() > MAX_OPERATIONS_PER_TRACE) indexed.pollLast();
                return indexed;
            });
        }
        while (operationCount.get() > operationCapacity) {
            OperationRecord expired = operations.pollLast();
            if (expired == null) break;
            operationCount.decrementAndGet();
            removeIndexedOperation(expired);
        }
    }

    public List<OperationRecord> operationsFor(String traceId, int limit) {
        if (traceId == null || traceId.isBlank()) return List.of();
        TraceRecord trace = records.stream().filter(item -> traceId.equals(item.traceId())
                && isEntrySource(item.sourceType())).findFirst().orElse(null);
        if (trace == null) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 1_000));
        ConcurrentLinkedDeque<OperationRecord> related = operationsByTraceId.get(traceId);
        if (related == null) return List.of();
        List<OperationRecord> result = new ArrayList<>();
        for (OperationRecord operation : related) {
            if (result.size() == safeLimit) break;
            result.add(operation);
        }
        return List.copyOf(result);
    }

    private OperationRecord firstRootOperation(String traceId, String sourceType) {
        ConcurrentLinkedDeque<OperationRecord> related = operationsByTraceId.get(traceId);
        if (related == null) return null;
        return related.stream().filter(operation -> isRootOperation(operation)
                && sourceType.equalsIgnoreCase(operation.type())).findFirst()
                .orElseGet(() -> related.stream().filter(RecentTraceStore::isRootOperation).findFirst().orElse(null));
    }

    private void removeIndexedOperation(OperationRecord operation) {
        String traceId = operation.traceId();
        if (traceId.isBlank()) return;
        operationsByTraceId.computeIfPresent(traceId, (ignored, related) -> {
            related.remove(operation);
            return related.isEmpty() ? null : related;
        });
    }

    private TraceRecord withOperations(TraceRecord record) {
        List<OperationRecord> candidates = operationsFor(record.traceId(), 500);
        OperationRecord root = candidates.stream().filter(operation -> isRootOperation(operation)
                && record.sourceType().equalsIgnoreCase(operation.type())).findFirst()
                .orElseGet(() -> candidates.stream().filter(RecentTraceStore::isRootOperation).findFirst().orElse(null));
        // A propagated Trace ID may have multiple local entry roots (for example, an HTTP request
        // that publishes Kafka and the consumer that processes it). Span IDs are local to each tree,
        // so select operations from the root's execution window instead of merging both trees.
        List<OperationRecord> related = root == null ? candidates : candidates.stream()
                .filter(operation -> belongsToEntryWindow(operation, root)).toList();
        return record.withDetails(root, related);
    }

    private static boolean belongsToEntryWindow(OperationRecord operation, OperationRecord root) {
        if (root.threadId() < 0 || operation.threadId() != root.threadId()) return false;
        if (operation.finishedAt() == null || root.finishedAt() == null) return true;
        Instant startedAt = root.finishedAt().minusMillis(Math.max(0, root.durationMs()) + 5);
        return !operation.finishedAt().isBefore(startedAt) && !operation.finishedAt().isAfter(root.finishedAt());
    }

    private static boolean isRootOperation(OperationRecord operation) {
        return isEntrySource(operation.type());
    }

    /** Trace history contains entry points; dependencies remain visible under the entry's detail. */
    public static boolean isEntrySource(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) return true; // legacy caller without a source tag
        return "HTTP".equals(sourceType) || "KAFKA_CONSUMER".equals(sourceType) || "JOB".equals(sourceType);
    }

    /** One completed request or manually recorded trace. */
    public record TraceRecord(long id, String traceId, String name, String method, int status,
                              long durationMs, boolean slow, boolean error, String tree, Instant finishedAt,
                              long threadId, long cpuTimeMs, long allocatedBytes,
                              List<OperationRecord> operations, String sourceType) {
        public TraceRecord {
            operations = operations == null ? List.of() : List.copyOf(operations);
            sourceType = sourceType == null || sourceType.isBlank()
                    ? inferSourceType(operations) : sourceType;
        }

        public TraceRecord(long id, String traceId, String name, String method, int status,
                           long durationMs, boolean slow, boolean error, String tree, Instant finishedAt) {
            this(id, traceId, name, method, status, durationMs, slow, error, tree, finishedAt,
                    -1, -1, -1, List.of(), "");
        }

        public TraceRecord(long id, String traceId, String name, String method, int status,
                           long durationMs, boolean slow, boolean error, String tree, Instant finishedAt,
                           long threadId, long cpuTimeMs, long allocatedBytes, List<OperationRecord> operations) {
            this(id, traceId, name, method, status, durationMs, slow, error, tree, finishedAt,
                    threadId, cpuTimeMs, allocatedBytes, operations, "");
        }

        private TraceRecord withDetails(OperationRecord root, List<OperationRecord> related) {
            if (root == null) return new TraceRecord(id, traceId, name, method, status, durationMs, slow, error,
                    tree, finishedAt, threadId, cpuTimeMs, allocatedBytes, related, sourceType);
            return new TraceRecord(id, traceId, name, method, status, durationMs, slow, error, tree, finishedAt,
                    root.threadId(), root.cpuTimeMs(), root.allocatedBytes(), related,
                    sourceType.isBlank() ? root.type() : sourceType);
        }

        private static String inferSourceType(List<OperationRecord> operations) {
            return operations.stream().filter(RecentTraceStore::isRootOperation)
                    .map(OperationRecord::type).findFirst().orElse("");
        }
    }
}
