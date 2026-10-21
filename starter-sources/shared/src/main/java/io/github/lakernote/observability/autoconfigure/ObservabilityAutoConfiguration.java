package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.metrics.BusinessMetrics;
import io.github.lakernote.observability.metrics.MetricCardinalityConfiguration;
import io.github.lakernote.observability.metrics.OperationRecorder;
import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.store.IncidentTraceStore;
import io.github.lakernote.observability.trace.BoundedHistoryWriter;
import io.github.lakernote.observability.trace.TraceContextTaskDecorator;
import io.github.lakernote.observability.metrics.history.MetricHistoryCollector;
import io.github.lakernote.observability.metrics.history.MetricHistoryStore;
import io.github.lakernote.observability.integration.job.JobObservation;
import io.github.lakernote.observability.integration.executor.ExecutorTaskObservation;
import io.github.lakernote.observability.integration.observation.ObservationTraceHandler;
import io.github.lakernote.observability.integration.observation.ObservationTraceIdResolver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.fasterxml.jackson.databind.ObjectMapper;

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
@Import(MetricCardinalityConfiguration.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObjectMapper observabilityObjectMapper() {
        // 使用独立实例，避免修改宿主应用的 ObjectMapper 行为。
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    BusinessMetrics businessMetrics(MeterRegistry meterRegistry, EasyObservabilityProperties properties) {
        return new BusinessMetrics(meterRegistry, properties.getMetrics().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    BoundedHistoryWriter boundedHistoryWriter() {
        return new BoundedHistoryWriter();
    }

    @Bean
    @ConditionalOnMissingBean
    RecentTraceStore recentTraces(EasyObservabilityProperties properties) {
        return new RecentTraceStore(properties.getWeb().getTraceLimit(), properties.getWeb().getOperationLimit());
    }

    @Bean
    @ConditionalOnMissingBean
    IncidentTraceStore incidentTraces(ObjectMapper objectMapper, EasyObservabilityProperties properties,
                              BoundedHistoryWriter writer) {
        EasyObservabilityProperties.Trace trace = properties.getTrace();
        return new IncidentTraceStore(objectMapper, trace.getSlowErrorTraceFile(), trace.getSlowErrorHistoryLimit(),
                trace.getSlowErrorMaxBytes().toBytes(), writer);
    }

    @Bean
    @ConditionalOnMissingBean
    MetricHistoryStore metricHistoryStore(ObjectMapper objectMapper, EasyObservabilityProperties properties) {
        EasyObservabilityProperties.Metrics.History history = properties.getMetrics().getHistory();
        return new MetricHistoryStore(objectMapper, history.getStorageDir(), history.getRetentionDays(),
                history.getMaxStorageBytes().toBytes(), history.getMaxSeriesBytes().toBytes(),
                history.getMaxSeries(), history.isEnabled());
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "easy.observability.metrics.history", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    MetricHistoryCollector metricHistoryCollector(MeterRegistry registry, MetricHistoryStore store,
                                                  EasyObservabilityProperties properties) {
        // 指标历史采集器使用独立低频后台任务，不要求宿主应用开启 Spring Scheduling。
        return new MetricHistoryCollector(registry, store, properties.getMetrics().getHistory().getIntervalMs());
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    OperationRecorder operationRecorder(MeterRegistry meterRegistry, RecentTraceStore recentTraces,
                                        EasyObservabilityProperties properties) {
        return new OperationRecorder(meterRegistry, recentTraces, properties);
    }

    @Bean
    @ConditionalOnBean(io.micrometer.observation.ObservationRegistry.class)
    @ConditionalOnMissingBean
    JobObservation jobObservation(io.micrometer.observation.ObservationRegistry registry) {
        return new JobObservation(registry);
    }

    @Bean
    @ConditionalOnBean(io.micrometer.observation.ObservationRegistry.class)
    @ConditionalOnMissingBean
    ExecutorTaskObservation executorTaskObservation(io.micrometer.observation.ObservationRegistry registry) {
        return new ExecutorTaskObservation(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(org.springframework.core.task.TaskDecorator.class)
    TraceContextTaskDecorator traceContextTaskDecorator() {
        return new TraceContextTaskDecorator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(OperationRecorder.class)
    @ConditionalOnMissingBean
    ObservationTraceHandler observationTraceHandler(OperationRecorder recorder, RecentTraceStore history,
                                                    IncidentTraceStore archive,
                                                    EasyObservabilityProperties properties,
                                                    org.springframework.beans.factory.ObjectProvider<ObservationTraceIdResolver> resolvers) {
        return new ObservationTraceHandler(recorder, history, archive, properties, resolvers.orderedStream().toList());
    }

}
