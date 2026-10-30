package io.github.lakernote.observability.web;

import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.TraceNodeFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityControllerTest {
    @Test
    void returnsOnlySlowAndErrorTraces() {
        RecentTraceStore history = new RecentTraceStore(10);
        record(history, "trace-1", "OrdersController#create", "POST /orders", "HTTP", 201, 12, false, false, "", "", -1, -1, -1, "", "", "");
        record(history, "trace-2", "OrdersController#slow", "GET /orders", "HTTP", 200, 900, true, false, "", "", -1, -1, -1, "", "", "");
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        var traces = controller.traces(100, "", "");

        assertThat(traces).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("trace-2");
    }

    @Test
    void rejectsRequestsWhenTheHostPolicyDeniesAccess() {
        ObservabilityController controller = new ObservabilityController(new RecentTraceStore(10), null, () -> false);

        assertThatThrownBy(() -> controller.traces(10, "", ""))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void clearsRecentTraceWindowThroughProtectedClearAction() throws Exception {
        RecentTraceStore history = new RecentTraceStore(10);
        record(history, "trace-1", "GET /orders", "GET", "HTTP", 200, 12, false, false, "tree", "", -1, -1, -1, "", "", "");
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        var result = controller.clearTraceHistory();

        assertThat(result.clearedRecentTraceCount()).isEqualTo(1);
        assertThat(controller.traces(10, "", "")).isEmpty();
    }

    @Test
    void deniesClearActionWhenTheHostPolicyDeniesAccess() {
        ObservabilityController controller = new ObservabilityController(new RecentTraceStore(10), null, () -> false);

        assertThatThrownBy(controller::clearTraceHistory)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void slowAndErrorListsReturnRootTracesFilteredByTheirEntrySource() {
        RecentTraceStore history = new RecentTraceStore(10);
        record(history, "http-slow", "OrdersController#list", "GET /orders", "HTTP", 200, 900, true, false, "http tree", "", -1, -1, -1, "", "", "");
        record(history, "job-error", "NightlyJob#run", "nightly-job", "JOB", 500, 120, false, true, "job tree", "", 7, 2, 100, "TransactionRollback", "query failed", "");
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        var slow = controller.diagnostics("slow", "", 100);
        var httpSlow = controller.diagnostics("slow", "HTTP", 100);
        var errors = controller.diagnostics("error", "", 100);

        assertThat(slow).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("http-slow");
        assertThat(httpSlow).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("http-slow");
        assertThat(errors).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("job-error");
        assertThat(httpSlow.get(0).sourceType()).isEqualTo("HTTP");
    }

    @Test
    void diagnosticTraceDrillDownNeverExposesDependencyOperationsAsRoots() {
        RecentTraceStore history = new RecentTraceStore(10);
        record(history, "http-1", "GET /orders", "GET", "HTTP", 200, 40, false, false, "http tree", "", -1, -1, -1, "", "", "");
        record(history, "redis-1", "Redis get", "get", "REDIS", 200, 1, true, false, "redis tree", "", -1, -1, -1, "", "", "");
        record(history, "transaction-1", "OrderService#create", "write", "TRANSACTION", 500, 100, false, true, "transaction tree", "", -1, -1, -1, "", "", "");
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        assertThat(controller.traces(100, "", "")).isEmpty();
        assertThat(controller.diagnostics("slow", "", 100)).isEmpty();
        assertThat(controller.diagnostics("error", "", 100)).isEmpty();
        assertThat(controller.trace("redis-1", "")).isNull();
        assertThat(controller.trace("transaction-1", "")).isNull();
    }

    @Test
    void keepsDistinctEntryRootsWhenHttpAndKafkaShareOneTraceId() {
        RecentTraceStore history = new RecentTraceStore(10);
        record(history, "shared-trace", "/api/demo/kafka/error", "GET", "HTTP", 200, 35, false, false, "http tree", "", -1, -1, -1, "", "", "");
        record(history, "shared-trace", "receive", "", "KAFKA_CONSUMER", 200, 4, false, true, "kafka tree", "", -1, -1, -1, "", "", "");
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        assertThat(controller.traces(10, "", "")).extracting(RecentTraceStore.TraceRecord::sourceType)
                .containsExactly("KAFKA_CONSUMER");
        assertThat(controller.diagnostics("error", "", 10))
                .extracting(RecentTraceStore.TraceRecord::sourceType).containsExactly("KAFKA_CONSUMER");
    }

    @Test
    void filtersTraceListBySource() {
        RecentTraceStore history = new RecentTraceStore(10);
        record(history, "job-trace", "nightly cleanup", "", "JOB", 200, 120, true, false, "tree", "", -1, -1, -1, "", "", "");
        record(history, "http-trace", "GET /orders", "GET", "HTTP", 200, 20, false, false, "tree", "", -1, -1, -1, "", "", "");
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        var matches = controller.traces(100, "JOB", "");

        assertThat(matches).singleElement().satisfies(trace -> {
            assertThat(trace.traceId()).isEqualTo("job-trace");
            assertThat(trace.name()).isEqualTo("nightly cleanup");
        });
    }

    @Test
    void searchesRecentTraceTreesForSqlAndDependencyDetails() {
        RecentTraceStore history = new RecentTraceStore(10);
        record(history, "sql-trace", "GET /orders", "GET", "HTTP", 200, 400, true, false, "`- [Database] query tag=\"select * from orders where id = ?\" 7ms", "", -1, -1, -1, "", "", "");
        record(history, "other-trace", "GET /health", "GET", "HTTP", 200, 3, false, false, "health tree", "", -1, -1, -1, "", "", "");
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        var matches = controller.traces(100, "HTTP", "select * from orders");

        assertThat(matches).singleElement().satisfies(trace -> assertThat(trace.traceId()).isEqualTo("sql-trace"));
    }

    @Test
    void searchesKafkaTraceByListenerThreadName() {
        RecentTraceStore history = new RecentTraceStore(10);
        record(history, "kafka-trace", "receive", "", "KAFKA_CONSUMER", 500, 8, false, true, "kafka tree", "demo-listener-0-C-1", 11, 1, 512, "", "", "");
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        var matches = controller.traces(100, "KAFKA_CONSUMER", "demo-listener-0");

        assertThat(matches).singleElement().satisfies(trace -> assertThat(trace.traceId()).isEqualTo("kafka-trace"));
    }

    @Test
    void reportsTheMemoryTraceHistoryCapacity() {
        RecentTraceStore history = new RecentTraceStore(12);
        record(history, "trace-1", "GET /orders", "GET", "HTTP", 200, 20, false, false, "tree", "", -1, -1, -1, "", "", "");
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        var status = controller.traceStorage();

        assertThat(status.recentTraceCount()).isEqualTo(1);
        assertThat(status.recentTraceCapacity()).isEqualTo(12);
        assertThat(status.diagnosticTraceCapacity()).isZero();
    }

    private static void record(RecentTraceStore history, String traceId, String name, String method, String sourceType,
                              int status, long durationMs, boolean slow, boolean error, String tree, String threadName,
                              long threadId, long cpuTimeMs, long allocatedBytes, String errorType, String errorMessage,
                              String errorStackTrace) {
        history.add(traceId, name, method, sourceType, status, durationMs, slow, error,
                TraceNodeFixtures.named(tree), threadName, threadId, cpuTimeMs, allocatedBytes, errorType,
                errorMessage, errorStackTrace, false, false);
    }
}
