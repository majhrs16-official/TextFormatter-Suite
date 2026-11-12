# web-editor — editor visual de configuración

> **Purpose**: aplicación web estática (HTML + JavaScript vanilla) para
> importar, editar, validar y exportar proyectos de configuración de
> TextFormatter Suite.

---

## 1. Responsibilities

- **Import/export** de proyectos (`textformatter-suite.zip`) con round-trip
  exacto panel → YAML → panel. El lector ZIP acepta STORE y DEFLATE con CRC,
  descriptor de datos y límites anti-bomba; el escritor actual usa STORE.
- **Import/export de la suite** desde la barra de herramientas, separado del
  modo proyecto. La importación acepta un `.zip` con `TextFormatter-Suite/` o
  los archivos en la raíz, muestra un resumen (archivos, módulos, mapeados,
  opacos, diferidos, texto extra y avisos) y exige confirmación antes de
  reemplazar el estado y borrar el historial. La exportación reutiliza el
  archivo importado como base, clona sus bytes y solo escribe lo editado.
- **Archivo de suite** (`js/suiteArchive.js`): normaliza rutas, detecta la raíz
  `TextFormatter-Suite/`, conserva binarios y archivos desconocidos bajo
  `config/`, ignora basura y excluye JARs, caché, journal, backups y temporales.
  Exporta con prefijo fijo y orden determinista.
- **Mapeo modelo ↔ suite** (`js/suiteMapping.js`): hidrata `config/suite/`,
  canales, grafo, traductores, sinks y la base de paquetes desde sus rutas
  canónicas y vuelca solo lo editado. Un archivo no tocado conserva sus bytes;
  un archivo opaco o diferido (YAML inválido, binario o ROM) nunca se
  sobrescribe.
- **CST de YAML** (`js/yamlCst.js`): parser y editor que conserva comentarios,
  orden de claves, comillas, sangría y saltos de línea (LF/CRLF) al exportar.
  Aplica solo el delta entre el valor hidratado y el estado actual, y proyecta
  el modelo para no inventar claves ausentes en el archivo. Si una edición no
  se puede representar sin perder formato (por ejemplo, convertir una colección
  con comentarios internos en escalar), falla con `CstError` y la exportación
  aborta sin escribir ningún archivo. Los archivos nuevos se serializan con
  `js/yaml.js`.
- **Base de paquetes** (`js/packageDb.js`): lector y escritor estricto de
  `manager/library/status`, que **no es YAML**. Reproduce el formato de bloques
  de `ManifestParser` (`manager-spi`): campos `Clave: valor` y una sección
  `Files:` con líneas `ruta<TAB>tamaño<TAB>sha256<TAB>dueño`, separadas por una
  línea en blanco. Conserva los campos desconocidos y la lista de archivos,
  normaliza dependencias y ordena por `name`, `version`, `channel` y `type`. Una
  base inválida queda opaca, no se hidrata y nunca se sobrescribe.
- **Vista Packages** (`js/packages.js`): lista, añade, edita y elimina entradas
  con una advertencia permanente, porque el archivo es la fuente de verdad de
  instalación del Manager. La edición valida el candidato con
  `Suite.packageDb.validate` antes de mutar el estado.
- **ROM de iFlow** (`js/rom.js`): valida el contenedor `.flow` v2 (magic,
  versiones, longitudes de sección, punto de entrada y trailer SHA-256) y el
  `.wasm` v1 (magic, versión y longitudes de sección). Calcula SHA-256 en
  JavaScript síncrono y mantiene el sidecar `policy.sha256` que exige
  `WasmPolicyRouter`. Una ROM inválida se rechaza y nunca sobrescribe los bytes
  importados.
- **Vista ROMs** (`js/roms.js`): lista, añade, reemplaza, clona y borra ROM.
  Los clones son entradas editables con ruta propia y, en `.wasm`, su propio
  sidecar; se exportan con la suite, pero el runtime solo carga las rutas
  canónicas `policy.flow` y `policy.wasm`.
- **Validación** contra el contrato del editor
  (`docs/prompts/especificaciones/config-schema-v2.2.md`) y contra las claves
  que consume el host.
- **Edición visual** de `config.yml`, canales, grafo de iFlow y backends de
  traducción.
- **Undo/redo, diffing y bindings** con tests de integración en `tests/`.

## 2. Non-responsibilities

- No es un backend: no sirve YAML desde un servidor ni escribe en el data
  folder del runtime.
- No ejecuta el pipeline ni valida permisos en tiempo de ejecución.
- No define el layout de disco: lo hace `docs/prompts/diseno/storage.md`.
- No es fuente de verdad de los esquemas: los contratos viven en
  `docs/prompts/especificaciones/`.

## 3. Structure

```text
index.html          entrada de la aplicación
js/                 estado, modelo, validación, UI y exportación
styles.css          estilos
tests/unit/         stateStore, model, validate, versionLabels, zip, suiteArchive, yamlCst, packageDb, rom, suiteMapping
tests/integration/  func, interact, click, chain, undo, diffing, bind, packages, roms
package.json        scripts de lint, format, test y check
```

## 4. Commands

```bash
cd src/web-editor
npm run check           # format:check + lint + unit
npm run test            # tests unitarios
npm run test:integration
```

Requiere Node.js >= 20.

## 5. Contract

- Las rutas planas del proyecto (`config.yml`, `channels/`, `sync/`) son del
  ZIP del editor, no del runtime. La correspondencia con `config/suite/`,
  `config/textformatter/`, `config/sync-*/`, etc. está en el schema y en
  `js/suiteMapping.js`.
- La importación de suite normaliza la raíz y no adivina layouts mixtos. Un
  archivo opaco o diferido (YAML inválido, binario o ROM) se conserva y nunca
  se sobrescribe; la exportación lo reporta como aviso.
- `manager/library/status` no es YAML. Se hidrata con `Suite.packageDb`; si el
  archivo no existe, `state.packages` es `null` y no se inventa. Un array vacío
  se exporta como archivo vacío; `null` nunca borra el archivo existente.
- La base de paquetes se reescribe solo si cambió respecto al baseline. Un
  archivo inválido se rechaza en la importación, queda opaco y la exportación
  avisa en lugar de pisarlo.
- Importar un proyecto plano o pegar YAML descarta la base de la suite: la
  siguiente exportación de suite parte del modelo actual, no de bytes viejos.
- Un archivo YAML existente se reescribe con el CST: se conservan comentarios,
  comillas, orden y saltos de línea, y solo cambia el delta editado. Si el CST
  no soporta la sintaxis del archivo (block scalars, anclas, alias, tags, claves
  complejas, documentos múltiples, tabs en la sangría o saltos mixtos), el
  archivo queda opaco y se preserva byte a byte.
- La exportación es atómica: `toSuite` calcula todas las escrituras y borrados
  antes de tocar el ZIP. Un error de formato en cualquier archivo deja el
  archivo de suite intacto.
- Todo `.yml` exportado declara `config-version: "1.0"`.
- `manifest.json` declara `suite-version: "1.0.0"` y la revisión del schema.
- Un archivo sin `config-version` se reporta como issue bloqueante antes de
  exportar; el runtime falla con
  `version de configuracion no identificada (archivo editado o corrupto?)`.
