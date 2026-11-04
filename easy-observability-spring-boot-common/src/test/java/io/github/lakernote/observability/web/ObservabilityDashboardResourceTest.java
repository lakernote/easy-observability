package io.github.lakernote.observability.web;

import org.junit.jupiter.api.Test;
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
                .contains("Easy Observability", "src=\"assets/dashboard.js", "href=\"metrics/\"",
                        "Trace 诊断", "正常请求不会显示在此列表中", "内存分配量", "完成时间", "入口线程资源",
                        "表格可横向滚动查看全部字段")
                .doesNotContain("href=\"/easy-observability", "src=\"/easy-observability");
        assertThat(metricsPageContent)
                .contains("src=\"../assets/metrics.js", "href=\"../\"", "href=\"./\"")
                .doesNotContain("href=\"/easy-observability", "src=\"/easy-observability");
        assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                .contains("new URL('../../api/easy-observability/', import.meta.url)",
                        "new URL(path.replace(/^\\/+/, ''), api)",
                        "easy-observability:auth-request", "easy-observability:auth-response",
                        "initializeEmbeddedAuth().then(refresh)", "if (!trace.slow && !trace.error && !resourceAlert) return false",
                        "result === 'slow' && (!trace.slow || trace.error)",
                        "formatAllocatedBytes(trace.allocatedBytes)", "formatAllocatedBytes(detail.allocatedBytes)",
                        "内存分配量", "不是当前堆占用", "条诊断 Trace",
                        "addFact($('facts'), '状态'")
                .doesNotContain("const api = '/api/");
        assertThat(new String(metricsScript.readAllBytes(), StandardCharsets.UTF_8))
                .contains("new URL('../../api/easy-observability/', import.meta.url)",
                        "new URL(`metrics?period=${encodeURIComponent($('period').value)}`, api)",
                        "new URL('metrics/storage', api)",
                        "if (!storage.initialized)", "本地指标历史存储不可用", "业务应用未受影响",
                        "$('instance').classList.add('degraded')")
                .doesNotContain("const api = '/api/");
        page.close();
        metricsPage.close();
        script.close();
        metricsScript.close();
        style.close();
    }

    @Test
    void redirectsEntryPagesToCanonicalTrailingSlashUrls() {
        var traceRedirect = new ObservabilityDashboardController().redirectToIndex();
        var metricsRedirect = new MetricsDashboardController().redirectToMetrics();

        assertThat(traceRedirect.getStatusCode()).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        assertThat(traceRedirect.getHeaders().getLocation()).hasToString("easy-observability/");
        assertThat(metricsRedirect.getStatusCode()).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        assertThat(metricsRedirect.getHeaders().getLocation()).hasToString("metrics/");
    }
}
