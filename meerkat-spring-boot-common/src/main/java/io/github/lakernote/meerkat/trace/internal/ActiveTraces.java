package io.github.lakernote.meerkat.trace.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of entry traces that are still running, read on demand by the live diagnostics page. Registration is a
 * single concurrent-set insert per entry; the set is capped so a leaked root can never grow it without bound.
 */
public final class ActiveTraces {
    static final int MAX_TRACKED = 10_000;
    private static final Set<TraceTree> ACTIVE = ConcurrentHashMap.newKeySet();

    private ActiveTraces() {
    }

    static boolean register(TraceTree trace) {
        if (ACTIVE.size() >= MAX_TRACKED) return false;
        return ACTIVE.add(trace);
    }

    static void unregister(TraceTree trace) {
        ACTIVE.remove(trace);
    }

    /** Returns the running entries, longest-running first. */
    public static List<ActiveTrace> snapshot() {
        List<ActiveTrace> running = new ArrayList<>();
        for (TraceTree trace : ACTIVE) {
            ActiveTrace view = trace.activeView();
            if (view != null) running.add(view);
        }
        running.sort(Comparator.comparingLong(ActiveTrace::elapsedMs).reversed());
        return running;
    }

    /**
     * A running entry. {@code detached} is true once the entry thread has returned while the work continues
     * asynchronously, so the entry thread's stack no longer belongs to this request.
     */
    public record ActiveTrace(String traceId, String source, String name, String tag, long elapsedMs,
                              long threadId, String threadName, boolean detached,
                              ThreadResourceSnapshot resourcesAtStart, int spanCount, Step currentStep) {
    }

    /** The deepest span that has started but not finished, such as a SQL statement or remote call in progress. */
    public record Step(String kind, String name, String tag, long elapsedMs) {
    }
}
