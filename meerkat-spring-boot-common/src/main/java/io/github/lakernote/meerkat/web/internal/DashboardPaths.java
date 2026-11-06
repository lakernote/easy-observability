package io.github.lakernote.meerkat.web.internal;

/** Recognizes requests for Meerkat's own pages and API, which are left out of the application's statistics. */
public final class DashboardPaths {
    private DashboardPaths() {
    }

    /** Whether {@code route}, a path or URI template with an optional query, belongs to the dashboard. */
    public static boolean isDashboardPath(String route) {
        if (route == null || route.isBlank()) return false;
        int end = route.length();
        for (char separator : new char[] {'?', '#'}) {
            int index = route.indexOf(separator);
            if (index >= 0 && index < end) end = index;
        }
        String path = route.substring(0, end);
        return "/meerkat".equals(path) || path.startsWith("/meerkat/")
                || "/api/meerkat".equals(path) || path.startsWith("/api/meerkat/");
    }
}
