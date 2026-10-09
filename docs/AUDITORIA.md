# Auditoría técnica integral — TextFormatter Suite

Base de auditoría: archivo ZIP proporcionado · 9 de octubre de 2026

La auditoría se realizará sobre el contenido de `TextFormatter-Suite-main (1).zip`, no sobre una versión consultada de GitHub. El prompt de auditoría adjunto se utilizará como especificación del trabajo.

## 1. Cobertura de auditoría

Archivo del proyecto

`TextFormatter-Suite-main (1).zip`

1,9 MB comprimidos · 4,25 MB descomprimidos · 968 entradas

# 208

Archivos fuente Java en los módulos principales

# 29

Módulos declarados en Gradle

También hay código JavaScript, pruebas Java y JavaScript, documentación, configuraciones YAML/JSON, recursos, workflows y herramientas de carga y rendimiento.

### Limitaciones verificadas

* Código y configuración: el ZIP se ha descomprimido correctamente y se está examinando su contenido.

* Historial de Git: el archivo no contiene un directorio `.git` identificable en la raíz inspeccionada. No es posible atribuir cambios a commits concretos ni reconstruir con certeza la evolución histórica a partir de esta copia.

* Pruebas automatizadas: intenté ejecutar `./gradlew test`, pero el wrapper necesita descargar Gradle 8.10 y el entorno no pudo resolver `services.gradle.org`. Por tanto, todavía no hay resultados de ejecución que permitan afirmar que la compilación o las pruebas pasan o fallan.

* Rendimiento: las conclusiones de escalabilidad deberán distinguir entre propiedades deducibles del código y resultados que exigirían benchmarks reales.

No consideraré que una prueba falla por un defecto del proyecto cuando ni siquiera ha podido iniciarse. Tampoco atribuiré al ZIP un hash de commit que no pueda verificar.

## 2. Resumen ejecutivo preliminar

El proyecto presenta una separación modular considerable, con contratos compartidos, adaptadores para plataformas, motor de reglas, formateo, traducción y varias integraciones de sincronización. Sin embargo, el número de módulos y la presencia de interfaces no bastan para demostrar que todos los componentes funcionen correctamente en conjunto.

La distinción central de esta auditoría será entre tres niveles:

1. Arquitectura: si los límites, contratos y direcciones de dependencia son correctos.

2. Implementación: si el código respeta esos contratos en condiciones normales y de error.

3. Operación a escala: si los componentes mantienen sus garantías bajo concurrencia, saturación, fallos parciales y carga sostenida.

Ya hay un hallazgo importante que requiere atención: la implementación de `DefaultSyncBus` presenta un camino de difusión que encola el mismo mensaje tanto para su procesamiento global como directamente en cada destino. El procesador global vuelve a difundirlo. Esto permite duplicar entregas y, además, el camino directo elude la deduplicación central. También hay un problema independiente en `broadcastAsync`: no se comprueba el resultado de la inserción en la cola global, por lo que, si la cola permanece llena, los futuros pueden quedarse sin completar.

Estos son hallazgos derivados del código, no de suposiciones sobre cómo debería comportarse el sistema. Se documentan con mayor precisión en el informe.

## 3. Identificación de la versión auditada

Snapshot ZIP

TextFormatter Suite · rama indicada por el nombre del archivo: `main`

SHA-256 del archivo ZIP

41db873f2895b4c059cd2fd3d5408eed4a04a1a33e7e152c0c0e086bb3c39180

Versión declarada en Gradle: `2.1.0-SNAPSHOT`

El SHA-256 identifica el archivo comprimido, no el commit de Git que lo produjo.

# 4. Arquitectura real

## 4.1. Mapa de módulos

El proyecto declara 29 módulos Gradle. La estructura puede agruparse funcionalmente así:

Adaptadores de plataforma

`spigot-host` · `fabric-host`

Integración y orquestación

`host` · `MessageDispatcher` · `SuiteHost`

Motor de mensajes

`core-api`

`iflow`

`textformatter`

Servicios auxiliares

`gtranslate`

`ltranslate`

`messages`

Sincronización

`transport`

`sync-http` · `sync-tcpudp`

`sync-websocket` · `sync-bus`

`sync-discord` · `sync-telegram` · `sync-velocity`

Extensibilidad y operación

`kernel`

`extension-api`

`manager-api` · `manager-impl`

`observability` · `presets` · `inworld`

Esquema funcional derivado de los módulos y sus dependencias declaradas; no representa una afirmación de que todas las rutas de integración estén activas en ejecución.

También existen `common-legacy`, `example-extension`, `tester`, `loadtest` y `performance`. El editor web se encuentra en `src/web-editor`, pero no está incluido como subproyecto Gradle en `settings.gradle`.

## 4.2. Arquitectura pretendida frente a la implementada

La arquitectura pretendida utiliza contratos compartidos y adaptadores para separar el motor de las plataformas. Esa intención tiene soporte real en el código.

Hay tres observaciones importantes:

* `core-api` contiene modelos, interfaces SPI y contratos que pueden utilizarse sin depender directamente de Bukkit o Fabric.

* `kernel` contiene `ModuleLoader`, `ModuleGraph` y los tipos necesarios para descubrir y resolver descriptores de módulos.

* `host` concentra parte de la composición real del sistema y de la coordinación del procesamiento de mensajes.

La diferencia es que el descubrimiento de módulos no equivale a su activación.

`Module.java` declara explícitamente que cada módulo es un descriptor de dependencias, no un objeto con ciclo de vida. `ModuleLoader` utiliza `ServiceLoader` para descubrir esas implementaciones. No existe un contrato genérico de arranque y parada en esa interfaz.

Esto es coherente con el diseño documentado: los servicios se activan mediante sus entry points de plataforma.

### Defecto arquitectónico: dos rutas de bootstrap

Existen dos mecanismos que no deben confundirse:

* `SuiteBootstrap.bootstrap(...)`: carga configuración, descubre y resuelve descriptores mediante el kernel y construye un `SuiteHost`.

* `SuiteHost.bootstrap(...)`: realiza directamente la composición del motor, configura las reglas, crea el router, los evaluadores de expresiones y el formateador.

Los entry points de Spigot y Fabric encontrados llaman a `SuiteHost.bootstrap(...)`, no a `SuiteBootstrap.bootstrap(...)`.

Clasificación: deuda técnica y duplicación de composición, no necesariamente un bug funcional.

Impacto: un desarrollador que siga el mapa de código y asuma que el bootstrap basado en el kernel es la ruta operativa principal puede interpretar incorrectamente qué validaciones se ejecutan durante el arranque.

Recomendación: documentar una única ruta oficial de inicialización o explicar explícitamente qué responsabilidad tiene cada una. No fusionarlas automáticamente si cumplen propósitos distintos.

## 4.3. Pipeline de procesamiento de mensajes

El flujo principal reconstruido a partir de `SuiteHost`, `MessageDispatcher` y los módulos del motor es:

```
Evento de plataforma
        |
        v
Adaptador Spigot / Fabric
        |
        v
Construcción del Message
        |
        v
MessageDispatcher
        |
        +--> Resolver idioma de origen
        |
        +--> Comprobar rate limit de emisión
        |
        +--> Expandir Direction a destinatarios
        |
        v
Procesamiento por destinatario
        |
        +--> Resolver idioma del destinatario
        |
        +--> DefaultRouter / iFlow
        |       |
        |       +--> Reglas y permisos
        |       +--> Transformaciones
        |       +--> Rate limits de canal
        |       +--> Decisión de entrega
        |
        v
TextFormatter
        |
        +--> Contexto de plantilla
        +--> Placeholders / expresiones
        +--> Traducción según configuración
        +--> Renderizado
        |
        v
ChatDelivery
        |
        v
Entrega a la plataforma
```

Este es el pipeline de entrega principal. La sincronización hacia redes externas constituye otra ruta, que depende de los sinks registrados y de su integración con los adaptadores.

### Evaluación

La división entre enrutamiento, representación del mensaje, renderizado y entrega es una fortaleza. Evita que toda la lógica tenga que residir dentro de un listener de Bukkit o Fabric.

No obstante, `MessageDispatcher` realiza también coordinación de concurrencia, recolección de resultados, gestión de retardos, redirecciones y entrega. Esto lo convierte en un componente operacionalmente importante que merece pruebas específicas de concurrencia y ciclo de vida.

# 5. Clean Architecture y arquitectura hexagonal

Estas puntuaciones son evaluaciones estáticas del diseño, no resultados de pruebas de conformidad automatizadas.

| Criterio                        | Puntuación | Justificación                                                                                                                                           |
| ------------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Clean Architecture              | 7,5/10     | Los contratos y los modelos compartidos están razonablemente separados de las plataformas, pero la composición está repartida entre rutas de bootstrap. |
| Hexagonal Architecture          | 7,5/10     | Hay SPI útiles y adaptadores sustituibles, aunque la existencia de contratos no demuestra que todas las integraciones estén aisladas en la práctica.    |
| Modularidad                     | 7,5/10     | Hay límites por función y dependencias Gradle explícitas, con riesgo de duplicación entre módulos y servicios de infraestructura.                       |
| Separación de responsabilidades | 7/10       | La separación entre router, formatter y plataforma es buena; algunos componentes de coordinación acumulan responsabilidades operacionales.              |

## 5.1. Qué está realmente bien

* Los modelos de `core-api` no requieren directamente los tipos de Bukkit o Fabric.

* `ChatDelivery` proporciona un punto de extensión para la entrega.

* `TranslationService`, `SyncSink`, `ActorDirectory` y otros SPI permiten desacoplar consumidores de implementaciones concretas.

* Los módulos de traducción y sincronización tienen proyectos separados.

* Los entry points específicos de plataforma pueden encargarse de sus respectivos ciclos de vida.

## 5.2. Qué no debe darse por demostrado

La existencia de `ModuleGraph` no demuestra que el ciclo de vida de todos los módulos se gestione mediante ese grafo. De hecho, la ruta operativa de bootstrap encontrada utiliza composición manual.

Tampoco basta con que los adaptadores implementen interfaces para demostrar que las llamadas bloqueantes, el estado compartido o los errores estén correctamente aislados.

Conclusión arquitectónica: existe una base de diseño modular real. El principal reto es consolidar las garantías operacionales y hacer que las fronteras arquitectónicas declaradas coincidan consistentemente con las rutas de ejecución efectivas.

# 6. Hallazgos clasificados

Los siguientes son los defectos concretos más relevantes identificados en el análisis estático. No representan una lista exhaustiva de todos los problemas posibles del repositorio.

## TXF-ZIP-001 — Difusión duplicada en `DefaultSyncBus`

Alta · Confirmado por código

Tipo: Bug.

Ubicación: `src/sync-bus/src/main/java/me/majhrs16/suite/syncbus/DefaultSyncBus.java` → `broadcast()` y `processMessage()`.

Descripción: el mismo mensaje tiene dos caminos de encolado hacia los sinks.

La implementación hace lo siguiente:

1. Inserta una tarea global que llama a `processMessage(message)`.

2. Encola directamente el mensaje en cada `SinkContext`.

3. Cuando se procesa la tarea global, `processMessage()` vuelve a encolar el mensaje en todos los sinks.

La deduplicación global no protege el segundo camino porque el encolado directo no pasa por `processMessage()`.

Impacto funcional: si un sink está iniciado y tiene capacidad de cola, un único `broadcast()` puede producir dos entregas del mismo mensaje a ese sink.

Impacto en rendimiento: duplica trabajo de cola y procesamiento en el camino afectado, y puede aumentar la saturación.

Condiciones: invocar `broadcast()` con al menos un sink registrado y en funcionamiento, con capacidad suficiente para aceptar ambas inserciones.

Solución sugerida: escoger un único camino de difusión. El método debe delegar el fan-out al procesador global o hacerlo directamente, pero no ambos.

Ejemplo de corrección localizada:

Java

```
@Override
public int broadcast(Message message) {
    if (shuttingDown.get() || sinks.isEmpty()) {
        return 0;
    }

    try {
        boolean offered = globalQueue.offer(
            () -> processMessage(message),
            100,
            TimeUnit.MILLISECONDS
        );

        if (!offered) {
            totalDropped.incrementAndGet();
            return 0;
        }

        // El fan-out corresponde exclusivamente a processMessage().
        // No volver a llamar a ctx.enqueue(message) aquí.
        return sinks.size();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        totalDropped.incrementAndGet();
        return 0;
    }
}
```

Este ejemplo ilustra la eliminación de la doble difusión, pero la semántica del retorno requiere una decisión adicional: el número de sinks registrados no garantiza que todos hayan aceptado el mensaje. Si el contrato exige contabilizar las aceptaciones reales, conviene que el procesador global registre esos resultados y los exponga explícitamente.

Impacto de la solución: alto beneficio, baja complejidad y riesgo de regresión controlable con pruebas unitarias. Merece la pena resolverlo antes de usar `SyncBus` como mecanismo fiable de difusión.

## TXF-ZIP-002 — Futuros que pueden quedar incompletos en `broadcastAsync`

Alta · Confirmado por código

Tipo: Bug.

Ubicación: `DefaultSyncBus.java` → `broadcastAsync()`.

Evidencia: el resultado de `globalQueue.offer(...)` se ignora. Si la cola no acepta la tarea, `globalFuture` no se completa en esa rama.

Los futuros individuales dependen de la finalización de `globalFuture`. Por tanto, pueden permanecer pendientes indefinidamente.

Existe además otro defecto semántico: `waitForSinkProcessing()` considera suficiente que la cola del sink esté vacía. Sin embargo, el trabajador puede haber retirado ya el mensaje y estar procesándolo todavía. En ese caso, el futuro puede completarse antes de que termine la entrega real.

Impacto funcional: el consumidor no puede confiar en que los futuros indiquen finalización efectiva ni que terminen siempre.

Condiciones: saturación de la cola global para el primer problema; procesamiento todavía activo con cola vacía para el segundo.

Solución sugerida:

* Comprobar el resultado de `offer()` y completar los futuros excepcionalmente o aplicar una política explícita de rechazo.

* Asociar cada mensaje a un futuro de procesamiento por sink.

* Completarlo al terminar el envío y sus reintentos, no mediante sondeo del tamaño de la cola.

Evaluación: merece la pena corregirlo junto con TXF-ZIP-001. Complejidad media porque requiere definir correctamente el contrato de entrega y sus estados de error.

## TXF-ZIP-003 — La protección de conexiones HTTPS en `HttpTransport` necesita corrección

Alta · Defecto de implementación sustentado por código

Tipo: Bug probable de transporte; la reproducción experimental no está disponible en este entorno.

Ubicación: `src/transport/src/main/java/me/majhrs16/suite/transport/HttpTransport.java` → `createConnection()` y `PinningSSLSocketFactory`.

Evidencia: con la protección SSRF activada, `createConnection()` crea un `Proxy.Type.HTTP` cuya dirección es la IP resuelta del destino y cuyo puerto es el puerto del destino. Después utiliza ese proxy para abrir la conexión.

La dirección del servidor de destino no es, por sí misma, la dirección de un proxy HTTP. El establecimiento de un túnel HTTPS a través de un proxy y una conexión TCP directa a una IP fijada son operaciones diferentes.

La clase también contiene una fábrica TLS personalizada que crea sockets directamente hacia la IP fijada en algunos métodos, mientras que el método que envuelve un socket existente delega en el socket proporcionado. Esas rutas deben verificarse como un conjunto, no de manera aislada.

Impacto funcional: las solicitudes HTTP/HTTPS pueden fallar dependiendo de la ruta que utilice `HttpURLConnection` y del tipo de conexión. Como `HttpTransport` es utilizado por los proveedores de traducción y por integraciones de transporte, el problema puede propagarse a varias features.

Condiciones: solicitudes con protección SSRF habilitada, que es la configuración utilizada por defecto en los constructores habituales y por los proveedores de traducción examinados.

Solución sugerida: implementar la fijación de IP mediante una conexión directa al destino, preservando correctamente el hostname para TLS SNI y la verificación del certificado. No utilizar la IP de destino como si fuera un proxy HTTP.

La corrección debe incluir pruebas de integración con:

* HTTP ordinario.

* HTTPS con certificado válido.

* HTTPS con hostname incorrecto.

* Redirecciones entre hostnames.

* Destinos privados bloqueados.

* Hostnames con múltiples direcciones DNS.

Evaluación: merece la pena priorizarlo. La complejidad es media-alta porque la corrección de SSRF no debe eliminar la validación de destinos ni introducir una vulnerabilidad de DNS rebinding.

## TXF-ZIP-004 — Ciclo de vida incompleto de `DefaultSyncBus`

Media · Confirmado por código

Tipo: Defecto de diseño con consecuencias de recursos.

Ubicación: `DefaultSyncBus.java` → constructor, `start()`, `stop()` y `close()`.

Descripción: el constructor inicia el procesador global y programa la tarea periódica de limpieza de deduplicación antes de que se invoque `start()`.

Sin embargo, `stop()` retorna inmediatamente si `started` vale cero. Por tanto, `close()` no detiene esos recursos si se llama antes de un `start()` satisfactorio. La misma situación puede darse tras ciertos fallos de arranque, cuando el estado vuelve a cero.

Además, una instancia detenida no puede reiniciarse de forma coherente: el estado `shuttingDown` permanece activado y el scheduler se ha cerrado.

Impacto: hilos daemon y tareas auxiliares pueden sobrevivir a una inicialización abortada; el ciclo de vida no admite de forma clara una operación stop/start repetida.

Solución sugerida: hacer explícito el estado del ciclo de vida —por ejemplo, `NEW`, `RUNNING`, `STOPPING` y `STOPPED`— y garantizar que `close()` libere todos los recursos creados, incluso si `start()` nunca llegó a completarse.

Evaluación: complejidad media. Beneficio importante para reloads, pruebas y gestión de errores.

## TXF-ZIP-005 — Funcionalidades anunciadas que siguen sin implementación

Media · Confirmado por código

Tipo: Defecto funcional y deuda técnica.

Ubicaciones:

* `src/spigot-host/src/main/java/me/majhrs16/suite/spigothost/command/DynamicCommand.java`

* `src/host/src/main/java/me/majhrs16/suite/host/config/CommandExecutor.java`

* `src/presets/src/main/java/me/majhrs16/suite/presets/PresetManager.java`

Evidencia: se encontraron acciones de comandos que devuelven mensajes de «no implementado» para instalación, actualización, eliminación e información de módulos. También hay métodos de edición y lectura de configuración pendientes, y operaciones de importación/exportación de presets marcadas con TODO.

En `CommandExecutor.execute(...)`, la implementación actual devuelve `false` en lugar de ejecutar una acción.

Impacto: la presencia de un comando o API no garantiza que la funcionalidad correspondiente esté disponible.

Solución sugerida: distinguir en la documentación y en la configuración qué operaciones están implementadas, cuáles están deshabilitadas y cuáles son experimentales. No es necesario eliminar las abstracciones existentes.

Evaluación: prioridad media para funciones administrativas; mayor si una release presenta estas operaciones como funcionalidades disponibles.

## TXF-ZIP-006 — Autenticidad de módulos descargados no garantizada por defecto

Media · Riesgo de seguridad

Tipo: Defecto de diseño de seguridad; no se ha demostrado una explotación.

Ubicación: `src/manager-impl/src/main/java/me/majhrs16/suite/manager/DefaultModuleLifecycle.java` → `download()`, `verifyModuleSignature()` y `verifySignature()`.

Evidencia: el gestor verifica checksums SHA-256, pero la verificación de firma es opcional. Si el descriptor no proporciona configuración de firma, el código registra que omite la verificación. La implementación de `verifySignature()` también devuelve `true` cuando no encuentra los archivos de firma o clave.

Impacto: un checksum obtenido de la misma fuente que el artefacto protege contra corrupción accidental, pero no demuestra por sí solo la identidad del publicador. La autenticidad depende también de la confianza en el repositorio, su canal de distribución y la configuración de firma.

Solución sugerida: exigir firma verificable para módulos de repositorios no confiables o para releases de producción, con claves de confianza distribuidas por un canal independiente. Mantener los checksums para verificar integridad.

Evaluación: merece la pena si se pretende soportar extensiones de terceros o descargas de módulos en producción. Complejidad media; el riesgo de regresión principal es rechazar releases antiguas que todavía no estén firmadas.

# 7. Evaluación por feature

Las valoraciones siguientes se refieren al diseño y al código inspeccionado, no a una certificación de que cada feature funcione en todas las plataformas.

| Feature                               | Valoración | Observación principal                                                                                                                                |
| ------------------------------------- | ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `core-api`                            | 8/10       | Contratos y modelos compartidos útiles para la portabilidad.                                                                                         |
| `kernel`                              | 7/10       | Descubrimiento y resolución separados; falta integrar más claramente la validación con la ruta operativa de composición.                             |
| `textformatter`                       | 8/10       | Motor de plantillas y renderizado independiente de la plataforma, con atención a la seguridad de expresiones.                                        |
| `iflow`                               | 8/10       | Reglas, permisos, routing y rate limiting tienen una separación funcional razonable.                                                                 |
| `host`                                | 7/10       | Integra los componentes, pero concentra responsabilidades importantes y tiene dos rutas de bootstrap.                                                |
| Traducción                            | 7/10       | Proveedores intercambiables y cachés; la ruta HTTP requiere verificación antes de confiar en el comportamiento real.                                 |
| Sincronización HTTP/TCP/UDP/WebSocket | 6,5/10     | Hay abstracciones reutilizables, pero la corrección de los transportes y su semántica deben verificarse individualmente.                             |
| `sync-bus`                            | 4,5/10     | La duplicación en `broadcast()` y las garantías defectuosas de `broadcastAsync()` son problemas importantes.                                         |
| `sync-discord` / `sync-telegram`      | 7/10       | Integraciones específicas separadas; dependen de la fiabilidad del transporte y de su gestión de errores.                                            |
| `sync-velocity`                       | 7,5/10     | Tiene un módulo propio y configuración específica; no se han podido ejecutar sus pruebas en este entorno.                                            |
| `observability`                       | 7/10       | Métricas y endpoints aportan visibilidad, pero la seguridad y la configuración de exposición deben evaluarse en cada despliegue.                     |
| `manager-api` / `manager-impl`        | 6,5/10     | Descarga, resolución y carga aislada de módulos son capacidades potentes; el ciclo de actualización y la confianza de artefactos requieren atención. |
| `extension-api`                       | 7/10       | Facilita extensibilidad, aunque el ciclo de vida y la compatibilidad deben estar bien definidos.                                                     |
| `presets`                             | 6/10       | Es una vía útil para reutilizar configuraciones; la importación y exportación no están completamente implementadas.                                  |
| `spigot-host` / `fabric-host`         | 7/10       | La separación de adaptadores es positiva, pero se necesita integración real por plataforma.                                                          |
| Editor web                            | 7/10       | Modularizado en JavaScript, con pruebas unitarias e integración declaradas; falta ejecutar las verificaciones en este entorno.                       |
| Testing y benchmarks                  | 6,5/10     | Existe una base considerable de pruebas y módulos de carga, pero la cobertura efectiva y los resultados no han podido verificarse aquí.              |

## 7.1. Personalización

La arquitectura admite diferentes proveedores de traducción, sinks, resolutores de placeholders, reglas y configuraciones de canal. Esto favorece las extensiones sin modificar el motor central.

Las limitaciones identificadas son más concretas:

* Algunas acciones administrativas no están implementadas.

* Los presets no tienen todas las operaciones anunciadas terminadas.

* Los módulos de infraestructura no comparten necesariamente las mismas garantías de entrega.

* El soporte de múltiples plataformas está condicionado por las implementaciones específicas de cada adaptador.

No considero que las restricciones de configuración sean defectos simplemente por ser restrictivas. La validación debe impedir estados técnicamente imposibles o inseguros, no combinaciones arbitrarias que podrían funcionar correctamente.

## 7.2. Seguridad

La inspección encontró mecanismos positivos:

* Uso de `SafeConstructor` para cargar diversas configuraciones YAML.

* Un evaluador SpEL para plantillas basado en `SimpleEvaluationContext.forReadOnlyDataBinding()`.

* Comprobaciones de destinos y límites de respuesta en `HttpTransport`.

* Requisito de token para iniciar el servidor WebSocket.

* Límites de tamaño de mensajes y rate limiting por conexión en el WebSocket.

* Mecanismos de checksum y verificación de firma en el gestor de módulos.

Estos mecanismos no equivalen a una garantía global de seguridad. El transporte HTTP necesita la corrección indicada, y la autenticidad de los módulos descargados depende de la configuración de confianza.

En el editor web se observaron múltiples usos de `innerHTML`. En los fragmentos examinados, varias inserciones escapan los valores mediante `Suite.utils.esc(...)`. No he confirmado una vulnerabilidad XSS explotable a partir de esa observación aislada; haría falta rastrear los datos controlados por el usuario hasta cada contexto de renderizado.

# 8. Concurrencia y rendimiento

## 8.1. `MessageDispatcher`

El dispatcher utiliza un executor acotado con un máximo de 32 hilos y una cola de 1.000 tareas, además de un scheduler dedicado para retardos. También tiene una opción `engineParallel` que determina si los destinatarios se procesan en paralelo o secuencialmente.

Hay dos propiedades importantes:

* `CallerRunsPolicy` proporciona contrapresión cuando el executor está saturado, pero puede trasladar trabajo al hilo que intenta enviar la tarea.

* El timeout de diez segundos limita cuánto espera el dispatcher por un destinatario, pero cancelar un `Future` no garantiza que una operación bloqueante o un proveedor externo interrumpa inmediatamente su trabajo.

Por ello, es necesario comprobar que el código invocado por el dispatcher no ejecute operaciones bloqueantes en el hilo principal de la plataforma.

## 8.2. `SyncBus`

La capacidad nominal de las colas es considerable:

* Cola global: 10.000 tareas.

* Cola por sink: 5.000 tareas.

* Ventana de deduplicación: 60 segundos.

* Límite de entradas de deduplicación: 50.000.

Esos valores no demuestran throughput ni latencia. La duplicación de entregas en `broadcast()` puede aumentar el consumo de las colas y acelerar la saturación.

Además, el executor global y su procesador manual comparten la misma cola. La interacción entre los trabajadores internos del executor y el bucle que extrae tareas directamente merece una simplificación: una única estrategia de consumo facilita razonar sobre orden, contrapresión y finalización.

## 8.3. Estimaciones de escalabilidad

No hay mediciones válidas obtenidas en esta auditoría. La siguiente tabla expresa riesgos arquitectónicos, no capacidad medida.

| Escenario            | Cuello de botella probable                                                     | Evaluación                                                               |
| -------------------- | ------------------------------------------------------------------------------ | ------------------------------------------------------------------------ |
| A — Servidor pequeño | Coste por mensaje, traducción y renderizado                                    | Viable en principio, sujeto a la corrección de las rutas de ejecución.   |
| B — Red mediana      | Fan-out, latencia de traducción, transporte y colas                            | Requiere pruebas de carga y de fallos parciales.                         |
| C — Red grande       | Sinks lentos, presión de colas, sincronización entre servidores y GC           | No se puede garantizar con el estado actual.                             |
| D — Network-scale    | Contrapresión distribuida, p99, reconexiones, entrega y recuperación de fallos | No demostrado; la implementación actual del bus debe corregirse primero. |

Para validar una red grande se necesitan, como mínimo:

* Mensajes por segundo y distribución de tamaños.

* Número de destinatarios por mensaje.

* Latencia p50, p95 y p99.

* Profundidad de colas, descartes y reintentos.

* Tiempo y tasa de éxito de traducciones.

* Consumo de CPU, heap y pausas de GC.

* Pruebas de saturación, desconexión y recuperación de sinks.

* Medición de duplicados y pérdida de mensajes.

No asigno cifras de throughput porque no hay resultados experimentales que las justifiquen.

# 9. Testing, build y evolución

## 9.1. Inventario de pruebas

En el ZIP se encontraron:

* 56 archivos dentro de rutas de pruebas.

* 49 archivos Java de pruebas.

* 10 archivos de pruebas JavaScript bajo `src/web-editor/tests`.

Hay pruebas para el kernel, el formateador, iFlow, traducción, configuración, transporte, sinks y pipeline de extremo a extremo, además de una simulación de carga.

La existencia de esas pruebas es una fortaleza. Sin embargo, no pude ejecutar el conjunto Java porque el wrapper no pudo descargar Gradle. Las pruebas del editor tampoco arrancaron: faltaba la dependencia `jsdom`.

Por tanto, no se dispone de un resultado de ejecución que confirme o refute las pruebas actuales.

## 9.2. Cobertura funcional que conviene reforzar

Priorizaría las siguientes pruebas:

1. `DefaultSyncBus.broadcast()` entrega una única copia por sink.

2. `broadcastAsync()` completa todos los futuros incluso cuando la cola está llena.

3. Los futuros de entrega no terminan antes de que el sink complete su trabajo.

4. `close()` libera recursos si el bus nunca se inició.

5. El ciclo de vida define qué ocurre al intentar reiniciar una instancia detenida.

6. `HttpTransport` funciona correctamente con HTTPS y rechaza destinos privados.

7. Las redirecciones vuelven a validar el destino efectivo.

8. El dispatcher mantiene un comportamiento definido cuando se cancela una operación bloqueante.

9. La composición de Spigot y Fabric realiza el pipeline completo de chat hasta entrega.

10. Las funcionalidades administrativas no implementadas están reflejadas correctamente en la documentación y los tests.

## 9.3. Historial de Git

El ZIP no contiene el historial necesario para realizar una evaluación fiable de la evolución arquitectónica.

Los documentos incluyen fechas, referencias a auditorías anteriores y menciones a commits, pero eso no permite reconstruir por sí solo:

* qué cambio introdujo cada defecto;

* si un refactor eliminó deuda o la trasladó;

* cuánto tiempo permaneció una regresión;

* si las correcciones se integraron en el commit que corresponde a este ZIP.

La evaluación de la evolución histórica queda pendiente de una copia con metadatos de Git o un conjunto de commits verificable.

# 10. Matriz final de puntuaciones

Las notas resumen el estado del código inspeccionado y están limitadas por la imposibilidad de ejecutar el conjunto completo.

| Área                            | Puntuación /10 | Estado                                                                      |
| ------------------------------- | -------------- | --------------------------------------------------------------------------- |
| Código                          | 7,5            | Buena base, con defectos operacionales importantes                          |
| Arquitectura                    | 7,5            | Separación modular real                                                     |
| Clean Architecture              | 7,5            | Límites razonables, composición no completamente consolidada                |
| Hexagonal                       | 7,5            | SPI útiles; aislamiento efectivo por validar                                |
| Modularidad                     | 7,5            | Amplia, pero con complejidad de integración                                 |
| Separación de responsabilidades | 7              | Buena en el motor; más compleja en orquestación                             |
| Legibilidad                     | 7              | Documentación extensa, navegación mejorable por duplicación de rutas        |
| Features                        | 7              | Amplia funcionalidad, algunas operaciones incompletas                       |
| Personalización                 | 7,5            | Buen potencial de extensión                                                 |
| Seguridad                       | 6,5            | Buenas medidas parciales; transporte y autenticidad necesitan atención      |
| Rendimiento                     | 6,5            | Algunas medidas de control, sin validación experimental                     |
| Escalabilidad                   | 5,5            | Potencial arquitectónico, garantías de runtime insuficientes                |
| Concurrencia                    | 6              | Executors acotados, pero problemas de sincronización y ciclo de vida        |
| Testing                         | 6,5            | Base relevante; ejecución y cobertura efectiva sin verificar                |
| Mantenibilidad                  | 7              | Buena separación, deuda de composición e integración                        |
| Documentación                   | 7,5            | Muy extensa; parte del estado declarado necesita contraste con la ejecución |

Valoración global estática: 7/10.

No es una media matemática estricta. Es una valoración cualitativa que pondera especialmente corrección, arquitectura, seguridad y fiabilidad operacional.

# 11. Potencial y madurez

## Potencial teórico

Alto. La combinación de un core compartido, adaptadores de plataforma, proveedores intercambiables, reglas y múltiples mecanismos de sincronización es adecuada para un sistema extensible de chat.

## Potencial arquitectónico

También alto, aunque inferior al teórico. La arquitectura ya proporciona buena parte de las fronteras necesarias para evolucionar hacia una red de servidores, pero los componentes de sincronización, transporte y ciclo de vida tienen que respetar garantías más fuertes.

## Potencial materializado

Estimación cualitativa: aproximadamente el 65–75 % del potencial arquitectónico está materializado en la estructura y las funcionalidades presentes. No es una métrica objetiva de progreso, ni una estimación de porcentaje de código terminado.

La diferencia restante no se resuelve añadiendo módulos. Se resuelve haciendo que las funcionalidades existentes tengan contratos operacionales verificables, pruebas de integración y comportamiento predecible bajo fallos.

# 12. Roadmap priorizado

| Prioridad | Trabajo                                                        | Esfuerzo aproximado | Beneficio                                                     |
| --------- | -------------------------------------------------------------- | ------------------- | ------------------------------------------------------------- |
| P0        | Corregir la doble difusión de `DefaultSyncBus.broadcast()`     | Bajo                | Elimina duplicados en una ruta crítica                        |
| P0        | Corregir la finalización y la semántica de `broadcastAsync()`  | Medio               | Hace fiables los contratos asíncronos                         |
| P0        | Corregir y probar la conexión fijada por IP en `HttpTransport` | Medio-alto          | Recupera fiabilidad HTTP/HTTPS y mantiene la protección SSRF  |
| P1        | Consolidar el ciclo de vida de `DefaultSyncBus`                | Medio               | Evita recursos huérfanos y estados de reinicio inconsistentes |
| P1        | Ejecutar las pruebas Java y web en CI y añadir regresiones     | Medio               | Verifica los fixes y evita recurrencias                       |
| P1        | Endurecer la verificación de firmas del gestor de módulos      | Medio               | Mejora la autenticidad de extensiones                         |
| P2        | Consolidar/documentar la ruta de bootstrap oficial             | Bajo-medio          | Reduce confusión arquitectónica                               |
| P2        | Completar las acciones administrativas y presets anunciados    | Medio-alto          | Alinea la funcionalidad con las interfaces expuestas          |
| P2        | Añadir benchmarks reproducibles y escenarios de fallo          | Medio-alto          | Permite justificar la escalabilidad                           |
| P3        | Mejorar el onboarding y reducir contradicciones documentales   | Bajo-medio          | Facilita contribuciones y mantenimiento                       |

Los esfuerzos son estimaciones de planificación, no tiempos medidos. No recomiendo una reescritura completa: los problemas prioritarios identificados admiten correcciones localizadas.

# 13. Conclusión final

1. ¿Qué tan bueno es realmente TextFormatter Suite? Es un proyecto modular con una base arquitectónica sólida y una amplitud funcional significativa, pero no está demostrado que todas sus integraciones cumplan sus contratos operacionales.

2. ¿Qué partes están excepcionalmente bien diseñadas? La separación entre modelos compartidos, router, formateador y adaptadores de plataforma; los SPI para servicios intercambiables; y la intención de aislar la infraestructura.

3. ¿Qué partes son mediocres? La integración entre algunos módulos, la consolidación de la composición y determinadas funcionalidades administrativas incompletas.

4. ¿Cuáles son sus peores defectos? La duplicación en `DefaultSyncBus.broadcast()`, los futuros poco fiables de `broadcastAsync()` y los problemas de diseño de la conexión fijada por IP en `HttpTransport`.

5. ¿Cuál es su mayor fortaleza arquitectónica? La capacidad de separar el motor del entorno de ejecución y de sustituir proveedores o adaptadores sin rehacer toda la lógica.

6. ¿Cuál es su mayor riesgo futuro? Confundir una arquitectura preparada para extensibilidad con un runtime que ya garantiza entrega, seguridad, orden y recuperación bajo carga.

7. ¿Qué tan mantenible es? Razonablemente mantenible, siempre que se consoliden las rutas de inicialización y se documenten los contratos de concurrencia y ciclo de vida.

8. ¿Qué tan extensible es? Potencialmente muy extensible gracias a sus SPI y módulos separados; la calidad de cada nueva integración dependerá de las garantías de su implementación.

9. ¿Qué tan preparado está para múltiples plataformas? La estructura está diseñada para ello y existen adaptadores Spigot y Fabric. La paridad funcional y el comportamiento real de ambos requieren pruebas de integración.

10. ¿Qué tan preparado está para una red grande de Minecraft? No puede considerarse demostrado. Hay que corregir primero la semántica del bus y del transporte, y después medir el comportamiento bajo carga.

11. ¿Qué tendría que cambiar para alcanzar escala network-level? Garantías de entrega claras, contrapresión efectiva, transporte fiable, deduplicación coherente, recuperación ante fallos, métricas y pruebas de carga con percentiles de latencia.

12. ¿Qué partes no deberían tocarse sin una razón concreta? Los contratos centrales de `core-api`, la separación entre routing y rendering, y los SPI que mantienen el core independiente de la plataforma. Los fixes deberían preservar estas fronteras.

13. ¿Qué debería hacerse primero? Corregir los tres hallazgos P0 y añadir pruebas de regresión antes de introducir nuevas funcionalidades de infraestructura.

14. ¿Qué porcentaje aproximado del proyecto está técnicamente maduro? Mi estimación cualitativa es del 65–75 % del potencial arquitectónico, con confianza limitada porque no ha sido posible ejecutar la suite completa ni recuperar el historial de Git.

La conclusión general es que TextFormatter Suite tiene una arquitectura que merece consolidarse, no sustituirse. El esfuerzo de mayor valor inmediato no consiste en añadir más capas o interfaces, sino en cerrar la brecha entre los contratos declarados y el comportamiento real de las rutas críticas de ejecución.

Nota de alcance: este informe es una auditoría estática basada en el ZIP y en las verificaciones que el entorno permitió realizar. No equivale a una auditoría experimental completa ni a una validación de producción.
