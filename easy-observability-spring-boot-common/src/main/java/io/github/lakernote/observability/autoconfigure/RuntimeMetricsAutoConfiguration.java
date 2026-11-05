package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.metrics.internal.MetricsArchive;
import io.github.lakernote.observability.metrics.internal.OperationStats;
import io.github.lakernote.observability.metrics.internal.ResourceHistory;
import io.github.lakernote.observability.metrics.internal.ResourceMetricsRegistry;
import io.github.lakernote.observability.storage.internal.StorageDirectory;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 运行指标：调用统计（来自 Trace 的采集点）与资源指标（来自 Spring Boot 已绑定的 Micrometer 指标），按天保存在存储目录。
 * <p>资源指标通过一个只接收固定指标的 {@link ResourceMetricsRegistry} 获取，因此排在 Spring Boot 创建默认注册表之后、
 * 组合注册表之前，宿主原有的指标导出不受影响。</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
}, beforeName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
})
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.metrics", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class RuntimeMetricsAutoConfiguration {
    static final int MAX_RETENTION_DAYS = 365;

    @Bean
    MetricsArchive easyObservabilityMetricsArchive(EasyObservabilityProperties properties, StorageDirectory storage) {
        EasyObservabilityProperties.Metrics metrics = properties.getMetrics();
        if (metrics.getMaxStorageSize() == null || metrics.getMaxStorageSize().toBytes() <= 0) {
            throw new IllegalArgumentException("easy.observability.metrics.max-storage-size must be positive");
        }
        return new MetricsArchive(storage.metricsDirectory(), metrics.getMaxStorageSize().toBytes(),
                retentionDays(metrics.getRetention()), ZoneId.systemDefault());
    }

    static int retentionDays(Duration retention) {
        if (retention == null || retention.toDays() < 1 || retention.toDays() > MAX_RETENTION_DAYS
                || !retention.equals(Duration.ofDays(retention.toDays()))) {
            throw new IllegalArgumentException("easy.observability.metrics.retention must be whole days between 1d and "
                    + MAX_RETENTION_DAYS + "d");
        }
        return (int) retention.toDays();
    }

    /** 调用统计依赖 Trace 采集点；关闭 Trace 时仍注册，页面据此提示开启。 */
    @Bean(initMethod = "start", destroyMethod = "close")
    OperationStats easyObservabilityOperationStats(EasyObservabilityProperties properties, MetricsArchive archive) {
        OperationStats stats = new OperationStats(properties.getMetrics().getMaxOperations(), archive);
        stats.setCollecting(properties.getTrace().isEnabled());
        return stats;
    }

    @Bean
    ResourceHistory easyObservabilityResourceHistory(MetricsArchive archive) {
        return new ResourceHistory(archive);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StepMeterRegistry.class)
    static class ResourceRegistryConfiguration {
        @Bean(initMethod = "startPublishing", destroyMethod = "close")
        ResourceMetricsRegistry easyObservabilityResourceMetricsRegistry(EasyObservabilityProperties properties,
                                                                         ResourceHistory history) {
            return new ResourceMetricsRegistry(history, properties.getMetrics().getInclude());
        }
    }
}
