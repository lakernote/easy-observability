package io.github.lakernote.observability.integration.feign;

import io.github.lakernote.observability.trace.internal.TraceIds;
import feign.RequestTemplate;
import io.github.lakernote.observability.config.EasyObservabilityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdRequestInterceptorTest {
    @AfterEach
    void clearTraceId() {
        TraceIds.clear();
    }

    @Test
    void onlyPropagatesAnExistingTraceId() {
        TraceIdRequestInterceptor interceptor = new TraceIdRequestInterceptor(new EasyObservabilityProperties());
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey("X-Trace-Id");

        TraceIds.putTraceId("trace-123");
        interceptor.apply(template);

        assertThat(template.headers().get("X-Trace-Id")).containsExactly("trace-123");
    }
}
