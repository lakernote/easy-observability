package io.github.lakernote.observability.integration.observation.internal;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.stats.internal.OperationStats;
import io.github.lakernote.observability.stats.internal.OperationStats.Category;
import io.github.lakernote.observability.stats.internal.OperationStats.Row;
import io.github.lakernote.observability.trace.internal.TraceContext;
import io.github.lakernote.observability.trace.internal.TraceIds;
import io.github.lakernote.observability.trace.internal.store.RecentTraceStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationTraceHandlerStatsTest {
    private final OperationStats stats = new OperationStats(100);
    private final ObservationRegistry registry = ObservationRegistry.create();

    ObservationTraceHandlerStatsTest() {
        registry.observationConfig().observationHandler(new ObservationTraceHandler(new RecentTraceStore(10), null,
                new EasyObservabilityProperties(), List.of(), stats));
    }

    @AfterEach
    void clearContexts() {
        TraceContext.clear();
        TraceIds.clear();
    }

    @Test
    void ranksHttpEndpointsByRouteAndMethodCountingServerErrors() {
        http("/orders/{id}", "GET", "200");
        http("/orders/{id}", "GET", "503");
        http("/orders/{id}", "GET", "404");

        assertThat(rows(Category.HTTP)).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("/orders/{id} · GET");
            assertThat(row.count()).isEqualTo(3);
            assertThat(row.errors()).isEqualTo(1);
        });
    }

    @Test
    void ranksDependencyCallsEvenOutsideATrace() {
        Observation.createNotStarted("http.client.requests", registry)
                .lowCardinalityKeyValue("method", "POST")
                .lowCardinalityKeyValue("client.name", "pay.example.com")
                .lowCardinalityKeyValue("uri", "/charges/{id}")
                .lowCardinalityKeyValue("status", "500")
                .start().stop();
        Observation.createNotStarted("http.client.requests", registry)
                .lowCardinalityKeyValue("method", "GET")
                .lowCardinalityKeyValue("client.name", "cdn.example.com")
                .lowCardinalityKeyValue("uri", "none")
                .start().stop();
        Observation.createNotStarted("spring.data.redis", registry)
                .lowCardinalityKeyValue("db.operation", "get").start().stop();
        Observation observation = Observation.createNotStarted("spring.data.redis", registry)
                .lowCardinalityKeyValue("db.operation", "GET").start();
        observation.error(new IllegalStateException("down"));
        observation.stop();

        assertThat(rows(Category.REMOTE)).extracting(Row::name, Row::traceQuery, Row::errors)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("POST pay.example.com /charges/{id}", "/charges/{id}", 1L),
                        org.assertj.core.groups.Tuple.tuple("GET cdn.example.com", "GET cdn.example.com", 0L));
        assertThat(rows(Category.REDIS)).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("GET");
            assertThat(row.count()).isEqualTo(2);
            assertThat(row.errors()).isEqualTo(1);
        });
        assertThat(TraceContext.active()).isFalse();
    }

    @Test
    void kafkaStatisticsNeverUsePerMessageValues() {
        for (int offset = 0; offset < 3; offset++) {
            Observation.createNotStarted("spring.kafka.listener", registry)
                    .lowCardinalityKeyValue("messaging.source.name", "orders")
                    .lowCardinalityKeyValue("spring.kafka.listener.id", "order-listener")
                    .highCardinalityKeyValue("messaging.kafka.message.offset", String.valueOf(offset))
                    .start().stop();
        }
        Observation.createNotStarted("spring.kafka.template", registry)
                .lowCardinalityKeyValue("messaging.destination.name", "audit")
                .start().stop();

        assertThat(rows(Category.KAFKA_CONSUMER)).extracting(Row::name, Row::traceQuery, Row::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("orders · order-listener", "order-listener", 3L));
        assertThat(rows(Category.KAFKA_PRODUCER)).extracting(Row::name).containsExactly("audit");
    }

    @Test
    void skipsJdbcObservationsTransactionsAndDashboardTraffic() {
        Observation.createNotStarted("spring.jdbc", registry)
                .lowCardinalityKeyValue("db.operation", "SELECT").start().stop();
        Observation.createNotStarted("spring.transaction", registry).start().stop();
        http("/easy-observability/stats/", "GET", "200");
        http("/api/easy-observability/stats", "GET", "200");

        assertThat(stats.snapshot(OperationStats.Window.TODAY).categories())
                .allSatisfy(category -> assertThat(category.rows()).isEmpty());
    }

    private void http(String route, String method, String status) {
        Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.route", route)
                .lowCardinalityKeyValue("uri", route)
                .lowCardinalityKeyValue("method", method)
                .lowCardinalityKeyValue("status", status)
                .start().stop();
    }

    private List<Row> rows(Category category) {
        return stats.snapshot(OperationStats.Window.TODAY).categories().stream()
                .filter(snapshot -> snapshot.category() == category).findFirst().orElseThrow().rows();
    }
}
