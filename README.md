# Easy Observability

**Spring Boot 3/4 内嵌式 Trace 诊断与运行指标。**

Easy Observability 帮助应用开发者快速回答两个问题：一次请求慢在哪里、异常从哪里开始；服务的请求量、错误和依赖耗时最近有什么变化。引入一个 Starter，即可在应用内查看 Trace 时间线和常用运行指标，不必先部署一套独立观测平台。

[![CI](https://github.com/lakernote/easy-observability/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/lakernote/easy-observability/actions/workflows/ci.yml)
[![Boot 3 Starter](https://img.shields.io/maven-central/v/io.github.lakernote/easy-observability-spring-boot-3-starter?label=Boot%203%20Starter&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.lakernote/easy-observability-spring-boot-3-starter)
[![Boot 4 Starter](https://img.shields.io/maven-central/v/io.github.lakernote/easy-observability-spring-boot-4-starter?label=Boot%204%20Starter&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.lakernote/easy-observability-spring-boot-4-starter)

## 解决什么问题

- **单次请求定位：** 从 HTTP、定时任务或 Kafka 消费入口查看调用时间线，串联 JDBC、Redis、远程 HTTP、Kafka 和已接入的异步任务；慢调用、错误及 CPU 时间或内存分配量超阈值的入口会进入诊断列表。
- **调用排行：** 不论快慢，按 HTTP 接口、SQL、远程调用、Redis 命令、定时任务和 Kafka 统计次数、错误率、平均/P95/最大耗时和总耗时，入口另有平均 CPU 和内存分配量，可看最近 1 小时、今天、7 天和 30 天，重启不丢；展开一行查看它的次数和耗时趋势。
- **趋势查看：** 查看各类调用的次数、错误和耗时趋势，以及 JVM/GC、CPU、线程、Hikari 连接池、线程池等资源曲线，识别问题出现的时间和变化方向。资源指标直接复用 Spring Boot Actuator 已绑定的 Micrometer 指标。
- **实时诊断：** 查看此刻正在执行的请求已经跑了多久、卡在哪个 SQL 或远程调用、线程堆栈和 CPU；按 CPU 使用率排出线程、检测死锁、合并相同堆栈，并可下载线程 Dump。按需读取，不在后台采样。
- **轻量接入：** 通过 Spring Boot Starter 自动配置，不需要外部存储。指标按天写入存储目录下的文本文件，旧数据自动压缩、按保留期和容量上限删除；应用现有的 Micrometer 导出（例如 Prometheus、InfluxDB）不受影响。

## 界面预览

诊断列表聚焦需要排查的入口：

![Trace 诊断列表](docs/images/trace-list.png)

打开一条记录查看入口资源、线程泳道和依赖调用的相对时序：

![Trace 详情时间线](docs/images/trace-detail-timeline.png)

指标趋势页面查看调用和资源的历史曲线：

![指标趋势](docs/images/metrics-dashboard.png)

## 快速接入

选择与应用 Spring Boot 主版本匹配的 Starter（Java 17+，Spring Boot 3.5+ / 4.0+，Spring MVC 等 Servlet Web 应用；WebFlux 不支持，见[运行环境](docs/guide.md#运行环境)）：

```xml
<!-- Spring Boot 3 -->
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>easy-observability-spring-boot-3-starter</artifactId>
    <version>0.1.0-alpha.4</version>
</dependency>
```

```xml
<!-- Spring Boot 4 -->
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>easy-observability-spring-boot-4-starter</artifactId>
    <version>0.1.0-alpha.4</version>
</dependency>
```

默认启用 Trace、运行指标和嵌入页面。应用启动后访问：

- Trace 诊断：`/easy-observability/`
- 调用排行：`/easy-observability/stats/`
- 指标趋势：`/easy-observability/metrics/`
- 实时诊断：`/easy-observability/live/`

生产环境请通过应用现有的 Spring Security 或网关保护页面和 API；Starter 不创建独立账号或登录系统。常用配置、采集范围和访问控制说明见[接入与配置指南](docs/guide.md)。

## 本地体验

示例包含 JDBC、Redis、远程 HTTP、Kafka、异步线程池及资源消耗场景，可直接生成 Trace 和运行指标：

```bash
docker compose -f examples/docker-compose.yml up -d --wait
mvn -pl examples/easy-observability-example-spring-boot-3 -am package
java -jar examples/easy-observability-example-spring-boot-3/target/easy-observability-example-spring-boot-3-0.1.0-alpha.4.jar
```

打开 `http://localhost:8083/` 查看示例入口。更多场景和 Boot 4 启动方式见[示例说明](examples/README.md)。
