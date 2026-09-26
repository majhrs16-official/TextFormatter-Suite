# example-extension — Example Extension Implementation

> **Purpose**: Demonstrates how to implement a TextFormatter Suite extension using `extension-api`.

---

## 1. Responsibilities

- **Example extension** — implements `Extension` interface
- **Shows best practices** — lifecycle, config, service access

---

## 2. Non-Responsibilities

- **No production features** — example only

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `extension-api` | Compile | Extension interfaces |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| — (leaf) | Example only |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `ExampleExtension` | Implements `Extension` with sample logic |

---

## 6. Data Flow

```text
ExtensionManager.loadExtension(jar)
         ↓
ExampleExtension.onEnable(context)
         ↓
Access SuiteHost via context
         ↓
Register listeners, commands, etc.
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `ExampleExtension` | `ExampleExtension.java` | `ExtensionManager` |

---

## 8. Exploration Path

```
1. ExampleExtension.java          → Extension implementation
2. extension.yml                  → Extension metadata
3. extension-api/Extension.java   → Interface being implemented
```

---

## 9. Related Modules

- [extension-api](../extension-api/README.md) — API being implemented
- [host](../host/README.md) — `SuiteHost` via context
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Extension hosts