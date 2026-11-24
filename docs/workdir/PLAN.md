# PLAN — TextFormatter Suite

> **Obligatorio leer y seguir [La Filosofía](/docs/prompts/contexto/philosophy.md)** antes de trabajar en este documento o en el código.
>
> Documento vivo: conserva **estado operativo y trabajo activo**. Las decisiones estructurales viven
> en `docs/workdir/ADR.md`; las especificaciones, en `docs/prompts/`. El historial cerrado vive en Git.
>
> Última actualización: 2026-11-23.

## Estado operativo

Base **1.0** en régimen pre-release: no hay versión estable ni producción. Los artefactos usan
`1.0.0` y los YAML `config-version: 1.0`. El E2E real en Spigot/Paper, Fabric y Velocity se ejecuta
después de `1.0.0`; los fallos beta se corrigen en la línea beta.

Gate vigente:

```sh
export JAVA_HOME=/opt/javac/x64/21
./gradlew build checkArchitecture checkLocks --no-daemon
cd src/web-editor && npm run check
git diff --check
```

Estado verificado:

- **`iflow-16`:** línea de 16 bits cerrada funcionalmente. `.flow` v2 es una imagen ROM2 con
  cabecera, TOC, segmentos tipados, hash SHA-256 y little-endian único en cabecera, TOC,
  instrucciones, inmediatos, direcciones, constantes, memoria y MMIO. `FLOW`/`ROM2` son firmas de
  bytes. Los fixtures se regeneraron y el compilador, desensamblador, editor y parser comparten el
  mismo layout. No hay lector big-endian ni migración en runtime.
- **`iflow-32`:** línea activa de 32 bits. V0–V3 cerrados; V4 tiene loader, hub/Router, kernel
  persistente, datos/bss, load addresses arbitrarias y aislamiento por unidad. Su suite y el gate
  están verdes.
- **`iflow-wasm`:** W1–W3 cerrados (contrato, runtime, router con SHA-256 y hot-reload). W3.5
  (paridad de mensaje) sigue pendiente.
- **`iflow-firewall`:** motor legacy independiente con SpEL; no es shim ni compite con el hub.
- **Pipeline y sync:** ADR-036 implementado en su alcance; las superficies nativas de
  `sync-discord`, `sync-telegram`, `sync-http`, `sync-websocket`, `sync-socket`,
  `sync-velocity-core`, `sync-bukkit` y `sync-fabric` están publicadas. Quedan slices por backend.
- **Plataformas:** Spigot/Paper solo tiene un smoke test; Fabric compila y ejecuta el probe;
  Velocity compila y pasa tests de paquete. No hay E2E real. Cualquier combinación no listada es
  **desconocida**.

## Trabajo activo

| ID | Estado | Siguiente acción | Bloqueo | Referencia |
|---|---|---|---|---|
| **V4 — `iflow-32`** | Loader, hub/Router y aislamiento por unidad cerrados | Varios programas y scheduler de procesos guest; aceptación con un mensaje real que cruce el pipeline con una política `.flow` v3 | Ninguno de V4; el resto depende del scheduler | ADR-037, ADR-038 |
| **V5 — herramientas v3** | Assembler `.flowasm` v3 adelantado en V3-2a | Firmware completo, ejemplos y validador | V4 para el corpus | ADR-037 |
| **V6 — compilador C** | Diseño aceptado; implementación pendiente | C0 (subconjunto y ABI) cuando V5 cierre | Bloqueado por V5 | ADR-023 |
| **W3.5 — paridad** | Contrato cerrado en ADR-031/ADR-036; implementación pendiente | Matriz `.flow` v3 ↔ `.wasm`, tests negativos y conformidad cruzada | Requiere V4 y el puente VM/Wasm | ADR-031, ADR-036 |
| **PL-2 — sync restante** | Superficies principales implementadas | Slices pendientes por backend y deuda de nombres | Ninguno; prioridad media | ADR-036; docs/prompts/diseno/pipeline-sync-v1.md §16 |
| **PL-4 — decisiones** | Editor web implementado en su alcance | Recalibrar límites anti-bomba; decidir exclusiones v3 | Requiere datos reales y contrato v3 | ADR-036, PL-4 |
| **PL-5 — config en vivo** | Propuesta, sin ADR aceptado | Cerrar esquema, protocolo y autorización | V4 y gate de `iflow-32` | Propuesta PL-5 |
| **PL-6 — caches/persistencia** | Etapa 1 en curso | Cerrar coherencia, ext4/FAT32 y editor de nodos | V4, revisión legal y modelo de memoria | Propuesta PL-6 |
| **AUD-1/AUD-2** | Pendientes | Revisión manual función por función | Humano; no la ejecuta un agente | `AUDITORIA.md` |
| **T5–T7** | Pendientes | Regresión bug→test, fuzzing y JMH | Ninguno | Este PLAN |
| **E2E.1** | Pendiente | E2E real posterior a `1.0.0` | Release `1.0.0` | ADR-034 |

## V4 — `iflow-32`

V4 integra la línea de 32 bits en el hub sin despacho dual ni retirada de `iflow-16`.

Completado:

- Loader: el kernel guest escanea la TOC, valida `PRG3`, copia el único `PROGRAM` desde su
  `loadAddress`, hace zero-fill hasta `memSize`, mapea con `W^X` y `U = 1`, asigna `PIDENT`/`MIDENT`
  y entra en modo usuario. Rechaza más de un programa, `loadAddress` no alineada o fuera de rango,
  `memSize > 64 KiB`, datos/bss que compartan página con el código y agotamiento del pool L2.
- Hub/Router: `Iflow32Activator` publica `IflowBackend`/`Router` con nombre `iflow-32`;
  `Iflow32Router` carga `policy.flow`, arranca el kernel una vez por recarga y lo reutiliza por
  mensaje; abre una unidad `(message × recipient)` con `MIDENT`, commitea el journal y traduce
  `ROUTE_DECISION` a `RouteOutcome` (`DELIVER`→`LOG`, `CANCEL_EVENT`→`DROP`, `ERROR`→`REJECT`).
- Aislamiento por unidad: el kernel recopia el segmento del `PROGRAM` desde la ROM, hace zero-fill
  hasta `memSize` y limpia la página de pila antes de entrar en U. La NVRAM persiste; la RAM del
  programa se reinicia por unidad. Falla cerrado ante ROM inválida, fault, watchdog o ausencia de
  decisión.

Pendiente:

- **Varios programas y scheduler de procesos guest.** Falta decidir en ADR-037 cómo se elige el
  programa por mensaje y cómo se reparten capacidades por `PIDENT`. Sin esa decisión no se implementa
  el scheduler.
- **Aceptación de V4:** un mensaje real atraviesa el pipeline con una política `.flow` v3; `iflow-16`
  sigue soportada y seleccionable; no hay despacho dual ni fallback entre líneas.

## V5 — herramientas de ensamblado

El assembler v3 ya existe en `tools/iflow-compiler` y se selecciona con `iflow-as -f iflow-32`.
Incluye secciones BIOS/BOOT/KERNEL/PROGRAM/DATA/FONT/CONFIG, directivas, encoder completo, CSR,
atómicas, alias `Jcc`, ROM `FLOWROM` v3 y `PRG3` con hashes. `RomParser` e `InstructionDecoder`
aceptan la ROM generada.

Pendiente: firmware completo, ejemplos y validador de artefactos. Criterio de cierre: build
reproducible, corpus de ejemplos y tests verdes.

## V6 — compilador C

Diseño en ADR-023. No se inicia antes de V5. El compilador es una herramienta offline
(`tools/iflow-c/`, CLI `iflow-cc`) que emite `.flowasm` v3; el assembler v3 sigue siendo el único
codificador y productor de ROM.

Alcance: C11 freestanding, ABI C v3 little-endian, `libiflow`, `crt0`, soft-float, atómicos, inline
asm y optimización `-O0`/`-O1` determinista. Exclusiones explícitas: `_Complex`, hilos,
`setjmp`/`longjmp`, VLA, bitfields, caracteres anchos, `_Generic`, `typeof`, `asm goto` y variádicas.
Lo no soportado falla con diagnóstico.

Fases: C0 contrato, C1 frontend, C2 IR/backend, C3 ABI/runtime, C4 optimización/robustez y C5
toolchain. El host no compila C ni añade toolchain nativa.

## W3.5 — paridad `iflow-32` (v3) ↔ `iflow-wasm`

La paridad es de **contrato**, no de implementación. La base es la spec v3
(`docs/prompts/diseno/iflow-cpu-implement.md`), no el código v2 de `iflow-16`.

| Área | Contrato v3 | Pendiente en `iflow-wasm` |
|---|---|---|
| Contexto | Tipo, dirección, emisor, destinatario, canal e identidad del mensaje | Imports de lectura, sin mutación |
| Contenido | `TRANSFORM_APPLY` sobre texto, placeholders y formato | Host functions TLV con UTF-8 sin truncar |
| Metadatos | Idioma, color, flags y `PROP_TRANSLATE` booleano | Validar `PROP_TRANSLATE`; no copiar `PROP_*` v2 retirados |
| Canal | `textformatter.channel.set` como única mutación de canal | `sync_invoke("textformatter", "channel.set", ...)` con permisos |
| Routing | Decisión explícita y tipada, default deny, `DELIVER` explícito | Import de decisión; sin `REDIRECT`/`CHANNEL_REDIRECT` |
| Journal | Solo write set del mensaje, atómico | Transacción de mensaje en el host |
| Rate limit/tiempo | Política en userspace; host solo aplica un cap duro | Exponer reloj/ciclos y `HALT`/sleep; no importar `RATE_LIMIT` |
| Input ABI | TLV, UTF-8 sin cortar, ausencia distinta de `"null"` | TLV y overlay de lectura del journal |

Criterios de cierre:

1. El mismo corpus `.flow` v3 y `.wasm` produce la misma decisión y el mismo mensaje observable.
2. `textformatter.channel.set` valida existencia y permisos igual en ambos motores.
3. El journal es atómico y cubre solo el write set del mensaje.
4. Rate limit y tiempo viven en el guest; el host no calcula backoff.
5. El ABI TLV no trunca UTF-8 y distingue ausencia de `"null"`.
6. La matriz y los tests negativos quedan documentados: propiedad desconocida, enum inválido,
   buffer insuficiente, capability denegada y canal inexistente.
7. `Formats` entrega N mensajes separados y el render de redirect conserva el recipient original.

## PL-2 — superficie de acciones sync: resto

Las superficies nativas principales están publicadas. Quedan estos slices:

- **`sync-discord`:** `channel.permission.overwrite`, consultas de miembros/roles, canales de voz y
  eventos de auditoría.
- **`sync-telegram`:** `chat.member.restrict` (mute), `message.pin`/`unpin`,
  `chat.permission.set` y `chat.description.set`.
- **`sync-http`:** cabeceras o métodos configurables cuando el adaptador pueda ejecutarlos.
- **`sync-websocket`:** cierre de una conexión concreta y suscripción remota.
- **`sync-socket`:** reapertura forzada del listener y métricas de datagramas.
- **`sync-velocity-core`:** reconexión del adaptador e inspección de la cola.
- **`sync-bukkit`/`sync-fabric`:** acciones de canal local y consultas de scoreboard (listar
  objetivos y puntuaciones). Los canales se cubren por el LCM `message.send`/`message.deliver`.
- **Conformidad VM/Wasm:** suite cruzada de `SYNC_SURFACE`/`SYNC_INVOKE` con el mismo `TlvCodec` y
  los mismos estados.
- **Fuel:** la retirada del fuel de `iflow-16` queda para la migración v3; `iflow-32` no cobra fuel.

Deuda de nomenclatura (prioridad baja): evaluar `SyncSink` frente a `SyncBackend`/`SyncEndpoint`.
No se renombra sin un plan y sin actualizar contratos, tests y documentación.

## PL-4 — decisiones abiertas del editor

El editor web importa/exporta la suite completa, gestiona ROM, valida YAML y conserva archivos
opacos. Quedan dos decisiones:

- **Límites anti-bomba:** baseline provisional de 100.000 entradas, 1 GiB total, 256 MiB por archivo
  y ratio 500:1 solo por encima de 1 MiB. Se recalibra con carpetas reales antes de congelarlo. Los
  límites viven en código, no en YAML.
- **Secciones de exclusión:** la exclusión completa de firmware, BIOS, bootloader, kernel y stdlib
  pertenece a v3; en `.flow` v2 solo existe el corte de usuario (`>= 0x1000`). Se revisa cuando el
  contrato v3 esté implementado.

Los grafos desde ROM están resueltos: el proceso inverso usa desensamblado y CFG, no metadatos de
grafo dentro de la ROM.

## PL-5 — configuración en vivo, telemetría y relay ZIP E2EE

**Estado:** propuesta revisada (2026-11-16). No hay ADR aceptado ni contrato vigente; no autoriza
dependencias nuevas. Origen: `docs/prompts/diseno/web-editor-transmission.md`, que no se modifica.

**Objetivo.** Permitir que el editor web configure y observe servidores en caliente sin backend
autoalojado ni servicio de pago. La vía primaria transporta solo cambios y consultas; el ZIP de PL-4
queda como fallback offline y como intercambio explícito de una suite completa.

**Diseño propuesto.**

1. **Hosts en configuración, nunca hardcodeados.** La lista vive en
   `config/web-editor/mirrors.yml` con `config-version: 1.0`. Un archivo ausente o inválido falla de
   forma explícita; no hay lista de emergencia.
2. **Dos modos.** `relay` usa brokers públicos MQTT sobre WebSocket con E2EE; `direct` usa WSS
   contra el plugin Velocity. El navegador bajo HTTPS solo intenta WSS para evitar *mixed content*.
3. **Sincronización incremental.** Operaciones por archivo o ruta con hash/versión base; aplicación
   atómica, conservación de comentarios y detección de conflictos. El ZIP completo solo se usa para
   la primera carga o para exportación/importación explícita.
4. **Acciones y telemetría.** Petición/respuesta y suscripciones para estado de la máquina iFlow,
   CPU virtual, RAM, núcleos, quantum/watchdog y journal. Una métrica no soportada falla de forma
   explícita; no se inventa.
5. **Jerarquía multi servidor.** En `direct`, Velocity coordina servidores hijos y publica topología.
   Bungee solo se soporta si se implementa de forma explícita.
6. **Seguridad y autorización.** En `relay`, AES-128-GCM con IV de 12 bytes y código de 48 hex; topic
   y clave son secreto portador. En `direct`, TLS no sustituye autenticación de aplicación.
   Consultar telemetría, editar configuración y ejecutar control son permisos distintos, con
   fail-closed y auditoría.
7. **Fallback explícito.** Si el canal no está disponible, se usa el ZIP de PL-4; se reporta y nunca
   se presenta como sincronización en vivo correcta.
8. **ZIP de uno o varios servidores.** El ZIP normal representa un servidor, pero el esquema debe
   admitir jerarquía multi servidor. No se añade lector doble ni se reinterpreta un ZIP de un
   servidor como multi servidor.

**Decisiones abiertas.**

- Esquema de `mirrors.yml`: `id`, `kind` (`relay`/`direct`), URL, TLS, prioridad, `enabled` y
  credenciales por referencia; recarga inmediata o solo al reconectar.
- Relay frente a directo: SLA, autenticación, autorización y protección contra exposición.
- Protocolo incremental: formato de operaciones, hash base, idempotencia, conflictos y notificación.
- Multi servidor: identificador estable, padre/hijo, aislamiento y formato del contenedor.
- Telemetría: identidad de proceso, unidades, muestreo, coste, retención y métricas por línea.
- Autorización: separar lectura, edición y control; auditoría y fail-closed.
- Cifrado y secreto: relay y directo; TLS no sustituye autenticación.
- ZIP y relay: el techo de 16 bits de `CHUNK_INDEX`/`TOTAL_CHUNKS` limita a ≈ 256 MiB; el canal
  incremental no hereda ese límite y cualquier ZIP por red rechaza tamaños mayores.
- Cliente del navegador: dependencia MQTT/WebSocket o framing mínimo propio, con CSP y sin
  dependencias nuevas sin ADR aceptado. No se reutiliza `sync-websocket` como broker público.
- Operación: timeout, reintentos, cancelación, limpieza de topic, backpressure y rate limiting.

**Fases (sin implementar).**

1. `mirrors.yml`: esquema, carga, validación, recarga y selección.
2. Canal `relay`/`direct`: TLS, autenticación, E2EE y reconexión.
3. Sincronización incremental: operaciones, hash base, aplicación atómica y conflictos.
4. Telemetría y acciones en caliente con capacidades y auditoría.
5. Topología multi servidor y coordinación desde Velocity.
6. UI de selección, edición en vivo y fallback ZIP; pruebas de fallo.

**Requisitos para aceptar:** esquema de hosts, modelo de amenaza, protocolo versionado,
idempotencia de QoS 1, techo ZIP, contrato de telemetría, topología multi servidor, pruebas de fallo,
separación de `sync-websocket` y conservación del ZIP de PL-4.

**Bloqueos:** no se implementa mientras V4 y el gate de `iflow-32` sigan abiertos. Requiere aceptar
la propuesta y resolver `mirrors.yml`, protocolo, autorización y telemetría.

## PL-6 — caches, HOST sync, partición persistente y editor de nodos

**Estado:** documentación de revisión. No hay ADR, contrato ni implementación. Las tres etapas son:
(1) documentar y acotar, (2) debatir alcance, alternativas, riesgos y mejoras, y (3) escribir el ADR
y el plan de implementación cuando el diseño esté cerrado.

**`iflow-16`.**

1. **Presencia de sync por `HOST`:** consulta explícita de si hay un backend de sincronización y qué
   superficies ofrece. No se infiere por la existencia de un módulo ni se usa un default silencioso.
   Hay que decidir si es un servicio nuevo, una capacidad del envelope o ambos, y cómo falla sin sync.
2. **Caché L2:** caché de RAM, no de ROM ni de la imagen del programa. Definir línea, asociatividad,
   política de escritura, coherencia con RAM y comportamiento ante `FENCE`.
3. **Caché L1:** separada de instrucciones y datos; se llena desde L2 y L2 desde RAM. Definir línea,
   asociatividad, escritura, coherencia con L2, MMU/TLB, `SFENCE`/`FENCE` y auto-modificación.
4. **Sin almacenamiento en ROM:** la ROM sigue siendo la imagen de programa; L2 es caché de RAM.

**`iflow-32`.**

1. **Presencia de sync por `HOST`:** misma consulta, adaptada al ABI v3 y a las capacidades.
2. **Almacenamiento persistente en ROM:** sin tabla de particiones por ahora; formato ext4; tamaño
   de partición separado de BIOS/firmware; SO dentro del sistema de archivos, incluidos kernel y
   bootloader; primer sector estilo MBR sin tabla de particiones; ejecutables como copias de memoria
   RAM, análogos a `.COM` pero mejor definidos. **FAT32 queda como pregunta abierta** por licencias,
   patentes y compatibilidad.
3. **Caché L2:** caché de RAM, no de la partición persistente ni de la ROM. Coherencia con RAM,
   `FENCE`, MMIO, MMU/TLB, atómicas y multicore; el almacenamiento queda fuera.
4. **Caché L1:** instrucciones y datos; coherencia intra-VM compatible con ADR-037. Decidir si es por
   core y cómo participa en TLB shootdown y coherencia de datos compartidos.

**Editor web.**

1. **Tamaño de partición configurable al compilar:** opción validada, con límites, unidad,
   alineación, impacto en ext4/FAT32 y errores cuando no cabe. No se confunde con BIOS/firmware.
2. **Rediseño del editor de nodos:** bloque de bucle único para `for` sobre arrays, `while (i < N)`,
   `while (i > N)` y rangos; condicional con salidas `true`/`false`; arista como dirección lógica;
   validación de ciclos, aristas huérfanas, tipos, alcance y límites antes de compilar.
3. **Compatibilidad del formato de nodos:** decidir si el rediseño rompe el formato guardado, si hay
   exportación/importación explícita o si se declara incompatible. Sin lector doble ni migración
   silenciosa.

| Etapa | Contenido | Salida | Estado |
|---|---|---|---|
| 1. Documentación | Propuesta, límites, preguntas y dependencias | Este PLAN | **En curso** |
| 2. Debate | Alcance, alternativas, coste, riesgos, licencias e impacto en ISA/ABI, ROM, MMU, caches y editor | Decisiones cerradas | Pendiente |
| 3. Planificación | ADR aceptado y plan de implementación por fases | ADR + fases + tests + gate | Pendiente |

**Bloqueos:** V4 y gate de `iflow-32`; revisión legal y de formato para ext4/FAT32; coherencia y
modelo de memoria para caches; IR y formato guardado para el editor de nodos.

## Revisiones manuales AUD-1/AUD-2

La revisión es **humana**, función por función, y explica qué hace cada una, cuándo se ejecuta, qué
efecto produce y qué deja fuera. El diagnóstico se escribe en `docs/workdir/AUDITORIA.md` mientras
la auditoría manual está en curso y se congela al terminar. Una auditoría agéntica se reemplaza por
completo. Un agente no ejecuta esta revisión ni la sustituye por otra: solo registra o corrige lo que
la persona indique. Las correcciones son trabajo aparte; si son P0/P1, llevan test de regresión.

| ID | Alcance | Estado |
|---|---|---|
| **AUD-1** | `sync` función por función: contratos `api.sync.*`, autoridad y cuotas de `DefaultSyncBus`, superficies nativas, entrega, puente `SYNC_SURFACE`/`SYNC_INVOKE` y auditoría | Pendiente |
| **AUD-2** | Manager función por función: `manager-spi`/`core`/`apt`, instalar → activar → desactivar, dependencias, metadatos, classloading, hot unload/upgrade y descargas | Pendiente |

## Tests T5–T7 y cobertura

T0–T4 están cerrados: inventario, scaffold, gates por módulo, contratos SPI/API e integración por
dominio. Quedan:

- **T5 — Regresión:** cada bug/fix P0/P1 de `AUDITORIA.md` y cada commit `fix:` tiene un test que
  falla sin el fix y pasa con él. Matriz bug→test inline en este PLAN.
- **T6 — Fuzzing/property-based:** `textformatter`, `messages`, bytecode `iflow-16` y loader
  `iflow-wasm` con jqwik o JUnit-Quickcheck; campañas nocturnas.
- **T7 — Performance/soak:** `loadtest` y `performance` con JMH, umbrales de regresión p99,
  throughput y heap; alerta si la regresión supera 10 %.

Objetivo de cobertura: líneas/branches ≥ 70 % en producción y ≥ 90 % en SPI/API pública. Los gaps
conocidos por debajo de 70 % son `sync-fabric`, `sync-discord-jda`, `fabric-host`, `spigot-host`,
`transport`, `sync-velocity`, `sync-discord`, `messages`, `coretranslator`, `iflow-firewall`,
`sync-websocket`, `sync-velocity-core` y `observability`. La tabla completa es un snapshot histórico;
se regenera con `tools/project-metrics/`.

## E2E.1 — betatesting posterior a `1.0.0`

Probar manualmente todas las features en Spigot/Paper, Fabric y Velocity reales, con matriz de
versiones y evidencia. No es un smoke test de carga. Registrar el resultado y declarar **desconocida**
toda combinación no probada. Los fallos se corrigen en la línea beta; estabilidad y producción se
declaran solo al cerrar este ciclo.

## Decisiones abiertas y deuda

- **PL-1/TF-001:** hay una contradicción documental: unas notas dan PL-1/PL-3 por cerrados y otras
  dicen que la configuración de `outputs` sigue pendiente. Verificar el código real y cerrar la
  contradicción en la próxima sesión; no se elige una versión en silencio.
- **PL-2:** la frase «resto de backends pendiente» ya no describe el estado; el detalle por backend
  está en «PL-2 — superficie de acciones sync: resto».
- **`iflow-wasm`:** falta decidir nombre/artefacto definitivo, compilador/WASI y límites. El ABI de
  imports, la validación de exports, el hash y el hot-reload están cerrados en ADR-031/ADR-033.
- **W3.5:** matriz de paridad y tests negativos, alineados a la spec v3 y no al ABI v2.
- **ChatTranslator CT5:** el panel de backend de traducción sigue diferido con el editor web.
- **Plataformas:** Spigot/Paper smoke, Fabric probe y Velocity tests de paquete; todo lo demás es
  desconocido hasta E2E.1.
- **ADR-005:** su ventana MMIO de 256 B en `0x0F00–0x0FFF` rige `iflow-16`; `iflow-32` se rige por
  ADR-037. La nota de ámbito está en el ADR.

## Bloqueos y autorizaciones

- V6 bloqueado por V5; no se inicia contra el ISA v2.
- PL-5 y PL-6 bloqueados por V4 y por el gate de `iflow-32`.
- PL-6: FAT32 requiere revisión legal/patentes; ext4 y el modelo de memoria siguen abiertos.
- Los documentos de `docs/prompts/` son de solo lectura salvo autorización explícita. Cualquier
  cambio de rutas, esquema o contrato en ellos se pregunta antes.
- No se hace `git push` sin autorización explícita. Los commits se escriben en español.

## Índice documental

- Decisiones estructurales: `docs/workdir/ADR.md`.
- Filosofía y costumbres: `docs/prompts/contexto/philosophy.md`.
- Especificaciones: `docs/prompts/especificaciones/` y `docs/prompts/diseno/storage.md`.
- Auditoría vigente: `docs/workdir/AUDITORIA.md` (solo se consulta; no es lectura obligatoria).
- Documentación de usuario: `docs/wiki/` (contexto, no fuente de verdad).
- Skills: `.opencode/skills/` (procedimientos, no contratos).
