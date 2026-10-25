# Estructura de Almacenamiento (Storage Layout)

> **Fuente de verdad**: Este documento describe cómo la suite organiza sus archivos de configuración en disco. Es la referencia única para rutas, precedencia y ciclo de vida de los archivos YAML.
>
> **Principio**: Cada módulo Java (`src/<modulo>/`) es dueño de su directorio de configuración en `config/<modulo>/`. El host/plataforma solo gestiona el directorio raíz y delega.

---

## 1. Layout General (Arquitectura Objetivo)

```
<servidor>/                          ← Raíz del servidor (Paper, Fabric, etc.)
└── <manager>/                       ← Directorio del gestor (plugins/TextFormatterSuite, mods/textformatter)
    ├── config/                      ← **Directorio raíz de configuración de la suite**
    │   ├── suite/                   ← Configuración GLOBAL del host (solo lo que el core necesita)
    │   │   ├── config.yml           ← Config principal: idioma, claim-mode, sound, parallel, repositorios
    │   │   ├── rules.yml            ← Reglas globales de transformación/bloqueo (motor de reglas)
    │   │   └── messages.yml         ← Strings de UI del host/platform (comandos /suite, prefijos)
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
    │   ├── sync/                    ← **Módulo sync-bus** - Dueño de sincronización
    │   │   ├── discord.yml
    │   │   ├── websocket.yml
    │   │   ├── tcp.yml
    │   │   ├── udp.yml
    │   │   ├── telegram.yml
    │   │   └── config.yml           ← Config global de sync (si aplica)
    │   │
    │   ├── inworld/                 ← **Módulo inworld** - Dueño de interacciones en mundo
    │   │   └── config.yml           ← Formatos sign/cofre/libro, radios, etc.
    │   │
    │   ├── observability/           ← **Módulo observability** - Dueño de métricas/debug/health
    │   │   └── config.yml           ← Puerto, bind, auth-token, métricas habilitadas
    │   │
    │   └── <modulo>/                ← **Cada módulo tiene su propio directorio**
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
| `config/suite/channels/` | `host` carga, `textformatter` usa | **`textformatter`** | ⚠️ Migrar |
| `config/suite/translators/` | `host` carga (`TranslatorsConfig`) | **`chattranslator`** | ⚠️ Migrar |
| `config/suite/sync/` | `spigot-host` carga directo | **`sync-bus`** | ⚠️ Migrar |
| `config/suite/messages.yml` | `spigot-host` carga (`MessagesConfig`) | **`spigot-host` / `fabric-host`** (platform adapter) | ✅ OK |
| `config/suite/config.yml` | `host` carga (`HostConfig`) | **`suite` (host core)** | ✅ OK |
| `config/suite/rules.yml` | `host` carga | **`suite` (host core)** | ✅ OK |

> **Nota**: La migración implica mover la lógica de carga (`ConfigLoader.loadChannels`, `TranslatorsConfig.load`, etc.) desde `host` hacia los módulos dueños (`textformatter`, `chattranslator`, `sync-bus`).

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

### 3.2 `config/suite/rules.yml` — Host Core (Reglas Globales)

```yaml
# Reglas globales de transformación/bloqueo/redirección
rules:
  - name: "no-caps"
    condition: "message.text().equals(message.text().toUpperCase()) && message.text().length() > 5"
    action: "BLOCK"
  - name: "staff-redirect"
    condition: "sender.hasPermission('suite.staff')"
    action: "REDIRECT"
    target-channel: "chat.staff"
```

**Cargado por**: `host` (rule engine)  
**Dueño**: `host` module (core)

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

> **Migración pendiente**: Mover `ConfigLoader.loadChannels()` de `host` a `textformatter`.

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

> **Migración pendiente**: Mover `TranslatorsConfig.load()` de `host` a `chattranslator`.

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

### 3.7 `config/sync/*.yml` — Módulo sync-bus

```yaml
# config/sync/discord.yml
enabled: true
token: ""
channels: []

# config/sync/websocket.yml
enabled: false
port: 9092
bind: "127.0.0.1"
token: "changeme"

# config/sync/tcp.yml
enabled: false
host: "127.0.0.1"
port: 9093

# config/sync/telegram.yml
enabled: false
token: ""
chat-id: 0
```

**Cargado por**: `sync-bus` (nuevo: `SyncConfig.load(folder)`)  
**Dueño**: `sync-bus` module  
**Usado por**: Sinks de sincronización (Discord, WebSocket, TCP, UDP, Telegram)

> **Migración pendiente**: Mover carga de sync de `spigot-host` a `sync-bus`.

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

### 4.1 Arranque (Bootstrap)

```
1. SuiteHost.bootstrap(folder) / SuiteBootstrap.bootstrap(folder)
   │
   ├─► ConfigLoader.loadConfig(folder.resolve("suite"))              → HostConfig (suite/config.yml)
   ├─► RulesConfig.load(folder.resolve("suite"))                     → Rules (suite/rules.yml)
   ├─► textformatter.ChannelConfig.load(folder.resolve("textformatter")) → ChannelRegistry
   ├─► chattranslator.BackendConfig.load(folder.resolve("chattranslator")) → TranslatorManager
   ├─► chattranslator.ChatTranslatorConfig.load(folder.resolve("chattranslator")) → ChatTranslator
   ├─► syncbus.SyncConfig.load(folder.resolve("sync"))               → SyncBus + sinks
   ├─► inworld.InWorldConfig.load(folder.resolve("inworld"))         → InWorldHandler
   ├─► observability.ObservabilityConfig.load(folder.resolve("observability")) → Observability
   └─► MessagesConfig.load(folder.resolve("suite"))                  → Messages (platform adapter)
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
   - config/suite/rules.yml
   - config/suite/messages.yml
   - config/textformatter/
   - config/chattranslator/
   - config/sync/
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
| Directorio módulo | `kebab-case` | `textformatter`, `chattranslator`, `sync` |
| Archivo config principal | `config.yml` | `config/chattranslator/config.yml` |
| Sub-archivos (colecciones) | `<nombre>.yml` | `config/textformatter/channels/chat.global.yml` |
| Backends (colección) | `<provider>.yml` | `config/chattranslator/translators/google.yml` |
| Sinks (colección) | `<sink>.yml` | `config/sync/discord.yml` |
| Backup timestamp | `yyyyMMdd-HHmmss` | `backup/20261024-153045/` |
| JAR módulo | `<modulo>-<version>.jar` | `chattranslator-2.1.0.jar` |

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
| `ConfigLoader.loadChannels` | `src/host/.../ConfigLoader.java` | `src/textformatter/.../ChannelConfig.java` |
| `TranslatorsConfig.load` | `src/host/.../TranslatorsConfig.java` | `src/chattranslator/.../BackendConfig.java` |
| `ChatTranslatorConfig.load` | `src/chattranslator/.../ChatTranslatorConfig.java` | ✅ Ya en módulo correcto |
| `SyncConfig.load` | `src/spigot-host/.../TextFormatterSuitePlugin.java` | `src/sync-bus/.../SyncConfig.java` |
| `MessagesConfig.load` | `src/spigot-host/.../MessagesConfig.java` | ✅ En platform adapter (correcto) |
| `YamlUserLanguageStore` | `src/host/.../YamlUserLanguageStore.java` | ✅ En host core (correcto) |
| `InWorldConfig` | `src/inworld/.../InWorldHandler.java` | ✅ En inworld (correcto) |
| `Observability` | `src/observability/.../Observability.java` | ✅ En observability (correcto) |

---

## 9. Próximos Pasos de Migración

1. **textformatter**: Crear `ChannelConfig` en `src/textformatter`, mover lógica de channels ahí
2. **chattranslator**: Crear `BackendConfig` en `src/chattranslator`, mover `TranslatorsConfig` ahí
3. **sync-bus**: Crear `SyncConfig` en `src/sync-bus`, mover carga de sinks ahí
4. **host**: Simplificar `ConfigLoader` y `TranslatorsConfig` para solo manejar `suite/config.yml` y `suite/rules.yml`
5. **spigot-host/fabric-host**: Eliminar carga directa de sync/channels/translators; usar módulos