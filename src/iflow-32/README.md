# iflow-32 — VM de 32 bits (núcleo v3)

> **Estado:** núcleo v3 aislado (328 tests); V3-1 (tiempo, quantum y watchdog configurable)
> implementado; V3-2 en curso (`CORE_COUNT`, `COREID`, `SYSINFO`, atómicas, PIC por core, IPI,
> `TARGET`, vista `CoreMemory` por core, `MachineRuntime` por core, journal concurrente por
> mensaje y V3-2a: backend `.flowasm` v3 `iflow-as -f iflow-32` con ROM `FLOWROM` v3/`PRG3`); faltan
> el kernel guest con scheduler/shootdown, el loader del host que copia los segmentos a su
> `loadAddress`, la persistencia entre mensajes y la integración con el hub.
>
> Este módulo implementa el contrato de ADR-037: ISA v3 de 32 bits, MMU paginada, bus MMIO,
> dispositivos, PIC/timer y ABI `HOST` v3. No comparte núcleo, decoder, MMU, formato ROM ni ABI con
> [`iflow-16`](../iflow-16/); el hub selecciona una sola línea.

## Contenido

- `rom`: parser de la ROM v3 (`FLOWROM`), cabecera/TOC, `PRG3`, hashes y capacidades.
- `isa/v3`: decoder, ALU, registros, flags, CPU, CSR, traps, interrupciones y `VirtualClock`.
- `memory/v3`: `MachineMemory` compartida (RAM/ROM, MMIO, PIC, timer, UART, display, NVRAM, SYSINFO
  y mailbox con `TARGET` por core) y `CoreMemory` por core con CSR, MMU, TLB y reserva atómica
  propios.
- `execution/v3`: slice de ejecución del host con quantum de preempción, watchdog de paso
  configurable y avance de tiempo en `HALT`; `MachineRuntime` crea un `Cpu`, una vista `CoreMemory`
  y un `ExecutionEngine` por core, ejecuta solo el core que elige el llamador y marca core y VM como
  `FAULTED` si un paso supera el watchdog. No es un scheduler de guest: la política de procesos vive
  en el kernel y el quantum no es fuel ni un límite total de ejecución.
- `host/v3`: envelope TLV, identidad, capacidades y puente de servicios `HostBridge`; `HostAbi`
  abre un `HostContext` por `MIDENT` y `MessageJournal` conserva el write set provisional y el
  latch único de `ROUTE_DECISION` hasta el commit o aborto de la unidad.

## Tiempo y ejecución

- `VirtualClock` es un contador `u64` de nanosegundos virtuales. Avanza durante ejecución y espera,
  compara deadlines sin signo y solo se reinicia con la VM.
- `ExecutionEngine` aplica el slice de host de 1.000.000 de instrucciones o 50 ms de reloj de
  pared, lo que ocurra primero, configurable y no expuesto al ABI. Agotarlo devuelve `PREEMPTED`:
  conserva el estado arquitectónico, la RAM y el journal abierto, y el host puede reanudar el mismo
  core. No es un fallo, no termina el mensaje y no limita la vida de la VM.
- `WATCHDOG` solo se devuelve si el watchdog está activo y un único paso de guest tarda más de
  50 ms en retornar (excluyendo `HOST`). Marca el core como `FAULTED` y no confirma el journal
  abierto; no es una política de entrega ni un límite para programas largos. La comprobación es por
  paso: no interrumpe asíncronamente un `cpu.step()` que nunca retorne.
- El tiempo de pared consumido dentro de `HOST` no se cobra al quantum ni al watchdog. La acción
  queda acotada por su propio timeout; `TIMEOUT` solo describe esa acción, no la VM.
- No hay fuel, gas ni presupuesto total de ejecución en el ABI v3. `LIMIT` solo significa capacidad
  insuficiente del buffer de resultado. La admisión, la cola y el backoff son política del guest.
- Cuando el vCPU está en `HALT`, el motor avanza el reloj virtual hasta el siguiente deadline en vez
  de hacer busy-wait; el timer dispara la IRQ 0 y despierta la CPU.
- El timer compara `CMP` con `RDTIME` en orden unsigned, de acuerdo con el contador de 64 bits.

## Journal concurrente

- Cada mensaje tiene su propio `HostContext` y `MessageJournal`, indexado por `MIDENT`. Dos
  unidades en cores distintos no comparten escrituras ni el latch de `ROUTE_DECISION`.
- `PROPERTY_WRITE` valida la propiedad en el `HostBridge` y la guarda en el overlay; las lecturas
  posteriores de la misma unidad la ven (*read-your-writes*), pero otra unidad no la ve antes del
  commit.
- `DELIVER` aplica el write set comprometido como lote y después registra la decisión. `CANCEL_EVENT`
  y `ERROR` registran la decisión sin aplicar escrituras; sin decisión, el journal se descarta.
- Una segunda decisión en la misma unidad devuelve `CONFLICT`. Las operaciones de una unidad se
  serializan por `MIDENT`; el commit usa además el lock de mutaciones globales del host, sin tomar
  un `machineLock` para ejecutar guest.
- Si el host rechaza la validación o el lote, el journal no publica escrituras y la unidad se cierra
  de forma explícita.

## Configuración

`WatchdogConfig.load(Path, PluginLogger)` y `CoreCountConfig.load(Path)` leen
`config/iflow-32/config.yml`. El archivo debe declarar `config-version: 1.0`; el watchdog está
activo por defecto y solo se desactiva de forma explícita. `core-count` es 1 por defecto:

```yaml
config-version: 1.0
core-count: 1
watchdog:
  enabled: false
```

Un archivo ausente mantiene el watchdog activo y un único core. Un valor que no sea booleano, una
raíz que no sea mapa o una versión ausente/incorrecta fallan de forma explícita. Desactivar el
watchdog registra un `warn`, no cambia el quantum y deja sin recuperación un `cpu.step()` que no
retorne. `core-count` acepta el rango arquitectónico 1..64, pero V3-2 todavía no implementa el
multinúcleo: un valor mayor que 1 falla con `UnsupportedOperationException` en vez de degradarse a
un solo core. El activador de V4 conectará ambos cargadores a la configuración del módulo.

## Límites actuales

- El espacio de direcciones es de 32 bits (máximo arquitectónico 4 GiB); la RAM física configurable
  de ADR-037 es 16 MiB–1 GiB.
- No acepta el `.flow` de `iflow-16`; esa línea pertenece a ese módulo.
- V3 sigue abierta: faltan el kernel guest con scheduler y TLB shootdown, la persistencia entre
  mensajes y el cierre del multinúcleo. V3-1 cubre reloj, quantum de preempción, watchdog de paso
  configurable y sleep/wake. V3-2 ya entrega `CoreCountConfig`, `COREID` (`0x10A`, RO),
  `SYSINFO.CORE_COUNT`, `FEATURES` bit 6 y las atómicas `LR.W`/`SC.W`/`AMOADD.W`/`AMOSWAP.W`/
  `AMOAND.W`/`AMOOR.W` con reserva por core, invalidación por trap y rechazo de MMIO. También
  entrega el PIC con banco por core, `SOFT_IRQ` con línea/destino (IPI y broadcast) y `TARGET` en
  `TIMER` y `HOST_MAILBOX`; un destino fuera de `CORE_COUNT` es `BUS_ERROR`. `MachineMemory`
  comparte RAM, MMIO, PIC y reservas atómicas, `CoreMemory` separa CSR, MMU, TLB y reserva por
  core, y `MachineRuntime` crea CPU, quantum y watchdog por core. Las palabras alineadas de 32 bits
  se leen y escriben sin tearing sobre frames concurrentes; los dispositivos MMIO mutables
  serializan sus transacciones entre cores. Un cambio de page table no vacía
  la TLB ajena: el core destino debe ejecutar `SFENCE.VMA` tras la IPI; no hay shootdown automático
  del host. El journal concurrente por unidad de mensaje está implementado con overlay aislado,
  read-your-writes y commit solo en `DELIVER`. V3-2a entrega el ensamblador `.flowasm` v3
  (`tools/iflow-compiler/src/v3`, formato `iflow-32` de `iflow-as`): secciones tipadas, directivas, encoder
  completo, ROM `FLOWROM` v3 y `PRG3`; `RomParser` acepta la ROM emitida y `InstructionDecoder`
  la decodifica. Faltan el kernel guest que complete scheduler y shootdown, el loader del host
  que copie los segmentos a su `loadAddress` y la persistencia entre mensajes. Hasta completar
  V3-2, `CORE_COUNT > 1` falla explícito.
- Todavía no está cableado al hub ni tiene `ModuleActivator` propio. El motor v2
  (`VmPolicyEngine`/`VmConfigLoader`/`Iflow16Activator`) no forma parte de esta línea.
