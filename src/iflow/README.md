# iflow — VM 16-bit flat 64KB + SoftMMU 4KB

> **Estado**: ✅ Implementada y probada (383 tests)  
> **Tipo**: Módulo funcional (VM interpreted)  
> **Contrato**: Implementa `Router` SPI (`core-api`)

---

## Arquitectura

iflow v2 reemplaza el motor de reglas basado en SpEL/regions por una **máquina virtual interpretada de 16 bits** con espacio de direcciones plano de 64 KB y paginación por software (SoftMMU) de 4 KB.

### Componentes principales

| Componente | Responsabilidad |
|------------|-----------------|
| `VmPolicyEngine` | Motor principal: implementa `Router`, ejecuta bytecode `.flow` via `VmInterpreter` |
| `VmInterpreter` | CPU: fetch/decode/execute loop, ISA v2, fuel budget; maestro del bus. El *fetch* va por el bus/`SoftMMU` (permiso `EXECUTE` efectivo, ventana de página validada); es inmutable y se reutiliza por programa (AUD-023/029) |
| `SoftMMU` | MMU por software: 16 páginas de 4 KB, permisos R/W/X, traducción virtual→físico; almacén físico *copy-on-write* (snapshot inmutable + overlay por frame) y *fork* aislado disponible (AUD-029/D25) |
| `PhysicalMemory` | Almacén físico de 64 KB en frames de 4 KB; la memoria viva de la máquina es la del programa y persiste entre mensajes (D30) |
| `VmBus` | Bus de datos explícito: única vía de transacción de CPU y DMA hacia `SoftMMU`; lleva el `Privilege` del maestro y **decodifica MMIO**: si la dirección pertenece a un periférico, enruta la transacción al dispositivo en lugar de a la RAM (D31) |
| `VmDmaController` | Vía canónica de transferencia host↔VM (read/write/copy/move validados sobre el bus); maestro supervisor |
| `VmDevice` | Interfaz de esclavo de bus: un periférico que decodifica su propio rango y responde lecturas/escrituras (D31) |
| `VmDisplay` | Pantalla de texto mapeada en memoria (D31): celda de 2 bytes (carácter + atributo `[letra:4][fondo:4]`, estilo VERA/X16); registros de control MMIO; API de framebuffer para el host |
| `VmNvram` | NVRAM del BIOS (D32): ventana de almacenamiento persistente en la apertura MMIO para el estado que sobrevive entre mensajes (p. ej. la bandera de arranque en frío, la tabla de IRQ) |
| `VmPic` | Controlador de interrupciones (D33): esclavo MMIO con latch/máscara de 16 líneas, disparo por software (`RAISE`) y confirmación (`ACK`); el runtime lo consulta para despachar |
| `VmConfigLoader` | Carga binaria `.flow` (sin YAML/SpEL), fallback pass-through |
| `ProgramImage` | Imagen de programa inmutable (bytecode + constantes + capabilities + hash) |
| `VmException` | Excepciones tipadas con IP capture (INVALID_OPCODE, MEMORY_FAULT, OUT_OF_FUEL, etc.) |

### Memoria

- **Espacio plano**: 64 KB (0x0000–0xFFFF)
- **Paginación**: 16 páginas × 4 KB = 64 KB
- **Permisos por página**: READ (0x1), WRITE (0x2), EXECUTE (0x4). El CPU hace *fetch* por la
  MMU, así que una página de código sin `EXECUTE` falla con `PRIVILEGE_FAULT` en ejecución
  (no es un permiso nominal); las instrucciones se leen de memoria, no de la imagen de
  despliegue. La página se valida al entrar en ella y se sirve de una ventana de 4 KB
  (I-cache) en el contexto.
- **Propiedad de página** (`Privilege`): las páginas *kernel* (código/constantes) son
  legibles/ejecutables por el programa pero **solo escribibles por el supervisor** (host,
  cargador y DMA); un write del programa falla con `PRIVILEGE_FAULT` aunque el bit W esté
  presente. El cargador mapea código/constantes como kernel y baja W con `mprotect`.
- **Mapeo típico** (fuente única: `VmMemoryMap`):
  - Páginas 0-1: Código (R/X, **kernel**) — ROM de firmware (0x0000) + código de usuario (0x1000); en bare-metal sólo se usa la página 0
  - Página 2: Constantes (R, **kernel**) (0x2000)
  - Páginas 3-4: Datos/Heap (R/W) (0x3000–0x4FFF)
  - Páginas 5-6: Pila (R/W, crece hacia abajo desde 0x7000)
  - Página 7: Buffer entrada I/O (R/W) (0x7000)
  - Página 8: Buffer salida I/O (R/W) (0x8000) — **reservado**: no tiene consumidor en producción (el resultado viaja por el *modification journal*); el programa puede usarlo como *scratch* (AUD-041)
  - Páginas 9-15: RAM de usuario de propósito general (R/W) (0x9000–0xFFFF) — almacenamiento libre del programa; el runtime nunca la limpia. La página 15 (0xF000–0xFFFF) es además la **página de pantalla**: con la pantalla activa su framebuffer sombrea los primeros `cols×rows×2` bytes; al deshabilitarla, toda la página vuelve a ser RAM
  - Bloque de control MMIO de dispositivos: 256 B (0x0F00–0x0FFF) **dentro de la página del BIOS** — `0x0F00–0x0F0F` banco de registros de la pantalla (D31), `0x0F10–0x0FEF` NVRAM del BIOS (D32) y `0x0FF0–0x0FFF` controlador de interrupciones (D33); el *interconnect* lo decodifica antes que la ROM/RAM. Es **espacio de dispositivo**, no memoria: no lo gobiernan los permisos ni la propiedad de página, y el programa (dominio `PROGRAM`) puede gobernar sus periféricos por diseño (contrato de `VmMemoryMap.DEVICE_MMIO_BASE`, AUD-031). Reutiliza la cola libre del ROM (el firmware acaba en 0x029B), así no recorta RAM de usuario
- **Contrato de entrada (engine)**: antes de ejecutar, el engine materializa el mensaje en
  `0x7000` (campos con longitud explícita) para los servicios de buffer; las propiedades
  siguen consultándose bajo demanda vía `HOST`/`HostContext` (AUD-021).
- **Memoria persistente — una sola máquina viva (D30)**: el motor mantiene **una única `SoftMMU`
  por programa** (la memoria viva de la máquina). Cada mensaje se ejecuta **directamente** sobre
  ella (sin *fork* por mensaje) y las ejecuciones se **serializan** en el único core, así que lo que
  el programa deja en memoria sigue ahí en el mensaje siguiente. La persistencia es **implícita**:
  el runtime solo limpia los buffers de E/S (`clearBlock`), nunca los datos; es el programa quien
  decide cuándo reutilizar o borrar. Un contador en `0x9000+` (p. ej. un entero de 4 bytes)
  simplemente funciona. La MMU conserva su capacidad *copy-on-write* (`SoftMMU(SoftMMU)`,
  `PhysicalMemory.fork`) para obtener copias aisladas (tests/snapshots), pero el camino por
  mensaje ya no forka.

### Pantalla de texto (D31)

La salida visual es **hardware**, no código de depuración en el firmware: un periférico de texto
mapeado en memoria (`VmDisplay`). Cada escritura que la CPU o el DMA hacen a la ventana del
framebuffer aparece en pantalla.

- **Celda (2 bytes)**: byte bajo = **carácter** (8 bits); byte alto = **atributo**
  `[letra:4][fondo:4]` (nibble alto = color de letra, nibble bajo = color de fondo), estilo VERA/X16.
- **Ventana**: `cols × rows × 2` bytes en una base elegida por el BIOS (por defecto `0xF000`).
  Debe caber entera en la **página de pantalla** (`0xF000–0xFFFF`, 4 KB); el *interconnect* (`VmBus`)
  decodifica la ventana y enruta las escrituras al dispositivo, sombreando la RAM que hubiera debajo.
  Esa página sigue mapeada R/W como RAM de usuario: si el programa deshabilita la pantalla, recupera
  los 4 KB completos como almacenamiento normal.
- **Registros MMIO** (banco de 16 B en `0x0F00`, dentro de la memoria del BIOS): `BASE` (0x00), `COLS` (0x02), `ROWS` (0x04),
  `CTRL` (0x06: bit0 = habilitar, bit1 = limpiar), `CURSOR` (0x08), `STATUS` (0x0A, solo lectura). El
  BIOS los escribe al arrancar (D32); reconfigurable en caliente. Una ventana que no quepa entera en
  la página de pantalla se rechaza (la pantalla queda deshabilitada).
- **Consumo por el host**: `VmPolicyEngine.display()` expone el framebuffer (`renderText()`,
  `renderTextTrimmed()`, `renderAnsi()`, `cellChar(...)`, `cellAttribute(...)`) para datos de
  producción y tests. En builds de **debug** (`logger.debug=true`) se vuelca la pantalla al log tras
  cada mensaje; el volcado está tras `DebugConfig.DEBUG`, así que es apagable y de coste cero cuando
  está desactivado.

### BIOS activo (D32)

Con el firmware ON la máquina **resetea en el BIOS**, no en el `main`: el `entryPoint` de la imagen es
la rutina `fw_reset` de la ROM, que encadena con el `main` del usuario. Así el hardware queda listo sin
que el programa tenga que configurarlo.

- **POST**: `fw_reset` detecta arranque **en frío vs. caliente** con una bandera persistente en la
  NVRAM. En frío limpia la **RAM volátil de datos** (0x3000–0x4FFF) y **configura la pantalla** por
  MMIO (`BASE=0xF000`, `COLS=80`, `ROWS=25`, `CTRL=enable`); después marca el arranque y encadena con
  `main`. En caliente salta directo a `main`.
- **«Limpiar memoria» sin romper D30**: el BIOS **nunca** toca la RAM de propósito general
  (0x9000+) ni la memoria del programa en el camino caliente, así que la persistencia implícita de D30
  se conserva; sólo el primer mensaje ve el POST.
- **NVRAM (`VmNvram`)**: ventana persistente en `0x0F10–0x0FEF` (dentro de la apertura MMIO). Como el
  engine mantiene una única máquina por programa, el dispositivo vive toda la vida de la máquina: lo
  escrito en un mensaje sigue ahí en el siguiente.
- El `main` ya no necesita configurar la pantalla; puede reconfigurarla en caliente si quiere.

### Interrupciones (D33)

El emulador **no** hardcodea la tabla de interrupciones: el BIOS la publica en su NVRAM y el runtime la
usa para despachar. Además de la tabla hay **fuentes de interrupción por E/S**.

- **Controlador (`VmPic`)**: banco de registros MMIO de 16 B en `0x0FF0–0x0FFF`, al final de la
  apertura. 16 líneas; registros `PENDING` (lectura), `ENABLE` (rw, máscara), `RAISE` (escritura:
  disparo software), `ACK` (escritura: completa la línea), `IN_SERVICE` (lectura) y `CURRENT`
  (lectura). Las líneas llegan **enmascaradas**: no se despacha nada hasta habilitar `ENABLE`.
- **Tabla**: `[boot:2][magic:2][count:2][handler_0:2]…[handler_n:2]` en la NVRAM. En el POST el BIOS
  publica `magic`, `count` y una entrada por línea apuntando a su stub por defecto (`fw_irq_stub`,
  que hace `ACK` y `RET`). El programa puede sobreescribir su entrada por MMIO.
- **Entrega tipo hardware**: en cada frontera de instrucción el intérprete consulta la línea pendiente,
  habilitada y no en servicio de menor número. Si hay, guarda el **estado completo de la CPU**
  (registros y `IP`/`SP`/`FP`/`FL`, más la profundidad de llamada), empuja la dirección de retorno en la
  pila (como `CALL`) y salta al manejador. El `RET` que cierra su marco restaura el estado, así que la
  interrupción es **transparente** al código interrumpido; no hay `IRET` ni el manejador salva
  registros. La línea queda *en servicio* (sin reentrada) hasta el `ACK`.
- **Fuentes de E/S**: (1) **llegada de mensaje** (IRQ 0, la levanta el runtime al preparar el buffer de
  entrada), (2) **software** vía `RAISE`, y (3) **fin de servicio HOST** (IRQ 1) cuando el servicio
  devuelve un error.
- El assembly expone las constantes `PIC_*`, `IRQ_TABLE_ENTRIES`, `IRQ_TABLE_MAGIC` e `IRQ_LINES`
  para que el programa instale su manejador sin conocer las direcciones a mano.

### ISA v2 (resumen)

| Grupo | Instrucciones |
|-------|---------------|
| Data transfer | `LI`, `MOV`, `LDB`, `LDW`, `STB`, `STW`, `COPY`, `MOVE` |
| Memoria indirecta | `LDBI`, `LDWI`, `STBI`, `STWI` (ISA v2; dirección tomada de un registro) |
| Aritmética | `ADD`, `SUB`, `MUL`, `DIV`, `MOD` |
| Lógica | `AND`, `OR`, `XOR`, `NOT` |
| Shifts | `SHL`, `SHR`, `SAR` |
| Comparación | `CMP`, `CMPU` |
| Control | `JMP`, `JZ`, `JNZ`, `JC`, `JV`, `CALL`, `RET` |
| Servicios | `HOST` |
| Control | `HALT`, `TRAP` |

**Formato de instrucción**: opcode de 8 bits que determina la longitud (1-8 bytes). Los **operandos
inmediatos de 16 bits van en big-endian** (`[hi, lo]`), igual que la cabecera del contenedor; el
opcode y los campos de registro ocupan 1 byte. El *endianness* de los **datos en memoria** es otro:
ver la cabecera y las instrucciones `LDW`/`STW`.

### Instrucción `HOST` (servicios genéricos) — ABI v2

Convención de registros (estilo syscall):

**Entrada**
- `R0` = `op` — service ID (16-bit)
- `R1` = `options` — parámetro del servicio (`(scope<<8)|propId`, decisión, key…)
- `R2` = `base` — vaddr del buffer de argumentos (dirección **completa**)
- `R3` = `length` — bytes (entrada: pedidos · salida: transferidos)
- `R4` — reservado para el resultado escalar (extras, si los hubiera, desde `R5`)

**Retorno**
- `R0` = `status` — código numérico (tabla abajo)
- `R1`, `R2` — sin cambios
- `R3` = bytes transferidos (lecturas: bytes escritos por el emulador; escrituras: bytes consumidos)
- `R4` = resultado escalar del servicio (p. ej. `0|1` en PERMISSION_CHECK, la decisión en ROUTE_DECISION)

`length` es **bidireccional** y no existe operando `offset`: la base es la dirección completa.

**Códigos de `status`**

| Valor | Status | | Valor | Status |
|---|---|---|---|---|
| `0` | OK | | `5` | UNSUPPORTED |
| `1` | NOT_FOUND | | `6` | CONFLICT |
| `2` | DENIED | | `7` | HOST_FAILURE |
| `3` | INVALID_ARGUMENT | | `8` | PARTIAL |
| `4` | BUFFER_TOO_SMALL (`R3` = bytes requeridos) | | | |

Servicios estándar (15 IDs; **todos registrados** — cualquier otro responde `status=5` UNSUPPORTED):

| ID | Servicio | Capability |
|----|----------|------------|
| 0x0100 | PROPERTY_READ | `property.read` |
| 0x0101 | PROPERTY_WRITE | `property.write` |
| 0x0102 | PROPERTY_EXISTS | `property.read` |
| 0x0200 | PERMISSION_CHECK | `permission.check` |
| 0x0201 | PERMISSION_QUERY | `permission.check` |
| 0x0300 | ROUTE_DECISION | `routing.decide` |
| 0x0301 | ROUTE_REDIRECT | `routing.decide` |
| 0x0302 | ROUTE_RATE_LIMIT | `routing.decide` |
| 0x0400 | TRANSFORM_APPLY | `transform.apply` |
| 0x0500 | STATE_READ | `state.read` |
| 0x0501 | STATE_WRITE | `state.write` |
| 0x0600 | CHANNEL_RESOLVE | `channel.resolve` |
| 0x0601 | CHANNEL_POLICY | `channel.resolve` |
| 0x0700 | MESSAGE_CLONE | `message.clone` |
| 0x0701 | MESSAGE_ID | `message.id` |

`TRANSFORM_APPLY` (0x0400): el byte bajo de `options` elige el tipo — `0` resuelve
placeholders (hook `placeholderResolver`) y `1` detecta el idioma (hook `translationDetector`);
sin hook inyectado responde `UNSUPPORTED`. `PROPERTY_READ` usa ambos hooks para resolver el
contenido y autodetectar el idioma de origen cuando falta.

Servicios añadidos en el lote 6 (D26):

- `PROPERTY_EXISTS` (0x0102): no transfiere bytes; devuelve `R4=1` si la propiedad existe.
- `PERMISSION_QUERY` (0x0201): como `PERMISSION_CHECK` (buffer `actor\0nodo`) pero reporta el
  resultado en `R0` (`OK`/`DENIED`) además de `R4=1/0`.
- `ROUTE_REDIRECT` (0x0301): buffer=destino, byte bajo de `options` = `0` actor / `1` canal;
  escribe la entrada `REDIRECT_TARGET`/`REDIRECT_CHANNEL` del *journal*.
- `ROUTE_RATE_LIMIT` (0x0302): buffer=clave; calcula el *backoff* con el `RateLimiter` inyectado
  y escribe `RATE_LIMIT_BACKOFF`; sin limiter → `UNSUPPORTED`.
- `CHANNEL_POLICY` (0x0601): buffer=nombre de canal (C string), byte bajo de `options` =
  `0` envío / `1` recepción / `2` tasa; escribe la política en el buffer; desconocido → `NOT_FOUND`.
- `MESSAGE_CLONE` (0x0700): `R2=dst`, `R3=n`; copia `n` bytes del payload serializado de entrada
  (`0x7000`) al destino vía DMA.
- `MESSAGE_ID` (0x0701): escribe el id del mensaje en el buffer.

`options` para servicios de propiedad: `(scope << 8) | propId`, con `propId` en los **8 bits bajos** (namespaceado por `scope`).
`scope`: `0x01` MESSAGE · `0x02` EMITTER · `0x03` RECIPIENT · `0x04` CHANNEL · `0x05` SERVER.

### Fuel Budget

- Base: 1 unidad/instrucción
- Servicios: coste base + coste variable × bytes
- Límite default: 100,000 unidades/evaluación
- Excepción: `OUT_OF_FUEL` con IP actual

### Formato binario `.flow`

```
Header (16 bytes):
  u8  formatVersion     = 1
  u8  isaVersion        = 2
  u8  minAbiVersion     = 2
  u8  minFirmwareVersion= 1
  u16 entryPoint        (big-endian)
  u16 codeLength        (big-endian)
  u16 constantsLength   (big-endian)
  u8  capabilitiesCount
  u8[5] padding

Code section: codeLength bytes (los operandos inmediatos son big-endian [hi, lo])
Constants: constantsLength × u16 (big-endian en el fichero; se materializan
           little-endian en memoria para que LDW las lea de forma natural)
Capabilities: null-terminated strings
Hash: 32 bytes (SHA-256 of everything before the hash field)
```

> **Endianness mixto (deliberado).** La cabecera del contenedor y los operandos inmediatos de las
> instrucciones son **big-endian**; las palabras de **datos** en memoria (RAM y constantes ya
> materializadas) son **little-endian**. Quien escriba un ensamblador o un parser nuevo debe
> respetarlo: asumir «todo little-endian» decodifica mal la cabecera y los inmediatos.

El parser exige que `minAbiVersion` **coincida exactamente** con el ABI del runtime
(hoy v2): un `.flow` construido para otra convención de llamada se rechaza al cargar.

### Compilación

Los programas se escriben en `.flowasm` y se compilan a `.flow` binario usando **iflow-compiler** (TypeScript):

```bash
cd tools/iflow-compiler
npm run build
node dist/cli.js programa.flowasm -o programa.flow
```

Ver [`tools/iflow-compiler/README.md`](../../tools/iflow-compiler/README.md) para detalles del ensamblador.

### Integración con iflow (runtime Java)

```java
// VmPolicyEngine implementa Router (core-api SPI)
VmPolicyEngine engine = new VmPolicyEngine(channelRegistry, permissionChecker, rateLimiter);

// Hot-reload de programa
engine.setProgram(programImage);

// Routing
RouteOutcome outcome = engine.route(message, recipient);
```

### Testing

| Test | Cobertura |
|------|-----------|
| `VmInterpreterTest` | Todas las instrucciones ISA (data transfer, aritmética, lógica, shifts, saltos, CALL/RET, memoria indirecta) + fetch por MMU (EXECUTE efectivo, lectura desde memoria) |
| `SoftMMUTest` | Traducción, permisos, fetch, bloques, aislamiento de copias, fork/`clearBlock` copy-on-write |
| `PhysicalMemoryTest` | Copy-on-write: fork comparte snapshot, copia por frame, omite escrituras sin cambio |
| `PageTableTest` | Mapeo, permisos, mprotect, fork copy-on-write aislado |
| `VmDmaControllerTest` | 24 tests DMA (cross-page, perms, overlap) |
| `ExecutionContextTest` | Pool/registros y cableado del bus (D18) y del DMA (D17) |
| `ExecutionContextPoolTest` | Pool, reset entre ejecuciones, cota de retención bajo concurrencia |
| `HostServiceRegistryTest` | ABI v2 de los 15 servicios HOST, capabilities, journal, transferencias vía DMA, copy-on-write del buffer |
| `VmPolicyEngineTest` | Integración Router + VM; concurrencia del intérprete compartido por programa; `CHANNEL_POLICY` extremo a extremo; persistencia de la memoria de la máquina entre mensajes (D30); pantalla mapeada en memoria (D31) |
| `VmDisplayTest` | Periférico de vídeo: decodificación de la ventana y del banco de registros, geometría, carácter/atributo, bloques, limpieza y render ANSI (D31) |
| `VmNvramTest` | NVRAM del BIOS: decodificación de ventana, R/W byte/palabra/bloque, ventana por defecto (D32) |
| `VmPicTest` | Controlador de interrupciones: decodificación, latch/máscara, prioridad, servicio/ACK, registros MMIO (D33) |
| `VmIntegrationTest` | Programas multi-instrucción |
| `VmConfigLoaderTest` | Carga binario, fallback pass-through |
| `FirmwareIntegrationTest` | **Ejecución firmware completo** (arithmetic, logic, shifts, jumps, memory, block ops, HOST services, loops) y POST del BIOS que configura la pantalla (D32) |
| `BiosBootTest` | End-to-end del BIOS activo: resetea en `fw_reset`, configura la pantalla, encadena a `main` y toma el camino caliente en el segundo mensaje (D32) |
| `IrqDeliveryTest` | End-to-end de IRQ: el BIOS publica la tabla, `main` instala su manejador, habilita y dispara la línea; el manejador corre y la interrupción es transparente (registros restaurados) (D33) |

**Total: 383 tests passing**

### Migración desde v1 (SpEL/regiones)

| v1 (legacy) | v2 (actual) |
|-------------|-------------|
| SpEL expressions | Bytecode `.flow` binario |
| Regiones + handles | Flat 64KB + SoftMMU 4KB pages |
| `Rule`/`TransformOp` | Bytecode compilado externamente |
| `VmProgramCompiler` (JIT) | `iflow-compiler` (TypeScript, offline) |
| YAML config | Solo binario `.flow` |
| `setRules()` | `setProgram()` + `UnsupportedOperationException` |

### Entry Points

| Clase | Responsabilidad |
|-------|-----------------|
| `IflowModule` | Registra `VmPolicyEngine` como `Router` SPI |
| `VmPolicyEngine` | Implementa `Router`, ejecuta VM, valida resultado |
| `VmPolicyEngine.setProgram()` | Hot-reload de `ProgramImage` |
| `VmConfigLoader.loadProgram()` | Carga `policy.flow` o fallback pass-through |

### Files

```
src/iflow/
├── src/main/java/me/majhrs16/suite/iflow/
│   ├── Router.java                    # SPI interface
│   ├── RouteDecision.java
│   ├── RouteOutcome.java
│   ├── channel/                       # ChannelRegistry, RateLimiter, PermissionChecker
│   ├── target/                        # PolicyTarget
│   └── vm/
│       ├── VmPolicyEngine.java        # Router impl + VM execution
│       ├── VmConfigLoader.java        # Binary .flow loader
│       ├── PassThroughProgram.java    # Fallback program (single source, AUD-027)
│       ├── isa/
│       │   ├── Opcode.java
│       │   ├── ProgramImage.java
│       │   ├── RegisterFile.java
│       │   └── VmException.java
│       ├── execution/
│       │   ├── ExecutionContext.java
│       │   ├── ExecutionContextPool.java
│       │   ├── VmInterpreter.java
│       │   └── ModificationJournal.java
│       ├── memory/
│       │   ├── SoftMMU.java
│       │   ├── PhysicalMemory.java    # Copy-on-write physical store (AUD-029/D25)
│       │   ├── PageTable.java
│       │   ├── PageEntry.java
│       │   ├── Privilege.java
│       │   ├── VmMemoryMap.java
│       │   ├── VmBus.java
│       │   └── VmDmaController.java
│       ├── service/
│       │   ├── HostContext.java
│       │   ├── HostServiceRegistry.java
│       │   └── ServiceResult.java
│       └── loader/                    # (vacío - legacy)
│
├── src/test/java/...                  # 383 tests
└── build.gradle
```

---

## Referencias

- [`HDL.md`](HDL.md) — mapeo a HDL: módulos, buses, señales, flujo de datos (D28)
- [`docs/prompts/diseno/iflow-cpu-implement.md`](../../docs/prompts/diseno/iflow-cpu-implement.md) — Especificación técnica completa (ABI v2)
- [`tools/iflow-compiler/`](../../tools/iflow-compiler/) — Ensamblador TypeScript (.flowasm → .flow)
- [`docs/prompts/diseno/iflow-cpu-design.md`](../../docs/prompts/diseno/iflow-cpu-design.md) — Diseño del CPU virtual (memoria, rings, privilegios)