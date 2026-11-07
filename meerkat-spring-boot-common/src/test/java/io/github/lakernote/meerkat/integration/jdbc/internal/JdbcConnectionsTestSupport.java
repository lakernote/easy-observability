package io.github.lakernote.meerkat.integration.jdbc.internal;

/** Lets tests in other packages seed and reset {@link JdbcConnections}. */
public final class JdbcConnectionsTestSupport {
    private JdbcConnectionsTestSupport() {
    }

    public static void open(Object key, String dataSource, String connectionId) {
        JdbcConnections.opened(key, dataSource, connectionId, null);
    }

    public static void close(Object key) {
        JdbcConnections.closed(key);
    }

    public static void clear() {
        JdbcConnections.clear();
    }
}
