package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.kafka.TraceIdProducerInterceptor;
import io.github.lakernote.observability.integration.observation.ObservationTraceHandler;
import io.github.lakernote.observability.metrics.OperationRecorder;
import io.github.lakernote.observability.trace.store.RecentTraceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.RecordInterceptor;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTraceAutoConfigurationTest {
    @Test
    void addsTracePropagationToListenerContainer() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaTraceAutoConfiguration.class,
                        KafkaListenerTraceAutoConfiguration.class))
                .withBean(EasyObservabilityProperties.class, EasyObservabilityProperties::new)
                .run(context -> {
                    TestContainer container = new TestContainer(
                            org.mockito.Mockito.mock(ConsumerFactory.class),
                                    new ContainerProperties("events"));
                    context.getBean(ContainerCustomizer.class).configure(container);
                    assertThat(container.installedRecordInterceptor())
                            .isInstanceOf(io.github.lakernote.observability.integration.kafka.TraceIdRecordInterceptor.class);
                });
    }

    @Test
    void appendsProducerInterceptorAndKeepsConfiguredInterceptors() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaProducerTraceAutoConfiguration.class))
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
                    factory.destroy();
                });
    }

    @Test
    void consumerObservationUsesTraceIdFromKafkaRecordHeader() {
        EasyObservabilityProperties properties = new EasyObservabilityProperties();
        RecentTraceStore history = new RecentTraceStore(10, 10);
        OperationRecorder recorder = new OperationRecorder(new SimpleMeterRegistry(), history, properties);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaTraceAutoConfiguration.class))
                .withBean(EasyObservabilityProperties.class, () -> properties)
                .run(context -> {
                    ObservationTraceHandler handler = new ObservationTraceHandler(recorder, history, null,
                            properties, context.getBeansOfType(
                            io.github.lakernote.observability.integration.observation.ObservationTraceIdResolver.class)
                                    .values().stream().toList());
                    ConsumerRecord<String, String> record = new ConsumerRecord<>("events", 0, 1L, "key", "value");
                    record.headers().add(new RecordHeader("X-Trace-Id",
                            "trace-from-message".getBytes(StandardCharsets.UTF_8)));
                    KafkaRecordReceiverContext receiver = new KafkaRecordReceiverContext(record, "demo-listener", () -> "cluster");
                    receiver.setName("spring.kafka.listener");

                    handler.onStart(receiver);
                    handler.onStop(receiver);

                    assertThat(history.recent(10)).singleElement()
                            .satisfies(trace -> assertThat(trace.traceId()).isEqualTo("trace-from-message"));
                });
    }

    private static final class TestContainer extends ConcurrentMessageListenerContainer<Object, Object> {
        private TestContainer(ConsumerFactory<Object, Object> factory, ContainerProperties properties) {
            super(factory, properties);
        }

        private RecordInterceptor<Object, Object> installedRecordInterceptor() {
            return super.getRecordInterceptor();
        }
    }
}
