# Auditoría técnica del Module Manager — TextFormatter Suite

Base de análisis: `TextFormatter-Suite-main (2).zip` y especificación `docs/NEW-MANAGER.md` · 10 de octubre de 2026

## A. Veredicto ejecutivo

El Module Manager presenta una separación arquitectónica reconocible entre la adquisición de paquetes (APT) y su instalación local (dpkg), pero la implementación todavía no ofrece garantías suficientes de corrección, integridad transaccional y compatibilidad para considerarse fiable.

He inspeccionado el código del archivo ZIP, incluyendo los tres módulos `manager-spi`, `manager-core` y `manager-apt`, los adaptadores, la integración con los comandos de Spigot, la configuración de Gradle y los tests disponibles. El análisis también contrasta el código con `docs/NEW-MANAGER.md`, que contiene la especificación normativa de la reingeniería.

## Diagnóstico principal

La arquitectura APT/dpkg existe realmente.

Hay componentes separados para repositorios, resolución de dependencias, instalación, inventario local y persistencia.

Hay defectos funcionales importantes.

El comodín `*` no funciona correctamente en el comparador de versiones, aunque es el valor predeterminado usado por los comandos de instalación. También existen problemas en la actualización y en la consistencia de instalaciones.

Las garantías de seguridad y recuperación son insuficientes.

La extracción no valida las rutas antes de crear sus directorios, las escrituras de archivos no constituyen una transacción completa y determinadas operaciones pueden dejar archivos y registros en estados incompatibles.

La verificación dinámica quedó incompleta.

No fue posible ejecutar los tests porque el wrapper de Gradle intentó descargar Gradle 8.10 y el entorno no pudo completar la conexión. Los hallazgos estáticos no deben confundirse con fallos reproducidos mediante ejecución.

### Evaluación global

| Dimensión                           | Veredicto                                               |
| ----------------------------------- | ------------------------------------------------------- |
| Separación APT/dpkg                 | Parcialmente correcta                                   |
| Arquitectura modular                | Razonable, con integración incompleta                   |
| Parser y formatos                   | Parciales                                               |
| Comparación de versiones            | Incorrecta en casos relevantes                          |
| Resolución de dependencias          | Parcial e inconsistente                                 |
| Descarga e integridad               | Parcialmente implementadas                              |
| Seguridad de rutas                  | Insuficiente                                            |
| Transacciones y recuperación        | Insuficientes                                           |
| Eliminación y propiedad de archivos | Parciales, con riesgos                                  |
| Comandos de Spigot                  | Implementados parcialmente                              |
| Ciclo de vida de módulos            | No equivale a instalación y activación completas        |
| Tests                               | Existen, pero no se pudieron ejecutar en esta auditoría |

Conclusión ejecutiva: no hay evidencia que justifique descartar automáticamente toda la arquitectura actual. La separación de responsabilidades es una base aprovechable. Sin embargo, los defectos detectados afectan al núcleo del contrato de paquetes: selección de versiones, actualización, instalación segura y coherencia del estado local. Por tanto, no es correcto considerar el Manager conforme a la especificación solo porque sus principales clases y operaciones estén presentes.

## B. Mapa de arquitectura real

### Componentes encontrados

### `manager-spi` — contratos y formatos

`ManifestParser`, `RepoConfigParser`, `Version`, `PackageEntry`, `FileEntry`, `RepositoryConfig`.

Define el modelo de datos y analiza los formatos. No ejecuta por sí solo la instalación.

### `manager-apt` — adquisición y planificación

`ManagerFacade`, `RepositoryManager`, `DependencyResolver`, `AptInstaller`, `ModuleLifecycleAdapter`.

Gestiona índices, candidatos, dependencias, descargas, actualizaciones y delegación de operaciones locales.

### `manager-core` — estado e instalación local

`LocalPackageDatabase`, `PackageInstaller`, `TransactionLog`.

Persiste `library/status`, mantiene el inventario de archivos y ejecuta la extracción, el registro y la eliminación de paquetes.

### Integración con Spigot

`TextFormatterSuitePlugin`, `DynamicCommandRegistrar`, `DynamicCommand`.

Inicializa el Manager y expone operaciones de administración mediante comandos.

Las rutas anteriores corresponden a código real del ZIP:

- `src/manager-spi/src/main/java/me/majhrs16/suite/manager/spi/`
- `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/`
- `src/manager-core/src/main/java/me/majhrs16/suite/manager/core/`
- `src/spigot-host/src/main/java/me/majhrs16/suite/spigothost/`

### Flujo real de instalación desde un repositorio

\#chatgpt-mermaid-\_r_jg\_{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;fill:rgb(237, 237, 237);}@keyframes edge-animation-frame{from{stroke-dashoffset:0;}}@keyframes dash{to{stroke-dashoffset:0;}}#chatgpt-mermaid-\_r_jg\_ .edge-animation-slow{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 50s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_jg\_ .edge-animation-fast{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 20s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_jg\_ .error-icon{fill:rgb(48, 48, 48);}#chatgpt-mermaid-\_r_jg\_ .error-text{fill:rgb(237, 237, 237);stroke:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jg\_ .edge-thickness-normal{stroke-width:1px;}#chatgpt-mermaid-\_r_jg\_ .edge-thickness-thick{stroke-width:3.5px;}#chatgpt-mermaid-\_r_jg\_ .edge-pattern-solid{stroke-dasharray:0;}#chatgpt-mermaid-\_r_jg\_ .edge-thickness-invisible{stroke-width:0;fill:none;}#chatgpt-mermaid-\_r_jg\_ .edge-pattern-dashed{stroke-dasharray:3;}#chatgpt-mermaid-\_r_jg\_ .edge-pattern-dotted{stroke-dasharray:2;}#chatgpt-mermaid-\_r_jg\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jg\_ .marker.cross{stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jg\_ svg{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;}#chatgpt-mermaid-\_r_jg\_ p{margin:0;}#chatgpt-mermaid-\_r_jg\_ .label{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jg\_ .cluster-label text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jg\_ .cluster-label span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jg\_ .cluster-label span p{background-color:transparent;}#chatgpt-mermaid-\_r_jg\_ .label text,#chatgpt-mermaid-\_r_jg\_ span{fill:rgb(237, 237, 237);color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jg\_ .node rect,#chatgpt-mermaid-\_r_jg\_ .node circle,#chatgpt-mermaid-\_r_jg\_ .node ellipse,#chatgpt-mermaid-\_r_jg\_ .node polygon,#chatgpt-mermaid-\_r_jg\_ .node path{fill:rgb(9, 23, 44);stroke:rgb(31, 78, 148);stroke-width:1px;}#chatgpt-mermaid-\_r_jg\_ .rough-node .label text,#chatgpt-mermaid-\_r_jg\_ .node .label text,#chatgpt-mermaid-\_r_jg\_ .image-shape .label,#chatgpt-mermaid-\_r_jg\_ .icon-shape .label{text-anchor:middle;}#chatgpt-mermaid-\_r_jg\_ .node .katex path{fill:#000;stroke:#000;stroke-width:1px;}#chatgpt-mermaid-\_r_jg\_ .rough-node .label,#chatgpt-mermaid-\_r_jg\_ .node .label,#chatgpt-mermaid-\_r_jg\_ .image-shape .label,#chatgpt-mermaid-\_r_jg\_ .icon-shape .label{text-align:center;}#chatgpt-mermaid-\_r_jg\_ .node.clickable{cursor:pointer;}#chatgpt-mermaid-\_r_jg\_ .root .anchor path{fill:rgb(175, 175, 175)!important;stroke-width:0;stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jg\_ .arrowheadPath{fill:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jg\_ .edgePath .path{stroke:rgb(175, 175, 175);stroke-width:1px;}#chatgpt-mermaid-\_r_jg\_ .flowchart-link{stroke:rgb(175, 175, 175);fill:none;}#chatgpt-mermaid-\_r_jg\_ .edgeLabel{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_jg\_ .edgeLabel p{background-color:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_jg\_ .edgeLabel rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_jg\_ .labelBkg{background-color:rgba(0, 0, 0, 0.5);}#chatgpt-mermaid-\_r_jg\_ .cluster rect{fill:rgb(48, 48, 48);stroke:rgba(255, 255, 255, 0.15);stroke-width:1px;}#chatgpt-mermaid-\_r_jg\_ .cluster text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jg\_ .cluster span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jg\_ div.mermaidTooltip{position:absolute;text-align:center;max-width:200px;padding:2px;font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:12px;background:rgb(48, 48, 48);border:1px solid rgba(255, 255, 255, 0.15);border-radius:2px;pointer-events:none;z-index:100;}#chatgpt-mermaid-\_r_jg\_ .flowchartTitleText{text-anchor:middle;font-size:18px;fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_jg\_ rect.text{fill:none;stroke-width:0;}#chatgpt-mermaid-\_r_jg\_ .icon-shape,#chatgpt-mermaid-\_r_jg\_ .image-shape{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_jg\_ .icon-shape p,#chatgpt-mermaid-\_r_jg\_ .image-shape p{background-color:rgb(0, 0, 0);padding:2px;}#chatgpt-mermaid-\_r_jg\_ .icon-shape .label rect,#chatgpt-mermaid-\_r_jg\_ .image-shape .label rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_jg\_ .label-icon{display:inline-block;height:1em;overflow:visible;vertical-align:-0.125em;}#chatgpt-mermaid-\_r_jg\_ .node .label-icon path{fill:currentColor;stroke:revert;stroke-width:revert;}#chatgpt-mermaid-\_r_jg\_ .node .neo-node{stroke:rgb(31, 78, 148);}#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].node rect,#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].cluster rect,#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].node polygon{stroke:url(#chatgpt-mermaid-\_r_jg\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].swimlane.cluster rect{filter:none;}#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].node path{stroke:url(#chatgpt-mermaid-\_r_jg\_-gradient);stroke-width:1px;}#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].node .outer-path{filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].node .neo-line path{stroke:rgb(31, 78, 148);filter:none;}#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].node circle{stroke:url(#chatgpt-mermaid-\_r_jg\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].node circle .state-start{fill:#000000;}#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].icon-shape .icon{fill:url(#chatgpt-mermaid-\_r_jg\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jg\_ [data-look="neo"].icon-shape .icon-neo path{stroke:url(#chatgpt-mermaid-\_r_jg\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_jg\_ .node text{font-size:14px;font-weight:600;letter-spacing:normal;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_jg\_ .edgeLabels text{font-size:13px;font-weight:600;letter-spacing:-0.08px;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_jg\_ .node tspan[font-weight="normal"],#chatgpt-mermaid-\_r_jg\_ .edgeLabels tspan[font-weight="normal"]{font-weight:600;}#chatgpt-mermaid-\_r_jg\_ .edgeLabel .label rect{opacity:1;rx:13px;ry:13px;fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-width:1px;}#chatgpt-mermaid-\_r_jg\_ .node rect,#chatgpt-mermaid-\_r_jg\_ .node circle,#chatgpt-mermaid-\_r_jg\_ .node ellipse,#chatgpt-mermaid-\_r_jg\_ .node polygon,#chatgpt-mermaid-\_r_jg\_ .node path{fill:rgb(0, 40, 77);stroke:rgba(255, 255, 255, 0.1);stroke-width:1px;}#chatgpt-mermaid-\_r_jg\_ .node rect{rx:16px;ry:16px;}#chatgpt-mermaid-\_r_jg\_ .node.mermaid-decision .label-container{fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-dasharray:2,2;}#chatgpt-mermaid-\_r_jg\_ .edgePaths .flowchart-link{stroke:rgb(175, 175, 175);stroke-width:1px;stroke-linecap:round;stroke-linejoin:round;}#chatgpt-mermaid-\_r_jg\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_jg\_ :root{--mermaid-font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";}Comando de SpigotManagerFacade.repoInstallAptInstaller.installDependencyResolver.resolveInstallRepositoryManager.findCandidatesDescarga y lectura demetadatosConstrucción del planAptInstaller.downloadPackagePackageInstaller.installValidación del ZIP ydependenciasExtracción a temporalMovimiento de archivosLocalPackageDatabase.registerPackagePersistencia enlibrary/status

El flujo existe, pero no todas sus etapas ofrecen las garantías que sugieren sus nombres. Por ejemplo, construir un plan no garantiza que sus versiones sean compatibles, y mover todos los archivos a un temporal antes de instalarlos no garantiza que la confirmación final sea atómica.

### Flujo real de actualización

`AptInstaller.upgrade()` solicita un plan a `DependencyResolver.resolveUpgrade()`. Para cada paquete del plan, busca una versión instalada anterior, la elimina y después descarga e instala la nueva versión.

Ese orden es significativo: la versión anterior se elimina antes de comprobar que la descarga y la instalación de la nueva vayan a finalizar correctamente. Si falla una operación posterior, la versión antigua ya puede haber desaparecido.

### Flujo real de eliminación

`PackageInstaller.remove()`:

1. Busca el paquete instalado.
2. Comprueba las dependencias inversas.
3. Consulta la propiedad de los archivos.
4. Elimina los archivos que considera exclusivos.
5. Desregistra el paquete de la base de datos.
6. Si se solicitó, intenta retirar dependencias huérfanas.

La secuencia tiene protecciones útiles, pero no constituye una transacción recuperable. Un fallo al eliminar varios archivos puede dejar parte de ellos borrados y el paquete todavía registrado.

### `Module.java` y el bootstrap

En `ManagerFacade.discoverAll(ClassLoader parent)`, el código delega el descubrimiento a `ModuleLoader.discover(parent)`. No he encontrado evidencia en ese método de que el Manager instancie reflectivamente `me.majhrs16.suite.api.Module` como si fuera un servicio de paquete.

Esto es coherente con la restricción arquitectónica de la especificación: el descubrimiento de descriptores de módulos no debe confundirse con la construcción de los servicios que los puntos de entrada de cada plataforma inicializan.

No obstante, el adaptador de ciclo de vida contiene métodos como `load()`, `register()`, `unload()` y `download()` que lanzan `UnsupportedOperationException`. Por ello, el descubrimiento y la gestión de paquetes no equivalen a un sistema completo de activación y desactivación de módulos.

## C. Inventario de funcionalidades

### C.1. Correctas o razonablemente bien encaminadas

| Funcionalidad                                 | Evidencia                                    | Evaluación                                                             |
| --------------------------------------------- | -------------------------------------------- | ---------------------------------------------------------------------- |
| Separación de módulos                         | `manager-spi`, `manager-core`, `manager-apt` | Separación real de responsabilidades                                   |
| Persistencia del estado                       | `LocalPackageDatabase.java`                  | Escritura temporal y reemplazo atómico del archivo `status`            |
| Comprobación de SHA-256                       | `PackageInstaller.java`, `AptInstaller.java` | Hashes calculados sobre los bytes del archivo                          |
| Detección de archivos duplicados en metadatos | `ManifestParser.java`                        | Rechaza rutas duplicadas dentro de la sección `Files`                  |
| Detección de dependencias inversas            | `LocalPackageDatabase.java`                  | Existe búsqueda por nombre del paquete                                 |
| Verificación de archivos instalados           | `verifyPackage()`                            | Comprueba existencia, tamaño y hash                                    |
| Índices en caché                              | `RepositoryManager.java`                     | Carga y almacena índices locales                                       |
| Configuración de repositorios                 | `RepoConfigParser.java`                      | Analiza YAML, valida ciertos campos y ordena por prioridad             |
| Descubrimiento de módulos                     | `ManagerFacade.discoverAll()`                | Delega en `ModuleLoader`, sin instanciar directamente los descriptores |

Estas funciones son reales, pero el alcance de cada garantía es limitado. En particular, la escritura atómica de `status` no convierte en atómica una instalación completa.

### C.2. Parciales

- Resolución de dependencias directas y transitivas.
- Selección de candidatos por versión y prioridad.
- Validación de los metadatos de los paquetes.
- Descarga, caché y comprobación de artefactos.
- Registro de propiedad de archivos.
- Eliminación de dependencias huérfanas.
- Detección de actualizaciones.
- Adaptación del contrato antiguo `ModuleLifecycle` al Manager nuevo.
- Integración de los comandos de administración.

### C.3. Incorrectas o con defectos confirmados en el código

- Comparación con el comodín `*`.
- Tratamiento de las restricciones de versión de las dependencias.
- Comprobación de compatibilidad del candidato.
- Garantías transaccionales de la instalación.
- Orden de las operaciones de actualización.
- Registro de propiedad de archivos tras cargar `status` desde disco.
- Coherencia entre archivos existentes y registros persistidos en determinados fallos.

### C.4. Ausentes o no verificadas

La inspección no permite considerar implementados, con garantías suficientes, los siguientes contratos de la especificación:

- Recuperación completa de transacciones interrumpidas.
- Rollback de una instalación que ya ha sobrescrito archivos.
- Protección completa frente a rutas maliciosas y cambios concurrentes del sistema de archivos.
- Gestión fiable de versiones diferentes y conflictos complejos entre paquetes.
- Activación y desactivación dinámica general de módulos.
- Soporte completo de todos los comandos y contratos normativos.
- Funcionamiento equivalente del Manager en todas las plataformas.
- Pruebas de integración que demuestren la recuperación ante fallos.

## D. Hallazgos técnicos

Los hallazgos siguientes distinguen entre defectos demostrables por inspección estática, riesgos que dependen de determinadas condiciones y comportamientos que necesitan una reproducción dinámica. Los números de línea corresponden al archivo incluido en el ZIP, no a una versión remota que no haya sido verificada.

### MGR-001 — El comodín `*` no satisface ninguna versión normal



Severidad alta

Bug funcional

Confirmado por código



Ubicación: `src/manager-spi/src/main/java/me/majhrs16/suite/manager/spi/Version.java`, aproximadamente líneas 140–175 y método `matchesWildcard()`.

Evidencia. La implementación transforma los caracteres `*`, `x` y `X` en `\d+` y aplica una expresión regular a la versión original. No trata el patrón aislado `*` como una restricción que acepta cualquier versión.

Por ejemplo, la versión `1.2.3` no coincide con la expresión `\d+`.

El problema llega al flujo real: `DynamicCommand.java` utiliza `*` como versión predeterminada al invocar instalaciones desde repositorios; `DependencyResolver` y `RepositoryManager` utilizan `Version.satisfies()` para filtrar candidatos.

Precondiciones: se solicita una instalación sin especificar versión, usando el valor predeterminado `*`.

Comportamiento actual: los candidatos con versiones semánticas normales no satisfacen esa restricción y pueden ser descartados.

Comportamiento esperado: aceptar cualquier versión válida cuando se solicita `*`.

Consecuencia: una instalación sin versión explícita puede fallar aunque el repositorio contenga el paquete solicitado.

Confianza: alta. La incompatibilidad se deduce directamente de la expresión regular y su uso; no se ha podido ejecutar una reproducción dinámica.

### MGR-002 — La comprobación de compatibilidad siempre acepta el candidato



Severidad alta

Incumplimiento funcional

Confirmado por código



Ubicación: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/DependencyResolver.java`, líneas 288–292.

```
private boolean isCompatible(PackageCandidate candidate) {
    // Check Arch, OS, Platform compatibility from manifest
    // For now, assume compatible
    return true;
}
```

Precondiciones: un candidato es seleccionado para instalación.

Comportamiento actual: `isCompatible()` siempre devuelve `true`. Los campos `Arch`, `OS` y `Platform` no intervienen en esa decisión.

Comportamiento esperado: rechazar los candidatos incompatibles con el entorno, de acuerdo con las reglas de la especificación.

Consecuencia: el resolver no ofrece una garantía efectiva de compatibilidad. Un artefacto incorrecto para la plataforma puede avanzar hasta la descarga y la instalación.

Clasificación: funcionalidad incompleta implementada mediante una aceptación incondicional, no una mera ausencia de pruebas.

### MGR-003 — La actualización elimina la versión antigua antes de validar la nueva



Severidad alta

Integridad

Confirmado por código



Ubicación: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/AptInstaller.java`, método `upgrade()`.

Evidencia. El flujo elimina la versión instalada si es distinta de la candidata y después llama a `downloadPackage(candidate)` y a `installer.install(...)`.

Precondiciones: existe una versión instalada y la descarga, la verificación o la instalación de la versión nueva falla.

Comportamiento actual: la versión anterior puede haber sido eliminada cuando se detecta el fallo de actualización.

Comportamiento esperado: mantener el paquete anterior operativo hasta que la sustitución pueda completarse o exista un mecanismo de rollback efectivo.

Consecuencia: pérdida de disponibilidad y posible pérdida de archivos o configuración administrados por el paquete.

Reproducción propuesta: instalar una versión, provocar un fallo de descarga de la actualización y comprobar el inventario y los archivos que quedan.

Confianza: alta respecto al orden del código; pendiente de reproducir el resultado completo en ejecución.

### MGR-004 — La instalación no es una transacción atómica



Severidad alta

Integridad

Confirmado por código



Ubicación: `src/manager-core/src/main/java/me/majhrs16/suite/manager/core/PackageInstaller.java`, líneas 90–155 y 417–440.

Evidencia. El instalador extrae los archivos a un directorio temporal y después los mueve al destino final, con `REPLACE_EXISTING` y `ATOMIC_MOVE`. A continuación registra cada paquete individualmente en la base de datos.

No hay una operación global de rollback que restaure todos los archivos previamente movidos y los registros anteriores si falla un movimiento o un registro posterior.

Precondiciones: una instalación contiene varios archivos o paquetes y una operación falla después de haber modificado el destino final.

Comportamiento actual: puede quedar una instalación parcial. Si se sobrescribieron archivos preexistentes, el directorio temporal no conserva por sí solo una copia recuperable de las versiones anteriores.

Comportamiento esperado: confirmar toda la operación o restaurar de forma coherente el estado previo.

Consecuencia: archivos huérfanos, sobrescrituras persistentes y divergencia entre disco e inventario.

Confianza: alta. La ausencia de una reversión global es visible en el flujo; la reproducción de cada escenario requiere pruebas.

### MGR-005 — Las rutas se validan después de crear directorios



Severidad alta

Seguridad

Riesgo confirmado por código



Ubicación: `src/manager-core/src/main/java/me/majhrs16/suite/manager/core/PackageInstaller.java`, método `extractPackageFilesToTemp()`, líneas 349–399.

Evidencia. El código resuelve la ruta indicada en los metadatos y ejecuta `Files.createDirectories(destPath.getParent())` antes de normalizar la ruta y comprobar si permanece dentro del directorio temporal.

Precondiciones: un ZIP contiene metadatos con rutas absolutas o rutas que incluyen componentes `..`.

Comportamiento actual: la validación puede rechazar la ruta antes de escribir el archivo, pero la creación de directorios ya se ha intentado sobre el padre de la ruta sin validar.

Comportamiento esperado: normalizar y validar las rutas antes de realizar cualquier operación de escritura o creación de directorios fuera del ámbito autorizado.

Consecuencia: un paquete malicioso podría provocar efectos no deseados en el sistema de archivos incluso si finalmente se rechaza su extracción. El alcance concreto depende de los permisos del proceso y de las rutas suministradas.

Clasificación: riesgo de seguridad real en el orden de las operaciones; no se afirma que se haya demostrado una explotación arbitraria.

### MGR-006 — La instalación forzada puede sobrescribir archivos que siguen perteneciendo a otro paquete



Severidad alta

Integridad

Confirmado por código



Ubicación: `PackageInstaller.java`, métodos `checkFileConflicts()` y `moveFilesToFinalLocation()`.

Evidencia. Con `force=true`, los conflictos de propiedad se registran como advertencias, pero la operación continúa. El movimiento final usa `REPLACE_EXISTING`. No se actualiza automáticamente la propiedad del archivo anterior como parte de una transacción de sustitución.

Precondiciones: un paquete intenta instalar un archivo que figura como propiedad de otro paquete, utilizando la opción forzada.

Comportamiento actual: el archivo puede sobrescribirse mientras el registro del propietario anterior permanece intacto.

Comportamiento esperado: mantener una relación coherente entre los archivos del disco y todos los propietarios registrados.

Consecuencia: al eliminar cualquiera de los paquetes, el Manager puede actuar sobre un archivo que ya no contiene los datos correspondientes a su inventario.

Confianza: alta en cuanto a la sobrescritura y la ausencia de una transferencia de propiedad en ese flujo.

### MGR-007 — El inventario de propietarios se reconstruye de forma inconsistente



Severidad alta

Persistencia

Confirmado por código



Ubicación: `src/manager-core/src/main/java/me/majhrs16/suite/manager/core/LocalPackageDatabase.java`, líneas 90–99 y 218–237.

Evidencia. Al cargar `status`, la base de datos añade `entry.name()` al conjunto de propietarios. Al registrar un paquete nuevo, añade `newEntry.id()`, que incluye nombre, versión y canal.

Precondiciones: el Manager carga paquetes existentes desde disco y después realiza operaciones que consultan la propiedad de archivos compartidos.

Comportamiento actual: la misma colección puede contener identificadores con formatos diferentes según si el paquete se cargó desde disco o se registró durante la ejecución actual.

Comportamiento esperado: reconstruir la propiedad con una única representación canónica de identidad.

Consecuencia: las comprobaciones de propiedad exclusiva y compartida pueden producir resultados incorrectos en determinados estados de ejecución. En particular, la comparación `owners.contains(entry.id())` no reconoce un propietario reconstruido únicamente mediante `entry.name()`.

Confianza: alta. El defecto está demostrado en las dos rutas de actualización de la estructura.

### MGR-008 — Las restricciones de versión de las dependencias se ignoran



Severidad alta

Dependencias

Confirmado por código



Ubicación: `DependencyResolver.java`, método `getDependencyConstraint()`, líneas 174–178; `PackageInstaller.java`, líneas 315–332.

Evidencia. El resolver devuelve `*` para las dependencias, independientemente de cualquier restricción declarada. El instalador local comprueba si existe una dependencia con el nombre correcto, pero el comentario `TODO: Version constraint checking` reconoce que no compara su versión.

Precondiciones: un paquete requiere una versión mínima, máxima o exacta de una dependencia.

Comportamiento actual: el resolver no conserva la restricción declarada y la instalación local acepta una dependencia por nombre sin verificar su versión.

Comportamiento esperado: resolver las restricciones de versión y comprobar que el conjunto instalado las satisface.

Consecuencia: pueden aceptarse combinaciones de paquetes incompatibles o rechazarse combinaciones válidas por problemas secundarios de selección.

Confianza: alta.

### MGR-009 — El orden de resolución puede producir planes incorrectos



Severidad alta

Dependencias

Confirmado por código



Ubicación: `DependencyResolver.java`, método `resolvePackage()`, líneas 121–172.

Evidencia. El resolver selecciona el primer candidato, lo añade a `selected` y lo marca como resuelto antes de recorrer sus dependencias. Además, la compatibilidad se comprueba solo para el candidato seleccionado, sin intentar alternativas posteriores.

Precondiciones: el candidato con mayor prioridad de repositorio es incompatible o su conjunto de dependencias no puede resolverse, pero existe otra alternativa válida.

Comportamiento actual: la resolución puede fallar sin explorar una solución alternativa, aunque exista un candidato viable en otro repositorio.

Comportamiento esperado: evaluar la compatibilidad y las restricciones de todos los candidatos relevantes y seleccionar un conjunto coherente.

Consecuencia: falsos negativos de resolución y selección de paquetes que no constituyen una solución global válida.

Confianza: alta respecto a la falta de retroceso entre candidatos; no implica que todos los grafos de dependencias vayan a fallar.

### MGR-010 — La eliminación puede dejar archivos borrados con el paquete aún registrado



Severidad alta

Integridad

Confirmado por código



Ubicación: `PackageInstaller.java`, método `remove()`, líneas 210–234.

Evidencia. El instalador elimina secuencialmente los archivos exclusivos. Si una eliminación falla, acumula el error y lanza una excepción sin desregistrar el paquete.

Precondiciones: un paquete contiene varios archivos y la eliminación falla después de borrar al menos uno.

Comportamiento actual: algunos archivos ya no existen, pero el paquete sigue registrado en `library/status`.

Comportamiento esperado: operación recuperable, rollback o registro explícito de un estado parcial que permita reparar el inventario.

Consecuencia: el estado persistido puede afirmar que el paquete está instalado cuando faltan archivos que pertenecen a él.

Confianza: alta en cuanto a la secuencia y la ausencia de restauración; la reproducción concreta depende del comportamiento del sistema de archivos.

### MGR-011 — El sistema no ofrece una garantía de límite de tamaño para la extracción local



Severidad media

Seguridad

Riesgo confirmado por código



Ubicación: `PackageInstaller.java`, método `extractPackageFilesToTemp()`, líneas 349–399.

Evidencia. Los bytes se copian con `is.transferTo(os)`. Después se comprueba el tamaño real frente al tamaño declarado. La validación posterior detecta discrepancias, pero no limita el volumen escrito durante la extracción.

Precondiciones: el paquete local contiene datos comprimidos cuyo tamaño extraído es mucho mayor que el esperado.

Comportamiento actual: el contenido puede escribirse en el directorio temporal antes de detectar que su tamaño no coincide con los metadatos.

Comportamiento esperado: imponer límites de tamaño y volumen total antes de consumir recursos de forma descontrolada.

Consecuencia: consumo excesivo de espacio en disco y posible denegación de servicio si un operador instala un paquete malicioso o defectuoso.

Confianza: alta respecto a la ausencia del límite en este flujo. No se afirma que se haya reproducido una bomba de descompresión.

### MGR-012 — El gestor de repositorios puede conservar índices obsoletos después de un fallo



Severidad media

Integridad

Confirmado por código



Ubicación: `RepositoryManager.java`, método `updateAll()`, líneas 58–85.

Evidencia. La actualización reemplaza el índice en memoria y en disco cuando la descarga tiene éxito. Si falla, registra el error y continúa, sin retirar explícitamente la entrada anterior de `cachedIndexes`.

Precondiciones: existe un índice previamente válido y la actualización del repositorio falla.

Comportamiento actual: el Manager puede seguir utilizando el índice anterior para encontrar candidatos, pese a que la actualización solicitada no se completó.

Comportamiento esperado: comunicar claramente que el repositorio no se actualizó y definir explícitamente si se permite continuar con su índice anterior.

Consecuencia: el operador puede interpretar una actualización parcial como una actualización global satisfactoria y trabajar con información desactualizada.

Confianza: alta respecto al comportamiento de la caché. El resultado final depende de cómo el comando presente `UpdateResult`.

### MGR-013 — La descarga de metadatos y la descarga del paquete usan clientes HTTP con políticas diferentes



Severidad media

Compatibilidad

Confirmado por código



Ubicación: `DependencyResolver.java`, método `downloadPackageToTemp()`, líneas 222–245; `AptInstaller.java`, método `downloadPackage()`.

Evidencia. El resolver usa `java.net.http.HttpClient` sin configurar una política de redirecciones. El instalador de APT utiliza OkHttp, que sigue redirecciones de manera predeterminada.

Precondiciones: la URL del artefacto devuelve una redirección HTTP antes de entregar el ZIP.

Comportamiento actual: los dos flujos pueden comportarse de forma diferente. El resolver puede guardar el cuerpo de una respuesta de redirección y después fallar en la verificación del hash o tamaño.

Comportamiento esperado: aplicar una política de redirecciones y validación coherente en ambos flujos.

Consecuencia: fallos de resolución que dependen del servidor de distribución, aunque la descarga final pudiera ser válida.

Confianza: alta respecto a la diferencia de configuración; pendiente de reproducir contra un servidor real.

### MGR-014 — La compatibilidad del formato de versión es incompleta



Severidad media

Compatibilidad

Confirmado por código



Ubicación: `Version.java`, método `satisfies()` y método `matchesWildcard()`.

Además del problema del comodín global, el comparador tiene limitaciones:

- El comodín se transforma en una expresión regular aplicada a la versión original completa, en vez de evaluarse como un rango semántico bien definido.
- Los identificadores numéricos de versiones preliminares se convierten a `int`; valores que excedan ese rango pueden lanzar `NumberFormatException`.
- El operador caret para versiones `0.x.y` implementa un límite por minor, pero no distingue correctamente todos los casos de semver, como `^0.0.x`.
- La versión original y los metadatos de compilación no participan de manera uniforme en la semántica de igualdad y comparación.

Consecuencia: las restricciones de versiones no deben considerarse una implementación completa de SemVer 2.0.0.

Confianza: alta para los casos identificados en el código.

### MGR-015 — La activación y la desactivación no están implementadas como ciclo de vida completo



Severidad media

Arquitectura

Confirmado por código



Ubicación: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/ModuleLifecycleAdapter.java`, métodos `download()`, `load()`, `register()`, `unregister()` y `unload()`.

Evidencia. Varios métodos lanzan `UnsupportedOperationException` y delegan la operación conceptual a las operaciones de instalación o eliminación del nuevo Manager.

Comportamiento actual: el adaptador ofrece compatibilidad de interfaz, pero no preserva la semántica completa de las operaciones antiguas.

Comportamiento esperado: diferenciar claramente entre adquisición, instalación, activación, desactivación y retirada de un módulo.

Consecuencia: consumidores que dependan de las operaciones de carga o descarga del contrato anterior no pueden asumir que el adaptador las ejecutará.

Confianza: alta.

### MGR-016 — El estado local no tiene recuperación de transacciones interrumpidas



Severidad media

Integridad

Funcionalidad ausente



Ubicación: `LocalPackageDatabase.java`, `PackageInstaller.java` y `TransactionLog.java`.

Evidencia. Existe un `TransactionLog`, pero en el flujo de instalación y eliminación analizado no se observa una transacción global que registre cada cambio de archivo y de estado, y permita recuperar o revertir la operación al reiniciar.

Precondiciones: el proceso termina abruptamente durante una operación que ya ha modificado archivos.

Comportamiento actual: el estado recuperable se basa principalmente en el archivo `status`; no hay evidencia suficiente de un protocolo de recuperación que reconcilie todos los cambios parciales.

Comportamiento esperado: detectar operaciones incompletas y recuperar un estado coherente tras el reinicio.

Consecuencia: archivos sin registro, registros sin archivos o actualizaciones incompletas.

Confianza: alta respecto a la falta de integración de una transacción global en los flujos examinados.

### MGR-017 — La actualización de índices no es una operación global atómica



Severidad media

Consistencia

Confirmado por código



Ubicación: `RepositoryManager.java`, método `updateAll()`, líneas 58–85.

Evidencia. Los repositorios se actualizan uno por uno. Los fallos se almacenan en `result.failed`, pero el método devuelve el resultado en lugar de abortar toda la operación.

Comportamiento actual: puede haber repositorios actualizados y otros que sigan utilizando índices antiguos.

Comportamiento esperado: el contrato debe distinguir claramente entre éxito global, éxito parcial y fallo, sin presentar una actualización parcial como una operación completa.

Consecuencia: el conjunto de candidatos depende del estado individual de cada repositorio.

Confianza: alta. Es una propiedad del diseño actual; que sea un bug de interfaz depende del tratamiento que haga el comando de los resultados parciales.

### MGR-018 — El inventario de archivos no constituye una protección suficiente frente a modificaciones locales



Severidad media

Integridad

Limitación



Ubicación: `LocalPackageDatabase.java`, métodos `verifyPackage()` y `checkOrphanedFiles()`.

Evidencia. La verificación detecta diferencias de tamaño y hash, pero el proceso de eliminación no usa esa verificación para decidir si debe retirar un archivo modificado localmente.

Precondiciones: un operador o una herramienta externa modifica un archivo instalado.

Comportamiento actual: el Manager puede eliminar el archivo si considera que pertenece exclusivamente al paquete, aunque su contenido ya no coincida con el inventario original.

Comportamiento esperado: que la política de eliminación defina explícitamente cómo tratar los archivos modificados, evitando pérdidas silenciosas.

Consecuencia: pérdida de cambios locales que el Manager no identifica como tales antes de borrar.

Confianza: alta en cuanto a la ausencia de una comprobación de integridad previa a la eliminación.

## E. Comportamientos esperados frente a los reales

| Operación                     | Comportamiento real observado en el código                                                      | Resultado                                              |
| ----------------------------- | ----------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| Instalar sin versión          | Usa `*`, que no coincide correctamente con versiones normales                                   | Puede fallar al seleccionar candidatos                 |
| Instalar desde un ZIP         | Valida metadatos, hashes y tamaños antes de registrar el paquete                                | Parcialmente correcto; sin rollback global             |
| Instalar dependencias         | Resuelve recursivamente, pero descarta restricciones de versión                                 | No garantiza una solución compatible                   |
| Actualizar índices            | Actualiza cada repositorio por separado y conserva cachés anteriores si falla una actualización | Resultado potencialmente parcial                       |
| Actualizar paquetes           | Retira la versión antigua antes de instalar la nueva                                            | Riesgo de pérdida de disponibilidad                    |
| Eliminar paquete              | Comprueba dependencias inversas y propiedad de archivos                                         | Protección útil, pero sin recuperación completa        |
| Eliminar con `autoRemove`     | Intenta eliminar dependencias de tipo `DEPENDENCY` sin consumidores                             | Parcial; depende de que el inventario sea coherente    |
| Verificar archivos            | Comprueba existencia, tamaño y SHA-256                                                          | Correcto para detectar modificaciones, no para reparar |
| Reiniciar después de un fallo | Carga el inventario persistido                                                                  | No garantiza reconciliación con operaciones parciales  |
| Activar un módulo             | No se deduce automáticamente de que esté instalado                                              | La activación general no está garantizada              |

### Garantías reales frente a garantías aparentes

Garantías que sí existen en el código:

- Se rechazan índices remotos con SHA-256 mal formado.
- Se comprueba el hash de los archivos extraídos frente al inventario declarado.
- Se utiliza un archivo temporal para escribir `status` antes de sustituir el archivo anterior.
- Se consultan las dependencias inversas antes de eliminar un paquete.
- Se comprueban ciertos conflictos de propiedad de archivos.

Garantías que no existen de forma suficiente:

- Que una actualización preserve siempre la versión anterior hasta completar la nueva.
- Que cualquier operación multi-paquete se confirme o revierta como una unidad.
- Que un paquete compatible sea seleccionado entre todos los candidatos posibles.
- Que un archivo solo se elimine si continúa siendo seguro hacerlo.
- Que una instalación registrada en `status` esté necesariamente completa.
- Que el éxito del gestor de paquetes implique que el módulo está activado en el runtime.

### Integridad frente a autenticidad

El Manager comprueba hashes SHA-256, lo cual permite detectar diferencias respecto al hash esperado. Sin embargo, eso no autentica por sí mismo al editor del paquete: si un repositorio malicioso puede controlar simultáneamente el artefacto y el índice que declara su hash, la comprobación no demuestra que el contenido sea confiable.

La especificación exige distinguir estas dos propiedades. El código actual implementa comprobaciones de integridad, pero no he encontrado evidencia suficiente de un mecanismo de firma y autenticación de editores que garantice la procedencia confiable de los paquetes.

## F. Aspectos correctos que deben preservarse

No todos los problemas requieren sustituir el componente afectado.

### F.1. Separación de responsabilidades

Los módulos `manager-spi`, `manager-core` y `manager-apt` representan una división razonable:

- Los formatos y contratos están en el SPI.
- La instalación local y la base de datos están en el core.
- Los repositorios y la planificación están en APT.

La separación es estructuralmente real, no una mera agrupación de clases por nombres. Lo que necesita revisión son las garantías de los contratos entre esos componentes.

### F.2. Escritura temporal de la base de datos

`LocalPackageDatabase.save()` escribe el contenido en un archivo temporal y después utiliza `Files.move()` con `REPLACE_EXISTING` y `ATOMIC_MOVE`.

Es una decisión correcta para reducir el riesgo de dejar un archivo `status` parcialmente escrito. La garantía es local al archivo de estado y depende del soporte del sistema de archivos; no cubre los archivos de los paquetes ni el conjunto de operaciones.

### F.3. Verificación de integridad

El cálculo del hash se realiza leyendo los bytes del archivo. El instalador verifica tamaño y hash de los archivos extraídos, y la base de datos puede volver a comprobarlos posteriormente.

Esto aporta una base útil para detectar corrupción. La limitación es que las verificaciones deben integrarse con la propiedad de archivos y las operaciones de eliminación para que la información resultante tenga consecuencias operativas coherentes.

### F.4. Rechazo de metadatos manifiestamente inválidos

`ManifestParser` comprueba campos obligatorios, valores de tamaño, formato SHA-256 y duplicados de rutas dentro de la sección `Files`. También diferencia el formato de índice remoto del formato de manifiesto interno.

Son controles positivos que conviene conservar conceptualmente, aunque la gramática todavía necesita una validación más estricta de sus campos, semántica y compatibilidad.

### F.5. Configuración de repositorios

`RepoConfigParser` utiliza SnakeYAML con `SafeConstructor`, valida los nombres y las URLs, detecta nombres y URLs duplicados y ordena los repositorios por prioridad.

El parser constituye una base razonable. No obstante, sus validaciones no bastan para garantizar que cada protocolo de repositorio, sus opciones y las URLs resultantes se comporten de forma segura durante la adquisición.

### F.6. Pruebas unitarias existentes

Hay tests en los tres módulos, incluidos:

- `src/manager-spi/src/test/java/me/majhrs16/suite/manager/spi/ManifestParserTest.java`
- `src/manager-spi/src/test/java/me/majhrs16/suite/manager/spi/VersionTest.java`
- `src/manager-core/src/test/java/me/majhrs16/suite/manager/core/LocalPackageDatabaseTest.java`
- `src/manager-core/src/test/java/me/majhrs16/suite/manager/core/PackageInstallerTest.java`
- `src/manager-apt/src/test/java/me/majhrs16/suite/manager/apt/RepositoryManagerTest.java`

Estos tests son evidencia de que existen esfuerzos de verificación, no de que el sistema global funcione correctamente. Algunos tests del instalador contienen comentarios que reconocen que ciertos escenarios no llegan a ejecutarse completamente.

## G. Matriz de cumplimiento de la especificación

La siguiente matriz evalúa los requisitos principales de `docs/NEW-MANAGER.md`. El estado se refiere al código inspeccionado, no a resultados de ejecución.

| Área normativa                              | Estado            | Evidencia y limitación                                                                                        |
| ------------------------------------------- | ----------------- | ------------------------------------------------------------------------------------------------------------- |
| Arquitectura APT/dpkg                       | PARCIAL           | Separación en tres módulos, pero sin transacciones completas                                                  |
| Configuración de repositorios               | PARCIAL           | `RepoConfigParser.java`; validación incompleta de opciones y protocolos                                       |
| Índice `packages.list`                      | PARCIAL           | `ManifestParser.parseRemoteIndex()` valida columnas y SHA-256, pero no garantiza toda la semántica del índice |
| Metadatos `METADATA/package.list`           | PARCIAL           | Parser funcional; validación semántica incompleta                                                             |
| Cabecera y bloques de metadatos             | PARCIAL           | Formato de bloques implementado; no existe garantía de todos los contratos normativos                         |
| Campos `Name`, `Version`, `Channel`, `Type` | PARCIAL           | Se parsean; no todos intervienen correctamente en las decisiones posteriores                                  |
| Campos `Dependencies`                       | INCORRECTO        | Se ignoran restricciones de versión                                                                           |
| Campos `Arch`, `Platform`, `OS`             | INCORRECTO        | `isCompatible()` acepta incondicionalmente                                                                    |
| SHA-256 del artefacto                       | PARCIAL           | Se verifica el hash esperado, pero no se autentica el editor                                                  |
| Tamaño de artefactos                        | PARCIAL           | Se comprueba el tamaño final; la extracción no tiene límites completos                                        |
| Comparación de versiones                    | INCORRECTO        | El comodín global falla; existen limitaciones de SemVer                                                       |
| Prioridad de repositorios                   | PARCIAL           | Ordena por prioridad y versión; no resuelve alternativas globalmente                                          |
| Dependencias transitivas                    | PARCIAL           | Hay recursión y ordenación topológica, pero restricciones y alternativas incompletas                          |
| Ciclos de dependencias                      | PARCIAL           | Existen comprobaciones, pero no demuestran una resolución global completa                                     |
| `library/status`                            | PARCIAL           | Persistencia atómica del archivo; reconstrucción inconsistente de propietarios                                |
| Inventario de archivos                      | PARCIAL           | Registra rutas, tamaños y hashes, sin garantizar propiedad coherente en todos los casos                       |
| Instalación local                           | PARCIAL           | Validación y extracción temporal; sin rollback global                                                         |
| Actualización de paquetes                   | INCORRECTO        | Puede retirar la versión anterior antes de completar la nueva                                                 |
| Eliminación                                 | PARCIAL           | Dependencias inversas y propiedad; fallos pueden dejar estado parcial                                         |
| Dependencias compartidas                    | PARCIAL           | Se detectan archivos compartidos, pero `force` puede desincronizar propietarios                               |
| Recuperación de transacciones               | AUSENTE           | No se demuestra un protocolo completo de recuperación                                                         |
| Activación y desactivación                  | AUSENTE / PARCIAL | El adaptador conserva parte de la interfaz, no el ciclo de vida completo                                      |
| Comandos de administración                  | PARCIAL           | Hay comandos reales, pero no todos los contratos de la especificación                                         |
| Seguridad de ZIP                            | PARCIAL           | Comprobación de traversal posterior a una operación sobre el sistema de archivos                              |
| Concurrencia                                | PARCIAL           | La base de datos tiene `ReadWriteLock`; no existe una transacción global coordinada                           |
| Tests de unidad                             | PARCIAL           | Existen, pero no se ejecutaron en este entorno                                                                |
| Tests de integración y recuperación         | NO VERIFICADO     | No hay evidencia suficiente de que cubran todos los escenarios exigidos                                       |
| Distribución y construcción                 | PARCIAL           | Los módulos están declarados en Gradle; la compilación no pudo verificarse                                    |

### G.1. Separación APT/dpkg

La separación es real en cuanto a las clases y a las llamadas entre componentes. Sin embargo, APT depende de `PackageInstaller` para ejecutar los cambios locales, y las operaciones de actualización pueden combinar retirada e instalación sin una transacción coordinada.

Por tanto, la separación organizativa está presente, pero la separación de responsabilidades no está acompañada de garantías transaccionales suficientes.

### G.2. Configuración, índices y metadatos

Se encontraron:

- `repo.yml`, utilizado por `ManagerFacade`.
- `library/status`, utilizado por `LocalPackageDatabase`.
- Índices almacenados en `library/lists`.
- `METADATA/package.list`, exigido por el instalador.

No he encontrado evidencia suficiente para afirmar que todos los contratos normativos de `repositories.yml`, `packages.list`, `status` y los campos del manifiesto estén implementados exactamente como los exige la especificación. El código utiliza `repo.yml`, por lo que también existe una diferencia de nomenclatura que debe contrastarse con el contrato de configuración deseado.

### G.3. Comandos

`DynamicCommand.java` implementa operaciones para repositorios, paquetes locales, instalación, eliminación, verificación y actualización. Por tanto, sería incorrecto afirmar que el Manager carece de comandos.

Sin embargo, no debe equipararse esa cobertura con el cumplimiento de todas las interfaces normativas:

- `/suite repo update`, `/suite repo install`, `/suite repo remove`, `/suite repo upgrade` y `/suite repo fix-broken` tienen rutas de ejecución identificables.
- `/suite package install`, `/suite package list`, `/suite package remove`, `/suite package files` y `/suite package verify` también tienen implementación.
- Los contratos exactos `/suite dpkg -i` y `/suite dpkg -r` no quedan acreditados como comandos equivalentes en el código examinado.

Las operaciones de consola, permisos, autocompletado y tratamiento de errores requieren una comprobación independiente de cada rama; la mera presencia de un `case` no demuestra todos esos comportamientos.

## H. Resultados de pruebas y compilación

### H.1. Ejecución intentada

Se intentó ejecutar la suite de pruebas de los tres módulos del Manager:

```
bash gradlew \
  :src:manager-spi:test \
  :src:manager-core:test \
  :src:manager-apt:test \
  --no-daemon
```

Resultado: ejecución bloqueada

El wrapper intentó descargar la distribución de Gradle 8.10 desde `services.gradle.org`, pero la conexión no pudo completarse.

Esto no es un fallo de compilación del Manager ni demuestra que los tests fallen. Significa que no se pudieron ejecutar en este entorno.

No se han inventado resultados de tests ni de compilación. Los hallazgos anteriores proceden de la inspección estática del código.

### H.2. Cobertura visible en los tests

| Área                             | Evidencia disponible                                                         | Limitación                                                              |
| -------------------------------- | ---------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Parser de índices                | Tests de entradas válidas, comentarios, tamaños negativos y hashes inválidos | No cubre todos los contratos semánticos                                 |
| Parser de manifiestos            | Tests de paquetes múltiples, rutas duplicadas y serialización                | No demuestra que todas las entradas maliciosas sean rechazadas          |
| Versiones                        | `VersionTest.java`                                                           | Ejecución no verificada; la inspección revela defectos en el comparador |
| Base de datos local              | `LocalPackageDatabaseTest.java`                                              | No se verificó recuperación tras interrupciones                         |
| Instalación                      | `PackageInstallerTest.java`                                                  | Varios escenarios están descritos como pruebas incompletas              |
| Repositorios                     | `RepositoryManagerTest.java`                                                 | No se verificaron errores de red ni redirecciones en ejecución          |
| Dependencias complejas           | No hay evidencia suficiente de cobertura integral                            | No verificado                                                           |
| Transacciones multi-paquete      | No hay evidencia suficiente de cobertura integral                            | No verificado                                                           |
| Recuperación después de un fallo | No hay evidencia suficiente de cobertura integral                            | No verificado                                                           |
| Activación de módulos            | No hay evidencia suficiente de cobertura integral                            | No verificado                                                           |

### H.3. Limitaciones de esta auditoría

El archivo ZIP permite inspeccionar el código distribuido, pero no proporciona por sí solo una reproducción dinámica de cada defecto. Tampoco se ha inspeccionado un historial Git verificable de esta copia, por lo que no es posible atribuir los defectos a una regresión concreta.

Los defectos identificados por flujo de código son conclusiones estáticas. Los resultados que dependen de condiciones externas —por ejemplo, permisos, redirecciones HTTP, fallos de disco o terminación abrupta del proceso— requieren pruebas de reproducción para establecer su comportamiento efectivo en ejecución.

## I. Diagnóstico arquitectónico

La pregunta principal no es si el Manager necesita una reescritura completa, sino qué garantías faltan y si la estructura existente puede soportarlas.

### I.1. Componentes compatibles con el modelo APT/dpkg

`manager-spi`: base aprovechable.

Los modelos de paquete, archivo, repositorio y versión proporcionan una base de contratos compartidos. El parser y el comparador de versiones necesitan corregir sus limitaciones, pero no existe evidencia que obligue a descartar todo el módulo.

`manager-core`: estructura razonable, garantías insuficientes.

`LocalPackageDatabase` y `PackageInstaller` separan el estado local de las operaciones de instalación. La persistencia temporal es una decisión positiva. El problema es que los cambios de archivos y los cambios del inventario no forman una transacción global.

`manager-apt`: separación conceptual válida, resolución incompleta.

`RepositoryManager`, `DependencyResolver` y `AptInstaller` cumplen funciones distintas. No obstante, la selección de candidatos, la comprobación de compatibilidad y el manejo de errores de actualización no alcanzan las garantías normativas.

### I.2. Problemas arquitectónicos frente a problemas localizados

| Tipo de problema                            | Ejemplos                                                 | Diagnóstico                                          |
| ------------------------------------------- | -------------------------------------------------------- | ---------------------------------------------------- |
| Bug localizado                              | Comodín `*`                                              | Corrección en la lógica de versiones                 |
| Contrato semántico incompleto               | Restricciones de dependencias                            | Afecta al resolver y al instalador local             |
| Contrato de seguridad insuficiente          | Validación tardía de rutas                               | Afecta al instalador y al inventario                 |
| Incompatibilidad estructural de operaciones | Instalación sin rollback global                          | Requiere garantías coordinadas entre core y APT      |
| Contrato de ciclo de vida incompleto        | Métodos no soportados en el adaptador                    | No se resuelve con una mejora del parser             |
| Consistencia de estado                      | Propietarios reconstruidos con identificadores distintos | Defecto localizado, con consecuencias en eliminación |
| Falta de verificación                       | Recuperación y fallos de integración                     | Requiere evidencia adicional                         |

### I.3. ¿Es necesario reemplazar componentes?

No existe fundamento suficiente para recomendar una reescritura total.

- La separación entre contratos, instalación local y adquisición remota es aprovechable.
- El parser tiene controles útiles que merece la pena conservar conceptualmente.
- La persistencia del estado utiliza un mecanismo apropiado para la sustitución de un archivo.
- La resolución de dependencias requiere correcciones que afectan a su semántica, no solo a la validación de entradas.
- La instalación y eliminación requieren garantías de consistencia que el flujo actual no proporciona.
- El adaptador de ciclo de vida necesita evaluarse como un contrato funcional incompleto, no como una implementación equivalente al sistema anterior.

La distinción importante es que una arquitectura puede ser razonable sin que sus operaciones sean fiables. El Manager actual presenta precisamente esa combinación.

## J. Conclusión final

### 1. ¿El Manager actual hace realmente lo que aparenta hacer?

Parcialmente.

Tiene repositorios, índices, resolución de dependencias, instalación local, persistencia, inventario y comandos. No obstante, algunos contratos importantes son incompletos o incorrectos. La existencia de esos componentes no garantiza que las operaciones funcionen bajo todas las condiciones esperadas.

### 2. ¿Qué operaciones funcionan correctamente y bajo qué condiciones?

Los parsers y las comprobaciones de integridad ofrecen funcionalidades reales para entradas normales y manifiestos válidos. La base de datos puede persistir el inventario mediante reemplazo atómico del archivo de estado. La eliminación consulta dependencias inversas y propiedad de archivos.

Estas garantías son locales y condicionadas. No demuestran que una operación multi-paquete sea transaccional ni que el estado persistido siempre coincida con el sistema de archivos.

### 3. ¿Cuáles son los bugs más importantes?

Los defectos de mayor impacto identificados son:

1. El comodín `*` no acepta versiones normales.
2. La comprobación de compatibilidad acepta incondicionalmente los candidatos.
3. La actualización puede eliminar la versión anterior antes de instalar la nueva.
4. La instalación no tiene rollback global.
5. Las rutas se validan después de intentar crear directorios.
6. La instalación forzada puede desincronizar la propiedad de archivos.
7. La reconstrucción del inventario de propietarios usa identificadores inconsistentes.
8. Las restricciones de versión de las dependencias se ignoran.

Los hallazgos de seguridad e integridad deben priorizarse por su impacto potencial, pero no todos han sido reproducidos dinámicamente.

### 4. ¿Qué comportamientos inesperados puede observar un usuario?

- Una instalación sin versión explícita puede no encontrar candidatos.
- Una actualización puede dejar el paquete antiguo eliminado si falla la nueva descarga.
- Una instalación puede dejar archivos residuales después de un fallo.
- La eliminación puede borrar algunos archivos y mantener el paquete registrado.
- La actualización de repositorios puede completarse solo parcialmente y mantener índices anteriores.
- La opción forzada puede sobrescribir archivos que siguen registrados como propiedad de otro paquete.
- Un paquete instalado puede no estar activado en el runtime.

### 5. ¿Qué requisitos de la especificación no están implementados?

No están garantizados suficientemente la resolución semántica de dependencias, la compatibilidad real de los candidatos, las transacciones multi-paquete, la recuperación tras fallos, la protección completa de rutas, la consistencia del inventario y el ciclo de vida general de activación y desactivación.

Además, no se ha demostrado el cumplimiento integral de todos los formatos, comandos y escenarios de pruebas exigidos por `docs/NEW-MANAGER.md`.

### 6. ¿Qué componentes actuales son técnicamente sólidos?

La separación de módulos, el modelo de datos, parte de la validación de metadatos, la comprobación de SHA-256, el inventario local y la escritura temporal del archivo de estado son bases útiles. No deben descartarse únicamente porque existan defectos en el sistema global.

### 7. ¿Qué problemas impiden considerar el Manager fiable?

Los más importantes son la falta de garantías transaccionales, la resolución incompleta de versiones y dependencias, la aceptación incondicional de compatibilidad, los riesgos de manipulación de rutas y las inconsistencias posibles entre archivos e inventario.

Estos problemas afectan al contrato central del gestor de paquetes. No son únicamente cuestiones de documentación o de cobertura de tests.

### 8. ¿Qué evidencias faltan para llegar a una conclusión definitiva?

Faltan principalmente:

- Ejecución de los tests unitarios y de integración con Gradle disponible.
- Reproducción de fallos de descarga y actualización.
- Pruebas de instalación con ZIP maliciosos y rutas manipuladas.
- Pruebas de interrupción del proceso durante instalación, actualización y eliminación.
- Pruebas de dependencias transitivas, restricciones, ciclos y versiones incompatibles.
- Verificación del comportamiento del Manager en cada plataforma soportada.
- Inspección del historial Git para distinguir defectos preexistentes de posibles regresiones.

Veredicto definitivo: el Module Manager tiene una arquitectura aprovechable y una implementación parcial real, pero todavía no cumple de forma fiable el contrato funcional y de integridad descrito en `docs/NEW-MANAGER.md`. Los defectos detectados justifican una revisión profunda de la resolución de dependencias, la instalación, la persistencia y el ciclo de vida; no justifican, por sí solos, desechar indiscriminadamente todos los componentes existentes.
