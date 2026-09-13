# TextFormatter Suite - Introduction

## What is TextFormatter Suite?

TextFormatter Suite is a modern, modular, and highly extensible chat formatting and translation platform for Minecraft servers. Built on a clean hexagonal architecture, it provides a robust foundation for chat formatting, translation, synchronization, and in-world text interactions.

## Key Features

### 🎯 Core Capabilities
- **Chat Formatting** - Advanced MiniMessage-based formatting with placeholders, gradients, and hover events
- **Translation** - Multi-provider translation (Google, LibreTranslate) with auto-detection
- **Message Routing** - iFlow rule engine with SpEL conditions and actions
- **Cross-platform** - Spigot/Paper and Fabric support
- **Real-time Sync** - Discord, Telegram, HTTP, TCP/UDP, WebSocket, Velocity

### 🏗️ Architecture
- **Hexagonal Architecture** - Clean separation of core logic from platform adapters
- **Modular Design** - 22 independent Gradle modules
- **SPI-based** - ServiceLoader discovery for extensions
- **Zero-dependency Core** - Core API has zero external dependencies

### ⚡ Performance
- **Parallel Processing** - Configurable parallel message processing
- **Memory Optimized** - Object pooling, weak caches, memory pressure handling
- **Async Processing** - Non-blocking message pipeline
- **JMH Benchmarked** - Continuous performance regression testing

## Quick Start

### Requirements
- Java 17 or 21
- Minecraft Server (Paper/Spigot 1.20.6+ or Fabric 1.21+)
- Git (for building from source)

### Installation

#### Spigot/Paper
1. Download the latest `textformatter-suite-spigot.jar`
2. Place in your server's `plugins/` folder
3. Start the server - config files will be generated automatically
4. Configure `plugins/TextFormatterSuite/config.yml` as needed
5. Run `/suite reload` to apply changes

#### Fabric
1. Download the latest `textformatter-suite-fabric.jar`
2. Place in your server's `mods/` folder (requires Fabric Loader 0.16+)
2. Start the server - config files will be generated in `config/textformatter-suite/`
3. Configure as needed
4. Run `/suite reload` to apply changes

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
| `/suite test` | Run test suite | `textformattersuite.admin` |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      TextFormatter Suite                        │
├─────────────────────────────────────────────────────────────────┤
│  spigot-host  │  fabric-host  │  (future: velocity, bungee)    │
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

## Next Steps

- [Configuration Guide](02-Configuration.md)
- [Channel Setup](03-Channels.md)
- [Translation Setup](04-Translation.md)
- [iFlow Rules](05-iFlow-Rules.md)
- [Sync Configuration](05-Sync.md)
- [Web Editor](07-Web-Editor.md)
- [Commands Reference](08-Commands.md)
- [Developer Guide](09-Developer-Guide.md)