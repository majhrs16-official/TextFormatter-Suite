# Estructura de Almacenamiento (Storage Layout)

> **Fuente de verdad**: Este documento describe cómo la suite organiza sus archivos de configuración en disco. Es la referencia única para rutas, precedencia y ciclo de vida de los archivos YAML.
>
> **Principio**: Cada módulo Java (`src/<modulo>/`) es dueño de su directorio de configuración en `config/<modulo>/`. El host/plataforma solo gestiona el directorio raíz y delega.

---

## 1.2 Terminología: Módulo vs Submódulo

> **Convención de arquitectura** usada en toda la suite:

| Término | Definición | Ejemplos |
|---------|------------|----------|
| **Módulo** | Mini-aplicación central con responsabilidad cohesiva, ciclo de vida propio, y capacidad de registro vía SPI (`ModuleDescriptor`). Puede funcionar independientemente o como núcleo para submódulos. | `chattranslator`, `sync-bus`, `textformatter`, `iflow-vm`, `host`, `observability` |
| **Submódulo** | Derivación/especialización que **extiende** un módulo central. Se registra como proveedor de capability del módulo padre. No existe sin su módulo padre. | `gtranslate`/`ltranslate` (submódulos de `chattranslator`), `sync-discord`/`sync-websocket`/`sync-telegram` (submódulos de `sync-bus`) |

**Reglas:**
1. Un **módulo** declara `provide(Capability.of("nombre-módulo", ...))`
2. Un **submódulo** declara `require(Capability.of("módulo-padre", ...))` y `provide(Capability.of("translator|sync-sink", ...))`
3. Cada **submódulo** es dueño de su `config/<submódulo>/config.yml`
3. El **módulo padre** solo gestiona config genérica (`config/<módulo>/config.yml`) y coordina el registro de submódulos

---

## 1.3 Layout General (Arquitectura Objetivo)

```
<servidor>/                          ← Raíz del servidor (Paper, Fabric, etc.)
└── <manager>/                       ← Directorio del gestor (plugins/TextFormatterSuite, mods/textformatter)
    ├── config/                      ← **Directorio raíz de configuración de la suite**
    │   ├── suite/                   ← Configuración GLOBAL del host (solo lo que el core necesita)
    │   │   ├── config.yml           ← Config principal: idioma, claim-mode, sound, parallel, repositorios
    │   │   └── messages.yml         ← Strings de UI del host/platform (comandos /suite, prefijos)
    │   │
    │   ├── iflow-firewall/          ← **Módulo iflow-firewall** (legacy, solo editor web)
    │   │   └── rules.yml            ← Grafo iFlow legacy YAML (solo editor web, no runtime)
    │   │
    │   ├── textformatter/           ← **Módulo textformatter** - Dueño de canales y formatos
    │   │   ├── channels/            ← Definición de canales (chat.global, join, quit, etc.)
    │   │   │   ├── chat.global.yml
    │   │   │   ├── join.yml
    │   │   │   └── ...
    │   │   └── config.yml           ← Config específica de textformatter (si aplica)
    │   │
    │   ├── chattranslator/          ← **Módulo chattranslator** - Dueño de backends de traducción
    │   │   ├── translators/         ← Configuración de backends SPI (google, libre, etc.)
    │   │   │   ├── google.yml
    │   │   │   ├── libre.yml
    │   │   │   └── ...
    │   │   └── config.yml           ← Config de la fachada: default-backend, cache, timeouts
    │   │
    │   ├── sync-bus/                ← **Módulo sync-bus** - Bus central de sincronización
    │   │   └── config.yml           ← Config genérica del bus (timeouts, retries, límites)
    │   │
    │   ├── sync-discord/            ← **Submódulo** (sink Discord)
    │   │   └── config.yml           ← token, channels, guild, etc.
    │   │
    │   ├── sync-websocket/          ← **Submódulo** (sink WebSocket)
    │   │   └── config.yml           ← port, bind, token, enabled
    │   │
    │   ├── sync-telegram/           ← **Submódulo** (sink Telegram)
    │   │   └── config.yml           ← token, chat-id
    │   │
    │   ├── sync-http/               ← **Submódulo** (sink HTTP)
    │   │   └── config.yml           ← endpoints, auth, timeouts
    │   │
    │   ├── sync-socket/             ← **Submódulo** (sinks TCP/UDP)
    │   │   ├── tcp.yml
    │   │   └── udp.yml
    │   │
    │   ├── inworld/                 ← **Módulo inworld** - Dueño de interacciones en mundo
    │   │   └── config.yml           ← Formatos sign/cofre/libro, radios, etc.
    │   │
    │   ├── observability/           ← **Módulo observability** - Dueño de métricas/debug/health
    │   │   └── config.yml           ← Puerto, bind, auth-token, métricas habilitadas
    │   │
    │   └── <modulo>/                ← **Cada módulo/submódulo tiene su propio directorio**
    │       └── config.yml
    │
    ├── data/                        ← Datos persistentes en runtime (no configs editables)
    │   ├── languages.yml            ← Almacenamiento de idiomas de jugadores (UserLanguageStore)
    │   ├── cache/                   ← Cachés serializadas (traducción, detección)
    │   └── journal/                 ← Journal de políticas iFlow (si habilitado)
    │
    ├── modules/                     ← JARs de módulos descargados/instalados (manager)
    │   └── <modulo>-<version>.jar
    │
    ├── backup/                      ← Backups automáticos al hacer /suite reset
    │   └── <timestamp>/
    │       ├── config.yml
    │       ├── textformatter/
    │       ├── chattranslator/
    │       └── ...
    │
    └── logs/                        ← Logs específicos de la suite (opcional)
```

---

## 2. Estado Actual vs Objetivo (Migración Pendiente)

| Directorio Actual | Dueño Real (Código) | Dueño Correcto (Arquitectura) | Estado |
|-------------------|---------------------|------------------------------|--------|
| `config/textformatter/channels/` | `textformatter` carga (`ChannelConfig`) | **`textformatter`** | ✅ Migrado (I-011) |
| `config/chattranslator/translators/` | `chattranslator` carga (`BackendConfig`) | **`chattranslator`** | ✅ Migrado (I-004) |
| `config/sync-bus/`, `config/sync-*/` | cada submódulo sync carga su config (`SyncYaml`) | **cada submódulo sync** | ✅ Migrado (I-004) |
| `config/sync-discord/config.yml` | `sync-discord` carga (`DiscordConfig`) | **`sync-discord`** (submódulo) | ✅ Migrado (I-004) |
| `config/sync-websocket/config.yml` | `sync-websocket` carga (`WebSocketSyncConfig`) | **`sync-websocket`** (submódulo) | ✅ Migrado (I-004) |
| `config/sync-telegram/config.yml` | `sync-telegram` carga (`TelegramConfig`) | **`sync-telegram`** (submódulo) | ✅ Migrado (I-004) |
| `config/sync-socket/tcp.yml` / `udp.yml` | `sync-socket` carga (`SocketConfig`) | **`sync-socket`** (submódulo) | ✅ Migrado (I-004) |
| `config/suite/messages.yml` | `host` carga (`MessagesConfig`) | **`host` (core)** o platform adapter | ✅ OK (ADR I-020 pendiente) |
| `config/suite/config.yml` | `host` carga (`HostConfig`/`HostPaths`) | **`suite` (host core)** | ✅ Migrado |
| `config/iflow-firewall/rules.yml` | `iflow-firewall` carga (`FirewallConfig`) | **`iflow-firewall`** | ✅ Migrado (I-001) |

> **Nota**: `textformatter`, `chattranslator`, `suite` y los submódulos sync ya usan el layout canónico con fallback a las rutas heredadas y son dueños de su propio parseo. El único archivo compartido es `config/suite/messages.yml` (ADR-020).

---

## 3. Especificación por Archivo (Por Módulo Dueño)

### 3.1 `config/suite/config.yml` — Host Core (Global)

```yaml
# Configuración global del host/core suite
quick-look: true
language: en
iflow:
  engine:
    parallel: false
sound:
  enabled: true
chat:
  claim-mode: CANCEL_EVENT        # CANCEL_EVENT | CLEAR_RECIPIENTS | NONE
  log-to-console: true
repositories:
  - name: "official"
    url: "https://github.com/majhrs16-official/TextFormatter-Suite/releases"
    type: "github"
    enabled: true
module-allowlist: []
```

**Cargado por**: `ConfigLoader.loadConfig(folder)` → `HostConfig` (en `host`)  
**Dueño**: `host` module (core)

---

### 3.2 `rules.yml` — absorbido por `iflow-firewall`

> **Histórico:** el antiguo `config/suite/rules.yml` con `rules: [{condition, action}]` quedó obsoleto
> al eliminar SpEL (`e21e7d7`). El único `rules.yml` vigente es
> `config/iflow-firewall/rules.yml` (§3.3.1), propiedad del módulo `iflow-firewall`.

---

### 3.3.1 `config/iflow-firewall/rules.yml` — Módulo iflow-firewall (Legacy)

> **Nota**: Este módulo es opcional y solo para compatibilidad con el editor web legacy.
> El runtime actual usa `policy.flow` (bytecode binario); este archivo YAML
> solo sirve para validación/edición en el web editor.

```yaml
# iFlow v2 no evalúa reglas YAML ni expresiones inline.
# Las políticas se cargan desde policy.flow (bytecode binario).
# Este archivo se conserva como grafo legacy para el editor web y no
# participa en la ejecución del runtime.
guard:
  max-steps: 512
filter:
  dedup-fanout: true
  priority: batch-first
priority: batch-first
nodes: []
edges: []
```

**Cargado por**: `iflow-firewall.FirewallConfig.load(folder)`  
**Dueño**: `iflow-firewall` module (legacy, solo editor web)  
**Resolución**: `config/iflow-firewall/rules.yml` (canónico) → `rules.yml` (heredado)  
**No participa** en la ejecución del runtime (VM .flow / Wasm)

---

### 3.3 `config/suite/messages.yml` — Platform Adapter (Spigot/Fabric)

```yaml
# Strings de UI específicos de la plataforma (comandos /suite, prefijos)
prefix: "[suite] "
not-initialized: "[suite] no inicializado"
usage: "[suite] uso: /suite <lang|reload|status|toggle|reset|module|suite>"
enabled: "[suite] activo: {} canales, traductor '{}'"
# ... resto de mensajes de comandos
```

**Cargado por**: `MessagesConfig.load(folder)` en `spigot-host` / `fabric-host`  
**Dueño**: Cada **platform adapter** (específico de Spigot/Fabric)  
**No pertenece** al core ni a módulos compartidos.

---

### 3.4 `config/textformatter/channels/*.yml` — Módulo textformatter

```yaml
# config/textformatter/channels/chat.global.yml
name: "chat.global"
permission: "suite.chat.global"
send-permission: "suite.chat.global.send"
receive-permission: "suite.chat.global.receive"
type: "CHAT"                      # CHAT | SYSTEM | LOG | WORLD | RADIUS | PRIVATE
show-sender: true
rate-limit-per-second: 5
lang-source: "auto"               # auto | <lang-code>
lang-target: "auto"               # auto | <lang-code>
messages: []                      # Formatos MiniMessage (ver Formats)
tooltips: []
sounds: []
```

**Cargado por**: `textformatter` (nuevo: `ChannelConfig.load(folder)`)  
**Dueño**: `textformatter` module  
**Usado por**: `ChannelRegistry`, `Router`, `ChatTranslator` (validar `SET_CHANNEL`)

> **Migración completada (I-011)**: `ConfigLoader.loadChannels()` se eliminó de `host`; la carga vive en `textformatter.ChannelConfig`. Rutas resueltas: `config/textformatter/channels/` (canónica) → `textformatter/channels/` → `channels/` (legacy).

---

### 3.5 `config/chattranslator/translators/*.yml` — Módulo chattranslator

```yaml
# config/chattranslator/translators/google.yml
provider: "google"                # Debe coincidir con TranslatorProvider.name()
active: true
timeout: 10                       # segundos
```

```yaml
# config/chattranslator/translators/libre.yml
provider: "libre"
active: true
base-url: "https://translate.example.com"
api-key: ""
timeout: 15
```

**Cargado por**: `chattranslator` (nuevo: `BackendConfig.load(folder)`)  
**Descubierto por**: `ServiceLoader<TranslatorProvider>` (SPI)  
**Dueño**: `chattranslator` module  
**Usado por**: `TranslatorManager`, `TranslationService`, `ChatTranslator`

> **Migración completada (I-004)**: `TranslatorsConfig` se eliminó de `host`; `chattranslator.BackendConfig` carga `config/chattranslator/translators/` y `ChatTranslatorConfig` carga `config/chattranslator/config.yml` (con fallback a las rutas heredadas).

---

### 3.6 `config/chattranslator/config.yml` — Módulo chattranslator (Fachada)

```yaml
# Configuración de la fachada ChatTranslator
default-backend: "google"         # Backend por defecto (null = auto: primer disponible)
context-override-enabled: true    # Permitir setContextBackend(key, backend) en runtime
cache-max-size: 10000             # Límite entradas caché traducción
detection-cache-max-size: 5000    # Límite entradas caché detección
translation-timeout-ms: 30000     # Timeout peticiones traducción (ms)
```

**Cargado por**: `ChatTranslatorConfig.load(folder)` → `ChatTranslatorConfig.Config`  
**Dueño**: `chattranslator` module

---

### 3.7 `config/sync-bus/config.yml` — Módulo sync-bus (Bus Central)

```yaml
# Configuración genérica del bus de sincronización
default-timeout-ms: 5000
max-retries: 3
backoff-base-ms: 1000
max-concurrent-deliveries: 100
```

**Cargado por**: `sync-bus` (`SyncBusConfig.load(folder)`)  
**Dueño**: `sync-bus` module (módulo central)  
**Usado por**: `DefaultSyncBus`, registro de sinks, health checks

---

### 3.7.1 `config/sync-discord/config.yml` — Submódulo sync-discord

```yaml
enabled: true
token: ""
channels: []
```

**Cargado por**: `sync-discord` (en su `onLoad`)  
**Dueño**: `sync-discord` module (submódulo)  
**Require**: `Capability.of("sync-bus", ...)`  
**Provide**: `Capability.of("sync-sink", ...)`

---

### 3.7.2 `config/sync-websocket/config.yml` — Submódulo sync-websocket

```yaml
enabled: false
port: 9092
bind: "127.0.0.1"
token: "changeme"
```

**Cargado por**: `sync-websocket` (en su `onLoad`)  
**Dueño**: `sync-websocket` module (submódulo)  
**Require**: `Capability.of("sync-bus", ...)`  
**Provide**: `Capability.of("sync-sink", ...)`

---

### 3.7.3 `config/sync-telegram/config.yml` — Submódulo sync-telegram

```yaml
enabled: false
token: ""
chat-id: 0
```

**Cargado por**: `sync-telegram` (en su `onLoad`)  
**Dueño**: `sync-telegram` module (submódulo)  
**Require**: `Capability.of("sync-bus", ...)`  
**Provide**: `Capability.of("sync-sink", ...)`

---

### 3.7.4 `config/sync-socket/` — Submódulo sync-socket (TCP/UDP)

```yaml
# config/sync-socket/tcp.yml
enabled: false
host: "127.0.0.1"
port: 9093

# config/sync-socket/udp.yml
enabled: false
remote-host: "127.0.0.1"
remote-port: 9094
local-port: 9095
```

**Cargado por**: `sync-socket` (en su `onLoad`)  
**Dueño**: `sync-socket` module (submódulo)  
**Require**: `Capability.of("sync-bus", ...)`  
**Provide**: `Capability.of("sync-sink", ...)` (doble: tcp + udp)

> **Migración completada (I-012/I-004)**: los adapters resuelven cada archivo con `syncbus.SyncConfigPaths` (`config/sync-*/` canónico, `sync/*.yml` como fallback) y cada submódulo expone su config (`DiscordConfig`, `WebSocketSyncConfig`, `SocketConfig`, `TelegramConfig`) sobre `syncbus.SyncYaml`; el adapter ya no parsea YAML de sync.

---

### 3.8 `config/inworld/config.yml` — Módulo inworld

```yaml
sign-format: "<gray>[Sign]</gray> <content>"
chest-format: "<gold>[Chest]</gold> <content>"
book-format: "<green>[Book]</green> <content>"
radius-format: "<aqua>[Local]</aqua> <sender>: <content>"
world-format: "<blue>[World]</blue> <sender>: <content>"
```

**Cargado por**: `inworld` (ya lo hace en `InWorldHandler`)  
**Dueño**: `inworld` module

---

### 3.9 `config/observability/config.yml` — Módulo observability

```yaml
port: 9091
bind: "127.0.0.1"
auth-token: "changeme"
metrics-enabled: true
debug-enabled: true
health-enabled: true
```

**Cargado por**: `observability` (ya lo hace en `Observability.createDefault`)  
**Dueño**: `observability` module

---

### 3.10 `data/languages.yml` — Host Core (UserLanguageStore)

```yaml
# Almacenamiento persistente de idiomas por jugador
"uuid-1": "es"
"uuid-2": "en"
"uuid-3": "fr"
```

**Gestionado por**: `YamlUserLanguageStore` (Spigot) / equivalente Fabric  
**Interfaz**: `UserLanguageStore` SPI  
**Dueño**: `host` module (core, no platform-specific)

---

## 4. Ciclo de Vida de Archivos

### 4.1 Arranque (Bootstrap) — Orden Módulos → Submódulos

```
1. SuiteHost.bootstrap(folder) / SuiteBootstrap.bootstrap(folder)
   │
   ├─► MÓDULOS CORE (independientes, sin requires de otros módulos suite)
   │   ├─► ConfigLoader.loadConfig(folder.resolve("suite"))              → HostConfig (suite/config.yml)
   │   ├─► firewall.FirewallConfig.load(folder)                          → RuleGraph (iflow-firewall/rules.yml)
   │   ├─► textformatter.ChannelConfig.load(folder.resolve("textformatter")) → ChannelRegistry
   │   ├─► inworld.InWorldConfig.load(folder.resolve("inworld"))         → InWorldHandler
   │   ├─► observability.ObservabilityConfig.load(folder.resolve("observability")) → Observability
   │   └─► MessagesConfig.load(folder.resolve("suite"))                  → Messages (platform adapter)
   │
   ├─► MÓDULOS CON DEPENDENCIAS (cargan después de sus requires)
   │   ├─► chattranslator.BackendConfig.load(folder.resolve("chattranslator")) → TranslatorManager
   │   ├─► chattranslator.ChatTranslatorConfig.load(folder.resolve("chattranslator")) → ChatTranslator
   │   └─► syncbus.SyncBusConfig.load(folder.resolve("sync-bus"))        → SyncBus (config genérica)
   │
   └─► SUBMÓDULOS (cargan tras sus módulos padre; auto-registran vía SPI)
       ├─► gtranslate/ltranslate → se registran en TranslatorManager
       ├─► sync-discord → se registra en SyncBus
       ├─► sync-websocket → se registra en SyncBus
       ├─► sync-telegram → se registra en SyncBus
       ├─► sync-http → se registra en SyncBus
       └─► sync-socket → se registra en SyncBus
```

### 4.2 Recarga (`/suite reload`)

```
1. Cierra runtime anterior (close() en todos los módulos)
2. Vuelve a ejecutar bootstrap completo (paso 4.1)
3. Los archivos en disco son la fuente de verdad absoluta
```

### 4.3 Reset (`/suite reset`)

```
1. Mueve configs de usuario a backup/<timestamp>/
   - config/suite/config.yml
   - config/suite/messages.yml
   - config/iflow-firewall/
   - config/textformatter/
   - config/chattranslator/
   - config/sync-bus/
   - config/sync-discord/
   - config/sync-websocket/
   - config/sync-telegram/
   - config/sync-http/
   - config/sync-socket/
   - config/inworld/
   - config/observability/
   - config/<modulo>/ (todos)
2. Regenera defaults desde resources/defaults/ de cada módulo
3. Notifica al admin: "Configs reseteados. Reinicia para aplicar."
```

---

## 5. Convenciones de Nombrado

| Elemento | Convención | Ejemplo |
|----------|------------|---------|
| Directorio **módulo** | `kebab-case` | `textformatter`, `chattranslator`, `sync-bus`, `inworld` |
| Directorio **submódulo** | `kebab-case` | `gtranslate`, `ltranslate`, `sync-discord`, `sync-websocket` |
| Archivo config principal | `config.yml` | `config/chattranslator/config.yml`, `config/sync-discord/config.yml` |
| Sub-archivos (colecciones) | `<nombre>.yml` | `config/textformatter/channels/chat.global.yml` |
| Backends (colección) | `<provider>.yml` | `config/chattranslator/translators/google.yml` |
| Sinks (colección — ya no aplica, cada submódulo tiene su config.yml) | — | `config/sync-discord/config.yml`, `config/sync-socket/config.yml` |
| Backup timestamp | `yyyyMMdd-HHmmss` | `backup/20261024-153045/` |
| JAR módulo | `<modulo>-<version>.jar` | `chattranslator-2.1.0.jar` |
| JAR submódulo | `<submodulo>-<version>.jar` | `gtranslate-2.1.0.jar`, `sync-discord-2.1.0.jar` |

---

## 6. Validación y Migración

### 6.1 Validación al Arranque

- `ConfigValidator.validate(HostConfig, logger, folder)` valida `config/suite/config.yml`
- Cada módulo valida su propio `config/<modulo>/config.yml` en su `onLoad()`
- Errores → log ERROR + config degradado (defaults) + suite sigue arrancando

### 6.2 Migración de Versiones

- Los campos desconocidos en YAML se **ignoran** (compatibilidad hacia adelante)
- Valores faltantes → **defaults** (compatibilidad hacia atrás)
- Migraciones rompedoras → version en `HostConfig` / `ModuleDescriptor` + lógica en `ConfigValidator`

---

## 7. Ejemplo: Cómo un Módulo Carga su Config

```java
// En ChatTranslatorModule.onLoad(SuiteHost host)
public void onLoad(SuiteHost host) {
    // host.config() expone el directorio base (folder)
    Path folder = host.config().getConfigDirectory(); // Path al <manager>/config
    
    // Cargar config propia (config/chattranslator/config.yml)
    ChatTranslatorConfig.Config facadeConfig = ChatTranslatorConfig.load(folder);
    
    // Cargar backends (config/chattranslator/translators/*.yml)
    TranslatorManager manager = BackendConfig.load(folder);
    
    // Crear fachada
    TranslationService translation = host.translation(); // ya creado con manager
    ChatTranslator chatTranslator = new ChatTranslator(manager, translation, facadeConfig);
    
    // Registrar en host para acceso global
    host.setChatTranslator(chatTranslator);
}
```

> **Nota**: `HostConfig` debe exponer `getConfigDirectory()` que devuelve `Path` al directorio `<manager>/config`. Los módulos resuelven `folder.resolve("<modulo>").resolve("config.yml")`.

---

## 8. Referencias de Código (Estado Actual vs Migración)

| Componente | Archivo Actual | Archivo Objetivo (Post-Migración) |
|------------|----------------|-----------------------------------|
| `ConfigLoader.loadConfig` | `src/host/.../ConfigLoader.java` | `src/host/.../ConfigLoader.java` (solo suite) |
| `ConfigLoader.loadChannels` | — (eliminado) | ✅ `src/textformatter/.../ChannelConfig.java` |
| `TranslatorsConfig.load` | `src/host/.../TranslatorsConfig.java` | `src/chattranslator/.../BackendConfig.java` |
| `ChatTranslatorConfig.load` | `src/chattranslator/.../ChatTranslatorConfig.java` | ✅ Ya en módulo correcto |
| `SyncConfig.load` | `src/spigot-host/.../TextFormatterSuitePlugin.java` | `src/sync-bus/.../SyncConfig.java` |
| `MessagesConfig.load` | `src/spigot-host/.../MessagesConfig.java` | ✅ En platform adapter (correcto) |
| `YamlUserLanguageStore` | `src/host/.../YamlUserLanguageStore.java` | ✅ En host core (correcto) |
| `InWorldConfig` | `src/inworld/.../InWorldHandler.java` | ✅ En inworld (correcto) |
| `Observability` | `src/observability/.../Observability.java` | ✅ En observability (correcto) |

---

## 9. Próximos Pasos de Migración

1. ~~**textformatter**: Crear `ChannelConfig`~~ ✅ I-011
2. ~~**chattranslator**: Crear `BackendConfig`~~ ✅ I-004
3. ~~**sync-bus**: Cada sink con su config~~ ✅ I-004
4. **host**: `ConfigLoader` solo maneja `config/suite/config.yml`; `rules.yml` vive en `iflow-firewall` (I-001) y `messages.yml` en `host` por ADR-020
5. ~~**spigot-host/fabric-host**: Eliminar carga directa de sync/channels/translators~~ ✅ I-004/I-011