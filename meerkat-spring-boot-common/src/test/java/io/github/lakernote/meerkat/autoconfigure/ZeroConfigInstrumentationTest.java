package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.trace.TraceContextTaskDecorator;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ZeroConfigInstrumentationTest {
    @Test
    void addsTraceDecoratorToApplicationExecutors() {
        TraceContextTaskDecorator ours = new TraceContextTaskDecorator();
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        ObservabilityAutoConfiguration.applyTraceDecorator(pool, ours);
        assertThat(ReflectionTestUtils.getField(pool, "taskDecorator")).isSameAs(ours);

        SimpleAsyncTaskExecutor simple = new SimpleAsyncTaskExecutor();
        ObservabilityAutoConfiguration.applyTraceDecorator(simple, ours);
        assertThat(ReflectionTestUtils.getField(simple, "taskDecorator")).isSameAs(ours);
    }

    @Test
    void composesWithApplicationDecoratorAndNeverAppliesTwice() {
        TraceContextTaskDecorator ours = new TraceContextTaskDecorator();
        AtomicInteger applicationCalls = new AtomicInteger();
        TaskDecorator application = task -> {
            applicationCalls.incrementAndGet();
            return task;
        };
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setTaskDecorator(application);
        ObservabilityAutoConfiguration.applyTraceDecorator(pool, ours);
        Object composed = ReflectionTestUtils.getField(pool, "taskDecorator");
        assertThat(composed).isInstanceOf(CompositeTaskDecorator.class);
        ((TaskDecorator) composed).decorate(() -> { });
        assertThat(applicationCalls).hasValue(1);

        ObservabilityAutoConfiguration.applyTraceDecorator(pool, ours);
        assertThat(ReflectionTestUtils.getField(pool, "taskDecorator")).isSameAs(composed);

        ThreadPoolTaskExecutor bootComposed = new ThreadPoolTaskExecutor();
        TaskDecorator bootDecorator = new CompositeTaskDecorator(List.of(application, ours));
        bootComposed.setTaskDecorator(bootDecorator);
        ObservabilityAutoConfiguration.applyTraceDecorator(bootComposed, ours);
        assertThat(ReflectionTestUtils.getField(bootComposed, "taskDecorator")).isSameAs(bootDecorator);
    }

    @Test
    void enablesKafkaObservationUnlessExplicitlyDisabled() {
        KafkaTemplate<Object, Object> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of()));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        MockEnvironment defaults = new MockEnvironment();
        KafkaTraceAutoConfiguration.enableObservation(template, defaults);
        KafkaTraceAutoConfiguration.enableObservation(factory, defaults);
        assertThat(ReflectionTestUtils.getField(template, "observationEnabled")).isEqualTo(true);
        assertThat(factory.getContainerProperties().isObservationEnabled()).isTrue();

        KafkaTemplate<Object, Object> optedOut = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of()));
        ConcurrentKafkaListenerContainerFactory<Object, Object> optedOutFactory = new ConcurrentKafkaListenerContainerFactory<>();
        MockEnvironment disabled = new MockEnvironment()
                .withProperty("spring.kafka.template.observation-enabled", "false")
                .withProperty("spring.kafka.listener.observation-enabled", "false");
        KafkaTraceAutoConfiguration.enableObservation(optedOut, disabled);
        KafkaTraceAutoConfiguration.enableObservation(optedOutFactory, disabled);
        assertThat(ReflectionTestUtils.getField(optedOut, "observationEnabled")).isEqualTo(false);
        assertThat(optedOutFactory.getContainerProperties().isObservationEnabled()).isFalse();
    }

    @Test
    void attachesRegistryToPlainRestClientsOnly() {
        ObservationRegistry registry = activeRegistry();
        RestTemplate plain = new RestTemplate();
        RestTemplateObservationAutoConfiguration.observe(plain, registry);
        assertThat(plain.getObservationRegistry()).isSameAs(registry);

        ObservationRegistry own = activeRegistry();
        RestTemplate configured = new RestTemplate();
        configured.setObservationRegistry(own);
        RestTemplateObservationAutoConfiguration.observe(configured, registry);
        assertThat(configured.getObservationRegistry()).isSameAs(own);

        Object client = RestTemplateObservationAutoConfiguration.observe(RestClient.create(), registry);
        assertThat(ReflectionTestUtils.getField(client, "observationRegistry")).isSameAs(registry);
        RestClient owned = RestClient.builder().observationRegistry(own).build();
        assertThat(RestTemplateObservationAutoConfiguration.observe(owned, registry)).isSameAs(owned);
    }

    private static ObservationRegistry activeRegistry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        return registry;
    }
}
