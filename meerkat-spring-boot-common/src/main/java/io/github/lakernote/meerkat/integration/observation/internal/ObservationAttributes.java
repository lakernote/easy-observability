package io.github.lakernote.meerkat.integration.observation.internal;

import io.github.lakernote.meerkat.integration.jdbc.internal.JdbcTraceSafety;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;

/**
 * Reads display names, details and status codes from Observation key values. All results are single-line and
 * length-bounded so they can be stored and rendered without further escaping.
 */
final class ObservationAttributes {
    private static final String REACTIVE_SERVER_CONTEXT =
            "org.springframework.http.server.reactive.observation.ServerRequestObservationContext";
    // Shared key lists keep the varargs arrays off the per-Observation allocation path.
    private static final String[] METHOD_KEYS = {"http.request.method", "http.method", "method"};
    private static final String[] OPERATION_KEYS = {"db.operation", "http.method", "http.request.method",
            "messaging.operation", "spring.kafka.listener.id"};
    private static final String[] TARGET_KEYS = {"http.route", "uri", "messaging.destination.name", "executor.name"};
    private static final String[] STATUS_KEYS = {"http.response.status_code", "status"};
    private static final String[] ROUTE_KEYS = {"http.route", "uri"};
    private static final String[] HOST_KEYS = {"client.name", "server.address", "target.host", "host"};
    private static final String[] URI_KEYS = {"uri", "url.template", "http.route"};

    private ObservationAttributes() {
    }

    static String operationName(Observation.Context context, ObservationType type) {
        if (type == ObservationType.KAFKA_CONSUMER) {
            String listenerId = lowCardinality(context, "spring.kafka.listener.id");
            if (!listenerId.isBlank()) return safe(listenerId, 200);
        }
        String operation = firstLowCardinality(context, OPERATION_KEYS);
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
            if (route.isBlank()) route = withoutQuery(detail);
            String method = firstLowCardinality(context, METHOD_KEYS);
            if (method.isBlank() && !"http.server.requests".equals(operation)) method = operation;
            if (!route.isBlank()) return safe(route + (method.isBlank() ? "" : " · " + method), 256);
        }
        return operation;
    }

    /** SQL is counted by the JDBC listener and transactions wrap other calls, so neither is ranked here. */
    static OperationStats.Category statsCategory(ObservationType type) {
        return switch (type) {
            case HTTP -> OperationStats.Category.HTTP;
            case JOB -> OperationStats.Category.JOB;
            case KAFKA_CONSUMER -> OperationStats.Category.KAFKA_CONSUMER;
            case KAFKA_PRODUCER -> OperationStats.Category.KAFKA_PRODUCER;
            case REMOTE, ELASTICSEARCH -> OperationStats.Category.REMOTE;
            case REDIS -> OperationStats.Category.REDIS;
            case DATABASE, TRANSACTION -> null;
        };
    }

    /**
     * Returns a low-cardinality statistics key. Only tag values that Micrometer itself treats as low cardinality are
     * used, so per-request values such as Kafka offsets or raw URLs never create new rows.
     */
    static String statsName(Observation.Context context, ObservationType type, String operation, String detail) {
        return switch (type) {
            case HTTP, JOB -> displayName(context, type, operation, detail);
            case KAFKA_CONSUMER -> joinNonBlank(" · ", lowCardinality(context, "messaging.source.name"),
                    lowCardinality(context, "spring.kafka.listener.id"), operation);
            case KAFKA_PRODUCER -> joinNonBlank(" · ", lowCardinality(context, "messaging.destination.name"),
                    operation);
            case REMOTE -> joinNonBlank(" ",
                    firstLowCardinality(context, METHOD_KEYS),
                    firstLowCardinality(context, HOST_KEYS),
                    meaningful(firstLowCardinality(context, URI_KEYS)), operation);
            case REDIS -> operation.toUpperCase(java.util.Locale.ROOT);
            case ELASTICSEARCH -> "Elasticsearch " + operation;
            case DATABASE, TRANSACTION -> operation;
        };
    }

    /** Joins the non-blank parts; the last part is a fallback used only when every earlier part is blank. */
    private static String joinNonBlank(String separator, String... parts) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i] == null || parts[i].isBlank()) continue;
            if (!joined.isEmpty()) joined.append(separator);
            joined.append(parts[i]);
        }
        return joined.isEmpty() ? parts[parts.length - 1] : safe(joined.toString(), 256);
    }

    private static String meaningful(String uri) {
        return "none".equalsIgnoreCase(uri) ? "" : withoutQuery(uri);
    }

    /** Clients without a URI template report the raw URL; its query and fragment would add a row per call and may carry tokens. */
    static String withoutQuery(String uri) {
        if (uri == null) return "";
        int end = uri.length();
        int query = uri.indexOf('?');
        if (query >= 0) end = query;
        int fragment = uri.indexOf('#');
        if (fragment >= 0 && fragment < end) end = fragment;
        return uri.substring(0, end);
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
        String target = firstLowCardinality(context, TARGET_KEYS);
        if (!target.isBlank()) return safe(target, 256);
        String sql = normalizedSql(context);
        if (sql.isBlank()) return "";
        String dataSource = lowCardinality(context, "jdbc.datasource.name");
        return safe(dataSource.isBlank() ? sql : dataSource + " · " + sql, 512);
    }

    /** Returns the numeric HTTP status, or 0 when the observation carries none. */
    static int httpStatus(Observation.Context context) {
        String status = firstLowCardinality(context, STATUS_KEYS);
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
        return isDashboardPath(firstLowCardinality(context, ROUTE_KEYS));
    }

    static boolean isDashboardPath(String route) {
        if (route == null || route.isBlank()) return false;
        String path = withoutQuery(route);
        return "/meerkat".equals(path) || path.startsWith("/meerkat/")
                || "/api/meerkat".equals(path) || path.startsWith("/api/meerkat/");
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

    private static String firstLowCardinality(Observation.Context context, String[] keys) {
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
