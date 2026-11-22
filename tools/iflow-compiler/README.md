# iflow-compiler — TypeScript Assembler (.flowasm → .flow)

> **Estado**: ✅ Funcional  
> **Ubicación**: `tools/iflow-compiler/`  
> **Lenguaje**: TypeScript (Node.js 18+)

---

## Qué hace

Convierte código ensamblador `.flowasm` (texto legible) a binario `.flow` (ejecutable por la VM iflow).

- **Entrada**: `.flowasm` (mnemónicos, labels, directivas, expresiones `LABEL+OFFSET`)
- **Salida**: `.flow` binario (FLOW v2 de `iflow-16` o FLOWROM v3 de `iflow-32`, según `-f`)
- **Una CLI, dos backends**: `iflow-as` comparte el parser de argumentos; `-f/--format`
  elige `iflow-16` (ISA v2 de 16 bits) o `iflow-32` (ISA v3 de 32 bits, módulo
  `src/v3/`). Los opcodes y formatos no se mezclan.

---

## Instalación

```bash
cd tools/iflow-compiler
npm ci
npm run build
```

Requiere **Node.js 18+** y **npm**.

---

## Uso (CLI)

```bash
# Compilar con iflow-16 (formato por defecto)
node dist/cli.js programa.flowasm -o programa.flow

# Con opciones de iflow-16
node dist/cli.js programa.flowasm \
  -f iflow-16 \
  -o programa.flow \
  -O 0x0000 \        # origen (con firmware, sube a 0x1000)
  -e main \          # entry point label
  -l \               # genera .lst (tabla de símbolos + listado)
  -d \               # activa debugInfo en el objeto; el .flow no lleva sección de debug
  -v                 # verbose

# Programa bare-metal (sin ROM de firmware)
node dist/cli.js programa.flowasm -o programa.flow --no-firmware
```

`-f/--format` acepta `iflow-16` (por defecto) y `iflow-32`. Las opciones de un
backend se rechazan de forma explícita en el otro: `-O`, `-d` y `--no-firmware`
son de `iflow-16`; `-R/--reset` y `--max-rom` son de `iflow-32`.

> Por defecto se antepone la **ROM de firmware** (0x0000–0x0FFF) y el código de
> usuario se coloca en 0x1000. `--no-firmware` compila bare-metal (origen 0x0000).

**Ejemplo**:

```bash
# Compilar el programa de prueba (con listado de símbolos)
node dist/cli.js test/firmware.flowasm -o test/firmware.flow -l
# ✓ Assembled test/firmware.flowasm → test/firmware.flow (4648 bytes)
```

---

## Desensamblador (`.flow` → texto/JSON)

`dist/disasm-cli.js` (bin `iflow-dis`) decodifica el **rango de usuario** del
`.flow` v2 y produce un listado con la CFG de bloques básicos. No reconstruye el
grafo de reglas del editor: la ROM no guarda ese grafo, así que se muestra lo que
los bytes dicen.

```bash
# Listado legible
node dist/disasm-cli.js test/firmware.flow

# JSON inspeccionable (instrucciones, bytes, targets y CFG)
node dist/disasm-cli.js test/firmware.flow --json

# Forzar el inicio del rango de usuario
node dist/disasm-cli.js test/simple.flow --user-start 0x1000

# Omitir la verificación SHA-256 (solo para inspeccionar bytes alterados)
node dist/disasm-cli.js test/simple.flow --no-verify
```

- **Corte de usuario.** Con firmware, el código de usuario empieza en `0x1000`;
  un binario `--no-firmware` se decodifica desde `0x0000`. El `entryPoint` de la
  cabecera es `fw_reset` cuando hay BIOS, no el `main`; por eso el CFG arranca en
  el inicio del rango de usuario.
- **Salidas externas.** Un `CALL` a una utilidad del firmware (`fw_strlen`, …)
  aparece como `exit` del bloque, no como error.
- **Errores explícitos.** Hash que no coincide, cabecera truncada, opcode
  desconocido o salto fuera del código producen un diagnóstico y el CLI termina
  con código distinto de cero. Un opcode desconocido se emite como `.byte` y la
  decodificación continúa.
- **API.** `disassemble(bytes, options)`, `parseProgramImage(bytes)`,
  `decodeProgramImage(program, options)` y `formatListing(result)`.

---

## Sintaxis `.flowasm`

### Instrucciones (mnemónicos ISA v2)

```asm
; Data transfer
LI   R0, 0x1234       ; Load immediate 16-bit
MOV  R1, R2           ; Move register (packed)
LDB  R0, 0x3000       ; Load byte from virtual address (data region)
LDW  R1, 0x3002       ; Load word (little-endian)
STB  R2, 0x8000       ; Store byte (output buffer)
STW  R3, 0x8002       ; Store word
; Indirect memory (ISA v2): the address is the value of a register
LDBI R3, [R1]         ; Load byte from the address in R1
LDWI R4, [R1]         ; Load word from the address in R1
STBI R2, [R1]         ; Store byte to the address in R1
STWI R2, [R1]         ; Store word to the address in R1
COPY 0x3000, 0x3200, 64  ; Copy block (no overlap)
MOVE 0x3000, 0x3002, 64  ; Move block (allows overlap)

; Arithmetic
ADD  R0, R1
SUB  R0, R1
MUL  R0, R1
DIV  R0, R1
MOD  R0, R1

; Logic
AND  R0, R1
OR   R0, R1
XOR  R0, R1
NOT  R0

; Shifts
SHL  R0, R1
SHR  R0, R1
SAR  R0, R1

; Comparison
CMP  R0, R1      ; signed
CMPU R0, R1      ; unsigned

; Control flow
JMP  label
JZ   label       ; jump if zero
JNZ  label       ; jump if not zero
JC   label       ; jump if carry
JV   label       ; jump if overflow
CALL label
RET

; Services
HOST              ; ABI v2: R0=op, R1=options, R2=base, R3=length

; Control
HALT
TRAP 0xFF
```

### Directivas

| Directiva | Uso |
|-----------|-----|
| `.origin 0x0000` | Set program origin |
| `.entry main` | Entry point label |
| `.section code` | Code section |
| `.section data` | Data section |
| `.const NAME 0x1234` | Constant (added to constant pool) |
| `.capability "property.read"` | Declare capability |
| `.align 4` | Align to boundary |
| `.equ LABEL 0x1234` | Equate (alias for label) |

### Labels y expresiones

```asm
main:
    LI R0, 0x1234
    CALL subroutine

subroutine:
    ; ...
    RET

; Expresiones LABEL+OFFSET
LDW R1, RESULT_BUFFER+2
COPY SRC+10, DST, LEN
```

### Registros

| Nombre | Índice | Tipo |
|--------|--------|------|
| R0–R7 | 0–7 | GPR |
| IP    | 8    | Control |
| SP    | 9    | Control |
| FP    | 10   | Control |
| FL    | 11   | Control |

### Formato RR (packed)

Instrucciones de 2 registros usan un solo byte: `RR = (rd << 4) | rs`

```asm
ADD R0, R1     ; byte = 0x01
MOV R3, R7     ; byte = 0x37
```

---

## Backend v3 (`-f iflow-32`)

El módulo `src/v3/` ensambla la ISA de 32 bits de `iflow-32` (ADR-037 §3–§4) y
emite el contenedor `FLOWROM` v3 (§8). Es independiente del ensamblador v2: no
importa sus opcodes, registros, secciones ni formato binario.

```bash
npm run build
node dist/cli.js kernel.flowasm -f iflow-32 -o kernel.flow
# o, tras npm link / instalación:
iflow-as kernel.flowasm -f iflow-32 -o kernel.flow -l
```

Opciones comunes: `-o/--output`, `-e/--entry <label>` y `-l/--list` (imprime la
tabla de símbolos). Específicas de `iflow-32`: `-R/--reset <addr>` y
`--max-rom <bytes>` (1–16777216). `-O`, `-d` y `--no-firmware` se rechazan en
este formato.

### Secciones

| Sección | Tipo TOC | `org` por defecto | Flags por defecto |
|---|---:|---:|---|
| `.section .bios` | 1 | `0x00000000` | `R|X` |
| `.section .boot` | 2 | `0x80000000` | `R|X` |
| `.section .kernel` | 3 | `0x80001000` | `R|X` |
| `.section .program.<nombre>` | 4 | `0x02000000` | `R|X|U` |
| `.section .data.<nombre>` | 5 | `0x80002000` | `R|W` |
| `.section .font.<nombre>` | 6 | `0x80003000` | `R` |
| `.section .config.<nombre>` | 7 | `0x80004000` | `R` |

Un ROM exige exactamente un `BIOS`, un `BOOT` y un `KERNEL`; admite hasta 64
`PROGRAM`. Cada `org` debe estar alineado a 4 KiB. El ensamblador aplica las
reglas por tipo de ADR-037 §8.5: BIOS R/X en `0`, BOOT/KERNEL en espacio de
kernel, PROGRAM en espacio de usuario con `U=1` y `W=0`, DATA como datos
supervisor, y rechaza `W && X`.

### Directivas

| Directiva | Uso |
|---|---|
| `.org <expr>` | Dirección de carga; solo antes del contenido de la sección. |
| `.entry <label>` | Punto de entrada del segmento. |
| `.reset <label>` | Vector de reset global (alternativa a `-e`/`-R`). |
| `.align <n>` | Rellena con ceros hasta el siguiente múltiplo de `n`. |
| `.byte <expr>...` | Bytes. |
| `.half <expr>...` | Mitades little-endian. |
| `.word <expr>...` | Palabras little-endian de 32 bits. |
| `.string "texto"...` | UTF-8 terminado en NUL por cada argumento. |
| `.space <n>` | Reserva `n` bytes a cero. |
| `.capability "nombre"` | Capacidad de un `PROGRAM` (máx. 128 B, máx. 256). |
| `.flags R\|W\|X\|U` | Sobrescribe los flags del segmento. |
| `.globl <label>` | Marcador de símbolo global; no cambia la emisión. |

`PROGRAM` se envuelve en la subcabecera `PRG3` de 32 bytes. El código empieza en
`org + 32`; las capacidades van tras el código y la TOC apunta al `entryPoint`
absoluto dentro del rango de código.

### Instrucciones y operandos

- Registros `R0`–`R15`; alias `SP` = `R14`, `FP` = `R13`, `LR` = `R15`.
- `MOV`, `LEA`, `PUSH`, `POP`; ALU `ADD`/`ADC`/`SUB`/`AND`/`OR`/`XOR`/`SHL`/
  `SHR`/`SAR`/`MUL`/`DIV`/`DIVU`/`MOD`/`MODU`/`CMP`/`TEST`; control `JMP`/`JCC`/
  `CALL`/`RET`/`ECALL`/`ERET`/`HALT`/`BREAK`/`NOP`; sistema `HOST`/`RDCYCLE`/
  `RDTIME`/`CSRRW`/`CSRRS`/`CSRRC`/`CSRR`/`CSRW`/`SFENCE.VMA`/`FENCE`; atómicas
  `LR.W`/`SC.W`/`AMOADD.W`/`AMOSWAP.W`/`AMOAND.W`/`AMOOR.W`.
- `LI Rd, imm` es un alias de `MOV Rd, imm`; `Jcc` genérico es `JCC <cond>,
  target` y los alias (`JZ`, `JNE`, `JGE`, …) llevan un solo destino.
- Sufijos `.B`/`.H` solo en `MOV` de memoria. `MOV [mem], imm` no existe: se
  escribe `MOV Rt, imm` y luego `MOV [mem], Rt`.
- Memoria: `[Rbase]`, `[Rbase + disp]`, `[Rbase + Rindex]`,
  `[Rbase + Rindex + disp]`; el índice no se escala.
- Nombres CSR: `STATUS`, `EPC`, `CAUSE`, `BADVADDR`, `TVEC`, `PTBR`, `SCRATCH`,
  `PIDENT`, `MIDENT`, `FLAGS`, `COREID`.
- Saltos inmediatos se codifican relativos a `PC + 7 + disp32`.

Cada instrucción ocupa 3 bytes en forma registro-registro y 7 bytes en
inmediato/memoria; todo es little-endian. `MOV` inmediato, ALU, `PUSH`/`POP` y
atómicas exigen `size = 32`.

### Formato `FLOWROM` v3

Cabecera de 80 bytes (`FLOWROM\0`, versiones 3/32/3, `resetVector`, TOC y hash
SHA-256), TOC de 64 bytes por segmento y payloads alineados a 16 bytes. El hash
global cubre la ROM con sus 32 bytes de hash a cero; cada entrada lleva el hash
del segmento. Un `PROGRAM` añade `PRG3` con `codeOffset`, `codeEnd`, rangos de
datos y capacidades.

> **Integración pendiente (V3-2b).** El ensamblador emite los segmentos con su
> `loadAddress` virtual, pero el host todavía no copia BIOS/BOOT/KERNEL desde el
> contenedor a esas direcciones. `RomParser` valida la imagen y el decodificador
> v3 acepta sus instrucciones; la ejecución completa espera al loader del host.

Pruebas y fixture de integración:

```bash
npm run build && npm run test:v3
# Regenera el ROM de prueba que consume iflow-32:
node dist/cli.js ../../src/iflow-32/src/test/resources/rom/v3/assembler-minimal.flowasm \
  -f iflow-32 -o ../../src/iflow-32/src/test/resources/rom/v3/assembler-minimal.rom
```

---

## ROM de firmware (0x0000–0x0FFF)

Con firmware activado (por defecto) se antepone una ROM de 4 KiB con la tabla de
vectores, las constantes de servicio y utilidades reutilizables. Sus puntos de entrada
se exponen como símbolos, así que el código de usuario puede invocarlos con `CALL`:

```asm
LI R1, 0x3000
CALL fw_strlen        ; R1 = dirección de la cadena → R0 = longitud
```

| Función | Entrada | Salida |
|---|---|---|
| `fw_reset` | — (punto de entrada del BIOS, D32) | POST + encadena con el `main` |
| `fw_irq_stub` | — (manejador de IRQ por defecto, D33) | `ACK` de la línea y `RET` |
| `fw_strlen` | `R1` = cadena | `R0` = longitud |
| `fw_strcpy` | `R1` = destino, `R2` = origen | `R0` = bytes copiados |
| `fw_strcmp` | `R1` = a, `R2` = b | `R0` = 0 si iguales, si no signo(a−b) |
| `fw_memcpy` | `R1` = destino, `R2` = origen, `R3` = len | `R0` = len |
| `fw_memset` | `R1` = destino, `R2` = byte, `R3` = len | `R0` = len |
| `fw_call_host` | `R0`=svc, `R1`=options, `R2`=base, `R3`=len | passthrough a `HOST` |
| `fw_prop_read` | `R1`=scope, `R2`=propId, `R3`=buf, `R4`=len | `HOST PROPERTY_READ` |
| `fw_prop_write` | `R1`=scope, `R2`=propId, `R3`=buf, `R4`=len | `HOST PROPERTY_WRITE` |
| `fw_perm_check` | `R1`=buf, `R2`=len | `HOST PERMISSION_CHECK` |
| `fw_route_decide` | `R1`=decision, `R2`=buf, `R3`=len | `HOST ROUTE_DECISION` |
| `fw_state_read` | `R1`=key, `R2`=buf, `R3`=len | `HOST STATE_READ` |
| `fw_state_write` | `R1`=key, `R2`=buf, `R3`=len | `HOST STATE_WRITE` |
| `fw_channel_resolve` | `R1`=buf, `R2`=len | `HOST CHANNEL_RESOLVE` |

Las utilidades de cadena/memoria usan el direccionamiento indirecto de la ISA v2
(`LDBI`/`STBI`) para recorrer los buffers.

**BIOS activo (D32).** Con firmware activado, el `entryPoint` de la imagen es
`fw_reset`, **no** el `main`: la máquina resetea en el BIOS. En el primer arranque
(frío) `fw_reset` limpia la RAM volátil de datos (0x3000–0x4FFF), configura la
pantalla por MMIO (`BASE=0xF000`, `COLS=80`, `ROWS=25`, `CTRL=enable`), publica la
tabla de interrupciones en la NVRAM, marca el arranque y encadena con el `main`; en
los siguientes arranques (caliente) salta directo al `main`. La cola de la página
del BIOS (0x0F00–0x0FFF) está reservada para el bloque de control MMIO (pantalla
0x0F00–0x0F0F, NVRAM 0x0F10–0x0FEF, PIC 0x0FF0–0x0FFF); el ensamblador da error si
el firmware creciera dentro de ella.

**Interrupciones (D33).** El BIOS publica en la NVRAM una tabla de vectores
(`[boot][magic][count][handler_0]…`) con todas las entradas apuntando a
`fw_irq_stub`. El programa puede instalar su propio manejador sobrescribiendo su
entrada por MMIO y habilitar la línea en el PIC. El ensamblador expone las
constantes `PIC_BASE`, `PIC_PENDING`, `PIC_ENABLE`, `PIC_RAISE`, `PIC_ACK`,
`PIC_IN_SERVICE`, `PIC_CURRENT`, `IRQ_TABLE_MAGIC`, `IRQ_TABLE_ENTRIES` e
`IRQ_LINES`. El manejador termina con `RET`; el runtime salva y restaura el estado
completo de la CPU, de modo que la interrupción es transparente al código
interrumpido.

---

## Ejemplo completo

```asm
; firmware.flowasm
.entry main

.const SERVICE_PROPERTY_READ  0x0100
.const SERVICE_ROUTE_DECISION 0x0300
.const DECISION_LOG          0x00
.const DECISION_DROP         0x01
.const SCOPE_MESSAGE         0x01
.const PROP_CONTENT          0x0010

.section code

main:
    ; Read message.content into the input buffer at 0x7000.
    ; HOST ABI v2: R0=op, R1=options=(scope<<8)|propId, R2=base, R3=length.
    LI R0, SERVICE_PROPERTY_READ
    LI R1, 0x0110          ; (SCOPE_MESSAGE << 8) | PROP_CONTENT
    LI R2, 0x7000          ; base vaddr of the buffer (input buffer)
    LI R3, 0x0100          ; max bytes to write
    HOST                   ; R0=status, R3=bytes written

    ; Check first byte == 's' (0x73)
    LDB R0, 0x7000
    LI R1, 0x73
    CMP R0, R1
    JZ drop

    ; Continue (LOG)
    LI R0, SERVICE_ROUTE_DECISION
    LI R1, DECISION_LOG
    HOST
    HALT

drop:
    LI R0, SERVICE_ROUTE_DECISION
    LI R1, DECISION_DROP
    HOST
    HALT

.capability "property.read"
.capability "routing.decide"

.end
```

Compilar:

```bash
node dist/cli.js firmware.flowasm -o firmware.flow -d
```

---

## API programática

```typescript
import { Assembler } from './assembler';

const asm = new Assembler({ origin: 0x0000, entryPoint: 'main' });

const source = `
    .origin 0x0000
    .entry main
    main:
        LI R0, 0x1234
        HALT
`;

const { program, symbols, diagnostics } = asm.assemble(source, 'test.flowasm');

if (diagnostics.some(d => d.severity === 'error')) {
    console.error('Assembly failed');
    process.exit(1);
}

// program es ProgramImage (cabecera, TOC, segmentos tipados y hash)
// Serializar a binario .flow:
const binary = asm.serializeProgramImage(program);
fs.writeFileSync('output.flow', Buffer.from(binary));
```

---

## Estructura del proyecto

```
tools/iflow-compiler/
├── src/
│   ├── assembler.ts      # Two-pass assembler (core)
│   ├── cli.ts            # Unified CLI dispatcher (iflow-as, -f/--format)
│   ├── cli-v2.ts         # ISA v2 backend runner
│   ├── cli-v3.ts         # ISA v3 backend runner
│   ├── disassembler.ts   # .flow v2 parser, decoder and CFG builder
│   ├── disasm-cli.ts     # Disassembler CLI entry point (iflow-dis)
│   ├── index.ts          # Public API barrel (library entry point)
│   ├── types.ts          # ISA definitions, types
│   └── v3/               # Independent ISA v3 assembler and FLOWROM v3 emitter
│       ├── ast.ts        # v3 syntax tree
│       ├── lexer.ts      # Tokens for .flowasm v3
│       ├── parser.ts     # Sections, directives, operands and expressions
│       ├── encoder.ts    # Instruction classification and encoding
│       ├── assembler.ts  # Two passes, segments, PRG3, hashes and validation
│       ├── types.ts      # v3 opcodes, conditions, CSRs and section types
│       └── index.ts      # Public API barrel
├── test/
│   ├── *.flowasm         # Fixtures (firmware, simple, main_only)
│   ├── *.flow            # Assembled binaries
│   ├── disasm/           # run.js (listing, CFG and error-path tests)
│   ├── validation/       # run.js + cases.json (CLI golden tests)
│   ├── cli/              # run.js (unified CLI format selection and rejection)
│   └── v3/               # run.js (v3 encoding, ROM, PRG3 and error paths)
├── package.json
├── tsconfig.json
└── README.md
```

---

## Desarrollo

```bash
# Compilar TypeScript a dist/
npm run build

# Validación del ensamblador (golden CLI: test/validation/*)
npm test            # validation + determinism + disassembler + CLI + v3

# Solo el desensamblador
npm run test:disasm

# Solo la CLI unificada
npm run test:cli

# Solo el ensamblador v3
npm run test:v3

# Recompilar el fixture de firmware
npm run build && node dist/cli.js test/firmware.flowasm -o test/firmware.flow
```

`dist/` es un artefacto generado y **no se versiona**: se produce con `npm run build`
(y también vía la tarea Gradle `:src:iflow-16:iflowCompilerBuild`).

---

## Formato binario `.flow` generado

### Cabecera (32 bytes, big-endian)

| Offset | Tamaño | Campo |
|--------|--------|-------|
| 0x00   | 4      | magic = 0x464C4F57 ("FLOW", u32 big-endian) |
| 0x04   | 1      | formatVersion = 2 |
| 0x05   | 1      | isaVersion |
| 0x06   | 1      | minAbiVersion |
| 0x07   | 1      | minFirmwareVersion |
| 0x08   | 2      | entryPoint (u16 big-endian) |
| 0x0A   | 2      | tocOffset (u16 big-endian) |
| 0x0C   | 2      | tocEntryCount (u16 big-endian; 1..16) |
| 0x0E   | 2      | headerFlags (0) |
| 0x10   | 4      | layoutMagic = 0x524F4D32 ("ROM2", u32 big-endian) |
| 0x14   | 4      | payloadSize (u32 big-endian; bytes before the hash) |
| 0x18   | 4      | reserved (0) |
| 0x1C   | 4      | reserved (0) |

### TOC (16 bytes por entrada)

| Offset | Tamaño | Campo |
|--------|--------|-------|
| +0x00  | 1      | type (1 BIOS, 2 BOOTLOADER, 3 KERNEL, 4 PROGRAM, 5 CONSTANTS, 6 CAPABILITIES, 7 DEBUG) |
| +0x01  | 1      | flags (R=1, W=2, X=4, USER=8) |
| +0x02  | 2      | virtualAddress (u16 big-endian) |
| +0x04  | 4      | fileOffset (u32 big-endian, absoluto desde el inicio) |
| +0x08  | 2      | size (u16 big-endian; bytes almacenados) |
| +0x0A  | 2      | memSize (u16 big-endian; bytes reservados; 0 en metadatos) |
| +0x0C  | 4      | reserved (0) |

Tras el TOC vienen los payloads en el mismo orden, y al final el hash SHA-256 de
32 bytes sobre todo lo anterior. Los segmentos cargables (`BIOS`..`CONSTANTS`)
se copian a memoria tal cual; `CAPABILITIES` y `DEBUG` son metadatos y no se
cargan. El compilador emite `BIOS` en `0x0000`, `PROGRAM` en página alineada
(`0x1000` con firmware, `0x0000` en bare-metal) y `CONSTANTS` en `0x2000`.
`CONSTANTS` ocupa como máximo 4 KiB (2048 palabras); el compilador rechaza un
pool mayor en vez de emitir una imagen que el parser de runtime descartaría.

> **El magic rechaza pronto.** Un fichero que no empieza por `FLOW` se descarta como «not a .flow»
> antes de leer versiones o hash. El `layoutMagic` hace lo mismo con el contenedor: un `.flow` del
> layout anterior de 20 bytes se rechaza sin lector doble. **ROM2 (2026-11-16):** sustituye el
> contenedor monolítico por cabecera + TOC + segmentos tipados; hay que recompilar los `.flow`
> antiguos.
>
> **`-d/--debug`.** Emite un segmento `DEBUG` (dirección + fuente de cada instrucción y tabla de
> símbolos `SYM nombre = 0x…`). Es informativo: el runtime nunca lo ejecuta, y queda cubierto por el
> hash, así que no puede alterar el programa sin romper la integridad.

> **Endianness mixto (deliberado).** La cabecera, el TOC y los operandos inmediatos del *código* son
> **big-endian**; las palabras de **datos** en memoria son **little-endian**. Como los payloads se
> copian a memoria sin conversión, el segmento `CONSTANTS` se serializa ya en little-endian para que
> `LDW` lo lea bien. No es una errata: quien escriba un ensamblador o un parser nuevo debe respetar
> esa separación.

---

## Referencias

- [`src/iflow-16/README.md`](../../src/iflow-16/README.md) — legacy 16-bit VM architecture, ISA and services
- [`docs/prompts/diseno/iflow-cpu-implement.md`](../../docs/prompts/diseno/iflow-cpu-implement.md) — Especificación técnica de referencia v3 de `iflow-32`
- [`src/iflow-16/src/main/java/.../VmInterpreter.java`] — Reference interpreter implementation
- [`src/iflow-16/src/test/.../VmInterpreterTest.java`] — Instruction test vectors