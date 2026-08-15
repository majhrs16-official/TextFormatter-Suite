# ChatTranslator v4

Big-bang rewrite of ChatTranslator as a **platform-agnostic core** with two
platform adapters, following a hexagonal (ports & adapters) architecture.

## Modules

| Module            | Java | Role                                                            |
|-------------------|------|-----------------------------------------------------------------|
| `common`          | 8    | Domain: message model, rules engine, template engine, routing    |
| `spigot`          | 8    | Spigot/Bukkit adapter (plugin jar, 1.0.0→26.3)                   |
| `fabric-1.20.6`   | 21   | Fabric 1.20.6 adapter (mod jar, fabric-loom)                     |

The root project only aggregates; no code lives there.

The two adapters are **functionally equivalent**: chat, private messages,
join/leave, deaths, advancements and interactive sign translation behave the
same way on both platforms.

For other Minecraft versions of Fabric, `tools/new-fabric-version.sh` clones
the reference module with the right gradle pins, Java toolchain, mixin
compatibility level and `fabric.mod.json` dependencies:

```bash
# list of KNOWN-drift points is printed after generation
tools/new-fabric-version.sh 1.21.4 \
  yarn_mappings=1.21.4+build.4 loader_version=0.16.12 fabric_version=0.119.9+1.21.4
./gradlew :fabric-1.21.4:build -x test
```

## Message model

Every chat event produces one or more atomic **`Message`** units, each with its
own sender, **`Direction`** (the audience), content arrays, format group,
colors, sounds and language pair — there is **no** embedded from/to pair.
The message shown to the initiator and the one broadcast to everyone else are
independent units with independent format groups and independent cancellation.
Messages are immutable; rules always mutate a private clone so no rule can
corrupt a unit shared with other recipients.

A `Message` carries:

- `type` — `MessageType` (CHAT, PRIVATE, MENTION, JOIN, LEAVE, DEATH,
  ADVANCEMENT, SIGN, INTERNAL, CUSTOM).
- `sender` — `Actor` (uuid, name, kind, language, native handle).
- `direction` — `Direction` (INITIATOR, OTHERS, ALL, CONSOLE, WORLD, RADIUS,
  PERMISSION, SPECIFIC) with an optional channel and explicit recipients.
- `messages` / `toolTips` — parallel `Formats` (texts + MiniMessage formats).
- `sounds` — `name;volume;pitch` specs.
- `colorMode`, `langSource`, `langTarget`, `translate`, `formatPapi`.
- `lastFormatPath` — the format group that built the message.

## Rules engine (native rules.yml)

Replaces ConditionalEvents. Rules apply per message before formatting and
delivery; a cancelled message is dropped, formatted atoms and helpers run over
a `ScriptSurface`, and stream errors leave the message untouched.

```yaml
rules:
  spam:
    events: [CHAT]
    conditions:
      - "'spam' in #msg.texts[0]"
    actions:
      - cancel()
      - skipTranslate()
```

Every rule is `(name, List<MessageType>, conditions SpEL, actions SpEL)`;
conditions are all evaluated and must all be true. ScriptSurface exposes
atomic operations (`setText`, `setTexts`, `setLangSource`, `setLangTarget`,
`setColorMode`, `setFormatPapi`, `show/hide`, `cancel`, `skipTranslate`) and
helpers (`setFormat(path)`, `clone()`, `toJson()`). The SpEL root object is
`#msg`.

## Events for external integrations

`ChatTranslatorApi.messageEvents()` returns a thread-safe `MessageEventBus`.
Listeners run synchronously on the dispatching thread, before any rule or
rendering:

```java
api.messageEvents().register("anti-swear", event -> {
    if (event.message().text().contains("badword")) {
        event.setCancelled(true);
    }
});
```

A listener may cancel the message, `setMessage(...)` a replacement unit, or
`setProcessed(true)` to take over delivery (the router will not display it).

## Format engine

MiniMessage + Adventure, plus one engine-specific span:

- `<tr>text</tr>` marks the part to be translated (per recipient).
- `%ct_messages%`, `$ct_messages$` and `{0}` inject the raw message text.
- `%player_name%`, `%player_uuid%`, `%lang_source%`, `%lang_target%` are
  built-ins; any other `%variable%` goes through `PlaceholderResolver`
  (PlaceholderAPI on Spigot, identity on Fabric).
- `<expr>...</expr>` evaluates a SpEL expression.
- All dynamic values are escaped so players cannot inject MiniMessage tags.

`formats.yml` is organised as named format groups (any path), each with
`messages.formats` / `messages.texts` (or `texts`), `toolTips`, `sounds` and an
optional `sourceLang` / `targetLang`. The legacy `from`/`to` blocks are gone:
one format group per event type rendered per recipient into that recipient's
language (default group path derived from the message type).

## Configuration

The plugin never touches the server's own YAML stack. Both adapters bundle
`snakeyaml` inside the jar and parse configuration with a hand-written loader
(`core.config.ConfigLoader`), which is also used for the user-data store.

Default `config.yml`, `formats.yml` and `rules.yml` ship **inside the jar with
their comments intact**; on first run `core.config.DefaultFiles` copies them
verbatim (byte-for-byte, comments included) and never overwrites user edits.
Because the files live in `common`, they are byte-identical across platforms.

`JsonCodec` (default `DefaultJsonCodec`) serialises a `Message` to and from a
compact JSON form with zero external dependencies, used to pass messages
across CoT boundaries.

## Domain packages (`common`)

- `core.language` — supported language catalog.
- `core.message` — `Message`, `Actor`, `Direction`, `Formats`, `MessageType`,
  `ColorMode`, `SoundSpec`, `JsonCodec`/`DefaultJsonCodec`.
- `core.player` — `Subject`, `Channel`.
- `core.rules` — `RulesEngine`, `RulesLoader`, `ScriptSurface`.
- `core.event` — `MessageEvent`, `MessageListener`, `MessageEventBus`.
- `core.translate` — `Translator`, Google/Libre backends, detection.
- `core.template` — template engine + renderer.
- `core.scripting` — SpEL evaluation (with the `#msg` root object).
- `core.chat` — `ChatRouter` (per-recipient rendering + delivery),
  `DefaultDirectionResolver`.
- `core.storage` — `UserStore` (YAML backend today; SQLite/MySQL behind the port).
- `core.config` — snakeyaml loading (`ConfigLoader`), `FormatGroups`,
  `FormatApplier` and default-file writer.
- `core.platform` — ports implemented by the adapters.
- `core.api` — public `ChatTranslatorApi`.

## Platform wiring

| Event             | Spigot                                   | Fabric                                              |
|-------------------|------------------------------------------|-----------------------------------------------------|
| Chat              | `AsyncPlayerChatEvent`                   | `ServerMessageEvents.ALLOW_CHAT_MESSAGE`            |
| Private           | routed via `ChatMessage.target`          | same (engine-level)                                 |
| Join / Leave      | `PlayerJoinEvent` / `PlayerQuitEvent`    | `PlayerManagerMixin` intercepts the vanilla broadcast|
| Death             | `PlayerDeathEvent`                       | `PlayerManagerMixin` (vanilla `death.*` broadcast)  |
| Advancement       | `PlayerAdvancementDoneEvent`             | `PlayerManagerMixin` (`chat.type.advancement*`)     |
| Sign              | `PlayerInteractEvent` (sneak + left)     | `AttackBlockCallback` (sneak + left)                |
| Sounds            | Bukkit `Sound` registry                  | `Registries.SOUND_EVENT` + `playSoundToPlayer`      |

The Fabric mixin is a single injection on `PlayerManager#broadcast(Text, boolean)`
(the call site Vanilla uses for death/join/leave/advancement announcements):
recognized translation keys are converted into `ChatMessage`s for the router
and the vanilla broadcast is suppressed; anything else falls through.

The Spigot adapter reads player locales through `NmsLocaleBridge`, which uses
`Player#getLocale()` on 1.12.2+ and falls back to reading the NMS
`EntityPlayer#locale` field reflectively on older versions, so locale-driven
translation works from 1.8 all the way to current builds.

## Building

> Requires JDK 8 and 21 (the Gradle daemon and fabric-loom run on Java 21,
> the core/spigot modules target Java 8); Gradle wrapper 8.13, fabric-loom
> 1.10.5. Declare the JDK locations in `org.gradle.java.installations.paths`
> in `gradle.properties`. A network connection is needed on first run (Fabric
> maven, Mojang).

```bash
./gradlew :common:build      # core + tests
./gradlew :spigot:shadowJar  # build/libs/chattranslator-spigot-3.0.0-SNAPSHOT.jar
./gradlew :fabric-1.20.6:build
```

## Status

- [x] Hexagonal skeleton + adapter boots (both platforms).
- [x] Atomic `Message` model with `Direction`, null-safe routing.
- [x] Native `rules.yml` rules engine (replaces ConditionalEvents).
- [x] Cancellable `MessageEvent` bus for external integrations.
- [x] Format engine with `<tr>` spans, scripting, placeholders.
- [x] Translation (Google free + LibreTranslate), detection.
- [x] Router, storage, config/rules loading with comments preserved.
- [x] Feature parity: chat, join/leave, death, advancement, signs, sounds.
- [x] Multi-version toolchain: Spigot NMS locale bridge + Fabric module generator.
- [ ] Discord (dst) module.
- [ ] SQLite/MySQL user storage.
- [ ] Full command set.