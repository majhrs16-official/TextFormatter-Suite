# sync-bukkit — captura de eventos Bukkit/Paper

> **Purpose**: paquete de plataforma descargable. Captura chat, join, quit y
> death en Spigot/Paper/Purpur, los traduce a `Message` y los despacha por
> `MessageDispatch`. No vive dentro del JAR de `spigot-host`.

---

## 1. Responsibilities

- **Listener Bukkit** — `BukkitChatListener` registra `AsyncPlayerChatEvent`,
  `PlayerJoinEvent`, `PlayerQuitEvent` y `PlayerDeathEvent`.
- **Reclamar el chat vanilla** — `claim-mode` decide entre cancelar el evento,
  vaciar sus destinatarios o no tocarlo.
- **Traducir a neutral** — delega en `sync-common` la selección de canal, el
  gating de traducción y la construcción de `Message`.
- **Despachar** — usa el `MessageDispatch` publicado por `SuiteRuntime`; no
  formatea, no enruta y no entrega.
- **Publicar el sink local** — registra `LocalSyncSink` (`sync-common`) como
  servicio `SyncSink` con backend `bukkit`; la entrega física la ejecuta el host
  a través del puerto `LocalDelivery`.
- **Entorno y eventos** — `BukkitLocalEnvironment` implementa el puerto neutral
  `LocalEnvironment`: jugadores en línea, mensaje privado, expulsión, comando de
  consola, scoreboard y el evento de chat ligado al dispatch. Las operaciones se
  ejecutan en el hilo principal y se esperan.
- **Ciclo de vida** — registra el listener en `start()` y lo desregistra en
  `stop()` con `HandlerList.unregisterAll`.

## 2. Non-Responsibilities

- No importa features, `host`, `kernel`, `manager` ni otro sincronizador.
- No implementa el sink: publica `LocalSyncSink` de `sync-common`; el fan-out y
  el ciclo de vida de los sinks pertenecen a `sync-bus`. Sí implementa el puerto
  neutral `LocalEnvironment` que ese sink consume.
- No crea el pipeline ni conoce sus etapas.
- No bloquea `start()` esperando red.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `ModuleActivator`, `MessageDispatch`, `PlatformHandle`, `SyncSink` |
| `sync-common` | Compile | `SyncEventProcessor`, `EventRules`, `ChannelSelector`, `LocalSyncSink` |
| `paper-api` | CompileOnly | SDK de Bukkit/Paper |
| `junit` / `mockito` | Test | Listener, activador y configuración |

Se distribuye como `PACKAGE` con `Platform: spigot`. El Manager lo instala solo
en hosts Bukkit; `paper`, `purpur`, `bukkit` y `spigot` se normalizan a la misma
familia.

## 4. Main Components

| Component | Role |
|-----------|------|
| `SyncBukkitActivator` | Resuelve `PlatformHandle`, registra el listener y el sink local, y los retira |
| `BukkitChatListener` | Traduce eventos Bukkit a llamadas neutrales y liga el evento de chat |
| `BukkitLocalEnvironment` | Ejecuta las acciones de entorno en el hilo principal |
| `LocalSyncSink` (`sync-common`) | Sink `bukkit`: LCM, `local.info` y acciones de entorno |
| `SyncBukkitConfig` | `enabled`, `claim-mode`, `log-chat-to-console` |

## 5. Data Flow

```text
AsyncPlayerChatEvent / PlayerJoinEvent / ...
  ↓
BukkitChatListener
  ↓
SyncEventProcessor (sync-common)
  ↓
MessageDispatch.dispatch(Message)
  ↓
SuiteRuntime → Router → ChatDelivery / sinks
```

`start()` no espera red. Los eventos join/quit/death se offloadan al
`Scheduler` del módulo para no bloquear el tick; el chat ya corre en el hilo
async de Bukkit.

## 6. Configuration

Archivo canónico: `config/sync-bukkit/config.yml`.

```yaml
enabled: true
claim-mode: cancel-event   # cancel-event | clear-recipients | none
log-chat-to-console: true
```

Si el archivo no existe, el paquete copia `defaults/config.yml` y sigue con los
valores por defecto. `start()` no falla por configuración ausente.

## 7. Tests

```bash
./gradlew :src:sync-bukkit:test
./gradlew :src:sync-bukkit:modulePackage
```

Cubren los tres modos de claim, el despacho de chat, el offload de eventos
tipados, la desactivación, el registro/desregistro del listener, el binding del
evento de chat y las operaciones de entorno (jugadores, mensaje, expulsión,
consola, scoreboard, cancelación y ejecución fuera del hilo principal). El sink
local se prueba en `sync-common` (`LocalSyncSinkTest`). No hay E2E contra un
servidor Paper real.

## 8. Native actions

`LocalSyncSink` publica el LCM y las acciones de entorno con el namespace
`bukkit`:

| Acción | Kind | Argumentos | Resultado |
|---|---|---|---|
| `local.info` | `READ` | ninguno | `name`, `surfaceVersion`, `localDeliveryRegistered`, `deliverySemantics`, `ordering`, `environmentRegistered`, `eventBound` |
| `player.list` | `READ` | ninguno | lista de jugadores (`uuid`, `name`, `world`) |
| `player.info` | `READ` | `player` | jugador o `NotFound` |
| `player.message` | `WRITE` | `player`, `text` | `Ok` |
| `player.kick` | `DESTRUCTIVE` | `player`, `reason` opcional | `Ok` |
| `console.command` | `DESTRUCTIVE` | `command` | `Ok` o `HostFailure` |
| `scoreboard.set` | `WRITE` | `objective`, `entry`, `value` | `Ok` o `NotFound` |
| `event.info` | `READ` | ninguno | evento ligado o `NotFound` |
| `event.cancel` | `WRITE` | ninguno | `Ok`, `NotFound` o `Unsupported` |

Las acciones nativas exigen `Principal.Program`; `Principal.Official` recibe
`Denied` (solo puede invocar el LCM). Una acción no publicada devuelve
`Unsupported`. `message.send` devuelve `Unsupported` porque el camino local exige
`message.deliver`. `event.cancel` solo cancela eventos Bukkit `Cancellable`.
