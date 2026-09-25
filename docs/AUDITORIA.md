Voy a tratar el Markdown adjunto como la especificación de la auditoría. Esa especificación exige reconstruir la arquitectura real, distinguir hechos/inferencias/hipótesis y no convertir preferencias en defectos. 

# Auditoría técnica integral — TextFormatter Suite

**Fecha:** 25 de septiembre de 2026, 06:00 UTC aprox.
**Repositorio:** `majhrs16-official/TextFormatter-Suite`
**Rama:** `main`
**HEAD auditado:** `f2e009b1ce7723df57d22e0983b5110d2e3cd171`
**Commit:** [`f2e009b1`](https://github.com/majhrs16-official/TextFormatter-Suite/commit/f2e009b1ce7723df57d22e0983b5110d2e3cd171)

---

# 1. Resumen ejecutivo

La conclusión más importante es esta:

> **TextFormatter Suite ya no es simplemente un plugin de chat con muchas features. Es una plataforma modular de procesamiento de mensajes con una arquitectura de core bastante seria, SPI, resolución de módulos, adapters de plataforma, routing, sincronización, traducción, configuración declarativa y tooling.**

Y al mismo tiempo:

> **Todavía no está al nivel de madurez que su arquitectura sugiere.**

El proyecto ha evolucionado muchísimo desde la auditoría anterior. De hecho, varios problemas graves encontrados en aquella auditoría fueron corregidos en commits posteriores:

* `PerformanceProfiler` roto → corregido.
* HTTP inbound sin límite → corregido.
* WebSocket sin límites → corregido.
* SSRF mediante redirects → mitigado.
* `TranslatorsConfig` acoplado a implementaciones → migrado a `TranslatorProvider` SPI.
* checksums SHA-256 → incorporados.
* CI actualizado a `src/`.
* módulos problemáticos → estabilizados.
* caché/deduplicación de traducciones → incorporada.

Los commits [`45929d4`](https://github.com/majhrs16-official/TextFormatter-Suite/commit/45929d4102a2f54e0c80cabe71b93b59d072df0d), [`ce97f11`](https://github.com/majhrs16-official/TextFormatter-Suite/commit/ce97f113fcd9a70912b8bb1ef2bdef684d825b89) y [`910b623`](https://github.com/majhrs16-official/TextFormatter-Suite/commit/910b623a5732daa7619d9f0987caef5263683ab3) muestran claramente esta secuencia de consolidación.

Por tanto, **la auditoría anterior no debe tomarse como fotografía del estado actual**.

Sin embargo, todavía quedan defectos reales.

Los que considero más importantes ahora mismo son:

1. **`ModuleGraph` no detecta el self-cycle que su propio algoritmo/documentación dice detectar.**
2. **`RateLimiter` mantiene una carrera potencial con el purger.**
3. **`HttpTransport` todavía tiene una protección SSRF incompleta frente a DNS multi-address/rebinding.**
4. **WebSocket limita caracteres, no bytes, pese a documentar el límite como bytes.**
5. **El proyecto mantiene una cantidad considerable de wiring/configuración duplicada.**
6. **`node_modules` está dentro del árbol Git**, lo que es una anomalía seria de higiene del repositorio.
7. **La arquitectura de módulos es mucho más avanzada que el sistema de integración/build que la rodea.**
8. **La escalabilidad network-level está arquitectónicamente encaminada, pero no demostrada mediante benchmarks equivalentes.**

---

# 2. Cobertura

Se examinó:

* árbol completo del repositorio;
* estructura `src/`;
* módulos Java;
* `core-api`;
* kernel/module resolution;
* host;
* iFlow;
* TextFormatter;
* traducción;
* transporte HTTP;
* sync HTTP/TCP/UDP/WebSocket/Discord/Telegram/Velocity;
* manager API/implementation;
* extension API;
* observabilidad;
* performance;
* loadtest;
* Spigot/Fabric;
* Web Editor;
* Gradle;
* CI/CD;
* verification metadata;
* README;
* PLAN;
* ADR;
* auditorías históricas;
* commits recientes.

El repositorio actualmente contiene además `src/web-editor/node_modules`, que aparece efectivamente en el árbol Git.

No ejecuté personalmente el build desde el runtime de esta auditoría. Por ello:

* los resultados declarados por commits se consideran **evidencia documental del proceso de desarrollo**;
* no los presento como benchmark independiente;
* el estado actual de CI tampoco puede darse por verificado para HEAD porque el commit actual no tiene status checks asociados.

El workflow CI actual, sin embargo, está configurado para compilar prácticamente todos los módulos Java, el plugin Spigot y ejecutar los tests del Web Editor. [CI](https://github.com/majhrs16-official/TextFormatter-Suite/blob/main/.github/workflows/ci.yml)

---

# 3. Arquitectura real

La arquitectura actual puede resumirse así:

```text
                  ┌─────────────────────┐
                  │ Spigot / Fabric     │
                  │ Platform API        │
                  └──────────┬──────────┘
                             │
                        Adapter layer
                             │
                             ▼
                     ┌──────────────┐
                     │   SuiteHost  │
                     └──────┬───────┘
                            │
          ┌─────────────────┼──────────────────┐
          ▼                 ▼                  ▼
       iFlow          TextFormatter       Translation
          │                 │                  │
          └─────────────────┼──────────────────┘
                            ▼
                     MessageDispatcher
                            │
                ┌───────────┼────────────┐
                ▼           ▼            ▼
             Chat       Sync sinks    External
           delivery                  integrations
```

Paralelamente existe:

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
          ┌────────────┼────────────┐
          ▼            ▼            ▼
      contract       JVM       capabilities
      checking      checking      / deps
```

Esto es importante:

### La modularidad es real.

No se limita a:

```text
domain/
application/
infrastructure/
```

con nombres bonitos.

Existe realmente:

* SPI;
* `ServiceLoader`;
* adapters;
* module descriptors;
* dependency resolution;
* capability checking;
* platform boundaries;
* API/core separado de implementación.

---

# 4. La decisión de `Module` es particularmente buena

La semántica actual es:

```text
Module
   =
descriptor / SPI
```

y no:

```text
Module
   =
objeto de servicio que SuiteBootstrap instancia
```

Eso evita convertir el sistema de módulos en un contenedor DI/reflection improvisado.

Es probablemente una de las decisiones arquitectónicas más importantes del proyecto.

El `core-api` puede permanecer relativamente limpio mientras los entry points reales de plataforma hacen la composición.

---

# 5. Clean Architecture

## **7.8/10**

La implementación es bastante buena, pero todavía no perfectamente limpia.

La mejora más importante reciente fue precisamente eliminar:

```text
host
 ├── new GTranslate(...)
 └── new LTranslate(...)
```

y sustituirlo por:

```text
host
   │
   ▼
TranslatorProvider
   │
   ├── Google implementation
   └── LibreTranslate implementation
```

Esto está implementado mediante `ServiceLoader`.

Es una mejora arquitectónica real, no cosmética.

### Lo que está bien

`core-api` permanece relativamente independiente de:

* Bukkit;
* Fabric;
* HTTP concreto;
* Google;
* LibreTranslate.

### Lo que sigue siendo mejorable

Hay zonas de wiring y configuración que todavía concentran conocimiento de infraestructura.

Además, el proyecto conserva varias representaciones del schema/configuración:

```text
schema-v2.2.md
paths.json
JS model
ConfigLoader paths
editor model
```

Esto genera riesgo de drift.

---

# 6. Hexagonal Architecture

## **8.2/10**

Aquí el proyecto es todavía más fuerte.

Hay ports/adapters reales:

```text
              ┌────────────────────┐
              │    Application     │
              │       Core         │
              └─────────┬──────────┘
                        │
                    Ports/SPI
                        │
        ┌───────────────┼────────────────┐
        ▼               ▼                ▼
     Spigot           Fabric          Sync
     adapter          adapter        adapters
```

Especialmente buenas:

* `ChatDelivery`;
* `ActorDirectory`;
* `Translator`;
* `TranslatorProvider`;
* `SyncSink`;
* `SyncListener`;
* placeholder SPI;
* platform adapters.

La infraestructura puede cambiar sin convertir el dominio en código Bukkit/Fabric.

Eso es **hexagonalidad real**.

---

# 7. Modularidad

## **8.5/10**

Los módulos actuales incluyen, entre otros:

```text
core-api
kernel
host
iflow
textformatter
gtranslate
ltranslate
transport
sync-http
sync-tcpudp
sync-discord
sync-telegram
sync-websocket
sync-velocity
observability
extension-api
manager-api
manager-impl
presets
inworld
loadtest
performance
tester
messages
spigot-host
fabric-host
web-editor
```

Eso ya es una plataforma.

### Problema

La frontera de módulos es mejor que la integración del build.

El `settings.gradle` actual sí incluye los módulos, pero la arquitectura de publicación y consumo todavía depende bastante de:

* Maven local;
* artifacts;
* wiring Gradle;
* múltiples builds;
* configuración repetida.

La siguiente evolución lógica sería una integración de build más cohesionada, no necesariamente fusionar módulos.

---

# 8. Flujo principal

Un flujo de chat típico puede conceptualizarse como:

```text
Minecraft event
      │
      ▼
Platform adapter
      │
      ▼
Message
      │
      ▼
MessageDispatcher
      │
      ├── source language
      │
      ▼
iFlow / routing
      │
      ├── permissions
      ├── conditions
      ├── transforms
      ├── redirects
      └── rate limiting
      │
      ▼
recipient expansion
      │
      ▼
translation
      │
      ▼
template / formatting
      │
      ▼
delivery
      │
      ▼
Spigot / Fabric / Sync
```

Eso es una pipeline razonablemente limpia.

La característica más importante es que **la plataforma no es el centro del procesamiento**.

El mensaje es el centro.

Eso permite:

```text
Spigot
Fabric
Discord
Velocity
HTTP
WebSocket
```

sin tener que reescribir el motor.

---

# 9. `Message`

## **8.7/10**

La transición hacia mensajes inmutables fue una decisión correcta:

```java
message.withText(...)
message.withChannel(...)
message.withCancelled(...)
message.withLangTarget(...)
```

en lugar de modificar el objeto compartido.

También existe `Builder.from()`.

Esto reduce considerablemente los problemas de:

* shared mutable state;
* carreras;
* efectos laterales;
* debugging.

Para un sistema de procesamiento concurrente de mensajes es exactamente la dirección adecuada.

---

# 10. iFlow

## **8.2/10**

iFlow es probablemente una de las partes con mayor potencial.

No es simplemente:

```text
if → else → formatter
```

La representación de reglas como grafo permite:

```text
input
  │
  ▼
condition
  ├──── yes ───► transform
  │
  └──── no ────► redirect
```

incluyendo fan-out, loops controlados y transformaciones.

La introducción de límites como `max-steps` es importante.

### Riesgo

La complejidad de iFlow puede crecer muy rápidamente.

El motor debe seguir evitando que:

```text
rule
 ↓
rule
 ↓
rule
 ↓
...
```

se convierta en una máquina de ejecución arbitraria.

El límite de pasos es una buena defensa.

---

# 11. BUG-01 — self-cycle en `ModuleGraph`

**Tipo:** Bug
**Severidad:** Media
**Confianza:** **Confirmado**

El código documenta:

> “A singleton with a self-edge is a cycle.”

Pero `strongConnect()` contiene:

```java
if (other == module) {
    continue;
}
```

Por tanto:

```text
A → A
```

no se convierte jamás en una arista para Tarjan.

Además, el componente singleton solo se añade como ciclo si:

```java
component.size() > 1
```

por lo que incluso una SCC singleton con self-edge no terminaría marcada como `CYCLE`.

Esto es un bug genuino, porque contradice explícitamente el contrato del algoritmo.

### Solución

Detectar:

```java
if (other == module && latent(module, module, active, available)) {
    // self-cycle
}
```

y permitir que el componente singleton sea clasificado como ciclo.

**Prioridad: P1.**

---

# 12. BUG-02 — carrera potencial en `RateLimiter`

**Tipo:** Bug
**Severidad:** Media
**Confianza:** Probable/confirmable por análisis estático

La estructura actual es:

```java
Bucket bucket = buckets.computeIfAbsent(key, ...);

synchronized (bucket) {
    ...
}
```

mientras el purger hace:

```java
buckets.entrySet().removeIf(...)
```

Es posible conceptualmente:

```text
Thread A                 Thread B

computeIfAbsent(A)
                         purgeIdle()
                         remove(A)

use old Bucket
                         computeIfAbsent(A)
                         create new Bucket
```

Durante un intervalo pueden existir dos buckets correspondientes a la misma clave lógica.

Esto puede producir una ventana en la que el rate limit efectivo no representa exactamente el estado esperado.

No lo considero un fallo catastrófico; sí un problema de precisión bajo concurrencia.

**Prioridad: P2**, salvo que benchmarks demuestren que afecta a la semántica del limiter.

---

# 13. HTTP inbound

Aquí hubo una corrección muy buena.

La versión anterior utilizaba:

```java
readAllBytes()
```

sin límite.

La versión actual establece:

```java
MAX_BODY_BYTES = 1024 * 1024
```

y además:

* inspecciona `Content-Length`;
* utiliza lectura limitada;
* tiene executor acotado;
* hace shutdown del executor.

Por tanto los antiguos hallazgos de:

```text
unbounded request body
+
unbounded thread pool
```

ya no deben considerarse bugs actuales.

Esto es un ejemplo claro de por qué una auditoría tiene que mirar Git y no solamente repetir hallazgos antiguos.

---

# 14. WebSocket

También ha mejorado significativamente.

Ahora existe:

```text
64 KiB max message
100 messages/sec/connection
```

y limpieza del estado al cerrar conexiones.

### Defecto menor

El código usa:

```java
message.length()
```

pero comunica el límite como:

```text
bytes
```

`String.length()` mide unidades UTF-16, no bytes.

Por tanto:

```text
MAX_MESSAGE_SIZE = 64 KiB
```

no significa realmente 64 KiB de payload UTF-8.

Esto es un **defecto de precisión**, no una vulnerabilidad crítica.

Si se quiere imponer un límite de wire bytes:

```java
message.getBytes(StandardCharsets.UTF_8).length
```

debería ser la medida.

---

# 15. BUG-03 — autenticación WebSocket opcional

**Tipo:** Riesgo de seguridad/configuración
**Severidad:** Media
**Confianza:** Confirmado

El servidor acepta conexiones cuando:

```java
authToken == null
```

o está vacío.

El propio código imprime:

```text
SECURITY: WebSocket server running without auth token!
```

Esto parece intencional para instalaciones locales, así que **no lo considero automáticamente un bug**.

Pero para un servidor que escucha en una interfaz accesible externamente, significa:

```text
WebSocket
    ↓
sin token
    ↓
cliente externo
    ↓
subscribe / message
```

Por ello la configuración debería distinguir claramente:

```text
local development
```

de:

```text
production / externally bound
```

y posiblemente negarse a arrancar externamente sin autenticación.

---

# 16. SSRF

La evolución aquí también es positiva.

Actualmente:

```java
setInstanceFollowRedirects(false)
```

y los redirects se procesan manualmente.

Cada redirect vuelve a pasar por:

```java
validateUrl(newUrl)
```

Esto corrige el problema anterior de:

```text
allowed.example
      ↓ 302
127.0.0.1
```

### Pero queda una limitación

`InetAddress.getByName(host)` devuelve una dirección concreta.

Para una protección SSRF fuerte sería preferible analizar **todas** las direcciones resultantes de:

```java
InetAddress.getAllByName(host)
```

porque un hostname puede resolver a:

```text
PUBLIC_IP
PRIVATE_IP
```

y el comportamiento puede depender de cuál termine utilizando la conexión.

Esto es especialmente relevante en entornos donde DNS está bajo control de terceros.

---

# 17. BUG-04 — DNS rebinding / multi-address SSRF

**Tipo:** Vulnerabilidad potencial
**Severidad:** Media/Alta según exposición
**Confianza:** Probable

La secuencia:

```text
validate DNS
     ↓
getByName()
     ↓
public address
     ↓
DNS changes / alternative address
     ↓
connection
```

no tiene una garantía criptográfica de que la dirección validada sea exactamente la dirección conectada.

Para un sistema que acepta URLs configurables por usuarios o módulos, una defensa más fuerte requeriría:

* resolver todas las IP;
* rechazar cualquier conjunto que contenga destinos prohibidos;
* preferiblemente conectar contra la IP validada;
* conservar `Host`/TLS SNI correctamente.

**Prioridad: P1 si `HttpTransport` recibe URLs controladas por usuarios. P2 si solo administra configuración confiable.**

---

# 18. Traducción

La arquitectura actual ha mejorado.

Antes:

```text
host → GTranslate
host → LTranslate
```

Ahora:

```text
host
 │
 ▼
TranslatorProvider
 │
 ├── Google
 └── Libre
```

Eso es exactamente el tipo de evolución que se esperaba.

Además se agregó caché/deduplicación en una mejora reciente:

```text
from | to | textHash
```

y cache de detección de idioma.

Esto tiene mucho sentido para una red Minecraft porque el patrón:

```text
100 jugadores
+
10 mensajes iguales
+
varios idiomas
```

puede multiplicar brutalmente llamadas externas.

---

# 19. Rendimiento

## **7.5/10 arquitectónicamente**

Hay buenas decisiones:

* executors acotados;
* caché de traducción;
* caché de language detection;
* rate limiting;
* procesamiento concurrente;
* objetos de mensaje inmutables;
* observabilidad;
* profiler;
* loadtest module.

### Pero no hay suficiente evidencia para afirmar:

```text
X messages/sec
```

o:

```text
p99 = X ms
```

para network-scale.

Eso requiere benchmarks reales.

---

# 20. Coste potencial de un mensaje

Un mensaje puede atravesar:

```text
routing
→ conditions
→ formatting
→ translation
→ serialization
→ delivery
```

El peor caso no es el mensaje local.

Es:

```text
1 mensaje
× N recipients
× M idiomas
× traducción externa
× formatting
× sync
```

La caché de traducción reduce una de las dimensiones más peligrosas.

Pero el proyecto todavía necesita medir específicamente:

```text
cost/message
cost/recipient
cost/translation miss
cost/translation hit
allocations/message
```

---

# 21. Escalabilidad tipo network

## Escenario A — servidor pequeño

Arquitectónicamente:

```text
<100 jugadores
```

no representa un problema especial.

El sistema tiene suficiente margen.

---

## Escenario B — red mediana

```text
100–500 jugadores
```

El diseño sigue siendo razonable.

Los riesgos principales pasan a ser:

* traducciones;
* sincronización;
* bursts;
* conexiones externas;
* GC.

---

## Escenario C — red grande

```text
500–2000+ jugadores
```

La arquitectura empieza a depender mucho más de:

```text
translation cache
+
executor sizing
+
sync topology
+
serialization
+
network latency
```

Aquí ya no basta con que el código sea correcto.

Hay que medir.

---

## Escenario D — network-scale

Para una red comparable conceptualmente a grandes redes Minecraft:

```text
múltiples servidores
      ↓
múltiples procesos
      ↓
múltiples regiones/instancias
      ↓
sync
      ↓
bursts
```

el proyecto **tiene una arquitectura que podría servir de base**, pero no existe evidencia suficiente para afirmar que el implementation actual soporte esa carga.

La limitación no es necesariamente el algoritmo de formatting.

Es la suma:

```text
external I/O
+
translation
+
cross-server sync
+
serialization
+
delivery
+
failure handling
```

---

# 22. Concurrencia

## **7.8/10**

La dirección es buena.

Hay:

* `ConcurrentHashMap`;
* immutable messages;
* bounded executors;
* futures;
* scheduler;
* async dispatch;
* main-thread hops para plataforma.

La principal preocupación es la frontera entre:

```text
async engine
```

y:

```text
platform APIs
```

Esta frontera está bastante bien encapsulada.

`SpigotChatDelivery` es precisamente el sitio correcto para resolver:

```text
worker thread
       ↓
Bukkit main thread
```

---

# 23. Seguridad

## **7.3/10**

La situación ha mejorado muchísimo.

### Bien

* HTTP body limit.
* HTTP executor limit.
* WebSocket message limit.
* WebSocket rate limit.
* SSRF redirect validation.
* SHA-256 module verification.
* manifest validation.
* safer YAML constructors en las zonas corregidas.
* authentication support.
* localhost restriction del debug endpoint de auditorías anteriores.

### Pendiente importante

**SpEL / expression evaluation.**

Este continúa siendo el componente que más merece una revisión de seguridad.

La pregunta crítica no es:

> "¿SpEL es peligroso?"

sino:

> "¿Qué objetos, métodos, beans, properties y evaluators están realmente expuestos al contexto de evaluación?"

Si el contexto permite reflection o acceso a APIs arbitrarias, el problema puede convertirse en ejecución de código.

La auditoría de ese componente debería ser específica y separada.

---

# 24. Testing

La estrategia es bastante buena para un proyecto de este tipo:

```text
unit
integration
web editor tests
configuration tests
module tests
loadtest
```

También existe CI para:

* Java;
* Spigot;
* Web Editor;
* integración;
* Javadoc.

El commit `910b623` documenta explícitamente:

```text
core + sync + spigot-host jar: BUILD SUCCESSFUL
core tests: BUILD SUCCESSFUL
```

pero esto sigue siendo evidencia del commit, no una ejecución independiente de esta auditoría.

### Falta importante

Faltan tests E2E que recorran:

```text
Minecraft event
 → dispatcher
 → iFlow
 → translation
 → formatter
 → delivery
```

como una sola pipeline.

Ese es probablemente el hueco de testing más importante.

---

# 25. CI/CD

El workflow actual es mucho más sólido que el de septiembre anterior.

Especialmente:

```text
CI
 ├── Java
 ├── Spigot
 ├── Web editor
 └── Javadoc
```

y release:

```text
build
 ↓
JARs
 ↓
SHA256
 ↓
GitHub Release
```

Esto además conecta correctamente con el Module Manager.

### Problema

El job:

```yaml
./gradlew dependencies --write-locks
continue-on-error: true
```

no constituye realmente una verificación de dependency locking.

Está generando/escribiendo locks y tolerando fallos.

Sería mejor que CI verificara:

```text
lockfile unchanged
+
dependency verification valid
```

en lugar de tratar el proceso como advisory.

---

# 26. Repositorio: `node_modules`

Este es un defecto muy claro.

El árbol Git contiene:

```text
src/web-editor/node_modules/
```

incluyendo una gran cantidad de archivos de dependencias.

Eso debería normalmente ser:

```text
.gitignore
node_modules/
```

y únicamente:

```text
package.json
package-lock.json
```

versionados.

No afecta directamente al runtime de TextFormatter, pero sí:

* aumenta el repositorio;
* dificulta navegación;
* introduce ruido en auditorías;
* aumenta coste de clones;
* puede producir diffs enormes;
* mezcla código propio con dependencias vendorizadas.

**P2.**

---

# 27. Documentación y onboarding

## **8.0/10**

La documentación existente es abundante:

* README;
* Wiki;
* ADR;
* PLAN;
* prompts;
* auditorías;
* schema;
* developer guide;
* API reference;
* migration guide;
* contributing;
* glossary.

Eso está por encima de lo habitual.

El problema es diferente:

> **hay mucha documentación, pero no necesariamente una única ruta cognitiva para entender el código.**

Precisamente aquí tu idea anterior del `codeguide` tiene muchísimo sentido.

Algo como:

```text
src/
└── README.md

core-api/
└── README.md

kernel/
└── README.md

host/
└── README.md

iflow/
└── README.md

textformatter/
└── README.md
```

podría reducir drásticamente el tiempo de onboarding.

---

# 28. Onboarding estimado

### 30 minutos

Un desarrollador puede entender:

```text
qué es TextFormatter
qué módulos existen
qué plataformas soporta
qué es Message
qué es SuiteHost
```

### 2 horas

Puede empezar a seguir:

```text
platform event
→ Message
→ dispatcher
→ routing
→ delivery
```

### 1 día

Puede trabajar razonablemente sobre:

* un módulo;
* una feature;
* un adapter;
* configuración.

### 1 semana

Con codeguide + tests + debugging debería poder modificar partes significativas del sistema sin depender continuamente del autor.

Sin ese índice arquitectónico, el repositorio es considerablemente más difícil de recorrer de lo que su diseño merece.

---

# 29. Historial de Git

La evolución reciente es una señal positiva.

El patrón ha sido:

```text
auditoría
 ↓
bugs identificados
 ↓
refactor
 ↓
compile
 ↓
security hardening
 ↓
build stabilization
 ↓
performance improvements
 ↓
release infrastructure
```

Los últimos commits muestran una consolidación bastante clara:

```text
Performance fixes
        ↓
security fixes
        ↓
translation cache
        ↓
CI/release
        ↓
build stabilization
```

No parece que el proyecto esté simplemente agregando features sin resolver deuda.

Está entrando en una etapa diferente:

> **consolidación de infraestructura.**

Eso es bueno.

---

# 30. Deuda técnica principal

| ID  | Área                        |  Severidad | Estado          |
| --- | --------------------------- | ---------: | --------------- |
| D01 | Schema/config duplicado     |      Media | Vivo            |
| D02 | Build/composite integration |      Media | Vivo            |
| D03 | `node_modules` versionado   |      Media | Vivo            |
| D04 | E2E pipeline Java           |      Media | Vivo            |
| D05 | SpEL security model         |       Alta | Pendiente       |
| D06 | DNS/SSRF hardening          | Media/Alta | Parcial         |
| D07 | ModuleGraph self-cycle      |      Media | Bug             |
| D08 | RateLimiter purge race      |      Media | Riesgo          |
| D09 | WebSocket bytes vs chars    |       Baja | Defecto         |
| D10 | Network-scale benchmarks    |       Alta | Falta evidencia |

---

# 31. Matriz de puntuación

| Área                            |     /10 | Estado                                        |
| ------------------------------- | ------: | --------------------------------------------- |
| Código                          | **8.0** | Bueno                                         |
| Arquitectura                    | **8.5** | Muy buena                                     |
| Clean Architecture              | **7.8** | Buena                                         |
| Hexagonal                       | **8.2** | Muy buena                                     |
| Modularidad                     | **8.5** | Muy buena                                     |
| Separación de responsabilidades | **8.0** | Buena                                         |
| Legibilidad                     | **7.8** | Buena                                         |
| Features                        | **8.5** | Muy amplia                                    |
| Personalización                 | **8.7** | Muy alta                                      |
| Seguridad                       | **7.3** | Mejorando, aún con riesgos                    |
| Rendimiento                     | **7.5** | Buena arquitectura, evidencia limitada        |
| Escalabilidad                   | **7.4** | Potencial alto, no demostrado a network-scale |
| Concurrencia                    | **7.8** | Buena                                         |
| Testing                         | **7.8** | Buena, E2E incompleto                         |
| Mantenibilidad                  | **7.7** | Buena, schema/build generan deuda             |
| Documentación                   | **8.0** | Amplia, falta mapa de código                  |

---

# 32. Potencial

### Potencial teórico

**Muy alto.**

La arquitectura permite convertirse en algo bastante más grande que un formatter:

```text
Minecraft messaging platform
        +
routing engine
        +
translation
        +
cross-server transport
        +
extension platform
        +
configuration editor
        +
runtime module manager
```

---

### Potencial arquitectónico

También es alto.

La estructura actual no obliga a quedarse en:

```text
Spigot plugin
```

Puede evolucionar hacia:

```text
Spigot
Fabric
Velocity
standalone service
proxy
bridge
external integration
```

sin destruir el core.

---

### Potencial alcanzado

Mi estimación:

**~65–75% del potencial arquitectónico**, pero con una distinción importante:

```text
arquitectura diseñada       ~80–85%
implementación funcional    ~70–75%
madurez production-scale    ~50–60%
```

No significa que "falte la mitad del proyecto".

Significa que las partes difíciles que quedan son precisamente las que convierten una arquitectura buena en una plataforma extremadamente robusta:

* seguridad profunda;
* failure semantics;
* E2E;
* benchmarks;
* compatibilidad;
* releases;
* operación a escala.

---

# 33. Roadmap

## P0

### P0-1 — cerrar cualquier regresión de build

El proyecto ha mejorado enormemente aquí. El commit `910b623` declara los módulos principales compilando correctamente.

Hay que convertir eso en una propiedad comprobada permanentemente por CI.

---

## P1

### P1-1 — auditoría profunda de SpEL

Determinar exactamente:

```text
qué puede leer
qué puede invocar
qué reflection existe
qué beans existen
qué context se utiliza
```

y construir tests de seguridad.

### P1-2 — hardening SSRF

Pasar de:

```text
getByName()
```

a una política que controle correctamente múltiples IPs y DNS.

### P1-3 — E2E

Test:

```text
event
→ Message
→ iFlow
→ translation
→ formatting
→ delivery
```

---

## P2

### P2-1 — arreglar self-cycle de ModuleGraph

Cambio pequeño, beneficio claro.

### P2-2 — revisar race de RateLimiter

No requiere reescritura.

### P2-3 — eliminar `node_modules` del repositorio

Cambio sencillo.

### P2-4 — centralizar schema

Idealmente:

```text
schema source
      │
 ┌────┼────┐
 ▼    ▼    ▼
Java  JS   docs
```

generados desde una única fuente.

---

## P3

### P3-1 — codeguide por módulo

Muy recomendable.

### P3-2 — documentación automática de arquitectura

Generar:

```text
module graph
dependency graph
entry points
SPI
```

a partir del código/build.

---

# 34. Qué NO tocaría

Hay varias cosas que **no justificaría reescribir**.

### 1. `Message` inmutable

Mantener.

### 2. `Module` como descriptor SPI

Mantener.

### 3. `ServiceLoader`

Mantener.

### 4. separación core/platform

Mantener.

### 5. `ChatDelivery` como boundary

Mantener.

### 6. bounded executors

Mantener.

### 7. translation cache

Mantener.

### 8. iFlow como grafo

No lo reemplazaría por un sistema de reglas lineales solamente por simplicidad.

### 9. Web Editor schema-first

Mantener.

---

# 35. Conclusiones directas

### 1. ¿Qué tan bueno es realmente TextFormatter Suite?

**Es técnicamente un proyecto bastante serio, especialmente considerando que todavía está en desarrollo.**

Su arquitectura está claramente por encima de la típica arquitectura de plugin Minecraft monolítico.

### 2. ¿Qué está excepcionalmente bien diseñado?

Principalmente:

```text
core-api
Module/SPI
platform adapters
Message
iFlow
ServiceLoader
module manager
separación de infraestructura
```

### 3. ¿Qué partes son mediocres?

Principalmente:

```text
config/schema duplication
build integration
algunos detalles de concurrencia
testing E2E
seguridad avanzada
```

### 4. ¿Peor defecto actual?

No es una única clase.

Es la **diferencia entre sofisticación arquitectónica y evidencia operacional**.

El proyecto tiene arquitectura para hacer cosas grandes, pero todavía necesita demostrar que esas cosas funcionan correctamente bajo carga, fallos y upgrades.

### 5. ¿Mayor fortaleza arquitectónica?

La separación:

```text
message engine
       ↓
ports/SPI
       ↓
platform/infrastructure
```

Eso es lo que realmente permite que TextFormatter sea Suite y no simplemente otro plugin.

### 6. ¿Mayor riesgo futuro?

La complejidad.

Especialmente:

```text
iFlow
+
dynamic modules
+
SpEL
+
sync
+
translation
+
configuration
```

pueden generar una superficie combinatoria enorme.

### 7. ¿Mantenibilidad?

**Buena actualmente**, con riesgo de deteriorarse si no se centraliza schema/config y no se mejora el mapa de código.

### 8. ¿Extensibilidad?

**Muy alta.**

Probablemente una de las características más fuertes del proyecto.

### 9. ¿Multi-plataforma?

**Arquitectónicamente sí.**

El core está correctamente orientado hacia adapters.

### 10. ¿Preparado para una gran network?

**Como arquitectura base, sí. Como sistema network-scale demostrado, todavía no.**

### 11. ¿Qué falta para network-level?

Principalmente:

```text
benchmarks reales
failure testing
backpressure formal
observabilidad de p95/p99
topología de sync
control de traducción externa
load testing distribuido
E2E
```

### 12. ¿Qué no debería tocarse?

Core API, Message, SPI, Module descriptor model y platform boundaries.

### 13. ¿Qué haría primero?

En este orden:

```text
1. SpEL security audit
2. E2E pipeline tests
3. ModuleGraph self-cycle
4. RateLimiter concurrency review
5. SSRF/DNS hardening
6. centralización del schema
7. eliminar node_modules del Git
8. benchmarks reproducibles
9. codeguide
```

### 14. ¿Madurez técnica aproximada?

Mi estimación global del **estado actual**, no del potencial:

> **~75% de madurez técnica para un proyecto de este tipo.**

Y lo interesante es que el porcentaje no está limitado principalmente por falta de features.

Está limitado por la última milla:

```text
correcto
   ↓
robusto
   ↓
demostrado
   ↓
operable
   ↓
network-scale
```

TextFormatter ya está bastante avanzado en los dos primeros escalones. Los siguientes son los que todavía necesitan evidencia.

---

## Veredicto final

La imagen que sale de esta auditoría es bastante clara:

```text
TextFormatter Suite
        │
        ├── Arquitectura       █████████░  muy fuerte
        ├── Modularidad        █████████░  muy fuerte
        ├── Extensibilidad     █████████░  muy fuerte
        ├── Features           █████████░  muy fuerte
        ├── Código             ████████░░  bueno
        ├── Seguridad          ███████░░░  en consolidación
        ├── Testing            ████████░░  bueno
        ├── Operación          ███████░░░  en desarrollo
        └── Scale proof        ██████░░░░  aún no demostrado
```

**No veo un proyecto que necesite ser reescrito.** Veo un proyecto que necesita **consolidar y demostrar** la arquitectura que ya construyó.

Y hay una señal particularmente positiva en el historial: los problemas graves de la auditoría anterior no se quedaron documentados; durante los últimos días se han ido atacando uno por uno —compilación, seguridad HTTP/WebSocket/SSRF, SPI de traducción, checksums, CI, performance y caché— hasta llegar al HEAD actual. Eso es exactamente el tipo de evolución que uno quiere ver en un proyecto que está pasando de *feature development* a *engineering hardening*. 
