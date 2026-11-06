package io.github.lakernote.meerkat.web.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import jakarta.servlet.FilterChain;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {
    private final TraceIdFilter filter = new TraceIdFilter(new MeerkatProperties());

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void acceptsTraceIdAndRestoresMdcAfterRequest() throws Exception {
        MDC.put(TraceIdContext.MDC_KEY, "previous");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader("X-Trace-Id", "client-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(MDC.get(TraceIdContext.MDC_KEY)).isEqualTo("client-trace");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("client-trace");
        assertThat(MDC.get(TraceIdContext.MDC_KEY)).isEqualTo("previous");
    }

    @Test
    void generatesSafeTraceIdWhenHeaderIsMissingOrInvalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader("X-Trace-Id", "bad\r\nheader");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(TraceIdContext.MDC_KEY)).hasSize(32));

        assertThat(response.getHeader("X-Trace-Id")).hasSize(32);
        assertThat(MDC.get(TraceIdContext.MDC_KEY)).isNull();
    }

    @Test
    void acceptsW3cTraceparentWhenTraceIdHeaderIsAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(TraceIdContext.MDC_KEY)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736"));

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(request.getAttribute(TraceIds.GENERATED_REQUEST_ATTRIBUTE)).isNull();
    }

    @Test
    void prefersTraceIdHeaderAndMarksOnlyGeneratedIds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader("X-Trace-Id", "gateway-1");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("gateway-1");
        assertThat(request.getAttribute(TraceIds.GENERATED_REQUEST_ATTRIBUTE)).isNull();

        MockHttpServletRequest bare = new MockHttpServletRequest("GET", "/api/users");
        filter.doFilter(bare, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });
        assertThat(bare.getAttribute(TraceIds.GENERATED_REQUEST_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
    }
}
