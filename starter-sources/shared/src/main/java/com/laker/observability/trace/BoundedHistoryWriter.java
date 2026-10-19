package com.laker.observability.trace;

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
    private static final int QUEUE_CAPACITY = 2_048;

    private final AtomicLong droppedTasks = new AtomicLong();
    private final AtomicBoolean dropWarningLogged = new AtomicBoolean();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), task -> {
        Thread thread = new Thread(task, "easy-observability-history-writer");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    public void execute(Runnable write) {
        if (write == null) return;
        try {
            executor.execute(write);
        } catch (RejectedExecutionException saturated) {
            // 队列满时丢弃新任务而不让业务线程反向执行落盘；计数和告警便于发现持久化压力。
            long dropped = droppedTasks.incrementAndGet();
            if (dropWarningLogged.compareAndSet(false, true)) {
                log.warn("Easy Observability 历史写入队列已满，后续记录可能丢弃；queueCapacity={}, dropped={}",
                        QUEUE_CAPACITY, dropped);
            }
        }
    }

    public long droppedTaskCount() {
        return droppedTasks.get();
    }

    @PreDestroy
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                int abandoned = executor.shutdownNow().size();
                if (abandoned > 0) log.warn("Easy Observability 关闭时丢弃了 {} 条待写历史记录", abandoned);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            int abandoned = executor.shutdownNow().size();
            if (abandoned > 0) log.warn("Easy Observability 关闭时丢弃了 {} 条待写历史记录", abandoned);
        }
    }
}
