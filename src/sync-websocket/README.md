# sync-websocket — WebSocket Sync Sink

> **Purpose**: Implements `SyncSink` SPI for WebSocket transport. Maintains persistent WebSocket connection for real-time message sync.

---

## 1. Responsibilities

- **WebSocket client** — connects to `ws://` or `wss://` endpoint
- **Persistent connection** — auto-reconnect, heartbeat/ping-pong
- **Message framing** — uses `MessageCodec` for JSON framing
- **Module registration** — `SyncWebSocketModule` registers sink

---

## 2. Non-Responsibilities

- **No WebSocket server** — client only
- **No message routing** — `iflow` decides what to sync
- **No platform-specific code** — pure Java WebSocket API

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `Message` SPIs |
| `kernel` | Test | Test fixtures |

(Note: Uses Java 11+ `java.net.http.WebSocket` — no external deps)

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | `SuiteBootstrap` collects `WebSocketSink` |
| `spigot-host` | Syncs via WebSocket |
| `fabric-host` | Syncs via WebSocket |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `WebSocketSink` | `SyncSink` impl: Java `WebSocket` client, auto-reconnect |
| `SyncWebSocketModule` | `Module` registering sink |

---

## 6. Data Flow

```text
SyncSink.send(message)
         ↓
MessageCodec.encode(message) → JSON string
         ↓
WebSocketSink: webSocket.sendText(json, true)
         ↓
Auto-reconnect on close/error
         ↓
Ping/pong heartbeat (configurable interval)
```

**Config** (`HostConfig.sync.websocket`):
```yaml
sync:
  websocket:
    enabled: true
    url: "wss://example.com/sync"
    reconnect-interval: 5000
    ping-interval: 30000
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncWebSocketModule` | `SyncWebSocketModule.java` | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Extension Points

- **TLS/SSL** — use `wss://` URL, configure `SSLContext` if needed
- **Custom headers** — extend sink to send auth headers on handshake
- **Binary frames** — extend to support binary message format
- **Inbound messages** — implement `SyncListener` via `WebSocket.Listener`

---

## 9. Exploration Path

```
1. WebSocketSink.java           → SyncSink implementation
2. SyncWebSocketModule.java     → Module registration
3. java.net.http.WebSocket      → Standard API used
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `Message` SPIs
- [transport](../transport/README.md) — `MessageCodec` (for framing)
- [host](../host/README.md) — Collects sinks, loads config
- [kernel](../kernel/README.md) — Loads module
- [sync-tcpudp](../sync-tcpudp/README.md) — TCP/UDP sync (similar pattern)