# project-metrics

Herramienta oficial de **renderizado del estado del proyecto**. Reproduce, en
local y desde cero, todas las gráficas que describen cómo ha evolucionado
TextFormatter Suite: métricas del repositorio (git) y de la capa narrativa
(las sesiones de asistencia), uniendo ambas por fecha y por commit.

El objetivo es que cualquiera pueda clonar el repositorio y regenerar las mismas
imágenes que se muestran en el `README.md`, sin depender de servicios externos.

## Requisitos

- Python 3.11+
- Pillow (`python3 -m pip install pillow` en un entorno virtual)
- `git` en el PATH

No usa matplotlib ni pandas: el render es propio (Pillow), que da control total
del nivel de detalle de cada gráfica.

## Uso

```bash
cd tools/project-metrics
./run_all.sh
```

Eso ejecuta, en orden:

| Paso | Script | Qué hace |
|---|---|---|
| 1 | `extract_git.py` | Un snapshot por día con commit (días vacíos rellenados). Métricas de estado y de actividad, por módulo y total. |
| 2 | `extract_chat.py` | Parsea `chat.log` y produce la tabla de eventos y las métricas diarias de la narrativa. |
| 3 | `join_commits.py` | Resuelve los `git commit` vistos en el chat contra el histórico real. |
| 4 | `scan_tools.py` | *(opcional)* Analizadores reales (semgrep/gitleaks/PMD/ESLint) → `data/tools/<fecha>.json`. |
| 5 | `judge.py` | Evidencia y veredictos de las familias 3 y 11: genera `data/evidence/` e importa `judge_scores.csv` → `data/judge/<fecha>.json`. |
| 6 | `maturity.py` | Rúbrica de madurez: 12 familias, 0–100, por módulo y por corte. |
| 7 | `render_pil.py` | Paneles `panel_git`, `panel_chat`, `panel_actividad` y gráficas por módulo. |
| 8 | `render_extra.py` | `heatmap_modulos` (módulo × tiempo) y `crecimiento` (primer vs último corte). |
| 9 | `render_maturity.py` | `rubrica_familias` (evolución por familia) y `rubrica_matriz` (módulo × familia). |

## La capa narrativa: `chat.log`

La entrada de la narrativa es **un único fichero `chat.log`**: la exportación de
las sesiones de OpenCode en formato **JSONL (una sesión por línea)**. Ese fichero
**no se sube a GitHub** (de hecho, la regla `*.log` del `.gitignore` de la raíz
ya lo ignoraría si estuviera dentro del repo). Por defecto se busca en
`~/chat.log`; se puede cambiar con `PM_CHAT_LOG`.

Si tienes las sesiones exportadas como ficheros `.json` sueltos, puedes unirlas:

```bash
python3 consolidate.py -o ~/chat.log /ruta/con/los/exports/
python3 consolidate.py -o ~/chat.log sesion1.json sesion2.json
```

El extractor acepta JSONL, un JSON único o un JSON en lista. Sin `chat.log`, el
paso 2 y la columna de narrativa quedan vacíos; el resto (git) funciona igual.

## Configuración (variables de entorno)

| Variable | Por defecto | Descripción |
|---|---|---|
| `PM_REPO` | raíz del repo | Repositorio git a analizar. |
| `PM_CHAT_LOG` | `~/chat.log` | Exportación de los chats de OpenCode. |
| `PM_DATA` | `./data` | CSV/JSONL intermedios. |
| `PM_IMG` | `<repo>/docs/metrics` | Imágenes PNG generadas. |

## Métricas (≈30)

**Repositorio (git)** — 15 métricas, disponibles en *todos* los commits:
`src_loc`, `src_files`, `loc_all`, `files_all`, `public_api`, `comment_lines`,
`comment_ratio`, `test_loc`, `test_files`, `test_ratio`, `todo`, `stubs`,
`commits`, `added_lines`, `deleted_lines`.

Además se extraen **señales** que alimentan la rúbrica de madurez:
`interfaces`, `concurrency`, `error_handling`, `bare_catch`, `timeouts`,
`ports`, `test_annotations`, `doc_loc`, `doc_files`.

**Narrativa (chat)** — 15 métricas, disponibles desde el primer chat (23-ago):
`user_prompts`, `assistant_texts`, `reasoning_blocks`, `tool_calls`,
`tool_errors`, `edits`, `reads`, `commands`, `searches`, `git_commits`,
`compactions`, `tokens_input`, `tokens_output`, `tokens_reasoning`, `cache_read`.

### Estado vs actividad

Las métricas de **estado** (LOC, archivos, comentarios, tests…) persisten en el
tiempo y se arrastran hacia adelante en los días sin commit. Las de **actividad**
(commits, líneas, tokens, llamadas a tools…) valen 0 los días sin trabajo. Esta
distinción es clave: sin ella, los picos de actividad se convierten en mesetas
falsas.

### Filtros

Se excluyen rutas generadas o vendidas (`node_modules/`, `dist/`, `build/`,
`out/`, `target/`, `vendor/`, `.gradle/`…) y ficheros como `.min.*`, `*.map`,
`*.bak`, `*.lock` o `package-lock.json`. Sin este filtro, un `node_modules`
commiteado (caso real, 26-sep) hinchaba el conteo a ~600.000 líneas.

## Rúbrica de madurez (12 familias)

`maturity.py` puntúa el proyecto de 0 a 100 en 12 familias, por **módulo** y por
**corte diario**, a partir de las señales de `git_long.csv`. Es una heurística
**determinista** y transparente; donde existe salida de una herramienta real
(`data/tools/<fecha>.json`), esa sustituye la aproximación.

| # | Familia | Señal principal (proxy determinista) |
|---|---|---|
| 1 | Calidad de código | defectos por kLOC (`stubs`, `bare_catch`, `todo`) — PMD/SpotBugs+ESLint si están |
| 2 | Cobertura de tests | `test_ratio` y anotaciones `@Test`/`describe(` por kLOC |
| 3 | Documentación | `doc_loc`/`src_loc`, ratio de comentarios, nº de `*.md` |
| 4 | Seguridad | manejo de errores vs `catch` vacíos — Semgrep/OWASP si están |
| 5 | Secretos | 100 salvo fugas detectadas por **gitleaks** |
| 6 | Dependencias | wrapper de Gradle, `verification-metadata.xml`, lockfiles |
| 7 | Concurrencia | `synchronized`/`Concurrent*`/`Atomic*` por kLOC |
| 8 | Estabilidad | manejo de errores + `timeout`/`retry`/`backoff` |
| 9 | Rendimiento | timeouts y concurrencia (JMH/Gatling pendientes) |
| 10 | Arquitectura | puertos/adaptadores (`*Port`, `*Adapter`, `*Spi`…), `interface` |
| 11 | Implementación real | inverso de `stubs` + superficie de API pública |
| 12 | Integración | API pública + puertos + ratio de tests |

Las familias **5 (secretos)** y **6 (dependencias)** son de nivel proyecto: el
mismo valor se aplica a todos los módulos. Las puntuaciones de densidad sobre
códigos muy pequeños pueden saturar (base monolítica inicial); es un artefacto
conocido y documentado de la heurística.

## Analizadores reales (opcional)

Las familias 1 (calidad), 4 (seguridad) y 5 (secretos) se aproximan de forma
determinista; si hay herramientas disponibles, `scan_tools.py` ejecuta las
reales y `maturity.py` sustituye su proxy:

| Herramienta | Familia | Qué mide |
|---|---|---|
| `gitleaks` | 5 · Secretos | fugas en **todo el histórico** git, asignadas a la fecha del commit |
| `semgrep` | 4 · Seguridad | hallazgos de seguridad por snapshot (peso: ERROR=4, WARNING=1) |
| `pmd` | 1 · Calidad | violaciones Java por snapshot (árbol completo; cubre el layout pre-split) |
| `eslint` | 1 · Calidad | errores/avisos JS del propio `web-editor` (su `eslint.config.js`); avisos ×0.25 |

Rutas por entorno o autodetección: `PM_GITLEAKS`, `PM_SEMGREP`, `PM_PMD`,
`PM_ESLINT`, `PM_ESLINT_MODULES`, `PM_JAVA_HOME`. La raíz del toolchain (y de los
temporales) se cambia con `PM_TOOLS` (por defecto `/tmp/opencode`). Son
**opcionales**: sin ellas la rúbrica usa los proxies y el resto del pipeline no
cambia.

```bash
PM_SCAN=1 ./run_all.sh                 # con analizadores si están
python3 scan_tools.py --only semgrep   # solo semgrep
python3 scan_tools.py --dates 2026-10-18
```

## Juez (familias 3 y 11)

Las familias **3 (documentación)** y **11 (implementación real)** son
cualitativas y difíciles de capturar con un proxy: lo que importa es la
*sincronía* de la documentación con el código y el *realismo* de lo implementado,
no una cifra. El resto de familias sí son deterministas.

El juicio lo hace el agente de OpenCode, no el programa. `judge.py` aporta solo
las dos mitades reproducibles:

```
data/evidence/<fecha>.<familia>.md   # evidencia (determinista, ~10 líneas)
judge_scores.csv                     # veredicto, commiteado (2 por corte)
data/judge/<fecha>.json              # veredicto materializado, lo que lee maturity.py
```

La evidencia se construye desde `git_long.csv` y el histórico de commits, **sin
usar nunca el `chat.log`**. Ejemplo real (familia 3, corte 2026-09-06) — la línea
`modulos con doc=0` es la que más discrimina:

```
CORTE 2026-09-06 - proyecto TextFormatter Suite
EVIDENCIA:
doc_loc=2664 doc_files=14 src_loc=20076 doc/src=0.13
comment_lines=1665 comment/src=0.08
modulos con doc=0: 46 de 48
ficheros .md tocados hoy (2): docs/PLAN.md, docs/historial/research-A5-...md
doc por modulo (top): docs=2336.0, (root)=328.0
commits de hoy: docs: actualiza PLAN.md con resultados Fase 2 (A1-A5)
```

La familia 11 añade además el análisis estático (`semgrep=N hallazgos`,
`pmd=N violaciones`), que sí varía entre cortes (PMD: 95 → 1536).

### Cómo se lanza

El paso de puntuar está empaquetado como **skill de OpenCode**
(`.opencode/skills/juzgar-rubrica/`). Un humano lo pide cuando quiere —
`@juzgar-rubrica` — y el agente hace el resto: genera la evidencia, lee los 70
paquetes, puntúa con la escala obligatoria, valida el CSV con
`scripts/validate_scores.py` e importa.

Equivalentemente, a mano:

```bash
python3 judge.py                                     # 1. genera data/evidence/
#  2. puntuar los paquetes y escribir judge_scores.csv
python3 judge.py --import-scores judge_scores.csv     # 3. -> data/judge/<fecha>.json
```

Formato de `judge_scores.csv` (commiteado, 2 líneas por corte):

```
date,family,score,reason,juez
2026-08-14,documentacion,15,doc/src=0.03 y 47 de 48 modulos con doc=0,opencode-agent
```

El inverso existe por si hay que regenerar el CSV desde una matriz ya
materializada: `python3 judge.py --export-scores judge_scores.csv`. El round-trip
es estable (importar y exportar devuelve el mismo fichero).

### Decisiones de diseño

- **El veredicto es de nivel proyecto.** En la matriz módulo × familia, la fila
  `(total)` es la única que lo lleva; las filas de módulo mantienen el proxy
  determinista. Puntuar cada módulo y corte no es tratable, y estas dos
  cualidades son propiedades de conjunto.
- **El veredicto se commitea como dato**, así que el pipeline es
  **determinista**: `run_all.sh` importa `judge_scores.csv` si existe y las
  imágenes se reproducen bit a bit. Recalcular los veredictos es el único paso no
  reproducible, y la columna `source` lo deja a la vista:
  `juez:opencode-agent`.
- **Sin `judge_scores.csv` no se rompe nada**: las familias 3 y 11 caen al proxy
  determinista. El juicio es una mejora, no un requisito.

Resultado sobre los 35 cortes: documentación 15→72, implementación 53→76.

## Salidas

En `PM_IMG` (por defecto `docs/metrics/`):

- `panel_git.png` — 15 métricas del repositorio.
- `panel_chat.png` — 15 métricas de la narrativa.
- `panel_actividad.png` — correlación git ↔ chat.
- `heatmap_modulos.png` — LOC por módulo y semana.
- `crecimiento.png` — primer corte vs último, con factor de crecimiento.
- `modules_src_loc.png`, `modules_test_loc.png` — por módulo.
- `rubrica_familias.png` — las 12 familias de madurez en el tiempo (0–100).
- `rubrica_matriz.png` — matriz módulo × familia en el último corte.

Los paneles marcan 8 **hitos** (inicio, chats base, auditorías, split modular…)
y sombrean dos **eras**: el monolito `src/suite` y el endurecimiento de iFlow.

## Notas

- Los días sin commit se rellenan para que la línea temporal sea continua.
- La narrativa solo cubre desde el 23-ago-2026 (primer chat). El proyecto
  empieza el 16-ago-2026.
- El chat es **evidencia**, no el eje del gráfico: el eje es el tiempo.
