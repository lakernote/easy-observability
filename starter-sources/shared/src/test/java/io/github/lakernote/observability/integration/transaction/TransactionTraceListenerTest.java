package io.github.lakernote.observability.integration.transaction;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.metrics.OperationRecorder;
import io.github.lakernote.observability.trace.OperationRecord;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionExecution;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionTraceListenerTest {
    @AfterEach
    void clearContext() {
        TraceIdContext.clear();
    }

    @Test
    void recordsLongTransactionAndActiveGaugeAtSpringTransactionBoundary() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        properties.getTransaction().setSlowThresholdMs(1_000);
        AtomicLong clock = new AtomicLong();
        RecentTraceStore history = new RecentTraceStore(10, 10);
        OperationRecorder recorder = new OperationRecorder(meters, history, properties);
        TransactionTraceListener listener = new TransactionTraceListener(recorder, meters, properties, clock::get);
        TransactionExecution execution = execution("OrderService#create", false);

        listener.beforeBegin(execution);
        clock.set(10_000_000_000L);
        listener.afterBegin(execution, null);
        assertThat(meters.get("easy.transactions.active").gauge().value()).isEqualTo(1);
        clock.addAndGet(1_200_000_000L);
        listener.afterCommit(execution, null);

        assertThat(history.recent(10)).isEmpty();
        assertThat(meters.get("easy.transactions").tag("result", "success").tag("read_only", "false")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("easy.transactions.active").gauge().value()).isZero();
        assertThat(history.recent(10)).isEmpty();
    }

    @Test
    void attachesTransactionAsSingleChildOfExistingHttpTrace() {
        OperationRecorder recorder = mock(OperationRecorder.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        AtomicLong clock = new AtomicLong();
        TransactionTraceListener listener = new TransactionTraceListener(recorder, meters, properties, clock::get);
        TransactionExecution execution = execution("OrderService#create", true);
        io.github.lakernote.observability.trace.TraceContext.startRoot("POST /orders",
                io.github.lakernote.observability.trace.SpanKind.HTTP, "", 8, 0);

        listener.beforeBegin(execution);
        String transactionSpanId = io.github.lakernote.observability.trace.TraceContext.currentSpanId();
        var connection = io.github.lakernote.observability.trace.TraceContext.startScopedSpan(
                "getConnection", io.github.lakernote.observability.trace.SpanKind.DATABASE, "");
        assertThat(connection.parentSpanId()).isEqualTo(transactionSpanId);
        connection.run();
        listener.afterBegin(execution, null);
        var query = io.github.lakernote.observability.trace.TraceContext.startScopedSpan(
                "OrderMapper#insert", io.github.lakernote.observability.trace.SpanKind.DATABASE, "INSERT");
        assertThat(query.parentSpanId()).isEqualTo(transactionSpanId);
        query.run();
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
        verify(recorder).record(any(OperationRecord.class));
    }

    private static TransactionExecution execution(String name, boolean readOnly) {
        TransactionExecution execution = mock(TransactionExecution.class);
        when(execution.getTransactionName()).thenReturn(name);
        when(execution.isReadOnly()).thenReturn(readOnly);
        return execution;
    }
}
