package io.github.lakernote.observability.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.metrics.OperationRecorder;
import io.github.lakernote.observability.trace.store.IncidentTraceStore;
import io.github.lakernote.observability.metrics.history.MetricHistoryStore;
import io.github.lakernote.observability.metrics.history.MetricHistoryPoint;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.HashMap;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only data API used by the bundled Easy Observability page. */
@RestController
@RequestMapping("/api/easy-observability")
public class ObservabilityController {
    private final MeterRegistry meterRegistry;
    private final RecentTraceStore recentTraces;
    private final MetricHistoryStore metricHistoryStore;
    private final IncidentTraceStore incidentTraces;
    private final ObjectMapper objectMapper;
    private final ObservabilityAccessPolicy accessPolicy;

    @Autowired
    public ObservabilityController(MeterRegistry meterRegistry, RecentTraceStore recentTraces,
                                  MetricHistoryStore metricHistoryStore,
                                  IncidentTraceStore incidentTraces, ObjectMapper objectMapper,
                                  ObservabilityAccessPolicy accessPolicy) {
        this.meterRegistry = meterRegistry;
        this.recentTraces = recentTraces;
        this.metricHistoryStore = metricHistoryStore;
        this.incidentTraces = incidentTraces;
        this.objectMapper = objectMapper;
        this.accessPolicy = accessPolicy;
    }

    public ObservabilityController(MeterRegistry meterRegistry, RecentTraceStore recentTraces,
                                  ObservabilityAccessPolicy accessPolicy) {
        this(meterRegistry, recentTraces, null, null, new ObjectMapper(), accessPolicy);
    }

    @GetMapping("/metrics")
    public List<MetricRecord> metrics(@RequestParam(defaultValue = "500") int limit) {
        requireAccess();
        return meterRegistry.getMeters().stream().map(ObservabilityController::metricRecord)
                .sorted(Comparator.comparing(MetricRecord::name).thenComparing(item -> item.tags().toString()))
                .limit(Math.max(1, Math.min(limit, 2000))).toList();
    }

    @GetMapping("/traces")
    public List<RecentTraceStore.TraceRecord> traces(@RequestParam(defaultValue = "100") int limit,
                                                     @RequestParam(defaultValue = "") String source,
                                                     @RequestParam(defaultValue = "") String operation) {
        requireAccess();
        if (!source.isBlank() || !operation.isBlank()) {
            // 入口统计是当前进程的 Micrometer 累计值；下钻只查当前进程的有界 Trace，避免混入重启前持久化事件。
            return recentTraces.recent(500).stream()
                    .filter(trace -> source.isBlank() || source.equalsIgnoreCase(trace.sourceType()))
                    .filter(trace -> operation.isBlank() || trace.operations().stream()
                            .filter(item -> item.type().equalsIgnoreCase(trace.sourceType()))
                            .map(OperationRecorder::operationTag).anyMatch(operation::equals))
                    .limit(Math.max(1, Math.min(limit, 500))).toList();
        }
        return allTraces(limit);
    }

    /** 保留普通 Java 调用方的原有签名。 */
    public List<RecentTraceStore.TraceRecord> traces(int limit) {
        return traces(limit, "", "");
    }

    @GetMapping("/request-statistics")
    public List<RequestStatistic> requestStatistics(@RequestParam(defaultValue = "") String source,
                                                    @RequestParam(defaultValue = "200") int limit) {
        requireAccess();
        Map<StatisticKey, MutableStatistic> statistics = new HashMap<>();
        for (Meter meter : meterRegistry.getMeters()) {
            Meter.Id id = meter.getId();
            if (!(meter instanceof Timer timer) || !"easy.operations.duration".equals(id.getName())) continue;
            String operationSource = id.getTag("source");
            String operation = id.getTag("operation");
            if (operationSource == null || operation == null || operation.isBlank()) continue;
            if (!List.of("HTTP", "JOB", "KAFKA_CONSUMER").contains(operationSource)) continue;
            if (!source.isBlank() && !"ALL".equalsIgnoreCase(source)
                    && !source.equalsIgnoreCase(operationSource)) continue;
            StatisticKey key = new StatisticKey(operationSource, operation);
            MutableStatistic value = statistics.computeIfAbsent(key, ignored -> new MutableStatistic());
            long count = timer.count();
            value.count += count;
            value.totalDurationMs += timer.totalTime(TimeUnit.MILLISECONDS);
            value.maxDurationMs = Math.max(value.maxDurationMs, timer.max(TimeUnit.MILLISECONDS));
            if ("failure".equalsIgnoreCase(id.getTag("outcome"))) value.errorCount += count;
        }
        addResourceStatistics(statistics, "easy.operations.cpu.time", true);
        addResourceStatistics(statistics, "easy.operations.allocated", false);
        return statistics.entrySet().stream()
                .map(entry -> entry.getValue().toRecord(entry.getKey()))
                .sorted(Comparator.comparingDouble(RequestStatistic::totalDurationMs).reversed())
                .limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    private void addResourceStatistics(Map<StatisticKey, MutableStatistic> statistics, String metricName,
                                       boolean cpuTime) {
        for (Meter meter : meterRegistry.getMeters()) {
            Meter.Id id = meter.getId();
            if (!(meter instanceof DistributionSummary summary) || !metricName.equals(id.getName())) continue;
            String source = id.getTag("source");
            String operation = id.getTag("operation");
            if (source == null || operation == null || operation.isBlank()) continue;
            MutableStatistic value = statistics.get(new StatisticKey(source, operation));
            if (value == null) continue;
            double total = summary.totalAmount();
            long count = summary.count();
            if (cpuTime) {
                value.cpuTotal += total;
                value.cpuCount += count;
            } else {
                value.allocatedTotal += total;
                value.allocatedCount += count;
            }
        }
    }

    @GetMapping("/incidents")
    public List<RecentTraceStore.TraceRecord> incidents(@RequestParam String kind,
                                                     @RequestParam(defaultValue = "") String source,
                                                     @RequestParam(defaultValue = "100") int limit) {
        requireAccess();
        if (!"slow".equalsIgnoreCase(kind) && !"error".equalsIgnoreCase(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind must be slow or error");
        }
        String sourceFilter = source.trim();
        return allTraces(500).stream()
                .filter(trace -> "slow".equalsIgnoreCase(kind) ? trace.slow() : trace.error())
                .filter(trace -> sourceFilter.isEmpty() || sourceFilter.equalsIgnoreCase(trace.sourceType()))
                .limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    private List<RecentTraceStore.TraceRecord> allTraces(int limit) {
        Map<TraceIdentity, RecentTraceStore.TraceRecord> merged = new LinkedHashMap<>();
        if (incidentTraces != null) incidentTraces.recent(limit)
                .forEach(trace -> merged.put(traceIdentity(trace), trace));
        recentTraces.recent(limit).forEach(trace -> merged.putIfAbsent(traceIdentity(trace), trace));
        return merged.values().stream().sorted(Comparator.comparing(RecentTraceStore.TraceRecord::finishedAt).reversed())
                .limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    private static TraceIdentity traceIdentity(RecentTraceStore.TraceRecord trace) {
        return new TraceIdentity(trace.traceId(), trace.sourceType(), trace.finishedAt());
    }

    @GetMapping("/traces/{traceId}")
    public RecentTraceStore.TraceRecord trace(@PathVariable String traceId,
                                              @RequestParam(defaultValue = "") String source) {
        requireAccess();
        RecentTraceStore.TraceRecord trace = recentTraces.find(traceId, source);
        return trace != null || incidentTraces == null ? trace : incidentTraces.find(traceId, source);
    }

    /** Preserves the direct Java caller signature used by integrations and tests. */
    public RecentTraceStore.TraceRecord trace(String traceId) {
        return trace(traceId, "");
    }

    @GetMapping("/metrics/series")
    public List<MetricHistoryStore.MetricSeriesDescriptor> metricSeries() {
        requireAccess();
        return metricHistoryStore == null ? List.of() : metricHistoryStore.listSeries();
    }

    @GetMapping("/metrics/history")
    public List<MetricHistoryPoint> queryHistory(@RequestParam String name,
                                                   @RequestParam(defaultValue = "") String tags,
                                                   @RequestParam(defaultValue = "1000") int limit) {
        requireAccess();
        Map<String, String> tagFilter = Map.of();
        try {
            if (!tags.isBlank()) tagFilter = objectMapper.readValue(tags, new TypeReference<>() { });
        } catch (Exception ignored) {
            return List.of();
        }
        return metricHistoryStore == null ? List.of()
                : metricHistoryStore.queryHistory(name, tagFilter, Math.max(1, Math.min(limit, 50000)));
    }

    @GetMapping("/metrics/storage")
    public MetricHistoryStore.StorageStatus metricStorage() {
        requireAccess();
        return metricHistoryStore == null ? null : metricHistoryStore.getStatus();
    }

    private void requireAccess() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static MetricRecord metricRecord(Meter meter) {
        Map<String, String> tags = new LinkedHashMap<>();
        meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
        Map<String, Double> values = new LinkedHashMap<>();
        for (Measurement measurement : meter.measure()) {
            double value = measurement.getValue();
            if (Double.isFinite(value)) values.put(measurement.getStatistic().name(), value);
        }
        return new MetricRecord(meter.getId().getName(), meter.getId().getType().name(), tags, values);
    }

    public record MetricRecord(String name, String type, Map<String, String> tags, Map<String, Double> values) { }

    public record RequestStatistic(String source, String operation, long count, long errorCount,
                                   double errorRate, double totalDurationMs, double meanDurationMs,
                                   double maxDurationMs, double meanCpuTimeMs, double meanAllocatedBytes) { }

    private record StatisticKey(String source, String operation) { }

    private record TraceIdentity(String traceId, String sourceType, Instant finishedAt) { }

    private static final class MutableStatistic {
        private long count;
        private long errorCount;
        private double totalDurationMs;
        private double maxDurationMs;
        private double cpuTotal;
        private long cpuCount;
        private double allocatedTotal;
        private long allocatedCount;

        private RequestStatistic toRecord(StatisticKey key) {
            return new RequestStatistic(key.source(), key.operation(), count, errorCount,
                    count == 0 ? 0 : (double) errorCount / count * 100, totalDurationMs,
                    count == 0 ? 0 : totalDurationMs / count, maxDurationMs,
                    cpuCount == 0 ? 0 : cpuTotal / cpuCount,
                    allocatedCount == 0 ? 0 : allocatedTotal / allocatedCount);
        }
    }
}
