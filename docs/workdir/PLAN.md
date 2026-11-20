# PLAN — TextFormatter Suite

> **Obligatorio leer y seguir [La Filosofia](/docs/prompts/contexto/philosophy.md)** antes de trabajar en este documento o en el código.
>
> Documento vivo: reléelo antes de cada sesión de trabajo para no perder el rumbo.
> Última actualización: 2026-11-20 (**V3-2 en curso:** `CoreCountConfig` con `core-count`, `COREID` `0x10A` RO y `SYSINFO.CORE_COUNT`/`FEATURES` bit 6, con 297 tests en `iflow-32`; **ROM2 de `iflow-16` completada:** cabecera de 32 B, TOC de 16 B, segmentos tipados, hash SHA-256 y migración de parser/cargador/compilador/desensamblador/fixtures; contenedor anterior rechazado sin lector doble. TF-002, TF-005 y TF-006 cerrados con test de regresión (fail-closed de `PERMISSION`/`WORLD`; `broadcast` cuenta sinks que aceptan y permite reintento; `broadcastAsync` se retiró al abrir PL-2 y cada acción conserva timeout e identidad explícitos). PL-2 (contratos de superficie de acciones y bus) implementado: `api.sync.*`, `SyncSink.surface()/invoke()`, `SyncBus.surfaces()/invoke()/broadcast(SyncCall)/send/sendTo`, autoridad fail-closed y resultados explícitos; `sync-discord` publica y ejecuta su primera superficie nativa (`channel.create`, `channel.delete`, `message.delete`, `message.edit`, `member.ban`, `role.assign`) sobre los adaptadores JDA y JDK, `sync-telegram` publica y ejecuta la suya (`chat.title.set`, `chat.leave`, `message.delete`, `message.edit`, `chat.member.ban`, `chat.member.unban`) sobre la Bot API y `sync-http` publica y ejecuta `webhook.send` y `endpoint.info` con mapeo explícito de estados HTTP, `sync-websocket` publica `path.broadcast` y `client.info` y corrige el reinicio del servidor, y `sync-socket` publica `endpoint.info`/`connection.probe` en TCP y `endpoint.info` en UDP, y `sync-velocity-core` publica `proxy.info`, `mapping.resolve` y `queue.purge`, y `sync-bukkit`/`sync-fabric` publican `local.info` desde `LocalSyncSink`; `textformatter.channel.set` implementado como acción de host con segunda fase de routing y retirada del import dedicado `set_channel` de la ABI v3; el puente iFlow (`SYNC_SURFACE`/`SYNC_INVOKE` en la VM y `sync_surface`/`sync_invoke` en Wasm, con `TlvCodec` y `SyncBridgeCodec` compartidos en `core-api`) implementado; concesiones por SHA-256 en `config/iflow/grants.yml` (comodines finales, `allow-destructive`), cuotas por programa y auditoría append-only con hash de argumentos redactados en `<data>/config/iflow/audit.log` implementadas (2026-11-13). PL-1 iniciado por slices: contratos neutrales del pipeline en `core-api` (`PipelineStage`, `PipelineContext`, `PipelineMessage`, `StageResult`, `PipelinePlan`, `OutputPlan`), `ComponentRenderedOutput` en `core-render`, `RecipientResolver` extraído, motor de ejecución (`PipelineEngine`, `DeliveryExecutor`, `BoundedPipelineTrace`) con 10 tests, etapas `route`/`format`/`sync` publicadas por `iflow`/`textformatter`/`sync-bus` con 12 tests, y **motor cableado como camino de producción de `suite-runtime`**, alimentado por la lista `pipeline` de `config/suite/config.yml`. La migración de descriptores de ADR-036 §15 está aplicada: ninguna feature exige otra feature, `checkArchitecture` rechaza requisitos no neutrales y los sinks se descubren por servicio publicado (`discoverSinks` + eventos de ciclo de vida) sin que el paquete dependa del bus. La traducción de plantillas permanece en `textformatter` mediante `<tr>` y el puerto `TranslationService`; no se separa como etapa. La fachada legacy `SuiteHost`/`MessageDispatcher` fue retirada: `PipelineEngine`, `RecipientResolver` y `DeliveryExecutor` son el único camino, `DispatchOutcome` informa `status`/`considered`/`failed` y el runtime expone `pipelineTraces()`. La configuración de `outputs` está implementada (mensaje → suite → default del módulo) con `outputs-parallel`; PL-3 cierra contrato, selección por etapa, orden/paralelismo, resultado agregado, entrega local por `sync.bukkit`/`sync.fabric` mediante `message.deliver` y el puerto `LocalDelivery`, la validación de propiedad de backends por etapa con el namespace `sync`, y la documentación de wiki y esquema. P0.1, P0.4, P0.6 y P0.8 cerrados; P0.4 purgó aliases, constructores de compatibilidad, `%s`, `reset`, W2, etiquetas `F7+`, versiones `2.1`/`3.0` y metadatos históricos; CoreTranslator sigue como expansión PAPI legacy `%cot_*` (ADR-035); contrato mínimo `>= 1.0.0` con techo en el host; P0.2, P0.3, P0.5, P0.7, P0.8 y P0.8b cerrados con gate verde. P0.8 podó los ADR duplicados en este PLAN, alineó `docs/prompts/` (raíz de comandos, fases del editor y régimen pre-1.0) y dejó `ADR.md` sin planes ni fases. Se registran `PL-1` —pipeline secuencial configurable—, `PL-2` —acciones de los backends sync—, `PL-3` —sincronización de salidas— y `PL-4` —import/export de la suite en el editor—; PL-4 avanza por slices: lector ZIP, archivo de suite, mapeo, interfaz, CST de YAML, base de paquetes, ROM, desensamblador, validación, exportación DEFLATE, tests de integración y documentación final implementados; PL-4 queda cerrado. **ADR-036 fue aceptado el 2026-11-06**; el catálogo de canales sigue en `textformatter` y su consumo pasa a ser opcional. P0.6 cerró la auditoría integral (TF-001…TF-012) y reemplazó `AUDITORIA.md` por completo. Cierre de detalles (2026-11-13): cuota de llamadas con ventana deslizante de 60 s y test de regresión que falla con ventana fija, `core-render` con `src/test`, `sync-tcpudp` erradicado (build, directorio y `settings.gradle`) e inventario de tests regenerado (43 módulos, 1744 tests, 28 con JaCoCo); `storage.md` §1 se alineó con ADR-036 §15. Quedan registradas **AUD-1** (implementación de `sync`) y **AUD-2** (funcionamiento general del Manager) como revisiones **manuales, hechas por una persona** función por función, cuyo diagnóstico se escribe en `AUDITORIA.md` mientras la auditoría está en curso y queda congelado al terminar (prioridad baja/media). PL-2 se amplía (2026-11-14) con `PATCH` en el `Transport` neutral y `discord.message.edit` en `sync-discord` sobre ambos adaptadores, y con la superficie de entorno/eventos de §17.5 en `sync-bukkit`/`sync-fabric` mediante el puerto neutral `LocalEnvironment` (`player.*`, `console.command`, `scoreboard.set`, `event.*`). **V1 cerrado (2026-11-14, enmendado 2026-11-16): contrato de máquina, ISA y ABI `HOST` v3 congelado en ADR-037; el núcleo sigue en v2 hasta su cableado en V4 y no se abre un camino paralelo v2/v3.** **V2-4 completado (2026-11-15):** memoria física dispersa con snapshots COW por frame, MMU de dos niveles con TLB coherente, bus MMIO supervisor, PIC/timer/UART/display/NVRAM/SYSINFO/mailbox y ABI `HOST` v3 completo (TLV, identidad, capacidades, `ROUTE_DECISION` única y mapeo de `SyncResult`), con 55 tests nuevos; el slice sigue aislado de `VmPolicyEngine`/`VmConfigLoader`/`Iflow16Activator` y el núcleo v2 sigue siendo el vigente. **V0 completado (2026-11-15):** `src/iflow-vm` pasa a `src/iflow-16`, se crea `src/iflow-32`, el hub no cruza el fallback entre ambas líneas y el gate completo queda verde. `iflow-16` queda cerrado funcionalmente (CPU, ROM, `HOST`, cola de entrega y límites documentados). **V3-1 completado (2026-11-16):** reloj virtual `u64`, slice de ejecución con quantum de preempción 1M/50 ms (`PREEMPTED` reanudable, sin fuel ni límite total), watchdog de paso configurable que excluye `HOST`, avance de tiempo en `HALT` y despertar por timer, con 22 tests nuevos (275 en `iflow-32`); **V3-2 en curso (2026-11-20):** `CoreCountConfig` carga `core-count` con fallo explícito para `>1`, `CsrFile` publica `COREID` `0x10A` (RO) y `SYSINFO` publica `CORE_COUNT`/`FEATURES` bit 6 y las atómicas `LR.W`/`SC.W`/`AMO*`; faltan estado por core, IPI, PIC por core, TLB shootdown, scheduler de guest, watchdog por core y journal concurrente (297 tests en `iflow-32`); **V3-3 pendiente:** journal por mensaje y persistencia entre mensajes. `iflow-16` conserva un único vCPU por diseño; hasta V3-2, `CORE_COUNT > 1` falla explícito. Siguiente: cerrar V3 y, en V4, cablear el núcleo v3 sin retirar `iflow-16`. Se registra como pendiente de prioridad media/alta la estandarización little-endian única en ambas líneas; `iflow-32` ya la cumple, mientras que `iflow-16` conserva el layout ROM2 mixto hasta enmendar ADR-038. PL-6 queda registrado como documentación de revisión pendiente: caches L1/L2, consulta HOST de presencia de sync, ROM persistente ext4/FAT32 y rediseño del editor de nodos; sin ADR ni implementación.)

> **Enmienda de multinúcleo (2026-11-16):** `iflow-32` es una sola VM con **N núcleos
> configurables**, no N VMs; `iflow-16` conserva **un único vCPU por diseño**. ADR-037 queda
> enmendado. La implementación es **V3-2** y, hasta que exista, `CORE_COUNT > 1` falla explícito.

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
> `engine: iflow-firewall|iflow-16|iflow-32|iflow-wasm` y `fallback-on-unavailable`. Default
> `iflow-firewall`. `SuiteRuntime` exige `IflowHub`; un motor no puede publicar un `Router` que
> sustituya al hub. `iflow.engine.kind` fue eliminado en P0.3 (ADR-034).
> `iflow-firewall`, `iflow-16`, `iflow-32` e `iflow-wasm` publican su backend; Wasm exige SHA-256 y recarga automáticamente.

> **Estado actual (2026-11-04).** Base integrada en construcción; no hay versión estable ni
> producción. Cerrados en su alcance: comandos neutrales y raíz configurable, sincronizadores de
> plataforma, empaquetado reproducible, paquetes de terceros y presets. ADR-027 implementado: adquisición,
> commit con journal y activación separada; la etapa 2 es el único rollback automático. `iflow-16`
> conserva el núcleo v2 de 16 bits; `iflow-32` implementa el contrato v3 congelado en ADR-037;
> `iflow-wasm` (Chicory) es la línea paralela. W3.5 (paridad `.flow` v3/VM) sigue pendiente y V6
> (compilador C) está bloqueado por V5. `CoreTranslator` se
> restauró como expansión PAPI legacy `%cot_*` (ADR-035). El E2E real es posterior a `1.0.0`.
> El historial cerrado vive en Git; este PLAN solo conserva estado y trabajo activo.

---

## Purga pre-1.0 — hoja de ruta inmediata (ADR-034)

> **Directriz de corto plazo:** no se inicia una feature nueva hasta cerrar la purga de
> compatibilidad y los bugs estructurales P0/P1 de la auditoría vigente. Cada tarea termina con
> código, tests, documentación y gate verde; no se aceptan cambios a medias ni “arreglos” que
> conserven el camino viejo.
>
> **Excepción registrada (2026-11-05, actualizada el 2026-11-06):** `PL-1` (pipeline secuencial
> configurable) dejó de ser una propuesta: su implementación avanza por slices con gate verde.
> ADR-036 fue aceptado el 2026-11-06 y la purga P0 está cerrada. La nota de esta sección no autoriza
> cambios en `docs/prompts/`.

| # | Tarea | Criterio de cierre | Estado |
|---|---|---|---|
| **P0.1** | Inventario de versiones y compatibilidad | Lista `archivo:línea` de todo `2.x`/`3.x`, `config-version`, aliases legacy, rutas planas, adaptadores, shims y módulos *deprecated*. Sin elementos sin clasificar. | Completado (2026-11-05; inventario integrado en este PLAN) |
| **P0.2** | Reset de versiones a la base `1.0` | `build.gradle` raíz y de módulos en `1.0.0`; manifiestos, capabilities y contratos `*-api` en `1.0.0`; todo `.yml` con `config-version: 1.0` en la raíz, preferentemente al final. `plugin.yml` queda excluido. Si falta `config-version`, el parser falla con un error explícito. Un sufijo de build no cambia la base funcional, pero su publicación se rige por la política de artefactos. | Completado (2026-11-04) |
| **P0.3** | Purga de configuración legacy | Eliminar `iflow.engine.kind`, `base-name` (si queda alguno; el código actual ya lo trata como retirado), lectores de la raíz plana de `config/` (`config/config.yml`, `config/channels/`, `config/translators/`, `config/sync/`, `config/rules.yml`, `config/inworld.yml`) y cualquier fallback que acepte el esquema viejo. Los `config/<module>/config.yml` canónicos se conservan. Las rutas incorrectas se ignoran o se rechazan explícitamente. | Completado (2026-11-04) |
| **P0.4** | Purga de compatibilidad y módulos legacy | Eliminar adaptadores, aliases, dobles lectores, puentes de conversión y fachadas de compatibilidad. Restaurar `CoreTranslator` como módulo legacy independiente, marcado *deprecated* y Bukkit-only por PlaceholderAPI (ADR-035); no como shim ni recuperando `suite-common-legacy`. El `LegacyBridge` de `7dd164a4^` no se restaura. Actualizar todos los consumidores en el mismo cambio. | Completado (2026-11-05) |
| **P0.5** | Purga de tests de compatibilidad | Eliminar o reescribir los tests que afirman el contrato viejo o la migración legacy. Añadir regresiones que demuestren rechazo o ignorancia del camino viejo, tests de `config-version`, tests de contrato `>= 1.0.0` y tests de `CoreTranslator`. | Completado (2026-11-04) |
| **P0.6** | Auditoría vigente | `AUDITORIA.md` contiene la auditoría integral de 2026-11-06 (TF-001…TF-012), con cobertura, evidencia, matriz y roadmap; el ciclo documental anterior (D-001…D-010) queda cerrado. Este PLAN mantiene el estado operativo y el mapeo P0/P1/P2 ↔ Alta/Media/Baja. Los hallazgos P0/P1 se corrigen con test de regresión antes de avanzar. | Completado (2026-11-06; informe reemplazado por completo) |
| **P0.7** | Gate completo | `./gradlew build checkArchitecture checkLocks --no-daemon` en verde, sin fallos tolerados. Los 10 tests condicionales omitidos (`logger` 3, `spigot-host` 4, `loadtest` 3) declaran su condición en el propio test y no enmascaran un fallo. | Completado (2026-11-04) |
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
| Archivos `.yml` de configuración | 37 (32 de `main` + 5 fixtures de test); 36 con `config-version: 1.0` en la raíz y como última línea |
| Excepción de `config-version` | `src/spigot-host/src/main/resources/plugin.yml` (descriptor de plugin) |
| Aliases de configuración legacy | 0 en producción; eliminados en P0.4 y cubiertos por tests de rechazo |
| Lectores de rutas planas | 0 en producción; los tests demuestran que las rutas planas se ignoran |
| Adaptadores, shims y fachadas de compatibilidad | 0; `LegacyBridge`/`suite-common-legacy` no existen |
| Módulos *deprecated* | Solo `CoreTranslator` (`@Deprecated`); `iflow-firewall` es motor legacy permitido, no shim |
| Deuda de metadatos | Ninguna: las entradas propias `2.1.0-SNAPSHOT` se retiraron de `verification-metadata.xml` |

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

Los 36 YAML de configuración (31 de `main` + 5 fixtures de test) declaran `config-version: 1.0`
como clave raíz y última línea no vacía.

- Defaults de `spigot-host`: `config.yml:25`, `commands.yml`, `messages.yml:53`, `iflow.yml`,
  `iflow-grants.yml`, `rules.yml:14`, canales, `sync/discord.yml`, `sync/websocket.yml`,
  `translators/google.yml`.
- Defaults de `fabric-host`: el mismo conjunto (incluido `iflow-grants.yml`), con sus rutas canónicas.
- Defaults de módulos: `inworld.yml`, `sync-bukkit/config.yml`, `sync-fabric/config.yml`,
  `sync-velocity/sync/velocity.yml`.
- Fixtures del editor: `src/host/src/test/resources/editor-default/config/suite/config.yml:16` y
  los cuatro canales de `config/textformatter/channels/`.
- Excluido por contrato: `src/spigot-host/src/main/resources/plugin.yml`.

La comprobación automatizada usada para este inventario fue:

```text
37 archivos; 36 con config-version; todos en la última línea no comentada.
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

**Corrección de hallazgos P0 (2026-11-06/07):** TF-002, TF-005 y TF-006 cerrados con test de regresión.
TF-002 aplica fail-closed en `RecipientResolver`: `PERMISSION` y `WORLD` sin qualifier entregan a
cero y registran un aviso, en lugar de ensanchar el mensaje a todos los jugadores. TF-005 hace que
`DefaultSyncBus.broadcast` ejecute el fan-out en el hilo llamante, devuelva cuántos sinks aceptaron
realmente y libere la marca de dedup cuando ninguno acepta, de modo que el reintento es posible tras
drenar las colas. TF-006 ata cada futuro de `broadcastAsync` a la tarea concreta de su mensaje: se
completa cuando `send` retorna, falla de forma explícita por rechazo, reintentos agotados, parada o
timeout acotado, y nunca deduce éxito de una cola vacía. `broadcastAsync` se retiró al abrir PL-2;
su garantía (timeout explícito e identidad por acción) se conserva en `invoke`/`send`. PL-1 avanza por slices con gate verde: contratos neutrales en `core-api`,
`RecipientResolver` extraído, motor de ejecución (`PipelineEngine`, `DeliveryExecutor`,
`BoundedPipelineTrace`) con 10 tests, etapas `route`/`format`/`sync` con 12 tests y motor ya
cableado como camino de producción, alimentado por `pipeline` en `config/suite/config.yml`.
La independencia de descriptores de ADR-036 §14/§15 también está aplicada: se retiraron los
`Requirement` entre features, `checkArchitecture` rechaza cualquier requisito que no sea
`api`/`jvm`/`kernel`/`manager-spi`/`platform`, y `DefaultSyncBus` descubre los `SyncSink`
publicados y se refresca con `ModuleActivatedEvent`/`ModuleDeactivatedEvent`. Los sinks estáticos
publican el servicio y detienen sus propios recursos en `stop()`, de modo que la liberación del
módulo no depende de que el bus exista.
Siguiente P0 de la auditoría: TF-001 (PL-1), concretamente la configuración de `outputs`.
La retirada final de `SuiteHost`/`MessageDispatcher` quedó cerrada y TF-006 desbloquea PL-2.

**Nota sobre la cita a `P0.1-inventory.md`:** el informe anterior remitía a
`docs/workdir/P0.1-inventory.md`, ruta que no existe; el inventario vigente está integrado en este
PLAN. El informe de 2026-11-06 ya no contiene esa cita. No se crea un archivo auxiliar.

**Orden de trabajo:** cierre de los P0 de la auditoría (TF-002 ✅ → TF-005 ✅ → TF-006 ✅ → TF-001/PL-1) → W3.5 (feature nueva + fix masivo) → V6 (bloqueado por V5; la ejecución del corpus requiere V2–V4).

**Cierre del ciclo anterior:** D-009 quedó resuelto. `IflowBackendManager` aplica la prioridad
explícita `iflow-firewall` → `iflow-16`/`iflow-32` → `iflow-wasm`; nunca hay fallback entre
`iflow-16` e `iflow-32`. El test de aceptación está en `IflowHubTest`.

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
- W3.5 espera la paridad `.flow` v3/VM y V6 sigue bloqueado por V5; V1 ya está cerrado.
- Con la independencia de descriptores (ADR-036 §15), el orden de activación deja de estar impuesto
  por el grafo. `coretranslator` se activa inerte si `chattranslator` no está presente; para usar sus
  placeholders hay que reactivarlo después de instalar la fachada. El aviso de activación lo indica.

### Después de 1.0.0 — betatesting/E2E real

La base `1.0.0` se alcanza con la suite integrada; el betatesting/E2E se ejecuta **después** y no
forma parte del cierre de la purga pre-1.0. Sin este ciclo no se declara estabilidad ni producción.

| # | Tarea | Criterio de cierre | Estado |
|---|---|---|---|
| **E2E.1** | Betatesting/E2E posterior a 1.0.0 | Probar manualmente todas las features de la suite en servidores reales Spigot/Paper, Fabric y Velocity, con matriz de versiones y evidencia. No es un smoke test de carga. Registrar resultado y declarar **desconocida** toda combinación no probada. Los fallos se corrigen en la línea beta; la estabilidad/producción se declara solo al cerrar este ciclo. | Pendiente |

---

### Feature propuesta — pipeline secuencial configurable (PL-1, 2026-11-05)

> **Estado:** implementado (PL-1 y PL-3 cerrados). **ADR-036 aceptado
> (2026-11-06).** Sustituye o enmienda ADR-019 (composition root y
> dependencias), ADR-024/025 (puertos neutrales y adapters), ADR-032 (hub iFlow), ADR-008
> (backend→fachada) y ADR-003 (§16, autoridad del guest) en lo relativo a los requisitos entre
> features y al orden del pipeline; adopta ADR-031 en la semántica de routing (§16 regula la
> exposición de `SET_CHANNEL` sin alterar D01–D05). La purga P0 está cerrada (P0.6, 2026-11-06),
> así que no queda bloqueo documental. Implementado hasta ahora: contratos neutrales del pipeline
> en `core-api`, `ComponentRenderedOutput` en `core-render`, motor de ejecución, etapas
> `route`/`format`/`sync`, cableado del motor en `suite-runtime`, independencia de descriptores
> (§14/§15), consumo opcional del catálogo de canales y descubrimiento de sinks por servicio.
> La retirada de `SuiteHost`/`MessageDispatcher` como fachada legacy está cerrada: el motor,
> `RecipientResolver` y `DeliveryExecutor` son el único camino de despacho, y la traza se
> publica en `SuiteRuntime.pipelineTraces()`. La configuración de `outputs` (§17) quedó
> implementada en PL-3: precedencia mensaje → suite → default del módulo, `outputs-parallel` y
> entrega local por `message.deliver`.
> La traducción de `<tr>` se mantiene dentro del
> render de `textformatter`; no se traslada a una etapa `translate`. Cualquier cambio en
> `docs/prompts/` requiere autorización explícita. La sincronización de salidas de §17 se planifica
> en PL-3.

#### Hallazgo estructural

Hoy la suite no es una colección de módulos opcionales: el grafo de módulos convierte features en
requisitos de otras features y el orden de procesamiento está cableado en código. Evidencia
verificada el 2026-11-05 sobre `2f5d3b27`:

| Hecho verificado | Evidencia | Efecto |
|---|---|---|
| `textformatter` es el único proveedor de `channel-api` | `TextFormatterModule.java:24`; `TextFormatterActivator.java:32` | El catálogo y su capability pertenecen a `textformatter`; es correcto por diseño. El problema es que otras features lo exigen como requisito. |
| Los tres motores iFlow requieren `channel-api` | `IflowFirewallModule.java:27`; `Iflow16Activator.java:35`; `IfowWasmModule.java:23`; `IflowWasmActivator.java:34` | El requisito duro deja los motores inactivos si falta `textformatter`; debe ser un consumo opcional. |
| `inworld`, `sync-bukkit`, `sync-fabric` y `tester` también requieren `channel-api` | `InWorldModule.java:27`; `SyncBukkitActivator.java:45`; `SyncFabricActivator.java:46`; `TesterModule.java:25` | Cuatro features más dependen de que `textformatter` esté instalado. |
| `SuiteRuntime` exige servicios concretos para construir el pipeline | `SuiteRuntime.java:203-211`; `resolveRouter()` en `245-253` | Sin `ChannelRegistry`, `TranslationService`, `IflowHub`, `MessageRenderer`, `ChatDelivery`, `ActorDirectory` o `PermissionChecker` no hay pipeline utilizable. El fallo se registra como «pipeline unavailable» (`SuiteRuntime.java:225-232`), no como arranque funcional. |
| El orden del pipeline está hardcodeado | `SuiteHost` (`SuiteHost.java:150-162`) y `MessageDispatcher` (`MessageDispatcher.java:40`) | No existe una lista ordenada configurable; el orden lo imponen el cableado y el grafo. |
| El catálogo de canales vive en `textformatter` | `ChannelConfig.java:22,73`; `TextFormatterActivator.java:34-39` | Decisión de diseño: el catálogo y el routing de canal son intrínsecos de `textformatter`; no se mueven al runtime. |
| La ruta `config/textformatter/channels/` está fijada fuera del módulo | `FilePresetTarget.java:96`; `DefaultConfigInstaller.java:54-57`; `config-schema-v2.2.md`; `storage.md` | Se conserva como ruta canónica; el catálogo de `textformatter` se carga desde ahí sin moverla. |
| Los backends de traducción requieren `chattranslator` | `GTranslateModule.java:23`; `LTranslateModule.java:23`; `CoreTranslatorModule.java:32`; ADR-008 | La relación backend→fachada es hoy un requisito de activación entre features; PL-1 debe decidir si un backend es instalable solo o si la fachada es su único punto de entrada. |
| El dispatcher conserva semántica de routing retirada | `MessageDispatcher.java` procesa `PolicyTarget.CHANNEL_REDIRECT` y reentrega; `PolicyTarget.java:23` conserva el valor | ADR-031 retira `REDIRECT`/`CHANNEL_REDIRECT`; el rediseño del pipeline debe alinear el código con ese contrato. |

Matiz importante: `textformatter` sí tolera la ausencia de `ChatTranslator`
(`TextFormatterActivator.java:48` usa `TextFormatters.emptyTranslation()`), de modo que el
acoplamiento no es de compilación de TextFormatter hacia ChatTranslator. El acoplamiento real está
en el grafo de requisitos y en el composition root: el runtime no tolera la ausencia de
`ChannelRegistry`, de `TranslationService` ni del hub iFlow para construir el pipeline.

`config/channels/` (ruta plana) está prohibida por P0.3/ADR-034 y no puede reutilizarse como ruta
canónica del catálogo de canales.

#### Principio propuesto

1. **La suite arranca y opera sin features.** El núcleo (comandos, ciclo de vida, registro de
   servicios, configuración y captura de plataforma) no exige ningún módulo de feature.
2. **Ninguna feature requiere otra feature.** Un módulo solo puede requerir contratos neutrales del
   núcleo o del runtime; nunca la capability de otro módulo opcional. El uso de un servicio de otra
   feature es opcional en runtime: si falta el proveedor, solo falla la operación que lo necesita.
3. **La ausencia de una etapa es un paso explícito, no un fallo ni un no-op silencioso.** El mensaje
   continúa sin esa transformación y la traza registra la etapa como ausente/saltada.
4. **El pipeline es secuencial e inyectivo.** Cada etapa recibe el mensaje, lo transforma y lo
   entrega a la siguiente. El orden se declara en configuración; no se deduce del grafo de
   dependencias ni del orden de activación.
5. **El grafo de módulos se conserva para el ciclo de vida y los servicios neutrales**, pero no
   impone el orden del pipeline ni convierte una feature en requisito de otra.
6. **El catálogo de canales pertenece a `textformatter`.** No se mueve al núcleo ni al runtime: el
   routing de canal es una función intrínseca de esa feature. Lo que cambia es su consumo: las
   demás features lo buscan de forma opcional y solo fallan si intentan usarlo sin él.
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
| `core-api` | Contratos de etapa, contexto, resultado, traza y `Optional<ChannelRegistry>`; `Channel`/`ChannelRegistry` se conservan. |
| `suite-runtime` | Construir el pipeline desde `pipeline` + `ServiceRegistry`; dejar de exigir servicios concretos y de fijar el orden en código. |
| `textformatter` | Conservar `channel-api` y el catálogo de canales; añadir `provide("stage:format")` y publicar la etapa de formato. |
| `iflow` y motores | Consumir `ChannelRegistry` de forma opcional, no como requisito; publicar una etapa de routing. |
| `chattranslator` y backends | Conservar la fachada y `TranslationService`; no publicar etapa de traducción (`<tr>` se resuelve dentro de `format`); revisar el requisito backend→fachada de ADR-008. |
| `sync-*`, `inworld`, `tester` | Consumir el mensaje final o una etapa; no declarar `channel-api` como requisito. |
| `presets` | Escribir el catálogo de `textformatter` en la ruta canónica vigente, `config/textformatter/channels/`; la ruta no se mueve. |
| `DefaultConfigInstaller` y hosts | Instalar el catálogo de `textformatter` y un `pipeline` por defecto coherente con lo instalado. |
| `checkArchitecture` | Guard aplicado en `build.gradle`: ninguna feature declara `Requirement` sobre otra feature; solo `api`/`jvm`/`kernel`/`manager-spi`/`platform` son exigibles. El orden del pipeline no se deriva del grafo y el consumo opcional no es un `Requirement`. |
| Configuración y documentación | `config/suite/config.yml`, `storage.md`, `config-schema-v2.2.md`, wiki y READMEs. Los documentos de `docs/prompts/` requieren autorización. |

**Nota de alcance:** PL-1 no es solo añadir una lista. Exige **rediseñar el pipeline actual**
(`SuiteHost`/`MessageDispatcher`), el modelo de requisitos del grafo y el consumo opcional del
catálogo de canales. La lista de módulos es la superficie visible; el cambio de fondo es qué
servicios son obligatorios y cómo se compone el procesamiento. El motor ya sustituye a
`MessageDispatcher` como camino de despacho; la independencia de descriptores y el descubrimiento
de sinks ya están aplicados. La fachada legacy está retirada y solo queda la configuración de
`outputs`; la traducción de `<tr>` permanece dentro de `format`.

#### Preguntas abiertas (resueltas por ADR-036, aceptado el 2026-11-06)

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
3. **Solo iFlow:** arranca y enruta sin `textformatter` ni `chattranslator`; sin `format` se entrega
   texto plano. Las operaciones que necesiten el catálogo de canales fallan de forma explícita, sin
   bloquear el pipeline.
4. **Solo `chattranslator`:** traduce con un render/pass-through neutral, sin `textformatter` ni
   iFlow.
5. **Orden configurable:** el mismo conjunto en dos órdenes produce resultados distintos y
   trazables; el orden no depende del grafo ni del orden de registro.
6. **Etapa ausente o no listada:** comportamiento explícito y probado, sin éxito fingido.
7. **Guard de independencia:** un descriptor de feature no declara `Requirement` sobre otra feature;
   solo sobre contratos neutrales. El consumo opcional en runtime no se declara como requisito.
8. **Regresión:** la configuración base reproduce el comportamiento actual cuando declara el orden
   vigente.
9. **Gate:** `./gradlew build checkArchitecture checkLocks --no-daemon`; si se toca el editor,
   `npm run check`.

#### Bloqueos

- **ADR-036 aceptado (2026-11-06).** Resolvió las doce preguntas abiertas y enmendó ADR-019,
  ADR-024/025, ADR-032, ADR-008 y ADR-003; adoptó ADR-031 en la semántica de routing. §16 añade la
  superficie de acciones de los backends sync.
- Purga P0 cerrada (P0.6, 2026-11-06).
- Autorización explícita para modificar `docs/prompts/diseno/storage.md`,
  `docs/prompts/especificaciones/config-schema-v2.2.md`, `docs/prompts/diseno/iflow-cpu-design.md`,
  `docs/prompts/diseno/iflow-cpu-implement.md` y el resto de documentos de diseño **solo si** la
  implementación cambia sus rutas o su esquema; la ruta de canales no cambia.
- Implementación en curso: los contratos neutrales (`PipelineStage`, `PipelineContext`,
  `PipelineMessage`, `StageResult`, `PipelinePlan`, `StageTrace`, `OutputPlan`) viven en `core-api`;
  `RecipientResolver` está extraído de `MessageDispatcher` con la política fail-closed intacta. El
  motor (`PipelineEngine`), la entrega (`DeliveryExecutor`), la traza acotada (`BoundedPipelineTrace`)
  y las etapas `route` (`iflow`), `format` (`textformatter`) y `sync` (`sync-bus`) están cubiertos
  por tests y **ya son el camino de producción**: `suite-runtime` construye el motor desde la lista
  `pipeline` de `config/suite/config.yml` y `MessageDispatch` entrega a través de él. `HostConfig`
  añade `pipeline`; un archivo existente sin esa clave es inválido (solo la ausencia del archivo usa
  los defaults del instalador), los ids base siempre se conocen y una etapa base inactiva queda
  `SKIPPED`, mientras que un id no base desconocido invalida el plan. `SuiteHost` y
  `MessageDispatcher` fueron retirados: `PipelineEngine`, `RecipientResolver` y
  `DeliveryExecutor` son el único camino, con `DispatchOutcome` de ocho campos y traza expuesta
  por `SuiteRuntime.pipelineTraces()`. La traducción de `<tr>` permanece en `TemplateRenderer`
  dentro de la etapa `format`; no se traslada a una etapa `translate`. Falta la configuración de
  `outputs` (§17); la migración de descriptores y el guard de independencia de
  `checkArchitecture` están aplicados.

### Feature propuesta — superficie de acciones de los backends sync (PL-2, 2026-11-06)

> **Estado:** contratos, bus, superficies nativas de `sync-discord`,
> `sync-telegram`, `sync-http`, `sync-websocket`, `sync-socket` y
> `sync-velocity-core`, puente iFlow, concesiones por SHA-256, cuotas y
> auditoría implementados (2026-11-12/13); el resto de backends sigue pendiente.
> Depende de **ADR-036 §16** (aceptado el 2026-11-06); **TF-006 quedó cerrado el
> 2026-11-07** y TF-005 el 2026-11-06.

#### Objetivo

Cada backend sync (`sync-discord`, `sync-telegram`, `sync-http`, `sync-websocket`, `sync-socket` y
`sync-velocity-core`) conoce su plataforma y publica todas sus operaciones nativas como un esquema de
datos. La suite oficial consume solo el mínimo común múltiplo (LCM). Los programas de iFlow de
terceros pueden invocar cualquier acción nativa que declaren y que el operador les conceda, lo que
permite construir un bot completo de Discord o Telegram sin que el núcleo conozca esos SDK.
`SET_CHANNEL` deja de ser una operación oficial y pasa a ser la acción agnóstica
`textformatter.channel.set`, disponible para terceros.

#### Modelo

| Nivel | Consumidor | Contenido |
|---|---|---|
| LCM oficial | pipeline `sync`, features oficiales, `CoreTranslator.sendDiscord` | `backend.info`, `message.send` |
| Agnóstico extendido | programas de terceros | `textformatter.channel.set` y acciones agnósticas acordadas |
| Nativo | programas de terceros | `discord.channel.create`, `discord.channel.permission.overwrite`, `discord.role.assign`, `discord.message.edit`, `discord.member.ban`, `telegram.chat.member.ban`, etc. |

`Principal.Official` solo puede invocar el LCM; `Principal.Program` solo lo concedido. Sin concesión
no hay efecto. La capacidad se deriva del origen: `sync.<backend>.<accion>` para sinks
(`sync.discord.channel.create`) y `module.<moduleId>.<accion>` para módulos/host
(`module.textformatter.channel.set`).

#### Estado actual y evidencia

| Hecho | Evidencia | Efecto |
|---|---|---|
| `SyncSink` publica superficie e invoca acciones | `SyncSink.surface()`/`invoke()`; `DefaultSyncBus.register` valida LCM y namespace | LCM y ejecución disponibles; `sync-discord`, `sync-telegram`, `sync-http`, `sync-websocket`, `sync-socket` y `sync-velocity-core` declaran y ejecutan sus acciones nativas. |
| `broadcast` cuenta los sinks que aceptaron | `DefaultSyncBus.send`/`broadcast`; TF-005 cerrado | El fan-out no confunde intento con aceptación. |
| Timeout e identidad por acción | `DefaultSyncBus.effectiveTimeout`/`dispatchCall`; `DefaultSyncBusTest` | `broadcastAsync` se retiró; cada acción conserva timeout explícito y resultado propio. |
| `set_channel` es un import dedicado de v3 | `PolicyAbiV3.SET_CHANNEL`; `PolicyHost.setChannel` | No es genérico ni está sujeto a concesiones por acción. |
| Los sinks se descubren por servicio | `DefaultSyncBus.discoverSinks`; eventos de ciclo de vida | Sin `feature → feature`; el paquete no depende del bus. |
| Concesiones, cuotas y auditoría | `ProgramGrant`/`ProgramGrantStore`, `ActionAudit`/`ActionAuditRecord`, `YamlProgramGrantStore`, `AppendOnlyActionAudit`; `DefaultSyncBusAuthorizationTest` | Un tercero solo ejecuta la intersección de su declaración y la concesión del operador; sin concesión, sin auditoría escribible o sin cuota no hay efecto. |

> **Nomenclatura:** `sync-tcpudp` está retirado; el módulo vigente es `sync-socket`. El nombre ya no
> aparece en build ni en el código: se retiró de `build.gradle`, se eliminó el directorio vacío
> `src/sync-tcpudp` y `src/sync-socket/settings.gradle` declara `suite-sync-socket`. La pestaña
> «TCP / UDP» del editor (`data-edge="tcpudp"`) es un ancla de UI para las claves `sync.tcp.*` y
> `sync.udp.*`, no una referencia al módulo retirado.

#### Impacto previsto

| Área | Cambio |
|---|---|
| `core-api` | **Implementado (2026-11-07):** `SyncSurface`, `SyncActionSpec`, `SyncParamSpec`, `ActionKind`, `SyncCall`, `SyncResult`, `Principal`, `SyncValueType`; `SyncSink`/`SyncBus` ampliados. Sin tipos de SDK. |
| `sync-bus` | **Implementado (2026-11-07):** registro y validación de superficies, `invoke`, fan-out exclusivo del LCM, `send`/`sendTo`, timeout por acción y autoridad fail-closed; `invokeHost` y `HostActionHandler` para acciones agnósticas (2026-11-13). **Concesiones y cuotas (2026-11-13):** capacidad derivada, `allow-destructive`, `calls-per-minute`, `max-payload-bytes` y auditoría con hash de argumentos redactados. |
| `sync-*` | Implementar `surface()` e `invoke()`; publicar `SyncSink` sin requerir el bus; declarar `stage:sync` solo en `sync-bus`. `sync-bukkit`/`sync-fabric` publican además el sink local y las acciones del entorno, eventos incluidos. |
| `iflow-16` | **Implementado (2026-11-13):** servicios `SYNC_SURFACE`/`SYNC_INVOKE` con TLV compartido, DMA, fuel y mapeo de estados; `set_channel` no existe. |
| `iflow-wasm` | **Implementado (2026-11-13):** imports `sync_surface`/`sync_invoke` con el mismo TLV y estados; `set_channel` retirado. |
| `suite-runtime` | **Implementado (2026-11-13):** `YamlProgramGrantStore` (carga estricta y recarga atómica), `AppendOnlyActionAudit` (`<data>/config/iflow/audit.log`, `force` por entrada) y registro/cierre en `SuiteRuntime`. |
| Configuración | `config/iflow/grants.yml` con `defaults`, `capabilities`, `allow-destructive`, `calls-per-minute` y `max-payload-bytes`; default vacío instalado en ambos hosts; ruta de auditoría en `storage.md` §5. |
| Tests | Conformidad VM/Wasm, autorización, destructivos, cuotas, `textformatter.channel.set` y regresión del LCM. |

#### Pasos de implementación

1. **Prerrequisito TF-006 (cerrado). ✅** `broadcast` cuenta éxitos reales y libera el dedup (TF-005,
   cerrado el 2026-11-06). TF-006 ató cada futuro a su mensaje con timeout explícito (2026-11-07);
   al abrir PL-2 se retiró `broadcastAsync` y la misma garantía vive en `invoke`/`send`. Los tests de
   regresión viven en `DefaultSyncBusTest`.
2. **Contratos neutrales. ✅** Añadir los tipos de §16.3 a `core-api` y ampliar `SyncSink`/`SyncBus`.
   `checkArchitecture` prohíbe JDA, Telegram, Bukkit, Fabric y cualquier SDK en `core-api` y
   `sync-bus`. Test de que una superficie se serializa sin tipos de plataforma.
3. **Bus de acciones. ✅ (2026-11-13)** Indexar `SyncSurface` por backend, validar duplicados, enrutar `invoke`,
   aplicar timeout/cuotas, emitir auditoría y mapear cada estado a `SyncResult`. `send(Message)` pasa
   a ser una comodidad sobre `message.send`.
4. **Backends. ✅ (2026-11-13; superficie de entorno/eventos de §17.5 cerrada el 2026-11-14)** Migrar
   `sync-discord` y `sync-telegram` primero (superficie completa: canales, roles, mensajes,
   miembros, moderación); después `sync-http`, `sync-websocket`, `sync-socket`,
   `sync-velocity-core`, `sync-bukkit` y `sync-fabric` (LCM, entrega local y nativas que apliquen).
   Una acción no soportada devuelve `Unsupported`, nunca un no-op.
5. **`textformatter.channel.set`. ✅ (2026-11-13)** Implementar el handler de host en el motor del pipeline,
   reutilizando la segunda fase de §7 y la regla de una sola mutación. `Principal.Official` recibe
   `Denied`; un tercero con `module.textformatter.channel.set` puede invocarlo.
6. **Puente iFlow. ✅ (2026-11-13, salvo la suite de conformidad cruzada de W3.5)** Añadir
   `SYNC_SURFACE`/`SYNC_INVOKE` en la VM y `sync_surface`/`sync_invoke` en Wasm, con el mismo
   `TlvCodec` y los mismos estados; retirar `set_channel` y actualizar fixtures y programas de
   ejemplo. Los vectores de cable compartidos viven en `core-api`; la suite de conformidad que
   ejecuta el mismo artefacto en ambos motores queda en W3.5.
7. **Concesiones y auditoría. ✅ (2026-11-13)** `config/iflow/grants.yml` indexado por SHA-256, comodines
   finales en la concesión, `allow-destructive`, cuotas por programa y registro append-only con hash
   de argumentos redactados. Un permiso ausente, ambiguo o no verificable deniega; un payload que
   excede el límite devuelve `Denied` y la cuota agotada `RateLimited`.
8. **Guardas.** `checkArchitecture`: ninguna feature requiere otra; ningún backend importa el núcleo
   del bus; las políticas oficiales no declaran `sync.invoke`. Test de que el LCM no invoca acciones
   nativas.
9. **Documentación y esquema.** Actualizar `storage.md` (submódulo, ruta de auditoría),
   `config-schema-v2.2.md`, la especificación iFlow y la wiki. `docs/prompts/` requiere autorización
   explícita.

#### Estado de implementación (2026-11-07)

Completado en esta unidad:

- Contratos de ADR-036 §16.3 en `core-api`: `ActionKind`, `Principal`,
  `SyncActionSpec`, `SyncCall`, `SyncParamSpec`, `SyncResult`, `SyncSurface` y
  `SyncValueType`; `SyncSink` gana `surface()`/`invoke()` con LCM por defecto.
- `SyncBus` gana `surfaces()`, `invoke(SyncCall)`, `broadcast(SyncCall)`,
  `send(Message)` y `sendTo(String, Message)`; se retiran
  `broadcastAsync(Message)` y `broadcast(Message): int`.
- `DefaultSyncBus` valida el LCM y el namespace, aplica autoridad fail-closed,
  valida argumentos, encola `message.send` por backend, acota cada acción con su
  timeout (máximo 30 s), reintenta solo acciones idempotentes y mapea todo fallo
  a `SyncResult`.
- `SyncStage` y `CoreTranslatorPlaceholders` usan `send`/`sendTo`.
- Tests de regresión en `DefaultSyncBusTest`, `DefaultSyncBusIntegrationTest`,
  `SyncStageTest`, `CoreTranslatorPlaceholdersTest` y `SyncContractTest`.

Pendiente y fuera de esta unidad:

- Migración de los backends restantes (`sync-bukkit` y `sync-fabric`):
  `sync-discord`, `sync-telegram`, `sync-http`, `sync-websocket`, `sync-socket`
  y `sync-velocity-core` ya publican sus nativas; los sinks locales publican
  `local.info` y la superficie de entorno y eventos de §17.5 (2026-11-14).
- La suite de conformidad cruzada VM/Wasm y la paridad end-to-end de
  `SyncResult` (W3.5).
- La retirada del fuel queda para la migración v3 (véase «Trayectoria v2 → v3 y
  fuel»).

#### Estado de implementación — `textformatter.channel.set` (2026-11-13)

Completado en esta unidad:

- `core-api` gana `HostActionHandler` y `HostActionContext` en `api.sync`, y
  `SyncBus.invokeHost(SyncCall, HostActionContext)` con implementación por
  defecto `Unsupported`. Las acciones de host no publican `SyncSurface`: el bus
  las resuelve por su nombre canónico.
- `DefaultSyncBus` indexa los `HostActionHandler` publicados por
  `ServiceRegistry` en `discoverSinks`, valida los argumentos contra el
  `SyncActionSpec` del handler, acota la ejecución con su timeout y aplica
  autoridad fail-closed: `Principal.Official` siempre recibe `Denied` y
  `Principal.Program` recibe `Denied` sin una concesión que cubra la capacidad.
  Un handler ausente o desaparecido devuelve `Unsupported`; un duplicado conserva
  el primero y se registra.
- `textformatter` publica `ChannelSetHandler` (`textformatter.channel.set`,
  `WRITE`, argumento `channel` de tipo `CHANNEL_REF`). Valida que el canal exista
  y que el emisor pueda enviar y el receptor recibir; devuelve
  `Ok(Message)` con el canal cambiado, `NotFound`, `Denied` o
  `InvalidArgument`. `Principal.Official` se rechaza también en el handler.
- `RouteStage` detecta cualquier mutación de `Message.channel` comparando el
  mensaje de entrada con el de la primera pasada y ejecuta exactamente una
  segunda pasada de routing; una segunda mutación o un segundo
  `CHANNEL_REDIRECT` producen `DECISION_CONFLICT`. La ruta legacy
  `CHANNEL_REDIRECT` se conserva para `iflow-firewall`.
- La ABI v3 de `iflow-wasm` retira el import dedicado `set_channel`,
  `PolicyHost.setChannel` y `PolicySession.setChannel`; el guest debe usar
  `sync_invoke("textformatter", "channel.set", ...)` cuando el puente esté
  disponible. Un artefacto que importe `iflow.set_channel` ya no valida.
- Tests: `ChannelSetHandlerTest` (contrato, `Ok`, `NotFound`, `Denied`,
  `InvalidArgument`, `Official`), `DefaultSyncBusTest` (`Unsupported` sin
  handler, `Denied` para oficial y programa, desaparición del handler y acción
  desconocida), `RouteStageTest` (mutación única, conflicto de segunda mutación,
  redirección legacy) y `WasmPolicyEngineTest` (rechazo del import retirado).

#### Estado de implementación — puente iFlow (2026-11-13)

Completado en esta unidad:

- `TlvCodec` se movió de `iflow-wasm` a `core-api` (`api.iflow`) y se amplió con `LIST`/`MAP`,
  `encodeList`/`listValue`, `encodeMap`/`mapValue`, `decodeSequence` y `decodePrefix`. La VM y
  Wasm comparten el mismo codec; ningún motor mantiene una copia.
- `SyncBridgeCodec` (`core-api`, `api.sync`) fija el cable: petición de `sync_surface` (string de
  backend), respuesta (lista de superficies), petición de `sync_invoke` (mapa `backend`/`action`/
  `args`) y respuesta (mapa `status` + `result` o `reason`). Los estados canónicos son `OK`,
  `DENIED`, `UNSUPPORTED`, `INVALID_ARGUMENT`, `NOT_FOUND`, `CONFLICT`, `RATE_LIMITED`, `TIMEOUT` y
  `HOST_FAILURE`; `SyncBridgeCodecTest` cubre cada uno y el mapeo neutral de valores.
- VM: `HostServiceRegistry` registra `SYNC_SURFACE` (`0x0800`) y `SYNC_INVOKE` (`0x0801`) con las
  capacidades `sync.surface`/`sync.invoke`, lectura por DMA, respuesta sobre el buffer de petición y
  mapeo a `ServiceResult.Status` (incluidos `TIMEOUT` y `RATE_LIMITED`). `HostContext` declara
  `syncSurfaces()` e `invokeHostAction()` con defaults explícitos (`Optional.empty()` y
  `Unsupported`).
- `VmPolicyEngine` resuelve el `SyncBus` con un `Supplier`, construye `Principal.Program` desde el
  SHA-256 de `ProgramImage.hash()`, despacha por namespace (sink → `invoke`, resto →
  `invokeHost`) y aplica la mutación de canal al journal. Una segunda mutación de canal devuelve
  `Conflict`; el programa recibe `Ok(null)`.
- Wasm: `PolicyAbiV3` añade `sync_surface`/`sync_invoke` y los estados `UNSUPPORTED`,
  `RATE_LIMITED` y `TIMEOUT`; `PolicyHost` traduce los buffers del guest; `PolicySession` aplica el
  mensaje devuelto y detecta la segunda mutación. `WasmPolicyRouter` conserva el SHA-256 verificado
  de `policy.wasm` y lo usa como principal.
- Tests: `HostServiceRegistryTest` (superficies, filtro por backend, resultado, todos los estados,
  petición malformada y capacidades), `VmPolicyEngineTest` (mutación aplicada, conflicto de segunda
  mutación y `Unsupported` sin bus), `WasmPolicyEngineTest` (mutación aplicada, `Unsupported` sin
  bus, conflicto y mapeo de estados) y `PassThroughProgramTest` (la política oficial no declara
  `sync.invoke` ni `sync.surface`).

Pendiente y fuera de esta unidad:

- La suite de conformidad cruzada que ejecuta el mismo artefacto en VM y Wasm (W3.5).

#### Estado de implementación — concesiones, cuotas y auditoría (2026-11-13)

Completado en esta unidad:

- `core-api`: `ProgramGrant` (validación de SHA-256, capacidades exactas o comodín final,
  `allow-destructive` y cuotas), `ProgramGrantStore`, `ActionAudit` y `ActionAuditRecord`;
  `SyncParamSpec` gana `secret` y `SyncBridgeCodec` lo transporta en el cable.
- `sync-bus`: `DefaultSyncBus` deriva la capacidad del origen (`sync.<backend>.<accion>` o
  `module.<moduleId>.<accion>`), resuelve la concesión y la auditoría con suppliers por invocación,
  exige `allow-destructive` para `DESTRUCTIVE`/`MODERATION`, mide el payload TLV, aplica la ventana
  de llamadas por minuto y audita cada intento. Sin store, sin concesión, sin auditoría escribible o
  con payload excedido devuelve `Denied`; la cuota agotada devuelve `RateLimited`.
  `SyncBusActivator` resuelve ambos servicios por tipo.
- `suite-runtime`: `YamlProgramGrantStore` carga `config/iflow/grants.yml` con validación estricta
  (claves desconocidas, hashes duplicados, comodines inválidos o `config-version` incorrecta
  deshabilitan el store completo) y recarga atómica; `AppendOnlyActionAudit` escribe
  `<data>/config/iflow/audit.log` con una línea por intento, escapado y `force` a disco.
  `SuiteRuntime` registra ambos servicios antes de activar paquetes y cierra la auditoría en
  `stop()`.
- `spigot-host`/`fabric-host`: default `defaults/iflow-grants.yml` con `grants: {}`;
  `DefaultConfigInstaller` lo instala como `config/iflow/grants.yml`.
- Tests: `ProgramGrantTest`, `YamlProgramGrantStoreTest`, `AppendOnlyActionAuditTest`,
  `DefaultSyncBusAuthorizationTest` (concesión presente/ausente, comodín, destructivo, cuota,
  payload, redacción de secretos y argumentos no declarados, auditoría ausente o no escribible y
  acciones de host) y `SyncBridgeCodecTest` (incluye `statusName(Ok)`).
- Documentación: ADR-036 §16.6.5/§16.6.6/§16.7.1/§16.7.4, `storage.md` §2/§4.1.1/§5 y este PLAN.

#### Estado de implementación — superficie nativa de `sync-discord` (2026-11-12, ampliada 2026-11-14)

Completado en esta unidad:

- `core-api` gana los tipos neutrales `DiscordOperation` y `DiscordResult`, y
  `DiscordConnection.execute(DiscordOperation)`. Las operaciones no llevan tipos
  de JDA ni de HTTP: ids, nombres, motivos y la ventana de borrado son datos.
- `sync-discord` publica en `DiscordSink.surface()` el LCM más seis acciones
  nativas: `channel.create` (`WRITE`), `channel.delete` (`DESTRUCTIVE`),
  `message.delete` (`DESTRUCTIVE`), `message.edit` (`WRITE`, idempotente),
  `member.ban` (`MODERATION`) y `role.assign` (`MODERATION`). `DiscordSink.invoke()`
  traduce los argumentos neutrales, exige un `Principal.Program`, rechaza
  `Principal.Official` con `Denied` y mapea cada `DiscordResult` a `SyncResult`
  (`Ok`, `NotFound`, `Conflict`, `RateLimited`, `HostFailure`); un adaptador
  ausente o desconectado devuelve `HostFailure`.
- `sync-discord-jda` ejecuta las seis operaciones con JDA (`createTextChannel`,
  `delete`, `deleteMessageById`, `editMessageById`, `ban`, `addRoleToMember`) y
  mapea `ErrorResponseException` a `NotFound`/`Forbidden`/`Failed`.
- `sync-discord-jdk` ejecuta las seis operaciones por REST (`POST`, `DELETE`,
  `PATCH`, `PUT`) con `Authorization`, `Content-Type` y `X-Audit-Log-Reason`;
  mapea `TransportException` (404/401/403/409/429/5xx) a resultados explícitos
  y lee `retry_after` del cuerpo 429. `message.edit` falla con `Failed` explícito
  si el `Transport` inyectado no implementa `PATCH`.
- `transport` añade `put`/`delete` (defaults que fallan explícitamente si no se
  implementan), `patch` (default que lanza `UnsupportedOperationException`) y
  `TransportException` con `statusCode`/`body`; `HttpTransport` implementa
  `PATCH` con `java.net.http.HttpClient` porque `HttpURLConnection` rechaza ese
  método. El `PATCH` conserva la validación SSRF de la URL inicial y de cada
  redirección, el límite de cinco redirecciones, HTTP/1.1, el timeout de la
  petición y el límite de respuesta de 1 MB; el pinning anti-TOCTOU de
  `HttpURLConnection` no aplica a este método y así se documenta.
- Tests: `DiscordSinkTest` cubre superficie, éxito, `Denied` oficial,
  `Unsupported`, argumentos inválidos, `NotFound`, `Conflict`, `RateLimited`,
  `HostFailure` y estado desconectado; `JdkDiscordConnectionTest` cubre las seis
  operaciones, la ausencia de `PATCH` y el mapeo de errores;
  `HttpTransportIntegrationTest` cubre `PATCH` (método, cabeceras, cuerpo,
  redirect 307 y 302, límite de redirects, error tipado, SSRF y default
  explícito) junto con `PUT`/`DELETE` y `TransportException`;
  `DiscordOperationTest` valida ids, rangos y contenido no vacío.

Pendiente y fuera de esta unidad:

- `channel.permission.overwrite`, consultas de miembros/roles, canales de voz y
  eventos de auditoría; se añadirán con sus propios slices.

#### Estado de implementación — superficie nativa de `sync-telegram` (2026-11-12)

Completado en esta unidad:

- `sync-telegram` publica en `TelegramSink.surface()` el LCM más seis acciones
  nativas: `chat.title.set` (`WRITE`), `chat.leave` (`DESTRUCTIVE`),
  `message.delete` (`DESTRUCTIVE`), `message.edit` (`WRITE`),
  `chat.member.ban` (`MODERATION`) y `chat.member.unban` (`MODERATION`).
  `TelegramSink.invoke()` exige `Principal.Program` para las nativas, rechaza
  `Principal.Official` con `Denied`, valida ids positivos y banderas booleanas y
  exige un `chat` explícito; el `chatId` configurado solo se usa para
  `message.send`.
- `TelegramApi` centraliza la Bot API sobre el `Transport` neutral: `POST` con
  cuerpo JSON, `GET` con query para `getUpdates`, parseo de `ok`/`error_code` y
  conversión de `parameters.retry_after` (segundos) a milisegundos. El token
  vive en `char[]`, se materializa solo en la URL de la llamada y se borra con
  `Arrays.fill('\0')` en `stop()`; el `String` con el token que antes retenía
  `baseUrl` desaparece.
- Mapeo explícito de errores: 400 con descripción `not found` → `NotFound`,
  otro 400 → `InvalidArgument`, 401/403 y 5xx → `HostFailure`, 404 → `NotFound`,
  409 → `Conflict`, 429 → `RateLimited`. Un fallo de red → `HostFailure`.
- `poll()` deja de devolver `0` cuando la Bot API responde `ok=false`: falla de
  forma explícita, y un sink detenido rechaza `send`, `poll` e `invoke` sin
  tocar la red.
- Tests: `TelegramSinkTest` cubre superficie, `sendMessage` por POST JSON,
  polling con offset, rechazo explícito del poll, `Denied` oficial,
  `Unsupported`, las seis nativas, argumentos inválidos, todo el mapeo de
  `error_code`, fallo de red y sink detenido.

Pendiente y fuera de esta unidad:

- `chat.member.restrict` (mute), `message.pin`/`unpin`, `chat.permission.set` y
  `chat.description.set`; se añadirán con sus propios slices.

#### Estado de implementación — superficie nativa de `sync-http` (2026-11-13)

Completado en esta unidad:

- `sync-http` publica en `HttpSink.surface()` el LCM más dos acciones nativas:
  `webhook.send` (`WRITE`, argumento `payload` de tipo `OBJECT`, no idempotente,
  15 s) y `endpoint.info` (`READ`, sin argumentos, idempotente, 5 s).
  `HttpSink.invoke()` exige `Principal.Program` para las nativas, rechaza
  `Principal.Official` con `Denied`, rechaza un sink detenido con `HostFailure`
  sin tocar la red y devuelve `Unsupported` para cualquier acción que no
  publique.
- `webhook.send` serializa el mapa neutral como cuerpo JSON y lo envía al
  webhook configurado; un `payload` ausente, de otro tipo o con claves vacías
  devuelve `InvalidArgument` sin red.
- Mapeo explícito de estados HTTP: 2xx → `Ok`, 400 → `InvalidArgument`,
  401/403 → `HostFailure`, 404 → `NotFound`, 409 → `Conflict`, 429 →
  `RateLimited` (`Retry-After` en segundos convertido a milisegundos), resto →
  `HostFailure`; `HttpTimeoutException` → `Timeout` y un fallo de E/S →
  `HostFailure`. `send(Message)` conserva el mensaje
  `webhook HTTP <status>` que verifica la integración con WireMock.
- `endpoint.info` describe el endpoint local y el destino sin secretos: no
  expone token, HMAC, path ni query del webhook; solo esquema, host y puerto.
- Se corrige el ciclo `stop()`/`start()`: `stop()` libera el `HttpServer`, el
  executor acotado y el `HttpClient`, y `start()` recrea el cliente y el
  executor cuando faltan, en lugar de reutilizar recursos cerrados tras un
  reinicio del bus. `URI.create` se resuelve una sola vez en el constructor.
- Tests: `HttpSinkTest` cubre superficie, `webhook.send` con payload neutral,
  `Denied` oficial, `Unsupported`, payload inválido sin red, el mapeo completo
  de estados (400/401/403/404/409/429/500/503), `Retry-After` en milisegundos,
  `endpoint.info` sin secretos, sink detenido y la regresión `stop()` →
  `start()`. `HttpSinkIntegrationTest` conserva el caso `webhook HTTP 500`.

Pendiente y fuera de esta unidad:

- Acciones nativas adicionales del endpoint (por ejemplo, cabeceras o métodos
  configurables); no se publican hasta que el adaptador pueda ejecutarlas.

#### Estado de implementación — superficie nativa de `sync-websocket` (2026-11-13)

Completado en esta unidad:

- `sync-websocket` publica en `WebSocketSyncSink.surface()` el LCM más dos
  acciones nativas: `path.broadcast` (`WRITE`, `path` y `payload` de tipo
  `OBJECT`, no idempotente, 15 s) y `client.info` (`READ`, `path` opcional,
  idempotente, 5 s). `invoke()` exige `Principal.Program` para las nativas,
  rechaza `Principal.Official` con `Denied`, rechaza un sink detenido con
  `HostFailure` sin tocar la red y devuelve `Unsupported` para cualquier acción
  que no publique.
- `path.broadcast` acepta `/ws/chat`, `/ws/events` y `/ws/sync`; `/ws/logs`
  queda reservado al canal de diagnóstico. El payload neutral se envuelve en
  `{"type":"broadcast","path":...,"payload":{...}}`, se limita a 64 KiB y se
  entrega a los suscriptores abiertos; el resultado informa `path` y
  `delivered`. Claves no textuales, valores no finitos, tipos no soportados,
  path ausente o fuera de la lista y payload ausente o no-object devuelven
  `InvalidArgument` sin enviar nada.
- `client.info` informa nombre, estado, puerto enlazado, clientes totales y
  suscriptores por endpoint (o de un endpoint concreto); nunca expone
  direcciones de clientes ni el token.
- Se corrige el ciclo `stop()`/`start()`: `org.java_websocket` no permite
  arrancar dos veces el mismo `WebSocketServer`, así que cada `start()` crea
  una instancia nueva y `stop()` libera la anterior. `start()` espera a que el
  selector esté listo y falla explícitamente si falta el token, si el bind
  falla o si el arranque agota el timeout; el puerto `0` enlaza un puerto
  efímero para tests.
- `send(Message)` falla con `IOException` si el sink está detenido y
  `broadcastToPath` devuelve cuántos suscriptores abiertos recibieron el
  mensaje, sin contar intentos fallidos.
- Tests: `WebSocketSyncSinkTest` cubre superficie, `path.broadcast` con un
  cliente real, `send` enrutado a `/ws/chat`, `client.info` sin secretos,
  `Denied` oficial, `Unsupported`, argumentos inválidos sin red, sink detenido,
  token ausente y la regresión `stop()` → `start()`.

Pendiente y fuera de esta unidad:

- Acciones nativas adicionales (por ejemplo, cierre de una conexión concreta o
  suscripción remota); no se publican hasta que el adaptador pueda ejecutarlas.

#### Estado de implementación — superficie nativa de `sync-socket` (2026-11-13)

Completado en esta unidad:

- `TcpSink` publica en `surface()` el LCM más `endpoint.info` (`READ`, sin
  argumentos, idempotente, 5 s) y `connection.probe` (`READ`, sin argumentos,
  idempotente, 5 s). `UdpSink` publica el LCM más `endpoint.info` (`READ`, sin
  argumentos, idempotente, 5 s). Ambos `invoke()` exigen `Principal.Program`,
  rechazan `Principal.Official` con `Denied`, rechazan un sink detenido con
  `HostFailure` y devuelven `Unsupported` para cualquier acción no publicada.
- `endpoint.info` informa host y puerto remotos, puerto de escucha enlazado y
  estado del listener; no expone credenciales. `connection.probe` abre y cierra
  una conexión TCP al listener remoto con timeout de 4 s, no envía mensaje y
  devuelve `Ok` con `reachable` y latencia, o `HostFailure` si el remoto no
  responde.
- `send(Message)` falla con `IOException` si el sink está detenido; en UDP ya no
  se desreferencia un socket cerrado. Los bucles de aceptación y recepción
  capturan el socket que poseen y salen cuando se cierra, de modo que un
  `stop()` → `start()` no deja un bucle obsoleto leyendo del socket nuevo.
- Tests: `TcpSinkTest` cubre superficie, `endpoint.info` sin secretos, sonda
  alcanzable y no alcanzable, `Denied` oficial, `Unsupported`, sink detenido con
  `send` explícito y reinicio del listener; `UdpSinkTest` cubre lo mismo salvo
  la sonda.

Pendiente y fuera de esta unidad:

- Acciones nativas adicionales (por ejemplo, reapertura forzada del listener o
  métricas de datagramas); no se publican hasta que el adaptador pueda
  ejecutarlas.

#### Estado de implementación — superficie nativa de `sync-velocity-core` (2026-11-13)

Completado en esta unidad:

- `VelocitySink` publica en `surface()` el LCM más tres acciones nativas:
  `proxy.info` (`READ`, sin argumentos, idempotente, 5 s), `mapping.resolve`
  (`READ`, `channel` requerido y `type` opcional, idempotente, 5 s) y
  `queue.purge` (`DESTRUCTIVE`, sin argumentos, idempotente, 5 s). `invoke()`
  exige `Principal.Program`, rechaza `Principal.Official` con `Denied`, rechaza
  un sink detenido, deshabilitado o en cierre con `HostFailure` y devuelve
  `Unsupported` para cualquier acción no publicada.
- `proxy.info` informa canal, subcanal, transporte, servidores disponibles,
  tamaño de la cola y salud con sus métricas; nunca expone el secreto
  compartido. `mapping.resolve` aplica las mismas reglas que `send` (exacta,
  `regex:`, `type:` y `*`, con lista estática o descubrimiento dinámico),
  devuelve los servidores destino y usa `CUSTOM` como tipo por defecto; un
  `channel` ausente o vacío devuelve `InvalidArgument`. `queue.purge` vacía la
  cola de reintentos y devuelve cuántas entradas eliminó.
- `send(Message)` sobre un sink deshabilitado lanza `IOException` en vez de
  descartar el mensaje en silencio, y un sink no arrancado conserva su fallo
  explícito.
- Tests: `VelocitySinkTest` cubre superficie, `proxy.info` sin secreto,
  `mapping.resolve` con regla comodín y regla por tipo, `queue.purge`, `Denied`
  oficial, `Unsupported`, argumentos inválidos, sink detenido, sink
  deshabilitado y la validación de configuración existente.

Pendiente y fuera de esta unidad:

- Acciones nativas adicionales (por ejemplo, reconexión del adaptador o
  inspección de la cola); no se publican hasta que el adaptador pueda
  ejecutarlas.

#### Estado de implementación — superficie nativa de `sync-bukkit`/`sync-fabric` (2026-11-13, ampliada 2026-11-14)

Completado en esta unidad:

- `LocalSyncSink` (`sync-common`), que publican `sync-bukkit` y `sync-fabric` con
  los namespaces `bukkit` y `fabric`, publica en `surface()` el LCM más
  `local.info` (`READ`, sin argumentos, idempotente, 5 s).
- `local.info` devuelve `name`, `surfaceVersion`, `localDeliveryRegistered`
  (si el host ya registró el puerto `LocalDelivery`), `deliverySemantics` y
  `ordering`; nunca expone el puerto ni un sobre. Funciona antes de `start()`
  porque el sink local no abre conexión.
- `invoke()` rechaza `Principal.Official` con `Denied` (solo puede invocar el
  LCM), devuelve `Unsupported` para una acción no publicada o una llamada
  dirigida a otro backend, y conserva `message.send` como `Unsupported` porque
  el camino local exige `message.deliver`.
- Tests: `LocalSyncSinkTest` cubre la superficie con `local.info`, la acción con
  y sin puerto registrado, `Denied` oficial, `Unsupported`, backend ajeno,
  `message.send` rechazado y `message.deliver` ejecutado por la superficie.

#### Superficie de entorno y eventos de §17.5 (2026-11-14)

Completado en esta unidad:

- `sync-common` gana el puerto neutral `LocalEnvironment` con `LocalPlayer`,
  `LocalEvent` y `LocalActionException` (`NOT_FOUND`, `UNSUPPORTED`,
  `INVALID_ARGUMENT`, `HOST_FAILURE`). Ninguna clase de plataforma cruza el
  puerto.
- `LocalSyncSink` publica y ejecuta ocho acciones de entorno además de
  `local.info`: `player.list` y `player.info` (`READ`), `player.message`
  (`WRITE`), `player.kick` (`DESTRUCTIVE`), `console.command` (`DESTRUCTIVE`),
  `scoreboard.set` (`WRITE`), `event.info` (`READ`) y `event.cancel` (`WRITE`).
  Todas exigen `Principal.Program`; `Principal.Official` recibe `Denied` sin
  efecto. Cada fallo del puerto conserva su familia (`NotFound`, `Unsupported`,
  `InvalidArgument`, `HostFailure`), y un evento ausente devuelve `NotFound`.
- `sync-bukkit` implementa el puerto con `BukkitLocalEnvironment`: jugadores en
  línea, mensaje privado, expulsión, comando de consola, scoreboard principal y
  el evento de chat ligado por hilo alrededor del dispatch. Las operaciones se
  ejecutan en el hilo principal y se esperan (`callSyncMethod`), de modo que no
  se reporta éxito antes de que Bukkit ejecute la acción. `event.cancel` solo
  cancela eventos `Cancellable`; un evento no cancelable devuelve `Unsupported`.
- `sync-fabric` implementa el puerto con `FabricLocalEnvironment`: jugadores en
  línea, mensaje privado, expulsión, comando de consola, scoreboard del servidor
  y el evento de chat ligado por hilo. Las operaciones se ejecutan en el hilo
  del servidor y se esperan (`submit().get()`), con la misma garantía de
  resultado. La callback de chat de Fabric no es un objeto cancelable una vez
  despachada, así que `event.cancel` devuelve `Unsupported` explícito en lugar
  de simular la cancelación.
- Tests: `LocalSyncSinkTest` cubre la superficie completa, la autoridad,
  argumentos inválidos, el mapeo de las cuatro familias y la ejecución de cada
  mutación; `BukkitLocalEnvironmentTest` cubre jugadores, mensaje, expulsión,
  consola, scoreboard, cancelación y anidamiento del binding;
  `BukkitChatListenerTest` comprueba que el evento de chat se liga solo durante
  el dispatch; `FabricLocalEventTest` cubre el contrato neutral del evento.

Pendiente y fuera de esta unidad:

- Acciones de canal local y consultas de scoreboard (listar objetivos y
  puntuaciones); se añadirán con sus propios slices. `canales` se cubre por el
  LCM `message.send`/`message.deliver` y no se duplica como acción nativa.

#### Criterios de cierre y tests

1. **LCM:** con cualquier subconjunto de backends, `backend.info` y `message.send` funcionan sin
   acciones nativas.
2. **Oficial acotado:** `Principal.Official` no puede invocar `textformatter.channel.set` ni una
   acción nativa; recibe `Denied` sin efecto.
3. **Tercero con concesión:** un programa de prueba con `sync.discord.channel.create` crea un canal
   en un backend falso y deja una entrada de auditoría con hash, sin valores sensibles.
4. **Sin concesión:** la misma llamada devuelve `Denied` y el backend no recibe la acción.
5. **Destructivo:** `discord.member.ban` sin `allow-destructive` devuelve `Denied`; con la marca,
   ejecuta y audita.
6. **Cuotas:** timeout y límite por minuto devuelven `Timeout`/`RateLimited`; no hay éxito parcial.
7. **`textformatter.channel.set`:** dispara la segunda fase de routing; la segunda mutación devuelve
   `DECISION_CONFLICT`; el import dedicado ya no existe; la capacidad es
   `module.textformatter.channel.set`.
8. **Acción desconocida:** `Unsupported` explícito; el bus no finge éxito.
9. **Paridad:** la misma llamada produce el mismo `SyncResult` en VM y Wasm.
10. **Sink local:** `sync-bukkit` y `sync-fabric` publican superficie y entregan a jugadores/consola;
    su acción de evento actúa sobre el evento actual y no sustituye a `CANCEL_EVENT`.
11. **Gate:** `./gradlew build checkArchitecture checkLocks --no-daemon`.

#### Dependencias y orden

- **ADR-036 aceptado** y, en particular, §16.
- **TF-006 cerrado el 2026-11-07**; TF-005 cerrado el 2026-11-06.
- **PL-1** para la etapa `sync`, el contexto del pipeline y la segunda fase de routing.
- **W3.5** para el puente Wasm; la VM y Wasm comparten conformidad.
- Los backends pueden avanzar en paralelo después de los contratos y el bus.

#### Contradicciones detectadas

| Documento | Contradicción | Resolución |
|---|---|---|
| ADR-003 | La autoridad del guest se limita a contenido/metadatos. | ADR-036 §16 añade concesiones por hash, marca destructiva, auditoría y fail-closed; las firmas siguen siendo opcionales. |
| ADR-031 | `SET_CHANNEL` es la única mutación de canal y no se expone a terceros. | No cambia D01–D05; `textformatter.channel.set` reutiliza D02 y retira el import dedicado. La exposición cambia, la semántica no. |
| ADR-019 | Permite `feature → feature` con dependencia declarada. | ADR-036 §2 elimina la excepción; §15 migra los descriptores. |
| `storage.md` §1 | Un submódulo declara `require(...)` del padre (`sync-*` → `sync-bus`). | Resuelto el 2026-11-13: la regla 2 se alineó con §15; el submódulo publica su puerto y el padre lo descubre, y el requisito duro queda solo para dependencias estructurales (`manager-*`). |
| Especificación iFlow v3 | El guest solo transforma contenido/metadatos; no hay efectos de host. | §16 amplía la autoridad a acciones de backend con concesión; requiere autorización para la spec. |
| Auditoría TF-006 | `broadcastAsync` no era fiable. | Resuelto el 2026-11-07: cada futuro se ata a su mensaje y tiene timeout explícito. `broadcastAsync` se retiró al abrir PL-2; `invoke`/`send` conservan ambas garantías. |
| ADR-035 | `CoreTranslator.sendDiscord` usa el bus actual. | Pasa al LCM `message.send`; no usa acciones nativas. Sin contradicción. |
| ADR-034 | Prohíbe aliases y compatibilidad. | El cambio es incompatible y se aplica en una sola unidad; `set_channel` se retira, no se conserva como alias. |

#### Bloqueos

- Ninguno por TF-006 (cerrado el 2026-11-07); TF-005 cerrado el 2026-11-06.
- Autorización para `storage.md`, el esquema de configuración y la especificación iFlow, solo si sus
  rutas o contratos cambian; el namespace `textformatter` y la ruta de canales no cambian.
- Paridad W3.5 para el puente Wasm; el puente VM puede adelantarse con conformidad propia.

#### Deuda de nomenclatura (baja prioridad)

`SyncSink` es el SPI del endpoint; `SyncBus` es el contrato del bus; `sync-bus` es el módulo
que lo implementa; `sync` es la etapa terminal y `sync.discord` un target. La interfaz del
endpoint podría llamarse `SyncBackend` o `SyncEndpoint` para alinear el vocabulario con ADR-036 §16
(«backend»), pero el cambio no aporta comportamiento y exige tocar código, tests y documentación.
No se renombra ahora: primero hay que investigar el propósito histórico y vigente de cada nombre y
planificar el cambio. Se registra como deuda de baja prioridad, no como requisito de PL-2.

---

### Feature propuesta — sincronización de salidas por mensaje (PL-3, 2026-11-06)

> **Estado:** implementado (2026-11-09): contrato, resolución, etapas selectivas, orden/paralelismo,
> resultado agregado, sinks locales, wiki y esquema. Depende de **ADR-036 §17** (aceptado el
> 2026-11-06).

#### Objetivo

Permitir que un mensaje declare, en orden, qué backend usa cada salida. Todos los targets comparten el
namespace `sync`: `sync.iflow-16`, `sync.gtranslate`, `sync.discord`. Sin `outputs`, se aplica
`config/suite/config.yml`; sin esa clave, el default del módulo. `sync-bus` deja de ser solo un
fan-out y publica la etapa terminal `sync`, sin un módulo nuevo. La entrega local (`bukkit`, `fabric`)
es una salida más de `sync`, no un camino paralelo al pipeline.

#### Estado actual y evidencia

| Hecho | Evidencia | Efecto |
|---|---|---|
| `IflowConfig` elige un motor global | `IflowConfig.java` (`engine`, `fallback-on-unavailable`) | No hay selección por mensaje. |
| `ChatTranslator` elige un backend global + contexto | `ChatTranslator.java` (`defaultBackend`, `contextOverrideEnabled`) | El contexto es una clave/`ThreadLocal`, no un campo del mensaje. |
| `SyncBus.broadcast` envía a todos los sinks | `DefaultSyncBus.broadcast` | No hay selección por mensaje ni orden. |
| `SyncBus.sendTo` se usa cuando hay targets | `SyncStage`; `DefaultSyncBus` | La etapa `sync` ya selecciona por `sync.<backend>`; `CoreTranslatorPlaceholders` sigue siendo ad hoc. |
| `Message.outputPlan()` y resolución de precedencia | `Message`, `OutputPlanResolver`, `SuiteRuntime` | Mensaje > suite > default del módulo; `outputs-parallel` resuelto. |
| `outputs`/`outputs-parallel` en `config/suite/config.yml` | `HostConfig`, `ConfigLoader` | Configuración validada; ausente hereda el default. |
| `PipelineStage.ownsOutput(backend)` | `PipelineStage`, `RouteStage`, `FormatStage`, `SyncStage` | Cada etapa declara qué backends consume; `PipelineEngine` rechaza un backend sin dueño o ambiguo antes de `MESSAGE_PRE`. |
| `sync-bukkit`/`sync-fabric` publican un `SyncSink` local | `SyncBukkitActivator`/`SyncFabricActivator` registran `LocalSyncSink` (`bukkit`/`fabric`) y declaran `sync-bus@>=1.0.0` | Son sinks por diseño y ya son seleccionables por `outputs`. |
| La entrega local pasa por el sobre | `LocalSyncSink` delega en `LocalDelivery`; `DeliveryExecutor` lo implementa y `SuiteRuntime` lo publica | Un único camino: el motor recoge `DeliveryUnit` y el sink local entrega el sobre. |

#### Impacto previsto

| Área | Cambio |
|---|---|
| `core-api` | **Implementado (2026-11-09):** `OutputPlan`, `ParallelMode`, `Message.outputPlan()`, `PipelineContext.outputs()`; todos los targets son `List<String>` con formato `sync.<backend>` y `PipelineStage.ownsOutput`. Sin tipos de plataforma. |
| `suite-runtime` | **Implementado (2026-11-09):** precedencia mensaje → suite → módulo, validación y traza del origen (`message`, `suite`, `module-default`) como entrada sintética `outputs`. |
| `iflow` | **Implementado (2026-11-09):** `route` posee los backends iFlow (`sync.iflow-16`, `sync.iflow-wasm`, `sync.iflow-firewall`) y los resuelve por el hub; conserva default/fallback de ADR-032; dos targets propios son conflicto y un backend no disponible es error explícito. |
| `textformatter` | **Implementado (2026-11-09):** `format` posee los backends de traducción (`sync.gtranslate`, `sync.ltranslate`) y los usa para elegir el proveedor de `<tr>`; conserva default/fallback de la fachada. |
| `sync-bus` | **Implementado (2026-11-09):** `sync` posee los backends de sink y usa sus targets en orden; `sendTo(List, Message, ParallelMode)` y `deliverTo(List, DeliveryEnvelope, ParallelMode)` respetan `outputs-parallel`; fan-out completo si no hay targets. `deliver`/`deliverTo` despachan `message.deliver`; `deliver` deduplica y `deliverTo` no. |
| `sync-bukkit` / `sync-fabric` | **Implementado (2026-11-09; `local.info` el 2026-11-13; entorno/eventos de §17.5 el 2026-11-14):** publican `LocalSyncSink` como `bukkit`/`fabric` con la superficie LCM, `local.info` y las acciones de entorno (`player.*`, `console.command`, `scoreboard.set`, `event.*`) sobre el puerto neutral `LocalEnvironment`; la entrega local delega en `LocalDelivery` y devuelve `Ok(DeliverySummary)`. Un evento no cancelable falla con `Unsupported`. |
| Configuración | **Implementado (2026-11-09):** `outputs`, `outputs-parallel` en `config/suite/config.yml`; `config-version: 1.0`; contrato del editor actualizado en `config-schema-v2.2.md`. |
| Tests | **Implementado (2026-11-09):** precedencia, vacío vs ausente, target inválido, namespace `sync` obligatorio, `outputs-parallel`, propiedad de backends por etapa, backend desconocido y ambiguo, origen del plan, orden/paralelismo de `sync`, resultado agregado por target, dedup/no-dedup de entrega y sink local con y sin puerto. |

#### Pasos de implementación

1. **Contrato neutral. ✅ (2026-11-09)** `OutputPlan`/`ParallelMode` en `core-api` y
   `Message.outputPlan()` con `List<String>` de targets `sync.<backend>`. Inmutable, sin
   `ThreadLocal`; `declared=false` ≠ `targets=[]`.
2. **Resolución. ✅ (2026-11-09)** Precedencia mensaje → `config/suite/config.yml` → módulo, con
   validación del namespace `sync` y error explícito. El plan efectivo se resuelve una vez por
   emisión en `PipelineEngine` y se expone en `PipelineContext.outputs()`.
3. **Etapas. ✅ (2026-11-09)** Cada etapa declara `ownsOutput(backend)`: `route` posee los backends
   iFlow, `format` los de traducción y `sync` los de sink. `route` resuelve `sync.iflow-16` por el
   hub y la traducción de `<tr>` dentro de `format` resuelve `sync.gtranslate` por la fachada. Sin
   target propio, cada etapa conserva su default y fallback. Dos targets propios de una etapa de
   transformación son `CONFLICT`; un backend desconocido o ambiguo lo rechaza el motor.
4. **Validación central. ✅ (2026-11-09)** `PipelineEngine` comprueba cada target declarado contra la
   unión de backends de las etapas resueltas antes de `MESSAGE_PRE`. Un backend sin dueño o con más
   de un dueño devuelve error con traza `ERROR` y ninguna etapa se ejecuta.
5. **`sync` selectivo. ✅ (2026-11-09)** `sync-bus` expone `sendTo` por `SyncSurface.backend` y
   conserva el fan-out completo cuando no hay targets. `bukkit` y `fabric` publican `LocalSyncSink`;
   la entrega física local pasa por `LocalDelivery`. No se crea `sync-mux`.
6. **Orden y paralelismo. ✅ (2026-11-09)** `sync` inicia los targets en el orden declarado.
   `SyncBus.sendTo(List, Message, ParallelMode)` encola todos los targets y espera en paralelo por
   defecto; `SEQUENTIAL` espera cada target antes de iniciar el siguiente. La traza conserva el
   orden de la lista.
7. **Resultado agregado. ✅ (2026-11-09)** `OutputOutcome` (target + `SyncResult`) viaja en
   `StageResult`, `PipelineOutcome` y `DispatchOutcome`; un fallo parcial queda visible sin éxito
   fingido ni rollback. La traza registra el origen del plan como entrada sintética `outputs`.
8. **Guardas.** `checkArchitecture`: `core-api` sin SDK; `stage:*`/`sync:*` declarativos, nunca
   `Requirement`; `textformatter` no es un backend de entrega; la entrega local es el sink
   `bukkit`/`fabric`, no un camino paralelo al pipeline.
9. **Documentación y esquema. ✅ (2026-11-09)** `config-schema-v2.2.md` documenta `outputs` y
   `outputs-parallel`; la wiki describe la selección de salidas y la entrega local.

#### Estado de implementación (2026-11-09)

Completado en esta unidad:

- `Message` gana `outputPlan()` con `OutputPlan.inherit()` por defecto, `withOutputPlan(...)`,
  copia en `toBuilder()`/`from(...)` y exposición en `toJson()`.
- `OutputPlan` exige el namespace `sync`; `sync.<backend>` es el único formato válido y
  `OutputPlan.backend(target)` extrae el backend.
- `HostConfig` incorpora el plan de suite; `ConfigLoader` lee `outputs` y `outputs-parallel`,
  valida cada target y reporta como error un valor no booleano o un target sin `sync.<backend>`.
- `OutputPlanResolver` aplica la precedencia mensaje → suite → default del módulo y resuelve
  `ParallelMode` de forma independiente a los targets.
- `PipelineEngine` resuelve el plan una vez por emisión, valida la propiedad de cada backend contra
  las etapas resueltas y lo entrega a `PipelineContext.outputs()`; `SuiteRuntime` pasa la
  configuración cargada y `HostFixture` usa el mismo camino.
- `ConfigSchemaGenerator` expone `config.outputs` (array) y `config.outputs-parallel` (boolean) al
  editor web; el esquema generado se regeneró y `npm run check` pasa.
- Tests: `MessageOutputPlanTest`, `OutputPlanResolverTest`, `ConfigLoaderTest` y
  `PipelineEngineTest` cubren default, precedencia, vacío vs ausente, paralelismo, namespace
  inválido, backend desconocido y backend ambiguo.
- `route` posee los backends iFlow y resuelve `sync.iflow-16` con `IflowHub.router(backend)`; sin
  target propio usa el hub y su fail-closed actual. `FormatStage` posee los backends de traducción,
  resuelve `sync.gtranslate` y lo pasa al render de `<tr>`; `TranslationService.translate(...,
  backend)` conserva el fallback de la fachada y falla explícitamente ante un proveedor desconocido.
- Tests: `RouteStageTest`, `IflowHubTest`, `FormatStageTest`, `TemplateRendererTest` y
  `TranslationServiceBackendTest` cubren selección, conflicto, backend no disponible, propiedad por
  etapa y separación de caché por backend.
- `OutputPlanResolver.resolveDetailed(...)` expone el origen del plan; `PipelineEngine` lo registra
  como entrada sintética `outputs` (`message`, `suite`, `module-default`).
- `SyncBus.sendTo(List, Message, ParallelMode)` reutiliza las colas por sink: en paralelo encola
  todos los targets antes de esperar; en secuencial espera cada uno. `SyncStage` posee los backends
  de sink, conserva el orden, expone un `OutputOutcome` por target y mantiene el fan-out completo
  cuando el plan se hereda.
- `OutputOutcome` viaja en `StageResult`, `PipelineOutcome` y `DispatchOutcome`; los contadores de
  destinatarios no se mezclan con el fallo parcial de una salida.
- Tests: `OutputPlanResolverTest`, `PipelineEngineTest`, `SyncStageTest` y `DefaultSyncBusTest`
  cubren origen del plan, orden, paralelo, secuencial, propiedad de sink y fallo parcial.
- `DeliveryUnit(recipient, decision, message, rendered)` y `DeliveryEnvelope(Message, units)` son el
  sobre neutral; `message.deliver` lo transporta con `DELIVERY_REF`. El motor recoge las unidades en
  `RECIPIENT`, las propaga a `MESSAGE_POST` y suma los contadores de `DeliverySummary`; ya no entrega
  directamente por el adaptador de plataforma.
- `LocalDelivery` es el puerto neutral que implementa `DeliveryExecutor`; `SuiteRuntime` lo registra
  en `moduleHost.services()` y lo libera en `closePipeline()`. `LocalSyncSink` lo resuelve en cada
  entrega y devuelve `Ok(DeliverySummary)`; sin puerto devuelve `HostFailure`.
- `sync-bukkit` y `sync-fabric` publican `LocalSyncSink` (`bukkit`/`fabric`), añaden la capacidad
  `sync-sink` y la dependencia de paquete `sync-bus@>=1.0.0`. `message.send` sobre un sink local es
  `Unsupported`: el camino local exige el sobre.
- `DefaultSyncBus.deliver(envelope)` deduplica por id de mensaje; `deliverTo(...)` no deduplica y
  conserva el orden solicitado. `message.deliver` es LCM, `Principal.Official` puede invocarlo y no
  se reintenta (`idempotent=false`).
- Tests: `DefaultSyncBusTest` cubre dedup de `deliver`, no-dedup de `deliverTo`, backend desconocido,
  argumento inválido y ausencia de reintento; `LocalSyncSinkTest` cubre puerto presente/ausente,
  fallo del puerto, `message.send` y semántica de entrega.

Pendiente y fuera de esta unidad:

- Ninguno. PL-3 queda cerrado; el E2E real y el betatesting siguen en ADR-034.

#### Criterios de cierre y tests

1. **Multi-salida:** `sync.discord` + `sync.tcp` + `sync.bukkit` entrega a los tres; la traza
   respeta el orden.
2. **Precedencia:** mensaje > suite > módulo; sin `outputs` en ningún nivel, fan-out completo.
3. **Vacío vs ausente:** `targets=[]` no entrega a ningún backend, incluida la entrega local;
   `declared=false` hereda.
4. **Transformación única:** dos targets propios de `route` (`sync.iflow-*`) o de `format`
   (`sync.gtranslate`, `sync.ltranslate`) → `Conflict`.
5. **Desconocido o ambiguo:** target con backend inexistente o reclamado por dos etapas → error
   explícito antes de `MESSAGE_PRE`, sin skip y sin ejecutar etapas.
6. **Namespace:** todo target debe empezar por `sync.`; otro namespace o un target sin punto es
   `InvalidArgument`.
7. **Paralelo:** un backend lento no bloquea a los demás; `outputs-parallel: false` serializa.
8. **Fallo parcial:** un target falla, los demás completan; el resultado agregado lo declara.
9. **Sin regresión:** sin `outputs`, el comportamiento actual se conserva.
10. **Local:** `sync.bukkit` y `sync.fabric` son targets de pleno derecho; incluirlos entrega a
    jugadores/consola, omitirlos no; `outputs=[]` tampoco entrega local. El evento solo se cancela con
    `DROP`/`REJECT`, no con la lista de salidas.
11. **Gate:** `./gradlew build checkArchitecture checkLocks --no-daemon`.

#### Dependencias y orden

- **ADR-036 aceptado**, §17 en particular.
- **PL-1** para el `PipelineContext`, la traza y las etapas.
- **PL-2** para `SyncSurface`/`invoke` y la selección por backend.
- **TF-006 cerrado** (2026-11-07) para el resultado fiable del fan-out asíncrono; TF-005 cerrado el 2026-11-06.
- **ADR-032/ADR-035** se conservan como default y fallback.

#### Contradicciones detectadas

| Documento | Contradicción | Resolución |
|---|---|---|
| ADR-032 | El hub elige un motor global (`IflowConfig.engine`). | §17 añade un override por mensaje; el default y el fallback se conservan. |
| ADR-035 | `ChatTranslator` elige un backend global + contexto. | §17 usa un campo del mensaje; la fachada y su default se conservan. |
| ADR-036 §17.2 (borrador previo) | Decía que la entrega local no era target de `sync`. | Corregido: `sync-bukkit`/`sync-fabric` son sinks; la entrega local es `sync.bukkit`/`sync.fabric`. |
| ADR-036 §3 | La configuración global solo define `pipeline`. | Se añaden `outputs` (lista de `sync.<backend>`) y `outputs-parallel`; `config-version: 1.0`. |
| ADR-019 | Prohíbe `feature → feature`. | La etapa `sync` la publica `sync-bus`; no se crea `sync-mux` ni se requiere otra feature. |
| ADR-034 | Prohíbe aliases. | `outputs` es una lista de cadenas; no hay claves alternativas ni alias en español. |
| `config-schema-v2.2.md` | No tenía `outputs`. | Actualizado: `outputs` y `outputs-parallel` documentados con sus reglas de validación. |

#### Bloqueos

- Ninguno para PL-3. El E2E real y el betatesting se ejecutan después de la base 1.0 (ADR-034).

### Feature propuesta — importar/exportar la suite completa desde el editor web (PL-4, 2026-11-06)

> **Estado:** completo (2026-11-12). El lector ZIP (paso 1), el archivo de suite (paso 2), el mapeo
> modelo ↔ suite (paso 3), la interfaz (paso 4), el CST de YAML (paso 5), la base de paquetes
> (paso 6), la ROM de iFlow (paso 7), el desensamblador (paso 8), la validación del YAML exportado
> (paso 9), la exportación DEFLATE (paso 10), los tests de integración (paso 11) y la documentación
> final (paso 12) están implementados y verificados.
> El ZIP completo no depende de ADR-036; el mapeo de canales usa
> `config/textformatter/channels/`, que es la ruta vigente y no se mueve. Cualquier cambio en
> `docs/prompts/` requiere autorización explícita.

#### Objetivo

Permitir que el editor web importe y exporte un **conjunto seleccionado de artefactos** de la suite,
no el data folder completo. La exportación siempre lleva `TextFormatter-Suite/` como directorio raíz.
La importación acepta ese directorio o los archivos en la raíz del ZIP, sin exigir una forma
concreta. El editor edita la configuración, la base de datos de paquetes y las ROM de iFlow; conserva
los archivos no editados y no transporta JARs, dependencias, caché, journal, backups ni temporales.

#### Estado al iniciar la feature (2026-11-06)

| Hecho | Evidencia | Efecto |
|---|---|---|
| El export actual es un proyecto plano, no la suite | `model.js` (`exportFiles`): `config.yml`, `channels/`, `rules.yml`, `sync/`, `translators/` | No reproduce `config/suite/`, `config/iflow/`, `config/sync-*/`... |
| El import actual solo lee rutas planas | `model.js` (`importFromFiles`) | Ignora la estructura real de la suite. |
| No hay lector ZIP | `zip.js` solo exporta `build`; `importExport.js` usa `Suite.zip.Reader` | La importación ZIP falla en runtime. |
| El escritor ZIP solo usa STORE | `zip.js` | Un ZIP comprimido con DEFLATE no se puede leer. |
| El editor no conoce el layout de `storage.md` | `model.js` usa rutas planas y `paths.json` no tiene un mapa de archivos | No hay correspondencia con `config/<módulo>/`. |
| El parser YAML descarta comentarios | `yaml.js:144` ignora `#` al tokenizar | Un archivo editado pierde sus comentarios al serializarse. |
| No hay desensamblador | `tools/iflow-compiler/src` solo contiene `assembler.ts`, `cli.ts`, `types.ts`, `index.ts` | La gestión de ROM no puede ofrecer desensamblado sin una herramienta nueva. |
| La base de datos de paquetes no es YAML | `LocalPackageDatabase` lee `manager/library/status` con `ManifestParser` | El editor necesita un parser/serializador propio y una advertencia de riesgo. |
| Las ROM tienen rutas canónicas concretas | `VmConfigLoader` usa `config/iflow-16/policy.flow`; `WasmPolicyRouter` usa `config/iflow-wasm/policy.wasm` + sidecar SHA-256 | El modo suite debe incluir esos archivos, no todo `config/iflow*`. |

#### Alcance

- **Modo suite (nuevo):** importa y exporta un conjunto seleccionado de artefactos:
  - `config/**` completo (`suite`, `textformatter`, `chattranslator`, `iflow`, `iflow-firewall`,
    `sync-*`, `inworld`, etc.).
  - `manager/library/status` (base de datos de paquetes) solo para listado y edición, con una
    advertencia visible de consistencia/estabilidad. No se importan los JARs ni las dependencias.
  - ROM de iFlow: `config/iflow-16/policy.flow` y `config/iflow-wasm/policy.wasm` con su sidecar
    SHA-256.
- **Modo proyecto (actual):** se conserva para el modelo visual plano; no se elimina ni se
  sustituye por el modo suite.
- **Excluido del modo suite:** `manager/library/packages/`, `manager/library/dependencies/`,
  `manager/library/cache/`, `journal/`, `backup/`, `trash/`, `tmp/`, `manager/runtime/`, logs,
  temporales y cualquier `*.jar`. No se importa «todo el data folder».
- **Archivos desconocidos o binarios:** se preservan byte a byte y se reexportan en su ruta.
- **Basura de sistema:** se ignoran `__MACOSX/`, `.DS_Store` y `Thumbs.db` al importar; no se
  inventan archivos al exportar.
- **Tamaño:** límites anti-bomba generosos y configurables solo en código: número de entradas
  (archivos y directorios), bytes descomprimidos totales, bytes por archivo y ratio de compresión
  (descomprimido/comprimido). Deben permitir muchas configuraciones, ROM y paquetes, y solo frenar
  archivos maliciosos; no son un límite de producto. El ratio **no** es el nivel de compresión: la
  exportación usa el nivel máximo de DEFLATE. Los valores provisionales se detallan en «Formato y
  normalización».

#### Gestión y desensamblado de ROM

- La ROM se puede borrar, añadir, editar y clonar desde el editor.
- El desensamblado se limita al programa de usuario. La exclusión completa de firmware, BIOS,
  bootloader, kernel y stdlib es un requisito de **v3**, no de v2.
- **Estado real:** no existe desensamblador en `tools/iflow-compiler` (solo assembler). El formato
  `.flow` v2 tiene firmware de 4 KiB en `0x0000–0x0FFF` y código de usuario desde `0x1000`; la
  sección `debug` opcional incluye dirección, fuente y símbolos. El desensamblador debe construirse
  a partir de `Opcode`/`INSTRUCTION_SIZES`.
- **Límite del formato actual:** en v2 no hay secciones separadas de BIOS, bootloader, kernel ni
  stdlib; esa separación pertenece al contrato v3 (V1 congelado; implementación V2–V5). En v2 el
  desensamblado es una aproximación limitada: solo decodifica el rango de usuario (`>= 0x1000`) y
  no interpreta el firmware como programa. No se le atribuye la semántica de exclusión de v3.
- **Grafos:** la página construye su configuración como grafo, la compila a assembler y de ahí a la
  ROM. El proceso inverso usa **desensamblado**, no metadatos de grafo almacenados en la ROM: así se
  acepta cualquier ROM, incluso editada externamente. El desensamblado produce un CFG (flujo de
  control), no el grafo de reglas del editor; reconstruir automáticamente nodos
  `cond`/`transform`/`action` no es determinista, así que el CFG se muestra y el usuario lo traduce
  (con ayuda de la herramienta), sin prometer reconstrucción semántica automática. La ROM no declara
  el grafo.

#### Formato y normalización

- ZIP con métodos STORE (0) y DEFLATE (8); se rechazan cifrado, ZIP64 no soportado, métodos
  desconocidos y CRC inválido.
- La exportación usa DEFLATE con el **nivel máximo** de compresión. El ratio de compresión se mide
  para el límite anti-bomba; no se confunde con el nivel.
- Raíz de importación:
  - `TextFormatter-Suite/config/...` → se elimina el prefijo.
  - `config/...` → se usa tal cual.
  - Raíz mixta, `TextFormatter-Suite/TextFormatter-Suite/...` o cualquier otra raíz única → error
    explícito; no se adivina.
- Normalizar `\` a `/`, quitar `./`, rechazar `..`, rutas absolutas, unidad de disco, NUL, enlaces
  simbólicos y duplicados normalizados.
- Exportar siempre `TextFormatter-Suite/<ruta>`, con orden determinista y fecha fija.
- Un archivo no editado se reexporta con sus bytes originales. Un archivo editado conserva sus
  comentarios y formato mediante el CST de `js/yamlCst.js` (paso 5); si una edición no puede
  preservarlos, se bloquea con error explícito en lugar de perderlos en silencio. `yaml.js` sigue
  usándose solo para serializar archivos nuevos, donde no hay formato previo que conservar.
- Límites anti-bomba **provisionales y generosos**, revisables tras medir carpetas reales:
  100.000 entradas (archivos y directorios), 1 GiB descomprimido total, 256 MiB por archivo y ratio
  500:1. El ratio se evalúa solo en archivos de más de 1 MiB descomprimidos, para no rechazar YAML
  pequeños muy comprimibles. Se aplican al importar y al exportar; viven en código, no en YAML. Un
  archivo que supere un límite se rechaza con error explícito, sin truncar el ZIP.

#### Correspondencia con el modelo

| Editor | Suite |
|---|---|
| `config` | `config/suite/config.yml` |
| `channels/*` | `config/textformatter/channels/*` |
| `graph` | `config/iflow-firewall/rules.yml` |
| `translators/<id>` | `config/chattranslator/translators/<id>.yml` |
| `sync.discord` | `config/sync-discord/config.yml` |
| `sync.telegram` | `config/sync-telegram/config.yml` |
| `sync.http` | `config/sync-http/config.yml` |
| `sync.websocket` | `config/sync-websocket/config.yml` |
| `sync.tcp` | `config/sync-socket/tcp.yml` |
| `sync.udp` | `config/sync-socket/udp.yml` |
| `sync.velocity` | `config/sync-velocity-core/config.yml` |
| `sync.bukkit` | `config/sync-bukkit/config.yml` |
| `sync.fabric` | `config/sync-fabric/config.yml` |
| `packages` | `manager/library/status` (listado/edición con advertencia) |
| `rom.iflow-16` | `config/iflow-16/policy.flow` |
| `rom.iflow-wasm` | `config/iflow-wasm/policy.wasm` + `policy.sha256` |

`state.extra` conserva `config/suite/messages.yml`, `config/suite/commands.yml`, el
`config/chattranslator/config.yml` completo y cualquier módulo no mapeado. El mapeo no crea rutas
legacy ni lectores alternativos (ADR-034).

> **Deuda del editor, resuelta en el slice 3:** el modelo, `paths.json`, `config.js`, la validación
> y la UI ya usan `sync.tcp`/`sync.udp` con los campos del runtime (`remoteHost`, `remotePort`,
> `localPort`) y las rutas `config/sync-socket/tcp.yml` y `udp.yml`. La clave combinada
> `sync.tcp-udp` se retiró; no se añadió ningún alias de runtime (ADR-034). En el mismo cambio se
> alinearon Telegram (`chatId`), HTTP (`outboundUrl`, `inboundPort`, `inboundPath`) y Discord con
> los esquemas reales de cada sink.

#### Pasos de implementación

1. **Lector ZIP. ✅ (2026-11-12)** Implementar `Suite.zip.Reader` con directorio central, STORE/DEFLATE, CRC,
   descriptor de datos, límites y errores explícitos. Tests de regresión que fallen sin él.
2. **Archivo de suite. ✅ (2026-11-12)** Añadir `suiteArchive.js`: normalización, detección de raíz, mapa
   `ruta → {bytes, texto?}`, exclusión de basura y exportación con prefijo fijo.
3. **Mapeo. ✅ (2026-11-12)** Hidratar el modelo visual desde las rutas conocidas y volcar los cambios al mapa;
   conservar el resto como opacos. Un archivo no tocado no se re-serializa.
4. **Interfaz. ✅ (2026-11-12)** Separar “Importar/Exportar proyecto” de “Importar/Exportar suite”;
   mostrar resumen (archivos, módulos, avisos) y confirmar el reemplazo del estado.
5. **CST de YAML. ✅ (2026-11-12)** `js/yamlCst.js` parsea mapas y secuencias de bloque, colecciones
   de flujo en una línea, escalares planos y entre comillas, comentarios y líneas en blanco, con
   spans y trivia. `apply(text, baseline, current, options)` aplica solo el delta y conserva
   comentarios, orden, comillas, sangría y saltos de línea (LF/CRLF, BOM); `create` serializa
   archivos nuevos y `parseValue` hidrata el baseline. `toSuite` proyecta el modelo con `project()`
   para no añadir defaults ausentes en el archivo, usa `parseValue(texts[source])` como baseline y
   precalcula `writes`/`deletes` antes de mutar el ZIP: una edición no representable lanza
   `SuiteMappingError` con la ruta y deja el archivo de suite intacto. Se rechazan explícitamente
   block scalars, anclas, alias, tags, claves complejas, documentos múltiples, flujo multilínea,
   comentarios dentro de flujo, tabs en la sangría, CR aislado y saltos mixtos; esos archivos
   quedan opacos y se preservan. Tests: `tests/unit/yamlCst.test.cjs` (47 casos, incluido el
   round-trip exacto de los 35 YAML reales de `src/`, con `plugin.yml` como única diferencia por
   recibir `config-version`) y `tests/unit/suiteMapping.test.cjs` (101 casos con regresiones de
   comentarios y atomicidad).
6. **Base de datos de paquetes. ✅ (2026-11-12)** `js/packageDb.js` lee y escribe
   `manager/library/status` con el formato de bloques de `ManifestParser` (no YAML): campos
   `Clave: valor` y una sección `Files:` con líneas `ruta<TAB>tamaño<TAB>sha256<TAB>dueño`,
   separadas por una línea en blanco. El parser es estricto (rechaza comentarios, texto suelto,
   campos e ids duplicados, tamaños y sha256 inválidos y NUL) para no perder lo que el manager
   ignoraría; el escritor reproduce el formato canónico, conserva los campos desconocidos y la
   lista de archivos y ordena por `name`/`version`/`channel`/`type`. `suiteMapping` hidrata
   `state.packages` (`null` si el archivo no existe, array si existe o se crea), solo reescribe si
   cambió y deja opaco un archivo inválido sin pisarlo. La vista `packages` lista, añade, edita y
   elimina con advertencia permanente y valida el candidato antes de mutar. Tests:
   `tests/unit/packageDb.test.cjs` (64 casos), `tests/unit/suiteMapping.test.cjs` (121 casos con
   paquetes y preservación byte a byte) y `tests/integration/packages.cjs` (vista).
7. **ROM de iFlow. ✅ (2026-11-12)** Añadir gestión de `config/iflow-16/policy.flow` y
   `config/iflow-wasm/policy.wasm` con su sidecar SHA-256: listar, añadir, borrar, editar y clonar.
   El clon copia bytes y sidecar; al editar un `.wasm` se recalcula el hash y se reescribe el
   sidecar. Se validan cabecera, versión, longitudes y hash antes de aceptar el archivo.
8. **Desensamblador. ✅ (2026-11-12)** `tools/iflow-compiler/src/disassembler.ts` parsea el `.flow`
   v2 con las comprobaciones de `ProgramImageParser` y decodifica el rango de usuario (`>= 0x1000`,
   o desde `0x0000` sin firmware) a instrucciones con bytes, mnemónico, operandos y destino, más una
   CFG de bloques básicos. Los saltos al firmware se reportan como salidas externas. `iflow-dis`
   (`src/disasm-cli.ts`) ofrece listado o JSON con `--user-start` y `--no-verify`, y falla
   explícitamente ante hash inválido, cabecera truncada, opcode desconocido o salto fuera del código.
   La vista ROM del editor (`js/disasm.js`) reutiliza el mismo algoritmo en modo de solo lectura; un
   test contrasta su tabla de opcodes con `types.ts` y `Opcode.java`. El proceso inverso sigue siendo
   desensamblado, no metadatos de grafo en la ROM.
9. **Validación. ✅ (2026-11-12)** `Suite.suiteMapping.validateSuite(archive, mapping)` parsea cada
   `.yml`/`.yaml` de `config/` con el CST, exige raíz mapa y `config-version: 1.0`, y devuelve
   `{errors, warnings, modules}`. `exportSuite` valida el ZIP resultante antes de descargarlo: si hay
   errores muestra un modal con la lista y **no** genera el archivo. Un módulo desconocido y un
   archivo opaco preservado son avisos, no errores. La ROM y la base de paquetes se omiten porque
   tienen su propio formato.
10. **Exportación. ✅ (2026-11-12)** El ZIP usa raíz `TextFormatter-Suite/`, rutas ordenadas y no
    añade el `manifest.json` del editor en modo suite. `suiteArchive` aplica los límites anti-bomba al
    exportar y `Suite.zip.build` comprime cada entrada con DEFLATE (nivel máximo) y solo usa STORE
    cuando comprimir no reduce el tamaño. El lector aplica los mismos límites al importar.
11. **Tests. ✅ (2026-11-12)** `tests/integration/suite.cjs` cubre raíz presente/ausente, ZIP mixto,
    zip-slip, duplicados, STORE/DEFLATE, binarios, round-trip byte a byte, comentarios preservados,
    ROM válida/inválida, base de paquetes corrupta, YAML inválido, `config-version` ausente y límites
    de tamaño.
12. **Documentación y gate. ✅ (2026-11-12)** README del editor y wiki (`07-Web-Editor.md`)
    documentan el modo suite, sus límites y el gate; `cd src/web-editor && npm run check` pasa.

#### Estado de implementación (2026-11-12)

Completado en este slice:

- `js/zip.js` incorpora `Suite.zip.Reader`, `ZipError`, `DEFAULT_LIMITS` e `inflateRaw`.
- El lector parsea el directorio central, exige EOCD y rechaza multi-disco, ZIP64, cifrado,
  métodos distintos de STORE/DEFLATE, CRC inválido, tamaños incoherentes, entradas truncadas y
  nombres duplicados o con NUL.
- Soporta descriptor de datos (bit 3) leyendo tamaños y CRC del directorio central, nombres UTF-8
  y entradas de directorio.
- `inflateRaw` implementa DEFLATE (RFC 1951) en JavaScript puro y síncrono: bloques almacenados,
  fijos y dinámicos, con copia solapada LZ77. No añade dependencias ni requiere APIs asíncronas.
- Límites anti-bomba aplicados al leer: 100.000 entradas, 1 GiB total, 256 MiB por archivo y ratio
  500:1 solo por encima de 1 MiB. Son configurables en código y fallan con error explícito.
- `tests/unit/zip.test.cjs` cubre STORE, DEFLATE, descriptor, CRC, truncado, ZIP64, cifrado, método
  desconocido, duplicados, límites, ratio, UTF-8, directorios, iteración e `inflateRaw`.
- `npm run check` pasa; el test unitario se añadió a `npm test`.

Completado en el slice 2 (paso 2):

- `js/suiteArchive.js` publica `Suite.suiteArchive` con `SuiteArchive`, `SuiteArchiveError`,
  `normalizePath`, `detectRoot`, `isGarbage`, `isExcluded`, `isSuiteFile` y `fromEntries`/`fromReader`/`fromZip`.
- La normalización convierte `\` en `/`, elimina `./` y segmentos vacíos y rechaza `..`, rutas
  absolutas, unidades de disco y NUL. Los duplicados normalizados y los enlaces simbólicos se rechazan.
- La raíz se detecta una sola vez: `TextFormatter-Suite/` se elimina; los archivos en la raíz se usan
  tal cual; una raíz única distinta o una mezcla de raíces falla con error explícito. Un prefijo
  anidado `TextFormatter-Suite/TextFormatter-Suite/` también se rechaza.
- El mapa `ruta → Uint8Array` conserva los binarios y los archivos desconocidos bajo `config/`. Solo
  se aceptan `config/**` y `manager/library/status`; el resto se ignora y se contabiliza.
- Se ignoran `__MACOSX/`, `.DS_Store` y `Thumbs.db`; se excluyen `manager/library/{packages,dependencies,cache}/`,
  `journal/`, `backup/`, `trash/`, `tmp/`, `manager/runtime/`, `logs/` y los sufijos `.jar`, `.log`, `.tmp`.
- `toEntries()` exporta con prefijo fijo, orden determinista y sin `manifest.json`; `toZip()` usa
  `Suite.zip.build` (STORE por ahora; DEFLATE de nivel máximo es el paso 10). `fromZip()` usa
  `Suite.zip.Reader`.
- Los límites se aplican al importar y al mutar/exportar el mapa; viven en código y fallan con error
  explícito.
- `tests/unit/suiteArchive.test.cjs` cubre normalización, raíces, basura, exclusiones, binarios,
  duplicados, zip-slip, symlinks, raíz anidada, round-trip, `set/get/delete`, límites y `fromReader`.
- `npm run check` pasa; el test unitario se añadió a `npm test`.

Completado en el slice 3 (paso 3):

- `js/suiteMapping.js` publica `Suite.suiteMapping` con `SuiteMappingError`, `FIXED`, `SYNC_FILES`,
  `channelPath`, `translatorPath`, `syncPath`, `canonical`, `stripMeta`, `serializeYaml`,
  `fromSuite` y `toSuite`.
- `fromSuite(archive)` hidrata `config/suite/config.yml`, `config/textformatter/channels/*.yml`,
  `config/iflow-firewall/rules.yml`, `config/chattranslator/translators/*.yml` y los sinks
  `config/sync-*/config.yml` y `config/sync-socket/{tcp,udp}.yml`. Los archivos no mapeados que son
  texto UTF-8 se guardan en `state.extra`; los binarios quedan opacos.
- `rules.yml` solo se hidrata si la raíz es un mapa con `nodes`, `edges`, `guard` o `filter`. Un
  arreglo SpEL ejecutable se conserva sin hidratar y produce un aviso explícito.
- La raíz `config-version` se retira del modelo y se vuelve a añadir al serializar un archivo
  editado, al final y con el valor `1.0`. Un archivo no editado conserva sus bytes originales,
  incluidos comentarios y orden de claves.
- `toSuite(state, archive, mapping)` vuelca solo las secciones que cambiaron. Si un archivo estaba
  presente y no se editó, no se re-serializa. Si una sección no existía en la suite importada y el
  usuario no la tocó, no se inventa. Las secciones añadidas, renombradas o eliminadas se escriben,
  renombran o borran en su ruta canónica.
- Los archivos opacos (YAML inválido, binarios, SpEL) y los diferidos
  (`config/iflow-16/policy.flow`, `config/iflow-wasm/policy.wasm` y su sidecar) nunca se sobrescriben;
  la exportación lo reporta como aviso. `state.extra` tampoco puede pisar una ruta mapeada. La base
  de paquetes dejó de ser diferida en el paso 6.
- El mapeo no añade `manifest.json` ni transporta JARs, caché, journal, backups o temporales.
- Se alineó el modelo de sync con el runtime: `tcp`/`udp` separados con `remoteHost`, `remotePort` y
  `localPort`; Telegram con `chatId`; HTTP con `outboundUrl`, `inboundPort`, `inboundPath`, `bind`,
  `authToken` y `hmacSecret`; Discord con `format` y `transport`. Se retiró `sync.tcp-udp`, la UI
  combinada, `telegram.hub`, `discord.intents` y sus bindings muertos.
- Se corrigió el orden de mezcla de `importFromFiles` en traductores y sync: los valores importados
  ahora ganan a los defaults, con test de regresión.
- `tests/unit/suiteMapping.test.cjs` cubre rutas, `serializeYaml`, hidratación, archivos opacos,
  preservación byte a byte, edición, alta/renombrado/borrado de canales, `state.extra`, exportación
  nueva y round-trip ZIP con binarios.
- `npm run check` pasa; el test unitario se añadió a `npm test`.

Completado en el slice 4 (paso 4):

- `index.html` separa las acciones: `import project`/`project ZIP` conservan el ZIP plano y
  `import suite`/`suite ZIP` usan el archivo de suite. La importación de suite acepta un `.zip`
  (`#suiteInput`) y la exportación descarga `TextFormatter-Suite-<timestamp>.zip`.
- Antes de reemplazar el estado se muestra un resumen con archivos, módulos, archivos mapeados,
  opacos, diferidos, texto extra y avisos. La confirmación es explícita porque `StateStore.replace`
  borra el historial; cancelar no toca el estado.
- `Suite.suiteMapping.summarize(archive, mapping)` calcula el resumen como función pura; la lista de
  módulos sale de `config/<módulo>/` y agrupa `manager/`. Los avisos se listan con `textContent`
  (sin interpolar nombres de archivo en HTML) y se recortan a 20 con un contador de resto.
- La base importada se conserva en memoria (`archive` + `mapping`) y se clona en cada exportación con
  `SuiteArchive.clone()`. Así `toSuite` escribe solo lo editado sobre los bytes originales y un undo
  no reexporta bytes ya escritos.
- Importar un proyecto plano o pegar YAML descarta la base de la suite (`resetSuiteSource()`), de
  modo que la siguiente exportación de suite parte del modelo actual y no de un baseline obsoleto.
- Se añadieron claves de i18n (en/es) para el resumen, la confirmación y los avisos; `FileReader` se
  declaró en `eslint.config.js` para no introducir avisos nuevos.
- `tests/unit/suiteMapping.test.cjs` cubre `summarize` con y sin mapping; `suiteArchive.test.cjs`
  cubre que `clone()` no muta el original ni comparte sus rutas nuevas.
- `npm run check` pasa con 0 errores y 38 avisos preexistentes (uno menos que el baseline por la
  declaración de `FileReader`).

Completado en el slice 6 (paso 6):

- `js/packageDb.js` publica `Suite.packageDb` con `PackageDbError`, `TYPES`, `KNOWN_FIELDS`, `parse`,
  `serialize`, `serializeEntry`, `canonical`, `create`, `validate`, `normalizeDependency`,
  `parseDependencies`, `parseNameList`, `parseFiles`, `formatFiles` e `idOf`.
- La base de paquetes **no es YAML**: usa el formato de bloques de `ManifestParser` (`manager-spi`).
  El parser exige `Name`, `Version`, `Channel` y `Type` (`PACKAGE`/`DEPENDENCY`/`METAPACKAGE`),
  valida `Size` y `SHA256`, lee la sección `Files:` con `ruta<TAB>tamaño<TAB>sha256<TAB>dueño` y
  conserva en `extra` los campos desconocidos. Rechaza comentarios, texto suelto, campos e ids
  duplicados, tamaños y sha256 inválidos, NUL y CR aislado, para no perder lo que el manager
  ignoraría. El escritor reproduce el formato canónico, separa entradas con una línea en blanco y
  ordena por `name`/`version`/`channel`/`type`.
- `suiteMapping` hidrata `manager/library/status` en `state.packages`: `null` si el archivo no
  existe, array si existe o el usuario lo crea. Solo lo reescribe si cambió respecto al baseline;
  `[]` se exporta como archivo vacío y `null` nunca borra el archivo. Una base inválida queda opaca,
  no se hidrata, produce un aviso y nunca se sobrescribe.
- `model.defaults()` incluye `packages: null`. La vista `packages` (`js/packages.js`) lista, añade,
  edita y elimina entradas con una advertencia permanente de que el archivo es la fuente de verdad
  del Manager; la edición valida el candidato con `Suite.packageDb.validate` antes de mutar y un
  tamaño inválido no cambia el estado.
- `index.html`, `sidebar.js`, `core.js`, `i18n.js` y `styles.css` incorporan la vista y sus estilos.
- Tests: `tests/unit/packageDb.test.cjs` (64 casos), `tests/unit/suiteMapping.test.cjs` (121 casos,
  con hidratación, edición, alta, borrado total, base inválida opaca y preservación byte a byte) y
  `tests/integration/packages.cjs` (12 casos de la vista).
- `npm run check` y `npm run test:integration` pasan con 0 errores.

Completado en el slice 7 (paso 7):

- `js/rom.js` publica `Suite.rom` con `RomError`, `sha256Hex`, `toBase64`, `fromBase64`,
  `validateFlow`, `validateWasm`, `entryFromBytes`, `sidecarHash`, `formatSidecar`,
  `sidecarPathFor`, `isRomPath`, `isSidecarPath`, `kindOfPath`, `canonicalPath`, `clonePath` y
  `basename`. Implementa SHA-256 y base64 síncronos en JavaScript puro, sin dependencias.
- `.flow` v2 se valida contra `ProgramImageParser`: magic `FLOW`, formato/ISA/ABI/firmware,
  longitudes de código/constantes/capacidades/debug, `entryPoint` dentro del código, capacidades
  terminadas en NUL y trailer SHA-256 de 32 bytes sin bytes extra. `.wasm` v1 se valida por magic,
  versión y longitudes de sección.
- `state.roms` es un array JSON-serializable de entradas
  `{id, path, kind, canonical, data (base64), valid, error, meta, sidecarPath, sidecarText, sha256}`,
  de modo que undo/redo y `StateStore.replace` funcionan sin conversiones.
- Un `.flow` bajo `config/iflow-16/` y un `.wasm` bajo `config/iflow-wasm/` se hidratan como ROM.
  Un `.wasm` importado exige un sidecar con hash coincidente; si falta o no coincide, queda opaco,
  produce aviso y nunca se sobrescribe. Una ROM inválida también queda opaca.
- `putRoms()` valida cada entrada, exige sidecar coincidente para `.wasm`, escribe el sidecar con
  `formatSidecar(hash, basename)` y borra del ZIP las ROM y sidecars del baseline que ya no están.
  `toSuite` precalcula escrituras y borrados: un hash o formato inválido lanza `SuiteMappingError`
  y deja el ZIP intacto.
- La vista `roms` (`js/roms.js`) lista, añade, reemplaza, clona y borra ROM con una advertencia
  permanente: el runtime solo carga las rutas canónicas `policy.flow` y `policy.wasm`, así que un
  clon se exporta pero no se ejecuta. El clon duplica bytes y sidecar en una ruta nueva y sigue
  siendo editable (bytes y ruta). Al añadir o reemplazar un `.wasm` se recalcula el SHA-256 y se
  reescribe el sidecar.
- `model.defaults()` incluye `roms: []`; `index.html`, `sidebar.js`, `core.js`, `i18n.js` y
  `styles.css` incorporan la vista. `state.extra` no puede pisar una ruta de ROM mapeada.
- Tests: `tests/unit/rom.test.cjs` (66 casos), `tests/unit/suiteMapping.test.cjs` (156 casos, con
  hidratación, edición, clon, borrado, sidecar ausente/erróneo y preservación byte a byte) y
  `tests/integration/roms.cjs` (22 casos de la vista).
- `npm run check` y `npm run test:integration` pasan con 0 errores.

Completado en el slice 8 (paso 8):

- `tools/iflow-compiler/src/disassembler.ts` publica `DisassemblerError`,
  `parseProgramImage`, `decodeProgramImage`, `disassemble` y `formatListing`, con los tipos
  `DisassembledInstruction`, `BasicBlock`, `DisassemblyDiagnostic` y `DisassemblyResult`.
- El parser reproduce las comprobaciones de `ProgramImageParser` (magic, formato/ISA/ABI/firmware,
  longitudes, capacidades NUL, debug opcional, SHA-256 final y ausencia de bytes extra). El
  desensamblador verifica el hash salvo con `--no-verify`.
- Decodifica toda la ISA v2: `LI`, `MOV`, memoria directa (`LDB`/`LDW`/`STB`/`STW`) e indirecta
  (`LDBI`/`LDWI`/`STBI`/`STWI`), `COPY`/`MOVE`, aritmética, lógica, desplazamientos, comparación,
  saltos relativos con signo, `CALL`/`RET`, `HOST`, `HALT` y `TRAP`. Un opcode desconocido se emite
  como `.byte`, produce diagnóstico y la decodificación continúa.
- El corte de usuario es `0x1000` cuando la imagen lleva firmware y `0x0000` en binarios
  `--no-firmware`; `--user-start` lo sobrescribe. El `entryPoint` de la cabecera es `fw_reset`
  cuando hay BIOS, así que el CFG arranca en el inicio del rango de usuario.
- La CFG usa líderes por entrada, destinos de salto y la instrucción siguiente a una transferencia;
  los saltos al firmware son `exit`, no error. Un salto fuera del código o a un límite no
  decodificado produce diagnóstico.
- `src/disasm-cli.ts` publica el bin `iflow-dis` (listado o `--json`). `src/index.ts` exporta la API
  programática y `test/disasm/run.js` cubre los cinco fixtures, cobertura de bytes y bloques,
  destinos de `CALL`, bare-metal, listado, errores y determinismo (79 casos); `npm test` ejecuta
  validation + determinism + disasm.
- La vista ROM del editor carga `js/disasm.js`: un `.flow` válido ofrece «desensamblar» y muestra
  el listado y la CFG en un `pre` de solo lectura; un `.wasm` no lo ofrece. `tests/unit/disasm.test.cjs`
  (79 casos) contrasta la tabla de opcodes y tamaños con `types.ts` y `Opcode.java`, y
  `tests/integration/roms.cjs` cubre el botón, el listado y el ocultado.
- `npm run check` y `npm run test:integration` pasan con 0 errores.

Completado en el slice 9 (paso 9):

- `js/suiteMapping.js` publica `validateSuite(archive, mapping)`: recorre cada `.yml`/`.yaml` bajo
  `config/`, lo decodifica como UTF-8 estricto, lo parsea con `Suite.yamlCst.parseValue` y exige
  raíz mapa y `config-version: 1.0` (acepta el número `1` que produce el CST para `1.0` y la cadena
  `'1.0'`). Devuelve `{errors, warnings, modules}` ordenados.
- Los errores bloquean: `falta config-version`, `config-version no es 1.0`, `la raíz no es un mapa`,
  `YAML inválido: …` y `no es texto UTF-8`. Los avisos no bloquean: `módulo desconocido` (uno por
  directorio de primer nivel) y `archivo opaco preservado; no se valida`.
- `KNOWN_MODULES` enumera los módulos que el runtime reconoce bajo `config/`; un directorio fuera de
  la lista es un aviso porque puede ser una extensión válida que el editor aún no modela.
- Las ROM (`config/iflow-16/`, `config/iflow-wasm/`) y `manager/library/status` no son YAML y quedan
  fuera del validador: se validan con `Suite.rom` y `Suite.packageDb` en sus propios slices.
- `js/importExport.js` valida el archivo que devuelve `toSuite` **antes** de descargar. Si hay
  errores muestra `showSuiteErrors` en el modal existente (`#suiteModal`, con `#suiteModalApply`
  oculto y el botón de cierre) y no genera el ZIP; si solo hay avisos, exporta y los reporta.
- `showSuiteSummary` restaura el texto y la visibilidad de los botones del modal para que un resumen
  posterior a un error no herede el estado del error.
- `tests/unit/suiteMapping.test.cjs` añade 21 casos de `validateSuite`: suite válida, versión
  ausente/errónea, YAML inválido, raíz no mapa, UTF-8 inválido, archivo opaco, módulo desconocido
  (un aviso por módulo), `config-version: "1.0"` entre comillas, ROM y paquetes omitidos y archivo
  ausente (177 casos en total).
- `npm run check` (0 errores, 38 avisos preexistentes, 177 tests) y `npm run test:integration`
  (func 28, interact 5, click/chain/undo/diffing/bind con `ERRORS (0)`, packages 12, roms 28) pasan.

Completado en el slice 10 (paso 10):

- `js/zip.js` publica `Suite.zip.deflateRaw(data)`: compresor DEFLATE (RFC 1951) síncrono y sin
  dependencias. Usa LZ77 con ventana de 32 KiB, hash de 3 bytes, búsqueda perezosa y cadenas de hasta
  4096 candidatos; el tamaño de la cadena baja en archivos grandes (1024 desde 1 MiB, 512 desde
  8 MiB y 128 desde 32 MiB) para acotar el coste en el navegador.
- Construye árboles de Huffman canónicos y limita la profundidad a 15 bits (7 para el alfabeto de
  longitudes) recortando pesos y reconstruyendo; un alfabeto con un solo símbolo recibe un dummy para
  que el árbol sea completo y aceptado por zlib/Info-ZIP.
- Elige el menor de bloque dinámico, bloque fijo y bloque almacenado. La cabecera dinámica usa
  RLE 16/17/18, `HCLEN` recortado y códigos canónicos; el bloque almacenado solo se considera hasta
  65535 bytes.
- `Suite.zip.build` comprime cada entrada y escribe el método 8 (DEFLATE) cuando reduce el tamaño;
  si no, usa STORE (0). Los tamaños comprimido y sin comprimir, el CRC y el método se escriben en la
  cabecera local y en el directorio central. La fecha fija y el compresor determinista mantienen la
  exportación reproducible.
- La exportación de suite conserva la raíz `TextFormatter-Suite/`, el orden de rutas y la ausencia de
  `manifest.json`; los límites anti-bomba ya se aplican al exportar en `suiteArchive.toEntries` y al
  importar en el lector.
- `tests/unit/zip.test.cjs` (54 casos) verifica `deflateRaw` contra `inflateRaw` y contra
  `zlib.inflateRawSync` para vacío, 1–2 bytes, texto repetitivo, YAML largo, bytes pseudoaleatorios y
  rampa binaria; comprueba la ratio frente a zlib nivel 9, el determinismo, que `build` use DEFLATE en
  texto compresible y STORE en binario incompresible, y el round-trip por el `Reader`. Un ZIP de
  prueba pasa `unzip -t` de Info-ZIP.
- `npm run check` (0 errores, 38 avisos preexistentes) y `npm run test:integration` pasan.

Completado en el slice 11 (paso 11):

- `tests/integration/suite.cjs` (47 casos) recorre el flujo completo sobre jsdom, sin llamar a la red
  ni al sistema de archivos: carga los scripts del editor, importa por `#suiteInput`, confirma por
  `#suiteModalApply` y exporta por `#dlSuiteBtn` capturando el `Blob` antes de que `download` lo
  convierta en enlace.
- Cubre la importación con raíz `TextFormatter-Suite/` y sin raíz, el resumen con módulos, archivos
  opacos y avisos, y la hidratación de `config`, canales, paquetes, ROM válida e inválida y
  `state.extra`.
- La exportación conserva la raíz fija, los comentarios de cabecera y en línea del YAML editado, y
  los bytes del binario desconocido, la ROM válida e inválida, la base de paquetes sin editar y el
  texto no mapeado; el ZIP resultante mezcla DEFLATE y STORE. Los archivos fuera de `config/`, los
  JAR y `__MACOSX/` no se exportan.
- Un `config-version` ausente y un YAML inválido bloquean la exportación: el modal muestra la lista,
  oculta `#suiteModalApply` y no se genera ningún ZIP. Un módulo desconocido sigue siendo un aviso.
- Una base de paquetes corrupta se reporta al importar, no hidrata (`state.packages === null`) y se
  reexporta byte a byte. Un zip-slip y un duplicado se rechazan con aviso y sin abrir el resumen, y
  los límites del archivo se aplican al leer.
- El test se añadió a `test:integration`; `npm run check` (0 errores, 38 avisos) y el gate de
  integración completo (incluido suite: 47 casos) pasan.

Completado en el slice 12 (paso 12):

- `src/web-editor/README.md` describe el modo suite completo: importación con raíz o sin ella,
  resumen y confirmación, exportación con prefijo fijo, conservación de bytes y comentarios,
  validación bloqueante, base de paquetes, ROM y límites anti-bomba; la lista de integración incluye
  `suite`.
- `docs/wiki/07-Web-Editor.md` añade «Import/Export Suite»: qué artefactos viajan (`config/**` y
  `manager/library/status`), qué se excluye (JARs, dependencias, caché, journal, backups, temporales
  y logs), cómo se confirma el reemplazo y cómo se comportan el CST, la validación, la base de
  paquetes y las defensas del ZIP.
- Gate final: `npm run check` (0 errores, 38 avisos preexistentes) y `npm run test:integration`
  (func 28, interact 5, click/chain/undo/diffing/bind con `ERRORS (0)`, packages 12, roms 28, suite
  47) pasan.

Pendiente y fuera de esta unidad:

- Ninguno. PL-4 queda cerrado; el betatesting/E2E real sigue en ADR-034.

#### Criterios de cierre y tests

1. **Dos formas de raíz:** un ZIP con `TextFormatter-Suite/` y otro sin ella producen el mismo
   archivo normalizado.
2. **Exportación:** todo entry empieza por `TextFormatter-Suite/`; el ZIP se puede descomprimir en
   el directorio padre del plugin/mod y reconstruye el conjunto seleccionado de artefactos, no el
   data folder completo.
3. **Round-trip:** importar → exportar sin editar produce los mismos archivos; los no editados
   conservan sus bytes.
4. **Desconocidos:** un `config/<módulo>/config.yml` no mapeado se conserva y se reexporta.
5. **Binarios:** un archivo no UTF-8 se conserva sin corrupción.
6. **Seguridad:** `../`, rutas absolutas, enlaces simbólicos y duplicados se rechazan con error.
7. **Compresión:** STORE y DEFLATE funcionan; la exportación usa DEFLATE en nivel máximo; cifrado,
   ZIP64 no soportado y CRC inválido fallan.
8. **Anti-bomba:** se rechazan entradas, bytes totales, bytes por archivo o ratios por encima del
   límite generoso; el ratio solo se evalúa por encima de 1 MiB y no se confunde con el nivel de
   compresión.
9. **Comentarios:** editar un YAML conserva sus comentarios y su formato; una edición no
   representable falla sin escribir el archivo.
10. **Base de paquetes:** se lista y edita `manager/library/status`; un archivo corrupto se rechaza
    y la advertencia de consistencia está visible.
11. **ROM:** se pueden listar, añadir, borrar, editar y clonar ROM; un `.wasm` editado sin hash
    actualizado se rechaza; el desensamblado se limita al rango de usuario (`>= 0x1000`) y produce un
    CFG; la exclusión de firmware/BIOS/bootloader/kernel/stdlib es requisito de v3.
12. **Validación:** cada `.yml` de `config/` declara `config-version: 1.0`; un error bloquea la
    exportación y muestra la lista; un módulo desconocido y un archivo opaco son avisos.
13. **Sin regresión:** el modo proyecto actual sigue funcionando.
14. **Gate:** `cd src/web-editor && npm run check`.

#### Decisiones abiertas

- **Límites anti-bomba:** el baseline provisional es 100.000 entradas, 1 GiB total, 256 MiB por
  archivo y ratio 500:1 (solo por encima de 1 MiB); se recalibra con carpetas reales antes de
  congelarlo. Viven en código, no en YAML.
- **Secciones de exclusión:** la exclusión completa de firmware, BIOS, bootloader, kernel y stdlib
  pertenece a v3; en `.flow` v2 solo existe el corte de usuario (`>= 0x1000`). La separación real
  depende del contrato v3 (V1 congelado; implementación V2–V5) y se revisa entonces.
- **Grafos desde ROM:** resuelto; el proceso inverso usa desensamblado y CFG, no metadatos de grafo
  en la ROM. No queda una decisión pendiente sobre declarar el grafo dentro del artefacto.

#### Bloqueos

- Autorización para `storage.md` y `config-schema-v2.2.md` solo si el mapeo cambia alguna ruta; la
  ruta de canales se conserva en `config/textformatter/channels/` y el mapeo de ROM usa las rutas
  canónicas del runtime.

### Feature propuesta — configuración en vivo, telemetría y relay ZIP E2EE (PL-5, 2026-11-16)

> **Estado:** propuesta revisada (prioridad media, 2026-11-16). No implementada y no autoriza
> dependencias nuevas. Origen: `docs/prompts/diseno/web-editor-transmission.md`, que no se modifica.
> La decisión se registra como **ADR-039 en estado propuesto**; el documento de origen la numera
> «ADR-0027», número ya ocupado por ADR-027. Esta revisión sustituye la anotación inicial centrada
> solo en el ZIP: el canal WebSocket pasa a ser la vía primaria de configuración en vivo y el ZIP
> queda como fallback offline.

#### Objetivo

Permitir que el editor web configure y observe servidores TextFormatter en caliente, sin backend
autoalojado ni servicio de pago. La vía primaria transporta solo los cambios y consultas
necesarios; la exportación/importación ZIP de PL-4 se conserva como fallback offline y como
intercambio explícito de una suite completa.

#### Diseño propuesto

1. **Hosts externos, nunca hardcodeados.** La lista de endpoints vive en
   `config/web-editor/mirrors.yml`, relativa a la raíz de configuración, con `config-version: 1.0`.
   El código Java y JavaScript no contiene hosts, puertos ni orden de cascada incrustados. Se
   distribuye un archivo por defecto, se recarga al iniciar o al recargar configuración y admite
   añadir, desactivar, reordenar o sustituir hosts, incluido un servidor personalizado. Un archivo
   ausente o inválido falla de forma explícita: no se activa una lista de emergencia hardcodeada.
2. **Dos modos de canal.** `relay` usa brokers públicos MQTT sobre WebSocket con E2EE; `direct`
   usa WSS contra el plugin Velocity de TextFormatterSuite. El modo, el host elegido, la prioridad
   y la cascada salen de `mirrors.yml`. El navegador bajo HTTPS solo intenta WSS para evitar
   *mixed content*. El ZIP no es el protocolo primario del canal.
3. **Sincronización incremental.** El canal transporta operaciones por archivo o ruta con hash o
   versión base, no el ZIP completo. Solo se envían las partes relevantes: cambios de YAML, altas,
   bajas, renombrados y consultas. La aplicación es atómica, conserva comentarios y formato del CST
   y detecta conflictos de base. Un snapshot completo solo se usa para la primera carga o para una
   exportación/importación explícita.
4. **Acciones en caliente y telemetría.** El canal ofrece petición/respuesta y suscripciones para
   consultar el estado vivo de la máquina iFlow, el consumo de CPU virtual por proceso, la RAM
   virtual utilizada, los núcleos activos, el quantum/watchdog y el journal. También puede exponer
   acciones explícitas de configuración o control, siempre con capacidades, autorización y
   auditoría. Una métrica no soportada por el motor activo falla de forma explícita; no se inventa
   ni se rellena con un valor falso.
5. **Jerarquía multi servidor.** El editor muestra una lista seleccionable de servidores para
   entornos de networking como Velocity o Bungee. En modo `direct`, el plugin Velocity actúa como
   coordinador: conoce los servidores hijos, publica su topología y canaliza la configuración y la
   telemetría de cada uno hacia la página. El ZIP normal contiene un solo servidor, pero el modelo
   y el contenedor deben admitir una jerarquía multi servidor aunque la UI no la use al principio.
6. **Fallback explícito.** Si el canal no está disponible, se usa la exportación/importación ZIP
   local de PL-4. El fallback puede ser manual o automático, pero se reporta y nunca se presenta
   como sincronización en vivo correcta. No hay éxito silencioso.
7. **Interfaz.** El editor permite elegir servidor y endpoint, ver el estado de conexión y la
   telemetría, editar en caliente y usar «importar/exportar manualmente» como salida offline. La
   importación ZIP sigue validando raíz, módulos, ROM, paquetes y límites de PL-4.

#### Límites y decisiones abiertas

- **Esquema de `mirrors.yml`.** Faltan campos canónicos: `id`, `kind` (`relay`/`direct`), URL,
  TLS, prioridad, `enabled` y referencia de credenciales. No se guardan secretos en claro. Hay que
  decidir si la recarga es inmediata o solo al reconectar.
- **Relay frente a directo.** El relay público no tiene SLA; el endpoint directo del plugin
  Velocity necesita autenticación, autorización y protección contra exposición accidental. Puede
  haber varios endpoints y fallback entre ellos, pero nunca un host implícito.
- **Protocolo incremental.** Hay que definir el formato de operaciones, el hash base, la
  idempotencia, la aplicación atómica, los conflictos entre editores y la notificación de cambios.
  No se reutiliza el ZIP como mensaje de sincronización.
- **Multi servidor.** Faltan el identificador estable de servidor, la relación padre/hijo, el
  aislamiento de configuración y el formato del contenedor multi servidor. El ZIP de un servidor
  sigue siendo el caso normal; la jerarquía no puede degradarse a un lector doble.
- **Telemetría.** Hay que fijar identidad de proceso, unidades, frecuencia de muestreo, coste,
  retención y qué métricas expone cada línea (`iflow-16`, `iflow-32`, `iflow-wasm`). El estado de
  `iflow-32` incluye quantum/watchdog por core; `iflow-16` conserva su modelo de un vCPU y fuel.
- **Autorización.** Consultar y mutar no son el mismo permiso. Hay que separar lectura de
  telemetría, edición de configuración y acciones de control, con auditoría y fail-closed.
- **Cifrado y secreto.** En relay se mantiene AES-128-GCM, IV de 12 bytes y el código de 48 hex;
  topic y clave siguen siendo un secreto portador. En directo, TLS no sustituye la autenticación
  de la aplicación.
- **ZIP y relay.** Si el ZIP completo se enviara por relay, el techo de 16 bits de
  `CHUNK_INDEX`/`TOTAL_CHUNKS` limita a ≈ 256 MiB. El canal incremental no debe heredar ese
  límite; el ZIP offline no lo necesita. Cualquier transferencia ZIP por red debe rechazar
  tamaños mayores de forma explícita.
- **Cliente del navegador.** Hay que decidir entre una dependencia MQTT/WebSocket y un framing
  mínimo propio, con CSP y sin añadir dependencias sin ADR aceptado. No se reutiliza
  `sync-websocket` como broker público ni se acopla el canal al pipeline de entrega.
- **Operación.** Faltan timeout, reintentos, cancelación, limpieza de topic, backpressure y rate
  limiting. La disponibilidad de brokers públicos no está garantizada.

#### Fases (sin implementar)

1. `mirrors.yml`: esquema, carga, validación, recarga y selección de endpoint.
2. Canal `relay`/`direct`: TLS, autenticación, E2EE y reconexión.
3. Sincronización incremental: operaciones, hash base, aplicación atómica y conflictos.
4. Telemetría y acciones en caliente con capacidades y auditoría.
5. Topología multi servidor y coordinación desde el plugin Velocity.
6. UI de selección de servidor, edición en vivo y fallback ZIP; pruebas de fallo.

#### Bloqueos

- Requiere aceptar ADR-039 y resolver el esquema de `mirrors.yml`, el protocolo incremental, la
  autorización y la telemetría por línea.
- Prioridad media: no se implementa mientras V3-2/V4 y el gate de `iflow-32` sigan abiertos.

---

### Feature propuesta — caches, HOST sync, ROM persistente y editor de nodos (PL-6, 2026-11-20)

> **Estado:** documentación de revisión pendiente. **No hay ADR, contrato ni implementación.**
> Esta sección solo conserva la propuesta inicial para las tres etapas acordadas:
> (1) documentar y acotar, (2) debatir alcance, alternativas, riesgos y mejoras, y (3) escribir el
> ADR y el plan de implementación en `PLAN.md` cuando el diseño esté cerrado. Nada de esta sección
> autoriza código, dependencias ni cambios de formato.

#### iflow-16

1. **Presencia de sync por `HOST`.** Añadir una consulta `HOST` que responda si hay un backend de
   sincronización presente y qué superficies/capacidades ofrece. Debe ser una consulta explícita,
   no una inferencia por la existencia de un módulo ni un valor por defecto silencioso. Hay que
   decidir si se expone como servicio nuevo, capacidad del envelope o ambos, y cómo falla cuando no
   hay sync.
2. **Caché L2.** Caché de lecturas de disco para reducir accesos al almacenamiento del host. Hay que
   definir qué la respalda en `iflow-16`, que no tiene almacenamiento persistente en ROM, y cómo se
   invalida cuando el host o una operación `HOST` cambia el contenido.
3. **Caché L1.** Caché separada de instrucciones y de datos para reducir accesos a L2. Hay que
   fijar línea, asociatividad, política de escritura, coherencia con L2, interacción con MMU/TLB y
   comportamiento ante `SFENCE`/`FENCE` y auto-modificación de código.
4. **Sin almacenamiento en ROM.** `iflow-16` no gana persistencia en ROM en esta propuesta. La ROM
   sigue siendo la imagen de programa; cualquier caché L2 debe declarar su fuente y no puede
   presentarse como almacenamiento persistente.

#### iflow-32

1. **Presencia de sync por `HOST`.** Misma consulta explícita que en `iflow-16`, adaptada al ABI
   `HOST` v3 y a las capacidades de la máquina. No se hardcodea un backend ni se asume que el hub
   active sync.
2. **Almacenamiento persistente dentro de la ROM.** Propuesta inicial:
   - sin tabla de particiones por ahora;
   - formato **ext4**;
   - el tamaño de la partición persistente es **aparte** del tamaño de BIOS/firmware; no se
     descuenta del presupuesto de la ROM de arranque;
   - el sistema operativo vive dentro del sistema de archivos, incluidos el kernel ejecutable y el
     bootloader;
   - el primer sector es el bootloader, con estilo MBR pero **sin tabla de particiones**; la zona
     original de la tabla de particiones queda reservada para funciones futuras;
   - los ejecutables serán, por ahora, copias de su memoria RAM: concepto análogo a los `.COM` de
     MS-DOS, pero con un formato mejor definido y menos límites.
   - **FAT32 queda como pregunta abierta:** hay que verificar licencias, patentes y compatibilidad
     antes de afirmar que el proyecto puede implementarlo libremente. No se asume permiso legal ni
     se elige FAT32 por descarte.
3. **Caché L2.** Caché de lecturas de disco para el almacenamiento persistente. Debe definir
   coherencia con escrituras, `FENCE`, journal, montaje/desmontaje y apagado seguro.
4. **Caché L1.** Caché de instrucciones y de datos para reducir accesos a L2, con reglas de
   coherencia intra-VM compatibles con el modelo de memoria de ADR-037. Hay que decidir si la L1 es
   por core y cómo participa en el TLB shootdown y en la coherencia de datos compartidos.

#### Editor web

1. **Tamaño de ROM configurable al compilar.** El tamaño de la ROM deja de ser una constante fija
   del compilador y pasa a ser una opción validada. Hay que definir límites, unidad, alineación,
   impacto en la cabecera/TOC, tamaño de la partición persistente de `iflow-32` y errores de
   compilación cuando no cabe.
2. **Rediseño del editor de nodos.** La propuesta busca simplificar la construcción lógica y subir
   el nivel de abstracción del editor:
   - bloque de bucle único que cubre `for` sobre arrays, `while (i < N)`, `while (i > N)` y rangos;
   - bloque condicional con dos salidas explícitas, `true` y `false`;
   - la arista se interpreta como dirección lógica del programa; si no hay un bloque condicional
     interpuesto, la arista es un salto incondicional y no se necesita un bloque de salto aparte;
   - el editor debe validar ciclos, aristas huérfanas, tipos, alcance de variables y límites antes
     de compilar.
3. **Compatibilidad del formato de nodos.** Hay que decidir si el rediseño rompe el formato
   guardado actual, si hay exportación/importación explícita o si se declara incompatible. No se
   acepta un lector doble ni una migración silenciosa en runtime.

#### Etapas y criterios de cierre

| Etapa | Contenido | Salida | Estado |
|---|---|---|---|
| 1. Documentación de revisión | Esta sección: propuesta, límites, preguntas y dependencias | `PLAN.md` actualizado; ADR intacto | **En curso** |
| 2. Debate | Alcance real, alternativas, coste, riesgos, licencias, impacto en ISA/ABI, ROM, MMU, caches, journal y editor | Decisiones cerradas y preguntas abiertas reducidas | Pendiente |
| 3. Planificación | ADR aceptado y plan de implementación por fases en `PLAN.md` | ADR + fases + tests + gate | Pendiente |

**Bloqueos:** no se implementa mientras V3-2/V4 y el gate de `iflow-32` sigan abiertos. La
persistencia ext4/FAT32 requiere revisión legal y de formato; las caches requieren cerrar coherencia
y el modelo de memoria; el editor de nodos requiere cerrar el IR y el formato guardado.

---

## LÍNEAS — 2026-10-23, revisado 2026-11-16: `iflow-16`, `iflow-32` e `iflow-wasm`

### Decisión

**iFlow no se abandona ni se elimina.** Se mantienen líneas paralelas que comparten el contrato
de políticas del host, pero no el mismo runtime:

- **`iflow-16` — VM propia de 16 bits y 64 KB.** Es la línea actual (`src/iflow-16/`), de
  minimalismo extremo y soporte legacy: ISA/formato v2, ABI `HOST` v2, fuel, ROM de 16 bits y
  límites de memoria explícitos. Recibe bugs, seguridad y compatibilidad; su público son servidores
  con poca CPU/RAM y entornos embebidos. Conserva **un único vCPU por diseño**.
- **`iflow-32` — VM propia de 32 bits.** Es la línea nueva (`src/iflow-32/`), con memoria/MMU de
  32 bits, ISA `.flow` v3, BIOS/kernel/scheduler, IRQ, tiempo, persistencia, concurrencia y
  **multinúcleo intra-VM configurable**. Es la línea activa y no hereda el ABI `HOST` v2.
- **`iflow-wasm` — motor WebAssembly.** Es la línea basada en **Chicory**, con artefactos
  `.wasm` portables, instancias aisladas, memoria lineal propia y un ABI de imports acotado. No
  hereda el ISA `.flow`, el ABI `HOST` v2 ni el modelo BIOS/kernel.

Las líneas son independientes. Los hallazgos `IFLOW-MAN-*` se revisan para `iflow-16` e `iflow-32`
y **no se aplican automáticamente** a `iflow-wasm`. El host selecciona el motor por configuración;
no hay despacho dual dentro de una VM.

### Selección de motores iFlow mediante el hub `iflow` (2026-11-04, ADR-032)

> **Los cuatro motores — `iflow-16`, `iflow-32`, `iflow-wasm` e `iflow-firewall` — publican
> `IflowBackend`.** El paquete `iflow` es el hub: descubre los backends y activa **uno solo** para
> el routing. Pueden coexistir instalados; el hub es la fuente de verdad y aplica el fallback
> configurado, pero nunca cruza entre `iflow-16` e `iflow-32`.
>
> Configuración canónica: `config/iflow/config.yml` con
> `engine: iflow-firewall | iflow-16 | iflow-32 | iflow-wasm` y
> `fallback-on-unavailable: true|false`. El default es `iflow-firewall`. `iflow.engine.kind` ya no
> existe: la selección de motor vive solo en `config/iflow/config.yml` y el hub es el único punto
> de resolución.
>
> Los contratos neutrales (`Router`, `IflowHub`, `IflowBackend`) viven en `core-api` bajo
> `me.majhrs16.suite.iflow`; cada motor usa su propio subpaquete. `SuiteRuntime` exige
> `IflowHub` y no acepta un `Router` suelto. `iflow-wasm` ya publica su backend con hash y watcher.
>
> El monorepo compila los cuatro motores y el hub; `./gradlew publishIflowEngines` publica
> `suite-iflow`, `suite-iflow-16`, `suite-iflow-32`, `suite-iflow-wasm` y `suite-iflow-firewall`
> en `build/repo`.

El único pendiente transversal es la **paridad funcional de propiedades del mensaje** (W3.5) contra
la **spec v3**: que `iflow-wasm` implemente el mismo contrato de transformación, decisión y entrega,
no el ABI `HOST` v2 de `src/iflow-16`. No reactiva el backlog de `iflow-32`. Detalle en
«Paridad funcional pendiente».

### Qué se conserva

- El contrato de entrada `Router` / `RouteDecision` / `RouteOutcome` y el resto del pipeline
  (`MessageDispatcher`, `SuiteHost`, `DefaultTextFormatter`, canales, permisos, sync).
- La separación de responsabilidades del host: el guest decide la política; el host ejecuta la
  entrega, aplica permisos y expone servicios acotados.
- La configuración por módulo y el editor web como superficies de autoría.
- Las decisiones de seguridad que no dependen del runtime: sandbox, capabilities, límites duros de
  memoria/tiempo/tamaño, validación de artefactos y verificación de integridad.
- **`src/iflow-16/` como línea legacy de 16 bits**: código, tests, fixtures, compilador y ROM de
  16 bits se conservan. **No se borra.**
- **`src/iflow-32/` como línea activa de 32 bits**: recibe el núcleo v3 aislado y evoluciona con
  ADR-037.
- **WebAssembly + Chicory** como línea `iflow-wasm`, con módulo propio en `src/iflow-wasm/`.

### Qué no se hereda en `iflow-wasm`

- El ISA `.flow`, el ABI `HOST` v2, el modelo BIOS → bootloader → kernel → userspace, el mapa de
  64 KB, los frames de 4 KB y el fuel como mecanismo de límite del motor.
- Los hallazgos `IFLOW-MAN-*` como deuda del motor Wasm: pertenecen a `iflow-16` e `iflow-32`.
- La documentación que presenta iFlow como único motor; se actualiza a las líneas separadas.

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

### Trayectoria v2 → v3, retrocompatibilidad y fuel (2026-11-07, revisado 2026-11-16)

La **v2 de 16 bits y 64 KB** no se trata como código a borrar: es `iflow-16`, la línea de
**retrocompatibilidad** que debe quedar terminada, soportada y funcional. La **v3** es `iflow-32`,
la línea de escala. Son **dos módulos separados**; la convivencia se resuelve en el hub, que
selecciona un engine, no con un despacho dual dentro de un núcleo.

V2-0 implementó el contenedor ROM v3 aislado, V2-1 el decoder de la ISA v3, V2-2 la
ALU/registros/flags v3, V2-3 el núcleo de ejecución v3 (fetch/decode/execute, CSR, memoria y
traps, privilegios, `HOST` mínimo, ciclos y tiempo) y V2-4 la memoria física dispersa, la MMU, el
bus MMIO, el PIC, los dispositivos y el ABI `HOST` v3 completo, todos sin cablear al motor; no
existe un despacho dual v2/v3. La migración a v3 (ISA/ABI de 32 bits, scheduler, persistencia,
quantum) es posterior y no se mezcla con esos slices. Por eso el fuel sigue siendo el límite de
ejecución vigente en v2 y las referencias de ADR-036 §16.6/§16.7 al coste de fuel describen esa
línea, no el objetivo v3.

**Límites de `iflow-16` que justifican `iflow-32`.** Cargar datos del entorno Minecraft en la RAM
virtual al vuelo mediante `HOST` es posible, pero los 64 KB limitan la carga a unos pocos cientos
de nombres de jugadores. Lo mismo ocurre con la cola de mensajes para aplicar limitación de
velocidad o cargar varios mensajes: aunque solo se guarden IDs tipo puntero —que además habría que
mapear a UUID y al contenido del mensaje—, el techo teórico es de unos 22 000 mensajes; el
overhead del protocolo de la propia cola lo reduce a unos 750 mensajes. En una network grande
alcanzar 750 mensajes por segundo en toda la red es relativamente fácil, de modo que `iflow-16`
no puede ser la solución general de memoria/cola. Su público real son servidores con poca CPU o
RAM y entornos embebidos.

**Objetivo de `iflow-32`.** Pasar de 64 KB a un espacio de direcciones de hasta **4 GiB** (máximo
arquitectónico; la RAM física configurable de ADR-037 es 16 MiB–1 GiB y cualquier ampliación
física exige revisar ese límite), con `kernelspace` y `userspace` formales, y rediseñar la
arquitectura a partir de la auditoría manual: eliminar la quota de instrucciones, mejorar la
instrucción `HOST`, rediseñar el formato de transacción y el uso de registros, entre otros
cambios.

**Núcleos.** `iflow-32` no se diseña como una VM por core: es **una sola VM con `CORE_COUNT`
núcleos configurables** (1..64) que comparten RAM, ROM, MMIO, PIC, timer y kernel, con estado
arquitectónico por core, atómicas, IPI, coherencia de TLB y journal concurrente. El kernel guest
decide el scheduler; el host solo crea los cores y aplica quantum/watchdog por core. `iflow-16`
conserva **un único vCPU por diseño**: el techo estructural de 64 KB y la simplicidad de su kernel
no justifican multinúcleo. La implementación de `iflow-32` es **V3-2**; hasta que exista,
`CORE_COUNT > 1` falla explícito y no se degrada a single-core en silencio.

`IFLOW-MAN-D36R` (retirar el fuel como límite) queda **resuelto para `iflow-32` por ADR-037**
(2026-11-16): el ABI v3 no tiene fuel, gas ni presupuesto total de ejecución; el host impone un
quantum de preempción de 1M/50 ms por slice y un watchdog de paso, ambos no enmascarables. El
quantum devuelve `PREEMPTED` y conserva el estado y el journal; no termina el mensaje ni limita la
vida de la VM. `iflow-16` conserva su fuel v2 como contrato de la línea legacy. El puente
`SYNC_INVOKE` de `iflow-32` no cobra fuel; el de `iflow-16` sí, según ADR-038.

> **Resuelto por ADR-038 (2026-11-15).** ADR-037 queda acotado al motor `iflow-32`: su regla de
> rechazo de `.flow` v2 aplica a esa línea. `iflow-16` es un módulo separado que sigue aceptando
> `.flow` v2 y se rige por ADR-038. No hay alias `iflow-16`; el rename es breaking y se hace antes
> de V4.

**Auditoría, ROM y herencia.** La auditoría manual de la línea 16-bit identificó que `.flow` no es
«solo un programa»: es una imagen ROM con cabecera, TOC, segmentos y un modelo
BIOS/bootloader/kernel. El hallazgo se aplicó inicialmente al rediseño de 32 bits, pero aplica a
ambas líneas; cada una define su layout. La MMU de `iflow-32` puede adaptarse a `iflow-16` si
conserva el espacio de 16 bits, los 64 KB, la ABI v2 y el determinismo; no se asume compatible
hasta verificarlo.

**Código compartido y ciclo de vida.** Se permite compartir primero `core-api`, modelos neutrales,
puertos de host y utilidades que no impongan una ISA; si una línea necesita evolucionar de forma
incompatible, el componente se bifurca. No se comparte el núcleo, decoder, MMU, formato ROM ni ABI
`HOST`. `iflow-16` recibe bugs, seguridad y compatibilidad; `iflow-32` es la línea activa. La
paridad de features es una recomendación, no una obligación.

**Paquetes Java internos.** Ambas líneas conservan el prefijo
`me.majhrs16.suite.iflow.vm` mientras no exista colisión de clases; los paquetes no son contrato
público y cada línea puede renombrarlos cuando su implementación diverja. Las clases de entrada ya
son `Iflow16Module`/`Iflow16Activator` para la línea 16 y el hub usa los IDs `iflow-16` e
`iflow-32`.

#### Endianness única: little-endian en ambas líneas (pendiente, prioridad media/alta)

**Objetivo registrado (2026-11-16).** `iflow-16` e `iflow-32` deben usar **little-endian único**
en todo artefacto y en toda la máquina: cabecera, TOC, segmentos, instrucciones, inmediatos,
direcciones, constantes, memoria y MMIO. No se conserva endianness mixta ni conversión de orden en
el loader, el bus o el compilador. Este objetivo **no es el contrato vigente de `iflow-16`** hasta
que se enmiende ADR-038; el PLAN solo registra el trabajo pendiente.

**Estado real.** `iflow-32` ya cumple el objetivo: ADR-037 §1.2 fija little-endian único en ROM,
cabecera, TOC, segmentos, instrucciones, memoria y MMIO. `iflow-16` no lo cumple: ADR-038 §8 define
cabecera y TOC en big-endian, y el ISA v2 usa big-endian para direcciones e inmediatos; solo
`LDW`/`STW` y el segmento `CONSTANTS` son little-endian. La cabecera de 32 B, la TOC de 16 B,
`entryPoint`, `tocOffset`, `fileOffset`, `size`, `memSize`, `payloadSize`, los offsets y los
inmediatos son los campos que cambian de orden.

**Alcance del cambio pendiente.**

- Enmendar ADR-038 §8: cabecera, TOC, segmentos, direcciones, tamaños, `entryPoint` y offsets pasan
  a little-endian; se conservan offsets, tamaños y validaciones del layout ROM2, pero cambia la
  interpretación de cada campo multibyte. El hash SHA-256 sigue siendo una secuencia de 32 bytes y
  no se byte-swap.
- Revisar ADR-037 §1.2, §3 y §8 para dejar explícito que `iflow-32` ya es little-endian y que esta
  decisión no lo modifica.
- Cambiar el formato `.flow` v2 de forma breaking: parser, cargador, compilador/ensamblador TS,
  desensamblador, fixtures, editor web y tests. Sin lector doble, alias, detección automática de
  orden ni migración en runtime; un artefacto big-endian se rechaza explícitamente.
- Revisar el ISA v2: inmediatos, direcciones y campos multibyte de instrucción pasan a
  little-endian. `LDW`/`STW` y `CONSTANTS` ya leen/escriben little-endian y mantienen su orden de
  memoria.
- Añadir regresiones: un `.flow` v2 big-endian falla con error de formato; un `.flow` v2
  little-endian se carga y ejecuta; el compilador emite solo little-endian; parser,
  desensamblador y editor comparten el mismo layout; `iflow-32` conserva sus tests de
  little-endian.
- Actualizar `src/iflow-16/README.md`, `src/iflow-32/README.md` y la referencia de formato de cada
  línea. `docs/prompts/` no se modifica sin autorización explícita.

**Prioridad y orden.** Media/alta. No bloquea V3-2, pero debe cerrarse antes de declarar congelado
el formato de `iflow-16` y antes de V4/V5, para no generar artefactos v2 con dos órdenes de bytes.
La decisión no autoriza a tocar el código en este cambio: la enmienda de ADR-038 y la migración del
formato requieren su propia unidad de trabajo con tests y gate verde.

**Criterio de cierre.** No queda ningún campo multibyte big-endian en `iflow-16` ni en `iflow-32`;
no hay lector doble ni migración en runtime; un artefacto con orden distinto falla explícito;
compilador, parser, desensamblador, editor, fixtures y tests comparten un único layout
little-endian; gate completo verde.

### Fases de `iflow-32` (32 bits)

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **V0 — Módulo y spec** | ✅ **Completado (2026-11-15):** `src/iflow-vm/` renombrado a `src/iflow-16/`; `src/iflow-32/` contiene los paquetes v3; `settings.gradle`, publicaciones, cobertura, lockfiles, tests, configuración, wiki y READMEs actualizados. El hub selecciona por nombre y no hace fallback entre `iflow-16` e `iflow-32`; `iflow-32` queda sin `ModuleActivator` hasta V4 | `:src:iflow-16:test` (428) y `:src:iflow-32:test` (253 al cerrar V0; 297 en el inventario actual) verdes; `./gradlew build checkArchitecture checkLocks` verde; `src/web-editor` en verde |
| **V1 — Contrato 32 bits** | ✅ **ADR-037 (2026-11-14, enmendado 2026-11-16):** ISA, memoria/MMU, IRQ, scheduler, tiempo, formato ROM y ABI `HOST` v3 de `iflow-32`; la enmienda añade multinúcleo intra-VM, atómicas `LR.W`/`SC.W`/`AMO*`, IPI, estado por core y journal concurrente. ADR-038 (2026-11-15) separa la línea `iflow-16`, que conserva un único vCPU por diseño, y acota el rechazo de `.flow` v2 a `iflow-32` | Contrato v3 congelado y enmendado; convivencia por hub, sin alias ni despacho dual |
| **V2 — Núcleo** | **V2-0 completado (2026-11-14):** contenedor ROM v3 aislado en `me.majhrs16.suite.iflow.vm.rom` (`RomParser`, cabecera/TOC de 80/64 bytes, `PRG3`, capacidades, hashes SHA-256, `W^X`, rechazo explícito de `.flow` v2) con 62 tests. **V2-1 completado (2026-11-14):** decoder v3 aislado en `me.majhrs16.suite.iflow.vm.isa.v3` (`Opcode`, `Instruction`, `InstructionDecoder`, `MachineFault`, registros/CSR, cajas de 3/7 bytes, formas, `size`, reservados y lector de instrucciones) con 25 tests. **V2-2 completado (2026-11-14):** ALU v3 aislada (`Alu`, `Flags`, `RegisterFile`) con 42 tests de aritmética, `ADC`, préstamo/carry, overflow, desplazamientos, `MUL`/`DIV`/`MOD` con signo y sin signo, trampas aritméticas y banco de 16 registros. **V2-3 completado (2026-11-14):** núcleo de ejecución aislado (`Cpu`, `Status`, `CsrFile`, `DataMemory`, `MemoryFault`, `HostCall`, `HostHandler`, `HostResult`, `HostStatus`, `TimeSource`) con fetch/decode/execute, GPR/flags/CSR, memoria y alineación, saltos/`CALL`/`RET`, privilegios, `ECALL`/`ERET`, `HOST` mínimo, `RDCYCLE`/`RDTIME`, `SFENCE.VMA`/`FENCE`, `BREAK`/`NOP`/`HALT`, doble falta y `TVEC=0`. **V2-4 completado (2026-11-15):** memoria física dispersa con snapshots COW por frame, MMU de dos niveles con TLB coherente, bus MMIO supervisor, PIC, timer, UART, display, NVRAM, SYSINFO, host mailbox y ABI `HOST` v3 completo (TLV, identidad, capacidades, `ROUTE_DECISION` única y mapeo de `SyncResult`) con 55 tests nuevos. Los cinco slices están **sin cablear** a `VmPolicyEngine`/`VmConfigLoader`/`Iflow16Activator`. El núcleo v3 de V2 queda cerrado; **`iflow-16` queda cerrado funcionalmente** (CPU, `HOST`, ROM, cola de entrega y límites documentados). Siguiente: V3 (persistencia, tiempo y multinúcleo) y, en V4, integrar `iflow-32` en el hub sin despacho dual ni retirada de `iflow-16`. | Tests de memoria, privilegios y arranque |
| **V3 — Persistencia, tiempo y multinúcleo** | **V3-1 completado (2026-11-16):** reloj virtual `u64` (`VirtualClock`), `ExecutionEngine` de host con quantum de preempción 1M/50 ms por slice (`PREEMPTED` reanudable, sin fuel ni límite total de ejecución) y watchdog de paso configurable que no cobra `HOST`, avance de tiempo en `HALT` y despertar por timer, y comparación unsigned de deadlines en `Timer`; 22 tests nuevos (275 en `iflow-32`). **V3-2 en curso (2026-11-20):** `CoreCountConfig`, `COREID` y `SYSINFO.CORE_COUNT`/`FEATURES` bit 6 implementados, más las atómicas `LR.W`/`SC.W`/`AMO*`, con 22 tests nuevos (297 en `iflow-32`); faltan estado por core, IPI, PIC por core, TLB shootdown, scheduler de guest, watchdog por core y journal concurrente. **V3-3 pendiente:** journal por unidad de mensaje, persistencia entre mensajes y cierre de concurrencia. Hasta V3-2, `CORE_COUNT > 1` falla explícito; `iflow-16` conserva un único vCPU por diseño | Tests de ejecución persistente, multinúcleo determinista con 1 core y cancelación; V3 no se declara cerrada |
| **V4 — Integración** | `iflow-32` conectado a `Router`/`MessageDispatcher`; `iflow-16` conservada como línea soportada y seleccionable por el hub | Un mensaje real atraviesa el pipeline con una política `.flow` de 32 bits |
| **V5 — Herramientas de ensamblado** | Assembler `.flowasm` v3, firmware, ejemplos y validador | Build reproducible + tests |
| **V6 — Compilador C** | Compilador C11 freestanding a `.flowasm` v3, `libiflow` y ejemplos | Corpus C compila, ejecuta en VM v3 y produce el mismo resultado observable; tests y determinismo |

#### Cierre y soporte de `iflow-16` (previo a V3)

`iflow-16` debe quedar funcional y soportada por retrocompatibilidad, no solo congelada:

- **✅ CPU (2026-11-16):** las 36 opcodes de la ISA v2 están implementadas y ninguna queda como stub. La cobertura de ejecución se completa en `VmInterpreterTest` con `MOD` (resto con signo y división por cero), `COPY`/`MOVE` (bloques y solapamiento por DMA), `SAR`, `JNZ`/`JC`/`JV`, desbordamiento de `DIV`, salto fuera del código y registro no GPR. El ABI `HOST` v2 sigue cubierto por los servicios registrados y sus regresiones.
- **✅ Fix aplicado (2026-11-15, prioridad media):** `PageEntry.PAGE_MASK` pasó a `0xF000` (frame de 16 bits, página de 4 KB). `PageEntry` rechaza frames fuera del espacio de 16 bits y `PageTable.mapPage`/`mapPageKernel` validan `frameBase` en `0x0000..0xFFFF` antes de mapear, con `MEMORY_FAULT` explícito; ya no se propaga `ArrayIndexOutOfBoundsException` desde `SoftMMU`. Regresión: `PageEntryTest` y `PageTableTest` cubren `0x10000`, `-1`, todos los frames válidos y la máscara; `:src:iflow-16:test` y el gate completo quedan verdes.
- **✅ ROM (2026-11-16):** `.flow` v2 es una imagen ROM2 de 16 bits con cabecera de 32 B, TOC de 16 B por entrada, segmentos tipados (`BIOS`, `BOOTLOADER`, `KERNEL`, `PROGRAM`, `CONSTANTS`, `CAPABILITIES`, `DEBUG`), flags `R/W/X/U` y hash SHA-256 final. El parser, el cargador, el compilador, el desensamblador, los fixtures y los tests usan el mismo layout; el contenedor anterior de 20 B se rechaza por `layoutMagic` sin lector doble. El BIOS se carga en la página 0, `PROGRAM` en página alineada y `CONSTANTS` en `0x2000`; el cargador omite la apertura MMIO y aplica permisos finales. Detalle del contrato en ADR-038 §8.
- **Pendiente — Endianness única (prioridad media/alta):** el layout ROM2 actual es mixto:
  cabecera, TOC, direcciones e inmediatos son big-endian, mientras que `LDW`/`STW` y `CONSTANTS`
  son little-endian. El objetivo es little-endian único en `iflow-16` e `iflow-32`; `iflow-32` ya
  lo cumple. Requiere enmienda de ADR-038, cambio breaking de `.flow` v2 y actualización de
  parser, cargador, compilador, desensamblador, editor, fixtures y tests. Detalle en «Endianness
  única: little-endian en ambas líneas».
- **✅ HOST (2026-11-16):** `SCOPE_EMITTER`/`SCOPE_RECIPIENT` exponen nombre, UUID e idioma del emisor/destinatario; el puente `SYNC_INVOKE` carga datos del entorno al vuelo (`player.list` en `bukkit`/`fabric`) en un buffer acotado y sin truncamiento (`BUFFER_TOO_SMALL` + tamaño requerido). Documentado el techo práctico de unos pocos cientos de entradas con UUID/mundo en 64 KB; regresión en `HostServiceRegistryTest`.
- **✅ Cola de entrega y rate limiting (2026-11-16):** `DeliveryExecutor` encola las decisiones `RATE_LIMIT` en `BoundedRateLimitQueue` (750 entradas por defecto), respeta `backoffMillis` sin bloquear el despacho y entrega de inmediato cuando el backoff es cero. Una cola llena devuelve `DISCARDED`, incrementa el contador `discarded` y deja un `warn` explícito; nunca se reporta como entrega correcta. `DeliverySummary`, `PipelineOutcome` y `DispatchOutcome` exponen `queued`/`discarded`, con regresiones en `DeliveryExecutorTest`. La capacidad es un techo de seguridad del host: cada entrada retiene la unidad renderizada, no un ID de 16 bits, así que 750 no es una medición de la RAM de la VM.
- **✅ Límites de la cola en RAM de `iflow-16` (2026-11-16):** presupuesto documentado en `src/iflow-16/README.md` §«Límites de la cola de rate limit». Con 24 KB útiles, un handle de 16 bits da 12.288 entradas; una entrada de índice (2) + deadline (4) + estado (1) + flags (1) da 3.072; con generación (2) baja a 2.457; UUID binario da 1.536 y UUID-texto 768 (750 con 24.000 B). Una cola en la VM usaría handles + metadatos, no UUID completos; esta línea no añade una cola a la VM. El límite de 750 de `BoundedRateLimitQueue` es el caso conservador de UUID-texto y un techo de seguridad del host, no una medición del heap de la VM.
- **Soporte:** journal/estado de host suficiente para diagnóstico y recuperación; compatibilidad de `.flow` v2 en esta línea.
- **Criterio de cierre:** tests verdes, límites medidos y documentados, y ninguna promesa de memoria por encima de 64 KB. No se inicia V3 hasta cerrar esta línea.

La convivencia con `iflow-32` se resuelve en el hub: cada módulo tiene su ISA y no hay doble lector ni despacho dual. Se permite compartir primero `core-api`, modelos neutrales, puertos de host y utilidades sin semántica de ISA; cuando una línea necesite divergir, el componente se bifurca. No hay alias `iflow-16` ni fallback automático entre líneas.

#### V6 — Compilador C (ADR-023)

**Estado:** diseño aceptado; implementación pendiente. No se inicia antes de
V5 (assembler v3); V1 (ISA/ABI v3) ya está congelado en ADR-037 y la ejecución del corpus requiere
V2/V3/V4.
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
v3 que aún no están implementados; el contrato ya está congelado en ADR-037.
Implementarlo antes obligaría a inventar el contrato o a clonar el ISA v2;
ambas cosas contradicen la regla de no crear un segundo camino. Se documenta y
se deja bloqueado por V5; V1 ya está congelado, no se adelanta como POC.

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


### Paridad funcional pendiente: `iflow-32` (v3) ↔ `iflow-wasm`

> **Base de la paridad:** la **spec v3** (`docs/prompts/diseno/iflow-cpu-implement.md`), no el código
> de `src/iflow-16`. Ese código implementa la **v2 de 16 bits** y se conserva como línea legacy; sus
> `PROP_*`, `MessageHostContext`, `PolicyTarget` y `HOST` v2 **no son contrato vigente**.
>
> **Alcance:** replicar en Wasm el contrato v3 de transformación, decisión y entrega, sin heredar ISA,
> MMU, BIOS/kernel ni ABI `HOST` v2. **No** incluye el backlog V2–V5 de `iflow-32`; V1 ya está congelado en ADR-037.

El objetivo es que una política `.wasm` observe y transforme el mensaje y emita la misma decisión
explícita que una política `.flow` v3, con el mismo resultado observable. La paridad es de **contrato**,
no de implementación: los nombres de imports pueden diferir, la semántica no.

| Área | Contrato v3 | Pendiente en `iflow-wasm` |
|---|---|---|
| Contexto de solo lectura | Tipo, dirección, emisor, destinatario, canal e identidad del mensaje, leídos como parámetros del IRQ. | Imports de lectura; sin mutación. |
| Contenido | `TRANSFORM_APPLY` solo contenido/metadatos: texto, placeholders y formato. | Host functions de lectura/escritura con buffer TLV/longitud; UTF-8 sin truncar. |
| Metadatos | Idioma origen/destino, color, flags y `PROP_TRANSLATE` booleano. Precedencia de idioma ratificada (`Q35` / `IFLOW-MAN-42`). Sonidos, `PROP_PROCESSED` y `PROP_ACTOR_WORLD` quedan fuera del baseline v3 (`IFLOW-MAN-31`). | Implementar `PROP_TRANSLATE` con validación `true`/`false`; **no copiar** los `PROP_*` v2 retirados. |
| Canal | `textformatter.channel.set` validado como única mutación de canal; si cambia permisos/destinatarios, segunda fase de routing, no reentrega recursiva. | `sync_invoke("textformatter", "channel.set", ...)` + validación de existencia y permisos. |
| Routing | Decisión explícita y tipada; el guest decide y el host registra. Default deny + `DELIVER` explícito, `textformatter.channel.set` validado, sin `REDIRECT`/`CHANNEL_REDIRECT`, `PolicyTarget` reducido y `CANCEL_EVENT` separado (D01–D05 ratificadas en ADR-031). | Import de decisión con target/destino/razón; sin `route:*` ni `SET_PROCESSED`; una sola decisión por ejecución. |
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

> **ADR-036 §16 (aceptado el 2026-11-06):** sustituye el import dedicado `SET_CHANNEL`
> por la acción agnóstica `textformatter.channel.set` invocada con `sync_invoke("textformatter", ...)`,
> y añade `sync_surface`/`sync_invoke` para las acciones nativas de los backends sync. Las filas
> «Canal» y «Routing» y los criterios 2 y 6 de W3.5 quedan actualizados; D01–D05 no cambian.

**Criterios de cierre:**

1. Un corpus de políticas `.flow` v3 (ROM) y `.wasm` produce la misma decisión explícita
   (target/destino/razón) y el mismo mensaje transformado para las mismas entradas.
2. `textformatter.channel.set` valida existencia y permisos igual en ambos motores; un cambio que
   altere destinatarios se resuelve como segunda fase de routing, no como reentrega recursiva.
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
backlog V2–V5 de `iflow-32`; V1 ya está congelado en ADR-037.

### Decisiones pendientes (ADR)

- **`iflow-32`:** ISA de 32 bits, mapa de memoria, MMU, IRQ, scheduler, política de tiempo
  (quantum/timer/watchdog), journal, concurrencia y ABI `HOST` v3. No acepta `.flow` v2; esa
  compatibilidad pertenece a `iflow-16` (ADR-038).
- **Endianness de `iflow-16`:** estandarizar little-endian único en `iflow-16` e `iflow-32`
  (prioridad media/alta). `iflow-32` ya lo cumple por ADR-037 §1.2; `iflow-16` conserva cabecera,
  TOC, direcciones e inmediatos big-endian en ADR-038 §8. Requiere enmienda de ADR-038, cambio
  breaking de `.flow` v2 y actualización de parser, cargador, compilador, desensamblador, editor,
  fixtures y tests, sin lector doble ni migración en runtime.
- **`iflow-wasm`:** nombre/artefacto definitivo, versión de Chicory (W0 fija `runtime:1.4.0`;
  falta decidir compilador/WASI), capabilities y límites. El ABI de imports, la validación de
exports, el hash del `.wasm` y el hot-reload quedaron cerrados en W1/W3 (`PolicyAbiV3`,
  `WasmPolicyRouter`, ADR-033).
- **Paridad `iflow-32` ↔ `iflow-wasm`:** contrato de política cerrado en ADR-031 (D01–D05,
  Q26, Q35–Q38, `IFLOW-MAN-31/43…45`, ABI TLV y decisión única). El hash/hot-reload y la selección
  de motor quedaron cerrados en ADR-032/ADR-033; falta la matriz de paridad W3.5, alineada a la
  **spec v3** y no al ABI v2 de `src/iflow-16`.

### Próxima acción inmediata (V0/W0/W1, CT1–CT4 ✅ y cierre de paridad)

1. Mantener `docs/prompts/diseno/iflow-cpu-implement.md` como especificación técnica de referencia v3 de la VM de 32 bits.
2. Conservar `src/iflow-16/` como línea legacy de 16 bits y crear/extraer `src/iflow-32/` como línea de 32 bits.
3. Mantener el scaffold de `src/iflow-wasm/` (Chicory, loader, tests, lockfile/verificación) validado con JDK.
4. ADRs del motor Wasm y de la VM de 32 bits: cerrados en ADR-031, ADR-032 y ADR-033.
5. Completar la paridad funcional de propiedades y decisiones entre ambos motores contra la spec v3 (W3.5, desbloqueado por ADR-031); W3 ya está cerrado con `WasmPolicyRouter`, SHA-256 y hot-reload. No se toma el código v2 como contrato ni se aborda el backlog V2–V5 de `iflow-32` (V1 ya está congelado en ADR-037).
6. **ChatTranslator:** CT4 completado; CT5 diferido con el editor web.
7. **V6 — Compilador C:** diseño en ADR-023; no se inicia antes de V5. La implementación empieza por C0 (subconjunto y ABI) con el ISA v3 ya congelado en ADR-037; no se adelanta contra el ISA v2.

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
   `/suite repo install iflow-16 confirm`.
4. Las dependencias satisfechas por `Provide` se reconocen tanto al instalar como al
   comprobar dependencias inversas y al diagnosticar paquetes rotos.

Ejemplo genérico de un paquete que sustituye a un puente legacy:

```
Exclude: legacy-bridge
Provide: router
```

Los tres motores iFlow **no** declaran `Exclude` entre sí (ADR-032). Cada uno declara su
propia capability (`iflow-16`, `iflow-wasm`) y `router`; el hub `iflow` es el paquete que
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

> **Situación actual (2026-11-13):** inventario medido sobre el árbol vigente — **43 módulos Java** en `src/`:
> - **0 sin directorio de tests** — `core-render` incorporó `src/test` en este cambio
> - **0 directorios vacíos**
> - **43 con tests** (1–29 archivos); `iflow-16` (29) y `core-api` (20) son los mayores
> - **28 módulos con reporte JaCoCo**; 15 sin reporte (cobertura N/A)
> - Cobertura de línea < 70 %: `sync-fabric` 10.5 %, `sync-discord-jda` 19.4 %, `fabric-host` 20.7 %, `spigot-host` y `transport` 30.3 %, `sync-velocity` 42.3 %, `sync-discord` y `messages` 60.0 %, `coretranslator` 61.0 %, `iflow-firewall` 61.7 %, `sync-websocket` 62.1 %, `sync-velocity-core` 62.3 %, `observability` 65.7 %

**Inventario detallado (T1 actualizado 2026-11-13):**

| Módulo | Test Dir | Archivos Test | Líneas Prod | Líneas Test | Cobertura Línea | Cobertura Rama | Gaps |
|---|---|---:|---:|---:|---:|---:|---|
| `src:chattranslator` | ✅ OK | 4 | 799 | 656 | N/A | N/A | Sin reporte JaCoCo |
| `src:core-api` | ✅ OK | 20 | 8800 | 2526 | N/A | N/A | Sin reporte JaCoCo |
| `src:core-render` | ✅ OK | 1 | 132 | 46 | N/A | N/A | Sin reporte JaCoCo; Pocos tests (<3) |
| `src:coretranslator` | ✅ OK | 3 | 589 | 522 | 61.0% | 51.5% | Cobertura línea <70% |
| `src:example-extension` | ✅ OK | 1 | 141 | 45 | N/A | N/A | Sin reporte JaCoCo; Pocos tests (<3) |
| `src:extension-api` | ✅ OK | 4 | 883 | 569 | N/A | N/A | Sin reporte JaCoCo |
| `src:fabric-host` | ✅ OK | 3 | 1117 | 344 | 20.7% | 12.4% | Cobertura línea <70%; Cobertura rama <50% |
| `src:gtranslate` | ✅ OK | 5 | 199 | 566 | N/A | N/A | Sin reporte JaCoCo |
| `src:host` | ✅ OK | 15 | 3083 | 3300 | 87.4% | 79.0% | — |
| `src:iflow` | ✅ OK | 3 | 651 | 839 | 86.0% | 77.9% | — |
| `src:iflow-firewall` | ✅ OK | 6 | 2378 | 887 | 61.7% | 51.8% | Cobertura línea <70% |
| `src:iflow-16` | ✅ OK | 29 | 7319 | 6813 | 86.0% | 72.4% | — |
| `src:iflow-wasm` | ✅ OK | 6 | 2163 | 1485 | N/A | N/A | Sin reporte JaCoCo |
| `src:inworld` | ✅ OK | 3 | 332 | 349 | 89.0% | 74.2% | — |
| `src:kernel` | ✅ OK | 5 | 465 | 324 | 91.8% | 82.5% | — |
| `src:loadtest` | ✅ OK | 3 | 839 | 877 | N/A | N/A | Sin reporte JaCoCo |
| `src:logger` | ✅ OK | 4 | 362 | 405 | 85.4% | 78.6% | — |
| `src:ltranslate` | ✅ OK | 5 | 223 | 635 | N/A | N/A | Sin reporte JaCoCo |
| `src:manager-apt` | ✅ OK | 4 | 2115 | 558 | N/A | N/A | Sin reporte JaCoCo |
| `src:manager-core` | ✅ OK | 3 | 1952 | 1279 | N/A | N/A | Sin reporte JaCoCo |
| `src:manager-spi` | ✅ OK | 5 | 1139 | 834 | N/A | N/A | Sin reporte JaCoCo |
| `src:messages` | ✅ OK | 2 | 146 | 117 | 60.0% | 64.3% | Cobertura línea <70%; Pocos tests (<3) |
| `src:observability` | ✅ OK | 6 | 901 | 556 | 65.7% | 60.7% | Cobertura línea <70% |
| `src:performance` | ✅ OK | 2 | 1630 | 135 | N/A | N/A | Sin reporte JaCoCo; Pocos tests (<3) |
| `src:presets` | ✅ OK | 6 | 1199 | 757 | N/A | N/A | Sin reporte JaCoCo |
| `src:spigot-host` | ✅ OK | 5 | 1167 | 674 | 30.3% | 25.9% | Cobertura línea <70%; Cobertura rama <50% |
| `src:suite-runtime` | ✅ OK | 12 | 5421 | 4304 | N/A | N/A | Sin reporte JaCoCo |
| `src:sync-bukkit` | ✅ OK | 3 | 308 | 383 | 78.7% | 55.9% | — |
| `src:sync-bus` | ✅ OK | 4 | 1848 | 2465 | 72.8% | 68.2% | — |
| `src:sync-common` | ✅ OK | 4 | 624 | 531 | 87.9% | 76.7% | — |
| `src:sync-discord` | ✅ OK | 3 | 595 | 477 | 60.0% | 47.4% | Cobertura línea <70%; Cobertura rama <50% |
| `src:sync-discord-jda` | ✅ OK | 1 | 327 | 71 | 19.4% | 14.1% | Cobertura línea <70%; Cobertura rama <50%; Pocos tests (<3) |
| `src:sync-discord-jdk` | ✅ OK | 2 | 557 | 582 | 84.6% | 64.3% | Pocos tests (<3) |
| `src:sync-fabric` | ✅ OK | 2 | 324 | 74 | 10.5% | 16.7% | Cobertura línea <70%; Cobertura rama <50%; Pocos tests (<3) |
| `src:sync-http` | ✅ OK | 5 | 803 | 964 | 79.2% | 64.1% | — |
| `src:sync-socket` | ✅ OK | 4 | 746 | 612 | 78.3% | 56.0% | — |
| `src:sync-telegram` | ✅ OK | 3 | 654 | 557 | 84.7% | 74.5% | — |
| `src:sync-velocity` | ✅ OK | 1 | 262 | 139 | 42.3% | 60.0% | Cobertura línea <70%; Pocos tests (<3) |
| `src:sync-velocity-core` | ✅ OK | 1 | 949 | 485 | 62.3% | 54.1% | Cobertura línea <70%; Pocos tests (<3) |
| `src:sync-websocket` | ✅ OK | 3 | 890 | 426 | 62.1% | 51.4% | Cobertura línea <70% |
| `src:tester` | ✅ OK | 3 | 1017 | 293 | 83.5% | 55.8% | — |
| `src:textformatter` | ✅ OK | 9 | 1558 | 1286 | 87.2% | 70.3% | — |
| `src:transport` | ✅ OK | 2 | 1038 | 395 | 30.3% | 28.8% | Cobertura línea <70%; Cobertura rama <50%; Pocos tests (<3) |

**Resumen:** 43 módulos totales · **43 con tests** · **0 sin test dir** · **0 vacíos** · 28 con cobertura real · 15 con tests pero sin reporte JaCoCo.

Líneas Prod/Test = líneas físicas de todas las `.java` de `src/main` y `src/test`; cobertura = JaCoCo del último gate (2026-11-13).

**Objetivo:** que **cada módulo** tenga una suite de tests unitarios y de componente con **cobertura significativa** (líneas/branches ≥ 70 % en código de producción, ≥ 90 % en SPI/API pública) y tests de regresión para cada bug/fix documentado.

| Fase | Entregable | Criterio de cierre |
|---|---|---|
| **T0 — Inventario y baseline** ✅ | Matriz `módulo × {archivos test, cobertura actual, gaps críticos}` generada por script CI | Tabla inline arriba con datos reales |
| **T1 — Scaffold obligatorio** ✅ | Todo módulo Java tiene `src/test` con JUnit 5 + AssertJ + Mockito; `build.gradle` incluye `testImplementation` y task `test` pasa en CI | **0 módulos sin directorio de tests; 0 directorios vacíos** |
| **T2 — Gates de cobertura por módulo** ✅ | `jacocoTestCoverageVerification` con reglas **por módulo** (no global): `minLineCoverage`, `minBranchCoverage`; falla el build si no se cumple | **Gates verdes en CI para todos los módulos** (umbrales según baseline T1) |
| **T3 — Tests de contrato SPI/API** ✅ | Cada `core-api`, `extension-api`, `manager-spi`, `kernel` tiene tests que validan contratos (ServiceLoader, interfaces, modelos inmutables) sin depender de implementaciones | **Tests de contrato pasan en aislamiento**; documentación de contratos generada |
| **T4 — Tests de integración por dominio** ✅ | `sync-*`, `transport`, `host`, `gtranslate`/`ltranslate`, `presets`, `inworld`, `observability`, `messages`: tests con testcontainers/mocks de red, disco y plataforma | Suite de integración ejecuta en CI (< 10 min) sin servidor real; el E2E real es la tarea E2E.1 posterior a 1.0.0 (ADR-034) |
| **T5 — Tests de regresión obligatorios** | Cada bug/fix P0/P1 de `AUDITORIA.md`, commits `fix:` tiene al menos un test que falla sin el fix y pasa con él | Matriz bug→test inline en `docs/workdir/PLAN.md` |
| **T6 — Fuzzing y property-based** | Módulos de parsing/serialización (`textformatter`, `messages`, `iflow-16` bytecode, `iflow-wasm` loader) usan jqwik o JUnit-Quickcheck | Campañas nocturnas en CI; hallazgos → issues automáticos |
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

**Próxima acción:** V1 cerrado con ADR-037 (enmendado 2026-11-16); V2-0 (contenedor ROM v3 aislado), V2-1 (decoder ISA v3 aislado), V2-2 (ALU, flags y registro v3 aislados), V2-3 (núcleo de ejecución v3 aislado) y V2-4 (memoria física, MMU, bus, PIC, dispositivos y ABI `HOST` v3 completo) completados con 62 + 25 + 42 tests más la suite del núcleo y 55 tests nuevos del slice V2-4. El núcleo v3 de V2 queda cerrado; la separación `src/iflow-16`/`src/iflow-32` está completada (V0) y el fix de `PageEntry.PAGE_MASK` está aplicado; `iflow-16` queda cerrado funcionalmente (CPU, `HOST`, ROM, cola de entrega y límites documentados); V3-1 completado: `VirtualClock`, `ExecutionEngine` con quantum de preempción (`PREEMPTED` reanudable, no fuel ni límite total) y watchdog de paso configurable, sleep/wake por timer y comparación unsigned de deadlines, con 275 tests en `:src:iflow-32:test`; V3-2 en curso (2026-11-20): `CoreCountConfig` con fallo explícito para `CORE_COUNT > 1`, `COREID` `0x10A` y `SYSINFO.CORE_COUNT`/`FEATURES` bit 6, 22 tests nuevos (297 total); atómicas `LR.W`/`SC.W`/`AMO*` implementadas; faltan estado por core, IPI, PIC por core, TLB shootdown, scheduler de guest, watchdog por core y journal concurrente; V3-3 pendiente: journal por mensaje y persistencia entre mensajes. `iflow-16` conserva un único vCPU por diseño; hasta V3-2, `CORE_COUNT > 1` falla explícito. En V4 se integra `iflow-32` en el hub sin despacho dual ni fallback; ADR-038 define la convivencia y el ciclo de vida de ambas líneas. W1/W2/W3 cerrados (contrato `iflow.*`, firmas, TLV, `PolicySession`, host functions, `WasmPolicyEngine`, `WasmPolicyRouter`, SHA-256 y hot-reload). Falta W3.5: matriz de paridad `.flow` v3 vs `.wasm` con el mismo resultado observable, sin reabrir el contrato v2. Queda pendiente la estandarización little-endian única de `iflow-16` e `iflow-32` (prioridad media/alta): `iflow-32` ya la cumple y `iflow-16` requiere enmienda de ADR-038 y cambio breaking de `.flow` v2.

---

## Estado actual del proyecto

El estado operativo vigente está en la cabecera de este PLAN y en `ADR.md`; no se mantiene aquí un
snapshot duplicado. Lo verificado y lo desconocido se declara de forma explícita:

- Spigot/Paper: el plugin carga y el módulo tester se ejecutó en Paper 1.20.6. Eso fue un smoke test,
  no un E2E.
- Fabric: el mod compila y el probe se ejecuta; no hay E2E real.
- Velocity: el plugin compila y los tests de paquete pasan; no hay E2E real.
- Cualquier combinación no listada se considera **desconocida** hasta el E2E posterior a `1.0.0`.

### Revisiones manuales pendientes (prioridad baja/media)

> **«Manual» significa humano, y el diagnóstico se escribe en `AUDITORIA.md`.** La revisión la hace
> una persona, **función por función**, y explica en lenguaje natural qué hace cada una, cuándo se
> ejecuta, qué efecto produce y qué deja fuera. Por ser manual, el archivo se edita **mientras la
> auditoría está en curso**: cada fallo o inconsistencia se anota ahí a medida que aparece. Terminado
> el diagnóstico, no se modifica. Una auditoría **agéntica** no se edita nunca: su informe se
> reemplaza por completo. En ambos casos, un ciclo nuevo empieza reemplazando el archivo. Un agente
> no ejecuta esta revisión ni la sustituye por otra: solo la registra o corrige lo que la persona
> indique. Las correcciones que surjan son trabajo aparte y, si son P0/P1, llevan test de regresión.

| # | Alcance | Criterio de cierre | Prioridad | Estado |
|---|---|---|---|---|
| **AUD-1** | Implementación de `sync`, recorrida función por función: contratos `api.sync.*`, autoridad y cuotas de `DefaultSyncBus`, superficies nativas de `sync-discord`/`telegram`/`http`/`websocket`/`socket`/`velocity-core`/`bukkit`/`fabric`, semántica de entrega (dedup, reintentos, timeout, colas), puente `SYNC_SURFACE`/`SYNC_INVOKE` y auditoría en `<data>/config/iflow/audit.log` | El diagnóstico cierra cuando `AUDITORIA.md` explica en lenguaje natural cada función o superficie (qué hace, cuándo se usa, qué efecto tiene, qué no cubre), marca como desconocido lo no revisado y queda congelado | Baja/Media | Pendiente |
| **AUD-2** | Funcionamiento general del Manager, recorrido función por función: `manager-spi`/`manager-core`/`manager-apt`, ciclo instalar → activar → desactivar, resolución de dependencias y metadatos del repositorio, classloading y `hot unload/upgrade` (P6), descarga de artefactos e integración con `suite-runtime` | Mismo formato en `AUDITORIA.md`: una entrada por función del Manager (qué hace, estados instalado/activo/fallido, qué garantiza y qué no) | Baja/Media | Pendiente |

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

> 🧱 **Toolchain de iFlow VM (2026-10-23, actualizado 2026-11-15).** `tools/iflow-compiler/` y
> `:src:iflow-16:test` son la base de `iflow-16`; se conservan. `iflow-32` tendrá su propio
> assembler v3 en V5 y sus tests en `:src:iflow-32:test`. El compilador C de V6 vivirá en
> `tools/iflow-c/` y emitirá `.flowasm` v3; no sustituye al assembler. En paralelo, `iflow-wasm`
> usa Chicory y artefactos `.wasm`, no `.flow`.

### Criterio de uso

- `context7` **antes** de escribir contra una API de terceros (Gradle, Paper, Fabric, JUnit…).
- `gh_grep` / `github` para ver cómo lo resuelven proyectos reales, no de memoria.
- `auditoria-seguridad-calidad` + `automated-test-engineer` al revisar código Java propio.
- `architecture-decision-records` cuando una decisión estructural merezca ADR.
- `release-notes` al cerrar una tanda de trabajo.
- `juzgar-rubrica` para refrescar la rúbrica de madurez (ver `tools/project-metrics/`).

---

## Principios de Arquitectura Técnica

Esta sección recopila los principios técnicos de diseño, costumbres del proyecto y razonamiento del "por qué" de las decisiones arquitectónicas. Pertenece al PLAN; el manifiesto abstracto de diseño vive en `docs/prompts/contexto/philosophy.md`.

### P1. El Manager es el origen de la composición

El Manager de paquetes es quien actualiza índices, descarga artefactos, resuelve dependencias, instala, **inicializa en memoria** y gestiona el ciclo de vida completo de los módulos. Los adaptadores de plataforma (Spigot/Fabric) no son composition roots ni conocen la existencia de los módulos de feature. Solo implementan puertos de plataforma; la captura y traducción de eventos vive en los paquetes `sync-bukkit`/`sync-fabric`, que además publican los sinks `bukkit`/`fabric`.

**Consecuencia:** `spigot-host` y `fabric-host` dependen solo de la API del núcleo + runtime de módulos + SDK de plataforma. Ninguna dependencia a `sync-*`, `textformatter`, `iflow-*`, `chattranslator`, `gtranslate`, `ltranslate`, `observability`, `manager-*` ni `presets`.

### P2. Instalar no es activar

La base de datos local (`library/status`) es un catálogo de programas instalados, no un registro de estado de ejecución. La presencia de un paquete implica que está instalado y que debe iniciarse. El estado de ejecución (encendido/apagado, fallido, requiere reinicio) no pertenece a `library/status`; si es necesario rastrearlo, se hace en un directorio temporal dentro del directorio del manager, nunca en el estado de instalación.

**Consecuencia:** `library/status` registra nombre, versión, canal, dependencias, archivos y hashes del artefacto. No guarda si un módulo está "activo" en un instante dado.

### P3. Los adaptadores de plataforma son delgados

Spigot y Fabric no deben conocer la existencia de ningún módulo de feature. Su propósito es simplemente implementar una API común entre plataformas y arrancar el runtime. La captura de eventos de Minecraft y su traducción a una representación interna agnóstica vive en paquetes de plataforma (`sync-bukkit`/`sync-fabric`), no en el adapter; esos paquetes también publican los sinks `bukkit`/`fabric` (entrada y salida) y su superficie. El cableado de sinks y servicios de plataforma vive en el runtime de módulos (`suite-runtime`).

**Consecuencia:** El adapter se reduce a crear `PlatformPorts`, arrancar `SuiteRuntime` y delegar; los listeners, la traducción de eventos y los sinks locales se cargan como paquetes `sync-bukkit`/`sync-fabric`.

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

**Consecuencia:** `iflow-16`, `iflow-32`, `iflow-wasm` e `iflow-firewall` son conceptualmente excluyentes para el routing, pero coexisten como paquetes independientes. El usuario elige cuál instalar y el hub activa uno solo; `iflow-16` e `iflow-32` nunca comparten despacho ni fallback.

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

### P17. Los eventos de plataforma se capturan en `sync-<plataforma>`, que también es sink

La sección del host que intercepta eventos de Minecraft y los entrega al núcleo agnóstico es, por definición, un sincronizador de plataforma. Se implementa como paquete descargable: `sync-bukkit` para Spigot/Paper y `sync-fabric` para Fabric. Cada host instala solo el paquete compatible con su entorno; ninguno se embebe en el JAR del host. Ese mismo paquete publica el sink local `bukkit`/`fabric` (entrada y salida) y su superficie, que incluye las operaciones sobre el evento actual (ADR-036 §17).

**Consecuencia:** `spigot-host`/`fabric-host` no contienen `@EventHandler` ni `ServerMessageEvents.CHAT_MESSAGE`; solo aportan `PlatformPorts` y el bootstrap. Los paquetes `sync-bukkit`/`sync-fabric` registran listeners, traducen a `Message`, despachan por `MessageDispatch`, publican el sink local y se desregistran en `stop()`; si la plataforma no permite desregistrar, se marca `RESTART_REQUIRED`.

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
