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
  -d \               # debug info en binario
  -l \               # genera .lst listing
  -v                 # verbose

# Programa bare-metal (sin ROM de firmware)
node dist/cli.js programa.flowasm -o programa.flow --no-firmware
```

> Por defecto se antepone la **ROM de firmware** (0x0000–0x0FFF) y el código de
> usuario se coloca en 0x1000. `--no-firmware` compila bare-metal (origen 0x0000).

**Ejemplo**:

```bash
# Compilar el programa de prueba
node dist/cli.js test/firmware.flowasm -o test/firmware.flow -d -l
# ✓ Assembled test/firmware.flowasm → test/firmware.flow (4615 bytes)
```

---

## Sintaxis `.flowasm`

### Instrucciones (mnemónicos ISA v1)

```asm
; Data transfer
LI   R0, 0x1234       ; Load immediate 16-bit
MOV  R1, R2           ; Move register (packed)
LDB  R0, 0x6000       ; Load byte from virtual address
LDW  R1, 0x6002       ; Load word (little-endian)
STB  R2, 0x7000       ; Store byte
STW  R3, 0x7002       ; Store word
COPY 0x1000, 0x2000, 64  ; Copy block (no overlap)
MOVE 0x1000, 0x1002, 64  ; Move block (allows overlap)

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
    ; Read message.content into buffer at 0x6000.
    ; HOST ABI v2: R0=op, R1=options=(scope<<8)|propId, R2=base, R3=length.
    LI R0, SERVICE_PROPERTY_READ
    LI R1, 0x0110          ; (SCOPE_MESSAGE << 8) | PROP_CONTENT
    LI R2, 0x6000          ; base vaddr of the buffer
    LI R3, 0x0100          ; max bytes to write
    HOST                   ; R0=status, R3=bytes written

    ; Check first byte == 's' (0x73)
    LDB R0, 0x6000
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
│   └── types.ts          # ISA definitions, types
├── test/
│   └── firmware.flowasm  # Comprehensive firmware test
├── package.json
├── tsconfig.json
└── README.md
```

---

## Desarrollo

```bash
# Compilar TypeScript
npm run build

# Tests (Jest)
npm run test

# Compilar y testear firmware
npm run build && node dist/cli.js test/firmware.flowasm -o test/firmware.flow -d
```

---

## Formato binario `.flow` generado

| Offset | Tamaño | Campo |
|--------|--------|-------|
| 0x00   | 1      | formatVersion |
| 0x01   | 1      | isaVersion |
| 0x02   | 1      | minAbiVersion |
| 0x03   | 1      | minFirmwareVersion |
| 0x04   | 2      | entryPoint (u16 LE) |
| 0x06   | 2      | codeLength (u16 LE) |
| 0x08   | 2      | constantsLength (u16 LE) |
| 0x0A   | 1      | capabilitiesCount |
| 0x0B   | 5      | padding (zeros) |
| 0x10   | N      | code (codeLength bytes) |
| ...    | 2×N    | constants (u16 LE each) |
| ...    | var    | capabilities (null-terminated strings) |
| ...    | 32     | hash (SHA-256 placeholder) |

---

## Referencias

- [`src/iflow/README.md`](../../src/iflow/README.md) — VM architecture, ISA, services
- [`docs/prompts/iflow/implement.md`](../../docs/prompts/iflow/implement.md) — Spec técnico completo
- [`src/iflow/src/main/java/.../VmInterpreter.java`] — Reference interpreter implementation
- [`src/iflow/src/test/.../VmInterpreterTest.java`] — Instruction test vectors