package io.github.lakernote.observability.integration.job;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.observation.ObservationTraceHandler;
import io.github.lakernote.observability.metrics.OperationRecorder;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobObservationTest {
    @Test
    void recordsCustomScheduledWorkThroughTheObservationExtension() {
        ObservationRegistry registry = ObservationRegistry.create();
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        RecentTraceStore history = new RecentTraceStore(10);
        OperationRecorder recorder = new OperationRecorder(new SimpleMeterRegistry(), history, properties);
        registry.observationConfig().observationHandler(new ObservationTraceHandler(recorder, history, properties));

        new JobObservation(registry).observe("nightly-reconciliation", () -> { });

        var trace = history.recent(10).get(0);
        assertThat(history.find(trace.traceId(), "JOB").operations()).singleElement().satisfies(operation -> {
            assertThat(operation.type()).isEqualTo("JOB");
            assertThat(operation.name()).isEqualTo("nightly-reconciliation");
        });
    }
}
