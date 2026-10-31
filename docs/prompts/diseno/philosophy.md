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

Estas son las costumbres de código y de diseño que guían el trabajo diario. Las especificaciones técnicas de cada una viven en el PLAN o en los ADR correspondientes.

## C1. Documento vivo

El PLAN, `docs/workdir/ADR.md` y los documentos de diseño son documentos vivos: se reescriben, podan y actualizan constantemente. `docs/workdir/ADR.md` es el registro vivo de decisiones de arquitectura y se actualiza en el mismo cambio que introduce una decisión estructural; no se trata como un archivo histórico. El PLAN resume el estado, las fases y el detalle operativo. Solo lo vigente permanece en el documento activo. El historial de git conserva todo lo necesario; no se acumula histórico cerrado en los documentos activos.

## C2. Romper para reestructurar

Cuando la arquitectura actual no puede sostener el modelo deseado, no se parchea lo que hay. Se rompe la suite, se desarma todo, se reestructura lo que debe ir y lo que no, y se vuelve a armar como debería haber sido desde el principio. No se aceptan implementaciones a medias, stubs, no-ops ni parches que preserven el acoplamiento actual.

## C3. Tests obligatorios por fase

Cada fase de trabajo termina con código, tests, documentación y eliminación del camino viejo. Una fase no se cierra porque compile; se cierra porque tiene su puerta verde.

## C4. Cada bug tiene su test

Cada bug debe tener un test que demuestre que está resuelto. No se considera cerrado un bug sin su prueba de regresión.

## C5. No stubs silenciosos

No se devuelve `parent`, `false` ni vacío fingiendo éxito. Lo no soportado lanza un error explícito o se marca como `RESTART_REQUIRED`.

## C6. Pluralidad sobre monolito

Si tienes opción a elegir, impleméntalas todas y déjaselo al usuario. No se fuerza un monolito a elegir un solo motor. Cada motor, backend o implementación es un módulo independiente instalable y reemplazable. Los motores opuestos para una misma tarea pueden coexistir como paquetes independientes; el usuario elige cuál instalar.

## C7. Cambios con retrocompatibilidad ideal

Idealmente se mantiene retrocompatibilidad. Cuando no es posible, se rompe a propósito. Cada fase debe tener puertas de calidad, planes 100% completados y tests en verde. Está prohibido dejar código a medias; si una fase no se completa, se tiene que terminar (a menos que haya una razón explícita para lo contrario).

## C8. Pruebas E2E descartadas

No se validará en servidores reales Spigot/Paper, Fabric ni proxies Velocity. La validación de plataformas se limita a compilación y tests automatizados que no requieran un servidor real.

## C9. Seguridad compartida: sandbox + dueño del repo

Hay sandboxing por módulo, pero el punto central no es el sandbox: **el dueño del repositorio es responsable de lo que se publica en él**. La seguridad se basa tanto en el sandbox como en la confianza en el repositorio. El manager verifica integridad (SHA-256) de lo que descarga; el operador decide qué repositorios añade. Firmas y allowlists son defensa en profundidad opcional, no el fundamento de la seguridad.

## C10. `force` significa forzar de verdad

El parámetro `force` no omite conflictos de forma silenciosa. Si un administrador usa `force`, es porque quiere forzar las cosas y sabe lo que hace. Esto incluye permitir la instalación de paquetes excluyentes si el administrador así lo decide, con la advertencia correspondiente.

## C11. `library/status` solo para instalados

La presencia de un paquete en `library/status` implica que está instalado. El estado de ejecución (encendido/apagado, fallido, requiere reinicio) no pertenece a `library/status`; si es necesario rastrearlo, se hace en un directorio temporal dentro del directorio del manager, nunca en el estado de instalación.

## C12. Organización por capa técnica

El directorio `src/` se organiza por capa técnica: `api`, `kernel`, `runtime`, `platform` y `packages`. `platform` contiene cada plataforma de Minecraft soportada; `kernel` contiene cada feature agnóstico, base, primario o importante para la arquitectura del sistema; `packages` contiene cada módulo independiente entre sí.

## C13. Hipótesis con evidencia, no supuestos cómodos

Una hipótesis no es una decisión. Frases como «probablemente solo haya uno», «no merece la pena», «debería ser suficiente» o «es un caso marginal» no cierran una fase ni justifican dejar una capacidad a medias. Antes de aceptar una hipótesis hay que citar la evidencia verificable: una especificación, un invariante validado, una prueba o el código real que lo demuestra. Si no hay evidencia, el estado correcto es **desconocido**, no **verdadero**.

No se simplifica un problema solo porque la reducción parezca obvia. Que algo «probablemente» sea pequeño, único o poco frecuente no autoriza a recortar la implementación ni a dejar una rama sin cubrir. El ADR y el PLAN se siguen al pie de la letra; si el código real no permite cumplirlos, o si aparece un riesgo de inconsistencia entre lo documentado y lo implementado, se detiene el trabajo y se pregunta antes de elegir una salida. La pregunta es parte del proceso, no una pérdida de tiempo.

Toda desviación respecto al ADR o al PLAN requiere justificación explícita y aprobación antes de implementarse. Si una decisión documentada resulta técnicamente inviable, incompleta o incompatible con el código real, se registra la discrepancia, se presentan las alternativas y sus consecuencias, y se espera autorización. Nunca se adapta silenciosamente el alcance, la arquitectura o las garantías para hacer que una implementación parezca terminada.

Esto obliga a dos disciplinas concretas:

1. **Paridad de capacidades.** Si una ruta admite una cardinalidad, una variante o un estado, las rutas que la sustituyen deben admitir lo mismo o rechazarlo explícitamente. No se puede activar varios JAR de un paquete y luego actualizar solo el primero; no se puede aceptar una dependencia al instalar y descartarla al actualizar. La asimetría silenciosa es un bug de arquitectura.
2. **Invariantes validados o soporte completo.** Cuando aparece una asimetría, se elige una de dos salidas: implementar la capacidad completa en todas las rutas, o declarar un invariante formal, validarlo en la frontera y fallar con un mensaje explícito cuando se incumpla. Lo que no se permite es asumir que la entrada siempre cumplirá el invariante sin comprobarlo.

La misma regla se aplica al estado de recuperación. Un rollback no es exitoso porque el código lo diga: hay que verificar que cada versión anterior quedó activa y que cada versión nueva quedó fuera. Si la compensación falla, no se finge éxito ni se borran artefactos que aún pueden hacer falta: se conservan ambos lados y se marca `RESTART_REQUIRED` con una causa verificable. Los estados `FAILED`, `RESTART_REQUIRED` y «desconocido» existen para decir la verdad, no para decorar logs.

---

# Principios de Diseño del Proyecto

Estos son los principios abstractos que guían el proyecto. Las especificaciones técnicas de cada uno viven en el PLAN o en los ADR correspondientes.

## D1. Pluralidad sobre monolito

Si tienes opción a elegir, impleméntalas todas y déjaselo al usuario. No se fuerza un monolito a elegir un solo motor. Cada motor, backend o implementación es un módulo independiente instalable y reemplazable. Los motores opuestos para una misma tarea pueden coexistir como paquetes independientes; el usuario elige cuál instalar.

## D6. Código arbitrario, repositorio responsable

El manager instala código arbitrario. Esa es la idea. Hay sandboxing por módulo, pero el punto central no es el sandbox: **el dueño del repositorio es responsable de lo que se publica en él**. La seguridad se basa tanto en el sandbox como en la confianza en el repositorio. Ver la costumbre C9.
