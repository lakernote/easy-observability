package io.github.lakernote.observability.integration.jdbc;

import net.ttddyy.dsproxy.ConnectionInfo;
import net.ttddyy.dsproxy.proxy.JdbcProxyFactory;
import net.ttddyy.dsproxy.proxy.ProxyConfig;
import net.ttddyy.dsproxy.proxy.ProxyJdbcObject;
import net.ttddyy.dsproxy.proxy.ResultSetProxyLogicFactory;
import net.ttddyy.dsproxy.proxy.jdk.CallableStatementInvocationHandler;
import net.ttddyy.dsproxy.proxy.jdk.ConnectionInvocationHandler;
import net.ttddyy.dsproxy.proxy.jdk.DataSourceInvocationHandler;
import net.ttddyy.dsproxy.proxy.jdk.PreparedStatementInvocationHandler;
import net.ttddyy.dsproxy.proxy.jdk.ResultSetInvocationHandler;
import net.ttddyy.dsproxy.proxy.jdk.StatementInvocationHandler;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.framework.ProxyFactory;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** 使用 Spring AOP 代理数据源，使 Spring 仍可识别并解包原始数据源。 */
public final class SpringAwareJdbcProxyFactory implements JdbcProxyFactory {
    @Override
    public DataSource createDataSource(DataSource dataSource, ProxyConfig proxyConfig) {
        return createProxy(dataSource, new DataSourceInvocationHandler(dataSource, proxyConfig), DataSource.class);
    }

    @Override
    public Connection createConnection(Connection connection, ConnectionInfo connectionInfo, ProxyConfig proxyConfig) {
        return createProxy(connection, new ConnectionInvocationHandler(connection, connectionInfo, proxyConfig),
                Connection.class);
    }

    @Override
    public Statement createStatement(Statement statement, ConnectionInfo connectionInfo, Connection proxyConnection,
                                     ProxyConfig proxyConfig) {
        return createProxy(statement,
                new StatementInvocationHandler(statement, connectionInfo, proxyConnection, proxyConfig),
                Statement.class);
    }

    @Override
    public PreparedStatement createPreparedStatement(PreparedStatement statement, String query,
                                                    ConnectionInfo connectionInfo, Connection proxyConnection,
                                                    ProxyConfig proxyConfig, boolean generateKey) {
        return createProxy(statement, new PreparedStatementInvocationHandler(statement, query, connectionInfo,
                proxyConnection, proxyConfig, generateKey), PreparedStatement.class);
    }

    @Override
    public CallableStatement createCallableStatement(CallableStatement statement, String query,
                                                     ConnectionInfo connectionInfo, Connection proxyConnection,
                                                     ProxyConfig proxyConfig) {
        return createProxy(statement, new CallableStatementInvocationHandler(statement, query, connectionInfo,
                proxyConnection, proxyConfig), CallableStatement.class);
    }

    @Override
    public ResultSet createResultSet(ResultSet resultSet, ConnectionInfo connectionInfo, ProxyConfig proxyConfig) {
        ResultSetProxyLogicFactory factory = proxyConfig.getResultSetProxyLogicFactory();
        return createProxy(resultSet, new ResultSetInvocationHandler(factory, resultSet, connectionInfo, proxyConfig),
                ResultSet.class);
    }

    @Override
    public ResultSet createGeneratedKeys(ResultSet resultSet, ConnectionInfo connectionInfo, ProxyConfig proxyConfig) {
        ResultSetProxyLogicFactory factory = proxyConfig.getGeneratedKeysProxyLogicFactory();
        return createProxy(resultSet, new ResultSetInvocationHandler(factory, resultSet, connectionInfo, proxyConfig),
                ResultSet.class);
    }

    private <T> T createProxy(Object target, InvocationHandler handler, Class<T> proxyType) {
        MethodInterceptor interceptor = invocation -> {
            Object proxy = invocation instanceof ProxyMethodInvocation proxyInvocation
                    ? proxyInvocation.getProxy() : invocation.getThis();
            return handler.invoke(proxy, invocation.getMethod(), invocation.getArguments());
        };
        ProxyFactory proxyFactory = new ProxyFactory(ProxyJdbcObject.class, proxyType);
        proxyFactory.addAdvice(interceptor);
        proxyFactory.setTarget(target);
        return proxyType.cast(proxyFactory.getProxy(ProxyJdbcObject.class.getClassLoader()));
    }
}
