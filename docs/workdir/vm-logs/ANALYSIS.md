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

### F-2 · Variable muerta `fp` en `executeReturn`

En `VmInterpreter.java:435` se lee `fp` y no se usa en ningún sitio. Es el resto de la
restauración de `FP` que se pretendía implementar (relacionado con F-1).

### F-3 · Comentario incorrecto sobre el empaquetado de registros

`VmInterpreter.java:128` (caso `MOV`) dice:

```java
// RR: MOV Rd, Rs (packed in one byte: high nibble Rs, low nibble Rd)
```

Pero el código (`int rd = (packed >> 4) & 0x0F;`) y el ensamblador
(`tools/iflow-compiler/src/assembler.ts:657,661 → (rd << 4) | rs`) usan **nibble alto = Rd**.
El comentario está invertido. (Los demás opcodes RR no llevan comentario, así que sólo `MOV` es incorrecto.
Verificado con `SUB` no conmutativo: `SUB R1,R2` produce `R1 = R1 - R2`, coherente con nibble alto = Rd.)

---

## 3. Observaciones (ambiguas, decidir/documentar)

- **Flags en instrucciones no aritméticas.** `LI`, `MOV`, `LDB`, `LDW` llaman a `updateZN`
  (fijan `Z`/`N`); lógica y `DIV`/`MOD` limpian `C`/`V`. §7.2 dice que *"las instrucciones
  aritméticas y las comparaciones"* actualizan flags, sin declarar el resto como "no debe tocar".
  No es necesariamente un fallo, pero conviene fijarlo por escrito.
- **El log no incluye el detalle de memoria.** No se registra la dirección/valor afectado por
  `STW/LDW/STB/LDB/COPY/MOVE` ni el coste variable real del `HOST`. Es un extra que permitiría
  validar las operaciones de memoria directamente desde el log (hoy sólo se infieren por lecturas
  posteriores a registros). No es un requisito del diseño actual de `logger`.

---

## 4. Reproducir

```bash
export JAVA_HOME=/opt/javac/x64/21
./gradlew :src:iflow:test -Plogger.debug=true
```

Los artefactos crudos (`*.log`, `*-cycles.txt`) se regeneran desde la suite; este directorio los
mantiene fuera de git mediante `.gitignore`.
