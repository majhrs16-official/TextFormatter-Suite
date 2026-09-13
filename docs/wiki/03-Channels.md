# Channel System

## Overview

The channel system is the core of TextFormatter Suite's message routing. Each channel defines how messages are formatted, who can send/receive, and what transformations are applied.

## Channel Types

### CHAT Channels
For player-to-player communication.

```yaml
name: chat.global
type: CHAT
permission: ""                    # Base permission (empty = no restriction)
send-permission: "cht.chat.global.send"      # Optional: permission to send
receive-permission: "cht.chat.global.receive" # Optional: permission to receive
show-sender: true                   # Show sender in message
rate-limit-per-second: 0            # Messages per second per player (0 = unlimited)
lang-source: auto                   # Source language detection
lang-target: auto                   # Target language for translation
messages:
  - "<green>%content%</green>"      # Primary format
  - "&7👉 &f%player_name%&7: %content%"  # Alternative format
tooltips:
  - "Hover: %lang_source% → %lang_target%"
sounds:
  - name: entity.experience_orb.pickup
    volume: 1.0
    pitch: 1.0
```

### Channel Permissions

```
# Base permission (subscription)
cht.<channel_name>                 # Subscribe to channel (receive messages)

# Asymmetric permissions (optional)
send-permission: cht.<channel>.send    # Can send to channel
receive-permission: cht.<channel>.receive # Can receive from channel

# Default behavior:
# - If no send-permission: anyone with base permission can send
# - If no receive-permission: anyone with base permission can receive
# - If neither defined: base permission controls both
```

### Asymmetric Permissions Example

```yaml
# staff.chat.yml
name: staff.chat
permission: staff.chat              # Base: staff access
send-permission: staff.chat.send    # Only staff can send
receive-permission: staff.chat.receive # Staff + moderators can receive
# Result: Staff can send & receive, moderators can only receive
```

## Default Channels

The suite includes these built-in channels:

| Channel | Type | Description |
|---------|------|-------------|
| `chat.global` | CHAT | Global player chat |
| `chat.local` | CHAT | Local/proximity chat |
| `chat.rp` | CHAT | Roleplay chat |
| `chat.ooc` | CHAT | Out-of-character chat |
| `staff.chat` | CHAT | Staff-only chat (asymmetric) |
| `staff.alerts` | CHAT | Staff alerts (receive-only for staff) |
| `join` | EVENT | Player join |
| `quit` | EVENT | Player quit |
| `death` | EVENT | Death messages |
| `advancement` | EVENT | Advancement earned |

## Channel Permissions Reference

### Base Permission Format
```
cht.<channel_name>                    # Base subscription
cht.<channel_name>.send               # Send permission (if defined)
cht.<channel_name>.receive            # Receive permission (if defined)
```

### Default Channels Permissions

| Channel | Base Permission | Send Perm | Receive Perm |
|---------|----------------|-----------|--------------|
| `chat.global` | `cht.chat.global` | `cht.chat.global.send` | `cht.chat.global.receive` |
| `chat.local` | `cht.chat.local` | `cht.chat.local.send` | `cht.chat.local.receive` |
| `chat.rp` | `cht.chat.rp` | `cht.chat.rp.send` | `cht.chat.rp.receive` |
| `chat.ooc` | `cht.chat.ooc` | `cht.chat.ooc.send` | `cht.chat.ooc.receive` |
| `staff.chat` | `staff.chat` | `staff.chat.send` | `staff.chat.receive` |
| `staff.alerts` | `staff.alerts` | `staff.alerts.send` | `staff.alerts.receive` |
| `join` | `cht.join` | - | - |
| `quit` | `cht.quit` | - | - |
| `death` | `cht.death` | - | - |
| `advancement` | `cht.advancement` | - | - |

## Event Channels

Event channels are special channels that handle server events:

```yaml
# channels/join.yml
name: join
type: EVENT
permission: ""
show-sender: true
messages:
  - "<green>+</green> %player_name% joined the game"
sounds: []
```

### Event Channel Types

| Event | Channel | Direction | Description |
|-------|---------|-----------|-------------|
| Player Join | `join` | `ALL` | Player joins server |
| Player Quit | `quit` | `ALL` | Player leaves server |
| Player Death | `death` | `ALL` | Player dies |
| Advancement | `advancement` | `ALL` | Advancement earned |
| Sign Edit | `sign` | `ALL` | Sign text changed (F8+) |

### Event Placeholders

| Placeholder | Join | Quit | Death | Advancement |
|-------------|------|------|-------|-------------|
| `%player_name%` | ✅ | ✅ | ✅ | ✅ |
| `%content%` | ✅ | ✅ | ✅ | ✅ |
| `%player_uuid%` | ✅ | ✅ | ✅ | ✅ |

## Channel Selector Logic

The channel selector determines which channel a message uses:

```java
// ChannelSelector.select() logic:
1. Get all channels ordered by priority
2. For each channel, check if sender has permission
3. First matching channel wins
4. Fallback: chat.global
```

### Custom Channel Selector

You can implement custom channel selection logic:

```java
// In your extension or custom module
ChannelSelector customSelector = channels -> {
    return channels.stream()
        .filter(c -> player.hasPermission(c.permission()))
        .findFirst()
        .orElse(channels.resolve("chat.global"));
};
```

## Channel Commands

```
/suite channels                    # List all channels
/suite channels list               # List all channels with details
/suite channels info <name>        # Show channel details
/suite channels create <name> [type]  # Create new channel
/suite channels delete <name>      # Delete channel
/suite channels edit <name> <key> <value>  # Edit channel property
```

### Channel Properties Editable via Commands

```
/suite channels edit chat.global type CHAT
/suite channels edit chat.global permission "cht.custom"
/suite channels edit chat.global rate-limit-per-second 5
/suite channels edit chat.global messages "<gold>%content%</gold>"
/suite channels edit chat.global sounds add entity.experience_orb.pickup 1.0 1.0
/suite channels edit chat.global sounds remove entity.experience_orb.pickup
```

## Channel Events

### Channel Events Fired

| Event | When | Data |
|-------|------|------|
| `ChannelCreateEvent` | Channel created | Channel object |
| `ChannelDeleteEvent` | Channel deleted | Channel name |
| `ChannelUpdateEvent` | Channel property changed | Channel, old/new values |
| `ChannelMessageEvent` | Message sent through channel | Message, channel, sender |

### Listening to Channel Events

```java
// In your extension or plugin
@EventHandler
public void onChannelMessage(ChannelMessageEvent event) {
    Channel channel = event.getChannel();
    Message message = event.getMessage();
    Actor sender = event.getSender();
    
    // Custom logic
}
```

## Channel Best Practices

1. **Naming Convention**: Use lowercase with dots: `chat.global`, `staff.alerts`
2. **Permissions**: Define both send/receive permissions for asymmetric channels
3. **Rate Limiting**: Set reasonable rate limits for public channels (e.g., 5/sec)
4. **Sounds**: Use Minecraft sound registry names (e.g., `entity.experience_orb.pickup`)
5. **Translations**: Set appropriate `lang-source` and `lang-target` for translated channels

## Channel Registry API

```java
// Access channel registry
ChannelRegistry registry = host.channels();

// Get channel by name
Optional<Channel> channel = registry.get("chat.global");

// Get all channels
List<Channel> allChannels = registry.all();

// Resolve channel for message
Channel channel = registry.resolve(message.channel());

// Check if channel exists
boolean exists = registry.has("chat.global");
```

## Advanced Channel Configuration

### Conditional Channel Selection

```yaml
# channels/conditional.yml
name: conditional.chat
type: CHAT
permission: "cht.conditional"
# Conditional selection via iFlow rules
# Rule: if player has permission "vip.chat", use vip.chat
# Rule: if player in world "survival", use survival.chat
```

### Channel Groups

```yaml
# Group channels for organizational purposes
groups:
  chat:
    - chat.global
    - chat.local
    - chat.rp
    - chat.ooc
  staff:
    - staff.chat
    - staff.alerts
  events:
    - join
    - quit
    - death
    - advancement
```

## Troubleshooting

### Common Issues

| Problem | Cause | Solution |
|---------|-------|----------|
| Messages not appearing | Missing permission | Check `cht.<channel>` permission |
| Messages not translating | Translator inactive | Check `translators/*.yml` active status |
| Sounds not playing | Invalid sound name | Use valid Minecraft sound registry names |
| Rate limiting not working | Config error | Check `rate-limit-per-second` is integer ≥ 0 |
| Channel not appearing | Config error | Check YAML syntax and channel `name` matches filename |

### Debug Commands

```
/suite channels                    # List all channels with status
/suite channels info chat.global   # Show channel details
/suite debug channels              # Debug channel registry
/suite test routing <player> <msg> # Test message routing
```