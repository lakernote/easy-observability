package io.github.lakernote.observability.metrics;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.step.StepRegistryConfig;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** A separate, bounded registry that persists only Easy Observability's public metrics. */
public final class Rrd4jStepMeterRegistry extends StepMeterRegistry {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Rrd4jStepMeterRegistry.class);
    private static final java.util.regex.Pattern IDENTIFIER_PATH_SEGMENT = java.util.regex.Pattern.compile(
            "(?i)(?<=/)([0-9]{2,}|[0-9a-f]{8}-[0-9a-f-]{27,}|[0-9a-f]{16,})(?=/|$)");
    private static final List<String> METER_NAMES = List.of(
            "http.server.requests", "http.client.requests", "tasks.scheduled.execution",
            "spring.kafka.listener", "spring.kafka.template", "spring.data.redis",
            "lettuce.command.completion", "lettuce.command.firstresponse",
            "jvm.memory.used", "jvm.memory.max", "jvm.gc.pause", "process.cpu.usage",
            "system.cpu.usage", "jvm.threads.live", "executor.active", "executor.queued",
            "executor.pool.size", "executor.pool.max", "executor.completed");

    private final Rrd4jMetricsStore store;
    private final java.time.Duration step;
    private volatile boolean storageWarningLogged;

    public Rrd4jStepMeterRegistry(EasyObservabilityProperties.Metrics properties, Rrd4jMetricsStore store) {
        super(new Config(properties), Clock.SYSTEM);
        this.store = store;
        this.step = properties.getStep();
        config().meterFilter(MeterFilter.denyUnless(id -> METER_NAMES.contains(id.getName())));
        config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                String family = familyFor(id.getName());
                if (family == null) return id.replaceTags(Tags.empty());
                if ("http.server.requests".equals(id.getName()) && isInternalHttpEndpoint(id.getTag("uri"))) {
                    return id.withName("easy.observability.internal.http.server.requests").replaceTags(Tags.empty());
                }
                int slot = store.seriesSlotIndex(family, dimensionLabel(id, family));
                return id.replaceTags(Tags.of("series", MetricsCatalog.slotId(family, slot),
                        "outcome", normalizedOutcomeTag(id)));
            }
        });
        // Per-family series limits bound tag cardinality; this final limit bounds meter instances across families.
        config().meterFilter(MeterFilter.maximumAllowableMetrics(512));
    }

    private static String familyFor(String name) {
        if (name.startsWith("http.server.requests")) return "http_server_uri";
        if (name.startsWith("http.client.requests")) return "http_client_uri";
        if ("tasks.scheduled.execution".equals(name)) return "scheduled";
        if ("spring.kafka.listener".equals(name)) return "kafka_listener";
        if ("spring.kafka.template".equals(name)) return "kafka_template";
        if ("spring.data.redis".equals(name) || name.startsWith("lettuce.command.")) return "redis";
        if (name.startsWith("jvm.memory.")) return "memory";
        if ("jvm.gc.pause".equals(name)) return "gc";
        if (name.startsWith("executor.")) return "executor";
        return null;
    }

    private static String dimensionLabel(Meter.Id id, String family) {
        return switch (family) {
            case "http_server_uri", "http_client_uri" ->
                    joinNonblank(" ", id.getTag("method"), normalizedUri(id.getTag("uri")));
            case "scheduled" -> firstNonblank(id, "code.function", "method", "name", "task", "code.namespace");
            case "kafka_listener" -> joinTags(id,
                    "spring.kafka.listener.id", "messaging.source.name", "messaging.kafka.consumer.group",
                    "name", "topic", "group.id", "listener.id");
            case "kafka_template" -> joinTags(id,
                    "spring.kafka.template.name", "messaging.destination.name", "name", "topic");
            case "redis" -> joinTags(id, "command", "operation");
            case "memory" -> joinNonblank(" · ", id.getTag("area"), id.getTag("id"));
            case "gc" -> joinNonblank(" · ", id.getTag("action"), id.getTag("cause"));
            case "executor" -> firstNonblank(id, "name", "executor");
            default -> "实例";
        };
    }

    private static String firstNonblank(Meter.Id id, String... keys) {
        for (String key : keys) {
            String value = id.getTag(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "实例";
    }

    private static String joinTags(Meter.Id id, String... keys) {
        List<String> values = new java.util.ArrayList<>();
        for (String key : keys) {
            String value = id.getTag(key);
            if (value != null && !value.isBlank()) values.add(key + "=" + value);
        }
        return values.isEmpty() ? "实例" : String.join(" · ", values);
    }

    private static String joinNonblank(String separator, String... values) {
        List<String> present = java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank()).toList();
        return present.isEmpty() ? "实例" : String.join(separator, present);
    }

    private static String normalizedUri(String uri) {
        if (uri == null || uri.isBlank()) return "UNKNOWN";
        int query = uri.indexOf('?');
        int fragment = uri.indexOf('#');
        int end = query < 0 ? uri.length() : query;
        if (fragment >= 0) end = Math.min(end, fragment);
        return IDENTIFIER_PATH_SEGMENT.matcher(uri.substring(0, end)).replaceAll("{id}");
    }

    private static boolean isInternalHttpEndpoint(String uri) {
        return uri != null && (uri.startsWith("/easy-observability/")
                || uri.startsWith("/api/easy-observability/") || uri.startsWith("/actuator/"));
    }

    private static String normalizedOutcomeTag(Meter.Id id) {
        String outcome = id.getTag("outcome");
        String result = id.getTag("result");
        String error = id.getTag("exception");
        String errorTag = id.getTag("error");
        String status = id.getTag("status");
        String raw = outcome != null ? outcome : result != null ? result : status;
        return outcome != null || result != null ? normalizeOutcome(raw)
                : status != null ? normalizeOutcome(status)
                : errorTag != null ? normalizeErrorTag(errorTag)
                : error != null ? ("none".equalsIgnoreCase(error) ? "success" : "error") : "other";
    }

    private static String normalizeErrorTag(String error) {
        if (error == null || error.isBlank()) return "other";
        return "none".equalsIgnoreCase(error) ? "success" : "error";
    }

    private static String normalizeOutcome(String value) {
        if (value == null || value.isBlank()) return "other";
        try {
            int status = Integer.parseInt(value);
            return status >= 400 ? "error" : "success";
        } catch (NumberFormatException ignored) {
            // Non-HTTP outcomes are normalized below.
        }
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCESS", "SUCCESSFUL", "COMPLETED", "OK", "NONE" -> "success";
            case "ERROR", "FAILURE", "FAILED", "CLIENT_ERROR", "SERVER_ERROR" -> "error";
            default -> "other";
        };
    }

    @Override
    protected void publish() {
        Map<String, Aggregate> totals = new HashMap<>();
        Map<String, Aggregate> tagged = new HashMap<>();
        for (Meter meter : getMeters()) {
            String name = meter.getId().getName();
            Aggregate total = totals.computeIfAbsent(name, ignored -> new Aggregate());
            Aggregate series = meter.getId().getTag("series") == null ? null
                    : tagged.computeIfAbsent(name + "|" + meter.getId().getTag("series"), ignored -> new Aggregate());
            if (meter instanceof Timer timer) {
                double count = timer.count();
                double elapsed = timer.totalTime(TimeUnit.MILLISECONDS);
                total.recordTimer(count, elapsed, "error".equals(meter.getId().getTag("outcome")));
                if (series != null) series.recordTimer(count, elapsed, "error".equals(meter.getId().getTag("outcome")));
            } else if (meter instanceof FunctionCounter counter) {
                double count = counter.count();
                total.count += count;
                if (series != null) series.count += count;
            } else if (meter instanceof Gauge gauge) {
                double value = gauge.value();
                if (Double.isFinite(value) && (!name.startsWith("jvm.memory.") || value >= 0)) {
                    total.recordGauge(value);
                    if (series != null) series.recordGauge(value);
                }
            }
        }

        Map<String, Double> values = new HashMap<>();
        Map<String, Aggregate> redisBySeries = new HashMap<>();
        java.util.Set<String> springRedisSeries = new java.util.HashSet<>();
        for (Map.Entry<String, Aggregate> entry : tagged.entrySet()) {
            int separator = entry.getKey().indexOf('|');
            String meter = entry.getKey().substring(0, separator);
            String slot = entry.getKey().substring(separator + 1);
            String slotNumber = slot.substring(slot.lastIndexOf('_') + 1);
            Aggregate aggregate = entry.getValue();
            if ("http.server.requests".equals(meter)) putTimer(values, "http_server_" + slotNumber, aggregate);
            else if ("http.client.requests".equals(meter)) putTimer(values, "http_client_" + slotNumber, aggregate);
            else if ("tasks.scheduled.execution".equals(meter)) putTimer(values, slot, aggregate);
            else if ("spring.kafka.listener".equals(meter)) putTimer(values, slot, aggregate);
            else if ("spring.kafka.template".equals(meter)) putTimer(values, slot, aggregate);
            else if ("spring.data.redis".equals(meter)) {
                if (aggregate.count > 0 || !redisBySeries.containsKey(slot)) {
                    redisBySeries.put(slot, aggregate);
                }
                if (aggregate.count > 0) springRedisSeries.add(slot);
            } else if ("lettuce.command.completion".equals(meter)) {
                if (!springRedisSeries.contains(slot)) redisBySeries.put(slot, aggregate);
            } else if ("lettuce.command.firstresponse".equals(meter) && aggregate.count > 0) {
                values.put(slot + "_first_response_ms", mean(aggregate));
            } else if ("jvm.memory.used".equals(meter)) values.put(slot + "_used", aggregate.gaugeTotal);
            else if ("jvm.memory.max".equals(meter)) values.put(slot + "_max", aggregate.gaugeTotal);
            else if ("jvm.gc.pause".equals(meter)) putTimer(values, slot, aggregate);
            else if ("executor.active".equals(meter)) values.put(slot + "_active", aggregate.gaugeTotal);
            else if ("executor.queued".equals(meter)) values.put(slot + "_queued", aggregate.gaugeTotal);
            else if ("executor.pool.size".equals(meter)) values.put(slot + "_pool_size", aggregate.gaugeTotal);
            else if ("executor.pool.max".equals(meter)) values.put(slot + "_pool_max",
                    aggregate.hasUnboundedExecutor ? Integer.MAX_VALUE : aggregate.gaugeTotal);
            else if ("executor.completed".equals(meter)) values.put(slot + "_completed", aggregate.count);
        }
        redisBySeries.forEach((slot, aggregate) -> putTimer(values, slot, aggregate));

        putGauge(values, totals, "process.cpu.usage", "process_cpu_pct", 100.0);
        putGauge(values, totals, "system.cpu.usage", "system_cpu_pct", 100.0);
        putGauge(values, totals, "jvm.threads.live", "jvm_threads_live", 1.0);

        try {
            store.update(values);
            if (storageWarningLogged) {
                LOGGER.info("RRD4J public metrics history writes have recovered");
                storageWarningLogged = false;
            }
        } catch (IOException | RuntimeException failure) {
            if (!storageWarningLogged) {
                LOGGER.warn("Could not write public metrics history to RRD4J", failure);
                storageWarningLogged = true;
            }
        }
    }

    private static void putTimer(Map<String, Double> values, String prefix, Aggregate aggregate) {
        values.put(prefix + "_count", aggregate.count);
        values.put(prefix + "_errors", aggregate.errors);
        if (aggregate.count > 0) values.put(prefix + "_avg_ms", mean(aggregate));
    }

    private static void putGauge(Map<String, Double> values, Map<String, Aggregate> totals,
                                 String name, String target, double multiplier) {
        Aggregate aggregate = totals.get(name);
        if (aggregate != null && aggregate.gaugeCount > 0) {
            values.put(target, aggregate.gaugeTotal / aggregate.gaugeCount * multiplier);
        }
    }

    private static double mean(Aggregate aggregate) {
        return aggregate.count == 0 ? 0 : aggregate.totalMillis / aggregate.count;
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.builder()
                .percentilesHistogram(false)
                .expiry(step)
                .build()
                .merge(DistributionStatisticConfig.DEFAULT);
    }

    private static final class Aggregate {
        private double count;
        private double errors;
        private double totalMillis;
        private double gaugeTotal;
        private int gaugeCount;
        private boolean hasUnboundedExecutor;

        private void recordTimer(double timerCount, double totalTime, boolean error) {
            count += timerCount;
            totalMillis += totalTime;
            if (error) errors += timerCount;
        }

        private void recordGauge(double value) {
            gaugeTotal += value;
            gaugeCount++;
            if (value >= Integer.MAX_VALUE) hasUnboundedExecutor = true;
        }
    }

    private record Config(EasyObservabilityProperties.Metrics properties) implements StepRegistryConfig {
        @Override public String prefix() { return "easy-observability.rrd"; }
        @Override public String get(String key) { return null; }
        @Override public java.time.Duration step() { return properties.getStep(); }
    }
}
