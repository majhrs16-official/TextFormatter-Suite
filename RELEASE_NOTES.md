# Release Notes — TextFormatter Suite v2.1.0

**Fecha**: 2026-09-18  
**Commit**: post-auditoría 14/16 sep + fixes 18 sep

---

## 🎯 Resumen

Esta release consolida el núcleo de TextFormatter Suite tras las auditorías del 14 y 16 de septiembre de 2026. El foco ha sido **completar el Module Manager (F12)**, **cerrar Security Sprint 3**, y **corregir bugs críticos** detectados en las auditorías.

---

## ✅ Completado en v2.1.0

### Module Manager (F12) — Núcleo Release-Ready
- **Version resolver**: Rangos semver (`[1.0,2.0)`, `^2.1.0`, `~2.1.0`) + compatibilidad de entorno (Java, MC, contract, platform, capabilities).
- **Dependency resolver**: Parsing de `module.yml`/`module.yaml` desde el JAR descargado para dependencias transitivas.
- **SHA256 verification**: Obligatorio. Asset `.sha256` separado verificado en download.
- **register() SPI-only**: No instancia `Module`; solo almacena descriptor + classloader. Nuevo método `discoverAll()` para integración kernel.
- **Manifest validation obligatoria**: Lanza excepción si falta `module.yml` o hay inconsistencias.
- **ClassLoader aislado**: `URLClassLoader` parent-last para módulos, parent-first para API/JDK.

### Security Sprint 3 — Completado
| Item | Implementación |
|------|----------------|
| Tokens en `char[]` | `JdaDiscordSink`, `DiscordSink`, `TelegramSink`, `LTranslate` usan `char[]` + `Arrays.fill('\0')` |
| MiniEscape completo | Escapa `< > \ { } [ ] ( ) # @` |
| PAPI dynamic check | `SpigotPlaceholderResolver.available()` ya existía |
| SpEL LRU cache | `LruExpressionCache` (1024 entradas) en `SpelExpressionEvaluator` |
| SSRF protection | `HttpTransport` valida IPs contra RFC 1918/3927/6598, loopback, multicast, deny patterns configurables |
| DependencyVerification | `verification-metadata.xml` con SHA256/SHA512 generado; `dependencyLocking` en build.gradle |

### Fixes Críticos (Auditoría 14/16 sep)
- **CT-01**: `Message.toJson()` → serialización JSON real (antes `toString()`)
- **OBS-01**: `DebugEndpoint` executor shutdown en `stop()`
- **CFG-01**: `ConfigLoader.LoadResult` con errores explícitos, logging ERROR level
- **MGR-02**: `register()` SPI-only + `discoverAll()` para kernel
- **SEC-01**: SpEL LRU cache (1024) implementado
- **SEC-02**: SSRF protection en `HttpTransport` (deny patterns RFC 1918/3927/6598)

---

## 🔄 En Progreso

- **Documentación**: PLAN.md ✅, Release Notes ✅, Wiki, ADR (README/PROMPT_NOW actualizados)
- **Release Pipeline**: GitHub Actions CI/CD, `verification-metadata.xml`, semantic versioning config
- **gradle.lockfile** portable (actualmente solo caché Gradle)

---

## ⏳ Pendiente

| Prioridad | Item | Detalle |
|-----------|------|---------|
| 🔴 | Tests E2E | Pipeline completo chat → iFlow → format → delivery (requiere Spigot real) |
| 🔴 | GitHub Releases | Para Module Manager downloader (F12-2) |
| 🟡 | sync-velocity | Implementar real o eliminar stub |
| 🟡 | Translation cache/dedup | P1 escalabilidad — dedup por (sourceLang, targetLang, text) |
| 🟡 | spigot-api | 1.16.5-R0.1-SNAPSHOT unavailable → fix build |

---

## 📦 Módulos Estables (Compilan + Tests Pasan)

| Módulo | Estado |
|--------|--------|
| `core-api`, `kernel`, `textformatter`, `iflow`, `host` | ✅ |
| `gtranslate`, `ltranslate`, `transport` | ✅ |
| `sync-discord`, `sync-telegram`, `sync-http`, `sync-tcpudp`, `sync-websocket` | ✅ |
| `manager-api`, `manager-impl`, `presets`, `inworld` | ✅ |
| `observability`, `extension-api`, `example-extension` | ✅ |
| `messages`, `tester`, `loadtest`, `performance` | ✅ |
| `spigot-host` | ✅ Compila (fat-jar) |
| `fabric-host` | ❌ Excluido (Loom/Minecraft download) |

---

## 🔐 Seguridad

| Vector | Estado | Mitigación |
|--------|--------|------------|
| SpEL RCE (CWE-94) | ✅ | `SimpleEvaluationContext.forReadOnlyDataBinding()` + LRU 1024 |
| YAML deserialization (CWE-502) | ✅ | `SafeConstructor` en 4 loaders |
| MiniMessage injection | ✅ | MiniEscape completo (10 chars) |
| Token leakage | ✅ | `char[]` + `Arrays.fill('\0')` |
| SSRF (CWE-918) | ✅ | Deny patterns RFC 1918/3927/6598 + configurable |
| Dependency verification | ✅ | `verification-metadata.xml` SHA256/SHA512 |
| Manifest validation | ✅ | Obligatoria en Module Manager |

---

## 📋 Migración desde v2.0.x

### Breaking Changes
- `Message.toJson()` ahora devuelve JSON válido (antes `toString()`)
- `ConfigLoader.loadConfig/loadChannels` devuelven `LoadResult<T>` (antes `T` directo)
- `register()` ya no instancia `Module` (era incorrecto per SPI contract)
- `HttpTransport` bloquea IPs privadas por defecto (desactivable via constructor)

### Configuración
```yaml
# HttpTransport SSRF deny patterns personalizados
# -Dtextformattersuite.http.deny="^10\.,^192\.168\."
```

---

## 🧪 Testing

```bash
# Core modules
./gradlew :suite:core-api:test :suite:host:test :suite:manager-impl:test --offline --no-daemon

# Transport + translators + sync
./gradlew :suite:transport:test :suite:gtranslate:test :suite:ltranslate:test :suite:sync-*:test --offline --no-daemon

# Web editor
cd suite/web-editor && npm run check && npm run test:integration
```

---

## 📄 Documentación Relacionada

- **PLAN.md** — Estado actualizado 2026-09-18
- **PROMPT_NOW.md** — Plan maestro vivo
- **AUDITORIA-14-09-2026.md** — Auditoría 14 sep
- **AUDITORIA-16-09-2026.md** — Auditoría 16 sep
- **ADR.md** — Decisiones de arquitectura
- **verification-metadata.xml** — SHA256/SHA512 de dependencias (en `gradle/` y raíz)

---

## 🙏 Créditos

Auditorías y fixes por agente de código autónomo.  
Arquitectura hexagonal, SPI, Module Manager, Security Sprint 3: implementación completa.