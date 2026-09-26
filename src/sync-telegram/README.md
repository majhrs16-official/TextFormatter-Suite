# sync-telegram — Telegram Sync Sink

> **Purpose**: Implements `SyncSink` SPI for Telegram Bot API. Sends formatted messages to Telegram chats.

---

## 1. Responsibilities

- **Telegram Bot API integration** — HTTP calls to `api.telegram.org`
- **Chat messaging** — send messages to configured chat ID(s)
- **Module registration** — `SyncTelegramModule` registers sink

---

## 2. Non-Responsibilities

- **No bot commands/updates** — only message sending (no long polling/webhook)
- **No message routing** — `iflow` decides what to sync
- **No platform-specific Minecraft code** — pure HTTP

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `Message` SPIs |
| `transport` | Compile | `HttpTransport` for API calls |
| `org.json` | Compile | JSON payload handling |
| `kernel` | Test | Test fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | `SuiteBootstrap` collects `TelegramSink` |
| `spigot-host` | Syncs chat to Telegram |
| `fabric-host` | Syncs chat to Telegram |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TelegramSink` | `SyncSink` impl: POST to `sendMessage` endpoint |
| `SyncTelegramModule` | `Module` registering sink |

---

## 6. Data Flow

```text
SyncSink.send(message)
         ↓
TelegramSink: build JSON payload (chat_id, text, parse_mode)
         ↓
HttpTransport.post(https://api.telegram.org/bot<token>/sendMessage)
         ↓
Telegram API responds
```

**Config** (`HostConfig.sync.telegram`):
```yaml
sync:
  telegram:
    enabled: true
    bot-token: "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ"
    chat-id: -1001234567890
    parse-mode: "HTML"
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncTelegramModule` | `SyncTelegramModule.java` | `ModuleLoader` → `SuiteBootstrap` |

---

## 8. Extension Points

- **Multiple chats** — extend sink to support chat mapping
- **Media support** — add `sendPhoto`, `sendDocument` methods
- **Webhook mode** — implement `SyncListener` for inbound updates

---

## 9. Exploration Path

```
1. TelegramSink.java            → SyncSink implementation
2. SyncTelegramModule.java      → Module registration
3. transport/HttpTransport.java → HTTP client used
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `Message` SPIs
- [transport](../transport/README.md) — `HttpTransport`
- [host](../host/README.md) — Collects sinks, loads config
- [kernel](../kernel/README.md) — Loads module
- [sync-http](../sync-http/README.md) — HTTP sync (similar pattern)