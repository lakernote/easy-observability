package io.github.lakernote.observability.integration.jdbc;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceContext;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

import java.util.List;

/** 将 JDBC 执行记录为当前 Trace 的子节点，不发布 Micrometer Observation。 */
public final class JdbcTraceQueryExecutionListener implements QueryExecutionListener {
    private static final String TRACE_STATE_KEY = JdbcTraceQueryExecutionListener.class.getName() + ".traceState";

    private final EasyObservabilityProperties properties;

    public JdbcTraceQueryExecutionListener(EasyObservabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void beforeQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        try {
            if (!TraceContext.active()) return;

            String sql = JdbcTraceSql.describe(queryInfoList);
            String dataSource = executionInfo.getDataSourceName();
            String detail = dataSource == null || dataSource.isBlank() ? sql : dataSource + " · " + sql;
            TraceContext.ScopedSpan span = TraceContext.startScopedSpan(
                    "SQL " + JdbcTraceSql.command(queryInfoList), SpanKind.DATABASE, detail);
            try {
                executionInfo.addCustomValue(TRACE_STATE_KEY, new TraceState(span));
            } catch (RuntimeException | Error failure) {
                span.close();
                throw failure;
            }
        } catch (Throwable ignored) {
            // Trace 回调不能影响 JDBC 调用。
        }
    }

    @Override
    public void afterQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        TraceState state = null;
        try {
            state = executionInfo.getCustomValue(TRACE_STATE_KEY, TraceState.class);
            if (state == null) return;

            long elapsedMs = Math.max(0, executionInfo.getElapsedTime());
            long thresholdMs = properties.getTrace().getDependencySlowThresholdMs();
            if (thresholdMs > 0 && elapsedMs >= thresholdMs) TraceContext.markSlow("SLOW_SQL");

            Throwable failure = executionInfo.getThrowable();
            if (failure != null) TraceContext.markError("SQL_ERROR", failure);
        } catch (Throwable ignored) {
            // Trace 回调不能替换 JDBC 结果或原始异常。
        } finally {
            if (state != null) {
                try {
                    state.span.close();
                } catch (Throwable ignored) {
                    // Trace 清理失败不能传播到业务代码。
                }
            }
        }
    }

    private record TraceState(TraceContext.ScopedSpan span) { }
}
