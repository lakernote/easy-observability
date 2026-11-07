package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.capture.internal.CaptureClientHttpRequestFactory;
import io.github.lakernote.meerkat.capture.internal.CaptureFilter;
import io.github.lakernote.meerkat.capture.internal.CaptureSessions;
import io.github.lakernote.meerkat.capture.internal.CaptureStore;
import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficCaptureAutoConfigurationTest {
    @TempDir
    Path storage;

    private WebApplicationContextRunner contextRunner() {
        return new WebApplicationContextRunner()
                .withPropertyValues("meerkat.storage.directory=" + storage)
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class,
                        RuntimeMetricsAutoConfiguration.class, ObservabilityWebAutoConfiguration.class,
                        TrafficCaptureAutoConfiguration.class));
    }

    @Test
    void isInstalledByDefaultButTheBuiltInPolicyDeniesCapture() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CaptureSessions.class);
            assertThat(context).hasSingleBean(CaptureStore.class);
            assertThat(context.getBean(ObservabilityAccessPolicy.class).canCapture()).isFalse();
        });
    }

    @Test
    void staysOffWhenTracingIsDisabled() {
        contextRunner().withPropertyValues("meerkat.trace.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CaptureSessions.class));
    }

    @Test
    void registersCaptureBeansAndBindsProperties() {
        contextRunner().withPropertyValues("meerkat.capture.max-duration=5m",
                        "meerkat.capture.max-records=3",
                        "meerkat.capture.retention=30m",
                        "meerkat.capture.max-body-size=2KB",
                        "meerkat.capture.max-result-rows=4",
                        "meerkat.capture.max-value-chars=500",
                        "meerkat.capture.max-trace-chars=9000")
                .run(context -> {
                    assertThat(context).hasSingleBean(CaptureSessions.class);
                    assertThat(context).hasSingleBean(CaptureStore.class);
                    assertThat(context.getBean(CaptureSessions.class).maxDurationSeconds()).isEqualTo(300);

                    MeerkatProperties.Capture capture = context.getBean(MeerkatProperties.class).getCapture();
                    assertThat(capture.getMaxRecords()).isEqualTo(3);
                    assertThat(capture.getRetention()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(capture.getMaxBodySize()).isEqualTo(DataSize.ofKilobytes(2));
                    assertThat(capture.getMaxResultRows()).isEqualTo(4);
                    assertThat(capture.getMaxValueChars()).isEqualTo(500);
                    assertThat(capture.getMaxTraceChars()).isEqualTo(9000);

                    FilterRegistrationBean<?> registration = context.getBean("meerkatCaptureFilter",
                            FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(CaptureFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(TrafficCaptureAutoConfiguration.FILTER_ORDER);
                    assertThat(registration.getUrlPatterns()).containsExactly("/*");
                    assertThat(registration.isAsyncSupported()).isTrue();
                });
    }

    @Test
    void lambdaPoliciesDenyCaptureByDefault() {
        ObservabilityAccessPolicy viewOnly = () -> true;

        assertThat(viewOnly.canCapture()).isFalse();
    }

    @Test
    void wrapsTheRequestFactoryOfHttpClientsOnce() {
        ClientHttpRequestInterceptor first = (request, body, execution) -> execution.execute(request, body);
        ClientHttpRequestInterceptor second = (request, body, execution) -> execution.execute(request, body);
        contextRunner()
                .withBean("restTemplate", RestTemplate.class, RestTemplate::new)
                .withBean("interceptedTemplate", RestTemplate.class, () -> {
                    RestTemplate template = new RestTemplate();
                    template.getInterceptors().add(first);
                    template.getInterceptors().add(second);
                    return template;
                })
                .withBean("restClientBuilder", RestClient.Builder.class, RestClient::builder)
                .withBean("restClient", RestClient.class, RestClient::create)
                .withBean("unrelated", String.class, () -> "plain")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RestTemplate template = context.getBean("restTemplate", RestTemplate.class);
                    assertThat(template.getInterceptors()).isEmpty();
                    assertThat(template.getRequestFactory()).isInstanceOf(CaptureClientHttpRequestFactory.class);
                    TrafficCaptureAutoConfiguration.HttpClientCapture.wrap(template);
                    assertThat(((CaptureClientHttpRequestFactory) template.getRequestFactory()).delegate())
                            .isInstanceOf(SimpleClientHttpRequestFactory.class);

                    RestTemplate intercepted = context.getBean("interceptedTemplate", RestTemplate.class);
                    assertThat(intercepted.getInterceptors()).containsExactly(first, second);
                    assertThat(intercepted.getRequestFactory()).isInstanceOf(InterceptingClientHttpRequestFactory.class);
                    intercepted.getInterceptors().clear();
                    assertThat(intercepted.getRequestFactory()).isInstanceOf(CaptureClientHttpRequestFactory.class);

                    RestClient.Builder builder = context.getBean(RestClient.Builder.class);
                    assertThat(TrafficCaptureAutoConfiguration.HttpClientCapture.requestFactory(builder))
                            .isInstanceOf(CaptureClientHttpRequestFactory.class);
                    assertThat(TrafficCaptureAutoConfiguration.HttpClientCapture.wrap(builder)).isFalse();

                    RestClient.Builder clientBuilder = context.getBean(RestClient.class).mutate();
                    ClientHttpRequestFactory clientFactory =
                            TrafficCaptureAutoConfiguration.HttpClientCapture.requestFactory(clientBuilder);
                    assertThat(clientFactory).isInstanceOf(CaptureClientHttpRequestFactory.class);
                    assertThat(((CaptureClientHttpRequestFactory) clientFactory).delegate())
                            .isNotInstanceOf(CaptureClientHttpRequestFactory.class);
                    assertThat(context.getBean("unrelated")).isEqualTo("plain");
                });
    }
}
