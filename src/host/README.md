# host — Shared Bootstrap, Dispatch & Configuration

> **Purpose**: Platform-agnostic integration hub. Wires all core modules (`textformatter`, `iflow`, translations, transports), loads configuration, and provides the `SuiteHost` runtime. Platform adapters (`spigot-host`, `fabric-host`) extend this.

---

## 1. Responsibilities

- **Bootstrap** — `SuiteBootstrap`: loads modules via `kernel`, wires services, creates `SuiteHost`
- **Message dispatch** — `MessageDispatcher`: routes → formats → translates → syncs → delivers
- **Configuration loading** — YAML config → `HostConfig`, `CommandsConfig`, `TranslatorsConfig`, `MessagesConfig`
- **Config validation** — `ConfigValidator` SPI (platform-specific implementations)
- **Service registry** — holds `ActorDirectory`, `TranslationService`, `SyncSink[]`, `Router`, `TextFormatter`, `PlaceholderResolver`
- **User language store** — `YamlUserLanguageStore` implements `UserLanguageStore` SPI
- **Schema generation** — `ConfigSchemaGenerator` generates web-editor schema from config paths

---

## 2. Non-Responsibilities

- **No platform-specific code** — no Bukkit/Fabric APIs
- **No command registration** — platform adapters handle that
- **No event listening** — platform adapters handle that
- **No module implementations** — only wires modules loaded by `kernel`

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | All SPIs, domain types |
| `kernel` | Compile | `ModuleLoader` for module discovery |
| `textformatter` | Compile | `TextFormatter`, `ChannelRegistry` |
| `iflow` | Compile | `Router` for message routing |
| `gtranslate` | Compile | `GTranslateProvider` for translation |
| `ltranslate` | Compile | `LTranslateProvider` for translation |
| `transport` | Compile | `HttpTransport` for webhook sync |
| `gson` | Compile | Config schema generation |
| `snakeyaml` | Compile | YAML config parsing |
| `adventure-text-minimessage` | Compile | Message formatting in config |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Extends `SuiteBootstrap`, provides platform SPIs |
| `fabric-host` | Extends `SuiteBootstrap`, provides platform SPIs |
| `observability` | Uses `host` services for metrics |
| `presets` | Uses `host` config & services |
| `manager-impl` | Uses `host` for module management |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `SuiteBootstrap` | Main bootstrap: `initialize() → SuiteHost`; loads modules, wires services |
| `SuiteHost` | Runtime host: holds service references, lifecycle management |
| `MessageDispatcher` | Core pipeline: route → format → translate → sync → deliver |
| `DispatchReport` | Result of dispatch: success, transformations, sync results |
| `RoutingResult` | Result from `Router` |
| `ConfigLoader` | Loads YAML → `HostConfig`, `CommandsConfig`, `TranslatorsConfig`, `MessagesConfig` |
| `HostConfig` | Root config: channels, formats, sync, translators, modules |
| `CommandsConfig` | Command definitions (aliases, permissions, etc.) |
| `TranslatorsConfig` | Translation provider config (API keys, endpoints) |
| `MessagesConfig` | Message catalog configuration |
| `ConfigValidator` | SPI: `validate(config) → errors[]` (platform-specific) |
| `YamlUserLanguageStore` | Implements `UserLanguageStore` SPI (persists to YAML) |
| `ConfigSchemaGenerator` | Generates `paths.json` + `schema-v2.2.md` for web editor |
| `ChatDelivery` | SPI (in `host.port`): `deliver(actor, message)` — platform adapters implement |

---

## 6. Data Flow

### Bootstrap Flow
```text
Platform Adapter (Plugin/Mod)
         ↓
SuiteBootstrap.initialize(classLoader)
         ↓
ModuleLoader.load() → discovers all Module implementations
         ↓
ModuleGraph.resolve() → topological order
         ↓
Module.initialize(Environment) for each
         ↓
Services registered:
  - ActorDirectory (from platform adapter)
  - TranslationService (from gtranslate/ltranslate modules)
  - SyncSink[] (from sync-* modules)
  - Router (from iflow module)
  - TextFormatter (from textformatter module)
  - PlaceholderResolver (from platform adapter)
  - UserLanguageStore (YamlUserLanguageStore)
  - ExpressionEvaluator (from textformatter module)
         ↓
ConfigLoader.load() → HostConfig + validators
         ↓
SuiteHost created with all services
         ↓
Platform adapter registers commands, listeners, validators
```

### Message Dispatch Flow
```text
MessageDispatcher.dispatch(rawMessage, sender, channel)
         ↓
Router.route(message, actor, channel) → RouteDecision
         ↓ (if ALLOW/TRANSFORM)
TextFormatter.format(message, channel, context) → formatted
         ↓ (if translation needed)
TranslationService.translate(formatted, targetLang) → translated
         ↓
For each SyncSink: sink.send(message) → cross-server
         ↓
ChatDelivery.deliver(recipient, message) → platform-specific send
```

### Configuration Flow
```text
config.yml (YAML)
         ↓
ConfigLoader.load() → HostConfig / CommandsConfig / TranslatorsConfig
         ↓
ConfigValidator.validate(config) → errors (platform-specific)
         ↓
SuiteBootstrap applies config:
  - ChannelRegistry ← channels from HostConfig
  - TranslatorManager ← translators from TranslatorsConfig
  - SyncSink config ← sync section
         ↓
ConfigSchemaGenerator.generate() → web-editor schema files
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SuiteBootstrap.initialize(classLoader)` | `SuiteBootstrap.java:58` | `TextFormatterSuitePlugin.onEnable()` (spigot), `TextFormatterSuiteMod.onInitialize()` (fabric) |
| `MessageDispatcher.dispatch()` | `MessageDispatcher.java` | Platform chat event handlers |
| `ConfigLoader.load()` | `ConfigLoader.java` | `SuiteBootstrap.initialize()` |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom `ConfigValidator` | Implement `ConfigValidator` SPI, platform adapter provides |
| Custom `ChatDelivery` | Implement `ChatDelivery` (in `host.port`), platform adapter provides |
| Custom `UserLanguageStore` | Implement `UserLanguageStore` SPI (core-api) |
| Additional config sections | Extend `HostConfig` + `ConfigLoader` + schema generator |
| Additional sync sinks | Implement `SyncSink` (core-api), register via module |

---

## 9. Exploration Path

```
1. SuiteBootstrap.java              → Main bootstrap logic
2. SuiteHost.java                   → Runtime host & service holder
3. MessageDispatcher.java           → Message pipeline orchestration
4. DispatchReport.java / RoutingResult.java → Dispatch results
5. ConfigLoader.java                → YAML loading
6. HostConfig.java / CommandsConfig.java / TranslatorsConfig.java → Config models
7. ConfigValidator.java             → Validation SPI
8. YamlUserLanguageStore.java       → Language persistence
9. ChatDelivery.java (host.port)    → Delivery SPI
10. ConfigSchemaGenerator.java      → Web-editor schema generation
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — All SPIs and domain types
- [kernel](../kernel/README.md) — Module loading
- [textformatter](../textformatter/README.md) — Formatting pipeline
- [iflow](../iflow/README.md) — Routing
- [gtranslate](../gtranslate/README.md) / [ltranslate](../ltranslate/README.md) — Translation
- [transport](../transport/README.md) — HTTP transport for sync
- [spigot-host](../spigot-host/README.md) — Bukkit platform adapter
- [fabric-host](../fabric-host/README.md) — Fabric platform adapter
- [observability](../observability/README.md) — Metrics on host services
- [presets](../presets/README.md) — Preset management using host config