# sync-velocity — Velocity Proxy Sync Sink (Production-Ready)

> **Purpose**: Implements `SyncSink` SPI for Velocity proxy plugin message channel. Sends messages across servers connected to the same Velocity proxy. **Production-ready** with async queue, exponential backoff retry, dynamic server discovery, advanced mapping (regex, per-message-type, wildcard), Prometheus-compatible metrics, health checks, and graceful shutdown with queue drain.

---

## 1. Responsibilities

- **Velocity plugin message channel** — uses `PluginMessageChannel` for cross-server communication
- **Module registration** — `SyncVelocityModule` registers sink
- **Fabric-only** — only used by `fabric-host` (Velocity is a proxy, not a Minecraft platform)

---

## 2. Non-Responsibilities

- **No standalone proxy** — requires Velocity proxy with plugin
- **No message routing** — `iflow` decides what to sync
- **Not Fabric-only** — works as a standard Velocity plugin (Java 17+, Velocity API 3.4.0)

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `Message` SPIs |
| `transport` | Compile | `MessageCodec` for message serialization |
| Velocity API | Compile | `PluginMessageChannel`, `ProxyServer`, `RegisteredServer` (3.4.0) |
| `org.json` | Compile | JSON config parsing |
| `slf4j` | Compile | Logging |

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Syncs across Velocity-connected servers (via Velocity plugin) |
| `fabric-host` | Syncs across Velocity-connected servers (via Velocity plugin) |
| Velocity Plugin | Runs as a Velocity plugin (`VelocityPlugin`) |

## 5. Main Components

| Component | Role |
|-----------|------|
| `VelocitySink` | `SyncSink` impl: async send, retry queue with exponential backoff, dynamic server discovery, advanced mapping (regex/per-type/wildcard), metrics, health checks, graceful shutdown |
| `VelocityPlugin` | Velocity plugin entry point: registers channel, event handlers, starts sink |
| `SyncVelocityModule` | `Module` registering sink |

---

## 6. Data Flow

```text
SyncSink.send(message)
         ↓
VelocitySink.send(message)
         ↓
Serialize message to JSON (MessageCodec)
         ↓
Build plugin message payload (subchannel, type, id, sender, channel, json, uuid, timestamp, auth)
         ↓
Resolve target servers via mapping config (regex, per-type, wildcard, * -> all)
         ↓
For each target: sendAsync(server, data)
         ↓
If immediate send fails → queue for retry with exponential backoff
         ↓
Retry processor drains queue on schedule
         ↓
Velocity proxy forwards to target server(s)
```

**Config** (`HostConfig.sync.velocity`):
```yaml
sync:
  velocity:
    enabled: true
    secret: "optional-shared-secret"
    servers: ["lobby", "survival", "creative"]  # static targets
    mapping: "* -> chat.hub; type:CHAT -> global"  # advanced mapping
    dynamic-discovery: true
    retry-initial-delay: 5s
    retry-max-delay: 5m
    retry-multiplier: 2.0
    max-retries: 10
    max-queue-size: 10000
    queue-drain-timeout: 30s
    metrics-interval: 1m
```

**Mapping Syntax**:
- `source -> target` — exact channel match
- `regex:pattern -> target` — regex match on channel
- `type:CHAT -> target` — match by message type
- `* -> target` — wildcard (all channels)
- Multiple mappings separated by `;`

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `VelocityPlugin.onLoad()` | `VelocityPlugin.java` | Velocity proxy load |
| `VelocityPlugin.onEnable()` | `VelocityPlugin.java` | Velocity proxy enable |
| `SyncVelocityModule` | `SyncVelocityModule.java` | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Extension Points

- **Custom mapping logic** — extend `resolveTargets()` for per-message server selection
- **Custom channel** — configure plugin message channel ID via config
- **Custom auth** — extend secret verification in `handleInbound()`
- **Additional metrics** — extend `logMetrics()` and `HealthStatus`

---

## 9. Exploration Path

```
1. VelocitySink.java            → SyncSink implementation (async, retry, mapping, metrics, health)
2. VelocityPlugin.java          → Velocity plugin entry point
3. SyncVelocityModule.java      → Module registration
4. Velocity API: PluginMessageChannel → Velocity proxy API used
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `Message` SPIs
- [transport](../transport/README.md) — `MessageCodec` for serialization
- [host](../host/README.md) — Collects sinks, loads config
- [kernel](../kernel/README.md) — Loads module
- [spigot-host](../spigot-host/README.md) — Consumer via Velocity plugin
- [fabric-host](../fabric-host/README.md) — Consumer via Velocity plugin (when rewritten)