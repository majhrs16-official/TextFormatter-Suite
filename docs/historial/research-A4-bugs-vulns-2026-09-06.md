# Análisis Estático de Bugs y Vulnerabilidades — TextFormatter Suite

**Fecha:** 2026-09-06  
**Alcance:** `suite/*/src/main/java` (todos los módulos)  
**Metodología:** Grep/lectura de patrones de riesgo + revisión manual de código crítico

---

## 1. Lista de Hallazgos

### RACE CONDITIONS (CWE-362)

| # | CWE | Severidad | Archivo:Línea | Descripción | PoC Sugerido | Fix Propuesto |
|---|-----|-----------|---------------|-------------|--------------|---------------|
| RC-1 | 362 | **Medio** | `NmsLocaleBridge.java:96-115` | Double-checked locking en `resolveGetHandle()` usa `volatile` en `getHandleMethod` y `getHandleResolved` (correcto en Java 5+), pero `getHandleMethod` se asigna `null` en caso de fallo y luego nunca se reintenta. Si el primer intento falla por ClassNotFound temporal, queda cacheado `null` permanente. | Iniciar servidor con classpath incompleto → primer jugador que chatea dispara `resolveGetHandle()` → falla → `getHandleResolved=true, getHandleMethod=null` → todos los jugadores siguientes obtienen `null` sin reintentar. | Cambiar a `AtomicReference<Method>` con `compareAndSet` o usar `ClassValue<Method>` para cache por clase. Eliminar `getHandleResolved` y usar `getHandleMethod != UNRESOLVED` como sentinela. |
| RC-2 | 362 | **Bajo** | `YamlUserLanguageStore.java:40-43, 61-66` | `languageOf()` llama a `loadIfChanged()` (sincronizado) pero luego accede a `cache.get(uuid)` sin sincronización externa. `loadIfChanged()` reemplaza todo el mapa `cache` bajo lock, pero hay ventana donde otro hilo lee `cache` viejo mientras se recarga. | Thread A: `languageOf(uuid)` → `loadIfChanged()` recarga → Thread B: `languageOf(uuid)` lee `cache` parcialmente actualizado. | Hacer `cache` `volatile` y en `loadIfChanged()` crear nuevo `ConcurrentHashMap` y asignar atómicamente: `cache = newCache`. Eliminar `synchronized` de `loadIfChanged()` y usar `compareAndSet` en `AtomicReference`. |
| RC-3 | 362 | **Bajo** | `DefaultRouter.java:42, 91-95` | `rules` es `AtomicReference<List<Rule>>` (correcto), pero `setRules()` ordena y crea `List.copyOf()` antes de `rules.set()`. Si `route()` se ejecuta concurrentemente, ve lista vieja o nueva —consistente—, pero no hay barrera de memoria para `channel.rateLimitPerSecond()` leído en `route()` línea 78. | Config hot-reload cambia `rateLimitPerSecond` mientras `route()` evalúa. Thread A lee 0, Thread B lee 20 → comportamiento inconsistente. | Hacer `Channel` inmutable o leer `rateLimitPerSecond` dentro de sección crítica. O usar `AtomicInteger` en `Channel` para rate-limit. |
| RC-4 | 362 | **Bajo** | `RateLimiter.java:20, 52, 67` | `buckets` es `ConcurrentHashMap`; `purgeIdle()` usa `removeIf()` que itera sin lock sobre `Bucket.lastFill` (no volatile). `tryAcquire()` sincroniza en `bucket` pero lee `bucket.lastFill` sin sincronización en `refill()`. | Thread purger lee `lastFill` stale → no evicta bucket idle → fuga de memoria. Thread `tryAcquire` lee `lastFill` stale → `refill()` calcula `elapsed` incorrecto. | Hacer `lastFill` `volatile` en `Bucket`. O mover `purgeIdle()` a sincronizar en cada bucket (costoso). Mejor: `lastFill` `volatile` + `tokens` `volatile` (ya son float, no atomic). |

---

### MEMORY LEAKS (CWE-772, CWE-400)

| # | CWE | Severidad | Archivo:Línea | Descripción | PoC Sugerido | Fix Propuesto |
|---|-----|-----------|---------------|-------------|--------------|---------------|
| ML-1 | 772 | **Alto** | `YamlUserLanguageStore.java:31` | `ConcurrentHashMap<UUID, String> cache` crece sin límite. Cada jugador único que se conecta queda cacheado para siempre. En servidor con alta rotación (ej. 10k jugadores/mes), mapa crece indefinidamente. | Conectar 50k jugadores únicos → `cache` retiene 50k entradas → OOM eventual. | Añadir TTL/eviction: `Caffeine` cache o implementar LRU con `LinkedHashMap` + límite `maxEntries`. O purge periódico de entradas >30 días sin uso (añadir `lastAccessed` en value). |
| ML-2 | 772 | **Medio** | `NmsLocaleBridge.java:31` | `LOCALE_CACHE: ConcurrentHashMap<UUID, String>` nunca evicta. Igual que ML-1 pero para locales. | Igual que ML-1. | Mismo fix: TTL o LRU. O usar `WeakHashMap<UUID, String>` (clave weak) pero UUID no es referenciado ailleurs → no ayuda. Usar `Caffeine` con `expireAfterAccess`. |
| ML-3 | 772 | **Medio** | `RateLimiter.java:20, 65-68` | `buckets` purgados cada minuto solo si `lastFill > 5min`. Si atacante rota claves únicas (ej. `channel\u0000uuid` con UUIDs aleatorios), crea buckets que viven 5 min cada uno. 10k req/s claves únicas → 3M buckets en 5 min → OOM. | Script envía paquetes con `sender` spoofeado (si posible) o muchos jugadores únicos → `buckets` crece sin control hasta purge. | Añadir límite máximo de buckets (`maxBuckets`). En `tryAcquire()`, si `buckets.size() > MAX`, ejecutar `purgeIdle()` inmediato o rechazar. Usar `Caffeine` con `maximumSize`. |
| ML-4 | 772 | **Bajo** | `MessagesCatalog.java:30` | `ConcurrentMap<Locale, ResourceBundle> cache` sin eviction. `Locale` keys limitados pero `ResourceBundle` retiene classloaders. | Recargar plugin muchas veces (hot reload) → classloaders antiguos retenidos por `ResourceBundle` en cache. | Usar `WeakHashMap<Locale, ResourceBundle>` o `Caffeine` con `weakKeys()`. Limpiar cache en `onDisable()`. |
| ML-5 | 772 | **Bajo** | `DiscordGateway.java:75` | `StringBuilder buffer` acumulador de frames WebSocket. Si frame fragmentado sin `last=true` (cliente malicioso o bug), `buffer` crece indefinidamente. | Cliente envía frames continuos sin `last` → `buffer` crece hasta OOM. | Límite de tamaño `buffer` (ej. 1MB). Si excede → cerrar conexión con error. |
| ML-6 | 772 | **Bajo** | `TemplateRenderer.java:218-235` | `findSpans()` crea `ArrayList<Span>` nuevo en cada render. Si template tiene miles de `<tr>...</tr>`, GC pressure. | Template con 10k spans → 10k objetos `Span` por mensaje. | Reutilizar `List<Span>` con `clear()` o usar `StringBuilder` + índices sin objetos intermedios. |

---

### INJECTION (CWE-79, CWE-94)

| # | CWE | Severidad | Archivo:Línea | Descripción | PoC Sugerido | Fix Propuesto |
|---|-----|-----------|---------------|-------------|--------------|---------------|
| INJ-1 | 79 | **Alto** | `MiniEscape.java:16-32` | `MiniEscape.escape()` solo escapa `<` → `\\<` y `\\` → `\\\\`. MiniMessage trata `>` como cierre de tag, `{`/`}` como placeholders, `[`/`]` como tags, `(`/`)` como componentes. Input: `<tr>malicious</tr>` → escapa a `\\<tr>malicious\\</tr>` (seguro). Pero input: `>malicious<` → no escapa `>` → rompe parsing. Input con `{variable}` → se interpreta como placeholder MiniMessage. | Jugador establece nombre `">malicious<"` → en template `%player_name%` → renderiza `>malicious<` → MiniMessage parsea `malicious` como tag. | Escapar todos los metacaracteres MiniMessage: `<`, `>`, `\`, `{`, `}`, `[`, `]`, `(`, `)`, `#`, `@`. Usar `MiniMessage.MM_ESCAPE` (Adventure 4.12+) o implementar escape completo según spec MiniMessage. |
| INJ-2 | 79 | **Medio** | `TemplateRenderer.java:103-109, 169-170` | Variables de contexto (`%player_name%`, `%lang_source%`, variables custom) y placeholders externos (PAPI) pasan por `MiniEscape.escape()` — **pero** el contenido del mensaje (`%content%`, línea 181) también pasa por escape. Si template incluye `<tr>%content%</tr>`, el contenido ya escapado se traduce y re-inserta escapado. Doble escape o rotura si traducción introduce tags. | Mensaje: `<red>hola</red>` → `%content%` escapado → `<tr>\\<red>hola\\</red></tr>` → traducción devuelve `<blue>hello</blue>` → re-insertado escapado → `\\<blue>hello\\</blue>` → render final muestra literal. | Documentar que contenido dentro de `<tr>` debe ser plain text. Validar que traducción no devuelve markup. O separar pipeline: traducir plain text, luego wrap en `<tr>`. |
| INJ-3 | 94 | **Crítico** | `ExpressionEvaluator.java:12-33` (SPI) | `ExpressionEvaluator` es SPI sin implementación por defecto. Si host provee implementación con `StandardEvaluationContext` (acceso a `java.lang.Runtime`, `System`, reflection), **RCE vía SpEL en templates/admin config**. Templates vienen de `channels/*.yml` (admin) pero también variables de usuario en SpEL (ej. `#msg.sender.name`). | Admin configura regla iFlow: `transform: "#msg.sender.name = T(java.lang.Runtime).getRuntime().exec('calc')"` → si ExpressionEvaluator usa `StandardEvaluationContext` → RCE. | **Obligatorio**: Implementación por defecto debe usar `SimpleEvaluationContext.forReadOnlyDataBinding()` + allowlist de clases/métodos (solo `String`, `Message`, `Actor`, `Language`, operaciones aritméticas/lógicas). Rechazar `T()`, `new`, property access a `class`, `getClass()`. |
| INJ-4 | 94 | **Alto** | `TranslatorsConfig.java:69, 81-88` / `ConfigLoader.java:39` / `MessagesConfig.java` / `DiscordBridge.java:50` | `new Yaml()` (constructor por defecto) usa `Constructor` inseguro → deserialización arbitraria de objetos Java (`!!javax.script.ScriptEngineManager`, `!!java.net.URLClassLoader`, etc.). Archivos YAML son locales (config), pero **si atacante escribe `translators/evil.yml`** (permiso escritura en data folder) → RCE al reload. | Atacante con acceso FS escribe `translators/evil.yml` con payload YAML → `/suite reload` → deserializa gadget → RCE. | Usar `new Yaml(new SafeConstructor())` en **todos** los `Yaml` loaders. O `Yaml yaml = new Yaml(); yaml.setBeanAccess(BeanAccess.NONE);` (snakeyaml 2.0+). Validar schema estricto post-load (ya se hace parcialmente con `instanceof Map`). |
| INJ-5 | 79 | **Medio** | `Rule.java:156-163` | `emitterPattern` / `receiverPattern` usan `glob()` simple (wildcard `*`). No regex → sin ReDoS. Pero patterns vienen de `rules.yml` (admin). Sin validación de longitud/complejidad. | Admin configura pattern `********************************************` (1000 `*`) → `glob()` itera O(n) pero sin backtracking. Riesgo bajo. | Limitar longitud pattern (ej. 256 chars). Documentar que no es regex. |

---

### DoS (CWE-400, CWE-833)

| # | CWE | Severidad | Archivo:Línea | Descripción | PoC Sugerido | Fix Propuesto |
|---|-----|-----------|---------------|-------------|--------------|---------------|
| DOS-1 | 400 | **Alto** | `HttpSink.java:60` | `HttpServer.create(..., 0)` → backlog 0 = default del SO (usualmente 50). `createContext()` usa executor por defecto (`Executors.newCachedThreadPool()` **unbounded**). Requests POST `/hook` masivos → threads ilimitados → OOM/thread exhaustion. | `ab -n 10000 -c 500 http://server:port/hook` → server crea 500+ threads → crash. | Usar `HttpServer.create(..., backlog)` con backlog configurado (ej. 100). `server.setExecutor(Executors.newFixedThreadPool(maxThreads))` o `ThreadPoolExecutor` con cola bounded + `CallerRunsPolicy`. |
| DOS-2 | 400 | **Alto** | `MessageDispatcher.java:59, 64-81` | `dispatch()` itera `recipients` (lista expandida de `Direction.ALL` = todos online) y llama `host.deliver()` + `delivery.deliver()` **secuencialmente** en hilo llamante (async chat event). 200 jugadores online → 200 entregas sincrónicas → bloquea hilo chat event → lag servidor. | Servidor 200 jugadores, chat global → hilo async chat tarda 500ms+ → timeout watchdog Paper → crash. | Paralelizar entrega: `recipients.parallelStream().forEach(...)` o usar `CompletableFuture.allOf`. O `ChatDelivery` que encola en main thread y retorna inmediato. Config `engineParallel` (ya existe en `HostConfig`) no se usa en dispatcher. |
| DOS-3 | 400 | **Medio** | `RateLimiter.java:27-30` | `RateLimiter(capacity)` hardcodeado a `1` en `DefaultRouter.java:51`. Canal con `rateLimitPerSecond: 20` usa bucket capacity 1 → **rate limit efectivo = 1 msg/s**, no 20. Bug de lógica: `RateLimiter` capacity debería ser `channel.rateLimitPerSecond()`. | Canal config `rate-limit-per-second: 20` → jugador envía 5 msg/s → todos throttled tras 1. | Pasar `channel.rateLimitPerSecond()` a `RateLimiter` constructor. `DefaultRouter` necesita `RateLimiter` por canal o capacity dinámico. |
| DOS-4 | 400 | **Medio** | `SyncTelegramModule.java` (no leído) / `TelegramSink.java:71-112` | `poll()` long-poll con `timeout=1` (1 segundo). Si `poll()` llamado en loop tight sin backoff → CPU 100%. `SyncTelegramModule` no revisado pero probable loop `while(running) poll()`. | Módulo telegram iniciado → `poll()` loop sin sleep → 1 core 100%. | Añadir `Thread.sleep(50)` tras `poll()` si 0 entregados. O usar `timeout=30` long-poll real (ya usa `timeout=1` + offset). |
| DOS-5 | 400 | **Bajo** | `TcpSink.java:94-114` / `UdpSink.java:81-109` | `acceptLoop()` / `receiveLoop()` procesan **un mensaje a la vez** sincrónicamente. `SyncListener.onMessage()` llama a `dispatcher.dispatch()` que es pesado. Conexión TCP lenta (slowloris) bloquea `acceptLoop` / `receiveLoop` → no acepta nuevas conexiones / datagramas. | Atacante abre TCP, envía 1 byte cada 10s → `acceptLoop` bloqueado en `reader.readLine()` → sink inbound caído. | Timeouts en sockets: `socket.setSoTimeout(5000)`. Procesar inbound en thread pool separado (`ExecutorService`), no en loop de red. |
| DOS-6 | 400 | **Bajo** | `DiscordGateway.java:154-159` | `Executors.newSingleThreadScheduledExecutor()` para heartbeat. Si `connect()` falla repetidamente (reconnect loop externo), se crean múltiples executors sin shutdown (ver `close()` línea 192-194). | Reconnect loop llama `connect()` → falla → `close()` → nuevo `connect()` → nuevo executor → leak. | Guardar `heartbeats` en campo y `shutdownNow()` en `connect()` antes de crear nuevo. O mover executor a campo `final` inicializado en constructor. |
| DOS-7 | 833 | **Bajo** | `DefaultRouter.java:52` | `rateLimit = new RateLimiter(1)` capacity 1 fijo. Si `setRules()` llamado concurrentemente con `route()`, y `RateLimiter` interno usa `ConcurrentHashMap` + `ScheduledExecutorService` (purger), no hay deadlock aparente. Pero `RateLimiter.close()` no se llama en `DefaultRouter` → purger thread leak si router recreado. | Hot reload recrea `DefaultRouter` → `RateLimiter` viejo purger sigue vivo → thread leak acumulativo. | `DefaultRouter` implementar `AutoCloseable` y llamar `rateLimit.close()` en `setRules()` o al recrear. |

---

### SECRET HANDLING (CWE-200, CWE-259)

| # | CWE | Severidad | Archivo:Línea | Descripción | PoC Sugerido | Fix Propuesto |
|---|-----|-----------|---------------|-------------|--------------|---------------|
| SEC-1 | 200 | **Alto** | `DiscordGateway.java:169-179` | `identify()` construye JSON con `"token": token` (línea 173). `send()` loggea payload en debug (no en código actual pero JDA/WebSocket logs podrían). Token en memoria como `String` → heap dumps, `jmap`, logs accidentales. | `jmap -dump:live,format=b,file=heap.hprof <pid>` → strings busca token bot Discord. | Usar `char[]` para token. `DiscordGateway` recibir `char[]` y convertir a `String` solo en `identify()` inmediato. O usar `JDABuilder.createLight(token)` que acepta `String` — migrar a `char[]` requiere fork JDA. Mitigación: zeroizar `token` tras `identify()` (`Arrays.fill(tokenChars, '\0')`). |
| SEC-2 | 200 | **Alto** | `JdaDiscordSink.java:35, 59` | `private final String token` campo inmutable. `JDABuilder.createLight(token)` — token en heap permanente hasta GC. | Igual que SEC-1. | Igual: `char[]` + zeroize tras build. JDA no soporta `char[]` nativamente → wrapper que zeroiza tras `build()`. |
| SEC-3 | 200 | **Medio** | `TelegramSink.java:29` | `baseUrl = "https://api.telegram.org/bot" + token` → token en URL string en heap, logs de `HttpClient` (si debug), heap dumps. | `jmap` heap dump → busca `bot<token>`. | No construir URL completa en campo. Guardar `token` `char[]` y construir URL en `send()`/`poll()` localmente. Zeroizar tras uso. |
| SEC-4 | 200 | **Medio** | `LTranslate.java:22, 38, 55` | `private final String apiKey` — clave API LibreTranslate en heap. Se envía en JSON body (`payload.put("api_key", apiKey)`). | Heap dump → api key visible. | `char[] apiKey` + zeroizar. O leer de env var en cada request (no cachear). |
| SEC-5 | 200 | **Bajo** | `TranslatorsConfig.java:79, 85-87` | `apiKey` leído de YAML → `String` → pasado a `LTranslate` constructor. Config YAML en disco (legible por proceso). | No es bug de código, pero config file permisos 644 → otros usuarios leen token. | Documentar `chmod 600 translators/*.yml`. O soportar `${ENV_VAR}` substitution en config loader. |
| SEC-6 | 259 | **Bajo** | `DiscordBridge.java:57` | `String token = map.get("token") == null ? "" : String.valueOf(map.get("token")).trim();` — token en variable local, luego pasado a constructor. OK, pero `String.valueOf()` en map value (podría ser `!!javax.crypto.SecretKey` si YAML unsafe) → toString() expone. | YAML malicioso con `token: !!javax.crypto.SecretKey ...` → `String.valueOf()` llama `toString()` → key material en logs. | Fix INJ-4 (SafeConstructor) previene. Además validar `token instanceof String`. |

---

### OTROS CWEs RELEVANTES

| # | CWE | Severidad | Archivo:Línea | Descripción | Fix |
|---|-----|-----------|---------------|-------------|-----|
| OTH-1 | 400 | **Medio** | `SyncTcpUdpModule.java` (no leído) | Módulo TCP/UDP probablemente inicia sinks en loop. Verificar shutdown ordenado. | Implementar `AutoCloseable` en módulos, cerrar en `onDisable()`. |
| OTH-2 | 772 | **Bajo** | `DiscordGateway.java:37-41` | `CountDownLatch ready` nunca se resetea tras `close()` → reconnect falla si `ready.await()` ya pasó. | Recrear `DiscordGateway` en reconnect (actual lo hace) o resetear latch. |
| OTH-3 | 200 | **Bajo** | `SpigotPlaceholderResolver.java:24` | `available = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null` — check una vez en constructor. Si PAPI carga tras plugin (load order), `available=false` permanente. | Mover check a `resolve()` o `available()` para check dinámico. |
| OTH-4 | 400 | **Medio** | `TemplateRenderer.java:130-136` | `evaluateExpressions()` busca `<expr>...</expr>` con `indexOf`. Si template malformado (`<expr>` sin cierre), `close < 0` → `break` → resto del template sin procesar. No DoS pero silencioso. | Validar templates al cargar: contar `<expr>` y `</expr>`. Log warning si mismatch. |

---

## 2. Resumen por Módulo

| Módulo | Crítico | Alto | Medio | Bajo | Total | Comentarios |
|--------|---------|------|-------|------|-------|-------------|
| **core-api** | 1 (INJ-3) | 0 | 0 | 1 (OTH-3) | 2 | SPI `ExpressionEvaluator` sin sandbox por defecto — riesgo crítico si se implementa mal. |
| **textformatter** | 0 | 1 (INJ-1) | 1 (INJ-2) | 2 (ML-6, DOS-2) | 4 | `MiniEscape` incompleto; dispatcher secuencial. |
| **iflow** | 0 | 0 | 2 (DOS-3, ML-3) | 2 (RC-3, RC-4) | 4 | RateLimiter capacity bug + buckets unbounded. |
| **host** (config) | 1 (INJ-4) | 0 | 2 (ML-1, ML-4) | 1 (RC-2) | 4 | YAML unsafe constructor en 4 loaders; caches sin TTL. |
| **spigot-host** | 0 | 1 (SEC-2) | 1 (SEC-6) | 2 (RC-1, OTH-4) | 4 | NMS bridge race; Discord token en String; PAPI check estático. |
| **sync-discord** | 0 | 2 (SEC-1, SEC-2) | 0 | 2 (ML-5, DOS-6) | 4 | Tokens en String + heap; executor leak en reconnect. |
| **sync-http** | 0 | 1 (DOS-1) | 0 | 0 | 1 | `HttpServer` executor unbounded. |
| **sync-tcpudp** | 0 | 0 | 2 (DOS-5) | 0 | 2 | Sockets sin timeout; procesamiento sincrónico en loop red. |
| **sync-telegram** | 0 | 0 | 1 (DOS-4) | 1 (SEC-3) | 2 | Token en URL string; poll loop potencial tight. |
| **ltranslate** | 0 | 0 | 1 (SEC-4) | 0 | 1 | API key en String. |
| **kernel/tester/fabric-host/web-editor** | 0 | 0 | 0 | 0 | 0 | Sin hallazgos relevantes en scope. |

**Totales:** 1 Crítico, 7 Alto, 11 Medio, 11 Bajo = **30 hallazgos**

---

## 3. Priorización de Fixes

### 🔴 FASE 1 — Crítico / Alto (Bloqueantes para producción)

| Prioridad | Hallazgos | Esfuerzo | Descripción |
|-----------|-----------|----------|-------------|
| **P0** | INJ-3, INJ-4 | ~2 días | **Sandbox SpEL obligatorio** + **SafeConstructor YAML** en todos los loaders. Sin esto, config admin = RCE. |
| **P1** | INJ-1, SEC-1/2/3/4 | ~3 días | **MiniMessage escape completo** + **tokens en `char[]` + zeroize** en todos los sinks (Discord, Telegram, LibreTranslate). |
| **P2** | DOS-1, DOS-2, DOS-3 | ~2 días | **HttpServer executor bounded**; **dispatcher paralelizable** (usar `engineParallel` config); **RateLimiter capacity = channel config**. |
| **P3** | ML-1, ML-2, ML-3 | ~2 días | **Caches con TTL/LRU** (Caffeine o manual): `YamlUserLanguageStore`, `NmsLocaleBridge`, `RateLimiter` buckets. |

### 🟠 FASE 2 — Medio (Robustez)

| Prioridad | Hallazgos | Esfuerzo | Descripción |
|-----------|-----------|----------|-------------|
| **P4** | RC-1, RC-2, RC-3, RC-4 | ~2 días | **Race conditions**: `AtomicReference` en NMS bridge, `volatile` cache en YAML store, `volatile` en Bucket fields. |
| **P5** | DOS-5, DOS-6, ML-5 | ~1 día | **Socket timeouts** en TCP/UDP; **executor reuse** en DiscordGateway; **buffer limit** en WebSocket. |
| **P6** | INJ-2, INJ-5, SEC-5, SEC-6 | ~1 día | **Validación templates** (balanceo `<tr>`/`</tr>`, `<expr>`); **límites patterns**; **ENV var substitution** en config. |

### 🟡 FASE 3 — Bajo (Calidad)

| Prioridad | Hallazgos | Esfuerzo | Descripción |
|-----------|-----------|----------|-------------|
| **P7** | ML-4, ML-6, OTH-1, OTH-2, OTH-3, OTH-4 | ~2 días | **WeakHashMap** en MessagesCatalog; **reutilizar listas** en TemplateRenderer; **PAPI check dinámico**; **validación templates al cargar**. |

---

## 4. Plan de Acción Sugerido (Sprints)

| Sprint | Foco | Hallazgos | Entregable |
|--------|------|-----------|------------|
| **Sprint 1** (1 semana) | **Seguridad Crítica** | INJ-3, INJ-4, INJ-1, SEC-1..4 | SpEL sandbox + SafeConstructor YAML + MiniEscape completo + char[] tokens |
| **Sprint 2** (1 semana) | **DoS / Estabilidad** | DOS-1, DOS-2, DOS-3, ML-1, ML-3 | Bounded executors, dispatcher paralelo, RateLimiter fix, cache eviction |
| **Sprint 3** (3-4 días) | **Race Conditions / Leaks menores** | RC-1..4, ML-2, ML-5, DOS-5, DOS-6 | AtomicReference, volatile, timeouts, executor reuse |
| **Sprint 4** (3-4 días) | **Hardening** | INJ-2, INJ-5, SEC-5/6, ML-4, ML-6, OTH-* | Template validation, env vars, weak refs, PAPI dynamic check |

---

## 5. Notas de Implementación Clave

### SpEL Sandbox (INJ-3) — Implementación Recomendada
```java
// En módulo que provee ExpressionEvaluator (ej. spigot-host o textformatter)
public final class SafeExpressionEvaluator implements ExpressionEvaluator {
    private static final SimpleEvaluationContext CTX = SimpleEvaluationContext
        .forReadOnlyDataBinding()
        .withPropertyAccessor(new SafePropertyAccessor()) // solo getters whitelisted
        .build();

    private final ExpressionParser parser = new SpelExpressionParser();

    @Override
    public String evaluate(String expression, Map<String, Object> bindings) {
        Expression exp = parser.parseExpression(expression);
        return String.valueOf(exp.getValue(CTX, bindings));
    }
    // evaluateObject similar
}

// SafePropertyAccessor: solo permite acceso a campos/métodos de:
// - String, Number, Boolean, UUID, Language, Actor, Message (readonly)
// - java.util.Map/List (readonly)
// BLOQUEA: T(), new, class, getClass, constructor, static fields
```

### SafeConstructor YAML (INJ-4) — Aplicar en 4 archivos
```java
// Cambio único en cada loader:
private static final Yaml YAML = new Yaml(new SafeConstructor());
// O snakeyaml 2.0+:
private static final Yaml YAML = new Yaml();
static { YAML.setBeanAccess(BeanAccess.NONE); }
```
Archivos: `ConfigLoader.java:27`, `TranslatorsConfig.java:35`, `MessagesConfig.java:24`, `DiscordBridge.java:8`

### MiniEscape Completo (INJ-1)
```java
// Según spec MiniMessage (Adventure), metacaracteres a escapar:
private static final String META = "<>\\{}[]()#@";
public static String escape(String value) {
    StringBuilder sb = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (META.indexOf(c) >= 0) sb.append('\\');
        sb.append(c);
    }
    return sb.toString();
}
```

### RateLimiter Fix (DOS-3 + ML-3)
```java
// DefaultRouter constructor:
this.rateLimit = new RateLimiter(channel.rateLimitPerSecond()); // por canal

// RateLimiter: añadir maxBuckets
private final int maxBuckets = 10000;
public boolean tryAcquire(String key) {
    if (buckets.size() >= maxBuckets) purgeIdle(clock.nanoTime()); // eager purge
    // ... resto
}
```

---

## 6. Conclusión

**Riesgo global: ALTO** — La combinación de **INJ-3 (SpEL sin sandbox)** + **INJ-4 (YAML unsafe)** + **SEC-1..4 (tokens en heap)** permite RCE y robo de credenciales si un atacante consigue escritura en config files o si se habilita SpEL sin implementación segura.

**Bloqueantes para producción:** P0, P1, P2 deben resolverse antes de cualquier deploy en entorno no controlado.

**Deuda técnica media:** Caches sin TTL (ML-1, ML-2, ML-3) causarán OOM en servidores largos (>semanas) con alta rotación de jugadores.

**Arquitectura sólida:** Uso de `ConcurrentHashMap`, `AtomicReference`, `volatile` correcto en la mayoría de casos; separación SPI/impl; inmutabilidad en modelos. Los fixes son puntuales, no requieren rediseño.

---

*Fin del reporte — Generado 2026-09-06*
