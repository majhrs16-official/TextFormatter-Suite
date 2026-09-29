# inworld — In-World Integration

> **Purpose**: Provides in-world (in-game) integration features such as signs, chests, books with WORLD/RADIUS delivery, click/hover buttons, caching, and glossary. **Compiles with Paper API 1.21.4**.

---

## 1. Responsibilities

- **In-world message display** — renders messages as holograms, signs, or entities
- **Module registration** — `InWorldModule` registers integration

---

## 2. Non-Responsibilities

- **No core formatting/routing** — delegates to `textformatter`/`iflow`
- **No platform-specific rendering** — platform adapters handle rendering

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `Message`, `Actor` |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | In-world displays via holograms/entities |
| `fabric-host` | In-world displays via Fabric equivalents |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `InWorldModule` | `Module` registering in-world integration |

---

## 6. Data Flow

```text
MessageDispatcher.dispatch()
         ↓
InWorldModule hooks into pipeline
         ↓
Renders message in-world (platform-specific)
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `InWorldModule` | `InWorldModule.java` | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Exploration Path

```
1. InWorldModule.java             → Module registration
2. spigot-host: SpigotActorDirectory.java → Platform rendering
3. fabric-host: FabricActorDirectory.java → Platform rendering
```

---

## 9. Related Modules

- [core-api](../core-api/README.md) — Module SPI, Message types
- [host](../host/README.md) — Pipeline integration
- [kernel](../kernel/README.md) — Loads module
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Platform rendering