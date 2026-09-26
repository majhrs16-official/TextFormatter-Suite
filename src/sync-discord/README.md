# sync-discord — Discord Sync Sink

> **Purpose**: Implements `SyncSink` SPI for Discord using JDA (Java Discord API). Sends formatted messages to Discord channels.

---

## 1. Responsibilities

- **Discord integration** — JDA connection, gateway management, reconnection
- **Channel messaging** — send messages to configured Discord channel(s)
- **Embed support** — rich embeds for formatted messages
- **Module registration** — `SyncDiscordModule` registers sink and starts gateway

---

## 2. Non-Responsibilities

- **No Discord bot commands** — only message sending
- **No message routing** — `iflow` decides what to sync
- **No platform-specific Minecraft code** — pure JDA

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `Message` SPIs |
| `transport` | Compile | `MessageCodec` for payload encoding |
| `org.json` | Compile | JSON handling |
| `net.dv8tion:JDA` | Compile | Discord API client |
| `kernel` | Test | Test fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | `SuiteBootstrap` collects `JdaDiscordSink` |
| `spigot-host` | Syncs chat to Discord |
| `fabric-host` | Syncs chat to Discord |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `JdaDiscordSink` | `SyncSink` impl: sends to Discord channel via JDA |
| `DiscordGateway` | Manages JDA `JDABuilder`, connection, reconnection, events |
| `DiscordSink` | Base interface for Discord sinks (extensibility) |
| `SyncDiscordModule` | `Module` registering sink, starts `DiscordGateway` |

---

## 6. Data Flow

```text
SyncSink.send(message)
         ↓
JdaDiscordSink: build Discord message (content + embeds)
         ↓
JDA: channel.sendMessage(message).queue()
         ↓
DiscordGateway handles rate limits, reconnection
```

**Config** (`HostConfig.sync.discord`):
```yaml
sync:
  discord:
    enabled: true
    token: "bot-token-here"
    channel-id: 123456789012345678
    embed-color: 0x00FF00
    show-server-name: true
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncDiscordModule` | `SyncDiscordModule.java` | `ModuleLoader` → `SuiteBootstrap` |
| `DiscordGateway.start()` | `DiscordGateway.java` | `SyncDiscordModule` init |

---

## 8. Extension Points

- **Multiple channels** — extend sink to support channel mapping per message type
- **Custom embeds** — extend `JdaDiscordSink.buildEmbed()`
- **Bot commands** — add `EventListener` to `DiscordGateway`
- **Webhook mode** — alternative to bot token (not implemented)

---

## 9. Exploration Path

```
1. JdaDiscordSink.java          → SyncSink implementation
2. DiscordGateway.java          → JDA connection management
3. SyncDiscordModule.java       → Module registration
4. test/DiscordSinkTest.java    → Usage examples
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `Message` SPIs
- [transport](../transport/README.md) — `MessageCodec` (shared)
- [host](../host/README.md) — Collects sinks, loads config
- [kernel](../kernel/README.md) — Loads module
- [sync-http](../sync-http/README.md) — HTTP sync (sister module)
- [sync-tcpudp](../sync-tcpudp/README.md) — TCP/UDP sync (sister module)