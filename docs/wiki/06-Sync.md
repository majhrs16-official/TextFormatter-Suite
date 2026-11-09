# Sync System

## Overview

The sync layer bridges Minecraft chat and typed server events to external
platforms. A `sync-bus` implementation publishes each sink's action surface,
delivers `message.send` to every registered backend, delivers the rendered
`message.deliver` envelope to the selected backends and executes native actions
per backend; each sink can also push inbound messages back through the neutral
pipeline.

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
SyncBus.deliver(envelope)  ──► message.deliver (LCM)
      ├─► DiscordSink
      ├─► TelegramSink
      ├─► HttpSink
      ├─► TcpSink / UdpSink
      ├─► VelocitySink
      ├─► WebSocketSink
      └─► BukkitSink / FabricSink ──► LocalDelivery (host)
```

`sync-bukkit` and `sync-fabric` are full sinks, not only event listeners. They
publish the `bukkit`/`fabric` backends, so `sync.bukkit` and `sync.fabric` are
valid `outputs` targets. Their physical delivery goes through the host
`LocalDelivery` port: the sink receives the rendered envelope and the host
delivers each unit to its recipient. A local sink rejects `message.send` with
`Unsupported` because the local path needs the per-recipient units; without the
port it returns `HostFailure` instead of pretending success.

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

`SyncSink` declares the transport, lifecycle and published action surface:

```java
public interface SyncSink {
    String name();
    DeliverySemantics deliverySemantics();
    Ordering ordering();
    default SyncSurface surface();           // LCM: backend.info + message.send + message.deliver
    default SyncResult invoke(SyncCall call); // explicit outcome
    default SyncResult deliver(DeliveryEnvelope envelope); // default delegates to send(message)
    void start() throws Exception;
    void stop();
    void send(Message message) throws Exception;
    void setListener(SyncListener listener);
}
```

`SyncBus` aggregates sinks and executes actions:

```java
void register(SyncSink sink);
boolean unregister(String name);
Collection<SyncSink> sinks();
Set<SyncSurface> surfaces();
SyncResult invoke(SyncCall call);
List<SyncResult> broadcast(SyncCall call);
List<SyncResult> send(Message message);
List<SyncResult> sendTo(String backend, Message message);
List<SyncResult> deliver(DeliveryEnvelope envelope);
List<SyncResult> deliverTo(List<String> backends, DeliveryEnvelope envelope, ParallelMode mode);
void setInboundListener(SyncListener listener);
void start() throws Exception;
void stop();
```

Every surface must contain the least common multiple: `backend.info` (`READ`,
5 s), `message.send` (`WRITE`, 15 s) and `message.deliver` (`WRITE`, 15 s, not
idempotent). The backend namespace must equal the sink name. `SyncCall` carries
backend, local action, neutral arguments (`SyncValueType`), `Principal`,
idempotency key and audit reason; the canonical name is `backend.action`.
`DeliveryEnvelope` carries the original `Message` and the recipient-specific
`DeliveryUnit`s already rendered by `format`; `message.deliver` transports it as
a `DELIVERY_REF` argument. `SyncResult` is explicit (`Ok`, `Denied`,
`Unsupported`, `InvalidArgument`, `NotFound`, `Conflict`, `RateLimited`,
`Timeout`, `HostFailure`): an unsupported action is never a silent no-op.

`send`/`sendTo` are typed conveniences over `message.send`; `deliver`/`deliverTo`
are typed conveniences over `message.deliver`. `deliver` deduplicates by message
id inside the dedup window; `deliverTo` does not, because an explicit directed
call is the caller's decision. Neither is a second delivery route.
`broadcast(SyncCall)` is exclusive to the LCM: it replaces the call backend with
each registered surface and returns `Unsupported` per backend for a native
action, so it can never become a destructive mass operation.

Delivery semantics belong to each sink: `BEST_EFFORT` (UDP), `AT_LEAST_ONCE`
(TCP, WebSocket, HTTP) and `EXACTLY_ONCE` (Velocity with acknowledgements; local
sinks also report exactly-once). Idempotent deliveries retry with capped
exponential backoff; `message.deliver` is not idempotent and is attempted once,
because a retry would duplicate the local delivery. Every action is bounded by
its declared timeout and the bus ceiling (30 s); a timeout, full queue or crash
is reported as `Timeout`/`HostFailure`, not as success.

Authority is fail-closed in this slice: `Principal.Official` may only invoke the
LCM, and `Principal.Program` is denied until the operator grant store
(`config/iflow/grants.yml`) exists. Native backend actions are not executable by
third parties yet.

## Outputs and local delivery

A message can select its `sync` targets through `outputs`, as described in
[Configuration](02-Configuration.md):

```yaml
outputs:
  - sync.discord
  - sync.tcp
  - sync.bukkit
outputs-parallel: true
```

`sync` delivers in declared order and reports one outcome per target.
`sync.bukkit` and `sync.fabric` are local targets: they receive the rendered
envelope and the host delivers it to players and the console through
`LocalDelivery`. When `outputs` lists only remote targets, local delivery is
omitted; when `outputs` is absent, the full fan-out includes `bukkit` and
`fabric`. `outputs: []` disables every output, including the local one. A local
delivery failure is reported as a partial output failure, never as a total
success. Event cancellation is not an output decision: use `DROP`/`REJECT` in
the iFlow policy.

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
