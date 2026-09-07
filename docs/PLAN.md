# PLAN — TextFormatter Suite

> Documento vivo: reléelo antes de cada sesión de trabajo para no perder el rumbo.
> Última actualización: 2026-08-31 (commit 4dc7375 - channel type system + tester module + default channels).

---

## Estado real del proyecto (2026-08-31)

- ✅ **Monorepo Git** en `/home/majhrs16/Documentos/textformatter-suite` (rama `main`, commit `4dc7375`, remoto GitHub `majhrs16-official/TextFormatter-Suite`).
- ✅ **Arquitectura**: `suite/*` módulos Gradle (Java 17/21) + adapters `spigot-host` (plugin Paper 1.20.6+) y `fabric-host` (WIP). `common-legacy` como referencia histórica.
- ✅ **Web editor** funcional (StateStore, diffing, validación incremental, paths.json) con 99 tests unitarios + 5 de integración (`npm run check` verde).
- ✅ **Suite corriendo en Spigot/Paper** (plugin `TextFormatterSuite` instalable, fat-jar construido, probado en servidor real Paper 1.20.6).
- ✅ **Módulos suite publicados en mavenLocal**: core-api, kernel, textformatter, iflow, gtranslate, ltranslate, sync-*, host, messages, tester, transport, coretranslator.
- ⚠️ **fabric-host** pendiente (WIP, Loom 1.6.12).
- ❌ **sync-velocity** no existe (stub en editor/config).

---

## RESUMEN DE LO HECHO (desde auditoría 2026-08-16)

### FASE 0 — SEGURIDAD Y GITHUB ✅
- Monorepo subido a GitHub (`majhrs16-official/TextFormatter-Suite`, GPL-3.0).
- `.git` completo, `.gradle` y `node_modules` ignorados correctamente.

### FASE 1 — P0 Web-editor ✅ (bugs confirmados arreglados)
- Mutaciones sobre clon descartado → arreglado (re-obtener `StateStore.getState()` DENTRO de `mutate`).
- Recursión infinita `core.js:82-84` → eliminada.
- Contrato preview (`res.output/error` vs `outputs/order/reason`) → alineado.
- `esc()`: apóstrofe dentro de la clase `[&<>"']` → arreglado + test.
- Mojibake UTF-8 → reescritos literales + lint/test anti-regresión.
- Drift config/graph (`config.iflow.*` vs `graph.*`) → unificado `model.js`.
- `toolbar.js:27` placeholder → handler real.
- `config.js:228` TypeError si no hay canales → arreglado.
- `console.log` residual → eliminado.
- `npm run check` + `test:integration` en verde.

### FASE 2 — P0/P1 Java ✅
- `SpigotScheduler.ticks()` → conversión MS→ticks correcta (redondeo, no truncar < 1s).
- `NmsLocaleBridge` → cache Class/Method/Field + log no silencioso.
- `RateLimiter` → purga TTL (5s cada 1024 adquisiciones).
- `HttpSink` → start/stop idempotente + campos volatile.
- `TcpSink`/`UdpSink` → campos volatile.
- **Wiring suite→plataforma** → `SpigotChatDelivery` (hop a main thread), `SuiteHost.bootstrap` con ServiceLoader, `MessageDispatcher` expande `Direction`→receptores, orquesta por-receptor.
- Dominio duplicado → `suite/core-api` adoptado como canónico; `common` → `common-legacy`; bridge `ChatRouter` eliminado.
- `HttpTransport` único en `suite/transport` (extraído de gtranslate/ltranslate/sync-*). `MessageCodec` único en `sync-http`/`sync-tcpudp`.
- Schema centralizado → `ConfigPath` enum en `ConfigLoader` genera `paths.json`.
- **Web-editor P0 arreglado y verificado** (`npm run check` + integración verde).

### FASE 3 — Configurabilidad / Paridad original ✅ (parcial)
- **Channel Type System** → `Channel.Type` enum (CHAT/EVENT). `ChannelSelector` filtra EVENT channels para chat.
- **Default channels** → `join.yml`, `quit.yml`, `death.yml`, `advancement.yml` con `type: EVENT`; placeholders `%player_name%` en lugar de `<sender>`.
- **Tester Module** → `suite/tester` con 25 tests runtime (routing, eventos, traducción, formato, iFlow, concurrencia, stress, profiling). `PerformanceProfiler` CPU/heap. Skip mechanism. Comandos `/suite test full|stress|concurrency`.
- **HttpTransport** → `HttpURLConnection` (no `java.net.http.HttpClient` por módulos en Paper).
- **Default channels creados** → join/quit/death/advancement con `type: EVENT` + `%player_name%`.
- **ChannelSelector filtra por tipo** → solo CHAT para chat de jugadores.
- **ConfigLoader lee `type`** → default CHAT.
- **Commands** → `/suite test full|stress|concurrency`, `/suite test full` corre 25 tests.
- Fix `langTarget` bug en Channel builder.
- `ConfigValidator` placeholder.
- `suite:messages` module para i18n centralizado (EN/ES).
- Fix `SpigotScheduler`, `NmsLocaleBridge`.
- Memory pressure test: 10MB en lugar de 1GB.
- Tests usan skip mechanism en lugar de throwing para jugadores insuficientes.

### FASE 4 — Roadmap largo (PENDIENTE)
- F0 restaurabilidad (suite.info + manifest + releases inmutables)
- F1 `fabric-host` real (Paper 1.20.6+ listo, Fabric pendiente)
- F2 freshness+i18n (strings UI centralizados en `lang/`)
- F3 observabilidad (metrics/debug/simulate)
- F4 extensiones/addons (core-api 2.2 + SDK)
- F5 **descargador runtime + attach/detach** (classloader dinámico + manifest + sha256 + allowlist)
- F6 sync-websocket/velocity real
- F7 presets, `transform` real en motor, `engine.parallel` knob
- F8 in-world (signos/cofres/libros, WORLD/RADIUS, caché+glosario, botones click/hover)
- Paridad funcional restante ChatTranslator original (comandos `/cht`, signs persistentes, storage SQLite/MySQL, Discord sync bidireccional completo).

---

## PRÓXIMAS ACCIONES CONCRETAS
1. **FASE 4** → `fabric-host` funcional (Paper ya listo).
2. **Strings UI centralizados** → mover strings hardcodeados a `lang/` (catalogos EN/ES) para recobrar 98%.
3. **Motor de reglas iFlow enriquecido** → destino "channel", permisos/PAPI en SpEL, `MessageEventBus` público, `transform` real (F7).
4. **ConfigValidator** real (issues con shape del editor).
5. **Comandos** → `/suite` base dinámico, reload atómico, edición estilo LuckPerms.
6. **sync-velocity** real (no stub).
7. **Observabilidad** → metrics/debug/simulate endpoints.

---

## BUGS CONOCIDOS Y DEUDA (actualizado 2026-08-31)

**Web editor:** P0 arreglados ✅. Queda: ampliar opciones YAML para reglas complejas sin perder usabilidad.

**Java legacy:** bugs trío monolítico moot (eliminado). Arreglados en suite: `RateLimiter` TTL, `HttpSink` idempotente, `TcpSink`/`UdpSink` volatile.

**Arquitectura (deuda viva):**
- Config schema en copias manuales: `paths.json`, `js/paths.js` (duplica paths.json), `js/model.js`, `ConfigLoader.ConfigPath`, `schema-v2.2.md`. → Centralizar generación.
- Suite sin composite build en `settings.gradle` raíz (hosts consumen jars vía `files()` / mavenLocal hasta composite build).
- `suite/coretranslator` deprecated → mantener solo para retrocompatibilidad funcional, no para uso nuevo.
- `sync-velocity` stub en editor/config → implementar real o eliminar.

---



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

---

## RESULTADOS FASE 2 — INVESTIGACIÓN (2026-09-06)

### A1 — Paridad Funcional ChatTranslator ✅
**Entregable:** `docs/historial/research-A1-paridad-chattranslator-2026-09-06.md`

**Resumen:** Paridad alta en core (chat translation, formatos, eventos join/quit/death/advancement, Discord sync, anti-spam, selección idioma, hot-reload).

**Gaps CRÍTICOS (bloqueantes migración producción):**
1. **Sign translation** (Shift+Click) — No hay listener Spigot ni persistencia `signs.yml`
2. **PAPI `%cot_*`** — Placeholders `cot_translate`, `cot_var`, `cot_broadcast`, `cot_lang`, `cot_sendDiscord` no existen (rompe ConditionalEvents/integraciones)
3. **Velocity/MySQL sync** — Solo stub en config; sin storage compartido para redes multi-servidor

**Gaps ALTOS:** MySQL storage, ConditionalEvents/MessageBus, Private messages, Connection loss indicator

**Ventajas Suite (nuevo):** iFlow rules engine, permisos asimétricos nativos send/receive, Telegram/HTTP/TCP-UDP sync, Web Editor round-trip YAML exacto, test runtime automatizado (`/suite test`), fallback multi-proveedor (Google→LibreTranslate), arquitectura hexagonal testeable.

---

### A2 — Arquitectura Hexagonal ✅
**Entregable:** `docs/historial/research-A2-hexagonal-2026-09-06.md`

**Cumplimiento:** 7/8 reglas ✅, 1 deuda menor (V1)

| Regla | Estado | Evidencia |
|-------|--------|-----------|
| 1. core-api JDK-puro | ✅ | Solo test deps JUnit |
| 2. Puertos separados | ✅ | `ActorDirectory` en `core-api/spi`; `ChatDelivery` en `host/port` (Adventure) |
| 3. Adapters sin imports cruzados | ✅ | Verificado por grep |
| 4. SPI = ServiceLoader | ✅ | `META-INF/services/me.majhrs16.suite.api.Module` (10 módulos) |
| 5. Handshake doble | ✅ | `Environment.current()` (JVM) + `ModuleDescriptor.contractVersion()` (semver) |
| 6. Mismatch → degrade/no crash | ✅ | `ResolutionStatus` no fatales |
| 7. Descubrimiento vía SPI | ✅ | `ModuleLoader.discover(ServiceLoader)` |
| 8. Web Editor desacoplado | ✅ | YAML/schema v2.2, parser JS propio, round-trip validado |

**Violación menor (V1):** `suite/tester/build.gradle:32` — `implementation 'org.spigotmc:spigot-api'` contamina classpath. Fix: cambiar a `compileOnly`.

---

### A3 — Clean Architecture ✅
**Entregable:** `docs/historial/research-A3-clean-arch-2026-09-06.md`

**Compliance:** ~85%

**Violaciones CRÍTICAS (Inversión de dependencias en `host`):**

| # | Archivo:Línea | Issue |
|---|---------------|-------|
| V1 | `host/config/TranslatorsConfig.java:82` | Host instancia directamente `GTranslate` (adapter) |
| V2 | `host/config/TranslatorsConfig.java:83-87` | Host instancia directamente `LTranslate` (adapter) |
| V3 | `host/build.gradle:24-25` | Host tiene `implementation` deps en `gtranslate`, `ltranslate` |

**Root cause:** `TranslatorsConfig` (capa caso de uso) tiene dependencias compile-time a implementaciones de adaptadores en vez de usar puerto `Translator` vía ServiceLoader/factory.

**Fix P0:** Introducir `TranslatorProvider` SPI + ServiceLoader discovery, remover deps `implementation` de host.

---

### A4 — Bugs & Vulnerabilidades Código ✅
**Entregable:** `docs/historial/research-A4-bugs-vulns-2026-09-06.md`

**30 hallazgos totales:** 1 Crítico, 7 Alto, 11 Medio, 11 Bajo

**Crítico (P0 — Fix inmediato):**
- **INJ-3 (CWE-94):** `ExpressionEvaluator` SPI sin sandbox por defecto → **RCE vía SpEL** si host usa `StandardEvaluationContext`
- **INJ-4 (CWE-502):** 4 loaders YAML usan `new Yaml()` (unsafe constructor) → **deserialización arbitraria** si atacante escribe en config files

**Alto (P1-P3):**
- INJ-1: `MiniEscape` solo escapa `<` y `\` — faltan `>`, `{`, `}`, `[`, `]`, `(`, `)`, `#`, `@`
- SEC-1..4: Tokens Discord, Telegram, LibreTranslate en `String` permanente en heap
- DOS-1: `HttpServer` executor unbounded → thread exhaustion
- DOS-2: `MessageDispatcher` secuencial en async chat event → lag servidor 200+ jugadores
- DOS-3: `RateLimiter` capacity hardcodeado a `1` (ignora `channel.rateLimitPerSecond()`)

**Plan de fixes (4 sprints):**
- Sprint 1: SpEL sandbox + SafeConstructor YAML + MiniEscape completo + char[] tokens
- Sprint 2: Bounded executors, dispatcher paralelo, RateLimiter fix, cache eviction
- Sprint 3: Race conditions, socket timeouts, executor reuse
- Sprint 4: Template validation, env vars, weak refs, PAPI dynamic check

---

### A5 — Vulnerabilidades Supply-Chain/Runtime ✅
**Entregable:** `docs/historial/research-A5-supply-chain-2026-09-06.md`

**Riesgo supply-chain: ALTO**

**Hallazgos clave:**
1. **SBOM top 20 deps** — JDA 5.0.0 (CVE-2023-2603, CVE-2022-23611), SnakeYAML 2.2 (CVE-2022-1471, CVE-2022-38751), Adventure 4.15.0 (sin CVEs públicos), Fabric Loom 1.6.12 (sin CVEs), Spigot/Paper API (provided)
2. **gradle.lockfile / SHA256** — **AMBOS AUSENTES**; sin `dependencyVerification` configurado; builds no reproducibles
3. **Repositorios Maven (8)** — Central, Plugin Portal, FabricMC, Spigot, PaperMC, HelpChat, Minecraft Libraries, mavenLocal(); riesgo: `mavenLocal()` con precedencia sobre remotos
4. **Reproducible builds** — NO (falta lockfile, verification metadata, versiones release en jars, determinismo timestamps)
5. **Manifest validation en ModuleLoader** — NO; solo ServiceLoader discovery, validación semántica posterior en ModuleGraph (contract/JVM/ciclos)
6. **Allowlist módulos** — NO; carga todo del classpath, solo rechazo post-resolución
7. **Prep classloader dinámico (F5)** — Kernel listo (ModuleLoader paramétrico, ModuleDescriptor serializable), gaps: provisioning seguro, verificación SHA256, aislamiento classloader, allowlist enforcement
8. **Checklist release (15 items P0/P2)** — P0: gradle.lockfile + SHA256 + dependencyVerification; P1: allowlist + manifest validation pre-load; P2: reproducible builds + CI/CD gate

**Recomendación inmediata:** Configurar `dependencyVerification` en `settings.gradle` con claves SHA256 + generar `gradle.lockfile` antes de cualquier release.






