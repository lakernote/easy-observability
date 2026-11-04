package io.github.lakernote.observability.integration.jdbc;

import net.ttddyy.dsproxy.proxy.ProxyJdbcObject;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAwareJdbcProxyFactoryTest {

    @Test
    void preservesConcreteTypeAndVendorMethodsWhileProxyingJdbcConnections() throws Exception {
        VendorDataSource target = new VendorDataSource();

        DataSource result = proxy(target);

        assertThat(result).isInstanceOf(VendorDataSource.class).isInstanceOf(ProxyJdbcObject.class);
        VendorDataSource typedProxy = (VendorDataSource) result;
        assertThat(typedProxy.vendorName()).isEqualTo("vendor-data-source");
        assertThat(typedProxy.getConnection(7)).isEqualTo("shard-7");
        assertThat(((ProxyJdbcObject) typedProxy).getTarget()).isSameAs(target);
        assertThat(typedProxy.isWrapperFor(VendorDataSource.class)).isTrue();
        assertThat(typedProxy.unwrap(VendorDataSource.class)).isInstanceOf(VendorDataSource.class);
        try (Connection connection = typedProxy.getConnection()) {
            assertThat(connection).isInstanceOf(ProxyJdbcObject.class);
            assertThat(((ProxyJdbcObject) connection).getTarget()).isSameAs(target.rawConnection());
        }

        typedProxy.close();
        assertThat(target.closed()).isTrue();
    }

    @Test
    void leavesFinalDataSourceUnwrappedInsteadOfChangingItsBeanType() {
        FinalDataSource target = new FinalDataSource();

        DataSource result = proxy(target);

        assertThat(result).isSameAs(target).isNotInstanceOf(ProxyJdbcObject.class);
    }

    @Test
    void leavesDataSourceWithPublicFinalMethodUnwrapped() {
        DataSourceWithFinalMethod target = new DataSourceWithFinalMethod();

        DataSource result = proxy(target);

        assertThat(result).isSameAs(target).isNotInstanceOf(ProxyJdbcObject.class);
    }

    private static DataSource proxy(DataSource target) {
        return ProxyDataSourceBuilder.create("testDataSource", target)
                .jdbcProxyFactory(new SpringAwareJdbcProxyFactory())
                .buildProxy();
    }

    public static class VendorDataSource implements DataSource, AutoCloseable {
        private final Connection connection = connectionProxy();
        private boolean closed;

        public String vendorName() {
            return "vendor-data-source";
        }

        public String getConnection(int shard) {
            return "shard-" + shard;
        }

        Connection rawConnection() {
            return connection;
        }

        boolean closed() {
            return closed;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) { }

        @Override
        public void setLoginTimeout(int seconds) { }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            if (type.isInstance(this)) return type.cast(this);
            throw new SQLException("Not a wrapper for " + type.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return type.isInstance(this);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    public static final class FinalDataSource extends VendorDataSource { }

    public static class DataSourceWithFinalMethod extends VendorDataSource {
        public final String finalVendorMethod() {
            return "final";
        }
    }

    private static Connection connectionProxy() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "toString" -> "test-connection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "unwrap" -> ((Class<?>) args[0]).cast(proxy);
                        case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }
}
