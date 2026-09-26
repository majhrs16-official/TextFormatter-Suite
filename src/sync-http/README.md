# Sync Modules — Cross-Server Message Synchronization

> **Purpose**: Implement `SyncSink` SPI from `core-api` to send/receive messages across servers via various transports (HTTP, TCP, UDP, Discord, Telegram, WebSocket, Velocity).

---

## Modules

| Module | Transport | Sink Implementation | Key Dependency |
|--------|-----------|---------------------|----------------|
| `sync-http` | HTTP/HTTPS | `HttpSink` | `transport` (HttpTransport) |
| `sync-tcpudp` | TCP/UDP | `TcpSink`, `UdpSink` | `transport` (MessageCodec) |
| `sync-discord` | Discord (JDA) | `JdaDiscordSink`, `DiscordGateway` | `transport`, JDA |
| `sync-telegram` | Telegram Bot API | `TelegramSink` | `transport` |
| `sync-websocket` | WebSocket | `WebSocketSink` | `core-api` only |
| `sync-velocity` | Velocity Proxy | `VelocitySink` | `core-api` only |

---

## 1. Responsibilities (All Sync Modules)

- **Implement `SyncSink`** — `send(message) → void`, `start()`, `stop()`
- **Implement `SyncListener`** (optional) — `onMessageReceived(message)` for inbound
- **Module registration** — `SyncXxxModule` registers sink with `SuiteBootstrap`
- **Configuration** — read endpoint/credentials from `HostConfig.sync` section
- **Message encoding** — use `MessageCodec` (from `transport`) for wire format

---

## 2. Non-Responsibilities

- **No message routing** — `iflow` decides what to sync
- **No formatting** — messages already formatted by `textformatter`
- **No translation** — already translated if needed
- **No platform-specific code** (except `sync-velocity` which is Velocity-specific)

---

## 3. Dependencies (Common)

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `SyncListener`, `Message` SPIs |
| `transport` | Compile | `HttpTransport`, `MessageCodec` (most modules) |
| `org.json` | Compile | JSON payload handling |
| `kernel` | Test | Test fixtures |

**Module-specific:**
- `sync-discord` → `net.dv8tion:JDA`
- `sync-velocity` → Velocity API (compileOnly)

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | `SuiteBootstrap` collects all `SyncSink` instances |
| `spigot-host` | Syncs messages via registered sinks |
| `fabric-host` | Syncs messages via registered sinks |

---

## 5. Main Components (Per Module)

### sync-http
| Component | Role |
|-----------|------|
| `HttpSink` | Implements `SyncSink`: POST JSON to webhook URL |
| `SyncHttpModule` | Registers `HttpSink` |

### sync-tcpudp
| Component | Role |
|-----------|------|
| `TcpSink` | Implements `SyncSink`: TCP client with `MessageCodec` framing |
| `UdpSink` | Implements `SyncSink`: UDP datagram with `MessageCodec` |
| `MessageCodec` | Frames messages for TCP/UDP (length-prefixed JSON) |
| `SyncTcpUdpModule` | Registers both sinks |

### sync-discord
| Component | Role |
|-----------|------|
| `JdaDiscordSink` | Implements `SyncSink`: sends to Discord channel via JDA |
| `DiscordGateway` | Manages JDA connection, reconnection, event handling |
| `DiscordSink` | Base interface for Discord sinks |
| `SyncDiscordModule` | Registers sink, starts gateway |

### sync-telegram
| Component | Role |
|-----------|------|
| `TelegramSink` | Implements `SyncSink`: sends via Telegram Bot API |
| `SyncTelegramModule` | Registers sink |

### sync-websocket
| Component | Role |
|-----------|------|
| `WebSocketSink` | Implements `SyncSink`: WebSocket client |
| `SyncWebSocketModule` | Registers sink |

### sync-velocity
| Component | Role |
|-----------|------|
| `VelocitySink` | Implements `SyncSink`: sends via Velocity proxy plugin message channel |
| `SyncVelocityModule` | Registers sink |

---

## 6. Data Flow (All Sync Modules)

```text
MessageDispatcher.dispatch()
         ↓ (after formatting & translation)
For each registered SyncSink:
         ↓
SyncSink.send(formattedMessage)
         ↓
Module-specific transport:
  - HttpSink: HttpTransport.post(webhookUrl, JSON)
  - TcpSink: TCP socket.write(MessageCodec.encode(msg))
  - UdpSink: DatagramSocket.send(MessageCodec.encode(msg))
  - JdaDiscordSink: JDA channel.sendMessage()
  - TelegramSink: HttpTransport.post(telegramApi, JSON)
  - WebSocketSink: WebSocket.send(MessageCodec.encode(msg))
  - VelocitySink: Velocity plugin message channel
```

**Config** (from `HostConfig.sync`):
```yaml
sync:
  http:
    enabled: true
    webhook-url: "https://example.com/webhook"
  tcp:
    enabled: true
    host: "127.0.0.1"
    port: 25565
  discord:
    enabled: true
    token: "bot-token"
    channel-id: 123456789
  # ... etc
```

---

## 7. Entry Points

| Module | Entry Point | Called By |
|--------|-------------|-----------|
| `sync-http` | `SyncHttpModule` | `ModuleLoader` → `SuiteBootstrap` |
| `sync-tcpudp` | `SyncTcpUdpModule` | `ModuleLoader` → `SuiteBootstrap` |
| `sync-discord` | `SyncDiscordModule` | `ModuleLoader` → `SuiteBootstrap` |
| `sync-telegram` | `SyncTelegramModule` | `ModuleLoader` → `SuiteBootstrap` |
| `sync-websocket` | `SyncWebSocketModule` | `ModuleLoader` → `SuiteBootstrap` |
| `sync-velocity` | `SyncVelocityModule` | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| New transport | Create new `sync-xxx` module, implement `SyncSink`, register via `Module` |
| Custom `MessageCodec` | Implement `MessageCodec` in `transport` module |
| Custom authentication | Add auth headers in sink's `send()` (HTTP) or connection handshake (WS/TCP) |
| Inbound sync | Implement `SyncListener` and register inbound handler |

---

## 9. Exploration Path (Per Module)

### sync-http
```
1. HttpSink.java              → SyncSink implementation
2. SyncHttpModule.java        → Module registration
3. transport/HttpTransport.java → HTTP client used
```

### sync-tcpudp
```
1. TcpSink.java / UdpSink.java → SyncSink implementations
2. MessageCodec.java           → Framing codec
3. SyncTcpUdpModule.java       → Module registration
```

### sync-discord
```
1. JdaDiscordSink.java         → SyncSink implementation
2. DiscordGateway.java         → JDA connection management
3. SyncDiscordModule.java      → Module registration
```

### sync-telegram / sync-websocket / sync-velocity
```
1. *Sink.java                  → SyncSink implementation
2. Sync*Module.java            → Module registration
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Defines `SyncSink`, `SyncListener`, `Message`
- [transport](../transport/README.md) — `HttpTransport`, `MessageCodec`
- [host](../host/README.md) — Collects sinks, loads sync config
- [kernel](../kernel/README.md) — Loads sync modules
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Platform adapters using sync