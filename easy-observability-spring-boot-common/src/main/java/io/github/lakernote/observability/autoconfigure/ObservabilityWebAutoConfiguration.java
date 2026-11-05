package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import io.github.lakernote.observability.web.internal.ObservabilityController;
import io.github.lakernote.observability.web.internal.ObservabilityDashboardController;
import io.github.lakernote.observability.web.internal.LiveDiagnosticsController;
import io.github.lakernote.observability.web.internal.MetricsController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the bundled dashboard and its read-only API in Spring MVC applications. */
@AutoConfiguration(after = {ObservabilityAutoConfiguration.class, RuntimeMetricsAutoConfiguration.class})
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.web", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({ObservabilityController.class, ObservabilityDashboardController.class, MetricsController.class,
        LiveDiagnosticsController.class})
public class ObservabilityWebAutoConfiguration implements WebMvcConfigurer {
    /**
     * Allows every request to view data and keeps clearing disabled. The host application is expected to protect
     * the dashboard paths with its own authentication.
     */
    @Bean
    @ConditionalOnMissingBean
    ObservabilityAccessPolicy observabilityAccessPolicy() {
        return () -> true;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/easy-observability/assets/**")
                .addResourceLocations("classpath:/META-INF/resources/easy-observability/assets/");
    }
}
