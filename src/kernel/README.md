# TextFormatter Suite — Kernel

## Purpose

The `kernel` module implements the **module resolution engine**: it discovers modules via `ServiceLoader`, validates their contracts against the host environment, resolves dependency graphs, and produces an activation order using Tarjan's SCC algorithm.

## Key Components

### ModuleGraph
- **Input**: Collection of `Module` descriptors + `Environment`
- **Output**: `ResolutionResult` with per-module status
- **Algorithm**: Fixed-point iteration + Tarjan SCC for cycle detection
- **Handles**: 
  - Contract version mismatch (major version, semver)
  - JVM version mismatch (min/max)
  - Missing requirements (unsatisfied capabilities)
  - Cycles (including self-cycles)

### ModuleLoader
- Discovers modules via `ServiceLoader.load(Module.class)`
- Validates module descriptors (manifest.yml mandatory)
- Creates `Module` instances wrapping descriptors
- Delegates to `ModuleGraph.resolve()`

### ResolutionResult
Per-module statuses:
- `RESOLVED` — Activated successfully
- `CONTRACT_MISMATCH` — Incompatible contract version
- `JVM_MISMATCH` — JVM version outside declared range
- `UNSATISFIED_REQUIREMENT` — Missing capability
- `CYCLE` — Dependency cycle detected

### ModuleDescriptor (from core-api)
- `name` — Unique module identifier
- `version` — SemVer
- `contractVersion` — API contract version
- `jvmMin` / `jvmMax` — JVM version range
- `provides` — `List<Capability>` (name + version)
- `requires` — `List<Requirement>` (name + semver range)

## Key Design Decisions

1. **Module = Descriptor, not Instance** — Avoids DI container anti-pattern
2. **ServiceLoader Discovery** — Standard Java mechanism, no custom registry
3. **Fixed-Point Resolution** — Iteratively activates modules whose requirements are met
4. **Tarjan SCC for Cycles** — Detects all cycles including self-cycles (A→A)
5. **Two-Handshake** — Contract version (semver) + JVM version

## Architecture Position

```
┌─────────────────────────────────────────────┐
│              Host Application               │
│  (Spigot-host, Fabric-host, standalone)    │
└────────────────────┬────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────┐
│                   Kernel                    │
│  ModuleLoader → ModuleGraph → Resolution    │
└────────────────────┬────────────────────────┘
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
   core-api     Module JARs   Environment
```

## Testing

Run: `./gradlew :src:kernel:test`

Key tests:
- `ModuleGraphTest` — Resolution logic, cycle detection, mismatch handling
- `ModuleLoaderTest` — ServiceLoader discovery, descriptor validation