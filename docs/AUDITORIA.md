# Auditoría técnica integral — TextFormatter Suite

**Fecha de auditoría:** 2026-10-05 04:00 UTC
**Repositorio:** `majhrs16-official/TextFormatter-Suite`
**Commit auditado:** `00fc8463658f54bc8aa6c9a1de2b8f0bfe6f4682`
**Rama:** `main`

> **Alcance y precisión:** hice una auditoría profunda sobre el repositorio GitHub, incluyendo arquitectura, módulos, documentación, configuración, build, historial y una inspección directa de los componentes críticos y de varios tests. No voy a afirmar que cada línea de cada archivo fue inspeccionada individualmente: el conector disponible no permite enumerar/leer el árbol completo de forma eficiente. Por tanto, esto es una **auditoría técnica amplia y basada en evidencia**, pero no una revisión byte-a-byte de absolutamente todo el repositorio. Los hallazgos de código que marco como *Confirmado* sí están sustentados directamente por el código inspeccionado.

---

# 1. Cobertura de auditoría

## Inspeccionado directamente

### Arquitectura/core

* `core-api`
* `kernel`
* `host`
* `textformatter`
* `iflow`
* `transport`
* `messages`
* `gtranslate`
* `ltranslate`

### Sincronización

* `sync-bus`
* `sync-http`
* `sync-tcpudp` mediante documentación y arquitectura
* `sync-discord` mediante documentación/historial
* `sync-telegram` mediante historial/documentación
* `sync-websocket`
* `sync-velocity`

### Plataforma

* `spigot-host`
* `fabric-host`
* wiring descrito por commits y documentación
* pipeline común `SuiteBootstrap` / `SuiteHost` / `MessageDispatcher`

### Extensiones / runtime

* `manager-api`
* `manager-impl`
* `extension-api`
* `example-extension`
* `presets`
* `inworld`
* `observability`
* `performance`
* `loadtest`
* `tester`

### Build/documentación

* `settings.gradle`
* `build.gradle`
* `README.md`
* `docs/PLAN.md`
* `docs/ADR.md`
* varios `src/*/README.md`
* GitHub Actions / pipeline descrito por historial
* dependency locking / verification metadata según documentación e historial

### Tests

Se inspeccionaron tests E2E del pipeline y tests del formatter, además de las cifras declaradas por el proyecto:

* 167+ tests Java reportados.
* 99 tests/integraciones del editor.
* tests específicos de SpEL, traducción, HTTP repository, GTranslate, LTranslate, E2E, concurrencia, etc.

### Historial

Se revisaron commits relevantes desde la evolución inicial hasta el commit auditado, incluyendo:

* separación del monolito
* introducción de `core-api`
* Clean Architecture / Translator SPI
* Security Sprint
* Module Manager
* SyncBus
* Fabric rewrite
* fixes de concurrencia
* SSRF
* configuración single-source
* CI/CD
* coverage gates
* Telegram/TCP/UDP
* estado final de Fabric.

---

# 2. Resumen ejecutivo

## Veredicto corto

**TextFormatter Suite es un proyecto técnicamente ambicioso y arquitectónicamente bastante superior a lo que normalmente se ve en plugins de Minecraft de este tamaño.**

No es simplemente un plugin con muchas features.

Hay una arquitectura modular real:

```text
                 ┌───────────────────────┐
                 │   Platform Adapter    │
                 │ Spigot / Fabric       │
                 └───────────┬───────────┘
                             │
                             ▼
                     ┌──────────────┐
                     │     host     │
                     │ composition  │
                     └──────┬───────┘
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
          ┌───────┐     ┌────────┐    ┌────────────┐
          │ iFlow │     │Format  │    │Translation │
          └───┬───┘     └───┬────┘    └─────┬──────┘
              │             │               │
              └─────────────┼───────────────┘
                            ▼
                       Message model
                            │
                            ▼
                        SyncBus
                  ┌──────┬──┼──┬──────┐
                  ▼      ▼  ▼  ▼      ▼
                HTTP   TCP UDP WS   Discord
                                      │
                                   Telegram
```

La arquitectura **sí tiene valor real**, no solamente nomenclatura de Clean/Hexagonal.

Pero hay una diferencia importante:

> **La arquitectura está bastante madura; la implementación operacional todavía no está al mismo nivel de madurez.**

Hay varios problemas que aparecen precisamente cuando se intenta llevar este diseño a carga alta:

* semántica incorrecta de `SyncBus`
* lifecycle incompleto de sinks
* afirmaciones de `EXACTLY_ONCE` que no están demostradas
* traducción bajo saturación
* un bug de futures que puede dejar entradas permanentes en `inFlightTranslations`
* pinning DNS de `HttpTransport` implementado de una forma problemática para HTTPS/virtual hosting
* configuración `engine.parallel` que se lee pero no controla realmente el dispatcher
* `MessageEvent` existe pero el bus público que se documenta no está materializado como tal
* documentación de módulos todavía inconsistente con el estado real.

Mi evaluación global actual sería:

# **8.0/10**

Y eso es una buena nota.

No le doy 9+ porque todavía hay una diferencia significativa entre **"arquitectura preparada para network-scale"** y **"runtime demostrado como network-scale"**.

---

# 3. Arquitectura pretendida vs arquitectura real

## Pretendida

El README presenta:

```text
platform adapters
       ↓
core-api
       ↓
kernel / host / textformatter / iflow / translators / sync
```

con:

* Hexagonal Architecture
* Clean Architecture
* SPI
* ServiceLoader
* composición en `host`
* adapters de plataforma separados.

## Real

La arquitectura real es más interesante.

`core-api` realmente es el contrato:

```text
core-api
 ├── domain model
 ├── Module SPI
 ├── Translator SPI
 ├── SyncSink SPI
 ├── ActorDirectory
 ├── PlaceholderResolver
 ├── ExpressionEvaluator
 └── message model
```

Los adapters de plataforma implementan los ports.

`host` es el verdadero **composition/integration layer**.

Y `SuiteBootstrap` actualmente hace algo deliberadamente distinto de un típico mod-loader:

```java
List<Module> modules = ModuleLoader.discover();
ModuleGraph graph = new ModuleGraph(env);
ResolutionResult result = graph.resolve(modules);
```

pero **no instancia los módulos para arrancarlos**.

Eso coincide con la corrección arquitectónica importante del proyecto:

> `Module` es descriptor/SPI para resolver el grafo; los servicios reales se activan desde los entry points de plataforma.

Eso está correctamente implementado en `SuiteBootstrap`.

---

# 4. Clean Architecture

## Evaluación: **8.5/10**

La parte más importante está bien.

La corrección de Translator SPI es particularmente buena:

Antes:

```text
host
 ├── gtranslate
 └── ltranslate
```

Ahora:

```text
             ┌─────────────┐
             │   host      │
             └──────┬──────┘
                    │
                    ▼
             ┌─────────────┐
             │  core-api   │
             │ Translator  │
             │ Provider    │
             └──────┬──────┘
                    ▲
             ┌──────┴──────┐
             │             │
       gtranslate      ltranslate
```

`ServiceLoader` rompe correctamente la dependencia compile-time.

Eso es Clean Architecture real.

### Lo que está especialmente bien

* `core-api` no conoce Bukkit/Fabric.
* `host` no necesita conocer las implementaciones concretas de traducción.
* `transport` es infraestructura.
* platform hosts son adapters.
* composición está fuera del dominio.
* translator providers pueden evolucionar independientemente.

### Defecto

La separación todavía no es perfecta porque `host` es un **integration hub bastante grande**.

Es correcto arquitectónicamente, pero tiende a convertirse en:

```text
host
 ├── config
 ├── bootstrap
 ├── dispatch
 ├── translation wiring
 ├── sync wiring
 ├── lifecycle
 ├── schema
 ├── command integration
 └── runtime state
```

No es todavía un God module catastrófico, pero es el principal candidato a convertirse en uno.

---

# 5. Hexagonal Architecture

## Evaluación: **8.8/10**

Aquí el proyecto está especialmente fuerte.

Hay ports claros:

```text
ActorDirectory
PlaceholderResolver
PermissionChecker
TranslationService
TranslatorProvider
SyncSink
ChatDelivery
ExpressionEvaluator
```

y adapters:

```text
SpigotActorDirectory
FabricActorDirectory

SpigotChatDelivery
FabricChatDelivery

GTranslate
LTranslate

HttpSink
TcpSink
UdpSink
WebSocketSyncSink
VelocitySink
DiscordSink
TelegramSink
```

La dependencia conceptual es correcta:

```text
         OUTSIDE
             │
        adapters
             │
             ▼
        ports / SPI
             │
             ▼
          core
```

### Principal problema

Algunos módulos todavía mezclan dos conceptos:

1. `Module` como descriptor.
2. módulo como unidad de runtime/lifecycle.

El código actual ha corregido esto, pero algunos READMEs siguen describiendo:

```text
Module.initialize()
Module.shutdown()
```

como si fuera el contrato vigente.

Eso es deuda documental, no una violación de runtime.

---

# 6. Flujo completo

El pipeline real es aproximadamente:

```text
Minecraft event
      │
      ▼
Platform adapter
      │
      ▼
ActorDirectory
      │
      ▼
Message
      │
      ▼
resolveSourceLanguage()
      │
      ▼
emission rate-limit
      │
      ▼
Direction expansion
      │
      ├── INITIATOR
      ├── OTHERS
      ├── ALL
      ├── WORLD
      ├── RADIUS
      ├── PERMISSION
      └── SPECIFIC
      │
      ▼
bounded executor
      │
      ▼
iFlow
      │
      ├── conditions
      ├── permissions
      ├── rate-limit
      ├── transforms
      ├── redirects
      └── cancellation
      │
      ▼
TemplateRenderer
      │
      ├── built-ins
      ├── expressions
      ├── placeholders
      ├── content
      ├── <tr>
      └── MiniMessage
      │
      ▼
TranslationService
      │
      ├── cache
      ├── in-flight dedup
      └── provider
      │
      ▼
Component
      │
      ├── local ChatDelivery
      │
      └── SyncBus
              │
              ├── Discord
              ├── Telegram
              ├── HTTP
              ├── TCP
              ├── UDP
              ├── WebSocket
              └── Velocity
```

Esto es una arquitectura bastante seria.

---

# 7. Modelo de mensajes

Uno de los mejores diseños del proyecto.

`Message` es esencialmente immutable y las transformaciones utilizan:

```text
withX()
Builder.from(message)
```

en lugar de mutación indiscriminada.

También es acertada la decisión de separar:

```text
Message
 ├── sender
 ├── direction
 ├── channel
 ├── language
 ├── text
 ├── format
 └── metadata
```

de la antigua semántica implícita:

```text
from → to
```

La representación:

```text
INITIATOR
OTHERS
ALL
WORLD
RADIUS
PERMISSION
SPECIFIC
```

es mucho más expresiva.

---

# 8. `Direction`

Está bien diseñado.

Especialmente:

```java
public static Direction specific(Channel channel, Actor... recipients)
```

hace que el conjunto de destinatarios sea explícito e inmutable mediante copia.

La expansión:

```java
return resolved.stream().distinct().toList();
```

evita duplicaciones.

### Riesgo

La política:

> qualifier vacío → fail-open

merece revisión.

En `MessageDispatcher`:

```java
PERMISSION + qualifier vacío
    → todos los jugadores
```

Eso puede ser correcto como filosofía de compatibilidad, pero para permisos de seguridad es una decisión peligrosa.

No lo marcaría como vulnerabilidad sin más contexto porque puede ser intencional.

**Tipo:** defecto de diseño potencial
**Confianza:** probable.

---

# 9. Formatter

## Evaluación: **8.5/10**

`TemplateRenderer` está bastante bien estructurado:

```text
replaceBuiltins()
evaluateExpressions()
resolveExternalTokens()
replaceContent()
translateSpans()
MiniMessage.deserialize()
```

Esto es mucho mejor que un enorme método de interpolación.

Además se corrigieron dos bugs interesantes:

### Unicode `İ`

Se reemplazó el enfoque:

```java
source.toLowerCase()
```

por:

```java
Pattern.CASE_INSENSITIVE
```

para localizar `</tr>`.

Correcto.

### Traducción → MiniMessage injection

Ahora:

```java
String translated = translation.translate(...);
return MiniEscape.escape(translated);
```

Eso elimina una clase real de inyección de MiniMessage.

---

# 10. SpEL

## Evaluación: **8/10**

La sandbox de templates utiliza:

```java
SimpleEvaluationContext.forReadOnlyDataBinding()
```

y la sandbox de reglas usa allowlist explícita.

Esto es mucho mejor que:

```java
new StandardEvaluationContext()
```

sin restricciones.

La allowlist:

```text
me.majhrs16.suite.api.message.*
me.majhrs16.suite.textformatter.channel.*
me.majhrs16.suite.iflow.*
```

con denylist de:

```text
java.*
javax.*
sun.*
com.sun.*
org.springframework.*
...
```

es una defensa razonable.

### Pero

No consideraría SpEL una frontera de seguridad absoluta simplemente porque exista esta allowlist.

Es una superficie compleja y debería mantenerse con:

* tests de escape
* fuzzing
* tests contra nuevas versiones de Spring
* tests de propiedades/metodos heredados.

Los tests existentes son una fortaleza.

---

# 11. iFlow

## Evaluación: **8.4/10**

Es probablemente la feature conceptualmente más ambiciosa.

Tiene:

* reglas
* condiciones
* transforms
* redirects
* channel redirects
* rate limiting
* permisos
* loops
* guards
* fan-out
* mux
* SpEL
* graph representation.

La idea:

```text
input
  ↓
condition
  ↓
transform
  ↓
output
```

como grafo configurable es potente.

Especialmente interesante:

```text
loop
max-steps
```

para evitar ciclos infinitos.

---

# 12. RateLimiter

La implementación actual es razonable:

```java
ConcurrentHashMap<String, Bucket>
synchronized(bucket)
```

y purga periódica.

La eliminación del:

```text
ConcurrentHashMap
+
ReentrantReadWriteLock
```

redundante fue una mejora correcta.

### Defecto menor

La capacidad del bucket se fija cuando se crea:

```java
new Bucket(capacity)
```

Si la configuración del canal cambia mientras el bucket existe, ese bucket conserva la capacidad anterior.

Si el reload reconstruye completamente el router/limiter, no hay problema.

Si se reutiliza la instancia durante reload, sí.

**Tipo:** Defecto de diseño
**Severidad:** Baja/Media
**Confianza:** Probable.

---

# 13. Traducción

## Evaluación: **7.8/10**

La arquitectura es buena:

```text
TranslatorManager
       │
       ├── Google
       └── LibreTranslate
```

y existe:

* cache
* detection cache
* in-flight dedup
* executor dedicado
* timeout
* cancellation.

Pero aquí aparece uno de los problemas técnicos más importantes actuales.

---

# 14. [TXF-001] In-flight translation puede quedar bloqueada permanentemente

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

`core-api -> TranslationService.translate()`

y:

`core-api -> TranslationExecutor.submit()`

### Problema

La entrada se añade:

```java
inFlightTranslations.computeIfAbsent(cacheKey, k ->
    executor.submit(...)
)
```

y se elimina desde dentro de la tarea:

```java
finally {
    inFlightTranslations.remove(cacheKey);
}
```

Pero `TranslationExecutor` puede cancelar el `Future` **antes de que la tarea llegue a ejecutarse**:

```java
executorFuture.cancel(true);
future.cancel(true);
```

Si la tarea estaba esperando en la cola y es cancelada antes de comenzar:

```text
computeIfAbsent()
      ↓
inFlightTranslations[key] = future
      ↓
executor queue
      ↓
timeout
      ↓
executorFuture.cancel(true)
      ↓
TASK NEVER RUNS
      ↓
finally NUNCA EJECUTA
      ↓
inFlightTranslations[key] queda para siempre
```

Después:

```java
inFlightTranslations.get(key)
```

devuelve el future cancelado.

Por tanto, esa traducción puede quedar permanentemente inutilizada durante toda la vida del `TranslationService`.

### Impacto

Bajo saturación del executor:

* la cola puede llenarse
* requests pueden expirar
* keys pueden quedar "en vuelo" permanentemente
* futuras traducciones de ese mismo texto pueden fallar inmediatamente.

### Solución mínima

Eliminar el entry asociado al future también desde el timeout/cancellation, usando identidad:

```java
inFlightTranslations.remove(cacheKey, future);
```

La misma consideración aplica a:

```text
inFlightDetections
```

---

# 15. [TXF-002] Rejection del TranslationExecutor puede escapar

**Tipo:** Bug
**Severidad:** Media/Alta
**Confianza:** Confirmado

Cuando el executor está lleno:

```java
new ThreadPoolExecutor.AbortPolicy()
```

hace que:

```java
executor.submit(...)
```

lance `RejectedExecutionException`.

`computeIfAbsent()` puede propagarla.

`TranslationService.translate()` no captura esa excepción alrededor del `computeIfAbsent`.

Por tanto:

```text
translation saturation
        ↓
RejectedExecutionException
        ↓
TranslationService.translate()
        ↓
propaga
        ↓
MessageDispatcher
        ↓
recipient silenced
```

Esto significa que bajo carga fuerte el sistema no degrada simplemente a:

```text
mensaje original
```

sino que puede perder la entrega de ese receptor.

Eso puede ser aceptable como backpressure, pero debe ser una política deliberada y documentada.

Para un sistema de chat, yo preferiría:

```text
executor saturated
      ↓
translation unavailable
      ↓
fallback policy
      ├── source text
      └── configured fallback translator
```

en vez de convertir saturación temporal en pérdida de mensaje.

---

# 16. HttpTransport

Aquí hay un problema más serio.

## [TXF-003] DNS pinning mediante sustitución del hostname

**Tipo:** Defecto de diseño / Bug funcional potencial
**Severidad:** Alta
**Confianza:** Confirmado en implementación; impacto HTTPS altamente probable.

El código hace:

```java
InetAddress pinnedAddress = validateUrlAndGetAddress(url);

connectionUrl =
    urlString.replaceFirst(
        url.getHost(),
        pinnedAddress.getHostAddress()
    );
```

y posteriormente:

```java
new URL(connectionUrl)
```

Esto cambia:

```text
https://translate.googleapis.com/...
```

a algo conceptualmente equivalente a:

```text
https://142.x.x.x/...
```

### Problema 1 — TLS/SNI

HTTPS normalmente necesita el hostname original para:

* SNI
* hostname verification
* certificado TLS.

Al conectar al IP, un certificado válido para:

```text
translate.googleapis.com
```

puede no ser válido para:

```text
142.x.x.x
```

### Problema 2 — HTTP virtual hosting

Incluso sin TLS:

```text
Host: example.com
```

puede convertirse en:

```text
Host: 1.2.3.4
```

y el servidor puede entregar otro virtual host.

### Problema 3 — IPv6

`getHostAddress()` puede devolver:

```text
fd12:3456::1
```

y sustituirlo directamente dentro de:

```text
https://hostname/path
```

requiere manejo de corchetes:

```text
https://[fd12:3456::1]/path
```

La implementación no lo hace explícitamente.

### Conclusión

La intención del fix SSRF es correcta.

La implementación de pinning **no es la forma adecuada de preservar simultáneamente SSRF protection + HTTPS hostname semantics**.

Debe conectarse al IP validado manteniendo:

```text
URL hostname
Host header
TLS SNI
hostname verification
```

sin volver a resolver DNS.

---

# 17. [TXF-004] `engine.parallel` no controla realmente el dispatcher

**Tipo:** Bug / defecto funcional
**Severidad:** Media
**Confianza:** Confirmado

`ConfigLoader` lee:

```java
boolean parallel = ...
```

y `HostConfig` lo conserva.

Pero `MessageDispatcher` siempre crea:

```java
ThreadPoolExecutor(
    4,
    32,
    ...
)
```

y siempre procesa:

```java
CompletableFuture.supplyAsync(...)
```

No existe una rama real:

```text
parallel = false
    → sequential
```

Por tanto:

```yaml
iflow:
  engine:
    parallel: false
```

no tiene el efecto semántico que su nombre indica.

Esto es particularmente importante porque es una configuración de rendimiento/concurrencia.

---

# 18. MessageDispatcher

La implementación actual es bastante buena conceptualmente:

```text
bounded executor
CallerRunsPolicy
future timeout
future.cancel(true)
sleep scheduler
```

pero tiene una propiedad importante:

### `CallerRunsPolicy`

Con suficiente presión:

```text
executor queue full
       ↓
CallerRunsPolicy
       ↓
caller ejecuta host.deliver()
```

Si `dispatch()` se invoca accidentalmente desde el thread principal de Minecraft, el trabajo pesado puede volver al main thread.

No es un bug inmediato porque los adapters actuales intentan escoger correctamente el contexto.

Pero es una **protección de último recurso peligrosa para una plataforma como Minecraft**.

Yo preferiría que el executor de dispatch tuviera una política explícita:

```text
queue full
   ↓
drop / defer / bounded wait
```

en vez de ejecutar potencialmente:

```text
SpEL + translation + formatting
```

en el caller.

---

# 19. Sleep transform

El cambio a scheduler fue correcto:

```java
sleepScheduler.schedule(...)
```

No bloquea el worker.

Pero hay una consecuencia semántica:

```java
delivered++;
```

ocurre cuando **se programa** la entrega, no cuando la entrega sucede.

Por tanto:

```text
DispatchReport.delivered = 1
```

puede significar:

```text
"scheduled"
```

y no:

```text
"actually delivered"
```

Si el callback falla posteriormente, el `DispatchReport` ya fue devuelto.

Esto es importante para:

* métricas
* SyncBus
* Discord bridge
* observabilidad.

**Severidad:** Baja/Media.

---

# 20. SyncBus

## Evaluación conceptual: **8/10**

La idea es muy buena:

```text
             SyncBus
          /     |      \
       queue   bulkhead  dedup
        /        |         \
     HTTP       Discord    WS
      │
 retry/backoff
```

Tiene:

* queue global
* queues por sink
* aislamiento
* retry
* dedup
* métricas
* lifecycle.

Pero aquí aparecen problemas reales.

---

# 21. [TXF-005] `SyncBus.unregister()` no detiene el sink

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado

`DefaultSyncBus.unregister()` hace:

```java
SinkContext removed = sinks.remove(name);

if (removed != null) {
    removed.shutdown();
}
```

Pero `SinkContext.shutdown()` detiene su executor:

```java
executor.shutdown();
```

y **no llama**:

```java
sink.stop();
```

Por tanto:

```text
SyncBus.unregister("websocket")
       ↓
sink worker stopped
       ↓
WebSocketSyncSink.stop() NO ejecutado
       ↓
socket/server puede seguir vivo
```

Lo mismo puede afectar:

* HTTP server
* WebSocket server
* Discord connection
* Telegram polling
* otros recursos externos.

Esto es un lifecycle bug real.

### Solución

```java
removed.shutdown();

try {
    removed.sink.stop();
} catch (Exception e) {
    ...
}
```

idealmente con orden cuidadosamente definido.

---

# 22. [TXF-006] SyncBus start parcial deja estado inconsistente

**Tipo:** Bug
**Severidad:** Media/Alta
**Confianza:** Confirmado

`start()` hace:

```java
started.set(1);

for (SinkContext ctx : sinks.values()) {
    ctx.sink.start();
    ctx.start();
}
```

Si el sink #3 falla:

```text
sink 1 → started
sink 2 → started
sink 3 → exception
```

pero:

```java
started == 1
```

sigue siendo cierto.

Una llamada posterior a `start()` interpreta:

```text
already started
```

aunque algunos sinks no estén iniciados.

Además, los sinks anteriores quedan activos.

Esto requiere rollback transaccional:

```text
start sink 1
start sink 2
start sink 3 → fail

rollback:
    stop sink 2
    stop sink 1
```

---

# 23. [TXF-007] `broadcast()` reporta éxito antes de la entrega

**Tipo:** Defecto de diseño
**Severidad:** Media
**Confianza:** Confirmado

La API dice:

> number of sinks that successfully accepted the message

pero:

```java
return sinks.size();
```

se ejecuta antes de que cada `SinkContext` procese realmente el mensaje.

Puede ocurrir:

```text
broadcast()
    ↓
10 sinks
    ↓
return 10
    ↓
sink queue full
    ↓
drop
```

Entonces:

```text
broadcast() == 10
actual deliveries == 9, 8, ...
```

Esto vuelve ambiguas las métricas y cualquier lógica que interprete `broadcast()` como ACK.

---

# 24. [TXF-008] `EXACTLY_ONCE` de Velocity no está demostrado

**Tipo:** Defecto de diseño
**Severidad:** Alta para escalabilidad/distributed semantics
**Confianza:** Confirmado

`VelocitySink` declara:

```java
DeliverySemantics.EXACTLY_ONCE
```

pero la infraestructura mostrada no proporciona un mecanismo suficiente para demostrar exactly-once:

* no hay ACK de aplicación claramente ligado al message ID
* no hay commit/ack transaction
* hay retry
* hay queue
* hay red potencialmente duplicada.

En sistemas distribuidos:

```text
retry
```

sin protocolo de deduplicación remoto significa como máximo:

```text
at-least-once
```

salvo que el receptor haga dedup por ID.

Por tanto, el contrato:

```text
EXACTLY_ONCE
```

debería rebajarse a:

```text
AT_LEAST_ONCE
```

hasta que exista un protocolo explícito:

```text
message UUID
   ↓
receiver dedup store
   ↓
ACK(message UUID)
   ↓
sender commits delivery
```

---

# 25. WebSocket

La situación actual es mucho mejor que la encontrada en auditorías anteriores.

Ahora:

```java
if (authToken == null || authToken.isBlank()) {
    conn.close(...)
}
```

y:

```text
127.0.0.1
```

es el bind predeterminado.

Además:

* límite 64 KB
* rate limit
* subscriptions
* cleanup
* SO_REUSEADDR.

Eso es sólido.

La implementación actual del rate limit también corrigió el problema de ventana deslizante incorrecta:

```text
windowStart
messageCount
```

es una ventana fija.

---

# 26. Metrics endpoint

La corrección a:

```java
127.0.0.1
```

es correcta.

Además tiene:

```text
bounded executor
scheduler shutdown
```

y `/health`.

Sin embargo `/health` devuelve esencialmente:

```json
{
  "status": "UP"
}
```

sin reflejar necesariamente el estado real de:

* sinks
* queues
* translation provider
* module manager.

El proyecto afirma health checks más completos en otras capas, por lo que esto es principalmente una cuestión de semantics.

---

# 27. Seguridad

## Evaluación: **8.4/10**

La mejora respecto al estado histórico es enorme.

Se corrigieron:

* SpEL sandbox
* YAML unsafe construction
* MiniMessage injection
* SSRF
* debug endpoint
* tokens
* HMAC
* replay protection
* payload limits
* dependency verification
* SHA256 artifacts
* lockfiles.

### Fortalezas

Especialmente buenos:

```text
SafeConstructor
MiniEscape
SpEL allowlist
bounded executors
HTTP body limits
HMAC
replay timestamps/nonces
127.0.0.1 binding
dependency verification
```

---

# 28. Supply chain

El proyecto ha avanzado mucho:

```text
gradle.lockfile
verification-metadata.xml
SHA256
manifest validation
module allowlist
```

Esto es bastante mejor que el típico plugin.

Pero hay una diferencia:

### SHA256 ≠ autenticidad

Un checksum obtenido del mismo canal que distribuye el JAR no demuestra quién creó el JAR.

El propio PLAN lo reconoce.

Para releases realmente sensibles:

```text
artifact
  ↓
signature
  ↓
trusted public key
```

sería mejor que depender solamente de:

```text
artifact.sha256
```

No lo considero crítico para el estado actual.

---

# 29. Module Manager

## Evaluación: **7.8/10**

Conceptualmente es ambicioso.

Tiene:

* version resolver
* semver
* dependency resolver
* manifest
* SHA256
* repository abstraction
* local repositories
* HTTP repositories
* GitHub repositories
* isolated classloader
* parent-last
* discoverAll
* SPI-only registration.

La corrección:

> `register()` no instancia `Module`

fue especialmente importante.

### Principal limitación

El proyecto todavía no tiene una demostración completa del flujo:

```text
GitHub Release real
      ↓
download
      ↓
verify
      ↓
resolve
      ↓
load
      ↓
activate
      ↓
rollback/update
```

Por tanto:

**núcleo arquitectónico:** fuerte
**producción distribuida demostrada:** todavía no.

---

# 30. Configuración

La idea de `ConfigPath` como fuente única es excelente.

Antes:

```text
Java schema
JS paths
JS model
paths.json
```

podían divergir.

Ahora:

```text
ConfigPath
     ↓
generator
     ├── paths.json
     ├── paths.js
     └── model.js
```

Esto reduce drift.

### Pero

`ConfigLoader` hace degradaciones como:

```java
invalid claim-mode
    → CANCEL_EVENT
```

y:

```java
invalid channel type
    → CHAT
```

Eso puede ocultar errores administrativos.

El proyecto quiere ser tolerante, lo cual está bien, pero sería mejor distinguir:

```text
missing value
    → default

invalid value
    → default + explicit validation error
```

No bloquear necesariamente, pero sí informar de forma estructurada.

---

# 31. Personalización

## Evaluación: **9/10**

Esta es una de las mejores áreas.

La arquitectura permite combinaciones poco convencionales:

* canales arbitrarios
* direcciones
* reglas
* transforms
* formatos
* idiomas
* permisos
* sonidos
* sync
* repositories
* presets
* extensiones.

La filosofía de:

> no rechazar algo simplemente porque sea extraño

está bastante bien reflejada.

No veo evidencia de una tendencia fuerte a prohibir configuraciones simplemente por estética.

---

# 32. Features

| Feature                 | Calidad |
| ----------------------- | ------: |
| Message model           |    9/10 |
| Direction/routing       |    9/10 |
| MiniMessage formatter   |  8.5/10 |
| iFlow                   |  8.5/10 |
| Translation abstraction |  8.5/10 |
| Google Translate        |    8/10 |
| LibreTranslate          |    8/10 |
| SyncBus                 |  7.5/10 |
| HTTP sync               |    8/10 |
| WebSocket               |    8/10 |
| Velocity                |  7.5/10 |
| Discord                 |    8/10 |
| Telegram                |    8/10 |
| Config system           |  8.5/10 |
| Web editor              |  8.5/10 |
| Observability           |    8/10 |
| Module manager          |  7.5/10 |
| Extension API           |    8/10 |
| In-world                |  7.5/10 |
| Fabric adapter          |  7.5/10 |
| Spigot adapter          |    8/10 |

---

# 33. Concurrencia

## Evaluación: **7.5/10**

Hay mucho trabajo bueno:

```text
bounded executors
CallerRunsPolicy
timeouts
future cancellation
dedicated translation executor
sleep scheduler
per-sink queues
bulkheads
retry
ConcurrentHashMap
Atomic*
```

Pero precisamente porque el proyecto ya intenta ser altamente concurrente, aparecen bugs más sutiles.

Los principales:

1. translation future leak
2. executor saturation semantics
3. CallerRunsPolicy
4. SyncBus lifecycle
5. report semantics de sleep
6. exactly-once no demostrado.

---

# 34. Rendimiento

No voy a inventar benchmarks.

## Complejidad conceptual

Para un mensaje:

```text
O(R)
```

donde `R` es número de receptores.

Cada receptor puede tener:

```text
routing
+
formatting
+
translation
```

La traducción tiene caching/dedup, por lo que el coste real se aproxima más a:

```text
O(R × formatting)
+
O(U × translation)
```

donde `U` es el número de combinaciones únicas:

```text
texto + source + target
```

Esto es una decisión correcta.

---

# 35. Hotspot principal

Para una red grande:

```text
1000 jugadores
    ↓
1 mensaje
    ↓
1000 recipient futures
    ↓
1000 routing evaluations
    ↓
1000 format operations
```

Incluso si:

```text
translation = 1 request
```

el formatter continúa siendo:

```text
O(R)
```

Esto es inevitable si cada jugador puede tener:

* idioma distinto
* permisos distintos
* placeholders distintos
* formato distinto
* dirección distinta.

La arquitectura no tiene un problema conceptual aquí.

Pero significa que **network-scale requiere optimización adicional**, no simplemente más threads.

---

# 36. Escalabilidad

## Escenario A — servidor pequeño

### ~10–50 jugadores

**Excelente.**

No veo un problema arquitectónico significativo.

---

## Escenario B — red mediana

### ~100–500 jugadores distribuidos

**Viable**, especialmente si:

* traducción está cacheada
* sinks externos no bloquean el pipeline
* configuración razonable.

SyncBus empieza a convertirse en un componente importante.

---

## Escenario C — red grande

### ~500–2000 jugadores / múltiples servidores

**Viable arquitectónicamente, pero requiere tuning y benchmarks.**

Los principales cuellos:

```text
recipient fan-out
translation
SyncBus queues
external HTTP
GC
SpEL
```

---

# 37. Escenario D — network-scale

Para una red comparable conceptualmente a Hypixel/UniversoCraft:

## Estado actual

**No demostrado.**

Y sería incorrecto decir:

> "sí, soporta Hypixel".

No existe evidencia de benchmark de esa magnitud.

### El diseño podría evolucionar hacia ello.

Pero necesitaría:

```text
Minecraft server
       │
       ▼
local TextFormatter
       │
       ▼
regional / network message bus
       │
       ▼
dedicated sync infrastructure
       │
       ├── Redis/Kafka/NATS/etc.
       └── network routing
```

En otras palabras:

**TextFormatter puede ser el procesamiento local del mensaje; no debería convertirse necesariamente en el backbone global de una network gigantesca.**

---

# 38. Cuello de botella network-scale

El mayor problema no sería MiniMessage.

Sería:

```text
translation + recipient-specific processing
```

y después:

```text
external synchronization
```

especialmente:

```text
HTTP
Telegram
Discord
WebSocket
```

si son ejecutados en el mismo proceso.

---

# 39. Qué necesitaría para network-level

P0/P1 arquitectónico futuro:

```text
1. benchmark reproducible de 1k/5k/10k recipients
2. benchmark translation-disabled
3. benchmark translation-enabled
4. benchmark multi-language
5. benchmark SyncBus
6. failure injection
7. queue saturation tests
8. p99 latency
9. GC profiling
10. CPU flamegraphs
```

Y probablemente:

```text
per-message recipient batching
translation batching
precompiled templates
compiled rule graphs
async sync transport
network-level message broker
```

---

# 40. Testing

## Evaluación: **7.5/10**

La cantidad de tests es buena.

Particularmente positivos:

* E2E
* SpEL security
* translation cache
* GTranslate
* LTranslate
* HTTP repositories
* concurrency
* stress
* editor integration.

Los coverage gates actuales reportados son aproximadamente:

```text
host           ~29%
iflow          ~29%
textformatter  ~62%
```

Eso revela algo importante:

> El proyecto tiene bastante testing, pero no tiene cobertura uniforme.

No es necesariamente malo; los módulos de infraestructura y adapters suelen tener menor cobertura.

Pero para afirmar madurez network-level faltan:

* failure injection
* real network tests
* lifecycle tests
* distributed delivery tests
* saturation tests
* real Fabric E2E
* real Velocity E2E.

---

# 41. Documentación

## Evaluación: **6.5/10**

Aquí hay una diferencia notable entre código y documentación.

Por ejemplo, `src/fabric-host/README.md` todavía dice:

> **EXCLUDED FROM BUILD — 42 compilation errors**

mientras que el commit actual y `PLAN.md` indican:

```text
fabric-host = COMPILA
```

Lo mismo ocurre con `core-api`, `host`, `spigot-host` y otros READMEs que todavía contienen estados históricos.

Esto es importante porque un desarrollador nuevo puede llegar a conclusiones completamente erróneas.

### El root README está mucho más actualizado.

Por tanto:

```text
root documentation     ≈ 8/10
module documentation   ≈ 5/10
PLAN                   ≈ 7/10
overall                ≈ 6.5/10
```

---

# 42. [TXF-009] Documentación contradictoria

**Tipo:** Deuda técnica
**Severidad:** Media
**Confianza:** Confirmado

Ejemplo:

```text
src/fabric-host/README.md
```

describe Fabric como excluido y con 42 errores.

Mientras:

```text
settings.gradle
PLAN.md
commits actuales
```

indican Fabric compilando.

También existen múltiples secciones históricas dentro de `PLAN.md` que continúan mostrando:

```text
P0 pendiente
P1 pendiente
fabric pendiente
```

aunque posteriormente aparecen como resueltas.

### Impacto

No rompe runtime, pero sí:

* onboarding
* mantenimiento
* auditorías futuras
* interpretación de arquitectura.

---

# 43. Historial de Git

La evolución es interesante.

El proyecto pasó aproximadamente por:

```text
monolito ChatTranslator
        ↓
separación modular
        ↓
core-api
        ↓
hexagonal
        ↓
iFlow
        ↓
web editor
        ↓
security audits
        ↓
module manager
        ↓
SyncBus
        ↓
Clean Translator SPI
        ↓
Fabric rewrite
        ↓
current consolidation
```

Esto es una evolución saludable en términos generales.

No parece que la arquitectura haya sido diseñada una vez y abandonada.

Más bien:

> **la arquitectura ha sido sometida a varias rondas de auditoría y posteriormente modificada.**

Eso es una señal positiva.

---

# 44. Pero existe una característica del historial

Hay una enorme cantidad de commits de tipo:

```text
audit fix
docs sync
fix audit finding
audit follow-up
architecture correction
```

Eso demuestra rigor, pero también indica que el proyecto ha tenido una fase de **reestructuración extremadamente intensa**.

Para los próximos años convendría cambiar progresivamente de:

```text
architecture correction
```

a:

```text
stability
benchmark
compatibility
release
```

---

# 45. Defectos adicionales relevantes

## [TXF-010]

**Tipo:** Defecto de diseño
**Severidad:** Media
**Confianza:** Confirmado

`MessageEvent` existe:

```java
MessageEvent
```

pero el bus público descrito en la arquitectura no aparece materializado como una infraestructura equivalente a:

```text
MessageEventBus
```

El propio root README todavía describe esa parte como incompleta.

Por tanto:

```text
MessageEvent API
```

existe,

pero:

```text
public extensibility event pipeline
```

no está al mismo nivel de madurez que el resto.

---

## [TXF-011]

**Tipo:** Deuda técnica
**Severidad:** Baja
**Confianza:** Confirmado

`RuleExpressionEvaluator` y `SpelExpressionEvaluator` tienen implementaciones propias de:

```text
LRU cache
```

basadas en:

```text
LinkedHashMap + synchronized
```

Funcionan, pero la API `ConcurrentMap` implementada manualmente es bastante pesada.

Es una abstracción de infraestructura que podría simplificarse.

No lo tocaría ahora salvo que profiling demuestre impacto.

---

# 46. Lo que está excepcionalmente bien diseñado

### 1. `core-api`

Probablemente la mejor decisión estructural.

### 2. `Message`

La inmutabilidad y `withX()` están bien pensadas.

### 3. `Direction`

Muy expresivo.

### 4. Translator SPI

La eliminación de dependencia compile-time entre host y proveedores es una mejora arquitectónica real.

### 5. Separación platform/core

La dirección:

```text
Spigot/Fabric
      ↓
ports
      ↓
core
```

está bien conseguida.

### 6. Seguridad

La cantidad de problemas históricos realmente corregidos es significativa.

### 7. Config schema single-source

Buena decisión para evitar drift Java/JS.

### 8. SyncBus

La idea de bulkhead por sink es correcta aunque la implementación todavía tenga defectos lifecycle/semantics.

### 9. iFlow

Tiene un potencial enorme y está diseñado como sistema general de routing, no como colección de `if`s.

### 10. Auditoría iterativa

El historial demuestra que los hallazgos realmente provocaron modificaciones estructurales.

---

# 47. Lo mediocre

Principalmente:

* documentación de módulos
* coverage desigual
* lifecycle del SyncBus
* semantics de delivery
* falta de benchmarks reales
* Module Manager todavía no probado como sistema completo de releases reales
* Fabric todavía mucho menos probado que Spigot.

---

# 48. Peores defectos actuales

Ordenados:

### 1.

**HttpTransport DNS pinning implementado sustituyendo hostname por IP.**

### 2.

**TranslationExecutor puede dejar futures permanentemente registrados después de timeout pre-ejecución.**

### 3.

**SyncBus unregister no llama `sink.stop()`.**

### 4.

**EXACTLY_ONCE no está realmente garantizado para Velocity.**

### 5.

**`engine.parallel` no tiene efecto real.**

### 6.

**Broadcast accounting de SyncBus no representa delivery real.**

### 7.

**Documentación interna está desincronizada.**

---

# 49. Qué NO tocaría

Esto es importante.

No recomendaría una reescritura.

No tocaría innecesariamente:

```text
core-api
Message
Direction
TranslatorProvider SPI
ActorDirectory SPI
ChatDelivery SPI
SyncSink SPI
ModuleDescriptor
ConfigPath
MiniEscape
ModuleGraph
```

Tampoco intentaría "simplificar" la arquitectura eliminando módulos.

El proyecto ya tiene suficientes boundaries reales como para que una reducción artificial probablemente lo empeore.

---

# 50. Roadmap

## P0 — Crítico

### P0-1 — Corregir HttpTransport pinning

**Beneficio:** enorme
**Esfuerzo:** medio
**Riesgo:** medio

Preservar:

```text
hostname
Host
SNI
certificate validation
```

mientras la conexión se realiza al IP validado.

---

### P0-2 — Corregir in-flight translation cancellation

**Beneficio:** alto
**Esfuerzo:** pequeño
**Riesgo:** bajo

Usar:

```java
inFlightTranslations.remove(key, future);
```

también en cancellation/timeout.

---

## P1 — Alto

### P1-1 — Lifecycle transaccional de SyncBus

```text
start failure
   ↓
rollback previous sinks
```

y:

```text
unregister
   ↓
sink.stop()
```

---

### P1-2 — Revisar delivery semantics

No declarar:

```text
EXACTLY_ONCE
```

sin ACK/dedup remoto demostrable.

---

### P1-3 — Saturación del TranslationExecutor

Definir explícitamente:

```text
queue full
```

¿Debe:

* fallback?
* reject?
* preserve original?
* drop?
* block?

Para chat yo elegiría:

```text
preserve original text
```

cuando sea seguro.

---

### P1-4 — Hacer real `engine.parallel`

Si:

```yaml
parallel: false
```

debe existir realmente:

```text
sequential dispatcher
```

---

## P2 — Medio

* mejorar `DispatchReport` para distinguir `scheduled` vs `delivered`
* revisar bucket capacity durante reload
* eliminar duplicación de caches LRU
* completar MessageEventBus
* lifecycle tests
* SyncBus saturation tests
* HTTP redirect/TLS tests
* Fabric E2E
* Velocity E2E.

---

## P3 — Bajo

* sincronización documental automática
* consolidar README históricos
* firma criptográfica de releases
* mejorar health endpoint
* reducir imports/código muerto
* simplificar algunas abstracciones.

---

# 51. Matriz final

| Área                         | Puntuación | Estado       | Principales problemas                        |
| ---------------------------- | ---------: | ------------ | -------------------------------------------- |
| Código                       |    **8.0** | Bueno        | algunos bugs concurrentes                    |
| Arquitectura                 |    **8.8** | Muy buena    | `host` concentra bastante wiring             |
| Clean Architecture           |    **8.5** | Muy buena    | algunos boundaries todavía evolucionan       |
| Hexagonal                    |    **8.8** | Muy buena    | principalmente sólida                        |
| Modularidad                  |    **8.7** | Muy buena    | gran cantidad de módulos                     |
| Separación responsabilidades |    **8.2** | Buena        | host puede crecer demasiado                  |
| Legibilidad                  |    **8.0** | Buena        | algunas implementaciones demasiado complejas |
| Features                     |    **8.8** | Excelente    | algunas aún no completamente endurecidas     |
| Personalización              |    **9.0** | Excelente    | muy flexible                                 |
| Seguridad                    |    **8.4** | Muy buena    | HttpTransport merece revisión                |
| Rendimiento                  |    **7.5** | Bueno        | falta evidencia a gran escala                |
| Escalabilidad                |    **7.2** | Prometedora  | no demostrada network-scale                  |
| Concurrencia                 |    **7.5** | Buena        | futures/queues/lifecycle                     |
| Testing                      |    **7.8** | Bueno        | cobertura desigual y pocos E2E reales        |
| Mantenibilidad               |    **8.0** | Buena        | documentación y `host`                       |
| Documentación                |    **6.5** | Regular/Good | varios READMEs obsoletos                     |

## Global

# **8.0 / 10**

---

# 52. Potencial

## Potencial teórico

### **9.5/10**

El modelo puede convertirse en una plataforma de chat bastante general:

```text
Minecraft
Discord
Telegram
HTTP
WebSocket
Velocity
custom extensions
custom translators
custom routers
```

sin tener que destruir el core.

---

## Potencial arquitectónico

### **9/10**

La arquitectura permite realmente una evolución importante.

No es una falsa modularidad.

---

## Potencial alcanzado

Mi estimación:

# **~70–75%**

No porque falten features.

De hecho, tiene muchísimas.

La diferencia está en:

```text
feature completeness
        ≠
operational maturity
```

Ya hay bastante feature completeness.

Falta más:

* endurecimiento
* benchmarks
* lifecycle
* distributed semantics
* observabilidad
* compatibilidad
* pruebas reales de carga.

---

# 53. Mantenibilidad a 1/3/5 años

## 1 año

**Buena**, si se corrigen los problemas actuales.

El mayor riesgo será `host` + SyncBus.

## 3 años

Puede seguir siendo muy mantenible si:

```text
core-api
host
sync
platform
```

mantienen boundaries estables.

## 5 años

El riesgo principal será:

```text
compatibilidad Minecraft
+
API evolution
+
module manager
+
configuration schema
```

más que el core de routing.

---

# 54. Onboarding

## 30 minutos

Un desarrollador puede entender:

```text
core-api
host
MessageDispatcher
textformatter
iflow
```

si empieza por los entry points correctos.

## 2 horas

Puede comprender el pipeline completo.

## 1 día

Puede probablemente modificar:

* formatter
* rule
* config
* adapter.

## 1 semana

Puede trabajar razonablemente en:

* nueva feature
* nuevo sync sink
* adapter
* translator provider.

La documentación contradictoria es el principal obstáculo.

---

# 55. Respuestas explícitas a las 14 preguntas finales

### 1. ¿Qué tan bueno es realmente?

**Bueno. 8/10 aproximadamente.**

Y la puntuación es más alta de lo que normalmente daría a un proyecto de este tipo.

---

### 2. ¿Qué partes están excepcionalmente bien diseñadas?

Principalmente:

```text
core-api
Message
Direction
SPI
platform adapters
TranslatorProvider
iFlow
config schema
```

---

### 3. ¿Qué partes son mediocres?

```text
documentación
lifecycle de SyncBus
delivery semantics
runtime module management
evidencia de performance
```

---

### 4. ¿Peores defectos?

Los actuales más importantes:

```text
HttpTransport pinning
TranslationExecutor leak
SyncBus lifecycle
EXACTLY_ONCE falso/no demostrado
parallel knob ignorado
```

---

### 5. Mayor fortaleza arquitectónica

# **La separación real entre core, ports y platform adapters.**

Especialmente después del Translator SPI.

---

### 6. Mayor riesgo futuro

# **Complejidad operacional.**

No creo que el proyecto vaya a morir por falta de features.

El riesgo es que:

```text
host
+
SyncBus
+
ModuleManager
+
platform adapters
+
translation
```

se vuelvan demasiado complejos de operar simultáneamente.

---

### 7. ¿Qué tan mantenible?

**8/10.**

Muy buena base, con deuda documental.

---

### 8. ¿Extensible?

# **9/10.**

Es una de sus mayores fortalezas.

---

### 9. ¿Preparado para múltiples plataformas?

# **8.5/10 arquitectónicamente.**

Spigot está más maduro; Fabric ya no es el agujero arquitectónico que era históricamente, pero necesita más evidencia operacional.

---

### 10. ¿Preparado para una gran network?

# **7/10 actualmente.**

La arquitectura sí.

La implementación todavía necesita endurecimiento y benchmarks.

---

### 11. ¿Qué debe cambiar para network-level?

Principalmente:

```text
translation backpressure
SyncBus semantics
distributed dedup
real ACK semantics
benchmarking
recipient batching
failure isolation
network-level broker
```

---

### 12. ¿Qué NO debería tocarse?

Especialmente:

```text
core-api
Message
Direction
Translator SPI
ports
platform boundaries
ConfigPath
```

No necesitan una "gran refactorización".

---

### 13. ¿Qué debería hacerse primero?

En este orden:

```text
1. HttpTransport
2. TranslationExecutor timeout leak
3. SyncBus lifecycle
4. delivery semantics
5. engine.parallel
6. saturation tests
7. Fabric/Velocity E2E
8. network-scale benchmarks
9. documentación
```

---

### 14. ¿Qué porcentaje está técnicamente maduro?

Mi estimación:

# **~70–75%**

El código central está más maduro que el porcentaje global.

La parte que falta no es tanto:

> "hacer más cosas"

sino:

> **demostrar y endurecer las cosas que ya existen bajo condiciones hostiles y carga alta.**

---

# 56. Conclusión final

La conclusión más importante de esta auditoría es que **TextFormatter Suite ya dejó de ser simplemente una reimplementación grande de ChatTranslator**.

El proyecto tiene una arquitectura propia con:

```text
immutable message model
        +
SPI
        +
ports/adapters
        +
rule engine
        +
translation abstraction
        +
cross-server bus
        +
runtime module manager
        +
configuration schema
        +
web editor
        +
multi-platform adapters
```

Eso es una plataforma.

Y la arquitectura no es humo: varias decisiones que podrían haber sido simplemente "nombres bonitos" realmente están respaldadas por dependencias y código.

El punto donde todavía está por debajo de su propia arquitectura es el **endurecimiento operacional**.

En particular, los problemas de `SyncBus`, traducción concurrente y `HttpTransport` son interesantes precisamente porque no son fallos de principiante como un `NullPointerException`: aparecen cuando el sistema empieza a comportarse como un sistema distribuido real.

Mi evaluación sería:

```text
Arquitectura conceptual       9/10
Arquitectura implementada   8.5/10
Feature set                 8.8/10
Seguridad                   8.4/10
Código                      8.0/10
Testing                     7.8/10
Operación                   7.3/10
Escalabilidad demostrada    6.5/10
──────────────────────────────────
Global                      ~8.0/10
```

Y hay una distinción importante:

> **No veo ninguna razón arquitectónica para reescribir TextFormatter Suite.**

Los problemas encontrados son, en su mayoría, **localizados y solucionables sin destruir la arquitectura existente**.

De hecho, el siguiente salto de calidad del proyecto no debería ser otra gran refactorización. Debería ser pasar de:

```text
"la arquitectura puede soportarlo"
```

a:

```text
"tenemos pruebas reproducibles que demuestran exactamente cuánto puede soportar,
cómo falla y cómo se recupera."
```

Ese sería el paso que convertiría el proyecto de **arquitectónicamente muy ambicioso y bastante maduro** a **infraestructura realmente production-grade a escala network**.
