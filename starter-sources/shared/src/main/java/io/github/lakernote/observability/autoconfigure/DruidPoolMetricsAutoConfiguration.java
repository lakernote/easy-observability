package io.github.lakernote.observability.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Adds Druid pool gauges without requiring the Druid classes as a starter dependency. */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnClass(DataSource.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DruidPoolMetricsAutoConfiguration {
    @Bean
    SmartInitializingSingleton druidPoolMetrics(ConfigurableListableBeanFactory beanFactory, MeterRegistry registry) {
        return () -> {
            Set<Object> bound = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String beanName : beanFactory.getBeanNamesForType(DataSource.class, false, false)) {
                Object target = unwrap(beanFactory.getBean(beanName));
                if (target == null || !isDruid(target.getClass()) || !bound.add(target)) continue;
                bind(registry, target, beanName);
            }
        };
    }

    private static void bind(MeterRegistry registry, Object source, String beanName) {
        Tags tags = Tags.of("name", beanName);
        gauge(registry, source, tags, "active", "getActiveCount");
        gauge(registry, source, tags, "idle", "getPoolingCount");
        gauge(registry, source, tags, "max", "getMaxActive");
        gauge(registry, source, tags, "wait.count", "getNotEmptyWaitCount");
        gauge(registry, source, tags, "wait.millis", "getNotEmptyWaitMillis");
        gauge(registry, source, tags, "execute.count", "getExecuteCount");
        gauge(registry, source, tags, "error.count", "getErrorCount");
    }

    private static void gauge(MeterRegistry registry, Object source, Tags tags, String metric, String getter) {
        Method method;
        try { method = source.getClass().getMethod(getter); }
        catch (ReflectiveOperationException ignored) { return; }
        MeterBinder binder = targetRegistry -> targetRegistry.gauge("druid.datasource." + metric, tags, source,
                value -> read(method, value));
        binder.bindTo(registry);
    }

    private static double read(Method method, Object source) {
        try {
            Object value = method.invoke(source);
            return value instanceof Number number ? number.doubleValue() : Double.NaN;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static Object unwrap(Object bean) {
        Object current = bean;
        for (int depth = 0; depth < 8 && current instanceof Advised advised; depth++) {
            try {
                Object target = advised.getTargetSource().getTarget();
                if (target == null || target == current) break;
                current = target;
            } catch (Exception ignored) { break; }
        }
        return current;
    }

    private static boolean isDruid(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals("com.alibaba.druid.pool.DruidDataSource")) return true;
        }
        return false;
    }
}
