# Auditoría técnica integral — TextFormatter Suite

Alcance: arquitectura, código, funcionalidades, seguridad, rendimiento y escalabilidad.

## 1. Identificación y alcance inicial

Repositorio: [majhrs16-official/TextFormatter-Suite](https://github.com/majhrs16-official/TextFormatter-Suite)

Commit de referencia: `4f9e8aaf427b30b502360d531eb7f637f6695469`

La auditoría debe distinguir entre tres niveles de evidencia: comportamiento demostrado por el código, inferencias arquitectónicas y cuestiones que requieren ejecutar pruebas o medir el rendimiento.

La inspección inicial del árbol Git confirmó 537 archivos y 474 directorios, con 226 archivos Java, y el árbol no estaba marcado como truncado. Esto permite establecer un inventario reproducible, pero no significa que esos 537 archivos ya hayan sido examinados individualmente.

Mantendré ese criterio durante el análisis: no atribuiré cobertura completa a archivos que solo haya localizado, ni presentaré estimaciones de capacidad como benchmarks reales.

## 2. Primeros resultados verificables

La lectura del código ya permite identificar una distinción importante: el proyecto tiene una arquitectura modular considerable, pero la existencia de módulos, contratos SPI y pruebas no demuestra por sí sola que todos los límites arquitectónicos sean efectivos ni que el conjunto esté preparado para producción.

### Hallazgo preliminar: orden de instalación de dependencias

Prioridad alta por verificar en integración

En `DependencyResolver.buildInstallPlan()`, el algoritmo de ordenación topológica incrementa el grado de entrada de los nodos que representan las dependencias, pero después empieza por los nodos con grado cero y recorre las dependencias salientes. Para un grafo simple `aplicación → biblioteca`, esto puede generar el orden `aplicación, biblioteca`, en lugar de `biblioteca, aplicación`.

La ejecución de `AptInstaller.install()` procesa los pasos en el orden recibido. Por tanto, si el grafo representa dependencias de instalación y no existe otra capa que reordene los pasos, el plan invierte el orden requerido.

- Evidencia: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/DependencyResolver.java`, método `buildInstallPlan()`, líneas 419–475.
- Flujo consumidor: `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/AptInstaller.java`, método `install()`, líneas 65–80.
- Clasificación provisional: bug funcional probable, con impacto potencialmente alto en instalaciones con dependencias.
- Verificación pendiente: revisar los tests específicos y confirmar si el contrato de los manifiestos define `dependencies` como requisitos previos de instalación. No voy a darlo por reproducido en ejecución sin esa comprobación.

Este tipo de defecto ilustra por qué la auditoría debe seguir el flujo completo y no limitarse a inspeccionar clases aisladas.

### Hallazgo adicional: trabajo síncrono de traducción en el hilo de servidor de Fabric

Riesgo alto de latencia

La ruta de chat de Fabric ejecuta `dispatcher.dispatch(broadcast)` directamente dentro del callback de `ServerMessageEvents.CHAT_MESSAGE`. En ese recorrido, `MessageDispatcher.dispatch()` llama a `SuiteHost.resolveSourceLanguage()` antes de expandir los destinatarios.

Cuando la detección de idioma no está en caché, `TranslationService.detect()` envía el trabajo a su executor dedicado, pero después espera el resultado mediante `future.get()`. El timeout configurado para la operación de traducción/detección es de 30 segundos.

Consecuencia: tener un executor dedicado para la traducción no vuelve asíncrono al llamador. En un fallo de red, una saturación o una detección lenta, el callback de chat de Fabric puede quedar bloqueado mientras espera la respuesta. El mismo riesgo debe evaluarse en otros eventos de plataforma que invoquen el dispatcher desde el hilo principal.

Evidencia:

- `src/fabric-host/src/main/java/me/majhrs16/suite/fabrichost/TextFormatterSuiteMod.java`, `ServerMessageEvents.CHAT_MESSAGE`, líneas 328–363.
- `src/host/src/main/java/me/majhrs16/suite/host/MessageDispatcher.java`, `dispatch()`, líneas 102–109.
- `src/host/src/main/java/me/majhrs16/suite/host/SuiteHost.java`, `effectiveSource()`, líneas 195–208.
- `src/core-api/src/main/java/me/majhrs16/suite/api/spi/TranslationService.java`, `detect()`, líneas 130–173.

La solución no consiste simplemente en aumentar el tamaño del pool: hay que separar la captura del evento, el procesamiento que puede bloquear y la entrega que exige el hilo de plataforma, preservando la semántica de cancelación, el orden y el estado del jugador.

## 3. Resumen ejecutivo

# 6,2/10

Valoración técnica provisional del estado auditado

# ≈60 %

Madurez técnica aproximada alcanzada

TextFormatter Suite presenta una arquitectura modular ambiciosa, separación razonable de responsabilidades en varias áreas, un núcleo de procesamiento reutilizable y una inversión importante en seguridad, pruebas y automatización. Sin embargo, todavía hay defectos funcionales en componentes críticos, inconsistencias entre el modelo modular y el despliegue, y limitaciones de concurrencia que impiden considerar el sistema listo para una red grande de producción.

La valoración es provisional porque todavía no se ha ejecutado el build ni se ha leído individualmente el contenido de los 537 archivos del árbol. No debe interpretarse como una auditoría exhaustiva ya cerrada.

### Diagnóstico principal

- Fortaleza: la separación entre contratos, procesamiento del mensaje, adaptadores de plataforma y conectores externos tiene una base real.
- Debilidad funcional: el Module Manager y el procesamiento de eventos no están tan integrados como sugeriría una lectura superficial de la documentación.
- Riesgo operativo: el procesamiento síncrono de traducción puede bloquear el hilo principal de Fabric.
- Riesgo de instalación: el resolver de dependencias genera un orden incompatible con la instalación de dependencias previas.
- Riesgo de escalabilidad: la arquitectura incorpora mecanismos de backpressure, colas y cachés, pero no hay evidencia suficiente para afirmar que aguanta una carga network-scale.
- Estado de producción: el README declara expresamente que el proyecto sigue en desarrollo intenso y no es apto para producción. La evidencia técnica revisada no justifica contradecir esa advertencia.

## 4. Cobertura de auditoría

### Qué se ha examinado

| Área                    | Cobertura en esta pasada                                                                                              |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------- |
| Árbol Git               | Inventario recursivo completo del commit, 537 archivos                                                                |
| Configuración de Gradle | `settings.gradle`, build raíz y scripts de módulos principales                                                        |
| Documentación           | `README.md`, `docs/PLAN.md` y documentos de arquitectura/auditoría localizados                                        |
| Arquitectura de módulos | Contratos `Module`/`ModuleDescriptor`, `ModuleLoader`, `ModuleGraph` y `SuiteBootstrap`                               |
| Pipeline de mensajes    | `SuiteHost`, `MessageDispatcher`, traducción y rutas de eventos de Fabric                                             |
| Module Manager          | `ManagerFacade`, `AptInstaller`, `DependencyResolver`, `PackageInstaller`, `TransactionLog`, SPI y tests relacionados |
| Seguridad de red        | `HttpTransport`, `HttpSink`, `DebugEndpoint` y partes de WebSocket                                                    |
| Sincronización          | `DefaultSyncBus`, colas, reintentos y aislamiento por sink                                                            |
| Tests                   | Inventario de los archivos de prueba y lectura de tests representativos                                               |
| Carga                   | Inspección del escenario Gatling y de tests de integración                                                            |
| Historial Git           | Metadatos de hasta 100 commits recientes y mensajes de refactors relevantes                                           |
| Web Editor              | Scripts de comprobación, estructura de tests y lectura parcial de validación/preview                                  |

### Qué no se ha completado

- No se han leído individualmente los 537 archivos.
- No se han recorrido todas las clases Java de todos los módulos.
- No se han ejecutado `./gradlew test`, `./gradlew build`, `npm run check` ni `npm run test:integration`.
- No se ha ejecutado el plugin dentro de Paper o Fabric.
- No se han reproducido los bugs en un servidor.
- No se ha realizado un análisis línea por línea de todos los cambios históricos ni del código eliminado.
- No se han medido latencias, allocations, throughput, GC o p95/p99 reales.

Por tanto, las conclusiones siguientes son una auditoría estática amplia con evidencia concreta, no una certificación de cobertura exhaustiva. Esta distinción es necesaria para cumplir tu regla contra falsos positivos.

## 5. Arquitectura real del sistema

### 5.1. Estructura principal

El proyecto está organizado como un monorepo Gradle con módulos separados para contratos, procesamiento, infraestructura, adaptadores y herramientas.

Adaptadores de plataforma

`spigot-host` · `fabric-host`

Composición y orquestación

`host` · `SuiteHost` · `MessageDispatcher`

Procesamiento

`iflow`

`textformatter`

`presets`

Servicios

`gtranslate`

`ltranslate`

`sync-bus`

Contratos e infraestructura

`core-api` · `transport` · `sync-*` · `observability`

Este diagrama representa las responsabilidades principales, no todas las dependencias Gradle ni una secuencia de ejecución estricta.

### 5.2. Arquitectura pretendida frente a arquitectura implementada

HECHO: `Module.java` define un descriptor SPI; no tiene métodos de inicio, parada ni carga de servicios. `ModuleLoader` descubre implementaciones mediante `ServiceLoader`. `SuiteBootstrap` utiliza el kernel para validar los descriptores y después crea manualmente el router y el formatter.

Fuentes:

- [`Module.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/core-api/src/main/java/me/majhrs16/suite/api/Module.java)
- [`ModuleGraph.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/kernel/src/main/java/me/majhrs16/suite/kernel/ModuleGraph.java)
- [`SuiteBootstrap.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/host/src/main/java/me/majhrs16/suite/host/SuiteBootstrap.java)

Esta separación es coherente con la idea de que `Module` representa un descriptor de dependencias, no un objeto de servicio que deba instanciarse reflexivamente.

La limitación es otra: el grafo no constituye por sí mismo un sistema completo de activación de servicios. El orden resuelto no se usa para instanciar todos los servicios. La composición efectiva sigue ocurriendo mediante wiring explícito en el host y los entry points de plataforma.

Además, la utilidad del grafo para validar dependencias entre módulos depende de que los descriptores declaren correctamente sus requisitos. Los módulos que únicamente declaran capacidades ofrecidas no expresan necesariamente toda su dependencia funcional.

Evaluación: arquitectura válida para un núcleo ensamblado explícitamente; arquitectura de módulos dinámicos todavía incompleta.

## 6. Flujo completo de un mensaje

El pipeline principal puede reconstruirse así:

```
Evento de plataforma
        |
        v
Adaptador Spigot / Fabric
        |
        v
Construcción de Message
        |
        v
MessageDispatcher.dispatch()
        |
        +--> resolver idioma de origen
        |
        +--> comprobar rate limit
        |
        +--> expandir Direction a destinatarios
        |
        v
Por cada destinatario
        |
        v
SuiteHost.deliver()
        |
        +--> determinar idioma del destinatario
        |
        +--> DefaultRouter.route()
        |       |
        |       +--> reglas, permisos y transforms
        |       +--> decisión de entrega / silencio / redirección
        |
        v
TextFormatter.format()
        |
        +--> plantilla del canal
        +--> placeholders
        +--> MiniMessage
        +--> traducción si corresponde
        |
        v
ChatDelivery
        |
        +--> entrega al jugador
        +--> consola o redirección
        |
        v
SyncBus / sinks externos, cuando corresponda
```

Las clases `SuiteHost` y `MessageDispatcher` separan de forma útil el procesamiento por destinatario de la entrega dependiente de la plataforma.

### Problemas de este pipeline

1. La resolución del idioma ocurre antes de distribuir el trabajo a los destinatarios. Esa operación puede ser síncrona aunque después se use un executor.
2. La expansión de destinatarios y la creación de futures tienen un coste proporcional al número de receptores.
3. El orden de entrega depende de la política de ejecución y del adapter.
4. La sincronización cross-server tiene su propio pipeline de colas y reintentos, pero no se ha demostrado que preserve orden global entre servidores.
5. La ruta real cambia entre Spigot y Fabric: no debe suponerse que ambos adaptadores comparten exactamente la misma semántica de threading.

## 7. Hallazgos técnicos

Los siguientes hallazgos son los que tienen evidencia suficiente para justificar cambios o pruebas específicas.

### TXF-AUD-001 — Orden incorrecto del plan de instalación

Tipo: Bug Severidad: Alta Confianza: Confirmado por análisis estático del algoritmo Ubicación: `DependencyResolver.buildInstallPlan()` y `AptInstaller.install()`

Descripción

El algoritmo de ordenación topológica trata una relación de dependencia como si el paquete dependiente debiera aparecer antes que el paquete requerido.

Para el grafo:

```
aplicación → biblioteca
```

el algoritmo puede producir:

```
aplicación, biblioteca
```

cuando la instalación requiere:

```
biblioteca, aplicación
```

Causa

En `DependencyResolver.buildInstallPlan()`, el grado de entrada se incrementa sobre los nodos de dependencia y, posteriormente, el algoritmo inicia la ordenación por los nodos con grado cero. Después decrementa el grado de entrada de las dependencias del nodo que acaba de visitar. Esa dirección produce el orden inverso al requerido por una instalación de dependencias previas.

Impacto funcional

`AptInstaller.install()` procesa los pasos en el orden recibido. Además, `PackageInstaller` valida que las dependencias estén instaladas. En consecuencia, una instalación de un paquete nuevo con dependencias todavía ausentes puede fallar antes de instalar dichas dependencias.

Evidencia

- [`DependencyResolver.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/DependencyResolver.java), `buildInstallPlan()`, líneas 419–475.
- [`AptInstaller.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/AptInstaller.java), `install()`, líneas 65–80.
- [`PackageInstaller.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/manager-core/src/main/java/me/majhrs16/suite/manager/core/PackageInstaller.java), validación de dependencias, alrededor de las líneas 447–463.

Solución sugerida

Construir el grafo con aristas desde la dependencia hacia el paquete que la necesita, o invertir la estrategia de ordenación de Kahn para que las dependencias se emitan primero.

Test mínimo:

```
A depende de B
B no depende de nadie

Resultado esperado del plan:
B, A
```

Añadir también un caso con dos dependencias y otro con una dependencia transitiva.

Evaluación de la solución: esfuerzo bajo; beneficio alto; riesgo de regresión bajo si se prueban los casos acíclicos y los ciclos.

### TXF-AUD-002 — Bloqueo potencial del hilo principal en Fabric

Tipo: Bug Severidad: Alta Confianza: Confirmado por el flujo de llamadas Ubicación: `TextFormatterSuiteMod`, `MessageDispatcher`, `SuiteHost`, `TranslationService`

Descripción

El callback `ServerMessageEvents.CHAT_MESSAGE` ejecuta directamente `dispatcher.dispatch(broadcast)`.

Antes de procesar destinatarios, `MessageDispatcher.dispatch()` llama a `host.resolveSourceLanguage()`. Si el idioma de origen no está resuelto y la traducción está disponible, `SuiteHost.effectiveSource()` llama a `translation.detect()`.

`TranslationService.detect()` envía la operación a un executor, pero espera su resultado con `future.get()`. El executor tiene un timeout de operación configurado en 30 segundos.

Causa

La ejecución del trabajo en otro hilo no evita que el hilo que llama a `detect()` espere sincrónicamente el resultado. La llamada se produce dentro de la ruta de chat de Fabric, que el propio adaptador documenta como ejecutada en el hilo del servidor.

Impacto funcional y de rendimiento

Una detección lenta o una avería del proveedor puede detener el procesamiento de eventos del servidor durante un periodo significativo. Esto puede manifestarse como ticks tardíos, latencia de chat y degradación de la experiencia de juego.

Evidencia

- [`TextFormatterSuiteMod.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/fabric-host/src/main/java/me/majhrs16/suite/fabrichost/TextFormatterSuiteMod.java), callback de chat, líneas 328–363.
- [`MessageDispatcher.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/host/src/main/java/me/majhrs16/suite/host/MessageDispatcher.java), `dispatch()`, líneas 102–109.
- [`TranslationService.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/core-api/src/main/java/me/majhrs16/suite/api/spi/TranslationService.java), `detect()`, líneas 130–173.

Solución sugerida

Separar las fases de captura y procesamiento:

1. Capturar y validar el evento en el hilo de plataforma.
2. Pasar la resolución de idioma, traducción y formateo a un executor apropiado.
3. Volver al hilo de plataforma únicamente para las operaciones que lo exijan.
4. Definir qué sucede si el evento ya no es válido cuando termina el trabajo.
5. Preservar el orden del chat por canal y la semántica de cancelación.

No basta con activar `engine.parallel`: la resolución del idioma ocurre antes de la distribución paralela.

Evaluación: esfuerzo medio; beneficio alto; requiere pruebas de ordering, desconexiones y shutdown.

### TXF-AUD-003 — Artefacto Spigot potencialmente incompleto

Tipo: Defecto de diseño / empaquetado Severidad: Alta Confianza: Alta para el empaquetado; ejecución no reproducida Ubicación: `src/spigot-host/build.gradle`, `TextFormatterSuitePlugin`

Descripción

La configuración de `shadowJar` establece `configurations = []` y excluye las dependencias de módulos del proyecto. Sin embargo, `TextFormatterSuitePlugin` contiene referencias directas a clases de `host`, `core-api`, `iflow`, `sync-*`, `manager-*` y otros módulos.

Por su configuración, el artefacto `shadowJar` no parece incluir las clases de esos módulos.

Causa

El empaquetado pretende dejar únicamente las clases del plugin y delegar los módulos a un manager. Pero el runtime hace referencias directas a esas clases antes de inicializar el `ManagerFacade`. El manager revisado instala paquetes en disco, pero no proporciona un mecanismo funcional de carga dinámica para resolver esas referencias: su adapter de compatibilidad contiene métodos `load()`, `register()`, `unregister()` y `unload()` que lanzan `UnsupportedOperationException`.

Impacto

Si el artefacto resultante se ejecuta sin que los módulos estén disponibles en el classloader del plugin por otra vía, el arranque puede fallar con errores de clases ausentes. El repositorio no publica releases oficiales y declara que aún no es un plugin listo para descargar, así que el impacto sobre una distribución publicada no se puede afirmar; el defecto afecta a la coherencia del proceso de empaquetado y despliegue.

Evidencia

- [`spigot-host/build.gradle`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/spigot-host/build.gradle), configuración de `shadowJar`, líneas 88–129.
- [`TextFormatterSuitePlugin.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/spigot-host/src/main/java/me/majhrs16/suite/spigothost/TextFormatterSuitePlugin.java), imports y `onEnable()`.
- [`ModuleLifecycleAdapter.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/ModuleLifecycleAdapter.java), métodos de carga y registro.

Solución sugerida

Elegir explícitamente uno de estos modelos:

- Artefacto autónomo: empaquetar las dependencias de proyecto necesarias en el plugin, excluyendo solo APIs que proporcione la plataforma.
- Artefacto modular real: eliminar las referencias estáticas que requieren clases aún no cargadas, implementar una fase de resolución/carga antes del wiring y definir un classloader compatible con el lifecycle de Bukkit/Paper.

No conviene mantener un empaquetado híbrido que excluye módulos mientras el código depende directamente de ellos.

Evaluación: esfuerzo medio; beneficio alto; riesgo de regresión medio. Antes de modificarlo, hay que inspeccionar el JAR generado y ejecutar un arranque real de Paper.

### TXF-AUD-004 — Recuperación transaccional incompleta en `TransactionLog`

Tipo: Defecto de diseño / deuda técnica Severidad: Media Confianza: Confirmado en la implementación revisada Ubicación: `TransactionLog.getIncompleteOperations()` y `recoverIncompleteTransactions()`

Descripción

`getIncompleteOperations()` recorre todas las líneas del log y añade cada entrada cuyo estado no sea `COMPLETED`, `FAILED` o `ROLLED_BACK`. No agrupa por `operationId` ni determina cuál es el estado más reciente de cada operación.

Por tanto, una instalación completada puede dejar registros históricos como `STARTED`, `EXTRACTING` o `MOVED` que el método seguirá clasificando como incompletos.

Además, `recoverIncompleteTransactions()` solo marca cada entrada como fallida y registra un aviso; no ejecuta una recuperación real basada en el estado de la transacción.

Impacto

Si el método se invoca durante el arranque, puede generar falsos positivos de transacciones incompletas y añadir repetidamente estados de fallo al log. No he encontrado una llamada a ese método en la búsqueda realizada, así que no afirmo que la recuperación se ejecute actualmente durante el arranque.

Evidencia

[`TransactionLog.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469/src/manager-core/src/main/java/me/majhrs16/suite/manager/core/TransactionLog.java), líneas 82–125.

Solución sugerida

Reconstruir el último estado por `operationId` y clasificar como incompletas únicamente las operaciones cuyo último estado sea no terminal. Después, implementar recuperación específica por fase o cambiar el nombre/documentación del método si solo pretende marcar operaciones como fallidas.

Evaluación: esfuerzo medio; beneficio medio-alto para la integridad del manager; riesgo de regresión bajo con tests de secuencias de estados.

### TXF-AUD-005 — El Module Manager no ofrece actualmente un lifecycle dinámico completo

Tipo: Limitación funcional / defecto de diseño Severidad: Alta respecto a la funcionalidad de carga dinámica Confianza: Confirmado por los métodos revisados Ubicación: `ManagerFacade`, `ModuleLifecycleAdapter`

El nuevo manager está estructurado como un sistema de paquetes estilo APT/dpkg: repositorios, resolución de dependencias, descargas, checksum, extracción e instalación.

No obstante:

- `ManagerFacade.discoverAll()` obtiene módulos mediante `ModuleLoader` del classloader proporcionado.
- El propio código indica que la detección de módulos instalados por paquete aún no está implementada en ese método.
- `ModuleLifecycleAdapter.load()`, `register()`, `unregister()` y `unload()` lanzan `UnsupportedOperationException`.
- La instalación de paquetes en disco no equivale a incorporarlos al classloader activo de la JVM.

Esto puede ser una decisión arquitectónica legítima si el manager se limita a instalar paquetes para un siguiente arranque. Se convierte en una limitación si la API de compatibilidad se presenta como soporte para cargar, registrar y descargar módulos en runtime.

Solución sugerida: separar claramente `PackageManager` de `RuntimeModuleRegistry`. La primera responsabilidad puede instalar, verificar y eliminar artefactos; la segunda debe gestionar descubrimiento, classloaders, activación, aislamiento y shutdown, o declarar explícitamente que estas operaciones solo son posibles tras reiniciar.

Evaluación: esfuerzo medio-alto; beneficio alto si la carga dinámica es un objetivo; no conviene implementar hot unload hasta definir referencias vivas, recursos y contratos de lifecycle.

### TXF-AUD-006 — Validación IPv6 de `HttpTransport` incompleta

Tipo: Riesgo de seguridad / compatibilidad de red Severidad: Media Confianza: Riesgo probable; explotación no demostrada Ubicación: `HttpTransport.validateUrlAndGetAddress()` y `createConnection()`

Descripción

La protección SSRF utiliza patrones de texto para varios rangos IPv4 e IPv6. Los patrones para IPv6 ULA están escritos de forma que no cubren de manera generalizada las representaciones habituales de direcciones como `fd12:3456::1`. Además, el pinning sustituye el hostname en la URL mediante una expresión regular que no trata correctamente todos los casos de IPv6 con corchetes y dos puntos.

Impacto

La validación no representa de forma robusta todos los rangos IPv6 internos, y los destinos IPv6 pueden fallar por la construcción de URL. No afirmo que el caso descrito permita alcanzar una dirección privada: la reescritura de URL puede fallar antes de conectar, por lo que hace falta una prueba dirigida para determinar si existe una ruta explotable.

Evidencia

[`HttpTransport.java`](https://github.com/majhrs16-official/TextFormatter-Suite/blob/4f9e8aaf427b30b502360d531eb7f637f6695469), `DEFAULT_DENY_PATTERNS`, `createConnection()` y `validateUrlAndGetAddress()`.

Solución sugerida

Sustituir las comprobaciones de rango por comparaciones binarias sobre `InetAddress`/bytes, cubrir ULA, link-local, loopback, multicast, IPv4-mapped IPv6 y rangos reservados, y construir la URL con una API que gestione correctamente los literales IPv6. Añadir tests con DNS controlado para evitar depender de la red pública.

Evaluación: esfuerzo medio; beneficio importante para seguridad y compatibilidad; riesgo de regresión medio.

### TXF-AUD-007 — Tests del instalador que no verifican lo que su nombre promete

Tipo: Deuda técnica de testing Severidad: Media Confianza: Confirmado Ubicación: `PackageInstallerTest`

Algunos tests contienen comentarios que explican que no pueden construir fácilmente el fixture necesario y no hacen la aserción correspondiente. Por ejemplo, los tests de checksum inválido y archivo duplicado no demuestran actualmente esos comportamientos por sí mismos.

Esto no demuestra que el código del instalador esté equivocado; demuestra que la suite no ofrece la evidencia de regresión que su nombre sugiere.

Solución sugerida: crear helpers de ZIP válidos y mutables, generar manifests con hashes incorrectos y duplicados reales, y verificar la excepción y la ausencia de cambios parciales en disco y base de datos.

Evaluación: esfuerzo bajo-medio; beneficio alto en un componente que escribe y elimina archivos; riesgo bajo.

### TXF-AUD-008 — El escenario Gatling no coincide con el endpoint de debug revisado

Tipo: Defecto de testing / deuda técnica Severidad: Media Confianza: Confirmado en el código revisado Ubicación: `SuiteLoadTestSimulation.scala` y `DebugEndpoint.java`

El escenario Gatling realiza peticiones a `/debug/simulate`, pero `DebugEndpoint` documenta que ese endpoint fue eliminado para prevenir la inyección remota de mensajes y registra solo endpoints de lectura.

El escenario WebSocket utiliza el token literal `test-token`, que solo funcionará si coincide con la configuración real del servidor de pruebas.

Impacto

El escenario no demuestra el throughput del pipeline de mensajes si el endpoint de simulación ya no existe o no está configurado para las pruebas. Tener un escenario de carga en el repositorio no significa que exista un benchmark válido del procesamiento real.

Solución sugerida: reemplazar el endpoint obsoleto por un generador de carga de integración que invoque el pipeline real con actores y sinks controlados, o desplegar un harness de prueba aislado que no se publique como endpoint de producción.

Evaluación: esfuerzo medio; beneficio alto para poder justificar decisiones de escalabilidad.

## 8. Clean Architecture

Puntuación provisional: 6,5/10.

Hay una separación real entre contratos, lógica de procesamiento y adaptadores de plataforma. `core-api` proporciona tipos de dominio y contratos SPI; `host` compone servicios; `spigot-host` y `fabric-host` integran APIs externas.

No obstante, no todos los módulos son dependencias hacia dentro en el sentido estricto de Clean Architecture:

- `host` depende de `textformatter`, `iflow` y `transport`.
- Los adaptadores de plataforma conocen múltiples módulos de funcionalidad e infraestructura.
- El wiring se realiza explícitamente en los hosts, pero también existen construcciones de servicios en varios puntos.
- El grafo de descriptores y el grafo real de dependencias Gradle no son equivalentes.
- La presencia de contratos SPI no garantiza que toda integración externa esté detrás de un port definido en el núcleo.

La decisión de no convertir `Module` en un objeto de lifecycle no es una violación arquitectónica; de hecho, evita mezclar metadatos con servicios. El problema es la integración incompleta del modelo de módulos con el modelo de empaquetado y runtime.

Veredicto: buena dirección arquitectónica, con fronteras parcialmente efectivas. No es correcto afirmar que todo el sistema cumple Clean Architecture estrictamente.

## 9. Hexagonal Architecture

Puntuación provisional: 7/10.

Los elementos realmente hexagonales incluyen:

- contratos en `core-api`;
- ports como `ChatDelivery`;
- adaptadores de plataforma para entrega y eventos;
- proveedores de traducción y sinks detrás de interfaces;
- infraestructura de transporte separada de los módulos que la consumen.

La reemplazabilidad es real en varios puntos: un sink o proveedor puede implementar su contrato sin reescribir el formatter.

Las limitaciones son:

1. La composición del sistema sigue estando fuertemente ligada a los hosts.
2. El Module Manager no proporciona actualmente el ciclo de vida dinámico que sugeriría una interpretación más ambiciosa de la modularidad.
3. La entrega y el threading dependen de los adaptadores y no hay una semántica uniforme demostrada entre plataformas.
4. La presencia de puertos no implica que todos los efectos externos pasen por ellos.

Veredicto: hexagonal en partes importantes del núcleo y en algunas integraciones, pero no completamente desacoplada en despliegue y ciclo de vida.

## 10. Modularidad y separación de responsabilidades

Puntuación de modularidad: 7/10. Puntuación de separación de responsabilidades: 6,5/10.

### Lo que está bien

- `core-api` centraliza los contratos fundamentales.
- `iflow` y `textformatter` tienen responsabilidades diferentes.
- Las traducciones están separadas en proveedores distintos.
- `sync-bus` unifica la difusión a varios sinks.
- `transport` evita replicar la misma implementación HTTP en cada proveedor.
- Los adaptadores de plataforma están aislados en módulos propios.
- `observability`, `extension-api`, `manager-*`, `loadtest` y `performance` separan preocupaciones operativas.

### Lo que limita la modularidad real

- El host tiene un papel importante de orquestación y conoce muchas integraciones.
- La configuración de `shadowJar` en Spigot no está alineada con las referencias estáticas a los módulos.
- El Module Manager instala artefactos, pero no completa su integración en el runtime.
- Algunos módulos de herramientas o compatibilidad parecen más cercanos a la implementación concreta del host que a contratos independientes.

No recomiendo fusionar módulos por el simple hecho de que el número sea elevado. La modularidad actual tiene una justificación razonable. La prioridad es hacer coherentes los límites de compilación, empaquetado y runtime, no reducir el número de directorios.

## 11. Evaluación por feature

| Feature                | Evaluación provisional                        | Riesgo o limitación principal                                                                      |
| ---------------------- | --------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| MiniMessage y formateo | Buena base                                    | Coste de renderizado por destinatario y necesidad de medir plantillas complejas                    |
| iFlow y reglas         | Buena separación                              | El coste y la seguridad dependen de condiciones, transforms y límites de ejecución                 |
| Traducción Google      | Funcionalidad real                            | Dependencia de red/proveedor y latencia variable                                                   |
| LibreTranslate         | Útil para self-hosting                        | La disponibilidad y capacidad dependen del servicio externo                                        |
| SyncBus                | Diseño prometedor                             | Pérdidas bajo saturación, reintentos y orden requieren pruebas de fallo                            |
| HTTP sink              | Buena superficie de integración               | La seguridad depende de configuración, autenticación y límites de payload                          |
| TCP/UDP                | Ligero y flexible                             | UDP no garantiza entrega; TCP necesita controlar framing y reconexiones                            |
| WebSocket              | Funcionalidad extensa                         | Requiere pruebas de autenticación, presión de conexiones y reconexión                              |
| Discord                | Integración amplia                            | Dependencia externa, rate limits y coste de serialización/red                                      |
| Telegram               | Integración útil                              | Long polling y disponibilidad remota                                                               |
| Velocity sync          | Arquitectura orientada a operación            | La etiqueta “production-ready” no está justificada por benchmarks en esta auditoría                |
| Observabilidad         | Buena base                                    | Métricas locales no sustituyen métricas de latencia end-to-end                                     |
| Extension SDK          | Potencial importante                          | La seguridad y compatibilidad del código de extensiones necesitan un modelo de confianza explícito |
| Module Manager         | Funcionalidad incompleta como runtime manager | Resolver de dependencias y lifecycle dinámico                                                      |
| Web Editor             | Buena inversión de UX y testing               | No se ha hecho una auditoría exhaustiva de todos los flujos del editor                             |
| In-world               | Funcionalidad específica útil                 | Estado de ejecución real no verificado aquí                                                        |
| Presets                | Facilita configuración y personalización      | Deben medirse los costes y restricciones de las transformaciones                                   |
| Performance/loadtest   | Infraestructura presente                      | El harness de carga revisado tiene endpoints obsoletos                                             |

No asigno un porcentaje de corrección por feature: no existe suficiente evidencia de ejecución para justificarlo.

## 12. Personalización

La capacidad de personalización es una fortaleza del diseño. El proyecto admite formatos de canal, reglas, transformaciones, traducciones, placeholders, sincronización, configuración de plataforma y extensiones.

La cuestión relevante no es cuántas opciones existen, sino si las combinaciones permitidas funcionan coherentemente.

### Restricciones que sí tienen justificación técnica

- Limitar el tamaño de los payloads de red para evitar consumo ilimitado de memoria.
- Rechazar rutas de extracción que escapen del directorio permitido.
- Limitar colas y definir qué sucede cuando se saturan.
- Validar rangos de versiones y compatibilidad de contratos.
- Impedir operaciones inseguras que puedan corromper el estado o comprometer el servidor.

### Restricciones que requieren justificación específica

Un valor de configuración inusual no debe rechazarse solo por ser extraño. En el caso de `port = -50`, la validación debería basarse en si el valor puede representarse como un endpoint válido para el componente concreto. Si el campo pretende ser un puerto TCP/UDP, el rango válido del protocolo proporciona una razón técnica clara para rechazarlo. Si es un identificador o un valor de sentinel interno, no necesariamente.

Evaluación: la orientación hacia la configurabilidad es buena. La prueba pendiente es una matriz de combinaciones de configuración, no la adición indiscriminada de opciones.

## 13. Seguridad

Puntuación provisional: 6/10.

El repositorio incorpora medidas reales de hardening: `SafeConstructor` para YAML en los lugares documentados, protección de salida MiniMessage, límites de payload en conectores, autenticación en endpoints y validación SSRF en `HttpTransport`.

Esto es positivo, pero no equivale a una certificación de seguridad.

### Evaluación por superficie

Configuración YAML. La revisión observada usa `SafeConstructor` en el host y varios componentes. Es una mejora adecuada contra deserialización insegura. Falta comprobar individualmente todos los loaders para afirmar cobertura completa.

MiniMessage y SpEL. El proyecto dispone de escape de texto y un evaluador de expresiones con restricciones. La garantía depende de todos los caminos que introducen texto externo y de que ninguna ruta use un evaluador más permisivo.

HTTP y SSRF. La validación de direcciones es una defensa necesaria, pero el tratamiento de IPv6 requiere pruebas específicas, como se indica en TXF-AUD-006.

HTTP entrante. `HttpSink` define límite de cuerpo, autenticación Bearer/HMAC y una política localhost-only si no hay credenciales configuradas. Es una base razonable, aunque la seguridad real depende de la configuración final de bind y autenticación.

WebSocket. El sink documenta límites de tamaño, control de conexiones y autenticación. No he verificado exhaustivamente todas las rutas de autenticación ni su comportamiento bajo reconexión y saturación.

Module Manager y cadena de suministro. Un checksum SHA-256 permite verificar que el archivo descargado coincide con el digest esperado. No autentica por sí solo al publicador si un atacante puede reemplazar tanto el índice como el artefacto. Si el manager debe cargar código de terceros, la política de confianza de repositorios y la exigencia de firmas deben estar explícitas.

Secretos en `char[]`. Reducir el tiempo de vida de ciertos secretos en memoria es una mejora limitada, no una garantía de eliminación: librerías HTTP, clientes externos y conversiones pueden crear copias.

No he encontrado evidencia suficiente para declarar una vulnerabilidad crítica explotable en todo el proyecto. Tampoco existe evidencia suficiente para declarar el sistema seguro.

## 14. Rendimiento y concurrencia

Puntuación de rendimiento: 5,5/10. Puntuación de concurrencia: 5/10.

### Aspectos positivos

- `MessageDispatcher` usa un executor acotado cuando el procesamiento paralelo está habilitado.
- `TranslationService` dispone de cachés y deduplicación de peticiones en curso.
- `DefaultSyncBus` separa la cola global de las colas por sink.
- Hay límites para varias colas, reintentos y payloads.
- Se utiliza un scheduler independiente para mensajes con retardo.

### Costes que deben medirse

1. Fan-out: el trabajo crece con el número de destinatarios. Cada receptor puede implicar routing, formato y entrega.
2. Futures: construir una lista de futures para cada receptor añade objetos temporales y presión de GC.
3. Traducción: las llamadas remotas dominan potencialmente la latencia cuando hay cache misses.
4. Reglas: la complejidad depende del número de reglas y del coste de las expresiones.
5. SyncBus: los sinks lentos consumen sus propias colas; al llenarse, algunos mensajes se descartan.
6. Serialización: el codec JSON y los conectores generan objetos temporales y copias de strings.
7. Locks y estado compartido: hay estructuras concurrentes, pero eso no prueba que todas las operaciones compuestas sean atómicas.

### Qué no se puede afirmar todavía

No se puede asignar un coste real por mensaje ni un p95/p99 fiable. Para hacerlo hacen falta benchmarks que separen:

- detección de idioma;
- traducción con cache hit y cache miss;
- routing;
- formateo;
- fan-out a diferentes cantidades de receptores;
- entrega local;
- entrega remota;
- difusión a varios sinks;
- saturación y recuperación de colas.

Los números de rendimiento deben venir de mediciones reproducibles, no de extrapolaciones a partir de la arquitectura.

## 15. Escalabilidad: carga tipo Hypixel / UniversoCraft

La arquitectura ofrece varios componentes que podrían formar parte de una solución escalable, pero no se ha demostrado que la implementación actual soporte una red de ese tamaño.

| Escenario                 | Evaluación provisional       | Principal riesgo                                                     |
| ------------------------- | ---------------------------- | -------------------------------------------------------------------- |
| A — Servidor pequeño      | Viable conceptualmente       | Bloqueo por traducción, configuración y fallos externos              |
| B — Red mediana           | Posible con tuning y pruebas | Colas, latencia remota y coherencia de sincronización                |
| C — Red grande            | No demostrado                | Fan-out, saturación, fallos parciales, ordering y p99                |
| D — Network-scale extrema | No se puede afirmar          | Capacidad real de todos los componentes y comportamiento distribuido |

No asigno throughput numérico a ninguno de los escenarios: el repositorio no aporta en esta revisión mediciones válidas que permitan justificarlo.

### Qué debe demostrarse para network-scale

1. Latencia p50/p95/p99 del procesamiento local sin traducción.
2. Latencia p50/p95/p99 con traducción remota y fallos del proveedor.
3. Throughput por servidor y por sink.
4. Comportamiento de colas llenas, descartes y reintentos.
5. Ordering por canal entre varios servidores.
6. Reconexión de WebSocket, TCP, Discord y Telegram.
7. Efecto de un sink lento sobre el resto del sistema.
8. GC, heap, CPU y número de threads bajo bursts.
9. Tiempo de recuperación tras caídas de red.
10. Pérdida de mensajes y duplicación bajo fallos parciales.

El SyncBus tiene una separación razonable entre la cola global y las colas por sink. No obstante, el aislamiento no significa entrega garantizada: una cola acotada que descarta mensajes al saturarse protege la memoria, pero cambia la semántica de entrega.

Para una red grande es necesario decidir explícitamente qué mensajes pueden descartarse, cuáles requieren persistencia, qué ordering se exige y cómo se recuperan los nodos después de una desconexión.

## 16. Testing

La estructura de pruebas es más madura que la de un proyecto que solo compila: existen unit tests, tests de configuración, tests de codecs, pruebas de sinks, pruebas de seguridad de expresiones y tests de integración.

La limitación es que la cantidad de tests no demuestra su calidad ni la cobertura de estados límite.

### Prioridades de testing

- P0: orden de instalación de dependencias.
- P0: arranque real del artefacto Spigot generado.
- P1: traducción lenta desde eventos Fabric y operaciones de shutdown.
- P1: instalación interrumpida y recuperación transaccional.
- P1: payloads HTTP/UDP/WebSocket excesivos y entradas malformadas.
- P1: backpressure y comportamiento bajo saturación del SyncBus.
- P2: pruebas de compatibilidad de configuración entre plataformas.
- P2: pruebas de contract testing para cada implementación de SPI.
- P2: pruebas de integración del Web Editor con configuración exportada/importada.
- P2: tests de red simulada para timeouts, reintentos y reconexión.

La suite del instalador necesita que los tests incompletos se conviertan en pruebas con fixtures reales. Y el harness de carga debe alinearse con los endpoints que realmente existen.

No he ejecutado la suite; por tanto, no afirmo que esté verde ni que los tests actuales fallen.

## 17. Mantenibilidad y evolución

Puntuación provisional: 6/10.

### A un año

El sistema puede seguir evolucionando de manera razonable si se corrigen los problemas de integración del manager, empaquetado y threading. Las áreas más sensibles serán el contrato `core-api`, la configuración y los adapters de plataforma.

### A tres años

Si crecen las features sin estabilizar los contratos, la compatibilidad entre módulos, las versiones de configuración y el lifecycle de extensiones pueden convertirse en los principales costes de mantenimiento.

### A cinco años

La mantenibilidad dependerá de que exista una política clara de versionado de contratos, migración de configuración, compatibilidad binaria y soporte de plataformas. El diseño modular ofrece una base para ello, pero no lo garantiza por sí solo.

### Onboarding

- 30 minutos: un desarrollador puede identificar módulos, entry points y estructura del monorepo.
- 2 horas: puede reconstruir el pipeline básico de mensajes y localizar la configuración principal.
- 1 día: puede seguir el flujo de un feature concreto y comprender el contrato SPI asociado.
- 1 semana: debería poder modificar una feature y sus tests, siempre que las fronteras de módulo y las convenciones estén actualizadas.

El README es útil como mapa general, pero presenta nombres de módulos y estados de features que deben reconciliarse con el árbol actual. Esa deriva documental aumenta el coste de onboarding.

## 18. Historial de Git y evolución arquitectónica

El historial reciente muestra una evolución rápida, con varios ciclos de auditoría y corrección:

- reestructuración importante a finales de septiembre;
- endurecimiento de seguridad en varias etapas;
- consolidación de transporte y sincronización;
- incorporación de `SyncBus`;
- integración de Fabric;
- reimplementación del Module Manager durante los días 9–11 de octubre;
- commit auditado con el mensaje `fix: resolve all MGR-001 through MGR-018 audit findings from AUDITORIA.md`.

Esto sugiere una arquitectura que está evolucionando activamente, no una arquitectura completamente estabilizada.

La repetición de auditorías y correcciones no es necesariamente negativa. Es positivo que el proyecto responda a hallazgos y documente cambios. El riesgo aparece cuando se declara completado un conjunto de correcciones sin que los tests cubran el comportamiento completo, o cuando un refactor modifica la arquitectura más rápido de lo que se actualizan las guías y los contratos.

La revisión histórica de esta pasada se basa principalmente en los metadatos de commits recientes; no equivale a un análisis completo de todos los diffs históricos.

## 19. Matriz de puntuaciones

Las puntuaciones son estimaciones técnicas provisionales, no métricas automáticas ni resultados de benchmarks.

| Área                            | Puntuación /10 | Estado                     | Principal limitación                                     |
| ------------------------------- | -------------- | -------------------------- | -------------------------------------------------------- |
| Código                          | 6,5            | Bueno con reservas         | Defectos funcionales en áreas críticas                   |
| Arquitectura                    | 6,5            | Buena base                 | Integración incompleta del runtime modular               |
| Clean Architecture              | 6,5            | Parcialmente sólida        | Wiring y dependencias concretas del host                 |
| Hexagonal                       | 7,0            | Relativamente sólida       | Límites no uniformes en todas las integraciones          |
| Modularidad                     | 7,0            | Buena                      | Empaquetado y carga dinámica                             |
| Separación de responsabilidades | 6,5            | Buena                      | Orquestación y lifecycle repartidos                      |
| Legibilidad                     | 6,5            | Razonable                  | Deriva documental y complejidad de integración           |
| Features                        | 7,0            | Amplias                    | Varias requieren validación real de ejecución            |
| Personalización                 | 7,0            | Buena orientación          | Falta matriz exhaustiva de combinaciones                 |
| Seguridad                       | 6,0            | Medidas reales             | Validación de red y confianza del manager                |
| Rendimiento                     | 5,5            | No demostrado a escala     | Bloqueo síncrono y coste de fan-out                      |
| Escalabilidad                   | 4,5            | No demostrada              | Ausencia de benchmarks válidos y garantías de entrega    |
| Concurrencia                    | 5,0            | Parcialmente bien diseñada | Threading distinto entre plataformas                     |
| Testing                         | 5,5            | Base razonable             | Tests incompletos y carga desalineada                    |
| Mantenibilidad                  | 6,0            | Prometedora                | Contratos, lifecycle y docs deben estabilizarse          |
| Documentación                   | 6,0            | Amplia, con deriva         | Algunas descripciones no coinciden con la implementación |

## 20. Potencial

### Potencial teórico: alto

La combinación de un núcleo independiente de plataforma, un motor de reglas, un formatter extensible, traducción, sinks de sincronización, observabilidad y un SDK de extensiones puede convertirse en una plataforma potente para redes de Minecraft.

### Potencial arquitectónico: medio-alto

La arquitectura actual permite evolucionar varios subsistemas de forma relativamente independiente. La limitación más importante es la integración entre módulos, empaquetado, carga y lifecycle. También hay que eliminar los bloqueos en las rutas de eventos que exigen baja latencia.

### Potencial alcanzado: aproximadamente 60 %

Esta cifra es una estimación cualitativa de madurez, no una medición de líneas implementadas. Hay muchas funcionalidades materializadas y una base arquitectónica útil, pero todavía faltan garantías operativas en instalación, threading, despliegue y escala.

## 21. Roadmap de mejoras

### P0 — Resolver antes de considerar producción

| Acción                                                        | Beneficio                                        | Esfuerzo |
| ------------------------------------------------------------- | ------------------------------------------------ | -------- |
| Corregir orden topológico del Module Manager                  | Instalar dependencias antes que sus consumidores | Bajo     |
| Validar el artefacto Spigot generado y su arranque            | Evitar errores de clases ausentes en despliegue  | Medio    |
| Eliminar trabajo de red síncrono del hilo principal de Fabric | Reducir riesgo de congelación del servidor       | Medio    |
| Añadir tests reales para el orden de dependencias             | Evitar regresiones en el resolver                | Bajo     |

### P1 — Estabilidad y seguridad

| Acción                                                     | Beneficio                                          | Esfuerzo   |
| ---------------------------------------------------------- | -------------------------------------------------- | ---------- |
| Corregir el tratamiento de IPv6 en `HttpTransport`         | Mejorar protección SSRF y compatibilidad           | Medio      |
| Completar tests de ZIP, checksum y rollback                | Mayor confianza en el instalador                   | Medio      |
| Rehacer recuperación de `TransactionLog` por último estado | Evitar falsos positivos y recuperación incompleta  | Medio      |
| Definir la política de confianza de repositorios y firmas  | Reducir riesgo de suministro de módulos maliciosos | Medio-alto |
| Probar saturación y pérdida de mensajes del SyncBus        | Definir garantías de entrega reales                | Medio      |

### P2 — Mantenimiento y arquitectura

| Acción                                                    | Beneficio                                       | Esfuerzo   |
| --------------------------------------------------------- | ----------------------------------------------- | ---------- |
| Separar instalación de paquetes de runtime module loading | Aclarar responsabilidades del manager           | Medio-alto |
| Reconciliar README, PLAN y nombres de módulos             | Reducir confusión durante el onboarding         | Bajo-medio |
| Añadir pruebas contractuales de SPI                       | Verificar compatibilidad entre implementaciones | Medio      |
| Alinear el harness Gatling con el pipeline real           | Hacer válidos los resultados de carga           | Medio      |
| Medir fan-out, traducción y serialización por separado    | Localizar hotspots reales                       | Medio      |

### P3 — Mejoras opcionales

| Acción                                                          | Beneficio                     | Esfuerzo   |
| --------------------------------------------------------------- | ----------------------------- | ---------- |
| Ampliar ejemplos de extensiones                                 | Mejor onboarding              | Bajo       |
| Añadir guías de migración de configuración por versión          | Menor coste de upgrades       | Medio      |
| Añadir perfiles de configuración para diferentes tamaños de red | Facilitar despliegue y tuning | Medio      |
| Automatizar la reconciliación de documentos generados           | Reducir deriva documental     | Bajo-medio |

No recomiendo una reescritura completa. Los defectos principales detectados admiten correcciones localizadas.

## 22. Conclusión final

1\. ¿Qué tan bueno es realmente TextFormatter Suite?

Es un proyecto técnicamente ambicioso con una base modular razonable y varias features reales. La calidad arquitectónica es superior a la de un plugin monolítico sin contratos ni pruebas, pero aún no alcanza un nivel de madurez operativa que justifique tratarlo como plataforma de producción.

2\. ¿Qué partes están excepcionalmente bien diseñadas?

La separación entre el modelo de mensaje, el routing, el formatter y los adapters de plataforma; la existencia de contratos SPI; y el diseño del SyncBus con colas y aislamiento por sink son las partes más prometedoras.

3\. ¿Qué partes son mediocres?

La integración del lifecycle de módulos, el empaquetado del host Spigot, la recuperación transaccional y la validación de la escala real. No es necesariamente mala la idea; es incompleta la conexión entre sus componentes.

4\. ¿Cuáles son sus peores defectos?

El orden incorrecto del plan de instalación de dependencias, el bloqueo síncrono en la ruta de chat de Fabric y la inconsistencia entre el empaquetado Spigot y las referencias estáticas a los módulos.

5\. ¿Cuál es su mayor fortaleza arquitectónica?

La posibilidad de separar funcionalidades mediante contratos y reemplazar proveedores o sinks sin reescribir el núcleo de procesamiento.

6\. ¿Cuál es su mayor riesgo futuro?

Que el número de features y módulos crezca más rápido que las garantías de integración: contratos estables, lifecycle, compatibilidad, testing y comportamiento bajo carga.

7\. ¿Qué tan mantenible es?

Razonablemente mantenible, siempre que se estabilicen los contratos y se reduzca la deriva documental. La modularidad ayuda, pero el wiring y la gestión de versiones deben madurar.

8\. ¿Qué tan extensible es?

Tiene buena extensibilidad conceptual. La extensibilidad efectiva depende de que los contratos sean estables, los adapters estén bien aislados y las extensiones puedan cargarse y descargarse de forma coherente.

9\. ¿Qué tan preparado está para múltiples plataformas?

La separación de `spigot-host` y `fabric-host` es un buen comienzo. La compatibilidad completa todavía requiere validar semántica de eventos, threading, entrega y despliegue en ambas plataformas.

10\. ¿Qué tan preparado está para una red de Minecraft grande?

No se ha demostrado. La arquitectura contiene componentes adecuados para construir una solución network-scale, pero no hay evidencia suficiente de throughput, latencia p99, pérdida de mensajes o recuperación ante fallos a esa escala.

11\. ¿Qué tendría que cambiar para alcanzar escala network-level?

Corregir el trabajo síncrono en hilos críticos, validar el orden y lifecycle del manager, asegurar un empaquetado coherente, probar el SyncBus bajo saturación, definir garantías de entrega y ejecutar benchmarks reproducibles con varios servidores y sinks reales.

12\. ¿Qué partes no deberían tocarse sin una razón concreta?

No reescribiría `core-api`, el modelo de `Message`, la separación entre routing y formatting, ni el contrato `SyncSink` solo por estética. Primero hay que demostrar qué problema concreto existe y añadir pruebas que preserven el comportamiento correcto.

13\. ¿Qué debería hacerse primero?

Primero el orden de instalación de dependencias; después el bloqueo síncrono de Fabric; a continuación el artefacto Spigot y el runtime del manager. Esas correcciones tienen un impacto funcional más directo que refactors cosméticos.

14\. ¿Qué porcentaje del proyecto está técnicamente maduro?

Mi estimación provisional es alrededor del 60 %. La cifra no significa que el 40 % restante esté roto: representa la diferencia entre tener features y estructura implementadas y tener comportamiento, despliegue, seguridad y escalabilidad demostrados de forma reproducible.

La conclusión más importante es esta: TextFormatter Suite tiene una base arquitectónica prometedora, pero su madurez real depende ahora menos de añadir features y más de cerrar correctamente las integraciones críticas y demostrar el comportamiento del sistema mediante pruebas ejecutables.

Para convertir este informe en una auditoría verdaderamente exhaustiva según tu criterio original, la siguiente pasada debe completar la lectura de las clases restantes, verificar el grafo completo de dependencias, revisar individualmente los tests y ejecutar el build y las suites automatizadas. Hasta completar esa fase, no sería correcto presentar las puntuaciones ni la lista de hallazgos como definitivas.
