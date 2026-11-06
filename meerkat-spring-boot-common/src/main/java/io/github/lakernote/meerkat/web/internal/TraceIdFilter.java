package io.github.lakernote.meerkat.web.internal;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.internal.MdcContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个 Servlet 请求接收（{@code X-Trace-Id} 或 W3C {@code traceparent}）或生成 Trace ID，并写入响应头和 MDC。
 */
public final class TraceIdFilter extends OncePerRequestFilter {
    private final MeerkatProperties properties;

    public TraceIdFilter(MeerkatProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 优先沿用上游的 Trace ID 请求头，其次是 W3C traceparent；无效值会被替换，保证后续链路使用统一格式。
        String headerName = properties.getTrace().getHeaderName();
        String traceId = request.getHeader(headerName);
        if (!TraceIds.isValidTraceId(traceId)) traceId = TraceIds.fromTraceparent(request.getHeader(TraceIds.TRACEPARENT_HEADER));
        if (traceId == null) {
            traceId = TraceIds.newTraceId();
            request.setAttribute(TraceIds.GENERATED_REQUEST_ATTRIBUTE, Boolean.TRUE);
        }
        response.setHeader(headerName, traceId);
        try (MdcContext.Scope ignored = MdcContext.scope()) {
            TraceIds.putTraceId(traceId);
            filterChain.doFilter(request, response);
            // Scope 在正常返回或异常退出时都会恢复请求前的 MDC，避免 Servlet 线程复用造成串号。
        }
    }
}
