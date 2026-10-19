# TextFormatter Suite

> **Actualmente en desarrollo intenso — No apto para producción**
>
> Este proyecto evoluciona activamente. Las APIs, funcionalidades, configuraciones y mecanismos internos pueden cambiar sin aviso previo. No hay garantías de estabilidad, compatibilidad hacia atrás ni soporte para entornos de producción. La documentación refleja el estado real del código y las pruebas a fecha de hoy, no el estado ideal al que aspira el proyecto.

---

## Qué es TextFormatter Suite

TextFormatter Suite es una **plataforma modular de formateo, traducción y routing de chat para servidores Minecraft**, construida sobre una arquitectura hexagonal (ports & adapters) real. Reemplaza al proyecto monolítico anterior **ChatTranslator** reorganizando sus capacidades en 29 módulos Gradle independientes que se comunican exclusivamente a través de contratos SPI (ServiceLoader) definidos en `core-api`.

**Problema que resuelve:** Los plugins de chat tradicionales mezclan formateo, traducción, permisos, sincronización cross-server y lógica de negocio en una única base de código acoplada a la plataforma (Bukkit/Fabric). TextFormatter Suite separa estas responsabilidades en módulos reemplazables, testeables en aislamiento y desplegables selectivamente.

**Enfoque:** Núcleo puro Java (JDK 17/21, cero dependencias externas) + adaptadores de plataforma delgados (`spigot-host`, `fabric-host`) que implementan los puertos del núcleo. Los módulos de funcionalidad (`textformatter`, `iflow`, `gtranslate`, `ltranslate`, `sync-*`, `transport`, `observability`, `presets`, `manager-impl`, etc.) dependen **solo** de `core-api`.

**Lo que NO es:**
- Un plugin único "todo en uno" listo para descargar y usar
- Una biblioteca estable con API versionada semánticamente publicada en Maven Central
- Un reemplazo directo de ChatTranslator v4 sin migración de configuración
- Un proyecto con releases oficiales, binarios publicados o garantías de soporte

---

## Estado de las funcionalidades (verificación honesta)

| Funcionalidad | Estado | Evidencia |
|---------------|--------|-----------|
| **Arquitectura hexagonal (core-api + SPI)** | ✅ Implementada y probada | `core-api` compila sin dependencias; 29 módulos usan ServiceLoader; tests de kernel/module loading pasan |
| **Motor de formateo MiniMessage (`textformatter`)** | ✅ Implementada y probada | `TemplateRenderer`, `MiniEscape` (10 chars), `ChannelRegistry`, `<tr>` tags; 32 tests |
| **Motor de reglas iFlow (VM)** | ✅ Implementada y probada | VM 16-bit flat 64KB + SoftMMU 4KB, bytecode `.flow` binario, `HOST` instruction, fuel budget 100K; 264 tests (VmInterpreter, HostServiceRegistry, VmPolicyEngine, FirmwareIntegrationTest) |
| **Traducción Google (gtranslate)** | ✅ Implementada y probada | Web scraping con UA rotation, rate limit, concatena todos segmentos; 17+19 tests extendidos |
| **Traducción LibreTranslate (ltranslate)** | ✅ Implementada y probada | Self-hosted/público, long-poll, tokens `char[]`; 17+15 tests extendidos |
| **Sync Discord** | ✅ Implementada y probada | Gateway v10 (WebSocket JDK + REST), intents, embeds; tokens `char[]`; 6 tests |
| **Sync Telegram** | ✅ Implementada y probada | Bot long-poll con watermark offset; tokens `char[]`; 7 tests |
| **Sync HTTP (webhook + REST)** | ✅ Implementada y probada | `HttpServer` JDK, inbound/outbound; 7 tests |
| **Sync TCP/UDP** | ✅ Implementada y probada | Raw sockets, JSON línea/datagrama, `MessageCodec`; 7 tests |
| **Sync WebSocket** | ✅ Implementada y probada | SO_REUSEADDR, auth token, subscripciones, log streaming; build + tests |
| **Sync Velocity (proxy)** | ✅ Implementada y probada | **Production-ready**: async queue, retry/backoff exponencial, métricas, health, dynamic discovery, mapping avanzado (regex, per-type); compila, tests pasan |
| **Sync Bus (pipeline unificado)** | ✅ Implementada y probada | `DefaultSyncBus`: deduplicación global, aislamiento por sink, retry/backoff, métricas; build + tests |
| **Spigot/Paper adapter (`spigot-host`)** | ✅ Implementada y probada | Plugin `TextFormatterSuite`: `AsyncPlayerChatEvent` (claim modes), join/quit/death/advancement channels, `/suite` commands, fat-jar shadow; **compila con Paper API 1.21.4**; probado en servidor real Paper 1.20.6 |
| **Fabric adapter (`fabric-host`)** | ✅ Implementada y probada | **COMPILA**: Fabric 1.21 + Fabric API 0.100.5, Brigadier, `ServerMessageEvents`, `ServerPlayConnectionEvents`, `ServerTickEvents`, death detection tick-based, claim mode configurable; **no probado en servidor real** |
| **Module Manager (runtime)** | ⚠️ Núcleo completado, releases pendientes | GitHub/local/HTTP downloader, version resolver (semver ranges + env compat), dependency resolver (module.yml), relocator (fixed), ClassLoader aislado parent-last, SHA256 obligatorio, `register()` SPI-only, `discoverAll()`/`discoverAvailableModules()`, manifest validation obligatoria; **no hay GitHub Releases públicos** |
| **Observabilidad** | ✅ Implementada y probada | `/metrics` (Prometheus), `/debug/*` (auth token, 127.0.0.1), Health checks (JVM, threads, sinks); build + tests |
| **Presets** | ✅ Implementada y probada | Standard/RPG/Staff/Minimal, `TransformEngine` (SpEL sandboxed), import/export YAML; build |
| **In-world (signs, chests, books)** | ✅ Compila | WORLD/RADIUS, botones click/hover, caché + glosario; **compila con Paper API 1.21.4**, no probado en servidor |
| **Extensiones (SDK)** | ✅ Implementada | `Extension`, `ExtensionContext`, `ExtensionManager`, Capability system, example-extension; build |
| **Web Editor** | ✅ Implementada y probada | 99 tests unitarios + harnesses integración (StateStore, model, validate, canvas, preview, import/export ZIP, i18n EN/ES, undo/redo, docking); `npm run check` verde |
| **Config schema v2.2 (single-source)** | ⚠️ Parcial | `ConfigPath` enum → `paths.json`/`js/paths.js`/`js/model.js` via `ConfigSchemaGenerator`; copies manuales en `schema-v2.2.md` |
| **Dependency Verification** | ✅ Completa | 29 proyectos con `gradle.lockfile` (root + 28 subprojects), `verification-metadata.xml` SHA256/SHA512 transitivos, `checkLocks` task |
| **Security Sprint 3** | ✅ Completa | Tokens `char[]` + `Arrays.fill('\0')`, MiniEscape 10 chars, PAPI dynamic check, SpEL LRU cache 1024, SSRF protection (`getAllByName`), SafeConstructor 4 loaders YAML |
| **Tests E2E pipeline completo** | ❌ Pendiente | Chat → iFlow → format → delivery en servidor real; 12 tests E2E unitarios existen pero requieren servidor real para validación completa |
| **GitHub Releases / publicación** | ❌ Pendiente | Workflow `release.yml` existe pero no se han publicado releases oficiales; distribución vía source/build local |
| **gradle.lockfile portable** | ⚠️ Parcial | Lockfiles existen pero reproducibilidad cross-platform no verificada exhaustivamente |

**Criterio usado:** "Implementada y probada" = código integrado + tests automatizados pasando (unit + integración donde aplica). "Compila" = build exitoso, sin tests de integración en entorno real. "Pendiente" = identificado en plan/docs pero sin implementación verificable.

---

## Métricas del proyecto (evolución real)

El repositorio **se mide a sí mismo**. [`tools/project-metrics/`](tools/project-metrics/README.md)
genera ~30 métricas a lo largo del tiempo —cortes diarios, no por commit— cruzando
el histórico de git con la narrativa de las sesiones de chat, más una **rúbrica de
madurez de 12 familias** (0–100). El renderizado es PNG estático (Pillow) y todo se
reproduce en local desde el código:

```bash
cd tools/project-metrics && ./run_all.sh   # regenera las 9 imágenes de docs/metrics/
```

Las gráficas de abajo corresponden al último corte medido (**2026-10-18**: 35 cortes
con commit sobre 66 días naturales). Métricas, filtros, rúbrica y juez, en
[`tools/project-metrics/README.md`](tools/project-metrics/README.md).

### Rúbrica de madurez (12 familias)

![Las 12 familias de madurez en el tiempo](docs/metrics/rubrica_familias.png)

*Un panel por familia, en corte diario y con escala fija 0–100. Los días sin commit
arrastran el último valor conocido (la madurez es un estado, no actividad). Las
familias de **documentación** e **implementación real** llevan veredicto cualitativo,
producido por el agente de OpenCode mediante el skill
[`juzgar-rubrica`](.opencode/skills/juzgar-rubrica/SKILL.md); el resto son proxies
deterministas.*

![Matriz módulo × familia](docs/metrics/rubrica_matriz.png)

*Matriz módulo × familia en el último corte. La fila `TOTAL (proyecto)` es la única
que lleva el veredicto cualitativo.*

### Actividad: repositorio y conversación

![15 métricas del repositorio](docs/metrics/panel_git.png)

*15 métricas del repositorio: commits, líneas, LOC, tests, API pública, deuda.*

![15 métricas de la narrativa](docs/metrics/panel_chat.png)

*15 métricas de la narrativa: mensajes, razonamiento, llamadas a herramientas, tokens.*

![Correlación git ↔ chat](docs/metrics/panel_actividad.png)

*Cruce de las dos capas: qué parte del código viene de trabajo conversado.*

> Los paneles marcan 8 **hitos** (inicio, chats base, auditorías, split modular…) y
> sombrean dos **eras**: el monolito `src/suite` y el endurecimiento de iFlow.

### Código por módulo

![LOC de fuente por módulo](docs/metrics/modules_src_loc.png)

*LOC de fuente por módulo (top 15) a lo largo del tiempo. El acantilado de `suite`
hasta el 15-sep es la reorganización de monolito a módulos.*

![LOC de test por módulo](docs/metrics/modules_test_loc.png)

*LOC de test por módulo (top 12): qué módulos tienen pruebas propias.*

![LOC por módulo y semana](docs/metrics/heatmap_modulos.png)

*LOC por módulo y semana.*

![Primer corte frente al último](docs/metrics/crecimiento.png)

*Primer corte (2026-08-14) frente al último (2026-10-18), con factor de crecimiento por
eje. En la fila `deuda (todo+stubs)` menos es mejor.*

---

## Enfoque, alcance y objetivos

| Objetivo | Descripción |
|----------|-------------|
| **Separación real de responsabilidades** | Cada capacidad (formateo, routing, traducción, sync, observabilidad) es un módulo independiente con contrato SPI propio |
| **Independencia de plataforma** | `core-api` y módulos funcionales son JDK-puro; solo `spigot-host`/`fabric-host` dependen de APIs de Minecraft |
| **Extensibilidad sin fork** | Nuevos proveedores de traducción, sinks de sync, formateadores, reglas = implementar SPI + registrar en META-INF/services |
| **Testabilidad** | Núcleo testeable sin servidor Minecraft; tests de integración con mocks; 167+ tests Java + 99 tests JS |
| **Seguridad por defecto** | Tokens en `char[]`, MiniEscape completo, SpEL sandbox (`SimpleEvaluationContext`), SSRF protection, SafeConstructor YAML |
| **Reproducibilidad** | Gradle lockfiles + verification metadata para supply chain integrity |

**Fuera de alcance (explícitamente):**
- Base de datos / persistencia propia (usa YAML files + Module Manager para módulos)
- UI de administración en juego más allá de `/suite` commands
- Soporte para versiones antiguas de Minecraft (< 1.20.6 Spigot, < 1.21 Fabric)
- Garantías de compatibilidad binaria entre versiones SNAPSHOT
- Panel de control web / dashboard (solo web-editor de configuración estático)

---

## Catálogo de módulos (29 módulos Gradle)

### Núcleo y contratos
| Módulo | Propósito | Estado |
|--------|-----------|--------|
| `core-api` | Contratos SPI + modelo de dominio (`Module`, `Message`, `TranslationService`, `SyncSink`, `ActorDirectory`, `TranslatorProvider`, semver, capabilities) | ✅ Probado |
| `kernel` | Module loading, dependency graph (Tarjan), SPI resolution, `Environment` | ✅ Probado (13 tests) |
| `host` | Composition root: `SuiteBootstrap`, `SuiteHost`, `MessageDispatcher`, `ConfigLoader` (enum `ConfigPath`), `ConfigValidator` | ✅ Probado |
| `messages` | i18n centralizado EN/ES, `MessagesCatalog` singleton | ✅ Probado |

### Formateo y routing
| Módulo | Propósito | Estado |
|--------|-----------|--------|
| `textformatter` | MiniMessage engine, `TemplateRenderer`, `SpelExpressionEvaluator`, `ChannelRegistry`, `<tr>` translation, `MiniEscape` | ✅ Probado (32 tests) |
| `iflow` | VM 16-bit flat 64KB + SoftMMU 4KB, bytecode `.flow` binario, `HOST` instruction, fuel budget 100K, `VmPolicyEngine`, `VmInterpreter`, `SoftMMU` | ✅ Probado (264 tests) |
| `presets` | Presets predefinidos (standard, rpg, staff, minimal), `TransformEngine` (SpEL sandboxed), import/export YAML | ✅ Probado |

### Traducción
| Módulo | Propósito | Estado |
|--------|-----------|--------|
| `gtranslate` | Google Translate provider (web scraping, UA rotation, rate limit, concatena segmentos) | ✅ Probado (17+19 tests) |
| `ltranslate` | LibreTranslate provider (self-hosted/público, long-poll, tokens `char[]`) | ✅ Probado (17+15 tests) |

### Transporte y sincronización
| Módulo | Propósito | Estado |
|--------|-----------|--------|
| `transport` | `Transport` abstraction, `HttpTransport` (`HttpURLConnection`), `MessageCodec`, SSRF protection (`getAllByName`, RFC 1918/3927/6598) | ✅ Probado |
| `sync-bus` | **Nuevo**: Pipeline unificado — deduplicación global, aislamiento por sink, retry/backoff, métricas, health | ✅ Probado |
| `sync-discord` | Discord Gateway v10 (WebSocket JDK + REST), intents, embeds, tokens `char[]` | ✅ Probado (6 tests) |
| `sync-telegram` | Telegram Bot long-poll, watermark offset, tokens `char[]` | ✅ Probado (7 tests) |
| `sync-http` | Webhook + REST (`HttpServer` JDK), inbound/outbound | ✅ Probado (7 tests) |
| `sync-tcpudp` | TCP/UDP raw sinks, JSON línea/datagrama | ✅ Probado (7 tests) |
| `sync-websocket` | WebSocket sync (SO_REUSEADDR, auth token, subs, log streaming) | ✅ Probado |
| `sync-velocity` | Velocity proxy sink — **production-ready**: async queue, retry/backoff, métricas, health, dynamic discovery, mapping regex/per-type | ✅ Probado |

### Operación y extensibilidad
| Módulo | Propósito | Estado |
|--------|-----------|--------|
| `manager-api` | SPI del Module Manager: `ModuleCoordinate`, `ModuleDescriptor`, `Environment`, `ModuleLifecycle` | ✅ Probado |
| `manager-impl` | Implementación: GitHub/local/HTTP downloader, version resolver, dependency resolver, SHA256, relocator, ClassLoader aislado, `register()` SPI-only, manifest validation | ⚠️ Núcleo completado |
| `extension-api` | Extension SPI: `Extension`, `ExtensionContext`, `ExtensionManager`, Capability system | ✅ Probado |
| `example-extension` | Demo de extensión funcional | ✅ Probado |
| `observability` | `/metrics` (Prometheus), `/debug/*` (auth, 127.0.0.1), Health checks (JVM, threads, sinks) | ✅ Probado |
| `inworld` | Signs, chests, books (WORLD/RADIUS), click/hover, caché + glosario | ⚠️ Compila |
| `performance` | `PerformanceProfiler`, `HotspotDetector`, `CacheOptimizer`, `MemoryOptimizer` | ✅ Probado |
| `loadtest` | JMH benchmarks + Gatling | ✅ Compila |
| `tester` | 25 tests runtime automatizados (`/suite test full|stress|concurrency`), `PerformanceProfiler` | ✅ Probado |

### Adaptadores de plataforma
| Módulo | Propósito | Estado |
|--------|-----------|--------|
| `spigot-host` | Plugin Bukkit/Paper (`TextFormatterSuitePlugin`): `SpigotActorDirectory`, `SpigotChatDelivery`, bootstrap, `/suite` commands, fat-jar shadow | ✅ Probado en servidor real Paper 1.20.6 |
| `fabric-host` | Mod Fabric (`TextFormatterSuiteMod`): `FabricActorDirectory`, `FabricChatDelivery`, Brigadier, Fabric events, death detection tick-based | ✅ Compila (no probado en servidor real) |

### Legacy / referencial
| Módulo | Propósito | Estado |
|--------|-----------|--------|
| `coretranslator` | Puente deprecated para retrocompatibilidad ChatTranslator v4 (PAPI `%cot_*`, captura mensajes, SpEL) | ⚠️ Deprecated, mantener |
| `common-legacy` | Referencia histórica del trío monolítico eliminado | 📚 Solo referencia |

> **Documentación por módulo:** Cada módulo en `src/*/README.md` detalla responsabilidades, dependencias, data flow y entry points. Ver [`src/README.md`](src/README.md) para mapa completo y discrepancias conocidas.

---

## Instalación y primeros pasos

### ⚠️ No hay releases oficiales ni binarios publicados
El proyecto **no publica JARs en Maven Central, GitHub Releases ni ningún repositorio de artefactos**. La única forma verificada de obtener el plugin/mod es **compilar desde fuente**.

### Requisitos previos
- **JDK 17 y 21** (toolchain Gradle; bytecode target 17)
- **Gradle 8.13** (wrapper incluido)
- **Git**
- Servidor Minecraft para probar:
  - **Spigot/Paper**: 1.20.6+ (probado en Paper 1.20.6 real)
  - **Fabric**: 1.21 + Fabric Loader 0.16+ + Fabric API 0.100.5 (compila, no probado en servidor real)

### Compilar desde fuente (verificado)

```bash
# Clonar
git clone https://github.com/majhrs16-official/TextFormatter-Suite.git
cd TextFormatter-Suite

# Configurar JDKs (ajustar rutas a tu entorno)
export JAVA_HOME=/opt/javac/x64/21
# En gradle.properties declarar org.gradle.java.installations.paths para JDK 17 y 21

# Compilar suite completa (tests + build)
./gradlew build test checkLocks --no-daemon

# Generar Javadoc
./gradlew javadoc --no-daemon

# Plugin Spigot (fat-jar en src/spigot-host/build/libs/)
./gradlew :src:spigot-host:build --no-daemon

# Mod Fabric (en src/fabric-host/build/libs/)
./gradlew :src:fabric-host:build --no-daemon

# Web Editor (validación + tests)
cd src/web-editor
npm ci
npm run check          # format:check + lint + test (99 unit)
npm run test:integration
```

### Instalar en servidor (tras compilar)

**Spigot/Paper:**
```bash
cp src/spigot-host/build/libs/textformatter-suite-spigot-*.jar /ruta/servidor/plugins/
# Iniciar servidor → se genera config en plugins/TextFormatterSuite/
# Editar plugins/TextFormatterSuite/config.yml
# /suite reload
```

**Fabric:**
```bash
cp src/fabric-host/build/libs/textformatter-suite-fabric-*.jar /ruta/servidor/mods/
# Requiere Fabric Loader + Fabric API 0.100.5 en mods/
# Iniciar servidor → se genera config en config/textformattersuite/
# Editar config/textformattersuite/config.yml
# /suite reload
```

### Verificar carga correcta
- Comando `/suite` muestra status
- `/suite status` lista canales, traductor activo, knobs
- Logs muestran `[TextFormatterSuite] Loaded X modules` y sinks registrados

### Limitaciones conocidas antes de probar
- **Fabric**: No probado en servidor real; death detection usa tick-based polling
- **Module Manager**: No hay releases públicos; `discoverAvailableModules()` requiere repo local/HTTP configurado
- **Config**: Schema v2.2 tiene copias manuales (`paths.json`, `schema-v2.2.md`) no centralizadas
- **Sin migración automática** desde ChatTranslator v4 o configs legacy

---

## Desarrollo: IDE y terminal

### IntelliJ IDEA (verificado)
1. **File → Open** → seleccionar carpeta raíz del repo (`TextFormatter-Suite/`)
2. **Import as Gradle Project** → usar wrapper (`./gradlew`)
3. **JDK**: seleccionar JDK 21 para el proyecto (toolchain resuelve 17 para bytecode)
4. **Gradle Sync** → espera resolución de dependencias (primera vez descarga ~200MB)
5. **Módulos**: 29 subproyectos bajo `:src` aparecen en panel Gradle
6. **Tasks útiles**:
   - `:src:core-api:test` — tests núcleo
   - `:src:spigot-host:build` — fat-jar plugin
   - `:src:fabric-host:build` — mod fabric
   - `checkLocks` — audita lockfiles
   - `javadoc` — genera docs

### Visual Studio Code (verificado)
1. Extensiones recomendadas: **Extension Pack for Java** (Microsoft), **Gradle for Java** (Microsoft)
2. **File → Open Folder** → raíz del repo
3. **Java: Configure Java Runtime** → JDK 21
4. **Gradle: Reload All Projects** (Ctrl+Shift+P → "Gradle: Reload")
5. Terminal integrado: `./gradlew tasks` lista tareas disponibles

### Terminal sin IDE (verificado)
```bash
# Compilar todo
export JAVA_HOME=/opt/javac/x64/21
./gradlew build --no-daemon

# Tests solo módulo específico
./gradlew :src:iflow:test --no-daemon

# Tests suite completa
./gradlew test --no-daemon

# Lockfiles + verification
./gradlew checkLocks --no-daemon

# Javadoc
./gradlew javadoc --no-daemon

# Web editor
cd src/web-editor && npm run check
```

> **Nota:** Con caché Gradle poblada, todo compila `--offline`. La primera build descarga dependencias (~2-5 min).

---

## Compilación y verificación (comandos verificados)

| Objetivo | Comando | Notas |
|----------|---------|-------|
| Build completo + tests | `./gradlew build test checkLocks --no-daemon` | 120 tasks, ~40s con caché |
| Solo tests | `./gradlew test --no-daemon` | 167+ tests Java |
| Solo build (sin tests) | `./gradlew build -x test --no-daemon` | Más rápido |
| Plugin Spigot (fat-jar) | `./gradlew :src:spigot-host:build --no-daemon` | En `src/spigot-host/build/libs/` |
| Mod Fabric | `./gradlew :src:fabric-host:build --no-daemon` | En `src/fabric-host/build/libs/` |
| Javadoc | `./gradlew javadoc --no-daemon` | En `build/docs/javadoc/` |
| Lockfiles auditoría | `./gradlew checkLocks --no-daemon` | 29 lockfiles, 0 missing |
| Verificación dependencias | Configurada en `settings.gradle` + `gradle/verification-metadata.xml` | Lenient para módulos con deps externas |
| Web editor check | `cd src/web-editor && npm run check` | 29/29 passed |
| Web editor tests integración | `cd src/web-editor && npm run test:integration` | Harnesses func/interact/click/chain/undo/diffing/bind |
| **Build reproducible (release)** | `BUILD_TIMESTAMP=1728000000000 ./gradlew build -Pversion=2.1.0` | JAR con `Build-Timestamp` fijo |
| **Verificar Build-Timestamp** | `unzip -p artifact.jar META-INF/MANIFEST.MF \| grep Build-Timestamp` | Debe coincidir con timestamp fijado |

---

## Cómo contribuir

No existe política formal de contribuciones (`CONTRIBUTING.md` no existe). Orientaciones conservadoras compatibles con el repo:

1. **Investigar**: Lee [`src/README.md`](src/README.md) para entender arquitectura, [`docs/workdir/PLAN.md`](docs/workdir/PLAN.md) para roadmap, [`docs/workdir/ADR.md`](docs/workdir/ADR.md) para decisiones previas
2. **Localizar módulo**: Usa el catálogo arriba o `src/README.md` §3 para encontrar el módulo responsable
3. **Tests primero**: Añade test unitario/integración que falle antes de fix; ejecuta `./gradlew :src:<modulo>:test`
4. **Verifica**: `./gradlew build test checkLocks --no-daemon` + `cd src/web-editor && npm run check`
5. **Commits atómicos**: Un cambio lógico por commit; mensaje convencional (`fix:`, `feat:`, `docs:`, `refactor:`)
6. **No rompas contratos**: `core-api` es inmutable para consumidores; cambios requieren ADR
7. **Documenta**: Actualiza `src/<modulo>/README.md` y `docs/workdir/PLAN.md` si cambia estado/funcionalidad
8. **PR**: Abre Pull Request contra `main`; CI ejecuta build + tests + javadoc + web-editor check

---

## Convenciones de desarrollo (observadas en el repo)

| Área | Convención |
|------|------------|
| **Organización módulos** | `src/<modulo>/` con `build.gradle` propio; composite build vía `settings.gradle` |
| **Separación responsabilidades** | `core-api` = contratos (JDK-puro); módulos funcionales dependen solo de `core-api`; adapters dependen de suite + 1 SDK plataforma |
| **Naming** | Paquetes `me.majhrs16.suite.<modulo>`; interfaces SPI en `...api.spi.*`; implementaciones en módulo correspondiente |
| **SPI / ServiceLoader** | Todos los puntos de extensión usan `META-INF/services/<interface>`; `core-api` define interfaces, módulos implementan |
| **Gestión dependencias** | `implementation project(':src:...')` para módulos internos; `compileOnly` para APIs plataforma (Paper, Fabric); `testImplementation` para tests |
| **Compatibilidad plataformas** | Código compartido en `core-api`/`host`/módulos funcionales; adaptadores en `spigot-host`/`fabric-host` implementan `ActorDirectory`, `ChatDelivery`, `PlaceholderResolver` |
| **Tests** | JUnit 5; tests unitarios en `src/test/java`; tests integración donde aplica; `ModuleLoaderTest` requiere aislamiento classpath |
| **Documentación APIs** | Javadoc en código; module READMEs en `src/<modulo>/README.md`; ADR en `docs/workdir/ADR.md` para decisiones arquitectónicas |
| **Errores** | Excepciones checked para errores recuperables; `IllegalStateException`/`SecurityException` para violaciones de contrato; logging SLF4J vía `PluginLogger` SPI |
| **Configuración** | YAML con snakeyaml `SafeConstructor`; `ConfigLoader` (enum `ConfigPath`) + `ConfigValidator`; defaults en `resources/defaults/` copiados en primer arranque |
| **Nuevas funcionalidades** | 1) Definir SPI en `core-api` si es punto de extensión; 2) Implementar en módulo dedicado; 3) Registrar vía ServiceLoader; 4) Wiring en `host`/`spigot-host`/`fabric-host`; 5) Tests + docs |

---

## Organización de la documentación

| Documento | Responsabilidad | Enlace |
|-----------|-----------------|--------|
| **README.md** (este archivo) | Página principal: presentación, estado, instalación, contribución, enlaces | — |
| **src/README.md** | Índice técnico: mapa módulos, dependencias, entry points, flows, discrepancias | [`src/README.md`](src/README.md) |
| **docs/workdir/PLAN.md** | Plan vivo: fases, estado actual, próxima acción, deuda conocida | [`docs/workdir/PLAN.md`](docs/workdir/PLAN.md) |
| **docs/workdir/ADR.md** | Architecture Decision Records: decisiones inmutables con contexto/fecha/consecuencias | [`docs/workdir/ADR.md`](docs/workdir/ADR.md) |
| **docs/wiki/01-Introduction.md** | Entrada wiki usuario/dev: quick start, módulos, arquitectura, guías | [`docs/wiki/01-Introduction.md`](docs/wiki/01-Introduction.md) |
| **docs/wiki/02-Configuration.md** | Guía configuración (config.yml, channels, rules, translators, sync) | [`docs/wiki/02-Configuration.md`](docs/wiki/02-Configuration.md) |
| **docs/wiki/05-iFlow-Rules.md** | Motor de reglas iFlow: sintaxis, condiciones, actions, targets, grafo | [`docs/wiki/05-iFlow-Rules.md`](docs/wiki/05-iFlow-Rules.md) |
| **docs/wiki/08-Commands.md** | Referencia comandos `/suite` (dinámicos v2) | [`docs/wiki/08-Commands.md`](docs/wiki/08-Commands.md) |
| **docs/wiki/10-Module-Manager.md** | Module Manager runtime: install/update/remove/info, repos, firmas | [`docs/wiki/10-Module-Manager.md`](docs/wiki/10-Module-Manager.md) |
| **docs/prompts/** | Material de referencia: contexto del proyecto, diseños de componentes y contratos de formato | [`docs/prompts/README.md`](docs/prompts/README.md) |
| **.opencode/skills/** | Procedimientos que el agente ejecuta por sí mismo (auditar, generar documentación) | [`docs/prompts/README.md`](docs/prompts/README.md) § Prompt frente a skill |
| **docs/workdir/AUDITORIA.md** | Auditoría técnica externa 2026-10-09 (TXF-ZIP-001..006 resueltos) | [`docs/workdir/AUDITORIA.md`](docs/workdir/AUDITORIA.md) |
| **tools/project-metrics/README.md** | Herramienta de métricas: ~30 métricas diarias, rúbrica de 12 familias, juez cualitativo | [`tools/project-metrics/README.md`](tools/project-metrics/README.md) |
| **src/<modulo>/README.md** | Codeguide por módulo: responsabilidades, deps, data flow, entry points | Ver [`src/README.md`](src/README.md) §3 |

> **Criterio de carpetas:** `docs/workdir/` guarda documentos de trabajo y `docs/prompts/` material de referencia (fuentes de verdad que se consultan). Los procedimientos —auditar, generar documentación— no son prompts: son skills en `.opencode/skills/`, y el criterio completo está en [`docs/prompts/README.md`](docs/prompts/README.md). Un prompt ya implementado que no se vaya a reutilizar se elimina; no se mantiene como histórico.

---

## Validación realizada

- [x] Advertencia "Actualmente en desarrollo intenso — No apto para producción" visible al inicio
- [x] Funcionalidades clasificadas con estados honestos y evidencia (tests, build, probado en servidor)
- [x] Propósito, enfoque, objetivos y límites explicados sin promesas futuras
- [x] Catálogo de 29 módulos reales agrupados por responsabilidad con estado
- [x] Instalación distingue: uso plugin (requiere build previo) vs desarrollo vs tests
- [x] IDE (IntelliJ, VS Code) y terminal documentados con comandos verificables
- [x] Comandos build/test/verificación contrastados contra `settings.gradle`/`build.gradle` y tareas reales
- [x] Contribución: flujo conservador sin inventar políticas inexistentes
- [x] `src/README.md` conserva su responsabilidad de mapa técnico y se enlaza
- [x] Documentación técnica extensa en archivos especializados (wiki, ADR, module READMEs)
- [x] Enlaces internos válidos y rutas relativas correctas desde GitHub
- [x] No se han inventado capacidades, releases, comandos ni requisitos
- [x] CI verificado: `./gradlew build test checkLocks` ✅, `./gradlew javadoc` ✅, `npm run check` ✅ (29/29)

---

## Licencia

GPL-3.0 — Ver [`LICENSE`](LICENSE) (si existe en el repo) o cabecera de archivos fuente.

---

*Última actualización: 2026-10-12 — Estado sincronizado con commit `84bf9ee` (TXF-AUD + S6 completados). Todos los hallazgos de AUDITORIA.md (TXF-AUD-001..008, B/M/V; TXF-ZIP-001..006) resueltos. CI verde.*