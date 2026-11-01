# manager-core — Package Installer & Local Database

> **Purpose**: Provides the local (dpkg-style) half of the module manager: installs
> and removes ZIP packages, keeps the package database, and journals transactions so
> interrupted operations can be recovered.

---

## 1. Responsibilities

- **Local installation** — `PackageInstaller` installs a ZIP package (with optional
  forced overwrite), removes it (optionally with orphaned dependencies), verifies its
  checksums and lists the files it owns.
- **Package database** — `LocalPackageDatabase` owns `library/status`: registers and
  unregisters packages, tracks file ownership and reverse dependencies, answers
  `isInstalled` / `getPackage`, and runs `verifyPackage`.
- **Durable transactions** — `PackageJournal` is an append-only, fsynced journal
  per operation. The atomic rename of `library/status` is the commit point:
  recovery rolls back before it and only cleans up after it. A crash cannot
  leave a half-installed package.
- **Versioned layout** — module JARs live under
  `library/packages/<package>/<version>/` and shared dependencies under
  `library/dependencies/<dependency>/<version>/`; an upgrade leaves the old
  version on disk and never reuses its path for the new version. Removing the
  package removes its owned files.
- **Validation** — package metadata, inventory and declared dependencies are checked
  before anything is written to disk; extraction is confined to the install root.

---

## 2. Non-Responsibilities

- **No network access** — repositories and downloads live in `manager-apt`.
- **No dependency resolution from repositories** — `DependencyResolver` in
  `manager-apt` builds the install plan.
- **No module loading** — `kernel` loads modules; a restart is required to apply
  changes.
- **No repository configuration** — `repo.yml` parsing is part of `manager-spi`.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `PluginLogger` and base domain types |
| `manager-spi` | Compile | `PackageEntry`, `FileEntry`, `PackageType`, `ManifestParser`, `Version` |
| `kernel` | Compile | Module loading contracts |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `manager-apt` | Delegates ZIP installation/removal and database writes |
| `spigot-host` | Compile dependency (wired through `ManagerFacade`) |
| `fabric-host` | Compile dependency |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `PackageInstaller` | Batch install / remove / verify / list files from local ZIPs (`InstallException`) |
| `LocalPackageDatabase` | `library/status` persistence, ownership, reverse deps, verification |
| `PackageJournal` | Append-only durable journal and crash recovery |
| `PackageLayout` | Versioned path validation for packages and dependencies |
| `IoSupport` | `fsync`, atomic writes, SHA-256 and safe deletion |

---

## 6. Data Flow

```
ZIP batch
   │
   ▼
PackageInstaller.installBatch()
   │  validate every ZIP, manifest and dependency
   │  extract under library/tmp/<operationId>
   │  write journal records + backup replaced files
   ▼
library/status atomic rename  ◄── commit point
   │
   ▼
PackageJournal.committed()
   │  cleanup backup/tmp
   ▼
installed packages (activation is owned by suite-runtime)
```

Removal follows the inverse path: deactivate through the runtime barrier,
compute the removal closure (including auto-removed dependencies when requested),
journal file removals and trash moves, and commit the new status. Before the
commit, recovery restores the previous status and files; after it, recovery never
reverts the installation.

---

## 7. Entry Points

- `PackageInstaller.installBatch(List<Path>, boolean[, Collection<PackageEntry>[, Runnable]])`
  rejects reinstalling a version that is already installed, so an active JAR is
  never overwritten in place.
- `PackageInstaller.remove(String, String, String, boolean[, Runnable])`
- `PackageInstaller.verify(...)` / `listFiles(...)` / `recoverIncompleteOperations()`
- `LocalPackageDatabase.initialize()` / `load()` / `save()`
- `PackageJournal.recoverAll(...)`

---

## 8. Exploration Path

```
1. PackageInstaller.java        → Batch install / remove / verify
2. LocalPackageDatabase.java    → library/status, images and ownership
3. PackageJournal.java          → Durable journal and crash recovery
4. PackageLayout.java           → Versioned package/dependency paths
5. IoSupport.java               → fsync, atomic writes and hashes
```

---

## 9. Related Modules

- [manager-spi](../manager-spi/README.md) — Package metadata and parsers
- [manager-apt](../manager-apt/README.md) — Repositories, dependency resolution and facade
- [kernel](../kernel/README.md) — Module loading
- [core-api](../core-api/README.md) — Base domain types
