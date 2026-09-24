# TextFormatter Suite
Plataforma **agnóstica** de traducción y routing de chat para Minecraft.
Un conjunto de módulos (JARs independientes) que rehace todo lo que era
ChatTranslator —y mucho más— bajo un núcleo hexagonal real (ports &
adapters), motor de reglas, formatos MiniMessage, grafos iFlow y un
web-editor de configuración.
> **Nombres.** *Suite* = paraguas (el conjunto de módulos). *ChatTranslator*
> queda retirado como marca global y se usa únicamente para los traductores
> **GTranslate** / **LTranslate**. La **retrocompatibilidad es funcional**:
> paridad de comportamiento con el proyecto original ChatTranslator, **no**
> con ningún código intermedio. El trío monolítico `common`/`spigot`/
> `fabric-1.20.6` fue eliminado del árbol (recuperable desde el historial
> git); los adapters reales son los módulos `*-host`.
---
## 1. Arquitectura (hexagonal)
```
┌─────────────────────────────────────────────────────────────┐
│  ADAPTERS       spigot-host ──────── fabric-host (⏳)        │
│  (implementan puertos; nunca se importan entre sí)           │
└───────────────▲─────────────────────────────────────────────┘
│ implementa puertos + bootstrapea
┌───────────────┴─────────────────────────────────────────────┐
│  suite  (módulos Gradle, Java 17/21)                         │
│  core-api (SPI + modelo, JDK-puro)  kernel  textformatter    │
│  iflow  gtranslate  ltranslate  sync-*  host                 │
│  messages  tester  transport  coretranslator                 │
│  web-editor (JS vanilla)                                     │
└─────────────────────────────────────────────────────────────┘
```
**Reglas de dependencia:**
- `core-api` = contrato único (SPI `Module`/`Translator`/`SyncSink`/
`ActorDirectory`/… + modelo `Message`). Dependencias: cero (solo JDK).
- Motores (`kernel`, `textformatter`, `iflow`, `gtranslate`, `ltranslate`,
`host`, `messages`, `tester`, `transport`, `coretranslator`) dependen
**solo** de `core-api`. Grafo acíclico.
- Adaptadores de plataforma dependen de la suite + exactamente un SDK
(`spigot-api`, `fabric-api`); nunca entre sí.
- `web-editor` comunica vía YAML/schema; cero acoplamiento al runtime Java.
- Descubrimiento de módulos: **ServiceLoader/SPI** (`META-INF/services/…Module`).
**Sin modloader**: sin ciclo de vida gestionado, sin manifiesto custom.
- **Handshake doble**: versión JVM de runtime + versión de contrato SPI
(semver por artefacto `*-api`); mismatch → cargar/degradar/avisar.
---
## 2. Módulos
| Módulo | Java | Rol |
|---|---|---|
| `suite/core-api` | 17 | SPI interno: `Module`, `ModuleDescriptor`, semver, capabilities, modelo `Message`, `Translator`, `TranslationService`, `SyncSink`, `SyncListener`, `ActorDirectory`, `PlaceholderResolver`, `PluginLogger`. |
| `suite/kernel` | 17 | `ModuleLoader`, `ModuleGraph` (resolución con Tarjan, detecta ciclos, `CONTRACT_MISMATCH`, `JVM_MISMATCH`), `Environment`. |
| `suite/textformatter` | 17 | Motor de formato MiniMessage: `TemplateRenderer`, `TemplateContext`, `MiniEscape`, `ChannelRegistry`, transforms. |
| `suite/iflow` | 17 | Motor de flujo: `DefaultRouter`, `Rule`, `RateLimiter` (token bucket per-key), `PermissionChecker` (base + send/receive). |
| `suite/coretranslator` | 17 | Puente deprecated que conserva capacidades del original: traducir textos al vuelo vía PAPI (`%cot_*`), capturar/modificar mensajes al vuelo vía API, inyectar lógica compleja vía SpEL. Deprecated = no recomendarlo para uso nuevo; **NO eliminar** (retrocompatibilidad funcional). |
| `suite/gtranslate` | 17 | Proveedor Google Translate. |
| `suite/ltranslate` | 17 | Proveedor LibreTranslate. |
| `suite/sync-discord` | 17 | Gateway Discord v10 (WebSocket JDK + REST), intents, embeds. |
| `suite/sync-telegram` | 17 | Bot Telegram, long-poll con watermark offset. |
| `suite/sync-http` | 17 | Webhook + REST (`HttpServer` JDK), inbound/outbound. |
| `suite/sync-tcpudp` | 17 | TCP/UDP raw (`TcpSink`/`UdpSink`), JSON por línea/datagrama. |
| `suite/sync-velocity` | 17 | **F7+ pendiente** (stub en editor/config, no existe en disco). |
| `suite/host` | 17 | Composition root: `SuiteHost`, `ConfigLoader` (enum `ConfigPath`), `HostConfig`. Ensambla todos. Wiring listo para plataforma: `MessageDispatcher` (expande `Direction`→receptores, orquesta por-receptor), port `ChatDelivery` (`host/port/`) y port `ActorDirectory` (`core-api/spi/`). |
| `suite/messages` | 17 | i18n centralizado: catálogos EN/ES, `MessagesCatalog` singleton. |
| `suite/tester` | 17 | Test runtime: 25 tests automatizados (routing, eventos, traducción, formato, iFlow, concurrencia, stress, profiling). `PerformanceProfiler` CPU/heap. Skip mechanism. `/suite test full|stress|concurrency`. |
| `suite/transport` | 17 | `HttpTransport` unificado (`HttpURLConnection`), `MessageCodec` único. |
| `suite/coretranslator` | 17 | Puente legacy (retrocompatibilidad funcional). |
| `suite/web-editor` | JS | UI configuración vanilla ES2022 (GitHub Pages estático). |
| `suite/spigot-host` | 17 | **Plugin Spigot de la suite** (`TextFormatterSuite`): `SpigotActorDirectory`, `SpigotChatDelivery` (hop a main thread), bootstrap `SuiteHost`+`MessageDispatcher`, `/suite reload|status|test|lang|toggle|reset`. Fat-jar construido (shadow). |
| `suite/fabric-host` | 17 | **Plugin Fabric de la suite** (`FabricMod`): `FabricActorDirectory`, `FabricChatDelivery`, bootstrap `SuiteHost`+`MessageDispatcher`. Loom 1.6.12, mappings 1.21. |
| `suite/manager-api` | 17 | SPI del gestor de módulos runtime: `ModuleCoordinate`, `ModuleDescriptor`, `Environment`, `ModuleLifecycle`. |
| `suite/manager-impl` | 17 | Implementación: GitHub releases downloader, version resolver (semver + env compat), dependency resolver (parsea module.yml), dependency relocator, ClassLoader aislado (parent-last), SHA256 verificación, register() SPI-only, discoverAll() para kernel. **Núcleo completado**; pendiente GitHub Releases reales y Security Sprint 3. |
| `suite/presets` | 17 | Presets de configuración predefinidos (standard, rpg, staff, minimal). |
| `suite/inworld` | 17 | Handlers in-world (signos, cofres, libros), WORLD/RADIUS, botones click/hover. |
| `suite/observability` | 17 | Metrics endpoint (`/metrics` Prometheus), Debug endpoint (`/debug/*` con auth token, 127.0.0.1), Health checks. |
| `suite/extension-api` | 17 | SPI de extensiones: `Extension`, `ExtensionContext`, `ExtensionConfig`, `ExtensionMetadata`. |
| `suite/example-extension` | 17 | Ejemplo de extensión demostrando la API. |
| `suite/loadtest` | 17 | Tests de carga/estrés (JMH). |
| `suite/performance` | 17 | Profiling y optimización (`PerformanceProfiler`, `HotspotDetector`, `CacheOptimizer`, `MemoryOptimizer`). |
| `suite/common-legacy` | 17 | Referencia histórica (trío monolítico eliminado). |
| `suite/sync-websocket` | 17 | WebSocket sync sink para tiempo real. |

Dependencias entre motores:
- `kernel→core-api`
- `textformatter→core-api` (+Adventure)
- `iflow→core-api+textformatter`
- `coretranslator→core-api`
- `gtranslate/ltranslate→core-api+transport`
- `host→core-api+textformatter+iflow+gtranslate+ltranslate+messages+tester`
- `manager-impl→manager-api+core-api+kernel+textformatter+host+gtranslate+ltranslate+sync-*+messages+tester+extension-api`
- `presets→core-api+textformatter+iflow+host+messages`
- `inworld→core-api+textformatter+iflow+host+messages`
- `observability→core-api+textformatter+iflow+host+messages`
- `extension-api→core-api+iflow+host+messages`
- `example-extension→extension-api+core-api`
- `loadtest→core-api+textformatter+iflow+host+messages+tester`
- `performance→core-api+textformatter+iflow+host+transport`
- `sync-websocket→core-api+textformatter+host+messages`
- `spigot-host→core-api+textformatter+iflow+host+messages+tester+manager-impl+presets+inworld+observability+extension-api+sync-*`
- `fabric-host→core-api+textformatter+iflow+host+messages+manager-impl+presets+inworld+observability+extension-api+sync-*`
---
## 3. Modelo de mensaje
Cada evento de chat produce unidades atómicas **`Message`** con su propio
emisor, **`Direction`** (audiencia), arrays de contenido, grupo de formato,
colores, sonidos y par de idiomas — **no** hay par from/to embebido. El mensaje
al iniciador y el broadcast al resto son unidades independientes con formato y
cancelación independientes. **Inmutables**; las reglas mutan un clon privado
vía `Message.withX()` methods (`withLangTarget`, `withText`, `withCancelled`, etc.).
Un `Message` lleva:
- `type` — `MessageType` (CHAT, PRIVATE, MENTION, JOIN, LEAVE, DEATH,
ADVANCEMENT, SIGN, INTERNAL, CUSTOM).
- `sender` — `Actor` (uuid, name, kind, language, native handle).
- `direction` — `Direction` (INITIATOR, OTHERS, ALL, CONSOLE, WORLD, RADIUS,
PERMISSION, SPECIFIC) con canal y receptores explícitos opcionales.
- `messages` / `toolTips` — `Formats` paralelas (textos + MiniMessage).
- `sounds` — specs `name;volume;pitch`.
- `colorMode`, `langSource`, `langTarget`, `translate`, `formatPapi`.
- `resolvedSourceLanguage` — idioma fuente resuelto (caché para evitar detección por receptor).
- `lastFormatPath` — el grupo de formato que construyó el mensaje.
---
## 4. Motor de formato (MiniMessage + Adventure)
- `<tr>text</tr>` marca la parte a traducir (por receptor).
- `%ct_messages%`, `$ct_messages$`, `{0}` inyectan el texto bruto.
- `%player_name%`, `%player_uuid%`, `%lang_source%`, `%lang_target%` son
built-ins; cualquier otro `%variable%` pasa por `PlaceholderResolver`
(PlaceholderAPI en Spigot, identidad en Fabric).
- `<expr>…</expr>` evalúa una expresión SpEL.
- Todos los valores dinámicos se escapan para impedir inyección MiniMessage.
`formats.yml` se organiza en **grupos de formato** (cualquier path), cada uno
con `messages.formats`/`messages.texts`, `toolTips`, `sounds` y opcionalmente
`sourceLang`/`targetLang`. Un grupo por tipo de evento, renderizado por
receptor al idioma de ese receptor.
---
## 5. Motor de reglas (rules.yml → iFlow)
Reemplaza ConditionalEvents. Las reglas aplican por mensaje antes del formato y
la entrega; un mensaje cancelado se descarta.
```yaml
rules:
spam:
events: [CHAT]
conditions:
- "'spam' in #msg.texts[0]"
actions:
- cancel()
- skipTranslate()
```
- Cada regla es `(name, List<MessageType>, conditions SpEL, actions SpEL)`.
- `ScriptSurface` expone operaciones atómicas (`setText`, `setTexts`,
`setLangSource`, `setLangTarget`, `setColorMode`, `setFormatPapi`,
`show/hide`, `cancel`, `skipTranslate`) y helpers (`setFormat(path)`,
`clone()`, `toJson()`). Root SpEL: `#msg`.
### iFlow (grafos)
Firewall por receptor/emisor con default-policy por canal y targets `LOG`,
`DROP`, `REJECT`, `REDIRECT` (a consola), `RATE-LIMIT`, `CHANNEL_REDIRECT`.
- Entradas múltiples = **mux** (independientes); salidas múltiples = **fan-out**
(broadcast); ramificación = condición-filtro; ciclos permitidos con guard
`max-steps` (default 512, DROP + log al superar).
- El editor lo edita como **grafo de nodos** (`rules.yml`): `input`, `cond`,
`transform`, `loop`, `sleep`, `output`, `redirect`, con transforms
`rewrite`/`sounds`/`sleep` (requieren motor F7+, se marcan en manifest).
- Prioridad = BFS por capas desde entradas; empates por índice de creación.
- **RateLimiter**: token bucket per-key (`channel + actor`), capacidad por canal
(`channel.rateLimitPerSecond()`).
- **iFlow como autoridad única**: Discord mirror respeta decisión del dispatcher
(solo envía si `delivered > 0`).
---
## 6. Permisos por canal
- **Base**: un único permiso `cht.<channel>` = suscripción (poseerlo = adscrito).
- **Opción**: `send-permission` / `receive-permission` para asimetría nativa
("todos leen, solo staff escribe"). Default ACCEPT si no se define nada.
- La asimetría también puede vivir en reglas de iFlow.
---
## 7. Configuración — Schema v2.2 (fuente única de verdad)
El editor importa/exporta contra este schema; el **host** (`ConfigLoader`)
parsa la misma estructura. **Round-trip exacto**: panel → YAML → panel sin
pérdida. Lo que no quepa aquí es falta de precisión del schema o del motor.
**Archivos del proyecto** (`textformatter-suite.zip`):
```
config.yml            → HostConfig (idéntico a ConfigLoader.loadConfig)
channels/<canal>.yml  → ChannelRegistry (idéntico a ConfigLoader.loadChannels)
rules.yml             → grafo iFlow (editor/F7+)
translators/*.yml     → proveedores (google/libre)
sync/discord.yml      sync/telegram.yml  sync/http.yml
sync/tcp-udp.yml      sync/velocity.yml
sync/websocket.yml
manifest.json         → versiones + validación + capabilities
```
**`config.yml`**: `quick-look`, `general.language`, `iflow.engine.parallel`,
`sonido.enabled`. Claves opcionales; desconocidas se ignoran (degradan).

**`channels/<id>.yml`**: `name` (es el id; renombrar propaga a rules.yml y
sync), `type` (CHAT|EVENT), `permission`, `send-permission`, `receive-permission`,
`show-sender`, `rate-limit-per-second`, `lang-source`, `lang-target`,
`messages[]`, `tooltips[]`, `sounds[]` (name/volume/pitch).

**`rules.yml`**: `guard.max-steps`, `filter.dedup-fanout`, `priority`, `nodes[]`
(kind, label, matcher, transforms, target), `edges[]`. Mux/fan-out/condición/
ciclos.

**`translators/*.yml`**: `provider` (google|libre), `active`, `base-url`,
`api-key`, `pool.max-concurrent`.

**`sync/*.yml`**: discord (token, channel, intents) · telegram (token,
chat-id, hub) · http (webhook-url, inbound-port, path) · tcp-udp (protocol,
host, outbound-port, inbound-port) · velocity (enabled, secret, servers[],
mapping) · websocket (token, port).

**`manifest.json`**: `schema`, `suite-version`, `generated-at`,
`capabilities` (`transforms: true/false`), `validation` (errors/warnings/
blocking/issues).

**Reglas de round-trip**: (1) writer/parser propios, byte-idéntico;
(2) `config.yml` + `channels/*.yml` parsables por el host (`ConfigLoaderTest`);
(3) import acepta cualquier export; campos faltantes = defaults; campos
desconocidos se **conservan**.
---
## 8. Web Editor (F6)
Artefacto estático único (GitHub Pages), HTML+CSS+JS vanilla, sin build.
- **Canvas de nodos** como centro de edición: celdas (TextFormatter) y grafos
(iFlow) con puertos arriba (entradas) y abajo (salidas); zoom `ctrl+rueda`
(25–400%), pan `espacio+arrastre`, snap 20px, minimapa.
- **Layout del usuario**: paneles extraíbles/reordenables; tema (oscuro
default) e idioma (en/es) en localStorage; autosave del proyecto.
- **Round-trip exacto** YAML (import→panel→export). Schema primero: el editor
no dibuja nada que el schema no represente.
- **Preview** replica el pipeline del motor (port JS + fixtures dorados contra
el host Java), sin red; traducción viva opcional con pool + rate-limit y
fallback a inglés.
- **Validación global** → `[{nivel, grupo, ruta, mensaje}]`; badges, rings
rojos, toasts, manifest. **Nunca se descarga con errores bloqueantes.**
- **Arquitectura JS**: StateStore (estado + historial undo/redo 80 +
persistencia + validadores con rollback + diffing de paths + autosave 400ms),
rendering con diffing, validación incremental por `revision()`, paths.json
centralizado para data-bind, i18n en/es, docking de paneles.
---
## 9. Eventos para integraciones externas
La API está diseñada pero **aún no implementada completamente**.
Plan: un bus público thread-safe en `core-api` (`MessageEventBus`), alimentado por
`MessageDispatcher` **antes** de reglas y renderizado:
```java
bus.register("anti-swear", event -> {
if (event.message().text().contains("badword")) {
event.setCancelled(true);   // o setMessage(...) / setProcessed(true)
}
});
```
Los listeners correrán en el hilo de dispatch; cancelar/reemplazar/tomar
control de la entrega serán operaciones del `event`. Este bus es además el
punto de enganche que reemplaza al evento Bukkit-custom que usaba
ConditionalEvents y la base sobre la que `coretranslator` recuperará las
capacidades del original (PAPI al vuelo `%cot_*`, captura/modificación de
mensajes, SpEL).
---
## 10. Wiring de plataforma
Los adapters implementan los puertos del motor y eligen el hilo:
| Puerto (`core-api/spi` / `host/port`) | Spigot (`spigot-host`) | Fabric (`fabric-host`) |
|---|---|---|
| `ActorDirectory` | `SpigotActorDirectory` (idioma: store→locale→null; snapshot anti-CME) | `FabricActorDirectory` |
| `ChatDelivery` | `SpigotChatDelivery` (BukkitAudiences, hop a main thread, sonidos normalizados) | `FabricChatDelivery` |
| Evento chat | `AsyncPlayerChatEvent` (LOWEST claim-first; claim configurable: `cancel-event`\|`clear-recipients`) | `ServerMessageEvents.ALLOW_CHAT_MESSAGE` |
| Join/Quit/Death/Advancement | canales convencionales `join`/`quit`/`death`/`advancement` (presencia = activado) | mismos canales |
| Idioma por usuario | `UserLanguageStore` (YAML) + `/suite lang [jugador] <auto|off|código>`; `off` = sin traducción | mismo |
| Permisos | `Player#hasPermission` | `ServerPlayerEntity#hasPermission` |
| Mundo/radio | `getWorld().getName()` / `distanceSquared` | mismo |
---
## 11. Configuración en runtime
- Nunca toca el stack YAML del servidor: los hosts embuten `snakeyaml`
dentro del jar y parsean con loaders propios (`host/config/ConfigLoader`,
`TranslatorsConfig`), tolerantes a archivos corruptos (degradan, no crashean).
- Defaults (`config.yml`, `channels/chat.global.yml`, `translators/google.yml`)
van **dentro del jar** (`resources/defaults/`); en primer arranque se copian
si faltan y **nunca sobrescriben** ediciones del usuario.
- Estrategia de E/S: lectura directa delegando en el Page Cache del SO;
`/suite reload` relee todo el layout sin watchers ni polling.
---
## 12. Construcción
> Requiere JDK 17 y 21 (toolchain Gradle; `options.release=17` para bytecode).
> Gradle wrapper 8.13, fabric-loom 1.6.12. Declara las rutas JDK en
> `org.gradle.java.installations.paths` (`gradle.properties`). Con caché Gradle
> poblada, todo compila `--offline`.
```bash
# Suite (cada módulo es un build independiente)
export JAVA_HOME=/opt/javac/x64/21
cd suite/core-api      && ./gradlew test publishToMavenLocal --offline --no-daemon
cd suite/kernel        && ./gradlew test publishToMavenLocal --offline --no-daemon
cd suite/textformatter && ./gradlew test publishToMavenLocal --offline --no-daemon
cd suite/iflow         && ./gradlew test publishToMavenLocal --offline --no-daemon
cd suite/gtranslate    && ./gradlew publishToMavenLocal --offline --no-daemon
cd suite/ltranslate    && ./gradlew publishToMavenLocal --offline --no-daemon
cd suite/sync-telegram && ./gradlew test publishToMavenLocal --offline --no-daemon

# Plugin Spigot de la suite (fat-jar)
cd suite/spigot-host   && ./gradlew build --offline --no-daemon

# Plugin Fabric de la suite
cd suite/fabric-host   && ./gradlew build --offline --no-daemon

# Web editor
cd suite/web-editor
npm run check                        # format:check + lint + test (99 unit)
npm run test:integration             # harnesses func/interact/click/chain/undo/diffing/bind
```
---
## 13. Pruebas
- **Suite Java**: 167+ tests verdes bajo Gradle (kernel, textformatter, iflow,
gtranslate/ltranslate, sync-*, host, messages, tester, transport,
spigot-host con normalización de sonido). `ModuleLoaderTest` requiere
ejecución aislada.
- **Web editor**: 99 unitarios (StateStore 40, model 30, validate 29) +
harnesses de integración in-repo (`tests/integration/*.cjs`).
- **Golden tests**: el editor y el host deben validar el mismo config
(`ConfigLoaderTest.parsesEditorExportedDefaultConfig` verde).
---
## 14. Estado real (2026-09-18)

**Fases cerradas:**
- F0 (GitHub), F1 (web-editor P0), F2 (Java P0/P1 + wiring),
- F3 (channel type system + tester module + default channels).
- F4 (fabric-host funcional), F5 (i18n strings UI), F6 (iFlow enriquecido: CHANNEL_REDIRECT, PAPI/permisos en SpEL, transform F7+),
- F7 (ConfigValidator real), F8 (comandos dinámicos `/suite`), F9 (sync-velocity real),
- F10 (observabilidad: metrics/debug/health), F11 (extensiones/addons SDK), **F12 (manager runtime - núcleo completado)**,
- F13 (sync-websocket), F14 (presets, transform real, engine.parallel), F15 (in-world),
- F16 (tests, profiling, docs — **parcial: tests E2E pendientes, docs sync en progreso**).

**Eliminado:** trío monolítico `common`/`spigot`/`fabric-1.20.6` (nunca
probado en servidor; recuperable desde historial git).

**Probado en producción:** Plugin `TextFormatterSuite` probado en servidor Paper 1.20.6 real — todos los comandos `/suite`, canales join/quit/death/advancement, chat con traducción, rate-limit, y tests runtime funcionando.

**Problemas críticos arreglados (commit 82d38f4, audit 2026-09-13 + fixes 2026-09-18):**
- ✅ **C1** Contrato `Message` roto → `withX()` methods inmutables, `Builder.from()`
- ✅ **C2** `MessageEvent` roto → `cancelled` no final, imports
- ✅ **C3** Module Manager → `URLClassLoader`, `register()` SPI-only (no instancia Module), `discoverAll()`, manifest validation, stubs lanzan `UnsupportedOperationException`
- ✅ **C4** Debug endpoint → 127.0.0.1, auth token, sin `/debug/simulate`, sin CORS *, executor shutdown
- ✅ **C5** HEAD no compilable → core modules compilan (core-api, iflow, host, observability, spigot-host, manager-impl)
- ✅ **H1** RateLimiter → per-key capacity, sin double scheduler
- ✅ **H2** Discord bypass iFlow → `mirror(DispatchReport)` solo si delivered
- ✅ **H3** Language detection O(recipients) → `resolvedSourceLanguage` caché
- ✅ **CT-01** `Message.toJson()` → serialización JSON real
- ✅ **CFG-01** ConfigLoader → `LoadResult` con errores, logging ERROR en lugar de degradar silenciosamente
- ✅ **OBS-01** DebugEndpoint → executor shutdown en stop()
- ✅ **F12-M2** Version resolver → semver ranges + env compat
- ✅ **F12-M3** Dependency resolver → parsing module.yml desde JAR
- ✅ **F12-M6** register() semántica → descriptor SPI only

**Pendientes / Deuda conocida:**
- ⚠️ **Module Manager (F12)**: GitHub Releases = 0 (requiere release pipeline), Security Sprint 3 pendiente
- ⚠️ **SpEL**: Debe auditarse profundamente (RCE potential sin sandbox) — Security Sprint 3
- ⚠️ **Tokens en heap**: Discord, Telegram, LibreTranslate en `String` permanente — Security Sprint 3 (char[])
- ⚠️ **MiniEscape**: Solo escapa `<` y `\` — faltan `>`, `{`, `}`, `[`, `]`, `(`, `)`, `#`, `@` — Security Sprint 3
- ⚠️ **Config schema**: Copias manuales (`paths.json`, `js/paths.js`, `js/model.js`, `ConfigLoader.ConfigPath`, `schema-v2.2.md`)
- ⚠️ **sync-velocity**: Stub en editor/config → implementar real o eliminar.
- ⚠️ **Tests E2E**: `npm run test:integration` para web-editor OK; tests Java E2E pipeline completo pendientes.
- ⚠️ **Documentación**: README, PLAN, PROMPT_NOW, Release Notes, Wiki, ADR deben sincronizarse a un mismo estado (en progreso).
- ⚠️ **GitHub Releases**: No existen; F12 requiere releases publicados.
---
## 15. Bugs conocidos y deuda (2026-09-13)

**Web editor:** P0 arreglados ✅. Queda: ampliar opciones YAML para reglas complejas sin perder usabilidad.

**Java legacy:** bugs trío monolítico moot (eliminado). Arreglados en suite: `RateLimiter` TTL, `HttpSink` idempotente, `TcpSink`/`UdpSink` volatile, `HttpTransport` → `HttpURLConnection`.

**Arquitectura (deuda viva):**
- Config schema en copias manuales: `paths.json`, `js/paths.js` (duplica paths.json), `js/model.js`, `ConfigLoader.ConfigPath`, `schema-v2.2.md`. → Centralizar generación.
- Suite sin composite build en `settings.gradle` raíz (hosts consumen jars vía `files()` / mavenLocal hasta composite build).
- `suite/coretranslator` deprecated → mantener solo para retrocompatibilidad funcional, no para uso nuevo.
- `sync-velocity` stub en editor/config → implementar real o eliminar.

**Seguridad (de auditoría A4 2026-09-06):**
- **INJ-3 (CWE-94)**: `ExpressionEvaluator` SPI sin sandbox por defecto → **RCE vía SpEL** si host usa `StandardEvaluationContext`.
- **INJ-4 (CWE-502)**: 4 loaders YAML usan `new Yaml()` (unsafe constructor) → **deserialización arbitraria** si atacante escribe en config files.
- **INJ-1**: `MiniEscape` solo escapa `<` y `\` — faltan `>`, `{`, `}`, `[`, `]`, `(`, `)`, `#`, `@`.
- **SEC-1..4**: Tokens Discord, Telegram, LibreTranslate en `String` permanente en heap.
- **DOS-1**: `HttpServer` executor unbounded → thread exhaustion.
- **DOS-2**: `MessageDispatcher` secuencial en async chat event → lag servidor 200+ jugadores.

**Supply Chain (A5 2026-09-06):**
- Sin `gradle.lockfile` / SHA256 / `dependencyVerification`.
- 8 repos Maven; `mavenLocal()` con precedencia.
- Builds no reproducibles.
- Sin allowlist módulos / manifest validation pre-load.

---
## 16. Documentación
| Documento | Contenido |
|---|---|
| `docs/PROMPT.md` | Prompt rector del proyecto (objetivos, reglas, ecosistema, repositorio, roadmap). **Solo lectura.** |
| `docs/PROMPT_NOW.md` | Plan de acción de corto plazo (leer al inicio de cada sesión). |
| `docs/ADR.md` | Registro de decisiones de arquitectura (contratos SPI, fases, ADR 2026-08-24). |
| `docs/PLAN.md` | Plan de ejecución vivo: bugs confirmados, deuda, roadmap por fases. |
| `docs/AUDITORIA.md` | Auditoría completa 2026-08-16: volcado íntegro de los 5 subagentes + verificación manual. |
| `docs/AUDITORIA-2026-08-24.md` | Auditoría integral del estado actual (módulos, clases, features, paridad) + veredictos del autor como decisiones. |
| `docs/historial/AUDITORIA-2026-09-13.md` | **Auditoría profunda HEAD 494f7ea** — código vs docs, contratos, seguridad, severidades, prioridades de reparación. |
| `docs/NEW-FEATURES.md` | Features nuevas documentadas (channel type, tester, etc.). |
| `docs/web-editor/DESIGN.md` | Diseño del Web Editor (layout GIMP/Grafana, canvas de nodos, decisiones). |
| `docs/web-editor/schema-v2.2.md` | Schema v2.2 detallado (archivos, claves, reglas de round-trip). |
| `docs/historial/` | Logs de sesión por fecha. |
---
## 17. Licencia
**GPL-3.0** (LICENSE). Repositorio:
https://github.com/majhrs16-official/TextFormatter-Suite
Documentación del proyecto original: https://github.com/Majhrs16/ChatTranslator y https://github.com/Majhrs16/ChatTranslator/wiki (referencia histórica funcional).