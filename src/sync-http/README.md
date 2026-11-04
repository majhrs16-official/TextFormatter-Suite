# sync-http — HTTP Webhook Sync Sink

> **Purpose**: Implements `SyncSink` SPI from `core-api` for HTTP webhook synchronization (outbound webhooks + inbound REST endpoint). Uses the JDK `HttpClient` for outbound calls and `transport.MessageCodec` for the wire format.

---

## Modules in Sync Family

| Module | Transport | Sink Implementation | Key Dependency |
|--------|-----------|---------------------|----------------|
| `sync-http` | HTTP/HTTPS | `HttpSink` | `transport` (`MessageCodec`) |
| `sync-socket` | TCP/UDP | `TcpSink`, `UdpSink` | `transport` (`MessageCodec`) |
| `sync-discord` | Discord (JDA) | `JdaDiscordSink`, `DiscordGateway` | `transport`, JDA |
| `sync-telegram` | Telegram Bot API | `TelegramSink` | `transport` |
| `sync-websocket` | WebSocket | `WebSocketSyncSink` | `observability` (metrics) |
| `sync-velocity` | Velocity Proxy | `VelocitySink` | Velocity API |

---

## 1. Responsibilities (All Sync Modules)

- **Implement `SyncSink`** — `send(message) → void`, `start()`, `stop()`
- **Implement `SyncListener`** (optional) — inbound messages for the bus
- **Package registration** — `SyncXxxActivator` resolves `SyncBus` from the service registry and registers its sink
- **Configuration** — read endpoint/credentials from the module-private `config/sync-xxx/` directory
- **Message encoding** — use `MessageCodec` (from `transport`) for wire format

---

## 2. Non-Responsibilities

- **No message routing** — `iflow-vm` decides what to sync
- **No formatting** — messages already formatted by `textformatter`
- **No translation** — already translated if needed
- **No platform-specific code** (except `sync-velocity` which is Velocity-specific)

---

## 3. Dependencies (Common)

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `SyncBus`, `SyncListener`, `Message` SPIs |
| `sync-common` | Compile | YAML reader and config paths (`DEPENDENCY` package) |
| `transport` | Compile | `HttpTransport`, `MessageCodec` (most modules) |
| `org.json` | Compile | JSON payload handling |
| `kernel` / `sync-bus` | Test | Module descriptor and bus fixtures |

**Module-specific:**
- `sync-discord` → `net.dv8tion:JDA`
- `sync-velocity` → Velocity API (compileOnly)

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| module runtime | Activates the `sync-*` packages and injects `SyncBus` |
| `SyncBus` | Owns and fans out to every registered sink |
| `spigot-host` / `fabric-host` | Legacy wiring until F4b |

---

## 5. Main Components (Per Module)

### sync-http
| Component | Role |
|-----------|------|
| `HttpSink` | Implements `SyncSink`: POST JSON to the webhook and expose the inbound endpoint |
| `HttpConfig` | Reads `config/sync-http/config.yml` |
| `SyncHttpActivator` | Registers/unregisters `HttpSink` with the running bus |
| `SyncHttpModule` | Legacy kernel descriptor publishing the `sync-sink` capability |

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

**Config** (`config/sync-http/config.yml`):
```yaml
enabled: true
outboundUrl: "https://example.com/webhook"
inboundPort: 0          # 0 = ephemeral
inboundPath: "/hook"
bind: "127.0.0.1"
authToken: ""           # optional Bearer token
hmacSecret: ""          # optional HMAC-SHA256 secret
```

Each `sync-*` package reads its own directory under `config/`. An enabled file
without the required fields is skipped with a log line; the package still
activates.

```bash
./gradlew :src:sync-http:test
```

`PackageRuntimeTest.installsSyncHttpPackageAndRegistersConfiguredSink`
installs the real `sync-common`, `transport`, `sync-bus` and `sync-http`
packages, verifies the sink is registered, then unloads `sync-http` and checks
that the running bus is empty.

---

## 7. Entry Points

| Module | Entry Point | Called By |
|--------|-------------|-----------|
| `sync-http` | `SyncHttpActivator` | Module runtime |
| `sync-socket` | `SyncSocketActivator` | Module runtime |
| `sync-discord` | `SyncDiscordModule` | Legacy kernel descriptor |
| `sync-telegram` | `SyncTelegramActivator` | Module runtime |
| `sync-websocket` | `SyncWebSocketModule` | Legacy kernel descriptor |
| `sync-velocity` | `SyncVelocityModule` | Legacy kernel descriptor |

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
2. HttpConfig.java            → Module config schema
3. SyncHttpActivator.java     → Package lifecycle and bus registration
4. transport/MessageCodec.java → Wire format used by send()
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
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Platform adapters using sync (**fabric-host excluded**)

---

## 11. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **M-06** | HttpSink inbound auth implemented | `HttpSink.handleInbound()` — Bearer token, HMAC-SHA256, fallback localhost-only; nonce/timestamp replay protection |