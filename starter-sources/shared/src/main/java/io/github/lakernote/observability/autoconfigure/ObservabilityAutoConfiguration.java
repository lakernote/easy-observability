package io.github.lakernote.observability.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.executor.ExecutorTaskObservation;
import io.github.lakernote.observability.integration.job.JobObservation;
import io.github.lakernote.observability.integration.observation.ObservationTraceHandler;
import io.github.lakernote.observability.integration.observation.ObservationTraceIdResolver;
import io.github.lakernote.observability.metrics.OperationRecorder;
import io.github.lakernote.observability.metrics.history.MetricHistoryStore;
import io.github.lakernote.observability.trace.BoundedHistoryWriter;
import io.github.lakernote.observability.trace.TraceContextTaskDecorator;
import io.github.lakernote.observability.trace.store.IncidentTraceStore;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
        "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration"
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
     * 为 Trace 和慢错历史文件提供有容量上限的写入器。
     */
    @Bean
    @ConditionalOnMissingBean
    BoundedHistoryWriter boundedHistoryWriter() {
        return new BoundedHistoryWriter();
    }

    /**
     * 保存供监控页面查询的近期 Trace 和操作记录。
     */
    @Bean
    @ConditionalOnMissingBean
    RecentTraceStore recentTraces(EasyObservabilityProperties properties) {
        return new RecentTraceStore(properties.getWeb().getTraceLimit(), properties.getWeb().getOperationLimit());
    }

    /**
     * 持久化慢操作和错误 Trace，并按配置限制历史数量与文件容量。
     */
    @Bean
    @ConditionalOnMissingBean
    IncidentTraceStore incidentTraces(ObjectMapper objectMapper, EasyObservabilityProperties properties,
                                      BoundedHistoryWriter writer) {
        EasyObservabilityProperties.Trace trace = properties.getTrace();
        return new IncidentTraceStore(objectMapper, trace.getSlowErrorTraceFile(), trace.getSlowErrorHistoryLimit(),
                trace.getSlowErrorMaxBytes().toBytes(), writer);
    }

    /**
     * 管理本地指标历史文件及其保留天数、总容量和单系列容量限制。
     */
    @Bean
    @ConditionalOnMissingBean
    MetricHistoryStore metricHistoryStore(ObjectMapper objectMapper, EasyObservabilityProperties properties) {
        EasyObservabilityProperties.Metrics.History history = properties.getMetrics().getHistory();
        return new MetricHistoryStore(objectMapper, history.getStorageDir(), history.getRetentionDays(),
                history.getMaxStorageBytes().toBytes(), history.getMaxSeriesBytes().toBytes(), history.isEnabled());
    }

    /**
     * 在 Trace 活跃时保存被观测操作明细。
     */
    @Bean
    @ConditionalOnMissingBean
    OperationRecorder operationRecorder(RecentTraceStore recentTraces, EasyObservabilityProperties properties) {
        return new OperationRecorder(recentTraces, properties);
    }

    /**
     * 将定时任务的 Micrometer Observation 转为本项目可处理的任务观测。
     */
    @Bean
    @ConditionalOnBean(io.micrometer.observation.ObservationRegistry.class)
    @ConditionalOnMissingBean
    JobObservation jobObservation(io.micrometer.observation.ObservationRegistry registry) {
        return new JobObservation(registry);
    }

    /**
     * 为执行器任务提供 Micrometer Observation 支持。
     */
    @Bean
    @ConditionalOnBean(io.micrometer.observation.ObservationRegistry.class)
    @ConditionalOnMissingBean
    ExecutorTaskObservation executorTaskObservation(io.micrometer.observation.ObservationRegistry registry) {
        return new ExecutorTaskObservation(registry);
    }

    /**
     * 在线程切换时传播 Trace 上下文，便于异步任务沿用调用链信息。
     */
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(org.springframework.core.task.TaskDecorator.class)
    TraceContextTaskDecorator traceContextTaskDecorator() {
        return new TraceContextTaskDecorator();
    }

    /**
     * 接收 Spring Observation，将操作记录关联到指标、近期 Trace 和慢错历史。
     */
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(OperationRecorder.class)
    @ConditionalOnMissingBean
    ObservationTraceHandler observationTraceHandler(OperationRecorder recorder, RecentTraceStore history,
                                                    IncidentTraceStore archive,
                                                    EasyObservabilityProperties properties,
                                                    ObjectProvider<ObservationTraceIdResolver> resolvers) {
        return new ObservationTraceHandler(recorder, history, archive, properties, resolvers.orderedStream().toList());
    }

}
