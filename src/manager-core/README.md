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
- **Transactions** — `TransactionLog` journals every operation and records its result
  (`log`, `markCompleted`, `markFailed`, `markRolledBack`,
  `recoverIncompleteTransactions`), so a crash leaves no half-installed package.
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
| `PackageInstaller` | Install / remove / verify / list files from a local ZIP (`InstallException`) |
| `LocalPackageDatabase` | `library/status` persistence, ownership, reverse deps, verification |
| `TransactionLog` | Journal and recovery of incomplete transactions |

---

## 6. Data Flow

```
ZIP package
   │
   ▼
PackageInstaller.install()
   │  validate metadata / inventory / dependencies
   │  extract under the install root
   ▼
LocalPackageDatabase   ── register package + files
   │
   ▼
TransactionLog         ── log → markCompleted (or markFailed / rollback)
```

Removal follows the inverse path: unregister from the database, delete owned files,
then optionally remove orphaned dependencies.

---

## 7. Entry Points

- `PackageInstaller.install(Path, boolean)`
- `PackageInstaller.remove(String, String, String, boolean)`
- `PackageInstaller.verify(...)` / `listFiles(...)`
- `LocalPackageDatabase.initialize()` / `load()` / `save()`
- `TransactionLog.recoverIncompleteTransactions()`

---

## 8. Exploration Path

```
1. PackageInstaller.java        → Install / remove / verify
2. LocalPackageDatabase.java    → library/status and ownership
3. TransactionLog.java          → Journal and crash recovery
```

---

## 9. Related Modules

- [manager-spi](../manager-spi/README.md) — Package metadata and parsers
- [manager-apt](../manager-apt/README.md) — Repositories, dependency resolution and facade
- [kernel](../kernel/README.md) — Module loading
- [core-api](../core-api/README.md) — Base domain types
