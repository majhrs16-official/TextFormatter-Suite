# manager-apt — Repositories, Resolution & Installer Facade

> **Purpose**: Provides the remote (apt-style) half of the module manager: refreshes
> repository indexes, resolves dependency plans, downloads packages and delegates
> installation to `manager-core`. Exposes `ManagerFacade`, the single entry point used
> by the platform hosts.

---

## 1. Responsibilities

- **Repositories** — `RepositoryManager` refreshes every configured repository,
  caches its index and answers `findCandidates(name, versionConstraint, channel)` and
  `getAllAvailablePackages()`; `loadCachedIndexes()` works offline.
- **Dependency resolution** — `DependencyResolver` builds a `ResolutionPlan` for
  `resolveInstall`, `resolveUpgrade` and `resolveFixBroken`: version constraints,
  conflicts, cycles, incompatibilities and topological order, with steps typed
  `INSTALL`, `UPGRADE`, `REMOVE` or `DOWNGRADE`.
- **Remote installation** — `AptInstaller` downloads the resolved artifacts (with
  checksum verification) and delegates to `PackageInstaller`; it also implements
  `upgrade`, `fixBroken` and `remove` (including orphaned dependencies).
- **Unified API** — `ManagerFacade` wires the database, repositories, resolver and
  installer, and exposes `repoUpdate`, `repoInstall`, `repoUpgrade`, `repoFixBroken`,
  `repoRemove`, `packageInstall/List/Remove/Files/Verify`, `checkUpdates`,
  `discoverAll` and `getLoadedModules`.
- **Legacy adapter** — `ModuleLifecycleAdapter` implements `ModuleLifecycle` on top of
  the facade. Discovery, download and registration are supported; dynamic
  load/unload is not (a restart is required).

---

## 2. Non-Responsibilities

- **No ZIP extraction or file database** — `manager-core` owns both.
- **No module loading** — `kernel` loads modules.
- **No package format or parser definitions** — `manager-spi` defines them.
- **No platform wiring** — `spigot-host` constructs and calls `ManagerFacade`.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `PluginLogger` and base domain types |
| `kernel` | Compile | Module loading contracts |
| `manager-spi` | Compile | Manifests, repository config, versions, `ModuleLifecycle` |
| `manager-core` | Compile | `PackageInstaller`, `LocalPackageDatabase`, `TransactionLog` |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Builds `ManagerFacade` and exposes repository/package commands |
| `fabric-host` | Compile dependency |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `ManagerFacade` | Single entry point: repo + package operations, updates, discovery |
| `RepositoryManager` | Index refresh/cache and candidate selection (`UpdateResult`, `PackageCandidate`) |
| `DependencyResolver` | Install/upgrade/fix-broken plans (`ResolutionPlan`, `PlanStep`, `ResolutionIssue`) |
| `AptInstaller` | Download, checksum verification and delegation to `manager-core` |
| `ModuleLifecycleAdapter` | Legacy `ModuleLifecycle` bridge (no dynamic load/unload) |

---

## 6. Data Flow

```
repo.yml ──► RepositoryManager.updateAll() ──► cached index
                                   │
                                   ▼
                    DependencyResolver.resolve*()
                                   │  ResolutionPlan (INSTALL/UPGRADE/REMOVE/DOWNGRADE)
                                   ▼
                    AptInstaller ──► download + verify
                                   │
                                   ▼
                    PackageInstaller (manager-core)
                                   │
                                   ▼
                    LocalPackageDatabase + TransactionLog
                                   │
                                   ▼
                    restart ──► kernel loads the new modules
```

---

## 7. Entry Points

- `ManagerFacade.initialize()`
- `ManagerFacade.repoUpdate()` / `repoInstall(...)` / `repoUpgrade()` /
  `repoFixBroken()` / `repoRemove(...)`
- `ManagerFacade.packageInstall(...)` / `packageRemove(...)` / `packageVerify(...)`
- `ManagerFacade.checkUpdates()` / `discoverAll(ClassLoader)`
- `ModuleLifecycleAdapter` (legacy SPI)

---

## 8. Exploration Path

```
1. ManagerFacade.java            → Unified API
2. RepositoryManager.java        → Index refresh, cache and candidates
3. DependencyResolver.java       → Resolution plans
4. AptInstaller.java             → Download + install orchestration
5. ModuleLifecycleAdapter.java   → Legacy lifecycle bridge
```

---

## 9. Related Modules

- [manager-spi](../manager-spi/README.md) — Manifests, repository config and lifecycle contracts
- [manager-core](../manager-core/README.md) — Local installer and package database
- [kernel](../kernel/README.md) — Module loading
- [spigot-host](../spigot-host/README.md) — Builds and calls `ManagerFacade`
- [fabric-host](../fabric-host/README.md) — Consumer
