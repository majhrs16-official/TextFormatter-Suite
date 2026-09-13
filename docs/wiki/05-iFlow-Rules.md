# iFlow Rules Engine

## Overview

iFlow is TextFormatter Suite's powerful rule engine for message routing, filtering, and transformation. It replaces traditional plugin-based conditional logic with a flexible, declarative rule system.

## Core Concepts

### Rule Structure

```yaml
# rules.yml
guard:
  max-steps: 512              # Max steps per message (prevent infinite loops)
filter:
  dedup-fanout: true          # Deduplicate fan-out messages
priority: batch-first         # Evaluation order: batch-first | depth-first

nodes:
  - id: n_input_chat
    kind: input
    label: chat.global
    matcher:
      channel: chat.global
  
  - id: n_anti_spam
    kind: cond
    label: Anti-spam
    matcher:
      channel: chat.global
    condition: "'spam' in #msg.texts[0]"
    actions:
      - cancel()
      - skipTranslate()
    target: DROP

edges:
  - from: n_input_chat
    to: n_anti_spam
  - from: n_anti_spam
    to: n_output_chat
```

### Node Types

| Kind | Description | Use Case |
|------|-------------|----------|
| `input` | Message entry point | Channel, event entry |
| `cond` | Condition (SpEL) | Filter messages |
| `transform` | Transform message | Rewrite, sounds, sleep |
| `loop` | Loop back | Retry, retry logic |
| `sleep` | Delay | Rate limiting, delays |
| `output` | Message exit | Deliver to channel |
| `redirect` | Redirect | Forward to another channel |

### Node Properties

```yaml
- id: unique_node_id
  kind: input|cond|transform|loop|sleep|output|redirect
  label: "Human readable label"
  matcher:                    # For cond nodes
    channel: chat.global      # Match channel
    sender: "PlayerName"      # Match sender
    receiver: "PlayerName"    # Match recipient
    direction: OTHERS         # Match direction: INITIATOR|OTHERS|ALL|CONSOLE|WORLD|RADIUS|PERMISSION|SPECIFIC
  condition: "'spam' in #msg.texts[0]"  # SpEL condition
  actions:                    # For transform/cond nodes
    - rewrite(template)       # Rewrite message text
    - sounds(add, remove)     # Add/remove sounds
    - sleep(ms)               # Delay
    - setLangSource(lang)     # Set source language
    - setLangTarget(lang)     # Set target language
    - setFormatPapi(true)     # Enable PAPI
    - cancel()                # Cancel message
    - skipTranslate()         # Skip translation
  target:                     # For redirect
    channel: staff.alert
  transform:                  # For transform nodes
    - op: rewrite
      template: "<green>%content%</green>"
    - op: sounds
      add: [sound1, sound2]
      remove: [sound3]
    - op: sleep
      millis: 1500
  priority: 100               # Lower = higher priority
```

## Matchers

### Matcher Types

```yaml
matcher:
  channel: chat.global           # Exact channel match
  channel: "staff.*"            # Glob pattern (future)
  sender: "PlayerName"          # Exact sender name
  sender: "Staff*"              # Glob pattern (future)
  receiver: "PlayerName"        # Exact receiver
  direction: OTHERS             # INITIATOR, OTHERS, ALL, CONSOLE, WORLD, RADIUS, PERMISSION, SPECIFIC
  type: CHAT                    # Message type filter
  permission: "staff.chat"      # Permission check
```

### Combining Matchers

```yaml
matcher:
  channel: chat.global
  direction: OTHERS
  permission: "chat.global"
  # All conditions must match (AND logic)
```

## Conditions (SpEL)

### SpEL Expression Language

Conditions use Spring Expression Language (SpEL):

```yaml
condition: "'spam' in #msg.texts[0]"
condition: "#msg.sender.name == 'Admin'"
condition: "#msg.channel == 'staff.chat' and #msg.sender.hasPermission('admin')"
condition: "T(java.time.LocalTime).now().getHour() > 22"
```

### Available Variables

| Variable | Type | Description |
|----------|------|-------------|
| `#msg` | `Message` | The message object |
| `#msg.texts` | `List<String>` | Message text parts |
| `#msg.sender` | `Actor` | Message sender |
| `#msg.channel` | `String` | Channel name |
| `#msg.direction` | `Direction` | Message direction |
| `#msg.type` | `MessageType` | Message type |
| `#msg.langSource` | `Language` | Source language |
| `#msg.langTarget` | `Language` | Target language |

### Actor Properties

| Property | Type | Description |
|----------|------|-------------|
| `#msg.sender.name` | String | Player name |
| `#msg.sender.uuid` | UUID | Player UUID |
| `#msg.sender.kind` | ActorKind | PLAYER, CONSOLE, SYSTEM |
| `#msg.sender.language` | Language | Player language |
| `#msg.sender.hasPermission('perm')` | Boolean | Permission check |

### Helper Functions

```yaml
# String operations
condition: "'hello' in #msg.texts[0]"
condition: "#msg.texts[0].startsWith('!')"
condition: "#msg.texts[0].contains('spam')"
condition: "#msg.texts[0].matches('.*\\d+.*')"

# Language checks
condition: "#msg.langSource == 'en'"
condition: "#msg.langTarget == 'es'"

# Permission checks
condition: "#msg.sender.hasPermission('staff.chat')"
condition: "!#msg.sender.hasPermission('bypass.antispam')"

# Time-based
condition: "T(java.time.LocalTime).now().getHour() >= 22"
condition: "T(java.time.DayOfWeek).from(#msg.timestamp).name() == 'SATURDAY'"
```

## Actions

### Available Actions

```yaml
actions:
  - cancel()                    # Cancel message delivery
  - skipTranslate()             # Skip translation
  - setLangSource(en)           # Force source language
  - setLangTarget(es)           # Force target language
  - setFormatPapi(true)         # Enable/disable PAPI placeholders
  - setColorMode(GRADIENT)      # Set color mode
  - setFormat(path)             # Set format path
  - cancel()                    # Cancel message
  - skipTranslate()             # Skip translation
  - redirect(channel)           # Redirect to channel
  - setFormat(path)             # Set format path
```

### Transform Actions

```yaml
transform:
  - op: rewrite
    template: "<green>💬 %content%</green>"
  - op: sounds
    add: [entity.experience_orb.pickup, block.note_block.pling]
    remove: [block.note_block.bell]
  - op: sleep
    millis: 1500
  - op: setLangSource
    lang: en
  - op: setLangTarget
    lang: es
  - op: setFormatPapi
    enabled: true
```

## Rule Examples

### Anti-Spam Rule

```yaml
- id: anti_spam
  kind: cond
  label: Anti-spam
  matcher:
    channel: chat.global
  condition: "'spam' in #msg.texts[0] or 'SPAM' in #msg.texts[0]"
  actions:
    - cancel()
    - skipTranslate()
  target: DROP
```

### Staff-Only Channel

```yaml
- id: staff_only
  kind: cond
  label: Staff Only
  matcher:
    channel: staff.chat
  condition: "!#msg.sender.hasPermission('staff.chat')"
  actions:
    - cancel()
  target: REJECT
```

### Language-Based Routing

```yaml
- id: spanish_redirect
  kind: cond
  label: Spanish Chat
  matcher:
    channel: chat.global
  condition: "#msg.langSource == 'es' or #msg.langSource == 'es-ES'"
  actions:
    - setFormat("chat.spanish")
    - setLangTarget(es)
  target: CHANNEL_REDIRECT
  redirectChannel: chat.spanish
```

### Time-Based Muting

```yaml
- id: night_mute
  kind: cond
  label: Night Mute
  matcher:
    channel: chat.global
  condition: "T(java.time.LocalTime).now().getHour() >= 23 or T(java.time.LocalTime).now().getHour() < 6"
  actions:
    - cancel()
  target: DROP
```

### Role-Based Routing

```yaml
- id: vip_chat
  kind: cond
  label: VIP Chat
  matcher:
    channel: chat.vip
  condition: "#msg.sender.hasPermission('vip.chat')"
  actions:
    - setFormat("chat.vip.premium")
  target: LOG
```

### Anti-Caps Lock

```yaml
- id: anti_caps
  kind: transform
  label: Anti Caps
  matcher:
    channel: chat.global
  transforms:
    - op: rewrite
      template: "#msg.texts[0].toLowerCase()"
  condition: "#msg.texts[0] == #msg.texts[0].toUpperCase() and #msg.texts[0].length() > 5"
```

## Graph Structure

### Edges

```yaml
edges:
  - from: n_input_chat
    to: n_anti_spam
  - from: n_anti_spam
    to: n_transform
  - from: n_transform
    to: n_output_chat
  - from: n_anti_spam
    to: n_redirect_staff
    condition: "#msg.sender.hasPermission('staff.alerts')"
```

### Graph Concepts

- **Mux (Fan-in)**: Multiple inputs → single node
- **Fan-out**: Single node → multiple outputs
- **Cycles**: Allowed with `guard.max-steps` protection
- **Priority**: Lower number = higher priority

## Rule Evaluation Order

1. **Input nodes** - Entry points (channel, event)
2. **Condition nodes** - Evaluated in priority order
3. **Transform nodes** - Modify message
3. **Output nodes** - Deliver to destination
4. **Redirect** - Forward to another channel

## Debugging Rules

### Debug Commands

```
/suite debug rules                    # Show rule graph
/suite debug rules trace <player>     # Trace message through rules
/suite debug rules reload             # Reload rules.yml
```

### Debug Output

```
[DEBUG] Rule 'anti_spam' matched for Player123
[DEBUG] Action 'cancel()' executed
[DEBUG] Message dropped: spam detected
```

## Best Practices

1. **Order rules by priority** - Lower number = higher priority
2. **Use specific matchers** - Avoid overly broad conditions
2. **Test conditions** - Use `/suite debug rules test` 
3. **Limit transforms** - Too many transforms impact performance
4. **Use priorities** - Lower number = higher priority
5. **Test thoroughly** - Test with `/suite debug rules test`

## Performance Tips

1. **Use specific matchers** - Avoid broad conditions
2. **Limit transforms** - Each transform adds latency
3. **Use priorities** - Order rules by priority
4. **Avoid complex SpEL** - Simple conditions are faster
5. **Cache results** - Cache permission checks

## Debugging

```bash
# Enable debug logging
-Dtextformattersuite.debug=true

# Or in-game
/suite debug rules on
/suite debug rules trace PlayerName
```

## Migration from ConditionalEvents

| ConditionalEvents | iFlow |
|-------------------|-------|
| Events | Input nodes (kind: input) |
| Conditions | cond nodes with SpEL |
| Actions | Transform/Action nodes |
| Variables | SpEL bindings (`#msg.*`) |
| Placeholders | `#msg.texts[0]`, `#msg.sender.name` |

## Migration Example

### ConditionalEvents
```yaml
# Old ConditionalEvents
events:
  on_chat:
    conditions:
      - "'spam' in message"
    actions:
      - cancel
      - message: "&cNo spam allowed!"
```

### iFlow
```yaml
# rules.yml
nodes:
  - id: n_anti_spam
    kind: cond
    label: Anti-spam
    matcher:
      channel: chat.global
    condition: "'spam' in #msg.texts[0]"
    actions:
      - cancel()
      - skipTranslate()
    target: DROP

edges:
  - from: n_input_chat
    to: n_anti_spam
```