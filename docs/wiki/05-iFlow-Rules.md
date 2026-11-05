# iFlow Policy Engines

## Hub and backends

iFlow is a family of routing backends managed by the `iflow` hub. The three
backends may be installed together; the hub activates exactly one and publishes
`IflowHub` and `Router`:

| Backend | Package | Policy source |
|---------|---------|---------------|
| Firewall | `iflow-firewall` | SpEL rules in `config/iflow-firewall/rules.yml` |
| VM | `iflow-vm` | Binary `.flow` program in `config/iflow-vm/policy.flow` |
| Wasm | `iflow-wasm` | WebAssembly module with a SHA-256 pin |

`SuiteRuntime` requires the hub. An engine no longer publishes a `Router` that
can bypass the hub.

### Hub configuration (`config/iflow/config.yml`)

```yaml
engine: iflow-firewall            # iflow-firewall | iflow-vm | iflow-wasm
fallback-on-unavailable: true     # Use the next installed backend if the selected one is missing
config-version: 1.0
```

Default selection is `iflow-firewall`. With fallback enabled, the order is
`iflow-firewall` → `iflow-vm` → `iflow-wasm`. `iflow.engine.kind` does not
exist; backend selection lives only in this file.

## `iflow-vm`

`iflow-vm` implements the `Router` SPI by executing a small deterministic virtual
machine:

- 16-bit address space, flat 64 KB.
- 16 pages of 4 KB with read/write/execute permissions and page ownership.
- Software MMU (`SoftMMU`) with copy-on-write support for snapshots.
- Binary program images (`.flow`), not YAML lists or inline expressions.
- A fuel budget (default 100,000 instructions) to bound execution.
- A `HOST` instruction/ABI for controlled access to host services.
- Memory-mapped devices: text display, NVRAM and interrupt controller.

### Main components

| Component | Responsibility |
|-----------|----------------|
| `VmPolicyEngine` | `Router` implementation; runs the program per message |
| `VmInterpreter` | Fetch/decode/execute loop, ISA v2, fuel and privilege checks |
| `SoftMMU` | Virtual-to-physical translation, page permissions and ownership |
| `PhysicalMemory` | 64 KB backing store split into 4 KB frames |
| `VmBus` | Single transaction bus for CPU/DMA; decodes MMIO before RAM |
| `VmDmaController` | Validated bulk transfers between host and VM |
| `VmDisplay` | Text framebuffer mapped through MMIO |
| `VmNvram` | Persistent BIOS state across messages |
| `VmPic` | 16-line interrupt controller with mask/raise/ack |
| `VmConfigLoader` | Loads `policy.flow`; pass-through fallback |
| `ProgramImage` | Immutable bytecode + constants + capabilities + hash |

### Memory model

- Pages 0–1: code (read/execute, kernel-owned).
- Page 2: constants (read, kernel-owned).
- Pages 3–4: data/heap (read/write).
- Pages 5–6: stack (grows down from `0x7000`).
- Page 7: input buffer.
- Page 8: output/scratch buffer.
- Pages 9–15: user RAM; page 15 also hosts the framebuffer when enabled.

Kernel pages are readable/executable by the program but writable only by the
supervisor. The program cannot write its own code or constants through normal
stores.

### Execution model

One live machine is serialized per program. General-purpose RAM persists between
messages; the runtime clears the I/O buffers. The BIOS runs on cold start,
configures the display and publishes the interrupt table, then jumps to the
program entry point.

Host services are invoked through `HOST`. The ABI carries a service id and
arguments in registers; errors can raise IRQ 1. The host reads diagnostics through
`VmPolicyEngine.display()`.

### ISA

The ISA includes data transfer, indirect memory access, arithmetic, logic,
shifts, comparisons, branches, calls, stack operations, `HOST`, `RAISE`, `ACK`,
`RET` and `HALT`. See
[`src/iflow-vm/README.md`](../../src/iflow-vm/README.md) and
[`src/iflow-vm/HDL.md`](../../src/iflow-vm/HDL.md).

### Build and test

```bash
./gradlew :src:iflow-vm:test --no-daemon
```

The tests cover interpreter, MMU, host services, policy engine and firmware
integration.

## `iflow-firewall`

`iflow-firewall` is the legacy SpEL backend. It loads
`config/iflow-firewall/rules.yml`. The file root must be a map with
`config-version: 1.0`:

- Executable rules live under `rules:`.
- The web editor graph uses `nodes:` and `edges:`.
- A root-level list is rejected because it cannot declare a version.

```yaml
rules:
  - id: drop-private
    target: DROP
    channel: private
    priority: 5
    reason: staff only
config-version: 1.0
```

The firewall validates the editor graph but executes only the `rules:` list. The
module does not require `iflow.engine.kind`; it is selected by the hub.

## `iflow-wasm`

`iflow-wasm` runs a WebAssembly module through Chicory. It does not share the VM
ISA or ABI. The module requires a SHA-256 pin and reloads automatically when the
pinned module changes.

## Notes

The old declarative `rules.yml` syntax with `cond`, `action`, `target` and SpEL
conditions belongs to `iflow-firewall`. It is not part of `iflow-vm` or
`iflow-wasm`. Do not use old wiki revisions as a reference for the VM; use the
module README and tests.
