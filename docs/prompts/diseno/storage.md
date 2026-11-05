# Estructura de Almacenamiento (Storage Layout)

> **Fuente de verdad.** Este documento define el layout canónico de disco de TextFormatter Suite:
> rutas, propiedad, precedencia y ciclo de vida. Si el código diverge, el código es el bug; no se
> añade un lector alternativo para acomodarlo.
>
> **Principio.** Cada módulo (`src/<modulo>/`) es dueño de su directorio `config/<modulo>/`. El
> host/plataforma solo gestiona `config/suite/` y delega el resto. El Manager es dueño de
> `manager/`.
>
> **Régimen pre-1.0 (ADR-034).** No hay rutas legacy, aliases, dobles lectores ni migraciones en
> runtime. Una ruta no canónica se ignora o se rechaza explícitamente. Todo `.yml` declara
> `config-version: 1.0` en la raíz, preferentemente al final; `plugin.yml` es la única excepción
> porque es formato propio de Bukkit.

---

## 1. Terminología: módulo vs submódulo

| Término | Definición | Ejemplos |
|---|---|---|
| **Módulo** | Aplicación con responsabilidad cohesiva, ciclo de vida propio y registro vía SPI (`ModuleDescriptor`). | `chattranslator`, `sync-bus`, `textformatter`, `iflow`, `host`, `observability` |
| **Submódulo** | Especialización que extiende un módulo central y se registra como proveedor de su capability. No existe sin el módulo padre. | `gtranslate`/`ltranslate` (de `chattranslator`), `sync-discord`/`sync-telegram` (de `sync-bus`) |

Reglas:

1. Un módulo declara `provide(Capability.of("<nombre>", ...))`.
2. Un submódulo declara `require(...)` del padre y `provide(...)` de su propia capability.
3. Cada módulo y submódulo es dueño de `config/<id>/config.yml` (o de sus subarchivos).
4. El módulo padre solo coordina; no parsea la configuración de sus submódulos.

---

## 2. Layout canónico

```text
<data>/                                  ← data folder del plugin/mod (plugins/TextFormatterSuite)
├── config/
│   ├── suite/                           ← configuración global del host
│   │   ├── config.yml
│   │   ├── commands.yml
│   │   └── messages.yml
│   ├── iflow/                           ← hub iFlow (selección de motor)
│   │   └── config.yml
│   ├── iflow-firewall/                  ← motor SpEL legacy
│   │   └── rules.yml
│   ├── textformatter/
│   │   ├── config.yml
│   │   └── channels/
│   │       ├── chat.global.yml
│   │       ├── join.yml
│   │       └── ...
│   ├── chattranslator/
│   │   ├── config.yml
│   │   └── translators/
│   │       ├── google.yml
│   │       └── libre.yml
│   ├── sync-bus/config.yml
│   ├── sync-discord/config.yml
│   ├── sync-websocket/config.yml
│   ├── sync-telegram/config.yml
│   ├── sync-http/config.yml
│   ├── sync-socket/
│   │   ├── tcp.yml
│   │   └── udp.yml
│   ├── sync-velocity-core/config.yml
│   ├── inworld/config.yml
│   └── observability/config.yml
│
├── manager/                             ← estado del Manager (ADR-027)
│   ├── repo.yml
│   ├── runtime/
│   └── library/
│       ├── packages/<packageId>/<version>/...
│       ├── dependencies/<dependencyId>/<version>/...
│       ├── status
│       ├── cache/
│       │   ├── downloads/<sha256>/
│       │   └── operations/<operationId>.json
│       ├── journal/<operationId>.log
│       ├── backup/<operationId>/
│       ├── trash/<operationId>/
│       ├── lists/
│       └── tmp/<operationId>/
│
├── storage.yml                          ← UserLanguageStore (uuid → idioma)
└── suite.log                            ← log de la suite (también /tmp/TextFormatter-Suite.log como fallback)
```

No existe `config/manager/`, `manager/config/`, `modules/` ni un `config.yml` plano en la raíz del
data folder. Esas rutas no son alternativas válidas.

---

## 3. Configuración global del host

### 3.1 `config/suite/config.yml`

Dueño: `host` (`ConfigLoader` → `HostConfig`).

```yaml
quick-look: true
general:
  language: en
iflow:
  engine:
    parallel: false
sonido:
  enabled: true
chat:
  claim-mode: CANCEL_EVENT        # CANCEL_EVENT | CLEAR_RECIPIENTS | NONE
  log-to-console: true
repositories:
  - name: official
    url: https://github.com/majhrs16-official/TextFormatter-Suite/releases
    type: github
    enabled: true
module-allowlist: []
config-version: 1.0
```

`iflow.engine.kind` no existe. La selección de motor pertenece al hub (`config/iflow/config.yml`,
§4.1). `iflow.engine.parallel` es una opción del pipeline, no un selector de motor.

### 3.2 `config/suite/commands.yml`

Dueño: `suite-runtime` (`CommandRootConfig`).

```yaml
root: [suite]
aliases:
  - [txf]
config-version: 1.0
```

Solo `root` y `aliases`. `base-name`, `actions` y `commands` están retirados y se ignoran.

### 3.3 `config/suite/messages.yml`

Dueño: el adaptador de plataforma (`spigot-host`/`fabric-host`, `MessagesConfig`). Contiene los
strings de UI de los comandos y prefijos; no pertenece al core ni a módulos compartidos.

---

## 4. Configuración por módulo

### 4.1 `config/iflow/config.yml` — hub iFlow

Dueño: `iflow` (`IflowConfig`). Selecciona el backend activo entre los motores instalados; los
motores pueden coexistir (ADR-032).

```yaml
engine: iflow-firewall            # iflow-firewall | iflow-vm | iflow-wasm
fallback-on-unavailable: true
config-version: 1.0
```

Solo se aceptan los nombres canónicos. El valor por defecto es `iflow-firewall`. Si el backend
configurado no está disponible y el fallback está activo, el hub usa el primer backend disponible
según la prioridad fijada en ADR-032.

### 4.2 `config/iflow-firewall/rules.yml` — motor SpEL legacy

Dueño: `iflow-firewall` (`RuleConfigLoader`, `FirewallConfig`). Solo participa cuando el hub activa
este backend.

El archivo admite dos esquemas:

```yaml
# Esquema ejecutable del motor SpEL
- id: drop-private
  target: DROP
  channel: private
  condition: "#content.length() > 0"
  action: "#surface.cancel()"
```

```yaml
# Grafo del editor: se valida, no se ejecuta
guard:
  max-steps: 512
filter:
  dedup-fanout: true
  priority: batch-first
priority: batch-first
nodes: []
edges: []
```

### 4.3 `config/textformatter/channels/*.yml` — canales

Dueño: `textformatter` (`ChannelConfig`). Un archivo por canal.

```yaml
name: chat.global
permission: cht.chat.global
send-permission: cht.chat.global.send
receive-permission: cht.chat.global.receive
type: CHAT                        # CHAT | EVENT
show-sender: true
rate-limit-per-second: 5
lang-source: auto
lang-target: auto
messages: []
tooltips: []
sounds: []
config-version: 1.0
```

### 4.4 `config/chattranslator/` — fachada y backends

Dueño: `chattranslator`.

```yaml
# config/chattranslator/config.yml
default-backend: google
context-override-enabled: true
cache-max-size: 10000
detection-cache-max-size: 5000
translation-timeout-ms: 30000
config-version: 1.0
```

```yaml
# config/chattranslator/translators/google.yml
provider: google
active: true
timeout: 10
config-version: 1.0
```

Los backends se descubren por `ServiceLoader<TranslatorProvider>`. Añadir un backend no requiere
tocar el host.

### 4.5 Sinks de sincronización

Cada submódulo es dueño de su archivo. Ejemplos mínimos:

```yaml
# config/sync-bus/config.yml
default-timeout-ms: 5000
max-retries: 3
backoff-base-ms: 1000
max-concurrent-deliveries: 100
config-version: 1.0
```

```yaml
# config/sync-discord/config.yml
enabled: true
token: ""
channels: []
config-version: 1.0
```

```yaml
# config/sync-socket/tcp.yml
enabled: false
host: 127.0.0.1
port: 9093
config-version: 1.0
```

`sync-velocity-core` usa `config/sync-velocity-core/config.yml`. El schema exacto de cada sink vive
en su módulo y en su README; este documento fija la ruta y la propiedad.

### 4.6 `config/inworld/config.yml` y `config/observability/config.yml`

Dueños: `inworld` y `observability`. Cada módulo parsea su propio archivo; no hay un lector central
de configuración de módulos.

### 4.7 `coretranslator`

No tiene archivo YAML. Es una expansión PlaceholderAPI (`%cot_*`) que se configura desde
PlaceholderAPI/otros plugins; su estado es en memoria y no persiste configuración propia.

---

## 5. Datos persistentes y logs

| Ruta | Dueño | Contenido |
|---|---|---|
| `<data>/storage.yml` | `suite-runtime` (`YamlUserLanguageStore`) | Idioma por UUID. Lectura por `lastModified`; escritura atómica (temp + move). |
| `<data>/suite.log` | `logger` | Log de la suite. Fallback: `/tmp/TextFormatter-Suite.log`. |
| `<data>/manager/library/` | Manager | Estado, artefactos, caché, journal, backup y trash de paquetes. |

No se usa `data/languages.yml`, `data/cache/` ni `data/journal/` como rutas canónicas.

---

## 6. Manager

El Manager no usa `modules/` ni un `status` en la raíz. Su layout es el de ADR-027:

- `manager/repo.yml`: fuentes de paquetes.
- `manager/library/status`: única fuente de verdad del estado local.
- `manager/library/packages/<packageId>/<version>/`: artefactos instalados.
- `manager/library/dependencies/<dependencyId>/<version>/`: dependencias compartidas.
- `manager/library/cache/downloads/<sha256>/`: caché content-addressed.
- `manager/library/journal/<operationId>.log`: journal durable del commit.
- `manager/library/backup/<operationId>/` y `trash/<operationId>/`: recuperación y retirada.
- `manager/runtime/`: estado de ejecución del Manager.

El commit de archivos es la única etapa con rollback automático; un fallo de activación no revierte
la instalación (ADR-027).

---

## 7. Ciclo de vida

### 7.1 Arranque

1. El adaptador de plataforma resuelve `<data>` e instala los defaults que falten.
2. El host carga `config/suite/config.yml`, `commands.yml` y `messages.yml`.
3. El runtime descubre módulos y resuelve el grafo de dependencias.
4. Cada módulo carga su `config/<modulo>/` cuando se activa.
5. El hub iFlow lee `config/iflow/config.yml` y selecciona el backend.

### 7.2 Recarga

`/suite reload` cierra el runtime, vuelve a resolver el grafo y reconstruye el estado desde disco.
Los archivos son la fuente de verdad; no hay estado de configuración paralelo en memoria.

### 7.3 Presets

El paquete `presets` aplica un layout canónico y recarga los módulos activos. No escribe en rutas
legacy ni en `config/manager/`.

---

## 8. Convenciones de nombres

| Elemento | Convención | Ejemplo |
|---|---|---|
| Directorio de módulo/submódulo | `kebab-case` | `textformatter`, `sync-bus`, `sync-discord` |
| Config principal | `config.yml` | `config/chattranslator/config.yml` |
| Colección de canales | `<canal>.yml` | `config/textformatter/channels/chat.global.yml` |
| Backends | `<provider>.yml` | `config/chattranslator/translators/google.yml` |
| JAR de módulo | `<modulo>-<version>.jar` | `chattranslator-1.0.0.jar` |
| `config-version` | `1.0` | al final del YAML |

---

## 9. Validación y `config-version`

- Todo `.yml` declara `config-version: 1.0` en la raíz, preferentemente al final.
- Si falta o no coincide, el parser falla con:
  `version de configuracion no identificada (archivo editado o corrupto?)`.
- `plugin.yml` queda excluido.
- Los campos desconocidos se ignoran; los valores faltantes usan defaults. Un cambio de esquema se
  aplica reescribiendo la configuración en el mismo cambio, no con un lector del esquema viejo.
- Cada módulo valida su propio archivo al activarse. Un error se registra y degrada la config, pero
  no se acepta una ruta o un esquema no canónico como fallback.

> **Estado de implementación.** El reset de versiones, la adición de `config-version` y la
> eliminación de los lectores legacy están rastreados en `PLAN.md` (P0.2/P0.3). Este documento fija
> el contrato; mientras esas tareas no cierren, el código puede seguir divergiendo y esa divergencia
> es deuda P0, no una excepción del layout.

---

## 10. Referencias de código

| Componente | Archivo |
|---|---|
| `HostPaths` (rutas canónicas) | `src/host/src/main/java/me/majhrs16/suite/host/config/HostPaths.java` |
| `ConfigLoader` (host) | `src/host/src/main/java/me/majhrs16/suite/host/config/ConfigLoader.java` |
| `CommandRootConfig` | `src/suite-runtime/src/main/java/me/majhrs16/suite/runtime/CommandRootConfig.java` |
| `ChannelConfig` | `src/textformatter/src/main/java/me/majhrs16/suite/textformatter/ChannelConfig.java` |
| `BackendConfig` / `ChatTranslatorConfig` | `src/chattranslator/src/main/java/me/majhrs16/suite/chattranslator/` |
| `IflowConfig` (hub) | `src/iflow/src/main/java/me/majhrs16/suite/iflow/hub/IflowConfig.java` |
| `RuleConfigLoader` / `FirewallConfig` | `src/iflow-firewall/src/main/java/me/majhrs16/suite/iflow/` |
| `SyncConfigPaths` | `src/sync-common/src/main/java/me/majhrs16/suite/synccommon/SyncConfigPaths.java` |
| `YamlUserLanguageStore` | `src/suite-runtime/src/main/java/me/majhrs16/suite/runtime/YamlUserLanguageStore.java` |
| `PackageLayout` (Manager) | `src/manager-core/src/main/java/me/majhrs16/suite/manager/core/PackageLayout.java` |
| `DefaultConfigInstaller` | `src/suite-runtime/src/main/java/me/majhrs16/suite/runtime/DefaultConfigInstaller.java` |
