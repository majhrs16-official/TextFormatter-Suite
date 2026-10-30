# presets — Transform Preset Catalog

> **Purpose**: Catalogs built-in transform presets, imports/exports preset YAML, applies a preset through the neutral `PresetTarget` port, and persists it in the canonical suite configuration layout through `FilePresetTarget`.

---

## 1. Responsibilities

- **Built-in catalog** — Standard, RPG, Staff and Minimal preset definitions
- **Preset management** — `PresetManager` registers, retrieves, imports, exports and applies presets
- **YAML import/export** — reads/writes preset files with SnakeYAML `SafeConstructor`
- **Preset application** — `Preset.apply(PresetTarget)` converts every preset entry to neutral values and calls the target
- **Canonical file target** — `FilePresetTarget` writes suite settings, channels, translators and sync configuration, merging with existing YAML instead of replacing unrelated keys
- **Runtime activation** — `PresetsActivator` creates the catalog in the module config directory and publishes `PresetManager` as a scoped service
- **Module descriptor** — `PresetsModule` declares the `presets` capability

---

## 2. Non-Responsibilities

- **No host reload** — `FilePresetTarget` persists files but does not reload the affected modules; the caller must reload after applying a preset
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
| `snakeyaml` | Compile | YAML parsing/writing with `SafeConstructor` |
| `kernel` | Test | Test fixtures |
| `junit-jupiter` | Test | Unit tests |

The distribution is a `PACKAGE` with no package dependencies; `core-api` is provided by the runtime host.

---

## 4. Consumers

`suite-runtime` installs the distribution ZIP and activates `PresetsActivator`. The activator publishes both `PresetManager` and the neutral `PresetService` port; the latter selects a preset, calls `PresetManager.apply(id, new FilePresetTarget(suiteDir))`, and only after success invokes the runtime reload callback. The host resolves `PresetService` from the service registry, so it does not import the presets package. Command wiring for selecting a preset belongs to F4d/F4e.

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `PresetManager` | Registry, built-in definitions, YAML import/export, `apply(id, target)` |
| `Preset` | Root preset model + nested `PresetConfig`, `PresetChannel`, `PresetTranslator`, `PresetSync`; `apply(PresetTarget)` |
| `PresetTarget` | Neutral port implemented by a persistence adapter |
| `FilePresetTarget` | Real file-backed target for the canonical `config/...` layout |
| `PresetApplyException` | Explicit failure when a preset cannot be persisted or contains an unsupported sync type |
| `DefaultPresetService` | Implements `api.spi.PresetService`; applies the catalog through `FilePresetTarget` and reloads only after success |
| `PresetsActivator` | `ModuleActivator`; creates the catalog and registers `PresetManager` plus `PresetService` |
| `PresetsModule` | `Module` descriptor; provides the `presets` capability |

---

## 6. Data Flow

```text
Preset YAML ──→ PresetManager.importFromYaml() ──→ Preset model
Preset model ──→ PresetManager.exportToYaml() ──→ Preset YAML
Built-in definitions ──→ PresetManager.getPreset(id)
Preset ──→ Preset.apply(PresetTarget) ──→ config + channels + translators + sync
FilePresetTarget ──→ config/suite/config.yml
                 ──→ config/textformatter/channels/<channel>.yml
                 ──→ config/chattranslator/translators/<provider>.yml
                 ──→ config/sync-discord|sync-telegram|sync-http|sync-socket|sync-velocity-core/...
```

`Preset.apply` is a real application algorithm, not a placeholder. `FilePresetTarget` merges each patch into existing YAML, preserves unrelated keys and writes atomically. There is no inline expression engine; runtime routing remains in iFlow VM programs.

Channel templates are persisted from `Formats.formatList()` (falling back to `textList()`), so the formatter consumes them as formats, not as literal texts.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `PresetManager.importFromYaml()` | `PresetManager.java` | Tests / host preset commands |
| `PresetManager.exportToYaml()` | `PresetManager.java` | Tests / host preset commands |
| `PresetManager.apply(id, target)` | `PresetManager.java` | Host adapter after the user selects a preset |
| `FilePresetTarget` | `FilePresetTarget.java` | Host adapter or tests |
| `PresetService` | `DefaultPresetService.java` | Host resolves it from `ServiceRegistry` after activation |
| `PresetsActivator.start()` | `PresetsActivator.java` | `ModuleHost` when the package activates |
| `PresetsModule.descriptor()` | `PresetsModule.java` | Kernel SPI discovery |

---

## 8. Limitations

- `DefaultPresetService` applies through `PresetManager.apply` and reloads modules only after success; the package itself is installed and activated by `suite-runtime`.
- `FilePresetTarget` writes the canonical configuration files but deliberately does not reload the host.
- Preset rules with SpEL conditions/actions were removed with the rest of the inline expression engine.
- `presets` has no platform commands yet; command registration belongs to the host adapter.

---

## 9. Tests

`./gradlew :src:presets:test` covers:

- Built-in catalog, import/export and `apply` with a fake target
- `FilePresetTarget` for config, channels, translators and every supported sync type
- Merge semantics that preserve unrelated YAML keys
- Explicit rejection of unknown sync types
- Channel templates written as formats
- `PresetsActivator` publishing `PresetManager` and `PresetService` in the module scope
- `DefaultPresetService` applying a preset through `FilePresetTarget` and reloading only after success
- Real distribution packaging through `modulePackage`

---

## 10. Exploration Path

```
1. PresetManager.java      → Built-in presets, import/export, apply
2. Preset.java             → Data model, nested values and apply algorithm
3. PresetTarget.java       → Neutral application port
4. FilePresetTarget.java   → Canonical file persistence
5. PresetsActivator.java   → Runtime activation and service registration
6. PresetsModule.java      → Module descriptor/capability
```

---

## 11. Related Modules

- [core-api](../core-api/README.md) — `Channel`, `Formats`, `Language`, `SoundSpec`
- [textformatter](../textformatter/README.md) — Channel formatting pipeline
- [chattranslator](../chattranslator/README.md) — Translator backends persisted by presets
- [spigot-host](../spigot-host/README.md) / [fabric-host](../fabric-host/README.md) — Future adapter that selects a preset and reloads modules
- [iflow-vm](../iflow-vm/README.md) — VM policies and routing
