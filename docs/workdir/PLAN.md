# PLAN — TextFormatter Suite

> **Obligatorio leer y seguir [La Filosofia](/docs/prompts/diseno/philosophy.md)** antes de trabajar en este documento o en el código.
>
> Documento vivo: reléelo antes de cada sesión de trabajo para no perder el rumbo.
> Última actualización: 2026-11-06 (**P0.1, P0.4, P0.6 y P0.8 cerrados; P0.4 purgó aliases, constructores de compatibilidad, `%s`, `reset`, W2, etiquetas `F7+`, versiones `2.1`/`3.0` y metadatos históricos; CoreTranslator sigue como expansión PAPI legacy `%cot_*` (ADR-035); contrato mínimo `>= 1.0.0` con techo en el host; P0.2, P0.3, P0.5, P0.7, P0.8 y P0.8b cerrados con gate verde. P0.8 podó los ADR duplicados en este PLAN, alineó `docs/prompts/` (raíz de comandos, fases del editor y régimen pre-1.0) y dejó `ADR.md` sin planes ni fases. Se registra `PL-1` —pipeline secuencial configurable— como feature propuesta, bloqueada por ADR y por el cierre de la purga P0; no hay código ni contrato nuevo. P0.6 cerró la auditoría integral (TF-001…TF-012) y reemplazó `AUDITORIA.md` por completo.**)

> **Régimen pre-1.0 (2026-11-04, ADR-034):** el proyecto no tiene versión estable ni producción.
> Todos los módulos, componentes, contratos, artefactos y configuraciones se alinean a la base
> `1.0` (`1.0.0` en artefactos, `config-version: 1.0` en YAML). No se mantiene
> retrocompatibilidad de features ni contratos, código de compatibilidad, aliases legacy ni ciclos
> de deprecación. Los módulos legacy independientes (`iflow-firewall`, `CoreTranslator`) no son
> shims y se rigen por C6. Los *breaking changes* son el modo normal hasta la `1.0` estable.
> `1.0.0` es la etiqueta de release
> de la base integrada; el betatesting/E2E en servidores reales Spigot/Paper, Fabric y Velocity se
> ejecuta **después** y sus fallos se corrigen en la línea beta. `1.0` es la versión estable,
> declarada solo cuando el proyecto completo está consolidado y sin errores críticos de diseño.
> `AUDITORIA.md` es efímera: contiene solo la auditoría vigente y se reemplaza al cerrar sus bugs
> con tests; su ciclo se declara en ADR-034 y se menciona desde este PLAN. La hoja de ruta inmediata
> está en «Purga pre-1.0 — hoja de ruta inmediata».

> **Hub iFlow (2026-11-04, ADR-032):** se añadió el paquete `iflow` como hub de selección. Los
> motores publican `IflowBackend`; el hub publica `IflowHub` y `Router`, elige un backend y aplica
> fallback configurable. Configuración canónica: `config/iflow/config.yml` con
> `engine: iflow-firewall|iflow-vm|iflow-wasm` y `fallback-on-unavailable`. Default
> `iflow-firewall`. `SuiteRuntime` exige `IflowHub`; un motor no puede publicar un `Router` que
> sustituya al hub. `iflow.engine.kind` fue eliminado en P0.3 (ADR-034).
> `iflow-firewall`, `iflow-vm` e `iflow-wasm` ya publican su backend; Wasm exige SHA-256 y recarga automáticamente.

> **Estado actual (2026-11-04).** Base integrada en construcción; no hay versión estable ni
> producción. Cerrados en su alcance: comandos neutrales y raíz configurable, sincronizadores de
> plataforma, empaquetado reproducible, paquetes de terceros y presets. ADR-027 implementado: adquisición,
> commit con journal y activación separada; la etapa 2 es el único rollback automático. `iflow-vm`
> (v2 de 16 bits, objetivo v3) e `iflow-wasm` (Chicory) son líneas paralelas; W3.5 (paridad `.flow`
> v3/VM) sigue pendiente y V6 (compilador C) está bloqueado por V1 y V5. `CoreTranslator` se
> restauró como expansión PAPI legacy `%cot_*` (ADR-035). El E2E real es posterior a `1.0.0`.
> El historial cerrado vive en Git; este PLAN solo conserva estado y trabajo activo.

---

## Purga pre-1.0 — hoja de ruta inmediata (ADR-034)

> **Directriz de corto plazo:** no se inicia una feature nueva hasta cerrar la purga de
> compatibilidad y los bugs estructurales P0/P1 de la auditoría vigente. Cada tarea termina con
> código, tests, documentación y gate verde; no se aceptan cambios a medias ni “arreglos” que
> conserven el camino viejo.
>
> **Excepción registrada (2026-11-05):** `PL-1` (pipeline secuencial configurable) queda
> **propuesta y documentada**, pero **no se inicia** hasta que un ADR aceptado sustituya o enmiende
> ADR-019, ADR-024/025 y ADR-032 en lo relativo al composition root, las dependencias entre
> features y el orden del pipeline, y hasta que cierre la purga P0 (ADR-034). La nota de esta
> sección no autoriza implementación ni cambios en `docs/prompts/`.

| # | Tarea | Criterio de cierre | Estado |
|---|---|---|---|
| **P0.1** | Inventario de versiones y compatibilidad | Lista `archivo:línea` de todo `2.x`/`3.x`, `config-version`, aliases legacy, rutas planas, adaptadores, shims y módulos *deprecated*. Sin elementos sin clasificar. | Completado (2026-11-05; inventario integrado en este PLAN) |
| **P0.2** | Reset de versiones a la base `1.0` | `build.gradle` raíz y de módulos en `1.0.0`; manifiestos, capabilities y contratos `*-api` en `1.0.0`; todo `.yml` con `config-version: 1.0` en la raíz, preferentemente al final. `plugin.yml` queda excluido. Si falta `config-version`, el parser falla con un error explícito. Un sufijo de build no cambia la base funcional, pero su publicación se rige por la política de artefactos. | Completado (2026-11-04) |
| **P0.3** | Purga de configuración legacy | Eliminar `iflow.engine.kind`, `base-name` (si queda alguno; el código actual ya lo trata como retirado), lectores de la raíz plana de `config/` (`config/config.yml`, `config/channels/`, `config/translators/`, `config/sync/`, `config/rules.yml`, `config/inworld.yml`) y cualquier fallback que acepte el esquema viejo. Los `config/<module>/config.yml` canónicos se conservan. Las rutas incorrectas se ignoran o se rechazan explícitamente. | Completado (2026-11-04) |
| **P0.4** | Purga de compatibilidad y módulos legacy | Eliminar adaptadores, aliases, dobles lectores, puentes de conversión y fachadas de compatibilidad. Restaurar `CoreTranslator` como módulo legacy independiente, marcado *deprecated* y Bukkit-only por PlaceholderAPI (ADR-035); no como shim ni recuperando `suite-common-legacy`. El `LegacyBridge` de `7dd164a4^` no se restaura. Actualizar todos los consumidores en el mismo cambio. | Completado (2026-11-05) |
| **P0.5** | Purga de tests de compatibilidad | Eliminar o reescribir los tests que afirman el contrato viejo o la migración legacy. Añadir regresiones que demuestren rechazo o ignorancia del camino viejo, tests de `config-version`, tests de contrato `>= 1.0.0` y tests de `CoreTranslator`. | Completado (2026-11-04) |
| **P0.6** | Auditoría vigente | `AUDITORIA.md` contiene la auditoría integral de 2026-11-06 (TF-001…TF-012), con cobertura, evidencia, matriz y roadmap; el ciclo documental anterior (D-001…D-010) queda cerrado. Este PLAN mantiene el estado operativo y el mapeo P0/P1/P2 ↔ Alta/Media/Baja. Los hallazgos P0/P1 se corrigen con test de regresión antes de avanzar. | Completado (2026-11-06; informe reemplazado por completo) |
| **P0.7** | Gate completo | `./gradlew build checkArchitecture checkLocks --no-daemon` en verde, sin tests omitidos ni fallos tolerados. | Completado (2026-11-04) |
| **P0.8** | Coherencia documental (P0) | `philosophy.md`, `ADR.md`, `PLAN.md`, `contexto/proyecto.md`, `diseno/storage.md`, `especificaciones/*`, `iflow-cpu-design.md` y las skills: una sola base `1.0`, contrato `[1.0.0, host]`, un solo camino vigente, sin planes/fases dentro del ADR, sin rutas ni migraciones legacy y con poda del historial cerrado. | Completado (2026-11-05) |
| **P0.8b** | Coherencia de la wiki | Actualizar `docs/wiki` para que no presente `iflow.engine.kind`, exclusividad de instalación ni pruebas reales antiguas como estado vigente. La wiki queda fuera de la auditoría documental de 2026-11-04. | Completado (2026-11-04) |

### Inventario P0.1 — versiones y compatibilidad (2026-11-05)

> Evidencia de cierre de P0.1. Alcance: código Java, recursos YAML, editor web, Gradle y
> documentación normativa. Se excluyen `node_modules/`, `build/`, `.git/` y los workflows de CI.
> Las referencias son `archivo:línea`; si una línea se mueve, la cadena citada sigue siendo la
> evidencia.

**Resultado**

| Comprobación | Resultado |
|---|---|
| Versión base de la suite | `1.0.0` en `build.gradle:6`; manifiestos y capabilities en `1.0.0` |
| Versión de esquema YAML | `1.0`; contrato único en `ConfigVersion` (`src/core-api/.../ConfigVersion.java:16`) |
| Archivos `.yml` de configuración | 34, todos con `config-version: 1.0` en la raíz y como última línea |
| Excepción de `config-version` | `src/spigot-host/src/main/resources/plugin.yml` (descriptor de plugin) |
| Aliases de configuración legacy | 0 en producción; eliminados en P0.4 y cubiertos por tests de rechazo |
| Lectores de rutas planas | 0 en producción; los tests demuestran que las rutas planas se ignoran |
| Adaptadores, shims y fachadas de compatibilidad | 0; `LegacyBridge`/`suite-common-legacy` no existen |
| Módulos *deprecated* | Solo `CoreTranslator` (`@Deprecated`); `iflow-firewall` es motor legacy permitido, no shim |
| Deuda de metadatos | Ninguna: las entradas propias `2.1.0-SNAPSHOT` se retiraron de `verification-metadata.xml` |
| Deuda documental | `docs/workdir/REPORT.md` es histórico; la guía manual de migración no implica soporte runtime |

**Versiones `2.x` / `3.x`**

| Evidencia | Clasificación | Estado |
|---|---|---|
| `build.gradle:6` — `1.0.0` | Base de release | Vigente |
| `src/core-api/.../ConfigVersion.java:22-23` — mensaje exacto de ausencia | Esquema `1.0` | Vigente |
| `src/transport/.../HttpTransport.java:164` — `TextFormatterSuite/1.0.0` | User-Agent de la suite | Corregido en P0.4 (antes `2.1`) |
| `src/textformatter/.../TextFormatterModule.java:12-13` — capability `1.0.0` | Contrato de módulo | Corregido en P0.4 (antes `3.0.0`) |
| `src/web-editor/js/model.js:372` y `src/host/.../web-editor-schema/model.js:454` — `suite-version: 1.0.0` | Manifiesto del editor | Corregido en P0.4 (antes `2.1.0`) |
| `src/web-editor/js/kernel.js:12-18`, `js/core.js:116`, `index.html:14` y `prototype.html` | Versión mostrada por la UI | Corregido en P0.4 (antes `2.1.0`/`v2.1`) |
| `src/web-editor/js/validate.js:107`, `js/paths.js:336`, `paths.json:300` y `src/host/.../web-editor-schema/*` | Etiquetas `F7+` de proxy/transform sustituidas por capability o «plugin Velocity» | Corregido en P0.4 |
| `src/*/build.gradle` — comentarios `F5`/`F6`/`F8` | Etiquetas históricas de fase en comentarios de build | Deuda cosmética; no afectan al runtime |
| `src/web-editor/js/model.js:3,371` y `docs/prompts/especificaciones/config-schema-v2.2.md:8` | Revisión del schema del editor (`v2.2`) | Eje distinto de la versión de la suite; documentado y permitido |
| `src/iflow-wasm/README.md:31,289` — ISA v2 / paridad `.flow` v3 | Nota histórica y trabajo W3.5 | Sin autoridad de contrato; W3.5 bloqueado |
| `src/core-api/src/test/.../SemVerTest.java:13-33` — `2.1.0` | Datos del comparador SemVer | Test intencional |
| `src/extension-api/src/test/.../ExtensionMetadataTest.java:45-58`, `ExtensionContractTest.java:28` | Versiones arbitrarias de prueba | Test intencional |
| `src/manager-spi/src/test/.../VersionContractTest.java`, `VersionTest.java`, `ManifestParserTest.java` | SemVer 2.0.0 y versiones de paquete de prueba | Test intencional |
| `src/manager-core/src/test/.../LocalPackageDatabaseTest.java:162,246` | Versiones de paquete de prueba | Test intencional |
| `gradle/verification-metadata.xml` | Se retiraron las entradas propias `2.1.0-SNAPSHOT` | Limpieza P0.4; no participan en la resolución actual |

No quedan cadenas `2.1.0`, `3.0.0` ni `TextFormatterSuite/2.1` en código de producción, recursos
del host o UI del editor. Las apariciones restantes son tests, dependencias de terceros o la
revisión independiente del schema del editor.

**`config-version` por archivo**

Los 34 YAML de configuración tienen `config-version: 1.0` como clave raíz y última línea no vacía:

- Defaults de `spigot-host`: `config.yml:25`, `commands.yml`, `messages.yml:53`, `iflow.yml`,
  `rules.yml:14`, canales, `sync/discord.yml`, `sync/websocket.yml`, `translators/google.yml`.
- Defaults de `fabric-host`: el mismo conjunto, con sus rutas canónicas.
- Defaults de módulos: `inworld.yml`, `sync-bukkit/config.yml`, `sync-fabric/config.yml`,
  `sync-velocity/sync/velocity.yml`.
- Fixtures del editor: `src/host/src/test/resources/editor-default/config/suite/config.yml:16` y
  los cuatro canales de `config/textformatter/channels/`.
- Excluido por contrato: `src/spigot-host/src/main/resources/plugin.yml`.

La comprobación automatizada usada para este inventario fue:

```text
34 archivos; todos con config-version; todos en la última línea no comentada.
```

**Aliases y compatibilidad eliminados en P0.4**

| Elemento | Evidencia actual | Regresión |
|---|---|---|
| `iflow.engine` ya no normaliza `firewall`/`vm`/`wasm` ni lee `default-backend` | `src/iflow/.../IflowConfig.java:61-62,86-89` | `IflowConfigTest.java:46,53` |
| `fallback` ya no es alias de `fallback-on-unavailable` | `src/iflow/.../IflowConfig.java:62` | `IflowConfigTest.java:53` |
| `transform: "<...>"` en `rules.yml` ya no se convierte en `Rewrite` | `src/iflow-firewall/.../RuleConfigLoader.java`; `Rule.Builder.transform(String)` eliminado | `RuleConfigLoaderTest.java:181` |
| Constructores de compatibilidad sin uso eliminados | `RoutingResult`, `DispatchReport`, `HttpSink` | Compilación y tests de host/sync |
| `ChatTranslator` ya no tiene constructor de dos argumentos | `ChatTranslatorTest.java:51-52` usa configuración explícita | Test de ChatTranslator |
| `WasmContract` ya no tiene constructor W2 sin firmas | `WasmContract`; tests con constructor canónico | `WasmModuleLoaderTest.java` |
| `ExecutionContext.reset()` eliminado; se usa `initialize()` | `ExecutionContextPool.java` | `ExecutionContextTest.java` |
| `MessagesCatalog.getAllMessages()` y placeholders `%s` eliminados | `MessagesCatalog.java`; `MessagesConfig.java` | `MessagesCatalogTest.java` |
| Javadocs de rutas heredadas corregidos | `BackendConfig`, `ChatTranslator`, `ChatTranslatorConfig`, `SocketConfig`, `WebSocketSyncConfig` | Revisión de documentación |

`CoreTranslatorPlaceholders` conserva `sendDiscord;<uuid>[;<channels>]` porque pertenece al módulo
legacy permitido por ADR-035; no es una fachada de compatibilidad del runtime nuevo.

**Rutas planas y dobles lectores**

No hay lectores de rutas planas en producción. Las rutas canónicas son:

- `config/suite/{config,commands,messages}.yml`
- `config/textformatter/channels/*.yml`
- `config/chattranslator/{config.yml,translators/*.yml}`
- `config/sync-*/…`
- `config/iflow/config.yml`
- `config/iflow-firewall/rules.yml`
- `config/inworld/config.yml`
- `manager/repo.yml`

Las rutas planas aparecen solo en tests que demuestran que se ignoran:

- `src/sync-common/src/test/.../SyncConfigPathsTest.java:42-57`
- `src/sync-websocket/src/test/.../WebSocketSyncConfigTest.java:88`
- `src/sync-socket/src/test/.../SocketConfigTest.java:90`
- `src/suite-runtime/src/test/.../DefaultConfigInstallerTest.java` (rutas legacy ignoradas)
- `src/suite-runtime/src/test/.../CommandRootConfigTest.java:53` (`base-name` retirado)
- `src/core-api/src/test/.../ConfigVersionTest.java:52` (`2.0` y abreviaturas rechazadas)

**Adaptadores, puentes y shims**

| Elemento | Clasificación |
|---|---|
| `src/spigot-host/.../bridge/NmsLocaleBridge.java:23` | Puente de plataforma Bukkit/NMS; integración vigente, no compatibilidad |
| `src/spigot-host/.../SpigotPlaceholderResolver.java:19` | Integración con PlaceholderAPI; vigente, no shim |
| `src/coretranslator/.../CoreTranslatorModule.java:14` | Mención histórica a `LegacyBridge`; el shim no existe |
| `src/iflow-firewall/.../IflowFirewallModule.java:10` | Motor SpEL legacy independiente permitido por ADR-032 |
| `src/iflow/.../IflowBackendManager.java:23` | Orden de fallback del hub; política vigente, no compatibilidad |
| `src/suite-runtime/.../SuiteRuntime.java:243-253` | El runtime exige `IflowHub`; no acepta un `Router` suelto |

Las implementaciones `sync-*`, `gtranslate` y `ltranslate` son módulos de integración actuales, no
adaptadores de versiones anteriores.

**Módulos *deprecated***

| Módulo | Evidencia | Estado |
|---|---|---|
| `CoreTranslator` | `src/coretranslator/.../CoreTranslatorModule.java:19-20` y las cuatro clases `@Deprecated` | Legacy independiente, Bukkit-only por PlaceholderAPI; ADR-035 |
| `iflow-firewall` | `src/iflow-firewall/.../IflowFirewallModule.java:10` | Motor legacy permitido; no está `@Deprecated` ni es shim |

No hay otros `@Deprecated` en `src/*/src/main`.

**Deuda abierta asignada**

1. **P0.8 (resuelto, 2026-11-05):** `docs/wiki/12-Migration-Guide.md` se conserva como guía de
   usuario para recrear el layout canónico; declara explícitamente que el runtime no migra y no
   introduce lectores legacy. No es contrato ni estado vigente.
2. **W3.5:** la mención a ISA v2 en `src/iflow-wasm/README.md` es histórica y debe resolverse con
   la matriz de paridad `.flow` v3/VM.

**Estado de la auditoría vigente (2026-11-06):** auditoría integral cerrada, HEAD `1cc3a77f`.
`AUDITORIA.md` contiene el informe vigente (TF-001…TF-012), con cobertura, matriz, roadmap y
evidencia ejecutada. El ciclo documental anterior (D-001…D-010, HEAD `c5992230`) quedó cerrado y su
informe fue reemplazado por completo (C14/ADR-034). Mapeo de severidades: P0 = Alta, P1 = Media,
P2 = Baja. El cierre se rastrea en P0.6.

**Nota sobre la cita a `P0.1-inventory.md`:** el informe anterior remitía a
`docs/workdir/P0.1-inventory.md`, ruta que no existe; el inventario vigente está integrado en este
PLAN. El informe de 2026-11-06 ya no contiene esa cita. No se crea un archivo auxiliar.

**Orden de trabajo:** purga P0 → W3.5 (feature nueva + fix masivo) → V6 (bloqueado por V1 y V5).

**Cierre del ciclo anterior:** D-009 quedó resuelto. `IflowBackendManager` aplica la prioridad
explícita `iflow-firewall` → `iflow-vm` → `iflow-wasm`; el test de aceptación está en `IflowHubTest`.

**Deuda conocida tras P0.2/P0.3/P0.4 (2026-11-05):**

- `iflow-firewall` sigue siendo un módulo legacy independiente con expresiones SpEL; no es shim
  ni compite con el hub, que lo selecciona como backend.
- `CoreTranslator` fue eliminado en `7dd164a4`; se restauró la feature real de ChatTranslator
  (expansión PAPI `%cot_*`) en `src/coretranslator`. El `LegacyBridge` y `suite-common-legacy` no
  se recuperan (ADR-035).
- La wiki (`docs/wiki/`) quedó alineada en P0.8b: 17 páginas revisadas, sin duplicar ADRs,
  sin comandos inexistentes y sin `iflow.engine.kind` presentado como vigente.
- P0.1 cerró el inventario y lo integró en este PLAN; P0.4 eliminó los aliases de iFlow, el
  `transform` legacy, los constructores de compatibilidad sin uso, `%s`, `reset`, W2, las
  etiquetas `F7+` de la UI, las versiones `2.1`/`3.0` y los metadatos propios
  `2.1.0-SNAPSHOT`. P0.8 y P0.6 cerrados.
- W3.5 espera la paridad `.flow` v3/VM y V6 sigue bloqueado por V1 y V5.

### Después de 1.0.0 — betatesting/E2E real

La base `1.0.0` se alcanza con la suite integrada; el betatesting/E2E se ejecuta **después** y no
forma parte del cierre de la purga pre-1.0. Sin este ciclo no se declara estabilidad ni producción.

| # | Tarea | Criterio de cierre | Estado |
|---|---|---|---|
| **E2E.1** | Betatesting/E2E posterior a 1.0.0 | Probar manualmente todas las features de la suite en servidores reales Spigot/Paper, Fabric y Velocity, con matriz de versiones y evidencia. No es un smoke test de carga. Registrar resultado y declarar **desconocida** toda combinación no probada. Los fallos se corrigen en la línea beta; la estabilidad/producción se declara solo al cerrar este ciclo. | Pendiente |

---

### Feature propuesta — pipeline secuencial configurable (PL-1, 2026-11-05)

> **Estado:** propuesta, no implementada y no contractual. **ADR-036 redactado (2026-11-06),
> pendiente de aceptación explícita.** Sustituye o enmienda ADR-019 (composition root y
> dependencias), ADR-024/025 (puertos neutrales y adapters), ADR-032 (hub iFlow) y ADR-008
> (backend→fachada) en lo relativo a los requisitos entre features y al orden del pipeline; adopta
> ADR-031 sin cambios. **No se inicia** hasta que el usuario acepte ADR-036 y se cierre la purga P0
> (ADR-034). Cualquier cambio en `docs/prompts/` requiere autorización explícita.

#### Hallazgo estructural

Hoy la suite no es una colección de módulos opcionales: el grafo de módulos convierte features en
requisitos de otras features y el orden de procesamiento está cableado en código. Evidencia
verificada el 2026-11-05 sobre `2f5d3b27`:

| Hecho verificado | Evidencia | Efecto |
|---|---|---|
| `textformatter` es el único proveedor de `channel-api` | `TextFormatterModule.java:24`; `TextFormatterActivator.java:32` | El catálogo de canales y su capability pertenecen a un módulo opcional. |
| Los tres motores iFlow requieren `channel-api` | `IflowFirewallModule.java:27`; `IflowVmActivator.java:35`; `IfowWasmModule.java:23`; `IflowWasmActivator.java:34` | Instalar solo iFlow deja los tres motores inactivos o sin backend activo. |
| `inworld`, `sync-bukkit`, `sync-fabric` y `tester` también requieren `channel-api` | `InWorldModule.java:27`; `SyncBukkitActivator.java:45`; `SyncFabricActivator.java:46`; `TesterModule.java:25` | Cuatro features más dependen de que `textformatter` esté instalado. |
| `SuiteRuntime` exige servicios concretos para construir el pipeline | `SuiteRuntime.java:203-211`; `resolveRouter()` en `245-253` | Sin `ChannelRegistry`, `TranslationService`, `IflowHub`, `MessageRenderer`, `ChatDelivery`, `ActorDirectory` o `PermissionChecker` no hay pipeline utilizable. El fallo se registra como «pipeline unavailable» (`SuiteRuntime.java:225-232`), no como arranque funcional. |
| El orden del pipeline está hardcodeado | `SuiteHost` (`SuiteHost.java:150-162`) y `MessageDispatcher` (`MessageDispatcher.java:40`) | No existe una lista ordenada configurable; el orden lo imponen el cableado y el grafo. |
| El catálogo de canales vive en `textformatter` | `ChannelConfig.java:22,73`; `TextFormatterActivator.java:34-39` | Un dominio neutral (canales, permisos y políticas de canal) pertenece a una feature. |
| La ruta `config/textformatter/channels/` está fijada fuera del módulo | `FilePresetTarget.java:96`; `DefaultConfigInstaller.java:54-57`; `config-schema-v2.2.md`; `storage.md` | Mover el catálogo obliga a tocar presets, instalador, esquema y documentación. |
| Los backends de traducción requieren `chattranslator` | `GTranslateModule.java:23`; `LTranslateModule.java:23`; `CoreTranslatorModule.java:32`; ADR-008 | La relación backend→fachada es hoy un requisito de activación entre features; PL-1 debe decidir si un backend es instalable solo o si la fachada es su único punto de entrada. |
| El dispatcher conserva semántica de routing retirada | `MessageDispatcher.java` procesa `PolicyTarget.CHANNEL_REDIRECT` y reentrega; `PolicyTarget.java:23` conserva el valor | ADR-031 retira `REDIRECT`/`CHANNEL_REDIRECT`; el rediseño del pipeline debe alinear el código con ese contrato. |

Matiz importante: `textformatter` sí tolera la ausencia de `ChatTranslator`
(`TextFormatterActivator.java:48` usa `TextFormatters.emptyTranslation()`), de modo que el
acoplamiento no es de compilación de TextFormatter hacia ChatTranslator. El acoplamiento real está
en el grafo de requisitos y en el composition root: el runtime no tolera la ausencia de
`ChannelRegistry`, de `TranslationService` ni del hub iFlow para construir el pipeline.

`config/channels/` (ruta plana) está prohibida por P0.3/ADR-034 y no puede reutilizarse como ruta
canónica del catálogo neutral.

#### Principio propuesto

1. **La suite arranca y opera sin features.** El núcleo (comandos, ciclo de vida, registro de
   servicios, configuración y captura de plataforma) no exige ningún módulo de feature.
2. **Ninguna feature requiere otra feature.** Un módulo solo puede requerir contratos neutrales del
   núcleo o del runtime; nunca la capability de otro módulo opcional.
3. **La ausencia de una etapa es un paso explícito, no un fallo ni un no-op silencioso.** El mensaje
   continúa sin esa transformación y la traza registra la etapa como ausente/saltada.
4. **El pipeline es secuencial e inyectivo.** Cada etapa recibe el mensaje, lo transforma y lo
   entrega a la siguiente. El orden se declara en configuración; no se deduce del grafo de
   dependencias ni del orden de activación.
5. **El grafo de módulos se conserva para el ciclo de vida y los servicios neutrales**, pero no
   impone el orden del pipeline ni convierte una feature en requisito de otra.
6. **El catálogo de canales es neutral.** Vive en el núcleo/runtime o viaja en el contexto del
   pipeline; no pertenece a `textformatter` ni a ningún módulo opcional.
7. **Sin doble camino.** La lista configurada es la única fuente de orden; no se conserva el
   pipeline cableado como fallback.

#### Semántica de la sección `pipeline`

La configuración canónica de la suite es `config/suite/config.yml`. Se propone una clave
`pipeline` con los `moduleId` en orden de ejecución:

```yaml
# Ejemplo, no orden fijo.
pipeline:
  - textformatter
  - iflow
  - chattranslator
  - sync-bus
config-version: 1.0
```

Reglas propuestas, sujetas al ADR:

- `pipeline` es el nombre canónico; `workflow` no se acepta como alias (ADR-034 prohíbe aliases).
- El orden es literal: la primera entrada procesa primero y la última cierra el recorrido.
- Una entrada debe identificar una etapa publicada por un módulo. Nombre de módulo y nombre de
  etapa pueden coincidir; si un módulo publica varias etapas, se necesita un identificador estable
  (pregunta abierta).
- Nombres duplicados, etapas desconocidas o etapas declaradas pero no disponibles fallan de forma
  explícita; no se ignoran en silencio.
- Una etapa no listada no se ejecuta. Su ausencia no se convierte en éxito falso ni en error de
  activación del resto.
- El contrato de etapa es neutral y mínimo; el siguiente es un boceto, no un contrato cerrado:

```java
interface PipelineStage {
    String name();
    PipelineResult process(PipelineMessage message, PipelineContext context);
}
```

  `PipelineContext` debe exponer solo servicios neutrales (catálogo de canales, actores,
  permisos, configuración y traza). `PipelineResult` debe distinguir al menos continuar con mensaje,
  descartar y rechazar; la semántica exacta pertenece al ADR.
- El fan-out a sinks puede ser una etapa terminal (`sync-bus`) o vivir fuera del pipeline; debe
  decidirse antes de implementar.

#### Impacto previsto

| Área | Cambio necesario |
|---|---|
| `core-api` | Contratos neutrales de catálogo de canales, etapa, contexto, resultado y traza. |
| `suite-runtime` | Construir el pipeline desde `pipeline` + `ServiceRegistry`; dejar de exigir servicios concretos y de fijar el orden en código. |
| `textformatter` | Dejar de proveer `channel-api`; consumir el catálogo neutral y publicar una etapa de formato. |
| `iflow` y motores | Requerir el servicio neutral de canales, no la capability de `textformatter`; publicar una etapa de routing. |
| `chattranslator` y backends | Publicar una etapa de traducción; revisar el requisito backend→fachada de ADR-008. |
| `sync-*`, `inworld`, `tester` | Consumir el mensaje final o una etapa; no requerir `channel-api` de un feature. |
| `presets` | Dejar de hardcodear `textformatter/channels`; escribir el catálogo neutral en su ruta canónica. |
| `DefaultConfigInstaller` y hosts | Instalar el catálogo neutral y un `pipeline` por defecto coherente con lo instalado. |
| `checkArchitecture` | Guard nuevo: ninguna feature declara `Requirement` sobre otra feature; el orden del pipeline no se deriva del grafo. |
| Configuración y documentación | `config/suite/config.yml`, `storage.md`, `config-schema-v2.2.md`, wiki y READMEs. Los documentos de `docs/prompts/` requieren autorización. |

**Nota de alcance:** PL-1 no es solo añadir una lista. Potencialmente exige **rediseñar el pipeline
actual** (`SuiteHost`/`MessageDispatcher`), el modelo de requisitos del grafo y el lugar del catálogo
de canales. La lista de módulos es la superficie visible; el cambio de fondo es quién posee los
servicios neutrales y cómo se compone el procesamiento.

#### Preguntas abiertas (resueltas por ADR-036, pendiente de aceptación)

1. ¿`pipeline` o `workflow` como nombre canónico? Se recomienda `pipeline`; el otro no será alias.
2. ¿El catálogo de canales vive en `core-api`, en `suite-runtime` o en el `PipelineContext`?
3. ¿La entrada identifica un `moduleId` o un `stageId`? ¿Puede un módulo publicar más de una etapa?
4. ¿La traducción es una etapa propia después de iFlow o permanece dentro del render de
   `textformatter`? Moverla cambia el contrato de `TranslationService` y el orden observable.
5. Si una etapa listada no está instalada o activa: ¿error de configuración explícito o
   `SKIPPED`? ¿El `pipeline` por defecto es vacío o una lista base?
6. ¿Los syncs son una etapa terminal única (`sync-bus`) o varias etapas? ¿El fan-out pertenece al
   pipeline o queda fuera?
7. ¿El pipeline sustituye `SuiteHost`/`MessageDispatcher` o los envuelve como adaptadores?
8. ¿Cómo se integra `SET_CHANNEL` y la segunda fase de routing de ADR-031 en un pipeline
   secuencial, sin reentrega recursiva?
9. ¿Qué ocurre al cambiar `pipeline` en caliente: `APPLIED`, `FAILED` o `RESTART_REQUIRED`?
10. ¿Qué contrato de traza por etapa necesita `tester` para señalar el punto exacto de fallo (P12)?
11. ¿Todo mensaje (chat, join/quit/death, sistema) recorre el mismo pipeline o solo el chat?
12. Bajo el principio de independencia, ¿un backend de traducción puede activarse sin la fachada
    `chattranslator`, o la fachada es el único punto de entrada y el backend no es instalable solo?

#### Criterios de cierre y tests

1. **Arranque sin features:** el núcleo arranca, registra comandos y gestiona el ciclo de vida; un
   despacho sin etapas devuelve un estado explícito de «sin pipeline», no un éxito falso.
2. **Solo `textformatter`:** formatea y entrega sin iFlow, sin traducción y sin syncs.
3. **Solo iFlow:** enruta con el catálogo neutral, sin `textformatter` ni `chattranslator`.
4. **Solo `chattranslator`:** traduce con un render/pass-through neutral, sin `textformatter` ni
   iFlow.
5. **Orden configurable:** el mismo conjunto en dos órdenes produce resultados distintos y
   trazables; el orden no depende del grafo ni del orden de registro.
6. **Etapa ausente o no listada:** comportamiento explícito y probado, sin éxito fingido.
7. **Guard de independencia:** un descriptor de feature no declara `Requirement` sobre otra feature;
   solo sobre contratos neutrales.
8. **Regresión:** la configuración base reproduce el comportamiento actual cuando declara el orden
   vigente.
9. **Gate:** `./gradlew build checkArchitecture checkLocks --no-daemon`; si se toca el editor,
   `npm run check`.

#### Bloqueos

- **ADR-036 pendiente de aceptación explícita** (redactado el 2026-11-06). Resuelve las doce
  preguntas abiertas y enmienda ADR-019, ADR-024/025, ADR-032 y ADR-008; adopta ADR-031.
- Cierre de la purga P0 (ADR-034); P0.6 cerrado (2026-11-06).
- Autorización explícita para modificar `docs/prompts/diseno/storage.md`,
  `docs/prompts/especificaciones/config-schema-v2.2.md` y el resto de documentos de diseño.
- No se implementa código mientras el ADR-036 no esté aceptado.

---

## DOBLE LÍNEA — 2026-10-23: `iflow-vm` (32 bits) e `iflow-wasm` (Chicory)

### Decisión

**iFlow no se abandona ni se elimina.** Se mantienen dos líneas paralelas que comparten el contrato
de políticas del host, pero no el mismo runtime:

- **`iflow-vm` — VM propia de 32 bits.** Es la evolución de la VM actual (`src/iflow-vm/`), con las
  mejoras pendientes de `docs/prompts/diseno/iflow-cpu-implement.md`: memoria/MMU de 32 bits, ISA `.flow` revisada,
  BIOS/kernel/scheduler, IRQ, tiempo, persistencia y concurrencia. Conserva el compilador
  `tools/iflow-compiler/` y los artefactos `.flow`. No se borra nada de la base actual.
- **`iflow-wasm` — motor WebAssembly.** Es la línea nueva basada en **Chicory**, con artefactos
  `.wasm` portables, instancias aisladas, memoria lineal propia y un ABI de imports acotado. No
  hereda el ISA `.flow`, el ABI `HOST` v2 ni el modelo BIOS/kernel.

Las dos líneas son independientes. Los hallazgos `IFLOW-MAN-*` se incorporan como requisitos de `iflow-vm`; **no se
aplican automáticamente** a `iflow-wasm`. El host podrá seleccionar el motor por configuración
cuando ambas estén listas.

### Selección de motores iFlow mediante el hub `iflow` (2026-11-04, ADR-032)

> **Los tres motores — `iflow-vm`, `iflow-wasm` e `iflow-firewall` — publican `IflowBackend`.**
> El paquete `iflow` es el hub: descubre los backends y activa **uno solo** para el routing.
> Pueden coexistir instalados; el hub es la fuente de verdad y aplica el fallback configurado.
>
> Configuración canónica: `config/iflow/config.yml` con
> `engine: iflow-firewall | iflow-vm | iflow-wasm` y `fallback-on-unavailable: true|false`.
> El default es `iflow-firewall`. `iflow.engine.kind` ya no existe: la selección de motor vive
> solo en `config/iflow/config.yml` y el hub es el único punto de resolución.
>
> Los contratos neutrales (`Router`, `IflowHub`, `IflowBackend`) viven en `core-api` bajo
> `me.majhrs16.suite.iflow`; cada motor usa su propio subpaquete. `SuiteRuntime` exige
> `IflowHub` y no acepta un `Router` suelto. `iflow-wasm` ya publica su backend con hash y watcher.
>
> El monorepo compila los tres motores y el hub; `./gradlew publishIflowEngines` publica
> `suite-iflow`, `suite-iflow-vm`, `suite-iflow-wasm` y `suite-iflow-firewall` en `build/repo`.

El único pendiente transversal es la **paridad funcional de propiedades del mensaje** (W3.5) contra
la **spec v3**: que `iflow-wasm` implemente el mismo contrato de transformación, decisión y entrega,
no el ABI `HOST` v2 de `src/iflow-vm`. No reactiva el backlog de 32 bits de la VM. Detalle en
«Paridad funcional pendiente».

### Qué se conserva

- El contrato de entrada `Router` / `RouteDecision` / `RouteOutcome` y el resto del pipeline
  (`MessageDispatcher`, `SuiteHost`, `DefaultTextFormatter`, canales, permisos, sync).
- La separación de responsabilidades del host: el guest decide la política; el host ejecuta la
  entrega, aplica permisos y expone servicios acotados.
- La configuración por módulo y el editor web como superficies de autoría.
- Las decisiones de seguridad que no dependen del runtime: sandbox, capabilities, límites duros de
  memoria/tiempo/tamaño, validación de artefactos y verificación de integridad.
- **`src/iflow-vm/` como base de la VM de 32 bits**: código, tests, fixtures y compilador se conservan y
  evolucionan en la línea de 32 bits. **No se borra.**
- **WebAssembly + Chicory** como línea `iflow-wasm`, con módulo propio en `src/iflow-wasm/`.

### Qué no se hereda en `iflow-wasm`

- El ISA `.flow`, el ABI `HOST` v2, el modelo BIOS → bootloader → kernel → userspace, el mapa de
  64 KB, los frames de 4 KB y el fuel como mecanismo de límite del motor.
- Los hallazgos `IFLOW-MAN-*` como deuda del motor Wasm: pertenecen a `iflow-vm`.
- La documentación que presenta iFlow como único motor; se actualiza a la doble línea.

### Arquitectura objetivo de `iflow-wasm` (borrador, sujeta a ADR)

```
Mensaje entrante
  → SuiteHost / MessageDispatcher
  → WasmPolicyEngine (Chicory)
       ├─ instancia Wasm aislada por programa (memoria lineal propia)
       ├─ imports de host acotados: propiedades, estado, canales, entrega, reloj
       ├─ capabilities verificadas antes de invocar
       └─ límites duros: memoria, tiempo de ejecución, tamaño de entrada/salida
  → RouteDecision / RouteOutcome
  → formateo + entrega existentes
```

Reglas de diseño:

1. **Sin WASI de sistema por defecto.** El guest no recibe filesystem, red, procesos ni variables
   de entorno del host. Solo imports explícitos y capability-gated.
2. **Sin estado de máquina compartido.** Cada instancia Wasm tiene su memoria lineal; la
   persistencia entre mensajes, si se necesita, se modela como estado de host explícito.
3. **El guest decide; el host solo aplica límites duros.** Rate limit, backoff y política temporal
   pertenecen a la lógica Wasm; el host puede imponer un cap de seguridad separado.
4. **Artefacto verificable.** El `.wasm` se valida antes de cargar: magic/version, límites de
   memoria, imports permitidos, exports requeridos y hash.
5. **Un solo ABI de host.** Los imports se documentan una vez y se versionan; no se replica el ABI
   `HOST` v2 de iFlow.
6. **Autoría multi-lenguaje.** El panel web puede editar WAT/AssemblyScript o subir un `.wasm`
   compilado; el servidor no ejecuta toolchains nativas arbitrarias.

### Fases de `iflow-vm` (32 bits)

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **V0 — Módulo y spec** | `src/iflow-vm/` renombrado, Gradle/CI/docs actualizados y especificación v3 enlazada | ✅ `:src:iflow-vm:build`, `:src:iflow-wasm:build` y `:src:host:build` verificados con JDK 21 |
| **V1 — Contrato 32 bits** | ADR de ISA, memoria/MMU, IRQ, scheduler, tiempo y ABI `HOST` v3; corte limpio sin compatibilidad `.flow` v2 | Contrato congelado antes de tocar el núcleo |
| **V2 — Núcleo** | CPU/MMU/bus de 32 bits, arranque BIOS/kernel, fixtures actualizados | Tests de memoria, privilegios y arranque |
| **V3 — Persistencia y tiempo** | Scheduler, sleep/wake, RTC, quantum, journal y concurrencia | Tests de ejecución persistente y cancelación |
| **V4 — Integración** | `iflow-vm` conectado a `Router`/`MessageDispatcher` | Un mensaje real atraviesa el pipeline con una política `.flow` de 32 bits |
| **V5 — Herramientas de ensamblado** | Assembler `.flowasm` v3, firmware, ejemplos y validador | Build reproducible + tests |
| **V6 — Compilador C** | Compilador C11 freestanding a `.flowasm` v3, `libiflow` y ejemplos | Corpus C compila, ejecuta en VM v3 y produce el mismo resultado observable; tests y determinismo |

#### V6 — Compilador C (ADR-023)

**Estado:** diseño aceptado; implementación pendiente. No se inicia antes de
V1 (ISA/ABI v3) y V5 (assembler v3); la ejecución del corpus requiere V2/V3/V4.
No se implementa contra el ISA v2 de 16 bits ni se reutiliza su ABI `HOST` v2.

**Objetivo.** Permitir que las políticas de iFlow se escriban en C sin perder el
control de bajo nivel del ensamblador. El compilador es una herramienta offline
(`tools/iflow-c/`, CLI `iflow-cc`) escrita en TypeScript; emite `.flowasm` v3 y
el assembler v3 sigue siendo el único codificador y productor de ROM. No se
duplica el formato binario ni la tabla de opcodes.

**Alcance del lenguaje.** Subconjunto C11 freestanding: `void`, `_Bool`,
`char` sin signo, `signed char`, `short`, `int`/`long` de 32 bits, `long long`
de 64 bits, `float` (binary32), `double`/`long double` (binary64), punteros de
32 bits, arrays, `struct`, `union`, `enum`, `typedef`, `const`, `volatile`,
`_Atomic` escalar/puntero, control de flujo completo, funciones,
`static`/`extern`, `goto`, `switch`, `_Static_assert`, macros de
objeto/función, `#include`, condicionales, `#`/`##` y ensamblador inline estilo
GNU (`asm`/`asm volatile` y forma extendida con constraints `r`/`m`/`i`).
`volatile` no se elimina, duplica ni reordena. `_Atomic` soporta cargas,
almacenes, intercambio, CAS y fetch-add/sub/and/or/xor con las órdenes de C11;
los agregados atómicos se revisan en C4.

**Exclusiones explícitas.** `_Complex`, hilos, `setjmp`/`longjmp`, VLA,
bitfields, caracteres anchos, `_Generic`, `typeof`, `asm goto` y funciones
variádicas. Un uso no soportado falla con diagnóstico y ubicación; no se acepta
en silencio ni se degrada. Glosario:
**VLA** = array local de tamaño calculado en runtime (`int a[n]`); **bitfields**
= campos de bits en `struct` (`unsigned f : 3`); **variádicas** = funciones con
`...` y `va_list`; **`setjmp`/`longjmp`** = salto no local con restauración de
contexto; **`_Complex`** = tipos complejos, no confundir con `_Atomic`; **anchos**
= `wchar_t`/`L"..."`; **`_Generic`** = selección por tipo; **`typeof`** =
extensión GNU que devuelve el tipo de una expresión.

**Prioridad de extensiones.** Las exclusiones no son permanentes. Después de V6,
con enmienda de ADR-023 y tests propios:

1. **Variádicas** y **bitfields** primero, por uso común en C. Variádicas exige
   ABI de varargs y `<stdarg.h>`; bitfields exige fijar orden de bits,
   empaquetado, alineación y layout.
2. **VLA** y **`setjmp`/`longjmp`** solo si una política real los necesita. VLA
   exige asignación dinámica de pila y control de desbordamiento;
   `setjmp`/`longjmp` exige guardar/restaurar contexto y definir el unwind.
Los agregados atómicos se revisan antes, en C4.

**ABI y runtime.** La ABI C v3 se congela en V6.0 sobre el ISA v3: little-endian,
pila de 4 bytes (8 para 64 bits), argumentos en pila de derecha a izquierda,
limpieza del llamador, retorno en `R0`/`R1:R0`, GPR caller-saved, `SP`/`FP`
preservados, `struct` por valor con puntero oculto, `char` sin signo y
`size_t` de 32 bits. `float`/`double` se pasan en pila y se retornan en
`R0`/`R1:R0`; el baseline es soft-float y una FPU futura no cambia la ABI.
`libiflow` es una biblioteca freestanding enlazada en el programa: `crt0`,
`mem*`, `str*`, rutinas de 64 bits, helpers soft-float IEEE-754, helpers
atómicos y arena allocator explícito. No hay `stdio`, `printf` ni
`malloc`/`free` estándar hasta que el kernel defina su ABI; lo no implementado
falla al enlazar, no se simula.

**Modelo de programa.** El baseline compila un programa completo (una o varias
unidades `.c` en la misma invocación) a un único `.flowasm` v3. No hay formato de
objeto estable ni linker de objetos separado; si V6.4 lo necesita, se añade con
ADR. El loader inicializa `.data` y zeroa `.bss`; `crt0` llama a `main`, recibe
el estado y ejecuta la salida. No hay constructores globales.

**Optimización.** `-O0` es el default y debe ser correcto antes que rápido.
`-O1` puede aplicar plegado de constantes, propagación local, eliminación de
código muerto y peephole, sin explotar comportamiento indefinido, sin alterar
`volatile` y sin reordenar operaciones atómicas. La coma flotante mantiene
semántica IEEE-754 por defecto; `-ffast-math` no es baseline. La salida es
determinista: mismo fuente, flags y versión producen los mismos bytes y el
mismo orden de símbolos.

**Fases internas.**

| Fase | Entregable | Puerta |
|---|---|---|
| **C0 — Contrato** | Subconjunto, gramática, ABI C v3, modelo de runtime y corpus | ADR y casos congelados; cada exclusión con diagnóstico y test |
| **C1 — Frontend** | Preprocesador, lexer, parser, AST, semántica y diagnósticos | Tests unitarios y negativos; nada no soportado pasa en silencio |
| **C2 — IR y backend** | IR de tres direcciones, lowering, registros y emisión `.flowasm` v3 | Golden tests y ensamblado con el assembler v3 |
| **C3 — ABI y runtime** | `libiflow`, `crt0`, wrappers `HOST` v3, punteros/arrays/structs/64 bits, soft-float, atómicos e inline asm | Ejecución en VM v3 con el corpus; sin stubs |
| **C4 — Optimización y robustez** | `-O1`, peephole, fuzz, determinismo, límites y agregados atómicos | Mismos resultados observables; salida reproducible |
| **C5 — Toolchain** | CLI, ejemplos, documentación, CI Node y paquete | `npm test`, corpus y build reproducible verdes |

**Criterios de cierre.**

1. Al menos tres políticas reales (rate limit, routing de canal y transformación
   de contenido) escritas en C compilan y producen la misma decisión y mensaje
   observable que su referencia.
2. El corpus cubre punteros, arrays, structs, uniones, `switch`, `goto`,
   `volatile`, 64 bits, `float`/`double`, atómicos, inline asm y llamadas a
   `HOST`; lo no soportado falla con diagnóstico, no con un artefacto
   incorrecto.
3. La salida es determinista y el assembler v3 produce una ROM verificable.
4. No hay compilación C ni toolchain nativa en el host; no hay stubs de libc.
5. Tests, documentación y ejemplos verdes; el ADR se enmienda si cambia el
   subconjunto o la ABI.

**Por qué no ahora.** V6 depende de un ISA v3, un ABI `HOST` v3 y un assembler
v3 que aún no existen. Implementarlo antes obligaría a inventar el contrato o a
clonar el ISA v2; ambas cosas contradicen la regla de no crear un segundo
camino. Se documenta y se deja bloqueado por V1/V5, no se adelanta como POC.

### Fases de `chattranslator` (Facade de traducción unificada)

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **CT0 — Scaffold** | Módulo `src/chattranslator/` creado, `ChatTranslator` facade, `Module` SPI, tests unitarios (12 tests) | ✅ Módulo Gradle incluido; `:src:chattranslator:build` y tests pasan |
| **CT1 — Config & Default Backend** | Soporte para `default-backend` en `config/chattranslator/config.yml`; `ChatTranslatorConfig` carga al arranque | ✅ Config schema propio (`chattranslator/config.yml`); `ChatTranslatorConfig.load(folder)` implementado; tests de prioridad pasando |
| **CT2 — Context Flags Runtime** | API `setContextBackend(key, backend)` funcional; prioridad contexto > config > auto | ✅ Tests de prioridad pasando; `context-override-enabled` configurable |
| **CT3 — Backend Re-registration** | Backends (`gtranslate`, `ltranslate`) declaran requerimiento `chattranslator` en `ModuleDescriptor`; `manager` respeta orden de carga | ✅ `gtranslate`/`ltranslate` actualizados con `.require(Requirement.atLeast("chattranslator", ...))`; tests de resolución de grafo pasando |
| **CT4 — Integración Host** | `SuiteHost`/`TranslationService` usan `ChatTranslator` como fachada principal; Spigot/Fabric hooks actualizados | Mensaje real traducido via `ChatTranslator` en pipeline E2E |
| **CT5 — Editor & Autoría** | Panel web para configurar backend default y ver backends registrados | ⏸️ Diferido con el editor web (`PLAN.md:12`); el backend (`ChatTranslatorConfig` + `BackendConfig`) ya expone lo necesario |

### Decisiones resueltas por el régimen pre-1.0 — ChatTranslator

- **Schema de config:** se usa el layout canónico `config/chattranslator/`; no se mantiene el
  `config.yml` raíz como fuente alternativa.
- **Backward compat:** se elimina `TranslationService.active()` como fallback automático. El camino
  vigente es `ChatTranslator`; ADR-034 prohíbe conservar el puente.
- **Capability versioning:** la capability `chattranslator` se ata a la base `1.0.0` de la suite;
  no se versiona de forma independiente ni se declaran rangos de compatibilidad ficticios.

### Paridad funcional `TranslationService` ↔ `ChatTranslator`

| Área | `TranslationService` (actual) | `ChatTranslator` (objetivo) |
|---|---|---|
| Selección backend | Primer `isAvailable()` en orden de registro | Contexto > Config > Auto (primer `isAvailable()`) |
| Configuración | `translators/*.yml` (por proveedor) | `config.yml` (default-backend) + `translators/*.yml` |
| Override runtime | No soportado | `setContextBackend(key, backend)` |
| Caché/detección | Interno en `TranslationService` | Delegado a `TranslationService` (sin cambios) |
| API pública | `translate(text, from, to)`, `detect(text)` | Mismos métodos + overloads con `contextKey` |

### Fases de `iflow-wasm` (Chicory)

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **W0 — Scaffold** | `src/iflow-wasm/` creado, dependencia Chicory, loader mínimo y lockfile/verificación iniciales | ✅ Módulo Gradle incluido; `:src:iflow-wasm:build` validado con JDK 21 y Chicory 1.4.0 |
| **W1 — Contrato** | ADR del motor Wasm: imports/exports alineados a la **spec v3**, capabilities, límites, errores, ciclo de vida | ✅ ADR-W1 aceptado: `decide` como export obligatorio (`() -> i32`), módulo de imports `iflow` con firmas explícitas (`read_property`, `write_property`, `transform_apply`, `set_channel`, `permission_check`, `channel_policy`, `decision`, `now_nanos`, `log`), sin WASI, `start` denegado, 256 páginas y 4 MiB por artefacto; `PolicyAbiV3` + `TlvCodec` |
| **W2 — Runtime** | Cargar, validar, instanciar e invocar un `.wasm` de prueba | ✅ `WasmContract`/`ValidationResult`; `WasmModuleLoader` valida imports/exports/start/memoria y firmas, instancia con `withStart`/`withMemoryLimits`/`withImportValues` e invoca exports; `PolicySession` (journal read-your-writes, decisión única), `PolicyHost` (host functions `iflow.*`), `WasmPolicyEngine` (evaluación aislada) y tests con módulos binarios generados por `WasmTestModule` |
| **W3 — Integración** | `WasmPolicyRouter` conectado a `Router`, SHA-256 del `.wasm` y hot-reload | ✅ `WasmPolicyRouter` mapea `DELIVER/CANCEL_EVENT/ERROR` a `LOG/DROP/REJECT`, exige sidecar SHA-256, recarga con `WatchService` y publica `IflowBackend`; `WasmPolicyRouterTest` e `IflowWasmActivatorTest` verdes |
| **W3.5 — Paridad de mensaje (v3)** | Imports de transformación, decisión y entrega alineados a la spec v3 | Matriz `.flow` v3 (ROM) vs `.wasm` con el mismo resultado observable |
| **W4 — Herramientas** | Ejemplos en al menos dos lenguajes, validador de artefactos y guía de autoría | Build reproducible de los ejemplos + tests |
| **W5 — Editor** | Soporte del panel web para editar/compilar/subir `.wasm` | Flujo de autoría de extremo a extremo |

#### ADR-W1: Contrato del motor Wasm — política pura, sin imports implícitos y con límites duros

**Estado:** Aceptado (W1, 2026-11-04)

**Contexto**

- `Hecho verificado`: Chicory `1.4.0` está fijado en `src/iflow-wasm/build.gradle` y W0 expone solo `Parser.parse` + `Instance.builder`.
- `Hecho verificado`: la spec v3 (`docs/prompts/diseno/iflow-cpu-implement.md`) define transformación, decisión y entrega; la semántica de política está cerrada en ADR-031. W1 fija los nombres/firmas de imports y el export `decide` en `PolicyAbiV3`; la implementación de las host functions queda en W2.
- `Restricción`: el artefacto `.wasm` es dato no confiable; un módulo puede declarar memoria de 4 GiB, imports de WASI o una función `start` que se ejecuta al instanciar.
- `Restricción`: no hay toolchain WAT en el build; los tests de W2 usan módulos binarios mínimos escritos a mano.
- `Supuesto`: las políticas reales serán pequeñas (lógica de decisión), no cargas de datos; 4 MiB y 256 páginas son holgados para ese caso y deben revisarse si aparecen políticas mayores.
- La decisión fija el contrato mínimo que W3 (`WasmPolicyEngine`) puede asumir.

**Consecuencias**

*Positivas*
- Un artefacto con imports no declarados, `start` o memoria excesiva se rechaza antes de ejecutar código, no durante.
- La instancia se crea con `withStart(contract.allowStart())` y `withMemoryLimits(initial, max)`, así el crecimiento de memoria queda acotado aunque el módulo declare un máximo superior.
- El límite de 4 MiB y la comprobación de tamaño evitan leer/parsear artefactos desproporcionados.
- Los errores de validación son acumulativos (`ValidationResult.errors()`), lo que da diagnóstico completo en lugar de fallar en el primer incumplimiento.

*Negativas*
- W1/W2 validan y ejecutan el contrato aislado; W3 los conecta a `Router` con `WasmPolicyRouter`, SHA-256 y hot-reload.
- El límite de memoria se aplica al total por instancia; un módulo con varias memorias se rechaza, lo que excluye el proposal multi-memory.
- El hash del `.wasm` se exige en W3 mediante sidecar; el contrato aislado por sí solo no protege contra sustitución en disco.
- La lista de imports es cerrada: añadir una función exige versionar `PolicyAbiV3` y actualizar el ADR, no un import ad hoc.

**Alternativas consideradas**

1. **Permitir cualquier import y confiar en el host para denegar en runtime**
   - Ventajas: compatible con cualquier toolchain desde el primer día.
   - Limitaciones: el módulo puede enlazar con WASI o imports no auditados; el coste de seguridad se traslada a cada llamada.
   - Evaluación: descartada; el modelo de capabilities exige denegar por defecto.

2. **Contrato mínimo actual: sin imports, `decide` obligatorio, sin `start`, memoria y tamaño acotados (elegida para W2)**
   - Ventajas: superficie de ataque mínima; testeable con módulos binarios; permite congelar W2 sin esperar al ABI v3.
   - Limitaciones: no sirve aún para políticas que lean el mensaje; requiere ampliación versionada en W3.
   - Evaluación: **recomendada**; W3 añade imports `iflow.*` con lista blanca explícita y validación de firmas.

3. **Contrato completo v3 antes de implementar W2**
   - Ventajas: una sola congelación de contrato.
   - Limitaciones: bloquea W2 tras una spec que aún está en revisión (W3.5); sin runtime no se puede validar la spec.
   - Evaluación: descartada por orden de dependencias; W2 valida la mecánica, W3/W3.5 el contrato de mensaje.

**Decisiones de W3 (cerradas)**
- Contrato de política v3: **cerrado en ADR-031**; no volver a abrir D01–D05, Q26, Q35–Q38 ni
  `IFLOW-MAN-31/43…45` sin un ADR de sustitución.
- Nombres y firmas de los imports `iflow.*`: **cerrados en W1** (`PolicyAbiV3`); cualquier cambio
  exige versión nueva del ABI, no una ampliación silenciosa.
- Validación de firma de exports: **implementada en W1** (`decide` `() -> i32`) sobre las secciones
  del módulo; `Instance.exportType(name)` queda como comprobación redundante opcional.
- Hash/firma del artefacto y política de hot-reload: **cerrados en ADR-033** (SHA-256 del `.wasm` y watcher).
- Selección de motor (`.flow` vs `.wasm`) por configuración: **cerrada en ADR-032** (hub `iflow`).


### Paridad funcional pendiente: `iflow-vm` (v3) ↔ `iflow-wasm`

> **Base de la paridad:** la **spec v3** (`docs/prompts/diseno/iflow-cpu-implement.md`), no el código
> actual de `src/iflow-vm`. Ese código implementa la **v2 de 16 bits** y se conserva como base
> histórica; sus `PROP_*`, `MessageHostContext`, `PolicyTarget` y `HOST` v2 **no son contrato vigente**.
>
> **Alcance:** replicar en Wasm el contrato v3 de transformación, decisión y entrega, sin heredar ISA,
> MMU, BIOS/kernel ni ABI `HOST` v2. **No** incluye el backlog V1–V5 de la VM de 32 bits.

El objetivo es que una política `.wasm` observe y transforme el mensaje y emita la misma decisión
explícita que una política `.flow` v3, con el mismo resultado observable. La paridad es de **contrato**,
no de implementación: los nombres de imports pueden diferir, la semántica no.

| Área | Contrato v3 | Pendiente en `iflow-wasm` |
|---|---|---|
| Contexto de solo lectura | Tipo, dirección, emisor, destinatario, canal e identidad del mensaje, leídos como parámetros del IRQ. | Imports de lectura; sin mutación. |
| Contenido | `TRANSFORM_APPLY` solo contenido/metadatos: texto, placeholders y formato. | Host functions de lectura/escritura con buffer TLV/longitud; UTF-8 sin truncar. |
| Metadatos | Idioma origen/destino, color, flags y `PROP_TRANSLATE` booleano. Precedencia de idioma ratificada (`Q35` / `IFLOW-MAN-42`). Sonidos, `PROP_PROCESSED` y `PROP_ACTOR_WORLD` quedan fuera del baseline v3 (`IFLOW-MAN-31`). | Implementar `PROP_TRANSLATE` con validación `true`/`false`; **no copiar** los `PROP_*` v2 retirados. |
| Canal | `SET_CHANNEL` validado como única mutación de canal; si cambia permisos/destinatarios, segunda fase de routing, no reentrega recursiva. | Import de `SET_CHANNEL` + validación de existencia y permisos. |
| Routing | Decisión explícita y tipada; el guest decide y el host registra. Default deny + `DELIVER` explícito, `SET_CHANNEL` validado, sin `REDIRECT`/`CHANNEL_REDIRECT`, `PolicyTarget` reducido y `CANCEL_EVENT` separado (D01–D05 ratificadas en ADR-031). | Import de decisión con target/destino/razón; sin `route:*` ni `SET_PROCESSED`; una sola decisión por ejecución. |
| Journal | Solo write set del mensaje: todo o nada para sus mutaciones; no cubre máquina, tiempo, rate limit, cola ni entrega (`D43`). | Transacción de mensaje en el host con la misma semántica. |
| Rate limit y tiempo | Política en userspace: token bucket, backoff y sleep usan RTC/ciclos/`HALT`; el host no calcula backoff y solo puede imponer un cap duro (`D40`, `D42`). | Exponer reloj/ciclos y `HALT`/sleep; **no** importar `RATE_LIMIT` ni `sleepMillis` como propiedad. |
| Input ABI | TLV; UTF-8 sin cortar; ausencia distinta de `"null"`; `read-your-writes` ratificado (Q26). | Implementar TLV de entrada/salida y overlay de lectura del journal. |
| Propiedades declaradas sin implementar | `PROP_SOUNDS`, `PROP_PROCESSED` y `PROP_ACTOR_WORLD` se retiran del baseline v3; `PROP_TRANSLATE` se implementa como booleano validado (`IFLOW-MAN-31`). | No replicar las propiedades retiradas; validar `PROP_TRANSLATE` como `true`/`false`. |
| Material diferido | Precedencia de idioma, `Formats` N, `Direction.channel` y render de redirect (`IFLOW-MAN-42…45`). Decisiones cerradas en ADR-031: Q35, N entregas separadas, eliminar `Direction.channel` y render con recipient original. | Implementación pendiente en W3.5/W4; no se reclasifica como trabajo hecho. |

> **Estado de las decisiones (2026-11-03):** D01–D05 están ratificadas en ADR-031, junto con
> Q26, Q35–Q38, `IFLOW-MAN-31`, `IFLOW-MAN-43…45`, el ABI TLV y la regla de una sola decisión por
> ejecución. D14–D16, D36R y D40–D43 ya estaban ratificadas. W3.5 queda desbloqueado en su contrato;
> W1/W2/W3 están implementados en `iflow-wasm` y la paridad sigue pendiente. D06–D13 continúan
> propuestas y no bloquean este contrato. Las decisiones de W3 sobre hash/hot-reload (ADR-033) y
> selección de motor (ADR-032) están cerradas; nombres/firmas de imports y validación de exports
> quedaron cerrados en W1.

> **No se hereda de la v2:** `LOG`/`processed`, `DROP`/`REJECT` como targets separados,
> `REDIRECT`/`CHANNEL_REDIRECT`, `RATE_LIMIT` con backoff calculado por el host, `sleepMillis` como
> propiedad del mensaje, `PROP_*` declaradas sin implementar ni la lectura que convierte `null` en
> `"null"`.

**Criterios de cierre:**

1. Un corpus de políticas `.flow` v3 (ROM) y `.wasm` produce la misma decisión explícita
   (target/destino/razón) y el mismo mensaje transformado para las mismas entradas.
2. `SET_CHANNEL` valida existencia y permisos igual en ambos motores; un cambio que altere
   destinatarios se resuelve como segunda fase de routing, no como reentrega recursiva.
3. El journal es atómico y cubre solo el write set del mensaje; no filtra estado de máquina, tiempo,
   rate limit, cola ni entrega.
4. La política de rate limit y de tiempo vive en el guest; el host solo aplica un cap duro separado
   y no calcula backoff ni interpreta `sleepMillis` como propiedad.
5. El ABI de entrada/salida usa TLV, no trunca UTF-8 y distingue ausencia de `"null"`;
   *read-your-writes* está implementado y documentado según Q26.
6. La matriz de paridad y los tests negativos (propiedad desconocida, enum inválido, buffer
   insuficiente, capability denegada, canal inexistente) quedan documentados.
7. `Formats` entrega N mensajes separados y un cambio de destino reutiliza el componente
   renderizado con el contexto del recipient original (Q36/Q38).

**Fases:** W1 define los imports alineados a la spec v3 (✅ cerrado); W2 implementa las host
functions y el engine aislado (✅ cerrado); W3 integra el motor en el pipeline con router, hash y
hot-reload (✅ cerrado); W3.5 cierra la paridad. W3.5 está desbloqueado por ADR-031. No se toca el
backlog V1–V5 de `iflow-vm`.

### Decisiones pendientes (ADR)

- **`iflow-vm`:** ISA de 32 bits, mapa de memoria, MMU, IRQ, scheduler, política de tiempo
  (quantum/timer/watchdog), journal, concurrencia y ABI `HOST` v3. No hay compatibilidad con
  `.flow` v2: el contrato viejo se corta en la purga pre-1.0 (ADR-034).
- **`iflow-wasm`:** nombre/artefacto definitivo, versión de Chicory (W0 fija `runtime:1.4.0`;
  falta decidir compilador/WASI), capabilities y límites. El ABI de imports, la validación de
exports, el hash del `.wasm` y el hot-reload quedaron cerrados en W1/W3 (`PolicyAbiV3`,
  `WasmPolicyRouter`, ADR-033).
- **Paridad `iflow-vm` ↔ `iflow-wasm`:** contrato de política cerrado en ADR-031 (D01–D05,
  Q26, Q35–Q38, `IFLOW-MAN-31/43…45`, ABI TLV y decisión única). El hash/hot-reload y la selección
  de motor quedaron cerrados en ADR-032/ADR-033; falta la matriz de paridad W3.5, alineada a la
  **spec v3** y no al ABI v2 de `src/iflow-vm`.

### Próxima acción inmediata (V0/W0/W1, CT1–CT4 ✅ y cierre de paridad)

1. Mantener `docs/prompts/diseno/iflow-cpu-implement.md` como especificación técnica de referencia v3 de la VM de 32 bits.
2. Conservar `src/iflow-vm/` como base de la VM de 32 bits.
3. Mantener el scaffold de `src/iflow-wasm/` (Chicory, loader, tests, lockfile/verificación) validado con JDK.
4. ADRs del motor Wasm y de la VM de 32 bits: cerrados en ADR-031, ADR-032 y ADR-033.
5. Completar la paridad funcional de propiedades y decisiones entre ambos motores contra la spec v3 (W3.5, desbloqueado por ADR-031); W3 ya está cerrado con `WasmPolicyRouter`, SHA-256 y hot-reload. No se toma el código v2 como contrato ni se aborda el backlog V1–V5 de `iflow-vm`.
6. **ChatTranslator:** CT4 completado; CT5 diferido con el editor web.
7. **V6 — Compilador C:** diseño en ADR-023; no se inicia antes de V1/V5. La implementación empieza por C0 (subconjunto y ABI) cuando el ISA v3 esté congelado; no se adelanta contra el ISA v2.

---

### Formato de paquete: `Exclude` y `Provide` (2026-10-27)

> **Nota de vigencia (ADR-032/ADR-034).** El mecanismo `Exclude`/`Provide` sigue existiendo para
> paquetes que realmente no pueden coexistir. **Los tres motores iFlow no se excluyen**: pueden
> estar instalados y el hub `iflow` activa uno solo. Los ejemplos de esta sección que usan
> `iflow-*` son históricos y no se usan como manifiestos recomendados.

El formato compartido por `METADATA/package.list` y `library/status` incorpora dos
campos opcionales, ambos con nombres separados por espacios:

```
Exclude: legacy-bridge
Provide: router
```

- **`Exclude`** declara paquetes que no pueden coexistir con el paquete actual. La
  relación es simétrica: basta que cualquiera de los dos excluya al otro. También
  coincide con nombres virtuales que el otro paquete declare en `Provide`.
- **`Provide`** declara nombres virtuales que el paquete satisface. Sirve sobre todo
  para metapaquetes y contratos estables: una dependencia de un contrato virtual puede
  quedar satisfecha por cualquiera de sus proveedores. Para iFlow, el proveedor estable
  de `iflow` es el hub; los motores publican `IflowBackend` y no compiten por el nombre.
  No cambia la identidad del paquete ni crea un paquete real adicional.

Semántica de instalación:

1. `PackageInstaller` (instalación local de un ZIP) rechaza la instalación si algún
   paquete del manifiesto entra en conflicto con uno ya instalado. El mensaje indica
   que debe desinstalarse primero; `force` no omite conflictos de paquete.
2. `DependencyResolver` calcula, además de los pasos de instalación, la lista de
   paquetes instalados que deben retirarse por `Exclude`. Si retirar uno rompe una
   dependencia inversa que ningún paquete nuevo satisface, la resolución falla.
3. `AptInstaller.install(..., confirmed=false)` no modifica el sistema si hay
   reemplazos: devuelve un resultado de confirmación con la pregunta estilo APT:
   `Deseas instalar estos paquetes?: <lista>` y `Reemplazarán a: <paquetes>`.
   Con `confirmed=true`, la variante que recibe la barrera de runtime desactiva
   primero los paquetes en conflicto y después instala el plan. Las sobrecargas
   sin barrera fallan explícitamente si hay reemplazos. En los comandos, el token
   final `confirm` activa esa confirmación:
   `/suite repo install iflow-vm confirm`.
4. Las dependencias satisfechas por `Provide` se reconocen tanto al instalar como al
   comprobar dependencias inversas y al diagnosticar paquetes rotos.

Ejemplo genérico de un paquete que sustituye a un puente legacy:

```
Exclude: legacy-bridge
Provide: router
```

Los tres motores iFlow **no** declaran `Exclude` entre sí (ADR-032). Cada uno declara su
propia capability (`iflow-vm`, `iflow-wasm`) y `router`; el hub `iflow` es el paquete que
provee `iflow` y elige el backend activo.

El estado instalado conserva `Exclude`/`Provide` al serializarse, de modo que la
restricción del paquete sigue vigente después de reiniciar el servidor. `module info`
muestra ambos campos.

**Alternativas consideradas**

1. **`iflow-api` compartido con un único `Router`**
   - Ventajas: sin duplicación de contrato.
   - Limitaciones: crea un tercer módulo del que dependen todos; el usuario lo
     rechazó explícitamente por mezclar los módulos.
   - Evaluación: descartada.

2. **Sufijo de paquete `.firewall` para permitir convivencia**
   - Ventajas: evita colisión si ambos JAR están presentes.
   - Limitaciones: introduce un paquete que no comparten los tres motores; el hub
     ya resuelve la convivencia sin sufijos (ADR-032).
   - Evaluación: descartada.

3. **Restaurar solo el grafo SpEL-free (decisión anterior)**
   - Ventajas: no reintroduce SpEL.
   - Limitaciones: no es el motor histórico y contradice la decisión del usuario.
   - Evaluación: descartada.

4. **Restauración literal, mismo paquete base y motores excluyentes (histórica)**
   - Ventajas: fidelidad histórica, contrato compartido por FQCN, sin `iflow-api`.
   - Limitaciones: los JAR no podían coexistir; el gestor debía reemplazar.
   - Evaluación: aceptada en 2026-10-27; **superada por ADR-032**. Hoy los tres JAR pueden
     coexistir y el hub activa uno solo.

**Implementación (2026-10-27).** Código restaurado en `me.majhrs16.suite.iflow`,
descriptor `iflow-firewall`, SPI de expresión dentro del módulo, `RouterFactory`
sin adaptador y selección de motor en `HostConfig`/`ConfigLoader`; `FirewallConfig` todavía tolera
el esquema legacy, deuda que la purga pre-1.0 elimina (ADR-034). Publicación separada de los tres motores con
`publishIflowEngines` y documentación de exclusividad (PLAN, ADR, storage, README,
wiki); esa exclusividad fue sustituida por el hub en ADR-032. `./gradlew build` y
`./gradlew publishIflowEngines` pasan.

### Índice de ADR

Las decisiones de arquitectura viven únicamente en `docs/workdir/ADR.md`. Este PLAN no las
duplica: conserva el estado operativo y el trabajo activo. El historial cerrado de implementación y
fases vive en Git.

### Cobertura de tests exhaustiva — Iniciativa transversal

> **Situación actual (2026-10-27):** inventario real generado con `./gradlew testInventory` — **34 módulos Java** en `src/`:
> - **0 sin directorio de tests**
> - **0 directorios vacíos**
> - **34 con tests** (1–27 archivos); solo `iflow-vm` (27), `host` (12) y `textformatter` (7) superan 5 archivos
> - **10 módulos con reporte JaCoCo**: `iflow-vm` (86.2%/71.3%), `kernel` (91.1%/82.0%), `logger` (93.3%/78.6%), `messages` (93.3%/75.0%), `textformatter` (81.6%/66.7%), `host` (30.9%/23.8%), `spigot-host` (2.5%/4.6%), `transport` (6.0%/1.0%), `fabric-host` (0.0%/0.0%), `tester` (0.0%/0.0%)
> - **23 módulos con tests pero SIN reporte JaCoCo** (cobertura N/A) — requieren `jacocoTestReport`

**Inventario detallado (baseline T0 → T1 actualizado 2026-10-24):**

| Módulo | Test Dir | Archivos Test | Líneas Prod | Líneas Test | Cobertura Línea | Cobertura Rama | Gaps Críticos |
|---|---|---:|---:|---:|---:|---:|---|
| `src:core-api` | ✅ OK | 2 | N/A | 259 | N/A | N/A | Pocos tests (<3) |
| `src:example-extension` | ✅ OK | 1 | N/A | 45 | N/A | N/A | Pocos tests (<3) |
| `src:extension-api` | ✅ OK | 3 | N/A | 260 | N/A | N/A | `ExtensionContext` usa puertos neutrales, sin `host` |
| `src:fabric-host` | ✅ OK | 5 | 840 | 86 | 18.4% | 11.2% | `FabricTestProbe` cubierto; E2E real posterior a 1.0.0 pendiente (ADR-034); cobertura global baja |
| `src:gtranslate` | ✅ OK | 3 | N/A | 229 | N/A | N/A |  |
| `src:host` | ✅ OK | 9 | 1503 | 1628 | 30.9% | 23.8% | Cobertura línea <70%; Cobertura rama <50% |
| `src:iflow-vm` | ✅ OK | 27 | 2618 | 6271 | 86.2% | 71.3% |  |
| `src:iflow-wasm` | ✅ OK | 1 | N/A | 32 | N/A | N/A | Pocos tests (<3) |
| `src:iflow-firewall` | ✅ OK | 5 | 2553 | 722 | 61.6% | 50.1% | Restauración literal del motor SpEL (44 tests); incluye validador del grafo del editor |
| `src:chattranslator` | ✅ OK | 1 | N/A | 350+ | N/A | N/A | 12 tests unitarios |
| `src:inworld` | ✅ OK | 3 | 349 | 356 | 88.1% | 74.2% | `PACKAGE` neutral; `InWorldHandler`/`InWorldPlatform`; `SpigotInWorldListener` en `spigot-host` |
| `src:kernel` | ✅ OK | 4 | 202 | 243 | 91.1% | 82.0% |  |
| `src:loadtest` | ✅ OK | 4 | N/A | 1120 | N/A | N/A |  |
| `src:logger` | ✅ OK | 4 | 75 | 405 | 93.3% | 78.6% |  |
| `src:ltranslate` | ✅ OK | 3 | N/A | 288 | N/A | N/A |  |
| `src:manager-apt` | ✅ OK | 3 | N/A | 317 | N/A | N/A |  |
| `src:manager-core` | ✅ OK | 3 | N/A | 683 | N/A | N/A |  |
| `src:manager-spi` | ✅ OK | 2 | N/A | 323 | N/A | N/A | Pocos tests (<3) |
| `src:messages` | ✅ OK | 1 | 30 | 53 | 93.3% | 75.0% | 18 tests integración |
| `src:observability` | ✅ OK | 2 | N/A | 105 | N/A | N/A | 15 tests integración |
| `src:performance` | ✅ OK | 2 | N/A | 135 | N/A | N/A | Pocos tests (<3) |
| `src:presets` | ✅ OK | 5 | N/A | 1152 | N/A | N/A | `Preset.apply` + `FilePresetTarget` reales; paquete y activador probados; comando integrado en `suite-runtime` |
| `src:spigot-host` | ✅ OK | 4 | 590 | 3346 | 7.5% | 7.7% | Cobertura línea <70%; Cobertura rama <50%; `SpigotInWorldListener` cubierto con Mockito |
| `src:sync-bus` | ✅ OK | 1 | N/A | 116 | N/A | N/A | Pocos tests (<3) |
| `src:sync-discord` | ✅ OK | 3 | N/A | 250 | N/A | N/A | Paquete neutral: puerto en `core-api`, sin JDA |
| `src:sync-discord-jda` | ✅ OK | 1 | N/A | 130 | N/A | N/A | Adaptador JDA; tests sin red |
| `src:sync-discord-jdk` | ✅ OK | 3 | N/A | 320 | N/A | N/A | Adaptador JDK; tests con WebSocket loopback |
| `src:sync-http` | ✅ OK | 3 | N/A | 192 | N/A | N/A |  |
| `src:sync-socket` | ✅ OK | 3 | N/A | 202 | N/A | N/A |  |
| `src:sync-telegram` | ✅ OK | 2 | N/A | 173 | N/A | N/A | Pocos tests (<3) |
| `src:sync-velocity-core` | ✅ OK | 1 | N/A | 420 | N/A | N/A | Paquete neutral: puerto en `core-api`, sin Velocity API |
| `src:sync-velocity` | ✅ OK | 1 | N/A | 180 | N/A | N/A | Plugin/adapter Velocity real; tests con Mockito |
| `src:sync-websocket` | ✅ OK | 1 | N/A | 26 | N/A | N/A | Pocos tests (<3) |
| `src:tester` | ✅ OK | 3 | 271 | 1057 | 81.6% | 55.8% | `PACKAGE` neutral; `TestProbe` + `SpigotTestProbe`; suite completa con probe falso |
| `src:textformatter` | ✅ OK | 6 | 282 | 512 | 81.6% | 66.7% |  |
| `src:transport` | ✅ OK | 1 | 351 | 22 | 6.0% | 1.0% | Pocos tests (<3); Cobertura línea <70%; Cobertura rama <50% |

**Resumen:** 34 módulos totales · **34 con tests** · **0 sin test dir** · **0 vacíos** · 13 con cobertura real · 21 con tests pero sin reporte JaCoCo.

**Objetivo:** que **cada módulo** tenga una suite de tests unitarios y de componente con **cobertura significativa** (líneas/branches ≥ 70 % en código de producción, ≥ 90 % en SPI/API pública) y tests de regresión para cada bug/fix documentado.

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **T0 — Inventario y baseline** ✅ | Matriz `módulo × {archivos test, cobertura actual, gaps críticos}` generada por script CI | Tabla inline arriba con datos reales |
| **T1 — Scaffold obligatorio** ✅ | Todo módulo Java tiene `src/test` con JUnit 5 + AssertJ + Mockito; `build.gradle` incluye `testImplementation` y task `test` pasa en CI | **0 módulos sin directorio de tests; 0 directorios vacíos** |
| **T2 — Gates de cobertura por módulo** ✅ | `jacocoTestCoverageVerification` con reglas **por módulo** (no global): `minLineCoverage`, `minBranchCoverage`; falla el build si no se cumple | **Gates verdes en CI para todos los módulos** (umbrales según baseline T1) |
| **T3 — Tests de contrato SPI/API** ✅ | Cada `core-api`, `extension-api`, `manager-spi`, `kernel` tiene tests que validan contratos (ServiceLoader, interfaces, modelos inmutables) sin depender de implementaciones | **Tests de contrato pasan en aislamiento**; documentación de contratos generada |
| **T4 — Tests de integración por dominio** ✅ | `sync-*`, `transport`, `host`, `gtranslate`/`ltranslate`, `presets`, `inworld`, `observability`, `messages`: tests con testcontainers/mocks de red, disco y plataforma | Suite de integración ejecuta en CI (< 10 min) sin servidor real; el E2E real es la tarea E2E.1 posterior a 1.0.0 (ADR-034) |
| **T5 — Tests de regresión obligatorios** | Cada bug/fix P0/P1 de `AUDITORIA.md`, commits `fix:` tiene al menos un test que falla sin el fix y pasa con él | Matriz bug→test inline en `docs/workdir/PLAN.md` |
| **T6 — Fuzzing y property-based** | Módulos de parsing/serialización (`textformatter`, `messages`, `iflow-vm` bytecode, `iflow-wasm` loader) usan jqwik o JUnit-Quickcheck | Campañas nocturnas en CI; hallazgos → issues automáticos |
| **T7 — Performance/soak baselines** | `loadtest`, `performance`: benchmarks JMH con umbrales de regresión (p99, throughput, heap) | Alertas automáticas si hay regresión > 10 % |

**Reglas de diseño:**
- **Los tests automatizados no requieren servidor real**: usar mocks, testcontainers o harnesses
  in-process. El betatesting/E2E en Spigot/Paper/Fabric/Velocity reales es una capa adicional
  obligatoria posterior a la 1.0.0 (ADR-034), no un sustituto de los tests.
- **Tests deterministas**: sin `Thread.sleep`, sin orden no determinista, sin estado global mutable entre tests.
- **Fixtures compartidas** en `src/fixtures/` (ya existe) para mensajes, canales, políticas, configs; versionadas con el código.
- **Mutación testing** (Pitest) opcional en T2/T3 para validar calidad de aserciones.
- **Documentación viva**: cada módulo tiene `src/<mod>/README.md` con sección «Tests» que explica cómo correr, qué cubre y cómo añadir casos.

**Próxima acción T4:** ✅ **COMPLETADO** — tests de integración por dominio para `sync-*`, `transport`, `host`, `gtranslate`/`ltranslate`, `presets`, `inworld` (14 tests neutrales: dominio, activador y descriptor), `observability` (15 tests) y `messages` (18 tests) — usando mocks de red, disco y plataforma; suite < 10 min en CI sin servidor real.

**Próxima acción:** W1/W2/W3 cerrados (contrato `iflow.*`, firmas, TLV, `PolicySession`, host functions, `WasmPolicyEngine`, `WasmPolicyRouter`, SHA-256 y hot-reload). Falta W3.5: matriz de paridad `.flow` v3 vs `.wasm` con el mismo resultado observable, sin reabrir el contrato v2.

---

## Estado actual del proyecto

El estado operativo vigente está en la cabecera de este PLAN y en `ADR.md`; no se mantiene aquí un
snapshot duplicado. Lo verificado y lo desconocido se declara de forma explícita:

- Spigot/Paper: el plugin carga y el módulo tester se ejecutó en Paper 1.20.6. Eso fue un smoke test,
  no un E2E.
- Fabric: el mod compila y el probe se ejecuta; no hay E2E real.
- Velocity: el plugin compila y los tests de paquete pasan; no hay E2E real.
- Cualquier combinación no listada se considera **desconocida** hasta el E2E posterior a `1.0.0`.

---

## Entorno de trabajo: skills, MCP y toolchain (2026-10-19)

Herramientas disponibles para trabajar en este repo. Las **globales** viven en
`~/.config/opencode/` y sirven para cualquier proyecto de esta máquina (no se
versionan); las de **proyecto** viven en `.opencode/` y viajan con el repo.

### Skills

| Skill | Ámbito | Cuándo usarla |
|---|---|---|
| `juzgar-rubrica` | **proyecto** | Puntuar las familias cualitativas de la rúbrica de métricas (documentación e implementación real) y refrescar `judge_scores.csv` |
| `architecture-decision-records` | global | Crear y mantener ADRs: contexto, alternativas, consecuencias |
| `auditoria-seguridad-calidad` | global | Auditar C/C++/Java: memoria, concurrencia, OWASP, calidad estática |
| `automated-test-engineer` | global | Diseñar suites de tests unitarios y de componente con alta cobertura |
| `low-level-systems-debugger` | global | Kernels, emuladores, CPU virtuales, MMU/MMIO, NASM, volcados |
| `release-notes` | global | Changelogs y notas de versión a partir de commits e hitos |
| `opencode` | integrada | Dudas sobre OpenCode: configuración, agentes, skills, SDK, CLI |
| `report` | integrada | Reportar un bug de OpenCode con diagnóstico |

### MCP

Declarados en `~/.config/opencode/opencode.jsonc` (ámbito global):

| Servidor | Tipo | Endpoint / comando | Para qué |
|---|---|---|---|
| `github` | local | `npx -y @modelcontextprotocol/server-github` (token vía `GITHUB_TOKEN`) | Issues, PRs, commits, búsqueda en repos |
| `puppeteer` | local | `npx -y @modelcontextprotocol/server-puppeteer` | Automatización de navegador |
| `gh_grep` | remote | `https://mcp.grep.app` | Buscar código real en repos públicos de GitHub |
| `context7` | remote | `https://mcp.context7.com/mcp` | Documentación actualizada de librerías y frameworks |

> ⚠️ **`puppeteer` está declarado y habilitado pero no se expone**: no aparece
> entre las herramientas de la sesión (solo `github`, `gh_grep` y `context7`).
> Revisar si se necesita automatización de navegador.

### Toolchain: JDKs disponibles

**No hay `java` ni `javac` en el `PATH` de la sesión.** Hay que exportar `JAVA_HOME`
(o usar rutas absolutas). Conviven varias instalaciones en `/opt`, y **no todas son
JDK**: las de `/opt/java` son JRE, sin `javac`.

| Ruta | Versión real | `javac` | Notas |
|---|---|---|---|
| `/opt/javac/x64/21` | OpenJDK **21.0.12** LTS | **Sí** | La correcta para este repo (target Java 17) |
| `/opt/javac/x64/8` | OpenJDK 1.8.0_492 | Sí | Solo para proyectos Java 8 |
| `/opt/javac/arm64/` | — | — | Vacío |
| `/opt/java/x64/21` | OpenJDK **25.0.2** LTS | No (JRE) | ⚠️ La carpeta dice 21 pero es 25 |
| `/opt/java/x64/11` | OpenJDK 11.0.29 | No (JRE) | |
| `/opt/java/x64/8` | OpenJDK 1.8.0_482 | No (JRE) | |
| `/opt/idea/jbr` | JBR **25.0.3** | Sí | Runtime de IntelliJ, no para el build |

Si se le pasa un JRE como toolchain, Gradle falla con
`Toolchain installation '...' does not provide the required capabilities: [JAVA_COMPILER]`.

Verificado el 2026-10-19 (compila sin red, todo desde `~/.gradle`):

```bash
export JAVA_HOME=/opt/javac/x64/21
./gradlew --offline build
```

> 🧱 **Toolchain de iFlow VM (2026-10-23, actualizado 2026-10-29).** `tools/iflow-compiler/` y
> `:src:iflow-vm:test` son la base de `iflow-vm`; se conservan y evolucionan a 32 bits. El compilador
> C de V6 vivirá en `tools/iflow-c/` y emitirá `.flowasm` v3; no sustituye al assembler. En paralelo,
> `iflow-wasm` usa Chicory y artefactos `.wasm`, no `.flow`.

### Criterio de uso

- `context7` **antes** de escribir contra una API de terceros (Gradle, Paper, Fabric, JUnit…).
- `gh_grep` / `github` para ver cómo lo resuelven proyectos reales, no de memoria.
- `auditoria-seguridad-calidad` + `automated-test-engineer` al revisar código Java propio.
- `architecture-decision-records` cuando una decisión estructural merezca ADR.
- `release-notes` al cerrar una tanda de trabajo.
- `juzgar-rubrica` para refrescar la rúbrica de madurez (ver `tools/project-metrics/`).

---

## Principios de Arquitectura Técnica

Esta sección recopila los principios técnicos de diseño, costumbres del proyecto y razonamiento del "por qué" de las decisiones arquitectónicas. Pertenece al PLAN; el manifiesto abstracto de diseño vive en `docs/prompts/diseno/philosophy.md`.

### P1. El Manager es el origen de la composición

El Manager de paquetes es quien actualiza índices, descarga artefactos, resuelve dependencias, instala, **inicializa en memoria** y gestiona el ciclo de vida completo de los módulos. Los adaptadores de plataforma (Spigot/Fabric) no son composition roots ni conocen la existencia de los módulos de feature. Solo implementan puertos de plataforma; la captura y traducción de eventos vive en los paquetes `sync-bukkit`/`sync-fabric`.

**Consecuencia:** `spigot-host` y `fabric-host` dependen solo de la API del núcleo + runtime de módulos + SDK de plataforma. Ninguna dependencia a `sync-*`, `textformatter`, `iflow-*`, `chattranslator`, `gtranslate`, `ltranslate`, `observability`, `manager-*` ni `presets`.

### P2. Instalar no es activar

La base de datos local (`library/status`) es un catálogo de programas instalados, no un registro de estado de ejecución. La presencia de un paquete implica que está instalado y que debe iniciarse. El estado de ejecución (encendido/apagado, fallido, requiere reinicio) no pertenece a `library/status`; si es necesario rastrearlo, se hace en un directorio temporal dentro del directorio del manager, nunca en el estado de instalación.

**Consecuencia:** `library/status` registra nombre, versión, canal, dependencias, archivos y hashes del artefacto. No guarda si un módulo está "activo" en un instante dado.

### P3. Los adaptadores de plataforma son delgados

Spigot y Fabric no deben conocer la existencia de ningún módulo de feature. Su propósito es simplemente implementar una API común entre plataformas y arrancar el runtime. La captura de eventos de Minecraft y su traducción a una representación interna agnóstica vive en paquetes de plataforma (`sync-bukkit`/`sync-fabric`), no en el adapter. El cableado de sinks y servicios de plataforma vive en el runtime de módulos (`suite-runtime`).

**Consecuencia:** El adapter se reduce a crear `PlatformPorts`, arrancar `SuiteRuntime` y delegar; los listeners y la traducción de eventos se cargan como paquetes `sync-bukkit`/`sync-fabric`.

### P4. Código arbitrario: seguridad compartida (sandbox + dueño del repo)

El manager tipo apt descarga e instala código arbitrario. Esa es la idea intencional. Hay sandboxing por módulo, pero el punto central no es el sandbox: **el dueño del repositorio es responsable de lo que publica**. La seguridad se basa tanto en el sandbox como en la confianza en el repositorio. El manager verifica integridad con SHA-256; el operador decide qué repositorios añadir y responde por esa confianza. Firmas y allowlists pueden añadirse como defensa en profundidad, pero no son requisitos de la filosofía.

**Consecuencia:** No se promete que el sandbox por sí solo sea suficiente. La confianza recae en el dueño del repositorio, complementada con verificación de integridad.

### P5. `force` significa forzar de verdad

El parámetro `force` no omite conflictos de forma silenciosa. Si un administrador usa `force`, es porque quiere forzar las cosas y sabe lo que hace. Esto incluye permitir la instalación de paquetes excluyentes si el administrador así lo decide, con la advertencia correspondiente. `force` es una señal de intención deliberada del operador, no un sinónimo de «ignorar problemas».

**Consecuencia:** `force` debe poder romper invariables de coexistencia cuando el admin lo solicita explícitamente.

### P6. Hot unload/upgrade real

El objetivo es descargar, instalar, activar y desactivar módulos en caliente, con liberación de recursos (listeners, tareas, hilos, sockets, referencias). Si una operación no puede completarse sin reinicio, se detecta y se comunica explícitamente (`RESTART_REQUIRED`). Nunca se finge éxito con no-ops, `return parent`, `return false` o métodos vacíos.

**Consecuencia:** Cada módulo implementa `ModuleActivator` con `start/stop`. Cada registro de servicio queda asociado al `moduleId` y se cierra al desactivar. Un classloader por módulo, cerrado al desactivar.

### P7. No hay doble camino

Cuando una funcionalidad se migra al runtime de módulos, su cableado compilado se elimina en el mismo cambio. No se mantienen dos formas de arrancar la suite ni dos mecanismos de descubrimiento de módulos.

**Consecuencia:** `ModuleLifecycleAdapter`, `SuiteHost.bootstrap()` hardcodeado y `ManagerFacade.discoverAll()` por classpath fueron eliminados cuando el runtime de módulos los sustituyó.

### P8. Las librerías compartidas son paquetes de dependencia

SnakeYAML, JDA, Adventure, transporte y otras librerías no se compilan dentro del JAR del host ni de los módulos. Son dependencias gestionadas por el manager, quien se asegura de relocalizar y cargar dichas dependencias para cada paquete tipo módulo.

**Consecuencia:** Ningún JAR de módulo empaqueta librerías compartidas. El classloader del módulo las resuelve desde el paquete de dependencia relocalizado que el manager le asigna; el host no las expone como parent-first.

### P9. Los metadatos autoritativos vienen del repositorio, no del JAR

El manager descarga del repositorio el archivo `packages.list` y, a partir de ahí, lista las URL de cada ZIP y módulo. No se incluye una lista de paquetes dentro del JAR. El `METADATA/package.list` de cada ZIP es válido para resolver, pero la fuente de verdad de qué paquetes existen y desde dónde descargarlos es el `packages.list` del repositorio.

**Consecuencia:** El manager nunca infiere paquetes mirando el contenido de un JAR; consulta el índice remoto.

### P10. Principio de pluralidad

Si tienes opción a elegir, impleméntalas todas y déjaselo al usuario. No se fuerza un monolito a elegir un solo motor. Cada motor, backend o implementación es un módulo independiente instalable y reemplazable.

**Consecuencia:** `iflow-vm`, `iflow-wasm` e `iflow-firewall` son conceptualmente excluyentes para el routing, pero coexisten como paquetes independientes. El usuario elige cuál instalar y el hub activa uno solo.

### P11. Extensiones sin catálogo cerrado

Las extensiones son módulos simplificados para programación de terceros. No existe un `enum` con todos los módulos posibles. Cualquier repositorio de paquetes de terceros puede otorgar paquetes custom, fuera de lo que hace esta suite. El sistema de descubrimiento debe estar abierto a paquetes arbitrarios declarados en índices remotos.

**Consecuencia:** `ModuleKind` no enumera módulos conocidos. El discovery se basa en `packages.list` y `METADATA/package.list`, no en una lista hardcodeada.

### P12. Tester es un módulo de producción

El módulo `tester` no es harness ni herramienta de desarrollo excluida del artefacto final. Es un módulo de benchmarking en tiempo de ejecución y de prueba del pipeline completo: simula el envío de un jugador, traza la ejecución del mensaje etapa por etapa, inspecciona el comportamiento directo del sistema y lanza un check si la comprobación de todo el pipeline es correcto. Puede detectar fallos delicados, el punto exacto y la diferencia vs lo esperado.

**Consecuencia:** `tester` vive en el directorio de producción y se distribuye como cualquier otro módulo.

### P13. Romper para reestructurar, no parchear

Cuando la arquitectura actual no puede sostener el modelo deseado, no se parchea lo que hay. Se rompe la suite, se desarma todo, se reestructura lo que debe ir y lo que no, y se vuelve a armar como debería haber sido desde el principio. No se aceptan implementaciones a medias, stubs, no-ops ni parches que preserven el acoplamiento actual.

**Consecuencia:** Cada cambio que migra funcionalidad al runtime de módulos elimina el cableado viejo en el mismo cambio. No se mantienen dos formas de arrancar la suite.

### P14. Tests obligatorios por fase

Cada fase de trabajo termina con código, tests, documentación y eliminación del camino viejo. Una fase no se cierra porque compile; se cierra porque tiene su puerta verde.

**Consecuencia:** Si una fase no se completa, se revierte. No se deja código a medias para «completarlo luego».

### P15. Cada bug tiene su test

Cada bug debe tener un test que demuestre que está resuelto. No se considera cerrado un bug sin su prueba de regresión.

**Consecuencia:** Todo fix de bug debe ir acompañado de un test que falle antes del fix y pase después.

### P16. Organización por capa técnica

El directorio `src/` se organiza por capa técnica: `api`, `kernel`, `runtime`, `platform` y `packages`. `platform` contiene cada plataforma de Minecraft soportada; `kernel` contiene cada feature agnóstico, base, primario o importante para la arquitectura del sistema; `packages` contiene cada módulo independiente entre sí.

**Consecuencia:** `spigot-host` y `fabric-host` viven en `platform/`. Los módulos de feature viven en `packages/`. Los contratos del núcleo viven en `api/` y `kernel/`. `sync-bukkit` y `sync-fabric` son paquetes de plataforma: viven bajo `platform/` y se distribuyen como ZIP descargable, no dentro del JAR del host.

### P17. Los eventos de plataforma se capturan en `sync-<plataforma>`

La sección del host que intercepta eventos de Minecraft y los entrega al núcleo agnóstico es, por definición, un sincronizador de plataforma. Se implementa como paquete descargable: `sync-bukkit` para Spigot/Paper y `sync-fabric` para Fabric. Cada host instala solo el paquete compatible con su entorno; ninguno se embebe en el JAR del host.

**Consecuencia:** `spigot-host`/`fabric-host` no contienen `@EventHandler` ni `ServerMessageEvents.CHAT_MESSAGE`; solo aportan `PlatformPorts` y el bootstrap. Los paquetes `sync-bukkit`/`sync-fabric` registran listeners, traducen a `Message`, despachan por `MessageDispatch` y se desregistran en `stop()`; si la plataforma no permite desregistrar, se marca `RESTART_REQUIRED`.

### P18. Los comandos son un contrato neutral y registrable

Cada módulo publica sus comandos jerárquicos a través de una API neutral (`api.command`) y su configuración a través de `api.config`. El registro devuelve un `CommandRegistration`/`ConfigRegistration` ligado al ciclo de vida del módulo; al desactivarlo, el runtime retira su subárbol y su esquema. El host solo traduce la invocación de plataforma; no define comandos de features ni mantiene un árbol duplicado.

**Consecuencia:** `SuiteCommand` y los comandos del runtime usan la misma API que cualquier módulo. `DynamicCommandRegistrar`/`DynamicCommand` y `CommandsConfig*` se eliminaron cuando el registro neutral sustituyó al árbol duplicado del host; el Brigadier de Fabric queda como adaptador de invocación y no hay dos formas de registrar comandos.

### P19. La raíz de comandos es configuración, no código

La raíz (`/suite`, `/suite module`, `/tf`, etc.) es un `CommandRoot` configurable. Los módulos registran paths relativos y nunca hardcodean la raíz. El runtime compone `root + path` y expone `CommandContext.root()` para help/usage/errores.

**Consecuencia:** Cambiar la raíz es cambiar `config/suite/commands.yml`; no se recompilan módulos. Si el cambio en caliente no puede desregistrar el comando raíz de la plataforma, se marca `RESTART_REQUIRED`.

### P20. El compilador C es una herramienta offline del guest

El compilador C de iFlow VM (`tools/iflow-c/`) es una herramienta de build/CI,
no un módulo del host ni un servicio de runtime. Emite `.flowasm` v3 y el
assembler v3 produce la ROM; el servidor solo carga artefactos ya compilados y
verificados. No se compila C en el proceso del host ni se añade una toolchain
nativa al servidor.

**Consecuencia:** El host no crece con Clang/GCC/TinyCC ni con un compilador
propio. La superficie de seguridad sigue siendo la validación de la ROM (hash,
segmentos, permisos y límites). El editor puede invocar el compilador en
cliente/CI, nunca en el host. Un cambio de subconjunto, ABI o formato de salida
requiere enmendar ADR-023.

---

## Referencias cruzadas al ADR-019

La decisión de reestructuración modular vive en el ADR-019. Este PLAN no la duplica: sus principios
operativos se resumen en P1–P3 (Manager como origen de composición, instalar no es activar y adapters
delgados), P7 (no hay doble camino), P11 (extensiones sin catálogo cerrado), P12 (`tester` como
módulo de producción) y P17 (sincronizadores de plataforma descargables).
