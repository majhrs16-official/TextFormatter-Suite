# commands.yml — contrato vigente (base 1.0)

> **Estado:** vigente. **Autoridad:** contrato de `commands.yml`. Si contradice un ADR aceptado,
> prevalece el ADR y este documento se corrige. **Ámbito:** raíz del árbol de comandos.
> **Obligatoriedad:** las reglas de «Reglas de validación» son normativas; los ejemplos son
> ilustrativos.
>
> El sufijo `v2` del nombre del archivo identifica la revisión del esquema, no una versión
> publicada de la suite. La base vigente es `1.0`.

`commands.yml` declara la **raíz** del árbol de comandos. No declara acciones ni
subcomandos: los comandos son código neutral en los paquetes y se registran por
`api.command`. La raíz es configuración; los módulos siempre usan rutas
relativas.

## Ubicación

- Ruta canónica única: `config/suite/commands.yml`.
- No se lee `commands.yml` en la raíz del data folder. Una ruta no canónica se
  ignora; no hay migración en runtime (ADR-034).
- `base-name`, `actions` y `commands` están retirados y se ignoran. La topología
  dinámica se retiró con el árbol de comandos legacy.
- Todo archivo declara `config-version: 1.0` en la raíz, preferentemente al final.

## Esquema

```yaml
# commands.yml — raíz del árbol de comandos
root: [suite]
aliases:
  - [txf]
config-version: 1.0
```

| Clave | Tipo | Obligatoria | Descripción |
|---|---|---|---|
| `root` | cadena o lista de cadenas | No | Segmentos del comando raíz. Si falta, se usa `[suite]`. |
| `aliases` | lista de cadenas o listas de cadenas | No | Raíces alternativas. Cada elemento puede ser una cadena (`txf`) o una lista de segmentos (`[sm, module]`). |

### Raíz multi-segmento

```yaml
root: [suite, module]
aliases:
  - [sm]
  - [txf, module]
config-version: 1.0
```

El adaptador de plataforma registra solo el primer segmento (`suite`). El
dispatcher neutral consume `module` antes de resolver el comando del módulo. Un
módulo que registra `demo echo` responde a:

```text
/suite module demo echo hola
/sm demo echo hola
/txf module demo echo hola
```

### Reglas de validación

- Cada segmento debe cumplir `[a-z0-9][a-z0-9_-]*`.
- `root` y cada alias deben tener al menos un segmento.
- Un alias no puede ser igual a `root`.
- Un alias no puede quedar tapado por la raíz. Con `root: [suite]`, un alias
  `[suite, module]` es ambiguo; con `root: [suite, module]`, un alias `[suite]`
  sí es válido porque la raíz no consume `module` en ese caso.
- No se permiten aliases duplicados.
- Un `commands.yml` inválido no reemplaza la raíz activa. El runtime registra el
  error y conserva la raíz anterior; al arrancar usa `/suite`.
- Si la plataforma ya tiene un comando con el primer segmento de la raíz, el
  registro falla explícitamente. No se roba un comando ajeno.

## Recarga en caliente

El comando built-in `reload` vuelve a leer `commands.yml`. Se invoca con la raíz
que está activa en ese momento (por defecto `/suite reload`):

```text
/suite reload
```

- Si la raíz no cambió: `UNCHANGED`.
- Si cambió y la plataforma puede desregistrar el comando anterior: se aplica y
  se re-registra la nueva raíz (`APPLIED`). Los comandos de los módulos no se
  tocan.
- Si la plataforma no puede desregistrar o restaurar la raíz: se informa
  `RESTART_REQUIRED`. No se simula un cambio exitoso.
- Si el archivo es inválido o la nueva raíz choca con un comando existente: se
  conserva la raíz anterior y se informa `FAILED`.

## Ejemplos

Mantener `/suite` y añadir `/tf`:

```yaml
root: [suite]
aliases: [tf]
config-version: 1.0
```

Mover todo el árbol a `/tf` sin alias:

```yaml
root: [tf]
config-version: 1.0
```

Mover el árbol a `/suite module` y conservar `/sm`:

```yaml
root: [suite, module]
aliases: [sm]
config-version: 1.0
```

## Contrato de implementación

- `api.command.CommandRoot` valida segmentos y aliases.
- `RuntimeCommandRegistry` resuelve paths relativos y consume el prefijo de la
  raíz.
- `CommandContext.root()` es la única fuente para help, usage y mensajes de
  error; ningún módulo concatena `/suite`.
- `SuiteRuntime` carga este archivo al arrancar y en `reloadCommandRoot()`.
- El adaptador de plataforma solo registra el comando raíz y delega en el
  registro neutral.
