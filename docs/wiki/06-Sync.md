# Sync System

## Overview

The sync layer bridges Minecraft chat and typed server events to external
platforms. A `sync-bus` implementation fans outgoing messages to every
registered `SyncSink`; each sink can also push inbound messages back through the
neutral pipeline.

```
Minecraft event
      │
      ▼
SyncEventProcessor  (channel selection, translation gating)
      │
      ▼
MessageDispatch ──► host pipeline (format, translate, iFlow)
      │
      ▼
SyncBus.broadcast()
      ├─► DiscordSink
      ├─► TelegramSink
      ├─► HttpSink
      ├─► TcpSink / UdpSink
      ├─► VelocitySink
      └─► WebSocketSink
```

## Configuration paths

Each sync package owns a directory under `config/`. The flat `sync/` directory
is an editor-project convention; the runtime never reads it.

| Sink | Canonical file |
|------|----------------|
| Bus | `config/sync-bus/config.yml` |
| Discord | `config/sync-discord/config.yml` |
| Telegram | `config/sync-telegram/config.yml` |
| HTTP/webhook | `config/sync-http/config.yml` |
| TCP | `config/sync-socket/tcp.yml` |
| UDP | `config/sync-socket/udp.yml` |
| Velocity | `config/sync-velocity-core/config.yml` |
| WebSocket | `config/sync-websocket/config.yml` |
| Bukkit events | `config/sync-bukkit/config.yml` |
| Fabric events | `config/sync-fabric/config.yml` |

All files declare `config-version: 1.0` at the root. A file without it is
rejected with `version de configuracion no identificada (archivo editado o
corrupto?)`; the sink reports the error and does not start.

## Discord

```yaml
# config/sync-discord/config.yml
enabled: true
token: "YOUR_BOT_TOKEN"
channel: 123456789012345678
format: "{sender}: {content}"
transport: jda                 # jda | jdk
intents:
  - GUILD_MESSAGES
  - MESSAGE_CONTENT
config-version: 1.0
```

Create a bot at <https://discord.com/developers/applications>, enable the
`MESSAGE_CONTENT` intent and invite it with read/send permissions. `channel` is
the Discord snowflake used for inbound/outbound chat.

## Telegram

```yaml
# config/sync-telegram/config.yml
enabled: true
token: "YOUR_BOT_TOKEN"
chatId: -1001234567890        # Negative for groups
config-version: 1.0
```

Create the bot with BotFather and add it to the target group. The current parser
reads `enabled`, `token` and `chatId`; there is no Telegram command bridge in the
integrated core.

## HTTP/webhook

```yaml
# config/sync-http/config.yml
enabled: true
outboundUrl: "https://your-server.com/hook"
inboundPort: 8080
inboundPath: "/hook"
bind: "127.0.0.1"
authToken: ""
hmacSecret: ""
config-version: 1.0
```

`outboundUrl` receives POSTed messages. The inbound listener binds to
`bind:inboundPort` and serves `inboundPath`. `authToken` and `hmacSecret` are
optional validation secrets; use TLS at the reverse proxy when exposing the
endpoint publicly.

## TCP/UDP

```yaml
# config/sync-socket/tcp.yml
enabled: true
remoteHost: "127.0.0.1"
remotePort: 9093
localPort: 9094
config-version: 1.0
```

```yaml
# config/sync-socket/udp.yml
enabled: false
remoteHost: "127.0.0.1"
remotePort: 9093
localPort: 9094
config-version: 1.0
```

TCP is ordered and at-least-once; UDP is best-effort and unordered. Both use the
neutral message serialization. Keep the ports open only on trusted networks.

## Velocity

```yaml
# config/sync-velocity-core/config.yml
enabled: false
transport: plugin-messaging
channel: "textformatter:velocity"
subchannel: ""
secret: "shared-secret"
servers:
  - survival
  - creative
  - lobby
mapping: "* -> chat.hub"
config-version: 1.0
```

The same `secret` is required on the proxy and every backend. The Velocity plugin
uses the `sync-velocity` adapter and the neutral `sync-velocity-core` config.

## WebSocket

```yaml
# config/sync-websocket/config.yml
enabled: false
token: ""
port: 9092
bind: "127.0.0.1"
config-version: 1.0
```

The WebSocket sink accepts authenticated clients when `token` is set. It is a
neutral JSON endpoint; there is no per-path `/ws/chat` router in the integrated
core.

## Neutral contracts

`SyncSink` declares the transport and lifecycle:

```java
public interface SyncSink {
    String name();
    DeliverySemantics deliverySemantics();
    Ordering ordering();
    void start() throws Exception;
    void stop();
    void send(Message message) throws Exception;
    void setListener(SyncListener listener);
}
```

`SyncBus` aggregates sinks:

```java
void register(SyncSink sink);
boolean unregister(String name);
Collection<SyncSink> sinks();
int broadcast(Message message);
Map<String, CompletableFuture<Void>> broadcastAsync(Message message);
boolean sendTo(String sinkName, Message message);
void setInboundListener(SyncListener listener);
void start() throws Exception;
void stop();
```

Delivery semantics belong to each sink: `BEST_EFFORT` (UDP), `AT_LEAST_ONCE`
(TCP, WebSocket, HTTP) and `EXACTLY_ONCE` (Velocity with acknowledgements).
`broadcast` is best-effort per sink; one failure does not stop the others.

## Inbound messages

A sink invokes its `SyncListener` with a neutral `Message`. The bus forwards it to
the host pipeline, which applies normal channel permissions, translation and
routing. Inbound sources should set a stable `source`/actor id so the host can
avoid loops when the same message is relayed back.

## Commands and diagnostics

The integrated command tree exposes `/suite reload`, `/suite list`,
`/suite state`, `/suite install`, `/suite remove`, `/suite upgrade` and
`/suite repo`. There are no `/suite sync ...` commands.

Use `/suite state` to see whether the sync packages are active. Restart the
server after editing `config/sync-*/` files; `/suite reload` only reloads
`config/suite/commands.yml`. `SyncBus` exposes counters such as
`getTotalBroadcast()`, `getTotalDropped()`, `getTotalRetries()` and
`getTotalDeduped()` for adapters and tests.

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| Discord not sending | Invalid token, missing intent or permission | Check token, `MESSAGE_CONTENT`, read/send permissions |
| Telegram not receiving | Wrong `chatId` | Use the group id (negative) |
| HTTP webhook failing | Bind/port or TLS | Check `bind`, `inboundPort`, `inboundPath` and reverse proxy |
| Velocity not syncing | Secret mismatch | Use the same `secret` on proxy and backends |
| WebSocket disconnecting | Auth or network | Check `token`, `bind` and firewall |
| Sink ignored after edit | Flat path | Move the file to `config/sync-*/`; flat `sync/` is not read |
| Config rejected | Missing `config-version` | Add `config-version: 1.0` at the root |
