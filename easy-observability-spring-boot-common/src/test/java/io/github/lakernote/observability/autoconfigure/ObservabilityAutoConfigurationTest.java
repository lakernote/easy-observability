package io.github.lakernote.observability.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.storage.internal.StorageDirectory;
import io.github.lakernote.observability.trace.TraceContextTaskDecorator;
import io.github.lakernote.observability.trace.internal.TraceSpanAspect;
import io.github.lakernote.observability.trace.internal.store.TraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskDecorator;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class,
                    TraceSpanAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void defaultStorageDirectoryIsSeparatedByApplicationName() {
        Path base = Path.of("data", "easy-observability");
        assertThat(ObservabilityAutoConfiguration.storageDirectory(null, "order-service"))
                .isEqualTo(base.resolve("order-service"));
        assertThat(ObservabilityAutoConfiguration.storageDirectory(" ", null)).isEqualTo(base.resolve("application"));
        assertThat(ObservabilityAutoConfiguration.storageDirectory(null, "a/../b c")).isEqualTo(base.resolve("a_.._b_c"));
        assertThat(ObservabilityAutoConfiguration.storageDirectory(null, "..")).isEqualTo(base.resolve("application"));
        assertThat(ObservabilityAutoConfiguration.storageDirectory("/var/lib/eo", "order-service"))
                .isEqualTo(Path.of("/var/lib/eo"));
    }

    @Test
    void storesTraceHistoryInTheOwnedStorageDirectory(@TempDir Path storage) {
        contextRunner.withPropertyValues("easy.observability.storage.directory=" + storage).run(context -> {
            assertThat(context.getBean(StorageDirectory.class).available()).isTrue();
            assertThat(context.getBean(TraceStore.class).storageStatus().persistent()).isTrue();
        });
    }

    @Test
    void rejectsInvalidTraceStorageSettings() {
        contextRunner.withPropertyValues("easy.observability.trace.retention=12h")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("easy.observability.trace.retention"));
        contextRunner.withPropertyValues("easy.observability.trace.max-storage-size=0B")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("easy.observability.trace.max-storage-size"));
    }

    @Test
    void secondContextOnTheSameDirectoryKeepsHistoryInMemory(@TempDir Path storage) {
        contextRunner.withPropertyValues("easy.observability.storage.directory=" + storage).run(first ->
                contextRunner.withPropertyValues("easy.observability.storage.directory=" + storage).run(second -> {
                    assertThat(second).hasNotFailed();
                    assertThat(second.getBean(StorageDirectory.class).available()).isFalse();
                    assertThat(second.getBean(TraceStore.class).storageStatus().persistent())
                            .isFalse();
                    assertThat(first.getBean(TraceStore.class).storageStatus().persistent())
                            .isTrue();
                }));
    }

    @Test
    void bindsDurationsAndSizesWithUnits() {
        contextRunner.withPropertyValues(
                "easy.observability.trace.http-slow-threshold=2s",
                "easy.observability.trace.job-slow-threshold=1500",
                "easy.observability.trace.kafka-consumer-slow-threshold=1m",
                "easy.observability.trace.cpu-threshold=0",
                "easy.observability.trace.max-storage-size=1MB",
                "easy.observability.trace.retention=3",
                "easy.observability.metrics.retention=14",
                "easy.observability.metrics.max-storage-size=32MB").run(context -> {
            EasyObservabilityProperties properties = context.getBean(EasyObservabilityProperties.class);
            assertThat(properties.getTrace().getHttpSlowThreshold()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getTrace().getJobSlowThreshold()).isEqualTo(Duration.ofMillis(1500));
            assertThat(properties.getTrace().getKafkaConsumerSlowThreshold()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.getTrace().getCpuThreshold()).isZero();
            assertThat(properties.getTrace().getMaxStorageSize()).isEqualTo(DataSize.ofMegabytes(1));
            assertThat(properties.getTrace().getRetention()).isEqualTo(Duration.ofDays(3));
            assertThat(context.getBean(TraceStore.class).storageStatus().retentionDays()).isEqualTo(3);
            assertThat(properties.getMetrics().getRetention()).isEqualTo(Duration.ofDays(14));
            assertThat(properties.getMetrics().getMaxStorageSize()).isEqualTo(DataSize.ofMegabytes(32));
        });
    }

    @Test
    void registersTheTraceTaskDecoratorAsTheOnlyDefaultDecorator() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TaskDecorator.class);
            assertThat(context.getBeanProvider(TaskDecorator.class).getIfUnique())
                    .isInstanceOf(TraceContextTaskDecorator.class);
        });
    }

    @Test
    void keepsTheApplicationDecoratorAsTheDefaultOnSpringBoot3() {
        TaskDecorator application = task -> task;
        contextRunner.withBean("applicationDecorator", TaskDecorator.class, () -> application)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanProvider(TaskDecorator.class).getIfUnique()).isSameAs(application);
                    assertThat(context.getBeansOfType(TaskDecorator.class)).hasSize(2);
                });
    }

    @Test
    void leavesDecoratorCompositionToSpringBoot4() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(ObservabilityAutoConfiguration.TASK_DECORATOR_BEAN,
                new RootBeanDefinition(TraceContextTaskDecorator.class));
        beanFactory.registerBeanDefinition("applicationDecorator",
                new RootBeanDefinition(TaskDecorator.class, () -> task -> task));

        ObservabilityAutoConfiguration.yieldTaskDecoratorToApplication(beanFactory, "4.1.1");
        assertThat(((AbstractBeanDefinition) beanFactory.getBeanDefinition(
                ObservabilityAutoConfiguration.TASK_DECORATOR_BEAN)).isDefaultCandidate()).isTrue();

        ObservabilityAutoConfiguration.yieldTaskDecoratorToApplication(beanFactory, "3.5.16");
        assertThat(((AbstractBeanDefinition) beanFactory.getBeanDefinition(
                ObservabilityAutoConfiguration.TASK_DECORATOR_BEAN)).isDefaultCandidate()).isFalse();
    }

    @Test
    void detectsSpringBootVersionsThatComposeDecorators() {
        assertThat(ObservabilityAutoConfiguration.composesTaskDecorators("4.0.0")).isTrue();
        assertThat(ObservabilityAutoConfiguration.composesTaskDecorators("5")).isTrue();
        assertThat(ObservabilityAutoConfiguration.composesTaskDecorators("3.5.16")).isFalse();
        assertThat(ObservabilityAutoConfiguration.composesTaskDecorators(null)).isFalse();
        assertThat(ObservabilityAutoConfiguration.composesTaskDecorators("dev")).isFalse();
    }

    @Test
    void doesNotRegisterTheTaskDecoratorWhenTraceIsDisabled() {
        contextRunner.withPropertyValues("easy.observability.trace.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TaskDecorator.class));
    }

    @Test
    void createsExplicitTraceAspectByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TraceSpanAspect.class);
        });
    }

    @Test
    void disablesTraceAspectWhenTraceIsDisabled() {
        contextRunner.withPropertyValues("easy.observability.trace.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TraceSpanAspect.class);
        });
    }

    @Test
    void masterSwitchDisablesStarterBeans() {
        contextRunner.withPropertyValues("easy.observability.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TraceSpanAspect.class);
        });
    }

    @Test
    void startsWithoutCreatingAMeterRegistryForTheHostApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(io.github.lakernote.observability.config.EasyObservabilityProperties.class);
                    assertThat(context).hasSingleBean(TraceStore.class);
                    assertThat(context).doesNotHaveBean("traceOperationRecorder");
                    assertThat(context).doesNotHaveBean(MeterRegistry.class);
                });
    }

    @Test
    void startsWithoutSpringAopOnTheApplicationClasspath() {
        contextRunner.withClassLoader(new FilteredClassLoader("org.aspectj")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TraceSpanAspect.class);
        });
    }

    @Test
    void doesNotRegisterStarterOwnedMetersForObservations() {
        ObservationRegistry registry = ObservationRegistry.create();
        contextRunner.withBean(ObservationRegistry.class, () -> registry).run(context -> {
            registry.observationConfig().observationHandler(context.getBean(
                    io.github.lakernote.observability.integration.observation.internal.ObservationTraceHandler.class));
            Observation.createNotStarted("spring.data.redis", registry)
                    .contextualName("GET")
                    .lowCardinalityKeyValue("db.operation", "GET")
                    .start().stop();

            assertThat(context.getBean(MeterRegistry.class).getMeters())
                    .noneMatch(meter -> meter.getId().getName().startsWith("easy."));
        });
    }

    @Test
    void doesNotContributeOrConsumeAJsonMapper() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TraceStore.class);
            assertThat(context).doesNotHaveBean(ObjectMapper.class);
        });
    }

    @Test
    void startsWithoutAnyJsonLibraryOnTheClasspath() {
        contextRunner.withClassLoader(new FilteredClassLoader("com.fasterxml.jackson", "tools.jackson"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TraceStore.class);
                });
    }
}
