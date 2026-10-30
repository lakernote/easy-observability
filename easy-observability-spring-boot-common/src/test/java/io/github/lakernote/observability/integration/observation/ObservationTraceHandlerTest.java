package io.github.lakernote.observability.integration.observation;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationTraceHandlerTest {
    @AfterEach
    void clearContexts() {
        TraceContext.clear();
        TraceIdContext.clear();
    }

    @Test
    void convertsFrameworkObservationToChildSpanInTheTraceTree() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        RecentTraceStore history = new RecentTraceStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, null, properties, java.util.List.of()));
        TraceIdContext.putTraceId("trace-redis-1");
        TraceContext.startRoot("GET /cache", SpanKind.HTTP);

        Observation observation = Observation.createNotStarted("spring.data.redis", registry)
                .contextualName("GET")
                .lowCardinalityKeyValue("db.operation", "GET");
        observation.start();
        observation.stop();

        TraceContext.Completion[] completion = new TraceContext.Completion[1];
        TraceContext.stopRoot(Long.MAX_VALUE, "test", null, result -> completion[0] = result);
        assertThat(completion[0].tree()).contains("[Redis] GET");
        assertThat(completion[0].slow()).isFalse();
        history.add("trace-redis-1", "GET /cache", "GET", "HTTP", 200, 20, false, false, completion[0].tree(), "", -1, -1, -1, "", "", "");
        assertThat(history.find("trace-redis-1", "HTTP").tree()).contains("[Redis] GET");
    }

    @Test
    void recordsStandaloneDependencyWithoutInventingTraceRoot() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        RecentTraceStore history = new RecentTraceStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, null, properties, java.util.List.of()));
        Observation observation = Observation.createNotStarted("spring.data.redis", registry)
                .contextualName("GET")
                .lowCardinalityKeyValue("db.operation", "GET");

        observation.start().stop();

        assertThat(history.recent(10)).isEmpty();
        history.add("orphan-trace", "GET /cache", "GET", "HTTP", 200, 10, false, false, "tree", "", -1, -1, -1, "", "", "");

        assertThat(history.find("orphan-trace", "HTTP").tree()).isEqualTo("tree");
    }

    @Test
    void recordsHttpServerErrorStatusAsFailedTraceWithoutThrowable() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        RecentTraceStore history = new RecentTraceStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, null, properties, java.util.List.of()));

        Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/orders/{id}")
                .lowCardinalityKeyValue("http.response.status_code", "503")
                .start().stop();

        assertThat(history.recent(10)).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo(503);
            assertThat(trace.error()).isTrue();
            assertThat(trace.tree()).isNotBlank();
        });
        assertThat(history.recent(10)).singleElement().satisfies(trace -> {
            assertThat(trace.errorType()).isEqualTo("HTTP_503");
            assertThat(trace.errorMessage()).isEqualTo("HTTP status 503");
        });
    }

    @Test
    void doesNotRetainFastSuccessfulHttpObservations() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        RecentTraceStore history = new RecentTraceStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, null, properties, java.util.List.of()));

        Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/orders/{id}")
                .start().stop();

        assertThat(history.recent(10)).isEmpty();
    }

    @Test
    void excludesBundledDashboardTrafficFromEasyObservabilityRecords() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        RecentTraceStore history = new RecentTraceStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, null, properties, java.util.List.of()));

        Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/api/easy-observability/traces")
                .start().stop();

        assertThat(history.recent(10)).isEmpty();
    }

    @Test
    void excludesDashboardTrafficWhenSpringAddsRouteOnlyAtObservationStop() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        RecentTraceStore history = new RecentTraceStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, null, properties, java.util.List.of()));

        Observation observation = Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET").start();
        observation.lowCardinalityKeyValue("http.route", "/api/easy-observability/traces").stop();

        assertThat(history.recent(10)).isEmpty();
        assertThat(TraceContext.active()).isFalse();
    }

    @Test
    void recordsHttpObservationWhenContextualNameIsNotAvailableAtStart() throws Exception {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        properties.getTrace().setHttpSlowThresholdMs(1);
        RecentTraceStore history = new RecentTraceStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, null, properties, java.util.List.of()));

        Observation observation = Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/orders")
                .start();
        Thread.sleep(5);
        observation.stop();

        assertThat(history.recent(10)).singleElement().satisfies(trace -> {
            assertThat(trace.name()).isEqualTo("/orders · GET");
            assertThat(trace.tree()).contains("/orders · GET");
        });
    }

    @Test
    void asyncSlowStopFinishesItsTraceWithoutEndingAnotherThreadsTrace() throws Exception {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        properties.getTrace().setHttpSlowThresholdMs(1);
        RecentTraceStore history = new RecentTraceStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, null, properties, java.util.List.of()));
        Observation observation = Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/async")
                .start();
        Thread.sleep(5);

        boolean unrelatedTraceWasPreserved = CompletableFuture.supplyAsync(() -> {
            TraceContext.startRoot("GET /unrelated", SpanKind.HTTP);
            observation.stop();
            boolean active = TraceContext.active();
            TraceContext.clear();
            return active;
        }).join();

        assertThat(unrelatedTraceWasPreserved).isTrue();
        assertThat(TraceContext.active()).isFalse();
        assertThat(history.recent(10)).singleElement().satisfies(trace -> {
            assertThat(trace.name()).isEqualTo("/async · GET");
            assertThat(trace.sourceType()).isEqualTo("HTTP");
        });
    }
}
