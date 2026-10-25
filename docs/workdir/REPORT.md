# Reporte Detallado de Inconsistencias — TextFormatter Suite
**Para revisión y decisión manual**

> **Commit auditado:** `3aaba49`  
> **Fecha:** 2026-10-24  
> **Autor:** Auditoría automática (skill `auditar-proyecto`)  
> **Destino:** `~/reporte-inconsistencia-txf.md` — **EDITAR ESTE ARCHIVO PARA DECIDIR ACCIONES**

---

## INSTRUCCIONES DE USO

Para cada hallazgo, completa la columna **DECISIÓN** con una de:
- `FIX_NOW` — Corregir inmediatamente (P0)
- `FIX_LATER` — Programar para próxima iteración (P1/P2)
- `WONT_FIX` — No corregir (documentar por qué)
- `NEEDS_DESIGN` — Requiere decisión de diseño / ADR antes de actuar
- `DELEGATE` — Asignar a otra persona/equipo

Y opcionalmente **NOTAS** con tu razonamiento.

---

## 1. ARQUITECTURA DE MÓDULOS (Module System)

### INCONSISTENCY-002: Submódulos sync-* faltan require('sync-bus')
| Campo | Valor |
|-------|-------|
| **Severidad** | Alta |
| **Ubicación** | `src/sync-discord/SyncDiscordModule.java`, `src/sync-websocket/SyncWebSocketModule.java`, `src/sync-telegram/SyncTelegramModule.java`, `src/sync-http/SyncHttpModule.java`, `src/sync-velocity/SyncVelocityModule.java` |
| **Descripción** | 5 de 6 submódulos sync declaran `provide('sync-sink')` pero NO `require('sync-bus')`. Solo `sync-socket` lo tiene (fix reciente). El ModuleGraph no puede validar dependencias. |
| **Evidencia** | `grep -r "require.*sync-bus" src/sync-*/` → solo sync-socket |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-006: iflow-vm y iflow-wasm sin Module implementation
| Campo | Valor |
|-------|-------|
| **Severidad** | Media |
| **Ubicación** | `src/iflow-vm/`, `src/iflow-wasm/` |
| **Descripción** | Ninguno tiene clase que implemente `Module`. No son descubribles via ServiceLoader ni participan en ModuleGraph. `storage.md` los lista como "Módulos". |
| **Evidencia** | `grep -r "implements Module" src/iflow-vm/ src/iflow-wasm/` → no matches |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-007: 19/33 módulos carecen de ModuleDescriptor
| Campo | Valor |
|-------|-------|
| **Severidad** | Media |
| **Ubicación** | `host`, `transport`, `messages`, `logger`, `core-api`, `extension-api`, `manager-spi`, `manager-core`, `performance`, `loadtest`, `common-legacy`, `fabric-host`, `spigot-host`, `example-extension`, `kernel` (solo test), `iflow-vm`, `iflow-wasm`, `web-editor` |
| **Descripción** | Solo 14 de 33 módulos tienen ModuleDescriptor. El kernel no ve la mayoría de la suite. |
| **Evidencia** | Script de verificación incluido al final |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-013: kernel solo tiene Module en test fixtures
| Campo | Valor |
|-------|-------|
| **Severidad** | Baja |
| **Ubicación** | `src/kernel/src/test/java/me/majhrs16/suite/kernel/SpiFixtureModule.java` |
| **Descripción** | `SpiFixtureModule` existe solo en `src/test/`. El módulo kernel real no se registra en el grafo. |
| **Evidencia** | No hay Module en `src/main/` |
| **DECISIÓN** | [ ] FIX_NOW  [*] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-015: Contrato sync-bus existe pero no se usa
| Campo | Valor |
|-------|-------|
| **Severidad** | Media |
| **Ubicación** | `src/sync-bus/SyncBusModule.java` |
| **Descripción** | `SyncBusModule` provee `sync-bus` capability. Los submódulos deberían requerirlo, pero no lo hacen (ver INCONSISTENCY-002). |
| **Evidencia** | `SyncBusModule.java:23` - `provide('sync-bus')`; solo sync-socket requiere |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

---

## 2. CHATTRANSLATOR — FACHADA UNIFICADA (CT4/CT5 pendientes)

### INCONSISTENCY-003: Dualidad TranslationService vs ChatTranslator
| Campo | Valor |
|-------|-------|
| **Severidad** | **Alta** |
| **Ubicación** | `src/host/SuiteHost.java`, `src/host/config/TranslatorsConfig.java`, `src/spigot-host/TextFormatterSuitePlugin.java:297` |
| **Descripción** | Existen dos fachadas: `TranslationService` (core-api, usado por host) y `ChatTranslator` (nuevo módulo). Pipeline real crea `TranslationService` directamente, sin usar `ChatTranslator`. CT4 pendiente. |
| **Evidencia** | `TextFormatterSuitePlugin.java:297` - `new TranslationService(TranslatorsConfig.load(folder))`; `ChatTranslator` no se instancia |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-005: SuiteHost.bootstrap() no inicializa ChatTranslator
| Campo | Valor |
|-------|-------|
| **Severidad** | **Alta** |
| **Ubicación** | `src/host/SuiteHost.java` (método bootstrap) |
| **Descripción** | `SuiteHost.bootstrap()` no carga config de chattranslator ni crea `ChatTranslator`. Per `storage.md` sección 4.1, debería ejecutarse en fase "MÓDULOS CON DEPENDENCIAS". |
| **Evidencia** | Bootstrap solo carga HostConfig, ChannelRegistry, VmPolicyEngine, TextFormatter |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-008: ChatTranslatorModule provee 'chattranslator' no 'translator'
| Campo | Valor |
|-------|-------|
| **Severidad** | **Alta** |
| **Ubicación** | `src/chattranslator/ChatTranslatorModule.java:22` |
| **Descripción** | `ChatTranslatorModule` provee capability `chattranslator`. Backends (`gtranslate`, `ltranslate`) proveen `translator` y requieren `chattranslator`. Semántica confusa: façade no expone capability unificada. |
| **Evidencia** | `ChatTranslatorModule.java:22` - `provide('chattranslator')`; `GTranslateModule.java:22` - `provide('translator')` |
| **DECISIÓN** | [ ] FIX_NOW  [*] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-009: Host usa ServiceLoader<TranslatorProvider> en lugar de delegar a chattranslator
| Campo | Valor |
|-------|-------|
| **Severidad** | Media |
| **Ubicación** | `src/host/config/TranslatorsConfig.java:56-63` (`discoverProviders()`) |
| **Descripción** | `TranslatorsConfig` en host descubre backends via `ServiceLoader<TranslatorProvider>`. Nueva arquitectura: debería moverse a `chattranslator.BackendConfig`. Host no debería conocer SPI de traducción. |
| **Evidencia** | `TranslatorsConfig.java:56-63` - `discoverProviders()` usa `ServiceLoader<TranslatorProvider>` |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-014: Confusión semántica capabilities chattranslator vs translator
| Campo | Valor |
|-------|-------|
| **Severidad** | **Alta** |
| **Ubicación** | `src/gtranslate/GTranslateModule.java:23`, `src/ltranslate/LTranslateModule.java:23`, `src/chattranslator/ChatTranslatorModule.java:22` |
| **Descripción** | Backends requieren `chattranslator` y proveen `translator`. `chattranslator` capability sugiere que provee traducción, pero es façade coordinador. |
| **Evidencia** | `require('chattranslator')` + `provide('translator')` vs `provide('chattranslator')` |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-016: Default default-backend inconsistente (null vs 'google')
| Campo | Valor |
|-------|-------|
| **Severidad** | Baja |
| **Ubicación** | `src/chattranslator/ChatTranslatorConfig.java:88` vs `docs/prompts/diseno/storage.md:269` |
| **Descripción** | Código: `defaultBackend=null` (auto = primer disponible). Doc: `default-backend: 'google'`. Comportamiento por defecto diverge. |
| **Evidencia** | `ChatTranslatorConfig.java:88` - `defaultBackend=null`; `storage.md:269` - `default-backend: 'google'` |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

---

## 3. OWNERSHIP DE CONFIGURACIÓN (storage.md vs realidad)

### INCONSISTENCY-004: Carga de config centralizada en host/spigot-host
| Campo | Valor |
|-------|-------|
| **Severidad** | **Alta** |
| **Ubicación** | `src/host/config/ConfigLoader.java` (`loadChannels`), `src/host/config/TranslatorsConfig.java`, `src/spigot-host/TextFormatterSuitePlugin.java` (sync config loading) |
| **Descripción** | Per `storage.md` tabla "Estado Actual vs Objetivo" y sección 9, carga debería moverse a dueños: channels→textformatter, translators→chattranslator, sync→sync-bus/submódulos. Sigue en host. |
| **Evidencia** | `SuiteHost.bootstrap()` llama `ConfigLoader.loadChannels()` y `TranslatorsConfig.load()`; spigot-host carga sync/* manualmente |
| **DECISIÓN** | [ ] FIX_NOW  [*] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-011: textformatter no carga sus channels
| Campo | Valor |
|-------|-------|
| **Severidad** | **Alta** |
| **Ubicación** | `src/textformatter/TextFormatterModule.java`, `src/host/config/ConfigLoader.java` |
| **Descripción** | `textformatter` es dueño de `config/textformatter/channels/` per `storage.md`, pero `ConfigLoader.loadChannels()` está en host. `TextFormatterModule` no tiene método de carga. |
| **Evidencia** | `ConfigLoader.loadChannels()` en host; `TextFormatterModule` solo provee `channel-api` |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-012: spigot-host carga sync desde rutas planas obsoletas
| Campo | Valor |
|-------|-------|
| **Severidad** | **Alta** |
| **Ubicación** | `src/spigot-host/TextFormatterSuitePlugin.java:317-421`, `src/fabric-host/TextFormatterSuiteMod.java` |
| **Descripción** | Carga configs desde `folder.resolve('sync/websocket.yml')`, `folder.resolve('sync/tcp.yml')`, etc. Debería ser `config/sync-websocket/config.yml`, `config/sync-socket/tcp.yml`, cargado por submódulos. |
| **Evidencia** | Línea 317: `Path wsConfig = folder.resolve('sync/websocket.yml')`; línea 372: `Path tcpConfig = folder.resolve('sync/tcp.yml')` |
| **DECISIÓN** | [ ] FIX_NOW  [*] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-017: copyDefaultsIfMissing crea configs en ubicaciones obsoletas
| Campo | Valor |
|-------|-------|
| **Severidad** | **Alta** |
| **Ubicación** | `src/spigot-host/TextFormatterSuitePlugin.java:571-586` (`copyDefaultsIfMissing`) |
| **Descripción** | Copia defaults a `channels/...`, `translators/google.yml`, `sync/discord.yml`, etc. Debería copiar a `config/textformatter/channels/`, `config/chattranslator/translators/`, `config/sync-discord/`, etc. |
| **Evidencia** | Líneas 576-582: copia a `channels/`, `translators/`, `sync/` (plano) |
| **DECISIÓN** | [ ] FIX_NOW  [*] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

### INCONSISTENCY-018: resetConfigs no hace backup de nuevos directorios config/<modulo>/
| Campo | Valor |
|-------|-------|
| **Severidad** | Media |
| **Ubicación** | `src/spigot-host/TextFormatterSuitePlugin.java:590-615` (`resetConfigs`) |
| **Descripción** | Solo respalda `config.yml`, `messages.yml`, `channels/`, `translators/`. Faltan: `config/chattranslator/`, `config/sync-bus/`, `config/sync-discord/`, `config/sync-websocket/`, `config/sync-telegram/`, `config/sync-http/`, `config/sync-socket/`, `config/inworld/`, `config/observability/`, `config/iflow-firewall/`. |
| **Evidencia** | Líneas 594-608: lista incompleta |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [ ] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | |

---

## 4. IFLOW-FIREWALL — MÓDULO FANTASMA

### INCONSISTENCY-001: iflow-firewall documentado pero no existe
| Campo | Valor |
|-------|-------|
| **Severidad** | Media |
| **Ubicación** | `docs/prompts/diseno/storage.md` (lines 36-37, 114, 168-191, 456) |
| **Descripción** | `storage.md` describe `config/iflow-firewall/rules.yml` como módulo legacy para editor web, con ownership y ciclo de vida. **NO existe `src/iflow-firewall/` en código**. Reset incluye este directorio pero no hay módulo que lo gestione. |
| **Evidencia** | `find /home/majhrs16/Documentos/textformatter-suite -name "*iflow-firewall*"` → no output |
| **DECISIÓN** | [ ] FIX_NOW  [*] FIX_LATER  [ ] WONT_FIX  [*] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | Hay que restaurar el codigo original del iflow legacy desde el historial de git. |

---

## 5. DIFERENCIAS SPIGOT VS FABRIC

### INCONSISTENCY-019: fabric-host sin dependencias de traducción
| Campo | Valor |
|-------|-------|
| **Severidad** | Media |
| **Ubicación** | `src/fabric-host/build.gradle` |
| **Descripción** | `fabric-host/build.gradle` NO incluye `gtranslate`, `ltranslate`, `chattranslator`. `spigot-host` SÍ los incluye (líneas 42-43). Diferencia de comportamiento en runtime. |
| **Evidencia** | `spigot-host/build.gradle:42-43` - gtranslate, ltranslate; `fabric-host/build.gradle`: ausentes |
| **DECISIÓN** | [*] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [*] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | Para empezar, por puro disenio clean+hexa, tanto spigot como fabric NO deberian incluir ningun modulo, pues estos son agnosticos a la plataforma. |

---

## 6. OTROS HALLAZGOS MENORES

### INCONSISTENCY-020: MessagesConfig ownership ambiguo
| Campo | Valor |
|-------|-------|
| **Severidad** | Baja |
| **Ubicación** | `src/host/config/MessagesConfig.java`, `src/spigot-host/TextFormatterSuitePlugin.java:296` |
| **Descripción** | `MessagesConfig` está en `host` (shared) pero `storage.md` dice que `messages.yml` pertenece a platform adapters. Spigot-host lo usa correctamente, pero ubicación de la clase es ambigua. |
| **Evidencia** | `storage.md:196-208` - "Dueño: Cada platform adapter"; `MessagesConfig.java` en host module |
| **DECISIÓN** | [ ] FIX_NOW  [ ] FIX_LATER  [ ] WONT_FIX  [*] NEEDS_DESIGN  [ ] DELEGATE |
| **NOTAS** | No puede haber un directorio por cada plataforma, se deberia abstraer en un mismo archivo para todas las plataformas, y ese es <manager>/config/suite/ |

---

## SCRIPTS DE VERIFICACIÓN PARA VALIDAR FIXES

```bash
# 1. Verificar Module implementations en todos los módulos
for dir in /home/majhrs16/Documentos/textformatter-suite/src/*/; do
    mod=$(basename "$dir")
    if grep -r "implements Module" "$dir" --include="*.java" -q 2>/dev/null; then
        echo "$mod: YES"
    else
        echo "$mod: NO"
    fi
done

# 2. Verificar require sync-bus en todos los submódulos sync
grep -r "require.*sync-bus" /home/majhrs16/Documentos/textformatter-suite/src/sync-*/

# 3. Verificar iflow-firewall
find /home/majhrs16/Documentos/textformatter-suite -name "*iflow-firewall*"

# 4. Verificar ChatTranslator en SuiteHost.bootstrap
grep -n "ChatTranslator" /home/majhrs16/Documentos/textformatter-suite/src/host/src/main/java/me/majhrs16/suite/host/SuiteHost.java

# 5. Verificar defaults de ChatTranslatorConfig
grep -A5 "defaults()" /home/majhrs16/Documentos/textformatter-suite/src/chattranslator/src/main/java/me/majhrs16/suite/chattranslator/ChatTranslatorConfig.java

# 6. Verificar dependencias fabric-host
grep -E "gtranslate|ltranslate|chattranslator" /home/majhrs16/Documentos/textformatter-suite/src/fabric-host/build.gradle

# 7. Verificar copyDefaultsIfMissing rutas
grep -A20 "copyDefaultsIfMissing" /home/majhrs16/Documentos/textformatter-suite/src/spigot-host/src/main/java/me/majhrs16/suite/spigothost/TextFormatterSuitePlugin.java

# 8. Verificar resetConfigs rutas
grep -A30 "resetConfigs" /home/majhrs16/Documentos/textformatter-suite/src/spigot-host/src/main/java/me/majhrs16/suite/spigothost/TextFormatterSuitePlugin.java

# 9. Build + tests + JaCoCo
export JAVA_HOME=/opt/javac/x64/21
cd /home/majhrs16/Documentos/textformatter-suite
./gradlew build test jacocoTestCoverageVerification --no-daemon
```

---

## RESUMEN DE DECISIONES PENDIENTES

| Cuenta | Categoría |
|--------|-----------|
| 5 | **Alta** (bloquean arquitectura correcta) |
| 8 | **Media** |
| 4 | **Baja** |
| **17** | **Total items para decidir** |

> **Próximo paso:** Completa la columna **DECISIÓN** para cada hallazgo. Luego ejecuta los scripts de verificación para validar los fixes implementados.