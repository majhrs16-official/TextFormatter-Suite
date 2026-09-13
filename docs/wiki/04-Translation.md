# Translation System

## Overview

TextFormatter Suite provides a flexible, multi-provider translation system with automatic language detection and per-player language preferences.

## Supported Providers

| Provider | API Key | Cost | Latency | Quality |
|----------|---------|------|---------|---------|
| Google Translate | No (free tier) | Free | Low | High |
| LibreTranslate | Optional | Free/Self-hosted | Medium | Good |

## Configuration

### Google Translate (Default)

```yaml
# translators/google.yml
provider: google
active: true
# No API key required (uses free web endpoint)
# Optional: pool settings for concurrent requests
pool:
  max-concurrent: 6
```

### LibreTranslate

```yaml
# translators/libre.yml
provider: libre
active: false
base-url: https://libretranslate.example.com  # Your instance URL
api-key: ""                                   # Optional API key
pool:
  max-concurrent: 6
```

### Provider Selection

The suite automatically selects the first active provider. Priority order:
1. Google (if active)
2. LibreTranslate (if active)

## Language Support

### Supported Languages

| Code | Language | Google | LibreTranslate |
|------|----------|--------|----------------|
| `en` | English | ✅ | ✅ |
| `es` | Spanish | ✅ | ✅ |
| `fr` | French | ✅ | ✅ |
| `de` | German | ✅ | ✅ |
| `pt` | Portuguese | ✅ | ✅ |
| `it` | Italian | ✅ | ✅ |
| `ru` | Russian | ✅ | ✅ |
| `ja` | Japanese | ✅ | ✅ |
| `ko` | Korean | ✅ | ✅ |
| `zh` | Chinese (Simplified) | ✅ | ✅ |
| `zh-TW` | Chinese (Traditional) | ✅ | ✅ |
| `ar` | Arabic | ✅ | ✅ |
| `hi` | Hindi | ✅ | ✅ |
| `pt` | Portuguese | ✅ | ✅ |
| `nl` | Dutch | ✅ | ✅ |
| `pl` | Polish | ✅ | ✅ |
| `tr` | Turkish | ✅ | ✅ |
| `vi` | Vietnamese | ✅ | ✅ |
| `th` | Thai | ✅ | ✅ |
| `id` | Indonesian | ✅ | ✅ |

Total: **100+ languages** supported

### Special Language Codes

| Code | Description |
|------|-------------|
| `auto` | Auto-detect source language |
| `off` | Disable translation for this player |

## Player Language Management

### Commands

```
/suite lang                    # Show your current language
/suite lang <code>             # Set your language (en, es, auto, off, etc.)
/suite lang <player> <code>    # Admin: set another player's language
/suite toggle                  # Toggle translation on/off
/suite toggle <player>         # Admin: toggle for another player
```

### Language Persistence

Player language preferences are stored in `storage.yml`:

```yaml
# storage.yml
players:
  "uuid-here":
    language: "es"          # or "auto", "off", "en", etc.
    updated: "2024-01-15T10:30:00Z"
```

### Language Codes

Use ISO 639-1 codes (2-letter) or ISO 639-1 + region:

| Code | Language | Region Variants |
|------|----------|-----------------|
| `en` | English | `en-US`, `en-GB`, `en-AU` |
| `es` | Spanish | `es-ES`, `es-MX`, `es-AR` |
| `fr` | French | `fr-FR`, `fr-CA` |
| `de` | German | `de-DE`, `de-AT` |
| `zh` | Chinese | `zh-CN`, `zh-TW`, `zh-HK` |
| `pt` | Portuguese | `pt-BR`, `pt-PT` |

### Special Language Codes

| Code | Behavior |
|------|----------|
| `auto` | Auto-detect source, translate to server default |
| `off` | Disable translation (show original text) |
| `<code>` | Force specific source→target |

## Translation in Channels

### Channel Language Configuration

```yaml
# channels/chat.global.yml
name: chat.global
lang-source: auto      # Source: auto, en, es, etc.
lang-target: auto      # Target: auto, en, es, etc.
translate: true        # Enable translation for this channel
```

### Per-Channel Translation

```yaml
# chat.global - Translated chat
lang-source: auto
lang-target: auto
translate: true

# staff.chat - No translation (staff reads original)
translate: false

# rp.chat - Force English source
lang-source: en
lang-target: auto
translate: true
```

### Translation Behavior

| Setting | Behavior |
|---------|----------|
| `translate: false` | No translation, show original |
| `translate: true` + `lang-source: auto` | Auto-detect, translate to recipient's language |
| `lang-source: en` | Force English as source |
| `lang-target: auto` | Translate to recipient's language |
| `lang-target: es` | Force Spanish output |

## Translation Tags

### Inline Translation Tags

Use `<tr>` tags to mark text for translation:

```yaml
messages:
  - "Welcome <tr>%player_name%</tr> to the server!"
  - "Server: <tr>Welcome to our server!</tr>"
```

### Tag Behavior

| Tag | Behavior |
|-----|----------|
| `<tr>text</tr>` | Mark text for translation |
| `<tr>` without `</tr>` | Translate until end or next tag |
| Nested tags | Not supported (flattened) |

### Placeholder Translation

Placeholders are translated based on recipient's language:

```yaml
messages:
  - "Welcome <tr>%player_name%</tr> to <tr>%server_name%</tr>!"
```

Result for Spanish player:
```
"¡Bienvenido Juan al Servidor Principal!"
```

## Translation Commands

### Player Commands

| Command | Description |
|---------|-------------|
| `/suite lang` | Show current language |
| `/suite lang auto` | Auto-detect |
| `/suite lang off` | Disable translation |
| `/suite lang es` | Set to Spanish |
| `/suite lang en` | Set to English |
| `/suite toggle` | Toggle on/off |

### Admin Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/suite lang <player> <code>` | Set player's language | `textformattersuite.admin` |
| `/suite toggle <player>` | Toggle player's translation | `textformattersuite.admin` |
| `/suite lang list` | List supported languages | `textformattersuite.admin` |

## Translation Providers Detail

### Google Translate

- **Endpoint**: `translate.googleapis.com` (free web endpoint)
- **Rate Limit**: ~100 requests/second (unofficial)
- **Format**: HTML-escaped text
- **Cache**: In-memory, 1-hour TTL
- **Fallback**: Returns original text on failure

### LibreTranslate

```yaml
# translators/libre.yml
provider: libre
active: true
base-url: https://libretranslate.com  # Or your self-hosted instance
api-key: "your-api-key"              # Optional
pool:
  max-concurrent: 6
  timeout-ms: 5000
```

### Self-hosted LibreTranslate

```yaml
# For self-hosted instance
base-url: https://translate.yourdomain.com
api-key: "your-api-key"
```

## Translation Cache

### Cache Configuration

```yaml
# In config.yml
translation:
  cache:
    enabled: true
    ttl-minutes: 60          # Cache TTL in minutes
    max-entries: 10000       # Max cache entries
```

### Cache Behavior

- **Key**: `source_text|source_lang|target_lang`
- **TTL**: 60 minutes default
- **Max Entries**: 10,000 entries
- **Eviction**: LRU when max reached

### Cache Commands

```
/suite translation cache stats     # Show cache stats
/suite translation cache clear     # Clear translation cache
/suite translation cache warm <file>  # Pre-warm from file
```

## Translation Quality

### Quality Comparison

| Aspect | Google | LibreTranslate |
|--------|--------|----------------|
| Accuracy | ★★★★★ | ★★★★☆ |
| Speed | Fast | Medium |
| Formality | Good | Good |
| Slang/Idioms | Excellent | Good |
| Technical Terms | Excellent | Good |

### Best Practices

1. **Use Google for production** - Best quality/speed balance
2. **Use LibreTranslate for privacy** - Self-hosted, no data leaves your server
3. **Enable cache** - Reduces API calls and latency
4. **Set appropriate TTL** - 60 min balances freshness/performance
5. **Monitor API usage** - Watch for rate limits

## Translation Commands Reference

| Command | Description | Example |
|---------|-------------|---------|
| `/suite lang` | Show your language | `/suite lang` |
| `/suite lang <code>` | Set language | `/suite lang es` |
| `/suite lang <player> <code>` | Set other's language (admin) | `/suite lang Player123 es` |
| `/suite toggle` | Toggle on/off | `/suite toggle` |
| `/suite toggle <player>` | Toggle other's translation (admin) | `/suite toggle Player123` |
| `/suite lang list` | List supported languages | `/suite lang list` |
| `/suite lang list codes` | List language codes | `/suite lang list codes` |

## Troubleshooting

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| Translation not working | Provider inactive | Check `active: true` in translator config |
| Wrong language | Wrong lang code | Use ISO 639-1 codes |
| Slow translation | No cache / high load | Enable cache, increase pool size |
| "Translation failed" | API error | Check logs, verify API key (LibreTranslate) |
| Auto-detect wrong | Short text | Use explicit `lang-source` |

### Debug Commands

```
/suite translation test "Hello world" en es     # Test translation
/suite translation cache stats                  # Cache statistics
/suite translation cache clear                  # Clear cache
/suite translation test "Hello" auto es         # Test auto-detect
```

### Debug Logging

```yaml
# In config.yml
debug:
  translation: true
  cache: true
```

Or JVM flag:
```bash
-Dtextformattersuite.translation.debug=true
```