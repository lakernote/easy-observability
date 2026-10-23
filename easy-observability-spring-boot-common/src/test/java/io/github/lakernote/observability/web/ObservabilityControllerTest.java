package io.github.lakernote.observability.web;

import io.github.lakernote.observability.trace.store.RecentTraceStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityControllerTest {
    @Test
    void returnsOnlySlowAndErrorTraces() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("trace-1", "OrdersController#create", "POST /orders", 201, 12, false, false, "");
        history.add("trace-2", "OrdersController#slow", "GET /orders", "HTTP", 200, 900, true, false, "");
        ObservabilityController controller = new ObservabilityController(history, () -> true);

        var traces = controller.traces(100);

        assertThat(traces).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("trace-2");
    }

    @Test
    void rejectsRequestsWhenTheHostPolicyDeniesAccess() {
        ObservabilityController controller = new ObservabilityController(new RecentTraceStore(10), () -> false);

        assertThatThrownBy(() -> controller.traces(10))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void clearsRecentTraceWindowThroughProtectedClearAction() throws Exception {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("trace-1", "GET /orders", "GET", "HTTP", 200, 12, false, false, "tree");
        ObservabilityController controller = new ObservabilityController(history, () -> true);

        var result = controller.clearTraceHistory();

        assertThat(result.clearedRecentTraceCount()).isEqualTo(1);
        assertThat(controller.traces(10)).isEmpty();
    }

    @Test
    void deniesClearActionWhenTheHostPolicyDeniesAccess() {
        ObservabilityController controller = new ObservabilityController(new RecentTraceStore(10), () -> false);

        assertThatThrownBy(controller::clearTraceHistory)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void slowAndErrorListsReturnRootTracesFilteredByTheirEntrySource() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("http-slow", "OrdersController#list", "GET /orders", "HTTP",
                200, 900, true, false, "http tree");
        history.add("job-error", "NightlyJob#run", "nightly-job", "JOB",
                500, 120, false, true, "job tree", 7, 2, 100,
                "TransactionRollback", "query failed");
        ObservabilityController controller = new ObservabilityController(history, () -> true);

        var slow = controller.slowErrors("slow", "", 100);
        var httpSlow = controller.slowErrors("slow", "HTTP", 100);
        var errors = controller.slowErrors("error", "", 100);

        assertThat(slow).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("http-slow");
        assertThat(httpSlow).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("http-slow");
        assertThat(errors).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("job-error");
        assertThat(httpSlow.get(0).sourceType()).isEqualTo("HTTP");
    }

    @Test
    void traceListAndSlowErrorDrillDownNeverExposeDependencyOperationsAsRoots() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("http-1", "GET /orders", "GET", "HTTP", 200, 40, false, false, "http tree");
        history.add("redis-1", "Redis get", "get", "REDIS", 200, 1, true, false, "redis tree");
        history.add("transaction-1", "OrderService#create", "write", "TRANSACTION",
                500, 100, false, true, "transaction tree");
        ObservabilityController controller = new ObservabilityController(history, () -> true);

        assertThat(controller.traces(100)).isEmpty();
        assertThat(controller.slowErrors("slow", "", 100)).isEmpty();
        assertThat(controller.slowErrors("error", "", 100)).isEmpty();
        assertThat(controller.trace("redis-1")).isNull();
        assertThat(controller.trace("transaction-1")).isNull();
    }

    @Test
    void keepsDistinctEntryRootsWhenHttpAndKafkaShareOneTraceId() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("shared-trace", "/api/demo/kafka/error", "GET", "HTTP",
                200, 35, false, false, "http tree");
        history.add("shared-trace", "receive", "", "KAFKA_CONSUMER",
                200, 4, false, true, "kafka tree");
        ObservabilityController controller = new ObservabilityController(history, () -> true);

        assertThat(controller.traces(10)).extracting(RecentTraceStore.TraceRecord::sourceType)
                .containsExactly("KAFKA_CONSUMER");
        assertThat(controller.slowErrors("error", "", 10))
                .extracting(RecentTraceStore.TraceRecord::sourceType).containsExactly("KAFKA_CONSUMER");
    }

    @Test
    void filtersTraceListBySource() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("job-trace", "nightly cleanup", "", "JOB", 200, 120, true, false, "tree");
        history.add("http-trace", "GET /orders", "GET", "HTTP", 200, 20, false, false, "tree");
        ObservabilityController controller = new ObservabilityController(history, () -> true);

        var matches = controller.traces(100, "JOB");

        assertThat(matches).singleElement().satisfies(trace -> {
            assertThat(trace.traceId()).isEqualTo("job-trace");
            assertThat(trace.name()).isEqualTo("nightly cleanup");
        });
    }

    @Test
    void searchesRecentTraceTreesForSqlAndDependencyDetails() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("sql-trace", "GET /orders", "GET", "HTTP", 200, 400, true, false,
                "`- [Database] query tag=\"select * from orders where id = ?\" 7ms");
        history.add("other-trace", "GET /health", "GET", "HTTP", 200, 3, false, false, "health tree");
        ObservabilityController controller = new ObservabilityController(history, () -> true);

        var matches = controller.traces(100, "HTTP", "select * from orders");

        assertThat(matches).singleElement().satisfies(trace -> assertThat(trace.traceId()).isEqualTo("sql-trace"));
    }

    @Test
    void searchesKafkaTraceByListenerThreadName() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("kafka-trace", "receive", "", "KAFKA_CONSUMER", 500, 8, false, true,
                "kafka tree", "demo-listener-0-C-1", 11, 1, 512, "", "");
        ObservabilityController controller = new ObservabilityController(history, () -> true);

        var matches = controller.traces(100, "KAFKA_CONSUMER", "demo-listener-0");

        assertThat(matches).singleElement().satisfies(trace -> assertThat(trace.traceId()).isEqualTo("kafka-trace"));
    }

    @Test
    void reportsTheMemoryTraceHistoryCapacity() {
        RecentTraceStore history = new RecentTraceStore(12);
        history.add("trace-1", "GET /orders", "GET", "HTTP", 200, 20, false, false, "tree");
        ObservabilityController controller = new ObservabilityController(history, () -> true);

        var status = controller.traceStorage();

        assertThat(status.recentTraceCount()).isEqualTo(1);
        assertThat(status.recentTraceCapacity()).isEqualTo(12);
        assertThat(status.slowErrorTraceCapacity()).isZero();
    }
}
