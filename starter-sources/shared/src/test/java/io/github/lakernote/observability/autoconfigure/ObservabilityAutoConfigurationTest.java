package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.trace.TraceSpanAspect;
import io.github.lakernote.observability.metrics.history.Rrd4jStepMeterRegistry;
import io.github.lakernote.observability.metrics.history.MetricHistoryStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityAutoConfigurationTest {
    @TempDir
    Path tempDirectory;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class,
                    TraceSpanAutoConfiguration.class))
            .withPropertyValues("easy.observability.metrics.history.enabled=false")
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

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
                    assertThat(context.getBean(io.github.lakernote.observability.config.EasyObservabilityProperties.class)
                            .getMetrics().getHistory().getRetentionDays()).isEqualTo(15);
                    assertThat(context).hasSingleBean(io.github.lakernote.observability.trace.store.RecentTraceStore.class);
                    assertThat(context).hasSingleBean(io.github.lakernote.observability.trace.TraceOperationRecorder.class);
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
                    io.github.lakernote.observability.integration.observation.ObservationTraceHandler.class));
            Observation.createNotStarted("spring.data.redis", registry)
                    .contextualName("GET")
                    .lowCardinalityKeyValue("db.operation", "GET")
                    .start().stop();

            assertThat(context.getBean(MeterRegistry.class).getMeters())
                    .noneMatch(meter -> meter.getId().getName().startsWith("easy."));
        });
    }

    @Test
    void registersRrd4jStepBackendAlongsideTheHostMeterRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class,
                        Rrd4jStepMeterRegistryAutoConfiguration.class))
                .withPropertyValues("easy.observability.metrics.history.storage-dir=" + tempDirectory,
                        "easy.observability.metrics.history.enabled=true")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Rrd4jStepMeterRegistry.class);
                    assertThat(context).hasSingleBean(MetricHistoryStore.class);
                    assertThat(context).hasBean("meterRegistry");
                });
    }
}
