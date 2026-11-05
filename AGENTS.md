# AGENTS.md — TextFormatter Suite

> Instrucciones persistentes para agentes. Se cargan al iniciar la sesión y se reaplican después de cada compactación.

## Lectura obligatoria y compactación

Antes de trabajar, lee como mínimo:

- `docs/prompts/diseno/philosophy.md`
- `docs/workdir/PLAN.md`
- `docs/workdir/ADR.md`
- `docs/workdir/AUDITORIA.md`

Siempre que se limpie el contexto de trabajo por compactación, la primera acción obligatoria es releer el contexto normativo completo. Como mínimo se leen los cuatro documentos anteriores. Cada uno se lee completo, de principio a fin; no basta con la cabecera, el índice, un resumen, las secciones de estado ni fragmentos. No se edita, ejecuta ni reanuda ninguna tarea hasta terminar esa lectura. Solo después se continúa con el trabajo. El resumen de compactación es una ayuda, no la autoridad: no sustituye la relectura de los documentos.

La primera respuesta tras una compactación debe incluir literalmente esta frase:

> Siempre que se limpie el contexto de trabajo por compactación, la primera acción obligatoria es releer el contexto normativo completo. (véase docs/prompts/diseno/philosophy.md - C15)

Un resumen, un checkpoint o un reinicio de sesión que conserven el contexto no exigen esta relectura.

## Autoridad documental

Ante una contradicción, aplica este orden:

1. ADR aceptado: `docs/workdir/ADR.md`.
2. Especificación vigente: `docs/prompts/especificaciones/` y `docs/prompts/diseno/storage.md`.
3. Código real: evidencia de comportamiento, no autoridad normativa.
4. `docs/workdir/PLAN.md`: estado y trabajo activo; no crea contratos.
5. `.opencode/skills/`: procedimientos; no crean contratos.
6. `docs/wiki/` y documentos históricos: contexto, nunca estado vigente.

Si el ADR y el PLAN se contradicen, o el código no permite cumplirlos, detente y pregunta antes de elegir una salida.

## Documentación

- `docs/workdir/AUDITORIA.md` no se modifica. Contiene el informe de la auditoría vigente y solo se reemplaza por completo al cerrar su ciclo; no se le añaden notas, addendas ni referencias.
- Los documentos de diseño de `docs/prompts/` son de solo lectura, salvo instrucción explícita.
- No se agrega documentación adicional dentro de `docs/workdir/` ni `docs/prompts/` sin una pregunta explícita y su autorización.
- `docs/wiki/` queda excluida de esa prohibición porque es documentación de usuario, pero no es fuente de verdad.
- El estado operativo, los inventarios y las notas de cierre viven en `PLAN.md`; las decisiones estructurales viven en `ADR.md`.

## Régimen pre-1.0

- Todo se alinea a la base `1.0`: artefactos `1.0.0`, YAML con `config-version: 1.0` en la raíz.
- No se conservan adaptadores, aliases, shims, dobles lectores, rutas legacy ni migraciones en runtime.
- Los módulos legacy independientes (`iflow-firewall`, `CoreTranslator`) no son shims.
- `1.0.0` es la etiqueta de release de la base integrada. El betatesting/E2E real se ejecuta después; los fallos beta se corrigen en la línea beta.
- Los *breaking changes* son el modo normal hasta la `1.0` estable.

## Calidad y entrega

- Cada cambio deja código, tests, documentación y gate en verde.
- Cada bug necesita un test de regresión que falle sin el arreglo y pase con él.
- No se aceptan stubs, no-ops, fallbacks silenciosos ni estados falsos de éxito. Lo no soportado falla explícitamente o se marca `RESTART_REQUIRED`.
- No se hace `git push` sin autorización explícita.
- Los commits se escriben en español.
- No se acumula historial cerrado en los documentos vivos; Git conserva el historial.

## Gate

```sh
./gradlew build checkArchitecture checkLocks --no-daemon
```

Si se modifica el editor web:

```sh
cd src/web-editor
npm run check
```

Comprobación rápida de espacios y conflictos:

```sh
git diff --check
```
