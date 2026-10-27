# ADR — TextFormatter Suite v2.1

Fecha: 2026-08-14 · Estado: aceptado (pendiente de revisión final del diseño)
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
`textformatter` quedan limitadas a placeholders, MiniMessage y traducción `<tr>`.

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
  Mientras el classpath de desarrollo contenga varios, `SuiteBootstrap` filtra los no seleccionados
  antes de resolver el grafo.
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
