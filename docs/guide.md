# 接入与配置

引入与应用 Spring Boot 主版本匹配的 Starter 后，Easy Observability 会自动注册 Trace 采集、运行指标和嵌入页面。通常不需要额外配置。Starter 依赖 `spring-boot-starter-actuator`，资源指标直接复用 Spring Boot 已绑定的 Micrometer 指标；它只额外加入一个只接收固定指标的注册表，不替换应用已有的 `MeterRegistry`、`ObservationRegistry`，也不影响应用原有的指标导出。

## 运行环境

- Java 17 及以上；Spring Boot 3 与 Spring Boot 4 分别使用对应的 Starter。
- Web 应用须为 Servlet 栈（Spring MVC）。Trace 上下文绑定在线程上，与"一个请求由一个线程处理"的 Servlet 模型匹配。
- **不支持 WebFlux 等响应式应用。** 引入 Starter 后应用仍可正常启动，但响应式 HTTP 请求不会生成入口 Trace，页面和查询 API 也不会注册；在响应式链路中切换线程的调用无法关联到同一 Trace。
- 非 Web 应用（如只消费 Kafka 或只运行定时任务）可以采集 Trace 和运行指标，但没有页面可以查看。
- 暂不支持 GraalVM Native Image；组件未提供 AOT 运行时提示，也未在 Native Image 下验证。

## 自动采集范围

| 场景 | 采集内容 | 说明 |
| --- | --- | --- |
| HTTP 入口 | 路由、状态、耗时、错误、入口线程 CPU 时间和内存分配量 | 慢阈值分类针对入口；CPU 时间和分配量是入口线程累计值，不是进程使用率或当前堆占用，不包含子线程 |
| 定时任务、Kafka Listener | 入口执行耗时和错误 | Kafka 跨生产/消费关联依赖 Spring Kafka Observation |
| JDBC、事务、MyBatis | SQL 模板、事务范围、耗时和错误 | SQL 会清理字面量和注释，不记录绑定参数 |
| Redis | Lettuce Observation 命令 Span；Spring Data Redis 经 Redisson `RedisConnectionFactory` 执行的命令 Span | 直接调用 Redisson `RLock`、`RMap` 等 API 不属于这组连接工厂命令采集 |
| 远程 HTTP | 已接入的客户端调用 Span；支持的客户端可传播 Trace ID | 通过 Spring 注入的 `RestClient.Builder`、`RestTemplateBuilder` 创建的客户端自动接入，OpenFeign 自动传播 Trace ID；直接 `new RestTemplate()`、JDK `HttpClient` 等绕过 Spring Builder 的调用不会被采集 |
| 异步线程池 | 任务耗时、异常及活动 Trace 上下文传播 | Spring Boot 默认线程池自动接入；自定义线程池设置 `TraceContextTaskDecorator` 后才会记录任务边界；请求已返回后继续运行的后台任务不会延长已完成的入口 Trace |
| 调用统计 | 每个 HTTP 接口、SQL 模板、远程调用、Redis 命令、定时任务和 Kafka Topic 的次数、错误、平均/P95/最大耗时和总耗时，及其随时间的趋势；HTTP、定时任务和 Kafka 消费入口另有平均 CPU 时间和平均内存分配量 | 不论是否慢或出错都会计入；与 Trace 共用采集点，见[运行指标](#运行指标) |
| 资源指标 | JVM 内存与 GC、CPU、线程、Hikari 连接池、线程池、Tomcat 等 Spring Boot 已绑定的指标，以及 `metrics.include` 指定的自定义指标 | 每分钟采样一次，见[运行指标](#运行指标) |

自定义业务边界可使用 `TraceBlock` 或 `@TraceSpan`。组件不会扫描所有业务 Service 自动创建 Span。

JDBC 采集会使用保留原数据源具体类型的代理，因此按 `HikariDataSource` 等具体类型注入及厂商扩展方法仍可使用。若数据源类型为 `final`、包含无法安全代理的公开 `final` 方法，或运行环境拒绝创建类代理，组件会记录警告并跳过该数据源的 JDBC Trace，不会用不兼容的包装对象替换业务 Bean。

## 默认配置

下面列出常用配置；未列出的配置保持 Starter 默认值。所有配置均可省略。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `easy.observability.enabled` | `true` | Starter 总开关 |
| `easy.observability.storage.directory` | `./data/easy-observability/{spring.application.name}` | 本地存储目录，存放诊断 Trace 分段文件 `traces/` 和运行指标历史 `metrics/`；相对路径基于工作目录，未设置应用名时使用 `application` |
| `easy.observability.trace.enabled` | `true` | Trace 采集及上下文传播 |
| `easy.observability.trace.header-name` | `X-Trace-Id` | HTTP 与受支持消息/客户端使用的 Trace ID 名称 |
| `easy.observability.trace.http-slow-threshold` | `500ms` | HTTP 入口慢阈值 |
| `easy.observability.trace.job-slow-threshold` | `1s` | 定时任务入口慢阈值 |
| `easy.observability.trace.kafka-consumer-slow-threshold` | `1s` | Kafka 消费入口慢阈值 |
| `easy.observability.trace.cpu-threshold` | `500ms` | 入口线程 CPU 时间资源告警阈值 |
| `easy.observability.trace.allocated-memory-threshold` | `50MB` | 入口线程累计内存分配量资源告警阈值 |
| `easy.observability.trace.retention` | `7d` | 诊断 Trace 保留天数，整天，范围 `1d`～`365d` |
| `easy.observability.trace.max-storage-size` | `128MB` | 诊断 Trace 磁盘占用上限，超出时删除最旧的分段文件 |
| `easy.observability.metrics.enabled` | `true` | 运行指标（调用统计与资源指标）总开关 |
| `easy.observability.metrics.max-operations` | `1000` | 所有类别合计最多统计的操作数，超出后新操作按类别并入“其他”行 |
| `easy.observability.metrics.retention` | `30d` | 指标历史保留时长，须为整天数，范围 `1d` 至 `365d` |
| `easy.observability.metrics.max-storage-size` | `64MB` | 指标历史文件的磁盘上限，超出后删除最早的日期 |
| `easy.observability.metrics.include` | 空 | 额外记录的自定义 Micrometer 指标名或名称前缀，例如 `orders` 匹配 `orders` 和 `orders.*` |
| `easy.observability.web.enabled` | `true` | 页面和查询 API 开关 |
| `easy.observability.web.trace-limit` | `500` | 内存中保留完整调用树的最新 Trace 数量；更早的记录在打开详情时从磁盘读取 |

示例：

```yaml
easy:
  observability:
    metrics:
      retention: 30d
      max-storage-size: 64MB
      include: orders, payment.
    trace:
      http-slow-threshold: 500ms
      job-slow-threshold: 1s
      kafka-consumer-slow-threshold: 1s
      cpu-threshold: 500ms
      allocated-memory-threshold: 50MB
```

时长配置使用 Spring Boot 的 `Duration` 格式（如 `500ms`、`2s`、`30d`）；阈值写纯数字时按毫秒解析，`retention` 写纯数字时按天解析。四个时长阈值设为 `0` 或负数、`allocated-memory-threshold` 设为 `0B`，可关闭对应的慢操作或资源告警触发。

存储目录由一个实例独占：启动时会在目录中创建 `.lock` 并持有操作系统文件锁，应用关闭时释放。同一台机器上第二个指向同一目录的实例（包括同一应用的多个副本）不会写入该目录，而是记录警告，并把诊断 Trace 和指标历史只保存在内存中，宿主应用照常启动。目录无法创建或不可写时同样降级为仅内存。同机部署多个副本时，请为每个实例配置不同的 `storage.directory`，例如在路径中加入实例编号。

只有慢调用、错误及 CPU 时间或内存分配量超阈值的入口（HTTP、定时任务、Kafka 消费）会被保存。诊断 Trace 以 JSON Lines 追加写入 `traces/traces-yyyyMMdd-N.jsonl`，按天滚动，单个分段达到 `max-storage-size` 的 1/8（256KB～8MB）时也会滚动。超过 `retention` 的整天分段、以及总占用超过 `max-storage-size` 时最旧的分段会被整文件删除，不改写正在使用的文件。重启后在后台线程扫描分段重建轻量索引（最多 5 万条，不含调用树），重建期间页面可用，旧记录逐步出现；打开详情时再从磁盘读取完整记录。单条记录编码后超过一个分段大小时只保留在内存中。

后台序列化和文件 I/O 不持有业务记录入口的锁，写入队列满时丢弃该条持久化而不阻塞请求、Kafka 消费或任务线程。若运行时存储 I/O 失败，Trace 存储会降级到仅内存模式直到应用重启，`/api/easy-observability/traces/storage` 的 `persistent` 会返回 `false`。

Trace 查询 API（均为 `GET`，前缀 `/api/easy-observability`）：

- `/traces?kind=all|slow|error|resource&source=HTTP|JOB|KAFKA_CONSUMER&q=&before=&limit=50`：按完成时间倒序分页，返回 `items`（不含调用树）、`nextCursor`（作为下一页的 `before`）、`total` 和 `scanLimited`。`q` 匹配入口名称、Trace ID、错误信息、线程名以及调用树中的 SQL、远程地址等节点；文本搜索需要扫描磁盘，每次请求最多读取 32MB，达到上限时 `scanLimited` 为 `true`，用 `nextCursor` 继续搜索，此时 `total` 为 `-1`。`limit` 最大 200。
- `/traces/{traceId}?source=`：完整调用树，不存在时返回 404。
- `/traces/storage`：`persistent`、`storageBytes`、`maxStorageBytes`、`retentionDays`、`traceCount`、`indexing`。

`retention`、`max-storage-size` 等无效配置会阻止应用启动，以便尽早暴露配置错误。运行时因目录权限、只读文件系统、磁盘 I/O 导致指标历史写入失败时，组件会记录警告并改为只保存在内存，不会中断宿主应用；页面会显示“存储降级”。修复存储问题后需要重启应用以恢复历史保存。

## 运行指标

运行指标回答“这个实例整体怎么样、哪个操作最慢最多”，Trace 诊断回答“这一次请求慢在哪里”。两者共用采集点，但各自独立：指标覆盖全部调用，Trace 只保留慢、错或资源超阈值的请求。运行指标有两个页面：

- **调用排行**（`/easy-observability/stats/`）：按类别列出每个操作的调用次数、错误数和错误率、平均耗时、P95、最大耗时、总耗时及其在本类别中的占比，点击列头排序；点击一行展开该操作的调用次数和耗时趋势。HTTP、定时任务和 Kafka 消费入口还显示每次调用平均消耗的入口线程 CPU 时间和内存分配量，用来找出“耗 CPU 的接口”和“分配大量对象、引起频繁 GC 的接口”；这两个值复用入口本来就采集的数据，不增加开销，转为异步处理的请求不计入。适合回答“哪个接口最耗时”“哪条 SQL 调用最多”“哪个远程服务最常出错”“哪个接口最吃 CPU 或内存”。
- **指标趋势**（`/easy-observability/metrics/`）：上半部分是各类调用的次数、错误和耗时趋势，下半部分是 JVM、CPU、线程、连接池、线程池等资源曲线。

### 调用统计

| 类别 | 统计名称 | 计为错误 |
| --- | --- | --- |
| HTTP 接口 | 路由模板 · 方法，例如 `/orders/{id} · GET` | 抛出异常或状态码 ≥ 500 |
| SQL | 去除字面量后的 SQL；`IN (?, ?, ...)` 合并为 `IN (?…)`，多行 `VALUES` 合并为首行 | JDBC 抛出异常 |
| 远程调用 | 方法 + 目标主机 + URI 模板 | 抛出异常或状态码 ≥ 500 |
| Redis | 命令名 | 抛出异常或命令返回错误 |
| 定时任务 / Kafka 消费 / Kafka 发送 | 任务名 / Topic · Listener ID / 目标 Topic | 抛出异常 |

- 排行时间范围可选“最近 1 小时”“今天”“最近 7 天”和“最近 30 天”，日期按 JVM 默认时区划分。趋势的分辨率：最近 1 小时每分钟一个点，24 小时每 10 分钟，7 天和 30 天每小时。
- P95 由对数分桶直方图估算，误差约 ±20%，且不会超过最大值；分钟级趋势不提供 P95。
- SQL 统计不依赖活动 Trace，启动阶段和后台线程执行的 SQL 也会计入；Spring 的 `spring.jdbc` Observation 不重复计数。关闭 `trace.enabled` 后除 SQL 外不再统计，页面会提示。
- 统计只用 Micrometer 定义的低基数字段和 SQL 模板命名，Kafka offset、原始 URL 等每次不同的值不会产生新行。操作数达到 `metrics.max-operations` 后，新操作并入该类别的“（其他：超出统计数量上限）”行。
- 每次调用的统计开销约 50 ns，无锁实现；内存中保留最近 2 小时，每个操作约 10 KB。

### 资源指标

资源指标来自 Spring Boot 已经绑定的 Micrometer 指标，组件不自己读取 JMX，也不重复埋点。Starter 注册一个只接收下列指标的注册表，每分钟采样一次：

| 指标 | 曲线拆分 |
| --- | --- |
| `jvm.memory.used/committed/max` | 按 `heap` / `nonheap` |
| `jvm.gc.pause`（每分钟次数和总耗时）、`jvm.gc.memory.allocated`、`jvm.threads.*`、`jvm.classes.loaded` | 合计 |
| `process.cpu.usage`、`system.cpu.usage`、`system.load.average.1m`、`process.files.open/max` | 合计 |
| `hikaricp.connections.active/idle/pending/max/timeout/acquire` | 按连接池 |
| `executor.active/queued/pool.size/pool.max/completed` | 按线程池 |
| `tomcat.threads.busy/current/config.max`、`tomcat.sessions.active.current`、`jetty.threads.*` | 合计 |

- `metrics.include` 中的自定义指标按名称合计成一条曲线；计数器和计时器显示每分钟的增量。每个指标最多按 16 个连接池或线程池拆分，总曲线数最多 256 条。
- 没有上限的线程池和资源（上限为 `-1` 或 `Integer.MAX_VALUE`）不绘制上限曲线。
- Spring Boot 只有在 Tomcat 注册 MBean 时才绑定 Tomcat 线程指标，因此 Starter 默认把 `server.tomcat.mbeanregistry.enabled` 设为 `true`（优先级最低，应用显式配置时以应用为准；关闭 `metrics.enabled` 时不设置）。开启后 Tomcat 会多占用少量内存。

### 存储

调用统计每 10 分钟一片、资源指标每分钟一个样本，追加写入存储目录下的 `metrics/operations-yyyyMMdd.tsv` 和 `metrics/resources-yyyyMMdd.tsv`。应用正常关闭时会写入当前未满的分片，重启后“今天”和更长范围仍然保留；进程被强制杀死时最多丢失最近约 10 分钟的调用统计。

- 两天前的文件会压缩一次：调用统计合并为每小时一行，资源指标合并为每 10 分钟的平均值，因此 30 天的数据通常只有几 MB。
- 超过 `metrics.retention` 的日期会被删除；总大小超过 `metrics.max-storage-size` 时按天删除最早的数据。
- 存储目录不可用（被其他实例占用或不可写）时只保存在内存，长范围只包含最近 2 小时，页面会提示。
- 每个实例各自保存，不跨实例汇总。`canClear()` 允许时调用排行页显示“重置统计”，会清空内存中和已保存的调用统计，资源指标历史保留。

查询 API（均为 `GET`，前缀 `/api/easy-observability/metrics`）：`/operations?window=1h|today|7d|30d`、`/operations/trend?category=HTTP&name=...&period=1h|24h|7d|30d`、`/operations/trends?period=...`、`/resources?period=...`、`/storage`；重置为 `POST /operations/reset`。

## 实时诊断

页面 `/easy-observability/live/` 回答“现在正在发生什么”，适合接口卡住、CPU 突然升高、线程池被占满时排查。数据在打开页面或点击刷新时读取，不在后台采样，也不写入磁盘。

- **进行中请求：** 列出正在执行的 HTTP、定时任务和 Kafka 消费入口，按已执行时间排序，显示入口线程已消耗的 CPU 时间、当前正在执行的最内层步骤（例如某条 SQL 或远程调用及其已执行时间）和线程状态；展开一行查看该线程此刻的堆栈和正在等待的锁。默认每 5 秒刷新。请求转为异步处理、入口线程已返回后，只显示耗时和当前步骤，不显示线程堆栈。最多显示 200 个，诊断页面自身的请求不计入。
- **线程：** 对全部平台线程采样 1 秒，按 CPU 使用率（100% 表示占满一个核心）排序，显示状态、累计 CPU、等待的锁及持有者，展开查看堆栈；检测到死锁时在顶部列出相关线程。勾选“合并相同堆栈”可以把状态和堆栈相同的线程合为一行，便于发现大量线程卡在同一处。可下载 jstack 格式的线程 Dump 文本。虚拟线程不在列表中。

每个入口只在开始和结束时各做一次并发集合的增删，不影响请求耗时。线程采样同一时间只允许一个，正在采样时再次请求返回 429。

查询 API（均为 `GET`，前缀 `/api/easy-observability/live`）：`/requests`、`/threads?sampleMs=0..5000`（默认 1000）、`/threads/dump`（`text/plain` 附件）。

## 页面和 API 访问

页面路径为 `/easy-observability/`，调用排行页面为 `/easy-observability/stats/`，指标趋势页面为 `/easy-observability/metrics/`，实时诊断页面为 `/easy-observability/live/`，查询 API 位于 `/api/easy-observability/`。组件不提供用户名、密码或登录系统，认证由应用现有的 Spring Security、Servlet 容器或网关负责。

API 授权由 `ObservabilityAccessPolicy` 决定，应用定义该类型的 Bean 即可替换默认实现：

| 方法 | 控制范围 | 默认 |
| --- | --- | --- |
| `canView()` | 查询 Trace、Metrics、存储状态和当前权限 | `true`，允许所有请求 |
| `canClear()` | 清空 Trace 历史（`POST /api/easy-observability/storage/clear`）和重置调用统计（`POST /api/easy-observability/metrics/operations/reset`），并且需要同时通过 `canView()` | `false`，拒绝所有请求；页面隐藏“清空历史”和“重置统计”按钮 |

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

## 日志中的 Trace ID

当前 Trace ID 写在 SLF4J MDC 的 `easyTraceId` 键中，可在日志格式中用 `%X{easyTraceId}` 输出。Spring Boot 可直接配置：

```yaml
logging:
  pattern:
    correlation: "[%X{easyTraceId:-}] "
```

该键与 Micrometer Tracing 使用的 `traceId`、`spanId` 不同。应用同时接入 Micrometer Tracing（OpenTelemetry 或 Brave）时，两套 ID 并存、互不覆盖，可在格式中同时输出 `%X{traceId}` 与 `%X{easyTraceId}`。代码中读取当前 ID 请使用 `TraceIdContext.currentTraceId()`。

## Kafka 与线程池

Spring Kafka 的 Listener/Template Observation 需要启用，才能记录相应入口/发送指标和 Trace Span。默认 Listener Factory 会自动接入 Trace ID 拦截器；应用使用自定义 `RecordInterceptor` 或 Listener Factory 时，需要按应用现有配置组合/注册 `TraceIdRecordInterceptor`。自定义消费者、Kafka Streams 不会被默认 Listener 集成自动覆盖。

Spring Boot 自动配置的默认线程池（`applicationTaskExecutor`，`@Async` 默认使用）和调度器会自动应用 Starter 注册的 `TraceContextTaskDecorator`。应用自己定义的线程池需要显式设置，直接创建实例即可，不依赖 Starter 是否启用：

```java
@Bean("billingExecutor")
ThreadPoolTaskExecutor billingExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setTaskDecorator(new TraceContextTaskDecorator());
    return executor;
}
```

应用已定义自己的 `TaskDecorator` Bean 时：Spring Boot 4 会把它与 Starter 的装饰器组合后应用到默认线程池；Spring Boot 3 只在装饰器唯一时才应用，为了不让宿主的装饰器失效，Starter 的装饰器会让出默认线程池，启动日志会给出提示。此时如需传播 Trace，请自行组合：

```java
executor.setTaskDecorator(new CompositeTaskDecorator(List.of(applicationDecorator, new TraceContextTaskDecorator())));
```

装饰器在线程池执行任务时传播活动 Trace 上下文并记录任务边界；无父 Trace 时不会创建 Trace。若 HTTP 请求先结束，Trace 时间线会在入口结束处停止，仍在运行的子任务标记为未完成，之后发生的调用不再补写到该 Trace。

## 性能开销

以下数字来自 `benchmarks` 模块中的 JMH 基准（Apple M4 Pro，JDK 21，单线程，平均耗时）。只代表量级，不同硬件和 JVM 会有差异，可按 [benchmarks/README.md](../benchmarks/README.md) 在自己的环境中复现。

| 场景 | 未接入 | 接入后 | 说明 |
| --- | --- | --- | --- |
| 一次未超阈值的入口请求，无依赖调用 | 0.06 µs | 1.8 µs | 主要是入口线程 CPU 时间与内存分配量的采样，以及登记到进行中请求列表 |
| 同上，含 5 次依赖调用 | 0.16 µs | 3.1 µs | 每次依赖调用（JDBC、Redis 等 Observation）约 0.3 µs |
| 同上，含 20 次依赖调用 | 0.44 µs | 6.9 µs | |
| 同上，含 20 次依赖调用并启用调用统计（默认） | 0.44 µs | 7.9 µs | 调用统计每次调用约 50 ns；`metrics.enabled=false` 可关闭 |
| `TraceBlock`，不在 Trace 中 | — | 18 ns | 直接执行业务代码，不记录 |
| 一个入口 Trace 内 10 个 `TraceBlock` | — | 1.0 µs | 每个 Span 约 0.1 µs |
| `TraceContextTaskDecorator` 装饰并执行一个任务 | — | 0.27 µs（无 Trace）/ 0.37 µs（Trace 内） | 无父 Trace 时会为任务生成新的 Trace ID |

对于耗时在毫秒级的典型 HTTP 请求，这部分开销通常低于 1%。慢请求、错误和资源超阈值的入口还会被写入内存列表并在后台线程异步归档，归档 I/O 不在业务线程上执行。

## 公共 API

以下类型是公开 API，正式发布后保持兼容：

| 类型 | 用途 |
|---|---|
| `trace.TraceSpan`、`trace.TraceDisabled` | 声明或排除方法级 Span |
| `trace.TraceBlock`、`trace.SpanKind` | 在代码中包裹自定义 Span |
| `trace.TraceIdContext` | 读取当前 Trace ID（`currentTraceId()`、`MDC_KEY`） |
| `trace.TraceContextTaskDecorator` | 线程池传播 Trace 上下文 |
| `web.ObservabilityAccessPolicy` | 页面和 API 授权扩展点 |
| `integration.observation.ObservationTraceIdResolver` | 从其他框架的 Observation 上下文提取已传播的 Trace ID，定义该类型的 Bean 即可生效 |
| `integration.feign.TraceIdRequestInterceptor`、`integration.kafka.TraceIdRecordInterceptor` | 手动组装 Feign 客户端或 Kafka Listener 时注册 |
| `config.EasyObservabilityProperties` | `easy.observability.*` 配置项 |

包名均以 `io.github.lakernote.observability` 开头。自动配置类（`autoconfigure` 包）只用于按名称排除。

包路径中含 `internal` 的类型是内部实现，例如 Trace 存储、指标存储、Controller 和 Filter。它们即使是 `public`，也可能在任何版本中调整，请不要直接依赖。这些内部组件对应的 Bean 也不支持用同类型 Bean 替换；需要定制时请使用上表中的扩展点或配置项。
