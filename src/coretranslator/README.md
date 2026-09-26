# coretranslator — Legacy Translation Bridge

> **Purpose**: Provides a bridge from legacy translation systems to the current `TranslationService` SPI.

---

## 1. Responsibilities

- **Legacy bridge** — `LegacyBridge` adapts old translation API to new `TranslationService`
- **Module registration** — `CoreTranslatorModule` registers bridge

---

## 2. Non-Responsibilities

- **No translation logic** — delegates to `gtranslate`/`ltranslate`
- **No new features** — transitional only

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `TranslationService` SPI |
| `kernel` | Compile | Module loading |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | Uses `LegacyBridge` for backward compatibility |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `LegacyBridge` | Adapts legacy `Translator` to `TranslationService` |
| `CoreTranslatorModule` | `Module` registering bridge |

---

## 6. Data Flow

```text
Legacy code calls LegacyBridge.translate()
         ↓
LegacyBridge delegates to TranslationService (gtranslate/ltranslate)
         ↓
Returns translated text
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `CoreTranslatorModule` | `CoreTranslatorModule.java` | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Exploration Path

```
1. LegacyBridge.java              → Bridge implementation
2. CoreTranslatorModule.java      → Module registration
3. core-api: TranslationService.java → Target SPI
```

---

## 9. Related Modules

- [core-api](../core-api/README.md) — `TranslationService` SPI
- [gtranslate](../gtranslate/README.md) / [ltranslate](../ltranslate/README.md) — Actual providers
- [host](../host/README.md) — Uses bridge
- [kernel](../kernel/README.md) — Loads module