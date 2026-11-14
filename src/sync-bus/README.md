# sync-bus — Unified Sync Pipeline

> **Purpose**: Central hub that fans out the LCM sync actions
> (`backend.info`, `message.send`) to every registered `SyncSink`, executes
> native actions per backend and routes inbound sink events back to the engine.

---

## 1. Responsibilities

- Register/unregister `SyncSink` implementations by backend namespace.
- Validate that every sink publishes the least common multiple (`backend.info`,
  `message.send`) and that `surface().backend()` equals `sink.name()`.
- Execute one action through `invoke(SyncCall)` with surface, argument and
  authorization checks; every failure maps to an explicit `SyncResult`.
- Authorize third-party programs by the SHA-256 of their image: the effective
  capability is `sync.<backend>.<action>` for sinks and
  `module.<moduleId>.<action>` for host actions, intersected with the operator
  grant. `DESTRUCTIVE` and `MODERATION` require `allow-destructive: true`.
- Enforce the per-program sliding 60-second call window and the TLV payload
  bound; an exhausted budget returns `RateLimited` and an oversized payload
  returns `Denied`.
- Append one audit entry per attempt with the redacted argument hash; a program
  action requires a writable audit trail before it runs.
- Fan out only the LCM (`broadcast(SyncCall)`); a native action is never turned
  into a mass operation.
- Deliver `message.send` through one bounded queue per sink (`send`, `sendTo`).
- Deliver a declared target list in order with `sendTo(List, Message, ParallelMode)`:
  parallel starts every target before waiting, sequential waits for each one.
- Isolate sinks with independent queues and worker threads (bulkhead pattern).
- Deduplicate messages by ID during a 60-second window.
- Retry idempotent deliveries with capped exponential backoff.
- Bound every action by its declared timeout (and the bus's 30 s ceiling).
- Expose counters for broadcasts, drops, retries and deduplicated messages.

---

## 2. Non-Responsibilities

- No platform I/O: Discord, Telegram, HTTP, TCP/UDP and WebSocket live in their
  own sync modules.
- No message formatting, translation or routing: `host`/`iflow-vm` produce the
  `Message`; the bus only distributes it.
- No native action implementation: each backend executes its own surface. The
  bus only validates, queues, bounds and maps the result.
- No grant storage: the bus consumes the `ProgramGrantStore` and `ActionAudit`
  services; `suite-runtime` owns `config/iflow/grants.yml` and `audit.log`.
- No persistence.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncBus`, `SyncSink`, `SyncListener`, `Message`, `PluginLogger`, `api.sync.*` |

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
| `SyncBus` | Public contract in `core-api`: registration, `surfaces`, `invoke`, LCM `broadcast`, `send`/`sendTo`, lifecycle, metrics |
| `DefaultSyncBus` | Implementation: surface validation, authorization, quotas, audit trail, dedup cache, per-sink workers, timeouts, retries |
| `SyncBusActivator` | `ModuleActivator` that creates, publishes, starts and stops the bus and resolves `ProgramGrantStore`/`ActionAudit` by service |
| `SyncBusModule` | Legacy kernel descriptor publishing the `sync-bus` capability |

Constants in `DefaultSyncBus`:

| Constant | Value |
|----------|-------|
| Global queue capacity | 10,000 |
| Per-sink queue capacity | 5,000 |
| Max retries | 3 |
| Initial / max retry delay | 100 ms / 30 s |
| Dedup window / max entries | 60 s / 50,000 |
| Program call window | 60 s |
| Action timeout ceiling | 30 s |

---

## 6. Data Flow

Outbound delivery uses the `message.send` action:

```text
pipeline → SyncBus.send(message)
                 ↓
       dedup by message.id()
                 ↓
       per-backend queue + worker
                 ↓
       SyncSink.invoke(SyncCall message.send)
                 ↑
       retry with backoff on idempotent HostFailure
                 ↓
       List<SyncResult>, one per backend
```

Directed and native actions use the same envelope:

```text
caller → SyncBus.invoke(SyncCall)
              ↓
      validate backend + surface + arguments
              ↓
      authorize: grant by SHA-256 + capability + allow-destructive
                 + payload bound + writable audit + call window
              ↓
      message.send → per-backend queue (same path as send)
      native action → bounded execution off the caller thread
              ↓
      append one audit entry (redacted argument hash)
              ↓
      SyncResult (never null, never a fabricated success)
```

`broadcast(SyncCall)` is the only fan-out: it replaces `call.backend()` with each
registered surface backend and accepts only the LCM. Any native action returns
one `Unsupported` per backend, so a generic broadcast can never become a
destructive mass operation.

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
`SyncSink.stop()`, wakes the global processor and shuts down the scheduler and
the invocation executor.

Ordering guarantees:

- `surfaces()` and plain `broadcast`/`send` results follow registration order.
- `sendTo(List, Message, mode)` returns one result per requested backend in the same order; an
  unregistered backend yields `Unsupported` in its position.
- `send` with no registered sink returns an empty list.
- A duplicate suppressed by the dedup window returns an empty list, because no
  delivery was attempted.
- If the bus is stopped, delivery returns one `HostFailure` per backend instead
  of pretending success. A full per-sink queue also returns `HostFailure`.
- `send` releases the dedup reservation when no backend accepted the message,
  so a later retry is not blocked.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncBusActivator` | `SyncBusActivator.java` | Module runtime |
| `DefaultSyncBus(PluginLogger, Supplier<ProgramGrantStore>, Supplier<ActionAudit>)` | `DefaultSyncBus.java` | `SyncBusActivator` (services resolved on every call) |
| `register(SyncSink)` | `DefaultSyncBus.java` | Sink packages or `discoverSinks` |
| `start()` / `stop()` | `DefaultSyncBus.java` | Activator lifecycle |
| `invoke(SyncCall)` | `DefaultSyncBus.java` | Pipeline, features and iFlow bridge |
| `broadcast(SyncCall)` | `DefaultSyncBus.java` | LCM fan-out (processed actions) |
| `send(Message)` / `sendTo(backend, Message)` | `DefaultSyncBus.java` | `SyncStage`, `CoreTranslatorPlaceholders` |
| `sendTo(List, Message, ParallelMode)` | `DefaultSyncBus.java` | `SyncStage` for a declared `sync.*` target list |

---

## 8. Extension Points

- Implement `SyncSink` and register it with the bus, or publish it as a service
  and let `discoverSinks` index it.
- Publish the LCM with the default `surface()`/`invoke()`. A backend with native
  actions overrides both and must execute every action it declares.
- Return `Unsupported` for an unpublished action; never return a no-op success.
- Add a new sink module; do not modify the bus for platform-specific behavior.
- `Principal.Official` is restricted to `backend.info`/`message.send`.
  `Principal.Program` runs only the actions covered by its grant in
  `config/iflow/grants.yml`; a missing, ambiguous or unreadable grant is a
  denial, and `DESTRUCTIVE`/`MODERATION` also require `allow-destructive: true`.
  Secret parameters and undeclared arguments are redacted before hashing.

---

## 9. Tests

`DefaultSyncBusTest` covers registration, duplicate-name rejection, surface
validation (LCM and backend/name match), guards before start/after unregister,
real delivery through a sink, sink shutdown and a non-blocking `stop()`. It also
covers the hot-load contract: a sink registered after `start()` is started
immediately, and a sink that fails to start is rolled back. Action coverage
includes: `backend.info` returns metadata, an unpublished action returns
`Unsupported`, a missing/unknown argument returns `InvalidArgument`, a wedged
sink returns `Timeout`, retry exhaustion returns `HostFailure`, a full queue
returns `HostFailure` instead of dropping silently, a deduplicated emission
returns an empty list and does not block a later retry, broadcast fans out only
the LCM, and both `Principal.Official` on a native action and
`Principal.Program` receive `Denied` without reaching the sink.
`PackageRuntimeTest.installsSyncBusPackageAndHotRegistersSinkModule`
installs the real `sync-bus` ZIP and a sink package, then unloads the sink and
checks that the running bus unregisters it.

`DefaultSyncBusAuthorizationTest` covers the operator grant: missing grant,
missing capability, exact capability, trailing wildcard, destructive and
moderation without `allow-destructive`, the per-minute budget, an oversized
payload, secret/undeclared argument redaction, a missing or read-only audit
trail, a failing grant store and the `module.<id>.<action>` host capability.

```bash
./gradlew :src:sync-bus:test --no-daemon
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `SyncListener`, `api.sync.*`, `Message`
- [sync-discord](../sync-discord/README.md) — Discord sink
- [sync-telegram](../sync-telegram/README.md) — Telegram sink
- [sync-http](../sync-http/README.md) — HTTP sink
- [sync-socket](../sync-socket/README.md) — TCP/UDP sinks
- [sync-websocket](../sync-websocket/README.md) — WebSocket sink
- [host](../host/README.md) — produces outbound messages
