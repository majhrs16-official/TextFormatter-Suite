# ADR — TextFormatter Suite v1.0 (pre-release)

Fecha: 2026-08-14 · Estado: aceptado (pre-release, sin versión estable) · Última actualización: 2026-11-06

> **Documento vivo:** se actualiza en el mismo cambio que introduce una decisión de arquitectura.
> Solo conserva decisiones vigentes; el historial cerrado, las fases y el estado operativo viven en
> `PLAN.md` y en Git. Este documento no mantiene roadmaps, tablas de estado ni registros de
> implementación.
>
> **Régimen pre-1.0 (ADR-034, 2026-11-04):** no existe versión estable ni producción. Todos los
> módulos, componentes, contratos y configuraciones se alinean a la base `1.0`; los números `2.x`,
> `3.x` y superiores del historial son etiquetas de iteración, no versiones publicadas. No se
> mantiene retrocompatibilidad de features ni contratos, ni código de compatibilidad; los módulos
> legacy independientes se declaran explícitamente. `1.0.0` es la etiqueta de release de la base
> integrada; el betatesting/E2E real se ejecuta **después** y sus fallos se corrigen en la línea
> beta. `1.0` es la versión estable, declarada solo cuando el proyecto completo está consolidado y
> sin errores críticos de diseño. Ver `docs/prompts/contexto/philosophy.md` §0 y `PLAN.md` «Purga
> pre-1.0».

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
con `CANCEL_EVENT` o falla con `ERROR` (ADR-031). Los motores `iflow-firewall`, `iflow-vm` e
`iflow-wasm` son backends seleccionables por el hub (ADR-032); no son targets de política.

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
clases, caché LRU acotada (1024) y `spring-expression` solo en su classpath. `iflow-vm` e
`iflow-wasm` no usan SpEL. `rules.yml` acepta el esquema de reglas del motor legacy; el grafo del
editor se valida pero no se ejecuta. Cualquier política nueva debe implementarse en la VM o en Wasm.

**Consecuencias.** Una sola superficie de políticas por motor; `textformatter` no depende de Spring;
los consumidores nuevos usan el contrato vigente y no el motor legacy.

---

## ADR-002 — Puertos de entrega y despacho neutral

**Estado:** Aceptada.

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

**Estado:** Aceptada e implementada.

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
| Paquetes base | `textformatter`, `messages`, `chattranslator`, `iflow`, `iflow-vm`, `iflow-firewall`, `iflow-wasm` | Paquetes instalados por defecto; los motores iFlow no se excluyen |
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

**Estado:** Aceptada e implementada (2026-10-29).

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

**Estado:** Aceptada e implementada (2026-10-29).

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

**Estado:** Aceptada. Diseño congelado; implementación pendiente. El orden de trabajo vive en
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

**Estado:** Aceptada e implementada (2026-10-30).

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

**Estado:** Aceptada e implementada (2026-10-30).

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

**Estado:** Aceptada e implementada. Sustituye la secuencia de activación/rollback de ADR-026 y
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

**Estado:** Aceptada e implementada.

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

**Estado:** Aceptada e implementada. Complementa ADR-026 y ADR-027; no cambia las tres etapas de
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

**Estado:** Aceptada e implementada.

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

**Estado:** Aceptada e implementada.

**Decisión.**

1. Se añade el paquete `iflow` como **hub**. Publica `IflowHub` y `Router`, y es el único punto que
   elige el backend activo.
2. Los motores publican `IflowBackend` (`name()`, `router()`, `isAvailable()`). No publican la
   selección ni conocen al hub.
3. La configuración canónica del hub es `config/iflow/config.yml`:
   `engine: iflow-firewall | iflow-vm | iflow-wasm` y `fallback-on-unavailable: true|false`. Solo se
   aceptan los nombres canónicos; no hay alias cortos ni `default-backend` en este archivo. Las
   rutas `config/manager/iflow/config.yml` y `manager/config/iflow/config.yml` son incorrectas y no
   se aceptan como fallback.
4. El valor por defecto es `iflow-firewall`. Si el backend configurado no está disponible y
   `fallback-on-unavailable` es `true`, el hub usa el primer backend disponible según la prioridad
   explícita `iflow-firewall` → `iflow-vm` → `iflow-wasm`, independiente del orden de activación de
   los módulos. Si no hay ninguno, `IflowHub.router()` devuelve `null` y el runtime deja el pipeline
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

**Estado:** Aceptada e implementada.

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
   `iflow-vm` e `iflow-firewall`.

**Consecuencias.** Un `.wasm` sin sidecar no se ejecuta; el hub puede seguir con otro backend. El
operador debe actualizar el sidecar al cambiar la política. El watcher es un hilo daemon por router y
se cierra con el módulo. La integridad del paquete y la del artefacto de política son
responsabilidades separadas y documentadas.

---

## ADR-034 — Régimen pre-1.0: versión base 1.0, rolling release y purga de compatibilidad

**Estado:** Aceptada. La implementación de la purga y el reseteo de versiones se rastrea en
`PLAN.md`, sección «Purga pre-1.0». Esta ADR sustituye cualquier política anterior de
retrocompatibilidad, versiones ficticias o validación exclusivamente automatizada.

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

**Estado:** Aceptada e implementada en `src/coretranslator` (2026-11-04).

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

## ADR-036 — Pipeline secuencial configurable, features opcionales, acciones sync y multiplexación de salidas

**Estado:** Aceptada (2026-11-06). Enmienda ADR-019, ADR-024, ADR-025, ADR-032, ADR-008 y ADR-003
en los puntos que se indican; adopta ADR-031 en la semántica de routing (§16 regula la exposición de
`SET_CHANNEL` como acción agnóstica sin alterar D01–D05). §17 añade la selección de backend por
mensaje y enmienda ADR-032/ADR-035 solo en esa selección: el default y el fallback del hub y de la
fachada de traducción se conservan. La implementación se rastrea en `PLAN.md` (PL-1, PL-2 y PL-3).

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
retirados. §17 añade un override de multiplexor/backend por mensaje; no cambia el orden del pipeline
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

El multiplexor `sync` es `sync-bus`; no se añade un módulo nuevo. Su registro de sinks, colas,
reintentos y métricas se reutilizan; la selección por mensaje se define en §17.

#### 16.2 Tres niveles

| Nivel | Quién lo usa | Acciones |
|---|---|---|
| Agnóstico oficial (LCM) | pipeline `sync`, features oficiales, `CoreTranslator.sendDiscord` | `backend.info`, `message.send` |
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
    // start/stop/setListener se conservan; send(Message) se sustituye por message.send
}

public record SyncSurface(
        String backend,                     // namespace: "discord", "telegram", "textformatter"
        SemVer contractVersion,
        Set<SyncActionSpec> actions
) {}

public record SyncActionSpec(
        String id,                          // id local: "message.send", "channel.create",
                                            // "channel.permission.overwrite"
        ActionKind kind,                    // READ, WRITE, DESTRUCTIVE, MODERATION
        List<SyncParamSpec> params,
        SyncParamSpec result,               // null si no devuelve valor
        boolean idempotent,
        long timeoutMillis
) {}

public enum ActionKind { READ, WRITE, DESTRUCTIVE, MODERATION }

public enum SyncValueType {
    STRING, INTEGER, BOOLEAN, DURATION,
    ACTOR_REF, CHANNEL_REF, MESSAGE_REF, ROLE_REF, OBJECT
}

public record SyncParamSpec(
        String name,
        SyncValueType type,
        boolean required,
        SyncParamSpec item                // solo para listas; null en el resto
) {}

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
}
```

`SyncParamSpec` tipa los argumentos como datos neutrales (`STRING`, `INTEGER`, `BOOLEAN`, `DURATION`,
`ACTOR_REF`, `CHANNEL_REF`, `MESSAGE_REF`, `ROLE_REF`, `OBJECT`). `OBJECT` es un mapa anidado
validado por el backend; el núcleo no interpreta su contenido. Ningún tipo de JDA, Telegram, Bukkit
o Fabric cruza esta frontera. `backend` es el namespace; las acciones del host usan `textformatter`,
no `"*"`. El nombre canónico de una acción es `<backend>.<action>` y admite profundidad arbitraria.
La capacidad se deriva del origen: `sync.<action>` para acciones de sink y
`module.<moduleId>.<action>` para acciones de módulo/host. `SyncSurface` solo lo publican los sinks;
las acciones del host no tienen superficie y el bus las resuelve en su handler interno.

#### 16.4 Mínimo común múltiplo

- `backend.info`: `READ`, idempotente; devuelve nombre, versión y versión de superficie.
- `message.send`: `WRITE`, idempotente por `idempotencyKey`; argumentos `message` (`MESSAGE_REF`) y
  `destination` (`CHANNEL_REF`, opcional).
- `SyncBus.send(Message)` es una comodidad tipada sobre `message.send`; no es un segundo camino.
- `SyncBus.sendTo(backend, Message)` es la forma dirigida de `message.send`; usa la misma ruta y
  devuelve un resultado por backend, no un booleano de aceptación.
- Un backend que no pueda implementar el LCM no se publica: no existe un `send` que finja éxito.

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
  ni lo convierte en multiplexor. La capacidad es `module.textformatter.channel.set`: `textformatter`
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
   por llamada de host y tamaño máximo de payload. El agotamiento devuelve `RateLimited` o `Timeout`,
   no un éxito parcial.
6. **Auditoría.** Registro append-only con hash del programa, backend, acción, actor, hash de
   argumentos (nunca valores), resultado y duración. Los argumentos marcados como secreto se
   redactan. La ruta la fija `storage.md`; cambiarla requiere autorización.
7. **Idempotencia.** Solo se reintenta una acción marcada `idempotent`; la clave es
   `message.id() + action + target`. Las acciones no idempotentes no se reintentan.
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

#### 16.8 Enmiendas y límites

- **ADR-003.** Se añaden invariantes: concesión por hash, marca destructiva, auditoría, fail-closed y
  cuotas. Las firmas siguen siendo opcionales; la concesión es del operador, no una prueba de
  autoría.
- **ADR-031.** D01–D05 no cambian. `textformatter.channel.set` reutiliza la segunda fase de D02 y
  retira el import dedicado, sin reintroducir `REDIRECT` ni `CHANNEL_REDIRECT`.
- **ADR-019.** Los `sync-*` dejan de requerir `sync-bus` (§15); publican `SyncSink` y el bus los
  descubre.
- **`storage.md` §2.** La regla «un submódulo declara `require(...)` del padre» contradice §15 y esta
  sección; actualizarla requiere autorización explícita.
- **Rutas.** Las concesiones viven en `config/iflow/grants.yml` (layout `config/<módulo>/`), no en
  `config/suite/iflow/`: `config/suite/` es solo para la configuración global.
- **Especificación iFlow.** El baseline v3 limita al guest a contenido/metadatos; esta sección amplía
  su autoridad a efectos de host. Requiere autorización para modificar
  `docs/prompts/diseno/iflow-cpu-design.md`, `iflow-cpu-implement.md` y la especificación de la ISA.
- **No promete** rollback, transacciones entre backends, reintentos automáticos ni autoridad
  ambiente. La superficie es amplia por diseño; la concesión es el límite.

### 17. Multiplexación y salidas por mensaje

#### 17.1 Problema

Hoy cada multiplexor tiene un solo backend global: `IflowConfig.engine` elige un motor iFlow,
`ChatTranslatorConfig.default-backend` elige un traductor y `SyncBus.broadcast` envía a todos los
sinks. No se puede decir «este mensaje va a Discord y a TCP, en este orden» ni «este mensaje
usa `gtranslate` e `iflow-wasm`». El `Message` no tiene un campo para expresarlo y la configuración
es global.

#### 17.2 `sync-bus` no es un multiplexor, pero pasa a serlo

`ChatTranslator` y `IflowHub` **seleccionan** un backend: prioridad, default y fallback. `sync-bus`
es un **bus de fan-out**: registra sinks, los arranca, reparte el mensaje a todos y ofrece
`sendTo(nombre, mensaje)`; no selecciona por mensaje ni aplica fallback. Por eso no es equivalente a
los otros dos.

No se añade un módulo `sync-mux`: duplicaría el registro de sinks, las colas y el ciclo de vida, y
crearía una dependencia feature→feature que §2 prohíbe. `sync-bus` se convierte en el multiplexor
`sync` y conserva su implementación de bus. Si más adelante hace falta una política de selección
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
        List<String> targets,               // "multiplexer.backend"; vacía y declared=true: sin salidas
        ParallelMode parallel               // INHERIT por defecto
) {
    public static OutputPlan inherit() {
        return new OutputPlan(false, List.of(), ParallelMode.INHERIT);
    }
}
```

Cada target es una cadena `<multiplexer>.<backend>`. El resolutor parte por el **primer punto**: el
primer segmento es el multiplexor y el resto es el backend, que puede contener puntos. No se admiten
segmentos vacíos ni targets sin punto. No hay `OutputTarget` en el contrato público; el par
`(multiplexer, backend)` es una representación interna del resolutor.

`Message` gana `outputPlan()`. El plan es inmutable y viaja con el mensaje; no se guarda en un
`ThreadLocal`. Un `OutputPlan` ausente (`declared=false`) no es lo mismo que uno vacío
(`declared=true, targets=[]`): el primero hereda, el segundo declara «sin salidas». `declared=false`
exige `targets=[]`; cualquier otra combinación es `InvalidArgument`.

El motor resuelve el plan efectivo una vez por emisión y lo expone en `PipelineContext.outputs()`.
La traza registra de dónde salió (`message`, `suite` o `module-default`). Las etapas no mutan el plan.

#### 17.4 Configuración y precedencia

```yaml
# config/suite/config.yml
outputs:
  - chattranslator.gtranslate
  - sync.discord
  - sync.tcp
  - sync.udp
outputs-parallel: true   # default; false = finalización secuencial
config-version: 1.0
```

Precedencia:

1. `Message.outputPlan()` con `declared=true`.
2. `config/suite/config.yml` → `outputs` (y `outputs-parallel`).
3. Default del módulo multiplexor: `iflow` usa su `engine`/fallback; `chattranslator` usa
   `default-backend`; `sync` conserva el fan-out a todos los sinks, incluidos `bukkit` y `fabric`.

Una lista vacía en el mensaje o en la configuración significa «sin salidas» y no se interpreta como
«todas». Un target cuyo multiplexor o backend sea desconocido es un error explícito
(`Unsupported`/`Invalid`), nunca un skip silencioso. Las claves canónicas son `outputs` y
`outputs-parallel`; cada elemento de `outputs` es una cadena `<multiplexer>.<backend>`. No hay claves
`multiplexer` ni `backend`, ni aliases en español (ADR-034).

#### 17.5 Multiplexores y backends

| Multiplexer | Módulo | Backends | Tipo |
|---|---|---|---|
| `iflow` | `iflow` (hub) | `iflow-vm`, `iflow-wasm`, `iflow-firewall` | transformación |
| `chattranslator` | `chattranslator` | `gtranslate`, `ltranslate` | transformación |
| `sync` | `sync-bus` | `discord`, `telegram`, `http`, `websocket`, `tcp`, `udp`, `velocity`, `bukkit`, `fabric` | entrega |

Un target se escribe `<multiplexer>.<backend>`: `iflow.iflow-wasm`, `chattranslator.gtranslate`,
`sync.discord`, `sync.bukkit`.

Los backends `bukkit` y `fabric` son sinks locales: su superficie incluye las operaciones del entorno
(jugadores, consola, canales, scoreboard) y la manipulación de eventos. Una acción de evento actúa
sobre el evento ligado al mensaje actual, no sobre un objeto de evento arbitrario, y no sustituye a
`CANCEL_EVENT` de ADR-031: ese sigue siendo el target oficial de cancelación; la acción nativa es
para programas de terceros.

Los multiplexores de transformación admiten **como máximo un target** por mensaje; un segundo
target del mismo multiplexor es `Conflict`. `sync` admite varios, en orden. Un multiplexor nuevo
declara su tipo (`TRANSFORM` o `DELIVER`) y su capacidad `multiplexer:<id>`, que es declarativa y no
puede ser un `Requirement` (misma regla que `stage:*`).

`textformatter` no es un multiplexor: es la etapa `format` y el namespace de las acciones del host.
No aparece en `outputs`.

#### 17.6 Orden y paralelismo

- La lista es ordenada. El motor **inicia** los targets en ese orden y la traza y el resultado
  conservan ese orden.
- No se paraleliza entre etapas: `route` → `format` → `sync` tienen dependencia de datos. La
  traducción de `<tr>` ocurre dentro de `format` y usa el target `chattranslator` cuando `outputs`
  lo selecciona. Se paraleliza dentro del mismo multiplexor de entrega (`sync`), que es donde hay
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
  de cada multiplexor (por ejemplo, dos targets `sync.*`) y en la traza de salidas.

#### 17.7 Efecto en las etapas

- `route` usa el target `iflow` si existe; si no, el hub aplica su default y fallback de ADR-032.
- La traducción de `<tr>` dentro de `format` usa el target `chattranslator` si existe; si no, la
  fachada aplica su `default-backend` y su fallback de ADR-035. No hay etapa `translate`.
- `sync` recorre todos los targets `sync` en orden y llama al sink correspondiente; si no hay
  targets, aplica el fan-out completo actual, incluida la entrega local por `bukkit`/`fabric`.
- `format` no es un multiplexor y no se selecciona por `outputs`; solo consume el target
  `chattranslator` para resolver `<tr>`.

#### 17.8 Enmiendas y límites

- **ADR-032.** El hub conserva su `engine`, su prioridad y su `fallback-on-unavailable`; `outputs`
  añade un override por mensaje. No se convierte al hub en un enrutador de motores sin default.
- **ADR-035.** `ChatTranslator` conserva su `default-backend` y su override de contexto; el
  `outputs` del pipeline sustituye el uso de `ThreadLocal` para la selección por mensaje, no la
  fachada.
- **ADR-019 / ADR-025.** `sync-bukkit`/`sync-fabric` siguen capturando eventos; §17 añade que
  también son sinks locales (`bukkit`/`fabric`) y que su superficie incluye las operaciones sobre el
  evento actual. La captura no es la razón de ser del sink, pero el sink expone el entorno completo.
- **`config-schema-v2.2.md`.** `outputs` es una lista de cadenas `<multiplexer>.<backend>` y
  `outputs-parallel` un booleano; requieren autorización para actualizar el esquema.
- **No promete** orden de finalización en modo paralelo, transacciones entre backends ni selección
  automática por salud. El modo secuencial es explícito.

### Respuestas a las preguntas abiertas de `PLAN.md`

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
| 13 | ¿Cómo se elige el backend de cada multiplexor por mensaje? | `outputs` en el `Message`; si falta, `config/suite/config.yml`; si tampoco, el default del módulo. §17. |
| 14 | ¿`sync-bus` es el multiplexor de sync? | Sí: es el hub de fan-out y pasa a seleccionar por mensaje; no se añade un módulo nuevo. §17. |

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
10. **Superficie sync:** el LCM oficial (`backend.info`, `message.send`) funciona sin acciones
    nativas; `textformatter.channel.set` solo lo invoca un tercero con concesión; una acción nativa
    sin concesión devuelve `Denied` sin efecto; `DESTRUCTIVE`/`MODERATION` exigen
    `allow-destructive`; el puente VM y el de Wasm devuelven los mismos estados para la misma
    llamada.
11. **Multiplexación:** un mensaje con `sync.discord`, `sync.tcp` y `sync.bukkit` entrega a los tres
    en el orden declarado; sin `outputs` se usa el default de `config/suite/config.yml`; un target
    desconocido falla de forma explícita y no se convierte en éxito parcial silencioso;
    `outputs-parallel: false` serializa la finalización.
12. **Sink local:** `sync-bukkit` y `sync-fabric` publican una superficie que incluye las operaciones
    del entorno y de eventos, y entregan a jugadores/consola; `bukkit.event.cancel` no sustituye a
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
- El mensaje deja de tener un único backend global por multiplexor: `outputs` selecciona `iflow`,
  `chattranslator` y `sync` por unidad, con default de suite y default de módulo.
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
