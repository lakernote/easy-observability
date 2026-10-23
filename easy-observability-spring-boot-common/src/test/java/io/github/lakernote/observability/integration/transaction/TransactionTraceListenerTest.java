package io.github.lakernote.observability.integration.transaction;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.TraceIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionExecution;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionTraceListenerTest {
    @AfterEach
    void clearContext() {
        TraceIdContext.clear();
    }

    @Test
    void attachesTransactionAsSingleChildOfExistingHttpTrace() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        AtomicLong clock = new AtomicLong();
        TransactionTraceListener listener = new TransactionTraceListener(properties, clock::get);
        TransactionExecution execution = execution("OrderService#create", true);
        io.github.lakernote.observability.trace.TraceContext.startRoot("POST /orders",
                io.github.lakernote.observability.trace.SpanKind.HTTP, "", 8, 0);

        listener.beforeBegin(execution);
        String transactionSpanId = io.github.lakernote.observability.trace.TraceContext.currentSpanId();
        var connection = io.github.lakernote.observability.trace.TraceContext.startScopedSpan(
                "getConnection", io.github.lakernote.observability.trace.SpanKind.DATABASE, "");
        assertThat(connection.parentSpanId()).isEqualTo(transactionSpanId);
        connection.close();
        listener.afterBegin(execution, null);
        var query = io.github.lakernote.observability.trace.TraceContext.startScopedSpan(
                "OrderMapper#insert", io.github.lakernote.observability.trace.SpanKind.DATABASE, "INSERT");
        assertThat(query.parentSpanId()).isEqualTo(transactionSpanId);
        query.close();
        clock.addAndGet(20_000_000L);
        listener.afterCommit(execution, null);
        java.util.concurrent.atomic.AtomicReference<io.github.lakernote.observability.trace.TraceContext.Completion> completion =
                new java.util.concurrent.atomic.AtomicReference<>();
        io.github.lakernote.observability.trace.TraceContext.markSlow("TEST_CAPTURE");
        io.github.lakernote.observability.trace.TraceContext.stopRoot(0, "test", null, completion::set);

        assertThat(completion.get().tree()).contains("OrderService#create", "getConnection", "OrderMapper#insert");
        assertThat(completion.get().tree().indexOf("OrderService#create"))
                .isLessThan(completion.get().tree().indexOf("getConnection"));
        assertThat(completion.get().tree().indexOf("getConnection"))
                .isLessThan(completion.get().tree().indexOf("OrderMapper#insert"));
    }

    @Test
    void closesTransactionSpanWhenBeginFails() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        TransactionTraceListener listener = new TransactionTraceListener(properties, () -> 0L);
        TransactionExecution execution = execution("OrderService#create", false);
        io.github.lakernote.observability.trace.TraceContext.startRoot("POST /orders",
                io.github.lakernote.observability.trace.SpanKind.HTTP, "", 8, 0);
        String rootSpanId = io.github.lakernote.observability.trace.TraceContext.currentSpanId();

        listener.beforeBegin(execution);
        assertThat(io.github.lakernote.observability.trace.TraceContext.currentSpanId()).isNotEqualTo(rootSpanId);
        listener.afterBegin(execution, new IllegalStateException("connection unavailable"));

        assertThat(io.github.lakernote.observability.trace.TraceContext.currentSpanId()).isEqualTo(rootSpanId);
        java.util.concurrent.atomic.AtomicReference<io.github.lakernote.observability.trace.TraceContext.Completion> completion =
                new java.util.concurrent.atomic.AtomicReference<>();
        io.github.lakernote.observability.trace.TraceContext.stopRoot(0, "test", null, completion::set);
        assertThat(completion.get().tree()).contains("OrderService#create");
        assertThat(completion.get().error()).isTrue();
        assertThat(completion.get().errorMessage()).isEqualTo("connection unavailable");
    }

    private static TransactionExecution execution(String name, boolean readOnly) {
        TransactionExecution execution = mock(TransactionExecution.class);
        when(execution.getTransactionName()).thenReturn(name);
        when(execution.isReadOnly()).thenReturn(readOnly);
        return execution;
    }
}
