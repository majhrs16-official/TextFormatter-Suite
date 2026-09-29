# Release Notes

## Version 2.1.0 (Current Development)

### Major Features

#### FASE 1-3: Foundation
- ✅ Core API with SPI system
- ✅ Module kernel with ServiceLoader discovery
- ✅ Hexagonal architecture implementation

#### FASE 4: Platform Adapters
- ✅ Spigot/Paper host (1.20.6+, compiles with Paper API 1.21.4)
- ❌ Fabric host — **EXCLUDED** (42 compile errors: uses Spigot/Bukkit APIs instead of Fabric APIs; rewrite needed to `ServerCommandSource`, `FabricAudiences`, Fabric events, Brigadier)
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
- ✅ Velocity proxy sync (Production-ready)
- ✅ Plugin messaging channel
- ✅ Async queue with retry/backoff
- ✅ Health checks & metrics
- ✅ Dynamic discovery & advanced mapping

#### FASE 10: Observability
- ✅ Prometheus metrics endpoint (`/metrics`)
- ✅ Debug endpoints (`/debug/dump`, `/debug/state`, `/debug/channels`, `/debug/rules`, `/debug/sinks`)
- ✅ Health checks for sinks (JVM, threads)
- ✅ Auth token, 127.0.0.1 binding, no CORS

#### FASE 11: Extensions SDK
- ✅ Extension SPI
- ✅ ExtensionManager
- ✅ ExtensionContext API
- ✅ Capability system
- ✅ Example extension

#### FASE 12: Module Manager (F12) — Núcleo Completado
- ✅ ModuleCoordinate/Descriptor/Environment
- ✅ ModuleLifecycle SPI
- ✅ DefaultModuleLifecycle implementation
- ✅ GitHub releases downloader (con soporte local/HTTP/File)
- ✅ Version resolver (semver ranges + env compatibility)
- ✅ Dependency resolver (parsing module.yml from JAR)
- ✅ Dependency relocator (shade, fixed infinite recursion)
- ✅ Isolated ClassLoader (parent-last)
- ✅ SHA256 verification (mandatory, separate .sha256 asset)
- ✅ register() SPI-only (no instancia Module)
- ✅ discoverAll() / discoverAvailableModules() para integración kernel
- ✅ Manifest validation obligatoria
- ✅ Force flag para versiones incompatibles
- ✅ `/suite module` commands (install/update/list/remove/info)
- ✅ `/suite suite update` command

#### FASE 13: sync-websocket
- ✅ WebSocket server (Java-WebSocket)
- ✅ Endpoints: /ws/chat, /ws/events, /ws/sync, /ws/logs
- ✅ Auth via token
- ✅ Subscription management
- ✅ Log streaming
- ✅ Integration in spigot-host/fabric-host
- ✅ SO_REUSEADDR fix

#### FASE 14: Presets & TransformEngine
- ✅ Presets module (standard, rpg, staff, minimal)
- ✅ TransformEngine with SpEL sandboxed
- ✅ TransformOps: rewrite, sounds, sleep, setLangSource, setLangTarget, setColorMode, setFormatPapi, setChannel
- ✅ PresetManager with YAML import/export
- ✅ TransformOp hierarchy with Jackson polymorphic serialization
- ✅ engine.parallel knob

#### FASE 15: F8 In-World
- ✅ InWorldHandler for signs, chests, books
- ✅ WORLD/RADIUS channel types
- ✅ Click/hover interactions
- ✅ Glossary/cache system with TTL
- ✅ SignChangeEvent handling
- ✅ Container interactions
- ✅ Written book reading
- ✅ Click/hover actions (open_url, run_command, etc.)

#### Security Sprint 3 — Completado
| Item | Implementación |
|------|----------------|
| Tokens en `char[]` | `JdaDiscordSink`, `DiscordSink`, `TelegramSink`, `LTranslate` usan `char[]` + `Arrays.fill('\0')` |
| MiniEscape completo | Escapa `< > \ { } [ ] ( ) # @` (10 chars) |
| PAPI dynamic check | `SpigotPlaceholderResolver.available()` |
| SpEL LRU cache | `LruExpressionCache` (1024 entradas) en `SpelExpressionEvaluator` |
| SSRF protection | `HttpTransport` valida IPs contra RFC 1918/3927/6598, loopback, multicast, deny patterns configurables |
| DependencyVerification | `verification-metadata.xml` con SHA256/SHA512; `dependencyLocking` en build.gradle |

### Fixes Críticos (Auditoría 14/16 sep + fixes 18 sep)
- **CT-01**: `Message.toJson()` → serialización JSON real (antes `toString()`)
- **OBS-01**: `DebugEndpoint` executor shutdown en `stop()`
- **CFG-01**: `ConfigLoader.LoadResult` con errores explícitos, logging ERROR level
- **MGR-02**: `register()` SPI-only + `discoverAll()` para kernel
- **SEC-01**: SpEL LRU cache (1024) implementado
- **SEC-02**: SSRF protection en `HttpTransport` (deny patterns RFC 1918/3927/6598)
- **ModuleGraph self-cycle**: Detecta self-edges en Tarjan (A→A)
- **RateLimiter race**: `ReentrantReadWriteLock` entre `tryAcquire` y `purgeIdle`
- **WebSocket bytes vs chars**: Límite en bytes UTF-8, no chars
- **SSRF/DNS hardening**: `InetAddress.getAllByName()` para validar todas las IPs resueltas

### Repository Abstraction (F12-14) — Completado
- `repositories:` en `config.yml` con soporte GitHub, local (`file://`), HTTP
- Fallback ordenado entre repositorios
- Testing local sin GitHub requerido
- `discoverAvailableModules()` funciona con repositorios locales/HTTP

### Build & CI
- ✅ Composite build: todos los módulos usan `project(':src:...')` dependencies
- ✅ spigot-host compila con project dependencies (Paper API 1.21.4)
- ✅ loadtest compila (Adventure deps, fixed TranslationService mock)
- ✅ inworld compila (Paper API 1.21.4)
- ✅ loadtest compilación arreglada (mock TranslationService con TranslatorManager)

### Testing — Completado/Mejorado
- ✅ 12 tests E2E pipeline en `E2EPipelineTest.java` (chat → iFlow → format → delivery)
- ✅ 14 tests SpEL security en `SpelExpressionEvaluatorSecurityTest.java`
- ✅ 15 tests Translation cache/dedup en `TranslationServiceCacheTest.java`
- ✅ 4 tests Module Manager HTTP repo en `LocalHttpRepositoryTest.java`
- ✅ 19 tests extendidos GTranslate (edge cases, malformed, unicode, rate limit)
- ✅ 15 tests extendidos LTranslate (error handling, unicode, rate limit)
- ✅ Todos los módulos pasan tests (79 tasks successful)

### Web Editor — Rules Graph Editor (Nuevo en 2.1.0)
- ✅ **8 tipos de nodo**: input, cond, transform, loop, sleep, output, redirect, channel_redirect
- ✅ **Matcher completo**: channel, sender, receiver, direction (8 tipos)
- ✅ **Condition (SpEL)**: textarea con syntax highlighting, ejemplos integrados
- ✅ **10 Actions**: cancel(), skipTranslate(), rewrite(), sounds(), sleep(), setLangSource(), setLangTarget(), setFormatPapi(), setChannel()
- ✅ **Target selector**: DROP, REJECT, LOG, REDIRECT, CHANNEL_REDIRECT
- ✅ **Priority**: 0-10000 (lower = higher priority)
- ✅ **Loop back**: selector de nodo cond destino
- ✅ **Round-trip YAML**: import/export sin pérdida de datos
- ✅ **i18n EN/ES**: 30+ keys traducidas
- ✅ **Props panel dinámico**: renderizado específico por tipo de nodo

### Build & Dependency Management
- ✅ **Gradle dependency locking**: 29 proyectos con `gradle.lockfile` (root + 28 subproyectos)
- ✅ **Task `checkLocks`**: auditoría de lockfiles faltantes
- ✅ **Config schema centralization**: `ConfigSchemaGenerator` genera `paths.json` + `js/paths.js` + `js/model.js` desde `ConfigPath` enum
- ✅ **generateSchema/verifySchema** tasks para validación

#### FASE 13 (2026-09-28): Clean Architecture + Release Pipeline + Dependency Verification
- ✅ **Clean Architecture (Translator SPI)**: `host` sin dependencias compile-time a `gtranslate`/`ltranslate`; descubre proveedores via `ServiceLoader` (SPI `TranslatorProvider`) en runtime
- ✅ **Release Pipeline**: GitHub Actions CI/CD (`.github/workflows/ci.yml`, `release.yml`), `verification-metadata.xml` completo con SHA256/SHA512, semantic versioning config
- ✅ **Dependency Verification**: 29 proyectos con `gradle.lockfile`, `verification-metadata.xml` con todos los checksums transitivos (incl. jackson-base-2.22.0, junit-bom-5.14.3, adventure-bom-4.13.1)
- ✅ **fabric-host excluido**: documentado con requisitos de rewrite completo

---

### Breaking Changes from 2.0.x

| Area | Change |
|-------|--------|
| Config | `claim-mode` values changed |
| API | `ModuleDescriptor` new fields |
| Sync | New sink interface |
| Commands | New dynamic command system |
| Config | New `extensions` section |
| Module Manager | `register()` SPI-only, `discoverAll()` nuevo |

### Migration Guide
See [Migration Guide](12-Migration-Guide.md)

---

## Version 2.0.x (Legacy - Monolithic ChatTranslator)
*See git history for full changelog*

---

*Release Notes v2.1 - Part of TextFormatter Suite Documentation*