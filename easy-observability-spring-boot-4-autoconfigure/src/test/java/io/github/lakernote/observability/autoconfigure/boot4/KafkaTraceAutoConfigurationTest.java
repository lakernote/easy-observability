package io.github.lakernote.observability.autoconfigure.boot4;

import io.github.lakernote.observability.autoconfigure.KafkaTraceAutoConfiguration;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.kafka.TraceIdProducerInterceptor;
import io.github.lakernote.observability.integration.observation.ObservationTraceHandler;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTraceAutoConfigurationTest {
    @Test
    void exposesTraceInterceptorAsBootListenerInterceptorBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaTraceAutoConfiguration.class))
                .withBean(EasyObservabilityProperties.class, EasyObservabilityProperties::new)
                .run(context -> {
                    assertThat(context.getBean(RecordInterceptor.class))
                            .isInstanceOf(io.github.lakernote.observability.integration.kafka.TraceIdRecordInterceptor.class);
                });
    }

    @Test
    void appendsProducerInterceptorAndKeepsConfiguredInterceptors() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaProducerTraceAutoConfiguration.class))
                .withBean(EasyObservabilityProperties.class, () -> {
                    EasyObservabilityProperties properties = new EasyObservabilityProperties();
                    properties.getTrace().setHeaderName("x-correlation-id");
                    return properties;
                })
                .run(context -> {
                    DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(Map.of(
                            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                            ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, List.of("example.ExistingInterceptor")));
                    context.getBean(DefaultKafkaProducerFactoryCustomizer.class).customize(factory);
                    Object configured = factory.getConfigurationProperties()
                            .get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
                    List<?> interceptors = (List<?>) configured;
                    List<String> names = interceptors.stream().map(String::valueOf).toList();
                    assertThat(names).contains("example.ExistingInterceptor",
                            TraceIdProducerInterceptor.class.getName());
                    assertThat(factory.getConfigurationProperties().get(
                            TraceIdProducerInterceptor.TRACE_ID_HEADER_CONFIG)).isEqualTo("x-correlation-id");
                    factory.destroy();
                });
    }

    @Test
    void producerUsesConfiguredHeaderAndDoesNotInventTraceIdsOutsideAnEntry() {
        TraceIdProducerInterceptor<String, String> interceptor = new TraceIdProducerInterceptor<>();
        interceptor.configure(Map.of(TraceIdProducerInterceptor.TRACE_ID_HEADER_CONFIG, "x-correlation-id"));
        var record = new org.apache.kafka.clients.producer.ProducerRecord<>("events", "key", "value");
        assertThat(interceptor.onSend(record).headers().lastHeader("x-correlation-id")).isNull();

        io.github.lakernote.observability.trace.TraceIdContext.putTraceId("0123456789abcdef0123456789abcdef");
        try {
            assertThat(interceptor.onSend(record).headers().lastHeader("x-correlation-id").value())
                    .containsExactly("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            assertThat(record.headers().lastHeader("X-Trace-Id")).isNull();
        } finally {
            io.github.lakernote.observability.trace.TraceIdContext.clear();
        }
    }

    @Test
    void slowConsumerObservationUsesTraceIdFromKafkaRecordHeader() throws Exception {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        properties.getTrace().setKafkaConsumerSlowThresholdMs(1);
        RecentTraceStore history = new RecentTraceStore(10);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaTraceAutoConfiguration.class))
                .withBean(EasyObservabilityProperties.class, () -> properties)
                .run(context -> {
                    ObservationTraceHandler handler = new ObservationTraceHandler(history, null,
                            properties, context.getBeansOfType(
                            io.github.lakernote.observability.integration.observation.ObservationTraceIdResolver.class)
                                    .values().stream().toList());
                    ConsumerRecord<String, String> record = new ConsumerRecord<>("events", 0, 1L, "key", "value");
                    record.headers().add(new RecordHeader("X-Trace-Id",
                            "trace-from-message".getBytes(StandardCharsets.UTF_8)));
                    KafkaRecordReceiverContext receiver = new KafkaRecordReceiverContext(record, "demo-listener", () -> "cluster");
                    receiver.setName("spring.kafka.listener");

                    handler.onStart(receiver);
                    Thread.sleep(5);
                    handler.onStop(receiver);

                    assertThat(history.recent(10)).singleElement()
                            .satisfies(trace -> assertThat(trace.traceId()).isEqualTo("trace-from-message"));
                });
    }

}
