# Architecture Decision Records (ADR)

## ADR-001: Hexagonal Architecture

**Status**: Accepted
**Date**: 2023-01-15

### Context

TextFormatter Suite needs a maintainable, testable architecture that supports multiple platforms (Spigot, Fabric, Velocity) and allows easy extension.

### Decision

Adopt **Hexagonal Architecture** (Ports & Adapters) with:
- **Core** (`core-api`): Pure Java, zero dependencies, contains SPI interfaces and domain models
- **Modules** (`textformatter`, `iflow`, `kernel`, etc.): Implement SPI interfaces, depend only on `core-api`
- **Hosts** (`spigot-host`, `fabric-host`): Platform adapters, depend on modules + platform APIs
- **Sync**: Independent modules implementing `SyncSink` SPI

### Consequences

**Positive:**
- Core is platform-agnostic, testable without Minecraft
- Easy to add new platforms (Velocity, BungeeCord, etc.)
- Clear dependency direction (inward)
- Easy to test core logic in isolation
- Modules can be developed/deployed independently

**Negative:**
- More initial boilerplate
- Indirection through interfaces
- Requires discipline to maintain boundaries

---

## ADR-002: SPI over Dependency Injection

**Status**: Accepted
**Date**: 2023-02-01

### Context

Need a way for modules to provide implementations without tight coupling.

### Decision

Use **Java ServiceLoader (SPI)** for all extension points instead of dependency injection frameworks.

### Consequences

**Positive:**
- Zero dependencies
- Standard Java mechanism
- Works in all environments (Spigot, Fabric, standalone)
- No reflection at runtime (compile-time discovery)
- Hot-reload friendly

**Negative:**
- No constructor injection
- Manual lifecycle management
- No circular dependency detection at startup

---

## ADR-003: Module System over Single Jar

**Status**: Accepted
**Date**: 2023-03-15

### Context

Monolithic jar vs modular architecture.

### Decision

**Modular Gradle multi-project** with each capability as separate module.

### Consequences

**Positive:**
- Independent versioning
- Selective deployment
- Clear ownership boundaries
- Parallel development
- Selective testing

**Negative:**
- Complex build configuration
- Version coordination
- Dependency management overhead

---

## ADR-004: SpEL for Rule Conditions

**Status**: Accepted
**Date**: 2023-04-10

### Context

Need a flexible, expressive language for iFlow rule conditions.

### Decision

Use **Spring Expression Language (SpEL)** for rule conditions and actions.

### Consequences

**Positive:**
- Rich expression language
- Type-safe evaluation
- Property accessors, method calls, operators
- Standard library, well-documented
- Sandboxable via `SimpleEvaluationContext`

**Negative:**
- Learning curve
- Performance overhead vs simple DSL
- Security concerns (sandboxing required)
- Spring dependency in core (mitigated: only in `iflow` module)

### Mitigations

- `SimpleEvaluationContext.forReadOnlyDataBinding()`
- Custom `PropertyAccessor` whitelist
- No `T()`, `new`, `class`, `getClass()` access

---

## ADR-005: MiniMessage over Legacy Format

**Status**: Accepted
**Date**: 2023-05-01

### Context

Need a modern, expressive formatting syntax.

### Decision

Adopt **Kyori Adventure's MiniMessage** as primary format.

### Consequences

**Positive:**
- Rich formatting (gradients, hover, click)
- Standard in Minecraft community
- Active maintenance
- Serialization to JSON/HTML/ANSI

**Negative:**
- Learning curve for legacy users
- Slightly larger payload
- Legacy `&` codes not supported (use `<color>`)

### Migration

- Legacy `&` codes → MiniMessage tags
- `&a` → `<green>`
- `&l` → `<bold>`
- `%var%` → `%var%` (same)

---

## ADR-006: Message Immutability

**Status**: Accepted
**Date**: 2023-06-15

### Context

Messages flow through multiple pipeline stages.

### Decision

**Messages are immutable**. Use `toBuilder()` for modifications.

### Consequences

**Positive:**
- Thread-safe
- Predictable behavior
- Easy debugging
- Undo/redo friendly

**Negative:**
- Object allocation overhead
- Builder pattern verbosity
- Copy overhead for large messages

### Implementation

```java
// Instead of mutation:
message.setText("new text");

// Use builder:
Message updated = message.toBuilder()
    .text("new text")
    .build();
```

---

## ADR-007: Module Communication via Events

**Status**: Accepted
**Date**: 2023-07-01

### Context

Modules need to communicate without direct dependencies.

### Decision

Use **Event Bus** pattern via `MessageEventBus` (core-api).

### Consequences

**Positive:**
- Loose coupling
- Async processing
- Easy testing
- Observable system

**Negative:**
- Eventual consistency
- Harder debugging
- Event schema evolution

### Event Structure

```java
record MessageEvent(Message message, Actor sender, UUID eventId) {
    Message getMessage();           // Original
    Message getModifiedMessage();   // Modified by listeners
    void setMessage(Message);       // Replace message
    void setCancelled(boolean);     // Cancel processing
}
```

---

## ADR-008: Configuration as Code (YAML)

**Status**: Accepted
**Date**: 2023-08-01

### Context

Configuration format choice.

### Decision

**YAML** for all configuration files.

### Consequences

**Positive:**
- Human readable
- Comments supported
- Rich types (lists, maps)
- Wide tooling support
- Round-trip preservation

**Negative:**
- Indentation sensitivity
- No schema validation by default
- Duplicate keys silently overwrite

### Mitigations

- JSON Schema validation (`docs/schema-v2.2.md`)
- Web editor validation
- ConfigValidator at runtime

---

## ADR-009: Clean Architecture — TranslatorProvider SPI (FASE 13)

**Status**: Accepted
**Date**: 2026-09-28

### Context

The `host` module had compile-time dependencies on `gtranslate` and `ltranslate` implementations, violating Clean Architecture (inward dependency rule). This prevented:
- Compiling `host` without translation providers
- Adding new translation providers without modifying `host`
- Testing `host` in isolation

### Decision

Extract **TranslatorProvider SPI** to `core-api`:
- `TranslatorProvider` interface (SPI for discovery)
- `TranslatorManager` (runtime registry via ServiceLoader)
- `Translator` interface (domain contract)
- `TranslationException` (domain exception)

`host` depends **only** on `core-api` (compile-time). Translation providers (`gtranslate`, `ltranslate`) implement `TranslatorProvider` and register via `META-INF/services/me.majhrs16.suite.api.spi.TranslatorProvider`.

`TranslatorsConfig` in `host` discovers providers at runtime via `ServiceLoader.load(TranslatorProvider.class)`.

Tests use `testImplementation` to make providers available to ServiceLoader during test execution.

`spigot-host` maintains `implementation` deps on translators for ServiceLoader at plugin runtime (fat-jar excludes them but ModuleManager loads them).

### Consequences

**Positive:**
- `host` compiles without any translation provider
- New providers = implement `TranslatorProvider` + META-INF registration only
- Clean Architecture: dependencies point inward to `core-api`
- Testability: `host` tests can mock or use real providers via testImplementation
- Runtime flexibility: providers can be added/removed without recompiling `host`

**Negative:**
- Slight runtime overhead (ServiceLoader discovery)
- More complex bootstrap (TranslatorsConfig must initialize before use)
- `spigot-host` needs compile deps for ServiceLoader at plugin runtime

---

## ADR-010: Release Pipeline — GitHub Actions CI/CD

**Status**: Accepted
**Date**: 2026-09-28

### Context

Need automated build, test, and release process for 29-module monorepo with dependency verification.

### Decision

Two GitHub Actions workflows:

**`.github/workflows/ci.yml`** — Runs on every push/PR:
- Build all 29 modules (`./gradlew build`)
- Run all tests (`./gradlew test`)
- Build Spigot plugin fat-jar (`spigot-host:build`)
- Dependency verification (lockfiles + verification-metadata.xml)
- Web editor checks (`npm run check` + `npm run test:integration`)
- Javadoc generation

**`.github/workflows/release.yml`** — Triggers on `v*` tags:
- Build all modules + `publishToMavenLocal`
- Build Spigot fat-jar
- Generate SHA256 for all JARs
- Create GitHub Release with artifacts + SHA256
- Semantic versioning: tag `vX.Y.Z` → version `X.Y.Z`; prerelease if tag contains `-`

### Consequences

**Positive:**
- Fully automated CI/CD
- Reproducible builds via lockfiles + verification metadata
- Release artifacts with integrity verification
- Semantic versioning enforced

**Negative:**
- GitHub Actions minutes cost
- Requires `GITHUB_TOKEN` for releases

---

## ADR-011: Dependency Verification — Gradle Lockfiles + Verification Metadata

**Status**: Accepted
**Date**: 2026-09-28

### Context

Supply chain security: need reproducible builds and verified dependencies across 29 modules.

### Decision

1. **`dependencyLocking`** in root + all subprojects `build.gradle` → 29 `gradle.lockfile` files (root + 28 subprojects)
2. **`verification-metadata.xml`** in `gradle/` with SHA256/SHA512 for ALL transitive dependencies
3. **`checkLocks` task** to audit missing lockfiles (handles intermediate `:src` project)
4. **Lenient verification** for modules with external plugins/deps: `tester`, `inworld`, `spigot-host`, `host`, `textformatter`, `loadtest`

Added checksums for previously missing: `jackson-base-2.22.0.pom`, `junit-bom-5.14.3.module`, `junit-bom-5.14.3.pom`, `adventure-bom-4.13.1.module`, `adventure-bom-4.13.1.pom` + ~50 fabric-loom transitive artifacts.

### Consequences

**Positive:**
- Supply chain integrity verified
- Reproducible builds (lockfiles)
- CI can fail on unverified dependencies
- All 29 projects covered

**Negative:**
- Maintenance overhead (update metadata on dependency upgrades)
- Lenient list requires auditing

---

## ADR-012: fabric-host — Excluded from Build

**Status**: Accepted
**Date**: 2026-09-28

### Context

`fabric-host` module had 42 compilation errors — it was a copy-paste of `spigot-host` using Bukkit/Spigot APIs (`CommandContext`, `hasPermissionLevel`, `sendFeedback`, `getEntity`, `AUTO`, `Server`, `WebSocketSyncSink`, `InWorldHandler`) instead of Fabric APIs.

### Decision

Exclude `fabric-host` from Gradle build (`settings.gradle`). Document rewrite requirements.

Rewrite to Fabric APIs requires:
- `ServerCommandSource` instead of `CommandContext`
- `FabricAudiences` instead of Bukkit audiences
- Fabric event system (`ServerPlayConnectionEvents`, `ServerMessageEvents`) instead of Bukkit events
- Brigadier native commands
- Fabric Loader + Fabric API + Yarn mappings

### Consequences

**Positive:**
- Build passes
- Clear documentation of what's needed
- No broken code in CI

**Negative:**
- No Fabric support currently
- Requires significant rewrite effort

---

*Architecture Decision Records - Part of TextFormatter Suite Documentation*