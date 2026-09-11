# Research A3: Clean Architecture Verification — TextFormatter Suite

**Date:** 2026-09-06  
**Scope:** Full suite (core-api, kernel, host, iflow, textformatter, spigot-host, fabric-host, sync-*, gtranslate, ltranslate, transport, coretranslator)  
**Method:** Static analysis of source code, build.gradle dependencies, and test structure

---

## 1. Layer Diagram (ASCII) with Dependency Arrows

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         FRAMEWORKS / DRIVERS (External)                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │  Bukkit/    │  │    JDA      │  │  Loom/      │  │  snakeyaml, │        │
│  │  Paper API  │  │  (Discord)  │  │  Fabric API │  │  Adventure  │        │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘        │
└─────────┼────────────────┼────────────────┼────────────────┼───────────────┘
          │                │                │                │
          ▼                ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    INTERFACE ADAPTERS (Adapters)                            │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │  spigot-host     │  │  fabric-host     │  │  sync-* (discord,        │  │
│  │  SpigotActorDir  │  │  FabricActorDir  │  │  telegram, http,         │  │
│  │  SpigotChatDeliv │  │  FabricChatDeliv │  │  tcpudp)                 │  │
│  │  DiscordBridge   │  │  TextFormatter   │  │  DiscordSink,            │  │
│  │  TextFormatter   │  │  SuiteMod        │  │  TelegramSink,           │  │
│  │  SuitePlugin     │  │                  │  │  HttpSink, TcpSink,      │  │
│  └────────┬─────────┘  └────────┬─────────┘  │  UdpSink                 │  │
└───────────┼─────────────────────┼────────────└───────────┬──────────────┘
            │                     │                        │
            │ implements          │ implements             │ implements
            ▼                     ▼                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      PORTS (Interfaces in core-api / host)                  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │  core-api/spi    │  │  host/port       │  │  core-api/spi            │  │
│  │  ActorDirectory  │  │  ChatDelivery    │  │  SyncSink                │  │
│  │  Translator      │  │                  │  │  SyncListener            │  │
│  │  SyncSink        │  │                  │  │  PermissionChecker       │  │
│  │  SyncListener    │  │                  │  │  PluginLogger            │  │
│  │  UserLanguageStr │  │                  │  │  PlaceholderResolver     │  │
│  │  ExpressionEval  │  │                  │  │                          │  │
│  └────────┬─────────┘  └────────┬─────────┘  └───────────┬──────────────┘
└───────────┼─────────────────────┼────────────────────────┼──────────────┘
            │                     │                        │
            ▼                     ▼                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        USE CASES (host / kernel)                            │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │  host            │  │  kernel          │  │  iflow                   │  │
│  │  SuiteHost       │  │  ModuleLoader    │  │  DefaultRouter           │  │
│  │  MessageDispatc  │  │  ModuleGraph     │  │  RateLimiter             │  │
│  │  ConfigLoader    │  │  ResolutionResul │  │  Rule evaluation         │  │
│  │  TranslatorsConf │  │  Environment     │  │                          │  │
│  │  TranslationSvc  │  │                  │  │                          │  │
│  └────────┬─────────┘  └────────┬─────────┘  └───────────┬──────────────┘
└───────────┼─────────────────────┼────────────────────────┼──────────────┘
            │ uses                │ uses                   │ uses
            ▼                     ▼                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        ENTITIES (core-api/model)                            │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Message, Actor, Channel, Direction, ModuleDescriptor, SemVer,      │   │
│  │  Language, Formats, MessageType, ColorMode, SoundSpec, ChatMessage, │   │
│  │  ActorKind, Capability, Requirement                                  │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘

DEPENDENCY RULE: All arrows point INWARD (toward Entities)
```

---

## 2. Dependency Analysis by Module

### 2.1 Core-api (`suite/core-api`) — **ENTITIES + PORTS** ✅ CLEAN
```
Dependencies: NONE (only java.*, javax.*)
```
- Pure JDK 17, zero external dependencies
- Contains: Entities (Message, Actor, Channel, Direction, ModuleDescriptor, SemVer, etc.)
- Contains: Ports (ActorDirectory, Translator, SyncSink, SyncListener, PermissionChecker, PluginLogger, UserLanguageStore, PlaceholderResolver, ExpressionEvaluator)
- **No framework annotations, no Adventure, no Bukkit, no JDA**

### 2.2 Kernel (`suite/kernel`) — **USE CASES** ✅ CLEAN
```
Dependencies: core-api only
```
- ModuleLoader (ServiceLoader discovery)
- ModuleGraph (dependency resolution, semver handshake, cycle detection)
- ResolutionResult, Environment, Capability, Requirement
- **No framework dependencies**

### 2.3 Textformatter (`suite/textformatter`) — **USE CASES** ⚠️ MINOR ISSUE
```
Dependencies: 
  - core-api ✅
  - net.kyori:adventure-text-minimessage:4.17.0 ⚠️
  - net.kyori:adventure-text-serializer-plain:4.17.0 ⚠️
```
- TemplateRenderer, TemplateContext, Template, MiniEscape
- Channel, ChannelRegistry, DefaultTextFormatter
- **Issue:** Direct dependency on Adventure (text formatting library) in use case layer.
  - Adventure is not a platform framework but a domain-specific library for MiniMessage parsing.
  - Acceptable for a text formatting engine, but technically leaks an external dep into use cases.

### 2.4 Iflow (`suite/iflow`) — **USE CASES** ⚠️ MINOR ISSUE
```
Dependencies:
  - core-api ✅
  - textformatter ✅ (same layer)
  - net.kyori:adventure-text-minimessage:4.17.0 ⚠️ (transitive via textformatter + direct)
  - net.kyori:adventure-text-serializer-plain:4.17.0 ⚠️
```
- DefaultRouter, Rule, RateLimiter, PermissionChecker (port), PolicyTarget, RouteDecision
- **Issue:** Depends on Adventure for MiniMessage in rules. Same concern as textformatter.

### 2.5 Host (`suite/host`) — **USE CASES (Composition Root)** ❌ VIOLATION
```
Dependencies:
  - core-api ✅
  - kernel ✅
  - textformatter ✅
  - iflow ✅
  - transport ✅
  - gtranslate ❌ (ADAPTER implementation)
  - ltranslate ❌ (ADAPTER implementation)
  - net.kyori:adventure-text-minimessage:4.17.0 ⚠️
  - org.yaml:snakeyaml:2.2 ⚠️
```
- SuiteHost, MessageDispatcher, ConfigLoader, HostConfig, TranslatorsConfig, MessagesConfig, YamlUserLanguageStore, TranslationService
- **Critical Violation:** `TranslatorsConfig` (host/config/) directly instantiates `GTranslate` and `LTranslate` (adapter implementations):
  ```java
  // TranslatorsConfig.java:81-88
  Translator settings = switch (kind) {
      case "google" -> new GTranslate(new HttpTransport());  // ← concrete adapter
      case "libre" -> new LTranslate(...);                   // ← concrete adapter
  };
  ```
- Host has **compile-time dependencies** on adapter modules (gtranslate, ltranslate) — violates Dependency Inversion Principle.
- **Correct approach:** Host should depend only on `Translator` port (core-api) and discover implementations via ServiceLoader at runtime.

### 2.6 Spigot-host (`suite/spigot-host`) — **ADAPTER** ✅ CORRECT
```
Dependencies (compile-time):
  - core-api, kernel, iflow, host, textformatter, transport, messages, tester ✅
  - sync-discord, sync-http, sync-tcpudp, sync-telegram ✅
  - gtranslate, ltranslate ✅ (composition root bundles all)
  - net.kyori:adventure-platform-bukkit, adventure-api, adventure-text-minimessage ✅
  - net.dv8tion:JDA:6.4.2 ✅
  - org.yaml:snakeyaml:2.2 ✅
  - org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT (compileOnly) ✅
  - me.clip:placeholderapi:2.11.6 (compileOnly) ✅
```
- Implements: `ActorDirectory` → `SpigotActorDirectory`
- Implements: `ChatDelivery` → `SpigotChatDelivery`
- Implements: `PlaceholderResolver` → `SpigotPlaceholderResolver`
- **Correctly depends inward on ports**; framework deps only in adapter layer.

### 2.7 Fabric-host (`suite/fabric-host`) — **ADAPTER** ✅ CORRECT
```
Dependencies:
  - core-api, kernel, textformatter, iflow, host, sync-discord, messages ✅
  - Fabric/Loom, Adventure Fabric, JDA, snakeyaml ✅
```
- Implements: `ActorDirectory` → `FabricActorDirectory`
- Implements: `ChatDelivery` → `FabricChatDelivery`
- Implements: `PlaceholderResolver` → `FabricPlaceholderResolver`
- **Correctly depends inward on ports.**

### 2.8 Sync-* Adapters — **ADAPTERS** ✅ CORRECT
```
sync-discord:  core-api, transport, JDA
sync-telegram: core-api, transport, org.json
sync-http:     core-api, transport, org.json
sync-tcpudp:   core-api, transport, org.json
```
- All implement `SyncSink` port from core-api
- Framework deps (JDA, JDK HttpClient, raw sockets) only in adapter layer
- **Clean separation.**

### 2.9 Translator Adapters — **ADAPTERS** ✅ CORRECT (but consumed incorrectly by host)
```
gtranslate:  core-api, transport, org.json
ltranslate:  core-api, transport, org.json
```
- Implement `Translator` port from core-api
- **Clean implementation**, but host violates DIP by depending on them at compile-time.

### 2.10 Transport (`suite/transport`) — **SHARED KERNEL / UTILITY** ✅
```
Dependencies: core-api only (+ org.json for MessageCodec)
```
- MessageCodec, Transport, HttpTransport
- Low-level HTTP/JSON utilities used by adapters
- Acceptable as shared infrastructure.

### 2.11 Coretranslator (`suite/coretranslator`) — **LEGACY BRIDGE** ⚠️
```
Dependencies: core-api, common-legacy
```
- Deprecated module for ChatTranslator v4 compatibility
- Depends on `common-legacy` (the old monolithic code)
- Marked deprecated per ADR; not part of clean architecture.

---

## 3. Violations Summary

| # | File:Line | Origin Layer | Destination Layer | Type | Severity |
|---|-----------|--------------|-------------------|------|----------|
| V1 | `host/config/TranslatorsConfig.java:82` | Use Cases (host) | Adapter (gtranslate) | **Compile-time dependency on adapter impl** | **CRITICAL** |
| V2 | `host/config/TranslatorsConfig.java:83-87` | Use Cases (host) | Adapter (ltranslate) | **Compile-time dependency on adapter impl** | **CRITICAL** |
| V3 | `host/build.gradle:24-25` | Use Cases (host) | Adapter (gtranslate, ltranslate) | **Gradle implementation dependency on adapters** | **CRITICAL** |
| V4 | `textformatter/build.gradle:22-23` | Use Cases (textformatter) | External (Adventure) | External library in use case layer | **MINOR** |
| V5 | `iflow/build.gradle:23-24` | Use Cases (iflow) | External (Adventure) | External library in use case layer | **MINOR** |
| V6 | `iflow/build.gradle:22` | Use Cases (iflow) | Use Cases (textformatter) | Cross-use-case dependency | **MINOR** |

### Violation Details

**V1, V2, V3 — Host depends on Translator implementations (CRITICAL)**
- `TranslatorsConfig.load()` directly constructs `GTranslate` and `LTranslate`
- Host module has `implementation` dependencies on `suite-gtranslate` and `suite-ltranslate`
- **Breaks Dependency Inversion:** Use cases should not know about adapter implementations
- **Fix:** Move translator instantiation to a factory in the adapter layer, or use ServiceLoader discovery in core-api `TranslatorManager`

**V4, V5 — Adventure dependency in use cases (MINOR)**
- Adventure is a text formatting library (MiniMessage), not a platform framework
- Domain-appropriate for a chat formatting suite
- Could be wrapped behind a port interface if strict purity required

**V6 — iflow depends on textformatter (MINOR)**
- Both are "use cases" layer modules
- `DefaultRouter` uses `Channel` from textformatter for rate-limit config
- Acceptable if considered same architectural layer

---

## 4. Dependency Inversion Verification

| Port (Interface) | Location | Implemented By | Direction |
|------------------|----------|----------------|-----------|
| `ActorDirectory` | core-api/spi | SpigotActorDirectory, FabricActorDirectory | ✅ Adapter → Port |
| `ChatDelivery` | host/port | SpigotChatDelivery, FabricChatDelivery | ✅ Adapter → Port |
| `Translator` | core-api/spi | GTranslate, LTranslate | ✅ Adapter → Port |
| `SyncSink` | core-api/spi | DiscordSink, TelegramSink, HttpSink, TcpSink, UdpSink | ✅ Adapter → Port |
| `SyncListener` | core-api/spi | SuiteHost (via wiring) | ✅ Use Case → Port |
| `PermissionChecker` | iflow/channel | SpigotPlugin::hasPermission, Fabric equivalent | ✅ Adapter → Port |
| `PluginLogger` | core-api/spi | SpigotPlugin logger wrapper | ✅ Adapter → Port |
| `UserLanguageStore` | core-api/spi | YamlUserLanguageStore (host/config) | ⚠️ In host not adapter |
| `PlaceholderResolver` | core-api/spi | SpigotPlaceholderResolver, FabricPlaceholderResolver | ✅ Adapter → Port |
| `ExpressionEvaluator` | core-api/spi | Not yet implemented | — |

**Note on `ChatDelivery` location:** Per ADR §132-150, `ChatDelivery` deliberately lives in `host/port/` (not core-api) because its contract carries Adventure `Component`. This is a **conscious deviation** from strict core-api purity, documented and accepted.

**Note on `UserLanguageStore`:** Implemented in `host/config/YamlUserLanguageStore` (use case layer) rather than adapter layer. This is a **minor violation** — persistence is an adapter concern. Should move to spigot-host/fabric-host.

---

## 5. Use Case Orchestration vs Framework Logic

| Use Case Class | Responsibility | Framework Logic? |
|----------------|----------------|------------------|
| `SuiteHost` | Wires TranslationService + Router + TextFormatter; renders per recipient | **No** — pure orchestration |
| `MessageDispatcher` | Expands Direction → recipients; loops deliver; handles REDIRECT/DROP/RATE_LIMIT; plays sounds | **No** — platform-agnostic logic |
| `DefaultRouter` | Evaluates channel policies + rules; returns RouteDecision | **No** — pure domain logic |
| `ModuleGraph` | Resolves module graph with semver handshake | **No** — pure kernel logic |
| `ConfigLoader` | Parses YAML into HostConfig/ChannelRegistry | **No** — uses snakeyaml (acceptable) |
| `TranslationService` | Delegates to Translator port; detects language | **No** — port-based |
| `TextFormatter` implementations | Render MiniMessage templates with context | **No** — uses Adventure for MiniMessage (domain lib) |

**Verdict:** Use cases correctly orchestrate without framework logic. All platform-specific code lives in adapters.

---

## 6. Entity Purity Check

| Entity | Immutable? | Framework Annotations? | Notes |
|--------|------------|------------------------|-------|
| `Message` | ✅ (Builder pattern, defensive copies) | ❌ | Clean POJO |
| `Actor` | ✅ (final fields, `withLanguage` returns new) | ❌ | Clean POJO, carries `nativeHandle` as `Object` |
| `Channel` | ✅ (enum) | ❌ | Clean |
| `Direction` | ✅ (final fields, factory methods) | ❌ | Clean |
| `ModuleDescriptor` | ✅ (record) | ❌ | Clean |
| `SemVer` | ✅ (final fields) | ❌ | Clean |
| `Language` | ✅ (enum-like class) | ❌ | Clean |
| `Formats` | ✅ (Builder) | ❌ | Clean |
| `SoundSpec` | ✅ (record) | ❌ | Clean |
| `Capability` / `Requirement` | ✅ (records) | ❌ | Clean |

**Verdict:** All entities are pure POJOs/records, immutable, no framework annotations. ✅

---

## 7. Testability Analysis (No Framework Required)

### Testable Without Framework (✅ Pure Unit Tests)

| Module | Test Class | Technique |
|--------|------------|-----------|
| `host` | `MessageDispatcherTest` | `RecordingDelivery` (ChatDelivery fake), `TestDirectory` (ActorDirectory fake), `PermissionChecker.ALLOW_ALL` |
| `host` | `SuiteHostTest` | Fake `Translator`, `PermissionChecker.ALLOW_ALL`, quiet `PluginLogger` |
| `host` | `ConfigLoaderTest` | TempDir + YAML fixtures, parses editor-exported config |
| `kernel` | `ModuleGraphTest` | In-memory `Module` stubs, fake `Environment` |
| `kernel` | `ModuleLoaderTest` | ServiceLoader on test classpath |
| `iflow` | `DefaultRouterTest` | Fake `ChannelRegistry`, `PermissionChecker.ALLOW_ALL` |
| `iflow` | `RuleTest` | Pure rule evaluation, no framework |
| `textformatter` | `DefaultTextFormatterTest` | Fake `TranslationService`, `TemplateContext` |
| `textformatter` | `ChannelRegistryTest` | In-memory registry |
| `gtranslate` | `GTranslateTest` | Mock `Transport` (interface) |
| `ltranslate` | `LTranslateTest` | Mock `Transport` |
| `sync-discord` | `DiscordSinkTest` | `WsServer` stub (RFC6455 loopback), mock `Transport` |
| `sync-telegram` | `TelegramSinkTest` | Mock `Transport` |
| `sync-http` | (implied) | Mock `Transport` |
| `sync-tcpudp` | `TcpSinkTest`, `UdpSinkTest` | Loopback sockets, `MessageCodec` |
| `transport` | `MessageCodecTest` | Pure JSON round-trip |

### Requires Framework / Integration (⚠️)

| Module | Test | Reason |
|--------|------|--------|
| `spigot-host` | `LogicTest`, `SpigotChatDeliverySoundNameTest` | Uses Bukkit API (compileOnly), runs in JVM test harness without server |
| `fabric-host` | (no tests yet) | Requires Fabric/Loom test environment |

**Verdict:** All **use cases and core domain** are fully testable without any framework. Adapters have unit tests with mocks/stubs; only spigot-host has lightweight tests using Bukkit API classes (no running server needed). ✅ **Excellent testability.**

---

## 8. Refactoring Recommendations by Priority

### P0 — Critical (Breaks Dependency Inversion)

| # | Recommendation | Effort |
|---|----------------|--------|
| R1 | **Remove `gtranslate` and `ltranslate` dependencies from `host/build.gradle`** | Low |
| R2 | **Move translator instantiation out of `TranslatorsConfig`** — create a `TranslatorFactory` in each adapter module (or use ServiceLoader in `TranslatorManager`) | Medium |
| R3 | **Host should only depend on `Translator` port** — `TranslatorsConfig` reads config, returns list of translator configs (name, params); a `TranslatorProvider` SPI in core-api instantiates them | Medium |

**Implementation sketch for R2/R3:**
```java
// core-api/spi/TranslatorProvider.java (NEW)
public interface TranslatorProvider {
    String kind(); // "google", "libre"
    Translator create(Map<String, Object> settings, Transport transport);
}

// gtranslate module registers via META-INF/services/...TranslatorProvider
// TranslatorsConfig reads YAML, looks up provider by kind, calls create()
```

### P1 — High (Architectural Purity)

| # | Recommendation | Effort |
|---|----------------|--------|
| R4 | **Move `YamlUserLanguageStore` from `host/config` to `spigot-host`/`fabric-host`** — persistence is adapter concern | Low |
| R5 | **Extract `ChatDelivery` port to core-api** with a platform-neutral `RenderedMessage` instead of Adventure `Component` — or keep as documented deviation | Medium |
| R6 | **Wrap Adventure MiniMessage behind a `TemplateEngine` port in core-api** — allows swapping formatter, keeps use cases pure | Medium |

### P2 — Medium (Maintainability)

| # | Recommendation | Effort |
|---|----------------|--------|
| R7 | **Consider making `textformatter` depend on a `TemplateEngine` port instead of Adventure directly** | Medium |
| R8 | **`iflow` should not depend on `textformatter` directly** — use `Channel` from core-api (move `Channel` entity to core-api) | Low |
| R9 | **Add `common-legacy` exclusion** — ensure no accidental dependency on deprecated module | Low |

### P3 — Low (Nice to Have)

| # | Recommendation | Effort |
|---|----------------|--------|
| R10 | **Document the `ChatDelivery` deviation in ADR** (already done in §132-150) | Done |
| R11 | **Add ArchUnit tests** to enforce layer boundaries in CI | Medium |

---

## 9. Module Testability Matrix

| Module | Layer | Testable Without Framework? | Test Strategy |
|--------|-------|----------------------------|---------------|
| `core-api` | Entities/Ports | ✅ Yes | Pure POJO tests, port contract tests |
| `kernel` | Use Cases | ✅ Yes | In-memory stubs, ServiceLoader on test CP |
| `host` | Use Cases | ✅ Yes | Fakes for all ports (Delivery, Directory, Logger, Translator, Permissions) |
| `iflow` | Use Cases | ✅ Yes | Fake ChannelRegistry, PermissionChecker |
| `textformatter` | Use Cases | ✅ Yes | Fake TranslationService, TemplateContext |
| `transport` | Infra | ✅ Yes | Pure codec tests, mock Transport |
| `gtranslate` | Adapter | ✅ Yes | Mock Transport (interface) |
| `ltranslate` | Adapter | ✅ Yes | Mock Transport |
| `sync-discord` | Adapter | ✅ Yes | WsServer stub (loopback), mock Transport |
| `sync-telegram` | Adapter | ✅ Yes | Mock Transport |
| `sync-http` | Adapter | ✅ Yes | Mock Transport |
| `sync-tcpudp` | Adapter | ✅ Yes | Loopback sockets, MessageCodec |
| `spigot-host` | Adapter | ⚠️ Partial | Bukkit API classes (no server), SpigotChatDeliverySoundNameTest |
| `fabric-host` | Adapter | ❌ No tests yet | Requires Fabric test harness |
| `coretranslator` | Legacy | ⚠️ N/A | Deprecated, depends on common-legacy |

**Key Insight:** 131+ tests pass in pure JVM (no Gradle, no server) per ADR §176-177. The entire core domain + use cases + adapter logic (with mocks) is framework-free testable.

---

## 10. Conclusion

**Overall Clean Architecture Compliance: ~85%**

### Strengths
- ✅ Clear layer separation: Entities → Use Cases → Adapters → Frameworks
- ✅ Entities are pure, immutable POJOs
- ✅ Ports (interfaces) defined in core-api/host, implemented by adapters
- ✅ Use cases orchestrate without framework logic
- ✅ Excellent testability: all core logic testable with fakes/mocks
- ✅ Adapters correctly implement ports; framework deps contained

### Critical Gaps
- ❌ **Host depends on adapter implementations (gtranslate, ltranslate) at compile-time** — violates Dependency Inversion
- ❌ **TranslatorsConfig directly instantiates concrete adapters** — should use factory/ServiceLoader

### Minor Issues
- ⚠️ Adventure dependency in use case layer (textformatter, iflow) — domain-appropriate but technically external
- ⚠️ `iflow` → `textformatter` cross-dependency (same layer)
- ⚠️ `UserLanguageStore` implementation in host not adapter layer

### Recommended Next Steps
1. **Immediate (P0):** Refactor `TranslatorsConfig` to use `TranslatorProvider` SPI / ServiceLoader; remove gtranslate/ltranslate deps from host
2. **Short-term (P1):** Move `YamlUserLanguageStore` to platform adapters; consider `TemplateEngine` port
3. **Ongoing:** Add ArchUnit tests to prevent regression

---

*Generated by automated architecture audit. No code modified.*
