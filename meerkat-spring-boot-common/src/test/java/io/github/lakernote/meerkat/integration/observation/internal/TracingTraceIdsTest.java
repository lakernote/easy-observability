package io.github.lakernote.meerkat.integration.observation.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TracingTraceIdsTest {
    private static final String TRACING_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @AfterEach
    void clear() {
        TraceContext.clear();
        TraceIds.clear();
    }

    @Test
    void httpEntryAdoptsTracingIdWhenTheFilterGeneratedOne() {
        ObservationRegistry registry = registry(TRACING_ID);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.setAttribute(TraceIds.GENERATED_REQUEST_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Trace-Id", "locally-generated");
        TraceIds.putTraceId("locally-generated");

        AtomicReference<String> seen = new AtomicReference<>();
        Observation observation = Observation.createNotStarted("http.server.requests",
                () -> new ServerRequestObservationContext(request, response), registry).start();
        seen.set(TraceIdContext.currentTraceId());
        observation.stop();

        assertThat(seen.get()).isEqualTo(TRACING_ID);
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(TRACING_ID);
    }

    @Test
    void httpEntryKeepsUpstreamTraceId() {
        ObservationRegistry registry = registry(TRACING_ID);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("X-Trace-Id", "gateway-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TraceIds.putTraceId("gateway-1");

        AtomicReference<String> seen = new AtomicReference<>();
        Observation observation = Observation.createNotStarted("http.server.requests",
                () -> new ServerRequestObservationContext(request, response), registry).start();
        seen.set(TraceIdContext.currentTraceId());
        observation.stop();

        assertThat(seen.get()).isEqualTo("gateway-1");
    }

    @Test
    void jobEntryUsesTracingIdAndReceiverReadsTraceparent() {
        ObservationRegistry registry = registry(TRACING_ID);
        AtomicReference<String> seen = new AtomicReference<>();
        Observation job = Observation.createNotStarted("tasks.scheduled.execution", registry).start();
        seen.set(TraceIdContext.currentTraceId());
        job.stop();
        assertThat(seen.get()).isEqualTo(TRACING_ID);

        ObservationRegistry plain = registry(null);
        ReceiverContext<Map<String, String>> receiver = new ReceiverContext<>(Map::get);
        receiver.setCarrier(Map.of("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));
        Observation consumer = Observation.createNotStarted("spring.kafka.listener", () -> receiver, plain).start();
        seen.set(TraceIdContext.currentTraceId());
        consumer.stop();
        assertThat(seen.get()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    /** Mimics Micrometer Tracing: its handler runs first and stores the span in the context. */
    private static ObservationRegistry registry(String tracingId) {
        ObservationRegistry registry = ObservationRegistry.create();
        if (tracingId != null) {
            registry.observationConfig().observationHandler(new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }

                @Override
                public void onStart(Observation.Context context) {
                    Span span = mock(Span.class);
                    io.micrometer.tracing.TraceContext traceContext = mock(io.micrometer.tracing.TraceContext.class);
                    when(traceContext.traceId()).thenReturn(tracingId);
                    when(span.context()).thenReturn(traceContext);
                    TracingObservationHandler.TracingContext tracing = new TracingObservationHandler.TracingContext();
                    tracing.setSpan(span);
                    context.put(TracingObservationHandler.TracingContext.class, tracing);
                }
            });
        }
        registry.observationConfig().observationHandler(new ObservationTraceHandler(
                TraceStore.inMemory(1024 * 1024), new MeerkatProperties(), List.of()));
        return registry;
    }
}
