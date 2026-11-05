package io.github.lakernote.observability.integration.jdbc.internal;

import io.github.lakernote.observability.metrics.internal.OperationStats;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.internal.ScopedSpan;
import io.github.lakernote.observability.trace.internal.TraceContext;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

import java.util.List;

/**
 * 将 JDBC 执行记录为当前 Trace 的子节点并计入 SQL 统计，不发布 Micrometer Observation。
 * <p>统计不要求活动 Trace；Trace 子节点只在活动 Trace 中创建。</p>
 */
public final class JdbcTraceQueryExecutionListener implements QueryExecutionListener {
    private static final String TRACE_STATE_KEY = JdbcTraceQueryExecutionListener.class.getName() + ".traceState";
    private final OperationStats operationStats;

    public JdbcTraceQueryExecutionListener() {
        this(null);
    }

    /** {@code operationStats} may be null when call statistics are disabled. */
    public JdbcTraceQueryExecutionListener(OperationStats operationStats) {
        this.operationStats = operationStats;
    }

    @Override
    public void beforeQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        try {
            boolean traceActive = TraceContext.active();
            if (!traceActive && operationStats == null) return;

            String sql = JdbcTraceSql.describe(queryInfoList);
            ScopedSpan span = null;
            if (traceActive) {
                String dataSource = executionInfo.getDataSourceName();
                String detail = dataSource == null || dataSource.isBlank() ? sql : dataSource + " · " + sql;
                span = TraceContext.startScopedSpan("SQL " + JdbcTraceSql.commandOf(sql), SpanKind.DATABASE, detail);
            }
            try {
                executionInfo.addCustomValue(TRACE_STATE_KEY, new TraceState(span,
                        operationStats == null ? null : JdbcTraceSql.statsName(queryInfoList, sql),
                        System.nanoTime()));
            } catch (RuntimeException | Error failure) {
                if (span != null) span.close();
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

            Throwable failure = executionInfo.getThrowable();
            if (failure != null && state.span != null) {
                TraceContext.markError("SQL_ERROR", JdbcTraceSafety.captureFailure(failure));
            }
            if (state.statsName != null) {
                operationStats.record(OperationStats.Category.SQL, state.statsName,
                        System.nanoTime() - state.startedAtNanos, failure != null);
            }
        } catch (Throwable ignored) {
            // Trace 回调不能替换 JDBC 结果或原始异常。
        } finally {
            if (state != null && state.span != null) {
                try {
                    state.span.close();
                } catch (Throwable ignored) {
                    // Trace 清理失败不能传播到业务代码。
                }
            }
        }
    }

    private record TraceState(ScopedSpan span, String statsName, long startedAtNanos) { }
}
