# TextFormatter Suite - Introduction

## What is TextFormatter Suite?

TextFormatter Suite is a modern, modular, and highly extensible chat formatting and translation platform for Minecraft servers. Built on a clean hexagonal architecture, it provides a robust foundation for chat formatting, translation, synchronization, and in-world text interactions.

## Key Features

### 🎯 Core Capabilities
- **Chat Formatting** - Advanced MiniMessage-based formatting with placeholders, gradients, and hover events
- **Translation** - Multi-provider translation (Google, LibreTranslate) with auto-detection
- **Message Routing** - iFlow VM with binary `.flow` policies and `HOST` services
- **Cross-platform** - Spigot/Paper (1.20.6+) — Fabric 1.21+ **production-ready** (fabric-host compiles: Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents)
- **Real-time Sync** - Discord, Telegram, HTTP, TCP/UDP, WebSocket, Velocity (production-ready)

### 🏗️ Architecture
- **Hexagonal Architecture** - Clean separation of core logic from platform adapters
- **Modular Design** - 31 independent Gradle modules with composite build
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
- **No Inline Expression Engine** - SpEL removed; templates only substitute placeholders, and policies run in the iFlow VM
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
| `src/core-api` | 17 | SPI + model (JDK-only): `Module`, `Message`, `Translator`, `SyncSink`, `ActorDirectory`, `TranslationService` |
| `src/kernel` | 17 | `ModuleLoader`, `ModuleGraph` (Tarjan, self-cycle detection, semver) |
| `src/textformatter` | 17 | MiniMessage engine, `MiniEscape` (10 chars), `ChannelRegistry`, `<tr>` translation |
| `src/iflow` | 17 | `DefaultRouter`, `Rule`, `RateLimiter` (per-key, `ReentrantReadWriteLock`) |
| `src/gtranslate` | 17 | Google Translate provider (web scraping, UA rotation, rate limit) |
| `src/ltranslate` | 17 | LibreTranslate provider (self-hosted/public) |
| `src/sync-discord` | 17 | Discord v10 (WebSocket + REST), `char[]` tokens |
| `src/sync-telegram` | 17 | Telegram Bot, long-poll + watermark, `char[]` tokens |
| `src/sync-http` | 17 | Webhook + REST (`HttpServer`), inbound/outbound |
| `src/sync-tcpudp` | 17 | TCP/UDP raw, JSON line/datagram |
| `src/sync-velocity` | 17 | **Production-ready**: async queue, retry/backoff, metrics, health, dynamic discovery, mapping avanzado |
| `src/sync-websocket` | 17 | WebSocket sync (SO_REUSEADDR, auth token, subscriptions, log streaming) |
| `src/sync-bus` | 17 | **Nuevo**: Pipeline unificado (deduplicación global, aislamiento por sink, retry/backoff, métricas) |
| `src/host` | 17 | Composition root: `SuiteHost`, `MessageDispatcher`, `ConfigLoader` (enum `ConfigPath`) |
| `src/messages` | 17 | i18n EN/ES, `MessagesCatalog` singleton |
| `src/tester` | 17 | 25 runtime tests + `PerformanceProfiler` |
| `src/transport` | 17 | `HttpTransport` (`HttpURLConnection`), `MessageCodec`, SSRF protection (`getAllByName`) |
| `src/manager-spi` | 17 | Module Manager SPI: `ModuleCoordinate`, `ModuleDescriptor`, `ModuleLifecycle`, `Environment`, manifest/version parsing |
| `src/manager-core` | 17 | Package installer, local package database, transaction log |
| `src/manager-apt` | 17 | Repository manager, dependency resolver, APT facade/installer |
| `src/presets` | 17 | Preset catalog (standard, rpg, staff, minimal) + YAML import/export; no execution engine |
| `src/inworld` | 17 | Signs, chests, books (WORLD/RADIUS), click/hover, glossary/cache |
| `src/observability` | 17 | `/metrics` (Prometheus), `/debug/*` (auth, 127.0.0.1), Health checks |
| `src/extension-api` | 17 | Extension SPI: `Extension`, `ExtensionContext`, Capability system |
| `src/example-extension` | 17 | Demo extension |
| `src/loadtest` | 17 | JMH benchmarks + Gatling |
| `src/performance` | 17 | Profiling: `PerformanceProfiler`, `HotspotDetector`, `CacheOptimizer` |

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
- [Release Notes](11-Release-Notes.md)
- [Migration Guide](12-Migration-Guide.md)