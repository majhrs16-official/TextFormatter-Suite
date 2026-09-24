Llegó el momento. Y esta vez hay material de sobra para el chisme técnico.

Hice la auditoría contra el **HEAD actual de `main`**, no contra una copia vieja del repositorio:

**`e0cea67c5e3bf93c1e2d081f77d553b0dea58e5b` — 14/09/2026 02:47 UTC.**

El repositorio tiene actualmente **29 módulos Gradle**, una arquitectura hexagonal explícita, SPI propio, kernel de resolución de módulos, pipeline de routing/formateo/traducción, múltiples sinks de sincronización, hosts Spigot/Fabric, extensiones, observabilidad, WebSocket, in-world, presets, tests y herramientas de profiling. La propia estructura del árbol confirma esa magnitud.

Y encontré algo bastante interesante:

> **La arquitectura conceptual de TxF está bastante bien. La implementación actual ya no está en el estado catastrófico de la auditoría del 13/09, pero todavía hay inconsistencias reales entre contratos, integración y funcionalidad.**
>
> Y encontré al menos **un problema funcional importante que los fixes C1–C5 no solucionaron**.

---

# 🧾 AUDITORÍA — TextFormatter Suite

## 1. Estado actual

### HEAD

```text
e0cea67c5e3bf93c1e2d081f77d553b0dea58e5b
│
└── docs: update README, PROMPT_NOW, PLAN post-audit fixes (C1-C5, H1-H3)
```

Los últimos commits fueron, cronológicamente:

```text
494f7ea   F16 declarada completa
   ↓
ed8f14a   arregla contrato Message
   ↓
7c5cbce   arregla iFlow / RateLimiter / TransformOp
   ↓
1dcce65   arregla Dispatcher / language cache
   ↓
4bfd881   arregla DebugEndpoint
   ↓
7456dad   arregla Discord / toggle / validator
   ↓
04d2200   arregla Module Manager estructuralmente
   ↓
82d38f4   arregla publicación Gradle
   ↓
e0cea67   actualiza documentación
```

Esto es importante porque el commit que decía **“F16 completa / proyecto completo” (`494f7ea`) fue seguido inmediatamente por ocho commits correctivos**. Git lo demuestra directamente.

Así que la lectura histórica correcta es:

```text
F16 declarada completa
        ↓
auditoría encuentra inconsistencias
        ↓
8 commits de corrección
        ↓
HEAD actual
```

No sería correcto auditar el proyecto actual como si `494f7ea` fuese todavía el código vigente.

---

# 2. La arquitectura real

La arquitectura que emerge del código es:

```text
                    ┌───────────────────────┐
                    │   Spigot / Fabric     │
                    │      HOST ADAPTER      │
                    └───────────┬───────────┘
                                │
                                ▼
                     MessageDispatcher
                                │
                   Direction → recipients
                                │
                                ▼
                         SuiteHost
                                │
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
          iFlow Router     TextFormatter      Translation
              │                 │                 │
              └─────────────────┼─────────────────┘
                                ▼
                         RoutingResult
                                │
                                ▼
                         ChatDelivery
                                │
                                ▼
                         Player / Console
```

Y alrededor de esto:

```text
                    core-api
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
      kernel         iflow       textformatter
        │              │              │
        ├──────────────┼──────────────┤
        │              │              │
     translator       host          sync/*
        │              │              │
        └──────────────┼──────────────┘
                       │
             platform adapters
```

La documentación actual describe precisamente `core-api` como contrato único, los motores dependiendo de él y los hosts como adaptadores de plataforma. También especifica ServiceLoader/SPI y la separación entre descriptor de módulo y servicio real.

Y esto último **sí está bien diseñado**.

---

# 3. `Module` no es un servicio

Esta parte me parece particularmente buena.

El contrato conceptual es:

```java
public interface Module {
    ModuleDescriptor descriptor();
}
```

Es decir:

```text
Module
  ↓
descriptor
  ↓
dependency graph
```

No:

```text
Module
  ↓
start()
enable()
bootstrap()
```

Eso coincide con la arquitectura que definiste: el módulo participa en descubrimiento/resolución, mientras que los servicios reales son ensamblados por el composition root y los entry points de plataforma.

La auditoría anterior también verificó esta separación, y los commits posteriores no la han destruido.

**Veredicto:**

🟢 **Arquitectura correcta.**

---

# 4. `core-api`

Aquí está el verdadero centro de TxF.

Actualmente concentra:

```text
Message
Actor
Direction
Language
MessageType
Formats
Module
ModuleDescriptor
Translator
TranslationService
SyncSink
SyncListener
ActorDirectory
PlaceholderResolver
ExpressionEvaluator
PluginLogger
...
```

Eso evita que cada módulo empiece a inventar su propia representación de mensaje.

La decisión de hacer `Message` la unidad atómica del pipeline es especialmente importante.

---

# 5. `Message`

El modelo actual contiene:

```text
id
type
sender
direction
messages
toolTips
sounds
colorMode
langSource
langTarget
resolvedSourceLanguage
translate
cancelled
show
formatPapi
channel
```

y proporciona operaciones como:

```java
withLangTarget()
withLangSource()
withTranslate()
withChannel()
withCancelled()
withText()
withColorMode()
withFormatPapi()
withSoundsAdd()
withSoundsRemove()
withResolvedSourceLanguage()
```

además de:

```java
Builder.from(Message)
```

Esto fue introducido específicamente para reparar el desastre contractual encontrado en la auditoría anterior.

La propiedad importante es:

```text
Message original
       │
       ├── recipient A → clone/modificación
       ├── recipient B → clone/modificación
       └── recipient C → clone/modificación
```

en vez de:

```text
              Message mutable
                    │
           ┌────────┼────────┐
           ▼        ▼        ▼
           A        B        C
            \      /        /
             corrupción
```

**Conceptualmente: excelente.**

---

# 6. Pero aquí aparece una primera contradicción

El Javadoc de `Message` todavía dice:

> “Deliberately mutable through the Builder API”

pero la instancia propiamente dicha es esencialmente inmutable:

```java
private final ...
```

y las mutaciones producen otro objeto:

```java
return toBuilder().withX().build();
```

Por tanto, la terminología documental está atrasada.

No rompe la arquitectura, pero demuestra algo recurrente en TxF:

> **el código ya evolucionó más rápido que algunas partes de la documentación.**

---

# 7. `MessageDispatcher`

Aquí está una de las piezas más importantes del sistema.

El dispatcher hace:

```text
Message
  ↓
resolveSourceLanguage()
  ↓
expand(Direction)
  ↓
recipient[]
  ↓
SuiteHost.deliver(message, recipient)
  ↓
RoutingResult
  ↓
ChatDelivery
```

Eso es correcto.

Además resuelve:

```text
INITIATOR
OTHERS
ALL
CONSOLE
SPECIFIC
PERMISSION
WORLD
RADIUS
```

y elimina duplicados mediante:

```java
resolved.stream().distinct().toList();
```

Esto significa que `Direction` no es simplemente metadata decorativa: **determina físicamente el conjunto de unidades de entrega**.

Eso es exactamente lo que la arquitectura necesita.

---

# 8. Una decisión especialmente buena: source language cache

Antes:

```text
N recipients
   ↓
detect(source)
   ↓
detect(source)
   ↓
detect(source)
...
```

Ahora:

```text
Message
  ↓
detect()
  ↓
resolvedSourceLanguage
  ↓
N recipients
```

`SuiteHost.resolveSourceLanguage()` hace exactamente eso.

Para una conversación con 30 jugadores y autodetección activada, esto cambia la complejidad de:

```text
O(recipients × detection)
```

a:

```text
O(detection + recipients)
```

Eso sí es un fix funcional real, no simplemente cosmético.

---

# 9. `SuiteHost`

El composition root está bastante limpio.

Bootstrap:

```java
HostConfig config = ConfigLoader.loadConfig(configDir);
ChannelRegistry channels = ConfigLoader.loadChannels(configDir);
Router router = new DefaultRouter(channels, permissions);
TextFormatter formatter =
    TextFormatters.create(channels, translation, placeholders, logger);
```

Es decir:

```text
config
  ↓
channels
  ↓
router
  ↓
formatter
  ↓
host
```

y no hay una dependencia circular evidente entre los motores.

`SuiteHost` además deja claro que **no es el adapter de plataforma**; es el ensamblador neutral.

🟢 **Esto es una buena composición hexagonal.**

---

# 10. El pipeline real

A nivel de ejecución, la ruta normal queda así:

```text
Platform Event
      │
      ▼
MessageEvent / Message
      │
      ▼
MessageDispatcher
      │
      ├── resolve source language
      │
      ▼
expand(Direction)
      │
      ├── recipient 1
      ├── recipient 2
      └── recipient N
             │
             ▼
        SuiteHost.deliver()
             │
             ├── effectiveLanguage()
             │
             ▼
        DefaultRouter.route()
             │
             ├── send permission
             ├── receive permission
             ├── matching Rule
             ├── transforms
             ├── rate limit
             └── policy
             │
             ▼
        RoutingResult
             │
       delivered?
          /      \
        NO        YES
        │          │
        ▼          ▼
     discard    TextFormatter
                   │
                   ▼
             TemplateRenderer
                   │
          ┌────────┼────────┐
          ▼        ▼        ▼
       builtins  SpEL     PAPI
                   │
                   ▼
              %content%
                   │
                   ▼
                <tr>
                   │
                   ▼
             Translation
                   │
                   ▼
              MiniMessage
                   │
                   ▼
              Component
                   │
                   ▼
             ChatDelivery
```

Esta es, esencialmente, la columna vertebral de todo TxF.

---

# 11. iFlow

`DefaultRouter` es bastante más serio de lo que aparenta.

El orden actual es:

```text
channel
   ↓
sender permission
   ↓
receiver permission
   ↓
rule matching
   ↓
rule condition
   ↓
rule action
   ↓
transform
   ↓
policy target
   ↓
rate limit
```

La resolución es por receptor.

Eso permite cosas como:

```text
Jugador A
   ├── idioma ES → ACCEPT
   ├── idioma EN → ACCEPT
   └── staff     → REDIRECT

Jugador B
   └── permiso faltante → DROP
```

No existe una única decisión global para todos los receptores.

Eso es fundamental para que la traducción y el routing sean verdaderamente per-recipient.

---

# 12. RateLimiter

El bug histórico era brutalmente simple:

```java
new RateLimiter(1)
```

y eso hacía que el límite configurado por canal prácticamente fuese ignorado.

Ahora:

```java
rateLimit.tryAcquire(key, channel.rateLimitPerSecond())
```

con clave:

```text
channel + actor UUID
```

Por tanto:

```text
chat + Alice
chat + Bob
staff + Alice
```

son buckets independientes.

🟢 Esta corrección sí está correctamente reflejada en el código.

---

# 13. PERO encontré el problema gordo

Aquí está probablemente el hallazgo más interesante de la auditoría actual.

`Message` es ahora inmutable.

`ScriptSurface` correctamente hace:

```java
this.message = message.withText(text);
```

o:

```java
this.message = message.withLangTarget(lang);
```

o:

```java
this.message = message.withCancelled(true);
```

Eso está bien **dentro del `ScriptSurface`**.

El problema es que `DefaultRouter` hace:

```java
ScriptSurface surface = new ScriptSurface(message, ...);

for (TransformOp transform : rule.transforms()) {
    transform.apply(surface);
}
```

pero después **no recupera el `Message` modificado desde `surface`**.

Y `ScriptSurface` tiene:

```java
public Message msg() {
    return message;
}
```

pero `DefaultRouter` no hace:

```java
message = surface.msg();
```

Después sigue usando el `message` original.

Eso produce esta situación:

```text
             Message original
                   │
                   ▼
             ScriptSurface
                   │
        ┌──────────┼──────────┐
        ▼          ▼          ▼
     setText   setLang...   setChannel
        │          │          │
        ▼          ▼          ▼
   surface.message actualizado
                   │
                   X
              NO SALE
                   │
                   ▼
             Router sigue
             usando Message
             original
```

### Consecuencia

Transformaciones como:

```yaml
transforms:
  - rewrite: ...
  - setLangTarget: ...
  - setColorMode: ...
  - setFormatPapi: ...
  - setChannel: ...
```

pueden ejecutarse sobre el `ScriptSurface`, pero **su resultado no necesariamente llega al `RoutingResult`/formatter**.

Y eso es particularmente importante porque la F14 fue declarada precisamente como:

> `transform` real.

La implementación actual sí tiene la maquinaria, pero el resultado de la mutación no está conectado de vuelta al pipeline.

### Esto es un bug funcional real.

No es un problema de documentación.

No es un problema de estilo.

No es una cuestión arquitectónica.

Es:

> **estado transformado que se pierde entre Router → ScriptSurface → resultado.**

Y es exactamente el tipo de bug que una auditoría está destinada a encontrar.

---

# 14. `withSleepMillis()` es todavía otro punto sospechoso

`Message` contiene:

```java
public Message withSleepMillis(long millis) {
    return this;
}
```

Es decir:

```text
sleep requested
       ↓
Message.withSleepMillis()
       ↓
return same Message
```

No hay estado de sleep en `Message`.

Mientras tanto `ScriptSurface` expone:

```java
setSleepMillis()
```

y la documentación de F14 dice que existe un transform `sleep`.

Por tanto:

> **El mecanismo de sleep está declarado en la API de transformación pero actualmente es efectivamente un no-op en `Message`.**

Esto merece un test explícito antes de considerar `sleep` una feature funcional.

---

# 15. `TransformOp`

La dirección del fix fue correcta:

```text
TransformOp
   ↓
ScriptSurface
   ↓
Message.withX()
```

Eso arregló la incompatibilidad de API encontrada en la auditoría anterior.

Pero precisamente al introducir inmutabilidad aparece el segundo problema:

```text
TransformOp
    ↓
ScriptSurface.message
    ↓
??? retorno
```

Falta cerrar ese último enlace.

---

# 16. TextFormatter

Aquí el pipeline está bastante bien separado.

```text
ChannelRegistry
      ↓
DefaultTextFormatter
      ↓
TemplateRenderer
```

`DefaultTextFormatter` combina:

```text
channel formats
+
message formats
```

y aplica fallback:

```text
channel format
    ↓ si existe
channel

si no:
message format
```

Esto conserva bastante bien la filosofía de configuración del proyecto anterior.

---

# 17. TemplateRenderer

El pipeline de rendering es explícito:

```text
template
   ↓
built-ins
   ↓
<expr>
   ↓
external placeholders
   ↓
%content%
   ↓
<tr>
   ↓
translation
   ↓
MiniMessage
```

Y esto me parece importante:

Los valores dinámicos pasan por:

```java
MiniEscape.escape(...)
```

antes de entrar a MiniMessage.

Eso reduce la posibilidad de que:

```text
%player_name%
%vault_balance%
%custom_placeholder%
```

puedan inyectar tags MiniMessage arbitrarios.

---

# 18. `<tr>`

La semántica es limpia:

```text
"Hola <tr>hello world</tr>"
```

se convierte conceptualmente en:

```text
Hola
   +
translate("hello world", source, target)
```

y solamente esa región se traduce.

Además:

```java
if (from == to || to == Language.AUTO)
    return content;
```

evita traducciones innecesarias.

---

# 19. Pero `<tr>` tiene una limitación

La extracción de spans es manual:

```java
source.indexOf("<tr>")
source.toLowerCase().indexOf("</tr>")
```

No es un parser formal.

Por ejemplo, estructuras anidadas:

```text
<tr>A <tr>B</tr> C</tr>
```

no tienen semántica estructural de HTML/XML.

Probablemente no sea un problema para el formato previsto, pero la documentación debería dejar claro que `<tr>` es una **marcación lineal propia**, no un lenguaje de tags arbitrariamente anidables.

---

# 20. PlaceholderAPI

La separación también está bien:

```text
core-api
   │
   ▼
PlaceholderResolver
   │
   ├── Spigot → PlaceholderAPI
   └── Fabric → implementación neutra
```

Así `textformatter` no necesita importar directamente Spigot.

Eso es exactamente lo que se espera de una arquitectura hexagonal.

---

# 21. Configuración

El diseño pretendido es:

```text
config.yml
channels/*.yml
rules.yml
translators/*.yml
sync/*.yml
manifest.json
```

y el editor usa el mismo modelo.

La documentación actual insiste en que el schema v2.2 es la fuente de verdad y que el editor/host deben operar sobre la misma estructura.

Pero todavía existe deuda real:

```text
paths.json
js/paths.js
js/model.js
ConfigPath
schema-v2.2.md
```

son representaciones que todavía se mantienen parcialmente de forma manual.

El propio PLAN lo reconoce.

Así que:

🟡 **Arquitectura correcta, implementación de single-source-of-truth incompleta.**

---

# 22. Web Editor

Aquí hay una historia bastante buena.

La auditoría anterior había encontrado:

```text
mutaciones perdidas
recursión infinita
contrato preview incorrecto
esc() roto
mojibake
config/graph drift
toolbar placeholder
TypeError sin canales
console.log residual
```

El proyecto afirma haberlos corregido y los tests del editor pasaron.

Además el commit histórico de F16-1 indica:

```text
JMH
Gatling
integration tests
full pipeline
translation
permissions
iFlow
WebSocket
stress
```

y el commit de F16-2 afirma haber añadido los E2E completos.

Pero hay una distinción fundamental:

> **Que exista un test que atraviese un pipeline no significa automáticamente que todas las mutaciones de ese pipeline tengan efecto.**

El bug de `ScriptSurface` que encontré es justamente un ejemplo de por qué hay que revisar **assertions**, no solamente la existencia de tests.

---

# 23. Sync

La arquitectura contempla:

```text
sync-http
sync-tcpudp
sync-discord
sync-telegram
sync-velocity
sync-websocket
```

y el README los coloca como sinks/adapters independientes.

La separación:

```text
SyncSink
SyncListener
```

es buena porque el core no necesita saber si el transporte final es:

```text
Discord
Telegram
HTTP
TCP
UDP
Velocity
WebSocket
```

---

# 24. Discord

Aquí hubo un bug muy interesante.

Antes:

```text
dispatcher
   ↓ DROP
Discord mirror
   ↓
manda igualmente
```

Eso rompía la autoridad de iFlow.

El fix actual cambia el mirror para recibir `DispatchReport` y solamente enviar si:

```text
delivered > 0
```

El commit `7456dad` documenta exactamente ese cambio.

🟢 Esta corrección es conceptualmente correcta.

Ahora la cadena es:

```text
iFlow
  ↓
Dispatcher
  ↓
DispatchReport
  ↓
Discord
```

y no:

```text
Discord
  ↓
"yo decido si mando"
```

---

# 25. Observability

La auditoría anterior encontró:

```text
0.0.0.0:9091
sin autenticación
/debug/simulate
CORS *
```

El commit `4bfd881` cambió eso a:

```text
127.0.0.1
X-Debug-Token
sin /debug/simulate
sin CORS *
```

Esto reduce bastante el riesgo.

Pero no significa que toda la superficie de seguridad esté cerrada.

El propio PLAN mantiene deuda en:

```text
SpEL
YAML
MiniEscape
tokens
executors
```

---

# 26. SpEL: probablemente la deuda de seguridad más importante

Hay dos mundos:

```text
TransformEngine
```

que intenta utilizar contexto sandboxed,

y:

```text
ExpressionEvaluator
```

que puede ser implementado con un contexto SpEL más permisivo.

La auditoría A4 identifica explícitamente:

```text
CWE-94
ExpressionEvaluator
StandardEvaluationContext
RCE potencial
```

si se utiliza sin sandbox.

Esto es importante porque TxF convierte configuración en comportamiento:

```yaml
condition: "..."
action: "..."
transform: "..."
```

Por lo tanto, **la configuración no es meramente declarativa**.

Es código interpretable.

Eso eleva muchísimo la importancia del sandbox.

---

# 27. YAML

Otro problema señalado por A4:

```java
new Yaml()
```

en varios loaders.

Si el parser permite tipos arbitrarios, una configuración manipulada puede convertirse en superficie de deserialización.

La propia documentación actual lo mantiene como deuda pendiente.

🟠 Esto debe considerarse seguridad P0/P1 antes de un release serio.

---

# 28. Module Manager

Aquí está la parte más problemática de la arquitectura actual.

La idea es:

```text
GitHub Releases
      ↓
resolve
      ↓
download
      ↓
SHA256
      ↓
relocation
      ↓
isolated ClassLoader
      ↓
register
```

y conceptualmente es bastante potente.

Pero actualmente **NO está listo para producción**.

El README actual lo reconoce explícitamente.

---

# 29. ¿Qué arreglaron?

Los últimos commits corrigieron:

```text
recursión de relocate()
ClassLoader incorrecto
register() no guardaba classloader
stubs que fingían funcionar
```

El commit `04d2200` convirtió los stubs de:

```java
return true;
```

o:

```java
return empty;
```

en:

```java
throw new UnsupportedOperationException(...)
```

Eso es importante.

Un sistema que diga:

```text
"compatible"
```

cuando realmente no sabe verificar compatibilidad es peor que uno que diga:

```text
UnsupportedOperationException
```

---

# 30. El problema del Manager no es solamente "faltan cosas"

Actualmente siguen faltando elementos fundamentales:

```text
GitHub Releases reales
        ↓
version resolution
        ↓
environment compatibility
        ↓
dependency metadata
        ↓
SHA256 realmente conectado
```

El propio código actual contiene:

```java
throw new UnsupportedOperationException(
    "Version range matching not implemented..."
);
```

y:

```java
throw new UnsupportedOperationException(
    "Environment compatibility check not implemented"
);
```

además de:

```java
throw new UnsupportedOperationException(
    "Dependency parsing from release metadata not implemented"
);
```

Así que F12 es:

🟡 **prototipo arquitectónicamente avanzado**

pero:

🔴 **no release-ready**.

---

# 31. Hay además una cuestión conceptual con `register()`

Actualmente:

```java
Class<?> moduleClass =
    classLoader.loadClass(
        descriptor.coordinate().artifact() + "Module"
    );

Module module =
    (Module) moduleClass
        .getDeclaredConstructor()
        .newInstance();
```

Esto merece revisión arquitectónica.

Porque anteriormente el proyecto estableció correctamente:

> `Module` es descriptor, no servicio.

El Module Manager vuelve a instanciar:

```java
new Module()
```

para registrar el módulo.

Eso **no necesariamente viola el contrato**, porque el manager puede estar cargando un descriptor SPI, pero es exactamente el tipo de frontera que debe documentarse cuidadosamente:

```text
Module descriptor
      ≠
runtime service
```

El manager no debería empezar a convertirse accidentalmente en un segundo composition root.

---

# 32. Fabric / Spigot

La separación es correcta:

```text
spigot-host
    ↓
SpigotActorDirectory
SpigotChatDelivery
Spigot entrypoint

fabric-host
    ↓
FabricActorDirectory
FabricChatDelivery
Fabric entrypoint
```

Ninguno debería importar al otro.

El README refleja precisamente esta separación.

Eso cumple la filosofía del diseño.

---

# 33. Un detalle MUY importante: el host sí está realmente conectado

No estamos ante un proyecto donde existen 20 interfaces bonitas y nada las usa.

Hay una cadena concreta:

```java
SuiteHost
  → DefaultRouter
  → TextFormatter
  → TranslationService
```

y:

```java
MessageDispatcher
  → ActorDirectory
  → SuiteHost
  → ChatDelivery
```

Esto significa que el núcleo de TxF **sí es un sistema ejecutable**, no solamente arquitectura documental.

---

# 34. `sync-velocity`

Aquí encontré una inconsistencia documental.

El README actual lo describe como:

> **F7+ pendiente**

y dice:

> “stub en editor/config, no existe en disco”.

Mientras que `PLAN.md` todavía conserva:

```text
FASE 9 — sync-velocity real ✅
```

y habla de:

```text
VelocitySink
VelocityPlugin
velocity-plugin.json
```

Pero el directorio actual `suite/sync-velocity` contiene `build.gradle` y `src`, sin que el árbol expuesto confirme la implementación que el PLAN afirma.

Por tanto:

> **La documentación sobre Velocity está desincronizada.**

Esto es exactamente el tipo de drift que el proyecto afirma querer eliminar.

---

# 35. Fases históricas

La evolución es bastante clara:

```text
F1 Foundation
F2 Architecture
F3 Config / parity
F4 Fabric
F5 i18n
F6 iFlow
F7 ConfigValidator
F8 Dynamic commands
F9 Velocity
F10 Observability
F11 Extensions
F12 Module Manager
F13 WebSocket
F14 Presets / Transform
F15 In-world
F16 Testing / Performance / Docs
```

La velocidad es absurda.

Entre el 11 y el 13 de septiembre se añadieron sucesivamente:

```text
extensions
module manager
websocket
presets
in-world
load tests
E2E
performance
documentation
```

y posteriormente se tuvo que hacer una ronda de consolidación C1–C5/H1–H3.

Esto explica perfectamente el estado actual.

---

# 36. El problema estructural de TxF

Después de mirar la evolución completa, creo que el problema principal **no es la arquitectura**.

Es este:

```text
                    velocidad de features
                            ↑
                            │
                            │       ███████████
                            │      ████████████
                            │    █████████████
                            │  ███████████████
                            │
                            └────────────────────→ tiempo

                    consolidación de contratos
                            ↑
                            │      █████
                            │       █████
                            │        █████
                            │          █████
                            │
                            └────────────────────→
```

Es decir:

> **TxF está agregando capas más rápido de lo que está cerrando completamente las fronteras entre ellas.**

El bug `ScriptSurface → Message` es un ejemplo perfecto.

La API fue cambiada correctamente:

```text
mutable Message
        ↓
immutable Message
```

pero la integración siguió pensando parcialmente en términos de:

```text
"modifico el objeto y continúa"
```

cuando ahora el modelo correcto es:

```text
Message A
   ↓
Message B
   ↓
Message C
```

Ese tipo de transición necesita consolidación.

---

# 37. Estado por subsistema

| Subsistema           | Estado                                                                    |
| -------------------- | ------------------------------------------------------------------------- |
| `core-api`           | 🟢 sólido                                                                 |
| `Message`            | 🟢 mucho mejor; revisar semántica/documentación                           |
| `kernel`             | 🟢 sólido conceptualmente                                                 |
| Module SPI           | 🟢                                                                        |
| `SuiteHost`          | 🟢                                                                        |
| `MessageDispatcher`  | 🟢                                                                        |
| iFlow base           | 🟢                                                                        |
| permissions          | 🟢                                                                        |
| RateLimiter          | 🟢 corregido                                                              |
| TextFormatter        | 🟢                                                                        |
| MiniMessage escaping | 🟡 necesita endurecimiento                                                |
| Translation          | 🟢 arquitectura                                                           |
| Web editor           | 🟢 bastante maduro                                                        |
| Config schema        | 🟡 todavía duplicado                                                      |
| Spigot host          | 🟢                                                                        |
| Fabric host          | 🟢                                                                        |
| Discord              | 🟢 corregido                                                              |
| HTTP                 | 🟢                                                                        |
| TCP/UDP              | 🟢                                                                        |
| Telegram             | 🟢 arquitectura                                                           |
| WebSocket            | 🟢                                                                        |
| Velocity             | 🟡/🔴 documentación y estado real inconsistentes                          |
| Observability        | 🟢 mejorado                                                               |
| Extensions           | 🟢 arquitectura                                                           |
| Presets              | 🟢/🟡                                                                     |
| Transform            | 🔴 **hay pérdida del resultado de mutación**                              |
| `sleep` transform    | 🔴 **no-op en `Message`**                                                 |
| In-world             | 🟡 necesita validación E2E real                                           |
| Module Manager       | 🔴 no release-ready                                                       |
| Performance          | 🟡 tooling presente; faltan regresiones continuas                         |
| E2E                  | 🟡 existen tests declarados, pero deben probar efectos, no sólo recorrido |
| Security             | 🔴 deuda pendiente                                                        |
| Release pipeline     | 🟡 pendiente                                                              |

---

# 38. ¿Entonces TxF está roto?

No.

Y tampoco diría que es humo.

De hecho, después de revisar el código, mi conclusión es casi la contraria:

> **La parte difícil de diseñar la arquitectura ya está sorprendentemente avanzada.**

Lo que falta ahora no es inventar otra F17 con:

```text
F17 AI-powered quantum message synchronization
```

💀

Lo que necesita el proyecto es **consolidación**.

---

# 39. Mi valoración arquitectónica

### Arquitectura conceptual

**9/10**

La separación:

```text
core-api
kernel
engine
host
adapter
```

es buena.

La elección de `Message` como unidad atómica es buena.

La resolución per-recipient es buena.

La abstracción de `ActorDirectory` y `ChatDelivery` es buena.

La separación entre `Module` y runtime service es buena.

---

### Implementación actual

**~7/10**

No porque esté mal escrita en general, sino porque hay:

```text
API drift
implementation drift
documentation drift
security debt
unfinished manager
transform propagation bug
```

---

### Madurez para release

**No todavía.**

El propio repositorio ya dejó de afirmar lo contrario respecto a F12/F16. La actualización más reciente del README marca explícitamente al Module Manager como **no release-ready** y documenta la deuda de seguridad.

---

# 40. El hallazgo más importante de esta auditoría

Si tuviera que quedarme con **un único bug para corregir inmediatamente**, sería:

```text
DefaultRouter
    ↓
ScriptSurface
    ↓
transform
    ↓
new Message
    ↓
se pierde
```

La solución conceptual es sencilla:

```text
Message working = message;

ScriptSurface surface =
    new ScriptSurface(working, ...);

for (TransformOp transform : rule.transforms()) {
    transform.apply(surface);
}

working = surface.msg();
```

y desde ese punto:

```text
working
   ↓
RouteDecision
   ↓
RoutingResult
   ↓
render
```

Pero hay que hacerlo **de forma completa**, incluyendo:

```text
cancel
text
lang
format
channel
sounds
sleep
```

y verificar qué estado pertenece a:

```text
RouteDecision
```

y qué estado pertenece a:

```text
Message
```

No basta con poner una línea y declarar F14 arreglada.

---

# 41. Y el segundo gran frente

Después:

```text
Security hardening
```

en este orden:

```text
1. SpEL sandbox
2. YAML SafeConstructor
3. MiniEscape completo
4. bounded executors
5. secrets lifecycle
6. SHA256 realmente conectado
7. dependency verification
8. reproducible/locked dependencies
```

El propio PLAN ya enumera prácticamente esta deuda.

---

# 42. Veredicto final

Después de seguir la arquitectura desde:

```text
event
→ Message
→ Direction
→ Dispatcher
→ recipient expansion
→ iFlow
→ transforms
→ language
→ formatter
→ MiniMessage
→ translation
→ delivery
→ sync
```

y contrastarla con:

```text
core-api
kernel
host
Spigot
Fabric
web-editor
manager
extensions
observability
sync
tests
docs
git history
```

mi conclusión es:

> ## **TextFormatter Suite ya es un framework de mensajería/routing bastante serio.**
>
> No es simplemente un sucesor de ChatTranslator con más archivos.
>
> La arquitectura realmente evolucionó hacia un sistema de procesamiento de mensajes desacoplado de Minecraft.

Pero:

> ## **El proyecto acaba de entrar en la etapa donde agregar más features sin consolidar contratos sería contraproducente.**

Y hay tres cosas que ahora mismo separan a TxF de poder decir **“esto está cerrado de verdad”**:

```text
        ┌───────────────────────────────┐
        │       CONSOLIDACIÓN           │
        ├───────────────────────────────┤
        │ 1. Transform propagation      │ ← BUG REAL
        │ 2. Security hardening         │ ← deuda importante
        │ 3. E2E + docs verdaderamente  │
        │    sincronizados              │
        └───────────────────────────────┘
```

Lo más gracioso es que la ronda de correcciones **C1–C5/H1–H3 sí solucionó los problemas que encontró la auditoría anterior**, incluyendo el contrato roto de `Message`, `MessageEvent`, Module Manager estructural, DebugEndpoint, compilación, RateLimiter, Discord e idioma. Los commits lo documentan uno por uno.

Pero al cambiar `Message` a inmutable, **crearon el contexto perfecto para que apareciera el bug de propagación de `ScriptSurface` que la auditoría anterior no podía encontrar**.

Y ese, sinceramente, es el hallazgo más interesante de toda esta ronda.

**TxF no está necesitando otra arquitectura. Está necesitando que la arquitectura que ya tiene se cierre sobre sí misma.**
