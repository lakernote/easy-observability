package io.github.lakernote.observability.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lakernote.observability.storage.internal.StorageDirectory;
import io.github.lakernote.observability.trace.internal.TraceSpanAspect;
import io.github.lakernote.observability.trace.internal.store.DiagnosticTraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

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
            assertThat(context.getBean(DiagnosticTraceStore.class).storageStatus().persistenceEnabled()).isTrue();
        });
    }

    @Test
    void secondContextOnTheSameDirectoryKeepsHistoryInMemory(@TempDir Path storage) {
        contextRunner.withPropertyValues("easy.observability.storage.directory=" + storage).run(first ->
                contextRunner.withPropertyValues("easy.observability.storage.directory=" + storage).run(second -> {
                    assertThat(second).hasNotFailed();
                    assertThat(second.getBean(StorageDirectory.class).available()).isFalse();
                    assertThat(second.getBean(DiagnosticTraceStore.class).storageStatus().persistenceEnabled())
                            .isFalse();
                    assertThat(first.getBean(DiagnosticTraceStore.class).storageStatus().persistenceEnabled())
                            .isTrue();
                }));
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
                    assertThat(context).hasSingleBean(io.github.lakernote.observability.trace.internal.store.RecentTraceStore.class);
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
            assertThat(context).hasSingleBean(DiagnosticTraceStore.class);
            assertThat(context).doesNotHaveBean(ObjectMapper.class);
        });
    }

    @Test
    void startsWithoutAnyJsonLibraryOnTheClasspath() {
        contextRunner.withClassLoader(new FilteredClassLoader("com.fasterxml.jackson", "tools.jackson"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DiagnosticTraceStore.class);
                });
    }
}
