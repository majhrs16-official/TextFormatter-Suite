# Glossary

## A

**Actor**
An entity that can send or receive messages. Types: `PLAYER`, `CONSOLE`, `SYSTEM`.

**ADR**
Architecture Decision Record - documents important architectural decisions.

**ADR** (SpEL)
Spring Expression Language - used for iFlow conditions and actions.

## B

**Built-in Preset**
Pre-configured preset shipped with the suite (standard, rpg, staff, minimal).

## C

**Capability**
A named, versioned feature that modules can provide or require.

**Channel**
A named message route with formatting, permissions, and translation settings.

**CHANNEL_REDIRECT**
PolicyTarget that redirects a message to a different channel.

**ChannelRegistry**
Registry managing all channels, accessible via `SuiteHost.channels()`.

**Claim Mode**
How the plugin claims chat events: `cancel-event` or `clear-recipients`.

**ConfigLoader**
Utility for loading `config.yml` and `channels/*.yml` into `HostConfig` and `ChannelRegistry`.

**Core API**
The `suite-core-api` module containing SPI interfaces and domain models.

## D

**Direction**
Message audience: `INITIATOR`, `OTHERS`, `ALL`, `CONSOLE`, `WORLD`, `RADIUS`, `PERMISSION`, `SPECIFIC`.

**Dispatcher**
See `MessageDispatcher`.

**DispatchReport**
Result of message dispatch: `considered`, `delivered`, `silenced`, `redirected`, `channelRedirected`.

## E

**Extension**
A dynamically loadable addon implementing the `Extension` SPI.

**ExtensionContext**
Context provided to extensions on enable, granting access to suite services.

**ExtensionManager**
Manages extension lifecycle: discovery, loading, unloading, reloading.

**ExtensionConfig**
Immutable configuration holder for extensions.

**ExpressionEvaluator**
SPI for evaluating expressions (SpEL) in templates and rules.

## F

**Format**
Message template with MiniMessage syntax and placeholders.

**Formats**
Pair of template arrays: `messages` and `tooltips`.

## G

**Glossary**
Key-value store for hover tooltips, accessible via click/hover events.

## G (continued)

**Gradle**
Build system used by the project.

**Gatling**
Load testing framework used for stress testing.

**GTranslate**
Google Translate provider implementation.

**GCloud**
Google Cloud Translation API adapter (planned).

## H

**Hexagonal Architecture**
Ports & Adapters architecture pattern used by the suite.

**Host**
See `SuiteHost`.

**HostConfig**
Immutable configuration object for the suite host.

**HotspotDetector**
Real-time performance bottleneck detector.

## I

**iFlow**
The rule engine module (`suite-iflow`) for message routing and transformation.

**InWorld**
Module for in-world interactions (signs, chests, books, WORLD/RADIUS channels).

**IFlow**
See iFlow.

## J

**JMH**
Java Microbenchmark Harness - used for microbenchmarks.

**JMH Benchmark**
Microbenchmark written using JMH.

## K

**Kernel**
The `suite-kernel` module: ModuleLoader, ModuleGraph, ServiceLoader discovery.

## L

**LibreTranslate**
Open-source translation provider (LibreTranslate).

**LibreTranslate (LTranslate)**
LibreTranslate provider implementation.

**LibreTranslate Module**
`suite-ltranslate` module.

**LibreTranslate Provider**
LibreTranslate translation provider implementation.

## M

**Message**
Immutable message object flowing through the pipeline.

**MessageCodec**
Encodes/decodes messages to/from JSON/bytes.

**MessageCodec**
See `MessageCodec`.

**MessageCodec (interface)**
Interface for encoding/decoding messages.

**MessageDispatcher**
Routes and delivers messages through the pipeline.

**Module**
A JAR implementing the `Module` SPI, discovered via ServiceLoader.

**ModuleDescriptor**
Metadata describing a module: id, version, capabilities, dependencies.

**ModuleDescriptor**
See `ModuleDescriptor`.

**ModuleLoader**
Loads modules via ServiceLoader, resolves dependencies, detects cycles.

**ModuleGraph**
Dependency graph for module loading order.

**ModuleManager**
Manages module lifecycle: discovery, loading, unloading, reloading.

## N

**NMS**
Net Minecraft Server - internal Minecraft server classes (Spigot).

## O

**Observability**
Module providing metrics, debugging endpoints, and health checks.

## P

**PerformanceProfiler**
Tracks CPU time and heap usage per test/operation.

**PlaceholderResolver**
SPI for resolving placeholders (PAPI on Spigot, identity on Fabric).

**PolicyTarget**
Disposition applied to a message: `LOG`, `DROP`, `REJECT`, `REDIRECT`, `CHANNEL_REDIRECT`, `RATE_LIMIT`.

**Preset**
Pre-configured configuration snapshot (standard, rpg, staff, minimal).

**PresetManager**
Manages preset loading, application, and YAML serialization.

## Q

## R

**RateLimiter**
Token bucket implementation for message rate limiting.

**RouteDecision**
Result of routing a message for one recipient.

**Router**
Interface for message routing (iFlow).

**Router (iFlow)**
See `DefaultRouter`.

## S

**ScriptSurface**
Exposed to SpEL expressions in rules, provides atomic operations.

**ScriptSurface**
Interface exposed to SpEL scripts in iFlow rules.

**SemVer**
Semantic Versioning (major.minor.patch).

**ServiceLoader**
Java SPI mechanism for module discovery.

**SPI**
Service Provider Interface - Java's extension mechanism.

**SpEL**
Spring Expression Language - used for iFlow conditions/actions.

**SuiteHost**
Composition root: assembles channels, dispatcher, translators, modules.

**SyncSink**
SPI for external message sinks (Discord, Telegram, HTTP, etc.).

**SyncListener**
Callback interface for inbound sync messages.

**SyncSink**
Interface for external synchronization sinks.

## T

**Template**
Message template with MiniMessage syntax and placeholders.

**TemplateRenderer**
Renders templates with context (sender, recipient, content, languages).

**TemplateContext**
Rendering context: sender, recipient, content, languages, variables.

**TemplateRenderer**
Renders templates to Adventure Components.

**TextFormatter**
Module for MiniMessage rendering and channel formatting.

**TransformEngine**
Executes `TransformOp` operations on messages.

**TransformEngine**
Engine for executing transform operations on messages.

**TransformOp**
Atomic transform operation: `rewrite`, `sounds`, `sleep`, `setLangSource`, `setLangTarget`, `setColorMode`, `setFormatPapi`, `setChannel`.

**TransformOp**
See `TransformOp`.

**Translator**
Interface for translation providers (Google, LibreTranslate).

**Translator**
Interface for translation providers.

**TranslationService**
Manages translation providers, caching, fallback.

## U

## V

**Velocity**
Minecraft proxy platform supported via `sync-velocity`.

## W

**Web Editor**
Browser-based configuration editor (static HTML/JS).

**WebSocket**
Real-time communication protocol used by `sync-websocket`.

**WebSocketSyncSink**
WebSocket-based sync sink implementation.

## X

## Y

**YAML**
Configuration file format used throughout the suite.

**YAML (config)**
Configuration file format used throughout the suite.

## Z

---
*Glossary v2.1 - Part of TextFormatter Suite Documentation*