# Auditoría documental — TextFormatter Suite

> **Tipo:** pseudoauditoría documental (Fase 1 de lectura y Fase 3 de ejecución documental).
> **Baseline auditado:** `5735b3c3` (2026-11-04) más los cambios de `CoreTranslator`; commit de
> cierre del baseline: `c5992230`.
> **Alcance:** `README.md`, `docs/README.md`, `docs/prompts/**`, `docs/workdir/{PLAN,ADR}.md`,
> READMEs de módulo y skills de auditoría. `docs/wiki/` quedó fuera de la ronda original y se
> alineó después en P0.8b (véase el addendum). Los arreglos de código se ejecutaron después en
> P0.2/P0.3 con tests de regresión.
> **Método:** lectura del árbol real, verificación de cada ruta citada, contraste con ADR y
> especificaciones, y comprobación de enlaces locales. No se usó el contenido de la auditoría
> anterior como evidencia: auditaba `3aaba49` (2026-10-24), 104 commits por detrás.

---

## 1. Resumen ejecutivo

La documentación no estaba alineada con las decisiones vigentes. El problema dominante no era la
falta de documentos, sino la convivencia de **tres regímenes** en el mismo árbol:

1. el régimen pre-1.0 de ADR-034 (`1.0`, sin compatibilidad, sin migraciones);
2. el régimen anterior (`2.1.0`, `iflow.engine.kind`, exclusividad de motores, rutas planas);
3. documentos históricos presentados como estado actual.

Resultado: **10 hallazgos confirmados** (D-001…D-010). Los 10 quedaron cerrados: D-002 y D-004
se resolvieron con la purga de código de P0.2/P0.3 (versiones, `config-version`, rutas canónicas,
`iflow.engine.kind` y router). El baseline de código quedó limpio en `c5992230` (CoreTranslator +
contrato `>= 1.0.0`); el gate `build checkArchitecture checkLocks` pasó el 2026-11-04.

| Severidad | Hallazgos | Estado documental |
|---|---|---|
| Alta (P0) | D-001, D-002, D-003, D-004, D-008 | 5 corregidos (D-001, D-002, D-003, D-004, D-008) |
| Media (P1) | D-005, D-007, D-009, D-010 | 4 corregidos (D-005, D-007, D-009, D-010) |
| Baja (P2) | D-006 | Corregido |

---

## 2. Matriz de autoridad documental

Ante una contradicción, se aplica este orden:

1. **ADR aceptado** (`docs/workdir/ADR.md`).
2. **Especificación vigente** (`docs/prompts/especificaciones/`, `docs/prompts/diseno/storage.md`).
3. **Código real**: evidencia de comportamiento, no autoridad normativa. Si diverge de 1 o 2, el
   código es el bug.
4. **PLAN** (`docs/workdir/PLAN.md`): estado y hoja de ruta; no crea contratos.
5. **Skills** (`.opencode/skills/`): procedimientos; no crean contratos.
6. **Documentos históricos, auditorías anteriores y wiki**: contexto, nunca estado vigente.

Reglas derivadas:

- Un ADR no contiene planes, fases ni roadmaps; el estado vive en `PLAN.md`.
- Una especificación no puede prometer compatibilidad que el ADR prohíbe.
- Un README de módulo describe el módulo real; si el módulo diverge, se corrige el módulo o se
  declara la deuda, no se inventa una ruta alternativa.
- `docs/wiki/` no es fuente de verdad; se alineó en P0.8b y sigue supeditada al ADR y a las
  especificaciones.

---

## 3. Hallazgos

### D-001 — Rutas alucinadas en el contexto del proyecto

- **Severidad:** Alta (P0).
- **Estado:** **Corregido**.
- **Evidencia (baseline):** `docs/prompts/contexto/proyecto.md` citaba `docs/PLAN.md`,
  `docs/ADR.md`, `docs/PROMPT.md`, `docs/PROMPT_NOW.md` y `/docs/web-editor/`.
- **Realidad verificada:** `docs/workdir/PLAN.md`, `docs/workdir/ADR.md`,
  `docs/prompts/contexto/proyecto.md`, `docs/workdir/PLAN.md` y `src/web-editor/`.
- **Impacto:** un agente o colaborador que siguiera el contexto leía rutas inexistentes y tomaba
  documentos históricos como estado actual.
- **Resolución:** `proyecto.md` reescrito con rutas existentes, régimen pre-1.0, precedencia
  documental, estado real (sin E2E) y protocolo de trabajo. No se conservan las rutas falsas.

### D-002 — iFlow: `iflow.engine.kind` y exclusividad de motores

- **Severidad:** Alta (P0).
- **Estado:** **Corregido** (documentación y código, 2026-11-04).
- **Evidencia (baseline):**
  - `docs/prompts/diseno/storage.md` presentaba `iflow.engine.kind` como selector vigente y la
    instalación como exclusiva.
  - `src/spigot-host/src/main/resources/defaults/config.yml` todavía contenía
    `iflow.engine.kind: vm`.
  - `src/iflow-firewall/build.gradle` se describía como «alternativa mutuamente excluyente a
    iflow-vm».
  - `src/iflow/README.md` decía «primer backend disponible en orden de registro».
  - `src/host/.../ConfigLoader.java:125,159` todavía lee `iflow.engine.kind`.
- **Norma vigente:** ADR-032: los tres motores pueden coexistir; el hub `iflow` activa uno solo y
  la selección vive en `config/iflow/config.yml`. ADR-034: no hay rutas ni claves legacy.
- **Resolución documental:** `storage.md` reescrito (hub, layout canónico, sin `engine.kind`);
  `src/iflow/README.md` alineado a la prioridad explícita; `ADR.md` con la prioridad
  `iflow-firewall` → `iflow-vm` → `iflow-wasm`; descripción de `iflow-firewall` corregida.
- **Resolución de código (P0.3):** `ConfigLoader` y `HostConfig` ya no tienen `Engine` ni
  `iflow.engine.kind`; los defaults de `spigot-host`/`fabric-host` tampoco lo declaran.
  `SuiteRuntime.resolveRouter()` exige `IflowHub` y no acepta un `Router` suelto. La wiki quedó
  actualizada en P0.8b.

### D-003 — Especificaciones con contrato legacy y versiones `2.x`

- **Severidad:** Alta (P0).
- **Estado:** **Corregido** (documentación y código, 2026-11-04).
- **Evidencia (baseline):**
  - `docs/prompts/especificaciones/commands-v2.md` documentaba `commands.yml` en la raíz del data
    folder, prioridad de la ruta canónica y migración de `base-name` («se elimina en F8»).
  - `docs/prompts/especificaciones/config-schema-v2.2.md` afirmaba que el host parsea el layout
    plano del editor (`config.yml`, `channels/`, `sync/tcp-udp.yml`) y que el motor «consumirá»
    el grafo.
  - `docs/prompts/especificaciones/extension-v1.md` fijaba `minSuiteVersion: "2.1.0"`.
- **Norma vigente:** ADR-034: base `1.0`, `config-version: 1.0`, sin migración en runtime; el
  layout de runtime es el de `storage.md`; el grafo del editor se valida, no se ejecuta.
- **Resolución documental:** las tres especificaciones tienen cabecera de estado/autoridad, base
  `1.0`, ruta canónica única, `config-version: "1.0"` en los ejemplos y `minSuiteVersion: "1.0.0"`.
  `config-schema-v2.2.md` ahora separa el ZIP del editor del layout de runtime con una tabla de
  correspondencia.
- **Resolución de código (P0.2):** `ExtensionMetadata` y los `build.gradle` de módulo quedaron en
  `1.0.0`/`>= 1.0.0`; los 29 YAML de `src/*/src/main/resources` (todos salvo `plugin.yml`)
  declaran `config-version: 1.0`. `ConfigVersion`/`ConfigVersionException` validan la clave y
  fallan con el mensaje contractual cuando falta. Los `rules.yml` con raíz de lista quedan para
  P0.3 porque ese esquema no admite la clave raíz.

### D-004 — Código con deuda P0 que contradice la documentación

- **Severidad:** Alta (P0).
- **Estado:** **Corregido (código, 2026-11-04)**. P0.2 corrigió versiones y `config-version`;
  P0.3 eliminó las rutas planas, `iflow.engine.kind`, el fallback de `Router` y el esquema de
  reglas con raíz de lista.
- **Evidencia (baseline):**
  - `src/host/.../HostPaths.java:66-85`: `suiteConfig`, `suiteMessages` y `suiteCommands` aceptan
    `config.yml`, `messages.yml` y `commands.yml` en la raíz del data folder.
  - `src/suite-runtime/.../DefaultConfigInstaller.java:97-120`: copia archivos legacy a la ruta
    canónica y registra «Config migrated».
  - `src/host/.../ConfigLoader.java:125,159`: lee `iflow.engine.kind`.
  - `src/sync-common/.../SyncConfigPaths.java` y `src/sync-socket/.../SocketConfig.java`:
    siguen aceptando las rutas planas `sync/*.yml` como fallback.
  - `src/extension-api/.../ExtensionMetadata.java:36`: `minSuiteVersion = "2.1.0"`.
  - Varios `build.gradle` de módulo (`gtranslate`, `ltranslate`, `logger`, `transport`,
    `chattranslator`, `web-editor`) declaran `2.1.0` o `>=2.1.0`.
- **Impacto:** en el baseline, la documentación describía el contrato correcto mientras el
  runtime aceptaba parte del esquema viejo.
- **Resolución:** P0.2 eliminó las versiones `2.x` y añadió la validación de `config-version`.
  P0.3 retiró las rutas planas, `iflow.engine.kind`, el fallback de `Router` y el esquema de
  reglas legacy. Los tests ahora verifican que las rutas antiguas se ignoran y que `rules.yml`
  exige un mapa con `config-version`.

### D-005 — Enlaces rotos y READMEs ausentes

- **Severidad:** Media (P1).
- **Estado:** **Corregido**.
- **Evidencia (baseline):**
  - `README.md:381` enlazaba a `CHANGELOG.md` (inexistente).
  - `docs/README.md:10-11,40` enlazaba a `RELEASE_NOTES.md` y `../CHANGELOG.md` (inexistentes).
  - Cinco READMEs de módulo enlazaban a `../sync-tcpudp/` (el módulo real es `sync-socket`).
  - Faltaban READMEs de `core-render`, `suite-runtime`, `iflow-firewall`, `web-editor` y
    `fixtures`.
- **Resolución:** el enlace a `CHANGELOG.md` se sustituyó por la referencia a Git; las filas de
  `RELEASE_NOTES.md`/`CHANGELOG.md` se retiraron y se añadió `AUDITORIA.md`; `sync-tcpudp` se
  reemplazó por `sync-socket`; se crearon los cinco READMEs. El checker local de enlaces devuelve
  **0 enlaces rotos**.

### D-006 — Índice de prompts incompleto

- **Severidad:** Baja (P2).
- **Estado:** **Corregido**.
- **Evidencia (baseline):** `docs/prompts/README.md` no indexaba `philosophy.md`, `storage.md`,
  `chattranslator.md` ni `metrics.md`, y la tabla de skills omitía `@juzgar-rubrica`.
- **Resolución:** índice y tabla de skills completados; se aclara que `v2`/`v2.2`/`v1.0` son
  revisiones de esquema, no versiones de la suite.

### D-007 — `PLAN.md` acumulaba historial cerrado

- **Severidad:** Media (P1).
- **Estado:** **Corregido**.
- **Evidencia (baseline):** cabecera con una cadena de «Antes: …» desde 2026-07; notas de estado
  de 2026-10-23, 24, 27, 29 y 31; sección «Fixes de Inconsistencia» con ítems ya cerrados;
  «Estado real del proyecto (2026-10-04; actualizado 2026-10-23)» duplicado y desactualizado.
- **Resolución:** cabecera condensada; notas históricas reemplazadas por un bloque de estado
  actual; sección de fixes cerrada eliminada; snapshot duplicado reemplazado por una declaración
  de lo verificado y lo desconocido; decisiones pendientes y próxima acción actualizadas. El
  historial completo sigue en Git.

### D-008 — ADR con contenido obsoleto y contradicciones internas

- **Severidad:** Alta (P0).
- **Estado:** **Corregido**.
- **Evidencia (baseline):**
  - La cabecera «## iFlow» enumeraba targets `LOG`, `DROP`, `REJECT`, `REDIRECT`, `RATE-LIMIT`,
    contradiciendo ADR-031 (`DELIVER`, `CANCEL_EVENT`, `ERROR`).
  - ADR-034 ejemplificaba el contrato con `1.5`, `2.5`, `3.0`, pero `SemVer.parse` exige
    `x.y.z`.
  - ADR-032 no fijaba una prioridad de fallback; remitía al orden de registro.
- **Nota:** el ADR **no** contiene planes, fases ni roadmaps: su sección «Fases y estado» delega
  expresamente en `PLAN.md`. El problema era contenido obsoleto y ambigüedad, no invasión de
  roadmap.
- **Resolución:** cabecera iFlow alineada a ADR-031; contrato descrito como intervalo cerrado
  `[1.0.0, host]` con SemVer completo; fallback con prioridad explícita y referencia a test.

### D-009 — Fallback de iFlow dependiente del orden de registro

- **Severidad:** Media (P1).
- **Estado:** **Corregido** (documentación, código y test).
- **Evidencia:** `src/iflow/.../IflowBackendManager.java:60-69` recorría `discovered` y elegía el
  primer backend disponible; `RuntimeServiceRegistry` usa `LinkedHashMap`, por lo que el resultado
  dependía del orden de activación. ADR-032 no definía prioridad.
- **Resolución documental:** ADR-032, `src/iflow/README.md` y `storage.md` fijan la prioridad
  `iflow-firewall` → `iflow-vm` → `iflow-wasm`.
- **Resolución de código:** `IflowBackendManager` aplica `FALLBACK_PRIORITY` de forma explícita;
  `IflowHubTest.fallbackUsesExplicitPriorityRegardlessOfRegistrationOrder` registra `iflow-vm`
  antes que `iflow-firewall` y verifica que el hub elige igualmente `iflow-firewall`.

### D-010 — Contrato y SemVer: ejemplos inválidos y techo del host

- **Severidad:** Media (P1).
- **Estado:** **Corregido** (documentación y código ya alineados).
- **Evidencia:** ADR-034 citaba `1.5`, `2.5`, `3.0`; `SemVer.parse` solo acepta `x.y.z`;
  `ModuleGraph` rechaza contratos superiores al host `2.1.0` (`Environment.java:26`).
- **Resolución:** ADR-034 ahora define `[1.0.0, host]`, exige SemVer completo y aclara que
  «contrato exacto» significa base mínima. `ModuleGraph` ya implementa la aceptación de `1.0.0` y
  el rechazo de `0.9.0`; `ModuleGraphTest` cubre ambos casos. No se requiere cambio de código.

---

## 4. Cambios aplicados en esta ronda

### 4.1 Baseline de código (commit `c5992230`)

- `src/coretranslator/`: módulo legacy, activador, hook Bukkit, expansión PAPI `%cot_*`,
  placeholders, servicios SPI, README y 13 tests.
- `ModuleGraph`: contrato `[1.0.0, host]`; tests de aceptación/rechazo.
- `ADR.md` (ADR-035), `PLAN.md`, `README.md` raíz, `src/README.md` y `settings.gradle`.

### 4.2 Documentación (esta ronda)

Commits: `0a7550ab` (contexto, storage y contratos), `9ffc0230` (índices, enlaces y READMEs) y
`34ceb7f9` (informe). El cierre de D-009 se registra junto con el código y su test.

| Archivo | Cambio |
|---|---|
| `docs/prompts/contexto/proyecto.md` | Reescritura: rutas reales, régimen pre-1.0, precedencia, estado sin E2E |
| `docs/prompts/diseno/storage.md` | Reescritura: layout canónico único, sin `engine.kind` ni rutas legacy |
| `docs/prompts/especificaciones/commands-v2.md` | Ruta única, sin migración, `config-version` |
| `docs/prompts/especificaciones/config-schema-v2.2.md` | ZIP del editor vs runtime, base 1.0, `config-version` |
| `docs/prompts/especificaciones/extension-v1.md` | `minSuiteVersion 1.0.0`, `config-version`, cabecera de autoridad |
| `docs/prompts/diseno/manager-v2.md` | Precedencia ADR-027, layout de `library/`, commit/activación separados |
| `docs/prompts/diseno/iflow-cpu-design.md` | Nota histórica corregida (SpEL, hub, targets v3) |
| `docs/workdir/ADR.md` | iFlow v3, contrato `[1.0.0, host]`, prioridad de fallback |
| `docs/workdir/PLAN.md` | Poda de historial, estado actual y fallback de router como deuda |
| `docs/prompts/README.md` | Índice completo y `@juzgar-rubrica` |
| `docs/README.md`, `README.md` | Enlaces corregidos; referencias iFlow, versiones y presets alineadas |
| `src/{core-render,suite-runtime,iflow-firewall,web-editor,fixtures}/README.md` | READMEs nuevos |
| `src/{core-api,messages,sync-bus,sync-http,transport}/README.md` | `sync-tcpudp` → `sync-socket` |
| `.opencode/skills/auditar-iflow/SKILL.md` | Spec v3 como contrato; design como histórico |
| `.opencode/skills/auditar-modulo/references/manager.md` | `repo.yml`, `library/status`, comandos actuales |
| `src/iflow/README.md` | Prioridad de fallback, VM v2/v3, deuda del router |
| `src/iflow-firewall/build.gradle` | Descripción sin «mutuamente excluyente» |

### 4.3 Orden de ejecución

| Orden | Cambio | Depende de |
|---|---|---|
| 1 | Baseline: CoreTranslator + contrato `[1.0.0, host]` + docs pendientes (`c5992230`) | Autorización A2 |
| 2 | Contexto y `storage.md` (rutas y régimen) | 1 |
| 3 | Especificaciones (commands, extension, config schema) | 2 |
| 4 | ADR, PLAN y skills | 2, 3 |
| 5 | Índices, enlaces y READMEs de módulo | 2–4 |
| 6 | Informe final en `AUDITORIA.md` | 2–5 |
| 7 | D-009 (prioridad de fallback + test) | 1–6 |
| 8 | P0.2/P0.3 de código (versiones, `config-version`, purga legacy) | 7 + autorización específica |
| 9 | P0.8b (wiki) | 8 |

---

## 5. Validación

- **Enlaces locales:** checker recursivo sobre todos los `*.md` versionados → **0 rotos**.
- **Rutas citadas:** las cinco rutas alucinadas de D-001 fueron sustituidas por rutas existentes.
- **Términos prohibidos:** se revisó `iflow.engine.kind`, `config/manager/`, `manager/config/`,
  `sync-tcpudp`, `RELEASE_NOTES.md`, `CHANGELOG.md` y `2.1.0` en la documentación del alcance.
  Quedan menciones históricas explícitas (p. ej. la nota de `iflow-cpu-design.md`); el código ya
  no acepta el esquema viejo.
- **Código de producción:** P0.2/P0.3 modificaron `host`, `suite-runtime`, `sync-common`,
  `textformatter`, `chattranslator`, `iflow-firewall` y los fixtures de `loadtest`. La suite
  `./gradlew test --no-daemon` pasó completa.
- **Gate completo:** `./gradlew build checkArchitecture checkLocks --no-daemon` pasó el
  2026-11-04 (36 módulos, 44 lockfiles, 0 sin lockfile).

---

## 6. Riesgos y límites

1. **La wiki quedó alineada, pero no es normativa.** P0.8b eliminó `iflow.engine.kind` como
   estado vigente, la exclusividad de motores, comandos inexistentes y la duplicación de ADRs.
   Sigue supeditada al ADR, a las especificaciones y al código real.
2. **Las rutas planas se ignoran, no se migran.** `HostPaths`, `DefaultConfigInstaller`,
   `ConfigLoader`, `SyncConfigPaths`, `ChannelConfig`, `ChatTranslatorConfig`, `BackendConfig` y
   `iflow-firewall` usan solo el layout canónico; los tests verifican que el camino viejo no se
   lee. `iflow-firewall` conserva SpEL como módulo legacy independiente, no como shim.
3. **No hay E2E.** Ni Spigot/Paper, ni Fabric, ni Velocity, ni `CoreTranslator` con PlaceholderAPI
   se han probado en un servidor real. El smoke test de Paper 1.20.6 no es E2E. El E2E pertenece a
   la fase posterior a `1.0.0`.
4. **Los READMEs nuevos son CodeGuide, no contratos.** Describen la estructura observada; no
   sustituyen a las especificaciones ni a los ADR.
5. **`fixtures/` no tiene consumidores en el código.** Su README lo declara como directorio de
   datos; si se convierte en módulo, necesita build propio y entrada en `settings.gradle`.

---

## 7. Próximos pasos

1. ~~Ejecutar P0.2~~ **Hecho (2026-11-04):** versiones a `1.0.0`, `config-version: 1.0` en los
   29 YAML de runtime, `minSuiteVersion: "1.0.0"` y validadores con test de regresión.
2. ~~Ejecutar P0.3~~ **Hecho (2026-11-04):** eliminados `iflow.engine.kind`, `HostConfig.Engine`,
   rutas planas, migración de `DefaultConfigInstaller` y el fallback de `Router`; `rules.yml`
   exige mapa con `config-version` y las rutas antiguas se ignoran con tests de regresión.
3. ~~Ejecutar P0.8b~~ **Hecho (2026-11-04):** 17 páginas de `docs/wiki/` alineadas con el hub,
   `1.0.0`/`1.0`, el layout canónico y los comandos reales; `14-ADR.md` quedó como índice del
   ADR canónico.
4. Retomar W3.5 (paridad `.flow` v3/VM) y después V6 (compilador C).
5. E2E real solo después de `1.0.0`, incluido el smoke test de `CoreTranslator` con
   Bukkit/Paper + PlaceholderAPI.

---

## 8. Decisiones de la ronda

- **A1:** `AUDITORIA.md` se reescribe como informe final; no se crea `PSEUDOAUDITORIA.md`.
- **A2:** se commiteó primero el baseline de CoreTranslator + contrato `>= 1.0.0` + docs
  pendientes, sin push (`c5992230`).
- **A3:** la auditoría documental fue solo documentación; el cierre de D-009 se hizo después con
  código y test. El resto de arreglos de código van a P0.2/P0.3.
- **A4:** P0.2 se ejecutó con reset de versiones, `config-version` en los YAML de runtime y
  `ConfigVersion` como contrato único de validación.
- **A5:** P0.3 eliminó el esquema de configuración legacy y cerró P0.5/P0.7: tests reescritos,
  rutas canónicas, hub obligatorio y gate completo en verde.

---

## 9. Referencias

- `docs/workdir/ADR.md`: ADR-001 (SpEL), ADR-027 (Manager), ADR-031 (política v3), ADR-032 (hub
  iFlow), ADR-034 (régimen pre-1.0), ADR-035 (CoreTranslator).
- `docs/workdir/PLAN.md`: P0.2, P0.3, P0.6, P0.8, P0.8b, deuda conocida.
- `docs/prompts/diseno/storage.md`: layout canónico.
- `docs/prompts/especificaciones/`: contratos de `commands.yml`, `extension.yml` y editor.
- `ModuleGraphTest`: aceptación de `1.0.0` y rechazo de `0.9.0`.


---

## 10. Addendum — P0.8b (wiki)

**Estado:** cerrado el 2026-11-04. `docs/wiki/` ya no se presenta como contrato ni como estado
vigente alternativo.

- `01-Introduction.md`: reescrita como introducción de usuario; elimina `31 modules`,
  `production-ready`, rutas Fabric falsas y comandos `/suite module ...`.
- `02-Configuration.md`: layout canónico, `manager/repo.yml`, `packages.list`, sin
  `iflow.engine.kind`, sin comando de módulos inventado y sin hot-reload ficticio.
- `03-Channels.md`, `04-Translation.md`, `05-iFlow-Rules.md`, `06-Sync.md`: hub y backends
  coexistentes, configuración real, límites reales de caché/timeout y ausencia de recarga
  genérica.
- `07-Web-Editor.md`: el ZIP del editor es un proyecto con rutas planas, no el layout de runtime.
- `08-Commands.md`, `10-Module-Manager.md`: comandos reales, permiso `textformatter.suite.admin`
  y `manager/repo.yml`.
- `09-Developer-Guide.md`, `10-API-Reference.md`: base `1.0.0`, sin Spring, sin Maven Central y
  con el gate real.
- `11-Release-Notes.md`, `12-Migration-Guide.md`, `13-FAQ.md`: régimen pre-1.0, sin migraciones,
  sin variables de entorno y sin comandos inexistentes.
- `14-ADR.md`: índice que remite a `docs/workdir/ADR.md`; no duplica ADRs ni decisiones
  históricas.
- `15-Contributing.md`, `16-Glossary.md`: reescritas para Java/Gradle/JUnit reales, el hub iFlow,
  `CoreTranslator` y el régimen pre-1.0.

**Validación:** `git diff --check` sin salida; checker de enlaces locales sobre `docs/wiki/*.md`
con **0 enlaces rotos**. No se ejecutó E2E real; sigue siendo posterior a `1.0.0`.

---

## 11. Addendum — P0.1 y avance de P0.4 (2026-11-05)

**Estado:** P0.1 cerrado; P0.4 parcial, sin declarar cerrado.

- Inventario completo en `docs/workdir/P0.1-inventory.md`: 34 YAML con `config-version: 1.0`,
  `plugin.yml` excluido, 0 aliases legacy en producción, 0 lectores de rutas planas, 0 shims y
  `CoreTranslator` como único módulo `@Deprecated` de la base.
- P0.4 eliminó los aliases de `IflowConfig` (`firewall`/`vm`/`wasm`, `default-backend`, `fallback`),
  el `transform` de cadena en `rules.yml`, los constructores de compatibilidad sin uso de
  `RoutingResult`, `DispatchReport`, `HttpSink`, `ChatTranslator` y `WasmContract`, el alias
  `ExecutionContext.reset()`, `MessagesCatalog.getAllMessages()` y los placeholders `%s`.
- Se corrigieron las versiones `2.1`/`3.0` que sobrevivían en `HttpTransport`, `TextFormatterModule`
  y la UI del editor web; el manifiesto exportado usa `suite-version: 1.0.0`. También se retiraron
  las etiquetas `F7+` de las rutas y validaciones del editor, y el builder `Rule.transform(String)`
  que ya no leía el loader.
- Se corrigieron Javadocs que describían rutas heredadas inexistentes (`BackendConfig`,
  `ChatTranslatorConfig`, `SocketConfig`, `WebSocketSyncConfig`) y las menciones de alias en
  `storage.md`, `ADR-032` y `src/iflow/README.md`.
- Deuda que permanece: entradas `2.1.0-SNAPSHOT` en `gradle/verification-metadata.xml`, revisión
  final de P0.4 y coherencia documental de P0.8. No se declara E2E ni estabilidad.

**Validación:** gate completo `./gradlew build checkArchitecture checkLocks --no-daemon` en verde;
`npm run check` del editor web en verde; regresiones de `IflowConfig`, `RuleConfigLoader`,
`MessagesCatalog`, `ExecutionContext` y `HttpTransport` añadidas/actualizadas.
