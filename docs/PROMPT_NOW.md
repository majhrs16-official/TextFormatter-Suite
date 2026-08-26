# PROMPT_NOW.md — Plan maestro vivo

> Regla anti-acumulación: este archivo se AUDITA al cierre de cada fase.
> Lo histórico va a `docs/historial/session-<fecha>.md`; lo resuelto se marca
> ✅ y se comprime; lo vigente queda accionable.

---

## 1. ESTADO DEL SISTEMA

| Pieza | Estado | Verificación |
|---|---|---|
| core-api (modelo atómico + SPI) | ✅ estable, JDK-puro | tests |
| kernel (ServiceLoader + grafo semver/Tarjan) | ✅ · ⚠️ decorativo en runtime hasta FASE 5/M1 | 13 tests |
| textformatter (MiniMessage + Channels + `<tr>`) | ✅ estable | tests |
| iflow (router/reglas/rate-limit) | ✅ (`engine.parallel` sin consumir, B2) | tests |
| host (SuiteHost+Dispatcher+loaders) | ✅ estable | 39 tests |
| gtranslate / ltranslate | ✅ | tests |
| sync-discord | ✅ JDA wired vía DiscordBridge | build |
| sync-telegram/http/tcpudp | 🟡 motores OK, sin wiring runtime (B1) | tests propios |
| spigot-host | ✅ funcional · ❌ sin probar en servidor real | 4 tests (lógica Bukkit sin testear → T2) |
| web-editor | ✅ gates verdes · UX confusa (→ FASE E) | check+integración |
| fabric-host / Manager | ❌ no existen | — |

## 2. COLA DE FASES

```
1. FASE T   red de seguridad de tests        ← SIGUIENTE
2. FASE C   sistema de comandos
3. FASE 5   Manager
4. FASE E   editor v3 "C sobre ASM"
5. RESTOS   P2/P3 consolidados
```

### FASE T — RED DE SEGURIDAD DE TESTS (BASE)

| # | Tarea | Estado |
|---|-------|--------|
| T1 | Cobertura medida: JaCoCo en host 89.7% · kernel 88.4% · iflow 85.9% · textformatter 74.2% · spigot-host **10.1%** (umbral diferido hasta más extracción). Umbrales anti-regresión activos en `check` (85/80/80/70). Editor c8 pendiente (harness multi-proceso requiere merge de reportes) | ✅ Java 2026-08-25 |
| T2 | Lógica pura fuera de Bukkit: `logic/ChannelSelector`, `logic/LangSetting` (effective/flip/isValid/display), `logic/EventRules` (canales tipados + shouldTranslate) → 8 tests nuevos; plugin/directory delegan en ellas | ✅ 2026-08-25 (12 tests spigot-host) |
| T3 | Golden cross-language: fixture YAML compartido Java↔JS | ⏳ |
| T4 | E2E sin servidor: store+dispatcher+delivery fake+sink stub | ⏳ |
| T5 | Concurrencia determinista: dispatch vs reload; store writers | ⏳ |
| T6 | CI GitHub Actions: suite Java + editor en cada push; fat-jar por tag | ⏳ |

### FASE C — SISTEMA DE COMANDOS (BASE)

Topología dinámica desde config (renombrable: `/cht` `/dst` `/txf` `/tg` `/if`),
acciones ATÓMICAS combinables, feedback reutilizando el motor de chat (el
comando ignora qué motor hay), config editable desde comandos estilo LuckPerms.

| # | Pieza | Estado |
|---|-------|--------|
| C1 | Registro de acciones atómicas tipadas (specs de args: jugador/idioma/ruta-config/enum), combinables, catálogo consultable | ⏳ |
| C2 | Topología commands.yml v2 → registro dinámico en CommandMap (sin plugin.yml) | ⏳ |
| C3 | Feedback = Message INTERNAL/CUSTOM por canales `plugin.cmd.*`, render por receptor (evoluciona messages.yml plano) | ⏳ |
| C4 | Acciones iniciales: lang get/set/toggle · config get/set booleanos/enums · iflow rules list/reload/test · sync status/test-send · txf preview | ⏳ |
| C5 | ConfigWriter con edición puntual preservando el archivo (decidir línea-vs-reescritura) | ⏳ |
| — | Los actuales `/suite *` son puente temporal hasta que C los reemplace | 🔁 |

### FASE 5 — MANAGER (BASE)

**Sin modloader.** Cada módulo es UN PLUGIN REAL (plugin.yml propio); la
plataforma nativa carga al arrancar ⇒ actualizar módulos requiere reiniciar.
El Manager solo gestiona ARCHIVOS y **selecciona/resuelve qué instalar**.

**Distribución (decisión autor):** 1 solo jar inicial = el Manager (= host).
Motores como módulos-plugin separados. Resolución CONTRA EL ENTORNO ACTUAL
(via ModuleDescriptor); `latest` solo en instalación limpia, que auto-selecciona
los obligatorios textformatter+iflow.

| # | Pieza | Estado |
|---|-------|--------|
| M0 | Módulos como plugins reales: plugin.yml propio + implementaciones registradas en META-INF/services (Translator/SyncSink además del descriptor Module) | ⏳ |
| M1 | Descubrimiento cross-plugin: classloaders de plugins-suite → ServiceLoader compuesto → `ModuleLoader.discover` + `ModuleGraph.resolve` (kernel pieza central, carga nativa de Bukkit) | ⏳ |
| M2 | Descarga GitHub Releases: latest-o-compatible, sha256, escritura atómica directa en plugins/ | ⏳ |
| M3 | Ciclo de vida: módulo⇒reinicio; config⇒hot (/suite reload); resolución fallida se informa sin crashear | ⏳ |
| M4 | `/suite module <list\|install\|update\|remove>` → raíz configurable vía FASE C | ⏳ (depende C) |
| M5 | CI releases: 1 artefacto por módulo-plugin + manifest.json (sha256) | ⏳ |

### FASE E — EDITOR v3 "C sobre ASM"

| # | Tarea | Estado |
|---|-------|--------|
| E1 | Matriz cobertura schema↔editor (soportada/parcial/faltante) — documento vivo | ⏳ |
| E2 | Modo atómico (ASM): nodos/YAML directo, todo permitido y visible | ⏳ |
| E3 | Modo alto nivel (C): recetas que COMPILAN a nodos+canales ("chat regional", "staff solo-lectura"); round-trip exacto como invariante | ⏳ |
| E4 | UX orgánica: una toolbar contextual explicada, onboarding, drag con magnetismo de puertos, estados vacíos que enseñan | ⏳ |

### RESTOS P2/P3 (features — después de las bases)

| Item | Fase origen | Prioridad |
|---|---|---|
| advancement wiring | A2 | P2 |
| Colores por permiso (consumo ColorMode en renderer) | A7 | P1-feature |
| Menciones @nick → re-ruteo configurable | A8 | P2 |
| SQLite/MySQL backends del store | A10 | P2 |
| Sinks telegram/http/tcpudp wiring runtime | B1 | media/baja |
| Paridad DST profunda (embeds, replies→DM, roles↔permisos, console-cmds, link) | A9 | P3 |
| engine.parallel (executor alrededor del loop) | B2 | P3 |
| Sonidos NamespacedKey lookup | B3 | P3 |
| signs persistentes · bStats · update-checker · rescue-mode | históricos | P3 |

## 3. DECISIONES VINCULANTES (índice)

- Retrocompatibilidad **FUNCIONAL** con ChatTranslator original (no código intermedio). Trío monolítico eliminado. → PROMPT.md / AUDITORIA-2026-08-24.md
- **Primero las bases**: Manager/tests/comandos/editor antes que features. → PROMPT.md
- core-api JDK-puro; puertos con Adventure viven en `host/port`. → ADR 2026-08-24
- Pureza modular: 1 jar inicial (Manager), motores separados, resolución vs entorno actual. → FASE 5
- Sin modloader: módulos = plugins reales; plataforma nativa carga. → FASE 5
- JDA detrás del puerto SyncSink; gateway propio = alternativa cero-deps. → A9
- Comandos: topología dinámica + acciones atómicas + feedback por motor. → FASE C
- Persistencia idioma paridad obligatoria; `off` ⇒ AUTO (texto fuente). → A1
- Claim-mode configurable cancel-event/clear-recipients. → A3

## 4. ENTORNO Y COMANDOS

```bash
export JAVA_HOME=/opt/javac/x64/21
source ~/.nvm/nvm.sh

# Web editor
cd suite/web-editor && npm run check && npm run test:integration

# Suite Java (orden; offline salvo deps nuevas)
for m in core-api kernel textformatter iflow gtranslate ltranslate; do
  (cd suite/$m && ./gradlew test publishToMavenLocal --offline --no-daemon)
done
(cd suite/host && ./gradlew test publishToMavenLocal --offline --no-daemon)
(cd suite/sync-discord && ./gradlew test --offline --no-daemon)
(cd suite/spigot-host && ./gradlew shadowJar --offline --no-daemon)   # fat-jar instalable
```

Git: commits convencionales por tema; push SOLO con autorización explícita.
