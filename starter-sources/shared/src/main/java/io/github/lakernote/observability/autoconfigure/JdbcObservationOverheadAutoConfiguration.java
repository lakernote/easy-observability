package io.github.lakernote.observability.autoconfigure;

import net.ttddyy.observation.tracing.DataSourceObservationListener;
import net.ttddyy.observation.tracing.JdbcObservationDocumentation;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.EnumSet;

/** Keeps the automatic JDBC observations useful while avoiding per-ResultSet method recording by default. */
@AutoConfiguration(afterName = "net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration")
@ConditionalOnClass(DataSourceObservationListener.class)
public class JdbcObservationOverheadAutoConfiguration {
    @Bean
    static BeanPostProcessor jdbcObservationOverheadPostProcessor(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSourceObservationListener listener)) return bean;

                // Respect the upstream jdbc.includes override. The starter's lightweight default keeps
                // connection/query/generated-key observations, while FETCH remains opt-in.
                if (!environment.containsProperty("jdbc.includes")) {
                    listener.setSupportedTypes(EnumSet.of(
                            JdbcObservationDocumentation.CONNECTION,
                            JdbcObservationDocumentation.QUERY,
                            JdbcObservationDocumentation.GENERATED_KEYS));
                }
                // ResultSet proxying still supports normal JDBC behavior; this only avoids retaining
                // a per-method argument/result record for every cursor operation.
                if (!environment.containsProperty("jdbc.resultset-operations.enabled")) {
                    listener.setIncludeResultSetOperations(false);
                }
                return bean;
            }
        };
    }
}
