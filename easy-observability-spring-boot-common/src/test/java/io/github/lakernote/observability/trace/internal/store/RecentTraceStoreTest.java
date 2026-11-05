package io.github.lakernote.observability.trace.internal.store;

import io.github.lakernote.observability.trace.internal.TraceNodeFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentTraceStoreTest {
    @Test
    void onlyKeepsInboundEntryPointsAsTraceRoots() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace("http-1", "HTTP").build());
        assertThat(history.add(trace("redis-1", "REDIS").build())).isNull();
        assertThat(history.add(trace("transaction-1", "TRANSACTION").build())).isNull();

        assertThat(history.recent(10)).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("http-1");
        assertThat(history.find("redis-1", "")).isNull();
        assertThat(history.find("transaction-1", "")).isNull();
    }

    @Test
    void keepsOnlyNewestTreesAndStoresEntryResourcesOnTheTrace() {
        RecentTraceStore history = new RecentTraceStore(2);
        history.add(trace("trace-1", "HTTP").build());
        history.add(trace("trace-2", "HTTP").status(500).durationMs(40).slow(true).error(true)
                .treeNodes(TraceNodeFixtures.named("second tree"))
                .threadId(12).cpuTimeMs(8).allocatedBytes(4096)
                .errorType("HTTP_500").errorMessage("request failed").build());
        history.add(trace("trace-3", "HTTP").build());

        List<RecentTraceStore.TraceRecord> records = history.recent(10);

        assertThat(records).extracting(RecentTraceStore.TraceRecord::traceId)
                .containsExactly("trace-3", "trace-2");
        assertThat(history.find("trace-2", "")).satisfies(trace -> {
            assertThat(trace.treeNodes()).extracting("name").containsExactly("second tree");
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
    void assignsIdentityAndReturnsTheStoredRecord() {
        RecentTraceStore history = new RecentTraceStore(10);
        Instant before = Instant.now();

        RecentTraceStore.TraceRecord first = history.add(trace("trace-1", "HTTP").id(99)
                .finishedAt(Instant.EPOCH).durationMs(-5).build());
        RecentTraceStore.TraceRecord second = history.add(trace("trace-2", "JOB").build());

        assertThat(first.id()).isEqualTo(1);
        assertThat(second.id()).isEqualTo(2);
        assertThat(first.finishedAt()).isAfterOrEqualTo(before);
        assertThat(first.durationMs()).isZero();
        assertThat(history.find("trace-1", "HTTP")).isSameAs(first);
    }

    @Test
    void builderDefaultsDescribeUnknownResourcesAndNoFailure() {
        RecentTraceStore.TraceRecord trace = RecentTraceStore.TraceRecord.builder().traceId("t").build();

        assertThat(trace.threadId()).isEqualTo(-1);
        assertThat(trace.cpuTimeMs()).isEqualTo(-1);
        assertThat(trace.allocatedBytes()).isEqualTo(-1);
        assertThat(trace.treeNodes()).isEmpty();
        assertThat(trace.sourceType()).isEmpty();
        assertThat(trace.errorType()).isEmpty();
        assertThat(trace.errorMessage()).isEmpty();
        assertThat(trace.errorStackTrace()).isEmpty();
        assertThat(trace.threadName()).isEmpty();
    }

    @Test
    void selectsRequestedEntrySourceWhenKafkaAndHttpShareTraceId() {
        RecentTraceStore history = new RecentTraceStore(10);
        history.add(trace("shared-trace", "HTTP").treeNodes(TraceNodeFixtures.named("http tree"))
                .cpuTimeMs(12).build());
        history.add(trace("shared-trace", "KAFKA_CONSUMER").treeNodes(TraceNodeFixtures.named("kafka tree"))
                .cpuTimeMs(3).build());

        assertThat(history.find("shared-trace", "").sourceType()).isEqualTo("HTTP");
        assertThat(history.find("shared-trace", "").treeNodes()).extracting("name").containsExactly("http tree");
        assertThat(history.find("shared-trace", "KAFKA_CONSUMER").treeNodes()).extracting("name")
                .containsExactly("kafka tree");
        assertThat(history.find("shared-trace", "KAFKA_CONSUMER").cpuTimeMs()).isEqualTo(3);
        assertThat(history.recent(10)).extracting(RecentTraceStore.TraceRecord::sourceType)
                .containsExactly("KAFKA_CONSUMER", "HTTP");
    }

    private static RecentTraceStore.TraceRecord.Builder trace(String traceId, String sourceType) {
        return RecentTraceStore.TraceRecord.builder().traceId(traceId).sourceType(sourceType)
                .name(traceId).method("GET").status(200).durationMs(5)
                .treeNodes(TraceNodeFixtures.named(traceId + " tree"));
    }
}
