# sync-common — Shared Sync Configuration Utilities

> **Purpose**: Dependency package with the YAML reader and configuration-path
> resolver shared by `sync-bus` and every `sync-*` sink package.

---

## 1. Responsibilities

- Read sync configuration files tolerantly: a missing, unreadable, empty or
  non-map YAML file yields an empty map instead of throwing.
- Coerce scalar YAML values (`string`, `bool`, `integer`, `longValue`) with
  explicit fallbacks.
- Resolve the canonical `config/sync-*/` paths and the legacy `sync/*.yml`
  fallback used during migration.

---

## 2. Non-Responsibilities

- No sink lifecycle, transport or message delivery.
- No validation of semantic fields (tokens, ports, chat ids): each sink module
  owns its schema and its `usable()` rule.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
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
| `spigot-host` / `fabric-host` | Legacy path resolution until F4b |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `SyncYaml` | Tolerant YAML reader and scalar coercion helpers |
| `SyncConfigPaths` | Canonical and legacy configuration path resolution |

---

## 6. Tests

```bash
./gradlew :src:sync-common:test
```

`SyncYamlTest` covers missing/scalar/invalid YAML and scalar coercion.
`SyncConfigPathsTest` covers canonical paths, legacy fallback and canonical
destination selection.
