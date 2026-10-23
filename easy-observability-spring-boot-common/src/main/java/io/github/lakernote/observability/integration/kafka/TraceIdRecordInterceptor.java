package io.github.lakernote.observability.integration.kafka;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.MdcContext;
import io.github.lakernote.observability.trace.TraceIdContext;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Kafka 消费端 Trace 上下文拦截器：消费前从消息头恢复 Trace ID，消费后恢复线程原有上下文。
 * 消息头缺失或 Trace ID 格式无效时，为本次消费创建新的 Trace ID。
 */
public final class TraceIdRecordInterceptor implements RecordInterceptor<Object, Object> {
    private final String headerName;
    private final ThreadLocal<Deque<MdcContext.Scope>> scopes = ThreadLocal.withInitial(ArrayDeque::new);

    public TraceIdRecordInterceptor(EasyObservabilityProperties properties) {
        this.headerName = properties.getTrace().getHeaderName();
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                     Consumer<Object, Object> consumer) {
        // 保存线程进入本条消息前的 MDC/Trace 状态，避免线程复用时串到下一条消息。
        MdcContext.Scope scope = MdcContext.scope();
        scopes.get().push(scope);
        String traceId = headerValue(record, headerName);
        if (TraceIdContext.isValidTraceId(traceId)) TraceIdContext.putTraceId(traceId);
        else TraceIdContext.getOrCreateTraceId();
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        // Listener 执行完后恢复原状态；Kafka 消费线程会被复用，必须清理本条消息的上下文。
        Deque<MdcContext.Scope> stack = scopes.get();
        if (stack.isEmpty()) return;
        try {
            stack.pop().close();
        } finally {
            if (stack.isEmpty()) scopes.remove();
        }
    }

    private static String headerValue(ConsumerRecord<?, ?> record, String name) {
        if (record == null || name == null || name.isBlank()) return null;
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) return null;
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
