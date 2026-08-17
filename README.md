# TextFormatter Suite

Plataforma **agnóstica** de traducción y routing de chat para Minecraft.
Un conjunto de **17 módulos** (JARs independientes, Spigot/CraftBukkit +
Fabric) que arma todo lo que era ChatTranslator y mucho más, bajo un núcleo
hexagonal (ports & adapters) con motor de reglas, formatos MiniMessage, grafos
iFlow y un web-editor de configuración.

> **Nombres.** *Suite* = paraguas (el conjunto de módulos). *ChatTranslator*
> queda retirado como marca global y se usa únicamente para los traductores
> **GTranslate** / **LTranslate**. Renombrados respecto a v4: `FormatGroups` →
> `Channels`, `FormatApplier` → `ChannelApplier`, `FormatCatalog` →
> `ChannelCatalog`, `lastFormatPath` → `channel`.

---

## 1. Arquitectura (hexagonal)

```
┌─────────────────────────────────────────────────────────────┐
│  ADAPTERS       spigot ─────────────── fabric-1.20.6         │
│  (implementan portos; nunca se importan entre sí)            │
└───────────────▲─────────────────────────────────────────────┘
                │ implementa
┌───────────────┴─────────────────────────────────────────────┐
│  common  (núcleo v4 legacy, Java 8)                         │
│  message/ player/ platform(ports)/ rules/ event/ translate/ │
│  template/ scripting/ chat/ storage/ config/ api             │
└─────────────────────────────────────────────────────────────┘

        ↯ puente: common-legacy → suite/coretranslator

┌─────────────────────────────────────────────────────────────┐
│  suite  (17 módulos Gradle, Java 17, publican a mavenLocal)  │
│  core-api (SPI + modelo)  kernel  textformatter  iflow       │
│  coretranslator  gtranslate  ltranslate  sync-*  host        │
│  web-editor (JS vanilla)                                     │
└─────────────────────────────────────────────────────────────┘
```

**Reglas de dependencia:**
- `core-api` = contrato único (SPI `Module`/`Translator`/`SyncSink`/… + modelo
  `Message`). Dependencias: cero (solo JDK).
- Motores (`kernel`, `textformatter`, `iflow`, `gtranslate`, `ltranslate`,
  `coretranslator`, `host`) dependen **solo** de `core-api`. Grafo acíclico.
- Adaptadores de plataforma dependen de `core-api` + exactamente un SDK
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
| `common` | 8 | Núcleo v4 legacy (dominio + portos + infra). Referencia/legacy. |
| `spigot` | 8 | Adapter Spigot/**CraftBukkit** (solo `org.bukkit.*`, `AsyncPlayerChatEvent`, adventure-platform-bukkit, NMS por reflection). |
| `fabric-1.20.6` | 21 | Adapter Fabric 1.20.6 (fabric-loom, mixins). |
| `suite/core-api` | 17 | SPI interno: `Module`, `ModuleDescriptor`, semver, capabilities, modelo `Message`, `Translator`, `TranslationService`, `SyncSink`, `SyncListener`, `PlaceholderResolver`, `PluginLogger`, `ExpressionEvaluator`. |
| `suite/kernel` | 17 | `ModuleLoader`, `ModuleGraph` (resolución con Tarjan, detecta ciclos, `CONTRACT_MISMATCH`, `JVM_MISMATCH`), `Environment`. |
| `suite/textformatter` | 17 | Motor de formato MiniMessage: `TemplateRenderer`, `TemplateContext`, `MiniEscape`, `ChannelRegistry`, transforms. |
| `suite/iflow` | 17 | Motor de flujo: `DefaultRouter`, `Rule`, `RateLimiter` (token bucket), `PermissionChecker` (base + send/receive). |
| `suite/coretranslator` | 17 | SPI `Translator` + `LegacyBridge` (conversión `ChatMessage→Message`, deprecated). |
| `suite/gtranslate` | 17 | Proveedor Google Translate. |
| `suite/ltranslate` | 17 | Proveedor LibreTranslate. |
| `suite/sync-discord` | 17 | Gateway Discord v10 (WebSocket JDK + REST), intents, embeds. |
| `suite/sync-telegram` | 17 | Bot Telegram, long-poll con watermark offset. |
| `suite/sync-http` | 17 | Webhook + REST (`HttpServer` JDK), inbound/outbound. |
| `suite/sync-tcpudp` | 17 | TCP/UDP raw (`TcpSink`/`UdpSink`), JSON por línea/datagrama. |
| `suite/sync-velocity` | 17 | **F7+ pendiente** (stub en editor/config, no existe en disco). |
| `suite/host` | 17 | Composition root: `SuiteHost`, `ConfigLoader` (enum `ConfigPath`), `HostConfig`. Ensambla todos. |
| `suite/web-editor` | JS | UI configuración vanilla ES2022 (GitHub Pages estático). |

Dependencias entre motores: `kernel→core-api` · `textformatter→core-api`
(+Adventure) · `iflow→core-api+textformatter` · `coretranslator→core-api` ·
`gtranslate/ltranslate→core-api` · `host→core-api+textformatter+iflow+
gtranslate+ltranslate`.

---

## 3. Modelo de mensaje

Cada evento de chat produce unidades atómicas **`Message`** con su propio
emisor, **`Direction`** (audiencia), arrays de contenido, grupo de formato,
colores, sonidos y par de idiomas — **no** hay par from/to embebido. El mensaje
al iniciador y el broadcast al resto son unidades independientes con formato y
cancelación independientes. **Inmutables**; las reglas mutan un clon privado.

Un `Message` lleva:

- `type` — `MessageType` (CHAT, PRIVATE, MENTION, JOIN, LEAVE, DEATH,
  ADVANCEMENT, SIGN, INTERNAL, CUSTOM).
- `sender` — `Actor` (uuid, name, kind, language, native handle).
- `direction` — `Direction` (INITIATOR, OTHERS, ALL, CONSOLE, WORLD, RADIUS,
  PERMISSION, SPECIFIC) con canal y receptores explícitos opcionales.
- `messages` / `toolTips` — `Formats` paralelas (textos + MiniMessage).
- `sounds` — specs `name;volume;pitch`.
- `colorMode`, `langSource`, `langTarget`, `translate`, `formatPapi`.
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
`DROP`, `REJECT`, `REDIRECT` (a consola), `RATE-LIMIT`.

- Entradas múltiples = **mux** (independientes); salidas múltiples = **fan-out**
  (broadcast); ramificación = condición-filtro; ciclos permitidos con guard
  `max-steps` (default 512, DROP + log al superar).
- El editor lo edita como **grafo de nodos** (`rules.yml`): `input`, `cond`,
  `transform`, `loop`, `sleep`, `output`, `redirect`, con transforms
  `rewrite`/`sounds`/`sleep` (requieren motor F7+, se marcan en manifest).
- Prioridad = BFS por capas desde entradas; empates por índice de creación.

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
manifest.json         → versiones + validación + capabilities
```

**`config.yml`**: `quick-look`, `general.language`, `iflow.engine.parallel`,
`sonido.enabled`. Claves opcionales; desconocidas se ignoran (degradan).

**`channels/<id>.yml`**: `name` (es el id; renombrar propaga a rules.yml y
sync), `permission`, `send-permission`, `receive-permission`, `show-sender`,
`rate-limit-per-second`, `lang-source`, `lang-target`, `messages[]`,
`tooltips[]`, `sounds[]` (name/volume/pitch).

**`rules.yml`**: `guard.max-steps`, `filter.dedup-fanout`, `priority`, `nodes[]`
(kind, label, matcher, transforms, target), `edges[]`. Mux/fan-out/condición/
ciclos.

**`translators/*.yml`**: `provider` (google|libre), `active`, `base-url`,
`api-key`, `pool.max-concurrent`.

**`sync/*.yml`**: discord (token, channel, intents) · telegram (token,
chat-id, hub) · http (webhook-url, inbound-port, path) · tcp-udp (protocol,
host, outbound-port, inbound-port) · velocity (enabled, secret, servers[],
mapping).

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

`ChatTranslatorApi.messageEvents()` devuelve un `MessageEventBus` thread-safe.
Los listeners corren sincrónicamente en el hilo de dispatch, antes de reglas y
renderizado:

```java
api.messageEvents().register("anti-swear", event -> {
    if (event.message().text().contains("badword")) {
        event.setCancelled(true);
    }
});
```

Un listener puede cancelar el mensaje, `setMessage(...)` reemplazarlo o
`setProcessed(true)` tomar el control de la entrega.

---

## 10. Wiring de plataforma

| Evento | Spigot | Fabric |
|---|---|---|
| Chat | `AsyncPlayerChatEvent` | `ServerMessageEvents.ALLOW_CHAT_MESSAGE` |
| Privado | vía `ChatMessage.target` | igual (nivel motor) |
| Join / Leave | `PlayerJoinEvent` / `PlayerQuitEvent` | `PlayerManagerMixin` |
| Death | `PlayerDeathEvent` | `PlayerManagerMixin` |
| Advancement | `PlayerAdvancementDoneEvent` | `PlayerManagerMixin` |
| Sign | `PlayerInteractEvent` (sneak+left) | `AttackBlockCallback` |
| Sonidos | registry Bukkit `Sound` | `Registries.SOUND_EVENT` |

`NmsLocaleBridge` lee locales: `Player#getLocale()` (1.12.2+) con fallback
reflective al campo NMS `EntityPlayer#locale` (caché de Class/Method/Field +
mapa por UUID), de 1.8 en adelante.

---

## 11. Configuración en runtime

- Nunca toca el stack YAML del servidor: ambos adapters embuten `snakeyaml`
  dentro del jar y parsean con un loader propio (`core.config.ConfigLoader`),
  también usado para el user-data store.
- Defaults (`config.yml`, `formats.yml`, `rules.yml`) van **dentro del jar con
  sus comentarios intactos**; en primer arranque `core.config.DefaultFiles` los
  copia verbatim y nunca sobrescribe ediciones del usuario.
- `JsonCodec` (default `DefaultJsonCodec`) serializa `Message` a JSON compacto
  con cero dependencias externas, usado para cruzar fronteras CoT.

---

## 12. Construcción

> Requiere JDK 8 y 21 (daemon Gradle y fabric-loom en 21; core/spigot target 8)
> y JDK 17 para `suite/*`. Gradle wrapper 8.13, fabric-loom 1.10.5. Declara las
> rutas JDK en `org.gradle.java.installations.paths` (`gradle.properties`).
> Red necesaria en el primer build (Fabric maven, Mojang).

```bash
# Núcleo v4 (common/spigot/fabric)
./gradlew :common:build
./gradlew :spigot:shadowJar          # build/libs/chattranslator-spigot-<ver>.jar
./gradlew :fabric-1.20.6:build

# Suite (cada módulo es un build independiente que publica a mavenLocal)
cd suite/<modulo> && ./gradlew publishToMavenLocal
./gradlew test                       # tests del módulo

# Web editor
cd suite/web-editor
npm run check                        # format:check + lint + test (99 unit)
npm run test:integration             # harnesses func/interact/click/chain/undo/diffing/bind
```

---

## 13. Pruebas

- **Suite Java**: 131+ tests verdes (kernel, textformatter, iflow,
  gtranslate/ltranslate, sync-http/tcpudp/telegram/discord, host
  `ConfigLoaderTest` e2e contra el schema del editor).
- **Web editor**: 99 unitarios (StateStore 40, model 30, validate 29) +
  harnesses de integración (func 28, interact, click, chain, undo, diffing,
  bind).
- **Golden tests**: el editor y el host deben validar el mismo config
  (`ConfigLoaderTest.parsesEditorExportedDefaultConfig` verde).

---

## 14. Estado real (2026-08-15)

**Fases cerradas:** F0–F4 (kernel/SPI, TextFormatter + Channels, iFlow,
GTranslate/LTranslate, CoreTranslator deprecated), F5 (bordes Sync:
sync-http/tcpudp/telegram/discord con tests), F6 (Web Editor funcional con
schema v2.2 y round-trip exacto).

**Pendientes (F7+):** knob `engine.parallel` en `DefaultRouter`, `transform`
en el motor, `sync-velocity` real, wiring suite→plataforma (composition root
`spigot-host`/`fabric-host`), strings de UI centralizados en `lang/`
(0% hoy vs ~98% del original), migración automática de configs v4, permisos
PAPI dentro de SpEL, destino "channel" en reglas.

---

## 15. Bugs conocidos y deuda (2026-08-16, auditoría)

**Web editor:** mutaciones sobre clon descartado en `actions.js:14`/`props.js`
(dup/rename/editar/tipo no hacen nada), recursión infinita `core.js:82-84`,
preview lee `res.output` pero `simulate` devuelve `outputs/order/reason`,
`esc()` no escapa `&<>"` (XSS), mojibake UTF-8 en strings de UI, drift
`config.iflow.*` vs `graph.*`.

**Java:** `SpigotScheduler.ticks()` truncaba delays <1s a 50ms (**arreglado**),
`RateLimiter` sin purga de buckets (**arreglado**: ConcurrentHashMap + TTL),
`HttpSink` no idempotente (**arreglado**: synchronized + volatile),
`TcpSink`/`UdpSink` sin `volatile` (**arreglado**), `NmsLocaleBridge` reflection
por mensaje (**arreglado**: caché + logging).

**Arquitectura:** dominio duplicado `common`↔`core-api` (~2300 líneas),
`HttpTransport` byte-idéntico en gtranslate/ltranslate, `MessageCodec` x2,
config schema en 5 copias manuales, suite no integrada en `settings.gradle`
raíz (15 builds sueltos).

> Ver **docs/PLAN.md** para el plan de ejecución detallado y el roadmap.

---

## 16. Documentación

| Documento | Contenido |
|---|---|
| `docs/ADR.md` | Registro de decisiones de arquitectura (contratos SPI, fases F0–F7, consecuencias). |
| `docs/PLAN.md` | Plan de ejecución vivo: bugs confirmados, deuda, roadmap por fases. |
| `docs/AUDITORIA.md` | Auditoría completa 2026-08-16: volcado íntegro de los 5 subagentes (bugs, paridad, arquitectura, clean code, roadmap) + verificación manual. |
| `docs/web-editor/DESIGN.md` | Diseño del Web Editor (layout GIMP/Grafana, canvas de nodos, decisiones). |
| `docs/web-editor/schema-v2.2.md` | Schema v2.2 detallado (archivos, claves, reglas de round-trip). |

## 17. Licencia

**GPL-3.0** (LICENSE). Repositorio:
https://github.com/majhrs16-official/TextFormatter-Suite

Documentación del proyecto original: https://github.com/Majhrs16/ChatTranslator y https://github.com/Majhrs16/ChatTranslator/wiki (referencia histórica).
