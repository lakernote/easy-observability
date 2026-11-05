package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.metrics.internal.MetricsArchive;
import io.github.lakernote.observability.metrics.internal.OperationStats;
import io.github.lakernote.observability.metrics.internal.ResourceHistory;
import io.github.lakernote.observability.metrics.internal.ResourceMetricsRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeMetricsAutoConfigurationTest {
    @TempDir
    Path storage;

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class,
                        RuntimeMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("easy.observability.storage.directory=" + storage);
    }

    @Test
    void registersPersistentMetricsInTheStorageDirectory() {
        contextRunner().withPropertyValues("easy.observability.metrics.include=orders",
                "easy.observability.metrics.retention=7d").run(context -> {
            assertThat(context).hasSingleBean(OperationStats.class).hasSingleBean(ResourceHistory.class)
                    .hasSingleBean(ResourceMetricsRegistry.class);
            MetricsArchive archive = context.getBean(MetricsArchive.class);
            assertThat(archive.status().persistent()).isTrue();
            assertThat(archive.status().retentionDays()).isEqualTo(7);
            assertThat(context.getBean(OperationStats.class).snapshot(OperationStats.Window.TODAY).persistent())
                    .isTrue();
            context.getBean(OperationStats.class).record(OperationStats.Category.HTTP, "/a", 1_000_000, false);
        });
        try (var files = Files.list(storage.resolve("metrics"))) {
            assertThat(files.map(file -> file.getFileName().toString()))
                    .anySatisfy(name -> assertThat(name).startsWith("operations-"));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void canBeDisabledAndKeepsCountingSwitchedOffWithoutTrace() {
        contextRunner().withPropertyValues("easy.observability.metrics.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(OperationStats.class).doesNotHaveBean(MetricsArchive.class)
                        .doesNotHaveBean(ResourceMetricsRegistry.class));
        contextRunner().withPropertyValues("easy.observability.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(OperationStats.class));
        contextRunner().withPropertyValues("easy.observability.trace.enabled=false").run(context ->
                assertThat(context.getBean(OperationStats.class).snapshot(OperationStats.Window.HOUR).collecting())
                        .isFalse());
    }

    @Test
    void rejectsInvalidRetentionAndStorageSize() {
        contextRunner().withPropertyValues("easy.observability.metrics.retention=0").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("must be whole days between 1d and 365d"));
        contextRunner().withPropertyValues("easy.observability.metrics.max-storage-size=0").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("max-storage-size must be positive"));
        assertThat(RuntimeMetricsAutoConfiguration.retentionDays(Duration.ofDays(365))).isEqualTo(365);
        assertThatThrownBy(() -> RuntimeMetricsAutoConfiguration.retentionDays(Duration.ofHours(36)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuntimeMetricsAutoConfiguration.retentionDays(Duration.ofDays(366)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
