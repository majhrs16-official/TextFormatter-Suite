# fabric-host — Fabric Mod Platform Adapter

> **Purpose**: Fabric mod implementation. Provides platform-specific SPI implementations (`ActorDirectory`, `ChatDelivery`, `PlaceholderResolver`, `ConfigValidator`) and registers event handlers.

---

## 1. Responsibilities

- **Mod entry point** — `TextFormatterSuiteMod` implements `ModInitializer`
- **Platform SPI implementations**:
  - `FabricActorDirectory` — resolves players/console by name/UUID
  - `FabricChatDelivery` — delivers formatted messages to players (Adventure Platform Fabric)
  - `FabricPlaceholderResolver` — resolves placeholders (Fabric-specific)
  - `ConfigValidator` — validates config against Fabric specifics
- **Event handlers** — chat, join, quit, command via Fabric API
- **Bootstrap extension** — extends `SuiteBootstrap` with Fabric-specific wiring

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
| `core-api` | Compile | All SPIs (via Maven coordinates) |
| `kernel` | Compile | Module loading |
| `host` | Compile | Bootstrap, dispatch, config |
| `iflow` | Compile | Routing |
| `textformatter` | Compile | Formatting |
| `sync-discord` | Compile | Discord sync |
| `sync-velocity` | Compile | Velocity sync |
| `sync-websocket` | Compile | WebSocket sync |
| `gtranslate` | Compile | Google Translate |
| `ltranslate` | Compile | LibreTranslate |
| `messages` | Compile | Message catalog |
| `observability` | Compile | Metrics |
| `manager-impl` | Compile | Module manager |
| `presets` | Compile | Preset management |
| `inworld` | Compile | In-world integration |
| `extension-api` | Compile | Extension system |
| `loadtest` | Compile | Load testing |
| `performance` | Compile | Performance profiling |
| `adventure-text-minimessage` | Compile | MiniMessage parsing |
| `adventure-api` | Compile | Adventure components |
| `adventure-text-serializer-gson` | Compile | Gson serialization |
| `adventure-platform-fabric` | Compile | Adventure → Fabric component conversion |
| `net.dv8tion:JDA` | Compile | Discord integration |
| `fabric-loader` | ModImplementation | Fabric Loader |
| `fabric-api` | ModImplementation | Fabric API |
| `minecraft` | Minecraft | Minecraft 1.21 |
| `yarn mappings` | Mappings | Yarn 1.21+build.1 |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| — (leaf) | Final mod artifact; no modules depend on this |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TextFormatterSuiteMod` | Main mod class: `onInitialize()`, bootstrap |
| `FabricActorDirectory` | `ActorDirectory` impl: `ServerPlayerEntity` lookup |
| `FabricChatDelivery` | `ChatDelivery` impl: Adventure Platform Fabric → `player.sendMessage()` |
| `FabricPlaceholderResolver` | `PlaceholderResolver` impl: Fabric-specific placeholders |
| `ConfigValidator` | Validates config: channels, formats, permissions |
| `ChannelSelector` | Selects channel based on event context |
| `EventRules` | Event cancellation/modification rules |
| `LangSetting` | Player language preference management |
| `DiscordBridge` | Discord integration (shared logic with Spigot) |

---

## 6. Data Flow

### Bootstrap
```text
TextFormatterSuiteMod.onInitialize()
         ↓
create SuiteBootstrap (host) with Fabric classloader
         ↓
SuiteBootstrap.initialize() → loads all modules
         ↓
Register Fabric-specific SPIs:
  - ActorDirectory → FabricActorDirectory
  - ChatDelivery → FabricChatDelivery
  - PlaceholderResolver → FabricPlaceholderResolver
  - ConfigValidator → ConfigValidator
         ↓
ConfigLoader.load() → validate via ConfigValidator
         ↓
Register Fabric event handlers:
  - ChatEvent → onChat()
  - ServerPlayConnectionEvents.JOIN → onJoin()
  - ServerPlayConnectionEvents.DISCONNECT → onQuit()
  - CommandRegistrationCallback → register commands
         ↓
Mod ready
```

### Chat Event Processing
```text
ChatEvent (Fabric)
         ↓
onChat(event)
         ↓
FabricActorDirectory.getActor(player) → Actor
         ↓
MessageDispatcher.dispatch(rawMessage, actor, channel)
         ↓ (host pipeline: route → format → translate → sync)
         ↓
FabricChatDelivery.deliver(recipients, formattedMessage)
         ↓
Adventure component → Fabric component → player.sendMessage()
```

---

## 7. Entry Points

| Entry Point | Location | Trigger |
|-------------|----------|---------|
| `TextFormatterSuiteMod.onInitialize()` | `TextFormatterSuiteMod.java` | Mod load |
| `ChatEvent` handler | `TextFormatterSuiteMod.java` | Player chat |
| `ServerPlayConnectionEvents.JOIN` | `TextFormatterSuiteMod.java` | Player join |
| `ServerPlayConnectionEvents.DISCONNECT` | `TextFormatterSuiteMod.java` | Player quit |
| `CommandRegistrationCallback` | `TextFormatterSuiteMod.java` | Command registration |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom commands | Register via `CommandRegistrationCallback` in `onInitialize()` |
| Custom placeholders | Extend `FabricPlaceholderResolver` |
| Custom chat delivery | Implement `ChatDelivery` (host.port), replace in bootstrap |
| Custom config validation | Extend `ConfigValidator` |
| Additional event handlers | Register in `onInitialize()` after bootstrap |

---

## 9. Exploration Path

```
1. TextFormatterSuiteMod.java       → Mod entry point, bootstrap
2. FabricActorDirectory.java        → ActorDirectory implementation
3. FabricChatDelivery.java          → ChatDelivery implementation
4. FabricPlaceholderResolver.java   → PlaceholderResolver implementation
5. ConfigValidator.java             → Config validation
6. ChannelSelector.java             → Channel selection logic
7. EventRules.java                  → Event handling rules
8. LangSetting.java                 → Language settings
9. DiscordBridge.java               → Discord integration
10. host/SuiteBootstrap.java        → Shared bootstrap (parent)
```

---

## 10. Build Notes

- **Fabric Loom** — uses `fabric-loom` Gradle plugin
- **Maven coordinates** — declares dependencies as `me.majhrs16:suite-*:2.1.0-SNAPSHOT` (not project deps)
- **Shadow JAR** — produces `textformatter-suite-fabric.jar` (thin + shadow)
- **Java 21** — requires Java 21 toolchain
- **Separate build** — likely built/published independently from Spigot

---

## 11. Related Modules

- [core-api](../core-api/README.md) — All SPIs implemented here
- [host](../host/README.md) — Shared bootstrap & pipeline
- [kernel](../kernel/README.md) — Module loading
- [textformatter](../textformatter/README.md) — Formatting
- [iflow](../iflow/README.md) — Routing
- [gtranslate](../gtranslate/README.md) / [ltranslate](../ltranslate/README.md) — Translation
- [sync-discord](../sync-discord/README.md) / [sync-velocity](../sync-velocity/README.md) / [sync-websocket](../sync-websocket/README.md) — Sync
- [manager-impl](../manager-impl/README.md) — Remote module loading
- [spigot-host](../spigot-host/README.md) — Bukkit equivalent