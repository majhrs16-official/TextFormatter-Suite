# REINGENIERÍA COMPLETA DEL MODULE MANAGER — TEXTFORMATTER SUITE

## 1. Objetivo principal

Reemplaza por completo el Module Manager actual de **TextFormatter Suite** por un gestor de paquetes inspirado en la separación arquitectónica de **APT y dpkg de Debian 13**.

No quiero una refactorización incremental del manager existente. Quiero descartar su diseño e implementación como solución funcional y construir un sistema nuevo que cumpla esta especificación, reutilizando únicamente el código que demuestres que es correcto, compatible y realmente útil.

El resultado debe integrarse con la arquitectura existente de TextFormatter Suite, respetando sus módulos, sus puntos de entrada y sus adaptadores de plataforma.

**No empieces a programar hasta haber inspeccionado el manager actual, la arquitectura de módulos, los puntos de entrada y los mecanismos existentes de construcción, distribución y carga.** Usa esa inspección para establecer cómo integrar el nuevo sistema sin imponer una arquitectura paralela innecesaria.

No te limites a redactar un plan ni a proponer pseudocódigo: debes implementar, probar e integrar el sistema completo.

## 2. Principio arquitectónico: APT y dpkg son responsabilidades diferentes

El sistema debe separar rigurosamente la gestión de repositorios de la gestión del estado local de los paquetes.

### 2.1. APT: repositorios, índices y adquisición

La parte equivalente a APT debe encargarse de:

- Leer la configuración de repositorios.
- Consultar los índices remotos.
- Actualizar los índices locales mediante `/suite repo update`.
- Encontrar candidatos compatibles para los paquetes solicitados.
- Resolver dependencias y restricciones de versiones.
- Determinar el conjunto completo de paquetes y versiones que deben instalarse, actualizarse o conservarse.
- Descargar los ZIP necesarios.
- Guardar temporalmente las descargas en el directorio `tmp/` del gestor.
- Verificar el tamaño y el SHA-256 de los artefactos descargados.
- Preparar un plan de instalación, actualización o eliminación para la parte equivalente a dpkg.
- Comparar el estado instalado con los candidatos disponibles.
- Resolver el grafo completo de dependencias antes de modificar el estado instalado.

APT no debe ser la fuente de verdad sobre qué paquetes están instalados. Tampoco debe modificar directamente el estado instalado saltándose las operaciones controladas del gestor local.

### 2.2. dpkg: instalación y estado local

La parte equivalente a dpkg debe encargarse de:

- Instalar paquetes a partir de un archivo ZIP local.
- Validar los metadatos, el inventario y la estructura del archivo.
- Validar dependencias y restricciones de compatibilidad.
- Rechazar instalaciones con dependencias no satisfechas o conflictos no resueltos.
- Registrar los paquetes instalados y sus versiones.
- Registrar los archivos propiedad de cada paquete, incluidos sus tamaños y SHA-256.
- Enumerar los paquetes instalados.
- Enumerar los archivos pertenecientes a un paquete.
- Eliminar paquetes de forma segura.
- Evitar que se eliminen dependencias todavía necesarias.
- Mantener un registro local coherente incluso si una operación falla.
- Detectar y recuperar operaciones interrumpidas cuando sea posible.
- Instalar un metapaquete y registrar correctamente todos los paquetes que contiene.

Debe existir una base de datos local de paquetes instalados, denominada `status`, con un formato de texto plano.

Su ubicación obligatoria es:

`<manager-dir>/library/status`

`status` es la fuente de verdad sobre el estado instalado. No debe reconstruirse a partir de los repositorios ni depender de que exista conexión a Internet.

### 2.3. Separación obligatoria

APT puede preparar y validar un plan, pero la modificación del estado instalado debe pasar por las operaciones controladas de dpkg.

Ambos componentes deben poder probarse por separado. El resolver de dependencias no debe depender de Bukkit, Fabric, Forge ni de detalles de los cargadores de mods.

APT no debe implementar una segunda vía de instalación que omita las validaciones, transacciones y actualizaciones de estado de dpkg.

## 3. Repositorios

Debe existir un archivo independiente llamado `repo.yml`, separado de `config.yml`.

- `repo.yml` configura las fuentes de paquetes.
- `config.yml` conserva la configuración general del sistema y no debe convertirse en el archivo de repositorios.
- La implementación debe respetar el mecanismo de configuración existente cuando sea compatible con esta separación.
- No inventes repositorios remotos ni publiques URLs ficticias como si fueran funcionales.

Los repositorios deben poder proporcionar un índice de paquetes denominado `packages.list`.

El diseño debe permitir varios repositorios y resolver candidatos de forma determinista. Si dos repositorios proporcionan candidatos para el mismo paquete, el resultado debe depender de reglas explícitas, no del orden accidental de iteración de una colección.

Define y documenta las reglas de prioridad de repositorios, selección de versiones y selección de canales. No confundas prioridad de origen con compatibilidad ni con orden de instalación.

La configuración de repositorios debe permitir identificar cada origen de forma inequívoca y mantener sus índices locales separados o identificados de forma segura para evitar colisiones.

## 4. Formato del índice remoto: `packages.list`

El índice debe utilizar texto plano, con una entrada por línea y campos separados por tabuladores.

Formato conceptual:

`nombre<TAB>URL<TAB>tamaño_zip<TAB>SHA256`

Ejemplo ilustrativo:

```text
iflow	https://example.invalid/packages/iflow-1.0.0.zip	48213	<sha256>
```

El ejemplo no constituye un repositorio real.

### Requisitos

- `nombre` identifica el paquete distribuido.
- `URL` identifica la ubicación exacta del artefacto.
- `tamaño_zip` es el tamaño total del archivo ZIP comprimido, expresado en bytes.
- `SHA256` es el hash SHA-256 del archivo ZIP completo.
- La URL debe conservarse tal como la proporciona el índice. No reconstruyas URLs a partir del nombre, la versión, el repositorio o una convención de nombres.
- El índice debe admitir validación estricta de sintaxis.
- Rechaza entradas malformadas, hashes inválidos, tamaños inválidos y registros ambiguos.
- No permitas que una entrada malformada se interprete silenciosamente como un paquete válido.
- Comprueba el tamaño y el hash del artefacto descargado antes de instalarlo.
- Los errores de red, las respuestas HTTP fallidas y los artefactos incompletos deben producir errores explícitos.
- No confundas el tamaño del ZIP con el tamaño total de sus archivos descomprimidos.
- No confundas una entrada del índice remoto con una entrada del inventario interno de un paquete.

El índice remoto identifica los artefactos disponibles. No es la base de datos del estado local.

La identidad y versión concretas de cada paquete deben obtenerse y validarse contra `METADATA/package.list`. Si el nombre del índice contradice los metadatos del artefacto, el candidato debe rechazarse.

## 5. Formato interno: `METADATA/package.list`

Cada archivo ZIP distribuido debe contener sus metadatos en:

`METADATA/package.list`

Este formato es originalmente **multip paquete**. No debe diseñarse como si cada archivo `package.list` solo pudiera describir un único paquete.

Un ZIP puede contener un paquete ordinario o un **metapaquete que agrupe varios paquetes instalables**. Un metapaquete se representa incluyendo las entradas de todos los paquetes que lo componen dentro del mismo `METADATA/package.list`.

No inventes un formato adicional de metapaquetes si la estructura multipaquete existente puede representar esa relación directamente. El formato de manifiesto y su parser deben permitir interpretar todos los registros de forma inequívoca.

### 5.1. Estructura y bloques

El archivo `METADATA/package.list` debe contener uno o varios bloques de metadatos, separados mediante una convención textual inequívoca y documentada.

- El primer bloque describe el paquete principal o cabecera del archivo.
- Los bloques siguientes pueden describir otros paquetes contenidos en el mismo ZIP.
- El primer bloque nunca debe descartarse ni interpretarse erróneamente como un separador.
- Cada bloque debe poder analizarse independientemente.
- Debe ser posible identificar todos los paquetes declarados en el ZIP, sus metadatos y sus relaciones.
- Un bloque no debe confundirse con un registro del índice remoto `packages.list`.
- La implementación debe definir de manera inequívoca los separadores, los campos, las listas, los valores vacíos y las reglas de escape.
- No introduzcas YAML como formato de metadatos internos.

La estructura debe ser coherente con `status`, de manera que los registros de los paquetes instalados puedan conservar la misma representación de los metadatos relevantes.

### 5.2. Campos obligatorios

Cada bloque de paquete debe contemplar los siguientes campos:

- `Name`
- `Version`
- `Channel`
- `Type`
- `Dependencies`
- `Arch`
- `Platform`
- `URL`
- `OS`
- `SHA256`

Además, debe incluir el inventario de archivos pertenecientes al paquete.

El formato definitivo debe conservar la siguiente semántica:

**Name:** nombre canónico del paquete.

**Version:** versión concreta del paquete. Define y aplica una política de comparación de versiones; no compares versiones numéricas como cadenas lexicográficas.

**Channel:** canal de distribución, por ejemplo `stable` o `beta`. El término es `Channel`, no `Branch`.

**Type:** tipo de paquete. Como mínimo, admite `package` y `dependency`. Define sus implicaciones operativas y no los trates como sinónimos. Si el diseño necesita identificar explícitamente metapaquetes, define cómo se representa esa condición y cómo se diferencia de un paquete ordinario sin romper el formato multipaquete.

**Dependencies:** dependencias declaradas y sus restricciones de versión. Debe admitir múltiples dependencias y restricciones explícitas. Las dependencias opcionales solo se admitirán si su semántica está completamente definida.

**Arch:** arquitecturas admitidas, por ejemplo `none`, `x86`, `x64`, `arm32` y `arm64`.

**Platform:** plataformas admitidas, como `bukkit`, `fabric` y `forge`, ampliables de forma controlada.

**URL:** URL del artefacto cuando corresponda al contrato del manifiesto. Debe definirse claramente cómo se relaciona con la URL autoritativa del índice remoto y con los paquetes instalados localmente.

**OS:** sistemas operativos admitidos, como `none`, `windows`, `linux`, `mac` y `android`.

**SHA256:** hash del artefacto cuando corresponda al contrato del manifiesto. Debe definirse qué valor es autoritativo y cómo se detectan discrepancias con el índice remoto.

No dejes estos campos como simples cadenas decorativas: deben validarse y participar en las operaciones que les correspondan.

### 5.3. Metapaquetes

Un metapaquete es un paquete cuya distribución contiene varios registros de paquetes en `METADATA/package.list`.

El sistema debe:

- Reconocer todos los registros válidos del manifiesto.
- Identificar el paquete principal y los paquetes adicionales declarados.
- Asociar cada registro con sus archivos, dependencias, versión, tipo y restricciones.
- Validar que los registros no tengan identidades duplicadas o contradictorias.
- Resolver las dependencias del conjunto completo, incluidos los paquetes incluidos en el metapaquete.
- Instalar o actualizar el conjunto como una operación coherente.
- Registrar en `library/status` todos los paquetes instalados, no únicamente el paquete principal del ZIP.
- Mantener la identidad del artefacto ZIP compartido por los registros que procedan de él, sin fingir que cada registro proviene de un ZIP distinto.
- Registrar las relaciones entre el artefacto de distribución, el paquete principal y los paquetes contenidos.
- Permitir listar, actualizar y eliminar cada paquete conforme a sus relaciones y a la propiedad real de sus archivos.
- Impedir que eliminar un paquete contenido elimine archivos compartidos o componentes que sigan siendo necesarios para otros paquetes.
- Detectar los conflictos de propiedad y las dependencias circulares antes de modificar el estado instalado.

El hecho de que varios paquetes estén declarados dentro de un ZIP no significa que puedan instalarse parcialmente de forma arbitraria. Define expresamente si la distribución constituye una unidad indivisible o si admite selección parcial. En ausencia de una regla explícita y verificable, trata el conjunto como una unidad transaccional.

No dupliques los archivos del mismo artefacto ni los registres como si fueran archivos físicamente independientes. El inventario y el estado deben representar la propiedad lógica y física de manera coherente.

### 5.4. Semántica de los campos de compatibilidad

`Arch`, `OS` y `Platform` deben admitir múltiples valores permitidos cuando el paquete sea compatible con varias opciones.

Estos valores representan alternativas admitidas, no requisitos simultáneos.

Por ejemplo, un paquete compatible con `x64` y `arm64` puede declarar ambas arquitecturas. No se debe interpretar que el equipo necesita disponer de ambas.

Define el significado de `none` de forma coherente. No lo trates como comodín universal sin una decisión explícita.

La plataforma, la arquitectura y el sistema operativo efectivos deben determinarse mediante el contexto de ejecución disponible. El gestor debe filtrar los candidatos incompatibles y rechazar una instalación incompatible.

No confundas el filtrado de compatibilidad con la selección de una versión arbitraria: las reglas de compatibilidad, la resolución de versiones y la selección de canales deben ser procesos explícitos y comprobables.

Si una plataforma no puede determinarse con fiabilidad, no supongas una compatibilidad inexistente.

### 5.5. Inventario de archivos y SHA-256 individuales

El manifiesto debe incluir un inventario verificable de los archivos que instala cada paquete.

Por cada archivo deben registrarse, como mínimo:

- Ruta relativa.
- Tamaño esperado en bytes.
- SHA-256 del contenido.
- Identidad del paquete propietario o relación inequívoca con el bloque del paquete correspondiente.

El SHA-256 del ZIP completo y los SHA-256 individuales tienen propósitos diferentes:

- El hash del ZIP permite verificar la integridad del artefacto descargado respecto del índice.
- Los hashes individuales permiten verificar los archivos después de extraerlos y detectar corrupción o modificaciones posteriores.

Ambos deben validarse en sus respectivos puntos del ciclo de vida. No sustituyas una comprobación por la otra.

El inventario debe coincidir con el contenido real del ZIP según las reglas de exclusión documentadas para los propios metadatos y otros elementos de infraestructura del paquete.

Rechaza rutas absolutas, traversal (`..`), rutas ambiguas, entradas duplicadas peligrosas y cualquier archivo que pueda escapar del directorio de instalación autorizado.

No confíes en los nombres de archivo del ZIP como rutas seguras.

Define cómo se representan los archivos compartidos, si están permitidos. Un mismo archivo físico no debe tener varios propietarios incompatibles sin una política explícita de propiedad compartida y eliminación segura.

## 6. Estructura de almacenamiento

El gestor debe tener un directorio propio, independiente de los directorios de plugins y mods administrados directamente por Bukkit, Fabric o Forge.

La estructura conceptual obligatoria es:

```text
<manager-dir>/
├── repo.yml
├── config.yml
└── library/
    ├── status
    ├── tmp/
    ├── lists/
    │   └── <repository-indexes>
    ├── packages/
    │   └── <package-artifacts>
    └── dependencies/
        └── <dependency-artifacts>
```

Adapta los nombres concretos solo cuando la integración existente lo justifique. No cambies su semántica.

- `repo.yml` configura los repositorios.
- `library/tmp/` almacena descargas y operaciones temporales.
- `library/lists/` almacena los índices descargados.
- `library/status` registra el estado instalado.
- `library/packages/` almacena los artefactos de paquetes instalados.
- `library/dependencies/` almacena las dependencias compartidas.

Los paquetes instalados y las dependencias no deben instalarse indiscriminadamente en `/plugins` o `/mods`. La presencia de un JAR en el directorio de almacenamiento del gestor no implica que Bukkit o Fabric lo carguen automáticamente.

Define una política inequívoca para transformar un ZIP de distribución en los artefactos instalados. No supongas que cualquier ZIP contiene un JAR único ni que basta con renombrarlo.

La política debe contemplar tanto los paquetes ordinarios como los metapaquetes. Cuando varios paquetes compartan un ZIP de distribución, conserva la identidad del artefacto de origen y evita copias innecesarias.

Valida el contenido, el inventario y la propiedad de los archivos. Los artefactos temporales no deben confundirse con paquetes instalados.

`library/status` debe ser la única ubicación canónica del estado local. No mantengas otro `status` en la raíz como fuente de verdad paralela.

## 7. Base de datos local `library/status`

Implementa una base de datos de texto plano que registre el estado real de los paquetes instalados.

Debe permitir reconstruir, sin conexión a Internet:

- Nombre, versión y canal instalados.
- Tipo de paquete.
- Dependencias declaradas.
- Dependencias efectivamente resueltas.
- Identidad y hash del artefacto ZIP de origen.
- Inventario de archivos propiedad de cada paquete, incluidos tamaños y SHA-256.
- Ubicaciones de instalación.
- Relaciones entre paquetes y metapaquetes.
- Relaciones entre los registros de paquetes y los artefactos físicos que los contienen.
- Estado de instalación y, cuando corresponda, activación.
- Referencias a dependencias compartidas.
- Información suficiente para comprobar consistencia, eliminar un paquete y diagnosticar errores.
- Operaciones incompletas o pendientes de recuperación.

Define un formato estable y versionado.

**Prioriza que el formato de `library/status` sea el mismo que el de `METADATA/package.list`, con los registros de paquetes separados por una doble línea en blanco**, tal como permite la estructura multipaquete original.

No crees un formato de estado completamente distinto si puedes representar el estado instalado ampliando de forma compatible los registros existentes. Si necesitas campos exclusivos del estado local, define cómo se incorporan sin invalidar el formato compartido.

Si no puedes mantener la misma representación, explica la incompatibilidad técnica concreta y documenta la conversión. No afirmes que ambos formatos son idénticos si sus reglas de análisis o su semántica difieren.

La base de datos debe soportar escrituras atómicas o un mecanismo equivalente de recuperación. Una interrupción durante una instalación no puede dejar `library/status` parcialmente escrito y aparentemente válido.

Implementa recuperación y detección de inconsistencias. No ocultes un estado corrupto reconstruyéndolo silenciosamente a partir de los repositorios.

El registro debe permitir distinguir entre una instalación completa y una operación interrumpida o pendiente de recuperación.

Las relaciones de propiedad, las dependencias compartidas y los metapaquetes deben poder reconstruirse desde el estado persistido sin depender de contadores volátiles en memoria.

## 8. Resolución de dependencias

Implementa un resolver real de dependencias.

Debe:

- Resolver restricciones de versión.
- Considerar los paquetes instalados y los candidatos disponibles.
- Detectar dependencias ausentes.
- Detectar ciclos.
- Detectar conflictos de versiones.
- Detectar conflictos de archivos.
- Detectar incompatibilidades de `Arch`, `OS` y `Platform`.
- Evitar descargas o instalaciones duplicadas de la misma dependencia compatible.
- Generar un plan reproducible antes de modificar el estado instalado.
- Explicar por qué una resolución no es posible.
- Considerar los paquetes contenidos en metapaquetes.
- Mantener la consistencia entre los paquetes lógicos y los artefactos físicos compartidos.
- Resolver el grafo completo de dependencias del estado final propuesto, no solamente las dependencias directas del paquete solicitado.

No resuelvas dependencias devolviendo simplemente el primer paquete encontrado.

### 8.1. Dependencias compartidas y versiones

Una dependencia que ya está instalada en una versión compatible debe poder reutilizarse.

Si varios paquetes necesitan exactamente la misma identidad de dependencia, no instales copias redundantes sin motivo.

Si dos paquetes necesitan versiones incompatibles, no los hagas compartir una única versión de forma incorrecta.

Permite coexistencia de versiones cuando el diseño de almacenamiento, el cargador y el modelo de dependencias puedan soportarla de manera segura. Si no pueden coexistir, detecta el conflicto y rechaza el plan.

La reubicación (*relocation/shading*) puede utilizarse cuando proceda, pero debe ser técnicamente correcta. Cambiar únicamente los nombres de las entradas de un JAR no equivale a reubicar clases Java. Si se implementa relocation, deben tratarse las referencias de bytecode y los recursos relevantes, y probarse el resultado.

No presentes una versión que no soporte coexistencia ni relocation como si resolviera esos casos.

### 8.2. Ciclo de vida de dependencias

La eliminación de un paquete no debe borrar dependencias que todavía estén siendo utilizadas por otros paquetes.

Mantén las relaciones entre dependencias y paquetes consumidores. Los contadores de referencias pueden derivarse del grafo instalado o reconstruirse de forma segura a partir de `library/status`; no deben ser la única fuente de verdad si pueden quedar desincronizados.

Define qué ocurre con dependencias huérfanas y proporciona un procedimiento seguro para identificarlas y eliminarlas sin afectar paquetes activos.

### 8.3. Resolución de actualizaciones

El resolver debe poder determinar un conjunto final coherente de versiones para los paquetes solicitados y sus dependencias.

No basta con actualizar cada paquete por separado. La selección de una versión nueva puede exigir actualizar, conservar o sustituir otras dependencias.

Si no existe una solución que satisfaga simultáneamente las restricciones del grafo, la operación debe fallar explícitamente antes de confirmar los cambios.

No fuerces versiones incompatibles ni dejes el estado local parcialmente actualizado para aparentar que se ha realizado la operación.

## 9. Instalación, actualización, eliminación y recuperación

Toda operación debe validarse antes de modificar el estado instalado.

### 9.1. Instalación

1. Resolver el plan completo.
2. Verificar compatibilidad y restricciones.
3. Obtener los artefactos necesarios.
4. Verificar tamaño y hash de cada ZIP.
5. Validar metadatos y contenido.
6. Verificar el inventario y los SHA-256 individuales.
7. Comprobar dependencias y conflictos.
8. Preparar los archivos en una ubicación temporal.
9. Instalar de forma controlada.
10. Actualizar `library/status` de forma atómica.
11. Limpiar temporales y conservar un estado coherente.

El sistema debe definir cómo revierte o recupera las operaciones que fallen a mitad del proceso.

La instalación desde repositorio debe delegar la aplicación del plan en las operaciones controladas de dpkg.

La instalación desde un ZIP local debe poder realizarse sin publicar previamente el archivo en un repositorio. Debe validar todos los paquetes declarados en su manifiesto, sus dependencias y la integridad del artefacto.

### 9.2. Semántica de `install`

Utilizar `install` sobre un paquete ya instalado **debe actualizarlo cuando exista una versión candidata seleccionada que corresponda a una versión más reciente o que satisfaga explícitamente la operación solicitada**.

No debe limitarse a informar de que el paquete ya está instalado.

La operación debe resolver las dependencias necesarias y construir un plan coherente. Si el paquete ya está en la versión seleccionada y todas sus dependencias están satisfechas, puede informar de que no hay cambios necesarios.

Una solicitud explícita de versión debe respetarse. No sustituyas silenciosamente la versión solicitada por otra.

### 9.3. `repo update`: actualizar índices

`/suite repo update` actualiza los índices de los repositorios configurados.

Esta operación:

- Descarga los índices actuales.
- Valida su sintaxis y contenido.
- Guarda los índices locales de manera segura.
- Conserva los índices válidos anteriores cuando una actualización falle, según una política transaccional documentada.
- Informa qué repositorios se actualizaron y cuáles fallaron.

No significa que los paquetes instalados se actualicen. Tampoco debe modificar `library/status` ni instalar paquetes.

### 9.4. `repo upgrade`: actualización global equivalente a APT

`/suite repo upgrade` debe mantener la paridad funcional esencial con `apt upgrade`: actualizar el conjunto de paquetes instalados a las versiones compatibles disponibles y resolver sus dependencias de manera global.

No es una sucesión de instalaciones independientes.

La operación debe:

1. Leer el estado instalado desde `library/status`.
2. Consultar los índices locales disponibles.
3. Identificar candidatos de actualización para todos los paquetes instalados.
4. Construir el grafo de dependencias del estado final propuesto.
5. Resolver restricciones de versiones, compatibilidad, dependencias compartidas, metapaquetes y conflictos de archivos.
6. Determinar qué paquetes pueden actualizarse y cuáles deben permanecer en su versión actual conforme a las reglas de resolución definidas.
7. Preparar un plan global y reproducible.
8. Descargar y verificar todos los artefactos necesarios.
9. Validar el plan completo antes de modificar el estado instalado.
10. Aplicar las operaciones mediante dpkg y su mecanismo transaccional.
11. Actualizar `library/status` de forma coherente.
12. Informar del resultado real de la operación.

**Si no existe una solución coherente para el grafo de dependencias, `repo upgrade` debe fallar explícitamente sin confirmar un estado parcialmente actualizado.**

No fuerces una actualización incompatible ni elimines dependencias arbitrariamente para completar la operación.

No confundas esta operación con `repo update`: la primera resuelve y aplica actualizaciones; la segunda refresca los índices.

No prometas que todos los paquetes pueden actualizarse sin reinicio. La activación del código debe respetar las capacidades reales del runtime.

### 9.5. `repo fix-broken`

Implementa `/suite repo fix-broken`, inspirado en `apt --fix-broken install`.

Debe inspeccionar el estado local y el grafo de dependencias para identificar paquetes con dependencias incumplidas, relaciones inconsistentes o instalaciones pendientes de recuperación.

Debe intentar construir un plan de reparación mediante los candidatos disponibles y el estado local. Cuando corresponda, puede descargar artefactos y delegar las operaciones en dpkg.

La reparación debe:

- Diagnosticar qué paquetes y restricciones están rotos.
- Explicar las causas de la inconsistencia.
- Proponer o construir un plan de reparación verificable.
- Reutilizar las versiones instaladas cuando satisfagan las restricciones.
- Seleccionar versiones compatibles cuando sea necesario y estén disponibles.
- Respetar las dependencias inversas, la propiedad de archivos y las relaciones entre metapaquetes.
- No eliminar paquetes ni archivos arbitrariamente para forzar un resultado.
- No reconstruir silenciosamente un estado corrupto a partir de los índices remotos.
- Fallar explícitamente si no existe una solución segura.
- Utilizar el mecanismo de recuperación transaccional y dejar constancia de cualquier operación pendiente.

Si el propio `library/status` está corrupto y no puede analizarse de forma fiable, debe distinguirse ese problema de una dependencia rota normal. No inventes un estado instalado para que el comando parezca funcionar.

### 9.6. Eliminación

Antes de eliminar un paquete, comprueba las dependencias inversas, los archivos que posee y las consecuencias para los servicios activos.

No borres archivos que pertenezcan a otro paquete. Detecta conflictos de propiedad y modificaciones locales cuando puedan causar pérdida de datos.

La eliminación de un paquete perteneciente a un metapaquete debe respetar las relaciones entre los registros y los artefactos físicos. No elimines automáticamente todo el ZIP compartido si otros paquetes siguen dependiendo de sus contenidos.

Si una operación no puede realizarse con seguridad, falla con un diagnóstico concreto y conserva un estado recuperable.

### 9.7. Transacciones

Las operaciones que afecten a varios paquetes deben validarse como un conjunto.

Por ejemplo, `/suite package install iflow textformatter` debe resolver primero todas las dependencias y conflictos. No debe instalar arbitrariamente la mitad del conjunto y dejar el sistema en un estado inconsistente cuando la otra mitad falla.

Las actualizaciones globales, las instalaciones de metapaquetes y las reparaciones deben usar el mismo principio de coherencia.

Implementa transacciones o una estrategia equivalente de preparación, confirmación, reversión y recuperación. Documenta las garantías reales: no afirmes que existe atomicidad total si las modificaciones del sistema de archivos o la activación de servicios no pueden revertirse por completo.

## 10. Integración con el runtime de TextFormatter Suite

La instalación de archivos y la activación de código son responsabilidades diferentes.

El nuevo gestor no debe suponer que cada paquete instalado es automáticamente un plugin de Bukkit o un mod de Fabric.

Inspecciona la arquitectura existente y define una interfaz explícita entre el gestor de paquetes y el runtime de TxF.

La gestión de paquetes debe permanecer independiente de Bukkit, Fabric y Forge. Los adaptadores de plataforma deben aportar únicamente las capacidades específicas que realmente necesite el runtime.

Respeta los puntos de entrada existentes. En particular, no conviertas `Module.java` en una clase que el bootstrap deba instanciar reflectivamente si su responsabilidad actual es describir módulos y dependencias.

No alteres el modelo de arranque de TxF por conveniencia del gestor.

### 10.1. Activación y cambios en caliente

Si el sistema promete instalar, actualizar o eliminar paquetes sin reiniciar, debe disponer de un contrato explícito de ciclo de vida para los paquetes que participen en esa operación.

Ese contrato debe contemplar, según corresponda:

- Preparación de la nueva versión.
- Inicialización controlada.
- Activación.
- Desactivación de la versión anterior.
- Liberación de listeners, tareas, threads, recursos y referencias.
- Confirmación del cambio.
- Recuperación o rollback cuando sea posible.

No afirmes que cualquier plugin o mod puede descargarse y actualizarse de forma segura en caliente. Si una operación requiere reinicio por las limitaciones del cargador o del paquete, debe detectarse y comunicarse explícitamente.

El estado instalado y el estado activado deben representarse por separado cuando no sean equivalentes.

Una actualización de archivos correctamente instalada no debe registrarse como una activación correcta si el runtime ha fallado al activar la nueva versión.

## 11. Interfaz de comandos

Implementa los comandos siguiendo las convenciones reales del proyecto, con permisos, validación de argumentos, autocompletado cuando corresponda y mensajes de error útiles.

La interfaz debe organizarse en dos familias: `repo`, para las operaciones equivalentes a APT, y `package`, para las operaciones locales equivalentes a dpkg.

### 11.1. Comandos `repo`

| Comando | Responsabilidad |
|---|---|
| `/suite repo update` | Actualizar los índices de los repositorios configurados. |
| `/suite repo install iflow` | Resolver, descargar e instalar un paquete y sus dependencias. |
| `/suite repo install iflow textformatter` | Resolver e instalar varios paquetes en una operación coherente. |
| `/suite repo remove iflow` | Resolver y eliminar un paquete respetando dependencias y propiedad de archivos. |
| `/suite repo upgrade` | Actualizar globalmente los paquetes instalados y resolver el grafo de dependencias resultante. |
| `/suite repo fix-broken` | Diagnosticar y reparar dependencias o instalaciones inconsistentes cuando exista una solución segura. |

`repo install` debe actualizar un paquete cuando ya esté instalado y exista una actualización aplicable. Si no hay cambios necesarios, debe informarlo explícitamente.

`repo remove` puede preparar un plan equivalente al de APT, pero la modificación del estado local debe realizarse mediante las operaciones controladas de dpkg.

`repo upgrade` y `repo fix-broken` no deben ser alias de `repo install` ni de `repo update`.

### 11.2. Comandos `package`

| Comando | Responsabilidad |
|---|---|
| `/suite package install /ruta/iflow.zip` | Instalar un ZIP local validando todos sus metadatos, dependencias e inventario. |
| `/suite package install iflow` | Si se admite el nombre como argumento, operar sobre el paquete local correspondiente conforme al contrato documentado, sin fingir que se ha consultado un repositorio. |
| `/suite package list` | Enumerar los paquetes instalados desde `library/status`. |
| `/suite package remove iflow` | Eliminar un paquete instalado mediante el gestor local, respetando dependencias inversas y propiedad de archivos. |
| `/suite package files iflow` | Enumerar los archivos registrados como propiedad del paquete. |
| `/suite package verify iflow` | Verificar la integridad de los archivos instalados frente al inventario y los SHA-256 registrados. |

La forma principal de `package install` debe permitir instalar directamente un archivo ZIP local, sin necesitar que el artefacto esté publicado en un repositorio.

Si se admite `package install` por nombre, define de forma precisa de dónde se obtiene el artefacto. No mezcles silenciosamente la resolución remota de APT con la instalación local de dpkg.

La instalación local debe leer `METADATA/package.list`, validar todos los registros que correspondan, comprobar las dependencias con el estado local disponible y fallar explícitamente si falta una dependencia. No debe fingir que una instalación está completa.

### 11.3. Contratos de comandos

Los nombres anteriores son la interfaz deseada. Adáptalos únicamente si las restricciones reales del sistema de comandos lo exigen, preservando la semántica.

Distingue claramente entre:

- Paquete desconocido.
- Índices no disponibles o desactualizados.
- Dependencia no satisfecha.
- Versión incompatible.
- Paquete ya instalado sin cambios necesarios.
- Conflicto de archivos.
- Hash incorrecto.
- Inventario de archivos incorrecto.
- Archivo malformado.
- Error de descarga.
- Error de instalación.
- Error de activación.
- Estado local corrupto.
- Operación pendiente de recuperación.
- Grafo de dependencias irresoluble.
- Reparación imposible con los candidatos disponibles.

Los mensajes no deben ocultar errores ni anunciar éxito antes de que la operación correspondiente haya finalizado correctamente.

## 12. Seguridad e integridad

El sistema debe tratar índices, ZIP y metadatos como entradas no confiables.

Implementa, como mínimo:

- Validación estricta de metadatos.
- Verificación de SHA-256 del artefacto.
- Verificación de SHA-256 de cada archivo inventariado.
- Comprobación de tamaños.
- Prevención de ZIP Slip y traversal.
- Protección frente a entradas ZIP duplicadas o ambiguas.
- Límites razonables de tamaño y expansión para evitar archivos comprimidos maliciosos.
- Descargas temporales que no se confundan con artefactos válidos.
- Escrituras atómicas y recuperación de estado.
- Validación de URLs y protocolos admitidos.
- Gestión segura de archivos ya existentes.
- Detección de conflictos de propiedad.
- Errores explícitos en lugar de fallos abiertos.

SHA-256 verifica la integridad respecto del hash esperado; por sí solo no autentica al editor del paquete. No describas los artefactos como firmados o autenticados si no se implementa un mecanismo de firma y verificación.

No añadas un sistema de firmas ficticio para aparentar seguridad. Si las firmas quedan fuera del alcance, documenta esa limitación.

El inventario de archivos y sus hashes debe permitir detectar modificaciones posteriores. Antes de sobrescribir o eliminar un archivo modificado, aplica una política explícita que evite la pérdida silenciosa de datos.

## 13. Inspección y reutilización del repositorio

Antes de cambiar código, inspecciona sistemáticamente:

- El árbol de archivos relevante.
- La implementación actual del manager.
- Los modelos de módulos y dependencias.
- Las APIs públicas del gestor.
- Los puntos de entrada de Bukkit, Fabric y cualquier otra plataforma soportada.
- La configuración y persistencia existentes.
- El sistema de comandos.
- El proceso de empaquetado y distribución.
- Las tareas de Gradle y las dependencias disponibles.
- Los tests existentes y la infraestructura de pruebas.

Después, identifica qué se elimina, qué se conserva y qué se reemplaza.

No mantengas clases antiguas sin uso, implementaciones duplicadas, resolvers incompatibles entre sí ni APIs obsoletas únicamente para aparentar compatibilidad.

No elimines funcionalidades externas legítimas sin demostrar que son incompatibles con el nuevo diseño.

No introduzcas dependencias externas innecesarias ni inventes capacidades de librerías que no hayas verificado.

Respeta las convenciones del repositorio y mantén los cambios enfocados en la reingeniería del gestor y en las adaptaciones estrictamente necesarias.

## 14. Pruebas obligatorias

Añade pruebas automatizadas para las partes que lo permitan. No te limites a probar el caso feliz.

### 14.1. Índices y metadatos

- Índice válido.
- Línea malformada.
- Campo ausente.
- Hash inválido.
- Tamaño negativo o desbordado.
- Nombre duplicado ambiguo.
- Metadatos internos malformados.
- Cabecera interpretada correctamente como metadatos del paquete.
- Manifiesto con un único paquete.
- Manifiesto con varios paquetes.
- Metapaquete cuyos registros se analizan correctamente.
- Identidades duplicadas o contradictorias dentro de un metapaquete.
- Inventario que no coincide con el ZIP.
- Tamaño de archivo incorrecto.
- SHA-256 individual incorrecto.
- Metadatos e índice con hash o identidad contradictorios.
- Artefacto compartido por varios registros sin duplicar su almacenamiento físico.
- Formato de `library/status` compatible con el parser de `METADATA/package.list`, cuando corresponda.

### 14.2. Resolución

- Paquete sin dependencias.
- Dependencia satisfecha localmente.
- Dependencia descargable.
- Restricción de versión imposible.
- Dependencia transitiva.
- Ciclo de dependencias.
- Dependencia compartida.
- Versiones incompatibles.
- Conflicto de archivos.
- Candidato incompatible con `Arch`, `OS` o `Platform`.
- Varios repositorios con candidatos para el mismo paquete.
- Prioridad de repositorios determinista.
- Selección de canal determinista.
- Metapaquete con varios paquetes.
- Dependencia de un paquete contenido en un metapaquete.
- Conflicto entre paquetes del mismo metapaquete.
- Grafo final irresoluble durante `repo upgrade`.
- Reparación posible e imposible mediante `repo fix-broken`.

### 14.3. Instalación y eliminación

- Instalación desde repositorio.
- Instalación desde ZIP local.
- ZIP corrupto.
- Hash incorrecto.
- Dependencia ausente.
- Instalación de varios paquetes.
- Instalación de metapaquete.
- Registro de todos los paquetes contenidos en `library/status`.
- Fallo durante la instalación.
- Recuperación tras una operación interrumpida.
- Actualización de un paquete ya instalado mediante `install`.
- Actualización global con dependencias compartidas.
- Rechazo de actualización con grafo irresoluble.
- Reparación de una instalación inconsistente.
- Eliminación de un paquete sin dependientes.
- Rechazo de eliminación de un paquete necesario.
- Dependencia compartida que debe conservarse.
- Eliminación de dependencia huérfana.
- Archivo modificado o propiedad en conflicto.
- `library/status` corrupto o incompleto.
- Eliminación de un paquete contenido sin eliminar archivos aún necesarios por otros paquetes.
- Actualización o eliminación fallida sin confirmar un estado incoherente.

### 14.4. Runtime

- Paquete instalado pero no activado.
- Activación correcta.
- Fallo de activación.
- Desactivación y limpieza de recursos.
- Actualización que necesita reinicio.
- Ausencia de capacidades de hot unload.
- Actualización de archivos sin declarar falsamente que el código está activado.
- Recuperación tras fallo de activación cuando sea compatible con la arquitectura real.

Adapta los casos a las capacidades reales del proyecto, pero no omitas los escenarios de integridad, dependencias, recuperación, metapaquetes y estado local.

Los tests deben ejecutarse de forma reproducible y no depender de repositorios externos activos ni de acceso a Internet. Utiliza fixtures y repositorios locales de prueba.

## 15. Criterios de aceptación

El trabajo no se considera terminado hasta que se cumpla lo siguiente:

1. El manager antiguo ha sido reemplazado, no simplemente envuelto.
2. APT y dpkg tienen responsabilidades separadas y verificables.
3. `repo.yml`, `packages.list`, `METADATA/package.list` y `library/status` tienen formatos documentados y parsers coherentes.
4. El parser admite manifiestos multipaquete y no descarta el primer bloque.
5. Los metapaquetes se representan mediante varios registros dentro de `METADATA/package.list`, sin introducir un formato paralelo innecesario.
6. Los paquetes se pueden instalar desde repositorios y desde archivos ZIP locales.
7. Las dependencias se resuelven de manera determinista y se detectan conflictos.
8. Las dependencias compartidas no se duplican innecesariamente ni se eliminan prematuramente.
9. El inventario permite verificar archivos por tamaño y SHA-256, enumerar su propiedad y eliminarlos de forma segura.
10. Las operaciones multipaquete se validan antes de aplicar cambios.
11. `repo update` refresca índices sin actualizar por sí mismo los paquetes instalados.
12. `repo upgrade` resuelve el grafo global de actualizaciones y falla explícitamente si no existe una solución coherente.
13. `repo fix-broken` diagnostica y repara dependencias rotas cuando existe una solución segura, sin inventar estados locales.
14. Los errores y las interrupciones no dejan un estado aparentemente válido pero inconsistente.
15. La gestión de paquetes está desacoplada de los adaptadores de plataforma.
16. No se presupone que instalar un paquete equivale a cargarlo como plugin o mod.
17. La compatibilidad y el ciclo de vida del runtime están documentados y aplicados.
18. Los comandos solicitados funcionan según sus contratos.
19. Los tests nuevos y los relevantes existentes pasan.
20. El proyecto compila en los targets pertinentes.
21. No quedan rutas de código antiguas incompatibles, referencias rotas ni implementaciones duplicadas sin justificación.
22. La documentación describe el comportamiento realmente implementado, no capacidades futuras.

## 16. Forma de trabajo y entrega

Trabaja directamente sobre el repositorio.

Sigue esta secuencia:

1. Inspección y mapa de arquitectura.
2. Inventario de la implementación antigua y sus consumidores.
3. Diseño de contratos y formatos.
4. Implementación del almacenamiento, parsers y validadores.
5. Implementación del resolver y de dpkg.
6. Implementación de APT, descarga de artefactos y actualización de índices.
7. Implementación de instalación, actualización global y reparación de dependencias.
8. Integración de comandos.
9. Integración del runtime y adaptadores.
10. Pruebas, compilación y corrección de errores.
11. Eliminación del código obsoleto y actualización de documentación.

No te detengas después de entregar el diseño. Continúa hasta implementar y verificar el resultado.

Si una decisión importante no puede deducirse del repositorio ni de esta especificación, identifica el conflicto concreto y elige la solución más conservadora compatible con la arquitectura existente. No inventes requisitos de negocio ni cambies silenciosamente los contratos públicos.

Al terminar, entrega un informe técnico conciso pero verificable que incluya:

- Arquitectura final.
- Archivos y componentes eliminados, modificados y añadidos.
- Formatos definitivos de los índices, metadatos y `library/status`.
- Cómo se representan los metapaquetes y cómo se vinculan con sus artefactos físicos.
- Comportamiento de la resolución de dependencias.
- Semántica real de `repo install`, `repo update`, `repo upgrade` y `repo fix-broken`.
- Garantías reales de instalación, recuperación y eliminación.
- Integración con cada plataforma soportada.
- Tests ejecutados y resultados reales.
- Comandos de compilación utilizados.
- Limitaciones restantes, especialmente las relacionadas con hot unload, coexistencia de versiones y seguridad de la cadena de distribución.

No declares completado ningún criterio que no hayas comprobado mediante código, tests o inspección verificable.
