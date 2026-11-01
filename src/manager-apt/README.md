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
- **Stage-1 cache** — `PackageCache` stores verified artifacts by SHA-256 under
  `library/cache/downloads/<sha256>/`, resumes incomplete `.part` files with
  HTTP `Range`, writes `library/cache/operations/<operationId>.json` with
  `PENDING`/`COMPLETE`, and never treats an unverified file as complete.
- **Batch installation** — `AptInstaller` downloads and verifies every artifact
  before mutating the installed store, then delegates one
  `PackageInstaller.installBatch(...)` call. A download failure leaves the
  installation untouched and can be resumed or cleaned with `cache clean`.
- **Activation is separate** — `AptInstaller.upgrade()` only commits files and
  `library/status`; `suite-runtime` activates the committed modules afterwards.
  There is no runtime veto, no `keepBoth`, and no automatic file rollback after
  the status commit.
- **Unified API** — `ManagerFacade` wires the database, repositories, resolver and
  installer, and exposes `repoUpdate`, `repoInstall`, `repoUpgrade`,
  `repoFixBroken`, `repoRemove`, `cacheClean`, `packageInstall/List/Remove/
  Files/Verify` and `checkUpdates`.

---

## 2. Non-Responsibilities

- **No ZIP extraction or file database** — `manager-core` owns both.
- **No module lifecycle** — `suite-runtime` loads, activates, upgrades and
  unloads modules. The manager only commits files and status; the runtime
  observes the committed result.
- **No package format or parser definitions** — `manager-spi` defines them.
- **No platform wiring** — `spigot-host`/`fabric-host` construct `SuiteRuntime`,
  which constructs `ManagerFacade`.

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `PluginLogger` and base domain types |
| `manager-spi` | Compile | Manifests, repository config, versions, package metadata |
| `manager-core` | Compile | `PackageInstaller`, `LocalPackageDatabase`, `PackageJournal` |

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
| `AptInstaller` | Stage-1 download/cache, batch commit and cache cleanup |
| `PackageCache` | Content-addressed downloads, resumable partials and manifests |

---

## 6. Data Flow

```
repo.yml ──► RepositoryManager.updateAll() ──► cached index
                                   │
                                   ▼
                    DependencyResolver.resolve*()
                                   │  ResolutionPlan (INSTALL/UPGRADE/REMOVE/DOWNGRADE)
                                   ▼
                    AptInstaller ──► PackageCache (download + SHA-256)
                                   │  all artifacts verified before mutation
                                   ▼
                    PackageInstaller.installBatch(...) (manager-core)
                                   │  journal → files → status commit
                                   ▼
                    LocalPackageDatabase + PackageJournal
                                   │
                                   ▼
                    suite-runtime activates the committed packages
                       │ success                 │ failure
                       ▼                         ▼
                     ACTIVE            ACTIVATION_FAILED / RESTART_REQUIRED
                                       (files stay installed)
```

The package-level outcome is persisted in `manager/runtime/modules.status` under
`package:<name>`; each module also keeps its own `moduleId` entry.

---

## 7. Entry Points

- `ManagerFacade.initialize()`
- `ManagerFacade.repoUpdate()` / `repoInstall(...)` / `repoUpgrade()` /
  `repoFixBroken()` / `repoRemove(...)` / `cacheClean()`
- `ManagerFacade.packageInstall(...)` / `packageRemove(...)` / `packageVerify(...)`
- `ManagerFacade.checkUpdates()`

---

## 8. Exploration Path

```
1. ManagerFacade.java            → Unified API
2. RepositoryManager.java        → Index refresh, cache and candidates
3. DependencyResolver.java       → Resolution plans
4. AptInstaller.java             → Stage-1 download + batch commit
5. PackageCache.java             → Content-addressed cache and resume
```

---

## 9. Related Modules

- [manager-spi](../manager-spi/README.md) — Manifests, repository config and package metadata
- [manager-core](../manager-core/README.md) — Local installer and package database
- [ModuleHost](../suite-runtime/src/main/java/me/majhrs16/suite/runtime/ModuleHost.java) — Live module lifecycle and hot upgrades
- [spigot-host](../spigot-host/README.md) — Runs `SuiteRuntime`
- [fabric-host](../fabric-host/README.md) — Runs `SuiteRuntime`
