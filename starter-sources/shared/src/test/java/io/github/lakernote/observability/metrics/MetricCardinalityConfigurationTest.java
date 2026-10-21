package io.github.lakernote.observability.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricCardinalityConfigurationTest {

    private final MetricCardinalityConfiguration configuration = new MetricCardinalityConfiguration();

    @Test
    void retainsSqlMapperDimensionLimitInsideTheCombinedGuard() {
        SimpleMeterRegistry registry = registryWithSeriesLimit();

        for (int i = 0; i < 525; i++) {
            Timer.builder("easy.sql.queries")
                    .tags("mapper", "Mapper" + i, "command", "SELECT", "result", "success")
                    .register(registry);
        }

        assertThat(registry.find("easy.sql.queries").timers()).hasSize(500);
    }

    @Test
    void boundsOperationNamesUsedByRequestStatistics() {
        SimpleMeterRegistry registry = registryWithSeriesLimit();

        for (int i = 0; i < 125; i++) {
            Timer.builder("easy.operations.duration")
                    .tags("type", "HTTP", "source", "HTTP", "operation", "GET /route-" + i,
                            "outcome", "success")
                    .register(registry);
        }

        assertThat(registry.find("easy.operations.duration").timers()).hasSize(100);
    }

    @Test
    void reservesSqlSeriesCapacityForEveryOverflowCommandAndOutcome() {
        SimpleMeterRegistry registry = registryWithSeriesLimit();

        for (int mapper = 0; mapper < 494; mapper++) {
            for (String outcome : new String[]{"success", "failure"}) {
                Timer.builder("easy.sql.queries")
                        .tags("mapper", "Mapper" + mapper, "command", "SELECT", "result", outcome)
                        .register(registry);
            }
        }
        for (String command : new String[]{"UNKNOWN", "INSERT", "UPDATE", "DELETE", "SELECT", "FLUSH"}) {
            for (String outcome : new String[]{"success", "failure"}) {
                Timer.builder("easy.sql.queries")
                        .tags("mapper", "unknown", "command", command, "result", outcome)
                        .register(registry);
            }
        }

        assertThat(registry.find("easy.sql.queries").timers()).hasSize(1_000);
        assertThat(registry.find("easy.sql.queries").tag("mapper", "unknown").timers()).hasSize(12);
    }

    @Test
    void leavesUnrelatedMetricsUntouched() {
        SimpleMeterRegistry registry = registryWithSeriesLimit();

        Counter counter = Counter.builder("unrelated.metric").register(registry);
        counter.increment();

        assertThat(registry.find("unrelated.metric").counter().count()).isEqualTo(1);
    }

    private SimpleMeterRegistry registryWithSeriesLimit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(configuration.metricSeriesCardinalityLimit());
        return registry;
    }
}
