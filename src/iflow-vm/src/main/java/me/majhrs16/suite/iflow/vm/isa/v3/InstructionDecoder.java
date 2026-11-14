package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.Objects;

/**
 * Decodes the hierarchical v3 instruction boxes.
 *
 * <p>The decoder validates the structural rules of ADR-037: instruction length,
 * reserved opcode combinations, the descriptor reserved bit, the reserved size
 * value, {@code base}/{@code idx} outside memory forms, the unused {@code rs}
 * field in non-indexed memory forms, and the opcode-specific size and operand
 * constraints. It does not execute the instruction or enforce privileges.</p>
 */
public final class InstructionDecoder {

    private static final long ADDRESS_SPACE_END = 0xFFFF_FFFFL;

    private static final int OPCODE_BOX = 1;
    private static final int DESCRIPTOR_BOX = 2;

    public Instruction decode(int pc, InstructionReader reader) {
        Objects.requireNonNull(reader, "reader");

        int opcodeByte = readByte(reader, pc, 0);
        int family = (opcodeByte >>> 6) & 0x03;
        int operation = (opcodeByte >>> 2) & 0x0F;
        InstructionForm form = InstructionForm.fromCode(opcodeByte & 0x03);

        Opcode opcode = Opcode.find(family, operation);
        if (opcode == null) {
            throw illegal(pc, "reserved opcode family=" + family + " operation=" + operation);
        }
        if (!opcode.supports(form)) {
            throw illegal(pc, opcode.mnemonic() + " does not support form " + form.code());
        }

        int descriptor = read16(reader, pc, OPCODE_BOX);
        if ((descriptor & 0x0001) != 0) {
            throw illegal(pc, "descriptor reserved bit is set");
        }

        int rd = (descriptor >>> 12) & 0x0F;
        int rs = (descriptor >>> 8) & 0x0F;
        int base = (descriptor >>> 4) & 0x0F;
        int idx = (descriptor >>> 3) & 0x01;
        int sizeCode = (descriptor >>> 1) & 0x03;
        if (sizeCode == 0x03) {
            throw illegal(pc, "reserved size 11");
        }
        MemorySize size = MemorySize.fromCode(sizeCode);

        if (!form.isMemory()) {
            if (base != 0 || idx != 0) {
                throw illegal(pc, "base/idx must be zero outside memory forms");
            }
        } else if (idx == 0 && rs != 0) {
            throw illegal(pc, "rs must be zero when idx=0 in memory forms");
        }

        int immediate = form == InstructionForm.REGISTER
                ? 0
                : read32(reader, pc, OPCODE_BOX + DESCRIPTOR_BOX);

        validate(pc, opcode, form, rd, rs, size, immediate);
        return new Instruction(pc, opcode, form, rd, rs, base, idx == 1, size, immediate);
    }

    private static void validate(int pc, Opcode opcode, InstructionForm form,
                                 int rd, int rs, MemorySize size, int immediate) {
        switch (opcode) {
            case MOV -> {
                if (form == InstructionForm.IMMEDIATE && size != MemorySize.WORD) {
                    throw illegal(pc, "MOV immediate requires size 32");
                }
            }
            case LEA -> {
                // LEA ignores size; the descriptor reserved bits were already checked.
            }
            case PUSH, POP -> {
                if (size != MemorySize.WORD) {
                    throw illegal(pc, opcode.mnemonic() + " requires size 32");
                }
            }
            case ADD, ADC, SUB, AND, OR, XOR, SHL, SHR, SAR,
                    MUL, DIV, DIVU, MOD, MODU, CMP, TEST -> {
                if (size != MemorySize.WORD) {
                    throw illegal(pc, opcode.mnemonic() + " requires size 32");
                }
            }
            case JMP, CALL -> {
                // Size is not part of the jump/call contract.
            }
            case JCC -> {
                if (rd != 0) {
                    throw illegal(pc, "Jcc reserves rd");
                }
            }
            case RET, ECALL, ERET, HALT, BREAK, NOP,
                    HOST, SFENCE_VMA, FENCE -> {
                // No opcode-specific descriptor constraint.
            }
            case RDCYCLE, RDTIME -> {
                if (rd == rs) {
                    throw illegal(pc, opcode.mnemonic() + " requires rd != rs");
                }
            }
            case CSRRW, CSRRS, CSRRC, CSRR, CSRW -> {
                if ((immediate >>> 12) != 0) {
                    throw illegal(pc, "CSR immediate high 20 bits must be zero");
                }
                if (Csr.fromAddress(immediate) == null) {
                    throw illegal(pc, "undefined CSR 0x" + Integer.toHexString(immediate));
                }
            }
            default -> throw new IllegalStateException("Unhandled v3 opcode: " + opcode);
        }
    }

    private static int read16(InstructionReader reader, int pc, int offset) {
        int low = readByte(reader, pc, offset);
        int high = readByte(reader, pc, offset + 1);
        return low | (high << 8);
    }

    private static int read32(InstructionReader reader, int pc, int offset) {
        int b0 = readByte(reader, pc, offset);
        int b1 = readByte(reader, pc, offset + 1);
        int b2 = readByte(reader, pc, offset + 2);
        int b3 = readByte(reader, pc, offset + 3);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readByte(InstructionReader reader, int pc, int offset) {
        long address = Integer.toUnsignedLong(pc) + offset;
        if (address > ADDRESS_SPACE_END) {
            throw MachineFault.instructionFault(pc,
                    "instruction fetch crosses the 32-bit address space");
        }
        return reader.readByte((int) address) & 0xFF;
    }

    private static MachineFault illegal(int pc, String detail) {
        return MachineFault.illegalInstruction(pc, detail);
    }
}
