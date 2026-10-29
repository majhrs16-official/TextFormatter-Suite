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
│   ├── iflow-vm          → VM 16-bit flat 64KB + SoftMMU 4KB, bytecode `.flow`, `HOST` instruction, fuel budget (base de la VM de 32 bits)
│   ├── iflow-wasm        → motor WebAssembly (`.wasm`) con Chicory; contrato W1/W2 (validación, instancia, invocación), sin integración al pipeline
│   ├── iflow-firewall    → motor de reglas SpEL legacy (alternativa mutuamente excluyente a la VM) + grafo del editor
│   ├── host              → Shared bootstrap, dispatch, configuration
│   ├── messages          → Message catalog (serialization formats)
│   ├── chattranslator    → Translation facade and provider lifecycle
│   ├── transport         → Transport abstraction (HTTP, codecs; dependency package)
│   ├── gtranslate        → Google Translate provider
│   ├── ltranslate        → LibreTranslate provider
│   ├── observability     → Metrics, health checks, debug endpoints
│   ├── presets           → Preset catalog and YAML import/export
│   ├── manager-spi       → Module manager SPI (coordinates, descriptors, lifecycle)
│   ├── manager-core      → Package installer + local package database
│   ├── manager-apt       → Repositories, dependency resolver, APT installer
│   ├── extension-api     → Extension system API
│   ├── inworld           → In-world integration
│   ├── performance       → Profiling & optimization
│   └── common-legacy     → Legacy shared code
│
├── Sync Transports (platform-agnostic)
│   ├── sync-http         → HTTP webhook sink
│   ├── sync-tcpudp       → TCP/UDP sinks
│   ├── sync-discord      → Discord sink (neutral)
│   ├── sync-discord-jda  → Discord JDA adapter
│   ├── sync-discord-jdk  → Discord JDK adapter
│   ├── sync-telegram     → Telegram sink
│   ├── sync-websocket    → WebSocket sink
│   ├── sync-velocity-core → Neutral Velocity sink
│   ├── sync-velocity     → Velocity plugin adapter
│   └── sync-bus          → Unified fan-out/lifecycle bus
│
└── Platform Adapters
    ├── spigot-host       → Bukkit/Paper plugin (Java 21)
    └── fabric-host       → Fabric mod (Java 21)
```

**Key architectural principles observed in code:**
- `core-api` has **zero dependencies** on other modules — it defines contracts only
- `kernel` depends only on `core-api` — loads modules via SPI
- `host` is the **integration hub** — wires core-api, textformatter, the installed iFlow engine (`iflow-vm` or `iflow-firewall`; mutually exclusive), translations, transports
- Platform adapters (`spigot-host`, `fabric-host`) depend on `host` + all sync modules
- Sync modules depend on `core-api`; technology adapters add `transport`, JDA or a platform SDK
- **No circular dependencies** between core modules

---

## 2. Directory Map

| Directory | Responsibility | Depends On | Used By |
|-----------|----------------|------------|---------|
| `core-api` | Core interfaces, SPI, domain types and cross-package contracts (Module, Actor, Channel, ChannelRegistry, Router, TranslationService, SyncSink, etc.) | — | **All modules** |
| `kernel` | Module discovery, dependency graph, loading, SPI resolution | `core-api` | `host`, `spigot-host`, `fabric-host`, test fixtures |
| `textformatter` | Template rendering (MiniMessage), placeholders | `core-api` | `host`, `presets`, `spigot-host`, `fabric-host` |
| `iflow-vm` | VM 16-bit flat 64KB + SoftMMU 4KB, bytecode `.flow`, `HOST` instruction, fuel budget, `VmPolicyEngine`, `VmInterpreter`, `SoftMMU`; base de la VM de 32 bits | `core-api`, `logger` (dependency package) | `host`, `spigot-host`, `fabric-host` |
| `iflow-wasm` | Motor WebAssembly (`.wasm`) con Chicory; W2: contrato, validación, instancia e invocación; sin ABI de host ni integración | `core-api`, `com.dylibso.chicory:runtime` | — (futuro `host`) |
| `iflow-firewall` | Motor de reglas SpEL legacy restaurado desde `933bdd3^` (`DefaultRouter`, `RuleExpressionEvaluator`, `Rule`, `TransformOp`) + validador del grafo del editor; alternativa **mutuamente excluyente** de `iflow-vm` (mismo paquete base, solo uno instalado) | `core-api`, `spring-expression`, `jackson`, `snakeyaml` | `host` (selección `vm`/`firewall`), web editor |
| `host` | Bootstrap, message dispatch, config loading, platform-agnostic wiring; selecciona el motor iFlow instalado con `iflow.engine.kind` | `core-api`, `kernel`, `textformatter`, `iflow-vm`/`iflow-firewall` (alternativos), `gtranslate`, `ltranslate`, `transport` | `spigot-host`, `fabric-host`, `observability`, `presets` |
| `messages` | Message catalog (serialized message formats) | — | `host`, `spigot-host`, `fabric-host` |
| `transport` | Transport abstraction (Transport, HttpTransport, MessageCodec) | `core-api` | `sync-http`, `sync-tcpudp`, `sync-discord`, `gtranslate`, `ltranslate` |
| `gtranslate` | Google Translate API provider | `core-api`, `transport` | `host`, `spigot-host`, `fabric-host` |
| `ltranslate` | LibreTranslate API provider | `core-api`, `transport` | `host`, `spigot-host`, `fabric-host` |
| `observability` | Prometheus metrics, health checks, debug/metrics HTTP endpoints | `core-api`, `host`, `textformatter` | `spigot-host`, `fabric-host` |
| `presets` | Preset catalog and YAML import/export (no execution engine) | `core-api`, `textformatter`, `host` | — (sin consumidor cableado) |
| `manager-spi` | Module manager SPI (coordinates, descriptors, lifecycle, manifest/version parsing) | `core-api` | `manager-core`, `manager-apt`, `spigot-host`, `fabric-host` |
| `manager-core` | Package installer, local package database, transaction log | `core-api`, `manager-spi`, `kernel` | `manager-apt`, `spigot-host`, `fabric-host` |
| `manager-apt` | Repository manager, dependency resolver, APT installer/facade | `core-api`, `kernel`, `manager-spi`, `manager-core` | `spigot-host`, `fabric-host` |
| `extension-api` | Extension system API (Extension, ExtensionContext, ExtensionManager) | `core-api` | `example-extension`, `spigot-host`, `fabric-host` |
| `inworld` | In-world integration module | `core-api` | `spigot-host`, `fabric-host` |
| `performance` | Hotspot detection, profiling, cache/memory optimization | `core-api` | `spigot-host`, `fabric-host` |
| `sync-http` | HTTP webhook sync sink | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-tcpudp` | TCP/UDP sync sinks | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-discord` | Neutral Discord sink; selects a `DiscordConnectionFactory` by config | `core-api`, `sync-common` | `spigot-host`, `fabric-host` |
| `sync-discord-jda` | JDA `discord-transport` adapter (provides the capability) | `core-api`, JDA | `spigot-host` |
| `sync-discord-jdk` | JDK WebSocket/REST `discord-transport` adapter | `core-api`, `transport` | optional |
| `sync-telegram` | Telegram sync sink | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-websocket` | WebSocket sync sink | `core-api` | `spigot-host`, `fabric-host` |
| `sync-velocity-core` | Neutral Velocity sink; queue, mapping, retry and payload codec | `core-api`, `sync-common`, `transport` | `sync-velocity`, optional suite hosts |
| `sync-velocity` | Velocity plugin adapter; channel registration and `sendPluginMessage` | `sync-velocity-core`, Velocity API | Velocity proxy |
| `sync-bus` | Unified fan-out, deduplication, retries and sink lifecycle | `core-api` | `spigot-host`, `fabric-host` |
| `sync-common` | Shared YAML reader and sync config path resolution (`DEPENDENCY`) | JDK, `snakeyaml` | `sync-*` |
| `spigot-host` | Bukkit/Paper plugin entry point, platform adapters | `core-api`, `kernel`, `host`, `iflow-vm`, `textformatter`, all sync, `gtranslate`, `ltranslate`, `messages`, `observability`, `manager-spi`, `manager-core`, `manager-apt`, `inworld`, `extension-api`, `tester` | — (leaf) |
| `fabric-host` | Fabric mod entry point, platform adapters | `core-api`, `kernel`, `host`, `iflow-vm`, `textformatter`, sync modules, `gtranslate`, `ltranslate`, `messages`, `observability`, `manager-spi`, `manager-core`, `manager-apt`, `presets`, `inworld`, `extension-api`, `loadtest`, `performance` | — (leaf) |
| `example-extension` | Example extension implementation | `extension-api` | — (leaf) |
| `loadtest` | Load testing utilities | — | `fabric-host` |
| `tester` | Testing utilities (Spigot-dependent) | `core-api` | `spigot-host` |

---

## 3. Module Index

| Module | Purpose | Main Responsibility | Key Classes | Entry Points | README |
|--------|---------|---------------------|-------------|--------------|--------|
| `core-api` | Contracts & SPI | Define all platform-agnostic interfaces and shared contracts | `Module`, `ModuleActivator`, `ServiceRegistry`, `ModuleActivatedEvent`, `ActorDirectory`, `TranslationService`, `SyncSink`, `Message`, `Channel`, `ChannelRegistry`, `Router` | — (no runtime entry) | [core-api/README.md](core-api/README.md) |
| `kernel` | Module system | Load modules, resolve deps, SPI | `ModuleLoader`, `ModuleGraph`, `ModuleDescriptor` | `ModuleLoader.load()` | [kernel/README.md](kernel/README.md) |
| `textformatter` | Formatting engine | Templates, placeholders | `DefaultTextFormatter`, `TemplateRenderer`, `TextFormatterActivator` | `TextFormatterActivator` | [textformatter/README.md](textformatter/README.md) |
| `iflow-vm` | VM 16-bit flat 64KB + SoftMMU 4KB, bytecode `.flow` binario, `HOST` instruction, fuel budget 100K, `VmPolicyEngine`, `VmInterpreter`, `SoftMMU`; base de la VM de 32 bits | `VmPolicyEngine`, `VmInterpreter`, `SoftMMU` | `IflowVmActivator` | [iflow-vm/README.md](iflow-vm/README.md) |
| `iflow-wasm` | Motor WebAssembly con Chicory (W2) | `WasmModuleLoader`, `WasmContract`, `ValidationResult` | `IflowWasmActivator` | [iflow-wasm/README.md](iflow-wasm/README.md) |
| `iflow-firewall` | Motor SpEL legacy + grafo del editor | `DefaultRouter`, `RuleExpressionEvaluator`, `RuleConfigLoader`, `RuleGraph`, `FirewallConfig` | `IflowFirewallActivator` | — |
| `host` | Shared host logic | Bootstrap, dispatch, config | `SuiteBootstrap`, `SuiteHost`, `MessageDispatcher`, `ConfigLoader` | `SuiteBootstrap.initialize()` | [host/README.md](host/README.md) |
| `chattranslator` | Translation facade | Builds the translator manager from provider services and refreshes it on module lifecycle events | `ChatTranslator`, `ChatTranslatorActivator`, `BackendConfig`, `TranslatorManager` | `ChatTranslatorActivator` | [chattranslator/README.md](chattranslator/README.md) |
| `gtranslate` | Google Translate | Translate via Google API | `GTranslate`, `GTranslateProvider` | `GTranslateActivator` | [gtranslate/README.md](gtranslate/README.md) |
| `ltranslate` | LibreTranslate | Translate via LibreTranslate API | `LTranslate`, `LTranslateProvider` | `LTranslateActivator` | [ltranslate/README.md](ltranslate/README.md) |
| `transport` | Transport layer (dependency package) | HTTP transport, codecs | `HttpTransport`, `Transport`, `MessageCodec` | `modulePackage` (`DEPENDENCY`) | [transport/README.md](transport/README.md) |
| `logger` | Logging dependency package | Structured logging shared by modules | `Logger`, `LoggerUtils` | `LoggerModule` (`DEPENDENCY`) | [logger/README.md](logger/README.md) |
| `observability` | Metrics & health | Prometheus, health checks, endpoints | `Observability`, `MetricsCollector`, `HealthCheckRegistry`, `MetricsEndpoint` | `ObservabilityModule` | [observability/README.md](observability/README.md) |
| `presets` | Preset catalog | YAML preset import/export and built-in definitions | `PresetManager`, `Preset`, `PresetsModule` | `PresetsModule` | [presets/README.md](presets/README.md) |
| `manager-spi` | Manager SPI | Coordinates, descriptors, lifecycle, manifest/version parsing | `ModuleCoordinate`, `ModuleDescriptor`, `ModuleLifecycle`, `Version` | — | [manager-spi/README.md](manager-spi/README.md) |
| `manager-core` | Package installer | Local ZIP installation, package database, transactions | `PackageInstaller`, `LocalPackageDatabase`, `TransactionLog` | — | [manager-core/README.md](manager-core/README.md) |
| `manager-apt` | Repository/APT layer | Repositories, dependency resolution, installer facade | `ManagerFacade`, `RepositoryManager`, `DependencyResolver`, `AptInstaller` | — | [manager-apt/README.md](manager-apt/README.md) |
| `extension-api` | Extension system | Extension lifecycle & context | `Extension`, `ExtensionContext`, `ExtensionManager` | — | [extension-api/README.md](extension-api/README.md) |
| `spigot-host` | Bukkit/Paper plugin | Platform adapter for Bukkit | `TextFormatterSuitePlugin`, `SpigotActorDirectory`, `DynamicCommandRegistrar` | `TextFormatterSuitePlugin.onEnable()` | [spigot-host/README.md](spigot-host/README.md) |
| `fabric-host` | Fabric mod | Platform adapter for Fabric | `TextFormatterSuiteMod`, `FabricActorDirectory`, `FabricChatDelivery` | `TextFormatterSuiteMod.onInitialize()` | [fabric-host/README.md](fabric-host/README.md) |

---

## 4. Where Should I Start?

### Understand Startup & Bootstrap
```
→ src/host/README.md#entry-points
  → SuiteBootstrap.java (host)
  → SuiteHost.java (host)
  → TextFormatterSuitePlugin.java (spigot-host)
  → TextFormatterSuiteMod.java (fabric-host)
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
  → ConfigLoader.java, HostConfig.java, CommandsConfig.java
  → SpigotConfigValidator.java / ConfigValidator.java (platform-specific)
```

### Understand Sync / Cross-server Communication
```
→ src/transport/README.md
  → Transport.java, HttpTransport.java, MessageCodec.java
→ src/sync-http/README.md, sync-tcpudp/README.md, sync-discord/README.md, etc.
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
  → Implement ActorDirectory, ChatDelivery, PlaceholderResolver
  → Implement Module (extend ModuleBase or similar)
  → Register commands, listeners, config validator
  → Wire in platform-specific bootstrap
```

---

## 5. Important Entry Points

### Platform Entry Points
| Platform | Entry Point | Responsibility |
|----------|-------------|----------------|
| **Bukkit/Paper** | `TextFormatterSuitePlugin.onEnable()` | Plugin bootstrap, register commands/listeners, init `SuiteBootstrap` |
| **Fabric** | `TextFormatterSuiteMod.onInitialize()` | Mod bootstrap, register event handlers, init `SuiteBootstrap` |

### Bootstrap & Initialization
| Component | Location | Responsibility |
|-----------|----------|----------------|
| `SuiteBootstrap` | `host` | Core initialization: load modules, wire services, create `SuiteHost` |
| `SuiteHost` | `host` | Runtime host: holds services, dispatches messages, manages lifecycle |
| `ModuleLoader` | `kernel` | Discovers and loads `Module` implementations via SPI |
| `ModuleGraph` | `kernel` | Resolves module dependency graph |

### Service Registration
| Service | Registration Point |
|---------|-------------------|
| `ActorDirectory` | `SuiteBootstrap` → platform adapter provides (`SpigotActorDirectory`, `FabricActorDirectory`) |
| `TranslationService` | `chattranslator` package registers the facade/service; `gtranslate`/`ltranslate` packages register `TranslatorProvider` |
| `ModuleActivatedEvent` / `ModuleDeactivatedEvent` | `ModuleHost` publishes them; modules subscribe through their scoped `EventBus` |
| `SyncSink` | `SuiteBootstrap` → each sync module registers its `SyncSink` implementation |
| `Router` | `SuiteBootstrap` → `VmPolicyEngine` |
| `MessageDispatcher` | `SuiteBootstrap` → `MessageDispatcher` |
| `ConfigValidator` | Platform-specific: `SpigotConfigValidator` / `ConfigValidator` |

### Command Registration
| Platform | Registration |
|----------|--------------|
| Bukkit | `DynamicCommandRegistrar` registers `DynamicCommand` instances |
| Fabric | Command registration via Fabric API in `TextFormatterSuiteMod` |

### Event/Listener Registration
| Platform | Registration |
|----------|--------------|
| Bukkit | `TextFormatterSuitePlugin` registers listeners for chat, join, quit, etc. |
| Fabric | `TextFormatterSuiteMod` registers event handlers via Fabric API |

---

## 6. Architectural Boundaries

| Boundary | Allowed Direction | Forbidden Direction | Notes |
|----------|-------------------|---------------------|-------|
| `core-api` → * | `core-api` is depended upon by all | Nothing depends on `core-api` internally | Pure contracts and shared value types; no feature dependencies |
| `kernel` → `core-api` | `kernel` uses `core-api` SPI | `core-api` must not depend on `kernel` | Module loading mechanism |
| `host` → core modules | `host` integrates `textformatter`, `iflow-vm`, translations, transports | Core modules must not depend on `host` | `host` is the integration hub |
| Platform adapters → `host` | `spigot-host`/`fabric-host` depend on `host` | `host` must not depend on platform adapters | Platform-agnostic core |
| Sync modules → `core-api` + `transport` | Sync modules implement `SyncSink` SPI | `core-api` must not depend on sync modules | Transport abstraction |
| `manager-apt`/`manager-core` → `manager-spi` | Implementations depend on the SPI | SPI must not depend on implementations | Standard API/impl separation |

> ⚠️ **Observed architectural issue**: `host` depends on `kernel` for `ModuleLoader` during bootstrap. This is intentional — `host` drives module loading. However, `kernel` tests use `host` classes (`SuiteBootstrapTest` doesn't exist but `kernel` tests exist in isolation).

---

## 7. Major Flows

### Startup Flow (Shared)
```
Platform Entry Point (Plugin.onEnable / Mod.onInitialize)
         ↓
SuiteBootstrap.initialize()
         ↓
ModuleLoader.load() → discovers Module implementations
         ↓
ModuleGraph.resolve() → orders by dependencies
         ↓
Modules initialized → register services (ActorDirectory, TranslationService, SyncSink, Router, etc.)
         ↓
SuiteHost created with wired services
         ↓
Platform-specific config validation & command/listener registration
         ↓
Ready
```

### Message Processing Flow
```
Minecraft Chat Event (PlayerChatEvent / Fabric equivalent)
         ↓
Platform Adapter (SpigotChatDelivery / FabricChatDelivery)
         ↓
MessageDispatcher.dispatch()
         ↓
Router.route() [iflow-vm] → VM executes `.flow` bytecode, applies rules/transforms/rate limits via HOST services
         ↓
TextFormatter.format() → MiniMessage + placeholder substitution
         ↓
TranslationService.translate() [if needed] → gtranslate/ltranslate
         ↓
SyncSink.send() [for each registered sink] → HTTP, Discord, TCP, etc.
         ↓
Platform-specific delivery → ChatDelivery.deliver()
```

### Configuration Flow
```
Config File (YAML)
         ↓
ConfigLoader.load() → HostConfig / CommandsConfig / TranslatorsConfig
         ↓
ConfigValidator.validate() [platform-specific]
         ↓
SuiteBootstrap applies config → services configured
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
| **Configuration** | `host` | `ConfigLoader`, `HostConfig`, `ConfigValidator`, `ConfigSchemaGenerator` |
| **Module System** | `kernel` | `ModuleLoader`, `ModuleGraph`, `ModuleDescriptor` |
| **Formatting** | `textformatter` | `DefaultTextFormatter`, `TemplateRenderer` |
| **Shared channel/routing contracts** | `core-api` | `api/channel/Channel`, `api/channel/ChannelRegistry`, `api/iflow/Router` |
| **Routing/Rules (VM)** | `iflow-vm` | `VmPolicyEngine`, `VmInterpreter`, `SoftMMU`, `VmConfigLoader` |
| **Translation** | `chattranslator` + `gtranslate`/`ltranslate` | `ChatTranslator`/`TranslationService`; `GTranslateProvider`/`LTranslateProvider` implement `TranslatorProvider` |
| **Sync/Transport** | `transport` + sync modules | `Transport`, `HttpTransport`, `SyncSink` implementations |
| **Observability** | `observability` | `MetricsCollector`, `HealthCheckRegistry`, `MetricsEndpoint`, `DebugEndpoint` |
| **Presets** | `presets` | `PresetManager`, `Preset` |
| **Platform: Bukkit** | `spigot-host` | `TextFormatterSuitePlugin`, `SpigotActorDirectory`, `SpigotChatDelivery`, `DynamicCommandRegistrar` |
| **Platform: Fabric** | `fabric-host` | `TextFormatterSuiteMod`, `FabricActorDirectory`, `FabricChatDelivery` |
| **Module Manager** | `manager-apt` | `ManagerFacade`, `RepositoryManager`, `DependencyResolver`, `AptInstaller` |
| **Extensions** | `extension-api` | `Extension`, `ExtensionManager`, `ExtensionContext` |

---

## 9. Discrepancies & Known Issues

> ⚠️ **Architectural discrepancy**: `host` depends on `kernel` (for `ModuleLoader`), but `kernel` is conceptually a lower-level module. This is intentional — `host` drives the module loading process. However, it means `kernel` cannot use `host` services (no circular dep).

> ⚠️ **Architectural discrepancy**: `spigot-host` depends on `tester` module (which has Spigot API dependency). This is a test-only dependency leaked into main compile scope (see `build.gradle` line 46-48 with exclusion). Ideally `tester` would be test-only.

> ℹ️ **Velocity split**: `sync-velocity-core` is the neutral sink; `sync-velocity` is the Velocity plugin. Only the plugin imports `com.velocitypowered`.

> ⚠️ **Duplicate MessageCodec**: Both `transport` and `sync-http`/`sync-tcpudp`/`sync-discord` have `MessageCodec` classes. The `transport` one is the base abstraction; sync modules have their own (possibly for historical reasons).

> ⚠️ **Legacy code**: `common-legacy` remains as a historical reference. `coretranslator` was removed from the build on 2026-09-28 and no longer exists in `src/`.

> ⚠️ **Incomplete migration**: `fabric-host` uses Maven coordinates for dependencies (e.g., `me.majhrs16:suite-core-api:2.1.0-SNAPSHOT`) while `spigot-host` uses Gradle project dependencies. This suggests `fabric-host` may be built/published separately.

---

## 10. Quick Reference: Key Files to Read First

| Goal | First File | Then |
|------|------------|------|
| Understand overall architecture | `src/README.md` (this file) | Module READMEs |
| Understand module loading | `kernel/src/main/java/me/majhrs16/suite/kernel/ModuleLoader.java` | `ModuleGraph.java` |
| Understand formatting | `textformatter/src/main/java/me/majhrs16/suite/textformatter/DefaultTextFormatter.java` | `TemplateRenderer.java`, `core-api/src/main/java/me/majhrs16/suite/api/channel/ChannelRegistry.java` |
| Understand routing (VM) | `iflow-vm/src/main/java/me/majhrs16/suite/iflow/vm/engine/VmPolicyEngine.java` | `VmInterpreter.java`, `SoftMMU.java` |
| Understand bootstrap | `host/src/main/java/me/majhrs16/suite/host/SuiteBootstrap.java` | `SuiteHost.java`, `MessageDispatcher.java` |
| Understand Spigot integration | `spigot-host/src/main/java/me/majhrs16/suite/spigothost/TextFormatterSuitePlugin.java` | `SpigotActorDirectory.java`, `SpigotChatDelivery.java` |
| Understand Fabric integration | `fabric-host/src/main/java/me/majhrs16/suite/fabrichost/TextFormatterSuiteMod.java` | `FabricActorDirectory.java`, `FabricChatDelivery.java` |
| Understand translation | `gtranslate/src/main/java/me/majhrs16/suite/gtranslate/GTranslateProvider.java` | `core-api/src/main/java/me/majhrs16/suite/api/spi/TranslationService.java` |
| Understand sync | `transport/src/main/java/me/majhrs16/suite/transport/Transport.java` | Any `sync-*/SyncSink.java` |
| Understand config | `host/src/main/java/me/majhrs16/suite/host/config/ConfigLoader.java` | `HostConfig.java`, `ConfigValidator.java` |

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

*Generated from actual repository state. Last updated: 2026-10-28 (F5 closes the six base packages; F6 adds transport as a dependency package, gtranslate/ltranslate activators, module lifecycle events for chattranslator, sync-bus as an activatable package with hot sink registration, sync-common as a shared dependency, sync-socket/sync-telegram/sync-http/sync-websocket as activatable sink packages, a neutral metrics facade, and observability as an activatable package with DebugEndpoint moved to host.debug).*
