package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.kafka.TraceIdRecordInterceptor;
import io.github.lakernote.observability.integration.observation.ObservationTraceIdResolver;
import io.github.lakernote.observability.trace.TraceIdContext;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

import java.nio.charset.StandardCharsets;

/**
 * 注册 Kafka 消费端 Trace 集成。
 *
 * <p>Observation 启动时，解析器从 Kafka 消息头读取 Trace ID，供通用的
 * {@link io.github.lakernote.observability.integration.observation.ObservationTraceHandler}
 * 关联消费 Trace。RecordInterceptor 则在 Listener 执行业务代码期间恢复 Trace/MDC，执行后清理线程上下文。
 * Producer 消息头写入由对应 Boot 版本的 {@code KafkaProducerTraceAutoConfiguration} 负责。</p>
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass(RecordInterceptor.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTraceAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "kafkaObservationTraceIdResolver")
    ObservationTraceIdResolver kafkaObservationTraceIdResolver(EasyObservabilityProperties properties) {
        String headerName = properties.getTrace().getHeaderName();
        return context -> {
            if (!(context instanceof KafkaRecordReceiverContext receiver)) return null;
            Header header = receiver.getRecord().headers().lastHeader(headerName);
            if (header == null || header.value() == null) return null;
            String traceId = new String(header.value(), StandardCharsets.UTF_8);
            return TraceIdContext.isValidTraceId(traceId) ? traceId : null;
        };
    }

    /**
     * 为 Spring Boot 配置的 Kafka Listener Factory 提供消费端拦截器。
     * 应用已有 RecordInterceptor Bean 时自动退让，避免与应用拦截器冲突。
     */
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(RecordInterceptor.class)
    TraceIdRecordInterceptor<Object, Object> easyObservabilityKafkaListenerTraceInterceptor(
            EasyObservabilityProperties properties) {
        return new TraceIdRecordInterceptor<>(properties);
    }
}
