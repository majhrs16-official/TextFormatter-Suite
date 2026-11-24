# iflow-32 — contrato de máquina, ISA y ABI `HOST` v3

> **Estado:** vigente y congelado. **Autoridad:** detalle normativo de ADR-037. Si este documento
> contradice el ADR, prevalece el ADR y este documento se corrige. **Ámbito:** máquina de 32 bits,
> ISA v3, MMU, MMIO, tiempo, scheduler, multinúcleo, ABI `HOST` v3, ROM `.flow` v3 y criterios de
> validación. Las referencias `ADR-037 §N` se resuelven en la sección §N de este documento.

## 1. Modelo de ejecución y registros

1. La máquina es de **32 bits**: registros, direcciones, desplazamientos, tamaños e inmediatos.
   El espacio virtual y el físico son de 32 bits; el máximo arquitectónico es 4 GiB. La RAM real es
   dispersa y acotada por VM.
2. **Little-endian único** en ROM, cabecera, TOC, segmentos, instrucciones, memoria y MMIO. No hay
   conversión de orden en el loader ni en el bus.
3. Hay **dieciséis registros de propósito general** de 32 bits:
   - `R0`–`R12`: uso general.
   - `R13` (`FP`): frame pointer.
   - `R14` (`SP`): stack pointer.
   - `R15` (`LR`): link register.
4. `PC` es un registro arquitectónico de 32 bits no direccionable como GPR. `FLAGS` es un registro
   arquitectónico de 32 bits con `Z` (bit 0), `N` (bit 1), `C` (bit 2) y `V` (bit 3); el resto de
   bits es cero. `FLAGS` se lee y escribe con `CSRR`/`CSRW` desde modo supervisor.
5. La pila es **full-descending**: `PUSH` resta 4 a `SP` y almacena; `POP` carga y suma 4. La
   alineación de pila es de 4 bytes; 8 bytes para valores de 64 bits y llamadas que los usen.
6. `CALL` escribe la dirección de retorno en `R15` y salta; `RET` salta a `R15`. No hay pila de
   retorno automática. Una función que llame a otra debe preservar `R15`.
7. El ABI C de ADR-023 se mantiene: argumentos en pila de derecha a izquierda, limpieza del
   llamador, retorno en `R0` o `R1:R0`, `R0`–`R12` y `R15` caller-saved, `R13`/`R14` preservados,
   `struct`/`union` por valor con puntero oculto, `char` sin signo y `size_t`/`uintptr_t`/
   `ptrdiff_t` de 32 bits. `float`/`double` se pasan en pila y se retornan en `R0`/`R1:R0`; el
   baseline es soft-float.
8. El estado de reset es determinista: `PC = resetVector`, `SP = FP = LR = 0`, GPR a cero,
   `FLAGS = 0`, `STATUS = 0x0000_0002` (supervisor, MMU apagada, interrupciones deshabilitadas),
   `TVEC = 0`, `PTBR = 0`, `PIDENT = MIDENT = 0xFFFF_FFFF`, `SCRATCH = 0`, `COREID = índice del
   core`. El BIOS fija su pila antes de usar `CALL`.
9. **Núcleos.** `CORE_COUNT = N` es configurable, `1 ≤ N ≤ 64`; no es una VM por core. Cada core
   tiene estado arquitectónico propio: `PC`, GPR, `FLAGS`, `STATUS`, `EPC`, `CAUSE`, `BADVADDR`,
   `TVEC`, `PTBR`, `PIDENT`, `MIDENT`, `SCRATCH`, TLB, reserva de `LR.W` y estado de traps.
   `RDCYCLE` es por core; `RDTIME` es global. La RAM/ROM física, el bus MMIO, el PIC, el timer, la
   NVRAM y los marcos de page tables son compartidos. El kernel guest decide qué proceso corre en
   cada core; el host no implementa ese scheduler ni crea una VM por core.

## 2. Privilegios y excepciones

1. Hay dos niveles: **supervisor** (`S`) y **usuario** (`U`). El BIOS, el bootloader y el kernel
   corren en `S`; los programas de política corren en `U`. No hay anillo intermedio.
2. `STATUS` tiene `IE` (bit 0), `PRV` (bit 1; 0 = U, 1 = S), `PIE` (bit 2), `PPRV` (bit 3), `MMU`
   (bit 4) y `TRAP` (bit 5). El resto debe ser cero.
3. En una excepción o IRQ: `PIE = IE`, `PPRV = PRV`, `IE = 0`, `PRV = S`, `TRAP = 1`,
   `PC = TVEC + 4 * CAUSE`. `EPC` es la dirección de la instrucción que falló en las excepciones
   síncronas, `PC + 3` en `ECALL` y la dirección de la instrucción siguiente en una IRQ. Si `TRAP` ya era 1,
   se entra en `DOUBLE_FAULT`; una segunda falta durante el doble fallo aborta la ejecución en el
   host con un error explícito. `ERET` restaura `IE`/`PRV` desde `PIE`/`PPRV`, limpia `TRAP` y
   vuelve a `EPC`; si `TRAP` era 0, `ERET` es `ILLEGAL_INSTRUCTION`.
4. `TVEC` es la base de una tabla de 64 entradas de 4 bytes (256 bytes), alineada a 256 bytes. Las
   entradas se interpretan en el espacio de direcciones actual (físico con MMU apagada, virtual con
   MMU encendida). Si `TVEC = 0`, cualquier trap es un error del host (`UNHANDLED_TRAP`); el BIOS
   debe publicar la tabla antes de habilitar interrupciones.
5. Códigos de excepción (0–15): `ILLEGAL_INSTRUCTION` (0), `BREAKPOINT` (1), `LOAD_FAULT` (2),
   `STORE_FAULT` (3), `INSTRUCTION_FAULT` (4), `ALIGNMENT` (5), `PRIVILEGE` (6), `ECALL_USER` (7),
   `ECALL_SUPERVISOR` (8), `DIVIDE_BY_ZERO` (9), `ARITHMETIC_OVERFLOW` (10), `BUS_ERROR` (11),
   reservados (12–13), `DOUBLE_FAULT` (14), reservado (15). Las IRQ ocupan 16–47 y se corresponden
   con las líneas 0–31 del PIC. `CAUSE` contiene el índice de vector; `BADVADDR` es válido para
   faltas de memoria, alineación y bus.
6. `ECALL` es la llamada al kernel: número en `R0`, argumentos en `R1`–`R6`, retorno en `R0`.
   Desde `U` usa el vector 7; desde `S`, el 8. El hardware no guarda GPR: el kernel guarda y
   restaura su marco. `HOST` no es `ECALL`: es una hiperllamada al host y no cambia `STATUS`.

## 3. ISA v3: codificación

1. Cada instrucción es una secuencia de cajas little-endian:
   - **Caja de opcode** (1 byte): `[7:6]` familia, `[5:2]` operación (0–15), `[1:0]` forma.
   - **Caja de descriptor** (2 bytes): `[15:12] rd`, `[11:8] rs`, `[7:4] base`, `[3] idx`,
     `[2:1] size`, `[0]` reservado.
   - **Caja de inmediato/desplazamiento** (4 bytes), solo cuando la forma la exige.
2. Formas: `00` registro-registro, `01` registro-inmediato, `10` lectura de memoria (o `LEA`),
   `11` escritura de memoria. En formas de memoria, `base` es el registro base, `rs` es el índice
   cuando `idx = 1` y queda reservado cuando `idx = 0`, y `rd` es el registro de datos: destino en
   lectura (forma 10) y fuente en escritura (forma 11). El desplazamiento es siempre de 32 bits y se
   suma a `base + index`; la suma es módulo 2^32. Un índice no se escala: el programa calcula
   `index * escala` con `SHL`/`ADD`. `base` e `idx` deben ser cero fuera de las formas de memoria.
3. `size`: `00` = 8 bits, `01` = 16 bits, `10` = 32 bits, `11` = reservado. En formas de memoria,
   el acceso debe estar alineado naturalmente (2 bytes para 16 bits, 4 para 32); si no, `ALIGNMENT`.
   Las cargas de 8/16 bits zero-extienden a 32 bits. Los almacenes escriben los bits bajos.
4. En `MOV` inmediato y en toda la ALU, `size` debe ser `10` (32 bits); usar 8/16 bits en esos
   casos es `ILLEGAL_INSTRUCTION`. Para cargar un valor pequeño, el programa usa un registro de
   32 bits y luego un almacén de 8/16 bits.
5. `MOV` forma 00 copia `rs` a `rd` (8/16 zero-extiende); forma 01 carga `imm32` en `rd`; forma 10
   carga `[base + index + disp32]` en `rd`; forma 11 almacena `rd` en `[base + index + disp32]`. En
   forma 11, `rd` es la fuente y `rs` es índice solo si `idx = 1`.
6. Longitud: 3 bytes para formas `00`; 7 bytes para formas `01`, y para las formas de memoria
   `10`/`11` de `MOV`/`LEA`. Los bits reservados de opcode/descriptor deben ser cero; si no,
   `ILLEGAL_INSTRUCTION`. No hay instrucciones de longitud fija: el decoder recorre las cajas y
   calcula la frontera con el mismo código que ejecuta. Una instrucción puede cruzar página; si la
   segunda página no es ejecutable, `INSTRUCTION_FAULT`.
7. No hay `MOV [mem], imm`: el ensamblador **no** sintetiza un registro temporal oculto. El autor
   escribe `MOV Rt, imm` y después `MOV [mem], Rt`. `LI` no es un opcode; es un alias de ensamblador
   que emite `MOV rd, imm32`.

## 4. ISA v3: instrucciones

| Familia | Op | Mnemónico | Formas | Semántica |
|---|---:|---|---|---|
| 0 | 0 | `MOV` | 00/01/10/11 | Registro, inmediato, carga y almacén. Único mnemónico para movimiento. |
| 0 | 1 | `LEA` | 10 | Calcula `base + index + disp32` y lo escribe en `rd`; no accede a memoria. |
| 0 | 2 | `PUSH` | 00 | `SP -= 4`; `[SP] = rs` (32 bits). |
| 0 | 3 | `POP` | 00 | `rd = [SP]`; `SP += 4` (32 bits). |
| 0 | 4–15 | — | — | Reservado. |
| 1 | 0 | `ADD` | 00/01 | `rd = rd + operando`; actualiza `Z/N/C/V`. |
| 1 | 1 | `ADC` | 00/01 | `rd = rd + operando + C`; actualiza `Z/N/C/V`. |
| 1 | 2 | `SUB` | 00/01 | `rd = rd - operando`; actualiza `Z/N/C/V`. |
| 1 | 3 | `AND` | 00/01 | `rd &= operando`; actualiza `Z/N`; limpia `C/V`. |
| 1 | 4 | `OR` | 00/01 | `rd = rd OR operando`; actualiza `Z/N`; limpia `C/V`. |
| 1 | 5 | `XOR` | 00/01 | `rd ^= operando`; actualiza `Z/N`; limpia `C/V`. |
| 1 | 6 | `SHL` | 00/01 | Desplaza a la izquierda; cuenta en `rs[4:0]` o inmediato; `Z/N/C`; `V = 0`. |
| 1 | 7 | `SHR` | 00/01 | Desplazamiento lógico; misma cuenta y flags. |
| 1 | 8 | `SAR` | 00/01 | Desplazamiento aritmético; misma cuenta y flags. |
| 1 | 9 | `MUL` | 00/01 | Producto bajo de 32 bits; `Z/N`; `C/V = 0`. |
| 1 | 10 | `DIV` | 00/01 | División entera con signo; cociente en `rd`; divisor cero o `INT_MIN / -1` trapa. |
| 1 | 11 | `DIVU` | 00/01 | División entera sin signo; divisor cero trapa. |
| 1 | 12 | `MOD` | 00/01 | Resto con signo; divisor cero o `INT_MIN % -1` trapa. |
| 1 | 13 | `MODU` | 00/01 | Resto sin signo; divisor cero trapa. |
| 1 | 14 | `CMP` | 00/01 | `SUB` sin escribir `rd`; actualiza `Z/N/C/V`. |
| 1 | 15 | `TEST` | 00/01 | `AND` sin escribir `rd`; actualiza `Z/N`; limpia `C/V`. |
| 2 | 0 | `JMP` | 00/01 | Salto a `rs` (forma 00) o relativo a `PC + 7 + disp32` (forma 01). |
| 2 | 1 | `Jcc` | 01 | Salto relativo si la condición de `rs` se cumple. `rd`/`base` reservados. |
| 2 | 2 | `CALL` | 00/01 | `R15 = PC + longitud`; salta a `rs` (forma 00) o a `PC + 7 + disp32`. |
| 2 | 3 | `RET` | 00 | `PC = R15`. |
| 2 | 4 | `ECALL` | 00 | Trap de syscall; vector 7/8 según `PRV`; número en `R0`. |
| 2 | 5 | `ERET` | 00 | Retorno de trap; solo supervisor y con `TRAP = 1`. |
| 2 | 6 | `HALT` | 00 | Detiene el core hasta IRQ habilitada, evento o watchdog; solo supervisor. |
| 2 | 7 | `BREAK` | 00 | Trap de breakpoint; código en `R0`; vector 1. |
| 2 | 8 | `NOP` | 00 | No hace nada. |
| 2 | 9–15 | — | — | Reservado. |
| 3 | 0 | `HOST` | 00 | Hiperllamada síncrona al host; convención de §7. |
| 3 | 1 | `RDCYCLE` | 00 | `rd = low32(cycles)`, `rs = high32(cycles)` en un snapshot atómico. |
| 3 | 2 | `RDTIME` | 00 | `rd = low32(timeNs)`, `rs = high32(timeNs)` en un snapshot atómico. |
| 3 | 3 | `CSRRW` | 01 | Lee el CSR a `rd` (si `rd != 0`) y escribe `rs`; número en `imm32[11:0]`. |
| 3 | 4 | `CSRRS` | 01 | Lee el CSR a `rd` y activa los bits de `rs`; si `rs = 0`, no escribe. |
| 3 | 5 | `CSRRC` | 01 | Lee el CSR a `rd` y limpia los bits de `rs`; si `rs = 0`, no escribe. |
| 3 | 6 | `CSRR` | 01 | Alias de `CSRRS rd, csr, 0`; lectura pura. |
| 3 | 7 | `CSRW` | 01 | Alias de `CSRRW 0, csr, rs`; escritura pura. |
| 3 | 8 | `SFENCE.VMA` | 00 | Vacía la TLB; necesario tras cambiar PTBR o una página. |
| 3 | 9 | `FENCE` | 00 | Ordena accesos de memoria/MMIO/DMA antes y después. |
| 3 | 10 | `LR.W` | 10 | Carga 32 bits y deja una reserva de `LR.W` sobre la dirección física. |
| 3 | 11 | `SC.W` | 11 | `rd` = éxito (1/0), `rs` = valor; almacena solo si la reserva sigue válida. |
| 3 | 12 | `AMOADD.W` | 11 | `rd` = valor anterior; `[addr] += rs`, atómico. |
| 3 | 13 | `AMOSWAP.W` | 11 | `rd` = valor anterior; `[addr] = rs`, atómico. |
| 3 | 14 | `AMOAND.W` | 11 | `rd` = valor anterior; `[addr] &= rs`, atómico. |
| 3 | 15 | `AMOOR.W` | 11 | `rd` = valor anterior; `[addr] |= rs`, atómico. |

Condiciones de `Jcc` (`rs`): 0 `EQ` (`Z=1`), 1 `NE`, 2 `CS` (`C=1`), 3 `CC`, 4 `MI` (`N=1`),
5 `PL`, 6 `VS` (`V=1`), 7 `VC`, 8 `HI` (`C=1 && Z=0`), 9 `LS` (`C=0 || Z=1`),
10 `GE` (`N=V`), 11 `LT` (`N!=V`), 12 `GT` (`Z=0 && N=V`), 13 `LE` (`Z=1 || N!=V`),
14 `ALWAYS`, 15 `NEVER`.

Reglas de flags: `ADD`/`ADC`/`SUB` actualizan `Z/N/C/V`; `C = 1` significa acarreo en `ADD` y
«sin borrow» en `SUB`/`CMP`; `V` es overflow de complemento a dos. `AND`/`OR`/`XOR`/`TEST`
actualizan `Z/N` y limpian `C/V`; los desplazamientos con cuenta 0 no cambian flags; `MUL` deja
`C/V = 0`; `DIV`/`DIVU`/`MOD`/`MODU` actualizan `Z/N` y dejan `C/V = 0`. `DIV`/`MOD` truncan hacia
cero y el resto tiene el signo del dividendo. `DIV`/`MOD` con divisor cero usan `DIVIDE_BY_ZERO`;
el caso `INT_MIN / -1` y `INT_MIN % -1` usan `ARITHMETIC_OVERFLOW`. `PUSH`/`POP` exigen `size = 10`
y `SP` alineado a 4; `LEA` ignora `size`; `MOV` de memoria admite 8/16/32. `CALL` lee el destino
antes de escribir `R15`. `RDCYCLE`/`RDTIME` exigen `rd != rs`; si coinciden, `ILLEGAL_INSTRUCTION`.

**Extensión atómica (enmienda 2026-11-16).** `LR.W`, `SC.W` y `AMO*` son la base de
sincronización intra-VM. El modelo es secuencialmente consistente para accesos de 32 bits
alineados; los accesos de 8/16 bits no son atómicos entre cores y exigen exclusión del kernel.
`LR.W` deja una reserva por core y dirección física; cualquier store de otro core a esa dirección,
un `SC.W`, un trap, `ERET`, `HALT` o un cambio de contexto la invalidan. `SC.W` escribe `rd = 1`
solo si la reserva sigue válida; si falla, `rd = 0` y no escribe memoria. `AMO*` hacen
leer-modificar-escribir atómico y devuelven el valor anterior en `rd`; `rs` es el operando. Todos
exigen alineación a 4 y `size = 10`; otra forma, tamaño o dirección produce
`ILLEGAL_INSTRUCTION`, `ALIGNMENT` o `BUS_ERROR`. Solo operan sobre RAM; un `LR.W`/`SC.W`/`AMO*`
sobre MMIO es `BUS_ERROR`. `rd` y `rs` pueden coincidir: el operando se lee antes de escribir el
resultado. La extensión está implementada: `CORE_COUNT > 1` no se simula con un solo hilo.

Privilegios de instrucción: `CSR*`, `ERET`, `SFENCE.VMA` y `HALT` son solo `S`; `ECALL`, `HOST`,
`RDCYCLE`, `RDTIME`, `FENCE`, `LR.W`, `SC.W`, `AMO*` y `BREAK` son `U/S`; el resto es común. Una
instrucción privilegiada en `U` produce `PRIVILEGE`. `HALT` con `IE = 0` produce
`ILLEGAL_INSTRUCTION`; dormir exige timer e interrupciones habilitadas.

CSR de v3.0: `STATUS` 0x100, `EPC` 0x101, `CAUSE` 0x102, `BADVADDR` 0x103, `TVEC` 0x104,
`PTBR` 0x105, `SCRATCH` 0x106, `PIDENT` 0x107, `MIDENT` 0x108, `FLAGS` 0x109, `COREID` 0x10A.
`COREID` es de solo lectura, devuelve el índice del core (0–63) y escribirla produce
`ILLEGAL_INSTRUCTION`. `TVEC` se alinea a
256 bytes y `PTBR` a 4 KB; un valor no alineado o con bits reservados no cero es
`ILLEGAL_INSTRUCTION`. En `CSR*`, `rd = 0` significa «no leer» y `rs = 0` significa «no escribir»;
por eso `CSRR`/`CSRW` son alias puros y R0 no se usa como operando en estas instrucciones. Los 20
bits altos del inmediato CSR deben ser cero; un número de CSR no definido o un valor con bits
reservados no cero es `ILLEGAL_INSTRUCTION`. `PIDENT` acepta 0–63 o 0xFFFF_FFFF; `MIDENT` acepta un
handle válido o 0xFFFF_FFFF. `FLAGS` solo escribe los bits 0–3.

## 5. Memoria, MMU y MMIO

1. **Mapa virtual** (constante, sin relocación):
   - `0x0000_0000`–`0x00FF_FFFF`: ROM de 16 MiB, R/X, supervisor.
   - `0x0100_0000`–`0x01FF_FFFF`: MMIO de 16 MiB, supervisor, decodificado **antes** de la MMU.
   - `0x0200_0000`–`0x7FFF_FFFF`: espacio de usuario, hasta ~2 GiB.
   - `0x8000_0000`–`0xFFFF_FFFF`: espacio de kernel, 2 GiB.
   - El contenedor ROM se mapea en `0x0000_0000`; `resetVector` apunta al offset físico
     4 KiB-alineado donde empieza el payload BIOS, después de cabecera y TOC (no a `0`). Las
     pilas iniciales son `0x7FFF_F000` (usuario) y `0xFFFF_F000` (kernel), con una página de
     guarda no mapeada debajo; el kernel las mapea.
2. La ROM física ocupa `0x0000_0000`–`0x00FF_FFFF`; el MMIO físico ocupa
   `0x0100_0000`–`0x01FF_FFFF`; la RAM física empieza en `0x0200_0000` y tiene `RAM_SIZE` bytes.
   Cualquier acceso físico fuera de esas regiones es `BUS_ERROR`. La ROM física es de solo lectura:
   un store a una página que la mapee produce `STORE_FAULT`.
3. **MMU apagada** al reset: las direcciones son físicas, solo se permite `S`, no hay permisos de
   página. El BIOS la enciende escribiendo `STATUS.MMU = 1` después de construir la page table. Un
   `ERET` a `U` con MMU apagada es `PRIVILEGE`. Con MMU apagada, `PC`/`EPC`/`TVEC`/`BADVADDR` son
   físicas; con MMU encendida son virtuales. El BIOS mantiene una identidad para su propio código o
   actualiza `TVEC` al encender la MMU.
4. Paginación de **dos niveles, 4 KB, 10 + 10 + 12 bits**. `PTBR` es la dirección física de la
   tabla de nivel 1, alineada a 4 KB (los 12 bits bajos deben ser cero; si no, `ILLEGAL_INSTRUCTION`
   al escribir el CSR). El PDE contiene `[31:12]` dirección física de la tabla de nivel 2 y bit 0
   `V`; el resto debe ser cero. El PTE contiene:
   - `[31:12]` frame físico;
   - bit 11 reservado (0);
   - bit 10 `S` (snapshot compartido);
   - bit 9 `C` (copy-on-write);
   - bit 8 `G` (global);
   - bit 7 `D` (dirty);
   - bit 6 `A` (accessed);
   - bit 5 `U` (accesible desde usuario);
   - bit 4 `X`, bit 3 `W`, bit 2 `R`, bit 0 `V`; bit 1 reservado (0).
   Un PDE o PTE con bits reservados no cero produce falta.
5. `W && X` es una página inválida y produce falta. Una página válida con `R = 0` no se puede leer,
   con `W = 0` no se puede escribir y con `X = 0` no se puede ejecutar; la falta es `LOAD_FAULT`,
   `STORE_FAULT` o `INSTRUCTION_FAULT`, respectivamente. `U` solo puede leer/escribir/ejecutar
   páginas con `U = 1`; `S` puede acceder a páginas de usuario. El hardware marca `A` en toda
   traducción válida y `D` en toda escritura. `C` o `S` con escritura producen `STORE_FAULT` para
   que el kernel resuelva COW/snapshot; el guest nunca recibe una escritura silenciosa sobre una
   página compartida.
6. La TLB es una caché; su contenido debe ser coherente con la page table después de escribir
   `PTBR` o ejecutar `SFENCE.VMA`. No hay ASID en v3.0: cambiar de proceso implica escribir `PTBR`
   y vaciar la TLB. Un TLB obsoleto es un bug del emulador, no un comportamiento permitido.
7. **MMIO antes de la MMU.** El rango `0x0100_0000`–`0x01FF_FFFF` no se traduce; el bus lo decodifica
   directamente y solo `S` puede acceder. Los accesos deben ser de 32 bits y estar alineados a 4;
   otro tamaño o alineación produce `ALIGNMENT` o `BUS_ERROR`. No se puede mapear MMIO mediante un
   PTE: un PTE que apunte a la región MMIO es `BUS_ERROR`.
8. **Dispositivos v3.0** (cada uno de 4 KB, registros de 32 bits):
   - `0x0100_0000` UART: `TX` WO, `RX` RO, `STATUS` RO (bit 0 TX listo, bit 1 RX disponible,
     bit 2 overflow), `CTRL` RW (bit 0 IRQ RX, bit 1 IRQ TX).
   - `0x0100_1000` TIMER: `CMP_LO`/`CMP_HI` RW, `CTRL` RW (bit 0 enable, bit 1 periódico,
     bit 2 IRQ), `TARGET` RW (`[7:0]` core destino, `0xFF` broadcast; reset 0), `STATUS` RW1C
     (bit 0 fired), `PERIOD_LO`/`PERIOD_HI` RW (por defecto 1.000.000 ns), `TIME_LO`/`TIME_HI` RO
     con latch (leer `TIME_LO` fija el alto hasta leer `TIME_HI`). `TIME_*` es global; `CMP`,
     `CTRL`, `TARGET` y `STATUS` los gestiona el kernel y la IRQ 0 se entrega al core destino.
   - `0x0100_2000` PIC: banco por core de `PENDING` RO, `MASK` RW (1 = enmascarada; reset todo 1),
     `ACK` WO e `IN_SERVICE` RO; `SOFT_IRQ` WO (`[7:0]` línea, `[15:8]` core destino, `0xFF`
     broadcast; escribe 1 en una línea para pendiente por software o IPI). Cada core solo ve y
     modifica su banco.
   - `0x0100_3000` DISPLAY: `COLS` RO (80), `ROWS` RO (25), `CURSOR` RW (`fila << 16 | columna`),
     `CTRL` RW (bit 0 enable, bit 1 clear), búfer de texto de 80×25 celdas de 2 bytes (carácter y
     atributo) en `0x0100_3010`–`0x0100_3FFF`.
   - `0x0100_4000` NVRAM: `SIZE` RO (4 KiB), `ADDR` RW, `DATA` RW, `COMMIT` WO (1 = flush).
   - `0x0100_5000` SYSINFO: `RAM_SIZE`, `RAM_BASE`, `ROM_SIZE`, `TOC_OFFSET`, `TOC_COUNT`,
     `CORE_COUNT`, `ABI_VERSION` (3), `FEATURES` (bit 0 timer, 1 NVRAM, 2 UART, 3 display, 4 sync,
     5 mailbox, 6 multicore), `CPU_HZ` (1.000.000.000), `TIME_HZ` (1.000.000.000).
   - `0x0100_6000` HOST_MAILBOX: `EVENT_TYPE` RO (1 mensaje, 2 apagado, 3 acción host completada),
     `MESSAGE_HANDLE` RO, `EVENT_SEQ` RO, `TARGET` RW (`[7:0]` core destino; reset 0), `ACK` WO,
     `STATUS` RO (bit 0 pendiente).
   - `0x0100_7000`–`0x01FF_FFFF`: reservado; acceso `BUS_ERROR`.
9. Las líneas de IRQ son: 0 timer, 1 UART RX, 2 UART TX, 3 display (no usada en v3.0), 4 reservada,
   5 host mailbox, 6–15 reservadas y 16–31 software. El PIC es de flanco, con `PENDING`, `MASK` e
   `IN_SERVICE` **por core**; cada core atiende solo su banco. Al tomar la línea pendiente de menor
   número no enmascarada, limpia ese bit, lo marca en `IN_SERVICE` y vectoriza; `ACK` local limpia
   `IN_SERVICE`. Un nuevo flanco durante el servicio vuelve a poner `PENDING`, pero la línea no se
   toma hasta el `ACK`. El kernel debe hacer `ACK` antes de `ERET`. Las líneas 16–31 son software y
   `SOFT_IRQ` las dirige al core indicado (IPI); `TARGET = 0xFF` es broadcast. Una IPI a un core
   fuera de `CORE_COUNT` es `BUS_ERROR`; no se ignora en silencio. El timer y el mailbox usan
   `TARGET` para elegir el core que recibe su IRQ; el kernel puede reenviarla con una IPI.

## 6. Tiempo, scheduler y persistencia

1. `RDCYCLE` es un contador monótono de 64 bits **por core** de instrucciones retiradas; solo
   avanza cuando ese core ejecuta. `RDTIME` es un contador monótono de 64 bits **global** de
   nanosegundos virtuales; avanza durante ejecución y espera. Cada core lee su `RDCYCLE`; `RDTIME`
   es común. Ambos se leen con un snapshot atómico en dos registros; no hay *tearing*. La
   frecuencia nominal es 1 GHz para ciclos y 1 ns para tiempo; el overflow es el de un `u64` y no
   se reinicia salvo reset de la VM.
2. El CPU comprueba IRQ pendientes al inicio de cada instrucción, antes del fetch. El timer compara
   `RDTIME` con `CMP`; al alcanzarlo, marca `STATUS.fired` y, si `CTRL.IRQ` y la línea 0 están
   desenmascaradas, levanta la IRQ 0. En modo periódico suma `PERIOD` a `CMP`; si la VM se atrasó,
   dispara una vez y fija `CMP = RDTIME + PERIOD` para no generar una tormenta.
3. `HALT` solo es válido en `S` y exige `IE = 1`; con `IE = 0` es `ILLEGAL_INSTRUCTION`. Detiene el
   core hasta que haya una IRQ habilitada, un evento de host o el watchdog (si está activo). Si no
   hay procesos ejecutables, el host avanza el tiempo virtual hasta el
   siguiente deadline en lugar de hacer busy-wait. `ECALL` permite al kernel ceder la CPU y dormir;
   la política temporal (token bucket, backoff, deadline) vive en el guest, no en el host.
4. **Núcleos.** `iflow-32` es **una sola VM con `CORE_COUNT = N` núcleos configurables**
   (`1 ≤ N ≤ 64`), no una VM por core. Los N cores comparten RAM, ROM, bus MMIO, kernel y estado
   de máquina; cada uno tiene su estado arquitectónico. El scheduler de procesos es software del
   kernel guest; el host solo crea los cores, impone quantum por core y watchdog de paso
   configurable, y no decide qué proceso corre en cada uno. El detalle de coherencia, atómicas,
   IPI, arranque y journal está en §6.1. V3-2 implementa ese mínimo y habilita `N > 1`; no hay
   degradación silenciosa a un solo core.
5. **Quantum de host por core (preempción, no fuel):** 1.000.000 de instrucciones o 50 ms de
   ejecución de guest por core y por slice, lo que ocurra primero, configurable. Al agotarse, el
   host recupera el control y devuelve `PREEMPTED`; **no es un fallo, no termina el mensaje, no
   revierte el journal y no limita la vida de la VM ni el número de slices**. El estado
   arquitectónico, la RAM, el journal abierto y los contadores se conservan; el host puede reanudar
   el mismo core de inmediato o cederlo a otro trabajo. El guest no puede enmascarar ni desactivar
   el quantum. No hay `machineLock` global para la ejecución normal: el lock de máquina solo
   serializa ciclo de vida (reset, snapshot, apagado). No es fuel y no se expone en el ABI.
   **Watchdog de paso:** si un solo paso de guest tarda más de 50 ms de reloj de pared en retornar
   (excluyendo `HOST`), el host devuelve `WATCHDOG` y deja el core en `FAULTED`; es una salvaguarda
   contra un fallo de implementación o un bloqueo del host, no una política de entrega ni un límite
   para programas largos. El tiempo bloqueado en `HOST` no consume el quantum ni el watchdog y
   queda acotado por el timeout de la acción. La demora de un mensaje es responsabilidad del
   programa de userspace/kernel guest, no del host. La comprobación es por paso y no interrumpe
   asíncronamente un `cpu.step()` que nunca retorne; esa interrupción externa exigiría aislar el
   core y queda fuera de V3-1. El watchdog está **activo por defecto**. El operador puede
   desactivarlo explícitamente en `config/iflow-32/config.yml` con `watchdog.enabled: false` para
   hosts muy lentos donde un solo paso puede superar 50 ms por causas legítimas (CPU antigua, JIT
   frío o pausas del host). Un archivo ausente mantiene el watchdog activo; un valor inválido falla
   de forma explícita. Desactivarlo se registra con `warn`, no cambia el quantum, que sigue siendo
   no enmascarable, y deja sin recuperación un `cpu.step()` que no retorne.
6. La **máquina es persistente**: RAM, registros, timer, page tables y NVRAM sobreviven entre
   mensajes mientras la VM está encendida. El journal es por unidad de mensaje: o se confirman
   todas las mutaciones del mensaje o ninguna. Un aborto de ejecución no revierte el estado de
   máquina ya avanzado (tiempo, ciclos, cola); no se presenta como éxito de mensaje. Al apagar la
   VM solo persiste la NVRAM; reiniciar no conserva RAM ni registros.
7. La cola de mensajes vive en el kernel. El host conserva los objetos `Message` y usa un handle
   opaco de 32 bits (16 bits de slot + 16 bits de generación) para evitar ABA. El handle no se
   interpreta en el ABI; el kernel lo trata como opaco. El host mailbox publica el handle y el
   evento; el kernel lo encola y fija `MIDENT` al ejecutar la política.
8. Una entrega diferida por `HALT`/sleep **no cuenta como entregada** hasta que el guest registra
   `ROUTE_DECISION` con target `DELIVER`. Si la VM se apaga o la ejecución se cancela antes, el
   host devuelve `NOT_DELIVERED`/`CANCELLED`; nunca un éxito falso. Si la entrega física falla
   después de la decisión, el resultado es el `SyncResult` explícito de la acción y no se reintenta
   si no es idempotente.
9. No hay `ROUTE_RATE_LIMIT` ni `DECISION_RATE_LIMIT`. El token bucket y el backoff son software
   del guest. El host puede aplicar un **cap duro separado** (llamadas por minuto, tamaño de
   payload, timeout de acción) que devuelve `RATE_LIMITED` para la tasa, `DENIED` para un payload
   que nunca cabría y `TIMEOUT` para una acción agotada; ese cap no es la política y no se duplica
   con ella.
10. El cambio de ROM en caliente no está soportado en v3.0: se marca `RESTART_REQUIRED`. Las
    instantáneas de memoria son por frame y COW; nunca se clona el espacio de 4 GiB. La compresión
    de frames queda fuera de v3.0.

### 6.1. Núcleos, coherencia y concurrencia (enmienda 2026-11-16)

1. **Modelo.** `CORE_COUNT` es configurable (`1 ≤ N ≤ 64`). Es **una sola VM con N vCPU**, no N
   VMs ni N kernels. Los cores comparten RAM, ROM, bus MMIO, PIC, timer, NVRAM, kernel y marcos de
   page tables; cada core conserva su estado arquitectónico y su TLB. El host crea los cores,
   aplica quantum por core y watchdog de paso configurable; el kernel guest decide qué proceso
   corre en cada uno.
2. **Estado por core.** `PC`, GPR, `FLAGS`, `STATUS`, `EPC`, `CAUSE`, `BADVADDR`, `TVEC`, `PTBR`,
   `PIDENT`, `MIDENT`, `SCRATCH`, TLB, reserva de `LR.W` y estado de traps son privados. `COREID`
   (0x10A, RO) identifica el core; `SYSINFO.CORE_COUNT` publica N y `FEATURES` bit 6 indica
   multicore. `RDCYCLE` es por core; `RDTIME` es global. `TVEC` y `PTBR` pueden apuntar a
   estructuras compartidas, pero cada core mantiene su copia.
3. **Memoria compartida.** El modelo es **secuencialmente consistente** para accesos de 32 bits
   alineados. Los accesos de 8/16 bits no son atómicos entre cores; el kernel debe protegerlos con
   `LR.W`/`SC.W` o con exclusión mutua. `FENCE` ordena memoria/MMIO observables; en v3.0 con
   coherencia secuencial no cambia el resultado, pero se conserva como barrera contractual. No hay
   cachés de datos; la TLB es por core. Un snapshot o apagado exige detener todos los cores y
   tomar el lock de ciclo de vida; no se toma un snapshot con cores ejecutando.
4. **MMIO.** El bus serializa cada transacción de 32 bits alineada y publica el efecto antes de
   retirar la instrucción. Los dispositivos que no admiten concurrencia serializan internamente y
   nunca descartan una escritura en silencio. El PIC tiene `PENDING`/`MASK`/`IN_SERVICE` por core;
   `TIMER.TARGET` y `HOST_MAILBOX.TARGET` eligen el core de destino; `SOFT_IRQ` puede dirigir una
   línea software a otro core como IPI. Un `TARGET` fuera de `CORE_COUNT` es `BUS_ERROR`.
5. **Atómicas.** `LR.W`/`SC.W`/`AMO*` de §4 son obligatorias para locks, colas y contadores
   compartidos. La reserva de `LR.W` es por core y dirección física; cualquier store de otro core
   a la misma dirección, un `SC.W`, un trap, `ERET`, `HALT` o un cambio de contexto la invalidan.
   `SC.W` devuelve 1 solo si la reserva sigue válida; si no, devuelve 0 y no escribe. Los `AMO*`
   son leer-modificar-escribir secuencialmente consistentes y solo operan sobre RAM; no hay
   atómicas sobre MMIO.
6. **IPI y TLB.** `SFENCE.VMA` vacía solo la TLB del core que la ejecuta. Tras cambiar una page
   table compartida, el kernel debe enviar una IPI a los demás cores (líneas software 16–31 con
   `SOFT_IRQ`) y esperar su confirmación antes de reutilizar el marco; no hay ASID ni shootdown
   automático del host. Un core que recibe la IPI ejecuta `SFENCE.VMA` y la reconoce.
7. **Scheduler.** El kernel guest es el único scheduler de procesos. El core 0 es el boot core:
   todos los cores arrancan en `resetVector`, leen `COREID`, y los cores 1..N-1 esperan la
   liberación del kernel en `HALT` o en una espera cooperativa; no ejecutan guest antes de que el
   kernel los publique. El host no hace work stealing ni elige el core de un mensaje. `HALT` es por
   core; si todos los cores están dormidos, el host avanza el tiempo virtual hasta el deadline más
   cercano; si alguno ejecuta, el tiempo avanza con la ejecución.
8. **Quantum, watchdog y fallo.** El quantum es por core y no cobra tiempo bloqueado en `HOST`.
   El watchdog de paso es por core, está activo por defecto y se configura en
   `config/iflow-32/config.yml`; desactivarlo no altera el quantum. Agotar el quantum devuelve
   `PREEMPTED`: no aborta la unidad de mensaje, no descarta el journal abierto y no limita la vida
   de la VM; el host puede reanudar el mismo core.
   El watchdog de paso solo devuelve `WATCHDOG` si un único paso de guest supera 50 ms de reloj de
   pared; entonces el core queda `FAULTED`, el journal abierto no se confirma y no se revierte
   ninguna unidad ya confirmada por otro core. Si el fallo puede haber corrompido estado
   compartido, la VM se marca `FAULTED` y exige reinicio; no se continúa con un core zombi ni se
   presenta un éxito falso. Los demás cores se detienen de forma ordenada en ese caso. La
   comprobación es por paso: si el código del host que implementa una instrucción no retorna, el
   hilo no recupera el control; una interrupción externa de un core colgado queda fuera de V3-1.
   Con el watchdog desactivado, un `cpu.step()` colgado deja de ser recuperable; es una decisión
   explícita del operador, se registra con `warn` y no habilita ningún fallback silencioso.
9. **Journal y `HOST` concurrente.** El journal es por unidad de mensaje, no por VM. Varias
   unidades pueden ejecutarse en cores distintos con overlays y decisiones independientes; cada
   una tiene su propio contexto de ABI y su latch de `ROUTE_DECISION`, sin compartir el de otra.
   `PROPERTY_*` y `ROUTE_DECISION` solo ven el overlay de su unidad; no hay visibilidad cruzada
   antes del commit. El host serializa las mutaciones globales o los servicios no thread-safe, pero
   no toma un `machineLock` global para ejecutar guest. El aborto de una unidad no revierte
   unidades ya confirmadas por otros cores.
10. **Arranque y gate.** El BIOS y el kernel deben ser multicore-safe. La implementación de este
    apartado corresponde a **V3-2** y entrega: `CORE_COUNT`, estado por core, `COREID`/`SYSINFO`,
    atómicas, IPI, PIC por core, TLB shootdown, scheduler de guest, watchdog por core y journal
    concurrente. `CORE_COUNT > 1` solo se habilita cuando el BIOS y el kernel guest cumplen ese
    mínimo; no hay un modo single-core silencioso ni un fallback. `CORE_COUNT = 1` debe seguir
    siendo determinista.

## 7. ABI `HOST` v3

1. `HOST` es una hiperllamada síncrona. Entradas:
   - `R0` = service ID de 32 bits;
   - `R1` = flags, debe ser 0;
   - `R2` = dirección virtual del buffer de argumentos;
   - `R3` = longitud de argumentos en bytes;
   - `R4` = dirección virtual del buffer de resultado;
   - `R5` = capacidad del buffer de resultado.
2. Salidas: `R0` = estado; `R1` = longitud del resultado. Todos los demás registros se preservan.
   Si la capacidad es insuficiente, `R0 = LIMIT`, `R1 = tamaño requerido` y no se escribe ningún
   byte parcial. Si `R3 = 0`, `R2` se ignora. Los buffers deben ser accesibles desde el modo actual
   y estar completamente mapeados; si no, `FAULT`.
3. Estados: `OK = 0`, `INVALID_ARGUMENT = 1`, `UNSUPPORTED = 2`, `DENIED = 3`, `NOT_FOUND = 4`,
   `CONFLICT = 5`, `LIMIT = 6` (solo capacidad del buffer de resultado), `FAULT = 7`,
   `INTERNAL = 8`, `NOT_READY = 9`, `RATE_LIMITED = 10`, `TIMEOUT = 11`. No hay códigos negativos
   ni éxito implícito. `LIMIT` no significa fuel, gas ni presupuesto de ejecución agotado.
4. El código de datos es **TLV** con `TlvCodec` de `core-api`: tipo `u8`, longitud `u32` LE,
   payload; tipos 0 `NULL`, 1 `STRING`, 2 `I64`, 3 `BOOL`, 4 `BYTES`, 5 `LIST`, 6 `MAP`;
   little-endian, sin padding, UTF-8 validado, sin truncar, ausencia explícita (`NULL`) distinta
   de cadena vacía, claves duplicadas inválidas en mapas y listas ordenadas. El máximo por buffer
   es 1 MiB. El puente sync usa además `SyncBridgeCodec`, sin un segundo codec.
5. Servicios v3:

   | ID | Servicio | Argumentos TLV | Resultado TLV |
   |---|---|---|---|
   | `0x0100` | `PROPERTY_READ` | `scope`, `property`, referencias opcionales | `present`, `value` tipado |
   | `0x0101` | `PROPERTY_WRITE` | `scope`, `property`, `value` | vacío |
   | `0x0102` | `PROPERTY_EXISTS` | `scope`, `property` | `present` |
   | `0x0200` | `PERMISSION_CHECK` | `actor`, `channel`, `permission` | `allowed` |
   | `0x0300` | `ROUTE_DECISION` | `target`, `reason`, `error` opcional | vacío |
   | `0x0400` | `TRANSFORM_APPLY` | `kind`, `input` | `output` |
   | `0x0701` | `MESSAGE_ID` | vacío | `id` |
   | `0x0800` | `SYNC_SURFACE` | vacío | superficies |
   | `0x0801` | `SYNC_INVOKE` | `backend`, `action`, `args`, `idempotencyKey`/`reason` opcionales | `SyncResult` |

   No existen en v3.0 `STATE_READ/WRITE`, `CHANNEL_RESOLVE/POLICY`, `MESSAGE_CLONE`,
   `ROUTE_REDIRECT`, `ROUTE_RATE_LIMIT`, `PERMISSION_QUERY` ni `DECISION_RATE_LIMIT`; un ID v2 no
   listado devuelve `UNSUPPORTED`.
6. `ROUTE_DECISION.target`: `DELIVER = 0`, `CANCEL_EVENT = 1`, `ERROR = 2`. No existe `DROP`,
   `LOG` ni `REDIRECT`; no llamar a `DELIVER` es el drop por defecto. Solo `DELIVER` confirma el
   journal y después intenta la entrega; sin decisión, con `CANCEL_EVENT` o con `ERROR`, el journal
   del mensaje se descarta (con `CANCEL_EVENT` sí se aplica la cancelación del evento). Un fallo de
   entrega posterior a `DELIVER` no revierte el mensaje ya
   confirmado. Una segunda decisión en la misma ejecución devuelve `CONFLICT` e invalida la unidad:
   no se entrega nada. El destino no viaja en la decisión: el cambio de canal
   se hace con `SYNC_INVOKE("textformatter", "channel.set", ...)` antes de decidir, y el backoff es
   tiempo de guest. Ese cambio de canal se registra en el journal; la segunda fase de routing se
   computa sobre el overlay y solo se entrega si la unidad termina en `DELIVER`. Así se conservan
   D02/D03/D40/D42 y docs/prompts/diseno/pipeline-sync-v1.md §16.5; el `destination`/`backoff` de D41 no se expone en el ABI v3.
7. `PROPERTY_WRITE` escribe en el overlay del journal; las lecturas posteriores de la misma
   ejecución ven el cambio (read-your-writes). El commit es todo o nada. `PROP_CHANNEL`,
   `PROP_DIRECTION`, `PROP_TYPE` y `PROP_CANCELLED` son de solo lectura; escribir una de ellas
   devuelve `DENIED`. El canal se cambia solo por la acción sync. Los IDs de propiedad son los de
   ADR-031, sin `PROP_PROCESSED`, `PROP_SOUNDS`, `PROP_ACTOR_WORLD` ni `PROP_SLEEP_MILLIS`;
   `PROP_CANCELLED` se conserva como lectura de estado, no como target.
8. `PERMISSION_CHECK` es fail-closed: actor, canal o permiso ausente/vacío/desconocido devuelve
   `allowed = false`; un argumento malformado devuelve `INVALID_ARGUMENT`. `PROPERTY_READ` y
   `PROPERTY_EXISTS` de una propiedad desconocida devuelven `present = false`; `PROPERTY_WRITE`
   devuelve `NOT_FOUND`; `TRANSFORM_APPLY` con `kind` desconocido devuelve `UNSUPPORTED`;
   `ROUTE_DECISION` con `target` inválido devuelve `INVALID_ARGUMENT`. `SYNC_SURFACE` devuelve
   `UNSUPPORTED` si el puente no está instalado y una lista vacía si existe pero no hay backends.
   `SYNC_INVOKE` mapea `Ok→OK`, `Denied→DENIED`, `Unsupported→UNSUPPORTED`,
   `InvalidArgument→INVALID_ARGUMENT`, `NotFound→NOT_FOUND`, `Conflict→CONFLICT`,
   `RateLimited→RATE_LIMITED`, `Timeout→TIMEOUT` y `HostFailure→INTERNAL`; conserva
   `retryAfterMillis`/`reason` y devuelve `UNSUPPORTED` sin puente. `SYNC_SURFACE` exige la
   capacidad `sync.surface` y `SYNC_INVOKE` la capacidad `sync.invoke`, además de la capacidad
   concreta de la acción.
9. `HOST` requiere `PIDENT` válido para cualquier servicio de política y `MIDENT` válido para los
   servicios que operan sobre el mensaje (`PROPERTY_*`, `PERMISSION_CHECK`, `ROUTE_DECISION`,
   `TRANSFORM_APPLY`, `MESSAGE_ID`, `SYNC_INVOKE`); `SYNC_SURFACE` solo exige `PIDENT`. `PIDENT`
   es el índice del programa en la tabla del loader (0–63); `MIDENT` es el handle del mensaje
   actual; ambos son CSR de supervisor (0x107 y 0x108) y `U` no puede escribirlos. El host resuelve
   el SHA-256 y las capacidades desde su tabla, no desde el guest. Sin identidad verificada,
   `DENIED` o `NOT_READY`. `Principal.Official` no ejecuta `HOST`.
10. Capacidades baseline (`iflow.property.read`, `iflow.property.write`, `iflow.permission.check`,
    `iflow.route.decision`, `iflow.transform.apply`, `iflow.message.id`) se conceden a todo
    programa verificado que las declare. Las acciones sync usan `sync.<backend>.<accion>` o
    `module.<moduleId>.<accion>` y exigen concesión del operador por SHA-256, con la intersección
    declarada/concedida de docs/prompts/diseno/pipeline-sync-v1.md §16.6. Una capacidad desconocida en la imagen invalida la ROM.
11. `HOST` no cambia `STATUS`, no es reentrante y puede ceder la CPU mientras espera al host. Una
    acción sync puede tener efecto externo antes del commit del journal; no hay transacción entre
    efecto nativo y mensaje (docs/prompts/diseno/pipeline-sync-v1.md §16.6). Un timeout devuelve `TIMEOUT`, nunca éxito.
12. Con `CORE_COUNT > 1`, cada core tiene su propio contexto de ABI y de decisión; el latch de
    `ROUTE_DECISION` no se comparte. Las operaciones sobre una unidad de mensaje se serializan por
    `MIDENT` y su journal; `HOST` puede ejecutarse en varios cores a la vez, pero los servicios que
    tocan estado global del host se serializan en el host. Un `PROPERTY_WRITE` de una unidad no es
    visible para otra antes del commit. Sin identidad verificada, `DENIED`.
13. **Sin fuel, gas ni presupuesto de ejecución en el ABI.** El guest no recibe, paga ni consulta
    un contador de ejecución; no existe un estado `OUT_OF_FUEL`, `LIMIT` no es un agotamiento de
    ejecución y `TIMEOUT` solo describe el timeout de una acción `HOST`. El quantum de host es un
    slice de preempción reanudable, no una cuota total: no limita la vida de la VM, la duración de
    un mensaje ni el número de slices. Si un mensaje se retrasa, la política de admisión, cola y
    backoff es del kernel/userspace guest. La única cota de pared de una llamada `HOST` es el
    timeout explícito de su acción.
14. **Vocabulario de `scope`/`property`.** Los argumentos son cadenas canónicas; no hay ids
    numéricos v2, alias ni despacho dual. Los pares admitidos son:
    - `message`: `content`, `langSource`, `langTarget`, `colorMode`, `formatPapi`, `translate`
      (lectura/escritura), y `channel`, `direction`, `type` (solo lectura).
    - `emitter` y `recipient`: `name`, `uuid`, `language` (solo lectura).
    - `channel`: `name`, `rate`, `sendPolicy`, `receivePolicy` (solo lectura).
    Un par desconocido se lee como ausencia (`present = false`) y `PROPERTY_EXISTS` devuelve
    `false`; `PROPERTY_WRITE` sobre un par desconocido devuelve `NOT_FOUND` y sobre uno conocido de
    solo lectura, `DENIED`. Los valores son TLV estrictos: texto, idioma y color como `STRING`
    válida y los booleanos como `BOOL`; un tipo incorrecto es `INVALID_ARGUMENT`. El host aplica el
    write set como un lote atómico al commitear `DELIVER` y no aplica ningún byte si el lote falla.

## 8. Formato ROM v3 (`.flow`)

1. El `.flow` v3 es una ROM completa, no un ejecutable. Cabecera de 80 bytes:

   | Offset | Tamaño | Campo |
   |---:|---:|---|
   | 0 | 8 | magic `FLOWROM\0` |
   | 8 | 2 | `formatVersion = 3` |
   | 10 | 2 | `isaVersion = 32` |
   | 12 | 2 | `abiVersion = 3` |
   | 14 | 2 | `headerSize = 80` |
   | 16 | 4 | `flags` (bit 0 firmada; bits 1–31 reservados) |
   | 20 | 4 | `romSize` |
   | 24 | 4 | `resetVector` |
   | 28 | 4 | `tocOffset` |
   | 32 | 2 | `tocCount` |
   | 34 | 2 | `tocEntrySize = 64` |
   | 36 | 4 | `hashAlgorithm = 1` (SHA-256) |
   | 40 | 4 | `signatureOffset` (0 si no hay) |
   | 44 | 4 | `signatureSize` (0 si no hay) |
   | 48 | 32 | `hash` SHA-256 |

2. El hash cubre toda la ROM con los 32 bytes del campo `hash` puestos a cero. Se verifica antes de
   mapear. Un hash todo cero es inválido. Si `flags.bit0 = 1`, debe existir firma y el host la
   verifica antes de cargar; el parser de la VM comprueba presencia y límites, no criptografía.
   `signatureOffset`/`signatureSize` deben caber en la ROM y no solaparse con la cabecera, la TOC
   ni los segmentos; si `flags.bit0 = 0`, ambos deben ser cero.
3. La TOC tiene entradas de 64 bytes:

   | Offset | Tamaño | Campo |
   |---:|---:|---|
   | 0 | 1 | `type` (1 BIOS, 2 BOOT, 3 KERNEL, 4 PROGRAM, 5 DATA, 6 FONT, 7 CONFIG; 8+ reservado) |
   | 1 | 1 | `flags` (bit 0 R, 1 W, 2 X, 3 U; bits 4–7 reservados) |
   | 2 | 2 | reservado (0) |
   | 4 | 4 | `offset` en la ROM |
   | 8 | 4 | `size` copiado |
   | 12 | 4 | `loadAddress` virtual |
   | 16 | 4 | `entryPoint` (0 si no es ejecutable) |
   | 20 | 4 | `memSize` (≥ `size`) |
   | 24 | 32 | hash SHA-256 del segmento |
   | 56 | 8 | reservado (0) |

4. Límites y validación: `headerSize = 80`, `tocEntrySize = 64`, `hashAlgorithm = 1`;
   `romSize <= 16 MiB`; `tocOffset >= 80` y alineado a 8; `tocCount <= 256`; hay exactamente un
   segmento BIOS, uno BOOT y uno KERNEL, y como máximo 64 PROGRAM;
   `tocOffset + tocCount*64 <= romSize`; sin desbordamiento de 32 bits; cada `loadAddress` está
   alineado a 4 KiB y cada segmento cumple
   `offset >= 80 + tocCount*64`, `offset + size <= romSize` y no solapa cabecera, TOC ni firma;
   `loadAddress + memSize` no desborda 32 bits; segmentos sin solaparse en ROM ni en memoria
   virtual; `size > 0`; `memSize >= size`; hash de segmento distinto de cero; `entryPoint` dentro
   del segmento si `X`; `resetVector` dentro del segmento BIOS y ejecutable; el `offset` del BIOS
   es igual a su `loadAddress`, ambos 4 KiB-alineados y posteriores a cabecera y TOC. El host solo
   mapea los primeros `romSize` bytes del contenedor en `0x0000_0000` con permisos R/X y `U = 0`;
   la última página se zero-fill hasta 4 KiB. BIOS, BOOT, KERNEL y PROGRAM se leen de la ROM por
   TOC; el guest copia a RAM los segmentos que necesite con los flags de la TOC y zero-fill hasta
   `memSize`. El host no reconstruye page tables ni copia todos los segmentos.
5. Reglas por tipo: BIOS en el offset alineado tras cabecera y TOC (`loadAddress == offset`),
   R/X, supervisor; BOOT/KERNEL en `0x8000_0000`–
   `0xFFFF_FFFF`, R/X o R/W, supervisor; PROGRAM en `0x0200_0000`–`0x7FFF_FFFF`, `U = 1`, R/X y
   `W = 0`; DATA es para firmware/kernel, no para programas; FONT/CONFIG son datos. `W && X` se
   rechaza. El flag `W` de la TOC de un `PROGRAM` debe ser 0: los permisos de datos/bss salen de
   `PRG3`, no de la TOC. Un `PROGRAM` empieza con la subcabecera `PRG3` de 32 bytes:

   | Offset | Tamaño | Campo |
   |---:|---:|---|
   | 0 | 4 | magic `PRG3` |
   | 4 | 4 | `codeOffset` |
   | 8 | 4 | `codeEnd` |
   | 12 | 4 | `dataOffset` |
   | 16 | 4 | `dataEnd` |
   | 20 | 4 | `capabilitiesOffset` |
   | 24 | 4 | `capabilitiesLength` |
   | 28 | 4 | reservado (0) |

   Los offsets son relativos al inicio del segmento. `codeOffset >= 32`, `codeEnd > codeOffset`,
   `dataEnd >= dataOffset`, y los rangos de código, datos y capacidades caben en `size` y no se
   solapan. Sin datos, `dataOffset = dataEnd = 0`; `memSize >= dataEnd`; el `entryPoint` de la TOC,
   traducido a offset (`entryPoint - loadAddress`), debe caer en `[codeOffset, codeEnd)`. Las
   capacidades son `u16 count` seguido de `u16 length + UTF-8` sin NUL, máximo 256 entradas de
   128 bytes. El kernel lee `PRG3` y las capacidades desde la ROM antes de mapear; solo los rangos
   de código y datos/bss se mapean. El SHA-256 del programa es el del segmento completo y es la
   identidad de `Principal.Program`.
6. Un `.flow` v2, un `magic` distinto, `formatVersion != 3`, `isaVersion != 32`,
   `abiVersion != 3`, `headerSize != 80`, `tocEntrySize != 64`, `hashAlgorithm != 1`, un `type` 0
   u 8+, un `PRG3` con magic distinto, cualquier reservado distinto de cero o cualquier límite
   incumplido se rechaza con `UnsupportedFormatException`/`InvalidRomException`. No hay lectura
   doble ni migración en runtime. Los fixtures v2 del compilador son material de test, no
   artefactos aceptados por v3.

## 9. Arranque y carga de programas

1. El host valida la ROM, la mapea físicamente y arranca en `resetVector` (offset del payload
   BIOS, no en `0`) con MMU apagada, `S`,
   interrupciones deshabilitadas y registros de §1.8. Cada core arranca con su `COREID`; el core 0
   es el boot core y hace POST: pila, `TVEC`, PIC, timer, NVRAM, display, page tables y `SYSINFO`;
   los cores 1..N-1 esperan la liberación del kernel en `HALT` o en una espera cooperativa y no
   ejecutan guest antes. Después el BIOS enciende `STATUS.MMU` y salta al bootloader.
2. El bootloader solo copia y verifica el segmento `KERNEL` de ROM a RAM y salta a su entry point.
   No hace POST, no reconstruye page tables, no cambia el stack ni enciende interrupciones.
3. El kernel inicializa el scheduler, la cola, las syscalls y los timers; carga los segmentos
   `PROGRAM` por TOC directa, sin filesystem, verificando hash y `PRG3`, y crea un proceso por
   programa. El máximo es 64 programas. Los programas no son relocalizables en v3.0: el linker fija
   `loadAddress`; el kernel mapea el rango de código R/X y el de datos/bss R/W, ambos con `U = 1` y
   `W^X`, y zero-fill desde `max(dataEnd, codeEnd)` hasta `memSize`. Los datos de programa no se
   cargan como segmentos DATA separados.
4. El kernel asigna `PIDENT` en el contexto de cada proceso, en orden de aparición de los
   segmentos `PROGRAM` en la TOC, y `MIDENT` al ejecutar la política de un mensaje. El host mailbox
   (IRQ 5) entrega el handle opaco; el kernel lo encola y lo conserva hasta la decisión, cancelación
   o apagado. El host no conoce la lógica del kernel ni cablea programas.
5. Si un trap ocurre con `TVEC = 0`, si el doble fallo se repite, si el watchdog expira o si una
   instrucción/servicio no está soportado, la ejecución termina con error explícito; no hay
   fallback silencioso ni éxito parcial. Cambiar la ROM exige reiniciar la VM.

## 10. Límites y valores por defecto

| Límite | Valor por defecto | Rango / nota |
|---|---:|---|
| RAM por VM | 64 MiB | 16 MiB–1 GiB configurable; 4 GiB máximo arquitectónico |
| ROM | 16 MiB | fijo por mapa |
| Frames | 4 KiB | asignación dispersa bajo demanda |
| TOC | 256 entradas | 64 bytes por entrada |
| Programas | 64 | `PIDENT` 0–63 |
| Núcleos por VM | 1..64 | configurable (`CORE_COUNT`); `iflow-16` = 1 por diseño; `>1` exige V3-2 |
| TLV por buffer | 1 MiB | validado en un único punto |
| Payload sync | 64 KiB | configurable; exceso `DENIED` |
| Timeout de acción | 1000 ms | solo para una acción `HOST`; no es límite de la VM |
| Quantum de host | 1.000.000 instrucciones / 50 ms | slice de preempción por core; no es fuel ni límite total |
| Timer | 1 kHz | periodo 1 ms; `RDTIME` en ns |
| NVRAM | 4 KiB | persistente entre reinicios |
| Cola | handles 32 bits | slot 16 + generación 16 |
| Páginas | 4 KiB | dos niveles; sin páginas grandes en v3.0 |

### 10.1. Cierre de las decisiones abiertas de §8.8

| Preguntas | Decisión |
|---|---|
| 1 | La máquina v3 **reemplaza** a la v2; no hay targets conviviendo. |
| 2 | El contenedor ROM se mapea en `0x0000_0000`; `resetVector` es el offset 4 KiB-alineado del payload BIOS tras cabecera y TOC. |
| 3 | 64 MiB por defecto; 16 MiB–1 GiB configurable; 4 GiB máximo arquitectónico. |
| 4 | Páginas de 4 KB, dos niveles 10+10+12; sin páginas grandes en v3.0. |
| 5 | Compresión de frames **fuera** de v3.0; se medirá antes de proponerla. |
| 6 | Handle opaco de 32 bits (slot 16 + generación 16); el ABI no lo interpreta. |
| 7 | Timer/RTC y contador virtual de ciclos; `RDCYCLE` por core y `RDTIME` global. |
| 8 | Carga por TOC directa; sin filesystem en v3.0. |
| 9 | `U/S`, páginas `U`, `W^X` y capacidades baseline/sync por SHA-256. |
| 10 | `.flow` v2 rechazado; sin migración runtime; se reensambla/recompila a v3. |
| 11 | ROM mapeada R/X, no como dispositivo de bloque. |
| 12 | Sin benchmark en V1; la aceptación de rendimiento se mide en V6. |
| 13–17 | Codificación de 3/7 bytes, cajas jerárquicas, `MOV` único, sin `LI` opcode, little-endian, campos de 4 bits y `size` de 2 bits. |
| 18–21 | Sin fuel en el ABI; quantum de host no enmascarable; watchdog de paso activo por defecto y desactivable en configuración; timer IRQ + `HALT`; `RDCYCLE`/`RDTIME`; ciclos como diagnóstico. |
| 22–25 | Contadores de 64 bits con snapshot atómico; 1 GHz/1 ns; `RDTIME` avanza también al dormir; busy-wait se permite pero el watchdog lo corta si está activo; dormir es `HALT` con timer, no un bucle. |
| 27 | `RDCYCLE`/`RDTIME`, timer MMIO e IRQ 0; el RTC es el mismo reloj virtual. |
| 28 | `HALT` no lleva deadline; el kernel programa el timer y duerme; una IRQ despierta; `HALT` exige `IE = 1`. |
| 29 | Token bucket en RAM del guest; sobrevive sleep; no sobrevive reinicio salvo que se guarde en NVRAM. |
| 30 | Se eliminan `ROUTE_RATE_LIMIT` y `DECISION_RATE_LIMIT`; una sola decisión de guest. |
| 31 | Cap duro del host separado; tasa `RATE_LIMITED`, payload `DENIED`, timeout `TIMEOUT`. |
| 32 | El quantum se comprueba en frontera de instrucción y devuelve `PREEMPTED` sin abortar la unidad ni revertir el journal; el watchdog de paso, activo por defecto y desactivable en `config/iflow-32/config.yml`, solo marca `FAULTED` el core si un único paso supera 50 ms y el journal abierto no se confirma. El `machineLock` global solo se usa para ciclo de vida. |
| 33 | `PERMISSION`/`WORLD` vacíos o no verificables son fail-closed (`allowed = false`). |
| 34 | Una entrega diferida no cuenta hasta `DELIVER`; si se cancela o apaga, `NOT_DELIVERED`/`CANCELLED`, nunca éxito. |
| Núcleos | `iflow-32` tiene `CORE_COUNT` configurable (1..64) intra-VM; `iflow-16` conserva un único vCPU por diseño. El kernel guest decide el scheduler; el host no crea una VM por core ni hace fallback. |

Las preguntas 26 y 35–38 ya estaban cerradas en §5.4 y ADR-031; este ADR no las reabre.

## 13. Criterios de validación de la implementación

V1 se cierra con este ADR. V2–V5 deben demostrar, como mínimo:

1. Ensamblador v3: codifica y decodifica todas las instrucciones, rechaza formas reservadas y
   produce ROM con hash/TOC válidos; `LI` solo como alias.
2. CPU: flags, saltos, `CALL`/`RET`, pila, alineación, `ECALL`/`ERET`, doble fallo y watchdog.
3. MMU: page walk de dos niveles, `U/S`, `W^X`, `A/D`, COW/snapshot, TLB coherente y límites de
   frame; ningún `AIOOBE` escapa como fallo de VM.
4. IRQ/tiempo: PIC, máscara, prioridad, `ACK`, timer periódico/one-shot, `RDCYCLE`/`RDTIME` sin
   tearing, `HALT`/sleep y avance de tiempo sin busy-wait.
5. ABI `HOST`: TLV, estados, capacidad insuficiente, buffers inválidos, `PIDENT`/`MIDENT`,
   `ROUTE_DECISION` única, read-your-writes y mapeo de `SyncResult`.
6. ROM: rechazo de v2, magic/versión, hash, solapamientos, `memSize`, `W^X`, `PRG3`, capacidades y
   límites; carga BIOS/boot/kernel/programa.
7. Integración: un mensaje real atraviesa el pipeline con una política `.flow` v3 y produce el
   mismo resultado observable que el contrato de ADR-031/ADR-036, sin fuel y sin doble camino.
8. Multinúcleo: `CORE_COUNT = 1` determinista; `CORE_COUNT > 1` exige estado por
   core, atómicas, IPI, TLB shootdown, MMIO/PIC por core, journal concurrente y watchdog de paso
   configurable por core. V3-2 entrega esas piezas y habilita el rango completo. `iflow-16` no
   implementa multinúcleo.
