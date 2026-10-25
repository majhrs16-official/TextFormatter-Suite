# Estructura de Almacenamiento (Storage Layout)

> **Fuente de verdad**: Este documento describe cómo la suite organiza sus archivos de configuración en disco. Es la referencia única para rutas, precedencia y ciclo de vida de los archivos YAML.

---

## 1. Layout General

```
<servidor>/                          ← Raíz del servidor (ej. Paper, Fabric)
└── <manager>/                        ← Directorio del gestor (ej. plugins/TextFormatterSuite, mods/textformatter)
    ├── config/                       ← **Directorio raíz de configuración de la suite**
    │   ├── suite/                    ← Configuración GLOBAL (aplica a toda la suite)
    │   │   ├── config.yml            ← Config principal: idioma, claim-mode, sound, parallel, repositorios
    │   │   ├── channels/             ← Definición de canales (chat.global, join, quit, etc.)
    │   │   │   ├── chat.global.yml
    │   │   │   ├── join.yml
    │   │   │   └── ...
    │   │   ├── translators/          ← Configuración de backends de traducción (SPI)
    │   │   │   ├── google.yml
    │   │   │   ├── libre.yml
    │   │   │   └── ...
    │   │   ├── sync/                 ← Configuración de sinks de sincronización
    │   │   │   ├── discord.yml
    │   │   │   ├── websocket.yml
    │   │   │   └── ...
    │   │   ├── messages.yml          ← Catálogo de mensajes i18n (EN/ES)
    │   │   └── rules.yml             ← Reglas globales de transformación/bloqueo
    │   │
    │   ├── chattranslator/           ← Configuración ESPECÍFICA del módulo ChatTranslator
    │   │   └── config.yml            ← default-backend, context-override-enabled, cache, timeouts
    │   │
    │   ├── gtranslate/               ← Configuración ESPECÍFICA del backend Google Translate
    │   │   └── config.yml            ← (opcional, params extra como base-url custom)
    │   │
    │   ├── ltranslate/               ← Configuración ESPECÍFICA del backend LibreTranslate
    │   │   └── config.yml            ← base-url, api-key, timeout
    │   │
    │   ├── inworld/                  ← Configuración ESPECÍFICA del módulo InWorld
    │   │   └── config.yml            ← Formatos de sign/cofre/libro, radios, etc.
    │   │
    │   ├── observability/            ← Configuración ESPECÍFICA de observabilidad
    │   │   └── config.yml            ← Puerto, bind, auth-token, métricas habilitadas
    │   │
    │   └── <modulo>/                 ← **Cada módulo tiene su propio directorio**
    │       └── config.yml
    │
    ├── data/                         ← Datos persistentes en runtime (no configs editables)
    │   ├── languages.yml             ← Almacenamiento de idiomas de jugadores (UserLanguageStore)
    │   ├── cache/                    ← Cachés serializadas (traducción, detección)
    │   └── journal/                  ← Journal de políticas iFlow (si habilitado)
    │
    ├── modules/                      ← JARs de módulos descargados/instalados (manager)
    │   └── <modulo>-<version>.jar
    │
    ├── backup/                       ← Backups automáticos al hacer /suite reset
    │   └── <timestamp>/
    │       ├── config.yml
    │       ├── channels/
    │       └── ...
    │
    └── logs/                         ← Logs específicos de la suite (opcional)
```

---

## 2. Principios de Diseño

### 2.1 Separación de Responsabilidades por Directorio

| Directorio | Propietario | Quién lo edita | Ciclo de vida |
|------------|-------------|----------------|---------------|
| `config/suite/` | **Host/Suite** | Admin + Suite (valida al arranque) | Persistente; migración automática en updates |
| `config/chattranslator/` | **Módulo chattranslator** | Admin (config) + Módulo (lee) | Persistente; módulo lo carga en `onLoad` |
| `config/gtranslate/` | **Módulo gtranslate** | Admin | Persistente; backend lo usa en `TranslatorProvider.create()` |
| `config/<modulo>/` | **Módulo correspondiente** | Admin | Persistente; cada módulo gestiona su propio YAML |
| `data/` | **Host/Suite** | Solo Suite (runtime) | Persistente; serialización periódica |
| `modules/` | **Module Manager** | Solo Manager (descarga/instala) | Transitorio (cache de JARs) |
| `backup/` | **Suite** | Solo Suite (auto) | Persistente hasta limpieza manual |

### 2.2 Regla de Oro: Un Directorio por Módulo

> **Cada módulo Java (`src/<modulo>/`) tiene su directorio de configuración dedicado en `config/<modulo>/`.**

- **NO** mezclar configuración de módulos en `config/suite/config.yml`
- **NO** que un módulo lea/escriba en el directorio de otro módulo
- **SÍ** que el host/gestor provea la ruta base (`folder`) y cada módulo resuelva su subdirectorio

---

## 3. Especificación por Archivo

### 3.1 `config/suite/config.yml` (Global)

```yaml
# Configuración global de la suite
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
module-allowlist: []              # Lista blanca de módulos permitidos (vacío = todos)
```

**Cargado por**: `ConfigLoader.loadConfig(folder)` → `HostConfig`  
**Usado por**: `SuiteHost`, `MessageDispatcher`, `Router`, todos los componentes core

---

### 3.2 `config/suite/channels/*.yml` (Canales)

```yaml
# config/suite/channels/chat.global.yml
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

**Cargado por**: `ConfigLoader.loadChannels(folder)` → `ChannelRegistry`  
**Usado por**: `Router`, `TextFormatter`, `ChatTranslator` (para validar `SET_CHANNEL`)

---

### 3.3 `config/suite/translators/*.yml` (Backends SPI)

```yaml
# config/suite/translators/google.yml
provider: "google"                # Debe coincidir con TranslatorProvider.name()
active: true
# Parámetros específicos del backend (pasados a TranslatorProvider.create())
timeout: 10                       # segundos
```

```yaml
# config/suite/translators/libre.yml
provider: "libre"
active: true
base-url: "https://translate.example.com"
api-key: ""                       # Opcional
timeout: 15
```

**Cargado por**: `TranslatorsConfig.load(folder)` → `TranslatorManager`  
**Descubierto por**: `ServiceLoader<TranslatorProvider>` (SPI)  
**Usado por**: `TranslationService`, `ChatTranslator`

---

### 3.4 `config/chattranslator/config.yml` (Módulo ChatTranslator)

```yaml
# Configuración específica de la fachada ChatTranslator
default-backend: "google"         # Backend por defecto (null = auto: primer disponible)
context-override-enabled: true    # Permitir setContextBackend(key, backend) en runtime
cache-max-size: 10000             # Límite entradas caché traducción
detection-cache-max-size: 5000    # Límite entradas caché detección
translation-timeout-ms: 30000     # Timeout peticiones traducción (ms)
```

**Cargado por**: `ChatTranslatorConfig.load(folder)` → `ChatTranslatorConfig.Config`  
**Usado por**: `ChatTranslatorModule.onLoad()` → inyecta en `ChatTranslator` constructor

---

### 3.5 `config/<modulo>/config.yml` (Patrón Genérico)

```yaml
# Cada módulo define su propio schema. Ejemplos:

# config/inworld/config.yml
sign-format: "<gray>[Sign]</gray> <content>"
chest-format: "<gold>[Chest]</gold> <content>"
book-format: "<green>[Book]</green> <content>"
radius-format: "<aqua>[Local]</aqua> <sender>: <content>"
world-format: "<blue>[World]</blue> <sender>: <content>"

# config/observability/config.yml
port: 9091
bind: "127.0.0.1"
auth-token: "changeme"
metrics-enabled: true
debug-enabled: true
health-enabled: true
```

**Cargado por**: Cada módulo en su `onLoad()` via `ConfigLoader` o YAML directo  
**Responsabilidad**: El módulo dueño del directorio

---

### 3.6 `data/languages.yml` (UserLanguageStore)

```yaml
# Almacenamiento persistente de idiomas por jugador
"uuid-1": "es"
"uuid-2": "en"
"uuid-3": "fr"
```

**Gestionado por**: `YamlUserLanguageStore` (Spigot) / equivalente Fabric  
**Interfaz**: `UserLanguageStore` SPI

---

## 4. Ciclo de Vida de Archivos

### 4.1 Arranque (Bootstrap)

```
1. SuiteHost.bootstrap(folder) / SuiteBootstrap.bootstrap(folder)
   │
   ├─► ConfigLoader.loadConfig(folder.resolve("suite"))          → HostConfig
   ├─► ConfigLoader.loadChannels(folder.resolve("suite/channels")) → ChannelRegistry
   ├─► TranslatorsConfig.load(folder.resolve("suite/translators")) → TranslatorManager
   ├─► ChatTranslatorConfig.load(folder.resolve("chattranslator")) → ChatTranslatorConfig.Config
   ├─► InWorldConfig.load(folder.resolve("inworld"))             → InWorldConfig
   └─► ... (cada módulo carga su config/<modulo>/config.yml)
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
   - config/suite/channels/
   - config/suite/translators/
   - config/chattranslator/
   - config/<modulo>/ (todos)
   - messages.yml
   - rules.yml
2. Regenera defaults desde resources/defaults/
3. Notifica al admin: "Configs reseteados. Reinicia para aplicar."
```

---

## 5. Convenciones de Nombrado

| Elemento | Convención | Ejemplo |
|----------|------------|---------|
| Directorio módulo | `kebab-case` | `chattranslator`, `gtranslate`, `inworld` |
| Archivo config principal | `config.yml` | `config/chattranslator/config.yml` |
| Sub-archivos (colecciones) | `<nombre>.yml` | `config/suite/channels/chat.global.yml` |
| Backup timestamp | `yyyyMMdd-HHmmss` | `backup/20261024-153045/` |
| JAR módulo | `<modulo>-<version>.jar` | `chattranslator-2.1.0.jar` |

---

## 6. Validación y Migración

### 6.1 Validación al Arranque

- `ConfigValidator.validate(HostConfig, logger, folder)` valida `config/suite/config.yml`
- Cada módulo valida su propio `config/<modulo>/config.yml` en `onLoad()`
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
    Path folder = host.config().getConfigDirectory(); // o similar
    
    // Cargar config propia (config/chattranslator/config.yml)
    ChatTranslatorConfig.Config config = ChatTranslatorConfig.load(folder);
    
    // Inyectar en facade
    TranslatorManager manager = host.translatorManager();
    TranslationService translation = host.translation();
    ChatTranslator chatTranslator = new ChatTranslator(manager, translation, config);
    
    // Registrar en host para acceso global
    host.setChatTranslator(chatTranslator);
}
```

> **Nota**: `host.config()` debe exponer el directorio base (`folder`) para que los módulos resuelvan `folder.resolve("<modulo>").resolve("config.yml")`.

---

## 8. Referencias de Código

| Componente | Archivo |
|------------|---------|
| `ConfigLoader` | `src/host/src/main/java/me/majhrs16/suite/host/config/ConfigLoader.java` |
| `TranslatorsConfig` | `src/host/src/main/java/me/majhrs16/suite/host/config/TranslatorsConfig.java` |
| `ChatTranslatorConfig` | `src/chattranslator/src/main/java/me/majhrs16/suite/chattranslator/ChatTranslatorConfig.java` |
| `YamlUserLanguageStore` | `src/host/src/main/java/me/majhrs16/suite/host/config/YamlUserLanguageStore.java` |
| `TextFormatterSuitePlugin.copyDefaultsIfMissing` | `src/spigot-host/.../TextFormatterSuitePlugin.java` |
| `SuiteBootstrap` | `src/host/src/main/java/me/majhrs16/suite/host/SuiteBootstrap.java` |