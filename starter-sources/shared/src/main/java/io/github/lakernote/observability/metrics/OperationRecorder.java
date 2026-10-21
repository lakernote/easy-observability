package io.github.lakernote.observability.metrics;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.OperationRecord;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.store.IncidentOperationStore;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一记录操作指标和诊断事件，是框架接入层与监控存储之间的边界。
 * <p>记录过程失败只影响监控数据，不得中断被观测的业务调用。</p>
 */
public final class OperationRecorder {
    private static final Logger log = LoggerFactory.getLogger(OperationRecorder.class);
    private static final List<String> TYPES = List.of(
            "HTTP", "SQL", "DATABASE", "REDIS", "ELASTICSEARCH", "TRANSACTION", "REMOTE", "KAFKA", "JOB",
            "EXECUTOR_TASK", "OTHER");
    private static final List<String> OUTCOMES = List.of("success", "failure");

    private final MeterRegistry meterRegistry;
    private final RecentTraceStore recentTraces;
    private final IncidentOperationStore incidentOperations;
    private final EasyObservabilityProperties properties;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public OperationRecorder(MeterRegistry meterRegistry, RecentTraceStore recentTraces,
                             EasyObservabilityProperties properties) {
        this(meterRegistry, recentTraces, null, properties);
    }

    public OperationRecorder(MeterRegistry meterRegistry, RecentTraceStore recentTraces,
                             IncidentOperationStore incidentOperations, EasyObservabilityProperties properties) {
        this.meterRegistry = meterRegistry;
        this.recentTraces = recentTraces;
        this.incidentOperations = incidentOperations;
        this.properties = properties;
    }

    /** 监控记录失败不向外抛出，不影响被观测的业务操作。 */
    public void record(OperationRecord operation) {
        if (operation == null) return;
        // 指标、近期 Trace 和慢错事件分别处理，单一存储故障不阻断其他记录路径。
        if (properties.getMetrics().isEnabled()) safely("记录操作指标", () -> recordMetrics(operation));
        if (properties.getTrace().isEnabled()) safely("保留 Trace 操作", () -> recentTraces.addOperation(operation));
        if (incidentOperations != null) safely("保留 slow/error 操作", () -> incidentOperations.record(operation));
    }

    private void safely(String actionName, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("Easy Observability {}失败；业务操作会继续执行", actionName, failure);
            }
        }
    }

    private void recordMetrics(OperationRecord operation) {
        String type = operation.type().startsWith("KAFKA_") ? "KAFKA"
                : TYPES.contains(operation.type()) ? operation.type() : "OTHER";
        String outcome = OUTCOMES.contains(operation.outcome()) ? operation.outcome() : "failure";
        String source = safeTag(operation.type());
        String name = operationTag(operation);
        Timer.builder("easy.operations.duration")
                .description("Observed operation duration")
                .tag("type", type).tag("source", source).tag("operation", name).tag("outcome", outcome)
                .register(meterRegistry)
                .record(operation.durationMs(), TimeUnit.MILLISECONDS);
        record("easy.operations.cpu.time", "milliseconds", type, source, name, outcome, operation.cpuTimeMs());
        record("easy.operations.allocated", "bytes", type, source, name, outcome, operation.allocatedBytes());
        record("easy.operations.blocked.count", "events", type, source, name, outcome, operation.blockedCount());
        record("easy.operations.blocked.time", "milliseconds", type, source, name, outcome, operation.blockedTimeMs());
        record("easy.operations.waited.count", "events", type, source, name, outcome, operation.waitedCount());
        record("easy.operations.waited.time", "milliseconds", type, source, name, outcome, operation.waitedTimeMs());
    }

    /** 返回入口指标使用的聚合名称，供统计下钻按相同维度匹配当前进程 Trace。 */
    public static String operationTag(OperationRecord operation) {
        if (operation == null) return "unknown";
        // SQL text belongs in the bounded Trace detail only. Putting it in a metric tag creates one series per
        // statement shape and makes the cardinality guard serialize every new query after its limit is reached.
        if ("DATABASE".equals(operation.type())) {
            String detail = operation.detail();
            int sqlStart = detail == null ? -1 : detail.indexOf(" · ");
            return sqlCommand(sqlStart >= 0 ? detail.substring(sqlStart + 3) : operation.name());
        }
        return safeTag(operation.detail().isBlank() ? operation.name()
                : "HTTP".equals(operation.type()) ? operation.detail() + " · " + operation.name()
                : operation.name() + " · " + operation.detail());
    }

    private static String sqlCommand(String sql) {
        if (sql == null || sql.isBlank()) return "SQL";
        int end = 0;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) end++;
        if (end == 0) return "SQL";
        String command = sql.substring(0, end).toUpperCase(java.util.Locale.ROOT);
        return switch (command) {
            case "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL", "WITH", "CREATE", "ALTER", "DROP" -> command;
            default -> "SQL";
        };
    }

    private void record(String name, String unit, String type, String source, String operation,
                        String outcome, long value) {
        if (value < 0) return;
        DistributionSummary.builder(name).baseUnit(unit).tag("type", type).tag("source", source)
                .tag("operation", operation).tag("outcome", outcome)
                .register(meterRegistry).record(value);
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= 200 ? clean : clean.substring(0, 200);
    }
}
