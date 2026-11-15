# iflow-32 — VM de 32 bits (núcleo v3)

> **Estado:** núcleo v3 aislado (253 tests); integración con el hub pendiente de V4.
>
> Este módulo implementa el contrato de ADR-037: ISA v3 de 32 bits, MMU paginada, bus MMIO,
> dispositivos, PIC/timer y ABI `HOST` v3. No comparte núcleo, decoder, MMU, formato ROM ni ABI con
> [`iflow-16`](../iflow-16/); el hub selecciona una sola línea.

## Contenido

- `rom`: parser de la ROM v3 (`FLOWROM`), cabecera/TOC, `PRG3`, hashes y capacidades.
- `isa/v3`: decoder, ALU, registros, flags, CPU, CSR, traps e interrupciones.
- `memory/v3`: memoria física dispersa, MMU, TLB, bus MMIO, PIC, timer, UART, display, NVRAM,
  SYSINFO y mailbox.
- `host/v3`: envelope TLV, identidad, capacidades y puente de servicios `HostBridge`.

## Límites actuales

- El espacio de direcciones es de 32 bits (máximo arquitectónico 4 GiB); la RAM física configurable
  de ADR-037 es 16 MiB–1 GiB.
- No acepta `.flow` v2; esa línea pertenece a `iflow-16`.
- Todavía no está cableado al hub ni tiene `ModuleActivator` propio. El motor v2
  (`VmPolicyEngine`/`VmConfigLoader`/`Iflow16Activator`) no forma parte de esta línea.
