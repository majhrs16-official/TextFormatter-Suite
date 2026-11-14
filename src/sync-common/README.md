# sync-common — Shared Sync Configuration Utilities

> **Purpose**: Dependency package with the YAML reader, configuration-path
> resolver and neutral event-processing helpers shared by `sync-*` packages.

---

## 1. Responsibilities

- Read sync configuration files tolerantly: a missing, unreadable, empty or
  non-map YAML file yields an empty map instead of throwing.
- Coerce scalar YAML values (`string`, `bool`, `integer`, `longValue`) with
  explicit fallbacks.
- Resolve the canonical `config/sync-*/` paths; the flat `sync/*.yml` layout is
  neither read nor migrated.
- Provide the platform-neutral event helpers used by `sync-bukkit` and
  `sync-fabric`: channel selection, translation gating, `Message` construction
  and dispatch.
- Publish `LocalSyncSink`, the sink that both platform packages expose as
  `bukkit`/`fabric`. It owns the LCM (`backend.info`, `message.send`,
  `message.deliver`) and the environment surface (`local.info`, `player.*`,
  `console.command`, `scoreboard.set`, `event.info`, `event.cancel`).
- Define the neutral `LocalEnvironment` port, with `LocalPlayer`, `LocalEvent`
  and `LocalActionException` (`NOT_FOUND`, `UNSUPPORTED`, `INVALID_ARGUMENT`,
  `HOST_FAILURE`). Platform types never cross this port.

---

## 2. Non-Responsibilities

- No transport, connection lifecycle or fan-out: those belong to `sync-bus`.
  `LocalSyncSink` delegates physical local delivery to the host's
  `LocalDelivery` port.
- No platform SDK types: Bukkit/Fabric events stay in their platform package.
  The platform implements `LocalEnvironment`; this package only consumes it.
- No validation of semantic fields (tokens, ports, chat ids): each sink module
  owns its schema and its `usable()` rule.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Message`, `ChannelRegistry`, `MessageDispatch`, `ActorDirectory`, `SyncSink`, `SyncSurface` |
| `snakeyaml` | Compile | Safe YAML parsing |
| JDK | Compile | `Path`, `Files` |

It is distributed as a `DEPENDENCY` package, so the manager installs its JAR in
`library/dependencies` and each module classloader resolves it from its
declared dependency scope.

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `sync-bus` | No longer uses it directly; kept as the shared package for sinks |
| `sync-discord` | `DiscordConfig` |
| `sync-socket` | `SocketConfig` |
| `sync-telegram` | `TelegramConfig` |
| `sync-websocket` | `WebSocketSyncConfig` |
| `sync-bukkit` | `SyncEventProcessor`, `EventRules`, `ChannelSelector`, `LocalSyncSink`, `LocalEnvironment` |
| `sync-fabric` | `SyncEventProcessor`, `EventRules`, `ChannelSelector`, `LocalSyncSink`, `LocalEnvironment` |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `SyncYaml` | Tolerant YAML reader and scalar coercion helpers |
| `SyncConfigPaths` | Canonical `config/sync-*/` path resolution |
| `ChannelSelector` | Chooses the first permitted `CHAT` channel, with `chat` fallback |
| `EventRules` | Conventional event channels and `off` translation gating |
| `EventMessages` | Builds neutral `Message` values for chat and typed events |
| `SyncEventProcessor` | Dispatches chat/join/quit/death through `MessageDispatch` |
| `LocalSyncSink` | Sink `bukkit`/`fabric`: LCM, `local.info` and environment actions |
| `LocalEnvironment` | Neutral port implemented by each platform: players, message, kick, console, scoreboard, event |
| `LocalPlayer` / `LocalEvent` | Immutable neutral snapshots exposed by the port |
| `LocalActionException` | Typed failure that the sink maps to `SyncResult` |

---

## 6. Tests

```bash
./gradlew :src:sync-common:test
```

`SyncYamlTest` covers missing/scalar/invalid YAML and scalar coercion.
`SyncConfigPathsTest` covers canonical paths and that the flat `sync/` layout is
ignored.
`SyncEventProcessorTest` covers chat fan-out, console copy, `off` translation,
channel fallback and typed-event gating.
`LocalSyncSinkTest` covers the full surface, the LCM, `Principal.Official` denial,
invalid arguments, the four failure families of `LocalActionException`, a foreign
backend and `message.send` being rejected in favour of `message.deliver`.
