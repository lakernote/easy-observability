package io.github.lakernote.observability.starter.boot4;

import io.github.lakernote.observability.trace.internal.store.TraceRecord;
import io.github.lakernote.observability.trace.internal.store.TraceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DashboardContextPathE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/tenant-app",
                "spring.mvc.servlet.path=/app",
                "easy.observability.trace.enabled=false",
                "logging.level.io.github.lakernote.observability.metrics=OFF"
        })
class DashboardContextPathE2ETest {
    private static final String APPLICATION_PATH = "/tenant-app/app";
    private static final Pattern PAGE_REFERENCE = Pattern.compile("(?:href|src)=\"([^\"]+)\"");
    private static final Path BLOCKING_FILE = createBlockingFile();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    @DynamicPropertySource
    static void metricsStorage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.storage.directory", () -> BLOCKING_FILE.resolve("storage").toString());
    }

    @AfterAll
    static void deleteBlockingFile() throws IOException {
        Files.deleteIfExists(BLOCKING_FILE);
    }

    @Test
    void servesTheCompleteDashboardUnderContextAndServletPaths() throws Exception {
        URI tracePage = assertCanonicalRedirect(
                "/easy-observability", "/easy-observability/");
        URI metricsPage = assertCanonicalRedirect(
                "/easy-observability/metrics", "/easy-observability/metrics/");

        HttpResponse<String> tracePageResponse = get(tracePage);
        assertThat(tracePageResponse.statusCode()).isEqualTo(200);
        assertThat(tracePageResponse.body()).contains("Trace 诊断");
        Set<URI> traceReferences = assertPageReferencesAvailable(tracePage, tracePageResponse.body());
        assertThat(paths(traceReferences)).containsExactlyInAnyOrder(
                APPLICATION_PATH + "/easy-observability/",
                APPLICATION_PATH + "/easy-observability/stats/",
                APPLICATION_PATH + "/easy-observability/live/",
                APPLICATION_PATH + "/easy-observability/assets/dashboard.css",
                APPLICATION_PATH + "/easy-observability/assets/dashboard.js");

        HttpResponse<String> metricsPageResponse = get(metricsPage);
        assertThat(metricsPageResponse.statusCode()).isEqualTo(200);
        assertThat(metricsPageResponse.body()).contains("指标趋势");
        Set<URI> metricsReferences = assertPageReferencesAvailable(metricsPage, metricsPageResponse.body());
        assertThat(paths(metricsReferences)).containsExactlyInAnyOrder(
                APPLICATION_PATH + "/easy-observability/",
                APPLICATION_PATH + "/easy-observability/stats/",
                APPLICATION_PATH + "/easy-observability/metrics/",
                APPLICATION_PATH + "/easy-observability/live/",
                APPLICATION_PATH + "/easy-observability/assets/dashboard.css",
                APPLICATION_PATH + "/easy-observability/assets/uplot.min.css",
                APPLICATION_PATH + "/easy-observability/assets/metrics.css",
                APPLICATION_PATH + "/easy-observability/assets/uplot.min.js",
                APPLICATION_PATH + "/easy-observability/assets/metrics.js");

        URI livePage = assertCanonicalRedirect("/easy-observability/live", "/easy-observability/live/");
        HttpResponse<String> livePageResponse = get(livePage);
        assertThat(livePageResponse.statusCode()).isEqualTo(200);
        Set<URI> liveReferences = assertPageReferencesAvailable(livePage, livePageResponse.body());
        assertThat(paths(liveReferences)).contains(
                APPLICATION_PATH + "/easy-observability/assets/live.css",
                APPLICATION_PATH + "/easy-observability/assets/live.js");
        HttpResponse<String> liveRequests = get(URI.create(livePage.toString()).resolve("../../api/easy-observability/live/requests"));
        assertThat(liveRequests.statusCode()).isEqualTo(200);
        assertThat(liveRequests.body()).contains("\"items\":");

        URI dashboardModule = referenceEndingWith(traceReferences, "/assets/dashboard.js");
        URI metricsModule = referenceEndingWith(metricsReferences, "/assets/metrics.js");
        HttpResponse<String> dashboardScript = get(dashboardModule);
        HttpResponse<String> metricsScript = get(metricsModule);
        assertThat(dashboardScript.body()).contains("from './common.js");
        assertThat(metricsScript.body()).contains("from './metrics-common.js");
        assertThat(get(metricsModule.resolve("metrics-common.js")).body()).contains("from './common.js");
        assertThat(get(dashboardModule.resolve("common.js")).body())
                .contains("new URL('../../api/easy-observability/', import.meta.url)");

        URI traceApi = dashboardModule.resolve("../../api/easy-observability/");
        URI metricsApi = metricsModule.resolve("../../api/easy-observability/");
        assertThat(traceApi).isEqualTo(uri("/api/easy-observability/"));
        assertThat(metricsApi).isEqualTo(traceApi);

        traceStore.clear();
        traceStore.add(TraceRecord.builder()
                .traceId("context-path-trace").sourceType("HTTP").name("GET /context-path").tag("GET")
                .status(503).durationMs(12).error(true)
                .threadName("test-thread").threadId(Thread.currentThread().getId())
                .errorType(IllegalStateException.class.getName()).errorMessage("context failure")
                .build());

        HttpResponse<String> traces = get(traceApi.resolve("traces?limit=50"));
        assertThat(traces.statusCode()).isEqualTo(200);
        assertThat(traces.body()).contains("context-path-trace", "GET /context-path");

        HttpResponse<String> traceDetail = get(traceApi.resolve("traces/context-path-trace?source=HTTP"));
        assertThat(traceDetail.statusCode()).isEqualTo(200);
        assertThat(traceDetail.body()).contains("context-path-trace", "context failure");

        HttpResponse<String> resources = get(traceApi.resolve("metrics/resources?period=1h"));
        assertThat(resources.statusCode()).isEqualTo(200);
        assertThat(resources.body()).contains("\"period\":\"HOUR\"", "\"persistent\":false");

        URI statsPage = assertCanonicalRedirect("/easy-observability/stats", "/easy-observability/stats/");
        HttpResponse<String> statsPageResponse = get(statsPage);
        assertThat(statsPageResponse.body()).contains("调用排行");
        assertThat(paths(assertPageReferencesAvailable(statsPage, statsPageResponse.body()))).contains(
                APPLICATION_PATH + "/easy-observability/assets/stats.css",
                APPLICATION_PATH + "/easy-observability/assets/stats.js");
        // Trace is disabled here, so calls are not collected; the API says so instead of failing.
        HttpResponse<String> operations = get(traceApi.resolve("metrics/operations"));
        assertThat(operations.statusCode()).isEqualTo(200);
        assertThat(operations.body()).contains("\"collecting\":false");

        HttpResponse<String> metricsStorage = get(traceApi.resolve("metrics/storage"));
        assertThat(metricsStorage.statusCode()).isEqualTo(200);
        assertThat(metricsStorage.body()).contains("\"persistent\":false");
    }

    private URI assertCanonicalRedirect(String entryPath, String canonicalPath) throws Exception {
        URI entry = uri(entryPath);
        HttpResponse<String> response = get(entry);

        assertThat(response.statusCode()).isEqualTo(308);
        URI redirected = entry.resolve(response.headers().firstValue("Location").orElseThrow());
        assertThat(redirected).isEqualTo(uri(canonicalPath));
        return redirected;
    }

    private Set<URI> assertPageReferencesAvailable(URI page, String html) throws Exception {
        Set<URI> references = new LinkedHashSet<>();
        Matcher matcher = PAGE_REFERENCE.matcher(html);
        while (matcher.find()) {
            String reference = matcher.group(1);
            if (reference.startsWith("#") || reference.startsWith("data:")) continue;
            URI resolved = page.resolve(reference);
            assertThat(resolved.getScheme()).isEqualTo(page.getScheme());
            assertThat(resolved.getAuthority()).isEqualTo(page.getAuthority());
            assertThat(resolved.getPath()).startsWith(APPLICATION_PATH + "/easy-observability");
            references.add(resolved);
        }

        assertThat(references).isNotEmpty();
        for (URI reference : references) {
            HttpResponse<String> response = get(reference);
            assertThat(response.statusCode()).as("GET %s", reference).isEqualTo(200);
            assertThat(response.body()).as("body of %s", reference).isNotEmpty();
        }
        return references;
    }

    private static Set<String> paths(Set<URI> references) {
        return references.stream().map(URI::getPath).collect(Collectors.toSet());
    }

    private static URI referenceEndingWith(Set<URI> references, String suffix) {
        return references.stream()
                .filter(reference -> reference.getPath().endsWith(suffix))
                .findFirst()
                .orElseThrow();
    }

    private HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String applicationPath) {
        return URI.create("http://127.0.0.1:" + port + APPLICATION_PATH + applicationPath);
    }

    private static Path createBlockingFile() {
        try {
            Path file = Files.createTempFile("easy-observability-context-path-storage-parent", ".tmp");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
