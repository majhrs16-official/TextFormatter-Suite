# Release Notes

> **Estado actual (2026-10-20):** SpEL y `TransformEngine` fueron eliminados. Las entradas de las fases 6 y 14 se actualizan abajo para reflejar la VM `.flow` y el catálogo de presets; no queda ningún motor de expresiones inline.

## Version 2.1.0 (Current Development)

### Major Features

#### FASE 1-3: Foundation
- ✅ Core API with SPI system
- ✅ Module kernel with ServiceLoader discovery
- ✅ Hexagonal architecture implementation

#### FASE 4: Platform Adapters
- ✅ Spigot/Paper host (1.20.6+, compiles with Paper API 1.21.4)
- ✅ Fabric host — **COMPILA** (Fabric 1.21 + Fabric API 0.100.5, Brigadier, ServerCommandSource, FabricAudiences, Fabric events, ServerMessageEvents, ServerPlayConnectionEvents, ServerTickEvents)
- ✅ Module lifecycle management

#### FASE 5: i18n & Localization
- ✅ Centralized MessagesCatalog
- ✅ EN/ES translations
- ✅ MessageCatalog singleton

#### FASE 6: iFlow VM
- ✅ Binary `.flow` programs (`VmPolicyEngine`/`VmInterpreter`)
- ✅ CHANNEL_REDIRECT target
- ✅ Transform operations (rewrite, sounds, sleep, etc.)
- ✅ `HOST` services, fuel budget and permission checks (no inline expressions)

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
- ✅ Debug endpoints (`/debug/dump`, `/debug/state`, `/debug/channels`, `/debug/rules`, `/debug/sinks`, `/debug/simulate`)
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

#### FASE 14: Presets catalog
- ✅ Presets module (standard, rpg, staff, minimal)
- ✅ `PresetManager` with YAML import/export
- ✅ Preset data model (`PresetConfig`, `PresetChannel`, `PresetTranslator`, `PresetSync`)
- ✅ `engine.parallel` knob
- ⚠️ No execution engine: `Preset.apply(...)` is a placeholder

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
| SpEL elimination | Inline evaluator removed; policies run in the iFlow VM (`.flow`) |
| SSRF protection | `HttpTransport` valida IPs contra RFC 1918/3927/6598, loopback, multicast, deny patterns configurables |
| DependencyVerification | `verification-metadata.xml` con SHA256/SHA512; `dependencyLocking` en build.gradle |

### Fixes Críticos (Auditoría 14/16 sep + fixes 18 sep)
- **CT-01**: `Message.toJson()` → serialización JSON real (antes `toString()`)
- **OBS-01**: `DebugEndpoint` executor shutdown en `stop()`
- **CFG-01**: `ConfigLoader.LoadResult` con errores explícitos, logging ERROR level
- **MGR-02**: `register()` SPI-only + `discoverAll()` para kernel
- **SEC-01**: SpEL eliminado; motor inline retirado del repositorio
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
- ✅ Tests de seguridad de la VM y del sandbox de servicios HOST
- ✅ 15 tests Translation cache/dedup en `TranslationServiceCacheTest.java`
- ✅ 4 tests Module Manager HTTP repo en `LocalHttpRepositoryTest.java`
- ✅ 19 tests extendidos GTranslate (edge cases, malformed, unicode, rate limit)
- ✅ 15 tests extendidos LTranslate (error handling, unicode, rate limit, missing fields)
- ✅ Todos los módulos pasan tests (79 tasks successful)

### Web Editor — Rules Graph Editor (Nuevo en 2.1.0)
- ✅ **8 tipos de nodo**: input, cond, transform, loop, sleep, output, redirect, channel_redirect
- ✅ **Matcher completo**: channel, sender, receiver, direction (8 tipos)
- ✅ Campo de condición eliminado con SpEL; el grafo legacy no evalúa expresiones
- ✅ **10 Actions**: cancel(), skipTranslate(), rewrite(), sounds(), sleep(), setLangSource(), setLangTarget(), setFormatPapi(), setChannel()
- ✅ **Target selector**: DROP, REJECT, LOG, REDIRECT, CHANNEL_REDIRECT
- ✅ **Priority**: 0-10000 (lower = higher priority)
- ✅ **Loop back**: selector de nodo cond destino
- ✅ **Round-trip YAML**: import/export sin pérdida de datos
- ✅ **i18n EN/ES**: 30+ keys traducidas
- ✅ **Props panel dinámico**: renderizado específico por tipo de nodo

### Build & Dependency Management
- ✅ **Gradle dependency locking**: 30 proyectos con `gradle.lockfile` (root + 29 subproyectos)
- ✅ **Task `checkLocks`**: auditoría de lockfiles faltantes
- ✅ **Config schema centralization**: `ConfigSchemaGenerator` genera `paths.json` + `js/paths.js` + `js/model.js` desde `ConfigPath` enum
- ✅ **generateSchema/verifySchema** tasks para validación
- ✅ **Dependency Verification**: `verification-metadata.xml` con SHA256/SHA512 para 435 dependencias no-SNAPSHOT
- ✅ **Reproducible Builds (Build-Timestamp)**: JAR manifest incluye `Build-Timestamp` determinístico
  - SNAPSHOT: epoch (0) → builds byte-idénticos en desarrollo
  - Release: `BUILD_TIMESTAMP` env var o propiedad `buildTimestamp` (ms since epoch)
  - Verificación: `unzip -p artifact.jar META-INF/MANIFEST.MF | grep Build-Timestamp`

#### FASE 13 (2026-09-28): Clean Architecture + Release Pipeline + Dependency Verification
- ✅ **Clean Architecture (Translator SPI)**: `host` sin dependencias compile-time a `gtranslate`/`ltranslate`; descubre proveedores via `ServiceLoader` (SPI `TranslatorProvider`) en runtime
- ✅ **Release Pipeline**: GitHub Actions CI/CD (`.github/workflows/ci.yml`, `release.yml`), `verification-metadata.xml` completo con SHA256/SHA512, semantic versioning config
- ✅ **Dependency Verification**: 30 proyectos con `gradle.lockfile`, `verification-metadata.xml` con todos los checksums transitivos
- ✅ **fabric-host COMPILA** (Fabric 1.21 + Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents)

#### FASE 16 (2026-10-05): AUDITORIA.md Hallazgos Resueltos — Production-Ready
- ✅ **TXF-001..TXF-008**: In-flight translation leak, RejectedExecutionException fallback, DNS pinning TLS/SNI, SyncBus lifecycle, engine.parallel sequential, broadcast ACKs, VelocitySink AT_LEAST_ONCE
- ✅ **B-01..B-06**: Double delivery, join/quit/death async, WebSocket bind/auth, TemplateRenderer İ, translation re-escape, WebSocket fixed-window rate limit
- ✅ **M-01..M-11, V-01**: GTranslate all segments, in-flight dedup, MessageCodec validation, HttpTransport hardening, SSRF IPv6 ULA, InterruptedException, RateLimiter no RWLock, WebSocket port, MetricsEndpoint bind

#### FASE 17 (2026-10-12): TXF-AUD Auditoría Externa 2026-10-09 — 8 Hallazgos Resueltos
- ✅ **TXF-AUD-001**: Orden topológico dependencias corregido (Kahn: dependencias antes que consumidores)
- ✅ **TXF-AUD-002**: Fabric chat offload a executor async (evita bloqueo main thread 30s)
- ✅ **TXF-AUD-003**: Spigot shadowJar fat-jar con módulos proyecto (auto-contenido)
- ✅ **TXF-AUD-004**: TransactionLog recovery por último estado por operationId
- ✅ **TXF-AUD-005**: ModuleLifecycleAdapter no-ops en lugar de UnsupportedOperationException
- ✅ **TXF-AUD-006**: IPv6 SSRF binario (fc00::/7, fe80::/10, ::1/128, ff00::/8) + bracket URLs
- ✅ **TXF-AUD-007**: Tests PackageInstaller con fixtures ZIP reales (SHA256, duplicados, force)
- ✅ **TXF-AUD-008**: /debug/simulate endpoint para Gatling (procesa pipeline completo)

#### S6 (2026-10-12): Dependency Verification Completa
- ✅ 30 proyectos con `gradle.lockfile` (root + 29 subproyectos)
- ✅ `verification-metadata.xml` con SHA256/SHA512 para 435 dependencias
- ✅ Build pasa con `--dependency-verification=strict`

### All CI Checks Pass
```
./gradlew build test checkLocks javadoc    ✅ BUILD SUCCESSFUL
cd src/web-editor && npm run check        ✅ 29/29 passed
```

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
See [Migration Guide](wiki/12-Migration-Guide.md)

---

## Version 2.0.x (Legacy - Monolithic ChatTranslator)
*See git history for full changelog*

---

*Release Notes v2.1 - Part of TextFormatter Suite Documentation*