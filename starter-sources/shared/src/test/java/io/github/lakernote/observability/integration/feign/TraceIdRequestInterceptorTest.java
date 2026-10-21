package io.github.lakernote.observability.integration.feign;

import feign.RequestTemplate;
import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.TraceIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdRequestInterceptorTest {
    @AfterEach
    void clearTraceId() {
        TraceIdContext.clear();
    }

    @Test
    void onlyPropagatesAnExistingTraceId() {
        TraceIdRequestInterceptor interceptor = new TraceIdRequestInterceptor(new EasyObservabilityProperties());
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey("X-Trace-Id");

        TraceIdContext.putTraceId("trace-123");
        interceptor.apply(template);

        assertThat(template.headers().get("X-Trace-Id")).containsExactly("trace-123");
    }
}
