# PROMPT_NOW.md — Plan maestro vivo

> Regla anti-acumulación: este archivo se AUDITA al cierre de cada fase.
> Lo histórico va a `docs/historial/session-<fecha>.md`; lo resuelto se marca
> ✅ y se comprime; lo vigente queda accionable.

---

## 1. ESTADO DEL SISTEMA (2026-09-16)

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
| sync-velocity | ❌ **Bloqueado** (dependencia velocity-api no disponible) | build falla |
| sync-websocket | ✅ real | build |
| spigot-host | ❌ **Bloqueado** (Spigot API no disponible en Maven) | build falla |
| fabric-host | ⚠️ **Excluido** (requiere descarga Minecraft) | build falla |
| web-editor | ✅ gates verdes | check+integración |
| manager-api | ✅ SPI estable | compile |
| manager-impl | ⚠️ **No release-ready** (ver auditoría 2026-09-13) | compile |
| presets | ✅ standard/rpg/staff/minimal | tests |
| inworld | ❌ **Bloqueado** (depende spigot-host) | build falla |
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

## 2. COLA DE FASES (actualizado 2026-09-16)

```
1. FASE 4   fabric-host funcional           ❌ (bloqueado por descarga Minecraft)
2. FASE 5   Strings UI centralizados (i18n) ✅
3. FASE 6   Motor de reglas iFlow enriquecido ✅
4. FASE 7   ConfigValidator real            ✅
4. FASE 8   Sistema comandos dinámico (/suite) ✅
5. FASE 9   sync-velocity real              ❌ (bloqueado velocity-api)
5. FASE 10  Observabilidad                  ✅
6. FASE 11  Extensiones/addons (SDK)        ✅
7. FASE 12  Descargador runtime + attach/detach ⚠️ (No release-ready)
8. FASE 13  sync-websocket                  ✅
9. FASE 14  Presets, `transform` real, `engine.parallel` knob  ✅
10. FASE 15 F8 in-world                     ❌ (bloqueado spigot-host)
11. FASE 16 Tests, Optimización y Documentación ⚠️ (parcial: docs + E2E pendientes)
```

### FASE 16 — Tests, Optimización y Documentación (EN CURSO)
| # | Pieza | Estado |
|---|-------|--------|
| F16-1 | Tests de carga/estrés (JMH + Gatling) | ✅ |
| F16-2 | Tests de integración end-to-end | ⏳ (pipeline completo Spigot+Fabric **bloqueado**) |
| F16-3 | Optimización de rendimiento (profiling, memory tuning) | ✅ |
| F16-4 | Documentación final (Wiki, API docs, guías) | ⏳ (sincronizar README/PLAN/PROMPT_NOW/Release Notes/ADR) |
| F16-5 | Benchmarks de regresión continua | ⏳ |
| F16-6 | Release pipeline y versionado semántico | ⏳ |

---

### FASE 12 — Descargador runtime + attach/detach (DEUDA CONOCIDA)
| # | Pieza | Estado |
|---|-------|--------|
| F12-1 | Manager API: ModuleCoordinate, ModuleDescriptor, Environment, ModuleLifecycle SPI | ✅ |
| F12-2 | Manager Impl: GitHub releases downloader | ⚠️ **Releases = 0** |
| F12-3 | Version resolver | ⚠️ **Stub** (lanza UnsupportedOperationException) |
| F12-4 | Dependency relocator | ✅ (bug crítico arreglado: recursión infinita) |
| F12-5 | ClassLoader aislado (parent-last) | ✅ (URLClassLoader, no ClassLoader) |
| F12-6 | SHA256 verification | ⚠️ **No conectado** (sha256 = null) |
| F12-7 | Force flag para versiones no compatibles | ✅ |
| F12-8 | Comando `/suite update` | ✅ |
| F12-9 | Comando `/suite module` (install, update, list, remove, info) | ✅ |
| F12-10 | Integración spigot-host/fabric-host (start/stop/reload) | ❌ (bloqueado spigot-host) |

> **Veredicto auditoría**: F12 no debería marcarse como 100%. Es un prototipo con API definida pero implementación incompleta.

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
cd suite/web-editor && npm run check && npm run test:integration

# Suite Java (orden; offline salvo deps nuevas)
for m in core-api kernel textformatter iflow gtranslate ltranslate messages tester transport sync-discord sync-telegram sync-http sync-tcpudp sync-websocket host presets inworld observability extension-api example-extension loadtest performance manager-api manager-impl; do
  (cd suite/$m && ./gradlew test publishToMavenLocal --offline --no-daemon)
done

# Plugin Spigot de la suite (fat-jar) — BLOQUEADO: Spigot API no disponible
# cd suite/spigot-host && ./gradlew build --offline --no-daemon

# Plugin Fabric de la suite — EXCLUIDO (requiere descarga Minecraft)
# cd suite/fabric-host && ./gradlew build --offline --no-daemon

# Web editor
cd suite/web-editor
npm run check                        # format:check + lint + test (99 unit)
npm run test:integration             # harnesses func/interact/click/chain/undo/diffing/bind
```

Git: commits convencionales por tema; push SOLO con autorización explícita.

---

## 5. PRÓXIMA ACCIÓN INMEDIATA

### 🔴 CRÍTICO — Desbloquear spigot-host
1. **Resolver dependencia Spigot API** — El artefacto `org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT` no está disponible en Maven Central ni en hub.spigotmc.org (404). Opciones:
   - Usar versión release de Spigot/Paper API en lugar de SNAPSHOT
   - Compilar Spigot API desde fuente
   - Usar Paper API como alternativa
   - Instalar manualmente en mavenLocal

### 🔴 CRÍTICO — Completar spigot-host
Una vez disponible Spigot API:
1. Completar `DynamicCommand` implementación `Command` interface
2. Implementar clases faltantes: `DispatchReport`, `HealthCheckRegistry`, `WebSocketSyncSink`, `InWorldHandler`
3. Publicar `sync-velocity`, `sync-websocket`, `inworld` en mavenLocal
2. **Sincronización documentación** (README, PLAN, PROMPT_NOW, Release Notes, Wiki, ADR a un mismo estado)
3. **Module Manager (F12) consolidación interna**: version resolver (rangos semver + env compat), dependency resolver (manifest parsing), SHA256 verification real, allowlist + manifest validation, register() semántica (descriptor SPI, no servicio)
4. **Security hardening pendiente**: tokens en `char[]` + `Arrays.fill()`, template validation, PAPI dynamic check
5. **Module Manager (F12) release-ready**: **AL FINAL** publicar GitHub releases (tags + assets + .sha256) solo cuando todo lo anterior esté verde en local
6. **Release pipeline** + versionado semántico + gradle.lockfile

---

## 6. RESUMEN DE AUDITORÍA 2026-09-16 — ESTADO ACTUAL

| Área | Estado | Comentario |
|------|--------|------------|
| **Core modules** | ✅ Compilan | core-api, iflow, host, textformatter, manager-impl, observability, extension-api |
| **Bugs críticos (C1-C5)** | ✅ Arreglados | Message contract, MessageEvent, Module Manager, DebugEndpoint, Compilación |
| **Bugs altos (H1-H5)** | ✅ Arreglados | RateLimiter, Discord bypass, Language cache, ConfigValidator, Channel/Direction |
| **Security Sprint 1** | ✅ Done | SpEL sandbox, YAML SafeConstructor, MiniEscape, SafeConstructor args |
| **Security Sprint 2** | ✅ Done | Bounded executors, parallel dispatcher, observability fixes |
| **Module Manager (F12)** | ⚠️ Parcial | M2 ✅, M3 ✅, M4 ✅, M5 ✅, M6 ⏳, M1 ⏳ (final) |
| **Security Sprint 3** | ⏳ Pendiente | char[] tokens, template validation, PAPI dynamic check |
| **Spigot-host** | ❌ Bloqueado | Spigot API no disponible |
| **fabric-host** | ❌ Excluido | Requiere descarga Minecraft |
| **sync-velocity** | ❌ Bloqueado | velocity-api no disponible |
| **inworld** | ❌ Bloqueado | Depende spigot-host |
| **Tests E2E** | ⏳ Pendiente | Pipeline completo bloqueado |
| **Docs Sync** | ⏳ Pendiente | README/PLAN/PROMPT_NOW/Release Notes/ADR |

---

## 7. PRÓXIMA ACCIÓN INMEDIATA

**PRIORIDAD 1**: Resolver dependencia Spigot API (usar Paper API, release version, o build local)
**PRIORIDAD 2**: Completar spigot-host + publicar módulos dependientes
**PRIORIDAD 3**: Tests E2E pipeline completo + Sincronización docs completa
**PRIORIDAD 4**: F12 release-ready (M6 + M1) + Security Sprint 3 + Release pipeline

---

*Última actualización: 2026-09-16 | Commit: a420e44*