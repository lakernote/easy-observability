# Easy Observability

Easy Observability 是面向 Spring Boot 应用的轻量监控项目，基于 Micrometer 提供指标趋势、慢请求与错误诊断、Trace 调用树和线程资源信息。项目包含 Spring Boot 3 / 4 两个独立 Starter，以及可直接运行的示例应用。

Starter 自动引入 Spring Boot Actuator，为 Micrometer 指标和 Observation 提供 Boot 管理的注册表与框架接入。Easy Observability 复用该注册表，不替换现有 Bean，也不设置 `management.*` 配置；Actuator 端点仍遵循 Spring Boot 自己的默认暴露范围。宿主应用如果已经引入 Actuator，Maven 会复用同一套依赖，不会创建第二个注册表。

## 选择 Spring Boot 版本

Spring Boot 3 和 4 使用不同的 Starter artifact，避免在一个构件中混合两代自动配置和模块依赖。两者使用相同的 Easy Observability API 和实现。

Spring Boot 3：

```xml
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>easy-observability-spring-boot-3-starter</artifactId>
    <version>0.1.0-alpha.0</version>
</dependency>
```

Spring Boot 4：

```xml
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>easy-observability-spring-boot-4-starter</artifactId>
    <version>0.1.0-alpha.0</version>
</dependency>
```

Maven 坐标使用 `io.github.lakernote`，Java API 使用 `io.github.lakernote.observability`；两者共享同一发布命名空间，方便用户识别来源。Starter 对外包名会作为兼容性 API 维护。

当前版本为预览版，尚未发布到 Maven Central。Java 17+、Maven 3.6.3+ 可在项目根目录构建全部 Starter 和示例：`mvn verify`。

## 模块与依赖

- 根 easy-observability 是父 POM 和聚合 POM，发布 Starter 时也需要发布它；示例只用于本地体验，不上传 Maven Central。
- 两个 Starter 保留一致的能力边界，Boot 3 / Boot 4 专属源码位于 `starter-sources/spring-boot-3` 和 `starter-sources/spring-boot-4`；共用实现和资源位于 `starter-sources/shared`。这些是源码集合，不是独立 Maven 构件。
- Actuator、Micrometer Core、Micrometer Observation、Jackson 和 RRD4J 是指标与本地历史存储所需的基础依赖。Starter 使用 Boot 的 Actuator 自动配置，但不改动其端点暴露设置，也不会覆盖宿主应用已有的 `MeterRegistry` 或 `ObservationRegistry`。
- Web MVC、Servlet、AOP、Redis/Lettuce、Kafka、事务、MyBatis 和 Feign 依赖均为可选；只有宿主应用已引入相应框架时才启用对应适配，不会把 Tomcat、Kafka 或 Redis 客户端强加给使用者。
- Spring Boot 4 使用 Boot 4 拆分后的 Kafka、Redis 自动配置依赖；Boot 3 继续使用其 spring-boot-autoconfigure 中提供的扩展点。两个 Starter 的依赖版本分别由各自 Boot BOM 管理，共享的独立集成版本在根 POM 集中维护。

## Maven Central 发布准备

发布构件前，先在 Central Portal 验证 `io.github.lakernote` namespace。正式版本不可覆盖；每次发布都要使用新的版本号。根目录的 `central-release` Maven profile 会生成签名并通过 Central Publishing Portal 提交构件；校验通过后会自动发布，两个 Example 模块会自动跳过发布。

GitHub Actions 的 `Publish to Maven Central` 支持两种触发方式：推送与 Maven 版本一致的 `v*` tag，或在 Actions 页面选择该工作流并点击 `Run workflow`。手动运行不需要填写参数，只会从默认分支读取当前 POM 版本；发布前先更新根 POM 与子模块 parent 中的版本，并确保该版本从未发布。校验通过后工作流会自动完成最终发布。

本地先验证发布包（不会上传）：

```bash
mvn -Pcentral-release -DskipTests -Dgpg.skip=true clean verify
```

仓库提供 GitHub Actions 发布工作流：创建名为 `maven-central` 的 GitHub Environment，并在其中配置以下 Secrets：

- `CENTRAL_USERNAME`、`CENTRAL_PASSWORD`：Central Portal 生成的 User Token 凭据。
- `GPG_PRIVATE_KEY`、`GPG_PASSPHRASE`：用于签署构件的 ASCII-armored GPG 私钥（`gpg --armor --export-secret-keys <密钥指纹>` 导出）及其口令。GitHub Actions 使用 Maven GPG Plugin 的 Bouncy Castle 签名器读取密钥，不需要在 Runner 上导入 GPG keyring。

推送与 Maven 项目版本完全匹配的 `v<version>` 标签后，工作流会构建、签名并提交到 Central Portal，校验通过后自动发布。正式版本不可覆盖，第一次发布前应先检查生成的主 JAR、sources JAR、Javadoc JAR、POM 和签名。

如果本地发布，可在 Maven `settings.xml` 配置 `id` 为 `central` 的用户令牌服务器，并配置可用的 GPG 私钥；之后使用新的版本执行 `mvn -Pcentral-release clean deploy`。


## 运行示例

仓库提供 Spring Boot 3 和 Spring Boot 4 两个独立示例，默认仅监听本机端口并允许匿名访问监控 API，适合本地体验，不要直接用于生产环境。

```bash
# Spring Boot 3 示例
mvn -pl examples/easy-observability-example-spring-boot-3 -am package
java -jar examples/easy-observability-example-spring-boot-3/target/easy-observability-example-spring-boot-3-0.1.0-alpha.0.jar

# Spring Boot 4 示例（另开终端）
mvn -pl examples/easy-observability-example-spring-boot-4 -am package
java -jar examples/easy-observability-example-spring-boot-4/target/easy-observability-example-spring-boot-4-0.1.0-alpha.0.jar
```

分别访问 `http://localhost:8083/` 或 `http://localhost:8084/`，可触发正常、慢、高 CPU、高内存分配、错误和嵌套 Trace 请求，再打开页面查看趋势与下钻详情。CPU 示例最多运行 3 秒；内存示例最多分配 32 MiB，并会等待片刻以进入慢请求列表。它们用于本机体验，不建议在生产环境或共享实例上触发。

## 配置

Starter 的配置统一使用 `easy.observability` 前缀：

```yaml
easy:
  observability:
    enabled: true
    metrics:
      enabled: true
      history:
        enabled: true
        storage-dir: ./data/easy-observability/metrics
        retention-days: 15
        max-storage-bytes: 67108864
        max-series: 512
      executor:
        enabled: true
    scheduling:
      enabled: true
    trace:
      enabled: true
      http-server-observation-enabled: true
      redis-observation-enabled: true
      database-observation-enabled: true
      header-name: X-Trace-Id
      slow-threshold-ms: 250
      job-slow-threshold-ms: 1000
      resource-sampling-enabled: true
      thread-contention-sampling-enabled: false
      slow-error-operation-file: ./data/easy-observability/incidents.jsonl
      slow-error-trace-file: ./data/easy-observability/traces.jsonl
      slow-error-history-limit: 10000
      slow-error-max-bytes: 33554432
    web:
      enabled: true
      allow-anonymous: false
      trace-limit: 500
    transaction:
      enabled: true
      slow-threshold-ms: 1000
    redis:
      enabled: true
    kafka:
      enabled: true
    mybatis:
      enabled: true
      slow-threshold-ms: 250
```

当应用存在 Micrometer `MeterRegistry` 且没有自定义 `BusinessMetrics` Bean 时，Starter 会创建 `BusinessMetrics`。开启 Trace 后会注册显式 `@TraceSpan` 切面；它只处理标注了注解的类和方法，不会拦截所有 Service。

框架接入代码位于 `io.github.lakernote.observability.integration`。Starter 使用 Micrometer `ObservationHandler` 把已启用的框架 Observation 接入有界操作历史和 Trace Tree；Spring 事务则通过 `TransactionExecutionListener` 接入真实的 begin、commit、rollback 生命周期，不需要业务方法 AOP。采集结果只包含操作名和低基数上下文，不复制 Redis key、SQL 绑定参数、HTTP 请求体或 Kafka 消息体。JVM 支持时，操作记录还会包含线程 ID、CPU 时间、分配字节数和阻塞/等待计数。

Servlet 应用会获得 `TraceIdFilter`。它独立于监控页面开关运行，接收有效的 `X-Trace-Id`，缺少或无效时生成新 ID，并将 ID 写入响应头；请求结束后恢复此前的 MDC。可通过 `easy.observability.trace.header-name` 修改请求头名称，也可由应用提供自己的 `TraceIdFilter` Bean 替换默认实现。

默认由 `http.server.requests` Observation 创建 HTTP 入口 Trace。若宿主已经提供负责异步生命周期的 HTTP Trace 适配器，可设置 `easy.observability.trace.http-server-observation-enabled=false`，让宿主适配器成为唯一入口；宿主仍可通过 Starter 的 `OperationRecorder` 写入统一指标和 Trace 历史。

Redis 和数据库 Observation 默认开启。若应用同时使用 Starter 提供的 Redisson 或 MyBatis 原生拦截器，应关闭对应的 `redis-observation-enabled` 或 `database-observation-enabled`，避免 Micrometer Observation 与原生扩展点重复记录同一次操作；原生拦截器不会受这两个开关影响。

## 框架接入状态

| 集成 | 当前能力 | 边界 |
| --- | --- | --- |
| Spring 事务 | Starter 通过 `TransactionExecutionListener` 采集耗时、长事务、Trace、线程资源和指标 | JFR、线程现场和宿主的额外诊断记录由应用负责 |
| Redis / Lettuce | Lettuce 存在时使用原生 Micrometer tracing 扩展 | 依赖应用实际启用 Lettuce 的 Observation |
| Redis / Redisson 社区版 | 通过 Spring Data `RedisConnection` 扩展记录命令 | 使用接口代理；应用若按具体 Redisson factory 类型注入，需关闭此适配或改注入 `RedisConnectionFactory` |
| HTTP server 请求 | Trace ID Filter 处理请求头；Micrometer HTTP server Observation 作为 Trace 根，记录慢/错操作和线程资源 | 异步请求最终生命周期由 Servlet/Micrometer 版本决定；不解析请求体 |
| HTTP client、JDBC | Observation Handler 接入 Micrometer 发布的 client 与 JDBC Observation；HTTP client 同时负责远程调用树节点。存在 OpenFeign 时自动加 Trace ID 请求头 | 客户端必须启用 Observation；不记录完整 URL 查询参数和 SQL 绑定参数 |
| Kafka 消息链路 | 自动把 Trace ID 生产拦截器追加到 Spring Boot 默认 ProducerFactory，并通过 `ContainerCustomizer` 接入默认 Listener Container。Observation Handler 接收 Spring Kafka Listener/Template Observation | 应用自定义 ProducerFactory、Listener Container Factory、ContainerCustomizer、非 Spring Kafka listener 或自定义消息头策略需自行接入；不读取消息体。应用必须启用 Kafka Observation 才会记录 Listener/Template 调用统计 |
| MyBatis | Starter 的 MyBatis `Interceptor` 自动记录 Mapper 名称、命令、耗时、线程资源及 Trace 子节点 | 只记录 Mapper ID 和 SQL 命令，不保存原始 SQL 与参数；可通过 `easy.observability.mybatis.enabled=false` 关闭 |
| Spring 线程池 | 提供 `TraceContextTaskDecorator`，传播并恢复 Trace ID 与 MDC | 自定义线程池需要设置该 `TaskDecorator`；不传播线程绑定的 Trace Tree 对象 |
| Spring 定时任务 | 使用 Spring 6.2 `SchedulingConfigurer` 扩展点启用 `@Scheduled` Observation，自动形成 JOB Trace 和慢/错记录 | 需要应用启用 `@EnableScheduling`；不使用 AOP 扫描任意方法 |
| Quartz / 自定义任务 | 注入 `JobObservation` 并调用 `observe`，复用相同的 JOB Trace、Slow/Error 和资源采集 | 不拦截 Quartz 内部类；由 Job/Listener 显式调用 |
| Spring 线程池指标 | 自动为 Spring `ThreadPoolTaskExecutor`、`ThreadPoolTaskScheduler` 和 ExecutorService 绑定 Micrometer 池指标 | 只记录池大小、活跃线程、队列等标准指标；任务名称需由 Spring 定时任务 Observation 或应用显式记录 |

Spring Kafka 的 Observation 需在模板和 Listener Container 上启用。Starter 会通过 Spring Boot 默认 `ContainerCustomizer` 安装 Trace ID 拦截器。若应用自定义了 Listener Container Factory 或 `ContainerCustomizer`，应在自己的配置中组合 `TraceIdRecordInterceptor` 与已有拦截器，避免替换宿主行为。应用专属消息头应由宿主自行管理。

Redis 适配可通过 `easy.observability.redis.enabled=false` 关闭。命令参数不会被记录。事务采集由 `easy.observability.transaction.enabled` 控制；`easy.observability.transaction.slow-threshold-ms` 默认是 `1000` 毫秒，设为 `0` 或负数会关闭长事务分类，但仍保留事务耗时采集。

Starter 不增加全局 Service 方法切面。优先通过框架 Filter、Interceptor、Listener 或 Observation 接入；只有需要显式业务节点时才使用 `@TraceSpan`。

Quartz、自建调度器或批处理入口可以直接使用 Starter 的 Observation 扩展，不需要引入 AOP：

```java
jobObservation.observe("nightly-reconciliation", () -> reconciliation.run());
```

## 独立监控页面和本地存储

Spring MVC 应用会获得 `/easy-observability/` 独立页面。页面打包在 Starter JAR 中，不依赖宿主前端路由、菜单或在线 JavaScript 资源。只读 API 位于 `/api/easy-observability`。

Micrometer 当前指标每分钟由独立后台线程写入 RRD4J。默认每个系列最多保留 15 天，可通过 `retention-days` 配置为 7 天或 30 天；历史分辨率为 1 分钟（24 小时）、5 分钟（7 天）和 30 分钟（其余保留期）。默认最多 512 个系列、总计 64 MiB，每个系列最多 8 MiB。总容量或系列数达到上限时，优先淘汰较低优先级、最近最久未更新的系列；同一优先级也会淘汰最旧系列，让新系列进入。JVM、进程和系统核心指标保留独立容量，低优先级系列不会挤掉它们。单轮最多创建或替换 64 个系列，以限制磁盘操作量；超出部分在后续采集轮次继续准入。指标名称、标签和 measurement 都有长度/数量上限。采集写入在独立后台线程执行，不占用业务请求线程；页面查询与写入共用有界文件访问，读请求等待超过上限时会快速失败返回。

为了控制高基数历史序列，RRD4J 保存 operation 耗时趋势，但不保存逐 operation 的 CPU、线程分配、阻塞和等待趋势；这些逐次资源信息仍可用于运行时统计，并随慢请求/错误 Trace 保存，支持按 Trace ID 下钻。JVM、进程和系统级资源趋势仍会保存。升级后首次恢复历史时，旧版本已经落盘的逐 operation 资源序列会被清理，释放出的空间可用于新的趋势数据。

慢操作和错误操作以有界 JSONL 持久化，事件包含操作名、Trace ID、线程 ID/名称、耗时、CPU 时间、线程分配字节数、阻塞/等待计数及错误摘要。慢/错 Trace 树单独落盘并保留关联操作资源信息；正常成功操作只保留在有界进程内列表，不写磁盘。默认文件位于 `./data/easy-observability/`，可用 `easy.observability.trace.slow-error-operation-file`、`slow-error-trace-file`、`slow-error-history-limit` 和 `slow-error-max-bytes` 调整。

独立页面采用 JavaMelody 报告式阅读顺序：顶部运行摘要、多个关键指标趋势、入口统计、慢请求/错误列表和最近 Trace。入口统计展示 HTTP、Job、Kafka 消费的调用数、平均/最大耗时、累计耗时占比、失败数/失败率、线程 CPU 时间和分配内存均值。计数从当前应用进程启动后累计；趋势范围作用于 RRD4J 时间序列和慢错事件列表。页面默认选择最多四个常用趋势，也可搜索 Micrometer 历史目录并切换最多四个指标。

Trace 根节点只由 HTTP server、Job 和 Kafka consumer 入口创建。HTTP client、Kafka producer、Redis、Elasticsearch、JDBC/MyBatis 与事务作为当前入口下的操作节点；它们在没有入口上下文时只记录操作指标，不会伪装成一次独立请求。发生慢调用或错误时，慢错列表打开入口 Trace，展示调用树、操作耗时、线程 ID、CPU 和线程分配内存。线程资源值受 JVM 支持和操作是否在同一线程完成的影响；异步切换线程时无法可靠计算的字段显示为空。慢错 Trace 在进程重启后仍可查询；数据按当前实例本地保存，不会自动汇总多个实例。

线程 CPU 时间和线程分配字节默认采样，可通过 `easy.observability.trace.resource-sampling-enabled=false` 关闭。分配字节表示操作期间线程累计分配量，不是操作结束时仍占用的堆内存。阻塞/等待计数需要额外查询 JVM `ThreadInfo`，默认关闭以减少高频调用开销；需要这些指标时可开启 `easy.observability.trace.thread-contention-sampling-enabled=true`。JVM 未启用相应线程监控能力时，字段显示为空值。

页面资源可直接打开，但 `/api/easy-observability/**` 默认拒绝匿名读取，避免新接入的应用意外暴露 Trace、错误摘要和线程信息。推荐由应用提供 `ObservabilityAccessPolicy` Bean 接入自己的登录/权限体系；页面同源请求会携带 Cookie，也支持 `window.EasyObservabilityAuth.token()` 钩子传入 Bearer Token。只有在可信本地开发环境，才显式设置 `easy.observability.web.allow-anonymous=true` 开放匿名读取。

设置 `easy.observability.web.enabled=false` 可关闭页面、API 和对应自动配置；`easy.observability.web.allow-anonymous=true` 仅用于可信本地环境；`easy.observability.web.trace-limit` 控制最近 Trace 数量，默认 `500`。生产环境应提供 `ObservabilityAccessPolicy` 并由宿主认证授权。

## Trace API

```java
@TraceSpan(name = "load-account", kind = SpanKind.SERVICE, tag = "account")
public Account loadAccount(long id) {
    return repository.findById(id);
}
```

`TraceContext` 管理当前 Trace Tree，`TraceBlock.call` / `TraceBlock.run` 用于分别包裹有返回值和无返回值的本地代码，`TraceIdContext` 管理 Trace ID 和 SLF4J MDC，`TraceContextTaskDecorator` 用于 Spring 线程池任务间传播和恢复 Trace/MDC 上下文。框架适配器内部使用有界的近期 Trace 存储；它不是分布式 Trace 后端，也不属于应用侧扩展 API。

需要跳过 `@TraceSpan` 时可在类或方法上标注 `@TraceDisabled`。`BusinessMetrics` 使用低基数标签提供 API 访问、远程调用、限流、定时任务、Outbox 和事务等 Micrometer 指标。应用可以调用这些方法，也可以直接使用 Micrometer 记录自己的指标。
