# iflow-compiler — TypeScript Assembler (.flowasm → .flow)

> **Estado**: ✅ Funcional  
> **Ubicación**: `tools/iflow-compiler/`  
> **Lenguaje**: TypeScript (Node.js 18+)

---

## Qué hace

Convierte código ensamblador `.flowasm` (texto legible) a binario `.flow` (ejecutable por la VM iflow).

- **Entrada**: `.flowasm` (mnemónicos, labels, directivas, expresiones `LABEL+OFFSET`)
- **Salida**: `.flow` binario (header + code + constants + capabilities + hash)

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
# Compilar
node dist/cli.js programa.flowasm -o programa.flow

# Con opciones
node dist/cli.js programa.flowasm \
  -o programa.flow \
  -O 0x0000 \        # origen (con firmware, sube a 0x1000)
  -e main \          # entry point label
  -l \               # genera .lst (tabla de símbolos + listado)
  -d \               # activa debugInfo en el objeto; el .flow no lleva sección de debug
  -v                 # verbose

# Programa bare-metal (sin ROM de firmware)
node dist/cli.js programa.flowasm -o programa.flow --no-firmware
```

> Por defecto se antepone la **ROM de firmware** (0x0000–0x0FFF) y el código de
> usuario se coloca en 0x1000. `--no-firmware` compila bare-metal (origen 0x0000).

**Ejemplo**:

```bash
# Compilar el programa de prueba (con listado de símbolos)
node dist/cli.js test/firmware.flowasm -o test/firmware.flow -l
# ✓ Assembled test/firmware.flowasm → test/firmware.flow (4648 bytes)
```

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

// program es ProgramImage (header + code + constants + capabilities + hash)
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
│   ├── cli.ts            # CLI entry point (commander)
│   ├── index.ts          # Public API barrel (library entry point)
│   └── types.ts          # ISA definitions, types
├── test/
│   ├── *.flowasm         # Fixtures (firmware, simple, main_only)
│   ├── *.flow            # Assembled binaries
│   └── validation/       # run.js + cases.json (CLI golden tests)
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
npm test            # alias de npm run test:validation

# Recompilar el fixture de firmware
npm run build && node dist/cli.js test/firmware.flowasm -o test/firmware.flow
```

`dist/` es un artefacto generado y **no se versiona**: se produce con `npm run build`
(y también vía la tarea Gradle `:src:iflow:iflowCompilerBuild`).

---

## Formato binario `.flow` generado

| Offset | Tamaño | Campo |
|--------|--------|-------|
| 0x00   | 1      | formatVersion |
| 0x01   | 1      | isaVersion |
| 0x02   | 1      | minAbiVersion |
| 0x03   | 1      | minFirmwareVersion |
| 0x04   | 2      | entryPoint (u16 big-endian) |
| 0x06   | 2      | codeLength (u16 big-endian) |
| 0x08   | 2      | constantsLength (u16 big-endian) |
| 0x0A   | 1      | capabilitiesCount |
| 0x0B   | 5      | padding (zeros) |
| 0x10   | N      | code (codeLength bytes; operandos inmediatos big-endian `[hi, lo]`) |
| ...    | 2×N    | constants (u16 big-endian en el fichero) |
| ...    | var    | capabilities (null-terminated strings) |
| ...    | 32     | hash (SHA-256 of all preceding bytes) |

> **Endianness mixto (deliberado).** La cabecera y los operandos inmediatos del *código* son
> **big-endian**; las palabras de **datos** en memoria son **little-endian**, y por eso las
> constantes se reescriben en little-endian al materializarse (para que `LDW` las lea bien). No es
> una errata: el parser (`ProgramImageParser`) lee la cabecera y las constantes en big-endian.

---

## Referencias

- [`src/iflow/README.md`](../../src/iflow/README.md) — VM architecture, ISA, services
- [`docs/prompts/diseno/iflow-cpu-implement.md`](../../docs/prompts/diseno/iflow-cpu-implement.md) — Spec técnico completo
- [`src/iflow/src/main/java/.../VmInterpreter.java`] — Reference interpreter implementation
- [`src/iflow/src/test/.../VmInterpreterTest.java`] — Instruction test vectors