package io.github.lakernote.meerkat.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

/**
 * 直接 {@code new RestTemplate()} 或 {@code RestClient.create()} 声明的 Bean 不经过 Spring Boot 的 Builder，
 * 默认没有 ObservationRegistry。这里补上注册表，使其调用进入 Trace 与调用统计，并由 Observation 写入 Trace ID 请求头。
 * <p>已配置注册表的客户端保持不变。</p>
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass({RestTemplate.class, ObservationRegistry.class})
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "meerkat.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RestTemplateObservationAutoConfiguration {
    @Bean
    static BeanPostProcessor meerkatHttpClientObservationPostProcessor(
            ObjectProvider<ObservationRegistry> registryProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof RestTemplate) && !(bean instanceof RestClient)) return bean;
                ObservationRegistry registry = registryProvider.getIfAvailable();
                return registry == null ? bean : observe(bean, registry);
            }
        };
    }

    static Object observe(Object bean, ObservationRegistry registry) {
        if (bean instanceof RestTemplate template) {
            if (template.getObservationRegistry().isNoop()) template.setObservationRegistry(registry);
            return template;
        }
        if (bean instanceof RestClient client && hasNoopRegistry(client)) {
            return client.mutate().observationRegistry(registry).build();
        }
        return bean;
    }

    private static boolean hasNoopRegistry(RestClient client) {
        Field field = ReflectionUtils.findField(client.getClass(), "observationRegistry", ObservationRegistry.class);
        if (field == null) return false;
        ReflectionUtils.makeAccessible(field);
        Object registry = ReflectionUtils.getField(field, client);
        return registry instanceof ObservationRegistry observationRegistry && observationRegistry.isNoop();
    }
}
