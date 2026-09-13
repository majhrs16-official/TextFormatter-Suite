# Commands Reference

## Command Structure

All commands use the base `/suite` command (configurable in `commands.yml`):

```
/suite <subcommand> [arguments]
```

### Base Permissions

| Permission | Description |
|------------|-------------|
| `textformattersuite.user` | Basic user commands |
| `textformattersuite.admin` | Administrative commands |

## Core Commands

### `/suite`
Shows suite status: channels, active translator, configuration knobs.

```
/suite
/suite status
```

**Output:**
```
[suite] activo: 12 canales, traductor 'google'
[suite] engine.parallel: false · sonido: true · claim: cancel-event
```

### `/suite reload`
Reloads all configuration files from disk.

```
/suite reload
```

**Output:**
```
[suite] recargado: 12 canales, traductor 'google'
```

### `/suite status`
Shows detailed suite status.

```
/suite status
```

**Output:**
```
[suite] canales: [chat.global, staff.chat, join, quit, death, advancement]
[suite] traductor activo: google
[suite] engine.parallel: false · sonido: true · claim: cancel-event
```

## Language Commands

### `/suite lang`
Show your current language setting.

```
/suite lang
```

**Output:**
```
[suite] tu idioma: auto
```

### `/suite lang <code>`
Set your language.

```
/suite lang es
/suite lang auto
/suite lang off
/suite lang en
/suite lang zh-CN
```

**Output:**
```
[suite] idioma actualizado: es
```

### `/suite lang <player> <code>` (Admin)
Set another player's language.

```
/suite lang Player123 es
```

**Permission:** `textformattersuite.admin`

**Output:**
```
[suite] idioma de Player123 actualizado a es
```

### `/suite toggle`
Toggle your translation on/off.

```
/suite toggle
```

**Output:**
```
[suite] traducción: off
```

### `/suite toggle <player>` (Admin)

```
/suite toggle Player123
```

**Permission:** `textformattersuite.admin`

## Module Management

### `/suite module`
Module management commands.

```
/suite module list                    # List installed modules
/suite module info <module>           # Show module info
/suite module install <module> [ver]  # Install module
/suite module update <module> [ver]   # Update module
/suite module remove <module>         # Remove module
/suite module enable <module>         # Enable module
/suite module disable <module>        # Disable module
/suite module reload <module>         # Reload module
```

### Module Actions

| Action | Description | Permission |
|--------|-------------|------------|
| `list` | List all available modules | `textformattersuite.user` |
| `info` | Show module details | `textformattersuite.user` |
| `install` | Install module | `textformattersuite.admin` |
| `update` | Update module | `textformattersuite.admin` |
| `remove` | Remove module | `textformattersuite.admin` |
| `enable` | Enable module | `textformattersuite.admin` |
| `disable` | Disable module | `textformattersuite.admin` |
| `reload` | Reload module | `textformattersuite.admin` |

### Examples

```
/suite module list
/suite module info suite-presets
/suite module install suite-rpg-preset
/suite module update suite-presets 1.2.0
/suite module enable suite-rpg-preset
/suite module disable suite-rpg-preset
/suite module reload suite-presets
```

## Suite Management

### `/suite update`
Update all modules to latest compatible versions.

```
/suite update [force]
```

**Options:**
- `force` - Force update even if not compatible

```
/suite update
/suite update force
```

**Permission:** `textformattersuite.admin`

### `/suite suite update`
Update entire suite to latest version.

```
/suite suite update [force]
```

## Testing Commands

### `/suite test`
Run test suite.

```
/suite test [type] [args...]
```

**Types:**
| Type | Description | Args |
|------|-------------|------|
| `full` | Full test suite | - |
| `stress` | Stress test | `[players] [msgs]` |
| `concurrency` | Concurrency test | `[threads] [msgs]` |
| `routing` | Routing tests | - |
| `events` | Event tests | - |
| `perf` | Performance tests | - |
| `sync` | Sync tests | - |

**Examples:**
```
/suite test
/suite test full
/suite test stress 10 50
/suite test concurrency 20 100
/suite test routing
```

### Test Output

```
[TEST] Starting: full
[TEST] Running: MessagePipelineTest (15 tests)
[TEST] Running: TranslationPipelineTest (8 tests)
[TEST] Running: IFlowRoutingTest (12 tests)
...
[PASS] All tests passed (47 tests, 2.3s)
```

## Health & Monitoring

### `/suite health`
Show system health status.

```
/suite health
```

**Output:**
```
[suite] Status: HEALTHY
[suite] JVM: 45% heap, 245 threads
[suite] Channels: 12 active
[suite] Translator: google (available)
[suite] Sync: 4/4 sinks healthy
[suite] Modules: 15 loaded
```

### `/suite metrics`
Show detailed metrics.

```
/suite metrics
```

**Output:**
```
[suite] Messages: 1,234,567 total
[suite] Translations: 892,341
[suite] Sync sent: 12,345
[suite] Sync received: 8,921
[suite] Avg latency: 2.3ms
[suite] Errors: 0.01%
```

### `/suite metrics reset`
Reset metrics counters.

```
/suite metrics reset
```

## Debug Commands

### `/suite debug`
Debug utilities.

```
/suite debug rules on|off|trace <player>
/suite debug channels on|off
/suite debug modules on|off
/suite debug sync on|off
/suite debug dump [file]
```

### Debug Commands

| Command | Description |
|---------|-------------|
| `/suite debug rules on` | Enable rule debugging |
| `/suite debug rules trace <player>` | Trace player's messages |
| `/suite debug channels on` | Enable channel debug logging |
| `/suite debug modules on` | Enable module debug logging |
| `/suite debug sync on` | Enable sync debug logging |
| `/suite debug dump [file]` | Dump current state to file |

### Examples

```
/suite debug rules on
/suite debug rules trace Player123
/suite debug dump debug-dump.json
```

## Module Commands

### `/suite extension` (alias: `/suite ext`)
Extension management.

```
/suite ext list                    # List extensions
/suite ext info <id>               # Extension info
/suite ext enable <id>             # Enable extension
/suite ext disable <id>            # Disable extension
/suite ext reload <id>             # Reload extension
/suite ext config <id>             # Show extension config
/suite ext logs <id> [lines]       # Show extension logs
```

### Extension Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `list` | List all extensions | `textformattersuite.user` |
| `info` | Show extension details | `textformattersuite.user` |
| `enable` | Enable extension | `textformattersuite.admin` |
| `disable` | Disable extension | `textformattersuite.admin` |
| `reload` | Reload extension | `textformattersuite.admin` |
| `config` | Show/edit config | `textformattersuite.admin` |
| `logs` | View extension logs | `textformattersuite.admin` |

## Sync Commands

### `/suite sync`
Sync system management.

```
/suite sync status              # Show all sink statuses
/suite sync status <sink>       # Show sink details
/suite sync reload              # Reload sync configs
/suite sync test <sink>         # Test sink connection
/suite sync send <sink> <msg>   # Send test message
/suite sync reload <sink>       # Reload specific sink
```

### Sync Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `status` | Show all sinks status | `textformattersuite.admin` |
| `status <sink>` | Show sink details | `textformattersuite.admin` |
| `reload` | Reload all sync configs | `textformattersuite.admin` |
| `test <sink>` | Test sink connection | `textformattersuite.admin` |
| `send <sink> <msg>` | Send test message | `textformattersuite.admin` |
| `reload <sink>` | Reload specific sink | `textformattersuite.admin` |

### Examples

```
/suite sync status
/suite sync status discord
/suite sync test discord
/suite sync send discord "Test message"
/suite sync reload discord
```

## Debug Commands

### `/suite debug`
Debug and diagnostic commands.

```
/suite debug rules on|off|trace <player>
/suite debug channels on|off
/suite debug modules on|off
/suite debug sync on|off
/suite debug dump [file]
/suite debug simulate <params>
```

### Debug Commands

| Command | Description |
|---------|-------------|
| `rules on` | Enable rule debugging |
| `rules off` | Disable rule debugging |
| `rules trace <player>` | Trace player's messages through rules |
| `channels on/off` | Enable channel debug logging |
| `modules on/off` | Enable module debug logging |
| `sync on/off` | Enable sync debug logging |
| `dump [file]` | Dump state to file |
| `simulate <params>` | Simulate message processing |

### Examples

```
/suite debug rules on
/suite debug rules trace Player123
/suite debug dump debug-dump.json
/suite debug simulate type=CHAT channel=chat.global content="Test" sender=TestPlayer
```

## Utility Commands

### `/suite version`
Show version information.

```
/suite version
```

**Output:**
```
TextFormatter Suite v2.1.0-SNAPSHOT
Build: 2024-01-15T10:30:00Z
Core API: 2.1.0
Modules: 22 loaded
```

### `/suite help`
Show help for all commands.

```
/suite help
/suite help <command>
```

### `/suite about`
Show project information.

```
/suite about
```

## Permission Reference

### User Permissions

| Permission | Description |
|------------|-------------|
| `textformattersuite.user` | Basic user commands |
| `textformattersuite.admin` | Administrative commands |

### Channel Permissions

| Permission | Description |
|------------|-------------|
| `cht.<channel>` | Subscribe to channel |
| `cht.<channel>.send` | Send to channel |
| `cht.<channel>.receive` | Receive from channel |

### Admin Permissions

| Permission | Commands |
|-------------|----------|
| `textformattersuite.admin` | All admin commands |
| `textformattersuite.admin.reload` | Reload config |
| `textformattersuite.admin.module` | Module management |
| `textformattersuite.admin.sync` | Sync management |
| `textformattersuite.admin.debug` | Debug commands |
| `textformattersuite.admin.test` | Test commands |

### Module Permissions

| Permission | Module |
|-------------|--------|
| `textformattersuite.module.<id>` | Module-specific permissions |

## Command Aliases

| Alias | Base Command |
|-------|--------------|
| `/tf` | `/suite` |
| `/txf` | `/suite` |
| `/dst` | `/suite` |
| `/tfx` | `/suite` |
| `/ift` | `/suite` |

### Configuration

```yaml
# commands.yml
base-name: "suite"
aliases: ["suite", "txf", "dst", "tfx", "ift"]
```

## Tab Completion

All commands support tab completion:

```
/suite <TAB>                    # Shows subcommands
/suite lang <TAB>              # Shows language codes
/suite module <TAB>            # Shows module commands
/suite module install <TAB>    # Shows available modules
/suite sync <TAB>              # Shows sync sinks
/suite module install <TAB>    # Shows installable modules
/suite debug rules <TAB>       # Shows rule actions
```

## Command Examples

### Common Workflows

```bash
# Full reload workflow
/suite reload
/suite status

# Language management
/suite lang es
/suite lang Player123 es
/suite toggle Player123

# Module management
/suite module list
/suite module install suite-rpg-preset
/suite module enable suite-rpg-preset
/suite module reload suite-rpg-preset

# Sync management
/suite sync status
/suite sync test discord
/suite sync reload discord

# Debugging
/suite debug rules trace Player123
/suite debug dump debug-dump.json

# Testing
/suite test full
/suite test stress 20 50

# Module management
/suite module install suite-rpg-preset
/suite module enable suite-rpg-preset
/suite module config suite-rpg-preset
```

## Tab Completion

All commands support tab completion:

```
/suite <TAB>
/suite module <TAB>
/suite module install <TAB>
/suite lang <TAB>
/suite module install <TAB>
/suite sync <TAB>
/suite module <TAB>
/suite debug <TAB>
```

## Command Permissions Summary

| Command | Min Permission |
|---------|----------------|
| `/suite` | `textformattersuite.user` |
| `/suite reload` | `textformattersuite.admin` |
| `/suite status` | `textformattersuite.user` |
| `/suite lang` | `textformattersuite.user` |
| `/suite lang <player> <code>` | `textformattersuite.admin` |
| `/suite toggle` | `textformattersuite.user` |
| `/suite toggle <player>` | `textformattersuite.admin` |
| `/suite module *` | `textformattersuite.admin` |
| `/suite update` | `textformattersuite.admin` |
| `/suite test` | `textformattersuite.admin` |
| `/suite health` | `textformattersuite.user` |
| `/suite metrics` | `textformattersuite.admin` |
| `/suite debug *` | `textformattersuite.admin` |
| `/suite sync *` | `textformattersuite.admin` |
| `/suite module *` | `textformattersuite.admin` |
| `/suite ext *` | `textformattersuite.admin` |
| `/suite version` | `textformattersuite.user` |
| `/suite help` | `textformattersuite.user` |

## Command Tree

```
suite
├── reload
├── status
├── lang
│   ├── [auto|off|<code>]
│   └── <player> <auto|off|<code>>
├── toggle [player]
├── reset
├── status
├── test [full|stress|concurrency|routing|events|perf|sync]
├── module
│   ├── list
│   ├── info <module>
│   ├── install <module> [version]
│   ├── update <module> [version]
│   ├── remove <module>
│   ├── enable <module>
│   ├── disable <module>
│   ├── reload <module>
│   └── config <module>
├── sync
│   ├── status [sink]
│   ├── reload [sink]
│   ├── test <sink>
│   └── send <sink> <message>
├── debug
│   ├── rules [on|off|trace <player>]
│   ├── channels [on|off]
│   ├── modules [on|off]
│   ├── sync [on|off]
│   ├── dump [file]
│   └── simulate <params>
├── ext / extension
│   ├── list
│   ├── info <id>
│   ├── enable <id>
│   ├── disable <id>
│   ├── reload <id>
│   ├── config <id>
│   └── logs <id> [lines]
├── version
├── help [command]
└── about
```

## Tab Completion

All commands support full tab completion:

```bash
/suite <TAB>
/suite module <TAB>
/suite module install <TAB>
/suite lang <TAB>
/suite sync <TAB>
/suite debug <TAB>
/suite module install <TAB>
```

The completion system provides:
- Subcommand completion
- Argument value completion (enum values, module IDs, channel names)
- Context-aware suggestions
- Permission-aware filtering