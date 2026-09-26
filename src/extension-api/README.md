# extension-api — Extension System API

> **Purpose**: Defines the extension system for third-party plugins/mods to extend TextFormatter Suite functionality.

---

## 1. Responsibilities

- **Extension contract** — `Extension` interface: `onEnable()`, `onDisable()`, `getMetadata()`
- **Extension context** — `ExtensionContext` provides access to host services
- **Extension manager** — `ExtensionManager` loads and manages extensions
- **Extension metadata** — `ExtensionMetadata` (id, version, dependencies, author)
- **Extension configuration** — `ExtensionConfig` for per-extension settings

---

## 2. Non-Responsibilities

- **No implementation** — only interfaces
- **No extension discovery** — platform adapters handle discovery
- **No platform-specific code** — pure Java

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `ModuleDescriptor` concepts |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `example-extension` | Example implementation |
| `spigot-host` | Loads Bukkit extensions |
| `fabric-host` | Loads Fabric extensions |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `Extension` | Interface: `onEnable(ctx)`, `onDisable()`, `getMetadata()` |
| `ExtensionContext` | Provides: `SuiteHost`, `Config`, `Logger`, `EventBus` |
| `ExtensionManager` | Loads extensions, manages lifecycle |
| `ExtensionMetadata` | Extension descriptor: id, version, dependencies |
| `ExtensionConfig` | Per-extension configuration |

---

## 6. Data Flow

```text
Platform adapter discovers extension JARs
         ↓
ExtensionManager.loadExtension(jar) → reads metadata
         ↓
ExtensionManager.enableExtension(id)
         ↓
Extension.onEnable(context) → registers listeners, commands, services
         ↓
Extension active
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `ExtensionManager.loadExtension()` | `ExtensionManager.java` | Platform adapter |
| `Extension.onEnable()` | `Extension.java` | `ExtensionManager` |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom extension | Implement `Extension`, package as JAR with `extension.yml` |
| Custom services | Access `SuiteHost` via `ExtensionContext` |
| Custom config | Define `ExtensionConfig` schema |

---

## 9. Exploration Path

```
1. Extension.java                 → Extension interface
2. ExtensionContext.java          → Context provided to extensions
3. ExtensionManager.java          → Manager interface
4. ExtensionMetadata.java         → Metadata model
4. ExtensionConfig.java           → Config model
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Base module concepts
- [example-extension](../example-extension/README.md) — Example implementation
- [host](../host/README.md) — Provides `SuiteHost` to context
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Extension hosts