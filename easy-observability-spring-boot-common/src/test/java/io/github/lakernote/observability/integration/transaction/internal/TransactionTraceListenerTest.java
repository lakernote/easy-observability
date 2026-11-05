package io.github.lakernote.observability.integration.transaction.internal;

import io.github.lakernote.observability.trace.internal.TraceIds;
import io.github.lakernote.observability.trace.internal.TraceCompletion;
import io.github.lakernote.observability.config.EasyObservabilityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionExecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionTraceListenerTest {
    @AfterEach
    void clearContext() {
        TraceIds.clear();
    }

    @Test
    void attachesTransactionAsSingleChildOfExistingHttpTrace() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        TransactionTraceListener listener = new TransactionTraceListener(properties);
        TransactionExecution execution = execution("OrderService#create", true);
        io.github.lakernote.observability.trace.internal.TraceContext.startRoot("POST /orders",
                io.github.lakernote.observability.trace.SpanKind.HTTP);

        listener.beforeBegin(execution);
        String transactionSpanId = io.github.lakernote.observability.trace.internal.TraceContext.currentSpanId();
        var connection = io.github.lakernote.observability.trace.internal.TraceContext.startScopedSpan(
                "getConnection", io.github.lakernote.observability.trace.SpanKind.DATABASE, "");
        assertThat(connection.parentSpanId()).isEqualTo(transactionSpanId);
        connection.close();
        listener.afterBegin(execution, null);
        var query = io.github.lakernote.observability.trace.internal.TraceContext.startScopedSpan(
                "OrderMapper#insert", io.github.lakernote.observability.trace.SpanKind.DATABASE, "INSERT");
        assertThat(query.parentSpanId()).isEqualTo(transactionSpanId);
        query.close();
        listener.afterCommit(execution, null);
        java.util.concurrent.atomic.AtomicReference<io.github.lakernote.observability.trace.internal.TraceCompletion> completion =
                new java.util.concurrent.atomic.AtomicReference<>();
        io.github.lakernote.observability.trace.internal.TraceContext.markSlow("TEST_CAPTURE");
        io.github.lakernote.observability.trace.internal.TraceContext.stopRoot(0, "test", null, completion::set);

        assertThat(completion.get().tree()).contains("OrderService#create", "getConnection", "OrderMapper#insert");
        assertThat(completion.get().tree().indexOf("OrderService#create"))
                .isLessThan(completion.get().tree().indexOf("getConnection"));
        assertThat(completion.get().tree().indexOf("getConnection"))
                .isLessThan(completion.get().tree().indexOf("OrderMapper#insert"));
    }

    @Test
    void closesTransactionSpanWhenBeginFails() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        TransactionTraceListener listener = new TransactionTraceListener(properties);
        TransactionExecution execution = execution("OrderService#create", false);
        io.github.lakernote.observability.trace.internal.TraceContext.startRoot("POST /orders",
                io.github.lakernote.observability.trace.SpanKind.HTTP);
        String rootSpanId = io.github.lakernote.observability.trace.internal.TraceContext.currentSpanId();

        listener.beforeBegin(execution);
        assertThat(io.github.lakernote.observability.trace.internal.TraceContext.currentSpanId()).isNotEqualTo(rootSpanId);
        listener.afterBegin(execution, new IllegalStateException("connection unavailable"));

        assertThat(io.github.lakernote.observability.trace.internal.TraceContext.currentSpanId()).isEqualTo(rootSpanId);
        java.util.concurrent.atomic.AtomicReference<io.github.lakernote.observability.trace.internal.TraceCompletion> completion =
                new java.util.concurrent.atomic.AtomicReference<>();
        io.github.lakernote.observability.trace.internal.TraceContext.stopRoot(0, "test", null, completion::set);
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
