# ltranslate — LibreTranslate Provider

> **Purpose**: Implements `TranslatorProvider` and `TranslationService` SPIs from `core-api` using LibreTranslate API (self-hosted or cloud).

---

## 1. Responsibilities

- **LibreTranslate integration** — HTTP calls to LibreTranslate API
- **TranslatorProvider implementation** — provides `Translator` instances
- **TranslationService implementation** — high-level `translate(text, targetLang)` entry point
- **Module registration** — `LTranslateModule` registers provider/service
- **Configuration** — reads endpoint, API key from `TranslatorsConfig` (via `host`)

---

## 2. Non-Responsibilities

- **No fallback logic** — `TranslatorManager` (core-api) handles fallback chains
- **No language detection** — LibreTranslate handles auto-detection
- **No caching** — could be added at `TranslatorManager` level
- **No platform-specific code** — pure Java HTTP client

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `TranslatorProvider`, `TranslationService`, `TranslationException` |
| `transport` | Compile | `HttpTransport`, `MessageCodec` for HTTP calls |
| `org.json` | Compile | JSON request/response parsing |
| `kernel` | Test | Test fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | Wires `LTranslateProvider` via `LTranslateModule` |
| `spigot-host` | Uses translation via `host` |
| `fabric-host` | Uses translation via `host` |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `LTranslate` | Main class: `translate(text, source, target) → translated text` |
| `LTranslateProvider` | Implements `TranslatorProvider`: `getTranslator() → LTranslate` |
| `LTranslateModule` | `Module` implementation: registers `LTranslateProvider` and `TranslationService` |

---

## 6. Data Flow

```text
TranslationService.translate(text, targetLang)
         ↓
LTranslateProvider.getTranslator()
         ↓
LTranslate.translate(text, sourceLang, targetLang)
         ↓
HttpTransport.post(LibreTranslate endpoint, JSON body)
         ↓
MessageCodec.encode/decode request/response
         ↓
Parse JSON response → translated text
         ↓
Return translated text
```

**Config** (from `host` `TranslatorsConfig`):
```yaml
translators:
  libretranslate:
    endpoint: "https://libretranslate.de/translate"
    api-key: "optional"  # if self-hosted with auth
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `LTranslateModule` | `LTranslateModule.java` | `ModuleLoader` → `SuiteBootstrap` |
| `LTranslateProvider.getTranslator()` | `LTranslateProvider.java` | `TranslatorManager` (core-api) |
| `LTranslate.translate()` | `LTranslate.java` | `TranslationService` impl |

---

## 8. Extension Points

- **Custom HTTP transport** — replace `HttpTransport` implementation (via `transport` module)
- **Custom request/response codec** — extend `MessageCodec`
- **Additional LibreTranslate features** — extend `LTranslate` (detection, batch, etc.)

---

## 9. Exploration Path

```
1. LTranslateModule.java        → Module registration
2. LTranslateProvider.java      → TranslatorProvider implementation
3. LTranslate.java              → Core translation logic
4. core-api: TranslationService.java → SPI being implemented
5. core-api: TranslatorProvider.java → SPI being implemented
6. transport: HttpTransport.java → HTTP client used
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Defines `TranslatorProvider`, `TranslationService` SPIs
- [transport](../transport/README.md) — HTTP transport abstraction
- [host](../host/README.md) — Wires translation providers, loads config
- [gtranslate](../gtranslate/README.md) — Alternative provider (Google)
- [kernel](../kernel/README.md) — Loads `LTranslateModule`