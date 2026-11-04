package io.github.lakernote.observability.integration.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.spring.data.connection.RedissonConnectionFactoryTestDouble;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedissonTraceConnectionFactoryPostProcessorTest {

    @Test
    void preservesConcreteFactoryTypeStateAndLifecycleWhileWrappingConnections() throws Exception {
        RedissonConnectionFactoryTestDouble.resetConstructions();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> registry);
        var target = new RedissonConnectionFactoryTestDouble(connection(false));
        target.afterPropertiesSet();

        Object result = processor.postProcessAfterInitialization(target, "redissonConnectionFactory");

        assertThat(result).isInstanceOf(RedissonConnectionFactoryTestDouble.class).isNotSameAs(target);
        var typedFactory = (RedissonConnectionFactoryTestDouble) result;
        assertThat(typedFactory.vendorName()).isEqualTo("initialized-redisson");
        assertThat(typedFactory.getConvertPipelineAndTxResults()).isTrue();
        assertThat(typedFactory.getConnection().ping()).isEqualTo("PONG");
        assertThat(typedFactory.connectionRequests()).isEqualTo(1);
        assertThat(RedissonConnectionFactoryTestDouble.constructions()).isEqualTo(1);

        typedFactory.destroy();
        assertThat(target.initializations()).isEqualTo(1);
        assertThat(target.destructions()).isEqualTo(1);
        assertThat(registry.get("spring.data.redis")
                .tags("client", "Redisson", "command", "PING", "operation", "ping", "result", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void preservesFactoryExceptionTypeAndMessage() {
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> null);
        var target = new RedissonConnectionFactoryTestDouble.FailingFactory(connection(false));
        var result = (RedissonConnectionFactoryTestDouble.FailingFactory)
                processor.postProcessAfterInitialization(target, "redissonConnectionFactory");

        assertThatThrownBy(result::getConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Factory unavailable");
    }

    @Test
    void leavesFinalFactoryUnwrapped() {
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> null);
        var target = new RedissonConnectionFactoryTestDouble.FinalFactory(connection(false));

        Object result = processor.postProcessAfterInitialization(target, "redissonConnectionFactory");

        assertThat(result).isSameAs(target);
    }

    @Test
    void leavesFactoryWithPublicFinalMethodUnwrapped() {
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> null);
        var target = new RedissonConnectionFactoryTestDouble.FactoryWithFinalMethod(connection(false));

        Object result = processor.postProcessAfterInitialization(target, "redissonConnectionFactory");

        assertThat(result).isSameAs(target);
        assertThat(target.finalVendorMethod()).isEqualTo("final-redisson");
    }

    @Test
    void leavesExistingSpringProxyUnchanged() {
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> null);
        var target = new RedissonConnectionFactoryTestDouble(connection(false));
        ProxyFactory existingProxyFactory = new ProxyFactory();
        existingProxyFactory.setTarget(target);
        existingProxyFactory.setProxyTargetClass(true);
        Object existingProxy = existingProxyFactory.getProxy(target.getClass().getClassLoader());

        Object result = processor.postProcessAfterInitialization(existingProxy, "redissonConnectionFactory");

        assertThat(result).isSameAs(existingProxy);
    }

    @Test
    void ignoresNonRedissonConnectionFactory() {
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> null);
        RedisConnectionFactory target = new OtherConnectionFactory(connection(false));

        Object result = processor.postProcessAfterInitialization(target, "otherConnectionFactory");

        assertThat(result).isSameAs(target);
    }

    @Test
    void recordsSuccessfulRedissonSpringDataCommand() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> registry);
        RedisConnection wrapped = processor.wrap(connection(false));

        assertThat(wrapped.ping()).isEqualTo("PONG");

        assertThat(registry.get("spring.data.redis")
                .tags("client", "Redisson", "command", "PING", "operation", "ping", "result", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void recordsFailedRedissonSpringDataCommandWithoutChangingException() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> registry);
        RedisConnection wrapped = processor.wrap(connection(true));

        assertThatThrownBy(wrapped::ping).isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis unavailable");

        assertThat(registry.get("spring.data.redis")
                .tags("client", "Redisson", "command", "PING", "operation", "ping", "result", "error")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void skipsPerCommandLatencyForPipelinedConnection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> registry);
        RedisConnection wrapped = processor.wrap(connection(false, true));

        assertThat(wrapped.ping()).isEqualTo("PONG");

        assertThat(registry.getMeters()).isEmpty();
    }

    private static RedisConnection connection(boolean failPing) {
        return connection(failPing, false);
    }

    private static RedisConnection connection(boolean failPing, boolean pipelined) {
        return (RedisConnection) Proxy.newProxyInstance(
                RedisConnection.class.getClassLoader(), new Class<?>[]{RedisConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "ping" -> {
                        if (failPing) throw new IllegalStateException("Redis unavailable");
                        yield "PONG";
                    }
                    case "isPipelined" -> pipelined;
                    case "isQueueing", "isClosed" -> false;
                    case "close", "openPipeline", "multi", "discard", "watch", "unwatch", "open" -> null;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "fake Redisson connection";
                    default -> null;
                });
    }

    private static final class OtherConnectionFactory implements RedisConnectionFactory {
        private final RedisConnection connection;

        private OtherConnectionFactory(RedisConnection connection) {
            this.connection = connection;
        }

        @Override
        public boolean getConvertPipelineAndTxResults() {
            return true;
        }

        @Override
        public RedisConnection getConnection() {
            return connection;
        }

        @Override
        public org.springframework.data.redis.connection.RedisClusterConnection getClusterConnection() {
            return null;
        }

        @Override
        public org.springframework.data.redis.connection.RedisSentinelConnection getSentinelConnection() {
            return null;
        }

        @Override
        public org.springframework.dao.DataAccessException translateExceptionIfPossible(RuntimeException exception) {
            return null;
        }
    }
}
