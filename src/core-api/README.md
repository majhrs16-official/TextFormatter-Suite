# core-api — Core Contracts & SPI

> **Purpose**: Defines all platform-agnostic interfaces, domain types, and Service Provider Interfaces (SPI) for the TextFormatter Suite. This module has **zero dependencies** on other modules — it is the foundation everything else builds upon.

---

## 1. Responsibilities

- Define the **Module** contract and lifecycle (`Module`, `ModuleDescriptor`, `Capability`, `Requirement`, `SemVer`)
- Define the **runtime lifecycle API** (`ModuleActivator`, `ModuleContext`, `ServiceRegistry`, `EventBus`, `Scheduler`, `ResourceTracker`, `PlatformPorts`, `ModuleState`, `ModuleActivatedEvent`, `ModuleDeactivatedEvent`)
- Define **shared cross-package contracts** (`api.channel.Channel`/`ChannelRegistry`, `api.iflow.Router` and routing value types)
- Define **message domain types** (`Message`, `ChatMessage`, `Actor`, `Channel`, `MessageType`, `Direction`, `Language`, `ColorMode`, `Formats`, `SoundSpec`)
- Define **SPI interfaces** for platform/implementation plugins:
  - `ActorDirectory` — resolve actors (players, consoles, etc.) by name/UUID
  - `Translator` / `TranslatorProvider` / `TranslatorManager` / `TranslationException` — translation pipeline (Clean Architecture: `host` depends only on these SPIs)
  - `TranslationService` — **legacy** high-level entry point (deprecated; use `TranslatorManager`)
  - `SyncSink` / `SyncListener` — cross-server message synchronization
  - `PlaceholderResolver` — resolve placeholders in messages
  - `PluginLogger` — abstract logging
  - `UserLanguageStore` — persist user language preferences
- Define **event types** (`MessageEvent`)
- Versioning via `SemVer`

---

## 2. Non-Responsibilities

- **No feature implementations** — contracts, value types and small thread-safe utilities only
- **No platform-specific code** — no Bukkit, Fabric, JDA, HTTP clients, etc.
- **No configuration loading** — that belongs in the feature packages
- **No module loading logic** — that belongs in `kernel`/`suite-runtime`

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| *(none)* | — | Foundation module; zero dependencies |

---

## 4. Consumers

**All modules** depend on `core-api`:
- `kernel` — uses `Module`, `ModuleDescriptor`, `Capability`, `Requirement`
- `textformatter` — uses `Message`, `Channel`, `TranslationService`, `PlaceholderResolver`
- `iflow-vm`/`iflow-firewall` — use `Message`, `Channel`, `ActorDirectory` and implement the shared `Router` contract
- `host` — uses nearly all SPIs and domain types (legacy wiring is being removed in F6/F4b)
- `suite-runtime` — implements the lifecycle API and publishes `ModuleActivatedEvent`/`ModuleDeactivatedEvent`
- `gtranslate`/`ltranslate` — publish `TranslatorProvider` in the module `ServiceRegistry`
- `chattranslator` — consumes providers and rebuilds its `TranslatorManager` on lifecycle events
- `transport` — uses `Message` for codecs
- Sync modules — implement `SyncSink`
- `observability` — uses `Message`, `Channel`
- `presets` — uses `Message`, `Channel`
- `manager-spi`/`manager-core`/`manager-apt` — use `ModuleDescriptor`, `ModuleCoordinate`
- `extension-api` — uses `Module` concepts
- `spigot-host` — implements all SPIs
- `fabric-host` — implements platform SPIs (compila; no probado en servidor real)

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `Module` | Base interface for all modules; defines `initialize()`, `shutdown()`, `getDescriptor()` |
| `ModuleDescriptor` | Metadata: id, version, capabilities, requirements, main class |
| `Capability` / `Requirement` | Module dependency declaration (name + version range) |
| `SemVer` | Semantic version parsing/comparison for requirements |
| `ModuleActivator` / `ModuleContext` | Runtime entry point and module-scoped view of services, events, scheduler and resources |
| `ServiceRegistry` | Mutable registry of live services; registrations disappear with their owner |
| `EventBus` | Scoped publish/subscribe used for runtime lifecycle and feature events |
| `Scheduler` / `ResourceTracker` | Module-owned tasks and resources; released on deactivation |
| `ModuleActivatedEvent` / `ModuleDeactivatedEvent` | Lifecycle notifications for hot install/unload consumers |
| `Channel` / `ChannelRegistry` | Shared channel model and registry in `api.channel` |
| `Router` / `RouteDecision` / `RouteOutcome` | Shared iFlow routing contract in `api.iflow` |
| `ActorDirectory` | SPI: resolve `Actor` by name/UUID; platform-specific impl in adapters |
| `TranslationService` | SPI: high-level translation entry point; `translate(text, targetLang)` |
| `Translator` | SPI: single translation provider; `translate(text, source, target)` |
| `TranslatorProvider` | SPI: provides `Translator` instances; registered via modules |
| `TranslatorManager` | SPI: manages multiple providers, fallback chains |
| `SyncSink` | SPI: send messages to remote servers (HTTP, Discord, TCP, etc.) |
| `SyncListener` | SPI: receive messages from remote servers |
| `PlaceholderResolver` | SPI: resolve `{placeholder}` in messages |
| `PluginLogger` | SPI: abstract logging for platform adapters |
| `UserLanguageStore` | SPI: persist/load user language preferences |
| `Message` / `ChatMessage` | Core message domain model |
| `Actor` | Message sender/recipient (player, console, webhook, etc.) |
| `Channel` | Logical chat channel (global, local, team, etc.) |
| `MessageEvent` | Event fired when message is processed |

---

## 6. Data Flow

`core-api` defines **contracts and value types**; the runtime data flow is implemented by `suite-runtime` and the feature packages:

```text
Platform Adapter (spigot-host/fabric-host)
       ↓ implements
ActorDirectory ←──────────────────
SyncSink ←──────────────────────── sync-http, sync-discord, sync-tcpudp, etc.
PlaceholderResolver ←───────────── Platform adapter

Module package (ModuleActivator)
       ↓ registers services
ServiceRegistry → EventBus → consumers
       ↑
ModuleActivatedEvent / ModuleDeactivatedEvent
```

---

## 7. Entry Points

None — this is a contract library. Entry points are in modules that **implement** these interfaces:
- `suite-runtime.ModuleHost` loads `ModuleActivator` implementations from package JARs
- `kernel.ModuleLoader` remains the legacy loader for `Module` implementations
- `host.SuiteBootstrap` wires the legacy host path
- Platform adapters provide `ActorDirectory`, `PlaceholderResolver`, `ChatDelivery`

---

## 8. Extension Points

| SPI | How to Extend |
|-----|---------------|
| `ModuleActivator` | Implement in a package and register it via `META-INF/services/me.majhrs16.suite.api.runtime.ModuleActivator` |
| `ServiceRegistry` / `EventBus` | Publish services or subscribe to lifecycle events from a `ModuleContext` |
| `ActorDirectory` | Implement in platform adapter (see `SpigotActorDirectory`, `FabricActorDirectory`) |
| `TranslationService` / `TranslatorProvider` | Implement in translation module (see `GTranslateProvider`, `LTranslateProvider`) |
| `SyncSink` | Implement in sync module (see `HttpSink`, `JdaDiscordSink`, `TcpSink`, `UdpSink`) |
| `PlaceholderResolver` | Implement in platform adapter (see `SpigotPlaceholderResolver`, `FabricPlaceholderResolver`) |
| `MessageRenderer` | Implement in the formatter package (`TextFormatterMessageRenderer`); adapters consume plain text without importing textformatter |
| `UserLanguageStore` | Implement in `host` (see `YamlUserLanguageStore`) |

---

## 9. Exploration Path

```
1. Module.java                    → Legacy Module contract & lifecycle
2. ModuleDescriptor.java          → Module metadata & dependencies
3. api/runtime/ModuleActivator.java → Runtime package entry point
4. api/runtime/ModuleContext.java → Module-scoped services, events, scheduler and resources
5. api/runtime/ServiceRegistry.java → Live service registry
6. api/runtime/EventBus.java      → Lifecycle and feature events
7. api/message/*.java             → Domain types (Message, Actor, Channel, etc.)
8. api/spi/TranslatorProvider.java → Translation provider SPI
9. api/iflow/Router.java          → Shared iFlow routing contract
```

---

## 10. Related Modules

- [kernel](../kernel/README.md) — Legacy loader for `Module` implementations
- [suite-runtime](../suite-runtime) — Implements the lifecycle API and package activation
- [textformatter](../textformatter/README.md) — Consumes `Message`, `Channel`, `TranslationService` and `PlaceholderResolver`
- [host](../host/README.md) — Legacy host wiring (F6/F4b removes feature dependencies)
- [chattranslator](../chattranslator/README.md) — Consumes `TranslatorProvider` services
- [gtranslate](../gtranslate/README.md) — Publishes `TranslatorProvider` (SPI)
- [ltranslate](../ltranslate/README.md) — Publishes `TranslatorProvider` (SPI)
- [iflow-vm](../iflow-vm/README.md) — Implements the shared `Router` contract
- [sync-http](../sync-http/README.md) — Implements `SyncSink`
- [spigot-host](../spigot-host/README.md) — Implements platform SPIs
- [fabric-host](../fabric-host/README.md) — Implements platform SPIs (compila; no probado en servidor real)