package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.HashMap;
import java.util.Map;

/**
 * Control and status registers defined for v3.0.
 */
public enum Csr {
    STATUS(0x100),
    EPC(0x101),
    CAUSE(0x102),
    BADVADDR(0x103),
    TVEC(0x104),
    PTBR(0x105),
    SCRATCH(0x106),
    PIDENT(0x107),
    MIDENT(0x108),
    FLAGS(0x109);

    private static final Map<Integer, Csr> BY_ADDRESS = new HashMap<>();

    static {
        for (Csr csr : values()) {
            BY_ADDRESS.put(csr.address, csr);
        }
    }

    private final int address;

    Csr(int address) {
        this.address = address;
    }

    public int address() {
        return address;
    }

    public static Csr fromAddress(int address) {
        return BY_ADDRESS.get(address);
    }
}
