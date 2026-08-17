# ADR — TextFormatter Suite v2.1

Fecha: 2026-08-14 · Estado: aceptado (pendiente de revisión final del diseño)

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