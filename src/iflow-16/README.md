# iflow-16 — VM 16-bit flat 64KB + SoftMMU 4KB

> **Línea 2026-11-15:** este módulo es **`iflow-16`**, la línea legacy de 16 bits y 64 KB. La
> línea moderna de 32 bits es [`iflow-32`](../iflow-32/) (ADR-037); la línea
> **`iflow-wasm`** (WebAssembly + Chicory) está en [`src/iflow-wasm/`](../iflow-wasm/). Ninguna
> de las dos hereda este ISA, este ABI `HOST` ni el modelo BIOS/kernel.

> **Estado**: ✅ Implementada y probada (411 tests)
>
> **Tipo**: Módulo funcional (VM interpreted)
> **Contrato**: Implementa `Router` SPI (`core-api`) y proporciona la capability `router`

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
- **Propiedad de página** (`Privilege`): las páginas *kernel* (BIOS, bootloader/kernel y
  constantes) son legibles/ejecutables por el programa pero **solo escribibles por el
  supervisor** (host, cargador y DMA); un write del programa falla con `PRIVILEGE_FAULT`
  aunque el bit W esté presente. El cargador mapea esos segmentos como kernel; `PROGRAM`
  se mapea como *user-owned*, se escribe con W durante la carga y termina en R/X sin W.
- **Mapeo típico** (fuente única: `VmMemoryMap`):
  - Páginas 0-1: Código (R/X) — BIOS **kernel** en 0x0000 y `PROGRAM` **user-owned** en 0x1000; en bare-metal sólo se usa la página 0
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
| `4` | BUFFER_TOO_SMALL (`R3` = bytes requeridos) | | `9` | TIMEOUT (puente sync) |
| | | | `10` | RATE_LIMITED (`R4` = retry ms) |

Servicios estándar (17 IDs; **todos registrados** — cualquier otro responde `status=5` UNSUPPORTED):

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
| 0x0800 | SYNC_SURFACE | `sync.surface` |
| 0x0801 | SYNC_INVOKE | `sync.invoke` |

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

Puente sync (ADR-036 §16.7). `SYNC_SURFACE` (0x0800) y `SYNC_INVOKE` (0x0801) son servicios
genéricos: no hay una instrucción ni un servicio por acción. Ambos usan el `TlvCodec` compartido de
`core-api` y el contrato de cable `SyncBridgeCodec`, con la misma codificación que los imports
`sync_surface`/`sync_invoke` de `iflow-wasm`.

- Petición y respuesta van por `R2`/`R3` y se transfieren con DMA. La respuesta **sobrescribe** la
  petición; al salir, `R3` son los bytes escritos. Si no cabe, `BUFFER_TOO_SMALL` deja el tamaño
  requerido en `R3`.
- `SYNC_SURFACE`: petición = TLV string con el backend (vacío = todos); respuesta = TLV list de
  superficies (`backend`, `contractVersion`, `actions`).
- `SYNC_INVOKE`: petición = TLV map `backend`/`action`/`args`; respuesta = TLV map con `status` y
  `result` o `reason`.
- La identidad es `Principal.Program(SHA-256)`: se toma de `ProgramImage.hash()`. Sin hash no se
  inventa identidad y la llamada queda `UNSUPPORTED`.
- El namespace decide el despachador: un sink registrado usa `SyncBus.invoke`; el resto usa
  `SyncBus.invokeHost`. `textformatter.channel.set` es una acción de host.
- Una acción que devuelve `Ok(Message)` muta el mensaje de trabajo vía journal y el programa recibe
  `OK` con `result = null`. Una segunda mutación de canal en la misma unidad es `CONFLICT`.
- Sin `SyncBus` instalado, ambos servicios responden `UNSUPPORTED`. El bus se resuelve por
  invocación, de modo que el orden de activación de módulos no importa.

`options` para servicios de propiedad: `(scope << 8) | propId`, con `propId` en los **8 bits bajos** (namespaceado por `scope`).
`scope`: `0x01` MESSAGE · `0x02` EMITTER · `0x03` RECIPIENT · `0x04` CHANNEL · `0x05` SERVER.

### Datos del entorno Minecraft y límite de 64 KB

- `SCOPE_EMITTER` y `SCOPE_RECIPIENT` exponen `name`, `uuid` y `language` del emisor y del
  destinatario del mensaje actual; no se copia el objeto completo del jugador.
- `SCOPE_SERVER` está reservado: el ABI v2 no define todavía propiedades de servidor.
- Para listar jugadores o consultar el entorno se usa el puente genérico `SYNC_INVOKE`
  (`player.list` en los backends `bukkit`/`fabric`). El host devuelve un TLV en el buffer `R2`;
  `R3` es la capacidad de entrada y, a la salida, los bytes escritos. La respuesta no se trunca:
  si no cabe, `BUFFER_TOO_SMALL` y `R3` lleva el tamaño requerido para reintentar.
- La lista se materializa **solo** en el buffer indicado, no se precarga el entorno entero. Con
  nombres de unos 16 B, UUID (36 B) y mundo más el overhead TLV, el techo práctico son unos pocos
  cientos de entradas. Los 64 KB no permiten cargar el censo completo de una network grande; para
  eso está la línea `iflow-32`.
- Regresión: `HostServiceRegistryTest` cubre una respuesta `player.list` que cabe y otra que no,
  comprobando que el límite se reporta explícitamente y no se produce un éxito parcial.

### Límites de la cola de rate limit (24 KB)

La cola de `RATE_LIMIT` vive en el host (`BoundedRateLimitQueue`), no en la VM: guarda unidades
renderizadas, no handles de 16 bits. Aun así, el presupuesto de la VM se puede acotar con la RAM
de usuario disponible:

| Representación por entrada | Bytes | Entradas en 24 KB |
|----------------------------|------:|------------------:|
| Handle de 16 bits | 2 | 12.288 |
| Handle + generación de 16 bits | 4 | 6.144 |
| Índice (2) + deadline (4) + estado (1) + flags (1) | 8 | 3.072 |
| Lo anterior + generación (2) | 10 | 2.457 |
| UUID binario | 16 | 1.536 |
| UUID como texto hexadecimal | 32 | 768 |

- La RAM de usuario son 28 KB; con la pantalla activa quedan 24 KB (24.576 B). El cálculo de
  24.000 B / 32 B = 750 es el caso conservador de guardar el UUID completo como texto.
- Una cola real de la VM no debe guardar UUID completos: guarda handles más deadline, estado y
  flags. Con 8–10 B por entrada caben unas 2.457–3.072 entradas; el array de handles de 16 bits
  llega a 12.288.
- La generación evita ABA cuando el host reutiliza un slot. El UUID y el contenido del mensaje
  se resuelven en el host, fuera de la RAM de la VM.
- El límite de 750 de `BoundedRateLimitQueue` es un techo de seguridad del host alineado con el
  caso conservador de UUID-texto; no es una medición del heap de la VM ni un límite del ABI.

### Fuel Budget

- Base: 1 unidad/instrucción
- Servicios: coste base + coste variable × bytes
- Límite default: 100,000 unidades/evaluación
- Excepción: `OUT_OF_FUEL` con IP actual

### Formato binario `.flow`

```
Header (32 bytes, big-endian):
  u32 magic             = 0x464C4F57 ("FLOW")
  u8  formatVersion     = 2
  u8  isaVersion        = 2
  u8  minAbiVersion     = 2
  u8  minFirmwareVersion= 1
  u16 entryPoint
  u16 tocOffset
  u16 tocEntryCount     (1..16)
  u16 headerFlags       (0)
  u32 layoutMagic       = 0x524F4D32 ("ROM2")
  u32 payloadSize       (bytes before the final hash)
  u32 reserved          (0)
  u32 reserved          (0)

TOC entry (16 bytes, repeated tocEntryCount times):
  u8  type              (1 BIOS, 2 BOOTLOADER, 3 KERNEL, 4 PROGRAM,
                         5 CONSTANTS, 6 CAPABILITIES, 7 DEBUG)
  u8  flags             (R=1, W=2, X=4, USER=8)
  u16 virtualAddress
  u32 fileOffset        (absolute from the start of the file)
  u16 size              (stored payload bytes)
  u16 memSize           (bytes reserved in memory; metadata segments use 0)
  u32 reserved          (0)

Payload: one stored section per TOC entry, in TOC order
Hash: 32 bytes (SHA-256 of everything before the hash field)
```

The compiler emits `BIOS` at `0x0000`, `PROGRAM` at a page-aligned image
address (`0x1000` with firmware, `0x0000` in bare-metal) and `CONSTANTS` at
`0x2000`. `CAPABILITIES` and `DEBUG` are metadata and are never loaded. The
loader writes each loadable payload at its virtual address, skips the MMIO
window, zero-fills any `memSize > size` tail and applies the declared
permissions at the end. `PROGRAM` is `R/X` user-owned, `BIOS` is `R/X`
kernel-owned and `CONSTANTS` is `R`.

> **El magic existe para rechazar pronto** (auditoría `IFLOW-AUD-038`): un fichero que no empieza
> por `FLOW` se descarta como «not a .flow» al instante, en lugar de fallar más tarde con un error
> de versión o de hash confuso. El `layoutMagic` cumple la misma función con el layout: un `.flow`
> del contenedor anterior de 20 bytes se rechaza en vez de leerse con un segundo parser. El cargador
> sigue siendo tolerante: si el `policy.flow` está corrupto o no es del formato, arranca con el
> programa pass-through.
>
> **ROM2 (2026-11-16).** Sustituye el contenedor monolítico de 20 bytes por una imagen con TOC y
> segmentos tipados. Los `.flow` antiguos no se migran en runtime: **hay que recompilar** con el
> ensamblador actual.

> **Endianness mixto (deliberado).** La cabecera, el TOC y los operandos inmediatos de las
> instrucciones son **big-endian**; las palabras de **datos** en memoria (RAM y el payload de
> `CONSTANTS`) son **little-endian**. El payload cargable se copia tal cual, así que `CONSTANTS`
> se serializa ya en orden de memoria. Quien escriba un ensamblador o un parser nuevo debe
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
| `HostServiceRegistryTest` | ABI v2 de los 17 servicios HOST, capabilities, journal, transferencias vía DMA, copy-on-write del buffer; puente sync (superficies, filtro por backend, mapeo de estados, petición malformada) |
| `PassThroughProgramTest` | La política oficial no declara `sync.invoke` ni `sync.surface` (ADR-036 §16.7) |
| `VmPolicyEngineTest` | Integración Router + VM; concurrencia del intérprete compartido por programa; `CHANNEL_POLICY` extremo a extremo; persistencia de la memoria de la máquina entre mensajes (D30); pantalla mapeada en memoria (D31); puente sync (mutación, conflicto y `UNSUPPORTED` sin bus) |
| `VmDisplayTest` | Periférico de vídeo: decodificación de la ventana y del banco de registros, geometría, carácter/atributo, bloques, limpieza y render ANSI (D31) |
| `VmNvramTest` | NVRAM del BIOS: decodificación de ventana, R/W byte/palabra/bloque, ventana por defecto (D32) |
| `VmPicTest` | Controlador de interrupciones: decodificación, latch/máscara, prioridad, servicio/ACK, registros MMIO (D33) |
| `VmIntegrationTest` | Programas multi-instrucción |
| `VmConfigLoaderTest` | Carga binario, fallback pass-through |
| `FirmwareIntegrationTest` | **Ejecución firmware completo** (arithmetic, logic, shifts, jumps, memory, block ops, HOST services, loops) y POST del BIOS que configura la pantalla (D32) |
| `BiosBootTest` | End-to-end del BIOS activo: resetea en `fw_reset`, configura la pantalla, encadena a `main` y toma el camino caliente en el segundo mensaje (D32) |
| `IrqDeliveryTest` | End-to-end de IRQ: el BIOS publica la tabla, `main` instala su manejador, habilita y dispara la línea; el manejador corre y la interrupción es transparente (registros restaurados) (D33) |

**Total: 411 tests passing**

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
| `Iflow16Module` | Registra `VmPolicyEngine` como `Router` SPI |
| `Iflow16Activator` | Activa el motor y publica `IflowBackend` (`iflow-16`) |
| `VmPolicyEngine` | Implementa `Router`, ejecuta VM, valida resultado |
| `VmPolicyEngine.setProgram()` | Hot-reload de `ProgramImage` |
| `VmConfigLoader.loadProgram()` | Carga `policy.flow` o fallback pass-through |

### Files

```
src/iflow-16/
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
├── src/test/java/...                  # 411 tests
└── build.gradle
```

---

## Referencias

- [`HDL.md`](HDL.md) — mapeo a HDL: módulos, buses, señales, flujo de datos (D28)
- [`docs/prompts/diseno/iflow-cpu-implement.md`](../../docs/prompts/diseno/iflow-cpu-implement.md) — Especificación técnica de referencia de la línea de 32 bits (`iflow-32`); la línea 16-bit conserva el ISA/formato v2 descrito aquí
- [`tools/iflow-compiler/`](../../tools/iflow-compiler/) — Ensamblador TypeScript (.flowasm → .flow)
- [`docs/prompts/diseno/iflow-cpu-design.md`](../../docs/prompts/diseno/iflow-cpu-design.md) — Diseño del CPU virtual (memoria, rings, privilegios)