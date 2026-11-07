package io.github.lakernote.meerkat.benchmark;

import io.github.lakernote.meerkat.integration.jdbc.internal.JdbcConnectionListener;
import io.github.lakernote.meerkat.integration.jdbc.internal.SpringAwareJdbcProxyFactory;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.trace.internal.TraceLogs;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Always-on costs of the troubleshooting features: copying log lines into the running trace, and tracking which
 * thread holds each JDBC connection. Traffic capture is off, as it is by default.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class DiagnosticsOverheadBenchmark {
    static final int LINES = 10;

    private DataSource plain;
    private DataSource traced;
    private DataSource proxyOnly;
    private Connection keepAlive;

    @Setup
    public void setUp() throws SQLException {
        TraceLogs.enable(TraceLogs.DEFAULT_MAX_PER_TRACE, TraceLogs.DEFAULT_MAX_MESSAGE_CHARS,
                TraceLogs.DEFAULT_MAX_BUFFERED_BYTES);
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:bench" + System.identityHashCode(this) + ";DB_CLOSE_DELAY=-1");
        plain = h2;
        keepAlive = h2.getConnection();
        keepAlive.createStatement().execute("create table if not exists t(id int primary key, v varchar(20))");
        keepAlive.createStatement().execute("merge into t values (1, 'a')");
        traced = ProxyDataSourceBuilder.create("bench", h2)
                .methodListener(JdbcConnectionListener.INSTANCE)
                .jdbcProxyFactory(new SpringAwareJdbcProxyFactory())
                .build();
        proxyOnly = ProxyDataSourceBuilder.create("bench", h2).build();
    }

    @TearDown
    public void tearDown() throws SQLException {
        keepAlive.close();
        TraceLogs.disable();
    }

    @TearDown(Level.Iteration)
    public void clearThreadState() {
        TraceContext.clear();
        TraceIds.clear();
    }

    /** What the log appender does for a line logged outside any trace. */
    @Benchmark
    public boolean logLineOutsideTrace() {
        boolean wanted = TraceLogs.wanted();
        if (wanted) TraceLogs.record("INFO", "com.example.OrderService", "never");
        return wanted;
    }

    /** An entry trace with ten log lines; subtract {@link #emptyTrace} and divide by ten for the per-line cost. */
    @Benchmark
    public void tenLogLinesInsideTrace() {
        startTrace();
        for (int i = 0; i < LINES; i++) {
            if (TraceLogs.wanted()) TraceLogs.record("INFO", "com.example.OrderService", "Loaded order 42 for customer 7");
        }
        TraceContext.stopRoot(Long.MAX_VALUE, "benchmark");
    }

    @Benchmark
    public void emptyTrace() {
        startTrace();
        TraceContext.stopRoot(Long.MAX_VALUE, "benchmark");
    }

    /** Eight threads logging at once share one buffered-bytes counter. */
    @Benchmark
    @Threads(8)
    public void tenLogLinesInsideTraceContended() {
        tenLogLinesInsideTrace();
    }

    @Benchmark
    @Threads(8)
    public void emptyTraceContended() {
        emptyTrace();
    }

    /** Same as {@link #queryWithConnectionTracking} without the connection-holder listener, for comparison. */
    @Benchmark
    public int queryWithProxyOnly() throws SQLException {
        return query(proxyOnly);
    }

    /** Borrow a connection, run one query and return it, straight against H2. */
    @Benchmark
    public int queryWithoutProxy() throws SQLException {
        return query(plain);
    }

    /** Same through the datasource proxy with the connection-holder listener. */
    @Benchmark
    public int queryWithConnectionTracking() throws SQLException {
        return query(traced);
    }

    private static int query(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select v from t where id = ?")) {
            statement.setInt(1, 1);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1).length() : 0;
            }
        }
    }

    private static void startTrace() {
        TraceIds.putTraceId("0123456789abcdef0123456789abcdef");
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
    }
}
