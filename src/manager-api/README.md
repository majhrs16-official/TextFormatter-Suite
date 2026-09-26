# manager-api — Module Manager API

> **Purpose**: Defines the API for remote module management — coordinates, descriptors, and lifecycle operations.

---

## 1. Responsibilities

- **Module coordinates** — `ModuleCoordinate` (group:artifact:version)
- **Module descriptors** — `ModuleDescriptor` (remote representation)
- **Lifecycle management** — `ModuleLifecycle` (install, update, uninstall, enable, disable)
- **Environment abstraction** — `Environment` for manager context

---

## 2. Non-Responsibilities

- **No implementation** — only interfaces and data types
- **No network code** — `manager-impl` handles GitHub API
- **No module loading** — `kernel` handles local loading

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `ModuleDescriptor`, `SemVer` |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `manager-impl` | Implements the API |
| `spigot-host` | Uses manager for remote module loading |
| `fabric-host` | Uses manager for remote module loading |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `ModuleCoordinate` | GAV coordinate: `group:artifact:version` |
| `ModuleDescriptor` | Remote module metadata (extends core-api descriptor) |
| `ModuleLifecycle` | Interface: `install()`, `update()`, `uninstall()`, `enable()`, `disable()` |
| `Environment` | Manager runtime environment |

---

## 6. Data Flow

API only — no runtime flow. `manager-impl` implements these interfaces.

---

## 7. Entry Points

None — API only.

---

## 8. Exploration Path

```
1. ModuleCoordinate.java          → Coordinate model
2. ModuleDescriptor.java          → Descriptor model
3. ModuleLifecycle.java           → Lifecycle interface
4. Environment.java               → Environment context
```

---

## 9. Related Modules

- [core-api](../core-api/README.md) — Base `ModuleDescriptor`, `SemVer`
- [manager-impl](../manager-impl/README.md) — Implementation
- [kernel](../kernel/README.md) — Local module loading
- [host](../host/README.md) — Integration point