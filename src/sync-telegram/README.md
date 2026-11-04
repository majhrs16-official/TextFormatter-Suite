# sync-telegram — Telegram Sync Sink

> **Purpose**: Implements `SyncSink` SPI for Telegram Bot API. Sends formatted messages to Telegram chats.

---

## 1. Responsibilities

- **Telegram Bot API integration** — HTTP calls to `api.telegram.org`
- **Chat messaging** — send messages to the configured chat id
- **Inbound polling** — `poll()` reads `getUpdates` and feeds `SyncListener`
- **Module registration** — `SyncTelegramActivator` registers the sink with the running bus

---

## 2. Non-Responsibilities

- **No bot commands** — only text messages and update polling
- **No message routing** — `iflow-vm` decides what to sync
- **No platform-specific Minecraft code** — pure HTTP

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `SyncSink`, `SyncBus`, `Message` SPIs |
| `sync-common` | Compile | `TelegramConfig` YAML reader (`DEPENDENCY` package) |
| `transport` | Compile | `HttpTransport` for API calls (`DEPENDENCY` package) |
| `org.json` | Compile | JSON payload handling |
| `kernel` / `sync-bus` | Test | Module descriptor and bus fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| module runtime | Activates `SyncTelegramActivator` from the `sync-telegram` ZIP |
| `SyncBus` | Receives the `telegram` sink when its config is usable |
| `spigot-host` | Legacy wiring: still constructs the sink until F4b |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TelegramSink` | `SyncSink` impl: POST to `sendMessage`, long-poll `getUpdates` |
| `SyncTelegramActivator` | Reads `config.yml`, registers the sink with the bus and unregisters it on stop |
| `SyncTelegramModule` | Legacy kernel descriptor publishing the `sync-sink` capability |

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

**Config** (`config/sync-telegram/config.yml`):
```yaml
enabled: true
token: "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ"
chatId: -1001234567890
```

The activator reads the file from its module config directory. An enabled file
without token or chat id is skipped with a log line; the package still
activates.

```bash
./gradlew :src:sync-telegram:test
```

`PackageRuntimeTest.installsSyncTelegramPackageAndRegistersConfiguredSink`
installs the real `sync-common`, `transport`, `sync-bus` and `sync-telegram`
packages, verifies the sink is registered, then unloads `sync-telegram` and
checks that the running bus is empty.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `SyncTelegramActivator` | `SyncTelegramActivator.java` | Module runtime |
| `SyncTelegramModule` | `SyncTelegramModule.java` | Legacy kernel descriptor |

---

## 8. Extension Points

- **Multiple chats** — extend sink to support chat mapping
- **Media support** — add `sendPhoto`, `sendDocument` methods
- **Webhook mode** — implement `SyncListener` for inbound updates

---

## 9. Exploration Path

```
1. TelegramSink.java            → SyncSink implementation
2. SyncTelegramActivator.java   → Package lifecycle and bus registration
3. transport/HttpTransport.java → HTTP client used
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `SyncSink`, `SyncBus`, `Message` SPIs
- [sync-common](../sync-common/README.md) — YAML reader and config paths (`DEPENDENCY`)
- [sync-bus](../sync-bus/README.md) — Fan-out bus that owns the sink
- [transport](../transport/README.md) — `HttpTransport`
- [sync-http](../sync-http/README.md) — HTTP sync (similar pattern)