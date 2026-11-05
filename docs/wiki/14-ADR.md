# Architecture Decision Records

This page does not duplicate the ADRs. The canonical, versioned record is
[`docs/workdir/ADR.md`](../workdir/ADR.md). If this wiki and the ADR disagree,
the ADR wins.

## Current decision set

The decisions below are summarised only to help navigation. Read the linked
document before changing architecture, contracts or configuration.

| ADR | Subject | Current effect |
|-----|---------|----------------|
| ADR-021 | Registrable hierarchical config and commands | Modules register relative command paths through `ModuleContext`; `commands.yml` declares only the root. |
| ADR-022 | Relocatable command root | The root can change with `commands.yml`; the platform reports `RESTART_REQUIRED` when it cannot unregister the old root. |
| ADR-023 | C compiler for the iFlow VM | Compiler work depends on the VM and toolchain gates; it is not part of the 1.0.0 base. |
| ADR-024 | Neutral rich-render port | Adventure remains a host API boundary; modules do not ship a second Adventure. |
| ADR-025 | Thin adapters over `SuiteRuntime` | Spigot and Fabric adapters delegate lifecycle and commands to the runtime. |
| ADR-026 | Hot upgrade, leaks and dependency limits | Deactivation must be clean; leaked resources make the module `RESTART_REQUIRED`. |
| ADR-027 | Staged install and failure semantics | Acquisition, journaled file commit and runtime activation are separate; activation failure does not roll back installed files. |
| ADR-028 | `<regex>` template tag | TextFormatter supports the tag under the ADR-028 constraints. |
| ADR-029 | Reproducible distribution and third-party boundary | Immutable ZIPs, `.sha256`, `packages.list`, `SHA256SUMS`; shared dependencies are `DEPENDENCY` packages. |
| ADR-030 | Presets by command | `presets list` and `presets apply` exist; applying a preset reloads active modules in dependency order. |
| ADR-031 | iFlow policy contract v3 | Defines the VM/Wasm policy contract and fail-closed routing. |
| ADR-032 | `iflow` hub | The `iflow-firewall`, `iflow-vm` and `iflow-wasm` backends coexist; the hub selects exactly one and publishes `IflowHub`. |
| ADR-033 | Wasm router, artifact SHA-256 and hot reload | Wasm requires a pinned module and reloads when the pin changes. |
| ADR-034 | Pre-1.0 regime | `1.0.0` is the integrated base, `1.0` is the YAML schema; no legacy readers, aliases or runtime migrations. |
| ADR-035 | `CoreTranslator` as an independent legacy module | Bukkit-only PlaceholderAPI expansion; deprecated and maintained, not a compatibility shim. |

## Precedence

1. Accepted ADRs in [`docs/workdir/ADR.md`](../workdir/ADR.md).
2. Current specifications in [`docs/prompts/especificaciones/`](../prompts/especificaciones/).
3. Real code and tests as evidence, never as authority to override an ADR.
4. `PLAN.md` for operational status.
5. This wiki for user-facing explanations.

Historical ADR text remains in the canonical file with its status and
supersession notes. This wiki does not keep a second copy.
