package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.web.internal.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Installs trace ID propagation separately from the optional dashboard.
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "meerkat.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TraceIdAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DispatcherServlet.class)
    static class ServletConfiguration {
        @Bean
        FilterRegistrationBean<TraceIdFilter>
        observabilityTraceIdFilter(MeerkatProperties properties) {
            FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>();
            registration.setName("meerkatTraceIdFilter");
            registration.setFilter(new TraceIdFilter(properties));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setAsyncSupported(true);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }
}
