# sync-discord-jdk — zero-dependency Discord transport

Technology adapter for the neutral [`sync-discord`](../sync-discord/README.md)
package. It uses only the JDK WebSocket client and the suite `Transport`
abstraction; it does not depend on JDA.

---

## 1. Responsibilities

- Implement `DiscordConnection` with the JDK HTTP/WebSocket clients.
- Handle the Discord Gateway v10 handshake (`Hello`, heartbeat, `Identify`,
  `READY`, `MESSAGE_CREATE`).
- Send outbound messages through `Transport` (REST).
- Wipe the token on close.
- Return from `connect()` immediately; the gateway handshake runs on a daemon
  thread.

## 2. Components

| Component | Role |
|-----------|------|
| `SyncDiscordJdkActivator` | Provides `discord-transport` and registers the factory. |
| `JdkDiscordConnectionFactory` | `name() == "jdk"`. |
| `JdkDiscordConnection` | Port implementation, REST outbound, gateway thread. |
| `JdkDiscordGateway` | Minimal Gateway v10 client over JDK WebSocket. |

---

## 3. Behavior

- Outbound sends only need the token, so they are available as soon as the
  adapter is connected, even while the gateway is still negotiating.
- The gateway thread logs a warning if `READY` is not reached; it never blocks
  module activation.
- `close()` stops the heartbeat scheduler, closes the socket and wipes the
  token.

---

## 4. Tests

`JdkDiscordConnectionTest` uses a loopback `WsServer` and a stub `Transport`:

- REST payload, URL and `Authorization: Bot ...` header;
- API error payloads fail;
- inbound `MESSAGE_CREATE` translation;
- heartbeat and `Identify` frames;
- `connect()` returns in under one second;
- send after close fails without touching the transport.

No external network is required.
