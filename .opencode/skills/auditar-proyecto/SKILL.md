---
name: Auditar el proyecto
description: Auditoría técnica, arquitectónica y funcional integral de TextFormatter Suite completa. Produce hallazgos verificables con evidencia y separa lo comprobado de lo desconocido. Úsala cuando el usuario pida auditar el proyecto entero o hacer una auditoría global.
---

# Auditar el proyecto (integral)

## Cuándo usarla

- El usuario pide "audita el proyecto", una auditoría integral, global o del repositorio entero.
- No para un módulo concreto: para eso están `auditar-iflow` y `auditar-modulo`.

## Referencia obligatoria

`references/auditoria-integral.md` es el procedimiento completo, la estructura del
informe y la lista de comprobación. **Léelo entero antes de emitir conclusiones**;
este fichero solo lo sitúa en el flujo de trabajo del repositorio.

## Cómo

1. Trabaja sobre el clon local. La URL que aparece en la referencia sirve para
   auditorías externas: en ese caso clona en un directorio temporal y audita ahí.
2. Inspecciona código, tests, build, documentación y plan. Sigue el flujo de
   ejecución real: no deduzcas comportamiento de los nombres de clases, del
   README ni de la estructura de directorios.
3. Clasifica cada hallazgo (incumplimiento, error real, defecto arquitectónico,
   riesgo, limitación legítima) y **adjunta evidencia**: fichero y línea, comando
   ejecutado o test.
4. No confundas una funcionalidad ausente con un bug de una existente, ni lo
   comprobado con lo que sigue siendo desconocido.

## Salida

Informe final en el orden que fija la referencia, con una sección de cobertura de
auditoría. Se entrega en la conversación; si el usuario quiere versionarlo, va a
`docs/workdir/AUDITORIA-<tema>.md`.

## Reglas

- No modifiques el código ni los archivos del proyecto: una auditoría es un
  diagnóstico, salvo que el usuario pida además un plan de acción.
- No afirmes nada que no hayas comprobado.
