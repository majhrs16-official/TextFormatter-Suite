# sync-velocity-core — neutral Velocity sink

`sync-velocity-core` is the **application** side of the Velocity edge. It owns the
queue, retry policy, channel/type mapping, payload codec, metrics and health.
It contains no Velocity API types.

The Velocity API lives in the plugin adapter:

| Package | Technology | Role |
|---------|------------|------|
| [`sync-velocity`](../sync-velocity/README.md) | Velocity API 3.4 | Velocity plugin, channel registration, `sendPluginMessage` |

The neutral package activates without an adapter. It listens for
`ModuleActivatedEvent`/`ModuleDeactivatedEvent` and attaches/detaches the sink
when a matching `VelocityConnectionFactory` appears or disappears.

---

## 1. Responsibilities

- Build the plugin-message payload from a neutral `Message`.
- Resolve target servers from static config, wildcard, regex or message type.
- Queue failed sends and retry with exponential backoff.
- Verify the shared secret on inbound payloads.
- Expose metrics and health.
- Never import `com.velocitypowered`.

## 2. Non-responsibilities

- No Velocity channel registration or event listener.
- No Minecraft/Paper/Bukkit code.
- No routing decisions: `iflow` decides what to sync.
- No proxy lifecycle: the plugin adapter owns `ProxyInitializeEvent` and
  `ProxyShutdownEvent`.

---

## 3. Components

| Component | Role |
|-----------|------|
| `VelocitySink` | Neutral `SyncSink`: encode/decode, mapping, retry, metrics, health. |
| `VelocityConfig` | Loads `config/sync-velocity-core/config.yml`; no Velocity types. |
| `SyncVelocityActivator` | Resolves `SyncBus` + `VelocityConnectionFactory`, hot attach/detach. |
| `SyncVelocityModule` | Legacy kernel descriptor, kept for the kernel graph. |

Ports in `core-api` (`me.majhrs16.suite.api.velocity`):

| Type | Role |
|------|------|
| `VelocityConnection` | Adapter contract: `start`, `stop`, `servers`, `send`. |
| `VelocityConnectionFactory` | Adapter discovery by `name()`. |
| `VelocitySettings` | Channel and subchannel names. |
| `VelocityListener` | Inbound payload callback. |

---

## 4. Configuration

Canonical path: `config/sync-velocity-core/config.yml`. The legacy
`sync/velocity.yml` is read during migration.

```yaml
enabled: true
transport: velocity
channel: "textformatter:velocity"
subchannel: "textformatter"
secret: "optional-shared-secret"
servers: ["lobby", "survival"]
mapping: "* -> chat.hub; type:CHAT -> global"
dynamic-discovery: true
retry-initial-delay: 5s
retry-max-delay: 5m
retry-multiplier: 2.0
max-retries: 10
max-queue-size: 10000
queue-drain-timeout: 30s
metrics-interval: 1m
```

Mapping syntax:

- `source -> target` — exact channel match.
- `regex:pattern -> target` — regex match on channel.
- `type:CHAT -> target` — match by message type.
- `* -> target` — wildcard.
- Multiple rules separated by `;`.

`transport` must match a factory registered by an installed adapter. If it is
absent, the package stays active without a sink and logs the available names.

---

## 5. Lifecycle

```text
SyncVelocityActivator.start()
  → find SyncBus
  → load config
  → subscribe to ModuleActivatedEvent / ModuleDeactivatedEvent
  → tryAttach()
      → find VelocityConnectionFactory by name
      → new VelocitySink(connection, config, settings, logger)
      → bus.register(sink)
          → sink.start()
              → connection.start(listener)   (adapter registers channel)
```

`stop()` unsubscribes and unregisters the sink. `VelocitySink.stop()` drains the
queue, stops the adapter connection and shuts down its daemon executors.

---

## 6. Tests

`VelocitySinkTest` uses `FakeConnection`; no proxy and no network:

- start/stop idempotence;
- disabled config does not touch the transport;
- mapped send;
- retry queue;
- secret verification;
- health and metrics;
- config validation.

`PackageRuntimeTest.installsNeutralVelocityPackageWithATransportAdapter`
installs the real package ZIP before a fake adapter package, verifies hot
attach, adapter unload and package unload.

---

## 7. Related modules

- [core-api](../core-api/README.md) — ports and suite contracts
- [sync-bus](../sync-bus/README.md) — sink lifecycle and fan-out
- [sync-common](../sync-common/README.md) — YAML/config path helpers
- [sync-velocity](../sync-velocity/README.md) — Velocity plugin adapter
