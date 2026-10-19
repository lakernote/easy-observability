package com.laker.observability.web;

import com.laker.observability.config.EasyObservabilityProperties;
import com.laker.observability.trace.MdcContext;
import com.laker.observability.trace.TraceIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 为每个 Servlet 请求接收或生成 Trace ID，并写入响应头和 MDC。 */
public final class TraceIdFilter extends OncePerRequestFilter {
    private final EasyObservabilityProperties properties;

    public TraceIdFilter(EasyObservabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 校验并规范化外部传入的 ID；无效值会被替换，保证后续链路使用统一格式。
        String headerName = properties.getTrace().getHeaderName();
        String traceId = TraceIdContext.resolve(request.getHeader(headerName));
        response.setHeader(headerName, traceId);
        try (MdcContext.Scope ignored = MdcContext.scope()) {
            TraceIdContext.putTraceId(traceId);
            filterChain.doFilter(request, response);
            // Scope 在正常返回或异常退出时都会恢复请求前的 MDC，避免 Servlet 线程复用造成串号。
        }
    }
}
