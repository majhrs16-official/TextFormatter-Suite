# iFlow VM — debug capture logs

Generated with the zero-overhead logger compiled with `-Plogger.debug=true`.

## Files

| File | Description |
|------|-------------|
| `firmware-debug.log` | Full logger output running `tools/iflow-compiler/test/firmware.flow` (INFO + 158 `VM_CYCLE` DEBUG lines). |
| `firmware-cycles.txt` | Only the `VM_CYCLE` lines, timestamp stripped. One line per executed instruction. |
| `tests-debug.log` | Full logger output of the whole `:src:iflow:test` suite (243 tests). ~10.3k `VM_CYCLE` lines. |
| `tests-cycles.txt` | Same, `VM_CYCLE` lines only. |

## Line format

```
IP=0x<ip> OP=0x<opcode>(<MNEMONIC>) REGS=R0=.. R1=.. .. R7=.. IP=.. SP=.. FP=.. FL=..(Z=.. N=.. C=.. V=..) FUEL=<remaining>
```

The register block is dumped **before** the instruction executes at that IP.
`FUEL` is the budget remaining after charging the base cost of the instruction.

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
