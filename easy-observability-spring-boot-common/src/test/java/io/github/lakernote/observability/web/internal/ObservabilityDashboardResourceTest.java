package io.github.lakernote.observability.web.internal;

import org.junit.jupiter.api.Test;
import io.github.lakernote.observability.metrics.internal.OperationStats;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityDashboardResourceTest {
    @Test
    void bundlesTheDashboardEntryAndItsLocalAssets() throws IOException {
        var classLoader = getClass().getClassLoader();
        var page = classLoader.getResourceAsStream("META-INF/resources/easy-observability/index.html");
        var metricsPage = classLoader.getResourceAsStream("META-INF/resources/easy-observability/metrics.html");
        var script = classLoader.getResourceAsStream("META-INF/resources/easy-observability/assets/dashboard.js");
        var metricsScript = classLoader.getResourceAsStream("META-INF/resources/easy-observability/assets/metrics.js");
        var style = classLoader.getResourceAsStream("META-INF/resources/easy-observability/assets/dashboard.css");

        assertThat(page).isNotNull();
        assertThat(metricsPage).isNotNull();
        assertThat(script).isNotNull();
        assertThat(metricsScript).isNotNull();
        assertThat(style).isNotNull();
        String pageContent = new String(page.readAllBytes(), StandardCharsets.UTF_8);
        String metricsPageContent = new String(metricsPage.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(pageContent)
                .contains("Easy Observability", "src=\"assets/dashboard.js", "href=\"stats/\">运行指标",
                        "Trace 诊断", "正常请求不会显示在此列表中", "内存分配量", "完成时间", "入口线程资源",
                        "表格可横向滚动查看全部字段",
                        "<button id=\"clear-history\" class=\"quiet-button\" type=\"button\" hidden>")
                .doesNotContain("href=\"/easy-observability", "src=\"/easy-observability");
        assertThat(metricsPageContent)
                .contains("src=\"../assets/metrics.js", "href=\"../\"", "href=\"./\">指标趋势",
                        "href=\"../stats/\">调用排行")
                .doesNotContain("href=\"/easy-observability", "src=\"/easy-observability");
        assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                .contains("new URL('../../api/easy-observability/', import.meta.url)",
                        "new URL(path.replace(/^\\/+/, ''), api)",
                        "easy-observability:auth-request", "easy-observability:auth-response",
                        "initializeEmbeddedAuth().then(() => Promise.all([loadPermissions(), refresh()]))",
                        "$('clear-history').hidden = !permissions.canClear",
                        "kind: $('result-filter').value", "params.set('before', pageCursors[page])",
                        "traces.scanLimited ? '继续搜索' : '下一页'", "notFoundMessage: '这条 Trace 已不在保留范围内'",
                        "formatAllocatedBytes(trace.allocatedBytes)", "formatAllocatedBytes(detail.allocatedBytes)",
                        "内存分配量", "不是当前堆占用", "条诊断 Trace",
                        "addFact($('facts'), '状态'")
                .doesNotContain("const api = '/api/");
        assertThat(new String(metricsScript.readAllBytes(), StandardCharsets.UTF_8))
                .contains("request(`metrics/operations/trends?period=${period}`)",
                        "request(`metrics/resources?period=${period}`)", "request('metrics/storage')",
                        "历史存储不可用", "$('instance').classList.toggle('degraded', !storage.persistent)")
                .doesNotContain("innerHTML", "const api = '/api/");
        page.close();
        metricsPage.close();
        script.close();
        metricsScript.close();
        style.close();
    }

    @Test
    void bundlesTheStatisticsPageWithRelativeLinks() throws IOException {
        var classLoader = getClass().getClassLoader();
        try (var page = classLoader.getResourceAsStream("META-INF/resources/easy-observability/stats.html");
             var script = classLoader.getResourceAsStream("META-INF/resources/easy-observability/assets/stats.js");
             var style = classLoader.getResourceAsStream("META-INF/resources/easy-observability/assets/stats.css");
             var index = classLoader.getResourceAsStream("META-INF/resources/easy-observability/index.html");
             var metrics = classLoader.getResourceAsStream("META-INF/resources/easy-observability/metrics.html");
             var common = classLoader.getResourceAsStream(
                     "META-INF/resources/easy-observability/assets/metrics-common.js");
             var dashboard = classLoader.getResourceAsStream(
                     "META-INF/resources/easy-observability/assets/dashboard.js")) {
            assertThat(style).isNotNull();
            assertThat(new String(page.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("src=\"../assets/stats.js", "src=\"../assets/uplot.min.js", "href=\"../\"",
                            "href=\"../metrics/\">指标趋势", "<option value=\"30d\">最近 30 天</option>",
                            "<button id=\"reset-stats\" class=\"quiet-button\" type=\"button\" hidden>")
                    .doesNotContain("href=\"/easy-observability", "src=\"/easy-observability");
            assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("request(`metrics/operations?window=${encodeURIComponent($('window').value)}`)",
                            "request(`metrics/operations/trend?category=", "LONG_RANGE_REFRESH_MS",
                            "url.searchParams.set('window', $('window').value)",
                            "request('/metrics/operations/reset', { method: 'POST' }",
                            "$('reset-stats').hidden = !permissions.canClear", "next.collecting")
                    .doesNotContain("innerHTML", "const api = '/api/", "traceQuery");
            assertThat(new String(common.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("new URL('../../api/easy-observability/', import.meta.url)",
                            "new URL(path.replace(/^\\/+/, ''), api)", "export function lineChart(")
                    .doesNotContain("innerHTML");
            assertThat(new String(index.readAllBytes(), StandardCharsets.UTF_8)).contains("href=\"stats/\"");
            assertThat(new String(metrics.readAllBytes(), StandardCharsets.UTF_8)).contains("href=\"../stats/\"");
            assertThat(new String(dashboard.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("new URLSearchParams(window.location.search)", "applyLinkedFilters()\nbind()");
        }
    }

    @Test
    void redirectsEntryPagesToCanonicalTrailingSlashUrls() {
        var traceRedirect = new ObservabilityDashboardController().redirectToIndex();
        var metricsRedirect = new MetricsController((OperationStats) null, null, null, () -> true).redirectToMetrics();

        assertThat(traceRedirect.getStatusCode()).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        assertThat(traceRedirect.getHeaders().getLocation()).hasToString("easy-observability/");
        assertThat(metricsRedirect.getStatusCode()).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        assertThat(metricsRedirect.getHeaders().getLocation()).hasToString("metrics/");
    }

    @Test
    void keepsThreadIdentityVisibleForSingleThreadTimelines() throws IOException {
        try (var script = getClass().getClassLoader()
                .getResourceAsStream("META-INF/resources/easy-observability/assets/dashboard.js")) {
            assertThat(script).isNotNull();
            assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("node('span', 'timeline-thread-heading', '线程')",
                            "node('span', `timeline-thread${threadClass}`, thread.key)",
                            "const role = thread.info.isEntry ? '入口线程' : '子线程'",
                            "container.insertBefore(legend, scale)");
        }
    }
}
