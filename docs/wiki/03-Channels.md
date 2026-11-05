# Channel System

## Overview

A channel defines who can send and receive messages, how the message is
formatted and whether sounds or translation are applied. Channels are YAML
files owned by `textformatter` and loaded from:

```
config/textformatter/channels/<id>.yml
```

The channel `name` is its id and must match the file name. A channel file
without `config-version: 1.0` is rejected and reported as a load error; it is
not registered.

## Channel definition

```yaml
# config/textformatter/channels/chat.global.yml
name: chat.global
type: CHAT
permission: ""                     # Base permission (empty = no restriction)
send-permission: cht.chat.global.send
receive-permission: cht.chat.global.receive
show-sender: true
rate-limit-per-second: 0           # 0 = unlimited
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

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `name` | string | file name | Channel id |
| `type` | string | `CHAT` | `CHAT` or `EVENT` |
| `permission` | string | `""` | Base permission |
| `send-permission` | string | — | Optional send permission |
| `receive-permission` | string | — | Optional receive permission |
| `show-sender` | boolean | `true` | Include the sender name |
| `rate-limit-per-second` | integer | `0` | Per-player limit; `0` = unlimited |
| `lang-source` | string | `auto` | Source language |
| `lang-target` | string | `auto` | Target language |
| `messages` | list | — | MiniMessage/legacy templates |
| `tooltips` | list | `[]` | Hover texts |
| `sounds` | list | `[]` | Sound descriptors |

A channel with no `messages` entry cannot format a delivery and is reported by
the formatter.

## Channel types

| Type | Description | Example |
|------|-------------|---------|
| `CHAT` | Player chat messages | Global, local, staff chat |
| `EVENT` | Server events | Join, quit, death, advancement |

There are no `SYSTEM`, `LOG`, `WORLD`, `RADIUS` or `PRIVATE` channel types. Use
an `EVENT` channel or the routing pipeline for those behaviours.

## Permissions

```text
cht.<channel>.send       # Optional send permission
cht.<channel>.receive    # Optional receive permission
<base>                   # Channel's `permission` value
```

- If `send-permission` is absent, the base permission (`permission`) controls
  sending.
- If `receive-permission` is absent, the base permission controls receiving.
- An empty base permission means the channel is open; explicit send/receive
  permissions still apply.

```yaml
# config/textformatter/channels/staff.chat.yml
name: staff.chat
type: CHAT
permission: staff.chat
send-permission: staff.chat.send
receive-permission: staff.chat.receive
messages:
  - "<dark_red>[STAFF]</dark_red> <gray>%player_name%:</gray> %content%"
config-version: 1.0
```

## Bundled defaults

`DefaultConfigInstaller` writes these channels on first start, only if the
canonical file does not already exist:

| Channel | Type | Description |
|---------|------|-------------|
| `chat.global` | CHAT | Global player chat |
| `join` | EVENT | Player join |
| `quit` | EVENT | Player quit |
| `death` | EVENT | Death message |
| `advancement` | EVENT | Advancement earned |

Other channels are user-created YAML files in the same directory.

## Event channels

```yaml
# config/textformatter/channels/join.yml
name: join
type: EVENT
permission: ""
show-sender: true
messages:
  - "<green>+</green> %player_name% joined the game"
sounds: []
config-version: 1.0
```

Event placeholders available to templates include `%player_name%`,
`%player_uuid%` and `%content%`.

## Channel selection

`ChannelSelector` (sync modules) and the host routing path resolve channels by
id. When a sync event has no explicit channel, the selector takes the first
permitted `CHAT` channel, falling back to `chat` when none matches.

```java
ChannelRegistry registry = host.channels();

Optional<Channel> channel = registry.get("chat.global");
List<Channel> all = registry.all();
boolean exists = registry.has("chat.global");
```

## Editing channels

There are no `/suite channels` CRUD commands. Edit the YAML file under
`config/textformatter/channels/` and restart the server to rebuild the channel
registry. Creating or deleting a channel is a file operation followed by a
restart.

## Loading channels

The channel registry is built from
`config/textformatter/channels/*.yml` when the pipeline starts. Invalid files
are reported individually and skipped; valid channels remain active.

`/suite reload` does not rebuild the registry; it only re-reads
`config/suite/commands.yml`.

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| Messages not appearing | Missing permission | Check `permission`, `send-permission` or `receive-permission` |
| Messages not translating | Translator inactive | Check `active: true` in `config/chattranslator/translators/*.yml` |
| Sounds not playing | Invalid sound name | Use a valid Minecraft sound registry name |
| Rate limit not working | Invalid value | `rate-limit-per-second` must be an integer ≥ 0 |
| Channel not registered | Config error | Check YAML syntax, `name` vs. file name and `config-version: 1.0` |
| Channel ignored after edit | Old flat path | Move the file to `config/textformatter/channels/`; flat `channels/` is not read |
