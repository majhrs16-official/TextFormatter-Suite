# gtranslate — Google Translate Provider

> **Purpose**: Implements `TranslatorProvider` SPI from `core-api` for Google Translate. **Clean Architecture**: discovered at runtime via `ServiceLoader` — `host` has no compile-time dependency on this module.

---

## 1. Responsibilities

- **Google Translate integration** — HTTP calls to Google Translation API (free web endpoint)
- **TranslatorProvider implementation** — provides `Translator` instances via `ServiceLoader`
- **Translator implementation** — `GTranslate` implements `Translator` interface
- **Module registration** — `GTranslateModule` registers `TranslatorProvider` SPI
- **Configuration** — reads API key, endpoint from `TranslatorsConfig` (via `host`)
- **char[] token handling** — API key stored in `char[]` + `Arrays.fill('\0')` after use

---

## 2. Non-Responsibilities

- **No fallback logic** — `TranslatorManager` (core-api) handles fallback chains
- **No language detection** — Google API handles auto-detection
- **No caching** — handled at `TranslatorManager` level
- **No platform-specific code** — pure Java HTTP client
- **No direct `host` dependency** — discovered via SPI at runtime

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `TranslatorProvider`, `Translator`, `TranslatorManager`, `TranslationException` |
| `transport` | Compile | `HttpTransport`, `MessageCodec` for HTTP calls |
| `org.json` | Compile | JSON request/response parsing |
| `kernel` | Test | Test fixtures |

> **Clean Architecture**: `host` no longer depends on `gtranslate` at compile-time. Tests use `testImplementation` to make provider available to ServiceLoader during test execution.

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `GTranslate` | Main class: `translate(text, source, target) → translated text` |
| `GTranslateProvider` | Implements `TranslatorProvider`: `getTranslator() → GTranslate` |
| `GTranslateModule` | `Module` implementation: registers `GTranslateProvider` and `TranslationService` |

---

## 6. Data Flow

```text
TranslationService.translate(text, targetLang)
         ↓
GTranslateProvider.getTranslator()
         ↓
GTranslate.translate(text, sourceLang, targetLang)
         ↓
HttpTransport.post(Google API endpoint, JSON body)
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
  google:
    api-key: "xxx"
    endpoint: "https://translation.googleapis.com/language/translate/v2"
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `GTranslateModule` | `GTranslateModule.java` | `ModuleLoader` → `SuiteBootstrap` |
| `GTranslateProvider.getTranslator()` | `GTranslateProvider.java` | `TranslatorManager` (core-api) |
| `GTranslate.translate()` | `GTranslate.java` | `TranslationService` impl |

---

## 8. Extension Points

- **Custom HTTP transport** — replace `HttpTransport` implementation (via `transport` module)
- **Custom request/response codec** — extend `MessageCodec`
- **Additional Google API features** — extend `GTranslate` (glossary, model selection, etc.)

---

## 9. Exploration Path

```
1. GTranslateModule.java        → Module registration
2. GTranslateProvider.java      → TranslatorProvider implementation
3. GTranslate.java              → Core translation logic
4. core-api: TranslationService.java → SPI being implemented
5. core-api: TranslatorProvider.java → SPI being implemented
6. transport: HttpTransport.java → HTTP client used
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Defines `TranslatorProvider`, `TranslationService` SPIs
- [transport](../transport/README.md) — HTTP transport abstraction
- [host](../host/README.md) — Wires translation providers, loads config
- [ltranslate](../ltranslate/README.md) — Alternative provider (LibreTranslate)
- [kernel](../kernel/README.md) — Loads `GTranslateModule`