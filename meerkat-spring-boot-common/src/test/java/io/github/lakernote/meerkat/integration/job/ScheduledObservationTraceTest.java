package io.github.lakernote.meerkat.integration.job;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.integration.observation.internal.ObservationTraceHandler;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import io.github.lakernote.meerkat.trace.internal.TraceNodeFixtures;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledObservationTraceTest {
    @Test
    void recordsSlowSpringScheduledObservationWithoutAStarterWrapper() {
        ObservationRegistry registry = ObservationRegistry.create();
        MeerkatProperties properties = new MeerkatProperties();
        properties.getTrace().setJobSlowThreshold(java.time.Duration.ofMillis(1));
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties, java.util.List.of()));

        Observation.createNotStarted("tasks.scheduled.execution", registry)
                .contextualName("nightly-reconciliation")
                .observe(() -> {
                    try { Thread.sleep(5); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });

        var trace = history.recent(10).get(0);
        assertThat(trace.name()).isEqualTo("nightly-reconciliation");
        // A slow root-only observation is retained with its diagnostic marker.
        assertThat(trace.slow()).isTrue();
        assertThat(TraceNodeFixtures.names(trace.treeNodes())).contains("nightly-reconciliation");
    }
}
