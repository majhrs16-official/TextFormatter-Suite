# host — Pipeline neutral, despacho y configuración

> **Purpose**: núcleo neutral que `SuiteRuntime` usa para construir el pipeline
> de mensajes. Ya no arranca módulos ni conoce features: recibe los servicios
> resueltos desde el `ServiceRegistry` del runtime.

---

## 1. Responsibilities

- **Pipeline** — `SuiteHost` combina `ChannelRegistry`, `TranslationService`,
  `Router`, `MessageRenderer`, `MessageComponentRenderer`, `PluginLogger` y
  `ChatDelivery`.
- **Message dispatch** — `MessageDispatcher` enruta, renderiza, traduce,
  entrega al destinatario y publica copias de auditoría.
- **Configuration loading** — `ConfigLoader` lee `config/suite/config.yml` y
  produce `HostConfig`; `HostPaths` resuelve el layout canónico.
- **Message catalog** — `MessagesConfig` carga `config/suite/messages.yml` con
  fallback a los recursos.
- **Schema generation** — `ConfigSchemaGenerator` genera el esquema del editor
  web desde las rutas de configuración.
- **Debug endpoint** — `DebugEndpoint` expone simulación y estado para
  diagnóstico.

## 2. Non-responsibilities

- **No arranca paquetes** — eso lo hace `SuiteRuntime`/`PackageRuntime`.
- **No construye features** — no importa `textformatter`, `chattranslator`,
  `iflow-*`, `transport` ni `kernel`.
- **No registra comandos ni listeners de plataforma** — eso pertenece a
  `suite-runtime`, `sync-bukkit`/`sync-fabric` y a los adapters.
- **No valida configuración de features** — cada paquete valida la suya.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | Tipos neutrales, SPIs y contratos |
| `core-render` | Compile | `ChatDelivery`, `MessageComponentRenderer` y Adventure |
| `snakeyaml` | Compile | Lectura de YAML |
| `gson` | Compile | Generación del esquema del editor |
| `textformatter`, `chattranslator`, `iflow-*`, `transport` | Test only | Fixtures de `HostFixture`; no están en producción |

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `suite-runtime` | Construye `SuiteHost` desde los servicios activos |
| `observability` | Consulta métricas del pipeline |
| `host` tests / `loadtest` | Usan `HostFixture` para cablear features reales en pruebas |

## 5. Main Components

| Component | Role |
|-----------|------|
| `SuiteHost` | Facade del pipeline; constructor neutral y `deliver(...)` |
| `MessageDispatcher` | Route → format → translate → sync → deliver |
| `RoutingResult` | Resultado de una entrega (entregado, silenciado, redirigido) |
| `DispatchReport` | Contadores de una operación de despacho |
| `ConfigLoader` | Carga `HostConfig` desde YAML |
| `HostPaths` | Layout canónico `config/<paquete>/...` con fallback legacy |
| `MessagesConfig` | Catálogo de mensajes con defaults empaquetados |
| `ConfigSchemaGenerator` | Esquema para el editor web |
| `DebugEndpoint` | Endpoint HTTP de simulación/estado |

## 6. Data Flow

```text
SuiteRuntime
  ├─ ServiceRegistry: ChannelRegistry, TranslationService, Router,
  │                   MessageRenderer, MessageComponentRenderer,
  │                   ChatDelivery, ActorDirectory, PermissionChecker
  ↓
new SuiteHost(config, channels, translation, router, renderers, logger, delivery)
  ↓
host.deliver(Message, recipient)
  ↓
Router.route(...) → MessageDispatcher
  ↓
MessageRenderer / MessageComponentRenderer
  ↓
ChatDelivery.deliver(...) / deliverConsole(...)
```

## 7. Entry points

- `SuiteRuntime` construye el host tras activar los paquetes.
- `MessageDispatcher` es el punto de entrada de mensajes ya normalizados.
- `DebugEndpoint` es el punto de entrada HTTP de diagnóstico.

## 8. Exploration path

1. `SuiteHost.java`
2. `MessageDispatcher.java`
3. `RoutingResult.java` y `DispatchReport.java`
4. `config/ConfigLoader.java` y `config/HostPaths.java`
5. `config/MessagesConfig.java`
6. `debug/DebugEndpoint.java`

## 9. Related modules

- [core-api](../core-api/README.md)
- [core-render](../core-render/README.md)
- [suite-runtime](../suite-runtime/README.md)
- [spigot-host](../spigot-host/README.md)
- [fabric-host](../fabric-host/README.md)
