package io.github.lakernote.meerkat.capture.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Captured requests, kept in memory only: newest first, bounded by count, estimated heap and age, never written to
 * disk. The newest record is always kept.
 */
public final class CaptureStore {
    private record Stored(CaptureRecord record, long bytes) { }

    private final ArrayDeque<Stored> records = new ArrayDeque<>();
    private final int maxRecords;
    private final long maxBytes;
    private final Duration retention;
    private final Clock clock;
    private long bytes;

    public CaptureStore(int maxRecords, Duration retention) {
        this(maxRecords, Long.MAX_VALUE, retention);
    }

    public CaptureStore(int maxRecords, long maxBytes, Duration retention) {
        this(maxRecords, maxBytes, retention, Clock.systemUTC());
    }

    CaptureStore(int maxRecords, Duration retention, Clock clock) {
        this(maxRecords, Long.MAX_VALUE, retention, clock);
    }

    CaptureStore(int maxRecords, long maxBytes, Duration retention, Clock clock) {
        this.maxRecords = Math.max(1, maxRecords);
        this.maxBytes = Math.max(0, maxBytes);
        this.retention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofHours(2) : retention;
        this.clock = clock;
    }

    public synchronized void add(CaptureRecord record) {
        Stored stored = new Stored(record, record.estimatedBytes());
        records.addFirst(stored);
        bytes += stored.bytes;
        while (records.size() > maxRecords || (bytes > maxBytes && records.size() > 1)) {
            bytes -= records.removeLast().bytes;
        }
        expire();
    }

    /** @param sessionId only records of this session, or all when blank */
    public synchronized List<CaptureRecord.Summary> list(String sessionId) {
        expire();
        List<CaptureRecord.Summary> summaries = new ArrayList<>();
        for (Stored stored : records) {
            CaptureRecord record = stored.record;
            if (sessionId == null || sessionId.isBlank() || sessionId.equals(record.sessionId())) {
                summaries.add(record.summary());
            }
        }
        return summaries;
    }

    public synchronized CaptureRecord find(String id) {
        expire();
        for (Stored stored : records) {
            if (stored.record.id().equals(id)) return stored.record;
        }
        return null;
    }

    public synchronized int clear() {
        int count = records.size();
        records.clear();
        bytes = 0;
        return count;
    }

    public synchronized int size() {
        return records.size();
    }

    synchronized long estimatedBytes() {
        return bytes;
    }

    private void expire() {
        Instant cutoff = clock.instant().minus(retention);
        Iterator<Stored> oldest = records.descendingIterator();
        while (oldest.hasNext()) {
            Stored stored = oldest.next();
            if (stored.record.finishedAt() == null || !stored.record.finishedAt().isBefore(cutoff)) break;
            oldest.remove();
            bytes -= stored.bytes;
        }
    }
}
