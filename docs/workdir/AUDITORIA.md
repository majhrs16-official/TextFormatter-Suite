# Auditoría técnica integral — TextFormatter Suite

> **Ciclo:** P0.6 (ADR-034) · **Cierre:** 2026-11-06
> **Commit auditado:** `1cc3a77f` — *fix(web-editor): elimina la etiqueta v2.1 y sincera el README*
> **Fecha del commit:** 2026-11-05 22:00:41 −0400
> **Base:** `1.0.0` (pre-release) · **Rama:** `main`
> **Gate:** `./gradlew build checkArchitecture checkLocks --no-daemon` → **BUILD SUCCESSFUL** (20 s, 361 tareas)
> **Tests:** **1.441** ejecutados en **42 módulos** · 0 fallos · 0 errores · 10 omitidos
> **Editor web:** `npm run check` → **129 comprobaciones, 0 fallos**

---

## 1. Identificación de la versión examinada

| Dato | Valor |
|---|---|
| Repositorio | `majhrs16-official/TextFormatter-Suite` |
| Commit | `1cc3a77f` (HEAD) |
| Commits totales | 447, del 2026-08-16 al 2026-11-05 |
| Módulos Gradle | **43** (`settings.gradle`) |
| Archivos Java | 561 |
| Líneas Java | **82.434** (50.031 en `src/main`, 32.403 en `src/test`) |
| Lockfiles | 44 · proyectos sin lockfile: **0** |
| Módulos neutrales/adaptadores escaneados | **36**, 0 violaciones |
| Versión de artefactos | `1.0.0` |
| Esquema YAML | `config-version: 1.0` |

**Nota de estado del árbol.** Durante la sesión se detectó que los archivos rastreados habían sido sobrescritos por una instantánea antigua del proyecto (coincidente con commits como `a9da105f`, `ea657968` y `84491c2a`): `build.gradle` sin `checkArchitecture`, 31 módulos en lugar de 43, ADR en versión `v2.1`, PLAN sin P0.6 y README con «29 módulos». Se restauraron los archivos rastreados desde `HEAD` y se movieron 167 archivos no rastreados de esa instantánea a `/tmp/opencode/quarantine-20261105/` (cuarentena reversible, con rutas preservadas). El árbol auditado es, por tanto, `HEAD` más diferencias exclusivamente de fin de línea (`CRLF`) en 10 `gradlew.bat`, sin cambios de contenido (`git diff --ignore-cr-at-eol` vacío). Este incidente es operativo, no un defecto del proyecto, y condiciona la reproducibilidad si no se parte de `HEAD`.

---

## 2. Cobertura de auditoría

**Examinado**

- Los 43 módulos: `build.gradle`, lockfile, README y estructura de paquetes.
- Puntos de entrada y composición: `TextFormatterSuitePlugin`, `TextFormatterSuiteMod`, `SuiteRuntime`, `ModuleHost`, `PackageRuntime`, `ModuleClassLoader`, `ServiceRegistry`, `PackageLayout`, `PackageCache`.
- Contratos SPI y puertos: `core-api` completo a nivel de interfaz, `core-render`, `MessageDispatch`, `ChatDelivery`, `ChannelRegistry`, `TranslationService`, `ActorDirectory`, `PermissionChecker`, `IflowHub`, `SyncSink`, `Transport`.
- iFlow: `iflow` (hub, router, configuración), `iflow-vm` (motor, MMU, HOST, fuel, pool), `iflow-wasm` (Chicory, ABI v3), `iflow-firewall` (SpEL), compilador `tools/iflow-compiler`.
- Pipeline: `SuiteHost`, `MessageDispatcher`, `DefaultSyncBus`, sinks HTTP/Telegram/Discord/WebSocket/Velocity/TCP-UDP, `HttpTransport`.
- Seguridad: secretos, autenticación, SSRF, path traversal, verificación de paquetes, endpoints de observabilidad.
- Gestión de módulos: kernel, manager-spi/core/apt, instalación, journal, repositorios, upgrades.
- Traducción: `chattranslator`, `gtranslate`, `ltranslate`, `coretranslator`.
- Formateo: `textformatter` (`TemplateRenderer`, `MiniEscape`, canales, `<tr>`).
- Observabilidad, presets, inworld, tester, loadtest, performance, extension-api, example-extension.
- Editor web: `package.json`, scripts, `js/**`, tests unitarios y de integración, `npm run check`.
- Historial Git: 447 commits, refactors de monolito a 43 módulos, reescritura de iFlow y purga pre-1.0.
- Evidencia ejecutada: gate Gradle, 1.441 tests, 129 comprobaciones del editor, inspección de resultados JUnit XML.

**No examinado o no certificado**

- Ejecución en servidores reales Spigot/Paper/Fabric/Velocity (no existe E2E; ver TF-012).
- Benchmarks ejecutados: el módulo `loadtest` contiene JMH y Gatling reales, pero no hay resultados publicados ni umbrales de regresión.
- Pruebas de concurrencia bajo carga sostenida y de fallos parciales de red.
- Revisión línea a línea de las 82.434 líneas Java: la cobertura es estructural y dirigida a riesgo, no una certificación formal.
- Pruebas de integración del editor web (`test:integration`) no forman parte de `npm run check`.
- Compatibilidad real con las versiones de Minecraft declaradas.

Toda conclusión de este informe se clasifica como **HECHO** (verificado en código o por ejecución), **INFERENCIA** (derivada del código) o **HIPÓTESIS** (requiere benchmark o reproducción). No se presentan hipótesis como hechos.

---

## 3. Resumen ejecutivo

TextFormatter Suite es una reescritura modular seria de un plugin de chat: 43 módulos Gradle, un núcleo Java sin dependencias de plataforma, contratos SPI reales, adaptadores delgados y una suite de 1.441 tests que pasa íntegra. La dirección de dependencias se sostiene: 36 módulos neutrales/adaptadores fueron escaneados sin importar SDK de plataforma ni internals del host. El motor iFlow (VM de 16 bits, MMU, servicios HOST, fuel) y el formateador MiniMessage tienen profundidad real, no son fachadas.

El problema principal no es la calidad del código aislado, sino **la rigidez del composition root**: `SuiteRuntime.rebuildPipeline()` exige ocho servicios concretos y un hub iFlow con backend activo; si falta uno, el pipeline queda «unavailable» y la suite arranca pero no procesa chat. Eso contradice el objetivo de que ningún módulo sea obligatorio y es el bloqueo real de PL-1. Le siguen cuatro riesgos de seguridad/robustez: fail-open de `PERMISSION`/`WORLD`, secretos de Telegram en `String`, comparaciones de token no constantes y pérdida silenciosa de mensajes en `SyncBus` con retorno de éxito falso.

Fortalezas destacables: `PackageLayout`/`PackageCache` (path traversal, tamaño exacto, SHA-256, promoción atómica), `HttpTransport` (SSRF, redirects manuales, pinning), `ModuleHost` (upgrade preparado antes de tocar el módulo activo, liberación verificada) y la separación `core-api` → features → adaptadores. La deuda más visible es documental: README y wiki anuncian `/debug/*` y extensiones que el runtime no cablea, y el editor web deja sus pruebas de integración fuera de `check`.

**Veredicto:** base técnica sólida y honesta en lo que declara implementado; madurez funcional alta para unit tests y baja para operación real. No está lista para declarar estabilidad ni escala sin cerrar TF-001, TF-002, TF-005 y el ciclo E2E.

| ID | Tipo | Severidad | Confianza | Título |
|---|---|---|---|---|
| TF-001 | Defecto de diseño | Alta | Confirmado | El pipeline exige módulos concretos; la suite no funciona sin ellos |
| TF-002 | Vulnerabilidad | Alta | Confirmado | `PERMISSION`/`WORLD` sin qualifier entregan a todos los jugadores |
| TF-003 | Vulnerabilidad | Media | Confirmado | El token de Telegram queda en un `String` (`baseUrl`) |
| TF-004 | Vulnerabilidad | Media | Confirmado | Secretos HTTP/WebSocket en `String` y comparación no constante |
| TF-005 | Bug | Media | Confirmado | `SyncBus.broadcast` devuelve éxito aunque todos los sinks descarten |
| TF-006 | Bug | Media | Confirmado | `broadcastAsync` sondea la cola sin timeout ni identidad de mensaje |
| TF-007 | Defecto de diseño | Media | Confirmado | El SDK de extensiones no está conectado al runtime |
| TF-008 | Deuda técnica | Media | Confirmado | `/debug/*` no se instancia en producción y dos endpoints son stubs |
| TF-009 | Vulnerabilidad | Media | Probable | Repositorios de paquetes sin HTTPS obligatorio ni firma |
| TF-010 | Bug | Media | Probable | Rebuild del pipeline puede rechazar un dispatch en vuelo |
| TF-011 | Rendimiento | Media | Hipótesis | `dispatch` bloquea al llamante y es secuencial por defecto |
| TF-012 | Deuda técnica | Baja | Confirmado | Sin E2E real y con integración web fuera de `check` |

---

## 4. Arquitectura real

La arquitectura declarada y la implementada coinciden en lo esencial: **núcleo neutral + puertos SPI + adaptadores de plataforma + paquetes de features**. La diferencia está en quién decide qué es obligatorio: el diseño dice «módulos opcionales»; el composition root decide lo contrario en tiempo de ejecución.

```
┌──────────────────────────── Plataformas ────────────────────────────┐
│ spigot-host (Paper API)          fabric-host (Fabric/Loom)          │
│  └─ SuiteRuntime                  └─ SuiteRuntime                   │
└───────────────┬───────────────────────────────┬─────────────────────┘
                │ PlatformPorts (puertos)        │
┌───────────────▼───────────────────────────────▼─────────────────────┐
│ suite-runtime: composición, ModuleHost, PackageRuntime, comandos     │
│   ├─ kernel: descubrimiento, grafo, ServiceLoader, aislamiento       │
│   ├─ manager-spi/core/apt: paquetes, repos, journal, instalación     │
│   └─ pipeline: SuiteHost + MessageDispatcher                          │
└───────────────┬──────────────────────────────────────────────────────┘
                │ servicios publicados por paquetes
┌───────────────▼──────────────────────────────────────────────────────┐
│ Features neutrales                                                    │
│ textformatter · iflow(hub) · iflow-vm · iflow-wasm · iflow-firewall  │
│ chattranslator · gtranslate · ltranslate · coretranslator (legacy)   │
│ sync-bus · sync-{http,telegram,discord,websocket,velocity,socket,...} │
│ transport · observability · presets · inworld · tester · extension-api│
└───────────────┬──────────────────────────────────────────────────────┘
                │ contratos
┌───────────────▼──────────────────────────────────────────────────────┐
│ core-api (0 dependencias de runtime) + core-render (Adventure)       │
└──────────────────────────────────────────────────────────────────────┘
```

**Capas y dependencias**

| Capa | Módulos | Puede depender de | Evidencia |
|---|---|---|---|
| Contratos | `core-api` | nada (solo JUnit en test) | `src/core-api/build.gradle` |
| Render neutral | `core-render` | `core-api`, Adventure | `build.gradle` |
| Runtime | `suite-runtime`, `kernel`, `host` | `core-api`, `core-render`, YAML | `checkArchitecture` |
| Features | `textformatter`, `iflow*`, `sync-*`, `transport`, `presets`, `inworld`, `observability`, `tester` | `core-api` (+ contratos compartidos) | 36 módulos escaneados |
| Adaptadores | `spigot-host`, `fabric-host` | `core-api`, `core-render`, `suite-runtime` | `checkArchitecture` |
| Legacy declarado | `coretranslator` (Bukkit/PAPI), `iflow-firewall` | plataforma / `core-api` | ADR-035, ADR-034 |

**Composición real.** `SuiteRuntime` es el composition root. En `start()` construye `ModuleHost`, registra los servicios de plataforma, crea `PackageRuntime`, activa los paquetes instalados y llama a `rebuildPipeline()` (`SuiteRuntime.java:150-169`). `ModuleHost` descubre módulos `Module` por `ServiceLoader`, resuelve dependencias y publica servicios en un `ServiceRegistry`. `PackageRuntime` instala ZIPs, valida layout/SHA-256 y activa los JAR comprometidos.

**Lo que no es hexagonal.** El pipeline no se compone por puertos: `rebuildPipeline()` hace `requireService(...)` de ocho tipos y `resolveRouter()` exige `IflowHub` (`SuiteRuntime.java:203-211`, `245-253`). La dependencia es de runtime, no de compilación, por lo que `checkArchitecture` no la detecta. Es el hallazgo TF-001 y la razón por la que PL-1 necesita un ADR, no un parche.

**Doble modelo de extensión.** `core-api.Module` (paquetes instalables, usado por el runtime) y `extension-api.Extension` (SDK con `ExtensionManager`) conviven. Solo el primero está conectado (TF-007).

---

## 5. Flujo completo del sistema

**Entrada.** `sync-bukkit` captura chat/join/quit/death y lo descarga a su scheduler para no bloquear el hilo del servidor (`BukkitChatListener.java:74`). El adaptador entrega a `MessageDispatch`; `SuiteRuntime.RuntimeMessageDispatch` resuelve el dispatcher activo o falla con «pipeline unavailable» (`SuiteRuntime.java:695-712`).

**Ruteo.** `MessageDispatcher.dispatch()`:

1. Descarta mensajes cancelados.
2. Resuelve idioma de origen una vez (`host.resolveSourceLanguage`).
3. Comprueba rate limit de emisión.
4. Expande el `Direction` a destinatarios (`expand`).
5. Si `engine.parallel=true`, procesa destinatarios en un pool (4-32 hilos, cola 1.000, `CallerRunsPolicy`); si no, secuencial en el hilo llamante.
6. `host.deliver()` aplica el `Router` de iFlow (`RouteDecision`), traduce y renderiza.
7. Entrega con `ChatDelivery`; el adaptador Spigot salta al hilo principal (`SpigotChatDelivery.java:31-42`).
8. Un `sleepMillis` se programa en un scheduler dedicado para no bloquear.
9. `SyncBus` (si el paquete está activo) recibe el mensaje y hace fan-out a los sinks.

**Salida.** `ChatDelivery` (jugador/consola) y `SyncSink` (HTTP, Telegram, Discord, WebSocket, Velocity, TCP/UDP). Cada sink declara semántica (`AT_LEAST_ONCE`/`AT_MOST_ONCE`) y orden (`PER_CHANNEL`).

**Punto crítico.** El pipeline no es una cadena de etapas opcionales: es un grafo fijo de servicios obligatorios. Si falta `TranslationService` o `MessageComponentRenderer`, no se degrada la etapa: no hay pipeline.

---

## 6. Evaluación por feature

### 6.1 Formateo MiniMessage (`textformatter`, 47 tests)

- **Qué hace:** renderiza plantillas con `TemplateRenderer`, escapa MiniMessage (`MiniEscape`, 10 caracteres), resuelve canales y etiquetas `<tr>`.
- **Cómo:** `ChannelRegistry` carga `channels/*.yml`, `TemplateRenderer` sustituye placeholders y aplica formato; `MessageComponentRenderer` conserva `Component` para Adventure.
- **Calidad:** buena separación motor/configuración; tests cubren canales, escape y traducción estructural.
- **Extensibilidad:** alta por canales y placeholders; media para nuevas etapas (el orden está fijado por `SuiteHost`).
- **Riesgo:** el renderizado se ejecuta por destinatario dentro del dispatch; con plantillas grandes y muchos jugadores es un hotspot (ver §14).

### 6.2 iFlow VM (`iflow-vm`, 399 tests, 6.907 líneas)

- **Qué hace:** máquina de 16 bits con memoria plana de 64 KB, MMU de páginas de 4 KB, ISA `.flow`, servicios HOST y presupuesto de fuel (100.000 por evaluación).
- **Cómo:** `VmPolicyEngine` publica `Router`; `VmInterpreter` ejecuta; `HostServiceRegistry` autoriza servicios por capacidades del programa; `ExecutionContextPool` reutiliza contextos.
- **Calidad:** la reescritura corrigió los hallazgos históricos de concurrencia: el pool usa `ConcurrentLinkedQueue` (`ExecutionContextPool.java:26`) y ya no existe el campo compartido `allowedCapabilities` que motivó IFLOW-001/002 en la auditoría anterior.
- **Límites:** la paridad `.flow` v3/VM sigue pendiente (W3.5, `PLAN.md:34-35`, `198-199`). No hay E2E de una política real atravesando el pipeline.
- **Potencial:** el activo técnico más diferencial del proyecto; su valor depende de que el hub y el pipeline lo traten como etapa sustituible.

### 6.3 iFlow WASM (`iflow-wasm`, 60 tests)

- **Qué hace:** backend Chicory con ABI v3 (`PolicyAbiV3`, `TlvCodec`, `WasmPolicyEngine`).
- **Calidad:** contrato explícito y tests de política; línea paralela a la VM, no un shim.
- **Riesgo:** dos implementaciones del mismo contrato sin matriz de paridad ejecutable; divergencia silenciosa posible. W3.5 es deuda reconocida, no oculta.

### 6.4 iFlow Firewall (`iflow-firewall`, 47 tests)

- **Qué hace:** motor legacy de expresiones SpEL, seleccionado por el hub como backend por defecto (`IflowConfig.DEFAULT_BACKEND = "iflow-firewall"`).
- **Calidad:** cumple su función, pero es el backend más débil en aislamiento (SpEL) y el que recibe el tráfico por defecto. La prioridad fija `firewall → vm → wasm` (`IflowBackendManager.java:26-29`) es una decisión de producto, no un bug; conviene que la configuración pueda invertirla explícitamente.

### 6.5 Traducción (`chattranslator`, `gtranslate`, `ltranslate`, `coretranslator`)

- **Qué hace:** fachada `TranslationService` con backends Google (scraping), LibreTranslate (self-hosted) y CoreTranslator (PAPI legacy).
- **Calidad:** contratos claros, tokens `char[]` en LTranslate, rate limit en Google, tests por backend (30/32/38).
- **Riesgo:** el scraping de Google es frágil por naturaleza; CoreTranslator es legacy declarado y Bukkit-only (ADR-035), correctamente aislado.
- **Acoplamiento:** los backends requieren `chattranslator` como fachada (`PLAN.md:265`), lo que refuerza el problema de obligatoriedad de TF-001.

### 6.6 Sync (`sync-bus` + sinks)

- **Qué hace:** bus con cola global (10.000), cola por sink (5.000), dedup de 60 s, retry con backoff (3 intentos), métricas y backpressure por `CallerRunsPolicy`.
- **Calidad:** la arquitectura de aislamiento por sink es correcta; los tests de ciclo y entrega existen.
- **Defectos:** `broadcast` devuelve `sinks.size()` aunque `enqueue` descarte (TF-005); `broadcastAsync` sondea la cola sin timeout (TF-006); `sendTo` sí devuelve el resultado real, lo que evidencia la inconsistencia.
- **Seguridad:** tokens en `String` en HTTP/Telegram/WebSocket (TF-003/004); HTTP tiene defensas buenas (límite 1 MB, replay, HMAC con cuerpo, localhost por defecto).

### 6.7 Transporte (`transport`)

- **Qué hace:** cliente HTTP(S) con protección SSRF, redirects manuales con validación, pinning TLS por IP y límites de respuesta.
- **Calidad:** es una de las piezas mejor resueltas; `isPrivateAddress` cubre loopback, link-local, ULA y rangos privados (`HttpTransport.java:325-408`).
- **Límite:** no es un cliente genérico; funcionalidad avanzada (proxies, HTTP/2) no está.

### 6.8 Observabilidad (`observability`, 30 tests)

- **Qué hace:** `/metrics` Prometheus en localhost (configurable), health checks de JVM/hilos/sinks.
- **Calidad:** `MetricsEndpoint` está cableado por `ObservabilityActivator`; bind por defecto `127.0.0.1`.
- **Defecto:** `DebugEndpoint` (host) no se instancia en producción y `/debug/rules` y `/debug/sinks` son stubs (TF-008). El README lo anuncia como operativo.

### 6.9 Gestión de módulos (`kernel`, `manager-*`, `suite-runtime`)

- **Qué hace:** descubre JARs, resuelve grafo, instala ZIPs, valida layout y SHA-256, journal durable, upgrade con preparación previa y liberación verificada.
- **Calidad:** `PackageLayout` rechaza rutas absolutas, `..`, NUL y prefijos inválidos (`PackageLayout.java:24-33`, `92-98`); `PackageCache` exige tamaño exacto y SHA-256 antes de promover `.part` (`PackageCache.java:50-123`); `ModuleHost.upgrade()` prepara el nuevo classloader antes de liberar el anterior (`ModuleHost.java:303-358`).
- **Riesgo:** integridad sin autenticidad: el SHA-256 viene del mismo índice que el paquete; no hay firma ni HTTPS obligatorio (TF-009).

### 6.10 Presets e inworld

- **Presets:** catálogo y aplicación real a `config/suite/` y `config/<module>/`; sin motor de ejecución, correctamente documentado.
- **Inworld:** dominio neutral (signs, cofres, libros, WORLD/RADIUS) con listeners en adaptadores; 14 tests y compilación contra Paper 1.21.4, sin prueba real en servidor.

### 6.11 SDK de extensiones (`extension-api`, `example-extension`)

- **Qué hace:** `Extension`, `ExtensionContext`, `ExtensionManager` con descubrimiento, dependencias y hot-reload.
- **Realidad:** ningún componente de producción instancia `ExtensionManager`; no hay referencias fuera de `extension-api` y `example-extension`. El runtime solo carga `Module` (TF-007).

### 6.12 Editor web

- **Qué hace:** edición de canales/configuración, validación, preview, import/export ZIP, i18n, undo/redo.
- **Calidad:** `npm run check` pasa 129 comprobaciones; versión alineada a `1.0.0`.
- **Límite:** `check` ejecuta formato, lint y tests unitarios; `test:integration` queda fuera (TF-012).

---

---

## 7. Evaluación de la calidad del código

**Correcto y consistente**

- Contratos pequeños y con propósito (`MessageDispatch`, `ChatDelivery`, `SyncSink`, `Transport`).
- `core-api` sin dependencias de runtime; los módulos neutrales no importan plataforma.
- Uso de `record` para valores inmutables (`Actor`, `Direction`, `RouteDecision`, configs).
- Copias defensivas en `ProgramImage` y validación estricta del formato binario.
- Errores explícitos: el pipeline no finge éxito cuando falta un servicio; `PackageCache` borra `.part` inválido; `HttpSink` responde 401/409/413.
- Nombres claros y paquetes coherentes; los README por módulo existen y en su mayoría coinciden con el código.

**Débil o mejorable**

- **Falso éxito:** `SyncBus.broadcast` devuelve el número de sinks registrados aunque ninguno acepte el mensaje (TF-005). Es el patrón que AGENTS.md prohíbe expresamente.
- **Duplicación de modelos:** `Module` vs `Extension`; `MessageRenderer` vs `MessageComponentRenderer`; dos caminos de dispatch (`dispatch` y `dispatchAndReport`).
- **Secretos:** `char[]` en algunos sinks y `String` en otros; la higiene es inconsistente (TF-003/004).
- **Concurrencia:** mezcla de `synchronized`, `volatile`, `ConcurrentHashMap`, `ThreadPoolExecutor` y sondeos; funciona, pero la semántica de completitud no está formalizada (TF-006).
- **Observabilidad:** logs útiles, pero sin correlación por mensaje ni métricas de latencia por etapa.
- **Documentación:** README y wiki describen características no cableadas (`/debug/*`, extensiones). El código es más honesto que la documentación.

**Complejidad.** Las clases más grandes son orquestadores (`SuiteRuntime` 715 líneas, `MessageDispatcher` ~350, `DefaultSyncBus` ~600). No son «god classes» de negocio: coordinan. El riesgo es que el composition root concentre decisiones de qué es obligatorio, que es exactamente TF-001.

---

## 8. Clean Architecture

**Cumple**

- `core-api` no conoce infraestructura: cero dependencias de runtime.
- Las features dependen de contratos, no de adaptadores.
- Los adaptadores de plataforma son delgados: `TextFormatterSuitePlugin` crea puertos y arranca el runtime; no contiene lógica de features (`TextFormatterSuitePlugin.java:31-37`).
- La composición ocurre en `suite-runtime`, no en los adaptadores.

**No cumple**

- El composition root conoce tipos concretos de feature y los exige (`ChannelRegistry`, `TranslationService`, `IflowHub`, `MessageRenderer`, `MessageComponentRenderer`, `ChatDelivery`, `ActorDirectory`, `PermissionChecker`). La regla de dependencia se respeta en compilación, pero se rompe en runtime: el núcleo no puede funcionar sin features concretas.
- `host` importa `api.iflow.Router` y `api.channel.ChannelRegistry`; es correcto (contratos), pero el host sigue siendo el que decide el orden y la obligatoriedad.
- `observability` no importa `host` y por eso el `DebugEndpoint` quedó aislado en `host` y sin cablear: la regla de dependencia se cumplió a costa de la funcionalidad (TF-008).

**Calificación:** 8/10. La separación es real, no cosmética; el debe está en la composición obligatoria.

---

## 9. Hexagonal Architecture

**Puertos reales**

| Puerto | Implementaciones | Dirección |
|---|---|---|
| `ChannelRegistry` | `textformatter` | feature → contrato → host |
| `TranslationService` | `chattranslator` + backends | feature → contrato → host |
| `MessageRenderer` / `MessageComponentRenderer` | `textformatter` | feature → contrato → host |
| `ChatDelivery` | `spigot-host`, `fabric-host` | adaptador → contrato |
| `ActorDirectory` | `spigot-host`, `fabric-host` | adaptador → contrato |
| `PermissionChecker` | adaptadores | adaptador → contrato |
| `IflowHub` / `Router` | `iflow` + backends | feature → contrato → host |
| `SyncSink` | `sync-*` | feature → contrato → bus |
| `Transport` | `transport` | feature → contrato |

**Hexagonal de verdad:** los adaptadores implementan puertos; las features no conocen Bukkit/Fabric; el host no conoce clases de sink concretas. Es una implementación reconocible, no solo nombres de paquetes.

**Fugas**

- El puerto `MessageDispatch` está bien, pero su implementación (`RuntimeMessageDispatch`) depende de `MessageDispatcher`, una clase concreta de `host`; el puerto no basta para sustituir el pipeline.
- `ExtensionManager` vive en un hexágono paralelo sin adaptador de entrada (TF-007).
- `IflowHub` exige un backend activo; el puerto no admite «sin iFlow» como estado válido (TF-001).

**Calificación:** 8/10.

---

## 10. Modularidad

- **43 módulos**, cada uno con `build.gradle`, lockfile y README. La modularidad no es cosmética: los límites se compilan y `checkArchitecture` los verifica.
- **Sin ciclos evidentes:** el grafo se resuelve en `kernel`; los módulos neutrales no dependen de adaptadores.
- **Fronteras reales:** `core-api` ← features ← `suite-runtime` ← hosts. Los adaptadores no importan features.
- **Frontera debilitada:** el pipeline obliga a que existan ocho features y un backend iFlow. Un operador no puede instalar «solo formateo» o «solo sync» y tener un pipeline funcional. La modularidad de compilación es alta; la de despliegue, media.
- **Módulos que podrían fusionarse:** `core-render` es pequeño pero justifica su separación (Adventure fuera de `core-api`). `manager-spi/core/apt` están bien separados por responsabilidad.
- **Módulos que podrían dividirse:** `iflow` concentra hub, config y router; `host` concentra SuiteHost, dispatcher, config y debug. No es urgente.

**Calificación:** 7/10.

---

## 11. Separación de responsabilidades

| Componente | Responsabilidad | Evaluación |
|---|---|---|
| `SuiteRuntime` | composición, ciclo de vida, pipeline, comandos | Cohesión aceptable; demasiadas decisiones de obligatoriedad |
| `ModuleHost` | activar/desactivar/upgrade/release | Buena; preparación antes de swap y liberación verificada |
| `PackageRuntime` | instalar/activar paquetes | Buena; separa instalación de activación |
| `MessageDispatcher` | expandir, rutear, entregar | Correcta; mezcla orquestación con entrega y scheduling |
| `SuiteHost` | resolver idioma, router, render | Correcta; es el núcleo del pipeline |
| `DefaultSyncBus` | colas, dedup, retry, métricas | Correcta; semántica de éxito imperfecta |
| `HttpSink` | inbound/outbound HTTP | Correcta; autenticación y replay bien resueltos |
| `Observability` | métricas y health | Correcta; debug fuera del cableado |

No hay lógica de negocio en adaptadores ni lógica de infraestructura en el dominio. La duplicación de responsabilidades aparece en el doble modelo de extensión y en los dos renderers.

---

## 12. Personalización

**Configurable**

- Raíz de comandos (`commands.yml`, por defecto `[suite]`), canales, plantillas, permisos, direcciones, sonidos.
- `engine.parallel` (por defecto `false`), `engine.fallback-on-unavailable`, backend iFlow.
- Bind addresses de métricas/HTTP/WebSocket, tokens, HMAC, límites.
- Presets aplicables sin editar código.

**Rígido**

- Orden del pipeline y etapas obligatorias (TF-001). No se puede insertar, quitar o reordenar una etapa sin tocar `SuiteRuntime`.
- Prioridad de backends iFlow fija (`firewall → vm → wasm`); la configuración elige backend, pero no redefine la prioridad de fallback.
- `engine.parallel` solo paraleliza destinatarios, no etapas.
- El tipo de mensaje y el modelo de `Direction` están fijados por `core-api`.

**Restricciones con razón técnica:** límites de tamaño de paquete, validación de SHA-256, rechazo de rutas `..`, puerto 0-65535 y bind localhost por defecto están justificados. No se critica la restricción, sino la ausencia de una vía segura para lo que el usuario sí necesita (pipeline opcional).

---

## 13. Seguridad

### 13.1 Superficie y defensas verificadas

| Control | Estado | Evidencia |
|---|---|---|
| SSRF en transporte | Implementado | `HttpTransport.isPrivateAddress`, redirects manuales, pinning TLS |
| Path traversal en paquetes | Implementado | `PackageLayout.normalized/resolve` |
| Integridad de paquetes | Implementado (SHA-256, tamaño exacto, `.part`) | `PackageCache.java:50-123` |
| Autenticidad de paquetes | **No implementada** | Sin firma; URL de repositorio sin HTTPS obligatorio (TF-009) |
| HTTP inbound | Token/HMAC, replay, límite 1 MB, localhost por defecto | `HttpSink.java:175-294` |
| WebSocket | Token obligatorio; comparación con `equals` | `WebSocketSyncSink.java:98-100`, `222` |
| Métricas | Bind localhost por defecto | `MetricsEndpoint.java:42-49` |
| Debug | Clase con token, pero no cableada; stubs | TF-008 |
| Secretos | Inconsistente (`char[]` y `String`) | TF-003/004 |
| Autorización de destinatarios | Fail-open en `PERMISSION`/`WORLD` | TF-002 |

### 13.2 Riesgos por categoría

- **Vulnerabilidad demostrable:** TF-002 (fail-open) y TF-003 (token en `String`). La primera puede exponer mensajes a todos los jugadores; la segunda facilita recuperar el token de un heap dump.
- **Riesgo probable:** TF-004 (timing de comparación), TF-009 (supply chain).
- **Riesgo hipotético:** agotamiento de recursos por mensajes patológicos (plantillas, expresiones SpEL) sin límites de complejidad; no se reprodujo.

### 13.3 Trust boundaries

1. **Jugador → evento de chat:** el mensaje es no confiable; se parsea y renderiza. El escape MiniMessage reduce inyección de formato, pero las expresiones SpEL del firewall merecen revisión si aceptan entrada del jugador.
2. **Operador → configuración:** confiable por definición; aun así, el fail-open convierte un campo vacío en una decisión de privacidad.
3. **Repositorio → paquete:** no confiable; SHA-256 protege integridad, no autenticidad.
4. **Red → sinks:** HTTP/WS autentican; Telegram/Discord dependen de TLS y token.

**Calificación de seguridad:** 6/10. Buenas defensas en las partes difíciles; debilidades concretas y corregibles.

---

## 14. Rendimiento

No se ejecutaron benchmarks en esta auditoría. El módulo `loadtest` contiene JMH (`MessageProcessingBenchmark`) y Gatling (`SuiteLoadTestSimulation`) reales, pero no hay resultados versionados ni umbrales. Por tanto, lo siguiente es **análisis arquitectónico**, no medición.

**Hotspots identificados**

- **Traducción por destinatario:** `host.deliver()` se invoca por receptor; si la traducción es remota y no hay caché por idioma, el coste crece con el número de destinatarios. El dispatcher no agrupa por idioma.
- **Futures por receptor:** con `engine.parallel=true` se crea un `CompletableFuture` por jugador y se recolectan con `get(10 s)`; para 500 jugadores son 500 futuros y 500 tareas.
- **Renderizado repetido:** la misma plantilla se renderiza por receptor aunque el texto sea idéntico; solo cambia el idioma/placeholders.
- **Allocations:** construcción de `Message`, `Direction`, `DispatchReport` y listas intermedias por mensaje; `String.format` en HMAC; `new String(token)` en Telegram.
- **Colas:** 10.000 globales + 5.000 por sink; el dedup mantiene IDs 60 s (coste de memoria acotado).
- **Locks:** `ModuleHost` sincroniza activación/upgrade (correcto, no está en el camino caliente); `SyncBus` usa colas concurrentes.

**Estimación honesta:** sin datos de p50/p95/p99 no se puede afirmar capacidad. Un servidor pequeño (decenas de jugadores) debería funcionar con el diseño actual; una red grande requiere medir y probablemente cachear traducciones y agrupar renderizados.

---

## 15. Concurrencia

**Modelo de hilos**

| Contexto | Origen | Bloqueante |
|---|---|---|
| Hilo del servidor | eventos Bukkit/Fabric | Sí; los listeners descargan a scheduler |
| Scheduler de `sync-bukkit` | `BukkitChatListener` | Ejecuta dispatch; no debe bloquear el hilo principal |
| Pool del dispatcher | 4-32 hilos, cola 1.000 | Traduce/renderiza por destinatario |
| Scheduler de `sleep` | 1 hilo | Retardos de entrega |
| Pool global de `SyncBus` | 2-8 hilos, cola 10.000 | Fan-out |
| Pool por sink | 1 hilo por sink, cola 5.000 | Envío y retry |
| Scheduler de `SyncBus` | 1 hilo | Retry/backoff, dedup, sondeo |

**Correcto**

- `ModuleHost` sincroniza el ciclo de vida y prepara el classloader nuevo antes de liberar el anterior.
- `DefaultSyncBus` usa colas concurrentes, dedup atómico (`putIfAbsent`) y retry con backoff.
- `MessageDispatcher` usa `CallerRunsPolicy` para backpressure y un scheduler separado para `sleep`, evitando bloquear workers.
- `SpigotChatDelivery` salta al hilo principal para enviar.

**Riesgos**

- **TF-006:** el sondeo de `broadcastAsync` no tiene timeout ni identifica el mensaje; bajo carga sostenida el futuro puede no completarse.
- **TF-010:** `rebuildPipeline()` hace `unbind()` y `dispatcher.close()` mientras un dispatch en vuelo puede intentar `supplyAsync` sobre un executor apagado; el resultado es `RejectedExecutionException` en el hilo del evento.
- **TF-011:** el dispatch bloquea al llamante hasta completar todos los destinatarios; la mitigación depende de que `sync-bukkit` esté instalado y use su scheduler.
- **Visibilidad:** `RuntimeMessageDispatch.dispatcher` es `volatile` (correcto); `pipelineError` se escribe bajo `synchronized` y se lee en `fail()` fuera de él, pero el campo es `volatile` en el dispatcher interno (`error`), así que la publicación es correcta.

**Calificación:** 6/10. Diseño consciente de concurrencia, con dos semánticas sin formalizar (completitud y rebuild).

---

## 16. Escalabilidad

Estimaciones arquitectónicas; **no son benchmarks**.

### Escenario A — servidor pequeño (≤ 50 jugadores)

- **Cuello:** ninguno previsible. Traducción remota ocasional.
- **Latencia:** dominada por la red del traductor.
- **Riesgo:** bajo. **Escalado horizontal:** no necesario.

### Escenario B — red mediana (50-500 jugadores, varios servidores)

- **Cuello:** traducción por destinatario y renderizado repetido; fan-out de `SyncBus` por sink.
- **Latencia:** crece con destinatarios si `engine.parallel=false`; con `true`, el `get(10 s)` y el pool de 32 limitan.
- **Riesgo:** medio; pérdida silenciosa si las colas de sink se llenan (TF-005).
- **Escalado:** necesario separar sinks por proceso y cachear traducciones.

### Escenario C — red grande (500-5.000 jugadores)

- **Cuello:** pool del dispatcher (32 hilos) y una cola de 1.000; traducción remota; dedup global; un hilo por sink.
- **Latencia:** p95/p99 no medibles sin benchmark; el diseño no tiene batching por idioma ni renderizado compartido.
- **Riesgo:** alto de saturación y de `RejectedExecutionException` si se combina con rebuild (TF-010).
- **Escalado:** obligatorio; el modelo actual es de un proceso.

### Escenario D — escala tipo Hypixel/UniversoCraft

- **Veredicto:** **no demostrado y poco probable con la arquitectura actual**. No hay sharding, batching, caché distribuida ni presupuesto de latencia por etapa.
- **Qué haría falta:** pipeline por etapas con colas y backpressure medible, traducción cacheada/agrupada, renderizado compartido, sinks con particionado, métricas p99 y pruebas de carga con umbrales.
- **Honestidad:** afirmar que soporta esa carga sin datos sería inventar. El diseño permite evolucionar, pero hoy no lo demuestra.

**Calificación:** 4/10 en escalabilidad demostrada; 7/10 en potencial arquitectónico.

---

## 17. Testing

**Ejecutado y verificado**

| Suite | Resultado |
|---|---|
| Gradle (`./gradlew test --rerun-tasks`) | 1.441 tests · 0 fallos · 0 errores · 10 omitidos · 42 módulos |
| `checkArchitecture` | 36 módulos neutrales/adaptadores, 0 violaciones |
| `checkLocks` | 44 lockfiles, 0 proyectos sin lockfile |
| Editor web (`npm run check`) | 129 comprobaciones, 0 fallos |

**Distribución de tests (mayores)**

| Módulo | Tests | Módulo | Tests |
|---|---:|---|---:|
| `iflow-vm` | 399 | `iflow-wasm` | 60 |
| `suite-runtime` | 103 | `textformatter` | 47 |
| `manager-spi` | 80 | `iflow-firewall` | 47 |
| `core-api` | 72 | `manager-core` | 43 |
| `host` | 65 | `ltranslate` | 38 |

**Calidad**

- Tests unitarios reales, con JUnit 5 y AssertJ, por comportamiento y no solo por cobertura.
- Tests de integración con mocks en los adaptadores (`SpigotChatDeliverySoundNameTest`, `DebugEndpointIntegrationTest`, `HttpSinkIntegrationTest`).
- Tests de contrato de configuración y de rechazo de rutas legacy (P0.3/P0.5).
- El editor web tiene tests unitarios y de integración separados.

**Huecos críticos**

1. **Sin E2E real:** no hay chat → iFlow → format → delivery en Paper/Fabric/Velocity. `PLAN.md:238` lo declara pendiente. Los 12 tests E2E «unitarios» no sustituyen el servidor real.
2. **Sin pruebas de concurrencia sostenida:** no hay estrés del dispatcher, del bus ni del rebuild del pipeline.
3. **Sin pruebas de fallo de red:** retry/backoff de sinks no se prueba con cortes reales.
4. **Sin presupuesto de rendimiento:** JMH/Gatling existen, pero no hay umbrales en CI.
5. **Integración web fuera de `check`:** `test:integration` no se ejecuta en el gate (`package.json:12-13`).

**Regresiones concretas propuestas**

| Hallazgo | Test |
|---|---|
| TF-001 | Arrancar con un solo paquete de formateo y verificar que el chat se procesa o falla con diagnóstico explícito, no «pipeline unavailable» |
| TF-002 | `PERMISSION`/`WORLD` con qualifier vacío debe fallar en validación o entregar a cero |
| TF-003/004 | Inspección de heap/`String` y comparación constante con vectores de tiempo |
| TF-005 | Sink con cola llena: `broadcast` debe devolver 0 o el número realmente encolado |
| TF-006 | `broadcastAsync` con sink lento debe completar o fallar por timeout |
| TF-010 | Rebuild concurrente con 1.000 dispatch: cero `RejectedExecutionException` |
| TF-012 | Job E2E con Paper real y matriz de versiones |

---

## 18. Mantenibilidad

**A 1 año.** La estructura aguanta: módulos pequeños, contratos estables, tests abundantes. El riesgo inmediato es que el composition root acumule más `requireService` y que la documentación siga prometiendo lo que el código no hace. La purga pre-1.0 elimina la carga de compatibilidad, lo que ayuda.

**A 3 años.** Sin un ADR de pipeline, cada feature nueva añadirá otra condición al composition root. La duplicación `Module`/`Extension` y `MessageRenderer`/`MessageComponentRenderer` se convertirá en deuda de concepto. Los sinks con secretos en `String` y comparaciones no constantes serán un lastre de seguridad.

**A 5 años.** La apuesta fuerte es iFlow: si W3.5 y V6 avanzan, el proyecto tiene un diferencial real. Si no, el valor queda en el formateo y la sincronización, donde compite con plugins más simples. La falta de E2E y de benchmarks es el mayor riesgo de regresión silenciosa.

**Cuellos de mantenimiento previsibles**

- `SuiteRuntime` (composición y obligatoriedad).
- `DefaultSyncBus` (semántica de éxito/completitud).
- `MessageDispatcher` (threading y recencia de configuración).
- Documentación pública (README/wiki) desincronizada del runtime.

**Calificación:** 7/10.

---

## 19. Historial y evolución

- **447 commits** en 81 días (≈5,5/día), del 2026-08-16 al 2026-11-05.
- **Monolito → 43 módulos:** la separación es el cambio estructural dominante; los commits de P0.4/P0.8 eliminan aliases, shims y versiones `2.x`.
- **iFlow:** reescritura de VM de 16 bits, MMU, HOST y fuel; corrección de los hallazgos IFLOW-001/002 de la auditoría anterior.
- **Purga pre-1.0:** ADR-034 fija una sola base `1.0`, sin compatibilidad hacia atrás y con betatesting posterior.
- **Patrón:** alta velocidad, commits pequeños y descriptivos, correcciones de auditoría con tests. La arquitectura se está **estabilizando**, no reestructurándose sin rumbo; el riesgo es la prisa documental.

No se juzga el estado actual por decisiones históricas: la existencia de `iflow-firewall` y `coretranslator` se explica por ADR-034/035 y está declarada, no oculta.

---

## 20. Registro de hallazgos

### TF-001 — El pipeline exige módulos concretos; la suite no funciona sin ellos

**Tipo:** Defecto de diseño
**Severidad:** Alta
**Confianza:** Confirmado
**Ubicación:** `src/suite-runtime/src/main/java/me/majhrs16/suite/runtime/SuiteRuntime.java` → `SuiteRuntime` → `rebuildPipeline()` / `resolveRouter()`

**Descripción.** `rebuildPipeline()` hace `requireService(...)` de `ChannelRegistry`, `TranslationService`, `MessageComponentRenderer`, `MessageRenderer`, `ChatDelivery`, `ActorDirectory` y `PermissionChecker`, y `resolveRouter()` exige `IflowHub` con un backend activo. Si falta cualquiera, captura la excepción, marca el pipeline como «unavailable» y todo dispatch falla.

**Causa.** El composition root construye un grafo fijo en lugar de componer etapas opcionales. La obligatoriedad no está declarada en los contratos: emerge del código.

**Impacto funcional.** Un operador que instale solo `textformatter` no tiene chat. La suite «arranca» pero no funciona; el mensaje de error es explícito, lo cual es correcto, pero contradice el requisito de módulos opcionales y bloquea PL-1.

**Impacto en rendimiento/escalabilidad.** Ninguno directo; limita la configuración y la sustitución de motores.

**Condiciones para reproducir.** Desinstalar `iflow` o `chattranslator`, o arrancar sin esos paquetes; enviar un mensaje.

**Evidencia.**

```java
// SuiteRuntime.java:203-211
ChannelRegistry channels = requireService(ChannelRegistry.class);
TranslationService translation = requireService(TranslationService.class);
Router router = resolveRouter();
MessageComponentRenderer componentRenderer = requireService(MessageComponentRenderer.class);
MessageRenderer renderer = requireService(MessageRenderer.class);
ChatDelivery delivery = requireService(ChatDelivery.class);
ActorDirectory actors = requireService(ActorDirectory.class);
PermissionChecker permissions = requireService(PermissionChecker.class);
```

```java
// SuiteRuntime.java:245-253
private Router resolveRouter() {
    IflowHub hub = moduleHost.services().find(IflowHub.class)
            .orElseThrow(() -> new IllegalStateException(
                    "iFlow hub is not installed; install the iflow package"));
    Router router = hub.router();
    if (router == null) {
        throw new IllegalStateException(
                "iFlow hub has no active backend: " + hub.availableBackends());
    }
    return router;
}
```

**Solución sugerida.** Definir un pipeline declarativo (lista ordenada de etapas con `Optional`/`Stage`), puertos con implementaciones por defecto neutras (`ChannelRegistry` vacío, `TranslationService` identidad, `Router` allow-all, `MessageRenderer` plano, `ChatDelivery` no-op) y validación explícita de las etapas requeridas por configuración. Requiere ADR que sustituya/enmiende ADR-019, ADR-024/025 y ADR-032 antes de implementar (PL-1).

**Código refactorizado (orientativo).**

```java
record Stage<T>(Class<T> port, boolean required, Supplier<? extends T> fallback) {}

private <T> T resolve(Stage<T> stage) {
    return moduleHost.services().find(stage.port())
        .orElseGet(() -> {
            if (stage.required()) throw new IllegalStateException("missing " + stage.port().getName());
            return stage.fallback().get();
        });
}
```

**Por qué merece la pena.** Es el requisito central del producto y el desbloqueo de PL-1. Complejidad media; riesgo de regresión alto si se hace sin ADR y sin tests de contrato por etapa.

---

### TF-002 — `PERMISSION`/`WORLD` sin qualifier entregan a todos los jugadores

**Tipo:** Vulnerabilidad
**Severidad:** Alta
**Confianza:** Confirmado
**Ubicación:** `src/host/src/main/java/me/majhrs16/suite/host/MessageDispatcher.java` → `MessageDispatcher` → `expand()` / `withPermission()` / `playersInWorld()`

**Descripción.** La política documentada como «fail-open» hace que un `PERMISSION` con qualifier nulo o vacío devuelva todos los jugadores online, y un `WORLD` con qualifier nulo también. Un campo omitido en la configuración convierte un mensaje restringido en un broadcast.

**Causa.** Se prioriza «no perder el mensaje» sobre «no filtrar el mensaje». No hay validación de configuración que exija qualifier cuando el `Direction` lo necesita.

**Impacto funcional.** Fuga de información entre jugadores; un mensaje de staff o de un mundo puede llegar a toda la red. Es un fallo de autorización, no solo de formato.

**Impacto en rendimiento/escalabilidad.** Ninguno; el impacto es de seguridad.

**Condiciones para reproducir.** Canal con `direction: permission` sin `permission`, o `direction: world` sin `world`; enviar un mensaje con ese canal.

**Evidencia.**

```java
// MessageDispatcher.java:232-233
 * <p>Fail-open policy: an empty qualifier on {@code PERMISSION} or
 * {@code WORLD} resolves to every online player instead of nobody.</p>

// MessageDispatcher.java:244-245
case PERMISSION -> withPermission(actors.onlinePlayers(), direction.qualifier());
case WORLD -> playersInWorld(direction.qualifier());

// MessageDispatcher.java:261-267
private List<Actor> withPermission(List<Actor> candidates, String permission) {
    if (permission == null || permission.isEmpty()) {
        return candidates;
    }
    ...
}

// MessageDispatcher.java:274-276
private List<Actor> playersInWorld(String world) {
    if (world == null) {
        return actors.onlinePlayers();
    }
```

**Solución sugerida.** Fail-closed: qualifier vacío ⇒ cero destinatarios y error de configuración en la carga; validar en `ConfigLoader` que `PERMISSION`/`WORLD` exigen qualifier. Si se quiere conservar el fail-open, debe ser una opción explícita (`security.fail-open: true`) y documentada, nunca el comportamiento por defecto.

**Código refactorizado.**

```java
case PERMISSION -> requireQualifier(direction).map(q -> withPermission(actors.onlinePlayers(), q)).orElse(List.of());
case WORLD -> requireQualifier(direction).map(actors::playersInWorld).orElse(List.of());
```

**Por qué merece la pena.**Cambio localizado, test de regresión simple y elimina una fuga real. Complejidad baja.

---

### TF-003 — El token de Telegram queda en un `String` (`baseUrl`)

**Tipo:** Vulnerabilidad
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `src/sync-telegram/src/main/java/me/majhrs16/suite/synctelegram/TelegramSink.java` → `TelegramSink` → constructor / `poll()`

**Descripción.** El constructor guarda el token en `char[]`, pero también construye `baseUrl = "https://api.telegram.org/bot" + new String(this.token)` y lo conserva. En `poll()` vuelve a crear `new String(token)`. El `Arrays.fill(token, '\0')` de `stop()` no borra ninguna de esas copias.

**Causa.** La API de `URL`/`String` obliga a materializar el token; se hizo sin aislar el ciclo de vida.

**Impacto funcional.** Un heap dump, un log de excepción o un volcado de memoria pueden exponer el token. La higiene de `char[]` es aparente.

**Impacto en rendimiento/escalabilidad.** Irrelevante; el problema es de exposición.

**Condiciones para reproducir.** Construir el sink con un token, inspeccionar el objeto o provocar un volcado.

**Evidencia.**

```java
// TelegramSink.java:23-32
private final String baseUrl;
private final char[] token;
...
this.token = Objects.requireNonNull(token, "token").toCharArray();
this.baseUrl = "https://api.telegram.org/bot" + new String(this.token);

// TelegramSink.java:88-89
String tokenStr = new String(token);
String url = "https://api.telegram.org/bot" + tokenStr + "/getUpdates?timeout=1";
```

**Solución sugerida.** No conservar `baseUrl` con el token; construir la URL por operación a partir de `char[]` y limpiar el buffer intermedio. Si el transporte exige `String`, aceptar la limitación y documentarla, pero no mantener una copia persistente. Alternativa: `Secret` encapsulado con `use(Consumer<char[]>)` y cero `String` de larga vida.

**Por qué merece la pena.** Elimina una copia permanente con un cambio acotado. Complejidad baja-media (afecta a `Transport.withQuery`).

---

### TF-004 — Secretos HTTP/WebSocket en `String` y comparación no constante

**Tipo:** Vulnerabilidad
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `src/sync-http/src/main/java/me/majhrs16/suite/synchttp/HttpSink.java` → `HttpSink` → `authenticate()`; `src/sync-websocket/src/main/java/me/majhrs16/suite/syncwebsocket/WebSocketSyncSink.java` → `WebSocketSyncSink` → `handleHandshake()`

**Descripción.** `HttpSink` guarda `authToken` y `hmacSecret` como `String` y compara con `equals`; `WebSocketSyncSink` también compara el token con `equals`. Las comparaciones no son de tiempo constante y los secretos no se pueden borrar.

**Causa.** Comodidad de la API de configuración (`String` en YAML) sin una capa de secreto.

**Impacto funcional.** Fuga por heap dump y, en teoría, ataque de tiempo para descubrir el token. La explotación remota del timing es difícil por la variabilidad de red, por lo que la severidad es media y la confianza del impacto práctico menor que la del hecho.

**Impacto en rendimiento/escalabilidad.** Ninguno.

**Condiciones para reproducir.** Inspección de campos; medición de tiempos con muchas muestras (no realizada).

**Evidencia.**

```java
// HttpSink.java:57-58
private final String authToken;
private final String hmacSecret;
// HttpSink.java:221
if (authToken != null && authToken.equals(token)) {
// HttpSink.java:242
if (signature.equals(expectedSig)) {
// WebSocketSyncSink.java:222
if (!token.equals(authToken)) {
```

**Solución sugerida.** `char[]`/`byte[]` para secretos y `MessageDigest.isEqual` para comparar; normalizar la configuración a un `Secret` que se borre al cerrar el sink. Para HMAC, comparar bytes, no hex `String`.

**Por qué merece la pena.** Endurecimiento estándar y bajo coste. Complejidad media por el número de sinks.

---

### TF-005 — `SyncBus.broadcast` devuelve éxito aunque todos los sinks descarten

**Tipo:** Bug
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `src/sync-bus/src/main/java/me/majhrs16/suite/syncbus/DefaultSyncBus.java` → `DefaultSyncBus` → `broadcast()` / `SinkContext.enqueue()`

**Descripción.** `broadcast()` devuelve `sinks.size()` sin mirar el resultado de `enqueue`. Si la cola de un sink está llena, `enqueue` descarta el mensaje, incrementa `totalDropped` y el llamante recibe un éxito que no ocurrió. El dedup se registra antes del fan-out, así que reintentar el mismo ID dentro de 60 s tampoco sirve.

**Causa.** Se confunde «fan-out intentado» con «fan-out aceptado».

**Impacto funcional.** Pérdida silenciosa de mensajes en picos; el operador ve éxito. Viola la regla de no aceptar estados falsos de éxito.

**Impacto en rendimiento/escalabilidad.** En carga alta, las colas se llenan y el sistema pierde mensajes sin señal; el backpressure no se propaga.

**Condiciones para reproducir.** Sink lento o bloqueado, cola de 5.000 llena, `broadcast` concurrente.

**Evidencia.**

```java
// DefaultSyncBus.java:247-253
boolean offered = globalQueue.offer(() -> processMessage(message), 100, TimeUnit.MILLISECONDS);
if (!offered) {
    submitToGlobalQueue(message); // CallerRunsPolicy
}
return sinks.size(); // éxito aunque processMessage descarte por sink

// DefaultSyncBus.java:513-524
boolean offered = queue.offer(task);
if (!offered) {
    totalDropped.incrementAndGet();
    logger.warn("SyncBus: sink '" + sink.name() + "' queue full, dropping message");
}
return offered;
```

**Solución sugerida.** Propagar el resultado de `enqueue` y devolver el número realmente encolado; separar «aceptado» de «entregado». Añadir un contador de fallos y una métrica de drops por sink. No marcar el dedup como consumido hasta que al menos un sink acepte (o documentar que el dedup es de emisión).

**Código refactorizado.**

```java
int accepted = 0;
for (SinkContext ctx : sinks.values()) {
    if (ctx.enqueue(message)) accepted++;
}
if (accepted == 0) totalDropped.incrementAndGet();
return accepted;
```

**Por qué merece la pena.** Cambio localizado con impacto directo en fiabilidad. Complejidad baja.

---

### TF-006 — `broadcastAsync` sondea la cola sin timeout ni identidad de mensaje

**Tipo:** Bug
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `src/sync-bus/src/main/java/me/majhrs16/suite/syncbus/DefaultSyncBus.java` → `DefaultSyncBus` → `waitForSinkProcessing()`

**Descripción.** Cuando el futuro global completa, se programa una comprobación cada 100 ms: si la cola del sink está vacía o el sink no corre, completa; si no, se reprograma. No hay timeout y la condición no demuestra que *este* mensaje se haya procesado, solo que la cola está vacía en ese instante.

**Causa.** No hay un futuro por mensaje que el worker complete tras `sink.send`.

**Impacto funcional.** Bajo carga sostenida, los futuros pueden no completarse nunca (fuga) o completarse antes de que el mensaje concreto se envíe (falso positivo). Los llamantes que esperan `broadcastAsync` se bloquean.

**Impacto en rendimiento/escalabilidad.** Hilos y futuros retenidos; sondeo periódico por sink.

**Condiciones para reproducir.** Sink lento con cola que nunca queda vacía; llamar a `broadcastAsync` y esperar.

**Evidencia.**

```java
// DefaultSyncBus.java:311-321
private void waitForSinkProcessing(SinkContext ctx, CompletableFuture<Void> future) {
    scheduler.schedule(() -> {
        if (!ctx.running.get() || ctx.queue.isEmpty()) {
            future.complete(null);
        } else {
            waitForSinkProcessing(ctx, future);
        }
    }, 100, TimeUnit.MILLISECONDS);
}
```

**Solución sugerida.** Asociar un `CompletableFuture` a cada tarea encolada y completarlo en `deliverWithRetry` (éxito o agotamiento de reintentos), con timeout explícito. Alternativa: `afterExecute` del `ThreadPoolExecutor` del sink.

**Por qué merece la pena.** Hace que la API asíncrona sea real y medible. Complejidad media.

---

### TF-007 — El SDK de extensiones no está conectado al runtime

**Tipo:** Defecto de diseño
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `src/extension-api/src/main/java/me/majhrs16/suite/extension/ExtensionManager.java` → `ExtensionManager`; `src/example-extension/.../ExampleExtension.java`

**Descripción.** `ExtensionManager` descubre, resuelve y habilita `Extension`, pero ningún componente de producción lo instancia. El runtime solo carga `Module` por `ServiceLoader`. `example-extension` implementa `Extension`, no `Module`, y nunca se carga. El README lo presenta como SDK implementado y probado.

**Causa.** Conviven dos modelos de extensión sin un adaptador de entrada que conecte `Extension` con `PackageRuntime`/`ModuleHost`.

**Impacto funcional.** Un desarrollador que siga el SDK no ve su extensión ejecutarse. La documentación induce a error y el código de `extension-api` es, en la práctica, muerto.

**Impacto en rendimiento/escalabilidad.** Ninguno; es deuda de producto.

**Condiciones para reproducir.** Instalar `example-extension` y comprobar que no se descubre ni se habilita.

**Evidencia.**

```java
// ExtensionManager.java:26-37
public final class ExtensionManager {
    ...
    public ExtensionManager(PluginLogger logger, ExtensionContextProvider contextProvider,
                            Path extensionsDir, SemVer runningCoreApiVersion) { ... }
    public void discover() { ... }
```

```text
$ grep -rn "ExtensionManager" --include=*.java src | grep -v src/extension-api
# sin resultados
```

**Solución sugerida.** Decidir el modelo: (a) integrar `ExtensionManager` en `SuiteRuntime` como descubrimiento de extensiones sobre `Module`, o (b) marcar `extension-api` como experimental/fuera de release y corregir README. No mantener dos modelos silenciosos.

**Por qué merece la pena.** Claridad de contrato y eliminación de código muerto. Complejidad media si se integra; baja si se declara experimental.

---

### TF-008 — `/debug/*` no se instancia en producción y dos endpoints son stubs

**Tipo:** Deuda técnica
**Severidad:** Media
**Confianza:** Confirmado
**Ubicación:** `src/host/src/main/java/me/majhrs16/suite/host/debug/DebugEndpoint.java` → `DebugEndpoint` → `/debug/rules`, `/debug/sinks`; `README.md:44,206`

**Descripción.** `DebugEndpoint` existe y tiene tests, pero solo lo instancian los tests. `ObservabilityActivator` crea `Observability` (métricas + health), no el debug. Además, `/debug/rules` y `/debug/sinks` devuelven `"not yet implemented"`.

**Causa.** El endpoint quedó en `host` para no violar la regla de dependencia de `observability` (que no puede importar `host`), y nadie lo cableó desde el adaptador.

**Impacto funcional.** El operador no tiene `/debug/*` aunque README y wiki lo anuncien; si se cablea, dos de sus rutas no aportan datos.

**Impacto en rendimiento/escalabilidad.** Ninguno.

**Condiciones para reproducir.** Arrancar la suite y solicitar `/debug/state` en el puerto 9091.

**Evidencia.**

```java
// DebugEndpoint.java:230
"note", "Rules dump not yet implemented - requires access to iFlow router"
// DebugEndpoint.java:246
"note", "Sinks dump not yet implemented - requires access to host sinks"
```

```text
$ grep -rn "DebugEndpoint" --include=*.java src
# solo la clase y sus tests; ninguna llamada en producción
```

**Solución sugerida.** Cablearlo desde `spigot-host`/`fabric-host` (donde sí se conoce `SuiteHost`) o mover el endpoint a un puerto neutral en `core-api` que `observability` pueda consumir. Implementar rules/sinks o retirar esas rutas y la promesa del README.

**Por qué merece la pena.** Coherencia documental y observabilidad real. Complejidad baja-media.

---

### TF-009 — Repositorios de paquetes sin HTTPS obligatorio ni firma

**Tipo:** Vulnerabilidad
**Severidad:** Media
**Confianza:** Probable
**Ubicación:** `src/manager-apt/src/main/java/me/majhrs16/suite/manager/apt/RepositoryManager.java` → `RepositoryManager` → `downloadIndex()` / `buildIndexUrl()` / `fetchRemoteIndex()`

**Descripción.** La URL del repositorio se usa tal cual: acepta `file://` de forma explícita y `http://` sin exigir HTTPS. El SHA-256 del paquete viene del mismo índice; no hay firma ni clave pública. Un repositorio comprometido o una red MITM puede servir índice y paquete coherentes entre sí.

**Causa.** Se implementó integridad (hash) sin autenticidad (firma) ni política de transporte.

**Impacto funcional.** Instalación de código arbitrario si el repositorio o la red se comprometen. Requiere configuración de repositorio por un operador, por lo que no es explotable por un jugador.

**Impacto en rendimiento/escalabilidad.** Ninguno.

**Condiciones para reproducir.** Configurar un repositorio `http://` propio y servir un paquete con su hash; la instalación lo acepta.

**Evidencia.**

```java
// RepositoryManager.java:106
if ("file".equals(repo.url().toLowerCase().startsWith("file://") ? "file" : "")) {
// RepositoryManager.java:143-150
private String buildIndexUrl(RepositoryConfig repo) {
    String baseUrl = repo.url();
    ...
    return baseUrl + "/packages.list";
}
// RepositoryManager.java:155-160
private String fetchRemoteIndex(String url) throws IOException {
    Request request = new Request.Builder().url(url).build();
```

**Solución sugerida.** Exigir `https://` para repositorios remotos (permitir `file://` solo con opt-in explícito de desarrollo), validar que la URL del paquete comparte origen con el índice y añadir firma de índice/paquete (Ed25519) con clave pública embebida.

**Por qué merece la pena.** El gestor de paquetes es una superficie de código ejecutable; la firma es la única defensa real contra un repositorio comprometido. Complejidad media-alta.

---

### TF-010 — El rebuild del pipeline puede rechazar un dispatch en vuelo

**Tipo:** Bug
**Severidad:** Media
**Confianza:** Probable
**Ubicación:** `src/suite-runtime/.../SuiteRuntime.java` → `SuiteRuntime` → `rebuildPipeline()` / `closePipeline()`; `src/host/.../MessageDispatcher.java` → `dispatch()` / `close()`

**Descripción.** `rebuildPipeline()` es `synchronized`, pero `dispatch()` no. `closePipeline()` hace `unbind()` y `dispatcher.close()`. Un hilo que ya obtuvo el dispatcher y está a punto de `CompletableFuture.supplyAsync(..., executor)` puede hacerlo después de `executor.shutdown()`; el `ThreadPoolExecutor` lanza `RejectedExecutionException`, que `dispatch` no captura.

**Causa.** El cierre no coordina con los dispatch en vuelo más allá de `awaitTermination`, que no cubre la ventana entre `require()` y `supplyAsync`.

**Impacto funcional.** Un mensaje se pierde y se propaga una excepción al listener de la plataforma durante una instalación/desinstalación de paquete. Poco frecuente, pero real.

**Impacto en rendimiento/escalabilidad.** Ninguno; es correctitud bajo lifecycle.

**Condiciones para reproducir.** Instalar/desinstalar un paquete mientras llegan mensajes; ventana de carrera.

**Evidencia.**

```java
// SuiteRuntime.java:258-265
private synchronized void closePipeline() {
    messageDispatch.unbind();
    if (dispatcher != null) {
        dispatcher.close();
        dispatcher = null;
    }
    host = null;
}

// MessageDispatcher.java:123-129
futures = recipients.stream()
    .map(recipient -> CompletableFuture.supplyAsync(() -> {
        RoutingResult result = host.deliver(messageWithResolvedSource, recipient);
        ...
    }, executor))
// MessageDispatcher.java:342-344
public void close() {
    executor.shutdown();
```

**Solución sugerida.** Un `ReadWriteLock` por pipeline (lectura para dispatch, escritura para rebuild) o un contador de dispatch en vuelo con `Phaser`; capturar `RejectedExecutionException` y devolver un `DispatchReport` de fallo explícito. No bloquear el hilo del servidor más de lo necesario.

**Por qué merece la pena.** Elimina una excepción espuria en recargas. Complejidad media.

---

### TF-011 — `dispatch` bloquea al llamante y es secuencial por defecto

**Tipo:** Rendimiento
**Severidad:** Media
**Confianza:** Hipótesis
**Ubicación:** `src/host/src/main/java/me/majhrs16/suite/host/MessageDispatcher.java` → `MessageDispatcher` → `dispatch()`; `src/host/.../ConfigLoader.java:89`

**Descripción.** `dispatch()` procesa destinatarios y espera cada futuro con `get(10 s)`. Con `engine.parallel=false` (por defecto) todo ocurre en el hilo llamante. La mitigación depende de que `sync-bukkit` descargue el evento a su scheduler; si un adaptador llama a `dispatch` desde el hilo principal, la traducción/render bloquean el tick.

**Causa.** El pipeline es síncrono por diseño; la paralización es opcional y solo por destinatario.

**Impacto funcional.** Con un traductor remoto lento, la latencia del chat crece linealmente con los destinatarios; en el peor caso, un tick largo.

**Impacto en rendimiento/escalabilidad.** Es el principal límite de throughput del diseño actual. Requiere medición (JMH/Gatling) para cuantificar.

**Condiciones para reproducir.** `engine.parallel=false`, 100 destinatarios, traducción con 100 ms de latencia.

**Evidencia.**

```java
// MessageDispatcher.java:121-137
if (engineParallel) { ... supplyAsync ... } else {
    for (Actor recipient : recipients) {
        RoutingResult result = host.deliver(messageWithResolvedSource, recipient);
        ...
    }
}
// MessageDispatcher.java:148
rr = future.get(10, TimeUnit.SECONDS);

// ConfigLoader.java:89
boolean parallel = bool(nested(map, ConfigPath.IFLOW, ConfigPath.IFLOW_ENGINE,
        ConfigPath.IFLOW_ENGINE_PARALLEL), false);
```

**Solución sugerida.** Medir primero; después, agrupar por idioma, cachear traducciones y renderizar una vez por variante. Mantener el dispatch fuera del hilo principal en todos los adaptadores (contrato documentado). No paralelizar por defecto sin datos.

**Por qué merece la pena.** Es el camino a escala, pero sin benchmark sería una optimización a ciegas. Prioridad media.

---

### TF-012 — Sin E2E real y con integración web fuera de `check`

**Tipo:** Deuda técnica
**Severidad:** Baja
**Confianza:** Confirmado
**Ubicación:** `README.md:52`; `docs/workdir/PLAN.md:238`; `src/web-editor/package.json:12-13`

**Descripción.** No hay prueba de chat → iFlow → format → delivery en servidores reales; el propio PLAN lo declara pendiente. El `check` del editor ejecuta formato, lint y tests unitarios, pero no `test:integration`.

**Causa.** El E2E real se planificó para después de `1.0.0` (ADR-034); el editor separa integración por coste/entorno.

**Impacto funcional.** Regresiones de integración entre módulos pueden pasar el gate. Los claims de «implementada y probada» son ciertos para unit tests, no para operación.

**Impacto en rendimiento/escalabilidad.** Sin datos E2E no se puede validar latencia ni comportamiento real de sinks.

**Condiciones para reproducir.** Cualquier cambio en el pipeline que rompa la integración pero no los unit tests.

**Evidencia.**

```text
README.md:52  | **Tests E2E pipeline completo** | ❌ Pendiente | ... requieren servidor real ...
PLAN.md:238   | **E2E.1** | Betatesting/E2E posterior a 1.0.0 | ... | Pendiente |
package.json:12 "test:integration": "node tests/integration/func.cjs && ..."
package.json:13 "check": "npm run format:check && npm run lint && npm run test"
```

**Solución sugerida.** Job E2E con Paper real (contenedor o servidor de test) y matriz de versiones; incluir `test:integration` en `check` o en un job de CI separado y obligatorio. Registrar resultados por commit.

**Por qué merece la pena.** Es la única forma de convertir «compila y pasa unit tests» en «funciona». Depende del cierre de la purga y de la infraestructura de test.

---

## 21. Defectos de diseño (resumen)

| ID | Defecto | Consecuencia |
|---|---|---|
| TF-001 | Pipeline de servicios obligatorios | Módulos no opcionales; PL-1 bloqueado |
| TF-007 | Doble modelo `Module`/`Extension` sin conexión | SDK muerto y documentación engañosa |
| TF-008 | Debug aislado en `host` y sin cablear | Feature anunciada no disponible |
| TF-011 | Pipeline síncrono con paralelismo opcional | Límite de throughput no medido |

No se incluyen como defecto la prioridad fija de backends iFlow ni las restricciones de configuración: son decisiones declaradas y justificadas.

---

## 22. Deuda técnica

1. **W3.5** — paridad `.flow` v3/VM (`PLAN.md:34-35`, `198-199`). Deuda reconocida, no oculta.
2. **V6** — compilador C, bloqueado por V1/V5 (`PLAN.md:501-511`).
3. **E2E.1** — betatesting real posterior a `1.0.0` (`PLAN.md:238`).
4. **TF-012** — integración web fuera del gate.
5. **Duplicación conceptual** — `MessageRenderer`/`MessageComponentRenderer`, `Module`/`Extension`.
6. **Documentación** — README/wiki prometen `/debug/*` y extensiones no cableadas.
7. **Ruido de repositorio** — diferencias `CRLF` en `gradlew.bat` (no funcional, pero ensucia `git status`).

---

## 23. Mejoras opcionales

- Añadir correlación por mensaje (`message.id`) en logs y métricas de latencia por etapa.
- Exponer métricas de drops/retries por sink en `/metrics`.
- Cachear traducciones por `(texto, idioma)` con TTL y límite de tamaño.
- Renderizar una vez por variante de idioma en lugar de por receptor.
- Añadir un `Secret` neutral en `core-api` para uniformar tokens.
- Documentar el modelo de hilos en `src/README.md` (hilo de evento, scheduler, pools).
- Incluir `test:integration` del editor en CI.
- Añadir un test de contrato que verifique que `broadcast` devuelve lo realmente encolado.

Ninguna de estas mejoras es un defecto; se listan separadas para no inflar el registro.

---

## 24. Roadmap priorizado

### P0 — Crítico (antes de cerrar la purga)

| # | Problema | Beneficio | Esfuerzo | Dependencia |
|---|---|---|---|---|
| 1 | TF-002 fail-open | Cierra fuga de autorización | 1-2 días | Ninguna |
| 2 | TF-005 éxito falso + drops | Fiabilidad del bus | 2-3 días | Ninguna |
| 3 | TF-001 pipeline opcional | Requisito de producto; desbloquea PL-1 | 1-2 semanas | ADR de pipeline |

### P1 — Alto

| # | Problema | Beneficio | Esfuerzo | Dependencia |
|---|---|---|---|---|
| 4 | TF-003/004 secretos | Higiene y comparación constante | 3-5 días | `Secret` neutral |
| 5 | TF-006 futuros del bus | API asíncrona real | 2-3 días | TF-005 |
| 6 | TF-010 rebuild concurrente | Cero excepciones en recarga | 2-4 días | Tests de estrés |
| 7 | TF-009 firma/HTTPS | Supply chain | 1 semana | Diseño de claves |
| 8 | TF-007 decidir SDK | Elimina código muerto | 3-5 días | Decisión de producto |

### P2 — Medio

| # | Problema | Beneficio | Esfuerzo | Dependencia |
|---|---|---|---|---|
| 9 | TF-008 cablear debug o retirar claim | Coherencia | 2-3 días | Adaptadores |
| 10 | TF-011 medir y cachear | Throughput | 1 semana | Benchmarks |
| 11 | TF-012 E2E + integración web | Confianza real | 1-2 semanas | Infraestructura |
| 12 | Documentación | Onboarding | 2-3 días | Cierre de hallazgos |

### P3 — Bajo

- Ruido `CRLF`.
- Métricas de latencia por etapa.
- `Secret` neutral.
- Limpieza de duplicación conceptual.

**Regla de cierre:** cada P0/P1 exige test de regresión que falle sin el arreglo y pase con él.

---

## 25. Matriz de puntuaciones

| Área | Puntuación /10 | Estado | Principales problemas |
|---|---:|---|---|
| Código | 7 | Sólido | Falso éxito en bus; secretos inconsistentes |
| Arquitectura | 8 | Sólida | Composition root rígido |
| Clean Architecture | 8 | Real | Obligatoriedad en runtime |
| Hexagonal | 8 | Real | Puertos sin implementación por defecto |
| Modularidad | 7 | Alta en compilación | Media en despliegue |
| Separación de responsabilidades | 7 | Buena | Orquestadores grandes |
| Legibilidad | 7 | Buena | Documentación desincronizada |
| Features | 7 | Amplias | Integración real no probada |
| Personalización | 6 | Media | Pipeline no configurable |
| Seguridad | 6 | Defensas buenas | Fail-open; secretos; supply chain |
| Rendimiento | 5 | No medido | Traducción/render por receptor |
| Escalabilidad | 4 | No demostrada | Sin sharding/batching/caché |
| Concurrencia | 6 | Consciente | Completitud y rebuild sin formalizar |
| Testing | 7 | Fuerte en unit | Sin E2E ni estrés |
| Mantenibilidad | 7 | Buena | Deuda conceptual y docs |
| Documentación | 5 | Abundante | Promete lo no cableado |

**Media ponderada aproximada:** 6,7/10. La nota refleja un proyecto técnicamente por encima de la media en estructura y tests, con madurez operativa pendiente.

---

## 26. Conclusión final

1. **¿Qué tan bueno es realmente TextFormatter Suite?** Es un proyecto técnicamente serio, con arquitectura modular real y una suite de tests poco habitual en plugins de Minecraft. Su calidad de código es buena; su madurez operativa aún no está demostrada.

2. **¿Qué partes están excepcionalmente bien diseñadas?** El núcleo `core-api` sin dependencias, el gestor de paquetes (`PackageLayout`, `PackageCache`, `ModuleHost`), el transporte HTTP con SSRF/pinning y la separación de adaptadores delgados.

3. **¿Qué partes son mediocres?** El cableado del pipeline (rígido), la semántica de éxito del `SyncBus`, la higiene de secretos y la documentación pública.

4. **¿Cuáles son sus peores defectos?** TF-001 (módulos no opcionales), TF-002 (fail-open de autorización), TF-005 (pérdida silenciosa con éxito falso) y TF-003/004 (secretos).

5. **¿Cuál es su mayor fortaleza arquitectónica?** La dirección de dependencias: contratos neutrales, features que no conocen la plataforma y adaptadores que solo implementan puertos. Es verificable, no declarativa.

6. **¿Cuál es su mayor riesgo futuro?** Que el composition root siga creciendo y convierta la modularidad en una ilusión de compilación, mientras la documentación promete features no cableadas.

7. **¿Qué tan mantenible es?** Alta para unit tests y módulos aislados; media para el pipeline y la documentación. La purga pre-1.0 ayuda porque elimina compatibilidad.

8. **¿Qué tan extensible es?** Alta por canales, sinks, backends y presets; baja para insertar/reordenar etapas del pipeline sin tocar `SuiteRuntime`.

9. **¿Qué tan preparado está para múltiples plataformas?** Bien: Spigot y Fabric comparten `core-api` y `suite-runtime`; los adaptadores son delgados. Falta E2E en ambas.

10. **¿Qué tan preparado está para una red grande?** No demostrado. El diseño actual es de un proceso, sin batching, caché distribuida ni presupuesto de latencia.

11. **¿Qué tendría que cambiar para escala network-level?** Pipeline por etapas con colas y backpressure medible, traducción cacheada/agrupada, renderizado compartido, sinks particionados, métricas p99 y pruebas de carga con umbrales.

12. **¿Qué partes NO deberían tocarse?** `core-api` (contratos), `PackageLayout`/`PackageCache`, `HttpTransport`, `ModuleHost` y la regla de dependencias de `checkArchitecture`. Son activos correctos.

13. **¿Qué debería hacerse primero?** TF-002 y TF-005 (correcciones locales de alto impacto), luego el ADR de pipeline para TF-001; después secretos y E2E.

14. **¿Qué porcentaje del proyecto es técnicamente maduro?** Aproximadamente **60%**: núcleo, módulos y tests están maduros; operación real, seguridad de secretos y escalabilidad siguen en fase de consolidación.

**Cierre.** La suite no necesita una reescritura. Necesita cerrar tres cosas concretas: hacer el pipeline opcional y declarativo, dejar de perder mensajes en silencio y unificar la higiene de secretos. Con eso, y con el E2E posterior a `1.0.0`, la base actual puede sostener el betatesting sin deuda estructural.

---

## Anexo A — Reproducibilidad

```sh
cd /home/majhrs16/Documentos/textformatter-suite
git rev-parse HEAD                    # 1cc3a77f
./gradlew build checkArchitecture checkLocks --no-daemon
./gradlew test --no-daemon --rerun-tasks
cd src/web-editor && npm run check
```

Resultados de esta ejecución:

- Gate: `BUILD SUCCESSFUL` · 36 módulos escaneados · 44 lockfiles · 0 sin lockfile.
- Tests: 1.441 · 0 fallos · 0 errores · 10 omitidos · 42 módulos.
- Editor: 129 comprobaciones · 0 fallos.

Los resultados JUnit se leyeron de `src/*/build/test-results/test/*.xml`; la marca de tiempo más reciente fue `2026-11-06 01:15`.

## Anexo B — Reglas contra falsos positivos

- No se declara bug sin evidencia de código o ejecución; las hipótesis se marcan como tales (TF-011).
- No se inventan benchmarks; el análisis de rendimiento es arquitectónico.
- No se declara Clean/Hexagonal por nombres de paquetes: se verificaron dependencias reales y la regla `checkArchitecture`.
- No se convierten preferencias en defectos: la prioridad de backends iFlow y las restricciones de configuración se tratan como decisiones.
- No se propone reescribir el proyecto: todas las soluciones son localizadas salvo TF-001, que exige un ADR por alcance de contrato.
- Cuando hay incertidumbre (timing, reproducción de carrera, escala) se indica explícitamente.
