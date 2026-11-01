package io.github.lakernote.observability.trace;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在线程池任务边界传播 Trace 执行上下文和 MDC。
 * <p>任务线程继承提交时的当前 Span；任务结束后恢复线程池线程原有的上下文。</p>
 */
public final class TraceContextTaskDecorator implements TaskDecorator {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TraceContextTaskDecorator.class);
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    @Override
    public Runnable decorate(Runnable task) {
        Objects.requireNonNull(task, "task");
        // ExecutionFrame 不可变，引用的 TraceTree 用同步方法保护跨线程的节点变更。
        Map<String, String> context = MdcContext.copy();
        TraceContext.Snapshot traceSnapshot = TraceContext.capture();
        return () -> {
            try (MdcContext.Scope ignored = MdcContext.withContext(context);
                 TraceContext.Scope traceScope = TraceContext.makeCurrent(traceSnapshot)) {
                // 无父 Trace 的独立任务仍生成 Trace ID，便于日志和下游调用关联。
                if (!TraceIdContext.isValidTraceId(TraceIdContext.currentTraceId())) {
                    TraceIdContext.getOrCreateTraceId();
                }
                runTask(task);
            }
        };
    }

    private void runTask(Runnable task) {
        if (!TraceContext.active()) {
            task.run();
            return;
        }

        TraceContext.RootTraceHandle trace = TraceContext.currentRootTraceHandle();
        TraceContext.ScopedSpan span;
        try {
            // 装饰器无法获知业务任务名，统一使用稳定的边界名称。
            span = TraceContext.startScopedSpan("executor.task", SpanKind.CODE, Thread.currentThread().getName());
        } catch (RuntimeException instrumentationFailure) {
            logFailure(instrumentationFailure);
            task.run();
            return;
        }

        Throwable failure = null;
        try {
            task.run();
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            try {
                if (failure == null) failure = completedFutureFailure(task);
                if (failure != null) {
                    BoundedThrowableFormatter.FailureEvidence evidence = BoundedThrowableFormatter.capture(failure);
                    span.markError(evidence);
                    if (trace != null) trace.markError("EXECUTOR_TASK_ERROR", evidence);
                }
            } catch (RuntimeException instrumentationFailure) {
                logFailure(instrumentationFailure);
            } finally {
                try {
                    span.close();
                } catch (RuntimeException instrumentationFailure) {
                    logFailure(instrumentationFailure);
                }
            }
        }
    }

    /** FutureTask 会吸收 run() 中的异常，因此在任务结束后读取已完成 Future 的结果。 */
    private static Throwable completedFutureFailure(Runnable task) {
        if (!(task instanceof Future<?> future) || !future.isDone()) return null;
        try {
            future.get();
            return null;
        } catch (ExecutionException failure) {
            return failure.getCause() == null ? failure : failure.getCause();
        } catch (CancellationException failure) {
            return failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void logFailure(RuntimeException failure) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("Executor task Trace recording failed; task result is unchanged ({})",
                    failure.getClass().getName());
        }
    }
}
