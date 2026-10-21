# 示例应用

- `easy-observability-example-spring-boot-3`：Spring Boot 3，默认地址 `http://localhost:8083/`
- `easy-observability-example-spring-boot-4`：Spring Boot 4，默认地址 `http://localhost:8084/`

主页提供正常、慢、错误、高 CPU、高内存请求，以及真实 H2 数据库、Redis、Kafka、定时任务和 httpbingo 远程 HTTP 调用。`/api/demo/real-flow` 在一个 HTTP Trace 中执行数据库写入/查询、Redis SET/GET、远程 HTTP 请求和 Kafka producer；`/api/demo/trace` 保留为明确标注的模拟嵌套树。监控 API 的匿名访问限制仅适用于本地体验。

## 启动与验证

先从仓库根目录启动 Redis 和 Kafka（Kafka 使用 KRaft，无需 ZooKeeper）：

```bash
docker compose -f examples/docker-compose.yml up -d --wait
```

然后选择一个 Spring Boot 示例启动：

在仓库根目录运行：

```bash
mvn -pl examples/easy-observability-example-spring-boot-3 -am package
java -jar examples/easy-observability-example-spring-boot-3/target/easy-observability-example-spring-boot-3-0.1.0-alpha.0.jar

mvn -pl examples/easy-observability-example-spring-boot-4 -am package
java -jar examples/easy-observability-example-spring-boot-4/target/easy-observability-example-spring-boot-4-0.1.0-alpha.0.jar
```

打开示例主页后触发这些入口：

- `/api/demo/redis`：真实 Redis 自增、写入并读回当前 Trace ID；树中应出现 Redis 子调用。示例用 `TraceBlock` 包裹真实同步 Redis 操作，确保 Lettuce Netty 线程不会让子调用脱离父 Trace。
- `/api/demo/kafka`：发送真实 Kafka 消息，等待消费者读取并返回生产/消费 Trace ID 是否一致。
- `/api/demo/kafka/slow`：触发真实 Kafka 消费端约 650 ms 的处理延迟；慢消费者应在关注列表中显示为 `KAFKA_CONSUMER` 入口，并可用响应中的 Trace ID 下钻。
- `/api/demo/kafka/error`：触发真实 Kafka 消费异常，验证消费端错误入口与调用详情；示例只投递一次并恢复消费，避免重试造成重复错误记录。
- Kafka 示例为同一演示主题配置两个独立 Consumer Group，并通过 Spring Kafka 的 `MicrometerConsumerListener` 绑定客户端指标；指标趋势页会把 Listener Timer 和 Kafka Consumer 客户端指标放在独立分组中，按 Listener/客户端标签展示多条曲线。
- Kafka 首次启动时 broker 或 consumer group 可能需要一些时间完成协调；若接口仍显示待确认，可查 `/api/demo/kafka/status`。三个接口都会返回生产与消费 Trace ID 的匹配结果。
- `/api/demo/remote`：调用 `https://httpbingo.org/delay/1` 和 `/headers`，httpbingo 响应会回显 `X-Trace-Id`；树中应出现两个远程 HTTP 子调用。
- `/api/demo/real-flow`：真实插入并查询 H2 的 `demo_orders`，读写 Redis，再请求 httpbingo `/anything/orders/{id}`，最后向 Kafka producer 发送订单事件。返回值包含订单、Redis 读回结果、远程响应片段及 Kafka topic/partition/offset；用响应头 `X-Trace-Id` 在监控页查看整条 Trace。
- `/api/demo/transaction/error`：事务内插入后主动抛错，用于观察 SQL 错误、Spring 事务回滚和根因定位；接口预期返回 HTTP 500。
- `/api/demo/okhttp`：通过 Spring 管理的 `OkHttpClient` 调用 httpbingo，验证自动注入 Trace ID、HTTP 状态/耗时 Observation 和远程调用记录。
- `/api/demo/executor?mode=normal|slow|error`：提交到 `demoTaskExecutor` 的异步任务并返回 task ID。慢任务执行约 700 ms，失败任务抛出异常。打开监控页“线程池任务”入口筛选，或在“慢请求 / 错误请求”下钻独立任务 Trace；`/api/demo/executor/{taskId}` 查看演示状态。示例显式使用 `TraceContextTaskDecorator` 传播提交线程的 Trace ID，任务用 `ExecutorTaskObservation` 记录耗时、失败根因和线程信息。
- 定时任务启动 5 秒后执行，随后每 30 秒递增 Redis 计数并非阻塞地读取队列；生成 `task DemoScheduledJob.countHeartbeat` 入口 Trace 及 Redis 子调用，不会持续制造慢调用。
- 第二个定时任务每 45 秒执行一次独立 Redis 计数；两个任务的 Spring 原生 `tasks.scheduled.execution` Timer 会在“定时任务”指标分组中分别显示。调度器线程池指标在 Spring Boot 将其绑定到 MeterRegistry 时也会显示在同一分组。

HTTP 请求的响应头也会返回 `X-Trace-Id`。打开 `/easy-observability/`，在“最近入口调用”或慢请求/错误请求列表中按该 ID 对照；Kafka 的 producer 和 consumer 是异步的独立调用入口，但共享相同 Trace ID。慢、错误 Kafka 示例可能同时让外层 HTTP 请求进入关注列表，这是因为接口会等待 consumer 完成。

线程池自动暴露 Micrometer 的 `executor.active`、`executor.queued`、`executor.pool.*`、`executor.completed` 和剩余队列容量等指标；监控页“指标趋势”可查看池容量、活跃线程和队列变化。逐任务的耗时、慢任务、异常和根因由 `ExecutorTaskObservation` 记录为独立 Trace；自动 executor 指标本身只呈现线程池整体状态，逐任务记录需要在任务执行边界调用该观测器。

`/api/demo/real-flow` 的订单写入/查询运行在 Spring `@Transactional` 服务中。JDBC 观测从真实 `DataSource` 边界采集，因此 `JdbcTemplate`、MyBatis 与 JPA 的 SQL 都可在 Trace 中查看脱敏模板、耗时、错误和慢调用；Spring 管理的事务会显示耗时与提交/回滚结果。Actuator 的 `jdbc.connections.*` 与 `hikaricp.*` 可查看连接池趋势；Druid 另显示 `druid.datasource.*` 指标。SQL 绑定参数默认不记录。

如需更换 httpbingo 地址，可设置 `EASY_OBSERVABILITY_DEMO_HTTPBINGO_BASE_URL`。完成后关闭本地依赖：

```bash
docker compose -f examples/docker-compose.yml down
```
