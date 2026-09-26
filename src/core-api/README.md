# core-api — Core Contracts & SPI

> **Purpose**: Defines all platform-agnostic interfaces, domain types, and Service Provider Interfaces (SPI) for the TextFormatter Suite. This module has **zero dependencies** on other modules — it is the foundation everything else builds upon.

---

## 1. Responsibilities

- Define the **Module** contract and lifecycle (`Module`, `ModuleDescriptor`, `Capability`, `Requirement`, `SemVer`)
- Define **message domain types** (`Message`, `ChatMessage`, `Actor`, `Channel`, `MessageType`, `Direction`, `Language`, `ColorMode`, `Formats`, `SoundSpec`)
- Define **SPI interfaces** for platform/implementation plugins:
  - `ActorDirectory` — resolve actors (players, consoles, etc.) by name/UUID
  - `TranslationService` / `Translator` / `TranslatorProvider` / `TranslatorManager` — translation pipeline
  - `SyncSink` / `SyncListener` — cross-server message synchronization
  - `PlaceholderResolver` — resolve placeholders in messages
  - `ExpressionEvaluator` — evaluate expressions (SpEL) in templates
  - `PluginLogger` — abstract logging
  - `UserLanguageStore` — persist user language preferences
- Define **event types** (`MessageEvent`)
- Versioning via `SemVer`

---

## 2. Non-Responsibilities

- **No implementations** — only interfaces and abstract base classes
- **No platform-specific code** — no Bukkit, Fabric, JDA, HTTP clients, etc.
- **No configuration loading** — that belongs in `host`
- **No module loading logic** — that belongs in `kernel`

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| *(none)* | — | Foundation module; zero dependencies |

---

## 4. Consumers

**All modules** depend on `core-api`:
- `kernel` — uses `Module`, `ModuleDescriptor`, `Capability`, `Requirement`
- `textformatter` — uses `Message`, `Channel`, `ExpressionEvaluator` SPI
- `iflow` — uses `Message`, `Channel`, `ActorDirectory` SPI
- `host` — uses nearly all SPIs and domain types
- `gtranslate`/`ltranslate` — implement `TranslationService`/`TranslatorProvider`
- `transport` — uses `Message` for codecs
- Sync modules — implement `SyncSink`
- `observability` — uses `Message`, `Channel`
- `presets` — uses `Message`, `Channel`
- `manager-api`/`manager-impl` — use `ModuleDescriptor`, `ModuleCoordinate`
- `extension-api` — uses `Module` concepts
- `spigot-host`/`fabric-host` — implement all SPIs

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `Module` | Base interface for all modules; defines `initialize()`, `shutdown()`, `getDescriptor()` |
| `ModuleDescriptor` | Metadata: id, version, capabilities, requirements, main class |
| `Capability` / `Requirement` | Module dependency declaration (name + version range) |
| `SemVer` | Semantic version parsing/comparison for requirements |
| `ActorDirectory` | SPI: resolve `Actor` by name/UUID; platform-specific impl in adapters |
| `TranslationService` | SPI: high-level translation entry point; `translate(text, targetLang)` |
| `Translator` | SPI: single translation provider; `translate(text, source, target)` |
| `TranslatorProvider` | SPI: provides `Translator` instances; registered via modules |
| `TranslatorManager` | SPI: manages multiple providers, fallback chains |
| `SyncSink` | SPI: send messages to remote servers (HTTP, Discord, TCP, etc.) |
| `SyncListener` | SPI: receive messages from remote servers |
| `PlaceholderResolver` | SPI: resolve `{placeholder}` in messages |
| `ExpressionEvaluator` | SPI: evaluate expressions (e.g., SpEL) in templates |
| `PluginLogger` | SPI: abstract logging for platform adapters |
| `UserLanguageStore` | SPI: persist/load user language preferences |
| `Message` / `ChatMessage` | Core message domain model |
| `Actor` | Message sender/recipient (player, console, webhook, etc.) |
| `Channel` | Logical chat channel (global, local, team, etc.) |
| `MessageEvent` | Event fired when message is processed |

---

## 6. Data Flow

`core-api` has no runtime data flow — it defines **contracts** that other modules implement.

```text
Platform Adapter (spigot-host/fabric-host)
       ↓ implements
ActorDirectory ←──────────────────
TranslationService ←────────────── gtranslate / ltranslate
SyncSink ←──────────────────────── sync-http, sync-discord, sync-tcpudp, etc.
PlaceholderResolver ←───────────── Platform adapter
ExpressionEvaluator ←───────────── textformatter (SpelExpressionEvaluator)
```

---

## 7. Entry Points

None — this is a contract library. Entry points are in modules that **implement** these interfaces:
- `kernel.ModuleLoader` loads `Module` implementations
- `host.SuiteBootstrap` wires implementations
- Platform adapters provide `ActorDirectory`, `PlaceholderResolver`, `ChatDelivery`

---

## 8. Extension Points

| SPI | How to Extend |
|-----|---------------|
| `ActorDirectory` | Implement in platform adapter (see `SpigotActorDirectory`, `FabricActorDirectory`) |
| `TranslationService` / `TranslatorProvider` | Implement in translation module (see `GTranslateProvider`, `LTranslateProvider`) |
| `SyncSink` | Implement in sync module (see `HttpSink`, `JdaDiscordSink`, `TcpSink`, `UdpSink`) |
| `PlaceholderResolver` | Implement in platform adapter (see `SpigotPlaceholderResolver`, `FabricPlaceholderResolver`) |
| `ExpressionEvaluator` | Implement in formatting module (see `SpelExpressionEvaluator`) |
| `UserLanguageStore` | Implement in `host` (see `YamlUserLanguageStore`) |

---

## 9. Exploration Path

```
1. Module.java                    → Module contract & lifecycle
2. ModuleDescriptor.java          → Module metadata & dependencies
3. api/message/*.java             → Domain types (Message, Actor, Channel, etc.)
4. api/spi/ActorDirectory.java    → Actor resolution SPI
5. api/spi/TranslationService.java → Translation SPI hierarchy
6. api/spi/SyncSink.java          → Cross-server sync SPI
7. api/spi/PlaceholderResolver.java → Placeholder resolution SPI
8. api/spi/ExpressionEvaluator.java → Expression evaluation SPI
9. api/event/MessageEvent.java    → Message event type
```

---

## 10. Related Modules

- [kernel](../kernel/README.md) — Loads `Module` implementations
- [textformatter](../textformatter/README.md) — Implements `ExpressionEvaluator`
- [host](../host/README.md) — Wires all SPI implementations
- [gtranslate](../gtranslate/README.md) — Implements `TranslatorProvider`
- [ltranslate](../ltranslate/README.md) — Implements `TranslatorProvider`
- [sync-http](../sync-http/README.md) — Implements `SyncSink`
- [spigot-host](../spigot-host/README.md) — Implements platform SPIs
- [fabric-host](../fabric-host/README.md) — Implements platform SPIs