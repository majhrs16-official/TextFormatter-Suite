# coretranslator — Legacy PlaceholderAPI expansion

> **Status:** deprecated, maintained, Bukkit-only. Version/contract `1.0.0`.
> This is a feature package, not a compatibility shim.

## Purpose

`coretranslator` restores the real **CoreTranslator** feature from the previous
ChatTranslator project: the PlaceholderAPI expansion with identifier `cot`
(`%cot_*`). It lets server configurations translate text, read a player's
stored language and build small message workflows without writing Java.

The historical `src/coretranslator` module at `7dd164a4^` was a different thing:
a `LegacyBridge` that converted `suite-common-legacy.ChatMessage` into the
current `Message`. That bridge and `suite-common-legacy` are **not** restored;
they were a compatibility shim and only carried deprecated DTOs.

See `docs/workdir/ADR.md` ADR-035 for the decision record.

## Requirements

- Spigot/Paper 1.21.4+ (Java 21).
- PlaceholderAPI 2.11.6+.
- The `chattranslator` package (`TranslationService`).
- Platform services for the optional commands: `MessageDispatch`,
  `ActorDirectory`, `UserLanguageStore` and, for `sendDiscord`, `SyncBus`.

The package declares `Platform: spigot`; the manager must not install it on
Fabric or Velocity. If PlaceholderAPI is missing, the module logs a warning and
does not expose placeholders; it does not crash the server.

## Placeholders

| Placeholder | Result |
|-------------|--------|
| `%cot_translate;<from>;<to>;<text>%` | Text translated through `TranslationService`. `from`/`to` are ISO-ish codes (`en`, `es`, `zh-CN`); `auto` detects the source. |
| `%cot_lang;<player>%` | The stored language of the player (`auto`, `off`, or a code). |
| `%cot_set;<key>;<value>%` | Stores a per-player variable and returns the value. `null` or empty removes it. |
| `%cot_get;<key>%` | Reads a per-player variable. |
| `%cot_new%` | Creates an empty message in the recent-message store and returns its UUID. |
| `%cot_send;<uuid>%` | Dispatches a stored message through the neutral pipeline. |
| `%cot_broadcast;<uuid>%` | Dispatches a clone of a stored message with `Direction.all()`. |
| `%cot_clone;<uuid>;<key>%` | Clones a stored message, stores the clone and saves its UUID in `<key>`. |
| `%cot_sendDiscord;<uuid>[;<channels>]%` | Sends a stored message to the configured `discord` SyncBus sink. The channel list is accepted for historical compatibility but ignored. |

Identifiers are case-insensitive. Fields before the payload are trimmed; a
payload may contain semicolons (`translate;en;es;hello; world` keeps
`hello; world`).

## What was not restored

- **`var;<uuid>;<expression>` (SpEL).** The current `Message` is immutable and
  exposing arbitrary SpEL from a placeholder would reintroduce a security hole.
  Use the neutral API if you need to inspect a message.
- **Live chat history.** `send`/`broadcast`/`clone` operate on messages created
  by `cot_new`/`cot_clone`. Capturing live chat belongs to `sync-bukkit` and the
  neutral pipeline, not to this legacy expansion.
- **Explicit Discord channel IDs.** `SyncBus` routes to its configured sink; the
  old channel list is parsed and ignored with a debug message.
- **`suite-common-legacy`.** It only contained `ChatMessage` and
  `ChatMessageType` for the removed bridge.

## Threading and limitations

PlaceholderAPI requires a value immediately, so `%cot_translate%` blocks the
calling thread. `TranslationService` caches results, but this is still a legacy
behaviour; high-traffic configurations should use the neutral pipeline instead.
The module is intentionally small and deprecated: it is maintained for existing
configurations, not as the recommended integration path.

## Build and test

```bash
./gradlew :src:coretranslator:test --no-daemon
./gradlew :src:coretranslator:modulePackage --no-daemon
```

`modulePackage` produces a reproducible ZIP and `.sha256` under
`src/coretranslator/build/distributions/`. The real Bukkit/Paper + PlaceholderAPI
smoke test is part of the E2E cycle after `1.0.0`; unit tests cover the
placeholder grammar, the module contract and the PAPI adapter metadata.

## Layout

| Class | Responsibility |
|-------|----------------|
| `CoreTranslatorModule` | SPI descriptor: capability `coretranslator`, requires `chattranslator >= 1.0.0`. |
| `CoreTranslatorActivator` | Resolves services and loads the Bukkit hook only on Spigot/Paper. |
| `CoreTranslatorPlaceholders` | Platform-neutral `%cot_*` grammar and recent-message store. |
| `CoreTranslatorBukkitHook` | Checks PlaceholderAPI and loads the expansion without hard-linking PAPI. |
| `CoreTranslatorPapiExpansion` | `PlaceholderExpansion` adapter (`cot`, version `1.0.0`). |
