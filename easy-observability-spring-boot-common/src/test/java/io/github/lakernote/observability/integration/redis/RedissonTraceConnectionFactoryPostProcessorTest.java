package io.github.lakernote.observability.integration.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedissonTraceConnectionFactoryPostProcessorTest {

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
}
