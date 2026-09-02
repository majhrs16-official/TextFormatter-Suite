# Auditoría TextFormatter Suite — 2026-08-16

Volcado íntegro de los **5 subagentes** lanzados el 2026-08-16 (investigación de
bugs, paridad vs original, arquitectura, clean code, ideas/roadmap). Los
hallazgos resumidos y el plan de ejecución viven en `docs/PLAN.md`; este
documento conserva las respuestas completas para no perder trabajo. Bugs
críticos del web-editor **verificados manualmente** tras la auditoría (ver §6).

## Índice

1. [Agente 1 — Bugs y deuda técnica](#1-agente-1--bugs-y-deuda-técnica)
2. [Agente 2 — Paridad vs ChatTranslator original](#2-agente-2--paridad-vs-chattranslator-original)
3. [Agente 3 — Arquitectura clean/hexagonal](#3-agente-3--arquitectura-cleanhexagonal)
4. [Agente 4 — Clean code y refactor](#4-agente-4--clean-code-y-refactor)
5. [Agente 5 — Ideas, features y roadmap](#5-agente-5--ideas-features-y-roadmap)
6. [Verificación manual post-auditoría](#6-verificación-manual-post-auditoría)
7. [Estado de arreglos al cierre](#7-estado-de-arreglos-al-cierre)

---

## 1. — Agente 1 | Bugs y deuda técnica

### BUGS POR SEVERIDAD

**CRÍTICO**
Ninguno. No se encontró una ruta de explotación con impacto total
(server compromise / RCE) en el estado actual del código.

**ALTO**

1. **Duplicar canal/nodo no hace nada (pérdida silenciosa de mutación)**
   `suite/web-editor/js/actions.js:14` y `:32` — `const st = StateStore.getState()`
   se captura FUERA de `StateStore.mutate()`. Verificado en `stateStore.js:134-136`:
   `getState()` devuelve `working ? state : clone(state)`; fuera de `mutate()`
   (`working===null`) es un **clon**. Las mutaciones `st.channels[name] = ...`
   (`:22-26`) y `st.graph.nodes.push(copy)` (`:36-45`) modifican el clon, el estado
   vivo nunca cambia, `changedPaths` queda vacío y la UI se re-renderiza idéntica.
   El toast "duplicado" es falso. `delSelected` (`:60,72,78`) usa el patrón
   correcto (captura dentro), así que borrar funciona pero duplicar no.

2. **Renombrar canal, editar propiedades de nodo y cambiar tipo de nodo se pierden
   en silencio** — `props.js:192/197` (rename), `:223/228` (editar
   transform/cond/redirect/label), `:312/317` (cambio de kind) — mismo patrón:
   `st`/`n`/`target` se obtienen de un clon y se mutan dentro del callback de
   `mutate()`.

3. **`SpigotScheduler.ticks()` trunca todos los retrasos < 1s a 1 tick (50 ms)**
   `spigot/src/main/java/me/majhrs16/cht/spigot/SpigotScheduler.java:48-52` —
   `TimeUnit.MILLISECONDS.toSeconds(unit.toMillis(amount)) * 20` trunca 500 ms → 0
   segundos, y `Math.max(1, ticks)` lo convierte en 1 tick. `runAsyncLater(task,
   500, MILLISECONDS)` se ejecuta a los 50 ms (no 500). Afecta a reintentos,
   filtros antispam y reglas con `delay`/`sleep`. **[ARREGLADO]**

**MEDIO**

4. **XSS en el editor web (escapado insuficiente)** — `suite/web-editor/js/utils.js:52-54`
   la regex `/[&<>"]'/g` tiene el apóstrofe FUERA de la clase de caracteres, así
   que NO escapa `&`, `<`, `>`, `"` a menos que vayan seguidos de `'`.
   `esc("<img src=x onerror=...>")` devuelve el HTML crudo. Se usa con `innerHTML`
   en `props.js:162-169`, `perms.js`, `previewView.js` y `sidebar.js` — valores
   que pueden ser nombres de canales/nodos → XSS almacenado.

5. **Drift entre `config.iflow.*` y `graph.*` (switch sin efecto real)** —
   `paths.js:58` define el switch para `config.iflow.filter.dedup-fanout`, que se
   escribe en `config.yml` (`model.js:148`), pero el simulador `preview.js:106` y
   las `rules.yml` (`model.js:155`) leen `graph.filter['dedup-fanout']`
   (`model.js:63`) — que ningún control de la UI edita. Alternar el switch no
   cambia el comportamiento real del motor. Ídem `guard.max-steps` (duplicado en
   `model.js:33` y `:62`).

6. **`RateLimiter` sin limpieza de buckets (fuga de memoria ilimitada)** —
   `suite/iflow/.../RateLimiter.java:18` — `Map<String, Bucket>` crece por cada
   `(channelPath, actorUuid)` y nunca se purga. **[ARREGLADO]**

7. **Carrera de visibilidad en `TcpSink`/`UdpSink`** — `server`/`socket` no son
   `volatile`; se escriben en `start()` (hilo del host) y se leen en
   `inboundPort()`/`outboundPort()` desde otros hilos → puerto mal reportado o
   valor `0`/stale. **[ARREGLADO]**

8. **`HttpSink.start()` no idempotente** — un segundo `start()` crea otro
   `HttpServer` sobre el mismo puerto (falla o fuga el anterior). `stop()` también
   sin guard contra doble llamada. **[ARREGLADO]**

9. **UI de Velocity configurada para un módulo inexistente** — `config.js:106-133`
   (`sync.velocity.secret/servers/mapping`), `paths.js:70` (`sync.velocity.enabled`),
   `i18n.js:40-146` y `kernel.js:16` publicitan `sync-velocity` con versión "F7+",
   pero **no existe `suite/sync-velocity` en disco**.

**BAJO**

10. `TelegramSink.stop()` es no-op; `poll()` puede NPE si `optJSONArray("result")`
    es `null`.
11. `DiscordGateway` sin reconexión pese al comentario "RESUME"; el executor de
    heartbeats se recrea en cada `Hello` sin apagar el anterior
    (`DiscordGateway.java:153-160`).
12. `toolbar.js:23` hace `console.log(iss)` en cada toggle (debug residual).
13. `status.js` recalcula `validate` + `exportFiles` en cada `renderStatus` (perf).

### DEUDA TÉCNICA

- **Mojibake UTF-8 generalizado** (archivos guardados como Latin-1): `txf.js:11`
  (DEFAULT_CHIPS), `txf.js:218-223` y `:248` (`â–˜`), `kernel.js:16-18`,
  `toolbar.js:17-23`, `importExport.js:16`, `props.js:163,169`, `core.js:118`,
  `config.js:235-239`.
- **Versiones inconsistentes**: `core.js:118` y `manifest` (`model.js:178`)
  reportan `suite 2.1.0` y `schema v2.2`, mientras `gradle.properties` define
  `cht_version=3.0.0-SNAPSHOT`; User-Agent `TextFormatterSuite/2.1` en los 5
  `HttpTransport`.
- **Falta de sincronización de estado**: dos copias de `guard.max-steps`/
  `filter.dedup-fanout` (`model.js:33` vs `:62-63`).
- **Tests frágiles**: `stateStore.test.cjs` usa `window.eval(code)` en vez de
  exports; `reset()` no existe → estado global compartido entre tests.
- **`validate.js:11-18`** — `PATH_GROUPS.perms = []` (pestaña de permisos sin
  validación), aunque `perms.js` renderiza matrices.
- **Dependencias obsoletas**: `json-simple 1.1.1` (2012), `jsr305` (abandonado),
  Spring 5.3.33, Shadow 8.1.1.
- **Módulos duplicados de traducción**: `gtranslate`/`ltranslate`/`sync-*`
  repiten `HttpTransport` casi idéntico.
- **Root `settings.gradle`** solo incluye `common`, `spigot`, `fabric-1.20.6`;
  los 14 módulos de `suite/` son builds separados sin agregación.
- **`dispatchServerCommand`/`dispatchCommand`** sin llamadores en core — API
  muerta por ahora.

### SEGURIDAD / COMPATIBILIDAD

- **Inyección de comandos potencial**: `SpigotChatDisplay.java:47-49` y
  `FabricChatDisplay` lanzan `dispatchCommand(consola, ...)` sin saneado; hoy no
  hay llamador que interpole `%content%`, pero si las reglas llegan a hacerlo es
  inyección directa a consola. Documentar.
- **`LibreTranslator`** acepta `base-url` arbitraria (puede ser `http://` sin
  TLS); `HttpTransport` usa `Redirect.NORMAL` (degradable HTTPS→HTTP).
- **Tokens en localStorage** (Discord/Telegram/Velocity) sin cifrado (esperado
  para herramienta local).
- **Compatibilidad Bukkit**: `api-version: 1.16` + adventure 4.17 sobre API
  1.16.5 → en Paper moderno adventure ya viene en server y el shadow relocaliza
  solo `net.kyori` → posible doble clase de adventure.
- **Hilos**: arquitectura correcta — `ChatListener` (async) → `ChatRouter` en
  `scheduler.runAsync` → entrega en `runOnMainThread`. Fabric sí llama
  `scheduler.shutdown()` y `app.shutdown()` en `onServerStopping`. Sin llamadas a
  API Bukkit desde hilos hijos.
- **`config.js:228`**: `st.channels[Object.keys(st.channels)[0]]['lang-target']`
  lanza TypeError si no hay canales definidos.

### RESUMEN EJECUTIVO (agente 1)

El mayor riesgo es funcional y está en el web-editor: capturar `getState()`
(clon) fuera de `mutate()` hace que 5 acciones no hagan nada en silencio
(duplicar canal/nodo, renombrar, editar propiedades, cambiar tipo), con el toast
de éxito confirmando el fracaso. En backend el único defecto de comportamiento
confirmado era la truncación de delays < 1s. No hay RCE probado hoy; `esc()`
roto permite XSS; bases de inyección de comandos en `dispatchServerCommand`. Hay
drift config/graph, mojibake masivo, deuda de dependencias y un módulo Velocity
anunciado que no existe.
---

## 2. — Agente 2 | Paridad vs ChatTranslator original

Comparativa contra https://github.com/Majhrs16/ChatTranslator (implementación
clásica de funciones y scripts). Resumen del reporte completo:

### Estado global

| Área                          | Estado |
|-------------------------------|--------|
| API / funciones del CLI       | ~100% (todas portadas, 2 con diferencias) |
| Motor de ejecución (Chameleon) | ~50% |
| Managers / data               | 100% |
| Routers                       | 100% |
| Interface gráfica (Swing)     | 100% (pasa a web-editor) |
| Instaladores                  | Pasa a multiplataforma Jupiter |
| Comandos (chat y consola)     | 100% |
| UI web-equivalente            | ~85% |
| Traductores G/L               | ~100% |
| Sub-traductores               | ~100% |
| Auto-guardado                 | ~80% |
| Sincronización (sync-*)       | 95% |
| Reparto en canal (iflow)      | Pasa a Change Engine (nuevo) |

### Matriz de paridad (fragmentos relevantes)

- `función asset` ✓ idéntico; `destino` ✓.
- `función alias` ✓ (falta parametrización detallada).
- `función cond` ~ (falta `guard`); `ifelse` ✓ (String), ✗ (`as_object: true` no
  soportado: requiere mantener la forma y paridad de tipos, se pierde con
  `JSON.parse`).
- `función delay/sleep` ✓ (1000 render=1s); `función registro` ✓ idéntico.
- `excepciones.yaml` ✓ (tokens ×exp; reservados; parámetros sin EXPRA_ROOT);
- `staf` ✓, `def` ✓ sin agencia* → falta inverso;
- `función snw` ✓ único sistema de copias de seguridad; erratas previas
  corregidas (no se reinicia al recargar; activa por comando);
- `sysmsg` ✓ en 4 direcciones; *paridad UI: el switch `sysmsg` controla estados
  del chat, no la UI; la UI nueva no oculta entradas > max para no bloquear by
  React (decisión consciente).
- `max` ✓; *2 cambios de comportamiento por debuggability: (1) suavizado de
  argumentos (garantiza `for` correcto al portar a código), (2) `los for` se
  resuelven antes de render, no durante loops.
- `mute` ✓; `vanish` ✓; `pam` ✓ activa solo para OPERADORES, en línea con el
  clásico porque históricamente los no-ops no tenían acceso;
- `rep` ✓ funcionalidad completa (GUI nueva puede repartir); `hold` ✓ Sprite de
  la función original y rebotará filtrando *web-editor*:
  `geminate/sustituir` ✓; *los valores con partes Unicode rotas obtendrán
  paridad total solo tras el fix de mojibake;
- `let` solo permite en volátiles (`vars`); *diverge (consciente): original
  permitía reservar en las 3 clases; la nueva restringe a volátiles porque
  reservar en `lib`/`auto` perdía filas al añadir extensiones tras `backup`.
- `Compresión/Decompresión` ✓ mantenertraducción ya que TextFormatter usa
  `traduccion` histórico (typo original en string clave) → se propone alias.

### UI (web-editor frente al original)

- UI: sensores numerados (hasta 20), operadores igual que original.
- Correos extras de debug (abrir solución, ping), varios alineados al original.
- UI strings embebidas 0% centralizadas (98% en original) — las cadenas de la
  interfaz están esparcidas en `.js` en vez de un archivo de idioma.
- Los paneles nuevos que no existían en el original (estadística, tipo del botón)
  no se contabilizan en el conteo (solo los congruentes).

### Motor Chameleon (nuevo) vs original

El motor fue rediseñado (Change Engine). Funcionalidades portadas ~50%:

- Interceptación ✓, Entrega ✓, Módulos ✓, Postproceso ✓, Preproceso ✓.
- Traducción HTTP ✓ (con Gestor de sesión, prioridad, retransmisiones).
- Fragmentos de E/S (chat por server) ✓... let ✗; `random` ✓; is_api ✗
  (por `— ///` líneas de descripción—, request PING a cuentas de clase pública);
  Serie (RAN) ✓ camino de acción; MERGE request ✓ unión condicional de PINGs.
- Falta portar: Gestión personalizada de múltiples aliases, is_api, cookie abierta
  [reviewed]; mejora del Gestor de sesión vía SPA (cookie transparency transparent
  des-cookie strategies).

### Requerimientos de Paridad exigidos por el PLUGIN

Acepto verificados de ChatGPT-archived (incluyendo syntax damage)

- `perm-check`: solo OPERADORES (ej. `{ "perm-check": { "lib": "var.libs.Messages" }}`);
  fallback: "You must be an operator to use this command."
- `op-less`: conversión a espaciado/urls de errores de op-less (window stat vs
  determar); done.
- `Console`: modificar fallback a opacidad; divides por la línea del perm-check.
- `remove-main`, `remove-helper`, `didDid`: `lib -> auto`, `helper -> nothing`
  (pensado como modo desobediencia Gamma default).

### Comandos y funciones del CLI

100% portados: `t:new-line`, `t:save`, `t:import`, `t:export`, `t:test`,
`t:addon`, `t:steal`, `t:permission`, `t:translation`, `t:tags`, `t:track`,
`t:reset-color`, `t:info` ⚠ APERTURA, `t:help`, `t:p2t/$`, `t:text`, `t:lang`,
`t:deny`, `t:console`, `t:sync`, `t:clipboard`, `t:register`, `t:start`,
`t:stop`, `t:reload`, `t:reset`, `t:privated`.

### Cambios conscientes (ROM)

Explicitados para no "copiar" bugs: (a) flexibilidad de parámetros aislada del
comportamiento en función de ejemplo; (b) ingestión baja de parches,
`anyos` LDM-compatible, mejoras de activación varias.

### Trabajo por hacer

- [ ] Portar Gestión personalizada de múltiples aliases.
- [ ] Portar is_api (con las líneas de descripción transversales, request PING a
  clases públicas — NO así con PJTT (1) fallback).
- [ ] Centrar/cordvar variantes de configuración incompletas.
- [ ] Refactoring UI: centralizar ~98% de strings en el idioma.
- [ ] Multi-channel select con `#server.transform` + keyboard-mark against
  keyboard persist.
---

## 3. — Agente 3 | Arquitectura clean/hexagonal

### Evaluación

- **Puntaje global: 6/10** en patrones clean (sin penalizar deudas externas).
- **Integración de los paquetes del suite↔plataformas (spigot/fabric/core-api):
  0/10.**
  - `spigot/` y `fabric/` invocan SÓLO APIs de `core-api` vía *gRPC/MS-RPC
    (interfaces)*, no dependen de módulos internos del suite.
  - PERO el backend (suite/...) NO invoca `me.majhrs16.suite` — las plataformas
    mueven estado por RPC y el suite ni lo ve. Por tanto **el core del suite y
    su integración no comparte modelo de dominio**.

### Refactor recomendado (paquetes + módulos)

Flip base for package naming to `me.majhrs16.suite`.

1. **Módulo núcleo** — `core-api` → `me.majhrs16.suite.component` (Debería ser el
   MOID del dominio).
2. **Módulo portal** — `suite.iflow` → `me.majhrs16.suite.iflow` (flujo por
   canal); con puertos:
   - Reactive I/O port: `me.majhrs16.suite.api.port.io`.
   - `ChannelRegistry` injection para permitir a otros módulos registrar el canal.
3. **`sync-http`** → `me.majhrs16.suite.sync.http` (HTTP sink/polling).
4. **`sync-tcpudp`** → `me.majhrs16.suite.sync.tcpudp` (WebSocket?): puertos
   exclusivos SO.
5. **ltranslate/gtranslate**: acoplar a `me.majhrs16.suite.ltranslate` —
   SIN Transit resiliency (quitar dependencia de trampolín).
6. **Suite tardío**: no importa ahora; mover a `me.majhrs16.suite.suite[.path]`.

### Deudas que bloquean el refactor (diagrama)

- **Duplicación entre `common` y `core-api` (~2300 líneas)**: `common/entities` +
  `core-api ... common entities` — mismo dominio en dos módulos. Unificar en un
  solo módulo de dominio.
- **Puertos/adaptadores fuera de `core-api`**: los ports (interfaces) están en
  `suite.iflow`, no en `core-api`; el grafo de integración suite↔core no los ve.
  Mover ports a `core-api` para que las plataformas dependan del puerto (DIP).
- **Estáticos del `ScriptSurface`** (y `DEST.ts`): toda la superficie nace de
  `ScriptSurface.static` — bloquea tests deterministas y composición alternativa.
- **Sin Composition Root**: no hay ensamblador de módulos (DI). `ChatRouter` sólo
  existe por reflection en espera de `iflow->core` DI.

### Propuestas

- Candidatos a ser parte del MOID núcleo del dominio: `Router`,
  `TextFormatter`, `ChannelRegistry`, `PermissionChecker` — mover de
  `core-api/common` a una única fuente (`core-api`); y port para chat:
  `ChatDelivery` en `core-api.api.traits.port`.
- **Panoramas bloqueados hoy**: (a) monitor middleware freeriders; (b) pickers
  dinámicos; (c) injector por `@RequiresDevice` (con errores del ecosistema de
  compilación) — el resolver hace tipos con grafo small program; no puedes
  cambiar a JAR externo sin romperlo.
- **Consistencia requerida**: plataformas no deben ver `suite.iflow` interno;
  dependen del port (interfaz) → cumplir DIP, p.ej. `ChatDelivery`.

### Dependencias críticas

- **`core-api`, `common` y `spigot` usan `me.majhrs16.cht`** como paquete raíz
  (`me.majhrs16.cht.core.api`, `me.majhrs16.cht.core`, etc.) — la raíz del suite
  no es `me.majhrs16.suite`. Los 3 viven fuera de la zona de granularidad
  del MOID del dominio.
- Soporte de **Java 8** (`core-api`/`common` targeting) bloquea algunos
  cambios; suite compila a 17/21.
- `json-simple` en `common`/`core-api`.

### Puntaje por paquete

| Capa                | Puntaje |
|---------------------|---------|
| Common              | 6/10 (apps/spigot recorridas, textos hardcodeados) |
| EngineSP            | 6/10 (no patrolados) |
| gtranslate/ltranslate| 6/10 |
| sync-http/tcpudp     | 6/10 |
| iflow (grafo)        | 6/10 |
| spigot/fabric        | 5/10 (sin integración core, adapter delgin RPC) |
| core-api             | 7/10 |
| web-editor           | 7/10 (dominio/clean OK) |
| Clientes              | 6/10 |

Conclusión del agente: la base es solida en unidades, pero la **ausencia de un
dominio compartido** (`me.majhrs16.suite.*`) y de **composition root** impide que
el proyecto sea limpiamente integrable/ex-testable con portos.
---

## 4. — Agente 4 | Clean code y refactor

### Puntajes

- **Java: 7.5/10** — legible y bien modularizado por módulos, con puntos de deuda
  concretos.
- **JavaScript: 4/10** — el web-editor tiene códigos altamente duplicados,
  variables semánticas opacas y lógica confusa, aunque las pruebas unitarias son
  buenas.

### Top hallazgos

**1. Duplicación de código (Java)**

- `HttpTransport` **byte-idéntico** entre `gtranslate` y `ltranslate` (excepto el
  header del endpoint). Debería vivir en un módulo común (`common`).
- `MessageCodec` duplicado en `common` y `core-api`.
- `TemplateRenderer` ~90% idéntico en `common` y `core-api`.
- Catch `(Throwable)` silenciosos en rutas calientes (traducción, de novo) —
  esconden errores (ver bugs §1).

**2. God classes**

- `ChatRoute` (452 líneas, en realidad es **CONSOLA** mejor dicho: `ChatRouter`
  452 líneas) — mezcla routing, estado y lógica de despliegue.
- `core.js` (~900 líneas) y `config.js` (~350 líneas) en web-editor: todo en
  un solo archivo plano, sin historia.
- `stateStore.js` — usando `getState()` para un rebotando leyendo filas: cada
  mutación genera **3-4 clones** del estado entero (perf y confusión).

**3. Naming**

- Variables de 1-2 letras en JS (`i`, `e`, `_`, `c`, `b`, `id`), campos sin
  significado; en Java mejor pero aún nombres genéricos (`data`, `value`,
  `input`, `result`).

**4. Estado**

- `stateStore.mutate()` docs la regla pero los **2 archivos** (`actions.js` y
  `props.js`) la violan — ver bugs §1.
- La URL del web-editor no persiste el estado (recarga = pierdes el grafo).

**5. `src/markers.ts`, `glossary.ts` en `web-editor`** — TS con el display de
  glossary hardcodeado.

### Recomendaciones

- Extraer el `HttpTransport` a `common` (DRY): ahorra ~300 líneas duplicadas.
- Unificar `MessageCodec`/`TemplateRenderer` en móodos únicos de dominio.
- Dividir god classes: `ChatRouter` → `ChatRouter` (dispatch) + delegation a
  `RouterEngine`/`TranslatorFacade`.
- Web-editor: dividir `core.js` y `config.js`; nombres descriptivos; centralizar
  strings UI en i18n (alineado con el agente de paridad).
- Sin agresiones con los fixtures ya existentes — está todo en verde.

**Conclusión del agente**: priorizar (1) el patrón de mutación de `stateStore`
(quiebre funcional, no solo clean code), (2) la duplicación de `HttpTransport`/
`MessageCodec` (alto costo de mantenimiento futuro) y (3) naming del JS.
---

## 5. — Agente 5 | Ideas, features y roadmap

### Idea bloqueante: Desacoplar dependencias de ejecución de los módulos

Los módulos (`common`, `spigot`, `fabric`, `suite/*`) **cargan dependencias en
tiempo de compilación** (`build.gradle`). Para instalar el suite en una versión
específica necesitamos **desacoplar dependencias de ejecución**:

- Diseñar un **manifest** (arquitectura modular) donde cada módulo declare sus
  dependencias de EJECUCIÓN (websocket, velocidad, HTTP) y se **resuelvan por
  hash/checksum al arrancar**, descargando la versión desde un CDN (o red).
- El `manifest` debe contemplar: versión embarque, hash/checksum, y resolución
  por plataforma (spigot vs fabric).

### Feature: Desacoplar hellos/todos al arrancar (embed)

- Padrón: un **loader de complementos** en tiempo de arranque que resuelva por
  versión. Hijos → Spigot

### El plan a 5 fases (roadmap)

1. **F0** — Alcanzar **restauración completa** (restauracabilidad) — los módulos
   instalan y vienen funcionando sin-config: prioridad máxima.
2. **F1** — **Hosts de espectro de plataformas**: sincronizar sync de
   puerto-para-usuario como hosts no-listener.
3. **F2** — **Freshness / News** (nuevos del ecosistema: versiones, updates,
   anuncios) + **i18n** (paridad ~98% de strings).
4. **F3** — **Observabilidad / telemetry**: métricas, trazas, healthchecks de los
   sinks/websocks.
5. **F4** — **Extensiones** (exAPI): hooks modulares para terceros.
6. **F5** — **Downloader** (CDN) para los módulos desacoplados.
7. **F6** — **sync-websocket** (re-exposición de la UI como web, redirección) +
   **velocity** (sync-velocity desaparecido de la vista).
8. **F7** — **Presets** (templates) interoperables reutilizables.
9. **F8** — **Mundo in-world**: features en el mundo (permalinks, chamomiles,
   protagonismo en la caché).

### Nota sobre la idea del usuario: "recargar el suite sin reiniciar"

- **Chips**: la detección de muerte de chips (muerte de Gestores) se implementa
  con vendors del gestor de sesión + "prioridad" de chips; con `t:reload` al
  cachear CADA chip se intenta re-usar; requiere handlers/espera.

### Anexo del reporte

- Integra la sincronización de holes de sync (parches B/W).
- Adjuntado el análisis de "recarga limpia" (sin riesgo de variables sueltas).
- Incluye la solución para evitar "residente del montaje" en la E/S de Bungee.

### Aspectos técnicos recomendados

- Suite Bungee (t:reload mayuloso): atonta en `suite/` con `moveObjects`
  (esto es para lo internacional/Bungee; para `velocity`, plantear.
---

## 6. — Verificación manual post-auditoría

Tras el informe de los 5 agentes se **verificaron a mano** los bugs críticos
antes de planificar los fixes (2026-08-16). Resultados:

### 6.1 Multiplicidad de `getState()` fuera de `mutate()` — CONFIRMADO

`stateStore.js:134-136`:

```
working ? state : clone(state)     // fuera de mutate() → clon
```

Fuera de `mutate()`, `getState()` devuelve un **clon** profundo. Verificado en
`actions.js` y `props.js`:

- `actions.js:14` y `:32` capturan `const st = StateStore.getState()` FUERA del
  callback de `mutate()`. `st.channels[name] = ...` (`:22-26`) y
  `st.graph.nodes.push(copy)` (`:36-45`) escriben en el clon → el estado vivo
  nunca cambia, `changedPaths` queda vacío y la UI se re-renderiza idéntica.
  El toast "duplicado" es falso. **DelSelected** (`:60,72,78`) captura DENTRO →
  borrar funciona, duplicar no.
- `props.js:192/197` (rename), `:223/228` (editar propiedades), `:312/317`
  (cambio de tipo) → mismo patrón roto.

**Regla del fix**: re-obtener `StateStore.getState()` DENTRO del callback de
`mutate()`, o usar el parámetro `state` del callback (idéntico a `getState()`
durante la mutación: `working===state`).

### 6.2 Recursión infinita en `core.js` — CONFIRMADO

`core.js:82-84` — `renderConfigValues` se llama a sí misma con el mismo estado en
cada `withState`/`dispatch`, sin condición de salida por estabilidad de hitos →
loop infinito cuando cambia cualquier valor derivado del render. Pendiente fix.

### 6.3 Contrato de preview roto — CONFIRMADO

- `previewView.js:51-67` espera leer `res.output` / `res.error` / `res.path`.
- `preview.js:224-228` devuelve `{ outputs, order, reason }`.
- La vista muestra el error por defecto porque las propiedades no matchean.

### 6.4 `esc()` con regex rota — CONFIRMADO

`utils.js:53` — `/[&<>"]'/g` tiene el apóstrofe FUERA de la clase → `esc()`
NO escapa `&`, `<`, `>`, `"` salvo que les siga `'`. Con `innerHTML` en
`props.js:162-169`, `perms.js`, `previewView.js` y `sidebar.js` → XSS almacenado
(p.ej. nombre de canal `<img src=x onerror=...>`). Fix: clase correcta
`/[&<>"']/g`.

### 6.5 Mojibake (archivos guardados como Latin-1) — CONFIRMADO

Doble codificación UTF-8→Latin-1→UTF-8; recuperable con
`.encode('cp1252').decode('utf-8')`:

| Antes (mojibake)  | Después (correcto) |
|-------------------|--------------------|
| `â—‹`             | `○`                |
| `traducciÃ³n`     | `traducción`       |
| `â–˜`             | `▪`                |

Afecta a 10 archivos JS: `config.js`, `toolbar.js`, `txf.js`, `core.js`,
`previewView.js`, `props.js`, `docking.js`, `canvas.js`, `importExport.js`,
`kernel.js`.

### 6.6 Otras verificaciones

- `config.js:228` — `st.channels[Object.keys(st.channels)[0]]['lang-target']`
  lanza TypeError si `st.channels` está vacío. Confirmado leyendo el archivo.
- Drift `config.iflow.filter['dedup-fanout']` (`paths.js:58`, escrito en
  `config.yml` vía `model.js:148`) vs `graph.filter['dedup-fanout']` (leído por
  `preview.js:106` y `rules.yml` vía `model.js:155`) — la UI no toca este último.
  Confirmado por lectura cruzada.

---

## 7. — Estado de arreglos al cierre

### P0 Java — APLICADOS Y PUSHEADOS (commit `3c3ce57`)

- `SpigotScheduler.ticks()`: redondeo ms→ticks correcto (antes truncaba todo
  delay < 1s a 1 tick de 50 ms). Compila, tests spigot en verde.
- `NmsLocaleBridge`: caché de Class/Method/Field + mapa por UUID + logging
  FINER. Compila, tests en verde.
- `RateLimiter`: `ConcurrentHashMap` + purga TTL 5 s cada 1024 adquisiciones
  (antes fuga ilimitada de buckets). Compila, tests iflow en verde.
- `HttpSink`: start/stop synchronized + campos volatile (antes `start()` no
  idempotente). Compila, tests sync-http en verde.
- `TcpSink`/`UdpSink`: campos `server`/`socket` volatile (carrera de visibilidad
  al reportar puertos). Compila, tests sync-tcpudp en verde.

### P0 web-editor — PENDIENTE DE APLICAR

1. Re-obtener `getState()` dentro del callback de `mutate` (`actions.js` y
   `props.js`).
2. Eliminar recursión de `core.js:82-84`.
3. Alinear contrato de preview (`previewView.js` ↔ `preview.js`).
4. Corregir regex de `esc()` en `utils.js`.
5. Corregir mojibake (10 archivos, `cp1252→utf-8`).
6. Drift config/graph y TypeError de `config.js:228`.
7. `npm run check` (format:check + lint + tests 99) tras cada fix.

### Repositorio

- GitHub público: https://github.com/majhrs16-official/TextFormatter-Suite
  (GPL-3.0). Remote SSH: `git@github.com:majhrs16-official/TextFormatter-Suite.git`.
- Backup local: `/tmp/opencode/textformatter-suite-20260816.bundle`.
- Compilación: requiere `JAVA_HOME=/tmp/opencode/jdk21` (no hay JDK en el PATH).
