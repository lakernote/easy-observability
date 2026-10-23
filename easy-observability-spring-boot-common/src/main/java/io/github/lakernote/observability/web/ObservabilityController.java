package io.github.lakernote.observability.web;

import io.github.lakernote.observability.trace.store.SlowErrorTraceStore;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
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
    private final SlowErrorTraceStore slowErrorTraceStore;
    private final ObservabilityAccessPolicy accessPolicy;

    public ObservabilityController(RecentTraceStore recentTraceStore, SlowErrorTraceStore slowErrorTraceStore,
                                   ObservabilityAccessPolicy accessPolicy) {
        this.recentTraceStore = recentTraceStore;
        this.slowErrorTraceStore = slowErrorTraceStore;
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
            return allSlowErrorTraces(500).stream()
                    .filter(trace -> source.isBlank() || source.equalsIgnoreCase(trace.sourceType()))
                    .filter(trace -> query.isEmpty() || matchesTrace(trace, query))
                    .limit(Math.max(1, Math.min(limit, 500))).toList();
        }
        return allSlowErrorTraces(limit);
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

    @GetMapping("/traces/storage")
    public TraceStorageStatus traceStorage() {
        requireAccess();
        SlowErrorTraceStore.StorageStatus slowErrorStorage =
                slowErrorTraceStore == null ? null : slowErrorTraceStore.storageStatus();
        return new TraceStorageStatus(recentTraceStore.size(), recentTraceStore.capacity(),
                slowErrorStorage == null ? 0 : slowErrorStorage.traceCount(),
                slowErrorStorage == null ? 0 : slowErrorStorage.maxTraceCount(),
                slowErrorStorage == null ? 0 : slowErrorStorage.fileBytes(),
                slowErrorStorage == null ? 0 : slowErrorStorage.maxFileBytes());
    }

    @GetMapping("/slow-errors")
    public List<RecentTraceStore.TraceRecord> slowErrors(@RequestParam String kind,
                                                         @RequestParam(defaultValue = "") String source,
                                                         @RequestParam(defaultValue = "100") int limit) {
        requireAccess();
        if (!"slow".equalsIgnoreCase(kind) && !"error".equalsIgnoreCase(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind must be slow or error");
        }
        String sourceFilter = source.trim();
        return allSlowErrorTraces(500).stream()
                .filter(trace -> "slow".equalsIgnoreCase(kind) ? trace.slow() : trace.error())
                .filter(trace -> sourceFilter.isEmpty() || sourceFilter.equalsIgnoreCase(trace.sourceType()))
                .limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    private List<RecentTraceStore.TraceRecord> allSlowErrorTraces(int limit) {
        Map<TraceIdentity, RecentTraceStore.TraceRecord> merged = new LinkedHashMap<>();
        if (slowErrorTraceStore != null) slowErrorTraceStore.findRecent(limit)
                .stream().filter(ObservabilityController::isSlowOrErrorTrace)
                .forEach(trace -> merged.put(traceIdentity(trace), trace));
        recentTraceStore.recent(limit).stream().filter(ObservabilityController::isSlowOrErrorTrace)
                .forEach(trace -> merged.putIfAbsent(traceIdentity(trace), trace));
        return merged.values().stream().sorted(Comparator.comparing(RecentTraceStore.TraceRecord::finishedAt).reversed())
                .limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    private static boolean isSlowOrErrorTrace(RecentTraceStore.TraceRecord trace) {
        return trace.slow() || trace.error();
    }

    private static TraceIdentity traceIdentity(RecentTraceStore.TraceRecord trace) {
        return new TraceIdentity(trace.traceId(), trace.sourceType(), trace.finishedAt());
    }

    @GetMapping("/traces/{traceId}")
    public RecentTraceStore.TraceRecord trace(@PathVariable String traceId,
                                              @RequestParam(defaultValue = "") String source) {
        requireAccess();
        RecentTraceStore.TraceRecord trace = recentTraceStore.find(traceId, source);
        return trace != null || slowErrorTraceStore == null ? trace
                : slowErrorTraceStore.findByTraceId(traceId, source);
    }

    @PostMapping("/storage/clear")
    public ClearStorageResult clearTraceHistory() throws IOException {
        requireAccess();
        int clearedSlowErrorTraces = slowErrorTraceStore == null ? 0 : slowErrorTraceStore.clearHistory();
        int clearedRecentTraces = recentTraceStore.clear();
        return new ClearStorageResult(clearedSlowErrorTraces, clearedRecentTraces);
    }

    private void requireAccess() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    public record TraceStorageStatus(int recentTraceCount, int recentTraceCapacity, int slowErrorTraceCount,
                                     int slowErrorTraceCapacity, long slowErrorFileBytes,
                                     long slowErrorFileMaxBytes) { }

    public record ClearStorageResult(int clearedSlowErrorTraceCount, int clearedRecentTraceCount) { }

    private record TraceIdentity(String traceId, String sourceType, Instant finishedAt) { }
}
