# PROMPT_NOW.md — Plan maestro vivo

> Regla anti-acumulación: este archivo se AUDITA al cierre de cada fase.
> Lo histórico va a `docs/historial/session-<fecha>.md`; lo resuelto se marca
> ✅ y se comprime; lo vigente queda accionable.

---

## 1. ESTADO DEL SISTEMA (2026-09-23)

| Pieza | Estado | Verificación |
|---|---|---|
| core-api (modelo atómico + SPI) | ✅ estable, JDK-puro | tests |
| kernel (ServiceLoader + grafo semver/Tarjan) | ✅ | 13 tests |
| textformatter (MiniMessage + Channels + `<tr>`) | ✅ estable | tests |
| iflow (router/reglas/rate-limit per-key) | ✅ (`engine.parallel` sin consumir) | tests |
| host (SuiteHost+Dispatcher+loaders) | ✅ estable | 39 tests |
| gtranslate / ltranslate | ✅ | tests |
| sync-discord | ✅ JDA wired vía DiscordBridge (respeta iFlow) | build |
| sync-telegram/http/tcpudp | ✅ motores OK | tests propios |
| sync-velocity | ✅ compila (repo: repo.papermc.io) | compile |
| sync-websocket | ✅ real (SO_REUSEADDR fix) | build |
| spigot-host | ✅ **COMPILA** (DynamicCommand, Registrar, Plugin, DiscordBridge, WS, HealthCheckRegistry) | compile |
| fabric-host | ⚠️ **Excluido** (requiere descarga Minecraft/Loom) | build falla |
| web-editor | ✅ gates verdes | check+integración |
| manager-api | ✅ SPI estable | compile |
| manager-impl | ✅ compila (manifest validation obligatoria, capability check habilitado) | compile |
| presets | ✅ standard/rpg/staff/minimal | tests |
| inworld | ✅ **COMPILA** (InWorldHandler con Server param) | compile |
| observability | ✅ metrics/debug/health (auth token, 127.0.0.1) | tests |
| extension-api | ✅ SDK estable | tests |
| example-extension | ✅ demo funcional | tests |
| tester | ✅ 25 tests runtime + PerformanceProfiler | tests |
| messages | ✅ i18n centralizado EN/ES | tests |
| i18n (FASE 5) | ✅ strings hardcodeados → MessagesCatalog | 98% |
| transport | ✅ HttpURLConnection + MessageCodec único | tests |
| loadtest | ✅ JMH benchmarks | build |
| performance | ✅ profiling (CPU/heap/hotspot/cache/memory) | tests |

---

## 2. COLA DE FASES (actualizado 2026-09-23)

```
1. FASE 4   fabric-host funcional           ❌ (bloqueado por descarga Minecraft/Loom)
2. FASE 5   Strings UI centralizados (i18n) ✅
3. FASE 6   Motor de reglas iFlow enriquecido ✅
4. FASE 7   ConfigValidator real            ✅
5. FASE 8   Sistema comandos dinámico (/src) ✅ (renamed from /suite)
6. FASE 9   sync-velocity real              ✅ compila
7. FASE 10  Observabilidad                  ✅
8. FASE 11  Extensiones/addons (SDK)        ✅
9. FASE 12  Descargador runtime + attach/detach ⚠️ (ver abajo)
10. FASE 13  sync-websocket                  ✅
11. FASE 14  Presets, `transform` real, `engine.parallel` knob  ✅
12. FASE 15 F8 in-world                     ✅ compila
13. FASE 16 Tests, Optimización y Documentación ⚠️ (docs + E2E pendientes)
```

### FASE 16 — Tests, Optimización y Documentación (EN CURSO)
| # | Pieza | Estado |
|---|-------|--------|
| F16-1 | Tests de carga/estrés (JMH + Gatling) | ✅ |
| F16-2 | Tests de integración end-to-end | ⏳ (requiere servidor Spigot real) |
| F16-3 | Optimización de rendimiento (profiling, memory tuning) | ✅ |
| F16-4 | Documentación final (Wiki, API docs, guías) | ⏳ (sincronizar README/PLAN/PROMPT_NOW/Release Notes/ADR) |
| F16-5 | Benchmarks de regresión continua | ⏳ |
| F16-6 | Release pipeline y versionado semántico | ⏳ |

---

### FASE 12 — Descargador runtime + attach/detach (DEUDA CONOCIDA)
| # | Pieza | Estado |
|---|-------|--------|
| F12-1 | Manager API: ModuleCoordinate, ModuleDescriptor, Environment, ModuleLifecycle SPI | ✅ |
| F12-2 | Manager Impl: GitHub releases downloader | ⚠️ **Releases = 0** (pendiente pipeline release) |
| F12-3 | Version resolver (rangos semver + env compat) | ✅ **Implementado** (SemVer.satisfies + env checks) |
| F12-4 | Dependency relocator | ✅ (bug crítico arreglado: recursión infinita) |
| F12-5 | ClassLoader aislado (parent-last) | ✅ (URLClassLoader, no ClassLoader) |
| F12-6 | SHA256 verification | ✅ **Conectado** (asset .sha256 obligatorio, verificación en download) |
| F12-7 | Force flag para versiones no compatibles | ✅ |
| F12-8 | Comando `/suite update` | ✅ |
| F12-9 | Comando `/suite module` (install, update, list, remove, info) | ✅ (UI lista con ✓/✗, install/update/remove TODOs) |
| F12-10 | Integración spigot-host/fabric-host (start/stop/reload) | ✅ spigot-host compila |
| F12-11 | register() semántica | ✅ **Descriptor SPI only**, no instanciar Module como servicio |
| F12-12 | discoverAll() para integración kernel | ✅ Combina classpath + classloaders runtime |
| F12-13 | Manifest validation obligatoria | ✅ Lanza excepción si falta module.yml |
| F12-14 | **Repository abstraction** (multi-source: GitHub + local file:/// http://) | ✅ **Completado** — Configuración de repositorios en config.yml, fallback ordenado, testing local sin GitHub |

> **Estado actual**: F12 núcleo completado. Pendiente: GitHub Releases reales (requiere release pipeline), Security Sprint 3.

---

## 3. DECISIONES VINCULANTES (índice)

- Retrocompatibilidad **FUNCIONAL** con ChatTranslator original (no código intermedio). Trío monolítico eliminado. → PROMPT.md / AUDITORIA-2026-08-24.md
- **Primero las bases**: Manager/tests/comandos/editor antes que features. → PROMPT.md
- core-api JDK-puro; puertos con Adventure viven en `host/port`. → ADR 2026-08-24
- Pureza modular: 1 jar inicial (Manager), motores separados, resolución vs entorno actual. → FASE 5
- Sin modloader: módulos = plugins reales; plataforma nativa carga. → FASE 5
- JDA detrás del puerto SyncSink; gateway propio = alternativa cero-deps. → A9
- Comandos: topología dinámica + acciones atómicas + feedback por motor. → FASE 8
- Persistencia idioma paridad obligatoria; `off` ⇒ AUTO (texto fuente). → A1
- Claim-mode configurable cancel-event/clear-recipients. → A3
- **Message inmutable** → mutaciones vía `withX()` + `Builder.from()`. → Auditoría C1
- **iFlow autoridad única** → Discord mirror respeta `DispatchReport`. → Auditoría H2
- **Language detection O(1)** → `resolvedSourceLanguage` caché en Message. → Auditoría H3

---

## 4. ENTORNO Y COMANDOS

```bash
export JAVA_HOME=/opt/javac/x64/21
source ~/.nvm/nvm.sh

# Web editor
cd src/web-editor && npm run check && npm run test:integration

# Suite Java (orden; offline salvo deps nuevas)
for m in core-api kernel textformatter iflow gtranslate ltranslate messages tester transport sync-discord sync-telegram sync-http sync-tcpudp sync-websocket sync-velocity host presets inworld observability extension-api example-extension loadtest performance manager-api manager-impl; do
  (cd src/$m && ./gradlew test publishToMavenLocal --offline --no-daemon)
done

# Plugin Spigot de la suite (fat-jar) — AHORA COMPILA
cd src/spigot-host && ./gradlew build --offline --no-daemon

# Plugin Fabric de la suite — EXCLUIDO (requiere descarga Minecraft/Loom)
# cd src/fabric-host && ./gradlew build --offline --no-daemon

# Web editor
cd src/web-editor
npm run check                        # format:check + lint + test (99 unit)
npm run test:integration             # harnesses func/interact/click/chain/undo/diffing/bind
```

Git: commits convencionales por tema; push SOLO con autorización explícita.

---

## 5. PRÓXIMA ACCIÓN INMEDIATA

### 🔴 CRÍTICO — Tests E2E
1. Servidor Spigot real corriendo
2. Pipeline completo: chat → iFlow → format → delivery

### 🔴 CRÍTICO — Repository Abstraction (F12-14)
1. Configurar `repositories:` en config.yml (lista de URLs: GitHub oficial, local, HTTP)
2. Modificar `DefaultModuleLifecycle` para iterar repositorios configurados
3. Soporte `file:///ruta/local` para testing sin GitHub
4. Fallback ordenado: local → GitHub oficial → mirrors

### 🟡 OTROS AUDITORIA-16
1. Translation cache/dedup (P1 - escalabilidad)
2. sync-velocity implementar real o eliminar stub
3. ~~Spigot build - spigot-api 1.16.5-R0.1-SNAPSHOT unavailable~~ ✅ Fix aplicado (Paper API)

### ✅ RESUELTOS EN ESTA SESIÓN (2026-09-24)
- JAR size: shadowJar excluye dependencias (era 30MB, ahora solo plugin code 56KB)
- Module list: `/suite module list` muestra instalados (✓) + disponibles (✗) de GitHub
- Chat duplication: fixed (iniciador + broadcast no se duplican)
- Google Translate: fixed Boolean/String parsing for `active` field
- Console chat: player chat ahora aparece en consola (`chat.log-to-console: true`)
- Router test: fixed RouteOutcome.decision() accessor
- Reload issues: WebSocket SO_REUSEADDR, Discord channel=0 handled gracefully
- Command: `/suite` (aliases: /suite, /txf) — directorio renombrado a `src/`
- Stress test resilient: continues on individual message failures
- System.out/err: replaced with PluginLogger in TextFormatters, DebugEndpoint
- **Repository Abstraction (F12-14)**: config.yml `repositories:` con soporte GitHub, local (file://), HTTP; fallback ordenado; testing local sin GitHub

---

## 6. RESUMEN DE AUDITORÍA 2026-09-23 — ESTADO ACTUAL

| Área | Estado | Comentario |
|------|--------|------------|
| **Core modules** | ✅ Compilan + tests pasan | core-api, iflow, host, textformatter, manager-impl, observability, extension-api, inworld, sync-velocity, sync-websocket |
| **Bugs críticos (C1-C5)** | ✅ Arreglados | Message contract, MessageEvent, Module Manager, DebugEndpoint, Compilación |
| **Bugs altos (H1-H5)** | ✅ Arreglados | RateLimiter, Discord bypass, Language cache, ConfigValidator, Channel/Direction |
| **Security Sprint 1** | ✅ Done | SpEL sandbox, YAML SafeConstructor, MiniEscape, SafeConstructor args |
| **Security Sprint 2** | ✅ Done | Bounded executors, CallerRunsPolicy, observability fixes |
| **Bugs auditoría 14/16 sep** | ✅ Arreglados | Transform propagation, Message.toJson(), Direction.specific(), MiniEscape, SpEL LRU cache, ConfigLoader logging |
| **Module Manager (F12)** | ✅ **Núcleo completado** | Version resolver, dependency parsing (manifest.yml), SHA256, register() SPI, discoverAll(), discoverAvailableModules() |
| **Security Sprint 3** | ✅ Completado | char[] tokens, MiniEscape completo, PAPI check, SpEL cache, SSRF, DependencyVerification |
| **Spigot-host** | ✅ COMPILA | DynamicCommand, Registrar, Plugin reload, DiscordBridge, WS, HealthCheckRegistry |
| **inworld** | ✅ COMPILA | InWorldHandler constructor con Server param |
| **sync-velocity** | ✅ **Production-ready** | Paper API 3.4.0, async queue, retry/backoff, metrics, health, dynamic discovery |
| **Spigot build** | ✅ **Fix aplicado** | Cambiado a Paper API 1.21.4 |
| **Docs Sync** | 🔄 **En progreso** | README/PLAN/PROMPT_NOW/Release Notes/ADR |

---

## 7. PRÓXIMA ACCIÓN INMEDIATA

**PRIORIDAD 1**: Tests E2E (servidor Spigot real)
**PRIORIDAD 2**: Repository Abstraction (F12-14) — config.yml `repositories:` + local file:/// + fallback
**PRIORIDAD 3**: Release pipeline — gradle.lockfile portable (verification-metadata.xml ✅, CI/CD ✅)
**PRIORIDAD 4**: GitHub Releases para Module Manager (F12-2)
**PRIORIDAD 5**: Translation cache/dedup para escalabilidad

---

*Última actualización: 2026-09-24 | Commit: (pendiente push)*