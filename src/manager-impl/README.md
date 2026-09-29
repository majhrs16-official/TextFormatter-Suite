# manager-impl — Module Manager Implementation

> **Purpose**: Implements `manager-api` for remote module loading from GitHub, local filesystem (`file://`), and HTTP repositories. Resolves, downloads, and installs modules at runtime. **Core implementation complete**; GitHub Releases = 0 (pending release pipeline).

---

## 1. Responsibilities

- **Multi-repository module resolution** — `ModuleResolver` interface with implementations:
  - `GitHubModuleResolver` — GitHub Releases (when published)
  - `LocalModuleResolver` — `file://` local filesystem (for testing)
  - `HttpModuleResolver` — generic HTTP endpoints
- **Ordered fallback** — repositories tried in config order (GitHub → local → HTTP)
- **Module installation** — downloads JARs, verifies SHA256 (mandatory), installs to local Maven repo
- **Dependency resolution** — resolves transitive dependencies from `module.yml` manifest
- **Manifest validation** — mandatory `module.yml` validation pre-load (throws if missing)
- **Lifecycle management** — `ModuleManager` implements `ModuleLifecycle` (SPI-only register, discoverAll())
- **Integration with host** — provides modules to `SuiteBootstrap`

---

## 2. Non-Responsibilities

- **No local module loading** — `kernel` handles that
- **No platform-specific code** — pure Java
- **No UI** — CLI/API only

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `ModuleDescriptor` |
| `manager-api` | Compile | Implements `ModuleLifecycle`, `ModuleCoordinate` |
| `kernel` | Compile | `ModuleLoader` for local installation |
| `host` | Compile | `SuiteHost` integration |
| `gson` | Compile | GitHub API JSON parsing |
| `commons-compress` | Compile | JAR handling |
| `snakeyaml` | Compile | Config parsing |
| `okhttp` | Compile | GitHub API HTTP client |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Remote module loading at startup |
| `fabric-host` | Remote module loading at startup |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `ModuleManager` | Main facade: `install(coord)`, `update(coord)`, `resolveDependencies()` |
| `GitHubModuleResolver` | Queries GitHub releases for module artifacts |
| `ModuleInstaller` | Downloads, verifies, installs JARs to local Maven repo |
| `DependencyResolver` | Resolves transitive dependencies from POMs |

---

## 6. Data Flow

```text
SuiteBootstrap (via host)
         ↓
ModuleManager.install(ModuleCoordinate)
         ↓
Repository Abstraction (config.yml repositories[]):
  GitHubModuleResolver.findRelease(coord) → release info
  LocalModuleResolver.findRelease(coord) → local JAR
  HttpModuleResolver.findRelease(coord) → HTTP endpoint
  (tried in order, first success wins)
         ↓
ModuleInstaller.download(assetUrl) → JAR file
         ↓
Verify SHA256 (mandatory, asset .sha256 separate)
         ↓
Install to ~/.m2/repository
         ↓
ModuleLoader.load() (kernel) → loads installed module
         ↓
Module registered in SuiteHost
```

**Config** (`HostConfig.repositories`):
```yaml
repositories:
  - type: github
    owner: majhrs16-official
    repo: TextFormatter-Suite
  - type: local
    path: file:///path/to/local/repo
  - type: http
    url: https://example.com/maven
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `ModuleManager.install()` | `ModuleManager.java` | `SuiteBootstrap` (host) |
| `ModuleManager.resolveDependencies()` | `ModuleManager.java` | Bootstrap |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom repository | Implement `ModuleResolver` interface (Maven Central, etc.) |
| Custom verification | Extend `ModuleInstaller` with signature verification |
| Custom storage | Change local repository location |
| Custom manifest parsing | Extend `DependencyResolver` for non-Maven manifests |

---

---

## 9. Exploration Path

```
1. ModuleManager.java             → Main facade
2. GitHubModuleResolver.java      → GitHub API integration
3. ModuleInstaller.java           → JAR download & install
4. DependencyResolver.java        → Transitive deps
5. manager-api/ModuleLifecycle.java → Interface implemented
```

---

## 10. Related Modules

- [manager-api](../manager-api/README.md) — API being implemented
- [core-api](../core-api/README.md) — Base types
- [kernel](../kernel/README.md) — Local module loading
- [host](../host/README.md) — Integration point
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Consumers

---

## 11. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **M-07** | SHA-256 only integrity, not authenticity | `DefaultModuleLifecycle.verifySignature()` — supports cosign/gpg signatures; `getCurrentEnvironment()` now dynamically detects platform (Spigot/Fabric/Velocity/Bungee) and MC version |