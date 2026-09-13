# Configuration Guide

## Configuration Files Overview

TextFormatter Suite uses a file-based configuration system with YAML files. All configuration files are located in the plugin/mod data directory:

```
plugins/TextFormatterSuite/          # Spigot
config/
  config.yml           # Main configuration
  messages.yml         # Localized messages
  channels/
    chat.global.yml    # Global chat channel
    join.yml           # Join event channel
    quit.yml           # Quit event channel
    death.yml          # Death event channel
    advancement.yml    # Advancement event channel
    staff.alert.yml    # Staff alert channel
  translators/
    google.yml         # Google Translate config
    libre.yml          # LibreTranslate config
  sync/
    discord.yml        # Discord sync config
    telegram.yml       # Telegram sync config
    http.yml           # HTTP webhook config
    tcp-udp.yml        # TCP/UDP sync config
    velocity.yml       # Velocity proxy config
    websocket.yml      # WebSocket config
  rules.yml            # iFlow rules (F7+)
  manifest.json        # Version & validation info
```

## Main Configuration (config.yml)

```yaml
# config.yml
quick-look: true                    # Enable quick-look feature
general:
  language: en                      # Default language (en, es, fr, de, auto)
iflow:
  engine:
    parallel: false                 # Enable parallel rule processing (experimental)
sonido:
  enabled: true                     # Enable sound effects globally
chat:
  claim-mode: cancel-event          # How to claim chat events: cancel-event | clear-recipients
```

### Configuration Options

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `quick-look` | boolean | `true` | Show message preview on hover |
| `general.language` | string | `en` | Default language code |
| `iflow.engine.parallel` | boolean | `false` | Enable parallel rule processing |
| `sonido.enabled` | boolean | `true` | Enable sound effects globally |
| `chat.claim-mode` | string | `cancel-event` | How to handle vanilla chat: `cancel-event` or `clear-recipients` |

## Channel Configuration (channels/*.yml)

Each channel is defined in its own YAML file under `channels/`:

```yaml
# channels/chat.global.yml
name: chat.global                    # Channel ID (must match filename)
permission: ""                       # Base permission (empty = no permission required)
type: CHAT                           # Channel type: CHAT | EVENT
send-permission: cht.chat.global.send    # Permission to send (optional)
receive-permission: cht.chat.global.receive # Permission to receive (optional)
show-sender: true                    # Show sender name in message
rate-limit-per-second: 0             # Messages per second per player (0 = unlimited)
lang-source: auto                    # Source language: auto, en, es, fr, etc.
lang-target: auto                    # Target language: auto, en, es, fr, etc.
messages:
  - "<green>%content%</green>"       # Message templates (MiniMessage)
  - "&7👉 &f%player_name%&7: %content%"  # Alternative formats
tooltips:
  - "Hover: %lang_source% → %lang_target%"
sounds:
  - name: entity.experience_orb.pickup  # Sound name (from Minecraft registry)
    volume: 1.0                        # Volume (0.0-1.0)
    pitch: 1.0                         # Pitch (0.5-2.0)
```

### Channel Types

| Type | Description | Use Case |
|------|-------------|----------|
| `CHAT` | Player chat messages | Global chat, team chat, local chat |
| `EVENT` | Server events | Join, quit, death, advancement |

### Channel Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%content%` | The message content |
| `%player_name%` | Sender's display name |
| `%player_uuid%` | Sender's UUID |
| `%lang_source%` | Source language code |
| `%lang_target%` | Target language code |
| `{0}` | First format argument |
| `%player_displayname%` | Player's display name (with formatting) |

## Translator Configuration (translators/*.yml)

```yaml
# translators/google.yml
provider: google                    # Provider: google | libre
active: true                        # Is this provider active
# No API key needed for Google (uses free endpoint)
pool:
  max-concurrent: 6                 # Max concurrent translation requests

# translators/libre.yml
provider: libre
active: false
base-url: https://libretranslate.example.com  # Custom LibreTranslate instance
api-key: ""                         # API key (optional for public instances)
pool:
  max-concurrent: 6
```

### Translation Providers

| Provider | API Key Required | Notes |
|----------|------------------|-------|
| `google` | No | Uses free Google Translate endpoint |
| `libre` | Optional | Self-hosted or public LibreTranslate |

## Sync Configuration (sync/*.yml)

### Discord Sync

```yaml
# sync/discord.yml
enabled: true
token: "YOUR_BOT_TOKEN"              # Discord bot token
channel: 123456789012345678          # Discord channel ID (snowflake)
intents:
  - GUILD_MESSAGES
  - MESSAGE_CONTENT
```

### Telegram Sync

```yaml
# sync/telegram.yml
enabled: true
token: "YOUR_BOT_TOKEN"              # Telegram bot token
chat-id: -1001234567890              # Chat ID (negative for groups)
hub: false                           # Hub mode (relay between servers)
```

### HTTP/Webhook Sync

```yaml
# sync/http.yml
enabled: true
webhook-url: "https://your-server.com/webhook"
inbound-port: 8080                    # Port for incoming webhooks
path: "/webhook/tf-suite"
```

### TCP/UDP Sync

```yaml
# sync/tcp-udp.yml
enabled: true
protocol: "TCP"                       # TCP or UDP
host: "127.0.0.1"
outbound-port: 9093                   # Port to send to
inbound-port: 9094                    # Port to listen on
```

### Velocity Proxy Sync

```yaml
# sync/velocity.yml
enabled: false
secret: "shared-secret"               # Shared secret for auth
servers:
  - "server1"
  - "server2"
mapping: "* -> chat.hub"              # Channel mapping pattern
```

## Translator Configuration

```yaml
# translators/google.yml
provider: google
active: true

# translators/libre.yml
provider: libre
active: false
base-url: https://libretranslate.example.com
api-key: ""
pool:
  max-concurrent: 6
```

## iFlow Rules (rules.yml)

```yaml
# rules.yml
guard:
  max-steps: 512                      # Max rule steps per message
filter:
  dedup-fanout: true                  # Deduplicate fan-out messages
priority: batch-first                 # BFS from inputs
nodes:
  - id: n_chat.global
    kind: input
    label: chat.global
    matcher: {}
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
  - from: n_chat.global
    to: n_anti_spam
```

## Message Configuration (messages.yml)

```yaml
prefix: "[suite] "
not-initialized: "[suite] no inicializado"
usage: "[suite] uso: /suite <lang|reload|status|toggle|reset|module|suite>"
enabled: "[suite] activo: {} canales, traductor '{}'"
reload-ok: "[suite] recargado: {} canales, traductor '{}'"
reload-error: "[suite] error al recargar: {}"
status:
  channels: "[suite] canales: {}"
  translator: "[suite] traductor activo: {}"
  knobs: "[suite] engine.parallel: {} · sonido: {} · claim: {}"
lang:
  current: "[suite] tu idioma: {}"
  updated: "[suite] idioma actualizado: {}"
  invalid: "[suite] valor inválido: usa auto | off | <código> (ej. es, en, zh-CN)"
  other-admin: "[suite] setear el idioma de otro requiere admin"
  player-offline: "[suite] jugador no conectado: {}"
  console: "[suite] consola no tiene idioma; usa /suite lang <jugador> <valor>"
toggle:
  current: "[suite] traducción: {}"
reset:
  ok: "[suite] configs restauradas (respaldo en backup/); storage.yml intacto"
  error: "[suite] error al restaurar: {}"
file:
  reset-failed: "reset falló: {}"
  default-created: "default creado: {}"
  create-failed: "no se pudo crear {}: {}"
test:
  service-unavailable: "Test service not available. Is tester module loaded?"
  starting: "[Test] Starting: {}"
  output: "{}"
  unknown: "Unknown test: {}. Use: full, stress, concurrency"
  error: "[Test] Error: {}"
module:
  list.empty: "No modules loaded"
  list.header: "Loaded modules ({0}):"
  list.entry: "  - {0} ({1})"
  install.started: "Installing {0}:{1}"
  update.started: "Updating {0}:{1}"
  remove.started: "Removing {0}"
  info: "Info for {0}: {1}"
  install.no-module: "Usage: /suite module install <module> [version]"
  update.no-module: "Usage: /suite module update <module> [version]"
  remove.no-module: "Usage: /suite module remove <module>"
  info.no-module: "Usage: /suite module info <module>"
  install.started: "Installing {0}:{1}"
  update.started: "Updating {0}:{1}"
  remove.started: "Removing {0}"
  info: "Info for {0}: {1}"
module:
  list.empty: "No modules loaded"
  list.header: "Loaded modules ({0}):"
  list.entry: "  - {0} ({1})"
  install.started: "Installing {0}:{1}"
  update.started: "Updating {0}:{1}"
  remove.started: "Removing {0}"
  info: "Info for {0}: {1}"
  install.no-module: "Usage: /suite module install <module> [version]"
  update.no-module: "Usage: /suite module update <module> [version]"
  remove.no-module: "Usage: /suite module remove <module>"
  info.no-module: "Usage: /suite module info <module>"
suite:
  update.started: "Starting full suite update ({0})"
  update.finished: "Suite update completed"
  suite:
  update.failed: "Update failed: {0}"
```

## Hot Reload

All configuration files support hot reload. Use `/suite reload` to apply changes without restarting the server.

### Files Supporting Hot Reload

| File | Reload Behavior |
|------|-----------------|
| `config.yml` | Full reload |
| `channels/*.yml` | Channel registry rebuild |
| `translators/*.yml` | Translator reload |
| `sync/*.yml` | Sink reconnect |
| `rules.yml` | Rule engine rebuild |
| `messages.yml` | Message catalog reload |

## Environment Variable Overrides

Configuration values can be overridden via environment variables:

```bash
# Override config values
export TFS_QUICK_LOOK=false
export TFS_GENERAL_LANGUAGE=es
export TFS_IFLOW_PARALLEL=true
export TFS_SONIDO_ENABLED=false
export TFS_CHAT_CLAIM_MODE=clear-recipients
```

## Configuration Validation

Run `/suite reload` to validate configuration. Errors will be reported in console and to players with `textformattersuite.admin` permission.

Common validation errors:
- Invalid YAML syntax
- Missing required fields
- Invalid channel types
- Duplicate channel names
- Invalid language codes
- Missing required sync fields