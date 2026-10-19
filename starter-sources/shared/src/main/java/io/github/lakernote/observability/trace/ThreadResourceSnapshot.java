package io.github.lakernote.observability.trace;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * 读取 JVM 提供的线程级累计计数；只保存数值，不保留业务参数或请求/消息内容。
 */
public record ThreadResourceSnapshot(long threadId, long cpuNanos, long allocatedBytes,
                                     long blockedCount, long blockedTimeMs,
                                     long waitedCount, long waitedTimeMs) {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean HOTSPOT =
            THREADS instanceof com.sun.management.ThreadMXBean bean ? bean : null;

    /**
     * 采集线程 CPU 和分配字节；阻塞/等待信息需要额外查询 ThreadInfo，默认由调用方选择是否启用。
     */
    public static ThreadResourceSnapshot capture(long threadId, boolean enabled, boolean contentionSamplingEnabled) {
        // -1 代表 JVM 不支持、权限未开启或本次无法采样，不能误显示为 0。
        if (!enabled) return new ThreadResourceSnapshot(threadId, -1, -1, -1, -1, -1, -1);
        long cpu = -1;
        long allocated = -1;
        ThreadInfo info = null;
        try {
            if (THREADS.isThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()) {
                cpu = THREADS.getThreadCpuTime(threadId);
            }
            if (HOTSPOT != null && HOTSPOT.isThreadAllocatedMemorySupported()
                    && HOTSPOT.isThreadAllocatedMemoryEnabled()) {
                allocated = HOTSPOT.getThreadAllocatedBytes(threadId);
            }
            if (contentionSamplingEnabled) info = THREADS.getThreadInfo(threadId);
        } catch (RuntimeException ignored) {
        }
        return new ThreadResourceSnapshot(threadId, cpu, allocated,
                info == null ? -1 : info.getBlockedCount(), info == null ? -1 : info.getBlockedTime(),
                info == null ? -1 : info.getWaitedCount(), info == null ? -1 : info.getWaitedTime());
    }

    public long cpuTimeMsSince(ThreadResourceSnapshot start) {
        return start.cpuNanos < 0 || cpuNanos < 0 ? -1 : Math.max(0, (cpuNanos - start.cpuNanos) / 1_000_000);
    }

    public long allocatedBytesSince(ThreadResourceSnapshot start) {
        return start.allocatedBytes < 0 || allocatedBytes < 0 ? -1 : Math.max(0, allocatedBytes - start.allocatedBytes);
    }

    public long blockedCountSince(ThreadResourceSnapshot start) {
        return start.blockedCount < 0 || blockedCount < 0 ? -1 : Math.max(0, blockedCount - start.blockedCount);
    }

    public long blockedTimeMsSince(ThreadResourceSnapshot start) {
        return start.blockedTimeMs < 0 || blockedTimeMs < 0 ? -1 : Math.max(0, blockedTimeMs - start.blockedTimeMs);
    }

    public long waitedCountSince(ThreadResourceSnapshot start) {
        return start.waitedCount < 0 || waitedCount < 0 ? -1 : Math.max(0, waitedCount - start.waitedCount);
    }

    public long waitedTimeMsSince(ThreadResourceSnapshot start) {
        return start.waitedTimeMs < 0 || waitedTimeMs < 0 ? -1 : Math.max(0, waitedTimeMs - start.waitedTimeMs);
    }
}
