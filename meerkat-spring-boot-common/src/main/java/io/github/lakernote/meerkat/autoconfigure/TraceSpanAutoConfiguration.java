package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.internal.TraceSpanAspect;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the opt-in annotation aspect only when Spring AOP is present.
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TraceSpanAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Aspect.class)
    @ConditionalOnProperty(prefix = "meerkat.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class AspectConfiguration {
        @Bean
        TraceSpanAspect traceSpanAspect(MeerkatProperties properties) {
            return new TraceSpanAspect(properties);
        }
    }
}
