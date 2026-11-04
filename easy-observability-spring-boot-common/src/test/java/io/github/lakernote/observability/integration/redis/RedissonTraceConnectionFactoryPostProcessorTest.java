package io.github.lakernote.observability.integration.redis;

import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.spring.data.connection.RedissonConnectionFactoryTestDouble;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStringCommands;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedissonTraceConnectionFactoryPostProcessorTest {
    private static final byte[] KEY = "cache-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "cache-value".getBytes(StandardCharsets.UTF_8);

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

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

    @Test
    void tracesSuccessfulCommandInvokedThroughStringCommandsProvider() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> registry);
        RedisStringCommands delegate = stringCommands(null);
        RedisConnection wrapped = processor.wrap(connection(delegate, keyCommands(null)));
        AtomicReference<TraceContext.DeferredCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("redis-command-provider", SpanKind.HTTP);
        RedisStringCommands commands = wrapped.stringCommands();
        assertThat(commands).isNotSameAs(delegate);
        assertThat(commands.get(KEY)).isEqualTo(VALUE);
        TraceContext.stopRootDeferred(null, completion::set);

        assertThat(completion.get().tree())
                .contains("[Redis] Redis get", "tag=\"get\"")
                .doesNotContain("stringCommands");
        assertThat(registry.get("spring.data.redis")
                .tags("client", "Redisson", "command", "GET", "operation", "get", "result", "success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).hasSize(1);
    }

    @Test
    void tracesFailedCommandProviderInvocationWithoutChangingException() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> registry);
        IllegalStateException failure = new IllegalStateException("Redis command failed");
        RedisKeyCommands delegate = keyCommands(failure);
        RedisConnection wrapped = processor.wrap(connection(stringCommands(null), delegate));
        AtomicReference<TraceContext.DeferredCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("redis-command-provider-failure", SpanKind.HTTP);
        RedisKeyCommands commands = wrapped.keyCommands();
        assertThat(commands).isNotSameAs(delegate);
        assertThatThrownBy(() -> commands.del(KEY)).isSameAs(failure);
        TraceContext.stopRootDeferred(null, completion::set);

        assertThat(completion.get().error()).isTrue();
        assertThat(completion.get().reasons()).contains("REDIS_ERROR");
        assertThat(completion.get().tree())
                .contains("[Redis] Redis del", "tag=\"del\"")
                .doesNotContain("keyCommands");
        assertThat(registry.get("spring.data.redis")
                .tags("client", "Redisson", "command", "DEL", "operation", "del", "result", "error")
                .timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).hasSize(1);
    }

    @Test
    void reusesConnectionProxyWhenCommandAccessorReturnsConnectionItself() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> registry);
        RedisConnection wrapped = processor.wrap(selfProvidingConnection());
        AtomicReference<TraceContext.DeferredCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("redis-self-command-provider", SpanKind.HTTP);
        RedisStringCommands commands = wrapped.stringCommands();
        assertThat(commands).isSameAs(wrapped);
        assertThat(commands.get(KEY)).isEqualTo(VALUE);
        TraceContext.stopRootDeferred(null, completion::set);

        assertThat(completion.get().tree())
                .contains("[Redis] Redis get", "tag=\"get\"")
                .doesNotContain("stringCommands");
        assertThat(completion.get().nodes()).singleElement().satisfies(root ->
                assertThat(root.children()).singleElement().satisfies(redis ->
                        assertThat(redis.name()).isEqualTo("Redis get")));
        assertThat(registry.get("spring.data.redis")
                .tags("client", "Redisson", "command", "GET", "operation", "get", "result", "success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).hasSize(1);
    }

    @Test
    void skipsMetricsForDistinctCommandProviderOnPipelinedConnection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var processor = new RedissonTraceConnectionFactoryPostProcessor(() -> registry);
        RedisStringCommands delegate = stringCommands(null);
        RedisConnection wrapped = processor.wrap(connection(delegate, keyCommands(null), true));

        RedisStringCommands commands = wrapped.stringCommands();
        assertThat(commands).isNotSameAs(delegate);
        assertThat(commands.get(KEY)).isEqualTo(VALUE);

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

    private static RedisConnection connection(RedisStringCommands stringCommands, RedisKeyCommands keyCommands) {
        return connection(stringCommands, keyCommands, false);
    }

    private static RedisConnection connection(RedisStringCommands stringCommands, RedisKeyCommands keyCommands,
                                              boolean pipelined) {
        return (RedisConnection) Proxy.newProxyInstance(
                RedisConnection.class.getClassLoader(), new Class<?>[]{RedisConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "stringCommands" -> stringCommands;
                    case "keyCommands" -> keyCommands;
                    case "isPipelined" -> pipelined;
                    case "isQueueing", "isClosed" -> false;
                    case "close", "openPipeline", "multi", "discard", "watch", "unwatch", "open" -> null;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "fake Redisson command-provider connection";
                    default -> null;
                });
    }

    private static RedisConnection selfProvidingConnection() {
        return (RedisConnection) Proxy.newProxyInstance(
                RedisConnection.class.getClassLoader(), new Class<?>[]{RedisConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "stringCommands" -> proxy;
                    case "get" -> VALUE;
                    case "isPipelined", "isQueueing", "isClosed" -> false;
                    case "close", "openPipeline", "multi", "discard", "watch", "unwatch", "open" -> null;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "self-providing Redisson connection";
                    default -> null;
                });
    }

    private static RedisStringCommands stringCommands(RuntimeException failure) {
        return (RedisStringCommands) Proxy.newProxyInstance(
                RedisStringCommands.class.getClassLoader(), new Class<?>[]{RedisStringCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> {
                        if (failure != null) throw failure;
                        yield VALUE;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "fake Redis string commands";
                    default -> null;
                });
    }

    private static RedisKeyCommands keyCommands(RuntimeException failure) {
        return (RedisKeyCommands) Proxy.newProxyInstance(
                RedisKeyCommands.class.getClassLoader(), new Class<?>[]{RedisKeyCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "del" -> {
                        if (failure != null) throw failure;
                        yield 1L;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "fake Redis key commands";
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
