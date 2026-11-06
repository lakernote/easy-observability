<p align="center">
  <img src="docs/images/meerkat-icon.png" alt="Meerkat observability icon" width="112">
</p>

# Meerkat

English | [简体中文](README.zh-CN.md)

**Embedded trace diagnostics and runtime metrics for Spring Boot 3/4.**

Meerkat helps application developers answer two questions quickly: where did this request spend its time and where did the error start, and how have the service's traffic, errors and dependency latency changed recently. Add one starter and you get trace timelines and the key runtime metrics inside your application, without deploying a separate observability platform first.

[![CI](https://github.com/lakernote/meerkat/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/lakernote/meerkat/actions/workflows/ci.yml)
[![Boot 3 Starter](https://img.shields.io/maven-central/v/io.github.lakernote/meerkat-spring-boot-3-starter?label=Boot%203%20Starter&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.lakernote/meerkat-spring-boot-3-starter)
[![Boot 4 Starter](https://img.shields.io/maven-central/v/io.github.lakernote/meerkat-spring-boot-4-starter?label=Boot%204%20Starter&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.lakernote/meerkat-spring-boot-4-starter)

## What it does

- **Single-request diagnosis:** follow the call timeline from an HTTP, scheduled-job or Kafka-consumer entry through JDBC, Redis, remote HTTP, Kafka and instrumented async tasks. Slow calls, errors, and entries whose CPU time or memory allocation exceed a threshold are kept in a diagnostic list.
- **Operation ranking:** every call, fast or slow, is counted per HTTP endpoint, SQL statement, remote call, Redis command, scheduled job and Kafka topic: count, error rate, average/P95/max and total latency, plus average CPU and allocation for entries. View the last hour, today, 7 days or 30 days; data survives restarts. Expand a row to see its count and latency trend.
- **Trends:** count, error and latency trends per call category, plus JVM/GC, CPU, threads, Hikari pool and executor charts to see when a problem started and where it is heading. Resource metrics reuse the Micrometer meters Spring Boot Actuator already binds.
- **Live diagnostics:** see how long in-flight requests have been running and which SQL or remote call they are stuck in, with thread stacks and CPU. Rank threads by CPU usage, detect deadlocks, merge identical stacks and download a thread dump. Data is read on demand; nothing is sampled in the background.
- **Lightweight:** Spring Boot auto-configuration, no external storage. Metrics are appended to daily text files in the storage directory; old data is compacted and deleted by retention and size limits. Your existing Micrometer exports (Prometheus, InfluxDB, ...) are untouched.

## Screenshots

The diagnostic list only keeps slow, failed or resource-heavy entries:

![Trace diagnostic list](docs/images/trace-list.png)

Open a record and follow the timeline to the root cause; repeated SQL in a loop is merged into one row and flagged as N+1:

![Trace detail timeline](docs/images/trace-detail-timeline.png)

The operation ranking finds the endpoints and SQL most worth optimizing by total time, errors or P95:

![Operation ranking](docs/images/stats-ranking.png)

Metric trends show the history of JVM, GC, connection pools, executors and more:

![Metric trends](docs/images/metrics-dashboard.png)

Live diagnostics show which step an in-flight request is stuck in right now:

![Live diagnostics](docs/images/live-requests.png)

## Getting started

Pick the starter that matches your Spring Boot major version (Java 17+, Spring Boot 3.5+ / 4.0+, Servlet web applications such as Spring MVC; WebFlux is not supported, see [Requirements](docs/guide.md#requirements)):

```xml
<!-- Spring Boot 3 -->
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>meerkat-spring-boot-3-starter</artifactId>
    <version>0.1.0-alpha.4</version>
</dependency>
```

```xml
<!-- Spring Boot 4 -->
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>meerkat-spring-boot-4-starter</artifactId>
    <version>0.1.0-alpha.4</version>
</dependency>
```

Tracing, runtime metrics and the embedded dashboard are on by default. The dashboard address is logged at startup (`Meerkat dashboard: http://localhost:8080/meerkat/`), or open it directly:

- Traces: `/meerkat/`
- Operations: `/meerkat/stats/`
- Metrics: `/meerkat/metrics/`
- Live: `/meerkat/live/`

The dashboard follows the browser language (Chinese or English) and can be switched in the top bar.

In production, protect the dashboard and API with your existing Spring Security setup or gateway; the starter has no accounts or login of its own. See the [configuration guide](docs/guide.md) for common settings, coverage and access control.

## Overhead

Time added on the business thread (JMH, Apple M4 Pro, JDK 21, single thread, average):

| Scenario | Added time |
| --- | --- |
| One entry (HTTP, scheduled job, Kafka consumer), excluding dependency calls | ~1.7 µs |
| Each dependency call (Redis, remote HTTP, Kafka send, ...) | ~0.3 µs |
| Each SQL statement | ~0.1 µs (outside a trace) / 0.2 µs (inside a trace) |

A request with 20 dependency calls adds about 7 µs in total: under 1% for requests taking 1 ms or more, under 0.1% for 10 ms requests. Archiving to disk happens on a background thread. See [Overhead](docs/guide.md#overhead) for the method and full results.

## Try it locally

The example covers JDBC, Redis, remote HTTP, Kafka, async executors and resource-heavy scenarios, so it produces traces and metrics right away:

```bash
docker compose -f examples/docker-compose.yml up -d --wait
mvn -pl examples/meerkat-example-spring-boot-3 -am package
java -jar examples/meerkat-example-spring-boot-3/target/meerkat-example-spring-boot-3-0.1.0-alpha.4.jar
```

Open `http://localhost:8083/` for the example entry page. See the [examples README](examples/README.md) for more scenarios and how to start the Boot 4 example.

## How it compares

| | Meerkat | JavaMelody | APMs such as SkyWalking / Pinpoint | Prometheus + Grafana |
|---|---|---|---|---|
| Deployment | In-app starter, no extra services | In-app dependency, no extra services | Java agent plus separate collector, storage and UI | Separate Prometheus and Grafana |
| Per-request call tree | Yes, for slow, failed or resource-heavy entries, including async threads, SQL, Redis, remote calls and Kafka | No, focuses on aggregates | Yes, including cross-service traces | No |
| Aggregates and trends | Operation ranking (P95, error rate, CPU, allocation) plus resource trends | Yes, covering HTTP, SQL, Spring beans and more | Yes | Yes, with the strongest querying and alerting |
| Live diagnostics | Current step of in-flight requests, thread CPU, deadlocks, thread dump | Current requests, threads, heap histogram and more | Some products | No |
| Best for | Monoliths or a few services where you want to see what is slow or failing inside the app | Runtime overview of classic Java web apps | Distributed tracing across many services, centralized operations | Centralized metrics and alerting |

Meerkat does not replace a central APM or metrics platform. It leaves your existing Micrometer exports alone and works alongside them. With Micrometer Tracing on the classpath, Meerkat uses the same trace ID, so you can jump from a Meerkat trace to the same trace in Zipkin, Jaeger or Tempo.
