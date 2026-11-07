package io.github.lakernote.meerkat.diagnostics.internal;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class CpuProfilerTest {
    private static CpuProfiler.Options options(Duration duration, boolean includeBlocked, boolean excludeIdle,
                                               boolean groupByThread) {
        return new CpuProfiler.Options(duration, 10, includeBlocked, excludeIdle, groupByThread);
    }

    @Test
    void samplesABusyThreadIntoAFlameGraph() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread busy = new Thread(() -> {
            while (running.get()) spin();
        }, "busy-test-thread");
        busy.setDaemon(true);
        busy.start();
        CpuProfiler profiler = new CpuProfiler();
        try {
            assertThat(profiler.latest()).isNull();
            CpuProfiler.SessionView started =
                    profiler.start(options(Duration.ofMillis(400), false, true, true), "alice@127.0.0.1");
            assertThat(started.state()).isEqualTo("RUNNING");
            assertThat(started.operator()).isEqualTo("alice@127.0.0.1");
            assertThat(profiler.start(options(Duration.ofSeconds(1), false, true, false), "bob")).isNull();
            await().atMost(Duration.ofSeconds(10)).until(() -> !"RUNNING".equals(profiler.latest().state()));

            CpuProfiler.SessionView finished = profiler.latest();
            assertThat(finished.state()).isEqualTo("FINISHED");
            assertThat(finished.ticks()).isPositive();
            assertThat(finished.samples()).isPositive();
            assertThat(finished.finishedAt()).isNotNull();
            CpuProfiler.Profile profile = profiler.profile(started.id());
            assertThat(profile.root().children()).extracting(CpuProfiler.Frame::name).contains("[busy-test-thread]");
            assertThat(profile.hotMethods()).isNotEmpty();
            assertThat(profile.nodes()).isGreaterThan(1);
            assertThat(profiler.collapsed(started.id())).contains("[busy-test-thread];").contains("CpuProfilerTest.spin");
            assertThat(profiler.profile("unknown")).isNull();
            assertThat(profiler.collapsed("unknown")).isNull();
            assertThat(profiler.stop(started.id())).isFalse();
        } finally {
            running.set(false);
            profiler.close();
        }
    }

    @Test
    void stopsEarlyOnRequestAndOnClose() {
        CpuProfiler profiler = new CpuProfiler();
        CpuProfiler.SessionView session = profiler.start(options(Duration.ofSeconds(30), true, false, false), "ops");
        assertThat(profiler.stop("other")).isFalse();
        assertThat(profiler.stop(session.id())).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> "STOPPED".equals(profiler.latest().state()));

        CpuProfiler.SessionView next = profiler.start(options(Duration.ofSeconds(30), false, false, false), null);
        assertThat(next.id()).isNotEqualTo(session.id());
        assertThat(next.operator()).isEqualTo("anonymous");
        assertThat(profiler.profile(session.id())).isNull();
        profiler.close();
        await().atMost(Duration.ofSeconds(5)).until(() -> "STOPPED".equals(profiler.latest().state()));
        profiler.close();
    }

    @Test
    void reportsAFailedSession() {
        ThreadMXBean broken = (ThreadMXBean) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ThreadMXBean.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException("no stacks");
                });
        CpuProfiler profiler = new CpuProfiler(broken, Clock.systemUTC());
        CpuProfiler.SessionView session = profiler.start(options(Duration.ofSeconds(5), false, true, false), "ops");
        await().atMost(Duration.ofSeconds(5)).until(() -> "FAILED".equals(profiler.latest().state()));
        assertThat(profiler.latest().error()).contains("no stacks");
        assertThat(profiler.profile(session.id()).root().total()).isZero();
    }

    @Test
    void validatesOptions() {
        CpuProfiler profiler = new CpuProfiler(ManagementFactory.getThreadMXBean(), Clock.systemUTC());
        assertThatThrownBy(() -> profiler.start(options(null, false, true, false), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profiler.start(options(Duration.ZERO, false, true, false), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profiler.start(options(Duration.ofSeconds(61), false, true, false), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profiler.start(new CpuProfiler.Options(Duration.ofSeconds(1), 9, false, true, false), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profiler.start(new CpuProfiler.Options(Duration.ofSeconds(1), 101, false, true, false), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(profiler.latest()).isNull();
    }

    @Test
    void recordsOnlyRunnableNonIdleStacksByDefault() {
        CpuProfiler.Session session = new CpuProfiler.Session("1", options(Duration.ofSeconds(1), false, true, false),
                "ops", Instant.EPOCH);
        StackTraceElement[] busy = CallTreeTest.stack("app.Main.run", "app.Main.work");
        StackTraceElement[] idle = CallTreeTest.stack("app.Main.run", "sun.nio.ch.EPoll.wait");

        CpuProfiler.record(session, "t", Thread.State.RUNNABLE, busy);
        CpuProfiler.record(session, "t", Thread.State.RUNNABLE, idle);
        CpuProfiler.record(session, "t", Thread.State.BLOCKED, busy);
        CpuProfiler.record(session, "t", Thread.State.RUNNABLE, new StackTraceElement[0]);

        assertThat(session.tree.samples()).isEqualTo(1);
        assertThat(session.idleSamples).isEqualTo(1);
        assertThat(CpuProfiler.isIdle(idle[0])).isTrue();
        assertThat(CpuProfiler.isIdle(busy[0])).isFalse();

        CpuProfiler.Session all = new CpuProfiler.Session("2", options(Duration.ofSeconds(1), true, false, false),
                "ops", Instant.EPOCH);
        CpuProfiler.record(all, "t", Thread.State.RUNNABLE, idle);
        CpuProfiler.record(all, "t", Thread.State.WAITING, busy);
        assertThat(all.tree.samples()).isEqualTo(2);
        assertThat(all.idleSamples).isZero();
    }

    private static void spin() {
        Deque<Double> values = new ArrayDeque<>();
        for (int index = 0; index < 1000; index++) values.push(Math.sqrt(index));
        values.clear();
    }
}
