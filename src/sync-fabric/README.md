# sync-fabric — captura de eventos Fabric

> **Purpose**: paquete de plataforma descargable. Captura chat, join, quit y
> death en Fabric, los traduce a `Message` y los despacha por
> `MessageDispatch`. No vive dentro del JAR de `fabric-host`.

---

## 1. Responsibilities

- **Callbacks Fabric** — `FabricEventListener` registra
  `ServerMessageEvents.CHAT_MESSAGE`, `ServerPlayConnectionEvents.JOIN`,
  `ServerPlayConnectionEvents.DISCONNECT` y `ServerTickEvents.END_SERVER_TICK`.
- **Detección de muerte** — `FabricDeathTracker` emite una sola vez por muerte
  hasta que el jugador reaparece.
- **Traducir a neutral** — delega en `sync-common` la selección de canal, el
  gating de traducción y la construcción de `Message`.
- **Despachar** — usa el `MessageDispatch` publicado por `SuiteRuntime`.
- **Publicar el sink local** — registra `LocalSyncSink` (`sync-common`) como
  servicio `SyncSink` con backend `fabric`; la entrega física la ejecuta el host
  a través del puerto `LocalDelivery`.
- **Ciclo de vida** — en `start()` registra los callbacks; en `stop()` los deja
  inertes. Fabric API no permite desregistrarlos, así que un unload en caliente
  marca `RESTART_REQUIRED`. En un apagado completo del servidor no se marca
  error.

## 2. Non-Responsibilities

- No importa features, `host`, `kernel`, `manager` ni otro sincronizador.
- No implementa el sink local: publica `LocalSyncSink` de `sync-common`; el
  fan-out y el ciclo de vida de los sinks pertenecen a `sync-bus`.
- No cancela el chat vanilla: Fabric API no expone un evento cancelable en el
  baseline usado por este paquete.
- No crea el pipeline ni conoce sus etapas.
- No bloquea `start()` esperando red.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `ModuleActivator`, `MessageDispatch`, `PlatformHandle`, `SyncSink` |
| `sync-common` | Compile | `SyncEventProcessor`, `EventRules`, `ChannelSelector`, `LocalSyncSink` |
| `fabric-loader` | Mod compile | Entrypoint y `MinecraftServer` |
| `fabric-api` | Mod compile | Eventos de mensaje, conexión y tick |
| `minecraft` / `yarn` | Loom | SDK de Minecraft |
| `junit` / `assertj` | Test | Configuración y tracker de muerte |

Se distribuye como `PACKAGE` con `Platform: fabric`. El JAR distribuido es el
resultado de `remapJar`; el JAR fino de `jar` no se publica.

## 4. Main Components

| Component | Role |
|-----------|------|
| `SyncFabricActivator` | Resuelve `PlatformHandle`, registra callbacks y el sink local, y aplica `RESTART_REQUIRED` |
| `FabricEventListener` | Traduce eventos Fabric a llamadas neutrales |
| `FabricDeathTracker` | Estado de una emisión por muerte |
| `LocalSyncSink` (`sync-common`) | Sink `fabric`: LCM más `local.info`; entrega por `LocalDelivery` |
| `SyncFabricConfig` | `enabled`, `log-chat-to-console` |

## 5. Data Flow

```text
CHAT_MESSAGE / JOIN / DISCONNECT / END_SERVER_TICK
  ↓
FabricEventListener
  ↓
SyncEventProcessor (sync-common)
  ↓
MessageDispatch.dispatch(Message)
  ↓
SuiteRuntime → Router → ChatDelivery / sinks
```

Los callbacks offloadan el despacho al `Scheduler` del módulo para no bloquear
el tick del servidor. `start()` no espera red.

## 6. Configuration

Archivo canónico: `config/sync-fabric/config.yml`.

```yaml
enabled: true
log-chat-to-console: true
```

Si el archivo no existe, el paquete copia `defaults/config.yml` y sigue con los
valores por defecto.

## 7. Tests

```bash
./gradlew :src:sync-fabric:test
./gradlew :src:sync-fabric:modulePackage
```

Cubren configuración y el tracker de muerte. El sink local se prueba en
`sync-common` (`LocalSyncSinkTest`). La compilación valida el uso real de la API
Fabric/Minecraft. No hay E2E contra un servidor Fabric real.

## 8. Native actions

`LocalSyncSink` publica el LCM más `local.info` con el namespace `fabric`:

| Acción | Kind | Argumentos | Resultado |
|---|---|---|---|
| `fabric.local.info` | `READ` | ninguno | `Ok` con `name`, `surfaceVersion`, `localDeliveryRegistered`, `deliverySemantics`, `ordering` |

`local.info` es de solo lectura, idempotente, no recibe argumentos y funciona
antes de `start()` porque el sink local no abre ninguna conexión. Nunca expone el
puerto `LocalDelivery` ni un sobre; solo informa si el host ya registró el
puerto. `Principal.Official` recibe `Denied` (solo puede invocar el LCM) y una
acción no publicada devuelve `Unsupported`. `message.send` devuelve
`Unsupported` porque el camino local exige `message.deliver`.
