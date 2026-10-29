# sync-discord-jda — JDA transport adapter

Technology adapter for the neutral [`sync-discord`](../sync-discord/README.md)
package. It is the only Discord package that imports JDA.

---

## 1. Responsibilities

- Implement `DiscordConnection` with JDA.
- Build JDA with the required gateway intents.
- Translate `MessageReceivedEvent` to `DiscordMessage`.
- Send plain text to the configured channel.
- Own the bot token copy and wipe it on close.
- Return from `connect()` without waiting for `JDA.Status.CONNECTED`.

## 2. Non-responsibilities

- No suite routing or formatting.
- No `SyncBus` registration.
- No configuration parsing: it receives `DiscordSettings`.

---

## 3. Components

| Component | Role |
|-----------|------|
| `SyncDiscordJdaActivator` | Provides the `discord-transport` capability and registers the factory. |
| `JdaDiscordConnectionFactory` | `name() == "jda"`. |
| `JdaDiscordConnection` | JDA lifecycle, outbound send and inbound translation. |

---

## 4. Behavior

- `connect()` calls `JDABuilder.createLight(...).build()` and returns. The
  adapter copy of the token is wiped immediately; JDA owns its own copy.
- `isConnected()` is true only when JDA reports `CONNECTED`.
- `send()` fails fast while JDA is not connected or the channel is not cached.
- `close()` calls `shutdownNow()` and is idempotent.

This removes the previous 30-second blocking wait from package activation.

---

## 5. Tests

`JdaDiscordConnectionTest` covers:

- factory name and creation;
- blank-token rejection before any network access;
- fail-fast send before connect;
- idempotent close and blocked sends after close.

No test opens a real Discord connection. End-to-end validation against Discord
is intentionally out of scope for CI.

---

## 6. Packaging

The Gradle project applies `gradle/module-package.gradle` and produces
`sync-discord-jda-2.1.0.zip`. JDA remains on the host classpath until F8
packages third-party libraries as `DEPENDENCY` artifacts.
