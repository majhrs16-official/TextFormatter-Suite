# Contributing Guide

## Before you start

TextFormatter Suite is pre-1.0. The base is `1.0.0` and the YAML schema is
`1.0`. There is no supported `2.x` line. Changes must keep code, tests,
documentation and the architecture/lockfile gates green.

Read these documents first:

- [ADR](../workdir/ADR.md) — accepted architecture decisions.
- [PLAN](../workdir/PLAN.md) — operational status and current phase.
- [Storage layout](../prompts/diseno/storage.md) — canonical disk paths.
- [Configuration schema](../prompts/especificaciones/config-schema-v2.2.md) —
  editor and runtime key contract.

The wiki explains usage; it is not the normative source.

## Requirements

- Java 17 or 21 (Java 21 recommended).
- Git.
- Gradle is provided by the wrapper; do not install a separate Gradle.
- Node.js 20+ only for `src/web-editor`.
- No real Spigot/Paper/Fabric/Velocity server is required for unit and
  integration tests. Real-server E2E is deferred until after `1.0.0`.

## Setup

```bash
git clone https://github.com/majhrs16-official/TextFormatter-Suite.git
cd TextFormatter-Suite

./gradlew build --no-daemon
./gradlew test --no-daemon
```

Full local gate:

```bash
./gradlew build checkArchitecture checkLocks --no-daemon
```

Useful focused commands:

```bash
./gradlew :src:textformatter:test --no-daemon
./gradlew :src:iflow-vm:test --no-daemon
./gradlew :src:suite-runtime:test --no-daemon
./gradlew spotlessApply
./gradlew spotlessCheck
```

The load-test module is `src/loadtest`:

```bash
./gradlew :src:loadtest:jmh
```

## Working rules

- One change per commit: code, tests, documentation and gate together.
- Every bug fix needs a regression test.
- Do not add legacy readers, aliases, shims or runtime migrations. This is the
  pre-1.0 regime from ADR-034.
- `1.0.0` is the release label; `1.0` is the YAML schema version.
- Every runtime configuration `.yml` declares `config-version: 1.0` at the root, preferably as
  the last key. `plugin.yml` is the only exception.
- Missing `config-version` fails with
  `version de configuracion no identificada (archivo editado o corrupto?)`.
- Do not add plans, phases or roadmaps to the ADR. Status belongs in `PLAN.md`.
- Do not push commits, tags or releases without explicit maintainer
  authorization.

## Branches and commits

The repository currently uses `main`. Create a short-lived branch for your
change:

```bash
git switch -c feat/descripcion-corta
```

Commit messages are written in Spanish and follow Conventional Commits:

```text
tipo(alcance): descripción

[cuerpo opcional]

[pie opcional]
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `perf`, `security`.

Examples:

```text
feat(iflow): añadir destino CHANNEL_REDIRECT
fix(iflow): corregir fuga del limitador de tasa
docs(wiki): documentar el layout canónico
refactor(core-api): simplificar ModuleDescriptor
test(loadtest): añadir benchmarks del pipeline
```

## Pull requests

1. Branch from `main`.
2. Add or update tests.
3. Update documentation in the same change.
4. Run the full gate.
5. Open a pull request with the problem, the change and the evidence.

Checklist:

- [ ] `./gradlew test --no-daemon` passes.
- [ ] `./gradlew build checkArchitecture checkLocks --no-daemon` passes.
- [ ] `./gradlew spotlessCheck` passes.
- [ ] Tests cover the bug or the new behaviour.
- [ ] Documentation matches the ADR and the real code.
- [ ] No legacy path, alias or migration was introduced.
- [ ] No secrets, tokens or machine-local paths were committed.

## Code standards

- Java 17/21.
- JUnit 5 for tests.
- Spotless for formatting; do not reformat unrelated files.
- Javadoc for public API.
- Keep platform code in the host adapters and neutral code in the modules.
- Do not import platform classes into `core-api`, `kernel` or `suite-runtime`.
- Do not add feature classes to the host Shadow JAR.

Import order:

1. `java.*` / `javax.*`
2. Third-party libraries
3. `me.majhrs16.suite.*`
4. Static imports

## Testing

Use plain JUnit 5. There is no Spring context in this project.

Unit test:

```java
@Test
void dispatchDeliversToRecipient() {
    var host = createTestHost();
    var sender = new Actor(UUID.randomUUID(), "Test", ActorKind.PLAYER, Language.EN, null);

    var message = Message.builder()
        .type(MessageType.CHAT)
        .sender(sender)
        .direction(Direction.OTHERS)
        .channel("chat.global")
        .text("Hello world")
        .build();

    var report = host.dispatch(message);

    assertTrue(report.delivered() > 0);
}
```

Integration test:

```java
@Test
void fullPipelineDispatches() {
    var host = createTestHost();
    var message = messageFor("chat.global", "Hello world");

    var report = host.dispatch(message);

    assertTrue(report.delivered() > 0);
}
```

Use real ZIPs, JARs and temporary directories for manager/runtime tests. Mock
only external transports that cannot run in CI.

## Web editor

```bash
cd src/web-editor
npm install
npm run check
npm run test:integration
```

The editor exports a project ZIP with flat paths (`config.yml`, `channels/`,
`sync/`). Those are editor-project paths, not runtime paths. The runtime layout
is `config/suite/` and `config/<module>/`.

## Documentation

- User documentation belongs in `docs/wiki/`.
- Architecture decisions belong in `docs/workdir/ADR.md`.
- Operational status belongs in `docs/workdir/PLAN.md`.
- Specifications belong in `docs/prompts/especificaciones/`.
- Do not duplicate ADR bodies in the wiki; link to them.
- Do not advertise commands, keys or paths that the code does not implement.

## Release process

`1.0.0` is the release label of the integrated base. It is not a claim that
real-server E2E has passed; those tests run after the release.

Version source:

```bash
# The build defaults to 1.0.0 unless -Pversion is supplied.
./gradlew build -Pversion=1.0.0
```

Checklist:

- [ ] Full gate passes.
- [ ] `PLAN.md` and `AUDITORIA.md` reflect the current state.
- [ ] Release notes are updated.
- [ ] Tag is created only after maintainer approval: `git tag -a v1.0.0`.
- [ ] No push of commits or tags without explicit authorization.
- [ ] Artifacts are attached to the release only if the maintainer requests it.

Maven Central publication and container images are not part of the 1.0.0 gate.

## Reporting issues

Include:

- version or commit hash;
- platform and server version;
- Java version;
- steps to reproduce;
- expected and actual behaviour;
- logs and the failing YAML file;
- whether the file declares `config-version: 1.0`.

## Support

- GitHub Issues: bugs and concrete proposals.
- GitHub Discussions: questions and design discussion.
- Wiki: user documentation.

---

*Contributing Guide v1.0 - Part of TextFormatter Suite Documentation*
