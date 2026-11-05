# Migration Guide (pre-1.0)

## Scope

The current runtime does **not** migrate old layouts or old schema versions.
`1.0.0` is the release label of the integrated base; `1.0` is the YAML schema
version. If a file cannot declare `config-version: 1.0`, it is rejected.

This is deliberate: pre-1.0 removes compatibility paths instead of carrying a
second reader.

## Canonical layout

Move or recreate configuration in the canonical locations:

| Old flat path | Current canonical path |
|---------------|------------------------|
| `config.yml` | `config/suite/config.yml` |
| `messages.yml` | `config/suite/messages.yml` |
| `commands.yml` | `config/suite/commands.yml` |
| `channels/*.yml` | `config/textformatter/channels/*.yml` |
| `translators/*.yml` | `config/chattranslator/translators/*.yml` |
| `sync/discord.yml` | `config/sync-discord/config.yml` |
| `sync/telegram.yml` | `config/sync-telegram/config.yml` |
| `sync/http.yml` | `config/sync-http/config.yml` |
| `sync/websocket.yml` | `config/sync-websocket/config.yml` |
| `sync/tcp.yml` | `config/sync-socket/tcp.yml` |
| `sync/udp.yml` | `config/sync-socket/udp.yml` |
| `sync/velocity.yml` | `config/sync-velocity-core/config.yml` |
| `rules.yml` | `config/iflow-firewall/rules.yml` |
| `iflow.yml` | `config/iflow/config.yml` |
| `inworld.yml` | `config/inworld/config.yml` |

The old paths are ignored. The installer only writes defaults to the canonical
destination and never overwrites an existing file.

## Schema changes

1. Add `config-version: 1.0` at the root of every runtime configuration YAML (preferably last).
   `plugin.yml` is the only exception.
2. Remove `iflow.engine.kind`. Backend selection lives in
   `config/iflow/config.yml`:
   ```yaml
   engine: iflow-firewall
   fallback-on-unavailable: true
   config-version: 1.0
   ```
3. If you use the firewall, put executable rules under `rules:`:
   ```yaml
   rules:
     - id: drop-private
       target: DROP
       channel: private
   config-version: 1.0
   ```
   A root-level list is rejected because it cannot declare a version. The editor
   graph stays under `nodes:`/`edges:`.
4. Use the module-specific sync keys (`outboundUrl`, `inboundPort`, `chatId`,
   `remoteHost`, …) documented in [Configuration](02-Configuration.md).
5. Rename `chat.claim-mode` values to `cancel-event`, `clear-recipients` or
   `none`.

## Module contracts

Modules declare a full SemVer contract. The accepted range is
`[1.0.0, host]`:

- Below `1.0.0`: rejected.
- Above the host version: rejected.
- Abbreviated forms such as `1.5` or `2.5` are not accepted.

Installing an incompatible package leaves it installed but reports the activation
failure; no files are rolled back.

## What was removed

- Flat runtime paths and their fallback readers.
- The `DefaultConfigInstaller` migration step.
- `HostConfig.Engine` / `iflow.engine.kind`.
- The `Router` fallback in `SuiteRuntime`; the iFlow hub is required.
- Root-level `rules.yml` list support.
- Compatibility tests that asserted the old contract.

`CoreTranslator` remains an independent deprecated Bukkit-only PlaceholderAPI
expansion (`%cot_*`); it is not a compatibility shim.

## Upgrade checklist

- [ ] Back up `plugins/TextFormatterSuite/` (or the mod data directory).
- [ ] Move files to `config/suite/` and `config/<module>/`.
- [ ] Add `config-version: 1.0` to every runtime configuration YAML.
- [ ] Remove `iflow.engine.kind` and configure `config/iflow/config.yml`.
- [ ] Convert `rules.yml` to the map form with `rules:` or `nodes:`/`edges:`.
- [ ] Restart the server to load module configuration. `/suite reload` only
      re-reads `config/suite/commands.yml`.
- [ ] Check the console for explicit version/path errors.
