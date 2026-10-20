# Contribuir a TextFormatter Suite

Gracias por el interés. El proyecto está en desarrollo activo y **no publica
releases ni binarios**. La distribución es por código fuente y build local; no
se aceptan cambios que asuman una versión estable.

## Antes de empezar

- Lee [`README.md`](README.md), [`src/README.md`](src/README.md) y
  [`docs/workdir/PLAN.md`](docs/workdir/PLAN.md).
- Para decisiones estructurales, consulta [`docs/workdir/ADR.md`](docs/workdir/ADR.md).
- Para problemas de seguridad, **no abras un issue público**: usa
  [`SECURITY.md`](SECURITY.md).
- Respeta el [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## Entorno

- **JDK 21** para compilar y ejecutar Gradle. El bytecode objetivo es Java 17.
- **Gradle 8.10+** mediante el wrapper del repositorio (`./gradlew`).
- **Node.js 20+** solo para `src/web-editor`.
- No hace falta un servidor Minecraft para los tests unitarios.

En esta máquina de desarrollo, el JDK válido está en
`/opt/javac/x64/21`; los JRE de `/opt/java/*` no incluyen `javac`.

## Build y pruebas

```bash
# Build de todos los subproyectos, con tests
./gradlew buildAll --no-daemon

# Un módulo concreto
./gradlew :src:<modulo>:test --no-daemon

# Lockfiles y schema generado
./gradlew checkLocks --no-daemon
./gradlew :src:host:verifySchema --no-daemon

# Editor web
cd src/web-editor
npm ci
npm run check
npm run test:integration
```

Si el entorno no tiene red, usa `--offline` y comprueba que las dependencias ya
están en la caché de Gradle. No añadas dependencias nuevas sin actualizar
`gradle.lockfile` y `gradle/verification-metadata.xml`.

## Cambios de código

1. Abre una rama descriptiva y trabaja en ella.
2. Añade primero un test que reproduzca el fallo o fije el contrato.
3. Mantén el estilo y la estructura del módulo que tocas.
4. No commitees `build/`, `node_modules/`, logs, artefactos de IDE ni secretos.
5. Actualiza el README del módulo y, si cambia el estado público, el
   [`README.md`](README.md) raíz.
6. No crees tags ni ejecutes el workflow de release: **la publicación está
   descartada por decisión del proyecto** (ver `PLAN.md`).

## Tests

- Usa JUnit 5 (`org.junit.jupiter`) y `@TempDir` para ficheros temporales.
- Evita rutas absolutas compartidas y `Thread.sleep` cuando exista una espera
  determinista.
- Los tests de integración que necesiten un servidor Minecraft deben marcarse
  como tales y no fingir que se ejecutaron.
- Un módulo sin tests debe decirlo en su README; no se considera “probado” por
  compilar.

## Commits y Pull Requests

- Commits atómicos y en español.
- Conventional Commits sin acentos: `fix:`, `feat:`, `docs:`, `test:`, `ci:`,
  `build:`, `chore:`.
- Un cambio lógico por commit; explica el **porqué** en el cuerpo si no es obvio.
- Abre un Pull Request contra `main`. El workflow de CI está configurado para
  compilar, ejecutar tests, validar lockfiles/schema y probar el editor; su
  ejecución en GitHub no está garantizada mientras el proyecto siga en este
  estado.
- No se exige CLA. Al contribuir aceptas que tu aportación se distribuya bajo
  la licencia GPL-3.0 del proyecto.

## Documentación

- La documentación de usuario vive en `docs/wiki/`.
- Los documentos de trabajo (plan, auditorías, ADR) viven en `docs/workdir/`.
- Los prompts reutilizables viven en `docs/prompts/`; los procedimientos
  ejecutables, en `.opencode/skills/`.
- No dupliques una fuente de verdad: enlaza al documento existente.

## Licencia

El proyecto se distribuye bajo **GPL-3.0**; consulta [`LICENSE`](LICENSE).
