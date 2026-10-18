package me.majhrs16.suite.iflow.vm.irq;

import me.majhrs16.suite.iflow.vm.memory.VmDevice;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;

/**
 * Programmable interrupt controller (D33): a bus slave with a small MMIO
 * register bank that latches interrupt lines and exposes their state to the
 * program and the runtime.
 * <p>
 * The controller is deliberately simple (16 lines):
 * </p>
 * <ul>
 *   <li>{@code PENDING} (ro): bitmap of raised lines still awaiting service.</li>
 *   <li>{@code ENABLE} (rw): mask of lines allowed to reach the CPU. Lines are
 *       masked by default, so raising one has no effect until a program or the
 *       BIOS enables it.</li>
 *   <li>{@code RAISE} (wo): writing a line number raises that line (software
 *       interrupt; the BIOS uses it and tests rely on it).</li>
 *   <li>{@code ACK} (wo): writing a line number completes its service, clearing
 *       the in-service bit so the line can fire again.</li>
 *   <li>{@code IN_SERVICE} (ro): bitmap of lines currently being handled (no
 *       re-entrancy for the same line).</li>
 *   <li>{@code CURRENT} (ro): the line most recently dispatched, or {@code 0xFFFF}.</li>
 * </ul>
 * <p>
 * Like every device, the registers are decoded by the interconnect before the
 * MMU, so they are not part of the program's RAM.
 * </p>
 */
public final class VmPic implements VmDevice {

    public static final int REG_PENDING = 0x00;
    public static final int REG_ENABLE = 0x02;
    public static final int REG_RAISE = 0x04;
    public static final int REG_ACK = 0x06;
    public static final int REG_IN_SERVICE = 0x08;
    public static final int REG_CURRENT = 0x0A;
    /** Number of register bytes (the bank size). */
    public static final int REG_COUNT = 0x0C;

    /** Standard I/O interrupt sources. */
    public static final int IRQ_MESSAGE = 0; // an incoming message is ready in the input buffer
    public static final int IRQ_HOST = 1;    // a HOST service completed with an error status

    /** Number of interrupt lines. */
    public static final int IRQ_LINES = 16;

    private final int base;
    private int pending;
    private int enabled;
    private int inService;
    private int current = -1;

    /** Creates the standard PIC bank (see {@link VmMemoryMap#DEVICE_PIC_BASE}). */
    public VmPic() {
        this(VmMemoryMap.DEVICE_PIC_BASE, VmMemoryMap.DEVICE_PIC_SIZE);
    }

    /**
     * Creates a PIC bank of at least {@link #REG_COUNT} bytes at {@code base}.
     *
     * @throws IllegalArgumentException if the bank cannot hold the registers
     */
    public VmPic(int base, int size) {
        if (size < REG_COUNT) {
            throw new IllegalArgumentException("PIC bank too small: " + size + " < " + REG_COUNT);
        }
        this.base = base;
    }

    public int base() {
        return base;
    }

    // ===== Controller state (used by the runtime and tests) =====

    /** Raises a line (idempotent while it stays pending). */
    public void raise(int irq) {
        if (irq >= 0 && irq < IRQ_LINES) {
            pending |= (1 << irq);
        }
    }

    /** Enables/disables a line. */
    public void setEnabled(int irq, boolean on) {
        if (irq < 0 || irq >= IRQ_LINES) {
            return;
        }
        if (on) {
            enabled |= (1 << irq);
        } else {
            enabled &= ~(1 << irq);
        }
    }

    public boolean isEnabled(int irq) {
        return irq >= 0 && irq < IRQ_LINES && (enabled & (1 << irq)) != 0;
    }

    public int pendingMask() {
        return pending;
    }

    public int enabledMask() {
        return enabled;
    }

    public int inServiceMask() {
        return inService;
    }

    /** Number of the line currently dispatched, or -1. */
    public int current() {
        return current;
    }

    /**
     * The lowest-numbered line that is pending, enabled and not in service, or
     * {@code -1} when no line can be dispatched.
     */
    public int nextPending() {
        int ready = pending & enabled & ~inService;
        return ready == 0 ? -1 : Integer.numberOfTrailingZeros(ready);
    }

    /** Marks a line as being serviced: clears it from pending, sets in-service. */
    public void beginService(int irq) {
        if (irq < 0 || irq >= IRQ_LINES) {
            return;
        }
        pending &= ~(1 << irq);
        inService |= (1 << irq);
        current = irq;
    }

    /** Completes service of a line (ACK): clears its in-service bit. */
    public void ack(int irq) {
        if (irq < 0 || irq >= IRQ_LINES) {
            return;
        }
        inService &= ~(1 << irq);
        if (current == irq) {
            current = -1;
        }
    }

    /** Drops a line entirely (pending + in-service); used when there is no handler. */
    public void complete(int irq) {
        if (irq < 0 || irq >= IRQ_LINES) {
            return;
        }
        pending &= ~(1 << irq);
        inService &= ~(1 << irq);
        if (current == irq) {
            current = -1;
        }
    }

    // ===== Bus (MMIO) =====

    @Override
    public boolean decodes(int vaddr) {
        return vaddr >= base && vaddr < base + REG_COUNT;
    }

    @Override
    public int readWord(int vaddr) {
        switch (vaddr - base) {
            case REG_PENDING: return pending;
            case REG_ENABLE: return enabled;
            case REG_IN_SERVICE: return inService;
            case REG_CURRENT: return current < 0 ? 0xFFFF : current;
            default: return 0;
        }
    }

    @Override
    public void writeWord(int vaddr, int value) {
        switch (vaddr - base) {
            case REG_ENABLE: enabled = value & 0xFFFF; break;
            case REG_RAISE: raise(value & 0x0F); break;
            case REG_ACK: ack(value & 0x0F); break;
            default: break; // PENDING / IN_SERVICE / CURRENT are read-only
        }
    }

    @Override
    public int readByte(int vaddr) {
        int off = vaddr - base;
        int word = readWord(base + (off & ~1));
        return (off & 1) != 0 ? (word >> 8) & 0xFF : word & 0xFF;
    }

    @Override
    public void writeByte(int vaddr, int value) {
        int off = vaddr - base;
        int regOff = off & ~1;
        if (regOff == REG_ENABLE) {
            int word = readWord(base + REG_ENABLE);
            word = (off & 1) != 0
                ? (word & 0x00FF) | ((value & 0xFF) << 8)
                : (word & 0xFF00) | (value & 0xFF);
            writeWord(base + REG_ENABLE, word);
        } else {
            // RAISE / ACK are triggered by a write; a low-byte write carries the line.
            writeWord(base + regOff, value & 0xFF);
        }
    }
}
