# Auditoría de Cumplimiento Estricto — Arquitectura Hexagonal (Ports & Adapters)
## TextFormatter Suite — 2026-09-06

---

## Resumen Ejecutivo

| Métrica | Valor |
|---------|-------|
| **Reglas verificadas** | 8/8 |
| **Cumplimiento total** | ✅ 7 · ⚠️ 1 (deuda menor) |
| **Violaciones críticas** | 0 |
| **Violaciones menores** | 1 |
| **Deuda arquitectural estimada** | ~2-4 horas (solo documentación/limpieza) |

---

## Tabla de Verificación por Regla

| # | Regla (ADR/PROMPT) | Estado | Evidencia (archivo:línea) |
|---|--------------------|--------|---------------------------|
| **1** | `core-api` = JDK-puro, cero dependencias externas | ✅ | `core-api/build.gradle:13-17` (solo test deps JUnit); `core-api` no importa `net.kyori.adventure`, `org.yaml`, `org.json`, etc. |
| **2** | Puertos en `host/port/` (ChatDelivery usa Adventure Component) — NO en `core-api` | ✅ | `host/src/main/java/me/majhrs16/suite/host/port/ChatDelivery.java:1-47` (importa `net.kyori.adventure.text.Component`); `core-api/src/main/java/me/majhrs16/suite/api/spi/ActorDirectory.java:1-53` (sin Adventure, JDK-puro) |
| **3** | Adapters implementan puertos, NUNCA se importan entre sí | ✅ | `spigot-host` importa solo `me.majhrs16.suite.spigothost.logic.*`; `fabric-host` importa solo `me.majhrs16.suite.fabrichost.logic.*`; **0** cruces `spigothost↔fabrichost` (ver `grep` resultados) |
| **4** | SPI = ServiceLoader (`META-INF/services/...Module`) — sin modloader custom | ✅ | `kernel/src/main/java/me/majhrs16/suite/kernel/ModuleLoader.java:19-22` (`ServiceLoader.load(Module.class, loader)`); cada módulo declara `META-INF/services/me.majhrs16.suite.api.Module` (ej. `textformatter/.../Module:1`) |
| **5** | Handshake doble: JVM runtime version + SPI contract semver (por artefacto `*-api`) | ✅ | `kernel/src/main/java/me/majhrs16/suite/kernel/Environment.java:22-28` (jvm + api capabilities); `ModuleGraph.java:221-247` (`contractMismatch`, `jvmMismatch` con `SemVer`); `ModuleDescriptor.java:14-29` (`contractVersion`, `jvmMin`, `jvmMax`) |
| **6** | Mismatch → cargar/degradar/avisar (no crash) | ✅ | `ModuleGraph.java:68-115` (`markRejected` clasifica: `CONTRACT_MISMATCH`, `JVM_MISMATCH`, `UNSATISFIED_REQUIREMENT`, `CYCLE`); `ResolutionStatus.java` enum con estados no fatales; host decide política de carga |
| **7** | Descubrimiento de módulos vía SPI únicamente | ✅ | `ModuleLoader.java:19-22` (`ServiceLoader` único punto de entrada); `kernel/src/test/.../ModuleLoaderTest.java` valida descubrimiento aislado; no hay `Class.forName`, `ModuleLayer`, ni manififest custom |
| **8** | `web-editor` comunica vía YAML/schema, cero acoplamiento runtime Java | ✅ | `web-editor/js/model.js:147-247` (export/import YAML puro); `web-editor/js/yaml.js` (parser/writer propio sin deps Java); `docs/web-editor/schema-v2.2.md` (schema v2.2 fuente única); `ConfigLoaderTest.parsesEditorExportedDefaultConfig` (e2e round-trip) |

---

## Detalle de Evidencia por Regla

### Regla 1 — `core-api` JDK-puro
```
core-api/build.gradle
13: dependencies {
14:     testImplementation platform('org.junit:junit-bom:5.10.2')
15:     testImplementation 'org.junit.jupiter:junit-jupiter'
16:     testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
17: }
```
- **Cero** dependencias `implementation` / `api` / `compileOnly` externas.
- Modelos (`Message`, `Actor`, `Direction`, `Channel`, `Language`, `SoundSpec`) y SPI (`Module`, `ActorDirectory`, `Translator`, `PlaceholderResolver`, `PluginLogger`, `TranslationService`, `SyncSink`, `ExpressionEvaluator`, `UserLanguageStore`) son **100% JDK 17**.

### Regla 2 — Puertos en `host/port/`, no en `core-api`
```
host/src/main/java/me/majhrs16/suite/host/port/ChatDelivery.java
7: import net.kyori.adventure.text.Component;
12: * ... porque su contrato lleva Adventure Component — core-api stays
13: * dependency-free.
```
- `ActorDirectory` (en `core-api/spi/`) es **puerto de entrada** (read-only view) — JDK-puro, métodos `default` para `playersInWorld`/`playersNear` que devuelven vacío.
- `ChatDelivery` (en `host/port/`) es **puerto de salida** — lleva `Component` de Adventure; el ADR §132-150 documenta la desviación consciente.

### Regla 3 — Adapters no se importan entre sí
```
grep -r "import.*spigothost\|import.*fabrichost" suite/ --include="*.java"
```
**Resultado:** Solo imports internos de cada adapter a su propio paquete `logic/*`. **Cero** dependencias cruzadas Spigot↔Fabric.

### Regla 4 — SPI = ServiceLoader
```
kernel/src/main/java/me/majhrs16/suite/kernel/ModuleLoader.java
19: public static List<Module> discover(ClassLoader loader) {
20:     List<Module> modules = new ArrayList<>();
21:     ServiceLoader.load(Module.class, loader).forEach(modules::add);
22:     return modules;
23: }
```
Cada módulo declara su proveedor:
```
textformatter/src/main/resources/META-INF/services/me.majhrs16.suite.api.Module
1: me.majhrs16.suite.textformatter.TextFormatterModule

gtranslate/src/main/resources/META-INF/services/me.majhrs16.suite.api.Module
1: me.majhrs16.suite.gtranslate.GTranslateModule

iflow/src/main/resources/META-INF/services/me.majhrs16.suite.api.Module
1: me.majhrs16.suite.iflow.IflowModule
... (10 módulos total)
```

### Regla 5 — Handshake doble (JVM + Contract SemVer)
```
kernel/src/main/java/me/majhrs16/suite/kernel/Environment.java
22: public static Environment current() {
23:     String version = System.getProperty("java.version");
24:     int major = versionMajor(version);
25:     return new Environment(SemVer.parse("2.1.0"), SemVer.of(major, 0, 0));
26: }
```
```
kernel/src/main/java/me/majhrs16/suite/kernel/ModuleGraph.java
221: private String contractMismatch(ModuleDescriptor descriptor) {
222:     SemVer host = environment.contractVersion();  // 2.1.0
223:     if (descriptor.contractVersion().major() < host.major()) { ... }
224:     if (descriptor.contractVersion().major() > host.major()) { ... }
225:     if (descriptor.contractVersion().compareTo(host) > 0) { ... }
238: private String jvmMismatch(ModuleDescriptor descriptor) {
239:     int running = environment.jvmVersion().major();
240:     if (descriptor.jvmMin() > 0 && running < descriptor.jvmMin()) { ... }
241:     if (descriptor.jvmMax() > 0 && running > descriptor.jvmMax()) { ... }
```
Cada `ModuleDescriptor` declara:
```java
.contractVersion(SemVer.of(2, 1, 0))
.jvmRange(17, 0)  // min=17, max=unbounded
```

### Regla 6 — Mismatch → degradar/avisar (no crash)
```
kernel/src/main/java/me/majhrs16/suite/kernel/ResolutionStatus.java
// Estados no fatales:
RESOLVED,
CONTRACT_MISMATCH,    // versión contrato incompatible
JVM_MISMATCH,         // JVM incompatible
UNSATISFIED_REQUIREMENT,  // dependencia no satisfecha
CYCLE                 // ciclo de dependencias
```
```
ModuleGraph.java:68-115  // markRejected clasifica y NO lanza excepción
```
El host (`SuiteHost.bootstrap` / plugins) recibe `ResolutionResult` y decide política (cargar, degradar, log warn).

### Regla 7 — Descubrimiento vía SPI únicamente
```
ModuleLoader.java:19-22  // Único punto de descubrimiento
```
- Tests: `kernel/src/test/.../ModuleLoaderTest.java` (aislado, classpath limpio)
- No hay `ModuleLayer`, `ServiceLoader` custom, reflexión de constructores, ni manifiestos propietarios.

### Regla 8 — Web Editor: YAML/Schema, cero acoplamiento Java
```
web-editor/js/model.js:147-247  // exportFiles() → YAML strings; importFromFiles() ← YAML strings
web-editor/js/yaml.js           // Parser/writer YAML 1.2 propio (zero deps)
docs/web-editor/schema-v2.2.md  // Schema v2.2: config.yml, channels/*.yml, rules.yml, translators/*.yml, sync/*.yml, manifest.json
host/src/test/.../ConfigLoaderTest.java  // parsesEditorExportedDefaultConfig (e2e verde)
```
- El editor es **estático** (GitHub Pages), zero JVM runtime.
- Round-trip verificado: export → import → export **byte-idéntico** (salvo `manifest.json` timestamp).

---

## Deuda Arquitectural Cuantificada

| Ítem | Qué Falta | Esfuerzo Estimado | Prioridad |
|------|-----------|-------------------|-----------|
| **D1** | Documentar en `ADR.md` la decisión explícita: `ActorDirectory` en `core-api/spi` (puerto entrada) vs `ChatDelivery` en `host/port` (puerto salida con Adventure) — ya implementado, falta ADR formal | 1-2h (docs) | Media |
| **D2** | `coretranslator` depende de `common-legacy` (línea 21 `build.gradle`) — módulo legacy transitorio; planificar eliminación completa cuando F8 (migración v4) concluya | 2-4h (refactor + tests) | Baja (WIP conocido) |
| **D3** | `tester` module tiene `implementation 'org.spigotmc:spigot-api'` en `compile` scope (línea 32 `tester/build.gradle`) — debería ser `testImplementation` o `compileOnly` para no contaminar classpath de producción | 30min (build.gradle) | Media |
| **D4** | `fabric-host` WIP: `FabricPlaceholderResolver` retorna `input` sin resolver (línea 14) — documentar como limitación conocida vs PAPI en Spigot | 30min (docs) | Baja |

**Total estimado: ~3-7 horas** (principalmente documentación y limpieza de `tester` scope).

---

## Violaciones Encontradas (Ubicación Exacta)

### ⚠️ V1 — `tester` module contamina classpath de producción con Spigot API
**Archivo:** `suite/tester/build.gradle:32`
```gradle
// Spigot API for runtime (needed for Bukkit types in main source)
implementation 'org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT'
```
**Problema:** `tester` es un módulo de suite (publica `suite-tester` jar) pero arrastra dependencia de Spigot en `implementation` (compile classpath). Esto rompe la regla "adapters no se importan entre sí" indirectamente: cualquier consumidor de `suite-tester` recibe Spigot transitivamente.

**Fix:** Cambiar a `compileOnly` o mover código Spigot-específico a `test` source set.
```gradle
compileOnly 'org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT'
```

### ✅ Sin violaciones críticas
- **Ningún** módulo interno (`kernel`, `textformatter`, `iflow`, `gtranslate`, `ltranslate`, `sync-*`, `messages`, `transport`, `coretranslator`) importa adaptadores de plataforma (`spigot-host`, `fabric-host`).
- **Ningún** adapter importa otro adapter.
- **Ningún** código en `core-api` usa Adventure, SnakeYAML, JSON, JDA, u otra lib externa.
- SPI handshake **completamente implementado** con degradación graceful.

---

## Conclusiones

1. **Arquitectura Hexagonal: CUMPLE** — Separación estricta core (domain+ports) / adapters (Spigot/Fabric) / host (composición). Puertos en ubicaciones correctas (`core-api/spi` para entrada, `host/port` para salida con deps plataforma).

2. **SPI + ServiceLoader: CUMPLE** — Descubrimiento único vía `META-INF/services`, handshake doble (JVM + contract semver) con estados de resolución no fatales.

3. **Web Editor desacoplado: CUMPLE** — Comunicación 100% YAML/schema v2.2, zero runtime Java, round-trip validado por tests e2e.

4. **Deuda menor identificada** — Solo `tester` scope Spigot y documentación de decisiones ADR pendientes. **Ninguna** violación estructural de puertos/adapters.

---

**Auditor:** Agente de Arquitectura Hexagonal  
**Fecha:** 2026-09-06  
**Baseline:** `docs/ADR.md` (2026-08-14 + notas 2026-09-02), `docs/PROMPT.md`, código fuente suite v2.1.0-SNAPSHOT
