package io.github.lakernote.observability.trace;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 在线程池任务边界传播 Trace 执行上下文和 MDC。
 * <p>任务线程继承提交时的当前 Span；任务结束后恢复线程池线程原有的上下文。</p>
 */
public final class TraceContextTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable task) {
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
                task.run();
            }
        };
    }
}
