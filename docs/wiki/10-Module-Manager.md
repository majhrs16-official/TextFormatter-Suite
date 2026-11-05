# Module Manager

> **ADR-027 implemented.** The manager uses the staged pipeline: acquisition
> into a content-addressed cache, journaled file/status commit and separate
> runtime activation. Activation failures are reported as `ACTIVATION_FAILED`;
> they do not roll back installed files. **ADR-029** adds reproducible
> distribution, third-party `DEPENDENCY` packages and the repository trust
> boundary.

## Overview

TextFormatter Suite separates **installation facts** from **live module state**:

- `manager-core` / `manager-apt` own `library/status`, package files and
  repository operations.
- `suite-runtime` owns `ModuleHost`: classloaders, activation, deactivation,
  hot upgrades and runtime states.

Neither layer pretends to be the other. `PackageRuntime` is the only bridge.

## Architecture

```
RepositoryManager ──► DependencyResolver ──► AptInstaller
                                                   │
                    ┌──────────────────────────────┼──────────────────────────┐
                    ▼                              ▼                          ▼
          Stage 1: cache/plan          Stage 2: PackageInstaller       Stage 3: ModuleHost
          SHA-256, PENDING/COMPLETE    journal + status + versioned    start/stop, leak checks
                                       directories                     ACTIVATION_* states
```

### Package storage

| Path | Meaning |
|---|---|
| `manager/library/packages/<packageId>/<version>/` | Module JARs for one installed version; the previous version directory survives until cleanup |
| `manager/library/dependencies/<dependencyId>/<version>/` | Shared dependency JARs, visible to module classloaders |
| `manager/library/status` | Authoritative installed-package database and persistent version pointer |
| `manager/library/cache/downloads/<sha256>/` | Content-addressed ZIP cache; `.part` files are never valid |
| `manager/library/cache/operations/<operationId>.json` | Per-operation artifact states (`PENDING`/`COMPLETE`) |
| `manager/library/journal/<operationId>.log` | Durable stage-2 journal used for crash recovery |
| `manager/library/backup/<operationId>/` | Backups of replaced non-versioned files |
| `manager/library/trash/<operationId>/` | Reversibly removed package files used by journal rollback before commit |
| `manager/runtime/modules.status` | Persistent activation state (`INSTALLED`, `ACTIVATION_PENDING`, `ACTIVE`, `FAILED`, `ACTIVATION_FAILED`, `RESTART_REQUIRED`); module ids are keys and package-level outcomes use `package:<name>` |

`library/status` records what is installed. It never records that a module is
active. A failed activation does not remove the package from `library/status`;
it changes the runtime state only.

## Lifecycle

### Activation

1. `PackageRuntime` reads installed packages and resolves dependency order.
2. `ModuleHost` creates an isolated `ModuleClassLoader` per module JAR.
3. The activator is loaded from the module's own
   `META-INF/services/me.majhrs16.suite.api.runtime.ModuleActivator`.
4. The descriptor, API contract, platform and capabilities are validated.
5. `start(ModuleContext)` runs inside the module's `ThreadGroup`.
6. Services, events, scheduler tasks, commands and configs are registered under
   the module id.
7. The runtime state becomes `ACTIVE` only after `start()` succeeds.

A failure closes the context, runs `stop()` for cleanup, closes the classloader
and marks the module `FAILED` — or `RESTART_REQUIRED` if cleanup leaked.

The package-level entry is written before the first `start()`
(`ACTIVATION_PENDING`), then becomes `ACTIVE`, `ACTIVATION_FAILED` or
`RESTART_REQUIRED`. Removing the package clears its entry.

### Deactivation

1. `stop()` runs inside the module's `ThreadGroup`.
2. `ResourceTracker` releases tracked resources in reverse order.
3. The scheduler cancels module-owned tasks.
4. Event subscriptions, services, commands and config sections owned by the
   module are removed.
5. The classloader closes.
6. The runtime checks four leak sources: resource cleanup failures, remaining
   owner registrations, an unclosed classloader and live threads in the module's
   `ThreadGroup`.

Clean shutdown marks the module `INSTALLED`. Any leak marks it
`RESTART_REQUIRED`.

### Hot reload and restart

Hot unload and hot upgrade are supported only when deactivation is clean. The
runtime closes the old classloader, releases tracked resources, cancels module
tasks and removes the services, commands, events and config sections owned by
the module. Versioned directories keep the old JARs available for manual
recovery, but the old runtime version is never reactivated automatically after a
stage-3 failure.

A restart is required when:

- `stop()` throws, a tracked resource fails to close, a live thread remains in
  the module `ThreadGroup`, or an owner registration is left behind;
- an active module uses a dependency package that is being upgraded or removed,
  directly or transitively;
- the platform adapter cannot undo a registration and reports the failure
  instead of pretending success;
- the operator wants to retry a module whose runtime state is
  `RESTART_REQUIRED`.

`RESTART_REQUIRED` is sticky: install, upgrade and remove operations that would
need to unload that module fail explicitly until the process restarts. A restart
re-reads `library/status` and activates the committed version. A stage-3
activation failure is different: files and `library/status` are already
committed, so activation can be retried without reinstalling, and the package is
reported as `ACTIVATION_FAILED`.

### Staged install and upgrade

1. **Acquisition.** Resolve packages, transitive dependencies, versions,
   conflicts, platform and activation order. Download every ZIP to
   `library/cache/downloads/<sha256>/` and verify size and SHA-256. Each artifact
   is `PENDING` or `COMPLETE`; a retry downloads only the pending ones. A
   `cache clean` command removes cache entries and operation manifests, not
   installed packages or `library/status`.
2. **File commit.** Apply the whole plan in one transaction with a durable journal
   under `library/journal/`. New versions go to
   `library/packages/<packageId>/<version>/`; the active version directory is not
   overwritten. `library/status` is published with an atomic rename and is the
   commit point. Recovery before the commit point rolls the journal back;
   recovery after it only completes cleanup. This is the only automatic
   rollback.
3. **Activation.** After the commit, stop previous versions in reverse order and
   start the new modules in dependency order. Persist `ACTIVATION_PENDING` before
   the first `start()`. If a module fails, stop the modules already started in
   this attempt in reverse order, keep the files and `library/status`, and mark
   `ACTIVATION_FAILED`. The previous version is not reactivated and no new files
   are deleted. Activation can be retried without reinstalling.

A package may contain several module JARs; every active module in it is upgraded
in the same runtime operation, mapped by the `moduleId` declared in each JAR. If
one module fails, the reverse-order cleanup covers the modules started in that
attempt; it is runtime cleanup, not an installation rollback.

The two versions are not started simultaneously: they share the module id,
service/config namespaces, command paths and thread group. Versioned directories
keep the old files available for manual recovery, but the old runtime version is
never restored automatically after a stage-3 failure.

### Staged uninstall

1. **Plan.** Resolve the reverse dependency closure and validate that no remaining
   module depends on what will be removed. No download is needed.
2. **Runtime barrier and file commit.** Deactivate affected modules in reverse
   order before touching files. If deactivation leaks, abort without removing
   anything and mark `RESTART_REQUIRED`. Otherwise move the owned files to
   `library/trash/<operationId>/` and publish `library/status`. If the file commit
   fails, restore the files and reactivate the previous versions.
3. **Finalization.** Clear the package runtime entry. Stage 3 does not mutate
   files. Installed dependency cleanup happens inside the same stage-2 manager
   transaction when auto-remove is requested. A stage-3 failure does not restore
   removed files; it marks `RESTART_REQUIRED` if live references remain.

`library/trash` holds removed files for journal rollback before the commit point;
after the commit it is only manual recovery, never an automatic rollback source.
Deactivation must run before file removal because an active classloader may still
hold a JAR open. An `Exclude` replacement uses the same barrier: stage
2 deactivates the packages removed by the plan before moving their files, and
stage 3 only starts the new packages.

### Package contents and module identity

A `PACKAGE` contains one or more module JARs. The package name is not the module
id: every JAR declares its own `moduleId` through
`ModuleActivator.descriptor()`, and `PackageRuntime` maps activation, upgrade and
removal by that id. Module ids must be unique within a package; zero JARs or
duplicate ids are explicit failures. Additional libraries belong in a
`DEPENDENCY` package.

Activation starts every JAR in the package. An upgrade replaces every active
module in the package in one runtime operation. If activation fails, the modules
already started in that attempt are stopped in reverse order and the package is
marked `ACTIVATION_FAILED`; installed files are not rolled back. The runtime
never chooses the first JAR silently and never exposes a package whose activation
and upgrade capabilities differ.

### Shared dependencies

Dependency packages are versioned too. A dependency upgrade is rejected while an
active module uses it directly or transitively; removing the old JAR could break
lazy class loading. Restart the server to apply that dependency upgrade.

Feature libraries that do not cross an API boundary are shipped as `DEPENDENCY`
packages: `snakeyaml`, `json`, `gson`, `java-websocket`, `chicory`,
`prometheus`, `spring-expression`, `jackson-databind` and `jda`. Their
transitive JARs are resolved at build time and declared in the consumer's
`moduleDependencies`, so the manager installs them before the module and
`ModuleClassLoader` exposes them from `library/dependencies`.

Adventure remains a host API dependency. `core-render` exposes Adventure types
in its contract, so loading a second Adventure classloader would split
`Component`/`Audience` identity across the API boundary. MiniMessage and the
plain serializer stay on the host for the same reason. A module must not declare
an Adventure `DEPENDENCY` package unless that contract is redesigned.

## Commands

The command root is configurable in `config/suite/commands.yml`. By default it is
`/suite` with `/txf` as an alias. Module commands register relative paths and
never hardcode `/suite`.

All built-in commands require `textformatter.suite.admin`.

| Command | Description |
|---|---|
| `/<root> help` | Show the active command tree |
| `/<root> reload` | Reload `commands.yml` and re-register the root |
| `/<root> list` | List installed packages and their runtime state |
| `/<root> state` | Show known runtime module states |
| `/<root> install <zip>` | Install and activate a local ZIP |
| `/<root> remove <pkg> [version] [channel]` | Deactivate and remove a package |
| `/<root> upgrade` | Upgrade all installed packages |
| `/<root> repo install <pkg...>` | Install packages from configured repositories |
| `/<root> repo remove <pkg> [version] [channel]` | Remove a package through repositories |
| `/<root> repo upgrade` | Upgrade packages from configured repositories |
| `/<root> cache clean` | Remove downloads, `.part` files and operation cache manifests |

Examples with the default root:

```text
/suite help
/suite list
/suite state
/suite install packages/textformatter-1.0.0.zip
/suite remove textformatter
/suite repo install textformatter messages
/suite upgrade
/suite reload
```

The complete command reference is [Commands Reference](08-Commands.md).

## Repository Configuration

Repositories are declared in `manager/repo.yml`:

```yaml
repositories:
  - name: stable
    type: http
    url: https://example.com/suite
    enabled: true
    priority: 10
config-version: 1.0
```

Supported types are `http`, `local` and `github`. The manager reads
`packages.list` from each repository, not `releases.json`. Every package entry
declares its SHA-256; downloads without a valid checksum are rejected.

## Package format

A package is a ZIP with `METADATA/package.list` and its files. A module
`PACKAGE` contains one or more executable JARs under
`manager/library/packages/<packageId>/<version>/`; dependency packages contain
their JARs under `manager/library/dependencies/<dependencyId>/<version>/`.
Each module JAR declares its own id through
`ModuleActivator.descriptor()`; the package name is not assumed to be a module
id. The `package.list` records name, version, channel, type, platform,
dependencies and per-file SHA-256.

There is no `module.yml` inside the package: the authoritative metadata is
`packages.list` / `package.list`, and the module JAR exposes its descriptor
through `ModuleActivator.descriptor()`.

## Distribution and repository responsibility

### Build outputs

| Task | Output | Purpose |
|---|---|---|
| `:<module>:modulePackage` | `build/distributions/<id>-<version>.zip` | One module or dependency package with `METADATA/package.list` |
| `:<module>:moduleChecksum` | `<zip>.sha256` | `sha256sum`-style sidecar; `assemble` depends on it |
| `thirdPartyPackages` | `build/third-party/*.zip` | Third-party `DEPENDENCY` packages with resolved transitive JARs |
| `moduleRepository` | `build/repository/` | Every module ZIP, sidecar, `packages.list` and `SHA256SUMS` |

`moduleRepository` requires `-PrepositoryBaseUrl=https://...`; it rejects a
missing or blank base URL. `packages.list` is the manager's authoritative index.
`SHA256SUMS` lets a mirror verify the whole directory with `sha256sum -c`.
Published ZIPs, sidecars and indexes must be immutable: a new build gets a new
version or a new directory, never a rewritten artifact under the same URL.

### Reproducibility

Module ZIPs use a stable entry order and fixed timestamps; the module JAR
manifests use `Build-Timestamp: 0` unless a release explicitly supplies
`BUILD_TIMESTAMP`/`-PbuildTimestamp`. The same sources, dependencies and
toolchain must produce byte-identical JARs and ZIPs. The build does not embed
wall-clock time, absolute paths or machine-local URLs.

### Host and module separation

The Spigot and Fabric shadow JARs contain only the host runtime
(`me.majhrs16.suite.{api,host,kernel,manager,runtime,...host}`), never feature
classes. `verifyShadowJarHasNoFeatures` runs as part of `check` and fails if a
feature leaks into a host artifact. Features, platform listeners and
`sync-bukkit`/`sync-fabric` are downloaded as packages; they are not embedded in
the bootstrap.

### Responsibility split

The repository owner is responsible for:

- publishing immutable ZIPs, matching `.sha256` sidecars, `packages.list` and
  `SHA256SUMS`;
- keeping old versions available long enough for rollback and manual recovery;
- using a stable HTTPS base URL and access controls appropriate to the trust
  level of the deployment;
- never publishing placeholder URLs, mutable checksums or a `packages.list` that
  disagrees with the ZIPs.

The manager is responsible for:

- resolving the dependency graph and rejecting missing or unsatisfied
  dependencies before mutating files;
- verifying size and SHA-256 for every downloaded artifact and using a
  content-addressed cache;
- applying stage 1 (acquisition), stage 2 (journaled commit) and stage 3
  (activation) exactly as described above;
- treating `library/status` as the only installed-version pointer and never
  overwriting a JAR used by an active `ClassLoader`.

### Trust boundaries and limits

TLS plus SHA-256 detects corruption and transport tampering; it does **not**
prove authorship. There is no signature or certificate pinning in this phase, so
a compromised repository can serve malicious packages with matching checksums.
The repository owner is trusted. Module isolation is a classloader and
capability boundary, not a security sandbox against hostile JVM code.

Third-party packages are resolved from Maven Central at build time and may carry
transitive duplicates. Modules must declare the dependencies they actually use;
the manager does not infer imports. Adventure is the documented API-boundary
exception. No E2E test runs against real Spigot/Paper/Fabric/Velocity servers;
the recovery and activation gates use real ZIPs, JARs and temporary manager
directories.

## API reference

The live lifecycle contracts are in `core-api`:

```java
public interface ModuleActivator {
    ModuleDescriptor descriptor();
    void start(ModuleContext context) throws Exception;
    void stop() throws Exception;
}

public interface ModuleContext extends AutoCloseable {
    String moduleId();
    Path configDir();
    PluginLogger logger();
    ServiceRegistry services();
    EventBus events();
    Scheduler scheduler();
    ResourceTracker resources();
    CommandRegistry commands();
    ConfigRegistry config();
    PlatformInfo platform();
    boolean isShuttingDown();
    void close();
}
```

`ModuleHost` exposes:

```java
List<ActivationResult> loadInstalled(); // inspection utility; startup uses library/status
ActivationResult activate(Path jar);
ActivationResult activate(Path jar, List<Path> dependencyJars);
UpgradeResult upgrade(String id, Path newJar, List<Path> dependencyJars);
DeactivationResult deactivate(String id);
Map<String, ModuleState> snapshot();
List<String> activeModules();
```

The old `ModuleLifecycle`, `ModuleCoordinate`, `ModuleDescriptor` (manager-spi)
and `Environment` bridge was removed in F7. Module metadata lives in
`me.majhrs16.suite.api.ModuleDescriptor`.

## Testing

```bash
./gradlew :src:suite-runtime:test :src:manager-apt:test
```

The suite includes real JAR/ZIP fixtures for:

- activation and deactivation;
- successful upgrade with the old classloader closed;
- failed stage-2 commit restoring previous files, directories and `library/status`;
- failed stage-3 activation that keeps the installation and persists
  `ACTIVATION_FAILED`;
- reverse-order cleanup of modules started during a failed activation;
- cache resume and cleanup without touching installed packages;
- journal recovery before and after the commit point;
- uninstall deactivation barrier before file removal;
- leaked module threads and failing resource cleanup.

No E2E test runs against real Spigot/Paper/Fabric/Velocity servers.

## Status

| Feature | Status |
|---|---|
| Version resolution | ✅ |
| Dependency resolution | ✅ |
| SHA-256 verification | ✅ |
| Versioned package paths | ✅ |
| Isolated classloaders | ✅ |
| Activation/deactivation | ✅ |
| Staged acquisition, commit and activation | ✅ ADR-027 |
| Stage-1 content-addressed cache and resume | ✅ ADR-027 |
| Stage-2 journal and crash recovery | ✅ ADR-027 |
| `ACTIVATION_FAILED` without install rollback | ✅ ADR-027 |
| Versioned package directories and persistent pointer | ✅ ADR-027 |
| One or more module JARs per `PACKAGE` with unique module ids | ✅ |
| Reproducible module ZIPs and `.sha256` sidecars | ✅ |
| Third-party `DEPENDENCY` packages with resolved transitives | ✅ |
| Repository `packages.list` and `SHA256SUMS` | ✅ |
| Leak detection and `RESTART_REQUIRED` | ✅ |
| Local/HTTP/file repositories | ✅ |
| GitHub releases | Not used; `packages.list` is authoritative |
| Real-server E2E | Not available |

## See Also

- [Commands Reference](08-Commands.md)
- [Configuration Guide](02-Configuration.md)
- [Developer Guide](09-Developer-Guide.md)
- [ADR-026 / ADR-027 / ADR-029](../workdir/ADR.md)
- [PLAN](../workdir/PLAN.md)
- [Philosophy, C13](../prompts/diseno/philosophy.md) — hypotheses need evidence; invariant or full support
