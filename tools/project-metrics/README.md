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
| 4 | `render_pil.py` | Paneles `panel_git`, `panel_chat`, `panel_actividad` y gráficas por módulo. |
| 5 | `render_extra.py` | `heatmap_modulos` (módulo × tiempo) y `crecimiento` (primer vs último corte). |

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

## Salidas

En `PM_IMG` (por defecto `docs/metrics/`):

- `panel_git.png` — 15 métricas del repositorio.
- `panel_chat.png` — 15 métricas de la narrativa.
- `panel_actividad.png` — correlación git ↔ chat.
- `heatmap_modulos.png` — LOC por módulo y semana.
- `crecimiento.png` — primer corte vs último, con factor de crecimiento.
- `modules_src_loc.png`, `modules_test_loc.png` — por módulo.

Los paneles marcan 8 **hitos** (inicio, chats base, auditorías, split modular…)
y sombrean dos **eras**: el monolito `src/suite` y el endurecimiento de iFlow.

## Notas

- Los días sin commit se rellenan para que la línea temporal sea continua.
- La narrativa solo cubre desde el 23-ago-2026 (primer chat). El proyecto
  empieza el 16-ago-2026.
- El chat es **evidencia**, no el eje del gráfico: el eje es el tiempo.
