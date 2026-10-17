# Auditoría técnica — iFlow (VM `src/iflow/` + compilador `tools/iflow-compiler/`)

> **Documento de auditoría de solo lectura.** No se modificó código, artefactos ni configuración del repositorio. Los únicos archivos escritos durante esta auditoría fueron este informe y los artefactos de build/test regenerados por las herramientas de Gradle/Node. Las verificaciones experimentales se ejecutaron en el árbol de trabajo del propio repositorio.
>
> **Esquema de numeración (declaración obligatoria):** este es el **segundo** informe de auditoría sobre el alcance. El primero (commit `5ade855`, ya en `docs/workdir/AUDITORIA.md` antes de sobrescribirse, preservado en Git vía `7455c73`) usó el namespace `IFLOW-AUD-001…018`. Para no colisionar con esos IDs —que siguen referenciándose en `docs/workdir/PLAN.md`— **los hallazgos de este informe continúan la serie a partir de `IFLOW-AUD-019`**.
>
> **Etiquetas de evidencia:** **HECHO** (demostrado por código/config/tests/binarios), **INFERENCIA** (conclusión razonable a partir del código) y **HIPÓTESIS** (requiere benchmark/ejecución para confirmarse). Se aplican a cada afirmación relevante.

---

## 1. Fecha, hora UTC y commit auditado

- **Fecha/hora UTC de la auditoría:** inicio `2026-10-17T22:45:24Z` · cierre `2026-10-17T22:58Z`.
- **Repositorio:** `/home/majhrs16/Documentos/textformatter-suite` (checkout local; no se clonó).
- **Rama:** `main`.
- **Commit (HEAD):** `dacf6c75606d08a0a10344b2f7245ecd8ded7ed8` — *docs(plan): confirm the handle-based memory model is obsolete after flat memory* — 2026-10-17 18:25:24 -0400.
- **`git status`:** limpio antes y después de las verificaciones (solo se regeneraron artefactos de `build/`, no versionados).
- **Alcance:** **solo** `src/iflow/` y `tools/iflow-compiler/`. El resto de módulos se consultó únicamente como contexto de consumo real (`src/host`, `src/loadtest`).
- **Toolchain:** JDK 21 (`/opt/javac/x64/21`), Gradle wrapper (8.10), Node v24, `NODE_OPTIONS=--jitless`, versión de módulo `2.1.0-SNAPSHOT`.

**HECHO:** `git log -1` confirma el commit y su árbol limpio; no se ejecutó `git push` ni se tocó ningún fuente.

---

## 2. Cobertura de auditoría

### 2.1 Compilador `tools/iflow-compiler/`

| Elemento | Estado |
|---|---|
| `src/assembler.ts` (1226 líneas) | Leído íntegro (incl. `generateFirmware`, serializadores, `debugInfo`) |
| `src/cli.ts` (115), `src/index.ts` (9), `src/types.ts` (289) | Leídos |
| `package.json`, `tsconfig.json`, `README.md` | Leídos |
| `test/validation/run.js` + `cases.json` + 14 fixtures `.flowasm` | Leídos y **ejecutados** |
| `npm run build` (tsc) + `node test/validation/run.js` | **13/13 OK** |
| `dist/` | Verificado **no versionado** en Git (`git ls-files dist` vacío) |
| Reensamblado de `firmware.flowasm`, `simple.flowasm`, `main_only.flowasm` | **Byte-idéntico** a los `.flow` versionados (determinismo) |

### 2.2 VM `src/iflow/`

| Elemento | Estado |
|---|---|
| 26 clases Java de `src/main` (4657 líneas) | Leídas íntegras |
| 21 clases Java de `src/test` (**288 tests**) | Leídas íntegras las clave; ejecutadas todas |
| `./gradlew :src:iflow:test --offline --no-daemon --rerun-tasks` | `BUILD SUCCESSFUL` (15 tareas ejecutadas) |
| XML de resultados | **tests=288, failures=0, errors=0, skipped=0** |
| `README.md`, `build.gradle` | Leídos |
| `docs/prompts/iflow/design.md`, `implement.md` | Leídos (mapa de memoria, ABI v2, rings, §5.3, §10, §11, §14) |

### 2.3 Contexto externo (solo uso real)

- `src/host/.../SuiteBootstrap.java` y `SuiteHost.java` — consumidores de producción de `VmConfigLoader.loadProgram` y `VmPolicyEngine`.
- `src/loadtest/.../MessageProcessingBenchmark.java`, `EndToEndIntegrationTest.java`.
- `docs/workdir/PLAN.md` (bitácora de remediación) y el informe anterior de `AUDITORIA.md` en Git.

### 2.4 Límites declarados

- **No** se ejecutó el benchmark de carga ni una red Minecraft real: toda afirmación de escalabilidad es **INFERENCIA/HIPÓTESIS**.
- No se midió el coste real por mensaje (no hay `jmh`); los juicios de rendimiento son por traza de código → **HECHO estructural** + **HIPÓTESIS cuantitativa**.

---

## 3. Resumen ejecutivo

iFlow es un **intérprete de 16 bits con memoria plana de 64 KB, paginación por software (SoftMMU 4 KB) y un ABI `HOST` v2 estilo syscall**, más un **ensamblador TypeScript de dos pasadas** con firmware ROM real. Desde el informe anterior (commit `5ade855`) el estado ha mejorado de forma sustancial: los 18 hallazgos previos se atacaron y **17 están resueltos** (loader real con parser, inmediatos negativos, pool operativo con `journal.reset()`, `prepareInput` con acotado, `UNSUPPORTED`, ABI v2 exacta, `fetch*` física, ROM de firmware real, guard de pila, cota de pool, capabilities concedidas por el host con intersección, puerto `HostContext`, `ServiceResult.detail`, `dist/` no versionado, `npm test = validación`).

Sin embargo, esta auditoría —centrada en el **eje memoria/ABI/DMA/parecido-a-hardware**— confirma un defecto central y varios asociados:

1. **El `VmDmaController` es código muerto.** Se restauró en el commit `f9f96ad` (y se documentó en `README.md` y `PLAN.md`) afirmando que «el emulador lo usa para mapear y transferir memoria según la configuración del host». **Esa afirmación es falsa (HECHO):** en todo el repositorio, `VmDmaController` solo está referenciado por su propio test. La inyección de entrada y la extracción de resultados se hacen **directamente por `SoftMMU`** (`ctx.softMMU().writeBlock/getRegion/writeByte`), saltándose el DMA. **IFLOW-AUD-019 (Alta).**
2. **El buffer de salida (`0x8000`) nunca se lee.** Se mapea R/W y se limpia en cada ejecución, pero **ninguna ruta de producción lo consume**; el resultado viaja solo por `ModificationJournal`. Es una feature de E/S muerta. **IFLOW-AUD-020 (Media).**
3. **No hay bus ni encapsulación de memoria hacia el host:** el motor sostiene referencias directas al `SoftMMU` y el `byte[]` interno se expone mutable (`getPhysicalMemory()`), además de tres accesores de `PageTable` duplicados sin uso. **IFLOW-AUD-022 (Media/Baja).**
4. **El fetch no pasa por la MMU:** el intérprete decodifica desde `program.code()` (heap Java), no desde la memoria paginada; el permiso `EXECUTE` de las páginas 0-1 es **nominal**. **IFLOW-AUD-023 (Media).**
5. **El modelo de privilegios (`PageEntry.user`, `mapPageKernel`) no se aplica** y el ABI/R2-R3 se usa de forma consistente salvo en el formateo del host. **IFLOW-AUD-030 / hallazgo de ABI.**

**Valoración global:** cimientos buenos y ahora **funcionales en el camino crítico** (el loader real y los servicios cableados hacen que la política compilada se ejecute de verdad). Pero el subsistema se presenta —en README y en el mensaje del commit `f9f96ad`— como más «hardware» de lo que es: **el DMA no está integrado**, no hay bus, la MMU no traduce de forma efectiva (mapeo identidad) y el fetch evade la protección. Madurez técnica estimada del alcance auditado: **~62 %** (sube desde el ~40 % del informe previo, por la remediación de 001–018, pero lastrada por el DMA/fachada falsa).

---

## 4. Arquitectura real

### 4.1 Arquitectura pretendida vs. implementada

**Pretendida (README/PLAN/spec):** el host carga un `policy.flow` compilado, lo ejecuta por mensaje en la VM; la VM llama a servicios `HOST` (propiedades, estado, canales, permisos, enrutado) a través de un puerto; los cambios se registran en un journal atómico; hay una ROM de firmware con utilidades y wrappers; y el DMA centraliza el intercambio host↔VM.

**Implementada (HECHO por lectura de código):**

```
                    tools/iflow-compiler (TS)
  .flowasm ──► Assembler (2 pasadas) ──► ProgramImage ──► serializeProgramImage() ──► .flow
                        │  (ROM firmware REAL: fw_strlen/strcpy/strcmp/memcpy/memset + wrappers HOST)
                        ▼
  src/host/SuiteBootstrap/SuiteHost ──► VmConfigLoader.loadProgram ──► ProgramImageParser.parse (real, SHA-256)
                        ▼
  new VmPolicyEngine(...) ──► setProgram(program) ──► setupProgramMappings (SoftMMU + PageTable)
                        ▼
  mensaje ──► VmPolicyEngine.route() ──► ExecutionContextPool.acquire()
      │            │
      │            ├── ctx.softMMU(new SoftMMU(loaded.softMMU))   ← clona 64 KB por mensaje
      │            ├── ctx.hostContext(MessageHostContext)         ← puerto del host (IFLOW-AUD-016)
      │            ├── prepareInput(): serializa texto/canal/dirección/actores en 0x7000 vía SoftMMU.writeBlock
      │            ├── new VmInterpreter(program, ctx.softMMU(), registry)   ← clona program.code()
      │            │        └── bucle decode desde codeCache (NO desde SoftMMU); HOST → HostServiceRegistry.invoke
      │            │                 └── servicios leen/escriben en 0x7000 vía ctx.softMMU() (NO vía DMA)
      │            └── PolicyResultValidator.validate() → journal.commit()/discard()
      │
      ▼
  [VmDmaController]  ← NO INSTANCIADO EN PRODUCCIÓN (solo en VmDmaControllerTest)
```

### 4.2 Componentes y responsabilidades reales

| Componente | Responsabilidad real |
|---|---|
| `Assembler` (TS) | Ensamblado 2 pasadas, firmware ROM real, serialización `.flow`, SHA-256, listing |
| `cli.ts` | Envoltura `commander`; `-d` (solo `debugInfo` en memoria, no en el binario), `-l`, `--no-firmware` |
| `VmConfigLoader` | **Real**: parsea el `.flow` con `ProgramImageParser`; fallback pass-through |
| `ProgramImageParser` | Parser binario real con verificación SHA-256 y validación de versiones/límites |
| `VmPolicyEngine` | Orquestador: permisos de canal, pool, `prepareInput`, ejecuta VM, valida, publica `LoadedProgram` atómico |
| `VmInterpreter` | Bucle fetch/decode/execute; usa `codeCache = program.code()` (no SoftMMU) |
| `SoftMMU` + `PageTable` + `PageEntry` | Traducción v→f y permisos R/W/X; mapeo **identidad**; `fetch*` solo usados por tests |
| `HostServiceRegistry` | 7 servicios registrados; leen/escriben memoria vía `ctx.softMMU()`; puerto genérico `HostContext` |
| `MessageHostContext` (inner de `VmPolicyEngine`) | Vista por mensaje: propiedades, canal, estado del host; **`writeProperty` siempre registra `SET_TEXT`** |
| `ModificationJournal` | Journal con estados committed/discarded + `reset()` reutilizable |
| `ExecutionContextPool` | Pool `ConcurrentLinkedQueue`, retención atómica; **ahora sí reutiliza** (`journal.reset()`) |
| `VmDmaController` | **Código muerto en producción**: solo instanciado por `VmDmaControllerTest` |

### 4.3 Dirección de dependencias

`src/iflow` depende de `core-api`, `textformatter` y `logger`. `src/host` y `src/loadtest` dependen de `src/iflow`. Dirección coherente. Dentro de `vm`: `isa` (puro) ← `memory` ← `execution`/`service` ← `engine`. **HECHO.**

---

## 5. Flujo completo del sistema (camino real observado)

1. **Compilación (offline):** `node dist/cli.js prog.flowasm -o prog.flow` → `Assembler.assemble()` → 1.ª pasada (labels/directivas), 2.ª pasada (bytecode, padding, ROM de firmware con fixups) → `createResult()` (hash SHA-256) → `serializeProgramImage()`.
2. **Arranque del host:** `SuiteBootstrap.java:99` / `SuiteHost.java:115` llaman `VmConfigLoader.loadProgram(configDir, logger)`.
3. **Carga:** si existe `policy.flow`, se **parsea de verdad** (`ProgramImageParser.parse`), validando versión de formato, ISA ≤ 2, **ABI == 2 exacta**, límites de sección y SHA-256; si falla, cae al pass-through.
4. **Motor:** `SuiteHost.java:121` crea `VmPolicyEngine` y `setProgram(...)`; `setupProgramMappings` puebla el `SoftMMU` (código R/X, constantes R, datos/pila/IO R/W) y publica `LoadedProgram` atómicamente.
5. **Enrutado por mensaje (`route`):** verifica `send/receivePolicy`; adquiere contexto del pool; **clona el `SoftMMU`**; instala `MessageHostContext`; `prepareInput` (escribe el mensaje en `0x7000`); crea `VmInterpreter` y ejecuta; `PolicyResultValidator.validate`; `release`.
6. **Resultado:** `RouteOutcome` (LOG/DROP/REJECT/REDIRECT/CHANNEL_REDIRECT/RATE_LIMIT).

**Observación clave (HECHO):** ninguna etapa del intercambio host↔VM pasa por `VmDmaController`; todo va por `SoftMMU`. El DMA no aparece en el camino real.

---

## 6. Evaluación por feature

| Feature | Estado real | Evidencia |
|---|---|---|
| Ensamblado `.flowasm` → `.flow` | Funciona; determinista; 13/13 fixtures | Reensamblado byte-idéntico |
| Firmware ROM por defecto | **Real y funcional** (utilidades + wrappers + fixups) | `generateFirmware`; `FirmwareIntegrationTest` |
| Flag bare-metal (`--no-firmware`) | Funciona | `userOrigin()` |
| Carga de `.flow` en runtime | **Real** (parser con hash y versiones) | `VmConfigLoader.loadNativeProgram` |
| Ejecución ISA v2 | Implementada y probada (aritmética, memoria, indirecta, saltos, CALL/RET) | `VmInterpreter`, 288 tests |
| Servicios `HOST`: permisos, routing, propiedad, estado, canal | **Cableados vía `HostContext`** | `VmPolicyEngine.createServiceRegistry` + `MessageHostContext` |
| **Escritura de propiedad distinta de `content`** | **No soportada**: `writeProperty` siempre registra `SET_TEXT` | `MessageHostContext.writeProperty` |
| Permisos de página R/W | Aplicados a load/store | `PageTable.translateRead/Write` |
| **Permiso X aplicado al fetch** | **Nominal**: el intérprete lee `codeCache` | `VmInterpreter.codeCache` |
| **DMA host↔VM** | **No integrado** (código muerto) | `grep` repo-wide; solo `VmDmaControllerTest` |
| **Buffer de salida `0x8000`** | **Mapeado pero nunca consumido** | `grep` de `OUTPUT_BASE` solo en setup/init |
| Modelo de anillos (ring 0/3) | **No implementado** (`PageEntry.user` ignorado) | `PageEntry.isUser` sin usos |
| Páginas 9-15 (estado persistente) | **No mapeadas** | `setupProgramMappings` no las toca |
| Journal atómico | Correcto; pool reutiliza | `ModificationJournal.reset()` + `ExecutionContextPool` |

### 6.1 ABI v2 y uso de R2/R3 (punto secundario 3)

**HECHO — consistente.** `VmInterpreter.executeHost` (líneas 503-525) expone `R0=serviceId`, `R1=options`, `R2=base`, `R3=length`, y devuelve `R0=status`, `R3=bytes`, `R4=result`; `HostServiceRegistry.invoke` recibe `argVAddr=R2`/`argLength=R3`; `ServiceResult` documenta y respeta ese contrato; el firmware (`fw_prop_read`, `fw_prop_write`, `fw_perm_check`, `fw_route_decide`, `fw_state_read/write`, `fw_channel_resolve`) coloca base en `R2` y longitud en `R3` antes de `HOST`. **La única desviación** es que `VmPolicyEngine.prepareInput` **no usa el ABI** (no es una llamada `HOST`): formatea el buffer del host directamente en `0x7000` con `writeField` (base fija, campos secuenciales). Es coherente internamente, pero implica que un programa debe conocer 0x7000 y el layout del framing fuera de banda (ver 6.3).

### 6.2 Integridad de memoria (punto secundario 1)

**HECHO:** el mapa de `VmMemoryMap` no presenta solapes: código 0x0000-0x1FFF (R/X), constantes 0x2000 (R), datos 0x3000-0x4FFF (R/W), pila 0x5000-0x6FFF (R/W), entrada 0x7000, salida 0x8000. La región de código reserva siempre 2 páginas, por lo que **nunca aliasa constantes** (corrección de `7e4cae5`). `SoftMMU.writeBlock` pre-valida todos los rangos antes de mutar (sin escrituras parciales ante fault). Aislamiento entre ejecuciones: el `SoftMMU` se **clona** del programa prístino en cada mensaje, y `initialize()` limpia registros, journal, `hostContext` y los 2 buffers de I/O → **no hay fuga** de datos entre mensajes. Puntos débiles: (a) `readBlock/writeBlock` no validan `length < 0` en la fachada `SoftMMU` (solo el `VmDmaController` —muerto— y el engine lo hacen); (b) no hay traducción real (mapeo identidad), luego la «protección» se limita a permisos por página; (c) el `byte[]` físico puede escaparse mutable.

### 6.3 ¿Mapea 1:1 a un diseño HDL? (punto secundario 4)

**Parcial (INFERENCIA sobre HECHO de código).** Se parecen a hardware: la CPU con bucle fetch/decode/execute, el banco de registros unificado (`RegisterFile`, 12 registros), la «ROM» (firmware en 0x0000), la «RAM» (datos/pila), el «IO» (0x7000/0x8000) y la tabla de páginas con permisos. **No** se parecen a hardware y serían difíciles de sintetizar:

- **No hay bus**: el host tiene referencias directas al `SoftMMU` y a un `byte[]`; los servicios acceden por método, no por un bus de direcciones/datos con ciclos.
- **El fetch no usa la MMU** (lee un array Java), así que no hay una «memoria de instrucciones» real.
- **El DMA no está integrado** (sería el canal de E/S del hardware).
- **La MMU no traduce** (frames = direcciones virtuales): no hay paginación efectiva ni TLB.
- **No hay controlador de interrupciones**: `HOST` es una llamada síncrona, no un vector de IRQ.
- **No hay anillos**: `PageEntry.user` y `mapPageKernel` existen pero nadie los consulta.

### 6.4 Veredicto: DMA, «parecido a hardware» y cambios mínimos hacia HDL mapeable (punto secundario 5)

- **DMA:** **NO integrado.** No es una hipótesis: no hay ni una referencia de producción. El commit `f9f96ad` y `PLAN.md` (líneas 1226-1237) documentan lo contrario (afirmación **falsa**). Debe decidirse: (a) integrarlo de verdad como la única puerta de E/S host↔VM (entrada del mensaje y extracción del buffer de salida), o (b) eliminarlo y corregir README/PLAN.
- **Hardware-likeness:** ~media. La forma (CPU/memoria/paginación/firmware/IO) es la de un procesador; pero sin bus, sin DMA, sin traducción real y sin privilegios es todavía «una máquina virtual con forma de CPU», no un diseño sintetizable.
- **Cambios mínimos hacia un diseño mapeable a HDL:** (1) introducir un **bus explícito** (una interfaz `Bus`/`MemoryPort` que sea la única vía de acceso host↔memoria, y hacer que el intérprete y los servicios la usen); (2) **integrar el DMA** como el adaptador de ese bus para E/S (o eliminarlo si se renuncia a la metáfora); (3) rutear el **fetch por la MMU** (`SoftMMU.fetchByte`/`fetchWord` ya existen y ya comprueban EXECUTE) en vez de `codeCache`; (4) hacer que la **MMU traduzca** de verdad (no identidad) para separar espacio virtual y físico; (5) aplicar **privilegios** (`PageEntry.user` + `mapPageKernel`) a un modelo de ring 0/3. Estos cinco cambios no requieren reescribir: reutilizan piezas ya presentes.

---

## 7. Evaluación de código

**Fortalezas (HECHO):**
- `VmMemoryMap` como fuente única de direcciones, consumida por parser, engine y contexto.
- `ProgramImage`/`ProgramImageParser` inmutables, con *defensive copies* y verificación SHA-256.
- `SoftMMU.writeBlock` pre-valida rangos completos antes de mutar.
- `fetch*` ya comprueba solo EXECUTE (corrección de AUD-007) — aunque el intérprete no los use.
- Errores tipados (`VmException.Code`) con captura de IP.
- ABI v2 bien documentada y coherente con `ServiceResult`; puerto `HostContext` formal (AUD-016); `grantedCapabilities` interseca solicitadas ∩ concedidas (AUD-015); `ServiceResult.detail` conserva el motivo (AUD-017).
- Pool realmente operativo: `ExecutionContext.initialize()` usa `journal.reset()` (no `clear()`), por lo que la reutilización ya no lanza.

**Debilidades (HECHO):**
- **DMA muerto** documentado como vivo (`README.md:20,166,223`; `PLAN.md:1226-1237`; commit `f9f96ad`).
- `SoftMMU` expone **cuatro** accesores equivalentes a la misma `PageTable` (`getPageTable`, `getPageTableDirect`, `getPageTableForInspection`, `getPageTableForMutation`) y los tres últimos **no se usan en ningún sitio**; además `getPhysicalMemory()` filtra el `byte[]` interno mutable.
- Estado duplicado/muerto en `ExecutionContext`: `pageTable` (siempre `null` en producción; `setPageTable` solo en tests) y getters `programId/messageId/actorId` que se asignan pero nunca se leen.
- `MessageHostContext.writeProperty` ignora `scope`/`propId` y siempre hace `SET_TEXT` → `PROPERTY_WRITE` no puede cambiar canal/idioma/color ni otras propiedades.
- `HostServiceRegistry` mantiene campos inyectables muertos (`placeholderResolver`, `translationDetector`) y declara constantes de servicio no registradas (`PROPERTY_EXISTS`, `PERMISSION_QUERY`, `ROUTE_REDIRECT`, `ROUTE_RATE_LIMIT`, `TRANSFORM_APPLY`, `CHANNEL_POLICY`, `MESSAGE_CLONE`, `MESSAGE_ID`).
- Duplicación de `createPassThroughProgram()` entre `VmConfigLoader` (con stubs `Opcode`/`HostServiceRegistry` internos) y `VmPolicyEngine`.
- `-d` no emite sección de debug en el binario (documentado honestamente en el README del compilador, pero sigue siendo un no-op de salida).

Complejidad: la clase mayor es `VmInterpreter` (627 líneas) seguida de `VmPolicyEngine` (524) y `HostServiceRegistry` (503). Legibilidad buena.

---

## 8. Clean Architecture

- **Capa ISA** (`Opcode`, `ProgramImage`, `RegisterFile`, `VmException`): dominio puro, sin infraestructura. **Bien.**
- **Capa ejecución** (`VmInterpreter`, `ExecutionContext`, `ModificationJournal`, `ExecutionContextPool`): orquesta; `VmInterpreter` conoce `HostServiceRegistry` (acoplamiento aceptable).
- **Capa engine**: `VmPolicyEngine` sigue mezclando composición, configuración de memoria, serialización de entrada y publicación atómica. Mayor infractor de SRP.
- `PolicyResultValidator` (engine) construye `Message`/`Actor` de `core-api` y conoce `ChannelRegistry` concreto de `textformatter`.

**Veredicto (INFERENCIA):** separación por paquetes razonable, pero **no hay puertos/adaptadores formales** salvo el recién añadido `HostContext` (que sí es un puerto de salida correcto). No se puede declarar «Clean Architecture correcta» solo por nombres de paquete (§26).

---

## 9. Hexagonal Architecture

- El núcleo es la VM; el **puerto de salida** ahora es `HostContext` (interfaz explícita implementada por `MessageHostContext` + `HostContext.NONE`): **mejora real** respecto al informe previo (lambdas sueltas).
- `Router` (`core-api`) es el **puerto de entrada** formal: `VmPolicyEngine implements Router`. **Bien.**
- Persisten acoplamientos concretos: `PolicyResultValidator`↔`ChannelRegistry` y el motor sostiene un `SoftMMU` concreto en lugar de un puerto de memoria/bus.

**Veredicto (INFERENCIA):** hexagonal **parcial-buena**: puerto de entrada formal y puerto de salida ya formalizado; falta el puerto de **memoria/bus** (que es precisamente lo que permitiría el DMA y el mapeo HDL).

---

## 10. Modularidad

- Dos módulos separados por el formato `.flow`: compilador (TS) y VM (Java). Buena frontera.
- `isa`/`execution`/`memory`/`service`/`engine` bien delimitados.
- `memory` contiene la clase muerta `VmDmaController` sin marcarla como tal, lo que sigue confundiendo al lector.
- `tools/iflow-compiler` ya tiene validación CLI (`npm test`) y un barrel real (`src/index.ts`).

---

## 11. Separación de responsabilidades

| Clase | SRP | Comentario |
|---|---|---|
| `Assembler` | Parcial | Parsing + codegen + firmware + hash + serialización |
| `VmPolicyEngine` | Baja | Permisos de canal + pool + memoria + I/O + validación + host view |
| `HostServiceRegistry` | Parcial | Registro + 7 servicios + inyección + campos muertos |
| `VmInterpreter` | Alta | Fetch/decode/execute bien delimitado |
| `SoftMMU`/`PageTable` | Alta | Traducción y permisos |
| `PolicyResultValidator` | Alta | Validación de resultado |
| `ExecutionContext` | Media | Configuración + estado + host port + memoria + journal |

---

## 12. Seguridad

- **Capabilities:** ahora `grantedCapabilities(requested, granted)` interseca lo que el programa **solicita** con lo que el host **concede** (AUD-015 resuelto). La allowlist del host es la unión de `declareCapability` + `requiredCapabilities` de los servicios registrados. **HECHO.**
- **Fetch sin EXECUTE efectivo:** el intérprete lee `codeCache` (heap Java); la página de código está mapeada R/X pero el permiso no se consulta en el fetch. Mitigaciones de facto: `IP` acotado a `codeSize` y `checkGPR` impide `LI`/aritmética sobre IP/SP/FP. **HECHO/INFERENCIA.**
- **Sin privilegios de kernel/user:** `PageEntry.user` y `mapPageKernel` existen pero **nadie los comprueba**; el modelo ring 0/3 de la spec §14 no está implementado. **HECHO.**
- **Escritura de propiedades limitada pero mal tipada:** `PROPERTY_WRITE` solo puede reescribir el texto (siempre `SET_TEXT`), lo que de hecho **reduce** la superficie de ataque (no permite cambiar canal/idioma arbitrariamente), pero por accidente, no por diseño.
- **Integridad binaria:** el parser verifica SHA-256 y ABI/ISA exactas: un `.flow` manipulado se rechaza al cargar. **HECHO.**

**Veredicto:** seguridad **media**: el modelo de capabilities quedó correcto y el parser es estricto, pero la protección de ejecución y los privilegios son nominales.

---

## 13. Rendimiento

**HECHO estructural (por traza de código), HIPÓTESIS en magnitud:**

- **Clonado de MMU por mensaje:** `VmPolicyEngine.route` hace `new SoftMMU(loaded.softMMU)` → `physicalMemory.clone()` (64 KB) + `pageTable.copy()` por cada `route()`.
- **Clon de código por mensaje:** se crea `new VmInterpreter(loaded.program, ...)` en cada `route()`; su constructor hace `this.codeCache = program.code()` → `ProgramImage.code()` devuelve `code.clone()` (hasta 8 KB por mensaje). El intérprete podría reutilizarse con el programa (es inmutable).
- **Limpieza de I/O por byte:** `ExecutionContext.initialize()` ejecuta **8192** `softMMU.writeByte` (4096 de entrada + 4096 de salida), cada uno con traducción + comprobación de permisos, por cada mensaje.
- **Pool:** ya reutiliza el `ExecutionContext`, así que se evitan crear registros/journal nuevos, pero **no** el clon de MMU/clon de código/limpieza, que siguen siendo por mensaje.
- **`UUID.randomUUID()` por ruta** seguía presente en informes previos; sigue (`ctx.executionId(UUID...)`).
- `isContiguous` + `arraycopy` están bien; el *slow path* por byte es aceptable.

**HIPÓTESIS:** bajo miles de mensajes/s, el clon de 64 KB + el clon de código + los 8192 `writeByte` dominarían el perfil. No se midió (sin benchmark).

---

## 14. Concurrencia

- `loadedProgramRef` es `AtomicReference<LoadedProgram>`: publicación coherente programa+memoria. **Bien.**
- `ExecutionContextPool.release` decide retener/descartar bajo `synchronized(this)` con `AtomicInteger` para los contadores; ya **no** hay *check-then-act* en `available.size() < maxSize`. `created` se ajusta al descartar y al cerrar. **HECHO (AUD-010 resuelto).**
- `hostState` es `ConcurrentHashMap` (estado persistente entre mensajes, por diseño). `RateLimiter` se comparte; se asume thread-safe (solo lectura).
- Cada mensaje usa su propio `SoftMMU` clonado, por lo que no hay carrera sobre la memoria de ejecución.

**Veredicto:** correcta para el camino actual; el punto débil ya no es la concurrencia del pool, sino la **copia de estado por mensaje**.

---

## 15. Escalabilidad

- `setProgram` es atómico y permite hot-reload: buena base.
- Los cuellos reales son de implementación (clon de MMU 64 KB + clon de código + limpieza por byte), no de diseño.
- El estado persistente entre mensajes existe (`hostState`) pero **no** hay estado persistente *en la memoria de la VM* (páginas 9-15 sin mapear, capability inexistente).

**INFERENCIA:** para escala network-level, el coste por mensaje actual es un lastre; haría falta MMU compartida de solo lectura + COW, reutilizar el `VmInterpreter` por programa y evitar el clon de código.

---

## 16. Testing

- **288 tests, 0 fallos, 0 errores, 0 skipped** (leído del XML tras `--rerun-tasks`). El README declara 288 → **coincide (ya no hay drift en el recuento)**.
- Cobertura amplia: ISA completa, memoria/paginación, permisos, `fetch*`, bloques, servicios, integración, pool, capa de config.
- `VmDmaControllerTest` (24 tests) prueba un componente **que no se usa en producción**: da una falsa sensación de que el DMA forma parte del sistema. **HECHO.**
- **Sin cobertura efectiva** de: la ruta DMA integrada (no existe), el consumo del buffer de salida (no existe), la escritura de propiedades distintas de `content`, `PageEntry.user`/`mapPageKernel`, y el fetch con permiso EXECUTE real.
- `FirmwareIntegrationTest` ahora es portable y asertivo (AUD-014 resuelto).
- `jacocoTestCoverageVerification` exige solo `LINE ≥ 0.25` (bajo).

---

## 17. Mantenibilidad

- Código legible, Javadoc de calidad en las clases clave (`VmMemoryMap`, `HostServiceRegistry`, `ServiceResult`).
- La coexistencia de la clase muerta `VmDmaController` con documentación que la presenta como viva (README/PLAN/commit) es el principal foco de desinformación.
- `HostServiceRegistry` acumula constantes de servicio no registradas y campos inyectables muertos.
- Duplicación del pass-through en dos clases.

---

## 18. Historial / evolución (Git)

```
dacf6c7 docs(plan): confirm the handle-based memory model is obsolete after flat memory  (HEAD)
7455c73 docs(audit): add the iFlow technical audit report
f9f96ad fix(iflow): restore VmDmaController removed by AUD-018     ← restauración + afirmación de uso NO respaldada
49f225e docs(plan): close the P2/P3 remediation (AUD-010/011/012/013/017/018)
afc9a77 chore(compiler): stop tracking dist/, add a real entry point and fix npm test (AUD-013)
e9dabba docs(iflow): align the READMEs with the code (AUD-012)
82668e9 refactor(iflow): remove the dead per-handle memory-region subsystem (AUD-018)
1fbdb64 fix(iflow): make the context pool bound real and its counters consistent (AUD-010)
e30a6a9 fix(iflow): keep host-service failure reasons instead of discarding them (AUD-017)
f60b99c fix(iflow): ABI version is v2 and the parser requires an exact match (AUD-011)
26a5985 refactor(iflow): formal HostContext port for HOST services (IFLOW-AUD-016)
a410a31 iflow: ISA v2 (LDBI/LDWI/STBI/STWI) + real firmware ROM
e446db2 fix(iflow): fetch* reads physical memory after translateExecute (AUD-007)
```

**Lectura (HECHO + INFERENCIA):** hubo una campaña de remediación real que resolvió 17 de los 18 hallazgos previos. La excepción es la «rectificación» de AUD-018: se restauró `VmDmaController` con la **suposición de que estaba en uso** (el propio mensaje del commit lo dice: «The AUD-018 cleanup inferred VmDmaController was dead because only its own test referenced it. That inference was wrong…»). Esta auditoría demuestra que **la suposición inversa también es falsa**: sigue sin usarse.

---

## 19. Clasificación de hallazgos

> IDs en el namespace `IFLOW-AUD-*`, **continuando desde `IFLOW-AUD-019`**. Etiquetas: **HECHO**, **INFERENCIA**, **HIPÓTESIS**. Clasificación obligatoria por hallazgo: ID, Tipo, Severidad, Confianza, Ubicación (`Archivo -> Clase -> Método`), Descripción, Causa, Impacto funcional, Impacto rendimiento/escalabilidad, Condiciones para reproducir, Evidencia, Solución sugerida, Código refactorizado.

---

### [IFLOW-AUD-019]

**Tipo:** Defecto de diseño (código muerto presentado como productivo) + desinformación documental
**Severidad:** Alta
**Confianza:** Confirmado (HECHO, `grep` repo-wide)
**Ubicación:** `src/iflow/.../vm/memory/VmDmaController.java -> VmDmaController` (toda la clase, 230 líneas); consumidores **inexistentes** en `vm/engine/VmPolicyEngine.java`, `vm/execution/VmInterpreter.java`, `vm/service/HostServiceRegistry.java`; documentación en `src/iflow/README.md:20,166,223` y `docs/workdir/PLAN.md:1226-1237`; commit `f9f96ad`.

**Descripción:** `VmDmaController` es la fachada de transferencia de memoria (read/write/copy/move/validateRange con permisos). **No se instancia ni se invoca en ninguna ruta de producción.** El intercambio host↔VM se hace por `SoftMMU` directamente: `VmPolicyEngine.prepareInput` → `ctx.softMMU().writeBlock(...)`; `HostServiceRegistry.doPropertyRead/doStateRead/doChannelResolve` → `ctx.softMMU().writeBlock(...)`; `doPropertyWrite/doStateWrite/doPermissionCheck/doRouteDecision` → `ctx.softMMU().getRegion(...)`; el intérprete → `mmu.readByte/writeWord/...`. El commit `f9f96ad` restauró la clase afirmando que «the emulator uses [it] to map and move memory according to the host configuration»; `PLAN.md` lo repite. **Ambas afirmaciones son falsas.**

**Causa:** la determinación previa (AUD-018) de que era muerta era correcta; la rectificación se basó en la descripción de la clase, no en sus referencias reales. La restauración revirtió un borrado correcto sin reconectar la clase al motor.

**Impacto funcional:** ninguno directo (el sistema funciona sin ella), pero **rompe el modelo mental documentado**: cualquier lector/ingeniero que intente razonar sobre el flujo de E/S creerá que pasa por el DMA y no lo hará. Es deuda activa y una falsa promesa de arquitectura.

**Impacto en rendimiento/escalabilidad:** neutro hoy; si se «integrara» sin diseño, podría añadir una capa de copia extra.

**Condiciones para reproducir:** `grep -rn "VmDmaController" --include=*.java` → solo la propia clase y `VmDmaControllerTest`; `grep -rn "new VmDmaController" src/iflow/src/main` → **vacío**.

**Evidencia (HECHO):** salida del `grep` anterior; `f9f96ad` con su mensaje; ausencia de cualquier instanciación en `src/main`.

**Solución sugerida (dos alternativas explícitas):**
- **(A) Integrarlo de verdad** como única puerta de E/S: que `prepareInput`, la extracción del buffer de salida y los servicios de buffer usen un `VmDmaController` inyectado en `ExecutionContext` en vez de `softMMU()` directo. Es el paso coherente con «mapear a HDL» (el DMA pasa a ser el canal de E/S del bus).
- **(B) Eliminarlo** (junto con su test) y corregir `README.md` y `PLAN.md` para no vender una feature inexistente. Es el camino mínimo y honesto si no se va a integrar.

Recomendación: **(B) a corto plazo**, **(A)** solo si se aborda el rediseño de bus/DMA descrito en 6.4.

**Código refactorizado (opción B, corrección documental):**
```md
<!-- src/iflow/README.md — sección Componentes -->
- | `VmDmaController` | Transferencias de memoria validadas ... |
+ (eliminar la fila; el modelo de E/S vigente es SoftMMU)
```
```md
<!-- docs/workdir/PLAN.md -->
- **Corrección:** ... **sí se usa** por el emulador ...
+ **Corrección de la corrección:** `VmDmaController` NO está referenciado por
+ ninguna ruta de producción (HECHO, IFLOW-AUD-019). Se elimina el 2026-10-17.
```

---

### [IFLOW-AUD-020]

**Tipo:** Defecto de diseño (feature de I/O inerte)
**Severidad:** Media
**Confianza:** Confirmado (HECHO)
**Ubicación:** `vm/memory/VmMemoryMap.java:68` (`OUTPUT_BASE=0x8000`); `vm/engine/VmPolicyEngine.setupProgramMappings` (líneas 280-281, mapeo R/W); `vm/execution/ExecutionContext.initialize` (líneas 129-135, limpieza).

**Descripción:** El buffer de salida (`0x8000`) se mapea R/W y se pone a cero en cada ejecución, pero **ninguna ruta de producción lo lee**. Los resultados de la VM salen exclusivamente por `ModificationJournal` → `PolicyResultValidator`. No existe un mecanismo que extraiga `0x8000` de vuelta al host análogo a `prepareInput`.

**Causa:** la «E/S» de este emulador es asimétrica: hay una entrada materializada por el host, pero no hay una salida consumida por el host; el canal de resultados es el journal.

**Impacto funcional:** un programa que escriba resultados en `0x8000` (por ejemplo, para devolver una cadena transformada) **verá esos bytes ignorados**. La única forma de modificar el mensaje es por servicios (`PROPERTY_WRITE`→journal), lo que limita la expresividad del modelo de memoria.

**Impacto en rendimiento/escalabilidad:** bajo, pero se paga la limpieza de 4096 bytes por mensaje por una página inútil.

**Condiciones para reproducir:** programa que escribe en `0x8000` y `HALT`; inspeccionar `RouteOutcome`: el mensaje no refleja la escritura.

**Evidencia (HECHO):** `grep -rn "OUTPUT_BASE\|outputBufferVAddr"` en `src/iflow/src/main` solo aparece en `setupProgramMappings`, `ExecutionContext` (constantes/getter) y tests; ninguna lectura en producción.

**Solución sugerida:** o (a) definir y consumir el contrato del buffer de salida (el engine lee `0x8000` tras la ejecución y lo incorpora al resultado, p. ej. como texto), o (b) dejar de mapearlo/limpiarlo y documentar que la salida es el journal. La opción (a) es la que da sentido a la página 8.

**Código refactorizado (opción a, esquema):**
```java
// Tras interpreter.execute(ctx), antes del validador:
byte[] out = ctx.softMMU().getRegion(ctx.outputBufferVAddr(), ctx.ioBufferSize());
int len = 0; while (len < out.length && out[len] != 0) len++;
if (len > 0) {
    ctx.journal().record(new ModificationJournal.Entry(
        ModificationJournal.OpType.SET_TEXT,
        new String(out, 0, len, StandardCharsets.UTF_8), "output-buffer"));
}
```

---

### [IFLOW-AUD-021]

**Tipo:** Contradicción spec↔implementación (materialización de entrada)
**Severidad:** Media
**Confianza:** Confirmado (HECHO)
**Ubicación:** `vm/engine/VmPolicyEngine.prepareInput` (líneas 284-325); spec `docs/prompts/iflow/implement.md:564-566` y `design.md:164-168`.

**Descripción:** La spec dice literalmente «No hay regiones, no hay handles, **no hay búferes de entrada aislados**. El programa interactúa directamente con la memoria plana» y «No debe ser obligatorio copiar el objeto `Message` completo… antes de ejecutar cada programa». En cambio, `prepareInput` **copia** texto, canal, dirección, emisor y destinatario al buffer `0x7000` (con terminadores NUL) en **cada** mensaje, mediante `writeField`. Es una materialización *a priori* de los datos del mensaje, exactamente lo que la spec desaconseja.

**Causa:** `prepareInput` es anterior al puerto `HostContext`; cuando se añadió el puerto (AUD-016), las propiedades pasaron a consultarse bajo demanda por `HOST`, pero el framing de `0x7000` se mantuvo por compatibilidad con el firmware/`MessageHostContext`.

**Impacto funcional:** duplica la fuente de verdad: los mismos datos están en el buffer (para los servicios de buffer) y en el `HostContext` (para los servicios de propiedad). Un cambio debe mantener ambos. Además fija un layout fuera de banda que el programa debe conocer, contradiciendo la genericidad perseguida.

**Impacto en rendimiento/escalabilidad:** se copian y serializan los datos del mensaje por cada ruta (coste O(bytes)); evitable si solo se consulta lo necesario por `HOST`.

**Condiciones para reproducir:** inspeccionar `0x7000` tras `route()` con cualquier mensaje.

**Evidencia (HECHO):** código de `prepareInput`/`writeField`; texto de la spec citado.

**Solución sugerida:** alinear spec e implementación. O bien (a) eliminar el framing y hacer que los servicios lean siempre de `HostContext` (lo que exige adaptar los servicios de buffer que hoy leen `ctx.softMMU()`), o (b) documentar `0x7000` como contrato estable del engine y ajustar la spec. Recomiendo (b) a corto plazo por coste/riesgo, con la nota de que los servicios de *buffer* deben convivir con los de *propiedad*.

---

### [IFLOW-AUD-022]

**Tipo:** Defecto de diseño (encapsulación/fuga de representación + API duplicada)
**Severidad:** Media-Baja
**Confianza:** Confirmado (HECHO)
**Ubicación:** `vm/memory/SoftMMU.java -> getPhysicalMemory` (líneas 213-216), `-> getPageTableDirect` (59), `-> getPageTableForInspection` (209), `-> getPageTableForMutation` (219), `-> getPageTable` (39).

**Descripción:** `getPhysicalMemory()` devuelve el **mismo** `byte[]` interno (mutable), lo que permite a cualquier llamante mutar memoria saltándose permisos y bounds. Además hay **cuatro** accesores que devuelven la misma `PageTable`, y los tres «auxiliares» **no se usan en ninguna parte del repositorio**.

**Causa:** residuo de una API preparada para «acceso directo del intérprete» que finalmente no se usó (el intérprete lee `codeCache`).

**Impacto funcional:** hoy nulo (nadie lo llama), pero es una **fuga de encapsulación latente**: el primer consumidor que use `getPhysicalMemory()` romperá el modelo de protección sin darse cuenta.

**Impacto en rendimiento/escalabilidad:** ninguno inherente; el peligro es de corrección/seguridad futura.

**Condiciones para reproducir:** `grep -rn "getPhysicalMemory\|getPageTableDirect\|getPageTableForInspection\|getPageTableForMutation" --include=*.java src` → solo definiciones.

**Evidencia (HECHO):** el `grep` anterior.

**Solución sugerida:** eliminar `getPhysicalMemory()`, `getPageTableDirect`, `getPageTableForInspection` y `getPageTableForMutation` (dejar `getPageTable()` como única vía); si en el futuro se necesita acceso rápido, exponer un `MemoryPort`/bus explícito con métodos acotados y comprobados.

**Código refactorizado (esquema):**
```java
// SoftMMU.java — mantener solo:
public PageTable getPageTable() { return pageTable; }
// y un puerto de memoria explícito si hiciera falta:
public interface MemoryPort {
    int readByte(int vaddr);
    void writeByte(int vaddr, int value);
    void readBlock(int vaddr, byte[] dst, int off, int len);
    void writeBlock(int vaddr, byte[] src, int off, int len);
}
```

---

### [IFLOW-AUD-023]

**Tipo:** Defecto de diseño / Seguridad (protección de ejecución nominal)
**Severidad:** Media
**Confianza:** Confirmado (HECHO) para el bypass; explotación = INFERENCIA
**Ubicación:** `vm/execution/VmInterpreter.java -> codeCache` (líneas 31, 37, 69-77); `vm/memory/SoftMMU.java -> fetchByte/fetchWord` (180-192).

**Descripción:** El intérprete decodifica desde `codeCache = program.code()` (una copia del array Java del programa), **no** desde la memoria paginada. Por tanto el permiso `EXECUTE` de las páginas de código —que sí se comprueba en `SoftMMU.fetchByte/fetchWord`— **nunca se ejerce durante la ejecución**. Los métodos `fetch*` son, en la práctica, código de test. Además, como el mapeo es identidad y la página de código es R/X (sin W), un programa no puede autoparchear el `physicalMemory`… pero aunque pudiera, el fetch lo ignoraría.

**Causa:** optimización (evitar traducción por instrucción) sin mantener la semántica de protección prometida; los `fetch*` se corrigieron (AUD-007) pero el intérprete nunca se cambió para usarlos.

**Impacto funcional:** la separación R/X del código es decorativa; la protección real se limita a `IP < codeSize` y a `checkGPR`.

**Impacto en rendimiento/escalabilidad:** el `codeCache` es rápido (bien); rutear el fetch por la MMU tendría coste, mitigable con la misma caché pero validando la página al entrar a un `ip` nuevo.

**Condiciones para reproducir:** mapear la página de código sin EXECUTE (solo R/W) y ejecutar: el intérprete corre igual (los tests `fetchByteWithoutExecuteThrows` prueban el método `fetchByte`, no la ejecución).

**Evidencia (HECHO):** líneas citadas; `grep` de `fetchByte/fetchWord` solo en `SoftMMU` y tests.

**Solución sugerida:** usar `SoftMMU.fetchByte/fetchWord` en el bucle (validando la página al cambiar de `ip`), o documentar explícitamente que el fetch es de una «ROM inmutable en heap» y que la página de código no tiene por qué existir. Para el objetivo HDL, lo primero es lo correcto.

**Código refactorizado (esquema):**
```java
// VmInterpreter.execute(), en lugar de codeCache[ip]:
int opcode = softMMU.fetchByte(ip);            // valida EXECUTE + bounds
// y para operandos multi-byte, fetchByte(ip+k) o fetchWord(ip+k).
// Optimización: cachear la página de código validada mientras ip permanezca en ella.
```

---

### [IFLOW-AUD-024]

**Tipo:** Deuda técnica (estado duplicado y muerto)
**Severidad:** Baja
**Confianza:** Confirmado (HECHO)
**Ubicación:** `vm/execution/ExecutionContext.java -> pageTable` (líneas 60-61), `-> pageTable()` (260), `-> setPageTable` (262-264).

**Descripción:** `ExecutionContext` mantiene un campo `pageTable` «para esta ejecución», pero en producción **nunca se asigna** (siempre `null`); el `SoftMMU` clonado es quien tiene la tabla viva. `setPageTable` solo se llama desde tests. Existen por tanto **dos representaciones** de la tabla de páginas, una muerta.

**Causa:** el `SoftMMU` absorbió la propiedad de la tabla; el campo del contexto quedó como residuo.

**Impacto funcional:** ninguno hoy; confunde (un lector puede creer que el contexto posee la tabla) y arriesga una futura desincronización.

**Impacto en rendimiento/escalabilidad:** ninguno.

**Condiciones para reproducir:** `grep -rn "setPageTable\|\.pageTable()" src/iflow/src/main` → vacío salvo el propio contexto.

**Evidencia (HECHO):** `setPageTable` solo en `ExecutionContextTest` y `FirmwareIntegrationTest`; `pageTable` nunca asignado en `src/main`.

**Solución sugerida:** eliminar `pageTable`/`pageTable()`/`setPageTable` del contexto y exponer la tabla vía `ctx.softMMU().getPageTable()` en los tests.

---

### [IFLOW-AUD-025]

**Tipo:** Deuda técnica (deriva documental + feature no implementada)
**Severidad:** Baja
**Confianza:** Confirmado (HECHO)
**Ubicación:** `vm/memory/VmMemoryMap.java` (líneas 22, 553 spec); `src/iflow/README.md:38`; `docs/prompts/iflow/implement.md:553,576`; `HostServiceRegistry` (constantes de servicio).

**Descripción:** Varias afirmaciones no respaldadas por el código:
- **Páginas 9-15 «Estado persistente / datos privados»:** `setupProgramMappings` **no las mapea** y no existe la capability `memory.persist` que la spec menciona (`implement.md:576`). Son direcciones no accesibles.
- **`README.md:85` «12 IDs»:** el código define más de 15 IDs de servicio (`TRANSFORM_APPLY 0x0400`, `MESSAGE_CLONE/ID 0x0700/0x0701`…), aunque muchos no estén registrados.
- **`implement.md` ejemplos de ensamblador** siguen usando la convención v1 (`R2 = offset del buffer`, líneas 1237, 1331-1332) mientras el §11.1 documenta ABI v2 (`R2 = base`).
- **`VmDmaController`** listado como componente (cubierto por IFLOW-AUD-019).

**Causa:** la remediación de AUD-012 alineó el recuento de tests y quitó el DMA, pero luego `f9f96ad` re-añadió el DMA al README y la spec nunca se reconcilió con la ABI v2 ni con las páginas reservadas.

**Impacto funcional:** desorientación; un programa que intente usar 0x9000+ obtendrá `MEMORY_FAULT`.

**Impacto en rendimiento/escalabilidad:** la ausencia de estado persistente en memoria de VM limita políticas con memoria entre mensajes (el estado del host existe, pero no el de la VM).

**Condiciones para reproducir:** `STW` a `0x9000` → `MEMORY_FAULT`.

**Evidencia (HECHO):** `setupProgramMappings` sin páginas 9-15; `grep memory.persist` sin resultados; textos citados.

**Solución sugerida:** o implementar el mapeo de 9-15 gated por una capability `memory.persist` (y su persistencia entre ejecuciones, hoy inexistente), o actualizar README/spec para declarar esas páginas reservadas y sin uso.

---

### [IFLOW-AUD-026]

**Tipo:** Deuda técnica (campos y constantes muertos)
**Severidad:** Baja
**Confianza:** Confirmado (HECHO)
**Ubicación:** `vm/service/HostServiceRegistry.java -> placeholderResolver` (482-485), `-> translationDetector` (487-490); constantes `PROPERTY_EXISTS` (52), `PERMISSION_QUERY` (56), `ROUTE_REDIRECT` (60), `ROUTE_RATE_LIMIT` (61), `TRANSFORM_APPLY` (64), `CHANNEL_POLICY` (72), `MESSAGE_CLONE` (75), `MESSAGE_ID` (76).

**Descripción:** `placeholderResolver` y `translationDetector` son inyectables que **nunca se llaman** (ni siquiera en tests, a diferencia de lo que ocurría antes). Ocho constantes de servicio están **declaradas pero no registradas** (responden `UNSUPPORTED` por el diseño de AUD-006, lo cual es correcto, pero el nombre sugiere que existen).

**Causa:** evolución del ABI sin limpieza de símbolos.

**Impacto funcional:** ninguno (los IDs no registrados devuelven `UNSUPPORTED` correctamente). Confusión y ruido de API.

**Impacto en rendimiento/escalabilidad:** nulo.

**Condiciones para reproducir:** `grep -rn "placeholderResolver\|translationDetector"` → solo definiciones; `getService(0x0400)` → `null`.

**Evidencia (HECHO):** `grep`s citados; `builtinServicesRegistered` solo registra 7.

**Solución sugerida:** eliminar los dos inyectables muertos; agrupar las constantes no registradas bajo un comentario `// declared but not implemented` o eliminarlas si no hay plan de implementarlas.

---

### [IFLOW-AUD-027]

**Tipo:** Deuda técnica (duplicación)
**Severidad:** Baja
**Confianza:** Confirmado (HECHO)
**Ubicación:** `vm/VmConfigLoader.java -> createPassThroughProgram` (57-70) + stubs internos `Opcode` (73-77) y `HostServiceRegistry` (79-82); `vm/engine/VmPolicyEngine.java -> createPassThroughProgram` (126-140).

**Descripción:** El bytecode del programa pass-through (`LI R0,0x0300; LI R1,6; HOST; HALT` + `capabilities={"routing.decide"}`) está **duplicado** literalmente en dos clases, y `VmConfigLoader` contiene además copias inline de las constantes `Opcode.LI/HOST/HALT` y `HostServiceRegistry.ROUTE_DECISION/DECISION_CONTINUE` «para evitar dependencia circular».

**Causa:** dependencia circular evitada con copias en lugar de extraer el programa a una constante compartida.

**Impacto funcional:** riesgo de divergencia (si cambia el ABI, hay que tocar dos sitios).

**Impacto en rendimiento/escalabilidad:** nulo.

**Condiciones para reproducir:** comparar ambas constantes.

**Evidencia (HECHO):** los dos métodos citados, byte a byte iguales.

**Solución sugerida:** extraer a `PassThroughProgram.INSTANCE` (o a `ProgramImage` estático) y que ambos lo referencien; eliminar los stubs internos.

---

### [IFLOW-AUD-028]

**Tipo:** Bug funcional (semántica del puerto host)
**Severidad:** Media
**Confianza:** Confirmado (HECHO)
**Ubicación:** `vm/engine/VmPolicyEngine.java -> MessageHostContext.writeProperty` (líneas 367-375); consumido por `vm/service/HostServiceRegistry.doPropertyWrite` (315-334).

**Descripción:** `writeProperty(scope, propId, value)` **ignora `scope` y `propId`** y siempre registra un `ModificationJournal.Entry(SET_TEXT, value, "propWrite:"+propId)`. Por tanto `PROPERTY_WRITE` (0x0101) solo puede reescribir el **texto** del mensaje; escribir `PROP_CHANNEL`, `PROP_LANG_*`, `PROP_COLOR_MODE`, etc. cambia el texto, no la propiedad pedida.

**Causa:** la implementación del puerto se hizo mínima (solo texto), pero el ABI expone `(scope << 8) | propId`.

**Impacto funcional:** una política que quiera cambiar el canal o el idioma vía `PROPERTY_WRITE` no puede; el `ModificationJournal` ya tiene `SET_CHANNEL`/`SET_LANG_*`/`SET_COLOR_MODE`, así que el mapeo existe pero no se usa. Es una limitación funcional real del contrato de servicios.

**Impacto en rendimiento/escalabilidad:** nulo.

**Condiciones para reproducir:** programa que hace `PROPERTY_WRITE` con `scope=CHANNEL, propId=PROP_CHAN_NAME`; el mensaje cambia de texto, no de canal.

**Evidencia (HECHO):** `MessageHostContext.writeProperty` sin `switch (scope/propId)`; `ModificationJournal.OpType` incluye los tipos necesarios.

**Solución sugerida:** mapear `(scope, propId)` a la `OpType` correcta:
```java
@Override
public void writeProperty(int scope, int propId, String value) {
    if (value == null) return;
    ModificationJournal.OpType op = switch (scope) {
        case HostServiceRegistry.SCOPE_CHANNEL -> HostServiceRegistry.PROP_CHAN_NAME == propId
            ? ModificationJournal.OpType.SET_CHANNEL : null;
        case HostServiceRegistry.SCOPE_MESSAGE -> switch (propId) {
            case HostServiceRegistry.PROP_CONTENT      -> ModificationJournal.OpType.SET_TEXT;
            case HostServiceRegistry.PROP_LANG_SOURCE  -> ModificationJournal.OpType.SET_LANG_SOURCE;
            case HostServiceRegistry.PROP_LANG_TARGET  -> ModificationJournal.OpType.SET_LANG_TARGET;
            case HostServiceRegistry.PROP_COLOR_MODE   -> ModificationJournal.OpType.SET_COLOR_MODE;
            default -> null;
        };
        default -> null;
    };
    if (op == null) return; // o ServiceResult.unsupported a nivel de servicio
    ctx.journal().record(new ModificationJournal.Entry(op, value, "propWrite:" + propId));
}
```

---

### [IFLOW-AUD-029]

**Tipo:** Defecto de rendimiento (asignaciones en el camino caliente)
**Severidad:** Media
**Confianza:** HECHO estructural; HIPÓTESIS en magnitud
**Ubicación:** `vm/engine/VmPolicyEngine.route` (líneas 173, 189); `vm/execution/ExecutionContext.initialize` (129-135); `vm/isa/ProgramImage.code` (68).

**Descripción:** Por **cada** `route()`: (1) `new SoftMMU(loaded.softMMU)` clona 64 KB (`physicalMemory.clone()`) + copia la tabla; (2) `new VmInterpreter(...)` clona el código (`program.code()` → `code.clone()`, hasta 8 KB); (3) `initialize()` ejecuta **8192** `softMMU.writeByte` (bounds+permiso por byte) para limpiar I/O. El pool evita crear el `ExecutionContext`, pero **no** evita (1)(2)(3).

**Causa:** el intérprete y el clon de MMU se tratan como por-ejecución cuando el programa es inmutable y el estado mutable ya se resetea.

**Impacto funcional:** ninguno (correcto).

**Impacto en rendimiento/escalabilidad:** alto en el camino caliente; **HIPÓTESIS**: a miles de msg/s dominaría CPUs y GC.

**Condiciones para reproducir:** medir con `MessageProcessingBenchmark` (existe en `src/loadtest`); no ejecutado aquí.

**Evidencia (HECHO):** líneas citadas.

**Solución sugerida:** reutilizar un `VmInterpreter` por programa (es inmutable; solo apunta a `ctx` en `execute`), limpiar I/O con un `arraycopy` de ceros o `Arrays.fill` sobre el rango físico (una sola operación), y considerar MMU compartida de solo lectura + COW para datos.

**Código refactorizado (esquema):**
```java
// limpieza de I/O en initialize(): un solo Arrays.fill por buffer en vez de 8192 writeByte.
// (Requiere un método interno acotado en SoftMMU; NO usar un byte[] expuesto mutable al exterior.)
if (softMMU != null) {
    softMMU.clearRange(INPUT_BUFFER_VADDR,  IO_BUFFER_SIZE);  // traduce una vez y rellena el tramo físico
    softMMU.clearRange(OUTPUT_BUFFER_VADDR, IO_BUFFER_SIZE);  // p. ej. physicalMemory[base..base+len] = 0
}
```

---

### [IFLOW-AUD-030]

**Tipo:** Defecto de diseño (modelo de privilegios no aplicado)
**Severidad:** Baja
**Confianza:** Confirmado (HECHO)
**Ubicación:** `vm/memory/PageEntry.java -> user` (27, 33, 60-62), `-> mappedKernel` (44-46); `vm/memory/PageTable.java -> mapPageKernel` (46-52); `vm/memory/SoftMMU.java -> mapPageKernel` (49-51).

**Descripción:** `PageEntry` distingue páginas *user* y *kernel* (`mapped` vs `mappedKernel`) y `PageTable`/`SoftMMU` exponen `mapPageKernel`, pero **ninguna traducción consulta `isUser()`**: `translate` solo comprueba validez y permisos. El modelo de anillos de la spec §14 (ring 0 firmware/servicios, ring 3 programas) **no está implementado**.

**Causa:** se dejó el bit en la estructura pero no la comprobación; el firmware y el usuario comparten las mismas páginas R/X.

**Impacto funcional:** un programa de usuario con permiso R/W sobre una página «kernel» podría escribirla (hoy ninguna se mapea como kernel, así que el efecto es latente).

**Impacto en rendimiento/escalabilidad:** nulo.

**Condiciones para reproducir:** mapear una página con `mapPageKernel(..., PERM_READ|PERM_WRITE)`, intentar escribir desde un programa: `translate` no la deniega.

**Evidencia (HECHO):** `grep -rn "isUser\|mapPageKernel"` → sin usos de comprobación en `translate`.

**Solución sugerida:** o bien aplicar el modo (una bandera `kernelMode` en el contexto que `translate` consulte; el firmware corre en kernel, el usuario en user), o bien eliminar `user`/`mapPageKernel` para no prometer un modelo inexistente.

---

## 20. Defectos (síntesis)

- **Diseño/E-S:** `VmDmaController` muerto presentado como vivo (IFLOW-AUD-019); buffer de salida inerte (IFLOW-AUD-020); sin bus ni encapsulación de memoria (IFLOW-AUD-022).
- **Diseño/ejecución:** fetch fuera de la MMU (IFLOW-AUD-023); privilegios no aplicados (IFLOW-AUD-030).
- **Contrato:** `PROPERTY_WRITE` no puede escribir propiedades distintas de texto (IFLOW-AUD-028); spec↔impl sobre materialización de entrada (IFLOW-AUD-021).
- **Estado/API muertos:** `ExecutionContext.pageTable`, accesores duplicados de `PageTable`, campos inyectables y constantes sin registrar (IFLOW-AUD-024, -022, -026).
- **Rendimiento:** clon de MMU/código + limpieza byte a byte por mensaje (IFLOW-AUD-029).

---

## 21. Deuda técnica

- Código muerto en producción: `VmDmaController` (+su test), `SoftMMU.getPhysicalMemory/getPageTableDirect/ForInspection/ForMutation`, `ExecutionContext.pageTable`, `HostServiceRegistry.placeholderResolver/translationDetector`, 8 constantes de servicio sin registrar.
- Duplicación: `createPassThroughProgram` y constantes inline (IFLOW-AUD-027).
- Deriva documental: páginas 9-15, «12 IDs», ejemplos de la spec con ABI v1, DMA en README/PLAN (IFLOW-AUD-025, -019).
- `-d` sin sección de debug en el binario (documentado en el README del compilador).

---

## 22. Mejoras opcionales (no defectos)

- Unificar los cuatro accesores de `PageTable` de `SoftMMU`.
- Añadir `@Nullable`/`Optional` donde se devuelve `null` (`readProperty`, `resolveChannel`).
- Reutilizar el `VmInterpreter` por programa.
- Definir un `MemoryPort`/bus explícito como preparación para el objetivo HDL.

---

## 23. Roadmap priorizado

### P0 — Crítico

| # | Problema | Beneficio | Esfuerzo | Dependencia | Prioridad |
|---|---|---|---|---|---|
| IFLOW-AUD-019 | DMA muerto presentado como vivo (README/PLAN/commit) | Honestidad y coherencia arquitectónica | S (documental) / M (integrar) | Decisión de diseño | Máxima |
| IFLOW-AUD-028 | `PROPERTY_WRITE` solo escribe texto | Habilita policies que cambian canal/idioma/color | S | Journal (ya tiene los tipos) | Máxima |

### P1 — Alto

| # | Problema | Beneficio | Esfuerzo | Dependencia | Prioridad |
|---|---|---|---|---|---|
| IFLOW-AUD-029 | Clon MMU + clon código + 8192 `writeByte`/msg | Rendimiento del camino caliente | S/M | — | Alta |
| IFLOW-AUD-023 | Fetch por MMU (EXECUTE efectivo) | Seguridad de ejecución; paso a HDL | M | `fetch*` listos | Alta |
| IFLOW-AUD-022 | Fuga de `byte[]` + API duplicada | Encapsulación; base para bus | S | — | Media-alta |
| IFLOW-AUD-030 | Modelo de privilegios no aplicado | Seguridad/ring 0-3 | M | Decisión de modelo | Media-alta |

### P2 — Medio

| # | Problema | Beneficio | Esfuerzo | Dependencia | Prioridad |
|---|---|---|---|---|---|
| IFLOW-AUD-020 | Buffer de salida inerte | E/S de salida útil | S/M | Contrato de salida | Media |
| IFLOW-AUD-021 | Spec↔impl de materialización de entrada | Coherencia | S (documentar) / M (eliminar) | Servicios de buffer | Media |
| IFLOW-AUD-024 | `ExecutionContext.pageTable` muerto | Claridad | S | — | Media |
| IFLOW-AUD-026 | Campos/constantes muertos | API honesta | S | — | Media |
| IFLOW-AUD-025 | Deriva documental (páginas, IDs, ABI de ejemplos) | Onboarding | S | — | Media |

### P3 — Bajo

| # | Problema | Beneficio | Esfuerzo | Dependencia | Prioridad |
|---|---|---|---|---|---|
| IFLOW-AUD-027 | Duplicación del pass-through | DRY | S | — | Baja |

---

## 24. Matriz de puntuaciones

| Área | Puntuación /10 | Estado | Principales problemas |
|---|---|---|---|
| Código | 7/10 | Bueno | API/estado muertos, duplicaciones |
| Arquitectura | 6/10 | Aceptable | Sin bus; DMA no integrado; engine sobrecargado |
| Clean Architecture | 6/10 | Aceptable | Puerto `HostContext` correcto; sin puerto de memoria |
| Hexagonal | 6/10 | Aceptable | Entrada formal; salida formal; falta puerto de memoria/bus |
| Modularidad | 7/10 | Buena | `memory` con clase muerta no marcada |
| Separación de responsabilidades | 6/10 | Aceptable | `VmPolicyEngine`/`Assembler` multi-responsabilidad |
| Legibilidad | 7/10 | Buena | Javadoc de calidad; ruido por código muerto |
| Features | 7/10 | Bueno | Loader/servicios/firmware reales; `PROPERTY_WRITE` limitado |
| Personalización | 6/10 | Aceptable | Capabilities intersecadas (bien); sin `memory.persist` |
| Seguridad | 5/10 | Débil | Fetch sin EXECUTE efectivo; sin anillos |
| Rendimiento | 5/10 | Débil | Clon MMU/código + limpieza byte a byte por mensaje |
| Escalabilidad | 5/10 | Débil | Coste por mensaje; sin persistencia en memoria de VM |
| Concurrencia | 7/10 | Buena | Pool acotado y contadores consistentes |
| Testing | 7/10 | Bueno | 288 tests; cubre un DMA no usado; huecos en salida/privilegios |
| Mantenibilidad | 6/10 | Aceptable | Buena base; deriva documental y código muerto |
| Documentación | 5/10 | Parcial | README/PLAN sobre el DMA; páginas 9-15; IDs |

---

## 25. Potencial

- **Potencial teórico (INFERENCIA):** alto. Una VM con ABI syscall, capabilities, memoria paginada, DMA/bus y firmware con wrappers es una base capaz de sostener políticas sandboxeadas y, con los cambios mínimos de 6.4, un diseño **mapeable a HDL**.
- **Potencial arquitectónico (INFERENCIA):** alto. La forma «CPU + ROM/RAM + MMU + IO + firmware» ya está; solo falta el **bus/DMA** y hacer efectiva la MMU y los privilegios.
- **Potencial alcanzado (INFERENCIA):** **~62 %**. El camino crítico ya funciona (loader real, servicios cableados, firmware real, pool operativo), pero la capa de E/S/bus y las protecciones efectivas están incompletas o son nominales.

---

## 26. Conclusión final

1. **¿Qué tan bueno es realmente?** Bueno como arquitectura de máquina virtual embebida y ahora **funcional de extremo a extremo** (la política compilada se ejecuta de verdad). No es aún un «procesador mapeable a HDL» porque su E/S (DMA/bus) y su protección de ejecución son nominales.
2. **¿Qué está excepcionalmente bien diseñado?** `VmMemoryMap` como fuente única; el ABI `HOST` v2 (documentado y coherente con `ServiceResult`); la inmutabilidad del `ProgramImage` con SHA-256; la pre-validación de rangos en `SoftMMU.writeBlock`; el puerto `HostContext`; y el pool con `journal.reset()`.
3. **¿Qué es mediocre?** La capa de E/S/bus (DMA muerto, buffer de salida inerte), la protección de ejecución (fetch por `codeCache`), y la coherencia README/PLAN↔código (DMA, páginas 9-15, IDs).
4. **¿Peores defectos?** IFLOW-AUD-019 (DMA muerto vendido como vivo) e IFLOW-AUD-028 (`PROPERTY_WRITE` incapaz de escribir propiedades distintas de texto), seguidos de IFLOW-AUD-023 (fetch sin EXECUTE) e IFLOW-AUD-029 (asignaciones por mensaje).
5. **¿Mayor fortaleza arquitectónica?** La separación ISA/memoria/ejecución/servicios con mapa único y un puerto de host formal: el diseño no impide integrar bus/DMA ni hacer efectiva la MMU.
6. **¿Mayor riesgo futuro?** Construir sobre lo documentado (DMA, estado persistente en 0x9000+) sin que exista; y creer que la protección R/X del código es real.
7. **¿Cuán mantenible?** Media-buena: código legible y modular, con deuda localizada (código muerto + deriva documental).
8. **¿Cuán extensible?** Alta: nuevo `serviceId` (el opcode `HOST` no cambia), nuevo opcode, `setProgram` atómico, puerto de host.
9. **¿Preparado para múltiples plataformas?** El núcleo es agnóstico (bytecode + servicios inyectables): sí. El `HostContext` formaliza el adaptador por plataforma.
10. **¿Preparado para una red Minecraft grande?** **Todavía no.** El clon de 64 KB + clon de código + 8192 `writeByte` por mensaje, y la ausencia de estado persistente efectivo, son lastres.
11. **¿Qué cambiaría para escala network-level?** (a) reutilizar el intérprete por programa; (b) limpiar I/O en un solo `arraycopy`/`fill`; (c) MMU compartida de solo lectura + COW; (d) estado persistente real (páginas 9-15 o servicio de estado robusto); (e) eliminar el `UUID` por ruta.
12. **¿Qué NO debería tocarse?** `VmMemoryMap`, el ABI `HOST` v2 y `ServiceResult`, la verificación de hash del parser, la pre-validación de `writeBlock` y el puerto `HostContext`. Están correctamente diseñados.
13. **¿Qué debería hacerse primero?** Resolver IFLOW-AUD-019 (decidir integrar o eliminar el DMA y corregir la documentación) e IFLOW-AUD-028 (mapear `scope/propId` en `writeProperty`). Después IFLOW-AUD-029 y IFLOW-AUD-023.
14. **¿Porcentaje técnicamente maduro?** **~62 %** del alcance auditado (cimientos y camino crítico maduros; capa de E/S/bus y protecciones efectivas incompletas).

---

## Apéndice A — Reproducción de las verificaciones

```bash
# Toolchain / tests
JAVA_HOME=/opt/javac/x64/21 ./gradlew :src:iflow:test --offline --no-daemon --rerun-tasks
#   -> BUILD SUCCESSFUL ; test-results: tests=288 failures=0 errors=0 skipped=0 (21 ficheros XML)

cd tools/iflow-compiler
NODE_OPTIONS=--jitless npm run build        # tsc OK
node test/validation/run.js                 # 13/13 cases passed

# Determinismo del ensamblador (byte-idéntico)
node dist/cli.js test/firmware.flowasm  -o /tmp/fw.flow   && cmp /tmp/fw.flow test/firmware.flow
node dist/cli.js test/simple.flowasm    -o /tmp/s.flow    && cmp /tmp/s.flow  test/simple.flow
node dist/cli.js test/main_only.flowasm -o /tmp/m.flow    && cmp /tmp/m.flow  test/main_only.flow

# IFLOW-AUD-019: VmDmaController es código muerto (solo su test lo referencia)
grep -rn "VmDmaController" --include=*.java . | grep -v /build/
#   -> solo VmDmaController.java y VmDmaControllerTest.java
grep -rn "new VmDmaController" src/iflow/src/main          # -> (vacío)

# IFLOW-AUD-020: el buffer de salida nunca se lee en producción
grep -rn "OUTPUT_BASE\|outputBufferVAddr" src/iflow/src/main   # -> setup/init/getter, sin lecturas

# IFLOW-AUD-022: fugas y API duplicada sin uso
grep -rn "getPhysicalMemory\|getPageTableDirect\|getPageTableForInspection\|getPageTableForMutation" --include=*.java src
#   -> solo definiciones en SoftMMU.java

# IFLOW-AUD-024: pageTable del contexto solo lo tocan los tests
grep -rn "setPageTable\|\.pageTable()" --include=*.java src
#   -> solo ExecutionContextTest y FirmwareIntegrationTest

# IFLOW-AUD-026: campos muertos y constantes no registradas
grep -rn "placeholderResolver\|translationDetector" --include=*.java src    # -> solo definiciones
```

*Fin del informe.*
