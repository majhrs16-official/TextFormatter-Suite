# Auditoría técnica — iFlow (compilador `tools/iflow-compiler/` + VM `src/iflow/`)

> **Documento de auditoría de solo lectura.** No se modificó código, artefactos ni configuración del repositorio. Las verificaciones experimentales se hicieron sobre copias de trabajo en `/tmp`.

---

## 1. Fecha, hora UTC y commit auditado

- **Fecha/hora UTC de la auditoría:** `2026-10-17T06:10:36Z`
- **Repositorio:** `/home/majhrs16/Documentos/textformatter-suite` (checkout local; no se clonó)
- **Rama:** `main`
- **Commit (HEAD):** `5ade8559ad7609d737890cc97708c747da2ef39c` — *docs(plan): log the preventive memory map (7e4cae5)* — 2026-10-17 01:35:42 -0400
- **`git status`:** limpio (sin cambios sin confirmar)
- **Alcance:** **solo** `tools/iflow-compiler/` y `src/iflow/`. El resto de módulos se consultó únicamente como contexto de dependencias y uso real (`src/host`, `src/loadtest`, `src/core-api`, `src/textformatter`).
- **Toolchain observada:** Java (toolchain 17 declarada, JDK 21 en `/opt/javac/x64/21`), Node v24.15.0, Gradle wrapper, versión de módulo `2.1.0-SNAPSHOT`.

---

## 2. Cobertura de auditoría

### 2.1 Compilador `tools/iflow-compiler/`

| Elemento | Estado |
|---|---|
| `src/assembler.ts` (1051 líneas) | Leído íntegro |
| `src/cli.ts` | Leído íntegro |
| `src/types.ts` | Leído íntegro |
| `package.json`, `tsconfig.json`, `README.md` | Leídos |
| `test/validation/run.js`, `cases.json` | Leídos |
| 11 fixtures de `test/validation/*.flowasm` | Leídos |
| `examples/policy.flowasm` | Leído |
| Artefactos `test/*.flow`, `test/*.lst`, `test/verify.py` | Leídos |
| `dist/` (incluido en git) | Inspeccionado |
| Ejecución `npm run test:validation` | **11/11 pasan** (con `NODE_OPTIONS=--jitless`) |
| Compilaciones de verificación en `/tmp` | Ejecutadas |

### 2.2 VM `src/iflow/`

| Elemento | Estado |
|---|---|
| 29 clases Java de `src/main` | Leídas íntegras |
| 20 clases de test de `src/test` (261 tests) | Leídas íntegras |
| `build.gradle` | Leído |
| `README.md` | Leído |
| Directorio `build/test-results` | Inspeccionado (261 tests, 0 fallos, 0 errores) |
| Ejecución `./gradlew :src:iflow:test --offline --no-daemon` | `BUILD SUCCESSFUL` (tarea UP-TO-DATE) |
| Verificación experimental del pool con `ModificationJournal` | Ejecutada desde `/tmp` |

### 2.3 Contexto externo (solo dependencias/uso)

- `src/host/.../SuiteBootstrap.java`, `SuiteHost.java` (consumidores reales de `VmConfigLoader.loadProgram` / `VmPolicyEngine`).
- `src/loadtest/.../MessageProcessingBenchmark.java`, `EndToEndIntegrationTest.java` (consumidores de `VmPolicyEngine`).
- `docs/workdir/PLAN.md` (sección «REDISEÑO DEL ABI DE `HOST`…», 2026-10-16).
- Historial de Git de ambos directorios.

### 2.4 Límites declarados

- No se ejecutó el benchmark de carga ni un experimento de red Minecraft; cualquier afirmación de escalabilidad es **INFERENCIA/HIPÓTESIS** y se marca como tal.
- No se probó la ruta `prepareInput` con un mensaje real de ≥3840 bytes (ver IFLOW-AUD-004): se documenta por traza de código.

---

## 3. Resumen ejecutivo

El subsistema iFlow es un **intérprete de 16 bits con memoria plana de 64 KB, paginación por software (SoftMMU) y un ABI `HOST` v2 tipo syscall**, más un **ensamblador TypeScript de dos pasadas**. La dirección arquitectónica (separar ISA, ejecución, memoria y servicios; mapa de memoria de fuente única en `VmMemoryMap`; isa/parser/oráculo inmutables) es sólida y está bien aislada. La disciplina de tests del lado Java es notable en cantidad (261 tests) y la memoria de layout está centralizada.

Sin embargo, el estado real está muy por detrás de lo documentado, y hay tres problemas que rompen el flujo end-to-end:

1. **El cargador de programas es un stub** (`VmConfigLoader.loadNativeProgram` ignora el binario y devuelve *pass-through*), por lo que, en el host real, **`policy.flow` nunca se ejecuta** (IFLOW-AUD-002, Crítico).
2. **La mitad de los servicios `HOST` no está cableada** (property/state/channel → `HOST_FAILURE` siempre) y **el firmware ROM es un esqueleto no funcional** (IFLOW-AUD-016, IFLOW-AUD-008).
3. **El pool de contextos nunca reutiliza** porque `ModificationJournal.clear()` lanza tras `commit()`/`discard()`; cada mensaje vuelve a instanciar contexto + clonar 64 KB de MMU (IFLOW-AUD-003, confirmado experimentalmente).

A esto se suma un **bug de correctitud en el ensamblador** con inmediatos negativos (`LI R1, -1` se codifica como `0x00FF`, no `0xFFFF`), confirmado experimentalmente (IFLOW-AUD-001), y **deriva de documentación** (250 vs 261 tests, servicios y `-d` descritos pero inexistentes).

**Valoración global:** subsistema con buenos cimientos (ISA/memoria/parser) pero **funcionalidad incompleta en el camino crítico**; hoy no es desplegable como motor de políticas real. Madurez técnica estimada del alcance auditado: **~40 %**.

---

## 4. Arquitectura real

### 4.1 Arquitectura pretendida vs. implementada

**Pretendida (README/PLAN):** el host carga un `policy.flow` compilado externamente, lo ejecuta en la VM por cada mensaje, la VM llama a servicios `HOST` (propiedades, estado, canales, permisos, enrutado), registra cambios en un journal que se confirma o descarta atómicamente, y devuelve un `RouteOutcome`. Hay una ROM de firmware que expone utilidades y wrappers.

**Implementada (HECHO por lectura de código):**

```
                    tools/iflow-compiler (TS)
  .flowasm ──► Assembler (2 pasadas) ──► ProgramImage ──► serializeProgramImage() ──► .flow
                        │                                            │
                        └── ROM firmware (stub, 0x0000-0x0FFF)       │
                                                                     ▼
  src/host/SuiteBootstrap ──► VmConfigLoader.loadProgram ──► [STUB: ignora el binario]
                                                                     │
                                                                     ▼
                                             createPassThroughProgram() (bytecode fijo)
                                                                     │
  src/host/SuiteHost ──► new VmPolicyEngine(...) ──► setProgram(program) ──► SoftMMU + PageTable
                                                                     │
  mensaje ──► VmPolicyEngine.route() ──► ExecutionContextPool.acquire()
                       │                        │
                       │                        └── ctx.softMMU(new SoftMMU(loaded.softMMU))  (clona 64 KB)
                       ├── prepareInput()  (serializa mensaje en 0x7000)
                       ├── VmInterpreter.execute()  (fetch desde codeCache privado, NO desde SoftMMU)
                       │        └── HOST → HostServiceRegistry.invoke() → services[] (7 registrados)
                       └── PolicyResultValidator.validate() → journal.commit()/discard()
                                                                     │
                       finally: contextPool.release(ctx) ──► ctx.reset() ──► journal.clear() ──► THROWS
                                                                     └── contexto DESCARTADO
```

### 4.2 Componentes y responsabilidades reales

| Componente | Responsabilidad real |
|---|---|
| `Assembler` (TS) | Ensamblado 2 pasadas, firmware stub, serialización `.flow`, SHA-256 |
| `cli.ts` | Envoltura `commander`; `-d`, `-l`, `--no-firmware` |
| `VmConfigLoader` | **Stub**: lee el archivo y devuelve pass-through |
| `ProgramImageParser` | Parser binario real (con verificación de hash) — **sin uso en producción** |
| `VmPolicyEngine` | Orquestador: permisos de canal, pool, `prepareInput`, ejecuta VM, valida, publica `LoadedProgram` atómico |
| `VmInterpreter` | Bucle fetch/decode/execute; usa `codeCache = program.code()` (no SoftMMU) |
| `SoftMMU` + `PageTable` | Traducción v→f y permisos R/W/X; métodos `fetch*` solo usados por tests |
| `HostServiceRegistry` | 7 servicios registrados; 6 dependencias de host inyectables, 5 **nunca cableadas** |
| `ModificationJournal` | Journal con estados committed/discarded |
| `ExecutionContextPool` | Pool `ConcurrentLinkedQueue` — **en la práctica no reutiliza** |
| `MemoryManager`, `MemoryRegion`, `RegionPerm`, `RegionType`, `VmDmaController` | **Solo usados por tests**; código muerto en producción |

### 4.3 Dirección de dependencias

`src/iflow` depende de `core-api`, `textformatter`, `logger` (build.gradle). `src/host` depende de `src/iflow`. La dirección es coherente (infra/host → módulo). El paquete `vm/memory` (region-based) y el uso `dmactl` no están conectados al motor: conviven dos modelos de memoria incompatibles (paginado vs. regiones con handle), y solo uno está vivo. **INFERENCIA:** esto es residuo de una migración v1→v2 no completada.

---

## 5. Flujo completo del sistema (camino real observado)

1. **Compilación (offline):** `node dist/cli.js prog.flowasm -o prog.flow` → `Assembler.assemble()` → primera pasada (labels, directivas), segunda pasada (bytecode, padding a `.origin`, ROM firmware si `firmware=true`) → `createResult()` → hash SHA-256 → `serializeProgramImage()`.
2. **Arranque del host:** `SuiteBootstrap.java:99` / `SuiteHost.java:115` llaman `VmConfigLoader.loadProgram(configDir, logger)`.
3. **Carga:** si existe `policy.flow`, se lee (`Files.readAllBytes`) y **se descarta**; se devuelve el pass-through (HECHO).
4. **Motor:** `SuiteHost.java:121` crea `VmPolicyEngine` y hace `setProgram(...)`; `setupProgramMappings` puebla `SoftMMU` (código R/X, constantes R, datos/stack/IO R/W) y `LoadedProgram` se publica atómicamente.
5. **Enrutado por mensaje (`route`):** comprueba `sendPolicy`/`receivePolicy` del canal; adquiere contexto; clona `SoftMMU`; configura; `prepareInput`; `VmInterpreter.execute`; `PolicyResultValidator.validate`; `release`.
6. **Resultado:** `RouteOutcome` (LOG/DROP/REJECT/REDIRECT/CHANNEL_REDIRECT/RATE_LIMIT).

**Punto de fuga observado:** en el paso 5, `release` siempre descarta el contexto por el bug del journal (sección 20).

---

## 6. Evaluación por feature

| Feature | Estado real | Evidencia |
|---|---|---|
| Ensamblado `.flowasm` → `.flow` | Funciona; determinista; 11/11 fixtures | `simple.flow` reproducible byte a byte (sha256 `5f1c6bfb…`) |
| Firmware ROM por defecto | **No funcional** (stub con operandos desalineados) | `generateFirmware` + `emitInstr` |
| Flag bare-metal (`--no-firmware`) | Funciona | `userOrigin()` |
| Carga de `.flow` en runtime | **No implementada** (stub) | `VmConfigLoader.loadNativeProgram` |
| Ejecución ISA v1 | Implementada y probada (aritmética, memoria, saltos, CALL/RET) | `VmInterpreter`, tests |
| Servicios `HOST`: permisos, routing | Funcionan | `PERMISSION_CHECK`, `ROUTE_DECISION` |
| Servicios `HOST`: propiedad/estado/canal | **No cableados** → `HOST_FAILURE` | `createServiceRegistry` solo inyecta `permissionChecker` y `rateLimiter` |
| Permisos de página R/W/X | Implementados en datos; **no aplicados al fetch** (el intérprete lee `codeCache`) | `VmInterpreter.codeCache` |
| Journal atómico | Lógica correcta; **rompe el pool** | `ModificationJournal` + `release` |
| Debug en binario (`-d`) | **No-op** | salidas `-d` y sin `-d` idénticas |
| Rate limit | Funciona vía `RATE_LIMIT_BACKOFF` | `doRouteDecision` |
| Redirecciones | Implementadas (canal/actor) | `PolicyResultValidator` |

---

## 7. Evaluación de código

**Fortalezas (HECHO):**
- `VmMemoryMap` como fuente única de direcciones, usada por parser, engine y contexto: elimina drift entre productores y consumidores.
- `ProgramImage`/`ProgramImageParser` inmutables, con *defensive copies* y verificación SHA-256.
- `SoftMMU.writeBlock` **pre-valida todos los rangos** antes de mutar (`validateWriteRange`), evitando escrituras parciales ante fault.
- Errores tipados (`VmException.Code`) con captura de IP.
- ABI v2 bien documentada en Javadoc (`HostServiceRegistry`) y coherente con `ServiceResult`.

**Debilidades (HECHO):**
- API duplicada: `SoftMMU` expone 4 accesores equivalentes a la misma `PageTable` (`getPageTable`, `getPageTableDirect`, `getPageTableForInspection`, `getPageTableForMutation`).
- Estado muerto en `ExecutionContext`: `propertyCache` (4 métodos, 0 usos en `main`), campo/getter `pageTable` (`setPageTable` sin uso en `main`), y getters `programId()/messageId()/actorId()` que se asignan pero nunca se leen.
- Parámetros de diagnóstico descartados: `ServiceResult.invalidArgument(String)` y `hostFailure(String)` ignoran `msg`.
- Comentarios que admiten incompletitud en producción: `// Simplified: ... will fix` (firmware), `// Simplified for now` (loader), `// Could log warning here` (pool).
- Duplicación de lógica de pass-through entre `VmConfigLoader.createPassThroughProgram()` y `VmPolicyEngine.createPassThroughProgram()` (dos copias del mismo bytecode y constantes inline duplicadas en clases internas).

Complejidad contenida: ninguna clase supera ~530 líneas (`HostServiceRegistry`), el resto < 400. La legibilidad general es buena.

---

## 8. Clean Architecture

- **Capa ISA** (`Opcode`, `ProgramImage`, `RegisterFile`, `VmException`): dominio puro, sin dependencias de infraestructura. **Bien.**
- **Capa ejecución** (`VmInterpreter`, `ExecutionContext`, `ModificationJournal`): orquesta; `VmInterpreter` conoce `HostServiceRegistry` (acoplamiento aceptable vía interfaz de servicio).
- **Capa engine**: `VmPolicyEngine` mezcla composición de objetos, configuración de memoria, serialización de entrada y validación. Es el mayor infractor de responsabilidad única.
- **Fuga de dominio:** `PolicyResultValidator` (engine) construye `Message`/`Actor` de `core-api` y conoce `ChannelRegistry` de `textformatter`. La frontera aplicación↔dominio es difusa pero consistente con el resto del repo.

**Veredicto:** hay separación por paquetes razonable, pero **no hay puertos/adaptadores formales** dentro de `vm`; la inversión de dependencias se hace con lambdas funcionales, no con interfaces de dominio. No se puede declarar Clean Architecture «correcta» solo por nombres de paquetes (regla §26).

---

## 9. Hexagonal Architecture

- La VM es el núcleo ejecutable; los servicios de host son el puerto hacia el exterior. Existe un **puerto implícito** (`HostServiceRegistry` con `BiFunction`/`Function`/`BiConsumer` inyectables) que es exactamente el adaptador que falta cablear.
- No hay interfaces explícitas de puerto (`PropertyPort`, `StatePort`, `ChannelPort`), sino funciones sueltas; esto dificulta test y descubrimiento.
- `Router` (core-api SPI) es el puerto de entrada correcto: `VmPolicyEngine implements Router`. **Bien.**
- La dependencia de `PolicyResultValidator` con `ChannelRegistry` concreto (no interfaz) rompe el aislamiento del núcleo.

**Veredicto:** arquitectura hexagonal **parcial**: el puerto de entrada existe y es formal; el puerto de salida es funcional y débilmente tipado.

---

## 10. Modularidad

- Dos módulos claramente separados: compilador (TS) y VM (Java), unidos solo por el formato `.flow`. Buena frontera.
- Dentro de `src/iflow`, `isa` / `execution` / `memory` / `service` / `engine` están bien delimitados.
- Problema: `memory` contiene dos subsistemas (paginado vivo + regiones muerto) sin marcar el obsoleto.
- `tools/iflow-compiler` no tiene tests unitarios propios (solo validación de CLI); `npm test` está roto (ver IFLOW-AUD-013).

---

## 11. Separación de responsabilidades

| Clase | SRP | Comentario |
|---|---|---|
| `Assembler` | Parcial | Parsing + codegen + firmware + hashing + serialización en una clase |
| `VmPolicyEngine` | Baja | Permisos de canal + pool + memoria + I/O + validación |
| `HostServiceRegistry` | Parcial | Registro + implementación de 7 servicios + inyección |
| `VmInterpreter` | Alta | Fetch/decode/execute bien delimitado |
| `SoftMMU`/`PageTable` | Alta | Traducción y permisos |
| `PolicyResultValidator` | Alta | Validación de resultado |

---

## 12. Seguridad

- **Modelo de capabilities auto-declarado (IFLOW-AUD-015, relevante):** `VmPolicyEngine.route` hace `ctx.allowedCapabilities(loaded.program.capabilities())`. Es decir, el **programa decide qué capacidades tiene**, no el host. Un `.flow` que incluya `.capability "state.write"` obtendrá el permiso, siempre que el servicio exista y esté cableado. Es una brecha de defensa en profundidad: la intención de un modelo de capabilities es que el *host* las conceda.
- **Ejecución fuera de páginas ejecutables:** el intérprete lee instrucciones desde `codeCache` privado, sin consultar el permiso EXECUTE de la página. La mitigación de facto es que el IP se acota a `program.codeSize()` (región de código), y `checkGPR` impide escribir IP/SP/FP con `LI`/aritmética. **INFERENCIA:** explotación directa no evidente, pero la protección R/X del fetch es nominal, no efectiva.
- **`RET` lee la dirección de retorno de la pila (R/W)**; si un programa corrompe la pila, podría saltar a otro punto del código (dentro de `codeSize`). Requiere análisis adicional para confirmar un vector real → **HIPÓTESIS**.
- No hay verificación de integridad efectiva en producción porque el parser (con SHA-256) no se usa.

---

## 13. Rendimiento

- **Clonado de MMU por mensaje:** `new SoftMMU(loaded.softMMU)` copia 64 KB (`physicalMemory.clone()`) + `pageTable.copy()` **en cada `route()`**, más `initialize()` que escribe 8192 bytes por `writeByte` (2×4096) con validación de permisos por byte. **HECHO.**
- **Pool inoperante:** por el bug del journal, cada mensaje crea un `ExecutionContext` nuevo (IFLOW-AUD-003) y paga el coste completo de inicialización. Esto anula el propósito declarado del pool.
- **`UUID.randomUUID()` por ruta** (`executionId`), coste menor pero prescindible en el camino caliente.
- `isContiguous` y el *fast path* de `arraycopy` son correctos; el *slow path* por byte es aceptable.
- **HIPÓTESIS:** bajo carga (miles de msg/s) el clonado de 64 KB y la no-reutilización dominarían el perfil; no se ha medido (no se ejecutó benchmark).

---

## 14. Concurrencia

- `loadedProgramRef` es `AtomicReference<LoadedProgram>`: publicación coherente programa+memoria. **Bien.**
- El pool usa `ConcurrentLinkedQueue` pero `available.size() < maxSize` y `created.get()` son *check-then-act* no atómicos: en concurrencia pueden crearse por encima de `maxSize` y el contador `created` puede desincronizarse (decrementos por descarte). **HECHO.**
- `release()` puede devolver un contexto reutilizable mientras su estado se limpia; el reset ocurre antes del `offer`, así que no hay fuga de estado entre ejecuciones (si no fuera por el bug del journal).
- `RateLimiter` se comparte; se asume thread-safe (verificado solo por lectura).

---

## 15. Escalabilidad

- La arquitectura permite `setProgram` atómico y hot-reload: buena base.
- Los cuellos reales son de implementación (clon de MMU por mensaje, pool muerto), no de diseño.
- Sin estado persistente real cableado, no hay escalado de estado entre nodos.
- **INFERENCIA:** para `network-level` (miles de jugadores) el coste por mensaje actual es incompatible; requeriría eliminar el clon y reactivar el pool, o pasar a un MMU de solo lectura compartido + COW.

---

## 16. Testing

- **261 tests, 0 fallos, 0 errores** (leído de `build/test-results`). El README declara 250 (drift).
- Cobertura amplia de ISA, memoria, permisos, servicios, integración.
- **Tests débiles o engañosos:**
  - `SoftMMUTest.fetchWordCrossPageThrows` pasa por falta de permiso READ, no por el cruce de página esperado (IFLOW-AUD-007).
  - `FirmwareIntegrationTest` usa una **ruta absoluta de máquina** `/home/majhrs16/...` (no portable en CI de terceros) y solo asserta `fuelRemaining > 0`, sin comprobar el resultado (IFLOW-AUD-014).
  - `ExecutionContextPoolTest.releasedContextIsReused` pasa porque en ese test **no se commitea el journal**; nunca reproduce la ruta de producción (commit → clear → throw).
- **Sin cobertura:** `prepareInput` con longitudes límite; `VmConfigLoader` con un `policy.flow` real (solo prueba el fallback); cableado de property/state/channel; `ServiceResult.unsupported()`; `MemoryManager.createSharedRegion` límites.
- `jacocoTestCoverageVerification` exige solo `LINE ≥ 0.25` (bajo).

---

## 17. Mantenibilidad

- Código legible y con Javadoc de calidad en las clases clave (`VmMemoryMap`, `HostServiceRegistry`, `ServiceResult`).
- La coexistencia de dos modelos de memoria y de clases enteras solo-test confunde al lector.
- Comentarios «Simplified/will fix» en producción siembran deuda y desinformación.
- La deriva README↔código (tests, servicios, `-d`, `VMDmaController`) aumenta el coste de onboarding.

---

## 18. Historial / evolución (Git)

Commits relevantes recientes (todos sobre el alcance):

```
7e4cae5 fix(iflow): explicit memory map prevents code/constants page overlap
280f790 fix(iflow-compiler): .const symbols show their value, not the pool index
65af8ce iflow(build): Gradle task to assemble policy programs + bare-metal switch
da2a0cb docs(iflow): unify the HOST ABI description on v2
1c33e5b iflow(vm): migrate remaining HOST callers to ABI v2; allow 2-page code
9743754 iflow(compiler): firmware constants as values, page-1 user origin, bare-metal flag
a946d57 iflow(vm): HOST ABI v2 — register layout, status exposure, 8-bit propId
252be08 iflow(isa): make RET a 1-byte opcode with no operand
ae9f635 fix(iflow-compiler): accept labels as LI immediates
47e0864 fix(iflow): re-arm fuelRemaining in ExecutionContext.fuelBudget()
```

**Lectura (HECHO/INFERENCIA):** hubo una migración v1→v2 activa (SpEL/regiones → bytecode/paginado) y un rediseño ABI v2 reciente (D1–D11, 2026-10-16). El foco fue el *layout* de registros y el mapa de memoria, **no** completar el cargador ni el firmware. Esto explica que el «código de la migración» esté mejor acabado que la funcionalidad de extremo a extremo.

---

## 19. Clasificación de hallazgos

> IDs en el namespace `IFLOW-AUD-*` para no colisionar con los `IFLOW-001…010` ya usados en `PLAN.md`. Etiquetas: **HECHO** (demostrado por código/experimento), **INFERENCIA**, **HIPÓTESIS**.

---

### [IFLOW-AUD-001]

**Tipo:** Bug
**Severidad:** Alta
**Confianza:** Confirmado (experimental)
**Ubicación:** `tools/iflow-compiler/src/assembler.ts -> Assembler -> parseOperands` (líneas 652-658), `-> getImm16Operand` (806-828), `-> getImm8Operand` (800-804)

**Descripción:** Los inmediatos negativos en el rango `[-128, 255]` se clasifican como `imm8` con `value & 0xFF`, y al emitir `LI` (imm16) se **extienden con ceros** en vez de signo. `LI R1, -1` produce los bytes `00 01 00 FF` (valor `0x00FF = 255`) en lugar de `FF FF` (`0xFFFF`). `LI R2, -128` produce `00 02 00 80` (`128`) en lugar de `FF 80`.

**Causa:**
```ts
// parseOperands
if (value >= -128 && value <= 255) {
  operands.push({ type: 'imm8', value: value & 0xFF, rawValue: value });
}
// getImm16Operand
if (op && (op.type === 'imm16' || op.type === 'imm8')) return op.value & 0xFFFF;
```
`op.value` ya está enmascarado a 8 bits para los `imm8`, así que la máscara de 16 bits no puede recuperar el signo. `rawValue` existe pero nunca se usa al emitir.

**Impacto funcional:** Cualquier programa que use constantes negativas (p. ej. `-1` para “todos los bits”, offsets negativos, máscaras) obtiene valores silenciosamente erróneos.

**Impacto en rendimiento/escalabilidad:** Ninguno; es correctitud.

**Condiciones para reproducir:** Ensamblar `LI R1, -1` y leer el binario.

**Evidencia (HECHO):** compilado en `/tmp` con `--no-firmware`:
```
00000010: 0001 00ff 0002 0080 0003 00ff 0004 0100
```
para la fuente `LI R1,-1 / LI R2,-128 / LI R3,255 / LI R4,256`.
Además, el fixture `test/validation/valid-edge-immediates.flowasm` incluye `LI R5, -1` y **pasa**, fijando el comportamiento incorrecto (test que consagra el bug).

**Solución sugerida:** emitir desde `rawValue` y enmascarar a 16 bits. Para `imm8` (`TRAP`) mantener `value & 0xFF`.

**Código refactorizado:**
```ts
private getImm16Operand(operands: Operand[], index: number): number {
  const op = operands[index];
  if (op && (op.type === 'imm16' || op.type === 'imm8')) {
    const raw = op.rawValue !== undefined ? op.rawValue : op.value;
    return raw & 0xFFFF;            // -1 -> 0xFFFF, -128 -> 0xFF80
  }
  // ...labels sin cambios
}
private getImm8Operand(operands: Operand[], index: number): number {
  const op = operands[index];
  if (op && (op.type === 'imm8' || op.type === 'imm16')) {
    const raw = op.rawValue !== undefined ? op.rawValue : op.value;
    return raw & 0xFF;
  }
  return 0;
}
```
Actualizar `valid-edge-immediates.flowasm` para assertar los bytes correctos (o añadir un caso de validación con hex esperado).

**Evaluación de la solución:** impacto alto (correctitud), complejidad mínima, riesgo de regresión muy bajo (solo cambia el caso negativo), compatibilidad total. **Merece la pena: sí, P0.**

---

### [IFLOW-AUD-002]

**Tipo:** Bug (funcionalidad crítica ausente) / Defecto de diseño
**Severidad:** Crítica
**Confianza:** Confirmado
**Ubicación:** `src/iflow/.../vm/VmConfigLoader.java -> loadNativeProgram` (líneas 43-52); consumidores `src/host/.../SuiteBootstrap.java:99`, `src/host/.../SuiteHost.java:115`

**Descripción:** `loadNativeProgram` lee el archivo, registra un log y **devuelve `createPassThroughProgram()`**. El parser real (`ProgramImageParser.parse`) existe y está probado, pero **no se usa en producción**: el `policy.flow` en disco se ignora por completo.

**Causa:**
```java
private static ProgramImage loadNativeProgram(Path path, PluginLogger logger) throws IOException {
    byte[] data = Files.readAllBytes(path);
    // ... For now, return pass-through
    return createPassThroughProgram();
}
```

**Impacto funcional:** El host nunca ejecuta la política compilada; todas las decisiones se resuelven con el pass-through fijo (`ROUTE_DECISION`/`CONTINUE`). El módulo no cumple su contrato. `ProgramImageParser`, su verificación de hash y los tests de parser no cubren la ruta real.

**Impacto en rendimiento/escalabilidad:** N/A (funcional).

**Condiciones para reproducir:** Colocar `policy.flow` en el directorio de config y arrancar el host; observar el log «Loaded native VM program …» seguido de comportamiento pass-through.

**Evidencia (HECHO):** lectura directa del método y de sus dos llamadas en `src/host`.

**Solución sugerida:** usar el parser y validar ABI/longitud mínima:
```java
private static ProgramImage loadNativeProgram(Path path, PluginLogger logger) throws IOException {
    byte[] data = Files.readAllBytes(path);
    ProgramImage image = ProgramImageParser.parse(data);   // valida hash, versiones, límites
    if (logger != null) logger.info("Loaded native VM program: " + path + " (" + data.length + " bytes)");
    return image;
}
```
Añadir test de `loadProgram` con un `.flow` válido generado por el compilador (no solo el caso inexistente).

**Evaluación de la solución:** impacto máximo (habilita la funcionalidad completa), complejidad mínima (una llamada), riesgo bajo (el parser ya está probado), compatibilidad total. **P0.**

---

### [IFLOW-AUD-003]

**Tipo:** Bug (rendimiento/gestión de recursos) / Defecto de diseño
**Severidad:** Alta (rendimiento) — funcionalmente no rompe, pero anula el pool
**Confianza:** Confirmado (experimental)
**Ubicación:** `ExecutionContextPool -> release` (68-90), `ExecutionContext -> initialize` (96-126), `ModificationJournal -> clear` (145-150); origen en `PolicyResultValidator.validate` (130 `journal.commit()` / 46/61/117 `journal.discard()`)

**Descripción:** Tras validar, el journal queda `committed` o `discarded`. En el `finally` de `route`, `contextPool.release(ctx)` llama `ctx.reset()` → `initialize()` → `journal.clear()`, que **lanza `IllegalStateException`** cuando el journal está committed/discarded. `release` captura la excepción y **descarta el contexto** (`created.decrementAndGet()`), sin devolverlo al pool.

**Causa:**
```java
// ModificationJournal.clear()
public void clear() {
    if (committed || discarded) throw new IllegalStateException(...);
    entries.clear();
}
// ExecutionContextPool.release()
try { ctx.reset(); configurator.accept(ctx); }
catch (Exception e) { created.decrementAndGet(); return; }   // contexto perdido
```

**Impacto funcional:** El pool `available` permanece vacío; cada mensaje instancia un `ExecutionContext` nuevo. El estado no se filtra (bien), pero el objetivo del pool no se cumple.

**Impacto en rendimiento/escalabilidad:** Alto en el camino caliente: creación de contexto + `new SoftMMU` (clon de 64 KB) + doble barrido de 8 KB de I/O por mensaje.

**Condiciones para reproducir:** Ejecutar una ruta completa que commitee el journal y volver a adquirir.

**Evidencia (HECHO, experimental):** programa Java en `/tmp` contra `iflow-2.1.0-SNAPSHOT.jar`:
```
after release: created=0 available=0 released=0
reused same instance? false
```
(commiteando el journal antes de `release`, como hace el validador).

**Solución sugerida:** que `clear()` sea idempotente/reutilizable (reset de estado, no guardia), o que `release` limpie los *flags* del journal antes de `reset`. La opción mínima:
```java
// ModificationJournal
public void reset() { entries.clear(); committed = false; discarded = false; }
// ExecutionContext.initialize(): usar journal.reset() en vez de journal.clear()
```
Mantener `clear()` con guardia si se usa en otros flujos, pero el reset de pool debe usar `reset()`.

**Evaluación de la solución:** impacto alto (reactiva el pool), complejidad baja, riesgo bajo (el invariante «no se puede escribir tras commit» se conserva entre ejecuciones porque el reset ocurre antes de reutilizar), beneficio claro. **P1** (P0 si el objetivo inmediato es rendimiento).

---

### [IFLOW-AUD-004]

**Tipo:** Bug (robustez / límites)
**Severidad:** Media
**Confianza:** Probable (confirmado por traza de código; no ejecutado con datos reales)
**Ubicación:** `src/iflow/.../vm/engine/VmPolicyEngine.java -> prepareInput` (256-301), captura en `route` (173-176)

**Descripción:** Los cálculos de longitud restante pueden volverse negativos. Con `offset = 4096` tras escribir texto (3840) + canal (254) + terminadores, la longitud de dirección es `Math.min(dirBytes.length, 4096 - 4096 - 1) = -1`, y `writeBlock(0x8000, dirBytes, 0, -1)` acaba en `System.arraycopy(..., -1)`, que lanza `IndexOutOfBoundsException`. Como `route` solo captura `VmException`, la excepción **escapa** del método.

**Causa:**
```java
int textLen = Math.min(textBytes.length, ctx.ioBufferSize() - offset - 256); // 3840
...
int channelLen = Math.min(channelBytes.length, ctx.ioBufferSize() - offset - 1);
...
int dirLen = Math.min(dirBytes.length, ctx.ioBufferSize() - offset - 1);      // puede ser -1
ctx.softMMU().writeBlock(inputVAddr + offset, dirBytes, 0, dirLen);
```

**Impacto funcional:** Ante un mensaje muy largo (texto ≥ 3840 bytes UTF-8) enviado a un canal con nombre ≥ 254 bytes, el enrutado lanza una excepción no controlada en lugar de degradar.

**Impacto en rendimiento/escalabilidad:** Bajo (ruta de borde), pero un fallo no controlado en el camino de mensajes es grave en producción.

**Condiciones para reproducir:** `message.text()` ≥ 3840 bytes y `channel` ≥ 254 bytes; que el canal resuelva y pase los chequeos de permisos.

**Evidencia (HECHO por traza):** aritmética anterior + `SoftMMU.writeBlock` no valida longitud negativa + `System.arraycopy` con longitud negativa lanza `IndexOutOfBoundsException` + `route` no captura `RuntimeException` genérica.

**Solución sugerida:** acotar `offset` y usar `if` guards; o serializar con un helper que devuelva bytes restantes ≥ 0:
```java
private static int remaining(int size, int offset) { return Math.max(0, size - offset); }
...
int textLen = Math.min(textBytes.length, remaining(ctx.ioBufferSize(), offset) - 256);
```
Y añadir `catch (RuntimeException e)` → REJECT en `route` como red de seguridad.

**Evaluación de la solución:** impacto medio, complejidad baja, riesgo bajo, beneficio de robustez alto. **P1.**

---

### [IFLOW-AUD-005]

**Tipo:** Bug / Deuda técnica (feature anunciada inexistente)
**Severidad:** Baja-Media
**Confianza:** Confirmado (experimental)
**Ubicación:** `assembler.ts -> createResult/serializeProgramImage` (890-1038), `cli.ts` (`-d`), `ProgramImageParser.parse`

**Descripción:** `debugInfo` se genera (`generateDebugInfo`) en memoria, pero `serializeProgramImage` **no lo emite**; el binario no tiene sección de debug y el parser nunca lo lee. `-d` es un no-op.

**Causa:** el formato serializado es header + code + constants + capabilities + hash; no hay campo `debugInfo`.

**Impacto funcional:** El README afirma «debug info en binario»; el usuario no obtiene símbolos ni source map. `.lst` es la única salida útil de depuración.

**Impacto en rendimiento/escalabilidad:** N/A.

**Condiciones para reproducir:** Compilar con y sin `-d` y comparar.

**Evidencia (HECHO, experimental):** ambos binarios byte-idénticos:
```
8340b9b2136ba547eb17650a676a3b5814c78a40b1871b78a3bbc404f27085dd  dbg1.flow
8340b9b2136ba547eb17650a676a3b5814c78a40b1871b78a3bbc404f27085dd  dbg2.flow
BYTE-IDENTICAL
```

**Solución sugerida:** o implementar una sección opcional (p. ej. tras el hash, con `debugLength` en un campo antes del padding) y leerla en el parser, o **eliminar** `-d`/`debug` y corregir los README. Recomiendo lo segundo a corto plazo (mínimo, sin riesgo de formato).

**Evaluación de la solución:** eliminar reduce deuda y falsas expectativas; implementarlo añade complejidad de formato y obliga a versionar. **P2.**

---

### [IFLOW-AUD-006]

**Tipo:** Bug (contrato de servicio)
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `HostServiceRegistry -> invoke` (245-270); `ServiceResult.unsupported()` (90)

**Descripción:** Un `serviceId` desconocido lanza `VmException.hostServiceNotFound`, que `VmPolicyEngine.route` captura y convierte en **REJECT**. El README y el Javadoc de `ServiceResult` declaran que un servicio no soportado debe responder `R0=status=5 (UNSUPPORTED)`. `ServiceResult.unsupported()` **nunca se usa**.

**Causa:**
```java
HostService service = services.get(serviceId);
if (service == null) throw VmException.hostServiceNotFound(serviceId, ...);
```

**Impacto funcional:** Un programa que sondea un servicio opcional recibe un REJECT total en vez de un status recuperable, imposibilitando la negociación por capabilities. Contradice el contrato documentado.

**Impacto en rendimiento/escalabilidad:** N/A.

**Condiciones para reproducir:** Programa que hace `HOST` con un `serviceId` no registrado.

**Evidencia (HECHO):** código anterior + `README.md` línea 84 («el resto responde `status=5` UNSUPPORTED») + `unsupported()` sin referencias en `main`.

**Solución sugerida:** devolver `ServiceResult.unsupported()` para IDs no registrados (y para los declarados pero no implementados), reservando `hostServiceNotFound` para errores internos. Con IFLOW-AUD-016 resuelto, los IDs 0x0102/0x0201/0x0301/… devolverían `UNSUPPORTED` de forma explícita.

**Evaluación de la solución:** impacto medio, complejidad mínima, riesgo bajo. **P1** (junto con IFLOW-AUD-016).

---

### [IFLOW-AUD-007]

**Tipo:** Defecto de diseño / Deuda técnica (código y tests engañosos)
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:** `SoftMMU -> fetchByte` (176-178), `-> fetchWord` (181-183); `SoftMMUTest.fetchWordCrossPageThrows`

**Descripción:** `fetchByte(ip)` hace `readByte(pageTable.translateExecute(ip))`, y `readByte` vuelve a traducir con `translateRead`. Es una **doble traducción**: el permiso EXECUTE se comprueba y luego el READ sobre el resultado. Se exige READ+EXECUTE, no EXECUTE. Además, el intérprete **no usa** `fetch*` (lee `codeCache`), por lo que son código muerto en producción.

**Causa:** composición de `translateExecute` + `readByte` (que traduce de nuevo).

**Impacto funcional:** Ninguno en producción (código muerto); pero la API pública engaña y los tests validan un comportamiento incorrecto.

**Impacto en rendimiento/escalabilidad:** N/A.

**Condiciones para reproducir:** Mapear una página solo-EJECUTABLE (sin READ) y llamar `fetchByte`; fallará aunque debería funcionar.

**Evidencia (HECHO):** `grep` confirma que `fetchByte`/`fetchWord` solo aparecen en `SoftMMU.java` y en tests.

**Solución sugerida:** implementar el fetch leyendo `physicalMemory` tras `translateExecute` sin re-traducir, o eliminar los métodos y sus tests si se decide que el fetch es siempre desde `codeCache`.

**Evaluación de la solución:** bajo impacto real; conviene al menos eliminar/arreglar para no dejar una API y unos tests que afirman algo falso. **P3.**

---

### [IFLOW-AUD-008]

**Tipo:** Bug / Deuda técnica (feature anunciada no funcional)
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `assembler.ts -> generateFirmware` (165-312), `-> emitInstr` (347-352)

**Descripción:** La ROM de firmware (activable por defecto) tiene funciones no funcionales:
- `emitInstr` escribe `opcode + un byte por operando`, pero `LI` ocupa 4 bytes (opcode+reg+2×imm) y otras instrucciones tienen tamaños distintos → **operandos desalineados** en el flujo de la ROM.
- Los saltos internos (`JZ`/`JMP`) se emiten con offset `0,0` («placeholder»), sin *fixup*: `fw_strlen` entra en bucle incorrecto.
- El propio código admite: `// Simplified: use loop…`, `// pseudo, will fix`, `// Since full firmware is complex, emit a minimal working stub`.
- Solo `fw_version` (bytes en 0x0100) es «real»; el resto se rellena con ceros hasta 0x1000.

**Causa:** la generación de la ROM se dejó a medio hacer; se priorizó el layout (0x0000-0x0FFF) sobre su contenido.

**Impacto funcional:** Al estar **ON por defecto**, todo `.flow` incluye 4 KB de ROM «válida» en apariencia pero inejecutable. Cualquier llamada a utilidades de firmware (`CALL fw_strlen`, etc.) produce comportamiento indefinido. El vector table apunta a direcciones que contienen bytecode corrupto.

**Impacto en rendimiento/escalabilidad:** 4 KB de binario por programa (menor) y una promesa de plataforma incumplida.

**Condiciones para reproducir:** Compilar con firmware ON y ejecutar una `CALL` a una función de firmware.

**Evidencia (HECHO):** lectura de `generateFirmware`/`emitInstr`; comentarios auto-declarativos; `INSTRUCTION_SIZES[LI]=4` frente a los 3 bytes que emite `emitInstr(LI, a, b)`.

**Solución sugerida:** corto plazo, **desactivar el firmware por defecto** (o marcar `fw_*` como no soportadas y devolver `UNSUPPORTED`) para no vender una ROM inexistente; medio plazo, implementar un emisor de firmware que respete `INSTRUCTION_SIZES` y resuelva saltos, con un test que ejecute `fw_strlen` sobre una cadena conocida.

**Evaluación de la solución:** desactivar por defecto es mínimo, seguro y elimina el riesgo; implementar la ROM es esfuerzo medio-alto. **P1** (desactivar) / P2 (implementar).

---

### [IFLOW-AUD-009]

**Tipo:** Defecto de diseño (guarda inefectiva)
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:** `ExecutionContext -> maxStackSize` (32, 88), `-> enterCall` (162-177)

**Descripción:** `maxStackSize = 16 KiB`, pero la región de pila son 8 KiB (`STACK_BASE=0x5000`, `STACK_TOP=0x7000`). `stackLimit = STACK_TOP - maxStackSize = 0x3000`, que cae **dentro de la región de datos**, de modo que la comprobación `sp - 2 < stackLimit` nunca se dispara antes de un fault de página o del límite de `maxCallDepth = 64`.

**Impacto funcional:** El mensaje de `STACK_LIMIT` y el cálculo de límite son engañosos; la cota real es `maxCallDepth` y el mapeo de páginas. No hay desbordamiento explotable (la página de stack y la de datos están separadas), pero el guard no hace lo que dice.

**Evidencia (HECHO):** aritmética sobre `VmMemoryMap` y `enterCall`.

**Solución sugerida:**
```java
private int maxStackSize = VmMemoryMap.STACK_PAGES * VmMemoryMap.PAGE; // 8 KiB
```
y derivar `stackLimit` de `STACK_BASE`, no de una constante fija.

**Evaluación:** impacto bajo, complejidad mínima. **P3.**

---

### [IFLOW-AUD-010]

**Tipo:** Defecto de diseño / Bug menor (concurrencia)
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:** `ExecutionContextPool -> acquire` (50-58), `-> release` (83-89)

**Descripción:** En `acquire`, el bloque `if (created.get() >= maxSize) { // … }` está **vacío**: el pool crea contextos por encima de `maxSize`. En `release`, `available.size() < maxSize` es *check-then-act* no atómico. Los contadores `created`/`released` pueden desincronizarse.

**Impacto funcional:** `maxSize` no es una cota real; `available()` puede superar `maxSize` bajo concurrencia.

**Solución sugerida:** usar `Semaphore`/`AtomicInteger` para la cota, o al menos registrar y respetar `maxSize` con `CAS`.

**Evaluación:** bajo impacto actual; relevante si se restaura el pool. **P3.**

---

### [IFLOW-AUD-011]

**Tipo:** Deuda técnica (versión de ABI incoherente)
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:** `ProgramImage -> CURRENT_ABI_VERSION = 1` (20); uso en `ProgramImageParser.parse` (42-45); compilador `minAbiVersion: 1`

**Descripción:** El ABI vigente es «v2» (commit `a946d57`, Javadoc de `HostServiceRegistry`, README), pero `CURRENT_ABI_VERSION = 1`, el compilador emite `minAbiVersion=1` y no existe ninguna comprobación del layout de registros. Un `.flow` con la convención v1 (que aún existía) pasaría el parser y fallaría en runtime.

**Impacto funcional:** Ninguno hoy (todo el runtime es v2), pero el versionado no protege contra binarios antiguos → riesgo latente.

**Solución sugerida:** subir `CURRENT_ABI_VERSION` a 2 y que el compilador emita `minAbiVersion: 2`; rechazar en el parser binarios v1.

**Evaluación:** mínimo coste, evita incompatibilidades silenciosas. **P2.**

---

### [IFLOW-AUD-012]

**Tipo:** Deuda técnica (deriva documental)
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:** `src/iflow/README.md`, `tools/iflow-compiler/README.md`

**Descripción:** Afirmaciones no respaldadas por el código:
- «250 tests» → reales **261**.
- `VmDmaController` descrito como componente de la VM → solo usado por tests; `MemoryManager`/regiones sin mención pero presentes y muertos.
- Línea 84: «el resto responde `status=5` UNSUPPORTED» → falso (lanza excepción → REJECT; ver IFLOW-AUD-006).
- `-d` «debug info en binario» → no-op (IFLOW-AUD-005).

**Solución sugerida:** corregir README para reflejar el estado real (o arreglar el código). Los README son fuente de onboarding; el drift es costoso.

**Evaluación:** bajo coste, alto retorno en mantenibilidad. **P2.**

---

### [IFLOW-AUD-013]

**Tipo:** Deuda técnica (artefactos y scripts)
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:** `tools/iflow-compiler/dist/`, `package.json`, `.gitignore`

**Descripción:** `dist/` está **versionado en git** (10+ archivos, incl. mapas) y contiene un `iflow-assembler.esm.js` **huérfano** (bundling esbuild sin fuente ni referencias). `package.json` declara `main: dist/index.js` y `types: dist/index.d.ts`, que no existen (`tsc` no genera `index`). `npm test` ejecuta `jest` sin configuración ni tests (`test/` solo tiene fixtures y `validation/run.js`).

**Impacto funcional:** `npm test` falla/no ejecuta nada; el paquete no es consumible como librería; los artefactos generados se mezclan con el código fuente.

**Solución sugerida:** ignorar `dist/` en `.gitignore`, eliminar el ESM huérfano, alinear `main`/`types` (o crear `src/index.ts`), y apuntar `npm test` a `test:validation` o añadir tests reales con config de jest.

**Evaluación:** bajo riesgo, mejora de higiene. **P2/P3.**

---

### [IFLOW-AUD-014]

**Tipo:** Deuda técnica (testing no portable y débil)
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `FirmwareIntegrationTest -> runFirmwareBinary` (16, 98)

**Descripción:** Ruta absoluta de máquina `/home/majhrs16/Documentos/textformatter-suite/tools/iflow-compiler/test/firmware.flow` (no portable) y única aserción `assertTrue(ctx.fuelRemaining() > 0)`, que pasaría incluso si el programa no ejecutara nada útil. Además imprime trazas de depuración.

**Impacto funcional:** El test no valida el firmware (que además es un stub) y rompe en cualquier máquina/CI distinto.

**Solución sugerida:** resolver la ruta vía `Paths.get("tools/iflow-compiler/test/firmware.flow")` relativo a la raíz del proyecto (o un recurso), y asertar un resultado concreto (registros, decisión). Mientras el firmware sea stub, renombrar el test y documentar que verifica solo que el binario se *parsea*.

**Evaluación:** bajo coste, mejora sustancial de la señal del test. **P2.**

---

### [IFLOW-AUD-015]

**Tipo:** Vulnerabilidad / Defecto de diseño (modelo de capacidades)
**Severidad:** Media
**Confianza:** Confirmado (código); explotación concreta = HIPÓTESIS
**Ubicación:** `VmPolicyEngine -> route` (158), `HostServiceRegistry -> invoke` (252-256), `-> declareCapability` (234)

**Descripción:** Las capacidades permitidas se toman del **propio programa**: `ctx.allowedCapabilities(loaded.program.capabilities())`. `HostServiceRegistry.declareCapability` y `createServiceRegistry` declaran capacidades en el lado host, pero la comprobación real (`ctx.hasCapability`) consulta la lista del programa. Por tanto, un programa que se autodeclare `.capability "state.write"` obtiene el permiso si el servicio existe.

**Impacto funcional / seguridad:** El modelo de capabilities se vuelve decorativo; no limita a un `.flow` comprometido o malicioso que se compila con permisos amplios. Se pierde defensa en profundidad.

**Condiciones para reproducir:** Cargar un `.flow` con `.capability "property.write"` y observar que `PROPERTY_WRITE` no es denegado por capability (sujeto a que el writer esté cableado, hoy no lo está).

**Evidencia (HECHO):** código de `route` + `hasCapability`. La explotación práctica está hoy atenuada porque los servicios de escritura no están cableados (IFLOW-AUD-016), pero el modelo ya es incorrecto.

**Solución sugerida:** la lista de capacidades debe venir de la **política del host** (configuración/allowlist), no del programa. P. ej.:
```java
ctx.allowedCapabilities(hostAllowedCapabilities);   // allowlist del host
```
y, opcionalmente, intersecar con las declaradas por el programa para auditoría (`program.capabilities()` como «solicitadas», no «concedidas»).

**Evaluación:** alto beneficio de seguridad, complejidad baja-media (requiere definir la allowlist en el host), riesgo de regresión moderado (podría denegar políticas legítimas hasta configurarlas). **P1.**

---

### [IFLOW-AUD-016]

**Tipo:** Bug funcional (servicios no cableados)
**Severidad:** Alta
**Confianza:** Confirmado
**Ubicación:** `VmPolicyEngine -> createServiceRegistry` (85-102) vs. `HostServiceRegistry` campos/inyectores (132-140, 476-519)

**Descripción:** Solo se inyectan `permissionChecker` y `rateLimiter`. Los seis *setters* restantes — `propertyReader`, `propertyWriter`, `stateReader`, `stateWriter`, `channelResolver`, `placeholderResolver`, `translationDetector` — **nunca se llaman en producción** (solo en tests). Por tanto:
- `0x0100 PROPERTY_READ`, `0x0101 PROPERTY_WRITE` → `HOST_FAILURE` («Property reader/writer not configured»).
- `0x0500 STATE_READ`, `0x0501 STATE_WRITE` → `HOST_FAILURE`.
- `0x0600 CHANNEL_RESOLVE` → `HOST_FAILURE` (y además `channelResolver.apply(channelName.hashCode())` pasa un hash, no el nombre: firma incoherente).

**Impacto funcional:** Los programas que dependen de leer/escribir propiedades, estado o resolver canales **no funcionan**. Solo permisos y enrutado son utilizables. El `propertyCache` del contexto (que ese código usaría) tampoco se usa.

**Impacto en rendimiento/escalabilidad:** N/A (funcional).

**Condiciones para reproducir:** Programa que invoque `PROPERTY_READ`; `doPropertyRead` devuelve `HOST_FAILURE`.

**Evidencia (HECHO):** `grep` de los inyectores: sin llamadas en `src/iflow/src/main` fuera de la propia firma.

**Solución sugerida:** cablear los adaptadores reales desde el host (`ChannelRegistry`, `Message`/`Actor` y estado del servidor) en `createServiceRegistry`, coherentes con el ABI v2:
```java
registry.propertyReader((scope, propId) -> readMessageProperty(...))
        .propertyWriter((scope, value) -> ...)
        .stateReader(() -> stateMap)
        .channelResolver(name -> channels.resolve(name).id());
```
Corregir la firma de `channelResolver` para recibir el nombre, no un hash. Añadir tests de integración que ejerzan cada servicio por su ID.

**Evaluación:** impacto máximo sobre features, complejidad media (depende de APIs del host), riesgo de regresión medio. **P1.**

---

### [IFLOW-AUD-017]

**Tipo:** Deuda técnica (diagnóstico perdido)
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:** `ServiceResult -> invalidArgument(String msg)` (81-83), `-> hostFailure(String msg)` (98-100)

**Descripción:** Ambos métodos reciben un mensaje y lo **descartan**; también los `catch (VmException e)` en `HostServiceRegistry` construyen mensajes («Memory write failed: …») que se pierden.

**Impacto funcional:** Depuración y observabilidad degradadas; el `status` viaja a la VM pero el motivo no queda registrado en ningún sitio.

**Solución sugerida:** almacenar el detalle en un campo `detail` de `ServiceResult` (sin romper el ABI de `R0/R3/R4`) y exponerlo a logs; o al menos `HostServiceRegistry` debería loguear el motivo antes de devolver.

**Evaluación:** bajo coste, mejora operativa. **P3.**

---

### [IFLOW-AUD-018]

**Tipo:** Defecto de diseño (código muerto con bugs latentes)
**Severidad:** Baja
**Confianza:** Confirmado (para «solo tests»); los límites concretos = HECHO por lectura
**Ubicación:** `MemoryManager` (26-172), `MemoryRegion`, `RegionPerm`, `RegionType`, `VmDmaController`

**Descripción:** El subsistema de regiones por handle no se usa en producción (solo `MemoryManager`/`VmDmaController` en tests). `createSharedRegion` llama a `createRegion` **sin respetar** `maxRegions`/`maxTotalMemory` (a diferencia de `createRegion`, que sí los comprueba), y los handles pueden reutilizarse tras *wrap*.

**Impacto funcional:** Ninguno hoy (muerto). Riesgo de que se reactive sin auditoría.

**Solución sugerida:** eliminar el subsistema o marcarlo `@Deprecated` y documentar que el modelo vivo es `SoftMMU`/`PageTable`; si se conserva, unificar la contabilidad de límites.

**Evaluación:** limpieza; reduce superficie y confusión. **P3.**

---

## 20. Defectos (síntesis)

- **Diseño:** coexistencia de dos modelos de memoria (paginado vivo / regiones muerto) — IFLOW-AUD-018.
- **Diseño:** `VmPolicyEngine` como «god class» de orquestación (permisos + I/O + memoria + validación).
- **Diseño:** duplicación del programa pass-through en dos clases.
- **Contrato:** capability auto-declarada — IFLOW-AUD-015.

## 21. Deuda técnica

- Código muerto en producción: `MemoryManager`/`VmDmaController`, `SoftMMU.fetch*`, `propertyCache`, `pageTable`, `programId/messageId/actorId`, `ServiceResult.unsupported()`.
- API duplicada de `PageTable` (4 accesores).
- Artefactos `dist/` versionados + ESM huérfano + `main`/`types` rotos — IFLOW-AUD-013.
- Deriva documental — IFLOW-AUD-012.
- Comentarios «Simplified/will fix» en producción.

## 22. Mejoras opcionales (no defectos)

- Renombrar `Task`/clases con nombres `* Manager` que hoy son solo-test.
- Añadir `@Nullable`/`Optional` explícitos donde se devuelven `null` (`propertyReader`, `channelResolver`).
- Unificar naming de `HOST` (`op` vs `serviceId`) en comentarios.

---

## 23. Roadmap priorizado

### P0 — Crítico

| # | Problema | Beneficio | Esfuerzo | Dependencia | Prioridad |
|---|---|---|---|---|---|
| IFLOW-AUD-002 | `VmConfigLoader` devuelve pass-through | Habilita ejecutar `policy.flow` (función nuclear) | S (1 línea + test) | `ProgramImageParser` (listo) | Máxima |
| IFLOW-AUD-001 | Inmediatos negativos mal codificados | Correctitud del ensamblador (fixtures y programas reales) | S | — | Máxima |
| IFLOW-AUD-016 | Servicios property/state/channel sin cablear | La mayoría de features de política pasan a funcionar | M | APIs del host | Alta |
| IFLOW-AUD-008 | Firmware stub ON por defecto | Evita ROM corrupta por defecto | S (desactivar) / L (implementar) | — | Alta |

### P1 — Alto

| # | Problema | Beneficio | Esfuerzo | Dependencia | Prioridad |
|---|---|---|---|---|---|
| IFLOW-AUD-003 | Pool inoperante por journal | Rendimiento del camino caliente | S | — | Alta |
| IFLOW-AUD-004 | `prepareInput` longitud negativa | Robustez ante mensajes límite | S | — | Alta |
| IFLOW-AUD-006 | Servicios no soportados → REJECT | Contrato `UNSUPPORTED` correcto | S | IFLOW-AUD-016 | Media-alta |
| IFLOW-AUD-015 | Capabilities auto-declaradas | Seguridad / defensa en profundidad | M | Allowlist del host | Alta |

### P2 — Medio

| # | Problema | Beneficio | Esfuerzo | Dependencia | Prioridad |
|---|---|---|---|---|---|
| IFLOW-AUD-011 | `CURRENT_ABI_VERSION=1` vs ABI v2 | Compatibilidad binaria explícita | S | — | Media |
| IFLOW-AUD-012 | Drift README | Onboarding correcto | S | — | Media |
| IFLOW-AUD-013 | `dist/` versionado, `npm test` roto | Higiene de artefactos | S/M | — | Media |
| IFLOW-AUD-014 | Test de firmware no portable/débil | Señal de test real | S | — | Media |
| IFLOW-AUD-005 | `-d`/debug no-op | Coherencia de feature | S (eliminar) / M (implementar) | — | Media |

### P3 — Bajo

| # | Problema | Beneficio | Esfuerzo | Dependencia | Prioridad |
|---|---|---|---|---|---|
| IFLOW-AUD-007 | `fetch*` doble traducción y muerto | API honesta | S | — | Baja |
| IFLOW-AUD-009 | `maxStackSize` engañoso | Guard real | S | — | Baja |
| IFLOW-AUD-010 | `maxSize` del pool no aplicado | Concurrencia correcta | S | — | Baja |
| IFLOW-AUD-017 | Mensajes de error descartados | Observabilidad | S | — | Baja |
| IFLOW-AUD-018 | Subsistema regiones muerto | Limpieza | M | — | Baja |

---

## 24. Matriz de puntuaciones

| Área | Puntuación /10 | Estado | Principales problemas |
|---|---|---|---|
| Código | 6/10 | Aceptable | Estado/API muertos, duplicaciones, stubs |
| Arquitectura | 6/10 | Aceptable | Dirección correcta; engine sobrecargado; dos modelos de memoria |
| Clean Architecture | 5/10 | Parcial | Sin puertos formales; `PolicyResultValidator` acoplado a `ChannelRegistry` |
| Hexagonal | 5/10 | Parcial | Puerto de entrada (`Router`) formal; puerto de salida por lambdas, sin interfaces |
| Modularidad | 7/10 | Buena | Frontera compiler/VM limpia; `memory` mezcla vivo y muerto |
| Separación de responsabilidades | 6/10 | Aceptable | `VmPolicyEngine`/`Assembler` multi-responsabilidad |
| Legibilidad | 7/10 | Buena | Javadoc de calidad; ruido por clases muertas |
| Features | 4/10 | Incompleto | Loader stub; 6 servicios sin cablear; firmware stub |
| Personalización | 5/10 | Parcial | Capabilities auto-declaradas; sin allowlist de host |
| Seguridad | 4/10 | Débil | Capabilities auto-declaradas; fetch sin permiso efectivo |
| Rendimiento | 4/10 | Débil | Clon de 64 KB/mensaje; pool muerto; UUID por ruta |
| Escalabilidad | 4/10 | Débil | Coste por mensaje; sin estado persistente real |
| Concurrencia | 6/10 | Aceptable | `AtomicReference` correcto; cuentas del pool no atómicas |
| Testing | 6/10 | Aceptable | 261 tests; algunos débiles/no portables; huecos críticos |
| Mantenibilidad | 6/10 | Aceptable | Buena base; deuda y deriva documental |
| Documentación | 5/10 | Parcial | README preciso en forma, inexacto en estado (250 tests, servicios, `-d`) |

---

## 25. Potencial

- **Potencial teórico (INFERENCIA):** alto. Una VM con ABI syscall, capacidades, memoria paginada y journal atómico es una base capaz de sostener políticas sandboxeadas multiplataforma.
- **Potencial arquitectónico (INFERENCIA):** alto. El diseño ISA/ejecución/memoria/servicios y el mapa de memoria de fuente única ya soportan multi-target y hot-reload.
- **Potencial alcanzado (INFERENCIA):** **~40 %**. El «esqueleto» (ISA, memoria, parser, ABI v2, tests) está sólido; el «músculo» (loader, servicios, firmware, pool) está incompleto o inerte.

---

## 26. Conclusión final

1. **¿Qué tan bueno es realmente?** Como *arquitectura*, bueno; como *producto funcional*, incompleto: el camino crítico (cargar y ejecutar la política) está roto por un stub. Hoy no es un motor de políticas desplegable.
2. **¿Qué está excepcionalmente bien diseñado?** `VmMemoryMap` como fuente única, el ABI `HOST` v2 (documentado y coherente), la inmutabilidad del `ProgramImage` con verificación de hash, y la pre-validación de rangos en `SoftMMU.writeBlock`.
3. **¿Qué es mediocre?** El cableado de servicios y adaptadores (lambdas sin puertos), el empaquetado del compilador (`dist` versionado, `npm test` roto) y la coherencia documentación↔código.
4. **¿Peores defectos?** IFLOW-AUD-002 (loader stub), IFLOW-AUD-016 (servicios sin cablear) y IFLOW-AUD-003 (pool inoperante) + IFLOW-AUD-001 (inmediatos negativos).
5. **¿Mayor fortaleza arquitectónica?** La separación ISA/memoria/ejecución/servicios con un mapa de memoria centralizado: el diseño no impide completar las piezas que faltan.
6. **¿Mayor riesgo futuro?** La brecha entre lo documentado/declarado y lo implementado; si se construyen features sobre stubs (loader/firmware) sin detectarlo, se acumulará deuda silenciosa. Y el modelo de capabilities auto-declarado si alguna vez se despliega con servicios cableados.
7. **¿Cuán mantenible?** Media: código legible y modular, pero con estado muerto, duplicaciones y deriva documental que encarecen el mantenimiento.
8. **¿Cuán extensible?** Alta por diseño (nuevo `serviceId`, nuevo opcode, `setProgram` atómico); media en la práctica por la falta de puertos formales.
9. **¿Preparado para múltiples plataformas?** El núcleo es agnóstico (bytecode + Servicios inyectables): sí, una vez que el loader sea real y los adaptadores estén cableados por plataforma. El test no portable es un síntoma a corregir.
10. **¿Preparado para una red Minecraft grande?** **No hoy.** El clon de MMU por mensaje y el pool muerto son cuellos de botella de CPU/GC; y sin servicios de estado/persistencia no hay funcionalidad de política real.
11. **¿Qué cambiaría para escala network-level?** (a) loader real; (b) pool funcional + MMU compartido de solo lectura con COW; (c) `prepareInput` sin asignaciones; (d) servicios de estado distribuido; (e) capabilities de host.
12. **¿Qué NO debería tocarse?** `VmMemoryMap` (fuente única), el ABI `HOST` v2 y `ServiceResult` (una vez corregido el `UNSUPPORTED`), la verificación de hash del parser y la pre-validación de `writeBlock`. Están correctamente diseñados.
13. **¿Qué debería hacerse primero?** IFLOW-AUD-002 (loader) e IFLOW-AUD-001 (inmediatos negativos): dos cambios mínimos que restauran la funcionalidad nuclear y la correctitud del toolchain. Después IFLOW-AUD-003 (pool) e IFLOW-AUD-016 (servicios).
14. **¿Porcentaje técnicamente maduro?** **~40 %** del alcance auditado (cimientos maduros; capa funcional incompleta).

---

## Apéndice A — Reproducción de las verificaciones

```bash
# Toolchain
NODE_OPTIONS=--jitless node tools/iflow-compiler/test/validation/run.js        # 11/11 ok
JAVA_HOME=/opt/javac/x64/21 ./gradlew :src:iflow:test --offline --no-daemon    # BUILD SUCCESSFUL

# IFLOW-AUD-001: inmediatos negativos (fixture en /tmp)
printf '.section code\nmain:\n  LI R1, -1\n  LI R2, -128\n  LI R3, 255\n  LI R4, 256\n  HALT\n' > /tmp/iflowchk/neg.flowasm
node tools/iflow-compiler/dist/cli.js /tmp/iflowchk/neg.flowasm -o /tmp/iflowchk/neg.flow --no-firmware
xxd -s 16 -l 16 /tmp/iflowchk/neg.flow        # 0001 00ff 0002 0080 ...  (esperado: ffff / ff80)

# IFLOW-AUD-005: -d no-op
node tools/iflow-compiler/dist/cli.js /tmp/iflowchk/dbg.flowasm -o /tmp/iflowchk/dbg1.flow --no-firmware
node tools/iflow-compiler/dist/cli.js /tmp/iflowchk/dbg.flowasm -o /tmp/iflowchk/dbg2.flow --no-firmware -d
cmp /tmp/iflowchk/dbg1.flow /tmp/iflowchk/dbg2.flow   # idénticos

# IFLOW-AUD-003: pool no reutiliza (programa en /tmp contra iflow-2.1.0-SNAPSHOT.jar)
javac -cp src/iflow/build/libs/iflow-2.1.0-SNAPSHOT.jar /tmp/iflowchk/T.java
java  -cp src/iflow/build/libs/iflow-2.1.0-SNAPSHOT.jar:/tmp/iflowchk T
# -> after release: created=0 available=0 released=0 ; reused same instance? false

# Determinismo del ensamblador (HECHO)
node tools/iflow-compiler/dist/cli.js tools/iflow-compiler/test/simple.flowasm -o /tmp/iflowchk/simple.flow
cmp /tmp/iflowchk/simple.flow tools/iflow-compiler/test/simple.flow   # idénticos
```

*Fin del informe.*
