package io.github.lakernote.observability.web.internal;

import io.github.lakernote.observability.trace.internal.store.TraceRecord;
import io.github.lakernote.observability.trace.internal.store.TraceStore;
import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Trace API used by the bundled Trace page. */
@RestController
@RequestMapping("/api/easy-observability")
public class ObservabilityController {
    private final TraceStore traceStore;
    private final ObservabilityAccessPolicy accessPolicy;

    public ObservabilityController(TraceStore traceStore, ObservabilityAccessPolicy accessPolicy) {
        this.traceStore = traceStore;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Lists stored traces newest first. {@code q} also matches span names and SQL, which are read from disk; such a
     * search stops after a bounded amount of data and returns a cursor to continue.
     */
    @GetMapping("/traces")
    public TraceStore.TracePage traces(@RequestParam(defaultValue = "all") String kind,
                                       @RequestParam(defaultValue = "") String source,
                                       @RequestParam(defaultValue = "") String q,
                                       @RequestParam(defaultValue = "") String before,
                                       @RequestParam(defaultValue = "50") int limit) {
        requireAccess();
        try {
            return traceStore.search(new TraceStore.TraceQuery(kind, source, q, before, limit));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @GetMapping("/traces/storage")
    public TraceStore.StorageStatus traceStorage() {
        requireAccess();
        return traceStore.storageStatus();
    }

    @GetMapping("/traces/{traceId}")
    public TraceRecord trace(@PathVariable String traceId, @RequestParam(defaultValue = "") String source) {
        requireAccess();
        TraceRecord trace = traceStore.find(traceId, source);
        if (trace == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return trace;
    }

    /** Tells the dashboard which actions the current request may perform. */
    @GetMapping("/permissions")
    public Permissions permissions() {
        requireAccess();
        return new Permissions(accessPolicy.canClear());
    }

    @PostMapping("/traces/clear")
    public ClearTracesResult clearTraces() {
        requireAccess();
        if (!accessPolicy.canClear()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return new ClearTracesResult(traceStore.clear());
    }

    private void requireAccess() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    public record Permissions(boolean canClear) { }

    public record ClearTracesResult(int clearedTraceCount) { }
}
