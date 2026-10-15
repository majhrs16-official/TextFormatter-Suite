# Auditoría técnica forense de iFlow VM + Compiler

El objeto de auditoría es el repositorio [majhrs16-official/TextFormatter-Suite](https://github.com/majhrs16-official/TextFormatter-Suite), con alcance sobre `src/iflow/`, `tools/iflow-compiler/` y las integraciones necesarias para comprobar el recorrido completo del programa.

He confirmado que el repositorio público existe, que su rama predeterminada es `main` y que contiene tanto el módulo Java de iFlow como el compilador TypeScript, programas de ejemplo y una suite de pruebas Java. Esto confirma que hay componentes concretos que inspeccionar, pero todavía no demuestra que el compilador y la VM sean compatibles ni que las políticas funcionen de extremo a extremo.

La auditoría distinguirá los defectos demostrados de las hipótesis pendientes y no presentará pruebas estáticas como si fueran ejecuciones reales.

## 1. Identificación de la versión examinada

Commit auditado

`0c776da0006c7fcbd72dea4b2c5629c8a40097a`

15 de octubre de 2026 · rama `main`

Componentes identificados

- Runtime Java: `src/iflow/src/main/java/`
- Pruebas Java: `src/iflow/src/test/java/`
- Compiler TypeScript: `tools/iflow-compiler/src/`
- Ejemplos y verificación auxiliar: `tools/iflow-compiler/test/`

Un dato importante para interpretar los resultados: el commit examinado incluye una corrección que declara abordar doce hallazgos previos, `IF-001` a `IF-012`. Entre los cambios anunciados figuran la corrección de una copia de memoria, la longitud de argumentos HOST, la validación del hash, la generación de listados y la publicación atómica del programa. Esas descripciones son metadatos del commit; he contrastado parte de ellas con el código, pero no las considero prueba automática de que las correcciones sean completas.

## 2. Primeros hallazgos técnicos

La inspección estática ya permite identificar varios puntos que requieren atención:

- Concurrencia: el registro HOST mantiene las capacidades autorizadas en un campo mutable compartido por las ejecuciones. El motor modifica ese campo al comenzar cada evaluación. Esto crea una carrera potencial entre programas que tengan capacidades diferentes.
- Pool de contextos: utiliza una `ArrayDeque` sin sincronización. Si el motor recibe evaluaciones concurrentes, la estructura compartida no ofrece por sí misma seguridad para esos accesos.
- Directivas del compilador: `.align` ejecuta un bucle cuyo avance depende del módulo de la alineación solicitada. No hay una validación visible del valor cero antes del bucle.
- Cobertura de pruebas: algunos tests siguen comprobando el comportamiento anterior de las capacidades. Es necesario contrastarlos con el nuevo contrato y ejecutar la suite para determinar cuáles fallan realmente.

Estos puntos no tienen todos el mismo grado de certeza: los patrones de código están presentes, mientras que sus consecuencias bajo una carga concreta deben distinguirse de los resultados experimentales.

## 3. Registro de hallazgos

Las referencias siguientes corresponden al commit indicado en la sección anterior. Los números de línea se han contrastado con los archivos recuperados de esa revisión.

### IFLOW-001 — Las capacidades HOST no están aisladas por ejecución concurrente

Alta · Seguridad

Estado epistemológico: INFERENCIA JUSTIFICADA, basada en un patrón de concurrencia verificable.

Ubicaciones:

- `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/service/HostServiceRegistry.java:113-113`
- `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/service/HostServiceRegistry.java:222-232`
- `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/service/HostServiceRegistry.java:235-247`
- `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/engine/VmPolicyEngine.java:153-181`

Evidencia y razonamiento:

1. El registro HOST mantiene `allowedCapabilities` en un campo mutable de instancia.
2. `VmPolicyEngine.route()` comparte el mismo `HostServiceRegistry` entre las evaluaciones.
3. Cada evaluación cambia las capacidades permitidas mediante `setAllowedCapabilities()`.
4. La autorización de cada servicio consulta ese campo compartido.
5. No hay sincronización que vincule la comprobación de capacidades a la identidad del programa que originó la llamada.

Si una ejecución del programa anterior continúa activa mientras se publica y ejecuta otro programa con capacidades distintas, las comprobaciones pueden observar el conjunto de capacidades equivocado.

Impacto: un programa podría recibir una autorización que no le corresponde, o perder una autorización válida. Es un riesgo de aislamiento que debe resolverse antes de considerar segura la ejecución concurrente durante una sustitución de programa.

Corrección recomendada: pasar las capacidades autorizadas como parte del contexto de ejecución o de un objeto de invocación HOST inmutable. La autorización debe derivarse del programa concreto asociado a esa ejecución, no de un estado global que pueda cambiar.

Regresión necesaria: ejecutar dos programas con conjuntos de capacidades disjuntos, intercalar sus llamadas HOST y sustituir el programa activo durante la prueba. Ninguna llamada debe heredar permisos del otro programa.

Limitación: el patrón de carrera es real en el código; no he ejecutado un test que reproduzca la intercalación ni he demostrado una explotación en producción.

### IFLOW-002 — El pool de contextos no es seguro para acceso concurrente

Alta · Estabilidad

Estado epistemológico: HECHO VERIFICADO sobre la implementación; impacto concurrente derivado de ella.

Ubicación: `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/execution/ExecutionContextPool.java:21-21`, `44-63` y `67-88`.

El pool utiliza una `ArrayDeque` compartida y ejecuta operaciones como `poll()`, `offer()` y `size()` sin sincronización. Los contadores atómicos no hacen thread-safe a la cola.

Si varias llamadas a `route()` se ejecutan concurrentemente, pueden adquirir y liberar contextos al mismo tiempo. La estructura no garantiza que estas operaciones se comporten correctamente bajo esa concurrencia.

Impacto: corrupción del estado interno de la cola, pérdida de contextos disponibles o comportamiento inconsistente del pool. Esto no demuestra por sí solo que dos ejecuciones reciban el mismo contexto, pero impide confiar en la implementación actual como pool concurrente.

Corrección recomendada: sustituir la cola por una estructura concurrente apropiada o sincronizar las operaciones de adquisición y liberación. Revisar también el cierre del pool y el acceso a sus métricas.

Regresión necesaria: prueba multihilo de adquisición y liberación, comprobando que ningún contexto se entrega simultáneamente a dos ejecuciones y que los contadores y la cola mantienen sus invariantes.

### IFLOW-003 — La escritura de bloques puede dejar modificaciones parciales

Alta · Integridad de memoria

Estado epistemológico: HECHO VERIFICADO sobre el flujo de escritura.

Ubicaciones:

- `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/memory/SoftMMU.java:127-141`
- `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/memory/VmDmaController.java:85-150` (la ruta de escritura delega en `SoftMMU` y la validación de rangos está implementada por separado).

Evidencia y razonamiento:

En el caso de un bloque que cruza páginas no contiguas, `SoftMMU.writeBlock()` escribe byte a byte. Antes del bucle traduce la dirección inicial, pero no valida previamente los permisos de escritura de todas las páginas que recibirá el bloque.

Si la primera página permite escritura y la segunda no, los primeros bytes pueden haberse escrito cuando el acceso a la segunda página falla.

El controlador DMA documenta validación de rangos y transferencias atómicas, pero su método público `writeBlock()` delega directamente en `SoftMMU.writeBlock()` en lugar de validar previamente el rango completo.

Impacto: una operación que falla puede modificar parcialmente el estado de la VM. Esto importa especialmente si una instrucción, un servicio HOST o una preparación de datos espera que la transferencia sea íntegra.

Corrección recomendada: validar el rango completo, todas las páginas y todos los permisos antes de modificar memoria. Si se requiere atomicidad real, garantizar que ninguna escritura pueda fallar después de comenzar la fase de modificación.

Regresión necesaria: escribir un bloque que cruce desde una página escribible a otra de solo lectura y verificar que, tras el error, todos los bytes originales permanecen intactos.

### IFLOW-004 — La directiva `.align` puede bloquear el compilador

Alta · Disponibilidad del compilador

Estado epistemológico: HECHO VERIFICADO por inspección estática.

Ubicación: `tools/iflow-compiler/src/assembler.ts:163-168`.

El compilador calcula la alineación y ejecuta:

```
while (this.currentAddress % align !== 0) {
  this.currentAddress++;
}
```

No se observa una comprobación previa que rechace una alineación igual a cero. Con `.align 0`, el resto es `NaN`, que nunca es igual a cero. El bucle continúa incrementando `currentAddress` indefinidamente.

Impacto: un archivo fuente malformado puede dejar el proceso del compilador bloqueado. También debe investigarse el tratamiento de literales numéricos inválidos, ya que `parseNumber()` puede devolver `NaN` sin lanzar una excepción.

Corrección recomendada: exigir una alineación positiva y válida, definir si se admiten únicamente potencias de dos y rechazar valores que no cumplan el contrato. Añadir límites de tamaño y tiempo para la compilación cuando procese fuentes no confiables.

Regresión necesaria: ejecutar `.align 0`, `.align -1` y `.align invalid` en procesos con timeout; cada caso debe finalizar con un diagnóstico claro, no bloquearse.

### IFLOW-005 — El mapa fijo de memoria entra en conflicto con programas grandes

Alta · Coherencia de memoria

Estado epistemológico: INFERENCIA JUSTIFICADA a partir de la configuración y del orden de los mapeos.

Ubicación: `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/engine/VmPolicyEngine.java:199-249`, junto con `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/isa/ProgramImageParser.java:56-79`.

El motor mapea las páginas necesarias para el código y copia el programa a memoria. Después asigna páginas fijas para constantes, datos, pila y buffers. En particular, la página 1 se reserva para constantes y las páginas 2 y 3 para datos.

Sin embargo, el parser permite imágenes con código de hasta 65.535 bytes, y el cálculo de páginas del motor contempla más de una página de código.

Para código que exceda la primera página, las asignaciones posteriores pueden reemplazar los mapeos que correspondían al código. Además, las constantes se escriben en la misma memoria física subyacente.

El intérprete utiliza una copia de los bytes del código para ejecutar las instrucciones, por lo que el problema no implica necesariamente que toda ejecución de código grande falle. Sí significa que la representación del código en memoria y su acceso a través de la MMU pueden dejar de corresponder con el diseño esperado.

Impacto: incompatibilidad entre el tamaño aceptado por el formato binario y el mapa de memoria real; posibles solapamientos entre código, constantes y datos.

Corrección recomendada: definir y validar explícitamente el tamaño máximo del código compatible con el mapa actual, o construir un mapa dinámico que reserve regiones sin solapamientos. No basta con limitar el número de páginas mapeadas.

Regresión necesaria: cargar programas con código de 4.095, 4.096, 4.097 y más bytes, con constantes presentes y ausentes. Comprobar el contenido de memoria, los permisos y la ejecución efectiva.

### IFLOW-006 — Los tests de capacidades no corresponden al nuevo contrato

Media · Regresión de tests

Estado epistemológico: CONTRADICCIÓN entre la implementación y las expectativas de los tests; el resultado de ejecución está pendiente.

Ubicaciones:

- `src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/service/HostServiceRegistry.java:218-232`
- `src/iflow/src/test/java/me/majhrs16/suite/iflow/vm/service/HostServiceRegistryTest.java:58-75`
- `src/iflow/src/test/java/me/majhrs16/suite/iflow/vm/service/HostServiceRegistryTest.java:78-100`

La implementación actual autoriza capacidades consultando exclusivamente `allowedCapabilities`. Por defecto, ese conjunto está vacío. `declareCapability()` actualiza `capabilityMap`, pero `hasCapability()` no consulta ese mapa.

Sin embargo, los tests esperan que las capacidades declaradas por el constructor y las añadidas mediante `declareCapability()` sean inmediatamente reconocidas por `hasCapability()`. Otros tests invocan servicios sin establecer primero las capacidades permitidas para la ejecución.

Por tanto, el código y las expectativas de esos tests son incompatibles. No he ejecutado JUnit para afirmar cuántos tests fallan realmente.

Corrección recomendada: decidir cuál es el contrato correcto. Si la autorización debe ser específica del programa, actualizar los tests para configurar explícitamente las capacidades permitidas y comprobar las denegaciones. No se debe restaurar accidentalmente una autorización global para hacer pasar los tests.

### IFLOW-007 — El compilador acepta operandos inválidos y puede sustituirlos por cero

Estado epistemológico: HECHO VERIFICADO sobre el comportamiento de los métodos auxiliares.

Severidad: Alta para la corrección funcional.

Ubicaciones:

- `tools/iflow-compiler/src/assembler.ts:258-343`
- `tools/iflow-compiler/src/assembler.ts:473-477`
- `tools/iflow-compiler/src/assembler.ts:485-507`

`getRegOperand()` devuelve el registro `R0` cuando el operando no es un registro válido. Otros métodos devuelven cero si no encuentran un operando del tipo esperado. El parser reconoce tokens, pero no se observa una validación completa de cantidad, tipo y rango de los operandos antes de emitir los bytes.

Esto permite que una instrucción sintácticamente incorrecta se convierta en bytecode con una semántica diferente de la pretendida, en lugar de producir necesariamente un error de compilación.

Corrección recomendada: validar cada instrucción contra una especificación única de la ISA: cantidad de operandos, tipos admitidos, registros permitidos y rangos numéricos. Un operando inválido debe generar un diagnóstico de error y evitar la generación del binario.

Regresión necesaria: probar instrucciones con registros inexistentes, operandos ausentes, operandos adicionales, literales fuera de rango y etiquetas utilizadas en posiciones que exigen registros.

### IFLOW-008 — `.origin` puede producir direcciones incoherentes al retroceder

Estado epistemológico: INFERENCIA JUSTIFICADA por el algoritmo de ensamblado.

Ubicación: `tools/iflow-compiler/src/assembler.ts:119-134` y `151-154`.

La primera pasada permite cambiar `currentAddress` mediante `.origin`. La segunda pasada solo agrega relleno cuando `code.length` es inferior a la dirección de la siguiente instrucción; no retrocede ni reescribe bytes cuando la dirección disminuye.

Por tanto, un programa que establezca un origen inferior a la posición actual puede tener etiquetas y direcciones declaradas que no coincidan con las posiciones donde finalmente se emiten las instrucciones.

Corrección recomendada: rechazar solapamientos y retrocesos no permitidos, o implementar una representación de secciones y direcciones que permita resolverlos correctamente.

Regresión necesaria: ensamblar un programa mínimo con dos directivas `.origin`, la segunda inferior a la primera, y comprobar las direcciones de las etiquetas y los bytes resultantes.

### IFLOW-009 — `.entry` no definido puede convertirse silenciosamente en el origen

Estado epistemológico: HECHO VERIFICADO sobre la lógica de resolución.

Ubicación: `tools/iflow-compiler/src/assembler.ts:650-656`.

`getEntryPointAddress()` busca la etiqueta indicada, pero si no la encuentra devuelve el origen del programa. No se observa que esa función emita un diagnóstico por la etiqueta inexistente.

Esto puede ocultar errores de configuración del punto de entrada y generar una imagen con un entry point distinto del solicitado.

Corrección recomendada: si se especifica `.entry` o `--entry`, exigir que la etiqueta exista. De lo contrario, abortar el ensamblado con el nombre de la etiqueta y su ubicación en el fuente.

### IFLOW-010 — El flujo de CI está desincronizado de la configuración de módulos

Estado epistemológico: CONTRADICCIÓN verificable entre archivos; la causa exacta del fallo de CI no está establecida.

Ubicaciones:

- `settings.gradle:28-38`
- `.github/workflows/ci.yml:33-68`

La configuración de Gradle incluye `manager-spi`, `manager-core` y `manager-apt`, mientras que el workflow intenta construir `manager-api` y `manager-impl`. Además, `fabric-host` está excluido en `settings.gradle`, pero el workflow intenta construirlo.

He consultado la ejecución real de GitHub Actions correspondiente al commit auditado:

\- [CI, ejecución 51](https://github.com/majhrs16-official/TextFormatter-Suite/actions/runs/38811982063)

La ejecución terminó en fallo. Fallaron los pasos de construcción de módulos, generación de Javadoc y pruebas de integración del editor web.

Importante: no he obtenido los registros detallados de esos pasos, por lo que no atribuyo todos los fallos a las discrepancias de nombres de módulos. Sí queda demostrado que la ejecución de CI no fue satisfactoria y que existen referencias incoherentes en el workflow.

Corrección recomendada: sincronizar el workflow con los proyectos incluidos en `settings.gradle` y verificar que las tareas invocadas existen antes de considerar válida la canalización.

## 4. Evaluación del diseño y de las garantías

No todos los mecanismos presentan defectos. También hay decisiones razonables que conviene conservar.

| Componente                  | Evaluación estática                                                                                                                                     |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ProgramImage`              | Las matrices recibidas y devueltas se copian defensivamente, lo que reduce la mutabilidad accidental.                                                   |
| Parser binario              | Comprueba las versiones mínimas, el entry point, los datos truncados, los bytes sobrantes y el SHA-256.                                                 |
| Publicación del programa    | `LoadedProgram` agrupa programa y MMU, y `AtomicReference` publica el conjunto en una sola operación.                                                   |
| Presupuesto de ejecución    | El intérprete consume fuel por instrucción y los servicios HOST tienen costes adicionales.                                                              |
| Journal                     | Las modificaciones se registran de forma diferida y existe una distinción entre commit y descarte.                                                      |
| Aislamiento por capacidades | La intención de autorizar según el programa es adecuada, pero el estado mutable compartido introduce el problema de concurrencia descrito en IFLOW-001. |
| Pool                        | La reutilización de contextos puede reducir asignaciones, pero la implementación de la cola no garantiza seguridad concurrente.                         |
| Compiler                    | Hay una separación reconocible entre primera pasada, segunda pasada y serialización, aunque la validación semántica de operandos es insuficiente.       |

Estas observaciones son estáticas. No equivalen a una certificación de corrección, seguridad o rendimiento.

## 5. Pruebas y limitaciones de esta ejecución

Hay una diferencia importante entre el trabajo realizado y una auditoría completamente ejecutada en un entorno local.

Comprobado

Identidad del commit, estructura del repositorio, código fuente de los componentes principales, configuración de Gradle, tests seleccionados y estado de la última ejecución de CI.

Pendiente de ejecución

Suite completa de JUnit, ensamblado real de los ejemplos, pruebas de regresión nuevas, reproducción multihilo, fuzzing, verificación byte a byte de imágenes compiladas y benchmarks.

No certificado

Aislamiento entre ejecuciones, atomicidad de todas las modificaciones, ausencia de corrupción de memoria, robustez frente a entradas hostiles y funcionamiento completo de las políticas en el servidor anfitrión.

No he clonado ni compilado el repositorio en un entorno local durante esta ejecución. En consecuencia, no afirmo que los tests pasen o fallen por ejecución propia, ni que el compilador y el runtime hayan superado pruebas de interoperabilidad.

## 6. Plan de corrección priorizado

1. P0 — Integridad y aislamiento
   - Corregir IFLOW-001: vincular las capacidades a cada ejecución.
   - Corregir IFLOW-003: validar los rangos de memoria antes de modificar datos.
   - Corregir IFLOW-005: resolver los solapamientos del mapa de memoria y establecer límites coherentes con el formato binario.
   Criterio de aceptación: pruebas que demuestren que una ejecución no obtiene permisos ajenos y que las escrituras fallidas no dejan modificaciones parciales.
2. P1 — Corrección funcional
   - Corregir IFLOW-007: validar los operandos.
   - Corregir IFLOW-009: rechazar entry points inexistentes.
   - Corregir IFLOW-008: definir el comportamiento permitido de `.origin`.
   - Revisar la coherencia entre las directivas del compiler y el formato aceptado por la VM.
   Criterio de aceptación: fuentes inválidas rechazadas y programas válidos con direcciones y bytes comprobables.
3. P2 — Robustez y concurrencia
   - Corregir IFLOW-002.
   - Corregir IFLOW-004.
   - Actualizar los tests de capacidades de IFLOW-006.
   - Añadir pruebas para datos malformados, límites de memoria y fallos durante la ejecución.
   Criterio de aceptación: ninguna prueba de regresión debe depender de permisos globales ni de comportamiento indefinido del compilador.
4. P3 — Build y observabilidad
   - Corregir IFLOW-010.
   - Ejecutar la suite completa del módulo iFlow.
   - Generar una imagen binaria con el compiler y cargarla con el parser Java.
   - Añadir una prueba de extremo a extremo que invoque un servicio HOST y valide el resultado producido por el motor.
   Criterio de aceptación: CI satisfactoria y resultados reproducibles asociados al mismo commit.

## 7. Puntuación técnica provisional

No asigno notas numéricas a dimensiones cuya corrección depende de pruebas que no he ejecutado. Hacerlo ahora introduciría una precisión aparente que la evidencia disponible no justifica.

| Dimensión            | Resultado                                                     |
| -------------------- | ------------------------------------------------------------- |
| Arquitectura general | Prometedora, con defectos de integración identificados        |
| ISA y semántica      | No evaluable de forma exhaustiva                              |
| Compiler             | Defectos estáticos identificados                              |
| Formato binario      | Validación estática parcial; interoperabilidad no certificada |
| Memoria y MMU        | Riesgos de integridad y coherencia identificados              |
| Capacidades HOST     | Riesgo de concurrencia identificado                           |
| Cobertura de tests   | Tests presentes; ejecución completa no verificada             |
| Rendimiento          | No evaluable sin benchmarks                                   |
| Seguridad global     | No certificada                                                |
| Madurez global       | No evaluable con rigor mediante esta ejecución parcial        |

## Conclusión

La inspección demuestra que iFlow cuenta con una arquitectura concreta de VM, memoria paginada, servicios HOST, compilador independiente y un mecanismo de publicación atómica del programa. También identifica defectos específicos en la gestión de capacidades concurrentes, el pool de contextos, las escrituras de memoria, el ensamblado y la configuración de CI.

La prioridad inmediata es resolver los riesgos de aislamiento y memoria, después endurecer el compilador y finalmente ejecutar las pruebas de interoperabilidad y regresión. No hay evidencia suficiente para declarar segura la VM ni para afirmar que la cadena completa compiler-runtime-host funcione correctamente.

Este informe es un resultado de auditoría estática con comprobaciones puntuales del repositorio y del estado de CI; no es todavía el informe forense completo de 25 secciones solicitado en el prompt. Para completarlo con el mismo nivel de rigor faltan, sobre todo, ejecutar las pruebas reproducibles y verificar la ISA y el flujo de políticas de extremo a extremo.
