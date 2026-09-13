# Frequently Asked Questions

## General

### What is TextFormatter Suite?

TextFormatter Suite is a modular, high-performance chat formatting and translation platform for Minecraft servers. It provides advanced chat formatting, translation, message routing, cross-server synchronization, and extensibility through a modular plugin architecture.

### What platforms are supported?

- **Spigot/Paper**: 1.16.5 - 1.21+
- **Fabric**: 1.21+ (Fabric Loader 0.16+)
- **Velocity**: 3.2+ (via sync-velocity module)
- **BungeeCord**: Planned

### What Java version is required?

**Java 17 or 21** (Java 21 recommended for best performance)

### Is it compatible with other chat plugins?

TextFormatter Suite replaces chat formatting plugins. It may conflict with other chat formatting plugins. Disable other chat formatting plugins before installing.

## Installation

### How do I install on Spigot/Paper?

1. Download `textformatter-suite-spigot.jar`
2. Place in `plugins/` folder
3. Start server
4. Configure `plugins/TextFormatterSuite/config.yml`
5. Run `/suite reload`

### How do I install on Fabric?

1. Install Fabric Loader 0.16+
2. Download `textformatter-suite-fabric.jar`
3. Place in `mods/` folder
4. Start server
5. Configure `config/textformatter-suite/config.yml`
6. Run `/suite reload`

### Does it work on BungeeCord/Velocity?

- **Velocity**: Yes, via `sync-velocity` module
- **BungeeCord**: Planned for future release

## Configuration

### Where are config files located?

**Spigot/Paper:** `plugins/TextFormatterSuite/`
**Fabric:** `config/textformatter-suite/`

### How do I reload configuration?

```
/suite reload
```

### How do I reset to defaults?

```
/suite reset
```
This creates a backup in `backup/<timestamp>/` and regenerates default configs.

### Can I use environment variables?

Yes, configuration values can be overridden:

```bash
export TFS_GENERAL_LANGUAGE=es
export TFS_IFLOW_PARALLEL=true
export TFS_SONIDO_ENABLED=false
```

## Translation

### Which translation providers are supported?

- **Google Translate** (free, no API key needed)
- **LibreTranslate** (self-hosted or public instances)

### How do I enable translation?

1. Enable in `translators/google.yml` or `translators/libre.yml`
2. Set `active: true`
3. Run `/suite reload`

### How do I set player language?

```
/suite lang es          # Set your language
/suite lang Player123 es  # Admin: set other player's language
/suite toggle            # Toggle on/off
/suite lang auto         # Auto-detect
/suite lang off          # Disable translation
```

### How does auto-detection work?

When `lang-source: auto` is set, the system detects the message language automatically using the translation provider's detection API.

### Can I use custom translation providers?

Yes, implement `TranslationService` SPI and register via `Module` or `Extension`.

## iFlow Rules

### How do I create a rule?

1. Edit `rules.yml`
2. Define nodes and edges
3. Run `/suite reload`

### What are the node types?

| Type | Description |
|------|-------------|
| `input` | Entry point |
| `cond` | Condition (SpEL) |
| `transform` | Transform message |
| `loop` | Loop/retry |
| `sleep` | Delay |
| `output` | Deliver to channel |
| `redirect` | Forward to channel |

### How do I write conditions?

Use SpEL (Spring Expression Language):

```yaml
condition: "'spam' in #msg.texts[0]"
condition: "#msg.sender.hasPermission('staff.chat')"
condition: "T(java.time.LocalTime).now().getHour() > 22"
```

### Available variables in conditions

| Variable | Type | Description |
|----------|------|-------------|
| `#msg` | Message | The message object |
| `#msg.texts` | List<String> | Message text parts |
| `#msg.sender` | Actor | Sender actor |
| `#msg.channel` | String | Channel name |
| `#msg.direction` | Direction | Message direction |
| `#msg.sender.hasPermission('perm')` | Boolean | Permission check |

## Sync

### How do I set up Discord sync?

1. Create Discord bot at https://discord.com/developers/applications
2. Enable `GUILD_MESSAGES` and `MESSAGE_CONTENT` intents
3. Copy bot token
4. Configure `sync/discord.yml`:
```yaml
enabled: true
token: "YOUR_BOT_TOKEN"
channel: 123456789012345678
intents: [GUILD_MESSAGES, MESSAGE_CONTENT]
```

### How do I set up Velocity sync?

1. Install `suite-sync-velocity` on all backend servers
2. Install Velocity plugin on proxy
3. Configure same `secret` on all servers
3. Configure `servers` list and `mapping`

### How do I set up WebSocket sync?

1. Enable in `sync/websocket.yml`
2. Set `port` and optional `token`
3. Connect to `ws://host:port/ws/chat`

## Commands

### How do I change the command name?

Edit `commands.yml`:
```yaml
base-name: "myname"
aliases: ["suite", "txf", "custom"]
```

### How do I add custom commands?

Edit `commands.yml`:
```yaml
actions:
  myaction:
    description: "My custom action"
    permission: "myplugin.custom"
    execute: |
      sender.sendMessage("Hello ${sender}!")
commands:
  suite:
    children:
      mycommand:
        ref: myaction
```

## Troubleshooting

### Messages not appearing

1. Check channel permissions: `cht.<channel>`
2. Verify channel exists in `channels/`
3. Check `/suite debug channels on`

### Translation not working

1. Verify translator config: `active: true`
2. Check `/suite translation test "hello" en es`
3. Check `/suite translation cache stats`
4. Check logs for errors

### Sync not working

1. Check `/suite sync status`
2. Run `/suite sync test <sink>`
3. Check logs for errors
4. Verify config in `sync/*.yml`

### Commands not working

1. Check permissions: `textformattersuite.admin`
2. Verify command registration: `/suite help`
3. Check for conflicts with other plugins

### High memory usage

1. Check `/suite metrics`
2. Run `/suite debug dump`
3. Check `/suite health`

### Translation not working

1. Verify `translators/google.yml` has `active: true`
2. Check `/suite translation test "hello" en es`
3. Check cache: `/suite translation cache stats`
4. Check logs for HTTP errors

### Rate limiting not working

1. Check channel config: `rate-limit-per-second`
2. Verify `/suite debug channels on`
3. Check iFlow rules for rate limiting

## Performance

### How to improve performance?

1. Enable `iflow.engine.parallel: true`
2. Enable translation cache (default: 60 min)
3. Use `engine.parallel: true` in config
4. Limit concurrent translations: `pool.max-concurrent`
5. Disable unused sync sinks

### Recommended settings for large servers (1000+ players)

```yaml
iflow:
  engine:
    parallel: true
translators:
  google:
    pool:
      max-concurrent: 20
translation:
  cache:
    ttl-minutes: 60
    max-entries: 50000
```

## Development

### How to build from source?

```bash
./gradlew build
```

### How to run tests?

```bash
./gradlew test
```

### How to run benchmarks?

```bash
./gradlew :suite:loadtest:jmh
```

### How to run load tests?

```bash
export SUITE_BASE_URL=http://localhost:9090
export LOAD_USERS=100
export LOAD_DURATION=60
./gradlew :suite:loadtest:loadTest
```

## Contributing

### How to contribute?

1. Fork repository
2. Create feature branch
3. Write tests
4. Ensure CI passes
5. Update documentation
6. Submit PR

### Code style

- Java 17/21
- Spotless formatting (`./gradlew spotlessApply`)
- JUnit 5 tests
- Javadoc for public APIs

### Reporting bugs

1. Check existing issues
2. Create minimal reproduction
3. Include version, platform, logs
4. Submit on GitHub Issues

---

*FAQ v2.1 - Part of TextFormatter Suite Documentation*