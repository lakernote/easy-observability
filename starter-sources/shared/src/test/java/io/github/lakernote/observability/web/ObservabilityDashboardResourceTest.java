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
                .contains("Easy Observability", "/easy-observability/assets/dashboard.js");
        assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                .contains("easy-observability:auth-request", "easy-observability:auth-response",
                        "initializeEmbeddedAuth().then(refresh)");
        page.close();
        script.close();
        style.close();
    }
}
