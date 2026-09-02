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
| fabric-host / Manager | ❌ no existen | — |
| tester (suite/tester) | ✅ 25 tests runtime + PerformanceProfiler | tests |
| messages | ✅ i18n centralizado EN/ES | tests |
| transport | ✅ HttpURLConnection + MessageCodec único | tests |

---

## 2. COLA DE FASES (actualizado 2026-09-02)

```
1. FASE 4   fabric-host funcional           ← SIGUIENTE
2. FASE 5   Strings UI centralizados (i18n)
3. FASE 6   Motor de reglas iFlow enriquecido
4. FASE 7   ConfigValidator real
5. FASE 8   Sistema comandos dinámico (/suite)
6. FASE 9   sync-velocity real
7. FASE 10  Observabilidad (metrics/debug/simulate)
8. RESTOS   P2/P3 consolidados
```

### FASE 4 — fabric-host (BASE)
| # | Pieza | Estado |
|---|-------|--------|
| F4-1 | fabric-host plugin: `FabricMod` entrypoint, `FabricActorDirectory`, `FabricChatDelivery` | ⏳ |
| F4-2 | Loom 1.6.12 configurado, mappings 1.20.6+ | ⏳ |
| F4-3 | Test en servidor Fabric real | ⏳ |

### FASE 5 — Strings UI centralizados (i18n)
| # | Pieza | Estado |
|---|-------|--------|
| F5-1 | Mover strings hardcodeados a `suite/messages` (catalogos EN/ES) | ⏳ |
| F5-2 | Recobrar 98% strings en config (actualmente 0% en plugin) | ⏳ |
| F5-3 | `/suite lang` usa `MessagesCatalog` | ⏳ |

### FASE 6 — Motor de reglas iFlow enriquecido
| # | Pieza | Estado |
|---|-------|--------|
| F6-1 | Destino "channel" en reglas | ⏳ |
| F6-2 | Permisos/PAPI dentro de SpEL | ⏳ |
| F6-3 | `MessageEventBus` público para third-party | ⏳ |
| F6-4 | `transform` real (F7+) | ⏳ |

### FASE 7 — ConfigValidator real
| # | Pieza | Estado |
|---|-------|--------|
| F7-1 | Validación estructural contra schema del editor | ⏳ |
| F7-2 | Issues con shape del editor reportados en consola | ⏳ |

### FASE 8 — Sistema comandos dinámico (/suite)
| # | Pieza | Estado |
|---|-------|--------|
| F8-1 | Topología dinámica desde `commands.yml` v2 | ⏳ |
| F8-2 | Acciones ATÓMICAS combinables (specs: jugador/idioma/ruta-config/enum) | ⏳ |
| F8-3 | Feedback reutilizando motor de chat | ⏳ |
| F8-4 | Edición config.yml desde comandos (estilo LuckPerms) | ⏳ |
| F8-5 | `/suite` base configurable (renombrable: cht/dst/txf/tg/if) | ⏳ |

### FASE 9 — sync-velocity real
| # | Pieza | Estado |
|---|-------|--------|
| F9-1 | Implementar `suite/sync-velocity` real o eliminar stub | ⏳ |

### FASE 10 — Observabilidad
| # | Pieza | Estado |
|---|-------|--------|
| F10-1 | Metrics endpoint (`/metrics` Prometheus) | ⏳ |
| F10-2 | Debug endpoints (`/debug/simulate`, `/debug/dump`) | ⏳ |
| F10-3 | Healthchecks para sinks | ⏳ |

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

---

## 5. PRÓXIMA ACCIÓN INMEDIATA

**FASE 4 → fabric-host funcional** (Paper ya listo y probado en servidor real).