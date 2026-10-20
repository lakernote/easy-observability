package io.github.lakernote.observability.metrics.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MetricHistoryCollectorTest {
    @TempDir
    Path tempDirectory;

    @Test
    void persistsOperationDurationAndSystemTrendsButSkipsPerOperationResourceSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("easy.operations.duration")
                .tag("source", "HTTP")
                .tag("operation", "GET /orders")
                .tag("outcome", "success")
                .register(registry)
                .increment(12);
        Counter.builder("easy.operations.cpu.time").tag("source", "HTTP")
                .tag("operation", "GET /orders").tag("outcome", "success")
                .register(registry).increment(3);
        Counter.builder("easy.operations.allocated").tag("source", "HTTP")
                .tag("operation", "GET /orders").tag("outcome", "success")
                .register(registry).increment(4096);
        Counter.builder("easy.operations.blocked.count").tag("source", "HTTP")
                .tag("operation", "GET /orders").tag("outcome", "success")
                .register(registry).increment(1);
        Counter.builder("easy.operations.blocked.time").tag("source", "HTTP")
                .tag("operation", "GET /orders").tag("outcome", "success")
                .register(registry).increment(2);
        Counter.builder("easy.operations.waited.count").tag("source", "HTTP")
                .tag("operation", "GET /orders").tag("outcome", "success")
                .register(registry).increment(1);
        Counter.builder("easy.operations.waited.time").tag("source", "HTTP")
                .tag("operation", "GET /orders").tag("outcome", "success")
                .register(registry).increment(5);
        Counter.builder("jvm.memory.used").tag("area", "heap")
                .register(registry).increment(1024);
        Counter.builder("logback.events").tag("level", "error")
                .register(registry).increment(2);
        Counter.builder("tomcat.sessions.created").register(registry).increment(3);

        MetricHistoryStore store = new MetricHistoryStore(new ObjectMapper(),
                tempDirectory.toString(), 15, 64L * 1024 * 1024,
                8L * 1024 * 1024, 512, true);
        store.restore();

        new MetricHistoryCollector(registry, store).collect();

        assertThat(store.listSeries()).extracting(MetricHistoryStore.MetricSeriesDescriptor::name)
                .contains("easy.operations.duration", "jvm.memory.used", "logback.events", "tomcat.sessions.created")
                .doesNotContain("easy.operations.cpu.time", "easy.operations.allocated",
                        "easy.operations.blocked.count", "easy.operations.blocked.time",
                        "easy.operations.waited.count", "easy.operations.waited.time");
        assertThat(registry.getMeters()).extracting(meter -> meter.getId().getName())
                .as("filtering applies only to persisted history; existing Micrometer meters stay registered")
                .contains("easy.operations.cpu.time", "easy.operations.allocated");
    }
}
