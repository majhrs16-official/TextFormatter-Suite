# ESPECIFICACIÓN TÉCNICA DE REFERENCIA v3 — iFlow VM (32 bits)

> **Origen y continuidad (2026-10-23):** este documento recoge la auditoría manual de iFlow del
> 2026-10-20 y se ratifica como **especificación técnica de referencia v3** de la línea **`iflow-vm`**
> (VM propia de 32 bits). Sus hallazgos y decisiones son requisitos de esa línea; **no se heredan**
> al motor paralelo **`iflow-wasm`** (WebAssembly/Chicory).

**Estado:** especificación técnica de referencia v3 (ratificada el 2026-10-23); la pasada original de
auditoría fue en solo lectura.
**Commit base de la pasada:** `2f0b217` (local, no publicado).
**Base de la discusión de arquitectura:** `4857997` (local, no publicado).
**Modo:** lectura estática y sondas locales sobre las clases compiladas; no se ejecutó la suite Gradle
ni pruebas de integración en esta pasada.
**Nota de reemplazo:** este documento sustituye la auditoría integral anterior. El contenido previo
queda en el historial de git; la historia no se reescribe.

> **Distinción de alcance:** la auditoría vigente comprende `IFLOW-MAN-01…41` y las preguntas 1–34.
> La sección `§3.7`, los hallazgos `IFLOW-MAN-42…45` y las preguntas 35–38 son **material diferido**:
> se conservan para la próxima sesión, después de reimplementar la VM con las nuevas specs, y no
> forman parte de las conclusiones ni de la remediación de esta pasada.

---

> **Alcance de esta pasada:** contratos de entrada, enlace host↔iFlow, `PolicyTarget`, carga de
> `.flow`, camino de transformación/canal, `HALT`/IRQ, MMU/bus/MMIO, parser, intérprete, servicios
> `HOST`, política de ejecución, firmware del compilador y primeras cuentas de la cola de rate limit.
> Quedan pendientes los tests, la integración host/TextFormatter y la validación E2E. La sección 8
> registra la dirección de arquitectura ratificada durante la revisión; no está implementada.

## 1. Mapa de lectura

| Bloque | Archivos leídos | Qué se verificó | Estado |
|---|---|---|---|
| Contratos de entrada | `Router`, `RouteDecision`, `RouteOutcome`, `PolicyTarget`, `PassThroughProgram`, `VmConfigLoader` | contrato, semántica de targets, fallback, carga inicial | ✅ leído |
| Enlace host↔iFlow | `DefaultTextFormatter`, `SuiteHost.deliver`, `MessageDispatcher`, `PolicyResultValidator`, `HostServiceRegistry` (routing/DMA/property) | cómo se transforma, rutea, formatea y entrega; `SET_CHANNEL` vs `CHANNEL_REDIRECT` | ✅ leído |
| Ejecución / IRQ | `VmInterpreter` completo, `ExecutionContext` (pila/cancelación/`irqHandler`), `VmPic`, `IrqTable`, `VmMemoryMap` | despacho de IRQ, estado de CPU, pila/CALL/RET, mapa de memoria, límites de 16 bits | ✅ leído |
| MMU / memoria | `SoftMMU`, `PageTable`, `PageEntry`, `PhysicalMemory`, `VmBus`, `VmDmaController`, `VmMemoryMap` | 64 KB, 16 páginas de 4 KB, copy-on-write, permisos, ownership, MMIO, DMA; límites para 32 bits | ✅ leído |
| ISA / parser | `Opcode`, `ProgramImage`, `ProgramImageParser`, fixtures `.flow` | magic, versiones, secciones, bounds, SHA-256, entry point, instrucciones | ✅ leído |
| Servicios / política | `HostServiceRegistry`, `VmPolicyEngine`, `PolicyResultValidator`, `MessageHostContext` | ABI `HOST`, capabilities, property, routing, input, fuel, carga y arranque | ✅ leído |
| Cola / rate limit | `RateLimiter`, `VmPolicyEngine` (rate limit de emisión), `MessageDispatcher` (rate limit por target) | dónde se aplica el límite, si hay cola o reintento | ✅ leído |
| Compilador / firmware | `tools/iflow-compiler/src/assembler.ts`, `types.ts`, `test/firmware.flowasm` | ROM embebida, vector de reset, POST, tablas IRQ, datos/constantes | 🟡 assembler leído; tests no ejecutados |
| Pendiente | suite de tests, integración host/TextFormatter, E2E y editor web | cobertura real, entrega/cancelación de evento, validación de extremo a extremo | ⏳ pendiente |

## 2. Definiciones para no confundir transformación con canal

Estas definiciones son **propuestas de trabajo**, no código aplicado. Sirven para separar dos
conceptos que hoy están mezclados en `PolicyTarget` y en el journal.

- **Transformar un mensaje:** mutar su contenido o sus metadatos sin cambiar su identidad de destino.
  Ejemplos: reescribir texto, resolver placeholders, cambiar formato, idioma, color, sonidos, flags.
- **Cambiar de canal:** cambiar el destino lógico del mensaje. Eso puede implicar otro tail de formato,
  otras reglas de permisos y, potencialmente, otro conjunto de destinatarios. No es solo una mutación
  de presentación.
- **Rutear:** decidir si el mensaje se entrega, se deniega, se cancela o se redirige. Es una decisión,
  no una mutación.
- **Entregar:** efecto final en el adaptador de plataforma. Solo ocurre si hay una decisión explícita
  de entrega.
- **Cancelar mensaje:** no entregar este mensaje concreto. **Cancelar evento:** cancelar el evento de
  la plataforma que originó el mensaje; son cosas distintas y hoy no existe un camino de vuelta al
  evento.

Conclusión conceptual: **`TRANSFORM_APPLY` debe quedarse en contenido/metadatos**. El cambio de canal
debe ser `SET_CHANNEL` validado. Si cambiar de canal exige re-evaluar permisos o destinatarios, eso es
una segunda fase de routing acotada, no una re-entrega recursiva.

## 3. Hallazgos confirmados

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| `IFLOW-MAN-01` | Baja | `Router.rules()` documenta que siempre lanza `UnsupportedOperationException`, pero devuelve `Collections.emptyList()`. | `Router.java:43-50` |
| `IFLOW-MAN-02` | Baja | `RouteOutcome.redirected()` solo reconoce `REDIRECT`; `RouteDecision.delivered()` también cuenta `CHANNEL_REDIRECT`. La asimetría no está documentada. | `RouteOutcome.java:22-24`, `RouteDecision.java:32-36` |
| `IFLOW-MAN-03` | Alta | `VmConfigLoader` hace *fail-open* tanto si falta `policy.flow` como si está corrupto: warning y `PassThroughProgram`. No distingue “no configurado” de “política rota”. | `VmConfigLoader.java:25-39` |
| `IFLOW-MAN-04` | Alta | `REDIRECT` a un actor concreto está roto: el target se guarda en `redirectChannel`, pero `RoutingResult` solo tiene un booleano `redirect` y `MessageDispatcher` siempre llama a `deliverConsole()`. El target nunca se lee. | `PolicyResultValidator.java:86-97`, `RoutingResult.java`, `MessageDispatcher.java:151-157` |
| `IFLOW-MAN-05` | Alta | `CHANNEL_REDIRECT` reconstruye el mensaje desde `messageWithResolvedSource`, no desde `result.message()`: pierde transformaciones previas. Después llama **una sola vez** a `host.deliver()` y comprueba `reResult.decision().delivered()`. Como `delivered()` incluye `CHANNEL_REDIRECT` y `REDIRECT`, un segundo redirect no se sigue: se entrega al recipient original con `reResult.rendered()` y el `redirectedMsg` sin usar `reResult.message()`, o se ignora el target de consola. No hay recursión, pero sí truncamiento silencioso y pérdida de las transformaciones del segundo salto. | `MessageDispatcher.java:158-176` |
| `IFLOW-MAN-06` | Alta | `SET_CHANNEL` no valida que el canal exista; `CHANNEL_REDIRECT` sí. Si el canal no existe, el formateador puede caer al ancestro o al default. Además, `SET_CHANNEL` no re-evalúa permisos; `CHANNEL_REDIRECT` sí. No son equivalentes. | `PolicyResultValidator.java:160-166`, `DefaultTextFormatter.java:44-60` |
| `IFLOW-MAN-07` | Media | `TRANSFORM_APPLY` solo reescribe texto (placeholders/detección de idioma). No cambia canal, formato ni destino. Si “transformar” incluye cambiar canal, el nombre y el contrato actuales son incompletos. | `HostServiceRegistry.java:470-508` |
| `IFLOW-MAN-08` | Alta | El validador arranca con `target = LOG`; si la VM no llama a `ROUTE_DECISION`, el mensaje se entrega. Si la VM falla, se devuelve `REJECT` y no se entrega. **Silencio = permitir; crash = denegar**, al revés de un firewall. | `PolicyResultValidator.java:70-75`, `VmPolicyEngine.java:181-260` |
| `IFLOW-MAN-09` | Media | `LOG` no escribe en ningún sink: solo registra `SET_PROCESSED` y `Message.processed=true`. No se encontró ningún consumidor de `processed()`. No hay diferencia práctica con “permitir”. | `HostServiceRegistry.java:618`, `PolicyResultValidator.java:78-85,174` |
| `IFLOW-MAN-10` | Media | `DROP` y `REJECT` no se diferencian en entrega: ambos descartan. Solo cambia el log de debug (`DROP` silencioso, `REJECT` con línea). `CONNECTION_LOST_MARKER` solo aparece en `isConnectionLostMarker()` y en un test; no se escribe en producción. | `MessageDispatcher.java:329-334`, `PolicyTarget.java:26-31` |
| `IFLOW-MAN-11` | Alta | `RATE_LIMIT` no encola ni reintenta. El rate limit de emisión es un token bucket que, al agotarse, devuelve un `DispatchReport` con 0 entregas; el target `RATE_LIMIT` del VM solo guarda `backoffMillis` y el dispatcher lo trata como silenciado. No hay cola, ni scheduler de reintento, ni timer IRQ. | `MessageDispatcher.java:98-113,180`, `HostServiceRegistry.java:601-625`, `VmPolicyEngine.java:641-664` |
| `IFLOW-MAN-12` | Alta | `HALT` es terminal, no un `HLT`: pone `IP = codeSize` y el bucle termina. El despacho de IRQ ocurre al inicio del bucle; después de `HALT` ya no hay límite de instrucción donde despachar. No hay timer IRQ ni flag global de interrupciones. | `VmInterpreter.java:73-76,297-302,469-505`, `VmPic.java`, `ExecutionContext.java:310-322` |
| `IFLOW-MAN-13` | Media | La cuenta de cola de 16 bits es viable solo para payload puro. 24 KB / 256 B = 93,75 mensajes; una entrada real necesita deadline, estado, canal/destinatario o handle. El mensaje completo no debe estar en la VM: el host puede guardar el objeto y la VM solo el handle. | `VmMemoryMap.java:80-100`, `MessageDispatcher.java`, `Message` |
| `IFLOW-MAN-14` | Media | La CPU conoce la tabla de IRQ del BIOS vía `ExecutionContext.irqHandler()` y `IrqTable` sobre NVRAM. Es acoplamiento a un firmware concreto; la CPU debería hablar con un `InterruptController`/`InterruptVectorTable` genérico y el firmware decidir dónde vive la tabla. | `ExecutionContext.java:306-322`, `IrqTable.java` |
| `IFLOW-MAN-15` | Baja | La fórmula propuesta “longitud / cantidad de nulos = tamaño del array” no es correcta: da el promedio de bytes por elemento. El número de elementos es la cantidad de nulos (si hay terminador final) o nulos + 1 (si no). El array de destinatarios tampoco cabe en 12.000 entradas dentro del buffer de entrada de 4 KB. | Propuesta de ABI, no implementada |

### 3.1. Pasada de memoria, bus, CPU, IRQ y servicios

> Confirmado por lectura y por sondas locales contra las clases compiladas del código actual. Las
> sondas no forman parte del repositorio y no se modificó código de producción.

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| `IFLOW-MAN-16` | Alta | `VmBus.readWord/writeWord` eligen el dispositivo solo por la dirección inicial; una palabra que cruza RAM↔MMIO o dos dispositivos no se divide. Sonda: `readWord(0x0FEF)` lanza `AIOOBE` en `VmNvram` (índice 224); `writeWord(0x0F0F, 0x1234)` escribe solo el byte bajo en el registro de pantalla y deja `0x0F10` de NVRAM intacto; `readWord(0x0EFF)` devuelve la palabra de RAM aunque el byte alto caiga en MMIO. `readBlock/writeBlock` sí dividen por byte; el fallo es exclusivo del camino de palabra. | `VmBus.java:100-143`, `VmNvram.java:54-70` |
| `IFLOW-MAN-17` | Alta | `deliverPendingInterrupt` hace `pic.beginService(irq)` y `ctx.enterInterrupt(ip)` antes de `ctx.enterCall` y del push del retorno. Si la pila falla (por ejemplo `maxStackSize=0`), la excepción deja `irqDepth=1`, la línea `inService` y `current=irq`, pero `pending=0`; el handler nunca corrió y, con el PIC persistente, la línea queda enmascarada. La secuencia de commit del IRQ no es atómica. | `VmInterpreter.java:491-505`, `ExecutionContext.java:332-350` |
| `IFLOW-MAN-18` | Media | `PageTable.mapPage` solo valida alineación, no que el frame físico exista. `mapPage(0, 0x10000)` y `mapPage(0, -4096)` se aceptan; el acceso posterior termina en `AIOOBE` de `PhysicalMemory` (índice 16 o 1048575), no en `VmException`. El problema crece con frames dispersos de 32 bits. | `PageTable.java:65-75`, `PageEntry.java:33-35`, `PhysicalMemory.java:103-109` |
| `IFLOW-MAN-19` | Media | No se verifica que los destinos de control sean frontera de instrucción. `executeJump` lo admite (“simplified: just check it's valid”); `executeCall` y el handler de IRQ solo comprueban rango; `executeReturn` no comprueba ni rango ni frontera; el parser solo valida `0 <= entryPoint < codeLength`. Sonda: un `JMP` dentro de un inmediato ejecuta bytes como `MOV` y un `entryPoint` dentro de un `LI` llega a `HALT`. | `VmInterpreter.java:448-465,508-555`, `ProgramImageParser.java:183-187` |
| `IFLOW-MAN-20` | Alta | `ROUTE_DECISION` con `REDIRECT` y longitud 0 o negativa registra `SET_PROCESSED` (LOG, permite) en lugar de error; con `CHANNEL_REDIRECT` registra un canal `null`, el validador omite la comprobación de existencia y el dispatcher cae a consola. Sonda: `REDIRECT` longitud 0 y -1 devuelven `OK` y `SET_PROCESSED`; `CHANNEL_REDIRECT` longitud 0 devuelve `OK` y `REDIRECT_CHANNEL=null`. Un test actual fija el caso nulo como válido. | `HostServiceRegistry.java:580-625`, `PolicyResultValidator.java:87-124`, `MessageDispatcher.java:158-176` |
| `IFLOW-MAN-21` | Media | `argLength` negativo de `HOST` no se valida de forma central. `VmBus.getRegion` hace `new byte[length]` antes de leer; los servicios pasan `R3` sin comprobar y un valor negativo produce `NegativeArraySizeException`, que el intérprete convierte en `HOST_ERROR` (o un `REJECT`) en vez de `INVALID_ARGUMENT`. | `VmBus.java:157-160`, `HostServiceRegistry.java:393-412,580-609`, `VmInterpreter.java:108-114` |
| `IFLOW-MAN-22` | Media | `messageProperty` usa `String.valueOf` para idioma, color y formato; cuando el valor es `null` devuelve la cadena `"null"`. `PROPERTY_EXISTS` la considera presente y la autodetección de `PROP_LANG_SOURCE` solo actúa si el valor es `null` o vacío, así que nunca se activa. | `VmPolicyEngine.java:503-515`, `HostServiceRegistry.java:421-435,266-272` |
| `IFLOW-MAN-23` | Media | `prepareInput` escribe campos NUL-separados sin longitud ni escape. Un `U+0000` en el texto trunca el campo y desplaza los siguientes; un NUL en emisor/canal puede inyectar campos. Además la truncación a 256 bytes puede partir un carácter UTF-8 multibyte. | `VmPolicyEngine.java:355-395` |
| `IFLOW-MAN-24` | Media | Las instrucciones de bloque del guest (`COPY`/`MOVE`) usan `ctx.dma()`, que es un bus `SUPERVISOR`, no el bus `PROGRAM` de la CPU. Sonda: con una página `mapPageKernel` R/W, `COPY` la escribe correctamente mientras un `writeByte` directo con privilegio `PROGRAM` es denegado. Hoy no hay páginas de kernel escribibles expuestas, pero se rompe la separación de dominios y cualquier página kernel futura sería escribible por el guest. | `VmInterpreter.java:244-260`, `ExecutionContext.java:465-473`, `VmBus.java:41-54` |
| `IFLOW-MAN-25` | Media | `VmDmaController.validateRange` calcula `endAddr = vaddr + length` en `int`; con desbordamiento (`0xFFFF + Integer.MAX_VALUE`) el rango se acepta y no se valida ninguna página. La clase promete transferencias “all-or-nothing”, pero `copyBlock` lee y luego escribe sin rollback; el camino MMIO de `VmBus.writeBlock` escribe byte a byte y puede dejar el destino parcial si un dispositivo falla. | `VmDmaController.java:20,144-159,198-228`, `VmBus.java:136-143` |
| `IFLOW-MAN-26` | Media | `VmPic` guarda un solo `current`. Si se anida una IRQ, `beginService` de la segunda pisa `current`; el ACK de la interna lo deja en `-1` y el stub del BIOS, que lee `CURRENT`, puede hacer ACK de la línea 15 al volver al handler externo. `inService` conserva las líneas, pero no hay pila de líneas activas. | `VmPic.java:52-53,122-145`, `assembler.ts:478-483` |
| `IFLOW-MAN-27` | Media | `HostServiceRegistry.invoke` cobra el fuel base antes del servicio y el variable después. `STATE_WRITE` escribe el estado del host de forma inmediata; si el fuel variable se agota, la ejecución termina en `REJECT` pero el estado ya cambió. No hay journal ni rollback para los efectos del host. | `HostServiceRegistry.java:393-402,654-664` |
| `IFLOW-MAN-28` | Media | El mapa de 64 KB solapa la apertura MMIO con la página de código 0. Con firmware desactivado, el assembler deja el código de usuario en `0x0000` y permite hasta 4 KB, así que puede llegar a `0x0F00`; la CPU ejecuta esos bytes desde RAM, pero un acceso de datos a la misma dirección se decodifica como dispositivo antes de la MMU. El código y el MMIO son el mismo byte con dos significados. | `VmMemoryMap.java:42-47,104-130`, `SoftMMU.java:329-331`, `VmBus.java:92-98`, `assembler.ts:630-637` |

### 3.1.x. Arquitectura de capas, estado persistente y concurrencia

> Hallazgos transversales que afectan la estructura completa de la VM y su viabilidad como máquina persistente multi-núcleo. Confirmados por lectura de `VmPolicyEngine`, `ExecutionContext`, `MessageHostContext`, `VmBus`, `VmInterpreter`, `MessageDispatcher` y `SuiteHost`.

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| `IFLOW-MAN-46` | Crítica | **`VmPolicyEngine` concentra BIOS + bootloader + kernel + router.** `setupProgramMappings()` mapea código, constantes, datos, stack, I/O, adjunta dispositivos (display, NVRAM, PIC) y escribe código/constantes con privilegio `SUPERVISOR` — todo trabajo de BIOS/bootloader. `route()` reconfigura `ExecutionContext` por mensaje, dispara `IRQ_MESSAGE` y ejecuta el entry point — trabajo de kernel/scheduler. No hay separación de capas: el router *es* la máquina. | `VmPolicyEngine.java:310-380` (`setupProgramMappings`), `VmPolicyEngine.java:200-266` (`route`), `VmPolicyEngine.java:470-540` (`setProgram`) |
| `IFLOW-MAN-47` | Crítica | **`ExecutionContext` mutable y reconfigurado por mensaje** rompe la persistencia real y la concurrencia futura. `contextPool.acquire()` + `configureContext()` inyectan `SoftMMU`, dispositivos, `hostState` en cada ejecución. El estado efímero (registros, PC, SP, FP, flags, fuel, journal, `hostContext`, `irqDepth`, `inInterrupt`) se mezcla con referencias a estado persistente. Si se quita `machineLock` o se añaden cores, dos contextos concurrentes comparten `SoftMMU` mutable sin aislamiento. | `ExecutionContext.java:180-230` (`configureContext`), `VmPolicyEngine.java:209-225`, `ExecutionContext.java:240-260` (`reset`/`initialize`) |
| `IFLOW-MAN-48` | Alta | **`MessageHostContext` constructor de 10 parámetros** acopla el contexto del mensaje a todo el host (`channels`, `permissions`, `rateLimit`, `serviceRegistry`). Viola "Tell, don't ask"; imposibilita testear sin montar el host completo. `initialize()` (líneas 400-420) es otro setter masivo que reconstruye el contexto al reciclarlo. El puerto `HostContext` debería ser solo lectura/escritura de estado (`readProperty`, `writeProperty`, `readState`, `writeState`, `readChannelProperty`). | `VmPolicyEngine.java:370-420` (constructor + `initialize`), `VmPolicyEngine.java:222` (instanciación en `route`) |
| `IFLOW-MAN-49` | Crítica | **No hay kernel / scheduler / cola dentro de la VM.** El host (`MessageDispatcher.dispatch()`) actúa como scheduler externo: expande destinatarios, maneja rate limit, parallaliza con `ExecutorService`, aplica timeouts, programa `sleepMillis`. La VM no tiene cola de mensajes, no tiene `HALT` real con timer IRQ, no tiene syscall `exec`/`yield`. El mensaje no es una IRQ atendida por el kernel; es un argumento de `route()`. | `MessageDispatcher.java:100-215`, `VmPolicyEngine.java:235` (`interpreter.execute(ctx, entryPoint)`), `VmInterpreter.java:73-76` (`HALT` terminal) |
| `IFLOW-MAN-50` | Media | **`VmBus` subutilizado; `VmPolicyEngine` wirea dispositivos directamente.** `VmBus` debería ser el único camino de transacciones (CPU `PROGRAM`, DMA `SUPERVISOR`, decode MMIO, word-split). En su lugar, `VmPolicyEngine.setupProgramMappings()` hace `mmu.attach(display)`, `mmu.attach(nvram)`, `mmu.attach(pic)` directamente. `VmBus` solo lo usan `VmInterpreter` (fetch/load/store) y `VmDmaController`. | `VmPolicyEngine.java:360-370`, `VmBus.java:41-54`, `VmInterpreter.java:150-200`, `VmDmaController.java:140-160` |
| `IFLOW-MAN-51` | Alta | **`machineLock` serializa toda la VM y oculta los defectos de diseño.** `route()` toma el lock, ejecuta `interpreter.execute()`, valida, suelta. Mientras el lock está tomado, ningún otro mensaje avanza. Si se elimina el lock para multi-núcleo (arquitectura objetivo §8: núcleos configurables detectados en instalador), el `SoftMMU` compartido, los dispositivos sin sincronización y `ExecutionContext` reconfigurado causan *data races* inmediatos. El lock es un parche que evita enfrentar la arquitectura. | `VmPolicyEngine.java:200` (`machineLock.lock()`), `VmPolicyEngine.java:265` (`finally machineLock.unlock()`), `IFLOW-MAN-47` |
| `IFLOW-MAN-52` | Crítica | **El mensaje no determina el flujo de ejecución; es solo una IRQ (`MESSAGE_IRQ`).** La arquitectura correcta (§8): el kernel atiende la IRQ, el userspace (programa `.flow`) usa `HOST` con `R2/R3` para leer parámetros del mensaje mapeados en memoria, hace su lógica, y finalmente invoca `HOST` con `DELIVER` para enviar según parámetros finales. Hoy `VmPolicyEngine.route()` recibe `Message` + `recipient`, prepara input en buffer `0x7000`, dispara `IRQ_MESSAGE`, y salta al entry point — el host dicta la semántica, no el guest. | `VmPolicyEngine.java:355-395` (`prepareInput`), `VmPolicyEngine.java:228` (`pic.raise(MESSAGE_IRQ)`), `HostServiceRegistry.java:260-290` (`HOST` services), `VmInterpreter.java:200-240` (`executeHost`) |
| `IFLOW-MAN-53` | Media | **La máquina de 32 bits debe ser multi-núcleo configurable.** La arquitectura objetivo (§8) prevé núcleos definidos en el archivo de configuración del módulo iFlow, detectados en el primer script de instalación del Module Manager. El diseño actual (`machineLock` global, `ExecutionContext` pool único, `SoftMMU` compartido sin aislamiento) no soporta esto. La transición a 32 bits requiere rediseñar el estado por core y el bus compartido. | `IFLOW-MAN-47`, `IFLOW-MAN-51`, `PLAN.md` §8 (arquitectura objetivo), `tools/module-manager` (instalador) |
| `IFLOW-MAN-54` | Alta | **`ExecutionContext` debe contener SOLO estado efímero por mensaje.** Estado efímero correcto: registros (R0-R15, PC, SP, FP, flags), fuel, journal, `hostContext`, `irqDepth`, `inInterrupt`, `startNanos`. NO debe contener ni reconfigurar: `SoftMMU`, dispositivos (display, NVRAM, PIC), `hostState` (global, se accede por referencia), `message`/`recipient`/`emitter`/`path` (vía `MessageHostContext` ligero). La persistencia (RAM, dispositivos, page tables) pertenece a `MachineState`/`LoadedProgram`, inicializada una vez por BIOS/bootloader/kernel. | `ExecutionContext.java:1-120` (campos), `ExecutionContext.java:180-230` (`configureContext`), `VmPolicyEngine.java:195-199` (comentario admite el workaround) |

### 3.2. Observaciones de compilador y firmware

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| `IFLOW-MAN-29` | Baja | `.section data` solo cambia un string interno: no mueve la dirección ni emite datos. `.const "global"` (dos tokens) se ignora en silencio; las etiquetas `channel_name` y `test_message` del fixture apuntan a la dirección de código y no hay bytes de cadena. El README del compilador y el fixture prometen una sección de datos que el assembler no implementa. | `assembler.ts:146-157,640-648,700-709`, `test/firmware.flowasm:345-353` |
| `IFLOW-MAN-30` | Media | El POST del firmware limpia solo la región de datos `0x3000-0x4FFF`; no limpia la pila `0x5000-0x6FFF`. `ExecutionContext.initialize` reinicia `SP/FP` y limpia solo entrada/salida, así que bytes de la pila de un mensaje anterior pueden leerse en el siguiente. Es una fuga de estado entre ejecuciones, aunque el frame activo se sobrescriba normalmente. | `assembler.ts:501-513`, `ExecutionContext.java:214-226`, `VmMemoryMap.java:61-66` |
| `IFLOW-MAN-31` | Baja | Se declaran propiedades que ningún `readProperty` implementa: `PROP_SOUNDS`, `PROP_PROCESSED`, `PROP_TRANSLATE` y `PROP_ACTOR_WORLD`. `PROPERTY_READ` devuelve `NOT_FOUND` y `PROPERTY_EXISTS` responde 0 aunque la constante sea pública; el ABI anuncia más de lo que expone. | `HostServiceRegistry.java:105-124`, `VmPolicyEngine.java:503-528` |

### 3.3. Nomenclatura y endianness

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| `IFLOW-MAN-32` | Baja | El loader dice “using pass-through” y la clase se llama `PassThroughProgram`. El runtime no puede asumir que el binario de fallback permite el mensaje: un fallback puede denegar, transformar o fallar. El nombre correcto es **fallback**, no *pass-through*. | `VmConfigLoader.java:32,39-41`, `PassThroughProgram.java:7-18`, `IFLOW-MAN-03`, `IFLOW-MAN-D06` |
| `IFLOW-MAN-33` | Media | El formato `.flow` v2 guarda magic, header y constantes en big-endian, y el intérprete lee los operandos multibyte (`LI`, `u16`) en big-endian, mientras la memoria del guest y `SoftMMU.readWord/writeWord` usan little-endian. `ProgramImageParser` convierte las constantes a `int` y `VmPolicyEngine` las vuelve a escribir en little-endian. Es una conversión deliberada entre dos convenciones; el objetivo es una sola convención little-endian en toda la VM, incluida la ROM y el stream de instrucciones. | `ProgramImageParser.java:42-45,82-87,127`, `assembler.ts:1280-1307`, `VmPolicyEngine.java:315-316`, `SoftMMU.java:173-185`, `VmInterpreter.java:154,176,185,201,592-594` |

### 3.4. Modelo de ejecución persistente y fuel

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| `IFLOW-MAN-34` | Alta | El fuel es una cota finita de instrucciones por ejecución (`consumeFuel(1)` por instrucción y coste adicional por servicio). Es incompatible con una máquina persistente: (1) un `sleep`/espera real cede la CPU y despierta por timer o IRQ, así que no se puede modelar con un presupuesto que solo avanza al ejecutar; si se implementa como busy-wait, el fuel lo corta; (2) el arranque BIOS → bootloader → kernel → scheduler es una secuencia larga que no pertenece a un mensaje y no tiene un presupuesto natural; (3) la vida de la máquina ya no termina al entregar un mensaje, por lo que `fuelBudget`/`fuelRemaining` no miden el recurso relevante. Un límite así o no alcanza para arrancar o no acota nada útil. | `VmInterpreter.java:84-85`, `ExecutionContext.java:240-251`, `HostServiceRegistry.java:393-402`, `PLAN.md:1953-1963` (`D36`) |

### 3.5. Rate limit, routing y tiempo

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| `IFLOW-MAN-35` | Media | Hay dos caminos redundantes para el mismo rate limit (`ROUTE_RATE_LIMIT` y `ROUTE_DECISION` con `DECISION_RATE_LIMIT`), y ambos hacen que el host/emulador calcule el backoff con el `RateLimiter` inyectado. La política —esperar, encolar o descartar— pertenece al software de usuario `.flow`; el hardware/emulador solo debe exponer mecanismos (RTC, timer, ciclos, `HALT`/sleep, scheduler) y el host no debe decidir. El defecto inmediato es la duplicación de contratos; el fondo es que la decisión está en la capa equivocada. | `HostServiceRegistry.java:299-305,603-613,747-765`, `VmPolicyEngine.java:168`, `IFLOW-MAN-11` |
| `IFLOW-MAN-36` | Media | El journal no tiene un `OpType.ROUTE_DECISION` explícito: `ROUTE_DECISION` usa `SET_PROCESSED` con `detail = "route:..."` y `PolicyResultValidator` parsea ese string. Varias decisiones compiten por “la última gana”; el flag “processed” y la decisión de routing comparten la misma entrada. La decisión es del guest; el host solo debería registrar una decisión ya tomada, no inferirla de strings ni mezclarla con el estado del mensaje. | `HostServiceRegistry.java:617-630`, `ModificationJournal.java:17-28`, `PolicyResultValidator.java:76-110,138-151` |
| `IFLOW-MAN-37` | Media | La ISA actual no expone tiempo ni ciclos al guest. `ExecutionContext.elapsedNanos()` mide tiempo de pared del host y no es accesible desde el programa; `HALT` existe, pero es terminal y no un `HLT` con timer; no hay RTC ni timer IRQ. La arquitectura objetivo sí debe permitir medir y dormir: el hardware expone RTC/timer/contador de ciclos y `HALT`/sleep, y el software de usuario decide qué hacer con ese tiempo. Falta el mecanismo, no la capacidad conceptual. | `Opcode.java:12-67`, `VmInterpreter.java:73-76,146-309`, `ExecutionContext.java:531-532`, `IFLOW-MAN-12` |
| `IFLOW-MAN-38` | Media | El journal difiere las escrituras, pero las lecturas de propiedades siguen viendo el mensaje original: `PROPERTY_WRITE` registra una entrada y `PROPERTY_READ` lee `message.text()`/`path`; `TRANSFORM_APPLY` modifica el buffer de entrada, no la vista del host. Una política que escribe o transforma y luego lee el mismo campo ve el valor viejo: no hay *read-your-writes* ni vista provisional. | `MessageHostContext.java:426-471,503-516`, `VmPolicyEngine.java:232-233`, `HostServiceRegistry.java:421-460` |

### 3.6. Dispatcher, timeout y expansión de destinatarios

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| `IFLOW-MAN-39` | Alta | El timeout de 10 s por destinatario no detiene de forma efectiva la ejecución: aunque `future.cancel(true)` interrumpa el hilo, el intérprete no consulta interrupciones y el task puede seguir. La ruta sigue ejecutándose bajo `machineLock`, mutando el estado persistente de la VM; las rutas siguientes quedan bloqueadas y el resultado se descarta. El `DispatchReport` la cuenta como silenciada mientras la máquina sigue ocupada. | `MessageDispatcher.java:145-213`, `VmPolicyEngine.java:200-266`, `VmInterpreter.java` (sin checks de interrupción) |
| `IFLOW-MAN-40` | Media | En el camino `sleepMillis > 0`, `delivered++` ocurre **antes** de que el `sleepScheduler` ejecute la entrega. Si `delivery.deliver` falla, solo se registra el error y el reporte ya contó la entrega; si `close()` apaga el scheduler, las tareas pendientes se pierden. Además, el scheduler es de un solo hilo, así que las entregas retrasadas se serializan detrás de la primera que bloquee. | `MessageDispatcher.java:186-203,342-356` |
| `IFLOW-MAN-41` | Media | La expansión de destinatarios es fail-open de forma inconsistente: `PERMISSION` con qualifier vacío y `WORLD` con `null` devuelven todos los jugadores online, mientras que un `WORLD` desconocido y un `RADIUS` inválido devuelven vacío. No hay validación ni error de configuración; un qualifier vacío o un `world(null)` puede convertir un envío dirigido en broadcast. | `MessageDispatcher.java:229-298` |

### 3.7. Material diferido: render, idioma y contrato de `Formats`

> **No forma parte de la auditoría vigente.** Esta sección se conserva para la próxima sesión, después
> de reimplementar la VM con las nuevas specs. No debe usarse como conclusión de esta pasada ni como
> base para remediar el código actual.

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| `IFLOW-MAN-42` | Media | El pipeline de render ignora la configuración de idioma del canal y el `langTarget` del mensaje. `Channel.langSource/langTarget` se cargan en `ConfigLoader` y se exponen en la API, pero `SuiteHost.effectiveLanguage` solo mira `recipient.language()`/default y `effectiveSource` solo `message.langSource()`/detección/default. `Message.langTarget` tampoco se lee. `lang-target` y `withLangTarget()` son inertes: el destino efectivo siempre es el idioma del recipient o el default. | `SuiteHost.java:163-207`, `Channel.java:89-95,132-139`, `Message.java:93-99`, `ConfigLoader.java:158-170`, `ConfigLoaderTest.java:113,156` |
| `IFLOW-MAN-43` | Media | `Formats` declara N textos/formatos paralelos, pero el pipeline solo renderiza el índice 0. `DefaultTextFormatter.format/formatTooltip` usan `merged.format(0)` y `SuiteHost.renderFor` pone `message.text()` (primer texto) en `%content%`; `MessageDispatcher` entrega un solo componente. Los formatos adicionales que `ConfigLoader` carga (el test comprueba `formats().length == 2`) nunca se emiten. Si el contrato es multi-mensaje, está incumplido; si no, la API y la configuración sobran. | `Formats.java:12-15,42-48`, `DefaultTextFormatter.java:45-62`, `SuiteHost.java:178-185`, `Message.java:132-139`, `ConfigLoaderTest.java:150` |
| `IFLOW-MAN-44` | Media | `REDIRECT` se renderiza con el contexto del destinatario, no con el del console ni el del target. `SuiteHost.deliver` calcula `lang = effectiveLanguage(recipient)` antes de `router.route` y llama `renderFor(..., recipient, lang)`; `MessageDispatcher` manda ese componente a `deliverConsole`. El console recibe el idioma y los placeholders del recipient. Cuando se implemente el target de `IFLOW-MAN-04`, el problema se extiende al actor destino. | `SuiteHost.java:163-175,178-185`, `MessageDispatcher.java:153-157`, `RoutingResult.java:16-17` |
| `IFLOW-MAN-45` | Baja | `Direction.channel(Channel)` es un contrato muerto: Spigot y el tester construyen `Direction.others().channel(Channel.CHAT)`, pero ningún consumidor lee `direction.channel()`; `MessageDispatcher.expand` usa `kind/qualifier` y el canal sale de `Message.channel()`. El override de canal del `Direction` no cambia nada y da falsa confianza. | `Direction.java:19-20,39-41,101-104`, `MessageDispatcher.java:236-249`, `TextFormatterSuitePlugin.java:698`, `TestService.java:509` |

## 4. Cosas que están bien y no hay que romper

- `DefaultTextFormatter` resuelve el canal **después** de aplicar el journal; un `SET_CHANNEL` sí cambia el tail de formato. El formateador no es el problema.
- El despacho de IRQ mientras la VM está corriendo sí usa la tabla, guarda estado y hace un CALL de hardware. El mecanismo existe; el problema es la secuencia de commit no atómica (`IFLOW-MAN-17`) y que `HALT` sigue siendo terminal (`IFLOW-MAN-12`).
- `PolicyResultValidator` valida que el canal de `CHANNEL_REDIRECT` exista **solo si no es `null`**; el caso nulo queda abierto (`IFLOW-MAN-20`).
- `VmBus.readBlock/writeBlock` sí dividen una transferencia que contiene un dispositivo aunque ninguno de sus extremos caiga en él; el fallo de `IFLOW-MAN-16` está limitado al camino de palabra.
- `SoftMMU.writeBlock` prevalida todas las páginas antes de escribir; el problema de `IFLOW-MAN-25` aparece cuando la validación se desborda o cuando la escritura llega a MMIO.
- `ProgramImageParser` verifica el SHA-256, que no sobren bytes y los tamaños de código/constantes; `IFLOW-MAN-19` es la validación que falta, no un parser sin controles.
- `VmDmaController` rechaza longitudes negativas en `copyBlock/moveBlock/validateRange`; el caso que falla es el desbordamiento de la suma y la atomicidad prometida.
- `RateLimiter` funciona como token bucket por `(canal, emisor)` para el límite de emisión previo al fan-out.
- `PassThroughProgram` es una única fuente de bytecode para el fallback y el programa por defecto; la idea de no duplicar bytecode es correcta.
- `VmMemoryMap` es una única fuente de verdad para el mapa de 64 KB; buena base para no hardcodear páginas.

## 5. Decisiones propuestas (pendientes de ratificar; no aplicadas)

| ID | Propuesta | Motivo |
|---|---|---|
| `IFLOW-MAN-D01` | **Default deny + `DELIVER` explícito.** Si la VM no llama a `DELIVER`, no se entrega. | Coherencia con firewall; elimina “silencio = permitir”. |
| `IFLOW-MAN-D02` | **`SET_CHANNEL` validado** como única mutación de canal. Si el canal no existe, error controlado; no fallback silencioso. | Evita que un typo cambie el formato sin avisar. |
| `IFLOW-MAN-D03` | **Eliminar `REDIRECT` y `CHANNEL_REDIRECT` como targets.** La consola es un canal/actor (`Channel.CONSOLE`, `actors.console()`); el cambio de canal es `SET_CHANNEL`. | Elimina el caso especial y la re-entrega recursiva. |
| `IFLOW-MAN-D04` | **Reducir `PolicyTarget`** a `DELIVER`, `CANCEL_EVENT` y `ERROR` (o equivalente). `DROP` deja de ser target: no llamar a `DELIVER` ya es drop. `LOG` se sustituye por pantalla/auditoría. | Menos estados muertos y menos semántica inventada. |
| `IFLOW-MAN-D05` | **Separar cancelación de mensaje y de evento.** `CANCEL_EVENT` debe volver al adaptador de plataforma, no solo marcar el `Message`. | Hoy `CANCEL_MESSAGE` no cancela el evento. |
| `IFLOW-MAN-D06` | **Fallback configurable:** `policy.flow` + `fallback-program` + `on-load-error` (`fallback`/`fail-closed`). Dos firmwares: allow-all y deny-all. Si el fallback también falla, deny-all + error visible o negarse a arrancar. | Distingue “no configurado” de “política rota” y permite lógica compleja por defecto. |
| `IFLOW-MAN-D07` | **`HLT` real** (estado `HALTED`, flag global de interrupciones, `IRQ` de timer, reanudación tras handler, timeout). Mantener `HALT` como terminación o renombrarlo a `EXIT`. | `HALT` actual no puede esperar ni despertar. |
| `IFLOW-MAN-D08` | **IRQ de mensaje configurable** hacia el entry point del programa de usuario; enmascarar las demás líneas durante la espera. | Evita despertar por cualquier IRQ. |
| `IFLOW-MAN-D09` | **Separar firmware/BIOS, bootloader y kernel.** El reset vector entra al firmware/BIOS; el BIOS hace POST e inicializa memoria, page tables, stack e interrupciones. El bootloader no inicializa hardware: solo carga el kernel de ROM a RAM y salta. El kernel gestiona scheduler/cola/tiempo y carga los programas. | No convertir el BIOS en kernel por accidente ni darle al bootloader responsabilidades del BIOS. |
| `IFLOW-MAN-D10` | **Nombres genéricos de hardware** en la CPU: `InterruptController`, `InterruptVectorTable`, `DisplayDevice`, `NvramDevice`, `Mmu`, `Bus`. | La CPU no debe depender de la estructura interna del BIOS. |
| `IFLOW-MAN-D11` | **Cola con handles, no con mensajes completos.** El host conserva los objetos; la VM guarda handles con generación/epoch y arrays separados de deadline/estado/prioridad. El ancho del handle (16 o 32 bits) se decide con el ABI; la dirección y la memoria son de 32 bits. En 32 bits la capacidad la fija el límite de RAM, no los 12.288 del VM actual. | Evita serializar el mensaje y convierte la cola en un recurso del kernel, no en un array gigante de objetos. |
| `IFLOW-MAN-D12` | **Firmwares de rate limit como producto:** `drop-on-saturation.flow`, `queue-on-saturation.flow`, `adaptive.flow` (decide según carga/prioridad). | Es la misión de iFlow: lógica compleja de chat. |
| `IFLOW-MAN-D13` | **Array de destinatarios null-separated** en un buffer acotado, con contrato explícito: UTF-8, contador de elementos = nulos (o nulos+1), máximo de bytes, comportamiento ante overflow. | Es viable, pero la fórmula de longitud debe corregirse y el buffer no es infinito. |

### 5.1. Decisiones ratificadas en esta pasada

Estas cuatro decisiones ya están ratificadas; no se han implementado.

| ID | Decisión | Motivo |
|---|---|---|
| `IFLOW-MAN-D14` | **Fallback neutral, no `PassThrough`.** La clase pasa a `FallbackProgram` y los mensajes dicen “using fallback”. El runtime no asume que el fallback permite el mensaje: es una política más y puede denegar, transformar o fallar. | El hardware/runtime no debe depender de la semántica de un binario de software. “Pass-through” describe una política concreta, no el rol de fallback. |
| `IFLOW-MAN-D15` | **Little-endian único.** Header, magic, segmentos, constantes, instrucciones, memoria y ROM usan little-endian. El loader no hace conversiones de extremo. | El código máquina y la memoria deben compartir una sola convención; una conversión “deliberada” entre formato y memoria es deuda y una fuente de errores. |
| `IFLOW-MAN-D16` | **Decode jerárquico tipo x86 y `MOV` único.** El primer byte y los bits de modo abren o cierran cajas; cada caja decide si sigue un byte tipo ModR/M/SIB, desplazamiento o inmediato, hasta la caja final del opcode. Se elimina `LI`: `MOV` cubre registro→registro, registro↔memoria e inmediato→registro; queda abierto si también inmediato→memoria. | Evita opcodes especiales y una tabla plana; permite crecer a 32 bits con modos y longitudes expresados por bits. La dirección está ratificada; el layout exacto queda en 8.8. |
| `IFLOW-MAN-D36R` | **Retirar el fuel como límite de ejecución.** La máquina es persistente: el tiempo, la CPU, el `sleep`/`wake` y la preempción pertenecen al scheduler/kernel, no a un contador de instrucciones por mensaje. El arranque BIOS → bootloader → kernel → scheduler no tiene un presupuesto finito por ejecución. La protección contra bucles queda abierta en 8.8 (quantum, timer IRQ, watchdog de host o combinación), pero no se implementa con el fuel actual. | Revisa `D36` del `PLAN`: el fuel fue útil mientras la VM era una ejecución acotada de un mensaje, pero no representa una máquina que duerme, despierta y sigue viva. |

### 5.2. Decisiones ratificadas: rate limit, routing y tiempo

No implementadas en esta pasada.

> **Corrección de `D40` (2026-10-20):** la política de rate limit no vive en el kernel ni en el
> BIOS: vive en el **userspace de la VM** (programa `.flow`). El hardware, el BIOS y el kernel solo
> aportan mecanismos: RTC/timer, contador de ciclos, `HALT`/sleep, scheduler y primitivas de cola.
> La versión anterior de `D40` decía “VM/kernel”; se corrige para no confundir mecanismo con política.
>
> Tampoco es correcto decir que “la VM no puede razonar sobre su propio tiempo ni dormir”: **sí puede**
> si el hardware expone RTC/timer y `HALT`. Lo que falta hoy es el mecanismo (RTC, timer IRQ, `HALT`
> real), no la capacidad conceptual. El token bucket debe vivir en memoria del guest; el reloj es un
> dispositivo de la VM que el guest lee, no una política del host.

| ID | Decisión | Motivo |
|---|---|---|
| `IFLOW-MAN-D40` | **La política de rate limit vive en el userspace `.flow`, no en el emulador/hardware, ni en el BIOS, ni en el kernel.** El token bucket, el backoff y la decisión esperar/encolar/descartar son software de usuario en la VM. El hardware/kernel solo proveen los mecanismos: RTC/timer, contador de ciclos, `HALT`/sleep, scheduler y primitivas de cola. Se elimina la duplicación `ROUTE_RATE_LIMIT`/`DECISION_RATE_LIMIT`: un solo contrato; el guest decide y el host no calcula el backoff. El host puede conservar un cap duro de seguridad, separado de la política. | `IFLOW-MAN-35`; la decisión pertenece al software, no al emulador; el hardware no debe elegir por la política. |
| `IFLOW-MAN-D41` | **Decisión de routing explícita y tipada.** El journal tiene una operación `ROUTE_DECISION` (o equivalente) con target, destino, backoff y razón; no se usa `SET_PROCESSED` como transporte ni se parsea `route:*`. La decisión la toma el guest; el host solo la registra. Si hay varias decisiones, se rechazan o se define una regla única explícita, no “la última gana”. | `IFLOW-MAN-36`; el contrato debe ser legible y verificable, no un detalle de string. |
| `IFLOW-MAN-D42` | **El hardware expone tiempo y ciclos; la política temporal es userspace.** La ISA expone un contador monótono de ciclos virtuales y/o un RTC/timer, con snapshot atómico y frecuencia conocida. El software de usuario usa esas lecturas para medir, calcular deadlines y decidir. El `sleep`/espera se hace con `HALT`/timer o una primitiva del kernel que cede la CPU, no con busy-wait. El ancho exacto (32/64 bits), la frecuencia y si el contador avanza durante la espera quedan abiertos en 8.8. | `IFLOW-MAN-37`; el hardware debe dar el mecanismo de tiempo, pero no decidir timeouts ni rate limits. |

### 5.3. Decisión ratificada: alcance del journal

| ID | Decisión | Motivo |
|---|---|---|
| `IFLOW-MAN-D43` | **El journal sobrevive al nuevo modelo, pero solo como write set del mensaje.** Sigue siendo el mecanismo para que las mutaciones del mensaje de una ejecución se apliquen todas o ninguna en el límite host/mensaje. No cubre el estado de la máquina, la RAM, el tiempo, el rate limit, la cola ni la entrega; esos efectos necesitan su propio modelo (estado del guest, scheduler o transacción de máquina). | Confirmado en la revisión: la atomicidad del mensaje sigue siendo útil aunque la máquina sea persistente; confundirla con atomicidad de máquina sería el error. |

## 6. Matemática de la cola: correcciones y límites

> **Nota (2026-10-20):** estas cuentas describen el VM actual de 16 bits y 64 KB. La dirección
> ratificada es 32 bits de memoria física y virtual, hasta 4 GiB. En ese modelo la cola se limita por
> el cap de RAM por VM y las cifras de 12.288 dejan de ser el techo. Ver sección 8.

- RAM de usuario actual: **28 KB** (7 páginas de 4 KB). Si la pantalla está activa, ocupa 4 KB; quedan
  **24 KB** utilizables.
- Array de índices de 16 bits: `24.576 / 2 = 12.288` entradas. El índice puede direccionar hasta
  **65.536** handles, pero solo caben 12.288 vivos en la cola.
- UUID binario de 16 bytes: `24.576 / 16 = 1.536`; UUID como texto hexadecimal de 32 bytes:
  `24.576 / 32 = 768` (tu cifra de 750 es correcta para 24.000 bytes). Con metadatos, baja.
- Entrada con `índice(2) + deadline(4) + estado(1) + flags(1) = 8 bytes`: **3.072** entradas.
- **No derives un UUID de dos bytes.** Un índice de 16 bits es un handle, no un identificador único.
  Si el host reutiliza slots, añade una generación/epoch para evitar ABA (un handle viejo que apunta
  a un mensaje nuevo).
- **`longitud / nulos` no da el número de elementos.** Da el promedio de bytes por elemento. El número
  de elementos es `nulos` (con terminador final) o `nulos + 1` (sin terminador). Si quieres acceso
  O(1), añade una tabla de offsets; si no, escanear es aceptable para listas pequeñas.
- **Destinatarios:** el buffer de entrada es de 4 KB. Con nombres de ~16 bytes + NUL, caben ~240, no
  12.000. Los 12.000 son handles de cola, no destinatarios.
- **32 bits no es gratis:** duplica punteros y slots de pila; una cola de índices de 32 bits reduce
  la densidad. Pero la dirección ratificada es **32 bits de memoria física y virtual**, no solo de
  direccionamiento. Los handles pueden seguir siendo de 16 bits con generación si el ABI lo permite,
  pero el espacio de direcciones, los frames y la page table son de 32 bits. Toca ISA, ABI, parser,
  MMU y tests.

## 7. Ruta de auditoría pendiente

| Orden | Componente | Qué falta verificar |
|---|---|---|
| 1 | `SoftMMU` | paginación, permisos R/W/X, copy-on-write, ownership, solapamientos |
| 2 | `VmBus` + MMIO | orden de decode, privilegios, DMA, apertura de dispositivos |
| 3 | `VmInterpreter` completo | fetch/decode/execute, flags, pila, CALL/RET, fuel, instrucciones de bloque |
| 4 | `ProgramImageParser` | magic, versiones, secciones, bounds, SHA-256, ABI/ISA |
| 5 | `HostServiceRegistry` completo | todos los servicios, DMA, capabilities, property, errores |
| 6 | `VmPolicyEngine` completo | carga, arranque, contexto, `machineLock`, errores, pool |
| 7 | `IrqTable` / `VmPic` / `VmNvram` / `VmDisplay` | layout, máscaras, prioridad, MMIO, persistencia |
| 8 | Tests | 397 tests declarados; cobertura real y casos de canal/IRQ/HALT |
| 9 | Compilador `.flowasm` | ABI, firmware, IRQ, determinismo, ejemplos |
| 10 | Integración host/TextFormatter | transformación vs canal, entrega, cancelación de evento |

**Estado al 2026-10-20:** los bloques 1–7 están leídos; el 8 (tests) no se ejecutó; el 9 (compilador)
se revisó en lectura, sin ejecutar sus tests; el 10 sigue pendiente. Los hallazgos de esta pasada están
en `3.1`–`3.6`, separados de los `IFLOW-MAN-01…15` de la primera revisión. La `§3.1.x` y los
hallazgos `46…54` son hallazgos arquitecturales transversales confirmados en esta pasada; la `§3.7`
y los hallazgos `42…45` quedan como material diferido para después de reimplementar la VM.

## 8. Arquitectura objetivo ratificada: ROM, BIOS, kernel y 32 bits

Nada de esta sección está implementado. El VM actual sigue siendo de 16 bits y 64 KB. Esta sección
registra la dirección ratificada durante la revisión manual del 2026-10-20 para la próxima generación
de iFlow. Separa las decisiones de máquina de las decisiones de contrato de políticas
(`IFLOW-MAN-D01…D16`, `D36R` y `D40…D43`; `D14…D16`, `D36R` y `D40…D43` ya están ratificadas).

### 8.1. Correcciones ratificadas

| Tema | Decisión |
|---|---|
| `.flow` | Es la **ROM completa**, no un programa ejecutable. |
| Fallback | Es **fallback neutral**, no `pass-through`: `FallbackProgram`, mensaje “using fallback”. Puede permitir, denegar o transformar; el runtime no asume su semántica. |
| Endianness | **Little-endian único** en ROM, header, segmentos, constantes, instrucciones y memoria. Sin conversiones en el loader. |
| ISA / decode | **Decode jerárquico tipo x86** por cajas de bits; `MOV` único para reg-reg, reg-mem e inmediato. Se elimina `LI` como opcode. |
| Ejecución persistente / fuel | La máquina es **persistente**; el fuel deja de ser el límite. El scheduler/kernel gestiona tiempo, `sleep`/`wake` y CPU; el arranque no tiene presupuesto por mensaje. El reemplazo del fuel queda abierto en 8.8. |
| Política vs mecanismo | El rate limit, el routing, los timeouts y las decisiones de espera son **política de userspace `.flow`**. El hardware, el BIOS y el kernel solo exponen mecanismos: RTC/timer, ciclos, `HALT`/sleep, scheduler y colas. |
| Rate limit | El token bucket y la decisión esperar/encolar/descartar viven en el **userspace de la VM**; el host no calcula backoff ni mantiene la política. Se elimina la duplicación `ROUTE_RATE_LIMIT`/`DECISION_RATE_LIMIT`: un solo contrato. El host puede conservar un cap duro de seguridad. |
| Routing | El journal usa una decisión **explícita y tipada**; el guest decide y el host solo registra. No se sobrecarga `SET_PROCESSED` ni se parsea `route:*`. |
| Tiempo | La ISA expone **RTC/timer y contador de ciclos virtuales**; el software de usuario calcula deadlines. El `sleep` es `HALT`/timer o cesión al scheduler, no busy-wait. |
| Journal | Sobrevive como **write set del mensaje**: todo o nada para las mutaciones del mensaje de una ejecución. No cubre estado de máquina, RAM, tiempo, rate limit, cola ni entrega. |
| Reset vector | Entra al **firmware/BIOS**. No entra al bootloader. |
| BIOS | Hace POST e inicializa memoria, page tables, stack e interrupciones. Publica las tablas que necesita el resto del sistema. |
| Bootloader | **No inicializa hardware**: no hace POST, no configura page tables, no cambia el stack y no enciende interrupciones. Solo carga el kernel de ROM a RAM y salta. |
| Entry point | Solo existe **un entry point arquitectónico**: el reset vector del BIOS. |
| Entry points de programas | Los administra el kernel al cargar cada programa. No son entry points de arranque de la ROM. |
| Memoria | **32 bits de memoria física y virtual**, no solo 32 bits de direccionamiento. |
| Máximo | 4 GiB de espacio físico. ROM, RAM y MMIO comparten ese espacio; el máximo de RAM útil es menor que 4 GiB. |
| Asignación | Física dispersa: frames de 4 KB asignados bajo demanda. No se reservan 4 GiB por VM. |
| Compresión | Se estudiará compresión de memoria en el emulador, a nivel de frame. Es una propuesta abierta, no una decisión cerrada. |
| Tipo de dirección | `long` con máscara o un tipo propio (`Address32`/bitset) si `long` complica el modelo. |
| Almacenamiento físico | `byte[][]` de frames de 4096 bytes; no un `byte[]` de 4 GiB. |
| Objetivo | VM de networking empresarial con kernel real, no un plugin de políticas. La competitividad es un objetivo de diseño; esta auditoría no aporta benchmarks. |

Roles separados:

- **Firmware/BIOS:** reset vector, POST, memoria, page tables, stack, interrupciones, enumeración de
  hardware y tablas del sistema.
- **Bootloader:** cargar y verificar el kernel; no inicializar hardware.
- **Kernel:** scheduler, cola, tiempo, syscalls, aislamiento, carga de programas.
- **Programas:** lógica de políticas; entry point de proceso administrado por el kernel.

### 8.2. `.flow` como ROM

El formato v2 actual describe un ejecutable: `entryPoint`, `codeLength`, `constantsLength` y una
sección de código. Para BIOS + kernel + programas eso es insuficiente. El contenedor debe describir
la ROM entera.

Header propuesto:

```text
magic           "FLOWROM"
formatVersion   3
isaVersion      32
romSize
resetVector
tocOffset
tocCount
flags
hash            cubre toda la ROM
signature       opcional
```

Tabla de contenidos propuesta:

```text
type            BIOS | BOOT | KERNEL | PROGRAM | DATA | FONT | CONFIG
offset
size
loadAddress
flags           R / W / X / privilegios
hash            por segmento
```

Puntos importantes:

- El header de la ROM tiene **un solo** `resetVector`. La TOC no define puntos de entrada de arranque.
- Cada segmento ejecutable (`BOOT`, `KERNEL`, `PROGRAM`) puede llevar su entry interno en su propio
  header de segmento. Lo consumen el BIOS, el bootloader o el kernel; no la CPU al reset.
- Un programa de usuario no tiene entry point de ROM: tiene entry point de proceso, y el kernel decide
  cuándo y cómo saltar a él.
- El host solo carga la ROM y la mapea. No cablea programas ni conoce su lógica.
- La ROM se mapea R/X; la RAM es R/W; el MMIO se decodifica como dispositivo.
- El hash cubre toda la imagen y cada segmento puede tener su propio hash.
- `ProgramImage` deja de ser el contenedor principal y pasa a ser un segmento. Hacen falta
  `RomImage`/`RomParser` y un formato v3. Los `.flow` v2 se rechazan o se migran explícitamente.

### 8.3. Secuencia de arranque

1. El host carga el `.flow` como ROM y lo mapea en el espacio físico.
2. La CPU arranca en `resetVector`.
3. El reset vector entra al **firmware/BIOS**, nunca al bootloader.
4. El BIOS hace POST: memoria, page tables, stack, controlador de interrupciones, timer, display,
   NVRAM y tablas del sistema.
5. El BIOS localiza el segmento `BOOT` y salta al bootloader.
6. El bootloader carga el segmento `KERNEL` de ROM a RAM, verifica su hash y salta.
7. El kernel inicializa scheduler, cola, syscalls y carga los segmentos `PROGRAM` cuando corresponda.
8. Cada programa corre en un contexto de proceso con su entry point administrado por el kernel.

El bootloader no repite la inicialización del BIOS. Si el BIOS ya dejó page tables y stack listos, el
bootloader los usa; no los reconstruye.

**Estado actual, para no confundirlo con la arquitectura objetivo:** el compilador ya antepone una ROM
de 4 KB con un `fw_reset` que es el entry point y hace un POST parcial (limpia datos, configura display
y publica la tabla de IRQ). Pero el formato v2 no tiene TOC ni segmentos, el host sigue inicializando
page tables y stack, y el firmware no inicializa la pila ni el PIC. Es un híbrido firmware+programa;
no es todavía una ROM con BIOS, bootloader y kernel separados.

### 8.4. Modelo de memoria de 32 bits

| Concepto | Decisión |
|---|---|
| Espacio virtual | 32 bits, hasta 4 GiB |
| Espacio físico | 32 bits, hasta 4 GiB; ROM/RAM/MMIO comparten el rango |
| Página | 4 KB |
| Frames | 1.048.576 máximos (`4 GiB / 4 KB`) |
| Page table | Dos niveles, 10 + 10 + 12 bits; PDE y PTE de 4 bytes |
| Backing store | `byte[][] frames = new byte[1 << 20][]`; frames de 4096 bytes bajo demanda |
| Direcciones | `long` con máscara `0xFFFF_FFFFL`, o `Address32` si se quiere un tipo propio |
| Límite práctico | Cap por VM configurable (por ejemplo 64 MB, 256 MB, 1 GB); 4 GiB es el máximo arquitectónico |
| Traducción | TLB/caché de página; no hacer page walk byte a byte |
| COW/snapshots | Por frame; nunca clonar 4 GiB completos |

Detalles de implementación:

- Java no permite un `byte[]` de 4 GiB. El array de frames es la solución; el índice de frame y el
  offset caben en `int`, pero la aritmética de direcciones debe usar `long` o un tipo sin signo.
- La page table completa de 4 GiB ocuparía unos 4 MB de PTEs más el directorio. Debe ser dispersa:
  solo se asignan las tablas de las regiones mapeadas.
- El array de 1.048.576 referencias ya ocupa varios MB en la JVM. Si eso es demasiado, se puede
  usar un array de arrays por niveles o una estructura sparse; el contrato visible sigue siendo
  “frames de 4 KB bajo demanda”.
- `PhysicalMemory.freeze()` actual clona la memoria plana. En 32 bits eso es inviable; el snapshot
  debe ser por frame y acotado.
- `getMemorySnapshot()` no puede devolver 4 GiB. Debe ser una operación acotada o por regiones.
- El MMIO se decodifica antes de la MMU, como hoy. Los registros de dispositivo también pasan a
  direcciones de 32 bits.
- La ISA debe ser de 32 bits: registros, opcodes, modos de direccionamiento, assembler, compilador,
  ABI y tests. Un ISA de 16 bits no puede direccionar 4 GiB de forma plana.

### 8.5. Compresión de memoria (propuesta abierta)

El emulador podría comprimir frames fríos para aumentar la capacidad efectiva sin reservar 4 GiB.
Diseño posible:

- Compresión **a nivel de frame** (4 KB), transparente al guest.
- Zero-page deduplicación: todos los frames cero comparten una página inmutable.
- COW sobre frames comprimidos: al escribir, se descomprime y se copia.
- Frames calientes sin comprimir; frames fríos comprimidos (LZ4, zstd o RLE simple).
- El guest sigue viendo 4 GiB; la compresión no cambia page tables ni permisos.
- Límite de descompresión por frame para evitar bombas y picos de latencia.
- La compresión no sustituye la asignación dispersa; es una capa adicional.

Costes: CPU, latencia de acceso, complejidad de TLB/COW y necesidad de métricas. Debe decidirse
después de medir cargas reales, no antes.

### 8.6. Capacidad de cola con 32 bits

La cola ya no está limitada por los 12.288 handles del VM de 64 KB. Está limitada por el cap de RAM
por VM y por el tamaño de cada entrada.

| Bytes por entrada | 64 MB | 256 MB | 1 GB |
|---:|---:|---:|---:|
| 8 | 8.388.608 | 33.554.432 | 134.217.728 |
| 12 | 5.592.405 | 22.369.621 | 89.478.485 |
| 16 | 4.194.304 | 16.777.216 | 67.108.864 |

- 12.000 mensajes dejan de ser un problema incluso con 64 MB.
- Con 4 GiB teóricos: ~536 millones de entradas de 8 bytes o ~268 millones de 16 bytes, pero no se
  asignan de golpe.
- Los handles pueden ser de 16 bits con generación o de 32 bits; el ancho se decide con el ABI.
- La cola vive en el kernel. El host conserva los objetos `Message` y ejecuta `DELIVER(handle)`.
- El estado de la entrada puede ser implícito (`cli` → pop → `DELIVER` → `sti`); solo hace falta un
  campo explícito si hay entrega asíncrona, reintentos, cancelación o reentrancia.

### 8.7. Impacto en el código actual

| Componente | Impacto |
|---|---|
| ISA / CPU | Registros y direcciones de 32 bits; opcodes, decoder, intérprete, flags, pila, CALL/RET, fuel. |
| `SoftMMU` / `PageTable` / `PageEntry` | Traducción de 32 bits, page table de dos niveles, TLB, permisos, ownership. |
| `PhysicalMemory` | Frames de 4 KB bajo demanda, snapshots por frame, COW, compresión opcional. |
| `VmMemoryMap` | Mapa de 32 bits: ROM, RAM, MMIO, stack, kernel, programas. |
| `.flow` / parser | Formato ROM v3 con header, TOC, segmentos y hash; `RomImage`/`RomParser`. |
| Arranque | BIOS, bootloader y kernel separados; reset vector al BIOS; el BIOS, no el host, inicializa page tables, stack y PIC. |
| ABI `HOST` | Direcciones y tamaños de 32 bits; longitudes sin signo validadas en un único punto; DMA, capabilities y servicios. |
| IRQ | Timer IRQ, PIC, `HLT` real, máscara, scheduler; commit de IRQ atómico y pila de líneas en servicio. |
| Bus / MMIO | Transacciones de palabra y de 32 bits divididas por frontera de dispositivo; el camino rápido no puede saltarse el decode. |
| DMA / privilegios | Masters separados: el guest usa `PROGRAM` para `COPY`/`MOVE`; solo el host/bootloader usa `SUPERVISOR`. |
| Verificador ISA | Mapa de fronteras de instrucción; entry point, CALL/RET, handler de IRQ y saltos deben caer en una frontera. |
| Memoria física | Validar frame y rango antes de traducir; `VmException`, nunca `AIOOBE`; frames dispersos acotados. |
| Input ABI | Campos con longitud/escape o TLV; UTF-8 sin cortar caracteres; ausencia distinta de la cadena `"null"`. |
| Tests / compilador | Recompilar fixtures, assembler y compiler; tests de 32 bits, ROM, arranque, memoria, IRQ y fronteras. |
| Documentación | README, `src/iflow/README.md`, wiki histórica y esta auditoría. |

Esto no es un parche de `SoftMMU`: es una VM nueva o un modo 32 bits separado. El VM de 16 bits y el
de 32 bits no deberían compartir el mismo intérprete sin una separación clara de ISA y formato.

### 8.8. Decisiones abiertas

1. ¿El VM de 16 bits se reemplaza por el de 32 bits o conviven como targets distintos?
2. ¿Dónde se mapea la ROM y cuál es la dirección exacta del reset vector?
3. ¿Cuál es el cap de RAM por VM por defecto: 64 MB, 256 MB, 1 GB?
4. ¿Páginas de 4 KB, 4 MB o mixtas para el kernel?
5. ¿La compresión de memoria entra en la primera versión o queda para una fase posterior?
6. ¿Handles de 16 bits con generación o de 32 bits?
7. ¿El timer es un dispositivo del host, un contador virtual de ciclos o ambos?
8. ¿Cómo carga el kernel los programas: TOC directa, filesystem mínimo o imagen en RAM?
9. ¿Qué capacidades/privilegios tiene un programa de usuario?
10. ¿Cómo se migran los `.flow` v2 y el compilador `.flowasm`?
11. ¿La ROM se mapea como memoria R/X o se expone como dispositivo de bloque?
12. ¿Qué benchmarks definen “competitivo en networking empresarial”?
13. ¿Cuál es el layout exacto del decode jerárquico: qué bits abren cada caja y cuántos niveles hay?
14. ¿`MOV` admite inmediato→memoria o solo inmediato→registro y registro↔memoria?
15. ¿`LI` desaparece por completo o queda solo como alias de ensamblador que se traduce a `MOV`?
16. ¿La migración a little-endian único rompe `.flow` v2 o se hace en el formato v3?
17. ¿Cuántos bits ocupan registro, modo, desplazamiento e inmediato en la ISA de 32 bits?
18. ¿Qué sustituye al fuel: quantum de scheduler, timer IRQ, watchdog de host, cuotas por actor o una combinación?
19. ¿Cómo se evita un bucle infinito en una máquina persistente sin fuel, sin bloquear el scheduler?
20. ¿Cómo se contabiliza CPU y se implementa `sleep`/`wake` en la máquina persistente?
21. ¿El fuel desaparece del ABI o queda solo como métrica de diagnóstico?
22. ¿El contador de ciclos es de 32 o 64 bits y cómo se expone en registros de 32 bits sin tearing?
23. ¿La frecuencia es fija en Hz o se expone como numerador/denominador? ¿Cómo se maneja el overflow?
24. ¿El contador avanza durante `sleep`/espera, solo cuando la VM ejecuta, o ambos (ciclos de CPU vs tiempo virtual)?
25. ¿La instrucción de tiempo puede usarse para busy-wait o el kernel debe forzar `sleep`/yield?
26. ¿Las lecturas de propiedades dentro de la misma ejecución deben ver el journal (*read-your-writes*) o el mensaje original? Si se mantiene lo segundo, debe documentarse y no sorprender a las políticas.
27. ¿Qué mecanismo de tiempo expone el hardware: RTC MMIO, timer IRQ, `RDCYCLE`, `RDTIME`, o varios? ¿Cómo se combinan?
28. ¿`HALT` acepta un deadline o el kernel expone `sleep(ticks)`? ¿Cómo despierta, cómo se reanuda y qué pasa si llega una IRQ antes?
29. ¿Dónde vive el token bucket: RAM de usuario, NVRAM o estado del kernel? ¿Cómo sobrevive a `sleep` y a un fallo de ejecución?
30. ¿Se eliminan `ROUTE_RATE_LIMIT` y `DECISION_RATE_LIMIT`, o queda uno como primitiva? ¿El guest pasa el backoff ya calculado?
31. ¿Cómo se separa el cap duro del host de la política del guest para que no se confundan ni se dupliquen?
32. ¿El timeout del dispatcher debe cancelar la ejecución y liberar `machineLock`, o el scheduler/kernel debe matar el quantum por otra vía?
33. ¿La expansión de `PERMISSION`/`WORLD` vacíos debe ser fail-open, fail-closed o un error de configuración?
34. ¿Una entrega diferida por `sleep` cuenta como entregada antes de ejecutarse? ¿Cómo se compensa si el scheduler se cierra o la entrega falla?
35. **(Diferida — no forma parte de la auditoría vigente)** ¿Cuál es la precedencia de idioma: `Message.langTarget`, `Channel.langTarget`, `Actor.language` o `config.defaultLanguage`? ¿Y la de `langSource` frente a la detección automática?
36. **(Diferida — no forma parte de la auditoría vigente)** ¿`Formats` debe renderizar N mensajes/líneas o el contrato real es un solo mensaje? Si es N, ¿cómo se combinan los N textos con `%content%` y los placeholders?
37. **(Diferida — no forma parte de la auditoría vigente)** ¿`Direction.channel` debe eliminarse o el dispatcher debe honrarlo? Si se honra, ¿qué precede: `Direction.channel` o `Message.channel`?
38. **(Diferida — no forma parte de la auditoría vigente)** ¿El redirect a consola o a un actor debe renderizarse con el contexto del destino y no con el del recipient? ¿Cómo se representa el actor consola para placeholders e idioma?

> Las preguntas 35–38 se retoman después de reimplementar la VM; no bloquean el cierre de esta pasada.

### 8.9. ISA de 32 bits: decode jerárquico y `MOV`

La dirección ratificada es un decoder **jerárquico**, inspirado en x86, no una tabla plana de opcodes.
La idea se puede describir así:

```text
byte 0
  ├─ bits de grupo: ¿qué familia de operación es?
  ├─ bits de modo: ¿reg-reg, reg-mem, mem-reg, inmediato?
  └─ bits de tamaño/dirección: ¿hay desplazamiento? ¿hay inmediato?
        └─ caja siguiente (ModR/M, SIB, displacement, immediate)
              └─ caja final: opcode concreto
```

Cada conjunto de bits **abre o cierra** la presencia de la caja siguiente. No se lee una instrucción
de tamaño fijo mirando solo el primer byte: se recorre un árbol hasta la hoja. Eso permite que el
mismo `MOV` tenga varias formas y que la ISA crezca sin reservar un opcode distinto para cada una.

Reglas de diseño:

- **`MOV` único.** Cubre como mínimo:
  - `MOV Rd, Rs` (registro a registro),
  - `MOV Rd, [Ra + disp]` (memoria a registro),
  - `MOV [Ra + disp], Rs` (registro a memoria),
  - `MOV Rd, imm` (inmediato a registro).
  Queda abierto si también existe `MOV [Ra + disp], imm`.
- **`LI` desaparece del ISA.** Si se conserva compatibilidad de ensamblador, `LI` será un alias que
  emite un `MOV` inmediato, no un opcode propio.
- **Little-endian en toda la cadena.** Los campos multibyte de la instrucción, el inmediato, el
  desplazamiento, las constantes y el header de la ROM se leen de menor a mayor. No hay conversión
  entre el formato y la memoria.
- **Longitud validable.** El decoder debe saber cuántos bytes consume cada caja antes de ejecutar y
  rechazar una caja reservada o una combinación ilegal. La frontera de instrucción se calcula con el
  mismo decoder que ejecuta, para que no haya dos verdades.
- **32 bits.** Registros, direcciones y desplazamientos son de 32 bits. El layout exacto de los bits
  queda para la decisión abierta 13–17; no se inventa aquí.

Lo que **no** cambia: la separación entre transformar contenido y cambiar canal, el journal, la
validación de la decisión y el modelo de memoria de la sección 8.4.

### 8.10. Tiempo, ciclos, RTC y `HALT`

La máquina persistente necesita **mecanismos** de tiempo; la **política** temporal pertenece al
userspace `.flow`. El hardware/emulador debe exponer, como mínimo:

- un **RTC/timer** (MMIO o instrucción) con frecuencia conocida;
- un **contador monótono de ciclos virtuales**, provisionalmente `RDCYCLE`;
- una **IRQ de timer** y un `HALT` que pueda despertar por esa IRQ (no el `HALT` terminal actual);
- una forma de **ceder la CPU** y reanudar: `HALT` con deadline o una primitiva de `sleep` del kernel.

Con esos mecanismos, el software de usuario puede medir intervalos, calcular deadlines, implementar
timeouts y decidir esperar, encolar o descartar. El hardware no decide nada de eso.

Requisitos:

- **Precisión.** Las lecturas deben permitir calcular intervalos y deadlines sin depender del reloj de
  pared del host. La frecuencia debe ser conocida y estable (Hz o un par numerador/denominador).
- **Snapshot atómico.** En una ISA de 32 bits, un contador de 64 bits no puede leerse en dos
  instrucciones sin riesgo de *tearing* entre el low y el high. `RDCYCLE` debe entregar un snapshot
  coherente, por ejemplo con dos registros en una sola instrucción o con un mecanismo de lectura
  atómica.
- **Contador virtual, no `TSC` del host.** Debe ser monótono por VM, con offset/escala propios. No debe
  exponer el `TSC` físico ni permitir inferir actividad de otras VM.
- **CPU vs tiempo.** Hay que decidir si el contador avanza solo cuando la VM ejecuta, también durante
  `sleep`/espera, o si se exponen dos fuentes: ciclos de CPU y tiempo virtual. El scheduler necesita
  deadlines; el rate limit necesita una base temporal; no son necesariamente el mismo contador.
- **`sleep` no es busy-wait.** La instrucción de tiempo sirve para medir y calcular, no para girar en
  un bucle hasta que pase un deadline. Dormir es ceder la CPU al scheduler/kernel y despertar por timer
  o IRQ. Un busy-wait basado en `RDCYCLE` consumiría CPU real y volvería a introducir el problema que
  el fuel intentaba limitar.
- **Overflow y wrap.** El contrato debe definir qué pasa al desbordar, cómo se comparan dos lecturas
  separadas por un wrap y si el contador puede resetearse al arrancar una VM.
- **Rate limit en userspace.** El token bucket de `IFLOW-MAN-D40` es software de usuario; usa estas
  lecturas y `HALT`/sleep como mecanismos, pero el host no calcula el backoff.

El layout exacto (nombre, registros, ancho, frecuencia, semántica durante la espera y relación entre
RTC y contador de ciclos) queda en las decisiones abiertas 22–25 y 27–31.

### 8.11. Rate limit: mecanismo vs política

El defecto actual no es solo que el rate limit esté en el host: hay **dos caminos** para la misma
decisión (`ROUTE_RATE_LIMIT` y `DECISION_RATE_LIMIT`) y ambos calculan el backoff en el emulador. La
separación objetivo es:

| Capa | Responsabilidad |
|---|---|
| Hardware/emulador | CPU, MMU, PIC, RTC/timer, contador de ciclos, MMIO, `HALT`/timer. **No** mantiene token buckets, no calcula backoff y no decide esperar/encolar/descartar. |
| BIOS | POST, inicialización de RTC/timer/PIC y publicación de tablas. **No** implementa política de rate limit. |
| Kernel | Scheduler, `sleep`/`wake`, timers, cola y primitivas de contexto. **No** decide la política por canal/actor. |
| Userspace `.flow` | Token bucket, lectura de tiempo, cálculo de backoff y decisión esperar/encolar/descartar. Es la política. |
| Límite host | Entrega, journal del mensaje y, si hace falta, un **cap duro de seguridad** separado. No es la política. |

Consecuencias:

- Se elimina la duplicación: **un solo contrato** de rate limit. Si el guest decide, el host no debe
  volver a calcular el mismo backoff por otro servicio.
- El guest debe poder leer el tiempo (RTC/ciclos) y dormir (`HALT`/timer o `sleep`) para implementar
  la política; el kernel solo aporta el mecanismo.
- El `PolicyTarget.RATE_LIMIT` actual, que solo silencia y no encola, no alcanza para una política
  real; la cola/reintento pertenece al modelo de userspace/kernel, no al journal del mensaje.
- El cap duro del host, si existe, debe ser explícito y no confundirse con la decisión del `.flow`.

## 9. Criterio de cierre de esta auditoría

- No se modifica código hasta ratificar `IFLOW-MAN-D01…D13` y las decisiones abiertas de la sección
  8.8. `D14…D16`, `D36R` y `D40…D43` ya están ratificadas, pero tampoco se implementan en esta pasada.
- Cada hallazgo debe tener evidencia en código y, cuando se remedie, un test de regresión.
- Este documento es el registro definitivo de los hallazgos `IFLOW-MAN-*` y de la arquitectura
  objetivo; al cerrar la pasada se actualizará el estado de cada punto (confirmado, descartado,
  ratificado o remediado). Los hallazgos `16…54` están confirmados por lectura/sonda, pero su
  remediación sigue pendiente de ratificación. Los hallazgos `46…54` (arquitectura de capas,
  estado persistente, concurrencia) son parte de la auditoría vigente; la `§3.7` y los hallazgos
  `42…45` son material diferido: no se consideran parte del cierre de esta pasada y se retoman
  después de reimplementar la VM con las nuevas specs.
- No se publica nada sin autorización explícita. Los commits locales de esta auditoría siguen sin
  publicar; `git log origin/main..HEAD` es la lista vigente.
