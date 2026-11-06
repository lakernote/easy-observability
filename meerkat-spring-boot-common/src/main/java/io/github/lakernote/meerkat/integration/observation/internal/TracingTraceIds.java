package io.github.lakernote.meerkat.integration.observation.internal;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.micrometer.observation.Observation;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.springframework.util.ClassUtils;

/**
 * 读取 Micrometer Tracing（OpenTelemetry/Brave 桥接）为同一 Observation 创建的 Span 的 trace id。
 * <p>入口没有上游 ID 时沿用它，使 {@code %X{traceId}} 与 {@code %X{meerkatTraceId}} 一致，
 * Trace 也能在 Zipkin、Jaeger 等平台按同一 ID 查到。</p>
 */
final class TracingTraceIds {
    private static final boolean PRESENT = ClassUtils.isPresent(
            "io.micrometer.tracing.handler.TracingObservationHandler", TracingTraceIds.class.getClassLoader());

    private TracingTraceIds() {
    }

    static String of(Observation.Context context) {
        if (!PRESENT) return null;
        try {
            return Bridge.read(context);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** Separate holder so Micrometer Tracing types are only resolved when they are on the classpath. */
    private static final class Bridge {
        static String read(Observation.Context context) {
            TracingObservationHandler.TracingContext tracing = context.get(TracingObservationHandler.TracingContext.class);
            if (tracing == null || tracing.getSpan() == null) return null;
            String traceId = tracing.getSpan().context().traceId();
            return TraceIds.isValidTraceId(traceId) ? traceId : null;
        }
    }
}
