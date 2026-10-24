# AUDITORÍA TÉCNICA FORENSE — IFLOW VM + COMPILER
## Análisis integral de arquitectura, ISA, memoria, ejecución, seguridad, compilación y corrección funcional

Realiza una auditoría técnica exhaustiva, crítica y verificable del módulo **iFlow** y de su compilador dentro del repositorio:

**Repositorio:** https://github.com/majhrs16-official/TextFormatter-Suite

**Alcance obligatorio:**
- Todo el módulo `src/iflow-vm/`.
- Todo el compilador `tools/iflow-compiler/`.
- Todos los README, especificaciones, documentos de diseño, ejemplos, programas ensamblados, fixtures y archivos de configuración relacionados con iFlow.
- Todos los módulos externos de TextFormatter Suite que interactúen con iFlow, en la medida necesaria para comprobar sus contratos e integración.
- Todos los tests relacionados con la VM, el ISA, la memoria, los servicios HOST, el compilador, el formato binario y la integración.
- La configuración real del sistema de build, las dependencias y los mecanismos de carga e inicialización.
- Cualquier documentación que describa funcionalidades, garantías, limitaciones o decisiones de diseño de iFlow.

El objetivo no es producir una descripción agradable del proyecto, validar las intenciones de su autor ni encontrar defectos a toda costa. El objetivo es determinar **qué hace realmente el código, qué debería hacer según sus contratos, qué puede demostrar el auditor y qué sigue siendo desconocido**.

No des por correcta una arquitectura por ser sofisticada. Tampoco la descartes por contener errores corregibles. Evalúa las decisiones de diseño por sus propiedades reales, sus costes, sus garantías y su utilidad funcional.

---

# 1. REGLAS INNEGOCIABLES CONTRA LAS ALUCINACIONES

Estas reglas tienen prioridad sobre cualquier otra instrucción de la auditoría.

## 1.1. No afirmes más de lo que hayas comprobado

No afirmes que una funcionalidad funciona simplemente porque:

- Existe una clase, método, interfaz, opcode o servicio con un nombre sugerente.
- Hay un README que promete esa funcionalidad.
- Existe un test que aparentemente la cubre.
- El código compila.
- El código parece correcto en una lectura superficial.
- El compilador genera un binario.
- El intérprete acepta ese binario.
- Una función devuelve un código de éxito.
- Un comentario afirma que una operación está terminada.
- El proyecto anuncia una cantidad determinada de tests.

La existencia de una API no demuestra que esté conectada. La existencia de un test no demuestra que el test se ejecute. La compilación no demuestra la corrección semántica. La ausencia de errores visibles no demuestra la ausencia de errores.

Comprueba el recorrido real de cada funcionalidad relevante.

## 1.2. No inventes código, ubicaciones ni resultados

Está prohibido inventar:

- Clases, métodos, instrucciones, servicios, archivos o directorios.
- Números de línea.
- Resultados de compilación o ejecución.
- Resultados de tests.
- Benchmarks.
- Mensajes de error.
- Comportamientos del runtime que no estén respaldados por código o experimentos.
- Garantías de seguridad.
- Contratos que no aparezcan en la documentación o que no puedan deducirse justificadamente del código.
- Limitaciones que no hayas investigado.

Si no puedes acceder a un archivo, obtener su contenido completo o ejecutar una prueba, decláralo explícitamente.

Si una herramienta falla, no sustituyas silenciosamente el resultado por una suposición.

## 1.3. Separa los tipos de afirmación

Clasifica cada afirmación relevante con una de estas categorías:

- **HECHO VERIFICADO:** respaldado directamente por código, configuración, documentación inequívoca o un experimento ejecutado y registrado.
- **INFERENCIA JUSTIFICADA:** conclusión derivada de evidencia concreta, pero no observada directamente en ejecución. Explica la cadena de razonamiento.
- **HIPÓTESIS PENDIENTE:** comportamiento plausible que necesita una comprobación adicional.
- **NO VERIFICADO:** no se pudo determinar con los medios disponibles.
- **CONTRADICCIÓN:** dos o más fuentes del proyecto afirman o implementan cosas incompatibles.
- **AFIRMACIÓN DESCARTADA:** una hipótesis fue investigada y la evidencia disponible demuestra que era incorrecta.
- **ERROR DEL AUDITOR:** afirmación anterior de la propia auditoría que resultó ser falsa, exagerada o insuficientemente respaldada.

No conviertas una hipótesis en un hecho por repetirla varias veces.

Si descubres que tú mismo has cometido un error durante el análisis, notifícalo expresamente, identifica la afirmación original y corrígela. No ocultes el error ni mantengas una conclusión solo para preservar la coherencia del informe.

## 1.4. Las referencias deben permitir verificar las afirmaciones

Para cada defecto, contradicción o afirmación técnica importante, incluye:

`directorio:archivo:línea_inicial-línea_final`

Usa rutas relativas a la raíz del repositorio.

Ejemplo de formato:

`src/iflow-vm/src/main/java/.../VmInterpreter.java:120-137`

Los nombres de archivo y los números de línea deben proceder de la versión del código que realmente inspeccionaste. Comprueba las líneas antes de entregar el informe.

Cuando un problema dependa de varios componentes, cita cada ubicación relevante. No cites únicamente el lugar donde se manifiesta el síntoma si el defecto se origina en otro componente.

Si no puedes verificar el número de línea, indica la ruta y escribe **«línea no verificada»**. Nunca inventes una línea para completar el formato.

Cuando una afirmación dependa de una prueba, indica también el comando ejecutado, su resultado y el archivo de test pertinente.

## 1.5. No afirmes que has ejecutado lo que no has ejecutado

Distingue siempre entre:

- Inspección estática del código.
- Ejecución de tests existentes.
- Ejecución de pruebas nuevas.
- Compilación del proyecto.
- Ensamblado de programas de ejemplo.
- Ejecución de binarios en la VM.
- Pruebas de integración.
- Pruebas de rendimiento.
- Pruebas de concurrencia.
- Pruebas de seguridad.

Si no dispones de un entorno de ejecución, realiza la mejor auditoría estática posible, pero declara la limitación y proporciona pruebas reproducibles para resolver las dudas pendientes.

No uses expresiones como «todos los tests pasan», «el proyecto compila correctamente» o «la VM es segura» sin evidencia suficiente.

---

# 2. IDENTIFICACIÓN DEL OBJETO REAL AUDITADO

Antes de analizar el comportamiento, registra:

1. URL del repositorio.
2. Rama examinada.
3. Commit SHA exacto, si es accesible.
4. Fecha de inspección.
5. Estado del árbol de trabajo, si has clonado el repositorio.
6. Versiones de Java, Node.js, TypeScript, Gradle y demás herramientas relevantes.
7. Sistemas operativos o entornos realmente utilizados.
8. Directorios y archivos examinados.
9. Directorios y archivos que no pudieron examinarse.
10. Comandos de compilación, test y ejecución que realmente se intentaron.

Si no puedes clonar el repositorio, utiliza el método de acceso disponible y declara sus limitaciones.

No atribuyas a una versión actual resultados obtenidos de otra revisión del código.

---

# 3. INVENTARIO COMPLETO Y MAPA DE DEPENDENCIAS

Antes de emitir conclusiones, construye un inventario del sistema.

## 3.1. Inventario de iFlow

Identifica y clasifica todos los componentes relacionados con:

- Motor de políticas.
- Intérprete.
- Ciclo de ejecución.
- Contexto de ejecución.
- Pool de contextos.
- Registros y flags.
- ISA y decodificación de instrucciones.
- Representación de programas.
- Parser del formato binario.
- Memoria virtual.
- MMU.
- Tablas de páginas.
- Permisos de memoria.
- DMA o mecanismos equivalentes.
- Regiones reservadas.
- Código, datos, constantes, pila y buffers.
- Presupuesto de instrucciones, fuel o límites de ejecución.
- Journal de modificaciones.
- Rollback y commit.
- Registro y despacho de servicios HOST.
- Capacidades y permisos.
- Lectura y escritura de propiedades.
- Acceso al estado.
- Resolución de canales.
- Decisiones de routing.
- Validación de resultados.
- Carga, sustitución y publicación de programas.
- Integración con el resto de TextFormatter Suite.

No presupongas que todos estos componentes existen: verifica cuáles están implementados, cuáles están parcialmente implementados y cuáles son únicamente conceptos documentados.

## 3.2. Inventario del compiler

Examina todos los archivos del compilador, incluidos los que no parezcan centrales:

- Lexer o análisis léxico, si existe.
- Parser del lenguaje ensamblador.
- Tipos y representación intermedia.
- Tabla de símbolos.
- Resolución de etiquetas.
- Gestión de secciones.
- Gestión de direcciones y alineamiento.
- Codificación de instrucciones.
- Codificación de operandos.
- Cálculo de offsets.
- Generación de imágenes binarias.
- Metadatos.
- Hashes y checksums.
- Capacidades declaradas.
- Validación de instrucciones.
- Diagnósticos y niveles de severidad.
- Generación de listados.
- CLI y gestión de argumentos.
- Códigos de salida.
- Fixtures y programas de ejemplo.
- Tests unitarios y de integración.

Comprueba qué archivos participan realmente en el flujo de compilación. No atribuyas funcionalidad a un archivo solo por su nombre.

## 3.3. Mapa de dependencias

Construye un mapa que muestre las relaciones reales entre los componentes.

Debe permitir responder:

- ¿Quién construye la imagen del programa?
- ¿Quién valida el binario?
- ¿Quién determina el entry point?
- ¿Quién crea los mapeos de memoria?
- ¿Quién inicializa cada región?
- ¿Quién decodifica las instrucciones?
- ¿Quién controla el avance del program counter?
- ¿Quién aplica los permisos?
- ¿Quién verifica las capacidades?
- ¿Quién ejecuta los servicios HOST?
- ¿Quién registra las modificaciones?
- ¿Quién confirma o revierte los cambios?
- ¿Quién valida el resultado de la política?
- ¿Quién publica o sustituye el programa activo?
- ¿Qué componentes dependen de servicios externos?
- ¿Qué responsabilidades están duplicadas entre componentes?

Representa las relaciones con un diagrama Mermaid cuando resulte útil, pero genera el diagrama a partir de referencias comprobadas.

---

# 4. RECONSTRUCCIÓN DEL FLUJO COMPLETO

Esta es una de las partes más importantes de la auditoría.

Reconstruye el flujo real desde un archivo fuente de ensamblador hasta el resultado observable en el servidor.

Como mínimo, sigue un programa de ejemplo real a través de estas etapas:

1. Archivo fuente.
2. Tokenización y parsing.
3. Validación sintáctica y semántica.
4. Tabla de símbolos.
5. Resolución de etiquetas y direcciones.
6. Codificación de instrucciones.
7. Generación de metadatos y formato binario.
8. Lectura del binario por el runtime Java.
9. Validación de la imagen.
10. Construcción de la representación del programa.
11. Inicialización de registros y memoria.
12. Configuración de páginas y permisos.
13. Selección del entry point.
14. Fetch, decode y ejecución de instrucciones.
15. Invocación de servicios HOST.
16. Modificación de propiedades o estado.
17. Validación de la decisión resultante.
18. Commit, rollback o descarte de modificaciones.
19. Entrega del resultado al sistema consumidor.

Para cada etapa, identifica:

- Archivo y método responsables.
- Datos de entrada.
- Datos de salida.
- Validaciones realizadas.
- Errores posibles.
- Supuestos sobre el formato o el estado.
- Contratos que recibe de la etapa anterior.
- Contratos que ofrece a la siguiente.

Comprueba que el formato producido por el compilador es exactamente compatible con el que acepta el runtime.

No basta con comparar nombres de campos: verifica tamaños, offsets, endianness, rangos, valores por defecto, semántica y orden de las operaciones.

Si un recorrido se interrumpe por falta de información, identifica el último punto comprobado y el punto exacto en el que se pierde la trazabilidad.

---

# 5. AUDITORÍA DE LA ISA Y DEL MODELO DE PROGRAMACIÓN

Determina cuál es la ISA real, sin confundir la documentación con la implementación.

## 5.1. Especificación efectiva de cada instrucción

Genera un inventario de todas las instrucciones que el compilador puede emitir y que el intérprete puede ejecutar.

Para cada instrucción, registra:

- Mnemonic.
- Opcode numérico.
- Formato binario.
- Longitud de la instrucción.
- Operandos.
- Registros implicados.
- Flags leídos y modificados.
- Efectos sobre memoria.
- Efectos sobre el program counter.
- Condiciones de error.
- Coste de fuel, si existe.
- Restricciones de permisos.
- Correspondencia entre compiler y runtime.
- Estado de implementación.
- Tests que demuestran su comportamiento.

Clasifica cada instrucción como:

- Implementada y probada.
- Implementada, pero insuficientemente probada.
- Implementación parcial.
- Reconocida, pero no implementada.
- Documentada, pero no encontrada en el código.
- Emitida por el compiler, pero incompatible con el runtime.
- Implementada en el runtime, pero no generable por el compiler.
- No verificable.

No declares que una instrucción funciona por el mero hecho de que existe un `case`, una constante o un mnemonic.

## 5.2. Semántica y casos límite

Investiga específicamente:

- Overflow y underflow.
- Aritmética con signo y sin signo.
- Conversión entre tamaños.
- División por cero.
- Desbordamiento del program counter.
- Saltos hacia direcciones inválidas.
- Saltos a mitad de una instrucción.
- Instrucciones truncadas.
- Opcodes desconocidos.
- Flags no inicializados o no actualizados.
- Lecturas y escrituras fuera de rango.
- Aliasing entre operandos.
- Registros reservados.
- Diferencias entre el comportamiento documentado y el efectivo.

Comprueba si las operaciones tienen semántica consistente entre instrucciones equivalentes y si el compiler impide producir programas inválidos o deja esa responsabilidad al runtime.

## 5.3. Capacidad expresiva

Evalúa si la ISA permite expresar las operaciones necesarias para construir políticas complejas.

No confundas «tener muchas instrucciones» con «tener gran capacidad expresiva».

Determina si se pueden implementar, de forma efectiva:

- Comparaciones y decisiones condicionales.
- Bucles.
- Contadores.
- Acceso condicional a propiedades.
- Transformaciones de valores.
- Lógica entre varios mensajes.
- Invocaciones HOST.
- Gestión de errores.
- Decisiones de routing.
- Composición de varias operaciones.
- Control de estado, cuando esté diseñado para permitirse.
- Programas no triviales sin depender de funcionalidades inexistentes.

Si el diseño promete alguna de estas posibilidades, construye un ejemplo real y sigue su ejecución. Si no puedes ejecutarlo, presenta el ejemplo como prueba propuesta y no como resultado demostrado.

---

# 6. AUDITORÍA PROFUNDA DEL COMPILER

No trates el compilador como un accesorio. Es parte de la superficie de corrección y seguridad de la VM.

## 6.1. Parsing y diagnósticos

Comprueba:

- Sintaxis válida e inválida.
- Comentarios y líneas vacías.
- Espacios y tabuladores.
- Mayúsculas y minúsculas.
- Literales numéricos.
- Literales negativos.
- Literales hexadecimales.
- Cadenas y escapes.
- Identificadores.
- Etiquetas duplicadas.
- Símbolos no definidos.
- Directivas desconocidas.
- Instrucciones desconocidas.
- Operandos ausentes o adicionales.
- Rangos fuera de límites.
- Errores en archivos incluidos, si existen.
- Número de línea y columna de los errores.
- Continuación o aborto después de errores.

Comprueba si los diagnósticos describen el error real o si ocultan una condición inválida bajo un warning, un mensaje genérico o un binario generado parcialmente.

## 6.2. Directivas y secciones

Audita todas las directivas soportadas por el compiler.

Para cada una, compara:

1. Documentación.
2. Parser.
3. Validación.
4. Representación intermedia.
5. Emisión de bytes.
6. Resultado en el runtime.
7. Tests.

Presta especial atención a:

- Origen del programa.
- Alineamiento.
- Secciones de código y datos.
- Constantes.
- Variables.
- Etiquetas.
- Entry point.
- Metadatos.
- Capacidades.
- Directivas ignoradas o implementadas parcialmente.
- Orden de las secciones.
- Relleno de huecos.
- Direcciones absolutas frente a offsets relativos.

Una directiva que solo modifica una variable interna, pero no tiene el efecto esperado en el binario, no debe considerarse funcional.

## 6.3. Resolución de símbolos y direcciones

Comprueba formalmente la coherencia entre:

- Primera pasada.
- Segunda pasada.
- Dirección de origen.
- Offset dentro del binario.
- Dirección virtual.
- Dirección física.
- Entry point.
- Etiquetas.
- Saltos absolutos y relativos.
- Tamaño real del código.
- Tamaño de la imagen.
- Alineamiento.
- Secciones y constantes.

Usa programas mínimos con direcciones conocidas para comprobar el resultado esperado byte a byte.

Determina si las etiquetas representan direcciones absolutas o posiciones relativas y si el runtime interpreta esos valores con la misma convención.

## 6.4. Codificación binaria

Documenta el formato binario efectivo campo por campo.

Para cada campo, identifica:

- Offset.
- Tamaño.
- Endianness.
- Tipo.
- Valores permitidos.
- Valores por defecto.
- Reglas de validación.
- Productor.
- Consumidor.

Comprueba especialmente:

- Magic bytes.
- Versión del formato.
- Longitud de cabecera.
- Longitud del código.
- Entry point.
- Capacidades.
- Secciones.
- Constantes.
- Hash o checksum.
- Bytes reservados.
- Padding.
- Datos truncados.
- Datos sobrantes.
- Versiones futuras o desconocidas.

Genera al menos una imagen binaria mínima y verifica sus bytes. Después, comprueba cómo los interpreta el runtime Java.

No asumas que dos campos son compatibles solo porque ambos ocupan el mismo número de bytes.

## 6.5. Integridad criptográfica

Si el formato incorpora un hash, comprueba:

- Si se calcula realmente.
- Qué bytes cubre.
- Si se incluye o excluye la cabecera.
- Si el hash se valida durante la carga.
- Si el valor se puede falsificar o queda sin comprobar.
- Si existe separación entre integridad accidental y autenticidad.
- Si los metadatos pueden modificarse sin invalidar el mecanismo.
- Si se usa un valor placeholder.

Distingue con precisión un hash calculado, un hash almacenado, un hash verificado y una firma autenticada. No los trates como equivalentes.

## 6.6. CLI y artefactos

Prueba todos los comandos documentados de la CLI.

Comprueba:

- Argumentos válidos e inválidos.
- Rutas inexistentes.
- Directorios de salida.
- Sobrescritura de archivos.
- Extensiones.
- Códigos de salida.
- Mensajes de error.
- Generación del binario.
- Generación del listado.
- Opciones no implementadas.
- Archivos parciales después de fallar.
- Comportamiento cuando el archivo contiene errores.
- Diferencias entre modo interactivo y modo batch, si existe.

Un comando que anuncia la generación de un artefacto pero solo imprime un TODO o no escribe el archivo correspondiente debe identificarse como funcionalidad incompleta.

---

# 7. AUDITORÍA DE LA VM, MEMORIA Y AISLAMIENTO

## 7.1. Modelo de memoria

Reconstruye el mapa real de memoria.

Identifica las regiones utilizadas y sus límites:

- Código.
- Constantes.
- Datos.
- Pila.
- Buffers.
- Memoria reservada.
- Regiones de dispositivos o DMA.
- Cualquier región adicional implementada.

Para cada región, indica dirección inicial, tamaño, permisos y método de inicialización. Si los límites dependen del programa, especifica cómo se calculan.

Comprueba:

- Solapamientos.
- Huecos.
- Alineamiento.
- Límites inclusivos o exclusivos.
- Direcciones negativas o desbordadas.
- Traducción de direcciones.
- Límites físicos.
- Límites virtuales.
- Páginas parciales.
- Cruces entre páginas.
- Acceso a regiones reservadas.
- Coherencia entre las tablas de páginas y la memoria física.

No inventes un mapa de memoria a partir de nombres o comentarios. Reconstrúyelo desde las operaciones que lo implementan.

## 7.2. Lecturas y escrituras

Audita individualmente los métodos de lectura y escritura.

Comprueba:

- Copias entre buffers.
- Índices de origen y destino.
- Offsets físicos y virtuales.
- Longitudes.
- Validación de límites.
- Permisos de lectura y escritura.
- Accesos a través de múltiples páginas.
- Operaciones de longitud cero.
- Arrays nulos.
- Overflow en `offset + length`.
- Modificaciones accidentales de memoria.
- Coherencia entre accesos byte a byte y por bloques.

Cuando encuentres una operación de copia, identifica de forma explícita cuál es el buffer fuente, cuál es el destino y qué rango de memoria puede modificarse.

No des por correcta una operación porque utiliza `System.arraycopy`, `ByteBuffer` u otra API estándar: comprueba los argumentos y sus efectos.

## 7.3. Permisos y seguridad de memoria

Comprueba si los permisos anunciados se aplican efectivamente a todas las rutas de acceso.

Investiga:

- Lectura desde páginas no legibles.
- Escritura en páginas de solo lectura.
- Ejecución desde regiones no ejecutables, si el modelo contempla ese permiso.
- Acceso mediante DMA.
- Acceso mediante servicios HOST.
- Inicialización de páginas.
- Cambios de permisos.
- Bypass de la MMU.
- Diferencias entre accesos directos y traducidos.
- Fallos de permisos durante la carga.
- Recuperación tras errores de acceso.

Distingue entre una política de permisos declarada y una política realmente impuesta.

## 7.4. Estado entre ejecuciones

Reconstruye el ciclo de vida de un contexto:

1. Creación.
2. Adquisición del pool.
3. Inicialización.
4. Ejecución.
5. Commit o rollback.
6. Liberación.
7. Limpieza.
8. Reutilización.

Identifica exactamente qué se reinicia y qué se conserva:

- Registros.
- Flags.
- Program counter.
- Memoria física.
- Pila.
- Buffers.
- Cachés.
- Journal.
- Propiedades.
- Estado compartido.
- Permisos.
- Referencias al programa.
- Tablas de páginas.

No afirmes que toda la memoria se limpia porque se inicializa la pila. Tampoco afirmes que todos los datos sobreviven porque existe una memoria reutilizada. Comprueba cada región por separado.

Si existe persistencia intencionada de estado entre mensajes, evalúa su semántica, sus límites, su control por configuración y sus implicaciones de aislamiento. Distingue una característica deliberada de una fuga accidental de estado.

## 7.5. DMA y mecanismos equivalentes

Si existe DMA u otro acceso indirecto a memoria, comprueba:

- Qué direcciones acepta.
- Quién puede invocarlo.
- Qué límites impone.
- Si respeta los permisos.
- Si puede escribir sobre código, constantes o estructuras de control.
- Si las validaciones son equivalentes a las del acceso normal.
- Si puede provocar inconsistencias entre la MMU y la memoria física.

No atribuyas privilegios ni capacidades que el código no demuestra.

---

# 8. AUDITORÍA DEL INTÉRPRETE Y DEL CICLO DE EJECUCIÓN

Sigue el ciclo de ejecución instrucción por instrucción.

Comprueba:

- Fetch.
- Decodificación.
- Validación del opcode.
- Lectura de operandos.
- Ejecución.
- Actualización de flags.
- Actualización del program counter.
- Accesos a memoria.
- Gestión de excepciones.
- Consumo de fuel.
- Saltos y llamadas.
- Finalización normal.
- Aborto.
- Suspensión y reanudación, si existe.
- Tratamiento de instrucciones inválidas.

Busca:

- Copias innecesarias dentro del bucle.
- Asignaciones repetidas.
- Cálculos redundantes.
- Bucles con coste no acotado.
- Posibles errores de overflow.
- Condiciones de terminación incorrectas.
- Instrucciones que no consumen fuel adecuadamente.
- Operaciones costosas cuyo coste no se refleja en el presupuesto.
- Excepciones absorbidas o convertidas en resultados ambiguos.
- Estados parcialmente modificados después de un fallo.

Si existe un límite de fuel, determina qué operaciones consumen presupuesto y si el límite acota de verdad el coste computacional.

No confundas un límite de instrucciones con un límite estricto de tiempo, memoria o CPU.

---

# 9. AUDITORÍA DE LOS SERVICIOS HOST

Los servicios HOST constituyen una frontera crítica entre el programa virtual y el sistema anfitrión.

## 9.1. ABI de los servicios

Reconstruye el contrato real de cada servicio:

- Identificador.
- Argumentos.
- Registros utilizados.
- Longitudes.
- Buffers.
- Valores de retorno.
- Códigos de error.
- Opciones.
- Efectos secundarios.
- Permisos.
- Coste de ejecución.
- Reglas de validación.

Compara el contrato documentado, el contrato que genera el compiler y el contrato que consume el runtime.

Comprueba especialmente si los argumentos se transmiten con su longitud real, si los buffers se validan y si los registros utilizados tienen significados consistentes.

## 9.2. Capacidades y autorización

Comprueba:

- Cómo se declaran las capacidades en el programa.
- Cómo se codifican en el binario.
- Cómo se parsean.
- Cómo se asocian al programa activo.
- Cómo se comprueban durante la ejecución.
- Si la autorización es global o específica de cada programa.
- Si un programa puede invocar un servicio sin declarar la capacidad correspondiente.
- Si una capacidad declarada puede ignorarse.
- Si las capacidades desconocidas se rechazan o se aceptan.
- Si hay confusión entre registro de servicios y autorización de servicios.

Distingue tres conceptos:

1. Servicio implementado.
2. Servicio registrado.
3. Servicio autorizado y funcional para un programa concreto.

No los trates como equivalentes.

## 9.3. Integración real

Sigue cada servicio desde su registro hasta el componente anfitrión que debería realizar el trabajo.

Comprueba si los lectores, escritores, resolutores y demás dependencias se configuran efectivamente.

Un servicio que devuelve «no configurado», un error genérico o un valor por defecto no debe contabilizarse como funcional simplemente porque existe su implementación.

Identifica por separado:

- Servicio implementado y conectado.
- Servicio implementado, pero no conectado.
- Servicio registrado, pero sin implementación funcional.
- Servicio que solo funciona con determinados consumidores.
- Servicio no comprobado.

---

# 10. AUDITORÍA DE RESULTADOS, JOURNAL Y SEMÁNTICA DE POLÍTICAS

Examina la relación entre las decisiones de la VM y los efectos que se producen realmente en TextFormatter Suite.

Investiga:

- Resultados posibles.
- Valores por defecto.
- Validación de resultados.
- Decisiones de aceptar, cancelar, modificar o redirigir.
- Tratamiento de rate limits.
- Canales de destino.
- Mensajes y propiedades modificadas.
- Modificaciones parciales.
- Commit y rollback.
- Efectos secundarios irreversibles.
- Errores de servicios.
- Resultados desconocidos.
- Conversión de errores en decisiones válidas.
- Consistencia entre el resultado de la VM y el resultado observado por el consumidor.

Construye una tabla con esta estructura:

| Decisión o efecto | Generable por la ISA | Emitible por el compiler | Aceptado por el runtime | Validado | Aplicado al sistema anfitrión | Verificado por test |
|---|---|---|---|---|---|---|

Usa `Sí`, `No`, `Parcial` o `No verificado`, acompañados de la evidencia correspondiente.

No afirmes que una decisión funciona de extremo a extremo si solo has confirmado la creación de su objeto de resultado.

---

# 11. CONCURRENCIA, CICLO DE VIDA Y PUBLICACIÓN DE PROGRAMAS

Analiza las posibles carreras y condiciones de concurrencia.

Comprueba:

- Carga de programas mientras existen ejecuciones activas.
- Sustitución del programa publicado.
- Actualización de los mapeos de memoria.
- Visibilidad entre hilos.
- Uso de referencias atómicas.
- Orden de publicación.
- Consistencia entre programa, código, constantes y tablas de páginas.
- Reutilización de contextos.
- Acceso concurrente a servicios.
- Estado compartido.
- Mutabilidad de objetos aparentemente inmutables.
- Cachés compartidas.
- Registro y modificación de servicios.
- Liberación de recursos.
- Interrupciones y cancelación, si existen.

Para cada posible carrera, identifica:

1. Operaciones que pueden intercalarse.
2. Estado que podría observar un hilo.
3. Invariante que podría romperse.
4. Evidencia estática.
5. Condiciones necesarias para reproducirla.
6. Prueba de concurrencia propuesta o ejecutada.

No declares que una carrera se reproduce en producción sin evidencia. Pero tampoco descartes una carrera porque el código utiliza `AtomicReference` si la actualización lógica comprende varios objetos.

---

# 12. TESTS Y PRUEBAS REPRODUCIBLES

Los tests son evidencia, no una garantía automática.

## 12.1. Inventario de tests

Identifica todos los tests relacionados con iFlow y su compiler.

Para cada test, registra:

- Archivo y método.
- Qué comportamiento pretende verificar.
- Qué condiciones establece.
- Qué aserciones realiza.
- Qué casos no cubre.
- Si se ejecutó.
- Resultado observado.

Comprueba si los tests se descubren y ejecutan mediante el sistema de build real.

No confundas tests escritos, tests descubiertos y tests ejecutados.

## 12.2. Tests de regresión obligatorios

Propón y, si el entorno lo permite, ejecuta pruebas para los siguientes escenarios cuando sean aplicables:

### Compiler y binario
- Programa mínimo válido.
- Programa vacío.
- Opcode desconocido.
- Etiqueta no definida.
- Etiquetas duplicadas.
- Operandos ausentes.
- Literales fuera de rango.
- Directiva desconocida.
- Origen distinto de cero.
- Alineamiento.
- Secciones múltiples.
- Constantes.
- Saltos hacia delante y hacia atrás.
- Código truncado.
- Cabecera truncada.
- Versión incompatible.
- Metadatos inválidos.
- Hash incorrecto.
- Bytes sobrantes.
- Capacidades malformadas.

### VM y memoria
- Lectura y escritura válidas.
- Lectura y escritura fuera de rango.
- Copia de bloques.
- Copias solapadas.
- Acceso entre páginas.
- Escritura en página de solo lectura.
- Inicialización de constantes.
- Entrada fuera del código.
- Salto inválido.
- Instrucción truncada.
- Agotamiento de fuel.
- Excepción durante ejecución.
- Rollback tras fallo.

### Servicios HOST
- Servicio autorizado.
- Servicio no autorizado.
- Capacidad ausente.
- Servicio desconocido.
- Argumentos inválidos.
- Longitud cero.
- Buffer demasiado pequeño.
- Servicio sin dependencias configuradas.
- Error del componente anfitrión.
- Resultado válido.
- Resultado inválido.
- Routing con destino válido e inválido.

### Ciclo de vida
- Primera ejecución.
- Segunda ejecución con el mismo contexto.
- Reutilización del pool.
- Limpieza después de fallo.
- Sustitución de programa.
- Ejecuciones concurrentes.
- Publicación mientras existen ejecuciones activas.

Adapta la lista a las capacidades reales del proyecto. Si un test no es aplicable, explica por qué. Si no puede ejecutarse, proporciona el motivo exacto.

## 12.3. Pruebas basadas en propiedades

Cuando sea viable, diseña pruebas basadas en propiedades o fuzzing para:

- Parser del ensamblador.
- Resolución de símbolos.
- Codificador binario.
- Parser binario Java.
- Accesos de memoria.
- Decodificación de instrucciones.
- Coherencia entre compiler y runtime.

Define las propiedades que deberían cumplirse. Por ejemplo, que una imagen válida generada por el compiler pueda parsearse por el runtime y conservar sus campos semánticos.

No afirmes que una propiedad se cumple en general a partir de un único ejemplo.

---

# 13. CAPACIDAD FUNCIONAL COMBINATORIA

Esta sección es obligatoria y no puede sustituirse por una lista de instrucciones.

La pregunta no es únicamente si iFlow tiene operaciones básicas. La pregunta es si estas pueden componerse para producir políticas útiles, complejas, deterministas y controlables.

Diseña una batería de programas ensamblados que combine varias capacidades.

Como mínimo, evalúa los siguientes tipos de programa, si la ISA y los servicios permiten expresarlos:

1. Política de decisión condicional simple.
2. Varias condiciones encadenadas.
3. Comparación de varios campos de un mensaje.
4. Transformación de datos seguida de una decisión.
5. Bucle con contador y condición de salida.
6. Programa que invoque varios servicios HOST.
7. Programa con rutas alternativas según el resultado de una operación anterior.
8. Programa que produzca un error controlado.
9. Programa que agote el fuel.
10. Programa que combine varias modificaciones antes de confirmar el resultado.
11. Programa que reutilice estado entre mensajes, únicamente si esa semántica está soportada.
12. Programa que demuestre aislamiento entre contextos cuando sea un requisito del diseño.

Para cada caso, documenta:

- Objetivo.
- Fuente ensamblada.
- Capacidades necesarias.
- Bytes o imagen compilada cuando sea relevante.
- Estado esperado de los registros.
- Accesos de memoria esperados.
- Servicios invocados.
- Resultado esperado.
- Resultado realmente observado.
- Diferencias.
- Tests asociados.

**No presentes pseudocódigo como prueba de que la ISA puede expresarlo.** Si el programa requiere una instrucción o servicio que no existe, identifícalo como una limitación concreta.

Si no puedes ejecutar los ejemplos, etiqueta sus resultados como esperados, no observados.

Al terminar, clasifica el alcance funcional en tres dimensiones distintas:

- **Capacidad teórica:** qué puede expresar el conjunto de instrucciones.
- **Capacidad implementada:** qué operaciones están conectadas de extremo a extremo.
- **Capacidad demostrada:** qué comportamientos se han verificado mediante tests o experimentos.

No asignes una puntuación alta a la expresividad basándote únicamente en el número de instrucciones o en la ambición del diseño.

---

# 14. CONTRADICCIONES E INCONSISTENCIAS ENTRE DOCUMENTACIÓN Y CÓDIGO

Compara sistemáticamente los README, las especificaciones, los ejemplos, los comentarios, el compiler y el runtime.

Busca:

- Instrucciones documentadas que no existen.
- Instrucciones implementadas que no están documentadas.
- Formatos binarios incompatibles.
- Diferencias de endianness.
- Tamaños o offsets incompatibles.
- Directivas prometidas que no generan el efecto descrito.
- Servicios que no están conectados.
- Capacidades con semánticas distintas entre documentación y código.
- Garantías de aislamiento que el código no impone.
- Límites de ejecución incompletos.
- Opciones de CLI no implementadas.
- Ejemplos que no compilan.
- Fixtures que no pueden producir el resultado comentado.
- Tests que contradicen la implementación.
- Configuración que excluye componentes anunciados como activos.
- Comentarios obsoletos.
- Nombres que sugieren una garantía inexistente.

Para cada contradicción, indica qué fuentes entran en conflicto y cuál describe el comportamiento real, si se puede determinar.

Si no puedes decidir cuál es la intención correcta, no la inventes. Describe la discrepancia y las alternativas.

---

# 15. SEGURIDAD Y MODELO DE AMENAZAS

Evalúa la VM como un mecanismo que ejecuta programas potencialmente malformados o no confiables dentro de un servidor anfitrión.

Define explícitamente el modelo de amenazas que estás usando. No presupongas que iFlow pretende ser una sandbox de seguridad fuerte si el proyecto no lo establece, pero investiga qué garantías ofrece realmente.

Examina:

- Acceso a memoria.
- Bypass de permisos.
- Capacidades.
- Servicios HOST.
- Abuso de fuel.
- Consumo de CPU y memoria.
- Inputs malformados.
- Desbordamientos.
- Denegación de servicio.
- Estado residual.
- Aislamiento entre programas.
- Aislamiento entre mensajes.
- Concurrencia.
- Integridad del binario.
- Límites del parser.
- Errores que dejan estado parcial.
- Acceso indirecto a recursos anfitriones.
- Fallos de validación.
- Comportamientos inesperados ante excepciones.

Clasifica los problemas por impacto y condiciones de explotación. No llames «vulnerabilidad crítica» a cualquier bug.

Distingue entre:

- Bug funcional.
- Riesgo de estabilidad.
- Riesgo de aislamiento.
- Vulnerabilidad explotable bajo el modelo de amenazas declarado.
- Riesgo hipotético pendiente de demostrar.

No afirmes que una VM es segura únicamente porque interpreta bytecode en lugar de ejecutar código nativo.

---

# 16. RENDIMIENTO Y COMPLEJIDAD

Busca costes innecesarios y límites de escalabilidad, sin inventar benchmarks.

Analiza:

- Copias de arrays.
- Clonación de programas.
- Asignaciones por instrucción.
- Costes del intérprete.
- Traducción de memoria.
- Consultas a tablas de páginas.
- Coste de los servicios HOST.
- Journal de modificaciones.
- Creación y reutilización de contextos.
- Coste de inicialización.
- Coste de limpiar memoria.
- Contención entre hilos.
- Complejidad temporal de los algoritmos.
- Complejidad espacial.
- Costes en el peor caso.

Distingue tres niveles de evidencia:

1. **Coste estático identificable:** se deduce directamente de las operaciones.
2. **Riesgo de rendimiento:** el patrón puede resultar costoso según tamaño o frecuencia.
3. **Regresión o cuello de botella medido:** demostrado con un benchmark reproducible.

Si ejecutas benchmarks, registra el entorno, el tamaño de los programas, las iteraciones, las opciones de JVM y las mediciones. No extrapoles a hardware o cargas diferentes sin justificarlo.

No recomiendes reescribir la ISA o cambiar el tamaño de palabra sin identificar primero un requisito funcional o un problema medido que lo justifique.

---

# 17. INTEGRACIÓN Y CONFIGURACIÓN DEL PROYECTO

Comprueba cómo se incorpora iFlow al sistema de build y cómo lo consume el resto de TextFormatter Suite.

Investiga:

- Inclusión real de los módulos en Gradle.
- Dependencias entre módulos.
- Configuración de Java.
- Compatibilidad con versiones declaradas.
- Compilación de fuentes.
- Descubrimiento de tests.
- Artefactos producidos.
- Configuración de runtime.
- Inicialización de servicios.
- Puntos de entrada.
- Conexión con módulos consumidores.
- Componentes deshabilitados o excluidos.
- Diferencias entre documentación y configuración efectiva.

Distingue claramente:

- El archivo existe.
- El módulo está declarado.
- El módulo participa en el build.
- El módulo compila.
- Los tests se ejecutan.
- El módulo se carga en el entorno anfitrión.
- Los servicios están conectados.
- El flujo completo funciona.

No uses una de estas condiciones como prueba automática de las siguientes.

---

# 18. FORMATO OBLIGATORIO PARA CADA HALLAZGO

Cada hallazgo debe seguir esta plantilla:

## [ID] Título conciso del problema

**Estado epistemológico:** HECHO VERIFICADO / INFERENCIA JUSTIFICADA / HIPÓTESIS PENDIENTE / NO VERIFICADO / CONTRADICCIÓN.

**Severidad:** Crítica / Alta / Media / Baja / Informativa.

**Confianza:** Alta / Media / Baja, explicando brevemente por qué.

**Ubicación principal:** `directorio:archivo:línea-línea`.

**Ubicaciones relacionadas:** todas las referencias adicionales necesarias.

**Componente afectado:** componente o subsistema concreto.

**Comportamiento esperado:** qué debería ocurrir y cuál es la fuente de ese contrato.

**Comportamiento observado:** qué hace realmente el código o qué resultado produjo la prueba.

**Evidencia:** fragmentos mínimos de código, flujo de llamadas, configuración o resultados experimentales.

**Razonamiento:** explicación paso a paso que conecta la evidencia con la conclusión.

**Impacto:** consecuencias funcionales, arquitectónicas, de seguridad o rendimiento.

**Condiciones necesarias:** cuándo puede manifestarse el problema y cuándo no.

**Reproducción:** test, programa mínimo, comandos y resultado observado, si se dispone de ellos.

**Corrección recomendada:** modificación concreta y proporcionada.

**Regresión que debe añadirse:** test que impida que el problema reaparezca.

**Limitaciones:** qué parte de la conclusión todavía no se ha comprobado.

No rellenes apartados con suposiciones. Si un campo no es aplicable, indícalo.

No conviertas una lista de sospechas en una lista de bugs confirmados.

---

# 19. CLASIFICACIÓN Y PRIORIZACIÓN

Usa una clasificación estable de identificadores:

- `ISA-xxx`: instrucciones y semántica.
- `CMP-xxx`: compiler y ensamblador.
- `BIN-xxx`: formato binario.
- `MEM-xxx`: memoria, MMU y permisos.
- `VM-xxx`: intérprete y ejecución.
- `ABI-xxx`: interfaz de servicios HOST.
- `CAP-xxx`: capacidades y autorización.
- `POL-xxx`: políticas y resultados.
- `CTX-xxx`: contextos, pool y estado.
- `CON-xxx`: concurrencia.
- `SEC-xxx`: seguridad.
- `PERF-xxx`: rendimiento.
- `INT-xxx`: integración.
- `DOC-xxx`: documentación y contradicciones.
- `TEST-xxx`: cobertura y pruebas.

Asigna un ID único a cada problema. Si varios síntomas tienen una misma causa raíz, agrúpalos cuando resulte razonable, pero conserva las ubicaciones y consecuencias de cada síntoma.

No multipliques artificialmente el número de hallazgos separando cada manifestación de un mismo defecto. Tampoco ocultes problemas independientes bajo un título genérico.

Para priorizar, considera:

1. Corrupción de memoria o de código.
2. Incumplimiento de permisos o aislamiento.
3. Incompatibilidad entre compiler y runtime.
4. Resultados funcionales incorrectos.
5. Servicios que no ejecutan la operación prometida.
6. Fallos de validación de entradas.
7. Condiciones de carrera.
8. Fallos de estabilidad.
9. Rendimiento.
10. Mantenibilidad y documentación.

La severidad debe reflejar el impacto real y las condiciones necesarias, no el dramatismo del título.

---

# 20. EVALUACIÓN DE LA ARQUITECTURA Y DE SUS ACIERTOS

La auditoría no debe limitarse a encontrar errores.

Identifica y justifica los aciertos reales de diseño, si los hay:

- Separación entre ISA y servicios HOST.
- Límites de ejecución.
- Gestión de capacidades.
- Modelo de memoria.
- Representación del programa.
- Journal de modificaciones.
- Rollback.
- Reutilización de contextos.
- Formato binario.
- Separación entre compiler y runtime.
- Extensibilidad.
- Portabilidad.
- Capacidad para construir políticas complejas.
- Facilidad de depuración.
- Testabilidad.
- Coste de mantenimiento.

Para cada acierto, explica:

1. Qué decisión existe realmente.
2. Dónde está implementada.
3. Qué ventaja proporciona.
4. Qué evidencia demuestra que funciona.
5. Qué límites tiene.
6. Qué defectos podrían neutralizar esa ventaja.

No atribuyas méritos a mecanismos que solo están documentados o que no se encuentran conectados al flujo real.

Evalúa también si la complejidad de cada mecanismo está justificada por el problema que resuelve.

---

# 21. PUNTUACIÓN FINAL

Asigna una puntuación de 0 a 10 a cada dimensión:

- Calidad de la arquitectura.
- Diseño y coherencia de la ISA.
- Corrección del intérprete.
- Corrección y robustez del modelo de memoria.
- Aislamiento y aplicación de permisos.
- Calidad del formato binario.
- Corrección del compiler.
- Coherencia compiler-runtime.
- Funcionalidad de los servicios HOST.
- Corrección de las decisiones y efectos de las políticas.
- Robustez ante entradas malformadas.
- Concurrencia y ciclo de vida.
- Rendimiento demostrado.
- Cobertura de tests.
- Calidad de documentación.
- Capacidad funcional combinatoria.
- Madurez global del módulo.

Para cada puntuación:

- Define qué significa un 10 en esa dimensión.
- Explica qué evidencia respalda la nota.
- Identifica los principales límites.
- Indica qué pruebas faltan.
- Evita falsa precisión.

Si una dimensión no puede evaluarse con evidencia suficiente, usa **«No evaluable»** en lugar de inventar una nota.

No confundas calidad conceptual con corrección implementada. Una arquitectura prometedora puede tener una implementación deficiente; una implementación correcta puede resolver un problema limitado sin ser una arquitectura general.

La puntuación global debe reflejar el estado funcional demostrado, no únicamente el potencial del diseño.

---

# 22. PLAN DE CORRECCIÓN

Al terminar el análisis, propone un plan de corrección priorizado.

Divide el plan en:

### P0 — Integridad y corrección fundamental
Defectos que puedan corromper memoria, romper invariantes básicos, invalidar el formato o permitir operaciones no autorizadas.

### P1 — Corrección funcional
Problemas que impidan ejecutar programas válidos, utilizar servicios o producir decisiones correctas.

### P2 — Robustez
Validación incompleta, errores de ciclo de vida, concurrencia, manejo de fallos y casos límite.

### P3 — Rendimiento y mantenibilidad
Optimizaciones demostrables, simplificación de contratos, reducción de duplicación y mejoras de observabilidad.

### P4 — Mejoras de diseño
Cambios arquitectónicos que aporten valor demostrado y no sean simples preferencias estéticas.

Para cada tarea, indica:

- IDs de los hallazgos relacionados.
- Archivos que deberían modificarse.
- Dependencias con otras correcciones.
- Riesgo de regresión.
- Tests requeridos.
- Criterio objetivo de aceptación.

No propongas una reescritura completa como respuesta automática. Antes de recomendarla, demuestra por qué una corrección localizada sería insuficiente.

---

# 23. ESTRUCTURA DEL INFORME FINAL

Entrega el informe en este orden:

1. **Resumen ejecutivo:** estado real, principales riesgos y conclusiones.
2. **Alcance y limitaciones:** commit, entorno, archivos y pruebas.
3. **Inventario y mapa arquitectónico.**
4. **Flujo completo compiler-runtime-host.**
5. **Especificación efectiva de la ISA.**
6. **Auditoría del compiler.**
7. **Auditoría del formato binario.**
8. **Auditoría de la VM y la memoria.**
9. **Auditoría de los servicios HOST y las capacidades.**
10. **Auditoría de políticas y resultados.**
11. **Estado, aislamiento y concurrencia.**
12. **Tests ejecutados y pruebas nuevas.**
13. **Capacidad funcional combinatoria.**
14. **Seguridad y rendimiento.**
15. **Contradicciones entre documentación e implementación.**
16. **Aciertos de arquitectura.**
17. **Registro completo de hallazgos con IDs.**
18. **Tabla de puntuaciones justificadas.**
19. **Plan de corrección priorizado.**
20. **Registro de dudas abiertas y verificaciones pendientes.**
21. **Registro de errores cometidos por el propio auditor**, si hubo alguno.

Incluye tablas cuando ayuden a comparar contratos o estados, pero no las utilices para sustituir el razonamiento técnico.

---

# 24. CONTROL DE CALIDAD ANTES DE ENTREGAR

Antes de concluir, realiza una segunda pasada centrada en verificar tus propias afirmaciones.

Para cada hallazgo, pregúntate:

1. ¿He leído el método completo o solo un fragmento?
2. ¿He seguido el flujo de llamadas relevante?
3. ¿He comprobado las condiciones previas?
4. ¿Existe otro componente que complete la operación?
5. ¿Estoy confundiendo una API disponible con una funcionalidad conectada?
6. ¿Estoy suponiendo una semántica que el código no garantiza?
7. ¿El problema es real o solo una preferencia de diseño?
8. ¿He exagerado su severidad?
9. ¿Puedo señalar el código exacto que demuestra el problema?
10. ¿Las líneas siguen siendo correctas?
11. ¿He afirmado que ejecuté una prueba que realmente no ejecuté?
12. ¿La conclusión depende de un comportamiento de Java, TypeScript, Gradle o del sistema operativo que no he comprobado?
13. ¿Hay evidencia que contradiga mi conclusión?
14. ¿La corrección recomendada resuelve la causa raíz?
15. ¿He confundido un riesgo potencial con un fallo reproducido?

Si una afirmación no supera esta revisión, corrígela, rebaja su nivel de certeza o retírala.

Al final, incluye un resumen de:

- Hechos confirmados.
- Inferencias razonables.
- Hipótesis pendientes.
- Afirmaciones descartadas.
- Limitaciones del acceso o del entorno.
- Pruebas que faltan para certificar el comportamiento.

---

# 25. INSTRUCCIÓN FINAL

No te apresures a redactar el informe.

Primero construye el modelo del sistema, sigue los flujos reales, contrasta compiler y runtime, verifica los contratos y ejecuta las pruebas disponibles. Después redacta las conclusiones.

No necesitas encontrar errores en todos los subsistemas. Si un componente está correctamente implementado y existe evidencia suficiente, dilo. Si un subsistema parece correcto pero no has podido verificarlo, dilo también.

No ocultes un fallo grave detrás de una puntuación global alta. No rebajes la calidad conceptual de todo el proyecto por un bug localizado. No declares que la VM es segura o insegura sin definir qué propiedad estás evaluando.

**La calidad de esta auditoría se mide por la precisión de sus conclusiones, la trazabilidad de la evidencia, la capacidad para detectar fallos reales y la honestidad sobre lo que no se ha podido comprobar; no por la longitud del informe ni por la cantidad de defectos encontrados.**

El resultado final debe permitir que otro desarrollador reproduzca los hallazgos, verifique cada afirmación y corrija los problemas sin depender de la confianza en el modelo que realizó la auditoría.
