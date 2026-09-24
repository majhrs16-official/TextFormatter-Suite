# CODEGUIDE GENERATION — TEXTFORMATTER SUITE

Genera una **documentación integral de exploración del código (CodeGuide)** para el proyecto **TextFormatter Suite**.

El objetivo de esta documentación NO es explicar detalladamente cómo funciona cada clase ni duplicar los comentarios del código. El objetivo es permitir que un desarrollador humano o un agente de IA pueda **entrar al repositorio, comprender rápidamente su estructura, localizar responsabilidades y saber por dónde empezar a leer o modificar el código**.

La documentación debe construirse **exclusivamente a partir del estado real del repositorio**.

---

# 1. REGLA PRINCIPAL: EL CÓDIGO ES LA FUENTE DE VERDAD

Antes de escribir cualquier documentación:

1. Inspecciona el repositorio completo.
2. Lee el código fuente relevante.
3. Inspecciona todos los módulos, paquetes y subpaquetes.
4. Inspecciona las interfaces y sus implementaciones.
5. Inspecciona las relaciones entre módulos.
6. Inspecciona los entry points reales.
7. Inspecciona las dependencias entre componentes.
8. Inspecciona la configuración de Gradle/Maven y la estructura real del proyecto.
9. Inspecciona tests cuando existan.
10. Inspecciona documentación existente.
11. Inspecciona el historial Git cuando sea útil para resolver ambigüedades arquitectónicas.

NO asumas que la arquitectura coincide con los nombres de los paquetes.

NO documentes una arquitectura idealizada.

NO inventes responsabilidades.

NO inventes dependencias.

NO inventes flujos que no puedan verificarse en el código.

Si existe una contradicción entre documentación existente y código actual, considera el **código actual como fuente de verdad** y documenta la realidad actual.

Si algo no puede determinarse con suficiente confianza, indícalo explícitamente en vez de inventarlo.

---

# 2. OBJETIVO DEL CODEGUIDE

El resultado debe responder rápidamente preguntas como:

* ¿Dónde está cada parte del sistema?
* ¿Qué responsabilidad tiene este módulo?
* ¿Qué NO debería colocarse aquí?
* ¿Qué módulos dependen de cuáles?
* ¿Cómo fluye una operación a través del sistema?
* ¿Dónde empieza una operación?
* ¿Dónde termina?
* ¿Qué clases son importantes?
* ¿Qué clases son infraestructura?
* ¿Qué clases son adapters?
* ¿Dónde se encuentra la lógica de negocio?
* ¿Cómo entra Bukkit al sistema?
* ¿Cómo entra Fabric al sistema?
* ¿Dónde se inicializan los servicios?
* ¿Dónde se registran comandos/listeners/eventos?
* ¿Dónde se encuentra la configuración?
* ¿Dónde se implementan los transports?
* ¿Dónde se implementan los formatos?
* ¿Dónde se implementan las traducciones?
* ¿Qué archivo debería leer primero para entender una determinada feature?
* ¿Qué archivos debería modificar para implementar una nueva feature?
* ¿Qué dependencias están permitidas?
* ¿Qué dependencias serían arquitectónicamente incorrectas?
* ¿Qué partes son compartidas entre plataformas?
* ¿Qué partes son específicas de Bukkit/Fabric?

---

# 3. ESTRUCTURA DE DOCUMENTACIÓN

Crea documentación de navegación en los directorios apropiados.

Como mínimo debe existir un índice principal:

```text
src/README.md
```

Este archivo será el **punto de entrada principal para explorar el código fuente**.

Cuando la estructura real del proyecto lo justifique, crea también README específicos para módulos o áreas relevantes:

```text
src/
├── README.md
├── <module>/
│   ├── README.md
│   └── ...
├── <module>/
│   ├── README.md
│   └── ...
└── ...
```

NO crees README arbitrarios solamente para cumplir una cantidad.

Crea documentación por nivel cuando aporte valor real para la navegación.

La estructura final debe reflejar la estructura REAL del repositorio.

---

# 4. `src/README.md`

El README principal debe funcionar como un **mapa del código**.

Debe incluir, cuando sea aplicable:

## 4.1 Purpose

Explica brevemente qué representa el árbol `src/`.

## 4.2 Architecture at a glance

Describe la arquitectura real observada en el código.

Incluye un diagrama ASCII o Mermaid cuando sea útil.

Ejemplo conceptual:

```text
                    TextFormatter Suite
                           │
             ┌─────────────┴─────────────┐
             │                           │
           Core                       Platform
             │                    ┌──────┴──────┐
             │                    │             │
          Domain              Bukkit         Fabric
             │
       Application
             │
      Infrastructure
```

NO utilices este ejemplo literalmente si no corresponde al código real.

El diagrama debe generarse a partir de las dependencias reales.

## 4.3 Directory map

Genera una tabla similar a:

| Directory | Responsibility | Depends on | Used by |
| --------- | -------------- | ---------- | ------- |
| `...`     | ...            | ...        | ...     |

Incluye solamente información verificable.

## 4.4 Module index

Para cada módulo relevante:

* nombre
* propósito
* responsabilidad principal
* dependencias
* consumidores
* clases principales
* entry points
* enlace a su README

## 4.5 Where should I start?

Incluye rutas de exploración para tareas comunes.

Por ejemplo:

```text
Understand startup
    → ...

Understand message formatting
    → ...

Understand translation
    → ...

Understand configuration
    → ...

Add a new formatter
    → ...

Add a new platform adapter
    → ...

Modify HTTP transport
    → ...
```

Los destinos deben corresponder al código real.

## 4.6 Important entry points

Lista los verdaderos entry points del sistema.

Distingue claramente:

* entry points de plataforma
* bootstrap
* initialization
* dependency wiring
* command registration
* event/listener registration
* service startup

---

# 5. DOCUMENTACIÓN DE CADA MÓDULO

Para cada módulo arquitectónicamente relevante crea una guía corta.

Cada guía debería responder:

## Purpose

¿Por qué existe este módulo?

## Responsibilities

¿Qué responsabilidades tiene realmente?

## Non-responsibilities

¿Qué cosas explícitamente NO deberían pertenecer aquí?

Este apartado es especialmente importante para preservar las fronteras arquitectónicas.

## Dependencies

¿Qué otros módulos consume?

## Consumers

¿Qué otros módulos lo utilizan?

## Main components

Tabla:

| Component   | Role |
| ----------- | ---- |
| `ClassName` | ...  |

No enumeres todas las clases automáticamente.

Incluye las clases relevantes para comprender la arquitectura.

## Data flow

Representa los flujos importantes.

Ejemplo:

```text
Input
  ↓
Component A
  ↓
Component B
  ↓
Component C
  ↓
Output
```

## Entry points

¿Desde dónde se utiliza este módulo?

## Extension points

Si el módulo permite extensibilidad, documenta dónde.

## Exploration path

Indica un orden recomendado de lectura.

Ejemplo:

```text
1. `X.java`
2. `Y.java`
3. `Z.java`
4. `...`
```

La ruta debe ser realmente útil para comprender el módulo.

## Related modules

Incluye enlaces relativos hacia módulos relacionados.

---

# 6. MAPA DE DEPENDENCIAS

Construye un mapa de dependencias basado en el código real.

Cuando sea útil, incluye un diagrama como:

```text
A
├── B
│   └── C
└── D
```

o Mermaid.

Distingue:

* dependencia de compilación
* dependencia de ejecución
* implementación
* interfaz
* adapter
* infraestructura

No confundas simplemente "importa una clase" con una dependencia arquitectónica de alto nivel sin analizar su significado.

---

# 7. FLUJOS IMPORTANTES

Identifica los flujos principales del sistema.

Por ejemplo, si existen:

```text
Minecraft event
    ↓
platform adapter
    ↓
application service
    ↓
domain
    ↓
formatter
    ↓
output adapter
```

documenta el flujo real.

Busca especialmente:

* startup
* configuración
* procesamiento de mensajes
* formatting
* translation
* synchronizers
* HTTP
* webhooks
* comandos
* eventos
* integración con plataformas
* persistencia
* serialización
* cualquier pipeline central

No inventes flujos.

Si una operación tiene varios caminos posibles, documenta los caminos relevantes.

---

# 8. ARCHITECTURAL BOUNDARIES

Identifica las fronteras arquitectónicas existentes.

Para cada frontera importante explica:

```text
Boundary
Purpose
Allowed direction
Forbidden direction
```

Ejemplo:

```text
Domain → Platform

Allowed:
Platform may depend on Domain.

Forbidden:
Domain must not depend on Bukkit/Fabric.
```

IMPORTANTE:

No declares una dependencia como "prohibida" solamente porque Clean Architecture normalmente la prohíbe.

Determina primero si realmente existe esa regla en la arquitectura del proyecto.

Si el código actual viola una frontera que aparentemente se pretende mantener, documenta el hecho como:

```text
Observed architectural issue
```

en lugar de falsificar la arquitectura.

---

# 9. PLATFORM INTEGRATIONS

Documenta específicamente las integraciones de plataforma.

Para cada plataforma:

* entry point
* initialization
* service creation
* dependency wiring
* command registration
* listener/event registration
* adapters
* platform-specific implementations
* interaction with shared/core code

Debe quedar claro qué código es:

```text
shared/core
```

y qué código es:

```text
Bukkit-specific
Fabric-specific
```

No mezcles ambos.

---

# 10. MODULE SYSTEM

Si el proyecto posee un sistema de módulos, documenta su comportamiento real.

Determina:

* qué representa un módulo
* cómo se declara
* cómo se descubren
* cómo se resuelven dependencias
* cómo se inicializan
* quién los instancia
* qué ciclo de vida tienen
* qué relación existe entre descriptor y servicio real

Presta especial atención a no asumir que un descriptor de módulo es necesariamente una instancia del servicio que representa.

Documenta específicamente el comportamiento real de `Module.java` y cualquier sistema relacionado.

---

# 11. CONFIGURATION

Documenta:

* dónde se define el schema
* dónde se parsea
* dónde se valida
* dónde se almacena
* quién consume la configuración
* cómo llega la configuración a los servicios
* qué partes son específicas de plataforma

Si existen múltiples representaciones del schema, documenta sus relaciones.

Si existe duplicación, no la ocultes.

---

# 12. EXTENSION / FEATURE DEVELOPMENT GUIDE

Incluye una sección que permita responder:

> "Quiero agregar una nueva feature. ¿Dónde empiezo?"

Proporciona rutas concretas para casos reales.

Ejemplo conceptual:

```text
Adding a new formatter
    1. ...
    2. ...
    3. ...

Adding a new synchronizer
    1. ...
    2. ...
    3. ...

Adding a new Bukkit integration
    1. ...
    2. ...
    3. ...

Adding a new Fabric integration
    1. ...
    2. ...
    3. ...
```

Solamente documenta procedimientos que puedan deducirse del código existente.

---

# 13. CODE EXPLORATION GUIDE

Incluye una sección explícitamente orientada a desarrolladores nuevos.

Debe contestar:

### "I want to understand..."

Para cada área importante:

```text
I want to understand X
    ↓
Read A
    ↓
Then B
    ↓
Then C
```

### "I want to modify..."

Para cada área importante:

```text
I want to modify X
    ↓
Start at A
    ↓
Likely extension point: B
    ↓
Platform-specific integration: C
```

Esta sección debe ser una de las partes más prácticas del CodeGuide.

---

# 14. CODEOWNERS MENTALES

No necesitas crear `CODEOWNERS`.

En cambio, identifica conceptualmente qué área "posee" cada responsabilidad.

Ejemplo:

```text
Configuration
    ├── schema → ...
    ├── loading → ...
    └── consumption → ...

Formatting
    ├── domain → ...
    ├── application → ...
    └── platform → ...
```

El objetivo es ayudar a determinar **dónde debería realizarse un cambio**.

---

# 15. HISTORIAL Y ARQUITECTURA

Utiliza Git únicamente cuando ayude a resolver preguntas como:

* ¿por qué existe una separación aparentemente extraña?
* ¿es código legado?
* ¿una clase fue reemplazada?
* ¿existen dos arquitecturas en transición?
* ¿una estructura es intencional o residual?
* ¿existe una migración incompleta?

NO conviertas el CodeGuide en una historia del proyecto.

Git es evidencia secundaria para comprender decisiones; el código actual sigue siendo la fuente principal.

---

# 16. DETECCIÓN DE DISCREPANCIAS

Si encuentras:

* documentación desactualizada
* README que contradice el código
* nombres engañosos
* módulos aparentemente duplicados
* dependencias inesperadas
* clases que realizan responsabilidades de otro módulo
* arquitectura parcialmente migrada
* código muerto
* entry points ambiguos

NO los corrijas silenciosamente en la documentación.

Márcalos claramente.

Ejemplo:

```md
> ⚠️ Architectural discrepancy
>
> The package structure suggests X, but the current implementation
> performs Y. This guide documents the observed implementation.
```

La documentación debe describir la realidad, no maquillarla.

---

# 17. CALIDAD DE LOS ENLACES

Todos los README deben enlazarse entre sí mediante rutas relativas válidas.

Ejemplo:

```md
[Application](../application/README.md)
```

Verifica que los enlaces realmente apunten a archivos existentes.

No generes enlaces ficticios.

---

# 18. NIVEL DE DETALLE

La documentación debe ser:

* suficientemente detallada para explorar el proyecto
* suficientemente compacta para poder mantenerla
* orientada a relaciones
* orientada a responsabilidades
* orientada a navegación
* útil para humanos
* útil para agentes de IA

Evita:

* repetir código
* copiar JavaDoc innecesariamente
* explicar sintaxis obvia
* describir cada getter/setter
* listar cientos de clases irrelevantes
* escribir teoría genérica de Clean Architecture
* inventar intenciones del autor
* llenar archivos con texto sin valor de navegación

Prioriza:

```text
Where?
Why?
Depends on what?
Used by what?
How does data flow?
Where do I start?
Where should I modify it?
What must not cross this boundary?
```

---

# 19. FORMATO

Usa Markdown limpio.

Utiliza:

* headings
* tablas
* listas
* enlaces relativos
* diagramas ASCII
* Mermaid solamente cuando realmente mejore la comprensión

Mantén nombres de clases, paquetes, métodos y archivos en backticks.

Los nombres deben coincidir exactamente con el código actual.

---

# 20. NO MODIFICAR EL CÓDIGO

Esta tarea es exclusivamente documental.

NO modifiques:

* Java
* Kotlin
* Gradle
* configuración
* tests
* recursos
* scripts

Salvo que sea absolutamente necesario crear o actualizar archivos Markdown de documentación.

---

# 21. VALIDACIÓN FINAL

Antes de terminar:

1. Comprueba que todos los módulos relevantes estén cubiertos.
2. Comprueba que las responsabilidades descritas coincidan con el código.
3. Comprueba los diagramas.
4. Comprueba los enlaces.
5. Comprueba los nombres de clases y paquetes.
6. Comprueba que no existan afirmaciones arquitectónicas sin evidencia.
7. Comprueba que no hayas confundido interfaces con implementaciones.
8. Comprueba que los entry points sean los reales.
9. Comprueba que las rutas de exploración sean navegables.
10. Comprueba que la documentación permita a una persona nueva comenzar a explorar el proyecto sin tener que descubrir primero toda la arquitectura por su cuenta.

Finalmente genera un pequeño informe:

```text
CODEGUIDE GENERATION SUMMARY

Files created:
- ...

Files updated:
- ...

Modules documented:
- ...

Major flows documented:
- ...

Entry points documented:
- ...

Architectural boundaries documented:
- ...

Undocumented/ambiguous areas:
- ...

Discrepancies discovered:
- ...
```

El resultado final debe ser un **índice navegable y fiel del código real de TextFormatter Suite**, no una explicación genérica de arquitectura.
