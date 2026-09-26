# performance — Profiling & Optimization

> **Purpose**: Provides performance profiling, hotspot detection, and optimization utilities.

---

## 1. Responsibilities

- **Hotspot detection** — `HotspotDetector` identifies performance bottlenecks
- **Profiling** — `PerformanceProfiler` collects method-level timing
- **Cache optimization** — `CacheOptimizer` tunes cache sizes
- **Memory optimization** — `MemoryOptimizer` analyzes heap usage

---

## 2. Non-Responsibilities

- **No metrics export** — `observability` handles Prometheus metrics
- **No platform-specific code** — pure Java

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module` SPI |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Performance monitoring |
| `fabric-host` | Performance monitoring |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `HotspotDetector` | Detects CPU/memory hotspots via sampling |
| `PerformanceProfiler` | Method-level profiling (async) |
| `CacheOptimizer` | Recommends cache size adjustments |
| `MemoryOptimizer` | Analyzes heap, suggests GC tuning |

---

## 6. Data Flow

```text
PerformanceProfiler.start() → async sampling
         ↓
HotspotDetector.analyze() → hotspot report
         ↓
CacheOptimizer/MemoryOptimizer → recommendations
         ↓
Observability exports metrics
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| Module (if any) | — | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Exploration Path

```
1. HotspotDetector.java           → Hotspot detection
2. PerformanceProfiler.java       → Profiling
3. CacheOptimizer.java            → Cache tuning
4. MemoryOptimizer.java           → Memory analysis
```

---

## 9. Related Modules

- [core-api](../core-api/README.md) — Module SPI
- [observability](../observability/README.md) — Metrics export
- [host](../host/README.md) — Integration
- [kernel](../kernel/README.md) — Loads module