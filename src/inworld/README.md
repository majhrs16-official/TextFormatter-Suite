# inworld — Neutral In-World Domain

> **Purpose**: Owns the platform-neutral in-world domain: sign/container/book message construction, sign channel resolution, WORLD/RADIUS player queries, click/hover actions, glossary and caches. It never imports Paper, Bukkit, Fabric or host internals.

---

## 1. Responsibilities

- **Sign channels** — resolve `sign.<name>` channels by `signLocation` and build `SIGN` messages per non-blank line
- **Container channels** — build `CONTAINER` messages for chests, barrels and shulkers
- **Book channels** — build `BOOK` messages from written-book titles and pages
- **WORLD/RADIUS delivery** — delegate player lookup to `InWorldPlatform`
- **Click/hover actions** — delegate `open_url`, `run_command`, `suggest_command`, `change_page` and `copy_to_clipboard`
- **Glossary and caches** — hover glossary, sign/container cache and per-player radius data
- **Runtime activation** — `InWorldActivator` publishes `InWorldHandler` and requires `channel-api`

---

## 2. Non-Responsibilities

- **No platform API** — no Bukkit, Paper, Fabric or Adventure imports in `src/main`
- **No event listeners** — the host adapter owns `SignChangeEvent`/`PlayerInteractEvent`
- **No formatting or delivery** — `InWorldPlatform.format(...)` and `MessageDispatch` stay in the host
- **No routing policy** — `iflow-vm` decides routing; this package only builds in-world messages

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `ChannelRegistry`, `Message`, `Actor`, `ModuleActivator` |
| `kernel` | Test | Module test harness |

The package requires the `channel-api` capability and provides `inworld-signs`, `inworld-containers`, `inworld-books`, `channel-world`, `channel-radius` and `click-hover`.

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | `SpigotInWorldListener` extracts Bukkit events and implements `InWorldPlatform` |
| `fabric-host` | Own Fabric listener; not wired to this package yet |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `InWorldHandler` | Neutral domain logic and message construction |
| `InWorldPlatform` | Port for formatting, players and click actions |
| `InWorldActivator` | Publishes the handler and declares capabilities |

---

## 6. Data Flow

```text
SignChangeEvent / PlayerInteractEvent (host adapter)
         ↓
SpigotInWorldListener extracts actor, world, coordinates and content
         ↓
InWorldHandler builds Message + resolves channel
         ↓
MessageDispatcher → textformatter/iflow-vm → sinks
         ↓
SpigotInWorldListener formats the sign line through InWorldPlatform.format(...)
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `InWorldActivator` | `InWorldActivator.java` | `ModuleLoader` / `PackageRuntime` |
| `SpigotInWorldListener` | `spigot-host` | Bukkit plugin manager |

---

## 8. Exploration Path

```
1. InWorldHandler.java               → domain logic and message construction
2. InWorldPlatform.java              → platform port
3. InWorldActivator.java             → package activation
4. spigot-host: SpigotInWorldListener.java → Bukkit event extraction
```

---

## 9. Related Modules

- [core-api](../core-api/README.md) — channels, messages and lifecycle
- [textformatter](../textformatter/README.md) — provides `channel-api` and rendering
- [spigot-host](../spigot-host/README.md) — Spigot adapter
- [fabric-host](../fabric-host/README.md) — Fabric adapter (own listener)
