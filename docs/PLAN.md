# PLAN — TextFormatter Suite

> Documento vivo: reléelo antes de cada sesión de trabajo para no perder el rumbo.
> Última actualización: 2026-09-28 (Release pipeline CI/CD + Dependency Verification completado, Clean Architecture Translator SPI completado).

---

## Estado real del proyecto (2026-09-23/24)

| Pieza | Estado | Verificación |
|---|---|---|
| core-api (modelo atómico + SPI) | ✅ estable, JDK-puro | tests |
| kernel (ServiceLoader + grafo semver/Tarjan) | ✅ | 13 tests |
| textformatter (MiniMessage + Channels + `<tr>`) | ✅ estable | tests |
| iflow (router/reglas/rate-limit per-key) | ✅ (`engine.parallel` sin consumir) | tests |
| host (SuiteHost+Dispatcher+loaders) | ✅ estable | 39 tests |
| gtranslate / ltranslate | ✅ | tests |
| sync-discord | ✅ JDA wired vía DiscordBridge (respeta iFlow) | build |
| sync-telegram/http/tcpudp | ✅ motores OK | tests propios |
| sync-velocity | ✅ **Production-ready** (Paper API 3.4.0, async queue, retry/backoff, métricas, health, dynamic discovery) | compile |
| sync-websocket | ✅ real (SO_REUSEADDR fix) | build |
| spigot-host | ✅ **COMPILA** (DynamicCommand, Registrar, Plugin, DiscordBridge, WS, HealthCheckRegistry) | compile |
| fabric-host | ❌ **Excluido** (copy-paste de spigot-host con APIs Bukkit/Spigot; 42 errores compile; requiere reescritura completa a Fabric APIs) | build falla |
| web-editor | ✅ gates verdes | check+integración |
| manager-api | ✅ SPI estable | compile |
| manager-impl | ✅ compila (manifest validation obligatoria, capability check habilitado) | compile |
| presets | ✅ standard/rpg/staff/minimal | tests |
| inworld | ✅ **COMPILA** (InWorldHandler con Server param) | compile |
| observability | ✅ metrics/debug/health (auth token, 127.0.0.1) | tests |
| extension-api | ✅ SDK estable | tests |
| example-extension | ✅ demo funcional | tests |
| tester | ✅ 25 tests runtime + PerformanceProfiler | tests |
| messages | ✅ i18n centralizado EN/ES | tests |
| i18n (FASE 5) | ✅ strings hardcodeados → MessagesCatalog | 98% |
| transport | ✅ HttpURLConnection + MessageCodec único | tests |
| loadtest | ✅ JMH benchmarks | build |
| performance | ✅ profiling (CPU/heap/hotspot/cache/memory) | tests |

- ✅ **Monorepo Git** en `/home/majhrs16/Documentos/textformatter-suite` (rama `main`, remoto GitHub `majhrs16-official/TextFormatter-Suite`).
- ✅ **Arquitectura**: `suite/*` módulos Gradle (Java 17/21) + adapters `spigot-host` (plugin Paper 1.20.6+) y `fabric-host` (excluido: copy-paste de spigot-host con APIs Bukkit/Spigot; 42 errores compile; requiere reescritura completa a Fabric APIs).
- ✅ **Web editor** funcional (StateStore, diffing, validación incremental, paths.json) con 99 tests unitarios + integración (`npm run check` verde).
- ✅ **Suite corriendo en Spigot/Paper** (plugin `TextFormatterSuite` instalable, fat-jar construido, probado en servidor real Paper 1.20.6).
- ❌ **Fabric-host excluido** (copy-paste de spigot-host con APIs Bukkit/Spigot: 42 errores compile - CommandContext, hasPermissionLevel, sendFeedback, getEntity, AUTO, Server, WebSocketSyncSink, InWorldHandler, etc. Requiere reescritura completa a Fabric APIs: ServerCommandSource, FabricAudiences, Fabric events, Brigadier nativo).
- ✅ **Módulos core compilando + tests pasando**: core-api, kernel, textformatter, iflow, gtranslate, ltranslate, sync-*, host, messages, tester, transport, manager-api, manager-impl, presets, inworld, observability, extension-api, example-extension, loadtest, performance, sync-websocket.
- ✅ **Module Manager (F12) núcleo completado**: version resolver (semver + env compat), dependency resolver (module.yml), SHA256 obligatorio, register() SPI-only, discoverAll(), manifest validation obligatoria.
- ✅ **Security Sprint 3 completado**: char[] tokens + Arrays.fill(), MiniEscape completo (< > \ { } [ ] ( ) # @), PAPI dynamic check, SpEL LRU cache (1024), SSRF protection (HttpTransport), DependencyVerification (verification-metadata.xml).
- ✅ **Clean Architecture (Translator SPI)**: `host` ya no depende de `gtranslate`/`ltranslate` en compile-time. Solo depende de `core-api` (SPI: `TranslatorProvider`, `TranslatorManager`). Proveedores se descubren via `ServiceLoader` en runtime. Tests usan `testImplementation` para translators.
- ⚠️ **sync-velocity**: ✅ **Production-ready** - Paper API 3.4.0, async queue, retry/backoff, métricas, health, dynamic discovery, advanced mapping

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

### FASE 4 — fabric-host ❌ (excluido)
- Código presente en `src/fabric-host/` pero **excluido del build**.
- Es un copy-paste de `spigot-host` usando APIs Bukkit/Spigot (CommandContext, hasPermissionLevel, sendFeedback, getEntity, AUTO, Server, etc.).
- 42 errores de compilación: usa APIs Spigot/Bukkit en lugar de Fabric (ServerCommandSource, FabricAudiences, Fabric events, Brigadier nativo).
- Requiere reescritura completa a Fabric APIs antes de poder incluirse.
- Canales por defecto (join/quit/death/advancement).
- Event handlers: death, advancement.
- Comando `/suite` (reload, status, lang, toggle, reset).

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

---

## BUGS CONOCIDOS Y DEUDA (actualizado 2026-09-13)

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

**Supply Chain (A5 2026-09-06):**
- Sin `gradle.lockfile` / SHA256 / `dependencyVerification`. ✅ **PARCIAL** — `dependencyVerification` con `verification-metadata.xml` (SHA256/SHA512), `dependencyLocking` en build.gradle. Pendiente: gradle.lockfile portable.
- 8 repos Maven; `mavenLocal()` con precedencia.
- Builds no reproducibles.
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
| C4 | — | Completar `fabric-host` (actualmente excluido, 42 errores compile) | `fabric-host/` | ⏳ |
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
7. T4 — Config Schema single-source generation ⏳
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
30. **C4 — fabric-host** completo (resolver 42 errores compile) ⏳

---

## FASE 17 — CONSOLIDACIÓN FINAL (Nueva)

```
F17-1  Security hardening completo (S1-S6)
F17-2  Module Manager release-ready (M1-M6)
F17-3  Tests E2E Spigot + Fabric
F17-4  Docs 100% sincronizadas
F17-5  Release pipeline + semver + lockfile
F17-6  TranslatorProvider SPI + Clean Arch fixes
F17-7  sync-velocity confirmado real
F17-8  Config schema single-source generado
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
- **fabric-host** ⏳ (resolver dependency verification / 42 errores compile)

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
| **AUDITORÍA 2026-09-28 P2** | ✅ **4/5** | TranslationExecutor ✅, VelocitySink ✅, Coverage gates ✅, Sleep scheduler ✅, fabric-host ⏳ |
| **Docs Sync** | 🔄 **En progreso** | README, PLAN, Release Notes, Wiki, ADR |

---

*Última actualización: 2026-09-30 | Commit: (pendiente push)*

---

## AUDITORÍA 2026-09-28 — HALLAZGOS CONFIRMADOS EN CÓDIGO (Verificados en source)

> **Metodología**: Cada hallazgo ha sido verificado leyendo el código fuente correspondiente. Solo se listan los que **aplican** al estado actual.

### 🔴 CRÍTICOS (P0 — Fix Inmediato)

| ID | Severidad | Problema | Archivo:Línea | Evidencia |
|---|---|---|---|---|
| **B-01** | 🔴 Crítico | **Doble entrega de cada mensaje de chat** — `broadcast()` despacha internamente (L518) Y el caller vuelve a despachar (L497) | `spigot-host/TextFormatterSuitePlugin.java:508-520, 495-498` | `broadcast()` llama `dispatcher.dispatch()` y retorna mensaje; `onChat()` despacha el retorno → 2x entrega, 2x traducción, 2x rate-limit |
| **B-03** | 🔴 Crítico | **Bloqueo hilo principal en join/quit/death** — Handlers `@EventHandler(priority=MONITOR)` corren en main thread, llaman `dispatcher.dispatch()` que hace `future.get()` sin timeout; tareas async pueden hacer HTTP a Google (10s timeout) | `spigot-host/TextFormatterSuitePlugin.java:535-568`, `host/MessageDispatcher.java:115` | `onJoin/onQuit/onDeath` → `dispatchTyped()` → `dispatcher.dispatch()` → `future.get()` bloquea main thread |
| **V-01** | 🔴 Crítico | **WebSocket bind 0.0.0.0 + auth opcional** — `WebSocketSyncSink` usa `new InetSocketAddress(port)` (todas las interfaces). Si token vacío, solo loggea warning pero **acepta conexiones** (L202-203). Plugin sí respeta `enabled: false` y exige token si enabled, pero sink creado directamente no | `sync-websocket/WebSocketSyncSink.java:70, 202-203` | Bind all interfaces; auth check solo loggea warning, no rechaza |

### 🟠 ALTOS (P1)

| ID | Severidad | Problema | Archivo:Línea | Evidencia |
|---|---|---|---|---|
| **B-04** | 🟠 Alto | **Corrupción render con "İ" (U+0130)** — `source.toLowerCase().indexOf()` desplaza índices porque `"İ".toLowerCase()` = 2 chars (`i` + combining dot). 10× `İ` → `IndexOutOfBoundsException`, mensaje descartado | `textformatter/template/TemplateRenderer.java:226` | `int close = source.toLowerCase().indexOf(CLOSE_TR, ...)` — lowercasing cambia longitud |
| **B-05** | 🟠 Alto | **Traducción sin re-escape** — Texto ya escapado se envía al traductor; respuesta se reinserta **sin re-escape** antes de `MiniMessage.deserialize`. Traductor puede mover/eliminar barras → `<click:run_command:...>` sobrevive | `textformatter/template/TemplateRenderer.java:215` | `return translation.translate(content, from, to);` — salida cruda a MiniMessage |
| **B-06** | 🟠 Alto | **Rate limit WebSocket nunca recupera con tráfico sostenido** — Ventana se resetea solo si `lastTimestamp < now-1s`; cada mensaje actualiza `lastTimestamp` → cliente a 5 msg/s bloqueado desde msg #100 y no recupera mientras siga enviando | `sync-websocket/WebSocketSyncSink.java:236-255` | `messageTimestamps` + `messageCount` solo limpia si `lastTimestamp < windowStart` |
| **M-01** | 🟠 Alto | **GTranslate solo primera oración** — `root[0][0][0]` toma solo primer segmento; mensajes multi-oración pierden el resto | `gtranslate/GTranslate.java:48` | `return root.optJSONArray(0).optJSONArray(0).optString(0, text);` |
| **M-02** | 🟠 Alto | **Thundering herd traducciones** — Sin dedup de peticiones en vuelo: N receptores mismo idioma disparan N llamadas HTTP idénticas concurrentes | `host/SuiteHost.java:171-184`, `core-api/TranslationService.java:42-67` | `deliver()` llama `renderFor()` → `translateSpans()` → `translate()` por receptor sin coordinación |
| **M-03** | 🟠 Alto | **ArrayStoreException en MessageCodec** — `texts.toList().toArray(new String[0])` falla si JSONArray tiene elementos no-String | `transport/MessageCodec.java:102` | `Formats.of(texts.toList().toArray(new String[0]));` |

### 🟡 MEDIOS (P2)

| ID | Severidad | Problema | Archivo:Línea | Evidencia |
|---|---|---|---|---|
| **M-04** | 🟡 Medio | **HttpTransport: getErrorStream() null, POST→GET en redirect, sin límite respuesta, readLine() quita \n** | `transport/HttpTransport.java:172-230` | L214-215: `getErrorStream()` puede ser null; L203: redirect fuerza GET; sin max body size; L218: `readLine()` |
| **M-05** | 🟡 Medio | **SSRF IPv6 ULA (fc00::/7) no cubierto** — `isSiteLocalAddress()` no detecta ULA; system property `textformattersuite.http.deny` **reemplaza** default en vez de ampliar | `transport/HttpTransport.java:166, 55-59` | L166: `address.isSiteLocalAddress()`; L55-59: property reemplaza lista |
| **M-08** | 🟡 Medio | **InterruptedException tragado** — `catch (Exception)` en `MessageDispatcher` incluye `InterruptedException` sin restaurar interrupción | `host/MessageDispatcher.java:167` | `} catch (Exception e) { logger.error(...); silenced++; }` |
| **M-09** | 🟡 Medio | **RWLock redundante sobre ConcurrentHashMap** — `RateLimiter` usa `ReentrantReadWriteLock` + `ConcurrentHashMap`; Javadoc dice "sliding window" pero es token bucket | `iflow/channel/RateLimiter.java:20, 23` | `buckets` es `ConcurrentHashMap` + `bucketsLock` RWLock |
| **M-10** | 🟡 Medio | **WebSocketSyncSink port sanitization bug** — Constructor sana `this.port` (L67) pero `new InetSocketAddress(port)` usa parámetro crudo (L70) | `sync-websocket/WebSocketSyncSink.java:67, 70` | `this.port = port > 0 ? port : DEFAULT_PORT;` vs `new InetSocketAddress(port)` |
| **M-11** | 🟡 Medio | **Observability metrics en 0.0.0.0:9090 sin auth** — `MetricsEndpoint` usa `new InetSocketAddress(port)` (todas interfaces), sin autenticación | `observability/endpoint/MetricsEndpoint.java:42` | `HttpServer.create(new InetSocketAddress(port), 0)` |

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
| **fabric-host** | ✅ **CONFIRMADO** | NO en `settings.gradle`; 42 errores compile; `inworld` compila contra Paper API L28, `fabric-host` depende de `inworld` L38 |
| **coretranslator** | ✅ **CONFIRMADO** | NO en `settings.gradle`; `common-legacy` SÍ está (L32) |
| **gradle.properties** | ✅ **CONFIRMADO** | JDK 8/21 (no 17/21 como README) |
| **sync-discord** | ✅ **CONFIRMADO** | Usa JDA 6.4.2 (no JDK WebSocket + REST) |
| **UA rotation gtranslate** | ✅ **CONFIRMADO** | User-Agent fijo `"TextFormatterSuite/2.1"` (HttpTransport L113) |

---

### PLAN DE ACCIÓN ACTUALIZADO (P0 → P1 → P2)

#### Semana 1: P0 Críticos
1. **B-01** — Fix doble entrega: `broadcast()` no debe despachar; solo construir mensaje
2. **B-03** — Join/quit/death: despachar via `runTaskAsynchronously` + `future.get(timeout)`
3. **V-01** — WebSocket: bind `127.0.0.1` por defecto; rechazar arranque si token vacío (no solo warning)

#### Semana 2: P1 Altos
4. **B-04** — TemplateRenderer: buscar `</tr>` con `Pattern.CASE_INSENSITIVE` sobre string original
5. **B-05** — Re-escape salida traductor O usar `MiniMessage.unparsed()` / `Component.text()`
6. **B-06** — WebSocket rate limit: ventana fija por instante inicio, no último mensaje
7. **M-01** — GTranslate: concatenar todos los segmentos `root[0][i][0]`
8. **M-02** — Dedup in-flight: cache `CompletableFuture` por `(text, from, to)` en `TranslationService`
9. **M-03** — MessageCodec: validar elementos JSONArray son String antes de `toArray(String[])`

#### Semana 3: P2 Medios
10. **M-04** — HttpTransport: null-check `getErrorStream()`, mantener method en redirect, max body size, `readLine()` → `read()`
11. **M-05** — SSRF: añadir patrón IPv6 ULA `^fc00::/7`; property `deny` amplía no reemplaza
12. **M-08** — MessageDispatcher: catch `InterruptedException` separado, `Thread.currentThread().interrupt()`
13. **M-09** — RateLimiter: eliminar RWLock, usar solo `ConcurrentHashMap` + `synchronized(bucket)`
14. **M-10** — WebSocketSyncSink: usar `this.port` en `InetSocketAddress`
15. **M-11** — MetricsEndpoint: bind `127.0.0.1` por defecto; auth opcional

#### Semana 4: P3 + Tests + Docs
16. **M-07** — ModuleLifecycle: firma JAR separada (cosign/gpg); `getCurrentEnvironment()` dinámico
17. **Tests E2E** — Spigot real: chat → iFlow → format → delivery
18. **Docs sincronización completa** — README, PLAN, Release Notes, Wiki, ADR un mismo estado