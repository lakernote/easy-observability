package io.github.lakernote.observability.trace.internal;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 使用单线程有界队列写本地历史，避免请求线程和消息监听线程执行磁盘 I/O。 */
public final class BoundedHistoryWriter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BoundedHistoryWriter.class);
    private static final int QUEUE_CAPACITY = 512;

    private final AtomicLong droppedTasks = new AtomicLong();
    private final AtomicBoolean dropWarningLogged = new AtomicBoolean();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), task -> {
        Thread thread = new Thread(task, "easy-observability-history-writer");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    /** @return whether the write was queued; {@code false} when the queue is full or the writer is closed */
    public boolean execute(Runnable write) {
        if (write == null) return false;
        try {
            executor.execute(write);
            return true;
        } catch (RejectedExecutionException saturated) {
            // 队列满时丢弃新任务而不让业务线程反向执行落盘；计数和告警便于发现持久化压力。
            long dropped = droppedTasks.incrementAndGet();
            if (dropWarningLogged.compareAndSet(false, true)) {
                log.warn("Easy Observability history queue is full; subsequent records may be dropped; "
                                + "queueCapacity={}, dropped={}",
                        QUEUE_CAPACITY, dropped);
            }
            return false;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                int abandoned = executor.shutdownNow().size();
                if (abandoned > 0) log.warn("Easy Observability shutdown discarded {} pending history records", abandoned);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            int abandoned = executor.shutdownNow().size();
            if (abandoned > 0) log.warn("Easy Observability shutdown discarded {} pending history records", abandoned);
        }
    }
}
