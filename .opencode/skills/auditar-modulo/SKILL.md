---
name: Auditar un módulo
description: Auditoría técnica de un módulo del proyecto contra su especificación de referencia, sin modificar la implementación. Evalúa cumplimiento, errores reales, defectos arquitectónicos, riesgos y decisiones correctas. Úsala cuando el usuario pida auditar un módulo concreto.
---

# Auditar un módulo

## Cuándo usarla

- El usuario pide auditar un módulo concreto (por ejemplo, el Module Manager),
  contra la especificación con la que se diseñó.
- Para el proyecto completo o para iFlow, usa `auditar-proyecto` o `auditar-iflow`.

## Focos disponibles

Los focos y el método por módulo viven en `references/`:

| Módulo | Referencia | Especificación contra la que se evalúa |
|---|---|---|
| Module Manager | `references/manager.md` | `docs/prompts/diseno/manager-v2.md` |

Para auditar otro módulo, añade `references/<modulo>.md` con sus focos y su
especificación. No crees un directorio nuevo: `references/` es el sitio.

## Cómo

1. Lee la referencia del módulo **y su especificación completa** antes de juzgar.
   La especificación es el contrato funcional y arquitectónico; la referencia
   aporta los focos, el método y la estructura del informe.
   *(La referencia asume la especificación "a continuación"; hoy vive en el
   fichero aparte que indica la tabla.)*
2. Evalúa la implementación existente. **No refactorices, no modifiques archivos
   y no conviertas la auditoría en un plan de implementación.**
3. Separa con cuidado: incumplimientos explícitos, errores reales, defectos
   arquitectónicos, riesgos de seguridad e integridad, limitaciones legítimas de
   la arquitectura y decisiones que conviene conservar.
4. No confundas una funcionalidad que todavía no existe con un bug de una que sí
   existe: ambas pueden incumplir la especificación, pero se clasifican aparte.

## Salida

Diagnóstico fundamentado en el código real, con veredicto por área y evidencia
(fichero y línea). Se entrega en la conversación; si el usuario quiere
versionarlo, va a `docs/workdir/AUDITORIA-<tema>.md`.
