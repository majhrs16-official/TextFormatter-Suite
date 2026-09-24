# Auditoría técnica integral — TextFormatter Suite

**Fecha de auditoría:** 24 de septiembre de 2026, ~10:10 UTC
**Commit auditado:** `44d3a30992a78b98bf36d9f67c8e4d54f01cd401`
**Rama:** `main`
**Repositorio:** `majhrs16-official/TextFormatter-Suite`

Tomé como especificación de auditoría el prompt adjunto, que exige reconstruir la arquitectura real, separar hechos/inferencias/hipótesis, evitar falsos positivos y evaluar específicamente Clean/Hexagonal, seguridad, rendimiento, concurrencia, modularidad y escala network-level. 

> **Resultado ejecutivo:** el proyecto tiene una arquitectura conceptual bastante ambiciosa y varias decisiones realmente buenas, pero el `HEAD` actual contiene al menos **errores de compilación demostrables**, además de varios defectos de seguridad/concurrencia que las auditorías anteriores declaraban resueltos. El salto arquitectónico es real; la madurez de producción, en cambio, está por detrás de la arquitectura.

---

# 1. Cobertura de auditoría

Inspeccioné:

* árbol Git completo del commit actual;
* `settings.gradle`, `build.gradle`, workflows CI/release;
* API/core;
* kernel y resolución de módulos;
* host/bootstrap/dispatcher;
* iFlow/router/rate limiter;
* textformatter/template/scripting;
* traducción Google/LibreTranslate;
* transport HTTP;
* sync HTTP/TCP/UDP/Discord/Telegram/WebSocket;
* Spigot/Fabric host;
* Module Manager;
* observabilidad;
* performance/loadtest;
* web editor y sus tests;
* configuración/defaults;
* documentación, PLAN, ADR, README y auditorías históricas;
* historial reciente de Git.

El árbol actual contiene **382 archivos relevantes** excluyendo `node_modules` y archivos no pertinentes para el análisis estático. Además, detecté que `node_modules` está realmente presente dentro del árbol Git actual, algo que considero un problema independiente.

No he ejecutado el proyecto desde este entorno: el clon directo de GitHub no es posible desde el runtime. Por ello, cualquier resultado de ejecución/benchmark que no esté respaldado por CI o documentación existente se marca como no demostrado.

---

# 2. El hallazgo más importante: el HEAD actual no parece compilar completamente

Esto cambia bastante la evaluación.

## BUG-01 — `PerformanceProfiler` contiene errores de compilación

**Tipo:** Bug
**Severidad:** Crítica
**Confianza:** **Confirmado**
**Ubicación:**
`src/performance/src/main/java/me/majhrs16/suite/performance/profiling/PerformanceProfiler.java`

La clase declara:

```java
record MethodProfile(
    String methodName,
    long totalTimeNanos,
    long callCount,
    long minTimeNanos,
    long maxTimeNanos,
    double avgTimeNanos
) {}
```

pero después hace:

```java
return new MethodProfile(methodName, durationNanos);
```

y:

```java
existing.addSample(durationNanos);
```

El record no tiene ese constructor de dos argumentos ni un método `addSample()`.

Eso no es una cuestión arquitectónica: **es código Java que no puede compilar tal como está**.

Y hay más.

`MemorySnapshot` declara:

```java
record MemorySnapshot(
    long heapUsed,
    long heapMax,
    long nonHeapUsed,
    long nonHeapMax,
    long poolCount,
    String[] poolNames
) {}
```

pero `takeMemorySnapshot()` intenta construirlo con:

```java
new MemorySnapshot(
    heapUsed,
    heapCommitted,
    heapMax,
    nonHeapUsed,
    getBufferPoolUsage(),
    getGcStats()
);
```

Los tipos de los dos últimos argumentos no coinciden con `long` y `String[]`.

Además, `ProfilingReport` tiene **10 campos**, mientras `stopProfiling()` intenta crear uno con **9 argumentos**.

Por tanto hay al menos **tres familias independientes de errores de compilación en la misma clase**.

### Impacto

El workflow actual pretende construir `:src:performance:build`, por lo que esto debería bloquear un build completo si esa ruta se ejecuta realmente. El commit actual no tiene resultados de GitHub Actions asociados; los checks/status consultados están vacíos.

### Diagnóstico

Esto parece una regresión provocada por la evolución rápida del módulo de profiling: la estructura se convirtió de una implementación mutable a records, pero quedaron consumidores del diseño anterior.

**Prioridad: P0.**

---

# 3. Arquitectura real

La arquitectura actual es considerablemente más seria que una simple separación por paquetes.

El flujo conceptual es:

```text
                    ┌─────────────────────────┐
                    │ Spigot / Fabric / etc. │
                    └────────────┬────────────┘
                                 │
                                 ▼
                         Platform Adapter
                                 │
                                 ▼
                         MessageDispatcher
                                 │
                  ┌──────────────┴──────────────┐
                  ▼                             ▼
             ActorDirectory                 SuiteHost
                                                  │
                          ┌───────────────────────┼─────────────────────┐
                          ▼                       ▼                     ▼
                       iFlow                 TextFormatter         Translation
                          │                       │                     │
                          ▼                       ▼                     ▼
                      Routing                Templates             Provider
                          │                       │
                          └──────────────┬────────┘
                                         ▼
                                  ChatDelivery
                                         │
                                         ▼
                                  Spigot / Fabric
```

Y paralelamente:

```text
Module JAR
   │
   ▼
ServiceLoader
   │
   ▼
Module descriptor
   │
   ▼
ModuleGraph
   │
   ├── contract compatibility
   ├── JVM compatibility
   ├── capability requirements
   └── dependency graph
```

Eso es una separación real.

La decisión de que `Module` sea únicamente un descriptor SPI y **no un servicio que `SuiteBootstrap` instancia/reflexivamente ejecuta** está correctamente implementada en el HEAD actual. `Module.java` lo declara explícitamente y `SuiteBootstrap` respeta esa semántica.

Esto es una de las mejores decisiones arquitectónicas del proyecto.

---

# 4. Arquitectura pretendida vs arquitectura real

| Área               | Pretendida              | Real                      |
| ------------------ | ----------------------- | ------------------------- |
| Core API           | JDK puro                | Sí                        |
| Modules            | SPI/descriptor          | Sí                        |
| Kernel             | Resolver dependencias   | Sí                        |
| Host               | Orquestación            | Sí                        |
| iFlow              | Routing/policy          | Sí                        |
| Formatter          | Renderización           | Sí                        |
| Translation        | Provider abstraction    | Parcial                   |
| Platform           | Adapter                 | Sí                        |
| Sync               | Ports/adapters          | Sí                        |
| Module Manager     | runtime dynamic loading | Bastante avanzado         |
| Web Editor         | configuración visual    | Sí                        |
| Clean Architecture | inversión estricta      | **No completamente**      |
| Hexagonal          | core aislado            | **Sí, con algunas fugas** |

La arquitectura **no es humo**. Hay inversión de dependencias real en varias zonas.

---

# 5. Clean Architecture

## Evaluación: **7.5/10**

El `core-api` está especialmente bien aislado.

La API central contiene:

* `Message`
* `Actor`
* `Direction`
* `Channel`
* `Language`
* `Module`
* SPI de traducción
* SPI de sync
* SPI de placeholders
* SPI de logging

sin depender directamente de Bukkit/Fabric.

Eso es exactamente el tipo de frontera que se desea.

### Pero existe una violación conocida y todavía presente

`TranslatorsConfig` importa directamente:

```java
GTranslate
LTranslate
HttpTransport
```

y crea:

```java
new GTranslate(new HttpTransport())
new LTranslate(...)
```

Eso significa que `host` conoce implementaciones concretas de infraestructura.

El propio diseño anterior ya había identificado esto como una violación:

```text
host → gtranslate
host → ltranslate
```

La solución adecuada continúa siendo algo como:

```text
host
  │
  ▼
TranslatorProvider SPI
  │
  ├── Google adapter
  └── Libre adapter
```

en lugar de:

```text
host
 ├── new GTranslate()
 └── new LTranslate()
```

### Conclusión

Clean Architecture está **bien encaminada**, pero no completamente aplicada.

---

# 6. Hexagonal Architecture

## Evaluación: **8/10**

Aquí el proyecto está mejor.

Especialmente buenos:

* `ChatDelivery`;
* `ActorDirectory`;
* `TranslationService`;
* `Translator`;
* `SyncSink`;
* `SyncListener`;
* `PlaceholderResolver`;
* separación platform/core;
* `ServiceLoader`.

Por ejemplo, `ChatDelivery` deliberadamente queda fuera de `core-api` porque necesita Adventure:

```text
core-api
    ↓
host port
    ↓
Spigot/Fabric adapter
```

Eso es una decisión correcta.

La contaminación principal aparece alrededor de algunos módulos de configuración y wiring, no en el corazón del modelo.

---

# 7. Core API

## Evaluación: **8.5/10**

`Message` está bastante bien diseñado.

La evolución reciente solucionó algo importante: las transformaciones ya no mutan destructivamente el mensaje compartido.

Ahora existe:

```java
withText()
withLangTarget()
withCancelled()
withChannel()
withTranslate()
withSoundsAdd()
withSoundsRemove()
```

y `Builder.from()`.

También se clonan arrays donde corresponde.

### Punto particularmente bueno

La arquitectura:

```text
Message
   +
Direction
   +
ActorDirectory
```

es bastante más flexible que codificar permanentemente:

```text
from → to
```

Esto permite:

```text
INITIATOR
OTHERS
ALL
WORLD
RADIUS
PERMISSION
SPECIFIC
CONSOLE
```

sin convertir `Message` en una clase específica de Minecraft.

---

# 8. ModuleGraph

Aquí encontré un bug interesante.

## BUG-02 — ciclo consigo mismo no se detecta

**Tipo:** Bug
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `kernel/ModuleGraph.java`

El comentario dice:

> “A singleton with a self-edge is a cycle.”

Pero `strongConnect()` contiene:

```java
if (other == module) {
    continue;
}
```

Por tanto, la arista:

```text
A → A
```

nunca se analiza.

Resultado:

```text
Module A
requires X
provides X
```

puede representar una dependencia autorreferencial que el comentario promete detectar, pero el algoritmo la excluye.

No afecta a ciclos normales `A → B → A`, que sí pasan por Tarjan, pero contradice el contrato documentado.

### Solución

No saltarse `other == module`; comprobar explícitamente:

```java
if (other == module) {
    if (latent(module, module, active, available)) {
        // self-cycle
    }
    continue;
}
```

---

# 9. ModuleGraph tampoco produce realmente un activation order

La documentación habla de:

> “activation order”

pero `ResolutionResult` conserva estados/resolución; no existe aquí un topological ordering explícito equivalente a:

```text
A
↓
B
↓
C
```

La resolución funciona como iteraciones sobre `pending`, por lo que existe un orden implícito de selección, pero no debería venderse como un orden topológico formal si no se expone como tal.

Esto lo clasifico como **defecto documental/contractual**, no como bug crítico.

---

# 10. iFlow

## Evaluación: **8/10**

La arquitectura de `DefaultRouter` es razonable:

```text
channel
   ↓
sender permission
   ↓
receiver permission
   ↓
rules
   ↓
rate limit
   ↓
delivery decision
```

Además:

```java
AtomicReference<List<Rule>>
```

y:

```java
rules.set(List.copyOf(ordered))
```

son buenas decisiones para permitir lecturas concurrentes sin lock durante el routing.

### Muy importante

El dispatcher ahora puede procesar recipients en paralelo.

Esto es necesario si se pretende crecer a servidores grandes.

---

# 11. RateLimiter

La implementación usa:

```text
ConcurrentHashMap
+
per-bucket synchronized
+
periodic purge
```

Es razonable para el volumen esperado.

Pero encontré un problema de concurrencia.

## BUG-03 — purge puede eliminar un bucket mientras está siendo usado

**Tipo:** Bug
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `iflow/channel/RateLimiter.java`

Secuencia posible:

```text
Thread A:
bucket = buckets.computeIfAbsent(key)

Thread B:
purgeIdle()
→ elimina bucket del map

Thread A:
usa el bucket antiguo

Thread C:
computeIfAbsent(key)
→ crea bucket nuevo
```

Durante un intervalo pueden coexistir dos buckets para la misma key.

Eso permite exceder el límite.

No es un escenario cotidiano, porque depende de la carrera con el purger, pero es una race real.

### Solución

El purge debe coordinarse con la obtención del bucket, o utilizar un diseño con timestamps/epoch que permita validar que el bucket sigue siendo el actualmente instalado antes de consumir.

---

# 12. MessageDispatcher

## Evaluación: **7.5/10**

La evolución aquí es buena.

Actualmente:

```text
Message
   ↓
resolve source language
   ↓
expand recipients
   ↓
bounded executor
   ↓
host.deliver()
   ↓
ChatDelivery
```

El pool:

```text
4 core
32 max
queue 1000
CallerRunsPolicy
```

es mucho más sensato que un executor ilimitado.

### Pero hay una consecuencia

`CallerRunsPolicy` significa que cuando el pool está saturado, el thread que llama a `dispatch()` ejecuta trabajo.

Eso evita pérdida silenciosa, pero introduce backpressure directamente sobre el productor.

Para un evento de chat async de Bukkit puede ser aceptable.

Para un productor que accidentalmente sea main-thread:

```text
MAIN THREAD
   ↓
dispatch()
   ↓
pool saturated
   ↓
CallerRunsPolicy
   ↓
host.deliver()
```

puede terminar haciendo procesamiento significativo en el thread principal.

No es necesariamente un bug; es una decisión de backpressure que debe documentarse y medirse.

---

# 13. Threading

La separación:

```text
routing/formatting
    ↓
worker
    ↓
SpigotChatDelivery
    ↓
main thread
```

es correcta conceptualmente.

`SpigotChatDelivery` detecta:

```java
Bukkit.isPrimaryThread()
```

y hace scheduler hop si es necesario.

Eso es una buena frontera.

El riesgo aparece en operaciones de I/O que siguen estando sincronizadas.

---

# 14. HTTP Sync

Aquí hay una inconsistencia importante.

La documentación/commits hablan de migración a `HttpURLConnection`, pero el `HttpSink` actual utiliza:

```java
java.net.http.HttpClient
```

directamente.

Mientras que existe además:

```text
transport/HttpTransport
```

Esto significa que hay **dos abstracciones HTTP diferentes** en el proyecto.

Eso contradice parcialmente la intención de centralizar transporte.

Más importante:

## BUG-04 — HTTP inbound acepta payloads sin límite

**Tipo:** Vulnerabilidad / DoS
**Severidad:** Alta
**Confianza:** Confirmado
**Ubicación:** `sync-http/HttpSink.java`

Hace:

```java
exchange.getRequestBody().readAllBytes()
```

sin un límite máximo de bytes.

Por tanto un cliente puede enviar un body arbitrariamente grande y obligar al servidor a materializarlo completamente en memoria.

Después:

```java
MessageCodec.fromJson(body)
```

lo procesa.

No hay:

* `Content-Length` máximo;
* límite de bytes leídos;
* rate limit;
* autenticación visible;
* límite por IP.

Para un endpoint expuesto, esto es un vector de DoS.

### Solución mínima

Rechazar:

```text
Content-Length > MAX
```

y además leer mediante un stream limitado, porque `Content-Length` puede faltar.

---

# 15. SSRF en HttpTransport

`HttpTransport` intenta protegerse contra SSRF, lo cual es bueno.

Comprueba:

* loopback;
* RFC1918;
* link-local;
* CGNAT;
* multicast;
* reserved.

Pero mantiene:

```java
conn.setInstanceFollowRedirects(true);
```

## BUG-05 — SSRF mediante redirect / DNS rebinding

**Tipo:** Vulnerabilidad
**Severidad:** Alta
**Confianza:** Probable/arquitectónicamente demostrable
**Ubicación:** `transport/HttpTransport.java`

La validación ocurre sobre la URL inicial.

Después el cliente puede seguir:

```text
https://trusted.example
       ↓ 302
http://127.0.0.1:8080
```

sin que el mismo control necesariamente se vuelva a aplicar.

Además, existe una ventana clásica:

```text
DNS validation
      ↓
IP A permitida
      ↓
DNS cambia
      ↓
connection → IP B privada
```

### Solución

Desactivar redirects automáticos y validar **cada hop**, idealmente resolviendo y conectando de forma controlada.

---

# 16. WebSocket

El módulo tiene:

* subscriptions;
* authentication;
* endpoints;
* broadcast;
* inbound messages;
* lifecycle.

Pero existen riesgos importantes.

## BUG-06 — WebSocket sin límite de tamaño/rate para mensajes entrantes

**Tipo:** Vulnerabilidad / DoS
**Severidad:** Alta
**Confianza:** Probable
**Ubicación:** `sync-websocket/WebSocketSyncSink.java`

El código recibe:

```java
String message
```

y después:

```java
JsonParser.parseString(message)
```

sin límite visible de tamaño.

Además:

```text
client
  ↓
message
  ↓
MessageCodec
  ↓
listener
  ↓
dispatcher
```

no tiene rate limiter por conexión.

Un cliente autenticado puede generar bursts muy grandes.

### Otro punto

Si `authToken` está vacío, el servidor acepta conexiones sin autenticación.

Eso puede ser intencional para instalaciones locales, pero para un servidor escuchando externamente es una configuración peligrosa.

---

# 17. Telegram / Discord

El tratamiento de tokens mejoró.

Por ejemplo Discord ahora conserva el token como:

```java
char[]
```

y lo limpia:

```java
Arrays.fill(token, '\0');
```

Eso es mejor que un `String` permanente.

Pero hay una limitación inevitable:

```java
String tokenStr = new String(token);
```

para pasarlo a JDA.

Por tanto el secreto sigue existiendo temporalmente como `String`.

No es una vulnerabilidad completa; simplemente no puede considerarse "el secreto nunca aparece en String".

---

# 18. Traducción

## Evaluación: **7.5/10**

Google y LibreTranslate están correctamente detrás de:

```java
Translator
```

y utilizan `Transport`.

Puntos buenos:

* fallback;
* detección;
* normalización de dialectos;
* ausencia de dependencia directa de proveedores en el core;
* tokens de LibreTranslate tratados mejor.

### Principal defecto

`TranslatorsConfig` continúa construyendo adapters concretos.

Esto mantiene una dependencia:

```text
host
 └── gtranslate
 └── ltranslate
```

que debería desaparecer si se busca Clean Architecture estricta.

---

# 19. Template engine

## Evaluación: **8/10**

La pipeline está bien estructurada:

```text
built-ins
   ↓
SpEL
   ↓
external placeholders
   ↓
content
   ↓
<tr> translation spans
   ↓
MiniMessage
```

Especialmente buena es la separación entre:

```text
dynamic user data
```

y:

```text
template markup
```

mediante `MiniEscape`.

La implementación actual escapa:

```text
< > \ { } [ ] ( ) # @
```

por lo que el hallazgo anterior de escape incompleto parece haber sido corregido.

---

# 20. SpEL

La situación actual es muy diferente de la auditoría antigua.

Ahora se utiliza:

```java
SimpleEvaluationContext.forReadOnlyDataBinding()
```

en lugar de un `StandardEvaluationContext` abierto.

Eso elimina las capacidades más peligrosas:

* `T(...)`;
* creación de objetos;
* acceso estático;
* escritura;
* invocación arbitraria.

Por tanto **no considero ya demostrado el antiguo RCE vía SpEL**.

Eso es una corrección real.

---

# 21. Web Editor

## Evaluación: **8/10**

El editor tiene una arquitectura bastante más elaborada de lo habitual para una herramienta de configuración:

```text
StateStore
    ↓
Model
    ↓
Validation
    ↓
YAML
    ↓
Import/Export
    ↓
Canvas / Preview
```

Tiene:

* undo/redo;
* snapshots;
* validators;
* revision counter;
* integración tests;
* round-trip;
* graph editing;
* persistencia local;
* protección básica contra `__proto__`.

Eso está bien hecho.

### Pero hay un bug de robustez

En `StateStore.mutate()`:

```javascript
working = state;
try {
    fn();
} finally {
    working = prevWorking;
}
```

Si `fn()` lanza una excepción, el estado puede haber sido modificado parcialmente.

No existe:

```text
catch
 → state = snapshotBefore
```

para ese caso.

El rollback solamente ocurre cuando falla el **post-validator**.

Por tanto:

```text
mutation
  ↓
partial mutation
  ↓
exception
  ↓
state remains partially mutated
```

Esto es un defecto real.

---

# 22. Web Editor: dependencia de schema

Otro punto interesante:

El host ya tiene:

```java
ConfigPath
```

como intento de single-source-of-truth.

Pero el editor conserva:

```text
paths.json
paths.js
model.js
```

y parte de los defaults también existen independientemente.

Eso mantiene el riesgo:

```text
Java schema
      ≠
JS schema
      ≠
documentation
```

La propia documentación ya identifica este problema.

---

# 23. Node_modules dentro del repositorio

Esto me parece uno de los defectos más claros de higiene.

El árbol Git actual contiene:

```text
src/web-editor/node_modules/...
```

con una cantidad enorme de archivos de terceros.

No es simplemente:

```text
npm install
```

fuera del repo.

Está en el árbol Git.

### Impacto

* repositorio artificialmente grande;
* clones innecesariamente pesados;
* diffs contaminados;
* búsqueda de código peor;
* mantenimiento innecesario;
* riesgo de confundir código propio con vendor code.

**Prioridad: P1/P2 dependiendo de si está efectivamente versionado en la rama de release.**

---

# 24. Gradle

Hay varias cosas buenas:

```gradle
dependencyLocking {
    lockAllConfigurations()
}
```

y existe:

```text
gradle/verification-metadata.xml
```

Pero hay inconsistencias.

`settings.gradle` incluye:

```gradle
include 'src:loadtest'
...
include 'src:tester'
include 'src:performance'
include 'src:loadtest'
```

`loadtest` aparece dos veces.

No rompe necesariamente Gradle porque Gradle puede tolerar el mismo path repetido, pero demuestra falta de limpieza en el composition root.

Además hay múltiples wrappers Gradle dentro de submódulos, cuando existe un wrapper raíz.

Eso aumenta ruido y mantenimiento.

---

# 25. CI

El workflow actual declara construir una gran cantidad de módulos.

Pero el job de dependency checking contiene:

```yaml
./gradlew dependencies --write-locks
continue-on-error: true
```

Eso no es una verificación estricta de locks.

Es más parecido a:

```text
"genera/actualiza locks y si falla, continúa"
```

que a:

```text
"el árbol de dependencias debe coincidir con locks aprobados"
```

Además, para el commit auditado:

```text
GitHub Actions runs: 0
commit statuses: 0
```

Por tanto no puedo afirmar que el HEAD haya pasado CI.

Y eso es particularmente importante porque encontré errores de compilación concretos.

---

# 26. Module Manager

Esta es probablemente la parte más ambiciosa del proyecto después del core.

Actualmente tiene:

```text
repository abstraction
       ↓
version resolution
       ↓
dependency resolution
       ↓
SHA256
       ↓
manifest
       ↓
relocation
       ↓
URLClassLoader
       ↓
registration
```

La decisión:

> `Module` = descriptor SPI, no servicio runtime

está correctamente respetada.

Eso es importante.

## Pero encontré un defecto serio

`resolve()` puede hacer:

```text
A
 ↓
B
 ↓
C
 ↓
A
```

porque la resolución recursiva de dependencias no muestra un conjunto de módulos actualmente visitados.

Un manifest malicioso o simplemente incorrecto podría producir:

```text
StackOverflowError
```

en lugar de una resolución limpia de:

```text
DEPENDENCY_CYCLE
```

**BUG-07 — dependencia recursiva sin detección de ciclo**

**Severidad:** Alta
**Confianza:** Probable/confirmado por flujo estático.

---

# 27. Otro problema del Module Manager

El método `download()` utiliza el URL del repositorio GitHub para descargar dependencias:

```text
resolve dependency
    ↓
dependency came from local/http repository
    ↓
download dependency from GitHub URL
```

Eso rompe parcialmente la abstracción multi-repository que el último commit pretende implementar.

Es decir:

```text
resolve()
```

respeta:

```text
local → GitHub → HTTP
```

pero:

```text
download()
```

no conserva necesariamente el origen elegido.

Eso puede producir:

```text
resolve = successful
download = wrong repository
```

**BUG-08 — repository provenance perdido durante download**

Severidad: Alta para instalaciones no-GitHub.

---

# 28. Relocation

Aquí sería especialmente prudente antes de llamar al sistema "production-ready".

Un relocator de JAR no consiste simplemente en renombrar:

```text
com/foo/X.class
→
me/majhrs16/relocated/com/foo/X.class
```

También hay que modificar referencias binarias dentro del bytecode y recursos relevantes:

```text
constant pool
descriptors
method signatures
service loaders
META-INF/services
reflection strings
resource references
module metadata
```

La implementación artesanal del manager merece una batería de tests de classloading/relocation mucho más profunda antes de considerarse segura para módulos arbitrarios.

No lo marco como bug confirmado sin ejecutar un JAR real; sí como **riesgo arquitectónico alto**.

---

# 29. Sync TCP/UDP

TCP tiene una limitación clara:

```java
reader.readLine()
```

sin timeout de socket.

Un cliente puede conectar y mantener la conexión abierta sin enviar `\n`.

Eso puede dejar el único accept thread bloqueado.

**BUG-09 — TCP inbound susceptible a conexión lenta**

**Severidad:** Media/Alta según exposición
**Confianza:** Confirmado.

Además, el servidor procesa conexiones secuencialmente:

```text
accept
 ↓
readLine
 ↓
process
 ↓
accept next
```

Por tanto una conexión lenta bloquea la aceptación de otras.

Para un transporte interno y controlado puede ser aceptable; para un endpoint de red pública, no.

---

# 30. Escalabilidad

No voy a afirmar:

> "soporta 10.000 jugadores"

porque el código no permite demostrarlo.

Pero sí se puede evaluar arquitectónicamente.

## Escenario A — servidor pequeño

```text
50–200 jugadores
```

La arquitectura debería ser razonable.

Cuellos de botella:

* traducción;
* renderización;
* dispatcher;
* I/O externo.

El diseño actual está suficientemente preparado.

## Escenario B — red mediana

```text
varios servidores
cientos/miles de jugadores
```

El core puede escalar conceptualmente, pero:

* sincronización;
* traducción;
* WebSocket;
* HTTP;
* serialización;
* delivery;
* ausencia de garantías formales de ordering

empiezan a dominar.

## Escenario C — network grande

Aquí el problema deja de ser solamente CPU.

Necesitarías:

```text
                    Global Sync Fabric
                           │
             ┌─────────────┼─────────────┐
             ▼             ▼             ▼
          Server A       Server B      Server C
             │             │             │
          local iFlow   local iFlow   local iFlow
```

y un transporte externo con:

* IDs globales;
* ordering;
* deduplicación;
* acknowledgements;
* retries;
* backpressure;
* TTL;
* loop detection;
* partition handling;
* observability;
* persistent/shared state.

El proyecto ya tiene conceptos que apuntan hacia esto, pero todavía no es un sistema de messaging distribuido de ese nivel.

---

# 31. El verdadero cuello de botella de network-scale

No sería probablemente `Message`.

Tampoco sería necesariamente MiniMessage.

Sería la **sincronización distribuida + servicios externos**.

Especialmente:

```text
Translation
HTTP
Discord
WebSocket
cross-server sync
```

si cada mensaje puede atravesar varias de esas capas.

La arquitectura tiene potencial para separar:

```text
local processing
```

de:

```text
distributed transport
```

pero esa separación todavía necesita garantías formales.

---

# 32. Rendimiento

Hay JMH en el repositorio, lo cual es positivo.

Pero no puedo utilizar sus resultados como benchmark real porque no existe aquí una ejecución verificable del HEAD.

Además, algunos benchmarks tienen mocks que eliminan precisamente los componentes caros:

```text
mock translation
mock delivery
mock placeholders
```

Eso los hace útiles para microbenchmarks de componentes, pero **no equivalentes a rendimiento end-to-end en producción**.

No proporcionaría p50/p95/p99 numéricos sin ejecutar.

---

# 33. Historial Git

La evolución reciente es bastante clara:

```text
13 Sep
  ↓
performance/loadtest
  ↓
14 Sep
  ↓
major security/architecture fixes
  ↓
15 Sep
  ↓
Module Manager
  ↓
16 Sep
  ↓
compile fixes
  ↓
24 Sep
  ↓
major refactor
```

Esto muestra algo interesante:

**la arquitectura está evolucionando extremadamente rápido.**

Eso ha permitido avances enormes:

* Module Manager;
* immutable Message;
* parallel dispatcher;
* security hardening;
* web editor;
* multi-repository;
* platform separation.

Pero también explica los problemas actuales.

Hay evidencia de:

```text
fix
→ auditoría
→ refactor
→ otro fix
→ nuevo refactor
```

en intervalos de días.

No diría que el proyecto está "mal diseñado"; diría que **todavía está en fase de consolidación arquitectónica**.

---

# 34. Features — evaluación resumida

| Feature              | Estado técnico                                                        |
| -------------------- | --------------------------------------------------------------------- |
| Core Message model   | Muy bueno                                                             |
| Direction system     | Muy bueno                                                             |
| iFlow                | Bueno                                                                 |
| Rate limiting        | Bueno, con race menor                                                 |
| Formatting           | Muy bueno                                                             |
| MiniMessage escaping | Bueno                                                                 |
| SpEL                 | Bueno tras sandbox                                                    |
| Google Translate     | Bueno                                                                 |
| LibreTranslate       | Bueno                                                                 |
| HTTP sync            | Medio                                                                 |
| TCP sync             | Medio                                                                 |
| UDP sync             | Medio                                                                 |
| Discord              | Bueno                                                                 |
| Telegram             | Bueno                                                                 |
| WebSocket            | Medio                                                                 |
| Spigot               | Bueno                                                                 |
| Fabric               | Bueno                                                                 |
| Web Editor           | Muy bueno                                                             |
| Module Graph         | Bueno                                                                 |
| Module Manager       | Ambicioso, todavía no release-ready                                   |
| Observability        | Bueno conceptualmente                                                 |
| Performance module   | **Roto actualmente**                                                  |
| Testing              | Bueno en cantidad, insuficiente en algunos integration/security cases |
| Documentation        | Extensa, pero puede divergir del código                               |

---

# 35. Testing

## Evaluación: **7/10**

Hay bastante infraestructura:

* unit tests;
* integration tests;
* JMH;
* web-editor tests;
* runtime tester;
* E2E;
* stress;
* concurrency.

Eso está por encima de un plugin Minecraft típico.

Pero faltan tests particularmente importantes para el estado actual:

### Necesarios

```text
ModuleGraph:
  A → A

ModuleManager:
  A → B → A
  local repository → download
  HTTP repository → download

RateLimiter:
  purge concurrent with acquire

HTTP:
  huge body
  missing Content-Length
  redirect → private IP
  DNS rebinding

TCP:
  slow client
  client never sends newline

WebSocket:
  huge frame
  burst inbound
  unauthenticated endpoint

Dispatcher:
  executor saturation
  CallerRunsPolicy
  shutdown during dispatch

StateStore:
  mutate() throws exception
  rollback guarantee
```

Y, sobre todo:

```text
Spigot/Fabric
chat
 → iFlow
 → translation
 → formatter
 → delivery
```

como E2E real.

---

# 36. Seguridad

## Evaluación: **6.5/10**

La arquitectura de seguridad ha mejorado muchísimo desde las auditorías antiguas.

### Corregido

* SpEL sandbox;
* MiniEscape;
* tokens `char[]`;
* bounded executor;
* debug endpoint local;
* debug authentication;
* SHA256;
* SafeConstructor en varias zonas;
* manifest validation.

### Todavía problemático

1. HTTP body ilimitado.
2. WebSocket payload/rate limits.
3. TCP slow-client.
4. SSRF redirect/rebinding.
5. Module Manager dependency recursion.
6. relocation artesanal.
7. algunos loaders/config paths siguen necesitando revisión de consistencia.

---

# 37. Modularidad

## **8.5/10**

Aquí TextFormatter Suite destaca.

Las fronteras:

```text
core-api
kernel
iflow
host
textformatter
gtranslate
ltranslate
sync-*
platform hosts
manager
web-editor
```

no son meramente cosméticas.

Hay una separación razonable entre:

```text
qué hacer
```

y:

```text
cómo hablar con Minecraft/Discord/HTTP
```

El principal defecto es que algunos módulos de configuración conocen demasiado de infraestructura concreta.

---

# 38. Separación de responsabilidades

## **8/10**

No veo un "God object" centralizado al nivel típico de plugins Minecraft.

Sí existe bastante orchestration en:

```text
SuiteHost
MessageDispatcher
TextFormatterSuitePlugin/Mod
```

pero cada uno tiene una razón estructural para ser grande.

El problema no es simplemente "tiene muchas líneas".

El riesgo real es que `TextFormatterSuiteMod`/`Plugin` están empezando a convertirse en composition roots que hacen:

```text
config
translation
dispatcher
Discord
WebSocket
observability
extensions
inworld
commands
reload
```

Esto es aceptable para un composition root, pero **no debería migrar lógica de negocio hacia allí**.

---

# 39. Personalización

## **8.5/10**

La filosofía definida en tu prompt está bastante bien reflejada.

El sistema permite combinaciones bastante arbitrarias.

Y correctamente no considera:

```text
valor raro
```

igual a:

```text
valor técnicamente imposible
```

La validación del editor distingue además:

```text
error
warning
```

lo cual es bueno.

El punto débil es que algunas configuraciones declaradas en schema todavía no tienen consumidores reales, por ejemplo ciertos parámetros de concurrency.

---

# 40. Mantenibilidad

### 1 año

**Buena**, si se consolida ahora.

### 3 años

**Condicional.**

El principal riesgo es el crecimiento de:

```text
config schema
+
platform adapters
+
sync adapters
+
module manager
```

sin single-source-of-truth.

### 5 años

El riesgo principal no es la cantidad de código.

Es que terminen coexistiendo varias arquitecturas:

```text
legacy
+
new core
+
manager
+
web-editor
+
platform-specific shortcuts
```

El repositorio ya conserva:

```text
common-legacy
```

por ejemplo.

Si no se controla, puede aparecer una "segunda arquitectura histórica".

---

# 41. Onboarding

### 30 minutos

Un desarrollador puede comprender:

```text
core-api
Module
Message
Direction
host
iFlow
formatter
```

si sigue el README.

### 2 horas

Puede comprender el pipeline principal.

### 1 día

Puede empezar a modificar features centrales.

### 1 semana

Puede comprender:

* platform adapters;
* sync;
* module manager;
* web editor;
* configuration schema.

El problema de onboarding no es tanto la falta de documentación.

Es **el volumen**.

El proyecto ya es suficientemente grande como para que "leer todo" sea una tarea seria.

---

# 42. Matriz final

| Área                         |     /10 | Estado                                         |
| ---------------------------- | ------: | ---------------------------------------------- |
| Código                       |   **7** | Bueno pero HEAD roto por `PerformanceProfiler` |
| Arquitectura                 | **8.5** | Ambiciosa y mayormente real                    |
| Clean Architecture           | **7.5** | Buena, con leaks host→adapter                  |
| Hexagonal                    |   **8** | Fronteras reales                               |
| Modularidad                  | **8.5** | Una de las mayores fortalezas                  |
| Separación responsabilidades |   **8** | Buena                                          |
| Legibilidad                  | **7.5** | Buena, volumen alto                            |
| Features                     | **8.5** | Amplias y profundas                            |
| Personalización              | **8.5** | Muy flexible                                   |
| Seguridad                    | **6.5** | Mejoró mucho, aún varios vectores              |
| Rendimiento                  |   **7** | Arquitectura razonable, evidencia insuficiente |
| Escalabilidad                | **6.5** | Buena base, falta distributed fabric           |
| Concurrencia                 |   **7** | Mejorada, todavía races/bloqueos               |
| Testing                      |   **7** | Mucha infraestructura, gaps críticos           |
| Mantenibilidad               | **7.5** | Buena si se consolida ahora                    |
| Documentación                |   **8** | Extensa, pero puede divergir                   |

---

# 43. Bugs / defectos prioritarios

| ID     | Hallazgo                                                  | Tipo           | Sev.        | Confianza            |
| ------ | --------------------------------------------------------- | -------------- | ----------- | -------------------- |
| BUG-01 | `PerformanceProfiler` no compila                          | Bug            | **Crítica** | **Confirmado**       |
| BUG-02 | self-cycle no detectado por ModuleGraph                   | Bug            | Media       | Confirmado           |
| BUG-03 | race entre RateLimiter purge/acquire                      | Bug            | Media       | Confirmado           |
| BUG-04 | HTTP body ilimitado                                       | Vulnerabilidad | **Alta**    | Confirmado           |
| BUG-05 | SSRF redirect/rebinding                                   | Vulnerabilidad | **Alta**    | Probable             |
| BUG-06 | WebSocket sin payload/rate limiting                       | Vulnerabilidad | **Alta**    | Probable             |
| BUG-07 | Module dependency recursion sin cycle guard               | Bug            | Alta        | Probable             |
| BUG-08 | download pierde repository provenance                     | Bug            | Alta        | Confirmado por flujo |
| BUG-09 | TCP `readLine()` sin timeout                              | DoS/defecto    | Media       | Confirmado           |
| BUG-10 | StateStore no rollback ante excepción                     | Bug            | Media       | Confirmado           |
| DEF-01 | host instancia GTranslate/LTranslate                      | Defecto diseño | Media       | Confirmado           |
| DEF-02 | HttpSink y HttpTransport duplican abstracción             | Deuda          | Media       | Confirmado           |
| DEF-03 | `node_modules` versionado                                 | Deuda          | Media       | Confirmado           |
| DEF-04 | `loadtest` incluido dos veces                             | Deuda          | Baja        | Confirmado           |
| DEF-05 | ModuleGraph promete activation order formal sin exponerlo | Defecto        | Baja        | Confirmado           |

---

# 44. P0 — Qué haría primero

## 1. Arreglar `PerformanceProfiler`

Antes de cualquier otra discusión.

El proyecto debe volver a un estado donde:

```bash
./gradlew build
```

sea demostrablemente verde.

---

## 2. Ejecutar CI sobre exactamente el commit actual

Porque actualmente no tenemos evidencia de que:

```text
44d3a309...
```

haya pasado siquiera compilación.

---

## 3. Security hardening

En este orden:

```text
HTTP body limits
WebSocket limits/rate
TCP socket timeout
SSRF redirect validation
Module Manager cycle detection
```

---

# 45. P1

Después:

```text
TranslatorProvider SPI
single-source schema
Module Manager repository provenance
relocation tests
E2E Spigot/Fabric
```

---

# 46. P2

Después:

```text
eliminar node_modules del repo
eliminar wrappers redundantes
limpiar duplicate imports
limpiar duplicate settings includes
consolidar composition roots
```

---

# 47. Qué NO tocaría

Esto es importante.

No haría una "reescritura arquitectónica".

No tocaría gratuitamente:

### `Message`

La dirección actual es buena.

### `Direction`

Es una abstracción potente y adecuada.

### `Module` como descriptor

La semántica actual:

```text
Module = descriptor
service = platform entrypoint
```

es correcta.

### `ChatDelivery`

Es una frontera hexagonal limpia.

### `ActorDirectory`

También.

### `DefaultRouter` + immutable rule snapshot

La idea:

```java
AtomicReference<List<Rule>>
```

es buena.

### Core API JDK-only

No lo contaminaría con Bukkit/Fabric/Adventure.

---

# 48. Potencial

## Potencial teórico

**Muy alto.**

La arquitectura permite evolucionar hacia:

```text
Minecraft
Discord
Telegram
HTTP
Velocity
WebSocket
custom extensions
```

sin convertir todo el sistema en un plugin específico de Minecraft.

---

## Potencial arquitectónico

**Alto.**

Especialmente por:

```text
core-api
SPI
ModuleGraph
platform adapters
sync adapters
web editor
```

La estructura soporta bastante más de lo que actualmente implementa.

---

## Potencial alcanzado

Mi estimación sería aproximadamente:

### **65–70% del potencial arquitectónico**

No significa:

> "70% del código está terminado."

Significa que la arquitectura ya materializa una proporción importante de la visión.

El resto es principalmente:

```text
distributed guarantees
security hardening
integration depth
module manager maturity
schema consolidation
production validation
```

---

# 49. Preparación para múltiples plataformas

## **8.5/10**

Está bastante preparada.

La existencia de:

```text
core-api
host
ChatDelivery
ActorDirectory
Spigot
Fabric
Velocity
```

demuestra que Minecraft no está completamente incrustado en el core.

Todavía hay wiring platform-specific considerable, pero eso es exactamente donde debería estar.

---

# 50. Preparación para network-level

## Actualmente: **5.5–6/10**

No porque el core sea incapaz.

El problema es que una network grande requiere propiedades adicionales:

```text
ordering
deduplication
delivery semantics
distributed state
backpressure
partition handling
retries
idempotency
global rate limiting
persistent queues
metrics
tracing
```

La arquitectura ya contiene varios ladrillos para construirlas, pero todavía no existe el sistema distribuido completo.

---

# 51. ¿Qué tan bueno es realmente TextFormatter Suite?

Mi conclusión es bastante específica:

**TextFormatter Suite ya no es simplemente "un plugin de chat con muchas features".**

El proyecto ha evolucionado hacia:

```text
message-processing platform
+
Minecraft adapters
+
translation
+
policy/routing engine
+
sync fabric
+
extension system
+
visual configuration system
```

La arquitectura es significativamente más sofisticada que la implementación promedio de este tipo de proyecto.

Pero tampoco lo consideraría todavía un producto técnicamente maduro de nivel network-scale.

El problema principal ya no es falta de arquitectura.

Es **consolidación**.

---

# 52. Mayor fortaleza

La mayor fortaleza es:

> **la separación entre el modelo de mensaje y los mecanismos concretos de transporte/plataforma.**

`Message → Direction → ActorDirectory → Router → Formatter → ChatDelivery`

es una cadena bastante sólida.

Eso es precisamente lo que permite imaginar:

```text
Spigot
Fabric
Velocity
Discord
Web
custom platform
```

sin duplicar el core.

---

# 53. Mayor riesgo futuro

No es el rendimiento.

No es Java.

No es MiniMessage.

Es:

> **que la enorme velocidad de evolución produzca divergencia entre arquitectura, implementación, schema, documentación y tests.**

El `PerformanceProfiler` roto en el HEAD actual es exactamente el tipo de síntoma que demuestra ese riesgo.

La arquitectura puede ser 8.5/10 mientras un módulo individual esté roto.

---

# 54. Madurez técnica aproximada

Si tuviera que expresarlo **sin convertirlo en una puntuación global simplista**, separaría:

```text
Madurez conceptual:       alta
Madurez arquitectónica:   alta
Madurez funcional:        media-alta
Madurez de seguridad:     media
Madurez de integración:   media
Madurez de producción:    media
Madurez network-scale:    media-baja
```

Y en porcentaje, siguiendo exactamente la pregunta del prompt:

### **~65% de madurez técnica global**

con una salvedad importante:

**el proyecto tiene más madurez arquitectónica que madurez operacional.**

Eso es probablemente la característica más importante del estado actual.

---

# 55. Roadmap final

```text
P0
├── BUG-01 PerformanceProfiler / build verde
├── CI real sobre HEAD
├── HTTP request limits
├── WebSocket limits
├── TCP timeout
└── Module dependency cycle detection

P1
├── TranslatorProvider SPI
├── SSRF redirect/rebinding protection
├── Module Manager repository provenance
├── relocation verification
├── E2E Spigot/Fabric real
└── concurrency tests

P2
├── schema single-source
├── remove node_modules
├── remove duplicate Gradle wrappers
├── clean duplicate includes/imports
└── consolidate composition roots

P3
├── distributed ordering
├── delivery semantics
├── persistent queues
├── distributed deduplication
├── tracing
└── network-scale sync fabric
```

## Veredicto técnico

**TextFormatter Suite tiene una arquitectura genuinamente fuerte, especialmente en core API, modularidad, SPI, ports/adapters y separación de plataforma.** La arquitectura actual permite llegar bastante lejos y no necesita una reescritura.

Lo que sí necesita ahora es exactamente lo contrario de otra gran reestructuración: **congelar arquitectura temporalmente y consolidar**.

El commit `44d3a309` es un buen ejemplo: introduce una cantidad enorme de mejoras —repository abstraction, Module Manager, refactor del árbol, reducción del JAR, fixes de routing y lifecycle— pero simultáneamente deja evidencia de que la superficie de cambios ya supera la capacidad de validación efectiva del proyecto.

En otras palabras:

```text
Arquitectura       █████████░  ~85%
Features           ████████░░  ~80%
Modularidad        █████████░  ~85%
Testing            ███████░░░  ~70%
Seguridad          ██████░░░░  ~65%
Producción         ██████░░░░  ~60%
Network-scale      █████░░░░░  ~55%
Consolidación      █████░░░░░  ~50%
```

Y el punto más urgente no es diseñar algo nuevo.

Es conseguir que **todo lo que ya existe sea coherente, compilable, testeado y verificablemente seguro en el mismo commit**.
