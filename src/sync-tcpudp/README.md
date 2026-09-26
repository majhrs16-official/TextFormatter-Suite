# sync-tcpudp — TCP/UDP Sync Sinks

> **Purpose**: Implements `SyncSink` SPI for TCP and UDP transport. Uses length-prefixed JSON framing via `MessageCodec` from `transport` module.

---

## 1. Responsibilities

- **TCP sink** — persistent TCP connection with reconnection logic
- **UDP sink** — datagram-based fire-and-forget delivery
- **Message framing** — `MessageCodec` encodes/decodes length-prefixed JSON
- **Module registration** — `SyncTcpUdpModule` registers both sinks

---

## 2. Non-Responsibilities

- **No HTTP/WebSocket/Discord** — separate modules
- **No message routing** — `iflow` decides what to sync
- **No platform-specific code** — pure Java NIO

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `Message` SPIs |
| `transport` | Compile | `MessageCodec` for framing |
| `org.json` | Compile | JSON payload handling |
| `kernel` | Test | Test fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | `SuiteBootstrap` collects `TcpSink` + `UdpSink` |
| `spigot-host` | Syncs via TCP/UDP |
| `fabric-host` | Syncs via TCP/UDP |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TcpSink` | `SyncSink` impl: TCP client, auto-reconnect, length-prefixed JSON |
| `UdpSink` | `SyncSink` impl: UDP datagram, length-prefixed JSON |
| `MessageCodec` | Frames messages: `[4-byte length][JSON bytes]` |
| `SyncTcpUdpModule` | `Module` registering both sinks |

---

## 6. Data Flow

```text
SyncSink.send(message)
         ↓
MessageCodec.encode(message) → byte[] (length + JSON)
         ↓
TcpSink: socket.write(bytes) with reconnect logic
   OR
UdpSink: datagramSocket.send(bytes)
```

**Config** (`HostConfig.sync.tcp` / `sync.udp`):
```yaml
sync:
  tcp:
    enabled: true
    host: "127.0.0.1"
    port: 25565
    reconnect-interval: 5000
  udp:
    enabled: true
    host: "127.0.0.1"
    port: 25565
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncTcpUdpModule` | `SyncTcpUdpModule.java` | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Extension Points

- **Custom framing** — extend `MessageCodec` in `transport` module
- **TLS/SSL** — wrap TCP socket with `SSLSocketFactory`
- **Custom reconnection** — extend `TcpSink` reconnection logic

---

## 9. Exploration Path

```
1. TcpSink.java                 → TCP SyncSink implementation
2. UdpSink.java                 → UDP SyncSink implementation
3. MessageCodec.java            → Framing codec (in transport module)
4. SyncTcpUdpModule.java        → Module registration
5. test/TcpSinkTest.java        → Usage examples
6. test/UdpSinkTest.java        → Usage examples
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `Message` SPIs
- [transport](../transport/README.md) — `MessageCodec` (shared)
- [host](../host/README.md) — Collects sinks, loads config
- [kernel](../kernel/README.md) — Loads module
- [sync-http](../sync-http/README.md) — HTTP sync (sister module)
- [sync-discord](../sync-discord/README.md) — Discord sync (sister module)