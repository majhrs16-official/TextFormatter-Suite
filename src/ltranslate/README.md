# ltranslate — LibreTranslate Provider

> **Purpose**: Implements `TranslatorProvider` SPI from `core-api` for LibreTranslate (self-hosted or cloud). **Clean Architecture**: discovered at runtime via `ServiceLoader` — `host` has no compile-time dependency on this module.

---

## 1. Responsibilities

- **LibreTranslate integration** — HTTP calls to LibreTranslate API (self-hosted or public)
- **TranslatorProvider implementation** — provides `Translator` instances via `ServiceLoader`
- **Translator implementation** — `LTranslate` implements `Translator` interface
- **Module registration** — `LTranslateModule` registers `TranslatorProvider` SPI
- **Configuration** — reads endpoint, API key from `TranslatorsConfig` (via `host`)
- **char[] token handling** — API key stored in `char[]` + `Arrays.fill('\0')` after use

---

## 2. Non-Responsibilities

- **No fallback logic** — `TranslatorManager` (core-api) handles fallback chains
- **No language detection** — LibreTranslate handles auto-detection
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

> **Clean Architecture**: `host` no longer depends on `ltranslate` at compile-time. Tests use `testImplementation` to make provider available to ServiceLoader during test execution.

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