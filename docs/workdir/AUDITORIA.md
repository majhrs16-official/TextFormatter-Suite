# Auditoría técnica — iFlow (VM `src/iflow/` + compilador `tools/iflow-compiler/`)

> **Documento de auditoría de solo lectura.** No se modificó código, artefactos ni
> configuración del repositorio. Los únicos archivos escritos durante esta auditoría fueron
> este informe y los artefactos regenerados por Gradle/Node en `build/` (no versionados).
>
> **Esquema de numeración (declaración obligatoria):** este es el **tercer** informe sobre el
> alcance. El primero usó `IFLOW-AUD-001…018` y el segundo `IFLOW-AUD-019…030`. Para no
> colisionar con esos IDs —que siguen referenciándose en `docs/workdir/PLAN.md` y en el
> código (`IFLOW-AUD-023`, `IFLOW-AUD-027`, `IFLOW-AUD-028`, `IFLOW-AUD-029`)— **los hallazgos
> de este informe continúan la serie a partir de `IFLOW-AUD-031`**.
>
> **Etiquetas de evidencia:** **HECHO** (demostrado por código/config/tests/binarios),
> **INFERENCIA** (conclusión razonable a partir del código) y **HIPÓTESIS** (requiere
> benchmark o ejecución específica para confirmarse). Se aplican a cada afirmación relevante.

---

## 1. Resumen ejecutivo

iFlow es un **intérprete de 16 bits con memoria plana de 64 KB, paginación por software
(SoftMMU de 4 KB), bus de datos explícito, DMA, periféricos MMIO (pantalla, NVRAM, PIC) y un
ABI `HOST` v2 tipo syscall**, más un **ensamblador TypeScript de dos pasadas** que genera una
ROM de firmware real. La política que consume el servidor (`Router`) es un `.flow` binario.

**Estado real, con evidencia:**

- El **formato binario es correcto y está alineado entre compilador y runtime**: ensamblé
  `examples/policy.flowasm` (4209 B), lo parseé a mano y verifiqué **campo a campo** que la
  cabecera, las constantes, las capabilities y el **SHA-256** coinciden con lo que valida
  `ProgramImageParser`. El compilador es **determinista** (los 5 `.flow` versionados se
  reensamblan byte-idénticos). **HECHO.**
- Las **pruebas pasan**: `383/383` en `src/iflow` (27 ficheros) y `13/13` validaciones del
  compilador. Cobertura jacoco: **84 % instrucciones, 71 % líneas, 70 % ramas, 91 % métodos,
  97 % clases**. **HECHO.**
- Los defectos centrales del informe anterior (DMA muerto, fetch que evadía la MMU, privilegio
  de página no aplicado, `writeProperty` incapaz de mapear `scope/propId`, clon por mensaje)
  están **corregidos** y ahora tienen test y comentario que los referencian. Ver §17.0.

**Los riesgos que permanecen** son de **aislamiento de E/S** y de **semántica de fallo**:

1. **La MMIO de dispositivos no comprueba ni permisos de página ni privilegio**
   (`IFLOW-AUD-031`, Media-Alta): el apéndice de E/S (`0x0F00–0x0FFF`) vive dentro de la página
   de BIOS, marcada R/X, y el bus lo enruta al dispositivo *antes* de la MMU. Cualquier `.flow`
   puede escribir la NVRAM (bandera de arranque) y los registros del PIC. El modelo «el dueño de
   la página decide» sí se aplica a la RAM, pero no a los periféricos.
2. **Las decisiones de ruta no válidas degradan a «entregar»** (`IFLOW-AUD-034`, Media): un
   código de decisión fuera de rango termina en `PolicyTarget.LOG`, cuyo `delivered()` es
   `true`. Los errores de *ejecución* sí caen en `REJECT` (fail-closed); los de *contenido de la
   decisión* no.
3. **Las lecturas descartan el privilegio** (`IFLOW-AUD-033`): el parámetro existe en la API y
   no se usa; es coherente con «páginas kernel legibles por el programa», así que no es una fuga,
   pero es una firma que promete algo que no hace.

**Valoración global:** cimientos sólidos y **camino crítico funcional de extremo a extremo**,
con una implementación claramente mejor que la que describe el informe anterior. La deuda que
queda es **acotada y localizada**: E/S sin control de acceso, *fail-open* puntual, documentación
de endianness incorrecta y algunos mecanismos nominales. **Madurez técnica estimada del alcance:
~7,5/10** (§18).

---

## 2. Alcance y limitaciones

### 2.1 Identificación del objeto auditado

| Dato | Valor |
|---|---|
| Repositorio | `/home/majhrs16/Documentos/textformatter-suite` (checkout local) |
| Rama | `main` |
| Commit auditado | `84491c2` (*docs(adr): registra el descarte del modelo «CPU física»*) |
| `git status` | Limpio antes y después de las verificaciones |
| Fecha/hora UTC | 2026-10-19T07:41Z – 2026-10-19T08:05Z |
| Toolchain | JDK 21.0.12 (`/opt/javac/x64/21`), Gradle 8.10 (wrapper, `--offline`), Node v24.15.0, `typescript` 5.x |
| Alcance | `src/iflow/`, `tools/iflow-compiler/`, sus tests y la documentación que los describe |
| Contexto (solo lectura) | `src/host` (`SuiteBootstrap`, `SuiteHost`, `VmConfigLoader`), `src/loadtest` |

### 2.2 Qué se leyó íntegro y qué no

**Leídos íntegros** (código de producción): `ProgramImage.java`, `ProgramImageParser.java`,
`Opcode.java`, `VmMemoryMap.java`, `SoftMMU.java`, `PageTable.java`, `VmBus.java`,
`HostServiceRegistry.java`, `PolicyResultValidator.java`, `RouteDecision.java`,
`VmConfigLoader.java`, `PassThroughProgram.java`.
**Leídos íntegros** (compilador): `types.ts`, `cli.ts`; de `assembler.ts` la generación de ROM,
el emisor de instrucciones, los dos serializadores, el cálculo de hash y la resolución de
`entryPoint`.
**Leídos parcialmente** (regiones relevantes + `grep` dirigido): `VmPolicyEngine.java`
(§134-333 y §420-549), `VmInterpreter.java` (bucle principal §57-140 y decodificación de
`LI`/saltos), `ExecutionContext.java` (§183-265 y consultas puntuales).

**No leídos íntegros:** `VmDmaController`, `PhysicalMemory`, `PageEntry`, `Privilege`,
`VmDisplay`, `VmNvram`, `VmPic`, `IrqTable`, `Router`, `RouteOutcome`, `channel/*`,
`target/*`, `ServiceResult`, `HostContext`, `VmException`, `RegisterFile`,
`ModificationJournal`, `ExecutionContextPool` y el resto de `assembler.ts`. Sobre ellos solo se
afirma lo que evidencia un `grep` o una llamada concreta.

### 2.3 Límites declarados

- **No ejecuté un arnés propio** que ensamble un `.flow` y lo ejecute en el JVM leyendo el
  resultado. La ejecución se verificó **con las suites existentes** (que incluyen pruebas
  end-to-end de firmware, BIOS e IRQ). El binario que ensamblé se validó **estáticamente**
  (campos + SHA-256), no ejecutándolo.
- **No medí rendimiento**: no hay JMH ni benchmark ejecutado. Todo juicio de coste es
  **HECHO estructural** (complejidad de las rutas) + **HIPÓTESIS cuantitativa**.
- **No ejecuté** el benchmark de carga ni una red Minecraft real.
- Las líneas citadas corresponden al commit auditado; si el fichero cambia, dejan de ser válidas.

---

## 3. Inventario y mapa arquitectónico

| Elemento | Medida |
|---|---|
| `src/iflow/src/main` | **35 clases Java, 6 937 líneas** |
| `src/iflow/src/test` | **27 ficheros de test, 383 tests** |
| `tools/iflow-compiler/src` | **1 798 líneas TS** (`assembler.ts` 1385, `types.ts` 289, `cli.ts` 115, `index.ts` 9) |
| `.flow` versionados | `test/{firmware,simple,main_only,irq,bios_screen}.flow` + `examples/policy.flowasm` |

**Dirección de dependencias (HECHO):** `src/iflow` depende de `core-api`, `textformatter` y
`logger` (`build.gradle`); no depende de `host` ni de `loadtest` (son consumidores). El
compilador TS **no depende de nada de Java**: solo comparte el contrato del binario, replicado
en `types.ts`. La única fuente de verdad en Java del mapa de memoria es `VmMemoryMap`, y el
parser la usa para acotar secciones (`ProgramImageParser.java:65-68,85-88`), de modo que el mapa
no puede derivar respecto al motor.

```
 .flowasm ──(iflow-as / tsc)──▶ .flow (header+código+constantes+caps+SHA-256)
                                    │
                    VmConfigLoader.loadProgram  ──(ProgramImageParser.parse)──▶ ProgramImage
                                    │
                          VmPolicyEngine.setProgram ─▶ setupProgramMappings(SoftMMU)
                                    │
   mensaje ──▶ route() ─▶ [MachineLock] ─▶ ExecutionContext (pool) ─▶ VmInterpreter
                                    │            │                        │
                                    │        HostContext            VmBus ─▶ SoftMMU ─▶ PhysicalMemory
                                    │     (propiedades/estado)      │  └─▶ VmDevice (display/NVRAM/PIC)
                                    └──▶ PolicyResultValidator ─▶ RouteOutcome ─▶ SuiteHost
```

---

## 4. Flujo completo compiler → runtime → host

1. **Compilación** (`cli.ts:56-74`): se lee el `.flowasm`, se ensambla (2 pasadas), se serializa
   y se escribe el `.flow`. La ROM de firmware se antepone por defecto
   (`assembler.ts:138-141`) y el `entryPoint` resultante es **`fw_reset`**, no el `main` del
   usuario (`assembler.ts:1313-1321`).
2. **Carga** (`VmConfigLoader.java:25-55`): se busca `policy.flow`; si existe, se parsea (que
   valida versiones, cotas de sección y SHA-256) y si falla **cae a pass-through** con un
   `warn`. Si no existe, `PassThroughProgram.INSTANCE`.
3. **Boot** (`VmPolicyEngine.setProgram` → `setupProgramMappings`, §269-349): el código se
   escribe por el bus **supervisor** y luego se **degrada a R/X** con `mprotect`; las constantes
   pasan a R; datos/pila/E-S/usuario se mapean R/W; acto seguido `freeze()` y `attach()` de los
   tres periféricos.
4. **Ejecución** (`route()`, §177-263): bajo `machineLock`, se toma un contexto del *pool*, se
   le instala la vista del mensaje (`MessageHostContext`), se serializa el mensaje en el buffer
   de entrada y se levanta la IRQ 0; corre `interpreter.execute(ctx)`.
5. **Resultado** (`PolicyResultValidator.validate`): si hubo error fatal → `REJECT`; si el
   journal cancela → `REJECT`/`DROP`; en otro caso se aplica el journal al mensaje y se
   **commitea**, produciendo un `RouteOutcome`.

**HECHO relevante de diseño:** existe **una sola máquina viva por programa**. `route()` no hace
*fork* de la memoria: el `SoftMMU` cargado es la memoria de la máquina y las escrituras del
programa persisten al siguiente mensaje (`VmPolicyEngine.java:193-198`, `VmMemoryMap.java:77-87`).
Esto convierte la «persistencia implícita» en una **feature real**, no documental: el
`ExecutionContext.initialize()` solo limpia los buffers de E/S (§218-226), nunca las páginas de
datos ni la RAM de usuario.

---

## 5. Especificación efectiva de la ISA

**Codificación (HECHO, `Opcode.instructionSize`):** la longitud la determina el opcode.

| Grupo | Mnemónicos | Codificación | Bytes |
|---|---|---|---|
| Immediate | `LI` | op + rd + imm16 | 4 |
| Registro-registro | `MOV`, `NOT`, `LDBI/LDWI`, `STBI/STWI`, `ADD/SUB/MUL/DIV/MOD`, `AND/OR/XOR`, `SHL/SHR/SAR`, `CMP/CMPU` | op + regs empaquetados | 2 |
| Memoria | `LDB/LDW/STB/STW` | op + rd + vaddr16 | 4 |
| Bloque | `COPY`, `MOVE` | op + src16 + dst16 + len16 + **1 B de relleno** | 8 |
| Salto/call | `JMP/JZ/JNZ/JC/JV/CALL` | op + offset16 | 3 |
| Servicio | `HOST` | op + relleno | 2 |
| Control | `RET`, `HALT` | op | 1 |
| Trap | `TRAP` | op + imm8 | 2 |

`COPY`/`MOVE` emiten **8 bytes** incluyendo un relleno explícito
(`assembler.ts:1024-1032`), que coincide exactamente con `Opcode.instructionSize` (§124). No
hay desalineación.

**Endianness (HECHO, y contradice la documentación):** los **operandos de instrucción** se
codifican **big-endian** — el emisor escribe `(imm >> 8)` primero (`assembler.ts:978-980`) y el
intérprete decodifica `(fetch(ip+2) << 8) | fetch(ip+3)`
(`VmInterpreter.java:154`). En cambio, las **palabras de datos** de la SoftMMU son
**little-endian** (`SoftMMU.java:150-168`), y las **constantes** del firmware se escriben
little-endian (`fw[off]=lo; fw[off+1]=hi`, `assembler.ts:583-584`). Los **campos de cabecera**
del `.flow` son **big-endian** (ver §7). Es un formato **mixto**; ver `IFLOW-AUD-039`.

**Semántica y casos límite (HECHO, verificados en el intérprete):**

| Caso | Comportamiento observado |
|---|---|
| Opcode inválido | `VmException.invalidOpcode` (`VmInterpreter.java:87-89`) |
| Instrucción truncada | `invalidOperand` si `ip + instrSize > codeSize` (`:91-93`) |
| `entryPoint` fuera de rango | `invalidJump` antes del bucle (`:64-66`) |
| IP fuera de rango en cada iteración | `invalidJump` (`:77-79`) |
| **Salto/PC que rebasa el final** | **Se detiene normalmente**: `if (newIp >= codeSize) break;` (`:135-138`) |
| Fuel agotado | `OUT_OF_FUEL` con IP (`ExecutionContext.java:241-244`) |
| Escribir página kernel desde el programa | `PRIVILEGE_FAULT` aunque el bit W esté puesto (`PageTable.java:166-171`) |

El caso «salto que rebasa el final se detiene en silencio» está **documentado como intencional**
(`:133-134`), así que no se registra como defecto, pero implica que un salto erróneo al final del
código **no** produce fallo: es un modo de terminación indistinguible del `HALT` legítimo.

---

## 6. Auditoría del compilador

- **Determinismo: HECHO.** Reensamblé los 5 `.flow` versionados y todos salieron
  **byte-idénticos** (Apéndice A). El SHA-256 se calcula sobre el prefijo y es estable.
- **Vacíos de validación: HECHO.** `npx tsc` sin errores; `node test/validation/run.js` →
  **13/13** (8 inválidos rechazados, 5 válidos aceptados, incl. indirectos, R7 como límite,
  immediatos extremos y precisión).
- **Directivas soportadas**: `.section/.seg`, `.entry`, `.origin/.org`, `.capability/.cap`,
  `.firmware`, `.align`, `.const`, `.equ/.set`, `.end` (`assembler.ts:644-722`).
- **`-d/--debug` es un no-op sobre el artefacto** (`IFLOW-AUD-037`): `generateDebugInfo()` se
  guarda en el objeto `ProgramImage` (`assembler.ts:1191,1218`) pero **ninguno** de los dos
  serializadores emite una sección de depuración (`:1234-1277` y `:1334-1373`). El `.flow`
  resultante es idéntico con y sin `-d`. El listado `-l` sí funciona (`cli.ts:76-78`).
- **Compatibilidad de versiones**: el parser exige `minAbiVersion == CURRENT_ABI_VERSION`
  (`ProgramImageParser.java:42-46`) pero acepta `isaVersion`/`minFirmwareVersion` menores con
  `>` (`:38-41,47-50`). Un `.flow` de ABI 1 se rechaza en bloque (`IFLOW-AUD-043`).

---

## 7. Auditoría del formato binario

Verificado de extremo a extremo (HECHO). Cabecera de 16 B, todo **big-endian**:

```
off  0  u8  formatVersion        (1)
off  1  u8  isaVersion           (2)
off  2  u8  minAbiVersion        (2)
off  3  u8  minFirmwareVersion   (1)
off  4  u16 entryPoint           BE
off  6  u16 codeLength           BE
off  8  u16 constantsLength      BE
off 10  u8  capabilitiesCount
off 11  u8[5] padding            (no validado por el parser)
off 16  code[codeLength]
        constants[constantsLength]  (cada una u16 BE, plegada a int)
        capabilities[n]             (cadenas NUL-terminadas)
        hash[32]                    (SHA-256 de TODO lo anterior)
```

**Comprobación empírica** con `examples/policy.flowasm` → `policy.flow` (4209 B):

```
formato=1 isa=2 abi_min=2 fw_min=1
entry=0x0202 code_len=4149 n_const=6 n_caps=0 pad=0000000000
constantes (6): [256, 768, 0, 1, 1, 16]
capacidades (0): []
hash almacenado == sha256(prefijo): True
bytes tras el hash: 0
```

El parser **exige** que no sobre ningún byte tras el hash
(`ProgramImageParser.java:129-131`) y verifica el SHA-256 sobre `data[0, hashOffset)`
(`:157-169`); el emisor calcula el hash sobre exactamente ese mismo prefijo
(`assembler.ts:1221-1223`). **Coherencia perfecta.**

**Debilidades (no defectos funcionales):**

- **Sin magic number** (`IFLOW-AUD-038`): no hay firma; cualquier fichero cuyos primeros bytes
  pasen las versiones y cotas llegará al SHA-256. El error será «Hash verification failed», no
  «no es un .flow».
- **`capabilitiesCount` es de 1 byte** (`:55` y `assembler.ts:1257`): tope duro de **255**
  capabilities; `codeLength` es u16 pero está acotado a `CODE_MAX_SIZE` = 8192 (`:65-68`).
- **Las cadenas de capability se leen byte a byte como `(char)`** (`:107`) y se escriben con
  `charCodeAt(0)` (`assembler.ts:1271`): correcto para ASCII, **pérdida silenciosa** para
  nombres no ASCII.
- El campo `debugInfo` existe en `ProgramImage` pero **no se serializa** (sin sección debug).

---

## 8. Auditoría de la VM y la memoria

### 8.1 Mapa de memoria (fuente única: `VmMemoryMap`)

| Región | Rango | Páginas | Permisos | Propiedad |
|---|---|---|---|---|
| Código (ROM + usuario) | `0x0000–0x1FFF` | 0-1 | R/X (tras boot) | kernel |
| Constantes | `0x2000–0x2FFF` | 2 | R | kernel |
| Datos/Heap | `0x3000–0x4FFF` | 3-4 | R/W | usuario |
| Pila | `0x5000–0x6FFF` | 5-6 | R/W | usuario |
| Entrada | `0x7000–0x7FFF` | 7 | R/W | usuario |
| Salida | `0x8000–0x8FFF` | 8 | R/W | usuario |
| RAM de usuario | `0x9000–0xFFFF` | 9-15 | R/W | usuario |
| **MMIO de dispositivos** | `0x0F00–0x0FFF` | (dentro de pág. 0) | — | dispositivo |

El **mapeo es identidad** página→frame en el camino de producción
(`VmPolicyEngine.java:292,315,325,332`). No hay solapamiento entre regiones mapeadas y no hay
huecos en las 16 páginas. La MMIO **se solapa** con la página 0 (código) a propósito
(`VmMemoryMap.java:91-101`); el bus la decodifica **antes** de la MMU.

### 8.2 Permisos: lo que sí se aplica (HECHO)

- `PageTable.translate` comprueba **límites**, **mapa** y **bits** (`:147-165`), y añade la
  regla de **propiedad**: escribir una página kernel sin privilegio supervisor →
  `PRIVILEGE_FAULT` **aunque el bit W esté presente** (`:166-171`). Esta es la pieza que el
  informe anterior daba por ausente, y hoy está implementada y comentada.
- `SoftMMU.writeBlock` **pre-valida todas las páginas** antes de escribir nada
  (`validateWriteRange`, `:264-265,278-293`), así que una escritura a caballo entre una página
  escribible y otra protegida **no** deja efectos parciales.
- El **fetch pasa por la MMU** con `EXECUTE` (`fetchByte`/`fetchWord` →
  `translateExecute`, `SoftMMU.java:311-323`), y la ventana de I-cache se rellena validando la
  página (`fillCodeWindow`, `:331-335`). El permiso de ejecución es **efectivo**, no nominal.
- El código se escribe por el bus **supervisor** y luego se **degrada** a R/X por `mprotect`
  (`VmPolicyEngine.java:293-304`): el programa no puede escribir su propio código ni durante el
  arranque.

### 8.3 Debilidades de memoria y E/S

- **`IFLOW-AUD-031` (Media-Alta) — la MMIO evade permisos y privilegio.** En `VmBus`, si la
  dirección pertenece a un dispositivo se llama directamente al periférico
  (`readByte/readWord/writeByte/writeWord`, `:85-117`) **sin consultar la `PageTable` ni el
  `Privilege` del maestro**. Como el apéndice MMIO vive en la página 0 (marcada R/X) y contiene
  la NVRAM del BIOS y el PIC, un `.flow` cualquiera (privilegio `PROGRAM`) puede:
  reescribir la **bandera de arranque en frío** (NVRAM) y el **latch/máscara del PIC**
  (`RAISE`/`ACK`), forzando y silenciando interrupciones. El comentario
  (`VmMemoryMap.java:96-98`) justifica el bypass *para que el BIOS pueda* escribir sin permiso de
  escritura en la página R/X, pero **no distingue emisor**: el mismo camino sirve a cualquier
  programa. Es una grieta de aislamiento dentro de la máquina (no un escape al host).
- **`IFLOW-AUD-032` (Media-Baja) — `touchesDevice` muestrea solo los extremos.**
  `VmBus.touchesDevice` (`:157-162`) decide si un bloque toca un periférico mirando **solo** el
  primer y el último byte. Un rango que *atraviese* la ventana MMIO sin empezar ni terminar en
  ella no se enruta a los dispositivos y cae por la MMU (→ `PRIVILEGE_FAULT` en página R/X, o
  escritura en RAM si el rango cae en zona R/W). Decodificación inconsistente con la de byte a
  byte.
- **`IFLOW-AUD-033` (Baja/Informativa) — las lecturas descartan el privilegio.**
  `SoftMMU.readByte/readWord/readBlock(vaddr, …, Privilege)` reciben `privilege` y llaman
  `translateRead(vaddr)`, que **no lo usa** (`:145-148,156-158,176-193`). Es coherente con
  «páginas kernel legibles por el programa» (`PageTable.java:79-81`), por lo que **no hay fuga**,
  pero la firma promete una comprobación inexistente y confunde.

---

## 9. Auditoría de servicios HOST y capacidades

**ABI v2 (HECHO, `HostServiceRegistry.java:22-38`):** `R0=op`, `R1=options`, `R2=base`,
`R3=length` (bidireccional), `R4=resultado`; retorno `R0=status`, `R3=bytes`, `R4=escalar`. Los
15 IDs declarados están **registrados** (`:184-336`); cualquier otro devuelve
`UNSUPPORTED` (`:375-383`).

**Capacidades (HECHO):**

- Cada servicio declara `requiredCapabilities()` (`:190-334`) y `invoke()` las comprueba contra
  el contexto **antes** de ejecutar (`:386-390`); si falta, lanza `hostServiceDenied`.
- Las capacidades del contexto son **la intersección** entre las que **pide el programa** (su
  `.capability` → `ProgramImage.capabilities()`) y las que **concede el host**
  (`grantedCapabilities`, `VmPolicyEngine.java:220-221,546-…`). Un programa **no puede ampliar**
  sus privilegios. `PassThroughProgram` declara `routing.decide` y llama a `ROUTE_DECISION`;
  funciona sin configuración.
- **Matiz:** `register()` auto-declara las capacidades de cada servicio en el *allowlist* del
  host (`:338-344`), de modo que el allowlist por defecto contiene **todas**; la restricción
  efectiva la ejerce la intersección con lo que pide el programa. Es el diseño correcto
  (deny-by-default efectivo), pero conviene saber que el allowlist no recorta nada por sí solo.
- `MESSAGE_CLONE` y `MESSAGE_ID` declaran `new String[0]` (`:324,334`): **no requieren
  capability**. Leer el contenido del mensaje (potencialmente privado) y su id no exige
  autorización; es una decisión de diseño, no un fallo, pero es la única pareja de servicios sin
  puerta.

**Integración real (HECHO):** los servicios transfieren por el **DMA del contexto**
(`ctx.dma()`, p. ej. `:454,482,502,628`), no por `SoftMMU` directo — exactamente como documenta
el ABI (`:44-49`). Los errores de servicio **no abortan** la VM: viajan como `status` en `R0`
(`:406-411`), y el fuel se cobra por coste base + variable por byte (`:392-401`).

**Servicios condicionados a inyección:** `PERMISSION_CHECK/QUERY` requieren `permissionChecker`
(si no, `HOST_FAILURE`, `:534-536`); `TRANSFORM_APPLY` requiere el hook del `kind` (si no,
`UNSUPPORTED`, `:476-478`); `ROUTE_RATE_LIMIT` requiere `rateLimiter` (si no, `UNSUPPORTED`,
`:729-731`). El motor los inyecta por defecto (`VmPolicyEngine.java:141-168`).

---

## 10. Auditoría de políticas y resultados

**Camino (HECHO):** el programa registra intenciones en el `ModificationJournal` (p. ej.
`ROUTE_DECISION` → `SET_PROCESSED`/`CANCEL_MESSAGE`/`REDIRECT_*`/`RATE_LIMIT_BACKOFF`,
`HostServiceRegistry.java:599-611`); `PolicyResultValidator.validate` las traduce a
`PolicyTarget` y aplica el journal al mensaje (`:76-135`).

**Fallos bien resueltos (fail-closed):**

- Error fatal de VM o `RuntimeException` → `REJECT` (`PolicyResultValidator.java:43-52`;
  `VmPolicyEngine.java:247-256`). El `catch (RuntimeException)` es una **red de seguridad
  explícita** para que ninguna excepción escape al pipeline del servidor.
- Canal de redirección inexistente → `REJECT` (`:113-124`).
- Cancelación → `REJECT` si el detalle es `route:REJECT`, si no `DROP` (`:56-68`).

**Debilidad:**

- **`IFLOW-AUD-034` (Media) — decisión fuera de rango degrada a LOG (fail-open).**
  `doRouteDecision` tiene un `default` que registra `SET_PROCESSED("route:UNKNOWN")`
  (`HostServiceRegistry.java:609`), y `parseRouteDecision` mapea cualquier cadena desconocida a
  `PolicyTarget.LOG` (`PolicyResultValidator.java:138-149`), cuyo `delivered()` es `true`
  (`RouteDecision.java:32-36`). Además el `target` **arranca** en `LOG` (`:71`). Resultado: un
  programa que emplace un código de decisión inválido (o ninguno) **entrega** el mensaje. Lo
  correcto para una política por defecto sería `REJECT`.
- **`IFLOW-AUD-042` (Baja) — operaciones del journal sin emisor y validación ausente.**
  `applyEntry` sabe aplicar `SET_SLEEP_MILLIS`, `SET_FORMAT_PAPI`, `ADD_SOUNDS`, `REMOVE_SOUNDS`
  y `SET_LANG_*`, pero **ninguna** de ellas la emite el código de producción (solo
  `SET_TEXT`/`SET_CHANNEL`/`SET_LANG_*`/`SET_COLOR_MODE` desde `writeProperty`
  (`VmPolicyEngine.java:441-459`) y las de routing). Y `SET_COLOR_MODE` aplica
  `ColorMode.valueOf(...toUpperCase())` (`PolicyResultValidator.java:167-168`) **sin validar**:
  un valor no válido lanza `IllegalArgumentException`, que la red de seguridad de `route()`
  convierte en `REJECT`. No escapa, pero se usa una excepción como control de flujo y **se pierde
  el motivo** (el mensaje se rechaza con «VM internal error»).

---

## 11. Estado, aislamiento y concurrencia

- **Serialización (HECHO):** `route()` toma `machineLock` para **toda** la ejecución
  (`VmPolicyEngine.java:198-262`), de modo que las escrituras sobre la memoria compartida de la
  máquina no necesitan sincronización interna. El `SoftMMU` no lleva *locks*, y es correcto **por
  este contrato**. Con el lock tomado durante la ejecución, el coste por mensaje es
  **secuencial** (HIPÓTESIS cuantitativa; no medido).
- **Estado persistente real (HECHO):** (a) `hostState` es un `ConcurrentHashMap` **del motor**
  (`:68`), consultado por `STATE_READ`/`STATE_WRITE` → persiste entre mensajes; (b) la RAM
  `0x9000+` y las páginas de datos nunca se limpian → persistencia implícita del programa. Es la
  base de la feature «una sola máquina viva» (D30).
- **Ciclo de vida del contexto (HECHO, `ExecutionContext.initialize`):** se limpian registros y
  flags, el journal se **`reset()`** (no `clear()`, para no romper el *pool*, `:197-199`), el
  fuel se restaura, el host port vuelve a `HostContext.NONE` y la ventana de I-cache se invalida
  (`codeWindowBase = -1`). **No** se limpian datos ni RAM de usuario (intencional).
- **`IFLOW-AUD-036` (Baja) — sin cancelación ni watchdog en producción.**
  `ExecutionContext.cancel()`/`checkCancelled` existen (`:416-421`) pero **nadie** activa
  `cancelled = true` fuera de los tests. La única cota de ejecución es el **fuel**, que sí es
  efectivo (ver §5). No hay cancelación por tiempo ni por apagado del servidor: si el servidor
  se detiene con un programa en bucle, la ejecución termina por fuel (100 000 unidades), no por
  señal.
- **`IFLOW-AUD-035` (Baja) — la rama de «error fatal» del validador es inalcanzable.**
  `setFatalError` solo lo invoca `ExecutionContextTest` (repo entero, `grep`); en producción los
  fallos llegan por excepción, no por `context.fatalError()`. La comprobación
  `context.hasFatalError()` (`PolicyResultValidator.java:43-52`) nunca se cumple en el camino
  real. No hace daño, pero es código muerto que aparenta una capacidad inexistente.

---

## 12. Tests ejecutados y pruebas nuevas

| Suite | Comando | Resultado |
|---|---|---|
| VM `src/iflow` | `JAVA_HOME=/opt/javac/x64/21 ./gradlew :src:iflow:test --offline --rerun-tasks` | **383 PASSED, 0 FAILED, 0 SKIPPED** (27 ficheros) |
| Cobertura | `jacocoTestReport` | **84 % instr · 71 % líneas · 70 % ramas · 91 % métodos · 97 % clases** |
| Compilador (build) | `npx tsc` | Sin errores |
| Compilador (validación) | `node test/validation/run.js` | **13/13** |
| Determinismo | reensamblado + `cmp` | **5/5 byte-idénticos** |

**Pruebas que añadiría** (no ejecutadas aquí; propuestas en §19): escribir en la NVRAM/PIC desde
un programa no-BIOS debe fallar; decisión de ruta fuera de rango debe dar `REJECT`; `SET_COLOR_MODE`
inválido debe dar `REJECT` **con motivo**, no «VM internal error»; bloque que atraviesa la MMIO
sin tocar extremos debe decodificarse igual que byte a byte; `-d` debe o bien emitir sección de
depuración o bien retirarse.

**Cobertura no verificada:** las suites existentes no incluyen un test que use un `.flow`
**ensamblado por el compilador real** y ejecutado en el JVM; los tests construyen los programas
en Java (`PassThroughProgram` o `byte[]` literales). Hay, por tanto, una **prueba de integración
compilador→VM ausente** (TEST). *No lo registro como defecto porque no he demostrado que el
camino falle, solo que no está cubierto.*

---

## 13. Capacidad funcional combinatoria

¿Puede un `.flow` expresar una política útil **sin** tocar nada que no exista? **Sí (HECHO por
inventario de ISA + servicios):** dispone de aritmética/lógica/shifts, comparación con
saltos condicionales (`JZ/JNZ/JC/JV`), subrutinas (`CALL/RET`), memoria indirecta
(`LDBI/LDWI/STBI/STWI`) para tablas, bloque (`COPY/MOVE`) y **15 servicios** que cubren
propiedades (leer/escribir/existe) de 5 ámbitos, permisos, enrutado (decidir/redirigir/limitar),
transformación (placeholders, detección de idioma), estado, canales, clon y id del mensaje.

**Lo que la ISA no ofrece** (y hay que resolver a mano con esos recursos): multiplicación/división
**sin signo** (no hay `MULU/DIVU`; `DIV/MOD` son con signo), comparación con inmediato (hay que
`LI` a un registro y `CMP`), punteros de 16 bits solo (no hay 32), y **no hay `IRET`** (la IRQ es
transparente, gestionada por el runtime, lo que se documenta como decisión de diseño).

**Conclusión:** la capacidad expresiva está **alineada con lo documentado**
(`src/iflow/README.md`), salvo la endianness (§15). No detecté servicios prometidos e
inexistentes: los 15 IDs del README están registrados.

---

## 14. Seguridad y rendimiento

### 14.1 Modelo de amenazas

**¿Qué protege el sistema hoy, y de qué?**

| Propiedad | Estado | Evidencia |
|---|---|---|
| El programa no puede escribir su código ni las constantes | **SÍ** | `mprotect` a R/X y R; `PageTable.java:166-171` |
| El programa no puede saltar a memoria sin `EXECUTE` | **SÍ** | `SoftMMU.fetch*` con `translateExecute` (`:311-323`) |
| El programa no puede ampliar sus capacidades | **SÍ** | intersección host∩programa (`VmPolicyEngine.java:546-…`) |
| El programa no puede invocar un servicio sin su capability | **SÍ** | `HostServiceRegistry.java:386-390` |
| El programa **no** puede escribir periféricos (NVRAM/PIC) | **NO** | `VmBus.java:101-117` (`IFLOW-AUD-031`) |
| Una decisión inválida no acaba en entrega | **NO** | `IFLOW-AUD-034` |

**Alcance de la amenaza:** los `.flow` los instala el administrador del servidor, así que
`IFLOW-AUD-031` **no** es un escape de sandbox frente a un tercero no confiable; es una **frontera
interna** (el BIOS usa el bypass a propósito). Su severidad es Media-Alta porque rompe la promesa
del modelo declarado («el dueño de la página decide») y porque un programa puede enmascarar la
IRQ de mensaje (silenciar la política) o disparar líneas espurias. **No** se verificó si la IRQ
espuria puede producir una recursión de manejador (sería HIPÓTESIS): el PIC marca la línea *en
servicio* sin reentrada y el intérprete guarda el estado completo, lo que sugiere que no.

### 14.2 Rendimiento

**No medido.** Cualitativamente (INFERENCIA): (a) el mapa es identidad → traducción O(1) sin
TLB, coste trivial; (b) la memoria es *copy-on-write* con snapshot congelado y `clearBlock` que
omite frames ya nulos (`ExecutionContext.java:218-226`), así que el arranque por mensaje evita
los 8 KB de escritura del modelo anterior; (c) la persistencia de la máquina elimina el *fork*
por mensaje. El punto débil es la **serialización** de toda la ejecución bajo `machineLock`.

---

## 15. Contradicciones entre documentación e implementación

| # | Documento | Afirma | Código | Estado |
|---|---|---|---|---|
| 1 | `src/iflow/README.md` («Formato instrucción: little-endian») y la tabla del `.flow` («`u16 entryPoint (little-endian)`») | Formato little-endian | Operandos de instrucción **big-endian** (`VmInterpreter.java:154`, `assembler.ts:978-980`); cabecera **big-endian** (`ProgramImageParser.java:52-55`) | **Contradicción** — `IFLOW-AUD-039` |
| 2 | `src/iflow/README.md` («el firmware acaba en 0x029B») | Fin del firmware en `0x029B` | `VmMemoryMap.java:95` dice «currently at 0x0204» | **Contradicción entre dos documentos** — `IFLOW-AUD-040` (cuál es el valor real: no verificado) |
| 3 | Informe anterior (`IFLOW-AUD-019/023/028/029/030`) | DMA muerto, fetch sin MMU, `writeProperty` limitado, privilegio no aplicado, clon por mensaje | Todo **corregido** (§17.0) | **Resuelto** (la doc antigua quedó superada) |
| 4 | `src/iflow/README.md` («buffer salida I/O (R/W) 0x8000») | Buffer de salida como E/S | Se mapea y se limpia, pero **ninguna** ruta de producción lo lee (§17.41) | **Implementado sin consumidor** — `IFLOW-AUD-041` |

No encontré contradicciones en lo esencial: el mapa de memoria, el ABI v2, los 15 servicios y
sus capabilities, los permisos R/W/X y la propiedad kernel **coinciden** entre README, `HDL.md`,
los prompts de diseño y el código, salvo las dos filas anteriores.

---

## 16. Aciertos de arquitectura

Cada uno con **dónde está**, **qué ventaja da** y **qué lo neutralizaría**:

1. **`VmMemoryMap` como fuente única de verdad.** Parser, motor y contexto derivan de él; el
   parser acota las secciones con `CODE_MAX_SIZE`/`CONSTANTS_MAX_SIZE` (`ProgramImageParser.java:65-68,85-88`).
   *Ventaja:* el mapa no puede derivar entre el binario y la máquina. *Límite:* el apéndice MMIO
   vive dentro de la página de código, que es justo el origen de `IFLOW-AUD-031`.
2. **`ProgramImage` inmutable con SHA-256 de prefijo.** Verificado byte a byte por ambos lados
   (§7). *Ventaja:* integridad y versionado del artefacto de despliegue. *Límite:* sin magic
   number (`IFLOW-AUD-038`).
3. **Privilegio de página efectivo en escritura** (`PageTable.java:166-171`): la propiedad manda
   sobre el bit. *Ventaja:* un bug de mapeo no abre el código a escritura. *Neutralizado por:*
   la MMIO, que no pasa por la `PageTable` (`IFLOW-AUD-031`).
4. **Bus explícito + master con privilegio** (`VmBus`): un único punto por el que transaccionan
   CPU y DMA, con el privilegio del emisor. *Ventaja:* modela la interconexión y da un punto de
   observación/sustitución. *Límite:* enruta los dispositivos **antes** de la MMU, lo que rompe
   la uniformidad del control de acceso.
5. **Fetch por la MMU con `EXECUTE`** (`SoftMMU.fetch*`): el permiso de ejecución es real y la
   ventana de 4 KB se valida al entrar. *Ventaja:* protecciones no nominales. *Evidencia:* tests
   `VmInterpreterTest`/`SoftMMUTest` y el comentario que documenta el fallo previo corregido.
6. **Memoria copy-on-write** (`PhysicalMemory`/`PageTable.fork` + `freeze`): *fork* barato para
   snapshots/tests; el camino por mensaje ya no forka. *Límite:* el `SoftMMU` no lleva *locks*;
   solo es seguro **porque** `machineLock` serializa (acoplamiento implícito que un futuro uso
   concurrente rompería).
7. **Capacidades con intersección host∩programa** (`grantedCapabilities`): el programa no puede
   ampliar privilegios; deny-by-default efectivo. *Límite:* `MESSAGE_CLONE`/`MESSAGE_ID` sin
   capability.
8. **Fuel con `OUT_OF_FUEL`** (`ExecutionContext.java:241-247`) y coste por servicio
   (`HostServiceRegistry.java:392-401`): cota de ejecución real, con IP en la excepción.
   *Límite:* no hay cancelación por tiempo ni watchdog (`IFLOW-AUD-036`).
9. **Errores de servicio como `status` en `R0`**, no como excepción
   (`ServiceResult.Status`, `:375-383`): la VM puede negociar capacidades y degradar sin morir.
10. **Pool de contextos con `journal.reset()`** y aislamiento entre ejecuciones
    (`initialize()`): reutilización sin fugas de estado entre mensajes. *Evidencia:* el
    `reset()` explícito está comentado como corrección de un fallo de pooling anterior.
11. **Estado persistente real** (`hostState` del motor + RAM `0x9000+`): la feature «una sola
    máquina viva» está implementada y probada (`VmPolicyEngineTest`), no solo documentada.
12. **Red de seguridad en `route()`** (`catch (RuntimeException)` → `REJECT`): ninguna excepción
    escapa al pipeline del servidor.
13. **Firmware ROM real y BIOS activo** (POST, pantalla, tabla de IRQ): el `entryPoint` es
    `fw_reset` y el hardware queda listo sin que el programa lo configure; hay pruebas end-to-end
    (`BiosBootTest`, `IrqDeliveryTest`, `FirmwareIntegrationTest`).

---

## 17. Registro completo de hallazgos

### 17.0 Estado de los hallazgos del informe anterior (AUD-019…030)

| ID previo | Tema | Estado hoy | Evidencia |
|---|---|---|---|
| AUD-019 | `VmDmaController` código muerto | **Resuelto** | `ctx.dma()` es la vía de todos los servicios (`HostServiceRegistry.java:454,482,502,628…`) |
| AUD-020 | Buffer de salida sin consumidor | **Persiste** | `IFLOW-AUD-041` (§17.41) |
| AUD-022 | Memoria cruda mutable expuesta | **Resuelto** | `getPhysicalMemory()` devuelve un **snapshot** (`SoftMMU.java:347-349`) |
| AUD-023 | Fetch sin MMU (`codeCache`) | **Resuelto** | `fetch*` con `translateExecute` + ventana de I-cache (`SoftMMU.java:311-335`) |
| AUD-028 | `writeProperty` no mapeaba `scope/propId` | **Resuelto** | `VmPolicyEngine.java:441-459` |
| AUD-029 | Clon de memoria por mensaje | **Resuelto** | «una sola máquina viva» + COW (`VmPolicyEngine.java:193-198`) |
| AUD-030 | Privilegio de página no aplicado | **Resuelto** | `PageTable.java:166-171` |
| AUD-024/025/026/027 | API duplicada, campos muertos, pass-through | **Parcial**: `PassThroughProgram` es hoy fuente única (AUD-027, OK); persisten accesores poco usados (`getPageTableForInspection` solo en test) | `grep` (§Apéndice A) |

*Los IDs previos no se reabren; se deja constancia del estado.*

---

### [IFLOW-AUD-031] La MMIO de periféricos no comprueba permisos de página ni privilegio del maestro

**Estado epistemológico:** HECHO VERIFICADO.
**Severidad:** Media-Alta (aislamiento interno).
**Confianza:** Alta (código leído íntegro y flujo seguido).
**Ubicación principal:** `src/iflow/.../vm/memory/VmBus.java:101-117` (escrituras), `:85-99` (lecturas).
**Ubicaciones relacionadas:** `VmMemoryMap.java:91-121` (apéndice MMIO dentro de la página 0);
`VmPolicyEngine.java:290-304` (página 0 marcada R/X); `PageTable.java:166-171` (la regla que *sí*
se aplica a RAM); `assembler.ts:523,540` (el BIOS escribe NVRAM con `STWI`).
**Componente afectado:** bus de datos / MMU / periféricos.
**Comportamiento esperado:** el maestro `PROGRAM` (CPU del programa) no puede escribir el
apéndice MMIO de control (NVRAM/PIC), o solo con capability explícita; el modelo «el dueño de la
página decide» (`PageTable.java:137-141`) debería gobernar también la E/S.
**Comportamiento observado:** `VmBus.writeByte/writeWord` deciden el destino con
`deviceFor(vaddr)` y, si es un dispositivo, llaman `d.writeByte(...)`/`d.writeWord(...)`
**sin** consultar la `PageTable` ni el `Privilege`. Ninguna ruta de E/S pasa por `translate`.
**Evidencia:**

```java
// VmBus.java:101-108
public void writeByte(int vaddr, int value) {
    VmDevice d = memory.deviceFor(vaddr);
    if (d != null) {
        d.writeByte(vaddr, value);
        return;
    }
    memory.writeByte(vaddr, value, privilege);   // ← solo la RAM comprueba permiso/privilegio
}
```

```java
// VmMemoryMap.java:96-98 — el bypass es intencional para el BIOS…
// "El interconnect lo decodifica *antes* de ROM/RAM, así que el BIOS puede manejar
//  dispositivos con stores normales sin permiso de escritura en la página (R/X) de código."
```

**Razonamiento:** la ventana MMIO (`0x0F00–0x0FFF`) está contenida en la página 0, que el cargador
marca R/X (`VmPolicyEngine.java:303`). Si la E/S pasara por la `PageTable`, el BIOS —que corre en
la misma página— no podría escribirla; para evitar eso, el bus la decodifica antes. La
consecuencia no prevista es que **tampoco** distingue cuándo el emisor es el programa de usuario:
el mismo camino sirve a cualquiera, y la página R/X deja de proteger la NVRAM y el PIC.
**Impacto:** un `.flow` puede reescribir la bandera de arranque del BIOS (alterando el POST del
siguiente arranque), el `ENABLE`/`RAISE`/`ACK` del PIC (enmascarar la IRQ de mensaje o disparar
líneas espurias) y el banco de registros de la pantalla. No hay escape al host (el periférico
está en el contenedor del programa), pero se rompe la frontera declarada.
**Condiciones necesarias:** cualquier `.flow` presente en el directorio de configuración.
**Reproducción:** `asm: STWI` a `DEVICE_NVRAM_BASE` (`0x0F10`) desde el programa de usuario y
leer la bandera de arranque en el mensaje siguiente (no ejecutado aquí; la ruta es la que usa el
propio BIOS en `assembler.ts:523,540`).
**Corrección recomendada:** que el bus consulte la `PageTable`/`Privilege` antes de enrutar al
dispositivo (p. ej. exigir presencia de mapeo y, para escrituras de control, privilegio
`SUPERVISOR` o una capability `device.write`), o sacar el apéndice MMIO de la página R/X a una
página propia con permisos explícitos.
**Regresión que debe añadirse:** test que ejecute un `STWI` a `0x0F10` desde un contexto
`PROGRAM` y espere fallo (hoy escribiría).
**Limitaciones:** no he ejecutado el `STWI`; la conclusión es de lectura del código y del mismo
patrón que usa el firmware.

---

### [IFLOW-AUD-032] `touchesDevice` decide por los extremos del rango

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Media-Baja. **Confianza:** Alta.
**Ubicación principal:** `src/iflow/.../vm/memory/VmBus.java:157-162`.
**Ubicaciones relacionadas:** `:119-148` (`readBlock`/`writeBlock`/`clearBlock`).
**Comportamiento esperado:** un bloque se enruta a los dispositivos si **algún** byte cae en una
ventana MMIO; la decodificación de bloque y la de byte a byte deben coincidir.
**Comportamiento observado:** solo se comprueba el **primer** y el **último** byte
(`isDeviceAddress(vaddr) || isDeviceAddress(vaddr + length - 1)`).
**Evidencia:**

```java
// VmBus.java:157-162
private boolean touchesDevice(int vaddr, int length) {
    if (length <= 0) return false;
    return memory.isDeviceAddress(vaddr) || memory.isDeviceAddress(vaddr + length - 1);
}
```

**Razonamiento:** para un rango como `0x0EF0…0x1000`, ni el primer byte (`0x0EF0`) ni el último
(`0x1000`) son dispositivos, pero **dentro** está toda la ventana `0x0F00–0x0FFF`. El bloque va
por el camino de RAM/`SoftMMU` en vez de por los periféricos. Como la página 0 es R/X, una
escritura así dará `PRIVILEGE_FAULT` (no corrompe), pero una lectura obtendrá la ROM en vez del
registro del dispositivo: **resultado distinto** del de leer byte a byte.
**Impacto:** inconsistencia de decodificación; lecturas/escrituras de bloque que solapan la MMIO
sin tocar extremos dan un resultado diferente a las equivalentes byte a byte.
**Condiciones necesarias:** bloques (`LDW/STW` sobre ventana o DMA) que crucen `0x0F00–0x0FFF`
por los bordes.
**Reproducción:** comparar `writeBlock(vaddr, src, 0, n)` con `n` escrituras `writeByte` sobre un
rango que contenga la ventana sin tocarla en los extremos (no ejecutado aquí).
**Corrección recomendada:** comprobar solapamiento de intervalo contra los rangos de cada
dispositivo, no solo los extremos.
**Regresión que debe añadirse:** test de equivalencia bloque↔byte sobre un rango que cruce la
MMIO.
**Limitaciones:** no he ejecutado el caso; el análisis es de lectura.

---

### [IFLOW-AUD-033] Las lecturas de la SoftMMU descartan el privilegio

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Baja/Informativa. **Confianza:** Alta.
**Ubicación principal:** `src/iflow/.../vm/memory/SoftMMU.java:145-148,156-158,176-193`.
**Ubicaciones relacionadas:** `PageTable.java:177-179` (`translateRead` sin privilegio).
**Comportamiento esperado:** o bien las lecturas no dependen del privilegio (y entonces el
parámetro sobra), o bien lo aplican.
**Comportamiento observado:** `readByte(vaddr, privilege)`, `readWord(vaddr, privilege)` y
`readBlock(vaddr, …, privilege)` reciben el privilegio y llaman `translateRead`, que **no lo
usa**. Es el único `translate*` que no lo propaga.
**Evidencia:**

```java
// SoftMMU.java:145-148
public int readByte(int virtualAddr, Privilege privilege) {
    int physAddr = pageTable.translateRead(virtualAddr);   // ← ignora 'privilege'
    return memory.readByte(physAddr);
}
```

**Razonamiento:** la `PageTable` documenta que las páginas kernel son *program-readable*
(`PageTable.java:79-81`), así que para `READ` el privilegio **no cambia el resultado** y no hay
fuga. El defecto es de **claridad y contrato de API**: la firma sugiere una comprobación
inexistente.
**Impacto:** riesgo de que un futuro permiso de lectura por privilegio se implemente solo en la
`PageTable` y las lecturas lo ignoren silenciosamente.
**Condiciones necesarias:** ninguna hoy (no hay efecto observable).
**Corrección recomendada:** propagar `privilege` a `translateRead` (aunque hoy no cambie el
resultado) o retirar el parámetro.
**Regresión que debe añadirse:** test que fije el contrato elegido.
**Limitaciones:** ninguna; código leído íntegro.

---

### [IFLOW-AUD-034] Una decisión de ruta fuera de rango degrada a LOG (fail-open)

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Media. **Confianza:** Alta.
**Ubicación principal:** `src/iflow/.../vm/engine/PolicyResultValidator.java:138-149` (default →
`LOG`), `:71` (`target` inicial `LOG`).
**Ubicaciones relacionadas:** `HostServiceRegistry.java:599-611` (`default` → `SET_PROCESSED`
`"route:UNKNOWN"`); `RouteDecision.java:32-36` (`LOG.delivered() == true`).
**Comportamiento esperado:** una política que produce un resultado inválido no debería **entregar**
el mensaje; el contrato de fallo del validador es `REJECT` (`:43-52`, `:113-124`).
**Comportamiento observado:** el `default` del `switch` en el servicio registra
`SET_PROCESSED(true)`, y `parseRouteDecision` traduce cualquier cadena desconocida a
`PolicyTarget.LOG`, que **se entrega**.
**Evidencia:**

```java
// PolicyResultValidator.java:138-149
private PolicyTarget parseRouteDecision(String route) {
    return switch (route.toUpperCase()) {
        case "LOG" -> PolicyTarget.LOG;
        …
        case "CONTINUE" -> PolicyTarget.LOG;
        default -> PolicyTarget.LOG;   // ← desconocido ⇒ LOG (entregable)
    };
}
```

**Razonamiento:** hay una asimetría entre el tratamiento de los **errores de ejecución**
(fail-closed → `REJECT`) y el de los **valores de decisión** inválidos (fail-open → `LOG`). Un
programa con un código de decisión erróneo termina entregando el mensaje, que es justo lo que una
política por defecto suele querer evitar.
**Impacto:** una política mal compilada o un código fuera de rango puede provocar entrega en vez
de rechazo, silenciosamente.
**Condiciones necesarias:** el programa emite `ROUTE_DECISION` con un `options` fuera de
`DECISION_LOG…DECISION_CONTINUE` (`0…6`), o termina sin decisión.
**Reproducción:** llamar a `ROUTE_DECISION` con `options=200` y comprobar que
`RouteOutcome.decision().delivered()` es `true` (no ejecutado aquí; verificado por código).
**Corrección recomendada:** que el `default` del servicio y de `parseRouteDecision` produzcan
`REJECT`, o que el servicio valide el rango y devuelva `INVALID_ARGUMENT`.
**Regresión que debe añadirse:** test `options` fuera de rango ⇒ `REJECT`.
**Limitaciones:** ninguna; las tres ubicaciones están leídas.

---

### [IFLOW-AUD-035] `setFatalError` nunca se invoca: rama de error fatal inalcanzable

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Baja (código muerto). **Confianza:** Alta.
**Ubicación principal:** `src/iflow/.../vm/execution/ExecutionContext.java:423-425`;
`PolicyResultValidator.java:43-52`.
**Comportamiento esperado:** o la rama se usa, o no existe.
**Comportamiento observado:** el único llamador de `setFatalError` en todo el repositorio es
`ExecutionContextTest`; la comprobación `context.hasFatalError()` del validador nunca se cumple
en producción (los fallos llegan como excepción capturada en `VmPolicyEngine.java:247-256`).
**Evidencia:** `grep -rn "setFatalError" --include=*.java src/` → definición + test.
**Impacto:** mantenibilidad; sugiere una capacidad de diagnóstico que no existe. No hay efecto
funcional.
**Corrección recomendada:** eliminar la rama y el campo, o cablearlos desde la captura de
excepciones del motor.
**Limitaciones:** ninguna.

---

### [IFLOW-AUD-036] No hay cancelación ni watchdog en producción

**Estado epistemológico:** HECHO VERIFICADO (ausencia) + INFERENCIA (impacto).
**Severidad:** Baja. **Confianza:** Media-Alta.
**Ubicación principal:** `ExecutionContext.java:416-421` (`cancel`/`checkCancelled`).
**Comportamiento observado:** `cancel()` solo se invoca en tests; no hay temporizador ni vía de
cancelación desde el host. La única cota es el fuel (efectiva).
**Impacto:** con `machineLock` tomado durante toda la ejecución, un programa que no consuma fuel
(no puede: cada instrucción consume 1) no puede colgarse indefinidamente; pero **sí** puede
consumir las 100 000 unidades de fuel en cada mensaje, y no hay forma de abortar una ejecución en
curso si el servidor se está apagando.
**Condiciones necesarias:** escenario de apagado con `policy.flow` activo.
**Corrección recomendada:** exponer una cancelación cooperativa (p. ej. `cancel()` desde el
`close()` del motor) y/o un presupuesto de tiempo además del de fuel.
**Limitaciones:** no he medido cuánto tarda en agotarse el fuel (HIPÓTESIS cuantitativa).

---

### [IFLOW-AUD-037] `-d/--debug` no altera el artefacto

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Baja. **Confianza:** Alta.
**Ubicación principal:** `tools/iflow-compiler/src/cli.ts:19` (opción), `assembler.ts:1191,1218`
(se guarda en `ProgramImage`), `:1234-1273` y `:1334-1373` (serializadores).
**Comportamiento esperado:** `-d` debería producir información de depuración en la salida (o no
existir).
**Comportamiento observado:** ninguno de los dos serializadores emite sección de depuración; el
`.flow` es idéntico con y sin `-d`. (El listado `-l/--list` sí genera el `.lst`.)
**Impacto:** opción engañosa; quien la use creerá que el binario lleva símbolos.
**Corrección recomendada:** implementar una sección `debug` versionada o retirar la opción.
**Limitaciones:** ninguna; verificado en el código de los dos serializadores.

---

### [IFLOW-AUD-038] El formato `.flow` carece de magic number

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Informativa. **Confianza:** Alta.
**Ubicación principal:** `ProgramImageParser.java:22-56`.
**Comportamiento esperado (endurecimiento):** un formato binario propio suele llevar firma para
rechazar rápido un fichero ajeno.
**Comportamiento observado:** no hay magic; cualquier fichero cuyos primeros bytes pasen las
versiones y cotas llegará al SHA-256. El error es «Hash verification failed», no «no es un .flow».
**Impacto:** diagnóstico; el fallo es igualmente seguro (la carga cae a pass-through).
**Corrección recomendada:** añadir `u32 magic` (p. ej. `0x464C4F57` «FLOW») como primera palabra
y versionar el formato.
**Limitaciones:** cambiaría el formato (requiere subir `formatVersion`).

---

### [IFLOW-AUD-039] La documentación declara little-endian; los operandos y la cabecera son big-endian

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Media (documental; trampa real).
**Confianza:** Alta.
**Ubicación principal:** `src/iflow/README.md` (tabla «Formato instrucción» y bloque del header).
**Ubicaciones relacionadas:** `VmInterpreter.java:154`; `assembler.ts:978-980,1251-1256`;
`ProgramImageParser.java:52-55`; `SoftMMU.java:150-168` (datos, little-endian).
**Comportamiento esperado según el documento:** todo little-endian.
**Comportamiento observado:** **mixto**: operandos de instrucción y cabecera **big-endian**;
palabras de datos (MMU) y constantes de la ROM **little-endian**.
**Evidencia:**

```java
// VmInterpreter.java:154 — inmediato big-endian
int imm = (fetch(ctx, ip + 2) << 8) | fetch(ctx, ip + 3);
```

```ts
// assembler.ts:1251-1253 — entrada big-endian
parts.push((image.entryPoint >> 8) & 0xFF);
parts.push(image.entryPoint & 0xFF);
```

**Impacto:** cualquiera que escriba un ensamblador o un parser nuevo siguiendo el README
codificará mal la cabecera y los inmediatos. Es el mayor riesgo de mantenimiento del formato.
**Corrección recomendada:** corregir el README (y `HDL.md` si procede) para describir el formato
mixto y etiquetar cada campo por su endianness.
**Regresión que debe añadirse:** un test del compilador que fije el layout byte a byte (golden
file) — el reensamblado determinista ya protege la estabilidad, pero no documenta la intención.
**Limitaciones:** el valor real es el del código (verificado por ambos lados).

---

### [IFLOW-AUD-040] Dos documentos discrepan sobre dónde acaba el firmware

**Estado epistemológico:** CONTRADICCIÓN (entre documentos). **Severidad:** Informativa.
**Confianza:** Alta (sobre la discrepancia), Baja (sobre cuál es el valor correcto).
**Ubicaciones:** `src/iflow/README.md` («el firmware acaba en `0x029B`») vs
`VmMemoryMap.java:95` («currently at `0x0204`»).
**Impacto:** documental. Ninguno funcional: la MMIO usa la cola libre de la ROM y el valor exacto
no participa en ninguna comprobación.
**Corrección recomendada:** dejar una sola cifra (derivarla de la ROM ensamblada).
**Limitaciones:** no verifiqué cuál es la correcta (requeriría medir la ROM generada).

---

### [IFLOW-AUD-041] El buffer de salida (`0x8000`) sigue sin consumidor en producción

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Baja. **Confianza:** Alta.
**Ubicación principal:** `VmMemoryMap.java:71`, `VmPolicyEngine.java:339`,
`ExecutionContext.java:112,498-499`.
**Comportamiento esperado:** si el mapa declara un buffer de salida, algún consumidor lo lee.
**Comportamiento observado:** el buffer se mapea R/W, se limpia en cada ejecución
(`initialize`) y tiene un getter (`outputBufferVAddr()`), pero **ninguna** ruta de producción lo
lee; el resultado viaja exclusivamente por el `ModificationJournal`. Es el único hallazgo del
informe anterior que **persiste**.
**Impacto:** superficie de E/S muerta; un programa puede escribir en `0x8000` sin que nadie lo use.
**Corrección recomendada:** o bien exponer una lectura (p. ej. para depuración/telemetría), o
documentarlo como reservado y quitar el getter.
**Limitaciones:** `grep` sobre producción; si un periférico lo decodificara, aparecería en
`deviceFor`.

---

### [IFLOW-AUD-042] Operaciones del journal inalcanzables y `SET_COLOR_MODE` sin validar

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Baja. **Confianza:** Alta.
**Ubicación principal:** `PolicyResultValidator.java:159-178`;
`VmPolicyEngine.java:441-459`.
**Comportamiento observado:** (a) el validador sabe aplicar `SET_SLEEP_MILLIS` (`Long`),
`SET_FORMAT_PAPI` (`Boolean`), `ADD_SOUNDS`/`REMOVE_SOUNDS` (`List`) y `SET_LANG_*`, pero **ningún**
emisor de producción los crea (solo lo hacen `writeProperty` y los servicios de routing);
(b) `SET_COLOR_MODE` aplica `ColorMode.valueOf(value.toUpperCase())` (`:167-168`) sin validar el
valor.
**Evidencia:**

```java
// PolicyResultValidator.java:167-168
case SET_COLOR_MODE -> msg.withColorMode(
    ColorMode.valueOf(((String) entry.value).toUpperCase()));
```

**Razonamiento:** un valor de color no válido (p. ej. `"banana"`) lanza
`IllegalArgumentException` dentro de `validate()`. No escapa —la red de seguridad de `route()`
lo convierte en `REJECT`— pero se usa una **excepción como control de flujo**, y el mensaje se
rechaza con «VM internal error» (motivo perdido).
**Impacto:** rechazos inexplicables ante un valor inválido del programa; ramas muertas que
confunden sobre qué puede emitirse.
**Corrección recomendada:** validar el valor antes de journalar (devolver `INVALID_ARGUMENT` en
`PROPERTY_WRITE`) y eliminar del validador las operaciones sin emisor.
**Regresión que debe añadirse:** `PROPERTY_WRITE` de color inválido ⇒ status de error, sin
`REJECT` opaco.
**Limitaciones:** ninguna sobre lo leído.

---

### [IFLOW-AUD-043] Asimetría de compatibilidad de versiones en el parser

**Estado epistemológico:** HECHO VERIFICADO. **Severidad:** Baja. **Confianza:** Alta.
**Ubicación principal:** `ProgramImageParser.java:38-50`.
**Comportamiento observado:** `isaVersion` y `minFirmwareVersion` se aceptan si son **menores o
iguales** (`>`), pero `minAbiVersion` exige **igualdad exacta** (`!=`). Un programa construido
para un ABI anterior se rechaza en bloque.
**Impacto:** documentado como intencional en el README («un `.flow` de otra convención de llamada
se rechaza»), pero la asimetría no está justificada en el código y complica la evolución del
ABI.
**Corrección recomendada:** hacer explícito el contrato (comentario y, si se quiere compatibilidad
hacia atrás, `minAbiVersion <= CURRENT`), o mantener la igualdad y documentarla como decisión
deliberada en el formato.
**Limitaciones:** ninguna.

---

## 18. Tabla de puntuaciones justificadas

| Dimensión | Nota | Justificación |
|---|---|---|
| Calidad de la arquitectura | **8,0** | Separación limpia ISA/memoria/ejecución/servicios, bus explícito, `VmMemoryMap` única |
| Diseño y coherencia de la ISA | **8,0** | Codificación completa y coherente; falta `MULU/DIVU` y CMP con inmediato (documentado) |
| Corrección del intérprete | **7,5** | Bucle correcto, errores tipados, fuel efectivo; terminación por desbordamiento de PC silenciosa |
| Corrección y robustez del modelo de memoria | **6,5** | COW, pre-validación y privilegio de escritura correctos; la MMIO evade el control |
| Aislamiento y aplicación de permisos | **6,0** | Excelente en RAM/código; **no** en periféricos (`IFLOW-AUD-031`) |
| Calidad del formato binario | **7,0** | Correcto y con integridad SHA-256; sin magic y con endianness mal documentada |
| Corrección del compilador | **8,0** | Determinista, 13/13 validaciones, ROM real; `-d` es no-op |
| Coherencia compilador↔runtime | **9,0** | Formato verificado byte a byte por ambos lados, hash alineado |
| Funcionalidad de los servicios HOST | **8,0** | 15/15 registrados, DMA como vía, errores por status; dos sin capability |
| Corrección de decisiones y efectos de las políticas | **6,0** | Journal y conversión sólidos; `default → LOG` fail-open |
| Robustez ante entradas malformadas | **7,5** | Cotas, hash, truncados y `fallback` pass-through; sin magic |
| Rendimiento | **7,0** | Estructura O(1) y sin fork por mensaje; serialización global (no medido → HIPÓTESIS) |
| Tests y cobertura | **8,5** | 383/383, 13/13; falta integración compilador→VM real |
| Documentación y coherencia con el código | **6,5** | Muy completa; endianness y fin del firmware mal |
| Mantenibilidad | **8,0** | Modular y legible; código muerto localizado (fatal error, cancel, journal ops) |
| Concurrencia | **7,0** | Serialización y pool correctos; acoplamiento implícito MMU↔lock, sin watchdog |

**Global ponderada: ~7,5/10** — subsistema maduro y funcional, con deuda localizada y dos
defectos que conviene cerrar antes de ampliarlo (E/S sin control de acceso y fail-open de
decisiones). **No** se rebaja la valoración global por ellos, ni se eleva por encima de lo que la
evidencia permite.

> **Comparación con el informe anterior (~62 %):** la subida no es cosmética: se debe a la
> corrección **verificada** de AUD-019/023/028/029/030 y al refuerzo de tests (288 → 383).

---

## 19. Plan de corrección priorizado

### P0 — Integridad y corrección fundamental
*Ninguno.* No he encontrado corrupción de memoria ni incumplimiento de la integridad del binario.

### P1 — Corrección funcional
1. **IFLOW-AUD-031** — control de acceso en la MMIO de periféricos (exigir privilegio o
   capability; o sacar el apéndice de la página R/X).
2. **IFLOW-AUD-034** — `default` de decisión de ruta ⇒ `REJECT` (cerrar el fail-open).

### P2 — Robustez
3. **IFLOW-AUD-042** — validar `SET_COLOR_MODE` en `PROPERTY_WRITE`; retirar las operaciones del
   journal sin emisor.
4. **IFLOW-AUD-032** — decodificación de bloques por solapamiento de intervalo.
5. **IFLOW-AUD-036** — cancelación cooperativa (y, opcionalmente, presupuesto de tiempo).

### P3 — Rendimiento y mantenibilidad
6. **IFLOW-AUD-039 / 040** — corregir la documentación de endianness y unificar la cifra del
   firmware.
7. **IFLOW-AUD-035 / 041 / 037** — retirar código/mecanismos muertos (`fatalError`,
   buffer de salida o su getter, opción `-d`).
8. **IFLOW-AUD-033** — decidir y fijar el contrato de privilegio en lectura.

### P4 — Mejoras de diseño
9. **IFLOW-AUD-038 / 043** — añadir magic number (subiendo `formatVersion`) y hacer explícito el
   contrato de versiones.
10. Capacidad para `MESSAGE_CLONE`/`MESSAGE_ID`; y **test de integración compilador→VM** que
    ensamble con el CLI real y ejecute en el JVM.

---

## 20. Registro de dudas abiertas y verificaciones pendientes

- **¿Cuánto tarda en agotarse el fuel** en una política típica? Sin benchmark no puedo afirmar
  si 100 000 unidades son holgadas o escasas (HIPÓTESIS).
- **¿Puede una IRQ espuria disparada por el programa producir recursión o inanición?** El PIC
  marca la línea en servicio y el intérprete guarda el estado completo, lo que sugiere que no,
  pero **no lo he ejecutado** (HIPÓTESIS).
- **¿Es `0x0204` o `0x029B` el fin real del firmware?** **RESUELTO (2026-10-19):** medido sobre el
  `.flow` ensamblado, el último byte no-cero de la página 0 está en `0x029B` (668 B). El README es
  correcto; el comentario de `VmMemoryMap` (`0x0204`) estaba obsoleto y se corrigió (`IFLOW-AUD-040`).
- **¿`MSGP`/servicios con `new String[0]` de capabilities** deben exigir alguna? Decisión
  del diseño, no comprobada con el autor.
- ~~**¿El display sombrea RAM de usuario (`0xE000`)?**~~ **RESUELTO (2026-10-19):** sí. La ventana por
  defecto 80×25×2 = 4000 B sombreaba el 14 % de las 28 KB declaradas (`0x9000–0xFFFF`), dejando
  24,1 KB útiles con la pantalla activa. Resuelto por decisión de diseño **D34**: la ventana pasa a la
  página dedicada `0xF000–0xFFFF` (sombreado alineado a página) y esa página vuelve a ser RAM completa
  al deshabilitar la pantalla; `VmDisplay.reconfigure()` ahora exige que la ventana quepa entera en ella.
- **Rendimiento por mensaje** y comportamiento bajo carga concurrente (la ejecución es
  secuencial): **no medido**.

---

## 21. Registro de errores cometidos por el propio auditor

- **Hipótesis retirada (fuel):** sospeché que el *fuel* no se aplicaba porque el bucle principal
  solo hace `consumeFuel(1)` y el `grep` inicial no mostró comparaciones contra cero. Al leer
  `ExecutionContext.consumeFuel` (`:241-247`) comprobé que **lanza `OUT_OF_FUEL`** cuando es
  insuficiente: la cota **sí** es efectiva. La sospecha queda **descartada**; no figura como
  hallazgo.
- **Afirmación matizada (privilegios de lectura):** empecé considerando `IFLOW-AUD-033` una fuga
  de aislamiento; al verificar que las páginas kernel son *program-readable* por diseño
  (`PageTable.java:79-81`), la rebajé a Baja/Informativa. **Rebajada.**
- **Severidad corregida (color inválido):** contemplé que `ColorMode.valueOf` pudiera escapar al
  pipeline del servidor; al leer la red de seguridad `catch (RuntimeException) → REJECT`
  (`VmPolicyEngine.java:251-256`) comprobé que **no escapa**. Reclasificado de «Alta» a «Baja».
- **Sin arnés E2E:** no escribí un programa Java que ejecute el `.flow` que ensamblé. Declarado
  como limitación (§2.3) y compensado con la suite existente; **no** afirmo haber ejecutado el
  binario.

**Afirmaciones descartadas / no sostenidas:** (a) fuel no aplicado; (b) fuga de lectura por
privilegio; (c) crash del servidor por color inválido; (d) desalineación de longitud en
`COPY`/`MOVE` (verificado: 8 B por ambos lados).

---

## Apéndice A — Reproducción de las verificaciones

```bash
# Toolchain (no hay java en el PATH): JDK 21 real en /opt/javac/x64/21
export JAVA_HOME=/opt/javac/x64/21

# Suite de la VM (offline, todo desde ~/.gradle)
./gradlew :src:iflow:test --offline --rerun-tasks
#   -> BUILD SUCCESSFUL ; 383 PASSED, 0 FAILED, 0 SKIPPED

# Cobertura jacoco
#   -> 84 % instrucciones · 71 % líneas · 70 % ramas · 91 % métodos · 97 % clases

# Compilador (offline, node_modules ya presentes)
cd tools/iflow-compiler
npx tsc                                     # sin errores
node test/validation/run.js                 # 13/13 cases passed
node dist/cli.js examples/policy.flowasm -o /tmp/opencode/iflow-e2e/policy.flow
#   -> ✓ Assembled … (4209 bytes)

# Verificación del binario por ambos lados (campos + SHA-256)
python3 - <<'PY'
import hashlib
d=open('/tmp/opencode/iflow-e2e/policy.flow','rb').read()
clen=(d[6]<<8)|d[7]; nconst=(d[8]<<8)|d[9]; ncaps=d[10]
off=16+clen+2*nconst
for _ in range(ncaps): off=d.index(0,off)+1
print("hash OK:", hashlib.sha256(d[:off]).digest()==d[off:off+32],
      "| extra:", len(d)-off-32)
PY
#   -> hash OK: True | extra: 0

# Determinismo del ensamblador (5/5 byte-idénticos)
for f in test/firmware test/simple test/main_only test/irq test/bios_screen; do
  node dist/cli.js "$f.flowasm" -o "/tmp/opencode/e2e_$(basename $f).flow" >/dev/null
  cmp -s "/tmp/opencode/e2e_$(basename $f).flow" "$f.flow" && echo "$f OK" || echo "$f DIFIERE"
done

# IFLOW-AUD-035: setFatalError solo definido + usado en un test
grep -rn "setFatalError" --include=*.java src/
# IFLOW-AUD-036: cancel() solo desde tests
grep -rn "cancelled = true\|\.cancel()" --include=*.java src/
# IFLOW-AUD-031/032: la E/S no consulta la PageTable
grep -n "deviceFor\|d.writeByte\|touchesDevice" src/iflow/src/main/java/.../vm/memory/VmBus.java
# IFLOW-AUD-039: inmediato big-endian en el intérprete
grep -n "fetch(ctx, ip + 2) << 8" src/iflow/src/main/java/.../vm/execution/VmInterpreter.java
```

*Fin del informe.*

---

## Addendum — 2026-10-19 (posterior al commit auditado)

Este informe describe el árbol en el commit `84491c2`. Cambios posteriores que **resuelven** puntos
aquí registrados:

- **§20 · solapamiento de la pantalla (`0xE000`):** verificado y resuelto. La ventana por defecto
  80×25×2 = 4000 B sombreaba el 14 % de la RAM de usuario declarada (28 KB → 24,1 KB útiles con la
  pantalla activa). Decisión **D34** (ver `PLAN.md`): la ventana pasa a la página dedicada
  `0xF000–0xFFFF` y `VmDisplay.reconfigure()` exige que quepa entera en ella; la página vuelve a ser
  RAM completa cuando la pantalla está deshabilitada. De paso se cierra la posibilidad de apuntar la
  base a `0x7000`/`0x3000` y sombrear la E/S o los datos propios.
- **`IFLOW-AUD-040`:** medido el `.flow` ensamblado, el firmware termina en `0x029B` (668 B). El
  README era correcto y el comentario de `VmMemoryMap` (`0x0204`) se corrigió.
- **`IFLOW-AUD-031`:** decidido (opción 1 del usuario) **aceptar el contrato y hacerlo explícito** en
  lugar de añadir un portero. La apertura MMIO es espacio de dispositivo y no la gobierna la
  `PageTable`; la propiedad de página sigue protegiendo la memoria. Queda escrito en el javadoc de
  `VmMemoryMap.DEVICE_MMIO_BASE`, `VmBus` y `VmDevice`, y fijado con
  `VmDisplayTest.programMayDriveDeviceRegistersInsideTheKernelPage`. Ver ADR 2026-10-19 y `PLAN.md` D35.
- **`IFLOW-AUD-034`:** cerrado. Un código de decisión fuera de rango devuelve `INVALID_ARGUMENT` y
  journaliza un `REJECT`; `PolicyResultValidator.parseRouteDecision` mapea lo desconocido a `REJECT`.
- **`IFLOW-AUD-032`:** cerrado. La detección de dispositivo en transacciones de bloque usa solapamiento
  real de intervalo (`VmDevice.overlaps` / `SoftMMU.isDeviceRange`), no solo los extremos.
- **`IFLOW-AUD-042`:** cerrado. `ColorMode.of()` parsea sin excepciones; `PROPERTY_WRITE` valida el color
  (`INVALID_ARGUMENT`) y se retiran del journal las cuatro operaciones sin emisor.
- **`IFLOW-AUD-033`:** cerrado. `PageTable.translateRead(vaddr, privilege)` propaga el privilegio (mismo
  contrato de propiedad que la escritura) y `SoftMMU` deja de descartarlo en `readByte`/`readWord`/
  `readBlock` (incluidas las rutas de cruce de página y byte a byte).
- **`IFLOW-AUD-035`:** cerrado. El `catch (VmException)` del motor marca el error como fatal y rechaza
  por el validador (que descarta el journal parcial): la rama «error fatal» ya es alcanzable en producción.
- **`IFLOW-AUD-039`:** cerrado. `README.md` de `src/iflow` y de `tools/iflow-compiler` describen el
  **endianness mixto** (cabecera e inmediatos big-endian; datos y constantes en memoria little-endian).
- **`IFLOW-AUD-041`:** cerrado como **reservado**. El buffer de salida se documenta sin consumidor en
  producción (el resultado viaja por el journal) y utilizable como *scratch* por el programa.
- **`IFLOW-AUD-036`:** resuelto por **decisión**. No se añade vigilante ni presupuesto de tiempo: una
  política puede ejecutarse legítimamente largo (sleep, RTC, espera de una IRQ) y el *fuel* sigue siendo
  el único límite (determinista y ajeno al host). Lo que debe entregarse rápido es el **mensaje**, no la
  vida de la máquina. Documentado en `ExecutionContext` (sección *Cancellation*).
- **`IFLOW-AUD-043`:** resuelto por **decisión**. La igualdad exacta de `minAbiVersion` es deliberada:
  garantiza que el programa se construyó para esta ABI y descarta fallos inesperados en tiempo de
  ejecución. Documentado en `ProgramImageParser`.
- **Punto 10 (P4):** cerrado. `MESSAGE_CLONE`/`MESSAGE_ID` exigen las capabilities `message.clone`/
  `message.id` (deny-by-default, como el resto), y `tools/iflow-compiler/test/determinism/run.js`
  (enganchado a `npm test`) reensambla los 5 fixtures con el CLI real y exige byte-identidad con los
  `.flow` que ejecutan los tests del JVM.
- Sigue abierto: `037` (sección `debug` versionada) y `038` (magic number).

