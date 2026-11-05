package io.github.lakernote.observability.starter.boot3;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String APPLICATION_BASE = "/tenant-app/app";
    private static final Path BLOCKING_FILE = createBlockingFile();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Pattern SCRIPT_API_BASE = Pattern.compile(
            "new URL\\('([^']+)'\\s*,\\s*import\\.meta\\.url\\)");

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void metricsStorage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.storage.directory", () -> BLOCKING_FILE.resolve("storage").toString());
    }

    @AfterAll
    static void deleteBlockingFile() throws IOException {
        Files.deleteIfExists(BLOCKING_FILE);
    }

    @Test
    void servesDashboardResourcesAndApisBehindContextAndServletPaths() throws Exception {
        assertThat(get(APPLICATION_BASE + "/probe").statusCode()).isEqualTo(200);
        assertThat(get("/probe").statusCode()).isEqualTo(404);
        assertThat(get("/tenant-app/probe").statusCode()).isEqualTo(404);

        URI tracePageUri = uri(APPLICATION_BASE + "/easy-observability/");
        URI metricsPageUri = uri(APPLICATION_BASE + "/easy-observability/metrics/");
        assertCanonicalRedirect(APPLICATION_BASE + "/easy-observability", tracePageUri);
        assertCanonicalRedirect(APPLICATION_BASE + "/easy-observability/metrics", metricsPageUri);

        HttpResponse<String> tracePage = get(tracePageUri);
        assertThat(tracePage.statusCode()).isEqualTo(200);
        assertThat(tracePage.body()).contains("Trace 诊断", "href=\"stats/\"");
        HttpResponse<String> statsPage = get(tracePageUri.resolve("stats/"));
        assertThat(statsPage.statusCode()).isEqualTo(200);
        assertThat(statsPage.body()).contains("href=\"../metrics/\"");
        assertThat(tracePageUri.resolve("stats/").resolve("../metrics/")).isEqualTo(metricsPageUri);

        URI dashboardCss = referencedUri(tracePageUri, tracePage.body(), "href", "dashboard.css");
        URI dashboardScript = referencedUri(tracePageUri, tracePage.body(), "src", "dashboard.js");
        assertThat(dashboardCss.getPath()).isEqualTo(APPLICATION_BASE
                + "/easy-observability/assets/dashboard.css");
        assertThat(dashboardScript.getPath()).isEqualTo(APPLICATION_BASE
                + "/easy-observability/assets/dashboard.js");
        assertThat(get(dashboardCss).statusCode()).isEqualTo(200);
        HttpResponse<String> dashboardScriptResponse = get(dashboardScript);
        assertThat(dashboardScriptResponse.statusCode()).isEqualTo(200);

        HttpResponse<String> metricsPage = get(metricsPageUri);
        assertThat(metricsPage.statusCode()).isEqualTo(200);
        assertThat(metricsPage.body()).contains("指标趋势", "href=\"../\"");
        assertThat(metricsPageUri.resolve("../")).isEqualTo(tracePageUri);

        URI metricsDashboardCss = referencedUri(metricsPageUri, metricsPage.body(), "href", "dashboard.css");
        URI uplotCss = referencedUri(metricsPageUri, metricsPage.body(), "href", "uplot.min.css");
        URI metricsCss = referencedUri(metricsPageUri, metricsPage.body(), "href", "metrics.css");
        URI uplotScript = referencedUri(metricsPageUri, metricsPage.body(), "src", "uplot.min.js");
        URI metricsScript = referencedUri(metricsPageUri, metricsPage.body(), "src", "metrics.js");
        for (URI asset : List.of(metricsDashboardCss, uplotCss, metricsCss, uplotScript, metricsScript)) {
            assertThat(asset.getPath()).startsWith(APPLICATION_BASE + "/easy-observability/assets/");
            assertThat(get(asset).statusCode()).isEqualTo(200);
        }

        URI traceApi = resolveApiBase(dashboardScript, dashboardScriptResponse.body());
        assertThat(traceApi.getPath()).isEqualTo(APPLICATION_BASE + "/api/easy-observability/");
        HttpResponse<String> traces = get(traceApi.resolve("traces?limit=1"));
        assertThat(traces.statusCode()).isEqualTo(200);
        assertThat(traces.body()).isEqualTo("[]");

        URI metricsCommon = metricsScript.resolve("metrics-common.js");
        HttpResponse<String> metricsCommonResponse = get(metricsCommon);
        assertThat(metricsCommonResponse.statusCode()).isEqualTo(200);
        URI metricsApi = resolveApiBase(metricsCommon, metricsCommonResponse.body());
        assertThat(metricsApi.getPath()).isEqualTo(APPLICATION_BASE + "/api/easy-observability/");
        HttpResponse<String> resources = get(metricsApi.resolve("metrics/resources?period=1h"));
        assertThat(resources.statusCode()).isEqualTo(200);
        assertThat(resources.body()).contains("\"period\":\"HOUR\"", "\"persistent\":false");
        HttpResponse<String> storage = get(metricsApi.resolve("metrics/storage"));
        assertThat(storage.statusCode()).isEqualTo(200);
        assertThat(storage.body()).contains("\"persistent\":false");

        assertThat(get("/api/easy-observability/traces").statusCode()).isEqualTo(404);
        assertThat(get("/tenant-app/api/easy-observability/traces").statusCode()).isEqualTo(404);
    }

    private void assertCanonicalRedirect(String requestPath, URI expected) throws Exception {
        URI requestUri = uri(requestPath);
        HttpResponse<String> response = get(requestUri);
        assertThat(response.statusCode()).isEqualTo(308);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(requestUri.resolve(location)).isEqualTo(expected);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(uri(path));
    }

    private HttpResponse<String> get(URI target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static URI referencedUri(URI pageUri, String html, String attribute, String marker) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(attribute) + "=\"([^\"]+)\"").matcher(html);
        while (matcher.find()) {
            if (matcher.group(1).contains(marker)) return pageUri.resolve(matcher.group(1));
        }
        throw new AssertionError("Missing " + attribute + " containing " + marker);
    }

    private static URI resolveApiBase(URI scriptUri, String script) {
        Matcher matcher = SCRIPT_API_BASE.matcher(script);
        if (!matcher.find()) throw new AssertionError("Missing context-relative API base");
        return scriptUri.resolve(matcher.group(1));
    }

    private static Path createBlockingFile() {
        try {
            Path file = Files.createTempFile("easy-observability-context-path-storage", ".tmp");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ProbeController.class)
    static class TestApplication { }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String probe() {
            return "ok";
        }
    }
}
