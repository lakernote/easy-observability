package io.github.lakernote.observability.autoconfigure;

import feign.RequestInterceptor;
import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.feign.TraceIdRequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import io.github.lakernote.observability.integration.feign.internal.FeignObservationCapability;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenFeign 在类路径中时，注册用于传递 Trace ID 的拦截器。
 *
 * <p>Spring Cloud OpenFeign 会自动将此拦截器应用到 Spring 管理的
 * {@code @FeignClient} 客户端。直接通过 {@code Feign.builder()} 创建的客户端
 * 不会自动使用 Spring Bean，需要手动注册拦截器，例如：</p>
 *
 * <pre>{@code
 * @Bean
 * InventoryClient inventoryClient(TraceIdRequestInterceptor traceIdInterceptor) {
 *     return Feign.builder()
 *             .requestInterceptor(traceIdInterceptor)
 *             .target(InventoryClient.class, "http://inventory-service");
 * }
 * }</pre>
 *
 * <p>同时注册 {@link FeignObservationCapability}，让 {@code @FeignClient} 调用出现在 Trace 和调用统计中。</p>
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass(RequestInterceptor.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FeignTraceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(TraceIdRequestInterceptor.class)
    TraceIdRequestInterceptor traceIdRequestInterceptor(EasyObservabilityProperties properties) {
        return new TraceIdRequestInterceptor(properties);
    }

    /** Spring Cloud OpenFeign 会把 Capability Bean 应用到所有 {@code @FeignClient}；已引入 feign-micrometer 时由其负责。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.observation.ObservationRegistry")
    @ConditionalOnMissingClass("feign.micrometer.MicrometerObservationCapability")
    @ConditionalOnBean(ObservationRegistry.class)
    static class FeignObservationConfiguration {
        @Bean
        @ConditionalOnMissingBean(FeignObservationCapability.class)
        FeignObservationCapability feignObservationCapability(ObservationRegistry registry) {
            return new FeignObservationCapability(registry);
        }
    }
}
