# ADR — TextFormatter Suite v2.1

Fecha: 2026-08-14 · Estado: aceptado (pendiente de revisión final del diseño)
> **Nota 2026-09-02**: Proyecto en F4 (fabric-host). Decisiones base vigentes. Ver `PLAN.md` y `PROMPT_NOW.md` para estado actual.

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
   previo (PROMPT_NOW FASE 2.1), que lo situaba en core-api.
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

**Implementación (misma fecha).** `suite/spigot-host`: plugin Spigot real
(`TextFormatterSuite`) que materializa la decisión anterior —
`SpigotActorDirectory` (locale vía `Player#getLocale`, handles nativos),
`SpigotChatDelivery` (BukkitAudiences, hop a main thread, normalización de
nombres de sonido con strip de extensiones `.mp3/.ogg/.wav`) y bootstrap
`SuiteHost`+`MessageDispatcher` con recarga total desde disco (`/suite
reload`). El chat cancela el broadcast vanilla y despacha dos unidades
atómicas (eco INITIATOR + OTHERS). `TranslatorsConfig` (módulo host) carga
`translators/*.yml`; `TranslationService.activeName()` expone el proveedor.
Los módulos hermanos se consumen vía `files()` a sus jars (determinista sin
red) hasta el composite build (FASE 3). Fat-jar pendiente de disponer red.