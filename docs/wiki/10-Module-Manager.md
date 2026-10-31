# Module Manager

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
                                                   ▼
                                      PackageInstaller (manager-core)
                                                   │
                                      library/status + library/packages
                                                   │
                                                   ▼
                                          PackageRuntime
                                                   │
                    ┌──────────────────────────────┴──────────────────────────┐
                    ▼                                                         ▼
          ModuleHost (suite-runtime)                         PackageUpgradeListener
          classloaders, start/stop, rollback                 runtime confirmation
```

### Package storage

| Path | Meaning |
|---|---|
| `manager/library/packages/<id>-<version>.jar` | Module JARs, versioned so the active version survives until an upgrade is confirmed |
| `manager/library/dependencies/<id>-<version>.jar` | Shared dependency JARs, visible to module classloaders |
| `manager/library/status` | Authoritative installed-package database |
| `manager/runtime/modules.status` | Transient runtime state (`ACTIVE`, `FAILED`, `RESTART_REQUIRED`, ...) |

`library/status` records what is installed. It never records that a module is
active. A failed activation does not remove the package from `library/status`.

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

### Hot upgrade

1. The new version is installed at a versioned path; the active JAR is untouched.
2. `ModuleHost.upgrade(...)` prepares the new classloader, descriptor and context
   without starting it.
3. The previous version stops and releases its resources.
4. If the previous version leaks, the new version is not started and the state is
   `RESTART_REQUIRED`.
5. The new version starts. If it fails, the previous JAR is prepared and started
   again (rollback), and the new package files are removed.
6. If a batch upgrades several modules and one fails, `PackageRuntime` records
   every module already upgraded, restores those JARs in reverse order and
   verifies that each one is `ACTIVE`. Only then does it authorize the manager
   to delete the new files. A local ZIP with several packages follows the same
   transaction, not just `repo upgrade`.
7. If compensation cannot verify every restoration, the runtime returns
   `keepBoth`: both versions stay on disk and the affected modules become
   `RESTART_REQUIRED`. No artifact is deleted.
8. Only after a clean upgrade does the manager remove the previous package.

The two versions are not started simultaneously: they share the module id,
service/config namespaces, command paths and thread group. Preparing the new
classloader first isolates class loading without colliding registrations.

### Package cardinality invariant

A `PACKAGE` contains exactly one module JAR. Additional libraries belong in a
`DEPENDENCY` package. `PackageRuntime` validates this before activation or
upgrade: zero or more than one module JAR is an explicit failure, never a silent
choice of the first JAR. Activation and upgrade use the same cardinality so the
two paths cannot drift.

### Shared dependencies

Dependency packages are versioned too. A dependency upgrade is rejected while an
active module uses it directly or transitively; removing the old JAR could break
lazy class loading. Restart the server to apply that dependency upgrade.

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

Repositories are declared in `config/suite/repo.yml`:

```yaml
repositories:
  - name: stable
    type: http
    url: https://example.com/suite
    enabled: true
    priority: 10
```

Supported types are `http`, `file` and `github`. The manager reads
`packages.list` from each repository, not `releases.json`. Every package entry
declares its SHA-256; downloads without a valid checksum are rejected.

## Package format

A package is a ZIP with `METADATA/package.list` and its files. A module
`PACKAGE` contains exactly one executable JAR under
`manager/library/packages/`; dependency packages contain their JARs under
`manager/library/dependencies/`. The `package.list` records name, version,
channel, type, platform, dependencies and per-file SHA-256.

There is no `module.yml` inside the package: the authoritative metadata is
`packages.list` / `package.list`, and the module JAR exposes its descriptor
through `ModuleActivator.descriptor()`.

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
List<ActivationResult> loadInstalled();
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
- successful hot upgrade with the old classloader closed;
- failed upgrade with rollback to the previous version;
- leaked module threads;
- failing resource cleanup;
- coherent installed state after upgrade and rollback.

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
| Hot upgrade + rollback | ✅ |
| Batch compensation and `keepBoth` recovery | ✅ |
| One module JAR per `PACKAGE` invariant | ✅ |
| Leak detection and `RESTART_REQUIRED` | ✅ |
| Two-phase manager/runtime upgrade | ✅ |
| Local/HTTP/file repositories | ✅ |
| GitHub releases | Not used; `packages.list` is authoritative |
| Real-server E2E | Not available |

## See Also

- [Commands Reference](08-Commands.md)
- [Configuration Guide](02-Configuration.md)
- [Developer Guide](09-Developer-Guide.md)
- [ADR-026](../workdir/ADR.md)
- [PLAN](../workdir/PLAN.md)
- [Philosophy, C13](../prompts/diseno/philosophy.md) — hypotheses need evidence; invariant or full support
