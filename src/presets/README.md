# presets — Transform Preset Management

> **Purpose**: Manages YAML-based transform presets that define formatting rules, channel mappings, and transform pipelines.

---

## 1. Responsibilities

- **Preset loading** — loads presets from YAML files
- **Preset management** — `PresetManager` registers, retrieves, applies presets
- **Transform engine** — `TransformEngine` executes preset transforms
- **Module registration** — `PresetsModule` registers manager/engine

---

## 2. Non-Responsibilities

- **No message routing** — `iflow` handles routing
- **No formatting** — delegates to `textformatter`
- **No platform-specific code** — pure Java/YAML

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Module`, `Message`, `Channel` |
| `textformatter` | Compile | `TextFormatter`, `ChannelRegistry` |
| `iflow` | Compile | `Router`, `Rule` for preset transforms |
| `host` | Compile | `HostConfig` for preset config |
| `spring-expression` | Compile | SpEL in preset definitions |
| `spring-beans` | Compile | Bean utilities |
| `snakeyaml` | Compile | YAML parsing |
| `kernel` | Test | Test fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Preset-based formatting |
| `fabric-host` | Preset-based formatting |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `Preset` | Preset model: id, name, format, transforms, conditions |
| `PresetManager` | Registry: `loadPresets()`, `getPreset(id)`, `applyPreset(message, preset)` |
| `TransformEngine` | Executes preset transforms using `TextFormatter` and `Router` |
| `PresetsModule` | `Module` registering manager and engine |

---

## 6. Data Flow

```text
PresetManager.loadPresets() → reads YAML from config directory
         ↓
Preset objects registered by ID
         ↓
TransformEngine.applyPreset(message, presetId)
         ↓
For each transform in preset:
  - TextFormatter.format() for format changes
  - Router.route() for routing transforms
  - TranslationService for translation transforms
         ↓
Transformed message returned
```

**Config** (`HostConfig.presets`):
```yaml
presets:
  - id: "default"
    name: "Default Format"
    format: "<gold>[<channel>] <white><message>"
    transforms:
      - type: "UPPERCASE"
        condition: "message.contains('urgent')"
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `PresetsModule` | `PresetsModule.java` | `ModuleLoader` → `SuiteBootstrap` |
| `PresetManager.applyPreset()` | `PresetManager.java` | `MessageDispatcher` or commands |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom transform types | Extend `TransformEngine` with new transform handlers |
| Preset storage | Replace YAML loading with DB/API in `PresetManager` |
| Preset conditions | Add SpEL functions in `ScriptSurface` (iflow) |

---

## 9. Exploration Path

```
1. PresetsModule.java             → Module registration
2. PresetManager.java             → Preset registry & loading
3. Preset.java                    → Preset model
4. TransformEngine.java           → Transform execution
5. host/HostConfig.java           → Preset config structure
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Module SPI, Message, Channel
- [textformatter](../textformatter/README.md) — Formatting engine
- [iflow](../iflow/README.md) — Routing for transforms
- [host](../host/README.md) — Config loading
- [kernel](../kernel/README.md) — Loads module