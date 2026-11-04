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

JDBC 采集会使用保留原数据源具体类型的代理，因此按 `HikariDataSource` 等具体类型注入及厂商扩展方法仍可使用。若数据源类型为 `final`、包含无法安全代理的公开 `final` 方法，或运行环境拒绝创建类代理，组件会记录警告并跳过该数据源的 JDBC Trace，不会用不兼容的包装对象替换业务 Bean。

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

诊断 Trace 归档超过 `diagnostic-max-bytes` 后会流式裁剪到约一半容量，只保留最新的有界记录，不会把异常增大的文件整体载入内存。单条记录编码后若超过 `diagnostic-max-bytes` 或 16MB（取较小值），仍保留在内存窗口中，但不会写入本地归档。后台序列化、裁剪和文件 I/O 不持有业务记录入口的状态锁，慢磁盘不会让请求、Kafka 消费或任务线程等待归档 I/O。若运行时存储 I/O 失败，归档会降级到仅内存模式直到应用重启，`/api/easy-observability/traces/storage` 的 `diagnosticPersistenceEnabled` 会返回 `false`。

`step`、`retention-days`、`max-storage-bytes` 等无效配置仍会阻止应用启动，以便尽早暴露配置错误。若已有 RRD 文件超过当前容量上限，或运行时因目录权限、只读文件系统、磁盘 I/O 导致 RRD 历史不可用，组件会记录警告并停用本地历史，但不会中断宿主应用；Metrics 页面会显示“存储降级”。修复存储问题后需要重启应用以恢复历史采集。

## 页面和 API 访问

页面路径为 `/easy-observability/`，Metrics 页面为 `/easy-observability/metrics/`，查询 API 位于 `/api/easy-observability/`。组件不提供用户名、密码或登录系统，认证由应用现有的 Spring Security、Servlet 容器或网关负责。

API 授权由 `ObservabilityAccessPolicy` 决定，应用定义该类型的 Bean 即可替换默认实现：

| 方法 | 控制范围 | 默认 |
| --- | --- | --- |
| `canView()` | 查询 Trace、Metrics、存储状态和当前权限 | `true`，允许所有请求 |
| `canClear()` | 清空 Trace 历史（`POST /api/easy-observability/storage/clear`），并且需要同时通过 `canView()` | `false`，拒绝所有请求；页面隐藏“清空历史”按钮 |

`canClear()` 是默认方法，只需要限制查看时可以直接用 lambda，清空历史仍保持关闭：

```java
@Bean
ObservabilityAccessPolicy observabilityAccessPolicy() {
    return () -> hasAnyRole("ROLE_DEVELOPER", "ROLE_OPS");
}
```

**默认情况下任何能访问应用端口的人都可以查看 Trace 和 Metrics。** 生产环境务必由应用的 Security Filter Chain 或网关保护 `/easy-observability/**` 和 `/api/easy-observability/**`，或自定义 `canView()`。两个方法都在请求线程上调用，可以读取 Spring Security 上下文或当前请求。例如只允许开发和运维角色查看、只允许运维角色清空：

```java
@Bean
ObservabilityAccessPolicy observabilityAccessPolicy() {
    return new ObservabilityAccessPolicy() {
        @Override
        public boolean canView() {
            return hasAnyRole("ROLE_DEVELOPER", "ROLE_OPS");
        }

        @Override
        public boolean canClear() {
            return hasAnyRole("ROLE_OPS");
        }
    };
}

private static boolean hasAnyRole(String... roles) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getAuthorities().stream()
            .anyMatch(authority -> List.of(roles).contains(authority.getAuthority()));
}
```

如果应用启用了 Spring Security 的 CSRF 防护，清空历史的 POST 请求需要 CSRF 令牌：使用 `CookieCsrfTokenRepository` 时页面会自动读取 `XSRF-TOKEN` Cookie 并回传 `X-XSRF-TOKEN` 请求头，否则需要对该路径单独配置 CSRF 策略。

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
