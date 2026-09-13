# Release Notes

## Version 2.1.0 (Current Development)

### Major Features

#### FASE 1-3: Foundation
- ✅ Core API with SPI system
- ✅ Module kernel with ServiceLoader discovery
- ✅ Hexagonal architecture implementation

#### FASE 4: Platform Adapters
- ✅ Spigot/Paper host (1.20.6+)
- ✅ Fabric host (1.21+)
- ✅ Module lifecycle management

#### FASE 5: i18n & Localization
- ✅ Centralized MessagesCatalog
- ✅ EN/ES translations
- ✅ MessageCatalog singleton

#### FASE 6: iFlow Rule Engine
- ✅ SpEL-based conditions
- ✅ CHANNEL_REDIRECT target
- ✅ Transform operations (rewrite, sounds, sleep, etc.)
- ✅ SpEL condition/action evaluation

#### FASE 7: ConfigValidator
- ✅ Structural validation
- ✅ Schema validation
- ✅ Console reporting

#### FASE 8: Dynamic Commands
- ✅ commands.yml v2 topology
- ✅ Atomic actions
- ✅ Chat-based feedback
- ✅ LuckPerms-style config editing
- ✅ Configurable base name

#### FASE 9: sync-velocity
- ✅ Velocity proxy sync
- ✅ Plugin messaging channel
- ✅ Server mapping

#### FASE 10: Observability
- ✅ Prometheus metrics endpoint
- ✅ Debug endpoints (/debug/simulate, /debug/dump)
- ✅ Health checks for sinks
- ✅ JMX metrics

#### FASE 11: Extensions SDK
- ✅ Extension SPI
- ✅ ExtensionManager
- ✅ ExtensionContext API
- ✅ Capability system
- ✅ Example extension

#### FASE 12: Module Manager
- ✅ ModuleCoordinate/Descriptor
- ✅ GitHub releases downloader
- ✅ Version resolver
- ✅ Dependency relocator (shade)
- ✅ Isolated ClassLoader (parent-last)
- ✅ SHA256 verification
- ✅ Force flag for unsupported versions
- `/suite module` commands
- `/suite suite update` command

#### FASE 13: sync-websocket
- ✅ WebSocket server (Java-WebSocket)
- ✅ Endpoints: /ws/chat, /ws/events, /ws/sync, /ws/logs
- ✅ Auth via token
- ✅ Subscription management
- ✅ Log streaming
- ✅ Integration in spigot-host/fabric-host

#### FASE 14: Presets & Transform Engine
- ✅ Presets module (standard, rpg, staff, minimal)
- ✅ TransformEngine (SpEL sandboxed)
- ✅ TransformOps: rewrite, sounds, sleep, setLangSource, setLangTarget, setColorMode, setFormatPapi, setChannel
- ✅ PresetManager with YAML import/export
- ✅ TransformOp hierarchy with Jackson polymorphic serialization
- ✅ engine.parallel knob

#### FASE 15: F8 In-World
- ✅ InWorldHandler for signs, chests, books
- ✅ WORLD/RADIUS channel types
- ✅ Click/hover interactions
- ✅ Glossary/cache system
- ✅ SignChangeEvent handling
- ✅ Container interactions
- ✅ Written book reading
- ✅ Click/hover actions (open_url, run_command, etc.)
- ✅ Glossary/cache system with TTL

#### FASE 13: sync-websocket
- ✅ WebSocket server (Java-WebSocket)
- ✅ Endpoints: /ws/chat, /ws/events, /ws/sync, /ws/logs
- ✅ Auth via token
- ✅ Subscription management
- ✅ Inbound/outbound handling
- ✅ Log streaming
- ✅ Host integration

#### FASE 14: Presets & TransformEngine
- ✅ Built-in presets (standard, rpg, staff, minimal)
- ✅ TransformEngine with SpEL sandbox
- ✅ TransformOp: rewrite, sounds, sleep, setLangSource, setLangTarget, setColorMode, setFormatPapi, setChannel
- ✅ PresetManager with built-in + custom presets
- ✅ Jackson polymorphic serialization
- ✅ engine.parallel knob

#### FASE 13: sync-websocket
- ✅ WebSocket server (Java-WebSocket)
- Endpoints: /ws/chat, /ws/events, /ws/sync, /ws/logs
- Auth via token
- Subscription management
- Log streaming
- Host integration

#### FASE 12: Module Manager
- ModuleCoordinate/Descriptor/Environment
- ModuleLifecycle SPI
- DefaultModuleLifecycle implementation
- GitHub releases downloader
- Version resolver with ranges
- Dependency relocator (shade)
- Parent-last ClassLoader
- SHA256 verification
- Force flag for unsupported versions
- ModuleManager integration in hosts

#### FASE 14: Presets & TransformEngine
- TransformOp hierarchy (rewrite, sounds, sleep, setLang*, etc.)
- PresetManager with built-in presets
- TransformEngine with SpEL sandbox
- engine.parallel knob
- Jackson polymorphic serialization
- 4 built-in presets (standard, rpg, staff, minimal)

#### FASE 13: sync-websocket
- WebSocketSyncSink implementation
- Endpoints: /ws/chat, /ws/events, /ws/sync, /ws/logs
- Token auth, subscriptions, inbound/outbound
- Log streaming via /ws/logs
- Integration in spigot-host/fabric-host

#### FASE 14: Presets & TransformEngine
- TransformOp hierarchy
- PresetManager with built-in presets
- TransformEngine with SpEL sandbox
- engine.parallel knob
- Jackson polymorphic serialization
- 4 built-in presets (standard, rpg, staff, minimal)

#### FASE 12: Module Manager
- ModuleCoordinate/Descriptor/Environment
- DefaultModuleLifecycle
- GitHub releases downloader
- Version resolver with ranges
- Dependency relocator (shade)
- Parent-last ClassLoader
- SHA256 verification
- Force flag for unsupported versions
- ModuleManager integration in hosts

#### FASE 11: Extensions SDK
- Extension API (Extension, ExtensionContext, ExtensionConfig, ExtensionMetadata)
- ExtensionManager with discovery, dependency resolution
- Capability system
- Example extension
- ExtensionContext with channel registration, message dispatch, state, events

#### FASE 10: Observability
- MetricsEndpoint (/metrics Prometheus, /health)
- DebugEndpoint (/debug/simulate, /debug/dump, /debug/state, /debug/channels, /debug/rules, /debug/sinks)
- HealthCheckRegistry (JVM, threads, sinks)
- MetricsCollector (Prometheus metrics)
- HealthCheckRegistry (JVM, threads, sinks)
- Dynamic commands: /suite health, /suite metrics

#### FASE 13: sync-websocket
- WebSocketSyncSink with Java-WebSocket
- Endpoints: /ws/chat, /ws/events, /ws/sync, /ws/logs
- Token auth, subscriptions, inbound/outbound
- Log streaming
- Host integration

#### FASE 14: Presets & TransformEngine
- TransformOp hierarchy
- PresetManager with built-in presets
- TransformEngine with SpEL sandbox
- engine.parallel knob
- Jackson polymorphic serialization
- 4 built-in presets

#### FASE 12: Module Manager
- ModuleCoordinate/Descriptor/Environment
- DefaultModuleLifecycle
- GitHub releases downloader
- Version resolver with ranges
- Dependency relocator (shade)
- Parent-last ClassLoader
- SHA256 verification
- Force flag for unsupported versions
- ModuleManager integration in hosts

#### FASE 11: Extensions SDK
- Extension API (Extension, ExtensionContext, ExtensionConfig, ExtensionMetadata)
- ExtensionManager: discovery, dependency resolution, load/unload/reload
- Capability system
- Example extension
- ExtensionContext: channel registration, message dispatch, state, events, permissions, config, translation

#### FASE 10: Observability
- MetricsEndpoint (/metrics Prometheus, /health)
- DebugEndpoint (/debug/simulate, /debug/dump, /debug/state, /debug/channels)
- HealthCheckRegistry (JVM, threads, sinks)
- MetricsCollector (Prometheus metrics)
- Dynamic commands: /suite health, /suite metrics
- Debug endpoints: /debug/simulate, /debug/dump, /debug/state, /debug/channels

#### FASE 12: Module Manager
- ModuleCoordinate/Descriptor/Environment
- DefaultModuleLifecycle
- GitHub releases downloader
- Version resolver with ranges
- Dependency relocator (shade)
- Parent-last ClassLoader
- SHA256 verification
- Force flag for unsupported versions
- ModuleManager integration in hosts

#### FASE 11: Extensions SDK
- Extension API (Extension, ExtensionContext, ExtensionConfig, ExtensionMetadata)
- ExtensionManager: discovery, dependency resolution, load/unload/reload
- Capability system
- Example extension
- ExtensionContext: channel registration, message dispatch, state, events, permissions, config, translation

#### FASE 10: Observability
- MetricsEndpoint (/metrics Prometheus, /health)
- DebugEndpoint (/debug/simulate, /debug/dump, /debug/state, /debug/channels)
- HealthCheckRegistry (JVM, threads, sinks)
- MetricsCollector (Prometheus metrics)
- Dynamic commands: /suite health, /suite metrics
- Debug endpoints: /debug/simulate, /debug/dump, /debug/state, /debug/channels

#### FASE 11: Extensions SDK
- Extension API (Extension, ExtensionContext, ExtensionConfig, ExtensionMetadata)
- ExtensionManager: discovery, dependency resolution, load/unload/reload
- Capability system
- Example extension
- ExtensionContext: channel registration, message dispatch, state, events, permissions, config, translation

#### FASE 9: sync-velocity
- VelocitySink implementation
- VelocityPlugin entry point
- Plugin messaging channel
- Auth via secret
- Config: enabled, secret, servers[], mapping

#### FASE 8: Dynamic Commands
- CommandsConfig/Loader
- DynamicCommandRegistrar/DynamicCommand
- /suite module (install/update/list/remove/info)
- /suite suite update
- Tab completion
- Fallback legacy

#### FASE 7: ConfigValidator
- ConfigValidator in host/config
- Validates config.yml, channels/*.yml, rules.yml, translators/*.yml, sync/*.yml
- Issues with shape matching editor: [{nivel, grupo, ruta, mensaje}]
- Integration in spigot-host/fabric-host reloadSuite()

#### FASE 6: iFlow Enhancements
- CHANNEL_REDIRECT target
- Rule: condition/action SpEL
- Rule: redirectChannel
- DefaultRouter: SpEL condition/action evaluation
- ScriptSurface: hasPermission, papi, cancel, setLangTarget, redirect, setFormat, skipTranslate, enableTranslate, setLangSource, setLangSource, setColorMode, setFormatPapi, cloneMessage, toJson, typeIs, directionIs
- TransformOp: rewrite, sounds, sleep, setLangSource, setLangTarget, setColorMode, setFormatPapi, setChannel

#### FASE 5: i18n
- MessagesCatalog singleton
- EN/ES catalogs
- /suite lang uses MessagesCatalog
- FabricPlaceholderResolver

#### FASE 4: fabric-host
- FabricMod entrypoint
- FabricActorDirectory
- FabricChatDelivery
- Loom 1.6.12, mappings 1.21+build.1
- Tested on Fabric 1.21

#### FASE 3: Core Refactoring
- TextFormatterSuitePlugin
- DynamicCommandRegistrar/DynamicCommand
- ModuleLifecycle integration
- ConfigValidator integration

#### FASE 2: Architecture
- Hexagonal architecture
- SPI system
- Module system
- Host abstraction

#### FASE 1: Foundation
- Core API
- Module system
- SPI contracts
- Gradle multi-module setup

### Breaking Changes from 2.0.x

| Area | Change |
|-------|--------|
| Config | `claim-mode` values changed |
| API | `ModuleDescriptor` new fields |
| Sync | New sink interface |
| Commands | New dynamic command system |
| Config | New `extensions` section |

### Migration Guide

See [Migration Guide](12-Migration-Guide.md)

---

*Release Notes v2.1 - Part of TextFormatter Suite Documentation*