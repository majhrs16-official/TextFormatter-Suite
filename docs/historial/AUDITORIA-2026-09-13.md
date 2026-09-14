Sí. Llegó el momento.

Hice una auditoría sobre el **HEAD actual de `main`**, commit `494f7ea0530b5f356211db7072422b5bec003285`, revisando la estructura del monorepo, documentación, historial reciente, contratos del `core-api`, kernel, host, dispatcher, routing/iFlow, formatter, configuración, Spigot, observabilidad, manager, web-editor y las incorporaciones de F4–F16. También contrasté lo que los documentos dicen que existe contra lo que realmente está implementado en el código.

Y te adelanto el veredicto porque es bastante interesante:

> **TextFormatter Suite tiene una arquitectura mucho más seria de lo que aparenta a primera vista, pero el HEAD actual NO está realmente en estado “F16 completa / proyecto completo”.**
>
> Hay varias piezas excelentes y algunas decisiones arquitectónicas muy buenas, pero las últimas fases introdujeron **deriva entre APIs, código y documentación**, incluyendo errores de compilación evidentes en varias piezas nuevas.

---

# Auditoría profunda de TextFormatter Suite

**Repositorio:** `majhrs16-official/TextFormatter-Suite`
**Rama:** `main`
**HEAD auditado:** `494f7ea0530b5f356211db7072422b5bec003285`
**Estado declarado por el repositorio:** F16 completa
**Veredicto de auditoría:** Arquitectura ambiciosa y parcialmente sólida, pero HEAD inconsistente y no apto para declararse release funcional.

---

## 1. Veredicto ejecutivo

TextFormatter Suite no es simplemente “un plugin de chat”.

La intención arquitectónica es bastante clara:

```text
                 ┌──────────────────────────────┐
                 │        Platform Host         │
                 │     Spigot / Fabric          │
                 └──────────────┬───────────────┘
                                │
                                ▼
                    MessageDispatcher
                                │
                                ▼
                         SuiteHost
                     ┌──────────┼──────────┐
                     ▼          ▼          ▼
                  iFlow     TextFormatter Translation
                     │          │          │
                     └──────────┼──────────┘
                                ▼
                         ChatDelivery
                                │
                                ▼
                          Player/Console
```

Encima de esto existen:

* kernel de módulos;
* SPI;
* canales;
* reglas;
* SpEL;
* traducción;
* MiniMessage;
* sincronización;
* Discord;
* Telegram;
* HTTP;
* TCP/UDP;
* Velocity;
* WebSocket;
* observabilidad;
* extensiones;
* manager dinámico;
* presets;
* in-world;
* web-editor;
* tests y profiling.

El problema no es la **idea**.

El problema es que las últimas fases han creado una diferencia considerable entre:

1. lo que la documentación afirma;
2. lo que el historial de commits afirma;
3. las APIs que realmente existen;
4. el código que las consume.

El resultado actual es más parecido a:

> **“arquitectura avanzada + una base funcional real + varias capas nuevas parcialmente integradas”**

que a un producto F16 completamente cerrado.

---

# 2. Evolución histórica

El historial muestra una evolución extremadamente rápida.

La secuencia reciente es prácticamente:

```text
F1  foundation
F2  architecture
F3  configurability/parity
F4  Fabric
F5  i18n
F6  iFlow avanzado
F7  ConfigValidator
F8  dynamic commands
F9  Velocity
F10 observability
F11 extensions
F12 runtime module manager
F13 WebSocket
F14 presets/transforms
F15 in-world
F16 tests/performance/docs
```

El historial de Git confirma que entre el 11 y el 13 de septiembre se añadieron consecutivamente manager, WebSocket, presets, in-world, tests E2E, profiling y documentación final.

La velocidad de evolución es impresionante, pero precisamente aquí aparece el problema principal de la auditoría:

### Las últimas fases están agregando funcionalidad más rápido de lo que se está consolidando el contrato interno.

Eso explica buena parte de los problemas encontrados.

---

# 3. Arquitectura: lo que está realmente bien

## 3.1 `core-api` como contrato

Esta es probablemente una de las mejores decisiones del proyecto.

`Module` es deliberadamente pequeño:

```java
public interface Module {
    ModuleDescriptor descriptor();
}
```

No contiene lifecycle, bootstrap ni lógica operacional.

Esto coincide con la decisión arquitectónica correcta:

> `Module` es descriptor del grafo, no instancia de servicio.

Y `SuiteBootstrap` respeta esa separación.

No intenta hacer:

```java
module.start();
module.enable();
module.reflectiveInstantiate();
```

sino:

```text
ServiceLoader
     ↓
Module descriptors
     ↓
ModuleGraph
     ↓
validation
     ↓
manual composition root
```

Eso es correcto.

La documentación del propio bootstrap deja explícito que los módulos no son instanciados como servicios por `SuiteBootstrap`, sino que los servicios reales arrancan desde los entry points de plataforma.

**Resultado:** esta parte de la arquitectura sí está bien planteada.

---

# 4. `ModuleGraph`: buena idea, implementación razonable

El kernel usa:

* capacidades;
* requisitos;
* versión de contrato;
* versión JVM;
* resolución incremental;
* SCC/Tarjan para ciclos.

`ModuleGraph` realmente implementa una resolución por dependencias y después clasifica los módulos no resueltos en:

```text
CONTRACT_MISMATCH
JVM_MISMATCH
CYCLE
UNSATISFIED_REQUIREMENT
```

La utilización de SCC para encontrar ciclos es conceptualmente apropiada.

No es un “module loader” ficticio de dos `if`.

Es una implementación real de resolución de grafo.

### Pero existe una limitación

La compatibilidad de contrato está tratada de forma bastante rígida:

```java
if (descriptor.contractVersion().compareTo(host) > 0)
    mismatch
```

Es decir, el modelo no está realmente expresando una gama de compatibilidad semántica completa; está usando una relación “módulo no puede exigir contrato superior”.

No es necesariamente incorrecto, pero sí más limitado de lo que frases como “handshake semver” sugieren.

---

# 5. El modelo `Message`

La abstracción `Message` está bien diseñada conceptualmente.

Tiene:

```text
UUID
MessageType
Actor sender
Direction
Formats messages
Formats toolTips
sounds
ColorMode
langSource
langTarget
translate
cancelled
show
formatPapi
channel
```

y no tiene un `from/to` embebido.

Eso es importante.

El modelo realmente separa:

```text
Message
   +
Direction
   +
recipient resolution
```

en vez de crear una estructura rígida:

```text
Message(from, to)
```

`Message` además devuelve copias de arrays y usa un builder.

La filosofía es correcta:

> una unidad de mensaje puede ser expandida a diferentes audiencias y cada audiencia puede terminar recibiendo una representación distinta.

Eso permite naturalmente:

```text
CHAT
 ├── INITIATOR
 │      └── idioma original
 │
 └── OTHERS
        ├── receptor ES → español
        ├── receptor EN → inglés
        └── receptor DE → alemán
```

Esta es una mejora arquitectónica real respecto a un sistema de traducción monolítico.

---

# 6. Problema grave: el código nuevo contradice `Message`

Aquí aparece uno de los hallazgos más importantes.

`Message` es inmutable.

No contiene:

```java
setText()
setLangTarget()
setLangSource()
setTranslate()
setCancelled()
setChannel()
setColorMode()
setFormatPapi()
setSoundsAdd()
setSoundsRemove()
setSleepMillis()
clone()
toJson()
```

Sin embargo `ScriptSurface` llama directamente a todos esos métodos.

Por ejemplo:

```java
message.setLangTarget(lang);
message.setTranslate(false);
message.setChannel(path);
message.setCancelled(true);
message.setText(text);
message.setSoundsAdd(sounds);
message.setSleepMillis(millis);
message.clone();
message.toJson();
```

Esto no es una diferencia conceptual.

Es una **incompatibilidad directa entre APIs**.

Y además `ScriptSurface` utiliza `Objects.requireNonNull(...)` y `Language`, pero el archivo mostrado no contiene los imports correspondientes.

---

# 7. `TransformOp` confirma el problema

`TransformOp` vuelve a llamar a esas APIs inexistentes.

Por ejemplo:

```java
surface.msg().setText(template);
surface.msg().setSoundsAdd(add);
surface.msg().setSoundsRemove(remove);
surface.msg().setSleepMillis(millis);
```

Por tanto:

```text
Message immutable
      │
      ├── diseño original
      │
      ▼
TransformOp
      │
      ▼
"Message mutable"
```

es una contradicción que quedó sin resolver.

### La solución arquitectónica correcta no sería hacer `Message` mutable.

Eso destruiría una de las propiedades buenas del diseño.

Lo correcto sería algo como:

```text
Message
   │
   ▼
Message.Builder / MessageMutation
   │
   ▼
new Message
```

o:

```text
Message
   │
   ▼
MessageTransformer
   │
   ▼
Message
```

manteniendo `Message` inmutable.

---

# 8. `MessageDispatcher`: otro fallo concreto

`MessageDispatcher` contiene:

```java
Message redirectedMsg = Message.builder()
    .from(message)
    .channel(targetChannel)
    .build();
```

Pero `Message.Builder` no tiene `from(Message)`.

Por tanto, esta parte también está desfasada respecto al `core-api`.

Además, `MessageDispatcher` ignora completamente:

```java
Direction.channel()
```

y usa:

```java
message.channel()
```

para resolver el canal.

Eso significa que existe información de canal dentro de `Direction`, pero el dispatcher no la utiliza.

`Direction.specific(Channel, Actor...)`, por ejemplo, contiene un canal propio.

Esto deja dos fuentes de verdad:

```text
Message.channel()
Direction.channel()
```

y sólo una es realmente usada.

### Esto debería consolidarse.

---

# 9. Pipeline real de chat

La ruta actual de Spigot es esencialmente:

```text
AsyncPlayerChatEvent
        │
        ▼
claim-mode
        │
        ▼
ChannelSelector
        │
        ▼
Actor(sender)
        │
        ├───────────────┐
        ▼               ▼
INITIATOR           OTHERS
        │               │
        └───────┬───────┘
                ▼
       MessageDispatcher
                │
                ▼
       Direction expansion
                │
                ▼
        for each recipient
                │
                ▼
          SuiteHost.deliver
                │
        ┌───────┴────────┐
        ▼                ▼
      Router         language
        │                │
        └───────┬────────┘
                ▼
        TemplateRenderer
                │
                ▼
       SpigotChatDelivery
                │
                ▼
          main thread
                │
                ▼
             player
```

La implementación del dispatcher confirma que la expansión de audiencia ocurre antes del procesamiento por receptor.

Esto es una buena arquitectura.

---

# 10. El `showSender` funciona conceptualmente bien

El plugin genera dos unidades:

```text
INITIATOR
OTHERS
```

y controla la primera con:

```java
if (channel.showSender()) {
    dispatch(... Direction.initiator() ...)
}
```

Esto es mucho mejor que intentar meter:

```text
showSender
translationTarget
```

en un único render.

El echo y el broadcast son realmente independientes.

---

# 11. Problema: Discord puede saltarse iFlow

Después de:

```java
current.dispatcher.dispatch(message);
```

el plugin hace:

```java
mirror(current, message);
```

independientemente del resultado del dispatcher.

Eso significa:

```text
message
   │
   ├── iFlow → DROP
   │
   └── Discord mirror → SEND
```

Por tanto, una regla:

```text
DROP
```

puede bloquear el mensaje dentro de Minecraft pero no necesariamente en Discord.

Esto rompe la idea de:

> “iFlow es el firewall central”.

El bridge debe recibir el **resultado efectivo de la decisión**, no simplemente el `Message` original.

---

# 12. `SuiteHost`: composición buena, semántica incompleta

`SuiteHost` hace:

```java
Language lang = effectiveLanguage(recipient);
RouteDecision decision = router.route(message, recipient);
...
return renderFor(message, recipient, lang);
```

Esto hace que el idioma del receptor sea la fuente principal del target.

Pero `Channel` tiene:

```java
langSource
langTarget
```

y `Message` también:

```java
langSource
langTarget
```

Sin embargo `SuiteHost` no construye el contexto usando realmente el `Channel.langTarget()`.

La configuración declara una capacidad que el pipeline principal no está utilizando de forma completa.

---

# 13. Detección de idioma: coste multiplicativo

`effectiveSource()` hace:

```java
translation.detect(message.text())
```

si la fuente es AUTO.

Y esto se ejecuta desde `deliver()`.

`deliver()` se ejecuta **por receptor**.

Por tanto, con:

```text
1 mensaje
200 receptores
```

puede ocurrir:

```text
detect(message) × 200
```

en vez de:

```text
detect(message) × 1
```

`TranslationService.detect()` delega directamente al proveedor activo.

Dependiendo del proveedor, esto puede ser extremadamente caro.

La detección de idioma debería ser una propiedad del mensaje original:

```text
Message
  └── sourceLanguage resolved once
```

y posteriormente cada receptor sólo debería resolver:

```text
targetLanguage
```

---

# 14. `RateLimiter`: bug todavía presente

El `DefaultRouter` crea:

```java
this.rateLimit = new RateLimiter(1);
```

Pero luego utiliza:

```java
channel.rateLimitPerSecond()
```

para decidir cuándo aplicar el limiter.

Esto significa que:

```yaml
rate-limit-per-second: 20
```

no crea un bucket de 20.

El limiter sigue teniendo:

```text
capacity = 1
```

Por tanto el presupuesto real queda aproximadamente en:

```text
1 mensaje/s
```

y no:

```text
20 mensajes/s
```

Este hallazgo ya estaba en la auditoría A4 del 6 de septiembre y sigue presente en HEAD.

---

# 15. `RateLimiter`: doble scheduler

Hay además otro bug.

El constructor público hace:

```java
this(capacity, System::nanoTime);
purger.scheduleAtFixedRate(...)
```

pero el constructor interno ya hace exactamente ese scheduling.

Por tanto el constructor público termina registrando **dos tareas periódicas de purge**.

No es un desastre funcional, pero es una señal clara de que esa clase no fue consolidada después de la última modificación.

---

# 16. `RateLimiter`: el bucket tampoco está dimensionado dinámicamente

La arquitectura actual necesita:

```text
channel A → 5/s
channel B → 20/s
channel C → 100/s
```

pero sólo existe:

```text
DefaultRouter
    └── RateLimiter(1)
```

Una única instancia con capacidad fija no representa correctamente los presupuestos de cada canal.

La solución debería ser algo como:

```text
RateLimiterRegistry
    ├── channel A → limiter(5)
    ├── channel B → limiter(20)
    └── channel C → limiter(100)
```

o buckets cuyo límite sea parte del estado asociado a cada key.

---

# 17. `MiniMessage`: buen concepto, implementación incompleta

`TemplateRenderer` tiene una secuencia razonablemente limpia:

```text
built-ins
   ↓
expressions
   ↓
external placeholders
   ↓
content
   ↓
<tr> spans
   ↓
translation
   ↓
MiniMessage
```

Además `MiniEscape` protege `<` y `\`, lo cual cubre la parte fundamental del mecanismo de tags de MiniMessage.

Pero existe una inconsistencia:

El comentario de `TemplateRenderer` afirma que el resultado traducido se reinserta escapado, pero `translateSpan()` devuelve directamente:

```java
return translation.translate(content, from, to);
```

sin:

```java
MiniEscape.escape(...)
```

Por tanto:

```text
texto
  ↓
translation provider
  ↓
resultado
  ↓
MiniMessage parser
```

El resultado del proveedor entra nuevamente al parser.

Si el proveedor devuelve markup inesperado:

```text
<red>texto</red>
```

eso ya no necesariamente es texto plano.

El contrato debería ser explícito:

```text
translation result = plain text
```

y luego escapar.

---

# 18. `Formats`: diseño bueno

`Formats` sí está bastante bien.

Tiene:

```text
texts[]
formats[]
```

y ambos se copian defensivamente.

También tiene una semántica útil:

```java
format(index)
```

devuelve `%ct_messages%` cuando no existe formato explícito.

Esto permite que:

```yaml
messages:
  - "Hola"
```

y:

```yaml
messages:
  - "<green>%content%</green>"
```

coexistan con un fallback razonable.

---

# 19. `ChannelRegistry`: potente pero demasiado tolerante

La resolución:

```text
a.b.c
 ↓
a.b
 ↓
a
 ↓
chat
```

es una buena idea para herencia de formato.

Pero hay una consecuencia:

```text
channel inexistente
       ↓
chat
```

Esto puede ocultar errores de configuración.

Un typo:

```text
chat.gloabl
```

puede terminar procesándose como:

```text
chat
```

en vez de provocar una resolución fallida.

Para un editor y validator tan sofisticados, sería mejor distinguir:

```text
resolve()
resolveRequired()
resolveOrFallback()
```

---

# 20. Configuración: buen loader, pero demasiada tolerancia

`ConfigLoader` es tolerante:

* YAML inválido → defaults;
* canal corrupto → se omite;
* campos desconocidos → se ignoran;
* canal faltante → registry vacío.

Esto es bueno para robustez operacional.

Pero combinado con:

```text
ConfigValidator
+
Web Editor
+
Schema
```

debería existir una distinción mucho más clara entre:

```text
parse failure
validation failure
runtime fallback
```

Actualmente pueden ocurrir cosas como:

```text
config incorrecta
    ↓
ConfigLoader la convierte silenciosamente en defaults
    ↓
Validator recibe defaults
    ↓
aparentemente todo funciona
```

Eso puede esconder errores reales.

---

# 21. ConfigValidator: implementación real, wrapper roto

El validator del `host` sí es sustancial.

Valida:

* config;
* channels;
* rules;
* translators;
* sync;
* tipos;
* rate limits;
* nodos;
* edges;
* etc.

Pero el wrapper de Spigot es:

```java
public final class ConfigValidator {

    public static void validate(...) {
        ConfigValidator.validate(...);
    }
}
```

mientras importa:

```java
import me.majhrs16.suite.host.config.ConfigValidator;
```

Es decir, el nombre local colisiona con el import y la llamada no está cualificada.

Además, conceptualmente la llamada se resuelve contra el propio `ConfigValidator`.

Esto es otro ejemplo de código nuevo que no fue compilado/consolidado correctamente.

---

# 22. `MessageEvent`: feature declarada pero no terminada

El documento declara:

> `MessageEventBus` público.

Pero en `core-api` lo que existe es `MessageEvent`.

No aparece un bus funcional equivalente.

Peor todavía: `MessageEvent` usa:

```java
Objects.requireNonNull(...)
```

sin el import correspondiente.

Y `cancelled` es:

```java
private final boolean cancelled;
```

pero después existe:

```java
setCancelled(boolean cancelled)
```

La variable es final.

Por tanto, la clase contiene otra contradicción interna directa.

### Conclusión

F6-3 está documentado como implementado, pero el contrato público todavía no está completo.

---

# 23. Spigot host: la composición es conceptualmente correcta

El plugin sí actúa como composition root:

```text
Spigot
  ↓
SuiteHost
  ↓
MessageDispatcher
  ↓
ActorDirectory
  ↓
ChatDelivery
```

y `SpigotChatDelivery` correctamente evita tocar Bukkit desde el hilo async:

```java
if (Bukkit.isPrimaryThread())
    task.run();
else
    scheduler.runTask(...)
```

Esto es correcto y particularmente importante para `AsyncPlayerChatEvent`.

---

# 24. Pero el plugin principal presenta varios síntomas de integración incompleta

El archivo `TextFormatterSuitePlugin` incorpora simultáneamente:

* commands;
* extensions;
* module manager;
* WebSocket;
* observability;
* inworld;
* Discord;
* language store;
* dispatcher.

Eso convierte al plugin en un enorme composition root.

Conceptualmente es correcto que sea el punto de composición.

Pero el código muestra integración apresurada.

Ejemplos:

### `toggle`

En una rama:

```java
if (args.length == 0) ...
else {
    ...
    value = LangSetting.normalize(args[1]);
}
```

Con un único argumento puede accederse a `args[1]`.

### Lambdas

`languages` es reasignada:

```java
UserLanguageStore languages = languageStore;

if (languages == null) {
    languages = ...
}
```

y después se captura en una lambda de `ExtensionManager`.

Eso impide que la variable sea efectivamente final.

### Clases no importadas

El archivo utiliza componentes de subpaquetes como `DynamicCommandRegistrar`, `WebSocketSyncSink` y `ModuleDescriptor` sin que aparezcan correctamente en el bloque de imports mostrado.

El archivo, por tanto, presenta más incompatibilidades de integración.

---

# 25. Observabilidad: funcionalidad interesante, pero peligrosa

El `DebugEndpoint` escucha en:

```java
new InetSocketAddress(9091)
```

que por defecto representa binding sobre la interfaz comodín.

Y expone:

```text
/debug/simulate
/debug/dump
/debug/state
/debug/channels
/debug/rules
/debug/sinks
```

sin autenticación.

Lo más grave no es que `/debug/dump` exponga información.

Es:

```text
POST /debug/simulate
        ↓
crea Message
        ↓
dispatcher.dispatch(message)
        ↓
ChatDelivery
        ↓
jugadores reales
```

Es decir, el endpoint puede convertirse en una **fuente remota de mensajes del servidor**.

Además:

```java
Access-Control-Allow-Origin: *
```

está habilitado.

Esto no debería estar expuesto sin autenticación.

---

# 26. Observabilidad también contiene funcionalidades falsas

`/debug/state` tiene:

```java
System.currentTimeMillis() - System.currentTimeMillis()
```

como uptime.

Resultado:

```text
0
```

prácticamente siempre.

Y:

```text
/debug/rules
/debug/sinks
```

devuelven explícitamente:

```text
not yet implemented
```

Por tanto, la feature existe como endpoint, pero no como funcionalidad completa.

---

# 27. Otro bug de observabilidad

El `DebugEndpoint` llama dos veces a:

```java
server.setExecutor(...)
```

Primero en el constructor y luego otra vez.

Es menor, pero refuerza el patrón:

> funcionalidades añadidas incrementalmente sin consolidación final.

---

# 28. Metrics endpoint

`MetricsEndpoint` sí tiene una estructura razonable:

```text
/metrics
/health
```

con scheduler para JVM metrics.

Pero `/health` siempre responde:

```json
{
  "status": "UP"
}
```

sin evaluar realmente el estado global.

Y tampoco hay autenticación.

Para Prometheus puede ser aceptable que `/metrics` sea público dentro de una red controlada, pero no debería asumirse como seguro por defecto.

---

# 29. Module Manager: aquí está probablemente el peor estado del proyecto

F12 es declarada como:

> GitHub releases downloader + version resolver + dependency relocation + isolated ClassLoader + SHA256 verification.

Pero el repositorio actualmente tiene:

```text
GitHub Releases = []
```

No existe ninguna release publicada.

Por tanto, el manager no puede hacer su operación principal contra el repositorio real.

---

# 30. El version resolver del manager es un stub

El código contiene:

```java
if (spec.startsWith("[") || spec.contains(",")) {
    // Range matching would go here
    return true;
}
```

Es decir:

> **las version ranges no están implementadas.**

Cualquier rango de esa forma devuelve `true`.

Además:

```java
isCompatibleWithEnv(...)
```

devuelve:

```java
return true;
```

independientemente del entorno.

Por tanto:

```text
version resolver
```

y:

```text
environment compatibility
```

son todavía placeholders.

---

# 31. Dependencias dinámicas tampoco están implementadas

`parseDependencies()` devuelve:

```java
return List.of();
```

Así que el manager declara soporte para resolver dependencias, pero actualmente no obtiene dependencias del release metadata.

---

# 32. SHA256 tampoco está realmente conectado

El código busca el asset principal:

```java
mainAsset
```

y luego intenta determinar si el propio nombre termina en:

```text
.sha256
```

Pero está buscando el JAR como asset principal.

Por tanto, normalmente:

```text
sha256 = null
```

y:

```java
verifyChecksum(...)
```

termina permitiendo:

```text
expectedSha256 == null
```

sin verificación.

La existencia de la función SHA256 no significa que el pipeline real esté protegido.

---

# 33. Relocation: problema crítico

El manager tiene dos métodos con la misma firma:

```java
relocate(Path, Path, Map)
```

uno público y otro privado.

El segundo incluso hace:

```java
return relocate(moduleJar, outputDir, relocations);
```

con exactamente la misma firma.

Eso es un error estructural directo.

Además el supuesto `ModuleClassLoader` extiende:

```java
ClassLoader
```

pero intenta construirlo mediante:

```java
super(urls, parent)
```

La API correspondiente para URLs es `URLClassLoader`, no `ClassLoader`.

Por tanto esta parte tampoco puede considerarse implementación funcional.

---

# 34. `register()` tampoco completa el ciclo

El manager hace:

```java
Class<?> moduleClass =
    classLoader.loadClass(descriptor.coordinate().artifact() + "Module");
```

Esto supone una convención de nombre extremadamente frágil.

Para un artefacto como:

```text
suite-gtranslate
```

se intentaría construir:

```text
suite-gtranslateModule
```

que no corresponde naturalmente con:

```text
GTranslateModule
```

Además:

```java
// This would integrate with SuiteHost/ModuleLoader
```

aparece literalmente en el código.

Eso significa que la integración que F12 declara todavía no está realmente realizada.

---

# 35. El classloader tampoco queda registrado

Existe:

```java
private final ConcurrentMap<String, ClassLoader> moduleClassLoaders
```

pero `register()` no almacena el classloader ahí.

Entonces:

```text
load()
 ↓
register()
 ↓
moduleClassLoaders.put(...)
```

no ocurre.

Después:

```text
unload()
 ↓
moduleClassLoaders.remove(...)
```

puede devolver:

```text
null
```

y no descargar nada.

Esto rompe el supuesto:

```text
attach/detach
```

de F12.

---

# 36. El manager es, en este momento, más un prototipo que un runtime manager

La clasificación correcta sería:

```text
API                    → bastante definida
Arquitectura           → interesante
Downloader             → parcialmente implementado
Version resolver       → incompleto
Dependency resolver    → stub
Compatibility          → stub
Checksum               → parcialmente conectado
Relocation              → roto
ClassLoader             → roto
Registration            → incompleto
Unload                  → incompleto
GitHub releases         → inexistentes
```

F12 no debería marcarse como 100%.

---

# 37. Web-editor

Aquí el panorama es bastante mejor.

El editor usa:

```text
StateStore
model
validate
paths
i18n
rendering
integration harnesses
```

y `package.json` define:

```text
format:check
lint
test
test:integration
```

Los tests unitarios están separados en:

```text
stateStore.test.cjs
model.test.cjs
validate.test.cjs
```

Esta parte tiene una estructura de testing mucho más coherente que varias de las últimas piezas Java.

---

# 38. El gran problema del Web Editor es el contrato compartido

La idea:

```text
schema
   ↓
web editor
   ↓
YAML
   ↓
Java ConfigLoader
```

es excelente.

Pero el proyecto todavía tiene varias representaciones:

```text
paths.json
js/paths.js
js/model.js
ConfigLoader.ConfigPath
schema-v2.2.md
```

El propio `PLAN.md` reconoce esta deuda.

Eso contradice parcialmente la afirmación:

> “single source of truth”.

Actualmente existe más bien:

> **single conceptual schema con múltiples copias/materializaciones.**

---

# 39. Documentación: aquí hay una regresión enorme

El commit más reciente declara:

> “FASE 16 completada - Documentación final completa”.

Pero `README.md` todavía describe el estado de septiembre 2.

Por ejemplo afirma:

```text
fabric-host pendiente
sync-velocity no existe
observability pendiente
extensions pendiente
runtime manager pendiente
WebSocket pendiente
presets pendiente
in-world pendiente
```

mientras todos esos componentes aparecen después en Git.

Eso hace que el README sea actualmente una fuente de información incorrecta.

---

# 40. README también tiene una cifra incorrecta

`settings.gradle` contiene **29 módulos Gradle**.

El README habla de:

```text
22 independent Gradle modules
```

Incluso sin entrar en si algunos son “support modules”, la cifra declarada no corresponde al árbol actual.

---

# 41. Release Notes también tienen problemas

`11-Release-Notes.md` contiene varias secciones repetidas:

```text
FASE 13
FASE 14
FASE 12
FASE 11
FASE 10
...
```

aparecen varias veces.

Además declara:

```text
Fabric host ... Tested on Fabric 1.21
```

mientras `PROMPT_NOW.md` mantiene:

```text
Test en servidor Fabric real → pendiente
```

y:

```text
F4-6 ... pendiente Java 21
```

Hay por tanto contradicciones documentales reales.

---

# 42. `PROMPT_NOW.md` tampoco representa el HEAD

El documento declara:

```text
F16-4 documentación final → ✅
```

pero al final todavía dice:

```text
FASE 16 (en progreso)
→ Tests E2E en curso
→ luego optimización
→ luego documentación
```

Ese archivo quedó congelado en una fase anterior.

---

# 43. Historial de auditorías: algo importante

El proyecto no está ignorando la calidad.

De hecho ya había producido investigaciones separadas:

```text
A1 parity ChatTranslator
A2 hexagonal architecture
A3 clean architecture
A4 bugs/vulnerabilities
A5 supply chain
```

y el propio PLAN registra resultados como:

```text
A2 → 7/8 reglas OK
A3 → ~85%
A4 → 30 hallazgos
A5 → riesgo alto
```

Esto es positivo.

El problema es que varias de esas conclusiones fueron obtenidas antes de F11–F16.

Es decir:

> **las últimas fases necesitan su propia auditoría post-integración.**

Y eso es exactamente lo que está revelando esta revisión.

---

# 44. Supply chain: el riesgo declarado por A5 sigue siendo importante

La auditoría histórica ya había marcado:

```text
sin lockfile
sin SHA256
sin allowlist
mavenLocal precedence
8 repos
reproducible builds NO
```

Desde entonces se añadió el Module Manager con supuesta verificación SHA256, pero la implementación actual no conecta esa garantía correctamente.

Por tanto, F12 no ha eliminado realmente el problema de supply chain.

---

# 45. Un detalle particularmente interesante: producción vs HEAD

El repositorio tiene evidencia de que **una versión anterior sí fue probada en Paper 1.20.6 real**.

El historial registra explícitamente:

```text
Plugin TextFormatterSuite probado en servidor Paper 1.20.6 real
```

Esto es importante.

No estoy concluyendo:

> “todo el proyecto siempre fue un prototipo”.

No.

La base anterior tenía una implementación funcional real.

El problema es que después se añadieron muchas capas nuevas y el HEAD actual ya no tiene la misma garantía de coherencia.

---

# 46. Patrón global encontrado

La mayoría de los problemas nuevos siguen el mismo patrón:

```text
Fase N
  ↓
añadir API
  ↓
añadir implementación
  ↓
añadir documentación
  ↓
marcar ✅
  ↓
siguiente fase
```

pero falta una etapa:

```text
                 ↓
         integración global
                 ↓
        compilación completa
                 ↓
      tests de todo el monorepo
                 ↓
       revisión de contratos
                 ↓
            consolidación
```

Y eso se nota especialmente en:

* `Message`;
* `ScriptSurface`;
* `TransformOp`;
* `MessageEvent`;
* `MessageDispatcher`;
* `ConfigValidator`;
* `TextFormatterSuitePlugin`;
* `DefaultModuleLifecycle`.

---

# 47. El problema principal NO es la arquitectura

Esto merece destacarse.

La arquitectura conceptual está por encima de lo habitual para un plugin Minecraft.

Especialmente:

### Muy buenos conceptos

* `core-api` como contrato;
* `Message` atómico;
* `Direction` como intención de routing;
* `Actor` platform-neutral;
* `Formats`;
* `ChannelRegistry`;
* `SuiteHost`;
* `MessageDispatcher`;
* ports/adapters;
* `ChatDelivery`;
* ServiceLoader;
* ModuleGraph;
* separación Spigot/Fabric;
* iFlow como firewall;
* MiniMessage;
* editor schema-driven.

No veo como problema que el proyecto haya intentado esto.

El problema es **la consolidación del código**.

---

# 48. Evaluación por componente

| Área                |                                                    Estado auditado |
| ------------------- | -----------------------------------------------------------------: |
| `core-api`          |                                             🟢 Arquitectura fuerte |
| `Message`           |                 🟢 Buen diseño, pero incompatible con código nuevo |
| `Direction`         |                           🟢 Buen concepto, integración incompleta |
| `Formats`           |                                                          🟢 Sólido |
| `Channel`           |                                                          🟢 Sólido |
| `ChannelRegistry`   |                            🟢 Bueno, fallback demasiado silencioso |
| `kernel`            |                                 🟢 Buena implementación conceptual |
| `ModuleGraph`       |                                                 🟢 Bastante sólido |
| `SuiteHost`         |                                               🟢 Buena composición |
| `MessageDispatcher` |                                            🟠 Contratos desfasados |
| `textformatter`     |               🟢 Arquitectura buena / algunos problemas semánticos |
| `iFlow`             |                          🟠 Potente pero actualmente inconsistente |
| `RateLimiter`       |                          🔴 Bug de capacidad + scheduler duplicado |
| `SpEL`              |                                    🔴 Debe auditarse profundamente |
| `ConfigLoader`      |                            🟢/🟠 Robusto pero demasiado silencioso |
| `ConfigValidator`   |                           🟠 Implementación real, integración rota |
| Spigot              |              🟠 Base funcional, HEAD con integración inconsistente |
| Fabric              |            🟠 Implementado en código, validación real insuficiente |
| Discord             |                      🟠 Funcionalidad interesante, bypass de iFlow |
| Observability       |                              🔴 Debug endpoint inseguro/incompleto |
| Extensions          |                🟠 API interesante, integración necesita validación |
| Module Manager      |                                                🔴 No release-ready |
| WebSocket           |               🟠 Implementación declarada, necesita validación E2E |
| Presets             |                 🟠 Implementación reciente, necesita consolidación |
| In-world            |                🟠 Implementación reciente, necesita validación E2E |
| Web Editor          |                                 🟢 Es de las partes más coherentes |
| Tests               | 🟠 Muchos tests declarados, pero no garantizan coherencia del HEAD |
| Docs                |                                     🔴 Fuertemente desincronizadas |

---

# 49. Severidades reales

## 🔴 Críticas

### C1 — Contrato `Message` roto

`ScriptSurface`/`TransformOp` requieren una API mutable que `Message` no posee.

### C2 — `MessageEvent` roto

API incompleta y estado `cancelled` final con setter.

### C3 — Module Manager no funcional

Relocation/ClassLoader/register/unload contienen errores estructurales.

### C4 — Debug endpoint sin autenticación

Puede inyectar mensajes reales mediante `/debug/simulate`.

### C5 — HEAD no puede considerarse compilación integralmente validada

Hay múltiples incompatibilidades estáticas evidentes entre clases.

---

## 🟠 Altas

### H1 — RateLimiter limitado a 1

`DefaultRouter` usa `new RateLimiter(1)` independientemente del canal.

### H2 — Discord mirror puede saltarse DROP

El mirror no depende del resultado efectivo del dispatcher.

### H3 — Detección de idioma multiplicada por receptor

Puede convertir una operación en:

```text
O(recipients)
```

llamadas al proveedor.

### H4 — Configuración/documentación desincronizada

README, PLAN, PROMPT_NOW y Release Notes describen estados diferentes.

### H5 — Channel y Direction tienen dos fuentes de canal

`message.channel()` vs `direction.channel()`.

---

## 🟡 Medias

* doble scheduler en RateLimiter;
* uptime fake;
* endpoints rules/sinks todavía stub;
* YAML tolerante en exceso;
* cache de idiomas que no elimina entradas borradas;
* traducción no escapada tras `<tr>`;
* fallback silencioso a `chat`;
* múltiples copias del schema;
* tests declarados pero sin CI global;
* WebSocket/Velocity/Manager sin pruebas reales del entorno correspondiente.

---

# 50. El mayor problema documental

Actualmente el repositorio transmite:

```text
"F16 completa"
```

pero el código transmite:

```text
"F16 + varias APIs experimentales parcialmente integradas"
```

La documentación debería reflejar esto.

No es necesario rebajar la ambición del proyecto.

Simplemente hay que diferenciar:

```text
implemented
```

de:

```text
integrated
```

y:

```text
production-verified
```

Esas tres categorías son distintas.

---

# 51. Estado que considero correcto para el proyecto

Yo clasificaría el HEAD así:

```text
TextFormatter Suite
│
├── Architecture          █████████░ 90%
├── Core model            █████████░ 90%
├── Spigot foundation     ████████░░ 80%
├── Web editor            █████████░ 90%
├── iFlow                 ███████░░░ 70%
├── Translation           ████████░░ 80%
├── Sync                  ███████░░░ 70%
├── Fabric                ██████░░░░ 60%
├── Extensions            ██████░░░░ 60%
├── Observability         █████░░░░░ 50%
├── Module Manager        ███░░░░░░░ 30%
├── In-world              █████░░░░░ 50%
├── Performance           ███████░░░ 70%
├── Tests                 ███████░░░ 70%
└── Documentation         █████░░░░░ 50%
```

No son porcentajes de LOC.

Son una estimación de **coherencia funcional verificable**.

---

# 52. Lo más importante: no rehacer la arquitectura

No recomiendo tirar abajo el diseño.

Sería un error.

La dirección correcta es:

```text
                CURRENT
                   │
                   ▼
       ┌─────────────────────┐
       │ Contract cleanup    │
       └──────────┬──────────┘
                  ▼
       ┌─────────────────────┐
       │ Compile entire repo │
       └──────────┬──────────┘
                  ▼
       ┌─────────────────────┐
       │ E2E pipeline        │
       └──────────┬──────────┘
                  ▼
       ┌─────────────────────┐
       │ Security audit      │
       └──────────┬──────────┘
                  ▼
       ┌─────────────────────┐
       │ Docs synchronization│
       └──────────┬──────────┘
                  ▼
             RELEASE
```

---

# 53. Prioridad de reparación

## P0 — Antes de cualquier feature nueva

### 1. Restaurar un único contrato `Message`

Decidir definitivamente:

```text
Message immutable
```

y convertir todas las mutaciones a:

```text
builder / transformed copy
```

### 2. Arreglar `MessageEvent`

Implementar realmente:

```text
MessageEventBus
```

o retirar temporalmente la afirmación de que existe.

### 3. Arreglar `TransformOp`

Que opere sobre una representación mutable interna o produzca un nuevo `Message`.

### 4. Arreglar `MessageDispatcher`

Eliminar `.from(message)` inexistente y resolver correctamente el canal.

### 5. Compilación global

No:

```bash
cd module
./gradlew
```

uno por uno.

Sino un gate que garantice:

```text
todos los módulos
↓
todos los tests
↓
Spigot host
↓
Fabric host
```

---

# 54. P1 — Seguridad

Especialmente:

```text
SpEL
YAML
/debug/*
WebSocket
runtime downloader
classloader
sync inbound
```

El debug endpoint debe pasar de:

```text
0.0.0.0:9091 + no auth
```

a un diseño explícitamente seguro.

Y el Module Manager no debería instalar JARs remotos hasta que:

```text
artifact
SHA256
manifest
version
compatibility
dependency graph
signature/trust
```

estén realmente verificados.

---

# 55. P1 — Corregir el pipeline de traducción

Mover:

```text
detect()
```

fuera del ciclo por receptor.

Ideal:

```text
incoming Message
      ↓
resolve source once
      ↓
routing
      ↓
for recipient:
    resolve target
    translate
    render
    deliver
```

---

# 56. P1 — iFlow como autoridad única

Todo flujo externo debería respetar:

```text
Message
 ↓
event bus
 ↓
iFlow
 ↓
effective decision
 ↓
render
 ↓
delivery/sync
```

Discord no debería tener un camino:

```text
dispatcher
+
mirror directo
```

que pueda saltarse la decisión.

---

# 57. P2 — Documentación

La documentación debería reconstruirse desde el código actual.

No al revés.

En particular:

```text
README
PLAN
PROMPT_NOW
Release Notes
Wiki
ADR
schema
```

deben describir un mismo estado.

Y separar:

```text
Implemented
Integrated
Tested
Production verified
```

---

# 58. Conclusión final

La auditoría cambia bastante mi valoración anterior del proyecto.

**TextFormatter Suite sí tiene una arquitectura seria.**

No es simplemente un ChatTranslator reescrito con nombres nuevos.

El modelo:

```text
Message
Direction
Actor
Channel
iFlow
TextFormatter
TranslationService
SPI
Host
Adapter
```

forma una arquitectura bastante potente.

La decisión de hacer `Module` un descriptor y no un servicio instanciable por `SuiteBootstrap` es correcta. El kernel también tiene una implementación real de resolución de dependencias y SCC. El Web Editor tiene una estructura de testing considerablemente madura. El composition root de Spigot está correctamente orientado hacia ports/adapters.

Pero el HEAD actual tiene un problema muy concreto:

> **Las últimas fases fueron desarrolladas como capas incrementales y varias no han pasado todavía por una consolidación global del contrato.**

Eso produjo código que literalmente habla APIs diferentes.

Los ejemplos más contundentes son:

```text
Message.java
      ↕
ScriptSurface.java

Message.java
      ↕
TransformOp.java

MessageEvent.java
      ↕
MessageEventBus inexistente

MessageDispatcher.java
      ↕
Message.Builder.from inexistente

spigot ConfigValidator
      ↕
host ConfigValidator

Module Manager
      ↕
ClassLoader / relocation / releases
```

Y además el repositorio actualmente **no tiene GitHub Releases**, pese a que F12 construye su modelo alrededor de ellas.

Por eso mi veredicto sobre el HEAD es:

## **TextFormatter Suite: arquitectónicamente fuerte, funcionalmente avanzada, pero actualmente NO release-ready.**

La buena noticia es que esto no parece requerir una reingeniería.

Requiere algo mucho más concreto:

> **una F17 de consolidación.**

No otra feature.

No otro módulo.

No otro sistema.

Una fase dedicada exclusivamente a hacer que **todo lo que ya existe realmente sea un único sistema coherente**.

Y, honestamente, después de ver el estado del proyecto completo, esa probablemente sería la fase que más valor le puede aportar ahora mismo.

La parte que más me llamó la atención es que **los problemas no están principalmente en las ideas originales de TxF**, sino en las funcionalidades añadidas durante F11–F16. La base `core-api → kernel → host → dispatcher → iFlow → formatter → adapter` es bastante defendible; lo que necesita ahora es una consolidación brutal de contratos y una compilación/integración global.

No pude ejecutar aquí el Gradle completo porque este entorno no tiene acceso de red al repositorio/dependencias; por eso no voy a fingir un `./gradlew build` que no ejecuté. Los errores que marqué como críticos, sin embargo, son incompatibilidades estáticas visibles directamente en el código actual, no especulación.
