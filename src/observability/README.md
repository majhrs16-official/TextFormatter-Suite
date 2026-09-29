# observability — Metrics, Health Checks & Debug Endpoints

> **Purpose**: Provides Prometheus metrics, health checks, and HTTP endpoints for debugging and monitoring.

---

## 1. Responsibilities

- **Metrics collection** — `MetricsCollector` gathers JVM, system, and application metrics
- **Health checks** — `HealthCheckRegistry` manages liveness/readiness probes
- **HTTP endpoints** — `MetricsEndpoint` (Prometheus), `DebugEndpoint` (debug info)
- **Module registration** — `ObservabilityModule` registers endpoints with host

---

## 2. Non-Responsibilities

- **No metrics storage** — exports to Prometheus via `/metrics` endpoint
- **No alerting** — external systems handle alerting
- **No platform-specific code** — pure Java, HTTP server agnostic

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `ModuleDescriptor` |
| `host` | Compile | Access to `SuiteHost` services for metrics |
| `textformatter` | Compile | Format debug output |
| `io.prometheus:simpleclient` | Compile | Prometheus metrics |
| `io.prometheus:simpleclient_common` | Compile | Common metrics utilities |
| `jackson-databind` | Compile | JSON serialization for debug endpoint |
| `kernel` | Test | Test fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Exposes `/metrics`, `/health`, `/debug` endpoints |
| `fabric-host` | Exposes `/metrics`, `/health`, `/debug` endpoints |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `Observability` | Main entry: `start()`, `stop()`, provides collectors |
| `MetricsCollector` | Collects: JVM (memory, threads, GC), CPU, custom counters |
| `HealthCheckRegistry` | Registers health checks: `liveness`, `readiness` |
| `MetricsEndpoint` | HTTP handler: `GET /metrics` → Prometheus format |
| `DebugEndpoint` | HTTP handler: `GET /debug` → JSON debug info (config, modules, services) |
| `ObservabilityModule` | `Module` registering endpoints |

---

## 6. Data Flow

```text
ObservabilityModule.initialize()
         ↓
MetricsCollector.start() → periodic collection
HealthCheckRegistry.register() → platform health checks
         ↓
HTTP server (platform-specific) mounts:
  - /metrics → MetricsEndpoint
  - /health → HealthCheckRegistry
  - /debug → DebugEndpoint
         ↓
Prometheus scrapes /metrics
Debug endpoint returns:
  - Config summary
  - Loaded modules
  - Service status
  - Queue sizes
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `ObservabilityModule` | `ObservabilityModule.java` | `ModuleLoader` → `SuiteBootstrap` |
| `MetricsEndpoint.handle()` | `MetricsEndpoint.java` | HTTP server |
| `DebugEndpoint.handle()` | `DebugEndpoint.java` | HTTP server |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom metrics | `MetricsCollector.registerGauge/Counter/Histogram(name, ...)` |
| Custom health checks | `HealthCheckRegistry.register(name, HealthCheck)` |
| Custom debug info | Extend `DebugEndpoint` to add sections |

---

## 9. Exploration Path

```
1. ObservabilityModule.java       → Module registration
2. Observability.java             → Main facade
3. MetricsCollector.java          → Metrics collection
4. HealthCheckRegistry.java       → Health check management
5. MetricsEndpoint.java           → Prometheus endpoint
6. DebugEndpoint.java             → Debug endpoint
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Module SPI
- [host](../host/README.md) — Provides `SuiteHost` for service introspection
- [textformatter](../textformatter/README.md) — Formats debug output
- [kernel](../kernel/README.md) — Loads module
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Mount HTTP endpoints

---

## 11. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **M-11** | MetricsEndpoint binds 0.0.0.0:9090 without auth | Now binds `127.0.0.1` by default via `textformattersuite.metrics.bind` property; `Observability.createDefault()` passes bind address |