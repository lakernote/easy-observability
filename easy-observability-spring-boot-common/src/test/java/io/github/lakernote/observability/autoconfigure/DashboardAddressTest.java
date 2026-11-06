package io.github.lakernote.observability.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAddressTest {

    @Test
    void combinesPortContextPathAndServletPath() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("local.server.port", "8080")
                .withProperty("server.servlet.context-path", "/shop/")
                .withProperty("spring.mvc.servlet.path", "api");

        assertThat(DashboardAddress.url(environment)).isEqualTo("http://localhost:8080/shop/api/easy-observability/");
    }

    @Test
    void usesHttpsWhenSslIsEnabledAndIgnoresRootPaths() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("local.server.port", "8443")
                .withProperty("server.ssl.enabled", "true")
                .withProperty("server.servlet.context-path", "/")
                .withProperty("spring.mvc.servlet.path", "/");

        assertThat(DashboardAddress.url(environment)).isEqualTo("https://localhost:8443/easy-observability/");
    }

    @Test
    void staysQuietWithoutARunningServer() {
        assertThat(DashboardAddress.url(new MockEnvironment())).isNull();
    }
}
