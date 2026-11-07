package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.trace.TraceIdContext;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcConnectionsTest {
    @BeforeEach
    @AfterEach
    void reset() {
        JdbcConnections.clear();
        MDC.clear();
    }

    private static DataSource proxy(DataSource target) {
        return ProxyDataSourceBuilder.create("ordersDb", target)
                .methodListener(JdbcConnectionListener.INSTANCE)
                .jdbcProxyFactory(new SpringAwareJdbcProxyFactory())
                .buildProxy();
    }

    @Test
    void tracksConnectionsFromGetConnectionUntilClose() throws Exception {
        DataSource target = mock(DataSource.class);
        when(target.getConnection()).thenAnswer(invocation -> mock(Connection.class));
        DataSource dataSource = proxy(target);
        MDC.put(TraceIdContext.MDC_KEY, "trace-1");

        Connection first = dataSource.getConnection();
        MDC.clear();
        Connection second = dataSource.getConnection();

        JdbcConnections.Snapshot open = JdbcConnections.snapshot();
        assertThat(open.items()).hasSize(2).allSatisfy(item -> {
            assertThat(item.dataSource()).isEqualTo("ordersDb");
            assertThat(item.threadName()).isEqualTo(Thread.currentThread().getName());
            assertThat(item.heldMs()).isGreaterThanOrEqualTo(0);
        });
        assertThat(open.items()).extracting(JdbcConnections.OpenConnection::traceId)
                .containsExactlyInAnyOrder("trace-1", "");

        first.close();
        assertThat(JdbcConnections.snapshot().items()).singleElement()
                .extracting(JdbcConnections.OpenConnection::traceId).isEqualTo("");
        second.close();
        assertThat(JdbcConnections.snapshot().items()).isEmpty();
    }

    @Test
    void ignoresOtherMethodsAndUnknownConnections() throws Exception {
        DataSource target = mock(DataSource.class);
        when(target.getConnection()).thenAnswer(invocation -> mock(Connection.class));
        try (Connection connection = proxy(target).getConnection()) {
            connection.setAutoCommit(false);
            connection.commit();
            assertThat(JdbcConnections.snapshot().items()).hasSize(1);
            JdbcConnections.closed(null);
            JdbcConnections.closed(new Object());
            JdbcConnections.opened(null, "x", "1", () -> false);
            assertThat(JdbcConnections.snapshot().items()).hasSize(1);
        }
        assertThat(JdbcConnections.snapshot().items()).isEmpty();
    }

    @Test
    void sweepsConnectionsClosedOutsideTheListenerAndSortsLongestFirst() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        JdbcConnections.opened(new Object(), "a", "1", closed::get);
        Thread.sleep(5);
        JdbcConnections.opened(new Object(), "b", "2", null);

        assertThat(JdbcConnections.snapshot().items()).extracting(JdbcConnections.OpenConnection::dataSource)
                .containsExactly("a", "b");
        closed.set(true);
        assertThat(JdbcConnections.snapshot().items()).extracting(JdbcConnections.OpenConnection::dataSource)
                .containsExactly("b");
    }

    @Test
    void countsConnectionsBeyondTheTrackingCap() {
        for (int i = 0; i < JdbcConnections.MAX_TRACKED + 3; i++) {
            JdbcConnections.opened(new Object(), "db", String.valueOf(i), null);
        }
        JdbcConnections.Snapshot snapshot = JdbcConnections.snapshot();
        assertThat(snapshot.items()).hasSize(JdbcConnections.MAX_TRACKED);
        assertThat(snapshot.untracked()).isEqualTo(3);
    }
}
