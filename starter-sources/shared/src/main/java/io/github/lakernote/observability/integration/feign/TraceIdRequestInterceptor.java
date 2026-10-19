package io.github.lakernote.observability.integration.feign;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.TraceIdContext;
import feign.RequestInterceptor;

/** Adds the current trace ID to outgoing Feign requests. */
public final class TraceIdRequestInterceptor implements RequestInterceptor {
    private final String headerName;

    public TraceIdRequestInterceptor(EasyObservabilityProperties properties) {
        this.headerName = properties.getTrace().getHeaderName();
    }

    @Override
    public void apply(feign.RequestTemplate template) {
        template.header(headerName, TraceIdContext.getOrCreateTraceId());
    }
}
