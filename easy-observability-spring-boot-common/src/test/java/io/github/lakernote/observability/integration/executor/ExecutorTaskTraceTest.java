package io.github.lakernote.observability.integration.executor;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.TraceIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutorTaskTraceTest {
    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
        TraceIdContext.clear();
    }

    @Test
    void addsTaskSpanToTheActiveTrace() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        TraceIdContext.putTraceId("trace-123");
        TraceContext.startRootSpan("request", SpanKind.HTTP, "", 8, 0);
        ExecutorTaskTrace trace = new ExecutorTaskTrace(properties);

        assertThat(trace.observe("worker-pool", "invoice.reconcile", () -> "done")).isEqualTo("done");
        // Successful traces keep the tree unrendered; slow/error traces materialize it on completion.
        var completion = stopRoot();

        assertThat(completion.tree()).isEmpty();
    }

    @Test
    void marksRootTraceFailedAndRethrowsTaskFailure() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        TraceIdContext.putTraceId("trace-123");
        TraceContext.startRootSpan("request", SpanKind.HTTP, "", 8, 0);
        ExecutorTaskTrace trace = new ExecutorTaskTrace(properties);
        IllegalStateException failure = new IllegalStateException("task failed");

        assertThatThrownBy(() -> trace.observe("worker-pool", "invoice.reconcile", () -> {
            throw failure;
        })).isSameAs(failure);
        var completion = stopRoot();

        assertThat(completion.error()).isTrue();
        assertThat(completion.errorMessage()).isEqualTo("task failed");
        assertThat(completion.tree()).contains("invoice.reconcile");
    }

    @Test
    void doesNotCreateAnOrphanTraceWhenNoTraceIsActive() {
        ExecutorTaskTrace trace = new ExecutorTaskTrace(new EasyObservabilityProperties());

        trace.observe("worker-pool", "invoice.reconcile", () -> { });

        assertThat(TraceContext.active()).isFalse();
    }

    private static TraceContext.Completion stopRoot() {
        AtomicReference<TraceContext.Completion> completion = new AtomicReference<>();
        TraceContext.stopRoot(Long.MAX_VALUE, "test", null, completion::set);
        return completion.get();
    }
}
