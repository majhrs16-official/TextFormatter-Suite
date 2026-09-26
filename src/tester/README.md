# tester — Testing Utilities (Spigot-Dependent)

> **Purpose**: Provides Spigot-dependent testing utilities for integration tests.

---

## 1. Responsibilities

- **Spigot test fixtures** — mock Bukkit objects for testing
- **Test service** — `TestService` for integration test helpers
- **Test module** — `TesterModule` registers test utilities

---

## 2. Non-Responsibilities

- **No production code** — testing only
- **Spigot-dependent** — requires Spigot API

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module` SPI |
| Spigot API | Compile | Bukkit mocking |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Integration tests (with exclusion in shadow JAR) |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TesterModule` | `Module` registering test utilities |
| `TestService` | Integration test helpers |

---

## 6. Data Flow

Testing only — no production flow.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `TesterModule` | `TesterModule.java` | `ModuleLoader` → `SuiteBootstrap` (test scope) |

---

## 8. Exploration Path

```
1. TesterModule.java              → Module registration
2. TestService.java               → Test helpers
3. spigot-host/build.gradle       → Dependency exclusion config
```

---

## 9. Related Modules

- [core-api](../core-api/README.md) — Module SPI
- [kernel](../kernel/README.md) — Loads module
- [spigot-host](../spigot-host/README.md) — Consumer (test scope)