# 接入与配置

引入与应用 Spring Boot 主版本匹配的 Starter 后，Easy Observability 会自动注册 Trace 采集、公共 Metrics 历史和嵌入页面。通常不需要额外配置。Starter 不替换应用已有的 `MeterRegistry`、`ObservationRegistry`，也不会把应用自定义的全部 Meter 写入本地历史。

## 自动采集范围

| 场景 | 采集内容 | 说明 |
| --- | --- | --- |
| HTTP 入口 | 路由、状态、耗时、错误、入口线程 CPU 时间和内存分配量 | 慢阈值分类针对入口；CPU 时间和分配量是入口线程累计值，不是进程使用率或当前堆占用，不包含子线程 |
| 定时任务、Kafka Listener | 入口执行耗时和错误 | Kafka 跨生产/消费关联依赖 Spring Kafka Observation |
| JDBC、事务、MyBatis | SQL 模板、事务范围、耗时和错误 | SQL 会清理字面量和注释，不记录绑定参数 |
| Redis | Lettuce Observation 命令 Span；Spring Data Redis 经 Redisson `RedisConnectionFactory` 执行的命令 Span | 直接调用 Redisson `RLock`、`RMap` 等 API 不属于这组连接工厂命令采集 |
| 远程 HTTP | 已接入的客户端调用 Span；支持的客户端可传播 Trace ID | Spring 管理的 `RestClient.Builder` 自动接入；自行创建并绕过 Spring Builder 的客户端需自行配置 |
| 异步线程池 | 任务耗时、异常及活动 Trace 上下文传播 | 配置 `TraceContextTaskDecorator` 后，才会记录该线程池的任务边界；请求已返回后继续运行的后台任务不会延长已完成的入口 Trace |
| Metrics | HTTP 入口/出口、定时任务、Kafka、Redis、JVM 内存与 GC、CPU、线程池等固定公共指标 | 标签维度拆分为独立曲线并设有上限；每个应用实例独立保存本地历史 |

自定义业务边界可使用 `TraceBlock` 或 `@TraceSpan`。组件不会扫描所有业务 Service 自动创建 Span。

## 默认配置

下面列出常用配置；未列出的配置保持 Starter 默认值。所有配置均可省略。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `easy.observability.enabled` | `true` | Starter 总开关 |
| `easy.observability.trace.enabled` | `true` | Trace 采集及上下文传播 |
| `easy.observability.trace.header-name` | `X-Trace-Id` | HTTP 与受支持消息/客户端使用的 Trace ID 名称 |
| `easy.observability.trace.http-slow-threshold-ms` | `500` | HTTP 入口慢阈值 |
| `easy.observability.trace.job-slow-threshold-ms` | `1000` | 定时任务入口慢阈值 |
| `easy.observability.trace.kafka-consumer-slow-threshold-ms` | `1000` | Kafka 消费入口慢阈值 |
| `easy.observability.trace.cpu-threshold-ms` | `500` | 入口线程 CPU 时间资源告警阈值；设为 `0` 或负数可关闭此类触发 |
| `easy.observability.trace.allocated-memory-threshold` | `50MB` | 入口线程累计内存分配量资源告警阈值；设为 `0B` 可关闭此类触发 |
| `easy.observability.trace.diagnostic-trace-file` | `./data/easy-observability/traces.jsonl` | 诊断 Trace 归档文件 |
| `easy.observability.trace.diagnostic-history-limit` | `1000` | 归档索引的最大记录数 |
| `easy.observability.trace.diagnostic-max-bytes` | `8MB` | 诊断归档大小上限 |
| `easy.observability.metrics.enabled` | `true` | 公共 Metrics 与本地历史总开关 |
| `easy.observability.metrics.step` | `60s` | 指标采样周期，范围 10 秒至 1 小时 |
| `easy.observability.metrics.storage-directory` | `./data/easy-observability/metrics` | RRD 历史目录 |
| `easy.observability.metrics.retention-days` | `30` | 历史保留天数，范围 7 至 365 天 |
| `easy.observability.metrics.max-storage-bytes` | `64MB` | RRD 历史存储上限 |
| `easy.observability.web.enabled` | `true` | 页面和查询 API 开关 |
| `easy.observability.web.allow-anonymous` | `false` | 默认 API 访问策略是否允许匿名请求 |
| `easy.observability.web.trace-limit` | `500` | 最近 Trace 的内存窗口容量 |

示例：

```yaml
easy:
  observability:
    metrics:
      step: 60s
      retention-days: 30
      max-storage-bytes: 64MB
    trace:
      http-slow-threshold-ms: 500
      job-slow-threshold-ms: 1000
      kafka-consumer-slow-threshold-ms: 1000
      cpu-threshold-ms: 500
      allocated-memory-threshold: 50MB
```

RRD 历史使用独立的 Micrometer `StepMeterRegistry`。采样周期或保留期变化会重建固定大小的 RRD 文件并清除旧的本地指标历史。多实例部署时，每个实例应使用独立目录；该历史不会自动跨实例汇总。采样与长周期归档的统计口径会显示在 Metrics 页面说明中。

## 页面和 API 访问

页面路径为 `/easy-observability/`，Metrics 页面为 `/easy-observability/metrics/`，查询 API 位于 `/api/easy-observability/`。`easy.observability.web.allow-anonymous` 不是认证开关：组件不提供用户名、密码或登录系统。默认值 `false` 会让内置 API 访问策略拒绝匿名请求；设为 `true` 会允许匿名访问 API，但仍受应用已有的 Spring Security、网关和网络策略约束。

生产环境建议用应用现有的身份认证保护 `/easy-observability/**` 和 `/api/easy-observability/**`。若应用已使用 Spring Security，可定义 `ObservabilityAccessPolicy` Bean，根据当前认证用户和角色授权 API；页面静态资源需要由应用自己的 Security Filter Chain 或网关一并保护。

## Kafka 与线程池

Spring Kafka 的 Listener/Template Observation 需要启用，才能记录相应入口/发送指标和 Trace Span。默认 Listener Factory 会自动接入 Trace ID 拦截器；应用使用自定义 `RecordInterceptor` 或 Listener Factory 时，需要按应用现有配置组合/注册 `TraceIdRecordInterceptor`。自定义消费者、Kafka Streams 不会被默认 Listener 集成自动覆盖。

Spring 管理的线程池可设置 Starter 提供的 `TraceContextTaskDecorator`，例如：

```java
@Bean("billingExecutor")
ThreadPoolTaskExecutor billingExecutor(TraceContextTaskDecorator decorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setTaskDecorator(decorator);
    return executor;
}
```

装饰器在线程池执行任务时传播活动 Trace 上下文并记录任务边界；无父 Trace 时不会创建 Trace。若 HTTP 请求先结束，Trace 时间线会在入口结束处停止，仍在运行的子任务标记为未完成，之后发生的调用不再补写到该 Trace。
