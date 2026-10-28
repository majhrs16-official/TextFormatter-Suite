# gtranslate — Google Translate Provider

> **Purpose**: Implements `TranslatorProvider` from `core-api` for Google Translate. The package publishes its provider through the module `ServiceRegistry`; `chattranslator` consumes it without importing this module.

---

## 1. Responsibilities

- **Google Translate integration** — HTTP calls to Google Translation API (free web endpoint)
- **TranslatorProvider implementation** — provides `Translator` instances
- **Translator implementation** — `GTranslate` implements `Translator` interface
- **Runtime registration** — `GTranslateActivator` publishes `TranslatorProvider` in the module `ServiceRegistry`; `GTranslateModule` remains the legacy kernel descriptor
- **Configuration** — `chattranslator` reads `translators/*.yml` and passes the provider settings to `GTranslateProvider.create(...)`
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
| `transport` | Compile + `DEPENDENCY` package | `HttpTransport`, `MessageCodec` for HTTP calls; the manager loads `transport.jar` from `library/dependencies` |
| `org.json` | Compile | JSON request/response parsing (external library; final packaging is F8) |
| `kernel` | Test | Test fixtures |

> **Runtime**: the package declares `Dependencies: transport@>=2.1.0` and
> `GTranslateActivator` registers the provider. The host's legacy compile-time
> wiring is removed in F6/F4b.

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `GTranslate` | Main class: `translate(text, source, target) → translated text` |
| `GTranslateProvider` | Implements `TranslatorProvider`: creates a configured `GTranslate` |
| `GTranslateActivator` | Runtime entry point: registers the provider in the module `ServiceRegistry` |
| `GTranslateModule` | Legacy `Module` implementation for kernel-based loaders |

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
| `GTranslateActivator` | `GTranslateActivator.java` | `ModuleHost` → `PackageRuntime` |
| `GTranslateModule` | `GTranslateModule.java` | Legacy `ModuleLoader` → `SuiteBootstrap` |
| `GTranslateProvider.create()` | `GTranslateProvider.java` | `BackendConfig` (chattranslator) |
| `GTranslate.translate()` | `GTranslate.java` | `TranslationService` impl |

---

## 8. Extension Points

- **Custom HTTP transport** — replace `HttpTransport` implementation (via `transport` module)
- **Custom request/response codec** — extend `MessageCodec`
- **Additional Google API features** — extend `GTranslate` (glossary, model selection, etc.)

---

## 9. Exploration Path

```
1. GTranslateActivator.java     → Runtime registration
2. GTranslateProvider.java      → TranslatorProvider implementation
3. GTranslate.java              → Core translation logic
4. core-api: TranslatorProvider.java → SPI being implemented
5. chattranslator: BackendConfig.java → Reads translators/*.yml and creates providers
6. transport: HttpTransport.java → HTTP client used
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Defines `TranslatorProvider`, `TranslationService` SPIs
- [transport](../transport/README.md) — HTTP transport dependency package
- [chattranslator](../chattranslator/README.md) — Consumes the provider and loads `translators/*.yml`
- [ltranslate](../ltranslate/README.md) — Alternative provider (LibreTranslate)
- [kernel](../kernel/README.md) — Legacy `ModuleLoader` path

---

## 11. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **M-01** | GTranslate only returned first sentence | `GTranslate.translate()` — concatenates all `root[0][i][0]` segments from API response |
| **M-02** | Translation thundering herd | `TranslationService` (core-api) — in-flight deduplication via `CompletableFuture` cache per `(text, from, to)` |
| **SEC** | API key in `char[]` + `Arrays.fill('\0')` | `GTranslate` constructor stores key in `char[]`, zeroes after use |