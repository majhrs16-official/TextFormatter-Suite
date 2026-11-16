# iflow-32 — VM de 32 bits (núcleo v3)

> **Estado:** núcleo v3 aislado (266 tests); V3-1 (tiempo y watchdog) implementado; integración
> con el hub pendiente de V4.
>
> Este módulo implementa el contrato de ADR-037: ISA v3 de 32 bits, MMU paginada, bus MMIO,
> dispositivos, PIC/timer y ABI `HOST` v3. No comparte núcleo, decoder, MMU, formato ROM ni ABI con
> [`iflow-16`](../iflow-16/); el hub selecciona una sola línea.

## Contenido

- `rom`: parser de la ROM v3 (`FLOWROM`), cabecera/TOC, `PRG3`, hashes y capacidades.
- `isa/v3`: decoder, ALU, registros, flags, CPU, CSR, traps, interrupciones y `VirtualClock`.
- `memory/v3`: memoria física dispersa, MMU, TLB, bus MMIO, PIC, timer, UART, display, NVRAM,
  SYSINFO y mailbox.
- `execution/v3`: ventana de ejecución del host con quantum, watchdog y avance de tiempo en `HALT`.
  No es un scheduler de guest: la política de procesos vive en el kernel.
- `host/v3`: envelope TLV, identidad, capacidades y puente de servicios `HostBridge`.

## Tiempo y ejecución

- `VirtualClock` es un contador `u64` de nanosegundos virtuales. Avanza durante ejecución y espera,
  compara deadlines sin signo y solo se reinicia con la VM.
- `ExecutionEngine` aplica el quantum de host de 1.000.000 de instrucciones o 50 ms de reloj de
  pared, lo que ocurra primero, configurable y no expuesto al ABI. Agotarlo devuelve
  `QUANTUM_EXCEEDED` o `HOST_TIMEOUT`; no hay éxito parcial.
- El tiempo de pared consumido dentro de `HOST` no se cobra al watchdog. La acción queda acotada por
  su propio timeout.
- Cuando el vCPU está en `HALT`, el motor avanza el reloj virtual hasta el siguiente deadline en vez
  de hacer busy-wait; el timer dispara la IRQ 0 y despierta la CPU.
- El timer compara `CMP` con `RDTIME` en orden unsigned, de acuerdo con el contador de 64 bits.

## Límites actuales

- El espacio de direcciones es de 32 bits (máximo arquitectónico 4 GiB); la RAM física configurable
  de ADR-037 es 16 MiB–1 GiB.
- No acepta `.flow` v2; esa línea pertenece a `iflow-16`.
- V3 sigue abierta: faltan el journal por unidad de mensaje, el scheduler de guest, la persistencia
  entre mensajes y la concurrencia. V3-1 cubre solo reloj, quantum, watchdog y sleep/wake.
- Todavía no está cableado al hub ni tiene `ModuleActivator` propio. El motor v2
  (`VmPolicyEngine`/`VmConfigLoader`/`Iflow16Activator`) no forma parte de esta línea.
