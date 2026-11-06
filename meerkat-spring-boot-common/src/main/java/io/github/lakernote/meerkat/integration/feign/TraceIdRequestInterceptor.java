package io.github.lakernote.meerkat.integration.feign;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import feign.RequestInterceptor;

/** Adds the current trace ID to outgoing Feign requests. */
public final class TraceIdRequestInterceptor implements RequestInterceptor {
    private final String headerName;

    public TraceIdRequestInterceptor(MeerkatProperties properties) {
        this.headerName = properties.getTrace().getHeaderName();
    }

    @Override
    public void apply(feign.RequestTemplate template) {
        String traceId = TraceIdContext.currentTraceId();
        if (TraceIds.isValidTraceId(traceId)) {
            template.removeHeader(headerName);
            template.header(headerName, traceId);
        }
    }
}
