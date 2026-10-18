# iflow — Mapeo a HDL (descripción hardware)

> **Propósito (D28).** Este documento describe la VM iflow como un *SoC*: qué
> entidades existen, cómo se interconectan, qué señales lleva cada bus y cómo
> fluyen los datos. El objetivo es que la arquitectura sea legible con la
> nomenclatura estándar de una descripción hardware (módulos, puertos, buses,
> maestros/esclavos, *clock*/reset) y que el código Java refleje 1:1 esa
> estructura.
>
> No es un diseño sintetizable: es la **correspondencia** entre cada concepto
> hardware y su realización en Java, para que el comportamiento sea auditable y
> portable a un HDL.
>
> Documento vivo: la sección [8](#8-hardware-previsto) reúne el hardware que aún
> no está implementado (pantalla, BIOS activo, tabla de IRQ).

---

## 1. Vista general del SoC

```
                              VmBus  (interconnect / sistema de bus)
        address[15:0] · data[7:0] · control(R/W/X) · privilege(PROGRAM|SUPERVISOR)
        ┌──────────────────────────────────────────────────────────────────────┐
        │                                                                        │
  MAESTROS                                                                  ESCLAVO
  ────────                                                                  ───────
  ┌───────────────┐        ┌───────────────────┐        ┌────────────────────────────┐
  │     CPU       │        │      DMA          │        │          SoftMMU            │
  │ VmInterpreter │        │ VmDmaController   │───────▶│  decode + translate + perm │
  │ (fetch/decode │───────▶│ (bulk transfers)  │        │        │                   │
  │  /execute)    │        │  SUPERVISOR       │        │        ▼                   │
  │  PROGRAM      │        └───────────────────┘        │  ┌──────────────────────┐  │
  └──────┬────────┘                                     │  │   PhysicalMemory     │  │
         │                                              │  │  (RAM 64 KB / frames)│  │
         │ HOST (servicio/trap)                         │  └──────────────────────┘  │
         ▼                                              └────────────────────────────┘
  ┌───────────────────────┐                                      ▲
  │  HostServiceRegistry   │        HostContext (puerto del host)  │
  │  (controlador «syscall»)│◀─────  message/actor/channel/state ──┘
  └───────────────────────┘

  Memoria de programa (solo lectura):
  ┌──────────┐   ROM de firmware (BROM)  0x0000-0x0FFF   (opcional)
  │  BROM    │
  └──────────┘

  Previsto (sección 8):  BIOS activo · IRQ (línea de interrupción + tabla)
```

Un **único** bus de datos (`VmBus`) conecta a todos los maestros (CPU y DMA) con
los esclavos: la memoria (`SoftMMU`) y los periféricos (`VmDevice`, hoy la
pantalla `VmDisplay`). La CPU **no** accede a la memoria física directamente:
emite transacciones por el bus. Lo mismo hace el DMA. El *interconnect* decodifica
la dirección: si pertenece a un periférico, enruta la transacción al dispositivo
(MMIO, sin traducción ni permisos); si no, la MMU traduce virtual→físico,
comprueba permisos y *propiedad* (privilegio) y sirve el dato desde
`PhysicalMemory`.

---

## 2. Módulos (entidad HDL ↔ clase Java)

| Entidad HDL | Clase Java | Dominio | Responsabilidad |
|---|---|---|---|
| `cpu` | `vm/execution/VmInterpreter` | maestro (PROGRAM) | Fetch/decode/execute, ISA v2, *fuel budget*; inmutable y compartida por programa |
| `dma` | `vm/memory/VmDmaController` | maestro (SUPERVISOR) | Transferencias en bloque host↔VM (`read/write/copy/move`) |
| `interconnect` | `vm/memory/VmBus` | — | Bus de sistema: única vía de transacción de los maestros; lleva el privilegio y **decodifica MMIO** hacia los periféricos |
| `mmu` | `vm/memory/SoftMMU` | esclavo | Decodificación de dirección, traducción V→F, permisos R/W/X y propiedad; hospeda la lista de periféricos |
| `device` | `vm/memory/VmDevice` | esclavo | Interfaz de periférico: decodifica su rango y responde a las transacciones |
| `video` | `vm/video/VmDisplay` | esclavo (MMIO) | Pantalla de texto mapeada en memoria (D31): celda de 2 B (carácter + atributo `[letra:4][fondo:4]`) y banco de registros MMIO |
| `pagetable` | `vm/memory/PageTable` + `PageEntry` | esclavo (interno) | 16 entradas (páginas de 4 KB); cada una mapea vpage→frame + permisos |
| `ram` | `vm/memory/PhysicalMemory` | esclavo (interno) | Banco físico de 64 KB en frames de 4 KB con *copy-on-write* |
| `brom` | ROM embebida en `ProgramImage` (cargada por el *boot loader*) | esclavo | Páginas de código kernel (firmware), R/X, solo escritura supervisor |
| `regfile` | `vm/isa/RegisterFile` | CPU (interno) | 8 registros de propósito general de 16 bits + EFLAGS/SR + IP/SP |
| `syscall` | `vm/service/HostServiceRegistry` | — | Despacha `HOST` a servicios; copia argumentos/resultados vía DMA |
| `host_port` | `vm/service/HostContext` | — | Puerto formal hacia estado propiedad del host (mensaje/canal/estado) |
| `isa` | `vm/isa/Opcode` | — | Tabla de opcodes y longitudes de instrucción |
| `image` | `vm/isa/ProgramImage` | brom | Imagen inmutable: bytecode + constantes + capabilities + hash |
| `ctx` | `vm/execution/ExecutionContext` | — | Estado del core: banco de registros, IP/SP, ventana I-cache, journal, E/S |
| `soc_top` | `vm/engine/VmPolicyEngine` | — | Integración: carga el programa, fija el mapa, ejecuta y valida el resultado |

**Convención de mapeo**: cada «entidad» es una clase cohesionada; los «puertos»
son sus métodos públicos; el estado interno son sus campos. `VmPolicyEngine`
actúa de *top-level* que instancia y cablea los módulos.

---

## 3. Señales del bus (`VmBus`)

El bus modela una transacción síncrona simple; cada llamada Java es un ciclo de
transferencia. No hay líneas físicas, pero la nomenclatura es la de un bus:

| Señal | Significado | En Java |
|---|---|---|
| `address[15:0]` | Dirección virtual completa (sin offset) | parámetro `vaddr` |
| `data[7:0]` / `data[15:0]` | Dato (byte o palabra *little-endian*) | valor leído/devuelto |
| `we` (write enable) | Escritura | `writeByte` / `writeWord` / `writeBlock` |
| `re` (read enable) | Lectura | `readByte` / `readWord` / `readBlock` |
| `x` (execute) | *Fetch* de instrucción | `fetchByte` / `fillCodeWindow` |
| `priv` | Modo del maestro: `PROGRAM` \| `SUPERVISOR` | `bus.privilege()` |
| `len` | Longitud de transferencia en bloque | parámetro `length` |

**Protocolo**

- Todas las transacciones atraviesan `VmBus`; el maestro nunca toca
  `PhysicalMemory`.
- El *interconnect* decodifica primero los periféricos (`SoftMMU.deviceFor`): si la
  dirección pertenece a un `VmDevice`, la transacción es MMIO y se entrega al
  dispositivo (sin traducción ni permisos); si no, va a la memoria.
- Para la memoria, el esclavo (`SoftMMU`) aplica, en este orden: *bounds* →
  *translate* (válido) → permisos (`R`/`W`/`X`) → propiedad (`priv` vs página
  *kernel*).
- Un fallo se señala con `VmException` (`MEMORY_FAULT`, `PRIVILEGE_FAULT`), con la
  IP capturada.

**Dominios**: hay dos niveles de autoridad (no se reproducen los *rings* de x86):

- `PROGRAM` (ring 3): la CPU. Limitada por permisos de página y capabilities.
- `SUPERVISOR` (ring 0): host, *boot loader* y DMA. Puede además escribir páginas
  *kernel* (ROM/constantes).

El `ExecutionContext` deriva ambos buses del mismo esclavo: `bus()` es el bus de
la CPU (PROGRAM) y `dma()` el bus del DMA (SUPERVISOR). Así el privilegio viaja
**en el bus**, no en un registro global.

---

## 4. *Clock* y *reset*

- **Clock**: la máquina es interpretada; el «ciclo» es una instrucción (o una
  transacción de bus). No hay señal de reloj global. El *fuel budget* limita el
  número de ciclos por ejecución (equivalente a un *watchdog*).
- **Reset** (arranque de una ejecución): `ExecutionContext.initialize()` restablece el **estado del
  core** (registros, IP/SP, fuel, journal, ventana I-cache) y los buffers de E/S; **no** borra la RAM
  del programa. El arranque (`setProgram` + `setupProgramMappings`) es el *boot loader*: mapea la
  imagen y publica el snapshot de arranque. Como la máquina es **una sola y viva**, la memoria del
  programa persiste entre mensajes (D30).
- **Reset de dispositivo**: la E/S de la ejecución se limpia con
  `dma().clearBlock(...)` (no copia si el frame compartido ya es cero).

---

## 5. Flujo de datos

1. **Fetch (CPU → interconnect → mmu → ram)**
   `fillCodeWindow(vpage)` valida `EXECUTE` en la página y copia sus 4 KB a la
   ventana I-cache del `ExecutionContext`; el intérprete decodifica desde ahí.
   La instrucción se lee de **memoria**, no de la imagen de despliegue.

2. **Load/store (CPU → interconnect → mmu → ram)**
   `readByte`/`writeByte`/`readWord`/`writeWord` con `priv = PROGRAM`.

3. **Transferencia en bloque (DMA → interconnect → mmu → ram)**
   `VmDmaController` emite `readBlock`/`writeBlock` con `priv = SUPERVISOR`.
   Es la **única** vía host↔VM: el engine nunca inyecta memoria saltándose el DMA.

4. **Servicio `HOST` (CPU → syscall → host_port)**
   La CPU ejecuta `HOST`; `HostServiceRegistry` decodifica el ABI v2
   (`R0..R4`), lee argumentos / escribe resultados **vía DMA** y consulta el
   estado del host por `HostContext`. No es una instrucción específica de
   dominio: es el «controlador de servicios» de la máquina.

5. **Entrada/salida**: `0x7000` (entrada) y `0x8000` (salida) son regiones
   genéricas de E/S; el host materializa el mensaje en `0x7000` y la salida se
   consume explícitamente por servicio/DMA (D19). La salida canónica sigue siendo
   el *journal*.

6. **MMIO de dispositivo (CPU/DMA → interconnect → device)**
   Si la dirección cae en el rango de un periférico (la ventana del framebuffer o
   el banco de registros en `0x0F00`), el *interconnect* entrega la transacción al
   `VmDisplay` (D31). Así cada escritura a la ventana se refleja en pantalla, sin
   pasar por la MMU.

---

## 6. Decodificación de dirección / mapa de memoria

Fuente única: `vm/memory/VmMemoryMap`. El mapa es **independiente de si la ROM de
firmware está activa** (el código siempre reserva dos páginas):

| Región | Dirección | Permisos | Propiedad | Uso |
|---|---|---|---|---|
| Código (ROM) | `0x0000-0x0FFF` | R/X | kernel | Firmware (con firmware ON) |
| Código (usuario) | `0x1000-0x1FFF` | R/X | kernel | Programa de usuario |
| Constantes | `0x2000-0x2FFF` | R | kernel | Sección de solo lectura |
| Datos/heap | `0x3000-0x4FFF` | R/W | user | Estado volátil de la ejecución |
| Pila | `0x5000-0x6FFF` | R/W | user | Crece hacia abajo desde `STACK_TOP` |
| Entrada | `0x7000-0x7FFF` | R/W | user | Buffer de entrada |
| Salida | `0x8000-0x8FFF` | R/W | user | Buffer de salida |
| RAM de usuario | `0x9000-0xFFFF` | R/W | user | Almacenamiento libre del programa; persiste entre mensajes (el runtime no la limpia) |
| MMIO de dispositivo | `0x0F00-0x0FFF` | I/O | — | Banco de registros de la pantalla, **dentro de la página del BIOS** (reutiliza la cola libre del ROM); el *interconnect* lo decodifica antes que la ROM/RAM (D31) |

El código y las constantes son **disjuntas por construcción** (prevención de
solapamiento, R3).

---

## 7. Estructura del código ↔ estructura HDL

| Estructura HDL | Estructura Java |
|---|---|
| Paquete de módulos | `vm/isa`, `vm/memory`, `vm/execution`, `vm/service`, `vm/engine` |
| Entidad | Clase (`VmInterpreter`, `SoftMMU`, …) |
| Puerto (entrada/salida) | Método público |
| Señal interna | Campo privado |
| `assign` combinacional | Método puro / `record` |
| `always`/proceso secuencial | Bucle `execute()` del intérprete |
| Instanciación de submódulo | Composición (`new`) en el *top-level* |
| Parámetros (`#(parameter)`) | Constantes (`VmMemoryMap`, `Opcode`) |

**ISA (estructura de instrucción)**: opcode de 8 bits que determina la longitud
(1-8 bytes), datos *little-endian*. Los operandos inmediatos `LI` son
*big-endian* `[hi, lo]` (detalle del ensamblador). La tabla de opcodes y tamaños
vive en `vm/isa/Opcode`.

---

## 8. Estado del hardware

| Entidad | Decisión | Estado |
|---|---|---|
| `video` (`VmDisplay`) | Periférico de texto mapeado en memoria; celda de 2 bytes (carácter + atributo `[letra:4][fondo:4]`, estilo VERA/X16); el *interconnect* decodifica su ventana y su banco de registros. D31 | ✅ |
| `bios` | ROM de firmware **activa**: reset que limpia RAM, configura la pantalla vía MMIO/DMA y publica la tabla de IRQ, luego cede al `main`. D32 | ⏳ |
| `irq` (tabla) | La tabla de interrupciones la define el BIOS en memoria (configurable), no hardcodeada en el emulador; fuentes por E/S. D33 | ⏳ |

La pantalla (D31) ya incorpora sus puertos/señales en las secciones 2–6. El BIOS
activo (D32) y la tabla de IRQ con fuentes de E/S (D33) están **documentadas pero
aún no implementadas** (ver `docs/workdir/PLAN.md`, revisión 2026-10-17 (2)); cuando
se implementen, este documento añadirá su rango y líneas de evento.

---

## 9. Convenciones de nomenclatura

- **Entidades** en minúsculas (`cpu`, `dma`, `mmu`, `ram`) en tablas/diagramas;
  la clase Java correspondiente en `CamelCase` con la ruta del paquete.
- **Señales** en minúsculas (`address`, `data`, `we`, `re`, `x`, `priv`, `len`).
- **Dominios** en mayúsculas (`PROGRAM`, `SUPERVISOR`).
- **Direcciones** en hexadecimal con prefijo `0x`; palabras *little-endian* salvo
  los inmediatos de `LI`.

---

## Referencias

- [`README.md`](README.md) — vista funcional (componentes, ISA, ABI, tests)
- [`docs/prompts/iflow/design.md`](../../docs/prompts/iflow/design.md) — §4
  (componentes) y §14 (privilegios/anillos)
- [`docs/prompts/iflow/implement.md`](../../docs/prompts/iflow/implement.md) — §12
  (privilegios, excepciones) y §13 (firmware)
- [`docs/workdir/PLAN.md`](../../docs/workdir/PLAN.md) — decisiones D17–D33
