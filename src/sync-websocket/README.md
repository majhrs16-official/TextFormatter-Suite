# sync-websocket — WebSocket Sync Server

> **Purpose**: Implements `SyncSink` SPI as a **WebSocket server** for real-time cross-server message sync. Provides endpoints `/ws/chat`, `/ws/events`, `/ws/sync`, `/ws/logs` with subscription management, auth token, rate limiting, and log streaming.

---

## 1. Responsibilities

- **WebSocket server** — binds to configurable address/port (default 127.0.0.1:9092)
- **Subscription management** — clients subscribe to paths (`/ws/chat`, `/ws/events`, `/ws/sync`, `/ws/logs`)
- **Auth token required** — rejects connections without valid token (config `token`)
- **Rate limiting** — fixed-window 100 msg/s per connection (recovers under sustained load)
- **Message framing** — uses `MessageCodec` for JSON encoding
- **Log streaming** — `/ws/logs` endpoint streams server logs
- **Module registration** — `SyncWebSocketActivator` registers the sink with the running bus

---

## 2. Non-Responsibilities

- **No WebSocket client** — server only
- **No message routing** — `iflow-vm` decides what to sync
- **No platform-specific code** — pure Java, uses `org.java_websocket`

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `SyncBus`, `SyncListener`, `Message`, `PluginLogger`, `Metrics` facade |
| `sync-common` | Compile | `WebSocketSyncConfig` YAML reader (`DEPENDENCY` package) |
| `transport` | Compile | `MessageCodec` for JSON encoding (`DEPENDENCY` package) |
| `org.java-websocket` | Compile | WebSocket server implementation (host classpath until F8) |
| `gson` / `org.json` | Compile | JSON handling (host classpath until F8) |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| module runtime | Activates `SyncWebSocketActivator` from the `sync-websocket` ZIP |
| `SyncBus` | Receives the `websocket` sink when its config is usable |
| `spigot-host` / `fabric-host` | Legacy wiring until F4b |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `WebSocketSyncSink` | `SyncSink` impl: `org.java_websocket.server.WebSocketServer`, subscriptions, auth, rate limiting |
| `WebSocketSyncConfig` | Reads `config/sync-websocket/config.yml` |
| `SyncWebSocketActivator` | Registers/unregisters the sink with the running bus |
| `SyncWebSocketModule` | Legacy kernel descriptor publishing the `sync-sink` and `websocket-server` capabilities |

---

## 6. Data Flow

```text
SyncSink.send(message)
         ↓
MessageCodec.toJson(message) → JSON string
         ↓
WebSocketSyncSink.broadcastToPath(path, json)
         ↓
All subscribed clients receive message
```

**Config** (`config/sync-websocket/config.yml`):
```yaml
enabled: true
port: 9092
bind: "127.0.0.1"   # default localhost for security
token: "secret"     # REQUIRED - no token = refused to start
```

The activator reads the file from its module config directory. An enabled file
without token is skipped with a log line; the package still activates.

```bash
./gradlew :src:sync-websocket:test
```

`PackageRuntimeTest.installsSyncWebSocketPackageAndRegistersConfiguredSink`
installs the real `sync-common`, `transport`, `sync-bus` and `sync-websocket`
packages on a free port, verifies the sink is registered, then unloads it and
checks that the running bus is empty.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncWebSocketActivator` | `SyncWebSocketActivator.java` | Module runtime |
| `SyncWebSocketModule` | `SyncWebSocketModule.java` | Legacy kernel descriptor |

---

## 8. Extension Points

- **Custom endpoints** — add paths to `SyncWebSocketServer.onOpen()`
- **Message filtering** — extend subscription logic per-path
- **Metrics** — uses the neutral `Metrics.recordSyncSent("websocket", ...)` facade; the observability package installs the Prometheus sink when active

---

## 9. Exploration Path

```
1. WebSocketSyncSink.java           → SyncSink implementation (server)
2. WebSocketSyncServer.java         → WebSocket server logic
3. WebSocketSyncConfig.java         → Module config schema
4. SyncWebSocketActivator.java      → Package lifecycle and bus registration
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `SyncBus`, `SyncListener`, `Message` SPIs, `Metrics` facade
- [sync-common](../sync-common/README.md) — YAML reader and config paths (`DEPENDENCY`)
- [sync-bus](../sync-bus/README.md) — Fan-out bus that owns the sink
- [transport](../transport/README.md) — `MessageCodec` (for framing)
- [sync-socket](../sync-socket/README.md) — TCP/UDP sync (similar pattern)

---

## 11. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **V-01** | Bind 0.0.0.0 + optional auth | Now binds `127.0.0.1` by default; requires token (refuses start if empty) |
| **B-06** | Rate limit never recovers under load | Fixed-window rate limiting (per-connection, resets every second) |
| **M-10** | Port sanitization bug | Constructor uses sanitized `this.port` in `InetSocketAddress` |