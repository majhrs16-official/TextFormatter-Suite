# iflow — VM 16-bit flat 64KB + SoftMMU 4KB

> **Estado**: ✅ Implementada y probada (250 tests)  
> **Tipo**: Módulo funcional (VM interpreted)  
> **Contrato**: Implementa `Router` SPI (`core-api`)

---

## Arquitectura

iflow v2 reemplaza el motor de reglas basado en SpEL/regions por una **máquina virtual interpretada de 16 bits** con espacio de direcciones plano de 64 KB y paginación por software (SoftMMU) de 4 KB.

### Componentes principales

| Componente | Responsabilidad |
|------------|-----------------|
| `VmPolicyEngine` | Motor principal: implementa `Router`, ejecuta bytecode `.flow` via `VmInterpreter` |
| `VmInterpreter` | Fetch/decode/execute loop, ISA v1, fuel budget, SoftMMU integration |
| `VmDmaController` | Transferencias de memoria validadas (read/write/copy/move con permisos) |
| `SoftMMU` | MMU por software: 16 páginas de 4 KB, permisos R/W/X, traducción virtual→físico |
| `VmConfigLoader` | Carga binaria `.flow` (sin YAML/SpEL), fallback pass-through |
| `VmInterpreter` | Fetch/decode/execute loop, fuel budget enforcement |
| `ProgramImage` | Imagen de programa inmutable (bytecode + constantes + capabilities + hash) |
| `VmException` | Excepciones tipadas con IP capture (INVALID_OPCODE, MEMORY_FAULT, OUT_OF_FUEL, etc.) |

### Memoria

- **Espacio plano**: 64 KB (0x0000–0xFFFF)
- **Paginación**: 16 páginas × 4 KB = 64 KB
- **Permisos por página**: READ (0x1), WRITE (0x2), EXECUTE (0x4)
- **Mapeo típico**:
  - Página 0: Código (R/X)
  - Páginas 1: Constantes (R)
  - Páginas 2-3: Datos/Heap (R/W)
  - Páginas 4-5: Pila (R/W, crece hacia abajo)
  - Página 6: Buffer entrada I/O (0x6000)
  - Página 7: Buffer salida I/O (0x7000)
  - Páginas 8-15: Estado persistente / datos privados

### ISA v1 (resumen)

| Grupo | Instrucciones |
|-------|---------------|
| Data transfer | `LI`, `MOV`, `LDB`, `LDW`, `STB`, `STW`, `COPY`, `MOVE` |
| Aritmética | `ADD`, `SUB`, `MUL`, `DIV`, `MOD` |
| Lógica | `AND`, `OR`, `XOR`, `NOT` |
| Shifts | `SHL`, `SHR`, `SAR` |
| Comparación | `CMP`, `CMPU` |
| Control | `JMP`, `JZ`, `JNZ`, `JC`, `JV`, `CALL`, `RET` |
| Servicios | `HOST` |
| Control | `HALT`, `TRAP` |

**Formato instrucción**: little-endian, opcode 8-bit determina longitud (1-8 bytes)

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

Servicios estándar (13 IDs; sólo los 7 marcados ✅ están registrados hoy — el resto responde `status=5` UNSUPPORTED):

| ID | Servicio | Capability | |
|----|----------|------------|---|
| 0x0100 | PROPERTY_READ | `property.read` | ✅ |
| 0x0101 | PROPERTY_WRITE | `property.write` | ✅ |
| 0x0102 | PROPERTY_EXISTS | `property.read` | |
| 0x0200 | PERMISSION_CHECK | `permission.check` | ✅ |
| 0x0201 | PERMISSION_QUERY | `permission.check` | |
| 0x0300 | ROUTE_DECISION | `routing.decide` | ✅ |
| 0x0301 | ROUTE_REDIRECT | `routing.decide` | |
| 0x0302 | ROUTE_RATE_LIMIT | `routing.decide` | |
| 0x0500 | STATE_READ | `state.read` | ✅ |
| 0x0501 | STATE_WRITE | `state.write` | ✅ |
| 0x0600 | CHANNEL_RESOLVE | `channel.resolve` | ✅ |
| 0x0601 | CHANNEL_POLICY | `channel.resolve` | |

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
  u8  isaVersion        = 1
  u8  minAbiVersion     = 1
  u8  minFirmwareVersion= 1
  u16 entryPoint        (little-endian)
  u16 codeLength
  u16 constantsLength
  u8  capabilitiesCount
  u8[5] padding

Code section: codeLength bytes
Constants: constantsLength × u16
Capabilities: null-terminated strings
Hash: 32 bytes (SHA-256 placeholder)
```

### Compilación

Los programas se escriben en `.flowasm` y se compilan a `.flow` binario usando **iflow-compiler** (TypeScript):

```bash
cd tools/iflow-compiler
npm run build
node dist/cli.js programa.flowasm -o programa.flow -d
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
| `VmInterpreterTest` | Todas las instrucciones ISA (19 tests) |
| `VmDmaControllerTest` | 24 tests DMA (cross-page, perms, overlap) |
| `SoftMMUTest` | Traducción, permisos, fetch, bloqueos |
| `VmInterpreterTest` | Aritmética, memoria, saltos, CALL/RET |
| `VmPolicyEngineTest` | Integración Router + VM (11 tests) |
| `VmIntegrationTest` | Programas multi-instrucción |
| `VmConfigLoaderTest` | Carga binario, fallback pass-through |
| `FirmwareIntegrationTest` | **Ejecución firmware completo** (arithmetic, logic, shifts, jumps, memory, block ops, HOST services, loops) |

**Total: 250 tests passing**

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
│       │   ├── PageTable.java
│       │   ├── PageEntry.java
│       │   └── VmDmaController.java
│       ├── service/
│       │   ├── HostServiceRegistry.java
│       │   └── ServiceResult.java
│       └── loader/                    # (vacío - legacy)
│
├── src/test/java/...                  # 250 tests
└── build.gradle
```

---

## Referencias

- [`docs/prompts/iflow/implement.md`](../../docs/prompts/iflow/implement.md) — Especificación técnica completa
- [`tools/iflow-compiler/`](../../tools/iflow-compiler/) — Ensamblador TypeScript (.flowasm → .flow)
- [`docs/prompts/iflow/`](../../docs/prompts/iflow/) — Prompts y decisiones de diseño