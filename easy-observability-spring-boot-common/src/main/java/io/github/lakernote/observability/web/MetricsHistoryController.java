package io.github.lakernote.observability.web;

import io.github.lakernote.observability.metrics.Rrd4jMetricsStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/** Read-only public metrics history API. */
@RestController
@RequestMapping("/api/easy-observability/metrics")
@ConditionalOnBean(Rrd4jMetricsStore.class)
public class MetricsHistoryController {
    private static final java.util.Set<String> PERIODS = java.util.Set.of("1h", "24h", "7d", "30d");

    private final Rrd4jMetricsStore store;
    private final ObservabilityAccessPolicy accessPolicy;

    public MetricsHistoryController(Rrd4jMetricsStore store, ObservabilityAccessPolicy accessPolicy) {
        this.store = store;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping
    public Rrd4jMetricsStore.History history(@RequestParam(defaultValue = "24h") String period) throws IOException {
        requireAccess();
        if (!PERIODS.contains(period)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be 1h, 24h, 7d or 30d");
        }
        return store.fetch(period);
    }

    @GetMapping("/storage")
    public Rrd4jMetricsStore.StorageStatus storage() throws IOException {
        requireAccess();
        return store.status();
    }

    private void requireAccess() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
