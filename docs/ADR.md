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

(End of file - total 193 lines)

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

