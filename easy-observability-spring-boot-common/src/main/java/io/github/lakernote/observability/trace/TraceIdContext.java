package io.github.lakernote.observability.trace;

import org.slf4j.MDC;

/**
 * Reads the current trace ID.
 *
 * <p>The trace ID is stored in SLF4J MDC under {@link #MDC_KEY}, so log patterns can print it with
 * {@code %X{traceId}}. The starter binds it at HTTP, Kafka, scheduled-job and executor boundaries.</p>
 */
public final class TraceIdContext {
    /** MDC key used to store the current trace ID. */
    public static final String MDC_KEY = "traceId";

    private TraceIdContext() {
    }

    /** Returns the trace ID bound to the current thread, or {@code null} outside a traced boundary. */
    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }
}
