package io.github.lakernote.meerkat.example;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.TraceBlock;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Live diagnostics: each endpoint keeps something going for a while so it can be watched on the 实时诊断 page.
 * Open the endpoint in one tab and the live page in another. Durations are capped so nothing runs forever.
 */
@RestController
@RequestMapping("/api/demo/live")
public class LiveDemoController {
    private static final int MAX_SECONDS = 120;
    private static final int LOCK_WAITERS = 3;
    private final DemoDatabaseService database;
    private final RestClient httpBingo;
    private final AtomicBoolean deadlockRunning = new AtomicBoolean();

    public LiveDemoController(DemoDatabaseService database, RestClient httpBingo) {
        this.database = database;
        this.httpBingo = httpBingo;
    }

    /** A request stuck in a business step: 进行中请求 shows the step "ReportService.export" and a TIMED_WAITING stack. */
    @GetMapping("/slow-step")
    public Map<String, Object> slowStep(@RequestParam(defaultValue = "30") int seconds) {
        int duration = DemoSupport.seconds(seconds, MAX_SECONDS);
        TraceBlock.run("ReportService.export", SpanKind.CODE, "导出年度报表",
                () -> DemoSupport.pause(TimeUnit.SECONDS.toMillis(duration)));
        return done(duration);
    }

    /** A request stuck in SQL: the current step is the SELECT and the stack is inside the JDBC driver. */
    @GetMapping("/slow-sql")
    public Map<String, Object> slowSql(@RequestParam(defaultValue = "30") int seconds) {
        int duration = DemoSupport.seconds(seconds, MAX_SECONDS);
        database.slowQuery(TimeUnit.SECONDS.toMillis(duration));
        return done(duration);
    }

    /** A request waiting on a remote service (httpbingo /delay, at most 10 s): the current step is the HTTP call. */
    @GetMapping("/slow-remote")
    public Map<String, Object> slowRemote(@RequestParam(defaultValue = "10") int seconds) {
        int duration = DemoSupport.seconds(seconds, 10);
        httpBingo.get().uri("/delay/{seconds}", duration).retrieve().toBodilessEntity();
        return done(duration);
    }

    /** A request burning a full core: its CPU keeps growing, and its thread tops the CPU ranking near 100%. */
    @GetMapping("/cpu")
    public Map<String, Object> cpu(@RequestParam(defaultValue = "30") int seconds) {
        int duration = DemoSupport.seconds(seconds, MAX_SECONDS);
        long work = DemoSupport.burnCpu(TimeUnit.SECONDS.toMillis(duration));
        return Map.of("traceId", TraceIdContext.currentTraceId(), "seconds", duration, "cpuWork", work);
    }

    /**
     * The request holds a lock while it works, and three background threads queue for it. The threads tab shows them
     * BLOCKED on {@code InventoryLock} owned by the request thread; "合并相同堆栈" folds them into one row.
     */
    @GetMapping("/lock")
    public Map<String, Object> lock(@RequestParam(defaultValue = "30") int seconds) {
        int duration = DemoSupport.seconds(seconds, MAX_SECONDS);
        InventoryLock lock = new InventoryLock();
        synchronized (lock) {
            for (int i = 1; i <= LOCK_WAITERS; i++) {
                Thread waiter = new Thread(() -> {
                    synchronized (lock) {
                        lock.reserved++;
                    }
                }, "demo-lock-waiter-" + i);
                waiter.setDaemon(true);
                waiter.start();
            }
            DemoSupport.pause(TimeUnit.SECONDS.toMillis(duration));
        }
        return done(duration);
    }

    /**
     * Starts two threads that deadlock on each other and returns at once. The threads tab reports the deadlock;
     * the threads are interrupted and end after {@code seconds}.
     */
    @GetMapping("/deadlock")
    public Map<String, Object> deadlock(@RequestParam(defaultValue = "60") int seconds) {
        int duration = DemoSupport.seconds(seconds, MAX_SECONDS);
        if (!deadlockRunning.compareAndSet(false, true)) {
            return Map.of("status", "already running", "hint", "打开实时诊断 → 线程，查看死锁");
        }
        ReentrantLock accounts = new ReentrantLock();
        ReentrantLock orders = new ReentrantLock();
        Thread first = deadlockThread("demo-deadlock-accounts", accounts, orders);
        Thread second = deadlockThread("demo-deadlock-orders", orders, accounts);
        first.start();
        second.start();
        CompletableFuture.runAsync(() -> {
            first.interrupt();
            second.interrupt();
            deadlockRunning.set(false);
        }, CompletableFuture.delayedExecutor(duration, TimeUnit.SECONDS));
        return Map.of("status", "started", "threads", "demo-deadlock-accounts, demo-deadlock-orders",
                "releasedAfterSeconds", duration, "hint", "打开实时诊断 → 线程，查看死锁");
    }

    /**
     * An asynchronous request: the controller returns a future and the request thread is released. 进行中请求 marks it
     * 异步 and shows no stack, because no thread is working on it.
     */
    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> async(@RequestParam(defaultValue = "30") int seconds) {
        int duration = DemoSupport.seconds(seconds, MAX_SECONDS);
        String traceId = TraceIdContext.currentTraceId();
        return CompletableFuture.supplyAsync(() -> Map.of("traceId", traceId, "seconds", duration),
                CompletableFuture.delayedExecutor(duration, TimeUnit.SECONDS));
    }

    private static Thread deadlockThread(String name, ReentrantLock own, ReentrantLock other) {
        Thread thread = new Thread(() -> {
            try {
                own.lockInterruptibly();
                try {
                    DemoSupport.pause(200);
                    other.lockInterruptibly();
                    other.unlock();
                } finally {
                    own.unlock();
                }
            } catch (InterruptedException | IllegalStateException released) {
                Thread.currentThread().interrupt();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static Map<String, Object> done(int seconds) {
        return Map.of("traceId", TraceIdContext.currentTraceId(), "seconds", seconds);
    }

    /** A named lock type so the live page shows {@code InventoryLock} rather than {@code Object}. */
    private static final class InventoryLock {
        private int reserved;
    }
}
