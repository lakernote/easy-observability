package io.github.lakernote.observability.metrics.history;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Measurement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定期保存有界的 Micrometer 指标快照。
 * <p>使用独立守护线程，不依赖宿主开启定时任务；每轮最多采集 512 个优先级较高的 Meter。</p>
 * <p>逐 operation 的 CPU、分配和线程争用明细不进入长期历史，具体值由慢错 Trace 保存。</p>
 */
public final class MetricHistoryCollector implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(MetricHistoryCollector.class);
    private final long intervalSeconds;
    private static final int MAX_METERS = 512;
    private static final String COLLECTOR_PREFIX = "easy.observability.history.collector.";
    private final MeterRegistry registry;
    private final MetricHistoryStore store;
    private volatile ScheduledExecutorService executor;

    public MetricHistoryCollector(MeterRegistry registry, MetricHistoryStore store) {
        this(registry, store, 60_000L);
    }

    public MetricHistoryCollector(MeterRegistry registry, MetricHistoryStore store, long intervalMs) {
        this.registry = registry;
        this.store = store;
        this.intervalSeconds = Math.max(60, Math.min(120, intervalMs / 1000));
    }

    @Override
    public void start() {
        if (executor != null || !store.getStatus().enabled()) return;
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "easy-observability-metrics-history");
            thread.setDaemon(true);
            return thread;
        });
        executor = created;
        created.scheduleWithFixedDelay(this::collectSafely, 15, intervalSeconds, TimeUnit.SECONDS);
        created.scheduleWithFixedDelay(store::pruneExpired, 1, 1, TimeUnit.DAYS);
    }

    public void collect() {
        // 注册表可能包含大量动态 Meter；使用固定容量优先队列，只保留最值得展示的系列。
        Comparator<Meter> order = Comparator.comparingInt((Meter meter) ->
                        MetricHistoryStore.metricPriority(meter.getId().getName(), tags(meter)))
                .thenComparing(meter -> meter.getId().getName())
                .thenComparing(meter -> meter.getId().getTags().toString());
        PriorityQueue<Meter> candidates = new PriorityQueue<>(MAX_METERS, order.reversed());
        for (Meter meter : registry.getMeters()) {
            if (!useful(meter.getId().getName())) continue;
            if (candidates.size() < MAX_METERS) candidates.offer(meter);
            else if (order.compare(meter, candidates.peek()) < 0) {
                candidates.poll();
                candidates.offer(meter);
            }
        }
        List<Meter> selected = candidates.stream().sorted(order).toList();
        store.recordMetricsSnapshot(selected.stream().map(MetricHistoryCollector::snapshot)
                .filter(sample -> !sample.measurements().isEmpty()).toList());
    }

    private void collectSafely() {
        try { collect(); }
        catch (Throwable failure) { log.warn("采集 Micrometer RRD4J 历史失败", failure); }
    }

    private static MetricSample snapshot(Meter meter) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Measurement measurement : meter.measure()) {
            double value = measurement.getValue();
            if (Double.isFinite(value)) values.put(measurement.getStatistic().name(), value);
        }
        return new MetricSample(meter.getId().getName(), tags(meter), values);
    }

    private static Map<String, String> tags(Meter meter) {
        Map<String, String> tags = new LinkedHashMap<>();
        meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
        return tags;
    }

    private static boolean useful(String name) {
        if (name.startsWith(COLLECTOR_PREFIX)) return false;
        // 逐 operation 资源差值会按 operation/outcome 扩展出许多序列；异常和慢操作的精确值
        // 已写入关联 Trace，长期指标历史只保留低基数的总体趋势。
        if (!MetricHistoryStore.shouldPersistMetric(name)) return false;
        return name.startsWith("easy.") || name.startsWith("jvm.") || name.startsWith("process.")
                || name.startsWith("system.") || name.startsWith("http.server.requests")
                || name.startsWith("hikaricp.") || name.startsWith("jdbc.connections.")
                || name.startsWith("tomcat.threads.") || name.startsWith("executor.");
    }

    @Override public void stop() {
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) current.shutdownNow();
    }
    @Override public boolean isRunning() { return executor != null && !executor.isShutdown(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MIN_VALUE; }
}
