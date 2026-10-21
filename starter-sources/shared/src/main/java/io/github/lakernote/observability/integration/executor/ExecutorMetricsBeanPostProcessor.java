package io.github.lakernote.observability.integration.executor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** Binds Micrometer's standard pool gauges to Spring executor beans after initialization. */
public final class ExecutorMetricsBeanPostProcessor implements BeanPostProcessor, SmartInitializingSingleton {
    private final ObjectProvider<MeterRegistry> registryProvider;
    private final Map<ExecutorService, String> candidates = Collections.synchronizedMap(new IdentityHashMap<>());

    public ExecutorMetricsBeanPostProcessor(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        try {
            ExecutorService executor = null;
            if (bean instanceof ThreadPoolTaskExecutor taskExecutor) executor = taskExecutor.getThreadPoolExecutor();
            else if (bean instanceof ThreadPoolTaskScheduler taskScheduler) executor = taskScheduler.getScheduledThreadPoolExecutor();
            else if (bean instanceof ExecutorService service) executor = service;
            if (executor != null) candidates.putIfAbsent(executor, beanName);
        } catch (Throwable ignored) {
            // Metrics binding must remain fail-open for custom wrappers and pre-bound Boot executors.
        }
        return bean;
    }

    @Override
    public void afterSingletonsInstantiated() {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) return;
        synchronized (candidates) {
            candidates.forEach((executor, beanName) -> {
                if (registry.find("executor.completed").tag("name", beanName).meter() == null) {
                    try {
                        new ExecutorServiceMetrics(executor, beanName, java.util.List.of()).bindTo(registry);
                    } catch (Throwable ignored) {
                        // Metrics binding must remain fail-open for custom executors and wrappers.
                    }
                }
            });
            candidates.clear();
        }
    }
}
