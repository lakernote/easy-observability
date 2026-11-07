package io.github.lakernote.meerkat.diagnostics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A sampling CPU profiler in plain Java: while a session runs, one daemon thread takes the stacks of all threads at
 * a fixed interval and merges them into a bounded call tree for a flame graph. Only one session runs at a time and
 * only the latest result is kept. Nothing is sampled outside a session.
 */
public final class CpuProfiler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CpuProfiler.class);
    public static final Duration MAX_DURATION = Duration.ofSeconds(60);
    public static final int MIN_INTERVAL_MS = 10;
    public static final int MAX_INTERVAL_MS = 100;
    static final int MAX_NODES = 20_000;
    static final int MAX_DEPTH = 128;
    static final int HOT_METHODS = 50;
    /** Top frames of threads that are RUNNABLE for the JVM while they wait in native code for I/O or a timer. */
    static final Set<String> IDLE_FRAMES = Set.of(
            "sun.nio.ch.EPoll.wait", "sun.nio.ch.KQueue.poll", "sun.nio.ch.WEPoll.wait",
            "sun.nio.ch.WindowsSelectorImpl$SubSelector.poll0", "sun.nio.ch.Net.poll", "sun.nio.ch.Net.accept", "sun.nio.ch.ServerSocketChannelImpl.accept0",
            "sun.nio.ch.SocketDispatcher.read0", "sun.nio.ch.SocketDispatcher.readv0", "sun.nio.ch.NioSocketImpl.accept",
            "sun.nio.ch.EPollArrayWrapper.epollWait", "sun.nio.ch.KQueueArrayWrapper.kevent0", "sun.nio.ch.IOUtil.drain",
            "java.net.SocketInputStream.socketRead0", "java.net.PlainSocketImpl.socketAccept",
            "java.io.FileInputStream.readBytes", "java.lang.ref.Reference.waitForReferencePendingList",
            "java.lang.ProcessHandleImpl.waitForProcessExit0", "java.lang.Thread.sleep", "java.lang.Thread.sleep0",
            "java.lang.Object.wait", "java.lang.Object.wait0", "jdk.internal.misc.Unsafe.park",
            "sun.nio.fs.LinuxWatchService.poll", "sun.nio.fs.MacOSXWatchService$CFRunLoopThread.CFRunLoopRun");
    private final ThreadMXBean threads;
    private final Clock clock;
    private final AtomicLong nextId = new AtomicLong();
    private Session current;

    public CpuProfiler() {
        this(ManagementFactory.getThreadMXBean(), Clock.systemUTC());
    }

    CpuProfiler(ThreadMXBean threads, Clock clock) {
        this.threads = threads;
        this.clock = clock;
    }

    /**
     * Starts sampling, replacing the previous result. Returns {@code null} when a session is already running.
     *
     * @throws IllegalArgumentException when the duration or interval is out of range
     */
    public synchronized SessionView start(Options options, String operator) {
        if (options.duration() == null || options.duration().toMillis() < 1
                || options.duration().compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("Duration must be between 1 and " + MAX_DURATION.toSeconds() + " seconds");
        }
        if (options.intervalMs() < MIN_INTERVAL_MS || options.intervalMs() > MAX_INTERVAL_MS) {
            throw new IllegalArgumentException("Interval must be between " + MIN_INTERVAL_MS + " and "
                    + MAX_INTERVAL_MS + " ms");
        }
        if (current != null && current.running()) return null;
        Session session = new Session(Long.toString(nextId.incrementAndGet()), options, Operators.safe(operator),
                clock.instant());
        current = session;
        Thread sampler = new Thread(() -> sample(session), "meerkat-cpu-profiler");
        sampler.setDaemon(true);
        session.thread = sampler;
        log.info("Meerkat CPU profiling #{} started by {} for {} ms every {} ms", session.id, session.operator,
                options.duration().toMillis(), options.intervalMs());
        sampler.start();
        return session.view(clock.instant());
    }

    /** The running or latest finished session, or {@code null} before the first one. */
    public synchronized SessionView latest() {
        return current == null ? null : current.view(clock.instant());
    }

    /** Asks the session to stop early; its samples so far stay available. False when it is unknown or finished. */
    public synchronized boolean stop(String id) {
        Session session = find(id);
        if (session == null || !session.running()) return false;
        session.stopRequested = true;
        session.thread.interrupt();
        return true;
    }

    /** The call tree so far, or {@code null} for an unknown session. */
    public Profile profile(String id) {
        Session session = find(id);
        if (session == null) return null;
        synchronized (session) {
            return new Profile(session.view(clock.instant()), session.tree.frames(),
                    session.tree.hotMethods(HOT_METHODS), session.tree.nodes(), session.tree.truncatedSamples());
        }
    }

    /** The samples so far in collapsed-stack format, or {@code null} for an unknown session. */
    public String collapsed(String id) {
        Session session = find(id);
        if (session == null) return null;
        synchronized (session) {
            return session.tree.collapsed();
        }
    }

    /** Stops a running session, for application shutdown. */
    @Override
    public synchronized void close() {
        if (current != null && current.running()) stop(current.id);
    }

    private synchronized Session find(String id) {
        return current != null && current.id.equals(id) ? current : null;
    }

    private void sample(Session session) {
        Options options = session.options;
        long startedAt = System.nanoTime();
        long deadline = startedAt + options.duration().toNanos();
        long intervalNanos = options.intervalMs() * 1_000_000L;
        long self = Thread.currentThread().getId();
        String error = null;
        try {
            while (!session.stopRequested) {
                long tickStart = System.nanoTime();
                if (tickStart >= deadline) break;
                ThreadInfo[] infos = threads.dumpAllThreads(false, false, MAX_DEPTH);
                synchronized (session) {
                    for (ThreadInfo info : infos) {
                        if (info == null || info.getThreadId() == self) continue;
                        record(session, info.getThreadName(), info.getThreadState(), info.getStackTrace());
                    }
                    session.ticks++;
                    session.overheadNanos += System.nanoTime() - tickStart;
                }
                long sleep = Math.min(intervalNanos - (System.nanoTime() - tickStart), deadline - System.nanoTime());
                if (sleep > 0) Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
            }
        } catch (InterruptedException interrupted) {
            // Stopped early.
        } catch (RuntimeException failure) {
            error = failure.toString();
            log.warn("Meerkat CPU profiling #{} failed", session.id, failure);
        } finally {
            synchronized (this) {
                synchronized (session) {
                    session.finishedAt = clock.instant();
                    session.state = error != null ? "FAILED" : session.stopRequested ? "STOPPED" : "FINISHED";
                    session.error = error;
                }
            }
            log.info("Meerkat CPU profiling #{} {} after {} samples", session.id, session.state, session.tree.samples());
        }
    }

    /** Adds one thread's stack when it is on CPU, or merely blocked or waiting when the options include those. */
    static void record(Session session, String threadName, Thread.State state, StackTraceElement[] frames) {
        Options options = session.options;
        if (frames.length == 0) return;
        if (state != Thread.State.RUNNABLE && !options.includeBlocked()) return;
        if (options.excludeIdle() && state == Thread.State.RUNNABLE && isIdle(frames[0])) {
            session.idleSamples++;
            return;
        }
        session.tree.add(options.groupByThread() ? threadName : null, frames);
    }

    static boolean isIdle(StackTraceElement top) {
        return IDLE_FRAMES.contains(top.getClassName() + "." + top.getMethodName());
    }

    /**
     * @param includeBlocked also sample threads that are BLOCKED, WAITING or TIMED_WAITING, which shows where time
     *                       is spent waiting rather than on CPU
     * @param excludeIdle    skip RUNNABLE threads parked in native I/O waits such as epoll or socket accept
     * @param groupByThread  put each thread name at the root of its stacks
     */
    public record Options(Duration duration, int intervalMs, boolean includeBlocked, boolean excludeIdle,
                          boolean groupByThread) { }

    /**
     * {@code state} is RUNNING, FINISHED, STOPPED or FAILED; {@code samples} counts thread stacks merged into the
     * tree and {@code ticks} sampling rounds; {@code overheadMs} is the time spent taking stacks.
     */
    public record SessionView(String id, String state, String operator, Instant startedAt, Instant finishedAt,
                              long durationMs, int intervalMs, boolean includeBlocked, boolean excludeIdle,
                              boolean groupByThread, long elapsedMs, long ticks, long samples, long idleSamples,
                              long overheadMs, String error) { }

    /** {@code truncatedSamples} counts samples cut short because the tree reached its node budget. */
    public record Profile(SessionView session, Frame root, List<HotMethod> hotMethods, int nodes,
                          long truncatedSamples) { }

    /** {@code total} counts samples in this frame and its callees, {@code self} those with this frame on top. */
    public record Frame(String name, long total, long self, List<Frame> children) { }

    public record HotMethod(String method, long samples, double percent) { }

    static final class Session {
        private final String id;
        private final Options options;
        private final String operator;
        private final Instant startedAt;
        final CallTree tree = new CallTree(MAX_NODES, MAX_DEPTH);
        private volatile boolean stopRequested;
        private volatile String state = "RUNNING";
        private Thread thread;
        private Instant finishedAt;
        private String error;
        private long ticks;
        long idleSamples;
        private long overheadNanos;

        Session(String id, Options options, String operator, Instant startedAt) {
            this.id = id;
            this.options = options;
            this.operator = operator;
            this.startedAt = startedAt;
        }

        boolean running() {
            return "RUNNING".equals(state);
        }

        synchronized SessionView view(Instant now) {
            Instant end = finishedAt != null ? finishedAt : now;
            return new SessionView(id, state, operator, startedAt, finishedAt, options.duration().toMillis(),
                    options.intervalMs(), options.includeBlocked(), options.excludeIdle(), options.groupByThread(),
                    Math.max(0, Duration.between(startedAt, end).toMillis()), ticks, tree.samples(), idleSamples,
                    overheadNanos / 1_000_000, error);
        }
    }
}
