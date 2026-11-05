package io.github.lakernote.observability.web.internal;

import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import io.github.lakernote.observability.trace.internal.store.RecentTraceStore;
import io.github.lakernote.observability.trace.internal.TraceNodeFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityControllerTest {
    @Test
    void returnsOnlySlowAndErrorTraces() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace().traceId("trace-1").sourceType("HTTP")
                .name("OrdersController#create").method("POST /orders").status(201).durationMs(12).build());
        history.add(trace().traceId("trace-2").sourceType("HTTP")
                .name("OrdersController#slow").method("GET /orders").status(200).durationMs(900)
                .slow(true).build());
        ObservabilityController controller = new ObservabilityController(history, null, allowAll());

        var traces = controller.traces(100, "", "");

        assertThat(traces).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("trace-2");
    }

    @Test
    void rejectsRequestsWhenThePolicyDeniesViewing() {
        ObservabilityController controller = new ObservabilityController(new RecentTraceStore(10), null, denyView());

        assertThatThrownBy(() -> controller.traces(10, "", ""))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void clearsRecentTraceWindowThroughProtectedClearAction() throws Exception {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace().traceId("trace-1").sourceType("HTTP")
                .name("GET /orders").method("GET").status(200).durationMs(12)
                .treeNodes(TraceNodeFixtures.named("tree")).build());
        ObservabilityController controller = new ObservabilityController(history, null, allowAll());

        var result = controller.clearTraceHistory();

        assertThat(result.clearedRecentTraceCount()).isEqualTo(1);
        assertThat(controller.traces(10, "", "")).isEmpty();
    }

    @Test
    void deniesClearActionWhenThePolicyDeniesViewing() {
        ObservabilityController controller = new ObservabilityController(new RecentTraceStore(10), null, denyView());

        assertThatThrownBy(controller::clearTraceHistory)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void deniesClearActionByDefaultEvenWhenViewIsAllowed() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace().traceId("trace-1").sourceType("HTTP")
                .name("GET /orders").method("GET").status(200).durationMs(900)
                .slow(true).treeNodes(TraceNodeFixtures.named("tree")).build());
        ObservabilityController controller = new ObservabilityController(history, null, () -> true);

        assertThatThrownBy(controller::clearTraceHistory)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        assertThat(controller.traces(10, "", "")).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("trace-1");
    }

    @Test
    void reportsClearPermissionToTheDashboard() {
        assertThat(new ObservabilityController(new RecentTraceStore(10), null, allowAll())
                .permissions().canClear()).isTrue();
        assertThat(new ObservabilityController(new RecentTraceStore(10), null, () -> true)
                .permissions().canClear()).isFalse();
        assertThatThrownBy(new ObservabilityController(new RecentTraceStore(10), null, denyView())
                ::permissions)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void slowAndErrorListsReturnRootTracesFilteredByTheirEntrySource() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace().traceId("http-slow").sourceType("HTTP")
                .name("OrdersController#list").method("GET /orders").status(200).durationMs(900)
                .slow(true).treeNodes(TraceNodeFixtures.named("http tree")).build());
        history.add(trace().traceId("job-error").sourceType("JOB")
                .name("NightlyJob#run").method("nightly-job").status(500).durationMs(120)
                .error(true).treeNodes(TraceNodeFixtures.named("job tree")).threadId(7).cpuTimeMs(2)
                .allocatedBytes(100).errorType("TransactionRollback").errorMessage("query failed").build());
        ObservabilityController controller = new ObservabilityController(history, null, allowAll());

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
        history.add(trace().traceId("http-1").sourceType("HTTP")
                .name("GET /orders").method("GET").status(200).durationMs(40)
                .treeNodes(TraceNodeFixtures.named("http tree")).build());
        history.add(trace().traceId("redis-1").sourceType("REDIS")
                .name("Redis get").method("get").status(200).durationMs(1)
                .slow(true).treeNodes(TraceNodeFixtures.named("redis tree")).build());
        history.add(trace().traceId("transaction-1").sourceType("TRANSACTION")
                .name("OrderService#create").method("write").status(500).durationMs(100)
                .error(true).treeNodes(TraceNodeFixtures.named("transaction tree")).build());
        ObservabilityController controller = new ObservabilityController(history, null, allowAll());

        assertThat(controller.traces(100, "", "")).isEmpty();
        assertThat(controller.diagnostics("slow", "", 100)).isEmpty();
        assertThat(controller.diagnostics("error", "", 100)).isEmpty();
        assertThat(controller.trace("redis-1", "")).isNull();
        assertThat(controller.trace("transaction-1", "")).isNull();
    }

    @Test
    void keepsDistinctEntryRootsWhenHttpAndKafkaShareOneTraceId() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace().traceId("shared-trace").sourceType("HTTP")
                .name("/api/demo/kafka/error").method("GET").status(200).durationMs(35)
                .treeNodes(TraceNodeFixtures.named("http tree")).build());
        history.add(trace().traceId("shared-trace").sourceType("KAFKA_CONSUMER")
                .name("receive").method("").status(200).durationMs(4)
                .error(true).treeNodes(TraceNodeFixtures.named("kafka tree")).build());
        ObservabilityController controller = new ObservabilityController(history, null, allowAll());

        assertThat(controller.traces(10, "", "")).extracting(RecentTraceStore.TraceRecord::sourceType)
                .containsExactly("KAFKA_CONSUMER");
        assertThat(controller.diagnostics("error", "", 10))
                .extracting(RecentTraceStore.TraceRecord::sourceType).containsExactly("KAFKA_CONSUMER");
    }

    @Test
    void filtersTraceListBySource() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace().traceId("job-trace").sourceType("JOB")
                .name("nightly cleanup").method("").status(200).durationMs(120)
                .slow(true).treeNodes(TraceNodeFixtures.named("tree")).build());
        history.add(trace().traceId("http-trace").sourceType("HTTP")
                .name("GET /orders").method("GET").status(200).durationMs(20)
                .treeNodes(TraceNodeFixtures.named("tree")).build());
        ObservabilityController controller = new ObservabilityController(history, null, allowAll());

        var matches = controller.traces(100, "JOB", "");

        assertThat(matches).singleElement().satisfies(trace -> {
            assertThat(trace.traceId()).isEqualTo("job-trace");
            assertThat(trace.name()).isEqualTo("nightly cleanup");
        });
    }

    @Test
    void searchesRecentTraceTreesForSqlAndDependencyDetails() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace().traceId("sql-trace").sourceType("HTTP")
                .name("GET /orders").method("GET").status(200).durationMs(400)
                .slow(true)
                .treeNodes(TraceNodeFixtures.named("`- [Database] query tag=\"select * from orders where id = ?\" 7ms"))
                .build());
        history.add(trace().traceId("other-trace").sourceType("HTTP")
                .name("GET /health").method("GET").status(200).durationMs(3)
                .treeNodes(TraceNodeFixtures.named("health tree")).build());
        ObservabilityController controller = new ObservabilityController(history, null, allowAll());

        var matches = controller.traces(100, "HTTP", "select * from orders");

        assertThat(matches).singleElement().satisfies(trace -> assertThat(trace.traceId()).isEqualTo("sql-trace"));
    }

    @Test
    void searchesKafkaTraceByListenerThreadName() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace().traceId("kafka-trace").sourceType("KAFKA_CONSUMER")
                .name("receive").method("").status(500).durationMs(8)
                .error(true).treeNodes(TraceNodeFixtures.named("kafka tree")).threadName("demo-listener-0-C-1")
                .threadId(11).cpuTimeMs(1).allocatedBytes(512).build());
        ObservabilityController controller = new ObservabilityController(history, null, allowAll());

        var matches = controller.traces(100, "KAFKA_CONSUMER", "demo-listener-0");

        assertThat(matches).singleElement().satisfies(trace -> assertThat(trace.traceId()).isEqualTo("kafka-trace"));
    }

    @Test
    void reportsTheMemoryTraceHistoryCapacity() {
        RecentTraceStore history = new RecentTraceStore(12);
        history.add(trace().traceId("trace-1").sourceType("HTTP")
                .name("GET /orders").method("GET").status(200).durationMs(20)
                .treeNodes(TraceNodeFixtures.named("tree")).build());
        ObservabilityController controller = new ObservabilityController(history, null, allowAll());

        var status = controller.traceStorage();

        assertThat(status.recentTraceCount()).isEqualTo(1);
        assertThat(status.recentTraceCapacity()).isEqualTo(12);
        assertThat(status.diagnosticTraceCapacity()).isZero();
        assertThat(status.diagnosticPersistenceEnabled()).isFalse();
    }

    private static RecentTraceStore.TraceRecord.Builder trace() {
        return RecentTraceStore.TraceRecord.builder();
    }

    private static ObservabilityAccessPolicy allowAll() {
        return new ObservabilityAccessPolicy() {
            @Override
            public boolean canView() {
                return true;
            }

            @Override
            public boolean canClear() {
                return true;
            }
        };
    }

    private static ObservabilityAccessPolicy denyView() {
        return new ObservabilityAccessPolicy() {
            @Override
            public boolean canView() {
                return false;
            }

            @Override
            public boolean canClear() {
                return true;
            }
        };
    }
}
