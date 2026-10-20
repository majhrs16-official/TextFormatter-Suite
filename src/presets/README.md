# presets — Transform Preset Catalog

> **Purpose**: Catalogs built-in transform presets, imports/exports preset YAML, and exposes the preset data model (`Preset`, `PresetChannel`, `PresetConfig`, `PresetTranslator`, `PresetSync`).

---

## 1. Responsibilities

- **Built-in catalog** — Standard, RPG, Staff and Minimal preset definitions
- **Preset management** — `PresetManager` registers, retrieves and exports presets
- **YAML import/export** — reads/writes preset files with SnakeYAML `SafeConstructor`
- **Module descriptor** — `PresetsModule` declares the `presets` capability

---

## 2. Non-Responsibilities

- **No transform execution** — there is no `TransformEngine`; `Preset.apply(...)` is an empty placeholder
- **No policy evaluation** — routing, conditions and actions belong to the iFlow VM (`.flow`)
- **No inline expressions** — SpEL and `PresetRule` conditions were removed from the repository
- **No message routing** — `iflow` handles routing
- **No formatting** — delegates to `textformatter`
- **No platform-specific code** — pure Java/YAML

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `Capability`, `SemVer` |
| `textformatter` | Compile | `Channel` model used by preset channels |
| `host` | Compile | `HostConfig` model for preset config |
| `snakeyaml` | Compile | YAML parsing with `SafeConstructor` |
| `kernel` | Test | Test fixtures |
| `junit-jupiter` | Test | Unit tests |

---

## 4. Consumers

No production consumer is wired yet. `host`, `spigot-host` and `fabric-host` do not load presets at runtime; the module is built and tested in isolation.

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `PresetManager` | Registry, built-in definitions, YAML import/export |
| `Preset` | Root preset model + nested `PresetConfig`, `PresetChannel`, `PresetTranslator`, `PresetSync` |
| `PresetsModule` | `Module` descriptor; provides the `presets` capability |

---

## 6. Data Flow

```text
Preset YAML ──→ PresetManager.importFromYaml() ──→ Preset model
Preset model ──→ PresetManager.exportToYaml() ──→ Preset YAML
Built-in definitions ──→ PresetManager.getPreset(id)
```

There is no `Preset → message` execution path. Any future execution must be implemented through iFlow VM programs, not by reintroducing an inline expression engine.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `PresetManager.importFromYaml()` | `PresetManager.java` | Tests / future host integration |
| `PresetManager.exportToYaml()` | `PresetManager.java` | Tests / future host integration |
| `PresetsModule.descriptor()` | `PresetsModule.java` | Kernel (when registered) |

---

## 8. Limitations

- `Preset.apply(...)` does nothing; presets are currently data/catalog only.
- The `PresetsModule` descriptor exists, but the module has no `META-INF/services` registration and is not loaded by `SuiteBootstrap`.
- Preset rules with SpEL conditions/actions were removed with the rest of the inline expression engine.

---

## 9. Exploration Path

```
1. PresetManager.java   → Built-in presets, import/export
2. Preset.java          → Data model and nested value objects
3. PresetsModule.java   → Module descriptor/capability
```

---

## 10. Related Modules

- [textformatter](../textformatter/README.md) — Channel model and formatting pipeline
- [host](../host/README.md) — Shared configuration types
- [iflow](../iflow/README.md) — VM policies and routing
