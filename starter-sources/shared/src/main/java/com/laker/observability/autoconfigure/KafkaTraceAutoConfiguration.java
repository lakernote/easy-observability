package com.laker.observability.autoconfigure;

import com.laker.observability.config.EasyObservabilityProperties;
import com.laker.observability.integration.kafka.TraceIdRecordInterceptor;
import com.laker.observability.integration.observation.ObservationTraceIdResolver;
import com.laker.observability.trace.TraceIdContext;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

/** Installs Kafka trace ID propagation only when Spring Kafka is on the application classpath. */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass(RecordInterceptor.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTraceAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(TraceIdRecordInterceptor.class)
    TraceIdRecordInterceptor traceIdRecordInterceptor(EasyObservabilityProperties properties) {
        return new TraceIdRecordInterceptor(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = "kafkaObservationTraceIdResolver")
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
}
