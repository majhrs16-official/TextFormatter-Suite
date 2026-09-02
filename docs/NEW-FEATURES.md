# NEW-FEATURES.md — Features nuevas implementadas y roadmap

> Documento vivo: documenta features completadas vs planeadas. Actualizar al cerrar cada fase.

---

## ✅ IMPLEMENTADO (2026-08-31, commit 4dc7375)

### Channel Type System
- **`Channel.Type` enum**: `CHAT` (player messages) y `EVENT` (join/quit/death/advancement)
- **`ChannelSelector` filtra por tipo**: solo considera canales `CHAT` para mensajes de jugadores
- **Default channels creados**:
  - `chat.global` → `type: CHAT`
  - `join.yml`, `quit.yml`, `death.yml`, `advancement.yml` → `type: EVENT`
- **Placeholders corregidos**: `%player_name%` en lugar de `<sender>` en todos los canales

### Tester Module (`suite/tester`)
- **25 tests automatizados runtime**: routing, eventos, traducción, formato, iFlow, concurrencia, stress, profiling
- **PerformanceProfiler**: mide CPU time (ThreadMXBean) + heap delta/used/max (MemoryMXBean) por test
- **Skip mechanism**: tests que requieren 2+ jugadores devuelven `skipped("Need 2+ online players")` en lugar de fallar
- **Comandos**: `/suite test full|stress <players> <msgs>|concurrency <threads> <msgs>`
- **Suite completa**: 25 tests en ~5s, todos PASS con 1 jugador

### HttpTransport → HttpURLConnection
- Migrado de `java.net.http.HttpClient` (problemas de módulos en Paper) a `HttpURLConnection`
- Usado por: GTranslate, LTranslate, sync-http, sync-telegram, sync-discord

### ConfigLoader → type field
- Lee campo `type` de `channels/*.yml` (CHAT|EVENT), default `CHAT`
- `ConfigPath.CHANNEL_TYPE` añadido al enum centralizado

### Channel Type Filtering en ChannelSelector
- `ChannelSelector.select()` ahora ignora canales `EVENT` para mensajes de chat
- Previene que canal `quit` sea seleccionado para chat normal

### ConfigValidator placeholder
- Clase placeholder en `spigot-host` para validación estructural futura (FASE 3)

### Messages Module (`suite/messages`)
- i18n centralizado: `MessagesCatalog` singleton con catálogos EN/ES
- Reemplaza strings hardcodeados en plugin

### Fixes de bugs heredados
- `SpigotScheduler.ticks()`: conversión correcta MS→ticks (redondeo, no truncar)
- `NmsLocaleBridge`: cache Class/Method/Field + log no silencioso
- `RateLimiter`: purga TTL (5s cada 1024 adquisiciones)
- `HttpSink`: start/stop idempotente + campos volatile
- `TcpSink`/`UdpSink`: campos volatile
- `HttpTransport`: `HttpURLConnection` en lugar de `HttpClient`
- `SpigotScheduler` y `NmsLocaleBridge` corregidos
- Memory pressure test: 10MB en lugar de 1GB

---

## 🔄 EN PROGRESO / PRÓXIMOS (FASE 4)

### 1. fabric-host funcional
- Paper 1.20.6+ listo y probado; Fabric pendiente (Loom 1.6.12)

### 2. Strings UI centralizados (i18n)
- Mover todos los strings hardcodeados a `lang/` (catalogos EN/ES)
- Recobrar 98% de strings en config (actualmente 0% en plugin)

### 3. Motor de reglas iFlow enriquecido
- Destino "channel" en reglas
- Permisos/PAPI dentro de SpEL
- `MessageEventBus` público para third-party
- `transform` real (F7+)

### 4. ConfigValidator real
- Validación estructural contra schema del editor
- Issues con shape del editor reportados en consola

### 5. Sistema de comandos dinámico (`/suite`)
- Topología desde `commands.yml` v2
- Acciones atómicas combinables
- Feedback reutilizando motor de chat
- Edición de config.yml desde comandos (estilo LuckPerms)

### 6. sync-velocity real
- Implementar o eliminar stub en editor/config

### 7. Observabilidad
- Metrics endpoint (`/metrics` Prometheus)
- Debug endpoints (`/debug/simulate`, `/debug/dump`)
- Healthchecks para sinks

---

## 📋 BACKLOG / IDEAS (del brainstorming original)

*El siguiente contenido es brainstorming histórico. Mover a "IMPLEMENTADO" o "EN PROGRESO" cuando se haga.*

### Translation
- Text discovery (extract/inject text de cualquier objeto)
- Traducción estructural (preservar Component semántica)
- Universal Text Pipeline (DETECT→PARSE→TRANSFORM→TRANSLATE→FORMAT→SYNC→OUTPUT)

### Formatting
- Arsenal de transformaciones (uppercase, rot13, base64, leetspeak, etc.)
- Unicode processing (NFC/NFD/NFKC/NFKD, grapheme segmentation, width calculation)
- Visual width (emojis, CJK, ANSI, MiniMessage, Adventure Components)
- Parser/serializer universal (Plain↔MiniMessage↔Adventure↔ANSI↔Markdown↔HTML↔JSON↔YAML)

### Sync
- Message synchronization fabric (broadcast a múltiples transports)
- Bridge declarativo YAML con loop detection (message-id, hop-count, route-history)
- Delivery guarantees (at-most-once, at-least-once, best-effort)
- Ordering guarantees (none, per-channel, global)
- Priority levels (low, normal, high)
- Message transformation entre transports (Discord embed ↔ Minecraft Message)
- UDP capabilities (broadcast, multicast, fragmentation, reliability custom)
- WebSocket subscriptions (`/ws/chat`, `/ws/events`, `/ws/sync`, `/ws/logs`)

### Web Editor
- YAML como lenguaje (variables, scopes, constantes, funciones, macros, imports, tipos, namespaces, condiciones, loops, composición)
- "Go to assembly" / "Show compiled representation" (high-level ↔ atomic operations)
- Compiler diagnostics tipados (no "YAML inválido")
- Formatter optimizer (fusionar operaciones equivalentes con prueba de equivalencia)
- Source maps (YAML line → AST → IR instruction → runtime operation)

### Universal Text Pipeline
```text
INPUT → DETECT → PARSE → TRANSFORM → TRANSLATE → FORMAT → SYNC → OUTPUT
```
(Cada etapa opcional)

---

## 📝 Notas de arquitectura

**Separación de áreas:**
| Área | Pregunta que responde |
|------|----------------------|
| **Translation** | ¿Qué debe decir el texto? |
| **Formatting** | ¿Cómo manipulo/represento ese texto? |
| **Sync** | ¿A dónde viaja y cómo llega? |
| **Web Editor** | ¿Cómo describo todo sin perder control de bajo nivel? |

**Ninguna área depende conceptualmente de Minecraft.** Minecraft es un consumidor más.

---

## 📚 Documentación relacionada
- `docs/PLAN.md` — Plan de ejecución vivo
- `docs/PROMPT.md` — Prompt rector (solo lectura)
- `docs/PROMPT_NOW.md` — Plan de acción de corto plazo
- `docs/ADR.md` — Decisiones de arquitectura
- `docs/PLAN.md` — Plan de ejecución vivo
- `docs/AUDITORIA.md` — Auditoría 2026-08-16
- `docs/AUDITORIA-2026-08-24.md` — Auditoría integral
- `docs/web-editor/DESIGN.md` — Diseño editor
- `docs/web-editor/schema-v2.2.md` — Schema v2.2
- `docs/historial/` — Logs de sesión

(End of file - total 160 lines)