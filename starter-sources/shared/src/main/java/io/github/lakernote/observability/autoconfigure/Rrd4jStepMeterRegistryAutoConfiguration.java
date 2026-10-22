package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.metrics.history.MetricHistoryStore;
import io.github.lakernote.observability.metrics.history.Rrd4jStepMeterRegistry;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Adds RRD4J after Boot's simple registry and before it assembles the composite registry. */
@AutoConfiguration(
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
        },
        beforeName = {
                "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnClass(StepMeterRegistry.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.metrics.history", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class Rrd4jStepMeterRegistryAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(Rrd4jStepMeterRegistry.class)
    Rrd4jStepMeterRegistry rrd4jStepMeterRegistry(MetricHistoryStore store) {
        return new Rrd4jStepMeterRegistry(store);
    }
}
