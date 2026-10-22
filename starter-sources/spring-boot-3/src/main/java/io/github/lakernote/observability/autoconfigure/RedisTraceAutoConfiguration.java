package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.redis.RedissonTraceConnectionFactoryPostProcessor;
import io.github.lakernote.observability.trace.TraceOperationRecorder;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** Enables Lettuce's native Micrometer observation support when Redis is available. */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisTraceAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer",
            "io.lettuce.core.tracing.MicrometerTracing",
            "org.springframework.data.redis.connection.RedisConnectionFactory"
    })
    static class LettuceConfiguration {
        @org.springframework.context.annotation.Bean
        @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
                havingValue = "true", matchIfMissing = true)
        org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer redisTracingCustomizer(
                org.springframework.beans.factory.ObjectProvider<ObservationRegistry> observationRegistryProvider,
                EasyObservabilityProperties properties) {
            return builder -> {
                ObservationRegistry registry = observationRegistryProvider.getIfAvailable();
                if (registry != null) {
                    builder.tracing(new io.lettuce.core.tracing.MicrometerTracing(registry,
                            properties.getRedis().getServiceName(), false));
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "org.redisson.spring.data.connection.RedissonConnectionFactory",
            "org.springframework.data.redis.connection.RedisConnectionFactory"
    })
    static class RedissonConfiguration {
        @org.springframework.context.annotation.Bean
        @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
                havingValue = "true", matchIfMissing = true)
        static RedissonTraceConnectionFactoryPostProcessor redissonTraceConnectionFactoryPostProcessor(
                org.springframework.beans.factory.ObjectProvider<TraceOperationRecorder> recorderProvider,
                org.springframework.beans.factory.ObjectProvider<EasyObservabilityProperties> propertiesProvider) {
            return new RedissonTraceConnectionFactoryPostProcessor(recorderProvider, propertiesProvider);
        }
    }
}
