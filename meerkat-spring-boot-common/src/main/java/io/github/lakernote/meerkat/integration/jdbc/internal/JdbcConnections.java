package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.trace.TraceIdContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * JDBC connections handed out by traced data sources and not yet closed (returned to the pool), with who took them.
 * <p>Answers "who is holding the pool" during exhaustion and spots connections that outlive the request that took
 * them. Costs one map insert per {@code getConnection} and one remove per {@code close}; the map holds at most the
 * open connections, capped at {@link #MAX_TRACKED}. Free of datasource-proxy types so the live page can read it
 * whether or not JDBC tracing is on the classpath.</p>
 */
public final class JdbcConnections {
    static final int MAX_TRACKED = 10_000;
    private static final Map<Object, Holder> OPEN = new ConcurrentHashMap<>();
    private static final AtomicLong UNTRACKED = new AtomicLong();

    private JdbcConnections() {
    }

    /**
     * @param key    identity of the logical connection, used again by {@link #closed(Object)}
     * @param closed fallback for closes the listener never saw; swept out when listed
     */
    static void opened(Object key, String dataSource, String connectionId, BooleanSupplier closed) {
        if (key == null) return;
        if (OPEN.size() >= MAX_TRACKED) {
            UNTRACKED.incrementAndGet();
            return;
        }
        Thread thread = Thread.currentThread();
        String traceId = TraceIdContext.currentTraceId();
        OPEN.put(key, new Holder(dataSource, connectionId, thread.getId(), thread.getName(),
                traceId == null ? "" : traceId, System.nanoTime(), Instant.now(), closed));
    }

    static void closed(Object key) {
        if (key != null) OPEN.remove(key);
    }

    /** Open connections, longest held first. */
    public static Snapshot snapshot() {
        long now = System.nanoTime();
        List<OpenConnection> items = new ArrayList<>(OPEN.size());
        OPEN.forEach((key, holder) -> {
            if (holder.closed != null && holder.closed.getAsBoolean()) {
                OPEN.remove(key, holder);
                return;
            }
            items.add(new OpenConnection(holder.dataSource, holder.connectionId, holder.openedAt,
                    Math.max(0, (now - holder.openedNanos) / 1_000_000), holder.threadId, holder.threadName,
                    holder.traceId));
        });
        items.sort(Comparator.comparingLong(OpenConnection::heldMs).reversed());
        return new Snapshot(items, UNTRACKED.get());
    }

    static void clear() {
        OPEN.clear();
        UNTRACKED.set(0);
    }

    private record Holder(String dataSource, String connectionId, long threadId, String threadName, String traceId,
                          long openedNanos, Instant openedAt, BooleanSupplier closed) {
    }

    /** @param traceId trace that was current on the thread when the connection was taken, or empty */
    public record OpenConnection(String dataSource, String connectionId, Instant openedAt, long heldMs, long threadId,
                                 String threadName, String traceId) {
    }

    /** @param untracked connections opened while {@link #MAX_TRACKED} were already open, never listed */
    public record Snapshot(List<OpenConnection> items, long untracked) {
    }
}
