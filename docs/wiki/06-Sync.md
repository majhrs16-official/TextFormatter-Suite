# Sync System

## Overview

The sync system enables cross-server and cross-platform message synchronization. Supports Discord, Telegram, HTTP webhooks, TCP/UDP, Velocity proxy, and WebSocket connections.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Sync Architecture                          │
├─────────────────────────────────────────────────────────────┤
│  Minecraft Server 1  ◄───┐                                  │
│                           │                                  │
│  Minecraft Server 2  ◄───┼──► Sync Manager ◄───► External   │
│                           │         │          Services      │
│  Minecraft Server N  ◄───┘         ▼                        │
│                    ┌─────────────────────────────────────┐   │
│                    │  Discord  │ Telegram │ HTTP │ WS    │   │
│                    └─────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Sync Sinks

Each sync protocol is implemented as a `SyncSink`:

| Sink | Protocol | Direction | Config File |
|------|----------|-----------|-------------|
| Discord | Discord Gateway + REST | Bidirectional | `sync/discord.yml` |
| Telegram | Long Polling | Bidirectional | `sync/telegram.yml` |
| HTTP | Webhook + REST | Bidirectional | `sync/http.yml` |
| TCP/UDP | Raw sockets | Bidirectional | `sync/tcp-udp.yml` |
| Velocity | Plugin messaging | Bidirectional | `sync/velocity.yml` |
| WebSocket | WebSocket | Bidirectional | `sync/websocket.yml` |

## Discord Sync

### Configuration

```yaml
# sync/discord.yml
enabled: true
token: "YOUR_BOT_TOKEN"
channel: 123456789012345678          # Discord channel ID (snowflake)
intents:
  - GUILD_MESSAGES
  - MESSAGE_CONTENT
```

### Bot Setup

1. Create application at https://discord.com/developers/applications
2. Create bot user
3. Enable **MESSAGE_CONTENT** intent
3. Copy token to config
4. Invite bot to server with `Send Messages`, `Read Messages`, `Read Message History` permissions

### Message Format

**Minecraft → Discord:**
```
[Server] PlayerName: Hello world!
```

**Discord → Minecraft:**
```
[Discord] UserName: Hello from Discord!
```

### Embeds

```yaml
# sync/discord.yml
embeds:
  enabled: true
  color: 0x00FF00
  show-server-name: true
  show-channel: true
```

## Telegram Sync

### Configuration

```yaml
# sync/telegram.yml
enabled: true
token: "YOUR_BOT_TOKEN"
chat-id: -1001234567890        # Chat ID (negative for groups)
hub: false                      # Hub mode (relay between servers)
```

### Bot Setup

1. Message @BotFather on Telegram
2. Create bot with `/newbot`
3. Copy token to config
3. Add bot to group/channel
4. Get chat ID (message the bot, check `getUpdates`)

### Commands

```yaml
# sync/telegram.yml
commands:
  enabled: true
  prefix: "/tf"
  commands:
    - name: "status"
      description: "Show server status"
    - name: "players"
      description: "List online players"
```

## HTTP/Webhook Sync

### Configuration

```yaml
# sync/http.yml
enabled: true
webhook-url: "https://your-server.com/webhook"
inbound-port: 8080
path: "/webhook/tf-suite"
secret: "optional-shared-secret"
```

### Outbound (Minecraft → External)

POST to `webhook-url`:
```json
{
  "type": "chat",
  "sender": "PlayerName",
  "channel": "chat.global",
  "content": "Hello world!",
  "timestamp": "2024-01-15T10:30:00Z",
  "uuid": "player-uuid"
}
```

### Inbound (External → Minecraft)

POST to `http://your-server:8080/webhook/tf-suite`:

```json
{
  "type": "chat",
  "sender": "ExternalBot",
  "channel": "chat.global",
  "content": "Message from external",
  "source": "webhook"
}
```

### Security

```yaml
# sync/http.yml
secret: "shared-secret"              # Optional: validate incoming requests
verify-ssl: true                     # Verify SSL certificates
timeout-ms: 5000                     # Request timeout
retry-attempts: 3                    # Retry failed sends
```

## TCP/UDP Sync

### Configuration

```yaml
# sync/tcp-udp.yml
enabled: true
protocol: "TCP"                       # TCP or UDP
host: "127.0.0.1"
outbound-port: 9093                   # Port to send to
inbound-port: 9094                    # Port to listen on
protocol-version: 1                   # Protocol version
```

### Message Format

```json
{
  "type": "chat",
  "sender": "PlayerName",
  "channel": "chat.global",
  "content": "Hello",
  "timestamp": 1705312200000,
  "uuid": "player-uuid"
}
```

### Protocol Details

- **TCP**: Length-prefixed JSON (4-byte big-endian length + JSON)
- **UDP**: Raw JSON datagrams (max 64KB)
- **Encoding**: UTF-8 JSON

## Velocity Proxy Sync

### Configuration

```yaml
# sync/velocity.yml
enabled: false
secret: "shared-secret"
servers:
  - "survival"
  - "creative"
  - "lobby"
mapping: "* -> chat.hub"
```

### Requirements

- Velocity proxy 3.2+
- TextFormatter Suite installed on **all** backend servers
- Same `secret` on all servers
- Velocity plugin messaging channel: `textformatter:velocity`

### Channel Mapping

```yaml
mapping: "* -> chat.hub"
# Format: "source_channel -> target_channel"
# * = all channels
# Supports multiple: "chat.* -> hub.chat, staff.* -> staff.global"
```

## WebSocket Sync

### Configuration

```yaml
# sync/websocket.yml
enabled: false
port: 9092
token: ""                          # Auth token (optional)
max-connections: 100
heartbeat-interval: 30000          # ms
```

### Endpoints

| Endpoint | Purpose |
|----------|---------|
| `/ws/chat` | Chat messages |
| `/ws/events` | Server events |
| `/ws/sync` | Cross-server sync |
| `/ws/logs` | Log streaming |

### Client Example (JavaScript)

```javascript
const ws = new WebSocket('ws://localhost:9092/ws/chat?token=secret');

ws.onopen = () => {
  ws.send(JSON.stringify({
    action: 'subscribe',
    path: '/ws/chat'
  }));
});

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('Received:', data);
});

// Send message
ws.send(JSON.stringify({
  action: 'message',
  channel: 'chat.global',
  content: 'Hello from web!'
}));
```

## Sync Architecture

### Message Flow

```
Minecraft Chat Event
       │
       ▼
MessagePipeline (format, translate, iFlow)
       │
       ▼
MessageDispatcher
       │
       ├──► Local Delivery (players)
       │
       ├──► DiscordSink.send()
       │
       ├──► TelegramSink.send()
       │
       ├──► HttpSink.send()
       │
       ├──► TcpUdpSink.send()
       │
       ├──► VelocitySink.send()
       │
       └──► WebSocketSink.send()
```

### Message Serialization

All sync messages use a common format:

```json
{
  "type": "chat",
  "id": "uuid-v4",
  "timestamp": 1705312200000,
  "sender": {
    "name": "PlayerName",
    "uuid": "uuid-v4",
    "kind": "PLAYER",
    "language": "en"
  },
  "channel": "chat.global",
  "type": "CHAT",
  "direction": "OTHERS",
  "content": "Hello world!",
  "raw": "Hello world!",
  "translated": "Hola mundo!",
  "langSource": "en",
  "langTarget": "es"
}
```

## Sync Listener

### Implementing Custom Sync

```java
public class CustomSyncSink implements SyncSink {
    @Override
    public String name() {
        return "custom";
    }
    
    @Override
    public void send(Message message) throws IOException, InterruptedException {
        // Send to external system
    }
    
    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }
    
    @Override
    public void start() throws IOException {
        // Initialize connection
    }
    
    @Override
    public void stop() {
        // Cleanup
    }
}
```

### Registering Custom Sink

```java
// In your extension or module
SyncSinkRegistry.register(new CustomSyncSink());
```

## Sync Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/suite sync status` | Show sync status | `textformattersuite.admin` |
| `/suite sync reload` | Reload sync config | `textformattersuite.admin` |
| `/suite sync test <sink>` | Test sink connection | `textformattersuite.admin` |
| `/suite sync send <sink> <msg>` | Send test message | `textformattersuite.admin` |

## Monitoring & Debugging

### Status Commands

```
/suite sync status           # Show all sink statuses
/suite sync status discord   # Show Discord sink details
/suite sync test discord     # Send test message
/suite sync reload           # Reload all sync configs
```

### Debug Logging

```yaml
# In config.yml
debug:
  sync: true
  discord: true
  websocket: true
```

### Metrics

```prometheus
# Prometheus metrics (if observability enabled)
textformatter_sync_messages_sent_total{sink="discord"} 1234
textformatter_sync_messages_received_total{sink="discord"} 567
textformatter_sync_latency_seconds{sink="discord"} 0.045
textformatter_sync_errors_total{sink="discord",type="timeout"} 3
```

## Troubleshooting

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| Discord not sending | Invalid token/permissions | Check bot token & permissions |
| Telegram not receiving | Wrong chat ID | Verify chat ID (negative for groups) |
| HTTP webhook failing | Firewall/SSL | Check port 8080, SSL cert |
| Velocity not syncing | Secret mismatch | Verify secret on all servers |
| WebSocket disconnecting | Timeout/heartbeat | Increase heartbeat interval |

### Debug Commands

```
/suite sync debug discord        # Verbose Discord logging
/suite sync trace message_id     # Trace message through pipeline
/suite sync dump                 # Dump sync state
/suite sync test all             # Test all sinks
```

## Best Practices

1. **Use Velocity for multi-server** - Best for proxy setups
2. **Use Discord for community** - Rich formatting, wide adoption
3. **Use Telegram for alerts** - Lightweight, mobile-friendly
4. **Use HTTP for integrations** - Flexible, firewall-friendly
5. **Use TCP/UDP for low-latency** - High-frequency, local sync
6. **Use WebSocket for web clients** - Real-time web dashboards
7. **Enable encryption** - Use TLS/SSL for all external connections
8. **Monitor metrics** - Set up alerts for sync failures
9. **Test failover** - Verify redundancy works
10. **Document your setup** - Keep sync topology documented