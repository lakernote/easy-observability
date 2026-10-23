# Easy Observability

面向 Spring Boot 应用的轻量 Trace Starter。它把 HTTP 请求、Spring 定时任务和 Kafka 消费作为入口 Trace，并把可观测到的数据库、Redis、事务和远程调用挂到同一调用树中，帮助从一次慢调用或错误定位具体耗时节点和异常堆栈。

项目提供 Spring Boot 3 和 Spring Boot 4 两个 Starter。项目不注册自定义 Micrometer Meter、不保存指标历史；Actuator 自带的指标能力由应用自己的 Actuator 配置管理。

## 安装

按应用的 Spring Boot 主版本选择一个 Starter：

```xml
<!-- Spring Boot 3 -->
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>easy-observability-spring-boot-3-starter</artifactId>
    <version>0.1.0-alpha.0</version>
</dependency>
```

```xml
<!-- Spring Boot 4 -->
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>easy-observability-spring-boot-4-starter</artifactId>
    <version>0.1.0-alpha.0</version>
</dependency>
```

当前为预览版本，需要 Java 17+ 和 Maven 3.6.3+。在仓库根目录运行 `mvn verify` 可构建两个 Starter 和示例应用。

## 模块结构

```text
easy-observability-spring-boot-common/           # Trace 实现、页面及共享自动配置
easy-observability-spring-boot-3-autoconfigure/ # Boot 3 自动配置
easy-observability-spring-boot-3-starter/       # Boot 3 依赖入口
easy-observability-spring-boot-4-autoconfigure/ # Boot 4 自动配置
easy-observability-spring-boot-4-starter/       # Boot 4 依赖入口
examples/                                       # 两个 Boot 版本的端到端示例
```

应用只需引入对应 Starter。它包含 Actuator 以使用 Spring Boot 的 Observation 基础设施；Starter 不覆盖应用已有的 `MeterRegistry` 或 `ObservationRegistry`，也不额外开放 Actuator 端点。应用是否暴露 Actuator 指标由应用自行配置。

## 自动 Trace 与接入边界

自动入口 Trace 来自 Spring MVC HTTP、Spring `@Scheduled` 和启用 Observation 的 Spring Kafka Listener。入口下游调用会作为 Span 加入当前 Trace；没有入口上下文时，不会为单个依赖调用创建伪造的请求 Trace。

| 操作 | Trace 行为 | 边界 |
| --- | --- | --- |
| HTTP server | 记录入口路由、状态、耗时和错误 | 非 Spring MVC 入口需要显式接入 |
| Spring HTTP client、OkHttp | 在对应客户端 Observation 可用时记录远程调用；Spring 容器中的 OkHttpClient 会自动增强 | 容器外创建的客户端不会自动增强；可关闭 `easy.observability.http.okhttp.enabled` |
| JDBC | 在当前 Trace 中记录脱敏 SQL 模板、耗时和错误 | 不记录绑定参数，也不做 SQL 汇总统计；可关闭 `easy.observability.trace.database-observation-enabled` |
| Spring 事务、MyBatis | 记录事务范围；MyBatis SQL 通过 DataSource/JDBC 监听器记录为 SQL Span | 不额外创建 Mapper Span；自定义事务或非 Spring 边界需自行接入 |
| Redis | 可用的 Redis Observation 作为依赖 Span | Lettuce 命令可能在 Netty 线程结束；按需用 `TraceBlock` 包住同步调用以保留父子关系 |
| Kafka | Listener 和 producer Observation 记录为入口或依赖 Span，并传播 Trace ID | Spring Kafka 需要启用 Listener/Template Observation；自定义消费者需自行接入 |
| 线程池任务 | `ExecutorTaskTrace` 记录单个任务耗时、错误和线程；`TraceContextTaskDecorator` 传播活动 Trace | 任务边界需要显式调用；任务不会默认创建独立入口 Trace |
| 业务方法 | `TraceBlock` 或 `@TraceSpan` 添加业务 Span | 不扫描所有 Service；`@TraceSpan` 需要应用已有 Spring AOP |

Spring Boot Actuator 可以按应用配置提供 JVM、进程、连接池等原生指标。本项目不读取、存储或绘制这些指标；要查看 Actuator 指标，请使用应用自己的 Actuator 端点或现有监控系统。

## 慢调用与错误定位

Trace 列表只保留慢调用和错误入口，不会显示正常请求。列表支持按入口、慢/错误状态和文本搜索，可匹配 Trace ID、SQL、依赖名称与错误摘要。详情包含入口总耗时、各调用节点耗时、HTTP 状态、线程信息及调用树；启用资源采样时还会显示线程 CPU 时间和线程分配量。列表中的分配内存也是请求线程累计分配量，不代表当前堆占用。单个依赖错误会标记在对应 Span 上，便于直接判断慢或错发生在哪个下游操作。

捕获到异常时，详情和慢错归档会保存异常类型、消息及有界堆栈；HTTP 5xx 等只有状态码的错误会标记为错误，但没有异常对象时不会生成虚构堆栈。SQL 会在进入 Trace 前清理字面量和注释。调用树和异常堆栈均有大小限制，避免单条异常无限扩张存储。

慢错 Trace 会同时保留在有界内存近期列表和本地 JSONL 文件中；正常 Trace 不会占用这两处历史记录。默认文件为 `./data/easy-observability/traces.jsonl`；多实例之间不会自动汇总。Trace ID 默认通过 `X-Trace-Id` 请求头传递。页面路径为 `/easy-observability/`，只读 API 路径为 `/api/easy-observability`。

## 配置

```yaml
easy:
  observability:
    enabled: true
    trace:
      http-slow-threshold-ms: 250
      job-slow-threshold-ms: 1000
      kafka-consumer-slow-threshold-ms: 250
      dependency-slow-threshold-ms: 250
      database-observation-enabled: true
      resource-sampling-enabled: false
      slow-error-history-limit: 1000
      slow-error-max-bytes: 8MB
    web:
      enabled: true
      allow-anonymous: false
```

| 配置 | 默认值 | 作用 |
| --- | --- | --- |
| `easy.observability.enabled` | `true` | Starter 总开关 |
| `easy.observability.trace.http-slow-threshold-ms` | `250` | HTTP 慢调用阈值；设为 0 或负数可关闭慢分类 |
| `easy.observability.trace.job-slow-threshold-ms` | `1000` | 定时任务慢阈值 |
| `easy.observability.trace.kafka-consumer-slow-threshold-ms` | `250` | Kafka 消费慢阈值 |
| `easy.observability.trace.dependency-slow-threshold-ms` | `250` | 依赖调用慢阈值 |
| `easy.observability.trace.database-observation-enabled` | `true` | 是否在 Trace 中记录 JDBC SQL Span |
| `easy.observability.trace.resource-sampling-enabled` | `false` | 是否在入口 Trace 附带线程 CPU 和分配量；逐操作读取 JVM 管理接口 |
| `easy.observability.trace.slow-error-history-limit` | `1000` | 内存近期慢错 Trace 与慢错归档条数上限 |
| `easy.observability.trace.slow-error-max-bytes` | `8MB` | Trace JSONL 文件容量上限 |
| `easy.observability.web.allow-anonymous` | `false` | 是否允许匿名读取 Trace 页面/API；生产环境保持关闭 |

页面默认使用 `ObservabilityAccessPolicy` 拒绝匿名访问。生产环境请接入应用自己的认证授权，可提供该策略的 Bean。`easy.observability.web.allow-anonymous=true` 适用于可信的本地示例环境。

## 手动添加业务 Span

```java
return TraceBlock.call("partner.lookup", SpanKind.REMOTE, "partner-api",
        () -> partnerClient.lookup(accountId));
```

线程池任务示例：

```java
executor.execute(() -> executorTaskTrace.observe(
        "billingExecutor", "invoice.reconcile", () -> invoiceService.reconcile()));
```

## 运行示例

示例演示 JDBC 查询、事务回滚、Redis、远程 HTTP、Kafka 和异步线程池上下文传播 Trace。启动本地依赖后运行任一示例：

```bash
docker compose -f examples/docker-compose.yml up -d --wait
mvn -pl examples/easy-observability-example-spring-boot-3 -am package
java -jar examples/easy-observability-example-spring-boot-3/target/easy-observability-example-spring-boot-3-0.1.0-alpha.0.jar
```

Boot 3 示例运行在 `http://localhost:8083/`，Boot 4 示例运行在 `http://localhost:8084/`。示例启用了匿名 Trace 页面，仅用于本地体验。完整场景见 [示例使用说明](examples/README.md)。

## Maven Central 发布

项目按 Maven Central 发布格式构建全部模块。发布前需要配置 Sonatype Central 凭据和 GPG 签名；发布步骤及签名配置见 [贡献指南](CONTRIBUTING.md)。
