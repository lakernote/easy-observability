package io.github.lakernote.observability.web.internal;

import io.github.lakernote.observability.trace.internal.TraceNodeFixtures;
import io.github.lakernote.observability.trace.internal.store.TraceRecord;
import io.github.lakernote.observability.trace.internal.store.TraceStore;
import io.github.lakernote.observability.trace.internal.store.TraceSummary;
import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityControllerTest {
    @Test
    void listsStoredTraceSummariesWithoutTheirCallTrees() {
        TraceStore store = TraceStore.inMemory(10);
        store.add(trace().traceId("trace-1").sourceType("HTTP")
                .name("OrdersController#create").method("POST /orders").status(201).durationMs(12).build());
        store.add(trace().traceId("trace-2").sourceType("HTTP")
                .name("OrdersController#slow").method("GET /orders").status(200).durationMs(900)
                .slow(true).treeNodes(TraceNodeFixtures.named("tree")).build());
        ObservabilityController controller = new ObservabilityController(store, allowAll());

        var page = controller.traces("all", "", "", "", 50);

        assertThat(page.items()).extracting(TraceSummary::traceId).containsExactly("trace-2");
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.nextCursor()).isNull();
        assertThat(controller.trace("trace-2", "HTTP").treeNodes()).isNotEmpty();
    }

    @Test
    void rejectsInvalidQueriesWithBadRequest() {
        ObservabilityController controller = new ObservabilityController(TraceStore.inMemory(10), allowAll());

        assertThatThrownBy(() -> controller.traces("bogus", "", "", "", 50))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> controller.traces("all", "", "", "not-a-cursor", 50))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void returnsNotFoundForUnknownTrace() {
        ObservabilityController controller = new ObservabilityController(TraceStore.inMemory(10), allowAll());

        assertThatThrownBy(() -> controller.trace("missing", ""))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void rejectsRequestsWhenThePolicyDeniesViewing() {
        ObservabilityController controller = new ObservabilityController(TraceStore.inMemory(10), denyView());

        assertThatThrownBy(() -> controller.traces("all", "", "", "", 10))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403 FORBIDDEN");
        assertThatThrownBy(controller::traceStorage)
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403 FORBIDDEN");
        assertThatThrownBy(() -> controller.trace("x", ""))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void clearsStoredTracesThroughProtectedClearAction() {
        TraceStore store = TraceStore.inMemory(10);
        store.add(trace().traceId("trace-1").sourceType("HTTP").name("GET /orders").durationMs(900).slow(true)
                .build());
        ObservabilityController controller = new ObservabilityController(store, allowAll());

        assertThat(controller.clearTraceHistory().clearedTraceCount()).isEqualTo(1);
        assertThat(controller.traces("all", "", "", "", 10).items()).isEmpty();
    }

    @Test
    void deniesClearActionWhenThePolicyDeniesViewing() {
        ObservabilityController controller = new ObservabilityController(TraceStore.inMemory(10), denyView());

        assertThatThrownBy(controller::clearTraceHistory)
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void deniesClearActionByDefaultEvenWhenViewIsAllowed() {
        TraceStore store = TraceStore.inMemory(10);
        store.add(trace().traceId("trace-1").sourceType("HTTP").name("GET /orders").durationMs(900).slow(true)
                .build());
        ObservabilityController controller = new ObservabilityController(store, () -> true);

        assertThatThrownBy(controller::clearTraceHistory)
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403 FORBIDDEN");
        assertThat(controller.traces("all", "", "", "", 10).items()).extracting(TraceSummary::traceId)
                .containsExactly("trace-1");
    }

    @Test
    void reportsClearPermissionToTheDashboard() {
        assertThat(new ObservabilityController(TraceStore.inMemory(10), allowAll()).permissions().canClear())
                .isTrue();
        assertThat(new ObservabilityController(TraceStore.inMemory(10), () -> true).permissions().canClear())
                .isFalse();
        assertThatThrownBy(new ObservabilityController(TraceStore.inMemory(10), denyView())::permissions)
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void filtersByKindAndEntrySource() {
        TraceStore store = TraceStore.inMemory(10);
        store.add(trace().traceId("http-slow").sourceType("HTTP").name("OrdersController#list")
                .durationMs(900).slow(true).build());
        store.add(trace().traceId("job-error").sourceType("JOB").name("NightlyJob#run").status(500)
                .error(true).errorType("TransactionRollback").errorMessage("query failed").build());
        ObservabilityController controller = new ObservabilityController(store, allowAll());

        assertThat(controller.traces("slow", "", "", "", 50).items()).extracting(TraceSummary::traceId)
                .containsExactly("http-slow");
        assertThat(controller.traces("all", "JOB", "", "", 50).items()).extracting(TraceSummary::traceId)
                .containsExactly("job-error");
        assertThat(controller.traces("error", "HTTP", "", "", 50).items()).isEmpty();
    }

    @Test
    void neverExposesDependencyOperationsAsRoots() {
        TraceStore store = TraceStore.inMemory(10);
        store.add(trace().traceId("redis-1").sourceType("REDIS").name("Redis get").slow(true).build());
        store.add(trace().traceId("transaction-1").sourceType("TRANSACTION").name("OrderService#create")
                .error(true).build());
        ObservabilityController controller = new ObservabilityController(store, allowAll());

        assertThat(controller.traces("all", "", "", "", 50).items()).isEmpty();
        assertThatThrownBy(() -> controller.trace("redis-1", "")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void keepsDistinctEntryRootsWhenHttpAndKafkaShareOneTraceId() {
        TraceStore store = TraceStore.inMemory(10);
        store.add(trace().traceId("shared-trace").sourceType("HTTP").name("/api/demo/kafka/error")
                .durationMs(900).slow(true).build());
        store.add(trace().traceId("shared-trace").sourceType("KAFKA_CONSUMER").name("receive")
                .error(true).build());
        ObservabilityController controller = new ObservabilityController(store, allowAll());

        assertThat(controller.traces("all", "", "", "", 10).items()).extracting(TraceSummary::sourceType)
                .containsExactly("KAFKA_CONSUMER", "HTTP");
        assertThat(controller.trace("shared-trace", "").sourceType()).isEqualTo("HTTP");
        assertThat(controller.trace("shared-trace", "KAFKA_CONSUMER").name()).isEqualTo("receive");
    }

    @Test
    void searchesTraceTreesForSqlAndThreadNames() {
        TraceStore store = TraceStore.inMemory(10);
        store.add(trace().traceId("sql-trace").sourceType("HTTP").name("GET /orders").durationMs(400).slow(true)
                .treeNodes(TraceNodeFixtures.named("`- [Database] query tag=\"select * from orders where id = ?\" 7ms"))
                .build());
        store.add(trace().traceId("kafka-trace").sourceType("KAFKA_CONSUMER").name("receive").error(true)
                .threadName("demo-listener-0-C-1").build());
        ObservabilityController controller = new ObservabilityController(store, allowAll());

        assertThat(controller.traces("all", "HTTP", "select * from orders", "", 50).items())
                .extracting(TraceSummary::traceId).containsExactly("sql-trace");
        assertThat(controller.traces("all", "", "demo-listener-0", "", 50).items())
                .extracting(TraceSummary::traceId).containsExactly("kafka-trace");
    }

    @Test
    void reportsMemoryOnlyStorage() {
        TraceStore store = TraceStore.inMemory(12);
        store.add(trace().traceId("trace-1").sourceType("HTTP").name("GET /orders").durationMs(900).slow(true)
                .build());
        ObservabilityController controller = new ObservabilityController(store, allowAll());

        var status = controller.traceStorage();

        assertThat(status.traceCount()).isEqualTo(1);
        assertThat(status.persistent()).isFalse();
        assertThat(status.storageBytes()).isZero();
    }

    private static TraceRecord.Builder trace() {
        return TraceRecord.builder();
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
