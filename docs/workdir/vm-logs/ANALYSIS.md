# Análisis de los logs de la VM iFlow

Fecha: 2026-10-16
Fuentes: `firmware-cycles.txt` (158 ciclos, `test/firmware.flow`) y `tests-cycles.txt` (10 339 ciclos, suite `:src:iflow:test`).
Método: desensamblado del binario `.flow` y cruce de cada ciclo contra los límites de instrucción reales
(script `analyze.js` / `analyze2.js` en `/tmp/iflow-debug`), más verificación manual contra `docs/prompts/iflow/implement.md`.

---

## 1. Comprobaciones que salen LIMPIAS

| Comprobación | Resultado |
| --- | --- |
| Cada IP ejecutado cae en un límite de instrucción válido | 158/158 firmware, 0 anomalías |
| El byte de opcode del log coincide con el del binario | 0 discrepancias |
| Efectos colaterales de registros (¿se pisa un registro que no toca?) | 0 anomalías |
| Avance de IP (secuencial / salto / retorno) | 0 anomalías |
| Disciplina de pila CALL/RET (cada RET vuelve a la dirección apilada) | 0 anomalías |
| Fuel: coste base 1 por instrucción | 0 anomalías reales |
| Fuel variable de `HOST` | coherente (recargos de 6–21 por servicio) |
| Flags Z/N/C/V (ADD carry `0xFFFF+1`, overflow con signo `0x7FFF+1`, NOT/lógica, SHL/SHR/SAR con extensión de signo, CMP/CMPU) | correctos |
| Bits reservados de `FL` (4–15) siempre a 0 | 10 339 líneas, 0 violaciones |
| `SP` y `FP` siempre pares | 0 violaciones |
| Integridad del log: `IP` de cabecera == `IP` dentro de `REGS` | 0 discrepancias |

Los "reinicios" de fuel en `tests-cycles.txt` (28) y los "saltos" de fuel son **nuevas ejecuciones**
concatenadas en el mismo stream (presupuestos iniciales 49 / 9999 / 100000), no fallos.

---

## 2. Hallazgos

### F-1 · `RET` no restaura `FP` (desviación de la especificación)

La especificación lo pide de forma explícita:

- §7.4 (convención de llamadas): *"`RET`: restaura la dirección **y el frame**."*
- §8.7 (saltos y subrutinas): *"`RET`: restaura el retorno **del frame actual**."*
- §7.1: `FP` = *"Base del frame actual"*.

La implementación sólo restaura la dirección y la pila:

```java
// VmInterpreter.executeReturn()  (líneas 430-445)
int sp = regs.get(RegisterFile.SP);
int fp = regs.get(RegisterFile.FP);   // <-- se lee y NUNCA se usa
int retAddr = softMMU.readWord(sp);
sp += 2;
regs.set(RegisterFile.SP, sp);        // SP se restaura
regs.set(RegisterFile.IP, retAddr);   // IP se restaura
// FP = ???  (nunca se restaura)
```

Mientras que `CALL` sí escribe `FP = SP` (VmInterpreter.java:424).

**Evidencia en el log** (llamada anidada `0x00BF` → `0x00C4`, `firmware-cycles.txt`):

```
51: IP=0x00C9 RET   SP=0x5FFA FP=0x5FFA
52: IP=0x00C7 RET   SP=0x5FFC FP=0x5FFA   <- SP ya es del llamador, FP sigue en el marco hijo
53: IP=0x00C2 RET   SP=0x5FFE FP=0x5FFA   <- FP obsoleto (debería seguir al SP del marco actual)
54: IP=0x0009 CALL  SP=0x6000 FP=0x5FFA   <- FP arrastra un marco que ya no existe
```

En el caso del firmware, 8 de los 9 `RET` dejan `FP` apuntando a un marco muerto.

**Impacto:** hoy es *latente*, porque ninguna instrucción direcciona memoria relativa a `FP`
(`LDB/LDW/STB/STW/COPY/MOVE` usan direcciones virtuales absolutas). Pero cualquier ABI que use
`FP` como base de variables locales se romperá tras el primer retorno. Es, además, una
contradicción directa con la especificación.

**Resuelto.** Se añadió una **pila sombra de `FP`** en `ExecutionContext`
(`enterCall(int callerFramePointer)` / `exitCall()` que devuelve el `FP` restaurado). `CALL`
guarda el `FP` del llamador; `RET` lo restaura. La dirección de retorno sigue en la pila de
memoria, así que la ABI de 2 bytes **no cambia** y `SP` conserva su comportamiento. Tras el
arreglo, en toda la suite (10 345 ciclos) **ningún `RET` deja `SP != FP`** del marco del llamador.

### F-2 · Variable muerta `fp` en `executeReturn`

En `VmInterpreter.java:435` se lee `fp` y no se usa en ningún sitio. Es el resto de la
restauración de `FP` que se pretendía implementar (relacionado con F-1). **Resuelto:** la variable
se eliminó y en su lugar `ctx.exitCall()` devuelve el `FP` del llamador para restaurarlo.

### F-3 · Comentario incorrecto sobre el empaquetado de registros

`VmInterpreter.java:128` (caso `MOV`) dice:

```java
// RR: MOV Rd, Rs (packed in one byte: high nibble Rs, low nibble Rd)
```

Pero el código (`int rd = (packed >> 4) & 0x0F;`) y el ensamblador
(`tools/iflow-compiler/src/assembler.ts:657,661 → (rd << 4) | rs`) usan **nibble alto = Rd**.
El comentario está invertido. (Los demás opcodes RR no llevan comentario, así que sólo `MOV` es incorrecto.
Verificado con `SUB` no conmutativo: `SUB R1,R2` produce `R1 = R1 - R2`, coherente con nibble alto = Rd.)

**Resuelto.** Corregidos los dos comentarios invertidos: `VmInterpreter.java` (caso `MOV`) y
`VmInterpreterTest.movInstruction`, que decía `high nibble=rs`. El código y los bytes de los tests
ya eran correctos; sólo la documentación en línea estaba mal. Se revisó el resto del ensamblador y
no quedan más comentarios con esa inversión (`RET rs` codifica el registro en el nibble alto por
convención, pero el intérprete ignora ese operando).

---

### F-4 · El intérprete no detecta el fin de código (usa un `IP` obsoleto)

Al reactivar los tests de `host` (que no compilaban desde la reescritura del VM) apareció este
fallo. En el bucle principal, el chequeo de fin de programa comparaba el `IP` **anterior** al avance:

```java
int newIp = context.registers().get(RegisterFile.IP);          // IP antiguo
if (!ipModifiedByInstruction && newIp == ip) {
    context.registers().set(RegisterFile.IP, ip + instrSize);  // IP nuevo…
}
if (newIp >= program.codeSize()) { break; }                     // …pero se compara el antiguo
```

Un programa cuya última instrucción avanza `IP` exactamente hasta `codeSize` no rompía el bucle: en
la iteración siguiente se lanzaba `invalidJump` (`ip >= codeSize`). **Resuelto**: se reutiliza el
`IP` ya actualizado para el chequeo.

### F-5 · Programa pass-through con `HOST` mal codificado

`VmConfigLoader.createPassThroughProgram()` emitía `Opcode.HOST` como **un solo byte**, pero `HOST`
es una instrucción de 2 bytes (`Opcode.instructionSize`). El `HALT` final quedaba absorbido como
operando de `HOST`, el programa se salía del código y, por F-4, toda evaluación con la política por
defecto terminaba en `REJECT`. **Resuelto**: se emite el byte de operando reservado (`0x00`); el
programa por defecto produce ahora `LOG`. `VmConfigLoaderTest` fija el layout corregido (11 bytes).

### F-6 · `DECISION_REJECT` colapsaba a `DROP`

`PolicyResultValidator` devolvía `DROP` ante **cualquier** entrada `CANCEL_MESSAGE`, de modo que un
programa que invocaba `ROUTE_DECISION` con `DECISION_REJECT` nunca producía `PolicyTarget.REJECT`
(la única forma de obtener `REJECT` era una excepción del VM). **Resuelto**: se distingue
`"route:REJECT"` del resto de cancelaciones. Nuevo test
`VmPolicyEngineTest.rejectDecisionFromProgram`.

> F-4, F-5 y F-6 se descubrieron al hacer compilar los tests de `host`/`loadtest`, que seguían
> usando la API de reglas eliminada y nunca se habían ejecutado desde `933bdd3`.

---

## 3. Observaciones (ambiguas, decidir/documentar)

- **Flags en instrucciones no aritméticas.** `LI`, `MOV`, `LDB`, `LDW` llaman a `updateZN`
  (fijan `Z`/`N`); lógica y `DIV`/`MOD` limpian `C`/`V`. §7.2 dice que *"las instrucciones
  aritméticas y las comparaciones"* actualizan flags, sin declarar el resto como "no debe tocar".
  No es necesariamente un fallo, pero conviene fijarlo por escrito.
- **El log ahora sí incluye el detalle de memoria** (implementado, ver §5). Sigue sin registrarse
  el coste variable real del `HOST` (sólo el remanente antes de invocarlo), pero el coste es
  deducible de la diferencia de `FUEL` entre el `HOST` y el ciclo siguiente.

---

## 5. Traza de memoria sparse (implementada)

Tras ejecutar cada instrucción de memoria/bloque, la VM emite **una sola línea compacta** con
sólo el rango tocado (nunca un volcado completo). Coste: 13 líneas en el firmware (158 ciclos) y
19 en la suite (10 339 ciclos), ~0.2% del total.

Verificaciones de datos que habilita (del firmware, todas correctas):

```
MEM WR16 [0x1000]<-0x1234 (R1)      ; y su lectura devuelve lo mismo
MEM RD16 [0x1000]=0x1234 -> R2
MEM WR8  [0x1002]<-0xAB (R1)
MEM RD8  [0x1002]=0xAB -> R2
MEM COPY [0x1000..0x1005] -> [0x1100..0x1105] len=6 sumSrc=0x00CC sumDst=0x00CC
MEM MOVE [0x1000..0x1003] -> [0x1002..0x1005] len=4 sumSrc=0x0044 sumDst=0x0066
```

- `COPY` no solapado: `sumSrc == sumDst` (copia íntegra verificada con checksum aditivo de 16 bits).
- `MOVE` solapado: los bytes resultantes son los esperados por la semántica `memmove`
  (`11 11 22 22` en `0x1002..0x1005`).

Reglas de formato (una línea `MEM` sólo aparece para `LDB/LDW/STB/STW/COPY/MOVE`):

| Operación | Formato |
| --- | --- |
| `LDB`/`LDW` | `MEM RD8|RD16 [0xADDR]=0xVAL -> Rd` |
| `STB`/`STW` | `MEM WR8|WR16 [0xADDR]<-0xVAL (Rs)` |
| `COPY`/`MOVE` | `MEM COPY|MOVE [src0..src1] -> [dst0..dst1] len=N sumSrc=0xS sumDst=0xD` |

---

## 4. Reproducir

```bash
export JAVA_HOME=/opt/javac/x64/21
./gradlew :src:iflow:test -Plogger.debug=true
```

Los artefactos crudos (`*.log`, `*-cycles.txt`) se regeneran desde la suite; este directorio los
mantiene fuera de git mediante `.gitignore`.
