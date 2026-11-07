# TextFormatter Suite — Introduction

## What is TextFormatter Suite?

TextFormatter Suite is a modular chat formatting, routing, translation and
synchronization platform for Minecraft servers. It uses a neutral host pipeline,
`textformatter` channels, `chattranslator` providers, the iFlow policy hub, sync
sinks, a package manager and a static web editor.

The project is **pre-1.0**. `1.0.0` is the release label of the integrated base
and `1.0` is the YAML schema version. There is no supported `2.x` line and there
are no runtime migrations from older layouts.

## What works today

- **Host pipeline** — neutral dispatch, channel selection, formatting,
  translation and recipient routing.
- **TextFormatter** — MiniMessage and legacy templates, channel permissions,
  rate limits and sounds; translation is a separate pipeline stage.
- **Translation** — `chattranslator` facade with Google (`gtranslate`) and
  LibreTranslate (`ltranslate`) providers, exposed as the `translate` stage.
- **iFlow** — the `iflow` hub selects one of three coexisting backends:
  `iflow-firewall` (SpEL rules), `iflow-vm` (binary `.flow`) or `iflow-wasm`
  (WebAssembly).
- **Sync** — Discord, Telegram, HTTP, TCP/UDP, WebSocket, Velocity, Bukkit and
  Fabric event sinks.
- **Module Manager** — content-addressed acquisition, journaled commit and
  separate runtime activation.
- **Web editor** — import, edit, validate and export editor project ZIPs.
- **Extensions, presets and observability** — separate packages with their own
  lifecycles.
- **CoreTranslator** — independent, deprecated Bukkit-only PlaceholderAPI
  expansion; it is not a shim.

Real-server E2E tests, including Bukkit/Paper + PlaceholderAPI, are deferred
until after `1.0.0`.

## Requirements

- Java 17 or 21 (Java 21 recommended).
- Spigot/Paper 1.20.6+ or Fabric 1.21+ with Fabric Loader 0.16+.
- Velocity is supported through the sync modules.
- BungeeCord is not implemented.

## Installation

### Spigot/Paper

1. Place the Spigot host JAR in `plugins/`.
2. Start the server.
3. The data root is `plugins/TextFormatterSuite/`.
4. Edit `config/suite/config.yml`, `config/textformatter/` and the module
   directories you use.
5. Restart the server to load module configuration.

### Fabric

1. Place the Fabric host JAR in `mods/`.
2. Start the server.
3. The data root is `<server run dir>/textformatter-suite/` and contains the
   same `config/` layout.
4. Restart the server to load module configuration.

### Configuration model

The layout is canonical:

```text
<data>/
├── config/
│   ├── suite/
│   │   ├── config.yml
│   │   ├── commands.yml
│   │   └── messages.yml
│   ├── textformatter/channels/*.yml
│   ├── chattranslator/
│   │   ├── config.yml
│   │   └── translators/*.yml
│   ├── iflow/config.yml
│   ├── iflow-firewall/rules.yml
│   ├── iflow-vm/policy.flow
│   └── sync-*/…
├── manager/
│   ├── repo.yml
│   └── library/…
└── storage.yml
```

Flat files in the data root (`config.yml`, `channels/`, `translators/`,
`sync/`, `rules.yml`) are not read and are not migrated. Every runtime configuration `.yml`
declares `config-version: 1.0` at the root; `plugin.yml` is the only exception.

## Minimal host configuration

```yaml
# config/suite/config.yml
general:
  language: en
iflow:
  engine:
    parallel: false
sonido:
  enabled: true
chat:
  claim-mode: cancel-event
  log-to-console: true
config-version: 1.0
```

iFlow backend selection is separate:

```yaml
# config/iflow/config.yml
engine: iflow-firewall              # iflow-firewall | iflow-vm | iflow-wasm
fallback-on-unavailable: true
config-version: 1.0
```

`iflow.engine.kind` does not exist. The three backends may be installed
together; the hub activates exactly one.

## Commands

The root is configurable in `config/suite/commands.yml`; the default is `/suite`
with `/txf` as an alias. Built-in commands require
`textformatter.suite.admin`.

| Command | Description |
|---------|-------------|
| `/<root> help` | Show the active command tree. |
| `/<root> reload` | Reload `commands.yml` and re-register the root. It does **not** reload module configuration. |
| `/<root> list` | List installed packages and runtime state. |
| `/<root> state` | Show known module states. |
| `/<root> install <zip>` | Install and activate a local package ZIP. |
| `/<root> remove <pkg> [version] [channel]` | Deactivate and remove a package. |
| `/<root> upgrade` | Upgrade installed packages. |
| `/<root> repo install <pkg...>` | Install packages from repositories. |
| `/<root> repo remove <pkg> [version] [channel]` | Remove a package through repositories. |
| `/<root> repo upgrade` | Upgrade packages from repositories. |
| `/<root> cache clean` | Clear the download cache; installed packages are untouched. |
| `/<root> presets list` | List available presets. |
| `/<root> presets apply <preset>` | Apply a preset and reload active modules. |

To apply manual module configuration changes, restart the server. Applying a
preset is the only built-in operation that reloads active modules.

See [Commands Reference](08-Commands.md) for the complete table.

## Architecture

```text
┌──────────────────────────────────────────────────────────────┐
│ Spigot host │ Fabric host │ Velocity sync adapter            │
├──────────────────────────────────────────────────────────────┤
│ SuiteRuntime: commands, module host, package runtime         │
├──────────────────────────────────────────────────────────────┤
│ host: dispatch, channels, translation, iFlow hub             │
├──────────────────────────────────────────────────────────────┤
│ textformatter │ chattranslator │ iflow │ sync-* │ manager-*  │
├──────────────────────────────────────────────────────────────┤
│ core-api / core-render: neutral SPI and models                │
└──────────────────────────────────────────────────────────────┘
```

Modules are discovered through `ModuleActivator` and own their configuration
under `config/<module>/`. The host adapters never load feature classes
directly; the runtime resolves packages, contracts and dependencies.

## Main modules

| Area | Modules |
|------|---------|
| Contracts | `core-api`, `core-render` |
| Runtime | `kernel`, `suite-runtime`, `host` |
| Formatting | `textformatter`, `messages` |
| Translation | `chattranslator`, `gtranslate`, `ltranslate`, `coretranslator` |
| iFlow | `iflow`, `iflow-vm`, `iflow-firewall`, `iflow-wasm` |
| Sync | `sync-bus`, `sync-discord`, `sync-telegram`, `sync-http`, `sync-socket`, `sync-websocket`, `sync-velocity-core`, `sync-velocity`, `sync-bukkit`, `sync-fabric` |
| Manager | `manager-spi`, `manager-core`, `manager-apt` |
| Platform hosts | `spigot-host`, `fabric-host` |
| Supporting | `presets`, `observability`, `extension-api`, `example-extension`, `inworld`, `transport`, `performance`, `tester`, `loadtest` |

## Next steps

- [Configuration](02-Configuration.md)
- [Channels](03-Channels.md)
- [Translation](04-Translation.md)
- [iFlow Policies](05-iFlow-Rules.md)
- [Sync](06-Sync.md)
- [Web Editor](07-Web-Editor.md)
- [Commands](08-Commands.md)
- [Developer Guide](09-Developer-Guide.md)
- [API Reference](10-API-Reference.md)
- [Module Manager](10-Module-Manager.md)
- [Release Notes](11-Release-Notes.md)
- [Migration Guide](12-Migration-Guide.md)
- [FAQ](13-FAQ.md)
- [ADR index](14-ADR.md)
- [Contributing](15-Contributing.md)
- [Glossary](16-Glossary.md)
