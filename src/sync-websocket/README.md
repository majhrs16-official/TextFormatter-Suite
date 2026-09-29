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
- **Module registration** — `SyncWebSocketModule` registers sink

---

## 2. Non-Responsibilities

- **No WebSocket client** — server only
- **No message routing** — `iflow` decides what to sync
- **No platform-specific code** — pure Java, uses `org.java_websocket`

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `SyncListener`, `Message`, `PluginLogger` |
| `transport` | Compile | `MessageCodec` for JSON encoding |
| `observability` | Compile | `MetricsCollector` for metrics |
| `org.java-websocket` | Compile | WebSocket server implementation |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | `SuiteBootstrap` collects `WebSocketSyncSink` |
| `spigot-host` | Syncs via WebSocket server |
| `fabric-host` | Syncs via WebSocket server (excluded from build) |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `WebSocketSyncSink` | `SyncSink` impl: `org.java_websocket.server.WebSocketServer`, subscriptions, auth, rate limiting |
| `SyncWebSocketModule` | `Module` registering sink |

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

**Config** (`sync/websocket.yml`):
```yaml
sync:
  websocket:
    enabled: true
    port: 9092
    bind: "127.0.0.1"   # default localhost for security
    token: "secret"     # REQUIRED - no token = refused to start
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncWebSocketModule` | `SyncWebSocketModule.java` | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Extension Points

- **Custom endpoints** — add paths to `SyncWebSocketServer.onOpen()`
- **Message filtering** — extend subscription logic per-path
- **Metrics** — uses `MetricsCollector.recordSyncSent("websocket", ...)`

---

## 9. Exploration Path

```
1. WebSocketSyncSink.java           → SyncSink implementation (server)
2. SyncWebSocketServer.java         → WebSocket server logic
3. SyncWebSocketModule.java         → Module registration
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `SyncListener`, `Message` SPIs
- [transport](../transport/README.md) — `MessageCodec` (for framing)
- [host](../host/README.md) — Collects sinks, loads config
- [kernel](../kernel/README.md) — Loads module
- [sync-tcpudp](../sync-tcpudp/README.md) — TCP/UDP sync (similar pattern)

---

## 11. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **V-01** | Bind 0.0.0.0 + optional auth | Now binds `127.0.0.1` by default; requires token (refuses start if empty) |
| **B-06** | Rate limit never recovers under load | Fixed-window rate limiting (per-connection, resets every second) |
| **M-10** | Port sanitization bug | Constructor uses sanitized `this.port` in `InetSocketAddress` |