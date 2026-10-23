package io.github.lakernote.observability.autoconfigure;

import io.lettuce.core.RedisClient;
import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** 为 Spring Boot 3 的 Lettuce 客户端接入 Micrometer Trace Observation。 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass({RedisClient.class, MicrometerTracing.class, ClientResourcesBuilderCustomizer.class})
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.trace", name = "redis-observation-enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisLettuceTraceAutoConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ClientResourcesBuilderCustomizer easyObservabilityLettuceTraceCustomizer(
            ObservationRegistry observationRegistry) {
        return builder -> builder.tracing(new MicrometerTracing(observationRegistry, "Redis"));
    }
}
