# TextFormatter Suite — Spigot Host

## Purpose

The `spigot-host` module is the **Paper/Spigot platform adapter**. It bridges the platform-neutral `host` module to the Paper/Spigot API.

## Key Components

### TextFormatterSuite (Main Plugin)
- Extends `JavaPlugin`
- Bootstraps `SuiteHost` on enable
- Registers event listeners
- Manages lifecycle (reload, disable)
- Registers `/suite` command tree

### Event Listeners
- `AsyncPlayerChatEvent` — Chat messages (claim modes: cancel-event / clear-recipients)
- `PlayerJoinEvent` → `MessageType.JOIN` → `join` channel
- `PlayerQuitEvent` → `MessageType.LEAVE` → `quit` channel
- `PlayerDeathEvent` → `MessageType.DEATH` → `death` channel
- `PlayerAdvancementDoneEvent` → `MessageType.ADVANCEMENT` → `advancement` channel

### Platform Adapters
- `SpigotActorDirectory` — Bukkit player lookup
- `SpigotChatDelivery` — Adventure Component → `Player.sendMessage()` (main thread hop)
- `SpigotUserLanguageStore` — Persistent language prefs (file-based)
- `SpigotPlaceholderResolver` — PlaceholderAPI integration
- `SpigotScheduler` — Bukkit scheduler wrapper (ms → ticks conversion)

### Commands (`/suite`)
Dynamic command tree from `commands.yml` v2:
- `/suite reload` — Reload config
- `/suite status` — Module status
- `/suite lang <player> <code>` — Set language
- `/suite toggle <player>` — Toggle translation
- `/suite reset <player>` — Reset language
- `/suite test <full|stress|concurrency>` — Run Tester module
- `/suite module <install|update|remove|list|info>` — Module Manager
- `/suite health` — Health check
- `/suite metrics` — Prometheus metrics

### Sync Integrations
- `JdaDiscordSink` — Discord bot (JDA)
- `DiscordBridge` — Discord ↔ Minecraft chat mirror (respects iFlow)
- `WebSocketSyncSink` — Real-time WS endpoints (/ws/chat, /ws/events, /ws/sync, /ws/logs)
- `HttpSink` / `TcpSink` / `UdpSink` — Generic sync

### Health & Metrics
- `HealthCheckRegistry` — JVM, threads, sinks
- `MetricsEndpoint` — `/metrics` (Prometheus), auth token, localhost only
- `DebugEndpoint` — `/debug/*` (state, channels, rules, sinks), no `/simulate`

## Build

Fat JAR via Shadow plugin:
```bash
./gradlew :src:spigot-host:shadowJar
```
Output: `build/libs/textformatter-suite-spigot-<version>.jar`

**Dependencies excluded from fat JAR** — Modules loaded via Manager at runtime.

## Testing

Run: `./gradlew :src:spigot-host:test`

Requires Paper API 1.21.4-R0.1-SNAPSHOT (compileOnly).