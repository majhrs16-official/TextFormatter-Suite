# Config schema — contrato del editor (base 1.0)

> **Estado:** vigente para el Web Editor. **Autoridad:** contrato de import/export del editor. Si
> contradice un ADR aceptado, prevalece el ADR y este documento se corrige. **Ámbito:** proyecto
> portable del editor y su correspondencia con el layout de runtime. **Obligatoriedad:** las reglas
> de «Round-trip» y la tabla de correspondencia son normativas; los ejemplos son ilustrativos.
>
> El sufijo `v2.2` del nombre del archivo identifica la revisión del schema del editor, no una
> versión publicada de la suite. `1.0.0` es la etiqueta de release de la base y `1.0` la versión de
> los esquemas YAML.

## 1. Proyecto del editor vs layout de runtime

El editor trabaja con un **proyecto portable** (`textformatter-suite.zip`). Ese ZIP **no es** el
layout de disco del runtime: es un artefacto de import/export. El layout canónico de runtime está
en `docs/prompts/diseno/storage.md`.

| Archivo del proyecto | Destino canónico en runtime | Dueño |
|---|---|---|
| `config.yml` | `config/suite/config.yml` | `host` |
| `commands.yml` | `config/suite/commands.yml` | `suite-runtime` |
| `channels/<canal>.yml` | `config/textformatter/channels/<canal>.yml` | `textformatter` |
| `rules.yml` | `config/iflow-firewall/rules.yml` | `iflow-firewall` |
| `translators/<provider>.yml` | `config/chattranslator/translators/<provider>.yml` | `chattranslator` |
| `sync/discord.yml` | `config/sync-discord/config.yml` | `sync-discord` |
| `sync/telegram.yml` | `config/sync-telegram/config.yml` | `sync-telegram` |
| `sync/http.yml` | `config/sync-http/config.yml` | `sync-http` |
| `sync/tcp-udp.yml` | `config/sync-socket/tcp.yml` + `udp.yml` | `sync-socket` |
| `sync/velocity.yml` | `config/sync-velocity-core/config.yml` | `sync-velocity-core` |
| `manifest.json` | No se copia al runtime; es metadata del proyecto | Editor |

El host **no** parsea el ZIP ni las rutas planas del proyecto. El Manager/runtime materializa cada
archivo en su ruta canónica. Un proyecto con rutas no canónicas se rechaza; no se migra en runtime.

## 2. `config.yml` (claves del host)

```yaml
quick-look: true              # BOOLEAN (el host usa bool(); false-strings NO valen)
general:
  language: en                # 'en' | 'es' | 'pt' | 'auto'...
iflow:
  engine:
    parallel: false
sonido:
  enabled: true
chat:
  claim-mode: cancel-event     # cancel-event | clear-recipients | none
  log-to-console: true
repositories: []
module-allowlist: []
config-version: 1.0
```

`iflow.engine.kind` no forma parte de este contrato. La selección de motor vive en
`config/iflow/config.yml` (hub iFlow):

```yaml
engine: iflow-firewall          # iflow-firewall | iflow-vm | iflow-wasm
fallback-on-unavailable: true
config-version: 1.0
```

Reglas: claves opcionales, desconocidas ignoradas por el host (degradan). Defaults del editor =
`HostConfig.defaults()`: quick-look `true`, language `en`, parallel `false`, sonido `true`.

## 3. `channels/<id>.yml` (claves del host)

```yaml
name: chat.global              # fallback del host = nombre de archivo
type: CHAT                     # CHAT (mensajes jugadores) | EVENT (join/quit/death/advancement)
permission: cht.chat.global    # permiso BASE
send-permission: cht.chat.global.send
receive-permission: cht.chat.global.receive
show-sender: true
rate-limit-per-second: 0
lang-source: auto
lang-target: auto
messages:
  - "<green>💬 %content%</green>"
  - "&7👉 &f%player_name%&7: %content%"
tooltips:
  - "Hover: %lang_source% → %lang_target%"
sounds:
  - name: ping-message.mp3
    volume: 1.0
    pitch: 1.0
config-version: 1.0
```

Regla de identidad: `name` **es el id**. Renombrar propaga a `rules.yml` (nodos) y a los mapeos de
sync.

**`type`** (opcional, default `CHAT`): solo `CHAT` y `EVENT`. No hay `SYSTEM`, `LOG`, `WORLD`,
`RADIUS` ni `PRIVATE`; esos comportamientos se modelan con canales `EVENT` o con el pipeline.

## 4. `rules.yml` (grafo del editor)

```yaml
guard:
  max-steps: 512
filter:
  dedup-fanout: true
priority: batch-first            # BFS por capas desde nodos de entrada
nodes:
  - id: n_chat.global
    kind: input                  # 'input'|'cond'|'transform'|'loop'|'sleep'|'output'|'redirect'
    label: chat.global           # canal si kind=input/output
    matcher: {}                  # condición: canal/emisor/receptor/dirección
    transforms:
      - op: rewrite
        template: "<green>💬 %content%</green>"
      - op: sounds
        add: [ping-message.mp3, notification.ogg]
        remove: [alert.wav]
      - op: sleep
        millis: 1500
    target:
      channel: staff.alert
edges:
  - from: n_chat.global
    to: n_cond
  - from: n_loop                 # arco de retorno (ciclo permitido)
    to: n_cond
config-version: 1.0
```

Semántica: entradas = mux; salidas = fan-out; condición = filtro por camino; ciclos permitidos con
límite `guard.max-steps` (DROP + log al superar).

**Alcance:** el motor `iflow-firewall` **valida** este grafo, pero no lo ejecuta. El esquema
ejecutable de reglas SpEL vive en el mismo archivo y solo se usa cuando el hub activa el backend
`iflow-firewall`. `iflow-vm` e `iflow-wasm` no consumen este grafo.

## 5. `translators/<provider>.yml`

```yaml
# translators/google.yml
provider: google
active: true
config-version: 1.0

# translators/libre.yml
provider: libre
active: false
base-url: https://libretranslate.example
api-key: ""                     # vacío = anónimo
pool:
  max-concurrent: 6
config-version: 1.0
```

## 6. `sync/*.yml`

```yaml
# discord.yml → config/sync-discord/config.yml
enabled: true
token: ""                       # nunca vacío si enabled (validación bloqueante)
channel: 1234567890             # canal remoto → local (name del channel)
intents: [GUILD_MESSAGES, MESSAGE_CONTENT]
config-version: 1.0

# telegram.yml → config/sync-telegram/config.yml
# http.yml     → config/sync-http/config.yml
# tcp-udp.yml  → config/sync-socket/tcp.yml + udp.yml
# velocity.yml → config/sync-velocity-core/config.yml
```

Las rutas planas `sync/` son del proyecto del editor, no del runtime. El runtime solo lee
`config/sync-*/`.

## 7. `manifest.json`

```json
{
  "schema": "2.2",
  "suite-version": "1.0.0",
  "config-version": "1.0",
  "generated-at": "…",
  "capabilities": { "transforms": false },
  "validation": { "errors": 0, "warnings": 0, "blocking": false, "issues": [] }
}
```

## 8. Reglas de round-trip

1. El editor emite con su propio writer; su parser lo re-lee idéntico.
2. `config.yml` y `channels/*.yml` deben ser parsables por el host en su ruta canónica (test:
   `ConfigLoaderTest`, `ChannelConfigTest`). Un YAML con `:` en templates debe citar el valor.
3. Import acepta cualquier proyecto exportado por este editor; campos faltantes = defaults; campos
   desconocidos se **conservan** (no se tiran).
4. Todo `.yml` exportado declara `config-version: 1.0`. Si falta, el runtime falla con
   `version de configuracion no identificada (archivo editado o corrupto?)`; el editor lo reporta
   como issue bloqueante antes de exportar.
5. El editor no escribe en `config/manager/`, `manager/config/`, `modules/` ni en la raíz plana del
   data folder.
