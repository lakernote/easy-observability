package io.github.lakernote.observability.integration.executor;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.OperationRecord;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.github.lakernote.observability.trace.TraceOperationRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentCaptor.forClass;

class ExecutorTaskTraceTest {
    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
        TraceIdContext.clear();
    }

    @Test
    void recordsTaskDetailsOnTheActiveTraceWithoutMicrometer() {
        TraceOperationRecorder recorder = mock(TraceOperationRecorder.class);
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        TraceIdContext.putTraceId("trace-123");
        TraceContext.startRootSpan("request", SpanKind.HTTP, "", 8, 0);
        ExecutorTaskTrace trace = new ExecutorTaskTrace(recorder, properties);

        trace.observe("worker-pool", "invoice.reconcile", () -> "done");

        var operation = forClass(OperationRecord.class);
        verify(recorder).record(operation.capture(), eq(true));
        assertThat(operation.getValue()).satisfies(record -> {
            assertThat(record.type()).isEqualTo("EXECUTOR_TASK");
            assertThat(record.traceId()).isEqualTo("trace-123");
            assertThat(record.name()).isEqualTo("invoice.reconcile");
            assertThat(record.detail()).isEqualTo("worker-pool");
            assertThat(record.outcome()).isEqualTo("success");
            assertThat(record.spanId()).isNotBlank();
        });
    }

    @Test
    void recordsFailureAndRethrowsIt() {
        TraceOperationRecorder recorder = mock(TraceOperationRecorder.class);
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        TraceIdContext.putTraceId("trace-123");
        TraceContext.startRootSpan("request", SpanKind.HTTP, "", 8, 0);
        ExecutorTaskTrace trace = new ExecutorTaskTrace(recorder, properties);
        IllegalStateException failure = new IllegalStateException("task failed");

        assertThatThrownBy(() -> trace.observe("worker-pool", "invoice.reconcile", () -> {
            throw failure;
        })).isSameAs(failure);

        var operation = forClass(OperationRecord.class);
        verify(recorder).record(operation.capture(), eq(true));
        assertThat(operation.getValue().error()).isTrue();
        assertThat(operation.getValue().errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(operation.getValue().errorMessage()).isEqualTo("task failed");
    }

    @Test
    void doesNotRecordAnOrphanTaskWhenNoTraceIsActive() {
        TraceOperationRecorder recorder = mock(TraceOperationRecorder.class);
        ExecutorTaskTrace trace = new ExecutorTaskTrace(recorder, new EasyObservabilityProperties());

        trace.observe("worker-pool", "invoice.reconcile", () -> { });

        verifyNoInteractions(recorder);
    }
}
