# PROMPT_NOW.md — Plan de Acción Corto Plazo (TEMPORAL)
> Leer cada sesión. Borrar cuando se complete FASE 2 completa.

---

## ESTADO ACTUAL (2026-08-24, sesión actual)

- ✅ Entorno COMPLETO: npm/nvm instalado por el usuario + caché Gradle copiada
  (wrapper 8.13 con `.ok`; builds corren con `--offline`, descargas ~0).
- ✅ **FASE 1 verificada con tests reales**: `npm run check` EXIT=0
  (format+lint+99 unit).
- ✅ **Harnesses de integración RECUPERADOS** desde `/tmp` del sistema viejo
  (`/media/majhrs16/rootantiX23/tmp/opencode/`) → ahora viven en el repo en
  `tests/integration/*.cjs`. `npm run test:integration` EXIT=0 (7 harnesses).
- ✅ Java validado con Gradle real (--offline): core-api/kernel/textformatter/
  iflow/gtranslate/ltranslate publicados a mavenLocal; test OK en TODOS los
  módulos incl. host (25) y sync-*/coretranslator.
- ❌ Suite NO corre aún en plataforma (gap #1): siguiente paso items 5-6.

### BUGS REALES DEL EDITOR encontrados hoy (y arreglados)
| Bug | Archivo | Fix |
|---|---|---|
| Boot crashea: `Suite.paths` undefined | `index.html` no cargaba `paths.js` | `<script>` añadido antes de config.js |
| Boot crashea: `KIND_H` undefined | `canvas.js` no lo exportaba; `core.js:110` lo lee | exportado en Object.assign |
| Click en nodo/canal crasheaba | `props.js` usa 5 wrappers inexistentes en HTML | envueltos `pfKindWrap/pfSoundsWrap/pfLangWrap/pfRateWrap/pfSenderWrap` |
| Switches duplicados (18→11): doble toggle neto-cero | `renderSwitches()` volcaba TODAS las rutas en Config | scope a `config.*`/`graph.*` |
| Switches estáticos sin listener fuera de Config | binding solo `#configSection` | delegación global en `document` (+guard `_switchDelegateBound`) |
| Clase `on` hardcodeada ≠ default del modelo | discord.enabled y velocity.enabled estáticos | quitado `on` del HTML; `renderSwitches` sincroniza estáticos |
| Tests con ruta absoluta vieja (`Default Project`) | unit + integration | `path.join(__dirname,'..','..')` |
| `.js` ESM vs CJS, falta `require('path')`, lista módulos stale (`app.js`), falta `StateStore.init` | integration harnesses | renombrados `.cjs`, imports/listas/init corregidos |
| `ConfigLoaderTest` dependía del CWD | suite/host test | fixture vía classpath resource |

> Nota: los crashes de boot implican que **el editor publicado no funcionaba**
> tras el refactor modular — cubrir boot con un harness permanente evita
> regresiones (click.cjs ya bootea la app completa).

## HALLAZGOS CLAVE DE MAPEO (sub-agentes 2026-08-24)

**suite/**: `SuiteHost.bootstrap(configDir, PermissionChecker, TranslationService, logger)`
ensambla router+formatter+config y expone `deliver(Message, Actor) → RoutingResult`
(por receptor individual). `ModuleLoader.discover()` (ServiceLoader) + `ModuleGraph.resolve()`
existen y están testeados pero NADIE los invoca en runtime. NO existe:
port de entrega a jugadores, port directorio de jugadores, expansión Direction→receptores,
carga de translators/*.yml (HostConfig solo tiene quick-look/language/parallel/sound).
core-api es JDK-puro (cero deps) — invariant documentado en README §1.

**common/adapters**: `ChatTranslatorApp.builder()` recibe 7 puertos; `ChatRouter.dispatch`
hace bus→rules→DefaultDirectionResolver→render→scheduler.runOnMainThread(display.send).
Spigot envía vía BukkitAudiences; Fabric vía GSON→Text. Los puertos comunes ya son
Adventure-native.

## DECISIONES DE DISEÑO (respetar)

1. **core-api sigue JDK-puro**. Se añadió `api/spi/ActorDirectory` (directorio
   de jugadores para expandir Direction). La migración de `Router`/
   `RouteDecision`/`PolicyTarget`/`PermissionChecker` a core-api queda
   APLAZADA a FASE 3 — ver ADR "Decisión 2026-08-24" por el razonamiento.
2. **`ChatDelivery` vive en `host/port/`** (NO core-api): su firma lleva
   Adventure `Component`. Documentado como desviación consciente en ADR.
3. **`MessageDispatcher` en host**: expansión Direction→Actors (8 semánticas),
   dedup, orquesta deliver→ChatDelivery, sonidos con gate hasSound,
   DispatchReport. Síncrono y thread-agnóstico; el adapter decide el hilo.
4. **spigot-host/fabric-host**: siguiente sesión (~4 clases por adapter).

---

## FASE 2 — WIRING SUITE → PLATAFORMA (en curso)

| # | Tarea | Estado |
|---|-------|--------|
| 1 | Puertos JDK-puros → `core-api/spi/`: **`ActorDirectory`** (nuevo; Router family APLAZADA a FASE 3, ver ADR) | ✅ 2026-08-24 |
| 2 | `host/port/ChatDelivery` + `host/MessageDispatcher` + `DispatchReport` + 15 tests | ✅ 2026-08-24 (auditoría calidad: bug P1 `sonido.enabled` no gateado → corregido + tests WORLD/console-audit/sound-off) |
| 3 | Tests suite verdes SIN Gradle: harness manual javac+JUnit (`/tmp/opencode/tfsuite/run-tests.sh`) → **138 tests OK** en todo el monorepo suite | ✅ 2026-08-24 |
| 4 | ADR: decisión puertos de entrega + aplazamiento migración Router family. Arquitectura auditada: **8.5/10** sin bloqueos | ✅ 2026-08-24 |
| 4b | Fix calidad test: `ConfigLoaderTest.parsesEditorExportedDefaultConfig` ya no depende del CWD (classpath resource) | ✅ 2026-08-24 |
| 5 | Módulos `spigot-host`/`fabric-host` | ✅ **spigot-host HECHO + auditado** (2026-08-24): plugin `TextFormatterSuite` con `SpigotActorDirectory` (locale full-code→base, snapshot defensivo anti-CME async), `SpigotChatDelivery` (BukkitAudiences + hop main thread + normalización de sonido), chat en `EventPriority.LOWEST` (claim-first como el legacy), eco gateado por `show-sender`, `/suite reload|status`, runtime swap atómico (record inmutable). Auditoría sub-agente: 0×P1; P2/P3 corregidos (showSender muerto, YAMLException fatal → degradación, CME, prioridad evento, locale zh, regex-injection en format, `options.release=17`). 4 tests sonido verdes. **Pendiente**: fat-jar (shadow requiere red), prueba en servidor real. `fabric-host`: ⏳ tras validar ruta Spigot. |
| 5b | Carga `translators/*.yml` → `TranslatorManager` | ✅ `host/config/TranslatorsConfig` (+5 tests): solo activos, google antes que libre, libre exige base-url, YAML corrupto se salta sin tumbar el resto; `TranslationService.activeName()` añadido a core-api; `ConfigLoader` también degrada ante YAML roto (antes: crash del bootstrap). Publicado a mavenLocal. |
| 6 | Migrar listeners adapters common→suite | ⏳ coexistencia: el jar legacy sigue funcionando; migrar cuando se pruebe spigot-host en servidor |
| 7 | Carga `translators/*.yml` | ✅ (ver 5b) |

**Nota entorno**: TODO disponible ahora (node 24 + npm via nvm; Gradle 8.13 con
caché completa copiada). Usar `--offline` en Gradle para evitar descargas
(red ~10KB/s). El harness manual de `/tmp/opencode/tfsuite/` queda como
respaldo; la validación canónica es Gradle + npm.

## FASE 2B — PARIDAD OBLIGATORIA (veredicto autor 2026-08-24, ver AUDITORIA-2026-08-24.md)

| # | Tarea | Prioridad | Notas de diseño |
|---|-------|-----------|-----------------|
| A1 | **Persistencia de idioma** + `/suite lang [jugador] <code\|auto\|off>` | ✅ 2026-08-25 | Port `UserLanguageStore` (core-api/spi) + `YamlUserLanguageStore` (host; caché RAM + hot-reload por lastModified, write-through atómico). spigot-host: store→locale→null; `off`→Language.AUTO = sin traducir (maquinaria existente); `/suite lang` propio gratis, ajeno admin. |
| A2 | **Eventos no-chat en spigot-host**: join/quit/death (+advancement ⏳) | ✅ 2026-08-25 (advancement pendiente) | Canales convencionales `join`/`quit`/`death`; presencia = activado; una unidad Direction.all(); MONITOR priority; %content% = mensaje vanilla de muerte / nombre. Espejo consola vía reglas REDIRECT del usuario. |
| A3 | **Claim-mode configurable**: `cancel-event` \| `clear-recipients` | ✅ 2026-08-25 | `chat.claim-mode` en config.yml → HostConfig.ClaimMode (tolerante a inválidos); defaults embebido actualizado; editor: select cfgClaimMode + default en model.js (round-trip incluido); fixture golden del host actualizado. Nota: clear-recipients deja el log vanilla en consola (duplicado potencial con REDIRECT) — documentado. |
| A4 | PlaceholderResolver adapter PAPI en spigot-host | ✅ 2026-08-25 | `SpigotPlaceholderResolver` (hook aislado, carga perezosa segura con dep compileOnly); `SuiteHost.bootstrap` overload con placeholders; wired en reloadSuite. Sin PAPI → unavailable y tokens intactos. |
| A5 | Comandos paridad: lang(A1)/toggle/reset/status | ✅ 2026-08-25 | `/suite toggle [jugador]` alterna off↔auto vía store; `/suite reset` mueve configs de usuario a backup/<timestamp>/ y regenera defaults (storage.yml intacto); status ampliado (claim). |
| A6 | Self-traducción UI: strings centralizados | 🟡 estructural ✅ 2026-08-25 | `messages.yml` copiado-if-missing + `MessagesConfig` (flatten anidado→claves dotted, defaults BUILT_IN como último recurso, {} / %s posicionales). TODOS los literales del plugin usan claves. Traducción VIVA por idioma de receptor (pasar por el pipeline) queda como mejora futura — hoy es configurable, no auto-traducido. |
| A7 | Colores por permiso (ColorMode consumo real en renderer) | P1 | FORZAR/POR-PERMISO/QUITAR como el original; permiso `suite.chat.<canal>.color`. |
| A8 | Menciones @nick → canal `_mention`-equivalente | P2 | Re-ruteo pre-dispatch según regex configurable. |
| A9 | Discord con JDA detrás de SyncSink (embeds, replies→DM, roles↔permisos, console, link) | P2 | Requiere red para deps; gateway propio queda como alternativa ligera. Confirmar autor. |
| A10 | Storage SQLite/MySQL backends | P2 | Tras estabilizar el port de A1. |
| B1 | Sinks sync wiring runtime (sync/*.yml → sinks + listener→dispatcher) | media/baja | Decisión autor 2026-08-24. Aquí se evalúa integrar kernel discovery (ventaja: módulos descargables Manager). |
| B2 | engine.parallel en DefaultRouter/dispatcher | baja | Executor alrededor del loop de deliver; decisiones puras ya lo permiten. |
| B3 | Sonidos: lookup adicional NamespacedKey registry | baja | Refinamiento de la heurística actual. |

## FASE 3 — UNIFICAR DOMINIO (deuda estructural, sin cambios)
Sin cambios vs plan anterior (adoptar core-api canónico, transport único, MessageCodec
único, schema single-source).

## FASE 4 — PARIDAD FUNCIONAL restante
Cubierta por FASE 2B (A4-A10). Restos históricos: signs persistentes, bStats,
update-checker, rescue-mode, migradores de config — prioridad baja (P3).

## COMANDOS ÚTILES (entorno actual)
```bash
export JAVA_HOME=/opt/javac/x64/21
source ~/.nvm/nvm.sh   # node/npm

# Web editor (gate completo):
cd suite/web-editor && npm run check && npm run test:integration

# Java suite (offline; publicar deps a mavenLocal antes de host):
cd suite/core-api      && ./gradlew test publishToMavenLocal --offline --no-daemon
cd suite/kernel        && ./gradlew test publishToMavenLocal --offline --no-daemon
cd suite/textformatter && ./gradlew test publishToMavenLocal --offline --no-daemon
cd suite/iflow         && ./gradlew test publishToMavenLocal --offline --no-daemon
cd suite/gtranslate    && ./gradlew publishToMavenLocal --offline --no-daemon
cd suite/ltranslate    && ./gradlew publishToMavenLocal --offline --no-daemon
cd suite/host          && ./gradlew test --offline --no-daemon
```

## NOTAS PARA PRÓXIMA SESIÓN
- Leer este archivo al inicio; actualizar estados ✅/❌.
- **Prioridad inmediata = FASE 2B**: A1 persistencia idioma + `/suite lang`
  (port `UserLanguageStore`, backend YAML), A2 eventos join/quit/death,
  A3 claim-mode configurable (cancel-event|clear-recipients).
- Antes de codificar A3: decidir clave y semántica exacta en config.yml y
  propagarla a schema del editor (nueva mini-versión v2.2.1 o v2.3).
- Fat-jar spigot-host: regenerarlo si se toca cualquier jar hermano
  (procedimiento manual sin red en historial de sesión).
- Git: ~140 cambios sin commitear (incluye eliminación del trío) — pedir
  autorización para commitear/pushear por temas.
- Auditoría integral + veredictos: docs/AUDITORIA-2026-08-24.md (fuente de
  verdad de prioridades FASE 2B).
- Deuda menor conocida: ventana de shutdown en delivery (P4), consola
  fail-closed en permisos (documentado), `pool.max-concurrent` sin consumir.
- Con spigot-host la suite YA corre en Spigot como plugin propio
  (coexistiendo con el legacy). Falta validación en servidor y Fabric.
