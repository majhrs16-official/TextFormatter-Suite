# iFlow VM — debug capture logs

Generated with the zero-overhead logger compiled with `-Plogger.debug=true`.

## Files

| File | Description |
|------|-------------|
| `firmware-debug.log` | Full logger output running `tools/iflow-compiler/test/firmware.flow` (INFO + 158 `VM_CYCLE` DEBUG lines). | |
| `firmware-cycles.txt` | Only the `VM_CYCLE` lines, timestamp stripped. One line per executed instruction. |
| `firmware-mem.txt` | Only the sparse `MEM` lines (memory/block accesses) from the same run. |
| `tests-debug.log` | Full logger output of the whole `:src:iflow:test` suite (247 tests). ~10.3k `VM_CYCLE` lines. |
| `tests-cycles.txt` | Same, `VM_CYCLE` lines only. |
| `tests-mem.txt` | Same, `MEM` lines only. |

## Line format

```
IP=0x<ip> OP=0x<opcode>(<MNEMONIC>) REGS=R0=.. R1=.. .. R7=.. IP=.. SP=.. FP=.. FL=..(Z=.. N=.. C=.. V=..) FUEL=<remaining>
```

The register block is dumped **before** the instruction executes at that IP.
`FUEL` is the budget remaining after charging the base cost of the instruction.

For memory/block instructions only, a compact **sparse** `MEM` line follows (never a full
memory dump), recording just the touched range:

```
MEM RD8  [0x1002]=0xAB -> R2
MEM RD16 [0x1000]=0x1234 -> R2
MEM WR8  [0x1002]<-0xAB (R1)
MEM WR16 [0x1000]<-0x1234 (R1)
MEM COPY [0x1000..0x1005] -> [0x1100..0x1105] len=6 sumSrc=0x00CC sumDst=0x00CC
MEM MOVE [0x1000..0x1003] -> [0x1002..0x1005] len=4 sumSrc=0x0044 sumDst=0x0066
```

`sumSrc`/`sumDst` are 16-bit additive checksums of the block ranges. For a non-overlapping
`COPY` they must be equal; for an overlapping `MOVE` `sumDst` reflects the `memmove` result.

## How to regenerate

```bash
export JAVA_HOME=/opt/javac/x64/21

# firmware only
./gradlew :src:iflow:test -Plogger.debug=true --tests '*FirmwareIntegrationTest*'

# full suite (debug dumps on screen)
./gradlew :src:iflow:test -Plogger.debug=true
```

## Quick consistency checks

Useful greps:

```bash
# Every RET and the FP/SP at that moment (FP restoral check)
grep '(RET)' firmware-cycles.txt

# Every CALL (SP/FP should move together, SP -= 2)
grep '(CALL)' firmware-cycles.txt

# Instruction-pointer sequence
grep -oE '^IP=0x[0-9A-F]+' firmware-cycles.txt | uniq -c | head
```
