# loadtest — Load Testing Utilities

> **Purpose**: Provides utilities for load testing the TextFormatter Suite pipeline.

---

## 1. Responsibilities

- **Load generation** — simulates high-volume message throughput
- **Performance measurement** — latency, throughput, error rates
- **Stress testing** — identifies bottlenecks under load

---

## 2. Non-Responsibilities

- **No production code** — testing only
- **No platform-specific code** — pure Java

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| *(minimal)* | — | Testing utilities |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `fabric-host` | Load testing integration |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| *(various)* | Load test runners, generators, reporters |

---

## 6. Exploration Path

```
1. Explore source files directly → Load test utilities
```

---

## 7. Related Modules

- [performance](../performance/README.md) — Profiling during load tests
- [observability](../observability/README.md) — Metrics during load tests
- [fabric-host](../fabric-host/README.md) — Consumer