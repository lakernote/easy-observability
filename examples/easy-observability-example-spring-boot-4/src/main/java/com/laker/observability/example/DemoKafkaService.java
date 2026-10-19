package com.laker.observability.example;

import com.laker.observability.trace.TraceIdContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DemoKafkaService {
    public static final String TOPIC = "easy-observability-demo";
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AtomicReference<Map<String, Object>> lastConsumed = new AtomicReference<>(Map.of("status", "暂无消费记录"));

    public DemoKafkaService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Bean
    DefaultErrorHandler demoKafkaErrorHandler() {
        // 示例错误只投递一次，避免重试生成多条相同 Trace ID 的错误记录。
        return new DefaultErrorHandler((record, error) -> { }, new FixedBackOff(0L, 0L));
    }

    @Bean
    NewTopic easyObservabilityDemoTopic() {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
    }

    public Map<String, Object> publishAndVerify(String traceId, String mode) {
        String key = UUID.randomUUID().toString();
        try {
            SendResult<String, String> sent = kafkaTemplate.send(TOPIC, key, mode + ":trace-check:" + key)
                    .get(8, TimeUnit.SECONDS);
            // Kafka 首次启动时 broker 和 consumer group 可能仍在完成协调，留出冷启动时间。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            Map<String, Object> consumed;
            do {
                consumed = lastConsumed.get();
                if (key.equals(consumed.get("key"))) {
                    return Map.of("traceId", traceId, "consumerTraceId", consumed.get("traceId"),
                            "traceIdMatches", traceId.equals(consumed.get("traceId")), "topic", TOPIC,
                            "consumerOutcome", consumed.get("outcome"),
                            "partition", sent.getRecordMetadata().partition(), "offset", sent.getRecordMetadata().offset(),
                            "messageKey", key);
                }
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            return Map.of("traceId", traceId, "consumerTraceId", "尚未消费，请稍后查询 /api/demo/kafka/status",
                    "traceIdMatches", false, "topic", TOPIC, "messageKey", key);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka 示例消息发送或消费失败", e);
        }
    }

    @KafkaListener(topics = TOPIC, groupId = "easy-observability-demo")
    public void consume(ConsumerRecord<String, String> record) {
        // TraceIdRecordInterceptor 从消息头恢复 ID；这里读取的是消费者当前 Trace 上下文。
            String payload = record.value();
            if (payload.startsWith("slow:")) pauseForSlowConsumer();
            boolean failed = payload.startsWith("error:");
            lastConsumed.set(Map.of("key", record.key(), "payload", payload,
                    "traceId", TraceIdContext.currentTraceId(), "partition", record.partition(), "offset", record.offset(),
                    "outcome", failed ? "error" : payload.startsWith("slow:") ? "slow" : "success"));
            if (failed) throw new IllegalStateException("Example Kafka consumer failure");
    }

    public Map<String, Object> lastConsumed() {
        return lastConsumed.get();
    }

    private static void pauseForSlowConsumer() {
        try {
            Thread.sleep(650);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Example Kafka consumer interrupted", error);
        }
    }
}
