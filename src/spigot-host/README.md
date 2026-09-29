# spigot-host — Bukkit/Paper Platform Adapter

> **Purpose**: Bukkit/Paper plugin implementation. Provides platform-specific SPI implementations (`ActorDirectory`, `ChatDelivery`, `PlaceholderResolver`, `ConfigValidator`) and registers commands/listeners.

---

## 1. Responsibilities

- **Plugin entry point** — `TextFormatterSuitePlugin` extends `JavaPlugin`
- **Platform SPI implementations**:
  - `SpigotActorDirectory` — resolves players/console by name/UUID
  - `SpigotChatDelivery` — delivers formatted messages to players (Adventure API)
  - `SpigotPlaceholderResolver` — resolves PlaceholderAPI + internal placeholders
  - `SpigotConfigValidator` — validates config against Paper/Bukkit specifics
- **Command registration** — `DynamicCommandRegistrar` registers `DynamicCommand`s
- **Event listeners** — chat, join, quit, command events
- **NMS integration** — `NmsLocaleBridge` for player locale detection
- **Discord bridge** — `DiscordBridge` for Discord SRV integration
- **Bootstrap extension** — extends `SuiteBootstrap` with Spigot-specific wiring

---

## 2. Non-Responsibilities

- **No core formatting logic** — delegates to `textformatter` via `host`
- **No routing logic** — delegates to `iflow` via `host`
- **No translation logic** — delegates to `gtranslate`/`ltranslate` via `host`
- **No sync logic** — delegates to sync modules via `host`
- **No module loading** — delegates to `kernel` via `host`

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | All SPIs |
| `kernel` | Compile | Module loading |
| `host` | Compile | Bootstrap, dispatch, config |
| `iflow` | Compile | Routing |
| `textformatter` | Compile | Formatting |
| `sync-http` | Compile | HTTP sync |
| `sync-tcpudp` | Compile | TCP/UDP sync |
| `sync-discord` | Compile | Discord sync |
| `sync-telegram` | Compile | Telegram sync |
| `sync-websocket` | Compile | WebSocket sync |
| `gtranslate` | Compile | Google Translate (ServiceLoader at runtime, excluded from shadow JAR) |
| `ltranslate` | Compile | LibreTranslate (ServiceLoader at runtime, excluded from shadow JAR) |
| `messages` | Compile | Message catalog |
| `observability` | Compile | Metrics |
| `manager-impl` | Compile | Module manager |
| `manager-api` | Compile | Manager API |
| `inworld` | Compile | In-world integration |
| `extension-api` | Compile | Extension system |
| `tester` | Compile | Testing utilities (with Spigot exclusion) |
| `transport` | Compile | Transport layer |
| `adventure-platform-bukkit` | Compile | Adventure → Bukkit component conversion |
| `adventure-text-minimessage` | Compile | MiniMessage parsing |
| `net.dv8tion:JDA` | Compile | Discord bridge |
| `paper-api` | CompileOnly | Paper API (1.21.4) |
| `placeholderapi` | CompileOnly | PlaceholderAPI support |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| — (leaf) | Final plugin artifact; no modules depend on this |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TextFormatterSuitePlugin` | Main plugin class: `onEnable()`, `onDisable()`, bootstrap |
| `SpigotActorDirectory` | `ActorDirectory` impl: `Bukkit.getPlayer()`, `getConsoleSender()` |
| `SpigotChatDelivery` | `ChatDelivery` impl: `player.sendMessage(Adventure component)` |
| `SpigotPlaceholderResolver` | `PlaceholderResolver` impl: PlaceholderAPI + internal |
| `DynamicCommandRegistrar` | Registers `DynamicCommand` instances from config |
| `DynamicCommand` | Command wrapper with tab completion, permission, aliases |
| `SpigotConfigValidator` | Validates config: channels, formats, permissions |
| `NmsLocaleBridge` | Detects player locale via NMS (version-dependent) |
| `DiscordBridge` | Discord SRV integration for linked accounts |
| `SpigotScheduler` | `BukkitScheduler` wrapper for async tasks |
| `ChannelSelector` | Selects channel based on event context |
| `EventRules` | Event cancellation/modification rules |
| `LangSetting` | Player language preference management |

---

## 6. Data Flow

### Bootstrap
```text
TextFormatterSuitePlugin.onEnable()
         ↓
create SuiteBootstrap (host) with Spigot classloader
         ↓
SuiteBootstrap.initialize() → loads all modules
         ↓
Register Spigot-specific SPIs:
  - ActorDirectory → SpigotActorDirectory
  - ChatDelivery → SpigotChatDelivery
  - PlaceholderResolver → SpigotPlaceholderResolver
  - ConfigValidator → SpigotConfigValidator
         ↓
ConfigLoader.load() → validate via SpigotConfigValidator
         ↓
DynamicCommandRegistrar.registerAll() → commands from config
         ↓
Register event listeners:
  - AsyncPlayerChatEvent → onChat()
  - PlayerJoinEvent → onJoin()
  - PlayerQuitEvent → onQuit()
  - PlayerCommandPreprocessEvent → onCommand()
         ↓
Plugin ready
```

### Chat Event Processing
```text
AsyncPlayerChatEvent
         ↓
onChat(event)
         ↓
SpigotActorDirectory.getActor(player) → Actor
         ↓
MessageDispatcher.dispatch(rawMessage, actor, channel)
         ↓ (host pipeline: route → format → translate → sync)
         ↓
SpigotChatDelivery.deliver(recipients, formattedMessage)
         ↓
Adventure component → Bukkit component → player.sendMessage()
```

---

## 7. Entry Points

| Entry Point | Location | Trigger |
|-------------|----------|---------|
| `TextFormatterSuitePlugin.onEnable()` | `TextFormatterSuitePlugin.java` | Plugin load |
| `TextFormatterSuitePlugin.onDisable()` | `TextFormatterSuitePlugin.java` | Plugin unload |
| `DynamicCommandRegistrar.registerAll()` | `DynamicCommandRegistrar.java` | Bootstrap |
| `AsyncPlayerChatEvent` listener | `TextFormatterSuitePlugin.java` | Player chat |
| `PlayerJoinEvent` listener | `TextFormatterSuitePlugin.java` | Player join |
| `PlayerQuitEvent` listener | `TextFormatterSuitePlugin.java` | Player quit |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom commands | Add `DynamicCommand` to config, or extend `DynamicCommandRegistrar` |
| Custom placeholders | Extend `SpigotPlaceholderResolver` or register PlaceholderAPI expansion |
| Custom chat delivery | Implement `ChatDelivery` (host.port), replace in bootstrap |
| Custom config validation | Extend `SpigotConfigValidator` |
| Custom NMS bridge | Extend `NmsLocaleBridge` for new MC versions |
| Additional listeners | Register in `onEnable()` after bootstrap |

---

## 9. Exploration Path

```
1. TextFormatterSuitePlugin.java      → Plugin entry point, bootstrap
2. SpigotActorDirectory.java          → ActorDirectory implementation
3. SpigotChatDelivery.java            → ChatDelivery implementation
4. SpigotPlaceholderResolver.java     → PlaceholderResolver implementation
5. DynamicCommandRegistrar.java       → Command registration
6. DynamicCommand.java                → Command model
7. SpigotConfigValidator.java         → Config validation
8. NmsLocaleBridge.java               → NMS locale detection
9. DiscordBridge.java                 → Discord SRV integration
10. host/SuiteBootstrap.java          → Shared bootstrap (parent)
```

---

## 10. Build Notes

- **Shadow JAR** — produces `textformatter-suite-spigot.jar` with only plugin classes (no dependencies)
- **Dependencies excluded** — all `me.majhrs16:suite-*` and external deps excluded from shadow JAR
- **Translation providers** — `gtranslate`/`ltranslate` are compile deps for ServiceLoader discovery at runtime; excluded from shadow JAR
- **Modules loaded remotely** — `manager-impl` loads modules from GitHub at runtime (when GitHub releases exist)
- **Java 21** — requires Java 21 toolchain
- **Paper API 1.21.4** — compiles against Paper 1.21.4-R0.1-SNAPSHOT

---

## 11. Related Modules

- [core-api](../core-api/README.md) — All SPIs implemented here
- [host](../host/README.md) — Shared bootstrap & pipeline
- [kernel](../kernel/README.md) — Module loading
- [textformatter](../textformatter/README.md) — Formatting
- [iflow](../iflow/README.md) — Routing
- [gtranslate](../gtranslate/README.md) / [ltranslate](../ltranslate/README.md) — Translation
- [sync-http](../sync-http/README.md) / [sync-tcpudp](../sync-tcpudp/README.md) / [sync-discord](../sync-discord/README.md) / etc. — Sync
- [manager-impl](../manager-impl/README.md) — Remote module loading
- [fabric-host](../fabric-host/README.md) — **Fabric equivalent (excluded from build)**

---

## 12. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **B-01** | Double message delivery in `onChat` | `broadcast()` now only builds message, doesn't dispatch; `onChat()` dispatches once |
| **B-03** | Main thread blocking in join/quit/death | Handlers offloaded to `runTaskAsynchronously` + `dispatcher.dispatch()` uses `future.get(10s)` timeout |
| **V-01** | WebSocket bind 0.0.0.0 + optional auth | `TextFormatterSuitePlugin` reads `bind` + `token` from `sync/websocket.yml`; passes to `WebSocketSyncSink` which binds 127.0.0.1 and requires token |