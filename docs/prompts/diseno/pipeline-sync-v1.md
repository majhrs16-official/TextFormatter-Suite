# Pipeline y sincronización — contrato v1

> **Estado:** vigente. **Autoridad:** detalle normativo de ADR-036. Si este documento contradice
> el ADR, prevalece el ADR y este documento se corrige. **Ámbito:** pipeline configurable, reglas de
> independencia de features, etapas, trazas, acciones sync, superficies nativas y `outputs` por
> mensaje. Las referencias `ADR-036 §N` se resuelven en la sección §N de este documento.

## 1. Alcance y enmiendas

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

## 2. Regla de independencia

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

## 3. Configuración canónica

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

## 4. Catálogo de etapas y contrato

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

## 5. Semántica de resultados

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

## 6. Traza por etapa

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

## 7. `SET_CHANNEL` y segunda fase de routing

`route` se ejecuta una vez por destinatario. Si la decisión incluye `SET_CHANNEL` y el nuevo canal
puede cambiar permisos o destinatarios, el motor ejecuta una **segunda pasada de routing solo para los
destinatarios afectados**, con `phase=secondary`:

- No hay reentrega recursiva ni reentrada en `format` o `sync`.
- Una segunda mutación de canal en la misma unidad produce `DECISION_CONFLICT` y la unidad se
  rechaza, conforme a ADR-031.
- Si el cambio de canal añade destinatarios, se añaden a la lista de entrega y se les aplica la
  pasada secundaria de routing.
- La consola es un actor (`Actor.console()`); no hay `REDIRECT` ni `CHANNEL_REDIRECT`.

## 8. Recarga en caliente

`reloadPipeline()` relee `config/suite/config.yml` y, si `textformatter` está activo, su catálogo de
canales; construye un `PipelinePlan` inmutable y lo publica con un swap atómico. Estados:

- `APPLIED`: el plan nuevo es válido y se publicó. Los despachos en vuelo conservan el plan anterior.
- `FAILED`: el archivo es inválido o una etapa lanzó al construirse. Se conserva el plan anterior y
  se devuelve el detalle.
- `RESTART_REQUIRED`: el plan anterior no pudo drenarse en el plazo acordado o un módulo dejó recursos
  vivos. No se declara éxito.

Cambiar la lista no reinicia módulos ni recarga classloaders. Instalar, desinstalar o actualizar un
paquete reconstruye el plan después de confirmar la activación o desactivación, igual que hoy.

## 9. Sustitución de `SuiteHost` y `MessageDispatcher`

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

## 10. Catálogo de canales

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

## 11. Traducción

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

## 12. Sincronización

`sync-bus` publica una única etapa terminal `sync`, con fase `MESSAGE_POST` y fan-out best-effort a
todos los sinks registrados. Los sinks no son etapas: publican `SyncSink` y el bus los descubre; no
imponen orden. `sync` se ejecuta una vez por emisión, después de las entregas, y no altera el
mensaje. Si el bus no está instalado, la etapa se salta y el resto del pipeline no cambia.

El fan-out oficial usa solo el mínimo común múltiplo (`backend.info`, `message.send`). La superficie
nativa de cada backend y las acciones agnósticas para terceros se definen en §16.

## 13. Qué mensajes recorren el pipeline

Todos los `Message` inyectados por `MessageDispatch` recorren el mismo pipeline, con independencia de
`MessageType` (chat, join/quit/death, sistema o evento). Los adaptadores deciden cómo capturan el
evento y si lo cancelan (`CANCEL_EVENT`); eso no es una etapa de feature. Una etapa que no aplique a
un tipo devuelve `NOT_APPLICABLE` y la traza lo registra.

## 14. Guard de independencia

`checkArchitecture` gana una regla: ningún descriptor de `src/*` puede declarar un `Requirement`
sobre una capacidad publicada por una feature. La regla se implementa en la tarea Gradle contra una
lista de capacidades neutrales publicadas por el entorno y por los módulos técnicos (`api`, `jvm`,
`kernel`, `manager-spi`, `platform`), y se refuerza con un test que construye el grafo y comprueba
que una feature no puede satisfacer el requisito de otra. Las capacidades `stage:*` se validan como
`provide` y se rechazan como `require`. El consumo opcional en runtime no se declara como
`Requirement`; el guard no impide buscar un servicio ausente y degradar solo la operación que lo
necesita. El guard falla el build; no se degrada a advertencia.

## 15. Migración de descriptores

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

## 16. Superficie de acciones de los backends sync

### 16.1 Principio

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

### 16.2 Tres niveles

| Nivel | Quién lo usa | Acciones |
|---|---|---|
| Agnóstico oficial (LCM) | pipeline `sync`, features oficiales, `CoreTranslator.sendDiscord` | `backend.info`, `message.send`, `message.deliver` |
| Agnóstico extendido | programas de terceros | `textformatter.channel.set` y las acciones agnósticas que se acuerden |
| Nativo | programas de terceros | `<backend>.<segmento>[...].<verbo>`: `discord.channel.create`, `discord.channel.permission.overwrite`, `discord.role.assign`, `discord.message.edit`, `discord.member.ban`, `telegram.chat.member.ban`, `bukkit.event.cancel`, etc. |

La profundidad no está limitada a tres segmentos: el primer segmento es el backend (namespace) y el
resto es el id local de la acción. `Principal.Official` solo puede invocar el LCM; `Principal.Program`
solo puede invocar lo que tenga concedido. El bus lo verifica; no es una convención.

### 16.3 Contratos

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

### 16.4 Mínimo común múltiplo

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

### 16.5 `textformatter.channel.set`

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

### 16.6 Autoridad, concesiones y auditoría

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

### 16.7 Puente iFlow

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

#### 16.7.1 Codec y layout de cable

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

#### 16.7.2 Estados

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

#### 16.7.3 Convención de buffer

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

#### 16.7.4 Autoridad, despacho y mutación

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

### 16.8 Enmiendas y límites

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

## 17. Sincronización de salidas por mensaje

### 17.1 Problema

Hoy cada etapa tiene un solo backend global: `IflowConfig.engine` elige un motor iFlow,
`ChatTranslatorConfig.default-backend` elige un traductor y `SyncBus.broadcast` envía a todos los
sinks. No se puede decir «este mensaje va a Discord y a TCP, en este orden» ni «este mensaje
usa `gtranslate` e `iflow-wasm`». El `Message` no tiene un campo para expresarlo y la configuración
es global.

### 17.2 Un único namespace `sync`

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

### 17.3 Contrato

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

### 17.4 Configuración y precedencia

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

### 17.5 Etapas y backends

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

### 17.6 Orden y paralelismo

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

### 17.7 Efecto en las etapas

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

### 17.8 Enmiendas y límites

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

## Criterios de aceptación y tests

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
