# sync-discord — neutral Discord sink

`sync-discord` is the **application** side of the Discord edge. It contains no
Discord SDK: it maps the suite message contract to the
`me.majhrs16.suite.api.discord.DiscordConnection` port and translates inbound
`DiscordMessage` values back into the suite pipeline.

The concrete transport lives in a separate adapter package:

| Package | Technology | Provides |
|---------|------------|----------|
| [`sync-discord-jda`](../sync-discord-jda/README.md) | JDA | `discord-transport` |
| [`sync-discord-jdk`](../sync-discord-jdk/README.md) | JDK WebSocket + REST | `discord-transport` |

The package activates even when no adapter is installed yet. It subscribes to
`ModuleActivatedEvent`/`ModuleDeactivatedEvent`, attaches the sink when a
matching `DiscordConnectionFactory` appears, and removes it when that adapter is
unloaded. This keeps install order irrelevant and makes adapter hot-swap real.

---

## 1. Responsibilities

- Select the configured `DiscordConnectionFactory` by name.
- Register a `DiscordSink` in the `SyncBus`.
- Translate outbound `Message` values to the connection port.
- Translate inbound `DiscordMessage` values to `Message` on the `discord`
  channel.
- Never block package activation waiting for the remote gateway.

## 2. Non-responsibilities

- No JDA, WebSocket or REST client code.
- No Minecraft platform code.
- No routing decisions: `iflow` decides what to sync.
- No token persistence: the token comes from the module config.

---

## 3. Main components

| Component | Role |
|-----------|------|
| `SyncDiscordActivator` | Resolves `SyncBus` and `DiscordConnectionFactory`, registers `DiscordSink`. |
| `DiscordSink` | Neutral `SyncSink`: start/stop, outbound send, inbound translation. |
| `DiscordConfig` | Reads `config/sync-discord/config.yml` (legacy `sync/discord.yml` is read during migration). |
| `SyncDiscordModule` | Legacy kernel descriptor, kept for the kernel graph. |

Ports (in `core-api`):

| Type | Role |
|------|------|
| `DiscordConnection` | Adapter contract: `connect()` must return without waiting for READY. |
| `DiscordConnectionFactory` | Adapter discovery by `name()`. |
| `DiscordSettings` | Token and channel id. |
| `DiscordMessage` | Inbound message translated by the adapter. |
| `DiscordListener` | Inbound callback used by `DiscordSink`. |

---

## 4. Configuration

Canonical path: `config/sync-discord/config.yml`.

```yaml
enabled: true
token: "bot-token"
channel: 123456789012345678
format: "{sender}: {content}"
transport: jda   # jda | jdk
```

`transport` must match a factory registered by an installed adapter. If the
adapter is absent, the package stays active without a sink and logs the
available adapter names; there is no silent fallback to another transport. When
the configured adapter is installed later, the sink attaches automatically.

---

## 5. Lifecycle

```text
SyncDiscordActivator.start()
  → find SyncBus
  → load config
  → subscribe to ModuleActivatedEvent / ModuleDeactivatedEvent
  → tryAttach()
      → find DiscordConnectionFactory by name
      → new DiscordSink(factory, settings, logger)
      → bus.register(sink)
          → sink.start()
              → factory.create(...)
              → connection.setListener(...)
              → connection.connect()   (returns immediately)

ModuleActivatedEvent  → tryAttach() if the configured factory appeared
ModuleDeactivatedEvent → detach() if the selected factory disappeared
```

`stop()` unsubscribes and unregisters the sink; `SyncBus` stops it, which closes
the connection. The adapter owns token wiping and remote shutdown.

---

## 6. Tests

- `DiscordSinkTest` uses a fake `DiscordConnectionFactory`; no network.
- `PackageRuntimeTest.installsNeutralDiscordPackageWithATransportAdapter`
  installs the real package ZIP before the adapter, verifies that the sink
  attaches when the adapter appears, and that unloading the adapter removes the
  sink.
- The real adapters have their own tests:
  [`sync-discord-jdk`](../sync-discord-jdk/README.md) uses a loopback WebSocket
  server; [`sync-discord-jda`](../sync-discord-jda/README.md) avoids network.

---

## 7. Related modules

- [core-api](../core-api/README.md) — ports and suite contracts
- [sync-bus](../sync-bus/README.md) — sink lifecycle and fan-out
- [sync-common](../sync-common/README.md) — YAML/config path helpers
- [sync-discord-jda](../sync-discord-jda/README.md) — JDA adapter
- [sync-discord-jdk](../sync-discord-jdk/README.md) — zero-dependency adapter
