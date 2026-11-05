package io.github.lakernote.observability.autoconfigure;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Defaults that runtime metrics need from Spring Boot. They are added with the lowest precedence, so any value the
 * application sets still wins.
 */
public final class RuntimeMetricsDefaults {
    static final String PROPERTY_SOURCE = "easyObservabilityDefaults";
    /** Spring Boot binds Tomcat's thread pool meters only when Tomcat registers its MBeans. */
    static final String TOMCAT_MBEAN_REGISTRY = "server.tomcat.mbeanregistry.enabled";

    private RuntimeMetricsDefaults() {
    }

    public static void apply(ConfigurableEnvironment environment) {
        if (!environment.getProperty("easy.observability.enabled", Boolean.class, true)
                || !environment.getProperty("easy.observability.metrics.enabled", Boolean.class, true)
                || environment.getPropertySources().contains(PROPERTY_SOURCE)) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE,
                Map.of(TOMCAT_MBEAN_REGISTRY, "true")));
    }
}
