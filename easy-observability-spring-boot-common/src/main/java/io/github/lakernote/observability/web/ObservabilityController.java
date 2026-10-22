package io.github.lakernote.observability.web;

import io.github.lakernote.observability.trace.store.IncidentTraceStore;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only Trace API used by the bundled Trace Explorer. */
@RestController
@RequestMapping("/api/easy-observability")
public class ObservabilityController {
    private final RecentTraceStore recentTraces;
    private final IncidentTraceStore incidentTraces;
    private final ObservabilityAccessPolicy accessPolicy;

    @Autowired
    public ObservabilityController(RecentTraceStore recentTraces, IncidentTraceStore incidentTraces,
                                   ObservabilityAccessPolicy accessPolicy) {
        this.recentTraces = recentTraces;
        this.incidentTraces = incidentTraces;
        this.accessPolicy = accessPolicy;
    }

    public ObservabilityController(RecentTraceStore recentTraces, ObservabilityAccessPolicy accessPolicy) {
        this(recentTraces, null, accessPolicy);
    }

    @GetMapping("/traces")
    public List<RecentTraceStore.TraceRecord> traces(@RequestParam(defaultValue = "100") int limit,
                                                     @RequestParam(defaultValue = "") String source,
                                                     @RequestParam(defaultValue = "") String q) {
        requireAccess();
        if (!source.isBlank() || !q.isBlank()) {
            String normalizedQuery = q.trim().toLowerCase(java.util.Locale.ROOT);
            final String query = normalizedQuery.length() > 200
                    ? normalizedQuery.substring(0, 200) : normalizedQuery;
            return allTraces(500).stream()
                    .filter(trace -> source.isBlank() || source.equalsIgnoreCase(trace.sourceType()))
                    .filter(trace -> query.isEmpty() || matchesTrace(trace, query))
                    .limit(Math.max(1, Math.min(limit, 500))).toList();
        }
        return allTraces(limit);
    }

    private static boolean matchesTrace(RecentTraceStore.TraceRecord trace, String query) {
        return containsIgnoreCase(trace.name(), query) || containsIgnoreCase(trace.method(), query)
                || containsIgnoreCase(trace.traceId(), query) || containsIgnoreCase(trace.tree(), query)
                || containsIgnoreCase(trace.errorType(), query) || containsIgnoreCase(trace.errorMessage(), query)
                || containsIgnoreCase(trace.threadName(), query);
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    /** Retains the direct Java caller signature used by integrations and tests. */
    public List<RecentTraceStore.TraceRecord> traces(int limit) {
        return traces(limit, "");
    }

    public List<RecentTraceStore.TraceRecord> traces(int limit, String source) {
        return traces(limit, source, "");
    }

    @GetMapping("/traces/storage")
    public TraceStorageStatus traceStorage() {
        requireAccess();
        IncidentTraceStore.StorageStatus archive = incidentTraces == null ? null : incidentTraces.status();
        return new TraceStorageStatus(recentTraces.size(), recentTraces.capacity(),
                archive == null ? 0 : archive.recordCount(), archive == null ? 0 : archive.capacity(),
                archive == null ? 0 : archive.bytes(), archive == null ? 0 : archive.maxBytes());
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

    @PostMapping("/storage/clear")
    public ClearStorageResult clearTraceHistory() throws IOException {
        requireAccess();
        int archivedTraces = incidentTraces == null ? 0 : incidentTraces.clear();
        int recentTraces = this.recentTraces.clear();
        return new ClearStorageResult(archivedTraces, recentTraces);
    }

    private void requireAccess() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    public record TraceStorageStatus(int recentCount, int recentCapacity, int archivedIncidentCount,
                                     int archivedIncidentCapacity, long archiveBytes, long archiveMaxBytes) { }

    public record ClearStorageResult(int archivedTraces, int recentTraces) { }

    private record TraceIdentity(String traceId, String sourceType, Instant finishedAt) { }
}
