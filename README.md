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
│  ADAPTERS       spigot-host (compila)    fabric-host (excluido)│
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
`ActorDirectory`/… + modelo `Message`, `TranslatorProvider`). Dependencias: cero (solo JDK).
- Motores (`kernel`, `textformatter`, `iflow`, `gtranslate`, `ltranslate`,
`host`, `messages`, `tester`, `transport`, `coretranslator`) dependen
**solo** de `core-api`. Grafo acíclico.
- **Clean Architecture**: `host` no depende de `gtranslate`/`ltranslate` en compile-time.
  Proveedores `Translator` se descubren via `ServiceLoader` (SPI `TranslatorProvider`) en runtime.
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
| `suite/kernel` | 17 | `ModuleLoader`, `ModuleGraph` (resolución con Tarjan, detecta ciclos incluyendo self-cycles, `CONTRACT_MISMATCH`, `JVM_MISMATCH`), `Environment`. |
| `suite/textformatter` | 17 | Motor de formato MiniMessage: `TemplateRenderer`, `TemplateContext`, `MiniEscape` (10 chars), `ChannelRegistry`, transforms. |
| `suite/iflow` | 17 | Motor de flujo: `DefaultRouter`, `Rule`, `RateLimiter` (token bucket per-key con `ReentrantReadWriteLock`), `PermissionChecker` (base + send/receive). |
| `suite/coretranslator` | 17 | Puente deprecated que conserva capacidades del original: traducir textos al vuelo vía PAPI (`%cot_*`), capturar/modificar mensajes al vuelo vía API, inyectar lógica compleja vía SpEL. Deprecated = no recomendarlo para uso nuevo; **NO eliminar** (retrocompatibilidad funcional). |
| `suite/gtranslate` | 17 | Proveedor Google Translate (web scraping + mitigadores UA rotation, rate limit). |
| `suite/ltranslate` | 17 | Proveedor LibreTranslate (self-hosted o público). |
| `suite/sync-discord` | 17 | Gateway Discord v10 (WebSocket JDK + REST), intents, embeds; tokens en `char[]`. |
| `suite/sync-telegram` | 17 | Bot Telegram, long-poll con watermark offset; tokens en `char[]`. |
| `suite/sync-http` | 17 | Webhook + REST (`HttpServer` JDK), inbound/outbound. |
| `suite/sync-tcpudp` | 17 | TCP/UDP raw (`TcpSink`/`UdpSink`), JSON por línea/datagrama. |
| `suite/sync-velocity` | 17 | **Production-ready**: async queue, retry/backoff, métricas, health, dynamic discovery, mapping avanzado. |
| `suite/sync-websocket` | 17 | WebSocket sync sink para tiempo real (SO_REUSEADDR, auth token, sub). |
| `suite/host` | 17 | Composition root: `SuiteHost`, `ConfigLoader` (enum `ConfigPath`), `HostConfig`, `MessageDispatcher` (expande `Direction`→receptores, orquesta por-receptor), port `ChatDelivery` (`host/port/`) y port `ActorDirectory` (`core-api/spi/`). |
| `suite/messages` | 17 | i18n centralizado: catálogos EN/ES, `MessagesCatalog` singleton. |
| `suite/tester` | 17 | Test runtime: 25 tests automatizados (routing, eventos, traducción, formato, iFlow, concurrencia, stress, profiling). `PerformanceProfiler` CPU/heap. Skip mechanism. `/suite test full|stress|concurrency`. |
| `suite/transport` | 17 | `HttpTransport` unificado (`HttpURLConnection`), `MessageCodec` único, SSRF protection (getAllByName, deny patterns RFC 1918/3927/6598). |
| `suite/web-editor` | JS | UI configuración vanilla ES2022 (GitHub Pages estático). |
| `suite/spigot-host` | 17 | **Plugin Spigot de la suite** (`TextFormatterSuite`): `SpigotActorDirectory`, `SpigotChatDelivery` (hop a main thread), bootstrap `SuiteHost`+`MessageDispatcher`, `/suite reload|status|test|lang|toggle|reset|module|suite`. Fat-jar construido (shadow). **Compila con Paper API 1.21.4**. |
| `suite/fabric-host` | 17 | **Plugin Fabric de la suite** (`FabricMod`): **EXCLUIDO del build** (42 errores compile — usa APIs Spigot/Bukkit en vez de Fabric APIs; requiere reescritura completa a `ServerCommandSource`, `FabricAudiences`, eventos Fabric, Brigadier nativo). |
| `suite/manager-api` | 17 | SPI del gestor de módulos runtime: `ModuleCoordinate`, `ModuleDescriptor`, `Environment`, `ModuleLifecycle`. |
| `suite/manager-impl` | 17 | Implementación: GitHub releases downloader (GitHub, local `file://`, HTTP), version resolver (semver + env compat), dependency resolver (parsea module.yml), dependency relocator (fixed), ClassLoader aislado (parent-last), SHA256 verificación (obligatoria, asset `.sha256` separado), register() SPI-only, discoverAll() / discoverAvailableModules() para kernel, manifest validation obligatoria. **Núcleo completado**; pendiente GitHub Releases reales. |
| `suite/presets` | 17 | Presets de configuración predefinidos (standard, rpg, staff, minimal). |
| `suite/inworld` | 17 | Handlers in-world (signos, cofres, libros), WORLD/RADIUS, botones click/hover. **Compila con Paper API 1.21.4**. |
| `suite/observability` | 17 | Metrics endpoint (`/metrics` Prometheus), Debug endpoint (`/debug/*` con auth token, 127.0.0.1), Health checks (JVM, threads, sinks). |
| `suite/extension-api` | 17 | SPI de extensiones: `Extension`, `ExtensionContext`, `ExtensionConfig`, `ExtensionMetadata`. |
| `suite/example-extension` | 17 | Ejemplo de extensión demostrando la API. |
| `suite/loadtest` | 17 | Tests de carga/estrés (JMH + benchmarks). **Compila** (mock TranslationService con TranslatorManager). |
| `suite/performance` | 17 | Profiling y optimización (`PerformanceProfiler`, `HotspotDetector`, `CacheOptimizer`, `MemoryOptimizer`). |
| `suite/common-legacy` | 17 | Referencia histórica (trío monolítico eliminado). |

Dependencias entre motores:
- `kernel→core-api`
- `textformatter→core-api` (+Adventure)
- `iflow→core-api+textformatter`
- `coretranslator→core-api`
- `gtranslate/ltranslate→core-api+transport`
- `host→core-api+textformatter+iflow+messages+tester` (translators via ServiceLoader SPI)
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
  `MiniEscape` escapa 10 chars: `< > \ { } [ ] ( ) # @`.
- `formats.yml` se organiza en **grupos de formato** (cualquier path), cada uno
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
(`channel.rateLimitPerSecond()`), thread-safe con `ReentrantReadWriteLock`.
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
`sonido.enabled`, `repositories[]` (GitHub, local `file://`, HTTP). Claves
opcionales; desconocidas se ignoran (degradan).

**`channels/<id>.yml`**: `name` (es el id; renombrar propaga a rules.yml y
sync), `type` (CHAT|EVENT), `permission`, `send-permission`, `receive-permission`,
`show-sender`, `rate-limit-per-second`, `lang-source`, `lang-target`,
`messages[]`, `tooltips[]`, `sounds[]` (name/volume/pitch).

**`rules.yml`**: `guard.max-steps`, `filter.dedup-fanout`, `priority`, `nodes[]`
(kind, label, matcher, transforms, target), `edges[]`. Mux/fan-out/condición/
ciclos (con self-cycle detection).

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
- **Pendiente**: ampliar opciones YAML para reglas complejas sin perder usabilidad.
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
- **Repository Abstraction (F12-14)**: `repositories:` en `config.yml` con
soporte GitHub, local (`file://`), HTTP; fallback ordenado; testing local
sin GitHub.
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

# Plugin Fabric de la suite (EXCLUIDO - 42 errores compile)
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
- **Tests nuevos añadidos**:
  - 12 tests E2E pipeline en `E2EPipelineTest.java`
  - 14 tests SpEL security en `SpelExpressionEvaluatorSecurityTest.java`
  - 15 tests Translation cache/dedup en `TranslationServiceCacheTest.java`
  - 4 tests Module Manager HTTP repo en `LocalHttpRepositoryTest.java`
  - 19 tests extendidos GTranslate (edge cases, malformed, unicode, rate limit)
  - 15 tests extendidos LTranslate (error handling, unicode, rate limit)
---
## 14. Estado real (2026-09-28)

**Fases cerradas:**
- F0 (GitHub), F1 (web-editor P0), F2 (Java P0/P1 + wiring),
- F3 (channel type system + tester module + default channels).
- F4 (fabric-host **EXCLUIDO** — 42 errores compile, requiere rewrite a Fabric APIs), F5 (i18n strings UI), F6 (iFlow enriquecido: CHANNEL_REDIRECT, PAPI/permisos en SpEL, transform F7+),
- F7 (ConfigValidator real), F8 (comandos dinámicos `/suite`), F9 (sync-velocity **production-ready**),
- F10 (observabilidad: metrics/debug/health), F11 (extensiones/addons SDK), **F12 (manager runtime - núcleo completado)**,
- F13 (sync-websocket), F14 (presets, transform real, engine.parallel), F15 (in-world **compila**),
- **FASE 13 (2026-09-28)**: Clean Architecture (Translator SPI), Release Pipeline CI/CD, Dependency Verification completa.
- F16 (tests, profiling, docs — **tests E2E pendientes, docs sync en progreso**).

**Eliminado:** trío monolítico `common`/`spigot`/`fabric-1.20.6` (nunca
probado en servidor; recuperable desde historial git).

**Probado en producción:** Plugin `TextFormatterSuite` probado en servidor Paper 1.20.6 real — todos los comandos `/suite`, canales join/quit/death/advancement, chat con traducción, rate-limit, y tests runtime funcionando.

**Completado en esta sesión (2026-09-28):**
- ✅ **Clean Architecture (Translator SPI)**: `host` sin dependencias compile-time a `gtranslate`/`ltranslate`; ServiceLoader discovery en runtime.
- ✅ **Release Pipeline**: GitHub Actions CI/CD (`.github/workflows/ci.yml`, `release.yml`), `verification-metadata.xml` completo con SHA256/SHA512, semantic versioning config.
- ✅ **Dependency Verification**: 29 proyectos con `gradle.lockfile` (root + 28 subprojects), `verification-metadata.xml` con todos los checksums transitivos.
- ✅ **fabric-host excluido**: 42 errores compile por uso de APIs Spigot/Bukkit; requiere reescritura completa a Fabric APIs.
- ✅ **Composite build**: Todos los módulos usan `project(':src:...')` dependencies.
- ✅ **Security Sprint 3**: char[] tokens + Arrays.fill(), MiniEscape completo (10 chars), PAPI dynamic check, SpEL LRU cache (1024), SSRF protection, DependencyVerification, SafeConstructor en 4 loaders YAML.

**Pendientes / Deuda conocida:**
- ⚠️ **Tests E2E**: Pipeline completo en Spigot real (chat → iFlow → format → delivery).
- ⚠️ **GitHub Releases**: Para Module Manager (F12-2) + sync-velocity (requiere release pipeline).
- ⚠️ **Documentación**: README, PLAN, Release Notes, Wiki, ADR → un mismo estado (en progreso).
- ⚠️ **Config schema**: Copias manuales (`paths.json`, `js/paths.js`, `js/model.js`, `ConfigLoader.ConfigPath`, `schema-v2.2.md`) → Centralizar generación.
- ⚠️ **gradle.lockfile portable**: Pendiente.
- ⚠️ **Web editor**: Ampliar opciones YAML para reglas complejas sin perder usabilidad.

---
## 15. Problemas críticos arreglados (historial)

| ID | Problema | Fix |
|---|---|---|
| **C1** | Contrato `Message` roto | `withX()` methods inmutables, `Builder.from()` |
| **C2** | `MessageEvent` roto | `cancelled` no final, imports |
| **C3** | Module Manager stubs | `URLClassLoader`, `register()` SPI-only, `discoverAll()`, manifest validation |
| **C4** | Debug endpoint inseguro | 127.0.0.1, auth token, sin `/debug/simulate`, executor shutdown |
| **C5** | HEAD no compilable | Core modules compilan |
| **H1** | RateLimiter global | Per-key + `ReentrantReadWriteLock` |
| **H2** | Discord bypass iFlow | `mirror(DispatchReport)` solo si delivered |
| **H3** | Language detection O(n) | `resolvedSourceLanguage` caché |
| **CT-01** | `Message.toJson()` | Serialización JSON real |
| **CFG-01** | ConfigLoader silencioso | `LoadResult<T>` con errores + logging ERROR |
| **OBS-01** | DebugEndpoint leak | Executor shutdown en stop() |
| **F12-M2** | Version resolver | Semver ranges + env compat |
| **F12-M3** | Dependency resolver | Parsing module.yml desde JAR |
| **F12-M6** | register() semántica | Descriptor SPI only |
| **F12-14** | Repository Abstraction | `repositories:` config con GitHub, local, HTTP |

---
## 16. Referencias rápidas

| Doc | Contenido |
|---|---|
| `docs/PLAN.md` | Plan vivo con fases, estado, próxima acción |
| `docs/ADR.md` | Decisiones de arquitectura con fechas |
| `docs/wiki/` | Wiki usuario/dev (config, channels, iFlow, sync, editor, comandos, API, etc.) |
| `src/*/README.md` | Codeguides por módulo (responsabilidades, deps, data flow, entry points) |
| `docs/NEW-FEATURES.md` | Tracking de features nuevas por fase |

**Probado en producción:** Plugin `TextFormatterSuite` probado en servidor Paper 1.20.6 real — todos los comandos `/suite`, canales join/quit/death/advancement, chat con traducción, rate-limit, y tests runtime funcionando.