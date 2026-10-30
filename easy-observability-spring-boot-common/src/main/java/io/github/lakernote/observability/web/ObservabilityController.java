package io.github.lakernote.observability.web;

import io.github.lakernote.observability.trace.store.DiagnosticTraceStore;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.github.lakernote.observability.trace.TraceNode;
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
    private final RecentTraceStore recentTraceStore;
    private final DiagnosticTraceStore diagnosticTraceStore;
    private final ObservabilityAccessPolicy accessPolicy;

    public ObservabilityController(RecentTraceStore recentTraceStore, DiagnosticTraceStore diagnosticTraceStore,
                                   ObservabilityAccessPolicy accessPolicy) {
        this.recentTraceStore = recentTraceStore;
        this.diagnosticTraceStore = diagnosticTraceStore;
        this.accessPolicy = accessPolicy;
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
            return allDiagnosticTraces(500).stream()
                    .filter(trace -> source.isBlank() || source.equalsIgnoreCase(trace.sourceType()))
                    .filter(trace -> query.isEmpty() || matchesTrace(trace, query))
                    .limit(Math.max(1, Math.min(limit, 500))).toList();
        }
        return allDiagnosticTraces(limit);
    }

    private static boolean matchesTrace(RecentTraceStore.TraceRecord trace, String query) {
        return containsIgnoreCase(trace.name(), query) || containsIgnoreCase(trace.method(), query)
                || containsIgnoreCase(trace.traceId(), query) || matchesTreeNode(trace.treeNodes(), query)
                || containsIgnoreCase(trace.errorType(), query) || containsIgnoreCase(trace.errorMessage(), query)
                || containsIgnoreCase(trace.threadName(), query);
    }

    private static boolean matchesTreeNode(List<TraceNode> nodes, String query) {
        if (nodes == null) return false;
        for (var node : nodes) {
            if (containsIgnoreCase(node.name(), query) || containsIgnoreCase(node.tag(), query)
                    || containsIgnoreCase(node.kind(), query) || containsIgnoreCase(node.errorType(), query)
                    || containsIgnoreCase(node.threadName(), query) || matchesTreeNode(node.children(), query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    @GetMapping("/traces/storage")
    public TraceStorageStatus traceStorage() {
        requireAccess();
        DiagnosticTraceStore.StorageStatus diagnosticStorage =
                diagnosticTraceStore == null ? null : diagnosticTraceStore.storageStatus();
        return new TraceStorageStatus(recentTraceStore.size(), recentTraceStore.capacity(),
                diagnosticStorage == null ? 0 : diagnosticStorage.traceCount(),
                diagnosticStorage == null ? 0 : diagnosticStorage.maxTraceCount(),
                diagnosticStorage == null ? 0 : diagnosticStorage.fileBytes(),
                diagnosticStorage == null ? 0 : diagnosticStorage.maxFileBytes());
    }

    @GetMapping("/diagnostics")
    public List<RecentTraceStore.TraceRecord> diagnostics(@RequestParam String kind,
                                                          @RequestParam(defaultValue = "") String source,
                                                          @RequestParam(defaultValue = "100") int limit) {
        requireAccess();
        if (!"slow".equalsIgnoreCase(kind) && !"error".equalsIgnoreCase(kind)
                && !"resource".equalsIgnoreCase(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind must be slow, error or resource");
        }
        String sourceFilter = source.trim();
        return allDiagnosticTraces(500).stream()
                .filter(trace -> switch (kind.toLowerCase(java.util.Locale.ROOT)) {
                    case "slow" -> trace.slow();
                    case "error" -> trace.error();
                    default -> trace.highCpu() || trace.highAllocation();
                })
                .filter(trace -> sourceFilter.isEmpty() || sourceFilter.equalsIgnoreCase(trace.sourceType()))
                .limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    private List<RecentTraceStore.TraceRecord> allDiagnosticTraces(int limit) {
        Map<TraceIdentity, RecentTraceStore.TraceRecord> merged = new LinkedHashMap<>();
        if (diagnosticTraceStore != null) diagnosticTraceStore.findRecent(limit)
                .stream().filter(ObservabilityController::isDiagnosticTrace)
                .forEach(trace -> merged.put(traceIdentity(trace), trace));
        recentTraceStore.recent(limit).stream().filter(ObservabilityController::isDiagnosticTrace)
                .forEach(trace -> merged.putIfAbsent(traceIdentity(trace), trace));
        return merged.values().stream().sorted(Comparator.comparing(RecentTraceStore.TraceRecord::finishedAt).reversed())
                .limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    private static boolean isDiagnosticTrace(RecentTraceStore.TraceRecord trace) {
        return trace.slow() || trace.error() || trace.highCpu() || trace.highAllocation();
    }

    private static TraceIdentity traceIdentity(RecentTraceStore.TraceRecord trace) {
        return new TraceIdentity(trace.traceId(), trace.sourceType(), trace.finishedAt());
    }

    @GetMapping("/traces/{traceId}")
    public RecentTraceStore.TraceRecord trace(@PathVariable String traceId,
                                              @RequestParam(defaultValue = "") String source) {
        requireAccess();
        RecentTraceStore.TraceRecord trace = recentTraceStore.find(traceId, source);
        return trace != null || diagnosticTraceStore == null ? trace
                : diagnosticTraceStore.findByTraceId(traceId, source);
    }

    @PostMapping("/storage/clear")
    public ClearStorageResult clearTraceHistory() throws IOException {
        requireAccess();
        int clearedDiagnosticTraces = diagnosticTraceStore == null ? 0 : diagnosticTraceStore.clearHistory();
        int clearedRecentTraces = recentTraceStore.clear();
        return new ClearStorageResult(clearedDiagnosticTraces, clearedRecentTraces);
    }

    private void requireAccess() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    public record TraceStorageStatus(int recentTraceCount, int recentTraceCapacity, int diagnosticTraceCount,
                                     int diagnosticTraceCapacity, long diagnosticFileBytes,
                                     long diagnosticFileMaxBytes) { }

    public record ClearStorageResult(int clearedDiagnosticTraceCount, int clearedRecentTraceCount) { }

    private record TraceIdentity(String traceId, String sourceType, Instant finishedAt) { }
}
