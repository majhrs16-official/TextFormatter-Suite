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
- **Ciclo de vida** — en `start()` registra los callbacks; en `stop()` los deja
  inertes. Fabric API no permite desregistrarlos, así que un unload en caliente
  marca `RESTART_REQUIRED`. En un apagado completo del servidor no se marca
  error.

## 2. Non-Responsibilities

- No importa features, `host`, `kernel`, `manager` ni otro sincronizador.
- No es un `SyncSink`: no hace fan-out; eso pertenece a `sync-bus`.
- No cancela el chat vanilla: Fabric API no expone un evento cancelable en el
  baseline usado por este paquete.
- No crea `SuiteHost` ni conoce el pipeline.
- No bloquea `start()` esperando red.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `ModuleActivator`, `MessageDispatch`, `PlatformHandle` |
| `sync-common` | Compile | `SyncEventProcessor`, `EventRules`, `ChannelSelector` |
| `fabric-loader` | Mod compile | Entrypoint y `MinecraftServer` |
| `fabric-api` | Mod compile | Eventos de mensaje, conexión y tick |
| `minecraft` / `yarn` | Loom | SDK de Minecraft |
| `junit` / `assertj` | Test | Configuración y tracker de muerte |

Se distribuye como `PACKAGE` con `Platform: fabric`. El JAR distribuido es el
resultado de `remapJar`; el JAR fino de `jar` no se publica.

## 4. Main Components

| Component | Role |
|-----------|------|
| `SyncFabricActivator` | Resuelve `PlatformHandle`, registra callbacks y aplica `RESTART_REQUIRED` |
| `FabricEventListener` | Traduce eventos Fabric a llamadas neutrales |
| `FabricDeathTracker` | Estado de una emisión por muerte |
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

Cubren configuración y el tracker de muerte. La compilación valida el uso real
de la API Fabric/Minecraft. No hay E2E contra un servidor Fabric real.
