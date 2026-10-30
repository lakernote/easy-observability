package io.github.lakernote.observability.autoconfigure.boot4;

import io.github.lakernote.observability.autoconfigure.ObservabilityAutoConfiguration;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.redis.RedissonTraceConnectionFactoryPostProcessor;
import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Enables Lettuce's native Micrometer observation support when Redis is available.
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisTraceAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer",
            "io.lettuce.core.tracing.MicrometerTracing",
            "org.springframework.data.redis.connection.RedisConnectionFactory"
    })
    static class LettuceConfiguration {
        @org.springframework.context.annotation.Bean
        @ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled",
                havingValue = "true", matchIfMissing = true)
        ClientResourcesBuilderCustomizer redisTracingCustomizer(
                ObjectProvider<ObservationRegistry> observationRegistryProvider,
                Environment environment) {
            return builder -> {
                ObservationRegistry registry = observationRegistryProvider.getIfAvailable();
                if (registry != null) {
                    builder.tracing(new MicrometerTracing(registry,
                            environment.getProperty("spring.application.name", "easy-observability"), false));
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
                org.springframework.beans.factory.ObjectProvider<EasyObservabilityProperties> propertiesProvider) {
            return new RedissonTraceConnectionFactoryPostProcessor(propertiesProvider);
        }
    }
}
