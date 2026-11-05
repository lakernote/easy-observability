package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.stats.internal.OperationStats;
import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import io.github.lakernote.observability.web.internal.OperationStatsController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityWebAutoConfigurationTest {
    @TempDir
    Path storage;

    private WebApplicationContextRunner contextRunner() {
        return new WebApplicationContextRunner()
                .withPropertyValues("easy.observability.metrics.enabled=false",
                        "easy.observability.storage.directory=" + storage)
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class,
                        ObservabilityWebAutoConfiguration.class));
    }

    @Test
    void allowsViewingAndDeniesClearingByDefault() {
        contextRunner().run(context -> {
            ObservabilityAccessPolicy policy = context.getBean(ObservabilityAccessPolicy.class);
            assertThat(policy.canView()).isTrue();
            assertThat(policy.canClear()).isFalse();
        });
    }

    @Test
    void backsOffWhenTheApplicationDefinesAPolicy() {
        ObservabilityAccessPolicy policy = () -> false;
        contextRunner().withBean(ObservabilityAccessPolicy.class, () -> policy).run(context ->
                assertThat(context.getBean(ObservabilityAccessPolicy.class)).isSameAs(policy));
    }

    @Test
    void servesStatisticsOnlyWhenStatisticsAreEnabled() {
        contextRunner().run(context -> {
            assertThat(context).hasSingleBean(OperationStats.class);
            assertThat(context).hasSingleBean(OperationStatsController.class);
        });
        contextRunner().withPropertyValues("easy.observability.stats.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OperationStats.class);
            assertThatThrownBy(() -> context.getBean(OperationStatsController.class).stats("hour"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        });
        contextRunner().withPropertyValues("easy.observability.trace.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(OperationStats.class));
        contextRunner().withPropertyValues("easy.observability.stats.max-keys=7").run(context ->
                assertThat(context.getBean(OperationStats.class).snapshot(OperationStats.Window.TODAY).maxKeys())
                        .isEqualTo(7));
    }

    @Test
    void deniesClearingForLambdaPoliciesThatOnlyDefineViewing() {
        ObservabilityAccessPolicy viewOnly = () -> true;

        assertThat(viewOnly.canClear()).isFalse();
    }
}
