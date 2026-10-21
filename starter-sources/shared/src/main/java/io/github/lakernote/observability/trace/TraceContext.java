package io.github.lakernote.observability.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 管理 Trace 树和当前执行位置。
 * <p>ThreadLocal 只保存本线程当前的不可变执行帧；异步任务通过 Snapshot 显式继承树和父 Span。</p>
 */
public final class TraceContext {
    private static final Logger log = LoggerFactory.getLogger(TraceContext.class);
    static final int MAX_INCIDENT_LOGS_PER_WINDOW = 20;
    static final long INCIDENT_LOG_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_LOG_DESCRIPTION_CHARS = 256;
    private static final int MAX_LOG_REASON_CHARS = 256;
    private static final ThreadLocal<ExecutionFrame> CURRENT_FRAME = new ThreadLocal<>();
    /** 错误和慢调用使用独立日志额度，避免慢调用风暴挤掉所有错误摘要。 */
    private static final IncidentLogLimiter ERROR_LOG_LIMITER =
            new IncidentLogLimiter(MAX_INCIDENT_LOGS_PER_WINDOW, INCIDENT_LOG_WINDOW_NANOS);
    private static final IncidentLogLimiter SLOW_LOG_LIMITER =
            new IncidentLogLimiter(MAX_INCIDENT_LOGS_PER_WINDOW, INCIDENT_LOG_WINDOW_NANOS);

    private TraceContext() {
    }

    /** 当前线程是否存在尚未结束的 Trace。 */
    public static boolean active() {
        return currentTrace() != null;
    }

    /** 当前没有 Trace 时创建根节点；已有 Trace 时只追加子节点。 */
    public static void startRoot(String name, SpanKind kind) {
        startRootSpan(name, kind, "", 8, 0);
    }

    public static void startRoot(String name, SpanKind kind, String tag, int maxDepth, long minNodeCostMs) {
        startRootSpan(name, kind, tag, maxDepth, minNodeCostMs);
    }

    /** 创建根节点并返回 Span ID，供集成适配器记录明确的父子关系。 */
    public static String startRootSpan(String name, SpanKind kind, String tag, int maxDepth, long minNodeCostMs) {
        ExecutionFrame current = currentFrame();
        if (current == null) {
            TraceTree trace = new TraceTree(name, kind, tag, maxDepth, minNodeCostMs);
            Span root = trace.rootSpan();
            CURRENT_FRAME.set(new ExecutionFrame(trace, root, 1, null, null, TraceIdContext.currentTraceId()));
            return root.spanId();
        }
        Span child = addChild(current, name, kind, tag);
        CURRENT_FRAME.set(new ExecutionFrame(current.trace, child == null ? current.span : child,
                child == null ? current.depth : current.depth + 1, child, current, current.traceId));
        return child == null ? "" : child.spanId();
    }

    /** Captures the current Trace ID and parent Span for propagation across an async boundary. */
    public static Snapshot capture() {
        return new Snapshot(currentFrame(), TraceIdContext.currentTraceId());
    }

    /** Makes a captured context current and restores the previous thread context when closed. */
    public static Scope makeCurrent(Snapshot snapshot) {
        ExecutionFrame previous = CURRENT_FRAME.get();
        String previousTraceId = TraceIdContext.currentTraceId();
        ExecutionFrame captured = snapshot == null ? null : snapshot.frame;
        boolean completed = captured != null && captured.trace.completed();
        if (completed) captured = null;
        if (captured == null) CURRENT_FRAME.remove();
        else CURRENT_FRAME.set(captured);
        String traceId = snapshot == null || completed ? null : snapshot.traceId;
        MdcContext.putOrRemove(TraceIdContext.MDC_KEY,
                TraceIdContext.isValidTraceId(traceId) ? traceId : null);
        return new Scope(previous, previousTraceId);
    }

    public static final class Snapshot {
        private final ExecutionFrame frame;
        private final String traceId;

        private Snapshot(ExecutionFrame frame, String traceId) {
            this.frame = frame;
            this.traceId = TraceIdContext.isValidTraceId(traceId) ? traceId : null;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final ExecutionFrame previous;
        private final String previousTraceId;
        private final long ownerThreadId = Thread.currentThread().getId();
        private boolean closed;

        private Scope(ExecutionFrame previous, String previousTraceId) {
            this.previous = previous;
            this.previousTraceId = previousTraceId;
        }

        @Override
        public void close() {
            if (closed) return;
            if (ownerThreadId != Thread.currentThread().getId()) {
                throw new IllegalStateException("Trace context scope must close on its owner thread");
            }
            if (previous == null) CURRENT_FRAME.remove();
            else CURRENT_FRAME.set(previous);
            MdcContext.putOrRemove(TraceIdContext.MDC_KEY, previousTraceId);
            closed = true;
        }
    }

    /** Returns a handle to the active tree so an Observation can update or finish it on another thread. */
    public static RootTraceHandle currentRootTraceHandle() {
        TraceTree trace = currentTrace();
        return trace == null ? null : new RootTraceHandle(trace);
    }

    /** Detaches an active tree from this thread while preserving its handle for asynchronous finalization. */
    public static void detach(RootTraceHandle handle) {
        ExecutionFrame current = CURRENT_FRAME.get();
        if (handle != null && current != null && current.trace == handle.trace) CURRENT_FRAME.remove();
    }

    /** 向当前 Trace 添加子节点；没有活动 Trace 时不做处理。 */
    public static void addSpan(String name, SpanKind kind) {
        addSpan(name, kind, "");
    }

    public static void addSpan(String name, SpanKind kind, String tag) {
        ExecutionFrame current = currentFrame();
        if (current == null) return;
        Span child = addChild(current, name, kind, tag);
        CURRENT_FRAME.set(new ExecutionFrame(current.trace, child == null ? current.span : child,
                child == null ? current.depth : current.depth + 1, child, current, current.traceId));
    }

    /** Adds a user-declared code span and retains its tree even when the request is otherwise successful. */
    public static void addExplicitSpan(String name, SpanKind kind, String tag) {
        TraceTree trace = currentTrace();
        if (trace != null) {
            trace.requireExplicitTree();
            addSpan(name, kind, tag);
        }
    }

    /** 返回当前节点的 Span ID；没有活动 Trace 时返回空字符串。 */
    public static String currentSpanId() {
        ExecutionFrame current = currentFrame();
        return current == null ? "" : current.span.spanId();
    }

    /** 框架解析出路由和方法后，补全根节点的展示名称与标签。 */
    public static void updateRootIdentity(String name, String tag) {
        TraceTree trace = currentTrace();
        if (trace != null) trace.updateRootIdentity(name, tag);
    }

    public static void stopSpan() {
        ExecutionFrame current = currentFrame();
        if (current == null || current.previous == null) return;
        if (current.openedSpan != null) current.trace.stopSpan(current.openedSpan);
        CURRENT_FRAME.set(current.previous);
    }

    /** 在监控初始化完成、业务代码开始前重置当前节点的计时边界。 */
    public static void beginCurrentSpanBoundary() {
        ExecutionFrame current = currentFrame();
        if (current != null) current.trace.beginBusinessBoundary(current.span);
    }

    /** 固定根节点和仍未结束子节点的耗时，但不生成树文本，也不清理上下文。 */
    public static void freezeRootBoundary() {
        TraceTree trace = currentTrace();
        if (trace != null) trace.freezeRoot();
    }

    /** 即使根节点未达到慢阈值，也可因内部慢节点将整条 Trace 标记为慢调用。 */
    public static void markSlow(String reason) {
        TraceTree trace = currentTrace();
        if (trace != null) trace.markSlow(reason);
    }

    /** 将当前 Trace 标记为错误，并保留首次获得的异常证据。 */
    public static void markError(String reason, Throwable failure) {
        TraceTree trace = currentTrace();
        if (trace != null) trace.markError(reason, failure);
    }

    /** 复用操作结束时已截取的异常摘要，避免再次格式化异常堆栈。 */
    public static void markError(String reason, BoundedThrowableFormatter.FailureEvidence failure) {
        TraceTree trace = currentTrace();
        if (trace != null) trace.markError(reason, failure);
    }

    /**
     * 创建由外部生命周期结束的子节点，例如事务完成回调。
     * 调用返回的 DetachedSpan.close() 结束节点；没有活动 Trace 时返回空操作对象。
     */
    public static DetachedSpan startDetachedSpan(String name, SpanKind kind, String tag) {
        ExecutionFrame current = currentFrame();
        if (current == null) return DetachedSpan.NOOP;
        Span span = current.trace.addChild(current.span, current.depth, name, kind, tag);
        return span == null ? DetachedSpan.NOOP : new DetachedSpan(current.trace, span);
    }

    /** Starts a synchronous child span that remains current until its scope closes. */
    public static ScopedSpan startScopedSpan(String name, SpanKind kind, String tag) {
        ExecutionFrame current = currentFrame();
        if (current == null) return ScopedSpan.NOOP;
        Span span = addChild(current, name, kind, tag);
        ExecutionFrame installed = new ExecutionFrame(current.trace, span == null ? current.span : span,
                span == null ? current.depth : current.depth + 1, span, current, current.traceId);
        CURRENT_FRAME.set(installed);
        return new ScopedSpan(current.trace, Thread.currentThread().getId(), span, current, installed);
    }

    public interface SpanHandle extends AutoCloseable {
        String spanId();

        String parentSpanId();

        @Override
        void close();
    }

    public static final class ScopedSpan implements SpanHandle, Runnable {
        private static final ScopedSpan NOOP = new ScopedSpan(null, -1, null, null, null);
        private final TraceTree trace;
        private final long threadId;
        private final Span span;
        private final ExecutionFrame previous;
        private final ExecutionFrame installed;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private ScopedSpan(TraceTree trace, long threadId, Span span,
                           ExecutionFrame previous, ExecutionFrame installed) {
            this.trace = trace;
            this.threadId = threadId;
            this.span = span;
            this.previous = previous;
            this.installed = installed;
        }

        @Override
        public String spanId() {
            return span == null ? "" : span.spanId();
        }

        @Override
        public String parentSpanId() {
            return span == null ? "" : span.parentSpanId();
        }

        @Override
        public void close() {
            if (!stopped.compareAndSet(false, true)) return;
            if (trace != null) {
                if (span != null) trace.stopSpan(span);
                if (threadId == Thread.currentThread().getId() && CURRENT_FRAME.get() == installed) {
                    CURRENT_FRAME.set(previous);
                }
            }
        }

        /** Compatibility for callers that previously used Runnable as a span handle. */
        @Override
        public void run() {
            close();
        }
    }

    public static final class DetachedSpan implements SpanHandle, Runnable {
        private static final DetachedSpan NOOP = new DetachedSpan(null, null);
        private final TraceTree trace;
        private final Span span;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private DetachedSpan(TraceTree trace, Span span) {
            this.trace = trace;
            this.span = span;
        }

        public void beginBusinessBoundary() {
            if (span != null) trace.beginBusinessBoundary(span);
        }

        @Override
        public String spanId() {
            return span == null ? "" : span.spanId();
        }

        @Override
        public String parentSpanId() {
            return span == null ? "" : span.parentSpanId();
        }

        @Override
        public void close() {
            if (span != null && stopped.compareAndSet(false, true)) trace.stopSpan(span);
        }

        /** Compatibility for callers that previously used Runnable as a span handle. */
        @Override
        public void run() {
            close();
        }
    }

    public static void stopRoot(long slowThresholdMs, String description) {
        stopRoot(slowThresholdMs, description, null);
    }

    public static void stopRoot(long slowThresholdMs, String description, Throwable throwable) {
        stopRoot(slowThresholdMs, description, throwable, (tree, cost) -> { });
    }

    public static void stopRoot(long slowThresholdMs, String description, Throwable throwable,
                                java.util.function.BiConsumer<String, Long> completion) {
        stopRoot(slowThresholdMs, description, throwable,
                result -> completion.accept(result.tree(), result.costMs()));
    }

    public static void stopRoot(long slowThresholdMs, String description, Throwable throwable,
                                Consumer<Completion> completion) {
        TraceTree trace = currentTrace();
        if (trace == null) {
            return;
        }
        completeRoot(trace, slowThresholdMs, description, throwable, completion);
    }

    private static void completeRoot(TraceTree trace, long slowThresholdMs, String description, Throwable throwable,
                                     Consumer<Completion> completion) {
        try {
            Completion result;
            long costMs;
            synchronized (trace) {
                if (trace.completed()) return;
                costMs = trace.completeRoot(slowThresholdMs, throwable);
                String tree = trace.explicitTreeRequired() || trace.slow() || trace.error()
                        ? trace.renderTree() : "";
                result = new Completion(tree, costMs, trace.slow(), trace.error(), trace.incidentReasons(),
                        trace.errorType(), trace.errorMessage(), trace.errorStackTrace());
            }
            completion.accept(result);
            if (throwable != null) {
                logIncidentSummary(ERROR_LOG_LIMITER, true, description, costMs, slowThresholdMs,
                        throwable.getClass().getName(), result.reasons());
            } else if (slowThresholdMs > 0 && costMs >= slowThresholdMs) {
                logIncidentSummary(SLOW_LOG_LIMITER, false, description, costMs, slowThresholdMs,
                        "", result.reasons());
            }
        } finally {
            ExecutionFrame current = CURRENT_FRAME.get();
            if (current != null && current.trace == trace) CURRENT_FRAME.remove();
        }
    }

    /** A root-tree reference retained by an Observation until its request finishes. */
    public static final class RootTraceHandle {
        private final TraceTree trace;

        private RootTraceHandle(TraceTree trace) {
            this.trace = trace;
        }

        public void updateRootIdentity(String name, String tag) {
            trace.updateRootIdentity(name, tag);
        }

        public void markSlow(String reason) {
            trace.markSlow(reason);
        }

        public void markError(String reason, Throwable failure) {
            trace.markError(reason, failure);
        }

        public void markError(String reason, BoundedThrowableFormatter.FailureEvidence failure) {
            trace.markError(reason, failure);
        }

        public void rememberOwnedTraceId(String traceId) {
            trace.rememberOwnedTraceId(traceId);
        }

        public void stop(long slowThresholdMs, String description, Throwable failure,
                         Consumer<Completion> completion) {
            completeRoot(trace, slowThresholdMs, description, failure, completion);
        }

        public void discard() {
            trace.discard();
            TraceContext.detach(this);
        }
    }

    /**
     * Stops a trace segment while retaining its bounded tree in object form. Async requests use this so their common
     * successful path never renders a tree String; the caller materializes it only if the completed request is slow
     * or failed.
     */
    public static void stopRootDeferred(Throwable throwable, Consumer<DeferredCompletion> completion) {
        TraceTree trace = currentTrace();
        if (trace == null) return;
        try {
            long costMs = trace.stopRoot();
            if (throwable != null && !trace.error()) trace.markError("ROOT_ERROR", throwable);
            trace.markCompleted();
            completion.accept(new DeferredCompletion(trace, costMs));
        } finally {
            clear();
        }
    }


    public static void clear() {
        CURRENT_FRAME.remove();
    }

    private static TraceTree currentTrace() {
        ExecutionFrame context = currentFrame();
        return context == null ? null : context.trace;
    }

    private static ExecutionFrame currentFrame() {
        ExecutionFrame context = CURRENT_FRAME.get();
        if (context != null && context.trace.completed()) {
            String contextTraceId = context.traceId;
            if (contextTraceId != null && contextTraceId.equals(TraceIdContext.currentTraceId())) {
                TraceIdContext.clear();
            } else {
                String ownedTraceId = context.trace.ownedTraceIdForCurrentThread();
                if (!ownedTraceId.isBlank() && ownedTraceId.equals(TraceIdContext.currentTraceId())) {
                    TraceIdContext.clear();
                }
            }
            CURRENT_FRAME.remove();
            return null;
        }
        return context;
    }

    private static Span addChild(ExecutionFrame parent, String name, SpanKind kind, String tag) {
        return parent.trace.addChild(parent.span, parent.depth, name, kind, tag);
    }

    private record ExecutionFrame(TraceTree trace, Span span, int depth, Span openedSpan,
                                    ExecutionFrame previous, String traceId) {
    }

    private static void logIncidentSummary(IncidentLogLimiter limiter, boolean error, String description,
                                           long costMs, long thresholdMs, String exceptionType,
                                           List<String> reasons) {
        long suppressed = limiter.tryAcquire(System.nanoTime());
        if (suppressed < 0) return;
        String safeDescription = oneLine(description, MAX_LOG_DESCRIPTION_CHARS);
        String safeExceptionType = oneLine(exceptionType, MAX_LOG_DESCRIPTION_CHARS);
        String safeReasons = summarizeReasons(reasons, MAX_LOG_REASON_CHARS);
        if (error) {
            // 异常详情和有界调用树已保存在结构化事件中；重复写入日志会放大错误风暴的分配与 I/O，且可能泄漏原始消息。
            log.error("运行诊断 incident 摘要: description={}, cost={}ms, threshold={}ms, exception={}, "
                            + "reasons={}, previousWindowSuppressed={}",
                    safeDescription, costMs, thresholdMs, safeExceptionType, safeReasons, suppressed);
        } else {
            log.warn("运行诊断 incident 摘要: description={}, cost={}ms, threshold={}ms, reasons={}, "
                            + "previousWindowSuppressed={}",
                    safeDescription, costMs, thresholdMs, safeReasons, suppressed);
        }
    }

    private static String oneLine(String value, int maxChars) {
        if (value == null || value.isBlank()) return "-";
        int limit = Math.max(1, maxChars);
        StringBuilder result = new StringBuilder(Math.min(value.length(), limit));
        for (int index = 0; index < value.length() && result.length() < limit; index++) {
            char character = value.charAt(index);
            result.append(character == '\r' || character == '\n' ? ' ' : character);
        }
        return result.toString();
    }

    private static String summarizeReasons(List<String> reasons, int maxChars) {
        if (reasons == null || reasons.isEmpty()) return "-";
        int limit = Math.max(1, maxChars);
        StringBuilder result = new StringBuilder(Math.min(limit, 64));
        for (String reason : reasons) {
            if (reason == null || reason.isBlank()) continue;
            if (!result.isEmpty() && result.length() < limit) result.append(',');
            for (int index = 0; index < reason.length() && result.length() < limit; index++) {
                char character = reason.charAt(index);
                result.append(character == '\r' || character == '\n' ? ' ' : character);
            }
            if (result.length() >= limit) break;
        }
        return result.isEmpty() ? "-" : result.toString();
    }

    /**
     * 无锁固定时间窗限流器：低 16 位保存已输出数量，高位保存单调递增的时间窗编号。
     * 达到上限后直接跳过日志格式化，避免错误风暴额外放大 CPU 和日志 I/O。
     */
    static final class IncidentLogLimiter {
        private static final long COUNT_MASK = 0xFFFFL;
        private final int limit;
        private final long windowNanos;
        private final AtomicLong windowAndCount = new AtomicLong();
        private final AtomicLong suppressed = new AtomicLong();

        IncidentLogLimiter(int limit, long windowNanos) {
            if (limit <= 0 || limit > COUNT_MASK) throw new IllegalArgumentException("invalid limit");
            if (windowNanos <= 0) throw new IllegalArgumentException("invalid window");
            this.limit = limit;
            this.windowNanos = windowNanos;
        }

        /** 返回 -1 表示本次被限流；否则返回上一时间窗内被抑制的日志数量。 */
        long tryAcquire(long nowNanos) {
            long window = Math.floorDiv(nowNanos, windowNanos);
            while (true) {
                long current = windowAndCount.get();
                long currentWindow = current >> 16;
                if (current == 0 || window > currentWindow) {
                    long next = (window << 16) | 1L;
                    if (windowAndCount.compareAndSet(current, next)) return suppressed.getAndSet(0);
                    continue;
                }
                // 调用线程跨时间窗暂停后恢复时，不能让共享窗口倒退。
                if (window < currentWindow || (current & COUNT_MASK) >= limit) {
                    suppressed.incrementAndGet();
                    return -1;
                }
                if (windowAndCount.compareAndSet(current, current + 1)) return 0;
            }
        }
    }

    public record Completion(String tree, long costMs, boolean slow, boolean error, List<String> reasons,
                             String errorType, String errorMessage, String stackTrace) {
        public Completion {
            tree = tree == null ? "" : tree;
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            errorType = errorType == null ? "" : errorType;
            errorMessage = errorMessage == null ? "" : errorMessage;
            stackTrace = stackTrace == null ? "" : stackTrace;
        }
    }

    public static final class DeferredCompletion {
        private final TraceTree trace;
        private final long costMs;
        private volatile String renderedTree;

        private DeferredCompletion(TraceTree trace, long costMs) {
            this.trace = trace;
            this.costMs = costMs;
        }

        public long costMs() { return costMs; }
        public boolean slow() { return trace.slow(); }
        public boolean error() { return trace.error(); }
        public List<String> reasons() { return trace.incidentReasons(); }
        public String errorType() { return trace.errorType(); }
        public String errorMessage() { return trace.errorMessage(); }
        public String stackTrace() { return trace.errorStackTrace(); }

        public String tree() {
            String value = renderedTree;
            if (value != null) return value;
            synchronized (this) {
                if (renderedTree == null) renderedTree = trace.renderTree();
                return renderedTree;
            }
        }
    }
}
