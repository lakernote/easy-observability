package org.redisson.spring.data.connection;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;

import java.util.concurrent.atomic.AtomicInteger;

/** Test double whose name exercises Redisson factory detection without adding Redisson to common tests. */
public class RedissonConnectionFactoryTestDouble
        implements RedisConnectionFactory, InitializingBean, DisposableBean {
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();
    private final RedisConnection connection;
    private final boolean failConnection;
    private int connectionRequests;
    private int initializations;
    private int destructions;

    public RedissonConnectionFactoryTestDouble(RedisConnection connection) {
        this(connection, false);
    }

    protected RedissonConnectionFactoryTestDouble(RedisConnection connection, boolean failConnection) {
        CONSTRUCTIONS.incrementAndGet();
        this.connection = connection;
        this.failConnection = failConnection;
    }

    public String vendorName() {
        return initializations == 1 ? "initialized-redisson" : "redisson";
    }

    public int connectionRequests() {
        return connectionRequests;
    }

    public int initializations() {
        return initializations;
    }

    public int destructions() {
        return destructions;
    }

    public static int constructions() {
        return CONSTRUCTIONS.get();
    }

    public static void resetConstructions() {
        CONSTRUCTIONS.set(0);
    }

    @Override
    public void afterPropertiesSet() {
        initializations++;
    }

    @Override
    public void destroy() {
        destructions++;
    }

    @Override
    public RedisConnection getConnection() {
        connectionRequests++;
        if (failConnection) throw new IllegalStateException("Factory unavailable");
        return connection;
    }

    @Override
    public RedisClusterConnection getClusterConnection() {
        return null;
    }

    @Override
    public boolean getConvertPipelineAndTxResults() {
        return true;
    }

    @Override
    public RedisSentinelConnection getSentinelConnection() {
        return null;
    }

    @Override
    public DataAccessException translateExceptionIfPossible(RuntimeException exception) {
        return null;
    }

    public static final class FinalFactory extends RedissonConnectionFactoryTestDouble {
        public FinalFactory(RedisConnection connection) {
            super(connection);
        }
    }

    public static class FactoryWithFinalMethod extends RedissonConnectionFactoryTestDouble {
        public FactoryWithFinalMethod(RedisConnection connection) {
            super(connection);
        }

        public final String finalVendorMethod() {
            return "final-redisson";
        }
    }

    public static class FailingFactory extends RedissonConnectionFactoryTestDouble {
        public FailingFactory(RedisConnection connection) {
            super(connection, true);
        }
    }
}
