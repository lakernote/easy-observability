package io.github.lakernote.meerkat.integration.observation.internal;

import io.micrometer.observation.Observation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.util.ClassUtils;

/**
 * Method and path of a Servlet request whose server Observation has just started. The route tag is only known at
 * stop, so the live diagnostics page reads the raw request instead.
 */
record HttpEntryRequest(String method, String path) {
    private static final boolean SERVLET_PRESENT =
            ClassUtils.isPresent("jakarta.servlet.http.HttpServletRequest", HttpEntryRequest.class.getClassLoader())
                    && ClassUtils.isPresent("org.springframework.http.server.observation.ServerRequestObservationContext",
                    HttpEntryRequest.class.getClassLoader());

    /** Returns the request, or {@code null} when the context does not wrap a Servlet request. */
    static HttpEntryRequest of(Observation.Context context) {
        return SERVLET_PRESENT ? Servlet.read(context) : null;
    }

    /** Separate holder so Servlet and Spring Web types are only resolved when they are on the classpath. */
    private static final class Servlet {
        static HttpEntryRequest read(Observation.Context context) {
            if (!(context instanceof ServerRequestObservationContext server)) return null;
            HttpServletRequest request = server.getCarrier();
            if (request == null) return null;
            String uri = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (uri == null) return null;
            if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
                uri = uri.substring(contextPath.length());
            }
            return new HttpEntryRequest(request.getMethod() == null ? "" : request.getMethod(),
                    ObservationAttributes.safe(uri.isEmpty() ? "/" : uri, 256));
        }
    }
}
