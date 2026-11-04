package io.github.lakernote.observability.web.internal;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Serves the dashboard entry page from this starter's JAR. */
@RestController
public class ObservabilityDashboardController {
    private static final Resource INDEX = new ClassPathResource("META-INF/resources/easy-observability/index.html");

    @GetMapping("/easy-observability")
    public ResponseEntity<Void> redirectToIndex() {
        // A trailing slash gives the browser a stable base for context-relative assets and API URLs.
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT)
                .location(URI.create("easy-observability/"))
                .build();
    }

    @GetMapping("/easy-observability/")
    public ResponseEntity<Resource> index() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(INDEX);
    }
}
