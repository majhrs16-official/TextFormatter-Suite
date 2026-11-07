# Translation System

## Overview

Translation is provided by `chattranslator` and the active `TranslatorProvider`
from the installed provider packages (`gtranslate`, `ltranslate`, …). The host
asks `TranslationService` for a translated fragment; the service caches results,
deduplicates in-flight requests and falls back to the original text on failure.

## Providers

| Provider | Package | API key | Notes |
|----------|---------|---------|-------|
| Google Translate | `gtranslate` | No | Free public endpoint |
| LibreTranslate | `ltranslate` | Optional | Self-hosted or public instance |

Only the **active** provider registered in `TranslatorManager` is used. The
console command `/suite state` shows the module state; the backend selection is
stored in `config/chattranslator/config.yml`.

## Configuration

### Facade defaults (`config/chattranslator/config.yml`)

```yaml
default-backend: google
context-override-enabled: true
config-version: 1.0
```

`default-backend` is the preferred provider. If it is unavailable, the first
active provider registered by a provider package is used.

### Google (`config/chattranslator/translators/google.yml`)

```yaml
provider: google
active: true
config-version: 1.0
```

### LibreTranslate (`config/chattranslator/translators/libre.yml`)

```yaml
provider: libre
active: false
base-url: https://libretranslate.example.com
api-key: ""
config-version: 1.0
```

The flat `translators/` directory in the data root is ignored. Only
`config/chattranslator/translators/` is read. A translator file without
`config-version: 1.0` is rejected (the activator reports the error).

## Languages

`Language` is an ISO 639-1/639-3 enum with the special values:

| Code | Meaning |
|------|---------|
| `auto` | Detect the source language |
| `off` | Do not translate; pass the original text through |

`TranslationService` compares source and target; if they are equal, translation
is skipped and the original text is returned. Detection failures also fall back
to the original text.

## Player language preferences

The neutral `UserLanguageStore` contract persists a player's preferred language.
The integrated runtime provides `YamlUserLanguageStore`, backed by
`storage.yml` in the data folder:

```yaml
# storage.yml (flat UUID -> value)
"00000000-0000-0000-0000-000000000000": es
"11111111-1111-1111-1111-111111111111": auto
```

The store is hot-reloaded when the file timestamp changes. The current core does
not register `/suite lang` or `/suite toggle`; adapters and extensions use the
`UserLanguageStore` service directly.

## Channel language settings

Each channel declares its language pair:

```yaml
# config/textformatter/channels/chat.global.yml
name: chat.global
lang-source: auto
lang-target: auto
messages:
  - "<green>%content%</green>"
config-version: 1.0
```

| Setting | Behavior |
|---------|----------|
| `lang-source: auto` | Detect the source language |
| `lang-source: en` | Force English as the source |
| `lang-target: auto` | Translate to the recipient's language |
| `lang-target: es` | Force Spanish as the target |

There is no per-channel `translate` boolean. Translation is applied by the
pipeline when the recipient language differs from the source; an unavailable
provider returns the original text.

## Translation stage

Templates do not carry translation tags. The `translate` stage of
`chattranslator` runs per recipient, after `route` and before `format`, and
translates the raw `Message.texts()` entries when the recipient language
differs from the source. The formatter then renders the already-translated
text, so `%content%` needs no wrapper:

```yaml
messages:
  - "Welcome %player_name% to the server!"
```

Translation is skipped explicitly when the message disables it, the source and
target languages are equal or the target is `auto`, or no provider is
available; in those cases the original text is rendered unchanged.

## Cache and deduplication

`TranslationService` keeps:

- A translation cache keyed by `source|target|text`.
- A detection cache keyed by `detect|text`.
- In-flight maps that deduplicate concurrent requests for the same key.

The current implementation uses fixed safety limits (`10,000` translations,
`5,000` detections, `30 s` timeout). Cache entries are not persisted across
restarts. Provider timeouts and failures return the source text, so a message is
never dropped because translation failed.

## Tests

```bash
./gradlew :src:core-api:test --tests '*TranslationService*' --no-daemon
./gradlew :src:chattranslator:test --no-daemon
./gradlew :src:gtranslate:test :src:ltranslate:test --no-daemon
```

The tests cover cache hit/miss, size limits, per-language isolation,
deduplication, detection caching, provider failures, malformed responses and
unicode input.

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| Translation not working | No active provider | Set `active: true` in a provider file and install its package |
| Wrong language | Unsupported or wrong code | Use a value accepted by `Language` |
| Slow translation | Provider latency / cache miss | Reduce provider latency; repeated text is cached in memory |
| Translation failed | Provider error or timeout | Check the log; the source text is returned |
| Auto-detect wrong | Very short text | Set an explicit `lang-source` |
| File ignored | Flat path | Move it to `config/chattranslator/translators/` |
