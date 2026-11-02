package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.metrics.Rrd4jMetricsStore;
import io.github.lakernote.observability.metrics.Rrd4jStepMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.rrd4j.core.RrdDb;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Installs the isolated RRD4J registry used for the bounded public metrics catalog. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
}, beforeName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
})
@ConditionalOnClass({MeterRegistry.class, RrdDb.class})
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MetricsHistoryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    Rrd4jMetricsStore rrd4jMetricsStore(EasyObservabilityProperties properties) {
        return new Rrd4jMetricsStore(properties.getMetrics());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    Rrd4jStepMeterRegistry rrd4jStepMeterRegistry(EasyObservabilityProperties properties,
                                                  Rrd4jMetricsStore store) {
        return new Rrd4jStepMeterRegistry(properties.getMetrics(), store);
    }

}
