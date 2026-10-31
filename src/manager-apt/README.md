# manager-apt — Repositories, Resolution & Installer Facade

> **Purpose**: Provides the remote (apt-style) half of the module manager:
> refreshes repository indexes, resolves dependency plans, downloads packages
> and delegates installation to `manager-core`. Exposes `ManagerFacade`, the
> single entry point used by the platform hosts and `PackageRuntime`.

---

## 1. Responsibilities

- **Repositories** — `RepositoryManager` refreshes every configured repository,
  caches its index and answers `findCandidates(name, versionConstraint, channel)`
  and `getAllAvailablePackages()`; `loadCachedIndexes()` works offline.
- **Dependency resolution** — `DependencyResolver` builds a `ResolutionPlan` for
  `resolveInstall`, `resolveUpgrade` and `resolveFixBroken`: version constraints,
  conflicts, cycles, incompatibilities and topological order, with steps typed
  `INSTALL`, `UPGRADE`, `REMOVE` or `DOWNGRADE`.
- **Remote installation** — `AptInstaller` downloads the resolved artifacts (with
  checksum verification) and delegates to `PackageInstaller`; it also implements
  `upgrade`, `fixBroken` and `remove` (including orphaned dependencies).
- **Two-phase upgrades** — `AptInstaller.upgrade(PackageUpgradeListener)` installs
  the new files first and invokes the runtime before removing the previous
  version. A clean rejection rolls back the files installed by that step and
  keeps the old installation. If the runtime cannot compensate a partially
  applied batch, it returns `Decision.keepBoth(...)`; the manager then keeps
  both versions on disk and reports that a restart is required.
- **Unified API** — `ManagerFacade` wires the database, repositories, resolver and
  installer, and exposes `repoUpdate`, `repoInstall`, `repoUpgrade(listener)`,
  `repoFixBroken`, `repoRemove`, `packageInstall/List/Remove/Files/Verify` and
  `checkUpdates`.

---

## 2. Non-Responsibilities

- **No ZIP extraction or file database** — `manager-core` owns both.
- **No module lifecycle** — `suite-runtime` loads, activates, upgrades and
  unloads modules. `PackageUpgradeListener` is the only crossing point.
- **No package format or parser definitions** — `manager-spi` defines them.
- **No platform wiring** — `spigot-host`/`fabric-host` construct `SuiteRuntime`,
  which constructs `ManagerFacade`.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `PluginLogger` and base domain types |
| `manager-spi` | Compile | Manifests, repository config, versions, package metadata |
| `manager-core` | Compile | `PackageInstaller`, `LocalPackageDatabase`, `TransactionLog` |

`manager-apt` no longer depends on `kernel`: discovery by classpath was removed
in F7.

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `suite-runtime` | `PackageRuntime` installs, removes and hot-upgrades packages |
| `spigot-host` / `fabric-host` | Indirectly through `SuiteRuntime` |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `ManagerFacade` | Single entry point: repo + package operations and updates |
| `RepositoryManager` | Index refresh/cache and candidate selection (`UpdateResult`, `PackageCandidate`) |
| `DependencyResolver` | Install/upgrade/fix-broken plans (`ResolutionPlan`, `PlanStep`, `ResolutionIssue`) |
| `AptInstaller` | Download, checksum verification, install and two-phase upgrade |
| `PackageUpgradeListener` | Runtime hook invoked before old package files are removed |

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
             PackageUpgradeListener ──► ModuleHost.upgrade(...)
                                   │
              allow ───────────────┼─────────────── keepBoth
                │                  │                    │
        remove old files      rollback new files   keep both versions
                                   │                    │
                              previous ACTIVE     RESTART_REQUIRED
```

---

## 7. Entry Points

- `ManagerFacade.initialize()`
- `ManagerFacade.repoUpdate()` / `repoInstall(...)` / `repoUpgrade(listener)` /
  `repoFixBroken()` / `repoRemove(...)`
- `ManagerFacade.packageInstall(...)` / `packageRemove(...)` / `packageVerify(...)`
- `ManagerFacade.checkUpdates()`

---

## 8. Exploration Path

```
1. ManagerFacade.java            → Unified API
2. RepositoryManager.java        → Index refresh, cache and candidates
3. DependencyResolver.java       → Resolution plans
4. AptInstaller.java             → Download + install + two-phase upgrade
5. PackageUpgradeListener.java   → Runtime confirmation hook
```

---

## 9. Related Modules

- [manager-spi](../manager-spi/README.md) — Manifests, repository config and package metadata
- [manager-core](../manager-core/README.md) — Local installer and package database
- [ModuleHost](../suite-runtime/src/main/java/me/majhrs16/suite/runtime/ModuleHost.java) — Live module lifecycle and hot upgrades
- [spigot-host](../spigot-host/README.md) — Runs `SuiteRuntime`
- [fabric-host](../fabric-host/README.md) — Runs `SuiteRuntime`
