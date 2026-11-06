package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.trace.internal.BoundedThrowableFormatter;

/** Shared confidentiality boundary for every JDBC trace integration. */
public final class JdbcTraceSafety {
    private static final String SAFE_ERROR_MESSAGE = "JDBC query failed";

    private JdbcTraceSafety() {
    }

    /** Returns a bounded SQL template without literal or bound parameter values. */
    public static String sanitizeSql(String sql) {
        return JdbcTraceSql.normalize(sql);
    }

    /** Retains the failure type and safe stack structure without copying database-driver messages. */
    public static BoundedThrowableFormatter.FailureEvidence captureFailure(Throwable failure) {
        if (failure == null) return BoundedThrowableFormatter.FailureEvidence.EMPTY;
        try {
            return BoundedThrowableFormatter.captureWithoutMessages(failure, SAFE_ERROR_MESSAGE);
        } catch (Throwable ignored) {
            String type = failure.getClass().getName();
            return new BoundedThrowableFormatter.FailureEvidence(type, SAFE_ERROR_MESSAGE, type);
        }
    }
}
