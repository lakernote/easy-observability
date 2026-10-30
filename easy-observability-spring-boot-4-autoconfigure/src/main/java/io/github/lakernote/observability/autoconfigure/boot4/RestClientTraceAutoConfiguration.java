package io.github.lakernote.observability.autoconfigure.boot4;

import io.github.lakernote.observability.autoconfigure.ObservabilityAutoConfiguration;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.TraceIdContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/** 为 Spring Boot 管理的 RestClient.Builder 自动传播 Trace ID。 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RestClientTraceAutoConfiguration {

    @Bean
    RestClientCustomizer easyObservabilityTraceIdRestClientCustomizer(EasyObservabilityProperties properties) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String traceId = TraceIdContext.currentTraceId();
            if (TraceIdContext.isValidTraceId(traceId)) {
                request.getHeaders().set(properties.getTrace().getHeaderName(), traceId);
            }
            return execution.execute(request, body);
        });
    }
}
