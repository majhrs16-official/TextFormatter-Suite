# core-api — Core Contracts & SPI

> **Purpose**: Defines all platform-agnostic interfaces, domain types, and Service Provider Interfaces (SPI) for the TextFormatter Suite. This module has **zero dependencies** on other modules — it is the foundation everything else builds upon.

---

## 1. Responsibilities

- Define the **Module** contract and lifecycle (`Module`, `ModuleDescriptor`, `Capability`, `Requirement`, `SemVer`)
- Define the **runtime lifecycle API** (`ModuleActivator`, `ModuleContext`, `ServiceRegistry`, `EventBus`, `Scheduler`, `ResourceTracker`, `PlatformPorts`, `PlatformHandle`, `ModuleState`, `ModuleActivatedEvent`, `ModuleDeactivatedEvent`)
- Define **shared cross-package contracts** (`api.channel.Channel`/`ChannelRegistry`, `api.iflow.Router` and routing value types)
- Define **sync action contracts** (`api.sync.ActionKind`, `Principal`, `SyncActionSpec`, `SyncCall`, `SyncParamSpec`, `SyncResult`, `SyncSurface`, `SyncValueType`)
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
- `host` — uses the neutral SPI and domain types; production feature wiring was removed in F4b/F6
- `suite-runtime` — implements the lifecycle API and publishes `ModuleActivatedEvent`/`ModuleDeactivatedEvent`
- `gtranslate`/`ltranslate` — publish `TranslatorProvider` in the module `ServiceRegistry`
- `chattranslator` — consumes providers and rebuilds its `TranslatorManager` on lifecycle events
- `transport` — uses `Message` for codecs
- Sync modules — implement `SyncSink`
- `observability` — uses `Message`, `Channel`
- `presets` — uses `Message`, `Channel`
- `manager-spi`/`manager-core`/`manager-apt` — use the package model and `me.majhrs16.suite.api.ModuleDescriptor`; the duplicate legacy descriptors were removed in F7
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
| `ModuleActivator` / `ModuleContext` | Runtime entry point and module-scoped view of services, events, scheduler and resources; `isShuttingDown()` distinguishes a full shutdown from a hot unload |
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
| `SyncSink` | SPI: send messages to remote servers and publish a sync action surface (HTTP, Discord, TCP, etc.) |
| `SyncListener` | SPI: receive messages from remote servers |
| `SyncSurface` / `SyncActionSpec` / `SyncParamSpec` | Declarative actions published by each sync backend; every surface includes the least common multiple (`backend.info`, `message.send`) |
| `SyncCall` / `SyncResult` | One action invocation and its explicit outcome (`Ok`, `Denied`, `Unsupported`, `InvalidArgument`, `NotFound`, `Conflict`, `RateLimited`, `Timeout`, `HostFailure`) |
| `Principal` / `ActionKind` / `SyncValueType` | Caller origin, authorization nature and neutral argument vocabulary; no SDK type crosses the boundary |
| `PlaceholderResolver` | SPI: resolve `{placeholder}` in messages |
| `PluginLogger` | SPI: abstract logging for platform adapters |
| `UserLanguageStore` | SPI: persist/load user language preferences |
| `Message` / `ChatMessage` | Core message domain model |
| `Actor` | Message sender/recipient (player, console, webhook, etc.) |
| `Channel` | Logical chat channel (global, local, team, etc.) |
| `MessageEvent` | Event fired when message is processed |

### Sync action surface

`SyncSink.surface()` publishes the actions a backend accepts. Every surface must
contain the least common multiple: `backend.info` (`READ`, 5 s) and
`message.send` (`WRITE`, 15 s). The backend namespace must equal
`SyncSink.name()`; the bus rejects a backend that does not publish the LCM.

`SyncCall` is the invocation envelope: backend, local action, neutral arguments,
`Principal`, idempotency key and audit reason. The canonical name is
`backend.action`. Arguments use `SyncValueType` (`STRING`, `INTEGER`, `BOOLEAN`,
`DURATION`, `ACTOR_REF`, `CHANNEL_REF`, `MESSAGE_REF`, `ROLE_REF`, `OBJECT`) and
never carry SDK objects.

`SyncResult` is a sealed, explicit outcome; there is no silent skip and no
fabricated success. Authority is fail-closed in this slice:
`Principal.Official` may only invoke the LCM and `Principal.Program` is denied
until the operator grant store exists.

---

## 6. Data Flow

`core-api` defines **contracts and value types**; the runtime data flow is implemented by `suite-runtime` and the feature packages:

```text
Platform Adapter (spigot-host/fabric-host)
       ↓ implements
ActorDirectory ←──────────────────
SyncSink ←──────────────────────── sync-http, sync-discord, sync-socket, etc.
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
- `SuiteRuntime` builds the pipeline from `ServiceRegistry`
- Platform adapters provide `ActorDirectory`, `PlaceholderResolver`, `ChatDelivery`

---

## 8. Extension Points

| SPI | How to Extend |
|-----|---------------|
| `ModuleActivator` | Implement in a package and register it via `META-INF/services/me.majhrs16.suite.api.runtime.ModuleActivator` |
| `ServiceRegistry` / `EventBus` | Publish services or subscribe to lifecycle events from a `ModuleContext` |
| `PlatformHandle` | Opaque native handle registered by the host; only platform packages resolve it |
| `ActorDirectory` | Implement in platform adapter (see `SpigotActorDirectory`, `FabricActorDirectory`) |
| `TranslationService` / `TranslatorProvider` | Implement in translation module (see `GTranslateProvider`, `LTranslateProvider`) |
| `SyncSink` | Implement in sync module (see `HttpSink`, `JdaDiscordSink`, `TcpSink`, `UdpSink`); override `surface()`/`invoke()` for native actions. Declaring an action without executing it is a contract violation |
| `PlaceholderResolver` | Implement in platform adapter (see `SpigotPlaceholderResolver`, `FabricPlaceholderResolver`) |
| `MessageRenderer` | Implement in the formatter package (`TextFormatterMessageRenderer`); adapters consume plain text without importing textformatter |
| `MessageDispatch` / `DispatchOutcome` | Hosts implement dispatch; adapters and diagnostics consume neutral counters without importing the pipeline engine or the removed dispatcher types |
| `PresetService` | Neutral preset application port; `presets` implements it and the host resolves it from the runtime without importing the package |
| `TestProbe` | Neutral live-runtime probe; `tester` owns the scenarios and `spigot-host` implements the adapter without importing `tester` |
| `TestRunner` / `TestRunnerFactory` | Neutral test execution contract and factory; `tester` implements them so platform commands never import the package |
| `UserLanguageStore` | Implement in `host` (see `YamlUserLanguageStore`) |

---

## 9. Exploration Path

```
1. Module.java                    → Legacy Module contract & lifecycle
2. ModuleDescriptor.java          → Module metadata & dependencies
3. api/runtime/ModuleActivator.java → Runtime package entry point
4. api/runtime/ModuleContext.java → Module-scoped services, events, scheduler and resources
5. api/runtime/PlatformHandle.java → Opaque bridge to the host plugin/server object
6. api/runtime/ServiceRegistry.java → Live service registry
7. api/runtime/EventBus.java      → Lifecycle and feature events
8. api/message/*.java             → Domain types (Message, Actor, Channel, etc.)
9. api/spi/TranslatorProvider.java → Translation provider SPI
10. api/spi/PresetService.java → Neutral preset application port
11. api/spi/TestProbe.java → Neutral live-runtime test probe
12. api/spi/TestRunner.java → Neutral test execution contract
13. api/iflow/Router.java          → Shared iFlow routing contract
14. api/sync/SyncSurface.java      → Actions published by a sync backend
15. api/sync/SyncCall.java         → Action invocation envelope
16. api/sync/SyncResult.java       → Explicit action outcome
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