# Documentación TextFormatter Suite

## Documentos vivos

Archivo | Propósito | Audiencia |
|---------|-----------|-----------|
| `workdir/PLAN.md` | Plan de ejecución: estado actual, fases completadas, próximos pasos, deuda técnica | Equipo / Mantenedores |
| `workdir/AUDITORIA.md` | Auditoría técnica externa (2026-10-09): hallazgos, puntuaciones, roadmap priorizado | Equipo / Auditores |
| `workdir/ADR.md` | Decisiones de arquitectura (fuente de verdad) | Arquitectos / Equipo |
| `RELEASE_NOTES.md` | Notas de versión por fase (FASE 0-17), breaking changes, comandos CI | Equipo / Auditores |

## Documentación usuario (`/docs/wiki/`)

<Falta mencionar rapidamente de que trata la introduccion + referencia>

## Material de referencia (`/docs/prompts/`)

**No son documentación de usuario.** Son fuentes de verdad que se consultan: contexto, diseños y contratos de formato. Los procedimientos (auditar, generar documentación) no viven aquí, sino como skills en `.opencode/skills/`.

| Directorio | Propósito |
|------------|-----------|
| `prompts/contexto/` | Contexto del proyecto: objetivos, reglas, módulos. |
| `prompts/diseno/` | Especificaciones de diseño y reingeniería de componentes (iflow, logger, Manager v2, Web Editor). |
| `prompts/especificaciones/` | Contratos de formato que valida el código: `commands.yml` v2, `extension.yml` v1.0, config schema v2.2. |

El criterio completo —qué es un prompt frente a una skill, cómo se nombran las carpetas y cuándo se elimina un documento— está en [`prompts/README.md`](prompts/README.md).

## Flujo de trabajo

```
prompts/diseno/manager-v2.md             →  implementación en src/manager-*
prompts/diseno/web-editor.md             →  implementación en src/web-editor/
prompts/especificaciones/commands-v2.md  →  implementación en src/host/config/CommandsConfig*
prompts/especificaciones/extension-v1.md →  implementación en src/extension-api/
skills auditar-*                         →  informes de auditoría (workdir/AUDITORIA.md)
skills generar-*                         →  README raíz y CodeGuide (src/README.md)
workdir/{PLAN,AUDITORIA,ADR}             →  derivan wiki/ y RELEASE_NOTES.md
```

### Regla de archivado

Cuando un prompt se implementa por completo y **no se va a reutilizar, se elimina**: no se mantiene como histórico (lo histórico va a `workdir/`). Una especificación de referencia se conserva aunque ya esté implementada, porque sigue siendo el contrato que la implementación debe cumplir — y contra el que las skills de auditoría la evalúan.

