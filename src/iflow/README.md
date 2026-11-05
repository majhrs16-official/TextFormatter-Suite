# iflow — hub de selección de motores

`iflow` es el paquete que publica el `Router` estable del host y elige **un**
backend iFlow entre los instalados:

- `iflow-firewall` — motor de reglas SpEL legacy.
- `iflow-vm` — VM propia (implementación v2 de 16 bits; objetivo v3 de 32 bits).
- `iflow-wasm` — políticas WebAssembly con Chicory.

El host no importa ninguno de esos paquetes. Solo consume `Router` y, para
diagnóstico, `IflowHub`.

## Configuración

Archivo canónico:

```text
<data>/config/iflow/config.yml
```

```yaml
engine: iflow-firewall        # iflow-firewall | iflow-vm | iflow-wasm
fallback-on-unavailable: true # usa el backend disponible según la prioridad explícita
```

`engine` también acepta los alias `firewall`, `vm` y `wasm`, y la clave
`default-backend` como alias de `engine`. Si el archivo falta o es inválido se
usan `iflow-firewall` y `fallback-on-unavailable: true`.

Las rutas `config/manager/iflow/config.yml` y `manager/config/iflow/config.yml`
son **incorrectas**. No son layouts legacy ni versiones alternativas; el
runtime y el instalador las ignoran, no las migran y no las eliminan.

## Contratos

| Tipo | Paquete | Rol |
|------|---------|-----|
| `Router` | `core-api` | Contrato que consume el host para enrutar mensajes |
| `IflowHub` | `core-api` | Fachada del hub: router activo, backends disponibles y `reload()` |
| `IflowBackend` | `core-api` | Servicio que publica cada motor: nombre, router y disponibilidad |

Los motores publican `IflowBackend` en su `ModuleContext`:

```java
context.services().register(IflowBackend.class, backend);
```

El hub los descubre con `ServiceRegistry.findAll(IflowBackend.class)`. No crea
ni cierra motores: cada motor conserva la propiedad de sus recursos. Cuando un
motor se activa o desactiva, el hub se refresca con `ModuleActivatedEvent` y
`ModuleDeactivatedEvent`.

## Selección y fallback

1. Si el backend configurado está disponible, se selecciona.
2. Si no está disponible y `fallback-on-unavailable` es `true`, se selecciona
   el primer backend disponible según la prioridad explícita
   `iflow-firewall` → `iflow-vm` → `iflow-wasm`, independiente del orden de
   registro.
3. Si no hay backend disponible, el hub no publica un router operativo: el
   pipeline del runtime queda sin motor y `IflowHub.router()` devuelve `null`.
   Las llamadas directas al hub fallan cerradas (`REJECT`).

## Integración con el runtime

`SuiteRuntime` resuelve el router desde `IflowHub`; el hub registra además
`Router` para los consumidores que lo resuelvan por tipo.

> **Deuda P0.** El código todavía conserva un fallback a un `Router` publicado
> directamente por un motor cuando el hub no está instalado. Ese camino es
> deuda de la purga pre-1.0 (PLAN P0.3), no un contrato de compatibilidad ni
> un modo soportado.

## Build y tests

```bash
./gradlew :src:iflow:test
./gradlew :src:iflow:build checkArchitecture checkLocks
```

Tests actuales:

- `IflowConfigTest`: defaults, alias, YAML inválido y normalización.
- `IflowHubTest`: registro del hub, refresco por eventos, fallback con prioridad
  explícita, fallo cerrado y delegación al backend activo.
