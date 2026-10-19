---
name: Generar la CodeGuide
description: Genera o regenera la CodeGuide del proyecto (src/README.md y READMEs por módulo) a partir del estado real del código, sin modificar el código. Úsala cuando el usuario pida crear o actualizar la documentación de navegación del código.
---

# Generar la CodeGuide

## Cuándo usarla

- El usuario pide crear, regenerar o actualizar la CodeGuide, el mapa del código,
  `src/README.md` o los READMEs de módulo.
- Es la herramienta de mantenimiento de la documentación **derivada del código**:
  re-ejecutable cada vez que el código cambia.

## Referencia obligatoria

`references/especificacion-codeguide.md` define el contenido, la estructura de
secciones y la validación final. **Léela entera**: es a la vez la especificación
del documento que se produce y la lista de comprobación del trabajo.

## Cómo

1. Inspecciona el repositorio real antes de escribir. El código es la fuente de
   verdad: no documentes una arquitectura idealizada ni te fíes de los nombres de
   paquetes, clases o del README existente.
2. Escribe `src/README.md` como mapa del código y, solo cuando aporte valor real,
   READMEs por módulo. No crees READMEs para cumplir un cupo.
3. Ejecuta la validación final de la referencia (enlaces, discrepancias
   código↔documento, formato, nivel de detalle) y corrige lo que falle.

## Entrega

Los archivos escritos en el repositorio más un informe breve: creados,
modificados y movidos; discrepancias detectadas; validaciones ejecutadas y su
resultado; y lo que no pudo verificarse.

## Reglas

- **No modifiques el código.** Si detectas una discrepancia, se documenta; no se
  "arregla" el código para que cuadre con la documentación.
- No dupliques la presentación pública del proyecto: eso es `generar-readme`.
