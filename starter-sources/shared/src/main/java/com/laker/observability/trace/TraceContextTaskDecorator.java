package com.laker.observability.trace;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 在线程池任务边界传播 Trace ID 和 MDC。
 * <p>任务结束后恢复线程池线程原有的 MDC，防止复用线程把上一个请求的上下文带给下一个任务。</p>
 * <p>此装饰器不跨线程共享 Trace 树；异步操作需要由框架 Observation 或应用自己的入口管理生命周期。</p>
 */
public final class TraceContextTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable task) {
        // 在提交任务的线程捕获上下文，在执行线程安装副本，最后清理本次任务写入的 Trace ID。
        Map<String, String> context = MdcContext.copy();
        String traceId = TraceIdContext.currentTraceId();
        return () -> {
            try (MdcContext.Scope ignored = MdcContext.withContext(context)) {
                if (TraceIdContext.isValidTraceId(traceId)) {
                    TraceIdContext.putTraceId(traceId);
                } else {
                    TraceIdContext.getOrCreateTraceId();
                }
                try {
                    task.run();
                } finally {
                    TraceIdContext.clear();
                }
            }
        };
    }
}
