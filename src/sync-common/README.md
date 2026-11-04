# sync-common — Shared Sync Configuration Utilities

> **Purpose**: Dependency package with the YAML reader, configuration-path
> resolver and neutral event-processing helpers shared by `sync-*` packages.

---

## 1. Responsibilities

- Read sync configuration files tolerantly: a missing, unreadable, empty or
  non-map YAML file yields an empty map instead of throwing.
- Coerce scalar YAML values (`string`, `bool`, `integer`, `longValue`) with
  explicit fallbacks.
- Resolve the canonical `config/manager/sync-*/` paths and the legacy `sync/*.yml`
  fallback used during migration.
- Provide the platform-neutral event helpers used by `sync-bukkit` and
  `sync-fabric`: channel selection, translation gating, `Message` construction
  and dispatch.

---

## 2. Non-Responsibilities

- No sink lifecycle, transport or message delivery.
- No platform SDK types: Bukkit/Fabric events stay in their platform package.
- No validation of semantic fields (tokens, ports, chat ids): each sink module
  owns its schema and its `usable()` rule.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Message`, `ChannelRegistry`, `MessageDispatch`, `ActorDirectory` |
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
| `sync-bukkit` | `SyncEventProcessor`, `EventRules`, `ChannelSelector` |
| `sync-fabric` | `SyncEventProcessor`, `EventRules`, `ChannelSelector` |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `SyncYaml` | Tolerant YAML reader and scalar coercion helpers |
| `SyncConfigPaths` | Canonical and legacy configuration path resolution |
| `ChannelSelector` | Chooses the first permitted `CHAT` channel, with `chat` fallback |
| `EventRules` | Conventional event channels and `off` translation gating |
| `EventMessages` | Builds neutral `Message` values for chat and typed events |
| `SyncEventProcessor` | Dispatches chat/join/quit/death through `MessageDispatch` |

---

## 6. Tests

```bash
./gradlew :src:sync-common:test
```

`SyncYamlTest` covers missing/scalar/invalid YAML and scalar coercion.
`SyncConfigPathsTest` covers canonical paths, legacy fallback and canonical
destination selection.
`SyncEventProcessorTest` covers chat fan-out, console copy, `off` translation,
channel fallback and typed-event gating.
