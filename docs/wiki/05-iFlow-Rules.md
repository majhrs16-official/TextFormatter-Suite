# iFlow VM (current)

> **This page describes the current iFlow virtual machine.**
>
> **2026-10-23:** this VM is the base of **`iflow-vm`**, which evolves to 32 bits. The parallel
> line **`iflow-wasm`** (WebAssembly + Chicory) lives in `src/iflow-wasm` and does not inherit this
> ISA or ABI.
>
> The old `DefaultRouter` / `Rule` / SpEL-based rule engine was removed. Its
> documentation is preserved only in git history; it does **not** describe the
> code in `src/iflow`. The current implementation is a 16-bit interpreted VM
> with a binary `.flow` image.

## What it is

`iflow` is the routing and policy module of TextFormatter Suite. It implements
the `Router` SPI and decides what happens to a message (deliver, drop, reject or
redirect) by executing a program on a small virtual machine.

The VM is deliberately small and deterministic:

- 16-bit address space, flat 64 KB.
- 16 pages of 4 KB with read/write/execute permissions and page ownership.
- Software MMU (`SoftMMU`) with copy-on-write support for isolated snapshots.
- Binary program images (`.flow`), not YAML rule lists or SpEL expressions.
- A fuel budget (default 100,000 instructions) to bound execution.
- A `HOST` instruction/ABI for controlled access to host services.
- Memory-mapped devices: text display, NVRAM and interrupt controller.

## Main components

| Component | Responsibility |
|---|---|
| `VmPolicyEngine` | `Router` implementation; loads a program and executes it per message |
| `VmInterpreter` | Fetch/decode/execute loop, ISA v2, fuel accounting and privilege checks |
| `SoftMMU` | Virtual-to-physical translation, page permissions and ownership |
| `PhysicalMemory` | 64 KB backing store split into 4 KB frames |
| `VmBus` | Single transaction bus for CPU/DMA; decodes MMIO before RAM |
| `VmDmaController` | Validated bulk transfers between host and VM |
| `VmDisplay` | Text framebuffer mapped through MMIO; returns to plain RAM when disabled |
| `VmNvram` | Persistent BIOS state across messages |
| `VmPic` | 16-line interrupt controller with mask/raise/ack |
| `VmConfigLoader` | Loads binary `.flow` images; pass-through fallback |
| `ProgramImage` | Immutable bytecode + constants + capabilities + hash |

## Memory model

- Pages 0–1: code (read/execute, kernel-owned).
- Page 2: constants (read, kernel-owned).
- Pages 3–4: data/heap (read/write).
- Pages 5–6: stack (grows down from `0x7000`).
- Page 7: input buffer.
- Page 8: output/scratch buffer.
- Pages 9–15: user RAM; page 15 also hosts the text framebuffer when the
  display is enabled. Disabling the display returns the page to normal RAM.

Kernel pages are readable/executable by the program but writable only by the
supervisor. The program cannot write its own code or constants through normal
stores.

## Execution model

Each program has one live machine. Executions are serialized, so data left in
general-purpose RAM persists between messages; the runtime only clears the I/O
buffers. The BIOS runs on cold start, configures the display and publishes the
interrupt table, then jumps to the program entry point.

Host services are invoked through `HOST`. The ABI carries a service id and
arguments in registers; errors can raise IRQ 1. The host can read the display
through `VmPolicyEngine.display()` for diagnostics and tests.

## ISA and ABI

The ISA includes data transfer, indirect memory access, arithmetic, logic,
shifts, comparisons, branches, calls, stack operations, `HOST`, `RAISE`, `ACK`,
`RET` and `HALT`. See the full tables and examples in
[`src/iflow/README.md`](../../src/iflow/README.md) and the hardware-oriented
mapping in [`src/iflow/HDL.md`](../../src/iflow/HDL.md).

## Build and test

```bash
./gradlew :src:iflow:test --offline --no-daemon
```

The module currently has 397 tests, including interpreter, MMU, host services,
policy engine and firmware integration.

## Historical note

If you are looking for the old declarative `rules.yml` syntax with `cond`,
`action`, `target` and SpEL conditions, that engine no longer exists in the
current code. Do not use old wiki revisions or external snippets as a reference
for `src/iflow`; use the module README and the tests instead.
