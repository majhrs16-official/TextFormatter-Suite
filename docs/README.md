# Documentación TextFormatter Suite

## Documentos vivos

Archivo | Propósito | Audiencia |
|---------|-----------|-----------|
| `workdir/PLAN.md` | Plan de ejecución: estado actual, fases completadas, próximos pasos, deuda técnica | Equipo / Mantenedores |
| `prompts/diseno/iflow-cpu-implement.md` | Especificación técnica de referencia v3 de iFlow VM (32 bits), derivada de la auditoría manual 2026-10-20: hallazgos `IFLOW-MAN-*`, propuestas, arquitectura objetivo ROM/BIOS/kernel/32 bits y ruta pendiente | Equipo / Arquitectos |
| `workdir/ADR.md` | Decisiones de arquitectura (fuente de verdad) | Arquitectos / Equipo |
| `workdir/AUDITORIA.md` | Auditoría vigente y hallazgos verificables | Equipo / Auditores |
| `../SECURITY.md` | Reporte privado de vulnerabilidades y alcance de seguridad | Usuarios / Auditores |
| `../CONTRIBUTING.md` | Entorno, build, tests, estilo de commits y Pull Requests | Contribuidores |

## Documentación usuario (`/docs/wiki/`)

La wiki cubre introducción, configuración, canales, traducción, iFlow (VM actual y nota histórica), sincronización, editor web, comandos, guía de desarrollo, API, Module Manager, notas de versión, migración, FAQ, ADR, contribución y glosario. Empieza por [`wiki/01-Introduction.md`](wiki/01-Introduction.md).

## Material de referencia (`/docs/prompts/`)

**No son documentación de usuario.** Son fuentes de verdad que se consultan: contexto, diseños y contratos de formato. Los procedimientos (auditar, generar documentación) no viven aquí, sino como skills en `.opencode/skills/`.

| Directorio | Propósito |
|------------|-----------|
| `prompts/contexto/` | Contexto del proyecto: objetivos, reglas, módulos y principios de ingeniería (philosophy). |
| `prompts/diseno/` | Especificaciones de diseño y reingeniería de componentes (storage, iflow, logger, Manager v2, chattranslator, metrics, Web Editor). |
| `prompts/especificaciones/` | Contratos de formato que valida el código: `commands.yml` v2, `extension.yml` v1.0, config schema v2.2. |

El criterio completo —qué es un prompt frente a una skill, cómo se nombran las carpetas y cuándo se elimina un documento— está en [`prompts/README.md`](prompts/README.md).

## Flujo de trabajo

```
prompts/diseno/manager-v2.md             →  implementación en src/manager-*
prompts/diseno/web-editor.md             →  implementación en src/web-editor/
prompts/especificaciones/commands-v2.md  →  implementación en src/core-api/api/command + src/suite-runtime/CommandRootConfig
prompts/especificaciones/extension-v1.md →  implementación en src/extension-api/
skills auditar-*                         →  auditorías contra la spec v3 (prompts/diseno/iflow-cpu-implement.md)
skills generar-*                         →  README raíz y CodeGuide (src/README.md)
workdir/{PLAN,ADR} + prompts/diseno      →  derivan la wiki y las notas de versión del tag
```

### Regla de archivado

Cuando un prompt se implementa por completo y **no se va a reutilizar, se elimina**: no se mantiene como histórico (lo histórico va a `workdir/`). Una especificación de referencia se conserva aunque ya esté implementada, porque sigue siendo el contrato que la implementación debe cumplir — y contra el que las skills de auditoría la evalúan.

