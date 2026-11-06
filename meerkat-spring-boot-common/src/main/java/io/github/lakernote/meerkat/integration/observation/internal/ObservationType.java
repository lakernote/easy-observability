package io.github.lakernote.meerkat.integration.observation.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.SpanKind;

import java.util.Locale;

/**
 * Observation 名称、展示类型和 Trace 行为集中维护，新增接入时避免漏配入口、开关或阈值。
 */
enum ObservationType {
    REDIS(SpanKind.REDIS, false, "spring.data.redis", "lettuce"),
    REMOTE(SpanKind.REMOTE, false, MatchMode.EXACT, "http.client.requests"),
    HTTP(SpanKind.HTTP, true, MatchMode.EXACT, "http.server.requests"),
    KAFKA_CONSUMER(SpanKind.KAFKA, true, "spring.kafka.listener"),
    KAFKA_PRODUCER(SpanKind.KAFKA, false, "spring.kafka.template"),
    ELASTICSEARCH(SpanKind.REMOTE, false, "spring.data.elasticsearch", "elasticsearch"),
    DATABASE(SpanKind.DATABASE, false, "spring.jdbc", "jdbc."),
    TRANSACTION(SpanKind.TRANSACTION, false, "spring.transaction"),
    JOB(SpanKind.SCHEDULE, true, MatchMode.EXACT, "tasks.scheduled.execution");

    private static final ObservationType[] VALUES = values();
    final SpanKind spanKind;
    /** 只有入口 Observation 创建根 Trace；其余类型只作为当前 Trace 的依赖节点。 */
    final boolean entryPoint;
    private final MatchMode matchMode;
    private final String[] observationNames;

    ObservationType(SpanKind kind, boolean entry, String... observationNames) {
        this(kind, entry, MatchMode.PREFIX, observationNames);
    }

    ObservationType(SpanKind kind, boolean entry, MatchMode matchMode, String... observationNames) {
        this.spanKind = kind;
        this.entryPoint = entry;
        this.matchMode = matchMode;
        this.observationNames = observationNames;
    }

    static ObservationType of(String observationName) {
        if (observationName == null) return null;
        String normalized = observationName.toLowerCase(Locale.ROOT);
        for (ObservationType type : VALUES) {
            if (type.matches(normalized)) return type;
        }
        return null;
    }

    private boolean matches(String normalized) {
        for (String candidate : observationNames) {
            if (normalized.equals(candidate)) return true;
            if (matchMode == MatchMode.PREFIX
                    && normalized.startsWith(candidate.endsWith(".") ? candidate : candidate + ".")) {
                return true;
            }
        }
        return false;
    }

    long slowThresholdMs(MeerkatProperties properties) {
        return switch (this) {
            case JOB -> properties.getTrace().getJobSlowThreshold().toMillis();
            case HTTP -> properties.getTrace().getHttpSlowThreshold().toMillis();
            case KAFKA_CONSUMER -> properties.getTrace().getKafkaConsumerSlowThreshold().toMillis();
            default -> 0;
        };
    }

    private enum MatchMode {EXACT, PREFIX}
}
