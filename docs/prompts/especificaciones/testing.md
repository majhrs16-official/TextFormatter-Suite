# PROMPT MAESTRO DE PRUEBAS DE CAOS, SEGURIDAD Y CASOS LÍMITE

**ROL DEL AGENTE:**
Eres un Ingeniero Principal de Control de Calidad y Pruebas de Software (QA/SDET) especializado en pruebas de estrés extremo, fuzzing de sistemas embebidos/VMs, seguridad ofensiva y auditoría de concurrencia en entornos Java 21 y Minecraft (Spigot/Fabric/Velocity).

**OBJETIVO GENERAL:**
Diseñar e implementar una suite completa de pruebas de caos, seguridad extrema, fuzzing y casos límite para el proyecto **TextFormatter-Suite**, elevando la cobertura y resistencia del framework más allá de los casos felices y pruebas unitarias convencionales hasta duplicar el alcance actual de los tests.

Debes generar casos de prueba exhaustivos en **JUnit 5 / AssertJ / Mockito / ByteBuddy** dirigidos a los siguientes frentes y subsistemas principales:

---

### LOTE 1: `iFlow-VM` — Fuzzing de Bytecode, SoftMMU, Pila y Pool de Contextos

1. **Mutación Genética y Fuzzing de Bytecode de 32 bits:**
* Inyecta binarios `.iflow` con instrucciones corruptas, opcodes no registrados (`0xFE`, `0xFF`), operandos truncados o desbordados y cabeceras/Magic Numbers alterados.
* Modifica aleatoriamente bytes en medio de las instrucciones (operandos, opcodes, saltos) e inyéctalos masivamente.
* Verifica que el SoftMMU, el intérprete y los validadores de sección aborten inmediatamente limpiando los recursos y emitan excepciones controladas sin generar excepciones no manejadas ni colgar el pool de contextos o el hilo del servidor.


2. **Ataques de Fuga de Pila, Permisos de Página y Corrupción de Punteros:**
* Diseña bytecode en ensamblador virtual que realice saltos indirectos a direcciones no mapeadas o regiones de código sin permisos de ejecución (`ExecutePermission`).
* Simula manipulación maliciosa de registros de control (`FP`, `SP`) para intentar sobreescribir la memoria de constantes o kernel (`ProgramWritingConstantsKernelPageIsRejected`).


3. **Agotamiento Malicioso de Combustible (*Fuel Exhaustion Attack*):**
* Crea bucles infinitos hiper-anidados, recursividades profundas y llamadas recursivas a subrutinas.
* Verifica que el mecanismo de *Fuel* corte la ejecución de inmediato exactamente en el ciclo gastado/límite configurado, liberando el hilo sin bloquear el hilo principal y devolviendo una decisión de rechazo (*Fail-closed*).


4. **Estrés de Concurrencia, Descriptores y Memoria en `ExecutionContextPool`:**
* Ejecuta cientos de hilos adquiriendo, ejecutando y liberando `ExecutionContext` simultáneamente para cazar condiciones de carrera en el reseteo de los buffers DMA, registros y `ModificationJournal`.
* Satura el pool de contextos pidiendo más hilos de los permitidos bajo una carga artificial de presión de memoria (`OutOfMemoryError` simulado) para verificar que el recolector y el pool se recuperen o falten de forma segura (*Fail-closed*).



---

### LOTE 2: `iflow-firewall` & `host` — SpEL Inception, Event Storming y Hot-Reload Atómico

1. **Introspección Avanzada, Inyección y Evasión en SpEL (`iflow-firewall`):**
* Construye reglas con expresiones SpEL manipuladas que intenten hacer introspección mediante reflexión (`T(java.lang.Runtime)`, `java.lang.System`, acceso a `ClassLoader`, `getRuntime()`).
* Prueba bucles de evaluación anidados y llamadas a métodos estáticos prohibidos.
* Verifica que el Sandbox de SpEL invalide o bloquee la evaluación de forma segura (*Fail-closed*) sin evaluar expresiones arbitrarias ni permitir escalada de privilegios a nivel de motor.


2. **Acondicionamiento de Rate-Limiter bajo Caos (Event Storming & DoS Lógico):**
* Simula ráfagas de 50,000 a 100,000 eventos de chat concurrentes en microsegundos con patrones de canales aleatorios, comodines (`*`), emisores nulos y canales falsos.
* Comprueba que la memoria, los búferes y las ventanas temporales (`nanosUntilNextRateLimitWindow`) no sufran desbordamiento numérico, desincronización de hilos ni latencia acumulada bajo presión extrema de CPU.


3. **Hot-Reload Concurrente y Ataques en `ServiceLoader` (`host` & `manager-core`):**
* Modifica, registra, desregistra y recarga proveedores de traducción (`TranslatorProvider`), extensiones y módulos iFlow en caliente exactamente en el mismo instante en que hilos paralelos intentan despachar mensajes de chat masivos.
* Garantiza la inmutabilidad de la vista del `ServiceLoader`, asegurando que no queden referencias colgadas, no se rompan los proxies de red y se evite completamente `ConcurrentModificationException`.



---

### LOTE 3: `gtranslate` & `transport` — Red, Payloads Malformados y Colisiones de Caché

1. **Payloads Tóxicos e Inyección de Caracteres en Transportes:**
* Envía cadenas con secuencias UTF-8 truncadas o malformadas, bytes nulos (`\0`), inyecciones de código ANSI/JSON malformado, secuencias de escape de terminal y strings gigantescos de varios megabytes a través de `HttpTransport`.
* Confirma que `GTranslate` y el parser de JSON capturen los errores de parseo y respondan limpiamente con `TranslationException` sin bloquear el hilo ni congelar la caché.


2. **Simulación de Caos de Red (*Network Split-Brain*, Partition & Jitter):**
* Simula respuestas HTTP truncadas, códigos de respuesta inusuales/inesperados (`429 Too Many Requests`, `502 Bad Gateway`, `504 Gateway Timeout`), desconexiones abruptas a mitad de stream y retardos extremos/erráticos de red (latencias intercaladas con desconexiones de microsegundos).
* Prueba que la deduplicación de solicitudes concurrentes (`CompletableFuture`) devuelva el fallback adecuado sin provocar fugas de memoria, desalineación de futures o *deadlocks* en la caché.


3. **Colisiones en Caché y Deduplicación:**
* Dispara miles de solicitudes de traducción idénticas concurrentes con el mismo par de idiomas mientras la red falla o fluctúa.
* Verifica que el mecanismo de deduplicación evite condiciones de carrera y respete los límites de tamaño de caché.



---

### REQUERIMIENTOS TÉCNICOS Y FORMATO DE ENTREGA:

1. Proporciona clases de prueba completas, autónomas y compilables en **Java 21** usando **JUnit 5** (`@Test`, `@ParameterizedTest`, `@RepeatedTest`).
2. Utiliza pruebas parametrizadas con `@ValueSource` o `@MethodSource` para inyectar vectores de ataque, cadenas malformadas y payloads variados.
3. Asegura que cada prueba tenga aserciones explícitas (`assertThatThrownBy`, `assertThat`, `assertAll`) verificando tanto las excepciones esperadas como la integridad del estado interno del sistema antes y después de cada fallo simulado.
