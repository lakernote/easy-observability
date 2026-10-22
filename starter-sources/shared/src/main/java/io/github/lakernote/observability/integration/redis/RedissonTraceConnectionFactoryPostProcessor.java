package io.github.lakernote.observability.integration.redis;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceContext;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/** Uses Spring Data's connection extension point for the Redisson community client. */
public final class RedissonTraceConnectionFactoryPostProcessor implements BeanPostProcessor {
    private final ObjectProvider<EasyObservabilityProperties> propertiesProvider;

    public RedissonTraceConnectionFactoryPostProcessor(ObjectProvider<EasyObservabilityProperties> propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof RedisConnectionFactory factory)
                || !ClassUtils.getUserClass(bean).getName().startsWith("org.redisson.spring.data.connection.RedissonConnectionFactory")) {
            return bean;
        }
        EasyObservabilityProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null) return bean;
        Set<Class<?>> interfaces = publicInterfaces(bean.getClass());
        interfaces.add(RedisConnectionFactory.class);
        return Proxy.newProxyInstance(bean.getClass().getClassLoader(), interfaces.toArray(Class<?>[]::new),
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return invokeTarget(method, factory, args);
                    Object result = invokeTarget(method, factory, args);
                    return result instanceof RedisConnection connection ? wrap(connection, properties) : result;
                });
    }

    private RedisConnection wrap(RedisConnection connection, EasyObservabilityProperties properties) {
        Set<Class<?>> interfaces = publicInterfaces(connection.getClass());
        interfaces.add(RedisConnection.class);
        return (RedisConnection) Proxy.newProxyInstance(connection.getClass().getClassLoader(),
                interfaces.toArray(Class<?>[]::new), new ConnectionHandler(connection, properties));
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final RedisConnection delegate;
        private final EasyObservabilityProperties properties;

        private ConnectionHandler(RedisConnection delegate, EasyObservabilityProperties properties) {
            this.delegate = delegate;
            this.properties = properties;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class || !isCommand(method)) return invokeTarget(method, delegate, args);
            // Redis is always a dependency operation. Only an inbound HTTP, job, or Kafka
            // consumer observation may create a trace root.
            String name = method.getName();
            long startedNanos = System.nanoTime();
            TraceContext.DetachedSpan span = TraceContext.startDetachedSpan("Redis " + name, SpanKind.REDIS, name);
            Throwable failure = null;
            try {
                return invokeTarget(method, delegate, args);
            } catch (Throwable error) {
                failure = error;
                throw error;
            } finally {
                finish(startedNanos, span, failure, properties);
            }
        }
    }

    private void finish(long startedNanos, TraceContext.DetachedSpan span, Throwable failure,
                        EasyObservabilityProperties properties) {
        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        long threshold = properties.getTrace().getRedisSlowThresholdMs();
        boolean slow = threshold > 0 && durationMs >= threshold;
        try {
            span.close();
            if (slow) TraceContext.markSlow("SLOW_REDIS");
            if (failure != null) TraceContext.markError("REDIS_ERROR", failure);
        } catch (Throwable ignored) {
            // Instrumentation must never change the application's Redis result.
        }
    }

    private static boolean isCommand(Method method) {
        return switch (method.getName()) {
            case "close", "isClosed", "getNativeConnection", "getSubscription", "openPipeline", "closePipeline",
                    "isQueueing", "open", "isPipelined", "multi", "exec", "discard", "watch", "unwatch" -> false;
            default -> true;
        };
    }

    private static Object invokeTarget(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static Set<Class<?>> publicInterfaces(Class<?> type) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            ClassUtils.getAllInterfacesForClassAsSet(current).stream()
                    .filter(candidate -> java.lang.reflect.Modifier.isPublic(candidate.getModifiers()))
                    .forEach(interfaces::add);
        }
        return interfaces;
    }
}
