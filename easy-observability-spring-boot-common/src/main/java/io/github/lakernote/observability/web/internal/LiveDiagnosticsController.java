package io.github.lakernote.observability.web.internal;

import io.github.lakernote.observability.diagnostics.internal.LiveDiagnostics;
import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Serves the live diagnostics page and its on-demand API: running requests, threads and thread dumps. */
@RestController
public class LiveDiagnosticsController {
    private static final Resource LIVE_PAGE = new ClassPathResource("META-INF/resources/easy-observability/live.html");
    private static final String API = "/api/easy-observability/live";
    private static final DateTimeFormatter DUMP_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final LiveDiagnostics diagnostics;
    private final ObservabilityAccessPolicy accessPolicy;

    @Autowired
    public LiveDiagnosticsController(ObservabilityAccessPolicy accessPolicy) {
        this(new LiveDiagnostics(), accessPolicy);
    }

    LiveDiagnosticsController(LiveDiagnostics diagnostics, ObservabilityAccessPolicy accessPolicy) {
        this.diagnostics = diagnostics;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/easy-observability/live")
    public ResponseEntity<Void> redirectToLive() {
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).location(URI.create("live/")).build();
    }

    @GetMapping("/easy-observability/live/")
    public ResponseEntity<Resource> livePage() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(LIVE_PAGE);
    }

    @GetMapping(API + "/requests")
    public LiveDiagnostics.ActiveRequests requests() {
        requireAccess();
        return diagnostics.activeRequests();
    }

    /** Samples thread CPU for {@code sampleMs} (0–5000); answers 429 while another sample is in progress. */
    @GetMapping(API + "/threads")
    public LiveDiagnostics.ThreadReport threads(@RequestParam(defaultValue = "1000") long sampleMs)
            throws InterruptedException {
        requireAccess();
        if (sampleMs < 0 || sampleMs > LiveDiagnostics.MAX_SAMPLE.toMillis()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sampleMs must be between 0 and 5000");
        }
        LiveDiagnostics.ThreadReport report = diagnostics.threads(Duration.ofMillis(sampleMs));
        if (report == null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        return report;
    }

    @GetMapping(API + "/threads/dump")
    public ResponseEntity<String> threadDump() {
        requireAccess();
        String fileName = "threads-" + DUMP_NAME.format(LocalDateTime.now()) + ".txt";
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(diagnostics.threadDump());
    }

    private void requireAccess() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
