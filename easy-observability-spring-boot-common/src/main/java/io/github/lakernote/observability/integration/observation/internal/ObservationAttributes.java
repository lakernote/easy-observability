package io.github.lakernote.observability.integration.observation.internal;

import io.github.lakernote.observability.integration.jdbc.internal.JdbcTraceSafety;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;

/**
 * Reads display names, details and status codes from Observation key values. All results are single-line and
 * length-bounded so they can be stored and rendered without further escaping.
 */
final class ObservationAttributes {
    private static final String REACTIVE_SERVER_CONTEXT =
            "org.springframework.http.server.reactive.observation.ServerRequestObservationContext";

    private ObservationAttributes() {
    }

    static String operationName(Observation.Context context, ObservationType type) {
        if (type == ObservationType.KAFKA_CONSUMER) {
            String listenerId = lowCardinality(context, "spring.kafka.listener.id");
            if (!listenerId.isBlank()) return safe(listenerId, 200);
        }
        String operation = firstLowCardinality(context, "db.operation", "http.method", "http.request.method",
                "messaging.operation", "spring.kafka.listener.id");
        if (operation.isBlank()) operation = nullToEmpty(context.getContextualName());
        if (operation.isBlank()) operation = nullToEmpty(context.getName());
        if (operation.isBlank()) operation = type.name();
        return safe(operation, 200);
    }

    static String displayName(Observation.Context context, ObservationType type, String operation, String detail) {
        String contextualName = context.getContextualName();
        if (type == ObservationType.JOB && contextualName != null && !contextualName.isBlank()) {
            return safe(contextualName, 200);
        }
        if (type == ObservationType.HTTP) {
            String route = lowCardinality(context, "http.route");
            if (route.isBlank()) route = detail;
            String method = firstLowCardinality(context, "http.request.method", "http.method", "method");
            if (method.isBlank() && !"http.server.requests".equals(operation)) method = operation;
            if (!route.isBlank()) return safe(route + (method.isBlank() ? "" : " · " + method), 256);
        }
        return operation;
    }

    static String detail(Observation.Context context, ObservationType type) {
        if (type == ObservationType.KAFKA_CONSUMER) {
            StringBuilder details = new StringBuilder();
            appendDetail(details, "topic", lowCardinality(context, "messaging.source.name"));
            appendDetail(details, "partition", highCardinality(context, "messaging.kafka.source.partition"));
            appendDetail(details, "offset", highCardinality(context, "messaging.kafka.message.offset"));
            appendDetail(details, "group", lowCardinality(context, "messaging.kafka.consumer.group"));
            appendDetail(details, "listener", lowCardinality(context, "spring.kafka.listener.id"));
            if (!details.isEmpty()) return details.toString();
        }
        String target = firstLowCardinality(context, "http.route", "uri", "messaging.destination.name",
                "executor.name");
        if (!target.isBlank()) return safe(target, 256);
        String sql = normalizedSql(context);
        if (sql.isBlank()) return "";
        String dataSource = lowCardinality(context, "jdbc.datasource.name");
        return safe(dataSource.isBlank() ? sql : dataSource + " · " + sql, 512);
    }

    /** Returns the numeric HTTP status, or 0 when the observation carries none. */
    static int httpStatus(Observation.Context context) {
        String status = firstLowCardinality(context, "http.response.status_code", "status");
        // Most observations carry no numeric status; parsing by hand avoids an exception on every stop.
        if (status.isEmpty() || status.length() > 3) return 0;
        int code = 0;
        for (int i = 0; i < status.length(); i++) {
            char digit = status.charAt(i);
            if (digit < '0' || digit > '9') return 0;
            code = code * 10 + (digit - '0');
        }
        return code;
    }

    static boolean isReactiveServerRequest(Observation.Context context) {
        for (Class<?> type = context.getClass(); type != null; type = type.getSuperclass()) {
            if (REACTIVE_SERVER_CONTEXT.equals(type.getName())) return true;
        }
        return false;
    }

    static boolean isDashboardRequest(Observation.Context context) {
        return isDashboardPath(firstLowCardinality(context, "http.route", "uri"));
    }

    static boolean isDashboardPath(String route) {
        if (route == null || route.isBlank()) return false;
        String path = route.split("[?#]", 2)[0];
        return "/easy-observability".equals(path) || "/easy-observability/".equals(path)
                || path.startsWith("/easy-observability/assets/")
                || "/api/easy-observability".equals(path) || path.startsWith("/api/easy-observability/");
    }

    static String highCardinality(Observation.Context context, String key) {
        KeyValue value = context.getHighCardinalityKeyValue(key);
        return value == null ? "" : value.getValue();
    }

    static String safe(String value, int limit) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ');
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    /**
     * SQL is useful for slow-query diagnosis; literal values and comments are removed before it is retained.
     */
    private static String normalizedSql(Observation.Context context) {
        for (KeyValue value : context.getHighCardinalityKeyValues()) {
            if (value.getKey().startsWith("jdbc.query[") && !value.getValue().isBlank()) {
                return JdbcTraceSafety.sanitizeSql(value.getValue());
            }
        }
        return "";
    }

    private static String firstLowCardinality(Observation.Context context, String... keys) {
        for (String key : keys) {
            String value = lowCardinality(context, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String lowCardinality(Observation.Context context, String key) {
        KeyValue value = context.getLowCardinalityKeyValue(key);
        return value == null ? "" : value.getValue();
    }

    private static void appendDetail(StringBuilder details, String key, String value) {
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value)) return;
        if (!details.isEmpty()) details.append(", ");
        details.append(key).append('=').append(safe(value, 200));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
