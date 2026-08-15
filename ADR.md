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
- **Un único permiso por canal** (`cht.<channel>`): poseerlo es la suscripción.
  Default ACCEPT (canal abierto). Con un solo nodo de permisos por canal, el
  envío y la recepción comparten el mismo permiso; la asimetría
  (p.ej. "puede leer pero no escribir") no se expresa con un segundo permiso
  del canal, sino con reglas de iFlow (firewall), que sí distinguen lado.
  El emisor se ve a sí mismo como parte del tail de emisor, sin permiso extra.

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

- Destino nombrado con **un único permiso** (`permission: cht.<channel>`),
  default ACCEPT. La asimetría lado-send/lado-receive vive en reglas de iFlow,
  no en el canal. Suscripción ≡ poseer el permiso.
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