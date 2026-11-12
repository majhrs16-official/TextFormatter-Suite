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
  canales, grafo, traductores y sinks desde sus rutas canónicas y vuelca solo lo
  editado. Un archivo no tocado conserva sus bytes; un archivo opaco o diferido
  (YAML inválido, binario, ROM o base de paquetes) nunca se sobrescribe.
- **CST de YAML** (`js/yamlCst.js`): parser y editor que conserva comentarios,
  orden de claves, comillas, sangría y saltos de línea (LF/CRLF) al exportar.
  Aplica solo el delta entre el valor hidratado y el estado actual, y proyecta
  el modelo para no inventar claves ausentes en el archivo. Si una edición no
  se puede representar sin perder formato (por ejemplo, convertir una colección
  con comentarios internos en escalar), falla con `CstError` y la exportación
  aborta sin escribir ningún archivo. Los archivos nuevos se serializan con
  `js/yaml.js`.
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
tests/unit/         stateStore, model, validate, versionLabels, zip, suiteArchive, yamlCst, suiteMapping
tests/integration/  func, interact, click, chain, undo, diffing, bind
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
  archivo opaco o diferido (YAML inválido, binario, ROM o base de paquetes) se
  conserva y nunca se sobrescribe; la exportación lo reporta como aviso.
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
