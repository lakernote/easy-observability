package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.observation.internal.ObservationTraceHandler;
import io.github.lakernote.observability.integration.observation.ObservationTraceIdResolver;
import io.github.lakernote.observability.storage.internal.StorageDirectory;
import io.github.lakernote.observability.trace.internal.BoundedHistoryWriter;
import io.github.lakernote.observability.trace.TraceContextTaskDecorator;
import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStore;
import io.github.lakernote.observability.trace.internal.store.RecentTraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

/**
 * 注册监控共用组件。
 * <p>这里的 Bean 都是内部实现，不支持宿主应用替换；扩展点见公共 API。框架专属接入由独立自动配置按需启用。</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
        "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"
})
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EasyObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    /**
     * 独占本实例的本地存储目录；目录被其他实例占用或不可写时，历史数据只保存在内存中。
     */
    @Bean(destroyMethod = "close")
    StorageDirectory easyObservabilityStorageDirectory(EasyObservabilityProperties properties,
                                                       Environment environment) {
        return StorageDirectory.acquire(storageDirectory(properties.getStorage().getDirectory(),
                environment.getProperty("spring.application.name")));
    }

    static Path storageDirectory(String configured, String applicationName) {
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String name = applicationName == null || applicationName.isBlank() ? "application"
                : applicationName.strip().replaceAll("[^A-Za-z0-9._-]", "_");
        // "." and ".." are valid file-name characters but would escape the per-application directory.
        if (name.chars().allMatch(c -> c == '.')) name = "application";
        return Path.of("data", "easy-observability", name);
    }

    /**
     * 为 Trace 诊断归档提供有容量上限的写入器。
     */
    @Bean
    BoundedHistoryWriter boundedHistoryWriter() {
        return new BoundedHistoryWriter();
    }

    /**
     * 保存供监控页面查询的近期 Trace 树。
     */
    @Bean
    RecentTraceStore recentTraceStore(EasyObservabilityProperties properties) {
        return new RecentTraceStore(properties.getWeb().getTraceLimit());
    }

    /**
     * 持久化慢操作和错误 Trace，并按配置限制历史数量与文件容量。
     */
    @Bean
    DiagnosticTraceStore diagnosticTraceStore(EasyObservabilityProperties properties, StorageDirectory storage,
                                              BoundedHistoryWriter writer) {
        EasyObservabilityProperties.Trace trace = properties.getTrace();
        return new DiagnosticTraceStore(storage.traceFile(), trace.getDiagnosticHistoryLimit(),
                trace.getDiagnosticMaxBytes().toBytes(), writer);
    }

    /**
     * 在线程池任务边界传播 Trace 上下文，并自动记录任务耗时、慢任务与异常。
     */
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(org.springframework.core.task.TaskDecorator.class)
    TraceContextTaskDecorator traceContextTaskDecorator() {
        return new TraceContextTaskDecorator();
    }

    /**
     * 接收 Spring Observation，将框架调用挂入 Trace 树并保存入口级资源数据。
     */
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ObservationTraceHandler observationTraceHandler(RecentTraceStore recentTraceStore,
                                                    DiagnosticTraceStore diagnosticTraceStore,
                                                    EasyObservabilityProperties properties,
                                                    ObjectProvider<ObservationTraceIdResolver> resolvers) {
        return new ObservationTraceHandler(recentTraceStore, diagnosticTraceStore, properties,
                resolvers.orderedStream().toList());
    }
}
