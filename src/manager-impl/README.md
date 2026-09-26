# TextFormatter Suite — Manager

## Purpose

The `manager-api` + `manager-impl` modules provide the **runtime Module Manager**:
- Discovers available modules (GitHub Releases, local files, HTTP)
- Resolves versions (semver ranges + environment compatibility)
- Downloads modules with SHA256 verification
- Manages isolated ClassLoaders (parent-last)
- Handles install/update/remove/reload via `/suite module` commands
- SPI-only registration (no instantiation)

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Module Manager                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ Repository  │  │   Version   │  │   Dependency        │ │
│  │ Abstraction │──▶│  Resolver   │──▶│   Resolver          │ │
│  │ (GitHub,    │  │ (semver +   │  │ (module.yml from     │ │
│  │  local,     │  │  env compat)│  │  JAR manifest)      │ │
│  │  HTTP)      │  │             │  │                     │ │
│  └─────────────┘  └─────────────┘  └──────────┬──────────┘ │
│                                                │            │
│  ┌─────────────┐  ┌─────────────┐  ┌───────────▼────────┐  │
│  │  Download   │  │   SHA256    │  │   ClassLoader      │  │
│  │  (verified) │──▶│ Verification│──▶│  (parent-last,    │  │
│  └─────────────┘  └─────────────┘  │   isolated)        │  │
│                                    └──────────┬──────────┘  │
│                                             │              │
│                                    ┌────────▼────────┐     │
│                                    │  ModuleRegistry │     │
│                                    │  register()     │     │
│                                    │  discoverAll()  │     │
│                                    └─────────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

## Key Components

### manager-api (SPI)
- `ModuleCoordinate` — group:artifact:version
- `ModuleDescriptor` — Extended with repo metadata
- `Environment` — Host environment (JVM, MC version, contract)
- `ModuleLifecycle` — SPI for install/update/remove/reload
- `ModuleManager` — Main interface (install, update, remove, list, info)

### manager-impl (Implementation)
- `DefaultModuleManager` — Core logic
- `Repository` implementations:
  - `GitHubRepository` — GitHub Releases API
  - `LocalRepository` — `file://` paths
  - `HttpRepository` — Generic HTTP
- `VersionResolver` — Semver range matching + env compatibility
- `DependencyResolver` — Parses `module.yml` from JAR
- `SHA256Verifier` — Mandatory verification (separate `.sha256` asset)
- `IsolatedClassLoader` — URLClassLoader, parent-last
- `ModuleRegistry` — SPI registration + `discoverAll()` for kernel

## Commands

```
/suite module install <id> [version]     # Install module
/suite module update <id> [version]      # Update module
/suite module remove <id>                # Remove module
/suite module list                       # List installed + available
/suite module info <id>                  # Show module details
/suite update                            # Full suite update
```

## Configuration

`config.yml` repositories section:
```yaml
repositories:
  - name: "github"
    type: "github"
    url: "https://api.github.com/repos/majhrs16-official/TextFormatter-Suite"
    enabled: true
  - name: "local"
    type: "local"
    url: "file:///path/to/modules"
    enabled: true
```

## Security

- SHA256 verification **mandatory** (no skip)
- Manifest validation required (`module.yml` in JAR)
- Isolated ClassLoaders prevent dependency conflicts
- Allowlist enforcement (future)

## Testing

Run: `./gradlew :src:manager-impl:test :src:manager-api:test`