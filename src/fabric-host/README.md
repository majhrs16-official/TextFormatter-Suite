# fabric-host — bootstrap Fabric

> **Purpose**: adaptador de plataforma Fabric. Crea los puertos nativos,
> registra los servicios del adapter y arranca `SuiteRuntime`. La captura de
> eventos de chat/join/quit/death pertenece a `sync-fabric` (F4c).

---

## 1. Responsibilities

- **Entry point** — `TextFormatterSuiteMod implements ModInitializer`.
- **Lifecycle** — `SERVER_STARTING` crea el runtime; `SERVER_STOPPING` lo
  cierra.
- **Command bridge** — `CommandRegistrationCallback` enlaza el
  `CommandDispatcher` de Brigadier con `FabricPlatformPorts`. La raíz y sus
  aliases se leen de `config/suite/commands.yml` (default copiado desde
  `defaults/commands.yml`); `/suite reload` re-registra el nodo raíz o informa
  `RESTART_REQUIRED`.
- **Platform ports** — `FabricPlatformPorts` implementa `info`, `deliver`,
  `send`, `findActor`, `onlineActors`, `hasPermission` y `registerCommand`.
- **Adapter services**:
  - `FabricActorDirectory` — jugadores/consola y su idioma.
  - `FabricChatDelivery` — componentes Adventure y sonidos nativos.
  - `FabricPlaceholderResolver` — passthrough (Fabric no tiene PlaceholderAPI).
- **Runtime bootstrap** — registra `ChatDelivery`, `ActorDirectory`,
  `PermissionChecker`, `UserLanguageStore`, `PlaceholderResolver` y
  `PluginLogger` y `PlatformHandle`, y llama a `runtime.start()`.
- **Default configuration** — `DefaultConfigInstaller` copia los defaults del
  host a `config/suite/` y los de cada módulo a `config/<module>/`; no migra
  archivos de la raíz plana ni sobrescribe configuración existente.
- **In-world adapter** — `FabricInWorldHandler` consume `InWorldService` y
  `MessageRenderer`.
- **Test probe adapter** — `FabricTestProbe` implementa el puerto neutral
  `TestProbe` con los puertos vivos del runtime; el comando `/test` todavía no
  está cableado.

## 2. Non-responsibilities

- **No features** — no importa `textformatter`, `chattranslator`, `iflow-*`,
  `messages`, `presets`, `tester`, `inworld` ni sinks.
- **No eventos de chat/join/quit/death** — pertenecen a `sync-fabric` (F4c).
- **No bootstrap heredado** — los helpers de arranque legacy fueron
  retirados.
- **No árbol de comandos duplicado** — F4d lo eliminó; Brigadier solo adapta
  la invocación y la tab completion.
- **No JDA** — el bridge Discord del host se eliminó; vive en paquetes de sync.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | Contratos neutrales |
| `core-render` | Compile | `ChatDelivery` |
| `suite-runtime` | Compile | Composition root y `DefaultConfigInstaller` |
| `adventure-api` | Compile | Componentes |
| `adventure-text-serializer-gson` | Compile | Conversión a `Text` de Fabric |
| `fabric-loader` / `fabric-api` | Compile | SDK de Fabric |
| Minecraft/Yarn | Compile | SDK de Minecraft |

## 4. Main Components

| Component | Role |
|-----------|------|
| `TextFormatterSuiteMod` | Entry point y ciclo de vida |
| `FabricPlatformPorts` | `PlatformPorts` sobre Fabric/Brigadier |
| `FabricActorDirectory` | `ActorDirectory` |
| `FabricChatDelivery` | `ChatDelivery` |
| `FabricPlaceholderResolver` | `PlaceholderResolver` |
| `FabricInWorldHandler` | Adapter de `InWorldService` |
| `FabricTestProbe` | `TestProbe` neutral (sin cablear al comando todavía) |

## 5. Data Flow

```text
onInitialize()
  ↓
SERVER_STARTING → YamlUserLanguageStore + FabricActorDirectory
  ↓
FabricPlatformPorts
  ↓
new SuiteRuntime(folder, ports, logger)
  ↓
registerPlatformService(...)
  ↓
runtime.start() → activa paquetes y construye el pipeline
  ↓
runtime.registerCommands() → cola hasta el CommandDispatcher
  ↓
CommandRegistrationCallback → bindDispatcher
  ↓
SERVER_STOPPING → runtime.close()
```

## 6. Exploration path

1. `TextFormatterSuiteMod.java`
2. `FabricPlatformPorts.java`
3. `FabricChatDelivery.java`
4. `FabricActorDirectory.java`
5. `FabricInWorldHandler.java`
6. `FabricTestProbe.java`

## 7. Related modules

- [host](../host/README.md)
- [suite-runtime](../suite-runtime/README.md)
- [core-render](../core-render/README.md)
- [spigot-host](../spigot-host/README.md)
