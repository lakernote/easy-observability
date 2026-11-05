package io.github.lakernote.observability.trace.internal;

import io.github.lakernote.observability.trace.SpanKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceTreeTest {

    @Test
    void shouldRenderTagAndLimitMaxDepth() {
        TraceTree trace = new TraceTree("HTTP", SpanKind.HTTP, "GET /api/users", 2, TraceTree.DEFAULT_MAX_NODES);

        Span service = trace.addChild(trace.rootSpan(), 1, "service", SpanKind.SERVICE, "userId=1");
        Span mapper = trace.addChild(service, 2, "mapper", SpanKind.DATABASE, "deptId=2");
        trace.stopSpan(mapper);
        trace.stopSpan(service);
        trace.completeRoot(0, null);

        String tree = trace.renderTree();

        assertThat(tree).contains("[Http] HTTP tag=\"GET /api/users\"");
        assertThat(tree).contains("[Service] service tag=\"userId=1\"");
        assertThat(tree).doesNotContain("mapper").contains("TRACE_TRUNCATED omitted=1");
        assertThat(trace.diagnosticReasons()).containsExactly("TRACE_TRUNCATED");
    }

    @Test
    void shouldResumeRecordingAfterReturningFromSkippedDepth() {
        TraceTree trace = new TraceTree("HTTP", SpanKind.HTTP, "GET /api/users", 2, TraceTree.DEFAULT_MAX_NODES);

        Span first = trace.addChild(trace.rootSpan(), 1, "first", SpanKind.SERVICE, "");
        Span skipped = trace.addChild(first, 2, "too-deep", SpanKind.DATABASE, "");
        assertThat(skipped).isNull();
        trace.stopSpan(first);
        Span second = trace.addChild(trace.rootSpan(), 1, "second", SpanKind.SERVICE, "");
        trace.stopSpan(second);
        trace.completeRoot(0, null);

        assertThat(trace.renderTree())
                .contains("first", "second", "TRACE_TRUNCATED omitted=1")
                .doesNotContain("too-deep");
        assertThat(trace.diagnosticReasons()).containsExactly("TRACE_TRUNCATED");
    }

    @Test
    void shouldAggregateOnlyConsecutiveDuplicateLeafSpans() throws Exception {
        TraceTree trace = new TraceTree("HTTP", SpanKind.HTTP, "GET /api/users");

        Span first = trace.addChild(trace.rootSpan(), 1, "SysUserMapper#selectById", SpanKind.DATABASE, "");
        Thread.sleep(1);
        trace.stopSpan(first);
        Span second = trace.addChild(trace.rootSpan(), 1, "SysUserMapper#selectById", SpanKind.DATABASE, "");
        Thread.sleep(2);
        trace.stopSpan(second);
        Span role = trace.addChild(trace.rootSpan(), 1, "SysRoleMapper#selectList", SpanKind.DATABASE, "");
        trace.stopSpan(role);
        Span third = trace.addChild(trace.rootSpan(), 1, "SysUserMapper#selectById", SpanKind.DATABASE, "");
        trace.stopSpan(third);
        trace.completeRoot(0, null);

        String tree = trace.renderTree();

        assertThat(tree)
                .contains("[Database] SysUserMapper#selectById [count=2 total=")
                .contains(" min=")
                .contains(" max=")
                .contains("] ")
                .contains("[Database] SysRoleMapper#selectList ")
                .doesNotContain("tag=\"\"");
        assertThat(occurrences(tree, "SysUserMapper#selectById")).isEqualTo(2);
    }

    @Test
    void shouldKeepLongTransactionAsRootLinkedTransactionSpan() {
        TraceTree trace = new TraceTree("HTTP", SpanKind.HTTP, "GET /orders");

        Span transaction = trace.addChild(trace.rootSpan(), 1, "OrderService#create", SpanKind.TRANSACTION, "write");
        transaction.stop();
        trace.markSlow("LONG_TRANSACTION");
        trace.completeRoot(0, null);

        assertThat(trace.renderTree()).contains("[Transaction] OrderService#create tag=\"write\"");
        assertThat(trace.slow()).isTrue();
        assertThat(trace.diagnosticReasons()).containsExactly("LONG_TRANSACTION");
    }

    @Test
    void shouldBoundTotalNodesAndReportOmittedSpans() {
        TraceTree trace = new TraceTree("HTTP", SpanKind.HTTP, "GET /loop", 8, 3);

        for (int index = 0; index < 10; index++) {
            Span loop = trace.addChild(trace.rootSpan(), 1, "loop-" + index, SpanKind.SERVICE, "");
            trace.stopSpan(loop);
        }
        Span detached = trace.addChild(trace.rootSpan(), 1, "transaction", SpanKind.TRANSACTION, "write");
        trace.completeRoot(0, null);

        assertThat(detached).isNull();
        assertThat(trace.renderTree())
                .contains("loop-0", "loop-1", "TRACE_TRUNCATED omitted=9 maxNodes=3")
                .doesNotContain("loop-2", "transaction");
        assertThat(trace.diagnosticReasons()).containsExactly("TRACE_TRUNCATED");
    }

    @Test
    void shouldStopRenderingAtHardCharacterLimitAndAppendMarker() {
        TraceTree trace = new TraceTree("x".repeat(TraceTree.MAX_RENDER_CHARS * 2), SpanKind.HTTP, "");
        Span child = trace.addChild(trace.rootSpan(), 1, "must-not-be-rendered-after-budget", SpanKind.SERVICE, "");
        trace.stopSpan(child);
        trace.completeRoot(0, null);

        String tree = trace.renderTree();

        assertThat(tree)
                .hasSize(TraceTree.MAX_RENDER_CHARS)
                .endsWith("[Trace] TRACE_OUTPUT_TRUNCATED maxChars=" + TraceTree.MAX_RENDER_CHARS)
                .doesNotContain("must-not-be-rendered-after-budget");
    }

    private static int occurrences(String text, String pattern) {
        return text.split(java.util.regex.Pattern.quote(pattern), -1).length - 1;
    }
}
