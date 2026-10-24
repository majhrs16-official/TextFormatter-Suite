# DISEÑO ARQUITECTÓNICO INTEGRAL DEL CPU VIRTUAL DE IFLOW

> **Nota de estado (2026-10-20):** documento histórico de diseño. La migración descrita ya se completó: SpEL fue eliminado del repositorio y la VM `.flow` es la única superficie de políticas ejecutables. Las menciones a SpEL son contexto de partida, no estado actual.

## Especificación técnica de la ISA, el modelo de ejecución, la memoria, el firmware y la integración con el motor de enrutamiento de mensajes

### 1. Objetivo y rol

Actúa como un arquitecto sénior de procesadores, máquinas virtuales, instruction set architectures (ISA), runtimes de alto rendimiento, sistemas embebidos y motores de políticas programables.

Tu misión es diseñar desde cero una arquitectura de procesador virtual para **iflow**, un motor de enrutamiento y políticas de mensajes perteneciente a TextFormatter Suite.

No quiero una descripción conceptual superficial, una lista arbitraria de instrucciones ni una arquitectura de juguete. Necesito una especificación técnica suficientemente precisa para que otro agente de programación pueda implementar posteriormente el procesador, su ensamblador, su firmware, su entorno de ejecución y su integración con iflow sin tener que inventar las decisiones arquitectónicas fundamentales.

El resultado debe ser un documento de diseño de ingeniería: coherente, verificable, extensible, seguro y orientado al rendimiento.

**No implementes todavía el procesador. Diseña primero su arquitectura completa.**

No presupongas que las propuestas iniciales incluidas en este documento son necesariamente correctas. Analízalas, identifica sus contradicciones y reemplázalas cuando exista una alternativa técnicamente superior.

---

# 2. Contexto funcional de iflow

## 2.1. Qué es iflow

iflow es un firewall y router declarativo de mensajes. Decide si un mensaje debe entregarse, cómo debe modificarse, cuándo debe entregarse y a qué destinatarios debe dirigirse.

Forma parte de una arquitectura modular que separa responsabilidades:

- `iflow-vm`: políticas, condiciones, transformaciones de routing, control de entrega y rate limiting.
- `textformatter`: formato y renderizado de mensajes.
- `gtranslate` / `ltranslate`: traducción.
- `sync-*`: sincronización con servicios y plataformas externas.
- `MessageDispatcher`: expansión de destinatarios y entrega efectiva.
- `PermissionChecker`: abstracción de permisos suministrada por el host.
- `ChannelRegistry`: resolución de canales.
- `PlaceholderResolver`: resolución de placeholders cuando esté disponible.

El núcleo debe seguir siendo agnóstico respecto a Bukkit, Spigot, Fabric y sus APIs específicas.

La nueva arquitectura pretende sustituir progresivamente el modelo de reglas y expresiones por una máquina virtual programable, capaz de ejecutar lógica compleja sobre mensajes y sobre el contexto del entorno.

## 2.2. Arquitectura actual de referencia

Las clases principales actuales son:

| Componente | Responsabilidad actual |
|---|---|
| `DefaultRouter` | Evalúa reglas por destinatario y genera resultados de routing. |
| `Router` | Contrato del router. |
| `Rule` | Matchers, condiciones, acciones, transforms y disposición final. |
| `ScriptSurface` | Superficie de API segura para operaciones de consulta y modificación. |
| `TransformOp` | Transformaciones atómicas de mensajes. |
| `PolicyTarget` | `LOG`, `DROP`, `REJECT`, `REDIRECT`, `CHANNEL_REDIRECT`, `RATE_LIMIT`. |
| `RateLimiter` | Limitación de frecuencia mediante token bucket. |
| `PermissionChecker` | Integración de permisos con el host. |
| `MessageDispatcher` | Distribución de mensajes entre destinatarios y entrega final. |

Actualmente existen reglas ordenadas por prioridad, condiciones SpEL restringidas, acciones y transformaciones. La implementación debe poder evolucionar hacia una VM sin que el CPU absorba responsabilidades que pertenecen al router, al dispatcher o a otros módulos.

La arquitectura nueva puede reemplazar la implementación interna del motor de políticas, pero debe preservar las responsabilidades y los contratos externos que sigan siendo necesarios.

No asumas que todas las clases actuales deben conservarse intactas. Determina cuáles deben mantenerse como adaptadores, cuáles deben evolucionar y cuáles podrían desaparecer.

---

# 3. La idea fundamental del procesador

La idea consiste en construir un procesador virtual de propósito general, especializado por su entorno de ejecución, no un procesador que contenga una instrucción diferente para cada propiedad de un mensaje.

La diferencia es esencial.

No quiero una instrucción como `SET_PLAYER_NAME`, otra como `SET_CHANNEL`, otra como `SET_MESSAGE_COLOR` y cientos de instrucciones equivalentes.

Quiero un conjunto de instrucciones pequeño y ortogonal que permita implementar esas operaciones mediante código ejecutable, bibliotecas y servicios del firmware.

El procesador debe permitir:

- Aritmética y operaciones lógicas.
- Comparaciones y saltos condicionales.
- Bucles y llamadas a subrutinas.
- Manipulación de bits y bytes.
- Lectura y escritura de memoria.
- Copia y movimiento de bloques de memoria.
- Variables temporales y estructuras de datos.
- Procesamiento de cadenas.
- Comparación de rangos de texto.
- Implementación de expresiones regulares mediante software.
- Acceso controlado al mensaje, al emisor, al destinatario y al contexto del servidor.
- Modificación de propiedades expuestas por el entorno.
- Ejecución de reglas stateful cuando exista estado explícitamente autorizado.
- Selección de acciones de routing.
- Control de entrega, cancelación y transformaciones.
- Programación de lógica arbitrariamente compleja dentro de límites de recursos estrictos.

La complejidad debe residir principalmente en el software ejecutado por la máquina virtual, no en una ISA repleta de operaciones específicas de iflow.

Una instrucción nueva debe justificarse únicamente cuando exista una ventaja técnica medible o una necesidad semántica que no pueda resolverse limpiamente mediante instrucciones existentes y servicios controlados.

---

# 4. Modelo mental: CPU, memoria, bus y firmware

Diseña iflow como una máquina virtual embebida con una interfaz explícita hacia el entorno anfitrión.

Como modelo conceptual inicial, considera los siguientes componentes:

1. **CPU Core:** ejecuta el ciclo de búsqueda, decodificación y ejecución de instrucciones.
2. **Banco de registros:** contiene operandos, punteros, contadores, estado y registros de control.
3. **Espacio de direcciones:** representa código, datos, pila, memoria temporal y estructuras necesarias para la ejecución.
4. **Interfaz de E/S:** permite consultar o modificar objetos del entorno Java sin tener que materializar de antemano todas sus propiedades en la memoria de la VM.
5. **Bus lógico de servicios:** transporta solicitudes entre la CPU y el entorno anfitrión.
6. **Firmware:** proporciona rutinas de inicialización, convenciones de llamadas, bibliotecas y preparación del entorno.
7. **Controlador de interrupciones o servicios:** despacha solicitudes al firmware y gestiona excepciones.
8. **Runtime anfitrión:** integra la VM con el ciclo de vida de iflow y con el sistema de mensajes.
9. **Supervisor de recursos:** impone límites de ejecución, memoria, llamadas al host y consumo de recursos.
10. **Programas de usuario:** implementan las políticas concretas de routing y transformación.

Esta lista representa componentes conceptuales, no una obligación de implementar diez subsistemas separados. Puedes fusionar componentes si ello simplifica la arquitectura sin comprometer sus propiedades.

Determina qué componentes deben existir realmente, cuáles son abstracciones lógicas y cuáles serían complejidad innecesaria.

No construyas un kernel de sistema operativo completo si el problema puede resolverse con un supervisor mínimo, un firmware pequeño y un runtime anfitrión.

Tampoco conviertas automáticamente cada concepto de una computadora física en una abstracción de software. Justifica qué conceptos tienen utilidad real en una VM implementada sobre Java.

---

# 5. Principios arquitectónicos obligatorios

La especificación debe respetar estos principios.

## 5.1. Agnosticismo

La ISA no debe depender de x86, ARM, AVX, AES-NI, instrucciones específicas de una GPU ni extensiones propietarias de procesadores físicos.

La implementación de la VM puede aprovechar optimizaciones de la JVM cuando resulte conveniente, pero el comportamiento de los programas debe ser independiente de la arquitectura física.

Separa rigurosamente:

- La ISA lógica.
- Su implementación en Java.
- Las optimizaciones específicas del runtime.
- Los servicios expuestos por iflow.

No introduzcas microcódigo como requisito inicial. Evalúa su utilidad real, pero parte de una implementación convencional de intérprete.

## 5.2. Generalidad

El procesador no debe conocer semánticamente cada propiedad del mensaje.

Por ejemplo, una política que modifica el canal debe poder implementarse mediante una llamada al servicio de escritura de una propiedad, no mediante una instrucción exclusiva para cambiar canales.

Sin embargo, no fuerces absolutamente todas las operaciones a convertirse en secuencias de instrucciones elementales si una primitiva de runtime resulta sustancialmente más eficiente y conserva una interfaz genérica.

Distingue entre:

- Instrucciones fundamentales de la ISA.
- Servicios genéricos de E/S.
- Rutinas del firmware.
- Bibliotecas de usuario.
- Optimizaciones internas que no cambian la semántica observable.

## 5.3. Integración con el host

La VM debe poder consultar propiedades bajo demanda.

No debe ser obligatorio copiar el objeto `Message` completo ni todas las propiedades del emisor, destinatario, canal o servidor a la memoria virtual antes de ejecutar cada programa.

Un programa debe poder consultar únicamente las propiedades que necesite.

La interfaz debe soportar tanto lecturas como escrituras autorizadas.

## 5.4. Persistencia selectiva

La arquitectura debe permitir conservar una instancia de la máquina virtual entre múltiples evaluaciones, si ello resulta conveniente para reducir overhead.

Debe distinguirse entre:

- Estado permanente del programa o de su instancia.
- Estado privado de una ejecución.
- Estado de un mensaje concreto.
- Estado temporal de una transformación.
- Estado del firmware.
- Estado global compartido.
- Estado del host al que se accede mediante servicios.

Si permite que la reutilización de la VM implique accidentalmente que los registros, y los datos de memoria de un mensaje anterior se filtren al siguiente, exceptuando la pila, o los permisos. Esto es para permitir logica compleja en intra mensajes, pero deberia convertirse en feature controlado, no en una brecha de seguridad descontrolada.

Así puedes permitir corrupción deliberada de la memoria privada de un programa como parte de su modelo de ejecución, sin permitir que esa corrupción alcance las estructuras privilegiadas del runtime.

## 5.5. Seguridad

El administrador debe poder escribir programas con gran capacidad expresiva sin obtener acceso arbitrario al proceso Java, a la JVM, al sistema de archivos, a la red o a las APIs del servidor.

No se debe ejecutar Java, JavaScript, C, C++ ni código nativo arbitrario como sustituto de la VM.

El sistema debe ofrecer límites verificables para instrucciones, memoria, llamadas al host, profundidad de llamadas, estado persistente y concurrencia.

La memoria virtual no debe proporcionar por sí sola acceso a objetos Java reales. La única vía de acceso al host debe ser una interfaz explícita y controlada.

---

# 6. Decisión crítica: procesador de 16 o 32 bits

Analiza la decisión de ancho de palabra sin asumir que un direccionamiento de 32 bits obliga a reservar físicamente 4 GiB por instancia.

Compara al menos estas alternativas:

- ISA de 16 bits con direcciones de 16 bits.
- ISA de 16 bits con espacio de direcciones extendido o segmentado.
- ISA de 16 bits con registros de propósito general y registros auxiliares más anchos.
- ISA de 32 bits con memoria asignada dinámicamente.
- Otra alternativa si existe una ventaja clara y justificable.

Evalúa:

1. Coste de implementación en Java.
2. Complejidad del intérprete.
3. Tamaño de las instrucciones.
4. Número de registros y presión sobre ellos.
5. Capacidad de direccionamiento.
6. Mensajes de hasta varios kilobytes.
7. Contexto de un mensaje y sus metadatos.
8. Memoria temporal para modificaciones atómicas.
9. Buffers de cadenas y expresiones regulares.
10. Pila de llamadas.
11. Bibliotecas cargadas.
12. Estado persistente.
13. Potencial de crecimiento de las funciones.
14. Rendimiento y localidad de memoria.
15. Facilidad de depuración y análisis de bytecode.
16. Compatibilidad futura.

Considera como referencia que un mensaje típico de chat de Minecraft suele ser pequeño y que los buffers iniciales podrían ocupar decenas de kilobytes, pero no conviertas estas cifras en restricciones rígidas sin justificación.

Distingue entre ancho de registro, ancho de dirección, tamaño de los operandos y capacidad máxima de memoria.

Si recomiendas 16 bits, explica cómo resolverás el problema de superar el espacio de direcciones de 64 KiB, si fuera necesario.

Si recomiendas 32 bits, explica por qué la flexibilidad adicional compensa sus costes reales y por qué no debe confundirse con reservar 4 GiB de memoria.

Presenta una recomendación principal y las condiciones concretas que justificarían cambiarla.

---

# 7. Diseño completo del banco de registros

Diseña el banco de registros desde cero.

No copies automáticamente los registros propuestos por una IA anterior.

Como punto de partida se ha planteado un banco pequeño con:

- Acumulador.
- Registros de propósito general.
- Punteros de memoria.
- Puntero de pila.
- Instruction pointer.
- Registro de flags.
- Registro de control de la política.
- Registros para transferencias de E/S.

Determina cuántos registros se necesitan realmente y qué función cumple cada uno.

Para cada registro especifica:

- Nombre.
- Ancho en bits.
- Propósito.
- Instrucciones que lo leen.
- Instrucciones que lo modifican.
- Si es visible para los programas.
- Si debe preservarse durante una llamada.
- Si tiene alias de byte alto o bajo.
- Su valor inicial.
- Su comportamiento tras excepciones.
- Si forma parte del contexto de ejecución persistente.

Evalúa si la segmentación de registros en mitades alta y baja aporta suficiente utilidad o solo introduce complejidad.

Si la mantienes, define exactamente cómo se leen y escriben los alias, incluyendo qué ocurre al modificar un byte sin alterar el otro.

No reutilices nombres como `AH`, `AL`, `CH` o `CL` de forma ambigua entre registros diferentes.

Evita registros dedicados a propiedades concretas del mensaje cuando estas puedan representarse mediante memoria, operandos o resultados de servicios genéricos.

Separa los registros de propósito general de los registros privilegiados del runtime.

---

# 8. Diseño formal de la ISA

Este es uno de los apartados más importantes.

Define una ISA mínima, ortogonal y suficientemente expresiva.

No quiero únicamente una lista de nombres. Cada instrucción debe tener una semántica formal e inequívoca.

## 8.1. Clases de instrucciones a evaluar

Como mínimo, analiza la necesidad de estas familias:

### Transferencia de datos

- Cargar una constante.
- Copiar entre registros.
- Leer memoria.
- Escribir memoria.
- Cargar y almacenar bytes.
- Cargar y almacenar palabras.
- Copiar bloques.
- Mover bloques con solapamiento seguro.
- Intercambiar registros, si aporta valor real.

### Aritmética y lógica

- Suma y resta.
- Multiplicación y división, si se justifican.
- AND, OR y XOR.
- NOT.
- Desplazamientos lógicos.
- Desplazamientos aritméticos, si se necesitan.
- Rotaciones, si aportan valor real.
- Incremento y decremento, si no son redundantes.

### Comparación y control de flujo

- Comparaciones con y sin signo.
- Saltos incondicionales.
- Saltos condicionales.
- Bucles.
- Llamadas a subrutinas.
- Retorno.
- Terminación normal.
- Instrucción de espera o cesión, solo si existe un modelo de planificación que la necesite.

### Control y servicios

- Invocación de servicios del host.
- Retorno de servicios.
- Gestión de errores.
- Instrucciones de control privilegiado.
- Operaciones de interrupción, si son realmente necesarias.

### Memoria y concurrencia

- Acceso a memoria temporal.
- Gestión de pila.
- Primitivas de sincronización, solo si el modelo de concurrencia las requiere.
- Operaciones atómicas, únicamente si su semántica y utilidad están justificadas.

No es obligatorio implementar todas estas instrucciones. Debes justificar cuáles forman parte de la ISA inicial, cuáles pueden implementarse mediante software y cuáles deben reservarse para una futura extensión.

## 8.2. Especificación individual

Para cada instrucción propuesta, documenta:

1. Mnemónico.
2. Opcode o mecanismo de codificación.
3. Formato de operandos.
4. Longitud en bytes o mecanismo para determinarla.
5. Tipos de operandos.
6. Efecto exacto sobre los registros.
7. Efecto sobre los flags.
8. Efecto sobre la memoria.
9. Comportamiento ante direcciones inválidas.
10. Comportamiento ante operandos inválidos.
11. Privilegio requerido.
12. Consumo de gas o unidades de ejecución.
13. Si puede interrumpirse o cancelarse.
14. Ejemplo de ensamblador.
15. Ejemplo de bytecode cuando sea pertinente.

Define formalmente las operaciones de salto, comparación, división, desplazamiento, desbordamiento aritmético y acceso a memoria.

No permitas que el comportamiento dependa accidentalmente de detalles de Java, como el signo de `byte`, el desbordamiento de enteros o la conversión implícita de tipos.

Especifica el modelo de enteros, incluyendo operaciones con signo y sin signo cuando proceda.

## 8.3. Codificación de instrucciones

Compara formatos de instrucción de longitud fija, longitud variable y formatos mixtos.

Evalúa la relación entre tamaño del bytecode, simplicidad del decodificador, coste del intérprete y facilidad de validación.

Propón una codificación concreta y deja espacio suficiente para futuras extensiones.

No asignes opcodes arbitrariamente sin documentar sus rangos y reservas.

---

# 9. Modelo de memoria

Diseña un modelo de memoria completo y explícito.

No presupongas que todo el espacio de direcciones debe respaldarse mediante un único array del tamaño máximo direccionable.

Considera memoria asignada bajo demanda, regiones separadas, buffers, espacios lógicos y otras alternativas.

Como mínimo, evalúa estas regiones:

- Firmware y rutinas de arranque.
- Código ejecutable.
- Constantes de solo lectura.
- Memoria de datos del programa.
- Pila de llamadas.
- Memoria temporal.
- Buffers de entrada y salida.
- Datos asociados al mensaje.
- Metadatos del emisor.
- Metadatos del destinatario.
- Datos del canal.
- Registros de control de routing.
- Estado persistente autorizado.
- Tablas del runtime.

Para cada región especifica:

- Propietario.
- Tamaño inicial y máximo.
- Permisos de lectura y escritura.
- Si es ejecutable.
- Duración.
- Política de asignación.
- Aislamiento respecto a otros mensajes.
- Reglas de limpieza.
- Tratamiento de direcciones inválidas.

Define si el espacio de direcciones es plano, segmentado o basado en regiones.

Evalúa los costes de introducir segmentación, tablas de regiones, handles u otras formas de direccionamiento.

No confundas una dirección virtual con una referencia directa a un objeto Java.

## 9.1. Representación de cadenas

Define cómo se representan las cadenas en memoria.

Considera UTF-8, UTF-16 u otra representación, teniendo en cuenta la integración con Java y las operaciones frecuentes de iflow.

Especifica:

- Codificación.
- Longitud.
- Terminación o ausencia de terminador.
- Tratamiento de Unicode.
- Indexación por bytes frente a unidades de código o puntos de código.
- Límites de longitud.
- Copia y modificación.
- Comportamiento ante secuencias inválidas.

La arquitectura debe permitir modificar texto arbitrario sin asumir que cada carácter ocupa un byte.

## 9.2. Copia de memoria

Define la semántica exacta de las operaciones de copia y movimiento.

Analiza el comportamiento cuando los rangos de origen y destino se solapan.

Determina cómo se evitan desbordamientos aritméticos en las direcciones y longitudes.

Considera si las operaciones deben tener un coste proporcional a los bytes procesados o si deben cobrarse por bloques con un límite máximo.

## 9.3. Memoria del host bajo demanda

Diseña la interfaz mediante la cual un programa puede leer o modificar propiedades externas sin copiar previamente todo el objeto.

Compara al menos:

- Copiar una propiedad a un buffer.
- Acceder mediante identificadores de propiedades.
- Usar handles opacos.
- Mantener una caché local durante la ejecución.
- Materializar una vista parcial del mensaje.

Determina cómo se representan los errores, las propiedades ausentes, los tipos incompatibles y las escrituras rechazadas.

La solución debe permitir acceso genérico a propiedades futuras sin introducir una instrucción por cada nueva propiedad.

---

# 10. Interfaz de E/S y servicios del host

La VM necesita comunicarse con el entorno Java. Diseña el protocolo de esa comunicación.

La propuesta inicial utiliza tres registros conceptuales:

- Un registro para identificar el servicio.
- Un registro que apunta al buffer de entrada o salida.
- Un registro que contiene la longitud o la capacidad del buffer.

Puedes conservar, modificar o reemplazar este esquema.

Define una convención de llamada precisa para los servicios.

Debe quedar claro cómo se transportan:

- Identificador del servicio.
- Argumentos.
- Dirección del buffer.
- Longitud de los datos.
- Tipo de operación.
- Resultado.
- Estado de éxito o error.
- Número de bytes leídos o escritos.
- Información de truncamiento.
- Permisos necesarios.

Evita interfaces que requieran demasiados registros especializados o que hagan imposible ampliar los servicios.

## 10.1. Operaciones genéricas

El diseño debe permitir operaciones como:

- Leer una propiedad del mensaje.
- Escribir una propiedad modificable.
- Consultar datos del emisor.
- Consultar datos del destinatario.
- Consultar el canal.
- Consultar permisos.
- Resolver un placeholder mediante un servicio autorizado.
- Solicitar una transformación admitida por iflow.
- Consultar el estado de rate limiting.
- Solicitar la aplicación de una decisión de routing.

No presupongas que todas estas operaciones deben convertirse en servicios del firmware. Algunas podrían ser bibliotecas o primitivas del runtime.

Para cada operación, determina si debe realizarse mediante memoria, servicio genérico, rutina de firmware o API del host.

## 10.2. Direcciones y longitudes

Especifica qué ocurre cuando:

- El buffer es demasiado pequeño.
- La propiedad no existe.
- El tipo no coincide.
- El host rechaza la operación.
- El programa no tiene permiso.
- La dirección está fuera de la región permitida.
- El servicio devuelve más datos de los que caben.
- La operación falla después de haber comenzado.
- La propiedad se modifica concurrentemente en el host.

Define si las operaciones son atómicas y cómo se informa de las escrituras parciales.

## 10.3. Evitar el acoplamiento al dominio

La ISA no debe conocer que un identificador de servicio significa `setChannel` o `setLangTarget`.

Es aceptable que el firmware estándar ofrezca bibliotecas especializadas en mensajes, canales, permisos y routing.

La separación debe ser explícita: una cosa es que el runtime ofrezca una operación autorizada para cambiar el canal, y otra que el CPU contenga una instrucción de hardware llamada `SET_CHANNEL`.

---

# 11. Interrupciones, excepciones y llamadas al firmware

Diseña un modelo de servicios y excepciones adecuado para una VM embebida.

No presupongas que las interrupciones deben imitar exactamente el hardware x86.

Compara:

1. Una instrucción explícita de invocación de servicio.
2. Una tabla de vectores.
3. Una tabla de servicios registrada por el runtime.
4. Un modelo de llamadas directas a funciones nativas del intérprete.
5. Un modelo híbrido.

Evalúa qué mecanismos ofrecen ventajas reales en una implementación Java.

Distingue entre:

- Invocaciones voluntarias de servicios.
- Excepciones aritméticas.
- Violaciones de memoria.
- Instrucciones inválidas.
- Violaciones de privilegios.
- Agotamiento del presupuesto de ejecución.
- Cancelación de una ejecución.
- Errores recuperables del host.
- Fallos internos del runtime.

Si utilizas vectores, define su estructura, sus rangos reservados, quién puede modificarlos y cómo se impide que un programa de usuario redirija servicios privilegiados.

Especifica qué errores pueden capturarse mediante manejadores y cuáles deben abortar obligatoriamente la ejecución.

Una excepción de programa no debe convertirse accidentalmente en una excepción fatal del servidor.

> **Resolución implementada (D31–D33).** Las llamadas voluntarias al host usan la instrucción `HOST`
> con un registro de servicios del runtime (opción 3) y un ABI de servicio versionado. Las excepciones
> del programa se traducen a códigos internos y a un resultado de fallo controlado del router; nunca
> escapan al servidor. La **tabla de IRQ** la define el BIOS en memoria (no el programa de usuario),
> con **fuentes de interrupción por E/S** (D33, implementada): el runtime la lee y despacha la línea
> como una llamada de hardware. El firmware ROM es **activo** (D32): resetea en su rutina de POST y
> encadena con el `main`. Ver `src/iflow-vm/HDL.md` y `docs/workdir/PLAN.md`.

---

# 12. Firmware, BIOS y bibliotecas estándar

Diseña un firmware mínimo que prepare el entorno de ejecución.

No presupongas que necesita un BIOS tradicional ni que debe implementar un microkernel completo.

Define las responsabilidades concretas del firmware:

- Inicialización de registros y estado.
- Preparación de regiones de memoria.
- Inicialización de tablas de servicios.
- Configuración de las convenciones de llamada.
- Carga o registro de bibliotecas estándar.
- Preparación del contexto de ejecución.
- Selección del programa inicial.
- Gestión de errores de inicialización.
- Recuperación o reinicialización de la instancia cuando corresponda.

Considera bibliotecas para:

- Operaciones con cadenas.
- Copia de bloques.
- Comparación de rangos.
- Búsqueda de subcadenas.
- Construcción de mensajes.
- Operaciones de bits.
- Expresiones regulares.
- Operaciones de routing.
- Lectura y escritura de propiedades.
- Gestión de estado persistente.

Determina qué utilidades deben implementarse en bytecode y cuáles conviene proporcionar mediante primitivas del runtime.

Las expresiones regulares, en particular, pueden consumir recursos de forma desproporcionada. Define una estrategia segura para su ejecución, incluidos límites de entrada, complejidad, memoria y tiempo.

No asumas que todas las bibliotecas deben cargarse en cada mensaje.

Diseña una estrategia de carga, reutilización, versionado e inicialización compatible con una VM persistente.

---

# 13. Arranque y diagnóstico

Se ha propuesto que el procesador comience conectando la salida a la entrada mediante un modo loopback.

No lo aceptes automáticamente como un autodiagnóstico válido.

Determina qué significa realmente la inicialización en un procesador virtual implementado en software.

Analiza si un loopback lógico comprueba algo distinto de que la implementación de ese loopback funciona correctamente.

Propón un procedimiento de inicialización y diagnóstico que compruebe, según corresponda:

- Estado inicial de los registros.
- Integridad de la memoria reservada.
- Consistencia de la decodificación de instrucciones.
- Disponibilidad de servicios.
- Validez del firmware.
- Registro correcto de bibliotecas.
- Capacidad de ejecutar un programa mínimo.
- Recuperación tras errores.
- Integridad de las estructuras del runtime.

Distingue entre pruebas realizadas una vez durante la inicialización, pruebas de desarrollo y comprobaciones de producción.

Evita ejecutar pruebas costosas por cada mensaje si no son necesarias.

---

# 14. Privilegios y aislamiento

Se ha planteado un modelo de dos niveles:

- Ring 0: firmware y servicios privilegiados.
- Ring 3: programas de usuario.

Evalúa si esta terminología y modelo aportan valor en una máquina virtual implementada en Java.

Puedes utilizar un modelo de privilegios equivalente sin reproducir exactamente los anillos de x86.

Lo importante es establecer límites de autoridad.

Define quién puede:

- Leer y escribir la memoria del programa.
- Modificar código ejecutable.
- Modificar las tablas de servicios.
- Invocar operaciones privilegiadas.
- Acceder a datos persistentes.
- Cambiar la configuración del runtime.
- Modificar el estado del mensaje.
- Solicitar acciones de routing.
- Consultar permisos.
- Interactuar con el host.

Distingue entre permisos de instalación, permisos del programa, permisos sobre propiedades y permisos del actor que originó el mensaje.

No confundas la autorización administrativa para instalar un programa con los permisos del jugador cuyo mensaje se procesa.

## 14.1. Seguridad de memoria

Un programa no debe poder sobrescribir arbitrariamente:

- Código del firmware.
- Tablas de servicios.
- Memoria de otro programa.
- Estado de otro mensaje.
- Estructuras internas de Java.
- Registros de control privilegiados.

Define cómo se detectan y tratan las violaciones.

No permitas que el mero hecho de que la ISA tenga escrituras de memoria genéricas convierta toda la memoria en una región escribible.

## 14.2. Seguridad de servicios

Un servicio privilegiado debe validar sus propios argumentos y permisos.

No debe confiar exclusivamente en que el programa haya sido compilado correctamente.

La autorización debe comprobarse en el límite de confianza adecuado.

## 14.3. Recuperación

Define cómo se aborta una ejecución maliciosa o defectuosa sin comprometer el estado de otras ejecuciones.

Determina qué estado puede conservarse después de una excepción y qué estado debe descartarse.

---

# 15. Modelo de ejecución y ciclo de vida

Este apartado es crítico porque la intención es evitar crear y destruir todo el procesador por cada mensaje.

Diseña el ciclo de vida de la VM desde el arranque del módulo hasta su apagado.

Evalúa separadamente:

- Instancia del procesador.
- Programa compilado.
- Firmware.
- Estado persistente del programa.
- Contexto de ejecución.
- Contexto del mensaje.
- Pila.
- Registros.
- Buffers temporales.
- Estado del router.
- Estado del rate limiter.

Compara al menos estos modelos:

### Modelo A: VM por mensaje

Crear, ejecutar y destruir una VM por cada mensaje.

### Modelo B: VM persistente compartida

Mantener una instancia y reutilizarla entre ejecuciones, aislando sus contextos.

### Modelo C: Pool de contextos de ejecución

Mantener el procesador y el programa compartidos, pero reutilizar contextos, registros y buffers independientes.

### Modelo D: Instancias o trabajadores múltiples

Distribuir ejecuciones entre varias instancias o trabajadores, manteniendo el aislamiento necesario.

Evalúa el coste de cada alternativa en términos de:

- Asignaciones de objetos Java.
- Recolección de basura.
- Copias de memoria.
- Contención.
- Latencia.
- Throughput.
- Complejidad de sincronización.
- Estado compartido.
- Aislamiento.
- Reinicialización tras errores.

No confundas una instancia de CPU con un único contexto de ejecución.

La recomendación debe distinguir explícitamente qué componentes son compartidos, cuáles son reutilizables y cuáles son privados de cada ejecución.

---

# 16. Concurrencia, paralelismo y scheduling

No asumas que la VM necesita un scheduler propio simplemente porque el servidor tiene varios núcleos físicos.

Primero determina si el trabajo debe ejecutarse secuencialmente, mediante un pool de trabajadores o mediante varios intérpretes.

Compara:

- Un único intérprete secuencial.
- Un intérprete compartido con contextos independientes.
- Un pool de contextos.
- Varias instancias de CPU.
- Un scheduler explícito de tareas de la VM.

Evalúa la compatibilidad con el modelo de hilos de Java, los requisitos de Bukkit y Fabric, y las operaciones que deben ejecutarse en el hilo principal del servidor.

La VM no debe acceder directamente a APIs que exigen un hilo específico.

Define las reglas de concurrencia del estado persistente y las políticas que modifican mensajes.

Determina si una misma instancia de programa puede ejecutar varios mensajes concurrentemente y, si es así, qué garantías ofrece sobre las variables globales y las operaciones de escritura.

Considera que las políticas de un mensaje no deben contaminar accidentalmente otro mensaje.

Si recomiendas un scheduler, especifica sus responsabilidades y justifica por qué no basta con el executor del host.

Evalua si diseñar paralelismo dentro de la VM es coherente con la complejidad del proyecto, si realmente vale la pena la complejidad que implica paralelizar en multiples nucleos del host.
Compara el coste del paralelismo interno con el de delegar la concurrencia al runtime Java, sin dar por hecho que una arquitectura con varios núcleos virtuales será superior.

---

# 17. Semántica del mensaje y atomicidad

El objeto `Message` debe ser accesible mediante una interfaz controlada.

No es necesario que todos sus campos se copien de antemano a la memoria virtual.

Diseña un modelo que permita:

1. Recibir un mensaje.
2. Crear el contexto de evaluación.
3. Leer las propiedades requeridas.
4. Ejecutar lógica arbitraria dentro de límites.
5. Modificar propiedades autorizadas.
6. Seleccionar una decisión de routing.
7. Validar el resultado.
8. Confirmar las modificaciones.
9. Devolver un resultado al dispatcher.
10. Limpiar el contexto temporal.

Evalúa si las modificaciones deben ser inmediatas, transaccionales o una combinación de ambas.

Considera un modelo de escritura diferida en el que las modificaciones se registran y solo se aplican al confirmar la ejecución.

Esto puede permitir que un programa falle sin dejar un mensaje parcialmente modificado.

No impongas transacciones completas si su coste o complejidad no se justifican. Compara sus ventajas y desventajas.

Define claramente qué ocurre con:

- Cancelaciones.
- Errores de ejecución.
- Rate limiting.
- Reintentos.
- Cambios de canal.
- Redirecciones.
- Transformaciones sucesivas.
- Mensajes ya procesados que todavía no se han entregado.

La ventana entre procesamiento y entrega no debe convertirse en una excusa para permitir modificaciones concurrentes no controladas.

Determina si las modificaciones posteriores a la evaluación son necesarias y, en caso afirmativo, diseña un mecanismo explícito de propiedad, versionado y sincronización.

---

# 18. Integración con el routing de iflow

La VM no debe reemplazar innecesariamente el sistema de entrega.

Define la relación entre la máquina virtual y el contrato actual:

`Router.route(Message, Actor) -> RouteOutcome`

Determina si la VM debe:

- Implementar directamente el contrato del router.
- Actuar como motor de políticas invocado por `DefaultRouter`.
- Producir un resultado intermedio que el router valida.
- Usar otra abstracción claramente justificada.

El diseño debe preservar las semánticas de las decisiones actuales:

- `LOG`
- `DROP`
- `REJECT`
- `REDIRECT`
- `CHANNEL_REDIRECT`
- `RATE_LIMIT`

Explica cómo se representa cada decisión en el resultado del programa.

No permitas que un programa invente una decisión inválida o acceda directamente a las rutinas de entrega.

La VM debe devolver un resultado verificable y el runtime debe validar su integridad antes de que el dispatcher actúe.

## 18.1. Permisos y rate limiting

Analiza cuidadosamente el orden de las operaciones.

En la arquitectura actual existen dos niveles:

- Rate limiting de emisión, una vez por mensaje antes del fan-out.
- Rate limiting asociado a una regla, evaluado cuando corresponde para un destinatario.

El nuevo diseño no debe duplicar accidentalmente las comprobaciones por cada destinatario ni cambiar el comportamiento observable sin documentarlo.

Determina qué lógica pertenece a la VM y qué lógica debe seguir perteneciendo al host.

## 18.2. Compatibilidad de políticas

Diseña una estrategia de migración desde reglas YAML y expresiones SpEL hacia programas de la VM.

Evalúa si conviene:

- Traducir automáticamente un subconjunto de reglas.
- Mantener un adaptador temporal.
- Permitir ambos motores.
- Versionar los formatos de programa.
- Ofrecer validación y diagnóstico antes de activar una política.

No prometas una traducción automática completa si las semánticas no son equivalentes.

---

# 19. Seguridad de ejecución y presupuesto de recursos

Un programa con bucles arbitrarios puede consumir CPU indefinidamente.

La VM necesita un mecanismo de límites que no dependa de que el programador escriba código correcto.

Diseña un presupuesto de ejecución, denominado provisionalmente *gas*, *fuel* o *instruction budget*.

Define:

- Qué consume presupuesto.
- Coste de cada instrucción.
- Coste de llamadas al host.
- Coste de copias de memoria.
- Coste de procesamiento de cadenas.
- Coste de expresiones regulares.
- Coste de acceso a estado persistente.
- Qué ocurre al agotar el presupuesto.
- Cómo se contabiliza el trabajo realizado por bibliotecas nativas del runtime.
- Cómo se impide que una única operación costosa eluda el presupuesto.

Un simple contador de instrucciones no basta si una instrucción puede iniciar una operación cuyo coste depende de millones de bytes.

Define límites independientes para memoria, tamaño del programa, pila, buffers, longitud de cadenas y tiempo de ejecución cuando proceda.

No uses un timeout como única defensa contra bucles infinitos.

Explica qué límites son deterministas y cuáles dependen del entorno físico.

Evita mecanismos de recuperación que permitan al programa reiniciar indefinidamente su presupuesto.

---

# 20. Rendimiento y estrategia de optimización

La finalidad es procesar mensajes con un coste razonable, potencialmente a tasas elevadas.

No asumas que el intérprete más sofisticado será el más rápido.

Compara una implementación de referencia basada en:

- Bytecode compacto.
- Intérprete por despacho de instrucciones.
- Prevalidación del programa.
- Contextos reutilizables.
- Buffers reutilizables.
- Acceso al host mediante servicios.
- Cachés de propiedades.
- Bibliotecas compiladas o implementadas en Java.
- Especialización de programas, si se justifica.

Evalúa el coste de las abstracciones adicionales, especialmente cuando se ejecutan cientos o miles de mensajes por segundo.

No inventes benchmarks.

Distingue entre hipótesis de rendimiento, estimaciones analíticas y resultados medidos.

Define qué métricas deben medirse antes de afirmar que la nueva arquitectura es superior:

- Throughput.
- Latencia p50, p95 y p99.
- Asignaciones por mensaje.
- Bytes asignados por mensaje.
- Tiempo de CPU.
- Coste de acceso al host.
- Coste de inicialización.
- Coste de validación.
- Coste de cambios de contexto.
- Efecto de las políticas stateful.

Evalúa si conviene implementar un intérprete sencillo primero y reservar futuras optimizaciones para perfiles de carga que las justifiquen.

No introduzcas microcódigo, JIT, SIMD o GPU en la arquitectura inicial sin demostrar una necesidad real.

---

# 21. Formato del programa y herramientas de desarrollo

Define el ecosistema necesario para que el procesador sea utilizable.

Como mínimo, especifica:

- Sintaxis de ensamblador.
- Formato binario del programa.
- Codificación de instrucciones.
- Etiquetas y resolución de saltos.
- Constantes.
- Secciones de código y datos.
- Convenciones de llamadas.
- Símbolos y metadatos.
- Versionado de la ISA.
- Versión del firmware requerido.
- Declaración de capacidades.
- Validación estática.
- Mensajes de error.
- Desensamblador.
- Herramientas de depuración.
- Trazas de ejecución.
- Inspección de registros y memoria.
- Diagnóstico de agotamiento de presupuesto.
- Pruebas reproducibles.

No es necesario implementar todavía todas estas herramientas, pero la arquitectura debe dejar definido cómo encajan.

Evalúa si conviene que los administradores escriban ensamblador directamente o si se debe diseñar posteriormente un lenguaje de nivel superior que compile a esta ISA.

No diseñes un lenguaje de alto nivel completo en esta fase. La prioridad es definir una máquina que pueda servir de objetivo de compilación.

---

# 22. Especificación mediante programas de ejemplo

La arquitectura no estará suficientemente diseñada si solo contiene tablas de instrucciones.

Demuestra su viabilidad con programas representativos escritos en el ensamblador propuesto.

Incluye al menos los siguientes ejemplos:

### Ejemplo A: paso transparente

El mensaje se procesa sin modificar su contenido y se entrega normalmente.

Debe demostrar el coste conceptual mínimo de ejecutar una política.

### Ejemplo B: descarte de mensaje

Una condición decide que el mensaje debe descartarse.

Debe demostrar cómo se representa la decisión y cómo se evita invocar innecesariamente servicios posteriores.

### Ejemplo C: restricción por permisos

El programa consulta los permisos del destinatario o emisor y decide si el mensaje se entrega.

Debe respetar la separación entre la lógica de decisión y la implementación real de permisos del host.

### Ejemplo D: transformación de texto

El programa lee el contenido, modifica un rango y escribe el resultado.

Debe demostrar las operaciones de memoria, las cadenas y la escritura controlada al host.

### Ejemplo E: bucle de búsqueda

El programa busca una subcadena o una secuencia de bytes y actúa según el resultado.

Debe demostrar comparaciones, desplazamiento de punteros y saltos.

### Ejemplo F: modificación de canal

El programa solicita una modificación del canal y produce una decisión coherente con el contrato de iflow.

### Ejemplo G: estado entre mensajes

Un programa mantiene un contador entre ejecuciones para demostrar el estado persistente.

Debe definir el aislamiento, la concurrencia y el comportamiento ante reinicios.

### Ejemplo H: agotamiento de presupuesto

Un programa contiene un bucle que no termina.

Debe demostrar que el runtime aborta la ejecución sin bloquear indefinidamente el servidor ni comprometer otras ejecuciones.

### Ejemplo I: fallo durante una modificación

Un programa modifica temporalmente el mensaje y después genera un error.

Demuestra si el modelo seleccionado conserva, revierte o descarta las modificaciones.

### Ejemplo J: acceso a una propiedad desconocida por el CPU

Una biblioteca consulta una propiedad nueva mediante el mecanismo genérico de E/S.

Debe demostrar que se puede ampliar el dominio del mensaje sin añadir una instrucción a la ISA.

Los ejemplos deben ser consistentes con la codificación, los registros, la memoria y las convenciones de llamada definidas en los apartados anteriores.

No inventes pseudoinstrucciones y las presentes como si formaran parte de la ISA definitiva.

Si algún ejemplo requiere una biblioteca que todavía no se ha especificado, decláralo y define la interfaz necesaria.

---

# 23. Pruebas de conformidad y criterios de corrección

Diseña un conjunto de pruebas para demostrar que dos implementaciones distintas de la ISA producirían resultados equivalentes.

Incluye pruebas de:

- Aritmética.
- Desbordamiento.
- Comparaciones.
- Saltos.
- Llamadas y retornos.
- Direcciones inválidas.
- Límites de memoria.
- Copias solapadas.
- Cadenas Unicode.
- Codificación del bytecode.
- Servicios del host.
- Errores de servicio.
- Privilegios.
- Aislamiento entre mensajes.
- Estado persistente.
- Agotamiento de presupuesto.
- Cancelación.
- Concurrencia.
- Recuperación después de una excepción.

Define invariantes que el runtime debe mantener en todo momento.

Cuando sea viable, propone pruebas basadas en propiedades y ejecución diferencial entre una implementación de referencia y una implementación optimizada.

Incluye pruebas de carga con perfiles de mensajes pequeños, grandes, reglas simples y reglas complejas.

No confundas pasar pruebas funcionales con demostrar rendimiento.

---

# 24. Decisiones que debes resolver explícitamente

Antes de cerrar el diseño, presenta una tabla de decisiones arquitectónicas.

Incluye, como mínimo:

| Decisión | Alternativas | Criterios | Recomendación |
|---|---|---|---|
| Ancho de la ISA | 16/32 bits u otra opción | Direcciones, coste y extensibilidad | Decisión justificada |
| Modelo de memoria | Plano, segmentado, regiones | Seguridad y complejidad | Decisión justificada |
| Codificación | Fija, variable, mixta | Despacho y tamaño | Decisión justificada |
| Registros | Banco mínimo o ampliado | Ortogonalidad y coste | Decisión justificada |
| Servicios | Instrucción, tabla o llamadas directas | Seguridad y rendimiento | Decisión justificada |
| Privilegios | Dos niveles, capacidades u otro modelo | Aislamiento | Decisión justificada |
| Estado persistente | Por mensaje, programa o instancia | Semántica y seguridad | Decisión justificada |
| Ejecución | VM por mensaje, contextos reutilizados o pool | Coste y aislamiento | Decisión justificada |
| Concurrencia | Secuencial o paralela | Host y sincronización | Decisión justificada |
| Atomicidad | Escrituras inmediatas o confirmación diferida | Integridad | Decisión justificada |
| Presupuesto | Gas, fuel u otro mecanismo | Seguridad y coste | Decisión justificada |
| Firmware | Mínimo o microkernel | Necesidades reales | Decisión justificada |
| Extensiones | ISA cerrada o versionada | Compatibilidad | Decisión justificada |

No respondas todas las filas con «depende». Si falta información, establece una hipótesis explícita, toma una decisión provisional y describe qué evidencia podría cambiarla.

---

# 25. Reglas contra las malas decisiones arquitectónicas

Debes cumplir estas restricciones durante todo el diseño.

1. No añadas una instrucción por cada propiedad del mensaje.
2. No copies toda la estructura del mensaje a la memoria de la VM si basta con consultar propiedades bajo demanda.
3. No confundas los registros con el espacio de memoria.
4. No confundas el tamaño de una dirección con la memoria realmente asignada.
5. No introduzcas registros redundantes sin justificar su necesidad.
6. No imites x86 por estética.
7. No introduzcas un kernel, scheduler o microcódigo completo sin justificar su función.
8. No presupongas que varias instancias de CPU equivalen automáticamente a paralelismo eficiente.
9. No permitas que la persistencia comparta accidentalmente la pila o los registros entre mensajes.
10. No permitas acceso directo a objetos Java desde el bytecode.
11. No confíes exclusivamente en validaciones realizadas por el compilador.
12. No dependas únicamente de timeouts para controlar bucles.
13. No permitas que una operación nativa costosa eluda el presupuesto de ejecución.
14. No sacrifiques la seguridad para permitir escrituras arbitrarias sobre memoria.
15. No conviertas errores de un programa en fallos fatales del servidor.
16. No declares una arquitectura de alto rendimiento sin mediciones.
17. No diseñes una ISA tan pequeña que requiera instrucciones especiales para resolver cada problema nuevo.
18. No diseñes una ISA tan grande que se convierta en un conjunto de primitivas específicas del dominio.
19. No introduzcas extensiones dependientes del procesador físico en el contrato portable de la ISA.
20. No implementes antes de cerrar las semánticas esenciales.

Cuando una propuesta de este documento resulte técnicamente equivocada, corrígela explícitamente y explica por qué.

---

# 26. Entregables obligatorios

Produce el resultado final en el siguiente orden:

1. **Resumen ejecutivo:** arquitectura recomendada y razones principales.
2. **Requisitos funcionales y no funcionales:** qué debe resolver la VM.
3. **Modelo de amenazas:** qué errores y ataques debe resistir.
4. **Diagrama de componentes:** CPU, memoria, firmware, runtime, servicios y host.
5. **Modelo de ejecución:** ciclo de vida y aislamiento.
6. **Decisión sobre el ancho de palabra y direccionamiento.**
7. **Banco de registros completo.**
8. **Especificación formal de la ISA.**
9. **Codificación del bytecode.**
10. **Modelo de memoria y representación de datos.**
11. **Interfaz de E/S y servicios del host.**
12. **Modelo de excepciones y privilegios.**
13. **Diseño del firmware y las bibliotecas estándar.**
14. **Protocolo de inicialización y diagnóstico.**
15. **Estado persistente y concurrencia.**
16. **Integración con el router y el dispatcher de iflow.**
17. **Presupuesto de ejecución y protección de recursos.**
18. **Estrategia de rendimiento y optimización.**
19. **Formato de programas y herramientas de desarrollo.**
20. **Programas de ejemplo completos.**
21. **Plan de pruebas de conformidad.**
22. **Registro de decisiones arquitectónicas.**
23. **Riesgos, limitaciones y cuestiones pendientes.**
24. **Hoja de ruta de implementación por fases.**

Para cada fase de implementación, especifica:

- Objetivo.
- Componentes afectados.
- Dependencias.
- Pruebas necesarias.
- Criterios objetivos de aceptación.
- Riesgos de regresión.
- Qué debe permanecer fuera del alcance de esa fase.

No escribas todavía la implementación completa del CPU.

---

# 27. Criterio de aceptación final

El diseño será aceptable únicamente si permite responder de manera precisa a estas preguntas:

- ¿Cómo se codifica y ejecuta una instrucción?
- ¿Qué registros existen y cuál es la semántica de cada uno?
- ¿Cómo se representa y protege la memoria?
- ¿Cómo se lee una propiedad del mensaje sin copiar todo el objeto?
- ¿Cómo se modifica una propiedad externa mediante una interfaz controlada?
- ¿Cómo se invoca un servicio del firmware?
- ¿Cómo se distingue una llamada autorizada de una operación privilegiada?
- ¿Cómo se representa una decisión de routing?
- ¿Cómo se ejecutan bucles sin riesgo de bloqueo indefinido?
- ¿Cómo se evita que una ejecución corrompa otra?
- ¿Qué persiste entre mensajes y qué se reinicia?
- ¿Cómo se reutiliza la VM sin compartir accidentalmente el contexto?
- ¿Cómo se gestiona la concurrencia?
- ¿Cómo se valida y depura un programa?
- ¿Cómo se preserva el comportamiento de iflow?
- ¿Cómo se amplía el sistema sin modificar la ISA para cada propiedad nueva?
- ¿Qué evidencia demostraría que esta arquitectura es mejor que una implementación convencional basada en reglas, bytecode existente o un lenguaje embebido?

La respuesta final debe ser una especificación de ingeniería autocontenida, no una colección de ideas inconexas.

**Prioridad absoluta:** coherencia semántica, simplicidad del núcleo, expresividad del software, aislamiento verificable, acceso eficiente al host y extensibilidad sin proliferación de instrucciones.

Si no puedes resolver una decisión con suficiente evidencia, identifícala como provisional. No rellenes los vacíos con detalles inventados ni presentes hipótesis como hechos demostrados.
