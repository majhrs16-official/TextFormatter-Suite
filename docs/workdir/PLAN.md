# PLAN — TextFormatter Suite

> **Obligatorio leer y seguir [La Filosofia](/docs/prompts/diseno/philosophy.md)** antes de trabajar en este documento o en el código.
>
> Documento vivo: reléelo antes de cada sesión de trabajo para no perder el rumbo.
> Última actualización: 2026-10-27 (**doble línea paralela: `iflow-vm` (VM propia de 32 bits, con las mejoras de la auditoría) e `iflow-wasm` (WebAssembly + Chicory); paridad funcional VM↔Wasm según spec v3 pendiente (W3.5)**; **T4 completado**: inworld (26 tests), observability (15 tests), messages (18 tests) — todos con mocks, sin servidor real; antes: auditoría manual de iFlow en curso, solo lectura, sin cambios de código; SpEL aislado en `iflow-firewall` (motor legacy, ADR-001); `iflow-vm`/`iflow-wasm` sin SpEL; ABI de `HOST` v2 cerrada + mapa de memoria preventivo (`VmMemoryMap`), fabric-host completo, Telegram sink SyncBus, config schema single-source, SSRF TOCTOU fix, coverage gates raised, all `AUDITORIA.md` P0/P1/P2 fixed (histórico)).

> **Nota de estado (2026-10-27):** **ADR-019 reescrito** por decisión del usuario: I-019 deja de ser un arreglo de dependencias y pasa a ser una **reestructuración integral** de la suite. El objetivo es un runtime de módulos con carga dinámica, **hot unload/upgrade real**, Manager como orquestador del ciclo de vida y adapters Spigot/Fabric que solo implementan puertos de plataforma. La implementación está pendiente y se rige por fases con puertas de calidad y un protocolo anti-vaguedad. Ver la sección **ADR-019** más abajo.

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
| **V5 — Herramientas** | Compilador `.flowasm`, firmware, ejemplos y validador | Build reproducible + tests |

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

---

### Fixes de Inconsistencia (desde `docs/workdir/REPORT.md` — 2026-10-25)

> **Fuente:** Auditoría integral + decisiones manuales en `REPORT.md`. 9 items **FIX_NOW** (P0), 3 **FIX_LATER** (P1/P2), 4 **NEEDS_DESIGN** (ADR).

#### P0 — FIX_NOW (Bloquean arquitectura correcta)

| ID | Acción | Módulos afectados | Criterio de cierre |
|---|---|---|---|
| **I-002** | Añadir `require('sync-bus')` a submódulos sync | `sync-discord`, `sync-websocket`, `sync-telegram`, `sync-http`, `sync-velocity` | ✅ `grep -r "require.*sync-bus" src/sync-*/` → 6/6 |
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

**Implementación:** 0% — esta entrada fija la decisión, el diagnóstico y el plan; no se ha modificado código todavía.

> **Ubicación:** este ADR vive en `docs/workdir/PLAN.md`. No es una sección de
> `docs/workdir/ADR.md`; ese archivo es el documento histórico de decisiones base
> y no se modifica aquí. Se reescribe este ADR en lugar de crear uno nuevo para
> evitar fragmentar todavía más la arquitectura.

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
- `inworld`, `observability`, `extension-api` y `presets` dependen de `host`.
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
- `inworld` es el único feature que importa Bukkit directamente; no puede ser
  neutral tal como está.

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
   `fabric-host` dependen de `core-api` + `suite-runtime` + SDK de plataforma.
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

##### Arquitectura objetivo

```text
┌──────────────────────────────────────────────────────────────────────┐
│ spigot-host / fabric-host                                            │
│ - implementa PlatformPorts                                           │
│ - traduce eventos de plataforma a Message                            │
│ - registra listeners/comandos y delega en SuiteRuntime               │
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
    public void loadInstalled();                 // lee library/status
    public ActivationResult activate(String id); // carga JAR + start()
    public DeactivationResult deactivate(String id);
    public UpgradeResult upgrade(String id, Path newJar);
    public RuntimeSnapshot snapshot();
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
- Parent-first para `me.majhrs16.suite.api.*`, `java.*` y librerías compartidas
  declaradas por el host.
- Child-first para las clases de implementación del módulo.
- Acceso a dependencias desde `library/dependencies`.
- Cierre del classloader al desactivar.
- Si el classloader no puede cerrarse o quedan hilos/listeners, el estado pasa
  a `RESTART_REQUIRED`; no se marca como desactivado limpio.

Secuencia de activación:

1. Leer metadatos del paquete (`METADATA/package.list` y `library/status`).
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
7. Confirmar el cambio en `library/status` solo después de la activación.

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
  → registra INSTALLED en library/status
  → ModuleHost.activate(...)
  → registra ACTIVE o FAILED

repo remove <pkg>
  → ModuleHost.deactivate(...)
  → elimina archivos y dependencias huérfanas
  → actualiza library/status

repo upgrade
  → resuelve y descarga nueva versión
  → instala en ruta versionada
  → ModuleHost.upgrade(...)
  → si falla, rollback a la versión anterior
  → si no se puede descargar la anterior, RESTART_REQUIRED

arranque
  → ManagerFacade.initialize()
  → ModuleHost.loadInstalled()
  → ModuleHost.activateAll() en orden topológico
  → SuiteRuntime construye el pipeline desde ServiceRegistry
```

`library/status` debe distinguir, como mínimo:

- `INSTALLED`: archivos presentes, sin activación confirmada.
- `ACTIVE`: activación confirmada.
- `FAILED`: instalación correcta, activación fallida.
- `RESTART_REQUIRED`: activación/desactivación incompleta que requiere reinicio.
- `REMOVED`: retirado.

La activación fallida no puede registrarse como correcta
(`manager-v2.md:546-548`). El estado instalado y el estado activo son campos
distintos.

##### Clasificación objetivo de módulos

| Categoría | Módulos | Destino |
|---|---|---|
| Núcleo técnico | `core-api`, `kernel`, `logger`, `transport`, `manager-spi`, `manager-core`, `manager-apt`, `suite-runtime` | Compilado en el host; sin features |
| Adaptadores | `spigot-host`, `fabric-host` | Solo `core-api` + `suite-runtime` + SDK de plataforma |
| Paquetes base | `textformatter`, `messages`, `chattranslator`, `iflow-vm`/`iflow-firewall`/`iflow-wasm` | Paquetes instalados por defecto; `Exclude`/`Provide` para motores iFlow |
| Paquetes opcionales | `gtranslate`, `ltranslate`, `sync-bus`, `sync-*`, `observability`, `presets`, `extension-api` | Paquetes; dependencias declaradas en `METADATA/package.list` |
| Plataforma | `inworld` (Bukkit), `tester` (Spigot), handlers Fabric | Adapter o paquete marcado `Platform: bukkit`/`fabric`; nunca en el loader neutral |
| Harness | `loadtest`, `performance`, `example-extension`, `fixtures` | Fuera del artefacto de producción |
| Eliminar | `common-legacy` (sin fuentes `main`), `ModuleLifecycleAdapter`, `SuiteHost.bootstrap` hardcodeado, dependencias muertas | Borrar en la fase correspondiente |

Decisión por defecto propuesta: **todo feature es paquete**, incluso
`textformatter`, `iflow-*`, `chattranslator` y `messages`. El núcleo solo
contiene contratos, kernel, manager, runtime y adapters. La suite instala un
conjunto base en el primer arranque; si falta, arranca en modo degradado con
avisos explícitos, no con una implementación falsa.

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

módulo platform-specific
  → core-api + SDK de plataforma
  → cargado por un loader de plataforma, no por el loader neutral
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
- `gtranslate`/`ltranslate` como `implementation` en adapters: pasan a ser
  paquetes o `runtimeOnly` del runtime, nunca dependencia de compilación del
  adapter.
- `presets` y `manager-*` sin uso en Fabric: se usan o se eliminan.
- `tester` en producción: se mueve a harness o a paquete platform-specific.
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
| **F0. Inventario y contrato** | Congelar el estado real y aprobar este ADR | Inventario de módulos, grafo actual, lista de deuda, contratos `ModuleActivator`/`ModuleContext`/`ServiceRegistry`/`PlatformPorts` | ADR aceptado; inventario verificable; ninguna decisión abierta que bloquee F1 |
| **F1. API de ciclo de vida** | Introducir contratos sin tocar aún el arranque | `ModuleActivator`, `ModuleContext`, `ServiceRegistry`, `EventBus`, `Scheduler`, `ResourceTracker`, `PlatformPorts`, `ModuleState`; tests de contrato | `core-api` compila sin dependencias de features; tests de API verdes |
| **F2. ModuleHost POC** | Cargar un JAR real de fixture desde disco | `ModuleClassLoader`, `ModuleHost`, activación/desactivación, orden topológico, aislamiento, cierre de classloader; test de dos versiones de una misma clase | Un JAR de fixture se carga, arranca, detiene y descarga sin classpath de features; sin fugas detectables en el fixture |
| **F3. Manager + activación** | Unir paquetes con runtime | Instalación en `library/packages`, estado instalado/activo/fallido/reinicio, `repo install/remove/upgrade`, rollback básico | Instalar activa; activación fallida no se marca activa; remover desactiva; tests de `Exclude`/`Provide` y dependencias |
| **F4. SuiteRuntime y adapters** | Romper el acoplamiento de los adapters | `SuiteRuntime`, `SpigotPlatformPorts`, `FabricPlatformPorts`, traducción de eventos, comandos delegados; eliminar deps de features de los adapters | `spigot-host`/`fabric-host` compilan solo con `core-api` + `suite-runtime` + SDK; guard de arquitectura verde |
| **F5. Migrar paquetes base** | Convertir features base en paquetes | `textformatter`, `messages`, `chattranslator`, `iflow-vm`/`iflow-firewall`/`iflow-wasm` como `ModuleActivator`; eliminar el cableado compilado | Cada paquete carga desde JAR, registra su servicio y funciona; el camino viejo ya no existe |
| **F6. Migrar paquetes opcionales** | Convertir el resto de features | `gtranslate`, `ltranslate`, `sync-bus`, `sync-*`, `observability`, `presets`, `extension-api`; mover `inworld`/`tester` a plataforma o harness | Todos los paquetes se activan desde `library/status`; ningún feature queda compilado en el host |
| **F7. Hot upgrade/unload real** | Cerrar el objetivo del usuario | Upgrade v1→v2 con dos classloaders, rollback, detección de fugas, `RESTART_REQUIRED`, liberación de listeners/tareas/sockets | Test de upgrade exitoso y fallido; sin hilos/classloaders filtrados en fixture; estado coherente en `library/status` |
| **F8. Empaquetado y seguridad** | Dejar el modelo reproducible | ZIPs de módulos, separación host/módulos, firmas/checksums, repositorios de confianza, documentación de límites | `./gradlew build` y `checkArchitecture` verdes; shadow JAR del host sin features; documentación de hot reload y reinicio |

Orden obligatorio: **F0 → F1 → F2 → F3 → F4 → F5/F6 → F7 → F8**. F5 y F6
pueden solaparse solo si cada paquete tiene su propia puerta y no se deja el
camino viejo activo.

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
- [ ] `ModuleHost` carga un JAR desde `library/packages`, lo activa y lo
      desactiva sin classpath de features.
- [ ] `library/status` distingue `INSTALLED`, `ACTIVE`, `FAILED` y
      `RESTART_REQUIRED`.
- [ ] `repo install` activa; `repo remove` desactiva; `repo upgrade` hace
      rollback si la nueva versión falla.
- [ ] Un test de upgrade v1→v2 demuestra que la v1 se detiene y se descarga, y
      que la v2 queda activa.
- [ ] Un test de upgrade fallido demuestra que la v1 sigue activa.
- [ ] Un test de activación fallida demuestra que el paquete no se marca
      `ACTIVE`.
- [ ] Un test de arquitectura falla si un adapter vuelve a declarar un feature.
- [ ] No quedan `ModuleLifecycleAdapter`, `SuiteHost.bootstrap` hardcodeado,
      `ManagerFacade.discoverAll` por classpath ni dependencias muertas.
- [ ] `./gradlew build` y `./gradlew checkArchitecture` pasan.
- [ ] La documentación de módulos explica `ModuleActivator`, `ModuleContext`,
      `library/packages`, `library/status`, hot upgrade y límites de reinicio.

##### Decisiones abiertas que F0 debe cerrar

1. **Núcleo vs paquetes:** confirmar que todo feature es paquete y que solo
   `core-api`, `kernel`, `manager-*`, `suite-runtime`, `logger` y `transport`
   son núcleo técnico.
2. **`inworld` y `tester`:** decidir si son módulos platform-specific o código
   del adapter. Propuesta: `inworld` se parte en API neutral + implementación
   Bukkit/Fabric; `tester` se mueve a harness o paquete Spigot.
3. **Extensions:** decidir si `ExtensionManager` se unifica con `ModuleHost`
   (mismo ciclo de vida) o se mantiene como subsistema separado con contrato
   propio. Propuesta: unificar como `ModuleKind.EXTENSION`.
4. **Versiones concurrentes:** decidir si se permite coexistencia de versiones.
   Propuesta: una versión activa por módulo; durante upgrade se solapan v1 y v2
   solo hasta confirmar el swap.
5. **Librerías compartidas:** decidir el parent classloader para SnakeYAML,
   JDA, Adventure y transporte. Propuesta: librerías compartidas en el host,
   parent-first; los módulos no las empaquetan.
6. **Metadatos:** decidir si el descriptor autoritativo es
   `METADATA/package.list` o el JAR. Propuesta: el paquete manda para resolver;
   el `ModuleDescriptor` del JAR se valida contra él y falla si discrepa.
7. **Seguridad:** decidir si se exigen firmas además de SHA-256 y qué
   repositorios son de confianza. Propuesta: SHA-256 obligatorio, firma
   obligatoria para repositorios remotos, allowlist de repos.

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
| `src:extension-api` | ✅ OK | 2 | N/A | 132 | N/A | N/A | Pocos tests (<3) |
| `src:fabric-host` | ✅ OK | 1 | 840 | 30 | 0.0% | 0.0% | Pocos tests (<3); Cobertura línea <70%; Cobertura rama <50% |
| `src:gtranslate` | ✅ OK | 3 | N/A | 229 | N/A | N/A |  |
| `src:host` | ✅ OK | 9 | 1503 | 1628 | 30.9% | 23.8% | Cobertura línea <70%; Cobertura rama <50% |
| `src:iflow-vm` | ✅ OK | 27 | 2618 | 6271 | 86.2% | 71.3% |  |
| `src:iflow-wasm` | ✅ OK | 1 | N/A | 32 | N/A | N/A | Pocos tests (<3) |
| `src:iflow-firewall` | ✅ OK | 5 | 2553 | 722 | 61.6% | 50.1% | Restauración literal del motor SpEL (44 tests); incluye validador del grafo del editor |
| `src:chattranslator` | ✅ OK | 1 | N/A | 350+ | N/A | N/A | 12 tests unitarios |
| `src:inworld` | ✅ OK | 1 | N/A | 1100+ | N/A | N/A | 26 tests integración |
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
| `src:presets` | ✅ OK | 1 | N/A | 60 | N/A | N/A | Pocos tests (<3) |
| `src:spigot-host` | ✅ OK | 2 | 1438 | 161 | 2.5% | 4.6% | Pocos tests (<3); Cobertura línea <70%; Cobertura rama <50% |
| `src:sync-bus` | ✅ OK | 1 | N/A | 116 | N/A | N/A | Pocos tests (<3) |
| `src:sync-discord` | ✅ OK | 3 | N/A | 476 | N/A | N/A |  |
| `src:sync-http` | ✅ OK | 3 | N/A | 192 | N/A | N/A |  |
| `src:sync-socket` | ✅ OK | 3 | N/A | 202 | N/A | N/A |  |
| `src:sync-telegram` | ✅ OK | 2 | N/A | 173 | N/A | N/A | Pocos tests (<3) |
| `src:sync-velocity` | ✅ OK | 1 | N/A | 305 | N/A | N/A | Pocos tests (<3) |
| `src:sync-websocket` | ✅ OK | 1 | N/A | 26 | N/A | N/A | Pocos tests (<3) |
| `src:tester` | ✅ OK | 1 | 570 | 29 | 0.0% | 0.0% | Pocos tests (<3); Cobertura línea <70%; Cobertura rama <50% |
| `src:textformatter` | ✅ OK | 6 | 282 | 512 | 81.6% | 66.7% |  |
| `src:transport` | ✅ OK | 1 | 351 | 22 | 6.0% | 1.0% | Pocos tests (<3); Cobertura línea <70%; Cobertura rama <50% |

**Resumen:** 34 módulos totales · **34 con tests** · **0 sin test dir** · **0 vacíos** · 11 con cobertura real · 23 con tests pero sin reporte JaCoCo.

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

**Próxima acción T4:** ✅ **COMPLETADO** — tests de integración por dominio para `sync-*`, `transport`, `host`, `gtranslate`/`ltranslate`, `presets`, `inworld` (26 tests), `observability` (15 tests), `messages` (18 tests) — usando mocks de red, disco y plataforma; suite < 10 min en CI sin servidor real.

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
| sync-discord | ✅ JDA wired vía DiscordBridge (respeta iFlow) | build |
| sync-telegram | ✅ **SyncBus registered** (HTTP transport, long-poll) | build |
| sync-http/tcpudp | ✅ **SyncBus registered** (IP pinning SSRF, timeouts) | build |
| sync-velocity | ✅ **Production-ready** (Paper API 3.4.0, async queue, retry/backoff, métricas, health, dynamic discovery) | compile |
| sync-websocket | ✅ real (SO_REUSEADDR fix) | build |
| spigot-host | ✅ **COMPILA** (DynamicCommand, Registrar, Plugin, DiscordBridge, WS, TCP/UDP/Telegram, HealthCheckRegistry) | compile |
| fabric-host | ✅ **COMPILA** (Fabric 1.21 + Fabric API 0.100.5, Brigadier, ServerMessageEvents, ServerTickEvents) | compile |
| web-editor | ⚠️ gates verdes; UI inestable; auditoría separada diferida | check+integración |
| manager-api | ✅ SPI estable | compile |
| manager-impl | ✅ compila (manifest validation obligatoria, capability check habilitado) | compile |
| presets | ✅ standard/rpg/staff/minimal | tests |
| inworld | ✅ **COMPILA** (InWorldHandler con Server param, sign formatting) | tests (26 integración) |
| observability | ✅ metrics/debug/health (auth token, 127.0.0.1, custom port) | tests (15 integración) |
| extension-api | ✅ SDK estable | tests |
| example-extension | ✅ demo funcional | tests |
| tester | ✅ 25 tests runtime + PerformanceProfiler | tests |
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
- ✅ **sync-velocity**: ✅ **Production-ready** - Paper API 3.4.0, async queue, retry/backoff, métricas, health, dynamic discovery, advanced mapping
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

> 🧱 **Toolchain de iFlow VM (2026-10-23).** `tools/iflow-compiler/` y `:src:iflow-vm:test` son la
> base de `iflow-vm`; se conservan y evolucionan a 32 bits. En paralelo, `iflow-wasm` usa Chicory y
> artefactos `.wasm`, no `.flow`.

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

El Manager de paquetes es quien actualiza índices, descarga artefactos, resuelve dependencias, instala, **inicializa en memoria** y gestiona el ciclo de vida completo de los módulos. Los adaptadores de plataforma (Spigot/Fabric) no son composition roots ni conocen la existencia de los módulos de feature. Solo implementan puertos de plataforma y traducen, en reversa, los eventos de su Minecraft a una representación interna agnóstica.

**Consecuencia:** `spigot-host` y `fabric-host` dependen solo de la API del núcleo + runtime de módulos + SDK de plataforma. Ninguna dependencia a `sync-*`, `textformatter`, `iflow-*`, `chattranslator`, `gtranslate`, `ltranslate`, `observability`, `manager-*` ni `presets`.

### P2. Instalar no es activar

La base de datos local (`library/status`) es un catálogo de programas instalados, no un registro de estado de ejecución. La presencia de un paquete implica que está instalado y que debe iniciarse. El estado de ejecución (encendido/apagado, fallido, requiere reinicio) no pertenece a `library/status`; si es necesario rastrearlo, se hace en un directorio temporal dentro del directorio del manager, nunca en el estado de instalación.

**Consecuencia:** `library/status` registra nombre, versión, canal, dependencias, archivos y hashes del artefacto. No guarda si un módulo está "activo" en un instante dado.

### P3. Los adaptadores de plataforma son delgados

Spigot y Fabric no deben conocer la existencia de ningún módulo de feature. Su propósito es simplemente implementar una API común entre plataformas y adaptar en reversa los eventos de cada Minecraft a una representación interna agnóstica. El cableado de sinks, listeners y servicios de plataforma vive en el runtime de módulos (`suite-runtime`), no en el adapter.

**Consecuencia:** El adapter se reduce a crear `PlatformPorts`, traducir eventos a `Message` y delegar en `SuiteRuntime`.

### P4. Código arbitrario: el dueño del repositorio es responsable

El manager tipo apt descarga e instala código arbitrario. Esa es la idea intencional. La seguridad no se basa en sandboxear cada módulo, sino en que el dueño del repositorio es responsable de lo que publica. SHA-256 es obligatorio para verificar integridad; firma es obligatoria para repositorios remotos; allowlist de repositorios de confianza.

**Consecuencia:** No se promete sandboxing de módulos. Se promete verificación de checksum y confianza en el repositorio.

### P5. `force` significa forzar de verdad

El parámetro `force` no omite conflictos de forma silenciosa. Si un administrador usa `force`, es porque quiere forzar las cosas y sabe lo que hace. Esto incluye permitir la instalación de paquetes excluyentes si el administrador así lo decide, con la advertencia correspondiente.

**Consecuencia:** `force` debe poder romper invariables de coexistencia cuando el admin lo solicita explícitamente.

### P6. Hot unload/upgrade real

El objetivo es descargar, instalar, activar y desactivar módulos en caliente, con liberación de recursos (listeners, tareas, hilos, sockets, referencias). Si una operación no puede completarse sin reinicio, se detecta y se comunica explícitamente (`RESTART_REQUIRED`). Nunca se finge éxito con no-ops, `return parent`, `return false` o métodos vacíos.

**Consecuencia:** Cada módulo implementa `ModuleActivator` con `start/stop`. Cada registro de servicio queda asociado al `moduleId` y se cierra al desactivar. Un classloader por módulo, cerrado al desactivar.

### P7. No hay doble camino

Cuando una funcionalidad se migra al runtime de módulos, su cableado compilado se elimina en el mismo cambio. No se mantienen dos formas de arrancar la suite ni dos mecanismos de descubrimiento de módulos.

**Consecuencia:** `ModuleLifecycleAdapter`, `SuiteHost.bootstrap()` hardcodeado y `ManagerFacade.discoverAll()` por classpath se eliminan cuando el runtime de módulos los sustituye.

### P8. Las librerías compartidas son paquetes de dependencia

SnakeYAML, JDA, Adventure, transporte y otras librerías no se compilan dentro del JAR del host ni de los módulos. Son dependencias gestionadas por el manager, quien se asegura de relocalizar y cargar dichas dependencias para cada paquete tipo módulo.

**Consecuencia:** Ningún JAR de módulo empaqueta librerías compartidas. El classloader del módulo accede a ellas mediante delegation parent-first al classloader del host/runtime.

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
