package io.github.lakernote.observability.integration.job;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.observation.ObservationTraceHandler;
import io.github.lakernote.observability.trace.TraceOperationRecorder;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledObservationTraceTest {
    @Test
    void recordsSpringScheduledObservationWithoutAStarterWrapper() {
        ObservationRegistry registry = ObservationRegistry.create();
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        RecentTraceStore history = new RecentTraceStore(10);
        TraceOperationRecorder recorder = new TraceOperationRecorder(history, properties);
        registry.observationConfig().observationHandler(new ObservationTraceHandler(recorder, history, properties));

        Observation.createNotStarted("tasks.scheduled.execution", registry)
                .contextualName("nightly-reconciliation")
                .observe(() -> { });

        var trace = history.recent(10).get(0);
        assertThat(history.find(trace.traceId(), "JOB").operations()).singleElement().satisfies(operation -> {
            assertThat(operation.type()).isEqualTo("JOB");
            assertThat(operation.name()).isEqualTo("nightly-reconciliation");
        });
    }

    @Test
    void canDisableScheduledTraceCaptureWithoutDisablingActuatorMetrics() {
        ObservationRegistry registry = ObservationRegistry.create();
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        properties.getScheduling().setEnabled(false);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        RecentTraceStore history = new RecentTraceStore(10);
        TraceOperationRecorder recorder = new TraceOperationRecorder(history, properties);
        registry.observationConfig().observationHandler(new ObservationTraceHandler(recorder, history, properties));

        Observation.createNotStarted("tasks.scheduled.execution", registry)
                .contextualName("nightly-reconciliation")
                .observe(() -> { });

        assertThat(history.recent(10)).isEmpty();
        assertThat(meterRegistry.get("tasks.scheduled.execution").timer().count()).isEqualTo(1);
        meterRegistry.close();
    }
}
