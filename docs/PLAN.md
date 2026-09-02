1: # PLAN — TextFormatter Suite
2: 
3: > Documento vivo: reléelo antes de cada sesión de trabajo para no perder el rumbo.
4: > Última actualización: 2026-08-31 (commit 4dc7375 - channel type system + tester module + default channels).
6: 
7: ---
8: 
9: ## Estado real del proyecto (2026-08-31)
9: 
10: - ✅ **Monorepo Git** en `/home/majhrs16/Documentos/textformatter-suite` (rama `main`, commit `4dc7375`, remoto GitHub `majhrs16-official/TextFormatter-Suite`).
10: - ✅ **Arquitectura**: `suite/*` módulos Gradle (Java 17/21) + adapters `spigot-host` (plugin Paper 1.20.6+) y `fabric-host` (WIP). `common-legacy` como referencia histórica.
11: - ✅ **Web editor** funcional (StateStore, diffing, validación incremental, paths.json) con 99 tests unitarios + 5 de integración (`npm run check` verde).
11: - ✅ **Suite corriendo en Spigot/Paper** (plugin `TextFormatterSuite` instalable, fat-jar construido, probado en servidor real Paper 1.20.6).
12: - ✅ **Módulos suite publicados en mavenLocal**: core-api, kernel, textformatter, iflow, gtranslate, ltranslate, sync-*, host, messages, tester, transport, coretranslator.
13: - ⚠️ **fabric-host** pendiente (WIP, Loom 1.6.12).
13: - ❌ **sync-velocity** no existe (stub en editor/config).
14: 
15: ---
16: 
17: ## RESUMEN DE LO HECHO (desde auditoría 2026-08-16)
18: 
19: ### FASE 0 — SEGURIDAD Y GITHUB ✅
19: - Monorepo subido a GitHub (`majhrs16-official/TextFormatter-Suite`, GPL-3.0).
20: - `.git` completo, `.gradle` y `node_modules` ignorados correctamente.
20: 
21: ### FASE 1 — P0 Web-editor ✅ (bugs confirmados arreglados)
21: - Mutaciones sobre clon descartado → arreglado (re-obtener `StateStore.getState()` DENTRO de `mutate`).
22: - Recursión infinita `core.js:82-84` → eliminada.
23: - Contrato preview (`res.output/error` vs `outputs/order/reason`) → alineado.
23: - `esc()`: apóstrofe dentro de la clase `[&<>"']` → arreglado + test.
24: - Mojibake UTF-8 → reescritos literales + lint/test anti-regresión.
24: - Drift config/graph (`config.iflow.*` vs `graph.*`) → unificado `model.js`.
25: - `toolbar.js:27` placeholder → handler real.
26: - `config.js:228` TypeError si no hay canales → arreglado.
27: - `console.log` residual → eliminado.
27: - `npm run check` + `test:integration` en verde.
27: 
28: ### FASE 2 — P0/P1 Java ✅
28: - `SpigotScheduler.ticks()` → conversión MS→ticks correcta (redondeo, no truncar < 1s).
29: - `NmsLocaleBridge` → cache Class/Method/Field + log no silencioso.
30: - `RateLimiter` → purga TTL (5s cada 1024 adquisiciones).
31: - `HttpSink` → start/stop idempotente + campos volatile.
32: - `TcpSink`/`UdpSink` → campos volatile.
33: - **Wiring suite→plataforma** → `SpigotChatDelivery` (hop a main thread), `SuiteHost.bootstrap` con ServiceLoader, `MessageDispatcher` expande `Direction`→receptores, orquesta por-receptor.
33: - Dominio duplicado → `suite/core-api` adoptado como canónico; `common` → `common-legacy`; bridge `ChatRouter` eliminado.
34: - `HttpTransport` único en `suite/transport` (extraído de gtranslate/ltranslate/sync-*). `MessageCodec` único en `sync-http`/`sync-tcpudp`.
34: - Schema centralizado → `ConfigPath` enum en `ConfigLoader` genera `paths.json`.
35: - **Web-editor P0 arreglado y verificado** (`npm run check` + integración verde).
35: 
36: ### FASE 3 — Configurabilidad / Paridad original ✅ (parcial)
36: - **Channel Type System** → `Channel.Type` enum (CHAT/EVENT). `ChannelSelector` filtra EVENT channels para chat.
37: - **Default channels** → `join.yml`, `quit.yml`, `death.yml`, `advancement.yml` con `type: EVENT`; placeholders `%player_name%` en lugar de `<sender>`.
37: - **Tester Module** → `suite/tester` con 25 tests runtime (routing, eventos, traducción, formato, iFlow, concurrencia, stress, profiling). `PerformanceProfiler` CPU/heap. Skip mechanism. Comandos `/suite test full|stress|concurrency`.
37: - **HttpTransport** → `HttpURLConnection` (no `java.net.http.HttpClient` por módulos en Paper).
38: - **Default channels creados** → join/quit/death/advancement con `type: EVENT` + `%player_name%`.
38: - **ChannelSelector filtra por tipo** → solo CHAT para chat de jugadores.
38: - **ConfigLoader lee `type`** → default CHAT.
39: - **Commands** → `/suite test full|stress|concurrency`, `/suite test full` corre 25 tests.
39: - Fix `langTarget` bug en Channel builder.
39: - `ConfigValidator` placeholder.
39: - `suite:messages` module para i18n centralizado (EN/ES).
40: - Fix `SpigotScheduler`, `NmsLocaleBridge`.
40: - Memory pressure test: 10MB en lugar de 1GB.
40: - Tests usan skip mechanism en lugar de throwing para jugadores insuficientes.
40: 
41: ### FASE 4 — Roadmap largo (PENDIENTE)
41: - F0 restaurabilidad (suite.info + manifest + releases inmutables)
42: - F1 `fabric-host` real (Paper 1.20.6+ listo, Fabric pendiente)
42: - F2 freshness+i18n (strings UI centralizados en `lang/`)
43: - F3 observabilidad (metrics/debug/simulate)
44: - F4 extensiones/addons (core-api 2.2 + SDK)
44: - F5 **descargador runtime + attach/detach** (classloader dinámico + manifest + sha256 + allowlist)
45: - F6 sync-websocket/velocity real
45: - F7 presets, `transform` real en motor, `engine.parallel` knob
46: - F8 in-world (signos/cofres/libros, WORLD/RADIUS, caché+glosario, botones click/hover)
46: - Paridad funcional restante ChatTranslator original (comandos `/cht`, signs persistentes, storage SQLite/MySQL, Discord sync bidireccional completo).
47: 
48: ---
49: 
50: ## PRÓXIMAS ACCIONES CONCRETAS
51: 1. **FASE 4** → `fabric-host` funcional (Paper ya listo).
52: 2. **Strings UI centralizados** → mover strings hardcodeados a `lang/` (catalogos EN/ES) para recobrar 98%.
53: 3. **Motor de reglas iFlow enriquecido** → destino "channel", permisos/PAPI en SpEL, `MessageEventBus` público, `transform` real (F7).
54: 4. **ConfigValidator** real (issues con shape del editor).
55: 5. **Comandos** → `/suite` base dinámico, reload atómico, edición estilo LuckPerms.
56: 6. **sync-velocity** real (no stub).
57: 7. **Observabilidad** → metrics/debug/simulate endpoints.
57: 
58: ---
59: 
59: ## BUGS CONOCIDOS Y DEUDA (actualizado 2026-08-31)
60: 
60: **Web editor:** P0 arreglados ✅. Queda: ampliar opciones YAML para reglas complejas sin perder usabilidad.
61: 
62: **Java legacy:** bugs trío monolítico moot (eliminado). Arreglados en suite: `RateLimiter` TTL, `HttpSink` idempotente, `TcpSink`/`UdpSink` volatile.
63: 
64: **Arquitectura (deuda viva):**
64: - Config schema en copias manuales: `paths.json`, `js/paths.js` (duplica paths.json), `js/model.js`, `ConfigLoader.ConfigPath`, `schema-v2.2.md`. → Centralizar generación.
65: - Suite sin composite build en `settings.gradle` raíz (hosts consumen jars vía `files()` / mavenLocal hasta composite build).
66: - `suite/coretranslator` deprecated → mantener solo para retrocompatibilidad funcional, no para uso nuevo.
66: - `sync-velocity` stub en editor/config → implementar real o eliminar.
67: 
67: ---
68: 
68: 

## FASE 2 — PLANIFICACIÓN (Documentar ahora, NO ejecutar)

### 1. Testing Exhaustivo — Diseño de Tests por Sección

Objetivo: Cubrir cada módulo/sección del proyecto con tests que definan claramente entradas y salidas esperadas.

| Sección / Módulo | Tests a Diseñar | Entradas | Salidas Esperadas |
|---|---|---|---|
| **core-api** (SPI + Modelo) | - Module/ModuleDescriptor registration<br>- Semver parsing & compatibility<br>- Message/Builder inmutabilidad<br>- Direction expansion (8 kinds)<br>- Actor equality & serialization | SPI configs, Message builders, Direction enums | Valid ModuleDescriptors, correct semver ranges, immutable Messages, expanded receiver lists |
| **kernel** (ModuleLoader + Graph) | - Tarjan cycle detection<br>- CONTRACT_MISMATCH / JVM_MISMATCH handling<br>- Environment bootstrap order<br>- ServiceLoader discovery | Module JARs with varying semver/JVM reqs | Correct load order, proper mismatch degradation, no cycles |
| **textformatter** (MiniMessage) | - TemplateRenderer with `<tr>` tags<br>- MiniEscape injection safety<br>- PlaceholderResolver (PAPI/identity)<br>- ChannelRegistry load/save<br>- Format groups per event type | MiniMessage templates, raw text, PAPI placeholders | Escaped output, translated segments, correct channel formats |
| **iflow** (Router + Rules) | - DefaultRouter BFS priority<br>- RateLimiter token bucket<br>- PermissionChecker (base + send/receive)<br>- Rule SpEL conditions/actions<br>- Graph cycles with max-steps guard | Rules YAML, message streams, permission maps | Correct routing decisions, rate-limited drops, permission-gated delivery |
| **gtranslate / ltranslate** | - Provider selection & fallback<br>- HttpTransport pool & rate-limit<br>- Translation cache TTL<br>- Error handling (429, 5xx, timeout) | Text batches, provider configs, mock HTTP | Translated text, proper fallback, metrics recorded |
| **sync-* (discord/telegram/http/tcpudp)** | - Sink start/stop idempotency<br>- MessageCodec encode/decode<br>- Discord gateway reconnect<br>- Telegram watermark offset<br>- TCP/UDP raw framing | Events, config YAML, mock sockets | Delivered payloads, acknowledged receipts, no leaks |
| **host** (SuiteHost + Dispatcher) | - ConfigLoader round-trip<br>- MessageDispatcher Direction→receivers<br>- ChatDelivery port contract<br>- ActorDirectory snapshot anti-CME<br>- Reload atomicity | Config files, Actor sets, Direction enums | Parsed config, delivered messages, thread-safe snapshots |
| **spigot-host** (Plugin) | - AsyncPlayerChatEvent claim modes<br>- Join/Quit/Death channel dispatch<br>- `/suite` command tree<br>- SpigotScheduler ticks conversion<br>- NmsLocaleBridge cache | Bukkit events, commands, player locales | Cancelled/cleared vanilla, dispatched Messages, correct tick math |
| **web-editor** (JS) | - StateStore undo/redo/diffing<br>- YAML writer/parser round-trip<br>- Validation incremental (revision)<br>- Canvas node graph (mux/fan-out)<br>- Preview pipeline (JS port) | User actions, YAML configs, graph edits | Consistent state, byte-identical YAML, valid issues list |

**Criterios de aceptación por test:**
- Given/When/Then explícito
- Inputs: fixtures YAML/JSON + mock objects
- Outputs: assertions sobre estado, side-effects, métricas
- Determinísticos (sin flakiness), paralelos, `--offline` compatibles

---

### 2. Sub-agentes de Investigación (5 agentes paralelos)

| Agente | Foco | Entregable | Fuentes |
|---|---|---|---|
| **A1 — Paridad Funcional ChatTranslator** | Comparar comportamiento runtime (comandos, canales, traducción, sync, storage) vs proyecto original ChatTranslator + wiki. Identificar gaps de paridad funcional (no código). | Matriz de paridad (feature → ✅/⚠️/❌ + notas), lista de gaps priorizados | `/home/majhrs16/Documentos/chattranslator`, `chattranslator.wiki`, GitHub `Majhrs16/ChatTranslator`, issues cerrados |
| **A2 — Arquitectura Hexagonal** | Validar cumplimiento estricto: core-api JDK-puro, puertos en `host/port/`, adapters sin dependencias cruzadas, SPI ServiceLoader, handshake doble, sin modloader. | Informe de conformidad (regla → ✅/❌ + evidencia), deuda arquitectural cuantificada | `suite/core-api`, `suite/host`, `suite/spigot-host`, `suite/fabric-host`, ADR.md |
| **A3 — Clean Architecture** | Verificar capas: Entidades (core-api/model) → Casos de uso (host/kernel) → Adaptadores (spigot-host, sync-*) → Frameworks (Bukkit, JDA, Loom). Inversión de dependencias, testabilidad. | Diagrama de capas + violaciones, recomendaciones de refactor | Código fuente suite/*, build.gradle, settings.gradle |
| **A4 — Bugs & Vulnerabilidades (Código)** | Análisis estático + revisión manual: race conditions, memory leaks, injection (MiniMessage, SpEL, YAML), DoS (rate-limit bypass, unbounded queues), secret handling (tokens en config/logs). | Lista de hallazgos (CWE, severidad, ubicación, PoC sugerido), fixes propuestos | `suite/*/src/main`, dependencias (Adventure, JDA, snakeyaml), `build.gradle` |
| **A5 — Vulnerabilidades (Cadena de Suministro / Runtime)** | Dependency check (OWASP), Gradle lockfiles, SHA256 de jars publicados, manifest validation, classloader isolation (runtime downloader futuro), allowlist enforcement. | SBOM, hallazgos CVE, plan de mitigación, checklist de release | `gradle.lockfile`, `mavenLocal`, `suite/*/build.gradle`, GitHub Actions (si existen) |

**Modo de operación:**
- Cada agente trabaja en paralelo, lectura sola (no modifica código)
- Reportes en `docs/historial/research-<agente>-<fecha>.md`
- Sesión de consolidación tras finalización → actualiza `PLAN.md` y `ADR.md` con decisiones

---

## INTEGRACIÓN DE NEW-FEATURES.md (consolidado 2026-09-02)

### ✅ IMPLEMENTADO (commit 4dc7375 - FASE 3)

| Feature | Detalles | Módulos afectados |
|---|---|---|
| **Channel Type System** | `Channel.Type` enum (CHAT/EVENT), `ChannelSelector` filtra por tipo, default channels join/quit/death/advancement con `type: EVENT` | `textformatter`, `spigot-host` |
| **Tester Module** | 25 tests runtime (routing, eventos, traducción, formato, iFlow, concurrencia, stress, profiling), `PerformanceProfiler` CPU/heap, skip mechanism, comandos `/suite test full|stress|concurrency` | `suite/tester` |
| **HttpTransport → HttpURLConnection** | Migración desde `java.net.http.HttpClient` (problemas módulos Paper), usado por GTranslate, LTranslate, sync-http, sync-telegram, sync-discord | `transport`, `gtranslate`, `ltranslate`, `sync-*` |
| **ConfigLoader type field** | Lee `type` (CHAT|EVENT) de `channels/*.yml`, default CHAT, `ConfigPath.CHANNEL_TYPE` en enum centralizado | `host`, `spigot-host` |
| **Messages Module** | i18n centralizado EN/ES, `MessagesCatalog` singleton, reemplaza strings hardcodeados | `suite/messages` |
| **Fixes bugs heredados** | `SpigotScheduler.ticks()` redondeo, `NmsLocaleBridge` cache, `RateLimiter` TTL, `HttpSink` idempotente, `TcpSink`/`UdpSink` volatile, memory pressure 10MB | `spigot-host`, `sync-*` |

### 🔄 EN PROGRESO / PRÓXIMOS (FASE 4+)

| Feature | Estado | Detalles |
|---|---|---|
| **fabric-host funcional** | ⏳ | Paper 1.20.6+ listo y probado; Fabric pendiente (Loom 1.6.12) |
| **Strings UI centralizados (i18n)** | ⏳ | Mover strings hardcodeados a `lang/` (catálogos EN/ES), recobrar 98% strings en config |
| **Motor de reglas iFlow enriquecido** | ⏳ | Destino "channel" en reglas, permisos/PAPI en SpEL, `MessageEventBus` público, `transform` real (F7+) |
| **ConfigValidator real** | ⏳ | Validación estructural contra schema editor, issues shape en consola |
| **Sistema comandos dinámico (`/suite`)** | ⏳ | Topología desde `commands.yml` v2, acciones atómicas combinables, feedback motor chat, edición config.yml estilo LuckPerms |
| **sync-velocity real** | ⏳ | Implementar o eliminar stub en editor/config |
| **Observabilidad** | ⏳ | Metrics endpoint Prometheus, debug endpoints `/debug/simulate` `/debug/dump`, healthchecks sinks |

### 📋 BACKLOG ARQUITECTURAL (brainstorming histórico)

| Área | Ideas clave |
|---|---|
| **Translation** | Text discovery, traducción estructural (preservar Component), Universal Text Pipeline DETECT→PARSE→TRANSFORM→TRANSLATE→FORMAT→SYNC→OUTPUT |
| **Formatting** | Arsenal transformaciones (uppercase, rot13, base64, leetspeak), Unicode processing (NFC/NFD/NFKC/NFKD, grapheme segmentation), visual width (emojis, CJK), parser/serializer universal (Plain↔MiniMessage↔Adventure↔ANSI↔Markdown↔HTML↔JSON↔YAML) |
| **Sync** | Message synchronization fabric, bridge declarativo YAML con loop detection, delivery guarantees (at-most-once, at-least-once), ordering guarantees, UDP capabilities, WebSocket subscriptions |
| **Web Editor** | YAML como lenguaje (variables, scopes, funciones, macros, imports, tipos, namespaces), "Go to assembly", compiler diagnostics tipados, formatter optimizer, source maps |

**Separación de áreas (Clean Architecture):**
| Área | Pregunta que responde |
|---|---|
| **Translation** | ¿Qué debe decir el texto? |
| **Formatting** | ¿Cómo manipulo/represento ese texto? |
| **Sync** | ¿A dónde viaja y cómo llega? |
| **Web Editor** | ¿Cómo describo todo sin perder control de bajo nivel? |

*Ninguna área depende conceptualmente de Minecraft. Minecraft es un consumidor más.*

> Ver `README.md` para estado detallado, arquitectura, módulos y build. Ver `docs/ADR.md` para decisiones de arquitectura. Ver `docs/PROMPT_NOW.md` para plan de acción de corto plazo. Ver `docs/NEW-FEATURES.md` para brainstorming histórico completo.

(End of file - total 104 lines)

