# TextFormatter Suite — Host

## Purpose

The `host` module is the **platform-neutral application core**. It wires together all SPI implementations into a single message processing pipeline:

```
Platform Event → Message → SuiteHost → MessageDispatcher → Delivery
```

It contains **no platform-specific code** (no Bukkit, no Fabric). Platform adapters (spigot-host, fabric-host) call into this module.

## Key Components

### SuiteHost (Facade)
Single-call pipeline entry point:
```java
SuiteHost host = SuiteHost.bootstrap(configDir, permissions, translation, logger);
RoutingResult result = host.deliver(message, recipient);
```

Bootstrap loads:
- `HostConfig` — General settings (language, iFlow parallel, sound)
- `ChannelRegistry` — Channel definitions (format, permissions, sounds, rate limits)
- `TranslationService` — Translation facade
- `Router` — iFlow rule engine
- `TextFormatter` — MiniMessage + `<tr>` translation tags
- `ChatDelivery` — Platform delivery (injected)

### MessageDispatcher
- Expands `Direction` → concrete recipient list via `ActorDirectory`
- Runs `SuiteHost.deliver()` per recipient in **parallel** (bounded executor)
- Aggregates `DispatchReport` (delivered, silenced, redirected, rate-limited)
- Handles `CHANNEL_REDIRECT` re-routing
- Thread-safe, bounded executor (DOS-2 fix)

### ConfigLoader
- Single source of truth for YAML paths (`ConfigPath` enum)
- Loads `config.yml` → `HostConfig`
- Loads `channels/*.yml` → `ChannelRegistry`
- Returns `LoadResult<T>` with config + validation errors
- Hot-reload support via timestamp check

### ChannelRegistry / Channel
- `Channel.Type` — `CHAT` (player messages) | `EVENT` (join/quit/death/advancement)
- Per-channel: permissions, format templates, rate limits, sounds, translation settings
- `ChannelSelector` filters EVENT channels for chat messages

### TranslatorProvider SPI
- Replaces hardcoded `GTranslate`/`LTranslate` instantiation
- `ServiceLoader` discovers translation providers
- Host calls `TranslatorProvider.get()` for active translator

## Key Flows

### Chat Message Delivery
```
1. Platform event (AsyncPlayerChatEvent)
2. SpigotChatDelivery → Message (sender, text, channel, direction)
3. MessageDispatcher.dispatch(message)
4. Expand Direction → List<Actor> (deduplicated)
5. Parallel per-recipient:
   a. host.resolveSourceLanguage() — detect once
   b. host.deliver(message, recipient)
      i. Resolve recipient language
      ii. Router.route() — iFlow rules, permissions, rate limits
      iii. TextFormatter.format() — MiniMessage + <tr> translation
   c. ChatDelivery.deliver(recipient, rendered, original)
6. Aggregate DispatchReport
```

### JOIN/QUIT/DEATH Events
- `MessageType.JOIN/LEAVE/DEATH/ADVANCEMENT`
- Direction = `OTHERS` (exclude sender)
- Channel = `join`/`quit`/`death`/`advancement` (type: EVENT)
- Format uses `%player_name%` placeholder

## Configuration Files

```
config.yml              → HostConfig (general, iflow, sonido, chat, repositories)
channels/chat.yml       → Channel (format, permissions, sounds, rate-limit)
channels/join.yml       → Channel (type: EVENT)
channels/quit.yml       → Channel (type: EVENT)
channels/death.yml      → Channel (type: EVENT)
channels/advancement.yml→ Channel (type: EVENT)
rules.yml               → iFlow rule graph
translators/google.yml  → Google Translate config
translators/libre.yml   → LibreTranslate config
sync/discord.yml        → Discord sync config
...
```

## Testing

Run: `./gradlew :src:host:test`

Key tests:
- `ConfigLoaderTest` — YAML round-trip, validation, LoadResult
- `SuiteHostTest` — Bootstrap, deliver pipeline, language resolution
- `MessageDispatcherTest` — Direction expansion, parallel delivery, rules, sounds
- `E2EPipelineTest` — Full pipeline: event → message → iFlow → translate → format → deliver
- `CrossLanguageGoldenTest` — Golden master tests for cross-language formatting