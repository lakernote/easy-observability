package com.laker.observability.integration.observation;

import io.micrometer.observation.Observation;

/** 从可选框架的 Observation 上下文中提取已传播的 Trace ID。 */
public interface ObservationTraceIdResolver {
    /** 返回上下文携带的 Trace ID；不适用或没有合法 ID 时返回 {@code null}。 */
    String resolve(Observation.Context context);
}
