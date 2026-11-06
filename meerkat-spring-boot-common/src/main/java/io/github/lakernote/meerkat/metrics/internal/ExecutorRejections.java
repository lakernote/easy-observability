package io.github.lakernote.meerkat.metrics.internal;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts tasks rejected by each {@link ThreadPoolTaskExecutor} bean, which Micrometer has no meter for. The bean's
 * rejection handler is wrapped after initialization: it counts, then runs the configured policy unchanged, so
 * {@code AbortPolicy} still throws and {@code CallerRunsPolicy} still runs the task. Executors are named by bean
 * name, like Spring Boot's {@code executor.*} meters. Rejections after shutdown are not counted.
 */
public final class ExecutorRejections implements BeanPostProcessor {
    static final String METER = "meerkat.executor.rejected";
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    private volatile MeterRegistry registry;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ThreadPoolTaskExecutor executor) {
            try {
                watch(beanName, executor.getThreadPoolExecutor());
            } catch (IllegalStateException notInitialized) {
                // Not started yet; Spring Boot does not bind its executor meters either.
            }
        }
        return bean;
    }

    void watch(String name, ThreadPoolExecutor pool) {
        RejectedExecutionHandler handler = pool.getRejectedExecutionHandler();
        if (handler instanceof Counting) return;
        LongAdder count = counts.computeIfAbsent(name, ignored -> new LongAdder());
        pool.setRejectedExecutionHandler(new Counting(count, handler));
        MeterRegistry bound = registry;
        if (bound != null) register(bound, name, count);
    }

    /**
     * Registers a counter for every executor watched so far and for those watched later, so each counter exists
     * before its first rejection is reported.
     */
    void bindTo(MeterRegistry registry) {
        this.registry = registry;
        counts.forEach((name, count) -> register(registry, name, count));
    }

    private static void register(MeterRegistry registry, String name, LongAdder count) {
        FunctionCounter.builder(METER, count, LongAdder::sum).tag("name", name).register(registry);
    }

    private record Counting(LongAdder count, RejectedExecutionHandler delegate) implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) count.increment();
            delegate.rejectedExecution(task, executor);
        }
    }
}
