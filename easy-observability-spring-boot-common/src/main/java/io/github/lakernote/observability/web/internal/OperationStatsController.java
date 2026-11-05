package io.github.lakernote.observability.web.internal;

import io.github.lakernote.observability.stats.internal.OperationStats;
import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/**
 * Serves the operation statistics page and its API. The page is always available so navigation links never break;
 * when statistics are disabled the API answers 404 and the page explains how to enable them.
 */
@RestController
public class OperationStatsController {
    private static final Resource PAGE = new ClassPathResource("META-INF/resources/easy-observability/stats.html");
    private final OperationStats operationStats;
    private final ObservabilityAccessPolicy accessPolicy;

    @Autowired
    public OperationStatsController(ObjectProvider<OperationStats> operationStats,
                                    ObservabilityAccessPolicy accessPolicy) {
        this(operationStats.getIfAvailable(), accessPolicy);
    }

    OperationStatsController(OperationStats operationStats, ObservabilityAccessPolicy accessPolicy) {
        this.operationStats = operationStats;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/easy-observability/stats")
    public ResponseEntity<Void> redirectToStats() {
        // Keep the redirect relative so servlet context paths and reverse-proxy prefixes are preserved.
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).location(URI.create("stats/")).build();
    }

    @GetMapping("/easy-observability/stats/")
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(PAGE);
    }

    @GetMapping("/api/easy-observability/stats")
    public OperationStats.Snapshot stats(@RequestParam(defaultValue = "hour") String window) {
        requireAccess();
        requireEnabled();
        try {
            return operationStats.snapshot(OperationStats.Window.parse(window));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window must be 1h, today, 7d or 30d");
        }
    }

    @PostMapping("/api/easy-observability/stats/reset")
    public ResponseEntity<Void> reset() {
        requireAccess();
        if (!accessPolicy.canClear()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        requireEnabled();
        operationStats.clear();
        return ResponseEntity.noContent().build();
    }

    private void requireEnabled() {
        if (operationStats == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operation statistics are disabled");
        }
    }

    private void requireAccess() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
