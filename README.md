# Easy Observability

**Spring Boot Trace 诊断 Starter**

面向 Spring Boot 3/4 应用的轻量嵌入式排障工具。它从 HTTP 请求、定时任务和 Kafka 消费入口采集 Trace，并串联可观测到的 JDBC、Redis、事务及远程调用，帮助快速定位慢调用、异常、CPU 消耗和内存分配问题。Trace ID 可沿受支持的 HTTP 客户端和 Kafka 消息传播；页面和有界历史聚焦当前应用实例。

项目提供 Spring Boot 3 和 Spring Boot 4 两个 Starter。项目不注册自定义 Micrometer Meter、不保存指标历史；Actuator 自带的指标能力由应用自己的 Actuator 配置管理。

[![Boot 3 Starter on Maven Central](https://img.shields.io/maven-central/v/io.github.lakernote/easy-observability-spring-boot-3-starter?label=Boot%203%20Starter&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.lakernote/easy-observability-spring-boot-3-starter)
[![Boot 4 Starter on Maven Central](https://img.shields.io/maven-central/v/io.github.lakernote/easy-observability-spring-boot-4-starter?label=Boot%204%20Starter&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.lakernote/easy-observability-spring-boot-4-starter)

## 界面预览

Trace 诊断页汇总慢调用、错误、高 CPU 和高内存分配入口；打开记录后，可沿时间线查看数据库、Redis、远程调用等节点，并定位耗时最长的子调用或资源告警。

![Trace 诊断列表](docs/images/trace-list.png)

![Trace 详情时间线及最长子调用](docs/images/trace-detail-timeline.png)

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
| Spring HTTP client、OkHttp | 在对应客户端 Observation 可用时记录远程调用；Spring 管理的 `RestClient.Builder` 自动传递 Trace ID；Spring 容器中的 OkHttpClient 会自动增强 | `RestClient.create()` 等绕过 Spring Builder 的客户端不会自动增强 |
| JDBC | 在当前 Trace 中记录脱敏 SQL 模板、耗时和错误 | 自动采集；不记录绑定参数，也不做 SQL 汇总统计 |
| Spring 事务、MyBatis | 记录事务范围；MyBatis SQL 通过 DataSource/JDBC 监听器记录为 SQL Span | 不额外创建 Mapper Span；自定义事务或非 Spring 边界需自行接入 |
| Redis | Lettuce Observation 自动记录命令级 Span，保留命令、耗时和错误 | Spring Boot 3/4 的 Lettuce Observation 接入随 Starter 自动配置；自定义 `ClientResources` 需自行接入 Observation |
| Kafka | Listener 和 producer Observation 记录为入口或依赖 Span，并传播 Trace ID | Spring Kafka 需要启用 Listener/Template Observation；自定义消费者需自行接入 |
| 线程池任务 | `TraceContextTaskDecorator` 在线程池边界传播活动 Trace，并自动记录任务耗时和异常 | 在线程池配置一次装饰器；无活动父 Trace 时不创建任务节点 |
| 业务方法 | `TraceBlock` 或 `@TraceSpan` 添加业务 Span | 不扫描所有 Service；`@TraceSpan` 需要应用已有 Spring AOP |

Spring Boot Actuator 可以按应用配置提供 JVM、进程、连接池等原生指标。本项目不读取、存储或绘制这些指标；要查看 Actuator 指标，请使用应用自己的 Actuator 端点或现有监控系统。

Redis 接入复用 Lettuce 的 Micrometer Observation，不单独注册 Meter；应用若启用了 Actuator 的 Meter handler，也会看到 Lettuce 自带的命令耗时指标。

## Trace 诊断与根因定位

诊断列表只保留慢调用、错误或 CPU/分配量超阈值的入口，不会显示普通请求。列表支持按入口、结果类型和文本搜索，可匹配 Trace ID、SQL、依赖名称与错误摘要。详情包含入口总耗时、各调用节点耗时、HTTP 状态、线程信息及调用时间线，也会显示入口线程 CPU 时间和累计分配量。分配量不代表当前堆占用。单个依赖错误会标记在对应 Span 上；依赖耗时仍完整保留，便于从入口 Trace 中定位耗时步骤。

捕获到异常时，详情和诊断归档会保存异常类型、消息及有界堆栈；HTTP 5xx 等只有状态码的错误会标记为错误，但没有异常对象时不会生成虚构堆栈。SQL 会在进入 Trace 前清理字面量和注释。调用树和异常堆栈均有大小限制，避免单条异常无限扩张存储。

慢调用、错误及资源超阈值 Trace 会同时保留在有界内存近期列表和本地 JSONL 文件中；正常 Trace 不会占用这两处历史记录。默认文件为 `./data/easy-observability/traces.jsonl`；多实例之间不会自动汇总。Trace ID 默认通过 `X-Trace-Id` 请求头传递。页面路径为 `/easy-observability/`，只读 API 路径为 `/api/easy-observability`，诊断筛选 API 为 `/api/easy-observability/diagnostics?kind=slow|error|resource`。

## 配置

以下配置均可省略；默认开启 Trace、HTTP 页面及 JDBC/Redis/事务节点采集，并在每个入口采集入口线程 CPU 时间和分配量。Starter 会提供线程池上下文装饰器 Bean，在线程池配置使用后才会传播和记录任务。慢阈值仅用于 HTTP、定时任务和 Kafka 消费入口；设为 `0` 或负数可关闭对应入口的慢分类。依赖节点始终记录耗时和错误，不单独按慢阈值标记；所有命中的诊断 Trace 都会保留。

```yaml
easy:
  observability:
    enabled: true
    trace:
      enabled: true
      header-name: X-Trace-Id
      http-slow-threshold-ms: 500
      job-slow-threshold-ms: 1000
      kafka-consumer-slow-threshold-ms: 1000
      cpu-threshold-ms: 500
      allocated-memory-threshold: 50MB
      diagnostic-trace-file: ./data/easy-observability/traces.jsonl
      diagnostic-history-limit: 1000
      diagnostic-max-bytes: 8MB
    web:
      enabled: true
      allow-anonymous: false
      trace-limit: 500
```

| 配置 | 默认值 | 作用 |
| --- | --- | --- |
| `easy.observability.enabled` | `true` | Starter 总开关；关闭后不创建 Easy Observability 自动配置 |
| `easy.observability.trace.enabled` | `true` | Trace 采集和上下文传播总开关 |
| `easy.observability.trace.header-name` | `X-Trace-Id` | HTTP 请求/响应及受支持客户端、Kafka 消息使用的 Trace ID 名称 |
| `easy.observability.trace.http-slow-threshold-ms` | `500` | HTTP 入口慢阈值 |
| `easy.observability.trace.job-slow-threshold-ms` | `1000` | 定时任务入口慢阈值 |
| `easy.observability.trace.kafka-consumer-slow-threshold-ms` | `1000` | Kafka 消费入口慢阈值 |
| `easy.observability.trace.cpu-threshold-ms` | `500` | 入口线程 CPU 时间达到该值时保存资源告警 Trace；设为 `0` 或负数关闭 CPU 触发 |
| `easy.observability.trace.allocated-memory-threshold` | `50MB` | 入口线程累计分配量达到该值时保存资源告警 Trace；支持 `8MB` 等大小写法，设为 `0B` 关闭分配量触发 |
| `easy.observability.trace.diagnostic-trace-file` | `./data/easy-observability/traces.jsonl` | 诊断入口 Trace 的 JSONL 归档路径，相对路径以应用工作目录为基准 |
| `easy.observability.trace.diagnostic-history-limit` | `1000` | 诊断归档索引的最大记录数；实际下限为 `100` |
| `easy.observability.trace.diagnostic-max-bytes` | `8MB` | JSONL 诊断归档文件上限；实际下限为 `1MB`，超限时保留较新的记录 |
| `easy.observability.web.enabled` | `true` | 是否提供 Trace 页面和只读 API |
| `easy.observability.web.allow-anonymous` | `false` | 是否允许匿名读取 Trace 页面/API；生产环境保持关闭 |
| `easy.observability.web.trace-limit` | `500` | 最近诊断入口 Trace 的内存窗口容量，不影响 JSONL 归档容量 |

页面默认使用 `ObservabilityAccessPolicy` 拒绝匿名访问。生产环境请接入应用自己的认证授权，可提供该策略的 Bean。`easy.observability.web.allow-anonymous=true` 适用于可信的本地示例环境。近期内存窗口和 JSONL 归档保存慢、错误或 CPU/分配量达到阈值的入口 Trace；`web.trace-limit` 控制内存窗口，`diagnostic-history-limit` 控制归档索引条数，`diagnostic-max-bytes` 控制磁盘文件大小。

### Kafka Listener Trace 接入范围

Kafka 消费端从消息头恢复 Trace ID，并提供 `TraceIdRecordInterceptor` 给 Spring Boot 默认 Listener Factory，因此使用默认 Factory 的 `@KafkaListener` 通常无需额外配置。生产端自动写入消息头；Spring Kafka 还需启用 Listener/Template Observation，Trace ID 才能跨生产和消费关联。

拦截器在 Listener 执行前读取消息头（默认 `X-Trace-Id`），恢复当前线程上下文；消息头缺失或格式无效时会生成新的 Trace ID。Listener 执行后会恢复线程原有上下文。它不读取消息体，也不会自动接入原生 `KafkaConsumer`、Kafka Streams 或绕过 Spring Boot Listener Factory 的自定义消费代码。

Kafka 自动接入由类路径上的 Spring Kafka 和 `easy.observability.enabled`、`easy.observability.trace.enabled` 控制，没有单独的 Kafka 开关。若应用已定义自己的 `RecordInterceptor` Bean，Starter 会让出该 Bean；请显式组合 Trace 拦截器。自定义 Listener Factory 若不使用 Boot 的 Factory 配置器，也需手动设置：

```java
@Bean
ConcurrentKafkaListenerContainerFactory<String, String> ordersListenerFactory(
        ConsumerFactory<String, String> consumerFactory,
        TraceIdRecordInterceptor traceInterceptor) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.setRecordInterceptor(traceInterceptor);
    return factory;
}

@KafkaListener(topics = "orders", containerFactory = "ordersListenerFactory")
public void consume(String message) {
    // 这里可读取 TraceIdContext.currentTraceId() 获取当前消息的 Trace ID。
}
```

如果 Factory 已有拦截器，请用 Spring Kafka 的 `CompositeRecordInterceptor` 组合，避免覆盖原有逻辑：

```java
factory.setRecordInterceptor(new CompositeRecordInterceptor<>(
        existingInterceptor,
        new TraceIdRecordInterceptor(properties)));
```

## 手动添加业务 Span

```java
return TraceBlock.call("partner.lookup", SpanKind.REMOTE, "partner-api",
        () -> partnerClient.lookup(accountId));
```

在线程池上配置一次公共装饰器即可自动记录任务边界，不需要在每个任务里手动调用 Trace API：

```java
@Bean("billingExecutor")
ThreadPoolTaskExecutor billingExecutor(TraceContextTaskDecorator traceTaskDecorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setTaskDecorator(traceTaskDecorator);
    return executor;
}
```

后续照常提交任务；装饰器会在线程池边界自动创建 `executor.task` Trace 节点。

Spring 管理的 `RestClient.Builder` 也会自动传递当前 Trace ID，并使用 Spring Boot 的 Observation 记录远程调用。通过 `RestClient.create()` 或独立 `RestClient.builder()` 绕过 Spring Builder 时，不会应用 Starter 的 Trace ID 拦截器。

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
