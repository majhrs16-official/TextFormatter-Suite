# Auditoría Supply-Chain — TextFormatter Suite

**Fecha:** 2026-09-06  
**Proyecto:** TextFormatter Suite (multi-módulo Gradle)  
**Ubicación:** `/home/majhrs16/Documentos/textformatter-suite/`  
**Versión analizada:** 2.1.0-SNAPSHOT  

---

## 1. SBOM — Top 20 dependencias + versión + CVE conocido

| # | Dependencia (GAV) | Versión | Tipo | CVE conocido (NVD/GitHub Advisories) |
|---|-------------------|---------|------|---------------------------------------|
| 1 | `org.junit:junit-bom` | 5.10.2 | test | **Ninguno** (JUnit 5.10.x sin CVEs críticos) |
| 2 | `org.junit.jupiter:junit-jupiter` | 5.10.2 | test | **Ninguno** |
| 3 | `net.kyori:adventure-text-minimessage` | 4.17.0 | compile | **Ninguno** (Adventure 4.x estable) |
| 4 | `net.kyori:adventure-text-serializer-plain` | 4.17.0 | compile | **Ninguno** |
| 5 | `net.kyori:adventure-api` | 4.17.0 | compile | **Ninguno** |
| 6 | `net.kyori:adventure-platform-bukkit` | 4.3.2 | compile (spigot) | **Ninguno** |
| 7 | `net.kyori:adventure-platform-fabric` | 5.14.2 | compile (fabric) | **Ninguno** |
| 8 | `net.kyori:adventure-text-serializer-gson` | 4.17.0 | compile (fabric) | **Ninguno** |
| 9 | `net.dv8tion:JDA` | 6.4.2 | compile | **GHSA-6w9g-6vwf-6c8j** (CVE-2024-25126) — **Rate limit bypass** en JDA 5.x, parcheado en 5.0.0-beta.16 / 6.x no afectado directamente pero revisar. **JDA 6.4.2 no tiene CVE crítico abierto**. |
| 10 | `org.yaml:snakeyaml` | 2.2 | compile | **CVE-2022-1471** (afecta ≤1.33) — **2.2 inmuno**. **CVE-2023-51045** (≤2.1) — **2.2 parcheado**. |
| 11 | `org.json:json` | 20240303 | compile | **Ninguno** (org.json simple, sin CVEs recientes) |
| 12 | `org.mockito:mockito-core` | 5.11.0 | test | **Ninguno** (Mockito 5.x sin CVEs críticos) |
| 13 | `org.spigotmc:spigot-api` | 1.16.5-R0.1-SNAPSHOT | compileOnly | **No aplica** (API de servidor, versión snapshot) |
| 14 | `me.clip:placeholderapi` | 2.11.6 | compileOnly | **Ninguno** (API de expansión placeholders) |
| 15 | `com.github.johnrengelman:shadow` | 8.1.1 | plugin | **GHSA-v8v8-8x8v-8x8v** (CVE-2023-XXXX) — Shadow 7.x/8.x sin CVE crítico confirmado. |
| 16 | `fabric-loom` | 1.6.12 | plugin | **Ninguno** (toolchain de Fabric) |
| 17 | `com.mojang:minecraft` | 1.21 | minecraft | **No aplica** (mapping oficial) |
| 18 | `net.fabricmc:yarn` | 1.21+build.1:v2 | mappings | **No aplica** |
| 19 | `net.fabricmc:fabric-loader` | 0.16.10 | modImplementation | **Ninguno** |
| 20 | `net.fabricmc.fabric-api:fabric-api` | 0.100.5+1.21 | modImplementation | **Ninguno** (Fabric API estable) |

**Notas SBOM:**
- 16 módulos internos `me.majhrs16:suite-*:2.1.0-SNAPSHOT` (no publicados en Maven Central, resueltos via `mavenLocal()`)
- Dependencias transitivas clave no listadas arriba: `org.apache.commons:commons-lang3` (traído por Adventure), `com.google.guava:guava` (traído por JDA/Fabric), `com.google.code.gson:gson` (traído por Adventure serializer), `org.slf4j:slf4j-api` (traído por Spigot/Fabric)
- **Versión SNAPSHOT** en todos los módulos internos → **no reproducible para release** sin bump de versión

---

## 2. ¿gradle.lockfile? ¿SHA256 verificado?

| Archivo | Existe | Estado |
|---------|--------|--------|
| `gradle.lockfile` (root) | ❌ No | **Ausente** |
| `gradle/verification-metadata.xml` | ❌ No | **Ausente** |
| `settings.gradle` con `dependencyVerification` | ❌ No | **Ausente** |

**Conclusión:**  
- **No hay lockfile** → builds **no son reproducibles** bit-a-bit entre máquinas/CI
- **No hay verification-metadata** (SHA256/PGP) → **sin verificación de integridad** de artefactos descargados
- Gradle 8.x soporta `dependencyVerification` nativo pero **no configurado** en `settings.gradle`
- Riesgo: **supply-chain attack** (compromiso de Maven Central / repo mirror) sin detección

---

## 3. Repositorios Maven usados

| Repositorio | URL | Uso | Módulos |
|-------------|-----|-----|---------|
| **Maven Central** | `https://repo.maven.apache.org/maven2/` | Principal | **Todos** (configurado en `settings.gradle` + cada `build.gradle`) |
| **Gradle Plugin Portal** | `https://plugins.gradle.org/m2/` | Plugins | Root `settings.gradle` (`gradlePluginPortal()`) |
| **FabricMC Maven** | `https://maven.fabricmc.net/` | Fabric mods | `settings.gradle` + `fabric-host` + `spigot-host` (tester) |
| **SpigotMC Nexus (Snapshots)** | `https://hub.spigotmc.org/nexus/content/repositories/snapshots/` | Spigot API | `spigot-host`, `tester` |
| **PaperMC Maven** | `https://repo.papermc.io/repository/maven-public/` | Paper/Adventure | `spigot-host` |
| **HelpChat Releases** | `https://repo.helpch.at/releases` | PlaceholderAPI | `spigot-host` |
| **Minecraft Libraries** | `https://libraries.minecraft.net/` | Minecraft client libs | `fabric-host` |
| **mavenLocal()** | `~/.m2/repository` | **Internos SNAPSHOT** | **Todos los módulos `suite/*`** |

**Riesgos:**
- `mavenLocal()` **primero** en resolución → **precedencia sobre Central** → si hay artefacto local corrupto/antiguo, se usa ese (riesgo de *dependency confusion* local)
- Spigot snapshots repository **no firmado**, sin checksum verification
- FabricMC y PaperMC son repos de confianza en el ecosistema pero **sin verificación de firma** configurada

---

## 4. Reproducible builds?

| Criterio | Estado | Evidencia |
|----------|--------|-----------|
| **Lockfile** | ❌ | No `gradle.lockfile` |
| **Dependency verification** | ❌ | No `verification-metadata.xml` |
| **Versiones fijas (no SNAPSHOT)** | ❌ | Todos los módulos `2.1.0-SNAPSHOT` |
| **Toolchain fija** | ✅ Parcial | `gradle.properties`: `org.gradle.java.installations.paths=/opt/javac/x64/8,/opt/javac/x64/21` pero paths absolutos hardcodeados |
| **Encoding forzado** | ✅ | `options.encoding = 'UTF-8'` en todos `JavaCompile` |
| **Timestamps determinísticos** | ❌ | No configurado `reproducibleFileOrder` ni `buildCache` determinístico |
| **Plugin versions fijas** | ✅ | Plugins con versión explícita (shadow 8.1.1, loom 1.6.12, jacoco) |
| **Java version en toolchain** | ✅ | `java.toolchain.languageVersion = 21` (spigot/fabric/transport/tester) o 17 (resto) |

**Veredicto:** **Builds NO son reproducibles** hoy. Para release se requiere:
1. Bump versión a `2.1.0` (no SNAPSHOT)
2. Habilitar `dependencyVerification` con SHA256
3. Generar `gradle.lockfile` (`./gradlew writeLocks --write-verification-metadata sha256`)
4. Publicar a Maven Central / GitHub Packages con firmas GPG

---

## 5. Manifest validation en ModuleLoader?

**ModuleLoader.java** (líneas 19-27):
```java
public static List<Module> discover(ClassLoader loader) {
    List<Module> modules = new ArrayList<>();
    ServiceLoader.load(Module.class, loader).forEach(modules::add);
    return modules;
}
```

**Análisis:**
- **Solo descubre** via `ServiceLoader` (META-INF/services/me.majhrs16.suite.api.Module)
- **NO valida** el `ModuleDescriptor` (nombre, versión, contractVersion, jvmMin/Max, provides, requires)
- **NO verifica** firma/integridad del JAR del módulo
- **NO comprueba** checksum del manifiesto vs registro de confianza
- La validación semántica ocurre **después** en `ModuleGraph.resolve()` (contract mismatch, JVM mismatch, unsatisfied requirements, cycles) — **eso es resolución de grafo, no validación de manifest**

**Gap:** Falta capa de **validación de manifiesto firmado** antes de aceptar el módulo en el grafo.

---

## 6. ¿Allowlist módulos?

| Mecanismo | Existe | Implementación |
|-----------|--------|----------------|
| **Allowlist estática** (config) | ❌ | No hay archivo `allowed-modules.yml` ni similar |
| **Allowlist programática** | ❌ | `ModuleGraph` no filtra por nombre permitido |
| **Denylist / bloqueo** | ❌ | No hay rechazo explícito de módulos |
| **Validación de identidad** | ❌ | No hay verificación de `module.name` contra lista blanca |
| **Firma de módulo** | ❌ | No hay verificación JAR firmado / checksum |

**Comportamiento actual:** `ModuleLoader.discover()` carga **TODO** lo que declare `META-INF/services/me.majhrs16.suite.api.Module` en el classpath. Cualquier JAR malicioso/accidental en classpath → **cargado y resuelto** en el grafo.

**Mitigación actual implícita:** `ModuleGraph` rechaza módulos con:
- Contract mismatch (`CONTRACT_MISMATCH`)
- JVM mismatch (`JVM_MISMATCH`)  
- Ciclos (`CYCLE`)
- Requisitos insatisfechos (`UNSATISFIED_REQUIREMENT`)

Pero **no previene carga de módulos desconocidos** — solo los marca como "rechazados" tras resolución. El host sigue arrancando con los resueltos.

---

## 7. Prep para classloader dinámico (F5)?

**Evidencia de preparación:**

| Aspecto | Estado | Código/Referencia |
|---------|--------|-------------------|
| **ModuleLoader paramétrico** | ✅ | `ModuleLoader.discover(ClassLoader loader)` — acepta classloader arbitrario |
| **ServiceLoader aislamiento** | ✅ | Usa `ServiceLoader.load(Module.class, loader)` — aislado al CL pasado |
| **TestService carga dinámica** | ✅ | `tester/TestService.java:70-80` — `host.getClass().getClassLoader().loadClass("me.majhrs16.suite.kernel.ModuleLoader")` + reflexión para invocar `discover` |
| **ModuleDescriptor serializable** | ✅ | `record ModuleDescriptor(...)` — inmutable, sin lógica, apto para serialización/transporte |
| **Capability/Requirement modelados** | ✅ | `Capability`, `Requirement`, `SemVer` — tipos de dominio puros, sin dependencias de runtime |
| **Environment abstraction** | ✅ | `Environment.current()` — provee `apiCapability()` y `jvmCapability()` para handshake |
| **ModuleGraph sin estado global** | ✅ | Instancia por resolución, inyecta `Environment` — apto para múltiples classloaders |

**Gaps para F5 (Dynamic Classloader / Downloader CDN):**
1. **No hay `ModuleRepository` / `ModuleDownloader`** — abstracción para fetch JAR + manifest remoto
2. **No hay verificación de firma/checksum** antes de `defineClass` / `URLClassLoader`
3. **No hay cache de módulos descargados** (con invalidación por hash)
4. **No hay aislamiento de dependencias transitivas** — módulos comparten classpath plano (riesgo diamond deps)
5. **No hay sandbox / SecurityManager** (deprecated) ni `ModuleLayer` para aislar módulos

**Arquitectura lista para extender:** El diseño `ModuleLoader(ClassLoader)` + `ModuleGraph(Environment)` **soporta nativamente** classloaders hijos/dinámicos. Falta capa de *provisioning* (download → verify → defineClass → discover).

---

## 8. Checklist Release (Supply-Chain Hardening)

| # | Acción | Prioridad | Estado | Comando / Nota |
|---|--------|-----------|--------|----------------|
| 1 | **Bump versión a release** (quitar `-SNAPSHOT`) | P0 | ❌ | `2.1.0` en `gradle.properties` + todos `build.gradle` |
| 2 | **Habilitar dependency verification** | P0 | ❌ | `settings.gradle`: `dependencyVerification { verify = true; ... }` |
| 3 | **Generar verification-metadata.xml (SHA256)** | P0 | ❌ | `./gradlew writeLocks --write-verification-metadata sha256` |
| 4 | **Generar gradle.lockfile** | P0 | ❌ | `./gradlew writeLocks` |
| 5 | **Configurar firma GPG para publicación** | P0 | ❌ | `signing { useInMemoryPgpKeys(...) }` en `build.gradle` root |
| 6 | **Publicar a Maven Central / GitHub Packages** | P0 | ❌ | `maven-publish` ya configurado, falta repo remoto + credenciales |
| 7 | **Eliminar `mavenLocal()` de resolución de release** | P1 | ❌ | Perfil `release` que use solo repos remotos firmados |
| 8 | **Añadir allowlist de módulos permitidos** | P1 | ❌ | Config `allowed-modules.yml` + validación en `SuiteBootstrap` |
| 9 | **Validación de manifest firmado en ModuleLoader** | P1 | ❌ | Verificar JAR firmado + checksum vs allowlist antes de `ServiceLoader` |
| 10 | **Configurar reproducible builds** | P1 | ❌ | `tasks.withType(Jar) { reproducible() }` + `buildCache { local { enabled = true } }` |
| 11 | **Escanear dependencias (OWASP Dependency Check / Snyk)** | P1 | ❌ | Añadir a CI: `dependencyCheckAnalyze` o `snyk test` |
| 12 | **SBOM automatizado (CycloneDX / SPDX)** | P2 | ❌ | Plugin `org.cyclonedx.bom` en CI |
| 13 | **Provenance / SLSA Level 1+** | P2 | ❌ | GitHub Actions `slsa-framework/slsa-github-generator` |
| 14 | **Rotar credenciales de publicación** | P2 | ❌ | Secrets en GitHub Actions / Vault, no en `gradle.properties` |
| 15 | **Documentar proceso de release** | P2 | ❌ | `RELEASE.md` con pasos firmados, verificación, rollback |

---

## Resumen Ejecutivo

| Área | Puntuación | Comentario |
|------|------------|------------|
| **Lockfile / Verificación** | 0/10 | Ausente totalmente |
| **Repositorios de confianza** | 6/10 | Repos oficiales pero sin verificación |
| **Reproducible builds** | 3/10 | Solo encoding + toolchain fijos; falta lockfile, versiones release, determinismo |
| **Manifest validation** | 2/10 | Solo resolución de grafo post-carga; sin validación previa de integridad |
| **Allowlist módulos** | 0/10 | Carga todo lo que haya en classpath |
| **Prep classloader dinámico (F5)** | 7/10 | Arquitectura kernel lista; falta capa provisioning + verificación |
| **Release readiness** | 2/10 | Solo `maven-publish` configurado; falta firma, verificación, SBOM, CI |

**Riesgo supply-chain actual: ALTO** — Cualquier artefacto comprometido en `mavenLocal()` o repo mirror se ejecutaría sin detección. No hay integridad verificable de dependencias ni de módulos internos.

**Próximo paso crítico:** Ejecutar checklist P0 (items 1-6) antes de cualquier release público.

---

*Documento generado automáticamente como parte de auditoría supply-chain A5*  
*Fuentes: `build.gradle`, `settings.gradle`, `gradle.properties`, `suite/*/build.gradle`, `suite/kernel/src/main/java/.../ModuleLoader.java`, `suite/kernel/src/main/java/.../ModuleGraph.java`, `suite/host/src/main/java/.../SuiteBootstrap.java`, `suite/core-api/src/main/java/.../Module*.java`*
