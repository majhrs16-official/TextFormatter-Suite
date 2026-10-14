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

## Especificaciones internas (`/docs/prompts/`)

**No son documentación de usuario.** Son specs, prompts de trabajo y contexto para desarrollo.

| Directorio/Archivo | Propósito |
|--------------------|-----------|
| `prompts/project.md` | Prompt primario contextual sobre objetivos primarios, reglas de proyecto, módulos principales. |
| `prompts/codeguide/` | Prompt de generacion de una guía sobre el código |
| `prompts/readme/` | Prompt de generacion de un README actualizado |
| `prompts/commands/` | Spec del sistema de comandos dinámico v2 (`/suite`) |
| `prompts/extension/` | Schema sobre el modulo para soporte de exntesiones nativas |
| `prompts/manager/` | Prompt de generacion para el modulo Manager v2 (implementación actual) |
| `prompts/web-editor/` | Prompt de generacion de la arquitectura + schema del Web Editor |
| `prompts/auditoria/project.md` | Prompts con instrucciones detalladas para realizar auditorías externas |
| `prompts/auditoria/modules/` | Focos específicos de auditoría por módulo |

## Flujo de trabajo

```
prompts/manager/manager.md   →  implementación en src/manager-*
prompts/web-editor/*         →  implementación en src/web-editor/
prompts/commands/*           →  implementación en src/host/config/CommandsConfig*
prompts/extension/*          →  implementación en src/extension-api/
prompts/auditoria/*          →  genera AUDITORIA.md en workdir/
workdir/{PLAN,AUDITORIA,ADR} →  derivan wiki/ y RELEASE_NOTES.md
```

### Regla de archivado

Cuando un prompt en `prompts/<domain>/` se implementa completamente → mover a `prompts/already/` solo si es reutilizable en un futuro, de lo contrario es preferible eliminar el prompt

**Ejemplo actual**: `prompts/manager/manager.md` sigue activo (v2 en desarrollo). Cuando v2 esté completo → Eliminar.
