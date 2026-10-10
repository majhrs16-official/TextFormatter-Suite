# Auditoría técnica del Module Manager — TextFormatter Suite

Repositorio analizado: [majhrs16-official/TextFormatter-Suite](https://github.com/majhrs16-official/TextFormatter-Suite) · Rama `main`

## A. Veredicto ejecutivo preliminar

La inspección inicial confirma que el repositorio contiene una implementación separada en tres módulos principales: `manager-spi`, `manager-core` y `manager-apt`. También existe una especificación específica de reingeniería en [`docs/NEW-MANAGER.md`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/main/docs/NEW-MANAGER.md), que debe utilizarse como referencia normativa, en lugar de inferir los requisitos únicamente de los nombres de las clases.

Los componentes identificados incluyen:

- `manager-spi`: contratos, descriptores, versiones, configuración de repositorios y parsers.
- `manager-core`: base de datos local de paquetes e instalación.
- `manager-apt`: repositorios, resolución de dependencias, adquisición de paquetes y adaptación al ciclo de vida de módulos.

La existencia de estos tres módulos demuestra una separación estructural inicial, pero no demuestra que exista una separación funcional completa entre APT y dpkg, que las instalaciones sean transaccionales ni que los paquetes sean seguros.

La auditoría debe seguir las operaciones reales a través de estos componentes, comprobar quién los invoca y distinguir entre código implementado, código integrado y código que solamente está definido.

Una limitación importante del alcance: el acceso al repositorio remoto permite inspeccionar archivos y estructura, pero todavía no equivale a ejecutar el proyecto en un entorno local. No presentaré compilaciones, pruebas de ejecución ni reproducciones como realizadas sin disponer de sus resultados reales.

## B. Mapa de arquitectura real

El flujo encontrado no es una implementación puramente declarativa: hay código operativo para consultar índices, seleccionar candidatos, descargar artefactos, extraer archivos y registrar paquetes. Sin embargo, varias de esas operaciones no están conectadas mediante las garantías que exige la especificación.

\#chatgpt-mermaid-\_r_jv\_{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;fill:rgb(237, 237, 237);}@keyframes edge-animation-frame{from{stroke-dashoffset:0;}}@keyframes dash{to{stroke-dashoffset:0;}}#chatgpt-mermaid-\_r_jv\_ .edge-animation-slow{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 50s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_jv\_ .edge-animation-fast{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 20s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_jv\_ .error-icon{fill:rgb(48, 48, 48);}#chatgpt-mermaid-\_r_jv\_ .error-text{fill:rgb(237, 237, 237);stroke:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jv\_ .edge-thickness-normal{stroke-width:1px;}#chatgpt-mermaid-\_r_jv\_ .edge-thickness-thick{stroke-width:3.5px;}#chatgpt-mermaid-\_r_jv\_ .edge-pattern-solid{stroke-dasharray:0;}#chatgpt-mermaid-\_r_jv\_ .edge-thickness-invisible{stroke-width:0;fill:none;}#chatgpt-mermaid-\_r_jv\_ .edge-pattern-dashed{stroke-dasharray:3;}#chatgpt-mermaid-\_r_jv\_ .edge-pattern-dotted{stroke-dasharray:2;}#chatgpt-mermaid-\_r_jv\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jv\_ .marker.cross{stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jv\_ svg{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;}#chatgpt-mermaid-\_r_jv\_ p{margin:0;}#chatgpt-mermaid-\_r_jv\_ .label{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jv\_ .cluster-label text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jv\_ .cluster-label span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jv\_ .cluster-label span p{background-color:transparent;}#chatgpt-mermaid-\_r_jv\_ .label text,#chatgpt-mermaid-\_r_jv\_ span{fill:rgb(237, 237, 237);color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jv\_ .node rect,#chatgpt-mermaid-\_r_jv\_ .node circle,#chatgpt-mermaid-\_r_jv\_ .node ellipse,#chatgpt-mermaid-\_r_jv\_ .node polygon,#chatgpt-mermaid-\_r_jv\_ .node path{fill:rgb(9, 23, 44);stroke:rgb(31, 78, 148);stroke-width:1px;}#chatgpt-mermaid-\_r_jv\_ .rough-node .label text,#chatgpt-mermaid-\_r_jv\_ .node .label text,#chatgpt-mermaid-\_r_jv\_ .image-shape .label,#chatgpt-mermaid-\_r_jv\_ .icon-shape .label{text-anchor:middle;}#chatgpt-mermaid-\_r_jv\_ .node .katex path{fill:#000;stroke:#000;stroke-width:1px;}#chatgpt-mermaid-\_r_jv\_ .rough-node .label,#chatgpt-mermaid-\_r_jv\_ .node .label,#chatgpt-mermaid-\_r_jv\_ .image-shape .label,#chatgpt-mermaid-\_r_jv\_ .icon-shape .label{text-align:center;}#chatgpt-mermaid-\_r_jv\_ .node.clickable{cursor:pointer;}#chatgpt-mermaid-\_r_jv\_ .root .anchor path{fill:rgb(175, 175, 175)!important;stroke-width:0;stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jv\_ .arrowheadPath{fill:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jv\_ .edgePath .path{stroke:rgb(175, 175, 175);stroke-width:1px;}#chatgpt-mermaid-\_r_jv\_ .flowchart-link{stroke:rgb(175, 175, 175);fill:none;}#chatgpt-mermaid-\_r_jv\_ .edgeLabel{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_jv\_ .edgeLabel p{background-color:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_jv\_ .edgeLabel rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_jv\_ .labelBkg{background-color:rgba(0, 0, 0, 0.5);}#chatgpt-mermaid-\_r_jv\_ .cluster rect{fill:rgb(48, 48, 48);stroke:rgba(255, 255, 255, 0.15);stroke-width:1px;}#chatgpt-mermaid-\_r_jv\_ .cluster text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jv\_ .cluster span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jv\_ div.mermaidTooltip{position:absolute;text-align:center;max-width:200px;padding:2px;font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:12px;background:rgb(48, 48, 48);border:1px solid rgba(255, 255, 255, 0.15);border-radius:2px;pointer-events:none;z-index:100;}#chatgpt-mermaid-\_r_jv\_ .flowchartTitleText{text-anchor:middle;font-size:18px;fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jv\_ rect.text{fill:none;stroke-width:0;}#chatgpt-mermaid-\_r_jv\_ .icon-shape,#chatgpt-mermaid-\_r_jv\_ .image-shape{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_jv\_ .icon-shape p,#chatgpt-mermaid-\_r_jv\_ .image-shape p{background-color:rgb(0, 0, 0);padding:2px;}#chatgpt-mermaid-\_r_jv\_ .icon-shape .label rect,#chatgpt-mermaid-\_r_jv\_ .image-shape .label rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_jv\_ .label-icon{display:inline-block;height:1em;overflow:visible;vertical-align:-0.125em;}#chatgpt-mermaid-\_r_jv\_ .node .label-icon path{fill:currentColor;stroke:revert;stroke-width:revert;}#chatgpt-mermaid-\_r_jv\_ .node .neo-node{stroke:rgb(31, 78, 148);}#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].node rect,#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].cluster rect,#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].node polygon{stroke:url(#chatgpt-mermaid-\_r_jv\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].swimlane.cluster rect{filter:none;}#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].node path{stroke:url(#chatgpt-mermaid-\_r_jv\_-gradient);stroke-width:1px;}#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].node .outer-path{filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].node .neo-line path{stroke:rgb(31, 78, 148);filter:none;}#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].node circle{stroke:url(#chatgpt-mermaid-\_r_jv\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].node circle .state-start{fill:#000000;}#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].icon-shape .icon{fill:url(#chatgpt-mermaid-\_r_jv\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jv\_ [data-look="neo"].icon-shape .icon-neo path{stroke:url(#chatgpt-mermaid-\_r_jv\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jv\_ .node text{font-size:14px;font-weight:600;letter-spacing:normal;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_jv\_ .edgeLabels text{font-size:13px;font-weight:600;letter-spacing:-0.08px;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_jv\_ .node tspan[font-weight="normal"],#chatgpt-mermaid-\_r_jv\_ .edgeLabels tspan[font-weight="normal"]{font-weight:600;}#chatgpt-mermaid-\_r_jv\_ .edgeLabel .label rect{opacity:1;rx:13px;ry:13px;fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-width:1px;}#chatgpt-mermaid-\_r_jv\_ .node rect,#chatgpt-mermaid-\_r_jv\_ .node circle,#chatgpt-mermaid-\_r_jv\_ .node ellipse,#chatgpt-mermaid-\_r_jv\_ .node polygon,#chatgpt-mermaid-\_r_jv\_ .node path{fill:rgb(0, 40, 77);stroke:rgba(255, 255, 255, 0.1);stroke-width:1px;}#chatgpt-mermaid-\_r_jv\_ .node rect{rx:16px;ry:16px;}#chatgpt-mermaid-\_r_jv\_ .node.mermaid-decision .label-container{fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-dasharray:2,2;}#chatgpt-mermaid-\_r_jv\_ .edgePaths .flowchart-link{stroke:rgb(175, 175, 175);stroke-width:1px;stroke-linecap:round;stroke-linejoin:round;}#chatgpt-mermaid-\_r_jv\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jv\_ :root{--mermaid-font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";}ManagerFacade.initialize()RepoConfigParserLocalPackageDatabase.initialize()RepositoryManager.loadCachedIndexes()PackageInstallerDependencyResolverAptInstallerrepoInstall()findCandidates()PlanStepdownloadPackage()PackageInstaller.install()LeerMETADATA/package.listValidar dependencias yconflictosExtraer archivosLocalPackageDatabase.registerPackage()library/statuspackageRemove()PackageInstaller.remove()Eliminar archivosLocalPackageDatabase.unregisterPackage()

El diagrama representa el flujo del código actual. No implica que las operaciones sean transaccionales ni que todos los pasos finalicen correctamente.

### Componentes y responsabilidades observadas

| Componente                                    | Responsabilidad observada                                          | Evaluación inicial                                                                                          |
| --------------------------------------------- | ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| `manager-spi/.../ManifestParser.java`         | Analiza índices remotos y manifiestos; serializa el estado local.  | Tiene un defecto confirmado en el análisis del índice.                                                      |
| `manager-spi/.../RepoConfigParser.java`       | Lee y ordena la configuración YAML de repositorios.                | Existe, pero su validación es incompleta.                                                                   |
| `manager-spi/.../Version.java`                | Compara versiones y evalúa restricciones.                          | Implementación parcial de SemVer y rangos.                                                                  |
| `manager-core/.../LocalPackageDatabase.java`  | Mantiene el inventario instalado y escribe `library/status`.       | Tiene persistencia y bloqueo, pero no garantiza atomicidad de operaciones completas.                        |
| `manager-core/.../PackageInstaller.java`      | Instala ZIP locales, verifica archivos y elimina paquetes.         | Tiene validaciones útiles, con problemas importantes de rollback, rutas y propiedad de archivos.            |
| `manager-apt/.../RepositoryManager.java`      | Descarga índices, conserva caché y selecciona candidatos.          | Funcionalidad real, con defectos de adquisición y selección.                                                |
| `manager-apt/.../DependencyResolver.java`     | Intenta construir planes de instalación y actualización.           | Parcial: el resolver no construye el grafo transitivo requerido.                                            |
| `manager-apt/.../AptInstaller.java`           | Descarga artefactos y delega la instalación al componente local.   | Separación conceptual correcta, ejecución no transaccional.                                                 |
| `manager-apt/.../ManagerFacade.java`          | Inicializa y conecta los componentes; expone operaciones públicas. | Existe una fachada funcional, pero no demuestra integración completa con los comandos y el runtime.         |
| `manager-apt/.../ModuleLifecycleAdapter.java` | Adapta el sistema nuevo al contrato de ciclo de vida anterior.     | Varios métodos son implementaciones vacías o respuestas de éxito sin realizar la operación correspondiente. |

### Integración con el modelo de módulos de TxF

`src/host/src/main/java/me/majhrs16/suite/host/SuiteBootstrap.java` documenta y aplica una distinción importante: `Module` se utiliza como descriptor para resolver el grafo, mientras que los servicios reales se activan mediante sus propios puntos de entrada. El bootstrap utiliza `ModuleLoader.discover()` y `ModuleGraph.resolve()`; no instancia reflectivamente cada descriptor como si fuera un servicio.

Por tanto, no hay evidencia en este flujo de que `SuiteBootstrap` cometa el error de tratar `Module.java` como una clase de servicio que debe instanciarse.

Eso no demuestra que el Manager esté correctamente integrado con el ciclo de vida: el adaptador de compatibilidad presenta problemas distintos que se detallan más adelante.

## C. Inventario de funcionalidades

La siguiente clasificación refleja la evidencia estática del código consultado. «Correcta y verificada» se reserva para propiedades demostradas por la implementación; no significa que se haya ejecutado una prueba de integración.

### Funcionalidades presentes

Presentes con una base técnica aprovechable

- Separación en módulos `manager-spi`, `manager-core` y `manager-apt`.
- Persistencia local en `library/status`.
- Escritura mediante archivo temporal y movimiento atómico en `LocalPackageDatabase.save()`.
- Comparación numérica de los componentes mayor, menor y parche de las versiones válidas.
- Comprobación de SHA-256 y tamaño de los archivos extraídos.
- Comprobación léxica de rutas para detectar una parte de los casos de ZIP Slip.
- Rechazo de la eliminación cuando el registro local identifica dependencias inversas.

Parciales o incorrectas

- Actualización y caché de índices.
- Selección de candidatos por versión y prioridad.
- Instalación desde ZIP local.
- Actualización de paquetes instalados.
- Registro de metapaquetes.
- Inventario de propiedad de archivos.
- Recuperación de errores durante instalación y eliminación.
- Compatibilidad de arquitectura, sistema operativo y plataforma.
- Adaptación del Manager al ciclo de vida anterior.

Ausentes o no demostradas

- Resolución transitiva completa con restricciones de versiones.
- Transacciones multi-paquete con rollback integral.
- Recuperación persistente de operaciones interrumpidas.
- Garantía de seguridad frente a enlaces simbólicos.
- Activación y desactivación efectiva de paquetes descargados.
- Contrato completo y verificable de los comandos exigidos por la especificación.
- Pruebas específicas del Manager que demuestren sus garantías de instalación y eliminación.

La distinción fundamental es que el Manager tiene más implementación que un simple esqueleto, pero las rutas críticas de instalación y eliminación todavía no ofrecen garantías suficientes para tratarlo como un gestor de paquetes fiable.

## D. Hallazgos técnicos

Los siguientes hallazgos se apoyan en rutas de ejecución concretas del código. «Confirmado» significa que el defecto se desprende de la implementación inspeccionada; no significa que se haya ejecutado una reproducción. No se han ejecutado todavía pruebas dinámicas del Manager.

### MGR-001 — El parser del índice remoto lee el campo equivocado para SHA-256



Alta

Bug confirmado por inspección



- Ubicación: `src/manager-spi/src/main/java/me/majhrs16/suite/manager/spi/ManifestParser.java`, método `parseRemoteIndex()`, aproximadamente líneas 30–50.
- Categoría: bug funcional, adquisición de paquetes, integridad.
- Precondiciones: el repositorio utiliza el formato tabulado `nombre<TAB>URL<TAB>tamaño<TAB>SHA256`.

Evidencia:

```
long size = Long.parseLong(fields[2].strip());
String sha256 = fields[2].strip(); // This is wrong - should be fields[3]
if (!isValidSha256(sha256)) {
    throw new ParseException(...);
}
```

El parser obtiene el tamaño de `fields[2]`, pero vuelve a leer ese mismo campo para obtener el hash. Un tamaño habitual, como `48213`, no es un SHA-256 de 64 caracteres hexadecimales.

Comportamiento actual: los índices convencionales con tamaños normales son rechazados por el parser. La descarga de índices no GitHub que pasa por este método falla antes de producir candidatos válidos.

La consecuencia alcanza también a `RepositoryManager.loadCachedIndexes()`: los índices guardados con el formato tabulado vuelven a analizarse mediante el mismo parser y pueden quedar inutilizables tras reiniciar el servidor.

Comportamiento esperado: leer el SHA-256 del cuarto campo y rechazar únicamente las entradas realmente inválidas.

Confianza: alta. La asignación errónea es explícita en el código. No se ha ejecutado una reproducción.

### MGR-002 — La resolución de dependencias no implementa el grafo transitivo



Crítica

Bug confirmado por inspección



- Ubicación: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/DependencyResolver.java`, métodos `resolveInstall()` y `resolvePackage()`.
- Categoría: bug funcional, integridad del estado, incumplimiento de especificación.
- Precondiciones: se intenta instalar un paquete que requiere una dependencia no instalada o que requiere una versión concreta de otra dependencia.

Evidencia:

```
selected.put(best.name(), best);
resolved.add(pkgName);
processing.remove(pkgName);

// Resolve dependencies (we'd need to download manifest to get actual deps)
// For now, assume dependencies are declared in the remote index or we need to fetch manifest
// This is a simplification - in reality we'd need to download the manifest first
```

El código selecciona el candidato, lo marca como resuelto y no examina las dependencias del manifiesto para continuar el recorrido del grafo.

Además, `isCompatible()` devuelve `true` incondicionalmente y el método que construye el plan declara que debería realizar una ordenación topológica, pero devuelve los pasos en el orden de selección.

Comportamiento actual: una instalación puede parecer resoluble aunque falten dependencias transitivas o el candidato sea incompatible. El instalador local realiza una segunda comprobación, pero solo contra los paquetes instalados, no contra un plan transitivo completo.

Consecuencia: una operación válida según el resolver puede fallar durante la instalación, y un conjunto de paquetes puede instalarse parcialmente antes de que aparezca el error.

Comportamiento esperado: resolver todas las dependencias y restricciones antes de modificar el estado, detectar ciclos y conflictos, y producir un plan completo.

Confianza: alta. La ausencia de recorrido de dependencias y la implementación de compatibilidad están explícitas en el código.

### MGR-003 — La instalación multi-paquete no es transaccional y el rollback no revierte los archivos



Crítica

Bug confirmado por inspección



- Ubicación: `src/manager-core/src/main/java/me/majhrs16/suite/manager/core/PackageInstaller.java`, método `install()`.
- Categoría: integridad, bug funcional, recuperación.
- Precondiciones: un ZIP contiene varios registros de paquetes, o falla la extracción de un paquete después de que se hayan escrito algunos archivos.

El método procesa los paquetes uno por uno. Cada paquete se extrae y después se registra individualmente en la base de datos. Si posteriormente ocurre una excepción de E/S, el bloque de recuperación desregistra los paquetes que ya se habían añadido a `installedEntries`, pero no restaura ni elimina sus archivos extraídos.

Existe una segunda ruta de fallo: si la extracción falla a mitad del paquete actual, los archivos que ya se escribieron para ese paquete tampoco quedan cubiertos por el rollback, porque el registro se agrega a `installedEntries` después de finalizar su extracción.

Comportamiento actual: el estado registrado puede volver a indicar que los paquetes no están instalados mientras permanecen archivos físicos de la operación fallida. Una operación con varios paquetes puede dejar un resultado parcialmente aplicado.

Comportamiento esperado: preparar y validar el conjunto completo antes de confirmar la operación, y disponer de una recuperación que restaure coherentemente los archivos y el estado.

Confianza: alta. Se deriva del orden de las escrituras y de la ausencia de restauración de archivos en las rutas de excepción.

### MGR-004 — El inventario no protege contra escapes mediante enlaces simbólicos



Alta

Riesgo de seguridad confirmado en el diseño del flujo



- Ubicación: `src/manager-core/src/main/java/me/majhrs16/suite/manager/core/PackageInstaller.java`, método `extractPackageFiles()`.
- Categoría: seguridad, integridad del sistema de archivos.
- Precondiciones: existe un enlace simbólico dentro de una ruta de destino que apunta fuera del directorio de instalación, y un paquete instala un archivo bajo ese enlace.

El código normaliza la ruta de destino y verifica que empiece por `installRoot.normalize()`. Esta comprobación protege frente a muchos casos de traversal léxico, pero no demuestra que el destino físico siga dentro del directorio autorizado.

Una ruta como `plugins/administrado/config/archivo` puede superar la comprobación léxica y, sin embargo, atravesar un enlace simbólico existente en `administrado` que apunte a otro directorio.

Comportamiento actual: el código abre el destino mediante las APIs ordinarias de archivos sin una garantía explícita de que los componentes de la ruta no redirijan la escritura fuera del directorio autorizado.

Consecuencia posible: sobrescritura de archivos ajenos si se cumplen las precondiciones. No se ha demostrado una explotación en una instalación concreta.

Comportamiento esperado: validar y abrir los destinos de manera segura frente a enlaces simbólicos y cambios concurrentes, además de rechazar rutas inválidas antes de escribir.

Confianza: alta respecto de la insuficiencia de la defensa; la explotabilidad depende del estado del sistema de archivos.

### MGR-005 — La actualización de paquetes utiliza `force` para sobrescribir archivos sin retirar correctamente la versión anterior



Alta

Bug confirmado por inspección



- Ubicación: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/AptInstaller.java`, método `upgrade()`; `PackageInstaller.java`, métodos `install()` y `checkFileConflicts()`.
- Categoría: integridad, gestión de versiones, propiedad de archivos.
- Precondiciones: se instala una versión nueva que comparte rutas con la versión anterior.

La actualización llama a:

```
installer.install(zipPath, true);
```

En el instalador local, `force` permite continuar ante conflictos de propiedad de archivos. No existe en ese flujo una operación transaccional que sustituya la versión anterior, retire su registro y preserve el estado previo en caso de fallo.

La base de datos identifica paquetes por nombre, versión y canal, por lo que las versiones diferentes pueden permanecer registradas simultáneamente.

Comportamiento actual: los archivos físicos pueden corresponder a la versión nueva mientras el estado conserva registros de ambas versiones. El inventario de propiedad puede indicar que ambas poseen las mismas rutas.

Consecuencia: verificaciones y eliminaciones posteriores pueden operar sobre una propiedad ambigua. Eliminar la versión antigua no restaura sus contenidos; la política de archivos compartidos puede, en cambio, conservar archivos de la versión nueva.

Comportamiento esperado: una actualización debe tener una semántica explícita de sustitución, con gestión coherente de versiones, propiedad y recuperación.

Confianza: alta para la inconsistencia posible; no se ha reproducido en ejecución.

### MGR-006 — La eliminación puede desregistrar paquetes aunque no consiga borrar sus archivos



Alta

Bug confirmado por inspección



- Ubicación: `src/manager-core/src/main/java/me/majhrs16/suite/manager/core/PackageInstaller.java`, método `remove()`; `LocalPackageDatabase.java`, método `unregisterPackage()`.
- Categoría: integridad, eliminación, recuperación.
- Precondiciones: el sistema de archivos rechaza la eliminación de un archivo que pertenece exclusivamente al paquete.

El método `remove()` captura el error de `Files.deleteIfExists()` y lo registra como advertencia. Después continúa con `database.unregisterPackage()`.

Comportamiento actual: el paquete puede desaparecer del estado local aunque algunos archivos sigan en disco. Una operación posterior ya no tendrá necesariamente el inventario de propiedad que permitiría identificar esos residuos.

Comportamiento esperado: informar de la eliminación incompleta y conservar o recuperar la información de propiedad hasta que el resultado sea coherente.

Confianza: alta. El flujo de control muestra que el error de eliminación no impide desregistrar el paquete.

### MGR-007 — La selección de candidatos deriva la versión de la URL en lugar de validar los metadatos del paquete



Alta

Bug confirmado por inspección



- Ubicación: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/RepositoryManager.java`, métodos `findCandidates()` y `extractVersionFromUrl()`.
- Categoría: compatibilidad, selección de versiones, incumplimiento de especificación.

La especificación establece que la identidad y versión del paquete deben validarse contra `METADATA/package.list`. El código actual extrae la versión del nombre del archivo, exige la extensión `.jar` y toma el texto posterior al último guion.

Comportamiento actual:

- Un artefacto distribuido como ZIP, según el contrato requerido, no pasa el filtro de extensión.
- Una versión con sufijo de prerelease, como `1.2.3-beta`, no se interpreta correctamente: el último guion produce `beta`, que no es una versión SemVer válida por sí sola.
- La versión elegida no se ha contrastado con el manifiesto del artefacto.

Consecuencia: candidatos válidos pueden desaparecer y la selección de versiones puede contradecir los metadatos reales.

Comportamiento esperado: obtener y validar la versión declarada por el paquete, y rechazar discrepancias entre índice y manifiesto.

Confianza: alta.

### MGR-008 — Los paquetes descargados se guardan con extensión `.jar` aunque el contrato de distribución exige ZIP



Media

Incumplimiento confirmado



- Ubicación: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/AptInstaller.java`, método `downloadPackage()`, líneas aproximadas 170–209.
- Categoría: compatibilidad, adquisición de artefactos.

El nombre de destino se construye como `nombre-versión.jar`. El instalador, en cambio, espera una distribución ZIP que contenga `METADATA/package.list` y la procesa mediante `ZipFile`.

El formato interno puede seguir siendo legible pese a la extensión incorrecta, pero la convención contradice el contrato de distribución y puede confundir herramientas, operadores y otros componentes que inspeccionen el archivo por su extensión.

Confianza: alta.

### MGR-009 — Las dependencias con restricciones no se validan de manera coherente



Alta

Bug confirmado por inspección



- Ubicación: `PackageInstaller.checkDependencies()` y `LocalPackageDatabase.getReverseDependencies()`.
- Categoría: dependencias, integridad del estado.

El parser guarda `Dependencies` como una lista de cadenas separadas por comas. El instalador local compara cada cadena con el nombre exacto de un paquete instalado, y la detección de dependencias inversas aplica el mismo criterio de igualdad textual.

Comportamiento actual: una dependencia expresada como `library >= 1.2.0` no coincide con un paquete cuyo nombre es `library`. Por tanto, el instalador puede rechazar una dependencia que está satisfecha o dejar de detectar correctamente que un paquete es necesario.

Comportamiento esperado: parsear las dependencias como identidades más restricciones de versión, y utilizar el mismo modelo en la resolución, instalación y eliminación.

Confianza: alta.

### MGR-010 — La base de datos local no restaura su estado en memoria cuando falla la persistencia



Alta

Bug confirmado por inspección



- Ubicación: `src/manager-core/src/main/java/me/majhrs16/suite/manager/core/LocalPackageDatabase.java`, métodos `registerPackage()` y `unregisterPackage()`.
- Categoría: integridad, recuperación.

Ambos métodos modifican primero las estructuras en memoria y después llaman a `save()`. Si la escritura falla, el método propaga la excepción, pero no revierte las modificaciones ya realizadas en los mapas de paquetes y propiedad.

Comportamiento actual: una llamada que falla puede dejar el estado en memoria distinto del estado persistido en `library/status`.

La escritura atómica del archivo protege frente a ciertos fallos durante su sustitución, pero no hace atómica la combinación de memoria, archivos instalados y registro persistente.

Comportamiento esperado: una operación fallida no debe dejar una divergencia silenciosa entre la base de datos en memoria y la persistida.

Confianza: alta.

### MGR-011 — La gestión de archivos compartidos identifica propietarios por nombre, no por identidad completa del paquete



Alta

Bug confirmado por inspección



- Ubicación: `LocalPackageDatabase.java`, métodos `registerPackage()`, `unregisterPackage()`, `checkOrphanedFiles()` y `getFileOwners()`.
- Categoría: integridad, propiedad de archivos.

La base de datos registra propietarios como nombres de paquete, aunque la identidad completa incluye nombre, versión y canal.

Comportamiento actual: dos versiones del mismo paquete que declaren un mismo archivo se representan con el mismo propietario en el conjunto de nombres. Al retirar una de ellas, se elimina ese nombre del conjunto, aunque otra versión del mismo paquete siga instalada.

Consecuencia: la base de datos puede dejar de reconocer que un archivo continúa asociado a otra versión. La protección de archivos compartidos no es fiable para versiones coexistentes.

Confianza: alta.

### MGR-012 — El análisis del manifiesto descarta silenciosamente entradas duplicadas de archivos



Media

Bug confirmado por inspección



- Ubicación: `src/manager-spi/src/main/java/me/majhrs16/suite/manager/spi/ManifestParser.java`, análisis de la sección `Files`.
- Categoría: validación, integridad del inventario.

El parser añade las entradas a un `LinkedHashMap` mediante `files.put(path, ...)`. Si aparece otra entrada con la misma ruta, sustituye la anterior sin rechazar el manifiesto.

Comportamiento actual: un manifiesto con rutas duplicadas puede terminar representado por una única entrada, perdiéndose la información del registro previo.

Comportamiento esperado: detectar rutas duplicadas y rechazar los inventarios ambiguos, salvo que exista una regla de duplicación explícita y segura.

Confianza: alta.

### MGR-013 — El contrato de ciclo de vida anterior devuelve éxitos ficticios o no realiza las operaciones solicitadas



Alta

Bug confirmado por inspección



- Ubicación: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/ModuleLifecycleAdapter.java`.
- Categoría: integración, API pública, comportamiento inesperado.

La implementación incluye métodos como `load()`, que devuelve el `parent` como cargador, `register()`, que devuelve `true`, y `unregister()`, que también devuelve `true`, sin realizar la operación de carga, registro o desregistro que su contrato aparenta ofrecer. `download()` devuelve una lista vacía y `relocate()` devuelve la ruta original.

Además, `resolve()` llama a `facade.repoInstall(...)`, que ejecuta la instalación, pese a que el método se presenta como una operación de resolución.

Comportamiento actual: un consumidor del contrato antiguo puede interpretar una respuesta positiva como prueba de que un módulo está cargado o registrado, cuando el adaptador no ha realizado esa operación.

Comportamiento esperado: cada método debe cumplir su contrato o informar explícitamente que la operación no está soportada.

Confianza: alta para los métodos observados; la consecuencia exacta depende del consumidor.

### MGR-014 — La detección de actualizaciones devuelve los paquetes instalados, no los candidatos nuevos



Media

Bug confirmado por inspección



- Ubicación: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/ManagerFacade.java`, método `checkUpdates()`.
- Categoría: bug funcional, experiencia operativa.

Cuando encuentra un candidato más reciente, el método agrega `installed` a la lista de actualizaciones, en lugar del candidato disponible.

Comportamiento actual: el resultado identifica los paquetes instalados que tienen una actualización disponible, no la versión candidata que debería presentarse como actualización.

Esto puede servir como lista de paquetes pendientes, pero no permite inferir que el método entregue las versiones nuevas.

Comportamiento esperado: diferenciar claramente el paquete instalado de la versión candidata.

Confianza: alta.

### MGR-015 — La configuración no valida suficientemente la identidad de los repositorios ni los tipos de entrada



Media

Defecto confirmado



- Ubicación: `src/manager-spi/src/main/java/me/majhrs16/suite/manager/spi/RepoConfigParser.java`.
- Categoría: configuración, determinismo, mantenimiento.

El parser acepta valores predeterminados para el nombre y la prioridad, y convierte determinados tipos de datos con coerciones. No comprueba de forma suficiente la unicidad de nombres, los conflictos de identidad ni que los tipos de todos los campos tengan la forma esperada antes de convertirlos.

La caché de índices se identifica mediante el nombre del repositorio, mientras que los archivos se guardan utilizando un nombre saneado. Dos nombres distintos pueden transformarse en el mismo nombre de archivo.

Consecuencia posible: colisiones de caché, ambigüedad entre orígenes o resultados dependientes de la configuración. No se ha demostrado una colisión concreta en una instalación real.

Confianza: alta respecto de la falta de validación; media respecto de la consecuencia operativa.

### MGR-016 — El soporte de GitHub Releases no demuestra una adquisición coherente con el contrato de paquetes



Media

Defecto confirmado



- Ubicación: `RepositoryManager.java`, métodos `buildIndexUrl()` y `parseGitHubReleases()`.
- Categoría: adquisición, compatibilidad, integridad.

La ruta especial de GitHub analiza releases y busca assets `.jar` junto con un asset de hash denominado `nombre.jar.sha256`. El sistema convierte el tag en versión y deriva el nombre del artefacto desde el nombre del asset.

Esto no equivale al contrato requerido de un índice tabulado con ZIP, metadatos internos autoritativos y validación de la identidad del paquete contra el manifiesto.

Además, la conversión de URL a endpoint de releases no demuestra que todos los formatos de URL de repositorio configurables se conviertan en endpoints válidos.

Confianza: alta respecto de la diferencia de contrato; no se ha probado cada variante de URL.

### MGR-017 — No existe una recuperación persistente demostrada para operaciones interrumpidas



Alta

Funcionalidad ausente



- Ubicación: `LocalPackageDatabase.java`, `PackageInstaller.java` y `AptInstaller.java`.
- Categoría: integridad, recuperación.

La base de datos escribe `library/status` mediante un archivo temporal y un movimiento atómico, pero el flujo de instalación no persiste un diario transaccional ni una lista recuperable de operaciones incompletas.

Comportamiento actual: la inicialización vuelve a leer el estado persistido. No se demuestra un mecanismo que reconstruya o revierta automáticamente archivos escritos antes de una interrupción del proceso.

Comportamiento esperado: detectar las operaciones incompletas y recuperar o declarar explícitamente un estado inconsistente.

Confianza: alta respecto de la ausencia en los flujos inspeccionados.

### MGR-018 — La implementación no demuestra límites de tamaño ni defensas contra bombas de descompresión



Media

Riesgo de seguridad



- Ubicación: `AptInstaller.downloadPackage()` y `PackageInstaller.extractPackageFiles()`.
- Categoría: seguridad, disponibilidad.

La descarga se copia mediante `transferTo()` y la extracción también utiliza una copia sin límite de bytes descomprimidos o número de entradas en el flujo observado.

Se verifica el tamaño final de los archivos declarados, pero esto no equivale a imponer límites globales antes o durante la extracción.

Consecuencia posible: consumo excesivo de disco, memoria o tiempo si se procesa un artefacto malicioso o desproporcionado.

Confianza: alta respecto de la ausencia de límites explícitos en estas rutas; el impacto depende del artefacto y del entorno.

### Otros defectos relevantes confirmados por inspección

Los hallazgos anteriores son los de mayor impacto. Hay, además, problemas específicos que afectan al comportamiento esperado del operador.

| ID      | Defecto                                                                                                         | Ubicación                                               | Consecuencia                                                                                 |
| ------- | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| MGR-019 | El tamaño esperado del ZIP se comprueba solo si el tamaño del manifiesto principal es positivo.                 | `PackageInstaller.install()`                            | Un tamaño omitido o cero evita esa comprobación local.                                       |
| MGR-020 | El parser acepta valores negativos para `Size` si son numéricamente válidos.                                    | `ManifestParser.buildPackageEntry()`                    | El campo no cumple una validación estricta de rango.                                         |
| MGR-021 | La eliminación de archivos registra errores como advertencias y continúa.                                       | `PackageInstaller.remove()`                             | Puede haber archivos residuales sin inventario.                                              |
| MGR-022 | `Version.satisfies()` devuelve `true` para una restricción vacía.                                               | `Version.java`                                          | Una restricción ausente significa «sin restricción», no una comprobación de compatibilidad.  |
| MGR-023 | La comparación SemVer no contempla correctamente todos los rangos de prerelease, comodines y límites numéricos. | `Version.java`                                          | Determinadas restricciones válidas pueden resolverse incorrectamente o provocar errores.     |
| MGR-024 | Los campos `Arch`, `Platform` y `OS` se parsean, pero el resolver no los aplica.                                | `DependencyResolver.isCompatible()`                     | La compatibilidad declarada no se convierte en una garantía de instalación.                  |
| MGR-025 | La caché no conserva necesariamente la identidad de origen de forma inequívoca.                                 | `RepositoryManager.loadCachedIndexes()` y `updateAll()` | Los índices dependen de nombres de repositorio y de su transformación en nombres de archivo. |
| MGR-026 | No se demuestra una política de limpieza integral de temporales tras fallos.                                    | `AptInstaller.downloadPackage()`                        | Los artefactos pueden permanecer en `library/tmp/`.                                          |

MGR-019 y MGR-020 no implican que cualquier paquete con un tamaño cero sea instalable: el hash y las demás comprobaciones pueden seguir rechazándolo. El defecto es que la validación de tamaño no es estricta y uniforme.

MGR-023 requiere una precisión: la clase `Version` sí implementa una parte importante de SemVer, incluyendo comparación numérica de `major.minor.patch` y orden de versiones prerelease frente a versiones estables. El problema es la cobertura incompleta de casos límite, no que toda comparación de versiones sea lexicográfica.

## E. Comportamientos esperados frente a comportamientos reales

| Operación                  | Comportamiento real encontrado                                                         | Evaluación                                                                                  |
| -------------------------- | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| Inicializar el Manager     | Lee `repo.yml`, inicializa el estado local y carga los índices en caché.               | Parcial; el arranque puede continuar con cero repositorios configurados.                    |
| Actualizar índices         | Descarga, analiza y guarda los índices por repositorio.                                | Parcial; el parser convencional tiene un defecto que impide aceptar entradas válidas.       |
| Consultar candidatos       | Filtra por nombre, versión derivada de la URL, canal y prioridad.                      | Incorrecto frente al contrato de metadatos autoritativos.                                   |
| Resolver dependencias      | Selecciona candidatos y construye pasos en orden de selección.                         | No garantiza transitividad, compatibilidad ni orden topológico.                             |
| Instalar desde repositorio | Descarga, verifica el artefacto y delega en el instalador local.                       | Parcial; no garantiza una operación atómica para el conjunto.                               |
| Instalar ZIP local         | Analiza el manifiesto, comprueba algunos conflictos, extrae y registra paquetes.       | Parcial; puede dejar archivos residuales y estado divergente tras un fallo.                 |
| Actualizar paquetes        | Resuelve candidatos e instala con `force=true`.                                        | Incorrecto para una actualización que deba preservar la coherencia de versiones y archivos. |
| Eliminar paquetes          | Comprueba dependencias inversas, elimina archivos y desregistra.                       | Parcial; puede eliminar registros aunque falle la eliminación física.                       |
| Verificar archivos         | Compara tamaño y SHA-256 de archivos inventariados.                                    | Parcialmente correcto; depende de que el inventario sea íntegro y las rutas sean seguras.   |
| Recuperarse de un fallo    | La inicialización vuelve a leer el estado persistido.                                  | No hay recuperación transaccional completa demostrada.                                      |
| Activar un módulo          | El adaptador no implementa la carga ni el registro real en los métodos inspeccionados. | No garantizado por el Manager.                                                              |

### Qué está garantizado y qué no

Garantías parciales presentes:

- El archivo `library/status` se escribe mediante temporal y movimiento atómico cuando `save()` finaliza correctamente.
- Los archivos extraídos pasan por comprobaciones de tamaño y SHA-256.
- Se detectan varios casos de rutas que escapan léxicamente del directorio de instalación.
- Las dependencias inversas simples, declaradas únicamente mediante nombres, bloquean ciertas eliminaciones.

Comportamientos condicionados:

- La descarga es verificada respecto al hash y tamaño del índice cuando el candidato llega correctamente a `AptInstaller`.
- La instalación local valida las dependencias según las cadenas que interpreta.
- La eliminación conserva archivos que el inventario identifica como compartidos, pero esa identificación no es fiable cuando coexisten versiones del mismo nombre.

Comportamientos no garantizados:

- Que un plan represente el grafo completo de dependencias.
- Que una operación multi-paquete se confirme o revierta como una unidad.
- Que la eliminación preserve siempre los archivos necesarios para otros paquetes.
- Que los archivos instalados pertenezcan físicamente al directorio autorizado frente a enlaces simbólicos.
- Que un paquete instalado quede activado en Bukkit, Fabric o Forge.
- Que el estado en memoria y el persistido permanezcan coherentes tras un fallo de escritura.

## F. Aspectos correctos que deben preservarse conceptualmente

La auditoría no justifica descartar indiscriminadamente todo el código. Hay componentes con una base técnica útil.

### 1. Separación de responsabilidades

`manager-spi`, `manager-core` y `manager-apt` expresan una división razonable entre contratos, estado local y adquisición de paquetes.

Esta división coincide con el principio de la especificación: APT debe preparar candidatos y planes; la instalación local debe controlar los cambios en el estado instalado.

El problema principal es que las garantías de los contratos todavía no están implementadas de extremo a extremo.

### 2. Base de datos local y formato compartido

`LocalPackageDatabase` mantiene el estado en `library/status`, independiente de la disponibilidad de los repositorios. Esto es correcto como principio arquitectónico.

También es razonable reutilizar `ManifestParser` para leer los manifiestos de los paquetes y el estado local, siempre que ambos formatos tengan reglas compatibles y validación estricta.

La escritura temporal y el movimiento atómico de `save()` son una buena medida para evitar que una escritura normal deje un archivo parcialmente escrito. No deben confundirse, sin embargo, con una transacción completa de instalación.

### 3. Verificación criptográfica de archivos

`AptInstaller` calcula SHA-256 sobre el archivo descargado, y `PackageInstaller` verifica el tamaño y el hash de los archivos extraídos.

Son controles importantes que merece la pena conservar conceptualmente. Su efectividad depende de que el hash esperado proceda de un índice de confianza y de que el manifiesto y el inventario se validen correctamente.

Un hash demuestra correspondencia con el valor esperado; por sí solo no autentica al editor del paquete ni a un repositorio comprometido.

### 4. Comparación numérica de versiones

`Version.compareTo()` compara numéricamente los componentes principales y contempla la precedencia de versiones prerelease. Es una base mejor que comparar versiones completas como cadenas.

La limitación está en los casos límite y en la implementación de rangos, no en la idea de utilizar un objeto de versión tipado.

### 5. Descriptor de módulos separado del ciclo de vida

`SuiteBootstrap` mantiene la distinción entre descriptores del grafo y servicios reales. Esto es coherente con el modelo arquitectónico de TxF y no debería confundirse con los defectos del adaptador de compatibilidad del Manager.

## G. Matriz de cumplimiento de la especificación

La matriz evalúa los requisitos de `docs/NEW-MANAGER.md` y las áreas adicionales de auditoría solicitadas. Los estados corresponden al código inspeccionado, no a una ejecución del sistema.

Leyenda: CUMPLIDO · PARCIAL · AUSENTE · INCORRECTO · NO VERIFICADO.

| Área / requisito                          | Estado                                       | Evidencia y diagnóstico                                                                                                         |
| ----------------------------------------- | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Arquitectura APT/dpkg                     | PARCIAL                                      | Hay módulos separados, pero el flujo APT no garantiza una operación local transaccional.                                        |
| Contratos públicos del Manager            | PARCIAL                                      | `ManagerFacade` expone operaciones, pero el adaptador antiguo contiene métodos que no realizan la operación anunciada.          |
| `Module.java` como descriptor             | CUMPLIDO en `SuiteBootstrap`                 | El bootstrap resuelve el grafo sin instanciar reflectivamente los descriptores.                                                 |
| Configuración `repo.yml`                  | PARCIAL                                      | Existe `RepoConfigParser`; no se demuestra validación completa de identidad y tipos.                                            |
| Configuración general `config.yml`        | NO VERIFICADO                                | No se ha seguido exhaustivamente el flujo de configuración de todas las plataformas para establecer su relación con el Manager. |
| Índice remoto `packages.list`             | INCORRECTO                                   | `ManifestParser.parseRemoteIndex()` usa el campo de tamaño como SHA-256.                                                        |
| Caché en `library/lists/`                 | INCORRECTO                                   | Los índices convencionales guardados pueden ser rechazados al recargarlos por el mismo defecto del parser.                      |
| Metadatos `METADATA/package.list`         | PARCIAL                                      | Existe parser multipaquete, pero hay validaciones incompletas y rutas ambiguas en el inventario.                                |
| Cabecera del manifiesto                   | PARCIAL                                      | Se procesa el primer bloque, pero no se validan todos los invariantes necesarios del paquete principal.                         |
| Campos obligatorios                       | PARCIAL                                      | Algunos campos son obligatorios; otros tienen valores predeterminados y no todos se aplican operativamente.                     |
| Semántica de `Type`                       | PARCIAL                                      | Existe `PackageType`, pero no se demuestra un tratamiento integral de paquetes, dependencias y metapaquetes.                    |
| `Channel`                                 | PARCIAL                                      | Se filtra mediante una opción del repositorio; no se demuestra una política completa de selección de canales.                   |
| Restricciones de versiones                | INCORRECTO                                   | El resolver no analiza dependencias con restricciones; el instalador compara cadenas con nombres de paquete.                    |
| Compatibilidad de arquitectura            | INCORRECTO                                   | `DependencyResolver.isCompatible()` devuelve `true`.                                                                            |
| Compatibilidad de sistema operativo       | INCORRECTO                                   | El campo se almacena, pero no se aplica en el resolver.                                                                         |
| Compatibilidad de plataforma              | INCORRECTO                                   | El campo se almacena, pero no se aplica en el resolver.                                                                         |
| Selección determinista de candidatos      | PARCIAL                                      | Ordena por prioridad y versión, pero la versión deriva de la URL y no se valida contra el manifiesto.                           |
| Dependencias transitivas                  | AUSENTE                                      | El resolver no recorre las dependencias declaradas por los artefactos.                                                          |
| Ciclos de dependencias                    | PARCIAL                                      | Existe detección de ciclos en la estructura de resolución, pero el recorrido real no construye el grafo transitivo.             |
| Dependencias compartidas                  | PARCIAL                                      | Hay registro de propiedad, pero la identidad del propietario se reduce al nombre del paquete.                                   |
| Conflictos entre archivos                 | PARCIAL                                      | Se detectan conflictos de propiedad; `force` permite sobrescribir sin sustituir coherentemente los registros previos.           |
| Descarga HTTP                             | PARCIAL                                      | Comprueba códigos HTTP y verifica el archivo descargado, pero carece de límites explícitos de descarga.                         |
| Validación del SHA-256 del ZIP            | PARCIAL                                      | El hash se verifica en la descarga; la coherencia entre índice, manifiesto y origen no está garantizada integralmente.          |
| Tamaño exacto del ZIP                     | PARCIAL                                      | La comprobación existe, pero el tamaño declarado puede omitirse o ser cero en el manifiesto local.                              |
| Integridad individual de archivos         | PARCIAL                                      | Se verifican tamaños y hashes tras extraer, pero la operación puede dejar residuos al fallar.                                   |
| Protección contra ZIP Slip                | PARCIAL                                      | Hay comprobación léxica de rutas; no hay protección completa demostrada frente a enlaces simbólicos.                            |
| Protección contra bombas de descompresión | AUSENTE en el flujo inspeccionado            | No se observan límites globales explícitos de tamaño descomprimido o cantidad de entradas.                                      |
| Inventario de archivos                    | PARCIAL                                      | Se registra por paquete, pero no se rechazan todas las ambigüedades de propiedad e inventario.                                  |
| Estado `library/status`                   | PARCIAL                                      | Existe lectura y escritura, pero no un mecanismo completo de recuperación transaccional.                                        |
| Escritura atómica de `status`             | PARCIAL                                      | Temporal y movimiento atómico; no cubre conjuntamente los archivos instalados y el estado en memoria.                           |
| Recuperación de interrupciones            | AUSENTE                                      | No se demuestra un diario persistente de operaciones ni una recuperación integral.                                              |
| Instalación desde ZIP local               | PARCIAL                                      | Existe flujo real, pero el rollback no revierte todos los archivos.                                                             |
| Actualización de paquetes                 | INCORRECTO                                   | `force=true` permite sobrescritura sin sustitución transaccional de versiones.                                                  |
| Eliminación segura                        | INCORRECTO                                   | Puede desregistrar paquetes aunque falle la eliminación física de archivos.                                                     |
| Metapaquetes                              | PARCIAL                                      | El parser y el instalador admiten varios registros, pero no se demuestra instalación y eliminación coherentes como unidad.      |
| Almacenamiento `library/packages/`        | PARCIAL                                      | Se crea el directorio; no se demuestra una política completa de almacenamiento de artefactos instalados.                        |
| Almacenamiento `library/dependencies/`    | PARCIAL                                      | Se crea el directorio; no se demuestra un modelo completo de dependencias físicas compartidas.                                  |
| Activación y desactivación                | AUSENTE en el adaptador inspeccionado        | Los métodos de ciclo de vida no realizan carga, registro y desregistro reales.                                                  |
| Integración Bukkit/Fabric/Forge           | NO VERIFICADO en su conjunto                 | El flujo del bootstrap está claro, pero falta una comprobación integral de cada adaptador y punto de entrada.                   |
| Comandos requeridos                       | NO VERIFICADO en su conjunto                 | No se ha reconstruido el registro completo de comandos de todas las plataformas.                                                |
| Pruebas específicas del Manager           | AUSENTE en el árbol de pruebas inspeccionado | No aparecen suites de pruebas dedicadas en `manager-core`, `manager-spi` o `manager-apt`.                                       |
| Compilación completa                      | NO VERIFICADO                                | No se ejecutó Gradle en un checkout local durante esta auditoría.                                                               |
| Empaquetado y distribución                | PARCIAL                                      | Hay configuración Gradle de los módulos, pero falta verificar los artefactos finales y su integración en cada distribución.     |

### Discrepancia de nombres de configuración

Hay una diferencia importante entre el texto de la solicitud de auditoría y la especificación normativa: la primera menciona `repositories.yml`, mientras que `docs/NEW-MANAGER.md` exige `repo.yml`, separado de `config.yml`.

En la implementación inspeccionada, `ManagerFacade.initialize()` utiliza `repo.yml`. No debe declararse que falta `repositories.yml` como bug sin resolver antes esa discrepancia contractual.

## H. Resultados de pruebas y compilación

### Alcance de las pruebas realizadas

| Comprobación                                                      | Resultado                                                                           |
| ----------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| Inspección del árbol remoto de `main`                             | Realizada mediante la API de GitHub.                                                |
| Lectura de la especificación `docs/NEW-MANAGER.md`                | Realizada.                                                                          |
| Inspección estática de los principales componentes del Manager    | Realizada parcialmente sobre sus archivos fuente completos o fragmentos relevantes. |
| Seguimiento del flujo de instalación, actualización y eliminación | Realizado mediante inspección estática.                                             |
| Ejecución de tests JUnit                                          | No ejecutada.                                                                       |
| Compilación Gradle                                                | No ejecutada.                                                                       |
| Reproducción de bugs en un entorno aislado                        | No ejecutada.                                                                       |
| Verificación de artefactos de distribución                        | No ejecutada.                                                                       |
| Pruebas de integración Bukkit/Fabric/Forge                        | No ejecutadas.                                                                      |

El módulo `manager-apt/build.gradle` declara JUnit 5, AssertJ y `useJUnitPlatform()`. Eso demuestra que la infraestructura de pruebas está configurada, no que haya pruebas útiles ni que estas pasen.

En el árbol consultado no aparecen suites específicas de pruebas dentro de los tres módulos del Manager. Es una carencia de cobertura observable en ese árbol, no una prueba de que todas las funcionalidades estén rotas.

No presento resultados de compilación ni tests inventados. Tampoco puedo concluir que el proyecto completo compile o falle: esa comprobación requiere ejecutar las tareas correspondientes sobre un checkout concreto.

## I. Diagnóstico arquitectónico

### ¿Qué componentes son compatibles con el modelo APT/dpkg?

`manager-spi`: conservar conceptualmente, pero no dar por correctos todos sus contratos.

La separación de modelos y parsers es apropiada. `PackageEntry`, `FileEntry`, `PackageType`, `RepositoryConfig` y `Version` permiten expresar buena parte del dominio requerido.

Los defectos de parsing, validación y semántica impiden considerar estos contratos una base terminada. La estructura es aprovechable; las garantías aún no lo son.

`manager-core`: responsabilidades adecuadas, garantías insuficientes.

`LocalPackageDatabase` y `PackageInstaller` corresponden al papel de dpkg. Es correcto que el estado instalado no dependa de la conexión a los repositorios.

La incompatibilidad más importante no es la existencia de estas clases, sino la falta de una unidad transaccional que coordine:

- La modificación de archivos.
- El inventario de propiedad.
- El registro persistido.
- La actualización en memoria.
- La recuperación ante errores.

La persistencia atómica de un archivo no resuelve por sí sola esta coordinación.

`manager-apt`: separación nominal correcta, resolución funcional incompleta.

`RepositoryManager`, `DependencyResolver` y `AptInstaller` expresan las responsabilidades adecuadas. Sin embargo, el resolver no genera un grafo transitivo real y la adquisición no valida la identidad del candidato contra los metadatos como exige el contrato.

Este es un problema estructural de la implementación del resolver, no un simple bug de presentación o de comandos.

`ManagerFacade`: útil como punto de composición, pero con integración incompleta.

Centralizar la inicialización y exponer operaciones públicas es razonable. No obstante, la fachada no convierte automáticamente en correctos los componentes que conecta.

Además, la inicialización debe manejar explícitamente la ausencia de repositorios o de índices válidos si el sistema pretende operar sin conexión o permitir instalaciones locales.

`ModuleLifecycleAdapter`: incompatible con un contrato de ciclo de vida efectivo.

Este componente presenta la incompatibilidad más clara entre la API anterior y la implementación nueva. Devolver resultados positivos sin ejecutar las operaciones anunciadas no constituye una adaptación funcional.

La separación de paquetes APT/dpkg puede coexistir con el modelo de módulos de TxF, pero no sustituye por sí sola al mecanismo de carga, activación y desactivación de las plataformas.

### Causas raíz y consecuencias

\#chatgpt-mermaid-\_r_lm\_{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;fill:rgb(237, 237, 237);}@keyframes edge-animation-frame{from{stroke-dashoffset:0;}}@keyframes dash{to{stroke-dashoffset:0;}}#chatgpt-mermaid-\_r_lm\_ .edge-animation-slow{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 50s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_lm\_ .edge-animation-fast{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 20s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_lm\_ .error-icon{fill:rgb(48, 48, 48);}#chatgpt-mermaid-\_r_lm\_ .error-text{fill:rgb(237, 237, 237);stroke:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_lm\_ .edge-thickness-normal{stroke-width:1px;}#chatgpt-mermaid-\_r_lm\_ .edge-thickness-thick{stroke-width:3.5px;}#chatgpt-mermaid-\_r_lm\_ .edge-pattern-solid{stroke-dasharray:0;}#chatgpt-mermaid-\_r_lm\_ .edge-thickness-invisible{stroke-width:0;fill:none;}#chatgpt-mermaid-\_r_lm\_ .edge-pattern-dashed{stroke-dasharray:3;}#chatgpt-mermaid-\_r_lm\_ .edge-pattern-dotted{stroke-dasharray:2;}#chatgpt-mermaid-\_r_lm\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_lm\_ .marker.cross{stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_lm\_ svg{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;}#chatgpt-mermaid-\_r_lm\_ p{margin:0;}#chatgpt-mermaid-\_r_lm\_ .label{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_lm\_ .cluster-label text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_lm\_ .cluster-label span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_lm\_ .cluster-label span p{background-color:transparent;}#chatgpt-mermaid-\_r_lm\_ .label text,#chatgpt-mermaid-\_r_lm\_ span{fill:rgb(237, 237, 237);color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_lm\_ .node rect,#chatgpt-mermaid-\_r_lm\_ .node circle,#chatgpt-mermaid-\_r_lm\_ .node ellipse,#chatgpt-mermaid-\_r_lm\_ .node polygon,#chatgpt-mermaid-\_r_lm\_ .node path{fill:rgb(9, 23, 44);stroke:rgb(31, 78, 148);stroke-width:1px;}#chatgpt-mermaid-\_r_lm\_ .rough-node .label text,#chatgpt-mermaid-\_r_lm\_ .node .label text,#chatgpt-mermaid-\_r_lm\_ .image-shape .label,#chatgpt-mermaid-\_r_lm\_ .icon-shape .label{text-anchor:middle;}#chatgpt-mermaid-\_r_lm\_ .node .katex path{fill:#000;stroke:#000;stroke-width:1px;}#chatgpt-mermaid-\_r_lm\_ .rough-node .label,#chatgpt-mermaid-\_r_lm\_ .node .label,#chatgpt-mermaid-\_r_lm\_ .image-shape .label,#chatgpt-mermaid-\_r_lm\_ .icon-shape .label{text-align:center;}#chatgpt-mermaid-\_r_lm\_ .node.clickable{cursor:pointer;}#chatgpt-mermaid-\_r_lm\_ .root .anchor path{fill:rgb(175, 175, 175)!important;stroke-width:0;stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_lm\_ .arrowheadPath{fill:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_lm\_ .edgePath .path{stroke:rgb(175, 175, 175);stroke-width:1px;}#chatgpt-mermaid-\_r_lm\_ .flowchart-link{stroke:rgb(175, 175, 175);fill:none;}#chatgpt-mermaid-\_r_lm\_ .edgeLabel{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_lm\_ .edgeLabel p{background-color:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_lm\_ .edgeLabel rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_lm\_ .labelBkg{background-color:rgba(0, 0, 0, 0.5);}#chatgpt-mermaid-\_r_lm\_ .cluster rect{fill:rgb(48, 48, 48);stroke:rgba(255, 255, 255, 0.15);stroke-width:1px;}#chatgpt-mermaid-\_r_lm\_ .cluster text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_lm\_ .cluster span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_lm\_ div.mermaidTooltip{position:absolute;text-align:center;max-width:200px;padding:2px;font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:12px;background:rgb(48, 48, 48);border:1px solid rgba(255, 255, 255, 0.15);border-radius:2px;pointer-events:none;z-index:100;}#chatgpt-mermaid-\_r_lm\_ .flowchartTitleText{text-anchor:middle;font-size:18px;fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_lm\_ rect.text{fill:none;stroke-width:0;}#chatgpt-mermaid-\_r_lm\_ .icon-shape,#chatgpt-mermaid-\_r_lm\_ .image-shape{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_lm\_ .icon-shape p,#chatgpt-mermaid-\_r_lm\_ .image-shape p{background-color:rgb(0, 0, 0);padding:2px;}#chatgpt-mermaid-\_r_lm\_ .icon-shape .label rect,#chatgpt-mermaid-\_r_lm\_ .image-shape .label rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_lm\_ .label-icon{display:inline-block;height:1em;overflow:visible;vertical-align:-0.125em;}#chatgpt-mermaid-\_r_lm\_ .node .label-icon path{fill:currentColor;stroke:revert;stroke-width:revert;}#chatgpt-mermaid-\_r_lm\_ .node .neo-node{stroke:rgb(31, 78, 148);}#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].node rect,#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].cluster rect,#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].node polygon{stroke:url(#chatgpt-mermaid-\_r_lm\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].swimlane.cluster rect{filter:none;}#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].node path{stroke:url(#chatgpt-mermaid-\_r_lm\_-gradient);stroke-width:1px;}#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].node .outer-path{filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].node .neo-line path{stroke:rgb(31, 78, 148);filter:none;}#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].node circle{stroke:url(#chatgpt-mermaid-\_r_lm\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].node circle .state-start{fill:#000000;}#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].icon-shape .icon{fill:url(#chatgpt-mermaid-\_r_lm\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_lm\_ [data-look="neo"].icon-shape .icon-neo path{stroke:url(#chatgpt-mermaid-\_r_lm\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_lm\_ .node text{font-size:14px;font-weight:600;letter-spacing:normal;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_lm\_ .edgeLabels text{font-size:13px;font-weight:600;letter-spacing:-0.08px;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_lm\_ .node tspan[font-weight="normal"],#chatgpt-mermaid-\_r_lm\_ .edgeLabels tspan[font-weight="normal"]{font-weight:600;}#chatgpt-mermaid-\_r_lm\_ .edgeLabel .label rect{opacity:1;rx:13px;ry:13px;fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-width:1px;}#chatgpt-mermaid-\_r_lm\_ .node rect,#chatgpt-mermaid-\_r_lm\_ .node circle,#chatgpt-mermaid-\_r_lm\_ .node ellipse,#chatgpt-mermaid-\_r_lm\_ .node polygon,#chatgpt-mermaid-\_r_lm\_ .node path{fill:rgb(0, 40, 77);stroke:rgba(255, 255, 255, 0.1);stroke-width:1px;}#chatgpt-mermaid-\_r_lm\_ .node rect{rx:16px;ry:16px;}#chatgpt-mermaid-\_r_lm\_ .node.mermaid-decision .label-container{fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-dasharray:2,2;}#chatgpt-mermaid-\_r_lm\_ .edgePaths .flowchart-link{stroke:rgb(175, 175, 175);stroke-width:1px;stroke-linecap:round;stroke-linejoin:round;}#chatgpt-mermaid-\_r_lm\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_lm\_ :root{--mermaid-font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";}Parser y contratosincompletosÍndices y candidatosincorrectosResolución dedependenciasincompletaPlanes que nogarantizancompatibilidadFallos durante lainstalaciónInstalación sintransacción integralArchivos residualesEstado divergentePropiedad por nombrede paqueteConflictos entreversionesEliminación overificación incoherentesAdaptador de ciclo devida incompletoInstalado no equivale aactivado

Las causas raíz que dominan el diagnóstico son:

1. Contratos de metadatos insuficientemente estrictos.
2. Resolver que no implementa el grafo exigido.
3. Instalación y eliminación sin transacciones completas.
4. Modelo de propiedad insuficiente para versiones y paquetes compartidos.
5. Adaptación incompleta entre gestión de paquetes y ciclo de vida del runtime.

Estos problemas están relacionados, pero no son intercambiables. Corregir el parser no vuelve transaccional la instalación; corregir la persistencia no hace que el resolver conozca dependencias transitivas.

La evidencia justifica sustituir o rediseñar partes fundamentales del resolver y del mecanismo de instalación transaccional. No justifica descartar automáticamente todos los modelos de dominio, parsers auxiliares o mecanismos de hash sin evaluar su utilidad individual.

## J. Conclusión

### 1. ¿El Manager actual hace realmente lo que aparenta hacer?

Parcialmente. Tiene operaciones reales de configuración, consulta de índices, descarga, instalación, persistencia y eliminación. Sin embargo, sus garantías no corresponden todavía a las de un gestor de paquetes completo y fiable.

### 2. ¿Qué operaciones funcionan correctamente y bajo qué condiciones?

La escritura atómica del archivo de estado, las comparaciones numéricas básicas de versiones y las comprobaciones de hashes son mecanismos válidos bajo sus condiciones de uso.

La instalación local puede funcionar con paquetes sencillos, manifiestos válidos, dependencias ya satisfechas y ausencia de conflictos de rutas. No se ha demostrado que ese éxito se mantenga ante fallos intermedios, actualizaciones o paquetes compartidos.

### 3. ¿Cuáles son los bugs más importantes?

Los de mayor impacto son:

- MGR-001: parser del índice que lee el campo incorrecto.
- MGR-002: ausencia de resolución transitiva real.
- MGR-003: rollback incompleto de instalación.
- MGR-004: protección insuficiente frente a enlaces simbólicos.
- MGR-005: actualización que puede dejar versiones y archivos incoherentes.
- MGR-006: eliminación que puede desregistrar paquetes con archivos residuales.
- MGR-009: validación inconsistente de dependencias con restricciones.
- MGR-010: divergencia entre memoria y estado persistido.
- MGR-013: adaptador de ciclo de vida que puede comunicar éxito sin realizar la operación.

### 4. ¿Qué comportamientos inesperados puede observar un usuario?

Entre otros:

- Índices válidos que no se pueden cargar.
- Paquetes que aparecen como resolubles y fallan durante la instalación.
- Instalaciones fallidas que dejan archivos en disco.
- Versiones antiguas y nuevas registradas simultáneamente.
- Eliminaciones que informan éxito operativo pese a dejar archivos residuales.
- Paquetes instalados que no se encuentran realmente activados.
- Estado en memoria que difiere del archivo `status` tras un error de persistencia.

### 5. ¿Qué requisitos no están implementados?

No se demuestra la implementación completa de:

- Resolución transitiva con restricciones de versiones.
- Compatibilidad efectiva por arquitectura, sistema operativo y plataforma.
- Planificación topológica de operaciones.
- Transacciones multi-paquete con rollback integral.
- Recuperación persistente tras interrupciones.
- Propiedad segura de archivos compartidos.
- Protección completa contra rutas redirigidas mediante enlaces simbólicos.
- Activación y desactivación reales mediante el adaptador de ciclo de vida.
- Contrato completo de comandos, permisos y operación en todas las plataformas.

### 6. ¿Qué componentes actuales son técnicamente sólidos?

La división entre SPI, núcleo local y adquisición remota es razonable. También son aprovechables la base de datos local, la escritura temporal de `status`, el modelo de versiones y la verificación criptográfica, siempre que se completen sus validaciones y contratos.

### 7. ¿Qué problemas impiden considerar el Manager fiable?

La combinación de resolución incompleta, instalación no transaccional, propiedad de archivos insuficiente y recuperación limitada. Estos problemas pueden producir un estado en el que el registro instalado no representa fielmente los archivos físicos del sistema.

### 8. ¿Qué evidencias faltan para una conclusión definitiva?

Faltan la ejecución de las suites de pruebas y de la compilación, la reproducción aislada de los defectos, la inspección completa de los comandos y adaptadores de plataforma, y la verificación de los artefactos de distribución.

Veredicto final: la implementación actual contiene una base arquitectónica aprovechable, pero no cumple la especificación de reingeniería en aspectos fundamentales. No debe considerarse fiable para operaciones generales de instalación, actualización y eliminación hasta que se demuestren las garantías de resolución, integridad, transacción y recuperación exigidas por el contrato.

La evidencia estática justifica una revisión profunda del resolver, del instalador local y del adaptador de ciclo de vida. La decisión de reemplazar el Manager completo debe apoyarse en esa incompatibilidad funcional demostrada, no en la mera existencia de defectos localizados ni en la ausencia de pruebas ejecutadas.
