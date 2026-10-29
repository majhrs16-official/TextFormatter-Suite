# presets — Transform Preset Catalog

> **Purpose**: Catalogs built-in transform presets, imports/exports preset YAML, and applies a preset through the neutral `PresetTarget` port (`Preset`, `PresetChannel`, `PresetConfig`, `PresetTranslator`, `PresetSync`).

---

## 1. Responsibilities

- **Built-in catalog** — Standard, RPG, Staff and Minimal preset definitions
- **Preset management** — `PresetManager` registers, retrieves and exports presets
- **YAML import/export** — reads/writes preset files with SnakeYAML `SafeConstructor`
- **Preset application** — `Preset.apply(PresetTarget)` converts every preset entry to neutral values and calls the target
- **Module descriptor** — `PresetsModule` declares the `presets` capability

---

## 2. Non-Responsibilities

- **No file/platform persistence** — `PresetTarget` is implemented by the host adapter; this module does not know the config layout
- **No policy evaluation** — routing, conditions and actions belong to the iFlow VM (`.flow`)
- **No inline expressions** — SpEL and `PresetRule` conditions were removed from the repository
- **No message routing** — `iflow-vm` handles routing
- **No formatting** — delegates to `textformatter`
- **No platform-specific code** — pure Java/YAML

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Channel`, `Formats`, `Language`, `SoundSpec` |
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
| `PresetManager` | Registry, built-in definitions, YAML import/export, `apply(id, target)` |
| `Preset` | Root preset model + nested `PresetConfig`, `PresetChannel`, `PresetTranslator`, `PresetSync`; `apply(PresetTarget)` |
| `PresetTarget` | Neutral port implemented by a host adapter (persist config, channels, translators, sync) |
| `PresetsModule` | `Module` descriptor; provides the `presets` capability |

---

## 6. Data Flow

```text
Preset YAML ──→ PresetManager.importFromYaml() ──→ Preset model
Preset model ──→ PresetManager.exportToYaml() ──→ Preset YAML
Built-in definitions ──→ PresetManager.getPreset(id)
Preset ──→ Preset.apply(PresetTarget) ──→ config + channels + translators + sync
```

`Preset.apply` is a real application algorithm, not a placeholder. The host
adapter implements `PresetTarget` and decides how to persist each part. There
is no inline expression engine; runtime routing remains in iFlow VM programs.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `PresetManager.importFromYaml()` | `PresetManager.java` | Tests / future host integration |
| `PresetManager.exportToYaml()` | `PresetManager.java` | Tests / future host integration |
| `PresetManager.apply(id, target)` | `PresetManager.java` | Host adapter after the user selects a preset |
| `PresetsModule.descriptor()` | `PresetsModule.java` | Kernel (when registered) |

---

## 8. Limitations

- `Preset.apply(...)` is implemented, but no production `PresetTarget` adapter is wired yet; packaging as a suite module waits for that adapter (F4b).
- The `PresetsModule` descriptor exists, but the module has no `META-INF/services` registration and is not loaded by `SuiteBootstrap`.
- Preset rules with SpEL conditions/actions were removed with the rest of the inline expression engine.

---

## 9. Exploration Path

```
1. PresetManager.java   → Built-in presets, import/export, apply
2. Preset.java          → Data model, nested values and apply algorithm
3. PresetTarget.java    → Neutral application port
4. PresetsModule.java   → Module descriptor/capability
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `Channel`, `Formats`, `Language`, `SoundSpec`
- [textformatter](../textformatter/README.md) — Channel formatting pipeline
- [host](../host/README.md) — Host adapter that will implement `PresetTarget`
- [iflow](../iflow/README.md) — VM policies and routing
