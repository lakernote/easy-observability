package io.github.lakernote.observability.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Public metric series persisted by Easy Observability. */
public final class MetricsCatalog {
    public static final List<SeriesFamily> FAMILIES = List.of(
            new SeriesFamily("http_server_uri", "HTTP 入口 URI", 24),
            new SeriesFamily("http_client_uri", "HTTP 出口 URI", 24),
            new SeriesFamily("scheduled", "任务", 12),
            new SeriesFamily("kafka_listener", "Kafka Listener", 12),
            new SeriesFamily("kafka_template", "Kafka 发送", 12),
            new SeriesFamily("redis", "Redis 命令", 16),
            new SeriesFamily("memory", "内存池", 16),
            new SeriesFamily("gc", "GC", 8),
            new SeriesFamily("executor", "线程池", 16));

    public static final List<DataSource> FIXED_DATA_SOURCES = List.of(
            new DataSource("process_cpu_pct", "进程 CPU 使用率", "%"),
            new DataSource("system_cpu_pct", "系统 CPU 使用率", "%"),
            new DataSource("jvm_threads_live", "JVM 活跃线程数", "count"));

    private MetricsCatalog() { }

    public static List<DataSource> dataSources(Map<String, List<String>> labelsByFamily,
                                               boolean includeEmptySlots) {
        List<DataSource> sources = new ArrayList<>(FIXED_DATA_SOURCES);
        for (SeriesFamily family : FAMILIES) {
            List<String> labels = labelsByFamily.getOrDefault(family.key(), List.of());
            for (int slot = 0; slot <= family.limit(); slot++) {
                String label = slot < labels.size() ? labels.get(slot) : null;
                if (!includeEmptySlots && label == null) continue;
                String legend = label == null ? "其他 " + family.legend() : label;
                addFamilySources(sources, family.key(), slot, legend);
            }
        }
        return List.copyOf(sources);
    }

    public static SeriesFamily family(String key) {
        return FAMILIES.stream().filter(item -> item.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown metric series family: " + key));
    }

    public static String slotId(String family, int slot) {
        return "%s_%02d".formatted(family, slot);
    }

    private static void addFamilySources(List<DataSource> target, String family, int slot, String legend) {
        String prefix = slotId(family, slot);
        switch (family) {
            case "http_server_uri" -> {
                String numbered = "%02d".formatted(slot);
                addTimer(target, "http_server_" + numbered, "HTTP 入口请求量", "HTTP 入口错误数",
                        "HTTP 入口平均耗时", "http_server", legend);
            }
            case "http_client_uri" -> {
                String numbered = "%02d".formatted(slot);
                addTimer(target, "http_client_" + numbered, "HTTP 出口请求量", "HTTP 出口错误数",
                        "HTTP 出口平均耗时", "http_client", legend);
            }
            case "scheduled" -> addTimer(target, prefix, "定时任务执行次数", "定时任务错误次数",
                    "定时任务平均耗时", "scheduled", legend);
            case "kafka_listener" -> addTimer(target, prefix, "Kafka 消费次数", "Kafka 消费错误次数",
                    "Kafka 消费平均耗时", "kafka_listener", legend);
            case "kafka_template" -> addTimer(target, prefix, "Kafka 发送次数", "Kafka 发送错误次数",
                    "Kafka 发送平均耗时", "kafka_template", legend);
            case "redis" -> {
                target.add(new DataSource(prefix + "_count", "Redis 命令数", "count", "redis_count", legend));
                target.add(new DataSource(prefix + "_avg_ms", "Redis 平均耗时", "ms", "redis_avg_ms", legend));
                target.add(new DataSource(prefix + "_first_response_ms", "Redis 首次响应平均耗时", "ms",
                        "redis_first_response_ms", legend));
            }
            case "memory" -> {
                target.add(new DataSource(prefix + "_used", "JVM 内存池已用", "bytes", "jvm_memory_used", legend));
                target.add(new DataSource(prefix + "_max", "JVM 内存池上限", "bytes", "jvm_memory_max", legend));
            }
            case "gc" -> {
                target.add(new DataSource(prefix + "_count", "GC 次数", "count", "jvm_gc_count", legend));
                target.add(new DataSource(prefix + "_avg_ms", "GC 平均暂停", "ms", "jvm_gc_avg_ms", legend));
            }
            case "executor" -> {
                target.add(new DataSource(prefix + "_active", "线程池活跃线程", "count", "executor_active", legend));
                target.add(new DataSource(prefix + "_queued", "线程池排队任务", "count", "executor_queued", legend));
                target.add(new DataSource(prefix + "_pool_size", "线程池当前线程", "count", "executor_pool_size", legend));
                target.add(new DataSource(prefix + "_pool_max", "线程池最大线程", "count", "executor_pool_max", legend));
                target.add(new DataSource(prefix + "_completed", "线程池完成任务数", "count", "executor_completed", legend));
            }
            default -> throw new IllegalArgumentException("Unknown metric series family: " + family);
        }
    }

    private static void addTimer(List<DataSource> target, String prefix, String countName, String errorName,
                                 String averageName, String chartPrefix, String legend) {
        target.add(new DataSource(prefix + "_count", countName, "count", chartPrefix + "_count", legend));
        target.add(new DataSource(prefix + "_errors", errorName, "count", chartPrefix + "_errors", legend));
        target.add(new DataSource(prefix + "_avg_ms", averageName, "ms", chartPrefix + "_avg_ms", legend));
    }

    public record SeriesFamily(String key, String legend, int limit) { }

    public record DataSource(String id, String displayName, String unit, String chartId, String legend) {
        public DataSource(String id, String displayName, String unit) {
            this(id, displayName, unit, id, displayName);
        }
    }
}
