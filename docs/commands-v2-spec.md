# TextFormatter Suite — Dynamic Command System (commands.yml v2)

## Config Example

```yaml
# commands.yml — Topología dinámica de comandos /suite

# Nombre base del comando (renombrable: cht/dst/txf/tg/if)
base-name: "suite"
aliases: ["suite", "txf", "textformat"]

# Definición de acciones atómicas reutilizables
actions:
  # Acción: recargar configuración
  reload:
    description: "Recarga toda la configuración desde disco"
    permission: "textformattersuite.admin"
    execute: |
      reloadSuite()
      sender.sendMessage("§a[suite] Recargado: {channels} canales, traductor '{translator}'")

  # Acción: mostrar estado
  status:
    description: "Muestra estado del plugin"
    permission: "textformattersuite.user"
    execute: |
      sender.sendMessage("§a[suite] Canales: {channels}")
      sender.sendMessage("§a[suite] Traductor: {translator}")
      sender.sendMessage("§a[suite] Engine: parallel={engineParallel} sonido={soundEnabled} claim={claimMode}")

  # Acción: configurar idioma (propio u otro jugador)
  lang:
    description: "Configura idioma de traducción"
    permission: "textformattersuite.user"
    admin-permission: "textformattersuite.admin"
    args:
      - name: "target"
        type: "player?"
        description: "Jugador objetivo (omitir = tú)"
      - name: "value"
        type: "enum(auto,off,<code>)"
        description: "auto | off | código de idioma (es, en, zh-CN, etc.)"
    execute: |
      target = target ?: sender
      if target != sender && !sender.hasPermission(admin-permission):
        sender.sendMessage("§cPermiso de admin requerido")
        return
      current.languages.save(target.uuid, value)
      sender.sendMessage("§a[suite] Idioma de {target} actualizado a {value}")

  # Acción: alternar traducción on/off
  toggle:
    description: "Alterna traducción entre auto y off"
    permission: "textformattersuite.user"
    admin-permission: "textformattersuite.admin"
    args:
      - name: "target"
        type: "player?"
        description: "Jugador objetivo (omitir = tú)"
    execute: |
      target = target ?: sender
      if target != sender && !sender.hasPermission(admin-permission):
        sender.sendMessage("§cPermiso de admin requerido")
        return
      current = current.languages.languageOf(target.uuid).orElse("auto")
      flipped = current == "off" ? "auto" : "off"
      current.languages.save(target.uuid, flipped)
      sender.sendMessage("§a[suite] Traducción de {target}: {flipped}")

  # Acción: resetear configuración
  reset:
    description: "Restaura configs por defecto (con backup)"
    permission: "textformattersuite.admin"
    confirm: true
    execute: |
      if resetConfigs():
        reloadSuite()
        sender.sendMessage("§a[suite] Configs restauradas (backup en backup/)")
      else:
        sender.sendMessage("§c[suite] Error: ver log")

  # Acción: test suite
  test:
    description: "Ejecuta tests automatizados"
    permission: "textformattersuite.admin"
    args:
      - name: "type"
        type: "enum(full,stress,concurrency,routing,events,perf,sync)"
        default: "full"
      - name: "args..."
        type: "string*"
        description: "Parámetros adicionales según tipo"
    execute: |
      # Delegar a TestService existente

  # Acción: editar config.yml estilo LuckPerms
  edit:
    description: "Edita un valor en config.yml"
    permission: "textformattersuite.admin"
    args:
      - name: "path"
        type: "string"
        description: "Ruta YAML (ej: general.language, chat.claim-mode)"
      - name: "value"
        type: "string"
        description: "Nuevo valor"
    execute: |
      config.set(path, value)
      config.save()
      sender.sendMessage("§a[suite] {path} = {value}")
      reloadSuite()  # hot-reload

  # Acción: ver valor actual
  get:
    description: "Muestra valor actual de una clave"
    permission: "textformattersuite.user"
    args:
      - name: "path"
        type: "string"
        description: "Ruta YAML"
    execute: |
      val = config.get(path)
      sender.sendMessage("§a[suite] {path} = {val}")

# Estructura de comandos (árbol)
commands:
  suite:
    description: "Comando principal TextFormatter Suite"
    permission: "textformattersuite.user"
    default: status  # subcomando por defecto sin args
    children:
      reload:
        ref: reload
      status:
        ref: status
      lang:
        ref: lang
        children:
          auto:
            ref: lang
            fixed-args: ["auto"]
          off:
            ref: lang
            fixed-args: ["off"]
          <code>:
            ref: lang
            arg-binding: "value"
      toggle:
        ref: toggle
        children:
          <player>:
            ref: toggle
            arg-binding: "target"
      reset:
        ref: reset
      test:
        ref: test
        children:
          full:
            ref: test
            fixed-args: ["full"]
          stress:
            ref: test
            arg-binding: "type"
            fixed-args: ["stress"]
          concurrency:
            ref: test
            arg-binding: "type"
            fixed-args: ["concurrency"]
      edit:
        ref: edit
      get:
        ref: get
```