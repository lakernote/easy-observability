package io.github.lakernote.observability.trace.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentTraceStoreTest {
    @Test
    void onlyKeepsInboundEntryPointsAsTraceRoots() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("http-1", "GET /orders", "GET", "HTTP", 200, 8, false, false, "http tree", "", -1, -1, -1, "", "", "");
        history.add("redis-1", "Redis get", "get", "REDIS", 200, 1, false, false, "redis tree", "", -1, -1, -1, "", "", "");
        history.add("transaction-1", "OrderService#create", "write", "TRANSACTION", 200, 4, false, false, "transaction tree", "", -1, -1, -1, "", "", "");

        assertThat(history.recent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("http-1");
        assertThat(history.find("redis-1", "")).isNull();
        assertThat(history.find("transaction-1", "")).isNull();
    }

    @Test
    void keepsOnlyNewestTreesAndStoresEntryResourcesOnTheTrace() {
        RecentTraceStore history = new RecentTraceStore(2);
        history.add("trace-1", "first", "GET /first", "HTTP", 200, 5, false, false, "first tree", "", -1, -1, -1, "", "", "");
        history.add("trace-2", "second", "GET /second", "HTTP", 500, 40, true, true, "second tree", "", 12, 8, 4096, "HTTP_500", "request failed", "");
        history.add("trace-3", "third", "GET /third", "HTTP", 200, 7, false, false, "third tree", "", -1, -1, -1, "", "", "");

        List<RecentTraceStore.TraceRecord> records = history.recent(10);

        assertThat(records).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("trace-3", "trace-2");
        assertThat(history.find("trace-2", "")).satisfies(trace -> {
            assertThat(trace.tree()).isEqualTo("second tree");
            assertThat(trace.threadId()).isEqualTo(12);
            assertThat(trace.threadName()).isEmpty();
            assertThat(trace.cpuTimeMs()).isEqualTo(8);
            assertThat(trace.allocatedBytes()).isEqualTo(4096);
            assertThat(trace.errorType()).isEqualTo("HTTP_500");
            assertThat(trace.errorMessage()).isEqualTo("request failed");
        });
        assertThat(history.find("trace-1", "")).isNull();
    }

    @Test
    void selectsRequestedEntrySourceWhenKafkaAndHttpShareTraceId() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("shared-trace", "/api/demo/real-flow", "GET", "HTTP", 200, 500, true, false, "http tree", "", 1, 12, 2048, "", "", "");
        history.add("shared-trace", "receive", "", "KAFKA_CONSUMER", 200, 20, false, false, "kafka tree", "", 2, 3, 512, "", "", "");

        assertThat(history.find("shared-trace", "").sourceType()).isEqualTo("HTTP");
        assertThat(history.find("shared-trace", "").tree()).isEqualTo("http tree");
        assertThat(history.find("shared-trace", "KAFKA_CONSUMER").tree()).isEqualTo("kafka tree");
        assertThat(history.find("shared-trace", "KAFKA_CONSUMER").cpuTimeMs()).isEqualTo(3);
        assertThat(history.recent(10)).extracting(RecentTraceStore.TraceRecord::sourceType)
                .containsExactly("KAFKA_CONSUMER", "HTTP");
    }
}
