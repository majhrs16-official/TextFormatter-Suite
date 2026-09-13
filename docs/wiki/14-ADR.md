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

## ADR-008: Message Immutability

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

## ADR-009: Module Communication via Events

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

## ADR-009: Configuration as Code (YAML)

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

## ADR-010: Module Communication via Events

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

*Architecture Decision Records - Part of TextFormatter Suite Documentation*