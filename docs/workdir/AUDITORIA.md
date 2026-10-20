# AUDITORÍA TÉCNICA INTEGRAL — TEXTFORMATTER SUITE

**Fecha UTC:** 2026-10-20 01:31
**Commit auditado:** `71f23187a2f73cb61eda327ff56f699c2b93f064`
**Modo:** auditoría en solo lectura. La remediación derivada (material y P0 de código) se detalla en la sección 10 y no altera el diagnóstico.
**Fuentes:** las dos auditorías de `$HOME/AUDITORIA.md` (en adelante *A1* y *A2*), releídas y verificadas contra el árbol de trabajo.

---

## 0. Cobertura de auditoría

| Área | Inspeccionado | Método |
|---|---|---|
| Estructura y módulos | 31 `build.gradle`, `settings.gradle`, 11 builds standalone | lectura + grafo de dependencias |
| Build / CI / release | `build.gradle`, `.github/workflows/ci.yml`, `release.yml` | lectura |
| Supply chain | `verification-metadata.xml`, `.bak`, 30 lockfiles, script generador | hashes recalculados |
| Tests | 40 XML de resultados, `src/*/src/test`, `@Disabled`, `Thread.sleep` | parseo XML + grep |
| Código | hallazgos de A2 (seguridad, concurrencia, recursos) | lectura directa + prueba Java del regex SSRF |
| Documentación | README raíz, `src/README.md`, wiki, ADR, PLAN, prompts | grep + comprobador de enlaces |
| Métricas | `tools/project-metrics/*.py`, `judge_scores.csv` | lectura |
| Working tree / artefactos | 361 MB en disco, 672 ficheros trackeados, 73 entradas que `git clean -ndX` borraría | `git status`, `du`, `find` |
| Historial | 267 commits, renombrados y reescrituras | `git log` |

**No ejecutado:** builds ni suites completas. La máquina tiene 7,5 GiB y hay `hs_err_pid*.log` con OOM de JVM; además el modo es solo lectura. Los conteos de tests provienen de los XML ya presentes en `build/test-results`, no de una ejecución nueva.

---

## 1. Resumen ejecutivo

La arquitectura por módulos es **la mejor parte del proyecto**: 31 módulos, `core-api` sin dependencias internas, **cero ciclos** y una dirección de dependencias limpia. No es una arquitectura de nombres: el grafo real la respalda.

El problema es la **verificación**. El proyecto afirma cosas que el árbol de trabajo contradice:

1. El `verification-metadata.xml` versionado **no verifica nada**: sus SHA-256 son el hash del texto `grupo:artefacto:versión`, usa un esquema que Gradle no reconoce y está en la ruta equivocada. El fichero válido solo existe como `.bak`.
2. El **release es imposible**: `release.yml` compila `manager-api`/`manager-impl`, que no existen, no aplica la versión del tag y referencia un `CHANGELOG.md` ausente.
3. El **token de Discord se borra antes de usarse**: `start()` hace `Arrays.fill(token,'\0')` y `send()` reconstruye el token desde ese array ya vacío.
4. El **README marca “✅ Probado”** en módulos que no tienen un solo test (`messages`, `observability`, `presets`, `sync-bus`, `sync-websocket`).
5. La **documentación describe un proyecto anterior**: 29 módulos (hay 31), iFlow como motor de reglas SpEL (hoy es una VM de bytecode `.flow`), comandos que no existen, tres estados distintos de Fabric.
6. La **automedición premia el fichero falso**: `maturity.py` da 20 puntos por la mera presencia de `verification-metadata.xml`.
7. El **árbol de trabajo está sucio**: 361 MB en disco para 7,8 MB de contenido versionado; 86 directorios `build/`, 114 MB de `node_modules`, 109 MB de `.gradle/`, 16 MB de datos de métricas fuera de git, 10 `hs_err` + 4 `replay` + 9 logs de VM, 2 `.bak` y 12 `gradle-wrapper.jar` versionados. El repo real son 672 ficheros; el resto es taller.

**Conclusión:** arquitectura ~8/10; verificación real ~4/10; higiene ~3/10. Antes de paralelizar agentes hay que arreglar la red de seguridad (supply chain, release, tests, documentación) y limpiar el árbol. Sin eso, cuatro agentes solo aceleran la producción de deuda.

---

## 2. Arquitectura real

### 2.1. Grafo de módulos

Extraído de los `build.gradle` (no de la documentación):

```
core-api  (0 dependencias internas)
   ▲
   ├── kernel ──► host ──► spigot-host / fabric-host
   ├── textformatter ──► iflow (VM)
   ├── transport
   ├── gtranslate / ltranslate
   ├── sync-* (discord, telegram, http, tcpudp, websocket, velocity, bus)
   ├── manager-spi ◄── manager-core ◄── manager-apt
   ├── extension-api ◄── example-extension
   ├── messages, observability, presets, inworld, performance
   └── logger
```

- 31 módulos, **ningún ciclo** (DFS sobre el grafo de `project(':src:…')`).
- `core-api` no importa ningún módulo interno.
- `host` depende de `core-api`, `kernel`, `iflow`, `textformatter`, `transport`, `gtranslate`, `ltranslate`.
- `spigot-host` y `fabric-host` son los adaptadores de plataforma; el resto son hojas o capas de servicio.

### 2.2. Entry points

| Plataforma | Entry point | Evidencia |
|---|---|---|
| Spigot/Paper | `TextFormatterSuitePlugin.onEnable()` | `src/spigot-host/.../TextFormatterSuitePlugin.java:236` |
| Fabric | `TextFormatterSuiteMod.onInitialize()` | `src/fabric-host/.../TextFormatterSuiteMod.java:288` |
| Núcleo | `SuiteBootstrap.initialize()` | `src/host/.../SuiteBootstrap.java` |

### 2.3. Pipeline de un mensaje

```
AsyncPlayerChatEvent (off-main)
  └─► TextFormatterSuitePlugin.onChat()
        ├─ claim mode: cancel-event | clear-recipients
        ├─ resuelve canal + actor + dirección (initiator / others / console)
        └─► MessageDispatcher.dispatch(Message)
              ├─ resolveSourceLanguage()
              ├─ rate limit de emisión (VmPolicyEngine)
              ├─ expand() → lista de recipientes
              ├─ por recipiente (paralelo o secuencial):
              │    └─ SuiteHost.deliver() → router → traducción → TextFormatter → render
              └─ entrega: SpigotChatDelivery (vuelve al main)
```

El `SyncBus` se cablea en `spigot-host` (`DefaultSyncBus`, líneas 31/109/153) para el fan-out a sinks externos. La traducción y el render ocurren en el hilo async del evento; la entrega vuelve al main.

### 2.4. Clean / Hexagonal

- **Clean:** la regla de dependencia se cumple a nivel de módulo. `core-api` es el centro; los adaptadores están en los extremos (`spigot-host`, `fabric-host`, `sync-*`, `manager-*`).
- **Hexagonal:** hay puertos reales (`SyncSink`, `TranslatorProvider`, `ChatDelivery`, `ModuleLifecycle`) y adaptadores reales. No es solo nomenclatura.
- **Fugas:** `iflow` depende de `textformatter` y `logger`; es discutible, pero está justificado por el renderizado de plantillas dentro de la VM. `spigot-host` arrastra `tester` en *compile scope* (deuda reconocida en `src/README.md:337`).

**Puntuación arquitectónica:** alta. **Puntuación de ejecución/verificación:** baja.

---

## 3. Estado de las auditorías fuente

Leyenda: **CONFIRMADO** = reproducido con evidencia; **CORREGIDO** = el hecho existe pero el detalle de A1/A2 era inexacto; **RESUELTO** = ya corregido antes de esta auditoría (commit `71f2318`); **NO CONFIRMADO** = no se reproduce en el código actual.

| # | Afirmación de A1/A2 | Veredicto | Evidencia |
|---|---|---|---|
| 1 | `verification-metadata.xml` con hashes inventados | **CONFIRMADO** | `sha256("aopalliance:aopalliance:1.0") = 0e26e161…` = valor del fichero; `generate-verification-metadata.py:79` dice “NOT a real checksum” |
| 2 | Fichero en ruta equivocada y esquema que Gradle no entiende | **CONFIRMADO** | raíz usa `http://www.gradle.org/schema/verification-metadata`; el válido (`https://schema.gradle.org/dependency-verification`) solo existe en `gradle/verification-metadata.xml.bak` |
| 3 | `release.yml` compila `manager-api`/`manager-impl` | **CONFIRMADO** | `release.yml:59-60`; esos proyectos no existen (`settings.gradle:29-31`) |
| 4 | Build standalone roto | **CONFIRMADO** | `src/host/settings.gradle` solo fija `rootProject.name`; `build.gradle:21-29` usa `project(':src:…')` |
| 5 | `checkLocks` falso (faltan `iflow` y `logger`) | **CONFIRMADO** | 29 lockfiles en `src/` + raíz; faltan esos dos; la tarea solo avisa, no falla |
| 6 | `spotless`/`checkstyle`/`spotbugs` documentados pero inexistentes | **CONFIRMADO** | no hay plugins en los `build.gradle`; la wiki los invoca |
| 7 | `CONTRIBUTING.md` / `CODE_OF_CONDUCT.md` ausentes | **CONFIRMADO** | no existen en la raíz; el enlace del wiki ya se retiró en `71f2318` |
| 8 | `.gitattributes` ausente | **CONFIRMADO** | no existe |
| 9 | 29 vs 31 módulos en la documentación | **CONFIRMADO** | `README.md:11,29,151,418`; catálogo lista 29 y omite `logger` y `common-legacy` |
| 10 | Wiki 05 enseña un iFlow eliminado | **CONFIRMADO** | `docs/wiki/05-iFlow-Rules.md` documenta `DefaultRouter`, `Rule`, SpEL y `DefaultRouterTest`; el código es `VmInterpreter`/`.flow` |
| 11 | Colisión de numeración wiki | **CONFIRMADO** | `10-API-Reference.md` y `10-Module-Manager.md` |
| 12 | `docs/README.md` con placeholder | **CONFIRMADO** | línea 14: `<Falta mencionar rapidamente…>` |
| 13 | Basura en `ADR.md` | **CONFIRMADO** | `docs/workdir/ADR.md:183`: `(End of file - total 193 lines)` |
| 14 | `AUDITORIA.md` mal indexado en README | **CONFIRMADO** | `README.md:405` lo describe como TXF-ZIP-001..006; el fichero era la auditoría de iFlow |
| 15 | Tres cifras de tests de iFlow | **CORREGIDO** | README 264; `src/iflow/README.md` 383; XML actual **397** (`build/test-results`) |
| 16 | Estado de Fabric triple | **CONFIRMADO** | README “no probado”, PLAN “corriendo”, wiki “production-ready” |
| 17 | Comandos documentados inexistentes | **CORREGIDO** | `/suite update` **sí existe** (alias de `repo update`, `DynamicCommandRegistrar.java:165`); `/suite debug`, `/suite ext`, `/suite module enable/disable`, `module update` y `metrics reset` **no** |
| 18 | Dir de config Fabric mal | **CONFIRMADO** | `TextFormatterSuiteMod.java:455` usa `textformatter-suite`; README/wiki dicen `textformattersuite/` |
| 19 | `docs/prompts/contexto/proyecto.md` con referencias muertas | **CONFIRMADO** | menciona `PROMPT.md`, `docs/PLAN.md`, `suite/*`, `GCloud` |
| 20 | Duplicación divergente de release notes y ADR | **CONFIRMADO** | `RELEASE_NOTES.md` ≠ `docs/RELEASE_NOTES.md` ≠ `docs/wiki/11`; `ADR.md` ≠ `wiki/14` |
| 21 | `common-legacy` vacío y lockfile externo | **CORREGIDO** | tiene `src/main` pero **0 ficheros**; su lockfile fija `me.majhrs16:suite-core-api:2.1.0-SNAPSHOT` externo |
| 22 | `tester` en compile de `spigot-host` | **CONFIRMADO** | `src/spigot-host/build.gradle:47` |
| 23 | 16 módulos sin tests | **CORREGIDO** | 10 módulos sin `src/test` + `fabric-host` con árbol vacío = **11 sin tests Java**; `build`/`fixtures` no son módulos |
| 24 | 491 tests / 4 `@Disabled` | **CORREGIDO** | XML actuales: **505 tests, 0 fallos, 7 skipped** (solo 4 módulos con resultados); **5** `@Disabled` |
| 25 | `VelocitySinkTest` tautológico | **CONFIRMADO** | `assertTrue(config.secret.equals("test-secret"))` |
| 26 | `DiscordSink` borra el token antes de usarlo | **CONFIRMADO** | `start()` línea 71 hace `Arrays.fill`; `send()` línea 92 hace `new String(token)` → `Bot \0\0\0` |
| 27 | YAML inseguro | **CONFIRMADO** | `ConfigValidator.java:165,347,531,610`; `ExtensionContext.java:179,196` |
| 28 | Bypass SSRF por userinfo | **CORREGIDO** | el bypass real es `http://user:pass@host:8080/x` → `http://<ip>:pass@host:8080/x`; con `user@host` el rewrite sí elimina el userinfo (probado con Java) |
| 29 | `TransactionLog` escribe `null` | **CONFIRMADO** | `log()` usa `type/packageName/version/channel = null`; el lector hace `OperationType.valueOf("null")` y solo captura `IOException` |
| 30 | `char[]` inútil en Telegram | **CONFIRMADO** | `TelegramSink.java:33` construye la URL con `new String(token)` |
| 31 | Secretos con `String.equals` | **CONFIRMADO** | `HttpSink:224`, `WebSocketSyncSink:222,348`, `VelocitySink:494`, `DebugEndpoint:128` |
| 32 | `DebugEndpoint` loguea 8 caracteres del token | **CONFIRMADO** | `DebugEndpoint.java:94` |
| 33 | Path traversal en `AptInstaller` | **CONFIRMADO** | `tmpDir.resolve(name-version.zip)` con `name`/`version` remotos |
| 34 | Zip-bomb / dependencias sin límite | **CONFIRMADO** | recursión sin profundidad en `DependencyResolver`; `readAllBytes()` del manifiesto |
| 35 | Descarga sin tope en `RepositoryManager` | **CONFIRMADO** | `response.body().string()` |
| 36 | `waitForSinkProcessing` sin deadline | **CONFIRMADO** | recursión con `scheduler.schedule` cada 100 ms |
| 37 | Dedup que no refresca timestamp | **CORREGIDO** | cierto (`putIfAbsent`), pero hay limpieza por antigüedad y tope (`DefaultSyncBus:449-456`); es una ventana fija, no una fuga |
| 38 | `Logger.init()` publica `initialized` antes de `writer` | **CONFIRMADO** | `Logger.java:143-145` |
| 39 | `HttpSink` reutiliza executor apagado | **CONFIRMADO** | `stop()` hace `executor.shutdown()`; `start()` reutiliza el mismo objeto |
| 40 | `ExtensionManager` recursión infinita ante ciclos | **CONFIRMADO** | `enable()` recursivo sin conjunto de visitados |
| 41 | `ExecutionContextPool` check-then-act | **CONFIRMADO** | `acquire()` comprueba `closed`, hace `poll()` e incrementa sin lock |
| 42 | `SoftMMU.readByte` ignora privilegio | **NO CONFIRMADO** | `readByte(addr)` delega en `readByte(addr, PROGRAM)`; existe la sobrecarga con `Privilege` |
| 43 | Recursos sin cerrar | **CONFIRMADO** | `VelocitySink` crea un executor de métricas y no lo apaga; `CacheOptimizer` no tiene `stop()`; `HotspotDetector` asigna `this.logger = null` |
| 44 | `CommandExecutor.execute()` sin implementar | **CONFIRMADO** | `return false;` + TODO |
| 45 | `MessageCodec` sin validar | **CONFIRMADO** | `UUID.fromString` y `valueOf` sobre JSON de red |
| 46 | `maturity.py` premia el fichero falso | **CONFIRMADO** | `maturity.py:92`: 20 puntos por presencia |
| 47 | Rúbrica dependiente de `~/chat.log` | **CORREGIDO** | `extract_chat.py` sí lo usa, pero `judge.py` **no** (ver `judge.py:12`); la parte narrativa se materializa en `judge_scores.csv` |
| 48 | `.bak` versionados / 11 wrappers | **CORREGIDO** | 2 `.bak` (~598 KB) y **12** `gradle-wrapper.jar` versionados |
| 49 | `auditar-modulo` evalúa contra una especificación muerta | **NO CONFIRMADO** | `docs/prompts/diseno/manager-v2.md` **es** la especificación APT/dpkg vigente, no la del manager antiguo |
| 50 | 8 enlaces rotos | **RESUELTO** | `71f2318` los corrigió; el comprobador da **0** |

### 3.1. Correcciones ya aplicadas antes de esta auditoría

El commit `71f2318` (inmediatamente anterior) resolvió:

- Los 8 enlaces relativos rotos (ahora 0).
- La nomenclatura `manager-api`/`manager-impl` → `manager-spi`/`manager-core`/`manager-apt`.
- `coretranslator` fuera de las listas de módulos (borrado el 2026-09-28).
- `docs/PLAN.md` → `docs/workdir/PLAN.md` en `especificacion-readme.md`.
- Rutas `suite/` → `src/` en la wiki.
- Comandos de build inexistentes del web editor.
- El enlace a `CODE_OF_CONDUCT.md`.
- Los tres READMEs de `manager-*` restaurados como CodeGuide.

---

## 4. Hallazgos

### P0 — Críticos

#### PROJ-AUD-001 — La verificación de dependencias es ficticia y está inactiva
**Tipo:** Vulnerabilidad / Defecto de diseño · **Severidad:** Crítica · **Confianza:** Confirmado
**Ubicación:** `verification-metadata.xml` → `generate-verification-metadata.py:79`
**Descripción:** El fichero versionado contiene hashes del texto de la coordenada, no de los artefactos. Además usa el namespace antiguo (`http://www.gradle.org/schema/verification-metadata`) y está en la raíz, mientras Gradle lee `gradle/verification-metadata.xml`.
**Evidencia:**
```
sha256("aopalliance:aopalliance:1.0")
  = 0e26e161ee03141cfadfd4eec6285eccc621a4877722bfb09a4ec2958e4dcf57
  = sha256 del <artifact> en verification-metadata.xml
```
El fichero válido (con `origin="Generated by Gradle"` y el esquema correcto) solo existe como `gradle/verification-metadata.xml.bak`.
**Impacto:** la cadena de suministro no está verificada; el README y la rúbrica afirman lo contrario.
**Solución:** borrar el fichero falso, promover el `.bak` válido a `gradle/verification-metadata.xml`, añadir `--write-verification-metadata sha256` al flujo y hacer que CI falle si falta.

#### PROJ-AUD-002 — El release no puede ejecutarse
**Tipo:** Bug / Defecto de diseño · **Severidad:** Crítica · **Confianza:** Confirmado
**Ubicación:** `.github/workflows/release.yml:59-60`, `:33-38`, `:100`
**Descripción:** Compila `:src:manager-api` y `:src:manager-impl`, que no existen. Extrae la versión del tag pero **no la aplica** al build (`-Pversion` ausente), así que los JAR salen `2.1.0-SNAPSHOT`. Referencia `CHANGELOG.md`, que no existe.
**Impacto:** cualquier tag falla o publica artefactos con versión incorrecta.
**Solución:** sustituir por `manager-spi/core/apt`; propagar `version` desde el tag; crear el CHANGELOG o retirar la referencia; incluir `logger` y `common-legacy`.

#### PROJ-AUD-003 — `DiscordSink` borra su token antes de usarlo
**Tipo:** Bug · **Severidad:** Crítica · **Confianza:** Confirmado
**Ubicación:** `src/sync-discord/.../DiscordSink.java:69-71` y `:92-95`
**Descripción:** `start()` copia el token a un `String`, construye el gateway y hace `Arrays.fill(token, '\0')`. Después, `send()` hace `new String(token)` para el header REST: el token ya son ceros.
**Impacto:** todos los envíos REST salen con `Authorization: Bot \0\0\0`; el sink no publica.
**Solución:** no borrar el array hasta `stop()`; o guardar el token en un `Secret` que sobreviva al ciclo de vida del sink.

#### PROJ-AUD-004 — Deserialización YAML insegura
**Tipo:** Vulnerabilidad · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `ConfigValidator.java:165,347,531,610`; `ExtensionContext.java:179,196`
**Descripción:** seis `new Yaml()` sin `SafeConstructor`, mientras el resto del repo sí lo usa.
**Impacto:** un YAML de configuración/extensión puede instanciar clases arbitrarias (RCE según classpath).
**Solución:** `new Yaml(new SafeConstructor(new LoaderOptions()))` en los seis sitios; test de regresión con `!!javax.script.ScriptEngineManager`.

#### PROJ-AUD-005 — Bypass del pinning SSRF con userinfo que contiene `:`
**Tipo:** Vulnerabilidad · **Severidad:** Alta · **Confianza:** Confirmado (probado con Java)
**Ubicación:** `src/transport/.../HttpTransport.java:346`
**Descripción:** `replaceHostInUrl()` usa `://[^:/]+`. Con `http://user:pass@host:8080/x` solo consume `://user`, produciendo `http://<ip>:pass@host:8080/x`; el host vuelve a ser `host` y se resuelve de nuevo.
**Evidencia (Java 21):**
```
http://user:pass@host:8080/x
  → http://10.0.0.1:pass@host:8080/x
  → new URL(...).getHost() = host
```
**Impacto:** se puede saltar el bloqueo de IPs privadas.
**Solución:** rechazar `URL.getUserInfo() != null` antes de validar, o reconstruir la URL con `URI` en lugar de regex.

#### PROJ-AUD-006 — `TransactionLog` genera líneas ilegibles y la recuperación lanza excepción
**Tipo:** Bug · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `manager-core/.../TransactionLog.java:67-77` y `:94-109`
**Descripción:** `markCompleted/markFailed/markRolledBack` llaman a `log(..., null, null, null, null, ...)`; `String.format("%s", null)` escribe `null`. Al releer, `OperationType.valueOf(parts[1])` lanza `IllegalArgumentException`, que **no** está capturada (solo `IOException`).
**Impacto:** la recuperación de transacciones interrumpidas falla justo cuando más se necesita.
**Solución:** serializar con un marcador vacío y parsear con `Optional`/`try`, o hacer que los métodos de estado no exijan tipo/paquete.

#### PROJ-AUD-007 — Path traversal en la descarga de paquetes
**Tipo:** Vulnerabilidad · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `manager-apt/.../AptInstaller.java:184-187`
**Descripción:** `fileName = candidate.name() + "-" + candidate.version() + ".zip"` y `tmpDir.resolve(fileName)`. `name`/`version` vienen del índice remoto.
**Impacto:** un repositorio comprometido escribe fuera de `tmpDir`.
**Solución:** validar `[A-Za-z0-9._-]+`, normalizar y comprobar `dest.startsWith(tmpDir)`.

#### PROJ-AUD-008 — Consumo de recursos sin límites en el manager
**Tipo:** Vulnerabilidad · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `DependencyResolver.java` (recursión), `RepositoryManager.java:150`, `DependencyResolver.java:253`
**Descripción:** resolución de dependencias recursiva sin profundidad ni número máximo; `response.body().string()` sin tope; `readAllBytes()` del manifiesto de un ZIP remoto.
**Impacto:** DoS por índice enorme, cadena de dependencias infinita o ZIP malicioso.
**Solución:** límites configurables (profundidad, paquetes, bytes), `Content-Length` máximo y lectura en streaming.

#### PROJ-AUD-009 — CI no valida lo que dice validar
**Tipo:** Defecto de diseño · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `.github/workflows/ci.yml`
**Descripción:** el job `build-and-test` compila 27 módulos (omite `logger` y `common-legacy`); `dependency-check` ejecuta `dependencies --write-locks` con `continue-on-error: true` (no comprueba nada); no se ejecutan `checkLocks` ni `verifySchema`; sin `--parallel` ni configuration cache.
**Impacto:** un CI verde no implica que el repo esté sano.
**Solución:** job explícito `checkLocks` (que falle), `verifySchema`, `logger`/`common-legacy`, y `--parallel` acotado.

#### PROJ-AUD-010 — El README afirma “probado/completo” donde no lo está
**Tipo:** Deuda técnica / Documentación · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `README.md:29-51,159,166,178,183,194`
**Descripción:** `messages`, `observability`, `presets`, `sync-bus` y `sync-websocket` aparecen como “✅ Probado” sin `src/test`. “Dependency Verification ✅ Completa” describe el fichero falso. “Security Sprint 3 ✅ Completa” coexiste con PROJ-AUD-003/004/005.
**Impacto:** la documentación pública induce a confiar en garantías inexistentes.
**Solución:** sustituir por estados verificables (“compila”, “sin tests”, “verificación inactiva”) y enlazar al test que lo demuestra.

### P1 — Altos

#### PROJ-AUD-011 — Doble sistema de build (11 standalone rotos)
**Tipo:** Defecto de diseño · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `src/*/settings.gradle`, `src/*/gradle/wrapper/*`
**Descripción:** 11 módulos tienen wrapper y `settings.gradle` propios; su `build.gradle` referencia `project(':src:…')`, que no existe en ese build. La raíz usa Gradle 8.10 y los módulos 8.13.
**Impacto:** `cd src/<m> && ./gradlew …` falla; duplica 12 `gradle-wrapper.jar` y confunde a cualquier agente.
**Solución:** eliminar los standalone o convertirlos en builds funcionales; alinear el wrapper.

#### PROJ-AUD-012 — Versión duplicada en 30 módulos
**Tipo:** Deuda técnica · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `src/*/build.gradle`
**Descripción:** 30 módulos fijan `2.1.0-SNAPSHOT`; `example-extension` fija `1.0.0`. El tag del release no se propaga.
**Impacto:** subir versión exige 30 ediciones; los JAR publicados mienten.
**Solución:** `version = rootProject.version` y `-Pversion` desde el workflow.

#### PROJ-AUD-013 — `checkLocks` no bloquea y faltan dos lockfiles
**Tipo:** Deuda técnica · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `build.gradle:178-214`
**Descripción:** la tarea solo imprime `present/missing`; no falla. Faltan `iflow` y `logger`. El README dice “root + 28” cuando hay root + 29 (30).
**Solución:** `if (!missing.isEmpty()) throw new GradleException(...)`; generar los dos lockfiles; corregir el recuento.

#### PROJ-AUD-014 — Deriva documental extensa
**Tipo:** Documentación · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `README.md`, `docs/wiki/*`, `docs/workdir/PLAN.md`
**Descripción:** 29 vs 31 módulos; wiki 05 describe el iFlow eliminado; comandos `/suite debug`, `/suite ext`, `module enable/disable`, `module update`, `metrics reset` inexistentes; tres estados de Fabric; cifras de tests 264/383/397; config dir `textformattersuite` vs `textformatter-suite`; `AUDITORIA.md` se reutiliza para auditorías distintas (TXF-ZIP → iFlow → esta) y el README sigue apuntando a TXF-ZIP; `docs/README.md` con placeholder; `ADR.md:183` con basura; colisión `10-*.md`; `RELEASE_NOTES` y ADR triplicados.
**Impacto:** cada lector (humano o agente) recibe una historia distinta.
**Solución:** una sola fuente por tema, un fichero por auditoría (`AUDITORIA-PROYECTO.md`, `AUDITORIA-IFLOW.md`, …), estados verificables y un comprobador de enlaces en CI.

> **Nota:** los 8 enlaces rotos concretos de A1/A2 ya se corrigieron en `71f2318`; esta sección cubre la deriva que queda.

#### PROJ-AUD-015 — Tests ausentes o engañosos
**Tipo:** Deuda técnica · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `src/*/src/test`
**Descripción:** 11 módulos sin tests Java (`common-legacy`, `example-extension`, `extension-api`, `inworld`, `messages`, `observability`, `performance`, `presets`, `sync-bus`, `sync-websocket`, `fabric-host`). 5 `@Disabled`, 7 skipped, 6 `Thread.sleep`, rutas `/tmp` compartidas. `VelocitySinkTest` es tautológico. Los tests de `logger` dependen de `assumeTrue(DebugConfig.DEBUG)`.
**Impacto:** un agente no puede verificar su trabajo; el paralelismo traslada todo el coste a revisión humana.
**Solución:** tests de contrato por módulo antes de paralelizar; eliminar el test tautológico; aislar rutas con `@TempDir`.

#### PROJ-AUD-016 — Concurrencia: cuatro puntos frágiles
**Tipo:** Bug / Defecto de diseño · **Severidad:** Alta · **Confianza:** Confirmado / Probable
**Ubicación:** `DefaultSyncBus:286-296`, `Logger:143-145`, `HttpSink:114/127`, `ExtensionManager:115-122`, `ExecutionContextPool:49-66`
**Descripción:**
- `waitForSinkProcessing` reprograma cada 100 ms sin deadline → un sink atascado cuelga el futuro.
- `Logger.init()` marca `initialized=true` antes de asignar `writer` → NPE en carrera.
- `HttpSink.stop()` apaga el executor; `start()` reutiliza el mismo objeto → rechazos tras reinicio.
- `ExtensionManager.enable()` recursivo sin visitados → `StackOverflowError` con ciclos.
- `ExecutionContextPool.acquire()` hace check-then-act frente a `close()`.
**Solución:** `orTimeout`/deadline, publicar `writer` antes del flag, recrear el executor en `start()`, conjunto de visitados, lock o CAS en el pool.

#### PROJ-AUD-017 — Recursos sin cerrar
**Tipo:** Bug · **Severidad:** Media-Alta · **Confianza:** Confirmado
**Ubicación:** `VelocitySink.java:238`, `CacheOptimizer.java:35`, `HotspotDetector.java:56`
**Descripción:** el executor de métricas de `VelocitySink` se crea inline y nunca se apaga; `CacheOptimizer` no tiene `stop()`; `HotspotDetector` descarta su logger (`this.logger = null`).
**Impacto:** hilos huérfanos tras recargas; diagnóstico imposible.
**Solución:** un único `ScheduledExecutorService` por sink con `shutdown()` en `stop()`; conservar el logger.

#### PROJ-AUD-018 — Entrada de red sin validar
**Tipo:** Bug · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** `MessageCodec` (transport, sync-http, sync-tcpudp)
**Descripción:** `UUID.fromString`, `MessageType.valueOf`, `ActorKind.valueOf` sobre JSON remoto sin `try`.
**Impacto:** un mensaje malformado tumba el hilo de recepción.
**Solución:** parseo defensivo con error tipado y descarte del mensaje.

#### PROJ-AUD-019 — Comparación de secretos no constante y fuga en logs
**Tipo:** Vulnerabilidad · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** `HttpSink:224`, `WebSocketSyncSink:222/348`, `VelocitySink:494`, `DebugEndpoint:94/128`
**Descripción:** `String.equals` para tokens (timing) y log de los 8 primeros caracteres del token de debug.
**Solución:** `MessageDigest.isEqual` sobre bytes; no loguear nunca el token.

#### PROJ-AUD-020 — `CommandExecutor.execute()` no implementado
**Tipo:** Bug funcional · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** `src/host/.../CommandExecutor.java:111-114`
**Descripción:** devuelve `false` con un TODO; las acciones de `commands.yml` son no-ops.
**Solución:** implementar o retirar la funcionalidad y documentarlo.

### P2 — Medios

#### PROJ-AUD-021 — La automedición premia el engaño
**Tipo:** Defecto de diseño · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** `tools/project-metrics/maturity.py:92`
**Descripción:** 20 puntos por la presencia de `verification-metadata.xml`, sin validar contenido ni ruta. `extract_chat.py` depende de `~/chat.log` (externo al repo) para la capa narrativa; `judge.py` ya no lo usa y materializa en `judge_scores.csv`.
**Solución:** validar el XML (namespace, hashes, ruta) antes de puntuar; marcar la capa narrativa como manual y no reproducible sin `chat.log`.

#### PROJ-AUD-022 — Higiene de lo versionado
**Tipo:** Deuda técnica · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** raíz, `gradle/`, `src/*/gradle/wrapper/`
**Descripción:** 2 `.bak` versionados (`verification-metadata.xml.bak`, `gradle/verification-metadata.xml.bak`, ~598 KB), 12 `gradle-wrapper.jar` (11 módulos + raíz), el `verification-metadata.xml` falso (160 KB), `src/web-editor/prototype.html` (74 KB sin referencias) e `icon.png` de raíz sin referencia. No hay `.gitattributes`.
**Solución:** borrar `.bak`, un solo wrapper, `.gitattributes` para EOL, mover o referenciar `icon.png` y `prototype.html`.

#### PROJ-AUD-023 — `common-legacy` vacío pero publicable
**Tipo:** Deuda técnica · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** `src/common-legacy`
**Descripción:** 0 ficheros bajo `src/main`; su `gradle.lockfile` fija `me.majhrs16:suite-core-api:2.1.0-SNAPSHOT` externo aunque el build usa `project(':src:core-api')`.
**Solución:** retirarlo del build o darle contenido y lockfile coherente.

#### PROJ-AUD-024 — `auditar-modulo` solo cubre Manager
**Tipo:** Deuda técnica · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** `.opencode/skills/auditar-modulo/`
**Descripción:** solo hay `references/manager.md`; el resto de módulos no tiene focos.
**Solución:** añadir referencias por módulo o declarar explícitamente el alcance.

> Corrección a A2: la especificación `docs/prompts/diseno/manager-v2.md` **sí** es la vigente (APT/dpkg); el problema es la cobertura, no la especificación.

#### PROJ-AUD-025 — Falta el bucle de desarrollo y `AGENTS.md`
**Tipo:** Deuda técnica · **Severidad:** Media · **Confianza:** Confirmado
**Descripción:** hay skills de auditar/documentar, pero ninguna de “implementar → test → commit”; no existe `AGENTS.md` con comandos, `JAVA_HOME`, `--offline` y rutas prohibidas.
**Impacto:** el plan multi-agente no tiene contrato operativo.
**Solución:** `AGENTS.md` raíz + skill de bucle de desarrollo.

### 4.1. Estado material del repositorio (lo que no es código)

Esto es lo que las auditorías de A1/A2 no miraban: el repositorio físico. No es un problema de estilo; cambia lo que un agente (o una búsqueda, o una medición) ve cuando trabaja. **Las cifras de esta sección son el estado previo a la limpieza de la sección 10.**

| Métrica | Valor | Comentario |
|---|---|---|
| Tamaño del árbol de trabajo | **361 MB** | incluye artefactos ignorados |
| Tamaño de `.git` | 34 MB | historia de 267 commits |
| Ficheros versionados | **672** (7,8 MB) | el “repo real” |
| Entradas ignoradas | **73** | `git clean -ndX` |
| Directorios `build/` | **86** | raíz + `src/` + 31 módulos (más anidados) |
| Peso de `build/` | ~74 MB | 12 MB raíz + ~62 MB módulos |
| `.gradle/` local | **~109 MB** | 106 MB raíz + 3,1 MB módulos |
| `node_modules` | **114 MB** | 41 MB web-editor + 73 MB iflow-compiler |
| `.idea/` | 36 KB | IDE local, ignorado |
| `hs_err_pid*.log` | **10** | OOM/crash de JVM |
| `replay_pid*.log` | **4** | volcados de JIT |
| `docs/workdir/vm-logs` | 9 ficheros | capturas locales de iFlow |
| `tools/project-metrics/data` | **182 ficheros / 16 MB** | **no versionado** |
| `.bak` versionados | 2 (~598 KB) | verificación duplicada |
| `gradle-wrapper.jar` versionados | **12** | raíz + 11 módulos |
| `.gitignore` anidados | **14** | 10 de módulo + herramientas/IDE; repiten `.gradle/` y `build/` |
| `prototype.html` | 74 KB | trackeado, sin referencias |

**Lo positivo:** el working tree solo tiene **un** cambio (`docs/workdir/AUDITORIA.md`, este informe) y **0 ficheros no ignorados sin trackear**. La disciplina de commits no es el problema; el problema es lo que se acumula alrededor.

#### PROJ-AUD-026 — 319 MB de artefactos en el árbol de trabajo
**Tipo:** Deuda técnica / Higiene · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** `build/`, `src/*/build/`, `src/*/.gradle/`, `src/web-editor/node_modules/`, `tools/iflow-compiler/node_modules/`, `.idea/`
**Descripción:** 361 MB en disco frente a 7,8 MB versionados. 86 directorios `build/` (74 MB), 114 MB de `node_modules`, 109 MB de `.gradle/` local. Todo está en `.gitignore`, pero sigue en el árbol.
**Impacto:** búsquedas, `grep`, índices de agentes y mediciones de tamaño trabajan sobre basura; los builds “limpios” no lo son; un `git clean -fdx` borra 319 MB (incluida la evidencia no versionada) que hay que reconstruir.
**Solución:** `git clean -Xfd` antes de medir/auditar; un script `clean` explícito; no volcar `build/` dentro de `src/`; documentar en `AGENTS.md` que el árbol no es el repo.

#### PROJ-AUD-027 — La evidencia de la rúbrica no está en el repositorio
**Tipo:** Defecto de diseño / Reproducibilidad · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** `tools/project-metrics/data/` (ignorado por `tools/project-metrics/.gitignore`)
**Descripción:** 182 ficheros y 16 MB de CSV, JSONL y paquetes de evidencia (`data/evidence/*.md`) están fuera de git. Lo único versionado es `judge_scores.csv`. `judge.py` consume esos paquetes; `run_all.sh` los regenera, pero `extract_chat.py` necesita `~/chat.log`, que tampoco está en el repo. Los 9 PNG de `docs/metrics` sí están versionados, pero los CSV que los generan no.
**Impacto:** un tercero no puede reconstruir el juicio con el repo solo; el veredicto canónico existe, la evidencia que lo sostiene no.
**Solución:** versionar `data/evidence/` y los CSV de entrada (o un subconjunto estable), o declarar explícitamente que la rúbrica no es reproducible sin `chat.log` y bajar su peso en la puntuación.

#### PROJ-AUD-028 — Las copias del schema divergen y nadie lo comprueba
**Tipo:** Bug / Defecto de diseño · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** `src/host/src/main/resources/web-editor-schema/` vs `src/web-editor/`
**Descripción:** `ConfigSchemaGenerator` escribe en los recursos del host, pero el web editor mantiene copias manuales. Diff actual: `paths.json` (7 líneas), `paths.js` (683 líneas), `model.js` (687 líneas). No son idénticos.
**Impacto:** el “single-source” del schema v2.2 es falso; el editor y el host pueden validar cosas distintas.
**Solución:** generar ambas salidas en el build y no versionar las copias, o un test de igualdad en CI que falle si divergen.

#### PROJ-AUD-029 — Restos estructurales que confunden el mapa
**Tipo:** Deuda técnica · **Severidad:** Baja-Media · **Confianza:** Confirmado
**Ubicación:** `src/build/`, `src/fixtures/`, `src/common-legacy/src/`, `src/web-editor/prototype.html`
**Descripción:** hay un `build/` de Gradle dentro de `src/`; `src/fixtures` no es un módulo (no tiene `build.gradle`) pero vive entre módulos; `common-legacy/src` existe vacío; `tmp/` está vacío en la raíz; `prototype.html` (74 KB) está trackeado y no lo referencia nadie. Además, los 31 módulos tienen `build/` (32 contando `src/build`) y 12 conservan `.gradle/` local por el doble build.
**Impacto:** el árbol de directorios miente sobre qué es un módulo; los agentes deducen estructura de los nombres.
**Solución:** mover `fixtures` a `src/test-fixtures` o `tools/`; sacar la salida de `src/`; borrar `prototype.html` o enlazarlo; retirar `common-legacy` o darle contenido.

#### PROJ-AUD-030 — Política de ignore fragmentada y sin `.gitattributes`
**Tipo:** Deuda técnica · **Severidad:** Baja-Media · **Confianza:** Confirmado
**Ubicación:** `.gitignore` raíz + 11 `.gitignore` de módulo + `tools/*/.gitignore`
**Descripción:** 10 módulos repiten `.gradle/` y `build/` (uno sin salto de línea final), y hay 4 `.gitignore` más en herramientas/IDE; el `.gitignore` raíz no cubre `node_modules/` ni `dist/` de forma genérica (solo `src/web-editor/node_modules/`); `tools/iflow-compiler/.gitignore` y `tools/project-metrics/.gitignore` lo suplen por separado. No hay `.gitattributes`: los EOL dependen del `core.autocrlf` de cada máquina.
**Impacto:** reglas contradictorias; artefactos que se cuelan o se ignoran de más; diffs de línea completa en Windows.
**Solución:** un `.gitignore` raíz con `**/build/`, `**/.gradle/`, `**/node_modules/`, `**/dist/`, `hs_err_pid*`, `replay_pid*`; borrar los 10 duplicados de módulo; `.gitattributes` con `* text=auto` y binarios explícitos.

#### PROJ-AUD-034 — Falta documentación de comunidad y el CHANGELOG que el release referencia
**Tipo:** Deuda técnica / Proceso · **Severidad:** Baja-Media · **Confianza:** Confirmado
**Ubicación:** raíz, `.github/`
**Descripción:** hay `LICENSE` (GPLv3), pero no `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `CODEOWNERS`, `CHANGELOG.md` ni plantillas de issue/PR. Solo existen dos workflows (`ci.yml`, `release.yml`), y `release.yml` referencia `CHANGELOG.md`.
**Impacto:** no hay canal para reportar vulnerabilidades ni proceso de contribución; el release apunta a un fichero ausente.
**Solución:** añadir `SECURITY.md` (reporte privado), `CONTRIBUTING.md`, `CHANGELOG.md` generado desde tags/commits, `CODEOWNERS` y plantillas.

### 4.2. Historial

| Métrica | Valor |
|---|---|
| Commits | **267** (255 en los últimos 60 días) |
| Merges | **0** (historia lineal) |
| Tags | **0** |
| Autores | 255 `majhrs16-official`, 12 `Majhrs16` (mismo email) |
| Commit más grande | `44d3a30` (2026-09-24): **3.181 ficheros, +584.213 líneas** |
| Borrado más grande | `e07e2d9` (2026-09-26): **2.858 ficheros, −572.111 líneas** |
| Blobs >600 KB en la historia | `prettier`, `typescript`, `graphemer` de `web-editor/node_modules` |

#### PROJ-AUD-031 — `node_modules` commiteado y borrado, pero vivo en la historia
**Tipo:** Deuda técnica / Higiene · **Severidad:** Media · **Confianza:** Confirmado
**Ubicación:** commits `44d3a30` y `e07e2d9`
**Descripción:** el 2026-09-24 se añadieron ~2.800 ficheros de `src/web-editor/node_modules` (+584.213 líneas); dos días después se borraron (−572.111). Los blobs siguen en `.git` (pack de 19 MB; `prettier/plugins/flow.js` >1 MB, `typescript.js` 900 KB, etc.).
**Impacto:** el clon pesa 34 MB en `.git` para 7,8 MB de contenido actual; la historia no se puede limpiar con `git gc` porque los objetos son alcanzables.
**Solución:** no urgente; si se quiere reducir, `git filter-repo --path src/web-editor/node_modules --invert-paths` (reescribe la historia y exige force-push coordinado). Prevenir con hook/pre-commit que rechace `node_modules`.

#### PROJ-AUD-032 — Cero tags: el release nunca se ha ejecutado
**Tipo:** Defecto de diseño / Proceso · **Severidad:** Alta · **Confianza:** Confirmado
**Ubicación:** `git tag` (vacío); `.github/workflows/release.yml` (trigger por tag)
**Descripción:** no hay ni un tag en 267 commits. El workflow de release solo se dispara con tags, así que nunca ha corrido. Esto refuerza PROJ-AUD-002: el release no solo está roto, es que **nunca se ha probado**.
**Impacto:** no hay artefactos publicados ni evidencia de que el pipeline funcione.
**Solución:** crear un tag de prueba en una rama y ejecutar el workflow; corregir 002 antes de taggear.

#### PROJ-AUD-033 — Identidad de autor inconsistente
**Tipo:** Deuda técnica / Proceso · **Severidad:** Baja · **Confianza:** Confirmado
**Ubicación:** historial de git
**Descripción:** 255 commits con `majhrs16-official <majhrs16@hotmail.com>` y 12 con `Majhrs16 <majhrs16@hotmail.com>`. El email coincide; el nombre no.
**Impacto:** métricas por autor y atribución inconsistentes.
**Solución:** fijar `user.name`/`user.email` en `AGENTS.md` y en la configuración local.

---

## 5. Viabilidad del plan multi-agente (A1)

A1 concluye 1,3–1,8×, no 4×. La evidencia lo respalda:

- **Hub compartido:** `core-api` y `host` obligan a recompilar ~20 módulos.
- **Archivos únicos calientes:** `PLAN.md` (40 commits/60 días), `settings.gradle` (23/60), READMEs y ADR.
- **Verificación inexistente en 11 módulos:** sin tests, un agente no puede cerrar tareas.
- **Memoria:** 7,5 GiB, 8 cores, OOM documentados (`hs_err_pid*.log`, 3 GiB libres).
- **Tests no herméticos:** `/tmp` compartido, sleeps, `@Disabled` — hostiles a ejecución paralela.

**Recomendación:** 1 integrador + 2–3 workers sobre módulos hoja con tests añadidos; `core-api`, `host`, `iflow` y docs globales serializados. Semáforo de 2 builds concurrentes. No 4 escritores sobre el mismo hub.

---

## 6. Evaluación arquitectónica

| Área | Puntuación /10 | Estado | Problemas principales |
|---|---|---|---|
| Código | 5,5 | Funcional con bugs localizados | token Discord, `TransactionLog`, concurrencia |
| Arquitectura | 8,0 | Sólida | hub `core-api`/`host`, acoplamiento de `iflow` |
| Clean Architecture | 7,5 | Real a nivel de módulo | `tester` en compile, fugas puntuales |
| Hexagonal | 7,0 | Puertos/adaptadores reales | adaptadores con lógica de dominio |
| Modularidad | 7,5 | 31 módulos sin ciclos | builds standalone rotos, versión duplicada |
| Separación de responsabilidades | 6,5 | Buena en general | `Gradle`/docs/manager con responsabilidades mezcladas |
| Legibilidad | 6,5 | Nombres claros | deriva documental, doble build |
| Features | 7,0 | Amplias | estados “probado” falsos |
| Personalización | 7,0 | Config + VM + presets | límites no siempre justificados |
| Seguridad | 4,0 | Débil | PROJ-AUD-001/003/004/005/007/008/019 |
| Rendimiento | 6,0 | Sin benchmarks | allocations, locks, `readAllBytes` |
| Escalabilidad | 5,0 | No demostrada | sin benchmarks; tests no herméticos |
| Concurrencia | 4,5 | Frágil | PROJ-AUD-016/017 |
| Testing | 4,0 | Parcial y engañoso | 11 módulos sin tests, tautologías |
| Mantenibilidad | 5,5 | Arquitectura ayuda | documentación y build duplicados |
| Documentación | 4,5 | Extensa pero divergente | PROJ-AUD-014 |
| Higiene del repositorio | 3,0 | Sucio | 361 MB de árbol, 86 `build/`, 109 MB `.gradle`, 16 MB de evidencia fuera de git, `node_modules` en la historia |

---

## 7. Potencial

- **Teórico:** alto (8/10). La separación por módulos y los SPI permiten traducción, formateo, routing y sync multi-plataforma.
- **Arquitectónico:** 7/10. La base es reutilizable; el freno es la verificación, no el diseño.
- **Alcanzado:** ~55–60 %. Las features existen; las garantías (tests, supply chain, release, docs) no.

---

## 8. Roadmap priorizado

### P0 — Inmediato
| # | Acción | Esfuerzo | Dependencia |
|---|---|---|---|
| 1 | Reemplazar `verification-metadata.xml` por el `.bak` válido en `gradle/` y borrar el falso | 1 h | — |
| 2 | Arreglar `release.yml` (`manager-*`, versión, CHANGELOG) | 2 h | 1 |
| 3 | Corregir `DiscordSink` (no borrar el token hasta `stop()`) | 30 min | — |
| 4 | `SafeConstructor` en los 6 YAML | 1 h | — |
| 5 | Rechazar `userinfo` en `HttpTransport` | 1 h | — |
| 6 | `TransactionLog`: no escribir `null` / parseo defensivo | 2 h | — |
| 7 | Validar `name`/`version` en `AptInstaller` + límites de descarga | 3 h | — |
| 8 | Honestizar README (quitar “✅ Probado/Completa” falsos) | 2 h | — |

### P1 — Alto
| # | Acción | Esfuerzo |
|---|---|---|
| 9 | CI: `checkLocks` bloqueante, `verifySchema`, `logger`/`common-legacy` | 4 h |
| 10 | Lockfiles de `iflow` y `logger` | 1 h |
| 11 | Tests de contrato para los 11 módulos sin tests | 2–3 días |
| 12 | Arreglar concurrencia (PROJ-AUD-016) y recursos (017) | 2 días |
| 13 | Unificar build (eliminar standalone o arreglarlos) | 1 día |
| 14 | Versión única (`rootProject.version` + `-Pversion`) | 3 h |
| 15 | Reparar wiki 05, comandos, config dir y estados de Fabric | 1 día |

### P2 — Medio
| # | Acción | Esfuerzo |
|---|---|---|
| 16 | Consolidar `RELEASE_NOTES` y ADR; resolver colisión `10-*`; placeholder; `ADR:183` | 1 día |
| 17 | Limpiar 319 MB: `git clean -Xfd` excluyendo `tools/project-metrics/data`, `build/` fuera de `src/`, crash logs, `.idea` | 1 h |
| 18 | `.gitignore` único (raíz) + `.gitattributes`; borrar los 10 duplicados de módulo | 2 h |
| 19 | Versionar `tools/project-metrics/data/evidence` o declararla efímera | 3 h |
| 20 | Schema single-source: generar en build + test de igualdad en CI | 4 h |
| 21 | `common-legacy`: retirar o completar | 2 h |
| 22 | Métricas: validar el XML antes de puntuar; documentar `chat.log` como externo | 3 h |
| 23 | `AGENTS.md` + skill de bucle de desarrollo | 4 h |

### P3 — Bajo
| # | Acción | Esfuerzo |
|---|---|---|
| 24 | `prototype.html`, `icon.png`, `src/fixtures`: referenciar, mover o eliminar | 1 h |
| 25 | `auditar-modulo`: referencias por módulo | 1 día |
| 26 | Benchmarks reales de throughput/latencia | 1 semana |

---

## 9. Conclusión (14 preguntas)

1. **¿Qué tan bueno es realmente?** Un proyecto con arquitectura por encima de la media y ejecución/verificación/higiene por debajo. ~5,5/10 global.
2. **¿Qué está excepcionalmente bien diseñado?** La separación `core-api`/`kernel`/`host`/adaptadores y el grafo sin ciclos.
3. **¿Qué es mediocre?** La disciplina de verificación y la higiene: tests, supply chain, release, documentación y un árbol de 361 MB.
4. **¿Peores defectos?** La verificación ficticia (PROJ-AUD-001) y el release roto (002); después, el token de Discord, el YAML inseguro y los 319 MB de artefactos que contaminan cualquier medición.
5. **¿Mayor fortaleza arquitectónica?** `core-api` como contrato único y los SPI que permiten plataformas y sinks intercambiables.
6. **¿Mayor riesgo futuro?** La deriva documental y la falsa sensación de seguridad: cada nueva feature se apoya en garantías que no existen.
7. **¿Mantenibilidad?** Media. La modularidad ayuda; el doble build, la versión duplicada y los docs divergentes la frenan.
8. **¿Extensibilidad?** Alta en teoría (SPI, extensiones, presets); limitada por la falta de tests de contrato.
9. **¿Multiplataforma?** Buena: Spigot y Fabric comparten el núcleo; Fabric está menos validado.
10. **¿Red grande?** No demostrado. No hay benchmarks; los tests no son herméticos y hay puntos de concurrencia frágiles.
11. **¿Qué falta para escala network-level?** Benchmarks, backpressure medido, límites de recursos, tests de carga y arreglar PROJ-AUD-016/017.
12. **¿Qué no debería tocarse?** El grafo de módulos, `core-api` y los SPI; funcionan y son la base.
13. **¿Qué hacer primero?** P0: supply chain, release, Discord, YAML, SSRF, TransactionLog, path traversal y honestidad del README. Después, limpiar el árbol (`git clean -Xfd`) para que las mediciones sean sobre el repo, no sobre `build/`.
14. **¿Porcentaje técnicamente maduro?** ~50–55 %. La arquitectura está; la red de seguridad y la higiene no.

---

## 10. Remediación aplicada tras la auditoría

Estas acciones se ejecutaron después de cerrar el diagnóstico. No cambian los hallazgos: corrigen la parte material y dejan constancia de lo que sigue pendiente.

| Acción | Resultado |
|---|---|
| Promover `gradle/verification-metadata.xml.bak` | pasa a `gradle/verification-metadata.xml`, con el esquema correcto y SHA-256 reales |
| Regenerar con `--write-verification-metadata sha256` | **626 componentes**; `:src:logger:compileJava --offline` pasa |
| Borrar los ficheros falsos | `verification-metadata.xml` y su `.bak` de la raíz fuera del árbol |
| Unificar reglas de ignore | `.gitignore` raíz + `.gitattributes`; **10** `.gitignore` de módulo retirados |
| Limpiar artefactos regenerables | **361 MB → 177 MB**; se conservan `data/`, `node_modules/`, `.idea/` y `vm-logs/` |
| No tocado | historia (267 commits, 0 tags), `prototype.html`, los 12 wrappers, `common-legacy`, `node_modules` históricos |

### 10.1. P0 de código corregidos

| ID | Corrección | Evidencia |
|---|---|---|
| PROJ-AUD-003 | `DiscordSink` conserva el token hasta `stop()`; `send()` falla si el sink está detenido en vez de enviar ceros | `sync-discord`: 8/8 |
| PROJ-AUD-004 | `SafeConstructor` en las 4 cargas de `ConfigValidator` y en `ExtensionContext` | `host`: 52/52; `extension-api` compila |
| PROJ-AUD-005 | `HttpTransport` rechaza `userinfo` antes de validar o abrir la conexión | `transport`: 1/1 |
| PROJ-AUD-006 | `TransactionLog` serializa nulos como vacío, sanea separadores y omite líneas corruptas sin perder las válidas | `manager-core`: 19/19 |
| PROJ-AUD-007 | `AptInstaller` valida nombre/versión y normaliza el destino contra el directorio temporal | `manager-apt`: 13/13 |
| PROJ-AUD-008 | índices ≤ 8 MiB, manifiestos ≤ 2 MiB, paquetes ≤ 512 MiB, profundidad ≤ 64 y ≤ 1024 paquetes resueltos | `manager-apt`: 13/13 |

Los límites de profundidad y de manifiesto no tienen un test de comportamiento dedicado; quedan cubiertos por compilación y revisión. El resto sí tiene prueba directa.

**Verificación:** 93 tests en los módulos tocados, 0 fallos, 0 errores, con `--offline`, `--no-daemon` y `JAVA_HOME=/opt/javac/x64/21`. `extension-api` no tenía tests en esa fase; ahora tiene 6 (ver 10.3).

### 10.2. P0 de release, CI y README corregidos

| ID | Corrección | Evidencia |
|---|---|---|
| PROJ-AUD-002 | `release.yml` usa `manager-spi/core/apt`, incluye `logger` y `common-legacy`, aplica `-Pversion` del tag, fija `BUILD_TIMESTAMP`, genera notas de release y ya no referencia `CHANGELOG.md`; publica Spigot y Fabric | `build.gradle` propaga la versión; `:src:core-api:properties -Pversion=9.9.9` devuelve `9.9.9` |
| PROJ-AUD-009 | CI construye `logger` y `common-legacy`, ejecuta `checkLocks` bloqueante y `:src:host:verifySchema`, y sustituye el job no-op por verificación estricta de metadata | `ci.yml`; `checkLocks` falla si falta un lockfile |
| PROJ-AUD-012 | Eliminadas 30 overrides locales de `version = '2.1.0-SNAPSHOT'`; solo `example-extension` conserva su `1.0.0` | `grep '^version' src/*/build.gradle` |
| PROJ-AUD-013 | Añadidos `src/logger/gradle.lockfile` y `src/iflow/gradle.lockfile`; total **32** lockfiles (root + 31 subproyectos), 0 missing | `./gradlew checkLocks` |
| PROJ-AUD-028 | Regenerado el schema y sincronizadas las copias de `src/host/resources` y `src/web-editor`; `paths.json` es semánticamente idéntico | `./gradlew :src:host:verifySchema` |
| PROJ-AUD-010 | README corregido: 31 módulos, estados reales, recuento de tests sincronizado (766), enlace histórico de iFlow, auditoría actual y comandos existentes; sin “CI verde” falso | `README.md`; 0 enlaces relativos rotos |
| Web editor | `Suite.utils.esc` escapa `& < > " '`; el guard `max-steps` limita el total de pasos, no solo la profundidad; integración y unitarios verdes | `npm run check` (108 tests JS) y `npm run test:integration` |

**Verificación (P0, focalizada):** 93 tests en los módulos de código tocados, 0 fallos, 0 errores, con `--offline`, `--no-daemon` y `JAVA_HOME=/opt/javac/x64/21`; `checkLocks` 32/32; `:src:host:verifySchema`; `:src:logger:compileJava` y `:src:iflow:compileJava` con `--dependency-verification=strict`; web editor `npm run check` (108 tests JS) y `npm run test:integration`.

**Decisión del proyecto (2026-10-20):** la historia de git **no se reescribe**. Los commits antiguos con otro nombre comparten email con `majhrs16-official` y GitHub ya los atribuye a la misma cuenta; reescribirlos cambiaría todos los hashes sin mejorar la atribución.

**Decisión del proyecto (2026-10-20):** **no se publicarán releases ni tags** mientras el proyecto siga en desarrollo. `release.yml` queda corregido y reproducible para el día en que se decida publicar, pero no se ejecutará ahora; `PLAN.md` registra la decisión. El primer run de CI (#65) se ejecutó y detectó metadata de verificación incompleta para un checkout limpio; se añadieron las entradas faltantes y el run #66, sobre `acd7b25`, completó los cuatro jobs (`build-and-test`, `supply-chain`, `web-editor`, `javadoc`) en verde.

**Pendiente de remediación:** deuda P1 restante (editor web inestable, `CHANGELOG` mantenido a mano, tests de `fabric-host`, `common-legacy` sin fuentes, etc.). El release y el CI ya no tienen los defectos P0 descritos.

### 10.3. Deuda P1 remediada (parcial, 2026-10-20)

| Área | Cambio | Evidencia |
|---|---|---|
| Comunidad | `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`, `.github/CODEOWNERS`, plantillas de issue y PR | ficheros nuevos; README los enlaza |
| Javadoc | Tarea agregada `javadocAll`; CI la usa; README la documenta | `./gradlew javadocAll` completo en local |
| Tests | 9 módulos que no tenían `src/test` ganan pruebas de contrato/que cubren API real: `messages`, `observability`, `performance`, `presets`, `extension-api`, `example-extension`, `sync-bus`, `sync-websocket`, `inworld` | 38 tests nuevos; `buildAll` 766 tests, 0 fallos |
| Bugs reales | `DefaultSyncBus.stop()` no llamaba a `SyncSink.stop()` y podía tardar 10 s en terminar | `DefaultSyncBusTest` (entrega real + parada < 2 s) |
| Limpieza SpEL | Se eliminó `TransformEngine` (código legado sin consumidores de producción, no un bug real), `ExpressionEvaluator`, `SpelExpressionEvaluator`, el paso `<expr>`, las reglas SpEL de presets y el campo de condición del editor web; se retiraron `spring-expression` y `spring-beans` | `grep` sin referencias activas; `buildAll` 766 tests |
| Fabric | Loom genera artefactos remapeados locales (`LoomLocalRemappedMods`) sin hash remoto; verificación deshabilitada **solo** para los classpaths de `fabric-host`, documentado en el build | `:src:fabric-host:compileJava` compila; el resto sigue en metadata estricta |
| Documentación histórica | `docs/wiki/05-iFlow-Rules.md` reescrito para la VM `.flow` actual (nota sobre el motor SpEL eliminado); placeholder de `docs/README.md` y línea corrupta de `ADR.md` corregidos | enlaces y contenido actualizados |
| Editor web | Escape HTML y límite de pasos ya corregidos; los harnesses cargan `paths.json` con un polyfill de `fetch` y no renderizan switches genéricos para rutas `[]`; `bind` pasa de 1 mismatch a 0 | `npm run check` (108 tests) y `npm run test:integration` (todos los harnesses, 0 fallos) |
| Conteos | README y `src/iflow/README.md` sincronizados con 766 tests JVM / 397 de iFlow | `buildAll`, parseo de `TEST-*.xml` |

**Verificación P1:** `./gradlew buildAll` (766 tests JVM en 28 módulos, 0 fallos, 11 skipped), `javadocAll`, `checkLocks` 32/32 y `:src:host:verifySchema`, todo con `--offline --no-daemon`; web editor `npm run check` (108 tests) y `npm run test:integration` (0 fallos). El editor web sigue sin considerarse estable: se corrigieron el escapado, el límite de pasos y los harnesses, pero su deuda funcional/visual permanece.

---

## Anexo A — Correcciones a las auditorías fuente

| Afirmación | Corrección |
|---|---|
| “8 enlaces rotos” | Ya corregidos en `71f2318`; hoy 0 |
| “16 módulos sin tests” | 11 módulos sin tests Java (10 sin `src/test` + `fabric-host` vacío) |
| “491 tests / 4 `@Disabled`” | 505 tests en los XML actuales (4 módulos), 0 fallos, 7 skipped; 5 `@Disabled` |
| “`/suite update` no existe” | Sí existe: alias de `repo update` (`DynamicCommandRegistrar.java:165`) |
| “SSRF con `http://user@host/`” | El bypass requiere `user:pass@host`; con `user@host` el rewrite elimina el userinfo |
| “`SoftMMU.readByte` ignora privilegio” | No confirmado: delega en la sobrecarga con `Privilege` |
| “`DefaultSyncBus` dedup sin refresco” | Cierto, pero hay limpieza por antigüedad y tope; es ventana fija, no fuga |
| “`common-legacy` sin `src/`” | Tiene `src/main`, pero 0 ficheros |
| “31 versiones hardcodeadas” | 30 con `2.1.0-SNAPSHOT` + `example-extension` con `1.0.0` |
| “`auditar-modulo` contra especificación muerta” | `manager-v2.md` es la especificación APT/dpkg vigente; el problema es que solo cubre Manager |
| “Rúbrica dependiente de `chat.log`” | `extract_chat.py` sí; `judge.py` no. La parte narrativa se materializa en `judge_scores.csv` |
| Omisión: estado material del repo | A1/A2 se centran en git y código; no miden los 361 MB del árbol, los 86 `build/`, los 114 MB de `node_modules`, los 16 MB de evidencia fuera de git ni el `node_modules` histórico |

## Anexo B — Comandos de verificación usados

```bash
git rev-parse HEAD
grep -c "^include" settings.gradle
find src -maxdepth 2 -name build.gradle | wc -l
find src -name gradle.lockfile | wc -l
grep -rn "project(':src:" src/*/build.gradle
python3 # parseo de TEST-*.xml y grafo de dependencias
sha256sum # comparación del hash de coordenada con verification-metadata.xml
java /tmp/opencode/UrlTest.java # bypass SSRF con userinfo
du -sh . .git
git status --porcelain --ignored
git clean -ndX
find . -type d -name build -not -path './.git/*' | wc -l
du -sh tools/project-metrics/data src/web-editor/node_modules tools/iflow-compiler/node_modules
diff -u src/host/src/main/resources/web-editor-schema/paths.js src/web-editor/js/paths.js | wc -l
git rev-list --objects --all | git cat-file --batch-check='%(objecttype) %(objectsize) %(rest)' | sort -k2 -rn | head
git tag | wc -l
```
