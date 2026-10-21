package io.github.lakernote.observability.integration.kafka;

import io.github.lakernote.observability.trace.TraceIdContext;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Copies the current trace ID to outgoing Kafka records without retaining the message payload. */
public class TraceIdProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_HEADER_CONFIG = "easy.observability.trace.header-name";
    private String traceIdHeader = TRACE_ID_HEADER;

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        String traceId = TraceIdContext.currentTraceId();
        if (TraceIdContext.isValidTraceId(traceId)) {
            record.headers().remove(traceIdHeader);
            record.headers().add(traceIdHeader, traceId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override public void onAcknowledgement(RecordMetadata metadata, Exception exception) { }
    @Override public void close() { }
    @Override public void configure(Map<String, ?> configs) {
        Object configuredHeader = configs.get(TRACE_ID_HEADER_CONFIG);
        if (configuredHeader instanceof String value && !value.isBlank()) traceIdHeader = value.trim();
    }
}
