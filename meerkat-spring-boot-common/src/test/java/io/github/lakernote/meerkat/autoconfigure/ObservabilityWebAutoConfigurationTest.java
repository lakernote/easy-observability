package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import io.github.lakernote.meerkat.web.internal.MetricsController;
import io.github.lakernote.meerkat.web.internal.ToolsController;
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
                .withPropertyValues("meerkat.storage.directory=" + storage)
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class,
                        RuntimeMetricsAutoConfiguration.class, ObservabilityWebAutoConfiguration.class));
    }

    @Test
    void allowsViewingAndDeniesClearingByDefault() {
        contextRunner().run(context -> {
            ObservabilityAccessPolicy policy = context.getBean(ObservabilityAccessPolicy.class);
            assertThat(policy.canView()).isTrue();
            assertThat(policy.canClear()).isFalse();
            assertThat(policy.canOperate()).isFalse();
            assertThat(context).hasSingleBean(ToolsController.class);
        });
    }

    @Test
    void backsOffWhenTheApplicationDefinesAPolicy() {
        ObservabilityAccessPolicy policy = () -> false;
        contextRunner().withBean(ObservabilityAccessPolicy.class, () -> policy).run(context ->
                assertThat(context.getBean(ObservabilityAccessPolicy.class)).isSameAs(policy));
    }

    @Test
    void servesMetricsOnlyWhenMetricsAreEnabled() {
        contextRunner().run(context -> {
            assertThat(context).hasSingleBean(OperationStats.class);
            assertThat(context).hasSingleBean(MetricsController.class);
            assertThat(context.getBean(MetricsController.class).operations("1h").collecting()).isTrue();
        });
        contextRunner().withPropertyValues("meerkat.metrics.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OperationStats.class);
            assertThatThrownBy(() -> context.getBean(MetricsController.class).operations("1h"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        });
        contextRunner().withPropertyValues("meerkat.trace.enabled=false").run(context ->
                assertThat(context.getBean(MetricsController.class).operations("1h").collecting()).isFalse());
        contextRunner().withPropertyValues("meerkat.metrics.max-operations=7").run(context ->
                assertThat(context.getBean(OperationStats.class).snapshot(OperationStats.Window.TODAY).maxKeys())
                        .isEqualTo(7));
    }

    @Test
    void deniesClearingForLambdaPoliciesThatOnlyDefineViewing() {
        ObservabilityAccessPolicy viewOnly = () -> true;

        assertThat(viewOnly.canClear()).isFalse();
        assertThat(viewOnly.canOperate()).isFalse();
    }
}
