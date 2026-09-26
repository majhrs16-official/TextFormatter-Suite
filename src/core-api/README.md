# TextFormatter Suite — Core API

## Purpose

The `core-api` module is the **absolute foundation** of TextFormatter Suite. It contains **zero external dependencies** (only JDK + test deps) and defines all the SPI interfaces, data models, and contracts that every other module implements or consumes.

## Key Contents

### Data Models (Immutable)
- `Message` — Central message object with `withX()` mutation pattern
- `Actor` — Sender/recipient representation (Player, Console, NPC, etc.)
- `Language` — Language codes with `AUTO` detection support
- `Direction` — Message routing targets (INITIATOR, OTHERS, ALL, CONSOLE, SPECIFIC, PERMISSION, WORLD, RADIUS)
- `MessageType` — CHAT, PRIVATE, MENTION, JOIN, LEAVE, DEATH, ADVANCEMENT
- `SoundSpec` — Sound playback specification

### SPI Interfaces (Ports)
- `ExpressionEvaluator` — SpEL evaluation sandbox
- `PlaceholderResolver` — PlaceholderAPI / built-in variable resolution
- `TranslationService` — Translation facade (wraps TranslatorManager)
- `PluginLogger` — Logging abstraction
- `ActorDirectory` — Player lookup (online, by UUID, by name, console)
- `UserLanguageStore` — Persistent language preferences
- `ChatDelivery` — Platform-specific message delivery (Adventure Component)
- `Translator` — Individual translation provider
- `TranslatorManager` — Multi-provider management with fallback
- `Module` — Module descriptor (SPI entry point)
- `ModuleDescriptor` — Module metadata (name, version, provides/requires)
- `Capability` / `Requirement` — Module dependency contracts
- `SemVer` — Semantic version parsing
- `Environment` — Host environment (JVM version, contract version)

### Module System
- `Module` = **SPI descriptor only** (not a service instance!)
- Discovered via `ServiceLoader`
- Version resolution: semver ranges + JVM/contract compatibility
- Dependency resolution via `provides` / `requires` capabilities

## Architecture Notes

- **JDK-only** — No Bukkit, Fabric, Spring, or any framework deps
- **Immutable models** — Use `message.withText(...)` not setters
- **Builder pattern** — `Message.builder()...build()`, `Message.Builder.from(existing)`
- **ServiceLoader** — All SPI implementations discovered via `META-INF/services/`

## For Implementers

If you're writing a new module:
1. Implement the SPI interfaces you need
2. Add `META-INF/services/me.majhrs16.suite.api.spi.YourInterface` with your implementation class
3. Declare capabilities/requirements in your module descriptor
4. Keep `core-api` as `compileOnly` dependency

## For Platform Adapters

Platform adapters (Spigot, Fabric) implement:
- `ActorDirectory` — Map platform players to `Actor`
- `ChatDelivery` — Push `Component` to platform chat
- `UserLanguageStore` — Persist language prefs
- `PlaceholderResolver` — Hook PlaceholderAPI (Spigot) or equivalent

## Testing

Run: `./gradlew :src:core-api:test`