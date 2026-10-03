# AUDITORÍA TÉCNICA INTEGRAL — TEXTFORMATTER SUITE

**Repositorio:** `majhrs16-official/TextFormatter-Suite`
**Commit auditado:** `1a98940222d030564029148dfc6e542c21bc4649`
**Fecha del commit:** 2026-10-02 21:57:15 UTC
**Versión declarada:** 2.1.0-SNAPSHOT

---

# 1. Resumen ejecutivo

TextFormatter Suite es un proyecto técnicamente ambicioso y, en términos de arquitectura, está considerablemente por encima de un plugin convencional de chat.

La base conceptual es sólida:

* core relativamente agnóstico de plataforma;
* `Message` inmutable;
* SPI/ports para infraestructura;
* iFlow como motor de routing/policy;
* TextFormatter separado del routing;
* `TranslationService` desacoplado de los proveedores;
* módulos Gradle separados;
* adapters de plataforma;
* sincronización externa;
* configuración declarativa;
* web editor;
* module manager;
* observabilidad;
* tests y benchmarks;
* soporte pretendido para Spigot y Fabric.

La arquitectura **no es humo**. Hay separación real de responsabilidades y varias decisiones son genuinamente buenas.

Sin embargo, el proyecto está en una situación interesante:

> **La arquitectura conceptual está bastante más madura que la integración operacional completa.**

El principal problema ya no es "falta arquitectura". El problema es que existen varios puntos donde las capas están correctamente diseñadas pero **el wiring concreto todavía tiene inconsistencias, stubs, lifecycle leaks o caminos alternativos que no utilizan la arquitectura nueva**.

Los problemas más importantes encontrados son:

1. **`TranslationService`, `RateLimiter` y parte de Observability no se cierran al hacer reload**, provocando acumulación de threads/executors.
2. El supuesto **`SyncBus` central todavía no es realmente el bus central del sistema**: en el runtime de Spigot/Fabric solo se registran Discord y WebSocket, y el outbound sigue pasando por `DiscordBridge.mirror()`.
3. `MessageDispatcher` utiliza un scheduler para `sleep`, pero inmediatamente hace `future.get()`: **el worker sigue bloqueado**, por lo que la solución no consigue realmente el objetivo declarado.
4. En Fabric, `MessageDispatcher.dispatch()` puede ejecutarse en el **server thread**, y el pipeline puede esperar traducción/IO hasta 10 segundos. Bajo saturación, `CallerRunsPolicy` puede ejecutar procesamiento pesado directamente en el hilo principal.
5. El `SpEL evaluator` para templates se crea en `SuiteHost` pero **no se conecta al `TextFormatter`**. La capacidad `<expr>` está, por tanto, arquitectónicamente preparada pero no está realmente cableada en el camino normal.
6. El sistema de acciones SpEL de iFlow tiene una discrepancia entre lo que documenta y lo que realmente puede modificar: `executeAction()` evalúa la expresión, pero no entrega un `ScriptSurface` mutable.
7. `InWorldHandler` contiene bastante código que es todavía **stub/minimal**, especialmente sign channels y formatting real.
8. `MessageCodec` no conserva toda la semántica de `Message`: pierde qualifiers de dirección, recipients específicos, sonidos, tooltips, etc. Esto limita seriamente la fidelidad del cross-server sync.
9. `HttpTransport` valida redirects, pero en redirects `307/308` **preserva el método y pierde el body/headers**.
10. El CI actual **no construye `fabric-host`**, pese a que el commit actual afirma haberlo incorporado al build.
11. La documentación todavía contiene afirmaciones históricas incompatibles con el estado actual, especialmente sobre Fabric.
12. El `shadowJar` de Spigot está configurado para excluir las dependencias de proyecto, mientras el plugin tiene referencias directas a `SuiteHost`, `MessageDispatcher`, etc. Esto requiere un artifact smoke test; estático, el empaquetado merece especial atención.

Por tanto:

> **TextFormatter Suite ya tiene una arquitectura seria, pero todavía no tiene una implementación operacional igualmente madura en todos sus bordes.**

No lo consideraría un proyecto experimental simple. Tampoco lo consideraría todavía una implementación production-ready a escala network-level.

---

# 2. Cobertura de auditoría

El árbol del commit contiene:

* **518 archivos**
* **155 directorios**
* **208 archivos Java**
* **59 Markdown**
* **43 Gradle**
* **29 lockfiles**
* **26 JavaScript**
* **11 archivos `META-INF/services`**
* **27 módulos Gradle** declarados en `settings.gradle`

Se inspeccionaron profundamente:

* `core-api`
* `kernel`
* `host`
* `textformatter`
* `iflow`
* `transport`
* `sync-http`
* `sync-tcpudp`
* `sync-websocket`
* `sync-velocity`
* `sync-bus`
* `observability`
* `manager-api`
* `manager-impl`
* `spigot-host`
* `fabric-host`
* `inworld`
* `web-editor`
* configuración/schema
* CI/CD
* load tests
* tester/runtime tests
* historial Git reciente
* `README`
* `src/README`
* `docs/PLAN.md`
* configuración Gradle y `settings.gradle`

El árbol completo fue inventariado. La revisión de código se concentró en el camino crítico, lifecycle, seguridad, concurrencia, transporte, adapters y módulos de integración; no ejecuté un servidor Minecraft real ni un build Gradle completo en esta auditoría, por lo que las conclusiones que dependan de ejecución están marcadas como hipótesis/probables.

---

# 3. Arquitectura real

## 3.1 Topología

La arquitectura efectiva es aproximadamente:

```text
                 ┌──────────────────────┐
                 │ Spigot / Fabric      │
                 │ Entry Point          │
                 └──────────┬───────────┘
                            │
                            ▼
                 ┌──────────────────────┐
                 │ Platform Adapter     │
                 │ ActorDirectory       │
                 │ ChatDelivery         │
                 │ permissions          │
                 └──────────┬───────────┘
                            │
                            ▼
                 ┌──────────────────────┐
                 │ MessageDispatcher    │
                 │ fan-out + lifecycle  │
                 └──────────┬───────────┘
                            │
             ┌──────────────┴──────────────┐
             ▼                             ▼
    ┌────────────────┐           ┌────────────────┐
    │ iFlow Router   │           │ TextFormatter  │
    │ policies       │           │ templates      │
    │ permissions    │           │ MiniMessage    │
    │ rules          │           │ translation    │
    └───────┬────────┘           └───────┬────────┘
            │                            │
            └────────────┬───────────────┘
                         ▼
                 ┌──────────────────────┐
                 │ ChatDelivery         │
                 └──────────────────────┘
```

Y alrededor:

```text
             Translation providers
                     │
                     ▼
              TranslationService
                     │
                     ▼
              TranslationExecutor


 Sync edges ──► SyncBus ──► Dispatcher
 Discord  ────────────────────┘
 WebSocket ────────────────────┘
 HTTP
 TCP/UDP
 Telegram
 Velocity
       ↑
       │
 actualmente no todos están realmente cableados al bus runtime
```

---

# 4. Arquitectura pretendida vs arquitectura implementada

## Arquitectura pretendida

El proyecto pretende tener:

* módulos independientes;
* kernel que resuelve módulos;
* host como composition root;
* dominio/core sin conocer plataforma;
* ports/adapters;
* SyncBus como frontera única de sincronización;
* platform adapters como únicos componentes conscientes de Minecraft;
* servicios externos intercambiables.

Esta arquitectura está documentada de forma bastante consistente.

## Arquitectura implementada

La separación existe, pero hay dos sistemas simultáneos:

### Sistema moderno

```text
Platform
  ↓
SuiteHost
  ↓
MessageDispatcher
  ↓
Router
  ↓
Formatter
  ↓
Delivery
```

### Sistema de integración paralelo

```text
DiscordBridge
  ↓
JdaDiscordSink
```

y:

```text
SyncBus
  ├── Discord
  └── WebSocket
```

El problema es que ambos coexisten.

`DiscordBridge.mirror()` sigue enviando directamente al sink:

```text
dispatcher.dispatch()
       │
       └── DiscordBridge.mirror()
                  │
                  ▼
             JdaDiscordSink
```

Mientras que `SyncBus` también registra ese mismo sink.

Por tanto, **SyncBus no es todavía la verdadera autoridad arquitectónica de sincronización**.

Esto no invalida el diseño; significa que la migración quedó a medio camino.

---

# 5. Pipeline completo

Para Spigot:

```text
AsyncPlayerChatEvent
        │
        ▼
ActorDirectory
        │
        ▼
ChannelSelector
        │
        ▼
Message
        │
        ├── initiator
        │
        └── others
              │
              ▼
       MessageDispatcher
              │
              ▼
       resolveSourceLanguage()
              │
              ▼
       emission rate limit
              │
              ▼
       expand recipients
              │
              ▼
     parallel recipient tasks
              │
              ▼
         SuiteHost.deliver()
              │
        ┌─────┴─────┐
        ▼           ▼
     iFlow       language
        │           │
        │           ▼
        │      TranslationService
        │
        ▼
     Rule / transform
        │
        ▼
    TextFormatter
        │
        ▼
     Component
        │
        ▼
    ChatDelivery
        │
        ▼
  Bukkit main thread
```

Esta es una arquitectura razonablemente limpia.

El problema principal está en los detalles de concurrencia y lifecycle, no en el concepto.

---

# 6. `Message`

`Message` es una de las partes mejor diseñadas del proyecto.

Características positivas:

* inmutabilidad;
* `Builder`;
* `withX()`;
* copia defensiva de arrays;
* UUID;
* dirección separada;
* source/target language;
* estado de cancelación;
* channel;
* sounds;
* sleep;
* resolved source language.

La decisión:

```text
Message original
       ↓
Message.withX()
       ↓
nuevo Message
```

es apropiada para un pipeline concurrente.

Esto reduce una clase importante de bugs donde un recipient podría observar una mutación realizada por otro recipient.

### Evaluación

**Código: 9/10**

Es una de las piezas que no recomendaría reescribir.

---

# 7. iFlow

La separación entre:

* `Router`
* `Rule`
* `RouteDecision`
* `RouteOutcome`
* `PolicyTarget`
* `TransformOp`
* `ScriptSurface`

es buena.

También es correcta la decisión de que el routing sea por `(message × recipient)`.

La semántica:

```text
emitter
receiver
channel
direction
rule
policy
```

es mucho más potente que un simple `if player.hasPermission()` dentro del listener.

---

# 8. Problema confirmado: SpEL actions no tienen superficie mutable

`DefaultRouter.apply()` hace:

```text
executeAction(rule, working, emitter, recipient)
```

y `executeAction()` construye bindings normales.

Posteriormente, los transforms sí crean:

```text
ScriptSurface surface =
    new ScriptSurface(...);
```

Por tanto:

```text
SpEL action
    ↓
bindings
    ↓
evaluation
```

no recibe el `ScriptSurface`.

Mientras:

```text
TransformOp
    ↓
ScriptSurface
    ↓
mutation
```

sí lo recibe.

Esto crea una discrepancia entre el modelo de scripting y el comportamiento real.

Si una acción pretende hacer algo equivalente a:

```text
cancel()
skipTranslate()
```

no existe en `executeAction()` una superficie mutable sobre la que actuar.

**Tipo:** Bug/defecto funcional
**Severidad:** Alta
**Confianza:** Confirmado

---

# 9. SpEL template evaluator desconectado

En `SuiteHost` se crea:

```java
ExpressionEvaluator templateEvaluator =
    new SpelExpressionEvaluator(...);
```

pero inmediatamente después:

```java
TextFormatter formatter =
    TextFormatters.create(channels, translation, placeholders, logger);
```

y `TextFormatters.create()` crea:

```java
TemplateRenderer renderer =
    new TemplateRenderer(translation, placeholders, logger);
```

sin pasar `templateEvaluator`.

Es decir:

```text
SpelExpressionEvaluator
        │
        X
        │
TemplateRenderer
```

La instancia se crea pero no se usa.

Esto es código muerto y, más importante, significa que una feature documentada queda desconectada del pipeline.

**Tipo:** Bug funcional
**Severidad:** Media/Alta
**Confianza:** Confirmado

---

# 10. TranslationService

Hay varias decisiones excelentes:

* executor dedicado;
* bounded queue;
* límite de threads;
* timeout;
* cancellation;
* cache;
* in-flight deduplication.

La eliminación del `ForkJoinPool.commonPool()` para traducción fue correcta.

El dedup:

```text
key
 ↓
inFlightTranslations.computeIfAbsent()
 ↓
single Future
 ↓
multiple consumers
```

es especialmente importante bajo fan-out.

Evita:

```text
100 recipients
       ↓
100 identical HTTP requests
```

y puede convertirlo en:

```text
100 recipients
       ↓
1 translation request
       ↓
100 consumers
```

Esto es una mejora arquitectónica real.

---

# 11. Problema de lifecycle: TranslationService

El problema es que `TranslationService` implementa `AutoCloseable`, pero `SuiteHost` no lo cierra.

En Spigot:

```java
TranslationService translation =
    new TranslationService(...);

SuiteHost reloaded =
    SuiteHost.bootstrap(..., translation, ...);
```

En reload:

```text
old Runtime.close()
      ↓
MessageDispatcher.close()
SyncBus.close()
Observability.stop()
...
```

pero:

```text
TranslationService.close()
```

no ocurre.

Cada reload puede dejar:

```text
translation-worker-*
translation-scheduler-*
```

vivos.

Con suficiente cantidad de reloads:

```text
reload 1 → executor
reload 2 → executor
reload 3 → executor
...
```

**Tipo:** Resource leak
**Severidad:** Alta
**Confianza:** Confirmado

La solución correcta es hacer que el runtime/composition root sea responsable de cerrar todos los servicios que crea.

---

# 12. Segundo lifecycle leak: RateLimiter

`DefaultRouter` crea:

```java
this.rateLimit = new RateLimiter();
```

`RateLimiter` crea:

```text
ScheduledExecutorService
    └── rate-limiter-purge
```

y tiene:

```java
close()
```

pero `DefaultRouter` no implementa `AutoCloseable`.

Tampoco `SuiteHost` cierra el router.

Resultado:

```text
reload
  ↓
new Router
  ↓
new RateLimiter
  ↓
new purge thread
```

El anterior no se cierra.

**Tipo:** Resource leak
**Severidad:** Alta
**Confianza:** Confirmado

Este es probablemente uno de los bugs de lifecycle más importantes que quedan.

---

# 13. Tercer lifecycle leak: MetricsEndpoint

`MetricsEndpoint` crea un `ThreadPoolExecutor` local:

```java
ThreadPoolExecutor boundedExecutor = ...
server.setExecutor(boundedExecutor);
```

pero no conserva una referencia al executor.

`stop()` detiene el scheduler y el HTTP server, pero no tiene referencia al executor para llamar:

```text
shutdown()
```

Por tanto el executor HTTP puede sobrevivir al stop.

**Tipo:** Resource leak
**Severidad:** Media/Alta
**Confianza:** Confirmado

---

# 14. MessageDispatcher

La estructura general es buena:

```text
4 core threads
32 max threads
1000 queue
CallerRunsPolicy
```

La intención de protegerse contra thread explosion es correcta.

También es buena la deduplicación de recipients:

```java
resolved.stream().distinct().toList();
```

y la expansión de:

* initiator;
* others;
* all;
* console;
* specific;
* permission;
* world;
* radius.

Esto es bastante potente.

---

# 15. Problema de `sleep`

El código dice:

```java
sleepScheduler.schedule(
    () -> sleepFuture.complete(null),
    sleepMillis,
    TimeUnit.MILLISECONDS
);

sleepFuture.get();
```

El scheduler evita que el thread haga:

```java
Thread.sleep()
```

pero el worker sigue esperando:

```text
dispatcher-worker
       │
       ├── schedule()
       │
       └── future.get()
              │
              └── BLOQUEADO
```

Por tanto:

> Se eliminó el bloqueo mediante `Thread.sleep()`, pero no se eliminó el bloqueo del worker.

Con 32 workers y 32 mensajes con `sleep=5000`:

```text
32 workers
   ↓
32 sleeps
   ↓
32 workers ocupados
```

El siguiente tráfico queda en queue.

La solución real requeriría convertir el delivery en una continuación asíncrona:

```text
route
  ↓
schedule
  ↓
return
  ↓
completion callback
  ↓
delivery
```

sin bloquear ningún worker.

**Tipo:** Defecto de concurrencia
**Severidad:** Alta
**Confianza:** Confirmado

---

# 16. `CallerRunsPolicy` y Fabric

`CallerRunsPolicy` es una decisión razonable como protección de backpressure, pero tiene una consecuencia muy importante:

Si la queue de 1000 elementos está llena:

```text
MessageDispatcher
      │
      └── CallerRunsPolicy
              │
              ▼
          caller thread
```

En Spigot, el chat principal es asíncrono.

En Fabric, `ServerMessageEvents.CHAT_MESSAGE` se está procesando desde el contexto del servidor.

Por tanto, bajo saturación:

```text
Fabric server thread
        ↓
dispatcher
        ↓
queue full
        ↓
CallerRunsPolicy
        ↓
host.deliver()
        ↓
translation / formatting
```

puede ejecutar procesamiento pesado en el hilo principal.

Además, `dispatch()` hace:

```java
future.get(10, TimeUnit.SECONDS)
```

por recipient.

Así que incluso sin queue saturation, el caller puede esperar a que terminen los workers.

En Fabric esto es un problema serio.

**Tipo:** Defecto de concurrencia
**Severidad:** Alta
**Confianza:** Confirmado por flujo de código

---

# 17. Fabric: riesgo de bloqueo del servidor

El problema anterior se combina con:

```text
resolveSourceLanguage()
```

que puede llamar:

```text
translation.detect()
```

antes de expandir recipients.

Esto significa que el caller thread puede realizar detección de idioma externa.

En Fabric:

```text
CHAT_MESSAGE
    ↓
dispatch()
    ↓
resolveSourceLanguage()
    ↓
TranslationService.detect()
    ↓
future.get()
```

El hilo del servidor puede quedar esperando una operación de traducción.

Esto debe solucionarse antes de considerar el adapter Fabric production-ready.

---

# 18. Fabric chat: integración incompleta

El listener registra:

```text
ServerMessageEvents.CHAT_MESSAGE
```

y genera su propio broadcast.

Pero no existe en el código revisado una integración equivalente al `claim-mode` de Spigot que suprima explícitamente el camino vanilla.

La arquitectura necesita verificar mediante integración real que no se produzca:

```text
vanilla chat
     +
TextFormatter chat
```

Es un punto que requiere un E2E real.

Lo clasifico como:

**Confianza:** Probable, requiere ejecución sobre Fabric API exacta.

---

# 19. Fabric InWorld

`FabricInWorldHandler` es explícitamente minimalista.

Por ejemplo:

```java
getSignChannel(...)
```

recorre los canales pero termina sin resolver un canal y devuelve:

```java
return null;
```

`getFormattedMessage()` devuelve directamente:

```java
return original;
```

Las acciones de click también son mayormente stubs.

Por tanto:

```text
Fabric in-world integration
```

no está al nivel del core.

Esto es importante porque el commit más reciente se llama:

> `feat: fabric-host complete`

pero "host compilable" y "feature-complete" no son equivalentes.

**Tipo:** Feature incompleta
**Severidad:** Media
**Confianza:** Confirmado

---

# 20. Spigot InWorld

El problema tampoco es exclusivo de Fabric.

El `InWorldHandler` de Spigot tiene:

```java
getSignChannel(...)
    → return null;
```

y:

```java
getFormattedMessage(...)
    → return original;
```

Por tanto signs no constituyen todavía un pipeline completo de TextFormatter.

El soporte de containers/books sí está mucho más avanzado, pero el módulo general sigue siendo parcialmente scaffold.

---

# 21. SyncBus

La interfaz está bien planteada:

```text
register
unregister
broadcast
sendTo
setInboundListener
start
stop
```

pero la implementación no cumple completamente lo que su documentación afirma.

La documentación dice:

* deduplication;
* backpressure;
* centralized pipeline.

`DefaultSyncBus.broadcast()` hace simplemente:

```text
for sink:
    sink.send(message)
```

de forma síncrona.

No existe:

* queue global;
* backpressure global;
* scheduling;
* dedup;
* bulkhead;
* per-sink isolation.

Por tanto el contrato documentado es más fuerte que la implementación.

---

# 22. SyncBus tampoco es todavía el outbound authority

En el runtime actual:

```text
DiscordBridge
    ↓
JdaDiscordSink
```

sigue siendo responsable del outbound mirror.

El `SyncBus` registra ese sink, pero no se observa un camino central:

```text
dispatcher
   ↓
syncBus.broadcast()
```

para los mensajes salientes.

Además, Spigot/Fabric registran actualmente:

* Discord;
* WebSocket.

No:

* HTTP;
* TCP;
* UDP;
* Telegram;
* Velocity.

Por tanto:

> `SyncBus` existe y es útil, pero la migración arquitectónica a un verdadero synchronization hub todavía no está terminada.

**Tipo:** Defecto de arquitectura/integración
**Severidad:** Alta
**Confianza:** Confirmado

---

# 23. MessageCodec

`MessageCodec` es una de las partes que más limita el escalamiento multi-server.

Serializa:

* id;
* type;
* channel;
* sender;
* direction kind;
* language;
* translate;
* cancelled;
* texts.

Pero no conserva completamente:

* direction qualifier;
* explicit recipients;
* sounds;
* tooltips;
* color mode;
* `formatPapi`;
* `show`;
* `sleepMillis`;
* resolved source;
* otros detalles del estado.

Y al decodificar:

```text
PERMISSION
WORLD
RADIUS
SPECIFIC
```

no se conservan con semántica completa.

El switch solo recupera:

```text
INITIATOR
ALL
CONSOLE
OTHERS
```

y cualquier otro caso cae a:

```text
OTHERS
```

Por tanto:

```text
Direction.WORLD("survival")
```

puede convertirse en:

```text
Direction.others()
```

en el otro servidor.

Eso es una pérdida semántica real.

**Tipo:** Bug/defecto de protocolo
**Severidad:** Alta para sync avanzado
**Confianza:** Confirmado

---

# 24. TCP

`TcpSink.send()` hace:

```java
socket.connect(new InetSocketAddress(remoteHost, remotePort));
```

sin timeout explícito.

Aunque el listener tiene timeout, el outbound connect puede depender de los timeouts del sistema operativo.

Si el `SyncBus` lo utilizara directamente:

```text
broadcast()
    ↓
TCP sink
    ↓
connect()
    ↓
bloqueo
```

podría bloquear el thread que ejecuta el bus.

Esto refuerza la conclusión de que `SyncBus.broadcast()` no debería ser una operación síncrona sin aislamiento por sink.

---

# 25. UDP

UDP tiene la propiedad normal de:

* no garantía de entrega;
* no ordering;
* no retransmisión;
* posible spoofing;
* tamaño máximo de datagrama.

Es adecuado como edge de baja garantía, pero no debe tratarse como transporte equivalente a TCP/WS.

La arquitectura debería declarar explícitamente semánticas por sink:

```text
reliable
ordered
at-most-once
best-effort
lossy
```

Eso todavía no está modelado en el `SyncSink` general.

---

# 26. HTTP Transport — redirect bug

`HttpTransport` desactiva redirects automáticos correctamente y valida el destino.

Eso es bueno.

Pero al procesar `307/308`:

```java
conn.setRequestMethod(requestMethod);
```

preserva el método.

Sin embargo el código no conserva:

* body;
* headers originales.

Por tanto:

```text
POST body=A
   ↓
307
   ↓
POST body=""
```

puede ocurrir.

La propia documentación/comentarios indican que POST debe preservarse, pero el body no se reconstruye.

**Tipo:** Bug
**Severidad:** Media
**Confianza:** Confirmado

---

# 27. SSRF

La protección SSRF de `HttpTransport` es bastante mejor que una implementación trivial.

Incluye:

* loopback;
* RFC1918;
* link-local;
* CGNAT;
* ULA IPv6;
* multicast;
* `isLoopbackAddress`;
* `isSiteLocalAddress`;
* `isLinkLocalAddress`;
* resolución de todas las IPs;
* validación de redirects.

Esto está bien diseñado conceptualmente.

Pero el comentario:

> "prevent DNS rebinding attacks"

es demasiado fuerte.

El código hace:

```text
DNS resolve
   ↓
validate
   ↓
open connection
   ↓
DNS may resolve again
```

No existe pinning entre la IP validada y la conexión.

Por tanto todavía existe una ventana TOCTOU de DNS.

Lo clasificaría como:

**Riesgo residual de seguridad**, no como vulnerabilidad demostrada.

---

# 28. Seguridad SpEL

El proyecto hizo un esfuerzo considerable:

* no `T()`;
* no constructor;
* method resolver;
* zero-argument methods;
* clases de dominio explícitamente reconocidas;
* cache limitada.

Esto es mucho mejor que ejecutar SpEL sin restricciones.

Pero existe una política interesante:

```java
return true;
```

para clases que no sean explícitamente bloqueadas.

Es decir, el sandbox funciona principalmente mediante:

```text
deny dangerous classes
+
allow getters
```

y no:

```text
allowlist estricta de tipos
```

Eso deja una superficie considerablemente mayor de la necesaria.

No he encontrado una explotación directa demostrable desde el código revisado, por lo que no lo clasifico como vulnerabilidad confirmada.

**Recomendación:** convertir el resolver en allowlist de clases/tipos, especialmente si expressions pueden provenir de configuraciones administrables externamente.

---

# 29. Observability

Tiene buenas ideas:

* metrics;
* health;
* debug;
* bounded executor;
* localhost por defecto;
* debug auth;
* removal de `/debug/simulate`.

La seguridad de los endpoints está mejor que en versiones anteriores.

Sin embargo:

### Problema 1

`createDefault()` hace:

```java
debugAuthToken = null
```

y `DebugEndpoint` rechaza requests si no existe token.

Resultado:

```text
debug endpoint iniciado
        ↓
todas las peticiones
        ↓
403
```

Por tanto el endpoint debug por defecto existe pero está inutilizable.

### Problema 2

El parámetro `debugPort` de `Observability` no se utiliza realmente porque `DebugEndpoint` crea:

```text
127.0.0.1:9091
```

directamente.

Esto es un bug de configuración.

### Problema 3

El executor HTTP de metrics no se conserva para shutdown.

---

# 30. Configuración

La configuración tiene una arquitectura razonable:

```text
config.yml
channels/*.yml
translators/*.yml
sync/*.yml
rules.yml
extensions/*.yml
```

`SafeConstructor` es una decisión correcta.

También es positiva la política:

> unknown keys are ignored

para compatibilidad hacia atrás.

No se está imponiendo validación arbitraria de cada valor.

Eso encaja bien con el principio de permitir combinaciones poco usuales mientras sean operacionalmente válidas.

---

# 31. Schema single-source

Aquí existe todavía deuda.

El proyecto tiene:

```text
ConfigPath enum
        ↓
ConfigSchemaGenerator
        ↓
paths.json
```

pero el web editor también mantiene `model.js`.

Además `docs/PLAN.md` todavía marca T4 como pendiente.

Por tanto el concepto de single-source existe, pero la sincronización todavía no es totalmente automática.

Peor aún:

`verifySchema` existe como tarea, pero el CI mostrado no la ejecuta.

**Tipo:** Deuda técnica
**Severidad:** Media

---

# 32. Web Editor

El web editor es considerablemente más serio de lo que parece.

Incluye:

* state store;
* undo/redo;
* validation;
* graph;
* cycle detection;
* config;
* sync;
* translators;
* import/export;
* schema;
* i18n;
* integration tests.

El graph editor tiene:

* input;
* condition;
* transform;
* loop;
* sleep;
* output;
* redirect;
* channel redirect.

Eso representa bastante trabajo.

El principal problema es que el editor puede describir configuraciones que el backend todavía no implementa completamente.

Esto es especialmente visible con:

```text
graph
  ↓
SpEL actions
  ↓
ScriptSurface
```

y con:

```text
in-world
```

---

# 33. Personalización

La capacidad de configuración es uno de los puntos fuertes.

El sistema permite modificar:

* channels;
* permissions;
* routing;
* language;
* translation;
* templates;
* sounds;
* tooltips;
* rules;
* sync;
* repositories;
* module allowlist;
* extensions.

Además no parece existir una tendencia general a prohibir configuraciones simplemente porque sean poco convencionales.

Esto es correcto.

---

# 34. Git history

La evolución reciente es bastante clara.

### 2026-09-26

Se introdujo el Rules Graph Editor y se ampliaron considerablemente las herramientas de configuración.

### 2026-09-29

Se hizo un gran security/audit pass:

* doble delivery;
* main-thread blocking;
* WebSocket auth;
* MiniEscape;
* translation dedup;
* MessageCodec;
* SSRF;
* RateLimiter;
* observability;
* module manager.

### 2026-09-29

Se añadió documentación de CodeGuide para los módulos.

### 2026-10-02

Se hizo un commit de gran tamaño:

```text
+1634 / -412
```

para completar `fabric-host` y consolidar fixes.

Esto demuestra una arquitectura que está **evolucionando activamente**, no una que haya permanecido congelada.

Pero también muestra un patrón:

```text
auditoría
  ↓
gran refactor
  ↓
nueva auditoría
  ↓
nuevos integration gaps
```

El siguiente paso debería ser menos arquitectura nueva y más **integration/E2E stabilization**.

---

# 35. Testing

La situación es mixta.

Coberturas declaradas:

| Módulo        | Umbral |
| ------------- | -----: |
| host          |    23% |
| iflow         |    24% |
| textformatter |    59% |

Estos valores son demasiado bajos para considerar cubiertos los caminos críticos.

El problema no es necesariamente que "23% sea malo" en abstracto.

El problema es qué partes quedan fuera.

Precisamente los puntos de mayor riesgo están en:

* lifecycle;
* integrations;
* platform adapters;
* network edges;
* concurrency;
* reload;
* cross-server behavior.

Y esas son las áreas donde el unit coverage tradicional suele ser insuficiente.

---

# 36. Tester runtime

El `TestService` tiene bastantes escenarios:

* routing;
* translation;
* MiniMessage;
* placeholders;
* concurrency;
* stress;
* burst;
* memory;
* Unicode;
* large message;
* offline player;
* hot paths.

Eso es positivo.

Pero su profiler tiene limitaciones importantes.

Por ejemplo:

```java
ThreadMXBean.getCurrentThreadCpuTime()
```

mide el thread que ejecuta el test, no necesariamente los workers donde ocurre el procesamiento.

Por tanto:

```text
dispatcher worker CPU
translation worker CPU
main thread CPU
```

no quedan correctamente atribuidos.

Además varios tests de stress son resilientes:

```text
error → logger.warn()
```

en lugar de:

```text
assert failure
```

Eso es útil para stress exploratorio, pero no como regression gate.

---

# 37. Load testing

La presencia de JMH y Gatling es una fortaleza.

Sin embargo el benchmark de múltiples recipients tiene una limitación importante:

se crea una lista de 50 actors, pero el `ActorDirectory` mock devuelve esencialmente un solo recipient.

Por tanto el benchmark llamado:

```text
dispatcherMultipleRecipients
```

no representa realmente un fan-out de 50 jugadores.

Esto reduce su valor para estimar network-scale.

No se deben extraer números de throughput a partir de esos benchmarks.

---

# 38. Rendimiento estático

Los principales hotspots arquitectónicos son:

### Hotspot A — fan-out

```text
N recipients
   ↓
N CompletableFutures
   ↓
N RoutingResult
   ↓
N Template rendering
   ↓
N delivery tasks
```

El coste es esencialmente:

```text
O(R)
```

por mensaje, donde `R` es número de recipients.

Eso es inevitable hasta cierto punto para traducción personalizada por receptor.

---

### Hotspot B — traducción

Con idiomas diferentes:

```text
message
   ↓
R recipients
   ↓
potentially R language targets
```

La deduplicación ayuda mucho, pero el límite actual de translation executor es:

```text
16 max threads
1000 queue
```

Esto es una limitación explícita.

---

### Hotspot C — Bukkit delivery

Cada recipient puede generar una tarea main-thread:

```text
recipient
   ↓
Bukkit scheduler
   ↓
sendMessage()
```

Con miles de recipients:

```text
1 message
  ↓
5000 recipients
  ↓
5000 main-thread tasks
```

Esto puede convertirse en un cuello de botella serio aunque el procesamiento previo sea perfectamente paralelo.

---

# 39. Estimación de latencia

No hay benchmarks de producción suficientes para dar p50/p95/p99 reales.

Por tanto no sería correcto inventarlos.

Sí pueden identificarse límites estructurales:

* translation timeout: **30 s**
* dispatcher recipient timeout: **10 s**
* translation executor: **4–16 workers**
* dispatcher executor: **4–32 workers**
* dispatcher queue: **1000**
* WebSocket rate limit: **100 msg/s por conexión**
* WebSocket max message: **64 KiB**
* HTTP response limit: **1 MiB**
* HTTP inbound body limit: **1 MiB**
* Velocity retry queue: **10,000** por configuración por defecto.

Estos son límites arquitectónicos, no throughput medido.

---

# 40. Escalabilidad

## Escenario A — servidor pequeño

Decenas de jugadores.

### Resultado

La arquitectura debería ser suficiente.

El fan-out es manejable y los problemas de concurrencia raramente alcanzarán los límites.

**Riesgo:** bajo/medio.

---

## Escenario B — red mediana

Cientos de jugadores distribuidos en varios servidores.

Aquí aparecen:

* translation fan-out;
* main-thread scheduling;
* sync;
* burst;
* queue;
* cross-server dedup.

La arquitectura puede evolucionar hasta este escenario, pero necesita:

* SyncBus real;
* async pipeline;
* batching;
* backpressure;
* E2E tests.

**Riesgo:** medio.

---

## Escenario C — red grande

Miles de jugadores.

El cuello de botella ya no sería principalmente CPU del formatter.

Sería:

```text
fan-out
+
translation
+
delivery scheduling
+
cross-server replication
+
main-thread Minecraft APIs
```

La arquitectura actual no demuestra esta escala.

**Riesgo:** alto.

---

## Escenario D — network-scale

A escala de una network grande:

```text
millones de recipient deliveries/min
```

la arquitectura necesitaría una capa de distribución explícita.

El patrón actual:

```text
server
  ↓
dispatcher
  ↓
each player
```

no puede convertirse simplemente en:

```text
server grande
```

y escalar indefinidamente.

Se necesitarían:

* event bus;
* batching;
* per-server aggregation;
* cross-server message IDs;
* dedup distribuido;
* backpressure;
* transport queues;
* circuit breakers;
* retry semantics;
* observability distribuida;
* eventualmente gateway/proxy-side routing.

---

# 41. ¿Puede llegar a network-level?

Sí, arquitectónicamente.

No porque ya lo haga.

La estructura correcta ya existe en buena parte:

```text
Message
Router
Formatter
Translation
Ports
Adapters
Sync edges
```

El mayor trabajo restante es transformar:

```text
local plugin architecture
```

en:

```text
distributed messaging architecture
```

sin contaminar el core.

Eso es perfectamente compatible con la dirección actual del proyecto.

---

# 42. Clean Architecture

## Lo que está bien

El core no depende directamente de Bukkit.

`core-api` mantiene SPIs.

`ChatDelivery` está en host/port.

`ActorDirectory` es abstracto.

`PermissionChecker` es abstracto.

`TranslationService` abstrae providers.

Esto es Clean Architecture real.

## Lo que falla

`SuiteHost` es un composition root correcto, pero hay wiring duplicado y caminos alternativos.

`SuiteBootstrap` resuelve el grafo pero no compone realmente todos los servicios.

También existe código de integración que instancia directamente determinados sinks.

Por tanto:

**Clean Architecture: 7.9/10**

No es una fachada de paquetes. Existe de verdad, pero todavía hay leakage y composición duplicada.

---

# 43. Hexagonal Architecture

Aquí el proyecto está incluso mejor.

Hay ports explícitos:

```text
ChatDelivery
ActorDirectory
PermissionChecker
TranslationService
SyncSink
SyncListener
PlaceholderResolver
```

y adapters:

```text
SpigotChatDelivery
FabricChatDelivery
JdaDiscordSink
WebSocketSyncSink
TcpSink
UdpSink
...
```

La dirección general es correcta:

```text
external world
      ↓
adapter
      ↓
port
      ↓
core
```

El principal problema es que algunos adapters contienen todavía lógica de negocio o wiring excesivo.

**Hexagonal: 8.4/10**

---

# 44. Modularidad

`settings.gradle` contiene 27 módulos.

Las fronteras principales son razonables:

```text
core-api
kernel
host
iflow
textformatter
transport
translation
sync
observability
manager
extensions
platform
```

Esto permite evolucionar componentes independientemente.

El principal defecto es que `spigot-host` conoce demasiados módulos:

* sync;
* manager;
* extension;
* observability;
* inworld;
* tester;
* core.

Como composition root es parcialmente justificable.

Pero:

```text
spigot-host → tester
```

es especialmente discutible para producción.

**Modularidad: 8.3/10**

---

# 45. Separación de responsabilidades

Generalmente buena.

Las principales clases grandes están actuando como composition roots o orchestrators, no simplemente acumulando lógica de negocio.

Pero:

* `TextFormatterSuitePlugin`;
* `TextFormatterSuiteMod`;
* `DefaultModuleLifecycle`;

están creciendo demasiado.

No son automáticamente God classes, pero están acercándose a esa zona.

El problema futuro será que cualquier feature nueva termine añadiéndose al platform entry point.

**Separación: 7.4/10**

---

# 46. Documentación

La documentación de arquitectura ha mejorado muchísimo.

La existencia de:

```text
src/README.md
src/*/README.md
```

es una muy buena decisión.

El CodeGuide reduce bastante el coste de onboarding.

El problema actual es la sincronización.

Ejemplo claro:

`fabric-host/README.md` todavía describe el módulo como:

```text
EXCLUDED FROM BUILD
42 compilation errors
```

mientras el HEAD actual contiene un rewrite de Fabric y `settings.gradle` incluye `fabric-host`.

Eso es documentación históricamente correcta pero actualmente incorrecta.

**Documentación: 7.0/10**

La estructura documental es 8.5; sincronización actual aproximadamente 5.5.

---

# 47. Roadmap de mejoras

## P0 — inmediato

### P0-1 — Lifecycle ownership

Hacer:

```text
SuiteHost implements AutoCloseable
```

o equivalente.

Cerrar explícitamente:

```text
TranslationService
Router / RateLimiter
MessageDispatcher
Observability
SyncBus
```

y cualquier executor creado durante bootstrap.

**Beneficio:** elimina leaks de reload.
**Esfuerzo:** medio.
**Riesgo:** bajo.

---

### P0-2 — Artifact smoke test

Construir el Spigot release artifact en un entorno limpio y comprobar:

```bash
jar tf textformatter-suite-spigot.jar
```

y posteriormente iniciar un classloader limpio.

Especialmente comprobar presencia/resolución de:

```text
SuiteHost
MessageDispatcher
core-api
iflow
textformatter
kernel
manager
```

El `shadowJar` actual excluye explícitamente dependencias.

**Beneficio:** elimina una incertidumbre crítica de packaging.
**Esfuerzo:** bajo.
**Riesgo:** bajo.

---

### P0-3 — Fabric async boundary

Nunca ejecutar:

```text
translation
dispatcher.wait
external IO
```

desde el server thread.

El entry point Fabric debe hacer:

```text
server event
    ↓
capture immutable event data
    ↓
async dispatcher
    ↓
processing
    ↓
server.execute(delivery)
```

**Beneficio:** evita freezes del servidor.
**Esfuerzo:** medio.
**Riesgo:** medio.

---

# 48. P1 — Alto

## P1-1 — Terminar SyncBus

Mover:

```text
Discord
HTTP
TCP
UDP
Telegram
WebSocket
Velocity
```

a una única abstracción.

Eliminar:

```text
DiscordBridge.mirror()
```

como camino especial.

---

## P1-2 — SyncBus async

Cambiar:

```text
broadcast()
    ↓
for sink
    sink.send()
```

por aislamiento:

```text
SyncBus
 ├── Discord queue
 ├── HTTP queue
 ├── TCP queue
 ├── WS queue
 └── ...
```

con:

* bounded queues;
* per-sink backpressure;
* circuit breaker;
* retry policy;
* metrics.

---

## P1-3 — Corregir `sleep`

No:

```text
schedule()
future.get()
```

sino:

```text
schedule()
return CompletionStage
```

---

## P1-4 — Completar protocolo MessageCodec

Definir un wire schema formal.

Por ejemplo:

```json
{
  "id": "...",
  "type": "CHAT",
  "channel": "chat.global",
  "direction": {
    "kind": "WORLD",
    "qualifier": "lobby-1"
  },
  "sender": {},
  "content": {},
  "metadata": {}
}
```

y versionarlo:

```text
protocolVersion: 2
```

---

## P1-5 — SpEL actions

Decidir explícitamente:

```text
condition
action
transform
```

y darles una semántica común.

No debería haber:

```text
action → Message immutable
transform → ScriptSurface mutable
```

sin una razón explícita.

---

# 49. P2 — Medio

* schema CI gate;
* documentation sync check;
* elevar coverage de host/iflow;
* tests de reload;
* tests de classloader;
* tests de cross-server codec;
* tests de concurrent reload;
* tests de queue saturation;
* tests de translation timeout;
* tests de interrupted translation;
* tests de Fabric main-thread safety;
* completar observability endpoints;
* corregir debug port;
* completar in-world.

---

# 50. P3 — Bajo

* eliminar imports duplicados;
* eliminar variables no usadas como `templateEvaluator`;
* consolidar comentarios históricos;
* reducir duplicación entre Spigot/Fabric bootstrap;
* extraer configuration/runtime factories;
* mejorar naming de algunos adapters.

---

# 51. Matriz final

| Área               | Puntuación | Estado                          | Principales problemas                      |
| ------------------ | ---------: | ------------------------------- | ------------------------------------------ |
| Código             | **7.8/10** | Bueno                           | lifecycle, stubs, algunos caminos muertos  |
| Arquitectura       | **8.2/10** | Muy buena                       | composición duplicada                      |
| Clean Architecture | **7.9/10** | Buena                           | wiring y boundaries incompletos            |
| Hexagonal          | **8.4/10** | Muy buena                       | adapters todavía con lógica/wiring         |
| Modularidad        | **8.3/10** | Muy buena                       | host demasiado amplio                      |
| Responsabilidades  | **7.4/10** | Buena                           | platform roots creciendo                   |
| Legibilidad        | **8.0/10** | Buena                           | documentación histórica mezclada           |
| Features           | **6.8/10** | Mixta                           | algunas features son parcialmente scaffold |
| Personalización    | **8.5/10** | Excelente                       | gran superficie declarativa                |
| Seguridad          | **7.7/10** | Buena                           | SpEL/SSRF aún requieren hardening          |
| Rendimiento        | **6.5/10** | Mixto                           | fan-out, main-thread, translation          |
| Escalabilidad      | **5.8/10** | Insuficiente para network-scale | sync/fan-out/backpressure                  |
| Concurrencia       | **5.9/10** | Necesita trabajo                | sleep, Fabric, lifecycle                   |
| Testing            | **6.2/10** | Aceptable                       | cobertura baja en piezas críticas          |
| Mantenibilidad     | **7.2/10** | Buena                           | lifecycle + integración                    |
| Documentación      | **7.0/10** | Buena pero desincronizada       | Fabric/PLAN histórico                      |

---

# 52. Potencial

## Potencial teórico

**Muy alto.**

El proyecto podría evolucionar hacia:

```text
platform-independent message processing engine
+
Minecraft adapters
+
distributed synchronization layer
+
translation infrastructure
+
declarative policy engine
+
extension system
```

No es necesario cambiar radicalmente el core para conseguirlo.

---

## Potencial arquitectónico

**Alto.**

La arquitectura actual permite:

```text
Spigot
Fabric
Velocity
Discord
HTTP
WebSocket
TCP
UDP
```

sin obligar a meter sus APIs en `core-api`.

Ese es probablemente el mayor activo técnico del proyecto.

---

## Potencial alcanzado

Mi estimación:

**≈ 65–70%**

No porque falten muchas clases.

Más bien porque:

```text
arquitectura ≈ 80–85%
integración ≈ 60–65%
production hardening ≈ 55–65%
network scalability ≈ 40–50%
```

La cifra global queda aproximadamente en:

**~68% de madurez técnica materializada.**

---

# 53. ¿Qué tan bueno es realmente?

Es un proyecto **bueno y técnicamente ambicioso**, con una arquitectura que ya puede considerarse seria.

No lo describiría como:

> "un plugin de chat con muchas features".

La descripción más correcta sería:

> **un message-processing engine modular con adapters de plataforma, policy engine, translation layer y extensiones de sincronización distribuida.**

La diferencia es importante.

---

# 54. Partes excepcionalmente bien diseñadas

Las cinco que considero más fuertes:

### 1. `Message`

La inmutabilidad y el modelo de dirección son excelentes.

### 2. Ports/Adapters

`ActorDirectory`, `ChatDelivery`, `PermissionChecker`, `SyncSink`, etc. son boundaries reales.

### 3. TranslationExecutor

El paso desde common pool hacia executor bounded + dedup es una mejora de producción genuina.

### 4. iFlow

La idea de evaluar:

```text
message × recipient
```

permite políticas mucho más sofisticadas que un router convencional.

### 5. Modularidad

27 módulos no son automáticamente algo bueno, pero en este caso las fronteras principales tienen sentido.

---

# 55. Partes mediocres

Las áreas que todavía están claramente por debajo del resto:

### InWorld

Demasiado scaffold.

### SyncBus

Arquitectónicamente prometedor, operacionalmente incompleto.

### Fabric

El rewrite es importante, pero necesita integración real y pruebas de thread model.

### Testing E2E

Hay mucho test infrastructure, pero falta validar el sistema completo como producto.

### Lifecycle

El proyecto ha prestado mucha atención a lifecycle, pero todavía quedan leaks significativos.

---

# 56. Peores defectos actuales

Si tuviera que reducir toda la auditoría a cinco problemas técnicos:

```text
1. Lifecycle incompleto
2. Fabric thread model
3. SyncBus incompleto
4. Protocol codec incompleto
5. Features declaradas que todavía son parcialmente stubs
```

No son problemas de "estilo".

Son los puntos que más separan el proyecto de una versión verdaderamente network-grade.

---

# 57. Mayor fortaleza arquitectónica

La mayor fortaleza es:

> **el core puede evolucionar sin quedar atado a Bukkit/Fabric.**

Esto no es simplemente porque haya interfaces.

Se observa en la dirección real de dependencias.

La idea:

```text
Minecraft
    ↓
adapter
    ↓
SPI
    ↓
core
```

está realmente presente.

Eso es difícil de conseguir en plugins Minecraft porque normalmente el API de Bukkit termina contaminando todo el código.

Aquí se evitó en gran medida.

---

# 58. Mayor riesgo futuro

El mayor riesgo no es performance.

Es:

> **que la arquitectura continúe expandiéndose mientras los caminos de integración permanecen parcialmente duplicados.**

El patrón peligroso sería:

```text
feature nueva
   ↓
nuevo módulo
   ↓
nuevo adapter
   ↓
nuevo wiring
   ↓
nuevo lifecycle
```

sin consolidar primero:

```text
composition root
runtime lifecycle
SyncBus
protocol
async boundary
```

Eso produciría una arquitectura cada vez más grande pero progresivamente más difícil de razonar.

---

# 59. Mantenibilidad

Actualmente:

**7.2/10**

Un desarrollador experimentado puede entender el proyecto.

El CodeGuide mejora mucho el onboarding.

Pero tendrá problemas si sigue creciendo sin resolver:

* composition;
* lifecycle;
* platform bootstrap duplication;
* synchronization architecture.

---

# 60. Extensibilidad

Actualmente:

**8.3/10**

La extensibilidad es uno de los puntos fuertes.

Añadir:

```text
nuevo translator
nuevo ChatDelivery
nuevo SyncSink
nuevo PlaceholderResolver
nuevo platform adapter
```

es conceptualmente sencillo.

La dificultad aparece en:

```text
lifecycle
configuration
registration
runtime wiring
```

no en el core.

---

# 61. Preparación multi-plataforma

### Core

**Alta.**

### Spigot

**Alta**, con problemas de packaging/lifecycle que deben verificarse.

### Fabric

**Media.**

La arquitectura está preparada, pero la implementación todavía requiere E2E y trabajo de integración.

### Velocity

El sink existe, pero no equivale a tener un adapter completo del engine.

---

# 62. Preparación para una network Minecraft grande

Actualmente:

**No suficiente para afirmarlo.**

No por falta de CPU en el formatter.

Por falta de:

* distributed backpressure;
* sync semantics;
* dedup distribuido;
* protocol versioning;
* asynchronous delivery architecture;
* main-thread isolation;
* benchmarks realistas;
* failure testing.

---

# 63. Qué tendría que cambiar para network-level

El core probablemente **no necesita una reescritura**.

La evolución debería ser:

```text
                    ┌───────────────────┐
                    │ Message Engine    │
                    │                   │
                    │ iFlow             │
                    │ Formatter         │
                    │ Translation       │
                    └─────────┬─────────┘
                              │
                     immutable events
                              │
                    ┌─────────▼─────────┐
                    │ Async Dispatcher  │
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │ Sync Bus          │
                    │                   │
                    │ queues            │
                    │ retry             │
                    │ dedup             │
                    │ backpressure      │
                    └─────────┬─────────┘
                              │
            ┌─────────────────┼─────────────────┐
            ▼                 ▼                 ▼
        Velocity          WebSocket          HTTP
            │                 │                 │
            └─────────────────┼─────────────────┘
                              ▼
                         other servers
```

Y los Minecraft adapters deberían reducirse a:

```text
capture event
      ↓
immutable event
      ↓
async engine
      ↓
schedule delivery
```

---

# 64. Qué NO debería tocarse

Estas partes ya están bien encaminadas:

### `Message` immutable

No convertirlo nuevamente en mutable.

### SPI de plataforma

No meter Bukkit/Fabric en `core-api`.

### `TranslationService`

La separación provider/service es correcta.

### iFlow como autoridad de routing

No distribuir la lógica de permisos por los adapters.

### `ChatDelivery`

Mantener la entrega específica de plataforma fuera del core.

### Direction model

No volver al antiguo modelo rígido `from/to`.

---

# 65. Prioridad real

Si tuviera que ordenar el trabajo actual:

```text
1. Lifecycle / resource ownership
2. Fabric async boundary
3. SyncBus real
4. MessageCodec/protocol
5. E2E tests
6. Spigot artifact smoke test
7. Complete in-world
8. Documentation synchronization
9. Coverage expansion
10. New features
```

Y específicamente:

> **No añadiría otra gran arquitectura nueva antes de terminar los puntos 1–6.**

El proyecto ya tiene suficientes abstracciones.

Ahora necesita consolidación.

---

# 66. Veredicto técnico final

TextFormatter Suite no está en la categoría de:

```text
"plugin experimental"
```

pero tampoco en:

```text
"production-ready network infrastructure"
```

Está en una categoría intermedia mucho más interesante:

```text
        arquitectura seria
               +
        implementación amplia
               +
        integración todavía desigual
```

Mi valoración global aproximada sería:

**7.2/10 como software actual.**

Y:

**8.3–8.6/10 como base arquitectónica/potencial técnico.**

La diferencia entre ambas cifras representa precisamente el trabajo que queda.

La conclusión más importante de la auditoría es esta:

> **El proyecto ya no necesita demostrar que sabe diseñar arquitectura. Necesita demostrar que puede cerrar correctamente todos los lifecycle, concurrency, integration y distributed-system boundaries que esa arquitectura abrió.**

Ese es el siguiente salto de madurez.

---

# 67. Resumen de hallazgos

| ID            | Tipo                 | Sev.       | Confianza  | Hallazgo                                                        |
| ------------- | -------------------- | ---------- | ---------- | --------------------------------------------------------------- |
| TF-LIFE-01    | Resource leak        | Alta       | Confirmado | `TranslationService` no se cierra en reload                     |
| TF-LIFE-02    | Resource leak        | Alta       | Confirmado | `RateLimiter`/purger no se cierra                               |
| TF-LIFE-03    | Resource leak        | Media/Alta | Confirmado | executor de MetricsEndpoint no se conserva/cierra               |
| TF-CONC-01    | Defecto              | Alta       | Confirmado | `sleepScheduler` sigue bloqueando workers con `future.get()`    |
| TF-CONC-02    | Defecto              | Alta       | Confirmado | Fabric puede ejecutar dispatch/esperas en server thread         |
| TF-SYNC-01    | Defecto arquitectura | Alta       | Confirmado | SyncBus no es todavía autoridad central                         |
| TF-SYNC-02    | Bug protocolo        | Alta       | Confirmado | MessageCodec pierde semántica de Direction/metadata             |
| TF-IFLOW-01   | Bug                  | Alta       | Confirmado | SpEL action no recibe superficie mutable                        |
| TF-TEXT-01    | Bug                  | Media/Alta | Confirmado | Template SpEL evaluator no está cableado                        |
| TF-FABRIC-01  | Feature incompleta   | Media      | Confirmado | Fabric InWorld contiene stubs                                   |
| TF-INWORLD-01 | Feature incompleta   | Media      | Confirmado | Spigot InWorld contiene stubs                                   |
| TF-OBS-01     | Bug                  | Media      | Confirmado | `debugPort` no controla realmente DebugEndpoint                 |
| TF-OBS-02     | Defecto              | Media      | Confirmado | Debug endpoint default queda sin token                          |
| TF-HTTP-01    | Bug                  | Media      | Confirmado | redirect 307/308 pierde body/headers                            |
| TF-HTTP-02    | Riesgo               | Media      | Probable   | SSRF DNS validation no elimina TOCTOU                           |
| TF-BUILD-01   | Riesgo de build      | Alta       | Probable   | Spigot shadowJar excluye dependencias requeridas                |
| TF-CI-01      | Deuda                | Media      | Confirmado | CI actual no construye `fabric-host`                            |
| TF-DOC-01     | Deuda                | Baja/Media | Confirmado | README/PLAN mantienen estados históricos de Fabric              |
| TF-TEST-01    | Defecto testing      | Media      | Confirmado | benchmark multi-recipient no representa realmente 50 recipients |
| TF-TEST-02    | Deuda                | Media      | Confirmado | cobertura baja en host/iflow                                    |

---

# 68. Conclusión

**TextFormatter Suite tiene una arquitectura genuinamente buena.**

Su mayor mérito no es la cantidad de features, sino que muchas de esas features están construidas sobre un core común:

```text
Message
  ↓
Router
  ↓
Formatter
  ↓
Translation
  ↓
Adapter
```

Eso proporciona una base que puede sobrevivir a varios años de evolución.

Sus mayores problemas actuales no requieren destruir esa arquitectura.

Requieren terminarla.

En particular:

```text
lifecycle
concurrency
SyncBus
protocol
Fabric
E2E
artifact validation
```

Una vez resueltos esos puntos, el proyecto estaría en una posición técnica muy distinta: no solamente "bien arquitecturado", sino también capaz de demostrar operacionalmente las propiedades que actualmente promete.

**Madurez técnica estimada actual: ~68%.**

**Madurez arquitectónica: ~83–86%.**

**Potencial: alto.**

**Necesidad de reescritura total: ninguna.**

**Necesidad de consolidación profunda: sí.**
