# ltranslate — LibreTranslate Provider

> **Purpose**: Implements `TranslatorProvider` from `core-api` for LibreTranslate (self-hosted or cloud). The package publishes its provider through the module `ServiceRegistry`; `chattranslator` consumes it without importing this module.

---

## 1. Responsibilities

- **LibreTranslate integration** — HTTP calls to LibreTranslate API (self-hosted or public)
- **TranslatorProvider implementation** — provides `Translator` instances
- **Translator implementation** — `LTranslate` implements `Translator` interface
- **Runtime registration** — `LTranslateActivator` publishes `TranslatorProvider` in the module `ServiceRegistry`; `LTranslateModule` remains the legacy kernel descriptor
- **Configuration** — `chattranslator` reads `translators/*.yml` and passes `base-url`/`api-key` to `LTranslateProvider.create(...)`
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
| `transport` | Compile + `DEPENDENCY` package | `HttpTransport`, `MessageCodec` for HTTP calls; the manager loads `transport.jar` from `library/dependencies` |
| `org.json` | Compile | JSON request/response parsing (external library; final packaging is F8) |
| `kernel` | Test | Test fixtures |

> **Runtime**: the package declares `Dependencies: transport@>=2.1.0` and
> `LTranslateActivator` registers the provider. The host's legacy compile-time
> wiring is removed in F6/F4b.

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `LTranslate` | Main class: `translate(text, source, target) → translated text` |
| `LTranslateProvider` | Implements `TranslatorProvider`: creates a configured `LTranslate` |
| `LTranslateActivator` | Runtime entry point: registers the provider in the module `ServiceRegistry` |
| `LTranslateModule` | Legacy `Module` implementation for kernel-based loaders |

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
| `LTranslateActivator` | `LTranslateActivator.java` | `ModuleHost` → `PackageRuntime` |
| `LTranslateModule` | `LTranslateModule.java` | Legacy `ModuleLoader` → `SuiteBootstrap` |
| `LTranslateProvider.create()` | `LTranslateProvider.java` | `BackendConfig` (chattranslator) |
| `LTranslate.translate()` | `LTranslate.java` | `TranslationService` impl |

---

## 8. Extension Points

- **Custom HTTP transport** — replace `HttpTransport` implementation (via `transport` module)
- **Custom request/response codec** — extend `MessageCodec`
- **Additional LibreTranslate features** — extend `LTranslate` (detection, batch, etc.)

---

## 9. Exploration Path

```
1. LTranslateActivator.java     → Runtime registration
2. LTranslateProvider.java      → TranslatorProvider implementation
3. LTranslate.java              → Core translation logic
4. core-api: TranslatorProvider.java → SPI being implemented
5. chattranslator: BackendConfig.java → Reads translators/*.yml and creates providers
6. transport: HttpTransport.java → HTTP client used
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Defines `TranslatorProvider`, `TranslationService` SPIs
- [transport](../transport/README.md) — HTTP transport dependency package
- [chattranslator](../chattranslator/README.md) — Consumes the provider and loads `translators/*.yml`
- [gtranslate](../gtranslate/README.md) — Alternative provider (Google)
- [kernel](../kernel/README.md) — Legacy `ModuleLoader` path

---

## 11. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **M-02** | Translation thundering herd | `TranslationService` (core-api) — in-flight deduplication via `CompletableFuture` cache per `(text, from, to)` |
| **SEC** | API key in `char[]` + `Arrays.fill('\0')` | `LTranslate` constructor stores key in `char[]`, zeroes after use |