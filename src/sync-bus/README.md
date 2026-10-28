# sync-bus — Unified Sync Pipeline

> **Purpose**: Central hub that fans out `Message` instances to every registered
> `SyncSink` and routes inbound sink events back to the engine.

---

## 1. Responsibilities

- Register/unregister `SyncSink` implementations by name.
- Fan out outbound messages to all sinks through one bounded global queue.
- Isolate sinks with independent queues and worker threads (bulkhead pattern).
- Deduplicate messages by ID during a 60-second window.
- Retry failed deliveries with capped exponential backoff.
- Expose counters for broadcasts, drops, retries and deduplicated messages.

---

## 2. Non-Responsibilities

- No platform I/O: Discord, Telegram, HTTP, TCP/UDP and WebSocket live in their
  own sync modules.
- No message formatting, translation or routing: `host`/`iflow-vm` produce the
  `Message`; the bus only distributes it.
- No persistence.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncBus`, `SyncSink`, `SyncListener`, `Message`, `PluginLogger` |
| `snakeyaml` | Compile | `SyncYaml` for the sync-* configuration readers |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| module runtime | Activates `SyncBusActivator`; the bus is published as `SyncBus` in the service registry |
| `sync-*` packages | Resolve `SyncBus` from the registry and register their sink |
| `spigot-host` | Legacy wiring: creates `DefaultSyncBus` until F4b moves it to the runtime |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `SyncBus` | Public contract in `core-api`: registration, broadcast, directed send, lifecycle, metrics |
| `DefaultSyncBus` | Implementation: global queue, dedup cache, per-sink workers, retries |
| `SyncBusActivator` | `ModuleActivator` that creates, publishes, starts and stops the bus |
| `SyncBusModule` | Legacy kernel descriptor publishing the `sync-bus` capability |

Constants in `DefaultSyncBus`:

| Constant | Value |
|----------|-------|
| Global queue capacity | 10,000 |
| Per-sink queue capacity | 5,000 |
| Max retries | 3 |
| Initial / max retry delay | 100 ms / 30 s |
| Dedup window / max entries | 60 s / 50,000 |

---

## 6. Data Flow

```text
host → SyncBus.broadcast(message)
                 ↓
       global bounded queue
                 ↓
       dedup by message.id()
                 ↓
       per-sink queue + worker
                 ↓
       SyncSink.send(message)
                 ↑
       retry with backoff on failure
```

Inbound flow:

```text
SyncSink → SyncListener.onMessage(sink, message)
                 ↓
       SyncBus.setInboundListener(...) callback
                 ↓
       host pipeline (routing/formatting)
```

`start()` is idempotent and starts every registered sink. A sink registered
after `start()` is started immediately, so a sink package installed later
becomes live without restarting the host. `stop()` stops sink workers, calls
`SyncSink.stop()`, wakes the global processor and shuts down the scheduler.
`broadcast()` before `start()` or after `stop()` returns `0`.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncBusActivator` | `SyncBusActivator.java` | Module runtime |
| `DefaultSyncBus(PluginLogger)` | `DefaultSyncBus.java` | `SyncBusActivator` |
| `register(SyncSink)` | `DefaultSyncBus.java` | Sink packages |
| `start()` / `stop()` | `DefaultSyncBus.java` | Activator lifecycle |
| `broadcast(Message)` | `DefaultSyncBus.java` | `DiscordBridge` and host message flow |

---

## 8. Extension Points

- Implement `SyncSink` and register it with the bus.
- Use `broadcastAsync(Message)` when the caller needs per-sink completion
  futures instead of queue acceptance only.
- Use `sendTo(name, message)` for directed delivery.
- Add a new sink module; do not modify the bus for platform-specific behavior.

---

## 9. Tests

`DefaultSyncBusTest` covers registration, duplicate-name rejection, guards before
start/after unregister, real delivery through a sink, sink shutdown and a
non-blocking `stop()`. It also covers the hot-load contract: a sink registered
after `start()` is started immediately, and a sink that fails to start is rolled
back. `PackageRuntimeTest.installsSyncBusPackageAndHotRegistersSinkModule`
installs the real `sync-bus` ZIP and a sink package, then unloads the sink and
checks that the running bus unregisters it.

```bash
./gradlew :src:sync-bus:test --no-daemon
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `SyncListener`, `Message`
- [sync-discord](../sync-discord/README.md) — Discord sink
- [sync-telegram](../sync-telegram/README.md) — Telegram sink
- [sync-http](../sync-http/README.md) — HTTP sink
- [sync-tcpudp](../sync-tcpudp/README.md) — TCP/UDP sinks
- [sync-websocket](../sync-websocket/README.md) — WebSocket sink
- [host](../host/README.md) — produces outbound messages
