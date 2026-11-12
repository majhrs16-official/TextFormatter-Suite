# web-editor — editor visual de configuración

> **Purpose**: aplicación web estática (HTML + JavaScript vanilla) para
> importar, editar, validar y exportar proyectos de configuración de
> TextFormatter Suite.

---

## 1. Responsibilities

- **Import/export** de proyectos (`textformatter-suite.zip`) con round-trip
  exacto panel → YAML → panel. El lector ZIP acepta STORE y DEFLATE con CRC,
  descriptor de datos y límites anti-bomba; el escritor actual usa STORE.
- **Archivo de suite** (`js/suiteArchive.js`): normaliza rutas, detecta la raíz
  `TextFormatter-Suite/`, conserva binarios y archivos desconocidos bajo
  `config/`, ignora basura y excluye JARs, caché, journal, backups y temporales.
  Exporta con prefijo fijo y orden determinista.
- **Mapeo modelo ↔ suite** (`js/suiteMapping.js`): hidrata `config/suite/`,
  canales, grafo, traductores y sinks desde sus rutas canónicas y vuelca solo lo
  editado. Un archivo no tocado conserva sus bytes; un archivo opaco o diferido
  (YAML inválido, binario, ROM o base de paquetes) nunca se sobrescribe.
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
tests/unit/         stateStore, model, validate, versionLabels, zip, suiteArchive, suiteMapping
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
- Todo `.yml` exportado declara `config-version: "1.0"`.
- `manifest.json` declara `suite-version: "1.0.0"` y la revisión del schema.
- Un archivo sin `config-version` se reporta como issue bloqueante antes de
  exportar; el runtime falla con
  `version de configuracion no identificada (archivo editado o corrupto?)`.
