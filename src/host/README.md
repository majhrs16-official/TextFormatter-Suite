# host — Pipeline neutral, despacho y configuración

> **Purpose**: núcleo neutral que `SuiteRuntime` usa para construir el pipeline
> de mensajes. Ya no arranca módulos ni conoce features: recibe los servicios
> resueltos desde el `ServiceRegistry` del runtime.

---

## 1. Responsibilities

- **Pipeline** — `PipelineEngine` ejecuta la lista ordenada de `PipelineStage`
  en tres fases (`MESSAGE_PRE`, `RECIPIENT`, `MESSAGE_POST`) y produce un
  `PipelineOutcome` con estado, contadores y traza. `OutputPlanResolver` fija
  el plan de salidas efectivo una vez por emisión (mensaje → suite → default).
- **Message dispatch** — `RecipientResolver` expande `Direction` con
  `ActorDirectory`; `DeliveryExecutor` aplica `sleepMillis`, sonidos y
  `ChatDelivery`, y encola las entregas limitadas por `RATE_LIMIT` con un
  backoff acotado.
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
| `OutputPlanResolver` | Precedencia mensaje → `config/suite/config.yml` → default del módulo |
| `RecipientResolver` | Expande `Direction` con `ActorDirectory` y políticas fail-closed |
| `DeliveryExecutor` | `sleepMillis`, sonidos, llamadas a `ChatDelivery` y cola acotada de `RATE_LIMIT` |
| `BoundedRateLimitQueue` | Backoff no bloqueante, capacidad máxima y descarte explícito |
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
OutputPlanResolver (mensaje → suite → default) → PipelineContext.outputs()
  ↓
MESSAGE_PRE → RecipientResolver → RECIPIENT (route → format) → DeliveryExecutor → MESSAGE_POST (sync)
  ↓
PipelineOutcome (status, considered, delivered, silenced, redirected, failed,
                queued, discarded, trace)
```

### Rate-limited delivery

`RouteDecision.RATE_LIMIT` does not drop the unit on the floor. `DeliveryExecutor`
schedules it after `backoffMillis` on a single daemon scheduler shared with
`sleepMillis`. `BoundedRateLimitQueue` caps pending deliveries (750 by default)
and releases its permit when the task runs, so a burst cannot grow the host heap
without limit. When the queue is full, the unit is reported as `DISCARDED` and
counted in `DeliverySummary.discarded`; it is never counted as delivered. A
zero backoff is delivered synchronously. The capacity is a host safety limit,
not the 64 KB VM budget: each queued entry keeps the rendered component and the
per-recipient message.

## 7. Entry points

- `SuiteRuntime` construye el motor tras activar los paquetes.
- `PipelineEngine.dispatch(...)` es el punto de entrada de mensajes ya normalizados.
- `DebugEndpoint` es el punto de entrada HTTP de diagnóstico.

## 8. Exploration path

1. `PipelineEngine.java`
2. `OutputPlanResolver.java`
3. `RecipientResolver.java` y `DeliveryExecutor.java`
4. `PipelineOutcome.java` y `BoundedPipelineTrace.java`
5. `config/ConfigLoader.java` y `config/HostPaths.java`
6. `config/MessagesConfig.java`
7. `debug/DebugEndpoint.java`

## 9. Related modules

- [core-api](../core-api/README.md)
- [core-render](../core-render/README.md)
- [suite-runtime](../suite-runtime/README.md)
- [spigot-host](../spigot-host/README.md)
- [fabric-host](../fabric-host/README.md)
