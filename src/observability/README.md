# observability — Metrics, Health Checks & Debug Endpoints

> **Purpose**: Provides Prometheus metrics and health checks as an activatable package. The host debug endpoint lives in `host.debug` because it needs host state.

---

## 1. Responsibilities

- **Metrics collection** — `MetricsCollector` gathers JVM, system, and application metrics
- **Health checks** — `HealthCheckRegistry` manages liveness/readiness probes
- **Metrics endpoint** — `MetricsEndpoint` exposes `/metrics` and `/health`
- **Metrics facade** — installs the neutral `api.metrics.MetricsSink` while active
- **Package registration** — `ObservabilityActivator` publishes the registry and facade in the service registry

---

## 2. Non-Responsibilities

- **No metrics storage** — exports to Prometheus via `/metrics` endpoint
- **No alerting** — external systems handle alerting
- **No debug endpoint** — `DebugEndpoint` lives in `host.debug` (host adapter)
- **No platform-specific code** — pure Java, HTTP server agnostic

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `ModuleDescriptor`, `Metrics` facade |
| `sync-common` | Compile | `ObservabilityConfig` YAML reader (`DEPENDENCY` package) |
| `io.prometheus:simpleclient` | Compile | Prometheus metrics (host classpath until F8) |
| `io.prometheus:simpleclient_common` | Compile | Common metrics utilities (host classpath until F8) |
| `junit` / `assertj` / `mockito` | Test | Endpoint and health tests |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| module runtime | Activates `ObservabilityActivator` from the `observability` ZIP |
| `host` / platform adapters | Resolve `HealthCheckRegistry` and `Observability` from the service registry |
| `api.metrics.Metrics` | Modules record sync metrics through the installed sink |
| `spigot-host` / `fabric-host` | Own `host.debug.DebugEndpoint` and mount `/debug` |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `Observability` | Metrics facade: `start()`, `stop()`, health summary, metrics sink installation |
| `ObservabilityActivator` | Package lifecycle: publishes `HealthCheckRegistry` and `Observability` |
| `ObservabilityConfig` | Reads `config/observability/config.yml` |
| `MetricsCollector` | Collects: JVM (memory, threads, GC), CPU, custom counters |
| `HealthCheckRegistry` | Registers health checks: `liveness`, `readiness` |
| `MetricsEndpoint` | HTTP handler: `GET /metrics` → Prometheus format, `GET /health` |
| `DebugEndpoint` | Host adapter in `host.debug`: `GET /debug` JSON with host state |
| `ObservabilityModule` | Legacy kernel descriptor publishing the `observability` capability |

---

## 6. Data Flow

```text
ObservabilityActivator.start()
         ↓
ObservabilityConfig.loadFromConfigDir()
HealthCheckRegistry.register("jvm"), ("threads")
         ↓
Observability.start()
  - MetricsEndpoint binds metricsBind:metricsPort
  - Metrics.install(MetricsCollector::recordSyncSent)
         ↓
Prometheus scrapes /metrics
Health probes call /health
Host adapter mounts /debug from host.debug.DebugEndpoint
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `ObservabilityActivator` | `ObservabilityActivator.java` | Module runtime |
| `ObservabilityModule` | `ObservabilityModule.java` | Legacy kernel descriptor |
| `MetricsEndpoint.handle()` | `MetricsEndpoint.java` | HTTP server |
| `DebugEndpoint.handle()` | `../host/.../host/debug/DebugEndpoint.java` | Host HTTP server |

---

## 7b. Configuration

`config/observability/config.yml`:

```yaml
enabled: true
metricsPort: 9090   # 0 = ephemeral
metricsBind: "127.0.0.1"
```

If `enabled` is false the activator still publishes the health registry, but no
metrics endpoint or `Observability` facade is created.

```bash
./gradlew :src:observability:test
```

`PackageRuntimeTest.installsObservabilityPackageAndServesHealthEndpoint`
installs the real `sync-common` and `observability` packages on a free port,
checks `GET /health`, then unloads the package and asserts it is no longer
active.

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom metrics | `MetricsCollector.registerGauge/Counter/Histogram(name, ...)` |
| Custom health checks | `HealthCheckRegistry.register(name, HealthCheck)` |
| Custom debug info | Extend `host.debug.DebugEndpoint` (host adapter) |

---

## 9. Exploration Path

```
1. ObservabilityActivator.java   → Package lifecycle
2. Observability.java            → Metrics facade and health summary
3. ObservabilityConfig.java      → Module config schema
4. MetricsCollector.java         → Metrics collection
5. HealthCheckRegistry.java      → Health check management
6. MetricsEndpoint.java          → Prometheus and health endpoint
7. host/debug/DebugEndpoint.java → Host debug adapter
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Module SPI and `api.metrics` facade
- [sync-common](../sync-common/README.md) — YAML reader (`DEPENDENCY`)
- [host](../host/README.md) — Owns `host.debug.DebugEndpoint`
- [kernel](../kernel/README.md) — Loads legacy module descriptor
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Mount HTTP endpoints

---

## 11. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **M-11** | MetricsEndpoint binds 0.0.0.0:9090 without auth | Now binds `127.0.0.1` by default via `textformattersuite.metrics.bind` property; `Observability.createDefault()` passes bind address |