# manager-spi — Module Manager SPI

> **Purpose**: Defines the package metadata and manifest contracts shared by the
> local (dpkg-style) and remote (apt-style) manager layers. Live module
> lifecycle belongs to `suite-runtime`; this module has no legacy lifecycle
> bridge.

---

## 1. Responsibilities

- **Package metadata** — `PackageEntry` (one package in a manifest or status
  database), `FileEntry` (one file in a package inventory), `PackageType`
  (`PACKAGE`, `DEPENDENCY`, `METAPACKAGE`) and `PlatformCompatibility`
  (shared platform-family matching).
- **Parsers and serializers** — `ManifestParser` (remote index `packages.list`,
  `METADATA/package.list`, `library/status`, `DependencySpec`), `RepoConfigParser`
  (`repo.yml` → `RepositoryConfig`), `Version` (SemVer 2.0.0 parsing, comparison
  and `satisfies(rangeSpec)`).

---

## 2. Non-Responsibilities

- **No implementation** — interfaces and immutable data types only.
- **No network code** — repository access lives in `manager-apt`.
- **No installation** — ZIP extraction and the local database live in
  `manager-core`.
- **No module lifecycle** — `ModuleHost` in `suite-runtime` loads, activates,
  upgrades and unloads modules.

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
| `manager-apt` | Repository config, manifests, versions and package metadata |
| `spigot-host` | Compile dependency; wires the manager through `ManagerFacade` |
| `fabric-host` | Compile dependency |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `PackageEntry` | One package record in a manifest or `library/status` (includes optional `Exclude`/`Provide`) |
| `FileEntry` | One file record in a package inventory |
| `PackageType` | `PACKAGE`, `DEPENDENCY`, `METAPACKAGE` |
| `ManifestParser` | Parses/serializes package lists and dependency specs |
| `RepoConfigParser` | Parses/serializes `repo.yml` |
| `RepositoryConfig` | Repository name, URL, type, enabled flag, priority and options |
| `Version` | SemVer 2.0.0: parse, compare, range matching |
| `PlatformCompatibility` | Matches `Platform` declarations (`common`/`any`, Bukkit family, exact) |

---

## 6. Data Flow

Contracts only — there is no runtime flow in this module. `manager-core`
implements the local package format and `manager-apt` implements repository
resolution and installation orchestration.

---

## 7. Entry Points

None — SPI and data types only.

---

## 8. Exploration Path

```
1. spi/ManifestParser.java         → Package list / status format
2. spi/RepoConfigParser.java       → repo.yml
3. spi/Version.java                → SemVer and ranges
4. spi/PlatformCompatibility.java  → Platform-family matching
5. spi/PackageEntry.java           → Installed/available package record
```

---

## 9. Related Modules

- [core-api](../core-api/README.md) — Base `Module`, `SemVer` and domain types
- [manager-core](../manager-core/README.md) — Local installer and package database
- [manager-apt](../manager-apt/README.md) — Repositories, dependency resolution and facade
- [ModuleHost](../suite-runtime/src/main/java/me/majhrs16/suite/runtime/ModuleHost.java) — Live module lifecycle and hot upgrades
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Consumers
