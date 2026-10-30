# spigot-host — bootstrap Bukkit/Paper

> **Purpose**: adaptador de plataforma. Crea los puertos de Bukkit, registra los
> servicios que posee el adapter y arranca `SuiteRuntime`. No construye
> features ni captura eventos de chat.

---

## 1. Responsibilities

- **Entry point** — `TextFormatterSuitePlugin extends JavaPlugin`.
- **Platform ports** — `SpigotPlatformPorts` implementa `info`, `deliver`,
  `send`, `findActor`, `onlineActors`, `hasPermission` y `registerCommand`.
- **Adapter services**:
  - `SpigotActorDirectory` — resuelve jugadores/consola y su idioma.
  - `SpigotChatDelivery` — entrega componentes Adventure y sonidos.
  - `SpigotPlaceholderResolver` — PlaceholderAPI + placeholders internos.
- **Runtime bootstrap** — crea `SuiteRuntime`, registra `ChatDelivery`,
  `ActorDirectory`, `PermissionChecker`, `UserLanguageStore`,
  `PlaceholderResolver` y `PluginLogger`, y llama a `runtime.start()`.
- **Root command** — `runtime.registerCommands()` registra el árbol neutral en
  el `CommandMap`; `plugin.yml` ya no declara `/suite`.
- **In-world adapter** — `SpigotInWorldListener` consume `InWorldService` y
  `MessageRenderer` desde el runtime.

## 2. Non-responsibilities

- **No features** — no importa `textformatter`, `chattranslator`, `iflow-*`,
  `messages`, `presets`, `tester`, `inworld` ni sinks.
- **No eventos de chat/join/quit/death** — pertenecen a `sync-bukkit` (F4c).
- **No bootstrap heredado** — `SuiteHost.bootstrap`/`SuiteBootstrap` fueron
  retirados.
- **No árbol de comandos duplicado** — F4d lo eliminó.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | Contratos neutrales |
| `core-render` | Compile | `ChatDelivery` |
| `suite-runtime` | Compile | Composition root |
| `adventure-platform-bukkit` | Compile | `BukkitAudiences` |
| `adventure-api` | Compile | Componentes |
| `paper-api` | CompileOnly | SDK de Bukkit/Paper |
| `placeholderapi` | CompileOnly | PlaceholderAPI |
| `inworld` | Test only | Fixture del listener in-world |

## 4. Main Components

| Component | Role |
|-----------|------|
| `TextFormatterSuitePlugin` | `onEnable`/`onDisable`, puertos y arranque del runtime |
| `SpigotPlatformPorts` | `PlatformPorts` sobre Bukkit |
| `SpigotActorDirectory` | `ActorDirectory` |
| `SpigotChatDelivery` | `ChatDelivery` |
| `SpigotPlaceholderResolver` | `PlaceholderResolver` |
| `SpigotInWorldListener` | Adapter de `InWorldService` |
| `SpigotTestProbe` | `TestProbe` neutral (sin cablear todavía) |

## 5. Data Flow

```text
onEnable()
  ↓
BukkitAudiences + SpigotActorDirectory + YamlUserLanguageStore
  ↓
SpigotPlatformPorts
  ↓
new SuiteRuntime(folder, ports, logger)
  ↓
registerPlatformService(...)
  ↓
runtime.start() → activa paquetes y construye SuiteHost
  ↓
runtime.registerCommands() → CommandMap
  ↓
onDisable() → runtime.close()
```

## 6. Exploration path

1. `TextFormatterSuitePlugin.java`
2. `SpigotPlatformPorts.java`
3. `SpigotChatDelivery.java`
4. `SpigotActorDirectory.java`
5. `SpigotInWorldListener.java`

## 7. Related modules

- [host](../host/README.md)
- [suite-runtime](../suite-runtime/README.md)
- [core-render](../core-render/README.md)
- [fabric-host](../fabric-host/README.md)
