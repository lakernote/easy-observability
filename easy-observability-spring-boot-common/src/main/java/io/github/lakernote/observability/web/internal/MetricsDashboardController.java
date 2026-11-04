package io.github.lakernote.observability.web.internal;

import io.github.lakernote.observability.metrics.internal.Rrd4jMetricsStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Serves the public metrics trend page only when local metrics history is enabled. */
@RestController
@ConditionalOnBean(Rrd4jMetricsStore.class)
public class MetricsDashboardController {
    private static final Resource PAGE = new ClassPathResource("META-INF/resources/easy-observability/metrics.html");

    @GetMapping("/easy-observability/metrics")
    public ResponseEntity<Void> redirectToMetrics() {
        // Keep the redirect relative so servlet context paths and reverse-proxy prefixes are preserved.
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT)
                .location(URI.create("metrics/"))
                .build();
    }

    @GetMapping("/easy-observability/metrics/")
    public ResponseEntity<Resource> metrics() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(PAGE);
    }
}
