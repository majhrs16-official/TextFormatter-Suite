# kernel — Module Loading & Dependency Resolution

> **Purpose**: Discovers, resolves, and loads `Module` implementations (defined in `core-api`) via Java SPI. Provides the module dependency graph and topological initialization order.

---

## 1. Responsibilities

- **Module discovery** — scans classpath for `META-INF/services/me.majhrs16.suite.api.Module`
- **Dependency resolution** — builds graph from `ModuleDescriptor` capabilities/requirements
- **Topological sorting** — determines initialization order respecting dependencies
- **Module instantiation** — creates instances and calls `initialize()` in order
- **Resolution status reporting** — `ResolutionResult` with `ResolutionStatus` (SUCCESS, MISSING_DEPENDENCY, CYCLE_DETECTED, etc.)

---

## 2. Non-Responsibilities

- **No business logic** — purely infrastructure for module system
- **No platform-specific code** — works with any `Module` implementation
- **No service wiring beyond module init** — `host` wires services after modules load
- **No configuration** — modules self-configure via their `initialize()`

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | Uses `Module`, `ModuleDescriptor`, `Capability`, `Requirement`, `SemVer` |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | Calls `ModuleLoader.load()` during bootstrap |
| `spigot-host` | Uses `ModuleLoader` for plugin module loading |
| `fabric-host` | Uses `ModuleLoader` for mod module loading |
| Test fixtures | `SpiFixtureModule` for testing |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `ModuleLoader` | Main entry point: `load(ClassLoader) → ResolutionResult` |
| `ModuleGraph` | Builds dependency graph, detects cycles, topological sort |
| `ModuleDescriptor` | Module metadata (from `core-api`) |
| `ResolutionResult` | Outcome: status + loaded modules + errors |
| `ResolutionStatus` | Enum: `SUCCESS`, `MISSING_DEPENDENCY`, `CYCLE_DETECTED`, `INVALID_DESCRIPTOR`, `INSTANTIATION_FAILED` |
| `Environment` | Runtime environment context passed to modules |

---

## 6. Data Flow

```text
Classpath (META-INF/services/me.majhrs16.suite.api.Module)
         ↓
ModuleLoader.load()
         ↓
ModuleGraph.build() → reads ModuleDescriptor from each Module
         ↓
ModuleGraph.resolve() → validates capabilities/requirements, checks SemVer
         ↓
Topological sort → initialization order
         ↓
Instantiate each Module → call Module.initialize(Environment)
         ↓
ResolutionResult { status, modules[], errors[] }
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `ModuleLoader.load(classLoader)` | `ModuleLoader.java:42` | `SuiteBootstrap.initialize()` (host), platform adapters |

---

## 8. Extension Points

- **Custom Module implementations** — implement `core-api` `Module` interface, declare in `META-INF/services/me.majhrs16.suite.api.Module`
- **Custom `Environment`** — extend `Environment` to provide additional context to modules

---

## 9. Exploration Path

```
1. ModuleLoader.java              → Main API, load() method
2. ModuleGraph.java               → Graph building, resolution, topological sort
3. ResolutionResult.java          → Result object with status & modules
4. ResolutionStatus.java          → Status enum
5. Environment.java               → Context passed to modules
6. test/ModuleLoaderTest.java     → Usage examples
7. test/ModuleGraphTest.java      → Graph resolution tests
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Defines `Module`, `ModuleDescriptor`, `Capability`, `Requirement`
- [host](../host/README.md) — Calls `ModuleLoader` during bootstrap
- [spigot-host](../spigot-host/README.md) — Uses module loading for plugin modules
- [fabric-host](../fabric-host/README.md) — Uses module loading for mod modules