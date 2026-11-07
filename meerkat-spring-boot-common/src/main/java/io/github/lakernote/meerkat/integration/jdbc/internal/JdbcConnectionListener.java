package io.github.lakernote.meerkat.integration.jdbc.internal;

import net.ttddyy.dsproxy.ConnectionInfo;
import net.ttddyy.dsproxy.listener.MethodExecutionContext;
import net.ttddyy.dsproxy.listener.MethodExecutionListener;

import java.sql.Connection;

/** Removes a connection from {@link JdbcConnections} when the application closes it. */
public final class JdbcConnectionListener implements MethodExecutionListener {
    public static final JdbcConnectionListener INSTANCE = new JdbcConnectionListener();

    private JdbcConnectionListener() {
    }

    static void opened(ConnectionInfo info) {
        if (info != null) JdbcConnections.opened(info, info.getDataSourceName(), info.getConnectionId(), info::isClosed);
    }

    @Override
    public void beforeMethod(MethodExecutionContext context) {
    }

    @Override
    public void afterMethod(MethodExecutionContext context) {
        if (context.getTarget() instanceof Connection && "close".equals(context.getMethod().getName())) {
            JdbcConnections.closed(context.getConnectionInfo());
        }
    }
}
