package io.github.lakernote.observability.integration.jdbc.internal;

import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.internal.TraceCompletion;
import io.github.lakernote.observability.trace.internal.TraceContext;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTraceQueryExecutionListenerTest {
    private final JdbcTraceQueryExecutionListener listener = new JdbcTraceQueryExecutionListener();

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void countsSqlStatisticsWithAndWithoutAnActiveTrace() {
        var stats = new io.github.lakernote.observability.metrics.internal.OperationStats(10);
        var counting = new JdbcTraceQueryExecutionListener(stats);
        List<QueryInfo> queries = List.of(new QueryInfo("SELECT * FROM orders WHERE id IN (1, 2)"));

        ExecutionInfo outsideTrace = new ExecutionInfo();
        counting.beforeQuery(outsideTrace, queries);
        counting.afterQuery(outsideTrace, queries);
        ExecutionInfo failed = new ExecutionInfo();
        failed.setThrowable(new SQLException("boom"));
        TraceContext.startRoot("jdbc-request", SpanKind.HTTP);
        counting.beforeQuery(failed, queries);
        counting.afterQuery(failed, queries);
        TraceContext.clear();

        var sql = stats.snapshot(io.github.lakernote.observability.metrics.internal.OperationStats.Window.TODAY)
                .categories().stream()
                .filter(c -> c.category() == io.github.lakernote.observability.metrics.internal.OperationStats.Category.SQL)
                .findFirst().orElseThrow();
        assertThat(sql.rows()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("SELECT * FROM orders WHERE id IN (?…)");
            assertThat(row.count()).isEqualTo(2);
            assertThat(row.errors()).isEqualTo(1);
        });
    }

    @Test
    void recordsOnlyTheRedactedSqlTemplate() {
        ExecutionInfo execution = new ExecutionInfo();
        execution.setDataSourceName("primary");
        List<QueryInfo> queries = List.of(new QueryInfo("SELECT 'literal-secret--tail'"));
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-request", SpanKind.HTTP);
        listener.beforeQuery(execution, queries);
        listener.afterQuery(execution, queries);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, "test", null, completion::set);

        assertThat(completion.get().tree())
                .contains("SQL SELECT", "primary · SELECT ?")
                .doesNotContain("literal-secret", "tail");
        assertThat(completion.get().error()).isFalse();
    }

    @Test
    void recordsSafeFailureEvidenceWithoutDriverOrCauseMessages() {
        String sqlSecret = "driver-secret-c91e";
        String causeSecret = "bind-secret-4d2e";
        SQLException failure = new SQLException(
                "Syntax error in SQL SELECT '" + sqlSecret + "'", new IllegalArgumentException(causeSecret));
        ExecutionInfo execution = new ExecutionInfo();
        execution.setThrowable(failure);
        List<QueryInfo> queries = List.of(new QueryInfo("SELECT '" + sqlSecret + "' FROM missing_table"));
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-request", SpanKind.HTTP);
        listener.beforeQuery(execution, queries);
        listener.afterQuery(execution, queries);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, "test", null, completion::set);

        TraceCompletion result = completion.get();
        assertThat(result.error()).isTrue();
        assertThat(result.reasons()).containsExactly("SQL_ERROR");
        assertThat(result.errorType()).isEqualTo(SQLException.class.getName());
        assertThat(result.errorMessage()).isEqualTo("JDBC query failed");
        assertThat(result.stackTrace())
                .contains(SQLException.class.getName(), IllegalArgumentException.class.getName())
                .doesNotContain(sqlSecret, causeSecret, "Syntax error in SQL", "secret");
        assertThat(result.tree())
                .contains("SQL SELECT", "SELECT ? FROM missing_table")
                .doesNotContain(sqlSecret, causeSecret, "secret");
    }

    @Test
    void fallsBackToSafeNonEmptyEvidenceWhenThrowableStackCannotBeRead() {
        SQLException failure = new UnreadableStackSqlException("driver-secret");
        ExecutionInfo execution = new ExecutionInfo();
        execution.setThrowable(failure);
        List<QueryInfo> queries = List.of(new QueryInfo("SELECT 'driver-secret'"));
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-request", SpanKind.HTTP);
        listener.beforeQuery(execution, queries);
        listener.afterQuery(execution, queries);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, "test", failure, completion::set);

        assertThat(completion.get().error()).isTrue();
        assertThat(completion.get().errorMessage()).isEqualTo("JDBC query failed");
        assertThat(completion.get().stackTrace())
                .isEqualTo(UnreadableStackSqlException.class.getName())
                .doesNotContain("driver-secret");
    }

    private static final class UnreadableStackSqlException extends SQLException {
        private UnreadableStackSqlException(String message) {
            super(message);
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            throw new IllegalStateException("stack-secret");
        }
    }
}
