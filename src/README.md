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
│   ├── textformatter     → Template engine, expression evaluation, channels
│   ├── iflow             → Message routing, rules, rate limiting
│   ├── host              → Shared bootstrap, dispatch, configuration
│   ├── messages          → Message catalog (serialization formats)
│   ├── transport         → Transport abstraction (HTTP, codecs)
│   ├── gtranslate        → Google Translate provider
│   ├── ltranslate        → LibreTranslate provider
│   ├── observability     → Metrics, health checks, debug endpoints
│   ├── presets           → Preset management for transformations
│   ├── manager-api       → Module manager API (remote loading)
│   ├── manager-impl      → Module manager implementation
│   ├── extension-api     → Extension system API
│   ├── coretranslator    → Legacy translation bridge
│   ├── inworld           → In-world integration
│   ├── performance       → Profiling & optimization
│   └── common-legacy     → Legacy shared code
│
├── Sync Transports (platform-agnostic)
│   ├── sync-http         → HTTP webhook sink
│   ├── sync-tcpudp       → TCP/UDP sinks
│   ├── sync-discord      → Discord sink (JDA)
│   ├── sync-telegram     → Telegram sink
│   ├── sync-websocket    → WebSocket sink
│   └── sync-velocity     → Velocity proxy sink
│
└── Platform Adapters
    ├── spigot-host       → Bukkit/Paper plugin (Java 21)
    └── fabric-host       → Fabric mod (Java 21)
```

**Key architectural principles observed in code:**
- `core-api` has **zero dependencies** on other modules — it defines contracts only
- `kernel` depends only on `core-api` — loads modules via SPI
- `host` is the **integration hub** — wires core-api, textformatter, iflow, translations, transports
- Platform adapters (`spigot-host`, `fabric-host`) depend on `host` + all sync modules
- Sync modules depend on `core-api` + `transport` (and `kernel` for tests)
- **No circular dependencies** between core modules

---

## 2. Directory Map

| Directory | Responsibility | Depends On | Used By |
|-----------|----------------|------------|---------|
| `core-api` | Core interfaces, SPI, domain types (Module, Actor, Channel, TranslationService, SyncSink, etc.) | — | **All modules** |
| `kernel` | Module discovery, dependency graph, loading, SPI resolution | `core-api` | `host`, `spigot-host`, `fabric-host`, test fixtures |
| `textformatter` | Template rendering (MiniMessage), SpEL expressions, channel registry | `core-api` | `host`, `iflow`, `presets`, `spigot-host`, `fabric-host` |
| `iflow` | Message routing, transform rules, rate limiting, permission checks | `core-api`, `textformatter` | `host`, `spigot-host`, `fabric-host` |
| `host` | Bootstrap, message dispatch, config loading, platform-agnostic wiring | `core-api`, `kernel`, `textformatter`, `iflow`, `gtranslate`, `ltranslate`, `transport` | `spigot-host`, `fabric-host`, `observability`, `presets`, `manager-impl` |
| `messages` | Message catalog (serialized message formats) | — | `host`, `spigot-host`, `fabric-host` |
| `transport` | Transport abstraction (Transport, HttpTransport, MessageCodec) | `core-api` | `sync-http`, `sync-tcpudp`, `sync-discord`, `gtranslate`, `ltranslate` |
| `gtranslate` | Google Translate API provider | `core-api`, `transport` | `host`, `spigot-host`, `fabric-host` |
| `ltranslate` | LibreTranslate API provider | `core-api`, `transport` | `host`, `spigot-host`, `fabric-host` |
| `observability` | Prometheus metrics, health checks, debug/metrics HTTP endpoints | `core-api`, `host`, `textformatter` | `spigot-host`, `fabric-host` |
| `presets` | Preset management (YAML-based transform presets) | `core-api`, `textformatter`, `iflow`, `host` | `spigot-host`, `fabric-host` |
| `manager-api` | Module manager API (coordinates, lifecycle, descriptors) | `core-api` | `manager-impl`, `spigot-host`, `fabric-host` |
| `manager-impl` | Module manager implementation (GitHub-based remote loading) | `core-api`, `manager-api`, `kernel`, `host` | `spigot-host`, `fabric-host` |
| `extension-api` | Extension system API (Extension, ExtensionContext, ExtensionManager) | `core-api` | `example-extension`, `spigot-host`, `fabric-host` |
| `coretranslator` | Legacy translation bridge | `core-api`, `kernel` | `host` (via LegacyBridge) |
| `inworld` | In-world integration module | `core-api` | `spigot-host`, `fabric-host` |
| `performance` | Hotspot detection, profiling, cache/memory optimization | `core-api` | `spigot-host`, `fabric-host` |
| `sync-http` | HTTP webhook sync sink | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-tcpudp` | TCP/UDP sync sinks | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-discord` | Discord sync sink (JDA) | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-telegram` | Telegram sync sink | `core-api`, `transport` | `spigot-host`, `fabric-host` |
| `sync-websocket` | WebSocket sync sink | `core-api` | `spigot-host`, `fabric-host` |
| `sync-velocity` | Velocity proxy sync sink | `core-api` | `fabric-host` |
| `spigot-host` | Bukkit/Paper plugin entry point, platform adapters | `core-api`, `kernel`, `host`, `iflow`, `textformatter`, all sync, `gtranslate`, `ltranslate`, `messages`, `observability`, `manager-impl`, `manager-api`, `inworld`, `extension-api`, `tester` | — (leaf) |
| `fabric-host` | Fabric mod entry point, platform adapters | `core-api`, `kernel`, `host`, `iflow`, `textformatter`, sync modules, `gtranslate`, `ltranslate`, `messages`, `observability`, `manager-impl`, `manager-api`, `presets`, `inworld`, `extension-api`, `loadtest`, `performance` | — (leaf) |
| `example-extension` | Example extension implementation | `extension-api` | — (leaf) |
| `loadtest` | Load testing utilities | — | `fabric-host` |
| `tester` | Testing utilities (Spigot-dependent) | `core-api` | `spigot-host` |

---

## 3. Module Index

| Module | Purpose | Main Responsibility | Key Classes | Entry Points | README |
|--------|---------|---------------------|-------------|--------------|--------|
| `core-api` | Contracts & SPI | Define all platform-agnostic interfaces | `Module`, `ActorDirectory`, `TranslationService`, `SyncSink`, `Message`, `Channel` | — (no runtime entry) | [core-api/README.md](core-api/README.md) |
| `kernel` | Module system | Load modules, resolve deps, SPI | `ModuleLoader`, `ModuleGraph`, `ModuleDescriptor` | `ModuleLoader.load()` | [kernel/README.md](kernel/README.md) |
| `textformatter` | Formatting engine | Templates, expressions, channels | `DefaultTextFormatter`, `TemplateRenderer`, `SpelExpressionEvaluator`, `ChannelRegistry` | `TextFormatterModule` | [textformatter/README.md](textformatter/README.md) |
| `iflow` | Message routing | Rules, routing, rate limiting | `Router`, `DefaultRouter`, `Rule`, `RateLimiter`, `PermissionChecker` | `IflowModule` | [iflow/README.md](iflow/README.md) |
| `host` | Shared host logic | Bootstrap, dispatch, config | `SuiteBootstrap`, `SuiteHost`, `MessageDispatcher`, `ConfigLoader` | `SuiteBootstrap.initialize()` | [host/README.md](host/README.md) |
| `gtranslate` | Google Translate | Translate via Google API | `GTranslate`, `GTranslateProvider` | `GTranslateModule` | [gtranslate/README.md](gtranslate/README.md) |
| `ltranslate` | LibreTranslate | Translate via LibreTranslate API | `LTranslate`, `LTranslateProvider` | `LTranslateModule` | [ltranslate/README.md](ltranslate/README.md) |
| `transport` | Transport layer | HTTP transport, codecs | `HttpTransport`, `Transport`, `MessageCodec` | — | [transport/README.md](transport/README.md) |
| `observability` | Metrics & health | Prometheus, health checks, endpoints | `Observability`, `MetricsCollector`, `HealthCheckRegistry`, `MetricsEndpoint` | `ObservabilityModule` | [observability/README.md](observability/README.md) |
| `presets` | Transform presets | YAML preset loading/management | `PresetManager`, `TransformEngine`, `PresetsModule` | `PresetsModule` | [presets/README.md](presets/README.md) |
| `manager-api` | Manager contracts | Remote module management API | `ModuleCoordinate`, `ModuleDescriptor`, `ModuleLifecycle` | — | [manager-api/README.md](manager-api/README.md) |
| `manager-impl` | Manager impl | GitHub-based module loading | `ModuleManager`, `GitHubModuleResolver` | — | [manager-impl/README.md](manager-impl/README.md) |
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
  → SpelExpressionEvaluator.java
  → ChannelRegistry.java
```

### Understand Message Routing (iflow)
```
→ src/iflow/README.md#data-flow
  → Router.java / DefaultRouter.java
  → Rule.java / TransformOp.java
  → RateLimiter.java / PermissionChecker.java
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
| `TranslationService` | `SuiteBootstrap` → `GTranslateProvider` / `LTranslateProvider` registered via modules |
| `SyncSink` | `SuiteBootstrap` → each sync module registers its `SyncSink` implementation |
| `ExpressionEvaluator` | `TextFormatterModule` → `SpelExpressionEvaluator` |
| `Router` | `IflowModule` → `DefaultRouter` |
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
| `core-api` → * | `core-api` is depended upon by all | Nothing depends on `core-api` internally | Pure interfaces, no implementations |
| `kernel` → `core-api` | `kernel` uses `core-api` SPI | `core-api` must not depend on `kernel` | Module loading mechanism |
| `host` → core modules | `host` integrates `textformatter`, `iflow`, translations, transports | Core modules must not depend on `host` | `host` is the integration hub |
| Platform adapters → `host` | `spigot-host`/`fabric-host` depend on `host` | `host` must not depend on platform adapters | Platform-agnostic core |
| Sync modules → `core-api` + `transport` | Sync modules implement `SyncSink` SPI | `core-api` must not depend on sync modules | Transport abstraction |
| `manager-impl` → `manager-api` | Implementation depends on API | API must not depend on impl | Standard API/impl separation |

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
Router.route() [iflow] → applies rules, transforms, rate limits
         ↓
TextFormatter.format() → MiniMessage + SpEL evaluation
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
| **Formatting** | `textformatter` | `DefaultTextFormatter`, `TemplateRenderer`, `SpelExpressionEvaluator`, `ChannelRegistry` |
| **Routing/Rules** | `iflow` | `Router`, `DefaultRouter`, `Rule`, `RateLimiter`, `PermissionChecker` |
| **Translation** | `gtranslate`/`ltranslate` | `GTranslateProvider`, `LTranslateProvider` (implement `TranslationService`) |
| **Sync/Transport** | `transport` + sync modules | `Transport`, `HttpTransport`, `SyncSink` implementations |
| **Observability** | `observability` | `MetricsCollector`, `HealthCheckRegistry`, `MetricsEndpoint`, `DebugEndpoint` |
| **Presets** | `presets` | `PresetManager`, `TransformEngine` |
| **Platform: Bukkit** | `spigot-host` | `TextFormatterSuitePlugin`, `SpigotActorDirectory`, `SpigotChatDelivery`, `DynamicCommandRegistrar` |
| **Platform: Fabric** | `fabric-host` | `TextFormatterSuiteMod`, `FabricActorDirectory`, `FabricChatDelivery` |
| **Module Manager** | `manager-impl` | `ModuleManager`, `GitHubModuleResolver` |
| **Extensions** | `extension-api` | `Extension`, `ExtensionManager`, `ExtensionContext` |

---

## 9. Discrepancies & Known Issues

> ⚠️ **Architectural discrepancy**: `host` depends on `kernel` (for `ModuleLoader`), but `kernel` is conceptually a lower-level module. This is intentional — `host` drives the module loading process. However, it means `kernel` cannot use `host` services (no circular dep).

> ⚠️ **Architectural discrepancy**: `spigot-host` depends on `tester` module (which has Spigot API dependency). This is a test-only dependency leaked into main compile scope (see `build.gradle` line 46-48 with exclusion). Ideally `tester` would be test-only.

> ⚠️ **Naming inconsistency**: `sync-velocity` exists but is only used by `fabric-host` (Velocity is a proxy, not a Minecraft platform per se). The name suggests it's a sync transport but it's platform-specific.

> ⚠️ **Duplicate MessageCodec**: Both `transport` and `sync-http`/`sync-tcpudp`/`sync-discord` have `MessageCodec` classes. The `transport` one is the base abstraction; sync modules have their own (possibly for historical reasons).

> ⚠️ **Legacy code**: `coretranslator` and `common-legacy` exist but appear minimally used. `coretranslator.LegacyBridge` is referenced from `host` but may be transitional.

> ⚠️ **Incomplete migration**: `fabric-host` uses Maven coordinates for dependencies (e.g., `me.majhrs16:suite-core-api:2.1.0-SNAPSHOT`) while `spigot-host` uses Gradle project dependencies. This suggests `fabric-host` may be built/published separately.

---

## 10. Quick Reference: Key Files to Read First

| Goal | First File | Then |
|------|------------|------|
| Understand overall architecture | `src/README.md` (this file) | Module READMEs |
| Understand module loading | `kernel/src/main/java/me/majhrs16/suite/kernel/ModuleLoader.java` | `ModuleGraph.java` |
| Understand formatting | `textformatter/src/main/java/me/majhrs16/suite/textformatter/DefaultTextFormatter.java` | `TemplateRenderer.java`, `ChannelRegistry.java` |
| Understand routing | `iflow/src/main/java/me/majhrs16/suite/iflow/DefaultRouter.java` | `Rule.java`, `RateLimiter.java` |
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

*Generated from actual repository state. Last updated: 2026-09-26*