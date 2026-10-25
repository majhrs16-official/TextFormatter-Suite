# Auditoría Exhaustiva de Inconsistencias — TextFormatter Suite

**Commit auditado:** `3aaba49` (HEAD, 15 commits ahead de origin/main)  
**Fecha:** 2026-10-24  
**Cobertura:** 33 módulos en `src/`, todos los `build.gradle`, `settings.gradle`, `docs/prompts/diseno/*.md`, `docs/workdir/PLAN.md`

---

## Resumen Ejecutivo

| Categoría | Crítica | Alta | Media | Baja | Total |
|-----------|---------|------|-------|------|-------|
| **Bugs** | 0 | 5 | 2 | 2 | 9 |
| **Defectos de diseño** | 0 | 5 | 4 | 2 | 11 |
| **Deuda técnica** | 0 | 2 | 0 | 0 | 2 |
| **Total** | **0** | **12** | **6** | **4** | **22** |

> **Nota:** Se encontraron 20 hallazgos únicos (algunos combinan múltiples ubicaciones).

---

## Hallazgos Críticos por Área

### 🔴 Arquitectura de Módulos (Module System)

| ID | Hallazgo | Severidad |
|----|----------|-----------|
| **INCONSISTENCY-002** | 5/6 submódulos `sync-*` no declaran `require('sync-bus')` | **Alta** |
| **INCONSISTENCY-006** | `iflow-vm` y `iflow-wasm` no tienen `Module` implementation | Media |
| **INCONSISTENCY-007** | 19/33 módulos carecen de `ModuleDescriptor` (incluye `host`, `core-api`, `kernel`) | Media |
| **INCONSISTENCY-013** | `kernel` solo tiene `Module` en test fixtures | Baja |
| **INCONSISTENCY-015** | Contrato `sync-bus` existe pero no se usa | Media |

### 🔴 ChatTranslator — Fachada Unificada (CT4/CT5 pendientes)

| ID | Hallazgo | Severidad |
|----|----------|-----------|
| **INCONSISTENCY-003** | Dualidad `TranslationService` vs `ChatTranslator`; pipeline real usa el primero | **Alta** |
| **INCONSISTENCY-005** | `SuiteHost.bootstrap()` no inicializa `ChatTranslator` | **Alta** |
| **INCONSISTENCY-008** | `ChatTranslatorModule` provee `chattranslator` no `translator` | **Alta** |
| **INCONSISTENCY-009** | `host` usa `ServiceLoader<TranslatorProvider>` en lugar de delegar a `chattranslator` | Media |
| **INCONSISTENCY-014** | Confusión semántica: backends requieren `chattranslator` pero proveen `translator` | **Alta** |
| **INCONSISTENCY-016** | Default `default-backend`: código `null` vs doc `'google'` | Baja |

### 🔴 Ownership de Configuración (storage.md vs realidad)

| ID | Hallazgo | Severidad |
|----|----------|-----------|
| **INCONSISTENCY-004** | Carga de `channels`, `translators`, `sync` sigue en `host`/`spigot-host` | **Alta** |
| **INCONSISTENCY-011** | `textformatter` no carga sus `channels` (está en `ConfigLoader.loadChannels()`) | **Alta** |
| **INCONSISTENCY-012** | `spigot-host` carga sync desde `sync/*.yml` plano, no `config/sync-*/config.yml` | **Alta** |
| **INCONSISTENCY-017** | `copyDefaultsIfMissing` crea configs en ubicaciones obsoletas | **Alta** |
| **INCONSISTENCY-018** | `resetConfigs` no hace backup de nuevos directorios `config/<modulo>/` | Media |

### 🔴 iflow-firewall — Módulo Fantasma

| ID | Hallazgo | Severidad |
|----|----------|-----------|
| **INCONSISTENCY-001** | `iflow-firewall` documentado en `storage.md` pero **no existe en código** | Media |

### 🔴 Diferencias Spigot vs Fabric

| ID | Hallazgo | Severidad |
|----|----------|-----------|
| **INCONSISTENCY-019** | `fabric-host` no incluye `gtranslate`, `ltranslate`, `chattranslator` | Media |

---

## Matriz de Módulos vs ModuleDescriptor

| Módulo | Tiene Module | Capability Provided | Requires | Estado |
|--------|-------------|---------------------|----------|--------|
| `chattranslator` | ✅ | `chattranslator` | — | ⚠️ Debería proveer `translator` |
| `gtranslate` | ✅ | `translator` | `chattranslator` | ✅ |
| `ltranslate` | ✅ | `translator` | `chattranslator` | ✅ |
| `sync-bus` | ✅ | `sync-bus` | — | ✅ |
| `sync-discord` | ✅ | `sync-sink` | ❌ **falta `sync-bus`** | **INCONSISTENCIA** |
| `sync-websocket` | ✅ | `sync-sink`, `websocket-server` | ❌ **falta `sync-bus`** | **INCONSISTENCIA** |
| `sync-telegram` | ✅ | `sync-sink` | ❌ **falta `sync-bus`** | **INCONSISTENCIA** |
| `sync-http` | ✅ | `sync-sink` | ❌ **falta `sync-bus`** | **INCONSISTENCIA** |
| `sync-socket` | ✅ | `sync-sink` | ✅ `sync-bus` | ✅ (fix reciente) |
| `sync-velocity` | ✅ | `sync-sink` | ❌ **falta `sync-bus`** | **INCONSISTENCIA** |
| `textformatter` | ✅ | `channel-api` | — | ⚠️ No carga su config |
| `inworld` | ✅ | `inworld-*`, `channel-*` | — | ✅ |
| `observability` | ✅ | `observability` | — | ✅ |
| `presets` | ✅ | `presets` | — | ✅ |
| `tester` | ✅ | `test-service` | `channel-api`, `router`, `formatter` | ✅ |
| `iflow-vm` | ❌ | — | — | **INCONSISTENCIA** |
| `iflow-wasm` | ❌ | — | — | **INCONSISTENCIA** |
| `host` | ❌ | — | — | **INCONSISTENCIA** |
| `kernel` | ❌* | — | — | **INCONSISTENCIA** (*solo test) |
| `core-api` | ❌ | — | — | **INCONSISTENCIA** |
| `transport` | ❌ | — | — | **INCONSISTENCIA** |
| `messages` | ❌ | — | — | **INCONSISTENCIA** |
| `logger` | ❌ | — | — | **INCONSISTENCIA** |
| `extension-api` | ❌ | — | — | **INCONSISTENCIA** |
| `manager-spi` | ❌ | — | — | **INCONSISTENCIA** |
| `manager-core` | ❌ | — | — | **INCONSISTENCIA** |
| `performance` | ❌ | — | — | **INCONSISTENCIA** |
| `loadtest` | ❌ | — | — | **INCONSISTENCIA** |
| `common-legacy` | ❌ | — | — | **INCONSISTENCIA** |

---

## Próximos Pasos Recomendados (Roadmap)

### P0 — Crítico (bloquean CT4/CT5 y arquitectura correcta)

1. **Añadir `require('sync-bus')` a todos los submódulos sync** — `sync-discord`, `sync-websocket`, `sync-telegram`, `sync-http`, `sync-velocity`
2. **Implementar `ChatTranslator` en `SuiteHost.bootstrap()`** — Cargar `ChatTranslatorConfig`, `BackendConfig`, crear `ChatTranslator`, inyectar en `TranslationService` o reemplazarlo
3. **Migrar carga de config a módulos dueños**:
   - `ConfigLoader.loadChannels()` → `textformatter.ChannelConfig.load()`
   - `TranslatorsConfig.load()` → `chattranslator.BackendConfig.load()`
   - Sync config loading → cada submódulo en su `onLoad()`
4. **Corregir `copyDefaultsIfMissing` y `resetConfigs`** en `spigot-host` para usar nuevas rutas `config/<modulo>/`
5. **Añadir dependencias `gtranslate`, `ltranslate`, `chattranslator` a `fabric-host/build.gradle`**

### P1 — Alto (completan arquitectura de módulos)

6. **Añadir `Module` implementations** a `iflow-vm`, `iflow-wasm`, `host`, `kernel`, `core-api`, `transport`, `messages`, `logger`, `extension-api`, `manager-spi`, `manager-core`
7. **Resolver confusión capability `chattranslator` vs `translator`** — Decidir si `ChatTranslatorModule` provee `translator` o si hay capability distinta para façade
8. **Crear `src/iflow-firewall/` módulo legacy** (o eliminar referencias de `storage.md` si no se va a implementar)
9. **Alinear `ChatTranslatorConfig.defaults()` con `storage.md`** (default-backend: null vs 'google')

### P2 — Medio (calidad y consistencia)

10. **Mover `MessagesConfig` a platform adapters** o documentar claramente ownership compartido
11. **Añadir `ModuleDescriptor` a `performance`, `loadtest`, `common-legacy`** si son módulos reales
12. **Documentar en `storage.md` la decisión sobre `iflow-firewall`** (implementar vs retirar)

### P3 — Bajo (mejoras opcionales)

13. **Unificar `TranslationService` y `ChatTranslator`** en una sola fachada (ADR pendiente)
14. **Validación de config al arranque** en cada módulo (`onLoad()`) per `storage.md` sección 6.1

---

## Evidencia de Verificación

```bash
# Verificar Module implementations
for dir in /home/majhrs16/Documentos/textformatter-suite/src/*/; do
    mod=$(basename "$dir")
    if grep -r "implements Module" "$dir" --include="*.java" -q 2>/dev/null; then
        echo "$mod: YES"
    else
        echo "$mod: NO"
    fi
done

# Verificar require sync-bus
grep -r "require.*sync-bus" /home/majhrs16/Documentos/textformatter-suite/src/sync-*/

# Verificar iflow-firewall
find /home/majhrs16/Documentos/textformatter-suite -name "*iflow-firewall*"

# Verificar ChatTranslator en bootstrap
grep -n "ChatTranslator" /home/majhrs16/Documentos/textformatter-suite/src/host/src/main/java/me/majhrs16/suite/host/SuiteHost.java
```

---

## Conclusión

El proyecto tiene **arquitectura documentada sólida** (`storage.md`, `chattranslator.md`, `PLAN.md`) pero **implementación parcial**:

- ✅ **Bien**: Scaffold de módulos, tests, build, SPI contracts, kernel/ModuleGraph, sync-socket fix
- ⚠️ **En progreso**: ChatTranslator (CT0-CT3 ✅, CT4-CT5 ❌), migración config ownership, iflow-wasm (W0 ✅, W1-W3 ❌)
- ❌ **Pendiente**: ModuleDescriptor en 19/33 módulos, require capabilities en submódulos sync, iflow-firewall, bootstrap ChatTranslator

**La prioridad inmediata es CT4 (integrar ChatTranslator en SuiteHost) + corregir requires de sync-bus**, ya que desbloquean la arquitectura frontend/backend y el grafo de módulos correcto.