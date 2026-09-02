1: # TextFormatter Suite
2: 
3: Plataforma **agnóstica** de traducción y routing de chat para Minecraft.
4: Un conjunto de módulos (JARs independientes) que rehace todo lo que era
5: ChatTranslator —y mucho más— bajo un núcleo hexagonal real (ports &
6: adapters), motor de reglas, formatos MiniMessage, grafos iFlow y un
7: web-editor de configuración.
8: 
9: > **Nombres.** *Suite* = paraguas (el conjunto de módulos). *ChatTranslator*
10: > queda retirado como marca global y se usa únicamente para los traductores
11: > **GTranslate** / **LTranslate**. La **retrocompatibilidad es funcional**:
12: > paridad de comportamiento con el proyecto original ChatTranslator, **no**
13: > con ningún código intermedio. El trío monolítico `common`/`spigot`/
14: > `fabric-1.20.6` fue eliminado del árbol (recuperable desde el historial
15: > git); los adapters reales son los módulos `*-host`.
16: 
17: ---
18: 
19: ## 1. Arquitectura (hexagonal)
19: 
20: ```
21: ┌─────────────────────────────────────────────────────────────┐
22: │  ADAPTERS       spigot-host ──────── fabric-host (⏳)        │
23: │  (implementan puertos; nunca se importan entre sí)           │
24: └───────────────▲─────────────────────────────────────────────┘
25:                 │ implementa puertos + bootstrapea
26: ┌───────────────┴─────────────────────────────────────────────┐
27: │  suite  (módulos Gradle, Java 17/21)                         │
27: │  core-api (SPI + modelo, JDK-puro)  kernel  textformatter    │
28: │  iflow  gtranslate  ltranslate  sync-*  host                 │
29: │  messages  tester  transport  coretranslator                 │
30: │  web-editor (JS vanilla)                                     │
30: └─────────────────────────────────────────────────────────────┘
31: ```
32: 
33: **Reglas de dependencia:**
34: - `core-api` = contrato único (SPI `Module`/`Translator`/`SyncSink`/
35:   `ActorDirectory`/… + modelo `Message`). Dependencias: cero (solo JDK).
36: - Motores (`kernel`, `textformatter`, `iflow`, `gtranslate`, `ltranslate`,
37:   `host`, `messages`, `tester`, `transport`, `coretranslator`) dependen
38:   **solo** de `core-api`. Grafo acíclico.
39: - Adaptadores de plataforma dependen de la suite + exactamente un SDK
40:   (`spigot-api`, `fabric-api`); nunca entre sí.
41: - `web-editor` comunica vía YAML/schema; cero acoplamiento al runtime Java.
42: - Descubrimiento de módulos: **ServiceLoader/SPI** (`META-INF/services/…Module`).
43:   **Sin modloader**: sin ciclo de vida gestionado, sin manifiesto custom.
44: - **Handshake doble**: versión JVM de runtime + versión de contrato SPI
45:   (semver por artefacto `*-api`); mismatch → cargar/degradar/avisar.
46: 
47: ---
48: 
49: ## 2. Módulos
50: 
51: | Módulo | Java | Rol |
51: |---|---|---|
52: | `suite/core-api` | 17 | SPI interno: `Module`, `ModuleDescriptor`, semver, capabilities, modelo `Message`, `Translator`, `TranslationService`, `SyncSink`, `SyncListener`, `ActorDirectory`, `PlaceholderResolver`, `PluginLogger`. |
53: | `suite/kernel` | 17 | `ModuleLoader`, `ModuleGraph` (resolución con Tarjan, detecta ciclos, `CONTRACT_MISMATCH`, `JVM_MISMATCH`), `Environment`. |
54: | `suite/textformatter` | 17 | Motor de formato MiniMessage: `TemplateRenderer`, `TemplateContext`, `MiniEscape`, `ChannelRegistry`, transforms. |
55: | `suite/iflow` | 17 | Motor de flujo: `DefaultRouter`, `Rule`, `RateLimiter` (token bucket), `PermissionChecker` (base + send/receive). |
56: | `suite/coretranslator` | 17 | Puente deprecated que conserva capacidades del original: traducir textos al vuelo vía PAPI (`%cot_*`), capturar/modificar mensajes al vuelo vía API, inyectar lógica compleja vía SpEL. Deprecated = no recomendarlo para uso nuevo; **NO eliminar** (retrocompatibilidad funcional). |
57: | `suite/gtranslate` | 17 | Proveedor Google Translate. |
58: | `suite/ltranslate` | 17 | Proveedor LibreTranslate. |
59: | `suite/sync-discord` | 17 | Gateway Discord v10 (WebSocket JDK + REST), intents, embeds. |
60: | `suite/sync-telegram` | 17 | Bot Telegram, long-poll con watermark offset. |
61: | `suite/sync-http` | 17 | Webhook + REST (`HttpServer` JDK), inbound/outbound. |
61: | `suite/sync-tcpudp` | 17 | TCP/UDP raw (`TcpSink`/`UdpSink`), JSON por línea/datagrama. |
62: | `suite/sync-velocity` | 17 | **F7+ pendiente** (stub en editor/config, no existe en disco). |
63: | `suite/host` | 17 | Composition root: `SuiteHost`, `ConfigLoader` (enum `ConfigPath`), `HostConfig`. Ensambla todos. Wiring listo para plataforma: `MessageDispatcher` (expande `Direction`→receptores, orquesta por-receptor), port `ChatDelivery` (`host/port/`) y port `ActorDirectory` (`core-api/spi/`). |
62: | `suite/messages` | 17 | i18n centralizado: catálogos EN/ES, `MessagesCatalog` singleton. |
63: | `suite/tester` | 17 | Test runtime: 25 tests automatizados (routing, eventos, traducción, formato, iFlow, concurrencia, stress, profiling). `PerformanceProfiler` CPU/heap. Skip mechanism. `/suite test full|stress|concurrency`. |
64: | `suite/transport` | 17 | `HttpTransport` unificado (`HttpURLConnection`), `MessageCodec` único. |
65: | `suite/coretranslator` | 17 | Puente legacy (retrocompatibilidad funcional). |
66: | `suite/web-editor` | JS | UI configuración vanilla ES2022 (GitHub Pages estático). |
67: | `suite/spigot-host` | 17 | **Plugin Spigot de la suite** (`TextFormatterSuite`): `SpigotActorDirectory`, `SpigotChatDelivery` (hop a main thread), bootstrap `SuiteHost`+`MessageDispatcher`, `/suite reload|status|test|lang|toggle|reset`. Fat-jar construido (shadow). |
68: 
69: Dependencias entre motores: `kernel→core-api` · `textformatter→core-api`
69: (+Adventure) · `iflow→core-api+textformatter` · `coretranslator→core-api` ·
70: `gtranslate/ltranslate→core-api+transport` · `host→core-api+textformatter+iflow+
71: gtranslate+ltranslate+messages+tester`.
72: 
73: ---
74: 
75: ## 3. Modelo de mensaje
76: 
77: Cada evento de chat produce unidades atómicas **`Message`** con su propio
78: emisor, **`Direction`** (audiencia), arrays de contenido, grupo de formato,
79: colores, sonidos y par de idiomas — **no** hay par from/to embebido. El mensaje
80: al iniciador y el broadcast al resto son unidades independientes con formato y
81: cancelación independientes. **Inmutables**; las reglas mutan un clon privado.
82: 
83: Un `Message` lleva:
84: 
85: - `type` — `MessageType` (CHAT, PRIVATE, MENTION, JOIN, LEAVE, DEATH,
86:   ADVANCEMENT, SIGN, INTERNAL, CUSTOM).
87: - `sender` — `Actor` (uuid, name, kind, language, native handle).
87: - `direction` — `Direction` (INITIATOR, OTHERS, ALL, CONSOLE, WORLD, RADIUS,
88:   PERMISSION, SPECIFIC) con canal y receptores explícitos opcionales.
89: - `messages` / `toolTips` — `Formats` paralelas (textos + MiniMessage).
90: - `sounds` — specs `name;volume;pitch`.
90: - `colorMode`, `langSource`, `langTarget`, `translate`, `formatPapi`.
91: - `lastFormatPath` — el grupo de formato que construyó el mensaje.
91: 
92: ---
93: 
94: ## 4. Motor de formato (MiniMessage + Adventure)
95: 
96: - `<tr>text</tr>` marca la parte a traducir (por receptor).
97: - `%ct_messages%`, `$ct_messages$`, `{0}` inyectan el texto bruto.
98: - `%player_name%`, `%player_uuid%`, `%lang_source%`, `%lang_target%` son
99:   built-ins; cualquier otro `%variable%` pasa por `PlaceholderResolver`
100:   (PlaceholderAPI en Spigot, identidad en Fabric).
101: - `<expr>…</expr>` evalúa una expresión SpEL.
102: - Todos los valores dinámicos se escapan para impedir inyección MiniMessage.
103: 
104: `formats.yml` se organiza en **grupos de formato** (cualquier path), cada uno
105: con `messages.formats`/`messages.texts`, `toolTips`, `sounds` y opcionalmente
106: `sourceLang`/`targetLang`. Un grupo por tipo de evento, renderizado por
107: receptor al idioma de ese receptor.
108: 
110: ---
111: 
112: ## 5. Motor de reglas (rules.yml → iFlow)
113: 
114: Reemplaza ConditionalEvents. Las reglas aplican por mensaje antes del formato y
115: la entrega; un mensaje cancelado se descarta.
116: 
117: ```yaml
118: rules:
119:   spam:
120:     events: [CHAT]
121:     conditions:
122:       - "'spam' in #msg.texts[0]"
123:     actions:
124:       - cancel()
125:       - skipTranslate()
126: ```
127: 
127: - Cada regla es `(name, List<MessageType>, conditions SpEL, actions SpEL)`.
128: - `ScriptSurface` expone operaciones atómicas (`setText`, `setTexts`,
129:   `setLangSource`, `setLangTarget`, `setColorMode`, `setFormatPapi`,
130:   `show/hide`, `cancel`, `skipTranslate`) y helpers (`setFormat(path)`,
131:   `clone()`, `toJson()`). Root SpEL: `#msg`.
132: 
133: ### iFlow (grafos)
134: 
135: Firewall por receptor/emisor con default-policy por canal y targets `LOG`,
136: `DROP`, `REJECT`, `REDIRECT` (a consola), `RATE-LIMIT`.
137: 
138: - Entradas múltiples = **mux** (independientes); salidas múltiples = **fan-out**
138:   (broadcast); ramificación = condición-filtro; ciclos permitidos con guard
139:   `max-steps` (default 512, DROP + log al superar).
140: - El editor lo edita como **grafo de nodos** (`rules.yml`): `input`, `cond`,
141:   `transform`, `loop`, `sleep`, `output`, `redirect`, con transforms
142:   `rewrite`/`sounds`/`sleep` (requieren motor F7+, se marcan en manifest).
143: - Prioridad = BFS por capas desde entradas; empates por índice de creación.
144: 
145: ---
146: 
147: ## 6. Permisos por canal
148: 
149: - **Base**: un único permiso `cht.<channel>` = suscripción (poseerlo = adscrito).
150: - **Opción**: `send-permission` / `receive-permission` para asimetría nativa
151:   ("todos leen, solo staff escribe"). Default ACCEPT si no se define nada.
152: - La asimetría también puede vivir en reglas de iFlow.
153: 
154: ---
155: 
156: ## 7. Configuración — Schema v2.2 (fuente única de verdad)
156: 
157: El editor importa/exporta contra este schema; el **host** (`ConfigLoader`)
159: parsa la misma estructura. **Round-trip exacto**: panel → YAML → panel sin
160: pérdida. Lo que no quepa aquí es falta de precisión del schema o del motor.
161: 
162: **Archivos del proyecto** (`textformatter-suite.zip`):
163: 
164: ```
165: config.yml            → HostConfig (idéntico a ConfigLoader.loadConfig)
166: channels/<canal>.yml  → ChannelRegistry (idéntico a ConfigLoader.loadChannels)
167: rules.yml             → grafo iFlow (editor/F7+)
169: translators/*.yml     → proveedores (google/libre)
170: sync/discord.yml      sync/telegram.yml  sync/http.yml
171: sync/tcp-udp.yml      sync/velocity.yml
172: manifest.json         → versiones + validación + capabilities
173: ```
174: 
175: **`config.yml`**: `quick-look`, `general.language`, `iflow.engine.parallel`,
176: `sonido.enabled`. Claves opcionales; desconocidas se ignoran (degradan).
177: 
177: **`channels/<id>.yml`**: `name` (es el id; renombrar propaga a rules.yml y
178: sync), `type` (CHAT|EVENT), `permission`, `send-permission`, `receive-permission`,
179: `show-sender`, `rate-limit-per-second`, `lang-source`, `lang-target`,
179: `messages[]`, `tooltips[]`, `sounds[]` (name/volume/pitch).
180: 
181: **`rules.yml`**: `guard.max-steps`, `filter.dedup-fanout`, `priority`, `nodes[]`
182: (kind, label, matcher, transforms, target), `edges[]`. Mux/fan-out/condición/
183: ciclos.
184: 
185: **`translators/*.yml`**: `provider` (google|libre), `active`, `base-url`,
186: `api-key`, `pool.max-concurrent`.
187: 
188: **`sync/*.yml`**: discord (token, channel, intents) · telegram (token,
189: chat-id, hub) · http (webhook-url, inbound-port, path) · tcp-udp (protocol,
190: host, outbound-port, inbound-port) · velocity (enabled, secret, servers[],
191: mapping).
192: 
193: **`manifest.json`**: `schema`, `suite-version`, `generated-at`,
193: `capabilities` (`transforms: true/false`), `validation` (errors/warnings/
194: blocking/issues).
195: 
196: **Reglas de round-trip**: (1) writer/parser propios, byte-idéntico;
197: (2) `config.yml` + `channels/*.yml` parsables por el host (`ConfigLoaderTest`);
198: (199: (3) import acepta cualquier export; campos faltantes = defaults; campos
200: desconocidos se **conservan**.
201: 
202: ---
203: 
203: ## 8. Web Editor (F6)
204: 
205: Artefacto estático único (GitHub Pages), HTML+CSS+JS vanilla, sin build.
206: 
207: - **Canvas de nodos** como centro de edición: celdas (TextFormatter) y grafos
208:   (iFlow) con puertos arriba (entradas) y abajo (salidas); zoom `ctrl+rueda`
209:   (25–400%), pan `espacio+arrastre`, snap 20px, minimapa.
210: - **Layout del usuario**: paneles extraíbles/reordenables; tema (oscuro
211:   default) e idioma (en/es) en localStorage; autosave del proyecto.
211: - **Round-trip exacto** YAML (import→panel→export). Schema primero: el editor
212:   no dibuja nada que el schema no represente.
213: - **Preview** replica el pipeline del motor (port JS + fixtures dorados contra
214:   el host Java), sin red; traducción viva opcional con pool + rate-limit y
215:   fallback a inglés.
216: - **Validación global** → `[{nivel, grupo, ruta, mensaje}]`; badges, rings
217:   rojos, toasts, manifest. **Nunca se descarga con errores bloqueantes.**
218: - **Arquitectura JS**: StateStore (estado + historial undo/redo 80 +
219:   persistencia + validadores con rollback + diffing de paths + autosave 400ms),
220:   rendering con diffing, validación incremental por `revision()`, paths.json
221:   centralizado para data-bind, i18n en/es, docking de paneles.
222: 
223: ---
224: 
225: ## 9. Eventos para integraciones externas (diseño, pendiente)
226: 
227: > La API descrita antes aquí (`ChatTranslatorApi.messageEvents()`) pertenecía
228: > al trío monolítico eliminado. El equivalente de la suite está diseñado pero
229: > **aún no implementado**.
230: 
231: Plan: un bus público thread-safe en `core-api` (`MessageBus`), alimentado por
232: `MessageDispatcher` **antes** de reglas y renderizado:
233: 
234: ```java
235: bus.register("anti-swear", event -> {
236:     if (event.message().text().contains("badword")) {
237:         event.setCancelled(true);   // o setMessage(...) / setProcessed(true)
238:     }
239: });
240: ```
241: 
242: Los listeners correrán en el hilo de dispatch; cancelar/reemplazar/tomar
243: control de la entrega serán operaciones del `event`. Este bus es además el
245: punto de enganche que reemplaza al evento Bukkit-custom que usaba
246: ConditionalEvents y la base sobre la que `coretranslator` recuperará las
247: capacidades del original (PAPI al vuelo `%cot_*`, captura/modificación de
248: mensajes, SpEL).
249: 
250: ---
251: 
251: ## 10. Wiring de plataforma
252: 
252: Los adapters implementan los puertos del motor y eligen el hilo:
253: 
253: | Puerto (`core-api/spi` / `host/port`) | Spigot (`spigot-host`) | Fabric (`fabric-host`, ⏳) |
254: |---|---|---|
255: | `ActorDirectory` | `SpigotActorDirectory` (idioma: store→locale→null; snapshot anti-CME) | ⏳ |
256: | `ChatDelivery` | `SpigotChatDelivery` (BukkitAudiences, hop a main thread, sonidos normalizados) | ⏳ |
257: | Evento chat | `AsyncPlayerChatEvent` (LOWEST claim-first; claim configurable: `cancel-event`\|`clear-recipients`) | `ServerMessageEvents.ALLOW_CHAT_MESSAGE` |
258: | Join/Quit/Death/Advancement | canales convencionales `join`/`quit`/`death`/`advancement` (presencia = activado) | ⏳ |
259: | Idioma por usuario | `UserLanguageStore` (YAML) + `/suite lang [jugador] <auto\|off\|código>`; `off` = sin traducción | ⏳ |
259: | Permisos | `Player#hasPermission` | ⏳ |
260: | Mundo/radio | `getWorld().getName()` / `distanceSquared` | ⏳ |
260: 
261: ---
262: 
262: ## 11. Configuración en runtime
263: 
264: - Nunca toca el stack YAML del servidor: los hosts embuten `snakeyaml`
265:   dentro del jar y parsean con loaders propios (`host/config/ConfigLoader`,
266:   `TranslatorsConfig`), tolerantes a archivos corruptos (degradan, no crashean).
267: - Defaults (`config.yml`, `channels/chat.global.yml`, `translators/google.yml`)
268:   van **dentro del jar** (`resources/defaults/`); en primer arranque se copian
269:   si faltan y **nunca sobrescriben** ediciones del usuario.
270: - Estrategia de E/S: lectura directa delegando en el Page Cache del SO;
271:   `/suite reload` relee todo el layout sin watchers ni polling.
272: 
273: ---
274: 
275: ## 12. Construcción
276: 
277: > Requiere JDK 17 y 21 (toolchain Gradle; `options.release=17` para bytecode).
278: > Gradle wrapper 8.13, fabric-loom 1.6.12. Declara las rutas JDK en
279: > `org.gradle.java.installations.paths` (`gradle.properties`). Con caché Gradle
280: > poblada, todo compila `--offline`.
281: 
282: ```bash
283: # Suite (cada módulo es un build independiente)
283: cd suite/core-api      && ./gradlew test publishToMavenLocal --offline --no-daemon
284: cd suite/kernel        && ./gradlew test publishToMavenLocal --offline --no-daemon
285: cd suite/textformatter && ./gradlew test publishToMavenLocal --offline --no-daemon
286: cd suite/iflow         && ./gradlew test publishToMavenLocal --offline --no-daemon
286: cd suite/gtranslate    && ./gradlew publishToMavenLocal --offline --no-daemon
287: cd suite/ltranslate    && ./gradlew publishToMavenLocal --offline --no-daemon
## 14. Estado real (2026-09-02)

**Fases cerradas:** F0 (GitHub), F1 (web-editor P0), F2 (Java P0/P1 + wiring),
F3 (channel type system + tester module + default channels).

**Eliminado:** trío monolítico `common`/`spigot`/`fabric-1.20.6` (nunca
probado en servidor; recuperable desde historial git).

**Probado en producción:** Plugin `TextFormatterSuite` probado en servidor Paper 1.20.6 real — todos los comandos `/suite`, canales join/quit/death/advancement, chat con traducción, rate-limit, y tests runtime funcionando.

**Pendientes (F4+):**
- `fabric-host` funcional
- Strings UI centralizados en `lang/` (i18n)
- Motor de reglas iFlow enriquecido (destino "channel", permisos/PAPI en SpEL, `MessageEventBus`, `transform` F7+)
- `ConfigValidator` real
- Comandos dinámicos (`/suite` base configurable)
- `sync-velocity` real
- Observabilidad (metrics/debug/simulate endpoints)
- Extensiones/addons (core-api 2.2 + SDK)
- Descargador runtime (classloader dinámico + manifest + sha256 + allowlist)
- sync-websocket
- Presets, `transform` real, `engine.parallel`
- F8 in-world (signos/cofres/libros, WORLD/RADIUS, caché+glosario, botones click/hover)
290: cd suite/sync-telegram && ./gradlew publishToMavenLocal --offline --no-daemon
291: 
292: # Plugin Spigot de la suite (fat-jar)
293: cd suite/spigot-host   && ./gradlew build --offline --no-daemon
294: 
295: # Web editor
296: cd suite/web-editor
297: npm run check                        # format:check + lint + test (99 unit)
298: npm run test:integration             # harnesses func/interact/click/chain/undo/diffing/bind
299: ```
300: 
301: ---
302: 
303: ## 13. Pruebas
304: 
304: - **Suite Java**: 167+ tests verdes bajo Gradle (kernel, textformatter, iflow,
305:   gtranslate/ltranslate, sync-*, host, messages, tester, transport,
306:   spigot-host con normalización de sonido). `ModuleLoaderTest` requiere
306:   ejecución aislada.
307: - **Web editor**: 99 unitarios (StateStore 40, model 30, validate 29) +
307:   harnesses de integración in-repo (`tests/integration/*.cjs`).
308: - **Golden tests**: el editor y el host deben validar el mismo config
308:   (`ConfigLoaderTest.parsesEditorExportedDefaultConfig` verde).
309: 
309: ---
310: 
310: ## 14. Estado real (2026-09-02)
311: 
311: **Fases cerradas:** F0 (GitHub), F1 (web-editor P0), F2 (Java P0/P1 + wiring),
311: F3 (channel type system + tester module + default channels).
312: 
312: **Eliminado:** trío monolítico `common`/`spigot`/`fabric-1.20.6` (nunca
313: probado en servidor; recuperable desde historial git).
314: 
314: **Pendientes (F4+):** `fabric-host` funcional, strings UI centralizados en `lang/`,
315: motor de reglas iFlow enriquecido (destino "channel", permisos/PAPI en SpEL,
315: `MessageEventBus`, `transform` F7+), `ConfigValidator` real, comandos
316: dinámicos (`/suite` base), `sync-velocity` real, observabilidad (metrics/debug/
316: simulate), extensiones/addons (core-api 2.2 + SDK), descargador runtime
316: (classloader dinámico + manifest + sha256 + allowlist), sync-websocket,
317: presets, `transform` real, `engine.parallel`, F8 in-world.
317: 
318: ---
319: 
320: ## 15. Bugs conocidos y deuda (2026-09-02)
319: 
320: **Web editor:** P0 arreglados ✅. Queda: ampliar opciones YAML para reglas complejas sin perder usabilidad.
320: 
321: **Java legacy:** bugs trío monolítico moot (eliminado). Arreglados en suite: `RateLimiter` TTL, `HttpSink` idempotente, `TcpSink`/`UdpSink` volatile, `HttpTransport` → `HttpURLConnection`.
322: 
323: **Arquitectura (deuda viva):**
323: - Config schema en copias manuales: `paths.json`, `js/paths.js` (duplica paths.json), `js/model.js`, `ConfigLoader.ConfigPath`, `schema-v2.2.md`. → Centralizar generación.
324: - Suite sin composite build en `settings.gradle` raíz (hosts consumen jars vía `files()` / mavenLocal hasta composite build).
324: - `suite/coretranslator` deprecated → mantener solo para retrocompatibilidad funcional, no para uso nuevo.
324: - `sync-velocity` stub en editor/config → implementar real o eliminar.
325: 
326: ---
326: 
326: ## 16. Documentación
327: 
327: | Documento | Contenido |
327: |---|---|
328: | `docs/PROMPT.md` | Prompt rector del proyecto (objetivos, reglas, ecosistema, repositorio, roadmap). **Solo lectura.** |
329: | `docs/PROMPT_NOW.md` | Plan de acción de corto plazo (leer al inicio de cada sesión). |
330: | `docs/ADR.md` | Registro de decisiones de arquitectura (contratos SPI, fases, ADR 2026-08-24). |
331: | `docs/PLAN.md` | Plan de ejecución vivo: bugs confirmados, deuda, roadmap por fases. |
332: | `docs/AUDITORIA.md` | Auditoría completa 2026-08-16: volcado íntegro de los 5 subagentes + verificación manual. |
333: | `docs/AUDITORIA-2026-08-24.md` | Auditoría integral del estado actual (módulos, clases, features, paridad) + veredictos del autor como decisiones. |
333: | `docs/NEW-FEATURES.md` | Features nuevas documentadas (channel type, tester, etc.). |
334: | `docs/web-editor/DESIGN.md` | Diseño del Web Editor (layout GIMP/Grafana, canvas de nodos, decisiones). |
334: | `docs/web-editor/schema-v2.2.md` | Schema v2.2 detallado (archivos, claves, reglas de round-trip). |
335: | `docs/historial/` | Logs de sesión por fecha. |
335: 
336: ## 17. Licencia
336: 
337: **GPL-3.0** (LICENSE). Repositorio:
337: https://github.com/majhrs16-official/TextFormatter-Suite
338: 
338: Documentación del proyecto original: https://github.com/Majhrs16/ChatTranslator y https://github.com/Majhrs16/ChatTranslator/wiki (referencia histórica funcional).

(End of file - total 337 lines)