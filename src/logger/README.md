# logger — Logging Dependency Package

## Purpose

`logger` provides the structured logger and utilities shared by suite modules.
It is distributed as a `DEPENDENCY` package, not as a feature package: the
manager installs it under `manager/library/dependencies/logger.jar` and the
module classloader makes it visible to consumers such as `iflow-16` and
`iflow-32`.

## Runtime model

- Package type: `DEPENDENCY` (`modulePackage` produces the ZIP).
- Installed artifact: `manager/library/dependencies/logger.jar`.
- No `ModuleActivator`: dependency packages are not activated; they are only
  added to the classpath of the modules that declare them.
- Consumers declare the dependency in `METADATA/package.list`, for example
  `Dependencies: logger@>=1.0.0`.
- `LoggerModule` remains the legacy `Module` descriptor for kernel-based
  loaders; the package runtime does not activate it.

## Key classes

| Class | Responsibility |
|-------|----------------|
| `Logger` | Logging facade used by modules |
| `LoggerUtils` | Formatting and stack-trace helpers |
| `LoggerModule` | Legacy `Module` descriptor |

## Tests

`LoggerTest` and the logger utility tests cover formatting, levels and
stack-trace handling.

## Related modules

- [core-api](../core-api/README.md) — `PluginLogger` contract.
- [suite-runtime](../suite-runtime) — dependency resolution and classloading.
- [iflow-16](../iflow-16/README.md) — legacy 16-bit consumer of this dependency package.
- [iflow-32](../iflow-32/README.md) — 32-bit line that also consumes this dependency package.
