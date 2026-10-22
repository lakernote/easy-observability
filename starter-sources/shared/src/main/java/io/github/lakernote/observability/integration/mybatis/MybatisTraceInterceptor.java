package io.github.lakernote.observability.integration.mybatis;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.mapping.BoundSql;

import java.util.concurrent.TimeUnit;

/** Records mapper execution around the MyBatis Executor extension point without SQL parameters. */
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class})
})
public final class MybatisTraceInterceptor implements Interceptor {
    private final EasyObservabilityProperties properties;

    public MybatisTraceInterceptor(EasyObservabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        String name = statement.getId();
        String command = command(statement.getSqlCommandType());
        TraceContext.ScopedSpan span = TraceContext.active()
                ? TraceContext.startScopedSpan(name, SpanKind.DATABASE, command) : null;
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            return invocation.proceed();
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            long durationNanos = Math.max(0, System.nanoTime() - started);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);
            boolean failed = failure != null;
            long threshold = properties.getMybatis().getSlowThresholdMs();
            boolean slow = threshold > 0 && durationMs >= threshold;
            try {
                if (slow) TraceContext.markSlow("SLOW_SQL");
                if (failed) TraceContext.markError("SQL_ERROR", failure);
                if (span != null) span.close();
            } catch (Throwable ignored) {
                // Instrumentation failures must not replace the mapper result or exception.
            }
        }
    }

    private static String command(SqlCommandType type) {
        return type == null || type == SqlCommandType.UNKNOWN ? "UNKNOWN" : type.name();
    }
}
