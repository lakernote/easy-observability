package io.github.lakernote.observability.autoconfigure;

import feign.RequestInterceptor;
import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.feign.TraceIdRequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

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
 * <p>此配置不作用于 {@code RestTemplate}、{@code WebClient} 等其他 HTTP 客户端；
 * 这些客户端需要通过各自的拦截器接入 Trace ID 传递。</p>
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
}
