# PLAN — TextFormatter Suite

> **Obligatorio leer y seguir [La Filosofia](/docs/prompts/diseno/philosophy.md)** antes de trabajar en este documento o en el código.
>
> Documento vivo: reléelo antes de cada sesión de trabajo para no perder el rumbo.
> Última actualización: 2026-10-30 (**doble línea paralela: `iflow-vm` (VM propia de 32 bits, con las mejoras de la auditoría) e `iflow-wasm` (WebAssembly + Chicory); paridad funcional VM↔Wasm según spec v3 pendiente (W3.5)**; **T4 completado**: inworld (14 tests neutrales), observability (15 tests), messages (18 tests) — todos con mocks, sin servidor real; antes: auditoría manual de iFlow en curso, solo lectura, sin cambios de código; SpEL aislado en `iflow-firewall` (motor legacy, ADR-001); `iflow-vm`/`iflow-wasm` sin SpEL; ABI de `HOST` v2 cerrada + mapa de memoria preventivo (`VmMemoryMap`), fabric-host completo, Telegram sink SyncBus, config schema single-source, SSRF TOCTOU fix, coverage gates raised, all `AUDITORIA.md` P0/P1/P2 fixed (histórico)).

> **Nota de estado (2026-10-29):** se añade el **compilador C de iFlow VM** como fase **V6** y ADR-023. Es una herramienta offline/CI que emite `.flowasm` v3; no se embebe en el host ni compila C en runtime. Depende de V1 (ISA/ABI v3) y V5 (assembler v3), y su cierre de ejecución de V2/V3/V4.

> **Nota de estado (2026-10-27):** **ADR-019 reescrito** por decisión del usuario: I-019 deja de ser un arreglo de dependencias y pasa a ser una **reestructuración integral** de la suite. El objetivo es un runtime de módulos con carga dinámica, **hot unload/upgrade real**, Manager como orquestador del ciclo de vida y adapters Spigot/Fabric que solo implementan puertos de plataforma. **F0 cerrada, F1 completada, F2 completada como POC, F3 completada, F4a completada, F5 cerrada y F6 en curso**: `core-api` expone la API neutral de ciclo de vida (`ModuleActivator`, `ModuleContext`, `ServiceRegistry`, `EventBus`, `Scheduler`, `ResourceTracker`, `PlatformPorts`, `ModuleState`) y los contratos compartidos entre paquetes (`api.channel`, `api.iflow`), `suite-runtime` carga JARs reales desde `library/packages` con classloader aislado, orden de dependencias y liberación de recursos, `PackageRuntime` conecta instalación con activación manteniendo `library/status` como registro de instalación y `<manager>/runtime/modules.status` como estado transitorio, `SuiteRuntime` es el composition root neutral que orquesta manager, host y comandos sin depender de features, y los seis paquetes base (`textformatter`, `messages`, `chattranslator`, `iflow-vm`, `iflow-firewall`, `iflow-wasm`) se empaquetan como ZIP, se instalan y se activan desde sus `ModuleActivator`. El classloader acepta JARs de paquetes de dependencia desde `library/dependencies`; `logger` es el primer paquete de dependencia real. F6 está en curso: `transport` ya se empaqueta como dependencia, `gtranslate`/`ltranslate` se activan como proveedores de `chattranslator` (que refresca su `TranslatorManager` con los eventos de ciclo de vida) y `presets` ya se empaqueta como `PACKAGE` con `FilePresetTarget` real para config, canales, traductores y sync, `tester` ya es un `PACKAGE` neutral con `TestProbe`, `TestServiceFactory` y adapter Spigot, e `inworld` también es un `PACKAGE` neutral con `InWorldHandler`/`InWorldPlatform` y el listener Bukkit en `spigot-host`. Queda la retirada del cableado del host, F4b, F4c (sincronizadores de plataforma), la retirada de los árboles de comandos de host de F4d y F4e (raíz de comandos relocalizable); la API neutral de F4d ya está implementada en `core-api`/`suite-runtime`. Ver la sección **ADR-019** más abajo y **ADR-021/ADR-022** para las dos mejoras de comandos.

> **Nota de estado (2026-10-27):** `iflow-vm` e `iflow-wasm` no usan SpEL. `iflow-firewall` restaura el motor SpEL histórico por decisión explícita (ADR-001). Los tres motores iFlow son **mutuamente excluyentes**: se instala uno y reemplaza al anterior; comparten el paquete base `me.majhrs16.suite.iflow`. Las menciones a la eliminación de SpEL entre 2026-10-20 y 2026-10-26 se conservan como registro histórico.

> **Nota de estado (2026-10-23):** **iFlow no se abandona ni se elimina.** Se divide en dos líneas paralelas: **`iflow-vm`** (VM propia de 32 bits, con las mejoras de la auditoría) e **`iflow-wasm`** (motor WebAssembly sobre Chicory). La especificación técnica de referencia v3 queda en `docs/prompts/diseno/iflow-cpu-implement.md` y recoge los hallazgos `IFLOW-MAN-*` como requisitos de la línea de 32 bits; **no se hereda** al motor Wasm. Las secciones históricas de este plan se conservan, pero la fuente de verdad es este bloque y el estado real.
>
> **Nota de estado (2026-10-24):** **Primer release (v2.1.0-SNAPSHOT).** No hay versiones previas, ni changelog, ni release notes históricos que acumular. `CHANGELOG.md` y `RELEASE_NOTES.md` fueron eliminados; la trazabilidad comienza ahora con commits convencionales y este `PLAN.md` como fuente única de verdad.

> **Alcance vigente (2026-10-23):** las pruebas reales/E2E siguen descartadas y el editor web sigue diferido a una auditoría separada. La sección «Decisiones de alcance vigentes» es la fuente de verdad para ambas. La auditoría de iFlow se ratifica como especificación técnica de referencia v3 en `docs/prompts/diseno/iflow-cpu-implement.md`; `docs/workdir/AUDITORIA.md` queda vacío como marcador histórico.

---

## DOBLE LÍNEA — 2026-10-23: `iflow-vm` (32 bits) e `iflow-wasm` (Chicory)

### Decisión

**iFlow no se abandona ni se elimina.** Se mantienen dos líneas paralelas que comparten el contrato
de políticas del host, pero no el mismo runtime:

- **`iflow-vm` — VM propia de 32 bits.** Es la evolución de la VM actual (`src/iflow-vm/`), con las
  mejoras pendientes de `docs/prompts/diseno/iflow-cpu-implement.md`: memoria/MMU de 32 bits, ISA `.flow` revisada,
  BIOS/kernel/scheduler, IRQ, tiempo, persistencia y concurrencia. Conserva el compilador
  `tools/iflow-compiler/` y los artefactos `.flow`. No se borra nada de la base actual.
- **`iflow-wasm` — motor WebAssembly.** Es la línea nueva basada en **Chicory**, con artefactos
  `.wasm` portables, instancias aisladas, memoria lineal propia y un ABI de imports acotado. No
  hereda el ISA `.flow`, el ABI `HOST` v2 ni el modelo BIOS/kernel.

Las dos líneas son independientes. Los hallazgos `IFLOW-MAN-*` se incorporan como requisitos de `iflow-vm`; **no se
aplican automáticamente** a `iflow-wasm`. El host podrá seleccionar el motor por configuración
cuando ambas estén listas.

### Regla de exclusividad de los motores iFlow (2026-10-27)

> **Los tres motores — `iflow-vm`, `iflow-wasm` e `iflow-firewall` — son mutuamente excluyentes.**
> Solo uno puede estar instalado en un servidor a la vez. Ocupan el mismo slot de instalación de
> motor iFlow; instalar otro **desinstala/reemplaza** al anterior y no se admite la convivencia en
> el classpath.
>
> Los tres comparten el paquete base `me.majhrs16.suite.iflow` (con subpaquetes propios:
> `.vm`, `.wasm`, `.rule`, etc.). Esto es intencional: el contrato `Router` y los tipos de
> decisión tienen el mismo FQCN en cada motor, y el host se compila contra ese contrato base.
> Al ser excluyentes, no hay colisión en runtime; **no se debe añadir un sufijo de paquete**
> (por ejemplo `.firewall`) ni un módulo `iflow-api` compartido.
>
> Consecuencia operativa: el paquete instalado debe aportar las clases de `me.majhrs16.suite.iflow`
> y el host selecciona el motor con `iflow.engine.kind` (`vm` por defecto, `firewall` para el
> motor legacy). Un despliegue con dos motores a la vez no está soportado. El monorepo compila
> los tres y `./gradlew publishIflowEngines` los publica como paquetes Maven separados en
> `build/repo` (`suite-iflow-vm`, `suite-iflow-wasm` y `suite-iflow-firewall`); el classpath de
> ejecución debe contener exactamente uno, y `SuiteBootstrap` filtra los alternativos si el
> classpath de desarrollo trae varios.

El único pendiente transversal es la **paridad funcional de propiedades del mensaje** (W3.5) contra
la **spec v3**: que `iflow-wasm` implemente el mismo contrato de transformación, decisión y entrega,
no el ABI `HOST` v2 de `src/iflow-vm`. No reactiva el backlog de 32 bits de la VM. Detalle en
«Paridad funcional pendiente».

### Qué se conserva

- El contrato de entrada `Router` / `RouteDecision` / `RouteOutcome` y el resto del pipeline
  (`MessageDispatcher`, `SuiteHost`, `DefaultTextFormatter`, canales, permisos, sync).
- La separación de responsabilidades del host: el guest decide la política; el host ejecuta la
  entrega, aplica permisos y expone servicios acotados.
- La configuración por módulo y el editor web como superficies de autoría.
- Las decisiones de seguridad que no dependen del runtime: sandbox, capabilities, límites duros de
  memoria/tiempo/tamaño, validación de artefactos y verificación de integridad.
- **`src/iflow-vm/` como base de la VM de 32 bits**: código, tests, fixtures y compilador se conservan y
  evolucionan en la línea de 32 bits. **No se borra.**
- **WebAssembly + Chicory** como línea `iflow-wasm`, con módulo propio en `src/iflow-wasm/`.

### Qué no se hereda en `iflow-wasm`

- El ISA `.flow`, el ABI `HOST` v2, el modelo BIOS → bootloader → kernel → userspace, el mapa de
  64 KB, los frames de 4 KB y el fuel como mecanismo de límite del motor.
- Los hallazgos `IFLOW-MAN-*` como deuda del motor Wasm: pertenecen a `iflow-vm`.
- La documentación que presenta iFlow como único motor; se actualiza a la doble línea.

### Arquitectura objetivo de `iflow-wasm` (borrador, sujeta a ADR)

```
Mensaje entrante
  → SuiteHost / MessageDispatcher
  → WasmPolicyEngine (Chicory)
       ├─ instancia Wasm aislada por programa (memoria lineal propia)
       ├─ imports de host acotados: propiedades, estado, canales, entrega, reloj
       ├─ capabilities verificadas antes de invocar
       └─ límites duros: memoria, tiempo de ejecución, tamaño de entrada/salida
  → RouteDecision / RouteOutcome
  → formateo + entrega existentes
```

Reglas de diseño:

1. **Sin WASI de sistema por defecto.** El guest no recibe filesystem, red, procesos ni variables
   de entorno del host. Solo imports explícitos y capability-gated.
2. **Sin estado de máquina compartido.** Cada instancia Wasm tiene su memoria lineal; la
   persistencia entre mensajes, si se necesita, se modela como estado de host explícito.
3. **El guest decide; el host solo aplica límites duros.** Rate limit, backoff y política temporal
   pertenecen a la lógica Wasm; el host puede imponer un cap de seguridad separado.
4. **Artefacto verificable.** El `.wasm` se valida antes de cargar: magic/version, límites de
   memoria, imports permitidos, exports requeridos y hash.
5. **Un solo ABI de host.** Los imports se documentan una vez y se versionan; no se replica el ABI
   `HOST` v2 de iFlow.
6. **Autoría multi-lenguaje.** El panel web puede editar WAT/AssemblyScript o subir un `.wasm`
   compilado; el servidor no ejecuta toolchains nativas arbitrarias.

### Fases de `iflow-vm` (32 bits)

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **V0 — Módulo y spec** | `src/iflow-vm/` renombrado, Gradle/CI/docs actualizados y especificación v3 enlazada | ✅ `:src:iflow-vm:build`, `:src:iflow-wasm:build` y `:src:host:build` verificados con JDK 21 |
| **V1 — Contrato 32 bits** | ADR de ISA, memoria/MMU, IRQ, scheduler, tiempo y ABI `HOST` v3; compatibilidad `.flow` | Contrato congelado antes de tocar el núcleo |
| **V2 — Núcleo** | CPU/MMU/bus de 32 bits, arranque BIOS/kernel, fixtures actualizados | Tests de memoria, privilegios y arranque |
| **V3 — Persistencia y tiempo** | Scheduler, sleep/wake, RTC, quantum, journal y concurrencia | Tests de ejecución persistente y cancelación |
| **V4 — Integración** | `iflow-vm` conectado a `Router`/`MessageDispatcher` | Un mensaje real atraviesa el pipeline con una política `.flow` de 32 bits |
| **V5 — Herramientas de ensamblado** | Assembler `.flowasm` v3, firmware, ejemplos y validador | Build reproducible + tests |
| **V6 — Compilador C** | Compilador C11 freestanding a `.flowasm` v3, `libiflow` y ejemplos | Corpus C compila, ejecuta en VM v3 y produce el mismo resultado observable; tests y determinismo |

#### V6 — Compilador C (ADR-023)

**Estado:** diseño aceptado; implementación pendiente. No se inicia antes de
V1 (ISA/ABI v3) y V5 (assembler v3); la ejecución del corpus requiere V2/V3/V4.
No se implementa contra el ISA v2 de 16 bits ni se reutiliza su ABI `HOST` v2.

**Objetivo.** Permitir que las políticas de iFlow se escriban en C sin perder el
control de bajo nivel del ensamblador. El compilador es una herramienta offline
(`tools/iflow-c/`, CLI `iflow-cc`) escrita en TypeScript; emite `.flowasm` v3 y
el assembler v3 sigue siendo el único codificador y productor de ROM. No se
duplica el formato binario ni la tabla de opcodes.

**Alcance del lenguaje.** Subconjunto C11 freestanding: `void`, `_Bool`,
`char` sin signo, `signed char`, `short`, `int`/`long` de 32 bits, `long long`
de 64 bits, `float` (binary32), `double`/`long double` (binary64), punteros de
32 bits, arrays, `struct`, `union`, `enum`, `typedef`, `const`, `volatile`,
`_Atomic` escalar/puntero, control de flujo completo, funciones,
`static`/`extern`, `goto`, `switch`, `_Static_assert`, macros de
objeto/función, `#include`, condicionales, `#`/`##` y ensamblador inline estilo
GNU (`asm`/`asm volatile` y forma extendida con constraints `r`/`m`/`i`).
`volatile` no se elimina, duplica ni reordena. `_Atomic` soporta cargas,
almacenes, intercambio, CAS y fetch-add/sub/and/or/xor con las órdenes de C11;
los agregados atómicos se revisan en C4.

**Exclusiones explícitas.** `_Complex`, hilos, `setjmp`/`longjmp`, VLA,
bitfields, caracteres anchos, `_Generic`, `typeof`, `asm goto` y funciones
variádicas. Un uso no soportado falla con diagnóstico y ubicación; no se acepta
en silencio ni se degrada. Glosario:
**VLA** = array local de tamaño calculado en runtime (`int a[n]`); **bitfields**
= campos de bits en `struct` (`unsigned f : 3`); **variádicas** = funciones con
`...` y `va_list`; **`setjmp`/`longjmp`** = salto no local con restauración de
contexto; **`_Complex`** = tipos complejos, no confundir con `_Atomic`; **anchos**
= `wchar_t`/`L"..."`; **`_Generic`** = selección por tipo; **`typeof`** =
extensión GNU que devuelve el tipo de una expresión.

**Prioridad de extensiones.** Las exclusiones no son permanentes. Después de V6,
con enmienda de ADR-023 y tests propios:

1. **Variádicas** y **bitfields** primero, por uso común en C. Variádicas exige
   ABI de varargs y `<stdarg.h>`; bitfields exige fijar orden de bits,
   empaquetado, alineación y layout.
2. **VLA** y **`setjmp`/`longjmp`** solo si una política real los necesita. VLA
   exige asignación dinámica de pila y control de desbordamiento;
   `setjmp`/`longjmp` exige guardar/restaurar contexto y definir el unwind.
Los agregados atómicos se revisan antes, en C4.

**ABI y runtime.** La ABI C v3 se congela en V6.0 sobre el ISA v3: little-endian,
pila de 4 bytes (8 para 64 bits), argumentos en pila de derecha a izquierda,
limpieza del llamador, retorno en `R0`/`R1:R0`, GPR caller-saved, `SP`/`FP`
preservados, `struct` por valor con puntero oculto, `char` sin signo y
`size_t` de 32 bits. `float`/`double` se pasan en pila y se retornan en
`R0`/`R1:R0`; el baseline es soft-float y una FPU futura no cambia la ABI.
`libiflow` es una biblioteca freestanding enlazada en el programa: `crt0`,
`mem*`, `str*`, rutinas de 64 bits, helpers soft-float IEEE-754, helpers
atómicos y arena allocator explícito. No hay `stdio`, `printf` ni
`malloc`/`free` estándar hasta que el kernel defina su ABI; lo no implementado
falla al enlazar, no se simula.

**Modelo de programa.** El baseline compila un programa completo (una o varias
unidades `.c` en la misma invocación) a un único `.flowasm` v3. No hay formato de
objeto estable ni linker de objetos separado; si V6.4 lo necesita, se añade con
ADR. El loader inicializa `.data` y zeroa `.bss`; `crt0` llama a `main`, recibe
el estado y ejecuta la salida. No hay constructores globales.

**Optimización.** `-O0` es el default y debe ser correcto antes que rápido.
`-O1` puede aplicar plegado de constantes, propagación local, eliminación de
código muerto y peephole, sin explotar comportamiento indefinido, sin alterar
`volatile` y sin reordenar operaciones atómicas. La coma flotante mantiene
semántica IEEE-754 por defecto; `-ffast-math` no es baseline. La salida es
determinista: mismo fuente, flags y versión producen los mismos bytes y el
mismo orden de símbolos.

**Fases internas.**

| Fase | Entregable | Puerta |
|---|---|---|
| **C0 — Contrato** | Subconjunto, gramática, ABI C v3, modelo de runtime y corpus | ADR y casos congelados; cada exclusión con diagnóstico y test |
| **C1 — Frontend** | Preprocesador, lexer, parser, AST, semántica y diagnósticos | Tests unitarios y negativos; nada no soportado pasa en silencio |
| **C2 — IR y backend** | IR de tres direcciones, lowering, registros y emisión `.flowasm` v3 | Golden tests y ensamblado con el assembler v3 |
| **C3 — ABI y runtime** | `libiflow`, `crt0`, wrappers `HOST` v3, punteros/arrays/structs/64 bits, soft-float, atómicos e inline asm | Ejecución en VM v3 con el corpus; sin stubs |
| **C4 — Optimización y robustez** | `-O1`, peephole, fuzz, determinismo, límites y agregados atómicos | Mismos resultados observables; salida reproducible |
| **C5 — Toolchain** | CLI, ejemplos, documentación, CI Node y paquete | `npm test`, corpus y build reproducible verdes |

**Criterios de cierre.**

1. Al menos tres políticas reales (rate limit, routing de canal y transformación
   de contenido) escritas en C compilan y producen la misma decisión y mensaje
   observable que su referencia.
2. El corpus cubre punteros, arrays, structs, uniones, `switch`, `goto`,
   `volatile`, 64 bits, `float`/`double`, atómicos, inline asm y llamadas a
   `HOST`; lo no soportado falla con diagnóstico, no con un artefacto
   incorrecto.
3. La salida es determinista y el assembler v3 produce una ROM verificable.
4. No hay compilación C ni toolchain nativa en el host; no hay stubs de libc.
5. Tests, documentación y ejemplos verdes; el ADR se enmienda si cambia el
   subconjunto o la ABI.

**Por qué no ahora.** V6 depende de un ISA v3, un ABI `HOST` v3 y un assembler
v3 que aún no existen. Implementarlo antes obligaría a inventar el contrato o a
clonar el ISA v2; ambas cosas contradicen la regla de no crear un segundo
camino. Se documenta y se deja bloqueado por V1/V5, no se adelanta como POC.

### Fases de `chattranslator` (Facade de traducción unificada)

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **CT0 — Scaffold** | Módulo `src/chattranslator/` creado, `ChatTranslator` facade, `Module` SPI, tests unitarios (12 tests) | ✅ Módulo Gradle incluido; `:src:chattranslator:build` y tests pasan |
| **CT1 — Config & Default Backend** | Soporte para `default-backend` en `config/chattranslator/config.yml`; `ChatTranslatorConfig` carga al arranque | ✅ Config schema propio (`chattranslator/config.yml`); `ChatTranslatorConfig.load(folder)` implementado; tests de prioridad pasando |
| **CT2 — Context Flags Runtime** | API `setContextBackend(key, backend)` funcional; prioridad contexto > config > auto | ✅ Tests de prioridad pasando; `context-override-enabled` configurable |
| **CT3 — Backend Re-registration** | Backends (`gtranslate`, `ltranslate`) declaran requerimiento `chattranslator` en `ModuleDescriptor`; `manager` respeta orden de carga | ✅ `gtranslate`/`ltranslate` actualizados con `.require(Requirement.atLeast("chattranslator", ...))`; tests de resolución de grafo pasando |
| **CT4 — Integración Host** | `SuiteHost`/`TranslationService` usan `ChatTranslator` como fachada principal; Spigot/Fabric hooks actualizados | Mensaje real traducido via `ChatTranslator` en pipeline E2E |
| **CT5 — Editor & Autoría** | Panel web para configurar backend default y ver backends registrados | ⏸️ Diferido con el editor web (`PLAN.md:12`); el backend (`ChatTranslatorConfig` + `BackendConfig`) ya expone lo necesario |

### Decisiones pendientes (ADR) — ChatTranslator

- **Schema de config:** ¿`translator.default-backend` en `config.yml` raíz o en `translators/*.yml`?
- **Backward compat:** ¿Mantener `TranslationService.active()` como fallback automático o forzar uso de `ChatTranslator`?
- **Capability versioning:** ¿`chattranslator` capability versionada independientemente o atada a suite version?

### Paridad funcional `TranslationService` ↔ `ChatTranslator`

| Área | `TranslationService` (actual) | `ChatTranslator` (objetivo) |
|---|---|---|
| Selección backend | Primer `isAvailable()` en orden de registro | Contexto > Config > Auto (primer `isAvailable()`) |
| Configuración | `translators/*.yml` (por proveedor) | `config.yml` (default-backend) + `translators/*.yml` |
| Override runtime | No soportado | `setContextBackend(key, backend)` |
| Caché/detección | Interno en `TranslationService` | Delegado a `TranslationService` (sin cambios) |
| API pública | `translate(text, from, to)`, `detect(text)` | Mismos métodos + overloads con `contextKey` |

### Fases de `iflow-wasm` (Chicory)

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **W0 — Scaffold** | `src/iflow-wasm/` creado, dependencia Chicory, loader mínimo y lockfile/verificación iniciales | ✅ Módulo Gradle incluido; `:src:iflow-wasm:build` validado con JDK 21 y Chicory 1.4.0 |
| **W1 — Contrato** | ADR del motor Wasm: imports/exports alineados a la **spec v3**, capabilities, límites, errores, ciclo de vida | 📝 ADR-W1 propuesto (inline más abajo): `decide` como export obligatorio, imports vacíos por defecto, sin WASI, `start` denegado, 256 páginas y 4 MiB por artefacto |
| **W2 — Runtime** | Cargar, validar, instanciar e invocar un `.wasm` de prueba | ✅ `WasmContract`/`ValidationResult`; `WasmModuleLoader` valida imports/exports/start/memoria, instancia con `withStart`/`withMemoryLimits` e invoca exports; 14 tests con módulos binarios escritos a mano |
| **W3 — Integración** | `WasmPolicyEngine` conectado a `Router`/`MessageDispatcher` | Un mensaje real atraviesa el pipeline con una política `.wasm` |
| **W3.5 — Paridad de mensaje (v3)** | Imports de transformación, decisión y entrega alineados a la spec v3 | Matriz `.flow` v3 (ROM) vs `.wasm` con el mismo resultado observable |
| **W4 — Herramientas** | Ejemplos en al menos dos lenguajes, validador de artefactos y guía de autoría | Build reproducible de los ejemplos + tests |
| **W5 — Editor** | Soporte del panel web para editar/compilar/subir `.wasm` | Flujo de autoría de extremo a extremo |

#### ADR-W1: Contrato del motor Wasm — política pura, sin imports implícitos y con límites duros

**Estado:** Propuesto

**Contexto**

- `Hecho verificado`: Chicory `1.4.0` está fijado en `src/iflow-wasm/build.gradle` y W0 expone solo `Parser.parse` + `Instance.builder`.
- `Hecho verificado`: la spec v3 (`docs/prompts/diseno/iflow-cpu-implement.md`) define transformación, decisión y entrega, pero el contrato de imports de Wasm sigue pendiente (W3.5).
- `Restricción`: el artefacto `.wasm` es dato no confiable; un módulo puede declarar memoria de 4 GiB, imports de WASI o una función `start` que se ejecuta al instanciar.
- `Restricción`: no hay toolchain WAT en el build; los tests de W2 usan módulos binarios mínimos escritos a mano.
- `Supuesto`: las políticas reales serán pequeñas (lógica de decisión), no cargas de datos; 4 MiB y 256 páginas son holgados para ese caso y deben revisarse si aparecen políticas mayores.
- La decisión fija el contrato mínimo que W3 (`WasmPolicyEngine`) puede asumir.

**Consecuencias**

*Positivas*
- Un artefacto con imports no declarados, `start` o memoria excesiva se rechaza antes de ejecutar código, no durante.
- La instancia se crea con `withStart(contract.allowStart())` y `withMemoryLimits(initial, max)`, así el crecimiento de memoria queda acotado aunque el módulo declare un máximo superior.
- El límite de 4 MiB y la comprobación de tamaño evitan leer/parsear artefactos desproporcionados.
- Los errores de validación son acumulativos (`ValidationResult.errors()`), lo que da diagnóstico completo en lugar de fallar en el primer incumplimiento.

*Negativas*
- Todo módulo que necesite imports legítimos (p. ej. `iflow.*` para leer el mensaje) será rechazado hasta que W3 amplíe `allowedImportModules`; el contrato actual es deliberadamente incompleto.
- No se valida todavía la **firma** de los exports (tipos/aridad): un export llamado `decide` con la firma equivocada pasa la validación y fallará al invocarlo.
- El límite de memoria se aplica al total por instancia; un módulo con varias memorias se rechaza, lo que excluye el proposal multi-memory.
- Sin hash/firma del artefacto, el contrato no protege contra sustitución del `.wasm` en disco.

**Alternativas consideradas**

1. **Permitir cualquier import y confiar en el host para denegar en runtime**
   - Ventajas: compatible con cualquier toolchain desde el primer día.
   - Limitaciones: el módulo puede enlazar con WASI o imports no auditados; el coste de seguridad se traslada a cada llamada.
   - Evaluación: descartada; el modelo de capabilities exige denegar por defecto.

2. **Contrato mínimo actual: sin imports, `decide` obligatorio, sin `start`, memoria y tamaño acotados (elegida para W2)**
   - Ventajas: superficie de ataque mínima; testeable con módulos binarios; permite congelar W2 sin esperar al ABI v3.
   - Limitaciones: no sirve aún para políticas que lean el mensaje; requiere ampliación versionada en W3.
   - Evaluación: **recomendada**; W3 añade imports `iflow.*` con lista blanca explícita y validación de firmas.

3. **Contrato completo v3 antes de implementar W2**
   - Ventajas: una sola congelación de contrato.
   - Limitaciones: bloquea W2 tras una spec que aún está en revisión (W3.5); sin runtime no se puede validar la spec.
   - Evaluación: descartada por orden de dependencias; W2 valida la mecánica, W3/W3.5 el contrato de mensaje.

**Pendiente de decisión (W3)**
- Nombres y firmas de los imports `iflow.*` (transformación, decisión, entrega) y su correspondencia con la spec v3.
- Validación de firma de exports mediante `Instance.exportType(name)`.
- Hash/firma del artefacto y política de hot-reload.
- Selección de motor (`.flow` vs `.wasm`) por configuración.


### Paridad funcional pendiente: `iflow-vm` (v3) ↔ `iflow-wasm`

> **Base de la paridad:** la **spec v3** (`docs/prompts/diseno/iflow-cpu-implement.md`), no el código
> actual de `src/iflow-vm`. Ese código implementa la **v2 de 16 bits** y se conserva como base
> histórica; sus `PROP_*`, `MessageHostContext`, `PolicyTarget` y `HOST` v2 **no son contrato vigente**.
>
> **Alcance:** replicar en Wasm el contrato v3 de transformación, decisión y entrega, sin heredar ISA,
> MMU, BIOS/kernel ni ABI `HOST` v2. **No** incluye el backlog V1–V5 de la VM de 32 bits.

El objetivo es que una política `.wasm` observe y transforme el mensaje y emita la misma decisión
explícita que una política `.flow` v3, con el mismo resultado observable. La paridad es de **contrato**,
no de implementación: los nombres de imports pueden diferir, la semántica no.

| Área | Contrato v3 | Pendiente en `iflow-wasm` |
|---|---|---|
| Contexto de solo lectura | Tipo, dirección, emisor, destinatario, canal e identidad del mensaje, leídos como parámetros del IRQ. | Imports de lectura; sin mutación. |
| Contenido | `TRANSFORM_APPLY` solo contenido/metadatos: texto, placeholders y formato. | Host functions de lectura/escritura con buffer TLV/longitud; UTF-8 sin truncar. |
| Metadatos | Idioma origen/destino, color y flags; sonidos solo si v3 ratifica su contrato (hoy no está implementado en v2). La precedencia de idioma sigue diferida (`IFLOW-MAN-42` / Q35). | Definir el conjunto exacto de propiedades y sus validadores; **no copiar** los `PROP_*` v2. |
| Canal | `SET_CHANNEL` validado como única mutación de canal; si cambia permisos/destinatarios, segunda fase de routing, no reentrega recursiva. | Import de `SET_CHANNEL` + validación de existencia y permisos. |
| Routing | Decisión explícita y tipada; el guest decide y el host registra. Default deny + `DELIVER` explícito y `CANCEL_EVENT` separado de cancelar mensaje (D01–D05 pendientes de ratificar). | Import de decisión con target/destino/razón; sin `route:*` ni `SET_PROCESSED`. |
| Journal | Solo write set del mensaje: todo o nada para sus mutaciones; no cubre máquina, tiempo, rate limit, cola ni entrega (`D43`). | Transacción de mensaje en el host con la misma semántica. |
| Rate limit y tiempo | Política en userspace: token bucket, backoff y sleep usan RTC/ciclos/`HALT`; el host no calcula backoff y solo puede imponer un cap duro (`D40`, `D42`). | Exponer reloj/ciclos y `HALT`/sleep; **no** importar `RATE_LIMIT` ni `sleepMillis` como propiedad. |
| Input ABI | Campos con longitud/escape o TLV; UTF-8 sin cortar; ausencia distinta de `"null"`; `read-your-writes` es decisión abierta (Q26). | Definir ABI de entrada/salida y comportamiento de lectura tras escritura. |
| Propiedades declaradas sin implementar | `PROP_SOUNDS`, `PROP_PROCESSED`, `PROP_TRANSLATE` y `PROP_ACTOR_WORLD` no son baseline: se implementan o se retiran (`IFLOW-MAN-31`). | No replicarlas hasta que v3 fije su contrato. |
| Material diferido | Precedencia de idioma, `Formats` N, `Direction.channel` y render de redirect (`IFLOW-MAN-42…45`). | Fuera de W3.5 hasta que se retomen tras implementar v3. |

> **Estado de las decisiones:** D01–D05 siguen propuestas (no ratificadas); D14–D16, D36R y
> D40–D43 sí están ratificadas. La paridad sigue la dirección v3, pero no congela nombres de
> `PolicyTarget`, IDs de propiedades ni imports hasta ratificar D01–D13 y cerrar las preguntas
> abiertas que afecten al contrato.

> **No se hereda de la v2:** `LOG`/`processed`, `DROP`/`REJECT` como targets separados,
> `REDIRECT`/`CHANNEL_REDIRECT`, `RATE_LIMIT` con backoff calculado por el host, `sleepMillis` como
> propiedad del mensaje, `PROP_*` declaradas sin implementar ni la lectura que convierte `null` en
> `"null"`.

**Criterios de cierre:**

1. Un corpus de políticas `.flow` v3 (ROM) y `.wasm` produce la misma decisión explícita
   (target/destino/razón) y el mismo mensaje transformado para las mismas entradas.
2. `SET_CHANNEL` valida existencia y permisos igual en ambos motores; un cambio que altere
   destinatarios se resuelve como segunda fase de routing, no como reentrega recursiva.
3. El journal es atómico y cubre solo el write set del mensaje; no filtra estado de máquina, tiempo,
   rate limit, cola ni entrega.
4. La política de rate limit y de tiempo vive en el guest; el host solo aplica un cap duro separado
   y no calcula backoff ni interpreta `sleepMillis` como propiedad.
5. El ABI de entrada/salida usa longitud/escape o TLV, no trunca UTF-8 y distingue ausencia de
   `"null"`; *read-your-writes* queda documentado según Q26.
6. La matriz de paridad y los tests negativos (propiedad desconocida, enum inválido, buffer
   insuficiente, capability denegada, canal inexistente) quedan documentados.

**Fases:** W1 define los imports alineados a la spec v3; W2 implementa las host functions y la
validación; W3 integra el motor; W3.5 cierra la paridad. No se toca el backlog V1–V5 de `iflow-vm`.

### Decisiones pendientes (ADR)

- **`iflow-vm`:** ISA de 32 bits, mapa de memoria, MMU, IRQ, scheduler, política de tiempo
  (quantum/timer/watchdog), journal, concurrencia, ABI `HOST` v3 y compatibilidad con `.flow` v2.
- **`iflow-wasm`:** nombre/artefacto definitivo, versión de Chicory (W0 fija `runtime:1.4.0`;
  falta decidir compilador/WASI), ABI de imports, capabilities, límites, hot-reload y firma/hash
  de artefactos.
- **Paridad `iflow-vm` ↔ `iflow-wasm`:** contrato de imports alineado a la **spec v3**
  (transformación, decisión, entrega y journal), no el ABI v2 de `src/iflow-vm`; criterios de
  equivalencia observable (ver «Paridad funcional pendiente»).
- **Exclusividad y selección:** solo un motor iFlow instalado a la vez (instalar otro reemplaza al
  anterior), selección por configuración, formato de configuración común, migración de políticas y
  criterio para declarar un motor como predeterminado.

### Próxima acción inmediata (V0/W0/W1, CT1–CT4 ✅ y cierre de paridad)

1. Mantener `docs/prompts/diseno/iflow-cpu-implement.md` como especificación técnica de referencia v3 de la VM de 32 bits.
2. Conservar `src/iflow-vm/` como base de la VM de 32 bits.
3. Mantener el scaffold de `src/iflow-wasm/` (Chicory, loader, tests, lockfile/verificación) validado con JDK.
4. Escribir el ADR del motor Wasm y el ADR de la VM de 32 bits antes de implementar núcleos.
5. Completar la paridad funcional de propiedades y decisiones entre ambos motores contra la spec v3 (W3.5); no se toma el código v2 como contrato ni se aborda el backlog V1–V5 de `iflow-vm`.
6. **ChatTranslator CT4:** ✅ **COMPLETADO** — `ChatTranslator` integrado en `SuiteHost.bootstrap()`; `spigot-host`/`fabric-host` actualizados; `BackendConfig` carga backends vía SPI; tests pasan.
7. **ChatTranslator CT5:** ⏸️ diferido con el editor web; el backend de configuración ya está listo.
8. **V6 — Compilador C:** diseño en ADR-023; no se inicia antes de V1/V5. La implementación empieza por C0 (subconjunto y ABI) cuando el ISA v3 esté congelado; no se adelanta contra el ISA v2.

---

### Fixes de Inconsistencia (desde `docs/workdir/REPORT.md` — 2026-10-25)

> **Fuente:** Auditoría integral + decisiones manuales en `REPORT.md`. 9 items **FIX_NOW** (P0), 3 **FIX_LATER** (P1/P2), 4 **NEEDS_DESIGN** (ADR).

#### P0 — FIX_NOW (Bloquean arquitectura correcta)

| ID | Acción | Módulos afectados | Criterio de cierre |
|---|---|---|---|
| **I-002** | Añadir `require('sync-bus')` a submódulos sync | `sync-discord`, `sync-websocket`, `sync-telegram`, `sync-http`, `sync-velocity-core` | ✅ `grep -r "require.*sync-bus" src/sync-*/` → 6/6 |
| **I-006** | Crear `Module` implementation en `iflow-vm` y `iflow-wasm` | `iflow-vm`, `iflow-wasm` | ✅ `grep -r "implements Module" src/iflow-vm/ src/iflow-wasm/` → YES |
| **I-007** | Añadir `ModuleDescriptor` a 19 módulos sin él | `host`, `transport`, `messages`, `logger`, `core-api`, `extension-api`, `manager-spi`, `manager-core`, `performance`, `loadtest`, `common-legacy`, `fabric-host`, `spigot-host`, `example-extension`, `kernel`, `iflow-vm`, `iflow-wasm`, `web-editor` | ✅ Cerrado con alcance acotado: core modules con `ModuleDescriptor` (host, kernel, transport, messages, logger, manager-spi, manager-core, manager-apt). Fuera de alcance: platform adapters (clean/hexa), API modules (core-api/extension-api), harness (`performance`, `loadtest`, `tester`), `example-extension` y `web-editor` (diferido). `common-legacy` no tiene fuentes `main` (candidato a eliminar, no a describir) |
| **I-003** | **CT4:** Integrar `ChatTranslator` en `SuiteHost.bootstrap()` | `host`, `chattranslator`, `spigot-host`, `fabric-host` | ✅ Pipeline real usa `ChatTranslator`; `TranslationService` creado internamente |
| **I-005** | Cargar `ChatTranslatorConfig` + `BackendConfig` en bootstrap | `host` (SuiteHost), `chattranslator` | ✅ `SuiteHost.bootstrap()` instancia `ChatTranslator` y lo expone via `chatTranslator()` |
| **I-009** | Mover `TranslatorsConfig.discoverProviders()` a `chattranslator.BackendConfig` | `host` (eliminar), `chattranslator` (nuevo `BackendConfig`) | ✅ `host` no usa `ServiceLoader<TranslatorProvider>`; `chattranslator` carga backends |
| **I-014** | Resolver confusión capability `chattranslator` vs `translator` | `chattranslator`, `gtranslate`, `ltranslate` | ✅ Façade `ChatTranslator` coordina backends; capability model alineado |
| **I-016** | Alinear `ChatTranslatorConfig.defaultBackend` con `storage.md` | `chattranslator`, `storage.md` | Default `'google'` en ambos (o `null` documentado como auto) | ✅ `ChatTranslatorConfig.defaults()` = `'google'` |
| **I-011** | **M1:** Mover `ConfigLoader.loadChannels()` a `textformatter.ChannelConfig` | `host` (eliminar), `textformatter` (nuevo `ChannelConfig`) | ✅ `ChannelConfig` en `textformatter` (canónico `config/textformatter/channels/` + fallbacks legacy); `ConfigLoader.loadChannels()` eliminado; `SuiteHost`/`SuiteBootstrap`/`ConfigValidator`/`loadtest` usan `ChannelConfig`; 7 tests en `ChannelConfigTest` |
| **I-018** | Completar `resetConfigs` con nuevos directorios `config/<modulo>/` | `spigot-host`, `fabric-host` | ✅ Backup incluye `config/` (suite, chattranslator, sync-*, inworld, observability, iflow-firewall, textformatter) + heredados (`channels/`, `translators/`, `sync/`, `rules.yml`, `inworld.yml`); fabric-host alineado con spigot-host |

#### P1 — FIX_LATER (Próxima iteración)

| ID | Acción | Módulos afectados | Criterio de cierre |
|---|---|---|---|
| **I-004** | Migrar carga config centralizada (translators, sync) a dueños | `host`, `chattranslator`, `sync-bus`, submódulos sync | ✅ `TranslatorsConfig` eliminado de `host` (`chattranslator.BackendConfig`/`ChatTranslatorConfig` cargan sus archivos); cada submódulo sync expone su config (`DiscordConfig`, `WebSocketSyncConfig`, `SocketConfig`, `TelegramConfig`) sobre `syncbus.SyncYaml`, y los adapters ya no parsean YAML de sync. `messages.yml` queda en `host` según ADR-020 |
| **I-012** | Corregir rutas sync en spigot-host: `sync/*.yml` → `config/sync-*/config.yml` | `spigot-host`, `fabric-host` | ✅ Rutas resueltas vía `syncbus.SyncConfigPaths` (canónica `config/sync-*/`, fallback `sync/*.yml`); websocket/tcp/udp/telegram y `DiscordBridge` en ambas plataformas |
| **I-017** | Corregir `copyDefaultsIfMissing` a nuevas rutas `config/<modulo>/` | `spigot-host`, `fabric-host` | ✅ Primer arranque escribe `config/suite/`, `config/textformatter/channels/`, `config/chattranslator/translators/`, `config/sync-*/`, `config/inworld/`, `config/iflow-firewall/`; no duplica si existe la ruta legacy |

#### P2 — NEEDS_DESIGN (Requieren ADR antes de actuar)

| ID | Tema | Decisión requerida |
|---|---|---|
| **I-001** | `iflow-firewall`: restaurar desde git history vs retirar de docs | ✅ ADR-001 aceptado y aplicado: `src/iflow-firewall` restaura **literalmente** el motor SpEL de `933bdd3^:src/iflow`; es **alternativa mutuamente excluyente** de `iflow-vm` (mismo paquete base, solo uno instalado) y el host elige con `iflow.engine.kind` |
| **I-019** | `fabric-host` deps: clean/hexa dice NO incluir módulos agnósticos | 🔴 **Reabierto como reestructuración integral**: no es un fix de dependencias. Se reescribe ADR-019 para convertir el Manager en orquestador de un runtime de módulos con carga dinámica, hot unload/upgrade real y adapters que solo implementan puertos de plataforma. Implementación pendiente por fases con puertas de calidad; ver ADR-019 |
| **I-020** | `MessagesConfig` ownership: shared host vs platform adapters | ✅ Aceptado: un único `config/suite/messages.yml` para todas las plataformas (`HostPaths`); loader compartido en `host` |
| **I-008** | `ChatTranslatorModule` capability: `chattranslator` vs `translator` | ✅ ADR-008 aceptado y verificado: se mantienen `chattranslator` (façade) y `translator` (proveedor); `ChatTranslatorModuleTest` prueba que la façade activa sin backends, que un backend sin façade queda inactivo y que ambos resuelven cuando coexisten |

#### ADR-001: `iflow-firewall` restaura el motor SpEL legacy como alternativa mutuamente excluyente

**Estado:** Aceptado (2026-10-27). Reemplaza la propuesta anterior de un módulo
SpEL-free que solo validaba el grafo del editor.

**Contexto**

- La búsqueda inicial (`git log --all -- '*firewall*'`) no encontró nada porque la
  ruta `iflow-firewall` nunca existió: el módulo original se llamaba `src/iflow`.
- `Hecho verificado`: en `933bdd3^` (padre del *VM architecture rewrite*) `src/iflow/`
  tenía 27 archivos: `DefaultRouter.java` (*«Firewall contract of the iFlow module»*),
  `Router`, `RouteDecision`, `RouteOutcome`, `RuleExpressionEvaluator`, `rule/{Rule,
  ScriptSurface, TransformOp}`, `channel/{PermissionChecker, RateLimiter}`,
  `target/PolicyTarget` e `IflowModule`; el host tenía `RuleConfigLoader`.
- `Hecho verificado`: `933bdd3` sustituyó el motor por `VmPolicyEngine`; `e21e7d7`
  eliminó SpEL (`ExpressionEvaluator`, `SpelExpressionEvaluator`, `spring-expression`);
  `ead0523` renombró `src/iflow` a `src/iflow-vm`.
- **Decisión explícita del usuario (2026-10-27):** restaurar literalmente el motor
  SpEL y desarrollar `iflow-vm` e `iflow-firewall` en paralelo, sin que uno sustituya
  al otro **en código**. La decisión de `e21e7d7` queda superada **solo** para este
  módulo; la VM y Wasm siguen sin SpEL.
- **Regla de producto:** los tres motores (`iflow-vm`, `iflow-wasm`, `iflow-firewall`)
  son **mutuamente excluyentes**. Solo uno se instala a la vez; instalar otro
  desinstala/reemplaza al anterior. Comparten el paquete base
  `me.majhrs16.suite.iflow`, por lo que no deben coexistir en el classpath.
- Restricción de diseño: cada motor es independiente. No se crea un `iflow-api`
  compartido ni `iflow-firewall` depende de `iflow-vm`.

**Decisión**

1. `src/iflow-firewall` contiene el motor restaurado desde `933bdd3^:src/iflow`:
   `Router`, `RouteDecision`, `RouteOutcome`, `DefaultRouter`,
   `RuleExpressionEvaluator`, `rule/{Rule, ScriptSurface, TransformOp}`,
   `channel/{PermissionChecker, RateLimiter}`, `target/PolicyTarget`,
   `RuleConfigLoader` e `IflowFirewallModule`.
2. El paquete es `me.majhrs16.suite.iflow` (el mismo base que `iflow-vm` y
   `iflow-wasm`), sin sufijo `.firewall`. La exclusividad de instalación elimina la
   colisión de FQCN; el sufijo anterior se retiró.
3. El descriptor se llama `iflow-firewall` (no `iflow`), provee el capability
   `router` y requiere `channel-api` de TextFormatter.
4. `host` se compila contra el contrato base `me.majhrs16.suite.iflow.Router` y
   `RouterFactory` selecciona el motor con `iflow.engine.kind` (`vm` por defecto,
   `firewall` legacy). No hay adaptador: cada motor implementa el mismo `Router`
   del paquete base y solo el instalado está presente en runtime.
5. `ExpressionEvaluator` y `ExpressionEvaluationException` viven en
   `iflow-firewall`, **no en `core-api`**: solo los usa este motor.
6. `config/iflow-firewall/rules.yml` acepta la lista histórica de reglas (con
   `condition`/`action` SpEL) o un mapa con `rules:`. El grafo del editor
   (`guard`/`filter`/`nodes`/`edges`) se valida pero **no se ejecuta**.

**Consecuencias**

*Positivas*
- Restauración literal del motor histórico y de sus tests.
- Los tres motores comparten paquete base (y el contrato `Router` donde aplica), sin
  `iflow-api` ni dependencias cruzadas.
- El host mantiene un único punto de selección; no hay adaptador de tipos.
- El sandbox de SpEL se conserva: `T()` bloqueado, allowlist de clases,
  resolución de métodos solo sin argumentos y caché LRU acotada (1024).
- `core-api` no incorpora SPI que solo use un módulo.

*Negativas*
- Cada JAR de motor duplica las clases del contrato base (`Router`, `RouteDecision`,
  etc.) con el mismo FQCN. Es intencional y seguro **solo** porque los motores son
  excluyentes; `SuiteBootstrap` deja activo uno y los tres se publican como paquetes
  separados, nunca en el mismo artefacto.
- El gestor de paquetes hace cumplir la exclusividad mediante los campos
  `Exclude`/`Provide` del formato compartido (`METADATA/package.list` y
  `library/status`); el empaquetado no debe incluir dos motores en el mismo classpath.
- SpEL vuelve a ser una superficie de seguridad y una dependencia
  (`spring-expression`, `spring-core`, `spring-jcl`) solo en `iflow-firewall`.
- `rules.yml` admite dos esquemas (lista ejecutable y grafo del editor), lo que
  puede confundir; el grafo no ejecuta nada.
- El motor legacy no se usa salvo que `iflow.engine.kind: firewall` y esté instalado.

### Formato de paquete: `Exclude` y `Provide` (2026-10-27)

El formato compartido por `METADATA/package.list` y `library/status` incorpora dos
campos opcionales, ambos con nombres separados por espacios:

```
Exclude: iflow-firewall iflow-wasm
Provide: iflow router
```

- **`Exclude`** declara paquetes que no pueden coexistir con el paquete actual. La
  relación es simétrica: basta que cualquiera de los dos excluya al otro. También
  coincide con nombres virtuales que el otro paquete declare en `Provide`.
- **`Provide`** declara nombres virtuales que el paquete satisface. Sirve sobre todo
  para metapaquetes y contratos estables: una dependencia `iflow` puede quedar
  satisfecha por `iflow-vm`, `iflow-wasm` o `iflow-firewall` sin que el dependiente
  conozca el motor concreto. No cambia la identidad del paquete ni crea un paquete
  real adicional.

Semántica de instalación:

1. `PackageInstaller` (instalación local de un ZIP) rechaza la instalación si algún
   paquete del manifiesto entra en conflicto con uno ya instalado. El mensaje indica
   que debe desinstalarse primero; `force` no omite conflictos de paquete.
2. `DependencyResolver` calcula, además de los pasos de instalación, la lista de
   paquetes instalados que deben retirarse por `Exclude`. Si retirar uno rompe una
   dependencia inversa que ningún paquete nuevo satisface, la resolución falla.
3. `AptInstaller.install(..., confirmed=false)` no modifica el sistema si hay
   reemplazos: devuelve un resultado de confirmación con la pregunta estilo APT:
   `Deseas instalar estos paquetes?: <lista>` y `Reemplazarán a: <paquetes>`.
   Con `confirmed=true` retira primero los paquetes en conflicto y después instala
   el plan. En los comandos, el token final `confirm` activa esa confirmación:
   `/suite repo install iflow-vm confirm`.
4. Las dependencias satisfechas por `Provide` se reconocen tanto al instalar como al
   comprobar dependencias inversas y al diagnosticar paquetes rotos.

Manifiestos recomendados para los tres motores iFlow (cada uno excluye a los otros
dos y provee el nombre virtual `iflow`):

```
Exclude: iflow-firewall iflow-wasm
Provide: iflow
```

```
Exclude: iflow-vm iflow-wasm
Provide: iflow
```

```
Exclude: iflow-vm iflow-firewall
Provide: iflow
```

El estado instalado conserva `Exclude`/`Provide` al serializarse, de modo que la
exclusividad sigue vigente después de reiniciar el servidor. `module info` muestra
ambos campos.

**Alternativas consideradas**

1. **`iflow-api` compartido con un único `Router`**
   - Ventajas: sin duplicación de contrato.
   - Limitaciones: crea un tercer módulo del que dependen todos; el usuario lo
     rechazó explícitamente por mezclar los módulos.
   - Evaluación: descartada.

2. **Sufijo de paquete `.firewall` para permitir convivencia**
   - Ventajas: evita colisión si ambos JAR están presentes.
   - Limitaciones: contradice la regla de exclusividad y introduce un paquete que
     no comparten los tres motores; el usuario lo rechazó.
   - Evaluación: descartada.

3. **Restaurar solo el grafo SpEL-free (decisión anterior)**
   - Ventajas: no reintroduce SpEL.
   - Limitaciones: no es el motor histórico y contradice la decisión del usuario.
   - Evaluación: descartada.

4. **Restauración literal, mismo paquete base y motores excluyentes (elegida)**
   - Ventajas: fidelidad histórica, contrato compartido por FQCN, sin `iflow-api`.
   - Limitaciones: los JAR no pueden coexistir; el gestor debe reemplazar.
   - Evaluación: **aceptada**.

**Implementación (2026-10-27).** Código restaurado en `me.majhrs16.suite.iflow`,
descriptor `iflow-firewall`, SPI de expresión dentro del módulo, `RouterFactory`
sin adaptador, `FirewallConfig` tolera el esquema legacy, selección de motor en
`HostConfig`/`ConfigLoader`, publicación separada de los tres motores con
`publishIflowEngines` y documentación de exclusividad (PLAN, ADR, storage, README,
wiki). `./gradlew build` y `./gradlew publishIflowEngines` pasan.

---

#### ADR-019: Reestructuración integral — runtime de módulos, hot unload/upgrade y adapters delgados

**Estado:** Aceptado (2026-10-27). Reescrito por decisión del usuario. Sustituye por completo la versión anterior de este ADR.

**Implementación:** F0 cerrada (inventario y decisiones), F1 completada (API de ciclo de vida en `core-api`), F2 completada como POC (`suite-runtime` con `ModuleClassLoader`/`ModuleHost`), F3 completada (puente `PackageRuntime` entre manager y runtime, estado de instalación y estado runtime separados), F4a completada (`SuiteRuntime` como composition root neutral con comandos), F5 cerrada (los seis paquetes base con activadores, ZIP de distribución y activación real; contratos compartidos en `core-api`; `logger` como paquete de dependencia) y F6 en curso (`transport` como paquete de dependencia; `gtranslate`/`ltranslate` como proveedores activables; eventos `ModuleActivatedEvent`/`ModuleDeactivatedEvent` para refrescar `chattranslator` sin reiniciar; `presets` empaquetado con `FilePresetTarget` real; `tester` empaquetado con `TestProbe` neutral; `inworld` empaquetado con `InWorldHandler`/`InWorldPlatform` y el listener Bukkit en `spigot-host`). Faltan la retirada del cableado del host (F4b) y F4c (sincronizadores de plataforma).

> **Ubicación:** el detalle operativo de ADR-019 vive en `docs/workdir/PLAN.md`;
> la decisión de arquitectura se registra también en `docs/workdir/ADR.md`, que
> es el documento vivo de ADRs. Se reescribe este ADR en lugar de crear uno
> nuevo para evitar fragmentar todavía más la arquitectura.

##### Decisión del usuario (2026-10-27)

- **I-019 no es un arreglo de dependencias**: es una reestructuración de la
  arquitectura de la suite.
- El objetivo es **hot unload/upgrade real**, no solo carga inicial de módulos.
- La solución no es empezar por el loader manteniendo la suite actual. Hay que
  **romper la suite, desarmar todo, reestructurar lo que debe ir y lo que no, y
  volver a armarla** como debería haber sido desde el principio.
- No se aceptan implementaciones a medias, stubs, no-ops, adaptadores de
  compatibilidad sin fecha de eliminación ni parches que preserven el
  acoplamiento actual.
- El Manager debe ser el origen de la composición: actualizar índice, descargar
  paquetes, resolver dependencias, instalar, **inicializar en memoria**, y
  gestionar el ciclo de vida completo.
- Spigot y Fabric no deben conocer la existencia de ningún módulo de feature.
  Solo implementan una API común entre plataformas y adaptan, en reversa, los
  eventos de su Minecraft a una representación interna agnóstica.

##### Diagnóstico: por qué el estado actual no puede sostener ese modelo

###### 1. El Manager instala, pero no activa

- `manager-apt/.../ModuleLifecycleAdapter.java:20-29` documenta explícitamente
  que la arquitectura APT/dpkg instala en disco y **requiere reinicio**; la
  carga/descarga dinámica **no está soportada**.
- `ModuleLifecycleAdapter.load()` devuelve el `parent` ClassLoader, no crea uno
  (`:95-99`).
- `register()` devuelve `false` y `unregister()`/`unload()` no hacen nada
  (`:103-117`).
- `ManagerFacade.discoverAll()` solo llama a
  `kernel.ModuleLoader.discover(parent)` y deja el TODO explícito:
  «Package-based module discovery not yet implemented in ModuleLoader»
  (`ManagerFacade.java:209-216`).
- `PackageInstaller` mueve archivos a `installRoot` y registra estado; no abre
  ningún `URLClassLoader` ni instancia servicios
  (`PackageInstaller.java:610-632`).
- El destino de instalación es el directorio de datos de la suite
  (`installRoot = managerDir.getParent()`), no `/plugins` ni `/mods`; por tanto
  el servidor **no** carga esos JARs automáticamente. Sin un `ModuleHost`, los
  paquetes instalados quedan inertes.

###### 2. `Module` no tiene ciclo de vida

- `core-api/.../Module.java:8-13` dice: «There is no lifecycle contract and no
  reflective method cracking: exposing a `ModuleDescriptor` is enough for the
  dependency graph».
- Ese contrato sirve para describir, no para cargar, iniciar, detener,
  desregistrar o descargar.
- `ServiceLoader` es estático: descubre lo que ya está en el classpath del host,
  no JARs instalados en `library/packages`, y no permite desregistrar.

###### 3. El Manager arranca después de la suite

- En Spigot, `SuiteHost.bootstrap(...)` se ejecuta en
  `TextFormatterSuitePlugin.java:297`, antes de inicializar `ManagerFacade` en
  `:457-474`. Cuando el Manager aparece, la suite ya está construida con todos
  los features cableados.
- En Fabric, `TextFormatterSuiteMod.java` **no inicializa `ManagerFacade` en
  absoluto**, aunque `fabric-host/build.gradle` declara `manager-spi`,
  `manager-core` y `manager-apt`.
- Por tanto, hoy el Manager no decide qué módulos forman la suite; solo gestiona
  archivos de una suite que ya está viva por otras vías.

###### 4. Los adapters y el host conocen los features

- `spigot-host/build.gradle:31-55` declara `sync-*`, `gtranslate`,
  `ltranslate`, `messages`, `textformatter`, `tester`, `transport`,
  `observability`, `manager-*`, `inworld` y `extension-api`.
- `fabric-host/build.gradle:24-44` declara casi lo mismo, con divergencias:
  falta `sync-http`, sobran `presets` y `manager-*` (no se usan en Java), y no
  existen `tester` ni `inworld`.
- `spigot-host` importa módulos de feature directamente
  (`TextFormatterSuitePlugin.java:3-42`); Fabric también
  (`TextFormatterSuiteMod.java:3-38`).
- `gtranslate` y `ltranslate` no se importan en los adapters: solo se necesitan
  por `ServiceLoader`. Aun así se declaran como `implementation`, lo que
  demuestra que la lista de dependencias no refleja el uso real.
- `host/build.gradle:19-30` depende de `textformatter`, `iflow-vm`,
  `iflow-firewall`, `transport` y `chattranslator`. `SuiteHost.java:100-131`
  los instancia directamente.
- `inworld` ya no depende de `host`: es un paquete neutral que solo usa
  `core-api`; el listener Bukkit vive en `spigot-host`
  (`SpigotInWorldListener`). `observability` y `presets` tampoco dependen de
  `host`, y `extension-api` usa puertos neutrales (`MessageDispatch`,
  `PermissionChecker`, `ActorDirectory`).
  Por eso no se puede «mover todo el wiring a `host`»: se crearían ciclos.

###### 5. El grafo del kernel no gobierna el arranque

- `SuiteBootstrap.java:45-132` descubre módulos y resuelve
  `provides`/`requires`, pero los adapters llaman a `SuiteHost.bootstrap(...)`,
  no a `SuiteBootstrap`.
- `SuiteBootstrap` solo se usa en tests de `loadtest`; en producción el grafo
  es una validación, no la fuente de verdad de la composición.

###### 6. Los módulos actuales son librerías, no plugins/mods autónomos

- Los módulos de feature no tienen `plugin.yml` ni `fabric.mod.json`; solo
  declaran `META-INF/services/me.majhrs16.suite.api.Module`.
- Están pensados para ser empaquetados dentro del shadow JAR del adapter, no
  para ser cargados desde un directorio de módulos.
- Ningún feature importa Bukkit/Paper en `src/main`: `inworld` es neutral y su
  listener Bukkit vive en `spigot-host`. El resto de features tampoco importa
  SDK de plataforma.

###### 7. La única carga dinámica existente es para extensions

- `ExtensionManager.java:126-127` y `:239-242` ya usan `URLClassLoader` para
  cargar extensions.
- Es una prueba de concepto útil, pero está separada del Manager y de los
  módulos; no hay un modelo común de ciclo de vida, registro de servicios ni
  descarga.

###### 8. Faltan guardrails

- No hay ningún test o tarea que impida que un adapter vuelva a declarar un
  módulo de feature.
- No hay tests de carga/descarga de JARs, aislamiento de classloader,
  activación fallida, rollback ni fugas de recursos.
- `library/status` no distingue con suficiente precisión entre instalado,
  activo, fallido y pendiente de reinicio; la spec lo exige
  (`manager-v2.md:321`, `:546-548`).

##### Principios no negociables

1. **El core no conoce features.** `core-api`, `kernel`, `manager-*` y
   `suite-runtime` no importan `sync-*`, `textformatter`, `iflow-*`,
   `chattranslator`, `gtranslate`, `ltranslate`, `observability`, etc.
2. **Los adapters solo conocen la plataforma y la API.** `spigot-host` y
   `fabric-host` son bootstrap y dependen de `core-api` + `suite-runtime` + SDK
   de plataforma. La captura de eventos vive en `sync-bukkit`/`sync-fabric`.
3. **El Manager es el orquestador.** Instalar, activar, desactivar, actualizar
   y reparar pasan por el Manager.
4. **Instalar no es activar.** El estado instalado y el estado activo se
   registran por separado; una activación fallida no se marca como éxito.
5. **El ciclo de vida es obligatorio para hot unload/upgrade.** Sin
   `start/stop` y liberación de recursos, no se promete hot reload.
6. **No hay doble camino.** Cuando una feature se migra al runtime, se elimina
   su cableado compilado en el mismo cambio. No se mantienen dos formas de
   arrancar.
7. **No hay stubs silenciosos.** Lo no soportado lanza un error explícito o se
   marca como `RESTART_REQUIRED`; nunca se devuelve `parent`, `false` o vacío
   fingiendo éxito.
8. **La arquitectura se verifica.** Un guard de dependencias/imports falla el
   build si aparece una violación.

##### Principios adicionales de diseño del ADR-019

Estos principios complementan los no negociables y definen el modelo de
dominio del reestructuramiento:

- **C2. `force` significa forzar de verdad.** El parámetro `force` no omite
  conflictos de forma silenciosa. Si un administrador usa `force`, es porque
  quiere forzar las cosas y sabe lo que hace. Esto incluye permitir la
  instalación de paquetes excluyentes si el administrador así lo decide, con
  la advertencia correspondiente. `force` es una señal de intención
  deliberada del operador, no un sinónimo de «ignorar problemas».

- **C3. Las librerías compartidas son dependencias, no parte del host.**
  Las librerías compartidas (SnakeYAML, JDA, Adventure, transporte y otras)
  no deberían vivir en el host. Son dependencias, paquetes aparte de tipo
  «dependencia». Es el manager quien se asegura de relocalizar y cargar
  dichas dependencias para cada paquete tipo módulo. No se deberían compilar
  dentro del JAR del host ni de los módulos.

- **C4. Los metadatos autoritativos vienen del repositorio, no del JAR.**
  No se incluye una lista de paquetes dentro del JAR. Eso es trabajo del
  manager: descargar del repositorio (en el directorio `repo`) el archivo
  `packages.list`, y a partir de ahí listar la URL de cada ZIP y por ende
  cada módulo. El manager nunca infiere paquetes mirando el contenido de un
  JAR; consulta el índice remoto.

- **C6. `library/status` es una BD de programas instalados, no de ejecución.**
  La presencia de un paquete implica que está instalado y que debe iniciarse.
  El estado de ejecución (encendido/apagado, fallido, requiere reinicio) no
  pertenece a `library/status`; si es necesario rastrearlo, se hace en un
  directorio temporal dentro del directorio del manager, nunca en el estado
  de instalación.

- **C7. `tester` es un módulo de producción.** El módulo `tester` no es una
  herramienta de desarrollo excluida del artefacto final. Es un módulo
  pensado para hacer benchmarking en tiempo de ejecución, además de probar
  toda la suite en todo el pipeline. Si algo está mal hecho, existe la alta
  probabilidad de que `tester` detecte el fallo, en qué punto, y cuál es la
  diferencia vs lo esperado. Para esto, `tester` tiene que simular el envío
  de un jugador, ir trazando la ejecución manualmente del mensaje, comprobar
  el resultado de cada etapa inspeccionando directamente el comportamiento
  del sistema, y lanzar un check si la comprobación de todo el pipeline es
  correcto. Es complejo, sí. Es potencialmente útil para fallos delicados,
  sí.

- **C9. Versiones concurrentes al estilo Debian.** A la hora de upgradear,
  se instalan versiones modernas remplazando al mismo paquete en su versión
  anterior, repitiendo el mismo proceso con todos los módulos y
  dependencias, resolviendo el grafo sin más. Esto es normal y es el camino
  de Debian en cuestión de apt y dpkg.

- **C10. La captura de eventos de plataforma es un sincronizador descargable.**
  El bloque del host que registra listeners de plataforma, traduce el evento a
  `Message` y lo entrega al núcleo no pertenece al host. Es un paquete de
  plataforma: `sync-bukkit` para Spigot/Paper y `sync-fabric` para Fabric. Cada
  entorno descarga únicamente el sincronizador compatible; ninguno se embebe en
  el JAR del host.

##### Decisión 2026-10-29 — sincronizadores de plataforma

La captura de eventos de Minecraft deja de ser código compilado en
`spigot-host`/`fabric-host`. El bloque que hoy registra
`AsyncPlayerChatEvent`/`ServerMessageEvents.CHAT_MESSAGE`, traduce el evento y
lo manda al núcleo agnóstico se convierte en un paquete de plataforma:

| Paquete | Entorno | Responsabilidad |
|---|---|---|
| `sync-bukkit` | Spigot/Paper/Purpur (API Bukkit/Paper) | Registra los listeners de chat/join/quit/death, traduce el evento a `Message` y lo despacha por `MessageDispatch`. |
| `sync-fabric` | Fabric Loader/Minecraft compatible | Igual, usando `ServerMessageEvents` y la API de Fabric. |

Reglas de la decisión:

1. **El host queda como bootstrap.** `spigot-host`/`fabric-host` construyen
   `PlatformPorts`, arrancan `SuiteRuntime`, publican los servicios neutrales y
   registran comandos. No contienen `@EventHandler`, no registran
   `ServerMessageEvents.CHAT_MESSAGE`, no traducen eventos y no importan
   features.
2. **Son paquetes, no código embebido.** `sync-bukkit` y `sync-fabric` se
   descargan e instalan como cualquier paquete. `packages.list` declara su
   plataforma/entorno y el Manager instala solo el compatible con
   `PlatformInfo`; un paquete incompatible no se activa.
3. **Son adaptadores de plataforma.** Pueden importar Bukkit/Paper o Fabric,
   pero no features, no `host` concreto y no otros sincronizadores. Consumen
   `MessageDispatch`, `ActorDirectory`, `PermissionChecker`,
   `UserLanguageStore` y los puertos neutrales que necesiten desde
   `ServiceRegistry`.
4. **Ciclo de vida real.** El paquete registra sus listeners en `start()` y los
   desregistra en `stop()`. Si la API de la plataforma no permite desregistrar
   un listener, la desactivación se marca `RESTART_REQUIRED`; no se deja un
   listener huérfano ni se finge que el unload fue limpio.
5. **La traducción termina en `Message`.** El sincronizador no decide formato,
   routing, traducción, canales ni entrega. Solo convierte el evento de
   plataforma a la representación neutral y la inyecta en el pipeline. No es un
   `SyncSink`: es una fuente de eventos local; el fan-out a sinks lo hace
   `sync-bus`.
6. **El guard lo verifica.** `checkArchitecture` permite el SDK de plataforma
   solo en `sync-bukkit`, `sync-fabric` y el bootstrap del host; falla si un
   módulo neutral o un adapter de feature importa Bukkit/Paper/Fabric.
7. **No hay doble camino.** Al cerrar F4c se borran los listeners de los hosts.
   No se conserva un fallback compilado ni una activación implícita.

##### Arquitectura objetivo

```text
┌──────────────────────────────────────────────────────────────────────┐
│ spigot-host / fabric-host                                            │
│ - bootstrap del runtime                                              │
│ - implementa PlatformPorts y registra comandos                       │
│ - NO registra listeners de eventos ni traduce mensajes               │
│ - NO importa ningún módulo de feature                                │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ core-api + suite-runtime
┌───────────────────────────────▼──────────────────────────────────────┐
│ suite-runtime                                                        │
│ - SuiteRuntime: ciclo de vida del conjunto                           │
│ - ModuleHost: carga, activa, desactiva, actualiza y descarga         │
│ - ModuleClassLoader: aislamiento por módulo                          │
│ - ServiceRegistry, EventBus, Scheduler, ResourceTracker              │
│ - SuiteHost/MessageDispatcher construidos desde servicios            │
│ - ManagerFacade como orquestador de paquetes                         │
└───────────────┬───────────────────────────────────┬──────────────────┘
                │                                   │
┌───────────────▼───────────────────┐   ┌───────────▼──────────────────┐
│ core-api + kernel                 │   │ manager-spi / core / apt     │
│ contratos, mensajes, puertos,     │   │ paquetes, estado, resolver,  │
│ ModuleActivator, ModuleContext    │   │ instalación, activación      │
└───────────────▲───────────────────┘   └──────────────────────────────┘
                │ solo core-api
┌───────────────┴──────────────────────────────────────────────────────┐
│ modules/*.jar instalados en library/packages                         │
│ textformatter, iflow-*, chattranslator, gtranslate, ltranslate,      │
│ sync-*, observability, presets, messages, ...                        │
│ implementan ModuleActivator y usan ModuleContext                     │
│                                                                      │
│ sync-bukkit / sync-fabric: paquetes de plataforma, uno por entorno   │
│ registran listeners, traducen a Message y despachan al pipeline      │
└──────────────────────────────────────────────────────────────────────┘
```

No existe una flecha `suite-runtime -> feature`. Las features se descubren
desde `library/status` y se cargan desde `library/packages`; nunca se importan
en tiempo de compilación.

##### Contrato de módulo

`Module` se conserva como descriptor (la spec prohíbe convertirlo en una clase
que el bootstrap instancie reflectivamente). Se añade un contrato de activación
separado:

```java
public interface ModuleActivator {
    ModuleDescriptor descriptor();
    void start(ModuleContext context) throws Exception;
    void stop() throws Exception;
}
```

El JAR declara:

```text
META-INF/services/me.majhrs16.suite.api.ModuleActivator
```

El `ModuleHost` lo descubre con:

```java
ServiceLoader.load(ModuleActivator.class, moduleClassLoader);
```

`ModuleContext` no expone Bukkit ni Fabric:

```java
public interface ModuleContext extends AutoCloseable {
    String moduleId();
    Path configDir();
    PluginLogger logger();
    ServiceRegistry services();
    EventBus events();
    Scheduler scheduler();
    ResourceTracker resources();
    PlatformInfo platform();
}
```

`ServiceRegistry` sustituye a `ServiceLoader` para servicios vivos:

```java
public interface ServiceRegistry {
    <T> Registration register(Class<T> type, T service);
    <T> Optional<T> find(Class<T> type);
    <T> List<T> findAll(Class<T> type);

    interface Registration extends AutoCloseable {
        void close();
    }
}
```

Cada registro queda asociado al `moduleId`; al desactivar el módulo se cierran
sus registros, se cancelan sus tareas y se liberan sus recursos.

##### `ModuleHost`: carga, activación y hot upgrade

```java
public final class ModuleHost implements AutoCloseable {
    public List<ActivationResult> loadInstalled();  // escanea library/packages
    public ActivationResult activate(Path jarPath); // carga JAR + start()
    public DeactivationResult deactivate(String id);
    public Map<String, ModuleState> snapshot();
    // upgrade(id, newJar) llega en F7
}
```

Estados:

```java
public enum ModuleState {
    INSTALLED,
    ACTIVE,
    FAILED,
    RESTART_REQUIRED,
    REMOVED
}
```

Reglas del loader:

- Un `ModuleClassLoader` por módulo, con parent común de API.
- Parent-first solo para `me.majhrs16.suite.api.*` y `java.*`.
- Los contratos que cruzan paquetes viven en `me.majhrs16.suite.api.*`
  (`api.channel`, `api.iflow`, `api.message`); una feature no expone sus tipos
  propios a otra feature.
- Child-first para las clases de implementación del módulo.
- El classloader recibe primero el JAR del módulo y después los JARs de los
  paquetes de tipo dependencia; un paquete `PACKAGE` no se añade al
  classloader de otro módulo.
- Las librerías compartidas no viven en el host: el manager las entrega como
  paquetes de dependencia relocalizados y el classloader del módulo las resuelve
  desde el scope que el manager le asigna (C3).
- Acceso a dependencias desde `library/dependencies`; un JAR de dependencia
  fuera de ese directorio se rechaza y se registra.
- Cierre del classloader al desactivar.
- Si el classloader no puede cerrarse o quedan hilos/listeners, el estado pasa
  a `RESTART_REQUIRED`; no se marca como desactivado limpio.

Secuencia de activación:

1. Recibir del manager las entradas instaladas (o escanear `library/packages` en
   el arranque; `library/status` sigue siendo la fuente de qué está instalado).
2. Resolver orden y dependencias con `kernel.ModuleGraph` y
   `DependencyResolver`.
3. Crear classloader y alcance de servicios.
4. Cargar `ModuleActivator`.
5. Validar descriptor, versión de API, plataforma y capacidades.
6. Crear `ModuleContext`.
7. Ejecutar `start()`.
8. Si falla: cerrar registros, ejecutar `stop()` de limpieza, cerrar
   classloader y marcar `FAILED`.
9. Si funciona: confirmar servicios y marcar `ACTIVE`.

Secuencia de hot upgrade:

1. Instalar la nueva versión en una ruta versionada; no sobrescribir la activa.
2. Crear nuevo classloader y nuevo alcance de servicios.
3. Ejecutar `start()` de la nueva versión.
4. Si falla: descartar la nueva versión, mantener la anterior activa y reportar.
5. Si funciona: detener la versión anterior, intercambiar registros y cerrar el
   classloader anterior.
6. Si la versión anterior no libera recursos: mantenerla hasta reinicio, marcar
   `RESTART_REQUIRED` y no afirmar que el upgrade fue limpio.
7. Confirmar la instalación en `library/status` solo después de que la nueva
   versión quede activa; el estado runtime se anota en
   `<manager>/runtime/modules.status`.

Secuencia de desactivación:

1. Ejecutar `stop()`.
2. Cerrar `ResourceTracker` (listeners, tareas, sockets, executors).
3. Cerrar registros de servicios del módulo.
4. Cerrar classloader.
5. Actualizar estado a `INSTALLED` o `REMOVED`.

##### Manager: instalación y activación separadas

```text
repo update
  → actualiza índices

repo install <pkg>
  → resuelve dependencias y conflictos (Exclude/Provide)
  → descarga y verifica SHA-256
  → instala en library/packages
  → registra la instalación en library/status
  → ModuleHost.activate(...)
  → anota ACTIVE o FAILED en runtime/modules.status

repo remove <pkg>
  → ModuleHost.deactivate(...)
  → elimina archivos y dependencias huérfanas
  → actualiza library/status (instalación)

repo upgrade
  → resuelve y descarga nueva versión
  → instala en ruta versionada
  → ModuleHost.upgrade(...)
  → si falla, rollback a la versión anterior
  → si no se puede descargar la anterior, RESTART_REQUIRED en el estado runtime

arranque
  → ManagerFacade.initialize()
  → ModuleHost.loadInstalled()
  → activación en orden topológico
  → SuiteRuntime construye el pipeline desde ServiceRegistry
```

`library/status` registra **solo instalación**: nombre, versión, canal,
dependencias, archivos y hashes. No guarda si un módulo está activo en un
instante dado (C6/P2).

El estado runtime se guarda aparte, en `<manager>/runtime/modules.status`
(transitorio, no autoritativo):

- `ACTIVE`: activación confirmada.
- `FAILED`: instalación correcta, activación fallida.
- `RESTART_REQUIRED`: activación/desactivación incompleta que requiere reinicio.

La activación fallida no puede registrarse como correcta
(`manager-v2.md:546-548`). Un paquete instalado y un módulo activo son hechos
distintos y viven en archivos distintos.

##### Clasificación objetivo de módulos

| Categoría | Módulos | Destino |
|---|---|---|
| Núcleo técnico | `core-api`, `kernel`, `manager-spi`, `manager-core`, `manager-apt`, `suite-runtime` | Compilado en el host; sin features |
| Adaptadores | `spigot-host`, `fabric-host` | Solo `core-api` + `suite-runtime` + SDK de plataforma; bootstrap sin listeners de eventos |
| Sincronizadores de plataforma | `sync-bukkit`, `sync-fabric` | Paquetes de plataforma descargables; capturan eventos, traducen a `Message` y despachan; el Manager instala solo el compatible; no se embeben en el host |
| Paquetes de dependencia | `logger`, `transport`, SnakeYAML, JDA, Adventure y demás librerías compartidas | Paquetes de tipo dependencia; el manager los descarga, verifica, relocaliza y carga por módulo (C3) |
| Paquetes base | `textformatter`, `messages`, `chattranslator`, `iflow-vm`/`iflow-firewall`/`iflow-wasm` | Paquetes instalados por defecto; `Exclude`/`Provide` para motores iFlow |
| Paquetes opcionales | `gtranslate`, `ltranslate`, `sync-bus`, `sync-*`, `observability`, `presets`, `inworld`, `extension-api` | Paquetes neutrales; dependencias declaradas en `METADATA/package.list` |
| Producción auxiliar | `tester` (`PACKAGE` neutral + `TestProbe`) | Módulo de producción: benchmarking en runtime y traza del pipeline completo (C7); no es harness |
| Harness | `loadtest`, `performance`, `example-extension`, `fixtures` | Fuera del artefacto de producción |
| Eliminar | `common-legacy` (sin fuentes `main`), `ModuleLifecycleAdapter`, `SuiteHost.bootstrap` hardcodeado, dependencias muertas | Borrar en la fase correspondiente |

Decisión por defecto: **todo feature es paquete**, incluso `textformatter`,
`iflow-*`, `chattranslator` y `messages`. El núcleo solo contiene contratos,
kernel, manager y runtime; `logger` y `transport` son paquetes de dependencia,
no código del host. Los hosts no contienen listeners de eventos: `sync-bukkit`
y `sync-fabric` se instalan como paquetes de plataforma y el Manager elige el
compatible con el entorno. La suite instala un conjunto base en el primer
arranque; si falta, arranca en modo degradado con avisos explícitos, no con una
implementación falsa.

##### Reglas de dependencia

```text
spigot-host / fabric-host
  → core-api
  → suite-runtime
  → SDK de plataforma

suite-runtime
  → core-api
  → kernel
  → manager-spi / manager-core / manager-apt
  → logger / transport (librerías)
  ✗ ningún módulo de feature

core-api
  → JDK
  ✗ kernel, manager, host, features, plataforma

core-api (api.command / api.config)
  → JDK
  ✗ Bukkit / Fabric / host / features

kernel
  → core-api

manager-*
  → core-api
  → kernel
  ✗ host, features, plataforma

feature module
  → core-api (y, si hace falta, APIs de host-core)
  ✗ otros features salvo dependencia declarada
  ✗ Bukkit / Fabric / Minecraft

sync-bukkit / sync-fabric
  → core-api
  → SDK de plataforma (Bukkit/Paper o Fabric)
  → MessageDispatch / ActorDirectory / PermissionChecker / UserLanguageStore
  ✗ features, host concreto, otros sync

módulo platform-specific
  → core-api + SDK de plataforma
  → el SDK lo provee el host; el loader neutral no importa la plataforma
```

##### Qué se elimina, no se envuelve

- `ModuleLifecycleAdapter`: se elimina cuando `ModuleHost` cubra su contrato.
- `ModuleLifecycle` antiguo: se sustituye por la API de `ModuleHost`; no se
  mantiene como fachada no-op.
- `SuiteHost.bootstrap(...)` con features hardcodeados: se sustituye por
  `SuiteRuntime`; se elimina.
- `SuiteBootstrap` duplicado: se integra en `SuiteRuntime` o se reduce a helper
  de grafo; no se mantienen dos bootstraps.
- `ManagerFacade.discoverAll()` basado en classpath: se elimina; el
  descubrimiento sale de `library/status` y `library/packages`.
- Dependencias de features en adapters y `host`: se eliminan.
- Listeners de chat/join/quit/death en `spigot-host`/`fabric-host`: se mudan a
  `sync-bukkit`/`sync-fabric`; no se conserva el cableado compilado.
- Árboles de comandos duplicados en `spigot-host` (`DynamicCommandRegistrar`/
  `DynamicCommand`) y `fabric-host` (Brigadier): se sustituyen por el
  `CommandRegistry` neutral; el host solo conserva la invocación de primer
  nivel. Los strings `/suite` hardcodeados se sustituyen por
  `CommandContext.root()`.
- `gtranslate`/`ltranslate` como `implementation` en adapters: pasan a ser
  paquetes o `runtimeOnly` del runtime, nunca dependencia de compilación del
  adapter.
- `presets` y `manager-*` sin uso en Fabric: se usan o se eliminan.
- `tester` sigue siendo paquete de producción (C7): ya se empaqueta como
  `PACKAGE` neutral; el adapter Spigot vive en `spigot-host` y Fabric deberá
  implementar su `TestProbe`. Nunca se mueve a harness.
- `common-legacy`: se elimina si sigue sin fuentes `main`.
- `sync-http` en Fabric: deja de ser una diferencia de adapter; se resuelve
  como paquete compartido.
- `ExtensionManager` con loader propio: se unifica con `ModuleHost` o se
  documenta como subsistema separado con contrato propio. No se mantienen dos
  loaders sin justificación.

##### Fases, puertas y definición de terminado

Cada fase termina con código, tests, documentación, eliminación del camino
viejo y guard de arquitectura. **Una fase no se cierra porque compile.**

| Fase | Objetivo | Entregables | Puerta de cierre |
|---|---|---|---|
| **F0. Inventario y contrato** | Congelar el estado real y aprobar este ADR | Inventario de módulos, grafo actual, lista de deuda, contratos `ModuleActivator`/`ModuleContext`/`ServiceRegistry`/`PlatformPorts` | ✅ ADR aceptado; inventario verificable; decisiones cerradas en «Decisiones cerradas por F0» |
| **F1. API de ciclo de vida** | Introducir contratos sin tocar aún el arranque | `ModuleActivator`, `ModuleContext`, `ServiceRegistry`, `EventBus`, `Scheduler`, `ResourceTracker`, `PlatformPorts`, `ModuleState`; tests de contrato | ✅ `core-api` compila sin dependencias de features; 8 tests de contrato verdes |
| **F2. ModuleHost POC** | Cargar un JAR real de fixture desde disco | `ModuleClassLoader`, `ModuleHost`, activación/desactivación, orden topológico, aislamiento, cierre de classloader; test de dos versiones de una misma clase | ✅ `src/suite-runtime` carga JARs reales generados en test, activa en orden de dependencias, aísla dos versiones de la misma clase, libera recursos y marca `FAILED` sin registrar servicios |
| **F3. Manager + activación** | Unir paquetes con runtime | `PackageRuntime` en `suite-runtime`; instalación local y repo, activación/desactivación, estado runtime separado en `<manager>/runtime/modules.status`; tests con ZIPs y JARs reales | ✅ Instalar activa; activación fallida no cambia `library/status`; remover desactiva; dependencias y `Exclude`/`Provide` cubiertos por manager-core/apt. Rollback de upgrade se cierra en F7 |
| **F4a. SuiteRuntime y puertos** | Crear el composition root neutral | `SuiteRuntime` en `suite-runtime` (ManagerFacade + ModuleHost + PackageRuntime + RuntimeStateStore), comandos neutrales, tests con `PlatformPorts` falso | ✅ `suite-runtime` sin dependencias de features; `SuiteRuntime` instala, activa, desactiva y arranca módulos ya instalados; 4 tests verdes |
| **F5. Migrar paquetes base** | Convertir features base en paquetes cargables | `textformatter`, `messages`, `chattranslator`, `iflow-vm`/`iflow-firewall`/`iflow-wasm` como `ModuleActivator`; contratos compartidos en `core-api` | ✅ Los seis paquetes base se instalan desde ZIP y activan; `ChannelRegistry`/`Channel` y `Router`/`RouteDecision`/`RouteOutcome`/`PolicyTarget`/`PermissionChecker`/`RateLimiter` viven en `core-api`; `logger` se carga como paquete de dependencia desde `library/dependencies`; tests reales verdes. El cableado compilado del host se retira en F6 |
| **F6. Migrar paquetes opcionales** | Convertir el resto de features y retirar el cableado compilado | `gtranslate`, `ltranslate`, `sync-bus`, `sync-*`, `observability`, `presets`, `tester`, `inworld`, `extension-api`; neutralizar `inworld` y mover su listener a `spigot-host`; eliminar el cableado de features en el host | Todos los paquetes instalados se activan desde `library/status` + `library/packages`; ningún feature queda compilado en el host |
| **F4b. Adapters delgados** | Cerrar el acoplamiento de los adapters | `SpigotPlatformPorts`, `FabricPlatformPorts`, cableado de `SuiteRuntime`, guard `checkArchitecture` | `spigot-host`/`fabric-host` compilan solo con `core-api` + `suite-runtime` + SDK; guard de arquitectura verde |
| **F4c. Sincronizadores de plataforma** | Sacar la captura de eventos de los hosts | `sync-bukkit`, `sync-fabric`, metadata de entorno, listeners traducidos a `Message`, activación/desactivación y hot unload | `spigot-host`/`fabric-host` no contienen `@EventHandler` ni `ServerMessageEvents.CHAT_MESSAGE`; cada paquete se instala solo en su entorno, traduce y despacha; guard verde y tests de ciclo de vida |
| **F4d. API de configuración y comandos** | Estandarizar la superficie de configuración de cada módulo | `api.command` (`CommandRegistry`, `CommandSpec`, `CommandContext`, `CommandRegistration`), `api.config` (`ConfigRegistry`, `ConfigSection`), comandos del runtime sobre la misma API, tab completion, permisos y desregistro por ciclo de vida | Un módulo registra y retira su árbol de comandos sin tocar el host; conflictos fallan explícitamente; `DynamicCommand*` y el Brigadier de Fabric dejan de duplicar el árbol; tests de registro, desregistro, permisos y colisiones |
| **F4e. Raíz de comandos relocalizable** | Desacoplar la raíz `/suite` del código de módulos y hosts | `CommandRoot`, configuración `root`/aliases en `config/suite/commands.yml`, dispatcher neutral root-relative, re-registro en caliente o `RESTART_REQUIRED` | El mismo árbol funciona bajo `suite`, `suite module` y `tf` sin recompilar módulos; tests de root multi-segmento, aliases, colisión y cambio de raíz |
| **F7. Hot upgrade/unload real** | Cerrar el objetivo del usuario | Upgrade v1→v2 con dos classloaders, rollback, detección de fugas, `RESTART_REQUIRED`, liberación de listeners/tareas/sockets | Test de upgrade exitoso y fallido; sin hilos/classloaders filtrados en fixture; estado de instalación coherente y estado runtime en `<manager>/runtime/modules.status` |
| **F8. Empaquetado y seguridad** | Dejar el modelo reproducible | ZIPs de módulos, separación host/módulos, SHA-256, modelo de responsabilidad del repositorio, documentación de límites | `./gradlew build` y `checkArchitecture` verdes; shadow JAR del host sin features; documentación de hot reload y reinicio |

**F5 cerrada:** `textformatter`, `messages`, `chattranslator`, `iflow-vm`,
`iflow-firewall` e `iflow-wasm` tienen activador y la tarea `modulePackage`
genera sus ZIP con `METADATA/package.list`; `PackageRuntime` los instala y
activa. El classloader resuelve JARs de paquetes `DEPENDENCY` desde
`library/dependencies`; `logger` es el primer paquete de dependencia real de
`iflow-vm`. Los contratos que cruzan módulos viven en `core-api`
(`api.channel`, `api.iflow`) y los motores publican el servicio `Router`. La
retirada del cableado compilado del host pertenece a F6/F4b; los listeners de
eventos se mudan a `sync-bukkit`/`sync-fabric` en F4c.

**F6 en curso:** `transport` se empaqueta como `DEPENDENCY`; `gtranslate` y
`ltranslate` tienen `ModuleActivator`, declaran `transport@>=2.1.0` y publican
`TranslatorProvider` en el registro de servicios. `chattranslator` se suscribe a
`ModuleActivatedEvent` y `ModuleDeactivatedEvent` y reconstruye su
`TranslatorManager` cuando un proveedor aparece o desaparece, sin reiniciar el
host. El test de `suite-runtime` instala los cuatro paquetes, comprueba que
`chattranslator` ve google/libre y que al descargar `gtranslate` se queda solo
con libre. `sync-bus` ya es paquete: su contrato `SyncBus` vive en `core-api`,
`SyncBusActivator` publica el bus en el registro y un sink registrado después de
`start()` arranca de inmediato; el test de `suite-runtime` instala el ZIP real,
instala un paquete de sink y comprueba que al descargarlo el bus lo desregistra.
Las utilidades YAML viven en `sync-common`, un paquete `DEPENDENCY` que los
sinks declaran en su manifiesto. `sync-socket` ya es paquete: su activador lee
`tcp.yml`/`udp.yml` de su carpeta privada, registra cada sink usable y los
desregistra al descargarse; el test instala `sync-common`, `transport`,
`sync-bus` y `sync-socket`, comprueba los dos sinks y descarga el paquete.
`sync-telegram` sigue el mismo patrón con `config.yml`: registra el sink si hay
token y chat, y lo desregistra al descargar el paquete. `sync-http` lee
`outboundUrl`, `inboundPort` y credenciales de su `config.yml`, registra el sink
y lo retira al descargarse. `sync-websocket` arranca su servidor en el puerto
configurado, se registra en el bus y usa la fachada neutral
`api.metrics.Metrics` en lugar de depender de `observability`. `observability`
ya es paquete: publica `HealthCheckRegistry` y `Observability`, instala el sink
Prometheus y sirve `/health`/`/metrics`; `DebugEndpoint` se movió a
`host.debug` porque necesita estado del host. Las librerías de terceros
(Java-WebSocket, Gson, org.json, Prometheus, JDA) siguen en el classpath del
host hasta que F8 las empaquete como `DEPENDENCY`.

**F4d — API neutral completada (2026-10-29):** `core-api` publica
`api.command` (`CommandRoot`, `CommandPath`, `CommandSpec`, `CommandOption`,
`CommandSource`, `CommandContext`, `CommandRegistry`, `CommandRegistration`) y
`api.config` (`ConfigRegistry`, `ConfigSection`, `ConfigRegistration`).
`SuiteRuntime` crea `RuntimeCommandRegistry`/`RuntimeConfigRegistry`,
`ModuleContext` entrega vistas module-scoped, exige que el árbol del módulo
esté enraizado en su `moduleId` y que la sección de configuración use ese mismo
namespace; `register(CommandPath, CommandSpec)` permite una rama explícita.
Los comandos del runtime (`help`, `list`, `state`, `install`, `remove`,
`upgrade`, `repo`) son nodos normales del mismo árbol. `SuiteCommand` se
eliminó; el host solo necesita registrar el comando raíz que delega en el
registro neutral, y ese registro puede cerrarse y volver a abrirse. Tests:
contrato de API, dispatch jerárquico, colisiones, permisos, usage, opciones, tab
completion y desregistro al desactivar un módulo. Queda pendiente la eliminación
física de `DynamicCommandRegistrar`/`DynamicCommand` y del Brigadier de Fabric,
que se ejecuta al adelgazar los hosts en F4b/F4c; no se añadió una fachada
temporal. Esta entrega es la parte neutral de F4d: la fase no se cierra hasta
que F4b/F4c permitan retirar los árboles de host.

**`sync-discord` desacoplado (2026-10-29):** el paquete neutral ya no importa
JDA ni `transport`. Los puertos `DiscordConnection`, `DiscordConnectionFactory`,
`DiscordSettings`, `DiscordMessage` y `DiscordListener` viven en `core-api`
(`api.discord`). `DiscordSink` contiene solo la lógica del módulo y resuelve el
adaptador configurado (`transport: jda|jdk`). Hay dos paquetes adaptadores:
`sync-discord-jda` (JDA, provee `discord-transport`) y `sync-discord-jdk`
(WebSocket JDK + REST, provee `discord-transport`). Ambos devuelven de
`connect()` sin esperar la red; el handshake corre en un hilo daemon y `send()`
falla rápido si el transporte no está listo. El paquete neutral se activa
aunque no haya adaptador: se suscribe a `ModuleActivatedEvent` y
`ModuleDeactivatedEvent`, registra el sink cuando aparece la fábrica configurada
y lo retira cuando el adaptador se descarga; el orden de instalación deja de
importar. Tests: fake de `DiscordConnectionFactory` en el paquete neutral,
servidor WebSocket loopback en el adaptador JDK, y un paquete adaptador falso
instalado después del paquete neutral en `PackageRuntimeTest` (adjunta y retira
el sink al instalar/descargar el adaptador). `spigot-host` ya usa `DiscordSink`
+ `JdaDiscordConnectionFactory` en su cableado temporal.

**`sync-velocity` desacoplado (2026-10-29):** el sink neutral es
`sync-velocity-core` (cola, retry, mapping, codec, métricas y health) y no
importa `com.velocitypowered`. Los puertos `VelocityConnection`,
`VelocityConnectionFactory`, `VelocitySettings` y `VelocityListener` viven en
`core-api` (`api.velocity`). El plugin Velocity real es `sync-velocity`, que
registra el canal y traduce `PluginMessageEvent`; su JAR incluye las clases
neutrales porque Velocity carga el plugin, no el runtime de la suite. El paquete neutral se activa sin adapter y
se engancha/desengancha por `ModuleActivatedEvent`/`ModuleDeactivatedEvent`.
Tests: `VelocitySinkTest` con `FakeConnection`, `VelocityProxyConnectionTest`
con Mockito y `PackageRuntimeTest` con un adapter falso instalado después del
paquete neutral.

**F6 restante — bloqueado por F4b/F4c/F7, no por falta de empaquetado:**
- `inworld` ya es un `PACKAGE` neutral (`InWorldHandler`/`InWorldPlatform`) y
  se empaqueta; el adapter Bukkit (`SpigotInWorldListener`) vive en
  `spigot-host`. `tester` también es neutral (`TestProbe`) y su adapter Spigot
  (`SpigotTestProbe`) vive en `spigot-host`.
- `presets` ya se empaqueta como `PACKAGE` y aplica mediante el puerto neutral
  `PresetTarget`; `FilePresetTarget` persiste el layout canónico y
  `PresetsActivator` publica `PresetManager`. Falta el adapter de host que
  seleccione el preset y recargue los módulos (F4b).
- `extension-api` ya usa `MessageDispatch`, `PermissionChecker` y
  `ActorDirectory`; no importa `host`.
- `spigot-host` y `fabric-host` siguen construyendo sinks, observability,
  inworld y extensiones directamente. `tester` ya es neutral, pero
  `spigot-host` aún lo construye con `SpigotTestProbe` en vez de resolver
  `TestServiceFactory` desde el runtime. La retirada de ese cableado es el
  primer trabajo de F4b: hosts sobre `SuiteRuntime` + `PlatformPorts`, sin
  imports de features. La captura de eventos de chat/join/quit/death se muda a
  `sync-bukkit`/`sync-fabric` en F4c, no se queda en el host.

**F4b — punto de partida medido:** `spigot-host` importa directamente al menos
9 paquetes de feature (`sync-socket`, `sync-websocket`, `sync-telegram`,
`sync-discord`, `sync-http`, `syncbus`, `observability`, `inworld` y
`extension`; `inworld` y `tester` ya son neutrales) y `fabric-host` al menos 7
(sin `sync-http` ni `inworld`).
El guard de arquitectura (`checkArchitecture`) ya existe y está verde: escanea
los módulos neutrales y adaptadores allowlisted, y falla si un módulo neutral
importa un SDK de plataforma o el host. Los listeners de eventos siguen en los
hosts; su extracción a `sync-bukkit`/`sync-fabric` es F4c.

**Avance F4b (2026-10-29):** existen `SpigotPlatformPorts` y
`FabricPlatformPorts` con implementación real de `info`, `deliver`, `send`,
`findActor`, `onlineActors`, `hasPermission` y `registerCommand`. Spigot usa
`CommandMap` y permisos Bukkit; Fabric encola comandos hasta que el bootstrap
engancha el `CommandDispatcher` de Brigadier y usa el nivel de operador 2 como
regla de permisos mientras no haya proveedor externo. La desregistración de
comandos está cubierta con tests; Fabric retira los nodos raíz y Spigot retira
las entradas de `CommandMap`. Estas clases aún no están cableadas en los
hosts: la sustitución de `SuiteHost`/cableado de features y la retirada de
`DynamicCommand*`/Brigadier siguen pendientes para cerrar F4b/F4d.

##### Dirección obligatoria: hexágono por módulo y frontera plataforma↔módulo

**Decisión de arquitectura (2026-10-28):** cada módulo se estructura como un
hexágono. El núcleo contiene la lógica y los contratos; los adaptadores
implementan los puertos hacia tecnologías concretas. Ningún módulo neutral
importa JDA, Velocity, Paper, Bukkit ni Fabric. Ningún adapter de plataforma
importa paquetes de feature. La frontera es bidireccional y estricta.

Reglas:

1. **Núcleo neutral.** La lógica de `sync-discord`, `sync-velocity-core`, `inworld`,
   `tester`, `presets` y `extension-api` vive en clases sin tipos de
   plataforma. Los puertos se declaran en `core-api` (si los comparten varios
   módulos) o en el `api` del propio paquete.
2. **Adaptadores tecnológicos.** JDA, Velocity API, Paper/Bukkit y Fabric viven
   solo en adaptadores: `adapters/jda`, `adapters/velocity`,
   `sync-bukkit`, `sync-fabric` y el bootstrap de `spigot-host`/`fabric-host`.
   El paquete neutral declara el puerto y el adapter lo implementa; el paquete
   no depende del adapter.
3. **Eventos agnósticos.** El sincronizador de plataforma
   (`sync-bukkit`/`sync-fabric`) traduce el evento de Minecraft a un
   `Message`/evento neutral y lo entrega al runtime. El módulo consume ese
   evento; nunca registra listeners de Paper/Bukkit. El host solo aporta
   `PlatformPorts` y el bootstrap.
4. **Ciclo de vida no bloqueante.** `start()` de un paquete no espera red ni
   servidores externos. Las conexiones (Discord, Velocity, etc.) se hacen en
   un adaptador con estado propio, reintentos y `stop()` determinista; la
   activación del paquete no depende de que el servicio externo esté vivo.
5. **Sin stubs.** Un paquete no se empaqueta si su operación principal es un
   no-op. `Preset.apply(...)` debe aplicar de verdad canales, traductores,
   sync y configuración, con tests de cada efecto, antes de convertir
   `presets` en paquete.
6. **Sin compatibilidad indefinida.** No se añaden adaptadores de
   compatibilidad sin fase de eliminación. `ModuleLifecycleAdapter`,
   `SuiteHost.bootstrap()` hardcodeado y el cableado de features en adapters
   se eliminan en F4b, no se conservan como fachada permanente.
7. **Comandos y configuración neutrales.** Los módulos registran sus comandos
   jerárquicos y su configuración por `api.command`/`api.config`; el host solo
   traduce la invocación de plataforma. La raíz es un `CommandRoot`
   configurable y los módulos usan paths relativos, nunca `/suite` literal.

Aplicación por módulo:

- **`sync-discord`** — separar `DiscordSink`/lógica neutral, puerto
  `DiscordConnection` (conectar, enviar, recibir, cerrar) y adapter JDA.
  Los tests usan un `FakeDiscordConnection`; no hay red ni espera de 30 s.
  El adapter JDA es un paquete de plataforma/dependencia, no una dependencia
  de compilación del paquete neutral.
- **`sync-velocity`** — mismo patrón: `sync-velocity-core` contiene la lógica
  neutral y `sync-velocity` es el plugin/adapter Velocity que implementa el
  puerto de mensajería del proxy. El núcleo no importa `com.velocitypowered`.
- **`sync-bukkit` / `sync-fabric`** — paquetes de plataforma descargables.
  Registran los listeners de chat/join/quit/death, traducen el evento a
  `Message` y lo despachan por `MessageDispatch`; no contienen features ni
  lógica de formato, routing, traducción o entrega. El host no los embebe.
- **`inworld`** — el dominio de “mensajes en el mundo” es neutral. El registro
  del listener de Paper y la traducción del evento viven en `spigot-host`.
  `fabric-host` implementa el mismo puerto con su API. El módulo no importa
  Paper.
- **`tester`** — el simulador de jugador y la traza del pipeline son
  neutrales. `TestProbe` expone canales, router, dispatch, formato y detección
  de idioma; la entrega/observación real vive en el adapter
  (`SpigotTestProbe`). El paquete no importa Paper ni `host`; los tests usan un
  `TestProbe` falso.
- **`presets`** — `Preset.apply(PresetTarget)` convierte y aplica config,
  canales, traductores y sync; `PresetTarget` es el puerto neutral y
  `FilePresetTarget` implementa la persistencia canónica. El paquete ya se
  empaqueta y activa. Falta el adapter de host que seleccione el preset y
  recargue los módulos. Sin `host.config` en el paquete.
- **`extension-api`** — `ExtensionContext` expone un puerto neutral
  (`dispatch`, `findActor`, `hasPermission`, `translation`, `channels`,
  `languages`) en vez de `SuiteHost`/`MessageDispatcher`.

Orden de F4b:

1. Declarar puertos y eventos neutrales en `core-api`.
2. Extraer la lógica neutral de `sync-discord`, `sync-velocity-core`,
   `presets` y `extension-api`; `inworld` y `tester` ya están extraídos como
   `PACKAGE` neutrales (`InWorldHandler`/`InWorldPlatform` y `TestProbe`).
3. Implementar los adaptadores de plataforma/tecnología y el ciclo de vida
   no bloqueante.
4. Poner `spigot-host`/`fabric-host` sobre `SuiteRuntime` + `PlatformPorts` y
   retirar todos los imports de features.
5. `checkArchitecture` ya está verde: adapters sin features,
   módulos neutrales sin SDK de plataforma, sin stubs.
6. Extraer la captura de eventos a `sync-bukkit`/`sync-fabric` (F4c) y borrar
   los listeners del host en el mismo cambio.

**Estado:** `sync-discord`, `sync-velocity-core`/`sync-velocity`, `Preset.apply`
con `FilePresetTarget`, el empaquetado/activación de `presets`, el empaquetado
neutral de `tester` con `TestProbe`/`SpigotTestProbe`, el empaquetado neutral
de `inworld` con `InWorldHandler`/`InWorldPlatform`/`SpigotInWorldListener` y
el puerto neutral de `extension-api` completados al 2026-10-30. Después: el
adapter de host de `PresetTarget` (selección y recarga), el adelgazamiento de
`spigot-host`/`fabric-host` sobre `SuiteRuntime` + `PlatformPorts` (F4b) y la
extracción de la captura de eventos a `sync-bukkit`/`sync-fabric` (F4c).

Orden obligatorio: **F0 → F1 → F2 → F3 → F4a → F5/F6 → F4b → F4c → F4d → F4e → F7 → F8**.
F5 y F6 pueden solaparse solo si cada paquete tiene su propia puerta y no se
deja el camino viejo activo. F4b se ejecuta después de F5/F6 porque el
adelgazamiento de los adapters depende de que los features ya sean paquetes.
F4c se ejecuta después de F4b porque los sincronizadores necesitan el host
delgado y `PlatformPorts`; F4d/F4e van después de F4c porque la API de comandos
y la raíz relocalizable se apoyan en un host que ya no define features. F7 va
después de F4e para probar el unload de listeners, comandos y raíz.

##### Protocolo anti-vaguedad

Para evitar que el cambio quede a medias:

1. **Una fase, una entrega verificable.** No se abre la siguiente hasta que la
   anterior tenga su puerta verde.
2. **Cada fase incluye una lista de eliminación.** Si no se borra el camino
   viejo, la fase no está terminada.
3. **Prohibidos los no-ops.** Nada de `return parent`, `return false`, métodos
   vacíos, `TODO` o `UnsupportedOperationException` silenciosa para aparentar
   compatibilidad.
4. **Prohibidos los adaptadores de compatibilidad sin fecha.** Si algo se
   conserva, tiene que ser con contrato, tests y fecha de eliminación.
5. **Cada PR incluye tests del nuevo camino y un guard que impida volver al
   viejo.** El guard debe fallar el build, no solo avisar.
6. **Cada PR declara qué se elimina.** El mensaje de commit y la descripción
   deben incluir la sección «Eliminado».
7. **Si una fase no se puede completar, se revierte.** No se deja código a
   medias para «completarlo luego».
8. **No se acepta «paridad inmediata» como cierre de I-019.** I-019 se cierra
   solo cuando el host no conoce features y el Manager activa módulos.
9. **No se acepta un `suite-runtime` que dependa de features.** Si depende de
   ellas, es otro composition root disfrazado.
10. **No se acepta un loader que solo funcione con módulos en el classpath.**
    Debe cargar desde `library/packages`.
11. **No se acepta hot reload sin liberación de recursos.** Si no se puede
    liberar, se marca `RESTART_REQUIRED`.
12. **No se acepta marcar `ACTIVE` si `start()` falló.**

##### Criterios de aceptación finales

- [ ] `spigot-host/build.gradle` y `fabric-host/build.gradle` solo declaran
      `core-api`, `suite-runtime` y SDK de plataforma.
- [ ] `suite-runtime/build.gradle` no declara ningún módulo de feature.
- [ ] `host` no declara `textformatter`, `iflow-*`, `chattranslator` ni
      `transport` como implementaciones; solo APIs.
- [ ] Los adapters no importan `me.majhrs16.suite.sync*`,
      `me.majhrs16.suite.textformatter`, `me.majhrs16.suite.iflow*`,
      `me.majhrs16.suite.chattranslator`, `me.majhrs16.suite.observability`,
      `me.majhrs16.suite.manager*` ni `me.majhrs16.suite.presets`.
- [x] `ModuleHost` carga un JAR desde `library/packages`, lo activa y lo
      desactiva sin classpath de features.
- [x] `library/status` registra solo la instalación; el estado runtime
      (`ACTIVE`, `FAILED`, `RESTART_REQUIRED`) vive en
      `<manager>/runtime/modules.status`, nunca en `library/status`.
- [ ] `repo install` activa; `repo remove` desactiva; `repo upgrade` hace
      rollback si la nueva versión falla.
- [ ] Un test de upgrade v1→v2 demuestra que la v1 se detiene y se descarga, y
      que la v2 queda activa.
- [ ] Un test de upgrade fallido demuestra que la v1 sigue activa.
- [x] Un test de activación fallida demuestra que el paquete no se marca
      `ACTIVE`.
- [ ] Un test de arquitectura falla si un adapter vuelve a declarar un feature.
- [ ] No quedan `ModuleLifecycleAdapter`, `SuiteHost.bootstrap` hardcodeado,
      `ManagerFacade.discoverAll` por classpath ni dependencias muertas.
- [x] `./gradlew build` y `./gradlew checkArchitecture` pasan.
- [ ] La documentación de módulos explica `ModuleActivator`, `ModuleContext`,
      `library/packages`, `library/status`, hot upgrade y límites de reinicio.

##### Decisiones cerradas por F0 (2026-10-27)

Estas decisiones sustituyen la lista de preguntas abiertas anterior. F0 queda
cerrada con ellas y F1 puede empezar.

1. **Núcleo vs paquetes.** Todo feature es paquete. El núcleo técnico es
   `core-api`, `kernel`, `manager-*` y `suite-runtime`. `logger` y `transport`
   son paquetes de dependencia (C3), no código del host. Ningún JAR del host
   contiene features.
2. **`inworld` y `tester`.** `inworld` se parte en API neutral + implementación
   de plataforma. `tester` es un módulo de producción (C7): se distribuye como
   paquete neutral (`PACKAGE`) con `TestProbe` y adapters de plataforma, con
   benchmarking en runtime y traza del pipeline completo; no se mueve a
   harness.
3. **Extensiones.** `ExtensionManager` se unifica con `ModuleHost`: una
   extensión es un paquete de tipo extensión con el mismo ciclo de vida. No
   existe un `enum` cerrado de módulos conocidos; cualquier repositorio de
   terceros puede publicar paquetes custom (P11/C8).
4. **Versiones concurrentes.** Al estilo Debian (C9): la versión nueva del mismo
   paquete reemplaza a la anterior resolviendo el grafo. Durante un upgrade
   pueden solaparse v1 y v2 solo hasta confirmar el swap; no hay coexistencia
   permanente de dos versiones del mismo paquete.
5. **Librerías compartidas.** Son paquetes de dependencia (C3). El manager las
   descarga, verifica, relocaliza y carga; no viven en el host ni se compilan
   dentro de los JAR de módulo. El classloader del módulo las resuelve desde el
   scope de dependencias que el manager le asigna, no por parent-first contra el
   classloader del host.
6. **Metadatos.** El `packages.list` del repositorio es la fuente de verdad de
   qué paquetes existen y desde dónde se descargan (C4). El
   `METADATA/package.list` del ZIP es el manifiesto de instalación y se valida
   contra el índice; si discrepa, la instalación falla.
7. **Seguridad.** Hay sandbox por módulo, pero la responsabilidad principal es
   del dueño del repositorio (D6/C9). El manager verifica integridad con
   SHA-256 y el operador decide qué repositorios añadir. Firmas y allowlists son
   mecanismos opcionales de defensa en profundidad, no requisitos de la
   filosofía.

##### Consecuencias

*Positivas*
- Los adapters dejan de ser composition roots y se vuelven reemplazables.
- El Manager pasa a ser la fuente de verdad del ciclo de vida.
- Los módulos se versionan, instalan y actualizan por separado.
- Hot upgrade/unload se diseña con contrato, no con promesas.
- La arquitectura se puede verificar automáticamente.

*Negativas*
- Es un cambio de alto riesgo que toca todos los módulos.
- Introduce classloaders, registro de servicios, ciclo de vida y estados de
  activación: más superficie de fallo.
- El hot unload real es difícil con Bukkit/Fabric; habrá casos que requieran
  reinicio.
- Obliga a borrar código actual, no a envolverlo.
- Exige tests nuevos y un guard de arquitectura desde la primera fase.

##### Lo que este ADR no promete

- No promete que todos los módulos puedan actualizarse sin reinicio.
- No promete que todos los módulos sean neutrales a la plataforma.
- No promete compatibilidad con el arranque actual.
- No promete mantener `ModuleLifecycleAdapter`, `SuiteHost.bootstrap` ni el
  descubrimiento por classpath.
- No promete que la primera fase deje la suite funcional: la suite se rompe a
  propósito y se rearma por fases con puertas de calidad.

---

#### ADR-020: Un único `config/suite/messages.yml` para todas las plataformas

**Estado:** Aceptado (decisión del usuario en `docs/workdir/REPORT.md:234`)

**Contexto**

- `MessagesConfig` vive en `host` (`MessagesConfig.java:24`) y `storage.md` lo asignaba a «cada platform adapter».
- Duplicar el archivo por plataforma haría divergir los textos de UI entre Spigot y Fabric y obligaría a mantener dos copias de los defaults.
- Decisión registrada: «No puede haber un directorio por cada plataforma, se debería abstraer en un mismo archivo para todas las plataformas, y ese es `<manager>/config/suite/`».
- `Hecho verificado`: `HostPaths.suiteMessages()` resuelve `config/suite/messages.yml` con fallback a `messages.yml`; `MessagesConfig.load()` ya lo consume.

**Consecuencias**

*Positivas*
- Una sola fuente de verdad para los textos de UI, independiente de la plataforma.
- Los fallbacks mantienen funcionando instalaciones con el archivo plano anterior.
- `resetConfigs`/`copyDefaultsIfMissing` escriben y respaldan la misma ruta en Spigot y Fabric.

*Negativas*
- `host` (core) sigue conociendo un recurso de presentación; si en el futuro se quiere un `messages` por plataforma habría que introducir overrides por adapter.
- El archivo compartido puede contener claves específicas de una plataforma; hoy no hay validación que lo impida.

**Alternativas consideradas**

1. **`messages.yml` por adapter (propuesta original de `storage.md`)**
   - Ventajas: cada plataforma controla sus textos.
   - Limitaciones: duplicación y divergencia; dos defaults que mantener.
   - Evaluación: descartada por la decisión del usuario.

2. **Un archivo único con loader en `host` (elegida)**
   - Ventajas: fuente única, reutiliza `HostPaths`, sin duplicar clases.
   - Limitaciones: acoplamiento de `host` a un recurso de presentación.
   - Evaluación: aceptada; el acoplamiento se considera menor frente a la duplicación.

3. **Mover el loader a un módulo `presentation`/`messages` compartido**
   - Ventajas: separación estricta entre core y UI.
   - Limitaciones: nuevo módulo y dependencia adicional en ambos adapters; el módulo `messages` existente gestiona catálogos, no el archivo de configuración.
   - Evaluación: aplazada; no aporta valor inmediato sobre (2).

---

#### ADR-008: Mantener `chattranslator` (façade) y `translator` (proveedor) como capabilities separadas

**Estado:** Aceptado (2026-10-27).

**Contexto**

- `Hecho verificado`: `ChatTranslatorModule.java:22` provee `chattranslator`; `GTranslateModule.java:22-23` y `LTranslateModule.java:22-23` proveen `translator` y requieren `chattranslator`.
- `Hecho verificado`: ningún módulo requiere `translator`; la façade no exige un backend concreto.
- `Hecho verificado`: en `kernel.ModuleGraph` una `Requirement` no satisfecha deja el módulo inactivo (`ModuleGraph.java:259` y siguientes), es decir, los requisitos son obligatorios.
- La traducción es opcional: una instalación sin backends debe arrancar con `TranslationService` en modo no-op.
- La ambigüedad es de nomenclatura y de dirección del grafo, no de funcionamiento.

**Consecuencias**

*Positivas*
- La façade puede activarse sin backends y el pipeline degrada a no-op, que es el comportamiento actual de `ChatTranslator`.
- Los proveedores declaran explícitamente que necesitan la façade, lo que ordena la inicialización (façade primero, backends después).
- No hay cambios de contrato ni migración de capacidades.

*Negativas*
- Dos nombres (`chattranslator`, `translator`) para un mismo dominio invitan a error al declarar nuevos módulos.
- La dirección «proveedor requiere façade» es la inversa de la intuición habitual («façade requiere proveedor»); requiere documentación explícita.
- Si en el futuro se quisiera exigir al menos un backend, el modelo actual de `Requirement` no permite expresar «opcional».

**Alternativas consideradas**

1. **Mantener ambas capabilities y documentar la dirección (recomendada)**
   - Ventajas: cero churn; el grafo actual ya funciona y expresa la opcionalidad.
   - Limitaciones: la nomenclatura sigue siendo confusa sin documentación.
   - Evaluación: **recomendada**; añadir un test de kernel que verifique que `chattranslator` activa sin proveedores.

2. **Unificar en `translator` para todos**
   - Ventajas: un solo nombre.
   - Limitaciones: `ModuleDescriptor` no distingue rol (façade vs proveedor); no se podría saber si un `translator` es coordinador o backend; además rompe CT3 y los tests existentes.
   - Evaluación: descartada por pérdida de información semántica.

3. **Renombrar a `translation-facade` y `translation-provider`**
   - Ventajas: nombres autoexplicativos.
   - Limitaciones: cambio de contrato en todos los descriptores y tests; obliga a versionar la capability.
   - Evaluación: aplazada hasta una revisión mayor de contratos; no compensa ahora.

**Implementación (2026-10-27).** Se mantiene la opción 1 sin cambios de contrato.
`ChatTranslatorModuleTest` fija la semántica: `chattranslator` provee la façade y no
requiere backends; `GTranslateModule` sin façade queda en
`UNSATISFIED_REQUIREMENT`; con la façade presente ambos módulos resuelven. La
dirección documentada es **proveedor (`translator`) requiere façade
(`chattranslator`)**, nunca al revés, porque la traducción es opcional y el
pipeline debe degradar a no-op cuando no hay backends instalados.

---

#### ADR-021: API de configuración y comandos jerárquicos registrables

**Estado:** Aceptado (2026-10-29).

**Contexto**

- `PlatformPorts.Command` solo registra un comando plano: no hay subcomandos,
  tab completion ni registro por módulo.
- `SuiteCommand` hardcodea `/suite` y su propio `switch`; `spigot-host`
  mantiene `DynamicCommandRegistrar`/`DynamicCommand` con un árbol duplicado y
  `fabric-host` otro árbol Brigadier.
- Un módulo no puede aportar comandos propios sin tocar el host; al descargarse
  no hay forma de retirar su subárbol.
- La configuración de cada módulo no tiene una API neutral: cada paquete lee su
  YAML y expone comandos a mano.

**Decisión**

1. `core-api` publica `api.command`: `CommandRegistry`, `CommandSpec`,
   `CommandPath`, `CommandSource`, `CommandContext` y `CommandRegistration`.
2. `CommandSpec` es un árbol: nombre, aliases, descripción, permiso, uso,
   argumentos/opciones, hijos y ejecutor. El registro es por módulo y devuelve
   `CommandRegistration` (`AutoCloseable`).
3. `ModuleContext.commands()` entrega un registro module-scoped; el runtime
   cierra todas las inscripciones después de `stop()`. El path por defecto es
   el namespace del módulo; un path explícito se declara y también se retira al
   desactivar. Un módulo no puede dejar comandos huérfanos.
4. `core-api` publica `api.config`: `ConfigRegistry`, `ConfigSection` y
   `ConfigRegistration`. Un módulo registra su sección (namespace, defaults,
   esquema, validador y reload) y puede exponer comandos de configuración
   generados o propios. La configuración persistida no se borra al
   desregistrar; solo se retira la superficie de comandos/esquema.
5. `CommandRegistry` es el único punto de composición: los comandos del runtime
   (`list`, `state`, `install`, `remove`, `upgrade`, `repo`) usan la misma API;
   el host solo traduce la invocación de plataforma y no define comandos de
   features.
6. Conflictos de path/alias fallan explícitamente; no hay override silencioso.
   La política `force` solo aplica si se define explícitamente y deja warning.
7. Los comandos son neutrales: no importan Bukkit/Fabric; reciben
   `CommandSource`/`CommandContext` y responden por `reply`. La tab completion
   se deriva del árbol.
8. El árbol de comandos del host (`DynamicCommandRegistrar`/`DynamicCommand` y
   el Brigadier de Fabric) se elimina o se reduce a un adaptador de invocación;
   no se mantiene duplicado.

**Consecuencias**

- Cada módulo puede publicar comandos bajo su namespace sin conocer la
  plataforma.
- Desactivar un módulo retira su subárbol; reactivarlo lo vuelve a registrar.
- El host deja de ser dueño del árbol de comandos.
- Requiere tests de registro/desregistro, colisiones, permisos, tab completion y
  esquema de configuración.
- La configuración existente de cada módulo sigue siendo suya; la API
  estandariza la superficie, no impone un formato de archivo.

---

#### ADR-022: Raíz de comandos relocalizable

**Estado:** Aceptado (2026-10-29).

**Contexto**

- `/suite` está hardcodeado en `SuiteCommand`, mensajes de ayuda,
  `DynamicCommandRegistrar` y el Brigadier de Fabric.
- Cambiar a `/suite module` o `/tf` obligaría a tocar todos los módulos y
  hosts.
- Los módulos deben registrar rutas relativas, no la raíz.

**Decisión**

1. `CommandRoot` es un value object con segmentos (`["suite"]`,
   `["suite","module"]`, `["tf"]`) y aliases opcionales. Se declara en
   `commands.yml` (ruta canónica `config/suite/commands.yml`, `root: [suite]`),
   no en código.
2. `CommandRegistry` almacena paths relativos al root. El runtime compone
   `root + path` al registrar y al resolver. Los módulos nunca ven ni
   hardcodean `/suite`.
3. El adaptador de plataforma registra el primer segmento del root y delega el
   resto al dispatcher neutral. Para roots multi-segmento (`suite module`), el
   dispatcher consume el prefijo y enruta el resto.
4. Help, usage y errores usan `CommandContext.root()`; no se concatenan strings
   `/suite`.
5. Cambiar la raíz es cambiar configuración: `root: [suite]` →
   `root: [suite, module]` o `root: [tf]`; los módulos no cambian. Los aliases
   permiten mantener `/suite` y `/tf` a la vez. El `base-name` actual se
   sustituye por `root`; no se conserva como fallback permanente.
6. Si la raíz cambia en caliente, el runtime re-registra el comando raíz; si la
   plataforma no permite desregistrar, se marca `RESTART_REQUIRED`. No se
   mantiene un root viejo activo.
7. Conflictos de root con comandos existentes fallan explícitamente; no se roba
   un comando ajeno.
8. Tests: el mismo árbol de módulo bajo `suite`, `suite module` y `tf`; cambio
   de root sin recompilar módulos; root multi-segmento; alias; colisión;
   re-registro/restart.

**Consecuencias**

- Migrar la raíz es configuración, no refactor.
- Los módulos son independientes del nombre del comando raíz.
- El host solo necesita un adaptador de invocación de primer nivel.
- La documentación y los mensajes deben usar `CommandContext.root()`.

---

### Cobertura de tests exhaustiva — Iniciativa transversal

> **Situación actual (2026-10-27):** inventario real generado con `./gradlew testInventory` — **34 módulos Java** en `src/`:
> - **0 sin directorio de tests**
> - **0 directorios vacíos**
> - **34 con tests** (1–27 archivos); solo `iflow-vm` (27), `host` (12) y `textformatter` (7) superan 5 archivos
> - **10 módulos con reporte JaCoCo**: `iflow-vm` (86.2%/71.3%), `kernel` (91.1%/82.0%), `logger` (93.3%/78.6%), `messages` (93.3%/75.0%), `textformatter` (81.6%/66.7%), `host` (30.9%/23.8%), `spigot-host` (2.5%/4.6%), `transport` (6.0%/1.0%), `fabric-host` (0.0%/0.0%), `tester` (0.0%/0.0%)
> - **23 módulos con tests pero SIN reporte JaCoCo** (cobertura N/A) — requieren `jacocoTestReport`

**Inventario detallado (baseline T0 → T1 actualizado 2026-10-24):**

| Módulo | Test Dir | Archivos Test | Líneas Prod | Líneas Test | Cobertura Línea | Cobertura Rama | Gaps Críticos |
|---|---|---:|---:|---:|---:|---:|---|
| `src:common-legacy` | ✅ OK | 1 | N/A | 30 | N/A | N/A | Pocos tests (<3) |
| `src:core-api` | ✅ OK | 2 | N/A | 259 | N/A | N/A | Pocos tests (<3) |
| `src:example-extension` | ✅ OK | 1 | N/A | 45 | N/A | N/A | Pocos tests (<3) |
| `src:extension-api` | ✅ OK | 3 | N/A | 260 | N/A | N/A | `ExtensionContext` usa puertos neutrales, sin `host` |
| `src:fabric-host` | ✅ OK | 1 | 840 | 30 | 0.0% | 0.0% | Pocos tests (<3); Cobertura línea <70%; Cobertura rama <50% |
| `src:gtranslate` | ✅ OK | 3 | N/A | 229 | N/A | N/A |  |
| `src:host` | ✅ OK | 9 | 1503 | 1628 | 30.9% | 23.8% | Cobertura línea <70%; Cobertura rama <50% |
| `src:iflow-vm` | ✅ OK | 27 | 2618 | 6271 | 86.2% | 71.3% |  |
| `src:iflow-wasm` | ✅ OK | 1 | N/A | 32 | N/A | N/A | Pocos tests (<3) |
| `src:iflow-firewall` | ✅ OK | 5 | 2553 | 722 | 61.6% | 50.1% | Restauración literal del motor SpEL (44 tests); incluye validador del grafo del editor |
| `src:chattranslator` | ✅ OK | 1 | N/A | 350+ | N/A | N/A | 12 tests unitarios |
| `src:inworld` | ✅ OK | 3 | 349 | 356 | 88.1% | 74.2% | `PACKAGE` neutral; `InWorldHandler`/`InWorldPlatform`; `SpigotInWorldListener` en `spigot-host` |
| `src:kernel` | ✅ OK | 4 | 202 | 243 | 91.1% | 82.0% |  |
| `src:loadtest` | ✅ OK | 4 | N/A | 1120 | N/A | N/A |  |
| `src:logger` | ✅ OK | 4 | 75 | 405 | 93.3% | 78.6% |  |
| `src:ltranslate` | ✅ OK | 3 | N/A | 288 | N/A | N/A |  |
| `src:manager-apt` | ✅ OK | 3 | N/A | 317 | N/A | N/A |  |
| `src:manager-core` | ✅ OK | 3 | N/A | 683 | N/A | N/A |  |
| `src:manager-spi` | ✅ OK | 2 | N/A | 323 | N/A | N/A | Pocos tests (<3) |
| `src:messages` | ✅ OK | 1 | 30 | 53 | 93.3% | 75.0% | 18 tests integración |
| `src:observability` | ✅ OK | 2 | N/A | 105 | N/A | N/A | 15 tests integración |
| `src:performance` | ✅ OK | 2 | N/A | 135 | N/A | N/A | Pocos tests (<3) |
| `src:presets` | ✅ OK | 5 | N/A | 1152 | N/A | N/A | `Preset.apply` + `FilePresetTarget` reales; paquete y activador probados; adapter de host pendiente |
| `src:spigot-host` | ✅ OK | 4 | 590 | 3346 | 7.5% | 7.7% | Cobertura línea <70%; Cobertura rama <50%; `SpigotInWorldListener` cubierto con Mockito |
| `src:sync-bus` | ✅ OK | 1 | N/A | 116 | N/A | N/A | Pocos tests (<3) |
| `src:sync-discord` | ✅ OK | 3 | N/A | 250 | N/A | N/A | Paquete neutral: puerto en `core-api`, sin JDA |
| `src:sync-discord-jda` | ✅ OK | 1 | N/A | 130 | N/A | N/A | Adaptador JDA; tests sin red |
| `src:sync-discord-jdk` | ✅ OK | 3 | N/A | 320 | N/A | N/A | Adaptador JDK; tests con WebSocket loopback |
| `src:sync-http` | ✅ OK | 3 | N/A | 192 | N/A | N/A |  |
| `src:sync-socket` | ✅ OK | 3 | N/A | 202 | N/A | N/A |  |
| `src:sync-telegram` | ✅ OK | 2 | N/A | 173 | N/A | N/A | Pocos tests (<3) |
| `src:sync-velocity-core` | ✅ OK | 1 | N/A | 420 | N/A | N/A | Paquete neutral: puerto en `core-api`, sin Velocity API |
| `src:sync-velocity` | ✅ OK | 1 | N/A | 180 | N/A | N/A | Plugin/adapter Velocity real; tests con Mockito |
| `src:sync-websocket` | ✅ OK | 1 | N/A | 26 | N/A | N/A | Pocos tests (<3) |
| `src:tester` | ✅ OK | 3 | 271 | 1057 | 81.6% | 55.8% | `PACKAGE` neutral; `TestProbe` + `SpigotTestProbe`; suite completa con probe falso |
| `src:textformatter` | ✅ OK | 6 | 282 | 512 | 81.6% | 66.7% |  |
| `src:transport` | ✅ OK | 1 | 351 | 22 | 6.0% | 1.0% | Pocos tests (<3); Cobertura línea <70%; Cobertura rama <50% |

**Resumen:** 34 módulos totales · **34 con tests** · **0 sin test dir** · **0 vacíos** · 13 con cobertura real · 21 con tests pero sin reporte JaCoCo.

**Objetivo:** que **cada módulo** tenga una suite de tests unitarios y de componente con **cobertura significativa** (líneas/branches ≥ 70 % en código de producción, ≥ 90 % en SPI/API pública) y tests de regresión para cada bug/fix documentado.

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **T0 — Inventario y baseline** ✅ | Matriz `módulo × {archivos test, cobertura actual, gaps críticos}` generada por script CI | Tabla inline arriba con datos reales |
| **T1 — Scaffold obligatorio** ✅ | Todo módulo Java tiene `src/test` con JUnit 5 + AssertJ + Mockito; `build.gradle` incluye `testImplementation` y task `test` pasa en CI | **0 módulos sin directorio de tests; 0 directorios vacíos** |
| **T2 — Gates de cobertura por módulo** ✅ | `jacocoTestCoverageVerification` con reglas **por módulo** (no global): `minLineCoverage`, `minBranchCoverage`; falla el build si no se cumple | **Gates verdes en CI para todos los módulos** (umbrales según baseline T1) |
| **T3 — Tests de contrato SPI/API** ✅ | Cada `core-api`, `extension-api`, `manager-spi`, `kernel` tiene tests que validan contratos (ServiceLoader, interfaces, modelos inmutables) sin depender de implementaciones | **Tests de contrato pasan en aislamiento**; documentación de contratos generada |
| **T4 — Tests de integración por dominio** ✅ | `sync-*`, `transport`, `host`, `gtranslate`/`ltranslate`, `presets`, `inworld`, `observability`, `messages`: tests con testcontainers/mocks de red, disco y plataforma | Suite de integración ejecuta en CI (< 10 min) sin servidor real |
| **T5 — Tests de regresión obligatorios** | Cada bug/fix P0/P1 de `AUDITORIA.md`, commits `fix:` tiene al menos un test que falla sin el fix y pasa con él | Matriz bug→test inline en `docs/workdir/PLAN.md` |
| **T6 — Fuzzing y property-based** | Módulos de parsing/serialización (`textformatter`, `messages`, `iflow-vm` bytecode, `iflow-wasm` loader) usan jqwik o JUnit-Quickcheck | Campañas nocturnas en CI; hallazgos → issues automáticos |
| **T7 — Performance/soak baselines** | `loadtest`, `performance`: benchmarks JMH con umbrales de regresión (p99, throughput, heap) | Alertas automáticas si hay regresión > 10 % |

**Reglas de diseño:**
- **No tests de integración con servidor real** (Spigot/Paper/Fabric/Velocity): decisión de alcance vigente. Usar mocks, testcontainers o harnesses in-process.
- **Tests deterministas**: sin `Thread.sleep`, sin orden no determinista, sin estado global mutable entre tests.
- **Fixtures compartidas** en `src/fixtures/` (ya existe) para mensajes, canales, políticas, configs; versionadas con el código.
- **Mutación testing** (Pitest) opcional en T2/T3 para validar calidad de aserciones.
- **Documentación viva**: cada módulo tiene `src/<mod>/README.md` con sección «Tests» que explica cómo correr, qué cubre y cómo añadir casos.

**Próxima acción T4:** ✅ **COMPLETADO** — tests de integración por dominio para `sync-*`, `transport`, `host`, `gtranslate`/`ltranslate`, `presets`, `inworld` (14 tests neutrales: dominio, activador y descriptor), `observability` (15 tests) y `messages` (18 tests) — usando mocks de red, disco y plataforma; suite < 10 min en CI sin servidor real.

**Próxima acción:** W1 — ADR del motor Wasm (imports/exports v3, capabilities, límites, errores, lifecycle) y W2 — implementación minimal `.wasm` load/validate/instantiate/invoke con unit test.

---

## Estado real del proyecto (2026-10-04; actualizado 2026-10-23)

| Pieza | Estado | Verificación |
|---|---|---|
| core-api (modelo atómico + SPI) | ✅ estable, JDK-puro | tests |
| kernel (ServiceLoader + grafo semver/Tarjan) | ✅ | 13 tests |
| textformatter (MiniMessage + Channels + `<tr>`) | ✅ estable | tests |
| iflow-vm (implementación v2 de 16 bits) | 🧱 módulo `src/iflow-vm/`: base histórica; el contrato vigente es la spec v3 de 32 bits (V1–V5) | tests actuales |
| iflow-wasm (WebAssembly + Chicory) | 🧪 W0 validado (loader mínimo); W1 contrato, W2 runtime, W3 integración y W3.5 paridad v3 pendientes | build |
| host (SuiteHost+Dispatcher+loaders) | ✅ estable | 39 tests |
| gtranslate / ltranslate | ✅ | tests |
| sync-discord | ✅ Paquete neutral + adapters `sync-discord-jda`/`sync-discord-jdk`; `DiscordBridge` usa el sink neutral (respeta iFlow) | build + tests de paquete |
| sync-telegram | ✅ **SyncBus registered** (HTTP transport, long-poll) | build |
| sync-http/tcpudp | ✅ **SyncBus registered** (IP pinning SSRF, timeouts) | build |
| sync-velocity | ✅ `sync-velocity-core` neutral + `sync-velocity` plugin real (Velocity API 3.4.0, cola async, retry/backoff, métricas, health, dynamic discovery) | build + tests de paquete |
| sync-websocket | ✅ real (SO_REUSEADDR fix) | build |
| spigot-host | ✅ **COMPILA** (DynamicCommand, Registrar, Plugin, DiscordBridge, WS, TCP/UDP/Telegram, HealthCheckRegistry) | compile |
| fabric-host | ✅ **COMPILA** (Fabric 1.21 + Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents) | compile |
| web-editor | ⚠️ gates verdes; UI inestable; auditoría separada diferida | check+integración |
| manager-api | ✅ SPI estable | compile |
| manager-impl | ✅ compila (manifest validation obligatoria, capability check habilitado) | compile |
| presets | ✅ standard/rpg/staff/minimal + `Preset.apply(PresetTarget)` + `FilePresetTarget` real; paquete/activador listos; adapter de host pendiente | tests + paquete |
| inworld | ✅ `PACKAGE` neutral (`InWorldHandler`/`InWorldPlatform`); `SpigotInWorldListener` en `spigot-host` | tests + paquete |
| observability | ✅ metrics/debug/health (auth token, 127.0.0.1, custom port) | tests (15 integración) |
| extension-api | ✅ SDK estable | tests |
| example-extension | ✅ demo funcional | tests |
| tester | ✅ `PACKAGE` neutral con `TestProbe`/`TestServiceFactory` + `SpigotTestProbe`; suite runtime y PerformanceProfiler | tests + paquete |
| messages | ✅ i18n centralizado EN/ES | tests |
| chattranslator | 🧪 scaffold + facade + 12 unit tests; backends (gtranslate/ltranslate) dependen de core-api SPI | build + tests |
| i18n (FASE 5) | ✅ strings hardcodeados → MessagesCatalog | 98% |
| transport | ✅ HttpURLConnection + MessageCodec único (IP pinning SSRF, 307/308 body preserve) | tests |
| loadtest | ✅ JMH benchmarks | build |
| performance | ✅ profiling (CPU/heap/hotspot/cache/memory) | tests |

- ✅ **Monorepo Git** en `/home/majhrs16/Documentos/textformatter-suite` (rama `main`, remoto GitHub `majhrs16-official/TextFormatter-Suite`).
- ✅ **Arquitectura**: `suite/*` módulos Gradle (Java 17/21) + adapters `spigot-host` (plugin Paper 1.20.6+) y `fabric-host` (Fabric 1.21 + Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents, **completo y funcional**).
- ⚠️ **Web editor**: gates verdes (StateStore, diffing, validación incremental, paths.json; 108 tests unitarios + integración), pero la UI no se considera estable; queda diferido a una auditoría separada (ver «Decisiones de alcance vigentes»).
- ✅ **Suite corriendo en Spigot/Paper** (plugin `TextFormatterSuite` instalable, fat-jar construido, probado en servidor real Paper 1.20.6).
- ✅ **Suite corriendo en Fabric** (mod `TextFormatterSuite` instalable, thin-jar construido, Fabric 1.21 + Fabric API 0.100.5).
- ✅ **Módulos core compilando + tests pasando (histórico, 2026-10-04)**: core-api, kernel, textformatter, iflow (v2 congelado el 2026-10-23), gtranslate, ltranslate, sync-*, host, messages, tester, transport, manager-api, manager-impl, presets, inworld, observability, extension-api, example-extension, loadtest, performance, sync-websocket.
- ✅ **Module Manager (F12) núcleo completado**: version resolver (semver + env compat), dependency resolver (module.yml), SHA256 obligatorio, register() SPI-only, discoverAll(), manifest validation obligatoria.
- ✅ **Security Sprint 3 completado**: char[] tokens + Arrays.fill(), MiniEscape completo (< > \ { } [ ] ( ) # @), PAPI dynamic check, SpEL aislado en `iflow-firewall` con sandbox (ADR-001), SSRF protection (HttpTransport + IP pinning), DependencyVerification (verification-metadata.xml).
- ✅ **Clean Architecture (Translator SPI)**: `host` ya no depende de `gtranslate`/`ltranslate` en compile-time. Solo depende de `core-api` (SPI: `TranslatorProvider`, `TranslatorManager`). Proveedores se descubren via `ServiceLoader` en runtime. Tests usan `testImplementation` para translators.
- ✅ **sync-velocity**: ✅ `sync-velocity-core` neutral + `sync-velocity` plugin real (Velocity API 3.4.0, async queue, retry/backoff, métricas, health, dynamic discovery, advanced mapping)
- ✅ **sync-telegram, sync-http, sync-socket**: ✅ **SyncBus registered** - full SyncBus integration with per-sink isolation, retry/backoff

---

## Entorno de trabajo: skills, MCP y toolchain (2026-10-19)

Herramientas disponibles para trabajar en este repo. Las **globales** viven en
`~/.config/opencode/` y sirven para cualquier proyecto de esta máquina (no se
versionan); las de **proyecto** viven en `.opencode/` y viajan con el repo.

### Skills

| Skill | Ámbito | Cuándo usarla |
|---|---|---|
| `juzgar-rubrica` | **proyecto** | Puntuar las familias cualitativas de la rúbrica de métricas (documentación e implementación real) y refrescar `judge_scores.csv` |
| `architecture-decision-records` | global | Crear y mantener ADRs: contexto, alternativas, consecuencias |
| `auditoria-seguridad-calidad` | global | Auditar C/C++/Java: memoria, concurrencia, OWASP, calidad estática |
| `automated-test-engineer` | global | Diseñar suites de tests unitarios y de componente con alta cobertura |
| `low-level-systems-debugger` | global | Kernels, emuladores, CPU virtuales, MMU/MMIO, NASM, volcados |
| `release-notes` | global | Changelogs y notas de versión a partir de commits e hitos |
| `opencode` | integrada | Dudas sobre OpenCode: configuración, agentes, skills, SDK, CLI |
| `report` | integrada | Reportar un bug de OpenCode con diagnóstico |

### MCP

Declarados en `~/.config/opencode/opencode.jsonc` (ámbito global):

| Servidor | Tipo | Endpoint / comando | Para qué |
|---|---|---|---|
| `github` | local | `npx -y @modelcontextprotocol/server-github` (token vía `GITHUB_TOKEN`) | Issues, PRs, commits, búsqueda en repos |
| `puppeteer` | local | `npx -y @modelcontextprotocol/server-puppeteer` | Automatización de navegador |
| `gh_grep` | remote | `https://mcp.grep.app` | Buscar código real en repos públicos de GitHub |
| `context7` | remote | `https://mcp.context7.com/mcp` | Documentación actualizada de librerías y frameworks |

> ⚠️ **`puppeteer` está declarado y habilitado pero no se expone**: no aparece
> entre las herramientas de la sesión (solo `github`, `gh_grep` y `context7`).
> Revisar si se necesita automatización de navegador.

### Toolchain: JDKs disponibles

**No hay `java` ni `javac` en el `PATH` de la sesión.** Hay que exportar `JAVA_HOME`
(o usar rutas absolutas). Conviven varias instalaciones en `/opt`, y **no todas son
JDK**: las de `/opt/java` son JRE, sin `javac`.

| Ruta | Versión real | `javac` | Notas |
|---|---|---|---|
| `/opt/javac/x64/21` | OpenJDK **21.0.12** LTS | **Sí** | La correcta para este repo (target Java 17) |
| `/opt/javac/x64/8` | OpenJDK 1.8.0_492 | Sí | Solo para proyectos Java 8 |
| `/opt/javac/arm64/` | — | — | Vacío |
| `/opt/java/x64/21` | OpenJDK **25.0.2** LTS | No (JRE) | ⚠️ La carpeta dice 21 pero es 25 |
| `/opt/java/x64/11` | OpenJDK 11.0.29 | No (JRE) | |
| `/opt/java/x64/8` | OpenJDK 1.8.0_482 | No (JRE) | |
| `/opt/idea/jbr` | JBR **25.0.3** | Sí | Runtime de IntelliJ, no para el build |

Si se le pasa un JRE como toolchain, Gradle falla con
`Toolchain installation '...' does not provide the required capabilities: [JAVA_COMPILER]`.

Verificado el 2026-10-19 (compila sin red, todo desde `~/.gradle`):

```bash
export JAVA_HOME=/opt/javac/x64/21
./gradlew --offline build
```

> 🧱 **Toolchain de iFlow VM (2026-10-23, actualizado 2026-10-29).** `tools/iflow-compiler/` y
> `:src:iflow-vm:test` son la base de `iflow-vm`; se conservan y evolucionan a 32 bits. El compilador
> C de V6 vivirá en `tools/iflow-c/` y emitirá `.flowasm` v3; no sustituye al assembler. En paralelo,
> `iflow-wasm` usa Chicory y artefactos `.wasm`, no `.flow`.

### Criterio de uso

- `context7` **antes** de escribir contra una API de terceros (Gradle, Paper, Fabric, JUnit…).
- `gh_grep` / `github` para ver cómo lo resuelven proyectos reales, no de memoria.
- `auditoria-seguridad-calidad` + `automated-test-engineer` al revisar código Java propio.
- `architecture-decision-records` cuando una decisión estructural merezca ADR.
- `release-notes` al cerrar una tanda de trabajo.
- `juzgar-rubrica` para refrescar la rúbrica de madurez (ver `tools/project-metrics/`).

---

## Principios de Arquitectura Técnica

Esta sección recopila los principios técnicos de diseño, costumbres del proyecto y razonamiento del "por qué" de las decisiones arquitectónicas. Pertenece al PLAN; el manifiesto abstracto de diseño vive en `docs/prompts/diseno/philosophy.md`.

### P1. El Manager es el origen de la composición

El Manager de paquetes es quien actualiza índices, descarga artefactos, resuelve dependencias, instala, **inicializa en memoria** y gestiona el ciclo de vida completo de los módulos. Los adaptadores de plataforma (Spigot/Fabric) no son composition roots ni conocen la existencia de los módulos de feature. Solo implementan puertos de plataforma; la captura y traducción de eventos vive en los paquetes `sync-bukkit`/`sync-fabric`.

**Consecuencia:** `spigot-host` y `fabric-host` dependen solo de la API del núcleo + runtime de módulos + SDK de plataforma. Ninguna dependencia a `sync-*`, `textformatter`, `iflow-*`, `chattranslator`, `gtranslate`, `ltranslate`, `observability`, `manager-*` ni `presets`.

### P2. Instalar no es activar

La base de datos local (`library/status`) es un catálogo de programas instalados, no un registro de estado de ejecución. La presencia de un paquete implica que está instalado y que debe iniciarse. El estado de ejecución (encendido/apagado, fallido, requiere reinicio) no pertenece a `library/status`; si es necesario rastrearlo, se hace en un directorio temporal dentro del directorio del manager, nunca en el estado de instalación.

**Consecuencia:** `library/status` registra nombre, versión, canal, dependencias, archivos y hashes del artefacto. No guarda si un módulo está "activo" en un instante dado.

### P3. Los adaptadores de plataforma son delgados

Spigot y Fabric no deben conocer la existencia de ningún módulo de feature. Su propósito es simplemente implementar una API común entre plataformas y arrancar el runtime. La captura de eventos de Minecraft y su traducción a una representación interna agnóstica vive en paquetes de plataforma (`sync-bukkit`/`sync-fabric`), no en el adapter. El cableado de sinks y servicios de plataforma vive en el runtime de módulos (`suite-runtime`).

**Consecuencia:** El adapter se reduce a crear `PlatformPorts`, arrancar `SuiteRuntime` y delegar; los listeners y la traducción de eventos se cargan como paquetes `sync-bukkit`/`sync-fabric`.

### P4. Código arbitrario: seguridad compartida (sandbox + dueño del repo)

El manager tipo apt descarga e instala código arbitrario. Esa es la idea intencional. Hay sandboxing por módulo, pero el punto central no es el sandbox: **el dueño del repositorio es responsable de lo que publica**. La seguridad se basa tanto en el sandbox como en la confianza en el repositorio. El manager verifica integridad con SHA-256; el operador decide qué repositorios añadir y responde por esa confianza. Firmas y allowlists pueden añadirse como defensa en profundidad, pero no son requisitos de la filosofía.

**Consecuencia:** No se promete que el sandbox por sí solo sea suficiente. La confianza recae en el dueño del repositorio, complementada con verificación de integridad.

### P5. `force` significa forzar de verdad

El parámetro `force` no omite conflictos de forma silenciosa. Si un administrador usa `force`, es porque quiere forzar las cosas y sabe lo que hace. Esto incluye permitir la instalación de paquetes excluyentes si el administrador así lo decide, con la advertencia correspondiente. `force` es una señal de intención deliberada del operador, no un sinónimo de «ignorar problemas».

**Consecuencia:** `force` debe poder romper invariables de coexistencia cuando el admin lo solicita explícitamente.

### P6. Hot unload/upgrade real

El objetivo es descargar, instalar, activar y desactivar módulos en caliente, con liberación de recursos (listeners, tareas, hilos, sockets, referencias). Si una operación no puede completarse sin reinicio, se detecta y se comunica explícitamente (`RESTART_REQUIRED`). Nunca se finge éxito con no-ops, `return parent`, `return false` o métodos vacíos.

**Consecuencia:** Cada módulo implementa `ModuleActivator` con `start/stop`. Cada registro de servicio queda asociado al `moduleId` y se cierra al desactivar. Un classloader por módulo, cerrado al desactivar.

### P7. No hay doble camino

Cuando una funcionalidad se migra al runtime de módulos, su cableado compilado se elimina en el mismo cambio. No se mantienen dos formas de arrancar la suite ni dos mecanismos de descubrimiento de módulos.

**Consecuencia:** `ModuleLifecycleAdapter`, `SuiteHost.bootstrap()` hardcodeado y `ManagerFacade.discoverAll()` por classpath se eliminan cuando el runtime de módulos los sustituye.

### P8. Las librerías compartidas son paquetes de dependencia

SnakeYAML, JDA, Adventure, transporte y otras librerías no se compilan dentro del JAR del host ni de los módulos. Son dependencias gestionadas por el manager, quien se asegura de relocalizar y cargar dichas dependencias para cada paquete tipo módulo.

**Consecuencia:** Ningún JAR de módulo empaqueta librerías compartidas. El classloader del módulo las resuelve desde el paquete de dependencia relocalizado que el manager le asigna; el host no las expone como parent-first.

### P9. Los metadatos autoritativos vienen del repositorio, no del JAR

El manager descarga del repositorio el archivo `packages.list` y, a partir de ahí, lista las URL de cada ZIP y módulo. No se incluye una lista de paquetes dentro del JAR. El `METADATA/package.list` de cada ZIP es válido para resolver, pero la fuente de verdad de qué paquetes existen y desde dónde descargarlos es el `packages.list` del repositorio.

**Consecuencia:** El manager nunca infiere paquetes mirando el contenido de un JAR; consulta el índice remoto.

### P10. Principio de pluralidad

Si tienes opción a elegir, impleméntalas todas y déjaselo al usuario. No se fuerza un monolito a elegir un solo motor. Cada motor, backend o implementación es un módulo independiente instalable y reemplazable.

**Consecuencia:** `iflow-vm`, `iflow-wasm` e `iflow-firewall` son mutuamente excluyentes pero coexisten como paquetes independientes. El usuario elige cuál instalar.

### P11. Extensiones sin catálogo cerrado

Las extensiones son módulos simplificados para programación de terceros. No existe un `enum` con todos los módulos posibles. Cualquier repositorio de paquetes de terceros puede otorgar paquetes custom, fuera de lo que hace esta suite. El sistema de descubrimiento debe estar abierto a paquetes arbitrarios declarados en índices remotos.

**Consecuencia:** `ModuleKind` no enumera módulos conocidos. El discovery se basa en `packages.list` y `METADATA/package.list`, no en una lista hardcodeada.

### P12. Tester es un módulo de producción

El módulo `tester` no es harness ni herramienta de desarrollo excluida del artefacto final. Es un módulo de benchmarking en tiempo de ejecución y de prueba del pipeline completo: simula el envío de un jugador, traza la ejecución del mensaje etapa por etapa, inspecciona el comportamiento directo del sistema y lanza un check si la comprobación de todo el pipeline es correcto. Puede detectar fallos delicados, el punto exacto y la diferencia vs lo esperado.

**Consecuencia:** `tester` vive en el directorio de producción y se distribuye como cualquier otro módulo.

### P13. Romper para reestructurar, no parchear

Cuando la arquitectura actual no puede sostener el modelo deseado, no se parchea lo que hay. Se rompe la suite, se desarma todo, se reestructura lo que debe ir y lo que no, y se vuelve a armar como debería haber sido desde el principio. No se aceptan implementaciones a medias, stubs, no-ops ni parches que preserven el acoplamiento actual.

**Consecuencia:** Cada cambio que migra funcionalidad al runtime de módulos elimina el cableado viejo en el mismo cambio. No se mantienen dos formas de arrancar la suite.

### P14. Tests obligatorios por fase

Cada fase de trabajo termina con código, tests, documentación y eliminación del camino viejo. Una fase no se cierra porque compile; se cierra porque tiene su puerta verde.

**Consecuencia:** Si una fase no se completa, se revierte. No se deja código a medias para «completarlo luego».

### P15. Cada bug tiene su test

Cada bug debe tener un test que demuestre que está resuelto. No se considera cerrado un bug sin su prueba de regresión.

**Consecuencia:** Todo fix de bug debe ir acompañado de un test que falle antes del fix y pase después.

### P16. Organización por capa técnica

El directorio `src/` se organiza por capa técnica: `api`, `kernel`, `runtime`, `platform` y `packages`. `platform` contiene cada plataforma de Minecraft soportada; `kernel` contiene cada feature agnóstico, base, primario o importante para la arquitectura del sistema; `packages` contiene cada módulo independiente entre sí.

**Consecuencia:** `spigot-host` y `fabric-host` viven en `platform/`. Los módulos de feature viven en `packages/`. Los contratos del núcleo viven en `api/` y `kernel/`. `sync-bukkit` y `sync-fabric` son paquetes de plataforma: viven bajo `platform/` y se distribuyen como ZIP descargable, no dentro del JAR del host.

### P17. Los eventos de plataforma se capturan en `sync-<plataforma>`

La sección del host que intercepta eventos de Minecraft y los entrega al núcleo agnóstico es, por definición, un sincronizador de plataforma. Se implementa como paquete descargable: `sync-bukkit` para Spigot/Paper y `sync-fabric` para Fabric. Cada host instala solo el paquete compatible con su entorno; ninguno se embebe en el JAR del host.

**Consecuencia:** `spigot-host`/`fabric-host` no contienen `@EventHandler` ni `ServerMessageEvents.CHAT_MESSAGE`; solo aportan `PlatformPorts` y el bootstrap. Los paquetes `sync-bukkit`/`sync-fabric` registran listeners, traducen a `Message`, despachan por `MessageDispatch` y se desregistran en `stop()`; si la plataforma no permite desregistrar, se marca `RESTART_REQUIRED`.

### P18. Los comandos son un contrato neutral y registrable

Cada módulo publica sus comandos jerárquicos a través de una API neutral (`api.command`) y su configuración a través de `api.config`. El registro devuelve un `CommandRegistration`/`ConfigRegistration` ligado al ciclo de vida del módulo; al desactivarlo, el runtime retira su subárbol y su esquema. El host solo traduce la invocación de plataforma; no define comandos de features ni mantiene un árbol duplicado.

**Consecuencia:** `SuiteCommand` y los comandos del runtime usan la misma API que cualquier módulo. `DynamicCommandRegistrar`/`DynamicCommand` y el Brigadier de Fabric se reducen a un adaptador de invocación; no hay dos formas de registrar comandos.

### P19. La raíz de comandos es configuración, no código

La raíz (`/suite`, `/suite module`, `/tf`, etc.) es un `CommandRoot` configurable. Los módulos registran paths relativos y nunca hardcodean la raíz. El runtime compone `root + path` y expone `CommandContext.root()` para help/usage/errores.

**Consecuencia:** Cambiar la raíz es cambiar `config/suite/commands.yml`; no se recompilan módulos. Si el cambio en caliente no puede desregistrar el comando raíz de la plataforma, se marca `RESTART_REQUIRED`.

### P20. El compilador C es una herramienta offline del guest

El compilador C de iFlow VM (`tools/iflow-c/`) es una herramienta de build/CI,
no un módulo del host ni un servicio de runtime. Emite `.flowasm` v3 y el
assembler v3 produce la ROM; el servidor solo carga artefactos ya compilados y
verificados. No se compila C en el proceso del host ni se añade una toolchain
nativa al servidor.

**Consecuencia:** El host no crece con Clang/GCC/TinyCC ni con un compilador
propio. La superficie de seguridad sigue siendo la validación de la ROM (hash,
segmentos, permisos y límites). El editor puede invocar el compilador en
cliente/CI, nunca en el host. Un cambio de subconjunto, ABI o formato de salida
requiere enmendar ADR-023.

---

## Referencias cruzadas al ADR-019

Los siguientes principios se definen en detalle en el ADR-019 (sección «Principios adicionales de diseño del ADR-019») y se resumen aquí para visibilidad desde el PLAN:

- **C7. `tester` es un módulo de producción** — resumen: `tester` no es herramienta de desarrollo excluida del artefacto final; es un módulo de benchmarking en tiempo de ejecución y de prueba del pipeline completo. Ver ADR-019, «Principios adicionales de diseño del ADR-019», punto C7.
- **C8. Extensiones sin catálogo cerrado** — resumen: no existe un `enum` con todos los módulos posibles. Cualquier repositorio de paquetes de terceros puede otorgar paquetes custom, fuera de lo que hace esta suite. El sistema de descubrimiento debe estar abierto a paquetes arbitrarios declarados en índices remotos. Ver ADR-019, «Principios adicionales de diseño del ADR-019», y P11 de esta sección.
- **D4. No hay doble camino** — resumen: cuando una funcionalidad se migra al runtime de módulos, su cableado compilado se elimina en el mismo cambio. No se mantienen dos formas de arrancar la suite ni dos mecanismos de descubrimiento de módulos. Ver ADR-019, «Principios no negociables», punto 6, y P7 de esta sección.
- **C10. Sincronizadores de plataforma descargables** — resumen: la captura de eventos de Minecraft vive en `sync-bukkit`/`sync-fabric`, no en el host; el Manager instala solo el compatible y los hosts quedan como bootstrap. Ver ADR-019, «Principios adicionales de diseño del ADR-019», punto C10, y P17 de esta sección.
