# suite-runtime — runtime neutral de módulos

> **Purpose**: descubrir, resolver, activar y desactivar los módulos instalados
> y construir el pipeline del host sin importar features ni adaptadores de
> plataforma.

---

## 1. Responsibilities

- **Ciclo de vida** — `SuiteRuntime` resuelve el grafo con `kernel`, crea el
  `ModuleClassLoader` de cada paquete, ejecuta los `ModuleActivator` y publica
  los servicios en `RuntimeServiceRegistry`.
- **Host** — `ModuleHost` y `DefaultModuleContext` entregan a cada módulo su
  `PluginLogger`, su directorio `config/<module>/`, su `ServiceRegistry` y sus
  recursos.
- **Paquetes** — `PackageRuntime` conecta el Manager (`manager-core`/`manager-apt`)
  con la activación; aplica los cambios de paquete y conserva el estado.
- **Comandos** — `SuiteCommands`, `RuntimeCommandRegistry`, `CommandRootConfig` y
  `RootCommand` construyen el árbol raíz desde `config/suite/commands.yml`.
- **Configuración** — `DefaultConfigInstaller` instala los defaults que falten y
  `RuntimeConfigRegistry` expone la configuración resuelta. `YamlUserLanguageStore`
  persiste el idioma por UUID en `<data>/storage.yml`.
- **Estado y eventos** — `RuntimeStateStore`, `RuntimeEventBus` y
  `RuntimeScheduler` sostienen el estado de ejecución y la publicación de eventos
  de ciclo de vida.

## 2. Non-responsibilities

- No contiene listeners de Bukkit/Fabric/Velocity: eso pertenece a
  `spigot-host`, `fabric-host` y `sync-velocity`.
- No implementa features de texto, traducción, iFlow ni sync.
- No descarga paquetes: delega en `manager-apt`/`manager-core`.
- No define la configuración de cada módulo: solo entrega su directorio.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api`, `core-render` | Compile | Contratos neutrales y puertos de render |
| `host`, `kernel` | Compile | Pipeline y resolución del grafo |
| `manager-spi`, `manager-core`, `manager-apt` | Compile | Instalación y estado de paquetes |
| `snakeyaml` | Compile | `commands.yml` y `storage.yml` |
| Features (`textformatter`, `chattranslator`, `iflow-*`, `sync-*`, `observability`, `presets`, `tester`, `inworld`) | Test only | Fixtures de activación; no están en producción |

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Construye el runtime en `onEnable` y registra comandos/listeners |
| `fabric-host` | Construye el runtime en el inicializador del mod |
| `manager-apt` | Solicita instalación/actualización/eliminación a través de `PackageRuntime` |

## 5. Main Components

| Component | Role |
|-----------|------|
| `SuiteRuntime` | Fachada de arranque, recarga, activación y parada |
| `ModuleHost` / `DefaultModuleContext` | Contexto neutral entregado a cada módulo |
| `ModuleClassLoader` | Carga aislada de los JARs de módulo |
| `PackageRuntime` | Aplica cambios de paquete y activa/desactiva módulos |
| `SuiteCommands` / `RuntimeCommandRegistry` | Árbol de comandos y comandos raíz |
| `DefaultConfigInstaller` | Instala defaults en `config/<module>/` |
| `YamlUserLanguageStore` | Persistencia de idioma por UUID (`storage.yml`) |

## 6. Configuración

El layout canónico está en `docs/prompts/diseno/storage.md`. El runtime lee
`config/suite/` para el host y entrega `config/<module>/` a cada módulo. No
existe un lector de la raíz plana ni de `config/manager/`.

`DefaultConfigInstaller` solo escribe defaults canónicos ausentes; no migra ni
lee archivos de la raíz plana. `SuiteRuntime.resolveRouter()` exige el hub
`iflow` y no acepta un `Router` publicado directamente por un motor.

## 7. Verification

```bash
./gradlew :src:suite-runtime:test --no-daemon
```
