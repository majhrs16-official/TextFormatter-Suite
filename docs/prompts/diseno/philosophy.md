# Manifiesto de Diseño: La Filosofía de Pluralidad y Redundancia Funcional

> *"Si tienes opción a elegir, impleméntalas todas y déjaselo al usuario."*

## 0. Régimen pre-1.0: una sola versión, cero compatibilidad prematura

TextFormatter Suite está en fase de diseño fundacional. No existe todavía una versión estable, no hay
producción y no se promete compatibilidad. Todo el proyecto se identifica con la base **1.0**:
módulos, componentes, contratos, artefactos y esquemas de configuración. Los números `2.x`, `3.x` o
superiores que aparezcan en el historial son etiquetas de iteración, no versiones publicadas ni
promesas de compatibilidad.

La versión oficial **1.0.0** es la etiqueta de release de la base integrada, no un número de build.
Se alcanza cuando la suite completa, la integración de todos los módulos y todas las features están
terminadas. Una vez publicada esa base, comienza el betatesting/E2E en servidores reales; los fallos
del beta se corrigen en la propia línea beta, no en la 1.0. La versión **1.0** se declara estable
solo cuando el proyecto completo demuestra ser estable, está consolidado y no tiene errores críticos
de diseño. «Versión oficial» significa públicamente descargable: una release puede ser oficial y
seguir en beta. Hasta la 1.0 el proyecto opera en régimen de *rolling release*: los *breaking
changes* están permitidos y son deseables para limpiar la deuda técnica antes de congelar la
arquitectura.

### Reglas de oro pre-1.0

1. **Diseño limpio antes que compatibilidad.** Se prioriza la arquitectura hexagonal inversa, la
   separación estricta entre frontend y backends, el tipado binario estricto en las fronteras y el
   bajo nivel predecible. La compatibilidad con versiones anteriores no es un objetivo de esta fase.
2. **Una sola base de versión.** Todo módulo, componente, contrato y configuración se alinea a
   `1.0` (`1.0.0` para artefactos y `config-version: 1.0` para YAML). No se mantienen ramas de
   versión ficticias ni matrices de compatibilidad.
3. **Cero compatibilidad prematura.** En pre-release no se conservan adaptadores, alias, shims,
   dobles lectores, rutas legacy ni capas de compatibilidad que existan solo para no romper
   instalaciones anteriores. La compatibilidad es una decisión de producción, no una deuda de
   pre-release. La pluralidad de módulos no es compatibilidad: si un feature se conserva, se
   implementa como módulo independiente.
4. **El camino viejo no se migra en runtime.** Si una instalación contiene rutas o formatos que ya
   no son canónicos, se ignoran o se rechazan explícitamente; no se leen como fallback ni se
   convierten silenciosamente.
5. **Cada cambio deja el gate verde.** Código, tests, documentación y eliminación del camino viejo
   forman una sola unidad de trabajo. Un *breaking change* no autoriza a dejar tests rojos.
6. **La verdad antes que la apariencia.** Si una plataforma, un servidor o una combinación no se ha
   probado, se declara desconocida. No se convierte una hipótesis en un resultado verde.

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

El PLAN y `docs/workdir/ADR.md` son documentos vivos: se reescriben, podan y actualizan constantemente. `docs/workdir/ADR.md` es el registro vivo de decisiones de arquitectura y se actualiza en el mismo cambio que introduce una decisión estructural; no se trata como un archivo histórico. El PLAN resume el estado, las fases y el detalle operativo. Solo lo vigente permanece en el documento activo. El historial de git conserva todo lo necesario; no se acumula histórico cerrado en los documentos activos. Los documentos de diseño (`docs/prompts/`) son de solo lectura: se consultan como referencia y no se reescriben ni se podan como documentos vivos, salvo instrucción explícita.

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

## C7. Sin retrocompatibilidad prematura

En régimen pre-1.0 no se mantiene compatibilidad de features ni de contratos hacia atrás. Se rompe
a propósito cuando la arquitectura lo necesita, se elimina el camino viejo en el mismo cambio y no
se conservan adaptadores, alias ni parches de soporte. La compatibilidad prematura contamina la
base de código (*bloatware*) y fija decisiones antes de que el diseño esté terminado. Cada fase
debe tener puertas de calidad, planes completados y tests en verde; está prohibido dejar código a
medias.

La prohibición se refiere a capas de compatibilidad dentro de un feature o contrato, no a la
existencia de módulos legacy independientes. Para módulos rige C6: si hay dos implementaciones
válidas, se implementan ambas y el usuario elige. `iflow-firewall` y `CoreTranslator` son módulos
legacy mantenidos, no shims. La compatibilidad con versiones publicadas es una decisión de
producción; no se anticipa en pre-release.

## C8. Betatesting/E2E real después de 1.0.0

Una vez publicada la etiqueta de release **1.0.0**, comienza el betatesting/E2E en servidores reales
Spigot/Paper, Fabric y proxies Velocity. Los tests automatizados siguen siendo obligatorios y no se
sustituyen por una prueba manual; el E2E real es una capa adicional para descubrir integraciones que
un test aislado no puede demostrar. E2E significa probar manualmente todas las features de la suite
en un servidor real, no un smoke test de carga. Una plataforma no probada se declara **desconocida**,
no aprobada. No se declara estabilidad ni producción hasta cerrar el ciclo de betatesting/E2E
posterior a la 1.0.0; los fallos del beta se corrigen en la línea beta, no en la 1.0.

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

## C14. Documentación mínima y autorizada

No se agrega documentación adicional dentro de `docs/workdir/` ni `docs/prompts/` sin una pregunta explícita y su autorización. El estado operativo, los inventarios y las notas de cierre viven en `PLAN.md` y, cuando sean decisiones estructurales, en `ADR.md`; no se crean archivos auxiliares para almacenar evidencia temporal, inventarios de una fase ni addendas. `AUDITORIA.md` no se modifica: contiene el informe de la auditoría vigente y no se le añaden notas, addendas ni referencias; solo se reemplaza por completo al cerrar su ciclo. Los documentos de diseño (`docs/prompts/`) son de solo lectura y no se usan como almacén de notas. `docs/wiki/` queda excluida de esta regla porque es documentación de usuario.

## C15. Relectura obligatoria tras limpiar el contexto

Siempre que se limpie el contexto de trabajo —por compactación, resumen, checkpoint o reinicio de sesión— la primera acción obligatoria es releer el contexto normativo completo. Como mínimo se leen `docs/workdir/AUDITORIA.md`, `docs/workdir/PLAN.md`, `docs/workdir/ADR.md` y `docs/prompts/diseno/philosophy.md`. No se edita, ejecuta ni reanuda ninguna tarea hasta terminar esa lectura. Solo después se continúa con el trabajo. El resumen de contexto es una ayuda, no la autoridad: no sustituye la relectura de los documentos.

---

# Principios de Diseño del Proyecto

Estos son los principios abstractos que guían el proyecto. Las especificaciones técnicas de cada uno viven en el PLAN o en los ADR correspondientes.

## D1. Pluralidad sobre monolito

Si tienes opción a elegir, impleméntalas todas y déjaselo al usuario. No se fuerza un monolito a elegir un solo motor. Cada motor, backend o implementación es un módulo independiente instalable y reemplazable. Los motores opuestos para una misma tarea pueden coexistir como paquetes independientes; el usuario elige cuál instalar.

## D6. Código arbitrario, repositorio responsable

El manager instala código arbitrario. Esa es la idea. Hay sandboxing por módulo, pero el punto central no es el sandbox: **el dueño del repositorio es responsable de lo que se publica en él**. La seguridad se basa tanto en el sandbox como en la confianza en el repositorio. Ver la costumbre C9.
