package io.github.lakernote.observability.integration.job;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.observation.ObservationTraceHandler;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledObservationTraceTest {
    @Test
    void recordsSlowSpringScheduledObservationWithoutAStarterWrapper() {
        ObservationRegistry registry = ObservationRegistry.create();
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        properties.getTrace().setJobSlowThresholdMs(1);
        RecentTraceStore history = new RecentTraceStore(10);
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties));

        Observation.createNotStarted("tasks.scheduled.execution", registry)
                .contextualName("nightly-reconciliation")
                .observe(() -> {
                    try { Thread.sleep(5); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });

        var trace = history.recent(10).get(0);
        assertThat(trace.name()).isEqualTo("nightly-reconciliation");
        // A slow root-only observation is retained with its diagnostic marker.
        assertThat(trace.tree()).contains("nightly-reconciliation", "[SLOW]");
    }
}
