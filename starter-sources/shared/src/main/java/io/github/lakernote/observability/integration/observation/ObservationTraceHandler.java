package io.github.lakernote.observability.integration.observation;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.BoundedThrowableFormatter;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.ThreadResourceSnapshot;
import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.github.lakernote.observability.trace.store.IncidentTraceStore;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 将 Spring/Micrometer 发布的框架 Observation 接入统一 Trace。
 * <p>只把 HTTP、Job、Kafka 消费作为入口；Redis、数据库等作为入口下的依赖节点。</p>
 */
public final class ObservationTraceHandler implements ObservationHandler<Observation.Context> {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ObservationTraceHandler.class);
    private static final Object STATE_KEY = ObservationTraceHandler.class.getName() + ".state";
    private static final Pattern SQL_LITERAL = Pattern.compile("'(?:''|[^'])*'|\\b[0-9]+(?:\\.[0-9]+)?\\b");
    private static final Pattern SQL_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|--[^\\r\\n]*");

    private final RecentTraceStore recentTraces;
    private final EasyObservabilityProperties properties;
    private final IncidentTraceStore incidentTraces;
    private final List<ObservationTraceIdResolver> traceIdResolvers;
    private final AtomicBoolean callbackFailureLogged = new AtomicBoolean();

    public ObservationTraceHandler(RecentTraceStore recentTraces, EasyObservabilityProperties properties) {
        this(recentTraces, null, properties, List.of());
    }

    public ObservationTraceHandler(RecentTraceStore recentTraces, IncidentTraceStore incidentTraces,
                                   EasyObservabilityProperties properties) {
        this(recentTraces, incidentTraces, properties, List.of());
    }

    public ObservationTraceHandler(RecentTraceStore recentTraces, IncidentTraceStore incidentTraces,
                                   EasyObservabilityProperties properties,
                                   List<ObservationTraceIdResolver> traceIdResolvers) {
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
        boolean installedTraceId = false;
        TraceContext.RootTraceHandle traceHandle = null;
        TraceContext.SpanHandle span = null;
        try {
            type = findObservationType(context);
            if (type == null || !type.isEnabled(properties)) return;
            // 诊断页轮询自身 API 和静态资源会持续制造 HTTP Trace；仅跳过本组件记录，不改动宿主 Actuator/Micrometer 指标。
            if (type == ObservationType.HTTP && isDashboardRequest(context)) return;
            // 只有入口 Observation 创建根 Trace。客户端调用、SQL、Redis 和事务是依赖节点；脱离入口时不生成孤立 Trace。
            boolean traceActive = TraceContext.active();
            boolean root = type.entryPoint && !traceActive;
            String currentTraceId = TraceIdContext.currentTraceId();
            String propagatedTraceId = resolveTraceId(context);
            boolean traceIdRequired = root || traceActive;
            installedTraceId = !TraceIdContext.isValidTraceId(currentTraceId)
                    && (traceIdRequired || TraceIdContext.isValidTraceId(propagatedTraceId));
            // Kafka 的 Observation 在 RecordInterceptor 之前启动；此处先从消息头恢复同一 Trace ID。
            String traceId = TraceIdContext.isValidTraceId(propagatedTraceId)
                    ? propagatedTraceId
                    : traceIdRequired ? TraceIdContext.getOrCreateTraceId() : "";
            if (TraceIdContext.isValidTraceId(propagatedTraceId)
                    && !TraceIdContext.isValidTraceId(currentTraceId)) {
                TraceIdContext.putTraceId(propagatedTraceId);
                installedTraceId = true;
            }
            String name = operationName(context, type.name());
            String detail = observationDetail(context);
            long threadId = Thread.currentThread().getId();
            // CPU and allocated bytes are sampled only at the entry boundary; child spans retain timing only.
            ThreadResourceSnapshot resources = root ? ThreadResourceSnapshot.capture(threadId,
                    properties.getTrace().isResourceSamplingEnabled()) : null;
            if (root) {
                // 入口建立请求级 Trace；依赖调用只挂到当前 Trace，不会单独伪装成请求。
                TraceContext.startRootSpan(name, type.spanKind, detail, 8, 0);
                rootStarted = true;
                traceHandle = TraceContext.currentRootTraceHandle();
                if (installedTraceId && traceHandle != null) {
                    traceHandle.rememberOwnedTraceId(traceId);
                }
            } else if (type == ObservationType.DATABASE || type == ObservationType.TRANSACTION) {
                // JDBC 查询在事务方法中同步执行；用作用域 span 表达事务包住 SQL 的真实调用层级。
                TraceContext.ScopedSpan scopedSpan = TraceContext.startScopedSpan(name, type.spanKind, detail);
                span = scopedSpan;
            } else {
                TraceContext.DetachedSpan detachedSpan = TraceContext.startDetachedSpan(name, type.spanKind, detail);
                span = detachedSpan;
            }
            if (traceHandle == null && traceActive) traceHandle = TraceContext.currentRootTraceHandle();
            context.put(STATE_KEY, new ObservationState(type, name, detail, traceId, root,
                    installedTraceId, System.nanoTime(), threadId, resources, span, traceHandle));
        } catch (RuntimeException ignored) {
            // 监控回调异常不能影响应用已有的 Trace 和业务流程。
            if (rootStarted) TraceContext.clear();
            if (span != null) span.close();
            if (installedTraceId) TraceIdContext.clear();
            logCallbackFailure("start", ignored);
        }
    }

    @Override
    public void onScopeClosed(Observation.Context context) {
        ObservationState state = context.get(STATE_KEY);
        if (state == null || !state.startedOnCurrentThread()) return;
        if (state.rootObservation) {
            // Async server observations leave the request thread before their final stop callback. Detach the tree
            // while that thread is still available for reuse; the Observation state retains it for finalization.
            TraceContext.detach(state.traceHandle);
        }
        if (state.installedTraceId && state.traceId.equals(TraceIdContext.currentTraceId())) TraceIdContext.clear();
    }

    @Override
    public void onError(Observation.Context context) {
        ObservationState state = context.get(STATE_KEY);
        if (state == null || context.getError() == null) return;
        try {
            BoundedThrowableFormatter.FailureEvidence failure = BoundedThrowableFormatter.capture(context.getError());
            state.failure = failure;
            if (state.traceHandle != null) {
                state.traceHandle.markError(state.observationType.name() + "_ERROR", failure);
            } else if (state.startedOnCurrentThread()) {
                TraceContext.markError(state.observationType.name() + "_ERROR", failure);
            }
        } catch (RuntimeException failure) {
            logCallbackFailure("error", failure);
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        ObservationState state = context.get(STATE_KEY);
        if (state == null) return;
        boolean sameThread = state.startedOnCurrentThread();
        try {
            // JDBC query text is fixed at observation start; avoid re-running sanitization regexes on completion.
            String currentDetail = state.observationType == ObservationType.DATABASE
                    ? state.startDetail : observationDetail(context);
            // Spring MVC 的 URI 路由标签可能到 onStop 才补齐；此时再判断，避免诊断页轮询占满 Trace 保留区。
            if (state.observationType == ObservationType.HTTP && isDashboardPath(
                    currentDetail.isBlank() ? state.startDetail : currentDetail)) {
                if (state.rootObservation && state.traceHandle != null) state.traceHandle.discard();
                return;
            }
            long durationMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - state.startedAtNanos));
            String detail = currentDetail.isBlank() ? state.startDetail : currentDetail;
            String displayName = traceDisplayName(context, state.observationType.name(), state.operationName, detail);
            ThreadResourceSnapshot resourcesAfter = state.rootObservation ? captureResourcesAtStop(state) : null;
            Throwable error = context.getError();
            int status = httpStatus(context);
            boolean failed = error != null || status >= 500;
            if (error == null && status >= 500) {
                BoundedThrowableFormatter.FailureEvidence statusFailure =
                        new BoundedThrowableFormatter.FailureEvidence("HTTP_" + status,
                                "HTTP status " + status, "");
                if (state.traceHandle != null) {
                    state.traceHandle.markError("HTTP_" + status, statusFailure);
                } else if (sameThread) {
                    TraceContext.markError("HTTP_" + status, statusFailure);
                }
            }
            long threshold = state.observationType.threshold(properties);
            boolean slow = threshold > 0 && durationMs >= threshold;
            // 通过所属 Trace 句柄更新标记，不读取结束线程当前的 ThreadLocal Trace。
            if (slow) {
                if (state.traceHandle != null) {
                    state.traceHandle.markSlow("SLOW_" + state.observationType.name());
                } else if (sameThread) {
                    TraceContext.markSlow("SLOW_" + state.observationType.name());
                }
            }
            if (state.rootObservation && state.traceHandle != null) {
                state.traceHandle.updateRootIdentity(displayName, detail);
            }
            if (state.spanHandle != null) state.spanHandle.close();
            long cpuTimeMs = resourcesAfter == null || state.resourcesAtStart == null ? -1
                    : resourcesAfter.cpuTimeMsSince(state.resourcesAtStart);
            long allocatedBytes = resourcesAfter == null || state.resourcesAtStart == null ? -1
                    : resourcesAfter.allocatedBytesSince(state.resourcesAtStart);
            completeRootTrace(state, threshold, error, displayName, detail, status, durationMs, slow, failed,
                    cpuTimeMs, allocatedBytes);
        } catch (RuntimeException failure) {
            if (state.rootObservation && state.traceHandle != null) {
                state.traceHandle.discard();
            }
            logCallbackFailure("stop", failure);
        } finally {
            if (sameThread && state.installedTraceId
                    && state.traceId.equals(TraceIdContext.currentTraceId())) TraceIdContext.clear();
            context.remove(STATE_KEY);
        }
    }

    private void logCallbackFailure(String callback, RuntimeException failure) {
        if (callbackFailureLogged.compareAndSet(false, true)) {
            log.warn("Easy Observability Observation {} callback failed; business execution continues ({})",
                    callback, failure.getClass().getName());
        }
    }

    private ThreadResourceSnapshot captureResourcesAtStop(ObservationState state) {
        if (!state.startedOnCurrentThread()) {
            // 资源计数来自线程累计值，跨线程结束时无法安全相减，使用 -1 表示未知。
            return new ThreadResourceSnapshot(state.startThreadId, -1, -1);
        }
        return ThreadResourceSnapshot.capture(state.startThreadId,
                properties.getTrace().isResourceSamplingEnabled());
    }

    private void completeRootTrace(ObservationState state, long threshold, Throwable error, String displayName,
                                  String detail, int status, long durationMs, boolean slow, boolean failed,
                                  long cpuTimeMs, long allocatedBytes) {
        if (!state.rootObservation || state.traceHandle == null) return;
        state.traceHandle.stop(threshold, state.observationType.name() + " observation", error,
                completion -> storeCompletedTrace(state, completion, displayName, detail, status,
                        durationMs, slow, failed, cpuTimeMs, allocatedBytes));
    }

    private void storeCompletedTrace(ObservationState state, TraceContext.Completion completion,
                                     String displayName, String detail, int status, long durationMs,
                                     boolean slow, boolean failed, long cpuTimeMs, long allocatedBytes) {
        boolean traceSlow = slow || completion.slow();
        boolean traceFailed = failed || completion.error();
        recentTraces.add(state.traceId, displayName, detail, state.observationType.name(),
                status > 0 ? status : failed ? 500 : 200, durationMs, traceSlow, traceFailed, completion.tree(),
                state.startThreadId, cpuTimeMs, allocatedBytes, completion.errorType(), completion.errorMessage());
        if (incidentTraces == null || (!traceSlow && !traceFailed)) return;
        // 只把慢/错入口 Trace 写入持久存储；正常请求留在有界内存列表中。
        try {
            incidentTraces.record(recentTraces.find(state.traceId, state.observationType.name()));
        } catch (RuntimeException failure) {
            logCallbackFailure("incident persistence", failure);
        }
    }

    private ObservationType findObservationType(Observation.Context context) {
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

    private static String traceDisplayName(Observation.Context context, String type, String operation, String detail) {
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

    private static String observationDetail(Observation.Context context) {
        String route = lowCardinality(context, "http.route");
        if (!route.isBlank()) return safe(route, 256);
        String uri = lowCardinality(context, "uri");
        if (!uri.isBlank()) return safe(uri, 256);
        String destination = lowCardinality(context, "messaging.destination.name");
        if (!destination.isBlank()) return safe(destination, 256);
        String executor = lowCardinality(context, "executor.name");
        if (!executor.isBlank()) return safe(executor, 256);
        String dataSource = lowCardinality(context, "jdbc.datasource.name");
        String sql = normalizedSql(context);
        if (!sql.isBlank()) return safe(dataSource.isBlank() ? sql : dataSource + " · " + sql, 512);
        return "";
    }

    /**
     * SQL is useful for slow-query diagnosis; literal values and comments are removed before it is retained.
     */
    private static String normalizedSql(Observation.Context context) {
        for (KeyValue value : context.getHighCardinalityKeyValues()) {
            if (value.getKey().startsWith("jdbc.query[")) {
                if (!value.getValue().isBlank()) {
                    String withoutComments = SQL_COMMENT.matcher(value.getValue()).replaceAll(" ");
                    String withoutLiterals = SQL_LITERAL.matcher(withoutComments).replaceAll("?");
                    return withoutLiterals.replaceAll("\\s+", " ").trim();
                }
            }
        }
        return "";
    }

    private static boolean isDashboardRequest(Observation.Context context) {
        String route = lowCardinality(context, "http.route");
        if (route.isBlank()) route = lowCardinality(context, "uri");
        return isDashboardPath(route);
    }

    private static boolean isDashboardPath(String route) {
        if (route == null || route.isBlank()) return false;
        String path = route.split("[?#]", 2)[0];
        return "/easy-observability".equals(path) || "/easy-observability/".equals(path)
                || path.startsWith("/easy-observability/assets/")
                || "/api/easy-observability".equals(path) || path.startsWith("/api/easy-observability/");
    }

    private static String lowCardinality(Observation.Context context, String key) {
        KeyValue value = context.getLowCardinalityKeyValue(key);
        return value == null ? "" : value.getValue();
    }

    private static int httpStatus(Observation.Context context) {
        String status = lowCardinality(context, "http.response.status_code");
        if (status.isBlank()) status = lowCardinality(context, "status");
        try {
            return Integer.parseInt(status);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String safe(String value, int limit) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ');
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    private static final class ObservationState {
        private final ObservationType observationType;
        private final String operationName;
        private final String startDetail;
        private final String traceId;
        private final boolean rootObservation;
        private final boolean installedTraceId;
        private final long startedAtNanos;
        private final long startThreadId;
        private final ThreadResourceSnapshot resourcesAtStart;
        private final TraceContext.SpanHandle spanHandle;
        private final TraceContext.RootTraceHandle traceHandle;
        private volatile BoundedThrowableFormatter.FailureEvidence failure;

        private ObservationState(ObservationType observationType, String operationName, String startDetail,
                                 String traceId, boolean rootObservation, boolean installedTraceId,
                                 long startedAtNanos, long startThreadId, ThreadResourceSnapshot resourcesAtStart,
                                 TraceContext.SpanHandle spanHandle, TraceContext.RootTraceHandle traceHandle) {
            this.observationType = observationType;
            this.operationName = operationName;
            this.startDetail = startDetail;
            this.traceId = traceId;
            this.rootObservation = rootObservation;
            this.installedTraceId = installedTraceId;
            this.startedAtNanos = startedAtNanos;
            this.startThreadId = startThreadId;
            this.resourcesAtStart = resourcesAtStart;
            this.spanHandle = spanHandle;
            this.traceHandle = traceHandle;
        }

        private boolean startedOnCurrentThread() {
            return startThreadId == Thread.currentThread().getId();
        }
    }

    /**
     * Observation 名称、展示类型和 Trace 行为集中维护，新增接入时避免漏配入口、开关或阈值。
     */
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

        private final SpanKind spanKind;
        private final boolean entryPoint;
        private final MatchMode matchMode;
        private final String[] observationNames;

        ObservationType(SpanKind kind, boolean entry, String... observationNames) {
            this(kind, entry, MatchMode.PREFIX, observationNames);
        }

        ObservationType(SpanKind kind, boolean entry, MatchMode matchMode, String... observationNames) {
            this.spanKind = kind;
            this.entryPoint = entry;
            this.matchMode = matchMode;
            this.observationNames = observationNames;
        }

        private boolean matches(String observationName) {
            String normalized = observationName.toLowerCase(Locale.ROOT);
            for (String candidate : observationNames) {
                boolean matches = matchMode == MatchMode.EXACT
                        ? normalized.equals(candidate)
                        : normalized.equals(candidate)
                        || normalized.startsWith(candidate.endsWith(".") ? candidate : candidate + ".");
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
                case JOB -> properties.getScheduling().isEnabled();
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

        private enum MatchMode {EXACT, PREFIX}
    }
}
