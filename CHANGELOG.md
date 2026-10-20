# Changelog

Todos los cambios relevantes de TextFormatter Suite se documentan aquí.

> **No hay releases públicos ni tags.** Este changelog registra el estado del
> desarrollo local; no implica que exista una versión publicada. La decisión de
> no publicar releases está registrada en `docs/workdir/PLAN.md`.

## [Unreleased]

### Added

- `buildAll` para compilar todos los subproyectos, incluidos sus tests.
- Lockfiles de `logger` e `iflow`; el proyecto tiene 32 lockfiles (raíz + 31
  subproyectos).
- Documentos de comunidad: `SECURITY.md`, `CONTRIBUTING.md`,
  `CODE_OF_CONDUCT.md`, `CODEOWNERS`, plantillas de issue/PR y este changelog.
- Tests de contrato para 9 módulos que antes no tenían `src/test`
  (`messages`, `observability`, `performance`, `presets`, `extension-api`,
  `example-extension`, `sync-bus`, `sync-websocket`, `inworld`; 38 tests).

### Changed

- La versión se propaga desde la raíz con `-Pversion`; los módulos ya no fijan
  `2.1.0-SNAPSHOT` por separado.
- `checkLocks` falla si falta un lockfile.
- El release usa `manager-spi/core/apt`, incluye `logger` y `common-legacy`,
  aplica la versión del tag y no depende de un `CHANGELOG.md` inexistente.
- El CI valida lockfiles, schema generado y metadata de dependencias con
  `--dependency-verification=strict`.
- README y documentación de módulos sincronizados con 31 módulos y estados
  verificables; la wiki de iFlow se marca como histórica y enlaza a la VM actual.
- Javadoc agregado para todos los subproyectos (`javadocAll`).
- SpEL eliminado de todo el repositorio: se retiraron el SPI `ExpressionEvaluator`, `SpelExpressionEvaluator`, el paso `<expr>` de `TemplateRenderer`, las reglas SpEL de presets, el campo de condición del editor web y las dependencias `spring-expression`/`spring-beans`. La única superficie de políticas ejecutables es la VM de iFlow (`.flow`).
- `TransformEngine` eliminado por ser código legado sin consumidores de producción; su test también se retiró.

### Fixed

- `DiscordSink` conserva el token hasta `stop()`.
- `SafeConstructor` en todas las cargas YAML de configuración y extensiones.
- Rechazo de `userinfo` en `HttpTransport` antes de validar la URL.
- `TransactionLog` tolera nulos, separadores y líneas corruptas.
- Validación de nombres/versiones y límites de recursos en el Module Manager.
- `DefaultSyncBus.stop()` ahora detiene cada `SyncSink` (no solo su worker) y despierta al procesador global para no esperar el timeout de 10 s.
- `Suite.utils.esc` del editor web escapa correctamente HTML; el guard
  `max-steps` acota el total de pasos de la simulación.
- Los harnesses de integración del editor cargan `paths.json` con un `fetch`
  local de JSDOM, en lugar de caer silenciosamente al fallback; el test de
  `bind` ya no reporta switches de rutas de array (`[]`) sin instancia.
- Schema del editor regenerado y `verifySchema` ejecutado en CI.
- Metadata de verificación completada para los artefactos de BOM y del classpath de Loom que el primer run de CI detectó como ausentes (`guava-parent`, `junit-bom`, `spring-framework-bom`, `adventure-bom`); `buildAll`, `javadocAll` y la verificación `strict` pasan con caché refrescada.

### Security

- Verificación de dependencias real (626 componentes) y lockfiles completos;
  excepción documentada para los classpaths de `fabric-host` (Loom remapea
  artefactos localmente).
- Límites de tamaño/profundidad en índices, manifiestos y paquetes del manager.
- SSRF: rechazo de `user:pass@host` y resolución validada.
- Secretos en `char[]` y comparaciones de tokens pendientes de migrar a
  comparación constante (deuda P1).

### Notes

- La historia de git **no se reescribe**; los commits antiguos con otro nombre
  comparten el mismo email y GitHub los atribuye a la misma cuenta.
- El editor web sigue teniendo deuda funcional y visual; los tests de integración
  pasan, pero no se considera una UI estable.
- Quedan módulos sin tests Java: `common-legacy` (sin fuentes) y `fabric-host`
  (requiere Fabric Loom/Minecraft). Los adaptadores de plataforma se documentan
  como “sin tests Java” hasta que existan pruebas reales.
