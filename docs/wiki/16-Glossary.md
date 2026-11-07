# Glossary

## A

**Actor**
An entity that can send or receive messages: player, console or system.

**ADR**
Architecture Decision Record. The canonical record is
[`docs/workdir/ADR.md`](../workdir/ADR.md).

## B

**Built-in preset**
A preset shipped with the suite: `standard`, `rpg`, `staff` and `minimal`.

## C

**Capability**
A named, versioned feature that a module provides or requires.

**Channel**
A named message route with formatting, permissions, rate limit and language
settings. Channels live in `config/textformatter/channels/<id>.yml`.

**ChannelRegistry**
Registry of loaded channels. Accessible through `PipelineContext.channels()` and `ExtensionContext.channels()` when `textformatter` is active.

**Claim mode**
How the platform adapter claims vanilla chat: `cancel-event`,
`clear-recipients` or `none`.

**ConfigVersion**
The `config-version` marker required at the root of every runtime `.yml` file.
The current schema is `1.0`; the only exception is `plugin.yml`.

**Core API**
The `core-api` module: SPI interfaces and platform-neutral domain models.

**CoreTranslator**
An independent, deprecated Bukkit-only PlaceholderAPI expansion
(`me.majhrs16.cot.CoreTranslator`). It uses the modern suite services and is not
a compatibility shim.

## D

**Direction**
Message audience: `INITIATOR`, `OTHERS`, `ALL`, `CONSOLE`, `WORLD`, `RADIUS`,
`PERMISSION`, `SPECIFIC`.

**Dispatcher**
See `PipelineEngine`.

**DispatchOutcome**
Result of a dispatch: pipeline status plus considered, delivered, silenced,
redirected and failed counts.

## E

**Extension**
A dynamically loadable addon implementing the `Extension` SPI.

**ExtensionContext**
Context granted to an extension: services, language store, channels, dispatch
and config access.

**ExtensionManager**
Manages extension discovery, loading, unloading and reloading.

## F

**Format**
A message template with MiniMessage/legacy syntax and placeholders.

**Formats**
A pair of template arrays: messages and tooltips.

## H

**Host**
The platform-neutral composition root. `SuiteRuntime` assembles the pipeline
engine, channels, dispatch, translation and the iFlow hub.

**HostConfig**
Immutable view of `config/suite/config.yml`.

**Hub**
See `IflowHub`.

## I

**iFlow**
The policy subsystem. The `iflow` hub selects one installed backend and
publishes `IflowHub`.

**IflowBackend**
SPI implemented by `iflow-firewall`, `iflow-vm` and `iflow-wasm`.

**IflowHub**
Stable facade over the active backend. `SuiteRuntime` requires the hub; an
engine cannot replace it by publishing a `Router` directly.

**iflow-firewall**
Legacy SpEL backend. Executes rules under `rules:` in
`config/iflow-firewall/rules.yml`; validates the editor graph but does not
execute `nodes:`/`edges:`.

**iflow-vm**
Deterministic VM backend. Executes a binary `.flow` program, normally
`config/iflow-vm/policy.flow`.

**iflow-wasm**
WebAssembly backend (Chicory). Requires a pinned module and reloads when the
pin changes.

## K

**Kernel**
The `kernel` module: `ModuleLoader`, `ModuleGraph` and ServiceLoader discovery.

## L

**Language**
ISO language code used by a channel or a player preference. `auto` detects the
source and `off` disables translation.

**LibreTranslate**
Self-hosted or public translation provider (`ltranslate`).

## M

**Message**
Immutable message object flowing through the pipeline.

**PipelineEngine**
Runs the ordered stages, resolves recipients, delivers messages and records the
trace.

**Module**
A JAR implementing the module SPI and discovered through
`ModuleActivator`.

**ModuleDescriptor**
Module metadata: id, version, contract version, platform and capabilities.

**ModuleGraph**
Dependency graph used to order module activation and detect cycles.

**ModuleHost**
Runtime owner of classloaders, activation, deactivation and hot upgrades.

**ModuleManager**
Package-level manager: acquisition, journaled commit, status and repository
operations.

## P

**PackageRuntime**
The only bridge between installed package facts and live module state.

**PlaceholderResolver**
SPI for placeholder resolution (PlaceholderAPI on Spigot, identity or platform
equivalent on Fabric).

**PolicyTarget**
Disposition applied to a message: `LOG`, `DROP`, `REJECT`, `REDIRECT`,
`CHANNEL_REDIRECT`, `RATE_LIMIT`.

**Preset**
A versioned configuration snapshot. `presets apply` writes the files and
reloads active modules in dependency order.

## R

**RateLimiter**
Token-bucket limiter used by channels and backend policies.

**RouteDecision**
Result of routing one message for one recipient.

**Router**
Neutral routing SPI. The hub exposes the active backend's router.

## S

**ScriptSurface**
Service surface exposed to VM programs for controlled message operations.

**SemVer**
Semantic Versioning with full `major.minor.patch` components.

**ServiceLoader**
Java SPI mechanism used for module and provider discovery.

**SPI**
Service Provider Interface; the extension points in `core-api`.

**PipelineOutcome**
Status, counters and trace produced by one `PipelineEngine.dispatch(...)`.

**SuiteRuntime**
Runtime that owns the module host, package runtime, commands and the message
pipeline.

**SyncBus**
Unified outbound/inbound pipeline with deduplication, per-sink isolation and
retry/backoff.

**SyncSink**
SPI for external sinks such as Discord, Telegram, HTTP, TCP/UDP, WebSocket and
Velocity.

## T

**Template**
Message template with MiniMessage or legacy formatting and placeholders.

**TemplateRenderer**
Renders templates with sender, recipient, content and language context.

**TextFormatter**
Module that owns the channel registry and template rendering.

**TransformOp**
Atomic policy operation: rewrite, sounds, sleep, language source/target, color
mode, PAPI formatting and channel override.

**TranslationService**
Manages active providers, caching, detection and fallback to the source text.

**Translator**
Provider contract for translation and language detection.

## U

**UserLanguageStore**
Service that persists each player's language preference in `storage.yml`.

## V

**Velocity**
Minecraft proxy supported through `sync-velocity-core` and the
`sync-velocity` proxy plugin.

## W

**Web Editor**
Static browser application for importing, editing, validating and exporting
editor project ZIPs.

**WebSocket**
Real-time transport used by `sync-websocket`.

## Y

**YAML schema**
The configuration contract version. The current value is `1.0`, written as
`config-version: 1.0`.

---
*Glossary v1.0 - Part of TextFormatter Suite Documentation*
