# AUDITORÍA TÉCNICA INTEGRAL — TEXTFORMATTER SUITE

**Fecha de auditoría:** 16 de septiembre de 2026
**Hora UTC del HEAD:** 2026-09-16 05:42:20 UTC
**Commit auditado:** `fa6b231c3ea9b25cea8b1d8ef4090f10d9b14161`
**Rama:** `main`

> **Conclusión adelantada:** TextFormatter Suite tiene una arquitectura bastante más seria que un plugin convencional de chat. El núcleo tiene buenas decisiones de diseño —especialmente `Message`, `Module`/SPI, separación de hosts, routing por receptor y composición— pero el proyecto todavía está en una etapa de **madurez arquitectónica superior a su madurez operacional**. Hay componentes muy bien diseñados junto a integraciones incompletas, documentación contradictoria, un Module Manager todavía no apto para producción y varios defectos concretos que sobreviven a las últimas correcciones.

El HEAD actual es posterior a una auditoría anterior y a una serie de commits correctivos. El propio historial muestra que el commit que declaró F16 completa fue seguido por múltiples commits de reparación, y el HEAD actual vuelve a documentar explícitamente varios componentes como bloqueados.

---

# 1. Cobertura de auditoría

## Examinado

### Estructura del repositorio

Se inspeccionaron:

* árbol raíz;
* `suite/`;
* configuración Gradle;
* módulos Java;
* `web-editor`;
* documentación principal;
* auditorías históricas;
* `PLAN`;
* `PROMPT_NOW`;
* README;
* configuración;
* módulos de testing/loadtest;
* historial reciente de Git.

El árbol actual contiene, entre otros, estos módulos:

```text
core-api
coretranslator
kernel
textformatter
iflow
host
gtranslate
ltranslate
messages
tester
transport

sync-http
sync-tcpudp
sync-discord
sync-telegram
sync-websocket
sync-velocity

manager-api
manager-impl

observability
extension-api
example-extension
presets
inworld
performance
loadtest

spigot-host
fabric-host
common-legacy
web-editor
```

El `suite` actual contiene 29 módulos conceptuales/Gradle según la documentación, aunque el `settings.gradle` raíz no incluye todos simultáneamente.

### Código inspeccionado directamente

Entre otros:

* `core-api`

  * `Module`
  * `ModuleDescriptor`
  * `Message`
  * `Direction`
  * `Actor`
  * build configuration
* `host`

  * `SuiteHost`
  * `MessageDispatcher`
  * `ConfigLoader`
* `iflow`

  * `DefaultRouter`
  * configuración de dependencias
* `textformatter`

  * `TemplateRenderer`
  * scripting
* `manager-impl`

  * `DefaultModuleLifecycle`
* `gtranslate`

  * `GTranslate`
* `transport`

  * `HttpTransport`
* `observability`

  * `DebugEndpoint`
* `loadtest`

  * `MessageProcessingBenchmark`
* `spigot-host`

  * build configuration
* `web-editor`

  * estructura, scripts y tests declarados.

### Documentación

También se contrastaron:

* `README.md`
* `docs/AUDITORIA-14-09-2026.md`
* `docs/historial/AUDITORIA-2026-09-13.md`
* `docs/PROMPT_NOW.md`
* estructura de Wiki
* ADR
* documentación de arquitectura.

Por tanto, esta auditoría tiene **cobertura amplia de arquitectura y de los componentes críticos**, pero no voy a fingir que he hecho una revisión manual línea-por-línea de absolutamente cada clase de los ~30 módulos. Las conclusiones sobre componentes secundarios se consideran de menor confianza cuando no fue necesario inspeccionar su implementación completa.

---

# 2. Estado real del proyecto

El commit actual contiene una corrección masiva respecto del estado del 13–14 de septiembre.

El último commit declara correcciones para:

* `SemVer`;
* capabilities;
* permisos de iFlow;
* `ChannelRegistry`;
* SHA-256;
* manifest validation;
* dependency resolution;
* DynamicCommand;
* observabilidad;
* PerformanceProfiler;
* Extension API;
* Discord;
* Module Manager;
* etc.

Pero el propio estado vivo del proyecto reconoce que todavía existen piezas bloqueadas:

```text
sync-velocity   → bloqueado
spigot-host     → bloqueado
fabric-host     → excluido/bloqueado
inworld         → bloqueado indirectamente
E2E completo    → pendiente
Module Manager  → no release-ready
```

Esto está explícitamente reflejado en `PROMPT_NOW`.

Por tanto:

### Estado técnico actual

```text
                 Arquitectura
                     █████████░  alta

                 Core funcional
                     ████████░░  bueno

                 Integración
                     ██████░░░░  incompleta

                 Release readiness
                     ████░░░░░░  baja-media

                 Escalabilidad demostrada
                     ███░░░░░░░  baja
```

---

# 3. Arquitectura real

La arquitectura efectiva es aproximadamente:

```text
                    Minecraft Platform
                 ┌──────────┴──────────┐
                 │                     │
             Spigot host           Fabric host
                 │                     │
                 └──────────┬──────────┘
                            │
                            ▼
                   MessageDispatcher
                            │
                    Direction expansion
                            │
                            ▼
                       SuiteHost
                            │
             ┌──────────────┼──────────────┐
             ▼              ▼              ▼
          iFlow        TextFormatter    Translation
             │              │              │
             └──────────────┼──────────────┘
                            ▼
                     RoutingResult
                            │
                            ▼
                      ChatDelivery
                            │
                            ▼
                    Minecraft / Console
```

Y alrededor:

```text
                       core-api
                           │
         ┌─────────────────┼──────────────────┐
         ▼                 ▼                  ▼
       kernel            iflow          textformatter
         │                 │                  │
         │                 │                  │
     module graph       rules             templates
         │                 │                  │
         └─────────────────┼──────────────────┘
                           │
                         host
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
       translators       syncs          observability
```

La idea fundamental es correcta: el core define contratos, los motores implementan comportamiento y los hosts adaptan la plataforma.

---

# 4. `core-api`

Este es uno de los puntos más fuertes del proyecto.

`Module` es deliberadamente pequeño:

```java
public interface Module {
    ModuleDescriptor descriptor();
}
```

No contiene:

```text
start()
stop()
enable()
bootstrap()
```

Eso es correcto porque `Module` funciona como descriptor/SPI del grafo, mientras los servicios reales son activados por los entry points de plataforma.

### Evaluación

**9/10**

Es una separación conceptualmente limpia.

---

# 5. `ModuleDescriptor`

El descriptor contiene:

```text
name
version
contractVersion
jvmMin
jvmMax
provides
requires
```

y copia defensivamente los conjuntos a estructuras inmutables.

Esto proporciona una base razonable para:

```text
module discovery
       ↓
capabilities
       ↓
requirements
       ↓
compatibility
       ↓
graph resolution
```

La principal limitación es que el handshake de versión todavía es relativamente simple frente a un sistema de compatibilidad semántica completo.

---

# 6. `Message`: una de las mejores decisiones

La evolución hacia un `Message` inmutable fue correcta.

El objeto contiene:

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
sleepMillis
```

y los cambios producen nuevos objetos:

```java
message.withLangTarget(...)
message.withText(...)
message.withCancelled(...)
message.withChannel(...)
```

El array `sounds` además se copia defensivamente.

Esto es particularmente importante porque el mismo mensaje puede utilizarse para múltiples receptores:

```text
                 Message
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
       A             B           C
        │           │           │
      clone        clone       clone
```

evitando:

```text
Message mutable compartido
          ↓
   receptor A modifica
          ↓
   receptor B recibe estado contaminado
```

### Evaluación

**9.2/10**

---

# 7. Defecto: `Message.toJson()` no genera JSON

Encontré un defecto concreto:

```java
public String toJson() {
    return toString();
}
```

mientras `toString()` produce:

```text
Message{CHAT from=... dir=... texts=...}
```

Eso no es JSON.

**[CT-01]**

**Tipo:** Defecto de diseño / Bug API
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:**
`core-api -> Message -> toJson()`

**Impacto:** cualquier consumidor que interprete `toJson()` literalmente recibirá una cadena que no puede deserializarse como JSON.

**Solución:** implementar serialización JSON real o, mejor todavía, eliminar `toJson()` del core si la serialización no pertenece a este contrato.

---

# 8. Defecto: `Direction.specific()` rompe parcialmente la inmutabilidad

El constructor recibe:

```java
this.recipients = recipients;
```

sin copiar el array.

La factory:

```java
public static Direction specific(Channel channel, Actor... recipients)
```

por tanto puede conservar el array que proporcionó el llamador.

Aunque `recipients()` devuelve una copia, el estado interno original puede modificarse externamente.

**[CT-02]**

**Tipo:** Defecto de diseño
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:**
`core-api -> Direction -> constructor`

**Solución mínima:**

```java
this.recipients =
    recipients == null ? null : recipients.clone();
```

No hace falta ninguna reestructuración.

---

# 9. `SuiteHost`

El composition root está razonablemente bien.

El bootstrap realiza:

```text
ConfigLoader
     ↓
ChannelRegistry
     ↓
DefaultRouter
     ↓
TextFormatters
     ↓
SuiteHost
```

El host recibe explícitamente:

* configuración;
* permisos;
* translation service;
* placeholders;
* logger;
* delivery.

Esto evita que cada módulo cree su propia infraestructura.

### Evaluación

**8.7/10**

---

# 10. Problema menor en `SuiteHost`

El objeto conserva:

```java
private final ChatDelivery chatDelivery;
```

pero `deliver()` no utiliza directamente ese campo.

La entrega real está en `MessageDispatcher`.

No es grave, pero es una señal de una responsabilidad que evolucionó durante los refactors.

**[DT-01]**

**Tipo:** Deuda técnica
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:**
`host -> SuiteHost -> chatDelivery`

Conviene eliminarlo si el contrato actual confirma que la entrega pertenece exclusivamente al dispatcher.

---

# 11. Pipeline completo

El pipeline real es:

```text
Minecraft event
      │
      ▼
Message
      │
      ▼
resolveSourceLanguage()
      │
      ▼
Direction
      │
      ▼
expand()
      │
      ├── INITIATOR
      ├── OTHERS
      ├── ALL
      ├── CONSOLE
      ├── SPECIFIC
      ├── PERMISSION
      ├── WORLD
      └── RADIUS
      │
      ▼
deduplicate
      │
      ▼
bounded executor
      │
      ▼
SuiteHost.deliver()
      │
      ├──────────────┐
      ▼              ▼
   iFlow         language
      │              │
      └──────┬───────┘
             ▼
      TextFormatter
             │
             ▼
      RoutingResult
             │
             ▼
       ChatDelivery
             │
             ▼
         recipient
```

Esta é uma arquitectura bastante buena para un motor de chat.

---

# 12. `MessageDispatcher`

Hay varias decisiones buenas:

* expansión de `Direction`;
* deduplicación;
* resolución de idioma una sola vez;
* executor acotado;
* aislamiento por receptor;
* tratamiento diferenciado de `DROP`, `REDIRECT`, `CHANNEL_REDIRECT`, etc.

El código efectivamente resuelve el idioma una sola vez:

```java
Message messageWithResolvedSource =
    host.resolveSourceLanguage(message);
```

y posteriormente utiliza ese objeto para todos los receptores.

Esto elimina una multiplicación innecesaria de detección:

```text
ANTES

message
 ├─ detect
 ├─ detect
 ├─ detect
 └─ detect

AHORA

message
 └─ detect
      ↓
resolvedSourceLanguage
      ├─ recipient A
      ├─ recipient B
      └─ recipient C
```

### Evaluación

**8.5/10**

---

# 13. Bug importante: `MessageDispatcher` puede lanzar por saturación

El executor está configurado:

```text
core = 4
max = 32
queue = 1000
AbortPolicy
```

Pero:

```java
CompletableFuture.supplyAsync(..., executor)
```

puede lanzar `RejectedExecutionException` inmediatamente cuando el executor está saturado.

Eso ocurre **antes** de que exista un `CompletableFuture` que el `try/catch` posterior pueda capturar.

Por tanto el comentario:

```java
@return per-target counters; never throws
```

no es cierto bajo saturación extrema.

**[HOST-01]**

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado por flujo de ejecución

**Condición:**

```text
>32 tareas activas
+
>1000 tareas esperando
```

**Impacto:** `dispatch()` puede fallar en lugar de producir un `DispatchReport`.

**Solución mínima:**

* usar `CallerRunsPolicy`, o
* manejar explícitamente `RejectedExecutionException`, o
* diseñar un backpressure real.

No recomiendo aumentar simplemente la cola.

---

# 14. Executor sin lifecycle explícito

`MessageDispatcher` crea:

```java
new ThreadPoolExecutor(...)
```

pero la clase no expone un `close()` que lo cierre.

Eso es problemático en:

* `/suite reload`;
* reload del plugin;
* tests repetidos;
* reinicialización del host.

Los threads son daemon, así que no constituyen el peor tipo de leak, pero siguen siendo recursos vivos mientras el objeto exista.

**[HOST-02]**

**Tipo:** Deuda técnica / Resource leak potencial
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:**
`host -> MessageDispatcher -> constructor`

**Solución:**

```java
public void close() {
    executor.shutdownNow();
}
```

y conectar el lifecycle del host/plugin.

---

# 15. Concurrencia

La arquitectura actual tiene una frontera razonable:

```text
platform event
       │
       ▼
async dispatcher
       │
       ▼
worker pool
       │
       ├─ routing
       ├─ formatting
       └─ translation
       │
       ▼
main-thread hop
       │
       ▼
platform delivery
```

Esto es mucho mejor que realizar HTTP/traducción dentro del hilo principal.

Sin embargo, el proyecto todavía depende demasiado de que el adapter de plataforma invoque `dispatch()` desde el contexto correcto. El propio código documenta esa responsabilidad en el adapter.

### Riesgo

Si una implementación futura llama:

```text
Minecraft main thread
      ↓
dispatch()
      ↓
future.get()
```

se puede bloquear el servidor.

La arquitectura debería hacer esa garantía estructuralmente, no solamente documentalmente.

---

# 16. `DefaultRouter`

El router tiene una estructura clara:

```text
channel
  ↓
send permission
  ↓
receive permission
  ↓
rule matching
  ↓
condition
  ↓
transform
  ↓
target
  ↓
rate limit
```

También utiliza:

```java
AtomicReference<List<Rule>>
```

para publicar conjuntos de reglas inmutables.

Esto es una decisión muy buena para configuración recargable.

### Evaluación

**8.7/10**

---

# 17. iFlow: punto fuerte y punto débil

La arquitectura de iFlow es conceptualmente interesante:

```text
input
  ↓
condition
  ↓
transform
  ↓
output
```

y permite:

* reglas;
* transformaciones;
* permisos;
* rate limiting;
* redirección;
* cancelación;
* condiciones.

Pero `DefaultRouter` captura errores de expresiones así:

```java
catch (Exception e) {
    return false;
}
```

y acciones:

```java
catch (Exception e) {
    // Action evaluation error -> ignore
}
```

Esto evita que una regla rompa el pipeline, pero también puede ocultar fallos de configuración.

### Recomendación

Mantener fail-safe, pero registrar:

```text
rule id
expression
message id
exception type
```

al menos una vez con rate limiting.

---

# 18. TextFormatter

El renderer tiene un pipeline bastante explícito:

```text
built-ins
    ↓
expressions
    ↓
external placeholders
    ↓
content
    ↓
translation spans
    ↓
MiniMessage
```

La decisión de escapar valores dinámicos antes de MiniMessage es correcta.

Esto reduce:

```text
usuario → placeholder → MiniMessage injection
```

a:

```text
usuario
  ↓
escape
  ↓
MiniMessage
```

### Evaluación

**8.8/10**

---

# 19. Scripting SpEL

La implementación utiliza:

```java
SimpleEvaluationContext
    .forReadOnlyDataBinding()
```

y evita explícitamente el modelo normal de acceso a tipos estáticos/constructores.

Esto es una mejora de seguridad importante.

No encontré evidencia suficiente para afirmar una RCE actual.

### Pero existe una preocupación

El cache es:

```java
ConcurrentHashMap<String, Expression>
```

sin límite.

Si expresiones arbitrarias pueden entrar desde una fuente controlable por un atacante:

```text
expresión 1
expresión 2
expresión 3
...
```

el cache puede crecer indefinidamente.

**[SEC-01]**

**Tipo:** Riesgo de seguridad / DoS
**Severidad:** Media
**Confianza:** Probable, no demostrable como exploit remoto con el flujo actual

Si solamente administradores modifican configuración local, el riesgo práctico disminuye mucho.

La solución correcta sería un cache bounded/LRU o compilar expresiones durante carga de configuración.

---

# 20. Configuración

`ConfigLoader` utiliza:

```java
SafeConstructor(new LoaderOptions())
```

lo cual es una buena decisión.

Además centraliza las claves mediante:

```java
ConfigPath
```

Esto ataca directamente uno de los problemas históricos del proyecto: múltiples copias del schema.

### Pero existe una política discutible

Los errores YAML producen:

```text
defaults
```

o simplemente:

```text
channel omitido
```

sin propagar el error.

Eso es operacionalmente peligroso.

Un servidor puede arrancar aparentemente bien con:

```text
configuración parcialmente rota
```

sin que el administrador se dé cuenta.

**[CFG-01]**

**Tipo:** Defecto de diseño
**Severidad:** Media
**Confianza:** Confirmado

No debería tumbar necesariamente el servidor, pero sí debería existir:

```text
ERROR config.yml: línea X...
ERROR channels/foo.yml ignored...
```

y una métrica/estado de configuración inválida.

---

# 21. Filosofía de configuración

Aquí TextFormatter va por buen camino.

El código no debe considerar:

```text
port = -50
```

incorrecto simplemente porque sea extraño.

La pregunta correcta es:

```text
¿qué operación intenta realizar el valor?
¿es segura?
¿puede representarse?
¿qué contrato exige la infraestructura?
```

Eso coincide con una filosofía de configuración extensible.

No encontré fundamento para recomendar un validador global que simplemente bloquee valores "raros".

---

# 22. Transporte HTTP

`HttpTransport` utiliza:

```java
HttpURLConnection
```

con:

```text
connect timeout = 10 s
read timeout = 10 s
```

y cierra la conexión mediante `disconnect()`.

Eso es razonable para compatibilidad con entornos de plugins.

### Problema de escalabilidad

No hay cache ni batching a nivel de traducción.

---

# 23. Traducción: el principal cuello de botella network-scale

`GTranslate` realiza directamente:

```text
cada llamada translate()
       ↓
HTTP request
       ↓
Google endpoint
```

Y `TemplateRenderer` hace:

```text
<tr>...</tr>
       ↓
translation.translate(...)
```

para cada render que requiera traducción.

Por tanto, conceptualmente:

```text
1 mensaje
   ↓
100 recipients
   ↓
100 renders
   ↓
100 translation calls
```

si cada receptor requiere una traducción distinta.

Esto es muchísimo más importante para escala que casi cualquier microoptimización de strings.

### Solución arquitectónica

Introducir:

```text
TranslationCoordinator
        │
        ├─ cache
        ├─ deduplication
        ├─ batching
        └─ async requests
```

con clave:

```text
(sourceLanguage,
 targetLanguage,
 normalizedText)
```

Así:

```text
100 jugadores ES
        ↓
1 traducción
        ↓
100 renders
```

en vez de:

```text
100 traducciones
```

---

# 24. Escalabilidad de traducción

Supongamos:

```text
1000 jugadores
20 mensajes/s
```

y 500 receptores requieren la misma traducción.

Sin cache:

```text
20 × 500
= 10,000 translation operations/s
```

Eso es una **estimación arquitectónica**, no un benchmark del proyecto.

Con deduplicación:

```text
20 unique texts/s
```

podría acercarse mucho más a la cantidad real de traducciones necesarias.

Esta es probablemente la optimización de mayor impacto de todo el sistema.

---

# 25. Module Manager

Aquí está la parte más débil de la arquitectura actual.

`DefaultModuleLifecycle` intenta resolver:

```text
GitHub Releases
     ↓
version resolution
     ↓
dependency resolution
     ↓
SHA256
     ↓
relocation
     ↓
isolated ClassLoader
     ↓
manifest validation
     ↓
registration
```

La ambición es correcta.

Pero la implementación todavía no alcanza el nivel del diseño.

---

# 26. Problema grave: manifest validation no es realmente obligatoria

El código hace:

```java
if (manifestStream == null) {
    logger.warn(...);
    return;
}
```

Por tanto:

```text
sin manifest
    ↓
warning
    ↓
continúa registration
```

Eso contradice la idea de que la validación sea un requisito obligatorio.

Además existe:

```java
if (false) {
    ...
}
```

en la comprobación de capabilities.

**[MGR-01]**

**Tipo:** Defecto de diseño
**Severidad:** Alta
**Confianza:** Confirmado

La solución mínima es hacer que un manifest requerido realmente falle:

```java
if (manifestStream == null) {
    throw new IllegalStateException(
        "Required module.yml missing"
    );
}
```

si esa es la política contractual.

---

# 27. Problema grave: `register()` no registra realmente el módulo en el kernel

El código:

```java
Module module =
    (Module) moduleClass.getDeclaredConstructor().newInstance();
```

y después:

```java
loadedModules.put(...)
moduleClassLoaders.put(...)
```

pero el comentario dice:

```java
// This would integrate with SuiteHost/ModuleLoader
```

Es decir, el objeto se instancia, pero no se observa una integración real con el kernel equivalente a:

```text
ModuleLoader
ModuleGraph
ServiceLoader
runtime services
```

**[MGR-02]**

**Tipo:** Defecto de diseño
**Severidad:** Alta
**Confianza:** Confirmado en el código inspeccionado

Esto es exactamente el tipo de diferencia entre:

> API definida

y:

> runtime funcional

que impide declarar F12 como completa.

---

# 28. ClassLoader aislado

La utilización de `URLClassLoader` para módulos es técnicamente razonable.

Pero parent-last introduce una cuestión importante:

```text
host core-api
       │
       ├── Module ClassLoader
       │       └── otra copia de core-api
```

Si el módulo lleva su propia copia de las clases del contrato:

```java
Module
Message
Actor
ModuleDescriptor
```

puede aparecer:

```text
ClassCastException
```

debido a identidad de clases distinta:

```text
host Module != module-loader Module
```

No afirmo que exista actualmente este fallo en una ruta concreta porque necesitaría ejecutar el manager con un artefacto real.

### Estado

**Hipótesis de riesgo arquitectónico**, no bug confirmado.

La solución habitual sería definir explícitamente qué paquetes son parent-first:

```text
me.majhrs16.suite.api.*
```

y aislar solamente dependencias externas.

---

# 29. SHA-256

La última corrección implementa la búsqueda obligatoria del asset:

```text
artifact.jar
artifact.jar.sha256
```

y rechaza la resolución si no existe.

Eso está bien como modelo de supply-chain.

Pero el proyecto todavía no dispone de un release pipeline suficientemente cerrado que produzca y publique sistemáticamente:

```text
artifact
sha256
manifest
release metadata
```

Por eso:

**arquitectura:** buena
**operacionalización:** incompleta.

---

# 30. Seguridad del Module Manager

El manager descarga código ejecutable desde GitHub y posteriormente lo carga.

Esto convierte:

```text
GitHub release
```

en una **trust boundary crítica**.

SHA256 evita corrupción accidental/manipulación del archivo si el checksum es confiable, pero no resuelve completamente:

* compromiso de la cuenta publicadora;
* reemplazo coordinado de artefacto y checksum;
* release malicioso firmado por el mantenedor;
* dependencia maliciosa.

Para producción sería preferible:

```text
SHA256
+
firma criptográfica
+
trusted public key
```

Esto es especialmente importante porque el componente tiene capacidad de ejecutar código dinámicamente.

---

# 31. Observabilidad

La estructura es buena:

```text
observability
 ├── MetricsCollector
 ├── MetricsEndpoint
 ├── DebugEndpoint
 └── HealthCheckRegistry
```

El debug endpoint:

* escucha en `127.0.0.1`;
* exige token;
* utiliza GET;
* eliminó el endpoint de simulación de mensajes.

Eso constituye una mejora de seguridad clara.

### Evaluación

**8/10**

---

# 32. Defecto en lifecycle del DebugEndpoint

Crea:

```java
Executors.newFixedThreadPool(4)
```

pero `stop()` hace solamente:

```java
server.stop(0);
```

No existe un shutdown explícito del executor creado.

**[OBS-01]**

**Tipo:** Deuda técnica / Resource leak
**Severidad:** Media
**Confianza:** Confirmado

Es especialmente relevante porque TextFormatter soporta reload.

---

# 33. Web Editor

La estructura es bastante madura para una herramienta estática:

```text
index.html
prototype.html
styles.css
js/
tests/
package.json
package-lock.json
ESLint
Prettier
```

y `PROMPT_NOW` reporta:

```text
99 unit tests
integration harnesses
format
lint
```

La arquitectura desacopla el editor del runtime Java mediante schema/YAML.

Eso es correcto.

### Evaluación

**8.5/10**

---

# 34. Problema de documentación: README vs realidad

El README todavía contiene información que no corresponde exactamente al HEAD.

Por ejemplo describe:

```text
sync-velocity → pendiente
fabric-host → funcional
spigot-host → fat-jar construido
```

mientras el estado vivo del 16/09 dice:

```text
sync-velocity → bloqueado
fabric-host → excluido
spigot-host → bloqueado
```

Esto no es un detalle menor para un proyecto modular.

**[DOC-01]**

**Tipo:** Deuda técnica
**Severidad:** Media
**Confianza:** Confirmado

---

# 35. `PROMPT_NOW` también tiene contradicciones internas

El mismo documento declara arriba:

```text
Module Manager
M2 ✅
M3 ✅
M4 ✅
M5 ✅
```

pero más abajo conserva:

```text
version resolver → Stub
SHA256 → No conectado
```

Esto demuestra que el problema no es simplemente:

> README desactualizado.

También existe una **fuente de estado duplicada internamente inconsistente**.

**[DOC-02]**

**Tipo:** Deuda técnica
**Severidad:** Media

---

# 36. Build system

El `settings.gradle` raíz incluye 26 proyectos explícitamente:

```text
core-api
coretranslator
kernel
iflow
host
textformatter
...
tester
```

pero no incluye:

```text
sync-velocity
fabric-host
performance
```

aunque existen físicamente.

Esto parece deliberado para aislar dependencias bloqueadas, pero tiene una consecuencia:

```text
./gradlew build
```

no representa realmente:

```text
"build entire suite"
```

### Evaluación

**7/10**

La estrategia es razonable para desarrollo offline, pero debería existir una separación explícita:

```text
buildCore
buildAll
buildPlatform
```

en lugar de que la topología del aggregate build sea ambigua.

---

# 37. Spigot host

El `spigot-host` está configurado para Java 21 y depende de:

```text
spigot-api:1.16.5-R0.1-SNAPSHOT
```

El problema actual es operacional:

```text
Spigot snapshot unavailable
        ↓
build blocked
        ↓
inworld blocked
        ↓
E2E Spigot blocked
```

Esto no demuestra un defecto del código de Spigot.

Es principalmente una **dependencia de build no reproducible**.

La documentación actual lo reconoce explícitamente.

---

# 38. Fabric

La situación es equivalente pero distinta:

```text
fabric-host
    ↓
Minecraft/Loom dependencies
    ↓
requiere descarga
```

El proyecto lo excluye de las pruebas offline actuales.

Esto impide validar actualmente el pipeline real:

```text
Minecraft
→ Fabric event
→ Message
→ Dispatcher
→ iFlow
→ Formatter
→ Delivery
```

---

# 39. Load testing

Aquí encontré un problema muy concreto.

`MessageProcessingBenchmark` crea:

```java
dispatcher = new MessageDispatcher(
    host,
    ...
);
```

pero:

```java
private SuiteHost host;
```

está `null`.

Y `MessageDispatcher` exige:

```java
Objects.requireNonNull(host, "host");
```

Por tanto el setup falla.

Además:

```java
renderer = new TemplateRenderer(
    null, // translation
    null, // placeholders
    logger
);
```

pero `TemplateRenderer` hace:

```java
this.translation =
    Objects.requireNonNull(translation, "translation");
```

Así que también falla.

**[LOAD-01]**

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado estáticamente

El benchmark no puede llegar a medir correctamente con ese setup.

El código del benchmark también declara un `teardown()` que intenta:

```java
host.close();
```

pero `host` nunca se construyó en el setup mostrado.

Esto es especialmente importante porque la documentación presenta el módulo como infraestructura de benchmarking.

---

# 40. Por tanto, NO hay que interpretar los benchmarks como evidencia de throughput

El proyecto tiene:

```text
JMH
warmup
measurement
forks
benchmarks
```

lo cual es buena infraestructura.

Pero hasta corregir el setup:

> **no existe evidencia válida para afirmar X mensajes/s, X μs/evento o determinado p99.**

Eso cumple exactamente con la regla de no inventar benchmarks.

---

# 41. Complejidad del pipeline

Para un mensaje con:

```text
R = receptores
F = reglas
S = longitud del template
```

el dispatcher realiza aproximadamente:

```text
expand recipients     O(R)
routing por receptor  O(R × F)
formatting            O(R × S)
```

y si hay traducción:

```text
translation            O(R × network)
```

en términos de llamadas externas.

El gran problema no es la complejidad algorítmica de Java.

Es la multiplicación:

```text
recipients × external services
```

---

# 42. Escenario A — servidor pequeño

Ejemplo conceptual:

```text
20–50 jugadores
1–5 msg/s
```

### Cuello de botella probable

Traducción HTTP.

### Riesgo CPU

Bajo.

### Riesgo memoria

Bajo.

### Riesgo GC

Bajo.

### Riesgo de pérdida

Bajo salvo fallos externos.

### Arquitectura

Adecuada.

---

# 43. Escenario B — red mediana

```text
100–300 jugadores por servidor
5–20 msg/s
```

La arquitectura sigue siendo viable, pero:

```text
translation
+
placeholder resolution
+
MiniMessage
+
parallel executor
```

empiezan a dominar.

El pool:

```text
4–32 workers
1000 queued
```

es un límite estructural explícito.

---

# 44. Escenario C — red grande

```text
500–1000+ jugadores
burst traffic
múltiples instancias
```

La arquitectura necesita obligatoriamente:

```text
translation cache
request deduplication
async external I/O
backpressure
metrics
timeouts
circuit breakers
```

Actualmente algunos de estos conceptos existen parcialmente, pero no forman todavía una estrategia integral.

---

# 45. Escenario D — network-scale

Para una red tipo:

```text
miles / decenas de miles de jugadores
múltiples servidores
```

la implementación actual **no está demostrada para esa escala**.

No porque el core sea malo.

Sino porque la ruta crítica contiene:

```text
per-recipient routing
per-recipient formatting
per-recipient translation
external HTTP
bounded worker pool
```

sin un sistema completo de:

```text
shared translation cache
cross-server caching
batching
event backpressure
distributed synchronization strategy
```

---

# 46. Qué necesitaría para network-scale

La arquitectura objetivo debería evolucionar a:

```text
                   Message
                      │
                      ▼
               Routing Planner
                      │
          ┌───────────┴───────────┐
          ▼                       ▼
    Recipient groups        Local delivery
          │
          ▼
 Translation Coordinator
          │
    ┌─────┼─────┐
    ▼     ▼     ▼
 cache  dedupe batch
    │     │     │
    └─────┼─────┘
          ▼
   Translation backend
          │
          ▼
      Render plan
          │
          ▼
      Delivery
```

El concepto de **grouping** es especialmente importante.

No debería procesarse:

```text
player 1
player 2
player 3
...
```

si todos comparten:

```text
same language
same format
same source
same translation
```

---

# 47. Seguridad

## Positivo

* YAML SafeConstructor.
* MiniMessage escaping.
* SpEL read-only context.
* Debug endpoint localhost.
* Debug token.
* eliminación de `/debug/simulate`.
* SHA256.
* validación de manifest en progreso.
* bounded executors.

Esto representa una mejora sustancial respecto de una arquitectura plugin convencional.

---

# 48. Riesgo SSRF

`HttpTransport` acepta una URL arbitraria:

```java
new URL(urlString)
```

y:

```java
conn.setInstanceFollowRedirects(true);
```

Eso puede ser peligroso si una URL es controlable por un actor no confiable.

Por sí mismo no puedo marcarlo como SSRF explotable remotamente porque el flujo de quién puede modificar las URLs no está demostrado como público.

### Clasificación

**[SEC-02]**

**Tipo:** Riesgo de seguridad
**Severidad:** Media
**Confianza:** Probable/Hipotético según configuración

Si la configuración sólo es administrable localmente, el riesgo práctico es mucho menor.

---

# 49. Testing

La infraestructura es bastante más avanzada de lo habitual:

```text
unit tests
integration tests
JMH
runtime tester
concurrency tests
stress
web-editor tests
```

`PROMPT_NOW` declara:

```text
kernel: 13 tests
host: 39 tests
tester: 25 runtime tests
web-editor: 99 unit tests
```

y además integration harnesses.

### Pero falta una pieza crucial

```text
actual platform E2E
```

El propio proyecto lo marca pendiente/bloqueado.

Por eso la cobertura del core no equivale a cobertura del producto.

---

# 50. Testing de concurrencia

El uso de:

```text
AtomicReference
ConcurrentHashMap
bounded executor
immutable Message
```

facilita los tests.

Pero faltan pruebas especialmente importantes para:

```text
executor saturation
RejectedExecutionException
reload mientras existen tasks
shutdown durante dispatch
translation timeout
translation failure
duplicate recipient
1000+ recipients
simultaneous configuration reload
```

Estos deberían convertirse en pruebas de regresión.

---

# 51. Historial de Git

La evolución reciente es clara:

```text
F16 declarada completa
        ↓
auditoría
        ↓
C1–C5
        ↓
H1–H5
        ↓
Module Manager fixes
        ↓
build fixes
        ↓
HEAD actual
```

El commit actual documenta explícitamente una larga lista de correcciones posteriores a la declaración inicial de F16.

Esto revela un patrón:

### La arquitectura está evolucionando rápidamente.

Eso tiene dos caras:

**Positivo**

* deuda descubierta;
* problemas corregidos;
* contratos refinados;
* arquitectura consolidándose.

**Negativo**

* contratos internos cambian más rápido que la documentación;
* features nuevas llegan antes de estabilizar completamente las anteriores.

---

# 52. ¿Está acumulando deuda?

Actualmente:

```text
                    Deuda
                     │
       ┌─────────────┼─────────────┐
       ▼             ▼             ▼
   Manager       Documentation   Integration
    alta             media          alta
```

El core no parece estar acumulando deuda de manera catastrófica.

La deuda está más concentrada en:

```text
feature integration
build reproducibility
release engineering
documentation synchronization
runtime manager
```

Eso es positivo porque son problemas solucionables sin reescribir el core.

---

# 53. Clean Architecture

Aquí hay que ser preciso.

No es simplemente:

```text
domain/
application/
infrastructure/
```

El proyecto tiene realmente contratos relativamente independientes.

### Dependency Rule

La parte central:

```text
core-api
   ↑
engines
   ↑
host/adapters
```

está bastante bien.

Sin embargo:

```text
iflow → textformatter
```

demuestra que no todo está aislado como un puro dominio independiente. El propio build de iFlow confirma esa dependencia.

Esto no es automáticamente incorrecto.

iFlow utiliza tipos de TextFormatter como parte de sus transforms.

### Evaluación

**8.1/10**

Es una implementación real de principios de Clean Architecture, pero no una separación académicamente pura.

---

# 54. Hexagonal Architecture

Aquí el proyecto es todavía más convincente.

Existen:

```text
ports
  ↓
ChatDelivery
ActorDirectory
TranslationService
SyncSink
PlaceholderResolver
ExpressionEvaluator
PluginLogger
```

y adapters:

```text
Spigot
Fabric
Discord
Telegram
HTTP
TCP/UDP
WebSocket
GTranslate
LTranslate
```

El core no necesita conocer directamente Bukkit para representar un actor.

Eso es exactamente lo que Ports & Adapters intenta conseguir.

### Evaluación

**8.8/10**

Probablemente el aspecto arquitectónico más sólido de todo el proyecto.

---

# 55. Modularidad

Las fronteras son reales en buena medida:

```text
core-api
   ↓
engines
   ↓
hosts
   ↓
platform
```

y muchos módulos pueden compilarse y probarse separadamente.

El coste aparece cuando se mira:

```text
manager-impl
spigot-host
inworld
presets
observability
```

porque algunos agregan muchas dependencias.

Aun así, no considero que "muchas dependencias" sea automáticamente un defecto.

### Evaluación

**8.4/10**

---

# 56. Features

| Feature                 | Calidad | Estado                                          |
| ----------------------- | ------: | ----------------------------------------------- |
| Core API                |     9.2 | estable                                         |
| Message model           |     9.2 | estable                                         |
| Module graph            |     8.8 | funcional                                       |
| iFlow                   |     8.7 | funcional                                       |
| TextFormatter           |     8.8 | funcional                                       |
| Translation abstraction |     8.5 | funcional                                       |
| GTranslate              |     7.8 | funcional, externo                              |
| LTranslate              |     7.8 | funcional, externo                              |
| Direction system        |     8.7 | funcional                                       |
| Channel system          |     8.5 | funcional                                       |
| Sync HTTP               |       8 | funcional                                       |
| Sync Telegram           |       8 | funcional                                       |
| Sync Discord            |       8 | funcional                                       |
| Sync WebSocket          |       8 | funcional                                       |
| TCP/UDP                 |       8 | funcional                                       |
| Observability           |       8 | funcional                                       |
| Web editor              |     8.5 | bastante maduro                                 |
| Extension API           |     8.5 | estable                                         |
| Presets                 |       8 | funcional                                       |
| In-world                |     6.5 | bloqueado por host                              |
| Velocity                |       6 | bloqueado                                       |
| Fabric host             |     6.5 | no validado actualmente                         |
| Spigot host             |     6.5 | build bloqueado                                 |
| Module Manager          |     5.5 | prototipo avanzado                              |
| Performance tooling     |       7 | infraestructura buena, benchmarks con problemas |
| E2E                     |       5 | incompleto                                      |

---

# 57. Personalización

La capacidad es alta.

El sistema permite configurar:

```text
channels
formats
tooltips
sounds
languages
permissions
rules
conditions
transforms
directions
sync
translation
placeholders
scripts
```

Y, especialmente, las transforms son composables.

La filosofía de:

```text
permitir combinaciones mientras sean técnicamente seguras
```

es adecuada para este tipo de framework.

### Evaluación

**8.8/10**

El mayor límite actual no es el modelo de configuración, sino la cantidad de comportamiento todavía no conectado completamente al runtime.

---

# 58. Mantenibilidad — 1 año

Con el core actual:

```text
bien
```

si se estabilizan:

* Message;
* SPI;
* module contracts;
* configuration schema.

El principal riesgo es que nuevas features sigan entrando sin cerrar las anteriores.

---

# 59. Mantenibilidad — 3 años

Aquí el punto crítico será:

```text
compatibilidad de API
+
config schema
+
module contracts
+
platform versions
```

Si se mantiene:

```text
core-api estable
```

la evolución puede ser saludable.

Si empiezan a modificarse continuamente los contratos centrales:

```text
Message
Module
Actor
TranslationService
SyncSink
```

el coste crecerá rápidamente.

---

# 60. Mantenibilidad — 5 años

El mayor riesgo no es el número de clases.

Es el **contrato histórico**.

Un framework que quiere soportar:

```text
Spigot
Fabric
Velocity
Discord
Telegram
WebSocket
HTTP
```

necesita versionar cuidadosamente sus APIs.

Aquí `contractVersion` es una buena base.

Pero debe evolucionar hacia:

```text
compatibility ranges
deprecation policy
migration guides
schema versions
contract tests
```

---

# 61. Onboarding

## 30 minutos

Un desarrollador competente podría entender:

```text
core-api
Message
Direction
SuiteHost
Dispatcher
```

y el pipeline general.

### 2 horas

Podría comprender:

```text
iFlow
TextFormatter
ChannelRegistry
Translation
```

### 1 día

Podría empezar a modificar:

```text
rules
formats
routing
translation
sync
```

### 1 semana

Podría razonablemente trabajar en:

```text
host
core
new adapter
new sink
new translation backend
```

si la documentación se sincroniza.

### Problema

El onboarding empeora considerablemente por las inconsistencias actuales entre README, PLAN y PROMPT_NOW.

---

# 62. Defectos encontrados

## [HOST-01]

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado

`MessageDispatcher` puede lanzar `RejectedExecutionException` cuando se llena el executor.

---

## [HOST-02]

**Tipo:** Deuda técnica
**Severidad:** Media
**Confianza:** Confirmado

Executor del dispatcher sin lifecycle/close explícito.

---

## [CT-01]

**Tipo:** Bug/API defect
**Severidad:** Media
**Confianza:** Confirmado

`Message.toJson()` devuelve `toString()`.

---

## [CT-02]

**Tipo:** Defecto de diseño
**Severidad:** Baja
**Confianza:** Confirmado

`Direction` conserva el array `recipients` sin copia defensiva.

---

## [CFG-01]

**Tipo:** Defecto de diseño
**Severidad:** Media
**Confianza:** Confirmado

Errores de YAML se degradan silenciosamente a defaults/omisión.

---

## [MGR-01]

**Tipo:** Defecto de diseño
**Severidad:** Alta
**Confianza:** Confirmado

Manifest ausente genera warning pero no impide registro.

---

## [MGR-02]

**Tipo:** Defecto de diseño
**Severidad:** Alta
**Confianza:** Confirmado

`register()` instancia el módulo y almacena metadata/classloader, pero la integración efectiva con el kernel/runtime todavía no está cerrada.

---

## [OBS-01]

**Tipo:** Deuda técnica
**Severidad:** Media
**Confianza:** Confirmado

Executor de `DebugEndpoint` sin shutdown explícito.

---

## [LOAD-01]

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado

`MessageProcessingBenchmark` construye `MessageDispatcher` con `host == null` y `TemplateRenderer` con `translation == null`, ambos incompatibles con los constructores actuales.

---

## [SEC-01]

**Tipo:** Riesgo de seguridad
**Severidad:** Media
**Confianza:** Probable

Expression cache potencialmente ilimitado.

---

## [SEC-02]

**Tipo:** Riesgo de seguridad
**Severidad:** Media
**Confianza:** Hipotético/probable

HTTP transport con URLs configurables y redirects puede constituir SSRF si una fuente no confiable controla el destino.

---

## [DOC-01]

**Tipo:** Deuda técnica
**Severidad:** Media
**Confianza:** Confirmado

README no representa completamente el estado actual.

---

## [DOC-02]

**Tipo:** Deuda técnica
**Severidad:** Media
**Confianza:** Confirmado

`PROMPT_NOW` contiene estados contradictorios dentro del mismo documento.

---

# 63. Cosas que NO recomiendo tocar

Hay partes que actualmente funcionan como buenos fundamentos y no necesitan una reescritura.

## No tocar innecesariamente:

### `Module` como descriptor

Está bien.

### `Message` inmutable

Está bien.

### `resolvedSourceLanguage`

La optimización es correcta.

### `Direction`

Mantener el concepto; solamente corregir la copia defensiva.

### `AtomicReference<List<Rule>>`

Buena solución para configuración de reglas publicada de forma atómica.

### Ports & Adapters

No sustituirlo por otra arquitectura.

### `SuiteHost` como composition root

Mantenerlo.

### `core-api` JDK-puro

Mantenerlo tan puro como sea posible.

### MiniMessage escaping

No eliminarlo en nombre de "flexibilidad".

### bounded executor

La idea es correcta; hay que mejorar el backpressure/lifecycle, no quitarlo.

---

# 64. Roadmap

## P0 — Crítico

| Problema                      | Beneficio                     | Esfuerzo |
| ----------------------------- | ----------------------------- | -------: |
| Corregir `LOAD-01`            | Benchmarks reales             |     Bajo |
| Corregir `HOST-01`            | Evitar fallos bajo saturación |     Bajo |
| Cerrar lifecycle de executors | Reload seguro                 |     Bajo |
| Resolver build Spigot         | Habilitar E2E                 |    Medio |
| Terminar Module Manager       | Seguridad/runtime             |     Alto |

---

# 65. P1 — Alto

| Problema                               | Beneficio            | Esfuerzo |
| -------------------------------------- | -------------------- | -------: |
| Translation cache/dedup                | Escalabilidad enorme |    Medio |
| Manifest obligatorio                   | Supply chain         |     Bajo |
| Integración real manager → kernel      | Runtime modules      |     Alto |
| Tests de saturación                    | Concurrencia         |    Medio |
| E2E Spigot                             | Validación producto  |    Medio |
| E2E Fabric                             | Validación producto  |    Medio |
| Shutdown formal de todos los executors | Lifecycle            |     Bajo |

---

# 66. P2 — Medio

| Problema                            | Beneficio      | Esfuerzo |
| ----------------------------------- | -------------- | -------: |
| Unificar documentación              | Onboarding     |    Medio |
| Limitar expression cache            | Robustez       |     Bajo |
| Logging de errores de configuración | Operabilidad   |     Bajo |
| SSRF policy                         | Seguridad      |    Medio |
| contract tests entre módulos        | Compatibilidad |    Medio |
| grouping de traducciones            | CPU/network    |    Medio |

---

# 67. P3 — Bajo

| Problema                     | Beneficio     | Esfuerzo |
| ---------------------------- | ------------- | -------: |
| `Message.toJson()`           | API limpia    |     Bajo |
| copia defensiva `Direction`  | Inmutabilidad |     Bajo |
| eliminar campos muertos      | Limpieza      |     Bajo |
| mejorar comentarios/Javadocs | Onboarding    |     Bajo |

---

# 68. Potencial

## Potencial teórico

**Muy alto.**

TextFormatter no está limitado conceptualmente a:

```text
plugin de chat
```

La arquitectura permite:

```text
chat
tab
MOTD
scoreboard
nametags
sync
translation
rules
external systems
extensions
```

y distintos hosts.

---

# 69. Potencial arquitectónico

**Alto.**

La existencia de:

```text
core-api
ports
SPI
host
ModuleDescriptor
Message
Direction
```

proporciona una base real para seguir creciendo sin convertir todo inmediatamente en un monolito.

---

# 70. Potencial alcanzado

Mi estimación técnica:

### **≈ 65–72% del potencial arquitectónico**

No porque falten "muchas features".

La razón es distinta:

```text
Core conceptual       █████████░
Feature breadth       █████████░
Integration           ██████░░░░
Release engineering   ████░░░░░░
Scale validation      ███░░░░░░░
Runtime modules       █████░░░░░
```

La arquitectura permite bastante más de lo que actualmente está demostrado operacionalmente.

---

# 71. Matriz final

| Área                         | Puntuación /10 | Estado         | Principales problemas                 |
| ---------------------------- | -------------: | -------------- | ------------------------------------- |
| Código                       |        **8.0** | Bueno          | algunos defectos localizados          |
| Arquitectura                 |        **8.7** | Muy buena      | integración incompleta                |
| Clean Architecture           |        **8.1** | Buena          | algunas dependencias entre engines    |
| Hexagonal                    |        **8.8** | Muy buena      | pocos límites realmente problemáticos |
| Modularidad                  |        **8.4** | Buena          | aggregate build incompleto            |
| Separación responsabilidades |        **8.2** | Buena          | manager/host aún evolucionando        |
| Legibilidad                  |        **8.0** | Buena          | documentación inconsistente           |
| Features                     |        **8.4** | Amplias        | varias parcialmente integradas        |
| Personalización              |        **8.8** | Muy alta       | runtime todavía limita algunas        |
| Seguridad                    |        **7.6** | Buena          | manager/SSRF/cache                    |
| Rendimiento                  |        **7.0** | Potencial alto | traducción per-recipient              |
| Escalabilidad                |        **6.3** | No demostrada  | I/O externo y dispatcher              |
| Concurrencia                 |        **7.5** | Buena base     | saturation/lifecycle                  |
| Testing                      |        **7.4** | Bastante bueno | E2E y benchmarks                      |
| Mantenibilidad               |        **7.9** | Buena          | contratos/docs                        |
| Documentación                |        **7.0** | Amplia         | contradicciones actuales              |

---

# 72. Evaluación global

Si separo las cosas:

```text
Calidad conceptual       ≈ 9/10
Calidad arquitectónica   ≈ 8.7/10
Calidad del core         ≈ 8.5/10
Calidad de integración   ≈ 6.5/10
Release readiness        ≈ 5.5/10
Escalabilidad demostrada ≈ 5/10
```

Eso explica por qué el proyecto puede parecer simultáneamente:

> "bastante profesional"

y:

> "todavía claramente en desarrollo".

Ambas observaciones son compatibles.

---

# 73. ¿Qué tan bueno es realmente TextFormatter Suite?

**Es un proyecto técnicamente serio, pero todavía no un producto completamente consolidado.**

La diferencia es importante.

No veo un "castillo de interfaces" vacío.

Hay arquitectura real, contratos reales, separación real, routing real, formatter real, traducción real, adapters reales y una cantidad considerable de testing.

Pero tampoco sería correcto llamarlo actualmente:

```text
release-ready
network-scale proven
F16 completely finished
```

porque el propio código y estado del proyecto no respaldan esas afirmaciones.

---

# 74. Sus partes excepcionalmente bien diseñadas

Las destacaría así:

### 1. Modelo `Message`

Muy buena evolución.

### 2. `Module` como descriptor

La separación descriptor/runtime está bien pensada.

### 3. Ports & Adapters

Es probablemente la mayor fortaleza arquitectónica.

### 4. Direction como unidad de audiencia

Evita meter un modelo artificial `from/to` en cada mensaje.

### 5. Source-language caching

Optimización sencilla y de gran impacto.

### 6. Composition root

`SuiteHost` mantiene bastante bien la responsabilidad de ensamblaje.

### 7. Seguridad de rendering

Escaping + MiniMessage + SpEL restringido es una combinación razonable.

---

# 75. Las partes mediocres

Principalmente:

```text
Module Manager
release engineering
documentation synchronization
platform integration
performance validation
```

No porque estén mal concebidas, sino porque están **inacabadas**.

---

# 76. Peores defectos

Los tres más importantes son:

### 1.

**El Module Manager todavía parece más un framework de infraestructura que un runtime de módulos terminado.**

### 2.

**La traducción per-recipient puede convertirse en el cuello de botella dominante a escala.**

### 3.

**La validación real del producto está limitada por la ausencia de un E2E completo sobre los hosts.**

Y hay un cuarto bastante concreto:

### 4.

**La infraestructura de benchmark actualmente tiene errores de inicialización que impiden utilizarla como evidencia.**

---

# 77. Mayor fortaleza arquitectónica

Sin duda:

```text
core-api
    +
ports
    +
host
    +
platform adapters
```

El proyecto logró algo que muchos plugins de Minecraft no hacen:

```text
Bukkit/Fabric
       ↓
no contaminan
       ↓
modelo central
```

Eso es exactamente lo que hace posible que el mismo concepto pueda vivir en plataformas diferentes.

---

# 78. Mayor riesgo futuro

No es el tamaño.

No es la cantidad de clases.

No es que haya "demasiadas interfaces".

El riesgo real es:

> **seguir agregando F17/F18/F19... más rápido de lo que se estabilizan los contratos y las integraciones.**

El historial reciente ya muestra ese patrón: feature → auditoría → correcciones múltiples.

La respuesta no es parar el desarrollo.

Es introducir una etapa formal de:

```text
feature
 ↓
integration
 ↓
contract tests
 ↓
E2E
 ↓
benchmark
 ↓
docs
 ↓
release
```

antes de marcarla como completa.

---

# 79. Preparación para múltiples plataformas

### Arquitectónicamente

**Alta.**

### Operacionalmente

**Media.**

Spigot/Fabric están claramente contemplados como adapters separados, pero actualmente las dependencias de plataforma impiden demostrar el pipeline completo.

---

# 80. Preparación para una network grande

Actualmente:

### **No demostrada.**

La arquitectura podría llegar allí, pero necesita principalmente:

```text
translation cache
translation dedupe
batching
backpressure
distributed synchronization strategy
E2E
real benchmarks
```

No necesita una reescritura total.

---

# 81. Qué tendría que cambiar para network-level

No:

```text
rewrite everything
```

Sí:

```text
1. estabilizar core-api
2. completar hosts
3. translation coordinator
4. bounded backpressure
5. circuit breakers
6. caching
7. grouping
8. observability
9. E2E
10. regression benchmarks
11. release pipeline
12. signed module artifacts
```

La mayor parte puede implementarse encima de la arquitectura actual.

---

# 82. Qué porcentaje considero técnicamente maduro

Separando madurez de cantidad de features:

### Core arquitectónico

**~85–90%**

### Motor funcional

**~80–85%**

### Ecosistema de módulos

**~70–75%**

### Platform integration

**~60–70%**

### Runtime Module Manager

**~50–60%**

### Release engineering

**~50%**

### Validación network-scale

**~30–40%**

### Madurez técnica global

## **≈ 72–78%**

No significa que "el 75% del código esté terminado". Es una estimación de madurez del sistema completo.

---

# 83. Conclusión final

Mi lectura después de contrastar código, arquitectura, build, documentación, tests declarados y evolución Git es:

```text
TextFormatter Suite
        │
        ├── Arquitectura       █████████░
        ├── Core               ████████░░
        ├── Extensibilidad     █████████░
        ├── Configuración      ████████░░
        ├── Features           ████████░░
        ├── Seguridad          ███████░░░
        ├── Testing            ███████░░░
        ├── Integración        ██████░░░░
        ├── Runtime manager    █████░░░░░
        └── Scale proof        ███░░░░░░░
```

La arquitectura **sí merece conservarse**.

No veo una razón técnica para tirar el proyecto y rehacerlo desde cero. De hecho, hacerlo probablemente destruiría varias de las decisiones que actualmente son sus mejores propiedades.

Lo que necesita ahora no es otra revolución arquitectónica.

Necesita **consolidación**:

```text
               AHORA
                 │
        ┌────────┴────────┐
        ▼                 ▼
   completar runtime   completar hosts
        │                 │
        └────────┬────────┘
                 ▼
              E2E
                 │
                 ▼
       translation caching
                 │
                 ▼
          load benchmarks
                 │
                 ▼
         docs synchronization
                 │
                 ▼
          release pipeline
                 │
                 ▼
          production-ready
```

### En una frase:

> **TextFormatter Suite ya tiene una arquitectura de framework real; lo que todavía le falta es demostrar que todas esas piezas arquitectónicas funcionan juntas como un producto operacional coherente bajo carga y lifecycle real.**

Y esa diferencia es importante: **el problema principal de TxF a fecha 16/09/2026 ya no es "¿la arquitectura está mal?" sino "¿podemos terminar de consolidar y demostrar la arquitectura que ya tenemos?"**
