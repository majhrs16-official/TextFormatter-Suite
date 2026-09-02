# Schema v2.2 — fuente única de verdad (F6)

> El editor importa y exporta contra este schema; el **host** (`ConfigLoader`)
> parsa la misma estructura. Round-trip exacto: panel → YAML → panel sin
> pérdida. Lo que no quepa aquí es falta de precisión del schema o del motor.

## 1. Archivos del proyecto (`textformatter-suite.zip`)

```
config.yml            → HostConfig (idéntico a ConfigLoader.loadConfig)
channels/<canal>.yml  → ChannelRegistry (idéntico a ConfigLoader.loadChannels)
rules.yml             → grafo iFlow (editor/F7+, el motor lo consumirá)
translators/*.yml     → proveedores (google/libre)
sync/discord.yml      sync/telegram.yml  sync/http.yml
sync/tcp-udp.yml      sync/velocity.yml
manifest.json         → versiones + resultado de validación + capabilities
```

## 2. `config.yml` (claves del host, tal cual)

```yaml
quick-look: true              # BOOLEAN (el host usa bool(); false-strings NO valen)
general:
  language: en                # 'en' | 'es' | 'pt' | 'auto'...
iflow:
  engine:
    parallel: false
sonido:
  enabled: true
```

Reglas: claves opcionales, desconocidas ignoradas por el host (degradan).
Reviews/editor usa `HostConfig.defaults()` = quick-look true, en, parallel
false, sonido true.

## 3. `channels/<id>.yml` (claves del host, tal cual)

```yaml
name: chat.global              # fallback del host = nombre de archivo
type: CHAT                     # CHAT (mensajes jugadores) | EVENT (join/quit/death/advancement)
permission: cht.chat.global    # permiso BASE (plugin)
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
```

Regla de identidad: `name` **es el id**. Renombrar propaga a `rules.yml`
(nodos) y a `translators/sync` (mapeos remoto→local).

**`type`** (opcional, default `CHAT`): `CHAT` = canal de mensajes de jugadores (chat normal); `EVENT` = eventos de sistema (join, quit, death, advancement). Solo canales `CHAT` son considerados para mensajes de chat de jugadores.

## 4. `rules.yml` (grafo iFlow → reglas)

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
        add: [ping-message.mp3, notification.ogg]     # multi
        remove: [alert.wav]
      - op: sleep
        millis: 1500
    target:                      # 'redirect'
      channel: staff.alert
edges:
  - from: n_chat.global
    to: n_cond
  - from: n_loop                 # arco de retorno (ciclo permitido)
    to: n_cond
```

Semántica: entradas = mux; salidas = fan-out; condición = filtro por camino;
ciclos permitidos con límite `guard.max-steps` (DROP + log al superar); las
transformaciones `rewrite/sounds/sleep` requieren motor con `transforms=true`
(manifest lo reporta).

## 5. `translators/*.yml`

```yaml
# translators/google.yml
provider: google
active: true

# translators/libre.yml
provider: libre
active: false
base-url: https://libretranslate.example
api-key: ""                     # vacío = anónimo
pool:
  max-concurrent: 6
```

## 6. `sync/*.yml`

```yaml
# discord.yml
enabled: true
token: ""                       # nunca *vacío* si enabled (validación bloqueante)
channel: 1234567890             # canal remoto → local (name del channel)
intents: [GUILD_MESSAGES, MESSAGE_CONTENT]

# telegram.yml   → token, chat-id, hub
# http.yml       → webhook-url, inbound-port, path
# tcp-udp.yml    → protocol, host, outbound-port, inbound-port
# velocity.yml   → enabled, secret, servers[], mapping ("* → chat.hub")
```

## 7. `manifest.json`

```json
{
  "schema": "v2.2",
  "suite-version": "2.1.0",
  "generated-at": "…",
  "capabilities": { "transforms": false },
  "validation": { "errors": 0, "warnings": 0, "blocking": false, "issues": [] }
}
```

## 8. Reglas de round-trip

1. El editor emite con su propio writer; el parser propio lo re-lee idéntico.
2. `config.yml` + `channels/*.yml` **parsables por el host** (test: `ConfigLoaderTest`)
   — nunca se bloquea la descarga si no sons JSON-friendly: YAML booleans,
   ints, floats y strings planas (los templates con `:` deben ir entre comillas
   simples/dobles si el parser lo exige).
3. Import acepta cualquier proyecto exportado por este editor; campos
   faltantes = defaults; campos desconocidos se **conservan** (no se tiran).