# Changelog

## 0.1.0-alpha.4

This preview contains breaking configuration and API changes. Data written by earlier previews is not migrated.

### Breaking changes

- Dashboard access: `ObservabilityAccessPolicy` now has `canView()` and `canClear()` (denied by default); clearing requires both. `easy.observability.web.allow-anonymous` is removed.
- Storage: `metrics.storage-directory` and `trace.diagnostic-trace-file` are replaced by a single `easy.observability.storage.directory`, defaulting to `./data/easy-observability/{spring.application.name}`. The directory is locked by one process; another instance using it falls back to memory-only storage with a warning.
- Configuration names and types:
  - `trace.http-slow-threshold-ms`, `job-slow-threshold-ms`, `kafka-consumer-slow-threshold-ms` and `cpu-threshold-ms` become `Duration` properties without the `-ms` suffix (`500ms`, `1s`; plain numbers are milliseconds).
  - `metrics.retention-days` becomes `metrics.retention` (`30d`, whole days from `1d` to `365d`).
  - `trace.diagnostic-max-bytes` and `metrics.max-storage-bytes` become `trace.diagnostic-max-size` and `metrics.max-storage-size`.
- The Trace ID is written to the `easyTraceId` MDC key so it coexists with Micrometer Tracing's `traceId`.
- Public API is limited to 11 documented types; everything else lives in `internal` packages and may change without notice. Internal beans no longer back off via `@ConditionalOnMissingBean`.
- The starters no longer depend on Jackson; the diagnostic archive uses a built-in codec.
- The starters now depend on `spring-boot-starter-actuator`. Resource metrics are read from the meters Spring Boot already binds instead of a separate RRD history: `metrics.step` is removed, RRD files (`common-metrics-*`) are deleted on startup, and the rrd4j dependency is gone. The old metrics API `GET /api/easy-observability/metrics?period=` is replaced by the endpoints below.

### Fixes

- Reactive (WebFlux) HTTP requests no longer create entry traces; only Servlet applications are supported.
- Requests for any page under `/easy-observability/` (including the metrics page) are no longer recorded as application traces.
- `TraceContextTaskDecorator` is applied together with application task decorators (composed on Spring Boot 4; on Spring Boot 3 an existing application decorator keeps precedence).
- Removed an exception thrown on every non-HTTP observation while parsing a status code (20-dependency request: 21.6 µs → 6.9 µs).

### New

- Runtime metrics, grouped under "运行指标" in the navigation:
  - Call ranking page `/easy-observability/stats/`: count, errors, mean, P95, max and total time for every HTTP endpoint, SQL template, remote call, Redis command, scheduled job and Kafka topic, over the last hour, today, 7 days or 30 days; expanding a row shows its trend. Lock-free in memory (about 50 ns per call).
  - Trends page `/easy-observability/metrics/`: call trends per category plus JVM, CPU, thread, Hikari, executor and Tomcat meters from Micrometer, and custom meters listed in `metrics.include`.
  - History is appended to `metrics/operations-yyyyMMdd.tsv` (10-minute slices) and `metrics/resources-yyyyMMdd.tsv` (1-minute samples); days older than two days are compacted to hourly / 10-minute resolution. Limited by `metrics.retention` and `metrics.max-storage-size`; falls back to memory when the directory is unavailable.
  - `server.tomcat.mbeanregistry.enabled` defaults to `true` (lowest precedence) so Tomcat thread meters are available.
  - Configuration: `metrics.enabled`, `metrics.max-operations`, `metrics.retention`, `metrics.max-storage-size`, `metrics.include`.
  - API under `/api/easy-observability/metrics`: `GET /operations`, `/operations/trend`, `/operations/trends`, `/resources`, `/storage`; `POST /operations/reset` (requires `canClear()`).
- The Trace Explorer accepts `?q=` and `?source=` so other pages can link to a filtered list.

### Other

- `GET /api/easy-observability/permissions`; the dashboard hides the clear button unless clearing is allowed and sends Spring Security's XSRF token.
- JMH benchmarks, a JaCoCo coverage gate, a JDK 17/21/25 CI matrix, Dependabot and issue/PR templates.

## 0.1.0-alpha.3 and earlier

Initial previews: embedded trace diagnosis for slow calls, errors, high CPU time and high memory allocation, plus local RRD metric history, for Spring Boot 3 and 4.
