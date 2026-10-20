# AUDITORÍA MANUAL DE iFLOW — 2026-10-20

**Estado:** en curso, solo lectura.
**Commit base:** `476c942` (local, no publicado).
**Modo:** lectura estática; no se ejecutó build ni tests en esta pasada.
**Nota de reemplazo:** este documento sustituye la auditoría integral anterior. El contenido previo
queda en el historial de git; la historia no se reescribe.

---

> **Alcance de esta pasada:** contratos de entrada, enlace host↔iFlow, `PolicyTarget`, carga de
> `.flow`, camino de transformación/canal, `HALT`/IRQ y primeras cuentas de la cola de rate limit.
> Quedan pendientes la MMU, el bus, el parser completo, el intérprete completo, todos los servicios
> `HOST`, el arranque/BIOS y los tests.

## 1. Mapa de lectura

| Bloque | Archivos leídos | Qué se verificó | Estado |
|---|---|---|---|
| Contratos de entrada | `Router`, `RouteDecision`, `RouteOutcome`, `PolicyTarget`, `PassThroughProgram`, `VmConfigLoader` | contrato, semántica de targets, fallback, carga inicial | ✅ leído |
| Enlace host↔iFlow | `DefaultTextFormatter`, `SuiteHost.deliver`, `MessageDispatcher`, `PolicyResultValidator`, `HostServiceRegistry` (routing/DMA/property) | cómo se transforma, rutea, formatea y entrega; `SET_CHANNEL` vs `CHANNEL_REDIRECT` | ✅ leído |
| Ejecución / IRQ | `VmInterpreter` (HALT e IRQ), `ExecutionContext` (cancelación/`irqHandler`), `VmPic`, `IrqTable`, `VmMemoryMap` | despacho de IRQ, estado de CPU, mapa de memoria, límites de 16 bits | 🟡 parcial |
| Cola / rate limit | `RateLimiter`, `VmPolicyEngine` (rate limit de emisión), `MessageDispatcher` (rate limit por target) | dónde se aplica el límite, si hay cola o reintento | ✅ leído |
| Pendiente | `SoftMMU`, `VmBus`, `ProgramImageParser` completo, `VmInterpreter` completo, `HostServiceRegistry` completo, `VmPolicyEngine` completo, `VmDisplay`/`VmNvram`, tests, compilador `.flowasm` | paginación, permisos, MMIO, ISA, ABI, arranque, cobertura | ⏳ pendiente |

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
| `IFLOW-MAN-D09` | **Separar bootloader, firmware/BIOS y kernel.** El bootloader carga el kernel de ROM a RAM; el kernel gestiona scheduler/cola/tiempo; el firmware hace POST y publica la tabla. | No convertir el BIOS en kernel por accidente. |
| `IFLOW-MAN-D10` | **Nombres genéricos de hardware** en la CPU: `InterruptController`, `InterruptVectorTable`, `DisplayDevice`, `NvramDevice`, `Mmu`, `Bus`. | La CPU no debe depender de la estructura interna del BIOS. |
| `IFLOW-MAN-D11` | **Cola con handles, no con mensajes completos.** El host conserva los objetos; la VM guarda índices de 16 bits + generación/epoch y, si hace falta, deadline/estado en arrays separados. | Evita serializar todo el mensaje y permite 12.288 handles en 24 KB. |
| `IFLOW-MAN-D12` | **Firmwares de rate limit como producto:** `drop-on-saturation.flow`, `queue-on-saturation.flow`, `adaptive.flow` (decide según carga/prioridad). | Es la misión de iFlow: lógica compleja de chat. |
| `IFLOW-MAN-D13` | **Array de destinatarios null-separated** en un buffer acotado, con contrato explícito: UTF-8, contador de elementos = nulos (o nulos+1), máximo de bytes, comportamiento ante overflow. | Es viable, pero la fórmula de longitud debe corregirse y el buffer no es infinito. |

## 6. Matemática de la cola: correcciones y límites

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
  la densidad. Si se migra, conviene mantener handles de 16 bits y usar 32 bits solo para direcciones
  y tamaños. Toca ISA, ABI, parser, MMU y tests.

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

## 8. Criterio de cierre de esta auditoría

- No se modifica código hasta ratificar `IFLOW-MAN-D01…D13`.
- Cada hallazgo debe tener evidencia en código y, cuando se remedie, un test de regresión.
- Este documento es el registro definitivo de los hallazgos `IFLOW-MAN-*`; al cerrar la pasada se
  actualizará su estado (confirmado, descartado o remediado).
- No se publica nada sin autorización explícita. `476c942` sigue siendo un commit local.
