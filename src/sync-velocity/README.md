# sync-velocity — Velocity Proxy Sync Sink

> **Purpose**: Implements `SyncSink` SPI for Velocity proxy plugin message channel. Sends messages across servers connected to the same Velocity proxy.

---

## 1. Responsibilities

- **Velocity plugin message channel** — uses `PluginMessageChannel` for cross-server communication
- **Module registration** — `SyncVelocityModule` registers sink
- **Fabric-only** — only used by `fabric-host` (Velocity is a proxy, not a Minecraft platform)

---

## 2. Non-Responsibilities

- **No standalone proxy** — requires Velocity proxy with plugin
- **No message routing** — `iflow` decides what to sync
- **No Bukkit/Spigot support** — Fabric/Velocity only

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `Message` SPIs |
| Velocity API | CompileOnly | `PluginMessageChannel`, `ProxyServer` |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `fabric-host` | Syncs across Velocity-connected servers |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `VelocitySink` | `SyncSink` impl: sends via `PluginMessageChannel` |
| `SyncVelocityModule` | `Module` registering sink |

---

## 6. Data Flow

```text
SyncSink.send(message)
         ↓
VelocitySink: encode message to byte[]
         ↓
ProxyServer.getChannel(PluginMessageChannel).send(server, bytes)
         ↓
Velocity proxy forwards to target server(s)
```

**Config** (`HostConfig.sync.velocity`):
```yaml
sync:
  velocity:
    enabled: true
    channel: "textformatter:sync"
    target-servers: ["lobby", "survival", "creative"]
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncVelocityModule` | `SyncVelocityModule.java` | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Extension Points

- **Server targeting** — extend to support per-message server selection
- **Custom channel** — configure plugin message channel ID
- **Bukkit support** — would need separate `sync-velocity-bukkit` module

---

## 9. Exploration Path

```
1. VelocitySink.java            → SyncSink implementation
2. SyncVelocityModule.java      → Module registration
3. Velocity API: PluginMessageChannel → Velocity proxy API used
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `Message` SPIs
- [host](../host/README.md) — Collects sinks, loads config
- [kernel](../kernel/README.md) — Loads module
- [fabric-host](../fabric-host/README.md) — Only consumer