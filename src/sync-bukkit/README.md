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
- **Ciclo de vida** — registra el listener en `start()` y lo desregistra en
  `stop()` con `HandlerList.unregisterAll`.

## 2. Non-Responsibilities

- No importa features, `host`, `kernel`, `manager` ni otro sincronizador.
- No es un `SyncSink`: no hace fan-out; eso pertenece a `sync-bus`.
- No crea `SuiteHost` ni conoce el pipeline.
- No bloquea `start()` esperando red.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `ModuleActivator`, `MessageDispatch`, `PlatformHandle` |
| `sync-common` | Compile | `SyncEventProcessor`, `EventRules`, `ChannelSelector` |
| `paper-api` | CompileOnly | SDK de Bukkit/Paper |
| `junit` / `mockito` | Test | Listener, activador y configuración |

Se distribuye como `PACKAGE` con `Platform: spigot`. El Manager lo instala solo
en hosts Bukkit; `paper`, `purpur`, `bukkit` y `spigot` se normalizan a la misma
familia.

## 4. Main Components

| Component | Role |
|-----------|------|
| `SyncBukkitActivator` | Resuelve `PlatformHandle`, registra el listener y lo retira |
| `BukkitChatListener` | Traduce eventos Bukkit a llamadas neutrales |
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

Archivo canónico: `config/manager/sync-bukkit/config.yml`.

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
tipados, la desactivación y el registro/desregistro del listener. No hay E2E
contra un servidor Paper real.
