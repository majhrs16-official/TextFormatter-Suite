# PROMPT_NOW.md — Plan maestro vivo

> Regla anti-acumulación: este archivo se AUDITA al cierre de cada fase.
> Lo histórico va a `docs/historial/session-<fecha>.md`; lo resuelto se marca
> ✅ y se comprime; lo vigente queda accionable.

---

## 1. ESTADO DEL SISTEMA (2026-09-02)

| Pieza | Estado | Verificación |
|---|---|---|
| core-api (modelo atómico + SPI) | ✅ estable, JDK-puro | tests |
| kernel (ServiceLoader + grafo semver/Tarjan) | ✅ | 13 tests |
| textformatter (MiniMessage + Channels + `<tr>`) | ✅ estable | tests |
| iflow (router/reglas/rate-limit) | ✅ (`engine.parallel` sin consumir) | tests |
| host (SuiteHost+Dispatcher+loaders) | ✅ estable | 39 tests |
| gtranslate / ltranslate | ✅ | tests |
| sync-discord | ✅ JDA wired vía DiscordBridge | build |
| sync-telegram/http/tcpudp | ✅ motores OK | tests propios |
| spigot-host | ✅ funcional · ✅ **probado en Paper 1.20.6 real** | 4 tests + server test |
| web-editor | ✅ gates verdes | check+integración |
| fabric-host / Manager | ✅ implementado | build |
| tester (suite/tester) | ✅ 25 tests runtime + PerformanceProfiler | tests |
| messages | ✅ i18n centralizado EN/ES | tests |
| i18n (FASE 5) | ✅ strings hardcodeados → MessagesCatalog | 98% |
| transport | ✅ HttpURLConnection + MessageCodec único | tests |

---

## 2. COLA DE FASES (actualizado 2026-09-02)

```
1. FASE 4   fabric-host funcional           ✅
2. FASE 5   Strings UI centralizados (i18n) ✅
3. FASE 6   Motor de reglas iFlow enriquecido ✅
5. FASE 7   ConfigValidator real            ✅
6. FASE 8   Sistema comandos dinámico (/suite) ✅
7. FASE 9   sync-velocity real              ✅
8. FASE 10  Observabilidad                  ✅
9. FASE 11  Extensiones/addons (SDK)        ✅
10. FASE 12  Descargador runtime + attach/detach ✅
11. FASE 13  sync-websocket                  ← SIGUIENTE
12. FASE 14  Presets, `transform` real, `engine.parallel` knob
13. FASE 15  F8 in-world (signos/cofres/libros, WORLD/RADIUS, caché+glosario, botones click/hover)
```

### FASE 4 — fabric-host (BASE)
| # | Pieza | Estado |
|---|-------|--------|
| F4-1 | fabric-host plugin: `FabricMod` entrypoint, `FabricActorDirectory`, `FabricChatDelivery` | ✅ |
| F4-2 | Loom 1.6.12 configurado, mappings 1.20.6+ | ✅ (1.21 + yarn 1.21+build.1) |
| F4-3 | Canales por defecto (join/quit/death/advancement) | ✅ |
| F4-4 | Event handlers: death, advancement | ✅ |
| F4-5 | Comando `/suite` (reload, status, lang, toggle, reset) | ✅ |
| F4-6 | Test en servidor Fabric real | ⏳ (pendiente Java 21) |

### FASE 5 — Strings UI centralizados (i18n)
| # | Pieza | Estado |
|---|-------|--------|
| F5-1 | Mover strings hardcodeados a `suite/messages` (catalogos EN/ES) | ✅ |
| F5-2 | Recobrar 98% strings en config (actualmente 0% en plugin) | ✅ |
| F5-3 | `/suite lang` usa `MessagesCatalog` | ✅ |

### FASE 6 — Motor de reglas iFlow enriquecido
| # | Pieza | Estado |
|---|-------|--------|
| F6-1 | Destino "channel" en reglas (CHANNEL_REDIRECT) | ✅ |
| F6-2 | Permisos/PAPI dentro de SpEL | ✅ |
| F6-3 | `MessageEventBus` público para third-party | ✅ (core-api/event/MessageEvent) |
| F6-4 | `transform` real (F7+) | ✅ (rewrite, sounds, sleep, setLangSource, setLangTarget, setColorMode, setFormatPapi, setChannel) |

### FASE 7 — ConfigValidator real
| # | Pieza | Estado |
|---|-------|--------|
| F7-1 | Validación estructural contra schema del editor | ✅ |
| F7-2 | Issues con shape del editor reportados en consola | ✅ |

### FASE 8 — Sistema comandos dinámico (/suite)
| # | Pieza | Estado |
|---|-------|--------|
| F8-1 | Topología dinámica desde `commands.yml` v2 | ✅ |
| F8-2 | Acciones ATÓMICAS combinables (specs: jugador/idioma/ruta-config/enum) | ✅ |
| F8-3 | Feedback reutilizando motor de chat | ✅ |
| F8-4 | Edición config.yml desde comandos (estilo LuckPerms) | ⏳ |
| F8-5 | `/suite` base configurable (renombrable: cht/dst/txf/tg/if) | ✅ |

### FASE 9 — sync-velocity real
| # | Pieza | Estado |
|---|-------|--------|
| F9-1 | Implementar `suite/sync-velocity` real o eliminar stub | ✅ |
| F9-2 | VelocitySink: plugin messaging channel, secret auth, mapping | ✅ |
| F9-3 | VelocityPlugin: Module SPI, velocity-plugin.json | ✅ |
| F9-4 | Test en proxy Velocity real | ⏳ (pendiente proxy Velocity) |

### FASE 10 — Observabilidad
| # | Pieza | Estado |
|---|-------|--------|
| F10-1 | Metrics endpoint (`/metrics` Prometheus) | ✅ |
| F10-2 | Debug endpoints (`/debug/simulate`, `/debug/dump`) | ✅ |
| F10-3 | Healthchecks para sinks | ✅ |
| F10-4 | Debug endpoints (`/debug/state`, `/debug/channels`) | ✅ |
| F10-5 | Dynamic commands: `/suite health`, `/suite metrics` | ✅ |

### FASE 11 — Extensiones/addons (SDK)
| # | Pieza | Estado |
|---|-------|--------|
| F11-1 | Extension API: Extension, ExtensionContext, ExtensionConfig, ExtensionMetadata | ✅ |
| F11-2 | ExtensionManager: discovery, dependency resolution, load/unload/reload | ✅ |
| F11-3 | Extension SPI: onEnable/onDisable/onConfigReload, Capability system | ✅ |
| F11-4 | Example extension demonstrating the API | ✅ |
| F11-5 | ExtensionContext: channel registration, message dispatch, state, events | ✅ |
| F11-6 | Integration with spigot-host/fabric-host (ExtensionManager start/stop) | ✅ |
| F11-7 | Web-editor support for extension management (list, enable/disable, config) | ✅ |
| F11-8 | Extension manifest schema (extension.yml) + validation | ✅ (docs/extension-schema.md) |
---

### FASE 12 u2014 Descargador runtime + attach/detach
| # | Pieza | Estado |
|---|-------|--------|
| F12-1 | Manager API: ModuleCoordinate, ModuleDescriptor, Environment, ModuleLifecycle SPI | ✅ |
| F12-2 | Manager Impl: GitHub releases downloader, version resolver, dependency relocator | ✅ |
| F12-3 | ClassLoader aislado (parent-last) + SHA256 verification | ✅ |
| F12-4 | Force flag para versiones no compatibles | ✅ |
| F12-5 | Comando `/suite update` (actualización completa) | ✅ |
| F12-6 | Comando `/suite module` (install, update, list, remove, info) | ✅ |
| F12-7 | Integración spigot-host/fabric-host (start/stop/reload) | ✅ |

---

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

---

## 4. ENTORNO Y COMANDOS

```bash
export JAVA_HOME=/opt/javac/x64/21
source ~/.nvm/nvm.sh

# Web editor
cd suite/web-editor && npm run check && npm run test:integration

# Suite Java (orden; offline salvo deps nuevas)
for m in core-api kernel textformatter iflow gtranslate ltranslate messages tester transport; do
  (cd suite/$m && ./gradlew test publishToMavenLocal --offline --no-daemon)
done
(cd suite/host && ./gradlew test publishToMavenLocal --offline --no-daemon)
(cd suite/sync-discord && ./gradlew test --offline --no-daemon)
(cd suite/sync-telegram && ./gradlew test --offline --no-daemon)
(cd suite/sync-http && ./gradlew test --offline --no-daemon)
(cd suite/sync-tcpudp && ./gradlew test --offline --no-daemon)
(cd suite/sync-telegram && ./gradlew test --offline --no-daemon)

# Plugin Spigot de la suite (fat-jar)
cd suite/spigot-host && ./gradlew build --offline --no-daemon

# Web editor
cd suite/web-editor
npm run check                        # format:check + lint + test (99 unit)
npm run test:integration             # harnesses func/interact/click/chain/undo/diffing/bind
```

Git: commits convencionales por tema; push SOLO con autorización explícita.

> **Nota sub-agentes (2026-09-06):** Cada `task` lanza un NUEVO agente stateless. No hay persistencia entre llamadas. Para "continuar" un agente fallido, relanzar `task` con prompt `"continua" + contexto resumido`. La API Nemotron 3 Ultra está saturada → reintentos frecuentes (502/504).

---

## 5. PRÓXIMA ACCIÓN INMEDIATA

**FASE 13 → sync-websocket** — WebSocket server para sync en tiempo real, suscripciones `/ws/chat`, `/ws/events`, `/ws/sync`, `/ws/logs`.
