package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
    void deniesClearingForLambdaPoliciesThatOnlyDefineViewing() {
        ObservabilityAccessPolicy viewOnly = () -> true;

        assertThat(viewOnly.canClear()).isFalse();
    }
}
