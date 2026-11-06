package io.github.lakernote.meerkat.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/** Builds the local dashboard URL from the running server's port, context path and DispatcherServlet path. */
final class DashboardAddress {
    private static final Logger log = LoggerFactory.getLogger(DashboardAddress.class);

    private DashboardAddress() {
    }

    static void log(Environment environment) {
        String url = url(environment);
        if (url != null) log.info("Meerkat dashboard: {}", url);
    }

    /** Returns {@code null} when no server port is known, for example in a mock web environment. */
    static String url(Environment environment) {
        String port = environment.getProperty("local.server.port");
        if (port == null || port.isBlank()) return null;
        String scheme = environment.getProperty("server.ssl.enabled", Boolean.class, false) ? "https" : "http";
        return scheme + "://localhost:" + port + path(environment.getProperty("server.servlet.context-path"))
                + path(environment.getProperty("spring.mvc.servlet.path")) + "/meerkat/";
    }

    private static String path(String value) {
        if (value == null || value.isBlank() || "/".equals(value.trim())) return "";
        String path = value.trim();
        if (!path.startsWith("/")) path = "/" + path;
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
