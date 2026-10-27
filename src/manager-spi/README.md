# manager-spi — Module Manager SPI

> **Purpose**: Defines the contracts shared by the module manager: module identity,
> the lifecycle SPI, and the package metadata/manifest parsers used by both the
> local (dpkg-style) and remote (apt-style) layers.

---

## 1. Responsibilities

- **Module identity and compatibility** — `ModuleCoordinate` (group:artifact:version:classifier),
  `ModuleDescriptor` (metadata), `Environment` (platform, Java/Minecraft versions,
  installed modules, capabilities). They bridge the legacy `ModuleLifecycle` API with the
  package system.
- **Lifecycle SPI** — `ModuleLifecycle`: `resolve`, `download`, `relocate`, `load`,
  `register`, `unregister`, `unload`, `checkUpdates`, `updateSuite`, `verifyChecksum`,
  `getLoadedModules`, `discoverAvailableModules`, `discoverAll`, plus the
  `ResolvedModule` / `ResolutionResult` value types.
- **Package metadata** — `PackageEntry` (one package in a manifest or status database),
  `FileEntry` (one file in a package inventory), `PackageType`
  (`PACKAGE`, `DEPENDENCY`, `METAPACKAGE`).
- **Parsers and serializers** — `ManifestParser` (remote index `packages.list`,
  `METADATA/package.list`, `library/status`, `DependencySpec`), `RepoConfigParser`
  (`repo.yml` → `RepositoryConfig`), `Version` (SemVer 2.0.0 parsing, comparison and
  `satisfies(rangeSpec)`).

---

## 2. Non-Responsibilities

- **No implementation** — interfaces and immutable data types only.
- **No network code** — repository access lives in `manager-apt`.
- **No installation** — ZIP extraction and the local database live in `manager-core`.
- **No module loading** — `kernel` loads modules.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `SemVer` and the base domain types |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `manager-core` | Package entries, manifests, versions and the local DB format |
| `manager-apt` | Repository config, manifests, versions and the lifecycle contracts |
| `spigot-host` | Compile dependency; wires the manager through `ManagerFacade` |
| `fabric-host` | Compile dependency |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `ModuleCoordinate` | Coordinate: group, artifact, version, classifier |
| `ModuleDescriptor` | Module metadata (name, author, Java/platform requirements, ...) |
| `Environment` | Manager runtime context (platform, Java/Minecraft versions, capabilities) |
| `ModuleLifecycle` | Lifecycle SPI and its resolution value types |
| `PackageEntry` | One package record in a manifest or `library/status` (includes optional `Exclude`/`Provide`) |
| `FileEntry` | One file record in a package inventory |
| `PackageType` | `PACKAGE`, `DEPENDENCY`, `METAPACKAGE` |
| `ManifestParser` | Parses/serializes package lists and dependency specs |
| `RepoConfigParser` | Parses/serializes `repo.yml` |
| `RepositoryConfig` | Repository name, URL, type, enabled flag, priority and options |
| `Version` | SemVer 2.0.0: parse, compare, range matching |

---

## 6. Data Flow

Contracts only — there is no runtime flow in this module. `manager-core` implements the
local package format and `manager-apt` implements the lifecycle adapter and repository
resolution.

---

## 7. Entry Points

None — SPI and data types only.

---

## 8. Exploration Path

```
1. ModuleLifecycle.java            → Lifecycle SPI
2. ModuleCoordinate.java           → Coordinate model
3. ModuleDescriptor.java           → Module metadata
4. spi/ManifestParser.java         → Package list / status format
5. spi/RepoConfigParser.java       → repo.yml
6. spi/Version.java                → SemVer and ranges
```

---

## 9. Related Modules

- [core-api](../core-api/README.md) — Base `Module`, `SemVer` and domain types
- [manager-core](../manager-core/README.md) — Local installer and package database
- [manager-apt](../manager-apt/README.md) — Repositories, dependency resolution and facade
- [kernel](../kernel/README.md) — Local module loading
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Consumers
