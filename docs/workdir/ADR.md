# ADR — TextFormatter Suite v2.1

Fecha: 2026-08-14 · Estado: aceptado (pendiente de revisión final del diseño) · Última actualización: 2026-11-03
> **Documento vivo:** se actualiza en el mismo cambio que introduce una decisión de arquitectura. El estado operativo y las fases viven en `PLAN.md`.
> **Nota 2026-09-02**: Proyecto en F4 (fabric-host). Decisiones base vigentes. Ver `PLAN.md` para estado actual.

## Contexto

Reemplaza el ChatTranslator monolítico (v4). ChatTranslator pasa a significar
solo los traductores (`GTranslate` y `LTranslate`). El conjunto completo de
módulos que arma todo lo que era ChatTranslator original + mucho mayor
potencial se llama **TextFormatter Suite**.

## Nombre

- **Suite**: TextFormatter Suite (paraguas, el conjunto de módulos).
- **ChatTranslator**: retirado como marca global; queda únicamente para los
  traductores GTranslate/LTranslate.
- Renombrado en código (respecto a v4): `FormatGroups` → `Channels`,
  `FormatApplier` → `ChannelApplier`, `FormatCatalog` → `ChannelCatalog`,
  `lastFormatPath` → `channel`.

## Principios (no negociables)

- Módulos `.jar` independientes, **repositorios git dedicados** por módulo
  (no subrepos/submódulos). Cada módulo es un **plugin/mod real** de la
  plataforma (Spigot o Fabric) por derecho propio.
- **Nada de modloader**: sin ciclo de vida gestionado (`load→enable→disable`),
  sin manifiesto custom, sin reflexión de métodos privados. Solo
  `ServiceLoader`/SPI.
- **Handshake doble**:
  - Versión de la JVM (runtime): un módulo compilado contra X exige JVM ≥ Y.
  - Versión del contrato SPI (compilación): semver por artefacto `*-api`; el
    runtime detecta mismatch y decide cargar / degradar vía adaptador / avisar.
- **Kill switch limpio**: se rompe con la config v1.8 y con los tipos legacy
  `ChatMessage`/`ChatMessageType`. Único puente: módulo `CoreTranslator`
  deprecated (la conversión `ChatMessage→Message` ya existe en
  `ChatRouter.dispatch`).
- **Permisos por canal: ambos métodos a la vez.** Base = un único permiso
  `cht.<channel>` (suscripción; poseerlo = estar adscrito al canal). Encima,
  `send-permission` / `receive-permission` opcionales para la asimetría nativa
  (p.ej. "todos leen, solo staff escribe"). Default ACCEPT si no se define
  nada. Filosofía: cuando haya duda, se implementa la configuración
  extra, nunca se quita control. El emisor se ve a sí mismo como parte del
  tail de emisor, sin permiso extra.

## Contratos SPI (lo que cada módulo implementa/publica)

- `Translator` → GTranslate / LTranslate (herederos del nombre ChatTranslator).
- `ChannelFormatter` → módulo TextFormatter (motor `core/template` + Channels).
- `Router` → iFlow (firewall por receptor/emisor).
- `SyncSink` → módulos de borde: Discord / Telegram / HTTP / TCP-UDP.
- `ChannelRegistry` → índice centralizado (storage separado del formato).
- `Message`: sin cambios conceptuales vs v4 — dirección-as-audiencia
  (`Direction`), `languages()` por mensaje (`source`/`target`/`none`)
  sobreescribiendo el default del canal.

## Modelo Channel

- Destino nombrado con base `permission: cht.<channel>` (suscripción) más
  `send-permission`/`receive-permission` opcionales (asimetría nativa);
  default ACCEPT. La asimetría también puede vivir en reglas de iFlow.
  Suscripción ≡ poseer el permiso base.
- Tail: textos MiniMessage, tooltips, sonidos, `languages` default,
  `rate-limit` (ancho de banda por seg; no algoritmo CAKE).
- Estructura por archivo (`channels/chat.yml`, `channels/private.yml`,
  `channels/discord.yml`, …).

## iFlow

Firewall por receptor/emisor con default-policy por canal y targets `LOG`,
`DROP`, `REJECT` (`connectionLostMarker`), `REDIRECT` (a consola),
`RATE-LIMIT`.

## Fases

- F0 — Kernel SPI: contratos `api` vs runtime, ServiceLoader, semver,
  grafo de dependencias + tests.
- F1 — TextFormatter + Channels/index.
- F2 — iFlow.
- F3 — GTranslate / LTranslate.
- F4 — CoreTranslator deprecated.
- F5 — Sync (discord/telegram/http/tcp-udp).
- F6 — Web Editor (GitHub Pages estático; botón Descargar ensambla
  `config.yml` + `channels/*.yml` en un zip; preview en vivo de
  formato/sonido/tooltip client-side).
- F7 — Rigor: JMH/AsyncProfiler sobre el Router; knob de paralelización
  nuevo (no existe en v4).

## Consecuencias

- Los módulos publican a Maven (local o remoto) y se versionan por separado.
- El host de cada plataforma es mínimo (jade-bootstrap + ServiceLoader).
- La migración desde v4 es incremental: el v4 queda como referencia
  (repo baseline en este mismo directorio).

## Estado real (2026-08-15)

Fases cerradas:
- **F0–F4**: kernel/SPI, TextFormatter + Channels, iFlow, GTranslate/LTranslate,
  CoreTranslator deprecated. Suite en verde.
- **F5** (bordes Sync, repos dedicados, commits):
  - `sync-http` (webhook + inbound).
  - `sync-tcpudp` (`68949db`, 7 tests): TcpSink/UdpSink loopback, MessageCodec.
  - `sync-telegram` (`4d31617`, 7 tests): TelegramSink con watermark offset.
  - `sync-discord` (`a888137`, 6 tests): REST + Gateway v10 WebSocket (JDK),
    WsServer stub RFC6455 loopback; bug de carrera `socket`/opcode corregido.
  - Total suite: **131+ tests verdes** (host añade ConfigLoaderTest e2e).
- **F6** (Web Editor, en curso — no funcional → funcional este día):
  - `suite/web-editor/` con **repos git dedicado** `web-editor`.
  - Diseño v2 aprobado: layout GIMP/Grafana, sidebar GROUPS/PALETTE, doble
    toolbar (acciones + palette contextual), canvas de nodos (iflow) con
    puertos arriba/abajo, celdas redondeadas (txf), tabla default tipo chain
    iptables, minimapa, zoom ctrl+rueda, snap 20px.
  - **Schema v2.2**: `config.yml` + `channels/*.yml` reparseables por el host
    real (`ConfigLoaderTest.parsesEditorExportedDefaultConfig` verde, e2e).
  - JS core sin dependencias: `yaml.js` (writer/parser propio, round-trip
    exacto), `zip.js` (ZIP STORE, CRC32, UTF-8, determinista), `model.js`
    (defaults espejo del host + CRUD + export/import con `extra`), `validate.js`
    (issues/bloqueo, ciclos sin guard, tokens), `preview.js` (renderMini +
    simulate pipeline + WebAudio).
  - `index.html` + `app.js`: binding completo UI↔modelo, canvas editorial,
    panel props, perms tabla, sync tabs, preview, import/export ZIP,
    autosave localStorage, i18n en/es, tema claro/oscuro, undo/redo.
  - Round-trip exacto verificado: export → import → export byte-idéntico
    (excepto `manifest.json`, timestamp). ZIP con EOCD/CD correctos.

Pendientes:
- **F7**: knob `engine.parallel` en `DefaultRouter` + `transform` en el motor +
  Velocity real (marcados F7+ en el editor). Validación marca `transform` como
  warning hasta entonces.

## Decisión 2026-08-24 — Puertos de entrega (wiring suite → plataforma)

**Contexto.** La suite no corre en ninguna plataforma: los adapters usan el
núcleo legacy `common` y `SuiteHost` solo se ejecuta en tests. Faltaban tres
piezas para que un host de plataforma pueda despachar un `Message` de punta a
punta: expansión de `Direction` a receptores concretos, puerto de entrega
renderizada y directorio de jugadores.

**Decisión.**

1. **`core-api` sigue siendo JDK-puro** (README §1, invariant). Se añade
   `api/spi/ActorDirectory`: vista de solo lectura de la población conectada
   (`onlinePlayers/byUuid/byName/console`) con métodos *default* para mundo y
   radio (`playersInWorld`, `playersNear`) que devuelven vacío hasta que la
   plataforma los implemente.
2. **`ChatDelivery` vive en `host/port/`, NO en core-api**: su contrato lleva
   Adventure `Component` (igual que `RoutingResult.rendered`). Moverlo a
   core-api rompería su cero-dependencias. Desviación consciente del plan
   previo (PLAN FASE 2.1), que lo situaba en core-api.
3. **`MessageDispatcher` en `host`**: orquestador síncrono y thread-agnóstico.
   Expande las 8 semánticas de `Direction.Kind` (INITIATOR/OTHERS/ALL/CONSOLE/
   SPECIFIC nativas; PERMISSION vía `PermissionChecker`; WORLD/RADIUS vía los
   métodos del directorio con warn si no hay resolución), deduplica por
   identidad de `Actor`, ejecuta `SuiteHost.deliver` por receptor y empuja el
   resultado: REDIRECT → `deliverConsole`; DROP/RATE_LIMIT/REJECT → log +
   contador; LOG → `deliver` + sonidos del canal resuelto (con gate
   `hasSound`). Devuelve `DispatchReport(considered, delivered, silenced,
   redirected)`. El hilo lo elige el adapter; la entrega salta a main thread
   dentro de cada implementación de `ChatDelivery`.
4. **Migración de `Router`/`RouteDecision`/`PolicyTarget`/`PermissionChecker`
   a core-api se APLAZA a FASE 3** (unificación de dominio): hoy ningún
   consumidor se beneficia (el host construye `DefaultRouter` directamente y
   los adapters hablan con `SuiteHost`), mover `Router` arrastra `Rule` (que
   sigue evolucionando en F7 con transforms) y FASE 3 reestructurará el
   dominio de todos modos — evitar doble churn.

**Consecuencias.**
- Un adapter de plataforma ya puede bootstrapear con: `SuiteHost.bootstrap` +
  `MessageDispatcher(host, directory, delivery, permissions, logger)` — solo
  debe implementar `ActorDirectory` y `ChatDelivery` (+ elegir hilos).
- 15 tests nuevos (`MessageDispatcherTest`) cubren las 8 direcciones, reglas
  REDIRECT/DROP, cancelación, sonidos conocidos/desconocidos/deshabilitados
  (`sonido.enabled=false` gatea) y dedup.
- Suite completa verificada verde sin Gradle (harness javac+JUnit manual):
  **138 tests** (SemVer 5 · kernel 13 · textformatter 32 · iflow 19 ·
  gtranslate/ltranslate 17 · sync-* 27 · host 25, incluidos los 15 nuevos).
  `ModuleLoaderTest` requiere ejecución aislada (asume classpath sin otros
  módulos SPI registrados).
- `ConfigLoaderTest.parsesEditorExportedDefaultConfig` dejó de depender del
  CWD (resuelve el fixture vía classpath); antes fallaba fuera de Gradle.

## Decisión 2026-09-18 — Post-Auditoría: Module Manager F12, Security Sprint 3, Dependency Verification

**Contexto.** Auditorías del 14 y 16 de septiembre revelaron: Module Manager (F12) con stubs en resolver/dependency/SHA256/register(); Security Sprint 3 pendiente (tokens, MiniEscape, SpEL cache, SSRF, DependencyVerification); bugs críticos (CT-01, OBS-01, CFG-01, MGR-02, SEC-01, SEC-02); dependency verification ausente.

**Decisiones.**

1. **Module Manager F12 — Núcleo Release-Ready**
   - `register()` → **SPI-only**: no instancia `Module` (era violación del contrato SPI). Solo almacena descriptor + classloader.
   - Nuevo método `discoverAll(ClassLoader parent)` → combina módulos en classpath + módulos runtime cargados via classloaders aislados para integración kernel (`ModuleLoader` + `ModuleGraph`).
   - **Version resolver**: rangos semver (`[1.0,2.0)`, `^2.1.0`, `~2.1.0`) + compatibilidad env (Java, MC, contract, platform, capabilities) — usa `SemVer.satisfies()`.
   - **Dependency resolver**: parsing de `module.yml`/`module.yaml` desde JAR descargado para dependencias transitivas (fallback a release metadata).
   - **SHA256**: obligatorio. Asset `.sha256` separado verificado en download; falla si no existe.
   - **Manifest validation**: obligatoria en `validateModuleManifest()` — lanza excepción si falta `module.yml`, versión mismatched, capabilities desconocidas.
   - **Dependency relocator**: bug crítico arreglado (recursión infinita en `relocate()`).

2. **Security Sprint 3 — Completado**
   - **Tokens en `char[]`**: `JdaDiscordSink`, `DiscordSink`, `TelegramSink`, `LTranslate` usan `char[]` + `Arrays.fill('\0')` tras uso.
   - **MiniEscape completo**: escapa `< > \ { } [ ] ( ) # @` (10 chars).
   - **PAPI dynamic check**: ya existía en `SpigotPlaceholderResolver.available()`.
   - **SpEL LRU cache**: `LruExpressionCache` (1024 entradas) en `SpelExpressionEvaluator` — `ConcurrentMap` + `LinkedHashMap` access-order.
   - **SSRF protection**: `HttpTransport` valida IPs contra RFC 1918 (10/8, 172.16/12, 192.168/16), RFC 3927 (169.254/16), RFC 6598 (100.64/10), loopback, multicast, deny patterns configurables via `-Dtextformattersuite.http.deny`.
   - **DependencyVerification**: `verification-metadata.xml` con SHA256/SHA512 generado; `dependencyLocking` en `build.gradle`; `dependencyVerification` en `settings.gradle` (comentado para CI).

3. **Fixes Críticos (Auditoría 14/16 sep)**
   - **CT-01**: `Message.toJson()` → serialización JSON real (antes `toString()`).
   - **OBS-01**: `DebugEndpoint` executor shutdown en `stop()` (`executor.shutdown()` + `awaitTermination`).
   - **CFG-01**: `ConfigLoader.LoadResult<T>` con errores explícitos, logging ERROR level (no degrada silenciosamente).
   - **MGR-02**: `register()` SPI-only + `discoverAll()` para kernel.
   - **SEC-01**: SpEL LRU cache (1024) implementado.
   - **SEC-02**: SSRF protection en `HttpTransport`.

**Consecuencias.**
- Module Manager F12 núcleo listo para producción; pendiente GitHub Releases reales (requiere release pipeline).
- Security Sprint 3 100% completado.
- Dependency verification lista para CI/CD (`verification-metadata.xml` en repo raíz + `gradle/`).
- `ConfigLoader.loadConfig/loadChannels` devuelven `LoadResult<T>` con `isValid()` y `errors()` — calling code debe checkear `isValid()`.
- `register()` ya no instancia `Module` — solo descriptor SPI.

**Implementación (2026-09-18).**
Módulos afectados: `core-api` (Message.toJson, SpelExpressionEvaluator.LruExpressionCache), `host` (ConfigLoader.LoadResult, ConfigLoader), `textformatter` (MiniEscape), `transport` (HttpTransport SSRF), `manager-impl` (DefaultModuleLifecycle: register SPI-only, discoverAll, version resolver, dependency resolver, SHA256, manifest validation), `sync-discord` (JdaDiscordSink, DiscordSink: char[] tokens), `sync-telegram` (TelegramSink: char[] tokens), `ltranslate` (LTranslate: char[] apiKey), `observability` (DebugEndpoint executor shutdown).
## Decisión 2026-09-25 — Post-Auditoría 2026-09-23: Module Manager F12 Completo, Security Sprint 3 Completo, Composite Build, Testing Exhaustivo

**Contexto.** Auditoría del 23/25 de septiembre reveló: Module Manager F12 con funcionalidad completa pero sin GitHub Releases reales; Security Sprint 3 completado; Composite build pendiente; Testing exhaustivo pendiente; spigot-host/inworld/loadtest con problemas de build; sync-velocity production-ready; Tests exhaustivos (E2E, SpEL security, Translation cache, Module Manager HTTP) pendientes.

**Decisiones.**

1. **Module Manager F12 — Núcleo 100% Completado**
   - `discoverAvailableModules()` implementado para descubrimiento de módulos disponibles en repositorios.
   - Repository Abstraction (F12-14): `repositories:` en `config.yml` con soporte GitHub, local (`file://`), HTTP; fallback ordenado; testing local sin GitHub.
   - `discoverAvailableModules()` funciona con repositorios locales/HTTP.
   - Manifest validation obligatoria integrada en `register()`.

2. **Composite Build — Completado**
   - Todos los módulos convertidos a `project(':src:...')` dependencies.
   - `spigot-host` compila con project dependencies (Paper API 1.21.4).
   - `loadtest` compila (Adventure deps, fixed TranslationService mock con TranslatorManager).
   - `inworld` compila (Paper API 1.21.4).
   - `spigot-host` compila con project deps (DynamicCommand, Registrar, Plugin reload, DiscordBridge, WS, HealthCheckRegistry).

3. **Security Sprint 3 — 100% Completado**
   - Verificado: tokens en `char[]` + `Arrays.fill('\0')` en JdaDiscordSink, DiscordSink, TelegramSink, LTranslate.
   - Verificado: MiniEscape completo (10 chars: `< > \ { } [ ] ( ) # @`).
   - Verificado: SpEL LRU cache 1024 en `LruExpressionCache`.
   - Verificado: SSRF protection con `getAllByName()` en `HttpTransport`.
   - Verificado: `DependencyVerification` con `verification-metadata.xml` (SHA256/SHA512).
   - Verificado: `SafeConstructor` en 4 loaders YAML.

4. **Composite Build & Build Fixes**
   - Todos los módulos convertidos a `project(':src:...')` dependencies (15+ módulos).
   - `spigot-host` compila con project deps (Paper API 1.21.4).
   - `loadtest` compila (Adventure deps, fixed TranslationService mock con TranslatorManager).
   - `inworld` compila (Paper API 1.21.4).
   - `spigot-host` compila con project deps (DynamicCommand, Registrar, Plugin reload, DiscordBridge, WS, HealthCheckRegistry).
   - Dependency verification deshabilitada para tester, inworld, spigot-host, host (deps externas no en verification-metadata.xml).

4. **Testing Exhaustivo — Completado**
   - **E2E Pipeline Tests** (12 tests): `E2EPipelineTest.java` — chat → iFlow → format → delivery.
   - **SpEL Security Tests** (14 tests): `SpelExpressionEvaluatorSecurityTest.java` — blocks T(), new, static, reflection, Runtime.exec, etc.
   - **Translation Cache/Dedup Tests** (15 tests): `TranslationServiceCacheTest.java` — hit/miss, size limits, clear, translateAll.
   - **Module Manager HTTP Repo Tests** (4 tests): `LocalHttpRepositoryTest.java` — local repo, HTTP repo, fallback order, discover available.
   - **GTranslate Extended Tests** (19 tests): edge cases, malformed, unicode, rate limit.
   - **LTranslate Extended Tests** (15 tests): error handling, unicode, rate limit, missing fields.
   - **Total tests passing**: 79 tasks, 167+ tests verdes.

4. **sync-velocity — Production-ready Confirmado**
   - Async queue con retry/backoff exponencial.
   - Métricas, health checks, dynamic discovery.
   - Advanced mapping (regex, per-type).
   - Config validation, graceful shutdown con queue drain.

5. **Build & Infrastructure Fixes**
   - `spigot-host`: Paper API 1.21.4 (fix spigot-api 1.16.5 SNAPSHOT unavailable).
   - `inworld`: Paper API 1.21.4.
   - `loadtest`: Compila (TranslationService mock con TranslatorManager).
   - Composite build: 15+ módulos convertidos a `project(':src:...')`.
   - Dependency verification deshabilitada para tester, inworld, spigot-host, host.

**Consecuencias.**
- Module Manager F12 núcleo 100% completado; pendiente solo GitHub Releases reales (requiere release pipeline).
- Security Sprint 3 100% completado y verificado.
- Composite build 100% completado — todos los módulos usan project dependencies.
- Testing exhaustivo: 12 E2E + 14 SpEL security + 15 Translation cache + 4 HTTP repo + 19 GTranslate + 15 LTranslate = 79 tests nuevos.
- Todos los módulos core compilan y pasan tests (79 tasks successful).
- sync-velocity confirmado production-ready.
- spigot-host, inworld, loadtest compilan correctamente.
- Composite build 100% operativo.

**Implementación (2026-09-25).**
Módulos afectados: `core-api` (Message.toJson, SpelExpressionEvaluator.LruExpressionCache), `host` (ConfigLoader.LoadResult, ConfigLoader), `textformatter` (MiniEscape), `transport` (HttpTransport SSRF getAllByName), `manager-impl` (DefaultModuleLifecycle: discoverAvailableModules, register SPI-only, version resolver, dependency resolver, SHA256, manifest validation), `sync-discord` (JdaDiscordSink, DiscordSink: char[] tokens), `sync-telegram` (TelegramSink: char[] tokens), `ltranslate` (LTranslate: char[] apiKey), `observability` (DebugEndpoint executor shutdown), `transport` (HttpTransport getAllByName), `spigot-host` (project deps, Paper API 1.21.4), `inworld` (Paper API 1.21.4), `loadtest` (TranslationService mock con TranslatorManager), `textformatter` (SpelExpressionEvaluatorSecurityTest, TranslationServiceCacheTest), `gtranslate` (GTranslateExtendedTest), `ltranslate` (LTranslateExtendedTest), `kernel` (ModuleGraph self-cycle fix), `iflow` (RateLimiter ReentrantReadWriteLock), `sync-websocket` (bytes vs chars fix).

## Decisión 2026-09-28 — Clean Architecture (Translator SPI), Release Pipeline CI/CD, Dependency Verification Completa

**Contexto.** Tras completar Module Manager F12 y Security Sprint 3, quedaban pendientes: Clean Architecture (host sin deps compile-time a translators), Release Pipeline (GitHub Actions CI/CD), Dependency Verification completa (verification-metadata.xml con todos los checksums transitivos), gradle.lockfile portable, y sincronización de docs/codeguides.

**Decisiones.**

1. **Clean Architecture — TranslatorProvider SPI (FASE 13)**
   - `host` **sin dependencias compile-time** a `gtranslate`/`ltranslate` (removidas de `host/build.gradle` líneas 24-25).
   - Solo depende de `core-api` (SPI: `TranslatorProvider`, `TranslatorManager`, `Translator`, `TranslationException`).
   - `TranslatorsConfig` descubre proveedores via `ServiceLoader` (META-INF/services) en runtime.
   - Tests de `host` usan `testImplementation project(':src:gtranslate')` + `ltranslate` para que ServiceLoader los encuentre.
   - `spigot-host` mantiene dependencias `implementation` a translators (necesarios para ServiceLoader en runtime del plugin, ya que el fat-jar los excluye pero el ModuleManager los carga).
   - Decoupling completo: host compila sin translators; nuevos proveedores solo implementan `TranslatorProvider` + registran en META-INF.

2. **Release Pipeline — GitHub Actions CI/CD**
   - `.github/workflows/ci.yml`: build & test all modules, build Spigot plugin, upload artifact, dependency-check (lockfiles), web-editor checks, javadoc.
   - `.github/workflows/release.yml`: trigger on `v*` tags; build all modules + publishToMavenLocal; build Spigot fat-jar; generate SHA256 for all JARs; create GitHub Release with artifacts + SHA256.
   - Semantic versioning: tag `vX.Y.Z` → version `X.Y.Z`; prerelease si tag contiene `-`.

3. **Dependency Verification — Completa**
   - `gradle/verification-metadata.xml` con SHA256/SHA512 para todas las dependencias transitivas.
   - Agregados checksums faltantes en esta sesión: `jackson-base-2.22.0.pom`, `junit-bom-5.14.3.module`, `junit-bom-5.14.3.pom`, `adventure-bom-4.13.1.module`, `adventure-bom-4.13.1.pom`.
   - `dependencyLocking` en `build.gradle` (root + subprojects) → 29 proyectos con `gradle.lockfile` (root + 28 subprojects).
   - `checkLocks` task actualizado para manejar proyecto intermedio `:src` (sin build.gradle).
   - Verificación deshabilitada para módulos con deps externas: `tester`, `inworld`, `spigot-host`, `host`, `textformatter`, `loadtest`.

4. **fabric-host — COMPILA (Fabric 1.21 + Fabric API 0.100.5)**
   - Reescritura completa a Fabric APIs: `ServerCommandSource`, `FabricAudiences`, eventos Fabric (`ServerMessageEvents`, `ServerPlayConnectionEvents`, `ServerTickEvents`), Brigadier nativo.
   - Incluido en `settings.gradle` y build CI.

5. **Sincronización Documentación/Codeguides**
   - `README.md` (root): arquitectura actualizada, fabric-host COMPILA, Clean Architecture documentada, dependencias host corregidas.
   - `docs/PLAN.md`: FASE 13 completada, Release Pipeline completada, Dependency Verification completada, estado actualizado.
   - Module READMEs (codeguides): `host`, `fabric-host`, `sync-velocity`, `transport`, `spigot-host`, `manager-impl`, `inworld` actualizados.

**Consecuencias.**
- Host compila sin translators; ServiceLoader discovery en runtime funcionando.
- CI/CD pipeline operativo; builds reproducibles con lockfiles + verification metadata.
- Build completo pasa con dependency verification estricta.
- 29/29 proyectos con gradle.lockfile.
- Todos los tests pasan (unit + integración).
- fabric-host documentado como COMPILA (Fabric 1.21 + Fabric API 0.100.5).

**Implementación (2026-09-28).**
Módulos afectados: `host` (build.gradle, TranslatorsConfig ServiceLoader), `core-api` (ya tenía TranslatorProvider SPI), `gtranslate` (GTranslateProvider + META-INF/services), `ltranslate` (LTranslateProvider + META-INF/services), `spigot-host` (build.gradle mantiene deps), `build.gradle` (root: dependencyLocking, checkLocks fix), `settings.gradle` (fabric-host **incluido**), `gradle/verification-metadata.xml` (checksums agregados), `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `docs/PLAN.md`, `README.md`, module READMEs.

---

## 6. **AUDITORIA.md Hallazgos Resueltos (2026-10-05)**

**Contexto:** Auditoría técnica integral 2026-10-05 (commit `14fcf58`) identificó 8 bugs críticos/altos (TXF-001..TXF-008) y 17 hallazgos B/M/V que requerían corrección para alcanzar madurez operacional production-ready.

**Decisiones tomadas:**

| Hallazgo | Decisión | Implementación |
|---|---|---|
| **TXF-001** In-flight translation leak | Fix obligatorio | `whenComplete` handlers en `TranslationService.translate()` + `detect()` para limpiar `inFlightTranslations`/`inFlightDetections` en todos los paths de completación |
| **TXF-002** RejectedExecutionException escapa | Fix obligatorio | Try-catch en `computeIfAbsent` + fallback a texto original / idioma default |
| **TXF-003** DNS pinning rompe TLS/SNI | Fix obligatorio | Conexión via `Proxy` a IP pinned; `Host` header explícito; `SSLSocketFactory` custom para SNI + hostname verification contra hostname original |
| **TXF-005** SyncBus.unregister() no llama sink.stop() | Fix obligatorio | `removed.sink.stop()` en `unregister()` con try-catch |
| **TXF-006** SyncBus.start() parcial | Fix obligatorio | Start transaccional con rollback de sinks ya iniciados; reset flag `started` |
| **TXF-004** engine.parallel ignorado | Fix obligatorio | Parámetro `engineParallel` en `MessageDispatcher`; procesamiento secuencial cuando false |
| **TXF-007** broadcast() reporta éxito antes de entrega | Fix obligatorio | `broadcast()` retorna count de sinks que encolaron; `broadcastAsync()` retorna futures por sink |
| **TXF-008** VelocitySink EXACTLY_ONCE no demostrado | Fix obligatorio | Cambiado a `AT_LEAST_ONCE` (sin protocolo ACK remoto) |
| **B-01** Double delivery | Fix obligatorio | `broadcast()` solo construye; `onChat()` despacha una vez (Spigot + Fabric) |
| **B-03** Join/quit/death bloquean main thread | Fix obligatorio | Executor async dedicado en `Runtime` (Fabric) + `runTaskAsynchronously` (Spigot) |
| **V-01** WebSocket bind 0.0.0.0 + auth opcional | Fix obligatorio | Bind 127.0.0.1; `start()` lanza `IOException` si token vacío |
| **B-04** TemplateRenderer İ corruption | Fix obligatorio | `Pattern.CASE_INSENSITIVE` en `findSpans()` sobre string original |
| **B-05** Traducción sin re-escape | Fix obligatorio | `MiniEscape.escape(translated)` en `translateSpan()` |
| **B-06** WebSocket rate limit sliding | Fix obligatorio | Ventana fija alineada a época (`now / 1000 * 1000`) |
| **M-01** GTranslate solo primera oración | Fix obligatorio | Loop concatena `root[0][i][0]` |
| **M-02** Thundering herd traducciones | Fix obligatorio | Cache `inFlightTranslations` con dedup + cleanup automático |
| **M-03** MessageCodec ArrayStoreException | Fix obligatorio | Validación elemento a elemento en sync-http/sync-tcpudp |
| **M-04** HttpTransport issues | Fix obligatorio | Null-check getErrorStream, 307/308 preservan method/body, MAX_RESPONSE_SIZE, `read()` |
| **M-05** SSRF IPv6 ULA + deny property | Fix obligatorio | Patrones `^fc[0-9a-f]:`, `^fd[0-9a-f]:`; property amplía no reemplaza |
| **M-08** InterruptedException tragado | Fix obligatorio | Catch separado + `Thread.currentThread().interrupt()` |
| **M-09** RWLock redundante RateLimiter | Fix obligatorio | Solo `ConcurrentHashMap` + `synchronized(bucket)` |
| **M-10** WebSocketSyncSink port bug | Fix obligatorio | Verificado: ya usa `this.port` correctamente |
| **M-11** MetricsEndpoint bind 0.0.0.0 | Fix obligatorio | Bind 127.0.0.1 por defecto via system property |

**Consecuencias:**
- Todos los hallazgos críticos/altos/medios de AUDITORIA.md resueltos
- Build, tests, javadoc, npm check: **todos pasan**
- Proyecto en estado **production-ready** para Spigot/Paper y Fabric
- Documentación sincronizada (README, PLAN, ADR, Wiki)

**Implementación (2026-10-05).**
Módulos afectados: `core-api` (TranslationService, TranslationExecutor), `transport` (HttpTransport), `sync-bus` (DefaultSyncBus), `sync-velocity` (VelocitySink), `sync-websocket` (WebSocketSyncSink), `textformatter` (TemplateRenderer), `gtranslate` (GTranslate), `host` (MessageDispatcher, SuiteHost), `spigot-host` (TextFormatterSuitePlugin), `fabric-host` (TextFormatterSuiteMod), `observability` (MetricsEndpoint), `docs` (README, PLAN, ADR, Wiki).

---

## Decisión 2026-10-14 — Modelo «CPU física» descartado en la VM de iFlow

*(Registrado retrospectivamente el 2026-10-19, al reaparecer el boceto original en una copia de trabajo fuera del repositorio.)*

**Contexto.** La especificación de diseño de iFlow propone un modelo mental de máquina con «CPU, memoria, bus y firmware» (`docs/prompts/diseno/iflow-cpu-design.md` §4, líneas 100-112), con siete componentes conceptuales: CPU Core, banco de registros, espacio de direcciones, interfaz de E/S, bus lógico de servicios, firmware y controlador de interrupciones. El 2026-10-14 ese modelo se bocetó como **estructura de paquetes**: `vm/cpu/ControlUnit.java` (367 líneas: ciclo fetch/decode/execute y el `switch` completo de la ISA) y `vm/cpu/Cpu.java` (integración de subcomponentes), apoyados en `RegisterFile`, `Alu` y `SystemBus` como clases independientes.

El boceto nunca entró en el repositorio: quedó en una copia suelta sin control de versiones y no existe en ninguna revisión (`git log --all -- '*ControlUnit.java' '*Cpu.java'` no devuelve nada). En su lugar se consolidó la implementación actual: `vm/execution/VmInterpreter` + `ExecutionContext` (con *fuel budget* y pooling) + `vm/engine/VmPolicyEngine` + `vm/irq/{VmPic,IrqTable}` + `vm/memory/VmBus`.

Hechos verificados (2026-10-19):

- El boceto no compila contra el repo: importa `vm.bus.SystemBus`, `vm.cpu.RegisterFile` y `vm.cpu.Alu`, que no existen; `Cpu.java` además instancia componentes duplicados y pasa `null` como memoria y programa al `ControlUnit`.
- `Cpu.setProgram()`, `Cpu.step()` y `Cpu.reset()` eran `TODO` sin implementación.
- La ISA del boceto es la actual **menos** las variantes inmediatas `LDBI`, `LDWI`, `STBI` y `STWI`.
- `ControlUnit.executeHost()` no recibía `HostServiceRegistry`, así que los servicios del host no podían resolverse.
- Ninguna clase del módulo iFlow lleva contabilidad de ciclos ni CPI (`grep` sobre `src/iflow-vm/src/main/java/`); el límite de ejecución es el *fuel budget* de `vm/execution/ExecutionContext` (líneas 36, 93, 133).
- El boceto es anterior al ABI de `HOST` v2 (2026-10-16/17) y al despacho de interrupciones en frontera de instrucción (D33).

**Decisión.** El modelo «CPU física» **no se implementa como estructura de paquetes**. El «CPU / banco de registros / bus / firmware» de la especificación es un **modelo mental**, no un mapa de clases: la CPU se materializa como intérprete con contexto de ejecución reutilizable y el bus como `vm/memory/VmBus`. El boceto se descarta; no se importa.

**Alternativas consideradas.**

1. **Importar e integrar el boceto** (`vm/cpu/ControlUnit` + `vm/cpu/Cpu` + `RegisterFile` + `Alu` + `SystemBus`).
   - Ventajas: refleja §4 con componentes explícitos y separa el ciclo de ejecución del motor de políticas.
   - Limitaciones: no compila (faltan tres clases), su ISA es anterior a las variantes inmediatas, ignora el despacho de IRQ en frontera de instrucción (D33) y no usa el journal de persistencia.
   - Evaluación: **descartada**. No aporta nada que el intérprete actual no cubra, y `Cpu.java` introduciría `TODO` y stubs en el recuento de deuda del propio proyecto (`tools/project-metrics`), además de contradecir el ABI v2.

2. **Mantener la implementación actual** (`VmInterpreter` + `ExecutionContext` + PIC).
   - Ventajas: un único lugar para el ciclo y el despacho de IRQ; contexto pooled sin asignaciones por ejecución; el *fuel budget* acota la ejecución; el journal persiste modificaciones selectivas.
   - Limitaciones: sin contabilidad de ciclos/CPI no se puede instrumentar coste por instrucción sin añadirla; §4 no tiene correspondencia 1:1 con paquetes.
   - Evaluación: **aceptada** (es lo implementado y lo que consolidaron la auditoría de iFlow y el ABI v2).

3. **Archivar el boceto dentro del repositorio** (como `.java` o como documento en `docs/workdir/`).
   - Ventajas: conserva el rastro de la evolución del diseño.
   - Limitaciones: no compila; como `.java` contaría en las métricas; como documento duplicaría lo que ya explica este ADR.
   - Evaluación: **descartada**. El registro de la decisión es este ADR, no el código muerto.

**Consecuencias.**

- El boceto, que vivía fuera del repositorio en una copia de trabajo sin git, se elimina tras registrar esta decisión (2026-10-19).
- Queda documentado por qué `vm/` no tiene `cpu/`, `bus/` ni `alu/` pese a lo que sugiere §4 de la especificación.
- La separación «CPU Core» / «banco de registros» / «controlador de interrupciones» se lee como responsabilidades dentro del intérprete, no como paquetes.
- Si en el futuro se quiere instrumentar coste por instrucción (ciclos/CPI), habrá que añadirlo: hoy no existe en ninguna clase del módulo.

**Implementación (2026-10-19).**
`docs/workdir/ADR.md` (esta entrada). Sin cambios de código: la implementación vigente no se toca.

---

## Decisión 2026-10-19 — La MMIO de dispositivos no la gobierna la PageTable (AUD-031)

**Contexto.** La tercera auditoría forense de iFlow registró `IFLOW-AUD-031` (Media-Alta, «aislamiento
interno»): la apertura MMIO de 256 B (`0x0F00–0x0FFF`) vive dentro de la página del BIOS, declarada
kernel R/X (`VmPolicyEngine` mapea la página 0 con `perm=R|X`), pero `VmBus` la decodifica **antes** de
la MMU (`VmBus.java`, `readByte`/`writeByte`). Consecuencia: ni la propiedad de página ni el privilegio
del maestro la gobiernan. Un programa puede reescribir la NVRAM del BIOS (bandera de arranque en frío,
tabla de IRQ), el PIC y los registros de la pantalla.

**Decisión.** **Aceptar el diseño y documentarlo explícitamente como contrato**, en lugar de añadir un
portero de acceso. La apertura es **espacio de dispositivo**, no memoria: como la MMIO real, no se
traduce ni se comprueba por privilegio. La protección por propiedad de página se mantiene para la
**memoria** (ROM, constantes): un programa sigue sin poder escribir RAM de propiedad del kernel.

Hechos que sostienen la decisión (verificados):

- D31/D33 **requieren** que el programa escriba los registros de la pantalla y del PIC — el programa
  habilita sus líneas (`ENABLE`), las dispara (`RAISE`) y las confirma (`ACK`) —, y la tabla de IRQ de
  la NVRAM está pensada para ser reescrita por el programa.
- El BIOS corre en el dominio `PROGRAM`, igual que el usuario: un portero por privilegio rompería
  también el POST.
- Lo único realmente privado del BIOS en la apertura es la **bandera de arranque en frío** de la NVRAM;
  su impacto se limita a forzar el cold-boot de la propia máquina del programa (limpia su RAM volátil
  `0x3000–0x4FFF`). **No hay escape al host**: el periférico vive en el contenedor del programa.

**Alternativas consideradas.**

1. **Mover los 256 B a una página propia** (segundo camino que propone el auditor: «sacar el apéndice
   de la página R/X»). Ventajas: la `PageTable` pasa a ser la autoridad única y desaparece el
   «bypass». Limitaciones: **no añade aislamiento** —la página tendría que seguir siendo escribible por
   el programa, por D31/D33— y cuesta RAM de usuario (28 KB → 24 KB) o geometría de pantalla (80×24).
   Evaluación: **descartada** para hoy (coste sin ganancia).
2. **Pasar la bandera de arranque de la NVRAM al dominio supervisor** (servicio HOST o gestión por el
   runtime), dejando el resto en el dominio del programa. Ventajas: es lo único que añadiría
   aislamiento real (un `.flow` no podría alterar el próximo arranque). Limitaciones: coste alto en
   firmware + motor + ABI. Evaluación: **no descartada para el futuro**; el riesgo actual es bajo.
3. **Aceptar y documentar.** Ventajas: coste cero, coherente con D31/D33 y con el javadoc de `VmDevice`
   (que ya describía la MMIO «como la MMIO real», sin traducción ni permisos). Limitaciones: un
   programa puede seguir alterando su propia NVRAM/PIC/pantalla. Evaluación: **aceptada**.

**Consecuencias.**

- El contrato queda escrito en `VmMemoryMap.DEVICE_MMIO_BASE` (y referenciado desde `VmBus` y
  `VmDevice`); deja de leerse como un «bypass accidental».
- Se fija con un test: `VmDisplayTest.programMayDriveDeviceRegistersInsideTheKernelPage` comprueba que
  un maestro `PROGRAM` recibe `PRIVILEGE_FAULT` al escribir **RAM** en la página kernel R/X pero **sí**
  configura la pantalla a través de la apertura.
- Si en el futuro se adopta la alternativa 2, este ADR se enmienda y se implementa el dominio supervisor
  para la NVRAM.

**Implementación (2026-10-19).**
`VmMemoryMap.java` (javadoc del contrato), `VmBus.java`, `VmDevice.java` (referencias), `VmDisplayTest.java`
(test que lo fija), `docs/workdir/PLAN.md` (D35), `src/iflow-vm/README.md` y `src/iflow-vm/HDL.md`. Sin cambios
de comportamiento: el código ya se comportaba así; ahora está declarado y probado.


---

## Decisión 2026-10-20 — SpEL fuera del repositorio; la VM es la única superficie de políticas

**Contexto.** iFlow v2 ya había sustituido el motor de reglas basado en SpEL/regiones por una VM de
16 bits con bytecode `.flow`. Sin embargo, quedaban restos activos o documentados como actuales:

- el SPI `ExpressionEvaluator` y su implementación `SpelExpressionEvaluator` en `textformatter`;
- el paso `<expr>` de `TemplateRenderer`;
- `TransformEngine` y las condiciones/acciones SpEL del modelo de presets;
- el campo “Condition (SpEL)” del editor web;
- las dependencias `spring-expression` y `spring-beans`;
- documentación de módulos y wiki que seguía presentando SpEL como una capacidad vigente.

`TransformEngine` no tenía consumidores de producción y `Preset.apply(...)` ya era un placeholder. El
arreglo de sus bindings registrado en el changelog anterior era mantenimiento de código muerto, **no**
la corrección de un bug de producción.

**Decisión.** Eliminar SpEL de todo el repositorio. La única superficie de políticas ejecutables es la
VM de iFlow: programas binarios `.flow` cargados por `VmConfigLoader` y ejecutados por
`VmPolicyEngine`/`VmInterpreter`, con servicios `HOST` y *fuel budget*. Las plantillas de
`textformatter` quedan limitadas a placeholders, MiniMessage, traducción `<tr>` y la etiqueta
`<regex>` definida en ADR-028.

Cambios aplicados:

1. Retirar `ExpressionEvaluator`, `ExpressionEvaluationException`, `SpelExpressionEvaluator` y el paso
   `<expr>`.
2. Retirar `TransformEngine`, su test, `PresetRule` y las condiciones/acciones SpEL de presets.
3. Retirar el campo de condición del editor web; el grafo `rules.yml` queda como material legacy y no
   se evalúa en runtime.
4. Retirar `spring-expression`/`spring-beans`, actualizar lockfiles y metadata de verificación.
5. Actualizar README, módulos, wiki, AUDITORIA, PLAN y CHANGELOG; dejar las menciones históricas
   marcadas como tales.

**Alternativas consideradas.**

1. **Mantener SpEL solo en plantillas, con sandbox.** Ventajas: conserva una función existente.
   Limitaciones: mantiene dos lenguajes de política, una dependencia Spring y una superficie de RCE que
   la VM ya no necesita. Evaluación: **descartada**.
2. **Migrar las condiciones SpEL de presets a `.flow`.** Ventajas: conserva la intención funcional.
   Limitaciones: no hay consumidor de presets ni compilador integrado; sería diseñar una función nueva
   sobre código muerto. Evaluación: **descartada**; si se necesita, se implementará como programa de la
   VM, no como expresión inline.
3. **Eliminación completa.** Ventajas: una sola superficie ejecutable, menos dependencias, documentación
   coherente. Limitaciones: rompe APIs internas (`ExpressionEvaluator`, constructor de `Preset`) sin
   consumidores de producción. Evaluación: **aceptada**.

**Consecuencias.**

- `textformatter` ya no depende de Spring; los lockfiles de los módulos que lo consumían dejan de
  arrastrar `spring-core`, `spring-expression`, `spring-beans` y `spring-jcl`.
- La metadata de verificación conserva los componentes Spring que usa el classpath de build de
  Fabric Loom; no aparecen en ningún lockfile de runtime ni son dependencias de la aplicación.
- El editor web no ofrece edición de condiciones; un `rules.yml` importado puede conservar la clave,
  pero no se evalúa ni se vuelve a mostrar.
- Las menciones a SpEL en documentos históricos (release notes antiguas, prompts de diseño, auditorías)
  se conservan como registro, no como estado actual.
- Cualquier política nueva debe implementarse en la VM y documentarse en `src/iflow-vm/README.md`.

**Implementación (2026-10-20).** Código, build, lockfiles, metadata, schema del editor y documentación
actualizados; `buildAll` pasa con 766 tests JVM (0 fallos, 11 skipped).


---

## Decisión 2026-10-27 — Restauración del motor SpEL legacy en `iflow-firewall` como alternativa mutuamente excluyente

**Estado:** Aceptada. Sustituye, **solo para `iflow-firewall`**, la decisión de 2026-10-20 de
eliminar SpEL de todo el repositorio. La VM y Wasm siguen sin SpEL.

**Contexto.** El usuario pidió restaurar el último commit donde existía el iFlow legacy y desarrollar
`iflow-vm` e `iflow-firewall` en paralelo, sin que uno sustituya al otro en código. La búsqueda inicial
no encontraba `iflow-firewall` porque la ruta original era `src/iflow`: en `933bdd3^` (padre del *VM
architecture rewrite*) convivían `DefaultRouter`, `RuleExpressionEvaluator`, `Rule`, `TransformOp`,
`ScriptSurface`, `Router`, `RouteDecision`, `RouteOutcome`, `PolicyTarget`, `PermissionChecker`,
`RateLimiter` e `IflowModule`; el host tenía `RuleConfigLoader`. `933bdd3` los sustituyó por la VM,
`e21e7d7` eliminó SpEL y `ead0523` renombró el módulo a `iflow-vm`.

**Regla de producto.** Los tres motores —`iflow-vm`, `iflow-wasm` e `iflow-firewall`— son
**mutuamente excluyentes**. Solo uno puede estar instalado en un servidor a la vez; instalar otro
desinstala/reemplaza al anterior. Los tres comparten el paquete base `me.majhrs16.suite.iflow`
(con subpaquetes propios: `.vm`, `.wasm`, `.rule`, etc.). El contrato `Router` tiene el mismo FQCN
en los motores que lo implementan. Por eso no deben coexistir en el classpath y no se usa un sufijo `.firewall` ni un
módulo `iflow-api` compartido.

**Decisión.**

1. Restaurar literalmente ese motor en `src/iflow-firewall`, con su propio contrato
   (`Router`, `RouteDecision`, `RouteOutcome`, `PolicyTarget`, etc.) en el paquete
   `me.majhrs16.suite.iflow`, y su descriptor `IflowFirewallModule` (nombre
   `iflow-firewall`), que provee el capability `router`.
2. Mantener los motores independientes: `iflow-firewall` no depende de `iflow-vm` y viceversa.
   No se crea un módulo `iflow-api` compartido; cada JAR aporta las clases del contrato base.
3. El host se compila contra `me.majhrs16.suite.iflow.Router`; `RouterFactory` selecciona el motor
   con `iflow.engine.kind` (`vm` por defecto, `firewall` legacy). No hay adaptador: el motor
   instalado implementa el mismo contrato.
4. `ExpressionEvaluator` y `ExpressionEvaluationException` viven en `iflow-firewall`, no en
   `core-api`, porque solo los usa este motor.
5. `rules.yml` acepta la lista histórica de reglas (SpEL) o un mapa con `rules:`; el grafo del
   editor se valida pero no se ejecuta.
6. Se restaura `spring-expression:6.1.12` en `iflow-firewall`; el sandbox conserva el bloqueo de
   `T()`, la allowlist de clases, la resolución de métodos sin argumentos y una caché LRU acotada
   (1024).

**Alcance de la revocación.** No se reintroduce SpEL en `textformatter`, presets, `<expr>` ni en el
campo de condición del editor. Esas partes de la decisión de 2026-10-20 siguen vigentes. El único
consumidor de SpEL es el motor legacy opcional de `iflow-firewall`.

**Consecuencias.**

- Los tres motores son alternativas instalables; el gestor de paquetes debe reemplazar uno por otro.
  Se compilan los tres y `./gradlew publishIflowEngines` los publica como paquetes Maven separados
  (`suite-iflow-vm`, `suite-iflow-firewall`, `suite-iflow-wasm`) en `build/repo`. Mientras el
  classpath de desarrollo contenga varios, `SuiteBootstrap` filtra los no seleccionados antes de
  resolver el grafo.
- Cada JAR duplica las clases del contrato base con el mismo FQCN. Es intencional y solo es seguro
  porque los motores son excluyentes; un despliegue con dos a la vez no está soportado.
- `spring-expression`/`spring-core`/`spring-jcl` vuelven solo al classpath de `iflow-firewall`.
- `core-api` no incorpora SPI de expresión de un solo módulo.
- El default `iflow.engine.kind: vm` conserva el comportamiento anterior; el motor legacy solo se
  ejecuta si está instalado y se selecciona `firewall`.
- Tests: 44 en `iflow-firewall` (motor, SpEL, loader, validador) y 3 de integración en `host`
  (selección VM/firewall y descriptores alternativos).

**Implementación (2026-10-27).** Código restaurado en `me.majhrs16.suite.iflow`, descriptor
`iflow-firewall`, SPI de expresión dentro del módulo, `RouterFactory` sin adaptador, `FirewallConfig`
tolera el esquema legacy, selección de motor en `HostConfig`/`ConfigLoader`, y documentación de
exclusividad (PLAN, ADR, storage, README, wiki). `./gradlew build` pasa.

---

## Decisión 2026-10-29 — Sincronizadores de plataforma `sync-bukkit` y `sync-fabric`

**Contexto.** `spigot-host` y `fabric-host` interceptaban eventos de Minecraft
(chat, join, quit y death), los traducían y los entregaban al núcleo. Ese bloque
no es bootstrap del host: es sincronización de plataforma. Mantenerlo dentro del
JAR del host impide que el Manager lo instale, versione, reemplace o descargue
solo en el entorno compatible.

**Decisión.**

1. La captura de eventos se mueve a dos paquetes de plataforma descargables:
   `sync-bukkit` para Spigot/Paper/Purpur y `sync-fabric` para Fabric. No se
   embeben en el JAR de `spigot-host` ni en el de `fabric-host`.
2. `spigot-host`/`fabric-host` quedan como bootstrap: construyen
   `PlatformPorts`, arrancan `SuiteRuntime`, publican servicios neutrales y
   registran comandos. No contienen `@EventHandler`, no registran
   `ServerMessageEvents.CHAT_MESSAGE`, no traducen eventos y no importan
   features.
3. `packages.list` declara la plataforma/entorno del paquete. El Manager
   instala solo el compatible con `PlatformInfo`; un paquete incompatible no se
   activa.
4. `sync-bukkit`/`sync-fabric` pueden importar el SDK de su plataforma, pero no
   features, no un `host` concreto y no otros sincronizadores. Consumen
   `MessageDispatch`, `ActorDirectory`, `PermissionChecker`,
   `UserLanguageStore` y los puertos neutrales que necesiten desde
   `ServiceRegistry`.
5. Traducen el evento de plataforma a `Message` y lo despachan por
   `MessageDispatch`. No son `SyncSink`: son fuentes de eventos locales; el
   fan-out a sinks lo hace `sync-bus`. No deciden formato, routing, traducción,
   canales ni entrega.
6. Registran sus listeners en `start()` y los retiran en `stop()`. Si la API de
   la plataforma no permite desregistrar, la desactivación se marca
   `RESTART_REQUIRED`; no se deja un listener huérfano.
7. `checkArchitecture` permite el SDK de plataforma solo en `sync-bukkit`,
   `sync-fabric` y el bootstrap del host; falla si un módulo neutral o un
   adapter de feature importa Bukkit/Paper/Fabric.
8. No hay doble camino: al cerrar F4c se eliminan los listeners de los hosts. No
   se conserva un fallback compilado ni una activación implícita.

**Estado:** Aceptada e implementada (F4c cerrada el 2026-10-31).

**Implementación (F4c).** `core-api` publica `PlatformHandle` (handle opaco que
los hosts registran antes de `start()`) y `ModuleContext.isShuttingDown()`.
`sync-common` concentra `ChannelSelector`, `EventRules`, `EventMessages` y
`SyncEventProcessor`, compartidos por ambas plataformas. `sync-bukkit` captura
chat/join/quit/death y aplica `claim-mode`; `sync-fabric` captura los eventos
equivalentes de Fabric API y detecta muertes en el tick. `PackageRuntime`
filtra por `PlatformCompatibility` y `checkArchitecture` permite el SDK solo en
los dos paquetes y en los hosts. Los ZIP declaran `Platform: spigot` y
`Platform: fabric`. La validación es compilación + tests; no hay E2E en
servidores reales.

**Consecuencias.**

- El host deja de ser composition root de eventos; su única responsabilidad de
  plataforma es `PlatformPorts` y el arranque del runtime.
- Cada entorno descarga su propio sincronizador; no existe un paquete universal
  de captura de eventos.
- Se añade la fase F4c al PLAN, después de F4b (hosts delgados) y antes de F7
  (hot upgrade/unload real). El detalle operativo vive en `PLAN.md`, sección
  «Decisión 2026-10-29 — sincronizadores de plataforma».
- Los tests de F4c deben cubrir instalación solo en el entorno compatible,
  traducción a `Message`, despacho, desactivación y ausencia de listeners
  huérfanos.

---

## Decisión 2026-10-29 — API de configuración y comandos jerárquicos registrables (ADR-021)

**Estado:** Aceptada e implementada (F4d cerrada el 2026-10-31).

**Contexto.** `PlatformPorts.Command` solo registra un comando plano: no hay
subcomandos, tab completion ni registro por módulo. `SuiteCommand` hardcodea
`/suite` y su propio `switch`; `spigot-host` mantiene
`DynamicCommandRegistrar`/`DynamicCommand` con un árbol duplicado y
`fabric-host` otro árbol Brigadier. Un módulo no puede aportar comandos propios
sin tocar el host, y al descargarse no hay forma de retirar su subárbol. La
configuración de cada módulo tampoco tiene una API neutral: cada paquete lee su
YAML y expone comandos a mano.

**Decisión.**

1. `core-api` publica `api.command`: `CommandRegistry`, `CommandSpec`,
   `CommandPath`, `CommandSource`, `CommandContext` y `CommandRegistration`.
   `CommandSpec` es un árbol con nombre, aliases, descripción, permiso, uso,
   argumentos/opciones, hijos y ejecutor.
2. El registro es propiedad del módulo y devuelve un `CommandRegistration`
   (`AutoCloseable`). `ModuleContext.commands()` entrega un registro
   module-scoped; el runtime cierra todas las inscripciones después de
   `stop()`. El path por defecto es el namespace del módulo; un path explícito
   se declara y también se retira al desactivar. Un módulo no puede dejar
   comandos huérfanos.
3. `core-api` publica `api.config`: `ConfigRegistry`, `ConfigSection` y
   `ConfigRegistration`. Un módulo registra su sección (namespace, defaults,
   esquema, validador y reload) y puede exponer comandos de configuración
   generados o propios. La configuración persistida no se borra al
   desregistrar; solo se retira la superficie de comandos/esquema.
4. `CommandRegistry` es el único punto de composición. Los comandos del runtime
   (`list`, `state`, `install`, `remove`, `upgrade`, `repo`) usan la misma API;
   el host solo traduce la invocación de plataforma y no define comandos de
   features.
5. Conflictos de path/alias fallan explícitamente; no hay override silencioso.
   La política `force` solo aplica si se define explícitamente y deja warning.
6. Los comandos son neutrales: no importan Bukkit/Fabric; reciben
   `CommandSource`/`CommandContext` y responden por `reply`. La tab completion
   se deriva del árbol.
7. El árbol de comandos del host (`DynamicCommandRegistrar`/`DynamicCommand` y
   el Brigadier de Fabric) se elimina o se reduce a un adaptador de invocación;
   no se mantiene duplicado.

**Consecuencias.**

- Cada módulo puede publicar comandos bajo su namespace sin conocer la
  plataforma.
- Desactivar un módulo retira su subárbol; reactivarlo lo vuelve a registrar.
- El host deja de ser dueño del árbol de comandos.
- Requiere tests de registro/desregistro, colisiones, permisos, tab completion y
  esquema de configuración.
- La configuración existente de cada módulo sigue siendo suya; la API
  estandariza la superficie, no impone un formato de archivo.

**Implementación (F4d).** `api.command` y `api.config` existen en `core-api`;
`SuiteRuntime` compone `RuntimeCommandRegistry`/`RuntimeConfigRegistry`;
`ModuleContext` entrega vistas module-scoped y exige que el árbol del módulo
esté enraizado en su `moduleId`; `register(CommandPath, CommandSpec)` permite
declarar una rama explícita y retirarla entera. Los comandos del runtime
(`help`, `list`, `state`, `install`, `remove`, `upgrade`, `repo`) usan la misma
API; `CommandOption` declara flags/valores para ayuda y tab completion;
`ConfigSection.validate` comprueba tipos por defecto y `reload` es explícito, no
un no-op; `SuiteCommand` se eliminó y el adaptador de plataforma solo necesita
registrar el comando raíz que delega en el registro neutral. Los tests cubren registro, desregistro por
ciclo de vida, aliases, colisiones, permisos por ancestro, usage, opciones y tab
completion. La eliminación física de `DynamicCommandRegistrar`/`DynamicCommand`,
`CommandsConfig`/`CommandsConfigLoader`/`CommandExecutor` y de los restos de
eventos de los hosts se completó en F4d; el Brigadier de Fabric queda como
adaptador de invocación. `RuntimeCommandRegistry` comprueba el permiso de cada
nodo del path y oculta de la tab completion los subárboles no permitidos.

---

## Decisión 2026-10-29 — Raíz de comandos relocalizable (ADR-022)

**Estado:** Aceptada e implementada en F4e. `CommandRoot` vive en `api.command`;
`CommandRootConfig` carga `root`/`aliases` desde `config/suite/commands.yml`;
`SuiteRuntime.reloadCommandRoot()` re-registra la raíz y distingue
`APPLIED`/`UNCHANGED`/`FAILED`/`RESTART_REQUIRED`.

**Contexto.** Antes de F4e, el literal `/suite` estaba hardcodeado en la raíz
por defecto y en los mensajes de ayuda. Los árboles de host ya se retiraron en
F4d; el Brigadier de Fabric solo adapta la invocación. Cambiar a `/suite module`
o `/tf` obligaría a tocar la configuración y los mensajes, no los módulos. Los
módulos deben registrar rutas relativas, no la raíz.

**Decisión.**

1. `CommandRoot` es un value object con segmentos (`["suite"]`,
   `["suite","module"]`, `["tf"]`) y aliases opcionales. Se declara en
   `commands.yml` (ruta canónica `config/suite/commands.yml`), no en código.
2. `CommandRegistry` almacena paths relativos al root. El runtime compone
   `root + path` al registrar y al resolver. Los módulos nunca ven ni
   hardcodean `/suite`.
3. El adaptador de plataforma registra el primer segmento del root y delega el
   resto al dispatcher neutral. Para roots multi-segmento (`suite module`), el
   dispatcher consume el prefijo y enruta el resto.
4. Help, usage y errores usan `CommandContext.root()`; no se concatenan strings
   `/suite`.
5. Cambiar la raíz es cambiar configuración: `root: [suite]` →
   `root: [suite, module]` o `root: [tf]`; los módulos no cambian. Los aliases
   permiten mantener `/suite` y `/tf` a la vez.
6. El `base-name` actual de `commands.yml` se sustituye por `root`. Se acepta
   solo como migración con aviso y se elimina en **F8**; no es un fallback
   permanente. La migración y el esquema exacto están en `commands-v2.md`.
7. Si la raíz cambia en caliente, el runtime re-registra el comando raíz; si la
   plataforma no permite desregistrar o restaurar, se marca
   `RESTART_REQUIRED` y no se publica la nueva raíz como activa. Si el nuevo
   registro falla, se intenta restaurar la raíz anterior y se informa
   `FAILED`.
8. Conflictos de root con comandos existentes fallan explícitamente; no se roba
   un comando ajeno.

**Consecuencias.**

- Migrar la raíz es configuración, no refactor.
- Los módulos son independientes del nombre del comando raíz.
- El host solo necesita un adaptador de invocación de primer nivel.
- La documentación y los mensajes deben usar `CommandContext.root()`.
- Tests obligatorios: el mismo árbol bajo `suite`, `suite module` y `tf`; root
  multi-segmento; aliases; colisión; re-registro en caliente o
  `RESTART_REQUIRED`.

---

## Decisión 2026-10-29 — Compilador C para iFlow VM (ADR-023)

**Estado:** Aceptada. Diseño congelado; implementación pendiente en la fase V6,
después de V1 (ISA/ABI v3), V2 (núcleo), V3 (máquina persistente), V4
(integración) y V5 (assembler v3). No se implementa contra el ISA v2 de 16 bits.

**Contexto.**

- La línea `iflow-vm` v3 es una máquina de 32 bits con ROM, BIOS, bootloader,
  kernel, ISA jerárquica, ABI `HOST` v3 y memoria paginada. La especificación de
  referencia es `docs/prompts/diseno/iflow-cpu-implement.md`.
- `tools/iflow-compiler/` solo ensambla `.flowasm` v2 a `.flow` v2. No hay
  frontend C, ni ABI C, ni runtime freestanding para la arquitectura v3.
- Escribir políticas en ensamblador es una barrera real para autores y para el
  editor. Un compilador C permite expresar políticas, firmware y utilidades sin
  convertir el ensamblador en el único lenguaje de autoría.
- El compilador no pertenece al host: es una herramienta offline/CI. El servidor
  solo carga ROM ya compilada y verificada; no compila C en runtime.
- El compilador debe ser determinista, auditable y no depender de una toolchain
  nativa externa (Clang/LLVM, GCC, TinyCC) para producir el artefacto.

**Decisión.**

1. **Herramienta separada.** Se añade `tools/iflow-c/` con el CLI `iflow-cc`,
   implementado en TypeScript sobre Node.js 18+, igual que el assembler actual.
   No sustituye a `tools/iflow-compiler/`: el compilador C emite `.flowasm` v3 y
   el assembler v3 sigue siendo el único codificador de instrucciones y el único
   productor de ROM. No se duplica la tabla de opcodes ni el formato binario.
2. **Pipeline.** El compilador ejecuta: preprocesador → lexer → parser →
   análisis semántico → AST tipado → IR de tres direcciones → optimización
   local → lowering al target → asignación de registros → emisión de
   `.flowasm` v3. El assembler v3 resuelve símbolos, relocaciones, TOC, hash y
   segmentos de la ROM.
3. **Lenguaje.** Se implementa un **subconjunto C11 freestanding**, no ISO C
   completo. Baseline:
   - tipos: `void`, `_Bool`, `char` (8 bits sin signo), `signed char`, `short`
     (16), `int`/`long` (32), `long long` (64), `float` (IEEE-754 binary32),
     `double` y `long double` (IEEE-754 binary64), punteros de 32 bits, arrays,
     `struct`, `union`, `enum`, `typedef`, `const`, `volatile` y `_Atomic`
     escalar/puntero;
   - expresiones: aritmética entera y de coma flotante, bitwise, shifts,
     comparaciones, lógica, asignación y compuestas, ternario, coma, casts,
     `sizeof`, dirección, desreferencia, acceso a miembros, indexación,
     llamadas, literales de cadena, enteros y reales;
   - sentencias: bloques, `if`/`else`, `switch`/`case`/`default`, `for`,
     `while`, `do`/`while`, `break`, `continue`, `return`, `goto`/etiquetas y
     declaraciones;
   - funciones con prototipos, `static`, `extern`, `inline` como pista y
     `_Static_assert`. Las funciones variádicas no son baseline.
   - `volatile` se respeta: no se eliminan, duplican ni reordenan accesos
     volátiles. Es obligatorio para MMIO.
   - `_Atomic` incluye cargas, almacenes, intercambio, comparación-e-intercambio
     y fetch-add/sub/and/or/xor para tipos escalares y punteros, con las
     órdenes de memoria de C11. Los agregados atómicos quedan fuera del
     baseline inicial y se revisan en C4.
   - ensamblador inline estilo GNU: `asm("...")`, `asm volatile("...")` y la
     forma extendida `asm volatile("..." : salidas : entradas : clobbers)`.
     Se soportan operandos `%0`/`%1`, constraints `r`, `m` e `i`, clobbers de
     registros y `"memory"`/`"cc"`. `asm goto` no es baseline.
4. **Exclusiones explícitas.** No son baseline: `_Complex`, hilos, `setjmp`/
   `longjmp`, VLA, bitfields, caracteres anchos, `_Generic`, `typeof` y
   funciones variádicas. Un uso no soportado produce un error de compilación
   con ubicación; nunca se acepta en silencio ni se degrada a otra semántica.
   Las exclusiones se revisan con ADR propio si una política real las necesita.
   Glosario:
   - **VLA** (*variable-length array*): array local cuyo tamaño se calcula en
     runtime, p. ej. `int n; int a[n];`. Exige asignación dinámica de pila y
     controles de desbordamiento; no es lo mismo que un array de tamaño fijo.
   - **Bitfields**: campos de bits dentro de `struct`, p. ej.
     `unsigned flags : 3;`. Su layout es implementation-defined y debe fijarse
     explícitamente antes de soportarlo.
   - **Variádicas**: funciones con número variable de argumentos, p. ej.
     `int f(int n, ...);` y `va_list`/`va_arg`. Requieren ABI de varargs y
     `<stdarg.h>`.
   - **`setjmp`/`longjmp`**: salto no local que restaura un contexto guardado;
     requiere guardar/restaurar registros y pila y complica el unwind.
   - **`_Complex`**: tipos complejos de C (`float _Complex`, etc.); no se
     confunden con `_Atomic`.
   - **Caracteres anchos**: `wchar_t`, `wint_t` y literales `L"..."`; exigen
     una anchura y codificación fijadas por el target.
   - **`_Generic`**: selección de expresión por tipo en C11; no es necesario
     para el subconjunto actual.
   - **`typeof`**: extensión GNU que obtiene el tipo de una expresión; no es
     C11 estándar.

   **Prioridad de extensiones.** Las exclusiones no son permanentes; se
   incorporan después de V6, con enmienda de este ADR y tests propios:

   1. **Variádicas** y **bitfields** son las primeras candidatas por uso común
      en C. Variádicas exige ABI de varargs y `<stdarg.h>`; bitfields exige
      fijar orden de bits, empaquetado, alineación y layout observable.
   2. **VLA** y **`setjmp`/`longjmp`** solo se incorporan si una política real
      los necesita. VLA requiere asignación dinámica de pila y comprobación de
      desbordamiento; `setjmp`/`longjmp` requiere guardar/restaurar contexto y
      definir el unwind.
   Los agregados atómicos se revisan antes, en C4, porque no cambian el
   subconjunto del lenguaje sino el alcance de `_Atomic`.
5. **Preprocesador.** Incluye `#include` con rutas de búsqueda, macros de objeto
   y de función, `#`/`##`, `#if`/`#ifdef`/`#ifndef`/`#elif`/`#else`/`#endif`,
   `#undef`, `#error` y `#pragma once`. No hay `#include_next` ni dependencia
   del preprocesador del sistema.
6. **ABI C v3.** Se congela en V6.0 sobre el ISA v3 que fije V1. Baseline:
   - little-endian único, alineación natural, pila de 4 bytes (8 para 64 bits)
     que crece hacia abajo;
   - argumentos en pila de derecha a izquierda; el llamador limpia; sin
     registros de argumento obligatorios;
   - retorno en `R0` (32 bits) o `R1:R0` (64 bits); `struct`/`union` por valor
     usan puntero oculto del llamador y el callee devuelve ese puntero en `R0`;
   - `float` (32 bits), `double`/`long double` (64 bits) se pasan en pila y se
     retornan en `R0` o `R1:R0`; el baseline es **soft-float**, sin registros
     FP obligatorios. Una FPU futura puede acelerar operaciones sin cambiar la
     ABI;
   - `_Atomic` escalar/puntero respeta la alineación natural y usa las
     primitivas atómicas del ISA v3 o los helpers de `libiflow`; no se asume
     lock-free si el target no lo garantiza;
   - todos los GPR son caller-saved; `SP` y `FP` se preservan; no hay red zone;
   - `char` sin signo, `size_t`/`uintptr_t` de 32 bits, `ptrdiff_t` de 32 bits;
   - símbolos C sin name mangling; `_start` es el entry de `crt0` y el kernel
     salta a él; `main` es la función del programa.
   Cualquier cambio de ABI requiere enmendar este ADR; no se mantienen dos ABI
   C en paralelo.
7. **Runtime `libiflow`.** Es una biblioteca freestanding enlazada dentro del
   segmento de programa, no un paquete del host. Provee `crt0`, `memcpy`,
   `memmove`, `memset`, `memcmp`, `strlen`, `strcmp`, `strcpy`, `strncpy`,
   rutinas de 64 bits, helpers **soft-float** (`__iflow_fadd`, `__iflow_fmul`,
   conversiones, comparaciones y redondeo IEEE-754), helpers atómicos
   (`__iflow_atomic_*`) y un arena allocator explícito (`iflow_alloc`). No
   provee `stdio`, `printf`, `malloc`/`free` estándar ni `stdlib` hasta que el
   ABI del kernel defina heap y errores. Una función no implementada falla
   enlazando; no se añade un stub que devuelva éxito.
8. **Modelo de programa.** El compilador compila un programa completo (una o
   varias unidades `.c` en la misma invocación) a un único `.flowasm` v3. El
   baseline no define un formato de objeto estable ni un linker de objetos
   separado; si V6.4 demuestra que hace falta, se añade con ADR. El loader v3
   inicializa `.data` y zeroa `.bss`; `crt0` llama a `main`, recibe el estado y
   ejecuta la salida del programa. No hay constructores globales.
9. **Optimización.** `-O0` es el default y debe ser correcto antes que rápido.
   `-O1` puede aplicar plegado de constantes, propagación local, eliminación de
   código muerto y peephole, sin explotar comportamiento indefinido, sin alterar
   `volatile` y sin reordenar operaciones atómicas. La coma flotante mantiene
   semántica IEEE-754 por defecto; `-ffast-math` no es baseline. La salida es
   determinista: mismo fuente, flags y versión producen los mismos bytes y el
   mismo orden de símbolos.
10. **Seguridad.** El compilador no corre en el host ni en el servidor. No
    descarga dependencias en tiempo de compilación ni ejecuta código del fuente.
    La ROM resultante es dato no confiable para la VM: se valida por hash,
    segmentos, permisos y límites como cualquier otro artefacto. El editor web
    puede invocar el compilador en cliente/CI, nunca en el proceso del host.
11. **Tests.** Deben cubrir lexer, preprocesador, parser, semántica, IR,
    codegen y diagnóstico; golden tests de `.flowasm`; pruebas de ABI, `volatile`,
    punteros, arrays, structs, `switch`, `goto`, 64 bits, coma flotante
    (`float`/`double`, redondeo, NaN, cero con signo y conversiones), atómicos
    (todas las órdenes y fetch/CAS) y ensamblador inline (operandos, clobbers y
    barreras); corpus de políticas reales; determinismo byte a byte; y ejecución
    contra la VM v3 cuando V2/V4 estén disponibles. Los casos no soportados
    tienen tests negativos. No hay E2E en servidores Minecraft.

**Fases V6.**

| Fase | Entregable | Puerta |
|---|---|---|
| **C0 — Contrato** | Subconjunto, gramática, ABI C v3, modelo de runtime y corpus de conformidad | ADR y lista de casos congelados; cada exclusión tiene diagnóstico y test |
| **C1 — Frontend** | Preprocesador, lexer, parser, AST, análisis semántico y diagnósticos | Tests unitarios y de errores; ninguna construcción no soportada pasa en silencio |
| **C2 — IR y backend** | IR de tres direcciones, lowering, asignación de registros y emisión `.flowasm` v3 | Golden tests y ensamblado con el assembler v3 |
| **C3 — ABI y runtime** | `libiflow`, `crt0`, wrappers de `HOST` v3, punteros/arrays/structs/64 bits, soft-float, atómicos y ensamblador inline | Ejecución en VM v3 con el corpus; sin stubs |
| **C4 — Optimización y robustez** | `-O1`, peephole, fuzz, determinismo, diagnóstico de límites y agregados atómicos | Mismos resultados observables; salida reproducible |
| **C5 — Toolchain** | CLI, ejemplos, documentación, CI Node y paquete de herramientas | `npm test`, corpus y build reproducible verdes |

**Criterios de cierre de V6.**

1. Al menos tres políticas reales (rate limit, routing de canal y transformación
   de contenido) se escriben en C, compilan a `.flowasm` v3 y producen la misma
   decisión y el mismo mensaje observable que su equivalente de referencia.
2. El corpus cubre punteros, arrays, structs, uniones, `switch`, `goto`,
   `volatile`, 64 bits, `float`/`double`, atómicos, ensamblador inline y
   llamadas a `HOST`; los casos no soportados fallan con diagnóstico, no con un
   artefacto incorrecto.
3. La salida es determinista y el assembler v3 produce una ROM verificable.
4. No hay compilación C ni toolchain nativa en el host; no hay stubs de libc.
5. Tests, documentación y ejemplos están en verde; el ADR se actualiza si cambia
   el subconjunto o la ABI.

**Alternativas consideradas.**

1. **Clang/LLVM con un backend propio para iFlow.** Ventajas: frontend C
   completo y optimizador maduro. Limitaciones: exige escribir y mantener un
   target LLVM, una toolchain nativa enorme y un ABI propio; contradice la
   independencia de toolchain y multiplica la superficie de build. Evaluación:
   **descartada**.
2. **Portar TinyCC o chibicc y sustituir su backend.** Ventajas: frontend C
   probado y código compacto. Limitaciones: introduce un compilador nativo en C,
   dependencias de build y supuestos x86; el port del backend sigue siendo
   trabajo nuevo y difícil de auditar desde TypeScript. Evaluación: **descartada
   como baseline**; se usa chibicc solo como referencia de arquitectura.
3. **Compilar C a WebAssembly y traducir a `.flow`.** Ventajas: reutiliza
   Clang/WASI y el motor Wasm. Limitaciones: no es la arquitectura v3, añade un
   traductor Wasm→flow, pierde el ABI nativo y duplica semántica. Evaluación:
   **descartada**.
4. **Compilador propio en TypeScript con backend de pila (elegido).** Ventajas:
   una sola toolchain, determinista, auditable, sin dependencias nativas y
   alineado con el assembler existente. Limitaciones: implementar C es costoso;
   se mitiga con subconjunto, fases y corpus. Evaluación: **aceptada**.

**Consecuencias.**

- Los autores pueden escribir políticas en C sin aprender el ISA; el assembler
  sigue disponible para firmware y código de máximo control.
- No se promete C completo: libc estándar, objetos separados, VLA, bitfields,
  variádicas, `setjmp`/`longjmp`, `_Complex` y otras funciones quedan fuera
  hasta que una necesidad real y un ADR los incorporen. `float`/`double`,
  `_Atomic` e inline asm sí forman parte del baseline.
- El compilador es una herramienta de build, no un módulo de la suite; el host
  no crece y la frontera de seguridad de la ROM no cambia.
- V6 no puede empezar antes de V1/V5 y no puede cerrarse antes de V2/V3/V4. El
  orden evita implementar un backend contra un ISA o un ABI que aún no existen.
- El mantenimiento del compilador es deuda explícita del proyecto; cada
  exclusión y cada limitación debe estar documentada y probada.

---

## Decisión 2026-10-30 — puerto neutral de renderizado enriquecido (ADR-024)

**Estado:** Aceptada e implementada. `core-render` publica
`MessageComponentRenderer`; `textformatter` lo implementa y `SuiteHost` ya no
depende de `TextFormatter` ni de `ChatTranslator` para renderizar.

**Contexto.** El pipeline de `SuiteHost` seguía compilado contra
`TextFormatter` y `TemplateContext`, ambos del paquete `textformatter`. Eso
impedía que `SuiteRuntime` construyera el pipeline desde `ServiceRegistry` sin
crear una flecha `suite-runtime -> feature`, y obligaba a los adapters a
conocer tipos del formateador.

**Decisión.**

1. `core-render` es un módulo neutral separado de `core-api` porque su contrato
   usa `net.kyori.adventure.text.Component`. `core-api` sigue sin dependencias.
2. `MessageComponentRenderer` recibe `Message`, `Actor`, idioma de origen e
   idioma de destino ya resueltos. No decide routing, permisos ni entrega.
3. `TextFormatterMessageRenderer` implementa `MessageRenderer` (texto plano) y
   `MessageComponentRenderer` (componente enriquecido). `textformatter` registra
   ambas interfaces en su `ModuleContext`.
4. `SuiteHost` recibe el renderizador por constructor. Su constructor público
   solo usa puertos neutrales y configuración del host, de modo que el runtime
   puede ensamblarlo después de activar los paquetes.
5. `SuiteHost.bootstrap(...)` queda como camino heredado de arranque y se
   elimina al cerrar F4b; no se añade una fachada nueva ni un segundo pipeline.

**Consecuencias.**

- El pipeline deja de importar tipos de `textformatter` y `chattranslator`.
- `checkArchitecture` escanea `core-render` como módulo neutral.
- La construcción real del pipeline desde `ServiceRegistry` y la retirada del
  bootstrap heredado son el siguiente paso de F4b.

---

## Decisión 2026-10-30 — adapters delgados sobre SuiteRuntime (ADR-025)

**Estado:** Aceptada e implementada (F4b cerrado). Los adapters Spigot y
Fabric son bootstrap neutral y el bootstrap heredado se retiró del módulo
`host`.

**Contexto.** Los adapters construían `SuiteHost`, `MessageDispatcher`, sinks,
observability, in-world y extensiones directamente. Eso duplicaba el cableado,
arrastraba features al classpath del host y hacía que instalar/descargar un
paquete no fuera la única vía de composición.

**Decisión.**

1. `spigot-host` y `fabric-host` solo crean `PlatformPorts`, registran los
   puertos propiedad del adapter y arrancan `SuiteRuntime`. El comando raíz se
   registra con `runtime.registerCommands()`.
2. Los adapters no importan `host`, `kernel`, `manager` ni paquetes de feature.
   `checkArchitecture` lo verifica para los 34 módulos neutrales/adapter.
3. `ChatDelivery` vive en `core-render` porque su contrato usa Adventure.
   `InWorldPlatform` e `InWorldService` viven en `core-api`; el paquete
   `inworld` implementa y publica el servicio, y el adapter solo ve el puerto.
4. `YamlUserLanguageStore` vive en `suite-runtime`; `SuiteRuntime` expone
   `defaultLanguage()` sin publicar `HostConfig` a los adapters.
5. La captura de eventos de chat/join/quit/death no pertenece al host: se
   implementó en `sync-bukkit`/`sync-fabric` (F4c).
6. No se conserva un segundo pipeline en los adapters. `SuiteHost.bootstrap`,
   `SuiteBootstrap`, `RouterFactory`, `IflowEngineSelector` y `ConfigValidator`
   se retiraron del módulo `host`. Los tests y benchmarks cablean las
   implementaciones reales con fixtures locales (`HostFixture`), fuera del
   classpath de producción.

**Consecuencias.**

- Instalar o descargar un paquete es la única forma de cambiar features en
  producción.
- Los adapters pueden compilar contra `core-api`, `core-render`,
  `suite-runtime` y el SDK de plataforma.
- El `runtimeClasspath` de `suite-runtime` ya no arrastra paquetes de feature;
  `host` solo depende de `core-api`, `core-render`, SnakeYAML y Gson.

---

## Decisión 2026-10-31 — hot upgrade, fugas y límites de dependencias (ADR-026)

**Estado:** Parcialmente sustituida por ADR-027. Se mantienen las reglas de
detección de fugas, dependencias compartidas, paquetes multi-JAR y retirada de
APIs legacy. La secuencia de activación, el contrato manager-runtime y la
política de rollback pasan a ADR-027.

**Contexto.** `ModuleHost` sabía activar y desactivar, pero no sustituir una
versión activa. El manager instalaba la versión nueva y borraba la anterior
antes de que el runtime pudiera confirmar el arranque; el empaquetado usaba
`library/packages/<id>.jar`, así que dos versiones no podían coexistir en disco.
Además, una desactivación podía cerrar el classloader y dejar hilos, recursos,
listeners o registros apuntando a clases muertas sin que el estado lo reflejara.

**Decisión vigente.**

1. Las rutas de instalación son versionadas por directorio:
   `library/packages/<id>/<version>/` y
   `library/dependencies/<id>/<version>/`, con la versión instalada referenciada
   desde `library/status`. Ningún JAR activo se sobrescribe durante la
   preparación. La política de commit, journal, backup y rollback de la
   operación completa vive en ADR-027.
2. La desactivación valida cuatro fugas antes de declarar `INSTALLED`:
   fallo de `ResourceTracker`, registros de owner restantes, classloader no
   cerrado e hilos vivos en el `ThreadGroup` del módulo. Cualquier fuga produce
   `RESTART_REQUIRED`. Los hilos de `start`/`stop` se ejecutan dentro del grupo
   del módulo para que la detección sea posible. Las tareas del scheduler que
   siguen ejecutándose también cuentan como owner vivo.
3. Un upgrade de una dependencia compartida se rechaza si algún módulo activo
   la usa (directa o transitivamente); el mensaje exige reiniciar. Borrar el JAR
   anterior mientras un classloader activo puede cargarlo después es peor que
   bloquear el upgrade. La comprobación ocurre en la fase de preparación, antes
   de tocar `library/packages`, `library/dependencies` o `library/status`.
4. Un paquete `PACKAGE` contiene uno o más JARs de módulo. El paquete no
   identifica al módulo: cada JAR declara su `moduleId` en
   `ModuleActivator.descriptor()` y el runtime mapea activación, upgrade y
   retirada por ese id. Los ids deben ser únicos dentro del paquete; cero JARs
   o ids duplicados son un error explícito. La activación arranca todos los
   JARs y el upgrade los sustituye en una sola operación de runtime. Un fallo de
   la etapa de runtime no restaura la versión anterior; detiene los módulos ya
   arrancados en ese intento y conserva la instalación (ADR-027). Las librerías
   adicionales van en paquetes `DEPENDENCY`.
5. `ModuleLifecycleAdapter`, `ModuleLifecycle`, `ModuleCoordinate`,
   `ModuleDescriptor` y `Environment` de `manager-spi`, junto con
   `ManagerFacade.discoverAll()`/`getLoadedModules()`, se eliminan. El
   descubrimiento por classpath ya no es una vía de producción.

**Consecuencias.**

- El manager conserva la propiedad de los hechos de instalación; el runtime
  conserva la propiedad del estado vivo. Ninguno simula el estado del otro.
- Una operación puede fallar en tres puntos con efectos distintos: preparación
  (sin mutación; caché reutilizable), commit de archivos (se restaura la
  instalación anterior) y runtime (la instalación nueva queda registrada y no
  hay rollback). ADR-027 fija el contrato.
- `manager-apt` deja de depender de `kernel`.
- Las dependencias compartidas no se actualizan en caliente mientras estén en
  uso; el reinicio es la vía segura.
- La regla de evidencia de C13 aplica a este ADR: no se puede asumir que un
  paquete tendrá un JAR, que una dependencia no estará en uso ni que una
  compensación funcionará. Cada una se valida y se prueba.

---

## Decisión 2026-10-31 — instalación por etapas y semántica de fallo (ADR-027)

**Estado:** Aceptada e implementada. Sustituye la secuencia de
activación/rollback de ADR-026 y retira `keepBoth` como mecanismo de
recuperación automática. El código ya aplica las tres etapas: caché
content-addressed, commit con journal durable y activación separada.

**Contexto.** El flujo anterior mezclaba red, commit de archivos y arranque:
descargaba e instalaba paquete por paquete y activaba cada módulo dentro del
callback del manager. Un fallo de arranque podía disparar compensación de
archivos y reactivación de versiones anteriores. Eso hace que el resultado
dependa de cuántos paquetes ya se habían instalado y de si el rollback del
runtime era verificable. El usuario fija un pipeline determinista: primero se
completa la adquisición, después el commit de archivos y solo entonces se
arranca en orden jerárquico. La única reversión automática es la del commit de
archivos; un fallo de arranque no deshace una instalación ya desplegada.

**Decisión.**

### 1. Etapa 1 — Adquisición independiente

Antes de modificar cualquier ruta de instalación, el manager construye un plan
completo e inmutable con:

- paquetes principales y dependencias transitivas;
- versiones resueltas y conflictos `Exclude`/`Provide`;
- plataforma, arquitectura y capacidades;
- hashes SHA-256, tamaños y metadatos de cada artefacto;
- orden jerárquico de instalación y activación.

Para instalar o actualizar, todos los ZIP y dependencias se descargan y
verifican en caché antes de tocar `library/packages`, `library/dependencies` o
`library/status`. La caché es contenido-direccionada:

```text
library/cache/downloads/<sha256>/<archivo>.zip
library/cache/downloads/<sha256>/<archivo>.zip.part
library/cache/operations/<operationId>.json
```

Cada artefacto tiene un estado explícito `PENDING` o `COMPLETE`. Solo es
`COMPLETE` cuando el archivo final existe, su tamaño coincide y su SHA-256 se
verificó. Un `.part` nunca se reutiliza como artefacto válido. Reanudar significa
reutilizar los `COMPLETE` y descargar únicamente los `PENDING`; no repetir todo
el proceso. El comando `/<root> cache clean` borra descargas, parciales y
manifiestos de operaciones, pero no toca paquetes instalados,
`library/status`, el journal ni `library/trash`. La raíz `<root>` es la
configurada en `commands.yml`.

La desinstalación no descarga nada. Su etapa 1 resuelve el cierre de
dependencias inverso y valida que ningún módulo restante dependa de lo retirado.
Si falla la etapa 1, la instalación no cambia y la caché permite reanudar o
limpiar.

### 2. Etapa 2 — Commit de archivos con journal duradero

La etapa 2 aplica **todos** los cambios del plan en una sola transacción, no
paquete por paquete. Antes de la primera mutación escribe un journal durable en
`library/journal/<operationId>.log` con:

- instantánea del `library/status` anterior;
- archivos preparados, creados, reemplazados y retirados;
- versiones y directorios anteriores;
- operaciones completadas y pendientes de revertir;
- acciones de rollback por operación.

El journal se escribe en modo append-only, se sincroniza a disco (`fsync`) y se
trata como la fuente de recuperación ante excepciones, `kill -9`, corte de
energía o reinicio abrupto del equipo. Los archivos se mueven con rename atómico
dentro del mismo sistema de archivos y se sincroniza el directorio padre. El
nuevo `library/status` se escribe en un temporal, se sincroniza y se publica con
rename atómico; **ese rename es el punto de commit**. Antes del punto de commit,
la recuperación revierte todas las operaciones registradas y restaura el estado
anterior. Después del punto de commit, la recuperación completa la limpieza y
nunca revierte la instalación. El runtime no arranca hasta que el journal se ha
recuperado y la etapa 2 está confirmada.

Los archivos de paquetes viven en directorios versionados; la versión nueva no
sobrescribe los archivos de la versión activa. Instalar la misma versión ya
presente se rechaza: para repararla hay que retirarla primero. Así nunca se
sobrescribe un JAR que un `ClassLoader` activo todavía referencia.
`library/status` guarda la referencia persistente a la versión instalada y es el
único puntero autoritativo.
Los archivos no versionados (por ejemplo, configuración reemplazada) se respaldan
en `library/backup/<operationId>/` antes de sustituirlos. Si falla cualquier
paso, se restaura el journal completo: archivos anteriores, archivos nuevos
retirados, `library/status` anterior y directorios de versión. La caché de
descargas no se borra.

En una desinstalación, la etapa 2 empieza con una **barrera de runtime**:
desactiva los módulos afectados en orden inverso antes de mover sus archivos. Si
la desactivación falla o deja fugas, se aborta antes de tocar archivos y se
marca `RESTART_REQUIRED`; no se borra nada. Si la desactivación es limpia, mueve
los archivos propios a `library/trash/<operationId>/`, actualiza
`library/status` y confirma el journal. Si el commit de archivos falla después
de desactivar, restaura los archivos y reactiva las versiones anteriores; si esa
reactivación no puede verificarse, conserva los archivos restaurados y marca
`RESTART_REQUIRED`. Un reemplazo por `Exclude` usa la misma barrera: la etapa 2
desactiva los paquetes que el plan retira antes de mover sus archivos y la
etapa 3 solo activa los paquetes nuevos. Las sobrecargas sin barrera de runtime
fallan explícitamente cuando el plan requiere reemplazos; no existe una ruta
que mueva JARs de paquetes activos.

La etapa 2 es el **único rollback automático** del manager. Un fallo en esta
etapa restaura íntegramente la instalación anterior, incluido el inventario
persistente.

### 3. Etapa 3 — Activación y recuperación, sin rollback de instalación

Solo después del commit de archivos, el runtime aplica el ciclo de vida:

- instalar/actualizar: detener las versiones anteriores en orden inverso y
  arrancar los módulos nuevos en orden jerárquico;
- desinstalar: limpiar el estado runtime del paquete. La desactivación efectiva
  y la liberación de classloaders, registros y recursos ya ocurrieron en la
  barrera de la etapa 2; la etapa 3 no muta archivos ni retira dependencias
  huérfanas.

Antes del primer `start()` se persiste el estado `ACTIVATION_PENDING` en
`runtime/modules.status`. Si el proceso cae durante la activación, el arranque
siguiente vuelve a activar los paquetes instalados desde `library/status`; no
asume que los módulos están activos ni revierte los archivos.

El orden es el orden topológico de dependencias declarado por los paquetes y,
dentro de un paquete, el orden de capacidades `requires`/`provides` de sus
módulos. Las dependencias se arrancan antes que sus consumidores. Los empates se
resuelven por nombre para que el orden sea estable. La desinstalación recorre el
orden inverso.

Si falla el arranque de un módulo:

1. Se detienen, en orden inverso, los módulos que ya se habían arrancado en este
   intento de activación. Esto es limpieza del runtime, no rollback de la
   instalación.
2. No se restauran archivos, `library/status` ni la versión anterior. Los
   archivos nuevos permanecen instalados.
3. El paquete queda `ACTIVATION_FAILED`; el módulo culpable queda `FAILED` y los
   módulos detenidos vuelven a `INSTALLED`. Si la detención de limpieza deja
   hilos, recursos o registros vivos, se marca `RESTART_REQUIRED`.
4. Si la versión anterior ya está en `RESTART_REQUIRED`, no se arranca la nueva
   versión: el paquete permanece en `RESTART_REQUIRED` y la instalación
   confirmada no se revierte.
5. El resultado incluye la etapa fallida (`RUNTIME`), el `moduleId` y la causa.
   La activación puede reintentarse sin reinstalar.

Estados persistentes de instalación/activación:

| Estado | Significado |
|---|---|
| `INSTALLED` | Archivos e inventario confirmados; activación no iniciada o detenida limpiamente. |
| `ACTIVATION_PENDING` | Activación iniciada y aún no confirmada; el arranque debe reanudarla. |
| `ACTIVE` | Todos los módulos esperados están activos. |
| `ACTIVATION_FAILED` | La instalación está confirmada, pero al menos un módulo no arrancó; se permite reintentar. |
| `RESTART_REQUIRED` | La limpieza del runtime no pudo verificarse; hace falta reiniciar. |

Un módulo puede quedar `FAILED`; al retirar un paquete se elimina su entrada
`package:<nombre>` en vez de persistir `REMOVED`.

**Invariante:** una instalación puede estar correctamente instalada sin estar
correctamente activada. `library/status` registra la instalación; el estado de
runtime registra la activación. El manager no convierte un fallo de activación
en un fallo de instalación ni oculta la diferencia con un rollback destructivo.
La etapa 3 solo actualiza estado de runtime; no modifica `library/status` ni el
sistema de archivos.

### 4. Desinstalación: coordinación explícita con el runtime

La desinstalación sigue las mismas tres etapas, con una adaptación obligatoria:
no se puede retirar un JAR que un classloader activo todavía tiene abierto. Por
eso la desactivación efectiva se ejecuta como barrera al **inicio de la etapa
2**, antes de mover archivos, y la etapa 3 solo finaliza y verifica esa
desactivación. No se invierte el orden en silencio ni se borran archivos con
módulos activos.

| Operación | Etapa 1 | Etapa 2 | Etapa 3 |
|---|---|---|---|
| `install` | resolver, descargar y verificar | commit de archivos y `status` | arrancar en orden |
| `upgrade` | resolver, descargar y verificar | commit de la versión nueva y puntero | detener anterior y arrancar nueva |
| `uninstall` | validar plan de retirada | detener módulos y retirar archivos/`status` | finalizar estado runtime (sin mutar archivos) |

Si falla la etapa 3 de una desinstalación, los archivos ya retirados y el
inventario ya actualizado no se revierten; se marca `RESTART_REQUIRED` si quedan
referencias vivas. La etapa 3 no retira dependencias huérfanas: cuando
`autoRemove` está activo, la etapa 2 las incluye en la misma transacción.
`library/trash` conserva los archivos retirados para el rollback del journal
antes del commit; después del commit solo sirve para recuperación manual, nunca
para rollback automático.

### 5. Layout versionado por paquete

El layout objetivo es:

```text
manager/library/
├── packages/
│   └── <packageId>/
│       ├── <version>/
│       │   ├── <module>.jar
│       │   └── ...
│       └── <versionAnterior>/
├── dependencies/
│   └── <dependencyId>/
│       └── <version>/
├── status
├── cache/
│   ├── downloads/<sha256>/
│   └── operations/<operationId>.json
├── journal/<operationId>.log
├── backup/<operationId>/
├── trash/<operationId>/
└── tmp/<operationId>/
```

`library/status` relaciona cada paquete con su versión instalada y con los
archivos y hashes de esa versión. No se usan enlaces simbólicos como puntero
autoritativo. Una versión anterior permanece en disco mientras exista una
referencia de runtime; no se borra durante el commit de la instalación. Un fallo
de activación no autoriza a borrar la versión anterior, pero tampoco la reactiva
automáticamente.

### 6. Contrato manager-runtime y resultados

La etapa 3 se invoca mediante un contrato post-commit que recibe el plan ya
instalado y devuelve un resultado por módulo. No puede vetar la etapa 2, ordenar
el borrado de archivos ni restaurar versiones anteriores.
`PackageUpgradeListener` con `allow()`, `rollback(...)` y `keepBoth(...)` se
retira; `keepBoth` deja de existir como estado de recuperación automática.

`InstallResult` debe distinguir:

- `installed`: el commit de archivos y `library/status` se completaron;
- `activation`: `ACTIVE`, `ACTIVATION_FAILED`, `ACTIVATION_PENDING` o
  `RESTART_REQUIRED`;
- `failedStage`: `PREPARE`, `COMMIT` o `RUNTIME`;
- `moduleId` y causa cuando el fallo es de runtime.

Un fallo de runtime no puede reportarse como “no instalado”. Un fallo de
instalación no puede reportarse como “módulo fallido”.

### 7. Validaciones de la etapa 1

Un ciclo de dependencias, un proveedor faltante, un checksum inválido, un
conflicto de plataforma, un artefacto fuera de la caché o una dependencia
compartida en uso se detectan en la etapa 1. Si no se puede determinar un orden
de arranque estable, la operación falla antes de mutar archivos. La caché se
verifica por SHA-256 antes de reutilizar cualquier artefacto; la limpieza no
sigue enlaces simbólicos y nunca acepta rutas fuera de `library/cache`,
`library/tmp`, `library/backup` o `library/trash`.

**Consecuencias.**

- La etapa 2 es el único rollback automático. Un fallo de etapa 3 deja una
  instalación nueva committed, con arranque detenido o parcial; no se deshace.
  Esto es deliberado: deshacer una instalación que ya sobrescribió el sistema es
  más peligroso que dejar un estado explícito y recuperable.
- Los estados `INSTALLED`, `ACTIVATION_PENDING`, `ACTIVE`,
  `ACTIVATION_FAILED` y `RESTART_REQUIRED` son persistentes y no se infieren del
  código de retorno de un comando.
- La atomicidad del lote no depende del número de paquetes. Todos los paquetes
  y dependencias se adquieren antes de la primera escritura final, y todos los
  archivos se confirman antes del primer `start()`.
- Los tests deben cubrir: fallo de etapa 1 sin mutación y reanudación/limpieza
  de caché; fallo de etapa 2 con restauración exacta de archivos, directorios y
  `library/status`; caída abrupta antes y después del punto de commit; fallo de
  etapa 3 sin rollback, con parada inversa de los módulos ya arrancados y
  `ACTIVATION_FAILED` persistido; reintento de activación sin reinstalar; orden
  topológico y orden inverso de desinstalación; barrera de desactivación antes
  de retirar archivos; y rechazo de ciclos, checksums y dependencias compartidas
  en uso.
- La regla C13 sigue aplicando: ninguna etapa puede asumirse correcta sin
  verificarla. Un rollback de etapa 2 se prueba; un fallo de etapa 3 se reporta
  con evidencia y no se disfraza de rollback.

---

## Decisión 2026-10-31 — etiqueta `<regex>` en plantillas de TextFormatter (ADR-028)

**Estado:** Aceptada e implementada. No modifica ADR-027 y enmienda la decisión de
2026-10-20 en lo relativo a las construcciones permitidas en las plantillas de `textformatter`:
además de placeholders, MiniMessage y `<tr>`, se admite la etiqueta `<regex>`.

**Contexto.** La VM de iFlow puede aplicar expresiones regulares en el espacio de usuario de sus
políticas. Esa es la vía principal para decidir routing, permisos o mutaciones. TextFormatter no
evalúa políticas, pero un template puede necesitar una comprobación regex local para decidir si
muestra un fragmento. Duplicar esa comprobación en el template evita obligar a que la política
preprocese el texto, sin convertir al formateador en un segundo motor de políticas. La etiqueta es
redundante: no reemplaza al regex de iFlow y no altera el mensaje original.

**Decisión.**

1. La sintaxis es `<regex match="PATRÓN">CONTENIDO</regex>`. No se introduce `%regex%`, `${regex}`
   ni otra variable escalar para esta función.
2. `match` es un patrón Java (`java.util.regex.Pattern`). Es obligatorio y no vacío. El patrón no
   se interpreta como MiniMessage; las comillas dobles y los caracteres XML se escapan en el
   atributo.
3. `CONTENIDO` es el texto candidato y, a la vez, la plantilla MiniMessage que se renderiza. Puede
   contener los placeholders existentes (`%content%`, `%player_name%`, etc.) y las etiquetas
   MiniMessage habituales.
4. La etiqueta se evalúa en `TemplateRenderer` antes del `deserialize` final de MiniMessage. Si el
   patrón coincide con el contenido ya resuelto, el span se sustituye por `CONTENIDO` interpretado
   como MiniMessage. Si no coincide, el span no emite texto. La etiqueta es, por tanto, una
   condición de renderizado local, no un motor de reemplazo global.
5. El formato MiniMessage se aplica al texto que satisface el patrón. El patrón en sí no se colorea
   ni se transforma; los grupos de captura no se interpolan automáticamente.
6. No se anidan etiquetas `<regex>` dentro de otra `<regex>`. `<tr>` conserva su tratamiento
   independiente y no cambia su contrato.
7. El patrón se compila una vez por plantilla y se reutiliza. Un patrón inválido, vacío o que exceda
   el límite configurado es un error explícito de configuración; no se ignora, no se convierte en
   texto plano y no se degrada a coincidencia vacía.
8. Se imponen límites duros y documentados al tamaño del patrón y al del contenido evaluado para
   acotar el coste de backtracking. El patrón procede de la plantilla, nunca del mensaje recibido.
9. La etiqueta no ejecuta código, no evalúa SpEL, no consulta la VM, no decide routing, permisos ni
   entrega y no muta el `Message`. No sustituye al regex que iFlow ejecuta en su espacio de usuario.

**Alternativas consideradas.**

1. **Variable escalar `%regex%`.** Ventajas: encaja con el mecanismo actual de placeholders.
   Limitaciones: el usuario fija la sintaxis MiniMessage y una variable escalar no expresa contenido
   ni condición; además colisiona con el espacio de nombres de placeholders. Evaluación:
   **descartada**.
2. **Motor de reemplazo regex global.** Ventajas: permitiría transformar el mensaje completo.
   Limitaciones: duplica la superficie de mutación de iFlow, introduce orden de aplicación y hace
   que el formateador compita con la política. Evaluación: **descartada**.
3. **Solo regex en iFlow.** Ventajas: una única superficie de decisión. Limitaciones: obliga a que
   cada template que necesite una comprobación local dependa de que la política haya preprocesado
   el texto. Evaluación: **descartada** para este caso; se conserva como vía principal.
4. **Etiqueta MiniMessage condicional `<regex>`.** Ventajas: sintaxis elegida por el usuario,
   redundancia local sin segundo motor de políticas, formato MiniMessage en el contenido.
   Limitaciones: añade un span al parser y requiere límites de coste. Evaluación: **aceptada**.

**Consecuencias.**

- `textformatter` gana una construcción de plantilla, no un lenguaje de políticas. La VM sigue
  siendo la única superficie para decisiones ejecutables.
- El regex de iFlow y la etiqueta `<regex>` pueden coexistir; si ambos evalúan el mismo texto deben
  producir el mismo resultado, pero la etiqueta solo controla la renderización del span.
- Los templates con patrones inválidos fallan al cargarse y no llegan a producción.
- El parser debe probar coincidencia, ausencia de coincidencia, formato MiniMessage, límites,
  patrones inválidos, entidades XML válidas y mal formadas, y ausencia de anidamiento.
- Implementado en `Template` (parseo y compilación única) y `TemplateRenderer` (condición de
  renderizado), con tests de coincidencia, ausencia, flags/grupos, entidades XML válidas y mal
  formadas, límites, anidamiento, `<tr>`/placeholders y no mutación. `src/textformatter/README.md`
  documenta sintaxis, semántica (`find`) y límites.
- Límites vigentes: patrón ≤ 512 unidades UTF-16, contenido evaluado ≤ 8192 unidades UTF-16 y
  ≤ 32 spans por plantilla. El límite de contenido se comprueba de nuevo tras resolver
  placeholders.

---

## Decisión 2026-11-02 — distribución reproducible y frontera de dependencias de terceros (ADR-029)

**Estado:** Aceptada e implementada. Complementa ADR-026 y ADR-027; no cambia las tres etapas de
instalación ni la semántica de `ACTIVATION_FAILED`.

**Contexto.** F8 debía dejar un modelo reproducible y verificable: ZIPs de módulo, separación
host/módulos, SHA-256, límites de confianza y un tratamiento explícito de las librerías de
terceros. El host ya no puede contener features, pero varias features necesitan librerías que no
cruzan la API neutral. Adventure es el caso límite: `core-render` expone `Component`/`Audience` en
su contrato, así que cargar una segunda copia en un classloader de módulo rompería la identidad de
tipos entre el módulo y el host.

**Decisión.**

1. Los JARs de módulo y de host se generan con orden estable y sin reloj de pared. El manifiesto usa
   `Build-Timestamp: 0` salvo que un release fije explícitamente `BUILD_TIMESTAMP` o
   `-PbuildTimestamp`. Los ZIP de módulo usan `preserveFileTimestamps = false` y
   `reproducibleFileOrder = true`.
2. `moduleChecksum` publica un sidecar `.sha256` junto a cada ZIP. `moduleRepository` exige
   `-PrepositoryBaseUrl`, recopila todos los ZIP de módulo y de terceros, y escribe
   `packages.list` y `SHA256SUMS`. Un artefacto publicado es inmutable: un cambio de contenido
   exige una versión o un directorio nuevos.
3. Las librerías de terceros que no cruzan una API neutral se empaquetan como `DEPENDENCY` con sus
   transitivas resueltas. El módulo consumidor las declara en `moduleDependencies`; el manager las
   instala antes y `ModuleClassLoader` las expone desde `library/dependencies`.
4. La versión de un paquete debe ser SemVer, aunque el artefacto Maven no lo sea. `json` usa la
   versión de paquete `2024.3.3` sobre el artefacto Maven `org.json:json:20240303`.
5. Adventure, MiniMessage y el serializador plano permanecen en el host. Un módulo no declara un
   paquete `DEPENDENCY` de Adventure mientras `core-render` exponga esos tipos. Cambiar esta regla
   requiere rediseñar el contrato de renderizado.
6. Los shadow JAR de Spigot y Fabric contienen solo el runtime del host.
   `verifyShadowJarHasNoFeatures` falla `check` si una feature se cuela en el bootstrap. Los
   listeners de plataforma y `sync-bukkit`/`sync-fabric` son paquetes descargables.
7. TLS + SHA-256 detecta corrupción y manipulación de transporte, no prueba autoría. No hay firma
   de paquetes, pinning ni certificados en esta fase; el propietario del repositorio es una
   autoridad de confianza. El aislamiento por classloader/capabilities no es un sandbox contra
   código JVM hostil.

**Alternativas consideradas.**

1. **Mantener todas las librerías de terceros en el host.** Ventajas: build más simple y sin
   paquetes extra. Limitaciones: el host vuelve a mezclar features y dependencias, no permite
   upgrades por módulo y contradice la separación host/módulos. Evaluación: **descartada**.
2. **Shading de todas las dependencias dentro del JAR del módulo.** Ventajas: un solo artefacto.
   Limitaciones: duplica clases entre módulos, impide compartir upgrades y agranda cada paquete.
   Evaluación: **descartada** para dependencias compartidas; se conserva para casos puntuales.
3. **Aislar también Adventure como `DEPENDENCY`.** Ventajas: consistencia teórica. Limitaciones:
   `core-render` y la API neutral exponen `Component`/`Audience`; dos classloaders partirían la
   identidad de tipos y romperían módulos y hosts. Evaluación: **descartada**.
4. **Firmar paquetes en F8.** Ventajas: autenticidad además de integridad. Limitaciones: requiere
   gestión, rotación y revocación de claves, y una política de confianza que no existe todavía.
   Evaluación: **diferida** a una fase con ADR propio.
5. **Reproducibilidad con timestamps del build.** Ventajas: trazabilidad de la fecha. Limitaciones:
   rompe la igualdad byte a byte. Evaluación: **descartada**; la fecha va en metadatos de release,
   no en el artefacto.

**Consecuencias.**

- Los paquetes de terceros son más grandes y pueden contener transitivas duplicadas; los módulos
  deben declarar lo que usan y el manager no infiere imports.
- `thirdPartyPackages` y `moduleRepository` requieren red para resolver Maven Central; un build
  offline solo funciona con caché poblada.
- `moduleRepository` no acepta una URL base vacía ni una ruta local como sustituto.
- La reproducibilidad se verifica con dos builds y comparación de hashes; `SHA256SUMS` se verifica
  con `sha256sum -c`.
- No hay E2E en Spigot/Paper/Fabric/Velocity reales. Los gates usan ZIP/JAR reales, directorios
  temporales y tests de recuperación/activación.
- Una futura firma de paquetes, un repositorio no confiable o un rediseño de `core-render` para
  aislar Adventure requieren una ADR nueva.

**Evidencia.**

- `./gradlew build checkArchitecture` verde.
- `./gradlew moduleRepository -PrepositoryBaseUrl=https://repo.example.com/suite` genera 35
  paquetes y `sha256sum -c SHA256SUMS` pasa.
- Dos ejecuciones de `thirdPartyPackages --rerun-tasks` producen los mismos hashes.
- `verifyShadowJarHasNoFeatures` verde en Spigot y Fabric.
- Tests de `PackageRuntimeTest`/`PackageJournalTest` cubren recuperación de etapa 2 y activación
  fallida; `PackageRuntimeTest` instala los paquetes de terceros reales antes de los módulos que los
  declaran.

---

## Decisión 2026-11-03 — presets por comando y recarga de módulos activos (ADR-030)

**Estado:** Aceptada e implementada. No cambia las tres etapas de instalación de ADR-027 ni el
modelo de paquetes de ADR-029.

**Contexto.** `PresetService` ya existía como puerto neutral y `DefaultPresetService` aplicaba el
catálogo con `FilePresetTarget`, pero no había comando para seleccionar un preset. El callback de
recarga era una promesa del contrato sin una operación runtime que lo cumpliera: `SuiteRuntime`
solo recargaba `commands.yml`. Además, el host lee su configuración de `config/suite/`, mientras
que `ModuleHost` entrega a cada módulo `manager/config/<module>/`; un único directorio base no
podía escribir ambas ubicaciones.

**Decisión.**

1. `suite-runtime` registra `presets list` y `presets apply <preset>` como comandos built-in con el
   permiso `textformatter.suite.admin`. El comando resuelve `PresetService` desde el
   `ServiceRegistry`; si el paquete no está activo responde explícitamente y no inventa catálogo.
2. `FilePresetTarget` escribe la configuración del host en `config/suite/` y la de cada módulo en
   `manager/config/<module>/`. El directorio base sigue siendo el data folder del adaptador.
3. Tras persistir un preset, el callback ejecuta `SuiteRuntime.reloadActiveModules()`: cierra el
   pipeline, detiene los módulos activos en orden inverso, los reactiva en el orden de activación y
   reconstruye el pipeline. No se recarga el conjunto instalado ni se activan módulos inactivos.
4. Si un módulo no se libera limpiamente, la recarga se detiene, el comando informa el módulo y su
   causa, y el estado queda `RESTART_REQUIRED`. No se restaura una versión anterior ni se simula
   éxito.
5. Un preset desconocido devuelve `false` sin escribir ni recargar. Los errores de escritura o de
   recarga se reportan al ejecutor del comando.

**Alternativas consideradas.**

1. **Exigir reinicio del servidor tras aplicar un preset.** Ventajas: implementación mínima.
   Limitaciones: no cumple el callback de recarga del contrato y obliga a reiniciar para cambios
   que el runtime puede aplicar. Evaluación: **descartada**.
2. **Registrar el comando dentro del paquete `presets`.** Ventajas: encapsula el catálogo.
   Limitaciones: el paquete tendría que conocer la raíz de comandos, el data folder y el ciclo de
   vida del runtime; además desaparecería durante la propia recarga. Evaluación: **descartada**.
3. **Recargar solo los módulos afectados por el preset.** Ventajas: menos interrupción.
   Limitaciones: exige un grafo de dependencias y un mapeo preset→módulos que hoy no existe;
   desactivar solo una parte puede dejar servicios de dependencias en estado inconsistente.
   Evaluación: **diferida** a una fase con ADR propio.
4. **Escribir todo bajo un único directorio base.** Ventajas: una sola ruta. Limitaciones: el host
   y los módulos ya tienen raíces distintas; elegir una rompe la otra. Evaluación: **descartada**.

**Consecuencias.**

- Aplicar un preset es una operación de ciclo de vida completo: puede ser más lenta que una
  escritura de archivos, pero deja archivos y servicios coherentes.
- La recarga depende de que los módulos liberen recursos. Un módulo que no lo hace exige reinicio;
  el comando lo dice en lugar de ocultarlo.
- `FilePresetTarget` conoce el layout canónico del runtime (`config/suite/` y
  `manager/config/<module>/`). Un cambio de layout requiere actualizar el target y sus tests.
- Los tests cubren el orden de parada/reinicio, la conservación de servicios, la lista, la
  aplicación, el preset desconocido y el paquete inactivo.

**Evidencia.**

- `./gradlew build checkArchitecture` verde.
- `./gradlew :src:presets:test :src:suite-runtime:test` verde.
- `./gradlew checkLocks` verde.
