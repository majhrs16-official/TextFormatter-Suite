# Auditoría técnica de iFlow — TextFormatter Suite

Repositorio analizado: [majhrs16-official/TextFormatter-Suite](https://github.com/majhrs16-official/TextFormatter-Suite) · Rama `main` · 15 de octubre de 2026

La cuestión central de esta auditoría no es si iFlow tiene una arquitectura interesante o si su intérprete ejecuta instrucciones. Es si la ISA, el ensamblador, el formato binario, el runtime Java, el modelo de memoria y la integración con el router forman un sistema coherente, seguro y funcional.

Voy a distinguir tres niveles de evidencia:

-

  HECHO

  &#x20;Comprobable directamente en los archivos, las declaraciones del proyecto o los resultados de pruebas que se hayan aportado.


-

  SUPOSICIÓN

  &#x20;Interpretación técnica plausible que necesita una prueba, una ejecución o más código para confirmarse.


-

  ALUCINACIÓN

  &#x20;Afirmación que se presenta como cierta sin evidencia suficiente, o que contradice la evidencia disponible. Esto incluye tanto afirmaciones exageradas sobre las capacidades como críticas que inventan fallos.



Una precisión importante: que un README diga que existen 250 tests y que pasan no demuestra que todas las propiedades arquitectónicas estén verificadas. Del mismo modo, que una clase tenga un nombre como `SoftMMU` o `VmDmaController` no demuestra por sí solo que sus garantías de seguridad sean correctas.

## 1. Primer hallazgo: la documentación no es una fuente de verdad suficiente

## Discrepancia verificable entre documentación y configuración de build

El README raíz describe una arquitectura con 29 módulos Gradle y presenta iFlow como una VM de 16 bits con memoria virtual de 64 KiB, paginación de 4 KiB, servicios `HOST` y un presupuesto de ejecución de 100.000 unidades.

Sin embargo, `settings.gradle` contiene esta declaración:

```
// include 'src:fabric-host'  // Temporarily disabled for Java 21 compatibility
```

HECHO: `fabric-host` está comentado en la configuración de Gradle inspeccionada, aunque la documentación también describe ese adaptador como parte del sistema. Esto no demuestra que el código Fabric no funcione; sí significa que no puede tratarse como un subproyecto activo de esa configuración sin verificar cómo se compila por otra vía.

Fuentes: [README raíz](https://github.com/majhrs16-official/TextFormatter-Suite/blob/main/README.md) · [settings.gradle](https://github.com/majhrs16-official/TextFormatter-Suite/blob/main/settings.gradle)

Esta discrepancia importa porque el proyecto afirma que la arquitectura está probada, pero hay que distinguir entre:

1. Código presente en el repositorio.
2. Código incluido en el build actual.
3. Código que compila.
4. Pruebas automatizadas que pasan.
5. Funcionalidad integrada en un servidor real.
6. Garantías de comportamiento bajo condiciones adversas.

Son niveles diferentes. No se puede ascender de uno a otro por inferencia.

También hay que tener cuidado con el estado temporal del repositorio: esta auditoría toma como referencia `main`, no una versión publicada e inmutable. El resultado debería asociarse a un commit concreto antes de convertirse en un informe de aceptación formal.

## 2. Hallazgos críticos en el runtime Java

He inspeccionado código de `VmPolicyEngine`, `VmInterpreter`, `ExecutionContextPool`, `ProgramImageParser`, `SoftMMU`, `HostServiceRegistry` y `PolicyResultValidator`. Los siguientes hallazgos ya no son simples dudas sobre el diseño: varios se desprenden directamente de las implementaciones.

IF-001 — Corrupción de memoria en `SoftMMU.readBlock()`

Crítico

HECHO. En la ruta rápida, `readBlock()` contiene esta operación:

```
System.arraycopy(
    physicalMemory, physAddr,
    physicalMemory, destOffset, length
);
```

La operación copia datos dentro de la propia memoria física de la VM, cuando el destino debería ser el array `dest` suministrado por el llamador. Inmediatamente después aparece la copia correcta hacia `dest`.

Esto puede sobrescribir memoria de la VM, incluido código u otros datos, y puede alterar las lecturas cuando origen y destino se solapan. El problema afecta a las operaciones que pasan por esta ruta rápida.

Archivo: [SoftMMU.java](https://github.com/majhrs16-official/TextFormatter-Suite/blob/main/src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/memory/SoftMMU.java).

IF-002 — ABI de `HOST` incompatible con los servicios que necesitan longitud

Crítico

HECHO. `VmInterpreter.executeHost()` establece `argLength = 0` de forma fija y pasa `R3` simultáneamente como `options`:

```
int argLength = 0;
int options = regs.get(RegisterFile.R3);

ServiceResult result = serviceRegistry.invoke(
    ctx, serviceId, argHandle, argOffset,
    argLength, options
);
```

Sin embargo, `HostServiceRegistry` usa `argLength` para leer y escribir buffers. Por ejemplo, `doPropertyWrite()` intenta leer una región de longitud `argLength`; `doPropertyRead()` comprueba si el resultado cabe en esa longitud.

En esta ruta, la longitud es cero. Por tanto, los servicios que dependen de ella no pueden realizar las operaciones documentadas de transferencia de datos.

La causa es más profunda que un parámetro mal asignado: el ABI actual intenta representar en un mismo registro tanto la longitud como las opciones o la decisión de routing. Hay que rediseñar esa convención, no limitarse a cambiar un valor.

IF-003 — Las capabilities no están aisladas por programa

Seguridad

HECHO. `HostServiceRegistry` mantiene un único `capabilityMap`. El método `register()` declara automáticamente las capabilities exigidas por cada servicio, y `invoke()` consulta ese mapa global. En `VmPolicyEngine.createServiceRegistry()` también se declaran explícitamente varias capabilities.

El registro no recibe la lista de capabilities de la `ProgramImage` al decidir si autoriza cada llamada. Por ello, el mecanismo inspeccionado no aplica la autorización por programa que describe el README.

Esto no demuestra que cualquier servicio tenga acceso arbitrario a Java: cada servicio conserva su propia implementación. Pero sí demuestra que el control de capabilities documentado no está aplicado en el punto de autorización como un permiso individual de cada programa.

IF-004 — La carga de constantes puede fallar por permisos de página

Alta

HECHO. En `VmPolicyEngine.setupProgramMappings()`, la página de constantes se mapea primero con permiso de solo lectura:

```
pageTable.mapPage(
    CONSTANTS_PAGE,
    CONSTANTS_PAGE * SoftMMU.PAGE_SIZE,
    PageEntry.PERM_READ
);
```

Después se intenta escribir en esa página mediante `mmu.writeBlock()`. Si `translateWrite()` aplica los permisos de escritura de la tabla —como exige el modelo descrito—, la operación falla.

El código también admite que el código ocupe varias páginas, pero las asigna desde la página cero. Un programa grande puede solaparse con las regiones reservadas para constantes, datos, pila y buffers.

La primera parte es un defecto de orden de inicialización si se confirma el comportamiento de `PageTable.translateWrite()`; la segunda es un riesgo de diseño que exige probar programas de diferentes tamaños y configuraciones de memoria.

IF-005 — Coste excesivo por copia del bytecode

Rendimiento

HECHO. `ProgramImage.code()` devuelve una copia defensiva del array de código. El bucle de `VmInterpreter.execute()` invoca `program.code()` para obtener el opcode en cada iteración, y `executeInstruction()` vuelve a solicitarlo.

Por tanto, una instrucción puede provocar una copia del programa completo, no solo una lectura del byte correspondiente. En un programa de \\(N\\) bytes ejecutado durante \\(I\\) instrucciones, esto puede elevar el trabajo de copia hasta el orden de \\(O(NI)\\), además del trabajo de interpretación.

La solución es conservar una representación interna inmutable o proporcionar acceso de lectura seguro sin clonar todo el bytecode en cada instrucción. La protección frente a modificaciones externas debe mantenerse, pero no necesita pagarse repetidamente dentro del bucle.

IF-006 — El parser binario no valida la integridad completa del archivo

Alta

HECHO. `ProgramImageParser.parse()` comprueba la longitud mínima de la cabecera y que las secciones de código y constantes no estén truncadas. Sin embargo, no valida de forma suficiente el conjunto de invariantes del formato:

- No verifica que las versiones del formato, ISA, ABI y firmware sean compatibles.
- No comprueba el hash SHA-256 como una condición de aceptación.
- No exige que las cadenas de capabilities terminen correctamente dentro de la sección esperada.
- No rechaza de forma explícita todos los bytes sobrantes o secciones malformadas.

El compilador también genera un array de 32 bytes de hash relleno con ceros, descrito como placeholder. Por tanto, el hash no constituye una garantía de integridad del artefacto compilado.

Esto no significa que cada archivo malformado consiga ejecutarse. Significa que el cargador todavía no establece una frontera de validación estricta y completa.

### 2.1. Un problema adicional: servicios anunciados que el motor no configura

`VmPolicyEngine.createServiceRegistry()` inyecta un comprobador de permisos, pero no configura allí los lectores y escritores de propiedades, el acceso al estado ni el resolver de canales.

HECHO: `HostServiceRegistry` devuelve errores como `Property reader not configured`, `State reader not configured` o `Channel resolver not configured` cuando faltan esas dependencias.

Esto significa que los servicios no están todos operativos por el mero hecho de estar registrados en la ISA o de existir sus implementaciones. Para considerar funcional una capacidad hay que verificar toda la cadena:

\\[ \text{Programa} \rightarrow \text{HOST} \rightarrow \text{ABI} \rightarrow \text{Servicio} \rightarrow \text{Dependencia configurada} \\]

Si cualquiera de esos eslabones falla, el servicio no ofrece la funcionalidad esperada.

Fuente: [HostServiceRegistry.java](https://github.com/majhrs16-official/TextFormatter-Suite/blob/main/src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/service/HostServiceRegistry.java).

## 3. Auditoría del compilador TypeScript

El compilador no es una herramienta auxiliar sin importancia. Es la otra mitad de la máquina: si genera instrucciones que el intérprete no entiende, un formato que el cargador interpreta de otra manera o un binario inválido que parece correcto, el diseño de la VM pierde valor independientemente de lo bien que esté escrito el intérprete.

He revisado `tools/iflow-compiler/README.md`, `src/assembler.ts`, `src/types.ts`, `src/cli.ts` y el programa `test/firmware.flowasm`.

Fuente principal: [tools/iflow-compiler](https://github.com/majhrs16-official/TextFormatter-Suite/tree/main/tools/iflow-compiler).

## IF-007 — El ensamblador no implementa correctamente el modelo de direcciones que anuncia

Alta

HECHO. La primera pasada calcula direcciones usando `.origin` y las directivas de alineación. La segunda pasada, sin embargo, genera el array de código concatenando las instrucciones:

```
const code: number[] = [];

for (const instr of this.instructions) {
  this.currentAddress = instr.address;
  code.push(instr.opcode);
  this.currentAddress++;
  this.emitOperands(instr, code);
  this.currentAddress += instr.size - 1;
}
```

No se insertan bytes de relleno para reproducir las direcciones calculadas.

Además, `getEntryPointAddress()` devuelve la dirección absoluta de la etiqueta de entrada, mientras que el intérprete valida que esa dirección sea menor que `program.codeSize()` y la usa como índice directo en el array de código.

Por tanto, un programa con origen distinto de cero puede tener etiquetas y punto de entrada que no correspondan con la posición real de las instrucciones en el bytecode. La opción `-O` y las directivas `.origin` no deben considerarse funcionales para programas con direcciones absolutas sin una solución explícita de reubicación.

## IF-008 — Las secciones y constantes no tienen una representación coherente

Alta

HECHO. `.section` cambia `currentSection`, pero la generación de código no utiliza esa información para separar segmentos. Las directivas `.const` añaden valores a `constantPool`, pero también crean etiquetas, con tratamiento diferente en el mapa de etiquetas y en el mapa de símbolos.

El propio ejemplo de firmware contiene declaraciones como:

```
.section data
.const "global"
```

La implementación inspeccionada de `.const` espera un nombre y un valor numérico, y llama a `parseNumber()` sobre el tercer elemento. No hay en esa ruta una implementación de literales de cadena como los del ejemplo.

Consecuencia: la sintaxis documentada y la sintaxis realmente aceptada por el ensamblador no coinciden por completo. No se puede dar por implementada la compilación de datos y cadenas únicamente porque aparezcan en el ejemplo.

## IF-009 — El hash no proporciona integridad criptográfica

Alta

HECHO. `createResult()` crea el hash así:

```
hash: new Uint8Array(32) // Placeholder for SHA-256
```

Y `serializeProgramImage()` escribe esos bytes en el archivo sin calcular un digest criptográfico.

El README describe una sección de hash SHA-256, pero el artefacto producido por esta ruta no tiene un SHA-256 calculado sobre su contenido.

Un campo de 32 bytes no equivale a una verificación de integridad. Antes de aceptar programas externos, hay que definir qué bytes se firman o hashean, cuándo se verifica el hash y qué ocurre cuando la verificación falla.

## IF-010 — La opción `-l` no genera el listado anunciado

Media

HECHO. `cli.ts` ofrece `-l` como opción para generar un listing. Sin embargo, `generateListing()` termina con un comentario `TODO` y un mensaje por consola. No escribe el archivo `.lst`.

El ejemplo del README afirma que la combinación `-d -l` compila el firmware, pero eso no demuestra que el listado se haya generado. La compilación y la generación de artefactos auxiliares deben verificarse por separado.

### Otras inconsistencias del compilador

| Área               | Hecho verificable                                                                                                                         | Impacto                                                                                                                                                                |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Codificación       | Los inmediatos de 16 bits y los desplazamientos de salto se serializan en orden de bytes alto primero.                                    | El README describe la codificación de instrucciones como little-endian, lo que no coincide con el código. La endianness debe definirse por campo, no de forma ambigua. |
| Caracteres         | Las capabilities se serializan mediante `charCodeAt(0)`, un carácter por byte.                                                            | Los nombres no ASCII no tienen una codificación UTF-8 correcta garantizada.                                                                                            |
| Tamaño             | La longitud de código se escribe en 16 bits sin que la ruta de serialización mostrada imponga un límite semántico completo al ensamblado. | Hay riesgo de que el binario generado no represente fielmente un programa demasiado grande.                                                                            |
| Diagnósticos       | El CLI solo aborta cuando encuentra diagnósticos con severidad `error`.                                                                   | Hay que verificar que todos los operandos inválidos, registros fuera de rango y expresiones malformadas se conviertan en errores, no en valores predeterminados.       |
| Firmware de prueba | El ejemplo contiene comentarios del tipo “R1 should be 30” y “All tests passed”.                                                          | Son expectativas escritas en el código fuente, no aserciones ejecutables. El nombre `firmware.flowasm` no demuestra por sí mismo que sea una prueba automatizada.      |

### ¿El ensamblador y el intérprete comparten la misma ISA?

Parcialmente, en cuanto a los opcodes y varios formatos de instrucción. Por ejemplo, ambos definen `LI` como opcode `0x00` y codifican sus inmediatos de 16 bits con el byte alto primero. Los saltos relativos también se codifican en dos bytes, y el intérprete los interpreta como desplazamientos relativos con signo.

Eso es un acierto: existe una base común para el contrato de instrucciones.

Pero el contrato todavía tiene huecos importantes. La existencia de códigos de opcode coincidentes no garantiza que las directivas, las direcciones, los operandos, las capabilities y el formato de archivo sean compatibles en todos los casos.

## 4. La integración de routing: donde la VM debe demostrar su utilidad real

El objetivo de iFlow no es ejecutar operaciones aritméticas por el mero hecho de poder ejecutarlas. Es tomar decisiones de política sobre mensajes y producir resultados que el resto de TextFormatter Suite pueda aplicar correctamente.

La cadena que hay que verificar es:

Mensaje + emisor + destinatario

`VmPolicyEngine`

Preparación de contexto y ejecución

ISA + memoria + `HOST`

Cálculo y solicitudes al entorno Java

`ModificationJournal`

Cambios y decisión diferida

`PolicyResultValidator` → `RouteOutcome`

Validación y resultado para el dispatcher

### IF-011 — Las decisiones de routing no tienen todas una semántica completa

HECHO. En `HostServiceRegistry.doRouteDecision()`, las decisiones `REDIRECT` y `RATE_LIMIT` se convierten en entradas `SET_PROCESSED`. En `PolicyResultValidator`, el resultado de routing se extrae de esas entradas, pero `backoffMillis` comienza en cero y no se asigna a un valor distinto en el fragmento inspeccionado.

La decisión `CHANNEL_REDIRECT` sí tiene una ruta específica que registra el canal de destino y que posteriormente se valida contra el registro de canales.

Esto produce una diferencia importante:

- `CHANNEL_REDIRECT` cuenta con un dato de destino explícito.
- `REDIRECT` no proporciona en esa implementación un destinatario alternativo concreto.
- `RATE_LIMIT` no calcula por esa vía un periodo de espera.

No afirmo que todas las formas de redirección o rate limiting de la suite sean imposibles: pueden existir mecanismos en el dispatcher o en otros módulos. Lo que sí se puede afirmar es que las decisiones anunciadas no están implementadas de forma equivalente dentro del servicio de routing inspeccionado.

Fuentes: [HostServiceRegistry.java](https://github.com/majhrs16-official/TextFormatter-Suite/blob/main/src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/service/HostServiceRegistry.java) · [PolicyResultValidator.java](https://github.com/majhrs16-official/TextFormatter-Suite/blob/main/src/iflow/src/main/java/me/majhrs16/suite/iflow/vm/engine/PolicyResultValidator.java).

### IF-012 — La publicación de un programa nuevo no es atómica respecto a su memoria

HECHO. `VmPolicyEngine.setProgram()` publica primero la `ProgramImage` mediante `programRef.set(program)` y después actualiza las asignaciones y el contenido de `sharedSoftMMU`.

Una evaluación concurrente puede observar el nuevo programa antes de que termine la actualización de la memoria compartida. Además, la ejecución obtiene la imagen de programa por separado de la copia de la MMU.

Por tanto, el cambio de programa y el cambio de su imagen de memoria no forman una única publicación atómica.

SUPOSICIÓN de impacto: durante una recarga concurrente, una evaluación podría ejecutar un programa con una imagen de memoria correspondiente a otro estado. La gravedad y la reproducibilidad exactas dependen de las intercalaciones entre hilos y de cómo se usen las páginas, pero la carrera es visible en la estructura de publicación.

La solución no es simplemente sincronizar una línea. Es crear un objeto de programa cargado que agrupe bytecode validado, tabla de páginas, memoria inicial y metadatos, y publicar una referencia inmutable a ese conjunto completo.

## 5. Modelo de memoria y ejecución: aciertos y límites

La VM contiene ideas técnicamente valiosas, pero hay que distinguirlas de las garantías que todavía necesitan demostración.

Acierto: memoria virtual con permisos explícitos

`PageTable` representa 16 páginas de 4 KiB y valida permisos de lectura, escritura y ejecución al traducir direcciones. `SoftMMU` canaliza el acceso ordinario a memoria mediante esa traducción.

Es una base razonable para evitar que los programas accedan libremente a cualquier región del proceso Java. No obstante, la protección solo funciona si todas las rutas de acceso la respetan, las páginas se asignan sin solapamientos y las operaciones por bloques validan el rango completo.

Acierto: registro de modificaciones diferido

`ModificationJournal` permite acumular cambios y descartarlos cuando falla la evaluación. `PolicyResultValidator` valida determinadas decisiones antes de confirmar el journal.

Este patrón es apropiado para un motor de políticas: evita aplicar directamente cada cambio al objeto original durante la ejecución del bytecode.

La limitación es que no todas las operaciones externas son necesariamente reversibles. El diseño debe especificar cuáles se registran, cuáles se ejecutan inmediatamente y qué significa realmente confirmar una transacción.

Acierto: presupuesto de ejecución

`ExecutionContext` tiene un contador de fuel, y el intérprete consume unidades por instrucción. `HostServiceRegistry` también asigna costes base y costes variables a los servicios.

Esto permite controlar mejor los bucles y el uso de servicios que un motor de expresiones sin presupuesto explícito.

Sin embargo, el presupuesto solo es útil si todos los caminos costosos están contabilizados. Hay que revisar también tamaños de buffers, asignaciones, llamadas externas, cancelación, profundidad de llamadas y el coste real de las operaciones de memoria.

Riesgo: reutilización de contextos y estado residual

`ExecutionContext.initialize()` limpia los registros, las flags, la caché de propiedades, el journal y los buffers de E/S. `ExecutionContextPool.release()` reinicia el contexto antes de devolverlo al pool.

Es un buen punto de partida para la reutilización, pero la memoria completa no se borra por defecto: se limpian los buffers de E/S, mientras que otras regiones de la MMU pueden conservar datos de una evaluación anterior.

En el código actual, `VmPolicyEngine.route()` asigna una copia de `sharedSoftMMU` al contexto antes de ejecutar. Eso cambia el análisis del estado residual: parte de la memoria puede heredarse de la imagen compartida y parte puede sobrevivir a través del ciclo de vida de esa imagen.

No es correcto afirmar que todos los datos de un mensaje anterior se filtran al siguiente, ni que se borran todos. Hay que definir la política de persistencia por región y demostrarla con pruebas de aislamiento.

### ¿Es correcta la arquitectura de 16 bits?

No la descartaría por ser de 16 bits. Para un router de mensajes, una ISA de 16 bits con 64 KiB de memoria virtual puede ser suficiente si los programas operan sobre buffers pequeños y acceden a los objetos del host bajo demanda.

Pero tampoco la daría por óptima solo porque sea sencilla.

| Perspectiva                | Evaluación                                                                                                                                                                    |
| -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Complejidad del intérprete | Favorable: registros y direcciones pequeños simplifican varias operaciones.                                                                                                   |
| Memoria por instancia      | El espacio de direcciones es pequeño, pero la copia de 64 KiB de memoria física por contexto tiene un coste que se multiplica con la concurrencia.                            |
| Mensajes y cadenas         | 64 KiB pueden bastar para muchos casos de chat, pero no garantizan espacio suficiente para todas las combinaciones de buffers, datos persistentes, código y transformaciones. |
| Extensibilidad             | La ISA puede seguir siendo compacta si las operaciones genéricas y los servicios del host están bien diseñados.                                                               |
| Rendimiento                | Las copias de memoria, la interpretación por instrucción y la materialización de datos pueden dominar sobre el coste de usar 16 bits.                                         |
| Evolución                  | El límite de direccionamiento es una restricción arquitectónica real. Aumentarlo posteriormente afecta al bytecode, a la ISA y al ABI.                                        |

Mi criterio es conservar provisionalmente los 16 bits, pero no congelar la ISA hasta resolver las incompatibilidades de carga, el ABI de `HOST`, el aislamiento y el coste del intérprete.

La decisión de pasar a 32 bits debería basarse en mediciones y en requisitos concretos de memoria, no en la idea de que una VM más grande es automáticamente más potente.

## 6. Hechos, suposiciones y alucinaciones: dictamen separado

Esta sección resume qué conclusiones considero defendibles y cuáles no deben convertirse todavía en afirmaciones definitivas.

### Hechos comprobados en el código inspeccionado

| ID   | Conclusión                                                                                                                      | Confianza |
| ---- | ------------------------------------------------------------------------------------------------------------------------------- | --------- |
| F-01 | `SoftMMU.readBlock()` realiza una copia adicional hacia `physicalMemory` cuando debería copiar únicamente hacia `dest`.         | Muy alta  |
| F-02 | El intérprete pasa una longitud fija de cero a los servicios `HOST`, aunque estos dependen de longitudes para transferir datos. | Muy alta  |
| F-03 | La autorización de capabilities usa un registro global, no una autorización específica de cada `ProgramImage`.                  | Muy alta  |
| F-04 | El compilador utiliza un hash SHA-256 de relleno en lugar de calcularlo.                                                        | Muy alta  |
| F-05 | El parser no aplica validaciones completas de versiones, hash y estructura de las secciones.                                    | Muy alta  |
| F-06 | La generación de código del ensamblador no materializa los huecos de dirección de `.origin` y `.align`.                         | Muy alta  |
| F-07 | `generateListing()` no escribe el listado que anuncia la opción `-l`.                                                           | Muy alta  |
| F-08 | El intérprete solicita copias defensivas del bytecode repetidamente durante la ejecución.                                       | Muy alta  |
| F-09 | La publicación del programa y la actualización de su memoria compartida son operaciones separadas.                              | Muy alta  |
| F-10 | Los servicios de propiedades, estado y canales pueden fallar por dependencias no configuradas.                                  | Muy alta  |
| F-11 | `settings.gradle` mantiene comentado el adaptador Fabric.                                                                       | Muy alta  |

Las referencias principales están enlazadas en las secciones anteriores. Los hallazgos corresponden al código de `main` que he inspeccionado, no a una versión histórica supuesta.

### Suposiciones que requieren experimentos

S-01 — La corrupción de memoria puede provocar fallos de routing.

Es una consecuencia plausible de F-01. La demostración debe ejecutar lecturas de bloques con distintas direcciones de origen y destino, verificar el contenido de la memoria antes y después y observar si el programa continúa ejecutándose correctamente.

S-02 — Las carreras de recarga pueden producir resultados inconsistentes.

La publicación separada de programa y memoria permite la intercalación problemática. Falta demostrar un caso reproducible con dos hilos y una recarga concurrente.

S-03 — El coste de las copias del bytecode puede ser un cuello de botella importante.

La complejidad de las copias está sustentada por el código. El impacto concreto en mensajes por segundo, latencia p95 y consumo de CPU requiere un benchmark.

S-04 — El modelo de 16 bits podría convertirse en una limitación para políticas avanzadas.

Es una posibilidad dependiente de los tamaños de código, datos, estado persistente y buffers reales. No existe evidencia suficiente para afirmar que 64 KiB sean insuficientes para los casos de uso actuales.

S-05 — El número de tests podría sobreestimar la cobertura semántica.

Es una hipótesis de auditoría, no una conclusión sobre los 250 tests. Para comprobarla hay que examinar las aserciones y ejecutar pruebas de integración de extremo a extremo.

### Afirmaciones que serían alucinaciones

Estas conclusiones serían injustificadas con la evidencia disponible:

- «La VM es insegura en todos los aspectos». Hay defectos concretos, pero eso no demuestra que todas sus barreras de seguridad sean ineficaces.
- «iFlow es una VM inútil». Los defectos del runtime no eliminan el valor potencial de la arquitectura ni de su separación entre ISA y host.
- «La memoria virtual de 64 KiB es necesariamente insuficiente». No se ha demostrado para los casos de uso reales.
- «Las 250 pruebas son falsas». El código inspeccionado permite cuestionar qué propiedades prueban, pero no permite declarar falsos los resultados de pruebas que no se han ejecutado aquí.
- «El sistema ya permite modificar cualquier propiedad del mensaje». Los servicios genéricos están planteados, pero su disponibilidad efectiva depende del ABI, la configuración y la implementación de cada operación.
- «El hash protege los binarios frente a manipulaciones». El compilador utiliza un placeholder; no hay una garantía criptográfica derivada de ese campo.

Esta separación es esencial: un defecto demostrado es un defecto; un riesgo arquitectónico es un riesgo; y una funcionalidad descrita en un README es una afirmación que todavía debe contrastarse con su implementación.

## 7. Evaluación arquitectónica desde distintos ángulos

### A. Diseño de la ISA

### Valoración: 7/10

Potencial del diseño, no calidad del runtime actual.

### Principal ventaja

Instrucciones generales y servicios de host.

La decisión de no crear una instrucción distinta para cada propiedad de mensaje es acertada. Una ISA pequeña, con operaciones de memoria, aritmética, control de flujo y llamadas a servicios, puede ser mucho más extensible que un motor compuesto exclusivamente por acciones predefinidas.

La contrapartida es que una instrucción genérica como `HOST` necesita un ABI excepcionalmente preciso. Si cada servicio interpreta los registros de una manera diferente o no se pueden expresar simultáneamente los argumentos, el tamaño del buffer y las opciones, la generalidad de la ISA queda neutralizada.

Mejora recomendada: especificar formalmente la codificación, los registros de entrada y salida, los errores, el coste de fuel y las capabilities de cada servicio. Mantener esa especificación como fuente única para Java, TypeScript, el firmware y las pruebas.

### B. Memoria y aislamiento

### Valoración: 5/10

Modelo interesante, garantías insuficientemente consolidadas.

### Principal riesgo

Integridad de las operaciones de memoria y de las regiones.

La combinación de una tabla de páginas, permisos R/W/X y una interfaz controlada hacia Java es una base razonable. No hace falta emular una CPU física completa para obtener esas ventajas.

Sin embargo, el diseño actual mezcla una memoria compartida utilizada como plantilla con copias por contexto y regiones que pueden conservar estado. También admite tamaños de código que pueden solaparse con regiones reservadas.

Mejora recomendada: definir una imagen de memoria validada e inmutable por programa; separar físicamente o mediante asignaciones no solapadas código, constantes, datos temporales, pila, buffers y estado persistente; y verificar los rangos completos antes de cada operación de bloque.

La política de persistencia debe ser explícita. El aislamiento no debería depender de que una llamada a `reset()` limpie accidentalmente suficiente memoria.

### C. Compilador y formato binario

### Valoración: 4/10

Existe un ensamblador funcional en estructura, pero con defectos de contrato.

### Principal riesgo

Que el artefacto generado no represente el programa previsto.

El uso de TypeScript para implementar un ensamblador de dos pasadas es una elección razonable. Permite mantener una herramienta independiente del servidor Minecraft y facilita generar programas reproducibles.

Pero antes de considerarlo un compilador fiable, hay que corregir las direcciones, las secciones, el tratamiento de constantes, el hash y el listado. También hay que comprobar que cada programa compilado se pueda cargar y ejecutar con la implementación Java de referencia.

Mejora recomendada: definir una especificación formal del formato `.flow`, un parser estricto y un conjunto de vectores de prueba compartidos. Cada instrucción debería tener un ejemplo binario esperado que ambos lenguajes interpreten exactamente igual.

### D. Routing y semántica de las políticas

### Valoración: 5/10

Integración planteada, cobertura funcional incompleta en lo inspeccionado.

### Principal riesgo

Confundir una decisión registrada con una acción efectivamente aplicada.

El `ModificationJournal` es uno de los mejores elementos del diseño. La capacidad de calcular una política y aplicar los cambios después de validarlos permite separar la ejecución de la decisión y la modificación del mensaje.

Pero una entrada en el journal no demuestra que una redirección, una cancelación, una limitación de frecuencia o una transformación se ejecute con la semántica correcta en todo el pipeline.

Mejora recomendada: crear pruebas contractuales para cada resultado de routing, desde el programa ensamblado hasta el `RouteOutcome` y su efecto final en el dispatcher. Incluir las combinaciones de transformaciones, permisos, redirecciones, rate limiting y errores de ejecución.

### E. Rendimiento y concurrencia

### Valoración: 5/10

Hay mecanismos de control, pero falta demostrar eficiencia y comportamiento concurrente.

### Principal riesgo

Copias de memoria y publicación no atómica.

El pool de contextos y el presupuesto de fuel son buenos mecanismos para contener costes. No obstante, el pool no impone estrictamente el máximo de contextos: si se agota, el código permite crear otro y solo comenta que podría ser conveniente bloquear o lanzar una excepción.

Además, copiar 64 KiB por contexto y copiar repetidamente el bytecode dentro del bucle de interpretación puede introducir costes evitables.

Mejora recomendada: benchmark con carga representativa, límites reales de concurrencia y métricas de asignaciones, latencia y fuel. La primera optimización debería ser eliminar las copias redundantes, no rediseñar toda la ISA prematuramente.

## 8. Qué cambiaría de la arquitectura y qué conservaría

No recomiendo desechar la VM ni volver automáticamente a un motor de expresiones. Recomiendo conservar sus ideas centrales y corregir primero los contratos que conectan sus componentes.

## Conservar

- ISA pequeña y genérica, independiente de las APIs de Bukkit o Fabric.
- Acceso al host mediante servicios explícitos en lugar de exponer objetos Java directamente.
- Presupuesto de ejecución y control de profundidad de llamadas.
- Journal de modificaciones y validación posterior del resultado.
- Ensamblador independiente, programa binario y runtime Java separados.

## Rediseñar o corregir antes de ampliar funciones

- ABI de `HOST`: argumentos, longitud, opciones, resultados y errores.
- Autorización de capabilities por programa y por servicio.
- Formato binario, integridad, validación de versiones y compatibilidad ISA/ABI.
- Mapa de memoria, inicialización de constantes y aislamiento de regiones.
- Publicación atómica de programa, memoria y metadatos durante hot reload.
- Semántica de todas las decisiones de routing y sus efectos finales.

### ¿Cambiaría la ISA a 32 bits?

No todavía. Una ISA de 32 bits no resolvería los defectos actuales del ABI, el parser, la memoria compartida o las capabilities. Incluso podría aumentar la complejidad de la implementación sin aportar un beneficio inmediato.

Sí reconsideraría la decisión si las pruebas muestran que los límites de memoria, la necesidad de buffers más grandes o el crecimiento de los programas hacen que 64 KiB sea una restricción real. Esa decisión debe tomarse después de corregir la semántica del sistema, no como sustituto de esas correcciones.

## 9. Plan de validación prioritario

Antes de añadir nuevas instrucciones, firmware más complejo o más servicios, ejecutaría este plan en orden.

## Fase 1 — Corregir los defectos que invalidan las pruebas

Progreso de revisión

0/6

Corregir \`SoftMMU.readBlock()\` y añadir pruebas de no corrupción de memoria.

Rediseñar el ABI de \`HOST\` y probar transferencias de longitud variable.

Implementar autorización de capabilities por \`ProgramImage\`.

Corregir la inicialización de constantes y los solapamientos de páginas.

Eliminar copias del bytecode dentro del bucle de interpretación.

Validar versiones, secciones, tamaños y hash del formato binario.

## Fase 2 — Verificar el compilador

- Probar `.origin`, `.align`, etiquetas hacia delante y hacia atrás, constantes numéricas y cadenas.
- Comprobar el round-trip: ensamblar, serializar, parsear y ejecutar.
- Rechazar registros inválidos, instrucciones truncadas, saltos fuera de rango y archivos malformados.
- Añadir tests que comprueben resultados mediante aserciones, no solo comentarios.
  ## Fase 3 — Verificar la semántica funcional
- Probar cada servicio `HOST` desde un programa real, incluidos los errores y las capabilities denegadas.
- Verificar combinaciones de transformaciones, routing, permisos y rate limiting.
- Confirmar que una ejecución fallida descarta las modificaciones y que una exitosa las aplica exactamente una vez.
- Probar hot reload concurrente y aislamiento entre contextos.
  ## Fase 4 — Medir
- Medir latencia por evaluación y throughput bajo carga.
- Comparar coste de ejecución con programas pequeños y grandes.
- Medir asignaciones y copias de memoria por mensaje.
- Probar concurrencia, agotamiento de fuel, buffers máximos y carga de programas malformados.

La condición de aceptación no debe ser «todas las clases existen» ni «el firmware parece completo». Debe ser que las combinaciones funcionales relevantes producen resultados correctos, reproducibles y seguros.&#x20;

## 10. Calificación final de iFlow

Estas notas son una evaluación técnica del estado observado, no una puntuación basada en el número de líneas, de clases o de tests anunciados.

NOTA GLOBAL DEL ESTADO ACTUAL

# 5,5/10

Potencial arquitectónico superior al estado de implementación verificado

Idea y arquitectura conceptual

#### 8.0/10

Diseño de ISA

#### 7.0/10

Separación entre VM y host

#### 7.0/10

Memoria y aislamiento

#### 5.0/10

Compilador y formato binario

#### 4.0/10

Integración funcional de servicios

#### 4.0/10

Robustez y seguridad

#### 4.0/10

Rendimiento demostrado

#### 4.5/10

### Mi veredicto

iFlow tiene una idea arquitectónica valiosa, pero su implementación actual necesita una corrección de contratos y de runtime antes de que pueda considerarse una base fiable para políticas programables.

Lo más prometedor es la separación entre ISA, memoria virtual, servicios del host y decisiones de routing. Esa separación puede permitir que el sistema crezca sin convertir el procesador en una colección de instrucciones específicas para cada propiedad del mensaje.

Lo más preocupante es que varias piezas fundamentales no coinciden todavía: el ABI de `HOST` no permite expresar correctamente las longitudes que necesitan los servicios; el control de capabilities no está aislado por programa; hay una corrupción de memoria en una operación básica; y el ensamblador no representa correctamente determinadas direcciones y secciones. Son defectos de fundamentos, no simples mejoras estéticas.

La prioridad no debería ser añadir más instrucciones ni ampliar la complejidad del firmware. Debería ser conseguir que un programa compilado por iFlow Compiler, cargado por Java y ejecutado contra un mensaje real tenga una semántica inequívoca, un aislamiento demostrable y un resultado de routing verificable.

Una vez que ese recorrido esté cubierto por pruebas de integración y por pruebas adversariales, la arquitectura tendrá una base mucho más sólida para justificar su complejidad y sus ventajas frente a un motor convencional de reglas.

Límite de esta auditoría: he inspeccionado documentación y código fuente relevante del repositorio, pero no he ejecutado el build ni los 250 tests anunciados en un entorno local. Por ello, las notas evalúan el diseño y los defectos encontrados en el código inspeccionado; no constituyen una certificación de todos los resultados de ejecución ni una auditoría completa de cada clase de iFlow.
