package io.github.lakernote.meerkat.trace.internal;

import io.github.lakernote.meerkat.trace.TraceIdContext;
import org.slf4j.MDC;

import java.util.UUID;

/** Validates, generates and binds trace IDs for the starter's integrations. */
public final class TraceIds {

    /**
     * W3C trace id 为 32 位十六进制，Brave/B3 常见 16 或 32 位，UUID 为 36 位。
     * 预留业务前缀后将上限定为 64，避免外部输入无限放大日志、JSONL 与 JFR 事件。
     */
    public static final int MAX_TRACE_ID_LENGTH = 64;
    /** W3C Trace Context 请求头；OpenTelemetry、Micrometer Tracing 等默认使用。 */
    public static final String TRACEPARENT_HEADER = "traceparent";
    /** 入口过滤器在没有上游 ID、自行生成时设置，供 Observation 回调换成 Micrometer Tracing 的 ID。 */
    public static final String GENERATED_REQUEST_ATTRIBUTE = TraceIds.class.getName() + ".generated";

    private TraceIds() {
    }

    public static String getOrCreateTraceId() {
        String traceId = TraceIdContext.currentTraceId();
        if (!isValidTraceId(traceId)) {
            traceId = newTraceId();
            putTraceId(traceId);
        }
        return traceId;
    }

    public static String putOrCreateTraceId(String traceId) {
        if (isValidTraceId(traceId)) {
            putTraceId(traceId);
            return traceId;
        }
        String generatedTraceId = newTraceId();
        putTraceId(generatedTraceId);
        return generatedTraceId;
    }

    public static void putTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TraceIdContext.MDC_KEY, traceId);
        }
    }

    public static void clear() {
        MDC.remove(TraceIdContext.MDC_KEY);
    }

    public static String resolve(String traceId) {
        return isValidTraceId(traceId) ? traceId : newTraceId();
    }

    /**
     * 从 {@code traceparent}（{@code version-traceId-parentId-flags}）取出 32 位 trace id；
     * 格式不符、版本为 {@code ff} 或 trace id 全零时返回 {@code null}。
     */
    public static String fromTraceparent(String header) {
        if (header == null) return null;
        String value = header.strip();
        if (value.length() < 55 || value.charAt(2) != '-' || value.charAt(35) != '-' || value.charAt(52) != '-') {
            return null;
        }
        if (!isLowerHex(value, 0, 2) || value.startsWith("ff") || !isLowerHex(value, 36, 52)
                || !isLowerHex(value, 53, 55)) {
            return null;
        }
        if (value.length() > 55 && (value.startsWith("00") || value.charAt(55) != '-')) return null;
        String traceId = value.substring(3, 35);
        if (!isLowerHex(traceId, 0, 32) || traceId.chars().allMatch(c -> c == '0')) return null;
        return traceId;
    }

    private static boolean isLowerHex(String value, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 只接收可安全写入日志、JSONL 和 JFR 字符串字段的短 ASCII 标识。
     * 支持十六进制 trace id、UUID，以及带 {@code . _ : -} 分隔符的网关业务链路号。
     */
    public static boolean isValidTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()
                || traceId.length() > MAX_TRACE_ID_LENGTH
                || !isAsciiLetterOrDigit(traceId.charAt(0))) {
            return false;
        }
        for (int i = 1; i < traceId.length(); i++) {
            char character = traceId.charAt(i);
            if (!isAsciiLetterOrDigit(character)
                    && character != '.'
                    && character != '_'
                    && character != ':'
                    && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return character >= '0' && character <= '9'
                || character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z';
    }
}
