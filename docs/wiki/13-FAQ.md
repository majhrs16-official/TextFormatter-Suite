# Frequently Asked Questions

## General

### What is TextFormatter Suite?

A modular chat formatting, routing, translation and synchronization platform for
Minecraft servers. It integrates a neutral host pipeline, `textformatter`
channels, `chattranslator` providers, the iFlow policy hub, sync sinks, the
module manager and a web editor.

### What platforms are supported?

- Spigot/Paper: 1.20.6+ (compiles against Paper API 1.21.4).
- Fabric: 1.21+ (Fabric Loader 0.16+); compiles.
- Velocity: through `sync-velocity-core` on backends and `sync-velocity` on the
  proxy.
- BungeeCord: not implemented.

### What Java version is required?

Java 17 or 21. Java 21 is recommended.

### Is it compatible with other chat plugins?

It replaces chat formatting. Disable other chat formatters to avoid double
formatting or competing event claims.

## Installation

### Spigot/Paper

1. Place `textformatter-suite-spigot.jar` in `plugins/`.
2. Start the server.
3. Edit `plugins/TextFormatterSuite/config/suite/config.yml`.
4. Restart the server to load module configuration.

### Fabric

1. Install Fabric Loader 0.16+.
2. Place `textformatter-suite-fabric.jar` in `mods/`.
3. Start the server.
4. The data root is `<server run dir>/textformatter-suite/` with the same
   `config/` layout.
5. Restart the server to load module configuration.

### Velocity / BungeeCord

Velocity is supported through the sync modules. BungeeCord is not implemented.

## Configuration

### Where are config files located?

- Spigot/Paper: `plugins/TextFormatterSuite/config/`.
- Fabric: the mod data directory, with the same `config/` structure.

The host reads `config/suite/`; each module reads `config/<module>/`. Flat files
in the data root are ignored.

### How do I reload configuration?

`/suite reload` only re-reads `config/suite/commands.yml` and re-registers the
command root. If the platform cannot unregister the old root, the runtime
reports `RESTART_REQUIRED`.

All other runtime configuration (host, channels, translators, sync, iFlow) is
read when the pipeline or module starts. Restart the server after editing those
files. `/<root> presets apply <preset>` is the only built-in operation that
rewrites files and reloads active modules.

### How do I reset to defaults?

There is no `/suite reset` command in the integrated core. Stop the server, back
up the data directory, remove the files you want to regenerate, and start again:
`DefaultConfigInstaller` writes only missing canonical defaults.

### Can I use environment variables?

No. The current host and module parsers read YAML files only. Environment
variables such as `TFS_GENERAL_LANGUAGE` are not supported.

### Why was my file rejected?

Every runtime configuration YAML must declare `config-version: 1.0` at the root. The parser
reports `version de configuracion no identificada (archivo editado o
corrupto?)` when it is absent. `plugin.yml` is excluded.

## Translation

### Which providers are supported?

- Google Translate (`gtranslate`, no API key).
- LibreTranslate (`ltranslate`, optional API key).

### How do I enable translation?

1. Install the provider package.
2. Set `active: true` in `config/chattranslator/translators/google.yml` or
   `config/chattranslator/translators/libre.yml`.
3. Set `default-backend` in `config/chattranslator/config.yml` if needed.
4. Restart the server.

### How do I set a player's language?

The core persists preferences through `UserLanguageStore`
(`storage.yml`, flat UUID → value). The integrated core does **not** register
`/suite lang` or `/suite toggle`; adapters or extensions write through the
service.

### How does auto-detection work?

With `lang-source: auto`, the pipeline asks the active provider to detect the
source language. On failure it falls back to the original text.

### Can I use custom providers?

Yes. Implement `Translator`/`TranslatorProvider` and register them from a module
or extension.

## iFlow Policies

### How do I create a policy?

Choose a backend in `config/iflow/config.yml`:

```yaml
engine: iflow-vm
fallback-on-unavailable: true
config-version: 1.0
```

For the VM, compile a `.flow` program and place it at
`config/iflow-vm/policy.flow`. For the firewall, put SpEL rules under `rules:`
in `config/iflow-firewall/rules.yml`.

### What are the editor node types?

| Type | Description |
|------|-------------|
| `input` | Entry point |
| `cond` | Matcher/filter (no inline expression) |
| `transform` | Rewrite, sounds, sleep |
| `loop` | Loop/retry |
| `sleep` | Delay |
| `output` | Deliver to channel |
| `redirect` | Forward to channel |

The editor graph uses `nodes:`/`edges:` and is validated by `iflow-firewall`; it
is not executed by the VM.

### How do I write conditions?

`iflow-vm` has no inline condition language or SpEL. Conditions and actions are
bytecode in the `.flow` program. The `condition` key in `rules.yml` belongs to
the legacy firewall's `rules:` list.

### Can several iFlow engines be installed?

Yes. `iflow-firewall`, `iflow-vm` and `iflow-wasm` may coexist. The `iflow` hub
activates exactly one and publishes `IflowHub`; `SuiteRuntime` requires the hub.
There is no `iflow.engine.kind`.

## Sync

### How do I set up Discord sync?

1. Create a bot at <https://discord.com/developers/applications>.
2. Enable `GUILD_MESSAGES` and `MESSAGE_CONTENT`.
3. Configure `config/sync-discord/config.yml`:

```yaml
enabled: true
token: "YOUR_BOT_TOKEN"
channel: 123456789012345678
intents: [GUILD_MESSAGES, MESSAGE_CONTENT]
config-version: 1.0
```

### How do I set up Velocity sync?

1. Install `sync-velocity-core` on every backend.
2. Install `sync-velocity` on the proxy.
3. Use the same `secret` everywhere.
4. Configure `servers` and `mapping` in
   `config/sync-velocity-core/config.yml`.

### How do I set up WebSocket sync?

1. Enable `config/sync-websocket/config.yml`.
2. Set `port`, `bind` and optional `token`.
3. Connect to the configured host/port; the integrated core does not expose a
   `/ws/chat` path router.

## Commands

### How do I change the command name?

Edit `config/suite/commands.yml`:

```yaml
root: [myname]
aliases:
  - [suite]
  - [txf]
  - [custom]
config-version: 1.0
```

Apply with `/<current-root> reload`. If the runtime reports
`RESTART_REQUIRED`, restart the server. `base-name` is retired.

### How do I add custom commands?

`commands.yml` declares only the root. Packages register `CommandSpec` nodes
through `ModuleContext.commands()`; there is no YAML action interpreter.

## Troubleshooting

### Messages not appearing

1. Check `permission`, `send-permission` and `receive-permission`.
2. Verify the channel file is in `config/textformatter/channels/`.
3. Use `/suite state` to confirm `textformatter` is active.
4. Check the console for channel load errors.

### Translation not working

1. Confirm the provider package is active (`/suite state`).
2. Check `active: true` in `config/chattranslator/translators/*.yml`.
3. Verify `config-version: 1.0`.
4. Check logs for HTTP or provider errors.

### Sync not working

1. Check `/suite state` for the sync package.
2. Verify the canonical `config/sync-*/` file.
3. Check tokens, secrets and ports.
4. Check the console; there are no `/suite sync` commands.

### Commands not working

1. Check `textformatter.suite.admin` (or the root command permission).
2. Run `/suite help`.
3. Look for command conflicts with other plugins.
4. Check `config/suite/commands.yml` for collision errors.

### High memory usage

1. Reduce installed packages and active sync sinks.
2. Check the JVM heap (`-Xmx`).
3. Use the observability module if installed.

### Rate limiting not working

1. Check `rate-limit-per-second` in the channel YAML.
2. Confirm the channel file is canonical and has `config-version`.
3. Check the firewall rules if that backend is active.

## Performance

### How to improve performance?

1. Enable `iflow.engine.parallel: true` for parallel recipient routing.
2. Disable unused sync sinks.
3. Keep channel files small and avoid unnecessary transforms.
4. Use the observability module to measure before changing settings.

### Recommended settings for large servers

```yaml
# config/suite/config.yml
iflow:
  engine:
    parallel: true
config-version: 1.0
```

There is no configurable translation-cache TTL or size in the integrated core;
`TranslationService` uses fixed safety limits.

## Development

### Build from source

```bash
./gradlew build
```

### Run tests

```bash
./gradlew test --no-daemon
```

### Run the full gate

```bash
./gradlew build checkArchitecture checkLocks --no-daemon
```

### Run benchmarks / load tests

```bash
./gradlew :src:loadtest:jmh
```

The load-test module is `src/loadtest`; it is not a `:suite:loadtest` project.

## Contributing

1. Fork the repository.
2. Create a feature branch.
3. Add tests for every bug fix.
4. Keep code, docs and the gate green.
5. Submit a pull request.

### Code style

- Java 17/21.
- Spotless (`./gradlew spotlessApply`).
- JUnit 5 tests.
- Javadoc for public APIs.

### Reporting bugs

1. Check existing issues.
2. Create a minimal reproduction.
3. Include version, platform, logs and the failing config.
4. Open a GitHub issue.

---

*FAQ v1.0 - Part of TextFormatter Suite Documentation*
