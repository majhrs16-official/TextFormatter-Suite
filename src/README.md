# TextFormatter Suite — Source Code Map

> **Purpose**: This is the entry point for exploring the TextFormatter Suite source code. It maps the real module structure, responsibilities, dependencies, and entry points so you can navigate the codebase efficiently.

---

## 1. Architecture at a Glance

```
TextFormatter Suite
│
├── Core (platform-agnostic)
│   ├── core-api          → Core interfaces, SPI, domain types
│   ├── kernel            → Module loading & dependency resolution
│   ├── textformatter     → Template engine, placeholders, channels
│   ├── iflow             → hub que descubre `IflowBackend` y publica el `Router` estable; selección y fallback
│   ├── iflow-vm          → VM 16-bit flat 64KB + SoftMMU 4KB, bytecode `.flow`, `HOST` instruction, fuel budget (base de la VM de 32 bits)
│   ├── iflow-wasm        → motor WebAssembly (`.wasm`) con Chicory; contrato v3, router, SHA-256 y hot-reload
│   ├── iflow-firewall    → motor de reglas SpEL legacy + grafo del editor
│   ├── host              → Shared bootstrap, dispatch, configuration
│   ├── messages          → Message catalog (serialization formats)
│   ├── chattranslator    → Translation facade and provider lifecycle
│   ├── coretranslator    → Legacy Bukkit-only PlaceholderAPI expansion (%cot_*); deprecated
│   ├── transport         → Transport abstraction (HTTP, codecs; dependency package)
│   ├── gtranslate        → Google Translate provider
│   ├── ltranslate        → LibreTranslate provider
│   ├── observability     → Metrics, health checks, debug endpoints
│   ├── presets           → Preset catalog, YAML import/export and canonical file target
│   ├── manager-spi       → Package metadata, manifests, versions and repository config
│   ├── manager-core      → Package installer + local package database
│   ├── manager-apt       → Repositories, dependency resolver, APT installer
│   ├── extension-api     → Extension system API
│   ├── inworld           → In-world integration
│   └── performance       → Profiling & optimization
│
├── Sync Transports (platform-agnostic)
│   ├── sync-http         → HTTP webhook sink
│   ├── sync-socket       → TCP/UDP sinks
│   ├── sync-discord      → Discord sink (neutral)
│   ├── sync-discord-jda  → Discord JDA adapter
│   ├── sync-discord-jdk  → Discord JDK adapter
│   ├── sync-telegram     → Telegram sink
│   ├── sync-websocket    → WebSocket sink
│   ├── sync-velocity-core → Neutral Velocity sink
│   ├── sync-velocity     → Velocity plugin adapter
│   ├── sync-common       → Shared YAML and neutral event helpers (DEPENDENCY)
│   └── sync-bus          → Unified fan-out/lifecycle bus
│
└── Platform Adapters
    ├── spigot-host       → Bukkit/Paper bootstrap (Java 21)
    ├── fabric-host       → Fabric bootstrap (Java 21)
    ├── sync-bukkit       → Event capture package (Platform: spigot)
    └── sync-fabric       → Event capture package (Platform: fabric)
```

**Key architectural principles observed in code:**
- `core-api` has **zero dependencies** on other modules — it defines contracts only
- `kernel` depends only on `core-api` — loads modules via SPI
- `host` is the **neutral pipeline** — dispatch, config and message catalog; it does not depend on features or `kernel`
- `suite-runtime` is the composition root — it builds the pipeline from installed packages and owns package lifecycle
- Platform adapters (`spigot-host`, `fabric-host`) depend only on `core-api`, `core-render`, `suite-runtime` and their SDK
- Event capture lives in downloadable `sync-bukkit`/`sync-fabric` packages, not in the host JAR
- Commands live in the neutral `api.command` tree; the hosts only register the root adapter (F4d)
- Sync modules depend on `core-api`; technology adapters add `transport`, JDA or a platform SDK
- Feature libraries that do not cross an API boundary are `DEPENDENCY` packages (`snakeyaml`, `json`, `gson`, `java-websocket`, `chicory`, `prometheus`, `spring-expression`, `jackson-databind`, `jda`); Adventure stays on the host because `core-render` exposes its types
- Host shadow JARs contain only the bootstrap/runtime; `verifyShadowJarHasNoFeatures` fails the build if a feature leaks in
- `modulePackage` builds a reproducible ZIP plus `.sha256`; `thirdPartyPackages` builds dependency ZIPs; `moduleRepository` writes `packages.list` and `SHA256SUMS` for a public base URL
- **No circular dependencies** between core modules

---

## 2. Directory Map

| Directory | Responsibility | Depends On | Used By |
|-----------|----------------|------------|---------|
| `core-api` | Core interfaces, SPI, domain types and cross-package contracts (Module, Actor, Channel, ChannelRegistry, Router, TranslationService, SyncSink, MessageRenderer, CommandRegistry, etc.) | — | **All modules** |
| `kernel` | Module discovery, dependency graph, loading, SPI resolution | `core-api` | `suite-runtime`, `manager-core`, test fixtures |
| `textformatter` | Template rendering (MiniMessage), placeholders | `core-api` | `host`, `spigot-host`, `fabric-host` |
| `iflow` | Hub de selección: descubre `IflowBackend`, publica `IflowHub` y `Router`, aplica `engine`/`fallback-on-unavailable` desde `config/iflow/config.yml` | `core-api`, `snakeyaml` | `suite-runtime`, `spigot-host`, `fabric-host` |
| `iflow-vm` | VM 16-bit flat 64KB + SoftMMU 4KB, bytecode `.flow`, `HOST` instruction, fuel budget, `VmPolicyEngine`, `VmInterpreter`, `SoftMMU`; base de la VM de 32 bits | `core-api`, `logger` (dependency package) | `iflow` (backend), `host` legacy |
| `iflow-wasm` | Motor WebAssembly (`.wasm`) con Chicory; contrato v3, `WasmPolicyRouter`, SHA-256 obligatorio y hot-reload; publica `IflowBackend` | `core-api`, `com.dylibso.chicory:runtime` | `iflow` (backend), `host` legacy |
| `iflow-firewall` | Motor de reglas SpEL legacy restaurado desde `933bdd3^` (`DefaultRouter`, `RuleExpressionEvaluator`, `Rule`, `TransformOp`) + validador del grafo del editor | `core-api`, `spring-expression`, `jackson`, `snakeyaml` | `iflow` (backend), `host` legacy, web editor |
| `coretranslator` | Expansión PlaceholderAPI legacy `%cot_*`; Bukkit-only, *deprecated*, mantenida | `core-api`, Paper API, PlaceholderAPI | `spigot-host` (instalado por el Manager) |
| `host` | Neutral message dispatch, config loading and `SuiteHost` assembly; feature-free | `core-api`, `core-render`, `snakeyaml`, `gson` | `suite-runtime` |
| `messages` | Message catalog (serialized message formats) | — | `host`, `spigot-host`, `fabric-host` |
| `transport` | Transport abstraction (Transport, HttpTransport, MessageCodec) | `core-api` | `sync-http`, `sync-socket`, `sync-discord`, `gtranslate`, `ltranslate` |
| `gtranslate` | Google Translate API provider | `core-api`, `transport` | `host`, `spigot-host`, `fabric-host` |
| `ltranslate` | LibreTranslate API provider | `core-api`, `transport` | `host`, `spigot-host`, `fabric-host` |
| `observability` | Prometheus metrics, health checks, debug/metrics HTTP endpoints | `core-api`, `host`, `textformatter` | `spigot-host`, `fabric-host` |
| `presets` | Preset catalog, YAML import/export and canonical file persistence (no execution engine) | `core-api`, `snakeyaml` | `suite-runtime` (paquete `PACKAGE`; comandos `presets list/apply`) |
| `manager-spi` | Package metadata, manifests, versions and repository config | `core-api` | `manager-core`, `manager-apt`, `suite-runtime`, hosts |
| `manager-core` | Package installer, local package database, durable journal, versioned layout | `core-api`, `manager-spi`, `kernel` | `manager-apt`, `suite-runtime` |
| `manager-apt` | Repository manager, dependency resolver, stage-1 cache, batch installer/facade | `core-api`, `manager-spi`, `manager-core` | `suite-runtime` |
| `extension-api` | Extension system API (Extension, ExtensionContext, ExtensionManager) | `core-api` | `example-extension`, `spigot-host`, `fabric-host` |
| `inworld` | Neutral in-world domain (signs, containers, books, WORLD/RADIUS, click/hover); packaged as `PACKAGE` | `core-api` | `spigot-host` (`SpigotInWorldListener`), `suite-runtime` |
| `performance` | Hotspot detection, profiling, cache/memory optimization | `core-api` | `spigot-host`, `fabric-host` |
| `sync-http` | HTTP webhook sync sink | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-socket` | TCP/UDP sync sinks | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-discord` | Neutral Discord sink; selects a `DiscordConnectionFactory` by config | `core-api`, `sync-common` | `spigot-host`, `fabric-host` |
| `sync-discord-jda` | JDA `discord-transport` adapter (provides the capability) | `core-api`, JDA | `spigot-host` |
| `sync-discord-jdk` | JDK WebSocket/REST `discord-transport` adapter | `core-api`, `transport` | optional |
| `sync-telegram` | Telegram sync sink | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-websocket` | WebSocket sync sink | `core-api` | `spigot-host`, `fabric-host` |
| `sync-velocity-core` | Neutral Velocity sink; queue, mapping, retry and payload codec | `core-api`, `sync-common`, `transport` | `sync-velocity`, optional suite hosts |
| `sync-velocity` | Velocity plugin adapter; channel registration and `sendPluginMessage` | `sync-velocity-core`, Velocity API | Velocity proxy |
| `sync-bus` | Unified fan-out, deduplication, retries and sink lifecycle | `core-api` | `spigot-host`, `fabric-host` |
| `sync-common` | Shared YAML reader, config paths and neutral event helpers (`DEPENDENCY`) | `core-api`, `snakeyaml` | `sync-*`, `sync-bukkit`, `sync-fabric` |
| `sync-bukkit` | Bukkit/Paper event capture package (`PACKAGE`, Platform `spigot`) | `core-api`, `sync-common`, Paper API | `spigot-host` (instalado por el Manager) |
| `sync-fabric` | Fabric event capture package (`PACKAGE`, Platform `fabric`) | `core-api`, `sync-common`, Fabric API/Minecraft | `fabric-host` (instalado por el Manager) |
| `spigot-host` | Bukkit/Paper bootstrap and platform ports | `core-api`, `core-render`, `suite-runtime`, Paper API | — (leaf) |
| `fabric-host` | Fabric bootstrap and platform ports | `core-api`, `core-render`, `suite-runtime`, Fabric API/Minecraft | — (leaf) |
| `example-extension` | Example extension implementation | `extension-api` | — (leaf) |
| `loadtest` | Load testing utilities | — | `fabric-host` |
| `tester` | Runtime pipeline test service behind the neutral `TestProbe`; packaged as `PACKAGE` | `core-api` | `spigot-host` (`SpigotTestProbe`), `fabric-host` (`FabricTestProbe`), `suite-runtime` |

---

## 3. Module Index

| Module | Purpose | Main Responsibility | Key Classes | Entry Points | README |
|--------|---------|---------------------|-------------|--------------|--------|
| `core-api` | Contracts & SPI | Define all platform-agnostic interfaces and shared contracts | `Module`, `ModuleActivator`, `ServiceRegistry`, `ModuleActivatedEvent`, `ActorDirectory`, `TranslationService`, `SyncSink`, `Message`, `Channel`, `ChannelRegistry`, `Router` | — (no runtime entry) | [core-api/README.md](core-api/README.md) |
| `kernel` | Module system | Load modules, resolve deps, SPI | `ModuleLoader`, `ModuleGraph`, `ModuleDescriptor` | `ModuleLoader.load()` | [kernel/README.md](kernel/README.md) |
| `textformatter` | Formatting engine | Templates, placeholders | `DefaultTextFormatter`, `TemplateRenderer`, `TextFormatterActivator` | `TextFormatterActivator` | [textformatter/README.md](textformatter/README.md) |
| `iflow-vm` | VM 16-bit flat 64KB + SoftMMU 4KB, bytecode `.flow` binario, `HOST` instruction, fuel budget 100K, `VmPolicyEngine`, `VmInterpreter`, `SoftMMU`; base de la VM de 32 bits | `VmPolicyEngine`, `VmInterpreter`, `SoftMMU` | `IflowVmActivator` | [iflow-vm/README.md](iflow-vm/README.md) |
| `iflow-wasm` | Motor WebAssembly con Chicory (W3) | `WasmModuleLoader`, `WasmContract`, `WasmPolicyRouter`, `IflowBackend` | `IflowWasmActivator` | [iflow-wasm/README.md](iflow-wasm/README.md) |
| `iflow-firewall` | Motor SpEL legacy + grafo del editor | `DefaultRouter`, `RuleExpressionEvaluator`, `RuleConfigLoader`, `RuleGraph`, `FirewallConfig` | `IflowFirewallActivator` | — |
| `host` | Neutral pipeline | Dispatch, config, message catalog | `SuiteHost`, `MessageDispatcher`, `ConfigLoader`, `HostPaths` | `SuiteRuntime` builds `SuiteHost` | [host/README.md](host/README.md) |
| `chattranslator` | Translation facade | Builds the translator manager from provider services and refreshes it on module lifecycle events | `ChatTranslator`, `ChatTranslatorActivator`, `BackendConfig`, `TranslatorManager` | `ChatTranslatorActivator` | [chattranslator/README.md](chattranslator/README.md) |
| `coretranslator` | Legacy PAPI expansion (`%cot_*`); Bukkit-only, deprecated, maintained | Placeholder grammar over `TranslationService`/`MessageDispatch`; PAPI adapter isolated behind a Bukkit hook | `CoreTranslatorModule`, `CoreTranslatorActivator`, `CoreTranslatorPlaceholders`, `CoreTranslatorPapiExpansion` | `CoreTranslatorActivator` | [coretranslator/README.md](coretranslator/README.md) |
| `gtranslate` | Google Translate | Translate via Google API | `GTranslate`, `GTranslateProvider` | `GTranslateActivator` | [gtranslate/README.md](gtranslate/README.md) |
| `ltranslate` | LibreTranslate | Translate via LibreTranslate API | `LTranslate`, `LTranslateProvider` | `LTranslateActivator` | [ltranslate/README.md](ltranslate/README.md) |
| `transport` | Transport layer (dependency package) | HTTP transport, codecs | `HttpTransport`, `Transport`, `MessageCodec` | `modulePackage` (`DEPENDENCY`) | [transport/README.md](transport/README.md) |
| `logger` | Logging dependency package | Structured logging shared by modules | `Logger`, `LoggerUtils` | `LoggerModule` (`DEPENDENCY`) | [logger/README.md](logger/README.md) |
| `observability` | Metrics & health | Prometheus, health checks, endpoints | `Observability`, `MetricsCollector`, `HealthCheckRegistry`, `MetricsEndpoint` | `ObservabilityModule` | [observability/README.md](observability/README.md) |
| `presets` | Preset catalog | YAML preset import/export, built-in definitions and canonical file persistence | `PresetManager`, `Preset`, `FilePresetTarget`, `PresetsActivator`, `PresetsModule` | `PresetsActivator` | [presets/README.md](presets/README.md) |
| `manager-spi` | Manager SPI | Package metadata, manifest/version parsing, repository config | `PackageEntry`, `ManifestParser`, `Version`, `PlatformCompatibility` | — | [manager-spi/README.md](manager-spi/README.md) |
| `manager-core` | Package installer | Local ZIP installation, package database, durable journal, versioned layout | `PackageInstaller`, `LocalPackageDatabase`, `PackageJournal`, `PackageLayout` | — | [manager-core/README.md](manager-core/README.md) |
| `manager-apt` | Repository/APT layer | Repositories, dependency resolution, download cache, batch commit | `ManagerFacade`, `RepositoryManager`, `DependencyResolver`, `AptInstaller`, `PackageCache` | — | [manager-apt/README.md](manager-apt/README.md) |
| `extension-api` | Extension system | Extension lifecycle & context | `Extension`, `ExtensionContext`, `ExtensionManager` | — | [extension-api/README.md](extension-api/README.md) |
| `spigot-host` | Bukkit/Paper plugin | Thin platform adapter | `TextFormatterSuitePlugin`, `SpigotPlatformPorts`, `SpigotActorDirectory`, `SpigotChatDelivery` | `TextFormatterSuitePlugin.onEnable()` | [spigot-host/README.md](spigot-host/README.md) |
| `fabric-host` | Fabric mod | Thin platform adapter | `TextFormatterSuiteMod`, `FabricPlatformPorts`, `FabricActorDirectory`, `FabricChatDelivery` | `TextFormatterSuiteMod.onInitialize()` | [fabric-host/README.md](fabric-host/README.md) |
| `sync-bukkit` | Bukkit/Paper event capture | Platform package | `SyncBukkitActivator`, `BukkitChatListener`, `SyncBukkitConfig` | `SyncBukkitActivator` | [sync-bukkit/README.md](sync-bukkit/README.md) |
| `sync-fabric` | Fabric event capture | Platform package | `SyncFabricActivator`, `FabricEventListener`, `FabricDeathTracker` | `SyncFabricActivator` | [sync-fabric/README.md](sync-fabric/README.md) |

---

## 4. Where Should I Start?

### Understand Startup & Bootstrap
```
→ src/suite-runtime/README.md
  → SuiteRuntime.java (suite-runtime)
  → TextFormatterSuitePlugin.java (spigot-host)
  → TextFormatterSuiteMod.java (fabric-host)
  → SuiteHost.java (host)
```

### Understand Message Formatting Pipeline
```
→ src/textformatter/README.md#data-flow
  → DefaultTextFormatter.java
  → TemplateRenderer.java
  → ChannelRegistry.java
```

### Understand Message Routing (iflow-vm)
```
→ src/iflow-vm/README.md#data-flow
  → VmPolicyEngine.java
  → VmInterpreter.java / SoftMMU.java
  → VmConfigLoader.java (policy.flow)
```

### Understand Wasm Policy Engine (iflow-wasm)
```
→ src/iflow-wasm/README.md
  → WasmModuleLoader.java (Chicory parse/instantiate)
  → W1: ADR de imports/exports y capabilities
  → W3: WasmPolicyEngine + Router/MessageDispatcher
```

### Understand Translation
```
→ src/gtranslate/README.md / src/ltranslate/README.md
  → GTranslateProvider.java / LTranslateProvider.java (implement TranslationService)
  → TranslationService SPI in core-api
```

### Understand Configuration
```
→ src/host/README.md#configuration
  → ConfigLoader.java, HostConfig.java
  → HostPaths.java (canonical layout)
```

### Understand Sync / Cross-server Communication
```
→ src/transport/README.md
  → Transport.java, HttpTransport.java, MessageCodec.java
→ src/sync-http/README.md, sync-socket/README.md, sync-discord/README.md, etc.
  → SyncSink implementations
```

### Add a New Formatter / Template Feature
```
→ src/textformatter/README.md#extension-points
  → Implement TextFormatter interface
  → Register via TextFormatterModule
  → Use Template/TemplateRenderer for MiniMessage
```

### Add a New Sync Transport
```
→ src/transport/README.md
  → Implement Transport interface
  → Implement SyncSink (core-api SPI)
  → Create module (e.g., SyncXxxModule) registering SyncSink
  → Add to spigot-host/fabric-host dependencies
```

### Add a New Platform Adapter
```
→ src/spigot-host/README.md or src/fabric-host/README.md
  → Implement PlatformPorts and the adapter-owned services
    (ActorDirectory, ChatDelivery, PlaceholderResolver, ...)
  → Register them in SuiteRuntime before start()
  → Start SuiteRuntime and register only the root command adapter
  → Keep command trees and event capture out of the host
```

---

## 5. Important Entry Points

### Platform Entry Points
| Platform | Entry Point | Responsibility |
|----------|-------------|----------------|
| **Bukkit/Paper** | `TextFormatterSuitePlugin.onEnable()` | Create `PlatformPorts`, register adapter services, start `SuiteRuntime` |
| **Fabric** | `TextFormatterSuiteMod.onInitialize()` | Create `PlatformPorts`, register adapter services, start `SuiteRuntime` |

### Bootstrap & Initialization
| Component | Location | Responsibility |
|-----------|----------|----------------|
| `SuiteRuntime` | `suite-runtime` | Composition root: installs packages, builds `SuiteHost`, loads the configured `CommandRoot`, publishes commands and `MessageDispatch` |
| `DefaultConfigInstaller` | `suite-runtime` | First-boot defaults: host files in `config/suite/`, module files in `config/<module>/`, legacy migration |
| `SuiteHost` | `host` | Neutral pipeline: holds services and dispatches messages |
| `ModuleLoader` | `kernel` | Discovers and loads `Module` implementations via SPI |
| `ModuleGraph` | `kernel` | Resolves module dependency graph |

### Service Registration
| Service | Registration Point |
|---------|-------------------|
| `ActorDirectory` | Platform adapter registers it in `SuiteRuntime` before `start()` (`SpigotActorDirectory`, `FabricActorDirectory`) |
| `TranslationService` | `chattranslator` package registers the facade/service; `gtranslate`/`ltranslate` packages register `TranslatorProvider` |
| `ModuleActivatedEvent` / `ModuleDeactivatedEvent` | `ModuleHost` publishes them; modules subscribe through their scoped `EventBus` |
| `SyncSink` | Each sync package registers its `SyncSink` implementation |
| `Router` | The active iFlow engine package registers it |
| `MessageDispatcher` | `SuiteRuntime` builds it from the active pipeline |
| `ConfigValidator` | Removed; each package validates its own configuration |

### Command Registration
| Platform | Registration |
|----------|--------------|
| Root | `config/suite/commands.yml` (`root`/`aliases`) → `CommandRootConfig` → `RuntimeCommandRegistry` |
| Neutral | `api.command` tree owned by `RuntimeCommandRegistry`; modules register root-relative paths through `ModuleContext.commands()` |
| Bukkit | `SuiteRuntime.registerCommands()` → `SpigotPlatformPorts` → `CommandMap` (root adapter only) |
| Fabric | `SuiteRuntime.registerCommands()` → `FabricPlatformPorts` → Brigadier (root adapter only) |
| Reload | `SuiteRuntime.reloadCommandRoot()` / `/suite reload` → re-register root or `RESTART_REQUIRED` |

### Event/Listener Registration
| Platform | Registration |
|----------|--------------|
| Bukkit | Event capture belongs to `sync-bukkit` (F4c); the host has no chat listeners |
| Fabric | Event capture belongs to `sync-fabric` (F4c); the host has no chat listeners |

---

## 6. Architectural Boundaries

| Boundary | Allowed Direction | Forbidden Direction | Notes |
|----------|-------------------|---------------------|-------|
| `core-api` → * | `core-api` is depended upon by all | Nothing depends on `core-api` internally | Pure contracts and shared value types; no feature dependencies |
| `kernel` → `core-api` | `kernel` uses `core-api` SPI | `core-api` must not depend on `kernel` | Module loading mechanism |
| `host` → core contracts | `host` consumes neutral contracts only | Core modules must not depend on `host` | Feature-free pipeline |
| Platform adapters → `suite-runtime` | `spigot-host`/`fabric-host` depend on `core-api`/`core-render`/`suite-runtime` | Adapters must not import features, `host`, `kernel` or `manager` | Bootstrap-only adapters |
| Sync modules → `core-api` + `transport` | Sync modules implement `SyncSink` SPI | `core-api` must not depend on sync modules | Transport abstraction |
| `manager-apt`/`manager-core` → `manager-spi` | Implementations depend on the SPI | SPI must not depend on implementations | Standard API/impl separation |

> ℹ️ **Current boundary**: `host` no longer depends on `kernel` or feature modules. `suite-runtime` owns package discovery and `SuiteHost` assembly.

---

## 7. Major Flows

### Startup Flow (Shared)
```
Platform Entry Point (Plugin.onEnable / Mod.onInitialize)
         ↓
PlatformPorts + adapter services registered
         ↓
SuiteRuntime.start()
         ↓
PackageRuntime installs/activates packages and hot-upgrades active ones
         ↓
ServiceRegistry: ChannelRegistry, TranslationService, Router, renderers, ChatDelivery
         ↓
SuiteRuntime builds SuiteHost and publishes MessageDispatch
         ↓
runtime.registerCommands() → platform root command
         ↓
Ready
```

### Message Processing Flow
```
Minecraft Chat Event (PlayerChatEvent / Fabric equivalent)
         ↓
Platform Adapter (SpigotChatDelivery / FabricChatDelivery)
         ↓
MessageDispatcher.dispatch() / PipelineEngine.dispatch()
         ↓
route stage [iflow] → VM executes `.flow` bytecode, applies rules/transforms/rate limits via HOST services
         ↓
format stage [textformatter] → TemplateRenderer + placeholders + <tr> translation via TranslationService (chattranslator)
         ↓
sync stage [sync-bus] → SyncSink.send() [for each registered sink] → HTTP, Discord, TCP, etc.
         ↓
Platform-specific delivery → ChatDelivery.deliver()
```

### Configuration Flow
```
Config File (YAML)
         ↓
ConfigLoader.load() → HostConfig
         ↓
Packages load their own canonical config (config/<package>/...)
         ↓
SuiteRuntime builds SuiteHost with the active services
         ↓
ConfigSchemaGenerator (host) → generates web-editor schema
```

### Module Loading Flow
```
ModuleLoader.scanClasspath() → finds META-INF/services/me.majhrs16.suite.api.Module
         ↓
Instantiates each Module
         ↓
ModuleGraph.build() → resolves Requirement/Capability
         ↓
Topological sort → initialization order
         ↓
Module.initialize() called in order
```

---

## 8. Code Ownership Map (Conceptual)

| Area | Primary Module | Key Files |
|------|----------------|-----------|
| **Configuration** | `host` | `ConfigLoader`, `HostConfig`, `HostPaths`, `ConfigSchemaGenerator` |
| **Module System** | `kernel` | `ModuleLoader`, `ModuleGraph`, `ModuleDescriptor` |
| **Formatting** | `textformatter` | `DefaultTextFormatter`, `TemplateRenderer` |
| **Shared channel/routing contracts** | `core-api` | `api/channel/Channel`, `api/channel/ChannelRegistry`, `api/iflow/Router` |
| **Routing/Rules (VM)** | `iflow-vm` | `VmPolicyEngine`, `VmInterpreter`, `SoftMMU`, `VmConfigLoader` |
| **Translation** | `chattranslator` + `gtranslate`/`ltranslate` | `ChatTranslator`/`TranslationService`; `GTranslateProvider`/`LTranslateProvider` implement `TranslatorProvider` |
| **Sync/Transport** | `transport` + sync modules | `Transport`, `HttpTransport`, `SyncSink` implementations |
| **Observability** | `observability` | `MetricsCollector`, `HealthCheckRegistry`, `MetricsEndpoint`, `DebugEndpoint` |
| **Presets** | `presets` | `PresetManager`, `Preset` |
| **Platform: Bukkit** | `spigot-host` | `TextFormatterSuitePlugin`, `SpigotPlatformPorts`, `SpigotActorDirectory`, `SpigotChatDelivery` |
| **Platform: Fabric** | `fabric-host` | `TextFormatterSuiteMod`, `FabricPlatformPorts`, `FabricActorDirectory`, `FabricChatDelivery` |
| **Module Manager** | `manager-apt` | `ManagerFacade`, `RepositoryManager`, `DependencyResolver`, `AptInstaller` |
| **Extensions** | `extension-api` | `Extension`, `ExtensionManager`, `ExtensionContext` |

---

## 9. Discrepancies & Known Issues

> ℹ️ **Current boundary**: `host` is feature-free and does not depend on `kernel`; package discovery and lifecycle live in `suite-runtime`/`kernel`.

> ℹ️ **Velocity split**: `sync-velocity-core` is the neutral sink; `sync-velocity` is the Velocity plugin. Only the plugin imports `com.velocitypowered`.

> ⚠️ **Duplicate MessageCodec**: Both `transport` and `sync-http`/`sync-socket`/`sync-discord` have `MessageCodec` classes. The `transport` one is the base abstraction; sync modules have their own (possibly for historical reasons).

> ℹ️ **Dependency coordinates**: `fabric-host` and `spigot-host` both consume the suite modules as Gradle project dependencies. Maven coordinates only appear in external dependency examples, not in the host build.

---

## 10. Quick Reference: Key Files to Read First

| Goal | First File | Then |
|------|------------|------|
| Understand overall architecture | `src/README.md` (this file) | Module READMEs |
| Understand module loading | `kernel/src/main/java/me/majhrs16/suite/kernel/ModuleLoader.java` | `ModuleGraph.java` |
| Understand formatting | `textformatter/src/main/java/me/majhrs16/suite/textformatter/DefaultTextFormatter.java` | `TemplateRenderer.java`, `core-api/src/main/java/me/majhrs16/suite/api/channel/ChannelRegistry.java` |
| Understand routing (VM) | `iflow-vm/src/main/java/me/majhrs16/suite/iflow/vm/engine/VmPolicyEngine.java` | `VmInterpreter.java`, `SoftMMU.java` |
| Understand bootstrap | `suite-runtime/src/main/java/me/majhrs16/suite/runtime/SuiteRuntime.java` | `SuiteHost.java`, `MessageDispatcher.java` |
| Understand Spigot integration | `spigot-host/src/main/java/me/majhrs16/suite/spigothost/TextFormatterSuitePlugin.java` | `SpigotActorDirectory.java`, `SpigotChatDelivery.java` |
| Understand Fabric integration | `fabric-host/src/main/java/me/majhrs16/suite/fabrichost/TextFormatterSuiteMod.java` | `FabricActorDirectory.java`, `FabricChatDelivery.java` |
| Understand translation | `gtranslate/src/main/java/me/majhrs16/suite/gtranslate/GTranslateProvider.java` | `core-api/src/main/java/me/majhrs16/suite/api/spi/TranslationService.java` |
| Understand sync | `transport/src/main/java/me/majhrs16/suite/transport/Transport.java` | Any `sync-*/SyncSink.java` |
| Understand config | `host/src/main/java/me/majhrs16/suite/host/config/ConfigLoader.java` | `HostConfig.java`, `HostPaths.java` |

---

## 11. Validation Checklist

- [x] All modules in `settings.gradle` covered
- [x] Dependencies match `build.gradle` files
- [x] Entry points verified in source code
- [x] No invented modules or responsibilities
- [x] Platform-specific vs shared code clearly separated
- [x] Links use relative paths to existing files
- [x] Discrepancies documented explicitly

---

*Generated from actual repository state. Last updated: 2026-10-31 (F4b/F4c/F4d closed: feature-free hosts, `PlatformHandle`, downloadable `sync-bukkit`/`sync-fabric` packages, shared neutral event helpers in `sync-common`, platform-filtered package activation, and the neutral `api.command` tree with no host command tree).*
