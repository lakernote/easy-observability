package io.github.lakernote.meerkat.integration.feign;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import feign.RequestTemplate;
import io.github.lakernote.meerkat.config.MeerkatProperties;
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
        TraceIdRequestInterceptor interceptor = new TraceIdRequestInterceptor(new MeerkatProperties());
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey("X-Trace-Id");

        TraceIds.putTraceId("trace-123");
        interceptor.apply(template);

        assertThat(template.headers().get("X-Trace-Id")).containsExactly("trace-123");
    }
}
