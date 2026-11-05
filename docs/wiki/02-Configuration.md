# Configuration Guide

TextFormatter Suite uses YAML files under the plugin/mod data directory. The
layout is canonical: the global host configuration lives in `config/suite/` and
every module owns its own `config/<module>/` directory. Flat files in the root
of the data directory (`config.yml`, `channels/`, `translators/`, `sync/`,
`rules.yml`, …) are **not** read and are **not** migrated.

```
plugins/TextFormatterSuite/          # Spigot/Paper data directory
config/
  suite/
    config.yml                       # Host configuration
    messages.yml                     # Message catalog
    commands.yml                     # Root command tree
  textformatter/
    channels/
      chat.global.yml
      join.yml
      quit.yml
      death.yml
      advancement.yml
      staff.alert.yml
  chattranslator/
    config.yml                       # Facade defaults
    translators/
      google.yml
      libre.yml
  sync-bus/config.yml
  sync-discord/config.yml
  sync-telegram/config.yml
  sync-http/config.yml
  sync-socket/
    tcp.yml
    udp.yml
  sync-velocity-core/config.yml
  sync-websocket/config.yml
  sync-bukkit/config.yml
  sync-fabric/config.yml
  iflow/
    config.yml                       # iFlow hub: backend selection
  iflow-firewall/
    rules.yml                        # SpEL rules + editor graph
  iflow-vm/
    policy.flow                      # Binary VM program (optional)
  inworld/config.yml
  observability/config.yml
manager/
  repo.yml                           # Module Manager repositories
  library/                           # Installed packages, status, journal, cache
storage.yml                          # UserLanguageStore (uuid -> language)
```

Every runtime configuration YAML under `config/` and `manager/` declares
`config-version: 1.0` at the root, preferably as the last key. `plugin.yml` is
the only platform file excluded. A configuration file without the key is
rejected with `version de configuracion no identificada (archivo editado o
corrupto?)`; the parser logs the error and applies its documented fallback.

`storage.yml` is the `UserLanguageStore` data file (UUID → language), not a
configuration file and not part of the `config-version` parser contract.

## Host Configuration (`config/suite/config.yml`)

```yaml
quick-look: true                    # Show a quick-look echo to the sender
general:
  language: en                      # Default language (en, es, pt, auto, …)
iflow:
  engine:
    parallel: false                 # Parallel recipient routing
sonido:
  enabled: true                     # Master switch for channel sounds
chat:
  claim-mode: cancel-event          # cancel-event | clear-recipients | none
  log-to-console: true              # Mirror player chat to the server console
repositories:                       # Host schema; package operations read manager/repo.yml
  - name: "github"
    type: "github"
    url: "https://api.github.com/repos/majhrs16-official/TextFormatter-Suite"
    enabled: true
module-allowlist: []                # Optional module id allowlist
config-version: 1.0
```

### Host options

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `quick-look` | boolean | `true` | Show a message preview to the sender |
| `general.language` | string | `en` | Default language code |
| `iflow.engine.parallel` | boolean | `false` | Parallel recipient routing |
| `sonido.enabled` | boolean | `true` | Enable channel sounds globally |
| `chat.claim-mode` | string | `cancel-event` | `cancel-event`, `clear-recipients` or `none` |
| `chat.log-to-console` | boolean | `true` | Mirror chat to the console |
| `repositories[]` | list | `[]` | Host repository schema (the manager reads `manager/repo.yml`) |
| `module-allowlist` | list | `[]` | Restrict which modules may activate |

The iFlow **backend** is not selected here. `iflow.engine.kind` does not exist.
The hub owns that decision in `config/iflow/config.yml`.

### iFlow hub (`config/iflow/config.yml`)

```yaml
engine: iflow-firewall              # iflow-firewall | iflow-vm | iflow-wasm
fallback-on-unavailable: true       # Use the next installed backend if the selected one is missing
config-version: 1.0
```

The three engines may be installed together. The hub activates exactly one and
publishes `IflowHub`; `SuiteRuntime` requires the hub and does not accept a
`Router` published directly by an engine. Default selection order when the
configured backend is unavailable: `iflow-firewall` → `iflow-vm` →
`iflow-wasm`.

### Repository configuration

The package manager reads `<data>/manager/repo.yml`, not the host
`repositories` list:

```yaml
# manager/repo.yml
repositories:
  - name: "official"
    type: "github"                  # github | local | http
    url: "https://api.github.com/repos/majhrs16-official/TextFormatter-Suite"
    enabled: true
    priority: 10
  - name: "local"
    type: "local"
    url: "file:///path/to/modules"
    enabled: false
    priority: 20
config-version: 1.0
```

Lower `priority` wins. Supported types are `http`, `local` and `github`. The
manager reads `packages.list` from each repository; `releases.json` is not used.
Every package entry declares its SHA-256, and downloads without a valid checksum
are rejected. Repository changes are applied when the manager initializes, so
restart the server after editing this file.

## Channel Configuration (`config/textformatter/channels/*.yml`)

Each channel is defined in its own file. `name` is the channel id and must match
the file name.

```yaml
# config/textformatter/channels/chat.global.yml
name: chat.global
permission: ""                       # Base permission (empty = none)
type: CHAT                           # CHAT | EVENT
send-permission: cht.chat.global.send
receive-permission: cht.chat.global.receive
show-sender: true
rate-limit-per-second: 0             # 0 = unlimited
lang-source: auto
lang-target: auto
messages:
  - "<green>%content%</green>"
  - "&7👉 &f%player_name%&7: %content%"
tooltips:
  - "Hover: %lang_source% → %lang_target%"
sounds:
  - name: entity.experience_orb.pickup
    volume: 1.0
    pitch: 1.0
config-version: 1.0
```

| Type | Description | Use case |
|------|-------------|----------|
| `CHAT` | Player chat messages | Global, team and local chat |
| `EVENT` | Server events | Join, quit, death, advancement |

| Placeholder | Description |
|-------------|-------------|
| `%content%` | Message content |
| `%player_name%` | Sender display name |
| `%player_uuid%` | Sender UUID |
| `%player_displayname%` | Formatted display name |
| `%lang_source%` | Source language code |
| `%lang_target%` | Target language code |
| `{0}` | First format argument |

A channel file without `config-version` is rejected and reported as a load error;
it is not registered.

## Translator Configuration (`config/chattranslator/`)

```yaml
# config/chattranslator/config.yml
default-backend: google
context-override-enabled: true
config-version: 1.0
```

```yaml
# config/chattranslator/translators/google.yml
provider: google
active: true
timeout: 10                         # HTTP timeout in seconds
config-version: 1.0
```

```yaml
# config/chattranslator/translators/libre.yml
provider: libre
active: false
base-url: https://libretranslate.example.com
api-key: ""                         # Empty = anonymous
timeout: 10                         # HTTP timeout in seconds
config-version: 1.0
```

| Provider | API key required | Notes |
|----------|------------------|-------|
| `google` | No | Free Google Translate endpoint |
| `libre` | Optional | Self-hosted or public LibreTranslate |

## Sync Configuration (`config/sync-*/`)

Each sync module reads only its own directory. The flat `sync/` layout is an
editor-project convention, not a runtime layout.

### Discord (`config/sync-discord/config.yml`)

```yaml
enabled: true
token: "YOUR_BOT_TOKEN"
channel: 123456789012345678          # Discord channel snowflake
format: "{sender}: {content}"
transport: jda                       # jda | jdk
intents:
  - GUILD_MESSAGES
  - MESSAGE_CONTENT
config-version: 1.0
```

### Telegram (`config/sync-telegram/config.yml`)

```yaml
enabled: true
token: "YOUR_BOT_TOKEN"
chatId: -1001234567890               # Negative for groups
config-version: 1.0
```

### HTTP/webhook (`config/sync-http/config.yml`)

```yaml
enabled: true
outboundUrl: "https://your-server.com/hook"
inboundPort: 8080
inboundPath: "/hook"
bind: "127.0.0.1"
authToken: ""
hmacSecret: ""
config-version: 1.0
```

### TCP/UDP (`config/sync-socket/tcp.yml` and `udp.yml`)

```yaml
enabled: true
remoteHost: "127.0.0.1"
remotePort: 9093
localPort: 9094
config-version: 1.0
```

### Velocity (`config/sync-velocity-core/config.yml`)

```yaml
enabled: false
secret: "shared-secret"
servers:
  - "server1"
  - "server2"
mapping: "* -> chat.hub"
config-version: 1.0
```

### WebSocket (`config/sync-websocket/config.yml`)

```yaml
enabled: true
token: "your-auth-token"
port: 9092
bind: "127.0.0.1"
config-version: 1.0
```

## iFlow policy files

- `config/iflow-vm/policy.flow`: binary VM program executed by `iflow-vm`. If
  absent or invalid, the VM uses a pass-through program.
- `config/iflow-firewall/rules.yml`: map with `config-version: 1.0`. The
  executable SpEL rules live under `rules:`; the editor graph uses `nodes:` and
  `edges:`. A root-level list is rejected because it cannot declare a version.

```yaml
# config/iflow-firewall/rules.yml
rules:
  - id: drop-private
    target: DROP
    channel: private
    priority: 5
    reason: staff only
config-version: 1.0
```

## Message Configuration (`config/suite/messages.yml`)

`messages.yml` is a string catalog. Keys are dotted paths and values are
user-visible text; `{}` is the positional placeholder. The file is copied on
first boot and must declare `config-version: 1.0`:

```yaml
prefix: "[suite] "
status:
  channels: "[suite] canales: {}"
config-version: 1.0
```

The catalog does not register commands. A key only has an effect when the
component that owns it reads it.

## Applying configuration changes

`/suite reload` only re-reads `config/suite/commands.yml` and re-registers the
command root. It does **not** reload channels, translators, sync sinks or the
iFlow hub.

| Change | How to apply it |
|--------|-----------------|
| `config/suite/commands.yml` | `/<root> reload` |
| `config/suite/config.yml` | Restart the server |
| `config/textformatter/channels/*.yml` | Restart the server |
| `config/chattranslator/**` | Restart the server |
| `config/sync-*/**` | Restart the server |
| `config/iflow/config.yml` | Restart the server; the hub re-reads its selection on the next pipeline build |
| `config/iflow-firewall/rules.yml` | Restart the server while that backend is active |
| `config/iflow-vm/policy.flow` | Restart the server; the VM reads the program at activation |
| `manager/repo.yml` | Restart the server |
| A preset | `/<root> presets apply <preset>` writes files and reloads active modules |

## Configuration validation

Validation runs when a file is loaded. Errors are logged at `ERROR`. Tolerant
subsystems use their documented fallback (for example, host config and messages
fall back to defaults); channel, translator and sync errors are reported
individually, and a sink with an invalid file does not start.

Common errors:

- Invalid YAML syntax.
- Missing `config-version` (explicit error).
- Invalid channel type or duplicate channel name.
- Invalid language code.
- Missing required sync credentials when `enabled: true`.
- A flat legacy path or a root-level `rules.yml` list (ignored or rejected; it is
  never migrated).

## Module Manager commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/<root> list` | List installed packages and runtime state | `textformatter.suite.admin` |
| `/<root> state` | Show known module states | `textformatter.suite.admin` |
| `/<root> install <zip>` | Install and activate a local package ZIP | `textformatter.suite.admin` |
| `/<root> remove <pkg> [version] [channel]` | Deactivate and remove a package | `textformatter.suite.admin` |
| `/<root> upgrade` | Upgrade installed packages | `textformatter.suite.admin` |
| `/<root> repo install <pkg...>` | Install packages from repositories | `textformatter.suite.admin` |
| `/<root> repo remove <pkg> [version] [channel]` | Remove a package through repositories | `textformatter.suite.admin` |
| `/<root> repo upgrade` | Upgrade packages from repositories | `textformatter.suite.admin` |
| `/<root> cache clean` | Clear the download cache | `textformatter.suite.admin` |
| `/<root> presets list` | List available presets | `textformatter.suite.admin` |
| `/<root> presets apply <preset>` | Apply a preset and reload active modules | `textformatter.suite.admin` |

The Module Manager uses `manager/repo.yml` to discover packages. Local testing is
supported through `file://` repositories.
