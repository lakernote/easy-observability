package io.github.lakernote.observability.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.observation.ObservationTraceHandler;
import io.github.lakernote.observability.integration.observation.ObservationTraceIdResolver;
import io.github.lakernote.observability.trace.BoundedHistoryWriter;
import io.github.lakernote.observability.trace.TraceContextTaskDecorator;
import io.github.lakernote.observability.trace.store.DiagnosticTraceStore;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 注册监控共用组件。
 * <p>各 Bean 都允许宿主应用通过同类型 Bean 替换；框架专属接入由独立自动配置按需启用。</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
        "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"
})
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EasyObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    /**
     * 为历史数据读写提供 JSON 序列化器；宿主应用已有 ObjectMapper 时沿用宿主配置。
     */
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper observabilityObjectMapper() {
        // 使用独立实例，避免修改宿主应用的 ObjectMapper 行为。
        return new ObjectMapper().findAndRegisterModules();
    }

    /**
     * 为 Trace 诊断归档提供有容量上限的写入器。
     */
    @Bean
    @ConditionalOnMissingBean
    BoundedHistoryWriter boundedHistoryWriter() {
        return new BoundedHistoryWriter();
    }

    /**
     * 保存供监控页面查询的近期 Trace 树。
     */
    @Bean
    @ConditionalOnMissingBean
    RecentTraceStore recentTraceStore(EasyObservabilityProperties properties) {
        return new RecentTraceStore(properties.getWeb().getTraceLimit());
    }

    /**
     * 持久化慢操作和错误 Trace，并按配置限制历史数量与文件容量。
     */
    @Bean
    @ConditionalOnMissingBean
    DiagnosticTraceStore diagnosticTraceStore(ObjectMapper objectMapper, EasyObservabilityProperties properties,
                                               BoundedHistoryWriter writer) {
        EasyObservabilityProperties.Trace trace = properties.getTrace();
        return new DiagnosticTraceStore(objectMapper, trace.getDiagnosticTraceFile(), trace.getDiagnosticHistoryLimit(),
                trace.getDiagnosticMaxBytes().toBytes(), writer);
    }

    /**
     * 在线程池任务边界传播 Trace 上下文，并自动记录任务耗时、慢任务与异常。
     */
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(org.springframework.core.task.TaskDecorator.class)
    TraceContextTaskDecorator traceContextTaskDecorator(EasyObservabilityProperties properties) {
        return new TraceContextTaskDecorator(properties);
    }

    /**
     * 接收 Spring Observation，将框架调用挂入 Trace 树并保存入口级资源数据。
     */
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    ObservationTraceHandler observationTraceHandler(RecentTraceStore recentTraceStore,
                                                    DiagnosticTraceStore diagnosticTraceStore,
                                                    EasyObservabilityProperties properties,
                                                    ObjectProvider<ObservationTraceIdResolver> resolvers) {
        return new ObservationTraceHandler(recentTraceStore, diagnosticTraceStore, properties,
                resolvers.orderedStream().toList());
    }

}
