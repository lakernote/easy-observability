package io.github.lakernote.observability.trace.store;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.github.lakernote.observability.trace.TraceNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceRecordJsonCodecTest {
    private static final TraceRecordJsonCodec CODEC = TraceRecordJsonCodec.INSTANCE;
    private static final String TRICKY = "quote\" backslash\\ slash/ nl\n cr\r tab\t ctl\u0001 中文 emoji😀 lone\uD800 end";

    @Test
    void roundTripsEveryRecordAndNodeComponent() throws IOException {
        RecentTraceStore.TraceRecord trace = fullTrace();

        assertThat(CODEC.read(encode(trace))).isEqualTo(trace);
    }

    @Test
    void writesSingleLineStandardJsonWithCamelCaseKeysAndIsoInstant() throws IOException {
        String json = new String(encode(fullTrace()), StandardCharsets.UTF_8);

        assertThat(json).doesNotContain("\n", "\r").startsWith("{\"id\":42,\"traceId\":\"trace-1\"");
        DocumentContext document = JsonPath.parse(json);
        assertThat(document.read("$.finishedAt", String.class)).isEqualTo("2026-10-21T10:00:00.123Z");
        assertThat(document.read("$.errorMessage", String.class)).isEqualTo(TRICKY);
        assertThat(document.read("$.treeNodes[0].children[0].name", String.class)).isEqualTo("SQL select");
        assertThat(document.read("$.treeNodes[0].startOffsetMs", Object.class)).isNull();
        assertThat(document.read("$.highAllocation", Boolean.class)).isTrue();
    }

    @Test
    void archiveFormatIsStable() throws IOException {
        TraceNode node = new TraceNode("Database", "q\"1", "", 2, null, false, true, false, "", "w", 3, List.of());
        RecentTraceStore.TraceRecord trace = new RecentTraceStore.TraceRecord(1, "t", "n", "GET", 500, 9, true,
                false, List.of(node), Instant.parse("2026-10-21T10:00:00Z"), 4, 5, 6, "HTTP", "E", "m\n", "th", "",
                false, true);

        assertThat(new String(encode(trace), StandardCharsets.UTF_8)).isEqualTo("{\"id\":1,\"traceId\":\"t\","
                + "\"name\":\"n\",\"method\":\"GET\",\"status\":500,\"durationMs\":9,\"slow\":true,\"error\":false,"
                + "\"treeNodes\":[{\"kind\":\"Database\",\"name\":\"q\\\"1\",\"tag\":\"\",\"durationMs\":2,"
                + "\"startOffsetMs\":null,\"slow\":false,\"error\":true,\"unfinishedAtTraceEnd\":false,"
                + "\"errorType\":\"\",\"threadName\":\"w\",\"threadId\":3,\"children\":[]}],"
                + "\"finishedAt\":\"2026-10-21T10:00:00Z\",\"threadId\":4,\"cpuTimeMs\":5,\"allocatedBytes\":6,"
                + "\"sourceType\":\"HTTP\",\"errorType\":\"E\",\"errorMessage\":\"m\\n\",\"threadName\":\"th\","
                + "\"errorStackTrace\":\"\",\"highCpu\":false,\"highAllocation\":true}");
    }

    @Test
    void ignoresUnknownFieldsAndDefaultsMissingOnes() throws IOException {
        String json = "{ \"traceId\" : \"t-1\", \"future\": {\"nested\": [1, -2.5e3, true, null, \"x\", {}]},"
                + " \"treeNodes\": [{\"name\": \"n\", \"extra\": [[]]}], \"sourceType\": \"HTTP\", \"slow\": true }";

        RecentTraceStore.TraceRecord trace = CODEC.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(trace.traceId()).isEqualTo("t-1");
        assertThat(trace.sourceType()).isEqualTo("HTTP");
        assertThat(trace.slow()).isTrue();
        assertThat(trace.finishedAt()).isNull();
        assertThat(trace.errorMessage()).isEmpty();
        assertThat(trace.treeNodes()).singleElement().satisfies(node -> {
            assertThat(node.name()).isEqualTo("n");
            assertThat(node.kind()).isEqualTo("OTHER");
            assertThat(node.children()).isEmpty();
        });
    }

    @Test
    void readsUnicodeEscapesProducedByOtherJsonWriters() throws IOException {
        String json = "{\"traceId\":\"\\u4e2d\\u6587\\ud83d\\ude00\\/\"}";

        assertThat(CODEC.read(json.getBytes(StandardCharsets.UTF_8)).traceId()).isEqualTo("中文😀/");
    }

    @Test
    void rejectsMalformedLines() {
        for (String malformed : List.of("", "{not-json}", "{\"traceId\":\"open", "{\"traceId\":1}",
                "{\"status\":\"x\"}", "{\"finishedAt\":\"yesterday\"}", "{} trailing", "{\"slow\":maybe}",
                "{\"traceId\":\"\\q\"}", "[]")) {
            assertThatThrownBy(() -> CODEC.read(malformed.getBytes(StandardCharsets.UTF_8)))
                    .as(malformed)
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsPathologicallyDeepNestingWithoutStackOverflow() {
        String deepNodes = "{\"treeNodes\":" + "[{\"children\":".repeat(10_000);
        String deepUnknown = "{\"x\":" + "[".repeat(100_000);

        assertThatThrownBy(() -> CODEC.read(deepNodes.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class).hasMessageContaining("Nesting");
        assertThatThrownBy(() -> CODEC.read(deepUnknown.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class).hasMessageContaining("Nesting");
    }

    @Test
    void doesNotCloseTheTargetStream() throws IOException {
        CloseTrackingStream output = new CloseTrackingStream();

        CODEC.write(output, fullTrace());

        assertThat(output.closed).isFalse();
        assertThat(output.size()).isPositive();
    }

    private static byte[] encode(RecentTraceStore.TraceRecord trace) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CODEC.write(output, trace);
        return output.toByteArray();
    }

    private static RecentTraceStore.TraceRecord fullTrace() {
        TraceNode child = new TraceNode("Database", "SQL select", "jdbc", 2, 1L, true, false, true,
                "SQLTimeout", "worker-2", 78, List.of());
        TraceNode root = new TraceNode("Http", "HTTP GET", TRICKY, 845, null, true, true, false,
                "HTTP_5XX", "http-nio-1", 77, List.of(child));
        return new RecentTraceStore.TraceRecord(42, "trace-1", "GET /orders/{id}", "GET", 500, 845, true, true,
                List.of(root), Instant.parse("2026-10-21T10:00:00.123Z"), 77, Long.MAX_VALUE, Long.MIN_VALUE,
                "HTTP", "HTTP_5XX", TRICKY, "http-nio-1", "java.lang.IllegalStateException\n\tat x.y(Z.java:1)",
                false, true);
    }

    private static final class CloseTrackingStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
