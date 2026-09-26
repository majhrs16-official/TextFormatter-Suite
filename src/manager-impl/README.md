# manager-impl — Module Manager Implementation

> **Purpose**: Implements `manager-api` for remote module loading from GitHub. Resolves, downloads, and installs modules at runtime.

---

## 1. Responsibilities

- **GitHub module resolution** — `GitHubModuleResolver` finds modules in GitHub releases
- **Module installation** — downloads JARs, verifies, installs to local repo
- **Dependency resolution** — resolves transitive dependencies
- **Lifecycle management** — `ModuleManager` implements `ModuleLifecycle`
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
GitHubModuleResolver.findRelease(coord) → release info
         ↓
ModuleInstaller.download(assetUrl) → JAR file
         ↓
Verify checksum, install to ~/.m2/repository
         ↓
ModuleLoader.load() (kernel) → loads installed module
         ↓
Module registered in SuiteHost
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
| Custom repository | Implement custom `ModuleResolver` (Maven Central, etc.) |
| Custom verification | Extend `ModuleInstaller` with signature verification |
| Custom storage | Change local repository location |

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