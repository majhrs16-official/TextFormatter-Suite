# Module Manager

## Overview

The Module Manager is TextFormatter Suite's runtime module management system. It handles module discovery, installation, updates, and lifecycle management through a secure, SPI-based architecture.

## Key Features

- **Runtime Module Installation** - Install modules without server restart
- **Version Resolution** - SemVer ranges with environment compatibility
- **Dependency Management** - Automatic transitive dependency resolution
- **SHA256 Verification** - Mandatory checksum verification
- **Isolated ClassLoaders** - Parent-last delegation for dependency isolation
- **Repository Abstraction** - GitHub, local (`file://`), and HTTP repositories
- **Manifest Validation** - Mandatory `module.yml` validation

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Module Manager                            │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ Repository  │  │   Version   │  │   Dependency        │ │
│  │ Abstraction │──▶│  Resolver   │──▶│   Resolver          │ │
│  │ (GitHub,    │  │ (semver +   │  │ (module.yml from    │ │
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
│                                    │  discoverAvailableModules()│
│                                    └─────────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/suite module list` | List installed/available modules | `textformattersuite.admin` |
| `/suite module info <module>` | Show module details | `textformattersuite.admin` |
| `/suite module install <id> [version]` | Install module | `textformattersuite.admin` |
| `/suite module update <module> [version]` | Update module | `textformattersuite.admin` |
| `/suite module remove <module>` | Remove module | `textformattersuite.admin` |
| `/suite update` | Full suite update | `textformattersuite.admin` |
| `/suite module list --available` | Show all available modules | `textformattersuite.admin` |

## Repository Configuration

The Module Manager supports multiple repository sources with ordered fallback:

```yaml
# config.yml
repositories:
  - name: "github"
    type: "github"
    url: "https://api.github.com/repos/majhrs16-official/TextFormatter-Suite"
    enabled: true
  - name: "local"
    type: "local"
    url: "file:///path/to/modules"
    enabled: false
  - name: "http"
    type: "http"
    url: "https://myserver.com/releases"
    enabled: false
```

### Repository Types

| Type | Description | URL Format |
|------|-------------|------------|
| `github` | GitHub Releases API | `https://api.github.com/repos/owner/repo` |
| `local` | Local filesystem | `file:///path/to/modules` or relative path |
| `http` | Generic HTTP server | `https://myserver.com/releases` |

### Repository Fallback

- Repositories are checked in order (first enabled wins)
- Local repos use `file://` URLs and `releases.json` format
- HTTP repos use `/releases?per_page=100` endpoint returning JSON array
- Fallback order allows local testing without GitHub

### Local Repository Format

Local repositories use a `releases.json` file:

```json
[
  {
    "tag_name": "v1.0.0",
    "name": "Test Module 1.0.0",
    "published_at": "2024-01-01T00:00:00Z",
    "assets": [
      {
        "name": "suite-test-module-1.0.0.jar",
        "browser_download_url": "file:///path/to/modules/suite-test-module-1.0.0.jar",
        "size": 12345
      },
      {
        "name": "suite-test-module-1.0.0.jar.sha256",
        "browser_download_url": "file:///path/to/modules/suite-test-module-1.0.0.jar.sha256",
        "size": 64
      }
    ]
  }
]
```

Each JAR must have a corresponding `.sha256` file with the checksum.

## Module Lifecycle

### Installation Process

1. **Resolve** - Version resolver matches semver + environment compatibility
2. **Download** - Download JAR from repository with SHA256 verification
3. **Relocate** - Shade dependencies (parent-last ClassLoader)
4. **Load** - Create isolated ClassLoader (parent-last delegation)
5. **Register** - SPI-only registration with kernel (`discoverAll()`)

### Register Semantics

The `register()` method is **SPI-only**:
- Does NOT instantiate `Module` class
- Stores descriptor + ClassLoader for `discoverAll()`
- Module services activate through platform entry points (Spigot plugin / Fabric mod)

### Manifest Validation

Every module JAR must contain `module.yml` or `module.yaml`:

```yaml
name: my-module
version: 1.0.0
description: My awesome module
artifact: suite-my-module
depends:
  - me.majhrs16:suite-core-api:2.1.0-SNAPSHOT
  - me.majhrs16:suite-textformatter:2.1.0-SNAPSHOT
```

Validation checks:
- Required fields: `name`, `version`, `description`, `artifact`
- Version matches JAR manifest
- Declared capabilities exist in registry
- Required dependencies are satisfiable

## Security

### SHA256 Verification

- **Mandatory** - No skip option
- Separate `.sha256` asset per JAR
- Verified during download and on load
- Fails if checksum mismatch or missing

### Dependency Verification

- `verification-metadata.xml` with SHA256/SHA512 for all dependencies
- `dependencyLocking` in `build.gradle` for reproducible builds
- `dependencyVerification` in `settings.gradle` (configurable for CI)

### Isolated ClassLoaders

- Parent-last delegation for module classes
- Parent-first for API/JDK classes
- Dependency relocation (shading) for non-API packages
- Automatic cleanup on unload

## Module Manifest Format

```yaml
name: my-module
version: 1.0.0
description: Module description
artifact: suite-my-module
authors:
  - "Author Name <email@example.com>"
license: GPL-3.0
repository: "https://github.com/user/repo"
issues: "https://github.com/user/repo/issues"

# Required capabilities
provides:
  - my-feature
  - my-api

# Required dependencies
requires:
  - my-dependency
  - other-api:2.0.0

# Optional dependencies
suggests:
  - optional-feature

# Environment requirements
minJavaVersion: 17
maxJavaVersion: 21
requiredCoreApi: "2.1.0"
minecraftVersion: "1.20.6"
platforms:
  - spigot
  - fabric
  - velocity
  - common
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/suite module list` | List installed modules | `textformattersuite.admin` |
| `/suite module list --available` | Show available modules | `textformattersuite.admin` |
| `/suite module info <module>` | Show module details | `textformattersuite.admin` |
| `/suite module install <id> [version]` | Install module | `textformattersuite.admin` |
| `/suite module update <module> [version]` | Update module | `textformattersuite.admin` |
| `/suite module remove <module>` | Remove module | `textformattersuite.admin` |
| `/suite update` | Full suite update | `textformattersuite.admin` |

### Version Specification

| Format | Example | Meaning |
|--------|---------|---------|
| Exact | `1.0.0` | Exactly version 1.0.0 |
| Range | `[1.0.0,2.0.0)` | 1.0.0 ≤ version < 2.0.0 |
| Caret | `^2.1.0` | ≥2.1.0 <3.0.0 |
| Tilde | `~2.1.0` | ≥2.1.0 <2.2.0 |
| Latest | `latest` | Latest compatible |

## Local Testing

Test modules locally without GitHub:

```yaml
# config.yml
repositories:
  - name: "local-test"
    type: "local"
    url: "file:///path/to/test/modules"
    enabled: true
```

Create `releases.json` in the directory (see Local Repository Format above).

## API Reference

### ModuleLifecycle SPI

```java
public interface ModuleLifecycle {
    // Resolve module with dependencies
    ResolutionResult resolve(ModuleCoordinate coordinate, Environment env, boolean force);
    
    // Download JARs with SHA256 verification
    List<Path> download(ResolvedModule resolved, List<ResolvedModule> deps, Path cacheDir);
    
    // Relocate dependencies (shading)
    Path relocate(Path moduleJar, Path outputDir, Map<String, String> relocations);
    
    // Load into isolated ClassLoader
    ClassLoader load(Path moduleJar, List<Path> dependencyJars, ClassLoader parent);
    
    // SPI-only registration
    boolean register(ClassLoader classLoader, ModuleDescriptor descriptor);
    
    // Unregister/unload
    boolean unregister(String moduleId);
    void unload(String moduleId);
    
    // Update/check updates
    List<ModuleCoordinate> checkUpdates();
    List<ModuleCoordinate> updateSuite(Environment env, boolean force);
    
    // Discovery
    List<ModuleDescriptor> getLoadedModules();
    List<ModuleCoordinate> discoverAvailableModules();
    List<Module> discoverAll(ClassLoader parent);
}
```

### ResolutionResult

```java
sealed interface ResolutionResult permits Success, Failure {
    record Success(ResolvedModule module, List<ResolvedModule> dependencies) implements ResolutionResult {}
    record Failure(String reason, List<String> candidates) implements ResolutionResult {}
}
```

## Testing

### Local HTTP Repository Tests

```bash
./gradlew :src:manager-impl:test --tests LocalHttpRepositoryTest --offline --no-daemon
```

Tests cover:
- Local `file://` repository resolution
- HTTP repository resolution
- Repository fallback ordering
- `discoverAvailableModules()` with local/HTTP repos

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| "No matching release" | Version not found | Check version spec, repository enabled |
| SHA256 mismatch | Corrupted download | Clear cache, re-download |
| Manifest validation failed | Invalid module.yml | Check required fields, version match |
| ClassLoader conflict | Dependency conflict | Check relocations, use parent-last |
| Dependency unresolved | Missing capability | Add dependency, check capability name |

## Status

| Feature | Status |
|---------|--------|
| Version Resolver | ✅ |
| Dependency Resolver | ✅ |
| SHA256 Verification | ✅ |
| Dependency Relocator | ✅ |
| Isolated ClassLoader | ✅ |
| register() SPI-only | ✅ |
| discoverAll() | ✅ |
| discoverAvailableModules() | ✅ |
| Manifest Validation | ✅ |
| Local Repositories | ✅ |
| HTTP Repositories | ✅ |
| GitHub Repositories | ⏳ (requires releases) |
| Release Pipeline | ⏳ (pending CI/CD) |
| GitHub Releases | ⏳ (pending) |

## See Also

- [Configuration Guide](02-Configuration.md#repositories)
- [Commands Reference](08-Commands.md#module-manager)
- [Developer Guide](09-Developer-Guide.md#modules)