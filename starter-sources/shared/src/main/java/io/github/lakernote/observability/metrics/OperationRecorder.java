package io.github.lakernote.observability.metrics;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.OperationRecord;
import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一保留诊断事件，是框架接入层与 Trace 存储之间的边界。
 * <p>记录过程失败只影响诊断数据，不得中断被观测的业务调用。</p>
 */
public final class OperationRecorder {
    private static final Logger log = LoggerFactory.getLogger(OperationRecorder.class);
    private final RecentTraceStore recentTraces;
    private final EasyObservabilityProperties properties;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public OperationRecorder(RecentTraceStore recentTraces, EasyObservabilityProperties properties) {
        this.recentTraces = recentTraces;
        this.properties = properties;
    }

    /** 诊断记录失败不向外抛出，不影响被观测的业务操作。 */
    public void record(OperationRecord operation) {
        record(operation, TraceContext.active());
    }

    /** Records operation details when their owning trace is active, even if completion runs on another thread. */
    public void record(OperationRecord operation, boolean traceActive) {
        if (operation == null) return;
        if (properties.getTrace().isEnabled() && traceActive) {
            safely("保留 Trace 操作", () -> recentTraces.addOperation(operation));
        }
    }

    private void safely(String actionName, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("Easy Observability {}失败；业务操作会继续执行", actionName, failure);
            }
        }
    }

}
