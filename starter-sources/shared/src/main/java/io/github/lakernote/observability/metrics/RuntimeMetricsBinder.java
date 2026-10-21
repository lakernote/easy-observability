package io.github.lakernote.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** Binds common JVM and CPU metrics when the host has not already registered them. */
public final class RuntimeMetricsBinder implements SmartInitializingSingleton, AutoCloseable {
    private final MeterRegistry registry;
    private JvmGcMetrics gcMetrics;

    public RuntimeMetricsBinder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // A binder registers a family of meters. Checking a single member can miss a partially
        // initialized family and then emit duplicate-registration warnings for its other members.
        if (!hasFamily("jvm.memory.")) new JvmMemoryMetrics().bindTo(registry);
        if (!hasFamily("jvm.gc.")) {
            gcMetrics = new JvmGcMetrics();
            gcMetrics.bindTo(registry);
        }
        if (!hasFamily("jvm.threads.")) new JvmThreadMetrics().bindTo(registry);
        if (!hasFamily("jvm.classes.")) new ClassLoaderMetrics().bindTo(registry);
        if (!hasFamily("system.cpu.") && !hasFamily("process.cpu.")) {
            new ProcessorMetrics().bindTo(registry);
        }
    }

    private boolean hasFamily(String prefix) {
        return registry.getMeters().stream().anyMatch(meter -> meter.getId().getName().startsWith(prefix));
    }

    @Override
    public void close() {
        if (gcMetrics != null) gcMetrics.close();
    }
}
