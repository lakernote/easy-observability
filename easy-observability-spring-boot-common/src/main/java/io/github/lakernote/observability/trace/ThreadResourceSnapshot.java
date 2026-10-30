package io.github.lakernote.observability.trace;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** Point-in-time per-thread CPU-time and cumulative-allocation counters; allocation is not retained heap. */
public record ThreadResourceSnapshot(long threadId, long cpuNanos, long allocatedBytes) {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean HOTSPOT =
            THREADS instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    private static volatile boolean countersInitialized;

    /** Captures the current CPU time and allocated-byte counters for a thread. */
    public static ThreadResourceSnapshot capture(long threadId) {
        enableCounters();
        long cpuNanos = -1;
        long allocatedBytes = -1;
        try {
            if (THREADS.isThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()) {
                cpuNanos = THREADS.getThreadCpuTime(threadId);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (HOTSPOT != null && HOTSPOT.isThreadAllocatedMemorySupported()
                    && HOTSPOT.isThreadAllocatedMemoryEnabled()) {
                allocatedBytes = HOTSPOT.getThreadAllocatedBytes(threadId);
            }
        } catch (RuntimeException ignored) {
        }
        return new ThreadResourceSnapshot(threadId, cpuNanos, allocatedBytes);
    }

    /** Enable supported JVM-wide counters once before collecting per-thread resource values. */
    private static void enableCounters() {
        if (countersInitialized) return;
        synchronized (THREADS) {
            if (countersInitialized) return;
            try {
                if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled()) {
                    THREADS.setThreadCpuTimeEnabled(true);
                }
            } catch (RuntimeException ignored) {
            }
            try {
                if (HOTSPOT != null && HOTSPOT.isThreadAllocatedMemorySupported()
                        && !HOTSPOT.isThreadAllocatedMemoryEnabled()) {
                    HOTSPOT.setThreadAllocatedMemoryEnabled(true);
                }
            } catch (RuntimeException ignored) {
            }
            countersInitialized = true;
        }
    }

    public long cpuTimeMsSince(ThreadResourceSnapshot start) {
        return start.cpuNanos < 0 || cpuNanos < 0 ? -1 : Math.max(0, (cpuNanos - start.cpuNanos) / 1_000_000);
    }

    public long allocatedBytesSince(ThreadResourceSnapshot start) {
        return start.allocatedBytes < 0 || allocatedBytes < 0
                ? -1 : Math.max(0, allocatedBytes - start.allocatedBytes);
    }
}
