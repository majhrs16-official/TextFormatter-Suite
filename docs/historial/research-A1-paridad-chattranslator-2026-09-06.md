# Investigación de Paridad Funcional RUNTIME: TextFormatter Suite vs ChatTranslator

**Fecha:** 2026-09-06  
**Fuentes analizadas:**
- `/home/majhrs16/Documentos/chattranslator` (código original v2.x)
- `/home/majhrs16/Documentos/chattranslator.wiki` (documentación oficial)
- `/home/majhrs16/Documentos/textformatter-suite` (código Suite actual)
- GitHub: https://github.com/Majhrs16/ChatTranslator (issues/releases)

---

## 1. Matriz de Paridad

| Feature / Comportamiento | ChatTranslator (original) | TextFormatter Suite | Estado |
|--------------------------|---------------------------|---------------------|--------|
| **Chat translation** (mensajes jugador→jugador) | ✅ Automático por idioma cliente | ✅ Canal `chat.global`, MiniMessage + `<tr>` | ✅ **Paridad total** |
| **Formato de mensajes** (formats.yml, from/to) | ✅ Grupos `from`/`to`, variables `%ct_*%` / `$ct_*$`, JSON legacy | ✅ MiniMessage nativo (`<tr>`), `%player_name%`, `%lang_source%`, `%lang_target%`, `{index}` | ⚠️ **Parcial** (sintaxis distinta, misma capacidad expresiva) |
| **Textos/índices** (`{0}`, `{1}` en formatos) | ✅ Soportado | ✅ Soportado via `Formats` + `TemplateContext` | ✅ **Paridad total** |
| **Escape de texto** (`` `texto` `` no traducir) | ✅ | ✅ (MiniMessage usa etiquetas `<tr>...</tr>` para marcar traducible) | ⚠️ **Parcial** (semántica equivalente, sintaxis distinta) |
| **Colores hex** (`#RRGGBB`) | ✅ 1.16.5+ | ✅ MiniMessage/Adventure nativo | ✅ **Paridad total** |
| **Anti-spam / Rate-limit** | ✅ `ChatLimiter` por tick | ✅ `RateLimiter` token-bucket por canal/emisor (configurable `rate-limit-per-second`) | ✅ **Paridad total** (mejorado) |
| **Detección automática de idioma** | ✅ `InternetCheckerAsync` + locale cliente | ✅ `Language.AUTO` + `TranslationService.detect()` (delegado a proveedor) | ✅ **Paridad total** |
| **Selección manual de idioma** (`/cht lang`) | ✅ `/cht lang [jugador] <código\|auto>` | ✅ `/suite lang [jugador] <auto\|off\|código>` (`off` = sin traducir) | ✅ **Paridad total** |
| **Toggle rápido** | ❌ | ✅ `/suite toggle [jugador]` (alterna auto↔off) | 🆕 **Nuevo en Suite** |
| **Sign translation** (Shift+Click) | ✅ `signs.yml` persiste texto+lang, traduce al click | ❌ **No implementado** (canal `sign` existe en API pero no hay listener Spigot) | ❌ **Gap crítico** |
| **Cofres/Contenedores** | ❌ | ❌ | — |
| **Libros escritos** | ❌ | ❌ | — |
| **Eventos join/quit/death/advancement** | ✅ Canales convencionales (`join`, `quit`, `death`, `advancement` en formats.yml) | ✅ Canales `join`/`quit`/`death`/`advancement` (tipo `EVENT`, presencia en registry = activado) | ✅ **Paridad total** |
| **Private messages / Mentions** | ✅ `/cht msg`, formato `mention` | ✅ `MessageType.PRIVATE`, `MENTION` en API; wiring pendiente en host | ⚠️ **Parcial** (API lista, host no expone comandos) |
| **Traducción proveedor Google** | ✅ `translate.googleapis.com` (gratis, sin key) | ✅ `GTranslate` (mismo endpoint `client=gtx`) | ✅ **Paridad total** |
| **Traducción proveedor LibreTranslate** | ❌ | ✅ `LTranslate` (self-hosted o público, API key opcional) | 🆕 **Nuevo en Suite** |
| **Cache de traducciones** | ❌ (cada mensaje = request HTTP) | ❌ No hay cache TTL implementado aún (F7+) | ❌ **Gap medio** |
| **Rate-limit proveedor** | ❌ | ⚠️ Schema tiene `pool.max-concurrent` (no consumido aún, F7+) | ❌ **Gap medio** |
| **Fallback entre proveedores** | ❌ | ✅ `TranslatorManager` prueba google→libre→fallback "none" | 🆕 **Nuevo en Suite** |
| **PAPI `%cot_*` (CoreTranslator)** | ✅ `cot_translate`, `cot_var`, `cot_broadcast`, `cot_lang`, `cot_sendDiscord`, `cot_set`/`cot_get`, `cot_new` | ❌ **No implementado** (módulo `coretranslator` es bridge legacy sin PAPI expansion) | ❌ **Gap crítico** |
| **PAPI placeholders nativos** | ✅ `%ct_*%`, `$ct_*$` en formatos | ✅ `%variable%` (sender) / `$variable$` (recipient) via `PlaceholderResolver` (PAPI en Spigot) | ✅ **Paridad total** |
| **SpEL en formatos** (`<expr>...</expr>`) | ✅ vía ConditionalEvents + CoT | ✅ MiniMessage `<expr>...</expr>` evalúa SpEL sobre `#msg` | ✅ **Paridad total** |
| **Discord Sync** (bidireccional) | ✅ `/dst link`, bot token, canales, embeds, intents | ✅ `DiscordSink` (REST v10 + WebSocket gateway), intents, embeds; `DiscordBridge` en host | ✅ **Paridad total** |
| **Telegram Sync** | ❌ | ✅ `SyncTelegramModule` (long-poll, watermark offset) | 🆕 **Nuevo en Suite** |
| **HTTP/Webhook Sync** | ❌ | ✅ `HttpSink` (outbound webhook + inbound `HttpServer` JDK) | 🆕 **Nuevo en Suite** |
| **TCP/UDP Sync** | ❌ | ✅ `TcpSink`/`UdpSink` (JSON por línea/datagrama) | 🆕 **Nuevo en Suite** |
| **Velocity Sync** | ✅ BungeeCord/Velocity via MySQL storage | ❌ **Stub only** (config/editor tienen `sync/velocity.yml` pero no hay módulo real) | ❌ **Gap alto** |
| **Storage YAML** | ✅ `storage.yml` (uuid→lang/discordID) | ✅ `YamlUserLanguageStore` (`storage.yml`, hot-reload por timestamp) | ✅ **Paridad total** |
| **Storage SQLite** | ✅ `sqlite` (tabla `storage`) | ❌ **No implementado** | ❌ **Gap medio** |
| **Storage MySQL/MariaDB** | ✅ `mysql`/`mariadb` (BungeeCord) | ❌ **No implementado** | ❌ **Gap alto** (requerido para red multi-servidor) |
| **Migración datos** | ✅ Auto-detecta tipo, crea tablas | ❌ **No implementado** | ❌ **Gap medio** |
| **Permisos base** | `ChatTranslator.chat.%s.messages\|toolTips\|sounds\|color` + `ChatTranslator.admin` | `cht.<channel>` (base = suscripción), `cht.admin` | ⚠️ **Parcial** (modelo distinto, misma cobertura) |
| **Permisos send/receive asimétricos** | ❌ (solo base por grupo) | ✅ `send-permission` / `receive-permission` por canal nativo + iFlow rules | 🆕 **Nuevo en Suite** |
| **iFlow / Reglas condicionales** | ❌ (requiere ConditionalEvents externo) | ✅ Motor reglas SpEL (`rules.yml`), grafo iFlow (nodos input/cond/transform/output/redirect) | 🆕 **Nuevo en Suite** |
| **Web Editor configuración** | ❌ | ✅ Static HTML/JS (GitHub Pages), canvas nodos, round-trip YAML exacto, validación global | 🆕 **Nuevo en Suite** |
| **Test runtime automatizado** | ❌ | ✅ `/suite test full\|stress\|concurrency\|routing\|events\|perf\|sync` (25+ tests) | 🆕 **Nuevo en Suite** |
| **Comando principal** | `/cht` (subcomandos: lang, reload, toggle, version, private, reset) | `/suite` (subcomandos: reload, status, lang, toggle, reset, test) | ✅ **Paridad funcional** (distinta UX) |
| **Configuración en caliente** | `/cht reload` (recarga YAML) | `/suite reload` (relee todo layout sin watchers) | ✅ **Paridad total** |
| **Compatibilidad versiones MC** | 1.5.2 – 1.20.6 | Paper 1.20.6+ (Java 17/21), Fabric ⏳ | ⚠️ **Parcial** (Suite más moderno, menos legacy) |
| **ConditionalEvents integración** | ✅ Evento custom `me.majhrs16.cht.events.custom.Message` | ❌ **No implementado** (plan: `MessageBus` en core-api) | ❌ **Gap alto** |
| **Connection loss indicator** | ✅ Prefijo `[!]` en formatos si sin internet | ❌ **No implementado** (InternetCheckerAsync eliminado) | ❌ **Gap medio** |

---

## 2. Lista de Gaps Priorizados

### 🔴 CRÍTICO
| Gap | Descripción | Impacto |
|-----|-------------|---------|
| **Sign translation** | Shift+Left-Click en cartel no traduce; no hay listener `PlayerInteractEvent` + persistencia `signs.yml` | Rompe feature visible para usuarios; pérdida de funcionalidad clave del original |
| **PAPI `%cot_*` (CoreTranslator)** | Placeholders `cot_translate`, `cot_var`, `cot_broadcast`, `cot_lang`, `cot_sendDiscord` no existen | Integraciones existentes (ConditionalEvents, otros plugins) se rompen; API pública perdida |
| **Velocity/BungeeCord sync** | Solo stub en config; sin módulo `sync-velocity` real | Imposible usar en redes multi-servidor (caso de uso principal del original con MySQL) |

### 🟠 ALTO
| Gap | Descripción | Impacto |
|-----|-------------|---------|
| **MySQL/MariaDB storage** | Solo YAML (`storage.yml`); sin SQLite ni MySQL | No escala a redes; sin persistencia compartida entre servidores |
| **ConditionalEvents / MessageBus** | Evento custom `Message` no emitido; `MessageBus` diseñado pero no implementado | Plugins que escuchaban `me.majhrs16.cht.events.custom.Message` dejan de funcionar |
| **Private messages / Mentions** | API tiene `MessageType.PRIVATE`/`MENTION` pero host no expone `/msg` `/r` ni detección `@jugador` | Funcionalidad social básica perdida |
| **Connection loss indicator** | Sin `InternetCheckerAsync`; no hay fallback visual `[!]` | Usuarios no saben si traducción falla por red |

### 🟡 MEDIO
| Gap | Descripción | Impacto |
|-----|-------------|---------|
| **SQLite storage** | Intermedio entre YAML y MySQL; útil para single-server persistente | Pérdida de opción de storage robusto sin MySQL |
| **Cache TTL traducciones** | Cada fragmento = request HTTP; sin cache en memoria | Latencia y cuota API innecesarias en chats repetitivos |
| **Rate-limit proveedor (`pool.max-concurrent`)** | Schema lo declara pero `TranslatorsConfig` lo ignora (F7+) | Sin control de concurrencia saliente a Google/LibreTranslate |
| **Migración datos YAML→SQL** | Sin herramienta de migración | Operación manual propensa a errores al escalar |

### 🟢 BAJO
| Gap | Descripción | Impacto |
|-----|-------------|---------|
| **Cofres/Contenedores translation** | Nunca existió en original; feature request | Ninguno (paridad mantenida) |
| **Libros escritos translation** | Nunca existió en original; feature request | Ninguno (paridad mantenida) |
| **Fabric host funcional** | Solo stub (`fabric-host` compila pero no probado) | Limita a Spigot/Paper únicamente |

---

## 3. Comandos: Original vs Suite

| Comando ChatTranslator (`/cht`) | Comando Suite (`/suite`) | Diferencias UX |
|--------------------------------|--------------------------|----------------|
| `/cht` → ayuda | `/suite` → `status` (canales + traductor activo + knobs) | Suite muestra estado rico por defecto |
| `/cht reload` | `/suite reload` | Mismo comportamiento; Suite recarga layout completo atomico |
| `/cht lang` → muestra tu idioma | `/suite lang` → muestra tu idioma (normalizado `auto`/`off`/`código`) | Suite usa `off` en vez de `disabled` |
| `/cht lang <código>` | `/suite lang <auto\|off\|código>` | Suite valida códigos via `LangSetting.isValid()` |
| `/cht lang <jugador> <código>` (admin) | `/suite lang <jugador> <auto\|off\|código>` (admin) | Igual; permiso `textformattersuite.admin` |
| `/cht toggle` | `/suite toggle` | Suite alterna `auto`↔`off`; original no tenía toggle |
| `/cht toggle <jugador>` (admin) | `/suite toggle <jugador>` (admin) | Nuevo en Suite |
| `/cht version` | `/suite status` incluye versión | Suite integra versión en status |
| `/cht msg <jugador> <mensaje>` | ❌ No implementado | Gap: mensajería privada |
| `/cht reset` (config) | `/suite reset` → backup `backup/<timestamp>/` + regenera defaults | Suite hace backup automático con timestamp |
| `/dst link` (Discord) | Configurado vía `sync/discord.yml` + web-editor | Suite elimina comando linking; config declarativa |
| — | `/suite test full\|stress\|concurrency\|routing\|events\|perf\|sync` | **Nuevo**: test runtime integrado |

**Resumen UX:** Suite unifica todo bajo `/suite` con subcomandos consistentes; elimina `/dst` y `/cht msg`; añade `toggle` y `test`. La configuración Discord pasa de comandos interactivos a YAML declarativo (web-editor friendly).

---

## 4. Canales / Eventos

| Evento | ChatTranslator (original) | TextFormatter Suite |
|--------|---------------------------|---------------------|
| **Chat jugador** | `MessageListener` + `MessageEvent` custom → `from`/`to` formats | Canal `chat.global` (tipo `CHAT`); `Direction.INITIATOR` (eco) + `Direction.OTHERS` (broadcast) atómicos |
| **Join** | Grupo `join` en `formats.yml` | Canal `join` (tipo `EVENT`); `Direction.ALL`; presencia en registry = activado |
| **Quit** | Grupo `quit` en `formats.yml` | Canal `quit` (tipo `EVENT`); `Direction.ALL` |
| **Death** | Grupo `death` en `formats.yml` | Canal `death` (tipo `EVENT`); `Direction.ALL`; `%content%` = mensaje vanilla muerte |
| **Advancement** | Grupo `advancement` (1.12+) | Canal `advancement` (tipo `EVENT`); `Direction.ALL` |
| **Sign** | `Signs` listener + `signs.yml` (persiste por coords) | **No implementado** (API tiene `MessageType.SIGN` pero sin wiring Spigot) |
| **Private/Mention** | `/cht msg` + formato `mention` | `MessageType.PRIVATE`/`MENTION` en API; sin comando ni detección `@` en host |

**Nota arquitectura Suite:** Cada canal define su `permission` base (suscripción), `send-permission` y `receive-permission` opcionales para asimetría nativa ("todos leen, solo staff escribe"). Los eventos no-chat usan `Direction.ALL` (una sola unidad para todos); el chat usa dos unidades atómicas (emisor + receptores) con formatos y cancelación independientes.

---

## 5. Traducción: Proveedores, Cache, Rate-limit, Fallback, PAPI

| Aspecto | ChatTranslator | TextFormatter Suite |
|---------|----------------|---------------------|
| **Proveedores** | Solo Google Translate (endpoint `translate_a/single`, `client=gtx`) | **Google** (`GTranslate`, mismo endpoint) + **LibreTranslate** (`LTranslate`, REST `/translate` + `/detect`, API key opcional, normaliza `zh-CN`→`zh`, `pt-BR`→`pt`) |
| **Configuración** | `config.yml` → `translator: google` | `translators/google.yml` + `translators/libre.yml` (schema v2.2); `provider: google\|libre`, `active: true`, `base-url`, `api-key`, `pool.max-concurrent` (F7+) |
| **Selección activa** | Hardcoded Google | `TranslatorManager`: prioridad google→libre→fallback "none"; primera disponible gana |
| **Cache** | Ninguno (request por fragmento) | **Ninguno implementado** (pendiente F7+) |
| **Rate-limit proveedor** | Ninguno | Schema declara `pool.max-concurrent` pero `TranslatorsConfig` lo ignora (F7+) |
| **Fallback** | Si sin internet → prefijo `[!]` en formatos | `TranslationService`: `from==to` o `target==AUTO` → no traduce; proveedor indisponible → devuelve texto original; `detect()` fallback `en` |
| **Detección idioma** | `InternetCheckerAsync` + locale cliente | `Language.AUTO` → `TranslationService.detect()` delega a proveedor activo |
| **PAPI `%cot_*`** | **Sí**: `cot_translate`, `cot_var`, `cot_broadcast`, `cot_lang`, `cot_sendDiscord`, `cot_set`/`cot_get`/`cot_new` | **No** (módulo `coretranslator` deprecado solo bridge legacy `ChatMessage`↔`Message`, sin PAPI expansion) |
| **PAPI nativo `%ct_*%` / `$ct_*$`** | En formatos: `%ct_messages%`, `%ct_lang_source%`, `%player_name%`, etc. | En MiniMessage: `%player_name%`, `%lang_source%`, `%lang_target%`, `{index}`; delega a `PlaceholderResolver` (PAPI en Spigot) |

---

## 6. Sync: Discord, Telegram, HTTP, TCP-UDP, Velocity — Bidireccionalidad

| Protocolo | ChatTranslator | TextFormatter Suite | Bidireccional |
|-----------|----------------|---------------------|---------------|
| **Discord** | ✅ `/dst link` + bot token; canales configurados; embeds; intents; sync permisos | ✅ `DiscordSink` (REST v10 `/channels/{id}/messages` + WebSocket Gateway v10); `DiscordBridge` en host hace mirror outbound; inbound via gateway → `SyncListener` | ✅ **Sí** (Suite: gateway WebSocket persistente) |
| **Telegram** | ❌ | ✅ `SyncTelegramModule` + `TelegramSink` (long-poll `getUpdates` con watermark offset) | ✅ **Sí** (inbound via long-poll) |
| **HTTP/Webhook** | ❌ | ✅ `HttpSink`: outbound `POST` webhook + inbound `HttpServer` JDK (`POST /hook`) | ✅ **Sí** |
| **TCP** | ❌ | ✅ `TcpSink`: outbound connect + write JSON line; inbound `ServerSocket` accept loop (una línea/conexión) | ✅ **Sí** |
| **UDP** | ❌ | ✅ `UdpSink`: outbound `DatagramPacket`; inbound `DatagramSocket` receive loop | ✅ **Sí** |
| **Velocity/Bungee** | ✅ Storage MySQL compartido + sync permisos | ❌ **Stub only** (`sync/velocity.yml` en config/editor; sin módulo real) | ❌ **No** |

**Arquitectura Suite:** Todos los sinks implementan `SyncSink` (outbound `send(Message)`) + `SyncListener` (inbound `onMessage(sink, Message)`). El `MessageDispatcher` hace fan-out a sinks registrados. `DiscordBridge` en spigot-host hace mirror automático de mensajes despachados.

---

## 7. Storage: SQLite/MySQL, Migración

| Backend | ChatTranslator | TextFormatter Suite |
|---------|----------------|---------------------|
| **YAML** | ✅ `storage.yml` (uuid→lang, discordID) | ✅ `YamlUserLanguageStore` (`storage.yml`); cache RAM + hot-reload por `lastModified`; write-through atómico (temp+move) |
| **SQLite** | ✅ `sqlite` (JDBC embedded); tabla `storage(uuid, discordID, lang)` | ❌ **No** |
| **MySQL/MariaDB** | ✅ `mysql`/`mariadb` (JDBC); pooling básico; para BungeeCord | ❌ **No** |
| **Migración** | Auto al cambiar `storage.type`; crea tablas | ❌ **No** (solo YAML) |
| **API** | `Storage.set/get/reload` | `UserLanguageStore` (SPI): `languageOf(UUID)`, `save(UUID, String)`, `flush()` |

**Impacto:** Suite actual es single-server only. Para redes se requiere implementar `SqliteUserLanguageStore` + `MySQLUserLanguageStore` + tool migración.

---

## 8. Permisos: Modelo Original vs Nuevo (base + send/receive + iFlow)

### ChatTranslator (Original)
```
ChatTranslator.admin                                    → Admin plugin
ChatTranslator.chat.%s.messages    → Ver mensajes (por grupo formato, * = todos)
ChatTranslator.chat.%s.toolTips    → Ver tooltips
ChatTranslator.chat.%s.sounds      → Oír sonidos
ChatTranslator.chat.%s.color       → Ver colores
```
- Un único permiso por grupo de formato (`%s` = nombre grupo o `*`)
- Sin distinción send/receive: si tienes permiso, ves y oyes todo del grupo
- ConditionalEvents permite lógica extra externa

### TextFormatter Suite (Nuevo)
**Base (suscripción a canal):**
```
cht.<channel>                      → Poseer = suscrito al canal (recibe + ve)
```
**Asimetría nativa (opcional, por canal en `channels/<id>.yml`):**
```
send-permission:  cht.<channel>.send    → Solo quienes tienen pueden EMITIR
receive-permission: cht.<channel>.receive → Solo quienes tienen pueden RECIBIR
```
- Default: si no se definen `send/receive-permission`, base `cht.<channel>` = ACCEPT para ambos
- **iFlow Rules** (SpEL sobre `#msg`): pueden hacer `cancel()`, `skipTranslate()`, `setFormat(path)`, `setLangTarget(...)`, `redirect()`, `rate-limit()` por emisor/receptor/canal/dirección
- **PermissionChecker** en router: evalúa `sendPolicy()` (emisor) y `receivePolicy()` (receptor) ANTES de reglas

**Equivalencia práctica:**
| Original | Suite |
|----------|-------|
| `ChatTranslator.chat.global.messages` | `cht.chat.global` (base) + `cht.chat.global.receive` (opcional) |
| `ChatTranslator.chat.global.color` | Incluido en formato MiniMessage (no hay permiso separado color) |
| Admin plugin | `textformattersuite.admin` (para `/suite reload|reset|test|lang other|toggle other`) |

---

## Conclusión General

**Paridad funcional alta** en: chat translation, formatos (MiniMessage ≡ JSON legacy), eventos join/quit/death/advancement, Discord sync, anti-spam, selección idioma, config hot-reload.

**Gaps críticos bloqueantes para migración producción:**
1. **Sign translation** (feature visible usuarios)
2. **PAPI `%cot_*`** (rompe integraciones ConditionalEvents/otros plugins)
3. **Velocity/MySQL** (requerido para redes multi-servidor)

**Gaps altos:** MySQL storage, ConditionalEvents/MessageBus, Private messages, Connection loss indicator.

**Ventajas Suite (nuevo):** iFlow rules engine, permisos asimétricos nativos, Telegram/HTTP/TCP-UDP sync, Web Editor round-trip, test runtime, fallback multi-proveedor, arquitectura hexagonal testeable.

**Recomendación:** Priorizar implementación de Sign translation + PAPI `%cot_*` + Velocity sync + MySQL storage antes de migración producción. El resto son mejoras incrementales.

---

*Fin del reporte — Generado 2026-09-06*
