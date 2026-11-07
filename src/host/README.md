# host — Pipeline neutral, despacho y configuración

> **Purpose**: núcleo neutral que `SuiteRuntime` usa para construir el pipeline
> de mensajes. Ya no arranca módulos ni conoce features: recibe los servicios
> resueltos desde el `ServiceRegistry` del runtime.

---

## 1. Responsibilities

- **Pipeline** — `PipelineEngine` ejecuta la lista ordenada de `PipelineStage`
  en tres fases (`MESSAGE_PRE`, `RECIPIENT`, `MESSAGE_POST`) y produce un
  `PipelineOutcome` con estado, contadores y traza.
- **Message dispatch** — `RecipientResolver` expande `Direction` con
  `ActorDirectory`; `DeliveryExecutor` aplica `sleepMillis`, sonidos y
  `ChatDelivery`.
- **Configuration loading** — `ConfigLoader` lee `config/suite/config.yml` y
  produce `HostConfig`; `HostPaths` resuelve el layout canónico del host
  (`config/suite/`) y el de los módulos (`config/<module>/`).
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
| `suite-runtime` | Construye `PipelineEngine` y sus colaboradores desde las etapas activas |
| `observability` | Consulta métricas del pipeline |
| `host` tests / `loadtest` | Usan `HostFixture` para cablear etapas reales en pruebas |

## 5. Main Components

| Component | Role |
|-----------|------|
| `PipelineEngine` | Orden, fases, expansión por destinatario, entrega y traza |
| `RecipientResolver` | Expande `Direction` con `ActorDirectory` y políticas fail-closed |
| `DeliveryExecutor` | `sleepMillis`, sonidos y llamadas a `ChatDelivery` |
| `PipelineOutcome` | Estado, contadores y traza de un despacho |
| `ConfigLoader` | Carga `HostConfig` desde YAML |
| `HostPaths` | `config/suite/` para el host y `config/<module>/` para módulos; sin fallback a la raíz plana |
| `MessagesConfig` | Catálogo de mensajes con defaults empaquetados |
| `ConfigSchemaGenerator` | Esquema para el editor web |
| `DebugEndpoint` | Endpoint HTTP de simulación/estado |

## 6. Data Flow

```text
SuiteRuntime
  ├─ ServiceRegistry: ChannelRegistry, TranslationService, Router,
  │                   ChatDelivery, ActorDirectory, PermissionChecker
  ↓
PipelinePlan + etapas publicadas (route, format, sync)
  ↓
PipelineEngine.dispatch(Message)
  ↓
MESSAGE_PRE → RecipientResolver → RECIPIENT (route → format) → DeliveryExecutor → MESSAGE_POST (sync)
  ↓
PipelineOutcome (status, considered, delivered, silenced, redirected, failed, trace)
```

## 7. Entry points

- `SuiteRuntime` construye el motor tras activar los paquetes.
- `PipelineEngine.dispatch(...)` es el punto de entrada de mensajes ya normalizados.
- `DebugEndpoint` es el punto de entrada HTTP de diagnóstico.

## 8. Exploration path

1. `PipelineEngine.java`
2. `RecipientResolver.java` y `DeliveryExecutor.java`
3. `PipelineOutcome.java` y `BoundedPipelineTrace.java`
4. `config/ConfigLoader.java` y `config/HostPaths.java`
5. `config/MessagesConfig.java`
6. `debug/DebugEndpoint.java`

## 9. Related modules

- [core-api](../core-api/README.md)
- [core-render](../core-render/README.md)
- [suite-runtime](../suite-runtime/README.md)
- [spigot-host](../spigot-host/README.md)
- [fabric-host](../fabric-host/README.md)
