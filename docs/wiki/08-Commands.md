# Commands Reference

## Raíz configurable

El árbol de comandos tiene una raíz configurable en
`config/suite/commands.yml`. Por defecto es `/suite` con el alias `/txf`:

```yaml
root: [suite]
aliases:
  - [txf]
```

La raíz puede tener varios segmentos y los aliases pueden ser cadenas o listas:

```yaml
root: [suite, module]
aliases:
  - [sm]
  - [txf, module]
```

Los módulos registran rutas **relativas**; cambiar la raíz no requiere
recompilarlos. El esquema completo, la migración de `base-name` y las reglas de
colisión están en
[`docs/prompts/especificaciones/commands-v2.md`](../prompts/especificaciones/commands-v2.md).

### Recarga de la raíz

```text
/suite reload
```

Se invoca con la raíz activa en ese momento. Si la plataforma puede
desregistrar el comando anterior, la nueva raíz se aplica en caliente. Si no,
el runtime responde `RESTART_REQUIRED`; no simula un cambio exitoso.

## Comandos built-in

Todos los comandos built-in requieren el permiso
`textformatter.suite.admin` (la consola lo tiene por defecto).

| Comando | Descripción |
|---|---|
| `/<raíz> help` | Muestra la ayuda del árbol activo. |
| `/<raíz> reload` | Recarga `commands.yml` y re-registra la raíz. |
| `/<raíz> list` | Lista los paquetes instalados y su estado. |
| `/<raíz> state` | Muestra el estado runtime de los módulos conocidos. |
| `/<raíz> install <zip>` | Instala y activa un ZIP local. La ruta puede ser absoluta o relativa al data folder. |
| `/<raíz> remove <pkg> [version] [canal]` | Desactiva y elimina un paquete. |
| `/<raíz> upgrade` | Actualiza todos los paquetes. |
| `/<raíz> repo install <pkg...>` | Instala paquetes desde los repositorios configurados. |
| `/<raíz> repo remove <pkg> [version] [canal]` | Elimina un paquete desde los repositorios. |
| `/<raíz> repo upgrade` | Actualiza paquetes desde los repositorios. |
| `/<raíz> cache clean` | Limpia descargas, archivos `.part` y manifiestos de operaciones de la caché; no toca paquetes instalados, `library/status`, journal ni trash. |

Ejemplos con la raíz por defecto:

```text
/suite help
/suite list
/suite state
/suite install paquetes/textformatter-1.0.0.zip
/suite remove textformatter
/suite repo install textformatter messages
/suite reload
```

## Comandos de módulos

Los paquetes registran sus comandos con `api.command` desde el ciclo de vida del
módulo (`ModuleContext.commands()`). No hay un intérprete de acciones en
`commands.yml` y los módulos no deben hardcodear `/suite`: usan rutas relativas
y `CommandContext.root()` para help, usage y errores.

## Permisos

| Permiso | Uso |
|---|---|
| `textformatter.suite.admin` | Comandos built-in de administración, instalación y recarga. |

Los módulos pueden declarar permisos propios en cada `CommandSpec`; el runtime
valida el permiso de cada nodo del path y oculta de la tab completion los
subárboles no permitidos.

## Tab completion

La tab completion se resuelve en el registro neutral:

```text
/suite <TAB>                 # subcomandos built-in
/suite repo <TAB>            # install, remove, upgrade
/suite install <TAB>         # sin sugerencias de archivo en el core
/suite <comando> <TAB>       # argumentos y subcomandos del módulo
```

Los aliases de comandos de módulo también aparecen en las sugerencias cuando el
actor tiene permiso.
