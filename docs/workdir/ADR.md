# ADR — TextFormatter Suite v1.0 (pre-release)

Fecha: 2026-08-14 · Estado: aceptado (pre-release, sin versión estable) · Última actualización: 2026-11-23

> **Documento vivo:** se actualiza en el mismo cambio que introduce una decisión de arquitectura.
> Solo conserva decisiones vigentes; el historial cerrado, las fases y el estado operativo viven en
> `PLAN.md` y en Git. Este documento no mantiene roadmaps, tablas de estado ni registros de
> implementación.
>
> **Régimen pre-1.0:** ver ADR-034. `1.0.0` es la etiqueta de release de la base integrada; el
> betatesting/E2E real se ejecuta después y sus fallos se corrigen en la línea beta.

## Contexto

Reemplaza el ChatTranslator monolítico (v4). ChatTranslator pasa a significar solo los traductores
(`GTranslate` y `LTranslate`). El conjunto completo de módulos que arma todo lo que era
ChatTranslator original + mucho mayor potencial se llama **TextFormatter Suite**.

## Nombre

- **Suite**: TextFormatter Suite (paraguas, el conjunto de módulos).
- **ChatTranslator**: retirado como marca global; queda únicamente para los traductores
  GTranslate/LTranslate.
- Renombrado en código (respecto a v4): `FormatGroups` → `Channels`, `FormatApplier` →
  `ChannelApplier`, `FormatCatalog` → `ChannelCatalog`, `lastFormatPath` → `channel`.

## Principios (no negociables)

- Módulos `.jar` independientes, **repositorios git dedicados** por módulo (no subrepos/submódulos).
  Cada módulo es un **plugin/mod real** de la plataforma (Spigot o Fabric) por derecho propio.
- **Nada de modloader**: sin ciclo de vida gestionado (`load→enable→disable`), sin manifiesto
  custom, sin reflexión de métodos privados. Solo `ServiceLoader`/SPI.
- **Handshake doble, base mínima de contrato**:
  - Versión de la JVM (runtime): un módulo compilado contra X exige JVM ≥ Y.
  - Versión del contrato SPI (compilación): todos los artefactos `*-api` comparten la base `1.0.0`
    y el requisito se resuelve con `>= 1.0.0`; un mismatch por debajo de la base no se degrada con
    un adaptador. El runtime rechaza el módulo y exige actualizar el contrato y sus consumidores en
    el mismo cambio.
- **Kill switch limpio**: se rompe con la config v1.8 y con los tipos legacy
  `ChatMessage`/`ChatMessageType`. `CoreTranslator` se conserva como módulo legacy independiente
  (ADR-035), no como puente interno permanente: los consumidores nuevos usan el contrato vigente.
- **Permisos por canal: ambos métodos a la vez.** Base = un único permiso `cht.<channel>`
  (suscripción; poseerlo = estar adscrito al canal). Encima, `send-permission` /
  `receive-permission` opcionales para la asimetría nativa (p.ej. "todos leen, solo staff escribe").
  Default ACCEPT si no se define nada. Filosofía: cuando haya duda, se implementa la configuración
  extra, nunca se quita control. El emisor se ve a sí mismo como parte del tail de emisor, sin
  permiso extra.

## Contratos SPI (lo que cada módulo implementa/publica)

- `Translator` → GTranslate / LTranslate (herederos del nombre ChatTranslator).
- `ChannelFormatter` → módulo TextFormatter (motor `core/template` + Channels).
- `Router` → hub iFlow y sus backends.
- `SyncSink` → módulos de borde: Discord / Telegram / HTTP / TCP-UDP.
- `ChannelRegistry` → índice centralizado (storage separado del formato).
- `Message`: dirección-as-audiencia (`Direction`), `languages()` por mensaje
  (`source`/`target`/`none`) sobreescribiendo el default del canal.

## Modelo Channel

- Destino nombrado con base `permission: cht.<channel>` (suscripción) más
  `send-permission`/`receive-permission` opcionales (asimetría nativa); default ACCEPT. La
  asimetría también puede vivir en reglas de iFlow. Suscripción ≡ poseer el permiso base.
- Tail: textos MiniMessage, tooltips, sonidos, `languages` default, `rate-limit` (ancho de banda
  por seg; no algoritmo CAKE).
- Estructura por archivo (`channels/chat.yml`, `channels/private.yml`, `channels/discord.yml`, …).

## iFlow

Hub de routing/firewall con default deny. La política v3 entrega con `DELIVER`, cancela el evento
con `CANCEL_EVENT` o falla con `ERROR` (ADR-031). Los motores `iflow-firewall`, `iflow-16`,
`iflow-32` e `iflow-wasm` son backends seleccionables por el hub (ADR-032); no son targets de
política. `iflow-16` e `iflow-32` son líneas separadas (ADR-038).

## Fases y estado

El plan de fases y el estado operativo viven exclusivamente en `PLAN.md`. Este ADR no mantiene
roadmaps, tablas de estado ni historial de fases.

## Consecuencias

- Los módulos publican a Maven (local o remoto) y conservan identidad de paquete separada, pero
  todos comparten la base de versión `1.0.0` durante el régimen pre-1.0; no hay matriz de
  compatibilidad entre versiones ficticias.
- El host de cada plataforma es mínimo (bootstrap + ServiceLoader/SPI).
- La migración desde v4 es un corte limpio, no incremental: v4 queda solo como referencia histórica
  y no se conservan adaptadores ni tipos legacy para convivir con él. Los módulos legacy
  independientes se declaran explícitamente (ADR-001 y ADR-035).

---

## ADR-001 — SpEL solo en `iflow-firewall`

**Estado:** Aceptada (2026-10-20; enmendada el 2026-10-27).

**Decisión.** SpEL no forma parte de `textformatter`, presets, `<expr>`, el editor web ni del
contrato de la VM. La única superficie ejecutable de políticas es iFlow. El motor
`iflow-firewall` conserva SpEL como implementación legacy independiente, con sandbox, allowlist de
clases, caché LRU acotada (1024) y `spring-expression` solo en su classpath. `iflow-16`, `iflow-32`
e `iflow-wasm` no usan SpEL. `rules.yml` acepta el esquema de reglas del motor legacy; el grafo del
editor se valida pero no se ejecuta. Cualquier política nueva debe implementarse en la VM o en Wasm.

**Consecuencias.** Una sola superficie de políticas por motor; `textformatter` no depende de Spring;
los consumidores nuevos usan el contrato vigente y no el motor legacy.

---

## ADR-002 — Puertos de entrega y despacho neutral

**Estado:** Aceptada. El punto 3 queda superado por ADR-036 §9: `MessageDispatcher` se sustituye
por `PipelineEngine` + `RecipientResolver` + `DeliveryExecutor`. El resto sigue vigente.

**Decisión.**

1. `core-api` publica `ActorDirectory`: vista de solo lectura de la población conectada
   (`onlinePlayers/byUuid/byName/console`) con métodos *default* para mundo y radio.
2. `ChatDelivery` vive en `core-render`, no en `core-api`, porque su contrato usa Adventure
   `Component`.
3. `MessageDispatcher` vive en el host y es síncrono y thread-agnóstico. Expande `Direction`,
   deduplica por identidad de `Actor`, entrega por receptor y devuelve un `DispatchReport`.
4. La semántica de targets es la de ADR-031: `DELIVER`, `CANCEL_EVENT` y `ERROR`. No se conservan
   `REDIRECT` ni `CHANNEL_REDIRECT` como targets; la consola es un canal/actor y el cambio de canal
   se hace con `SET_CHANNEL` en una segunda fase de routing, sin reentrega recursiva.
5. El hilo lo elige el adapter; la entrega salta a main thread dentro de cada implementación de
   `ChatDelivery`.

**Consecuencias.** El adapter solo implementa `ActorDirectory` y `ChatDelivery`; el despacho no
depende de tipos de feature. Cualquier código que aún procese `CHANNEL_REDIRECT` es deuda de
alineación con ADR-031 y se corrige en el rediseño del pipeline.

---

## ADR-003 — Invariantes de seguridad y verificación

**Estado:** Aceptada.

**Invariantes vigentes.**

1. El registro de módulos es SPI-only: no se instancia un `Module` por reflexión. La validación de
   manifiesto es obligatoria y falla explícitamente si falta o no coincide.
2. SHA-256 es obligatorio para artefactos descargados; un asset sin hash se rechaza. La caché se
   verifica antes de reutilizar cualquier artefacto.
3. `verification-metadata.xml` y `dependencyLocking` cubren las dependencias; `checkLocks` forma
   parte del gate.
4. Los tokens sensibles se guardan en `char[]` y se limpian con `Arrays.fill('\0')` tras su uso.
5. `MiniEscape` escapa `< > \ { } [ ] ( ) # @`.
6. `HttpTransport` aplica protección SSRF: rechaza rangos privados, loopback, link-local, multicast
   y patrones denegados configurables; resuelve por `getAllByName()` y no confía en el primer
   registro.
7. Los loaders YAML usan `SafeConstructor`.
8. TLS + SHA-256 detectan corrupción y manipulación de transporte, no prueban autoría. La firma de
   paquetes, el pinning y los certificados quedan fuera de esta fase (ADR-029).
9. `library/status` registra solo instalación; el estado de ejecución vive en
   `runtime/modules.status` y nunca se mezcla con el inventario.

**Consecuencias.** La seguridad se apoya en el sandbox por módulo y en la responsabilidad del dueño
del repositorio; las allowlists y firmas son defensa en profundidad opcional, no el fundamento.

---

## ADR-004 — Modelo de ejecución de la VM de iFlow

**Estado:** Aceptada (2026-10-14).

**Decisión.** El modelo «CPU / banco de registros / bus / firmware» de la especificación es un
modelo mental, no un mapa de clases. La VM se implementa como intérprete con contexto de ejecución
reutilizable (`VmInterpreter` + `ExecutionContext` + *fuel budget*) y el bus como `VmBus`; no se
crean paquetes `cpu/`, `alu/` ni `bus/`. El boceto histórico de `ControlUnit`/`Cpu` no se importa.
La contabilidad de ciclos/CPI no existe y, si se necesita, se añade como capacidad nueva.

**Consecuencias.** Un único lugar para el ciclo y el despacho de IRQ; contexto pooled sin
asignaciones por ejecución; el *fuel budget* acota la ejecución. La separación de responsabilidades
se lee dentro del intérprete, no como estructura de paquetes.

---

## ADR-005 — La MMIO de dispositivos no la gobierna la PageTable

**Estado:** Aceptada (2026-10-19).

**Ámbito:** esta ventana MMIO rige `iflow-16`. `iflow-32` se rige por ADR-037.

**Decisión.** La apertura MMIO de dispositivos (256 B en `0x0F00–0x0FFF`) es espacio de
dispositivo, no memoria: no se traduce ni se comprueba por privilegio, igual que la MMIO real. La
protección por propiedad de página se mantiene para la memoria (ROM, constantes). El programa puede
escribir los registros de pantalla, PIC y su propia NVRAM; no hay escape al host porque el
periférico vive en el contenedor del programa. Un test fija que un maestro `PROGRAM` recibe
`PRIVILEGE_FAULT` al escribir RAM de la página kernel R/X pero sí configura la pantalla por la
apertura.

**Consecuencias.** El contrato queda escrito en `VmMemoryMap.DEVICE_MMIO_BASE` y referenciado desde
`VmBus`/`VmDevice`; no se lee como un bypass accidental. Si en el futuro se aísla la NVRAM en el
dominio supervisor, este ADR se enmienda.

---

## ADR-008 — `chattranslator` (fachada) y `translator` (proveedor)

**Estado:** Aceptada (2026-10-27).

**Decisión.** Se mantienen dos capabilities separadas: `chattranslator` publica la fachada y
`translator` identifica a los proveedores. La fachada se activa sin backends y el pipeline degrada
a no-op; los proveedores declaran que requieren la fachada. La dirección es **proveedor requiere
fachada**, nunca al revés. `TranslationService` no se usa como fallback automático: el camino
vigente es `ChatTranslator`. La capability se ata a la base `1.0.0`; no se versiona de forma
independiente.

**Consecuencias.** La traducción es opcional y el pipeline arranca sin proveedores. La nomenclatura
exige documentación explícita; si en el futuro se quisiera exigir al menos un backend, el modelo de
`Requirement` no expresa «opcional» y habría que enmendarlo.

---

## ADR-019 — Reestructuración modular: runtime, ciclo de vida y adaptadores delgados

**Estado:** Aceptada.

**Decisión del usuario (2026-10-27).** No es un arreglo de dependencias: es una reestructuración de
la arquitectura. El objetivo es hot unload/upgrade real. La solución no es empezar por el loader
manteniendo la suite actual: hay que romper la suite, desarmar todo, reestructurar lo que debe ir y
lo que no, y volver a armarla como debería haber sido desde el principio. No se aceptan
implementaciones a medias, stubs, no-ops, adaptadores de compatibilidad sin fecha de eliminación ni
parches que preserven el acoplamiento actual. El Manager es el origen de la composición: actualiza
índices, descarga paquetes, resuelve dependencias, instala, inicializa en memoria y gestiona el
ciclo de vida. Spigot y Fabric no conocen ningún módulo de feature; solo implementan una API común
entre plataformas y adaptan, en reversa, los eventos de su Minecraft a una representación interna
agnóstica.

### Principios no negociables

1. **El core no conoce features.** `core-api`, `kernel`, `manager-*` y `suite-runtime` no importan
   `sync-*`, `textformatter`, `iflow-*`, `chattranslator`, `gtranslate`, `ltranslate`,
   `observability`, etc.
2. **Los adapters solo conocen la plataforma y la API.** `spigot-host` y `fabric-host` son
   bootstrap y dependen de `core-api` + `suite-runtime` + SDK de plataforma. La captura de eventos
   vive en `sync-bukkit`/`sync-fabric`, que además publican el sink local `bukkit`/`fabric`
   (ADR-036 §17).
3. **El Manager es el orquestador.** Instalar, activar, desactivar, actualizar y reparar pasan por
   el Manager.
4. **Instalar no es activar.** El estado instalado y el estado activo se registran por separado;
   una activación fallida no se marca como éxito.
5. **El ciclo de vida es obligatorio para hot unload/upgrade.** Sin `start/stop` y liberación de
   recursos, no se promete hot reload.
6. **No hay doble camino.** Cuando una feature se migra al runtime, se elimina su cableado
   compilado en el mismo cambio. No se mantienen dos formas de arrancar.
7. **No hay stubs silenciosos.** Lo no soportado lanza un error explícito o se marca como
   `RESTART_REQUIRED`; nunca se devuelve `parent`, `false` o vacío fingiendo éxito.
8. **La arquitectura se verifica.** Un guard de dependencias/imports falla el build si aparece una
   violación.

### Principios adicionales

- **`force` significa forzar de verdad.** No omite conflictos de forma silenciosa: permite la
  instalación de paquetes excluyentes si el administrador lo decide, con advertencia.
- **Las librerías compartidas son dependencias, no parte del host.** Se empaquetan como
  `DEPENDENCY`, se relocalizan y se cargan por módulo; no se compilan dentro del JAR del host ni de
  los módulos.
- **Los metadatos autoritativos vienen del repositorio, no del JAR.** El manager consulta
  `packages.list`; el `METADATA/package.list` del ZIP se valida contra el índice.
- **`library/status` es una BD de programas instalados, no de ejecución.** La presencia de un
  paquete implica que está instalado y debe iniciarse; el estado de ejecución vive en
  `runtime/modules.status`.
- **`tester` es un módulo de producción.** Benchmarking en runtime y traza del pipeline completo;
  no es harness ni herramienta de desarrollo excluida.
- **Versiones concurrentes al estilo Debian.** La versión nueva reemplaza a la anterior resolviendo
  el grafo; durante un upgrade pueden solaparse v1 y v2 solo hasta confirmar el swap.
- **La captura de eventos de plataforma es un sincronizador descargable.** `sync-bukkit` y
  `sync-fabric` registran listeners, traducen a `Message`, despachan y publican el sink local
  `bukkit`/`fabric`; el host no los embebe (ADR-036 §17).

### Contrato de módulo

`Module` se conserva como descriptor. Se añade un contrato de activación separado:

```java
public interface ModuleActivator {
    ModuleDescriptor descriptor();
    void start(ModuleContext context) throws Exception;
    void stop() throws Exception;
}
```

El JAR declara `META-INF/services/me.majhrs16.suite.api.ModuleActivator` y el `ModuleHost` lo
descubre con `ServiceLoader.load(ModuleActivator.class, moduleClassLoader)`.

`ModuleContext` no expone Bukkit ni Fabric:

```java
public interface ModuleContext extends AutoCloseable {
    String moduleId();
    Path configDir();
    PluginLogger logger();
    ServiceRegistry services();
    EventBus events();
    Scheduler scheduler();
    ResourceTracker resources();
    PlatformInfo platform();
}
```

`ServiceRegistry` sustituye a `ServiceLoader` para servicios vivos:

```java
public interface ServiceRegistry {
    <T> Registration register(Class<T> type, T service);
    <T> Optional<T> find(Class<T> type);
    <T> List<T> findAll(Class<T> type);

    interface Registration extends AutoCloseable {
        void close();
    }
}
```

Cada registro queda asociado al `moduleId`; al desactivar el módulo se cierran sus registros, se
cancelan sus tareas y se liberan sus recursos.

### `ModuleHost`: carga, activación y hot upgrade

```java
public final class ModuleHost implements AutoCloseable {
    public List<ActivationResult> loadInstalled();
    public ActivationResult activate(Path jarPath);
    public UpgradeResult upgrade(String id, Path newJar, List<Path> dependencyJars);
    public DeactivationResult deactivate(String id);
    public Map<String, ModuleState> snapshot();
}
```

Estados: `INSTALLED`, `ACTIVATION_PENDING`, `ACTIVE`, `FAILED`, `ACTIVATION_FAILED`,
`RESTART_REQUIRED`, `REMOVED`.

Reglas del loader:

- Un `ModuleClassLoader` por módulo, con parent común de API.
- Parent-first solo para `me.majhrs16.suite.api.*` y `java.*`.
- Los contratos que cruzan paquetes viven en `me.majhrs16.suite.api.*`; una feature no expone sus
  tipos propios a otra feature.
- Child-first para las clases de implementación del módulo.
- El classloader recibe primero el JAR del módulo y después los JARs de los paquetes de tipo
  dependencia; un paquete `PACKAGE` no se añade al classloader de otro módulo.
- Las librerías compartidas no viven en el host: el manager las entrega como paquetes de
  dependencia relocalizados y el classloader del módulo las resuelve desde el scope asignado.
- Acceso a dependencias desde `library/dependencies`; un JAR fuera de ese directorio se rechaza.
- Cierre del classloader al desactivar. Si no puede cerrarse o quedan hilos/listeners, el estado
  pasa a `RESTART_REQUIRED`; no se marca como desactivado limpio.

Secuencia de activación:

1. Recibir del manager las entradas instaladas; `library/status` es la fuente de qué está instalado.
2. Resolver orden y dependencias con `kernel.ModuleGraph` y `DependencyResolver`.
3. Crear classloader y alcance de servicios.
4. Cargar `ModuleActivator`.
5. Validar descriptor, versión de API, plataforma y capacidades.
6. Crear `ModuleContext`.
7. Ejecutar `start()`.
8. Si falla: cerrar registros, ejecutar `stop()` de limpieza, cerrar classloader y marcar `FAILED`.
9. Si funciona: confirmar servicios y marcar `ACTIVE`.

La instalación y la desinstalación siguen las tres etapas de ADR-027. La desactivación efectiva de
una desinstalación es una barrera al inicio de la etapa 2; si un módulo no se detiene limpio, no se
toca ningún archivo. Un reemplazo por `Exclude` usa la misma barrera.

Invariante de paquete: un `PACKAGE` contiene uno o más JARs de módulo, cada uno con un `moduleId`
único declarado por su descriptor. El nombre del paquete no se usa como id de módulo. Las librerías
adicionales van en `DEPENDENCY`. `PackageRuntime` inspecciona todos los JARs antes de activar o
actualizar; cero JARs o ids duplicados son un error explícito.

### Clasificación de módulos

| Categoría | Módulos | Destino |
|---|---|---|
| Núcleo técnico | `core-api`, `kernel`, `manager-spi`, `manager-core`, `manager-apt`, `suite-runtime` | Compilado en el host; sin features |
| Adaptadores | `spigot-host`, `fabric-host` | Solo `core-api` + `suite-runtime` + SDK de plataforma; bootstrap sin listeners de eventos |
| Sincronizadores de plataforma | `sync-bukkit`, `sync-fabric` | Paquetes de plataforma descargables; capturan eventos, traducen a `Message`, despachan y publican el sink local `bukkit`/`fabric` |
| Paquetes de dependencia | `logger`, `transport`, SnakeYAML, JDA, Adventure y demás librerías compartidas | Paquetes `DEPENDENCY`; el manager los descarga, verifica, relocaliza y carga por módulo |
| Paquetes base | `textformatter`, `messages`, `chattranslator`, `iflow`, `iflow-16`, `iflow-32`, `iflow-firewall`, `iflow-wasm` | Paquetes instalados por defecto; los motores iFlow no se excluyen |
| Paquetes opcionales | `gtranslate`, `ltranslate`, `sync-bus`, `sync-*`, `observability`, `presets`, `inworld`, `extension-api` | Paquetes neutrales; dependencias declaradas en `METADATA/package.list` |
| Producción auxiliar | `tester` | `PACKAGE` neutral + `TestProbe`; benchmarking y traza del pipeline |
| Harness | `loadtest`, `performance`, `example-extension`, `fixtures` | Fuera del artefacto de producción |
| Retirado | `ModuleLifecycleAdapter`, `SuiteHost.bootstrap` hardcodeado, descubrimiento por classpath, cableado de features en adapters, `common-legacy` | Eliminados; no se restauran |

Decisión por defecto: **todo feature es paquete**, incluso `textformatter`, `iflow-*`,
`chattranslator` y `messages`. El núcleo solo contiene contratos, kernel, manager y runtime;
`logger` y `transport` son paquetes de dependencia, no código del host. Los hosts no contienen
listeners de eventos.

### Reglas de dependencia

```text
spigot-host / fabric-host
  → core-api
  → suite-runtime
  → SDK de plataforma

suite-runtime
  → core-api
  → kernel
  → manager-spi / manager-core / manager-apt
  → logger / transport (librerías)
  ✗ ningún módulo de feature

core-api
  → JDK
  ✗ kernel, manager, host, features, plataforma

kernel
  → core-api

manager-*
  → core-api
  → kernel
  ✗ host, features, plataforma

feature module
  → core-api (y, si hace falta, APIs de host-core)
  ✗ otros features salvo dependencia declarada
  ✗ Bukkit / Fabric / Minecraft

sync-bukkit / sync-fabric
  → core-api
  → SDK de plataforma
  → MessageDispatch / ActorDirectory / PermissionChecker / UserLanguageStore
  ✗ features, host concreto, otros sync
```

### Hexágono por módulo y frontera plataforma↔módulo

Cada módulo se estructura como un hexágono. El núcleo contiene la lógica y los contratos; los
adaptadores implementan los puertos hacia tecnologías concretas. Ningún módulo neutral importa JDA,
Velocity, Paper, Bukkit ni Fabric. Ningún adapter de plataforma importa paquetes de feature.

1. **Núcleo neutral.** La lógica de `sync-discord`, `sync-velocity-core`, `inworld`, `tester`,
   `presets` y `extension-api` vive en clases sin tipos de plataforma.
2. **Adaptadores tecnológicos.** JDA, Velocity API, Paper/Bukkit y Fabric viven solo en adaptadores:
   `adapters/jda`, `adapters/velocity`, `sync-bukkit`, `sync-fabric` y el bootstrap de los hosts.
3. **Eventos agnósticos.** El sincronizador traduce el evento de Minecraft a `Message`/evento
   neutral y lo entrega al runtime; nunca registra listeners de Paper/Bukkit.
4. **Ciclo de vida no bloqueante.** `start()` no espera red ni servidores externos. Las conexiones
   se hacen en un adaptador con estado propio, reintentos y `stop()` determinista.
5. **Sin stubs.** Un paquete no se empaqueta si su operación principal es un no-op.
6. **Sin compatibilidad indefinida.** No se añaden adaptadores de compatibilidad sin fase de
   eliminación.
7. **Comandos y configuración neutrales.** Los módulos registran sus comandos y configuración por
   `api.command`/`api.config`; el host solo traduce la invocación de plataforma.

### Decisiones de composición

1. **Núcleo vs paquetes.** Todo feature es paquete. El núcleo técnico es `core-api`, `kernel`,
   `manager-*` y `suite-runtime`. `logger` y `transport` son paquetes de dependencia.
2. **`inworld` y `tester`.** `inworld` se parte en API neutral + implementación de plataforma.
   `tester` es un módulo de producción y se distribuye como paquete neutral con adapters.
3. **Extensiones.** `ExtensionManager` se unifica con `ModuleHost`: una extensión es un paquete de
   tipo extensión con el mismo ciclo de vida. No existe un `enum` cerrado de módulos conocidos.
4. **Versiones concurrentes.** Al estilo Debian: la versión nueva reemplaza a la anterior
   resolviendo el grafo; no hay coexistencia permanente de dos versiones del mismo paquete.
5. **Librerías compartidas.** Son paquetes `DEPENDENCY`; el classloader del módulo las resuelve
   desde el scope que el manager le asigna.
6. **Metadatos.** El `packages.list` del repositorio es la fuente de verdad; el manifiesto del ZIP
   se valida contra el índice.
7. **Seguridad.** Sandbox por módulo y responsabilidad del dueño del repositorio; SHA-256
   obligatorio, firmas y allowlists opcionales (ADR-003).

### Lo que este ADR no promete

- No promete que todos los módulos puedan actualizarse sin reinicio.
- No promete que todos los módulos sean neutrales a la plataforma.
- No promete compatibilidad con ningún bootstrap anterior; los caminos retirados no se restauran
  como fachada.
- No promete que todos los módulos liberen recursos en todos los escenarios; cuando no pueden, el
  estado es `RESTART_REQUIRED`, no un éxito fingido.
- No promete que la suite haya quedado funcional sin pasar por las puertas de calidad de cada fase.

---

## ADR-020 — Un único `config/suite/messages.yml` para todas las plataformas

**Estado:** Aceptada (2026-10-27).

**Decisión.** Los textos de UI viven en un único `config/suite/messages.yml`, independiente de la
plataforma. `HostPaths.suiteMessages()` resuelve la ruta canónica y `MessagesConfig` la consume. El
fallback al `messages.yml` plano anterior fue eliminado en P0.3; una ruta no canónica se ignora o
se rechaza, no se migra en runtime. `resetConfigs`/`copyDefaultsIfMissing` escriben y respaldan la
misma ruta en Spigot y Fabric.

**Consecuencias.** Una sola fuente de verdad para los textos de UI. `host` (core) conoce un recurso
de presentación; si en el futuro se quiere un `messages` por plataforma, se introducen overrides por
adapter. El archivo compartido puede contener claves específicas de una plataforma; hoy no hay
validación que lo impida.

---

## ADR-021 — API de configuración y comandos jerárquicos registrables

**Estado:** Aceptada (2026-10-29).

**Decisión.**

1. `core-api` publica `api.command`: `CommandRegistry`, `CommandSpec`, `CommandPath`,
   `CommandSource`, `CommandContext`, `CommandOption` y `CommandRegistration`.
2. `CommandSpec` es un árbol: nombre, aliases, descripción, permiso, uso, argumentos/opciones,
   hijos y ejecutor. El registro es por módulo y devuelve un `CommandRegistration`
   (`AutoCloseable`).
3. `ModuleContext.commands()` entrega un registro module-scoped; el runtime cierra todas las
   inscripciones después de `stop()`. El path por defecto es el namespace del módulo; un path
   explícito se declara y también se retira al desactivar. Un módulo no puede dejar comandos
   huérfanos.
4. `core-api` publica `api.config`: `ConfigRegistry`, `ConfigSection` y `ConfigRegistration`. Un
   módulo registra su sección (namespace, defaults, esquema, validador y reload). La configuración
   persistida no se borra al desregistrar; solo se retira la superficie de comandos/esquema.
5. `CommandRegistry` es el único punto de composición: los comandos del runtime (`help`, `list`,
   `state`, `install`, `remove`, `upgrade`, `repo`) usan la misma API; el host solo traduce la
   invocación de plataforma.
6. Conflictos de path/alias fallan explícitamente; no hay override silencioso. La política `force`
   solo aplica si se define explícitamente y deja warning.
7. Los comandos son neutrales: no importan Bukkit/Fabric; reciben `CommandSource`/`CommandContext`
   y responden por `reply`. La tab completion se deriva del árbol.
8. El árbol de comandos del host (`DynamicCommandRegistrar`/`DynamicCommand` y el Brigadier de
   Fabric) se elimina o se reduce a un adaptador de invocación; no se mantiene duplicado.

**Consecuencias.** Cada módulo publica comandos bajo su namespace sin conocer la plataforma.
Desactivar un módulo retira su subárbol; reactivarlo lo vuelve a registrar. El host deja de ser
dueño del árbol de comandos. La configuración existente sigue siendo del módulo; la API estandariza
la superficie, no impone un formato de archivo.

---

## ADR-022 — Raíz de comandos relocalizable

**Estado:** Aceptada (2026-10-29).

**Decisión.**

1. `CommandRoot` es un value object con segmentos (`["suite"]`, `["suite","module"]`, `["tf"]`) y
   aliases opcionales. Se declara en `config/suite/commands.yml`, no en código.
2. `CommandRegistry` almacena paths relativos al root. El runtime compone `root + path` al registrar
   y al resolver. Los módulos nunca ven ni hardcodean `/suite`.
3. El adaptador de plataforma registra el primer segmento del root y delega el resto al dispatcher
   neutral. Para roots multi-segmento, el dispatcher consume el prefijo y enruta el resto.
4. Help, usage y errores usan `CommandContext.root()`; no se concatenan strings `/suite`.
5. Cambiar la raíz es cambiar configuración: `root: [suite]` → `root: [suite, module]` o
   `root: [tf]`; los módulos no cambian. Los aliases permiten mantener `/suite` y `/tf` a la vez.
6. `base-name`, `actions` y `commands` están retirados: se ignoran y no hay migración en runtime.
   El esquema vigente es `root` + `aliases`.
7. Si la raíz cambia en caliente, el runtime re-registra el comando raíz; si la plataforma no
   permite desregistrar o restaurar, se marca `RESTART_REQUIRED` y no se publica la nueva raíz como
   activa. Si el nuevo registro falla, se intenta restaurar la raíz anterior y se informa `FAILED`.
8. Conflictos de root con comandos existentes fallan explícitamente; no se roba un comando ajeno.

**Consecuencias.** Migrar la raíz es configuración, no refactor. Los módulos son independientes del
nombre del comando raíz. El host solo necesita un adaptador de invocación de primer nivel. La
documentación y los mensajes deben usar `CommandContext.root()`.

---

## ADR-023 — Compilador C para iFlow VM

**Estado:** Aceptada. Diseño congelado. El orden de trabajo vive en
`PLAN.md`. No se implementa contra el ISA v2 de 16 bits.

**Contexto.** `tools/iflow-compiler/` solo ensambla `.flowasm` v2 a `.flow` v2. No hay frontend C,
ni ABI C, ni runtime freestanding para la arquitectura v3. Escribir políticas en ensamblador es una
barrera real para autores y para el editor. El compilador no pertenece al host: es una herramienta
offline/CI; el servidor solo carga ROM ya compilada y verificada.

**Decisión.**

1. **Herramienta separada.** Se añade `tools/iflow-c/` con el CLI `iflow-cc`, implementado en
   TypeScript sobre Node.js 18+, igual que el assembler actual. No sustituye a
   `tools/iflow-compiler/`: el compilador C emite `.flowasm` v3 y el assembler v3 sigue siendo el
   único codificador de instrucciones y el único productor de ROM.
2. **Pipeline.** Preprocesador → lexer → parser → análisis semántico → AST tipado → IR de tres
   direcciones → optimización local → lowering al target → asignación de registros → emisión de
   `.flowasm` v3. El assembler v3 resuelve símbolos, relocaciones, TOC, hash y segmentos.
3. **Lenguaje.** Subconjunto C11 freestanding: tipos `void`, `_Bool`, `char` sin signo, `signed
   char`, `short`, `int`/`long` de 32 bits, `long long` de 64 bits, `float` (binary32),
   `double`/`long double` (binary64), punteros de 32 bits, arrays, `struct`, `union`, `enum`,
   `typedef`, `const`, `volatile`, `_Atomic` escalar/puntero, control de flujo completo, funciones,
   `static`/`extern`, `goto`, `switch`, `_Static_assert`, macros de objeto/función, `#include`,
   condicionales, `#`/`##` y ensamblador inline estilo GNU (`asm`/`asm volatile` y forma extendida
   con constraints `r`/`m`/`i`). `volatile` no se elimina, duplica ni reordena. `_Atomic` soporta
   cargas, almacenes, intercambio, CAS y fetch-add/sub/and/or/xor.
4. **Exclusiones explícitas.** `_Complex`, hilos, `setjmp`/`longjmp`, VLA, bitfields, caracteres
   anchos, `_Generic`, `typeof`, `asm goto` y funciones variádicas. Un uso no soportado falla con
   diagnóstico y ubicación; no se acepta en silencio ni se degrada. Las exclusiones se revisan con
   ADR propio si una política real las necesita.
5. **Preprocesador.** Incluye `#include` con rutas de búsqueda, macros de objeto y de función,
   `#`/`##`, `#if`/`#ifdef`/`#ifndef`/`#elif`/`#else`/`#endif`, `#undef`, `#error` y `#pragma once`.
6. **ABI C v3.** Se congela sobre el ISA v3: little-endian, alineación natural, pila de 4 bytes (8
   para 64 bits), argumentos en pila de derecha a izquierda, limpieza del llamador, retorno en
   `R0`/`R1:R0`, GPR caller-saved, `SP`/`FP` preservados, `struct`/`union` por valor con puntero
   oculto, `char` sin signo y `size_t`/`uintptr_t`/`ptrdiff_t` de 32 bits. `float`/`double` se pasan
   en pila y se retornan en `R0`/`R1:R0`; el baseline es soft-float. Un cambio de ABI requiere
   enmendar este ADR.
7. **Runtime `libiflow`.** Biblioteca freestanding enlazada en el programa: `crt0`, `mem*`, `str*`,
   rutinas de 64 bits, helpers soft-float IEEE-754, helpers atómicos y arena allocator explícito. No
   provee `stdio`, `printf` ni `malloc`/`free` estándar hasta que el kernel defina su ABI; lo no
   implementado falla al enlazar, no se simula.
8. **Modelo de programa.** El baseline compila un programa completo (una o varias unidades `.c` en
   la misma invocación) a un único `.flowasm` v3. No hay formato de objeto estable ni linker de
   objetos separado; si se necesita, se añade con ADR. El loader inicializa `.data` y zeroa `.bss`;
   `crt0` llama a `main`. No hay constructores globales.
9. **Optimización.** `-O0` es el default y debe ser correcto antes que rápido. `-O1` puede aplicar
   plegado de constantes, propagación local, eliminación de código muerto y peephole, sin explotar
   comportamiento indefinido, sin alterar `volatile` y sin reordenar operaciones atómicas. La coma
   flotante mantiene semántica IEEE-754 por defecto; `-ffast-math` no es baseline. La salida es
   determinista.
10. **Seguridad.** El compilador no corre en el host ni en el servidor. No descarga dependencias en
    tiempo de compilación ni ejecuta código del fuente. La ROM resultante es dato no confiable para
    la VM: se valida por hash, segmentos, permisos y límites como cualquier otro artefacto. El
    editor puede invocar el compilador en cliente/CI, nunca en el proceso del host.

**Prerrequisitos.** El ISA/ABI v3 y el assembler v3 deben estar congelados antes de implementar el
compilador. El orden y las puertas de trabajo se rastrean en `PLAN.md`.

**Consecuencias.** Los autores pueden escribir políticas en C sin aprender el ISA; el assembler
sigue disponible para firmware y código de máximo control. No se promete C completo: libc estándar,
objetos separados, VLA, bitfields, variádicas, `setjmp`/`longjmp`, `_Complex` y otras funciones
quedan fuera hasta que una necesidad real y un ADR las incorporen. El mantenimiento del compilador
es deuda explícita del proyecto; cada exclusión y limitación debe estar documentada y probada.

---

## ADR-024 — Puerto neutral de renderizado enriquecido

**Estado:** Aceptada (2026-10-30).

**Decisión.**

1. `core-render` es un módulo neutral separado de `core-api` porque su contrato usa
   `net.kyori.adventure.text.Component`. `core-api` sigue sin dependencias.
2. `MessageComponentRenderer` recibe `Message`, `Actor`, idioma de origen e idioma de destino ya
   resueltos. No decide routing, permisos ni entrega.
3. `TextFormatterMessageRenderer` implementa `MessageRenderer` (texto plano) y
   `MessageComponentRenderer` (componente enriquecido). `textformatter` registra ambas interfaces en
   su `ModuleContext`.
4. `SuiteHost` recibe el renderizador por constructor. Su constructor público solo usa puertos
   neutrales y configuración del host, de modo que el runtime puede ensamblarlo después de activar
   los paquetes.
5. No se conserva un segundo pipeline en los adapters ni un bootstrap heredado.

**Consecuencias.** El pipeline deja de importar tipos de `textformatter` y `chattranslator`.
`checkArchitecture` escanea `core-render` como módulo neutral. La construcción real del pipeline
desde `ServiceRegistry` es responsabilidad de `suite-runtime`.

---

## ADR-025 — Adapters delgados sobre `SuiteRuntime`

**Estado:** Aceptada (2026-10-30).

**Decisión.**

1. `spigot-host` y `fabric-host` solo crean `PlatformPorts`, registran los puertos propiedad del
   adapter y arrancan `SuiteRuntime`. El comando raíz se registra con
   `runtime.registerCommands()`.
2. Los adapters no importan `host`, `kernel`, `manager` ni paquetes de feature.
   `checkArchitecture` lo verifica.
3. `ChatDelivery` vive en `core-render` porque su contrato usa Adventure. `InWorldPlatform` e
   `InWorldService` viven en `core-api`; el paquete `inworld` implementa y publica el servicio, y el
   adapter solo ve el puerto.
4. `YamlUserLanguageStore` vive en `suite-runtime`; `SuiteRuntime` expone `defaultLanguage()` sin
   publicar `HostConfig` a los adapters.
5. La captura de eventos de chat/join/quit/death no pertenece al host: se implementó en
   `sync-bukkit`/`sync-fabric`, que además publican el sink local `bukkit`/`fabric` (ADR-036 §17).
6. No se conserva un segundo pipeline en los adapters. `SuiteHost.bootstrap`, `SuiteBootstrap`,
   `RouterFactory`, `IflowEngineSelector` y `ConfigValidator` se retiraron del módulo `host`. Los
   tests y benchmarks cablean las implementaciones reales con fixtures locales, fuera del classpath
   de producción.

**Consecuencias.** Instalar o descargar un paquete es la única forma de cambiar features en
producción. Los adapters pueden compilar contra `core-api`, `core-render`, `suite-runtime` y el SDK
de plataforma. El `runtimeClasspath` de `suite-runtime` no arrastra paquetes de feature; `host` solo
depende de `core-api`, `core-render`, SnakeYAML y Gson.

---

## ADR-026 — Hot upgrade, fugas y límites de dependencias

**Estado:** Aceptada. Parcialmente sustituida por ADR-027: se mantienen las reglas de detección de
fugas, dependencias compartidas, paquetes multi-JAR y retirada de APIs legacy; la secuencia de
activación, el contrato manager-runtime y la política de rollback pasan a ADR-027.

**Decisión vigente.**

1. Las rutas de instalación son versionadas por directorio: `library/packages/<id>/<version>/` y
   `library/dependencies/<id>/<version>/`, con la versión instalada referenciada desde
   `library/status`. Ningún JAR activo se sobrescribe durante la preparación.
2. La desactivación valida cuatro fugas antes de declarar `INSTALLED`: fallo de `ResourceTracker`,
   registros de owner restantes, classloader no cerrado e hilos vivos en el `ThreadGroup` del
   módulo. Cualquier fuga produce `RESTART_REQUIRED`. Las tareas del scheduler que siguen
   ejecutándose también cuentan como owner vivo.
3. Un upgrade de una dependencia compartida se rechaza si algún módulo activo la usa (directa o
   transitivamente); el mensaje exige reiniciar. La comprobación ocurre en la fase de preparación,
   antes de tocar `library/packages`, `library/dependencies` o `library/status`.
4. Un paquete `PACKAGE` contiene uno o más JARs de módulo. Cada JAR declara su `moduleId` en
   `ModuleActivator.descriptor()` y el runtime mapea activación, upgrade y retirada por ese id. Los
   ids deben ser únicos dentro del paquete; cero JARs o ids duplicados son un error explícito.
5. `ModuleLifecycleAdapter`, `ModuleLifecycle`, `ModuleCoordinate`, `ModuleDescriptor` y
   `Environment` de `manager-spi`, junto con `ManagerFacade.discoverAll()`/`getLoadedModules()`, se
   eliminan. El descubrimiento por classpath ya no es una vía de producción.

**Consecuencias.** El manager conserva la propiedad de los hechos de instalación; el runtime
conserva la propiedad del estado vivo. Ninguno simula el estado del otro. Las dependencias
compartidas no se actualizan en caliente mientras estén en uso; el reinicio es la vía segura.

---

## ADR-027 — Instalación por etapas y semántica de fallo

**Estado:** Aceptada. Sustituye la secuencia de activación/rollback de ADR-026 y
retira `keepBoth` como mecanismo de recuperación automática.

**Contexto.** El flujo anterior mezclaba red, commit de archivos y arranque. Un fallo de arranque
podía disparar compensación de archivos y reactivación de versiones anteriores. El usuario fija un
pipeline determinista: primero se completa la adquisición, después el commit de archivos y solo
entonces se arranca en orden jerárquico. La única reversión automática es la del commit de archivos;
un fallo de arranque no deshace una instalación ya desplegada.

### 1. Etapa 1 — Adquisición independiente

Antes de modificar cualquier ruta de instalación, el manager construye un plan completo e inmutable
con paquetes principales y dependencias transitivas, versiones resueltas y conflictos
`Exclude`/`Provide`, plataforma/arquitectura/capacidades, hashes SHA-256, tamaños y metadatos, y
orden jerárquico de instalación y activación.

Todos los ZIP y dependencias se descargan y verifican en caché antes de tocar `library/packages`,
`library/dependencies` o `library/status`. La caché es contenido-direccionada:

```text
library/cache/downloads/<sha256>/<archivo>.zip
library/cache/downloads/<sha256>/<archivo>.zip.part
library/cache/operations/<operationId>.json
```

Cada artefacto tiene un estado explícito `PENDING` o `COMPLETE`. Solo es `COMPLETE` cuando el
archivo final existe, su tamaño coincide y su SHA-256 se verificó. Un `.part` nunca se reutiliza
como artefacto válido. Reanudar significa reutilizar los `COMPLETE` y descargar únicamente los
`PENDING`. `/<root> cache clean` borra descargas, parciales y manifiestos de operaciones, pero no
toca paquetes instalados, `library/status`, el journal ni `library/trash`.

La desinstalación no descarga nada. Su etapa 1 resuelve el cierre de dependencias inverso y valida
que ningún módulo restante dependa de lo retirado.

### 2. Etapa 2 — Commit de archivos con journal duradero

La etapa 2 aplica **todos** los cambios del plan en una sola transacción. Antes de la primera
mutación escribe un journal durable en `library/journal/<operationId>.log` con la instantánea del
`library/status` anterior, archivos preparados/creados/reemplazados/retirados, versiones y
directorios anteriores, operaciones completadas y pendientes de revertir, y acciones de rollback
por operación.

El journal se escribe en modo append-only, se sincroniza a disco (`fsync`) y se trata como la fuente
de recuperación ante excepciones, `kill -9`, corte de energía o reinicio abrupto. Los archivos se
mueven con rename atómico dentro del mismo sistema de archivos y se sincroniza el directorio padre.
El nuevo `library/status` se escribe en un temporal, se sincroniza y se publica con rename atómico;
**ese rename es el punto de commit**. Antes del punto de commit, la recuperación revierte todas las
operaciones registradas y restaura el estado anterior. Después del punto de commit, la recuperación
completa la limpieza y nunca revierte la instalación. El runtime no arranca hasta que el journal se
ha recuperado y la etapa 2 está confirmada.

Los archivos de paquetes viven en directorios versionados; la versión nueva no sobrescribe los
archivos de la versión activa. Instalar la misma versión ya presente se rechaza: para repararla hay
que retirarla primero. `library/status` guarda la referencia persistente a la versión instalada y
es el único puntero autoritativo. Los archivos no versionados (por ejemplo, configuración
reemplazada) se respaldan en `library/backup/<operationId>/` antes de sustituirlos. Si falla
cualquier paso, se restaura el journal completo: archivos anteriores, archivos nuevos retirados,
`library/status` anterior y directorios de versión. La caché de descargas no se borra.

En una desinstalación, la etapa 2 empieza con una **barrera de runtime**: desactiva los módulos
afectados en orden inverso antes de mover sus archivos. Si la desactivación falla o deja fugas, se
aborta antes de tocar archivos y se marca `RESTART_REQUIRED`; no se borra nada. Si la desactivación
es limpia, mueve los archivos propios a `library/trash/<operationId>/`, actualiza `library/status`
y confirma el journal. Si el commit de archivos falla después de desactivar, restaura los archivos
y reactiva las versiones anteriores; si esa reactivación no puede verificarse, conserva los
archivos restaurados y marca `RESTART_REQUIRED`. Un reemplazo por `Exclude` usa la misma barrera: la
etapa 2 desactiva los paquetes que el plan retira antes de mover sus archivos y la etapa 3 solo
activa los paquetes nuevos. Las sobrecargas sin barrera de runtime fallan explícitamente cuando el
plan requiere reemplazos.

La etapa 2 es el **único rollback automático** del manager. Un fallo en esta etapa restaura
íntegramente la instalación anterior, incluido el inventario persistente.

### 3. Etapa 3 — Activación y recuperación, sin rollback de instalación

Solo después del commit de archivos, el runtime aplica el ciclo de vida:

- instalar/actualizar: detener las versiones anteriores en orden inverso y arrancar los módulos
  nuevos en orden jerárquico;
- desinstalar: limpiar el estado runtime del paquete. La desactivación efectiva y la liberación de
  classloaders, registros y recursos ya ocurrieron en la barrera de la etapa 2; la etapa 3 no muta
  archivos ni retira dependencias huérfanas.

Antes del primer `start()` se persiste `ACTIVATION_PENDING` en `runtime/modules.status`. Si el
proceso cae durante la activación, el arranque siguiente vuelve a activar los paquetes instalados
desde `library/status`; no asume que los módulos están activos ni revierte los archivos.

El orden es el orden topológico de dependencias declarado por los paquetes y, dentro de un paquete,
el orden de capacidades `requires`/`provides` de sus módulos. Las dependencias se arrancan antes que
sus consumidores. Los empates se resuelven por nombre para que el orden sea estable. La
desinstalación recorre el orden inverso.

Si falla el arranque de un módulo:

1. Se detienen, en orden inverso, los módulos que ya se habían arrancado en este intento. Esto es
   limpieza del runtime, no rollback de la instalación.
2. No se restauran archivos, `library/status` ni la versión anterior. Los archivos nuevos permanecen
   instalados.
3. El paquete queda `ACTIVATION_FAILED`; el módulo culpable queda `FAILED` y los módulos detenidos
   vuelven a `INSTALLED`. Si la detención de limpieza deja hilos, recursos o registros vivos, se
   marca `RESTART_REQUIRED`.
4. Si la versión anterior ya está en `RESTART_REQUIRED`, no se arranca la nueva versión: el paquete
   permanece en `RESTART_REQUIRED` y la instalación confirmada no se revierte.
5. El resultado incluye la etapa fallida (`RUNTIME`), el `moduleId` y la causa. La activación puede
   reintentarse sin reinstalar.

Estados persistentes de instalación/activación:

| Estado | Significado |
|---|---|
| `INSTALLED` | Archivos e inventario confirmados; activación no iniciada o detenida limpiamente. |
| `ACTIVATION_PENDING` | Activación iniciada y aún no confirmada; el arranque debe reanudarla. |
| `ACTIVE` | Todos los módulos esperados están activos. |
| `ACTIVATION_FAILED` | La instalación está confirmada, pero al menos un módulo no arrancó; se permite reintentar. |
| `RESTART_REQUIRED` | La limpieza del runtime no pudo verificarse; hace falta reiniciar. |

Un módulo puede quedar `FAILED`; al retirar un paquete se elimina su entrada `package:<nombre>` en
vez de persistir `REMOVED`.

**Invariante:** una instalación puede estar correctamente instalada sin estar correctamente
activada. `library/status` registra la instalación; el estado de runtime registra la activación. El
manager no convierte un fallo de activación en un fallo de instalación ni oculta la diferencia con
un rollback destructivo. La etapa 3 solo actualiza estado de runtime; no modifica `library/status`
ni el sistema de archivos.

### 4. Desinstalación: coordinación explícita con el runtime

La desinstalación sigue las mismas tres etapas, con una adaptación obligatoria: no se puede retirar
un JAR que un classloader activo todavía tiene abierto. Por eso la desactivación efectiva se ejecuta
como barrera al **inicio de la etapa 2**, antes de mover archivos, y la etapa 3 solo finaliza y
verifica esa desactivación.

| Operación | Etapa 1 | Etapa 2 | Etapa 3 |
|---|---|---|---|
| `install` | resolver, descargar y verificar | commit de archivos y `status` | arrancar en orden |
| `upgrade` | resolver, descargar y verificar | commit de la versión nueva y puntero | detener anterior y arrancar nueva |
| `uninstall` | validar plan de retirada | detener módulos y retirar archivos/`status` | finalizar estado runtime (sin mutar archivos) |

Si falla la etapa 3 de una desinstalación, los archivos ya retirados y el inventario ya actualizado
no se revierten; se marca `RESTART_REQUIRED` si quedan referencias vivas. La etapa 3 no retira
dependencias huérfanas: cuando `autoRemove` está activo, la etapa 2 las incluye en la misma
transacción. `library/trash` conserva los archivos retirados para el rollback del journal antes del
commit; después del commit solo sirve para recuperación manual, nunca para rollback automático.

### 5. Layout versionado por paquete

```text
manager/library/
├── packages/
│   └── <packageId>/
│       ├── <version>/
│       │   ├── <module>.jar
│       │   └── ...
│       └── <versionAnterior>/
├── dependencies/
│   └── <dependencyId>/
│       └── <version>/
├── status
├── cache/
│   ├── downloads/<sha256>/
│   └── operations/<operationId>.json
├── journal/<operationId>.log
├── backup/<operationId>/
├── trash/<operationId>/
└── tmp/<operationId>/
```

`library/status` relaciona cada paquete con su versión instalada y con los archivos y hashes de esa
versión. No se usan enlaces simbólicos como puntero autoritativo. Una versión anterior permanece en
disco mientras exista una referencia de runtime; no se borra durante el commit de la instalación. Un
fallo de activación no autoriza a borrar la versión anterior, pero tampoco la reactiva
automáticamente.

### 6. Contrato manager-runtime y resultados

La etapa 3 se invoca mediante un contrato post-commit que recibe el plan ya instalado y devuelve un
resultado por módulo. No puede vetar la etapa 2, ordenar el borrado de archivos ni restaurar
versiones anteriores. `PackageUpgradeListener` con `allow()`, `rollback(...)` y `keepBoth(...)` se
retira; `keepBoth` deja de existir como estado de recuperación automática.

`InstallResult` debe distinguir:

- `installed`: el commit de archivos y `library/status` se completaron;
- `activation`: `ACTIVE`, `ACTIVATION_FAILED`, `ACTIVATION_PENDING` o `RESTART_REQUIRED`;
- `failedStage`: `PREPARE`, `COMMIT` o `RUNTIME`;
- `moduleId` y causa cuando el fallo es de runtime.

Un fallo de runtime no puede reportarse como “no instalado”. Un fallo de instalación no puede
reportarse como “módulo fallido”.

### 7. Validaciones de la etapa 1

Un ciclo de dependencias, un proveedor faltante, un checksum inválido, un conflicto de plataforma,
un artefacto fuera de la caché o una dependencia compartida en uso se detectan en la etapa 1. Si no
se puede determinar un orden de arranque estable, la operación falla antes de mutar archivos. La
caché se verifica por SHA-256 antes de reutilizar cualquier artefacto; la limpieza no sigue enlaces
simbólicos y nunca acepta rutas fuera de `library/cache`, `library/tmp`, `library/backup` o
`library/trash`.

**Consecuencias.** La etapa 2 es el único rollback automático. Un fallo de etapa 3 deja una
instalación nueva committed, con arranque detenido o parcial; no se deshace. Los estados
`INSTALLED`, `ACTIVATION_PENDING`, `ACTIVE`, `ACTIVATION_FAILED` y `RESTART_REQUIRED` son
persistentes y no se infieren del código de retorno de un comando. La atomicidad del lote no depende
del número de paquetes. La regla C13 sigue aplicando: ninguna etapa puede asumirse correcta sin
verificarla.

---

## ADR-028 — Etiqueta `<regex>` en plantillas de TextFormatter

**Estado:** Aceptada.

**Decisión.**

1. La sintaxis es `<regex match="PATRÓN">CONTENIDO</regex>`. No se introduce `%regex%`,
   `${regex}` ni otra variable escalar.
2. `match` es un patrón Java (`java.util.regex.Pattern`), obligatorio y no vacío. El patrón no se
   interpreta como MiniMessage; las comillas dobles y los caracteres XML se escapan.
3. `CONTENIDO` es el texto candidato y, a la vez, la plantilla MiniMessage que se renderiza. Puede
   contener placeholders y las etiquetas MiniMessage habituales.
4. La etiqueta se evalúa en `TemplateRenderer` antes del `deserialize` final de MiniMessage. Si el
   patrón coincide con el contenido ya resuelto, el span se sustituye por `CONTENIDO` interpretado
   como MiniMessage. Si no coincide, el span no emite texto.
5. El formato MiniMessage se aplica al texto que satisface el patrón. El patrón no se colorea ni se
   transforma; los grupos de captura no se interpolan automáticamente.
6. No se anidan etiquetas `<regex>`. `<tr>` conserva su tratamiento independiente.
7. El patrón se compila una vez por plantilla y se reutiliza. Un patrón inválido, vacío o fuera de
   límite es error explícito de configuración; no se ignora ni se degrada.
8. Se imponen límites duros y documentados al patrón y al contenido evaluado para acotar el coste de
   backtracking. Límites vigentes: patrón ≤ 512 unidades UTF-16, contenido evaluado ≤ 8192 unidades
   UTF-16 y ≤ 32 spans por plantilla. El límite de contenido se revalida tras resolver placeholders.
9. La etiqueta no ejecuta código, no evalúa SpEL, no consulta la VM, no decide routing, permisos ni
   entrega y no muta el `Message`.

**Consecuencias.** `textformatter` gana una construcción de plantilla, no un lenguaje de políticas.
La VM sigue siendo la única superficie para decisiones ejecutables. Un template inválido falla al
cargarse y no llega a producción.

---

## ADR-029 — Distribución reproducible y frontera de dependencias de terceros

**Estado:** Aceptada. Complementa ADR-026 y ADR-027; no cambia las tres etapas de
instalación ni la semántica de `ACTIVATION_FAILED`.

**Decisión.**

1. Los JARs de módulo y de host se generan con orden estable y sin reloj de pared. El manifiesto usa
   `Build-Timestamp: 0` salvo que un release fije explícitamente `BUILD_TIMESTAMP` o
   `-PbuildTimestamp`. Los ZIP de módulo usan `preserveFileTimestamps = false` y
   `reproducibleFileOrder = true`.
2. `moduleChecksum` publica un sidecar `.sha256` junto a cada ZIP. `moduleRepository` exige
   `-PrepositoryBaseUrl`, recopila todos los ZIP de módulo y de terceros, y escribe `packages.list`
   y `SHA256SUMS`. Un artefacto publicado es inmutable: un cambio de contenido exige una versión o
   un directorio nuevos.
3. Las librerías de terceros que no cruzan una API neutral se empaquetan como `DEPENDENCY` con sus
   transitivas resueltas. El módulo consumidor las declara en `moduleDependencies`; el manager las
   instala antes y `ModuleClassLoader` las expone desde `library/dependencies`.
4. La versión de un paquete debe ser SemVer, aunque el artefacto Maven no lo sea.
5. Adventure, MiniMessage y el serializador plano permanecen en el host. Un módulo no declara un
   paquete `DEPENDENCY` de Adventure mientras `core-render` exponga esos tipos. Cambiar esta regla
   requiere rediseñar el contrato de renderizado.
6. Los shadow JAR de Spigot y Fabric contienen solo el runtime del host.
   `verifyShadowJarHasNoFeatures` falla `check` si una feature se cuela en el bootstrap. Los
   listeners de plataforma y `sync-bukkit`/`sync-fabric` son paquetes descargables.
7. TLS + SHA-256 detecta corrupción y manipulación de transporte, no prueba autoría. No hay firma de
   paquetes, pinning ni certificados en esta fase; el propietario del repositorio es una autoridad
   de confianza. El aislamiento por classloader/capabilities no es un sandbox contra código JVM
   hostil.

**Consecuencias.** Los paquetes de terceros son más grandes y pueden contener transitivas
duplicadas; los módulos deben declarar lo que usan y el manager no infiere imports.
`thirdPartyPackages` y `moduleRepository` requieren red para resolver Maven Central; un build
offline solo funciona con caché poblada. La reproducibilidad se verifica con dos builds y
comparación de hashes; `SHA256SUMS` se verifica con `sha256sum -c`.

---

## ADR-030 — Presets por comando y recarga de módulos activos

**Estado:** Aceptada.

**Decisión.**

1. `suite-runtime` registra `presets list` y `presets apply <preset>` como comandos built-in con el
   permiso `textformatter.suite.admin`. El comando resuelve `PresetService` desde el
   `ServiceRegistry`; si el paquete no está activo responde explícitamente y no inventa catálogo.
2. `FilePresetTarget` escribe la configuración del host en `config/suite/` y la de cada módulo en
   `config/<module>/`. El directorio base sigue siendo el data folder del adaptador.
3. Tras persistir un preset, el callback ejecuta `SuiteRuntime.reloadActiveModules()`: cierra el
   pipeline, detiene los módulos activos en orden inverso, los reactiva en el orden de activación y
   reconstruye el pipeline. No se recarga el conjunto instalado ni se activan módulos inactivos.
4. Si un módulo no se libera limpiamente, la recarga se detiene, el comando informa el módulo y su
   causa, y el estado queda `RESTART_REQUIRED`. No se restaura una versión anterior ni se simula
   éxito.
5. Un preset desconocido devuelve `false` sin escribir ni recargar. Los errores de escritura o de
   recarga se reportan al ejecutor del comando.
6. La ruta canónica de módulos es `config/<module>/`, por ejemplo `config/chattranslator/config.yml`.
   Las rutas `config/manager/<module>/` y `manager/config/<module>/` son incorrectas: no son layouts
   legacy, no son versiones superiores y no se aceptan como fallback. Cualquier archivo en esas
   rutas se ignora: no se migra ni se elimina automáticamente. Nunca se sobrescribe una
   configuración existente.

**Consecuencias.** Aplicar un preset es una operación de ciclo de vida completo: puede ser más
lenta que una escritura de archivos, pero deja archivos y servicios coherentes. La recarga depende
de que los módulos liberen recursos; un módulo que no lo hace exige reinicio. `FilePresetTarget`
conoce el layout canónico del runtime; un cambio de layout requiere actualizar el target y sus
tests.

---

## ADR-031 — Contrato de política iFlow v3 para la paridad VM/Wasm

**Estado:** Aceptada. El estado de implementación y la paridad pendiente entre motores se rastrean
en `PLAN.md`.

**Decisión.** Se ratifican las siguientes reglas para el contrato de política v3:

| ID | Decisión |
|---|---|
| `IFLOW-MAN-D01` | Default deny: si la política no llama a `DELIVER`, no se entrega. |
| `IFLOW-MAN-D02` | `SET_CHANNEL` es la única mutación de canal; valida existencia y permisos, sin fallback silencioso. Si cambia destinatarios, se resuelve en una segunda fase de routing sin reentrega recursiva. |
| `IFLOW-MAN-D03` | Se eliminan `REDIRECT` y `CHANNEL_REDIRECT` como targets. La consola es un canal/actor (`Channel.CONSOLE`, `actors.console()`) y el cambio de canal se hace con `SET_CHANNEL`. |
| `IFLOW-MAN-D04` | `PolicyTarget` se reduce a `DELIVER`, `CANCEL_EVENT` y `ERROR`. `DROP` no es target porque no llamar a `DELIVER` ya es drop; `LOG` pasa a pantalla/auditoría. |
| `IFLOW-MAN-D05` | `CANCEL_EVENT` vuelve al adaptador de plataforma y es distinto de cancelar el mensaje. |
| `Q26` | Read-your-writes: las lecturas de propiedades dentro de la misma ejecución ven el overlay provisional del journal; el commit sigue siendo todo o nada. |
| `Q35` / `IFLOW-MAN-42` | Destino: `Message.langTarget` > `Channel.langTarget` > `Actor.language` > `defaultLanguage`. Origen: `Message.langSource` > `Channel.langSource` > detección automática > `defaultLanguage`. |
| `IFLOW-MAN-31` | Se retiran `PROP_PROCESSED`, `PROP_SOUNDS` y `PROP_ACTOR_WORLD` del baseline v3. `PROP_TRANSLATE` se implementa como booleano `true`/`false` validado. |
| `Q36` / `IFLOW-MAN-43` | `Formats` produce N mensajes separados, uno por `texts[i]`/`formats[i]`, en orden. `%content%` es el contenido original en cada uno; rate limit y permisos se evalúan por entrega. |
| `Q38` / `IFLOW-MAN-44` | El render usa el recipient original. Tras un cambio de destino, el destinatario final reutiliza el componente ya renderizado; no impone su idioma ni sus placeholders. |
| `Q37` / `IFLOW-MAN-45` | Se elimina `Direction.channel`; el canal se toma solo de `Message.channel`. |
| ABI | Entrada/salida TLV, con ausencia explícita distinta de cadena vacía y UTF-8 sin truncar. |
| Decisiones | Una sola decisión de routing por ejecución; una segunda produce `DECISION_CONFLICT` y no se entrega. |

D41 y D43 siguen vigentes: la decisión es explícita y tipada, y el journal cubre solo el write set
del mensaje, no el estado de la máquina, el tiempo, la cola ni la entrega.

**Consecuencias.** La entrega deja de depender de un silencio implícito. `SET_CHANNEL` centraliza la
mutación de canal y elimina la reentrega recursiva de `REDIRECT`. `PolicyTarget` y el validador
pierden estados muertos. Read-your-writes permite escribir y volver a leer en la misma ejecución sin
introducir un segundo modelo de estado. La precedencia de idioma deja de ser implícita. TLV evita
truncamientos UTF-8 y distingue ausencia de cadena vacía. N entregas separadas respetan el contrato
de `Formats` y permiten formato, permisos y rate limit por mensaje. Rompe políticas v2 que dependían
de entrega implícita, `REDIRECT`, `processed` o `LOG`.

---

## ADR-032 — Hub `iflow` para seleccionar backend

**Estado:** Aceptada.

**Decisión.**

1. Se añade el paquete `iflow` como **hub**. Publica `IflowHub` y `Router`, y es el único punto que
   elige el backend activo.
2. Los motores publican `IflowBackend` (`name()`, `router()`, `isAvailable()`). No publican la
   selección ni conocen al hub.
3. La configuración canónica del hub es `config/iflow/config.yml`:
   `engine: iflow-firewall | iflow-16 | iflow-32 | iflow-wasm` y
   `fallback-on-unavailable: true|false`. Solo se aceptan los nombres canónicos; no hay alias cortos
   ni `default-backend` en este archivo. Las rutas `config/manager/iflow/config.yml` y
   `manager/config/iflow/config.yml` son incorrectas y no se aceptan como fallback.
4. El valor por defecto es `iflow-firewall`. Si el backend configurado no está disponible y
   `fallback-on-unavailable` es `true`, el hub usa el primer backend disponible según la prioridad
   explícita `iflow-firewall` → `iflow-16`/`iflow-32` → `iflow-wasm`, independiente del orden de
   activación de los módulos. **Nunca hay fallback entre `iflow-16` e `iflow-32`**: son semánticas
   distintas y el engine debe elegirse explícitamente. Si no hay ninguno, `IflowHub.router()` devuelve
   `null` y el runtime deja el pipeline
   sin motor; las llamadas directas al hub fallan cerradas con `REJECT`. El orden se fija con un
   test de aceptación; no se deduce del orden de registro del `ServiceRegistry`.
5. `IflowHub` extiende `Router`. `SuiteRuntime` exige `IflowHub`: un motor no puede sustituirlo
   publicando un `Router` suelto.
6. Los motores pueden coexistir instalados; el hub activa uno solo. La regla histórica de
   exclusividad de paquetes queda sustituida por la selección del hub para el runtime.
7. `HostConfig.Engine` e `iflow.engine.kind` fueron eliminados por ADR-034. El hub lee únicamente
   su propio `config/iflow/config.yml`.

**Consecuencias.** El host deja de importar motores concretos y solo consume `Router`/`IflowHub`.
Instalar varios motores ya no es un error de classpath; el hub elige uno y los demás quedan
inactivos para el routing. Un backend puede existir pero no estar disponible; el hub puede aplicar
fallback. El hub no crea ni cierra motores: cada motor conserva la propiedad de sus recursos.
Cambiar la selección en caliente requiere que el hub refresque; los eventos
`ModuleActivatedEvent`/`ModuleDeactivatedEvent` ya lo hacen.

---

## ADR-033 — Router Wasm, SHA-256 del artefacto y hot-reload

**Estado:** Aceptada.

**Decisión.**

1. `WasmPolicyRouter` implementa `Router` y es el backend que publica `iflow-wasm`. Traduce
   `PolicyOutcome` a `RouteOutcome`: `DELIVER → LOG`, `CANCEL_EVENT → DROP`, `ERROR → REJECT`. El
   mensaje transformado del journal se devuelve en el outcome.
2. La política se lee de `<data>/config/iflow-wasm/policy.wasm`. El SHA-256 es **obligatorio** y se
   toma de `policy.sha256` o `policy.wasm.sha256`; si falta o no coincide, el backend queda no
   disponible y falla cerrado. No se evalúa un artefacto sin hash.
3. El hash del `.wasm` es adicional al hash del paquete. El manager sigue verificando el ZIP/JAR
   contra el manifiesto y `SHA256SUMS`; el sidecar de la política cubre el archivo mutable de
   configuración. No se duplica el hash del paquete en el sidecar ni se introduce una firma de autor
   que el modelo de confianza actual no exige.
4. `WasmPolicyRouter` observa el directorio con `WatchService` y recarga con debounce de 100 ms al
   cambiar `policy.wasm` o cualquiera de los sidecars. La recarga también es invocable con
   `reload()`.
5. Un artefacto nuevo inválido desactiva el backend hasta que se corrija. No se conserva
   silenciosamente la política anterior: la recarga es fail-closed.
6. `iflow-wasm` publica `IflowBackend` con nombre `iflow-wasm`; `isAvailable()` refleja si hay una
   política verificada y validada. El hub `iflow` puede aplicar `fallback-on-unavailable`.
7. El router aplica las políticas de envío/recepción del canal y el rate limit de emisión, igual que
   `iflow-16`, `iflow-32` e `iflow-firewall`.

**Consecuencias.** Un `.wasm` sin sidecar no se ejecuta; el hub puede seguir con otro backend. El
operador debe actualizar el sidecar al cambiar la política. El watcher es un hilo daemon por router y
se cierra con el módulo. La integridad del paquete y la del artefacto de política son
responsabilidades separadas y documentadas.

---

## ADR-034 — Régimen pre-1.0: versión base 1.0, rolling release y purga de compatibilidad

**Estado:** Aceptada. La implementación de la purga y el reseteo de versiones se rastrea en
`PLAN.md`. Esta ADR sustituye cualquier política anterior de retrocompatibilidad, versiones
ficticias o validación exclusivamente automatizada.

**Contexto.** TextFormatter Suite sigue en fase de diseño fundacional. No existe una versión
estable, no hay producción y no hay una promesa de compatibilidad que sostener. El repositorio
arrastraba números `2.x`/`3.x`, aliases legacy, lectores de rutas antiguas, módulos *deprecated* y
textos que presentaban como “production-ready” algo que aún no había cerrado su integración. Esa
ficción de estabilidad fija decisiones antes de que la arquitectura esté terminada y añade carga
mental a cada refactor.

La versión `1.0.0` debe ser un hito real, no un número de build. Hasta alcanzarlo, el proyecto opera
en régimen de *rolling release*: los *breaking changes* están permitidos y son deseables para limpiar
la deuda técnica.

**Decisión.**

1. **Una sola base de versión.** Todos los módulos, componentes, contratos, artefactos y esquemas de
   configuración se alinean a `1.0`: artefactos Maven/Gradle `1.0.0`; contratos `*-api` y
   capabilities `1.0.0`; configuración YAML `config-version: 1.0`. Un sufijo de build no cambia la
   base funcional. No se usan `2.x`, `3.x` ni superiores como promesa de compatibilidad.
2. **Sin retrocompatibilidad pre-1.0.** Se eliminan los adaptadores, aliases, shims, dobles
   lectores, rutas legacy y ramas de compatibilidad que existan solo para no romper instalaciones
   anteriores. Los módulos legacy independientes (`iflow-firewall`, `CoreTranslator`) no son shims:
   se mantienen como implementaciones alternativas bajo C6. Un cambio incompatible se aplica en el
   mismo cambio que actualiza a todos sus consumidores; no hay ciclo de deprecación ni periodo de
   convivencia.
3. **Contrato base `1.0.0`.** La resolución de dependencias acepta contratos en el intervalo cerrado
   `[1.0.0, host]`: el mínimo es `1.0.0` y el máximo es la versión de contrato que expone el host.
   Rechaza cualquier contrato inferior a `1.0.0` y cualquiera superior al host. Las versiones son
   SemVer completas (`mayor.menor.parche`); no se aceptan formas abreviadas como `1.5` o `2.5`. Un
   módulo `1.0.0` funciona sobre un host `2.1.0`; un módulo `2.1.1` se rechaza hasta que el host
   suba su contrato. No se declaran rangos que prometan compatibilidad con versiones ficticias ni se
   degrada un mismatch mediante un adaptador.
4. **Configuración sin migración silenciosa.** Todos los `.yml` declaran `config-version: 1.0` en la
   raíz, preferentemente al final. Aplica a defaults, configuración instalada y configuración
   generada; no aplica a `plugin.yml`, que es formato propio de Bukkit. Si falta o no coincide, el
   parser falla con un error explícito. Si el esquema cambia, se reescribe la configuración en el
   mismo cambio; no se añaden lectores que acepten el esquema viejo como fallback. Una ruta o un
   formato no canónico se ignora o se rechaza con un mensaje explícito, nunca se convierte en
   runtime.
5. **Purga inmediata.** La primera tarea del PLAN es inventariar y eliminar el código de
   compatibilidad: `iflow.engine.kind`, `base-name` (si queda alguno), rutas planas de la raíz de
   `config/` (`config/config.yml`, `config/channels/`, `config/translators/`, `config/sync/`,
   `config/rules.yml`, `config/inworld.yml`), dobles lectores, aliases de motores y cualquier capa
   de compatibilidad dentro de features o contratos. Los `config/<module>/config.yml` canónicos se
   conservan. El inventario de versiones `2.x`/`3.x` se corrige a `1.0.0` o se elimina si ya no
   representa una capacidad vigente. `CoreTranslator` no se elimina: se restaura como módulo legacy
   independiente (ADR-035).
6. **E2E real después de `1.0.0`.** `1.0.0` es la etiqueta de release de la base integrada. Una vez
   publicada, comienza el betatesting/E2E en servidores reales Spigot/Paper, Fabric y proxies
   Velocity. Los tests automatizados siguen siendo obligatorios y no se sustituyen por una prueba
   manual. E2E significa probar manualmente todas las features de la suite en un servidor real, no
   un smoke test de carga. Una plataforma no probada se declara **desconocida**, no aprobada. Los
   fallos del beta se corrigen en la línea beta, no en la 1.0; no se declara estabilidad ni
   producción hasta cerrar ese ciclo.
7. **Hito `1.0.0` y estabilidad `1.0`.** La etiqueta de release `1.0.0` se alcanza cuando la suite
   completa, la integración de todos los módulos y todas las features estén terminadas, no queden
   hallazgos P0/P1 abiertos y el gate completo esté verde. La versión estable `1.0` se declara
   después, cuando el proyecto completo demuestra ser estable, está consolidado y no tiene errores
   críticos de diseño. «Versión oficial» significa públicamente descargable; una release puede ser
   oficial y seguir en beta.
8. **`AUDITORIA.md` efímera.** El archivo contiene solo la auditoría vigente. Cuando todos sus bugs
   estén corregidos con tests de validación, se reemplaza por completo para el siguiente ciclo. El
   historial de auditorías vive exclusivamente en Git. El archivo no se modifica para dejar notas de
   metodología: su ciclo se declara aquí y se menciona desde `PLAN.md`. El estado operativo de la
   auditoría se mantiene en el PLAN.

**Consecuencias.** Los cambios incompatibles dejan de ser excepcionales: son el modo normal de
trabajo hasta la 1.0 estable. Cada cambio debe actualizar módulos, contratos, configuración, tests y
documentación en la misma unidad de trabajo. Las instalaciones que conserven rutas o formatos
antiguos no se migran en runtime. El E2E real añade tiempo y requiere entornos de prueba; a cambio,
la estabilidad y la producción no se declaran sobre una integración no verificada. La documentación
debe hablar de una sola base `1.0`, una base mínima de contrato `1.0.0` y un solo camino vigente por
contrato; los módulos legacy independientes se declaran como tales.

---

## ADR-035 — CoreTranslator como módulo legacy independiente

**Estado:** Aceptada (2026-11-04). El módulo vive en `src/coretranslator`.

**Contexto.** `CoreTranslator` proviene del proyecto anterior ChatTranslator y se conserva por su
utilidad como feature. No es una capa de compatibilidad dentro de otro módulo ni un shim: es una
implementación legacy independiente. La regla de no retrocompatibilidad se refiere a features y
contratos, no a la pluralidad de módulos; para módulos rige C6. El módulo llamado `src/coretranslator`
en `7dd164a4^` no era el CoreTranslator funcional: era un `LegacyBridge` que convertía
`common.legacy.ChatMessage` al `Message` actual. El CoreTranslator real es la expansión
PlaceholderAPI `me.majhrs16.cot.CoreTranslator`, que expone los placeholders `%cot_*`.

**Decisión.**

1. No restaurar el `LegacyBridge` de `7dd164a4^` ni recuperar `suite-common-legacy`: no aportan una
   feature, solo tipos de compatibilidad para el puente eliminado.
2. Restaurar la feature real desde el proyecto ChatTranslator: una expansión PlaceholderAPI con
   identificador `cot`, adaptada a los contratos actuales (`Actor`, `Message`, `TranslationService`,
   `MessageDispatch`, `UserLanguageStore`, `SyncBus`).
3. Mantenerla como módulo *deprecated*, independiente y mantenido. Es exclusivo de Bukkit porque
   PlaceholderAPI es un plugin Bukkit; puede importar Bukkit/Spigot/Paper y fija Java 21 por la API
   de Paper 1.21.4.
4. No se usa como puente interno ni como fallback de otros módulos. Los consumidores nuevos usan el
   contrato vigente. El módulo se declara `Platform: spigot` y no se instala en Fabric/Velocity.
5. Alinear versión, contrato y capability a `1.0.0`; el requisito es `chattranslator >= 1.0.0`.
6. Alcance restaurado: `translate`, `lang`, `set`/`get`, `new`, `send`, `broadcast`, `clone` y
   `sendDiscord`. No se restaura `var` (SpEL): el modelo actual es inmutable y exponer SpEL
   arbitrario en un placeholder reintroduce un agujero de seguridad. La lista explícita de canales
   de `sendDiscord` se acepta por compatibilidad, pero el `SyncBus` actual enruta a su sink
   configurado.
7. Añadir tests neutrales de gramática, contrato de módulo y adaptador PAPI. La prueba real en
   servidor Bukkit/Paper con PlaceholderAPI pertenece al E2E posterior a `1.0.0`.

**Consecuencias.** `suite-common-legacy` y el `LegacyBridge` siguen eliminados; no hay doble lector
ni adaptador interno. `CoreTranslator` es una expansión PAPI, no un motor de traducción ni un puente
de mensajes. Si PlaceholderAPI no está instalado, el módulo lo registra en el log y no expone
placeholders. `%cot_translate%` resuelve de forma síncrona porque PAPI exige un valor inmediato; se
apoya en la caché de `TranslationService`. `send`/`broadcast`/`clone` operan sobre mensajes creados
por `cot_new`/`cot_clone`; no capturan el historial del chat vivo. `CoreTranslator` no se elimina en
la purga P0.4; se mantiene como feature legacy.

---

## ADR-036 — Pipeline secuencial configurable, features opcionales, acciones sync y salidas sincronizadas por mensaje

**Estado:** Aceptada (2026-11-06). Enmienda ADR-019, ADR-024, ADR-025, ADR-032, ADR-008 y ADR-003
en los puntos que se indican; adopta ADR-031 en la semántica de routing (§16 regula la exposición de
`SET_CHANNEL` como acción agnóstica sin alterar D01–D05). §17 añade la selección de backend por
mensaje y enmienda ADR-032/ADR-035 solo en esa selección: el default y el fallback del hub y de la
fachada de traducción se conservan. La implementación y su estado se rastrean en `PLAN.md`.

**Contexto.** El diseño declara que todo feature es un paquete opcional, pero el composition root
decide lo contrario en tiempo de ejecución. `SuiteRuntime.rebuildPipeline()` exige ocho servicios
concretos (`ChannelRegistry`, `TranslationService`, `IflowHub`/`Router`, `MessageComponentRenderer`,
`MessageRenderer`, `ChatDelivery`, `ActorDirectory` y `PermissionChecker`) y, si falta uno, deja el
pipeline «unavailable» (`SuiteRuntime.java:203-211, 245-253`). Es `TF-001`: la suite no arranca
funcional sin `textformatter`, sin `chattranslator` o sin un motor iFlow. Además, el orden de
procesamiento está cableado en `SuiteHost.deliver()` y `MessageDispatcher.dispatch()`; el catálogo de
canales pertenece a `textformatter`; y los descriptores convierten features en requisitos de otras
features (`channel-api`, `chattranslator`).

El usuario ha fijado el criterio que gobierna esta decisión: **si falta iFlow, el pipeline no se
bloquea; continúa con la siguiente etapa**. La ausencia de una etapa es un hecho explícito y
trazable, no un fallo del pipeline.

### 1. Alcance y enmiendas

| Documento | Lo que se enmienda | Lo que se conserva |
|---|---|---|
| ADR-019 | El composition root puede exigir servicios de una feature; el grafo impone el orden. | Ciclo de vida, `ModuleHost`, classloaders, paquetes, guard de imports, «no doble camino». |
| ADR-024 | `SuiteHost` como ensamblador del render y el pipeline cableado. | `core-render` como módulo neutral de contratos con Adventure; `MessageComponentRenderer` como puerto. |
| ADR-025 | El adapter construye y posee el pipeline. | Adapters delgados, `PlatformPorts`, captura de eventos en `sync-*`; sinks `bukkit`/`fabric`. |
| ADR-032 | `SuiteRuntime` exige `IflowHub` y un `Router` no nulo para construir el pipeline. | Selección de backend por el hub, prioridad de fallback, configuración `config/iflow/config.yml`. |
| ADR-008 | El backend de traducción requiere `chattranslator` para activarse. | La fachada `ChatTranslator` y `TranslationService` como punto de uso de la traducción. |
| ADR-003 | La autoridad de un programa iFlow se limita a contenido y metadatos del mensaje. | Sandbox por módulo, SHA-256, allowlists, responsabilidad del repositorio. |

ADR-031 se adopta íntegra en la semántica de routing: `PolicyTarget` se reduce a `DELIVER`,
`CANCEL_EVENT` y `ERROR`; `SET_CHANNEL` es la única mutación de canal; `REDIRECT` y
`CHANNEL_REDIRECT` desaparecen. §16 no cambia esas reglas: convierte `SET_CHANNEL` en una acción
agnóstica disponible para terceros y retira su import dedicado, sin reintroducir los targets
retirados. §17 añade un override de backend por mensaje con el namespace `sync`; no cambia el orden del pipeline
ni el default ni el fallback de ADR-032/ADR-035.

### 2. Regla de independencia

1. **El núcleo arranca sin features.** Comandos, ciclo de vida, configuración, eventos y captura de
   plataforma no exigen ningún paquete de feature.
2. **Ninguna feature exige otra feature para arrancar.** Un descriptor no declara un `Requirement`
   sobre una capacidad publicada por una feature (`channel-api`, `formatter`, `router`,
   `chattranslator`, `sync-bus`, `stage:*`); solo puede requerir capacidades del entorno o de
   módulos técnicos neutrales (`api`, `jvm`, `kernel`, `manager-spi`, `platform`). El uso de un
   servicio de otra feature es **opcional en runtime**: si el proveedor no está instalado, solo falla
   la operación que lo necesita, de forma explícita, y el resto del pipeline sigue. Las capacidades
   `stage:*` son declarativas y nunca pueden ser objeto de un `Requirement`.
3. **Todo feature es opcional.** Instalar una feature no es requisito para arrancar; desinstalarla no
   invalida el pipeline, solo elimina su etapa o su servicio opcional.
4. **La ausencia de etapa es explícita.** Se registra como `SKIPPED` con su motivo y el mensaje
   continúa. No se convierte en error de configuración, en éxito falso ni en bloqueo.
5. **Un solo camino.** La lista `pipeline` es la única fuente de orden. No se conserva el pipeline
   cableado como fallback ni se deduce el orden del grafo o del orden de activación.
6. **El grafo se conserva para el ciclo de vida y los servicios neutrales**, no para imponer el orden
   del procesamiento.

### 3. Configuración canónica

La clave vive en `config/suite/config.yml` y se llama `pipeline`. `workflow` no se acepta como alias
ni como clave alternativa (ADR-034 prohíbe aliases).

```yaml
# Orden base instalado por defecto. No es un orden obligatorio.
pipeline:
  - route
  - format
  - sync
config-version: 1.0
```

Reglas:

- Cada entrada es un `stageId` estable, no un `moduleId`. El `moduleId` es un detalle de instalación;
  el `stageId` es el contrato del pipeline.
- El orden es literal: la primera etapa del archivo se ejecuta antes que la segunda dentro de la misma
  fase. La primera y la última entrada de una fase cierran su recorrido.
- `DefaultConfigInstaller` escribe la lista base al instalar. Si el archivo existe y le falta
  `pipeline`, `ConfigLoader` produce un error y el plan queda `INVALID`; no hay lista oculta de
  rescate. Si no existe el archivo entero, se aplican los defaults de primera instalación del
  instalador.
- Una lista vacía es válida y significa «sin etapas»: el pipeline queda `NO_PIPELINE` y el despacho
  devuelve ese estado explícito. No se entrega nada ni se finge éxito.
- Duplicados en la lista, `stageId` sintácticamente inválido o etapa no declarada por ningún módulo
  instalado producen `INVALID`. Un `stageId` declarado por un módulo instalado pero no activo, o no
  publicado por un módulo activo, produce `SKIPPED`.
- `sync` es terminal: cualquier entrada posterior a `sync` en la lista produce `INVALID`.

### 4. Catálogo de etapas y contrato

Cada módulo declara las etapas que puede publicar como capacidades `stage:<id>` en su descriptor, por
ejemplo `stage:route` o `stage:format`. La declaración permite distinguir «no instalado» (conocido o
desconocido) de «instalado pero inactivo». La publicación real es un servicio `PipelineStage`
registrado en el `ServiceRegistry` del módulo.

- Un módulo puede publicar varias etapas distintas.
- Dos módulos no pueden publicar el mismo `stageId`; la ambigüedad produce `INVALID`.
- `stageId` canónico: `^[a-z][a-z0-9-]*$`. Los ids base son `route` (`iflow`),
  `format` (`textformatter`) y `sync` (`sync-bus`). La traducción de plantillas no es una etapa:
  vive dentro de `format` mediante los spans `<tr>` y el puerto `TranslationService` (§11). Son ids
  de contrato, no una lista cerrada: una feature nueva puede declarar `stage:normalize` sin tocar el
  núcleo.

Los contratos viven en `core-api` bajo `me.majhrs16.suite.api.pipeline`. `core-api` sigue sin
depender de Adventure: el resultado renderizado se expresa con el marcador neutral `RenderedOutput`;
`core-render` aporta `ComponentRenderedOutput`, que envuelve el `Component` y es el único punto que
conoce Adventure.

```java
public interface PipelineStage {
    String id();
    Set<PipelineScope> scopes();

    default StageResult beforeMessage(PipelineMessage message, PipelineContext context) {
        return StageResult.continueWith(message);
    }
    default StageResult perRecipient(PipelineMessage message, PipelineContext context) {
        return StageResult.continueWith(message);
    }
    default StageResult afterMessage(PipelineMessage message, PipelineContext context) {
        return StageResult.continueWith(message);
    }
}
```

```java
public record PipelineMessage(
        Message message,
        Actor recipient,              // null en MESSAGE_PRE y MESSAGE_POST
        RouteDecision decision,       // null si no hay etapa route
        RenderedOutput rendered,      // null hasta que la etapa format lo produce
        boolean cancelEvent
) {}

public interface PipelineContext {
    String messageId();
    PipelineScope scope();
    Optional<ChannelRegistry> channels();   // catálogo de textformatter; vacío si no está activo
    ActorDirectory actors();
    PermissionChecker permissions();
    PipelineSettings settings();
    PipelineServices services();      // find/findAll de puertos neutrales
    OutputPlan outputs();             // plan efectivo resuelto (§17)
    PluginLogger logger();
    PipelineTrace trace();
    long planVersion();
}
```

`PipelineSettings` es un subconjunto neutral de `HostConfig` (`defaultLanguage`, `engineParallel`,
`soundEnabled`). `OutputPlan` se define en §17.3. `HostConfig` se conserva como configuración del
host, pero no cruza la frontera del pipeline. Las etapas reciben sus dependencias concretas por
constructor desde su propio módulo: la etapa `format` ya tiene su `TextFormatter`, la etapa `route`
su `Router` y la etapa `sync` su `SyncBus`. El contexto solo expone servicios transversales
neutrales. `channels()` es opcional: refleja el catálogo de `textformatter` cuando está activo y
queda vacío cuando no lo está. Una etapa que lo necesite debe comprobarlo y fallar solo su
operación, con un error explícito; la ausencia nunca invalida el plan ni el resto del pipeline.

**Fases.** El motor recorre la misma lista ordenada en tres fases:

1. `MESSAGE_PRE`: una vez por emisión, antes de expandir destinatarios. Sirve para rate limit de
   emisión y normalizaciones globales. La etapa `route` puede declarar esta fase.
2. `RECIPIENT`: una vez por destinatario. Aquí viven `route` y `format`; la traducción de `<tr>` se
   resuelve dentro de `format`.
3. `MESSAGE_POST`: una vez por emisión, después de las entregas. Aquí vive `sync`.

Entre `MESSAGE_PRE` y `RECIPIENT` el motor materializa los destinatarios con `ActorDirectory`. Esa
expansión no es una etapa opcional: es la definición de «entrega» y no depende de ninguna feature.
Una etapa declara en `scopes()` las fases en las que participa; el motor solo invoca los métodos
correspondientes. El orden dentro de cada fase es el de la lista.

### 5. Semántica de resultados

| Resultado | Efecto en la unidad | Traza | ¿Termina el pipeline? |
|---|---|---|---|
| `CONTINUE` | Sustituye el mensaje por el devuelto y sigue. | `RUN` | No. |
| `DROP` | Descarta la unidad sin entregar. | `DROP` + motivo | Sí, para esa unidad. |
| `REJECT` | Descarta y marca rechazo explícito (equivalente a `REJECT` de ADR-031). | `REJECT` + motivo | Sí, para esa unidad. |
| `ERROR` | Falla la unidad; no se entrega y se registra el error. | `ERROR` + motivo | Sí, para esa unidad. |
| `NOT_APPLICABLE` | No transforma; sigue con el mismo mensaje. | `NOT_APPLICABLE` | No. |

`ERROR` no se degrada a éxito ni se reintenta en silencio. `DROP` y `REJECT` no lanzan excepción al
adaptador: se contabilizan en el resultado del despacho.

**Etapa ausente.** Si una entrada de la lista no tiene etapa publicada, el motor registra `SKIPPED`
con el motivo (`module-inactive` o `stage-unpublished`) y continúa. El pipeline global sigue siendo
válido. En particular:

| Etapa ausente | Comportamiento por defecto | Traza |
|---|---|---|
| `route` | Se entrega a los destinatarios expandidos. El default deny de ADR-031 pertenece a la política del motor iFlow instalado, no a la ausencia de motor. | `SKIPPED` + `DEFAULT_ALLOW` |
| `format` | Se entrega texto plano neutral (`Component.text`). | `SKIPPED` + `PASS_THROUGH` |
| `sync` | No hay fan-out a sinks. | `SKIPPED` |

La traducción no aparece en esta tabla porque no es una etapa. Si `chattranslator` no está
instalado, `format` sigue funcionando con el servicio vacío y los spans `<tr>` conservan su
contenido sin traducir; no se registra una etapa `translate` ausente.

Si la lista no tiene ninguna etapa efectiva, el estado es `NO_PIPELINE`: no se entrega nada y el
resultado del despacho lo declara. `MessageDispatch.dispatch()` no lanza excepción en ese caso
(romper el evento de plataforma sería peor), pero registra un `warn` una sola vez por versión del
plan y deja el estado en la traza. Los accesos de diagnóstico (`SuiteRuntime.pipelineStatus()`,
`pipelineError()`) sí fallan de forma explícita.

### 6. Traza por etapa

`PipelineTrace` acumula, por despacho, una entrada `StageTrace` por etapa y fase ejecutada o saltada:

```java
public record StageTrace(
        String stageId,
        String moduleId,
        PipelineScope scope,
        Actor recipient,        // null fuera de RECIPIENT
        StageStatus status,     // RUN, SKIPPED, DROP, REJECT, ERROR, NOT_APPLICABLE
        String detail,
        long durationNanos
) {}
```

El runtime conserva las últimas 256 trazas en un búfer circular; `tester` las consume mediante
`TestProbe.lastTrace(String messageId)` y `TestProbe.recentTraces()`. El punto exacto de fallo de P12
es la primera entrada con `ERROR` o `REJECT` para el `message.id()`; si no hay ninguna, el fallo está
en la entrega y la traza lo indica con una entrada terminal del motor. La traza no captura el
contenido del mensaje ni datos personales, y no sustituye a las métricas.

### 7. `SET_CHANNEL` y segunda fase de routing

`route` se ejecuta una vez por destinatario. Si la decisión incluye `SET_CHANNEL` y el nuevo canal
puede cambiar permisos o destinatarios, el motor ejecuta una **segunda pasada de routing solo para los
destinatarios afectados**, con `phase=secondary`:

- No hay reentrega recursiva ni reentrada en `format` o `sync`.
- Una segunda mutación de canal en la misma unidad produce `DECISION_CONFLICT` y la unidad se
  rechaza, conforme a ADR-031.
- Si el cambio de canal añade destinatarios, se añaden a la lista de entrega y se les aplica la
  pasada secundaria de routing.
- La consola es un actor (`Actor.console()`); no hay `REDIRECT` ni `CHANNEL_REDIRECT`.

### 8. Recarga en caliente

`reloadPipeline()` relee `config/suite/config.yml` y, si `textformatter` está activo, su catálogo de
canales; construye un `PipelinePlan` inmutable y lo publica con un swap atómico. Estados:

- `APPLIED`: el plan nuevo es válido y se publicó. Los despachos en vuelo conservan el plan anterior.
- `FAILED`: el archivo es inválido o una etapa lanzó al construirse. Se conserva el plan anterior y
  se devuelve el detalle.
- `RESTART_REQUIRED`: el plan anterior no pudo drenarse en el plazo acordado o un módulo dejó recursos
  vivos. No se declara éxito.

Cambiar la lista no reinicia módulos ni recarga classloaders. Instalar, desinstalar o actualizar un
paquete reconstruye el plan después de confirmar la activación o desactivación, igual que hoy.

### 9. Sustitución de `SuiteHost` y `MessageDispatcher`

El pipeline configurable **sustituye** el cableado actual; no lo envuelve ni conserva como fallback:

- `SuiteHost` desaparece como fachada. Sus accesos pasan a `PipelineContext` (lectura) y a
  `SuiteRuntime` (diagnóstico y administración).
- `MessageDispatcher` se divide en tres piezas neutrales:
  `PipelineEngine` (orden, fases, traza, resultado), `RecipientResolver` (expansión de `Direction` con
  `ActorDirectory`) y `DeliveryExecutor` (pool acotado, timeout, `sleepMillis` y cierre determinista).
- `RoutingResult` y `DispatchReport` se retiran. `DispatchOutcome` gana `PipelineStatus status`,
  `int considered` e `int failed`; todos sus consumidores se actualizan en el mismo cambio.
- `MessageDispatch` conserva su firma. `dispatchAndReport` es obligatorio y devuelve el estado real
  del pipeline; una implementación que no pueda informar debe declararlo, nunca fabricar un éxito.
  `dispatch` es la variante sin informe.
- `TestProbe` gana `lastTrace`/`recentTraces` mediante el puerto neutral `PipelineTraceSource` y
  pierde los métodos que expongan `SuiteHost`.

No queda un segundo camino de composición en `host`, en los adapters ni en los tests de producción.

### 10. Catálogo de canales

- Los contratos `Channel` y `ChannelRegistry` ya viven en `core-api`; se conservan.
- El catálogo **sigue perteneciendo a `textformatter`**. `TextFormatterActivator` lo carga desde
  `config/textformatter/channels/*.yml` y publica `ChannelRegistry` y `channel-api`. No se traslada
  a `suite-runtime`, no se crea `ChannelCatalogLoader` ni un módulo `channel-catalog`, y la ruta no
  se mueve.
- El routing de canal —resolución, permisos, políticas y `SET_CHANNEL`— es una función intrínseca
  de `textformatter`; no tiene sentido como servicio neutral del runtime.
- `iflow-*`, `inworld`, `sync-bukkit`, `sync-fabric` y `tester` consumen el catálogo de forma
  **opcional** desde el `ServiceRegistry`; no declaran `Requirement` sobre `channel-api`. Si
  `textformatter` no está instalado, el pipeline arranca y esas features funcionan sin catálogo;
  solo falla la operación concreta que lo necesite, con un error explícito.
- `SET_CHANNEL` deja de ser un import dedicado y pasa a `textformatter.channel.set` por la interfaz
  sync (§16.5). Si `textformatter` no está, la acción devuelve `NotFound`/`Unsupported` cuando se
  invoca; no bloquea iFlow ni el resto de la suite. Es el mismo patrón que un sink no instalado.
- Ruta canónica: `config/textformatter/channels/`, que se conserva. `config/suite/channels/` no es
  una ruta válida y no se introduce; `config/channels/` (ruta plana) sigue prohibida por ADR-034.
  Este concepto no cambia `storage.md` ni `config-schema-v2.2.md`.
- `textformatter` mantiene `provide("channel-api")` y añade `provide("stage:format")`.

### 11. Traducción

La traducción de plantillas no es una etapa del pipeline. Vive en `textformatter`, dentro del render
de la etapa `format`, y se aplica exclusivamente a los spans `<tr>...</tr>` de la plantilla.
Consecuencias:

- `TemplateRenderer` resuelve placeholders y `%content%`, traduce cada span `<tr>` con el puerto
  neutral `TranslationService` y solo entonces parsea MiniMessage. El resultado traducido se escapa
  para que no se interprete como markup.
- `<tr>` conserva su semántica de span: se traduce únicamente el contenido marcado, no el mensaje
  entero. Esto evita que la traducción se independice del contrato de plantilla y rompa `<tr>`.
- `chattranslator` conserva la fachada, `TranslationService` y los proveedores; **no publica una
  etapa `translate`**. `textformatter` lo consume de forma opcional: si no está instalado, `<tr>`
  deja el contenido intacto y el resto del pipeline sigue.
- El orden base es `route` → `format` → `sync`. `translate` no figura en `pipeline` ni en
  `DefaultConfigInstaller`; una configuración que lo declare es inválida por etapa no declarada, no
  un skip silencioso.
- `TranslationService` conserva su contrato en `core-api`; no cambia su firma.
- Los backends `gtranslate`, `ltranslate` y `coretranslator` dejan de requerir `chattranslator` para
  activarse. Publican `TranslatorProvider` y quedan inertes si la fachada no está instalada. La
  fachada sigue siendo el único punto de uso de la traducción y descubre proveedores por `findAll` y
  `ServiceLoader`, como ya hace.
- Un backend sin fachada no traduce nada, pero no bloquea el arranque ni el resto del pipeline.

### 12. Sincronización

`sync-bus` publica una única etapa terminal `sync`, con fase `MESSAGE_POST` y fan-out best-effort a
todos los sinks registrados. Los sinks no son etapas: publican `SyncSink` y el bus los descubre; no
imponen orden. `sync` se ejecuta una vez por emisión, después de las entregas, y no altera el
mensaje. Si el bus no está instalado, la etapa se salta y el resto del pipeline no cambia.

El fan-out oficial usa solo el mínimo común múltiplo (`backend.info`, `message.send`). La superficie
nativa de cada backend y las acciones agnósticas para terceros se definen en §16.

### 13. Qué mensajes recorren el pipeline

Todos los `Message` inyectados por `MessageDispatch` recorren el mismo pipeline, con independencia de
`MessageType` (chat, join/quit/death, sistema o evento). Los adaptadores deciden cómo capturan el
evento y si lo cancelan (`CANCEL_EVENT`); eso no es una etapa de feature. Una etapa que no aplique a
un tipo devuelve `NOT_APPLICABLE` y la traza lo registra.

### 14. Guard de independencia

`checkArchitecture` gana una regla: ningún descriptor de `src/*` puede declarar un `Requirement`
sobre una capacidad publicada por una feature. La regla se implementa en la tarea Gradle contra una
lista de capacidades neutrales publicadas por el entorno y por los módulos técnicos (`api`, `jvm`,
`kernel`, `manager-spi`, `platform`), y se refuerza con un test que construye el grafo y comprueba
que una feature no puede satisfacer el requisito de otra. Las capacidades `stage:*` se validan como
`provide` y se rechazan como `require`. El consumo opcional en runtime no se declara como
`Requirement`; el guard no impide buscar un servicio ausente y degradar solo la operación que lo
necesita. El guard falla el build; no se degrada a advertencia.

### 15. Migración de descriptores

El cambio de modelo obliga a corregir los descriptores en la misma unidad de trabajo:

| Módulos | Antes | Después |
|---|---|---|
| `iflow-*`, `inworld`, `sync-bukkit`, `sync-fabric`, `tester` | `require("channel-api")` | sin requisito; consumen `ChannelRegistry` de forma opcional en runtime |
| `gtranslate`, `ltranslate`, `coretranslator` | `require("chattranslator")` | sin requisito; publican `TranslatorProvider` |
| sinks `sync-*` | `require("sync-bus")` | sin requisito; publican `SyncSink` y el bus los descubre |
| `tester` | `require("formatter")`, `require("router")` | sin requisito; sondas opcionales con `SKIPPED` |
| `textformatter` | `provide("channel-api")` | conserva `provide("channel-api")` y añade `provide("stage:format")`; sigue siendo dueño del catálogo |
| `iflow` | — | `provide("stage:route")` |
| `chattranslator` | — | publica `TranslationService` y proveedores; no publica etapa `translate`, porque `<tr>` se resuelve dentro de `format` |
| `sync-bus` | — | `provide("stage:sync")`; descubre `SyncSink` |

`DefaultSyncBus` deja de depender de que cada sink lo busque al activarse: indexa los `SyncSink`
publicados y se suscribe a `ModuleActivatedEvent`/`ModuleDeactivatedEvent`, igual que hace el hub de
iFlow con sus backends.

### 16. Superficie de acciones de los backends sync

#### 16.1 Principio

El backend conoce su plataforma; el núcleo no. `sync-discord` conoce canales, roles, mensajes,
miembros, bans y auditoría de Discord; `sync-telegram` conoce chats, mensajes, miembros y permisos de
Telegram; `sync-http` conoce su endpoint y sus cabeceras; `sync-bukkit` conoce jugadores, consola,
canales y eventos de Bukkit; `sync-fabric` conoce los equivalentes de Fabric. Ese conocimiento no se
compila en `core-api`: se publica como un esquema de datos y se ejecuta en el backend.

La suite oficial usa solo el mínimo común múltiplo (LCM) de todos los backends. Los programas de
iFlow de terceros pueden descubrir y ejecutar cualquier acción nativa que el backend declare, siempre
que el programa la declare y el operador la conceda. Sin concesión no hay acceso: la superficie es
amplia, la autoridad no es ambiente.

La etapa terminal `sync` la publica `sync-bus`; no se añade un módulo nuevo. Su registro de sinks, colas,
reintentos y métricas se reutilizan; la selección por mensaje se define en §17.

#### 16.2 Tres niveles

| Nivel | Quién lo usa | Acciones |
|---|---|---|
| Agnóstico oficial (LCM) | pipeline `sync`, features oficiales, `CoreTranslator.sendDiscord` | `backend.info`, `message.send`, `message.deliver` |
| Agnóstico extendido | programas de terceros | `textformatter.channel.set` y las acciones agnósticas que se acuerden |
| Nativo | programas de terceros | `<backend>.<segmento>[...].<verbo>`: `discord.channel.create`, `discord.channel.permission.overwrite`, `discord.role.assign`, `discord.message.edit`, `discord.member.ban`, `telegram.chat.member.ban`, `bukkit.event.cancel`, etc. |

La profundidad no está limitada a tres segmentos: el primer segmento es el backend (namespace) y el
resto es el id local de la acción. `Principal.Official` solo puede invocar el LCM; `Principal.Program`
solo puede invocar lo que tenga concedido. El bus lo verifica; no es una convención.

#### 16.3 Contratos

```java
public interface SyncSink {                 // evoluciona; no se envuelve
    String name();
    SyncSurface surface();
    SyncResult invoke(SyncCall call);
    SyncResult deliver(DeliveryEnvelope envelope); // por defecto: send(message)
    // start/stop/setListener se conservan; send(Message) se sustituye por message.send
}

public record SyncSurface(
        String backend,                     // namespace: "discord", "telegram", "textformatter"
        SemVer contractVersion,
        Set<SyncActionSpec> actions
) {}

public record SyncActionSpec(
        String id,                          // id local: "message.send", "message.deliver",
                                            // "channel.create", "channel.permission.overwrite"
        ActionKind kind,                    // READ, WRITE, DESTRUCTIVE, MODERATION
        List<SyncParamSpec> params,
        SyncParamSpec result,               // null si no devuelve valor
        boolean idempotent,
        long timeoutMillis
) {}

public enum ActionKind { READ, WRITE, DESTRUCTIVE, MODERATION }

public enum SyncValueType {
    STRING, INTEGER, BOOLEAN, DURATION,
    ACTOR_REF, CHANNEL_REF, MESSAGE_REF, ROLE_REF, DELIVERY_REF, OBJECT
}

public record SyncParamSpec(
        String name,
        SyncValueType type,
        boolean required,
        SyncParamSpec item,               // solo para listas; null en el resto
        boolean secret                    // redactar el valor antes de auditar
) {}

public record DeliveryUnit(
        Actor recipient,                    // destinatario de la unidad
        RouteDecision decision,             // null cuando no aplicó routing
        Message message,                    // mensaje por destinatario tras route
        RenderedOutput rendered             // salida de format
) {}

public record DeliveryEnvelope(
        Message message,                    // mensaje que produjo las unidades
        List<DeliveryUnit> units            // unidades renderizadas, en orden
) {}

public record DeliverySummary(
        int delivered, int silenced, int redirected, int failed,
        int queued, int discarded
) {}

public interface LocalDelivery {            // puerto neutral, lo implementa el host
    DeliverySummary deliver(DeliveryEnvelope envelope);
}

public sealed interface Principal {
    record Official(String moduleId) implements Principal {}
    record Program(String sha256) implements Principal {}
}

public record SyncCall(
        String backend,                     // "discord", "telegram", "textformatter"
        String action,                      // id local; canónico = backend + "." + action
        Map<String, Object> args,           // tipos de SyncParamSpec, sin tipos de SDK
        Principal principal,                // Official(moduleId) | Program(sha256)
        String idempotencyKey,
        String reason
) {}

public sealed interface SyncResult {
    record Ok(Object value) implements SyncResult {}
    record Denied(String reason) implements SyncResult {}
    record Unsupported(String reason) implements SyncResult {}
    record InvalidArgument(String reason) implements SyncResult {}
    record NotFound(String reason) implements SyncResult {}
    record Conflict(String reason) implements SyncResult {}
    record RateLimited(long retryAfterMillis) implements SyncResult {}
    record Timeout(String reason) implements SyncResult {}
    record HostFailure(String reason) implements SyncResult {}
}

public interface SyncBus {
    Set<SyncSurface> surfaces();
    SyncResult invoke(SyncCall call);
    List<SyncResult> broadcast(SyncCall call);              // fan-out del LCM
    List<SyncResult> send(Message message);                 // comodidad sobre message.send
    List<SyncResult> sendTo(String backend, Message message); // dirigido; misma ruta
    List<SyncResult> deliver(DeliveryEnvelope envelope);     // comodidad sobre message.deliver
    List<SyncResult> deliverTo(List<String> backends, DeliveryEnvelope envelope,
                               ParallelMode mode);           // dirigido; misma ruta
}
```

`SyncParamSpec` tipa los argumentos como datos neutrales (`STRING`, `INTEGER`, `BOOLEAN`, `DURATION`,
`ACTOR_REF`, `CHANNEL_REF`, `MESSAGE_REF`, `ROLE_REF`, `DELIVERY_REF`, `OBJECT`). `OBJECT` es un mapa
anidado validado por el backend; el núcleo no interpreta su contenido. `DELIVERY_REF` transporta un
`DeliveryEnvelope` y no un tipo de SDK. `secret` marca el parámetro cuya representación se redacta
antes de hashear para la auditoría; no altera la validación ni el valor que recibe el backend.
Ningún tipo de JDA, Telegram, Bukkit o Fabric cruza esta
frontera. `backend` es el namespace; las acciones del host usan `textformatter`, no `"*"`. El nombre
canónico de una acción es `<backend>.<action>` y admite profundidad arbitraria.
La capacidad se deriva del origen: `sync.<action>` para acciones de sink y
`module.<moduleId>.<action>` para acciones de módulo/host. `SyncSurface` solo lo publican los sinks;
las acciones del host no tienen superficie y el bus las resuelve en su handler interno.

#### 16.4 Mínimo común múltiplo

- `backend.info`: `READ`, idempotente; devuelve nombre, versión y versión de superficie.
- `message.send`: `WRITE`, idempotente por `idempotencyKey`; argumentos `message` (`MESSAGE_REF`) y
  `destination` (`CHANNEL_REF`, opcional).
- `message.deliver`: `WRITE`, **no idempotente** (no se reintenta); argumento `delivery`
  (`DELIVERY_REF`). Transporta el `DeliveryEnvelope` con las unidades ya renderizadas por
  destinatario. Es el camino del pipeline y de la entrega local; un sink remoto puede ignorar las
  unidades y entregar el mensaje, pero un sink local exige el sobre.
- `SyncBus.send(Message)` es una comodidad tipada sobre `message.send`; no es un segundo camino.
- `SyncBus.sendTo(backend, Message)` es la forma dirigida de `message.send`; usa la misma ruta y
  devuelve un resultado por backend, no un booleano de aceptación.
- `SyncBus.deliver(DeliveryEnvelope)` es una comodidad tipada sobre `message.deliver`; deduplica por
  id de mensaje. `SyncBus.deliverTo(backends, envelope, mode)` es la forma dirigida y no deduplica:
  la decisión explícita del llamante prevalece.
- Un backend que no pueda implementar el LCM no se publica: no existe un `send` que finja éxito.
- La entrega física local no la hace el sink: la hace el host a través del puerto neutral
  `LocalDelivery`. El sink local (`sync-bukkit`, `sync-fabric`) resuelve el puerto en cada entrega y
  devuelve `Ok(DeliverySummary)` con los contadores reales; sin puerto devuelve `HostFailure`, y
  `message.send` sobre un sink local devuelve `Unsupported` porque el camino local exige el sobre.
- `DeliverySummary` distingue `queued` (unidad `RATE_LIMIT` aceptada por la cola acotada, pendiente de
  entrega) de `discarded` (unidad rechazada porque la cola está llena). Ninguna de las dos cuenta como
  `delivered`; el descarte es explícito y observable.

#### 16.5 `textformatter.channel.set`

- Acción agnóstica del host (`backend = "textformatter"`), `WRITE`; argumento `channel`
  (`CHANNEL_REF`).
- Equivale a `SET_CHANNEL` de ADR-031 sobre `Message.channel`: cambia el canal de routing y dispara la
  segunda fase de §7. Una sola mutación por unidad; la segunda produce `DECISION_CONFLICT`.
- iFlow oficial no la invoca: no pertenece al LCM y `Principal.Official` no puede invocarla. Un
  programa de terceros puede hacerlo con la capacidad `module.textformatter.channel.set`.
- El cambio de destino nativo de un backend no se hace con `textformatter.channel.set`: se hace con
  una acción nativa o con el argumento `destination` de `message.send`. La acción nunca significa
  implícitamente «canal de Discord/Telegram».
- El import dedicado `set_channel` de la ABI v3 se retira; se sustituye por
  `sync_invoke("textformatter", "channel.set", ...)`. No hay alias ni doble lector (ADR-034).
- `textformatter` es el namespace del host/suite; no crea una dependencia del módulo `textformatter`
  ni lo convierte en un backend de entrega. La capacidad es `module.textformatter.channel.set`: `textformatter`
  es una excepción de namespace de módulo, no un backend sync.

#### 16.6 Autoridad, concesiones y auditoría

1. **Declaración.** El programa declara las capacidades que usa en su imagen
   (`ProgramImage.capabilities`) con el formato correspondiente al origen de la acción:
   `sync.<backend>.<accion>` para acciones de sink (por ejemplo `sync.discord.channel.create` o
   `sync.discord.channel.permission.overwrite`) y `module.<moduleId>.<accion>` para acciones de
   módulo/host (por ejemplo `module.textformatter.channel.set`). El id de la acción sigue siendo
   `<backend>.<accion>`; la capacidad añade el prefijo `sync.` o `module.`.
2. **Concesión.** El operador las autoriza en `config/iflow/grants.yml`, indexadas por el
   SHA-256 de la imagen del programa, no por su nombre. La concesión puede usar comodines
   (`sync.discord.*`, `module.textformatter.*`); el programa debe declarar la acción exacta; la
   autoridad efectiva es la intersección.
3. **Destructivo.** `DESTRUCTIVE` y `MODERATION` exigen `allow-destructive: true` en la concesión.
   Sin esa marca, la acción devuelve `Denied` y no produce efecto.
4. **Fail-closed.** Permiso ausente, ambiguo o no verificable → `Denied`. No se hereda el fail-open
   de `PERMISSION`/`WORLD` (TF-002).
5. **Cuotas.** Timeout por acción (`timeoutMillis`), llamadas por minuto por programa, coste de fuel
   por llamada de host y tamaño máximo de payload. El presupuesto de llamadas es una ventana
   deslizante de 60 s: cada llamada autorizada caduca exactamente un minuto después, así que el
   límite no se duplica al cruzar un borde de ventana. El presupuesto agotado devuelve
   `RateLimited` con el reintento en milisegundos; un payload que excede `max-payload-bytes` devuelve
   `Denied`, no `RateLimited`, porque repetir los mismos argumentos nunca cabría. Ningún agotamiento
   produce un éxito parcial.
6. **Auditoría.** Registro append-only con hash del programa, backend, acción, actor, hash de
   argumentos (nunca valores), resultado y duración. Los argumentos marcados como secreto se
   redactan; un argumento que la acción no declara también se redacta (fail-closed). El hash es
   determinista: ordena las claves y sustituye los valores redactados por una marca fija. Una acción
   de programa exige que el registro sea escribible antes de ejecutarse; un llamador oficial se
   audita best-effort para no detener el pipeline. La ruta es `<data>/config/iflow/audit.log`
   (`storage.md` §5); cambiarla requiere autorización.
7. **Idempotencia.** Solo se reintenta una acción marcada `idempotent`; la clave es
   `message.id() + action + target`. Las acciones no idempotentes no se reintentan. `message.deliver`
   es no idempotente: un reintento duplicaría la entrega local, así que el sobre se intenta una vez.
8. **Sin transacción.** Un efecto nativo (ban, creación de canal, edición) no se revierte si el
   mensaje falla después. No hay rollback entre backends ni compensación automática. El operador
   concede acciones destructivas con esa consecuencia explícita.
9. **Punto de ejecución.** Las acciones se ejecutan en la etapa `route` (iFlow), antes de `format`
   y `sync`. El puente devuelve un estado; la política decide si entrega. `Denied` nunca tiene
   efecto. Una acción no es una decisión de routing: no consume la única decisión de ADR-031.
10. **Aislamiento.** Un fallo del backend se devuelve como `HostFailure` y no tumba el bus ni el
    pipeline; un fallo del programa se rige por §5.

#### 16.7 Puente iFlow

- VM: dos servicios `HOST` genéricos, `SYNC_SURFACE` (`0x0800`) y `SYNC_INVOKE` (`0x0801`), con
  payload TLV y transferencia por DMA; el coste se carga al *fuel budget* existente.
- Wasm v3: dos imports `sync_surface` y `sync_invoke` con el mismo TLV y los mismos estados.
- No se añade una instrucción ni un import por acción. Es la aplicación directa del principio de
  `iflow-cpu-design.md` §10: el CPU no conoce `SET_CHANNEL`; el runtime ofrece una operación
  autorizada y genérica.
- Ambos motores comparten la misma suite de conformidad; la paridad VM/Wasm es parte de W3.5.
- `sync.invoke` es la capacidad del servicio genérico, distinta de las capacidades de acción
  (`sync.discord.*`, `module.textformatter.*`): un programa declara ambas. Las políticas oficiales
  que se distribuyen con la suite no declaran `sync.invoke`; un test lo verifica. Solo los programas
  de terceros lo declaran.

##### 16.7.1 Codec y layout de cable

El codec es único y vive en `core-api` (`me.majhrs16.suite.api.iflow.TlvCodec`), no en cada motor.
Un valor se codifica como `[type:u8][length:u32 LE][payload]`; los tipos son `NULL=0`, `STRING=1`,
`I64=2`, `BOOL=3`, `BYTES=4`, `LIST=5` y `MAP=6`. Una lista es la concatenación de valores TLV y un
mapa es la concatenación de pares `[clave string][valor]`, en orden de inserción y sin claves
duplicadas. Las cadenas son UTF-8 estricto: no se truncan caracteres ni se aceptan surrogates
sueltos. `NULL` es ausencia explícita y no equivale a cadena vacía.

El contrato de cable vive en `core-api` (`me.majhrs16.suite.api.sync.SyncBridgeCodec`). Ambos
motores lo usan tal cual; ningún motor redefine el formato.

| Operación | Petición | Respuesta |
|---|---|---|
| `sync_surface` | TLV string: namespace del backend; vacío = todos | TLV list de superficies |
| `sync_invoke` | TLV map: `backend`, `action`, `args` | TLV map: `status` + (`result` o `reason`) |

Una superficie es un mapa `backend`, `contractVersion`, `actions`. Una acción es un mapa `id`,
`kind`, `params`, `result`, `idempotent`, `timeoutMillis`. Un parámetro es un mapa `name`, `type`,
`required`, `item`, `secret`. Las superficies se ordenan por `backend` y las acciones por `id` para
que la codificación sea determinista. Los argumentos y el resultado usan el mapeo neutral de
`SyncBridgeCodec`: `null`, string, i64, bool, bytes, lista y mapa.

##### 16.7.2 Estados

Los nombres de `status` son canónicos y no dependen del motor. Cada motor los proyecta a su registro
de estado; el payload no cambia.

| `status` | VM (`ServiceResult.Status`) | Wasm (`PolicyAbiV3`) |
|---|---|---|
| `OK` | `OK` (0) | `STATUS_OK` (0) |
| `NOT_FOUND` | `NOT_FOUND` (1) | `STATUS_NOT_FOUND` (1) |
| `INVALID_ARGUMENT` | `INVALID_ARGUMENT` (3) | `STATUS_INVALID_ARGUMENT` (3) |
| `DENIED` | `DENIED` (2) | `STATUS_DENIED` (4) |
| `CONFLICT` | `CONFLICT` (6) | `STATUS_CONFLICT` (5) |
| `HOST_FAILURE` | `HOST_FAILURE` (7) | `STATUS_HOST_FAILURE` (6) |
| `UNSUPPORTED` | `UNSUPPORTED` (5) | `STATUS_UNSUPPORTED` (7) |
| `RATE_LIMITED` | `RATE_LIMITED` (10) | `STATUS_RATE_LIMITED` (8) |
| `TIMEOUT` | `TIMEOUT` (9) | `STATUS_TIMEOUT` (9) |

La numeración de cada motor es la suya y no se reordena; la paridad se garantiza en el nombre del
payload y en el mapeo, no en el valor numérico. `RATE_LIMITED` viaja con el retry en milisegundos:
la VM lo deja además en `R4`.

##### 16.7.3 Convención de buffer

- **VM.** `R2` es la base del buffer y `R3` su capacidad. La respuesta se escribe **sobre** la
  petición: al salir, `R3` son los bytes escritos. Solo se lee el primer TLV (`decodePrefix`), de
  modo que el relleno de la capacidad no se confunde con bytes sobrantes. Si la respuesta no cabe,
  `BUFFER_TOO_SMALL` deja el tamaño requerido en `R3`.
- **Wasm.** `sync_surface(inPtr, inLen, outPtr, outCap) -> status` y
  `sync_invoke(inPtr, inLen, outPtr, outCap) -> status`. La entrada tiene longitud exacta; la salida
  se escribe completa o devuelve `BUFFER_TOO_SMALL`, y si `outCap >= 4` se escribe la longitud
  requerida en little-endian para reintentar.
- El servicio decodifica exactamente el primer TLV del buffer de petición; el resto se trata como
  relleno de capacidad y no como datos. Un payload truncado o con un tipo incorrecto devuelve
  `INVALID_ARGUMENT`; no se adivina.

##### 16.7.4 Autoridad, despacho y mutación

- El principal es `Principal.Program(SHA-256)`. La VM usa `ProgramImage.hash()`; Wasm usa el
  SHA-256 verificado de `policy.wasm` en `reload()`. Un programa sin hash verificado recibe
  `UNSUPPORTED`: no se inventa una identidad.
- El namespace decide el despachador: si el backend es un sink registrado se usa
  `SyncBus.invoke`; en cualquier otro caso se usa `SyncBus.invokeHost` con el `HostActionContext`
  de la unidad. `textformatter` es el namespace del host y no un sink.
- Una acción que devuelve `Ok(Message)` muta el mensaje de trabajo: la VM journaliza el diff
  (`SET_CHANNEL`, `SET_TEXT`, idiomas, color) y Wasm actualiza la sesión. Por el cable el programa
  recibe `Ok(null)`: el efecto es el mensaje, no un valor.
- Una segunda mutación de canal en la misma unidad es `CONFLICT` y no se aplica. En la VM la
  detección mira el journal (`SET_CHANNEL`/`REDIRECT_CHANNEL`); en Wasm compara el mensaje de trabajo
  con el original.
- El bus se resuelve con un `Supplier` por invocación: `iflow-16`, `iflow-32` e `iflow-wasm` pueden activarse
  antes que `sync-bus`. Sin bus, `sync_surface`/`sync_invoke` devuelven `UNSUPPORTED`. El bus
  resuelve también la concesión (`ProgramGrantStore`) y la auditoría (`ActionAudit`) con suppliers:
  sin concesión, sin auditoría escribible o sin cuota, la acción devuelve `DENIED` o
  `RATE_LIMITED` y no tiene efecto.
- El puente no crea un presupuesto de fuel nuevo: usa el base y el coste por byte del registro de
  servicios. `sync.invoke` no se concede por declarar una capacidad de acción.

#### 16.8 Enmiendas y límites

- **ADR-003.** Se añaden invariantes: concesión por hash, marca destructiva, auditoría, fail-closed y
  cuotas. Las firmas siguen siendo opcionales; la concesión es del operador, no una prueba de
  autoría.
- **ADR-031.** D01–D05 no cambian. `textformatter.channel.set` reutiliza la segunda fase de D02 y
  retira el import dedicado, sin reintroducir `REDIRECT` ni `CHANNEL_REDIRECT`.
- **ADR-019.** Los `sync-*` dejan de requerir `sync-bus` (§15); publican `SyncSink` y el bus los
  descubre.
- **`storage.md` §1 (Terminología), regla 2.** Actualizada (2026-11-13) para alinearse con §15: el
  submódulo no exige al padre, publica su puerto y el padre lo descubre; el requisito duro queda solo
  para dependencias estructurales (`manager-apt`/`manager-core` sobre la API de `manager-spi`).
- **Rutas.** Las concesiones viven en `config/iflow/grants.yml` (layout `config/<módulo>/`), no en
  `config/suite/iflow/`: `config/suite/` es solo para la configuración global.
- **Especificación iFlow.** El baseline v3 limita al guest a contenido/metadatos; esta sección amplía
  su autoridad a efectos de host. Requiere autorización para modificar
  `docs/prompts/diseno/iflow-cpu-design.md`, `iflow-cpu-implement.md` y la especificación de la ISA.
- **No promete** rollback, transacciones entre backends, reintentos automáticos ni autoridad
  ambiente. La superficie es amplia por diseño; la concesión es el límite.

### 17. Sincronización de salidas por mensaje

#### 17.1 Problema

Hoy cada etapa tiene un solo backend global: `IflowConfig.engine` elige un motor iFlow,
`ChatTranslatorConfig.default-backend` elige un traductor y `SyncBus.broadcast` envía a todos los
sinks. No se puede decir «este mensaje va a Discord y a TCP, en este orden» ni «este mensaje
usa `gtranslate` e `iflow-wasm`». El `Message` no tiene un campo para expresarlo y la configuración
es global.

#### 17.2 Un único namespace `sync`

`outputs` no tiene un namespace por etapa: todos los targets son `sync.<backend>`. `sync` es el
concepto que unifica la selección de salidas; el backend identifica la etapa que lo consume. No se
añade un módulo `sync-mux`: `sync-bus` sigue siendo el registro de sinks, las colas y el ciclo de
vida, y publica la etapa terminal `sync`. Si más adelante hace falta una política de selección
(fallback, salud, carga), se añade como estrategia interna del bus, no como módulo nuevo.

La entrega local también es un target de `sync`: `sync-bukkit` y `sync-fabric` son sinks, no solo
listeners. Por ellos entra y sale texto de la plataforma. La captura de eventos la hace el paquete de
plataforma y los entrega a la suite; el sink, por definición, expone en su superficie todo lo
manipulable de su entorno —jugadores, consola, canales, scoreboard y eventos—, de modo que
`sync.bukkit` y `sync.fabric` son backends de pleno derecho y pueden aparecer en `outputs`.
`outputs=[]` deja sin salidas, incluida la local; para cancelar el evento de origen sigue siendo
`DROP`/`REJECT` de la política, no una lista vacía.

#### 17.3 Contrato

```java
public enum ParallelMode { INHERIT, PARALLEL, SEQUENTIAL }

public record OutputPlan(
        boolean declared,                   // false: consultar la configuración
        List<String> targets,               // "sync.<backend>"; vacía y declared=true: sin salidas
        ParallelMode parallel               // INHERIT por defecto
) {
    public static final String NAMESPACE = "sync";
    public static OutputPlan inherit() {
        return new OutputPlan(false, List.of(), ParallelMode.INHERIT);
    }
}
```

Cada target es una cadena `sync.<backend>`; el backend puede contener puntos. No se admiten otros
namespaces, segmentos vacíos ni targets sin el prefijo `sync.`. `OutputPlan.backend(target)` extrae
el backend. No hay `OutputTarget` en el contrato público.

`Message` gana `outputPlan()`. El plan es inmutable y viaja con el mensaje; no se guarda en un
`ThreadLocal`. Un `OutputPlan` ausente (`declared=false`) no es lo mismo que uno vacío
(`declared=true, targets=[]`): el primero hereda, el segundo declara «sin salidas». `declared=false`
exige `targets=[]`; cualquier otra combinación es `InvalidArgument`.

El motor resuelve el plan efectivo una vez por emisión y lo expone en `PipelineContext.outputs()`.
La traza registra de dónde salió (`message`, `suite` o `module-default`). Las etapas no mutan el plan.

`PipelineStage.ownsOutput(backend)` declara qué backends consume cada etapa. El motor valida cada
target declarado contra la unión de las etapas resueltas antes de `MESSAGE_PRE`: un backend sin dueño
o con más de un dueño es un error explícito con traza `ERROR`, y ninguna etapa se ejecuta. Una etapa
solo procesa los targets que posee.

#### 17.4 Configuración y precedencia

```yaml
# config/suite/config.yml
outputs:
  - sync.gtranslate
  - sync.discord
  - sync.tcp
  - sync.udp
outputs-parallel: true   # default; false = finalización secuencial
config-version: 1.0
```

Precedencia:

1. `Message.outputPlan()` con `declared=true`.
2. `config/suite/config.yml` → `outputs` (y `outputs-parallel`).
3. Default del módulo: `route` usa su `engine`/fallback; `format` usa `default-backend`; `sync`
   conserva el fan-out a todos los sinks, incluidos `bukkit` y `fabric`.

Una lista vacía en el mensaje o en la configuración significa «sin salidas» y no se interpreta como
«todas». Un target con un backend desconocido o ambiguo es un error explícito
(`Unsupported`/`Invalid`), nunca un skip silencioso. Las claves canónicas son `outputs` y
`outputs-parallel`; cada elemento de `outputs` es una cadena `sync.<backend>`. No hay claves
alternativas ni aliases en español (ADR-034).

#### 17.5 Etapas y backends

| Etapa | Módulo | Backends | Tipo |
|---|---|---|---|
| `route` | `iflow` (hub) | `iflow-16`, `iflow-32`, `iflow-wasm`, `iflow-firewall` | transformación |
| `format` | `textformatter` | `gtranslate`, `ltranslate` | transformación |
| `sync` | `sync-bus` | `discord`, `telegram`, `http`, `websocket`, `tcp`, `udp`, `velocity`, `bukkit`, `fabric` | entrega |

Un target se escribe `sync.<backend>`: `sync.iflow-wasm`, `sync.gtranslate`, `sync.discord`,
`sync.bukkit`.

Los backends `bukkit` y `fabric` son sinks locales: su superficie incluye las operaciones del entorno
(jugadores, consola, canales, scoreboard) y la manipulación de eventos. Una acción de evento actúa
sobre el evento ligado al mensaje actual, no sobre un objeto de evento arbitrario, y no sustituye a
`CANCEL_EVENT` de ADR-031: ese sigue siendo el target oficial de cancelación; la acción nativa es
para programas de terceros.

`route` y `format` admiten **como máximo un target propio** por mensaje; un segundo target del mismo
dueño es `Conflict`. `sync` admite varios, en orden. Una etapa nueva declara su tipo (`TRANSFORM` o
`DELIVER`) y sus backends mediante `ownsOutput`; no se crea un namespace nuevo.

`textformatter` es la etapa `format` y el namespace de las acciones del host; no es un backend de
entrega ni un namespace de `outputs`. Su backend de traducción se escribe `sync.gtranslate`, no
`textformatter.*`.

#### 17.6 Orden y paralelismo

- La lista es ordenada. El motor **inicia** los targets en ese orden y la traza y el resultado
  conservan ese orden.
- No se paraleliza entre etapas: `route` → `format` → `sync` tienen dependencia de datos. La
  traducción de `<tr>` ocurre dentro de `format` y usa el target `sync.gtranslate`/`sync.ltranslate`
  cuando `outputs` lo selecciona. Se paraleliza dentro de la etapa terminal `sync`, que es donde hay
  varios targets independientes.
- Por defecto se ejecutan en paralelo, con un pool acotado por etapa; un backend lento no bloquea a
  los demás. La finalización no está ordenada.
- `outputs-parallel: false` (o `ParallelMode.SEQUENTIAL` en el `OutputPlan`) ejecuta un target cada
  vez y espera su finalización antes de iniciar el siguiente. Es la opción para dependencias
  estrictas.
- Los targets son independientes por contrato: no hay transacción, rollback ni compensación entre
  ellos (§16.6.8). Si un target falla, los demás pueden completarse; `DispatchOutcome` marca el fallo
  parcial y nunca declara éxito total.
- El orden de la lista no reordena las etapas: `pipeline` sigue mandando. El orden se respeta dentro
  de la etapa `sync` (por ejemplo, dos targets `sync.*`) y en la traza de salidas.

#### 17.7 Efecto en las etapas

- `route` posee los backends iFlow (`sync.iflow-16`, `sync.iflow-32`, `sync.iflow-wasm`, `sync.iflow-firewall`); si
  el plan declara uno, lo usa; si no, el hub aplica su default y fallback de ADR-032.
- La traducción de `<tr>` dentro de `format` posee los backends de traducción
  (`sync.gtranslate`, `sync.ltranslate`); si el plan declara uno, lo usa; si no, la fachada aplica su
  `default-backend` y su fallback de ADR-035. No hay etapa `translate`.
- `sync` posee los backends de sink y recorre sus targets en orden, llamando al sink correspondiente
  con el `DeliveryEnvelope` que el motor construyó a partir de las unidades de `RECIPIENT`; si no hay
  targets propios, aplica el fan-out completo actual, incluida la entrega local por
  `bukkit`/`fabric`. Tanto el camino declarado como el heredado emiten un `OutputOutcome` por
  superficie, de modo que la traza no pierde los targets del fan-out.
- La entrega física local la ejecuta el host: el sink `bukkit`/`fabric` delega el sobre en
  `LocalDelivery` y el motor suma los contadores de `DeliverySummary`. El sink no entrega en
  `RECIPIENT` por su cuenta ni el motor salta al adaptador de plataforma: hay un único camino.
- `format` no es un backend de entrega y no se selecciona por `outputs` como sink; solo consume sus
  propios targets de traducción para resolver `<tr>`.

#### 17.8 Enmiendas y límites

- **ADR-032.** El hub conserva su `engine`, su prioridad y su `fallback-on-unavailable`; `outputs`
  añade un override por mensaje. No se convierte al hub en un enrutador de motores sin default.
- **ADR-035.** `ChatTranslator` conserva su `default-backend` y su override de contexto; el
  `outputs` del pipeline sustituye el uso de `ThreadLocal` para la selección por mensaje, no la
  fachada.
- **ADR-019 / ADR-025.** `sync-bukkit`/`sync-fabric` siguen capturando eventos; §17 añade que
  también son sinks locales (`bukkit`/`fabric`) y que su superficie incluye las operaciones sobre el
  evento actual. Publican el sink desde `sync-common` y declaran dependencia de paquete con
  `sync-bus`; la entrega física pasa por el puerto `LocalDelivery` que implementa el host. La captura
  no es la razón de ser del sink, pero el sink expone el entorno completo.
- **`config-schema-v2.2.md`.** `outputs` es una lista de cadenas `sync.<backend>` y
  `outputs-parallel` un booleano; el contrato del editor ya los documenta con sus reglas de
  validación.
- **No promete** orden de finalización en modo paralelo, transacciones entre backends ni selección
  automática por salud. El modo secuencial es explícito.

### Resumen de decisiones del contrato

| # | Pregunta | Decisión |
|---|---|---|
| 1 | ¿`pipeline` o `workflow`? | `pipeline`. `workflow` no es alias. |
| 2 | ¿Dónde vive el catálogo de canales? | Contratos en `core-api`; carga y propiedad en `textformatter`; el `PipelineContext` lo expone de forma opcional en solo lectura. |
| 3 | ¿`moduleId` o `stageId`? ¿Varias etapas por módulo? | `stageId`. Un módulo puede publicar varias; dos no pueden publicar el mismo id. |
| 4 | ¿Traducción como etapa? | No: la traducción de plantillas vive en `format` mediante `<tr>` y `TranslationService`; no hay etapa `translate` ni entrada en `pipeline`. |
| 5 | ¿Etapa ausente: error o `SKIPPED`? ¿Lista por defecto? | `SKIPPED` y continúa. La lista base la instala `DefaultConfigInstaller`; falta de clave en un archivo existente es `INVALID`. Lista vacía es `NO_PIPELINE`. |
| 6 | ¿Syncs como etapa? | Una etapa terminal `sync` (`MESSAGE_POST`); los sinks no son etapas. La superficie de acciones se define en §16. |
| 7 | ¿Sustituye o envuelve a `SuiteHost`/`MessageDispatcher`? | Sustituye. `PipelineEngine` + `RecipientResolver` + `DeliveryExecutor`. |
| 8 | `SET_CHANNEL` y segunda fase | Segunda pasada de routing solo para afectados, sin reentrega recursiva; segunda mutación → `DECISION_CONFLICT`. |
| 9 | Cambio en caliente | `APPLIED` con swap atómico, `FAILED` conservando el plan anterior, `RESTART_REQUIRED` si no se puede drenar. |
| 10 | Traza para `tester` | `PipelineTrace`/`StageTrace` con estado, fase, destinatario y duración; búfer de 256 y acceso por `TestProbe`. |
| 11 | ¿Todo mensaje o solo chat? | Todo `Message`; la captura y `CANCEL_EVENT` son del adaptador. |
| 12 | ¿Backend sin fachada? | Sí, instalable e inerte; la fachada es el único punto de uso y descubre proveedores. |
| 13 | ¿Cómo se elige el backend de cada etapa por mensaje? | `outputs` en el `Message`; si falta, `config/suite/config.yml`; si tampoco, el default del módulo. §17. |
| 14 | ¿`sync-bus` publica la etapa terminal `sync`? | Sí: es el hub de fan-out y pasa a seleccionar por mensaje; no se añade un módulo nuevo. §17. |

### Criterios de aceptación y tests

1. **Arranque sin features:** el núcleo arranca, registra comandos y gestiona el ciclo de vida; un
   despacho sin etapas devuelve `NO_PIPELINE`, no un éxito falso.
2. **Solo `textformatter`:** formatea y entrega sin iFlow, sin traducción y sin syncs.
3. **Solo iFlow:** arranca y enruta sin `textformatter` ni `chattranslator`; sin `format` se entrega
   texto plano y la traza lo declara. Las operaciones que necesiten el catálogo de canales fallan de
   forma explícita (`NotFound`/`Unsupported`) sin bloquear el pipeline.
4. **Solo `chattranslator`:** traduce con render pass-through, sin `textformatter` ni iFlow.
5. **Orden configurable:** el mismo conjunto en dos órdenes produce resultados distintos y trazables;
   el orden no depende del grafo ni del orden de registro.
6. **Etapa ausente o no listada:** comportamiento explícito y probado, sin éxito fingido.
7. **Guard de independencia:** un descriptor de feature no declara `Requirement` sobre otra feature;
   solo sobre contratos neutrales.
8. **Regresión:** la configuración base reproduce el comportamiento actual cuando declara el orden
   vigente; los tests de `E2EPipelineTest` y de render se conservan o se adaptan sin relajar
   aserciones.
9. **Gate:** `./gradlew build checkArchitecture checkLocks --no-daemon`; si se toca el editor,
   `npm run check`.
10. **Superficie sync:** el LCM oficial (`backend.info`, `message.send`, `message.deliver`) funciona
    sin acciones nativas; `textformatter.channel.set` solo lo invoca un tercero con concesión; una
    acción nativa sin concesión devuelve `Denied` sin efecto; `DESTRUCTIVE`/`MODERATION` exigen
    `allow-destructive`; el puente VM y el de Wasm devuelven los mismos estados para la misma
    llamada.
11. **Sincronización de salidas:** un mensaje con `sync.discord`, `sync.tcp` y `sync.bukkit` entrega a los tres
    en el orden declarado; sin `outputs` se usa el default de `config/suite/config.yml`; un target
    desconocido o ambiguo falla de forma explícita y no se convierte en éxito parcial silencioso;
    `outputs-parallel: false` serializa la finalización.
12. **Sink local:** `sync-bukkit` y `sync-fabric` publican una superficie que incluye las operaciones
    del entorno y de eventos, y entregan a jugadores/consola mediante `message.deliver` y el puerto
    `LocalDelivery`; sin ese puerto fallan de forma explícita; `bukkit.event.cancel` no sustituye a
    `CANCEL_EVENT` como target de política.

Tests adicionales obligatorios: ausencia de `iflow` con el resto instalado (debe entregar, no
bloquear); `iflow` instalado sin backend (etapa `SKIPPED`); duplicado de etapa (`INVALID`); dos
módulos publicando el mismo `stageId` (`INVALID`); `sync` seguido de otra entrada (`INVALID`);
recarga en caliente con plan inválido (conserva el anterior); despacho en vuelo durante un swap
(usa el plan anterior y no falla); y `SET_CHANNEL` con segunda pasada sin recursión.

### Consecuencias

- El pipeline deja de ser un grafo implícito de servicios obligatorios y pasa a ser una lista
  explícita de etapas opcionales con trazabilidad por unidad.
- Instalar o quitar una feature cambia el procesamiento sin bloquear el arranque ni el resto de
  etapas.
- `SuiteRuntime` deja de conocer `SuiteHost`, `MessageDispatcher`, `IflowHub` y los tipos concretos
  del render; solo conoce contratos neutrales y el plan.
- El catálogo de canales sigue perteneciendo a `textformatter` y la fachada de traducción a
  `chattranslator`; lo que cambia es que su consumo deja de ser un requisito de activación.
  `textformatter` se convierte además en publicador de la etapa `format`; `chattranslator` permanece
  como servicio opcional y no publica etapa, porque `<tr>` se resuelve dentro de `format`.
- La recarga en caliente gana un estado verificable (`APPLIED`/`FAILED`/`RESTART_REQUIRED`) y deja de
  tener la carrera de `TF-010` en el rebuild.
- Cada backend sync publica su superficie nativa como datos; la suite oficial sigue usando solo el
  LCM y los terceros pueden construir bots completos de Discord/Telegram con concesiones explícitas.
- El mensaje deja de tener un único backend global por etapa: `outputs` selecciona los backends de
  `route`, `format` y `sync` por unidad con el namespace `sync`, con default de suite y default de módulo.
- El fan-out de `sync` puede ser selectivo y paralelo sin perder el orden de dispatch ni la traza.
- El cambio es incompatible: descriptores, configuración, esquema, presets, instalador, tests y
  documentación se actualizan en la misma unidad de trabajo. No hay periodo de convivencia.

### Lo que este ADR no promete

- No promete que un pipeline vacío entregue mensajes: `NO_PIPELINE` es un estado explícito, no un
  fallback.
- No promete que las etapas sean puras ni sin estado; solo que el motor no impone su orden fuera de
  la lista.
- No convierte `sync` en entrega garantizada: el fan-out sigue siendo best-effort y no hay
  reintentos nuevos.
- No sustituye el E2E real ni declara estable la integración; eso sigue en ADR-034.
- No autoriza por sí mismo cambios en `docs/prompts/`: `storage.md` y el esquema requieren
  autorización explícita.

---

## ADR-037 — Contrato de máquina, ISA y ABI `HOST` v3 de `iflow-32`

**Estado:** Aceptada y congelada (2026-11-14). **Enmendada el 2026-11-16:** `iflow-32`
es multinúcleo intra-VM; se añaden §1.9, §6.1 y §7.12, y se corrigen §4 (extensión atómica),
§5.8/§5.9 (IPI y dispositivos), §6.4, §10 y §12.
**Alcance (ADR-038, 2026-11-15):** este contrato rige el motor `iflow-32`; no prohíbe la línea
separada `iflow-16`, que se rige por ADR-038 y conserva **un único vCPU por diseño**.

**Contexto.** `src/iflow-16` implementa hoy la máquina v2 de 16 bits y 64 KB, con fuel por
ejecución, un `.flow` ejecutable y un ABI `HOST` basado en registros. La especificación v3
(`docs/prompts/diseno/iflow-cpu-implement.md`, §8) exige una máquina de 32 bits, ROM con BIOS,
bootloader y kernel, MMU paginada, IRQ, tiempo virtual, persistencia entre mensajes y un ABI de
servicios acotado. Las preguntas 1–25 y 27–34 de §8.8 seguían abiertas. Este ADR las resuelve y fija
el contrato que V2–V5 deben implementar.

**Decisión.** Se sustituye la línea v2 por una única máquina v3 **dentro del motor `iflow-32`**. No
hay convivencia de ISAs, lectores dobles, migración en runtime ni adaptadores de compatibilidad en
ese motor. Un `.flow` v2 se rechaza con un error explícito de formato; el ensamblador y el
compilador v3 generan únicamente ROM v3. La línea `iflow-16` es un motor separado (ADR-038), no un
modo de compatibilidad de `iflow-32`.

### 1. Modelo de ejecución y registros

1. La máquina es de **32 bits**: registros, direcciones, desplazamientos, tamaños e inmediatos.
   El espacio virtual y el físico son de 32 bits; el máximo arquitectónico es 4 GiB. La RAM real es
   dispersa y acotada por VM.
2. **Little-endian único** en ROM, cabecera, TOC, segmentos, instrucciones, memoria y MMIO. No hay
   conversión de orden en el loader ni en el bus.
3. Hay **dieciséis registros de propósito general** de 32 bits:
   - `R0`–`R12`: uso general.
   - `R13` (`FP`): frame pointer.
   - `R14` (`SP`): stack pointer.
   - `R15` (`LR`): link register.
4. `PC` es un registro arquitectónico de 32 bits no direccionable como GPR. `FLAGS` es un registro
   arquitectónico de 32 bits con `Z` (bit 0), `N` (bit 1), `C` (bit 2) y `V` (bit 3); el resto de
   bits es cero. `FLAGS` se lee y escribe con `CSRR`/`CSRW` desde modo supervisor.
5. La pila es **full-descending**: `PUSH` resta 4 a `SP` y almacena; `POP` carga y suma 4. La
   alineación de pila es de 4 bytes; 8 bytes para valores de 64 bits y llamadas que los usen.
6. `CALL` escribe la dirección de retorno en `R15` y salta; `RET` salta a `R15`. No hay pila de
   retorno automática. Una función que llame a otra debe preservar `R15`.
7. El ABI C de ADR-023 se mantiene: argumentos en pila de derecha a izquierda, limpieza del
   llamador, retorno en `R0` o `R1:R0`, `R0`–`R12` y `R15` caller-saved, `R13`/`R14` preservados,
   `struct`/`union` por valor con puntero oculto, `char` sin signo y `size_t`/`uintptr_t`/
   `ptrdiff_t` de 32 bits. `float`/`double` se pasan en pila y se retornan en `R0`/`R1:R0`; el
   baseline es soft-float.
8. El estado de reset es determinista: `PC = resetVector`, `SP = FP = LR = 0`, GPR a cero,
   `FLAGS = 0`, `STATUS = 0x0000_0002` (supervisor, MMU apagada, interrupciones deshabilitadas),
   `TVEC = 0`, `PTBR = 0`, `PIDENT = MIDENT = 0xFFFF_FFFF`, `SCRATCH = 0`, `COREID = índice del
   core`. El BIOS fija su pila antes de usar `CALL`.
9. **Núcleos.** `CORE_COUNT = N` es configurable, `1 ≤ N ≤ 64`; no es una VM por core. Cada core
   tiene estado arquitectónico propio: `PC`, GPR, `FLAGS`, `STATUS`, `EPC`, `CAUSE`, `BADVADDR`,
   `TVEC`, `PTBR`, `PIDENT`, `MIDENT`, `SCRATCH`, TLB, reserva de `LR.W` y estado de traps.
   `RDCYCLE` es por core; `RDTIME` es global. La RAM/ROM física, el bus MMIO, el PIC, el timer, la
   NVRAM y los marcos de page tables son compartidos. El kernel guest decide qué proceso corre en
   cada core; el host no implementa ese scheduler ni crea una VM por core.

### 2. Privilegios y excepciones

1. Hay dos niveles: **supervisor** (`S`) y **usuario** (`U`). El BIOS, el bootloader y el kernel
   corren en `S`; los programas de política corren en `U`. No hay anillo intermedio.
2. `STATUS` tiene `IE` (bit 0), `PRV` (bit 1; 0 = U, 1 = S), `PIE` (bit 2), `PPRV` (bit 3), `MMU`
   (bit 4) y `TRAP` (bit 5). El resto debe ser cero.
3. En una excepción o IRQ: `PIE = IE`, `PPRV = PRV`, `IE = 0`, `PRV = S`, `TRAP = 1`,
   `PC = TVEC + 4 * CAUSE`. `EPC` es la dirección de la instrucción que falló en las excepciones
   síncronas, `PC + 3` en `ECALL` y la dirección de la instrucción siguiente en una IRQ. Si `TRAP` ya era 1,
   se entra en `DOUBLE_FAULT`; una segunda falta durante el doble fallo aborta la ejecución en el
   host con un error explícito. `ERET` restaura `IE`/`PRV` desde `PIE`/`PPRV`, limpia `TRAP` y
   vuelve a `EPC`; si `TRAP` era 0, `ERET` es `ILLEGAL_INSTRUCTION`.
4. `TVEC` es la base de una tabla de 64 entradas de 4 bytes (256 bytes), alineada a 256 bytes. Las
   entradas se interpretan en el espacio de direcciones actual (físico con MMU apagada, virtual con
   MMU encendida). Si `TVEC = 0`, cualquier trap es un error del host (`UNHANDLED_TRAP`); el BIOS
   debe publicar la tabla antes de habilitar interrupciones.
5. Códigos de excepción (0–15): `ILLEGAL_INSTRUCTION` (0), `BREAKPOINT` (1), `LOAD_FAULT` (2),
   `STORE_FAULT` (3), `INSTRUCTION_FAULT` (4), `ALIGNMENT` (5), `PRIVILEGE` (6), `ECALL_USER` (7),
   `ECALL_SUPERVISOR` (8), `DIVIDE_BY_ZERO` (9), `ARITHMETIC_OVERFLOW` (10), `BUS_ERROR` (11),
   reservados (12–13), `DOUBLE_FAULT` (14), reservado (15). Las IRQ ocupan 16–47 y se corresponden
   con las líneas 0–31 del PIC. `CAUSE` contiene el índice de vector; `BADVADDR` es válido para
   faltas de memoria, alineación y bus.
6. `ECALL` es la llamada al kernel: número en `R0`, argumentos en `R1`–`R6`, retorno en `R0`.
   Desde `U` usa el vector 7; desde `S`, el 8. El hardware no guarda GPR: el kernel guarda y
   restaura su marco. `HOST` no es `ECALL`: es una hiperllamada al host y no cambia `STATUS`.

### 3. ISA v3: codificación

1. Cada instrucción es una secuencia de cajas little-endian:
   - **Caja de opcode** (1 byte): `[7:6]` familia, `[5:2]` operación (0–15), `[1:0]` forma.
   - **Caja de descriptor** (2 bytes): `[15:12] rd`, `[11:8] rs`, `[7:4] base`, `[3] idx`,
     `[2:1] size`, `[0]` reservado.
   - **Caja de inmediato/desplazamiento** (4 bytes), solo cuando la forma la exige.
2. Formas: `00` registro-registro, `01` registro-inmediato, `10` lectura de memoria (o `LEA`),
   `11` escritura de memoria. En formas de memoria, `base` es el registro base, `rs` es el índice
   cuando `idx = 1` y queda reservado cuando `idx = 0`, y `rd` es el registro de datos: destino en
   lectura (forma 10) y fuente en escritura (forma 11). El desplazamiento es siempre de 32 bits y se
   suma a `base + index`; la suma es módulo 2^32. Un índice no se escala: el programa calcula
   `index * escala` con `SHL`/`ADD`. `base` e `idx` deben ser cero fuera de las formas de memoria.
3. `size`: `00` = 8 bits, `01` = 16 bits, `10` = 32 bits, `11` = reservado. En formas de memoria,
   el acceso debe estar alineado naturalmente (2 bytes para 16 bits, 4 para 32); si no, `ALIGNMENT`.
   Las cargas de 8/16 bits zero-extienden a 32 bits. Los almacenes escriben los bits bajos.
4. En `MOV` inmediato y en toda la ALU, `size` debe ser `10` (32 bits); usar 8/16 bits en esos
   casos es `ILLEGAL_INSTRUCTION`. Para cargar un valor pequeño, el programa usa un registro de
   32 bits y luego un almacén de 8/16 bits.
5. `MOV` forma 00 copia `rs` a `rd` (8/16 zero-extiende); forma 01 carga `imm32` en `rd`; forma 10
   carga `[base + index + disp32]` en `rd`; forma 11 almacena `rd` en `[base + index + disp32]`. En
   forma 11, `rd` es la fuente y `rs` es índice solo si `idx = 1`.
6. Longitud: 3 bytes para formas `00`; 7 bytes para formas `01`, y para las formas de memoria
   `10`/`11` de `MOV`/`LEA`. Los bits reservados de opcode/descriptor deben ser cero; si no,
   `ILLEGAL_INSTRUCTION`. No hay instrucciones de longitud fija: el decoder recorre las cajas y
   calcula la frontera con el mismo código que ejecuta. Una instrucción puede cruzar página; si la
   segunda página no es ejecutable, `INSTRUCTION_FAULT`.
7. No hay `MOV [mem], imm`: el ensamblador **no** sintetiza un registro temporal oculto. El autor
   escribe `MOV Rt, imm` y después `MOV [mem], Rt`. `LI` no es un opcode; es un alias de ensamblador
   que emite `MOV rd, imm32`.

### 4. ISA v3: instrucciones

| Familia | Op | Mnemónico | Formas | Semántica |
|---|---:|---|---|---|
| 0 | 0 | `MOV` | 00/01/10/11 | Registro, inmediato, carga y almacén. Único mnemónico para movimiento. |
| 0 | 1 | `LEA` | 10 | Calcula `base + index + disp32` y lo escribe en `rd`; no accede a memoria. |
| 0 | 2 | `PUSH` | 00 | `SP -= 4`; `[SP] = rs` (32 bits). |
| 0 | 3 | `POP` | 00 | `rd = [SP]`; `SP += 4` (32 bits). |
| 0 | 4–15 | — | — | Reservado. |
| 1 | 0 | `ADD` | 00/01 | `rd = rd + operando`; actualiza `Z/N/C/V`. |
| 1 | 1 | `ADC` | 00/01 | `rd = rd + operando + C`; actualiza `Z/N/C/V`. |
| 1 | 2 | `SUB` | 00/01 | `rd = rd - operando`; actualiza `Z/N/C/V`. |
| 1 | 3 | `AND` | 00/01 | `rd &= operando`; actualiza `Z/N`; limpia `C/V`. |
| 1 | 4 | `OR` | 00/01 | `rd = rd OR operando`; actualiza `Z/N`; limpia `C/V`. |
| 1 | 5 | `XOR` | 00/01 | `rd ^= operando`; actualiza `Z/N`; limpia `C/V`. |
| 1 | 6 | `SHL` | 00/01 | Desplaza a la izquierda; cuenta en `rs[4:0]` o inmediato; `Z/N/C`; `V = 0`. |
| 1 | 7 | `SHR` | 00/01 | Desplazamiento lógico; misma cuenta y flags. |
| 1 | 8 | `SAR` | 00/01 | Desplazamiento aritmético; misma cuenta y flags. |
| 1 | 9 | `MUL` | 00/01 | Producto bajo de 32 bits; `Z/N`; `C/V = 0`. |
| 1 | 10 | `DIV` | 00/01 | División entera con signo; cociente en `rd`; divisor cero o `INT_MIN / -1` trapa. |
| 1 | 11 | `DIVU` | 00/01 | División entera sin signo; divisor cero trapa. |
| 1 | 12 | `MOD` | 00/01 | Resto con signo; divisor cero o `INT_MIN % -1` trapa. |
| 1 | 13 | `MODU` | 00/01 | Resto sin signo; divisor cero trapa. |
| 1 | 14 | `CMP` | 00/01 | `SUB` sin escribir `rd`; actualiza `Z/N/C/V`. |
| 1 | 15 | `TEST` | 00/01 | `AND` sin escribir `rd`; actualiza `Z/N`; limpia `C/V`. |
| 2 | 0 | `JMP` | 00/01 | Salto a `rs` (forma 00) o relativo a `PC + 7 + disp32` (forma 01). |
| 2 | 1 | `Jcc` | 01 | Salto relativo si la condición de `rs` se cumple. `rd`/`base` reservados. |
| 2 | 2 | `CALL` | 00/01 | `R15 = PC + longitud`; salta a `rs` (forma 00) o a `PC + 7 + disp32`. |
| 2 | 3 | `RET` | 00 | `PC = R15`. |
| 2 | 4 | `ECALL` | 00 | Trap de syscall; vector 7/8 según `PRV`; número en `R0`. |
| 2 | 5 | `ERET` | 00 | Retorno de trap; solo supervisor y con `TRAP = 1`. |
| 2 | 6 | `HALT` | 00 | Detiene el core hasta IRQ habilitada, evento o watchdog; solo supervisor. |
| 2 | 7 | `BREAK` | 00 | Trap de breakpoint; código en `R0`; vector 1. |
| 2 | 8 | `NOP` | 00 | No hace nada. |
| 2 | 9–15 | — | — | Reservado. |
| 3 | 0 | `HOST` | 00 | Hiperllamada síncrona al host; convención de §7. |
| 3 | 1 | `RDCYCLE` | 00 | `rd = low32(cycles)`, `rs = high32(cycles)` en un snapshot atómico. |
| 3 | 2 | `RDTIME` | 00 | `rd = low32(timeNs)`, `rs = high32(timeNs)` en un snapshot atómico. |
| 3 | 3 | `CSRRW` | 01 | Lee el CSR a `rd` (si `rd != 0`) y escribe `rs`; número en `imm32[11:0]`. |
| 3 | 4 | `CSRRS` | 01 | Lee el CSR a `rd` y activa los bits de `rs`; si `rs = 0`, no escribe. |
| 3 | 5 | `CSRRC` | 01 | Lee el CSR a `rd` y limpia los bits de `rs`; si `rs = 0`, no escribe. |
| 3 | 6 | `CSRR` | 01 | Alias de `CSRRS rd, csr, 0`; lectura pura. |
| 3 | 7 | `CSRW` | 01 | Alias de `CSRRW 0, csr, rs`; escritura pura. |
| 3 | 8 | `SFENCE.VMA` | 00 | Vacía la TLB; necesario tras cambiar PTBR o una página. |
| 3 | 9 | `FENCE` | 00 | Ordena accesos de memoria/MMIO/DMA antes y después. |
| 3 | 10 | `LR.W` | 10 | Carga 32 bits y deja una reserva de `LR.W` sobre la dirección física. |
| 3 | 11 | `SC.W` | 11 | `rd` = éxito (1/0), `rs` = valor; almacena solo si la reserva sigue válida. |
| 3 | 12 | `AMOADD.W` | 11 | `rd` = valor anterior; `[addr] += rs`, atómico. |
| 3 | 13 | `AMOSWAP.W` | 11 | `rd` = valor anterior; `[addr] = rs`, atómico. |
| 3 | 14 | `AMOAND.W` | 11 | `rd` = valor anterior; `[addr] &= rs`, atómico. |
| 3 | 15 | `AMOOR.W` | 11 | `rd` = valor anterior; `[addr] |= rs`, atómico. |

Condiciones de `Jcc` (`rs`): 0 `EQ` (`Z=1`), 1 `NE`, 2 `CS` (`C=1`), 3 `CC`, 4 `MI` (`N=1`),
5 `PL`, 6 `VS` (`V=1`), 7 `VC`, 8 `HI` (`C=1 && Z=0`), 9 `LS` (`C=0 || Z=1`),
10 `GE` (`N=V`), 11 `LT` (`N!=V`), 12 `GT` (`Z=0 && N=V`), 13 `LE` (`Z=1 || N!=V`),
14 `ALWAYS`, 15 `NEVER`.

Reglas de flags: `ADD`/`ADC`/`SUB` actualizan `Z/N/C/V`; `C = 1` significa acarreo en `ADD` y
«sin borrow» en `SUB`/`CMP`; `V` es overflow de complemento a dos. `AND`/`OR`/`XOR`/`TEST`
actualizan `Z/N` y limpian `C/V`; los desplazamientos con cuenta 0 no cambian flags; `MUL` deja
`C/V = 0`; `DIV`/`DIVU`/`MOD`/`MODU` actualizan `Z/N` y dejan `C/V = 0`. `DIV`/`MOD` truncan hacia
cero y el resto tiene el signo del dividendo. `DIV`/`MOD` con divisor cero usan `DIVIDE_BY_ZERO`;
el caso `INT_MIN / -1` y `INT_MIN % -1` usan `ARITHMETIC_OVERFLOW`. `PUSH`/`POP` exigen `size = 10`
y `SP` alineado a 4; `LEA` ignora `size`; `MOV` de memoria admite 8/16/32. `CALL` lee el destino
antes de escribir `R15`. `RDCYCLE`/`RDTIME` exigen `rd != rs`; si coinciden, `ILLEGAL_INSTRUCTION`.

**Extensión atómica (enmienda 2026-11-16).** `LR.W`, `SC.W` y `AMO*` son la base de
sincronización intra-VM. El modelo es secuencialmente consistente para accesos de 32 bits
alineados; los accesos de 8/16 bits no son atómicos entre cores y exigen exclusión del kernel.
`LR.W` deja una reserva por core y dirección física; cualquier store de otro core a esa dirección,
un `SC.W`, un trap, `ERET`, `HALT` o un cambio de contexto la invalidan. `SC.W` escribe `rd = 1`
solo si la reserva sigue válida; si falla, `rd = 0` y no escribe memoria. `AMO*` hacen
leer-modificar-escribir atómico y devuelven el valor anterior en `rd`; `rs` es el operando. Todos
exigen alineación a 4 y `size = 10`; otra forma, tamaño o dirección produce
`ILLEGAL_INSTRUCTION`, `ALIGNMENT` o `BUS_ERROR`. Solo operan sobre RAM; un `LR.W`/`SC.W`/`AMO*`
sobre MMIO es `BUS_ERROR`. `rd` y `rs` pueden coincidir: el operando se lee antes de escribir el
resultado. La extensión está implementada: `CORE_COUNT > 1` no se simula con un solo hilo.

Privilegios de instrucción: `CSR*`, `ERET`, `SFENCE.VMA` y `HALT` son solo `S`; `ECALL`, `HOST`,
`RDCYCLE`, `RDTIME`, `FENCE`, `LR.W`, `SC.W`, `AMO*` y `BREAK` son `U/S`; el resto es común. Una
instrucción privilegiada en `U` produce `PRIVILEGE`. `HALT` con `IE = 0` produce
`ILLEGAL_INSTRUCTION`; dormir exige timer e interrupciones habilitadas.

CSR de v3.0: `STATUS` 0x100, `EPC` 0x101, `CAUSE` 0x102, `BADVADDR` 0x103, `TVEC` 0x104,
`PTBR` 0x105, `SCRATCH` 0x106, `PIDENT` 0x107, `MIDENT` 0x108, `FLAGS` 0x109, `COREID` 0x10A.
`COREID` es de solo lectura, devuelve el índice del core (0–63) y escribirla produce
`ILLEGAL_INSTRUCTION`. `TVEC` se alinea a
256 bytes y `PTBR` a 4 KB; un valor no alineado o con bits reservados no cero es
`ILLEGAL_INSTRUCTION`. En `CSR*`, `rd = 0` significa «no leer» y `rs = 0` significa «no escribir»;
por eso `CSRR`/`CSRW` son alias puros y R0 no se usa como operando en estas instrucciones. Los 20
bits altos del inmediato CSR deben ser cero; un número de CSR no definido o un valor con bits
reservados no cero es `ILLEGAL_INSTRUCTION`. `PIDENT` acepta 0–63 o 0xFFFF_FFFF; `MIDENT` acepta un
handle válido o 0xFFFF_FFFF. `FLAGS` solo escribe los bits 0–3.

### 5. Memoria, MMU y MMIO

1. **Mapa virtual** (constante, sin relocación):
   - `0x0000_0000`–`0x00FF_FFFF`: ROM de 16 MiB, R/X, supervisor.
   - `0x0100_0000`–`0x01FF_FFFF`: MMIO de 16 MiB, supervisor, decodificado **antes** de la MMU.
   - `0x0200_0000`–`0x7FFF_FFFF`: espacio de usuario, hasta ~2 GiB.
   - `0x8000_0000`–`0xFFFF_FFFF`: espacio de kernel, 2 GiB.
   - El contenedor ROM se mapea en `0x0000_0000`; `resetVector` apunta al offset físico
     4 KiB-alineado donde empieza el payload BIOS, después de cabecera y TOC (no a `0`). Las
     pilas iniciales son `0x7FFF_F000` (usuario) y `0xFFFF_F000` (kernel), con una página de
     guarda no mapeada debajo; el kernel las mapea.
2. La ROM física ocupa `0x0000_0000`–`0x00FF_FFFF`; el MMIO físico ocupa
   `0x0100_0000`–`0x01FF_FFFF`; la RAM física empieza en `0x0200_0000` y tiene `RAM_SIZE` bytes.
   Cualquier acceso físico fuera de esas regiones es `BUS_ERROR`. La ROM física es de solo lectura:
   un store a una página que la mapee produce `STORE_FAULT`.
3. **MMU apagada** al reset: las direcciones son físicas, solo se permite `S`, no hay permisos de
   página. El BIOS la enciende escribiendo `STATUS.MMU = 1` después de construir la page table. Un
   `ERET` a `U` con MMU apagada es `PRIVILEGE`. Con MMU apagada, `PC`/`EPC`/`TVEC`/`BADVADDR` son
   físicas; con MMU encendida son virtuales. El BIOS mantiene una identidad para su propio código o
   actualiza `TVEC` al encender la MMU.
4. Paginación de **dos niveles, 4 KB, 10 + 10 + 12 bits**. `PTBR` es la dirección física de la
   tabla de nivel 1, alineada a 4 KB (los 12 bits bajos deben ser cero; si no, `ILLEGAL_INSTRUCTION`
   al escribir el CSR). El PDE contiene `[31:12]` dirección física de la tabla de nivel 2 y bit 0
   `V`; el resto debe ser cero. El PTE contiene:
   - `[31:12]` frame físico;
   - bit 11 reservado (0);
   - bit 10 `S` (snapshot compartido);
   - bit 9 `C` (copy-on-write);
   - bit 8 `G` (global);
   - bit 7 `D` (dirty);
   - bit 6 `A` (accessed);
   - bit 5 `U` (accesible desde usuario);
   - bit 4 `X`, bit 3 `W`, bit 2 `R`, bit 0 `V`; bit 1 reservado (0).
   Un PDE o PTE con bits reservados no cero produce falta.
5. `W && X` es una página inválida y produce falta. Una página válida con `R = 0` no se puede leer,
   con `W = 0` no se puede escribir y con `X = 0` no se puede ejecutar; la falta es `LOAD_FAULT`,
   `STORE_FAULT` o `INSTRUCTION_FAULT`, respectivamente. `U` solo puede leer/escribir/ejecutar
   páginas con `U = 1`; `S` puede acceder a páginas de usuario. El hardware marca `A` en toda
   traducción válida y `D` en toda escritura. `C` o `S` con escritura producen `STORE_FAULT` para
   que el kernel resuelva COW/snapshot; el guest nunca recibe una escritura silenciosa sobre una
   página compartida.
6. La TLB es una caché; su contenido debe ser coherente con la page table después de escribir
   `PTBR` o ejecutar `SFENCE.VMA`. No hay ASID en v3.0: cambiar de proceso implica escribir `PTBR`
   y vaciar la TLB. Un TLB obsoleto es un bug del emulador, no un comportamiento permitido.
7. **MMIO antes de la MMU.** El rango `0x0100_0000`–`0x01FF_FFFF` no se traduce; el bus lo decodifica
   directamente y solo `S` puede acceder. Los accesos deben ser de 32 bits y estar alineados a 4;
   otro tamaño o alineación produce `ALIGNMENT` o `BUS_ERROR`. No se puede mapear MMIO mediante un
   PTE: un PTE que apunte a la región MMIO es `BUS_ERROR`.
8. **Dispositivos v3.0** (cada uno de 4 KB, registros de 32 bits):
   - `0x0100_0000` UART: `TX` WO, `RX` RO, `STATUS` RO (bit 0 TX listo, bit 1 RX disponible,
     bit 2 overflow), `CTRL` RW (bit 0 IRQ RX, bit 1 IRQ TX).
   - `0x0100_1000` TIMER: `CMP_LO`/`CMP_HI` RW, `CTRL` RW (bit 0 enable, bit 1 periódico,
     bit 2 IRQ), `TARGET` RW (`[7:0]` core destino, `0xFF` broadcast; reset 0), `STATUS` RW1C
     (bit 0 fired), `PERIOD_LO`/`PERIOD_HI` RW (por defecto 1.000.000 ns), `TIME_LO`/`TIME_HI` RO
     con latch (leer `TIME_LO` fija el alto hasta leer `TIME_HI`). `TIME_*` es global; `CMP`,
     `CTRL`, `TARGET` y `STATUS` los gestiona el kernel y la IRQ 0 se entrega al core destino.
   - `0x0100_2000` PIC: banco por core de `PENDING` RO, `MASK` RW (1 = enmascarada; reset todo 1),
     `ACK` WO e `IN_SERVICE` RO; `SOFT_IRQ` WO (`[7:0]` línea, `[15:8]` core destino, `0xFF`
     broadcast; escribe 1 en una línea para pendiente por software o IPI). Cada core solo ve y
     modifica su banco.
   - `0x0100_3000` DISPLAY: `COLS` RO (80), `ROWS` RO (25), `CURSOR` RW (`fila << 16 | columna`),
     `CTRL` RW (bit 0 enable, bit 1 clear), búfer de texto de 80×25 celdas de 2 bytes (carácter y
     atributo) en `0x0100_3010`–`0x0100_3FFF`.
   - `0x0100_4000` NVRAM: `SIZE` RO (4 KiB), `ADDR` RW, `DATA` RW, `COMMIT` WO (1 = flush).
   - `0x0100_5000` SYSINFO: `RAM_SIZE`, `RAM_BASE`, `ROM_SIZE`, `TOC_OFFSET`, `TOC_COUNT`,
     `CORE_COUNT`, `ABI_VERSION` (3), `FEATURES` (bit 0 timer, 1 NVRAM, 2 UART, 3 display, 4 sync,
     5 mailbox, 6 multicore), `CPU_HZ` (1.000.000.000), `TIME_HZ` (1.000.000.000).
   - `0x0100_6000` HOST_MAILBOX: `EVENT_TYPE` RO (1 mensaje, 2 apagado, 3 acción host completada),
     `MESSAGE_HANDLE` RO, `EVENT_SEQ` RO, `TARGET` RW (`[7:0]` core destino; reset 0), `ACK` WO,
     `STATUS` RO (bit 0 pendiente).
   - `0x0100_7000`–`0x01FF_FFFF`: reservado; acceso `BUS_ERROR`.
9. Las líneas de IRQ son: 0 timer, 1 UART RX, 2 UART TX, 3 display (no usada en v3.0), 4 reservada,
   5 host mailbox, 6–15 reservadas y 16–31 software. El PIC es de flanco, con `PENDING`, `MASK` e
   `IN_SERVICE` **por core**; cada core atiende solo su banco. Al tomar la línea pendiente de menor
   número no enmascarada, limpia ese bit, lo marca en `IN_SERVICE` y vectoriza; `ACK` local limpia
   `IN_SERVICE`. Un nuevo flanco durante el servicio vuelve a poner `PENDING`, pero la línea no se
   toma hasta el `ACK`. El kernel debe hacer `ACK` antes de `ERET`. Las líneas 16–31 son software y
   `SOFT_IRQ` las dirige al core indicado (IPI); `TARGET = 0xFF` es broadcast. Una IPI a un core
   fuera de `CORE_COUNT` es `BUS_ERROR`; no se ignora en silencio. El timer y el mailbox usan
   `TARGET` para elegir el core que recibe su IRQ; el kernel puede reenviarla con una IPI.

### 6. Tiempo, scheduler y persistencia

1. `RDCYCLE` es un contador monótono de 64 bits **por core** de instrucciones retiradas; solo
   avanza cuando ese core ejecuta. `RDTIME` es un contador monótono de 64 bits **global** de
   nanosegundos virtuales; avanza durante ejecución y espera. Cada core lee su `RDCYCLE`; `RDTIME`
   es común. Ambos se leen con un snapshot atómico en dos registros; no hay *tearing*. La
   frecuencia nominal es 1 GHz para ciclos y 1 ns para tiempo; el overflow es el de un `u64` y no
   se reinicia salvo reset de la VM.
2. El CPU comprueba IRQ pendientes al inicio de cada instrucción, antes del fetch. El timer compara
   `RDTIME` con `CMP`; al alcanzarlo, marca `STATUS.fired` y, si `CTRL.IRQ` y la línea 0 están
   desenmascaradas, levanta la IRQ 0. En modo periódico suma `PERIOD` a `CMP`; si la VM se atrasó,
   dispara una vez y fija `CMP = RDTIME + PERIOD` para no generar una tormenta.
3. `HALT` solo es válido en `S` y exige `IE = 1`; con `IE = 0` es `ILLEGAL_INSTRUCTION`. Detiene el
   core hasta que haya una IRQ habilitada, un evento de host o el watchdog (si está activo). Si no
   hay procesos ejecutables, el host avanza el tiempo virtual hasta el
   siguiente deadline en lugar de hacer busy-wait. `ECALL` permite al kernel ceder la CPU y dormir;
   la política temporal (token bucket, backoff, deadline) vive en el guest, no en el host.
4. **Núcleos.** `iflow-32` es **una sola VM con `CORE_COUNT = N` núcleos configurables**
   (`1 ≤ N ≤ 64`), no una VM por core. Los N cores comparten RAM, ROM, bus MMIO, kernel y estado
   de máquina; cada uno tiene su estado arquitectónico. El scheduler de procesos es software del
   kernel guest; el host solo crea los cores, impone quantum por core y watchdog de paso
   configurable, y no decide qué proceso corre en cada uno. El detalle de coherencia, atómicas,
   IPI, arranque y journal está en §6.1. V3-2 implementa ese mínimo y habilita `N > 1`; no hay
   degradación silenciosa a un solo core.
5. **Quantum de host por core (preempción, no fuel):** 1.000.000 de instrucciones o 50 ms de
   ejecución de guest por core y por slice, lo que ocurra primero, configurable. Al agotarse, el
   host recupera el control y devuelve `PREEMPTED`; **no es un fallo, no termina el mensaje, no
   revierte el journal y no limita la vida de la VM ni el número de slices**. El estado
   arquitectónico, la RAM, el journal abierto y los contadores se conservan; el host puede reanudar
   el mismo core de inmediato o cederlo a otro trabajo. El guest no puede enmascarar ni desactivar
   el quantum. No hay `machineLock` global para la ejecución normal: el lock de máquina solo
   serializa ciclo de vida (reset, snapshot, apagado). No es fuel y no se expone en el ABI.
   **Watchdog de paso:** si un solo paso de guest tarda más de 50 ms de reloj de pared en retornar
   (excluyendo `HOST`), el host devuelve `WATCHDOG` y deja el core en `FAULTED`; es una salvaguarda
   contra un fallo de implementación o un bloqueo del host, no una política de entrega ni un límite
   para programas largos. El tiempo bloqueado en `HOST` no consume el quantum ni el watchdog y
   queda acotado por el timeout de la acción. La demora de un mensaje es responsabilidad del
   programa de userspace/kernel guest, no del host. La comprobación es por paso y no interrumpe
   asíncronamente un `cpu.step()` que nunca retorne; esa interrupción externa exigiría aislar el
   core y queda fuera de V3-1. El watchdog está **activo por defecto**. El operador puede
   desactivarlo explícitamente en `config/iflow-32/config.yml` con `watchdog.enabled: false` para
   hosts muy lentos donde un solo paso puede superar 50 ms por causas legítimas (CPU antigua, JIT
   frío o pausas del host). Un archivo ausente mantiene el watchdog activo; un valor inválido falla
   de forma explícita. Desactivarlo se registra con `warn`, no cambia el quantum, que sigue siendo
   no enmascarable, y deja sin recuperación un `cpu.step()` que no retorne.
6. La **máquina es persistente**: RAM, registros, timer, page tables y NVRAM sobreviven entre
   mensajes mientras la VM está encendida. El journal es por unidad de mensaje: o se confirman
   todas las mutaciones del mensaje o ninguna. Un aborto de ejecución no revierte el estado de
   máquina ya avanzado (tiempo, ciclos, cola); no se presenta como éxito de mensaje. Al apagar la
   VM solo persiste la NVRAM; reiniciar no conserva RAM ni registros.
7. La cola de mensajes vive en el kernel. El host conserva los objetos `Message` y usa un handle
   opaco de 32 bits (16 bits de slot + 16 bits de generación) para evitar ABA. El handle no se
   interpreta en el ABI; el kernel lo trata como opaco. El host mailbox publica el handle y el
   evento; el kernel lo encola y fija `MIDENT` al ejecutar la política.
8. Una entrega diferida por `HALT`/sleep **no cuenta como entregada** hasta que el guest registra
   `ROUTE_DECISION` con target `DELIVER`. Si la VM se apaga o la ejecución se cancela antes, el
   host devuelve `NOT_DELIVERED`/`CANCELLED`; nunca un éxito falso. Si la entrega física falla
   después de la decisión, el resultado es el `SyncResult` explícito de la acción y no se reintenta
   si no es idempotente.
9. No hay `ROUTE_RATE_LIMIT` ni `DECISION_RATE_LIMIT`. El token bucket y el backoff son software
   del guest. El host puede aplicar un **cap duro separado** (llamadas por minuto, tamaño de
   payload, timeout de acción) que devuelve `RATE_LIMITED` para la tasa, `DENIED` para un payload
   que nunca cabría y `TIMEOUT` para una acción agotada; ese cap no es la política y no se duplica
   con ella.
10. El cambio de ROM en caliente no está soportado en v3.0: se marca `RESTART_REQUIRED`. Las
    instantáneas de memoria son por frame y COW; nunca se clona el espacio de 4 GiB. La compresión
    de frames queda fuera de v3.0.

### 6.1. Núcleos, coherencia y concurrencia (enmienda 2026-11-16)

1. **Modelo.** `CORE_COUNT` es configurable (`1 ≤ N ≤ 64`). Es **una sola VM con N vCPU**, no N
   VMs ni N kernels. Los cores comparten RAM, ROM, bus MMIO, PIC, timer, NVRAM, kernel y marcos de
   page tables; cada core conserva su estado arquitectónico y su TLB. El host crea los cores,
   aplica quantum por core y watchdog de paso configurable; el kernel guest decide qué proceso
   corre en cada uno.
2. **Estado por core.** `PC`, GPR, `FLAGS`, `STATUS`, `EPC`, `CAUSE`, `BADVADDR`, `TVEC`, `PTBR`,
   `PIDENT`, `MIDENT`, `SCRATCH`, TLB, reserva de `LR.W` y estado de traps son privados. `COREID`
   (0x10A, RO) identifica el core; `SYSINFO.CORE_COUNT` publica N y `FEATURES` bit 6 indica
   multicore. `RDCYCLE` es por core; `RDTIME` es global. `TVEC` y `PTBR` pueden apuntar a
   estructuras compartidas, pero cada core mantiene su copia.
3. **Memoria compartida.** El modelo es **secuencialmente consistente** para accesos de 32 bits
   alineados. Los accesos de 8/16 bits no son atómicos entre cores; el kernel debe protegerlos con
   `LR.W`/`SC.W` o con exclusión mutua. `FENCE` ordena memoria/MMIO observables; en v3.0 con
   coherencia secuencial no cambia el resultado, pero se conserva como barrera contractual. No hay
   cachés de datos; la TLB es por core. Un snapshot o apagado exige detener todos los cores y
   tomar el lock de ciclo de vida; no se toma un snapshot con cores ejecutando.
4. **MMIO.** El bus serializa cada transacción de 32 bits alineada y publica el efecto antes de
   retirar la instrucción. Los dispositivos que no admiten concurrencia serializan internamente y
   nunca descartan una escritura en silencio. El PIC tiene `PENDING`/`MASK`/`IN_SERVICE` por core;
   `TIMER.TARGET` y `HOST_MAILBOX.TARGET` eligen el core de destino; `SOFT_IRQ` puede dirigir una
   línea software a otro core como IPI. Un `TARGET` fuera de `CORE_COUNT` es `BUS_ERROR`.
5. **Atómicas.** `LR.W`/`SC.W`/`AMO*` de §4 son obligatorias para locks, colas y contadores
   compartidos. La reserva de `LR.W` es por core y dirección física; cualquier store de otro core
   a la misma dirección, un `SC.W`, un trap, `ERET`, `HALT` o un cambio de contexto la invalidan.
   `SC.W` devuelve 1 solo si la reserva sigue válida; si no, devuelve 0 y no escribe. Los `AMO*`
   son leer-modificar-escribir secuencialmente consistentes y solo operan sobre RAM; no hay
   atómicas sobre MMIO.
6. **IPI y TLB.** `SFENCE.VMA` vacía solo la TLB del core que la ejecuta. Tras cambiar una page
   table compartida, el kernel debe enviar una IPI a los demás cores (líneas software 16–31 con
   `SOFT_IRQ`) y esperar su confirmación antes de reutilizar el marco; no hay ASID ni shootdown
   automático del host. Un core que recibe la IPI ejecuta `SFENCE.VMA` y la reconoce.
7. **Scheduler.** El kernel guest es el único scheduler de procesos. El core 0 es el boot core:
   todos los cores arrancan en `resetVector`, leen `COREID`, y los cores 1..N-1 esperan la
   liberación del kernel en `HALT` o en una espera cooperativa; no ejecutan guest antes de que el
   kernel los publique. El host no hace work stealing ni elige el core de un mensaje. `HALT` es por
   core; si todos los cores están dormidos, el host avanza el tiempo virtual hasta el deadline más
   cercano; si alguno ejecuta, el tiempo avanza con la ejecución.
8. **Quantum, watchdog y fallo.** El quantum es por core y no cobra tiempo bloqueado en `HOST`.
   El watchdog de paso es por core, está activo por defecto y se configura en
   `config/iflow-32/config.yml`; desactivarlo no altera el quantum. Agotar el quantum devuelve
   `PREEMPTED`: no aborta la unidad de mensaje, no descarta el journal abierto y no limita la vida
   de la VM; el host puede reanudar el mismo core.
   El watchdog de paso solo devuelve `WATCHDOG` si un único paso de guest supera 50 ms de reloj de
   pared; entonces el core queda `FAULTED`, el journal abierto no se confirma y no se revierte
   ninguna unidad ya confirmada por otro core. Si el fallo puede haber corrompido estado
   compartido, la VM se marca `FAULTED` y exige reinicio; no se continúa con un core zombi ni se
   presenta un éxito falso. Los demás cores se detienen de forma ordenada en ese caso. La
   comprobación es por paso: si el código del host que implementa una instrucción no retorna, el
   hilo no recupera el control; una interrupción externa de un core colgado queda fuera de V3-1.
   Con el watchdog desactivado, un `cpu.step()` colgado deja de ser recuperable; es una decisión
   explícita del operador, se registra con `warn` y no habilita ningún fallback silencioso.
9. **Journal y `HOST` concurrente.** El journal es por unidad de mensaje, no por VM. Varias
   unidades pueden ejecutarse en cores distintos con overlays y decisiones independientes; cada
   una tiene su propio contexto de ABI y su latch de `ROUTE_DECISION`, sin compartir el de otra.
   `PROPERTY_*` y `ROUTE_DECISION` solo ven el overlay de su unidad; no hay visibilidad cruzada
   antes del commit. El host serializa las mutaciones globales o los servicios no thread-safe, pero
   no toma un `machineLock` global para ejecutar guest. El aborto de una unidad no revierte
   unidades ya confirmadas por otros cores.
10. **Arranque y gate.** El BIOS y el kernel deben ser multicore-safe. La implementación de este
    apartado corresponde a **V3-2** y entrega: `CORE_COUNT`, estado por core, `COREID`/`SYSINFO`,
    atómicas, IPI, PIC por core, TLB shootdown, scheduler de guest, watchdog por core y journal
    concurrente. `CORE_COUNT > 1` solo se habilita cuando el BIOS y el kernel guest cumplen ese
    mínimo; no hay un modo single-core silencioso ni un fallback. `CORE_COUNT = 1` debe seguir
    siendo determinista.

### 7. ABI `HOST` v3

1. `HOST` es una hiperllamada síncrona. Entradas:
   - `R0` = service ID de 32 bits;
   - `R1` = flags, debe ser 0;
   - `R2` = dirección virtual del buffer de argumentos;
   - `R3` = longitud de argumentos en bytes;
   - `R4` = dirección virtual del buffer de resultado;
   - `R5` = capacidad del buffer de resultado.
2. Salidas: `R0` = estado; `R1` = longitud del resultado. Todos los demás registros se preservan.
   Si la capacidad es insuficiente, `R0 = LIMIT`, `R1 = tamaño requerido` y no se escribe ningún
   byte parcial. Si `R3 = 0`, `R2` se ignora. Los buffers deben ser accesibles desde el modo actual
   y estar completamente mapeados; si no, `FAULT`.
3. Estados: `OK = 0`, `INVALID_ARGUMENT = 1`, `UNSUPPORTED = 2`, `DENIED = 3`, `NOT_FOUND = 4`,
   `CONFLICT = 5`, `LIMIT = 6` (solo capacidad del buffer de resultado), `FAULT = 7`,
   `INTERNAL = 8`, `NOT_READY = 9`, `RATE_LIMITED = 10`, `TIMEOUT = 11`. No hay códigos negativos
   ni éxito implícito. `LIMIT` no significa fuel, gas ni presupuesto de ejecución agotado.
4. El código de datos es **TLV** con `TlvCodec` de `core-api`: tipo `u8`, longitud `u32` LE,
   payload; tipos 0 `NULL`, 1 `STRING`, 2 `I64`, 3 `BOOL`, 4 `BYTES`, 5 `LIST`, 6 `MAP`;
   little-endian, sin padding, UTF-8 validado, sin truncar, ausencia explícita (`NULL`) distinta
   de cadena vacía, claves duplicadas inválidas en mapas y listas ordenadas. El máximo por buffer
   es 1 MiB. El puente sync usa además `SyncBridgeCodec`, sin un segundo codec.
5. Servicios v3:

   | ID | Servicio | Argumentos TLV | Resultado TLV |
   |---|---|---|---|
   | `0x0100` | `PROPERTY_READ` | `scope`, `property`, referencias opcionales | `present`, `value` tipado |
   | `0x0101` | `PROPERTY_WRITE` | `scope`, `property`, `value` | vacío |
   | `0x0102` | `PROPERTY_EXISTS` | `scope`, `property` | `present` |
   | `0x0200` | `PERMISSION_CHECK` | `actor`, `channel`, `permission` | `allowed` |
   | `0x0300` | `ROUTE_DECISION` | `target`, `reason`, `error` opcional | vacío |
   | `0x0400` | `TRANSFORM_APPLY` | `kind`, `input` | `output` |
   | `0x0701` | `MESSAGE_ID` | vacío | `id` |
   | `0x0800` | `SYNC_SURFACE` | vacío | superficies |
   | `0x0801` | `SYNC_INVOKE` | `backend`, `action`, `args`, `idempotencyKey`/`reason` opcionales | `SyncResult` |

   No existen en v3.0 `STATE_READ/WRITE`, `CHANNEL_RESOLVE/POLICY`, `MESSAGE_CLONE`,
   `ROUTE_REDIRECT`, `ROUTE_RATE_LIMIT`, `PERMISSION_QUERY` ni `DECISION_RATE_LIMIT`; un ID v2 no
   listado devuelve `UNSUPPORTED`.
6. `ROUTE_DECISION.target`: `DELIVER = 0`, `CANCEL_EVENT = 1`, `ERROR = 2`. No existe `DROP`,
   `LOG` ni `REDIRECT`; no llamar a `DELIVER` es el drop por defecto. Solo `DELIVER` confirma el
   journal y después intenta la entrega; sin decisión, con `CANCEL_EVENT` o con `ERROR`, el journal
   del mensaje se descarta (con `CANCEL_EVENT` sí se aplica la cancelación del evento). Un fallo de
   entrega posterior a `DELIVER` no revierte el mensaje ya
   confirmado. Una segunda decisión en la misma ejecución devuelve `CONFLICT` e invalida la unidad:
   no se entrega nada. El destino no viaja en la decisión: el cambio de canal
   se hace con `SYNC_INVOKE("textformatter", "channel.set", ...)` antes de decidir, y el backoff es
   tiempo de guest. Ese cambio de canal se registra en el journal; la segunda fase de routing se
   computa sobre el overlay y solo se entrega si la unidad termina en `DELIVER`. Así se conservan
   D02/D03/D40/D42 y ADR-036 §16.5; el `destination`/`backoff` de D41 no se expone en el ABI v3.
7. `PROPERTY_WRITE` escribe en el overlay del journal; las lecturas posteriores de la misma
   ejecución ven el cambio (read-your-writes). El commit es todo o nada. `PROP_CHANNEL`,
   `PROP_DIRECTION`, `PROP_TYPE` y `PROP_CANCELLED` son de solo lectura; escribir una de ellas
   devuelve `DENIED`. El canal se cambia solo por la acción sync. Los IDs de propiedad son los de
   ADR-031, sin `PROP_PROCESSED`, `PROP_SOUNDS`, `PROP_ACTOR_WORLD` ni `PROP_SLEEP_MILLIS`;
   `PROP_CANCELLED` se conserva como lectura de estado, no como target.
8. `PERMISSION_CHECK` es fail-closed: actor, canal o permiso ausente/vacío/desconocido devuelve
   `allowed = false`; un argumento malformado devuelve `INVALID_ARGUMENT`. `PROPERTY_READ` y
   `PROPERTY_EXISTS` de una propiedad desconocida devuelven `present = false`; `PROPERTY_WRITE`
   devuelve `NOT_FOUND`; `TRANSFORM_APPLY` con `kind` desconocido devuelve `UNSUPPORTED`;
   `ROUTE_DECISION` con `target` inválido devuelve `INVALID_ARGUMENT`. `SYNC_SURFACE` devuelve
   `UNSUPPORTED` si el puente no está instalado y una lista vacía si existe pero no hay backends.
   `SYNC_INVOKE` mapea `Ok→OK`, `Denied→DENIED`, `Unsupported→UNSUPPORTED`,
   `InvalidArgument→INVALID_ARGUMENT`, `NotFound→NOT_FOUND`, `Conflict→CONFLICT`,
   `RateLimited→RATE_LIMITED`, `Timeout→TIMEOUT` y `HostFailure→INTERNAL`; conserva
   `retryAfterMillis`/`reason` y devuelve `UNSUPPORTED` sin puente. `SYNC_SURFACE` exige la
   capacidad `sync.surface` y `SYNC_INVOKE` la capacidad `sync.invoke`, además de la capacidad
   concreta de la acción.
9. `HOST` requiere `PIDENT` válido para cualquier servicio de política y `MIDENT` válido para los
   servicios que operan sobre el mensaje (`PROPERTY_*`, `PERMISSION_CHECK`, `ROUTE_DECISION`,
   `TRANSFORM_APPLY`, `MESSAGE_ID`, `SYNC_INVOKE`); `SYNC_SURFACE` solo exige `PIDENT`. `PIDENT`
   es el índice del programa en la tabla del loader (0–63); `MIDENT` es el handle del mensaje
   actual; ambos son CSR de supervisor (0x107 y 0x108) y `U` no puede escribirlos. El host resuelve
   el SHA-256 y las capacidades desde su tabla, no desde el guest. Sin identidad verificada,
   `DENIED` o `NOT_READY`. `Principal.Official` no ejecuta `HOST`.
10. Capacidades baseline (`iflow.property.read`, `iflow.property.write`, `iflow.permission.check`,
    `iflow.route.decision`, `iflow.transform.apply`, `iflow.message.id`) se conceden a todo
    programa verificado que las declare. Las acciones sync usan `sync.<backend>.<accion>` o
    `module.<moduleId>.<accion>` y exigen concesión del operador por SHA-256, con la intersección
    declarada/concedida de ADR-036 §16.6. Una capacidad desconocida en la imagen invalida la ROM.
11. `HOST` no cambia `STATUS`, no es reentrante y puede ceder la CPU mientras espera al host. Una
    acción sync puede tener efecto externo antes del commit del journal; no hay transacción entre
    efecto nativo y mensaje (ADR-036 §16.6). Un timeout devuelve `TIMEOUT`, nunca éxito.
12. Con `CORE_COUNT > 1`, cada core tiene su propio contexto de ABI y de decisión; el latch de
    `ROUTE_DECISION` no se comparte. Las operaciones sobre una unidad de mensaje se serializan por
    `MIDENT` y su journal; `HOST` puede ejecutarse en varios cores a la vez, pero los servicios que
    tocan estado global del host se serializan en el host. Un `PROPERTY_WRITE` de una unidad no es
    visible para otra antes del commit. Sin identidad verificada, `DENIED`.
13. **Sin fuel, gas ni presupuesto de ejecución en el ABI.** El guest no recibe, paga ni consulta
    un contador de ejecución; no existe un estado `OUT_OF_FUEL`, `LIMIT` no es un agotamiento de
    ejecución y `TIMEOUT` solo describe el timeout de una acción `HOST`. El quantum de host es un
    slice de preempción reanudable, no una cuota total: no limita la vida de la VM, la duración de
    un mensaje ni el número de slices. Si un mensaje se retrasa, la política de admisión, cola y
    backoff es del kernel/userspace guest. La única cota de pared de una llamada `HOST` es el
    timeout explícito de su acción.
14. **Vocabulario de `scope`/`property`.** Los argumentos son cadenas canónicas; no hay ids
    numéricos v2, alias ni despacho dual. Los pares admitidos son:
    - `message`: `content`, `langSource`, `langTarget`, `colorMode`, `formatPapi`, `translate`
      (lectura/escritura), y `channel`, `direction`, `type` (solo lectura).
    - `emitter` y `recipient`: `name`, `uuid`, `language` (solo lectura).
    - `channel`: `name`, `rate`, `sendPolicy`, `receivePolicy` (solo lectura).
    Un par desconocido se lee como ausencia (`present = false`) y `PROPERTY_EXISTS` devuelve
    `false`; `PROPERTY_WRITE` sobre un par desconocido devuelve `NOT_FOUND` y sobre uno conocido de
    solo lectura, `DENIED`. Los valores son TLV estrictos: texto, idioma y color como `STRING`
    válida y los booleanos como `BOOL`; un tipo incorrecto es `INVALID_ARGUMENT`. El host aplica el
    write set como un lote atómico al commitear `DELIVER` y no aplica ningún byte si el lote falla.

### 8. Formato ROM v3 (`.flow`)

1. El `.flow` v3 es una ROM completa, no un ejecutable. Cabecera de 80 bytes:

   | Offset | Tamaño | Campo |
   |---:|---:|---|
   | 0 | 8 | magic `FLOWROM\0` |
   | 8 | 2 | `formatVersion = 3` |
   | 10 | 2 | `isaVersion = 32` |
   | 12 | 2 | `abiVersion = 3` |
   | 14 | 2 | `headerSize = 80` |
   | 16 | 4 | `flags` (bit 0 firmada; bits 1–31 reservados) |
   | 20 | 4 | `romSize` |
   | 24 | 4 | `resetVector` |
   | 28 | 4 | `tocOffset` |
   | 32 | 2 | `tocCount` |
   | 34 | 2 | `tocEntrySize = 64` |
   | 36 | 4 | `hashAlgorithm = 1` (SHA-256) |
   | 40 | 4 | `signatureOffset` (0 si no hay) |
   | 44 | 4 | `signatureSize` (0 si no hay) |
   | 48 | 32 | `hash` SHA-256 |

2. El hash cubre toda la ROM con los 32 bytes del campo `hash` puestos a cero. Se verifica antes de
   mapear. Un hash todo cero es inválido. Si `flags.bit0 = 1`, debe existir firma y el host la
   verifica antes de cargar; el parser de la VM comprueba presencia y límites, no criptografía.
   `signatureOffset`/`signatureSize` deben caber en la ROM y no solaparse con la cabecera, la TOC
   ni los segmentos; si `flags.bit0 = 0`, ambos deben ser cero.
3. La TOC tiene entradas de 64 bytes:

   | Offset | Tamaño | Campo |
   |---:|---:|---|
   | 0 | 1 | `type` (1 BIOS, 2 BOOT, 3 KERNEL, 4 PROGRAM, 5 DATA, 6 FONT, 7 CONFIG; 8+ reservado) |
   | 1 | 1 | `flags` (bit 0 R, 1 W, 2 X, 3 U; bits 4–7 reservados) |
   | 2 | 2 | reservado (0) |
   | 4 | 4 | `offset` en la ROM |
   | 8 | 4 | `size` copiado |
   | 12 | 4 | `loadAddress` virtual |
   | 16 | 4 | `entryPoint` (0 si no es ejecutable) |
   | 20 | 4 | `memSize` (≥ `size`) |
   | 24 | 32 | hash SHA-256 del segmento |
   | 56 | 8 | reservado (0) |

4. Límites y validación: `headerSize = 80`, `tocEntrySize = 64`, `hashAlgorithm = 1`;
   `romSize <= 16 MiB`; `tocOffset >= 80` y alineado a 8; `tocCount <= 256`; hay exactamente un
   segmento BIOS, uno BOOT y uno KERNEL, y como máximo 64 PROGRAM;
   `tocOffset + tocCount*64 <= romSize`; sin desbordamiento de 32 bits; cada `loadAddress` está
   alineado a 4 KiB y cada segmento cumple
   `offset >= 80 + tocCount*64`, `offset + size <= romSize` y no solapa cabecera, TOC ni firma;
   `loadAddress + memSize` no desborda 32 bits; segmentos sin solaparse en ROM ni en memoria
   virtual; `size > 0`; `memSize >= size`; hash de segmento distinto de cero; `entryPoint` dentro
   del segmento si `X`; `resetVector` dentro del segmento BIOS y ejecutable; el `offset` del BIOS
   es igual a su `loadAddress`, ambos 4 KiB-alineados y posteriores a cabecera y TOC. El host solo
   mapea los primeros `romSize` bytes del contenedor en `0x0000_0000` con permisos R/X y `U = 0`;
   la última página se zero-fill hasta 4 KiB. BIOS, BOOT, KERNEL y PROGRAM se leen de la ROM por
   TOC; el guest copia a RAM los segmentos que necesite con los flags de la TOC y zero-fill hasta
   `memSize`. El host no reconstruye page tables ni copia todos los segmentos.
5. Reglas por tipo: BIOS en el offset alineado tras cabecera y TOC (`loadAddress == offset`),
   R/X, supervisor; BOOT/KERNEL en `0x8000_0000`–
   `0xFFFF_FFFF`, R/X o R/W, supervisor; PROGRAM en `0x0200_0000`–`0x7FFF_FFFF`, `U = 1`, R/X y
   `W = 0`; DATA es para firmware/kernel, no para programas; FONT/CONFIG son datos. `W && X` se
   rechaza. El flag `W` de la TOC de un `PROGRAM` debe ser 0: los permisos de datos/bss salen de
   `PRG3`, no de la TOC. Un `PROGRAM` empieza con la subcabecera `PRG3` de 32 bytes:

   | Offset | Tamaño | Campo |
   |---:|---:|---|
   | 0 | 4 | magic `PRG3` |
   | 4 | 4 | `codeOffset` |
   | 8 | 4 | `codeEnd` |
   | 12 | 4 | `dataOffset` |
   | 16 | 4 | `dataEnd` |
   | 20 | 4 | `capabilitiesOffset` |
   | 24 | 4 | `capabilitiesLength` |
   | 28 | 4 | reservado (0) |

   Los offsets son relativos al inicio del segmento. `codeOffset >= 32`, `codeEnd > codeOffset`,
   `dataEnd >= dataOffset`, y los rangos de código, datos y capacidades caben en `size` y no se
   solapan. Sin datos, `dataOffset = dataEnd = 0`; `memSize >= dataEnd`; el `entryPoint` de la TOC,
   traducido a offset (`entryPoint - loadAddress`), debe caer en `[codeOffset, codeEnd)`. Las
   capacidades son `u16 count` seguido de `u16 length + UTF-8` sin NUL, máximo 256 entradas de
   128 bytes. El kernel lee `PRG3` y las capacidades desde la ROM antes de mapear; solo los rangos
   de código y datos/bss se mapean. El SHA-256 del programa es el del segmento completo y es la
   identidad de `Principal.Program`.
6. Un `.flow` v2, un `magic` distinto, `formatVersion != 3`, `isaVersion != 32`,
   `abiVersion != 3`, `headerSize != 80`, `tocEntrySize != 64`, `hashAlgorithm != 1`, un `type` 0
   u 8+, un `PRG3` con magic distinto, cualquier reservado distinto de cero o cualquier límite
   incumplido se rechaza con `UnsupportedFormatException`/`InvalidRomException`. No hay lectura
   doble ni migración en runtime. Los fixtures v2 del compilador son material de test, no
   artefactos aceptados por v3.

### 9. Arranque y carga de programas

1. El host valida la ROM, la mapea físicamente y arranca en `resetVector` (offset del payload
   BIOS, no en `0`) con MMU apagada, `S`,
   interrupciones deshabilitadas y registros de §1.8. Cada core arranca con su `COREID`; el core 0
   es el boot core y hace POST: pila, `TVEC`, PIC, timer, NVRAM, display, page tables y `SYSINFO`;
   los cores 1..N-1 esperan la liberación del kernel en `HALT` o en una espera cooperativa y no
   ejecutan guest antes. Después el BIOS enciende `STATUS.MMU` y salta al bootloader.
2. El bootloader solo copia y verifica el segmento `KERNEL` de ROM a RAM y salta a su entry point.
   No hace POST, no reconstruye page tables, no cambia el stack ni enciende interrupciones.
3. El kernel inicializa el scheduler, la cola, las syscalls y los timers; carga los segmentos
   `PROGRAM` por TOC directa, sin filesystem, verificando hash y `PRG3`, y crea un proceso por
   programa. El máximo es 64 programas. Los programas no son relocalizables en v3.0: el linker fija
   `loadAddress`; el kernel mapea el rango de código R/X y el de datos/bss R/W, ambos con `U = 1` y
   `W^X`, y zero-fill desde `max(dataEnd, codeEnd)` hasta `memSize`. Los datos de programa no se
   cargan como segmentos DATA separados.
4. El kernel asigna `PIDENT` en el contexto de cada proceso, en orden de aparición de los
   segmentos `PROGRAM` en la TOC, y `MIDENT` al ejecutar la política de un mensaje. El host mailbox
   (IRQ 5) entrega el handle opaco; el kernel lo encola y lo conserva hasta la decisión, cancelación
   o apagado. El host no conoce la lógica del kernel ni cablea programas.
5. Si un trap ocurre con `TVEC = 0`, si el doble fallo se repite, si el watchdog expira o si una
   instrucción/servicio no está soportado, la ejecución termina con error explícito; no hay
   fallback silencioso ni éxito parcial. Cambiar la ROM exige reiniciar la VM.

### 10. Límites y valores por defecto

| Límite | Valor por defecto | Rango / nota |
|---|---:|---|
| RAM por VM | 64 MiB | 16 MiB–1 GiB configurable; 4 GiB máximo arquitectónico |
| ROM | 16 MiB | fijo por mapa |
| Frames | 4 KiB | asignación dispersa bajo demanda |
| TOC | 256 entradas | 64 bytes por entrada |
| Programas | 64 | `PIDENT` 0–63 |
| Núcleos por VM | 1..64 | configurable (`CORE_COUNT`); `iflow-16` = 1 por diseño; `>1` exige V3-2 |
| TLV por buffer | 1 MiB | validado en un único punto |
| Payload sync | 64 KiB | configurable; exceso `DENIED` |
| Timeout de acción | 1000 ms | solo para una acción `HOST`; no es límite de la VM |
| Quantum de host | 1.000.000 instrucciones / 50 ms | slice de preempción por core; no es fuel ni límite total |
| Timer | 1 kHz | periodo 1 ms; `RDTIME` en ns |
| NVRAM | 4 KiB | persistente entre reinicios |
| Cola | handles 32 bits | slot 16 + generación 16 |
| Páginas | 4 KiB | dos niveles; sin páginas grandes en v3.0 |

### 10.1. Cierre de las decisiones abiertas de §8.8

| Preguntas | Decisión |
|---|---|
| 1 | La máquina v3 **reemplaza** a la v2; no hay targets conviviendo. |
| 2 | El contenedor ROM se mapea en `0x0000_0000`; `resetVector` es el offset 4 KiB-alineado del payload BIOS tras cabecera y TOC. |
| 3 | 64 MiB por defecto; 16 MiB–1 GiB configurable; 4 GiB máximo arquitectónico. |
| 4 | Páginas de 4 KB, dos niveles 10+10+12; sin páginas grandes en v3.0. |
| 5 | Compresión de frames **fuera** de v3.0; se medirá antes de proponerla. |
| 6 | Handle opaco de 32 bits (slot 16 + generación 16); el ABI no lo interpreta. |
| 7 | Timer/RTC y contador virtual de ciclos; `RDCYCLE` por core y `RDTIME` global. |
| 8 | Carga por TOC directa; sin filesystem en v3.0. |
| 9 | `U/S`, páginas `U`, `W^X` y capacidades baseline/sync por SHA-256. |
| 10 | `.flow` v2 rechazado; sin migración runtime; se reensambla/recompila a v3. |
| 11 | ROM mapeada R/X, no como dispositivo de bloque. |
| 12 | Sin benchmark en V1; la aceptación de rendimiento se mide en V6. |
| 13–17 | Codificación de 3/7 bytes, cajas jerárquicas, `MOV` único, sin `LI` opcode, little-endian, campos de 4 bits y `size` de 2 bits. |
| 18–21 | Sin fuel en el ABI; quantum de host no enmascarable; watchdog de paso activo por defecto y desactivable en configuración; timer IRQ + `HALT`; `RDCYCLE`/`RDTIME`; ciclos como diagnóstico. |
| 22–25 | Contadores de 64 bits con snapshot atómico; 1 GHz/1 ns; `RDTIME` avanza también al dormir; busy-wait se permite pero el watchdog lo corta si está activo; dormir es `HALT` con timer, no un bucle. |
| 27 | `RDCYCLE`/`RDTIME`, timer MMIO e IRQ 0; el RTC es el mismo reloj virtual. |
| 28 | `HALT` no lleva deadline; el kernel programa el timer y duerme; una IRQ despierta; `HALT` exige `IE = 1`. |
| 29 | Token bucket en RAM del guest; sobrevive sleep; no sobrevive reinicio salvo que se guarde en NVRAM. |
| 30 | Se eliminan `ROUTE_RATE_LIMIT` y `DECISION_RATE_LIMIT`; una sola decisión de guest. |
| 31 | Cap duro del host separado; tasa `RATE_LIMITED`, payload `DENIED`, timeout `TIMEOUT`. |
| 32 | El quantum se comprueba en frontera de instrucción y devuelve `PREEMPTED` sin abortar la unidad ni revertir el journal; el watchdog de paso, activo por defecto y desactivable en `config/iflow-32/config.yml`, solo marca `FAULTED` el core si un único paso supera 50 ms y el journal abierto no se confirma. El `machineLock` global solo se usa para ciclo de vida. |
| 33 | `PERMISSION`/`WORLD` vacíos o no verificables son fail-closed (`allowed = false`). |
| 34 | Una entrega diferida no cuenta hasta `DELIVER`; si se cancela o apaga, `NOT_DELIVERED`/`CANCELLED`, nunca éxito. |
| Núcleos | `iflow-32` tiene `CORE_COUNT` configurable (1..64) intra-VM; `iflow-16` conserva un único vCPU por diseño. El kernel guest decide el scheduler; el host no crea una VM por core ni hace fallback. |

Las preguntas 26 y 35–38 ya estaban cerradas en §5.4 y ADR-031; este ADR no las reabre.

### 11. Consecuencias

- V2 debe **reemplazar** el intérprete, el parser, la MMU, el bus, el PIC y el ABI v2, no añadir un
  modo paralelo. El `ProgramImage.CURRENT_FORMAT_VERSION = 2` y los opcodes v2 dejan de ser
  contrato; el `.flow` v2 se rechaza. El compilador y el ensamblador v3 son la única vía de autoría.
- La máquina gana un modelo de arranque real (BIOS → bootloader → kernel → programas), MMU de
  32 bits, IRQ, timer, tiempo virtual y persistencia. El fuel desaparece del ABI; el quantum es un
  límite de seguridad no enmascarable y el watchdog de paso es una salvaguarda configurable, no
  política del guest. Esto sustituye la cláusula
  «coste de fuel» de ADR-036 §16.6 para v3; las cuotas pasan a llamadas/minuto, payload y timeout.
- **No hay fuel, gas ni presupuesto total de ejecución en v3.** El ABI no expone un contador de
  ejecución ni un estado de agotamiento; `LIMIT` solo indica capacidad insuficiente del buffer y
  `TIMEOUT` solo el timeout de una acción `HOST`. El quantum de 1M/50 ms es un slice reanudable y
  no limita la vida de la VM, la duración del mensaje ni el número de slices. El retraso de un
  mensaje es política del kernel/userspace guest, no un fallo del host.
- La enmienda de multinúcleo convierte `iflow-32` en **una VM con N vCPU configurables**, no en
  una colección de VMs. Exige estado por core, atómicas, IPI, TLB shootdown, MMIO/PIC seguro,
  scheduler de guest, watchdog por core y journal concurrente; V3-2 entrega esas piezas y habilita
  `CORE_COUNT > 1`. `iflow-16` conserva un único vCPU por diseño y no hereda esta decisión.
- El contrato de política de ADR-031 se conserva: default deny, `DELIVER` explícito, una sola
  decisión, `CANCEL_EVENT` separado, TLV, read-your-writes, sin `REDIRECT`/`RATE_LIMIT`. El cambio
  de canal sigue siendo `textformatter.channel.set` por `SYNC_INVOKE`, no un import dedicado.
- El ABI C de ADR-023 queda implementable sin cambios: `R13`/`R14` son `FP`/`SP`, `R15` es `LR`,
  la pila es de 4 bytes y el retorno es `R0`/`R1:R0`. El compilador C sigue bloqueado por V5, no
  por V1.
- `iflow-wasm` no se toca: su ABI de imports y W3.5 son una línea paralela. Este ADR no declara
  paridad `.flow`/`.wasm`; esa matriz sigue en W3.5.
- La seguridad es fail-closed: ROM y segmentos con hash, `W^X`, `U/S`, MMIO solo supervisor,
  capacidades declaradas y concedidas por SHA-256, sin acceso a filesystem/red/procesos del host y
  sin stubs. Un servicio o instrucción no soportados fallan explícitamente.

### 12. Lo que este ADR no promete

- No implementa V2–V5 ni cambia código en V1.
- No conserva compatibilidad v2, no migra `.flow` v2 en runtime y no mantiene dos lectores.
- No incluye compresión de frames, ASID, páginas grandes, hot-reload de ROM ni verificación
  criptográfica de firmas dentro de la VM.
- El multinúcleo intra-VM de `iflow-32` **sí** es contrato desde la enmienda de 2026-11-16; su
  implementación es V3-2 y no se promete en V1/V2. `iflow-16` no implementa multinúcleo.
- No define benchmarks de «networking empresarial»; se medirán con V6 y no se asumen.
- No declara estable la suite ni sustituye el E2E real de ADR-034.
- No autoriza cambios en `docs/prompts/`; la especificación sigue siendo de solo lectura.

### 13. Criterios de validación de la implementación

V1 se cierra con este ADR. V2–V5 deben demostrar, como mínimo:

1. Ensamblador v3: codifica y decodifica todas las instrucciones, rechaza formas reservadas y
   produce ROM con hash/TOC válidos; `LI` solo como alias.
2. CPU: flags, saltos, `CALL`/`RET`, pila, alineación, `ECALL`/`ERET`, doble fallo y watchdog.
3. MMU: page walk de dos niveles, `U/S`, `W^X`, `A/D`, COW/snapshot, TLB coherente y límites de
   frame; ningún `AIOOBE` escapa como fallo de VM.
4. IRQ/tiempo: PIC, máscara, prioridad, `ACK`, timer periódico/one-shot, `RDCYCLE`/`RDTIME` sin
   tearing, `HALT`/sleep y avance de tiempo sin busy-wait.
5. ABI `HOST`: TLV, estados, capacidad insuficiente, buffers inválidos, `PIDENT`/`MIDENT`,
   `ROUTE_DECISION` única, read-your-writes y mapeo de `SyncResult`.
6. ROM: rechazo de v2, magic/versión, hash, solapamientos, `memSize`, `W^X`, `PRG3`, capacidades y
   límites; carga BIOS/boot/kernel/programa.
7. Integración: un mensaje real atraviesa el pipeline con una política `.flow` v3 y produce el
   mismo resultado observable que el contrato de ADR-031/ADR-036, sin fuel y sin doble camino.
8. Multinúcleo: `CORE_COUNT = 1` determinista; `CORE_COUNT > 1` exige estado por
   core, atómicas, IPI, TLB shootdown, MMIO/PIC por core, journal concurrente y watchdog de paso
   configurable por core. V3-2 entrega esas piezas y habilita el rango completo. `iflow-16` no
   implementa multinúcleo.

---

## ADR-038 — Dos líneas separadas: `iflow-16` e `iflow-32`

**Estado:** Aceptada (2026-11-15). Enmendada (2026-11-23): `.flow` v2 pasa a little-endian único;
ADR-037 sigue siendo el contrato de `iflow-32` y esta decisión rige la línea `iflow-16`.

**Contexto.** `src/iflow-vm` implementa la máquina v2 de 16 bits y 64 KB. La línea v3 de 32 bits
ya tiene contrato congelado en ADR-037 y un núcleo aislado, pero todavía no está cableada al hub.
La v2 no es solo deuda: tiene un público real en servidores con poca CPU o RAM y en entornos
embebidos, y debe poder recibir soporte y correcciones sin arrastrar las decisiones de la v3. Al
mismo tiempo, la auditoría manual de la línea 16-bit identificó que el `.flow` no es «solo un
programa»: es una imagen ROM con cabecera, TOC, segmentos y un modelo BIOS/bootloader/kernel. Ese
hallazgo se aplicó inicialmente a la migración de 32 bits, pero conceptualmente afecta a ambas
líneas.

**Decisión.**

1. **Dos módulos independientes, no un módulo con dos ISAs.**
   - `iflow-16`: la línea actual `src/iflow-vm`, de 16 bits y 64 KB, ISA/formato `.flow` v2, ABI
     `HOST` v2 y fuel. Se publica como `suite-iflow-16` y se identifica como `iflow-16`. Conserva
     **un único vCPU por diseño**: el techo de 64 KB y la simplicidad del kernel no permiten un
     multinúcleo intra-VM creíble; no se simula ni se promete.
   - `iflow-32`: la línea nueva `src/iflow-32`, de 32 bits, ISA/formato `.flow` v3, ABI `HOST` v3,
     MMU, scheduler, **multinúcleo intra-VM configurable** y espacio de direcciones de hasta 4 GiB.
     Se publica como `suite-iflow-32` y se identifica como `iflow-32`.
   - El nombre `iflow-vm` desaparece. No se conserva alias de módulo, artefacto, capability ni
     engine. El rename es un cambio breaking deliberado del régimen pre-1.0.

2. **El hub selecciona; ninguna VM despacha dos ISAs.** `iflow-16` solo acepta artefactos v2 y
   `iflow-32` solo acepta artefactos v3. No hay lector doble, migración en runtime ni despacho
   dual dentro de un núcleo. El hub resuelve el engine configurado y falla explícitamente si no
   está disponible; no hay fallback automático entre `iflow-32` e `iflow-16`.

3. **ADR-037 queda acotado a `iflow-32`.** Su regla «un `.flow` v2 se rechaza» aplica al motor de
   32 bits. `iflow-16` sigue aceptando `.flow` v2 y rechaza `.flow` v3. «La v3 sustituye a la v2»
   significa que `iflow-32` es la línea activa, no que `iflow-16` se elimine.

4. **Ciclo de vida asimétrico.** `iflow-16` es una línea soportada: recibe bugs, seguridad y
   compatibilidad. `iflow-32` es la línea activa. La paridad de features es una recomendación, no
   una obligación: si `iflow-16` puede heredar una feature de `iflow-32` sin romper sus límites,
   se evalúa y se hace; si no, se documenta la diferencia.

5. **Compartir primero, divergir después.** Se permite compartir `core-api`, modelos neutrales,
   puertos de host y utilidades que no impongan la semántica de una ISA. Todo componente
   compartido debe tener tests de contrato y no puede asumir 16/32 bits, MMU, fuel ni un ABI
   concreto. Si una línea necesita evolucionar de forma incompatible, el componente se bifurca y
   cada módulo conserva su copia. No se comparte el núcleo, el decoder, la MMU, el formato ROM ni
   el ABI `HOST`.

6. **Herencia técnica sujeta a verificación.** La MMU de `iflow-32` puede adaptarse a `iflow-16`
   si conserva el espacio de 16 bits, los 64 KB, la ABI v2 y el determinismo; no se asume que sea
   compatible. El modelo de ROM (cabecera, TOC, segmentos, BIOS/bootloader/kernel) aplica a ambas
   líneas, pero cada una define su layout. `.flow` es una imagen ROM, no un programa suelto. Los
   hallazgos de la auditoría manual que originaron el rediseño se revisan para ambas líneas, no
   solo para la de 32 bits.

7. **Migración de nombres.** El cambio se hace ahora, antes de V4: `src/iflow-vm` pasa a
   `src/iflow-16`, se crea `src/iflow-32` y se mueven a él los paquetes v3 aislados (`rom`,
   `isa.v3`, `memory.v3`, `host.v3`). Se actualizan `settings.gradle`, publicaciones, cobertura,
   CI, tests, configuración y documentación. No se conservan rutas ni identificadores antiguos.

8. **Layout `.flow` v2 de `iflow-16` (ROM2).** El contenedor de 16 bits se rediseña como imagen
   ROM con cabecera, TOC y segmentos tipados. El layout es propio de `iflow-16`; no se comparte
   con la ROM v3 de ADR-037.
   - **Cabecera de 32 bytes, little-endian.** `+0` 4 bytes magic ASCII `FLOW`; `+4` format `2`;
     `+5` ISA `2`; `+6` ABI mínima `2`; `+7` firmware mínimo `1`; `+8` `entryPoint` u16;
     `+10` `tocOffset` u16; `+12` `tocEntryCount` u16; `+14` flags de cabecera (0);
     `+16` 4 bytes layout magic ASCII `ROM2`; `+20` `payloadSize` u32 (bytes antes del hash);
     `+24` y `+28` reservados (0). Todos los campos numéricos multibyte son little-endian.
     `FLOW` y `ROM2` son firmas de bytes, no enteros multibyte, y se comparan byte a byte.
   - **TOC de 16 bytes por entrada.** `+0` tipo u8; `+1` flags u8; `+2` `vaddr` u16;
     `+4` `fileOffset` u32; `+8` `size` u16; `+10` `memSize` u16; `+12` reservado u32 (0).
     Todos los campos numéricos son little-endian. Los offsets son absolutos desde el inicio del
     archivo. Se admiten de 1 a 16 entradas.
   - **Tipos de segmento.** `BIOS=1`, `BOOTLOADER=2`, `KERNEL=3`, `PROGRAM=4`, `CONSTANTS=5`,
     `CAPABILITIES=6`, `DEBUG=7`. Del 1 al 5 son cargables; el 6 y el 7 son metadatos y siempre
     tienen `memSize=0`.
   - **Flags de segmento.** `R=1`, `W=2`, `X=4`, `U=8`; coinciden con los bits de `PageEntry`.
     El cargador escribe con `W` temporal y aplica al final los permisos declarados. `BIOS`,
     `BOOTLOADER` y `KERNEL` son `R/X` de kernel; `PROGRAM` es `R/X` de usuario; `CONSTANTS` es
     `R` y vive exactamente en `0x2000`.
   - **Región de imagen.** Los segmentos de código (BIOS, bootloader, kernel y programa) terminan como
     máximo en `0x2000`; el BIOS ocupa la página 0 y los demás empiezan alineados a página. Las
     constantes viven en `0x2000..0x2FFF` y el resto de la imagen no se usa para código. `entryPoint`
     debe caer dentro de un segmento ejecutable almacenado. El compilador emite BIOS, PROGRAM y
     CONSTANTS; los tipos `BOOTLOADER` y `KERNEL` quedan reservados para una separación posterior.
   - **Orden de bytes de datos e instrucciones.** Los payloads cargables se copian a memoria tal
     cual. El ISA lee palabras little-endian, los operandos inmediatos de 16 bits se codifican
     `[lo, hi]` y el segmento `CONSTANTS` almacena cada palabra en orden de memoria (byte bajo
     primero). Cabecera, TOC, instrucciones, direcciones, tamaños, constantes, memoria y MMIO son
     little-endian; no queda ningún campo big-endian ni conversión en el loader.
   - **Integridad y rechazo.** `payloadSize` debe coincidir con el archivo menos los 32 bytes del
     SHA-256 final; el hash cubre todo el payload. El layout anterior de 20 bytes se rechaza por
     `layoutMagic`: no hay lector legacy, alias ni migración en runtime. Un `.flow` v3 se rechaza
     por versión/layout, no se reinterpreta como v2. Un `.flow` v2 con la cabecera o el TOC
     big-endian anteriores no se lee con un segundo parser: se rechaza por las validaciones de
     tamaño/TOC y debe recompilarse con el ensamblador actual.

9. **Núcleos por línea.** `iflow-32` es una sola VM con `CORE_COUNT` núcleos configurables
   (ADR-037 enmendado, 2026-11-16); no es una VM por core. `iflow-16` conserva **un único vCPU por
   diseño**, por el techo estructural de 64 KB y por la simplicidad de su kernel. La decisión no se
   hereda entre líneas: no se simula multinúcleo en 16 bits ni se impone el único core a 32 bits.

**Consecuencias.**

- Positivas: aislamiento de seguridad y memoria, evolución independiente, compatibilidad real de
  la línea 16-bit, audiencias diferenciadas y ausencia de `if(version)` en los núcleos.
- Negativas: dos suites de tests, dos matrices de CI, dos documentaciones, dos superficies de
  seguridad y posibles portes de fixes entre líneas.
- Riesgos: que el código compartido se convierta en un contrato encubierto; que la paridad se
  vuelva obligatoria por inercia; que la configuración siga usando `iflow-vm` por costumbre. Se
  mitiga con tests de contrato, revisión de dependencias y eliminación explícita de alias.

**Criterios de validación.**

1. `:src:iflow-16:build` y `:src:iflow-32:build` pasan con la misma JDK.
2. `iflow-16` ejecuta sus tests y artefactos v2; `iflow-32` ejecuta sus tests y rechaza v2.
3. El hub selecciona `iflow-16` o `iflow-32` y falla explícito si el engine no existe.
4. No queda ninguna referencia activa a `iflow-vm` en módulos, artefactos, capabilities,
   configuración, tests ni documentación de trabajo.
5. No hay fallback automático entre las dos líneas ni lectura cruzada de `.flow`.

**Lo que este ADR no promete.** No declara paridad inmediata de features, no migra `.flow` v2 a
v3, no conserva alias de compatibilidad y no elimina `iflow-16` cuando `iflow-32` madure.

---
