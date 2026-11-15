# sync-socket — TCP/UDP Sync Sinks

> **Purpose**: Implements `SyncSink` SPI for TCP and UDP transport. Uses length-prefixed JSON framing via `MessageCodec` from `transport` module.

---

## 1. Responsibilities

- **TCP sink** — persistent TCP connection with reconnection logic
- **UDP sink** — datagram-based fire-and-forget delivery
- **Native action surface** — `tcp.endpoint.info`, `tcp.connection.probe` and `udp.endpoint.info`, each mapped to an explicit `SyncResult`
- **Message framing** — `MessageCodec` encodes/decodes length-prefixed JSON
- **Module registration** — `SyncSocketActivator` registers each configured sink with the running bus

---

## 2. Non-Responsibilities

- **No HTTP/WebSocket/Discord** — separate modules
- **No message routing** — the iFlow engine decides what to sync
- **No platform-specific code** — pure Java NIO

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `SyncBus`, `Message` SPIs |
| `sync-common` | Compile | `SocketConfig` YAML reader (`DEPENDENCY` package) |
| `transport` | Compile | `MessageCodec` for framing (`DEPENDENCY` package) |
| `org.json` | Compile | JSON payload handling |
| `kernel` / `sync-bus` | Test | Module descriptor and bus fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| module runtime | Activates `SyncSocketActivator` from the `sync-socket` ZIP |
| `SyncBus` | Receives the `tcp` and `udp` sinks when their config is usable |
| `spigot-host` | Legacy wiring: still constructs the sinks until F4b |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TcpSink` | `SyncSink` impl: TCP client, auto-reconnect, length-prefixed JSON, surface and native action dispatch |
| `UdpSink` | `SyncSink` impl: UDP datagram, length-prefixed JSON, surface and native action dispatch |
| `MessageCodec` | Frames messages: `[4-byte length][JSON bytes]` |
| `SyncSocketActivator` | Reads `tcp.yml`/`udp.yml`, registers each usable sink with the bus and unregisters it on stop |
| `SyncSocketModule` | Legacy kernel descriptor publishing the `sync-sink` capability |

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

SyncBus.invoke(SyncCall)
         ↓
TcpSink.invoke(call)                 UdpSink.invoke(call)
         ↓                                    ↓
endpoint.info → in-memory description   endpoint.info → in-memory description
connection.probe → TCP connect check
```

### Native actions

Both sinks publish the LCM plus an `endpoint.info` action. `TcpSink` also
publishes `connection.probe`. `invoke()` requires a `Principal.Program`:
`Principal.Official` receives `Denied` and only reaches the LCM. A stopped sink
returns `HostFailure` and `send(Message)` throws `IOException` instead of
opening a socket or dereferencing a closed one.

| Backend | Action | Kind | Arguments | Result |
|---|---|---|---|---|
| `tcp` | `endpoint.info` | `READ` | none | `Ok` with host, ports and listener state |
| `tcp` | `connection.probe` | `READ` | none | `Ok` with `reachable` and latency, or `HostFailure` |
| `udp` | `endpoint.info` | `READ` | none | `Ok` with host, ports and listener state |

`endpoint.info` reports the configured remote host/port, the bound inbound port
and whether the listener is open; it exposes no credentials. `connection.probe`
opens and immediately closes a TCP connection to the remote listener with a 4 s
connect timeout; it sends no message and returns `HostFailure` when the remote
is unreachable. `send()` and the inbound loops capture the socket they own, so
a `stop()` → `start()` cycle cannot leave a stale loop reading from the new
socket.

**Config** (`config/sync-socket/tcp.yml` / `config/sync-socket/udp.yml`):
```yaml
enabled: true
remoteHost: "127.0.0.1"
remotePort: 25565
localPort: 0
```

The activator reads the files from its module config directory. A disabled or
incomplete file is skipped with a log line; the package still activates.

```bash
./gradlew :src:sync-socket:test
```

`PackageRuntimeTest.installsSyncSocketPackageAndRegistersConfiguredSinks`
installs the real `sync-common`, `transport`, `sync-bus` and `sync-socket`
packages, verifies both sinks are registered, then unloads `sync-socket` and
checks that the running bus has no sinks left.

`TcpSinkTest` and `UdpSinkTest` cover the published surfaces, `endpoint.info`
without secrets, the TCP probe against a reachable and an unreachable remote,
`Denied` for `Principal.Official`, `Unsupported`, a stopped sink (including the
explicit `send` failure) and the inbound listener restarting after `stop()`.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncSocketActivator` | `SyncSocketActivator.java` | Module runtime |
| `SyncSocketModule` | `SyncSocketModule.java` | Legacy kernel descriptor |

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
4. SyncSocketActivator.java     → Package lifecycle and bus registration
5. test/TcpSinkTest.java        → Usage examples
6. test/UdpSinkTest.java        → Usage examples
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `SyncBus`, `Message` SPIs
- [sync-common](../sync-common/README.md) — YAML reader and config paths (`DEPENDENCY`)
- [sync-bus](../sync-bus/README.md) — Fan-out bus that owns the sinks
- [transport](../transport/README.md) — `MessageCodec` (shared)
- [sync-http](../sync-http/README.md) — HTTP sync (sister module)
- [sync-discord](../sync-discord/README.md) — Discord sync (sister module)