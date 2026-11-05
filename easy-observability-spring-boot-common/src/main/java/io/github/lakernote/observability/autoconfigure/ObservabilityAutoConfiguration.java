package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.observation.internal.ObservationTraceHandler;
import io.github.lakernote.observability.integration.observation.ObservationTraceIdResolver;
import io.github.lakernote.observability.metrics.internal.OperationStats;
import io.github.lakernote.observability.storage.internal.StorageDirectory;
import io.github.lakernote.observability.trace.TraceContextTaskDecorator;
import io.github.lakernote.observability.trace.internal.store.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;

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
    static final String TASK_DECORATOR_BEAN = "traceContextTaskDecorator";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

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
     * 保存慢、错和资源超阈值的入口 Trace：内存索引 + 最近的完整记录 + 按天分段的本地文件。
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    TraceStore traceStore(EasyObservabilityProperties properties, StorageDirectory storage) {
        EasyObservabilityProperties.Trace trace = properties.getTrace();
        if (trace.getMaxStorageSize() == null || trace.getMaxStorageSize().toBytes() <= 0) {
            throw new IllegalArgumentException("easy.observability.trace.max-storage-size must be positive");
        }
        return new TraceStore(storage.traceDirectory(), RuntimeMetricsAutoConfiguration.retentionDays("trace", trace.getRetention()),
                trace.getMaxStorageSize().toBytes(), trace.getMemoryCacheSize());
    }

    /**
     * 在线程池任务边界传播 Trace 上下文，并自动记录任务耗时、慢任务与异常。
     * <p>Spring Boot 会把该 Bean 应用到自动配置的默认线程池和调度器。</p>
     */
    @Bean(TASK_DECORATOR_BEAN)
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    TraceContextTaskDecorator traceContextTaskDecorator() {
        return new TraceContextTaskDecorator();
    }

    @Bean
    static BeanFactoryPostProcessor easyObservabilityTaskDecoratorCandidatePolicy() {
        return beanFactory -> yieldTaskDecoratorToApplication(beanFactory, SpringBootVersion.getVersion());
    }

    /**
     * Spring Boot 4 会把所有 TaskDecorator 组合后应用到默认线程池；Spring Boot 3 只在唯一时应用，
     * 多出一个会让宿主自己的装饰器也失效。因此在 Boot 3 下若宿主已有装饰器，本 Starter 的装饰器
     * 不再参与按类型的默认注入，宿主行为保持不变。
     */
    static void yieldTaskDecoratorToApplication(ConfigurableListableBeanFactory beanFactory, String bootVersion) {
        if (composesTaskDecorators(bootVersion) || !beanFactory.containsBeanDefinition(TASK_DECORATOR_BEAN)) return;
        String[] decorators = beanFactory.getBeanNamesForType(TaskDecorator.class, true, false);
        if (decorators.length < 2) return;
        if (beanFactory.getBeanDefinition(TASK_DECORATOR_BEAN) instanceof AbstractBeanDefinition definition) {
            definition.setDefaultCandidate(false);
            LOG.info("Application defines its own TaskDecorator; Spring Boot's default executor keeps it and "
                    + "TraceContextTaskDecorator is not applied there. Combine them with CompositeTaskDecorator "
                    + "to propagate Trace context.");
        }
    }

    static boolean composesTaskDecorators(String bootVersion) {
        if (bootVersion == null) return false;
        int dot = bootVersion.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? bootVersion : bootVersion.substring(0, dot)) >= 4;
        } catch (NumberFormatException unknown) {
            return false;
        }
    }

    /**
     * 接收 Spring Observation，将框架调用挂入 Trace 树并保存入口级资源数据。
     */
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ObservationTraceHandler observationTraceHandler(TraceStore traceStore,
                                                    EasyObservabilityProperties properties,
                                                    ObjectProvider<ObservationTraceIdResolver> resolvers,
                                                    ObjectProvider<OperationStats> operationStats) {
        return new ObservationTraceHandler(traceStore, properties,
                resolvers.orderedStream().toList(), operationStats.getIfAvailable());
    }
}
