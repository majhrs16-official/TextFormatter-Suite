Necesito implementar un sistema de logging general y de ultra bajo nivel ubicado en `/src/logger` para todo el ecosistema de plugins. Este módulo gestionará el runtime (`info`, `warn`, `error`, escritura en consola o en archivos como `<servidor>/plugins/<manager>/suite.log` y `/tmp/TextFormatter-Suite.log`), y además incluirá un mecanismo de depuración optimizado para cero overhead.

---

### 1. Requisitos de Arquitectura y Cero Overhead (Debug Estático)
- **Clase de Configuración Estática:** Debe existir una clase central en el logger (ej. `Logger` o `DebugConfig`) que contenga una constante booleana estática modificada por la compilación:
  `public static final boolean DEBUG = /* inyectado/modificado por flag de Gradle */;`
- **Cero Overhead por JIT del JDK:** Las llamadas al depurador en los módulos deben realizarse mediante una función estática limpia, por ejemplo:
  `Logger.debug("formato", ...parámetros);`
- Internamente, o envuelto por el usuario mediante un bloque condicional, el JDK debe ser capaz de detectar en tiempo de compilación si `DEBUG` es `false` para eliminar por completo el bytecode del bloque (idéntico a una macro de C).
- **Control por Gradle:** Un flag en los archivos de propiedades de Gradle modificará esta propiedad de clase al compilar. **Queda estrictamente prohibido usar `System.getProperty(...)` para el control del debug.**

---

### 2. Gestión de Logs en Runtime
- El módulo se encargará de los niveles estándar: `info`, `warn`, `error`.
- Los logs deben escribirse dinámicamente según la configuración del servidor:
  - Salida estándar (`System.out.println`).
  - Archivo principal del plugin: `<servidor>/plugins/<manager>/suite.log`.
  - Archivo de respaldo temporal: `/tmp/TextFormatter-Suite.log`.
- Cada módulo mantendrá su propio formato distintivo al registrar sus eventos (por ejemplo, `iflow-vm` volcando el estado de la VM por ciclo de instrucción, y otros módulos como GTranslate registrando pasos de red, parsing de URL, etc.).

---

### Tareas a realizar:
1. Crear el módulo unificado en `/src/logger` que soporte la gestión general de logs (`info`, `warn`, `error`) y los destinos de archivos/consola.
2. Definir la propiedad booleana `final static` controlada por la compilación de Gradle (sin `System.getProperty`).
3. Implementar el método estático `Logger.debug(...)` listo para ser consumido por los diferentes módulos (empezando por `iflow-vm`) garantizando la eliminación de código muerto por parte del compilador Java.
