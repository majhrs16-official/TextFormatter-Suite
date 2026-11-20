package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.Objects;

/**
 * Decoded v3 instruction.
 *
 * <p>The fields mirror the architectural boxes: {@code rd} is the data
 * register, {@code rs} is the second register or the index when
 * {@link #indexed()} is true, and {@code base} is only meaningful for memory
 * forms. The immediate field holds the immediate value for form 01 and the
 * displacement for forms 10 and 11.</p>
 */
public record Instruction(
        int pc,
        Opcode opcode,
        InstructionForm form,
        int rd,
        int rs,
        int base,
        boolean indexed,
        MemorySize size,
        int immediate
) {

    public Instruction {
        Objects.requireNonNull(opcode, "opcode");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(size, "size");
        if (!Register.isValid(rd) || !Register.isValid(rs) || !Register.isValid(base)) {
            throw new IllegalArgumentException("Register out of range");
        }
        if (!opcode.supports(form)) {
            throw new IllegalArgumentException(opcode.mnemonic() + " does not support form " + form);
        }
        if (!form.isMemory() && (base != 0 || indexed)) {
            throw new IllegalArgumentException("base/idx must be zero outside memory forms");
        }
        if (form.isMemory() && !indexed && rs != 0 && !opcode.usesRsAsOperand()) {
            throw new IllegalArgumentException("rs must be zero when idx=0 in memory forms");
        }
        if (opcode.usesRsAsOperand() && indexed) {
            throw new IllegalArgumentException(
                    opcode.mnemonic() + " does not accept an index register");
        }
    }

    public int length() {
        return form.length();
    }

    public boolean load() {
        return form == InstructionForm.LOAD;
    }

    public boolean store() {
        return form == InstructionForm.STORE;
    }

    public int memorySizeBytes() {
        return size.bytes();
    }
}
