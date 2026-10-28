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

# Principios de Diseño del Proyecto

Estos son los principios abstractos que guían el proyecto. Las especificaciones técnicas de cada uno viven en el PLAN o en los ADR correspondientes.

## D1. Pluralidad sobre monolito

Si tienes opción a elegir, impleméntalas todas y déjaselo al usuario. No se fuerza un monolito a elegir un solo motor. Cada motor, backend o implementación es un módulo independiente instalable y reemplazable. Los motores opuestos para una misma tarea pueden coexistir como paquetes independientes; el usuario elige cuál instalar.

## D6. Código arbitrario, repositorio responsable

El manager instala código arbitrario. Esa es la idea. Hay sandboxing por módulo, pero el punto central no es el sandbox: **el dueño del repositorio es responsable de lo que se publica en él**. La seguridad se basa tanto en el sandbox como en la confianza en el repositorio.

## C10. Las pruebas E2E en servidores reales están descartadas

No se validará en servidores reales Spigot/Paper, Fabric ni proxies Velocity. La validación de plataformas se limita a compilación y tests automatizados que no requieran un servidor real. Las menciones históricas a «tests E2E pendientes», «probar en servidor real» o «proxy Velocity real» quedan cerradas por esta decisión.

## C11. No acumular histórico en documentos

Lo que ya está hecho, simplemente se borra del documento. El historial de git conserva todo lo necesario. No se debe acumular histórico cerrado en archivos de planificación o diseño; si ya está implementado, sale del documento activo.
