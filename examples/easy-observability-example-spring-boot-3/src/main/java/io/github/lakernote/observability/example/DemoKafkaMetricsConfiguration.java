package io.github.lakernote.observability.example;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.MicrometerConsumerListener;

/** Uses Spring Kafka's native binder so Kafka client metrics appear beside listener observations. */
@Configuration(proxyBeanMethods = false)
public class DemoKafkaMetricsConfiguration {
    @Bean
    DefaultKafkaConsumerFactoryCustomizer demoKafkaConsumerMetrics(MeterRegistry registry) {
        return factory -> factory.addListener(new MicrometerConsumerListener<>(registry));
    }
}
