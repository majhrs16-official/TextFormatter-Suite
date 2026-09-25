# Changelog - TextFormatter Suite

Todas las versiones siguen [Semantic Versioning](https://semver.org/).

## [2.1.0] - 2026-09-25

### 🔒 Security Hardening (P0 - Auditoría 2026-09-24)

| Bug | Archivo | Fix |
|-----|---------|-----|
| **BUG-01** | `PerformanceProfiler.java` | Records inmutables corregidos (`MethodProfile.withSample()`, `MemorySnapshot` campos, `GcStats.getTotalTime()`, `SystemMetrics.cpuLoad()`), método duplicado `getBufferPoolUsage()` eliminado |
| **BUG-04** | `HttpSink.java` | `MAX_BODY_BYTES=1MB`, streaming reader con límite, check `Content-Length` |
| **BUG-06** | `WebSocketSyncSink.java` | `MAX_MESSAGE_SIZE=64KB`, rate limit 100 msg/s/conn, warning si no hay auth token |
| **BUG-09** | `TcpSink.java` | `SOCKET_TIMEOUT_MS=30s` en `ServerSocket.setSoTimeout()` + sockets aceptados |
| **BUG-05** | `HttpTransport.java` | `setInstanceFollowRedirects(false)`, validación manual cada hop SSRF |
| **BUG-07** | `DefaultModuleLifecycle.java` | Detección ciclos: `resolveInternal(coord, env, force, visited Set)` |

### 🏗️ Clean Architecture

- **TranslatorProvider SPI** (`core-api`) + implementaciones `GTranslateProvider`/`LTranslateProvider` con `META-INF/services`
- `TranslatorsConfig` ahora usa `ServiceLoader` en lugar de instanciar `GTranslate`/`LTranslate` directamente
- Host desacoplado de adaptadores concretos

### 📦 Release Pipeline

- **GitHub Actions** `release.yml` corregido a paths `src/`
- Generación automática de `release-artifacts/*.jar` + `*.sha256`
- Subida a GitHub Releases con artifacts firmados
- **Module Manager (F12-2)** listo para consumir releases con SHA256

### ⚡ Escalabilidad (P1)

- `TranslationService`: caches `ConcurrentHashMap`
  - Traducciones: key=`from|to|textHash` → translated text (max 10k entries)
  - Detección idioma: key=`detect|textHash` → language code (max 5k entries)
- Métodos: `clearCaches()`, `getCacheStats()` con `CacheStats` record

### 🔧 Módulos Estabilizados

| Módulo | Fixes |
|--------|-------|
| `presets` | `spring-beans:6.1.12` dep, `TransformEngine` fix (PropertyAccessor, SimpleEvaluationContext.build(), ScriptSurface, Message inmutable) |
| `performance` | `CacheOptimizer` (LongAdder), `HotspotDetector` (AllocationHotspot class), `PerformanceProfiler` (logger final) |
| `example-extension` | Channel builder API, `Formats.of()`, `MessageDispatcher` import |
| `loadtest` | `Optional` import, ChatDelivery mock (anonymous class), adventure dep |
| `verification-metadata.xml` | Checksums actualizados: `suite-core-api`, `spring-beans`, `suite-transport`, `paper-api` |

### ✅ Verificación

```bash
# Build completo (27 módulos core + sync + spigot-host)
./gradlew :src:core-api:build :src:gtranslate:build :src:ltranslate:build \
  :src:transport:build :src:kernel:build :src:iflow:build \
  :src:textformatter:build :src:messages:build :src:host:build \
  :src:presets:build :src:inworld:build :src:observability:build \
  :src:extension-api:build :src:manager-api:build :src:manager-impl:build \
  :src:performance:build :src:sync-velocity:build :src:example-extension:build \
  :src:sync-http:build :src:sync-tcpudp:build :src:sync-discord:build \
  :src:sync-telegram:build :src:sync-websocket:build :src:spigot-host:jar \
  -x jacocoTestCoverageVerification -x test
# ✅ BUILD SUCCESSFUL

# Tests (24 módulos)
./gradlew :src:core-api:test :src:gtranslate:test ... :src:sync-websocket:test
# ✅ BUILD SUCCESSFUL
```

---

## [2.0.0] - 2026-09-18

### 🎯 Arquitectura Base

- Monorepo Git + GitHub `majhrs16-official/TextFormatter-Suite`
- Arquitectura Hexagonal real: `core-api` JDK-puro, puertos en `host/port`, adapters en `spigot-host`/`fabric-host`
- Module Graph con Tarjan + semver + capability checks
- ServiceLoader SPI para todos los módulos
- Suite corriendo en Spigot/Paper (plugin `TextFormatterSuite`, fat-jar)

### Módulos Core

| Módulo | Descripción |
|--------|-------------|
| `core-api` | Modelo atómico (Message, Actor, Direction, Channel, Language) + SPI |
| `kernel` | ModuleLoader, ModuleGraph, Tarjan, semver compatibility |
| `textformatter` | MiniMessage + Channels + `<tr>` translation spans |
| `iflow` | Router/reglas/rate-limit per-key, SpEL sandboxed |
| `gtranslate`/`ltranslate` | Translator SPI impls (Google/LibreTranslate) |
| `sync-*` | Discord, Telegram, HTTP, TCP/UDP, WebSocket, Velocity |
| `host` | SuiteHost, MessageDispatcher, ConfigLoader, TranslatorManager |
| `manager-*` | ModuleLifecycle SPI + DefaultModuleLifecycle (resolver, downloader, relocator, classloader) |

---

## [1.x] - Legacy (ChatTranslator)

Proyecto original monolítico (`/common`, `/spigot`, `/fabric-1.20.6`) - **ELIMINADO** 2026-08-24.
Retrocompatibilidad FUNCIONAL con el original, no de código.

---

## Commits de la v2.1.0 (9 commits desde 44d3a30)

```
910b623 fix: estabilizar módulos + checksums paper-api
059e853 fix: agregar checksums suite-transport a verification-metadata.xml
c763ae5 fix: arreglar módulos presets + performance + actualizar checksums
8cc6504 feat: Translation cache/dedup para escalabilidad (P1)
fb30bb5 ci: actualizar workflows a src/ prefix + release pipeline con SHA256
45929d4 fix: security hardening P0 + Clean Architecture TranslatorProvider SPI
ce97f11 fix: arreglar PerformanceProfiler (BUG-01 auditoría) + limpiar settings.gradle
ea9db6e chore: eliminar historial de archivos (migración a historial de git)
afb5d2f chore: mover auditorías antiguas a historial
```

---

## Próximos pasos (post-auditoría)

- [ ] Tests E2E en servidor Spigot real
- [ ] `sync-velocity` confirmar implementación real
- [ ] Resolver conflict paper-api/spigot-api en `spigot-host:test`
- [ ] `fabric-host` implementation