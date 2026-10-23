package io.github.lakernote.observability.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpanRelationshipTest {
    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void detachedOperationsAttachToTheCurrentSpanAndExposeStableIds() {
        String rootId = TraceContext.startRootSpan("GET /orders", SpanKind.HTTP, "", 8, 0);
        TraceContext.addSpan("OrderService#load", SpanKind.SERVICE, "order");
        String serviceId = TraceContext.currentSpanId();

        TraceContext.DetachedSpan sql = TraceContext.startDetachedSpan("OrderMapper#select", SpanKind.DATABASE, "SELECT");

        assertThat(rootId).isEqualTo("1");
        assertThat(serviceId).isNotBlank().isNotEqualTo(rootId);
        assertThat(sql.spanId()).isNotBlank().isNotEqualTo(serviceId);
        assertThat(sql.parentSpanId()).isEqualTo(serviceId);
    }

    @Test
    void scopedOperationsNestAndRestoreTheirParentInCallOrder() {
        String rootId = TraceContext.startRootSpan("GET /orders", SpanKind.HTTP, "", 8, 0);
        TraceContext.ScopedSpan transaction = TraceContext.startScopedSpan(
                "OrderService#create", SpanKind.TRANSACTION, "write");
        TraceContext.ScopedSpan query = TraceContext.startScopedSpan(
                "OrderMapper#insert", SpanKind.DATABASE, "INSERT");

        assertThat(transaction.parentSpanId()).isEqualTo(rootId);
        assertThat(query.parentSpanId()).isEqualTo(transaction.spanId());

        query.close();
        assertThat(TraceContext.currentSpanId()).isEqualTo(transaction.spanId());
        transaction.close();
        assertThat(TraceContext.currentSpanId()).isEqualTo(rootId);

        TraceContext.markSlow("test");
        var completion = new java.util.concurrent.atomic.AtomicReference<TraceContext.Completion>();
        TraceContext.stopRoot(Long.MAX_VALUE, "test", null, completion::set);
        assertThat(completion.get().tree()).contains("OrderService#create", "OrderMapper#insert");
        assertThat(completion.get().tree().indexOf("OrderService#create"))
                .isLessThan(completion.get().tree().indexOf("OrderMapper#insert"));
    }
}
