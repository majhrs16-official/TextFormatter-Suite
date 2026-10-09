# TextFormatter Suite - Introduction

## What is TextFormatter Suite?

TextFormatter Suite is a modern, modular, and highly extensible chat formatting and translation platform for Minecraft servers. Built on a clean hexagonal architecture, it provides a robust foundation for chat formatting, translation, synchronization, and in-world text interactions.

## Key Features

### 🎯 Core Capabilities
- **Chat Formatting** - Advanced MiniMessage-based formatting with placeholders, gradients, and hover events
- **Translation** - Multi-provider translation (Google, LibreTranslate) with auto-detection
- **Message Routing** - iFlow rule engine with SpEL conditions and actions
- **Cross-platform** - Spigot/Paper (1.20.6+) — Fabric 1.21+ **production-ready** (fabric-host compiles: Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents)
- **Real-time Sync** - Discord, Telegram, HTTP, TCP/UDP, WebSocket, Velocity (production-ready)

### 🏗️ Architecture
- **Hexagonal Architecture** - Clean separation of core logic from platform adapters
- **Modular Design** - 29 independent Gradle modules with composite build
- **SPI-based** - ServiceLoader discovery for extensions and modules
- **Zero-dependency Core** - Core API has zero external dependencies (JDK only)
- **Clean Architecture** - `host` module has zero compile-time dependencies on `gtranslate`/`ltranslate`; `TranslatorProvider` SPI via ServiceLoader

### ⚡ Performance
- **Parallel Processing** - Configurable parallel message processing (`engine.parallel`)
- **Memory Optimized** - Object pooling, weak caches, memory pressure handling
- **Async Processing** - Non-blocking message pipeline with bounded executors
- **JMH Benchmarked** - Continuous performance regression testing with loadtest module
- **SyncBus** - Unified pipeline with deduplication, per-sink isolation, retry/backoff, metrics

### 🔒 Security
- **Tokens in `char[]`** - Discord, Telegram, LibreTranslate tokens zeroed after use (`Arrays.fill('\0')`)
- **MiniEscape Complete** - Escapes 10 chars: `< > \ { } [ ] ( ) # @`
- **SpEL Sandbox** - `SimpleEvaluationContext.forReadOnlyDataBinding()` + LRU cache (1024)
- **SSRF Protection** - `HttpTransport` validates IPs via `getAllByName()` (RFC 1918/3927/6598)
- **SafeConstructor** - All YAML loaders use `SafeConstructor` (no arbitrary deserialization)
- **Signature Verification** - Module Manager supports cosign/gpg verification (mandatory for production)

## Quick Start

### Requirements
- Java 17 or 21
- Minecraft Server (Paper/Spigot 1.20.6+ or Fabric 1.21+)
- Git (for building from source)

### Installation

#### Spigot/Paper
1. Download the latest `textformatter-suite-spigot.jar` (from GitHub Releases)
2. Place in your server's `plugins/` folder
3. Start the server - config files will be generated automatically
4. Configure `plugins/TextFormatterSuite/config.yml` as needed
5. Run `/suite reload` to apply changes

#### Fabric (Production-Ready)
1. Download the latest `textformatter-suite-fabric.jar` (from GitHub Releases)
2. Place in your server's `mods/` folder (requires Fabric Loader + Fabric API 0.100.5)
3. Start the server - config files will be generated automatically
4. Configure `config/textformattersuite/config.yml` as needed
5. Run `/suite reload` to apply changes

**Fabric implementation uses:** `ServerCommandSource`, `FabricAudiences`, Fabric event system (`ServerMessageEvents`, `ServerPlayConnectionEvents`, `ServerTickEvents`), Brigadier command registration
- Brigadier native commands
- Fabric Loader + Fabric API + Yarn mappings

When available:
1. Download the latest `textformatter-suite-fabric.jar`
2. Place in your server's `mods/` folder (requires Fabric Loader 0.16+)
3. Start the server - config files will be generated in `config/textformatter-suite/`
4. Configure as needed
5. Run `/suite reload` to apply changes

### Basic Configuration

```yaml
# config.yml
quick-look: true
general:
  language: en
iflow:
  engine:
    parallel: false
sonido:
  enabled: true
chat:
  claim-mode: cancel-event  # or clear-recipients
```

### Basic Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/suite` | Show status | `textformattersuite.user` |
| `/suite reload` | Reload configuration | `textformattersuite.admin` |
| `/suite status` | Show detailed status | `textformattersuite.admin` |
| `/suite lang [auto\|off\|<code>]` | Set language | `textformattersuite.user` |
| `/suite toggle` | Toggle translation | `textformattersuite.user` |
| `/suite reset` | Reset to defaults | `textformattersuite.admin` |
| `/suite test [full\|stress\|concurrency]` | Run test suite | `textformattersuite.admin` |
| `/suite module [install\|update\|list\|remove\|info]` | Module Manager | `textformattersuite.admin` |
| `/suite suite update` | Full suite update | `textformattersuite.admin` |
| `/suite health` | Health check | `textformattersuite.admin` |
| `/suite metrics` | Prometheus metrics | `textformattersuite.admin` |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      TextFormatter Suite                        │
├─────────────────────────────────────────────────────────────────┤
│  spigot-host  │  fabric-host (compila)    │
├─────────────────────────────────────────────────────────────────┤
│                        suite-host                               │
│  ┌─────────────┬─────────────┬─────────────┬─────────────────┐ │
│  │  Dispatcher │  ConfigLoad │  Translator │  ModuleManager  │ │
│  └─────────────┴─────────────┴─────────────┴─────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│  textformatter │ iflow │ kernel │ transport │ gtranslate │ ... │
├─────────────────────────────────────────────────────────────────┤
│                        core-api (SPI)                           │
└─────────────────────────────────────────────────────────────────┘
```

## Module Overview

| Module | Java | Role |
|--------|------|------|
| `suite/core-api` | 17 | SPI + model (JDK-only): `Module`, `Message`, `Translator`, `SyncSink`, `ActorDirectory`, `TranslationService` |
| `suite/kernel` | 17 | `ModuleLoader`, `ModuleGraph` (Tarjan, self-cycle detection, semver) |
| `suite/textformatter` | 17 | MiniMessage engine, `MiniEscape` (10 chars), `ChannelRegistry`, `<tr>` translation |
| `suite/iflow` | 17 | `DefaultRouter`, `Rule`, `RateLimiter` (per-key, `ReentrantReadWriteLock`) |
| `suite/gtranslate` | 17 | Google Translate provider (web scraping, UA rotation, rate limit) |
| `suite/ltranslate` | 17 | LibreTranslate provider (self-hosted/public) |
| `suite/sync-discord` | 17 | Discord v10 (WebSocket + REST), `char[]` tokens |
| `suite/sync-telegram` | 17 | Telegram Bot, long-poll + watermark, `char[]` tokens |
| `suite/sync-http` | 17 | Webhook + REST (`HttpServer`), inbound/outbound |
| `suite/sync-tcpudp` | 17 | TCP/UDP raw, JSON line/datagram |
| `suite/sync-velocity` | 17 | **Production-ready**: async queue, retry/backoff, metrics, health, dynamic discovery, mapping avanzado |
| `suite/sync-websocket` | 17 | WebSocket sync (SO_REUSEADDR, auth token, subscriptions, log streaming) |
| `suite/sync-bus` | 17 | **Nuevo**: Pipeline unificado (deduplicación global, aislamiento por sink, retry/backoff, métricas) |
| `suite/host` | 17 | Composition root: `SuiteHost`, `MessageDispatcher`, `ConfigLoader` (enum `ConfigPath`) |
| `suite/messages` | 17 | i18n EN/ES, `MessagesCatalog` singleton |
| `suite/tester` | 17 | 25 runtime tests + `PerformanceProfiler` |
| `suite/transport` | 17 | `HttpTransport` (`HttpURLConnection`), `MessageCodec`, SSRF protection (`getAllByName`) |
| `suite/manager-api` | 17 | Module Manager SPI: `ModuleCoordinate`, `ModuleLifecycle`, `Environment` |
| `suite/manager-impl` | 17 | **Núcleo completado**: GitHub/local/HTTP downloader, version resolver, dependency resolver, SHA256, relocator, isolated ClassLoader, register SPI-only, `discoverAll()`/`discoverAvailableModules()`, manifest validation |
| `suite/presets` | 17 | Presets (standard, rpg, staff, minimal) + `TransformEngine` (SpEL sandboxed) |
| `suite/inworld` | 17 | Signs, chests, books (WORLD/RADIUS), click/hover, glossary/cache |
| `suite/observability` | 17 | `/metrics` (Prometheus), `/debug/*` (auth, 127.0.0.1), Health checks |
| `suite/extension-api` | 17 | Extension SPI: `Extension`, `ExtensionContext`, Capability system |
| `suite/example-extension` | 17 | Demo extension |
| `suite/loadtest` | 17 | JMH benchmarks + Gatling |
| `suite/performance` | 17 | Profiling: `PerformanceProfiler`, `HotspotDetector`, `CacheOptimizer` |

## Next Steps

- [Configuration Guide](02-Configuration.md)
- [Channel Setup](03-Channels.md)
- [Translation Setup](04-Translation.md)
- [iFlow Rules](05-iFlow-Rules.md)
- [Sync Configuration](06-Sync.md)
- [Web Editor](07-Web-Editor.md)
- [Commands Reference](08-Commands.md)
- [Developer Guide](09-Developer-Guide.md)
- [Module Manager](10-Module-Manager.md)
- [In-World Features](11-In-World.md)
- [Observability](12-Observability.md)