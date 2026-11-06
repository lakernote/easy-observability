package io.github.lakernote.meerkat.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeMetricsDefaultsTest {
    @Test
    void enablesTheTomcatMBeanRegistryUnlessTheApplicationDecides() {
        StandardEnvironment environment = new StandardEnvironment();
        RuntimeMetricsDefaults.apply(environment);
        RuntimeMetricsDefaults.apply(environment);
        assertThat(environment.getProperty(RuntimeMetricsDefaults.TOMCAT_MBEAN_REGISTRY)).isEqualTo("true");
        assertThat(environment.getPropertySources().stream()
                .filter(source -> source.getName().equals(RuntimeMetricsDefaults.PROPERTY_SOURCE))).hasSize(1);

        StandardEnvironment explicit = environment(Map.of(RuntimeMetricsDefaults.TOMCAT_MBEAN_REGISTRY, "false"));
        RuntimeMetricsDefaults.apply(explicit);
        assertThat(explicit.getProperty(RuntimeMetricsDefaults.TOMCAT_MBEAN_REGISTRY)).isEqualTo("false");
    }

    @Test
    void leavesTheRegistryAloneWhenMetricsAreOff() {
        for (String disabled : new String[] {"meerkat.enabled", "meerkat.metrics.enabled"}) {
            StandardEnvironment environment = environment(Map.of(disabled, "false"));
            RuntimeMetricsDefaults.apply(environment);
            assertThat(environment.getProperty(RuntimeMetricsDefaults.TOMCAT_MBEAN_REGISTRY)).isNull();
        }
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}
