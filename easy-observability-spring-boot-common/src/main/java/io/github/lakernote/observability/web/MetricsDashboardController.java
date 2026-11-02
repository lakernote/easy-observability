package io.github.lakernote.observability.web;

import io.github.lakernote.observability.metrics.Rrd4jMetricsStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the public metrics trend page only when local metrics history is enabled. */
@RestController
@ConditionalOnBean(Rrd4jMetricsStore.class)
public class MetricsDashboardController {
    private static final Resource PAGE = new ClassPathResource("META-INF/resources/easy-observability/metrics.html");

    @GetMapping({"/easy-observability/metrics", "/easy-observability/metrics/"})
    public ResponseEntity<Resource> metrics() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(PAGE);
    }
}
