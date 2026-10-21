# Easy Observability

面向 Spring Boot 应用的轻量观测 Starter，基于 Micrometer 提供指标、趋势、慢操作与错误定位，以及本地 Trace 调用树。项目提供 Spring Boot 3 和 Spring Boot 4 两个 Starter，使用相同的 API。

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

项目当前为预览版本。需要 Java 17+ 和 Maven 3.6.3+；在仓库根目录运行 `mvn verify` 可构建两个 Starter 和示例应用。

Starter 包含 Spring Boot Actuator，并复用 Boot 创建的 Micrometer 注册表；不会额外开放 Actuator 端点，也不会覆盖应用已有的 `MeterRegistry` 或 `ObservationRegistry`。

## 运行示例

示例会演示真实的 JDBC 写入/查询、事务回滚、Redis 读写、HTTP/OkHttp 调用、Kafka producer 和线程池任务。Redis、Kafka 示例需要先启动仓库提供的本地依赖；远程 HTTP 示例会请求 `httpbingo.org`：

```bash
docker compose -f examples/docker-compose.yml up -d --wait
```

然后启动一个版本：

```bash
mvn -pl examples/easy-observability-example-spring-boot-3 -am package
java -jar examples/easy-observability-example-spring-boot-3/target/easy-observability-example-spring-boot-3-0.1.0-alpha.0.jar
```

Boot 3 示例页面在 `http://localhost:8083/`；Boot 4 示例用 `easy-observability-example-spring-boot-4` 模块，页面在 `http://localhost:8084/`。示例为本地体验开放匿名监控 API，并包含慢请求和资源消耗演示，不要部署到生产环境。
各示例接口和预期观测结果见 [示例使用说明](examples/README.md)。

## 自动采集与手动接入

是否自动采集取决于应用是否使用对应 Spring 集成，以及该集成是否启用了 Observation。

| 能力 | 默认行为 | 需要手动处理的情况 |
| --- | --- | --- |
| JVM、进程、线程池指标 | 自动绑定；指标历史默认每分钟采样 | 自建指标使用 Micrometer API；不需要本地趋势时关闭 `easy.observability.metrics.history.enabled` |
| HTTP server Trace | Spring MVC Observation 创建入口 Trace，记录慢调用和错误 | 非 Spring MVC 或由应用管理生命周期的异步入口需自行接入 |
| Spring HTTP clients | 记录 Boot/Micrometer 已接入 Observation 的客户端调用 | 自建客户端按需安装 Observation 或拦截器 |
| OkHttp | 自动增强 Spring 容器中的 `OkHttpClient` Bean，并传播 Trace ID | 容器外创建的客户端不自动增强；可用 `easy.observability.http.okhttp.enabled=false` 关闭 |
| JDBC、事务、MyBatis | DataSource 记录 SQL 执行；Spring 事务记录耗时和提交/回滚；MyBatis 插件补充 Mapper 节点 | 自建 DataSource/事务边界需自行接入；ResultSet 每次读取的 FETCH 细节默认关闭 |
| Kafka Consumer | Spring Kafka Listener Observation 生成原生 Timer；绑定 `MicrometerConsumerListener` 后同时采集 `kafka.consumer.*` 客户端指标 | 需设置 `spring.kafka.listener.observation-enabled: true`；自定义工厂或非 Spring 消费者需自行接入 |
| `@Scheduled` | 启用 Spring Scheduling 后连接 Spring 原生 Observation；保存 `tasks.scheduled.execution` Timer 和可用的调度线程池指标 | Quartz、自建调度器和批处理入口可调用 `JobObservation` |
| 线程池任务 | 自动记录 Spring 线程池状态 | 可调用 `ExecutorTaskObservation` 记录单个任务的操作指标；它不创建独立入口 Trace。自建线程池需设置 `TraceContextTaskDecorator` 传播 Trace 执行上下文和 MDC |
| 业务方法与业务指标 | 不扫描所有 Service | 使用 `TraceBlock` 显式记录调用；自建指标使用 Micrometer API；`@TraceSpan` 需要应用提供 Spring AOP |

自动创建 Trace 的入口只有 HTTP 请求、Spring 定时任务和 Kafka consumer。HTTP client、Kafka producer、Redis、JDBC/MyBatis、事务和线程池任务作为当前入口下的操作；没有入口上下文时只记录操作指标，不会虚构请求 Trace。

HTTP、Spring 定时任务和 Kafka Consumer 的耗时趋势读取 Spring/Micrometer 注册的原生 Timer；本地历史采集器定期保存这些 Meter，图表按 URI、任务或监听器标签绘制多条曲线。Kafka 客户端指标通过 Spring Kafka 提供的 `MicrometerConsumerListener` 注册后也会进入独立的 Kafka 指标分组。Actuator 提供当前 Meter 查询与导出能力，本地趋势由历史采集器保留。

JDBC 代理默认观测连接、SQL 执行和 generated keys，不记录 SQL 参数。SQL 字面量和注释会被清理后用于诊断。需要逐次 ResultSet 读取数据时，显式设置：

```yaml
jdbc:
  includes: CONNECTION,QUERY,FETCH,KEYS
  resultset-operations:
    enabled: true
```

DataSource SQL 采集可用 `jdbc.datasource-proxy.enabled=false` 关闭。Spring Boot Actuator 会为支持的数据源和 HikariCP 提供连接池指标；本项目还补充 Druid 指标。Apache Commons Pool 通用对象池不是 JDBC 连接池指标的自动采集范围。

## 配置与开销

默认开启框架级耗时、慢操作和错误记录。线程 CPU/分配量、线程阻塞/等待采样需要逐操作查询 JVM 管理接口，因此默认关闭；大结果集的 ResultSet 方法级记录也默认关闭。指标历史由后台线程采样，并受系列数和磁盘容量上限约束。

```yaml
easy:
  observability:
    metrics:
      history:
        enabled: true
        storage-dir: ./data/easy-observability/metrics
        retention-days: 15
        max-storage-bytes: 64MB
        max-series-bytes: 8MB
        max-series: 256
    trace:
      resource-sampling-enabled: false
      slow-error-history-limit: 1000
      slow-error-max-bytes: 8MB
    web:
      enabled: true
      allow-anonymous: false
```

这些容量项使用 Spring Boot 内置的 `DataSize` 配置转换，支持 `B`、`KB`、`MB`、`GB`、`TB` 单位。建议始终带单位填写，例如 `64MB`，避免裸数字产生单位歧义。

### 常用配置

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `easy.observability.enabled` | `true` | 总开关；关闭后不创建 Starter 的观测组件 |
| `easy.observability.metrics.enabled` | `true` | 开关 Starter 自身的 Micrometer 指标 |
| `easy.observability.metrics.history.enabled` | `true` | 开关本地趋势存储；关闭后仍可由 Micrometer 注册表导出指标 |
| `easy.observability.metrics.history.retention-days` | `15` | 趋势保留天数 |
| `easy.observability.metrics.history.max-storage-bytes` | `64MB` | 历史文件总容量上限 |
| `easy.observability.metrics.history.max-series-bytes` | `8MB` | 单个指标系列的容量上限 |
| `easy.observability.metrics.history.max-series` | `256` | 最多保存的指标系列数 |
| `easy.observability.trace.http-slow-threshold-ms` | `250` | HTTP 入口慢阈值；设为 `0` 或负数关闭慢分类 |
| `easy.observability.trace.job-slow-threshold-ms` | `1000` | 定时任务慢阈值 |
| `easy.observability.trace.executor-task-slow-threshold-ms` | `250` | 线程池任务慢阈值 |
| `easy.observability.trace.kafka-consumer-slow-threshold-ms` | `250` | Kafka consumer 慢阈值 |
| `easy.observability.trace.dependency-slow-threshold-ms` | `250` | HTTP client、JDBC 等依赖调用慢阈值 |
| `easy.observability.transaction.slow-threshold-ms` | `1000` | Spring 事务慢阈值 |
| `easy.observability.trace.resource-sampling-enabled` | `false` | 采集线程 CPU 时间和分配字节；逐操作查询 JVM 管理接口 |
| `easy.observability.trace.slow-error-history-limit` | `1000` | 慢错事件与 Trace 的内存历史上限 |
| `easy.observability.trace.slow-error-max-bytes` | `8MB` | 慢错事件文件和 Trace 文件各自的容量上限 |
| `easy.observability.web.allow-anonymous` | `false` | 是否允许匿名访问监控 API；生产保持关闭 |

`slow-error-history-limit` 对事件列表和 Trace 列表分别生效。Redis、Kafka、MyBatis、事务、定时任务和 OkHttp 可分别通过 `easy.observability.redis.enabled`、`kafka.enabled`、`mybatis.enabled`、`transaction.enabled`、`scheduling.enabled` 和 `http.okhttp.enabled` 关闭。JDBC DataSource 代理使用上游开关 `jdbc.datasource-proxy.enabled`。

按需诊断时可开启 `easy.observability.trace.resource-sampling-enabled`，采集操作线程的 CPU 时间和分配字节。分配量表示这次调用在线程上累计分配的内存，不是调用结束后仍存活的堆内存，也不代表峰值堆占用；它适合发现高分配调用，OOM 还需结合堆转储、GC 和堆使用趋势分析。数据受 JVM 支持情况影响，异步跨线程结束时线程级差值可能为空。

Metrics 历史默认每分钟采样，最多保留 15 天、256 个系列和 64 MiB 数据。慢错事件和 Trace 文件各自最多 8 MiB，进程内慢错历史默认最多 1,000 条；单棵 Trace 最多 512 个节点。关闭历史趋势可设置 `easy.observability.metrics.history.enabled=false`，关闭整个 Starter 可设置 `easy.observability.enabled=false`。

## 页面与安全

引入 Spring MVC 后，监控页面位于 `/easy-observability/`，只读 API 位于 `/api/easy-observability`。API 默认拒绝匿名访问；生产环境应接入应用自己的认证授权，可提供 `ObservabilityAccessPolicy` Bean。`easy.observability.web.allow-anonymous=true` 仅适用于可信的本地环境。

慢错 Trace 和指标历史保存在当前实例本地 `./data/easy-observability/`，不会自动汇总多个实例。Micrometer 指标仍可通过应用已有的注册表导出给外部监控系统；本项目不会替应用修改 Actuator 端点暴露配置。

## API 示例

用 `@TraceSpan` 标记少量有诊断价值的业务方法。它需要应用已有 Spring AOP，不会自动切面扫描所有 Service：

```java
@Service
class PricingService {
    @TraceSpan(name = "pricing.quote", kind = SpanKind.SERVICE, tag = "pricing")
    public Quote quote(String sku) {
        return pricingClient.quote(sku);
    }
}
```

对需要明确标记的本地代码块使用 `TraceBlock`；异常会正常向上抛出，调用树仍会记录该节点：

```java
return TraceBlock.call("partner.lookup", SpanKind.REMOTE, "partner-api",
        () -> partnerClient.lookup(accountId));
```

Quartz 或自建任务可用 `JobObservation` 建立任务入口：

```java
jobObservation.observe("nightly-reconciliation", () -> reconciliation.run());
```

线程池整体状态指标是自动采集的；逐任务 Trace 需要在任务执行边界显式调用：

```java
executor.execute(() -> executorTaskObservation.observe(
        "billingExecutor", "invoice.reconcile", () -> invoiceService.reconcile()));
```

`TraceContextTaskDecorator` 在提交任务时捕获 Trace ID、当前父 Span 和 MDC，在工作线程中按作用域恢复，并在任务结束后恢复工作线程原有上下文。项目提供本地调用树和有界事件历史，不是分布式 Trace 后端。
