package io.github.lakernote.observability.trace.store;

import org.junit.jupiter.api.Test;
import io.github.lakernote.observability.trace.OperationRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecentTraceStoreTest {
    @Test
    void onlyKeepsInboundEntryPointsAsTraceRoots() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("http-1", "GET /orders", "GET", "HTTP", 200, 8, false, false, "");
        history.add("redis-1", "Redis get", "get", "REDIS", 200, 1, false, false, "");
        history.add("transaction-1", "OrderService#create", "write", "TRANSACTION",
                200, 4, false, false, "");

        assertThat(history.recent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("http-1");
        assertThat(history.find("redis-1")).isNull();
        assertThat(history.find("transaction-1")).isNull();
    }

    @Test
    void keepsOnlyTheNewestRecordsAndFindsTraceById() {
        RecentTraceStore history = new RecentTraceStore(2);
        history.add("trace-1", "first", "GET /first", 200, 5, false, false, "");
        history.add("trace-2", "second", "GET /second", 500, 40, true, true, "tree");
        history.add("trace-3", "third", "GET /third", 200, 7, false, false, "");

        List<RecentTraceStore.TraceRecord> records = history.recent(10);

        assertThat(records).extracting(RecentTraceStore.TraceRecord::traceId).containsExactly("trace-3", "trace-2");
        assertThat(history.find("trace-2").tree()).isEqualTo("tree");
        assertThat(history.find("trace-1")).isNull();
    }

    @Test
    void includesRootOperationInRecentListForStatisticDrilldown() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add("job-trace", "nightly cleanup", "", "JOB", 200, 120, true, false, "tree");
        history.addOperation(new OperationRecord("JOB", "job-trace", "tasks.scheduled.execution", "worker-1",
                7, "success", 120, 3, 4096, -1, -1, -1, -1, "", "", "", true, false,
                List.of(), "", "", Map.of(), Instant.now(), "1", ""));

        RecentTraceStore.TraceRecord recent = history.recent(10).get(0);

        assertThat(recent.name()).isEqualTo("nightly cleanup");
        assertThat(recent.operations()).extracting(OperationRecord::name)
                .containsExactly("tasks.scheduled.execution");
        assertThat(history.find("job-trace").operations()).hasSize(1);
    }

    @Test
    void selectsTheRequestedEntryWhenKafkaAndHttpSegmentsShareATraceId() {
        RecentTraceStore history = new RecentTraceStore(10);
        Instant httpFinished = Instant.parse("2026-10-21T10:00:01Z");
        Instant kafkaFinished = Instant.parse("2026-10-21T10:00:02Z");
        history.add("shared-trace", "/api/demo/real-flow", "GET", "HTTP", 200, 500,
                true, false, "http-tree");
        history.add("shared-trace", "receive", "", "KAFKA_CONSUMER", 200, 20,
                false, false, "kafka-tree");
        history.addOperation(operation("REMOTE", "http.client.requests", 1, 100,
                httpFinished.minusMillis(100), "2", "1"));
        history.addOperation(operation("KAFKA_PRODUCER", "publish", 1, 50,
                httpFinished.minusMillis(50), "3", "1"));
        history.addOperation(operation("DATABASE", "consumer.handle", 2, 10,
                kafkaFinished.minusMillis(10), "2", "1"));
        history.addOperation(operation("KAFKA_CONSUMER", "receive", 2, 20, kafkaFinished, "1", ""));
        history.addOperation(operation("HTTP", "http.server.requests", 1, 500, httpFinished, "1", ""));

        assertThat(history.find("shared-trace").sourceType()).isEqualTo("HTTP");
        assertThat(history.find("shared-trace").tree()).isEqualTo("http-tree");
        assertThat(history.find("shared-trace", "HTTP").operations())
                .extracting(OperationRecord::name)
                .containsExactly("http.server.requests", "publish", "http.client.requests");
        assertThat(history.find("shared-trace", "KAFKA_CONSUMER").tree()).isEqualTo("kafka-tree");
        assertThat(history.find("shared-trace", "KAFKA_CONSUMER").operations())
                .extracting(OperationRecord::name).containsExactly("receive", "consumer.handle");
        assertThat(history.recent(10)).extracting(RecentTraceStore.TraceRecord::sourceType)
                .containsExactly("KAFKA_CONSUMER", "HTTP");
        assertThat(history.recent(10).get(0).operations().get(0).type()).isEqualTo("KAFKA_CONSUMER");
    }

    private static OperationRecord operation(String type, String name, String spanId, String parentSpanId) {
        return new OperationRecord(type, "shared-trace", name, "worker-1", 1, "success", 1,
                1, 1, -1, -1, -1, -1, "", "", "", false, false, List.of(), "", "", Map.of(),
                Instant.now(), spanId, parentSpanId);
    }

    private static OperationRecord operation(String type, String name, long threadId, long durationMs,
                                             Instant finishedAt, String spanId, String parentSpanId) {
        return new OperationRecord(type, "shared-trace", name, "worker-" + threadId, threadId, "success", durationMs,
                1, 1, -1, -1, -1, -1, "", "", "", false, false, List.of(), "", "", Map.of(),
                finishedAt, spanId, parentSpanId);
    }
}
