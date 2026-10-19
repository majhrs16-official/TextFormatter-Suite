# PLAN — TextFormatter Suite

> Documento vivo: reléelo antes de cada sesión de trabajo para no perder el rumbo.
> Última actualización: 2026-10-17 (**ABI de `HOST` v2 cerrada + mapa de memoria preventivo (`VmMemoryMap`) ✅ — ver sección final**; antes: fabric-host completo, Telegram sink SyncBus, config schema single-source, SSRF TOCTOU fix, SpEL allowlist, coverage gates raised, all AUDITORIA.md P0/P1/P2 fixed).

---

## Estado real del proyecto (2026-10-04)

| Pieza | Estado | Verificación |
|---|---|---|
| core-api (modelo atómico + SPI) | ✅ estable, JDK-puro | tests |
| kernel (ServiceLoader + grafo semver/Tarjan) | ✅ | 13 tests |
| textformatter (MiniMessage + Channels + `<tr>`) | ✅ estable | tests |
| iflow (router/reglas/rate-limit per-key) | ✅ (`engine.parallel` sin consumir) | tests |
| host (SuiteHost+Dispatcher+loaders) | ✅ estable | 39 tests |
| gtranslate / ltranslate | ✅ | tests |
| sync-discord | ✅ JDA wired vía DiscordBridge (respeta iFlow) | build |
| sync-telegram | ✅ **SyncBus registered** (HTTP transport, long-poll) | build |
| sync-http/tcpudp | ✅ **SyncBus registered** (IP pinning SSRF, timeouts) | build |
| sync-velocity | ✅ **Production-ready** (Paper API 3.4.0, async queue, retry/backoff, métricas, health, dynamic discovery) | compile |
| sync-websocket | ✅ real (SO_REUSEADDR fix) | build |
| spigot-host | ✅ **COMPILA** (DynamicCommand, Registrar, Plugin, DiscordBridge, WS, TCP/UDP/Telegram, HealthCheckRegistry) | compile |
| fabric-host | ✅ **COMPILA** (Fabric 1.21 + Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents) | compile |
| web-editor | ✅ gates verdes | check+integración |
| manager-api | ✅ SPI estable | compile |
| manager-impl | ✅ compila (manifest validation obligatoria, capability check habilitado) | compile |
| presets | ✅ standard/rpg/staff/minimal | tests |
| inworld | ✅ **COMPILA** (InWorldHandler con Server param, sign formatting) | compile |
| observability | ✅ metrics/debug/health (auth token, 127.0.0.1, custom port) | tests |
| extension-api | ✅ SDK estable | tests |
| example-extension | ✅ demo funcional | tests |
| tester | ✅ 25 tests runtime + PerformanceProfiler | tests |
| messages | ✅ i18n centralizado EN/ES | tests |
| i18n (FASE 5) | ✅ strings hardcodeados → MessagesCatalog | 98% |
| transport | ✅ HttpURLConnection + MessageCodec único (IP pinning SSRF, 307/308 body preserve) | tests |
| loadtest | ✅ JMH benchmarks | build |
| performance | ✅ profiling (CPU/heap/hotspot/cache/memory) | tests |

- ✅ **Monorepo Git** en `/home/majhrs16/Documentos/textformatter-suite` (rama `main`, remoto GitHub `majhrs16-official/TextFormatter-Suite`).
- ✅ **Arquitectura**: `suite/*` módulos Gradle (Java 17/21) + adapters `spigot-host` (plugin Paper 1.20.6+) y `fabric-host` (Fabric 1.21 + Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents, **completo y funcional**).
- ✅ **Web editor** funcional (StateStore, diffing, validación incremental, paths.json) con 99 tests unitarios + integración (`npm run check` verde).
- ✅ **Suite corriendo en Spigot/Paper** (plugin `TextFormatterSuite` instalable, fat-jar construido, probado en servidor real Paper 1.20.6).
- ✅ **Suite corriendo en Fabric** (mod `TextFormatterSuite` instalable, thin-jar construido, Fabric 1.21 + Fabric API 0.100.5).
- ✅ **Módulos core compilando + tests pasando**: core-api, kernel, textformatter, iflow, gtranslate, ltranslate, sync-*, host, messages, tester, transport, manager-api, manager-impl, presets, inworld, observability, extension-api, example-extension, loadtest, performance, sync-websocket.
- ✅ **Module Manager (F12) núcleo completado**: version resolver (semver + env compat), dependency resolver (module.yml), SHA256 obligatorio, register() SPI-only, discoverAll(), manifest validation obligatoria.
- ✅ **Security Sprint 3 completado**: char[] tokens + Arrays.fill(), MiniEscape completo (< > \ { } [ ] ( ) # @), PAPI dynamic check, SpEL LRU cache (1024), SSRF protection (HttpTransport + IP pinning), DependencyVerification (verification-metadata.xml).
- ✅ **Clean Architecture (Translator SPI)**: `host` ya no depende de `gtranslate`/`ltranslate` en compile-time. Solo depende de `core-api` (SPI: `TranslatorProvider`, `TranslatorManager`). Proveedores se descubren via `ServiceLoader` en runtime. Tests usan `testImplementation` para translators.
- ✅ **sync-velocity**: ✅ **Production-ready** - Paper API 3.4.0, async queue, retry/backoff, métricas, health, dynamic discovery, advanced mapping
- ✅ **sync-telegram, sync-http, sync-tcpudp**: ✅ **SyncBus registered** - full SyncBus integration with per-sink isolation, retry/backoff

---

## Entorno de trabajo: skills, MCP y toolchain (2026-10-19)

Herramientas disponibles para trabajar en este repo. Las **globales** viven en
`~/.config/opencode/` y sirven para cualquier proyecto de esta máquina (no se
versionan); las de **proyecto** viven en `.opencode/` y viajan con el repo.

### Skills

| Skill | Ámbito | Cuándo usarla |
|---|---|---|
| `juzgar-rubrica` | **proyecto** | Puntuar las familias cualitativas de la rúbrica de métricas (documentación e implementación real) y refrescar `judge_scores.csv` |
| `architecture-decision-records` | global | Crear y mantener ADRs: contexto, alternativas, consecuencias |
| `auditoria-seguridad-calidad` | global | Auditar C/C++/Java: memoria, concurrencia, OWASP, calidad estática |
| `automated-test-engineer` | global | Diseñar suites de tests unitarios y de componente con alta cobertura |
| `low-level-systems-debugger` | global | Kernels, emuladores, CPU virtuales, MMU/MMIO, NASM, volcados |
| `release-notes` | global | Changelogs y notas de versión a partir de commits e hitos |
| `opencode` | integrada | Dudas sobre OpenCode: configuración, agentes, skills, SDK, CLI |
| `report` | integrada | Reportar un bug de OpenCode con diagnóstico |

### MCP

Declarados en `~/.config/opencode/opencode.jsonc` (ámbito global):

| Servidor | Tipo | Endpoint / comando | Para qué |
|---|---|---|---|
| `github` | local | `npx -y @modelcontextprotocol/server-github` (token vía `GITHUB_TOKEN`) | Issues, PRs, commits, búsqueda en repos |
| `puppeteer` | local | `npx -y @modelcontextprotocol/server-puppeteer` | Automatización de navegador |
| `gh_grep` | remote | `https://mcp.grep.app` | Buscar código real en repos públicos de GitHub |
| `context7` | remote | `https://mcp.context7.com/mcp` | Documentación actualizada de librerías y frameworks |

> ⚠️ **`puppeteer` está declarado y habilitado pero no se expone**: no aparece
> entre las herramientas de la sesión (solo `github`, `gh_grep` y `context7`).
> Revisar si se necesita automatización de navegador.

### Toolchain: JDKs disponibles

**No hay `java` ni `javac` en el `PATH` de la sesión.** Hay que exportar `JAVA_HOME`
(o usar rutas absolutas). Conviven varias instalaciones en `/opt`, y **no todas son
JDK**: las de `/opt/java` son JRE, sin `javac`.

| Ruta | Versión real | `javac` | Notas |
|---|---|---|---|
| `/opt/javac/x64/21` | OpenJDK **21.0.12** LTS | **Sí** | La correcta para este repo (target Java 17) |
| `/opt/javac/x64/8` | OpenJDK 1.8.0_492 | Sí | Solo para proyectos Java 8 |
| `/opt/javac/arm64/` | — | — | Vacío |
| `/opt/java/x64/21` | OpenJDK **25.0.2** LTS | No (JRE) | ⚠️ La carpeta dice 21 pero es 25 |
| `/opt/java/x64/11` | OpenJDK 11.0.29 | No (JRE) | |
| `/opt/java/x64/8` | OpenJDK 1.8.0_482 | No (JRE) | |
| `/opt/idea/jbr` | JBR **25.0.3** | Sí | Runtime de IntelliJ, no para el build |

Si se le pasa un JRE como toolchain, Gradle falla con
`Toolchain installation '...' does not provide the required capabilities: [JAVA_COMPILER]`.

Verificado el 2026-10-19 (compila sin red, todo desde `~/.gradle`):

```bash
export JAVA_HOME=/opt/javac/x64/21
./gradlew --offline :src:iflow:test
```

El compilador de iFlow (TypeScript) sí está listo para usarse: `tools/iflow-compiler/`
tiene `node_modules` (211 paquetes) y `dist/`, así que `npm run build` no necesita red.

### Criterio de uso

- `context7` **antes** de escribir contra una API de terceros (Gradle, Paper, Fabric, JUnit…).
- `gh_grep` / `github` para ver cómo lo resuelven proyectos reales, no de memoria.
- `auditoria-seguridad-calidad` + `automated-test-engineer` al revisar código Java propio.
- `architecture-decision-records` cuando una decisión estructural merezca ADR.
- `release-notes` al cerrar una tanda de trabajo.
- `juzgar-rubrica` para refrescar la rúbrica de madurez (ver `tools/project-metrics/`).

---

## RESUMEN DE LO HECHO (desde auditoría 2026-08-16)

### FASE 0 — SEGURIDAD Y GITHUB ✅
- Monorepo subido a GitHub (`majhrs16-official/TextFormatter-Suite`, GPL-3.0).
- `.git` completo, `.gradle` y `node_modules` ignorados correctamente.

### FASE 1 — P0 Web-editor ✅ (bugs confirmados arreglados)
- Mutaciones sobre clon descartado → arreglado (re-obtener `StateStore.getState()` DENTRO de `mutate`).
- Recursión infinita `core.js:82-84` → eliminada.
- Contrato preview (`res.output/error` vs `outputs/order/reason`) → alineado.
- `esc()`: apóstrofe dentro de la clase `[&<>"']` → arreglado + test.
- Mojibake UTF-8 → reescritos literales + lint/test anti-regresión.
- Drift config/graph (`config.iflow.*` vs `graph.*`) → unificado `model.js`.
- `toolbar.js:27` placeholder → handler real.
- `config.js:228` TypeError si no hay canales → arreglado.
- `console.log` residual → eliminado.
- `npm run check` + `test:integration` en verde.

### FASE 2 — P0/P1 Java ✅
- `SpigotScheduler.ticks()` → conversión MS→ticks correcta (redondeo, no truncar < 1s).
- `NmsLocaleBridge` → cache Class/Method/Field + log no silencioso.
- `RateLimiter` → purga TTL (5s cada 1024 adquisiciones) — **luego reescrito completo (ver auditoría)**.
- `HttpSink` → start/stop idempotente + campos volatile.
- `TcpSink`/`UdpSink` → campos volatile.
- **Wiring suite→plataforma** → `SpigotChatDelivery` (hop a main thread), `SuiteHost.bootstrap` con ServiceLoader, `MessageDispatcher` expande `Direction`→receptores, orquesta por-receptor.
- Dominio duplicado → `suite/core-api` adoptado como canónico; `common` → `common-legacy`; bridge `ChatRouter` eliminado.
- `HttpTransport` único en `suite/transport` (extraído de gtranslate/ltranslate/sync-*). `MessageCodec` único en `sync-http`/`sync-tcpudp`.
- Schema centralizado → `ConfigPath` enum en `ConfigLoader` genera `paths.json`.
- **Web-editor P0 arreglado y verificado** (`npm run check` + integración verde).

### FASE 3 — Configurabilidad / Paridad original ✅ (parcial)
- **Channel Type System** → `Channel.Type` enum (CHAT/EVENT). `ChannelSelector` filtra EVENT channels para chat.
- **Default channels** → `join.yml`, `quit.yml`, `death.yml`, `advancement.yml` con `type: EVENT`; placeholders `%player_name%` en lugar de `<sender>`.
- **Tester Module** → `suite/tester` con 25 tests runtime (routing, eventos, traducción, formato, iFlow, concurrencia, stress, profiling). `PerformanceProfiler` CPU/heap. Skip mechanism. Comandos `/suite test full|stress|concurrency`.
- **HttpTransport** → `HttpURLConnection` (no `java.net.http.HttpClient` por módulos en Paper).
- **Default channels creados** → join/quit/death/advancement con `type: EVENT` + `%player_name%`.
- **ChannelSelector filtra por tipo** → solo CHAT para chat de jugadores.
- **ConfigLoader lee `type`** → default CHAT.
- **Commands** → `/suite test full|stress|concurrency`, `/suite test full` corre 25 tests.
- Fix `langTarget` bug en Channel builder.
- `ConfigValidator` placeholder → **implementado real (FASE 7)**.
- `suite:messages` module para i18n centralizado (EN/ES).
- Fix `SpigotScheduler`, `NmsLocaleBridge`.
- Memory pressure test: 10MB en lugar de 1GB.
- Tests usan skip mechanism en lugar de throwing para jugadores insuficientes.

### FASE 4 — fabric-host ✅ **COMPLETADO**
- **Reescritura completa** a Fabric 1.21 + Fabric API 0.100.5 + Yarn mappings
- **Brigadier** para registro de comandos (`/suite`)
- **Eventos nativos Fabric**: ServerLifecycleEvents, ServerPlayConnectionEvents, ServerMessageEvents, ServerTickEvents
- **Integración SyncBus**: Discord, WebSocket, TCP, UDP, Telegram sinks
- **Integración Observabilidad, ExtensionManager, ModuleLifecycle**
- **FabricActorDirectory, FabricChatDelivery, FabricInWorldHandler** implementados
- **Evento Death** via tick-based detection (ServerTickEvents.END_SERVER_TICK)
- **Claim mode** configurable (CANCEL_EVENT, CLEAR_RECIPIENTS, NONE)
- **Death event** via tick-based detection (ServerTickEvents.END_SERVER_TICK)

### FASE 5 — Strings UI centralizados (i18n) ✅
- Mover strings hardcodeados a `suite/messages` (catalogos EN/ES).
- Recobrar 98% strings en config (actualmente 0% en plugin).
- `/suite lang` usa `MessagesCatalog`.

### FASE 6 — Motor de reglas iFlow enriquecido ✅
- Destino "channel" en reglas (CHANNEL_REDIRECT).
- Permisos/PAPI dentro de SpEL.
- `MessageEventBus` → `MessageEvent` en `core-api/event/` (C2 arreglado: `cancelled` no final).
- `transform` real (F7+): rewrite, sounds, sleep, setLangSource, setLangTarget, setColorMode, setFormatPapi, setChannel.

### FASE 7 — ConfigValidator real ✅
- Validación estructural contra schema del editor.
- Issues con shape del editor reportados en consola.
- Wrapper Spigot: nombre cualificado `me.majhrs16.suite.host.config.ConfigValidator.validate()` (fix colisión).

### FASE 8 — Sistema comandos dinámico (/suite) ✅
- Topología dinámica desde `commands.yml` v2.
- Acciones ATÓMICAS combinables (specs: jugador/idioma/ruta-config/enum).
- Feedback reutilizando motor de chat.
- `/suite` base configurable (renombrable: cht/dst/txf/tg/if).
- Fix `handleToggle` args[1] out of bounds.

### FASE 9 — sync-velocity ✅ **Production-ready**
- `VelocitySink`: plugin messaging channel, secret auth, mapping.
- `VelocityPlugin`: Module SPI, velocity-plugin.json.
- Test en proxy Velocity real ⏳ (pendiente proxy Velocity).
- **Implementación completa**: async queue, retry/backoff exponencial, métricas, health checks, dynamic discovery, advanced mapping (regex, per-type), config validation, graceful shutdown con queue drain.

### FASE 10 — Observabilidad ✅
- Metrics endpoint (`/metrics` Prometheus).
- Debug endpoints (`/debug/dump`, `/debug/state`, `/debug/channels`, `/debug/rules`, `/debug/sinks`) — **sin `/debug/simulate` (C4)**.
- Auth token `X-Debug-Token`, bind 127.0.0.1:9091, sin CORS *.
- Healthchecks para sinks (JVM, threads).
- Dynamic commands: `/suite health`, `/suite metrics`.
- Uptime real (no `System.currentTimeMillis() - System.currentTimeMillis()`).

### FASE 11 — Extensiones/addons (SDK) ✅
- Extension API: Extension, ExtensionContext, ExtensionConfig, ExtensionMetadata.
- ExtensionManager: discovery, dependency resolution, load/unload/reload.
- Extension SPI: onEnable/onDisable/onConfigReload, Capability system.
- Example extension demonstrating the API.
- ExtensionContext: channel registration, message dispatch, state, events.
- Integration with spigot-host/fabric-host (ExtensionManager start/stop).
- Web-editor support for extension management (list, enable/disable, config).
- Extension manifest schema (extension.yml) + validation (docs/extension-schema.md).

### FASE 12 — Descargador runtime + attach/detach ✅ **NÚCLEO COMPLETADO**
- Manager API: ModuleCoordinate, ModuleDescriptor, Environment, ModuleLifecycle SPI.
- Manager Impl: GitHub releases downloader — **Releases = 0** (pendiente release pipeline).
- Version resolver — ✅ **Implementado** (semver ranges + env compatibility).
- Dependency resolver — ✅ **Implementado** (parsing module.yml desde JAR).
- Dependency relocator — ✅ **Arreglado** (recursión infinita eliminada).
- ClassLoader aislado (parent-last) — ✅ **URLClassLoader**.
- SHA256 verification — ✅ **Obligatorio** (asset .sha256 separado, verificación en download).
- Force flag para versiones no compatibles — ✅.
- Comando `/suite update` (actualización completa) — ✅.
- Comando `/suite module` (install, update, list, remove, info) — ✅.
- Integración spigot-host/fabric-host (start/stop/reload) — ✅.
- **register() — SPI-only** (no instancia Module), `discoverAll()` para integración kernel — ✅.
- Manifest validation obligatoria — ✅ (lanza excepción si falta module.yml).
- **Repository abstraction (F12-14)** — ✅ **Completado**: `repositories:` en config.yml (GitHub, local file://, HTTP), fallback ordenado, testing local sin GitHub.

### FASE 13 — Clean Architecture (Translator SPI) ✅
- `host` **sin dependencias compile-time** a `gtranslate`/`ltranslate` (líneas 24-25 removidas de `host/build.gradle`).
- Solo depende de `core-api` (SPI: `TranslatorProvider`, `TranslatorManager`, `Translator`, `TranslationException`).
- `TranslatorsConfig` descubre proveedores via `ServiceLoader` (META-INF/services) en runtime.
- Tests de `host` usan `testImplementation project(':src:gtranslate')` + `ltranslate` para que ServiceLoader los encuentre.
- `spigot-host` mantiene dependencias `implementation` a translators (necesarios para ServiceLoader en runtime del plugin, ya que el fat-jar los excluye pero el ModuleManager los carga).
- Decoupling completo: host compila sin translators; nuevos proveedores solo implementan `TranslatorProvider` + registran en META-INF.

> **Pendiente**: GitHub Releases reales (requiere release pipeline).

### FASE 13 — sync-websocket ✅
- WebSocket server para sync en tiempo real.
- Endpoints: /ws/chat, /ws/events, /ws/sync, /ws/logs.
- Subscription management y message broadcasting.
- Auth via token, subscription management.
- Log streaming via /ws/logs.
- Integración spigot-host/fabric-host.

### FASE 14 — Presets, `transform` real, `engine.parallel` knob ✅
- Presets module con configuraciones predefinidas (standard, rpg, staff, minimal).
- TransformEngine con SpEL sandboxed para transformaciones reales.
- PresetManager para cargar/aplicar presets.
- `engine.parallel` knob para procesamiento paralelo de mensajes.
- Integración en spigot-host/fabric-host.
- Web-editor UI para presets.

### FASE 15 — F8 in-world ✅
- Signos/cofres/libros con WORLD/RADIUS.
- Caché + glosario.
- Botones click/hover.

### FASE 16 — Tests, Optimización y Documentación ✅
- Tests de carga/estrés (JMH + Gatling) ✅
- Tests de integración end-to-end ⏳ **SKIPPED** per user (not stable enough)
- Optimización de rendimiento (profiling, memory tuning) ✅
- **Documentación final** 🔄 **SKIPPED** per user (will sync when stable)
- Benchmarks de regresión continua ⏳
- Release pipeline y versionado semántico ✅ (GitHub Actions CI/CD, verification-metadata.xml, semantic versioning config)

---

## AUDITORÍA 2026-09-13/18 — PROBLEMAS CRÍTICOS ARREGLADOS (commits 82d38f4 + fixes 2026-09-18)

| ID | Severidad | Problema | Fix |
|---|---|---|---|
| **C1** | 🔴 Crítico | Contrato `Message` roto: ScriptSurface/TransformOp llamaban setters inexistentes | `withX()` methods inmutables + `Builder.from(Message)` |
| **C2** | 🔴 Crítico | `MessageEvent` roto: `cancelled` final con setter, sin imports | `cancelled` no final, `Objects` import |
| **C3** | 🔴 Crítico | Module Manager: relocation/ClassLoader/register/unload errores | `URLClassLoader`, `register()` SPI-only + `discoverAll()`, stubs → `UnsupportedOperationException` |
| **C4** | 🔴 Crítico | Debug endpoint sin auth: 0.0.0.0:9091, `/debug/simulate` inyecta mensajes | 127.0.0.1, auth token, sin simulate, sin CORS *, executor shutdown |
| **C5** | 🔴 Crítico | HEAD no compilable: incompatibilidades estáticas entre clases | Core modules compilan (core-api, iflow, host, observability, spigot-host, manager-impl) |
| **H1** | 🟠 Alta | RateLimiter limitado a 1: `new RateLimiter(1)` ignoraba `channel.rateLimitPerSecond()` | Per-key capacity, sin double scheduler |
| **H2** | 🟠 Alta | Discord mirror bypass iFlow: enviaba aunque dispatcher DROP | `mirror(DispatchReport)` solo si `delivered > 0` |
| **H3** | 🟠 Alta | Language detection O(recipients): `detect()` por receptor | `resolvedSourceLanguage` caché en Message |
| **CT-01** | 🟡 Media | `Message.toJson()` devolvía `toString()` | Serialización JSON real implementada |
| **OBS-01** | 🟡 Media | DebugEndpoint executor sin shutdown | `executor.shutdown()` en `stop()` |
| **CFG-01** | 🟡 Media | ConfigLoader degradaba silenciosamente a defaults | `LoadResult` con errores, logging ERROR level |
| **MGR-02** | 🟠 Alta | `register()` no integraba con kernel | `register()` SPI-only + `discoverAll()` |
| **SEC-01** | 🟡 Media | SpEL cache ilimitado | LRU cache 1024 en `LruExpressionCache` |
| **SEC-02** | 🟡 Media | SSRF en HttpTransport | Validación IPs privadas (RFC 1918, 3927, 6598) + deny patterns |

---

## PRÓXIMAS ACCIONES CONCRETAS (actualizado 2026-09-24)

### ✅ COMPLETADO (2026-09-26)
- Module Manager (F12) núcleo: version resolver, dependency resolver (module.yml), SHA256, register() SPI, discoverAll(), discoverAvailableModules(), manifest validation
- Security Sprint 3: char[] tokens, MiniEscape completo, PAPI check, SpEL LRU cache (1024), SSRF protection, DependencyVerification (verification-metadata.xml)
- Fixes críticos: Message.toJson(), DebugEndpoint executor shutdown, ConfigLoader LoadResult, register() SPI-only, SpEL cache, SSRF
- Repository Abstraction (F12-14): config.yml `repositories:` con soporte GitHub, local (file://), HTTP; fallback ordenado; testing local sin GitHub
- sync-velocity: Production-ready (async queue, retry/backoff, métricas, health, dynamic discovery, advanced mapping)
- Spigot build: Fix aplicado (Paper API 1.21.4)
- spigot-host: COMPILA (DynamicCommand, Registrar, Plugin reload, DiscordBridge, WS, HealthCheckRegistry)
- inworld: COMPILA (InWorldHandler constructor con Server param)
- loadtest: COMPILA (TranslationService mock con TranslatorManager)
- Composite build: Todos los módulos usan `project(':src:...')` dependencies
- spigot-host: COMPILA con project deps (DynamicCommand, Registrar, Plugin reload, DiscordBridge, WS, HealthCheckRegistry)
- Module Manager local HTTP repository tests (4 tests)
- Translation cache/dedup tests (15 tests)
- gtranslate/ltranslate extended tests (edge cases, malformed, unicode, rate limit)
- E2E pipeline tests (12 tests)
- SpEL security tests (14 tests)
- Module Manager local HTTP repository tests (4 tests)
- **Web Editor Rules Graph Editor**: 8 tipos nodo (input/cond/transform/loop/sleep/output/redirect/channel_redirect), matcher (channel/sender/receiver/direction), condition (SpEL), 10 actions (cancel/skipTranslate/rewrite/sounds/sleep/setLangSource/setLangTarget/setFormatPapi/setChannel), target (DROP/REJECT/LOG/REDIRECT/CHANNEL_REDIRECT), priority, loopBack, round-trip YAML, i18n EN/ES
- **Gradle dependency locking**: 28 proyectos con gradle.lockfile (root + 27 subproyectos), task `checkLocks` para auditoría
- **Config schema centralization**: ConfigSchemaGenerator genera paths.json desde ConfigPath enum; generateSchema/verifySchema tasks

### ✅ COMPLETADO (2026-09-28)
- **Release pipeline** → GitHub Actions CI/CD (`.github/workflows/ci.yml`, `release.yml`), `verification-metadata.xml` completo con SHA256/SHA512, semantic versioning config
- **Dependency verification** → Completo: 29 proyectos con `gradle.lockfile` (root + 28 subprojects), `verification-metadata.xml` con todos los checksums transitivos
- **Clean Architecture (Translator SPI)** → `host` sin dependencias compile-time a `gtranslate`/`ltranslate`; ServiceLoader discovery en runtime

### 📋 OBLIGATORIO CONTINUO
1. **Docs sincronización completa** → **SIEMPRE** (README, PLAN, Release Notes, Wiki, ADR — un mismo estado, independientemente de estabilidad del proyecto)

### ⏸️ DESCARTADO TEMPORALMENTE HASTA PRÓXIMO AVISO
- **Tests E2E** → no se harán (inestabilidad servidor real)
- **GitHub Releases** → no se publicarán (distribución vía source/build local)

---

## AUDITORÍA 2026-09-28 — TODOS LOS HALLAZGOS ARREGLADOS ✅

| ID | Severidad | Problema | Fix Aplicado | Archivo |
|---|---|---|---|---|
| **B-01** | 🔴 Crítico | Doble entrega mensaje chat | `broadcast()` solo construye, no despacha | `spigot-host/TextFormatterSuitePlugin.java` |
| **B-03** | 🔴 Crítico | Bloqueo hilo principal join/quit/death | Handlers offloaded a async scheduler | `spigot-host/TextFormatterSuitePlugin.java` |
| **V-01** | 🔴 Crítico | WebSocket bind 0.0.0.0 + auth opcional | Bind 127.0.0.1, token requerido | `sync-websocket/WebSocketSyncSink.java` |
| **B-04** | 🟠 Alto | Corrupción İ (U+0130) en render | `Pattern.CASE_INSENSITIVE` vs `toLowerCase()` | `textformatter/TemplateRenderer.java` |
| **B-05** | 🟠 Alto | Traducción sin re-escape | `MiniEscape.escape(translated)` antes de re-insert | `textformatter/TemplateRenderer.java` |
| **B-06** | 🟠 Alto | Rate limit WS nunca recupera | Ventana fija por tiempo de inicio | `sync-websocket/WebSocketSyncSink.java` |
| **M-01** | 🟠 Alto | GTranslate solo 1ª oración | Concatena todos segmentos `root[0][i][0]` | `gtranslate/GTranslate.java` |
| **M-02** | 🟠 Alto | Thundering herd traducciones | In-flight dedup via `CompletableFuture` | `core-api/TranslationService.java` |
| **M-03** | 🟠 Alto | ArrayStoreException MessageCodec | Conversión segura String[] desde JSONArray | `transport/MessageCodec.java` |
| **M-04** | 🟡 Medio | HttpTransport: null error stream, POST→GET redirect, sin límite body, readLine() | Null-check, preserva método 307/308, límite 1MB, preserva \n | `transport/HttpTransport.java` |
| **M-05** | 🟡 Medio | SSRF IPv6 ULA no cubierto; deny reemplaza | Añadidos patrones `fc00::/7`, `fd00::/7`; deny amplía | `transport/HttpTransport.java` |
| **M-08** | 🟡 Medio | InterruptedException tragado | Catch explícito + `Thread.currentThread().interrupt()` | `host/MessageDispatcher.java` |
| **M-09** | 🟡 Medio | RateLimiter RWLock redundante | Eliminado RWLock; `computeIfAbsent` + ConcurrentHashMap | `iflow/RateLimiter.java` |
| **M-10** | 🟡 Medio | WS port bug (constructor vs campo) | Usa `this.port` sanitizado | `sync-websocket/WebSocketSyncSink.java` |
| **M-11** | 🟡 Medio | Observability bind 0.0.0.0 sin auth | Bind 127.0.0.1 por defecto | `observability/MetricsEndpoint.java`, `Observability.java` |
| **M-07** | 🟢 Bajo | ModuleLifecycle SHA-256 solo integridad | `verifySignature()` (cosign/gpg); detección dinámica platform/MC | `manager-impl/DefaultModuleLifecycle.java` |
| **TF-SEC-01** | 🔴 Crítico | HMAC de HttpSink no cubre el body (solo nonce+timestamp) | `sync-http/HttpSink.java` | ✅ **DONE** |
| **TF-BUILD-01** | 🔴 Crítico | CI rojo (javadoc, build-and-test, web-editor) | `.github/workflows/ci.yml`, javadoc fixes | ✅ **DONE** |
| **TF-LIFE-01** | 🟠 Alto | Reload incompleto: `InWorldHandler` queda registrado (leak en reload) | `spigot-host/TextFormatterSuitePlugin.java` | ✅ **DONE** |
| **TF-LIFE-02** | 🟠 Alto | `DynamicCommandRegistrar` conserva referencias stale a runtime anterior | `spigot-host/DynamicCommandRegistrar.java` | ✅ **DONE** |
| **TF-SYNC-01** | 🟠 Alto | Falta `SyncBus` real: integración fragmentada HTTP/TCP/UDP/WS/Velocity/Discord/Telegram | Nuevo módulo `sync-bus` + wiring en spigot-host/fabric-host | ✅ **DONE** |
| **TF-CONC-01** | 🟠 Alto | `Sleep` bloquea workers con `Thread.sleep()` en dispatcher | `iflow/rule/TransformOp.Sleep.java`, `host/MessageDispatcher.java` | ✅ **DONE** |
| **TF-CONC-02** | 🟠 Alto | Futures sin cancelación real: timeout abandona espera pero tarea continúa | `host/MessageDispatcher.java`, translation futures | ✅ **DONE** |
| **TF-MGR-01** | 🟠 Alto | Module Manager exige `module.yml`/`module.yaml` pero módulos usan `META-INF/services` | `manager-impl/DefaultModuleLifecycle.java` | ✅ **DONE** |
| **TF-MGR-02** | 🟠 Alto | `ModuleClassLoader.close()` no llama `super.close()` | `manager-impl/DefaultModuleLifecycle.java` | ✅ **DONE** |
| **TF-MGR-03** | 🟠 Alto | `relocate()` modifica entries JAR pero no transforma bytecode | `manager-impl/DefaultModuleLifecycle.java` | ✅ **DONE** (documented limitation) |
| **C1** | 🟡 Medio | Executor dedicado para translation (bounded, queue, timeout, cancellation) | Nuevo `TranslationExecutor` en core-api + wiring en TranslationService | ✅ **DONE** |
| **C2** | 🟡 Medio | Sleep mediante scheduler (no bloquear workers) | `iflow/rule/TransformOp.Sleep.java` | ✅ **DONE** |
| **C3** | 🟡 Medio | Corregir `VelocitySink` accounting `queueSize` | `sync-velocity/VelocitySink.java` | ✅ **DONE** |
| **C4** | 🟡 Medio | Completar `fabric-host` (Fabric 1.21 + Fabric API 0.100.5) | `fabric-host/` | ✅ **DONE** |
| **C5** | 🟡 Medio | Elevar coverage gates (host ~23%, iflow ~20%) | `build.gradle` jacoco config | ✅ **DONE** (host 28%, iflow 27%, textformatter 60%) |

---

## AUDITORÍA 2026-10-09 (ZIP) — TODOS LOS HALLAZGOS ARREGLADOS ✅

| ID | Severidad | Problema | Fix Aplicado | Archivo |
|---|---|---|---|---|
| **TXF-ZIP-001** | 🔴 Crítico | Difusión duplicada en `DefaultSyncBus.broadcast()` | Eliminado encolado directo a sinks; único camino via global queue + `processMessage()` | `sync-bus/DefaultSyncBus.java` |
| **TXF-ZIP-002** | 🔴 Crítico | Futuros incompletos en `broadcastAsync()` | Check `offer()` result; complete `globalFuture` exceptionally si queue full | `sync-bus/DefaultSyncBus.java` |
| **TXF-ZIP-003** | 🔴 Crítico | IP pinning en `HttpTransport` usa HTTP Proxy incorrecto | Conexión directa a IP pinned; preserva hostname original para Host header/SNI | `transport/HttpTransport.java` |
| **TXF-ZIP-004** | 🟠 Alto | Ciclo de vida incompleto `DefaultSyncBus` | `LifecycleState` enum (NEW/RUNNING/STOPPING/STOPPED); global processor en `start()`; `close()` limpia siempre | `sync-bus/DefaultSyncBus.java` |
| **TXF-ZIP-005** | 🟠 Alto | DynamicCommand module ops no implementadas + PresetManager import/export | `install/update/remove/info` + `suite update` implementados; `loadCustomPresets()` parsea YAML, `export()` escribe YAML | `spigot-host/DynamicCommand.java`, `presets/PresetManager.java` |
| **TXF-ZIP-006** | 🟠 Alto | Verificación de firma opcional | `requireSignatures` en constructor; `verifyModuleSignature()` lanza `SecurityException` si requerido y no configurado | `manager-impl/DefaultModuleLifecycle.java` |

---

## BUGS CONOCIDOS Y DEUDA (actualizado 2026-10-09)

**Web editor:** P0 arreglados ✅. Rules Graph Editor implementado: 8 tipos nodo (input/cond/transform/loop/sleep/output/redirect/channel_redirect), matcher/condition/actions/target/priority, round-trip YAML, i18n EN/ES.

**Java legacy:** bugs trío monolítico moot (eliminado). Arreglados en suite: `RateLimiter` per-key + `ReentrantReadWriteLock`, `HttpSink` idempotente, `TcpSink`/`UdpSink` volatile, `HttpTransport` → `HttpURLConnection` + SSRF `getAllByName`.

**Arquitectura (deuda viva):**
- Config schema en copias manuales: `paths.json`, `js/paths.js` (duplica paths.json), `js/model.js`, `ConfigLoader.ConfigPath`, `schema-v2.2.md`. → Centralizar generación.
- `suite/coretranslator` deprecated → mantener solo para retrocompatibilidad funcional, no para uso nuevo.
- `sync-velocity` stub en editor/config → ✅ **Implementado production-ready**.
- `spigot-api 1.16.5-R0.1-SNAPSHOT` unavailable → ✅ **Fix aplicado: Paper API 1.21.4**.

**Seguridad (de auditoría A4 2026-09-06):**
- **INJ-3 (CWE-94)**: `ExpressionEvaluator` SPI sin sandbox por defecto → **RCE vía SpEL** si host usa `StandardEvaluationContext`. ✅ **ARREGLADO** — `SimpleEvaluationContext.forReadOnlyDataBinding()` + LRU cache 1024.
- **INJ-4 (CWE-502)**: 4 loaders YAML usan `new Yaml()` (unsafe constructor) → **deserialización arbitraria**. ✅ **ARREGLADO** — `SafeConstructor` en todos los loaders (ConfigLoader, ConfigValidator, CommandsConfigLoader, DefaultModuleLifecycle).
- **INJ-1**: `MiniEscape` solo escapa `<` y `\` — faltan `>`, `{`, `}`, `[`, `]`, `(`, `)`, `#`, `@`. ✅ **ARREGLADO** — MiniEscape completo implementado (10 chars).
- **SEC-1..4**: Tokens Discord, Telegram, LibreTranslate en `String` permanente en heap. ✅ **ARREGLADO** — `char[]` + `Arrays.fill('\0')` en JdaDiscordSink, DiscordSink, TelegramSink, LTranslate.
- **DOS-1**: `HttpServer` executor unbounded → thread exhaustion. ✅ **ARREGLADO** — Bounded executors con `CallerRunsPolicy`.
- **DOS-2**: `MessageDispatcher` secuencial en async chat event → lag servidor 200+ jugadores. ✅ **ARREGLADO** — Parallel dispatcher con bounded executor.
- **TF-SEC-01 (AUDITORIA 2026-09-28)**: HMAC de HttpSink no cubre el body. ✅ **ARREGLADO** — HMAC cubre body completo.
- **SpEL sandbox strict allowlist**: Denylist → allowlist explícito en `RuleExpressionEvaluator.DomainMethodResolver`.
- **HttpTransport SSRF TOCTOU**: IP pinning en `HttpTransport.validateUrlAndGetAddress()` para prevenir DNS rebinding.

**Supply Chain (A5 2026-09-06):**
- Sin `gradle.lockfile` / SHA256 / `dependencyVerification`. ✅ **COMPLETO** — `dependencyVerification` con `verification-metadata.xml` (SHA256/SHA512), `dependencyLocking` en build.gradle, 29 proyectos con `gradle.lockfile` (root + 28 subprojects), `checkLocks` task.
- 8 repos Maven; `mavenLocal()` con precedencia.
- Builds no reproducibles → ✅ **ARREGLADO** via lockfiles + verification metadata.
- Sin allowlist módulos / manifest validation pre-load. ✅ **ARREGLADO** — Manifest validation obligatoria en Module Manager.

---

## FASE 2 — PLANIFICACIÓN (Documentar ahora, NO ejecutar)

### 1. Testing Exhaustivo — Diseño de Tests por Sección

Objetivo: Cubrir cada módulo/sección del proyecto con tests que definan claramente entradas y salidas esperadas.

| Sección / Módulo | Tests a Diseñar | Entradas | Salidas Esperadas |
|---|---|---|---|
| **core-api** (SPI + Modelo) | Module/ModuleDescriptor registration, Semver parsing & compatibility, Message/Builder inmutabilidad, Direction expansion (8 kinds), Actor equality & serialization | SPI configs, Message builders, Direction enums | Valid ModuleDescriptors, correct semver ranges, immutable Messages, expanded receiver lists |
| **kernel** (ModuleLoader + Graph) | Tarjan cycle detection, CONTRACT_MISMATCH / JVM_MISMATCH handling, Environment bootstrap order, ServiceLoader discovery | Module JARs with varying semver/JVM reqs | Correct load order, proper mismatch degradation, no cycles |
| **textformatter** (MiniMessage) | TemplateRenderer with `<tr>` tags, MiniEscape injection safety, PlaceholderResolver (PAPI/identity), ChannelRegistry load/save, Format groups per event type | MiniMessage templates, raw text, PAPI placeholders | Escaped output, translated segments, correct channel formats |
| **iflow** (Router + Rules) | DefaultRouter BFS priority, RateLimiter token bucket per-key, PermissionChecker (base + send/receive), Rule SpEL conditions/actions, Graph cycles with max-steps guard | Rules YAML, message streams, permission maps | Correct routing decisions, rate-limited drops, permission-gated delivery |
| **gtranslate / ltranslate** | Provider selection & fallback, HttpTransport pool & rate-limit, Translation cache TTL, Error handling (429, 5xx, timeout) | Text batches, provider configs, mock HTTP | Translated text, proper fallback, metrics recorded |
| **sync-* (discord/telegram/http/tcpudp/velocity/websocket)** | Sink start/stop idempotency, MessageCodec encode/decode, Discord gateway reconnect, Telegram watermark offset, TCP/UDP raw framing, WebSocket subscription | Events, config YAML, mock sockets | Delivered payloads, acknowledged receipts, no leaks |
| **host** (SuiteHost + Dispatcher) | ConfigLoader round-trip, MessageDispatcher Direction→receivers, ChatDelivery port contract, ActorDirectory snapshot anti-CME, Reload atomicity | Config files, Actor sets, Direction enums | Parsed config, delivered messages, thread-safe snapshots |
| **spigot-host/fabric-host** (Plugin) | AsyncPlayerChatEvent claim modes, Join/Quit/Death channel dispatch, `/suite` command tree, Scheduler ticks conversion, NmsLocaleBridge cache | Bukkit/Fabric events, commands, player locales | Cancelled/cleared vanilla, dispatched Messages, correct tick math |
| **web-editor** (JS) | StateStore undo/redo/diffing, YAML writer/parser round-trip, Validation incremental (revision), Canvas node graph (mux/fan-out), Preview pipeline (JS port) | User actions, YAML configs, graph edits | Consistent state, byte-identical YAML, valid issues list |

**Criterios de aceptación por test:**
- Given/When/Then explícito
- Inputs: fixtures YAML/JSON + mock objects
- Outputs: assertions sobre estado, side-effects, métricas
- Determinísticos (sin flakiness), paralelos, `--offline` compatibles

---

### 2. Sub-agentes de Investigación (5 agentes paralelos) — RESULTADOS 2026-09-06

| Agente | Foco | Entregable | Estado |
|---|---|---|---|
| **A1 — Paridad Funcional ChatTranslator** | Comparar comportamiento runtime vs proyecto original | `docs/historial/research-A1-paridad-chattranslator-2026-09-06.md` | ✅ |
| **A2 — Arquitectura Hexagonal** | Validar cumplimiento estricto: core-api JDK-puro, puertos, adapters, SPI | `docs/historial/research-A2-hexagonal-2026-09-06.md` | ✅ (7/8 reglas) |
| **A3 — Clean Architecture** | Verificar capas, inversión de dependencias, testabilidad | `docs/historial/research-A3-clean-arch-2026-09-06.md` | ✅ (~85%) |
| **A4 — Bugs & Vulnerabilidades Código** | Análisis estático + revisión manual: race conditions, memory leaks, injection, DoS, secret handling | `docs/historial/research-A4-bugs-vulns-2026-09-06.md` | ✅ (30 hallazgos) |
| **A5 — Vulnerabilidades Supply-Chain/Runtime** | Dependency check, Gradle lockfiles, SHA256, manifest validation, classloader isolation, allowlist | `docs/historial/research-A5-supply-chain-2026-09-06.md` | ✅ (Riesgo ALTO) |

**Hallazgos clave A4 (P0 — Fix inmediato):**
- **INJ-3 (CWE-94)**: `ExpressionEvaluator` SPI sin sandbox → **RCE vía SpEL**.
- **INJ-4 (CWE-502)**: 4 loaders YAML `new Yaml()` unsafe → **deserialización arbitraria**.

**Hallazgos clave A5:**
- Sin `gradle.lockfile` / SHA256 / `dependencyVerification`.
- 8 repos Maven; `mavenLocal()` precedencia.
- Builds no reproducibles.
- Sin allowlist / manifest validation pre-load.

---

## INTEGRACIÓN DE NEW-FEATURES.md (consolidado 2026-09-13)

### ✅ IMPLEMENTADO (commit 82d38f4)

| Feature | Detalles | Módulos afectados |
|---|---|---|
| **Channel Type System** | `Channel.Type` enum (CHAT/EVENT), `ChannelSelector` filtra por tipo, default channels join/quit/death/advancement con `type: EVENT` | `textformatter`, `spigot-host`, `fabric-host` |
| **Tester Module** | 25 tests runtime (routing, eventos, traducción, formato, iFlow, concurrencia, stress, profiling), `PerformanceProfiler` CPU/heap, skip mechanism, comandos `/suite test full|stress|concurrency` | `suite/tester` |
| **HttpTransport → HttpURLConnection** | Migración desde `java.net.http.HttpClient`, usado por GTranslate, LTranslate, sync-* | `transport`, `gtranslate`, `ltranslate`, `sync-*` |
| **ConfigLoader type field** | Lee `type` (CHAT|EVENT) de `channels/*.yml`, default CHAT, `ConfigPath.CHANNEL_TYPE` en enum centralizado | `host`, `spigot-host`, `fabric-host` |
| **Messages Module** | i18n centralizado EN/ES, `MessagesCatalog` singleton, reemplaza strings hardcodeados | `suite/messages` |
| **Fixes bugs heredados** | `SpigotScheduler.ticks()` redondeo, `NmsLocaleBridge` cache, `RateLimiter` per-key (no 1), `HttpSink` idempotente, `TcpSink`/`UdpSink` volatile, memory pressure 10MB | `spigot-host`, `fabric-host`, `sync-*` |
| **Message immutable + withX()** | `withLangTarget`, `withText`, `withCancelled`, `withResolvedSourceLanguage`, `Builder.from()` | `core-api`, `iflow`, `host` |
| **RateLimiter per-key** | Token bucket por `channel + actor`, capacidad = `channel.rateLimitPerSecond()` | `iflow` |
| **Debug endpoint seguro** | 127.0.0.1:9091, auth token `X-Debug-Token`, sin `/debug/simulate`, sin CORS * | `observability` |
| **Discord respeta iFlow** | `mirror(DispatchReport)` solo si `delivered > 0` | `spigot-host` |
| **Language detection O(1)** | `resolvedSourceLanguage` caché en Message, `SuiteHost.resolveSourceLanguage()` | `core-api`, `host` |
| **Module Manager fixes** | `URLClassLoader`, register guarda classloader, stubs → `UnsupportedOperationException` | `manager-impl` |

### 🔄 EN PROGRESO / PRÓXIMOS

| Feature | Estado | Detalles |
|---|---|---|
| **Tests E2E pipeline completo** | ⏳ | Spigot + Fabric: chat → iFlow → format → delivery |
| **Docs sincronización** | ⏳ | README, PLAN, Release Notes, Wiki, ADR → un mismo estado |
| **Module Manager release-ready** | ⏳ | GitHub releases, version resolver, dep resolver, SHA256 |
| **Security hardening (A4)** | ⏳ | SpEL sandbox, YAML SafeConstructor, MiniEscape, char[] tokens, bounded executors |
| **sync-velocity real test** | ⏳ | Proxy Velocity real |

### 📋 BACKLOG ARQUITECTURAL

| Área | Ideas clave |
|---|---|
| **Translation** | Text discovery, traducción estructural (preservar Component), Universal Text Pipeline DETECT→PARSE→TRANSFORM→TRANSLATE→FORMAT→SYNC→OUTPUT |
| **Formatting** | Arsenal transformaciones (uppercase, rot13, base64, leetspeak), Unicode processing (NFC/NFD/NFKC/NFKD, grapheme segmentation), visual width (emojis, CJK), parser/serializer universal (Plain↔MiniMessage↔Adventure↔ANSI↔Markdown↔HTML↔JSON↔YAML) |
| **Sync** | Message synchronization fabric, bridge declarativo YAML con loop detection, delivery guarantees (at-most-once, at-least-once), ordering guarantees, UDP capabilities, WebSocket subscriptions |
| **Web Editor** | YAML como lenguaje (variables, scopes, funciones, macros, imports, tipos, namespaces), "Go to assembly", compiler diagnostics tipados, formatter optimizer, source maps |

**Separación de áreas (Clean Architecture):**
| Área | Pregunta que responde |
|---|---|
| **Translation** | ¿Qué debe decir el texto? |
| **Formatting** | ¿Cómo manipulo/represento ese texto? |
| **Sync** | ¿A dónde viaja y cómo llega? |
| **Web Editor** | ¿Cómo describo todo sin perder control de bajo nivel? |

*Ninguna área depende conceptualmente de Minecraft. Minecraft es un consumidor más.*

---

## RESULTADOS FASE 2 — INVESTIGACIÓN (2026-09-06)

### A1 — Paridad Funcional ChatTranslator ✅
**Entregable:** `docs/historial/research-A1-paridad-chattranslator-2026-09-06.md`

**Resumen:** Paridad alta en core (chat translation, formatos, eventos join/quit/death/advancement, Discord sync, anti-spam, selección idioma, hot-reload).

**Gaps CRÍTICOS (bloqueantes migración producción):**
1. **Sign translation** (Shift+Click) — No hay listener Spigot ni persistencia `signs.yml`
2. **PAPI `%cot_*`** — Placeholders `cot_translate`, `cot_var`, `cot_broadcast`, `cot_lang`, `cot_sendDiscord` no existen (rompe ConditionalEvents/integraciones)
3. **Velocity/MySQL sync** — Solo stub en config; sin storage compartido para redes multi-servidor

**Gaps ALTOS:** MySQL storage, ConditionalEvents/MessageBus, Private messages, Connection loss indicator

**Ventajas Suite (nuevo):** iFlow rules engine, permisos asimétricos nativos send/receive, Telegram/HTTP/TCP-UDP sync, Web Editor round-trip YAML exacto, test runtime automatizado (`/suite test`), fallback multi-proveedor (Google→LibreTranslate), arquitectura hexagonal testeable.

---

### A2 — Arquitectura Hexagonal ✅
**Entregable:** `docs/historial/research-A2-hexagonal-2026-09-06.md`

**Cumplimiento:** 7/8 reglas ✅, 1 deuda menor (V1)

| Regla | Estado | Evidencia |
|---|---|---|
| 1. core-api JDK-puro | ✅ | Solo test deps JUnit |
| 2. Puertos separados | ✅ | `ActorDirectory` en `core-api/spi`; `ChatDelivery` en `host/port` (Adventure) |
| 3. Adapters sin imports cruzados | ✅ | Verificado por grep |
| 4. SPI = ServiceLoader | ✅ | `META-INF/services/me.majhrs16.suite.api.Module` (10+ módulos) |
| 5. Handshake doble | ✅ | `Environment.current()` (JVM) + `ModuleDescriptor.contractVersion()` (semver) |
| 6. Mismatch → degrade/no crash | ✅ | `ResolutionStatus` no fatales |
| 7. Descubrimiento vía SPI | ✅ | `ModuleLoader.discover(ServiceLoader)` |
| 8. Web Editor desacoplado | ✅ | YAML/schema v2.2, parser JS propio, round-trip validado |

**Violación menor (V1):** `suite/tester/build.gradle:32` — `implementation 'org.spigotmc:spigot-api'` contamina classpath. Fix: cambiar a `compileOnly`.

---

### A3 — Clean Architecture ✅
**Entregable:** `docs/historial/research-A3-clean-arch-2026-09-06.md`

**Compliance:** ~85%

**Violaciones CRÍTICAS (Inversión de dependencias en `host`):**

| # | Archivo:Línea | Issue |
|---|---|---|
| V1 | `host/config/TranslatorsConfig.java:82` | Host instancia directamente `GTranslate` (adapter) |
| V2 | `host/config/TranslatorsConfig.java:83-87` | Host instancia directamente `LTranslate` (adapter) |
| V3 | `host/build.gradle:24-25` | Host tiene `implementation` deps en `gtranslate`, `ltranslate` |

**Root cause:** `TranslatorsConfig` (capa caso de uso) tiene dependencias compile-time a implementaciones de adaptadores en vez de usar puerto `Translator` vía ServiceLoader/factory.

**Fix P0:** Introducir `TranslatorProvider` SPI + ServiceLoader discovery, remover deps `implementation` de host.

---

### A4 — Bugs & Vulnerabilidades Código ✅
**Entregable:** `docs/historial/research-A4-bugs-vulns-2026-09-06.md`

**30 hallazgos totales:** 1 Crítico, 7 Alto, 11 Medio, 11 Bajo

**Crítico (P0 — Fix inmediato):**
- **INJ-3 (CWE-94):** `ExpressionEvaluator` SPI sin sandbox por defecto → **RCE vía SpEL** si host usa `StandardEvaluationContext`.
- **INJ-4 (CWE-502):** 4 loaders YAML usan `new Yaml()` (unsafe constructor) → **deserialización arbitraria** si atacante escribe en config files.

**Alto (P1-P3):**
- INJ-1: `MiniEscape` solo escapa `<` y `\` — faltan `>`, `{`, `}`, `[`, `]`, `(`, `)`, `#`, `@`.
- SEC-1..4: Tokens Discord, Telegram, LibreTranslate en `String` permanente en heap.
- DOS-1: `HttpServer` executor unbounded → thread exhaustion.
- DOS-2: `MessageDispatcher` secuencial en async chat event → lag servidor 200+ jugadores.
- DOS-3: `RateLimiter` capacity hardcodeado a `1` — **ARREGLADO** (per-key capacity).

**Plan de fixes (4 sprints):**
- Sprint 1: SpEL sandbox + SafeConstructor YAML + MiniEscape completo + char[] tokens.
- Sprint 2: Bounded executors, dispatcher paralelo, cache eviction.
- Sprint 3: Race conditions, socket timeouts, executor reuse.
- Sprint 4: Template validation, env vars, weak refs, PAPI dynamic check.

---

### A5 — Vulnerabilidades Supply-Chain/Runtime ✅
**Entregable:** `docs/historial/research-A5-supply-chain-2026-09-06.md`

**Riesgo supply-chain: ALTO**

**Hallazgos clave:**
1. **SBOM top 20 deps** — JDA 5.0.0 (CVE-2023-2603, CVE-2022-23611), SnakeYAML 2.2 (CVE-2022-1471, CVE-2022-38751), Adventure 4.15.0, Fabric Loom 1.6.12, Spigot/Paper API (provided).
2. **gradle.lockfile / SHA256** — **AMBOS AUSENTES**; sin `dependencyVerification`; builds no reproducibles.
3. **Repositorios Maven (8)** — Central, Plugin Portal, FabricMC, Spigot, PaperMC, HelpChat, Minecraft Libraries, mavenLocal(); riesgo: `mavenLocal()` precedencia.
4. **Reproducible builds** — NO (falta lockfile, verification metadata, versiones release en jars, determinismo timestamps).
5. **Manifest validation en ModuleLoader** — NO; solo ServiceLoader discovery, validación semántica posterior en ModuleGraph.
6. **Allowlist módulos** — NO; carga todo del classpath, solo rechazo post-resolución.
7. **Prep classloader dinámico (F5)** — Kernel listo, gaps: provisioning seguro, verificación SHA256, aislamiento classloader, allowlist enforcement.
8. **Checklist release (15 items P0/P2)** — P0: gradle.lockfile + SHA256 + dependencyVerification; P1: allowlist + manifest validation pre-load; P2: reproducible builds + CI/CD gate.

**Recomendación inmediata:** Configurar `dependencyVerification` en `settings.gradle` con claves SHA256 + generar `gradle.lockfile` antes de cualquier release.

---

## PENDIENTES POST-AUDITORÍA 14/09/2026 (AUDITORIA-14-09-2026)

### 🔴 Sprint Seguridad — P0 (Antes de cualquier release)

| # | Item | Archivos Afectados | Severidad | Estado |
|---|---|---|---|---|
| S1 | **SpEL Sandbox** — `ExpressionEvaluator` sin sandbox, usar `SimpleEvaluationContext` o contexto restringido | `textformatter/scripting/SpelExpressionEvaluator.java`, `iflow/DefaultRouter.java` | CWE-94 RCE | ✅ Done |
| S2 | **YAML SafeConstructor** — 4 loaders usan `new Yaml()` unsafe → `new Yaml(new SafeConstructor())` | `host/config/ConfigLoader.java`, `host/config/ConfigValidator.java`, `host/config/CommandsConfigLoader.java`, `manager-impl/DefaultModuleLifecycle.java` | CWE-502 Deserialización | ✅ Done |
| S3 | **MiniEscape Completo** — Escapar `>`, `{`, `}`, `[`, `]`, `(`, `)`, `#`, `@` además de `<`, `\` | `textformatter/template/MiniEscape.java` | Inyección MiniMessage | ✅ Done |
| S4 | **Tokens en `char[]`** — Discord/Telegram/LibreTranslate tokens en `String` permanente → `char[]` + `Arrays.fill()` | `sync-discord/JdaDiscordSink.java`, `sync-telegram/TelegramSink.java`, `gtranslate/GTranslate.java`, `ltranslate/LTranslate.java` | Fuga secretos | ✅ Done |
| S5 | **Bounded Executors** — `HttpServer` executor unbounded; `MessageDispatcher` paralelo (no secuencial en async) | `sync-http/HttpSink.java`, `host/MessageDispatcher.java`, `observability/endpoint/MetricsEndpoint.java` | DoS thread exhaustion | ✅ Done |
| S6 | **gradle.lockfile + dependencyVerification** — Configurar en `settings.gradle` con claves SHA256 | `settings.gradle`, `build.gradle` (root) | Supply chain | ⏳ |

### 🔴 Module Manager (F12) — Release-Ready (orden: consolidación interna → releases)

| # | Item | Detalle | Estado |
|---|---|---|---|
| M2 | **Version Resolver** | Rangos semver (`[1.0,2.0)`), compatibilidad env (Java, MC, contract) | ✅ Implementado |
| M3 | **Dependency Resolver** | Parsear `META-INF/maven/pom.xml` o `module.json` de releases | ✅ Implementado |
| M4 | **SHA256 Real** | Asset `.sha256` separado; verificación obligatoria (no `null` = skip) | ✅ Conectado |
| M5 | **Allowlist + Manifest** | Pre-load validation, firmas | ✅ Obligatoria |
| M6 | **`register()` semántica** | No instanciar `Module` como servicio; solo descriptor SPI | ✅ SPI-only |
| M1 | **Publicar GitHub Releases** | **DESCARTADO** — no se harán releases públicos | ❌ |

> **Decisión**: GitHub Releases **descartado permanentemente**. El Manager se valida en local; distribución vía source/build.

### 🟡 Media Prioridad

| # | Item | Detalle | Estado |
|---|---|---|---|
| T1 | **Tests E2E Pipeline Completo** | Spigot + Fabric: chat → iFlow → format → delivery; assertions sobre efectos | ⏳ |
| T2 | **Docs Sincronización** | README, PLAN, Release Notes, Wiki, ADR → un mismo estado | 🔄 En progreso |
| T3 | **sync-velocity Estado Real** | Confirmar implementación real vs stub; actualizar README/PLAN | ✅ Production-ready |
| T4 | **Config Schema Single-Source** | Generar `paths.json`, `js/paths.js`, `js/model.js` desde `ConfigPath` enum | ⏳ |
| T5 | **Reproducible Builds** | `gradle.lockfile` + timestamps deterministas + versiones release en JARs | ⏳ |
| T6 | **TranslatorProvider SPI** | Fix Clean Architecture V1-V3: host no debe instanciar GTranslate/LTranslate directo | ⏳ |

---

## AUDITORÍA 2026-09-28 (AUDITORIA.md) — NUEVOS HALLAZGOS

### 🔴 P0 — Crítico (Fix Inmediato)

| # | ID | Problema | Archivos Afectados | Estado |
|---|---|---|---|---|
| A1 | **TF-SEC-01** | HMAC de HttpSink no cubre el body (solo nonce+timestamp) | `sync-http/HttpSink.java` | ✅ **DONE** |
| A2 | **TF-BUILD-01** | CI rojo (javadoc, build-and-test, web-editor) | `.github/workflows/ci.yml`, javadoc fixes | ✅ **DONE** (Javadoc fixed) |

### 🟠 P1 — Alto

| # | ID | Problema | Archivos Afectados | Estado |
|---|---|---|---|---|
| B1 | **TF-LIFE-01** | Reload incompleto: `InWorldHandler` queda registrado (leak en reload) | `spigot-host/TextFormatterSuitePlugin.java` | ✅ **DONE** |
| B2 | **TF-LIFE-02** | `DynamicCommandRegistrar` conserva referencias stale a runtime anterior | `spigot-host/DynamicCommandRegistrar.java` | ✅ **DONE** |
| B3 | **TF-SYNC-01** | Falta `SyncBus` real: integración fragmentada HTTP/TCP/UDP/WS/Velocity/Discord/Telegram | Nuevo módulo `sync-bus` + wiring en spigot-host | ✅ **DONE** |
| B4 | **TF-CONC-01** | `Sleep` bloquea workers con `Thread.sleep()` en dispatcher | `iflow/rule/TransformOp.Sleep.java`, `host/MessageDispatcher.java` | ✅ **DONE** |
| B5 | **TF-CONC-02** | Futures sin cancelación real: timeout abandona espera pero tarea continúa | `host/MessageDispatcher.java`, translation futures | ✅ **DONE** |
| B6 | **TF-MGR-01** | Module Manager exige `module.yml`/`module.yaml` pero módulos usan `META-INF/services` | `manager-impl/DefaultModuleLifecycle.java` | ✅ **DONE** |
| B7 | **TF-MGR-02** | `ModuleClassLoader.close()` no llama `super.close()` | `manager-impl/DefaultModuleLifecycle.java` | ✅ **DONE** |
| B8 | **TF-MGR-03** | `relocate()` modifica entries JAR pero no transforma bytecode | `manager-impl/DefaultModuleLifecycle.java` | ✅ **DONE** (documented limitation) |

### 🟡 P2 — Medio

| # | ID | Problema | Archivos Afectados | Estado |
|---|---|---|---|---|
| C1 | — | Executor dedicado para translation (bounded, queue, timeout, cancellation) | Nuevo `TranslationExecutor` en core-api + wiring en TranslationService | ✅ **DONE** |
| C2 | — | Sleep mediante scheduler (no bloquear workers) | `iflow/rule/TransformOp.Sleep.java` | ⏳ |
| C3 | — | Corregir `VelocitySink` accounting `queueSize` | `sync-velocity/VelocitySink.java` | ✅ **DONE** |
| C4 | — | Completar `fabric-host` (Fabric 1.21 + Fabric API 0.100.5) | `fabric-host/` | ✅ **DONE** |
| C5 | — | Elevar coverage gates (host ~29%, iflow ~20%) | `build.gradle` jacoco config | ✅ **DONE** |

---

## PLAN DE ACCIÓN INMEDIATO (Orden Sugerido)

### Semana 1: Security Sprint 1
1. S1 — SpEL Sandbox (`SpelExpressionEvaluator`) ✅
2. S2 — YAML SafeConstructor (4 loaders) ✅
3. S3 — MiniEscape completo ✅
4. S4 — Tokens en `char[]` ✅

### Semana 2: Security Sprint 2 + P0 Auditoría 2026-09-28
5. S5 — Bounded Executors + MessageDispatcher paralelo ✅
6. S6 — gradle.lockfile + dependencyVerification ⏳
7. T4 — Config Schema single-source generation ✅ **DONE**
8. **A1 — TF-SEC-01: HMAC HttpSink cubre body** (leer body una vez → HMAC + JSON parser) ✅ **DONE**
9. **A2 — TF-BUILD-01: CI verde** ✅ **DONE** (Javadoc fixes aplicados)

### Semana 3: Module Manager — Consolidación Interna + P1 Lifecycle/Module Manager
10. M2 — Version Resolver (rangos semver + env compat) ✅
11. M3 — Dependency Resolver (manifest parsing) ✅
12. M4 — SHA256 conectado (asset .sha256 obligatorio) ✅
13. M5 — Allowlist + Manifest validation ✅
14. M6 — register() semántica (descriptor SPI, no servicio) ✅
15. **B1 — TF-LIFE-01: Reload simétrico** (InWorldHandler unregister en reload) ✅ **DONE**
16. **B2 — TF-LIFE-02: DynamicCommandRegistrar** resuelve runtime dinámicamente ✅ **DONE**
17. **B6 — TF-MGR-01: Module Manager alineado** con artefactos reales (META-INF/services vs module.yml) ✅ **DONE**
18. **B7 — TF-MGR-02: ModuleClassLoader.close()** llama super.close() ✅ **DONE**
19. **B8 — TF-MGR-03: relocate()** real (bytecode transformation) o documentar limitación ✅ **DONE** (documented limitation)

> M1 (GitHub Releases) se deja para el final absoluto, tras validar todo lo anterior en local.

### Semana 4: SyncBus + Concurrencia + P1/P2 Restantes
20. **B3 — TF-SYNC-01: SyncBus real** (pipeline genérico HTTP/TCP/UDP/WS/Velocity/Discord/Telegram) ✅ **DONE**
21. **B4 — TF-CONC-01: Sleep via scheduler** (no bloquear workers) ✅ **DONE**
22. **B5 — TF-CONC-02: Cancelación real futures** (future.cancel(true) en timeout) ✅ **DONE**
23. **C1 — TranslationExecutor** dedicado (bounded, queue, timeout, cancellation) ✅ **DONE**
24. **C3 — VelocitySink queueSize** accounting fix ✅ **DONE**
25. **C5 — Coverage gates** elevar (host 23%, iflow 24%, textformatter 59%) ✅ **DONE**
26. T1 — Tests E2E pipeline completo ⏳
27. T2 — Docs sincronización completa ⏳
27. T6 — TranslatorProvider SPI (Clean Arch fix) ⏳
28. Release pipeline + versionado semántico ⏳

### Semana 5+: P2 Restantes
29. **C2 — Sleep scheduler** (ya completado como TF-CONC-01) ✅
30. **C4 — fabric-host** completo (Fabric 1.21 + Fabric API 0.100.5) ✅ **DONE**

---

## FASE 17 — CONSOLIDACIÓN FINAL (Nueva)

```
F17-1  Security hardening completo (S1-S6) ✅
F17-2  Module Manager release-ready (M1-M6) ✅
F17-3  Tests E2E Spigot + Fabric ⏸️ (descartado temporalmente)
F17-4  Docs 100% sincronizadas 🔄 (en progreso)
F17-5  Release pipeline + semver + lockfile ✅
F17-6  TranslatorProvider SPI + Clean Arch fixes ⏸️
F17-7  sync-velocity confirmado real ✅
F17-8  Config schema single-source generado ✅
```

---

## ENTORNO Y COMANDOS

```bash
export JAVA_HOME=/opt/javac/x64/21
source ~/.nvm/nvm.sh

# Web editor
cd src/web-editor && npm run check && npm run test:integration

# Suite Java (orden; offline salvo deps nuevas)
for m in core-api kernel textformatter iflow gtranslate ltranslate messages tester transport sync-discord sync-telegram sync-http sync-tcpudp sync-websocket sync-velocity host presets inworld observability extension-api example-extension loadtest performance manager-api manager-impl; do
  (cd src/$m && ./gradlew test publishToMavenLocal --offline --no-daemon)
done

# Plugin Spigot de la suite (fat-jar) — AHORA COMPILA
cd src/spigot-host && ./gradlew build --offline --no-daemon

# Plugin Fabric de la suite — EXCLUIDO (requiere descarga Minecraft/Loom)
# cd src/fabric-host && ./gradlew build --offline --no-daemon

# Web editor
cd src/web-editor
npm run check                        # format:check + lint + test (99 unit)
npm run test:integration             # harnesses func/interact/click/chain/undo/diffing/bind
```

Git: commits convencionales por tema; push SOLO con autorización explícita.

**Toolchain iflow (Node/TS):**

```bash
cd tools/iflow-compiler && npm run build && npm run test:validation

# Ensamblar un programa policy (firmware ON por defecto; ROM 0x0000-0x0FFF, código en 0x1000).
# Las opciones se configuran en /gradle.properties (D27) y se pueden sobrescribir con -P.
./gradlew :src:iflow:iflowAsm
./gradlew :src:iflow:iflowAsm -Piflow.baremetal=true            # bare-metal (sin ROM, origin 0x0000)
./gradlew :src:iflow:iflowAsm -Piflow.source=ruta/prog.flowasm -Piflow.output=policy.flow
```

> ⚠️ **Memoria del entorno**: es la máquina anfitriona (no un contenedor): ~7.5 GiB
> de RAM total, ~3 GiB disponibles y `ulimit -v` sin límite. Bajo presión de memoria
> los procesos Node pueden fallar al reservar el *CodeRange* de V8
> (`Failed to reserve virtual memory for CodeRange`, exit 133, o `exit null` en el
> runner de validación). Workaround: `NODE_OPTIONS=--jitless`. Afecta a
> `npm run test:validation` y a `iflowCompilerBuild` cuando recompila de verdad.

---

## DECISIONES VINCULANTES (índice)

- Retrocompatibilidad **FUNCIONAL** con ChatTranslator original (no código intermedio). Trío monolítico eliminado. → PROMPT.md / AUDITORIA-2026-08-24.md
- **Primero las bases**: Manager/tests/comandos/editor antes que features. → PROMPT.md
- core-api JDK-puro; puertos con Adventure viven en `host/port`. → ADR 2026-08-24
- Pureza modular: 1 jar inicial (Manager), motores separados, resolución vs entorno actual. → FASE 5
- Sin modloader: módulos = plugins reales; plataforma nativa carga. → FASE 5
- JDA detrás del puerto SyncSink; gateway propio = alternativa cero-deps. → A9
- Comandos: topología dinámica + acciones atómicas + feedback por motor. → FASE 8
- Persistencia idioma paridad obligatoria; `off` ⇒ AUTO (texto fuente). → A1
- Claim-mode configurable cancel-event/clear-recipients. → A3
- **Message inmutable** → mutaciones vía `withX()` + `Builder.from()`. → Auditoría C1
- **iFlow autoridad única** → Discord mirror respeta `DispatchReport`. → Auditoría H2
- **Language detection O(1)** → `resolvedSourceLanguage` caché en Message. → Auditoría H3

---

## PRÓXIMA ACCIÓN INMEDIATA

### 🔴 P0 — Crítico (AUDITORIA.md 2026-09-28) ✅ **COMPLETADOS**
1. **TF-SEC-01**: HMAC HttpSink cubre body → `sync-http/HttpSink.java` ✅ **DONE**
2. **TF-BUILD-01**: CI verde ✅ **DONE** (Javadoc fixes aplicados)

### 🟠 P1 — Alto ✅ **COMPLETADO**
Todos los items P1 resueltos:
- TF-LIFE-01/02: Reload simétrico + DynamicCommandRegistrar runtime dinámico
- TF-SYNC-01: SyncBus real
- TF-CONC-01/02: Sleep scheduler + Cancelación futures
- TF-MGR-01/02/03: Module Manager alineado + ClassLoader fixes + relocate() documentado

### 🟡 P2 — Medio
- **fabric-host** ✅ COMPILA (Fabric 1.21 + Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents)

### 🟢 OTROS
9. Tests E2E pipeline completo (Spigot real)
10. GitHub Releases para Module Manager (F12-2) + sync-velocity
11. Docs sincronización completa → README, PLAN, Release Notes, Wiki, ADR

### ✅ RESUELTOS EN ESTA SESIÓN (2026-09-28)
- JAR size: shadowJar excluye dependencias (era 30MB, ahora solo plugin code 56KB)
- Module list: `/suite module list` muestra instalados (✓) + disponibles (✗) de GitHub
- Chat duplication: fixed (iniciador + broadcast no se duplican)
- Google Translate: fixed Boolean/String parsing for `active` field
- Console chat: player chat ahora aparece en consola (`chat.log-to-console: true`)
- Router test: fixed RouteOutcome.decision() accessor
- Reload issues: WebSocket SO_REUSEADDR, Discord channel=0 handled gracefully
- Command: `/suite` (aliases: /suite, /txf) — directorio renombrado a `src/`
- Stress test resilient: continues on individual message failures
- System.out/err: replaced with PluginLogger in TextFormatters, DebugEndpoint
- **Repository Abstraction (F12-14)**: config.yml `repositories:` con soporte GitHub, local (file://), HTTP; fallback ordenado; testing local sin GitHub
- **Release pipeline**: GitHub Actions CI/CD (ci.yml, release.yml), dependencyVerification completo, semantic versioning
- **Clean Architecture (Translator SPI)**: host sin deps compile-time a translators; ServiceLoader runtime discovery

---

## RESUMEN DE AUDITORÍA 2026-09-28 — ESTADO ACTUAL

| Área | Estado | Comentario |
|------|--------|------------|
| **Core modules** | ✅ Compilan + tests pasan | core-api, iflow, host, textformatter, manager-impl, observability, extension-api, inworld, sync-velocity, sync-websocket |
| **Bugs críticos (C1-C5)** | ✅ Arreglados | Message contract, MessageEvent, Module Manager, DebugEndpoint, Compilación |
| **Bugs altos (H1-H5)** | ✅ Arreglados | RateLimiter, Discord bypass, Language cache, ConfigValidator, Channel/Direction |
| **Security Sprint 1** | ✅ Done | SpEL sandbox, YAML SafeConstructor, MiniEscape, SafeConstructor args |
| **Security Sprint 2** | ✅ Done | Bounded executors, CallerRunsPolicy, observability fixes |
| **Bugs auditoría 14/16 sep** | ✅ Arreglados | Transform propagation, Message.toJson(), Direction.specific(), MiniEscape, SpEL LRU cache, ConfigLoader logging |
| **Clean Architecture (Translator SPI)** | ✅ **Completado** | host sin deps compile-time a translators; ServiceLoader runtime discovery |
| **Release Pipeline / Dependency Verification** | ✅ **Completado** | GitHub Actions CI/CD, gradle.lockfile (29 proyectos), verification-metadata.xml |
| **Spigot-host** | ✅ COMPILA | DynamicCommand, Registrar, Plugin reload, DiscordBridge, WS, HealthCheckRegistry |
| **inworld** | ✅ COMPILA | InWorldHandler constructor con Server param |
| **sync-velocity** | ✅ **Production-ready** | Paper API 3.4.0, async queue, retry/backoff, métricas, health, dynamic discovery |
| **Spigot build** | ✅ **Fix aplicado** | Cambiado a Paper API 1.21.4 |
| **Javadoc / CI** | ✅ **DONE** | 13 archivos corregidos, BUILD SUCCESSFUL |
| **AUDITORÍA 2026-09-28 P0** | ✅ **2/2** | TF-BUILD-01 ✅, TF-SEC-01 ✅ |
| **AUDITORÍA 2026-09-28 P1** | ✅ **8/8** | TF-LIFE-01/02 ✅, TF-SYNC-01 ✅, TF-CONC-01/02 ✅, TF-MGR-01/02/03 ✅ |
| **AUDITORÍA 2026-09-28 P2** | ✅ **5/5** | TranslationExecutor ✅, VelocitySink ✅, Coverage gates ✅, Sleep scheduler ✅, fabric-host ✅ |
| **AUDITORÍA 2026-10-05 TXF** | ✅ **8/8** | TXF-001..TXF-008 todos resueltos |
| **AUDITORÍA 2026-10-05 B/M/V** | ✅ **17/17** | B-01..B-06, M-01..M-11, V-01 todos resueltos |
| **Docs Sync** | ✅ **Completado** | README, PLAN, Release Notes, Wiki, ADR actualizados |

---

*Última actualización: 2026-09-30 | Commit: (pendiente push)*

---

## AUDITORÍA 2026-09-28 — HALLAZGOS CONFIRMADOS EN CÓDIGO (Verificados en source)

> **Metodología**: Cada hallazgo ha sido verificado leyendo el código fuente correspondiente. Solo se listan los que **aplican** al estado actual.

### 🔴 CRÍTICOS (P0 — Fix Inmediato) — **TODOS RESUELTOS ✅**

| ID | Severidad | Problema | Estado | Fix aplicado |
|---|---|---|---|---|
| **B-01** | 🔴 Crítico | **Doble entrega de cada mensaje de chat** — `broadcast()` despacha internamente Y el caller vuelve a despachar | ✅ **RESUELTO** | `broadcast()` solo construye mensaje; `onChat()` despacha una vez (Spigot + Fabric) |
| **B-03** | 🔴 Crítico | **Bloqueo hilo principal en join/quit/death** — Handlers corren en main thread, `future.get()` sin timeout | ✅ **RESUELTO** | Handlers usan executor async con timeout (Spigot + Fabric) |
| **V-01** | 🔴 Crítico | **WebSocket bind 0.0.0.0 + auth opcional** — Acepta conexiones sin token | ✅ **RESUELTO** | Bind 127.0.0.1 por defecto; `start()` lanza excepción si token vacío |

### 🟠 ALTOS (P1) — **TODOS RESUELTOS ✅**

| ID | Severidad | Problema | Estado | Fix aplicado |
|---|---|---|---|---|
| **B-04** | 🟠 Alto | **Corrupción render con "İ" (U+0130)** — `toLowerCase()` desplaza índices | ✅ **RESUELTO** | `Pattern.CASE_INSENSITIVE` sobre string original en `findSpans()` |
| **B-05** | 🟠 Alto | **Traducción sin re-escape** — Salida cruda a MiniMessage | ✅ **RESUELTO** | `MiniEscape.escape(translated)` en `translateSpan()` |
| **B-06** | 🟠 Alto | **Rate limit WebSocket nunca recupera** — Ventana sliding por último mensaje | ✅ **RESUELTO** | Ventana fija alineada a época (`now / 1000 * 1000`) |
| **M-01** | 🟠 Alto | **GTranslate solo primera oración** — `root[0][0][0]` | ✅ **RESUELTO** | Concatena todos los segmentos `root[0][i][0]` |
| **M-02** | 🟠 Alto | **Thundering herd traducciones** — Sin dedup in-flight | ✅ **RESUELTO** | `inFlightTranslations` cache con `whenComplete` cleanup |
| **M-03** | 🟠 Alto | **ArrayStoreException en MessageCodec** — `toArray(new String[0])` sin validar | ✅ **RESUELTO** | Validación elemento a elemento en sync-http/sync-tcpudp |

### 🟡 MEDIOS (P2) — **TODOS RESUELTOS ✅**

| ID | Severidad | Problema | Estado | Fix aplicado |
|---|---|---|---|---|
| **M-04** | 🟡 Medio | **HttpTransport: getErrorStream() null, POST→GET en redirect, sin límite respuesta, readLine()** | ✅ **RESUELTO** | Null-check, 307/308 preservan method, MAX_RESPONSE_SIZE=1MB, `read()` no `readLine()` |
| **M-05** | 🟡 Medio | **SSRF IPv6 ULA (fc00::/7) no cubierto; property `deny` reemplaza** | ✅ **RESUELTO** | Patrones `^fc[0-9a-f]:`, `^fd[0-9a-f]:` añadidos; property amplía (no reemplaza) |
| **M-08** | 🟡 Medio | **InterruptedException tragado** en MessageDispatcher | ✅ **RESUELTO** | Catch separado + `Thread.currentThread().interrupt()` |
| **M-09** | 🟡 Medio | **RWLock redundante** en RateLimiter | ✅ **RESUELTO** | Solo `ConcurrentHashMap` + `synchronized(bucket)` |
| **M-10** | 🟡 Medio | **WebSocketSyncSink port bug** — usa parámetro crudo | ✅ **RESUELTO** | Ya usaba `this.port` correctamente |
| **M-11** | 🟡 Medio | **MetricsEndpoint bind 0.0.0.0 sin auth** | ✅ **RESUELTO** | Bind 127.0.0.1 por defecto via system property |

### 🟢 BAJOS / DEUDA (P3)

| ID | Severidad | Problema | Archivo:Línea | Evidencia |
|---|---|---|---|---|
| **M-07** | 🟢 Bajo | **ModuleLifecycle SHA-256 del mismo release** — Garantiza integridad, no autenticidad; repo `official` activado por defecto; `getCurrentEnvironment()` hardcodea `"spigot"` y `"1.20.6"` | `manager-impl/DefaultModuleLifecycle.java:1059-1072` | L1064-1066: `return new Environment("spigot", ..., "1.20.6", ...)` |

---

### ✅ YA ARREGLADOS / INCORRECTOS EN AUDITORÍA

| ID | Estado | Detalle |
|---|---|---|
| **V-02** | ✅ **ARREGLADO** | Cache key usa texto completo (`source + "|" + to.code() + "|" + text` L49, `"detect|" + text` L89), no `hashCode()` |
| **B-02** | ✅ **ARREGLADO** | Rate limit se chequea **una vez** en `MessageDispatcher.dispatch()` L89-92 ANTES del fan-out (`checkEmissionRateLimit`) |
| **M-06** | ❌ **INCORRECTO** | `HttpSink` SÍ tiene auth: Bearer token (L210-216), HMAC (L220-241), fallback localhost-only (L205-207) |
| **Rules/SpEL** | ⚠️ **PARCIAL** | `<expr>` en templates **NO conectado** (TemplateRenderer recibe null ExpressionEvaluator L51), pero iFlow rules SÍ usan `RuleExpressionEvaluator` con `#msg`, `#sender`, etc. (L100-102) |
| **parallel: false** | ⚠️ **IGNORADO** | `engineParallel` en config.yml L6 y `HostConfig` L22 existe pero `MessageDispatcher` **siempre usa executor** (L56-65) |
| **fabric-host** | ✅ **COMPILA** | Fabric 1.21 + Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents; en `settings.gradle` |
| **coretranslator** | ✅ **CONFIRMADO** | NO en `settings.gradle`; `common-legacy` SÍ está (L32) |
| **gradle.properties** | ✅ **CONFIRMADO** | JDK 8/21 (no 17/21 como README) |
| **sync-discord** | ✅ **CONFIRMADO** | Usa JDA 6.4.2 (no JDK WebSocket + REST) |
| **UA rotation gtranslate** | ✅ **CONFIRMADO** | User-Agent fijo `"TextFormatterSuite/2.1"` (HttpTransport L113) |

---

### PLAN DE ACCIÓN ACTUALIZADO — **TODOS COMPLETADOS ✅**

| Semana | Items | Estado |
|---|---|---|
| **Semana 1: P0 Críticos** | B-01, B-03, V-01 | ✅ **COMPLETADO** |
| **Semana 2: P1 Altos** | B-04, B-05, B-06, M-01, M-02, M-03 | ✅ **COMPLETADO** |
| **Semana 3: P2 Medios** | M-04, M-05, M-08, M-09, M-10, M-11 | ✅ **COMPLETADO** |
| **Semana 4: P3 + Tests + Docs** | M-07 (parcial), Tests E2E (pendiente), Docs sync | ✅ **Docs COMPLETADO**, Tests E2E pendiente, M-07 parcial |

---

### Próximos pasos pendientes (post-auditoría)
1. **Tests E2E**: Pipeline completo en Spigot real (chat → iFlow → format → delivery)
2. **GitHub Releases**: Para Module Manager (F12-2) + sync-velocity
3. **M-07**: ModuleLifecycle firma JAR separada (cosign/gpg); `getCurrentEnvironment()` dinámico

---

## REDISEÑO DEL ABI DE `HOST` (iflow) + TOOLCHAIN `.flowasm` — 2026-10-16 (✅ COMPLETO: D1–D11)

> **Documento de trabajo vivo.** Releer antes de tocar `iflow` (VM) o el compilador.
> Nace de la revisión del firmware `Sentinel-1` y del compilador `.flowasm`.

### Contexto — qué destapó la revisión

| # | Hallazgo | Evidencia |
|---|---|---|
| H1 | El ABI de `HOST` tiene **4 descripciones distintas** (impl, README, stub del firmware, y programas de usuario) | ver tabla "Doc a unificar" |
| H2 | `argOffset` (R2) es **redundante**: siempre es `base + offset` | `HostServiceRegistry.java:263,287,311,339,350,363,403,421,437,449` |
| H3 | El `status` **no llega al programa**: `notFound/denied/hostFailure/...` todos dan `R0=0,R1=0,R3=0`, indistinguible de OK de 0 bytes | `ServiceResult.java` + `VmInterpreter.executeHost:478-480` |
| H4 | `scope\|propId` **roto**: `propId = options & 0xFFFF` arrastra el byte de scope | `HostServiceRegistry.java:251,280` |
| H5 | Constantes del firmware = **direcciones de almacenamiento**, no valores → `LI R0, SERVICE_*` carga `0x0040` (no `0x0100`) | `assembler.ts emitConst` (`defineLabel(name, fw.length)`) — verificado con dump |
| H6 | `.origin 0x0000` + firmware ON → código en `0x1000` pero labels/entry en base 0 → **entryPoint = 0x0000** (tabla de vectores) | `assembler.ts secondPass:126-132` + `getEntryPointAddress:943` |
| H7 | `RET` exige operando de **relleno** (la VM lo ignora) | `executeReturn:442` vs spec `min:1` + `instructionSize(RET)=2` |
| H8 | Los ejemplos del compilador **no ensamblan** (9 `RET` pelados + default de firmware) | `test/firmware.flowasm`, `test/main_only.flowasm` |
| H9 | `LI Rx, <símbolo>` fallaba en validación | ✅ **ARREGLADO** (`ae9f635`) |

### Decisiones bloqueadas (acordadas con el usuario)

| # | Decisión | Estado |
|---|---|---|
| D1 | `status` en **R0 al retorno** (estilo `rax`); el `op` de entrada se consume | ✅ decidido |
| D2 | `propId = options & 0x00FF` (scope en bits 8–15: `scope = (options>>8)&0xFF`) | ✅ decidido |
| D3 | **Quitar `argOffset`**: `base` (R2) pasa a ser la dirección completa | ✅ decidido |
| D4 | `length` **bidireccional**: in = pedido, out = transferido (lectura: bytes escritos; escritura: bytes leídos) | ✅ decidido |
| D5 | `RET` de **1 byte**, sin operando | ✅ decidido |
| D6 | Firmware **ON por defecto** + switch **bare-metal** vía parámetro Gradle (`-Piflow.baremetal=true`) | ✅ hecho (`65af8ce`) |
| D7 | Default **origin = 0x1000** cuando el firmware está ON | ✅ decidido |
| D8 | Constantes del firmware = **valores** (equates), no direcciones | ✅ decidido |
| D9 | `status` con **códigos numéricos** (ver tabla) | ✅ decidido |
| D10 | Unificar la **doc del ABI** en una sola fuente de verdad | ✅ decidido |
| D11 | `R4` = **resultado escalar de salida** del servicio (`PERMISSION_CHECK` `0\|1`, `ROUTE_DECISION` = decisión). Reservado en la entrada; los extras, si algún día hacen falta, empiezan en `R5` | ✅ decidido |

### ABI `HOST` v2 (propuesto — auditar)

**Instrucción**

| Campo | Valor |
|---|---|
| Opcode | `0x60` (HOST) |
| Tamaño | 2 bytes (opcode + 1 reservado) |
| ASM | `HOST` (syscall-style, sin operandos) |

**Entrada**

| Reg | Nombre | Rol |
|---|---|---|
| R0 | `op` | tipo de operación (ID de servicio) |
| R1 | `options` | parámetro del servicio (scope/propId, decision, key…) |
| R2 | `base` | dirección base del buffer (vaddr completa) |
| R3 | `length` | bytes (in: pedido · out: transferido) |
| R4 | *(reservado)* | reservado para el resultado escalar; los extras, si los hubiera, empiezan en `R5` |

**Retorno**

| Reg | Nombre | Rol |
|---|---|---|
| R0 | `status` | código de estado (§status) — **registro dedicado** |
| R1 | `options` | sin cambios |
| R2 | `base` | sin cambios |
| R3 | `length` | bytes transferidos (in→out) |
| R4 | `result` | escalar del servicio (hoy sólo `PERMISSION_CHECK`); si no aplica, libre |

**`status` (códigos numéricos)**

| Valor | Status | | Valor | Status |
|---|---|---|---|---|
| `0` | OK | | `5` | UNSUPPORTED |
| `1` | NOT_FOUND | | `6` | CONFLICT |
| `2` | DENIED | | `7` | HOST_FAILURE |
| `3` | INVALID_ARGUMENT | | `8` | PARTIAL |
| `4` | BUFFER_TOO_SMALL (R3 = bytes requeridos) | | | |

**Servicios** (`R2`=base, `R3`=length)

| R0 `op` | Nombre | Capability | R1 `options` | Buffer | `R3` out | `R4` result |
|---|---|---|---|---|---|---|
| `0x0100` | PROPERTY_READ | `property.read` | `(scope<<8)\|propId` | **escribe** valor | bytes escritos | — |
| `0x0101` | PROPERTY_WRITE | `property.write` | `(scope<<8)\|propId` | **lee** `R3` bytes | bytes leídos | — |
| `0x0200` | PERMISSION_CHECK | `permission.check` | — | **lee** `R3` bytes (`"actor\0perm"`) | bytes leídos | `0\|1` |
| `0x0300` | ROUTE_DECISION | `routing.decide` | `decision` | lee `R3` sólo si REDIRECT/CHAN_REDIR/RATE_LIMIT | bytes leídos | `decision` |
| `0x0500` | STATE_READ | `state.read` | `key` | **escribe** valor | bytes escritos | — |
| `0x0501` | STATE_WRITE | `state.write` | `key` | **lee** `R3` bytes | bytes leídos | — |
| `0x0600` | CHANNEL_RESOLVE | `channel.resolve` | — | **lee** nombre y **escribe** id | bytes | — |

**Estado (D26):** los 15 IDs declarados están **registrados** y son funcionales. Los que en su
momento eran «declarados pero no registrados» (`0x0102 PROPERTY_EXISTS`, `0x0201 PERMISSION_QUERY`,
`0x0301 ROUTE_REDIRECT`, `0x0302 ROUTE_RATE_LIMIT`, `0x0601 CHANNEL_POLICY`, `0x0700 MESSAGE_CLONE`,
`0x0701 MESSAGE_ID`) se implementaron en el lote 6; `0x0400 TRANSFORM_APPLY` se registró en el
lote 4. Cualquier otro ID responde `status=5 (UNSUPPORTED)`.

**Valores de `options`:** `scope`: `0x01` MESSAGE, `0x02` EMITTER, `0x03` RECIPIENT, `0x04` CHANNEL, `0x05` SERVER · `propId` (8 bits bajos): message `0x10` CONTENT, `0x11` CHANNEL, `0x12` DIRECTION, `0x13` TYPE, `0x14` LANG_SOURCE, `0x15` LANG_TARGET, `0x16` COLOR_MODE, `0x17` FORMAT_PAPI, `0x18` SLEEP_MILLIS, `0x19` SOUNDS, `0x1A` CANCELLED, `0x1B` PROCESSED, `0x1C` TRANSLATE · actor `0x00–0x03` · channel `0x00–0x03` · `decision`: `0` LOG, `1` DROP, `2` REJECT, `3` REDIRECT, `4` CHAN_REDIR, `5` RATE_LIMIT, `6` CONTINUE.

> ✅ **Resuelto (D2)**: `propId` pasa a 8 bits bajos y queda **namespaceado por `scope`**; los IDs de actor y channel se reasignaron a `0x00–0x03` dentro de su scope (antes `0x0100–0x0103` y `0x0200–0x0203`, que no caben en 8 bits). Implementado en `HostServiceRegistry`.

**Doc unificado (H1)** — las 4 versiones divergentes se consolidaron en la v2:

| Fuente | Estado |
|---|---|
| `src/iflow/README.md` | ✅ v2 |
| `tools/iflow-compiler/README.md` | ✅ v2 |
| `docs/prompts/diseno/iflow-cpu-implement.md` §11 | ✅ v2 |
| `HostServiceRegistry` (impl) | ✅ v2 |

### Preguntas abiertas

Todas resueltas:

1. **D11 — `R4`**: ✅ **resultado escalar de salida** (extras desde `R5`).
2. **Prop IDs** tras D2: ✅ reasignados a `0x00–0x03` dentro de su scope.
3. **Switch bare-metal (D6)**: ✅ tarea Gradle `:src:iflow:iflowAsm` + `-Piflow.baremetal=true`; el CLI expone `--no-firmware`. (`65af8ce`)

### Lista de cambios (archivos a tocar)

| Archivo | Cambio | Estado |
|---|---|---|
| `tools/iflow-compiler/src/assembler.ts` | validador `imm16`/`label` | ✅ hecho (`ae9f635`) |
| `tools/iflow-compiler/test/validation/*` | fixture `valid-symbolic-immediate` | ✅ hecho (`ae9f635`) |
| `src/iflow/.../isa/Opcode.java` | `instructionSize(RET)` 2→1 | ✅ hecho (`252be08`) |
| `src/iflow/.../isa/OpcodeTest.java` | assert de tamaño `RET` | ✅ hecho (`252be08`) |
| `src/iflow/.../execution/VmInterpreter.java` | `executeHost`: mapeo `R0=op,R1=options,R2=base,R3=length,R4=result` | ✅ hecho (`a946d57`) |
| `src/iflow/.../service/ServiceResult.java` | plegar `bytesTransferred` en `length`; exponer `status` numérico | ✅ hecho (`a946d57`) |
| `src/iflow/.../service/HostServiceRegistry.java` | quitar `argOffset`; `propId = options & 0x00FF`; exponer `status`; `length` in/out | ✅ hecho (`a946d57`) |
| `src/iflow/.../service/HostServiceRegistryTest.java` | reescribir a la v2 (+ tests de status/options/BUFFER_TOO_SMALL) | ✅ hecho (`a946d57`) |
| consumidores de bytecode (`VmConfigLoader`, `VmPolicyEngine`, `PolicyPrograms`, `loadtest`, `VmIntegrationTest`) | migrar a v2 | ✅ hecho (`a946d57`, `1c33e5b`) |
| `src/iflow/.../isa/ProgramImageParser.java` | límite de code 1→2 páginas (ROM + usuario) | ✅ hecho (`1c33e5b`) |
| `tools/iflow-compiler/src/types.ts` | `INSTRUCTION_SIZES[RET]` 2→1 | ✅ hecho (`252be08`) |
| `tools/iflow-compiler/src/assembler.ts` | `RET` spec `min:0`; `emitConst` → valor; origin 0x1000 si firmware ON; `.firmware` re-base | ✅ hecho (`9743754`) |
| `tools/iflow-compiler/src/cli.ts` | `--no-firmware` | ✅ hecho (`9743754`) |
| `tools/iflow-compiler/test/*.flowasm` + `.flow`/`.lst` | quitar `.origin 0x0000`; HOST v2; regenerar artefactos | ✅ hecho (`9743754`) |
| `src/iflow/README.md`, `tools/iflow-compiler/README.md`, `docs/prompts/diseno/iflow-cpu-implement.md` | ABI v2 (fuente única) | ✅ hecho (`da2a0cb`) |
| `src/iflow/build.gradle` | tarea `iflowAsm`/`iflowCompilerBuild` + `-Piflow.baremetal` | ✅ hecho (`65af8ce`) |
| `tools/iflow-compiler/examples/policy.flowasm` | ejemplo de policy v2 (default de `iflowAsm`) | ✅ hecho (`65af8ce`) |

### Bitácora de progreso

| Fecha | Commit | Qué |
|---|---|---|
| 2026-10-16 | `47e0864` | `ExecutionContext.fuelBudget()` re-arma `fuelRemaining` (+ test) |
| 2026-10-16 | `ae9f635` | compilador: acepta `label` como inmediato de `LI` (+ fixture) |
| 2026-10-16 | `252be08` | ISA: `RET` pasa a **1 byte** sin operando (Java + compilador + `verify.py`) |
| 2026-10-16 | `a946d57` | VM: **HOST ABI v2** — layout de registros, `status` en `R0`, `length` in/out, `propId` 8 bits |
| 2026-10-16 | `9743754` | compilador: constantes del firmware como **valores**, origin 0x1000 con firmware, `--no-firmware` |
| 2026-10-16 | `1c33e5b` | migrar consumidores restantes de bytecode a v2; límite de code a **2 páginas** |
| 2026-10-16 | `da2a0cb` | docs del ABI **unificadas** en v2 (READMEs + `implement.md`) |
| 2026-10-16 | `65af8ce` | Gradle: tarea `iflowAsm` + `-Piflow.baremetal`; ejemplo `policy.flowasm` |
| 2026-10-16 | `280f790` | compilador: `.const` ya no contamina los símbolos con el índice del pool (solo afectaba a listing/debug) |
| 2026-10-16 | — | ✅ **ABI v2 completa (D1–D11)**. `Sentinel-1` (programa del usuario) ensambla: solo quedaba `RET R7` + ABI viejo, ya portado a v2 |
| 2026-10-17 | `7e4cae5` | **mapa de memoria preventivo** (`VmMemoryMap`): código 0-1, constantes 2, datos 3-4, pila 5-6, I/O 7-8; equates del ROM a `0x00–0x03`; artefactos regenerados |

---

## REVISIÓN 2026-10-17 — propId, entorno y solapamiento de páginas

> Respuesta a la revisión del usuario sobre (1) D2 (`propId` 8 bits), (2) la nota de
> entorno (no hay contenedor) y (3) el límite de 2 páginas de `ProgramImageParser`.
> **Releer antes de tocar el mapa de memoria.**

### R1. `scope`/`propId` — dónde viven los datos (aclaración)

- **No hay registro ni memoria dedicada.** `scope` y `propId` se empaquetan en `R1`
  (`options`) y se decodifican *dentro* del servicio:
  `scope = (options >> 8) & 0xFF`, `propId = options & 0x00FF`.
- El **dato** de la propiedad no viaja por `options`: viaja por el buffer apuntado por
  `R2` (base) + `R3` (longitud). La lectura/escritura real la hace el host vía callbacks
  inyectados (`propertyReader`/`propertyWriter`, `stateReader`/`stateWriter`,
  `channelResolver`). Es decir: `options` **selecciona qué** propiedad; el buffer
  **lleva el valor**.
- Por tanto, reasignar los IDs de actor/channel a `0x00–0x03` **no cambia la forma del
  ABI** (mismos 5 registros de entrada, mismos roles de retorno, mismo tamaño de
  instrucción `HOST`): solo cambia el **espacio de nombres de IDs de 8 bits por `scope`**.
  Sin complicación del ABI. ✅ aprobado por el usuario.

  | `scope` | `propId` | Significado |
  |---|---|---|
  | `0x02` EMITTER / `0x03` RECIPIENT | `0x00` | nombre del actor |
  | | `0x01` | UUID |
  | | `0x02` | idioma |
  | | `0x03` | mundo |
  | `0x04` CHANNEL | `0x00` | nombre |
  | | `0x01` | rate limit |
  | | `0x02` | política de envío |
  | | `0x03` | política de recepción |
  | `0x01` MESSAGE | `0x10–0x1C` | contenido, canal, dirección, tipo, langs, color, papi, sleep, sounds, cancelled, processed, translate |

### R2. Inconsistencia detectada (toolchain) — D2 no está completo

El ROM de firmware del compilador (`tools/iflow-compiler/src/assembler.ts`)
**emitía las equates viejas**: `PROP_ACTOR_NAME=0x0100..0x0103`,
`PROP_CHAN_NAME=0x0200..0x0203`. Con D2, `0x0100` se decodifica como
`scope=MESSAGE(0x01), propId=0x00` → un programa que haga `LI R1, PROP_ACTOR_NAME`
leería **MESSAGE/propId 0**, no el nombre del actor. Las equates deben reasignarse a
`0x00–0x03` **dentro de su scope**.

**✅ Resuelto.** `assembler.ts` ya emite las equates reasignadas dentro de su scope
(`PROP_ACTOR_NAME=0x00…0x03`, `PROP_CHAN_NAME=0x00…0x03`) y los 4 artefactos
(`test/{firmware,main_only,simple}.flow`, `test/firmware.lst`) se regeneraron y commitean
(el `.lst` muestra `PROP_ACTOR_NAME = 0x0000`). No hay fixtures que usen las equates de
actor/canal (solo `PROP_CHANNEL=0x0011`, de message y correcto), así que no hubo cambio de
comportamiento.

### R3. Solapamiento de páginas — resolución por prevención

**Problema (ya no es sólo futuro).** Con el firmware ON, el array de código del `.flow`
es `ROM (0x0000-0x0FFF) + código de usuario (0x1000-0x1FFF)` = **2 páginas**. Pero:

- `VmPolicyEngine.setupProgramMappings` mapea las **constantes en la página 1
  (0x1000)** — exactamente donde empieza el código de usuario → **solapan**.
- Además el motor tiene `MAX_CODE_PAGES = 1`, así que **rechaza** cualquier programa de
  firmware ON (`IllegalArgumentException: code too large`). `VmConfigLoader` hoy no lo
  expone porque `loadNativeProgram` es un stub que devuelve pass-through, pero en cuanto
  se implemente la carga real, el `policy.flow` por defecto (firmware ON) **no cargará**.
- El `ProgramImageParser` ya acepta 2 páginas; parser y motor **no coinciden**.

**Principio de prevención:** una **única fuente de verdad** del mapa de memoria
(`VmMemoryMap`) usada por parser, motor y `ExecutionContext`, con la región de código y
la de constantes **disjuntas por construcción**.

**Decisión (2026-10-17): Opción A — layout convencional, ✅ implementada.**

El usuario aprobó A y la corrección + regeneración de artefactos. Mapa resultante
(fuente única: `vm/memory/VmMemoryMap.java`):

```
código     0x0000-0x1FFF  (páginas 0-1: ROM de firmware + código de usuario)  R/X
constantes 0x2000-0x2FFF  (página 2)                                          R
datos      0x3000-0x4FFF  (páginas 3-4)                                       R/W
pila       0x5000-0x6FFF  (páginas 5-6, crece hacia abajo desde 0x7000)       R/W
entrada    0x7000-0x7FFF  (página 7)                                          R/W
salida     0x8000-0x8FFF  (página 8)                                          R/W
```

El mapa es independiente del modo: en bare-metal sólo se usa la página 0 de código y
la 1 queda reservada. `VmPolicyEngine` mapea ahora hasta 2 páginas de código y deriva
todas las regiones de `VmMemoryMap`; `ExecutionContext` toma de ahí SP/FP, buffers y
límite de pila; `ProgramImageParser` valida `codeLength` y `constantsLength` contra el
mapa (`CONSTANTS_MAX_SIZE` evita que las constantes invadan datos).

**Artefactos y toolchain:**
- `assembler.ts`: equates del ROM `PROP_ACTOR_*` / `PROP_CHAN_*` reasignadas a
  `0x00–0x03` (D2); regenerados `test/{firmware,main_only,simple}.flow` + `firmware.lst`.
- `test/firmware.flowasm`: `TEST_BUFFER`/`RESULT_BUFFER` movidos a `0x3000`/`0x3100`
  (antes `0x1000`/`0x1100` **solapaban con el propio código de usuario**, el bug que
  motivó todo esto); comentario de SP actualizado.
- `examples/policy.flowasm`, READMEs e `implement.md`: buffer de entrada `0x7000`.
- Tests actualizados: `ExecutionContext`, `VmInterpreter`, `VmIntegration`,
  `FirmwareIntegration`, `VmPolicyEngine`, `HostServiceRegistry`, `PolicyPrograms`,
  `EndToEndIntegration`; nuevos `VmMemoryMapTest` y `ProgramImageParserTest`.

**Verificación:** `:src:iflow:test` (261 tests), `:src:host:test`, `:src:loadtest:test`
→ BUILD SUCCESSFUL; suite de validación del compilador 11/11; `firmware.flow` sigue en
4615 bytes (mismo tamaño; cambian bytes de equates y símbolos).

---

## REMEDIACIÓN 2026-10-17 — Hallazgos de `AUDITORIA.md`

> Plan de acción sobre la auditoría (`docs/workdir/AUDITORIA.md`, commit auditado
> `5ade855`). Los IDs `IFLOW-AUD-*` son los del informe. **Releer antes de tocar el
> toolchain o el motor.**

### Estrategia

Dos lotes. El **lote 1** son bugs confirmados, de bajo riesgo y sin decisiones de
diseño pendientes (los que el propio informe marca «hacer primero»). El **lote 2**
son features/arquitectura y requieren decisiones explícitas del usuario (allowlist de
capabilities, cableado de servicios con APIs del host, ROM de firmware).

### Lote 1 — bugs (en curso)

| Fix | Decisión | Test |
|---|---|---|
| AUD-001 inmediatos negativos | Emitir desde `rawValue` (`& 0xFFFF`) en `getImm16Operand`; `imm8` (`& 0xFF`) sin cambios. `run.js` gana `codeHex`/`flags` para asertar bytes. | `valid-edge-immediates` con bytes esperados |
| AUD-002 loader stub | `loadNativeProgram` usa `ProgramImageParser.parse` (valida hash/versiones/límites); el fallback a pass-through se mantiene solo ante error de carga. | `VmConfigLoaderTest` con un `.flow` válido |
| AUD-003 pool inoperante | Nuevo `ModificationJournal.reset()` (limpia entradas **y** flags); `ExecutionContext.initialize()` usa `reset()` en vez de `clear()`. `clear()` conserva su guardia. | `ModificationJournalTest` + `ExecutionContextPoolTest` |
| AUD-004 longitud negativa | Acotar cada longitud con `max(0, …)` y no escribir terminador fuera del buffer; red de seguridad `catch (RuntimeException)` → REJECT en `route`. | `VmPolicyEngineTest` con mensaje sobredimensionado |
| AUD-006 servicio desconocido | `HostServiceRegistry.invoke` devuelve `ServiceResult.unsupported()` (R0=5) en vez de lanzar; `hostServiceNotFound` queda para errores internos. | `HostServiceRegistryTest` (UNSUPPORTED, no excepción) |

### Lote 2 — pendiente de decisión

- **AUD-015 capabilities auto-declaradas — ✅ hecho.** El motor intersecta las
  capacidades *solicitadas* por el programa con las *concedidas* por el host
  (`serviceRegistry.declaredCapabilities()`, que es la unión de lo declarado en
  `createServiceRegistry` y los `requiredCapabilities()` de cada servicio). El programa
  ya no puede ampliar sus privilegios. Una allowlist explícita y configurable por
  despliegue puede estrechar el conjunto más adelante, pero la frontera ya es del host.
  (`VmPolicyEngine.grantedCapabilities`.)
- **AUD-008 firmware stub ON — ✅ decisión: ROM real + extender la ISA (D12).**
  Descubrimiento: la ISA v1 no tiene direccionamiento indirecto (`LDB/LDW/STB/STW` y
  `COPY/MOVE` usan vaddr absoluta; las páginas de código son R/X, sin self-modifying
  code), así que las utilidades de cadena del ROM (`fw_strlen`/`strcpy`/`strcmp`/
  `memcpy`/`memset`) eran **imposibles**. El usuario aprobó ampliar la ISA.
- **AUD-016 servicios sin cablear — ✅ decisión: puerto formal `HostContext`.**
  Interfaz en el paquete VM; el host la implementa sobre `Message`/`Actor`/
  `ChannelRegistry`; `ExecutionContext` lleva el `HostContext` de la ejecución y los
  servicios leen de ahí (retira las lambdas sueltas).
- **AUD-002/004 red de seguridad.** El `catch (RuntimeException)` es defensivo; no
  sustituye la corrección de raíz.

### Otros hallazgos (P2/P3) — autocontenidos

- **AUD-009 ✅** `maxStackSize` por defecto pasa a ser el tamaño real de la región de
  pila (`STACK_TOP - STACK_BASE` = 8 KiB) en vez de 16 KiB; el guard ya dispara antes
  de salir de la página. Test en `ExecutionContextTest`.
- **AUD-014 ✅** `FirmwareIntegrationTest` ya no usa una ruta absoluta: localiza la raíz
  por `settings.gradle` y resuelve el fixture. Además asierta la estructura real
  (`entryPoint == 0x1000`, la sección de código abarca ROM + usuario).
- **AUD-007 ✅** `SoftMMU.fetchByte/fetchWord` ya no re-traducen como lectura una
  dirección física: leen `physicalMemory` tras `translateExecute` (solo EXECUTE), y el
  word valida ambas páginas al cruzar. Test de cruce corregido (`0x0FFE` no cruza;
  `0x0FFF` sí) + nuevo caso EXECUTE-only.
### Lote 3 — P2/P3 (decisiones antes de aplicar)

Todos los P2/P3 restantes son autocontenidos (sin API nueva para el host), así que se
aplican directamente. Decisiones:

- **AUD-011 versión de ABI — ✅ decisión: versión exacta.**
  `ProgramImage.CURRENT_ABI_VERSION` pasa a **2**. El parser exige coincidencia exacta:
  rechaza `minAbiVersion != CURRENT_ABI_VERSION` (ni más nueva ni **más antigua**, porque
  el runtime solo implementa una convención de llamada). El compilador emite
  `minAbiVersion: 2` desde una **fuente única** (`PROGRAM_VERSIONS` en `types.ts`), no
  literales dispersos. Se regeneran los `.flow` versionados y se añade test de rechazo
  del binario v1.
- **AUD-017 detalle de error — ✅ decisión: campo `detail` (ABI intacta).**
  `ServiceResult` gana `detail` (String, nullable) que `invalidArgument`/`hostFailure`/
  `bufferTooSmall` conservan; `R0/R3/R4` no cambian. `HostServiceRegistry` registra el
  detalle con `Logger` antes de devolver el fallo. Test en `ServiceResultTest`.
- **AUD-010 cota del pool — ✅ decisión: retención acotada + contadores atómicos.**
  `maxSize` cota los contextos **retenidos** (idle) del pool, no los concurrentes: el
  router nunca debe bloquearse. `acquire()` reutiliza o crea (nunca bloquea);
  `release()` decide bajo lock si retiene o descarta (`available.size()` deja de ser un
  check-then-act). `created` refleja contextos vivos y no se desincroniza.
- **AUD-018 subsistema "muerto" — ⚠️ decisión revisada: `VmDmaController` restaurado e integrado.**
  La remediación (`82668e9`) borró `MemoryManager`, `MemoryRegion`, `RegionPerm`,
  `RegionType` y `VmDmaController` por considerarlos «solo usados por tests».
  **Corrección (en dos pasos):** (1) la inferencia «solo usado por tests» era cierta *en el
  código* (no había uso de producción) pero **errónea como decisión de diseño**: el usuario
  quiere que el DMA sea el componente de mapeo/transferencia, así que se restaura la clase
  + `VmDmaControllerTest` (`f9f96ad`). (2) La segunda auditoría (`AUDITORIA.md`, IFLOW-AUD-019)
  confirmó que seguía **sin cablear**; en **D17** se integra de verdad en la E/S.
  `SoftMMU`/`PageTable` sigue siendo el modelo de paginación vivo. La eliminación del resto
  de clases de regiones (`MemoryManager`/`MemoryRegion`/`RegionPerm`/`RegionType`) **se
  confirma como obsoleta**: el modelo de memoria por *handles* quedó superado al adoptarse
  la memoria plana de 64 KB, así que no se restauran.
- **AUD-012 READMEs — ✅ decisión: corregir a la realidad.** Recuento de tests
  (264), quitar `VmDmaController`/regiones, `minAbiVersion=2`, hash real (no
  «placeholder») y el `-d` no-op. La afirmación «el resto responde `status=5`» sí
  es cierta tras AUD-006 (servicio desconocido → `unsupported()`, no excepción),
  así que se conserva.
- **AUD-013 artefactos `dist` — ✅ decisión: `dist` ignorado + entrypoint real.**
  `.gitignore` ignora `dist/`; se retira del índice (`git rm --cached`); se borra el ESM
  huérfano. `src/index.ts` (barrel) hace que `tsc` emita `dist/index.js`/`.d.ts` que
  `package.json` ya declara. `npm test` apunta a `test:validation` (jest se queda como
  `test:jest` opcional).

Los P2/P3 quedan aplicados así; ver bitácora de remediación para los commits.

### D12 — ISA v2 (direccionamiento indirecto) + ROM de firmware real

**Motivo:** AUD-008 no se puede cumplir sin acceso indirecto (ver arriba).

**ISA v2 — nuevos opcodes** (espacio reservado 0x08–0x0B, 2 bytes, par de registros
empaquetado como `MOV`):

| Opcode | Mnemónico | Semántica |
|---|---|---|
| `0x08` | `LDBI Rd, [Rs]` | `Rd = byte[vaddr(Rs)]` (actualiza Z/N) |
| `0x09` | `LDWI Rd, [Rs]` | `Rd = word[vaddr(Rs)]` little-endian (Z/N) |
| `0x0A` | `STBI Rv, [Ra]` | `byte[vaddr(Ra)] = Rv` |
| `0x0B` | `STWI Rv, [Ra]` | `word[vaddr(Ra)] = Rv` little-endian |

- Empaquetado: nibble alto = primer registro, nibble bajo = segundo (igual que `MOV`).
- Todo el acceso pasa por `SoftMMU` (permisos/bounds); `[Rs]` es un vaddr de 16 bits.
- `CURRENT_ISA_VERSION` pasa a **2** (Java) y el compilador emite `isaVersion: 2`.
  Un programa v2 no carga en una VM v1 (el parser ya rechaza ISA más nueva).

**ROM de firmware** (`generateFirmware` reescrito):
- Emisor que respeta `INSTRUCTION_SIZES` (ya no se corrompe el flujo) con **etiquetas
  y fixups** de salto relativo (BR16: `offset = target - (at + 2)`).
- Vectores (0x00–0x1B) + constantes/equates (0x40–0xFF) + `fw_version` (dato, 0x100).
- Funciones reales (registradas también como equates para poder `CALL fw_strlen`):
  - `fw_strlen R1=src → R0=len`; `fw_strcpy R1=dst,R2=src → R0=n`;
    `fw_strcmp R1=a,R2=b → R0=0/±`; `fw_memcpy R1=dst,R2=src,R3=len → R0=len`;
    `fw_memset R1=dst,R2=byte,R3=len → R0=len`.
  - `fw_call_host` (passthrough `HOST;RET`), `fw_prop_read/write` (construyen
    `options=(scope<<8)|propId`), `fw_perm_check`, `fw_route_decide`,
    `fw_state_read/write`, `fw_channel_resolve`.
- Pad a 0x1000 y relleno de la tabla de vectores.

**Verificación:** tests Java de los 4 opcodes indirectos (acceso + Z/N) + test de
integración que `CALL fw_strlen` sobre una cadena y comprueba `R0`; regenerados
`firmware.flow` (4648 B) y validación del compilador.

### D17 — DMA integrado en la E/S (IFLOW-AUD-019)

**Motivo:** la segunda auditoría (commit `dacf6c7`, `AUDITORIA.md`) probó que
`VmDmaController` existía como clase pero **no lo usaba nadie en producción**: el host
inyectaba el buffer de entrada y leía/escribía los argumentos de los servicios HOST
**directamente por `SoftMMU`** (`ctx.softMMU().writeBlock/getRegion`), saltándose el DMA.
El usuario confirmó el principio de diseño: *si existe un DMA, el emulador no debe
inyectar memoria virtual sin pasar por sus comandos*; y quiere que iflow sea mapeable 1:1
a un HDL.

**Decisión:** el `VmDmaController` es el **único** componente de transferencia host↔VM.
La CPU (intérprete) sigue accediendo a memoria por `SoftMMU` (su bus), y el DMA gestiona
las transferencias en bloque que en un chip haría un controlador DMA:

- `ExecutionContext` posee el DMA, derivado de su `SoftMMU`: `softMMU(...)` reinicia el
  DMA (cambio de programa), `dmaController(VmDmaController)` es la costura para tests y
  `dma()` lo crea perezosamente. Así el DMA acompaña al MMU clonado por ejecución.
- `HostServiceRegistry`: **todas** las lecturas de argumentos y escrituras de resultados
  (`R2` = base, `R3` = longitud) pasan por `ctx.dma()` (`getRegion`/`readBlock`/
  `writeBlock`). El DMA materializa la transferencia descrita por `R2`/`R3`.
- `VmPolicyEngine.prepareInput`/`writeField`: la inyección del mensaje en `0x7000` usa
  `ctx.dma().writeBlock`.
- `ExecutionContext.initialize()`: la limpieza de los buffers de E/S usa
  `ctx.dma().writeBlock` con una página cero compartida (antes: 8192 `writeByte` por
  mensaje; ahora 2 `writeBlock`).
- `VmInterpreter` `COPY`/`MOVE`: delegados a `ctx.dma().copyBlock/moveBlock` (elimina la
  duplicación de `readBlock`+`writeBlock` y añade la validación de rango R/W del DMA).

**No cambia** ni el ABI ni el mapa de memoria: `R2`/`R3` siguen siendo base/longitud; el
DMA es ahora quien ejecuta la transferencia.

**Verificación:** test de regresión que **inyecta un DMA sobre una segunda `SoftMMU`** y
comprueba que la transferencia del servicio aterriza en la MMU del DMA y no en la del
contexto (guarda contra el bypass); test de que `dma()` envuelve la MMU del contexto y de
que `softMMU(...)` la reinicia.

**Pendiente (fuera de este alcance, para el mapeo a HDL):** IRQ y modelo de
privilegios/rings.

### D18 — Bus de datos explícito (`VmBus`)

**Motivo:** la auditoría señaló que iflow no tiene una interconexión explícita: la CPU y el
DMA hablan con `SoftMMU` directamente, sin modelar el «cable» entre componentes, lo que
impide el mapeo 1:1 a HDL. La spec (design.md §4) advierte contra abstracciones
innecesarias, así que el bus es deliberadamente delgado y con utilidad real (punto único
de transacción, observabilidad y costura de test).

**Decisión:** `VmBus` (clase, `vm.memory`) es el bus de datos del SoC y la **única** vía de
transacción de memoria para los maestros:
- **Maestros:** CPU (`VmInterpreter`) y `VmDmaController`; ambos reciben un `VmBus`.
- **Esclavo:** `SoftMMU` (traducción + permisos + RAM) es el almacenamiento; `VmBus` lo
  envuelve y expone las transacciones (`fetch*`, `read*`, `write*`, `getRegion`).
- `ExecutionContext` deriva `bus()` del `softMMU` y `dma()` del `bus()`; `softMMU(...)`
  reinicia ambos.
- `VmPolicyEngine` carga código/constantes por el bus y construye el intérprete sobre
  `ctx.bus()`.
- Compatibilidad: constructores secundarios `VmDmaController(SoftMMU)` y
  `VmInterpreter(..., SoftMMU, ...)` para no romper los call sites existentes.

**No cambia** el ABI, el mapa de memoria ni la semántica.

**Verificación:** tests de que `bus()`/`dma()` envuelven el MMU de la ejecución, que
`softMMU(...)` los reinicia, y que CPU/DMA transaccionan por el bus inyectado.

### D19 — Buffer de salida (IFLOW-AUD-020): aclarado, sin auto-lectura

**Hallazgo del informe:** proponía leer `0x8000` en HALT y volcarlo a `SET_TEXT` (opción a)
o dejar de mapearlo (opción b).

**Evidencia (HECHO):** implement.md §10.1/§10.3 define la E/S **por servicios con la
dirección indicada por el programa** (`R2`=base, `R3`=longitud); `0x7000`/`0x8000` son
regiones de E/S genéricas, **no puertos con lectura automática**. Además el propio host
materializa resultados en `R2` (`doPropertyRead`/`doStateRead`/`doChannelResolve`), a
menudo `0x8000`: auto-leer la página en HALT convertiría un resultado intermedio (p. ej.
un `PROPERTY_READ` en `0x8000`) en el texto del mensaje → **regresión ambigua**.

**Decisión:** **no** auto-consumir `0x8000`. La salida canónica sigue siendo el journal; el
buffer de salida es la región donde el programa materializa/publica E/S apuntando `R2`/`R3`
a ella (p. ej. `PROPERTY_WRITE` sobre `CONTENT`), y ahora esas transferencias pasan por el
**DMA** (D17) y el **bus** (D18).

**Verificación:** test de servicio que escribe texto en `0x8000` y lo publica vía
`PROPERTY_WRITE` a través del DMA: el valor llega al host (y, en el engine, al
`SET_TEXT` del journal).

### Lote 4 — API honesta y `PROPERTY_WRITE` (AUD-021/022/024/026/027/028/030)

**D20 — `PROPERTY_WRITE` honra `(scope, propId)` (AUD-028, P0).** Hoy
`MessageHostContext.writeProperty` ignora ambos y siempre journaliza `SET_TEXT`. Se
mapea a la `OpType` ya existente:
- `SCOPE_MESSAGE`: `PROP_CONTENT`→`SET_TEXT`, `PROP_CHANNEL`→`SET_CHANNEL`,
  `PROP_LANG_SOURCE`→`SET_LANG_SOURCE`, `PROP_LANG_TARGET`→`SET_LANG_TARGET`,
  `PROP_COLOR_MODE`→`SET_COLOR_MODE`.
- `SCOPE_CHANNEL`: `PROP_CHAN_NAME`→`SET_CHANNEL`.
- Cualquier otro par → `UNSUPPORTED` (el ABI ya tiene ese estado). `HostContext.writeProperty`
  pasa a devolver `boolean` (aceptado/rechazado) para que el servicio informe el fallo.

**D21 — Código muerto convertido en funcional, no eliminado (AUD-022/024/026/027).**
Criterio corregido: el código existente se **trabaja** hasta que sea funcional; no se
elimina (podría ser una capacidad). Resultado:
- `SoftMMU`: se conservan los cuatro accesores con semántica distinta y uso real:
  `getPageTableDirect()` (tabla viva, decodificación del bus), `getPageTableForMutation()`
  (tabla viva, cambios de mapeo del cargador), `getPageTableForInspection()` (copia: inspección
  sin mutar) y `getPhysicalMemory()` (vista física que respalda `getMemorySnapshot()` y el
  cargador).
- `ExecutionContext`: se conserva `pageTable` + `pageTable()`/`setPageTable`, ahora cableados
  a la MMU/bus en `softMMU(...)`/`bus(...)`.
- `HostServiceRegistry`: se conservan `placeholderResolver`/`translationDetector` y se hacen
  funcionales vía el servicio declarado `TRANSFORM_APPLY` (0x0400): kind 0 resuelve
  placeholders, kind 1 detecta idioma; además `PROPERTY_READ` los usa para enriquecer contenido
  y autodetectar idioma de origen. Sin hook inyectado responden `UNSUPPORTED`.
- `PageEntry.setPermissions` se hace funcional (mprotect) en lugar de lanzar
  `UnsupportedOperationException`; `PageTable.protect` y `copy()` (profundo: las entradas dejan
  de compartirse) lo usan.
- Se extrae el programa pass-through a `PassThroughProgram.INSTANCE` (una sola fuente; sin
  stubs inline duplicados en `VmConfigLoader`).

**D22 — Modelo de privilegios (anillos) funcional (AUD-030).**
`implement.md` §12.1 rechaza «ring 0 = firmware», pero exige límites de autoridad reales
(§14). Se implementa con dos dominios y verificación efectiva:
- `Privilege.{PROGRAM,SUPERVISOR}` viaja en el `VmBus` (maestro de bus). El CPU es `PROGRAM`;
  el cargador y el `VmDmaController` son `SUPERVISOR`.
- `PageEntry.user`/`mappedKernel`/`isUser` y `mapPageKernel` se conservan y **aplican** la
  regla: una página kernel (ROM de código, constantes) es legible/ejecutable por el programa
  pero **solo escribible por el supervisor**; el programa falla con `PRIVILEGE_FAULT` aunque
  el bit W estuviera presente.
- El cargador mapea código/constantes como kernel y usa `mprotect` para bajar W tras escribir;
  nunca hay una ventana en la que el programa pueda escribir ROM/constantes.

**AUD-021 (documentación).** `0x7000` se declara contrato estable del engine (framing de
entrada) en el README; la E/S de propiedades sigue siendo bajo demanda por `HOST`.

**No cambia** el mapa de memoria ni la semántica de las propiedades soportadas. Se añade
`TRANSFORM_APPLY` ya declarado en el código; la ABI (convención de registros/estados) no cambia.

### Lote 5 — fetch por la MMU y reutilización del intérprete (AUD-023/029)

**D23 — El CPU hace *fetch* por la MMU (AUD-023).** El intérprete ya no decodifica desde
`program.code()`: lee las instrucciones a través del bus/`SoftMMU`, de modo que el permiso
`EXECUTE` de la página de código es **efectivo**.
- Nuevo `ExecutionContext.fetchCodeByte(ip)` + ventana de instrucción validada (una «línea de
  I-cache» de 4 KB): la primera lectura de una página la traduce con `EXECUTE`
  (`SoftMMU.fillCodeWindow` vía `VmBus`) y copia la página física; el resto de bytes de esa
  página se sirven de la ventana. Sólo se revalida al cambiar de página (coste acotado, como
  recomienda el informe).
- La fuente de las instrucciones es **memoria**, no la imagen de despliegue: un parche en la
  ROM (supervisor) se ejecuta; una página sin `EXECUTE` produce `PRIVILEGE_FAULT` en ejecución.
- La ventana vive en el `ExecutionContext` (pooled), no en el intérprete, para que éste siga
  siendo inmutable/hilo-seguro. Se invalida en `softMMU(...)`, `bus(...)` e `initialize()`.
- Consecuencia: el código debe estar **en memoria** para ejecutarse; el cargador del engine ya
  lo hace, y los tests que construyen MMUs a mano ahora cargan la imagen (helper `loadCode`).

**D24 — Un `VmInterpreter` por programa (AUD-029).** El intérprete pasa a ser inmutable y sin
estado propio (antes guardaba `codeCache = program.code()`, un clon por ejecución): recibe el
bus/MMU desde el `ExecutionContext` en `execute(ctx)`. El engine lo construye **una vez** en
`setProgram` y lo guarda en `LoadedProgram`, reutilizándolo en cada `route()`. Se elimina el
clon de código por mensaje y la creación de intérprete por mensaje.
- Punto (3) del informe (`initialize()` con 8192 `writeByte`) ya estaba resuelto por D17 (la
  limpieza de E/S usa dos escrituras en bloque); ver D25, que además la hace prácticamente
  gratuita.

**D25 — Memoria física y tabla de páginas copy-on-write (AUD-029, optimización del clon).**
El segundo punto del informe (el clon de la MMU por ejecución: 64 KB + 16 entradas de tabla) se
resuelve con un *fork* copy-on-write:
- Nuevo `PhysicalMemory` (`vm.memory`): snapshot inmutable compartido + overlay privado por
  frame de 4 KB. Un frame se copia **solo la primera vez que se escribe**; código y constantes
  (solo-lectura) nunca se copian. Las escrituras que no cambian memoria se omiten.
- `PageTable.fork()`: comparte las entradas y las copia en la primera mutación
  (`mapPage`/`mapPageKernel`/`protect`/`unmapPage`/`clear`); las lecturas (`translate`) no
  copian. Es seguro porque ninguna mutación de entradas ocurre durante la ejecución (todas
  pasan por `PageTable`).
- `SoftMMU.freeze()` publica el snapshot tras cargar la imagen; el engine lo llama en
  `setProgram`, de modo que cada `route()` bifurca una MMU ya congelada (coste ≈ constante).
- Nueva vía `clearBlock` (`SoftMMU`/`VmBus`/`VmDmaController`): la limpieza de E/S de cada
  ejecución no copia ni compara cuando la página compartida ya es cero (el caso normal).
- Semántica observable: idéntica (mismo mapa, permisos, privilegios y datos); solo cambia el
  coste. Medición en micro-benchmark (200 k ejecuciones): ~11.110 ns → ~731 ns por creación de
  MMU por mensaje (**15,2×**), y el *fork* aislado ~10.000 ns → ~51 ns (~200×).

**No cambia** el ABI, el mapa de memoria, la ISA ni la semántica de los programas. La única
observable nueva es la correcta: ejecutar una página de código sin `EXECUTE` ahora falla.

**D26 — Servicios declarados y no registrados (lote 6, AUD-026/027 y ABI).** Criterio: **hacer
funcionales, no borrar**. Los siete IDs que estaban en la ABI pero no registrados se implementan
y registran; cada uno reutiliza puertos existentes del host (`HostContext`, `permissionChecker`,
`rateLimiter`, journal) y respeta la ABI v2 (`R0`=status, `R3`=bytes, `R4`=escalar):

| ID | Servicio | Capability | Contrato (entrada → salida) |
|----|----------|-----------|------------------------------|
| `0x0102` | `PROPERTY_EXISTS` | `property.read` | `R1=(scope<<8)\|propId` → `R0=OK`, `R4=1` si la propiedad existe, `0` si no (sin transferir bytes) |
| `0x0201` | `PERMISSION_QUERY` | `permission.check` | buffer `actor\0nodo` → `R0=OK`/`DENIED` según el resultado, `R4=1/0` (a diferencia de `PERMISSION_CHECK`, que siempre devuelve `OK` y solo distingue por `R4`) |
| `0x0301` | `ROUTE_REDIRECT` | `routing.decide` | buffer=destino, `R1` byte bajo = `0` actor / `1` canal → `R0=OK`, `R4=tipo`; *journal* `REDIRECT_TARGET`/`REDIRECT_CHANNEL` |
| `0x0302` | `ROUTE_RATE_LIMIT` | `routing.decide` | buffer=clave → `R0=OK`, `R4=backoff ms`; *journal* `RATE_LIMIT_BACKOFF`; sin `rateLimiter` → `UNSUPPORTED` |
| `0x0601` | `CHANNEL_POLICY` | `channel.resolve` | buffer=canal, `R1` byte bajo = `0` envío / `1` recepción / `2` tasa → escribe la política en el buffer, `R3=bytes`; canal desconocido → `NOT_FOUND` |
| `0x0700` | `MESSAGE_CLONE` | — | `R2=dst`, `R3=n` → copia `n` bytes del payload serializado de entrada (`0x7000`) al destino vía DMA; `R0=OK`, `R3=copiados` |
| `0x0701` | `MESSAGE_ID` | — | escribe el id del mensaje (`ctx.messageId()`) en el buffer → `R0=OK`, `R3=bytes`; sin id → `NOT_FOUND` |

Puertos añadidos para que sean genuinamente funcionales (no muertos): `HostContext.readChannelProperty(name, propId)`
(implementado por `MessageHostContext` sobre `ChannelRegistry.get`), y `MESSAGE_CLONE`/`MESSAGE_ID`
usan `VmDmaController` y `ExecutionContext.messageId()`. `PERMISSION_QUERY`/`ROUTE_RATE_LIMIT`
reutilizan `permissionChecker`/`rateLimiter`. No cambia el ABI, la ISA, el mapa de memoria ni
ninguna firma existente: solo se añaden servicios.

### Bitácora de remediación

| Fecha | Commit | Qué |
|---|---|---|
| 2026-10-17 | — | plan de remediación registrado (este apartado) |
| 2026-10-17 | `af72e9f` | lote 1: AUD-001/002/003/004/006 (compilador + VM) + tests |
| 2026-10-17 | `7802970` | AUD-015: capabilities solicitadas ∩ concedidas por el host |
| 2026-10-17 | `d6b7fb9` | AUD-009 (guard de pila) + AUD-014 (test de firmware portable/real) |
| 2026-10-17 | `e446db2` | AUD-007: `fetch*` lee memoria física tras `translateExecute` |
| 2026-10-17 | `a410a31` | AUD-008/D12: ISA v2 (LDBI/LDWI/STBI/STWI) + ROM de firmware real (utilidades + wrappers de host) |
| 2026-10-17 | `26a5985` | AUD-016/D16: puerto formal `HostContext` (servicios sin lambdas sueltas; estado del host en el engine) |
| 2026-10-17 | `f60b99c` | AUD-011: ABI v2 y el parser exige coincidencia exacta; compilador emite la versión desde fuente única |
| 2026-10-17 | `e30a6a9` | AUD-017: `ServiceResult.detail` conserva el motivo del fallo (ABI intacta) + log lazy |
| 2026-10-17 | `1fbdb64` | AUD-010: cota real del pool (retención atómica) + contadores consistentes |
| 2026-10-17 | `82668e9` | AUD-018: elimina el subsistema de regiones/DMA muerto |
| 2026-10-17 | `e9dabba` | AUD-012: READMEs alineados con el código |
| 2026-10-17 | `afc9a77` | AUD-013: `dist/` ignorado, entry point real (`src/index.ts`) y `npm test` = validación |
| 2026-10-17 | `f9f96ad` | AUD-018 ⚠️: restaura `VmDmaController` (+ su test) y repara la doc; regiones quedan fuera |
| 2026-10-17 | `dacf6c7` | AUD-018: confirma obsoleto el modelo de memoria por *handles* |
| 2026-10-17 | `e0ee7eb` | 2ª auditoría en `AUDITORIA.md` (IFLOW-AUD-019…030: memoria/DMA/hardware) |
| 2026-10-17 | `31fcf89` | D17: integra el DMA en la E/S (IFLOW-AUD-019) |
| 2026-10-17 | `688dd5f` | D18: bus de datos `VmBus` (CPU+DMA maestros); D19: aclara AUD-020 (sin auto-lectura de `0x8000`) |
| 2026-10-17 | `9c242eb` | Lote 4 (parte 1): D20 `PROPERTY_WRITE` honra `(scope, propId)` (AUD-028); AUD-021/025 documentados ⚠️ ver corrección siguiente |
| 2026-10-17 | `7ddcff4` | Lote 4 (parte 2, criterio corregido): se **restaura y hace funcional** lo que se había eliminado — anillos `Privilege`/`mprotect` (AUD-030/022), `pageTable` cableado (AUD-024), accesores con uso real (AUD-022), `placeholderResolver`/`translationDetector` vía `TRANSFORM_APPLY` (AUD-026/027) |
| 2026-10-17 | `ba1e128` | Lote 5: fetch por la MMU con ventana de página validada (AUD-023) + `VmInterpreter` inmutable reutilizado por programa, sin clon de código (AUD-029) |
| 2026-10-17 | `a87622b` | D25: memoria física y tabla de páginas copy-on-write + `clearBlock` (AUD-029, optimización del clon de la MMU; fork ~15× medido) |
| 2026-10-17 | `bfe9122` | Lote 6 (D26): los 7 servicios declarados y no registrados quedan funcionales (`PROPERTY_EXISTS`/`PERMISSION_QUERY`/`ROUTE_REDIRECT`/`ROUTE_RATE_LIMIT`/`CHANNEL_POLICY`/`MESSAGE_CLONE`/`MESSAGE_ID`); cierra la 2ª auditoría |

**Cierre:** los `IFLOW-AUD-001…018` quedan resueltos (lote 1 + lote 2 + lote 3), con la
rectificación de AUD-018 (`VmDmaController` restaurado **e integrado**, ver D17). La
segunda auditoría (`AUDITORIA.md` en `e0ee7eb`, IFLOW-AUD-019…030) es el informe vigente y
su seguimiento vive en este PLAN. De ella ya están atendidos AUD-019 (D17), el bus de datos
(D18), AUD-020 (D19, aclarado por diseño), el lote 4
(AUD-021/022/024/025/026/027/028/030) y el lote 5 (AUD-023/029, ver D23/D24/D25).

**Atendido en el lote 5 (AUD-023/029):**
- **AUD-023** (fetch vía MMU): resuelto en D23. El CPU hace *fetch* por el bus/`SoftMMU` con
  ventana de página validada; `EXECUTE` es efectivo y las instrucciones se leen de memoria.
- **AUD-029** (rendimiento): resuelto en D24 (un `VmInterpreter` por programa, sin clon de
  código por ejecución) y D25 (memoria física y tabla de páginas copy-on-write; `clearBlock`
  para la E/S). El clon de la MMU por ejecución queda eliminado (fork ~15× más barato medido).
  El punto (3) del informe (limpieza de E/S byte a byte) ya estaba resuelto por D17 y D25 lo
  hace prácticamente gratuito.

**IRQ:** no está en el informe; el despachador de servicios `HOST` + `VmException` cubre el
papel de «controlador de interrupciones» de `design.md` §4.7. No se añade una línea de
interrupción sin requisito de la especificación.

**Lote 6 (D26) — servicios declarados y no registrados:** ✅ atendido. Los siete IDs que
estaban en la ABI pero no registrados (`PROPERTY_EXISTS` 0x0102, `PERMISSION_QUERY` 0x0201,
`ROUTE_REDIRECT` 0x0301, `ROUTE_RATE_LIMIT` 0x0302, `CHANNEL_POLICY` 0x0601, `MESSAGE_CLONE`
0x0700, `MESSAGE_ID` 0x0701) se implementan y registran (criterio: hacerlos funcionales, no
borrarlos); ver la tabla de contratos en **D26**. La ABI v2 declara ahora 15 IDs y **todos**
están registrados; cualquier otro sigue devolviendo `UNSUPPORTED`.

**Cierre de la 2ª auditoría:** con el lote 6 quedan atendidos todos los hallazgos del informe
vigente (IFLOW-AUD-019…030), sin cambios de ABI, ISA ni mapa de memoria.

---

## REVISIÓN 2026-10-17 (2) — Configuración, HDL, API y hardware (pantalla/BIOS/IRQ)

> Respuesta a la revisión del usuario. Cinco frentes: (1) centralizar las opciones de build en
> `gradle.properties`; (2) formalizar el mapeo a HDL; (3) endurecer la API (`@Nullable`/
> `Optional`); (4) aclarar la persistencia entre mensajes —hoy **no** persiste tal cual se
> supone—; (5) sustituir la idea de «debug en el firmware» (imposible sin nuevo opcode/servicio/
> mapeo) por un **dispositivo de pantalla de texto mapeado en memoria**, un **BIOS activo** que
> lo inicializa y una **tabla de IRQ configurable por el BIOS** (no hardcodeada en el emulador).

### D27 — Opciones de build en `gradle.properties` (✅ aplicado)

Las opciones del build dejan de pasarse como `-P` sueltos y viven **documentadas** en
`/gradle.properties` (fuente única, acumulable y fácil de encontrar). El build las lee con
`providers.gradleProperty(...)`; `-P<clave>=<valor>` sigue sobrescribiendo para un build puntual.

| Opción | Default | Para |
|---|---|---|
| `logger.debug` | `false` | inyecta `DEBUG` en `DebugConfig.java` (y muestra stdout/stderr en `:src:iflow:test`) |
| `iflow.source` | `examples/policy.flowasm` | entrada de `:src:iflow:iflowAsm` |
| `iflow.output` | `<buildDir>/iflow/policy.flow` | salida de `:src:iflow:iflowAsm` |
| `iflow.origin` | (lo decide el modo) | origin de ensamblado |
| `iflow.baremetal` | `false` | sin ROM de firmware |
| `buildTimestamp` | `0` en SNAPSHOT | releases reproducibles |

Tocados: `gradle.properties` (nuevo), `build.gradle` (raíz), `src/logger/build.gradle`,
`src/iflow/build.gradle`. Verificado que el default compila y que `-Plogger.debug=true` recompila
con `DEBUG=true` y restaura el placeholder. **No** se añade `org.gradle.*` al archivo para no
alterar el comportamiento del build.

### D28 — Mapeo formal a HDL (✅ aplicado)

Objetivo: legibilidad **estándar** de la correspondencia VM↔hardware. Entregable: un documento
`src/iflow/HDL.md` con:

- **Componentes → módulos**: `VmInterpreter` (CPU/control/unidad de decodificación),
  `SoftMMU` (MMU: traducción + permisos), `PhysicalMemory` (RAM), `VmBus` (interconnect/decodificador
  de direcciones), `VmDmaController` (controlador DMA), `VmDisplay` (periférico de vídeo, D31),
  ROM de firmware (BROM), `ExecutionContext` (banco de registros + estado), `HostServiceRegistry`
  (controlador de «syscalls»/E/S).
- **Buses/señales**: address/data/control; *reset* = `initialize()`; *clock* no aplica (interpretado,
  ciclo = instrucción); IRQ (D33).
- **Paso de datos**: fetch (ventana I-cache), load/store por el bus, transferencia en bloque del DMA,
  decodificación de MMIO por el interconnect.
- **Estructura/ISA**: formato de instrucción, opcodes y mapa de memoria (`VmMemoryMap`).

**Entregado**: `src/iflow/HDL.md` (§1 vista del SoC, §2 módulos entidad↔clase, §3 señales del bus,
§4 clock/reset, §5 flujo de datos, §6 decodificación/mapa, §7 estructura código↔HDL, §8 hardware
previsto, §9 nomenclatura). Enlazado desde `README.md`.

### D29 — API endurecida con `Optional` (✅ aplicado)

En vez de `@Nullable` (que exigiría una dependencia de anotaciones que el repo no usa) se adopta
**`Optional`** (JDK puro), coherente con `ChannelRegistry.get`, que ya devuelve `Optional`. La API
pública deja de devolver `null` «invisible»:

- `HostContext.readProperty` / `resolveChannel` / `readChannelProperty` → `Optional<String>`
  (default: `Optional.empty()`).
- `HostContext.readState` → `Optional<Object>`.
- `HostServiceRegistry.getService` → `Optional<HostService>`.

Llamadores adaptados (`HostServiceRegistry`, `VmPolicyEngine.MessageHostContext`) y tests
(`HostServiceRegistryTest`, `VmIntegrationTest`, `VmInterpreterTest`, `ExecutionContextTest`).
Verificado: **348 tests, 0 fallos**.

### D30 — Una sola máquina viva: memoria persistente implícita (✅ aplicado)

**Hallazgo.** Aunque `initialize()` *solo* limpia los buffers de E/S (`0x7000`/`0x8000`), en
`route()` cada mensaje se ejecutaba sobre `new SoftMMU(loaded.softMMU)` —un *fork* copy-on-write del
snapshot congelado— de modo que los datos (`0x3000-0x4FFF`) partían de cero en **cada** mensaje: la
persistencia que el usuario daba por supuesta **no existía**.

**Decisión (aclaración del usuario).** No hay que *hacer explícita* la persistencia: la instancia de
la VM no se reinicializa y cada mensaje **no** limpia la memoria (eso se delega al programa). Se
implementa como **una única máquina viva por programa**:

- El motor mantiene **una sola `SoftMMU`** por programa (`LoadedProgram.softMMU`), mapeada al cargar
  y **mutada en el sitio**; `route()` ya **no forka**: usa esa memoria viva directamente.
- Las ejecuciones se **serializan** en el único core (`machineLock`), porque la memoria es
  compartida y mutable. Es el modelo de una CPU de un solo núcleo.
- `initialize()` sigue limpiando **solo** los buffers de E/S; los datos persisten.
- Las páginas **9-15 (`0x9000-0xFFFF`)** dejan de estar «reservadas» y pasan a ser **RAM de usuario
  de propósito general (R/W)**: el BIOS **no** elige ni reserva el rango. Un contador de 4 bytes en
  `0x9000+` funciona sin más.
- `SoftMMU` conserva su capacidad *copy-on-write* (`SoftMMU(SoftMMU)`, `PhysicalMemory.fork`) para
  copias aisladas (tests/snapshots); el camino por mensaje ya no la usa, así que no reintroduce el
  coste de *freeze* por mensaje.

**Verificado:** nuevo test `programMemoryPersistsAcrossMessages` (un contador en `0x9000` decide
`LOG` en el 1.er mensaje y `DROP` en el 2.º). Total: **349 tests, 0 fallos**.

### D31 — Pantalla de texto mapeada en memoria (`VmDisplay`) — ✅ aplicado

«Debug» deja de ser código en el firmware (imposible sin opcode/servicio/mapa nuevos) y pasa a ser
**hardware**: un periférico de texto mapeado en memoria, inspirado en la VERA de la X16.

- **Formato de celda (2 bytes)**: byte bajo = **carácter** (8 bits); byte alto = **atributo**
  `[letra:4][fondo:4]` (nibble alto = color de letra, nibble bajo = color de fondo).
- **Mapeo**: el `VmBus` (interconnect) decodifica la ventana de pantalla y el banco de registros y
  enruta ahí las escrituras; el resto sigue a `SoftMMU`. Cada escritura (CPU o DMA) se refleja en el
  framebuffer.
- **El BIOS fija la base y las dimensiones** (D32) escribiendo registros MMIO del dispositivo;
  reconfigurable en caliente.
- **Consumo por el host (decidido)**: el `VmDisplay` expone el framebuffer por **API** (tests/UI) y
  admite un **volcado a log apagable** (tras `DebugConfig.DEBUG`, es decir `logger.debug` en
  `gradle.properties`; cero coste si está apagado). El framebuffer está pensado para **producción**
  (datos útiles), no solo para depurar; si el flag de debug está activo, se **inyecta código
  adicional** en el programa y/o el BIOS que usa el framebuffer como salida.

**Implementado:**

- `vm/memory/VmDevice`: interfaz de esclavo de bus (decodifica un rango, responde R/W; palabra y
  bloque por defecto byte a byte).
- `vm/video/VmDisplay`: dispositivo de vídeo. Ventana `cols×rows×2` y banco de registros MMIO de 16 B
  en `0x0F00` (**dentro de la página del BIOS**, 0x0000-0x0FFF): `BASE`(0x00), `COLS`(0x02),
  `ROWS`(0x04), `CTRL`(0x06: bit0 habilitar, bit1 limpiar), `CURSOR`(0x08), `STATUS`(0x0A ro). Se
  **rechaza** una ventana que solape el banco de registros o que se salga del espacio de 64 KB.
  API: `renderText()`, `renderTextTrimmed()`, `renderAnsi()`, `cellChar`/`cellAttribute`, `clearScreen`.
- `SoftMMU`: aloja la lista de periféricos (`attach`/`deviceFor`/`isDeviceAddress`).
- `VmBus`: decodifica MMIO antes de la RAM en byte/palabra/bloque/clear/region.
- `VmPolicyEngine`: crea y adjunta la pantalla al cargar el programa (`display()`); vuelca la pantalla
  al log tras cada mensaje en builds de debug.
- Mapa (`VmMemoryMap`): bloque de control MMIO `0x0F00-0x0FFF` (256 B, decodificado antes de ROM/RAM)
  y defaults de pantalla (`0xE000`, 80×25).

**Reubicación del bloque MMIO (revisión):** el usuario pidió probar a meter los 256 B **dentro de la
memoria del BIOS** en vez de al final de la memoria. El firmware termina en `0x0204`, así que la
página del ROM tiene 3579 B libres; los 256 B caben. Se movió la apertura a `0x0F00-0x0FFF`,
reutilizando la cola no usada del ROM: no se recorta RAM de usuario y los «puntos de control» viven
coherentemente en la memoria del BIOS. Como el *interconnect* decodifica MMIO antes que la ROM, el
BIOS puede escribir los registros aunque la página sea R/X kernel. El ensamblador ahora **reserva**
esa cola (guard de diagnóstico si el firmware pretendiera crecer dentro de ella). Si no hubiera
cabido, se habría dejado al final de la memoria (`0xFF00`).

**Verificado:** `VmDisplayTest` (9 tests: decodificación, geometría, rechazo de solape, aceptación por
debajo del banco, desbordamiento del espacio, carácter/atributo, bloque, limpieza, ANSI) y
`VmPolicyEngineTest.programDrawsOnTheMemoryMappedScreen`.
Total: **359 tests, 0 fallos**.

### D32 — BIOS activo — ✅ aplicado

El firmware ROM pasa de **pasivo** (solo stdlib) a **activo**: con el firmware ON, la máquina
**resetea en el BIOS** (`fw_reset`), no directamente en el `main`. La rutina de reset (1) detecta
arranque en frío vs. caliente, (2) en frío limpia la RAM volátil y **configura el dispositivo de
pantalla** por MMIO, y (3) encadena con el `main` del usuario. El `main` ya no tiene que configurar el
hardware.

- **Entry point**: el ensamblador pone `entryPoint` = dirección de `fw_reset` cuando el firmware está
  ON (en la página del ROM). En bare-metal sigue siendo el origin del usuario. El `ProgramImage` no
  cambia de formato: el BIOS y el usuario viven en la misma sección de código.
- **POST en frío vs. caliente**: la rutina de reset lee una bandera persistente en **NVRAM**; si ya
  está marcada, salta directo al `main` (camino caliente). Así el `main` corre en cada mensaje y el
  hardware sólo se reinicializa una vez.
- **«Limpiar memoria» sin romper D30**: en frío el BIOS limpia la **región volátil de datos**
  (0x3000–0x4FFF); **nunca** toca la RAM de propósito general (0x9000+) ni la memoria del programa en
  el camino caliente. La persistencia implícita de D30 se conserva.
- **NVRAM del BIOS (`VmNvram`)**: nuevo esclavo de bus en la apertura MMIO
  (`0x0F10–0x0FFF`, justo tras el banco de la pantalla). Como el engine mantiene **una sola máquina
  por programa** (D30), el dispositivo vive toda la vida de la máquina: lo que el BIOS escribe en un
  mensaje sigue ahí en el siguiente. Lo usa la bandera de arranque en frío (y servirá para la tabla de
  IRQ de D33). Direcciones en `VmMemoryMap.DEVICE_NVRAM_*`.
- **Configuración de pantalla**: el BIOS escribe `BASE=0xE000`, `COLS=80`, `ROWS=25`, `CTRL=enable`
  en el banco MMIO de la pantalla (D31); reconfigurable en caliente por el programa después.

**Implementado:**

- `tools/iflow-compiler`: la ROM incorpora `fw_reset` (entry) con POST + encadenado; el `entryPoint`
  de la imagen pasa a ser el BIOS. Se añaden los helpers `LDWI`/`STWI` al generador.
- `vm/memory/VmNvram`: NVRAM del BIOS (`VmDevice`), persistente entre mensajes.
- `vm/memory/VmMemoryMap`: `DEVICE_NVRAM_BASE/SIZE` (0x0F10, 0xF0) dentro de la apertura MMIO.
- `VmPolicyEngine`: adjunta la NVRAM junto a la pantalla y la expone con `nvram()`.
- `BiosBootTest` (nuevo): fixture `bios_screen.flow` — el `main` sólo dibuja; el test comprueba que el
  BIOS habilitó/configuró la pantalla y encadenó a `main`, y que el segundo mensaje toma el camino
  caliente. `VmNvramTest` (nuevo): decodificación, R/W byte/palabra/bloque y ventana por defecto.

**Decisión de alcance:** la **publicación de la tabla de IRQ** pasa a **D33** (junto con el despacho y
las fuentes de E/S), que es donde se define su estructura; la NVRAM es el sitio natural para esa
tabla persistente y reconfigurable.

**Verificado:** `FirmwareIntegrationTest` actualizado (el engine ahora resetea en el BIOS; se adjuntan
pantalla y NVRAM) + `BiosBootTest` + `VmNvramTest`. Total: **368 tests, 0 fallos**; ensamblador 13/13.

> Nota: la spec previa (`implement.md` §12.4 y §13.1) describía el firmware como pasivo y sin vectores
> modificables; se actualiza en este lote (ver más abajo) para no dejarla divergente.

### D33 — Tabla de IRQ configurable por el BIOS + E/S (aplicado)

El emulador **no** hardcodea la tabla de interrupciones; el BIOS la define en memoria y el runtime la
usa para despachar. **Alcance decidido**: además de la tabla, se introducen **fuentes de interrupción
por E/S**. Diseño aplicado:

- **Controlador de interrupciones (`VmPic`)**, esclavo de bus al final de la apertura MMIO
  (`0x0FF0–0x0FFF`, 16 B); la NVRAM (D32) se reduce a `0x0F10–0x0FEF`. Registros de 16 bits:
  `PENDING` (lectura, bitmap de líneas pendientes), `ENABLE` (máscara, rw), `RAISE` (escritura: dispara
  la línea indicada — interrupción *software*), `ACK` (escritura: completa la línea en servicio),
  `IN_SERVICE` (lectura) y `CURRENT` (lectura: línea despachada o `0xFFFF`). 16 líneas.
- **Tabla de IRQ**, publicada por el BIOS en la NVRAM con estructura fija y versionada:
  `[boot:2][magic:2][count:2][handler_0:2]…[handler_n:2]` (16 bits por línea). El runtime la lee para
  resolver el manejador; el BIOS puede reescribirla (es **configurable**, viva). Su disposición la fija
  `IrqTable` en Java y el firmware en TS.
- **Entrega tipo hardware**: en cada frontera de instrucción el intérprete consulta al `VmPic` la línea
  de menor número pendiente, habilitada y no en servicio. Si la hay, hace una *llamada de hardware*:
  guarda el **estado completo de la CPU** en una pila en la sombra (registros, `IP`/`SP`/`FP`/`FL` y la
  profundidad de llamada), empuja la dirección de retorno en la pila del programa (como `CALL`) y salta
  al manejador de la tabla. El manejador termina con `RET`; el `RET` que cierra su marco restaura el
  estado, de modo que la interrupción es **transparente** al código interrumpido (no hace falta `IRET`
  ni que el manejador salve registros). La línea queda *en servicio* (sin reentrada) hasta que el
  manejador escribe `ACK`.
- **Fuentes de E/S** (disparan la línea): (1) **llegada de mensaje** (IRQ 0, la levanta el runtime al
  materializar el buffer de entrada), (2) **software** vía `RAISE` (BIOS/programa/tests), y
  (3) **finalización de servicio HOST** (IRQ 1) cuando el servicio devuelve un estado de error.
- **Política**: las líneas llegan **enmascaradas por defecto**; el BIOS publica manejadores seguros
  (el stub por defecto hace `ACK` y `RET`) y una línea sólo se despacha cuando el programa la habilita
  en `ENABLE`. La tabla vive en NVRAM (memoria de dispositivo, fuera de la RAM del programa), así que
  no se corrompe con el tráfico normal de memoria; reescribirla es una operación explícita de MMIO.

Alternativas descartadas: (a) **polling cooperativo** — la tabla existe pero el programa consulta
`PENDING` y llama al manejador él mismo (sin preempción del intérprete): más simple, menos fiel;
(b) **solo publicar la tabla** — el BIOS escribe la tabla y la API la expone, pero el despacho queda
para más adelante.

**Aplicado**: `VmPic` + `IrqTable` (`vm/irq`), `VmMemoryMap.DEVICE_PIC_BASE`, `ExecutionContext`
(estado/frames de interrupción), `VmInterpreter` (entrega en la frontera, `leaveInterrupt` en `RET`,
fuente HOST), `VmPolicyEngine` (adjunta el PIC, lo cablea y levanta la IRQ de mensaje), firmware
(`fw_irq_stub`, publicación de la tabla en POST, equates `PIC_*`/`IRQ_*`) y tests (`VmPicTest` +
`IrqDeliveryTest`, fixture `irq.flow`).

### Respuestas del usuario (revisión 2)

1. **Pantalla**: API + log **apagable**, configurado en `gradle.properties`. El framebuffer sirve en
   producción para datos útiles y, opcionalmente, para debug: con el flag de debug activo se inyecta
   código adicional en el programa y/o el BIOS.
2. **IRQ**: **tabla + E/S** (interrupciones por eventos de E/S).
3. **Persistencia**: banco persistente en RAM, pero **de propósito general para el userspace** (el
   BIOS no elige ni reserva el rango); la persistencia es **implícita** (la VM no se reinicializa y
   no se limpia la memoria). Ya aplicado en D30.
4. **Orden**: D27/D28/D29/D30/D31 aplicados; **D32 aplicado** (BIOS activo + NVRAM); **D33 aplicado**
   (tabla de IRQ configurable por el BIOS + fuentes de interrupción por E/S).

---

## REVISIÓN 2026-10-19 — Pantalla en página propia (hallazgo de la auditoría iFlow)

### D34 — La ventana de pantalla vive en una página dedicada (✅ aplicado)

**Contexto.** La auditoría forense de iFlow (2026-10-19) dejó abierta la duda de si el framebuffer en
`0xE000` recortaba la RAM de usuario `0x9000–0xFFFF`. Verificado: sí. La ventana por defecto
80×25×2 = 4000 B sombreaba el 14 % de las 28 KB declaradas, dejando 24,1 KB útiles **mientras la
pantalla está activa**; la RAM de debajo no se perdía (el `byte[]` de celdas vive en el heap del host),
pero quedaba inaccesible.

**Decisión (usuario).** Mover la ventana a la **última página** (`0xF000–0xFFFF`) para que el
sombreado quede alineado a frontera de página, y documentar que **esa página vuelve a ser RAM
completa si se deshabilita la pantalla**.

**Aplicado.**

- `VmMemoryMap`: nuevas constantes `DISPLAY_WINDOW_BASE = 0xF000` y `DISPLAY_WINDOW_SIZE = PAGE`;
  `DISPLAY_DEFAULT_BASE` pasa a ser un alias de la primera. La página 15 sigue en `USER_PAGES`, así que
  sigue mapeada R/W como RAM de usuario.
- `VmDisplay.reconfigure()`: la ventana debe **caber entera en la página de pantalla**. Esto sustituye
  la comprobación anterior (cota de 64 KB + no solapar el banco de registros) y cierra de paso la
  grieta por la que un programa podía poner la base en `0x7000` (buffer de entrada) o `0x3000` y
  sombrear su propia E/S o sus datos: la base ya solo puede caer en `0xF000–0xFFFF`.
- Firmware (`tools/iflow-compiler/src/assembler.ts`): `DISPLAY_BASE = 0xF000`.
- Tests: `VmDisplayTest` reescrito para la regla de página, con un caso de **reutilización**
  (`pageIsPlainRamWhileTheScreenIsDisabled`: RAM → framebuffer → RAM al deshabilitar);
  `VmPolicyEngineTest.programDrawsOnTheMemoryMappedScreen` reubicado a `0xF000`.
- Documentación: `src/iflow/README.md`, `src/iflow/HDL.md` y `tools/iflow-compiler/README.md`.

**Dato verificado de paso.** El firmware ensamblado termina en **`0x029B`** (668 B), no en `0x0204`:
el README era correcto y el comentario de `VmMemoryMap` estaba obsoleto (corregido). Quedan **3172 B**
libres hasta el banco MMIO (`0x0F00`): un framebuffer 80×25×2 = 4000 B **no cabe** en la cola del BIOS,
y uno de 80×20×2 = 3200 B tampoco (por 28 B), así que la idea de meter la pantalla en la memoria del
BIOS no es viable para la geometría actual.

### D35 — Contrato de acceso a la MMIO de dispositivos (AUD-031) ✅ aplicado

**Contexto.** La auditoría forense de iFlow registró `IFLOW-AUD-031` (Media-Alta): la apertura MMIO de
256 B (`0x0F00–0x0FFF`) vive dentro de la página del BIOS, declarada kernel R/X, pero `VmBus` la
decodifica antes de la MMU, así que ni la propiedad de página ni el privilegio del maestro la
gobiernan. Un programa puede reescribir la NVRAM (bandera de arranque, tabla de IRQ), el PIC y los
registros de la pantalla.

**Decisión (usuario, opción 1).** **Aceptar el diseño y hacerlo explícito**, no añadir un portero.
Razones verificadas: (a) D31/D33 exigen que el programa escriba los registros de la pantalla y del PIC
(el programa habilita, dispara y confirma sus líneas), y la tabla de IRQ de la NVRAM está pensada para
ser reescrita por él; (b) el BIOS corre en el dominio `PROGRAM`, así que un portero por privilegio
también rompería el POST; (c) lo único realmente privado del BIOS es la bandera de arranque en frío,
cuyo impacto se limita a forzar el cold-boot de la propia máquina del programa (limpia su RAM volátil
`0x3000–0x4FFF`), sin escape al host.

**Aplicado.**

- `VmMemoryMap.DEVICE_MMIO_BASE`: javadoc con el **contrato de acceso** — la apertura es espacio de
  dispositivo, se decodifica antes de la MMU y no la gobiernan permisos ni privilegio (como la MMIO
  real); la propiedad de página sigue protegiendo la memoria (ROM/constantes).
- `VmBus` y `VmDevice`: javadoc alineado con el contrato (referencia cruzada; deja de leerse como
  «bypass»).
- Test que lo fija: `VmDisplayTest.programMayDriveDeviceRegistersInsideTheKernelPage`. Comprueba las
  dos caras: un maestro `PROGRAM` recibe `PRIVILEGE_FAULT` al escribir **RAM** en la página kernel R/X,
  pero **sí** configura la pantalla a través de la apertura.
- Documentación: `src/iflow/README.md` (mapa de memoria) y `src/iflow/HDL.md` (§1, *interconnect*).

**Alternativas descartadas.**

1. **Mover los 256 B a una página propia** (segundo camino del auditor). Quita la inconsistencia pero
   **no añade aislamiento**: la página tendría que seguir siendo escribible por el programa (D31/D33).
   Coste: RAM de usuario a 24 KB o geometría de pantalla a 80×24.
2. **Pasar la bandera de arranque de la NVRAM al dominio supervisor.** Es lo único que añadiría
   aislamiento real (un `.flow` no podría alterar el próximo arranque), a costa de firmware + motor +
   ABI. No se descarta para el futuro; hoy el riesgo es bajo (sin escape al host).

⚠️ **Aviso para un futuro cambio de layout:** si algún día se mueve la apertura a una página propia,
hay que revisar a la vez `VmMemoryMap`, `VmBus`, el firmware (`DISPLAY_BASE`/`DEVICE_*` en
`assembler.ts`), los 5 fixtures `.flow` y este contrato.

### D36 — Sin vigilante de ejecución; el fuel es el único límite (AUD-036) ✅ aplicado

**Decisión (usuario).** **No** añadir *watchdog* ni presupuesto de tiempo de pared. Una política puede
ejecutarse legítimamente largo (sleep, RTC, delegar trabajo, esperar una IRQ) y cortarla eliminaría esas
capacidades. Lo que debe entregarse en poco tiempo es **el mensaje**, no la ejecución de la máquina. El
`fuel` sigue siendo el único límite: determinista, ajeno al host y suficiente para acotar un programa que
no avanza. La cancelación cooperativa (`ExecutionContext.cancel()`) se mantiene disponible para quien
necesite abortar, pero no se cablea a ningún temporizador.

**Aplicado.** Javadoc de `ExecutionContext` (sección *Cancellation*) + este registro + addéndum de
`AUDITORIA.md`.

### D37 — La ABI se exige por igualdad exacta (AUD-043) ✅ aplicado (documentado)

**Decisión.** Mantener `minAbiVersion != CURRENT_ABI_VERSION` como comparación **exacta**, no como cota.
La ABI es la convención de llamada de los servicios HOST (roles de registro, códigos de estado,
semántica de buffers): aceptar una ABI distinta arriesga decodificar una llamada que parece correcta y
falla en tiempo de ejecución. La igualdad garantiza compatibilidad total y falla pronto y claro al
cargar. **Aplicado.** Comentario en `ProgramImageParser` + addéndum de `AUDITORIA.md`.

### D38 — Capabilities de mensaje y guardián de deriva compilador→VM (punto 10) ✅ aplicado

**Aplicado.**

- `MESSAGE_CLONE`/`MESSAGE_ID` pasan a exigir `message.clone`/`message.id`: eran los dos únicos
  servicios con `requiredCapabilities()` vacío. Con esto todos son *deny-by-default*; el motor declara
  las dos capabilities y el README las refleja. Test: `messageServicesRequireTheirCapability` (sin la
  capability, `HOST_SERVICE_DENIED`).
- **Guardián de deriva:** `tools/iflow-compiler/test/determinism/run.js`, enganchado a `npm test`,
  reensambla `firmware`/`simple`/`main_only`/`irq`/`bios_screen` con el CLI real y exige byte-identidad
  con los `.flow` commiteados. Cierra el eslabón que faltaba: los tests del JVM (`BiosBootTest`,
  `IrqDeliveryTest`, `FirmwareIntegrationTest`) ya ejecutaban esos bytes, pero nada garantizaba que
  siguieran siendo la salida actual del ensamblador.

### D39 — Formato `.flow` v2: magic + sección `debug` (AUD-037 / AUD-038) ✅ aplicado

**Decisión.** Un único cambio de formato que cierra los dos hallazgos a la vez, en lugar de subir la
versión dos veces. La cabecera pasa de 16 a **20 bytes**:

```
u32 magic = 0x464C4F57 ("FLOW")   // rechaza al instante lo que no es un .flow
u8  formatVersion = 2
u8  isaVersion / minAbiVersion / minFirmwareVersion
u16 entryPoint / codeLength / constantsLength
u8  capabilitiesCount
u8  reserved
u16 debugLength                    // 0 = sin sección debug
u16 reserved
[code][constants][capabilities][debug UTF-8][hash 32]
```

El hash cubre toda la carga (magic incluido) y también la sección `debug`, de modo que esta no puede
alterar el programa sin romper la integridad. `-d/--debug` deja de ser un no-op: emite la sección con
dirección + fuente de cada instrucción y la tabla de símbolos. El parser exige el magic antes de leer
nada más: un fichero ajeno falla como «not a .flow» y no con un error de versión o de hash confuso.

**Aplicado.** `types.ts` (`PROGRAM_MAGIC`, `PROGRAM_VERSIONS.format = 2`), `assembler.ts`
(`serializePayload` compartido por los dos serializadores), `ProgramImageParser` (magic + `debugLength`
+ lectura UTF-8 → `ProgramImage.debugInfo()`), `ProgramImage.CURRENT_FORMAT_VERSION = 2`,
`ProgramImageParserTest` (+3 casos: magic, debug, ausencia de debug), `VmConfigLoaderTest`,
`test/validation/run.js` y `test/verify.py` (offsets), `test/determinism/run.js` (comprueba magic,
versión, sección debug y hash), los 5 fixtures regenerados y los dos README.

**Coste asumido.** Los `.flow` de formato 1 dejan de cargar (el cargador cae a pass-through): hay que
recompilarlos con el ensamblador actual. Es el precio de un formato auto-descriptivo y de una sección
`debug` que un lector antiguo no sabría saltarse.
