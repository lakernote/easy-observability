# 示例应用

- `easy-observability-example-spring-boot-3`：Spring Boot 3，默认 `http://localhost:8083/`
- `easy-observability-example-spring-boot-4`：Spring Boot 4，默认 `http://localhost:8084/`

主页按 JDBC、Redis、远程 HTTP、Kafka 和异步线程池分组。每个依赖提供正常、慢、错误场景，便于在 Trace 页面核对耗时节点和异常堆栈。示例不额外记录业务 Metrics。

## 启动

H2 是内嵌数据库，不需要额外服务。Redis 和 Kafka 示例需要先启动本地依赖：

```bash
docker compose -f examples/docker-compose.yml up -d --wait
```

然后在仓库根目录选择一个 Spring Boot 版本启动：

```bash
mvn -pl examples/easy-observability-example-spring-boot-3 -am package
java -jar examples/easy-observability-example-spring-boot-3/target/easy-observability-example-spring-boot-3-0.1.0-alpha.0.jar

mvn -pl examples/easy-observability-example-spring-boot-4 -am package
java -jar examples/easy-observability-example-spring-boot-4/target/easy-observability-example-spring-boot-4-0.1.0-alpha.0.jar
```

打开应用主页，再打开 `/easy-observability/` 查看最近 Trace。Boot 3 示例可用 `http://localhost:8083/`，Boot 4 示例可用 `http://localhost:8084/`。

## 依赖调用场景

所有场景都在一个 HTTP Trace 中查看入口及依赖调用树。

| 依赖 | 正常 | 慢 | 错误 | 场景说明 |
| --- | --- | --- | --- | --- |
| JDBC / H2 | `/api/demo/db?mode=normal` | `/api/demo/db?mode=slow` | `/api/demo/db?mode=error` | 执行真实 `JdbcTemplate` 写入和查询；慢场景在查询边界模拟 900 ms 等待；错误场景重复插入主键以产生真实 SQL 约束异常 |
| Redis | `/api/demo/redis?mode=normal` | `/api/demo/redis?mode=slow` | `/api/demo/redis?mode=error` | 执行真实 SET/GET；慢场景使用 1 秒 BLPOP 等待；错误场景对字符串键执行列表命令以触发 `WRONGTYPE` |
| 远程 HTTP | `/api/demo/remote?mode=normal` | `/api/demo/remote?mode=slow` | `/api/demo/remote?mode=error` | 调用 httpbingo 的 `/anything`、`/delay/1` 和 `/status/500`，分别查看正常、慢和 HTTP 500 错误 Span |
| Kafka | `/api/demo/kafka` | `/api/demo/kafka/slow` | `/api/demo/kafka/error` | 发送真实消息并等待 listener 消费；响应包含 producer/consumer Trace ID 是否一致 |

Kafka 首次启动时 broker 和 consumer group 需要一些时间完成协调。若接口返回尚未消费，可查询 `/api/demo/kafka/status`。慢和错误场景只用于本地诊断演示，Kafka 错误消息配置为不重试，避免生成重复错误 Trace。

远程 HTTP 场景需要访问 httpbingo。可用 `EASY_OBSERVABILITY_DEMO_HTTPBINGO_BASE_URL` 指定兼容的服务地址。

## 异步线程池上下文传播

主页的“异步线程池上下文传播对照”提供 `normal`、`slow`、`error` 三种任务，以及带/不带 Trace 包装器两种线程池：

- `propagation=wrapped`：线程池只需配置一次公共 `TraceContextTaskDecorator`。任务自动继承父 Trace，并以 `executor.task` 节点记录耗时、慢状态和异常；不需要在任务代码里手动包装。接口会等待任务结束，保证父 Trace 仍处于活动状态。
- `propagation=unwrapped`：线程池不使用包装器。任务状态响应显示上下文未传播，任务不会出现在请求 Trace 调用树中。
- `/api/demo/executor/{taskId}` 可查看任务执行状态；提交入口也会等待任务完成并返回任务状态、worker 线程以及上下文传播结果。

任务节点使用通用名称 `executor.task`，标签显示执行线程名；慢阈值由 `easy.observability.trace.executor-task-slow-threshold-ms` 控制。Trace 记录不注册 Timer 或其他 Micrometer meter。Spring 的 `Future` 会吸收任务异常，装饰器会在任务结束后读取已完成 Future 的异常结果；任务内部自行捕获且不再抛出的异常无法由边界装饰器判断。

## Redis 与 RestClient 自动接入

两个示例均启用 Lettuce Observation。Redis 命令由客户端自动生成命令级 Trace Span，业务代码不需要用 `TraceBlock` 包围每次调用。Spring Boot 4 使用 Boot 自带的 Lettuce Micrometer 集成；Spring Boot 3 由 Starter 的 Lettuce `ClientResourcesBuilderCustomizer` 接入。示例保留的 Redis 正常、慢、错误场景都是真实 Redis 命令。

示例的 HTTP 客户端通过 Spring 管理的 `RestClient.Builder` 创建。Starter 自动添加 Trace ID 拦截器，Boot 的 Observation 负责远程调用 Span。`RestClient.create()` 或单独调用 `RestClient.builder()` 创建的客户端绕过 Spring 定制器，不会自动传递 Trace ID。

## 完整链路与事务回滚

- `/api/demo/real-flow` 在一个请求中执行 H2 写入/查询、Redis SET/GET、远程 HTTP 和 Kafka producer，适合检查跨依赖 Trace 串联。
- `/api/demo/transaction/error` 在 Spring 事务内写入后抛出异常，适合检查 SQL、事务回滚结果和根因堆栈。

示例将 `easy.observability.trace.resource-sampling-enabled` 设为 `true`，Trace 详情会显示线程 CPU 时间和线程分配量；生产默认关闭。这里的分配量是该线程在请求期间新分配的对象字节数，不代表进程当前堆内存占用。HTTP 服务依赖失败仍会作为 Trace 错误保存；Redis 和 Kafka 的场景需要本地 Docker 依赖可用。

完成后可关闭本地依赖：

```bash
docker compose -f examples/docker-compose.yml down
```
