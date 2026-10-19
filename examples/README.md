# 示例应用

- `easy-observability-example-spring-boot-3`：Spring Boot 3，默认地址 `http://localhost:8083/`
- `easy-observability-example-spring-boot-4`：Spring Boot 4，默认地址 `http://localhost:8084/`

主页提供正常、慢、错误、高 CPU、高内存请求，以及真实 Redis、Kafka、定时任务和 httpbingo 远程 HTTP 调用。`/api/demo/trace` 仍是明确标注的模拟嵌套树；其他入口使用真实客户端与本地依赖。监控 API 的匿名访问限制仅适用于本地体验。

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
- Kafka 首次启动时 broker 或 consumer group 可能需要一些时间完成协调；若接口仍显示待确认，可查 `/api/demo/kafka/status`。三个接口都会返回生产与消费 Trace ID 的匹配结果。
- `/api/demo/remote`：调用 `https://httpbingo.org/delay/1` 和 `/headers`，httpbingo 响应会回显 `X-Trace-Id`；树中应出现两个远程 HTTP 子调用。
- 定时任务启动 5 秒后执行，随后每 30 秒递增 Redis 计数并等待一次约 1 秒的 Redis 队列轮询；生成 `task DemoScheduledJob.countHeartbeat` 慢入口 Trace 及 Redis 子调用。

HTTP 请求的响应头也会返回 `X-Trace-Id`。打开 `/easy-observability/`，在“最近入口调用”或慢请求/错误请求列表中按该 ID 对照；Kafka 的 producer 和 consumer 是异步的独立调用入口，但共享相同 Trace ID。慢、错误 Kafka 示例可能同时让外层 HTTP 请求进入关注列表，这是因为接口会等待 consumer 完成。

如需更换 httpbingo 地址，可设置 `EASY_OBSERVABILITY_DEMO_HTTPBINGO_BASE_URL`。完成后关闭本地依赖：

```bash
docker compose -f examples/docker-compose.yml down
```
