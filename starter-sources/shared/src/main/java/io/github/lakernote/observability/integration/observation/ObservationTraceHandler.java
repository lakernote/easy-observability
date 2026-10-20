package io.github.lakernote.observability.integration.observation;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.metrics.OperationRecorder;
import io.github.lakernote.observability.trace.OperationRecord;
import io.github.lakernote.observability.trace.BoundedThrowableFormatter;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.ThreadResourceSnapshot;
import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.store.IncidentTraceStore;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 将 Spring/Micrometer 发布的框架 Observation 接入统一操作记录和 Trace。
 * <p>只把 HTTP、Job、Kafka 消费作为入口；Redis、数据库等作为入口下的依赖节点。</p>
 */
public final class ObservationTraceHandler implements ObservationHandler<Observation.Context> {
    private static final Object STATE_KEY = ObservationTraceHandler.class.getName() + ".state";

    private final OperationRecorder recorder;
    private final RecentTraceStore recentTraces;
    private final EasyObservabilityProperties properties;
    private final IncidentTraceStore incidentTraces;
    private final List<ObservationTraceIdResolver> traceIdResolvers;

    public ObservationTraceHandler(OperationRecorder recorder, RecentTraceStore recentTraces,
                                   EasyObservabilityProperties properties) {
        this(recorder, recentTraces, null, properties, List.of());
    }

    public ObservationTraceHandler(OperationRecorder recorder, RecentTraceStore recentTraces,
                                   IncidentTraceStore incidentTraces, EasyObservabilityProperties properties) {
        this(recorder, recentTraces, incidentTraces, properties, List.of());
    }

    public ObservationTraceHandler(OperationRecorder recorder, RecentTraceStore recentTraces,
                                   IncidentTraceStore incidentTraces, EasyObservabilityProperties properties,
                                   List<ObservationTraceIdResolver> traceIdResolvers) {
        this.recorder = recorder;
        this.recentTraces = recentTraces;
        this.incidentTraces = incidentTraces;
        this.properties = properties;
        this.traceIdResolvers = List.copyOf(traceIdResolvers);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        // Micrometer 可能在 Convention 设置名称前询问是否支持；等到 onStart 再读取完整上下文。
        return true;
    }

    @Override
    public void onStart(Observation.Context context) {
        ObservationType type = null;
        boolean rootStarted = false;
        boolean generatedTraceId = false;
        try {
            type = resolveType(context);
            if (type == null || !type.isEnabled(properties)) return;
            // 只有入口请求创建根 Trace。客户端调用、SQL、Redis 和事务是依赖节点；脱离入口时只记录操作指标。
            boolean root = type.entry && !TraceContext.active();
            String currentTraceId = TraceIdContext.currentTraceId();
            String propagatedTraceId = resolveTraceId(context);
            generatedTraceId = !TraceIdContext.isValidTraceId(currentTraceId)
                    && !TraceIdContext.isValidTraceId(propagatedTraceId);
            // Kafka 的 Observation 在 RecordInterceptor 之前启动；此处先从消息头恢复同一 Trace ID。
            String traceId = TraceIdContext.isValidTraceId(propagatedTraceId)
                    ? propagatedTraceId
                    : TraceIdContext.getOrCreateTraceId();
            String name = operationName(context, type.name());
            String detail = safeTag(context);
            long threadId = Thread.currentThread().getId();
            ThreadResourceSnapshot resources = ThreadResourceSnapshot.capture(threadId,
                    properties.getTrace().isResourceSamplingEnabled(),
                    properties.getTrace().isThreadContentionSamplingEnabled());
            TraceContext.DetachedSpan span = null;
            String spanId;
            String parentSpanId;
            if (root) {
                // 入口建立请求级 Trace；依赖调用只挂到当前 Trace，不会单独伪装成请求。
                spanId = TraceContext.startRootSpan(name, type.kind, detail, 8, 0);
                rootStarted = true;
                parentSpanId = "";
            } else {
                span = TraceContext.startDetachedSpan(name, type.kind, detail);
                spanId = span.spanId();
                parentSpanId = span.parentSpanId();
            }
            context.put(STATE_KEY, new State(type, name, detail, traceId, root, generatedTraceId,
                    System.nanoTime(), threadId, Thread.currentThread().getName(), resources, span,
                    spanId, parentSpanId));
        } catch (RuntimeException ignored) {
            // 监控回调异常不能影响应用已有的 Trace 和业务流程。
            if (rootStarted) TraceContext.clear();
            if (generatedTraceId) TraceIdContext.clear();
        }
    }

    @Override
    public void onError(Observation.Context context) {
        State state = context.get(STATE_KEY);
        if (state == null || context.getError() == null || !state.startedOnCurrentThread()) return;
        try { TraceContext.markError(state.type.name() + "_ERROR", context.getError()); }
        catch (RuntimeException ignored) { }
    }

    @Override
    public void onStop(Observation.Context context) {
        State state = context.get(STATE_KEY);
        if (state == null) return;
        boolean sameThread = state.startedOnCurrentThread();
        try {
            long durationMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - state.startedNanos));
            String currentDetail = safeTag(context);
            String detail = currentDetail.isBlank() ? state.detail : currentDetail;
            String displayName = displayName(context, state.type.name(), state.name, detail);
            ThreadResourceSnapshot resourcesAfter = Thread.currentThread().getId() == state.threadId
                    ? ThreadResourceSnapshot.capture(state.threadId,
                    properties.getTrace().isResourceSamplingEnabled(),
                    properties.getTrace().isThreadContentionSamplingEnabled())
                    // 资源计数来自线程累计值，跨线程结束时无法安全相减，使用 -1 表示未知。
                    : new ThreadResourceSnapshot(state.threadId, -1, -1, -1, -1, -1, -1);
            Throwable error = context.getError();
            int status = httpStatus(context);
            boolean failed = error != null || status >= 500;
            BoundedThrowableFormatter.FailureEvidence failure = error == null
                    ? BoundedThrowableFormatter.FailureEvidence.EMPTY
                    : BoundedThrowableFormatter.capture(error);
            if (sameThread && error == null && status >= 500) {
                TraceContext.markError("HTTP_" + status,
                        new BoundedThrowableFormatter.FailureEvidence("HTTP_" + status,
                                "HTTP status " + status, ""));
            }
            long threshold = state.type.threshold(properties);
            boolean slow = threshold > 0 && durationMs >= threshold;
            // TraceTree 仍是线程隔离的；异步回调不能误操作结束线程上另一个请求的 Trace。
            if (sameThread && slow) TraceContext.markSlow("SLOW_" + state.type.name());
            if (sameThread && state.root) TraceContext.updateRootIdentity(displayName, detail);
            if (sameThread && state.span != null) state.span.run();
            String outcome = failed ? "failure" : "success";
            recorder.record(new OperationRecord(state.type.name(), state.traceId, state.name, state.threadName,
                    state.threadId, outcome, durationMs, resourcesAfter.cpuTimeMsSince(state.resources),
                    resourcesAfter.allocatedBytesSince(state.resources),
                    resourcesAfter.blockedCountSince(state.resources),
                    resourcesAfter.blockedTimeMsSince(state.resources),
                    resourcesAfter.waitedCountSince(state.resources),
                    resourcesAfter.waitedTimeMsSince(state.resources), detail, "", failure.stackTrace(), slow,
                    failed, slow ? List.of("SLOW_" + state.type.name()) : List.of(),
                    error == null ? (failed ? "HTTP_" + status : "") : error.getClass().getName(),
                    error == null && failed ? "HTTP status " + status : failure.message(),
                    Map.of(), Instant.now(), state.spanId, state.parentSpanId));
            if (state.root && sameThread) {
                TraceContext.stopRoot(threshold, state.type.name() + " observation", error, completion -> {
                    boolean traceSlow = slow || completion.slow();
                    boolean traceFailed = failed || completion.error();
                    recentTraces.add(state.traceId, displayName, detail, state.type.name(),
                            status > 0 ? status : failed ? 500 : 200, durationMs, traceSlow, traceFailed, completion.tree());
                    if (incidentTraces != null && (traceSlow || traceFailed)) {
                        // 只把慢/错入口 Trace 写入持久存储；正常请求留在有界内存列表中。
                        try { incidentTraces.record(recentTraces.find(state.traceId)); }
                        catch (RuntimeException ignored) { }
                    }
                });
            }
        } catch (RuntimeException ignored) {
            if (state.root && sameThread) TraceContext.clear();
        } finally {
            if (sameThread && state.generatedTraceId) TraceIdContext.clear();
            context.remove(STATE_KEY);
        }
    }

    private ObservationType resolveType(Observation.Context context) {
        String name = context.getName();
        if (name == null) return null;
        for (ObservationType type : ObservationType.values()) {
            if (type.matches(name)) return type;
        }
        return null;
    }

    private String resolveTraceId(Observation.Context context) {
        for (ObservationTraceIdResolver resolver : traceIdResolvers) {
            try {
                String traceId = resolver.resolve(context);
                if (TraceIdContext.isValidTraceId(traceId)) return traceId;
            } catch (RuntimeException ignored) {
                // 可选集成的解析异常不能影响 Observation 和业务调用。
            }
        }
        return null;
    }

    private static String operationName(Observation.Context context, String type) {
        String operation = lowCardinality(context, "db.operation");
        if (operation.isBlank()) operation = lowCardinality(context, "http.method");
        if (operation.isBlank()) operation = lowCardinality(context, "http.request.method");
        if (operation.isBlank()) operation = lowCardinality(context, "messaging.operation");
        if (operation.isBlank()) operation = lowCardinality(context, "spring.kafka.listener.id");
        if (operation.isBlank()) operation = context.getContextualName();
        if (operation == null) operation = "";
        if (operation.isBlank()) operation = context.getName();
        if (operation == null || operation.isBlank()) operation = type;
        return safe(operation, 200);
    }

    private static String displayName(Observation.Context context, String type, String operation, String detail) {
        if ("JOB".equals(type) && context.getContextualName() != null
                && !context.getContextualName().isBlank()) {
            return safe(context.getContextualName(), 200);
        }
        if ("HTTP".equals(type)) {
            String route = lowCardinality(context, "http.route");
            if (route.isBlank()) route = detail;
            String method = lowCardinality(context, "http.request.method");
            if (method.isBlank()) method = lowCardinality(context, "http.method");
            if (method.isBlank()) method = lowCardinality(context, "method");
            if (method.isBlank() && !"http.server.requests".equals(operation)) method = operation;
            if (!route.isBlank()) return safe(route + (method.isBlank() ? "" : " · " + method), 256);
        }
        return operation;
    }

    private static String safeTag(Observation.Context context) {
        String route = lowCardinality(context, "http.route");
        if (!route.isBlank()) return safe(route, 256);
        String uri = lowCardinality(context, "uri");
        if (!uri.isBlank()) return safe(uri, 256);
        String destination = lowCardinality(context, "messaging.destination.name");
        if (!destination.isBlank()) return safe(destination, 256);
        return "";
    }

    private static String lowCardinality(Observation.Context context, String key) {
        KeyValue value = context.getLowCardinalityKeyValue(key);
        return value == null || value.getValue() == null ? "" : value.getValue();
    }

    private static int httpStatus(Observation.Context context) {
        String status = lowCardinality(context, "http.response.status_code");
        if (status.isBlank()) status = lowCardinality(context, "status");
        try { return Integer.parseInt(status); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String safe(String value, int limit) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ');
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    private record State(ObservationType type, String name, String detail, String traceId, boolean root,
                         boolean generatedTraceId, long startedNanos, long threadId, String threadName,
                         ThreadResourceSnapshot resources, TraceContext.DetachedSpan span,
                         String spanId, String parentSpanId) {
        private boolean startedOnCurrentThread() {
            return threadId == Thread.currentThread().getId();
        }
    }

    /** Observation 名称、展示类型和 Trace 行为集中维护，新增接入时避免漏配入口、开关或阈值。 */
    private enum ObservationType {
        REDIS(SpanKind.REDIS, false, "spring.data.redis", "lettuce"),
        REMOTE(SpanKind.REMOTE, false, MatchMode.EXACT, "http.client.requests"),
        HTTP(SpanKind.HTTP, true, MatchMode.EXACT, "http.server.requests"),
        KAFKA_CONSUMER(SpanKind.KAFKA, true, "spring.kafka.listener"),
        KAFKA_PRODUCER(SpanKind.KAFKA, false, "spring.kafka.template"),
        ELASTICSEARCH(SpanKind.REMOTE, false, "spring.data.elasticsearch", "elasticsearch"),
        DATABASE(SpanKind.DATABASE, false, "spring.jdbc", "jdbc."),
        TRANSACTION(SpanKind.TRANSACTION, false, "spring.transaction"),
        JOB(SpanKind.SCHEDULE, true, MatchMode.EXACT, "tasks.scheduled.execution");

        private final SpanKind kind;
        private final boolean entry;
        private final MatchMode matchMode;
        private final String[] observationNames;

        ObservationType(SpanKind kind, boolean entry, String... observationNames) {
            this(kind, entry, MatchMode.PREFIX, observationNames);
        }

        ObservationType(SpanKind kind, boolean entry, MatchMode matchMode, String... observationNames) {
            this.kind = kind;
            this.entry = entry;
            this.matchMode = matchMode;
            this.observationNames = observationNames;
        }

        private boolean matches(String observationName) {
            String normalized = observationName.toLowerCase(Locale.ROOT);
            for (String candidate : observationNames) {
                boolean matches = matchMode == MatchMode.EXACT
                        ? normalized.equals(candidate)
                        : normalized.startsWith(candidate);
                if (matches) {
                    return true;
                }
            }
            return false;
        }

        private boolean isEnabled(EasyObservabilityProperties properties) {
            return switch (this) {
                case HTTP -> properties.getTrace().isHttpServerObservationEnabled();
                case REDIS -> properties.getTrace().isRedisObservationEnabled();
                case DATABASE -> properties.getTrace().isDatabaseObservationEnabled();
                default -> true;
            };
        }

        private long threshold(EasyObservabilityProperties properties) {
            return switch (this) {
                case REDIS -> properties.getTrace().getRedisSlowThresholdMs();
                case JOB -> properties.getTrace().getJobSlowThresholdMs();
                case HTTP -> properties.getTrace().getHttpSlowThresholdMs();
                case KAFKA_CONSUMER -> properties.getTrace().getKafkaConsumerSlowThresholdMs();
                default -> properties.getTrace().getDependencySlowThresholdMs();
            };
        }

        private enum MatchMode { EXACT, PREFIX }
    }
}
