package io.github.lakernote.observability.autoconfigure.boot4;

import io.github.lakernote.observability.autoconfigure.RuntimeMetricsDefaults;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/** Enables Tomcat's MBean registry by default so its thread pool meters reach the runtime metrics. */
public class RuntimeMetricsEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        RuntimeMetricsDefaults.apply(environment);
    }
}
