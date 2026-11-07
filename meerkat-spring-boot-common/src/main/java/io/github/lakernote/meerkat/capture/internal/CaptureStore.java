package io.github.lakernote.meerkat.capture.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Captured requests, kept in memory only: newest first, bounded by count and age, never written to disk. */
public final class CaptureStore {
    private final ArrayDeque<CaptureRecord> records = new ArrayDeque<>();
    private final int maxRecords;
    private final Duration retention;
    private final Clock clock;

    public CaptureStore(int maxRecords, Duration retention) {
        this(maxRecords, retention, Clock.systemUTC());
    }

    CaptureStore(int maxRecords, Duration retention, Clock clock) {
        this.maxRecords = Math.max(1, maxRecords);
        this.retention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofHours(2) : retention;
        this.clock = clock;
    }

    public synchronized void add(CaptureRecord record) {
        records.addFirst(record);
        while (records.size() > maxRecords) records.removeLast();
        expire();
    }

    /** @param sessionId only records of this session, or all when blank */
    public synchronized List<CaptureRecord.Summary> list(String sessionId) {
        expire();
        List<CaptureRecord.Summary> summaries = new ArrayList<>();
        for (CaptureRecord record : records) {
            if (sessionId == null || sessionId.isBlank() || sessionId.equals(record.sessionId())) {
                summaries.add(record.summary());
            }
        }
        return summaries;
    }

    public synchronized CaptureRecord find(String id) {
        expire();
        for (CaptureRecord record : records) {
            if (record.id().equals(id)) return record;
        }
        return null;
    }

    public synchronized int clear() {
        int count = records.size();
        records.clear();
        return count;
    }

    public synchronized int size() {
        return records.size();
    }

    private void expire() {
        Instant cutoff = clock.instant().minus(retention);
        Iterator<CaptureRecord> oldest = records.descendingIterator();
        while (oldest.hasNext()) {
            CaptureRecord record = oldest.next();
            if (record.finishedAt() == null || !record.finishedAt().isBefore(cutoff)) break;
            oldest.remove();
        }
    }
}
