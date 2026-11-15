package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.Objects;

/**
 * Architectural CSR bank of the v3 machine.
 *
 * <p>Writes enforce the alignment and reserved-bit rules of ADR-037. Privilege
 * is checked by the CPU before calling {@link #write(Csr, int, int)}.</p>
 */
public final class CsrFile {

    public static final int NO_IDENTITY = 0xFFFF_FFFF;

    private Status status = Status.RESET;
    private int epc;
    private int cause;
    private int badvaddr;
    private int tvec;
    private int ptbr;
    private int scratch;
    private int pident = NO_IDENTITY;
    private int mident = NO_IDENTITY;
    private Flags flags = Flags.CLEAR;

    public void reset() {
        status = Status.RESET;
        epc = 0;
        cause = 0;
        badvaddr = 0;
        tvec = 0;
        ptbr = 0;
        scratch = 0;
        pident = NO_IDENTITY;
        mident = NO_IDENTITY;
        flags = Flags.CLEAR;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public Flags flags() {
        return flags;
    }

    public void setFlags(Flags flags) {
        this.flags = Objects.requireNonNull(flags, "flags");
    }

    public int epc() {
        return epc;
    }

    public int cause() {
        return cause;
    }

    public int badvaddr() {
        return badvaddr;
    }

    public int tvec() {
        return tvec;
    }

    public int ptbr() {
        return ptbr;
    }

    public int scratch() {
        return scratch;
    }

    public int pident() {
        return pident;
    }

    public int mident() {
        return mident;
    }

    public int read(Csr csr) {
        Objects.requireNonNull(csr, "csr");
        return switch (csr) {
            case STATUS -> status.bits();
            case EPC -> epc;
            case CAUSE -> cause;
            case BADVADDR -> badvaddr;
            case TVEC -> tvec;
            case PTBR -> ptbr;
            case SCRATCH -> scratch;
            case PIDENT -> pident;
            case MIDENT -> mident;
            case FLAGS -> flags.bits();
        };
    }

    public void write(Csr csr, int value, int pc) {
        Objects.requireNonNull(csr, "csr");
        switch (csr) {
            case STATUS -> {
                if (!Status.isValidBits(value)) {
                    throw MachineFault.illegalInstruction(pc, "STATUS has reserved bits set");
                }
                status = Status.fromBits(value);
            }
            case EPC -> epc = value;
            case CAUSE -> cause = value;
            case BADVADDR -> badvaddr = value;
            case TVEC -> {
                if ((value & 0xFF) != 0) {
                    throw MachineFault.illegalInstruction(pc, "TVEC must be 256-byte aligned");
                }
                tvec = value;
            }
            case PTBR -> {
                if ((value & 0xFFF) != 0) {
                    throw MachineFault.illegalInstruction(pc, "PTBR must be 4 KiB aligned");
                }
                ptbr = value;
            }
            case SCRATCH -> scratch = value;
            case PIDENT -> {
                if (value != NO_IDENTITY && (value < 0 || value > 63)) {
                    throw MachineFault.illegalInstruction(pc,
                            "PIDENT must be 0..63 or 0xFFFFFFFF");
                }
                pident = value;
            }
            case MIDENT -> mident = value;
            case FLAGS -> flags = Flags.fromBits(value);
        }
    }

    public void enterTrap(int epc, int cause, Integer badAddress) {
        this.status = status.enterTrap();
        this.epc = epc;
        this.cause = cause;
        this.badvaddr = badAddress == null ? 0 : badAddress;
    }
}
