# Release Notes

## Current base: `1.0.0`

`1.0.0` is the release label of the integrated base; `1.0` is the YAML schema
version. The project is pre-1.0 and there is no supported `2.x` line.

### Integrated modules

- `core-api`, `kernel` and `suite-runtime` with the neutral module lifecycle.
- `host` pipeline: routing, formatting, translation and dispatch.
- `textformatter` channels, `chattranslator` facade and provider packages
  (`gtranslate`, `ltranslate`, `inworld`).
- `iflow` hub with `iflow-firewall`, `iflow-vm` and `iflow-wasm` backends.
- Sync sinks: Discord, Telegram, HTTP, TCP/UDP, Velocity, WebSocket, Bukkit and
  Fabric events.
- Manager: staged install, dependency resolution, package status and runtime
  activation.
- Web editor with canonical project import/export and the `rules.yml` graph.
- Extension API, presets, observability and load-test harness.

### Configuration contract

- Canonical layout: `config/suite/` for the host and `config/<module>/` for each
  module.
- Every runtime configuration YAML declares `config-version: 1.0`; `plugin.yml` is excluded.
- Missing version is an explicit error:
  `version de configuracion no identificada (archivo editado o corrupto?)`.
- Flat runtime paths and `iflow.engine.kind` do not exist and are not migrated.
- Module contracts are accepted in `[1.0.0, host]`.

### Validation

- `./gradlew test --no-daemon` passes.
- `./gradlew build checkArchitecture checkLocks --no-daemon` passes.

### Known limits

- Real end-to-end tests (Spigot/Paper, Fabric, Velocity, PlaceholderAPI) are
  deferred until after the `1.0.0` release.
- The wiki is documentation, not a contract; the ADR and the specifications take
  precedence.

## Historical notes

The previous `2.x` line (monolithic ChatTranslator) is historical and is not
supported by the current runtime. Old release notes and migration guides are
preserved in Git history; they are not current instructions.
