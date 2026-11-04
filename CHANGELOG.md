# Changelog

## Unreleased

- Remove `easy.observability.web.allow-anonymous`. `ObservabilityAccessPolicy` now has `canView()` (built-in default: allow) and a default `canClear()` method (deny unless overridden). Clearing trace history requires both.
- Add `GET /api/easy-observability/permissions`; the dashboard hides the clear-history button unless clearing is allowed.
- The dashboard echoes Spring Security's `XSRF-TOKEN` cookie as `X-XSRF-TOKEN` on state-changing requests.

## 0.1.0-alpha.1 - 2026-10-30

- Focus the product on trace diagnosis for slow calls, errors, high CPU time, and high memory allocation.
- Improve cross-thread tracing so spans show their thread, timing relationship, and whether work was still running when the entry request ended.
- Expand the Spring Boot 3 and 4 demos with realistic remote-call, asynchronous, scheduled-job, CPU, and memory-allocation scenarios.
- Simplify trace configuration and update the UI and documentation to make captured resource measurements easier to interpret.
