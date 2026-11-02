package io.github.lakernote.observability.metrics;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rrd4jMetricsStoreTest {
    @TempDir
    Path directory;

    @Test
    void labelsTimerCountsByTheWorkUnitTheyMeasure() {
        Map<String, java.util.List<String>> labels = Map.of(
                "http_server_uri", java.util.List.of("GET /orders"),
                "scheduled", java.util.List.of("dailyJob"),
                "kafka_listener", java.util.List.of("orders-listener"),
                "kafka_template", java.util.List.of("orders-template"));

        Set<String> names = MetricsCatalog.dataSources(labels, false).stream()
                .map(MetricsCatalog.DataSource::displayName)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(names.contains("定时任务执行次数"));
        assertTrue(names.contains("定时任务错误次数"));
        assertTrue(names.contains("Kafka 消费次数"));
        assertTrue(names.contains("Kafka 发送次数"));
        assertTrue(names.contains("HTTP 入口请求量"));
        assertTrue(names.contains("HTTP 入口错误数"));
        assertTrue(names.contains("HTTP 入口平均耗时"));
    }

    @Test
    void reportsTheConfiguredRrdArchiveInterval() throws Exception {
        EasyObservabilityProperties.Metrics settings = properties();
        settings.setStep(Duration.ofSeconds(40));
        Rrd4jMetricsStore store = new Rrd4jMetricsStore(settings);

        assertEquals(40, store.fetch("1h").pointIntervalSeconds());
        assertEquals(320, store.fetch("7d").pointIntervalSeconds());
        assertEquals(3_600, store.fetch("30d").pointIntervalSeconds());

        settings = properties();
        settings.setStep(Duration.ofHours(1));
        store = new Rrd4jMetricsStore(settings);
        assertEquals(7_200, store.fetch("7d").pointIntervalSeconds());
        assertEquals(10_800, store.fetch("30d").pointIntervalSeconds());
    }

    @Test
    void keepsBoundedTaggedSeriesStableAcrossRestartAndUsesOverflowSeries() throws Exception {
        Rrd4jMetricsStore store = new Rrd4jMetricsStore(properties());
        MetricsCatalog.SeriesFamily family = MetricsCatalog.family("http_server_uri");
        for (int index = 0; index < family.limit(); index++) {
            assertEquals(index, store.seriesSlotIndex(family.key(), "/route/" + index));
        }
        assertEquals(family.limit(), store.seriesSlotIndex(family.key(), "/route/overflow"));
        assertEquals(family.limit(), store.seriesSlotIndex(family.key(), "/another/overflow"));

        Rrd4jMetricsStore.History history = store.fetch("1h");
        assertEquals(MetricsCatalog.dataSources(Map.of(family.key(), java.util.stream.Stream.concat(
                java.util.stream.IntStream.range(0, family.limit()).mapToObj(index -> "/route/" + index),
                java.util.stream.Stream.of("其他 URI")) .toList()), false).size(), history.series().size());
        assertTrue(history.series().stream().anyMatch(series -> "其他 HTTP 入口 URI".equals(series.legend())));

        Rrd4jMetricsStore restarted = new Rrd4jMetricsStore(properties());
        assertEquals(0, restarted.seriesSlotIndex(family.key(), "/route/0"));
        assertEquals(family.limit(), restarted.seriesSlotIndex(family.key(), "/new/overflow"));
        assertTrue(restarted.status().storageBytes() <= DataSize.ofMegabytes(64).toBytes());
    }

    @Test
    void excludesTheComponentOwnHttpEndpointsFromUriSeries() {
        Rrd4jMetricsStore store = new Rrd4jMetricsStore(properties());
        Rrd4jStepMeterRegistry registry = new Rrd4jStepMeterRegistry(properties(), store);
        try {
            Timer.builder("http.server.requests").tag("uri", "/api/easy-observability/metrics")
                    .tag("method", "GET").tag("outcome", "SUCCESS").register(registry);
            assertTrue(registry.getMeters().isEmpty());
            assertEquals(0, store.seriesSlotIndex("http_server_uri", "/business"));
            Timer.builder("http.server.requests").tag("uri", "/actuator/health")
                    .tag("method", "GET").register(registry);
            assertTrue(registry.getMeters().isEmpty());
        } finally {
            registry.stop();
        }
    }

    @Test
    void omitsMemoryPoolsWithoutAFixedMaximumFromHistory() throws Exception {
        Rrd4jMetricsStore store = new Rrd4jMetricsStore(properties());
        assertEquals(0, store.seriesSlotIndex("memory", "heap · Metaspace"));
        assertEquals(1, store.seriesSlotIndex("memory", "heap · G1 Old Gen"));
        store.update(Map.of("memory_00_max", 0.0, "memory_01_max", 1024.0));

        Rrd4jMetricsStore.History history = store.fetch("1h");
        assertTrue(history.points().stream().noneMatch(point -> point.values().containsKey("memory_00_max")));
        assertTrue(history.points().stream().anyMatch(point -> point.values().getOrDefault("memory_01_max", 0.0) == 1024.0));
    }

    @Test
    void keepsHttpMethodAndNormalizedUriAsTheSeriesDimension() {
        Rrd4jMetricsStore store = new Rrd4jMetricsStore(properties());
        Rrd4jStepMeterRegistry registry = new Rrd4jStepMeterRegistry(properties(), store);
        try {
            Timer.builder("http.server.requests").tag("uri", "/api/orders/123?debug=true")
                    .tag("method", "GET").tag("outcome", "SUCCESS").register(registry);
            assertEquals("http_server_uri_00", registry.getMeters().get(0).getId().getTag("series"));
            assertEquals(0, store.seriesSlotIndex("http_server_uri", "GET /api/orders/{id}"));
        } finally {
            registry.stop();
        }
    }

    @Test
    void keepsSpringKafkaListenerIdTopicAndConsumerGroupAsSeriesDimensions() {
        Rrd4jMetricsStore store = new Rrd4jMetricsStore(properties());
        Rrd4jStepMeterRegistry registry = new Rrd4jStepMeterRegistry(properties(), store);
        try {
            Timer.builder("spring.kafka.listener")
                    .tag("spring.kafka.listener.id", "demo-primary-listener")
                    .tag("messaging.source.name", "orders")
                    .tag("messaging.kafka.consumer.group", "orders-workers")
                    .register(registry);

            assertEquals("kafka_listener_00", registry.getMeters().get(0).getId().getTag("series"));
            assertEquals(0, store.seriesSlotIndex("kafka_listener",
                    "spring.kafka.listener.id=demo-primary-listener · messaging.source.name=orders"
                            + " · messaging.kafka.consumer.group=orders-workers"));
        } finally {
            registry.stop();
        }
    }

    @Test
    void classifiesSpringKafkaErrorTagForRequestErrorCounts() {
        Rrd4jMetricsStore store = new Rrd4jMetricsStore(properties());
        Rrd4jStepMeterRegistry registry = new Rrd4jStepMeterRegistry(properties(), store);
        try {
            Timer success = Timer.builder("spring.kafka.listener")
                    .tag("spring.kafka.listener.id", "orders-listener")
                    .tag("error", "none")
                    .register(registry);
            Timer failure = Timer.builder("spring.kafka.listener")
                    .tag("spring.kafka.listener.id", "orders-listener")
                    .tag("error", "IllegalStateException")
                    .register(registry);

            assertEquals("success", success.getId().getTag("outcome"));
            assertEquals("error", failure.getId().getTag("outcome"));
        } finally {
            registry.stop();
        }
    }

    @Test
    void keepsSpringKafkaTemplateNameAndDestinationAsSeriesDimensions() {
        Rrd4jMetricsStore store = new Rrd4jMetricsStore(properties());
        Rrd4jStepMeterRegistry registry = new Rrd4jStepMeterRegistry(properties(), store);
        try {
            Timer.builder("spring.kafka.template")
                    .tag("spring.kafka.template.name", "demoKafkaTemplate")
                    .tag("messaging.destination.name", "orders")
                    .register(registry);

            assertEquals(0, store.seriesSlotIndex("kafka_template",
                    "spring.kafka.template.name=demoKafkaTemplate · messaging.destination.name=orders"));
        } finally {
            registry.stop();
        }
    }

    private EasyObservabilityProperties.Metrics properties() {
        EasyObservabilityProperties.Metrics properties = new EasyObservabilityProperties.Metrics();
        properties.setStorageDirectory(directory.toString());
        properties.setStep(Duration.ofSeconds(10));
        properties.setMaxStorageBytes(DataSize.ofMegabytes(64));
        return properties;
    }
}
