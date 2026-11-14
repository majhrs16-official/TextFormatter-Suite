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
SyncResult invokeHost(SyncCall call, HostActionContext context);
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

Authority is fail-closed: `Principal.Official` may only invoke the LCM, and
`Principal.Program` runs only what the operator grants for its SHA-256 in
`config/iflow/grants.yml`. A grant lists exact capabilities or a trailing
wildcard (`sync.discord.*`, `module.textformatter.*`); the program must also
declare the exact action in its image, and the effective authority is the
intersection. `DESTRUCTIVE` and `MODERATION` additionally require
`allow-destructive: true`. Each grant carries `calls-per-minute` and
`max-payload-bytes`: an exhausted budget returns `RateLimited`, an oversized
payload returns `Denied`. A missing, ambiguous or unreadable grant denies the
action. `sync-discord`, `sync-telegram`, `sync-http`, `sync-websocket`,
`sync-socket`, `sync-velocity-core` and the local sinks
(`sync-bukkit`/`sync-fabric` through `LocalSyncSink`) publish and execute native
actions for granted programs. An unknown native action returns `Unsupported`;
an adapter that is not connected returns `HostFailure`. Every attempt appends
one line to `<data>/config/iflow/audit.log` with the program hash, backend,
action, actor, redacted argument hash, status and duration; secret and
undeclared argument values are never written.

### Agnostic host action: `textformatter.channel.set`

Host actions have no surface: the bus resolves a `HostActionHandler` by its
canonical name and validates the call against the handler's `SyncActionSpec`.
`textformatter.channel.set` is `WRITE`, takes `channel` (`CHANNEL_REF`) and
returns the working `Message` with the new channel. It is not part of the LCM,
so `Principal.Official` is always denied. A third-party program reaches it
through `sync_invoke("textformatter", "channel.set", ...)` when the operator
grants `module.textformatter.channel.set`; without that capability the program
is denied. The handler validates that the channel
exists and that the emitter may send and the recipient may receive: an unknown
channel is `NotFound`, a missing permission is `Denied`, and a missing handler
is `Unsupported`. The engine detects the channel change and runs exactly one
secondary routing pass; a second mutation is `DECISION_CONFLICT`. The dedicated
`set_channel` import of the iFlow ABI v3 was removed, with no alias or second
reader (ADR-034).

### Native Discord actions

| Action | Kind | Arguments | Adapters |
|---|---|---|---|
| `discord.channel.create` | `WRITE` | `guild`, `name`, optional `topic` | JDA, JDK |
| `discord.channel.delete` | `DESTRUCTIVE` | `channel`, optional `reason` | JDA, JDK |
| `discord.message.delete` | `DESTRUCTIVE` | `channel`, `message`, optional `reason` | JDA, JDK |
| `discord.member.ban` | `MODERATION` | `guild`, `user`, optional `reason`, optional `deleteMessageSeconds` | JDA, JDK |
| `discord.role.assign` | `MODERATION` | `guild`, `user`, `role`, optional `reason` | JDA, JDK |

Native ids are neutral strings (Discord snowflakes). `reason` is sent as the
Discord audit-log reason. `message.edit` is not published yet: the neutral
`Transport` does not support `PATCH`, and a backend never declares an action it
cannot execute.

### Native Telegram actions

| Action | Kind | Arguments |
|---|---|---|
| `telegram.chat.title.set` | `WRITE` | `chat`, `title` |
| `telegram.chat.leave` | `DESTRUCTIVE` | `chat` |
| `telegram.message.delete` | `DESTRUCTIVE` | `chat`, `message` |
| `telegram.message.edit` | `WRITE` | `chat`, `message`, `text` |
| `telegram.chat.member.ban` | `MODERATION` | `chat`, `user`, optional `revokeMessages` |
| `telegram.chat.member.unban` | `MODERATION` | `chat`, `user`, optional `onlyIfBanned` |

`chat` is a numeric chat id or an `@channel` username, `user` and `message` are
positive Telegram ids. Native actions always require an explicit `chat`; the
configured `chatId` is only the `message.send` destination. Telegram reports a
missing message or chat as HTTP 400, so a `not found` description maps to
`NotFound` and any other 400 maps to `InvalidArgument`; 401/403 and 5xx map to
`HostFailure`, 409 to `Conflict` and 429 to `RateLimited` with
`retry_after` converted to milliseconds.

### Native HTTP actions

| Action | Kind | Arguments |
|---|---|---|
| `http.webhook.send` | `WRITE` | `payload` (`OBJECT`, required) |
| `http.endpoint.info` | `READ` | none |

`webhook.send` posts the neutral `OBJECT` payload as the JSON body to the
configured webhook. `endpoint.info` describes the local inbound endpoint and the
outbound target without exposing secrets: no token, no HMAC secret, and no path
or query from the webhook URL. HTTP statuses map to explicit results: 2xx →
`Ok`, 400 → `InvalidArgument`, 401/403 → `HostFailure`, 404 → `NotFound`, 409 →
`Conflict`, 429 → `RateLimited` (`Retry-After` in seconds converted to
milliseconds) and anything else → `HostFailure`. A `Timeout` comes from
`HttpTimeoutException`. A stopped sink returns `HostFailure` without touching
the network.

### Native WebSocket actions

| Action | Kind | Arguments |
|---|---|---|
| `websocket.path.broadcast` | `WRITE` | `path` (`STRING`, required), `payload` (`OBJECT`, required) |
| `websocket.client.info` | `READ` | optional `path` (`STRING`) |

`path.broadcast` accepts `/ws/chat`, `/ws/events` and `/ws/sync`; `/ws/logs` is
reserved for the diagnostics channel. The payload is wrapped in
`{"type":"broadcast","path":...,"payload":{...}}`, limited to 64 KiB, and sent
to every open subscriber of that path; the result reports the delivered count.
`client.info` reports the sink name, running flag, bound port, total clients
and subscriber counts per endpoint, never client addresses or the auth token.
Invalid arguments return `InvalidArgument` without sending, a stopped sink
returns `HostFailure`, and `Principal.Official` receives `Denied`.

### Native TCP and UDP actions

| Backend | Action | Kind | Arguments |
|---|---|---|---|
| `tcp` | `tcp.endpoint.info` | `READ` | none |
| `tcp` | `tcp.connection.probe` | `READ` | none |
| `udp` | `udp.endpoint.info` | `READ` | none |

`endpoint.info` reports the configured remote host/port, the bound inbound port
and whether the listener is open, without credentials. `connection.probe` opens
and closes a TCP connection to the remote listener with a 4 s connect timeout,
sends no message, and returns `Ok` with `reachable` and latency or `HostFailure`
when the remote is unreachable. A stopped sink returns `HostFailure` and
`send(Message)` throws `IOException` instead of sending through a closed socket.

### Native Velocity actions

| Action | Kind | Arguments |
|---|---|---|
| `velocity.proxy.info` | `READ` | none |
| `velocity.mapping.resolve` | `READ` | `channel` (`STRING`, required), optional `type` (`STRING`) |
| `velocity.queue.purge` | `DESTRUCTIVE` | none |

`proxy.info` reports channel, subchannel, transport, available servers, queue
size and health without exposing the shared secret. `mapping.resolve` applies
the configured exact, `regex:`, `type:` and `*` rules and returns the target
servers; `type` defaults to `CUSTOM`. `queue.purge` drops queued retries and
reports how many entries it removed. A stopped, disabled or shutting-down sink
returns `HostFailure`, and `send(Message)` on a disabled sink throws
`IOException` instead of silently dropping the message.

### Native local actions

| Action | Kind | Arguments |
|---|---|---|
| `bukkit.local.info` | `READ` | none |
| `fabric.local.info` | `READ` | none |

`local.info` reports the backend name, surface version, whether the host
registered the `LocalDelivery` port, and the declared delivery semantics and
ordering. It takes no arguments, is idempotent and works before `start()` because
the local sink opens no connection. It never exposes the port or an envelope.
`Principal.Official` receives `Denied`, and `message.send` returns
`Unsupported` because the local path requires `message.deliver`.


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
