package io.github.lakernote.meerkat.metrics.internal;

import io.github.lakernote.meerkat.web.internal.DashboardPaths;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;

/**
 * Collapses Spring Boot's {@code http.server.requests} timers, one per URI, method, status and exception, into one
 * timer per status class in Meerkat's registry: {@code 1xx} to {@code 5xx}, with {@code 429} kept apart from
 * {@code 4xx} because it signals rate limiting rather than a client mistake. Requests for the dashboard itself are
 * renamed to a meter outside the whitelist, so they are dropped. The application's own registries are unaffected.
 */
final class HttpStatusClasses implements MeterFilter {
    static final String METER = "http.server.requests";
    static final String TAG = "status";
    private static final String DROPPED = "meerkat.dashboard.requests";

    @Override
    public Meter.Id map(Meter.Id id) {
        if (!METER.equals(id.getName())) return id;
        if (DashboardPaths.isDashboardPath(id.getTag("uri"))) return id.withName(DROPPED);
        return id.replaceTags(Tags.of(TAG, statusClass(id.getTag(TAG))));
    }

    static String statusClass(String status) {
        if (status == null || status.length() != 3) return "unknown";
        if ("429".equals(status)) return status;
        char first = status.charAt(0);
        boolean numeric = Character.isDigit(status.charAt(1)) && Character.isDigit(status.charAt(2));
        return first >= '1' && first <= '5' && numeric ? first + "xx" : "unknown";
    }
}
