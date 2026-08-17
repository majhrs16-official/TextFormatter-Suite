# PLAN — TextFormatter Suite

> Documento vivo: reléelo antes de cada sesión de trabajo para no perder el rumbo.
> Última actualización: 2026-08-16 (auditoría de 5 subagentes + verificación de bugs).

---

## Estado real del proyecto (2026-08-16)

- ✅ **Monorepo Git** en `/home/majhrs16/Documentos/Default Project` (rama `main`, commit inicial `033295f`).
- ✅ **Arquitectura**: `common` (SPI Java 8 neutro) + adapters `spigot` (compatible CraftBukkit, usa solo `org.bukkit.*`) y `fabric-1.20.6` + `suite/` (13 módulos reales presentes; faltan `sync-velocity`).
- ✅ **Web editor** funcional (StateStore, diffing, validación incremental, paths.json) con 99 tests unitarios + 5 de integración.
- ⚠️ **La suite NO corre** en ninguna plataforma: ni spigot ni fabric importan `me.majhrs16.suite`; `ModuleLoader` solo se ejecuta en tests. Es hexágono "de papel".
- ❌ **No está subido a GitHub** (riesgo de pérdida).

---

## HALLADAZGOS DE LA AUDITORÍA (5 agentes)

### Bugs confirmados (web-editor)
| Bug | Ubicación | Severidad |
|---|---|---|
| Mutaciones sobre clon descartado: duplicar canal/nodo, renombrar canal, editar propiedades, cambiar tipo NO hacen nada (toast falso) | `js/actions.js:14,32`, `js/props.js:192,223,312` | **ALTO** |
| Recursión infinita: `renderConfigValues()` se llama a sí misma → RangeError en renderAll | `js/core.js:82-84` | **ALTO** |
| Preview lee `res.output/error` pero `simulate` devuelve `outputs/order/reason` → undefined | `js/previewView.js:51-67` vs `js/preview.js:224-228` | **ALTO** |
| `esc()` no escapa `&<>"` (apóstrofe fuera de la clase de caracteres) → XSS | `js/utils.js:52-54` | MEDIO |
| Mojibake UTF-8 masivo en strings visibles (`â—‹`, `Â·`, `interconexiÃ³n`) | `txf.js:11`, `canvas.js:150`, `kernel.js:16`, `core.js:118`, etc. | MEDIO |
| Drift config: `config.iflow.*` escrito pero el motor lee `graph.*` | `js/paths.js:58`, `js/model.js:33 vs 62` | MEDIO |
| `toolbar.js:27` placeholder sobrescribe handler real de import/export | `js/toolbar.js:27` | BAJO |
| `config.js:228` TypeError si no hay canales | `js/config.js:228` | BAJO |
| `console.log` residual | `js/toolbar.js:23` | BAJO |

### Bugs confirmados (Java)
| Bug | Ubicación | Severidad |
|---|---|---|
| `ticks()` trunca delays < 1s a 1 tick (50ms) | `spigot/.../SpigotScheduler.java:48-52` | ALTO |
| `RateLimiter` fuga de memoria ilimitada (buckets nunca purgados) | `suite/iflow/.../RateLimiter.java:18` | MEDIO |
| `TcpSink`/`UdpSink`: campos no `volatile` → puerto stale | `suite/sync-tcpudp/...` | MEDIO |
| `HttpSink.start()` no idempotente (segundo start = otro HttpServer) | `suite/sync-http/.../HttpSink.java` | MEDIO |
| `NmsLocaleBridge`: reflection en hot path por mensaje + `catch(Throwable){}` | `spigot/.../NmsLocaleBridge.java:44-61` | MEDIO |
| `TelegramSink.stop()` no-op; `DiscordGateway` sin reconexión real | `sync-telegram`, `sync-discord` | BAJO |

### Deuda estructural
- **Dominio duplicado en 2 arquitecturas**: `common/me.majhrs16.cht.core.*` ↔ `suite/core-api/me.majhrs16.suite.api.*` (Message, Formats, Actor, Language, TemplateRenderer, MiniEscape...). ~2300+ líneas.
- **`HttpTransport` byte-idéntico** en gtranslate/ltranslate; ~90% en sync-telegram/sync-discord.
- **`MessageCodec` duplicado** en sync-http/sync-tcpudp.
- **No hay composition root real**: `SuiteHost` solo se usa en tests; falta `ChatDelivery` (puerto de salida) en core-api.
- **Config schema en 5 copias manuales**: `paths.json`, `js/paths.js` (duplica paths.json), `js/model.js`, `host/ConfigLoader.java` (enum `ConfigPath`), `schema-v2.2.md`.
- **UI del plugin 0% centralizada**: todos los strings en `ChtCommand.java:46-115` (el original tenía ~98% en config).
- **`settings.gradle` raíz no incluye la suite** (15 builds sueltos, no composite).

### Paridad vs original (`/usr/src/chattranslator`)
- ✅ Núcleo de traducción, join/leave/death, idioma por jugador (YamlUserStore), permits, rate-limit, PAPI, signos (declarativos).
- ❌ Strings de UI centralizados (0% vs 98%).
- ❌ Módulo `commands.yml`, Signs interactivo (solo declarado en formats), muerte/advancement no wired.
- ⚠️ CE replicable PARCIALMENTE: el motor nativo `rules.yml` (SpEL + ScriptSurface) cubre captura de vars, condiciones, `setFormat`, `cancel()`. FALTA: permisos/PAPI dentro de SpEL, destino "channel" en las reglas, evento público para third-party, `transform` F7+.

### Arquitectura (veredicto agente)
- **Parcial hexagonal: 6/10**. common+adapters 8/10; suite interna 7/10; **integración suite↔plataforma 0/10** (no existe).
- Violaciones key: infra dentro de common (GoogleTranslator, YamlUserStore, ConfigLoader, Spring SpEL); singleton estáticos (`ScriptSurface.bind*`); portos repartidos fuera de core-api (Router, TextFormatter, ChannelRegistry, PermissionChecker).

### Code quality
- Java: **7.5/10**; JS: **4/10**. Diosas JS (txf.renderTxf, config.bindSyncFields, canvas.bindViewport), O(n³) `cycleSet`, deep-clone en cada get/mutate/notify (3-4 clones por mutación).
- NMS reflection por mensaje; templates reparseados por (formato×receptor); `stateStore` clones en cada subscriber.

---

## PLAN DE EJECUCIÓN (orden de prioridad)

### FASE 0 — SEGURIDAD Y GITHUB (esta sesión)
1. **Subir a GitHub** el monorepo (privado, backupear YA). Decidir nombre/orgs.
2. **Ver antes de perder**: confirmar que `.git` tiene todo incluido (no perder `.gradle`, node_modules está ignorado correctamente).

### FASE 1 — P0 web-editor (bugs confirmados)
3. Corregir captura de clon: regla "re-obtener `StateStore.getState()` DENTRO del callback de `mutate`" en `actions.js` y `props.js`. Opcional: cambiar API `mutate(draft => ...)` estilo immer.
4. Eliminar recursión `core.js:82-84` (`renderAll` llama al método real de config).
5. Alinear contrato preview: `previewView.js` consume `outputs/order/reason`; test fixture del shape.
6. `esc()`: apóstrofe dentro de la clase `[&<>"']`; test.
7. Arreglar mojibake (reescribir literales UTF-8) + lint/test anti-regresión.
8. Drift config/graph: unificar `model.js` (una sola fuente).

### FASE 2 — P0/P1 Java
9. `SpigotScheduler.ticks()`: conversión MS→ticks correcta (redondeo, no truncar < 1s).
10. `NmsLocaleBridge`: cachear Class/Method/Field; log no silencioso.
11. `RateLimiter`: purga por TTL.
12. `HttpSink` idempotente; `TcpSink/UdpSink` volatile.
13. **Wiring suite→plataforma** (el gran hueco): composition root `spigot-host`/`fabric-host`, `ChatDelivery` port en core-api, inicializar `SuiteHost.bootstrap` con ServiceLoader de verdad.
14. Unificar dominio duplicado: adoptar `suite/core-api` como canónico; `common` → `common-legacy`; borrar bridge interno `ChatRouter.dispatch(ChatMessage)` y copia `coretranslator/legacy`.
15. Extraer `suite/transport` (HttpTransport único) y `MessageCodec` único; centralizar schema (generar paths.json del enum).

### FASE 3 — Configurabilidad (paridad original)
16. Centralizar TODOS los strings en `lang/` (catalogos EN/ES) — recobrar el 98%.
17. Motor de reglas enriquecido iFlow: destino "channel", permisos/PAPI en SpEL, evento público (`MessageEventBus`), `transform` real (F7).
18. Comandos/config and enhancement: `/suite` base, reload atómico config, `ConfigValidator` (issues con shape del editor).

### FASE 4 — Roadmap largo (del agente 5)
- F0 restaurabilidad (suite.info + manifest + releases inmutables), F1 hosts reales, F2 freshness+i18n, F3 observabilidad (metrics/debug/simulate), F4 extensiones/addons (core-api 2.2 + SDK), F5 **descargador runtime + attach/detach** (classloader dinámico + manifest + sha256 + allowlist), F6 sync-websocket/velocity, F7 presets, F8 in-world (signos/cofres/libros, WORLD/RADIUS, caché+glosario, botones click/hover).

---

## PRÓXIMAS ACCIONES CONCRETAS
1. (Ahora) Confirmar GitHub + decidir P0 editor vs P0 java primero.
2. Ejecutar Fase 1 (P0 web-editor) con tests.
3. Lanzar `npm run check` tras cada fix.