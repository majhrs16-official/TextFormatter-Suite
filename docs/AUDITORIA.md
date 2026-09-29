# AUDITORÍA TÉCNICA INTEGRAL — TEXTFORMATTER SUITE

**Fecha de auditoría:** 2026-09-26, aproximadamente 21:28 UTC
**Commit auditado:** `9b96ce62798ab473546e9533a6074ebb8f003fd8`
**Repositorio:** `majhrs16-official/TextFormatter-Suite`
**Versión declarada:** `2.1.0-SNAPSHOT`

---

# 1. Cobertura de auditoría

El snapshot auditado contiene aproximadamente:

* **518 archivos**
* **205 archivos Java**
* **68 archivos de tests**
* múltiples módulos Gradle
* aplicación web/editor
* documentación y wiki
* CI/CD
* metadatos de verificación de dependencias
* sistema de módulos
* sistema de extensiones
* dos adapters de plataforma
* varios transports/sinks

Se examinó:

### Estructura

* `core-api`
* `kernel`
* `textformatter`
* `iflow`
* `host`
* `transport`
* `gtranslate`
* `ltranslate`
* `sync-http`
* `sync-tcpudp`
* `sync-discord`
* `sync-telegram`
* `sync-websocket`
* `sync-velocity`
* `observability`
* `extension-api`
* `manager-api`
* `manager-impl`
* `presets`
* `inworld`
* `performance`
* `loadtest`
* `tester`
* `messages`
* `coretranslator`
* `common-legacy`
* `spigot-host`
* `fabric-host`
* `web-editor`
* `example-extension`

### Código principal revisado directamente

Entre otros:

* `SuiteBootstrap`
* `SuiteHost`
* `MessageDispatcher`
* `Module`
* `ModuleDescriptor`
* `ModuleLoader`
* `ModuleGraph`
* `DefaultRouter`
* `RateLimiter`
* `DefaultTextFormatter`
* `TemplateRenderer`
* `SpelExpressionEvaluator`
* `Channel`
* `ChannelRegistry`
* `ScriptSurface`
* `TransformOp`
* `TranslationService`
* `TranslatorManager`
* `ConfigLoader`
* `ConfigValidator`
* `ConfigSchemaGenerator`
* `HttpTransport`
* `HttpSink`
* `TcpSink`
* `UdpSink`
* `WebSocketSyncSink`
* `JdaDiscordSink`
* `VelocitySink`
* `ExtensionManager`
* `DefaultModuleLifecycle`
* `TextFormatterSuitePlugin`
* `SpigotChatDelivery`
* `FabricChatDelivery`

### También se revisó

* `settings.gradle`
* múltiples `build.gradle`
* `gradle.lockfile`
* `verification-metadata.xml`
* workflows CI/CD
* workflow de release
* `PLAN.md`
* `src/README.md`
* documentación de módulos
* CodeGuide recién añadido
* documentación de arquitectura
* tests unitarios/integración
* benchmarks JMH
* Gatling load tests
* historial de commits
* cambios del refactor principal

### Evidencia experimental

No se inventaron benchmarks.

Sí se verificó el estado real de GitHub Actions del propio commit auditado:

**Workflow run:** `36272574778`

Resultado:

* `build-and-test`: **FAIL**
* `javadoc`: **FAIL**
* `web-editor`: **FAIL**

Por tanto, el estado actual del repositorio **no es CI-green**.

---

# 2. Resumen ejecutivo

## Veredicto técnico

**TextFormatter Suite es un proyecto arquitectónicamente ambicioso y considerablemente mejor estructurado que un plugin Minecraft convencional, pero todavía no está técnicamente maduro como producto network-scale.**

Su mayor virtud no es la cantidad de features, sino que existe un **core bastante bien separado de los adapters de plataforma**, con contratos explícitos, mensajes inmutables, ports para delivery, módulo kernel, routing independiente y un renderer relativamente limpio.

Su principal problema tampoco es una clase concreta.

Es la **distancia entre la arquitectura diseñada y la arquitectura efectivamente integrada**.

Hay varias piezas que individualmente están bien diseñadas pero que todavía no forman un sistema coherente:

```text
Arquitectura declarada
        │
        ├── Module system
        ├── Extensions
        ├── iFlow
        ├── Sync modules
        ├── Web editor
        └── Platform adapters
                 │
                 ▼
        integración parcial
                 │
                 ▼
       comportamiento real actual
```

El resultado es un proyecto con **buenos componentes, pero integración incompleta**.

Los problemas más importantes encontrados son:

1. **WebSocket sync se inicia aunque `enabled: false` y sin token por defecto.**
2. Esto deja un endpoint de sincronización potencialmente accesible sin autenticación y capaz de inyectar mensajes.
3. **Reload de Spigot deja recursos/listeners vivos.**
4. **Rate limiting se consume por receptor, no por mensaje**, por lo que un broadcast puede agotar el presupuesto simplemente por tener muchos jugadores.
5. `rules.yml` no está integrado en el bootstrap actual.
6. El `ExpressionEvaluator` no está conectado al `DefaultRouter` en el camino normal.
7. Algunas transformaciones (`sleep`, sonidos) se almacenan pero no llegan a ejecutarse completamente.
8. `ExtensionManager` no puede cargar correctamente extensiones desde JARs externos con su implementación actual.
9. El chequeo de compatibilidad de extensions compara la versión de la extensión con su Core API requerida.
10. La caché de traducción usa `hashCode()` como identificador de contenido, permitiendo colisiones funcionales.
11. `ChannelRegistry` se presenta como extensible, pero internamente se convierte en `unmodifiableMap`, haciendo `register()`/`unregister()` inválidos.
12. El build actual tiene dependencias `SNAPSHOT` externas que hacen fallar CI.
13. `fabric-host` ni siquiera está incluido en `settings.gradle`, por lo que no forma parte del build raíz.
14. El web editor actual tiene errores de sintaxis JavaScript detectados por CI.

Eso es bastante importante:

> **El problema actual de TextFormatter Suite no es falta de arquitectura. Es falta de cierre de integración y estabilización.**

---

# 3. Arquitectura pretendida vs arquitectura real

## Arquitectura pretendida

El proyecto describe algo cercano a:

```text
                 ┌─────────────────────┐
                 │ Platform Adapter    │
                 │ Spigot / Fabric     │
                 └──────────┬──────────┘
                            │
                            ▼
                 ┌─────────────────────┐
                 │ SuiteBootstrap      │
                 └──────────┬──────────┘
                            │
                  ModuleLoader / Graph
                            │
          ┌─────────────────┼─────────────────┐
          ▼                 ▼                 ▼
      TextFormatter       iFlow            Sync
          │                 │                 │
          └─────────────────┼─────────────────┘
                            ▼
                        core-api
```

Eso es conceptualmente razonable.

## Arquitectura real

En la práctica:

```text
Spigot/Fabric
     │
     ▼
Platform entry point
     │
     ├── ConfigLoader
     ├── TranslationService
     ├── SuiteBootstrap
     │       │
     │       ├── ModuleLoader
     │       ├── ModuleGraph
     │       │
     │       └── valida módulos
     │
     ├── DefaultRouter
     ├── TextFormatters.create(...)
     ├── MessageDispatcher
     ├── WebSocketSyncSink
     ├── DiscordBridge
     ├── Observability
     ├── ExtensionManager
     └── ModuleLifecycle
```

La diferencia crítica es:

**`ModuleGraph` no inicializa los módulos.**

`Module` es correctamente tratado como descriptor:

```java
public interface Module {
    ModuleDescriptor descriptor();
}
```

Eso es una buena decisión.

Los servicios reales se construyen manualmente:

```java
new DefaultRouter(...)
TextFormatters.create(...)
new MessageDispatcher(...)
```

Por tanto, el sistema actual es más exactamente:

> **modular monolith + ports/adapters + descriptor-based module graph**

que un sistema completamente gestionado por el kernel.

Eso no es malo.

De hecho, considero correcto que `Module` no sea instanciado como servicio runtime. El problema es que parte de la documentación todavía describe un lifecycle que ya no corresponde con el código.

---

# 4. Flujo real completo

El pipeline real de un chat Spigot es aproximadamente:

```text
AsyncPlayerChatEvent
        │
        ▼
TextFormatterSuitePlugin.onChat()
        │
        ├── claim event
        ├── determina channel
        ├── construye Message
        │
        ▼
MessageDispatcher.dispatch()
        │
        ├── resolveSourceLanguage()
        │
        ├── expand(Direction)
        │       │
        │       └── lista de jugadores
        │
        ├── bounded executor
        │
        ▼
SuiteHost.deliver(message, recipient)
        │
        ├── effectiveLanguage(recipient)
        │
        ▼
DefaultRouter.route()
        │
        ├── send permission
        ├── receive permission
        ├── rules
        ├── rate limit
        └── RouteOutcome
        │
        ▼
TemplateRenderer
        │
        ├── built-ins
        ├── expressions
        ├── placeholders
        ├── content
        ├── <tr>
        │
        ▼
TranslationService
        │
        ├── cache
        └── provider externo
        │
        ▼
MiniMessage
        │
        ▼
Component
        │
        ▼
SpigotChatDelivery
        │
        └── scheduler/main thread
```

Este flujo está bien conceptualmente.

Una corrección importante respecto a la documentación:

**el código real hace routing antes de formatting.**

`SuiteHost.deliver()`:

```text
Router.route()
      ↓
transformed Message
      ↓
formatter.format()
```

No:

```text
formatter
      ↓
router
```

como aparece en partes de la documentación.

---

# 5. Clean Architecture

## Evaluación: 5.5/10

Hay principios correctos, pero no consideraría que el proyecto implemente Clean Architecture de forma estricta.

### Lo que está bien

`core-api` funciona como contrato compartido.

`host` actúa como composition/integration layer.

Los adapters de plataforma están en los bordes:

```text
spigot-host
fabric-host
```

y utilizan interfaces como:

* `ActorDirectory`
* `ChatDelivery`
* `PlaceholderResolver`
* `PluginLogger`

Esto reduce acoplamiento con Bukkit/Fabric.

### Problemas

El `core-api` contiene bastante comportamiento concreto.

Ejemplo:

```text
TranslationService
TranslatorManager
```

no son únicamente ports.

Además:

```text
host
 ├── textformatter
 ├── iflow
 ├── gtranslate
 ├── ltranslate
 └── transport
```

hace que `host` conozca directamente muchos detalles concretos.

Esto es integración válida, pero no es una separación estricta de application/domain/infrastructure.

El proyecto tampoco posee un verdadero:

```text
domain
application
infrastructure
```

boundary.

Es más correcto verlo como:

```text
contracts
core services
integration host
platform adapters
infrastructure modules
```

Eso es perfectamente defendible, pero no debería venderse como Clean Architecture pura.

---

# 6. Hexagonal Architecture

## Evaluación: 7.0/10

Aquí el proyecto sale mejor parado.

Hay ports reales:

```text
ActorDirectory
ChatDelivery
PlaceholderResolver
Transport
SyncSink
SyncListener
```

y adapters reales:

```text
SpigotActorDirectory
SpigotChatDelivery
FabricActorDirectory
FabricChatDelivery
HttpTransport
JDA
Telegram
WebSocket
TCP
UDP
```

El core no necesita conocer Bukkit para representar un mensaje.

Eso es una frontera arquitectónica real.

## Principal debilidad

`host` se ha convertido en un integration hub muy grande.

Eso genera:

```text
host
 ├── configuration
 ├── bootstrap
 ├── routing
 ├── dispatch
 ├── translations
 └── infrastructure wiring
```

No es todavía un desastre, pero a largo plazo puede convertirse en un God Module.

---

# 7. Modularidad

## Evaluación: 7.5/10

La modularidad conceptual es una de las mejores partes del proyecto.

Existen fronteras bastante claras:

```text
core-api
kernel
textformatter
iflow
transport
sync-*
platform-host
```

También existe una idea correcta de capabilities:

```text
provides()
requires()
```

y `ModuleGraph` utiliza SCC/Tarjan para detectar ciclos.

Eso es bastante más sofisticado que el típico sistema de módulos de plugin.

## Problema

Algunos módulos son realmente módulos de código.

Otros son módulos de distribución.

Otros son módulos de infraestructura.

Otros son módulos experimentales.

Otros son legacy.

Por ejemplo:

```text
coretranslator
common-legacy
performance
loadtest
tester
manager-api
manager-impl
extension-api
presets
```

hacen que el árbol sea más difícil de conceptualizar.

A 5 años esto requiere una política clara:

> qué módulos son producto, cuáles son tooling y cuáles son transitional.

---

# 8. Features

## 8.1 TextFormatter

**Estado:** sólido.

Muy buen pipeline:

```text
template
 ↓
built-ins
 ↓
expressions
 ↓
placeholders
 ↓
content
 ↓
translation spans
 ↓
MiniMessage
```

La utilización de `MiniEscape` antes de insertar valores externos es una buena decisión de seguridad.

### Fortaleza

El renderer es razonablemente independiente de Bukkit/Fabric.

### Riesgo

Se renderiza individualmente por receptor.

A gran escala:

```text
1 mensaje
×
500 receptores
=
500 renders
```

Eso es correcto funcionalmente, pero costoso.

---

# 8.2 Channels

**Estado:** conceptualmente muy bueno.

La resolución jerárquica:

```text
private.staff.mod
        ↓
private.staff
        ↓
private
        ↓
chat
```

es una buena abstracción.

También está bien la combinación:

```text
permission
send-permission
receive-permission
```

Permite políticas bastante flexibles.

### Bug

`ChannelRegistry` se construye con:

```java
Collections.unmodifiableMap(copy)
```

pero expone:

```java
register()
unregister()
```

que intentan modificar ese mapa.

Por tanto, la API promete extensibilidad que la implementación no permite.

---

# 8.3 iFlow

**Potencial:** muy alto.

Tiene:

* reglas
* prioridades
* conditions
* transforms
* redirects
* channel redirects
* permissions
* rate limiting
* scripting

Es posiblemente la parte con mayor potencial arquitectónico.

Pero actualmente tiene problemas de integración.

---

# 8.4 Translation

La arquitectura de proveedores es buena:

```text
TranslationService
       ↓
TranslatorManager
       ↓
Translator
       ├── Google
       └── LibreTranslate
```

La introducción de `TranslatorProvider` mediante SPI en el refactor reciente también es una dirección correcta.

El problema principal es rendimiento y cache.

---

# 8.5 Sync

Existe una colección considerable de transports:

```text
HTTP
TCP
UDP
Discord
Telegram
WebSocket
Velocity
```

Pero no debe confundirse:

> **tener un módulo SyncSink no significa que el runtime actual lo esté utilizando.**

El bootstrap no construye automáticamente una infraestructura central de todos ellos.

El WebSocket sí se inicia directamente desde el platform host.

Los demás dependen de wiring específico.

Esto hace que el sistema de sincronización sea más parecido a una colección de adapters que a un bus de eventos cross-server completo.

---

# 8.6 Web editor

La idea es excelente.

Pero el estado actual no es release-ready.

GitHub Actions detectó errores de sintaxis en:

```text
src/web-editor/js/props.js
src/web-editor/js/sidebar.js
```

Por ejemplo:

```text
js/props.js: SyntaxError
Unexpected token, expected "," (414:3)
```

y:

```text
js/sidebar.js: SyntaxError
Unexpected token, expected "," (484:36)
```

Por tanto:

**el web editor actual no pasa su propio pipeline de CI.**

---

# 9. Personalización

## Evaluación: 8/10

Aquí TextFormatter destaca.

Hay mucha capacidad configurable:

* channels
* permissions
* languages
* formats
* tooltips
* sounds
* routing
* transformations
* translators
* sync
* repositories
* module allowlist
* extension system

La filosofía de no prohibir configuraciones simplemente porque sean extrañas es apropiada.

No encontré evidencia suficiente para afirmar que existan restricciones arbitrarias sistemáticas del tipo:

```text
port = -50
```

como problema arquitectónico.

El problema actual es otro:

> algunas configuraciones existen en el schema/editor pero no llegan al runtime.

---

# 10. Bugs, defectos y vulnerabilidades

## [TF-01]

**Tipo:** Vulnerabilidad
**Severidad:** Crítica
**Confianza:** Confirmado

**Ubicación:**

```text
spigot-host
 -> TextFormatterSuitePlugin.reloadSuite()
```

y:

```text
sync-websocket
 -> WebSocketSyncSink
```

### Descripción

El código lee:

```text
sync/websocket.yml
```

pero no comprueba el campo:

```yaml
enabled: false
```

y aun con el default:

```yaml
enabled: false
port: 9092
token: ""
```

ejecuta:

```java
WebSocketSyncSink wsSink = new WebSocketSyncSink(wsPort, wsToken, logger);
wsSink.start();
```

### Consecuencia

El servidor WebSocket se inicia aunque esté deshabilitado.

Además, cuando:

```text
token = ""
```

el propio WebSocket permite conexiones sin autenticación.

El código incluso registra:

```text
SECURITY: WebSocket server running without auth token!
```

### Impacto

El endpoint puede aceptar mensajes externos y pasarlos al:

```java
SyncListener.onMessage(...)
```

que en Spigot está conectado a:

```java
dispatcher.dispatch(message)
```

Por tanto existe una ruta potencial:

```text
Internet/LAN
   ↓
9092
   ↓
WebSocket
   ↓
MessageCodec
   ↓
SyncListener
   ↓
MessageDispatcher
   ↓
Minecraft chat
```

Eso es una superficie de **inyección remota de mensajes**.

### Solución

No iniciar el sink si:

```yaml
enabled: false
```

y, adicionalmente:

* bind localhost por defecto, o
* exigir token cuando el bind sea externo
* rechazar `enabled=true` + token vacío si escucha en `0.0.0.0`
* almacenar el sink en `Runtime`
* cerrarlo durante reload/shutdown.

---

## [TF-02]

**Tipo:** Defecto de diseño / Bug
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

```text
spigot-host
 -> TextFormatterSuitePlugin.reloadSuite()
```

### Problema

En cada reload se crean:

```text
MessageDispatcher
WebSocketSyncSink
Observability
ExtensionManager
InWorldHandler
DiscordBridge
```

pero no existe una estrategia completa de teardown para todos.

`MessageDispatcher` tiene:

```java
close()
```

pero `reloadSuite()` no lo llama sobre el runtime anterior.

### Consecuencias

Después de varios:

```text
/suite reload
```

pueden quedar:

* executors antiguos
* listeners antiguos
* WebSocket servers antiguos
* extensions antiguas
* handlers de InWorld antiguos

Además, `InWorldHandler` se registra nuevamente:

```java
registerEvents(inworldHandler, this)
```

sin una desregistración equivalente durante reload.

### Impacto

Un servidor administrado durante días podría acumular:

```text
listener_1
listener_2
listener_3
...
```

y trabajo duplicado.

### Solución

El runtime debe ser explícitamente `AutoCloseable`:

```java
interface Runtime extends AutoCloseable {
    @Override
    void close();
}
```

y:

```text
oldRuntime.close()
newRuntime.start()
atomic swap
```

No al revés.

---

## [TF-03]

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

```text
iflow
 -> DefaultRouter.route()
 -> RateLimiter.tryAcquire()
```

### Problema

El rate limiter dice:

```text
messages per second
```

y usa:

```text
channel + actorUuid
```

como key.

Pero `route()` se ejecuta **una vez por receptor**.

Por tanto:

```text
1 mensaje
100 receptores
rate-limit = 10
```

consume potencialmente:

```text
10 tickets
```

con los primeros 10 receptores.

Los otros 90 pueden quedar throttled.

### Resultado

El límite de mensajes se comporta accidentalmente como:

```text
deliveries per second
```

en vez de:

```text
messages emitted per second
```

### Solución

Aplicar el rate limit en el nivel de emisión:

```text
message
 ↓
rate-limit once
 ↓
fan-out
 ↓
recipients
```

o separar explícitamente:

```text
MessageRateLimiter
RecipientDeliveryLimiter
```

---

## [TF-04]

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

```text
host
 -> ConfigLoader
 -> SuiteBootstrap
 -> DefaultRouter
```

### Problema

Existe:

```text
rules.yml
```

y existe todo el modelo:

```text
Rule
TransformOp
ScriptSurface
ExpressionEvaluator
```

pero `ConfigLoader` no carga las reglas.

Además:

```java
new DefaultRouter(channels, permissions)
```

no recibe un `ExpressionEvaluator`.

Por tanto:

```java
expressionEvaluator == null
```

en el bootstrap normal.

### Consecuencia

Las capacidades documentadas de scripting/conditions/actions no forman parte del pipeline normal.

### Solución

Crear explícitamente:

```text
RuleConfigLoader
ExpressionEvaluator
DefaultRouter(channels, permissions, evaluator)
router.setRules(...)
```

durante composición.

---

## [TF-05]

**Tipo:** Bug
**Severidad:** Media-Alta
**Confianza:** Confirmado

**Ubicación:**

```text
iflow
 -> TransformOp.Sleep
 -> ScriptSurface.setSleepMillis()
```

### Problema

`Sleep` escribe:

```java
message.withSleepMillis(...)
```

pero no existe una etapa posterior del pipeline que consuma ese valor y espere antes de entregar.

Lo mismo sucede con los sonidos transformados.

El mensaje puede almacenar:

```text
sounds
sleepMillis
```

pero `MessageDispatcher` obtiene los sonidos del `Channel`, no ejecuta el estado transformado de la misma manera.

### Consecuencia

Parte del API de transforms es actualmente decorativa/no efectiva.

### Solución

Crear un `DeliveryPlan` explícito:

```text
RouteOutcome
   ↓
DeliveryPlan
   ├── message
   ├── delay
   ├── sounds
   └── target
```

y que Dispatcher consuma ese plan.

---

## [TF-06]

**Tipo:** Bug
**Severidad:** Media
**Confianza:** Confirmado

**Ubicación:**

```text
core-api
 -> TranslationService
```

### Problema

La caché utiliza:

```java
source + "|" + to.code() + "|" + text.hashCode()
```

como key.

`String.hashCode()` tiene colisiones conocidas.

Por ejemplo, existen strings distintos con el mismo hash.

### Impacto

Una colisión puede provocar:

```text
texto A
 ↓
traducción A
 ↓
cache

texto B
 ↓
mismo hash
 ↓
traducción A
```

### Solución mínima

Usar el texto completo:

```java
source + "|" + to.code() + "|" + text
```

o un digest de contenido.

No hace falta un sistema de hashing sofisticado para este caso.

---

## [TF-07]

**Tipo:** Bug
**Severidad:** Media
**Confianza:** Confirmado

**Ubicación:**

```text
textformatter
 -> ChannelRegistry
```

### Problema

El constructor crea:

```java
Collections.unmodifiableMap(copy)
```

pero posteriormente:

```java
register()
unregister()
```

intentan modificar `channels`.

Eso termina en:

```text
UnsupportedOperationException
```

### Consecuencia

El sistema de extensiones no puede realmente modificar el registry aunque la API lo promete.

### Solución

Opción A:

hacer el registry completamente inmutable y eliminar:

```text
register()
unregister()
```

Opción B:

usar un registry mutable/thread-safe.

Prefiero A para el core y crear un nuevo snapshot durante composición.

---

## [TF-08]

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

```text
extension-api
 -> ExtensionManager.enable()
```

### Problema

El código comprueba:

```java
desc.version().satisfies(desc.requiredCoreApi().toString())
```

pero:

```text
desc.version()
```

es la versión de la extensión.

Debería compararse:

```text
runningCoreApiVersion
```

contra:

```text
requiredCoreApi
```

### Consecuencia

La compatibilidad se evalúa contra el objeto equivocado.

### Solución

Inyectar explícitamente:

```java
SemVer coreApiVersion
```

al `ExtensionManager`.

---

## [TF-09]

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

```text
extension-api
 -> ExtensionManager.loadExtensionClass()
```

### Problema

El código dice:

```java
// For simplicity, use system classloader.
return Class.forName(className, true, getClass().getClassLoader());
```

Esto no carga realmente las clases desde el JAR que el manager descubrió en:

```text
extensions/
```

### Consecuencia

El sistema de extensions promete:

```text
drop JAR
→ discover
→ load
```

pero la clase principal no se carga mediante un classloader asociado al JAR.

### Además

El propio código reconoce:

```text
Production: use custom classloader
```

pero actualmente no lo hace.

### Solución

Un `URLClassLoader` por extensión:

```text
extension.jar
     ↓
ExtensionClassLoader
     ↓
Extension instance
```

y cerrar el classloader en `disable()`.

---

## [TF-10]

**Tipo:** Vulnerabilidad
**Severidad:** Alta
**Confianza:** Confirmado por código

**Ubicación:**

```text
sync-http
 -> HttpSink.handleInbound()
```

### Problema

El endpoint inbound:

```text
POST /hook
```

no tiene autenticación.

Sólo limita:

```text
1 MB
```

### Consecuencia

Si se expone en una interfaz accesible:

```text
attacker
 ↓
POST /hook
 ↓
MessageCodec
 ↓
SyncListener
```

puede introducir mensajes arbitrarios.

### Solución

Agregar:

* bearer/HMAC token
* bind localhost por defecto
* rate limit
* content-type obligatorio
* replay protection si se usa cross-server

---

## [TF-11]

**Tipo:** Defecto de integración
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

```text
settings.gradle
```

### Problema

`fabric-host` existe como módulo y tiene `build.gradle`, pero no está incluido como subproyecto del build raíz.

Además CI no lo compila.

### Consecuencia

La afirmación:

```text
TextFormatter Suite soporta Spigot + Fabric
```

no significa:

```text
CI valida Spigot + Fabric
```

Actualmente sólo existe cobertura real de build para Spigot.

### Solución

Agregar:

```gradle
include ':src:fabric-host'
```

y hacer que CI lo compile explícitamente.

---

## [TF-12]

**Tipo:** Defecto de build
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

```text
performance/build.gradle
example-extension/build.gradle
```

### Problema

Esos módulos dependen de:

```text
me.majhrs16:suite-*:2.1.0-SNAPSHOT
```

en lugar de dependencias Gradle `project(...)`.

En CI esos artifacts no existen en Maven Central.

El workflow falló exactamente con:

```text
Could not find me.majhrs16:suite-core-api:2.1.0-SNAPSHOT
Could not find me.majhrs16:suite-textformatter:2.1.0-SNAPSHOT
...
```

### Solución

Para módulos pertenecientes al mismo reactor:

```gradle
implementation project(':src:core-api')
```

Para módulos realmente externos:

```text
publish first
consume second
```

pero eso debe estar formalmente modelado.

La solución preferida dentro del monorepo es `project(...)`.

---

## [TF-13]

**Tipo:** Defecto de CI
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

```text
.github/workflows/ci.yml
```

### Estado actual

`javadoc` falla por dependency verification:

```text
junit-bom-5.7.2.module
junit-bom-5.9.1.module
spring-framework-bom-5.3.24.module
```

no presentes correctamente en:

```text
verification-metadata.xml
```

### Además

`build-and-test` falla por los SNAPSHOT externos descritos arriba.

### Consecuencia

El commit auditado no tiene un pipeline verde.

---

## [TF-14]

**Tipo:** Defecto
**Severidad:** Alta
**Confianza:** Confirmado

**Ubicación:**

```text
web-editor
 -> js/props.js
 -> js/sidebar.js
```

### Problema

CI detecta errores sintácticos JavaScript.

No es una cuestión estética.

Es código que no puede pasar el `format:check`.

### Solución

Corregir sintaxis primero.

Después ejecutar:

```text
npm run check
npm run test:integration
```

antes de considerar el editor estable.

---

# 11. Rendimiento

## Evaluación arquitectónica: 6.5/10

No existen benchmarks de producción suficientes para afirmar:

```text
X msg/s
```

por lo que no voy a inventarlos.

Sí pueden identificarse los costes estructurales.

## Coste por mensaje

Para un broadcast:

```text
O(P)
```

donde `P` es el número de receptores.

Cada receptor puede ejecutar:

```text
routing
+
permissions
+
rules
+
formatting
+
translation
+
MiniMessage parsing
```

Esto es inevitable hasta cierto punto, porque el idioma del receptor puede variar.

## Principal hotspot

Translation.

Si hay:

```text
200 receptores
10 idiomas
```

y mensajes nuevos, la caché no necesariamente evita las llamadas externas.

Además, las traducciones son síncronas.

Aunque existe un executor de:

```text
4–32 workers
```

eso no convierte un proveedor HTTP en una operación barata.

---

# 12. Concurrencia

## Aspectos buenos

`MessageDispatcher` utiliza:

```text
ThreadPoolExecutor
core = 4
max = 32
queue = 1000
CallerRunsPolicy
```

Esto es muchísimo mejor que:

```java
Executors.newCachedThreadPool()
```

para un servidor Minecraft.

También:

* `AtomicReference` para rules
* `ConcurrentHashMap`
* `CopyOnWriteArrayList`
* snapshots inmutables
* delivery main-thread en adapters

son decisiones razonables.

## Problema

`CallerRunsPolicy` significa que bajo saturación el hilo que llama al dispatcher puede ejecutar trabajo.

En un chat event esto puede convertirse en:

```text
queue full
 ↓
AsyncPlayerChatEvent thread
 ↓
ejecuta routing/render
 ↓
event tarda
```

No necesariamente rompe el servidor, pero elimina la garantía de aislamiento bajo saturación.

---

# 13. Escalabilidad

## Escenario A — servidor pequeño

```text
10–50 jugadores
```

La arquitectura es perfectamente razonable después de solucionar los bugs críticos.

Principal coste:

* traducción
* MiniMessage
* fan-out

No es preocupante.

---

## Escenario B — red mediana

```text
100–500 jugadores por servidor
```

Puede funcionar, pero empiezan a importar:

* 32 workers
* queue 1000
* rendering por receptor
* traducciones
* rate limiting
* sync

Aquí el bug del rate limiter se vuelve especialmente visible.

---

## Escenario C — red grande

```text
500–2000 jugadores por instancia
```

La arquitectura actual necesita optimización antes de considerarse segura para esta carga.

El problema no es principalmente CPU.

Es:

```text
fan-out
×
per-recipient work
×
external translation
```

---

## Escenario D — network-scale

```text
miles de jugadores
múltiples servidores
múltiples proxies
```

No consideraría la implementación actual preparada para esta escala.

No porque sea imposible.

Sino porque faltan varias garantías:

```text
centralized/distributed event transport
        +
backpressure
        +
message batching
        +
translation batching
        +
deduplication
        +
distributed rate limiting
        +
delivery semantics
        +
failure isolation
        +
bounded queues end-to-end
```

---

# 14. Arquitectura necesaria para network-level

El salto importante sería pasar de:

```text
Server
 └── MessageDispatcher
       └── SyncSink
```

a algo como:

```text
Minecraft Server
      │
      ▼
Local Chat Core
      │
      ▼
Message Bus
      │
      ├── Server A
      ├── Server B
      ├── Server C
      └── Server N
```

con:

```text
Message ID
Origin
Timestamp
TTL
Sequence
Idempotency key
Source server
Target scope
```

y garantías explícitas:

```text
at-most-once
at-least-once
deduplicated
ordered-per-channel
```

No hace falta implementar todo eso ahora.

Pero sí hace falta definir el modelo si el objetivo final es network-scale.

---

# 15. Seguridad

## Lo bien hecho

Hay varias medidas de seguridad bastante buenas:

### SpEL

`SimpleEvaluationContext.forReadOnlyDataBinding()` limita:

* `T(...)`
* constructors
* static methods
* reflection
* arbitrary method invocation

Esto está acompañado por tests de seguridad.

### YAML

Se usa:

```text
SafeConstructor
LoaderOptions
```

en el loader principal.

### HTTP

`HttpTransport`:

* desactiva redirects automáticos
* valida destinos
* resuelve DNS
* rechaza varias redes privadas
* limita redirects

### WebSocket

Existe:

* token
* límite de payload
* rate limit por conexión

El problema es que el WebSocket **se arranca sin token cuando el default está vacío**.

Por tanto, la implementación defensiva existe pero el wiring la invalida.

Ese patrón aparece varias veces en el proyecto:

> **el componente individual está endurecido, pero el composition root no siempre respeta ese modelo de seguridad.**

---

# 16. Mantenibilidad

## 1 año

Con correcciones P0/P1:

**buena.**

La modularidad actual permite añadir:

* translators
* transports
* channels
* transforms
* platform adapters

sin reescribir el core.

## 3 años

El mayor riesgo será:

```text
host
spigot-host
module manager
configuration
```

Si siguen acumulando wiring manual, se convertirán en el centro de gravedad del proyecto.

## 5 años

El principal riesgo sería que el sistema termine con:

```text
host
 ├── legacy
 ├── modules
 ├── manager
 ├── extensions
 ├── sync
 ├── config
 ├── platform hacks
 └── compatibility
```

todo conectado.

La solución no es una reescritura.

Es mantener estrictos boundaries desde ahora.

---

# 17. Legibilidad / onboarding

## 30 minutos

Un desarrollador competente puede entender:

```text
core-api
kernel
host
textformatter
iflow
```

y encontrar los entry points.

**Resultado:** bueno.

## 2 horas

Puede reconstruir:

```text
Plugin
 → SuiteHost
 → Router
 → Formatter
 → Delivery
```

y entender channels/translation.

**Resultado:** bastante bueno.

## 1 día

Puede modificar:

* formatting
* routing
* channel config
* translation

sin demasiados problemas.

## 1 semana

Puede trabajar en:

* sync
* module manager
* extensions
* platform adapters

pero necesitará leer bastante código.

**Resultado general: 7.5/10.**

La incorporación del `src/README.md` es una mejora correcta.

Pero el CodeGuide actual contiene algunas descripciones que ya no coinciden exactamente con la implementación. Por ejemplo, describe inicialización de módulos y un flujo formatter→router que el código actual no realiza.

---

# 18. Testing

La cantidad de tests es razonable.

Hay tests en:

```text
core-api
coretranslator
gtranslate
host
iflow
kernel
ltranslate
loadtest
sync-discord
sync-http
sync-tcpudp
sync-telegram
sync-velocity
textformatter
spigot-host
web-editor
```

Eso es positivo.

## Problema

Parte del testing es:

```text
mock-heavy
```

y varios tests de infraestructura real están deshabilitados.

Por ejemplo existen tests marcados como:

```java
@Disabled
```

para concurrencia/integración que requieren entorno real.

Eso no es necesariamente malo.

Pero significa:

> el número de archivos de test sobreestima la cobertura real de producción.

## Falta especialmente

Tests de:

* reload lifecycle
* multiple reloads
* WebSocket default security
* fanout + rate limiting
* rules.yml → router
* extension JAR loading
* extension unload
* cross-server duplicate delivery
* queue saturation
* translation provider latency
* 500+ recipients
* 2000+ recipients

---

# 19. Historial de Git

La evolución reciente es especialmente reveladora.

El gran refactor:

```text
44d3a309
2026-09-24
feat: major refactor and improvements
```

fue seguido rápidamente por:

```text
security sprint
manager M2
manager M3
manager M4
manager M5
translation cache
CI refactor
schema changes
rules graph editor
CodeGuide
```

Entre el refactor principal y el commit auditado hay **14 commits**.

Eso muestra que la arquitectura está:

> **evolucionando rápidamente, pero todavía no completamente estabilizada.**

La secuencia es bastante saludable conceptualmente:

```text
auditoría
 ↓
refactor
 ↓
security
 ↓
module manager
 ↓
cache
 ↓
documentation
```

El problema es que se añadieron features antes de cerrar completamente la integración.

---

# 20. Deuda técnica

## Deuda real

### D1 — Build heterogéneo

Algunos módulos:

```text
project(...)
```

otros:

```text
me.majhrs16:suite-*:SNAPSHOT
```

Esto fragmenta el reactor.

### D2 — Lifecycle distribuido

No existe un lifecycle central suficientemente fuerte.

### D3 — Configuración duplicada entre runtime/editor/schema

La existencia de:

```text
ConfigLoader
ConfigValidator
ConfigSchemaGenerator
paths.json
web editor
defaults
```

crea múltiples representaciones de la misma configuración.

### D4 — Módulos legacy

```text
coretranslator
common-legacy
```

deben tener una política explícita:

```text
maintain
deprecated
remove
```

### D5 — Sync architecture

Hay muchos adapters, pero falta una abstracción central de:

```text
cross-server event transport
```

---

# 21. Cosas que NO deberían tocarse

Hay varias decisiones que considero correctas y que no necesitan una reescritura.

## 1. `Module` como descriptor

Mantener:

```java
Module.descriptor()
```

y **no convertir Module en lifecycle service**.

La separación:

```text
descriptor
≠
runtime service
```

es correcta.

## 2. `Message` inmutable

Mantener el modelo immutable/functional:

```text
message.withX(...)
```

Es muy útil para concurrencia.

## 3. `ChatDelivery`

Es un buen port.

```text
core
 ↓
ChatDelivery
 ↓
Spigot/Fabric
```

Debe mantenerse.

## 4. `ActorDirectory`

También es un boundary correcto.

## 5. TemplateRenderer

No necesita una reescritura.

La secuencia actual es razonablemente clara.

## 6. MiniMessage escaping

Debe mantenerse como principio obligatorio.

## 7. ModuleGraph

La idea de resolver capabilities y detectar ciclos es válida.

No reemplazarlo por un simple mapa de dependencias sólo porque sea más sencillo.

---

# 22. Evaluación de soluciones

## P0 WebSocket

**Esfuerzo:** pequeño
**Riesgo:** bajo
**Beneficio:** enorme

Primero:

```text
if (!enabled) return;
```

Después:

```text
bind localhost by default
```

y:

```text
require token for non-local bind
```

Esto debe hacerse inmediatamente.

---

## P0 lifecycle

**Esfuerzo:** medio
**Riesgo:** medio
**Beneficio:** enorme

Crear:

```text
Runtime.close()
```

y cerrar todos los recursos.

No reescribir `SuiteHost`.

---

## P0 build

**Esfuerzo:** medio
**Riesgo:** bajo
**Beneficio:** enorme

Unificar dependencias del monorepo.

---

## P1 iFlow

**Esfuerzo:** medio
**Riesgo:** medio

Conectar:

```text
rules.yml
 ↓
RuleLoader
 ↓
ExpressionEvaluator
 ↓
DefaultRouter
```

No hace falta modificar el modelo de reglas.

---

## P1 rate limiting

**Esfuerzo:** medio
**Riesgo:** medio

Separar:

```text
message admission
```

de:

```text
recipient delivery
```

---

## P1 ExtensionManager

**Esfuerzo:** medio
**Riesgo:** medio

Introducir classloader real por extensión.

No cambiar la API de Extension.

---

# 23. Matriz final

| Área                         | Puntuación | Estado                   | Principales problemas                                          |
| ---------------------------- | ---------: | ------------------------ | -------------------------------------------------------------- |
| Código                       | **7.0/10** | Bueno                    | integración y algunos bugs funcionales                         |
| Arquitectura                 | **7.2/10** | Buena                    | boundaries todavía incompletos                                 |
| Clean Architecture           | **5.5/10** | Parcial                  | demasiada integración concreta en host                         |
| Hexagonal                    | **7.0/10** | Buena                    | ports reales, composition centralizado                         |
| Modularidad                  | **7.5/10** | Buena                    | módulos numerosos y algo heterogéneos                          |
| Separación responsabilidades | **7.0/10** | Buena                    | host empieza a crecer demasiado                                |
| Legibilidad                  | **7.8/10** | Buena                    | estructura bastante discoverable                               |
| Features                     | **6.5/10** | Amplias                  | varias aún incompletamente conectadas                          |
| Personalización              | **8.0/10** | Muy buena                | gran superficie configurable                                   |
| Seguridad                    | **5.0/10** | Insuficiente actualmente | WebSocket default vulnerable                                   |
| Rendimiento                  | **6.5/10** | Razonable                | fan-out + traducción síncrona                                  |
| Escalabilidad                | **5.0/10** | Limitada                 | no network-scale todavía                                       |
| Concurrencia                 | **6.5/10** | Razonable                | lifecycle/backpressure pendientes                              |
| Testing                      | **6.2/10** | Mixto                    | muchos tests, cobertura real incompleta                        |
| Mantenibilidad               | **6.8/10** | Buena con riesgo         | host/config lifecycle                                          |
| Documentación                | **8.0/10** | Muy buena                | bastante documentación, algo desactualizada respecto al código |

---

# 24. Potencial

## Potencial teórico

**90/100**

La arquitectura tiene espacio real para convertirse en una plataforma de chat bastante potente.

No es simplemente:

```text
listener → format → send
```

Tiene bases para:

```text
message model
routing
translation
policy engine
transports
extensions
module system
platform adapters
observability
configuration tooling
```

---

## Potencial arquitectónico

**80/100**

Las fronteras actuales permiten evolucionar considerablemente sin reescribir todo.

La principal limitación es que `host` concentra demasiada integración.

---

## Potencial alcanzado

**≈60/100**

No significa que sólo esté "60% programado".

Significa:

```text
capacidad arquitectónica potencial
vs
capacidad integrada y demostrablemente estable
```

La diferencia está principalmente en:

* lifecycle
* build
* extensions
* rules
* sync
* web editor
* CI
* network-scale semantics

---

# 25. Roadmap

## P0 — Crítico

### P0-1 — Cerrar WebSocket por defecto

**Problema:** endpoint 9092 activo aunque disabled y sin token.

**Beneficio:** elimina la vulnerabilidad más importante encontrada.

**Esfuerzo:** bajo.

**Dependencias:** ninguna.

---

### P0-2 — Lifecycle completo

Implementar:

```text
Runtime.close()
```

cerrando:

* dispatcher
* WebSocket
* Discord
* observability
* extensions
* module lifecycle
* listeners
* schedulers

**Esfuerzo:** medio.

---

### P0-3 — Reparar build reactor

Eliminar dependencias internas:

```text
me.majhrs16:suite-*:SNAPSHOT
```

cuando corresponda utilizar:

```gradle
project(':src:...')
```

**Esfuerzo:** medio.

---

### P0-4 — CI realmente representativo

Agregar al CI:

```text
fabric-host
coretranslator
common-legacy
```

o documentar explícitamente que son builds independientes.

**Esfuerzo:** bajo-medio.

---

## P1 — Alto

### P1-1 — Integrar rules.yml

```text
YAML
 ↓
RuleLoader
 ↓
DefaultRouter
```

---

### P1-2 — Corregir rate limiter

Aplicar límite por mensaje emitido, no por receptor.

---

### P1-3 — Reparar ExtensionManager

Classloader real por JAR.

---

### P1-4 — Corregir compatibility check

Comparar:

```text
runningCoreApi
vs
requiredCoreApi
```

---

### P1-5 — Reparar web editor

Eliminar errores sintácticos y hacer que CI vuelva a verde.

---

### P1-6 — Autenticar HTTP inbound

No permitir:

```text
POST /hook
```

sin autenticación cuando se expone externamente.

---

## P2 — Medio

### P2-1

Eliminar `hashCode()` de las keys de traducción.

### P2-2

Resolver la contradicción de mutabilidad de `ChannelRegistry`.

### P2-3

Centralizar configuración y schema.

### P2-4

Definir lifecycle uniforme:

```text
start()
stop()
reload()
```

para todos los modules.

### P2-5

Separar `host` en subcomponentes si sigue creciendo.

---

## P3 — Bajo

* limpieza legacy
* mejoras de documentación
* optimizaciones menores
* profiling adicional
* mejoras de DX
* tooling del CodeGuide

---

# 26. Escala network-level: qué tendría que cambiar

Para una red grande, las prioridades serían:

## 1. Event bus

No depender de sinks arbitrarios como backbone principal.

## 2. Idempotencia

Cada mensaje debería tener:

```text
messageId
origin
sequence
```

## 3. Deduplicación

Especialmente cuando:

```text
server A → proxy → server B
```

puede generar loops.

## 4. Backpressure

Toda cadena debe estar limitada:

```text
chat event
 ↓
queue
 ↓
routing
 ↓
translation
 ↓
sync
 ↓
delivery
```

## 5. Translation batching

No:

```text
500 HTTP calls
```

para un solo mensaje.

Idealmente:

```text
message
 ↓
unique target languages
 ↓
batch translation
 ↓
reuse results
```

## 6. Per-language fan-out

En lugar de:

```text
recipient
 ↓
translate
 ↓
render
```

hacer:

```text
message
 ↓
group recipients by language
 ↓
translate once/language
 ↓
render/send
```

## 7. Distributed rate limiting

El actual `RateLimiter` es local.

Network-level requiere:

```text
player identity
+
network scope
```

si el límite debe ser global.

---

# 27. Mayor fortaleza

La mayor fortaleza de TextFormatter Suite es:

> **la existencia de un core de procesamiento relativamente independiente de la plataforma, con un modelo de mensaje inmutable y ports reales para las operaciones externas.**

Esto permite que:

```text
Spigot
Fabric
Velocity
Discord
HTTP
```

no tengan que convertirse en parte del dominio del formatter.

Esa decisión arquitectónica sí tiene valor a largo plazo.

---

# 28. Peores defectos actuales

En orden de impacto técnico:

1. **WebSocket inseguro y activado por defecto.**
2. **Lifecycle/reload incompleto.**
3. **CI/build roto.**
4. **Rules/iFlow no completamente conectados al runtime.**
5. **Rate limiting incorrecto para fan-out.**
6. **ExtensionManager incompleto.**
7. **Web editor roto actualmente.**
8. **Arquitectura de sync todavía fragmentada.**
9. **Configuración representada en demasiados lugares.**
10. **Coste de traducción/render por receptor para escala grande.**

---

# 29. Lo que TextFormatter Suite NO es todavía

No lo describiría todavía como:

```text
production-proven network-scale chat platform
```

Tampoco como:

```text
fully modular runtime
```

ni:

```text
pure Clean Architecture implementation
```

Eso sería exagerar lo que demuestra el código actual.

Lo describiría técnicamente como:

> **un core de chat/formateo/routing modular y multiplataforma en desarrollo avanzado, con una arquitectura de ports/adapters bastante sólida, pero con integración de runtime, lifecycle, tooling y distribución todavía en fase de estabilización.**

---

# 30. Conclusión final

## 1. ¿Qué tan bueno es realmente TextFormatter Suite?

**Bueno y técnicamente ambicioso, pero todavía no acabado.**

El código está claramente por encima de un plugin Minecraft monolítico convencional.

---

## 2. ¿Qué partes están excepcionalmente bien diseñadas?

Especialmente:

* `Message` immutable
* `ChatDelivery`
* `ActorDirectory`
* TemplateRenderer
* MiniMessage escaping
* module descriptor model
* ModuleGraph
* separación platform/core
* TranslatorManager
* bounded dispatcher
* configuración mediante snapshots

---

## 3. ¿Qué partes son mediocres?

Principalmente:

* lifecycle
* configuración/editor synchronization
* extension runtime
* sync integration
* build orchestration
* network-scale semantics

---

## 4. ¿Cuáles son sus peores defectos?

El peor actualmente es el WebSocket:

```text
enabled=false
token=""
```

pero el servidor se inicia igualmente.

El segundo gran defecto es el lifecycle de reload.

---

## 5. ¿Cuál es su mayor fortaleza arquitectónica?

La separación:

```text
platform
   ↓
ports
   ↓
core
```

junto con el modelo inmutable de mensajes.

---

## 6. ¿Cuál es su mayor riesgo futuro?

Que `host` se convierta en:

```text
God module
```

que conozca absolutamente todo:

```text
config
modules
extensions
sync
translation
platform
observability
lifecycle
```

Si eso sucede, la modularidad actual perdería gran parte de su valor.

---

## 7. ¿Qué tan mantenible es?

**7/10 actualmente.**

Puede mantenerse bien si se estabilizan boundaries y lifecycle.

---

## 8. ¿Qué tan extensible es?

**8/10 conceptualmente.**

La arquitectura ofrece muchos extension points reales.

El sistema de extensions propiamente dicho necesita terminar de implementarse correctamente.

---

## 9. ¿Qué tan preparado está para múltiples plataformas?

**7/10 arquitectónicamente.**

**5/10 operacionalmente.**

Spigot está mucho más integrado.

Fabric existe, pero ni siquiera participa actualmente en el build raíz/CI.

---

## 10. ¿Qué tan preparado está para una red Minecraft grande?

**5/10 actualmente.**

Puede ser una buena base.

No es todavía una implementación demostrada de network-scale.

---

## 11. ¿Qué tendría que cambiar para network-level?

Principalmente:

```text
distributed event semantics
+
message IDs
+
deduplication
+
backpressure
+
translation batching
+
language fan-out
+
distributed rate limiting
+
failure isolation
+
metrics p95/p99
```

---

## 12. ¿Qué partes NO deberían tocarse?

No reescribir:

* `Message`
* `ChatDelivery`
* `ActorDirectory`
* `TemplateRenderer`
* `Module`
* `ModuleDescriptor`
* la separación de adapters
* el modelo de ports

Hay una base buena ahí.

---

## 13. ¿Qué debería hacerse primero?

Orden exacto:

```text
1. WebSocket security
2. Runtime lifecycle
3. Build/CI
4. rules.yml + iFlow wiring
5. rate limiter semantics
6. ExtensionManager
7. web editor
8. config/schema consolidation
9. sync architecture
10. network-scale optimization
```

No empezaría una reescritura arquitectónica.

---

## 14. ¿Qué porcentaje del proyecto considero técnicamente maduro?

**≈60%**

Con una interpretación estricta:

```text
Core conceptual:             ~80%
Código individual:           ~70%
Arquitectura:                ~70–75%
Integración runtime:         ~55–60%
Tooling/build:               ~50%
Production hardening:        ~50%
Network-scale readiness:     ~35–40%
```

El promedio aproximado termina alrededor del **60% de madurez técnica materializada**.

Eso no significa que el proyecto esté "a medias".

Significa que ya tiene bastante arquitectura y código construido, pero todavía existe una diferencia considerable entre:

```text
"la capacidad que el diseño pretende ofrecer"
```

y:

```text
"la capacidad que el sistema actual demuestra integrada, segura y reproduciblemente"
```

---

# Dictamen final

TextFormatter Suite **no necesita ser destruido y reescrito**.

De hecho, una reescritura sería contraproducente.

La arquitectura central tiene suficiente calidad para continuar evolucionando.

El proyecto está en una fase muy concreta:

```text
                    ┌─────────────────────┐
                    │  Idea / prototipo   │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Arquitectura sólida │
                    │       ← AQUÍ        │
                    └──────────┬──────────┘
                               │
                    estabilización necesaria
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Production-grade    │
                    │ network platform    │
                    └─────────────────────┘
```

El trabajo más importante ahora no es añadir otras veinte features.

Es **hacer que las features existentes formen un único sistema coherente**.

La arquitectura ya justifica seguir invirtiendo en ella.

Lo que falta es cerrar la brecha entre:

**diseño → integración → lifecycle → seguridad → CI → escala.**
