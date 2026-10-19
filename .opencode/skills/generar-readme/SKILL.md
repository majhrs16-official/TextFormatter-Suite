---
name: Generar el README
description: Reestructura y mantiene el /README.md raíz como presentación pública del proyecto, con estados de verificación honestos y enlaces válidos. Úsala cuando el usuario pida reescribir, reorganizar o actualizar el README principal.
---

# Generar el README principal

## Cuándo usarla

- El usuario pide reescribir, reorganizar, actualizar o "hacer la v2" del
  `/README.md` raíz.
- No para la documentación técnica: `src/README.md` y los READMEs de módulo los
  mantiene `generar-codeguide`.

## Referencia obligatoria

`references/especificacion-readme.md` define la filosofía editorial, la
responsabilidad de cada documento, las secciones, los criterios de aceptación y
la validación obligatoria. **Léela entera** y respétala: es la especificación del
README raíz.

> Aviso: la referencia es anterior a la reorganización de `docs/`. Donde dice
> `docs/PLAN.md` hoy es `docs/workdir/PLAN.md`.

## Cómo

1. Inspecciona antes de escribir: funcionalidades reales, cómo se verificaron
   (tests, build, probado en servidor) y la fuente que respalda cada afirmación.
2. Reescribe `/README.md` como página principal: qué es el proyecto, para qué
   sirve, en qué estado está y cómo empezar a explorarlo o instalarlo.
3. Mantén intactas la responsabilidad de `/src/README.md` y su enlace desde la raíz.
4. Ejecuta la validación obligatoria y los criterios de aceptación de la
   referencia: advertencia de desarrollo visible, enlaces y rutas relativas,
   comandos de Gradle que existan de verdad, y diff acotado al objetivo documental.

## Entrega

El diff documental en el repositorio más un informe verificable: archivos creados,
modificados y movidos; responsabilidad final de cada documento; verificaciones
ejecutadas; y lo que no pudo comprobarse.

## Reglas

- No inventes funcionalidades, resultados de pruebas, compatibilidades,
  instrucciones de instalación ni garantías.
- Si algo no se puede verificar, se dice; no se presenta como un hecho.
