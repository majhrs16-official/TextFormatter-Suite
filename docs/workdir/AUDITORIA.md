# AUDITORÍA MANUAL DE iFLOW — 2026-10-20

**Estado:** en curso, solo lectura.
**Commit base:** `476c942` (local, no publicado).
**Base de la discusión de arquitectura:** `4857997` (local, no publicado).
**Modo:** lectura estática; no se ejecutó build ni tests en esta pasada.
**Nota de reemplazo:** este documento sustituye la auditoría integral anterior. El contenido previo
queda en el historial de git; la historia no se reescribe.

---

> **Alcance de esta pasada:** contratos de entrada, enlace host↔iFlow, `PolicyTarget`, carga de
> `.flow`, camino de transformación/canal, `HALT`/IRQ, MMU base y primeras cuentas de la cola de
> rate limit. Quedan pendientes el bus/MMIO, el parser completo, el intérprete completo, todos los
> servicios `HOST`, el arranque/BIOS, los tests y el compilador. La sección 8 registra la dirección
> de arquitectura ratificada durante la revisión; no está implementada.

## 1. Mapa de lectura

| Bloque | Archivos leídos | Qué se verificó | Estado |
|---|---|---|---|
| Contratos de entrada | `Router`, `RouteDecision`, `RouteOutcome`, `PolicyTarget`, `PassThroughProgram`, `VmConfigLoader` | contrato, semántica de targets, fallback, carga inicial | ✅ leído |
| Enlace host↔iFlow | `DefaultTextFormatter`, `SuiteHost.deliver`, `MessageDispatcher`, `PolicyResultValidator`, `HostServiceRegistry` (routing/DMA/property) | cómo se transforma, rutea, formatea y entrega; `SET_CHANNEL` vs `CHANNEL_REDIRECT` | ✅ leído |
| Ejecución / IRQ | `VmInterpreter` (HALT e IRQ), `ExecutionContext` (cancelación/`irqHandler`), `VmPic`, `IrqTable`, `VmMemoryMap` | despacho de IRQ, estado de CPU, mapa de memoria, límites de 16 bits | 🟡 parcial |
| MMU / memoria | `SoftMMU`, `PageTable`, `PhysicalMemory`, `VmMemoryMap` | 64 KB, 16 páginas de 4 KB, copy-on-write, permisos, ownership; límites para 32 bits | 🟡 parcial |
| Cola / rate limit | `RateLimiter`, `VmPolicyEngine` (rate limit de emisión), `MessageDispatcher` (rate limit por target) | dónde se aplica el límite, si hay cola o reintento | ✅ leído |
| Pendiente | `VmBus`, `PageEntry`, `ProgramImageParser` completo, `VmInterpreter` completo, `HostServiceRegistry` completo, `VmPolicyEngine` completo, `VmDisplay`/`VmNvram`, tests, compilador `.flowasm` | MMIO, ISA, ABI, arranque, cobertura | ⏳ pendiente |

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
| `IFLOW-MAN-05` | Alta | `CHANNEL_REDIRECT` reconstruye el mensaje desde `messageWithResolvedSource`, no desde `result.message()`: pierde transformaciones previas. Además llama otra vez a `host.deliver()` sin límite de profundidad ni canales visitados; una política A→B, B→A recursa. | `MessageDispatcher.java:158-176` |
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

## 4. Cosas que están bien y no hay que romper

- `DefaultTextFormatter` resuelve el canal **después** de aplicar el journal; un `SET_CHANNEL` sí cambia el tail de formato. El formateador no es el problema.
- El despacho de IRQ mientras la VM está corriendo sí usa la tabla, guarda estado y hace un CALL de hardware. El problema es `HALT`, no el mecanismo de despacho en ejecución.
- `PolicyResultValidator` valida que el canal de `CHANNEL_REDIRECT` exista antes de aceptarlo.
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

## 8. Arquitectura objetivo ratificada: ROM, BIOS, kernel y 32 bits

Nada de esta sección está implementado. El VM actual sigue siendo de 16 bits y 64 KB. Esta sección
registra la dirección ratificada durante la revisión manual del 2026-10-20 para la próxima generación
de iFlow. Separa las decisiones de máquina de las decisiones de contrato de políticas
(`IFLOW-MAN-D01…D13`).

### 8.1. Correcciones ratificadas

| Tema | Decisión |
|---|---|
| `.flow` | Es la **ROM completa**, no un programa ejecutable. |
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
| Arranque | BIOS, bootloader y kernel separados; reset vector al BIOS. |
| ABI `HOST` | Direcciones y tamaños de 32 bits; DMA, capabilities y servicios. |
| IRQ | Timer IRQ, PIC, `HLT` real, máscara, scheduler. |
| Tests / compilador | Recompilar fixtures, assembler y compiler; tests de 32 bits, ROM, arranque y memoria. |
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

## 9. Criterio de cierre de esta auditoría

- No se modifica código hasta ratificar `IFLOW-MAN-D01…D13` y cerrar las decisiones abiertas de la
  sección 8.8.
- Cada hallazgo debe tener evidencia en código y, cuando se remedie, un test de regresión.
- Este documento es el registro definitivo de los hallazgos `IFLOW-MAN-*` y de la arquitectura
  objetivo; al cerrar la pasada se actualizará el estado de cada punto (confirmado, descartado,
  ratificado o remediado).
- No se publica nada sin autorización explícita. `476c942` y `4857997` siguen siendo commits locales.
