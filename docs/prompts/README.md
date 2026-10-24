# Prompts de TextFormatter Suite

Material de referencia para trabajar en el repositorio: **fuentes de verdad que
se consultan**.

## Prompt frente a skill

Los dos conceptos se confunden porque ambos son texto que guía al agente. La
diferencia es qué se hace con ellos:

| | `docs/prompts/` (prompt) | `.opencode/skills/` (skill) |
|---|---|---|
| Qué es | material de **referencia**: se consulta | **procedimiento**: se ejecuta (inspeccionar → producir → validar) |
| Quién lo activa | tú, al aportarlo a la conversación | el agente, cuando la tarea encaja (`@id` para forzarlo) |
| Estructura | Markdown suelto | `SKILL.md` + `references/` |
| Efecto | informa | produce o modifica artefactos y verifica el resultado |

**Test:** ¿se puede ejecutar de principio a fin y verificar el resultado? Sí →
skill. No, es una fuente de verdad que se lee → prompt.

**Corolario:** si una skill *consume* un documento como especificación de
referencia, ese documento es un prompt. Por ejemplo, `@auditar-modulo` evalúa el
Manager contra `diseno/manager-v2.md`: la skill es el procedimiento, la
especificación es el prompt.

## Estructura

| Directorio | Contenido |
|---|---|
| `contexto/` | Contexto del proyecto para arrancar una sesión. |
| `diseno/` | Especificaciones de diseño y reingeniería de componentes. |
| `especificaciones/` | Contratos de formato (`yml`, JSON) que valida el código. |

```text
contexto/proyecto.md                     objetivos, reglas y módulos del proyecto
diseno/iflow-cpu-design.md               diseño del CPU virtual de iFlow
diseno/iflow-cpu-implement.md            especificación técnica de referencia v3 de iflow-vm (32 bits)
diseno/logger.md                         diseño del sistema de logging
diseno/manager-v2.md                     reingeniería del Module Manager (APT/dpkg)
diseno/web-editor.md                     diseño del Web Editor (F6)
especificaciones/commands-v2.md          contrato de commands.yml v2
especificaciones/extension-v1.md         contrato de extension.yml v1.0
especificaciones/config-schema-v2.2.md   config schema v2.2 del Web Editor
```

### Criterio de carpetas

El directorio indica el **propósito**, nunca el nombre del fichero:

- un directorio nuevo se justifica cuando hay (o se esperan) varios ficheros de
  esa naturaleza;
- un fichero va al directorio de su propósito, no a uno propio.

## Procedimientos (skills)

Los documentos que son procedimiento no viven aquí: son skills de proyecto en
`.opencode/skills/`, con `SKILL.md` (el procedimiento) y `references/` (el texto
detallado, que el agente lee solo si lo necesita).

| Skill | Procedimiento |
|---|---|
| `@auditar-proyecto` | Auditoría integral del proyecto contra el código real. |
| `@auditar-iflow` | Auditoría forense de iFlow (VM, ISA, memoria, compilador). |
| `@auditar-modulo` | Auditoría de un módulo contra su especificación de referencia. |
| `@generar-codeguide` | Genera o regenera la CodeGuide (`src/README.md` y módulos). |
| `@generar-readme` | Reestructura y mantiene el `/README.md` raíz. |

## Cuándo se elimina un prompt

Un prompt ya implementado que no se vaya a reutilizar **se elimina**; no se
mantiene como histórico. Lo histórico va a `docs/workdir/`.
