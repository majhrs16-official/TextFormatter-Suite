# Auditoría integral TextFormatter Suite — 2026-08-24

Presentación completa del estado del proyecto (construcción, decisiones,
módulos, clases, features, paridad) + **veredicto del autor** integrado como
decisiones vinculantes. Complementa a `AUDITORIA.md` (2026-08-16).

---

## PARTE I — Cómo está construido

Monorepo git (`main` → GitHub `majhrs16-official/TextFormatter-Suite`):

```
suite/               # EL producto: builds Gradle independientes (Java 17)
├── core-api/        # contrato SPI + modelo de dominio   (JDK-puro, cero deps)
├── kernel/          # descubrimiento y resolución de módulos (ServiceLoader+Tarjan)
├── textformatter/   # motor de formato MiniMessage + Channels
├── iflow/           # motor de routing/firewall
├── gtranslate/      # Google Translate vía endpoint gtx (scraping)
├── ltranslate/      # LibreTranslate API real
├── coretranslator/  # puente funcional del original (deprecated, NO borrar — ver Parte IV)
├── sync-discord|telegram|http|tcpudp/   # bordes de integración
├── host/            # composition root: SuiteHost + ConfigLoader + TranslatorsConfig
│                    #   + MessageDispatcher + puerto ChatDelivery
├── spigot-host/     # ADAPTER REAL: plugin Spigot instalable (TextFormatterSuite)
└── web-editor/      # editor visual estático (JS vanilla, GitHub Pages)
```

El trío monolítico `common/spigot/fabric-1.20.6` fue **eliminado** (2026-08-24):
monolíticos, nunca probados en servidor. Recuperable vía historial git.

## PARTE II — Decisiones de diseño rectoras

| # | Decisión | Por qué |
|---|---|---|
| 1 | Hexagonal estricta: dependencia solo hacia adentro | El núcleo compila sin Bukkit/Fabric |
| 2 | core-api JDK-puro (cero deps) verificable por grep | El contrato nunca arrastra Adventure/Bukkit |
| 3 | Puertos con Adventure viven en `host/port` (no core-api) | ChatDelivery lleva Component; pureza del contrato |
| 4 | Mensaje atómico con Direction-as-audience | Reemplaza el par from/to: eco y broadcast independientes |
| 5 | ServiceLoader/SPI + handshake doble (semver contrato + JVM) | Módulos descargables del Manager a futuro |
| 6 | Sin modloader ni ciclo de vida gestionado | El host es composition root explícito |
| 7 | E/S directa + timestamp check, sin watchers | Page Cache del SO; /suite reload relee todo |
| 8 | Retrocompatibilidad FUNCIONAL con ChatTranslator (comportamiento), no con código intermedio | Decisión del autor |

Grafo: `kernel/textformatter/iflow/gtranslate/ltranslate/sync-* → core-api`;
`iflow → textformatter`; `host → {core-api,textformatter,iflow,gtranslate,ltranslate}`;
hosts de plataforma → suite + un SDK. Acíclico.

## PARTE III — Clases y conexión (resumen por módulo)

**core-api**: modelo atómico (`Message` inmutable + Builder; `Actor` con
nativeHandle opaco; `Direction` 8 semánticas de audiencia; `Formats`
texto+MiniMessage paralelos; Language ~140 códigos) + SPI: `Translator`,
`TranslatorManager` (prioridad por inserción, FALLBACK "none" identidad),
`TranslationService` (síncrona, excepción→texto original), `SyncSink`/
`SyncListener`, `ActorDirectory`, `PlaceholderResolver`, `PluginLogger`,
SPI de módulos (`Module/ModuleDescriptor/SemVer/Capability/Requirement`).

**kernel**: `ModuleLoader.discover()` (ServiceLoader) + `ModuleGraph.resolve()`
(fixpoint; clasifica UNSATISFIED/CONTRACT_MISMATCH/JVM_MISMATCH/CYCLE-Tarjan).
Completo y testeado; sin invocación runtime todavía.

**textformatter**: `ChannelRegistry` índice por path punteado con fallback por
ancestros → canal sintético `chat`. `Channel` = formatos+tooltips+sounds+
idiomas+permisos base/send/receive+showSender+rateLimit. `TemplateRenderer`:
builtins → `<expr>` SpEL → placeholders externos → `%content%`/`%ct_messages%`
→ spans `<tr>` (traducible por receptor) → MiniMessage, con escape
anti-inyección (`MiniEscape`).

**iflow**: `DefaultRouter.route(message, recipient)`: selfEcho salta send-policy;
emisor sin permiso→REJECT; receptor sin permiso→DROP; primera `Rule` que
matchea decide (glob, priority); default-accept con token-bucket
`rateLimitPerSecond` por canal+emisor → RATE_LIMIT(backoff). Targets:
LOG/DROP/REJECT(marca conexión-perdida)/REDIRECT(consola)/RATE_LIMIT.

**host**: `SuiteHost.bootstrap()` arma router+formatter desde archivos;
`deliver(msg,recipient)` = idioma efectivo→route→render por idioma del
receptor. `MessageDispatcher.dispatch()`: expande Direction→receptores dedup
(8 semánticas), orquesta entrega/consola/silencio + sonidos gateados por
`sonido.enabled` y registro; devuelve DispatchReport. `ConfigLoader` +
`TranslatorsConfig` tolerantes a YAML corrupto (degradan). Puerto
`ChatDelivery` (Component) vive aquí, no en core-api.

**spigot-host** (`TextFormatterSuitePlugin`): listener chat LOWEST claim-first;
cancela vanilla y despacha eco (gated por show-sender) + broadcast como
unidades atómicas; canal MVP = primer permiso base que el jugador tiene.
`SpigotActorDirectory` (snapshot anti-CME, locale full-code→base),
`SpigotChatDelivery` (BukkitAudiences, hop main thread, normalización de
nombres de sonido). Defaults embebidos copiados si faltan. `/suite
reload|status`. Runtime swap atómico (record inmutable).

**sync-\*** (motor listo, **runtime sin cablear**): Discord gateway v10 propio
(hello→heartbeat→identify; texto plano; sin auto-reconexión), Telegram
long-poll manual watermark, HTTP webhook+inbound idempotente, TCP/UDP JSON
por línea/datagrama. Nadie llama send()/registra listeners fuera de tests.

**web-editor**: StateStore undo/redo + validadores rollback; round-trip YAML
exacto byte-idéntico; preview espejo del pipeline; canvas nodos iFlow/celdas
TXF; 99 unit + 7 integration harnesses in-repo; golden test contra el host.

Flujo end-to-end Spigot: AsyncChatEvent(LOWEST, async) → cancelar vanilla →
Actor(locale) → canal por permiso → dispatcher → route(iFlow) → render
(MiniMessage + traducción `<tr>` por idioma receptor vía google/libre/none)
→ hop main thread → BukkitAudiences + sonidos.

## PARTE IV — Veredicto del autor (decisiones vinculantes)

| Tema | Decisión |
|---|---|
| Persistencia de idioma (/cht lang, storage SQL) | **Paridad OBLIGATORIA.** Prioridad alta. |
| Eventos no-chat (join/quit/death) wired en spigot-host | **Importante, resolver.** Prioridad alta. |
| Cancel-vanilla vs clear-recipients | Debe ser **configurable** (el original tenía ambas estrategias). |
| Sinks sync wiring | Prioridad media/baja (diferido). |
| coretranslator | **NO eliminar.** Es retrocompatibilidad FUNCIONAL del original: traducir textos al vuelo vía PAPI (%cot_translate), capturar/modificar mensajes al vuelo vía API, inyectar lógica compleja vía SpEL. Deprecated = no recomendarlo ni depender de él para uso nuevo, pero es pieza útil y debe alcanzar esa paridad. |
| Kernel en runtime | Integrar SOLO si ofrece ventaja arquitectónica/funcional real (p.ej. descubrir translators/sinks para el Manager). Evaluar al cablear sync. |
| README §9 (bus público) | Reescribir: la API del trío eliminado ya no existe. |
| engine.parallel | Prioridad baja SIN comprometer arquitectura: mantener decisiones puras y despacho aislado para que el paralelismo sea un executor alrededor del loop. |
| JDA vs gateway propio | Propuesta del agente: JDA detrás del puerto SyncSink (implementación intercambiable, motor intacto); gateways propios como alternativa ligera opcional. Pendiente confirmación del autor (dependencia pesada requiere red). |
| Sonidos ".mp3" | Heurística actual documentada; refinamiento futuro: lookup adicional por NamespacedKey (registry moderno). |

## PARTE V — Paridad funcional vs ChatTranslator original

✅ hoy: google/libre, detección locale, tooltips+sounds por canal, iFlow
(supera al ConditionalEvents externo), sinks nuevos (tg/http/tcp/udp),
canales por suscripción (supera), editor visual (no existía).

❌ gap (ordenado por decisión del autor): persistencia idioma + `/suite lang`
(P0) · eventos no-chat wiring (P0) · claim-mode configurable cancel/clear-
recipients (P0) · PlaceholderResolver adapter PAPI (P1) · comandos completos
lang/toggle/reset/tell (P1) · self-traducción UI lang/ (P1) · colores por
permiso consumo (P1) · menciones @nick (P2) · signs (P2) · Discord paridad
completa con JDA (P2, tras red) · storage SQLite/MySQL (P2, tras YAML) ·
bStats/update-checker/rescue-mode/migradores (P3) · engine.parallel (P3).

🟡 diferencias de diseño aceptadas: MiniMessage moderno (se pierde 1.7–1.15
NMS), versiones MC modernas únicamente, KICK-spam → drop+backoff configurable.
