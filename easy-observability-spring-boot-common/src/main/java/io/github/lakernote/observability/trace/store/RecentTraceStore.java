package io.github.lakernote.observability.trace.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Keeps a bounded in-memory view of completed traces for the bundled dashboard. */
public class RecentTraceStore {
    private final ConcurrentLinkedDeque<TraceRecord> records = new ConcurrentLinkedDeque<>();
    private final AtomicInteger recordCount = new AtomicInteger();
    private final AtomicLong nextId = new AtomicLong();
    private final int capacity;

    public RecentTraceStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(String traceId, String name, String method, String sourceType, int status,
                    long durationMs, boolean slow, boolean error, String tree, String threadName, long threadId,
                    long cpuTimeMs, long allocatedBytes, String errorType, String errorMessage,
                    String errorStackTrace) {
        if (!isEntrySource(sourceType)) return;
        records.addFirst(new TraceRecord(nextId.incrementAndGet(), traceId, name, method, status,
                Math.max(0, durationMs), slow, error, tree, Instant.now(), threadId, cpuTimeMs, allocatedBytes,
                sourceType, errorType, errorMessage, threadName, errorStackTrace));
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
            result.add(record);
        }
        return List.copyOf(result);
    }

    public int size() {
        return recordCount.get();
    }

    /** Clears the in-memory trace window without resetting the monotonically increasing record ID. */
    public synchronized int clear() {
        int cleared = recordCount.getAndSet(0);
        records.clear();
        return cleared;
    }

    public int capacity() {
        return capacity;
    }

    public TraceRecord find(String traceId, String sourceType) {
        if (traceId == null || traceId.isBlank()) return null;
        var candidates = records.stream().filter(item -> traceId.equals(item.traceId())
                && isEntrySource(item.sourceType()));
        if (sourceType == null || sourceType.isBlank()) {
            return candidates.filter(item -> "HTTP".equals(item.sourceType())).findFirst()
                    .orElseGet(() -> records.stream().filter(item -> traceId.equals(item.traceId())
                            && isEntrySource(item.sourceType())).findFirst().orElse(null));
        }
        return candidates.filter(item -> sourceType.equalsIgnoreCase(item.sourceType())).findFirst().orElse(null);
    }

    /** Trace history contains entry points; dependencies remain in the entry's single tree. */
    public static boolean isEntrySource(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) return false;
        return "HTTP".equals(sourceType) || "KAFKA_CONSUMER".equals(sourceType) || "JOB".equals(sourceType);
    }

    /** One completed request or manually recorded trace. */
    public record TraceRecord(long id, String traceId, String name, String method, int status,
                              long durationMs, boolean slow, boolean error, String tree, Instant finishedAt,
                              long threadId, long cpuTimeMs, long allocatedBytes, String sourceType,
                              String errorType, String errorMessage, String threadName, String errorStackTrace) {
        public TraceRecord {
            sourceType = sourceType == null ? "" : sourceType;
            errorType = errorType == null ? "" : errorType;
            errorMessage = errorMessage == null ? "" : errorMessage;
            threadName = threadName == null ? "" : threadName;
            errorStackTrace = errorStackTrace == null ? "" : errorStackTrace;
        }

    }
}
