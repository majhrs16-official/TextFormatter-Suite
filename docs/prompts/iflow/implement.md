# Diseño arquitectónico integral del CPU virtual de iflow

Especificación técnica de referencia · Versión 1.0-draft · 13 de octubre de 2026

## Dictamen técnico previo: viabilidad de la arquitectura

La arquitectura es viable, pero no conviene implementarla literalmente como una computadora física reproducida en Java. La solución adecuada es una máquina virtual interpretada, con una ISA propia, memoria virtual protegida mediante paginación por software (SoftMMU), servicios genéricos hacia el host y un modelo explícito de persistencia.

La principal dificultad no es ejecutar instrucciones aritméticas. Es conseguir que la VM sea simultáneamente expresiva, eficiente, segura, reutilizable y compatible con las semánticas actuales de iflow.

El diseño debe resolver primero cinco problemas:

1. Cómo representar direcciones y datos en un espacio plano de 64 KB sin limitar la expresividad.
2. Cómo mantener el estado de una ejecución aislado sin perder la posibilidad de conservar estado deliberadamente entre mensajes.
3. Cómo consultar objetos Java bajo demanda sin convertir cada consulta en una operación insegura ni cada acceso en una copia completa.
4. Cómo ejecutar lógica arbitraria sin permitir que un bucle o una expresión regular bloquee el servidor.
5. Cómo permitir persistencia cruzada entre mensajes sin crear canales de información no deseados.

La propuesta siguiente resuelve estos problemas mediante decisiones concretas.

## Auditoría inicial: contradicciones y correcciones necesarias

1\. Una ISA de 16 bits **sí puede** trabajar con 64 KiB de espacio plano lineal.

Se propone un espacio de direcciones plano de 16 bits ($0x0000$–$0xFFFF$, **64 KB totales**) con **paginación de 4 KB (16 páginas)** gestionada por un **SoftMMU** (Memory Management Unit por software). Esto elimina la complejidad de *handles*, regiones y offsets compuestos, mientras que la paginación proporciona aislamiento y protección por página (permisos R/W/X por página). No hay regiones, no hay handles, no hay búferes de entrada aislados.

2\. Reutilizar la VM no debe equivaler a reutilizar accidentalmente un contexto.

El programa compilado (`.flow`), el firmware y las bibliotecas pueden persistir. Los registros, la pila, el presupuesto y la tabla de páginas pertenecen a una ejecución. La persistencia de memoria o registros entre mensajes debe ser una capacidad explícita, aislada y desactivada por defecto.

3\. Una instrucción genérica de acceso al host es preferible a una ISA específica del dominio.

La instrucción `HOST` es la única puerta al exterior. Todo lo que el programa necesita —leer/escribir propiedades, comprobar permisos, tomar decisiones de enrutamiento, leer/escribir estado persistente, resolver canales— se hace mediante `HOST` con un *service ID* de 16 bits. Las capacidades (strings) gating cada servicio.

4\. El presupuesto de *fuel* es obligatorio, no opcional.

Cada instrucción consume 1 unidad base + coste variable. Servicios `HOST` consumen coste base + coste por byte. Se lanza excepción `OUT_OF_FUEL` si se agota. Límite por defecto: 100 000 unidades/evaluación.

5\. La persistencia de memoria/registros entre ejecuciones es una *capability*, no un derecho.

El host puede conceder o denegar `memory.persist` y `registers.persist`. Sin ellas, cada ejecución empieza con memoria y registros a cero (salvo I/O buffers y página de código). Con ellas, se conserva el estado del *frame* o páginas marcadas.

6\. No hay bytecode SpEL ni compilación JIT en caliente. Los programas se entregan como binarios `.flow` pre-compilados.

La herramienta externa `iflow-compiler` (TypeScript/JS) ensambla `.flowasm` → `.flow` y calcula hashes. El runtime Java **solo carga y ejecuta**. No hay `VmProgramCompiler`, no hay `RuleExpressionEvaluator`, no hay `VmConfigLoader` de YAML.

7\. **No hay búferes de entrada externos.** Al ser un modelo de memoria completamente plano, el host no "inyecta" datos en búferes especiales. El programa en la VM interactúa directamente con la memoria plana mediante llamadas `HOST`, pasando direcciones virtuales absolutas (ej.: "escribe la propiedad en el buffer de entrada `0x7000`"). El programa manipula los datos in-place usando punteros, bucles y condiciones estándar.

### Decisión central

La arquitectura recomendada es una **VM de 16 bits con espacio plano de 64 KB y paginación SoftMMU de 4 KB**, de un solo flujo de ejecución por contexto, interpretada y con servicios explícitos del host.

No se recomienda empezar con un procesador multinúcleo virtual, un JIT, microcódigo, una tabla de interrupciones física ni una implementación de todo el mensaje como memoria precargada.

El paralelismo entre mensajes será responsabilidad del runtime Java. El procesador VM es secuencial; la concurrencia se logra instanciando múltiples contextos de ejecución (pools) y dejando que el scheduler Java los distribuya.

## Programas iflow

Bytecode versionado (`.flow`) · Bibliotecas · Políticas

Validador y cargador (solo binario, sin YAML/SpEL)

Formato, opcodes, capacidades, límites y control de flujo

## CPU virtual (arquitectura hardware-style)

Fetch · Decode · Execute

Contexto

Registros · Pila · Fuel · SoftMMU (PageTable)

Memoria plana 64 KB

16 páginas · 4 KB c/u · Permisos R/W/X por página

Supervisor

# 2. Requisitos funcionales y no funcionales

## 2.1. Requisitos funcionales

La máquina debe:

- Ejecutar bytecode portable sin depender de instrucciones del procesador físico.
- Realizar operaciones aritméticas, lógicas, de memoria y de control de flujo.
- Ejecutar subrutinas, bucles y bibliotecas.
- Leer y escribir propiedades del mensaje bajo demanda.
- Consultar permisos y solicitar operaciones del host sin acceder directamente a objetos Java.
- Generar decisiones de routing verificables.
- Ejecutar transformaciones mediante una interfaz controlada.
- Mantener estado persistente cuando la política lo autorice.
- Abortarse de manera acotada ante errores o agotamiento de recursos.
- Permitir inspección, desensamblado y diagnóstico de programas.
- Admitir nuevas propiedades y servicios sin modificar la ISA para cada incorporación.

## 2.2. Requisitos no funcionales

| Propiedad      | Requisito                                                      |
| -------------- | -------------------------------------------------------------- |
| Seguridad      | Ningún acceso arbitrario a JVM, archivos, red o APIs del host  |
| Aislamiento    | Una ejecución no puede escribir en el contexto privado de otra |
| Portabilidad   | Semántica independiente de la plataforma                       |
| Determinismo   | Instrucciones puras y costes básicos reproducibles             |
| Rendimiento    | Medición de latencia, throughput y asignaciones                |
| Compatibilidad | Versionado explícito de ISA, ABI y programas                   |
| Extensibilidad | Nuevos servicios sin ampliar el repertorio fundamental         |
| Recuperación   | Los errores de una política no deben derribar el servidor      |
| Observabilidad | Trazas opcionales y errores reproducibles                      |
| Mantenibilidad | Intérprete de referencia simple antes de optimizar             |

## 2.3. Fuera de alcance de la primera versión

- Compilación JIT.
- Ejecución de código nativo.
- Hilos creados por programas de usuario.
- Operaciones atómicas compartidas dentro de la ISA.
- Interrupciones asíncronas de estilo hardware.
- Un sistema operativo invitado.
- Un lenguaje de alto nivel propio.
- Paralelismo interno a una única ejecución.
- Compatibilidad binaria automática con reglas que tengan semánticas diferentes.

# 3. Modelo de amenazas

La VM debe asumir que un programa puede estar mal escrito, ser excesivamente costoso o haber sido creado deliberadamente para abusar de los recursos disponibles.

| Amenaza                   | Ejemplo                                                           | Defensa                                                  |
| ------------------------- | ----------------------------------------------------------------- | -------------------------------------------------------- |
| Bucle infinito            | Salto hacia sí mismo                                              | Fuel por instrucción                                     |
| Operación costosa         | Regex patológica                                                  | Límites de entrada y algoritmo seguro                    |
| Lectura fuera de límites  | Cargar un offset inválido                                         | Comprobación en cada acceso                              |
| Corrupción de privilegios | Sobrescribir una tabla de servicios                               | Memoria privilegiada inaccesible                         |
| Fuga entre mensajes       | Leer el registro que dejó otra ejecución                          | Contextos privados y reinicialización                    |
| Abuso de persistencia     | Crecimiento ilimitado de un contador o buffer                     | Cuotas y esquema persistente                             |
| Confusión de permisos     | Usar autorización del administrador como autorización del jugador | Identidades y capacidades separadas                      |
| Servicio malicioso        | Solicitar una escritura no autorizada                             | Validación dentro del servicio                           |
| Bloqueo del servidor      | Servicio host que espera indefinidamente                          | Servicios acotados y prohibición de bloqueos arbitrarios |
| Fallo de transacción      | Error después de modificar texto                                  | Escritura diferida y descarte del journal                |
| Condición de carrera      | Dos mensajes modifican el mismo estado                            | Exclusión por instancia o versionado                     |
| Bytecode corrupto         | Opcode truncado o salto inválido                                  | Validación de formato y decodificación segura            |

La validación estática no reemplaza las comprobaciones en tiempo de ejecución. Un programa válido puede recibir una dirección inválida desde un cálculo, agotar su presupuesto o solicitar una propiedad cuyo acceso haya sido revocado.

La JVM y las APIs del host permanecen fuera de la memoria de la VM. El límite de confianza está en el intérprete, el validador y los servicios Java autorizados.

# 4. Diagrama de componentes y responsabilidades

## 4.1. Componentes lógicos

| Componente           | Responsabilidad                               | ¿Debe existir como subsistema separado?                   |
| -------------------- | --------------------------------------------- | --------------------------------------------------------- |
| Decoder/interpreter  | Ejecutar instrucciones                        | Sí, componente central                                    |
| RegisterFile         | Estado visible de CPU                         | Sí, puede ser una estructura de datos                     |
| SoftMMU / PageTable | Memoria paginada, permisos y límites         | Sí (`SoftMMU` sobre `PageTable`)                          |
| ExecutionContext     | Registros, pila, fuel, estado de la ejecución | Sí                                                        |
| ProgramImage         | Bytecode y constantes inmutables              | Sí                                                        |
| HostServiceRegistry  | Resolver servicios y validar argumentos       | Sí                                                        |
| Supervisor           | Límites, capacidades y aborto                 | Lógico; puede integrarse con el intérprete                |
| Firmware             | Rutinas y ABI estándar                        | Biblioteca y convenciones; no necesita un arranque físico |
| Interrupt Controller | Interrupciones virtuales                      | No en la versión inicial                                  |
| Scheduler de la VM   | Distribuir tareas                             | No; utilizar el runtime Java                              |
| Runtime iflow        | Integración con mensajes y router             | Sí                                                        |
| MessageDispatcher    | Entrega efectiva                              | Se mantiene fuera de la VM                                |

La separación es conceptual y contractual, no una obligación de crear una clase Java por componente.

## 4.2. Regla de autoridad

La VM puede solicitar una operación. El runtime determina si está permitida, la ejecuta de acuerdo con el contrato del host y devuelve un resultado estructurado.

El bytecode nunca recibe una referencia Java a `Message`, `Player`, `Channel`, `PermissionChecker` ni a otros objetos del host.

# 5. Modelo de ejecución y ciclo de vida

## 5.1. Separación de los objetos persistentes y temporales

La VM no se crea ni se destruye necesariamente por cada mensaje. Su unidad de reutilización es el runtime, mientras que su unidad de aislamiento es el contexto de ejecución.

| Objeto                    | Vida útil                                 | Compartido                      |
| ------------------------- | ----------------------------------------- | ------------------------------- |
| `ProgramImage`            | Hasta descargar o sustituir el programa   | Sí, inmutable                   |
| Firmware y bibliotecas    | Vida del runtime o del cargador           | Sí, inmutables una vez cargados |
| Tabla de servicios        | Vida del runtime                          | Sí, protegida                   |
| Código y constantes       | Vida del programa                         | Sí, solo lectura                |
| Memoria persistente       | Vida de la instancia lógica de programa   | Solo bajo reglas explícitas     |
| Registros generales       | Una ejecución                             | No                              |
| Pila de llamadas          | Una ejecución                             | No                              |
| Presupuesto               | Una ejecución                             | No                              |
| Journal de modificaciones | Una evaluación de mensaje                 | No                              |
| Caché de propiedades      | Una ejecución                             | No                              |
| Resultado de routing      | Una evaluación                            | No                              |
| Rate limiter              | Según su ámbito de configuración          | Estado del host                 |
| Contexto de mensaje       | Una evaluación o unidad lógica de routing | No                              |

## 5.2. Comparación de los modelos de ejecución

| Modelo                                  | Ventajas                     | Inconvenientes                           | Dictamen                 |
| --------------------------------------- | ---------------------------- | ---------------------------------------- | ------------------------ |
| A. VM por mensaje                       | Aislamiento sencillo         | Inicialización y asignaciones excesivas  | No recomendado           |
| B. VM compartida                        | Reutiliza código y servicios | Riesgo si se comparte el estado mutable  | Insuficiente por sí solo |
| C. CPU compartida con pool de contextos | Reutilización y aislamiento  | Necesita gestionar el pool y sus límites | Recomendado              |
| D. Varios trabajadores o instancias     | Mayor concurrencia potencial | Sincronización y orden más complejos     | Extensión del runtime    |

La implementación inicial debe mantener un `ProgramImage` compartido y reutilizar `ExecutionContext` privados mediante un pool acotado.

Un contexto devuelto al pool debe quedar limpio de estado transitorio. Si una ejecución termina por un error de integridad, se descarta el contexto en lugar de reutilizarlo a ciegas.

## 5.3. Ciclo de vida

1. El módulo inicializa el runtime y registra servicios.
2. El cargador valida y publica el programa.
3. El router recibe el mensaje y crea su contexto lógico.
4. Se adquiere un contexto de ejecución limpio.
5. Se asocian las capacidades autorizadas y el presupuesto.
6. Se ejecuta el punto de entrada del programa.
7. La VM devuelve una decisión y las modificaciones pendientes.
8. El runtime valida el resultado.
9. Se confirma o descarta el journal.
10. Se actualiza el estado persistente autorizado.
11. Se libera el contexto para su reutilización.
12. El dispatcher realiza la entrega cuando corresponda.

La actualización persistente y la confirmación de las modificaciones del mensaje deben tener un orden definido. Si ambas se confirman como una sola operación lógica, el runtime debe poder revertir las modificaciones preparadas cuando falle la actualización del estado. Si no puede garantizarlo, debe documentar que la atomicidad es por recurso y no global.

# 6. Decisión sobre el ancho de palabra y direccionamiento

## 6.1. Comparación de alternativas

| Alternativa                                 | Ventajas                                         | Costes y limitaciones                                    |
| ------------------------------------------- | ------------------------------------------------ | -------------------------------------------------------- |
| 16 bits con direcciones de 16 bits planas   | Bytecode sencillo, operandos compactos           | Límite global de 64 KiB                                  |
| 16 bits con segmentación convencional       | Amplía direcciones                               | Segmentos y registros especiales aumentan la complejidad |
| 16 bits con registros auxiliares de 32 bits | Amplía los valores y las direcciones             | Semántica desigual entre registros                       |
| 32 bits con memoria dinámica                | Direcciones y longitudes cómodas                 | Instrucciones y estado algo mayores                      |
| 16 bits con paginación SoftMMU 4 KB         | Espacio plano 64 KB, aislamiento por página R/W/X | Requiere tabla de páginas, ligera overhead               |

Recomendación: **16 bits con espacio plano de 64 KB y paginación SoftMMU de 4 KB**.

El espacio de direcciones plano de 16 bits ($0x0000$–$0xFFFF$, **64 KB totales**) con **paginación de 4 KB (16 páginas)** gestionada por un **SoftMMU** elimina la complejidad de *handles*, regiones y offsets compuestos, mientras que la paginación proporciona aislamiento y protección por página (permisos R/W/X por página). No hay regiones, no hay handles, no hay búferes de entrada aislados.

El diseño deberá revisarse si los benchmarks demuestran que la paginación por software introduce overhead significativo.

## 6.2. Semántica numérica

- Registro general: entero sin signo de 16 bits como representación física.
- Rango modular: \\(0\\) a \\(65535\\).
- Interpretación con signo: complemento a dos, rango \\(-32768\\) a \\(32767\\).
- Desbordamiento: módulo \\(2^{16}\\) en las operaciones ordinarias.
- División por cero: excepción aritmética.
- Desplazamientos: se valida que el conteo esté entre 0 y 15.
- Dirección: dirección virtual absoluta de 16 bits (traducida por SoftMMU).
- Longitudes mayores de 65535 bytes: no se expresan en una única operación ordinaria; se procesan por fragmentos o mediante una extensión explícita.

Una dirección no es un número arbitrario que pueda convertirse en una referencia Java. Es una dirección virtual validada por el SoftMMU.

# 7. Banco de registros completo

## 7.1. Registros visibles para los programas

La versión 1 tendrá ocho registros generales de 16 bits.

| Registro | Función                            | Inicialización              | Persistencia  |
| -------- | ---------------------------------- | --------------------------- | ------------- |
| `R0`     | General; resultado por convención  | Cero                        | Por ejecución |
| `R1`     | General                            | Cero                        | Por ejecución |
| `R2`     | General                            | Cero                        | Por ejecución |
| `R3`     | General                            | Cero                        | Por ejecución |
| `R4`     | General                            | Cero                        | Por ejecución |
| `R5`     | General                            | Cero                        | Por ejecución |
| `R6`     | General                            | Cero                        | Por ejecución |
| `R7`     | General                            | Cero                        | Por ejecución |
| `IP`     | Offset de la siguiente instrucción | Punto de entrada            | Por ejecución |
| `SP`     | Offset de la pila de llamadas      | Pila inicial                | Por ejecución |
| `FP`     | Base del frame actual              | Inicializado por el runtime | Por ejecución |
| `FL`     | Flags aritméticos                  | Cero                        | Por ejecución |

`IP`, `SP` y `FP` no son registros generales. Sus modificaciones se controlan por las instrucciones de flujo, las llamadas y el retorno. No pueden apuntar arbitrariamente a regiones privilegiadas.

Los registros generales no tienen alias de byte alto o bajo. Las operaciones sobre bytes utilizan instrucciones de carga y almacenamiento de byte, evitando los problemas de aliasado parcial que aparecen en arquitecturas antiguas.

## 7.2. Flags

`FL` contiene:

| Bit  | Nombre     | Significado                         |
| ---- | ---------- | ----------------------------------- |
| 0    | `Z`        | Resultado igual a cero              |
| 1    | `N`        | Bit más significativo del resultado |
| 2    | `C`        | Carry o borrow según la operación   |
| 3    | `V`        | Desbordamiento con signo            |
| 4–15 | Reservados | Deben permanecer en cero            |

Las instrucciones que no modifican flags dejan su valor intacto. Las instrucciones aritméticas y las comparaciones documentadas actualizan los flags.

La comparación calcula internamente una resta, actualiza los flags y descarta el resultado numérico. No requiere un registro dedicado al resultado de comparación.

## 7.3. Registros y estado privilegiado

El runtime mantiene fuera del banco visible:

- Identidad del programa.
- Versión de ISA.
- Identidad de la ejecución.
- Identidad del mensaje.
- Tabla de regiones y permisos.
- Presupuesto restante.
- Capacidades concedidas.
- Referencia al journal de modificaciones.
- Estado de cancelación.
- Profundidad máxima de llamadas.
- Último error fatal.

Estos valores no pueden leerse ni modificarse directamente mediante bytecode ordinario.

## 7.4. Convención de llamadas

- `R0`–`R3`: argumentos y resultados primarios.
- `R4`–`R7`: registros que una subrutina debe preservar si los modifica.
- `SP`: pila de llamadas privada.
- `FP`: base del frame actual.
- `CALL`: guarda la dirección de retorno.
- `RET`: restaura la dirección y el frame.
- Los servicios `HOST` tienen una convención de argumentos propia, especificada en el apartado 11.

La convención es una ABI, no una obligación del hardware físico. Puede evolucionar mediante versionado de la ABI.

# 8. Especificación formal de la ISA

## 8.1. Modelo de ejecución

El intérprete ejecuta el siguiente ciclo:

1. Verificar que `IP` apunte a una instrucción válida.
2. Comprobar el presupuesto mínimo para comenzar la instrucción.
3. Decodificar el opcode y sus operandos.
4. Validar los registros y los operandos codificados.
5. Calcular el coste de la operación.
6. Verificar memoria y privilegios cuando corresponda.
7. Ejecutar la instrucción.
8. Actualizar los flags, registros y memoria según su semántica.
9. Avanzar `IP`, salvo que la instrucción cambie el flujo.
10. Comprobar cancelación y límites de ejecución.

El decodificador trabaja con bytes sin signo. La conversión a un valor Java `byte` con signo nunca determina la semántica de una instrucción.

## 8.2. Conjunto inicial de instrucciones

La ISA v1 se divide en las siguientes familias. La tabla es normativa en cuanto al repertorio inicial; los opcodes concretos se fijan en el apartado 9.

| Familia                | Instrucciones                     | Semántica                                       |
| ---------------------- | --------------------------------- | ----------------------------------------------- |
| Constantes y registros | `LI`, `MOV`                       | Cargar constante y copiar registro              |
| Memoria                | `LDB`, `LDW`, `STB`, `STW`        | Cargar y almacenar bytes y palabras             |
| Memoria indirecta      | `LDBI`, `LDWI`, `STBI`, `STWI`    | Acceso a memoria usando la dirección de un registro (ISA v2) |
| Bloques                | `COPY`, `MOVE`                    | Copia no solapada y movimiento con solapamiento |
| Aritmética             | `ADD`, `SUB`, `MUL`, `DIV`, `MOD` | Operaciones enteras                             |
| Lógica                 | `AND`, `OR`, `XOR`, `NOT`         | Operaciones bit a bit                           |
| Desplazamiento         | `SHL`, `SHR`, `SAR`               | Desplazamientos lógico y aritmético             |
| Comparación            | `CMP`                             | Comparación y actualización de flags            |
| Flujo                  | `JMP`, `JZ`, `JNZ`, `JC`, `JV`    | Saltos condicionales e incondicionales          |
| Subrutinas             | `CALL`, `RET`                     | Llamada y retorno                               |
| Servicios              | `HOST`                            | Solicitud genérica al runtime                   |
| Control                | `HALT`, `TRAP`                    | Finalización y generación de excepción          |

`INC`, `DEC`, `SWAP`, `ROT`, `NOP` y las instrucciones atómicas no forman parte de la ISA inicial. Se pueden implementar mediante instrucciones existentes o reservarse para extensiones.

No existe una instrucción específica para cambiar el canal, consultar un permiso o escribir el nombre del jugador.

## 8.3. Tipos de operandos

- `REG`: índice de registro general, de 0 a 7.
- `IMM8`: constante de 8 bits.
- `IMM16`: constante de 16 bits.
- `TARGET`: destino de control de flujo dentro del programa.
- `REGION`: handle de una región autorizada.
- `OFFSET`: desplazamiento dentro de una región.

Las direcciones de memoria ordinarias se codifican como un handle y un offset, o se preparan mediante operandos de registro y convenciones de biblioteca. El handle no otorga permisos: cada acceso verifica el propietario y los permisos actuales.

## 8.4. Semántica aritmética

Sea \\(u\\) un entero de 16 bits. Las operaciones aritméticas de resultado de 16 bits producen el resultado modular.

\\[ \operatorname{wrap}\_{16}(x)=x\bmod 65536 \\]

Para `ADD`, `SUB` y `MUL`:

- El resultado se reduce a 16 bits.
- `Z` indica resultado cero.
- `N` indica el bit 15.
- `C` registra carry en suma y borrow en resta.
- `V` registra overflow con signo.

Para `DIV` y `MOD` se utiliza división entera con signo, con truncamiento hacia cero. La operación falla si el divisor es cero. El caso \\(-32768 / -1\\) produce overflow aritmético y una excepción definida, en lugar de depender del comportamiento de Java.

Para `SHL`, `SHR` y `SAR`, los desplazamientos de 0 a 15 son válidos. Un valor de desplazamiento fuera de ese intervalo genera una excepción de operando. No se aplicará silenciosamente una máscara al conteo.

`CMP` tiene modos de comparación con signo y sin signo. Estos modos se codifican mediante un campo del opcode extendido o una forma específica de instrucción; no se infieren del tipo Java del registro.

## 8.5. Semántica de memoria

`LDB` carga un byte y lo extiende con cero a 16 bits.

`LDW` carga una palabra de 16 bits en orden de bytes little-endian, independientemente del host.

`STB` escribe los ocho bits inferiores de un registro. `STW` escribe los 16 bits completos.

Las formas indirectas `LDBI`, `LDWI`, `STBI` y `STWI` (incorporadas en la ISA v2) toman
la dirección de memoria del valor de un registro general de 16 bits en lugar de un
offset constante: `LDBI Rd, [Rs]` carga desde la dirección contenida en `Rs` y
`STBI Rv, [Ra]` almacena en la dirección contenida en `Ra`. La carga de un byte o
palabra actualiza `Z` y `N`. Estas operaciones pasan por las mismas verificaciones de
permisos y límites que las formas directas.

Toda operación valida:

1. Existencia del handle.
2. Propiedad de la región.
3. Permiso de lectura o escritura.
4. Offset y longitud.
5. Ausencia de desbordamiento en `offset + length`.
6. Límite máximo de la región.

No se permiten lecturas parciales implícitas cuando una palabra cruza el final de una región. La operación falla sin modificar memoria.

## 8.6. Semántica de bloques

`COPY` exige que origen y destino no se solapen; si se solapan, produce una excepción de rango.

`MOVE` permite solapamiento y conserva la semántica de `memmove`: el resultado equivale a copiar desde una instantánea lógica del origen.

Ambas operaciones validan el rango completo antes de escribir. No producen escrituras parciales ante un error de validación.

Las operaciones se dividen en fragmentos internos para no monopolizar el intérprete. Su coste depende del número de bytes procesados y se carga al presupuesto antes de iniciar cada fragmento.

## 8.7. Saltos y subrutinas

Los saltos solo pueden dirigirse a límites válidos de instrucción dentro de la sección ejecutable del programa.

- `JMP`: siempre salta.
- `JZ`: salta si `Z = 1`.
- `JNZ`: salta si `Z = 0`.
- `JC`: salta si `C = 1`.
- `JV`: salta si `V = 1`.
- `CALL`: apila el retorno y transfiere el control.
- `RET`: restaura el retorno del frame actual.

Una dirección de salto inválida produce una excepción de ejecución. El validador también debe detectar saltos fuera del código y destinos que caigan dentro de los operandos de otra instrucción.

`HALT` termina la ejecución normal. No implica automáticamente confirmar los cambios del mensaje; esa decisión corresponde al contrato de terminación del programa y al runtime.

`TRAP` genera una excepción de programa con un código controlado. No permite saltar a direcciones arbitrarias de memoria.

## 8.8. Semántica de servicios

`HOST` solicita una operación registrada en el runtime. Sus argumentos se validan antes de invocar código Java.

El servicio devuelve un código de estado, un número de bytes transferidos cuando corresponda y un resultado definido por la ABI. Una operación rechazada no modifica el mensaje.

Los servicios que puedan modificar el mensaje deben escribir en el journal de la ejecución, no directamente en el objeto original, salvo que el contrato del host documente una operación no transaccional.

## 8.9. Excepciones de instrucciones

| Excepción          | Causa                                    | Recuperación                         |
| ------------------ | ---------------------------------------- | ------------------------------------ |
| `INVALID_OPCODE`   | Opcode desconocido                       | Abortar                              |
| `INVALID_OPERAND`  | Registro o modo inválido                 | Abortar                              |
| `MEMORY_FAULT`     | Acceso inválido                          | Abortar                              |
| `ARITHMETIC_FAULT` | División por cero u overflow excepcional | Abortar por defecto                  |
| `PRIVILEGE_FAULT`  | Operación no autorizada                  | Abortar                              |
| `HOST_ERROR`       | Servicio rechazado o fallido             | Recuperable solo si la ABI lo define |
| `OUT_OF_FUEL`      | Presupuesto agotado                      | Abortar obligatoriamente             |
| `STACK_LIMIT`      | Pila excedida                            | Abortar                              |
| `CANCELLED`        | Cancelación solicitada                   | Abortar                              |

La VM no captura automáticamente las excepciones de ejecución como si fueran condiciones normales. Una futura ISA podría incorporar manejadores de excepciones de usuario, pero no son necesarios para la primera versión.

# 9. Codificación del bytecode

## 9.1. Formato de instrucciones

Se recomienda una codificación mixta: instrucciones cortas para operaciones frecuentes y formas extendidas para operandos o constantes más grandes.

Todas las instrucciones comienzan con un opcode de 8 bits. El opcode determina exactamente la longitud y el formato del resto de la instrucción.

| Formato | Tamaño     | Uso                                          |
| ------- | ---------- | -------------------------------------------- |
| `RR`    | 2 bytes    | Operaciones entre registros                  |
| `R`     | 2 bytes    | Instrucciones con un registro                |
| `RI8`   | 3 bytes    | Registro y constante de 8 bits               |
| `RI16`  | 4 bytes    | Registro y constante de 16 bits              |
| `BR16`  | 3 bytes    | Salto relativo con desplazamiento de 16 bits |
| `HOST`  | 2 bytes    | Invocación genérica                          |
| `MEM`   | 4–6 bytes  | Handle, offset y registro, según operación   |
| `BLOCK` | 6–10 bytes | Regiones, offsets y longitud de bloque       |

Los desplazamientos de salto son relativos al final de la instrucción, con signo de 16 bits. El ensamblador debe rechazar destinos que no quepan en el rango representable; puede generar un salto largo mediante una secuencia válida de instrucciones.

El tamaño de una instrucción es determinado por su opcode, no por buscar un delimitador ni por inspeccionar sus bytes hasta encontrar una terminación.

## 9.2. Mapa de opcodes

Se reserva un espacio de 256 opcodes:

| Rango       | Uso                                                       |
| ----------- | --------------------------------------------------------- |
| `0x00–0x1F` | Transferencia de datos y memoria                          |
| `0x20–0x3F` | Aritmética y lógica                                       |
| `0x40–0x5F` | Comparación y flujo de control                            |
| `0x60–0x6F` | Servicios y terminación                                   |
| `0x70–0x7F` | Reservado para extensiones de control                     |
| `0x80–0xEF` | Reservado para extensiones versionadas                    |
| `0xF0–0xFF` | Inválidos en ISA v1, salvo definición explícita posterior |

Dentro de cada rango, el ensamblador y la especificación normativa asignarán identificadores concretos a los mnemónicos definidos. Los opcodes desconocidos deben rechazarse durante la validación.

No se permite reinterpretar silenciosamente un opcode reservado en un programa que declara una versión anterior.

## 9.3. Cabecera del programa

Cada archivo binario contiene, como mínimo:

- Firma del formato.
- Versión del formato binario.
- Versión de la ISA.
- Versión mínima de la ABI del host.
- Versión mínima del firmware estándar.
- Longitudes de las secciones.
- Punto de entrada.
- Tabla de símbolos opcional.
- Declaración de capacidades requeridas.
- Metadatos de depuración opcionales.
- Hash de integridad del contenido.

La cabecera y las secciones se validan antes de publicar el programa en el runtime. El hash garantiza integridad del artefacto según el mecanismo elegido; no demuestra que el programa sea seguro.

# 10. Modelo de memoria y representación de datos

## 10.1. Modelo de memoria plana con SoftMMU

El espacio de direcciones es plano de 16 bits (64 KB totales: $0x0000$–$0xFFFF$) con **paginación de 4 KB (16 páginas)** gestionada por un **SoftMMU** (Memory Management Unit por software).

| Página | Rango        | Uso típico                           | Permisos base          |
| ------ | ------------ | ------------------------------------ | ---------------------- |
| 0      | 0x0000–0x0FFF | Código: ROM de firmware (o código de usuario en bare-metal) | R / X |
| 1      | 0x1000–0x1FFF | Código: usuario (con firmware activado) | R / X               |
| 2      | 0x2000–0x2FFF | Constantes / datos de solo lectura   | R                      |
| 3–4    | 0x3000–0x4FFF | Datos privados / heap                | R / W                  |
| 5–6    | 0x5000–0x6FFF | Pila (crece hacia abajo desde 0x7000)| R / W                  |
| 7      | 0x7000–0x7FFF | Buffer de entrada (I/O)              | R / W                  |
| 8      | 0x8000–0x8FFF | Buffer de salida (I/O)               | R / W                  |
| 9–15   | 0x9000–0xFFFF | Datos privados / estado persistente  | Según capacidad        |

> **Actualización (D30–D32).** Las páginas 9–15 (0x9000–0xFFFF) son **RAM de usuario de propósito
> general** (R/W) que el runtime **nunca** limpia: la persistencia entre mensajes es implícita y es el
> programa quien decide cuándo reutilizarla. Además, la apertura MMIO de dispositivos vive en
> `0x0F00–0x0FFF`, **dentro de la página del BIOS**: `0x0F00–0x0F0F` es el banco de la pantalla (D31)
> y `0x0F10–0x0FFF` la NVRAM del BIOS (D32); el *interconnect* la decodifica antes que ROM/RAM. Fuente
> única: `VmMemoryMap`.

> El mapa es independiente del modo: la región de código reserva siempre dos
> páginas (ROM + usuario), y en bare-metal la página 1 queda sin usar. Así el
> código nunca solapa la página de constantes. Fuente única: `VmMemoryMap`.

- **PageTable**: 16 entradas, cada una = `frame_base` (16-bit) + `perms` (3-bit R/W/X) + `valid` + `user`
- **Traducción**: `virt_page = vaddr >> 12`, `offset = vaddr & 0xFFF`, `phys = frame_base + offset`
- **Permisos por página**: `READ` (0x1), `WRITE` (0x2), `EXECUTE` (0x4)
- **Métodos SoftMMU**: `readByte/Word`, `writeByte/Word`, `fetchByte/Word`, `mapPage`, `unmapPage`, `copy` (validación + copia)

No hay regiones, no hay handles, no hay búferes de entrada aislados. El programa interactúa directamente con la memoria plana mediante direcciones virtuales absolutas.

Los datos del mensaje, emisor, destinatario, canal y servidor no necesitan tener regiones permanentes. La interfaz del host materializa el valor solicitado en la dirección virtual indicada por el programa (ej.: "escribe la propiedad en el buffer de entrada `0x7000`").

## 10.2. Tamaños y límites

Los tamaños máximos deben configurarse por política y por runtime. Como valores iniciales de referencia para pruebas, no como límites universales:

- Espacio plano total: 64 KiB (16 páginas × 4 KB)
- Pila: 8 KiB (páginas 5–6, crece hacia abajo desde 0x7000)
- Buffer I/O individual: 4 KiB (1 página c/u: entrada en 0x7000, salida en 0x8000)
- Código: límite configurable por artefacto, con un perfil inicial de 256 KiB (requiere carga por fragmentos si supera 64 KB)
- Estado persistente: 64 KiB por instancia lógica (páginas 8–15, según capacidad `memory.persist`)

Estos valores son hipótesis operativas que deben validarse con programas representativos. Un mensaje más grande puede procesarse por fragmentos si el contrato del servicio lo permite.

El runtime debe imponer también un límite total agregado para todos los contextos y programas, de modo que multiplicar el límite por ejecución no permita agotar la memoria del servidor.

## 10.3. Cadenas y Unicode

La representación principal de cadenas dentro de los buffers será UTF-8, con longitud explícita y sin terminador obligatorio.

Cada cadena se representa mediante una secuencia de bytes y una longitud. No se presupone que un carácter ocupe un byte.

- Las operaciones de memoria cuentan bytes.
- Las operaciones de texto deben respetar límites de secuencias UTF-8.
- Una operación que necesita texto Unicode válido rechaza secuencias inválidas.
- Una operación puramente binaria puede manipular cualquier byte.
- La longitud máxima depende del servicio y de la región.
- La indexación por puntos de código requiere una rutina de biblioteca; no se confunde con el offset de bytes.

Java utiliza cadenas UTF-16 internamente, por lo que la conversión UTF-8/UTF-16 ocurre en el adaptador del host. Las conversiones deben tener límites de tamaño y no deben exponer referencias internas del objeto Java.

Las transformaciones de texto deben declarar si operan sobre bytes, puntos de código o unidades de texto. Esto evita resultados ambiguos con emojis, caracteres combinados y secuencias multibyte.

## 10.4. Copia y asignación

El gestor de memoria debe:

- Rechazar desbordamientos en `offset + length`.
- Validar el rango completo antes de escribir.
- Cobrar por los bytes procesados.
- Limitar el tamaño de una única operación.
- Permitir dividir operaciones grandes en fragmentos.
- Evitar que los buffers de una ejecución se conviertan en referencias a datos de otra.

No se requiere compactar la memoria como un heap físico. Las regiones se asignan y liberan de forma explícita y pueden respaldarse mediante arrays Java u otras estructuras internas sin alterar la semántica visible.

## 10.5. Caché de propiedades del host

La caché es local a la ejecución y está asociada al contexto del mensaje.

Puede evitar consultar varias veces una propiedad inmutable durante una misma evaluación. No debe asumirse que una propiedad modificable conserva su valor inicial después de una escritura en el journal.

La caché se invalida cuando cambia una propiedad afectada. Se descarta al finalizar la ejecución.

# 11. Interfaz de E/S y servicios del host

## 11.1. Contrato general de `HOST`

La ISA no contiene instrucciones para leer nombres, cambiar canales o consultar permisos. Contiene una instrucción que solicita servicios mediante una ABI genérica.

**Convención de registros `HOST` (ABI v2, estilo syscall):**

Entrada:
- `R0` = `op` (service ID, 16-bit)
- `R1` = `options` (parámetro del servicio: `(scope << 8) | propId`, decisión, key…)
- `R2` = `base` (vaddr del buffer de argumentos — dirección **completa**)
- `R3` = `length` (bytes; entrada: pedidos · salida: transferidos)
- `R4` reservado para el resultado escalar (extras, si los hubiera, desde `R5`)

Retorno:
- `R0` = `status` (código numérico, ver §11.2)
- `R1`, `R2` sin cambios
- `R3` = bytes transferidos (lecturas: bytes escritos por el emulador; escrituras: bytes consumidos)
- `R4` = resultado escalar del servicio (p. ej. `0|1` en PERMISSION_CHECK, la decisión en ROUTE_DECISION)

`length` es **bidireccional**. No existe operando `offset`: la base es la dirección completa. Para servicios de propiedad, `options` empaqueta `(scope << 8) | propId`, con `propId` en los 8 bits bajos (namespaceado por `scope`).

El opcode `HOST` (0x60) es de 2 bytes (opcode + padding). El service ID se pasa en R0, no en el bytecode.

El contrato concreto de cada servicio determina el significado de los campos. El opcode no cambia cuando se incorporan nuevos servicios.

### Servicios estándar implementados

| Service ID | Nombre | Capability requerida | Estado |
|------------|--------|----------------------|--------|
| 0x0100 | PROPERTY_READ | `property.read` | registrado |
| 0x0101 | PROPERTY_WRITE | `property.write` | registrado |
| 0x0102 | PROPERTY_EXISTS | `property.read` | declarado, no registrado |
| 0x0200 | PERMISSION_CHECK | `permission.check` | registrado |
| 0x0201 | PERMISSION_QUERY | `permission.check` | declarado, no registrado |
| 0x0300 | ROUTE_DECISION | `routing.decide` | registrado |
| 0x0301 | ROUTE_REDIRECT | `routing.decide` | declarado, no registrado |
| 0x0302 | ROUTE_RATE_LIMIT | `routing.decide` | declarado, no registrado |
| 0x0500 | STATE_READ | `state.read` | registrado |
| 0x0501 | STATE_WRITE | `state.write` | registrado |
| 0x0600 | CHANNEL_RESOLVE | `channel.resolve` | registrado |
| 0x0601 | CHANNEL_POLICY | `channel.resolve` | declarado, no registrado |

Un service ID no registrado produce `status = UNSUPPORTED`.

## 11.2. Estados de servicio

Los códigos de estado son parte de la ABI:

Los códigos se escriben en `R0` al retornar de `HOST`.

| Código | Estado             | Significado                                 |
| ------ | ------------------ | ------------------------------------------- |
| `0`    | `OK`               | Operación completada                        |
| `1`    | `NOT_FOUND`        | Propiedad o recurso inexistente             |
| `2`    | `DENIED`           | Operación no autorizada                     |
| `3`    | `INVALID_ARGUMENT` | Argumentos inválidos                        |
| `4`    | `BUFFER_TOO_SMALL` | Capacidad insuficiente (`R3` = bytes requeridos) |
| `5`    | `UNSUPPORTED`      | Operación no disponible                     |
| `6`    | `CONFLICT`         | Conflicto de versión o de estado            |
| `7`    | `HOST_FAILURE`     | Error controlado del host                   |
| `8`    | `PARTIAL`          | Resultado parcial permitido por el contrato |

La distinción entre un estado de error y una excepción de la VM es importante. Una propiedad inexistente puede ser un resultado normal que el programa evalúe. Una dirección inválida es un fallo de ejecución.

## 11.3. Lectura de una propiedad

El servicio genérico `PROPERTY_READ` recibe:

- El ámbito del objeto: mensaje, emisor, destinatario, canal u otro objeto autorizado.
- El identificador de propiedad.
- El formato solicitado.
- La región y la dirección del buffer de destino.
- La capacidad disponible.

El runtime devuelve:

- Estado.
- Longitud requerida o bytes transferidos.
- Tipo del valor.
- Indicador de truncamiento, si el contrato permite truncamiento.

La propiedad no se carga al comenzar el mensaje. Se resuelve al invocar el servicio.

Si la capacidad es insuficiente, el contrato debe elegir entre informar la longitud necesaria sin escribir, truncar de manera explícita o utilizar una operación de consulta de longitud. No se permite truncamiento silencioso.

## 11.4. Escritura de una propiedad

`PROPERTY_WRITE` recibe el ámbito, el identificador de propiedad, el tipo del valor y el buffer de entrada.

El runtime verifica:

1. Que la propiedad exista o admita creación.
2. Que el tipo sea compatible.
3. Que el valor cumpla los límites.
4. Que el programa tenga capacidad de escritura.
5. Que el actor que origina el mensaje no esté siendo confundido con la identidad administrativa del programa.
6. Que la escritura pueda incorporarse al journal.
7. Que el resultado final respete las invariantes de iflow.

El servicio devuelve un estado. La aceptación de una escritura en el journal no significa necesariamente que el mensaje original ya haya sido modificado.

## 11.5. Espacio de nombres de propiedades y servicios

Los identificadores deben pertenecer a espacios de nombres versionados. Un esquema conceptual:

- `message.content`
- `message.metadata.*`
- `actor.permissions.*`
- `recipient.metadata.*`
- `channel.*`
- `routing.*`

Estos nombres son identificadores de servicios o propiedades, no mnemónicos de la ISA.

El formato binario puede utilizar identificadores numéricos registrados para evitar analizar cadenas en cada invocación. Los nombres simbólicos se resuelven al cargar el programa.

Las propiedades desconocidas deben producir `NOT_FOUND` o `UNSUPPORTED`, no una excepción de decodificación ni una consulta reflexiva arbitraria sobre objetos Java.

## 11.6. Atomicidad y concurrencia del host

La interfaz debe declarar si cada servicio:

- Lee una instantánea estable.
- Lee el valor actual del host.
- Registra una escritura diferida.
- Realiza una operación inmediata.
- Puede fallar parcialmente.

Las operaciones de modificación de mensajes deben preferir el journal. Los servicios de consulta externa pueden ser inmediatos si no alteran estado.

Los servicios no pueden bloquear indefinidamente esperando un hilo del servidor. Si una API solo puede ejecutarse en el hilo principal, el runtime debe programar la operación según el contrato del host y definir si la evaluación se suspende o se rechaza. La primera versión debería evitar suspensiones arbitrarias de la VM.

# 12. Modelo de excepciones y privilegios

## 12.1. Dos dominios de autoridad

En lugar de reproducir los anillos x86, se distinguen dos dominios:

- Dominio de programa: ejecuta instrucciones, usa regiones autorizadas e invoca servicios declarados.
- Dominio de supervisor: valida capacidades, administra memoria, ejecuta servicios del host, confirma cambios y controla recursos.

El firmware estándar no obtiene acceso privilegiado simplemente por ser código llamado «firmware». La autoridad procede del runtime y de las capacidades explícitamente concedidas.

## 12.2. Matriz de privilegios

| Operación                                     | Programa                         | Supervisor/runtime                   |
| --------------------------------------------- | -------------------------------- | ------------------------------------ |
| Leer memoria privada                          | Sí                               | Sí                                   |
| Escribir memoria privada                      | Sí                               | Sí                                   |
| Modificar código ejecutable                   | No                               | Cargador autorizado                  |
| Acceder a otro contexto                       | No                               | Solo mediante interfaces controladas |
| Modificar tablas de servicios                 | No                               | Sí                                   |
| Invocar servicios permitidos                  | Sí                               | Sí                                   |
| Invocar servicios privilegiados sin capacidad | No                               | Según autorización                   |
| Cambiar configuración del runtime             | No                               | Administración                       |
| Modificar el mensaje                          | Solo mediante journal autorizado | Sí                                   |
| Confirmar cambios                             | No directamente                  | Sí                                   |
| Entregar mensajes                             | No                               | Dispatcher                           |
| Consultar permisos                            | Solo servicio autorizado         | Sí                                   |
| Modificar el presupuesto                      | No                               | Supervisor                           |

## 12.3. Capacidades

Cada programa declara las capacidades que necesita, pero la declaración no concede autoridad por sí sola.

Durante la carga, el administrador y el runtime determinan cuáles se conceden. En cada ejecución se construye un conjunto de capacidades efectivas limitado por:

- Capacidades aprobadas al instalar el programa.
- Configuración del runtime.
- Ámbito de ejecución.
- Permisos del actor, cuando la operación dependa del jugador o emisor.
- Restricciones de la propiedad o servicio.

Las comprobaciones sensibles se repiten en el límite del servicio. No se confía en que el compilador o el validador hayan eliminado todas las operaciones ilegales.

## 12.4. Excepciones y recuperación

Una excepción no recuperable termina la ejecución y descarta el journal. El runtime registra el motivo y devuelve un resultado de fallo controlado al router.

El servidor no debe recibir una excepción fatal causada por un programa de usuario. Los errores internos del runtime se registran separadamente y no se disfrazan como un fallo normal de política.

La pila y los registros se descartan tras una excepción. El estado persistente solo se conserva si el contrato define que la operación fallida no lo modificó o si su actualización ya se confirmó mediante un procedimiento consistente.

No se habilita inicialmente un vector de interrupciones modificable por programas. Las excepciones se representan mediante códigos internos y rutas de error controladas.

> **Actualización (D31–D32).** El firmware dejó de ser pasivo: con el firmware ON, la máquina resetea
> en una rutina de BIOS (`fw_reset`) que hace POST, configura el hardware (pantalla por MMIO) y cede
> el control al `main`. Una **tabla de interrupciones configurable por el BIOS** (no por el programa
> de usuario) y **fuentes de interrupción por E/S** están previstas en D33; el despacho sigue estando
> bajo control del runtime, no de un vector arbitrario del programa. Ver `docs/workdir/PLAN.md`,
> revisión 2026-10-17 (2), y `src/iflow/HDL.md`.

# 13. Firmware y bibliotecas estándar

## 13.1. Qué significa firmware en esta arquitectura

El firmware es una capa de convenciones, rutinas reutilizables y servicios estándar. No es un BIOS de hardware ni un kernel completo.

> **Actualización (D32).** En la implementación actual el firmware ROM es **activo**: además de la
> capa de convenciones (stdlib y ABI), el punto de entrada de una imagen con firmware es su rutina de
> reset (`fw_reset`), que ejecuta un POST mínimo —limpia la RAM volátil y configura la pantalla por
> MMIO— y encadena con el `main` del usuario. Sigue **sin ser un kernel completo**: no hay
> planificación, ni aislamiento entre programas, ni servicios privilegiados propios del firmware. La
> publicación de una tabla de IRQ (D33) completará esta capa. Ver `src/iflow/HDL.md` §8 y
> `docs/workdir/PLAN.md`.

Sus responsabilidades son:

- Definir el punto de entrada.
- Inicializar el contexto.
- Establecer convenciones de llamadas.
- Registrar las bibliotecas estándar compatibles.
- Proporcionar rutinas reutilizables.
- Definir códigos de error y convenciones de retorno.
- Verificar compatibilidad con la ABI.
- Preparar el programa para ejecución.

La creación de regiones, la asignación de capacidades y la inicialización de la tabla de servicios siguen perteneciendo al runtime privilegiado.

## 13.2. Bibliotecas

| Biblioteca   | Funciones                                 | Implementación recomendada          |
| ------------ | ----------------------------------------- | ----------------------------------- |
| `mem`        | Copia, movimiento, comparación binaria    | Primitivas del runtime y wrappers   |
| `text`       | Longitudes, búsqueda, comparación Unicode | Rutinas controladas                 |
| `string`     | Construcción y concatenación acotada      | Bytecode y servicios de memoria     |
| `property`   | Lectura y escritura genéricas             | Wrappers sobre `HOST`               |
| `permission` | Consulta de permisos                      | Servicio del host                   |
| `routing`    | Construcción de decisiones válidas        | Wrapper sobre servicios autorizados |
| `transform`  | Solicitud de transformaciones             | Host y journal                      |
| `state`      | Lectura y escritura persistente           | Host con cuotas                     |
| `regex`      | Coincidencias y extracción                | Runtime con límites estrictos       |

No es obligatorio implementar todas las bibliotecas como bytecode. Las operaciones costosas o difíciles de acotar pueden ser primitivas del runtime, siempre que su semántica esté documentada y no otorguen acceso directo a objetos Java.

## 13.3. Expresiones regulares

Las expresiones regulares requieren un tratamiento especial.

La primera versión debe usar un motor con comportamiento acotado, preferiblemente basado en autómatas para el subconjunto soportado. Si se utiliza un motor cuya complejidad puede ser exponencial, debe existir una defensa específica que limite el patrón, la entrada y el trabajo realizado.

Límites necesarios:

- Longitud máxima del patrón.
- Longitud máxima de entrada.
- Número máximo de grupos y construcciones admitidas.
- Memoria máxima del motor.
- Presupuesto específico de regex.
- Rechazo de construcciones no soportadas.
- Resultado explícito cuando se agota el presupuesto.

No basta con cobrar una unidad de gas por invocar una regex. El coste debe reflejar el trabajo realizado, o la operación debe tener un límite interno estricto.

## 13.4. Carga y versionado

Las bibliotecas se cargan y validan al registrar el programa o preparar el runtime. Las rutinas inmutables pueden compartirse entre ejecuciones.

El firmware declara su versión y las bibliotecas disponibles. Un programa incompatible no debe empezar a ejecutarse para fallar después al invocar un servicio inexistente.

# 14. Protocolo de inicialización y diagnóstico

## 14.1. Secuencia de arranque

1. Crear el runtime.
2. Validar la configuración de memoria y recursos.
3. Registrar la tabla de servicios.
4. Comprobar versiones de ABI y firmware.
5. Validar y cargar las bibliotecas.
6. Verificar la integridad de las imágenes de programa.
7. Ejecutar pruebas de autodiagnóstico de bajo coste.
8. Publicar el programa como disponible.
9. Aceptar evaluaciones.

No se necesita un loopback obligatorio. Un programa de eco puede ser útil para comprobar el camino de entrada y salida de una operación concreta, pero no demuestra que el resto de la ISA funcione correctamente.

## 14.2. Niveles de diagnóstico

| Nivel              | Frecuencia          | Pruebas                                          |
| ------------------ | ------------------- | ------------------------------------------------ |
| Inicialización     | Una vez por runtime | Configuración, servicios, ABI y memoria          |
| Carga del programa | Por artefacto       | Formato, opcodes, saltos y capacidades           |
| Desarrollo         | Bajo demanda        | Pruebas exhaustivas, trazas y casos límite       |
| Producción         | Según configuración | Contadores, errores, límites y salud del runtime |

Las pruebas exhaustivas de aritmética y decodificación no deben ejecutarse por cada mensaje.

El diagnóstico de producción debe evitar exponer datos sensibles del mensaje. Las trazas pueden registrar offsets, opcodes, códigos de error y costes sin volcar el contenido completo.

# 15. Estado persistente y concurrencia

## 15.1. Modelo de estado

La persistencia de registros o memoria debe convertirse en una capacidad formal denominada, provisionalmente, persistent execution state.

Se distinguen cuatro ámbitos:

1. Transitorio: registros, pila, buffers y variables de una ejecución.
2. Estado de mensaje: journal, caché y resultados temporales.
3. Estado de instancia de programa: datos que pueden sobrevivir entre mensajes para una instancia lógica determinada.
4. Estado global del runtime: configuración, bibliotecas y estructuras administrativas.

El estado transitorio se reinicializa siempre. El estado de mensaje se descarta al finalizar. El estado de instancia solo se conserva mediante el mecanismo autorizado.

## 15.2. Persistencia controlada

La arquitectura puede permitir que registros y datos privados sobrevivan entre ejecuciones, pero no debe hacerlo accidentalmente por reutilizar un contexto del pool.

Para habilitar esa función se requiere:

- Un ámbito explícito de persistencia.
- Una identidad estable de la instancia lógica.
- Una declaración de capacidad.
- Una cuota de memoria.
- Una política de inicialización.
- Una política de expiración.
- Una política de acceso.
- Una estrategia de concurrencia.
- Un procedimiento de migración de versión.
- Una regla de recuperación ante corrupción.

La pila nunca se conserva. Tampoco se conservan automáticamente los permisos efectivos, los buffers del mensaje ni los handles temporales del host.

Los registros persistentes no forman parte de la ABI general de las subrutinas: son estado de instancia controlado por el runtime. Su conservación debe ser explícita en el descriptor del programa y no una consecuencia de qué trabajador del pool atendió el mensaje anterior.

## 15.3. Riesgo de contaminación entre mensajes

Permitir que una política conserve estado puede ser útil para contadores, ventanas de observación o algoritmos de routing stateful. También puede permitir que información de un mensaje afecte a otro.

Por eso, la clave de estado debe estar definida por el administrador: por ejemplo, por programa y ámbito configurado, no por un identificador de jugador elegido arbitrariamente por el bytecode.

El runtime debe impedir que el programa cambie el ámbito de su estado sin autorización.

## 15.4. Concurrencia

La primera versión debe ejecutar secuencialmente las operaciones que modifican una misma instancia lógica de estado.

Si dos mensajes intentan modificar ese estado simultáneamente, las opciones son:

- Serializar la ejecución por instancia.
- Usar control optimista de versiones y rechazar conflictos.
- Aislar las instancias si no necesitan compartir estado.

La recomendación inicial es serialización por instancia persistente. Es más fácil de verificar que permitir escrituras concurrentes arbitrarias sobre registros y memoria compartidos.

Los mensajes independientes pueden procesarse en paralelo mediante trabajadores Java, cada uno con su propio contexto.

No se implementa paralelismo dentro de una misma VM. No se justifican aún múltiples núcleos virtuales ni un scheduler interno: añadirían sincronización y semánticas de memoria compartida sin una necesidad demostrada.

# 16. Integración con el router y el dispatcher de iflow

## 16.1. Contrato arquitectónico

Se conserva el contrato conceptual:

`Router.route(Message, Actor) -> RouteOutcome`

La VM no reemplaza al dispatcher. Se integra como motor de políticas invocado por el router.

La arquitectura recomendada es:

- `VmPolicyEngine`: ejecuta el programa y produce un resultado intermedio.
- `VmExecutionContext`: contiene el estado privado de la evaluación.
- `HostServiceRegistry`: proporciona acceso autorizado a servicios.
- `PolicyResultValidator`: valida la decisión y el journal.
- `DefaultRouter`: mantiene la coordinación con las reglas y el ciclo de routing.
- `MessageDispatcher`: expande destinatarios y entrega el mensaje.
- `RateLimiter` y `PermissionChecker`: continúan siendo servicios del host.

El motor de políticas no debe llamar directamente al dispatcher ni decidir por su cuenta en qué hilo se entrega un mensaje.

## 16.2. Representación del resultado

El resultado intermedio debe incluir:

- Estado de ejecución.
- Decisión de routing.
- Transformaciones pendientes.
- Journal de modificaciones.
- Diagnóstico no sensible.
- Consumo de recursos.
- Referencia de versión del programa.

La decisión de routing utiliza un conjunto cerrado de variantes:

| Decisión           | Semántica                                               |
| ------------------ | ------------------------------------------------------- |
| `LOG`              | Registrar o continuar según la semántica existente      |
| `DROP`             | No entregar                                             |
| `REJECT`           | Rechazar de acuerdo con el contrato actual              |
| `REDIRECT`         | Solicitar redirección validada                          |
| `CHANNEL_REDIRECT` | Solicitar cambio de canal validado                      |
| `RATE_LIMIT`       | Aplicar la semántica de limitación definida por el host |
| `CONTINUE`         | Continuar el flujo normal, si el contrato lo permite    |

`CONTINUE` es un valor interno del nuevo modelo, no una nueva decisión pública obligatoria. Debe mapearse a la semántica real del router existente.

Los valores inválidos se rechazan. No se convierten automáticamente en `CONTINUE`, porque eso podría permitir que un programa defectuoso eluda una política de seguridad.

## 16.3. Rate limiting y permisos

El orden debe preservar los dos niveles existentes:

- El rate limiting de emisión se evalúa una vez por mensaje, antes del fan-out.
- El rate limiting asociado a una regla se evalúa en el ámbito de destinatario que corresponda a esa regla.

La VM no debe duplicar estas comprobaciones si el router ya las realiza.

Las políticas pueden solicitar consultas o decisiones mediante servicios. El runtime conserva la autoridad sobre la identidad del actor, la clave del rate limiter y el momento de aplicación.

Una regla que requiera un permiso no debe sustituir `PermissionChecker` por una implementación propia que acceda directamente a las APIs del host.

## 16.4. Migración de reglas

La migración debe realizarse en tres modalidades:

1. Compatibilidad: el motor existente continúa ejecutando reglas que todavía no se han migrado.
2. Traducción parcial: reglas simples y de semántica equivalente se convierten a bytecode.
3. Programa nativo: políticas complejas se escriben para la VM.

Las reglas SpEL no deben traducirse automáticamente cuando sus efectos secundarios, orden de evaluación, tratamiento de errores o semántica de valores nulos difieran.

Durante la transición debe ser posible ejecutar pruebas de comparación en modo sombra: ambos motores evalúan el mismo caso, pero solo uno produce efectos. Las divergencias se registran para su revisión.

La activación de un programa debe ser atómica respecto a la publicación de su nueva versión. Un mensaje no debe observar a mitad de su evaluación una mezcla de dos versiones de programa.

# 17. Presupuesto de ejecución y protección de recursos

## 17.1. Fuel

Cada ejecución recibe un presupuesto finito de unidades de trabajo, denominado `fuel`.

El contador es privado de la ejecución. El programa no puede incrementarlo, reiniciarlo ni transferirlo a otra ejecución.

El coste se descuenta antes de realizar una operación que pueda consumir recursos. Cuando el coste no se puede calcular de antemano, se descuenta de manera incremental.

## 17.2. Costes iniciales propuestos

Los valores siguientes son unidades de diseño para el prototipo, no mediciones de tiempo ni constantes universales.

| Operación                        | Fuel inicial                                       |
| -------------------------------- | -------------------------------------------------- |
| Instrucción aritmética simple    | 1                                                  |
| Comparación o salto              | 1                                                  |
| Carga o almacenamiento ordinario | 2                                                  |
| Llamada o retorno                | 2                                                  |
| Invocación `HOST`                | 10 de base, más coste del servicio                 |
| Copia de memoria                 | 1 por cada bloque de hasta 16 bytes                |
| Conversión de texto              | Base más coste por bytes procesados                |
| Consulta de propiedad            | Base más coste según tamaño y operación            |
| Regex                            | Coste medido por el motor o presupuesto específico |
| Asignación de memoria            | Base más coste proporcional al tamaño              |

Los costes deben calibrarse mediante benchmarks. Su función es limitar trabajo, no predecir exactamente los nanosegundos de una operación.

## 17.3. Operaciones costosas

Una instrucción `HOST` no puede eludir el presupuesto ejecutando una operación nativa de coste ilimitado.

Cada servicio debe declarar:

- Coste fijo de entrada.
- Coste variable.
- Límites de entrada y salida.
- Posibles asignaciones.
- Condiciones de cancelación.
- Si puede bloquearse o delegar trabajo.

Para un servicio de copia, el coste se calcula por bytes. Para regex, el runtime debe limitar el trabajo interno o utilizar un algoritmo de complejidad acotada. Para servicios que no puedan detenerse con seguridad, la operación debe ser estrictamente limitada o no exponerse a programas de usuario.

## 17.4. Límites independientes

Fuel no sustituye a:

- Cuota de memoria.
- Límite de pila.
- Límite de profundidad de llamadas.
- Longitud máxima de entrada.
- Límite de buffers.
- Número máximo de servicios.
- Cuota de estado persistente.
- Control de concurrencia.
- Límite de tiempo para operaciones externas.

El agotamiento de fuel provoca la terminación obligatoria de la ejecución y el descarte del journal. No existe una excepción recuperable que permita al programa continuar sin presupuesto.

# 18. Rendimiento y estrategia de optimización

## 18.1. Implementación inicial

La primera implementación debe ser un intérprete sencillo, con un bucle de despacho explícito y un conjunto reducido de instrucciones.

Prioridades:

1. Validación del bytecode al cargar.
2. Contextos de ejecución reutilizables.
3. Buffers reutilizables y acotados.
4. Servicios del host con contratos compactos.
5. Evitar asignaciones por instrucción.
6. Evitar conversiones repetidas de cadenas.
7. Caché de propiedades dentro de la evaluación.
8. Medición del coste de cada familia de instrucciones.

No se recomienda introducir inicialmente un JIT, un formato de microcódigo, SIMD ni aceleración mediante GPU.

## 18.2. Hipótesis de rendimiento

La arquitectura puede ser eficiente si las políticas hacen suficiente trabajo local y las consultas al host no dominan el tiempo total.

Puede ser ineficiente si una política necesita efectuar muchas llamadas pequeñas al host, convierte cadenas repetidamente o ejecuta una gran cantidad de instrucciones para reproducir operaciones que el runtime ya realiza de manera optimizada.

Por ello, la ISA debe seguir siendo pequeña, pero la ABI debe permitir operaciones genéricas por lotes cuando estén justificadas. Una operación por lotes debe mantener semántica genérica; no debe convertirse en una instrucción especial por propiedad.

## 18.3. Benchmark obligatorio

La comparación debe incluir al menos:

- Motor actual con reglas simples.
- Motor actual con condiciones complejas.
- VM con programa transparente.
- VM con consultas al host.
- VM con transformaciones de texto.
- VM con búsquedas y bucles.
- VM con estado persistente.
- VM con errores y agotamiento de fuel.

Métricas:

- Throughput.
- Latencia p50, p95 y p99.
- Asignaciones por mensaje.
- Bytes asignados por mensaje.
- Tiempo de CPU.
- Coste de llamadas al host.
- Coste de inicialización y validación.
- Coste de reutilización de contextos.
- Contención del estado persistente.

Las pruebas deben usar cargas equivalentes y medir también la presión sobre el hilo principal de Bukkit/Spigot cuando corresponda.

No debe afirmarse que la VM es más rápida que las reglas existentes sin mediciones reproducibles.

# 19. Formato de programas y herramientas de desarrollo

## 19.1. Ensamblador

El ensamblador debe soportar:

- Mnemónicos y operandos tipados.
- Etiquetas.
- Directivas de secciones.
- Constantes numéricas y cadenas.
- Declaración de capacidades.
- Símbolos de servicios.
- Resolución de saltos.
- Verificación de rangos.
- Generación de metadatos.
- Errores con número de línea y ubicación.

La sintaxis exacta puede evolucionar, pero debe mantener la distinción entre código, datos, constantes y declaraciones de capacidades.

## 19.2. Formato binario

El artefacto compilado incluye la cabecera versionada, las secciones, el punto de entrada y las capacidades declaradas. El formato debe permitir rechazar programas incompatibles antes de su publicación.

No se deben ejecutar artefactos que declaren capacidades desconocidas o que dependan de una ABI no soportada.

## 19.3. Herramientas

La primera versión del ecosistema debe incluir:

- Ensamblador.
- Desensamblador.
- Validador estático.
- Verificador de compatibilidad.
- Trazador de ejecución.
- Inspector de registros.
- Inspector de regiones de memoria.
- Informe de consumo de fuel.
- Diagnóstico de servicios.
- Ejecutor de pruebas reproducibles.

La depuración debe poder detenerse en un límite de instrucción, pero esa capacidad pertenece a las herramientas de desarrollo y no debe convertirse en una vía de suspensión arbitraria en producción.

## 19.4. Lenguaje de nivel superior

La ISA debe ser adecuada como objetivo de compilación. No es necesario decidir ahora la sintaxis de un lenguaje de políticas de alto nivel.

La posibilidad de compilar reglas o un lenguaje posterior a bytecode debe evaluarse después de cerrar la semántica de la máquina y construir un validador fiable.

# 20. Programas de ejemplo completos

Los ejemplos siguientes definen un dialecto de ensamblador de referencia. Utilizan instrucciones de la ISA descrita y rutinas estándar identificadas mediante contratos explícitos. No representan todavía archivos binarios compilados.

Para hacer los ejemplos legibles, se emplean símbolos como `PROPERTY_READ` y `ROUTE_DROP`. El ensamblador resuelve estos símbolos a identificadores de servicios registrados. No son opcodes adicionales.

Convenciones de los ejemplos:

- `R0`–`R7` son registros generales.
- `HOST` invoca un servicio mediante la ABI definida.
- `HALT` termina la evaluación.
- `JZ` y `JNZ` consumen los flags establecidos por una operación previa.
- `CALL` y `RET` utilizan la pila privada.
- Los buffers y handles se preparan mediante el cargador o rutinas estándar.
- El runtime valida todas las decisiones antes de aplicarlas.

## Ejemplo A. Paso transparente

El programa no modifica el mensaje y solicita la continuación normal del flujo.

```
.entry main

main:
    LI   R0, ROUTE_CONTINUE
    HOST
    HALT
```

`ROUTE_CONTINUE` solicita que iflow continúe el procesamiento normal. No implica entrega inmediata ni omite las comprobaciones del router.

El runtime verifica que el resultado es válido y no encuentra modificaciones que confirmar.

## Ejemplo B. Descarte de mensaje

```
.entry main

main:
    LI   R0, PROPERTY_READ
    ; R1 identifica el descriptor de la propiedad
    ; R2 contiene el offset del buffer
    HOST

    ; La biblioteca compara el valor leído
    ; y establece el resultado de comparación.
    CALL check_discard_condition
    JNZ discard
    JMP continue

discard:
    LI   R0, ROUTE_DROP
    HOST
    HALT

continue:
    LI   R0, ROUTE_CONTINUE
    HOST
    HALT

check_discard_condition:
    ; Cuerpo de la rutina definido por la biblioteca
    RET
```

El fragmento muestra la estructura de control, pero `check_discard_condition` requiere una implementación concreta para el tipo de dato y la condición elegida. No se considera un programa compilable hasta que esa rutina y sus argumentos estén definidos.

## Ejemplo C. Restricción por permisos

La comprobación de permisos corresponde al host.

```
.entry main

main:
    LI   R0, PERMISSION_CHECK
    ; Descriptor: identidad del actor y permiso requerido
    ; La identidad procede del contexto autorizado.
    HOST

    ; El resultado se interpreta según la ABI:
    ; autorizado o denegado.
    ; La rama se implementa usando el código de estado
    ; devuelto por el servicio.
    CALL interpret_permission_result
    HALT

interpret_permission_result:
    ; Si autorizado:
    LI   R0, ROUTE_CONTINUE
    HOST
    RET
```

Este ejemplo es estructural: el contrato de `PERMISSION_CHECK` debe especificar el estado exacto de retorno y la rutina `interpret_permission_result` debe cubrir ambos casos antes de que el programa se considere terminado. El bytecode no puede falsificar la respuesta del `PermissionChecker`.

## Ejemplo D. Transformación de texto

La transformación utiliza un buffer temporal. El siguiente es el flujo requerido:

```
.entry main

main:
    ; 1. Leer el contenido en el buffer de trabajo.
    LI   R0, PROPERTY_READ
    HOST

    ; 2. Ejecutar la transformación sobre memoria privada.
    CALL transform_text

    ; 3. Registrar la escritura diferida.
    LI   R0, PROPERTY_WRITE
    HOST

    ; 4. Solicitar continuación.
    LI   R0, ROUTE_CONTINUE
    HOST
    HALT

transform_text:
    ; La rutina debe validar el rango, la codificación
    ; y la capacidad antes de modificar el buffer.
    RET
```

La rutina concreta depende de la transformación. El runtime no confirma el cambio al objeto original hasta validar y confirmar el journal.

## Ejemplo E. Búsqueda de subcadena

Esta rutina demuestra un bucle de búsqueda sobre una región de memoria. `LDB`, `CMP`, `JZ`, `JMP` y `RET` son instrucciones de la ISA.

```
; Entrada:
; R1 = región de texto
; R2 = offset inicial
; R3 = longitud del texto
; R4 = byte buscado
; Salida:
; R0 = offset encontrado o 0xFFFF

find_byte:
    LI   R0, 0xFFFF
    LI   R5, 0

loop:
    CMP  R5, R3
    JZ   not_found

    LDB  R6, [R1:R2+R5]
    CMP  R6, R4
    JZ   found

    ; Avanzar al siguiente byte.
    ADD  R5, R5, 1
    JMP  loop

found:
    MOV  R0, R5
    RET

not_found:
    RET
```

La notación de memoria mostrada es azúcar sintáctico del ensamblador: debe traducirse a las instrucciones y operandos de dirección admitidos. Si la ISA v1 no incluye una forma indexada, el ensamblador debe generar el cálculo del offset mediante instrucciones válidas. No se puede asignar al dialecto una instrucción de carga indexada que no exista en el mapa de opcodes.

El bucle está limitado por `R3`, y el runtime aplica además el presupuesto de ejecución.

## Ejemplo F. Modificación de canal

```
.entry main

main:
    ; Leer el canal actual si la política lo necesita.
    LI   R0, PROPERTY_READ
    HOST

    ; Solicitar un cambio mediante el servicio genérico
    ; de escritura de propiedades.
    LI   R0, PROPERTY_WRITE
    HOST

    ; Solicitar la decisión correspondiente.
    LI   R0, ROUTE_CHANNEL_REDIRECT
    HOST

    HALT
```

El programa solicita una modificación y una decisión. El runtime debe validar que el destino existe, que el actor y la política pueden usarlo y que el resultado es coherente con el contrato de iflow. La modificación no se confirma si la evaluación falla.

## Ejemplo G. Estado entre mensajes

La persistencia debe realizarse mediante el servicio de estado, no dejando registros de un contexto del pool sin inicializar.

```
.entry main

main:
    ; Leer el contador de la instancia lógica.
    LI   R0, STATE_READ
    HOST

    ; La ABI devuelve el valor o NOT_FOUND.
    CALL increment_counter

    ; Escribir el nuevo valor en el journal de estado.
    LI   R0, STATE_WRITE
    HOST

    LI   R0, ROUTE_CONTINUE
    HOST
    HALT

increment_counter:
    ; El cuerpo concreto debe comprobar el valor,
    ; el overflow y la cuota del estado.
    RET
```

`STATE_READ` y `STATE_WRITE` solo están disponibles cuando el programa posee la capacidad correspondiente. La actualización se serializa por instancia o utiliza control de versiones.

## Ejemplo H. Agotamiento de presupuesto

```
.entry main

loop:
    JMP loop
```

El programa nunca termina por su propio flujo de control. El supervisor agota el fuel, genera `OUT_OF_FUEL`, descarta el journal y devuelve el contexto al pool únicamente si sigue siendo seguro reutilizarlo.

## Ejemplo I. Fallo durante una modificación

```
.entry main

main:
    LI   R0, PROPERTY_WRITE
    HOST

    ; Provocar una excepción controlada.
    TRAP 0x01

    HALT
```

La primera operación registra una modificación pendiente. La excepción posterior impide confirmar el journal. El mensaje original permanece sin modificar, siempre que el servicio de escritura haya respetado el contrato transaccional.

## Ejemplo J. Propiedad desconocida para la ISA

```
.entry main

main:
    ; PROPERTY_READ es un servicio genérico.
    ; El identificador simbólico de la propiedad se
    ; resuelve en el descriptor de argumentos.
    LI   R0, PROPERTY_READ
    HOST

    ; La propiedad puede incorporarse al registro de
    ; servicios sin añadir un opcode al procesador.
    HALT
```

El ejemplo demuestra la separación entre ISA y dominio, pero el descriptor de argumentos y el buffer deben inicializarse antes de ejecutar el programa. Si la propiedad no está registrada o no es visible para el actor, el servicio devuelve el estado correspondiente.

## 20.1. Condición de aceptación de los ejemplos

Los ejemplos anteriores validan la estructura arquitectónica, pero no todos son todavía programas compilables completos. Las rutinas auxiliares, descriptores de argumentos y algunas convenciones de comparación necesitan cerrarse en la especificación de la ABI y del ensamblador.

Antes de declarar terminada la fase de diseño, el equipo debe convertir estos ejemplos en fixtures compilables y ejecutables. No sería correcto presentar pseudocódigo incompleto como prueba de conformidad de la ISA.

# 21. Plan de pruebas de conformidad

El objetivo de las pruebas es garantizar que dos implementaciones de la ISA produzcan los mismos resultados observables, aunque internamente utilicen estructuras de datos o estrategias de optimización diferentes.

## 21.1. Pruebas de la ISA

| Suite            | Casos obligatorios                               | Criterio de aceptación                           |
| ---------------- | ------------------------------------------------ | ------------------------------------------------ |
| Decodificación   | Cada opcode válido, desconocido y truncado       | Resultado definido sin lecturas fuera de límites |
| Aritmética       | Cero, máximos, mínimos y overflow                | Resultado idéntico al modelo formal              |
| División         | Divisor cero y casos extremos con signo          | Excepciones y resultados exactos                 |
| Comparaciones    | Con signo y sin signo                            | Flags correctos                                  |
| Desplazamientos  | Conteos 0, 15 y valores inválidos                | Semántica determinista                           |
| Flujo de control | Saltos, llamadas, retornos y límites             | Ningún salto a código inválido                   |
| Memoria          | Región inexistente, permisos y límites           | Rechazo seguro                                   |
| Bloques          | Copias solapadas y no solapadas                  | Semántica documentada                            |
| Unicode          | Secuencias válidas e inválidas                   | Conversión y errores consistentes                |
| Servicios        | Éxito, error, truncamiento y denegación          | ABI respetada                                    |
| Privilegios      | Acceso a región y servicio no autorizados        | Sin elevación de privilegios                     |
| Fuel             | Bucle infinito y operación costosa               | Terminación dentro de límites                    |
| Aislamiento      | Ejecuciones sucesivas con contextos reutilizados | Sin contaminación accidental                     |
| Persistencia     | Reinicio, expiración y conflictos                | Estado conforme a la política                    |
| Atomicidad       | Error después de una escritura                   | Sin modificaciones parciales confirmadas         |
| Concurrencia     | Accesos simultáneos al estado                    | Semántica de serialización respetada             |

## 21.2. Pruebas basadas en propiedades

Se recomienda generar automáticamente secuencias de instrucciones válidas y comparar sus resultados con un modelo de referencia sencillo.

Ejemplos:

- `MOV` conserva exactamente el valor del registro fuente.
- `LDB` siempre produce un valor entre 0 y 255.
- `STB` no modifica los siete bytes vecinos ni ningún otro dato fuera de su destino.
- `MOVE` produce el mismo resultado que una copia de instantánea cuando los rangos se solapan.
- Una escritura fallida no modifica el journal.
- Una ejecución sin capacidad no puede completar un servicio protegido.
- Un programa que agota el fuel no puede confirmarse como una ejecución normal.
- El contexto devuelto al pool no contiene registros, pila ni buffers transitorios del mensaje anterior.

La implementación de referencia debe priorizar claridad y corrección sobre rendimiento.

## 21.3. Ejecución diferencial

Cada programa de prueba debe ejecutarse en:

1. El intérprete de referencia.
2. La implementación optimizada, cuando exista.
3. El desensamblador y validador del formato.
4. El runtime de integración, para las pruebas que utilizan servicios del host.

Se comparan los registros observables, el estado final de memoria autorizada, los errores, el fuel consumido según el modelo y el resultado de routing.

## 21.4. Pruebas de carga

Los perfiles de carga deben incluir:

- Mensajes pequeños y políticas transparentes.
- Mensajes de varios kilobytes.
- Reglas con muchas consultas de propiedades.
- Transformaciones de texto.
- Búsquedas y bucles.
- Regex.
- Estado persistente con contención.
- Alto volumen de mensajes independientes.
- Ejecuciones que terminan por agotamiento de presupuesto.

No se establece un objetivo numérico de mensajes por segundo sin medir primero el entorno de referencia.

# 22. Registro de decisiones arquitectónicas

Esta tabla es el registro normativo de las decisiones propuestas para la versión inicial.

| Decisión           | Alternativas                                        | Recomendación                              | Confianza |
| ------------------ | --------------------------------------------------- | ------------------------------------------ | --------- |
| Ancho de palabra   | 16 / 32 bits                                        | 16 bits, con memoria regional              | Media     |
| Direccionamiento   | Plano / segmentado / regiones                       | Handles y offsets regionales               | Alta      |
| Codificación       | Fija / variable / mixta                             | Mixta, determinada por opcode              | Media     |
| Registros          | Banco pequeño / ampliado                            | Ocho GPR y registros de control definidos  | Media     |
| Alias de registros | Mitades alta/baja / sin alias                       | Sin alias                                  | Alta      |
| Servicios          | Vectores / llamadas directas / instrucción genérica | `HOST` y tabla versionada                  | Alta      |
| Privilegios        | Rings / capacidades / permisos                      | Supervisor del runtime y capacidades       | Alta      |
| Firmware           | BIOS / microkernel / biblioteca                     | Firmware mínimo                            | Alta      |
| Persistencia       | Implícita / explícita                               | Explícita y desactivada por defecto        | Alta      |
| Ejecución          | VM por mensaje / compartida / pool                  | Programa compartido y pool de contextos    | Alta      |
| Concurrencia       | Intérprete secuencial / multihilo interno           | Concurrencia administrada por Java         | Alta      |
| Estado compartido  | Libre / serializado / versionado                    | Serialización por instancia inicialmente   | Alta      |
| Atomicidad         | Escritura inmediata / diferida                      | Journal y confirmación                     | Alta      |
| Presupuesto        | Timeout / instrucciones / fuel                      | Fuel más límites por recurso               | Alta      |
| Regex              | Motor general / autómata acotado                    | Motor con complejidad y recursos limitados | Alta      |
| Optimización       | Intérprete / JIT / microcódigo                      | Intérprete primero                         | Alta      |
| Formato binario    | Sin versión / versionado                            | Formato e ISA versionados                  | Alta      |
| Compatibilidad     | Sustitución inmediata / migración                   | Adaptador y migración gradual              | Alta      |

## 22.1. Decisiones que deben someterse a revisión experimental

Hay cuatro áreas que no deberían considerarse cerradas sin prototipos:

A. Ancho de 16 bits. La memoria regional soluciona el límite de direccionamiento, pero puede aumentar el número de instrucciones y los costes de manejo de direcciones. Debe compararse con una variante de 32 bits utilizando los mismos programas y el mismo intérprete de referencia.

B. Coste de `HOST`. La instrucción genérica aporta extensibilidad, pero demasiadas llamadas pequeñas al host pueden dominar la latencia. Deben medirse las consultas de propiedades, la caché y las operaciones por lotes.

C. Persistencia de registros. Puede ser útil para programas stateful, pero añade complejidad de versionado, aislamiento y recuperación. Debe implementarse después de validar el modelo transitorio.

D. Atomicidad. El journal reduce el riesgo de dejar mensajes parcialmente modificados, pero introduce memoria temporal y un protocolo de confirmación. Debe medirse con transformaciones reales y definirse con precisión qué sucede si falla una actualización externa durante el commit.

## 22.2. Invariantes arquitectónicos no negociables

1. Ningún bytecode puede acceder directamente a un objeto Java.
2. Ningún contexto de ejecución puede escribir directamente en la memoria privada de otro.
3. La pila nunca se comparte entre mensajes.
4. El presupuesto de ejecución nunca puede reiniciarse desde el programa.
5. Los servicios validan sus argumentos y capacidades.
6. Las decisiones de routing se validan antes de llegar al dispatcher.
7. El código ejecutable y las tablas privilegiadas no son escribibles por programas de usuario.
8. Un error de programa no puede producir una entrega parcial por un camino no autorizado.
9. La persistencia se administra mediante un ámbito explícito.
10. Las optimizaciones deben conservar la semántica de la ISA.
11. Una versión incompatible del programa se rechaza antes de publicarse.
12. Las operaciones costosas deben tener un coste acotado o un mecanismo efectivo de cancelación.

# 23. Riesgos, limitaciones y cuestiones pendientes

## 23.1. Riesgos principales

Crítico

Inconsistencia transaccional

Una operación de host puede modificar un recurso antes de que otra operación falle. El journal debe definir qué operaciones son diferidas, cuáles son irreversibles y cómo se manejan los fallos durante la confirmación.

Crítico

Persistencia con fuga de información

Si una instancia conserva registros o memoria sin una identidad y ámbito claros, un mensaje puede influir en otro. La persistencia necesita un modelo de autoridad y aislamiento propio.

Alto

Coste de la ISA de 16 bits

El modelo regional es viable, pero el coste real de gestionar handles y offsets debe medirse frente a una ISA de 32 bits.

Alto

Bloqueo indirecto mediante servicios

Aunque el intérprete tenga fuel, un servicio Java bloqueante puede retener un hilo. Cada servicio necesita límites operativos, no solo un coste de entrada.

Alto

Complejidad del formato y la ABI

Una ISA pequeña no garantiza una ABI sencilla. El descriptor de argumentos, la semántica de las propiedades, los errores y las capacidades deben ser uniformes.

## 23.2. Limitaciones del diseño inicial

- El direccionamiento regional requiere una convención de acceso y gestión de handles bien definida.
- La VM no es automáticamente más rápida que SpEL o que las reglas actuales.
- Las operaciones del host pueden dominar el coste total.
- El estado persistente introduce problemas de concurrencia y migración.
- El journal no garantiza atomicidad global sobre sistemas externos que no participen en la transacción.
- Una ISA portable no garantiza idéntico rendimiento en diferentes JVM.
- Las reglas complejas existentes pueden no ser traducibles automáticamente.
- Los ejemplos de la sección 20 todavía requieren fixtures compilables y una ABI cerrada.

## 23.3. Cuestiones que requieren información adicional del proyecto

Antes de implementar el adaptador definitivo deben verificarse en el código actual de TextFormatter Suite:

1. La semántica exacta de `RouteOutcome` y de cada `PolicyTarget`.
2. El orden real de evaluación de reglas y condiciones.
3. El momento en que se aplican las transformaciones.
4. El ciclo de vida del objeto `Message` durante la evaluación y la entrega.
5. Los puntos exactos en que se comprueban los permisos.
6. Las claves y los ámbitos de los dos niveles de rate limiting.
7. Las restricciones de hilo de los servicios Bukkit, Spigot y Fabric.
8. Los requisitos de compatibilidad de las reglas y configuraciones existentes.

No es correcto afirmar que el nuevo motor conserva todos los contratos hasta verificar estas semánticas en la implementación real.

# 24. Hoja de ruta de implementación por fases

La implementación debe avanzar en incrementos verificables. Cada fase debe dejar el sistema en un estado coherente, sin introducir prematuramente características que dificulten el diagnóstico de errores.

## 01

### Cerrar la especificación normativa

Dependencias: ninguna · Prioridad máxima

Objetivo: resolver los formatos de instrucción, ABI, semántica numérica, regiones y resultados de routing.

Componentes: documentos de ISA, ABI, formato binario y contratos de integración.

Pruebas: revisión de contradicciones, ejemplos ensamblables y casos límite de cada instrucción.

Aceptación: no existen operandos, códigos de estado, excepciones o transiciones de control sin definir.

Riesgos de regresión: introducir semánticas incompatibles con el router actual.

Fuera de alcance: implementación del intérprete, optimizaciones y persistencia avanzada.

## 02

### Modelo de referencia y formato binario

Dependencia: fase 01

Objetivo: establecer una representación normativa de instrucciones y programas.

Componentes: especificación de opcode, codificador de prueba, decodificador de referencia y fixtures binarios.

Pruebas: instrucciones válidas, truncadas, opcodes desconocidos y destinos inválidos.

Aceptación: cada instrucción tiene una codificación única y una longitud determinable.

Riesgos de regresión: divergencia entre ensamblador y decodificador.

Fuera de alcance: servicios del host, runtime de producción y optimizaciones.

## 03

### Intérprete mínimo

Dependencia: fase 02

Objetivo: ejecutar la ISA fundamental sin acceso al host.

Componentes: banco de registros, fetch/decode/execute, aritmética, memoria básica y saltos.

Pruebas: suite completa de aritmética, flujo de control, límites y excepciones.

Aceptación: el intérprete coincide con el modelo de referencia para todas las pruebas deterministas.

Riesgos de regresión: diferencias de signo, overflow o actualización de flags.

Fuera de alcance: persistencia, routing y concurrencia.

## 04

### Memoria protegida y supervisor

Dependencia: fase 03

Objetivo: completar el modelo regional y la protección de memoria.

Componentes: gestor de regiones, cuotas, pila, permisos y excepciones.

Pruebas: rangos inválidos, overflow de offsets, escrituras cruzadas y agotamiento de recursos.

Aceptación: ninguna operación puede modificar una región fuera de sus permisos.

Riesgos de regresión: errores de límites y asignaciones no contabilizadas.

Fuera de alcance: memoria compartida entre programas y JIT.

## 05

### Fuel y terminación segura

Dependencia: fase 04

Objetivo: imponer límites a instrucciones, bloques y servicios.

Componentes: supervisor de presupuesto, límites de pila, cuotas de memoria y cancelación.

Pruebas: bucles infinitos, operaciones costosas y agotamiento durante transformaciones.

Aceptación: ninguna ejecución puede continuar después de agotar su presupuesto ni reiniciarlo por sí misma.

Riesgos de regresión: operaciones del runtime cuyo coste no se contabilice.

Fuera de alcance: persistencia y paralelismo.

## 06

### ABI y servicios del host

Dependencias: fases 04 y 05

Objetivo: permitir lecturas y escrituras controladas.

Componentes: `HOST`, registro de servicios, descriptores de argumentos, capacidades y journal.

Pruebas: propiedades ausentes, buffers insuficientes, permisos, errores y escrituras diferidas.

Aceptación: ningún servicio permite acceso directo a objetos Java y todos validan argumentos y capacidades.

Riesgos de regresión: bloqueos de hilo y diferencias entre la semántica declarada y la real del host.

Fuera de alcance: acceso arbitrario a red, filesystem o ejecución de código nativo.

## 07

### Ensamblador, bibliotecas y ejemplos ejecutables

Dependencias: fases 02–06

Objetivo: convertir la ISA en una plataforma utilizable.

Componentes: ensamblador, desensamblador, bibliotecas, diagnósticos y fixtures.

Pruebas: compilación de todos los ejemplos, round-trip de desensamblado y ejecución reproducible.

Aceptación: cada ejemplo aceptado puede ensamblarse, validarse y ejecutarse con resultados esperados.

Riesgos de regresión: divergencia entre sintaxis, ABI y formato binario.

Fuera de alcance: lenguaje de alto nivel y compilación JIT.

## 08

### Adaptador de routing y compatibilidad

Dependencias: fases 06 y 07

Objetivo: integrar la VM sin sustituir el dispatcher ni alterar accidentalmente las semánticas existentes.

Componentes: `VmPolicyEngine`, validador de resultados, journal y adaptadores del router.

Pruebas: equivalencia de decisiones, rate limiting, permisos, transformaciones y errores.

Aceptación: las políticas de prueba producen los resultados esperados y las decisiones inválidas se rechazan.

Riesgos de regresión: duplicación de controles o cambios en el orden de evaluación.

Fuera de alcance: retirar el motor antiguo sin una migración verificada.

## 09

### Pool de contextos y persistencia controlada

Dependencia: fase 08

Objetivo: reutilizar recursos sin compartir accidentalmente estado transitorio.

Componentes: pool acotado, limpieza de contextos, almacén de estado, serialización y cuotas.

Pruebas: reutilización después de éxito y error, contaminación entre mensajes, concurrencia y reinicio.

Aceptación: el estado transitorio queda limpio; el estado persistente solo sobrevive según su ámbito declarado.

Riesgos de regresión: fugas de información y condiciones de carrera.

Fuera de alcance: estado global mutable sin aislamiento y scheduler interno.

## 10

### Evaluación de rendimiento y endurecimiento

Dependencias: fases 01–09

Objetivo: decidir si la VM cumple los objetivos del proyecto y qué optimizaciones están justificadas.

Componentes: benchmarks, perfiles, métricas, pruebas de carga y auditoría de seguridad.

Pruebas: perfiles representativos, comparación con el motor actual, latencia p50/p95/p99 y asignaciones.

Aceptación: resultados reproducibles, límites verificados y ausencia de regresiones funcionales conocidas.

Riesgos de regresión: optimizaciones que alteren semántica o reduzcan el aislamiento.

Fuera de alcance: JIT, paralelismo interno o cambios de ISA sin evidencia.

# Dictamen final

La propuesta de un CPU virtual para iflow es técnicamente viable, y el enfoque de una ISA pequeña con acceso genérico al host es coherente con el objetivo de permitir lógica compleja sin convertir el procesador en un catálogo de propiedades del mensaje.

Sin embargo, el diseño no debería pasar directamente a implementación completa. Antes deben cerrarse tres contratos:

1. ISA y formato binario: todos los operandos, códigos de operación, excepciones, flags y reglas de direccionamiento deben quedar normativamente definidos.
2. ABI del host: las lecturas, escrituras, capacidades, errores y operaciones transaccionales deben tener semántica precisa.
3. Contrato de routing: el resultado de la VM debe mapearse sin ambigüedad a los comportamientos actuales de iflow.

La primera implementación debe demostrar corrección, aislamiento y terminación acotada antes de optimizar el rendimiento.

La persistencia de registros y memoria puede convertirse en una característica potente, pero solo después de implementar un contexto transitorio limpio, un almacén de estado con ámbitos explícitos y un protocolo de recuperación. No debe surgir accidentalmente del mecanismo de reutilización.

Por último, el criterio para considerar exitosa la arquitectura no es que se parezca a un procesador físico ni que ejecute muchas instrucciones. Es que permita expresar políticas que resulten difíciles de mantener con el motor actual, con una semántica verificable, una integración limpia con el host y un coste de ejecución medido que justifique su adopción.

La decisión de construirla debe quedar condicionada a una prueba de concepto que compare el intérprete de referencia con el motor actual en corrección, latencia, asignaciones y complejidad de las políticas. Esa evidencia determinará si conviene conservar la ISA de 16 bits propuesta o cambiarla antes de fijar la primera versión estable.
