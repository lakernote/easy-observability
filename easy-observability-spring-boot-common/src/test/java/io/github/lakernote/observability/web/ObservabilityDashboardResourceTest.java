package io.github.lakernote.observability.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityDashboardResourceTest {
    @Test
    void bundlesTheDashboardEntryAndItsLocalAssets() throws IOException {
        var classLoader = getClass().getClassLoader();
        var page = classLoader.getResourceAsStream("META-INF/resources/easy-observability/index.html");
        var script = classLoader.getResourceAsStream("META-INF/resources/easy-observability/assets/dashboard.js");
        var style = classLoader.getResourceAsStream("META-INF/resources/easy-observability/assets/dashboard.css");

        assertThat(page).isNotNull();
        assertThat(script).isNotNull();
        assertThat(style).isNotNull();
        assertThat(new String(page.readAllBytes(), StandardCharsets.UTF_8))
                .contains("Easy Observability", "/easy-observability/assets/dashboard.js",
                        "正常请求不会进入列表", "分配内存", "状态", "完成时间",
                        "不是当前堆占用", "表格可横向滚动查看全部字段");
        assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                .contains("easy-observability:auth-request", "easy-observability:auth-response",
                        "initializeEmbeddedAuth().then(refresh)", "if (!trace.slow && !trace.error) return false",
                        "result === 'slow' && (!trace.slow || trace.error)",
                        "formatAllocatedBytes(trace.allocatedBytes)", "条慢/错 Trace",
                        "addFact($('facts'), '状态'", "不是当前堆占用");
        page.close();
        script.close();
        style.close();
    }
}
