# Manifiesto de Diseño: La Filosofía de Pluralidad y Redundancia Funcional

> *"Si tienes opción a elegir, impleméntalas todas y déjaselo al usuario."*

## 1. El Conflicto de la Dualidad en Sistemas Tradicionales

¿Por qué existen dos módulos que hacen exactamente lo mismo —como `iFlow-VM` e `iFlow-WASM`, cuyo propósito fundamental es programar mensajes—?

En una suite monolítica tradicional, tener componentes redundantes compitiendo por la misma funcionalidad es un sinsentido conflictivo. Pero la diferencia aquí radica en la naturaleza de su creación: **uno está hecho por pura pasión artesanal; el otro por cruda eficiencia industrial.**

Forzar un monolito a elegir uno solo destruye la experimentación o castiga el rendimiento. La arquitectura tradicional sufre de parálisis cuando se debate entre la nostalgia de bajo nivel y la modernidad estandarizada.

## 2. El Manager como Arbitro de la Redundancia Pacífica

La razón de ser fundamental del **Manager** de módulos es precisamente resolver este dilema.

Sin un sistema de instalación remota, soportar motores opuestos para una misma tarea sería un infierno de dependencias y código espagueti. Con un ecosistema de plugins y módulos desacoplados:

* Podemos facilitar componentes redundantes sin que generen conflictos sistémicos.
* Es el usuario final o el administrador quien decide activamente qué filosofía instalar en su entorno según sus prioridades (nostalgia, velocidad, seguridad o ligereza).

## 3. La Regla de Oro: Derivación Paralela sobre Versiones Monolíticas (`v3`, `v4`...)

Bajo esta filosofía, si mañana surge una idea para un nuevo motor (por ejemplo, para el módulo *Text Formatter*), no se destruye lo anterior ni se crea una dolorosa versión **v3**.

En su lugar:

1. Se construye el nuevo motor **en paralelo** como un módulo independiente.
2. Se otorga soporte oficial a ambos (el veterano y el disruptivo).
3. Se asume el costo inicial de duplicar código a cambio de una libertad creativa absoluta.

Con el tiempo, la selección natural del software hará su trabajo: los módulos se bifurcarán de manera tan natural que evolucionarán en direcciones totalmente independientes e incompatibles entre sí, especializándose al máximo sin haber frenado jamás el ritmo de innovación del proyecto.

---

# Costumbres del Proyecto

Esta sección recoge las costumbres del proyecto, la filosofía de código y el razonamiento del "por qué" de las decisiones. No son especificaciones técnicas: son principios de comportamiento y razonamiento que guían cómo se trabaja y por qué se toman ciertas decisiones.

## C1. Romper para reestructurar, no parchear

Cuando la arquitectura actual no puede sostener el modelo deseado, no se empieza parcheando lo que hay. Se **rompe la suite, se desarma todo, se reestructura lo que debe ir y lo que no, y se vuelve a armar como debería haber sido desde el principio.**

No se aceptan implementaciones a medias, stubs, no-ops, adaptadores de compatibilidad sin fecha de eliminación ni parches que preserven el acoplamiento actual. Cada cambio debe llevar el camino viejo a su eliminación, no a su convivencia.

## C2. `force` significa forzar de verdad

El parámetro `force` no omite conflictos de forma silenciosa. Si un administrador usa `force`, es porque quiere forzar las cosas y sabe lo que hace. Esto incluye permitir la instalación de paquetes excluyentes si el administrador así lo decide, con la advertencia correspondiente. No se debe usar `force` como sinónimo de "ignorar problemas"; se usa como señal de intención deliberada del operador.

## C3. Las librerías compartidas son dependencias, no parte del host

Las librerías compartidas (SnakeYAML, JDA, Adventure, transporte y otras) **no deberían vivir en el host**. Son dependencias, paquetes aparte de tipo "dependencia". Es el manager quien se asegura de relocalizar y cargar dichas dependencias para cada paquete tipo módulo. No se deberían compilar dentro del JAR del host ni de los módulos.

## C4. Los metadatos autoritativos vienen del repositorio, no del JAR

No se incluye una lista de paquetes dentro del JAR. Eso es trabajo del manager: descargar del repositorio (en el directorio `repo`) el archivo `packages.list`, y a partir de ahí listar la URL de cada ZIP y por ende cada módulo. El manager nunca infiere paquetes mirando el contenido de un JAR; consulta el índice remoto.

## C5. Seguridad: el dueño del repositorio es responsable

El manager tipo apt descarga e instala código arbitrario. Esa es la idea intencional. La seguridad no se basa en sandboxear cada módulo, sino en que **el dueño del repositorio es responsable de lo que se publica en él.** Se sigue el mismo principio de un repositorio de Linux.

## C6. `library/status` es una BD de programas instalados, no de estado de ejecución

La presencia de un paquete ya implica que está instalado. Si está instalado, se inicia. Si el administrador apaga el módulo (incluso descargándolo de memoria), `library/status` no es el lugar para indicar el estado de ejecución. Esto es la base de datos de programas instalados, no de su estado. Si se requiere almacenar algo así (activo/fallido/requiere reinicio), debería ser en un directorio temporal dentro del directorio del manager, no en el estado de instalación.

## C7. `tester` es un módulo de producción

El módulo `tester` no es herramienta de desarrollo excluida del artefacto final. Es un módulo pensado para hacer benchmarking en tiempo de ejecución, además de probar toda la suite en todo el pipeline. Si algo está mal hecho, existe la alta probabilidad de que `tester` detecte el fallo, en qué punto, y cuál es la diferencia vs lo esperado. Para esto, `tester` tiene que simular el envío de un jugador, ir trazando la ejecución manualmente del mensaje, comprobar el resultado de cada etapa inspeccionando directamente el comportamiento del sistema, y lanzar un check si la comprobación de todo el pipeline es correcto. Es complejo, sí. Es potencialmente útil para fallos delicados, sí.

## C8. Extensiones sin catálogo cerrado

No existe un `enum` con todos los módulos posibles. Cualquier repositorio de paquetes de terceros puede otorgar paquetes custom, fuera de lo que hace esta suite. El sistema de descubrimiento debe estar abierto a paquetes arbitrarios declarados en índices remotos.

## C9. Versiones concurrentes al estilo Debian

A la hora de upgradear, se instalan versiones modernas remplazando al mismo paquete en su versión anterior, repitiendo el mismo proceso con todos los módulos y dependencias, resolviendo el grafo sin más. Esto es normal y es el camino de Debian en cuestión de apt y dpkg.

## C10. Las pruebas E2E en servidores reales están descartadas

Técnicamente la prueba del proyecto en Spigot fue nula: se hicieron pocas pruebas y no se considera útil contarlo como un punto a considerar. La decisión de alcance vigente descarta las pruebas reales/E2E. No se validará en servidores reales Spigot/Paper, Fabric ni proxies Velocity; la validación de plataformas se limita a compilación y tests automatizados que no requieran un servidor real.

## C11. No acumular histórico en documentos

Lo que ya está hecho, simplemente se borra del documento. El historial de git conserva todo lo necesario. No se debe acumular histórico cerrado en archivos de planificación o diseño; si ya está implementado, sale del documento activo.

---

# Principios de Diseño (por qué se hacen las cosas)

## D1. Pluralidad sobre monolito

Si tienes opción a elegir, impleméntalas todas y déjaselo al usuario. No se fuerza un monolito a elegir un solo motor. Cada motor, backend o implementación es un módulo independiente instalable y reemplazable. Los motores opuestos para una misma tarea pueden coexistir como paquetes independientes; el usuario elige cuál instalar.

## D2. El Manager como origen de la composición

El Manager de paquetes es quien actualiza índices, descarga artefactos, resuelve dependencias, instala, **inicializa en memoria** y gestiona el ciclo de vida completo de los módulos. Los adaptadores de plataforma (Spigot/Fabric) no son composition roots ni conocen la existencia de los módulos de feature. Solo implementan puertos de plataforma y traducen, en reversa, los eventos de su Minecraft a una representación interna agnóstica.

## D3. Instalar no es activar

Instalar un paquete no significa cargarlo como plugin o mod automáticamente. La gestión de paquetes debe permanecer independiente de Bukkit, Fabric y Forge. Los adaptadores de plataforma deben aportar únicamente las capacidades específicas que realmente necesite el runtime.

## D4. No hay doble camino

Cuando una funcionalidad se migra al runtime de módulos, su cableado compilado se elimina en el mismo cambio. No se mantienen dos formas de arrancar la suite ni dos mecanismos de descubrimiento de módulos.

## D5. Hot unload/upgrade real

El objetivo es descargar, instalar, activar y desactivar módulos en caliente, con liberación de recursos (listeners, tareas, hilos, sockets, referencias). Si una operación no puede completarse sin reinicio, se detecta y se comunica explícitamente. Nunca se finge éxito con no-ops, `return parent`, `return false` o métodos vacíos.

## D6. Código arbitrario, repositorio responsable

El manager instala código arbitrario. Esa es la idea. La confianza recae en el dueño del repositorio, no en sandboxes por módulo.

---

# Correcciones: lo que yo creía vs lo que me corregiste

Esta sección documenta las correcciones que me hiciste durante nuestra conversación, para que no se pierdan y queden como registro del razonamiento del proyecto.

| Lo que yo creía / propuse | Lo que me corrigiste | Lección |
|---|---|---|
| Proponer `suite-runtime` como composición root que agrega features | No: eso sigue compilando módulos dentro del host. La arquitectura correcta es que el Manager sea el orquestador y los adapters solo implementen puertos de plataforma | Los adapters no deben conocer módulos de feature |
| Empezar por el loader manteniendo la suite actual | No: hay que romper la suite, desarmar todo, reestructurar y volver a armar | No hay parches a medias |
| `force` debería omitir conflictos de forma segura | `force` significa forzar de verdad, incluyendo paquetes excluyentes | El admin sabe lo que hace |
| Librerías compartidas viviendo en el host | No deberían vivir en el host; son paquetes de dependencia gestionados por el manager | Separación clara de responsabilidades |
| Lista de paquetes dentro del JAR | Eso no se hace; es trabajo del manager descargar `packages.list` del repositorio | El repositorio manda, no el JAR |
| Sandboxing de módulos para seguridad | El dueño del repositorio es responsable de lo que publica | Principio tipo repositorio Linux |
| `library/status` debería registrar estado de ejecución (activo/fallido/reinicio) | No: es BD de programas instalados. El estado de ejecución va en un directorio temporal dentro del manager | Separación instalación vs ejecución |
| `tester` es herramienta de desarrollo o harness | `tester` es módulo de producción para benchmarking y trazado del pipeline | `tester` tiene valor en producción |
| Un `enum` cerrado de módulos para extensiones | No existe `enum` cerrado; repositorios de terceros pueden otorgar paquetes custom | Discovery abierto a índices remotos |
| Versiones concurrentes como conflicto complejo | Seguir el camino Debian apt/dpkg: reemplazar versión anterior por la moderna, resolver el grafo | Simplicidad sobre complejidad innecesaria |
| Probar en servidores reales como algo útil | Pruebas E2E en servidores reales están descartadas | Validación por compilación y tests automatizados |
| Acumular histórico cerrado en documentos | No acumular; git conserva el historial; lo hecho se borra del documento | Documento activo solo con lo vigente |
