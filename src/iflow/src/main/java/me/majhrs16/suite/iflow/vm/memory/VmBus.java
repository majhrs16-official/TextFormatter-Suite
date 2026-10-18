package me.majhrs16.suite.iflow.vm.memory;

import java.util.Objects;

/**
 * Logical data bus interconnecting the components of the VM (design.md §4).
 * <p>
 * This is the single memory-transaction path shared by the bus <em>masters</em>: the
 * CPU ({@link me.majhrs16.suite.iflow.vm.execution.VmInterpreter}) and the
 * {@link VmDmaController}. They issue transactions on the bus instead of reaching
 * the memory fabric directly. {@link SoftMMU} is the <em>slave</em>/fabric
 * (virtual&rarr;physical translation, per-page permissions, physical RAM).
 * </p>
 * <p>
 * Each bus carries the {@link Privilege} of its master (design.md §14): the CPU is
 * a {@link Privilege#PROGRAM} master, while the boot loader and the DMA controller
 * are {@link Privilege#SUPERVISOR} masters. The MMU enforces ownership from that,
 * so a program cannot write kernel-owned pages (ROM, constants) even if a mapping
 * bug left them writable.
 * </p>
 * <p>
 * Modeling the interconnection explicitly is what makes the wiring mappable to an
 * HDL and gives a single point to observe or substitute: a test can wire the bus
 * over a different MMU and assert that the masters transact through it. It is
 * deliberately thin — every operation delegates to the {@link SoftMMU} — because
 * the spec warns against abstractions without real utility (design.md §4).
 * </p>
 */
public final class VmBus {

    private final SoftMMU memory;
    private final Privilege privilege;

    /** Creates a program-domain bus (the CPU master). */
    public VmBus(SoftMMU memory) {
        this(memory, Privilege.PROGRAM);
    }

    public VmBus(SoftMMU memory, Privilege privilege) {
        this.memory = Objects.requireNonNull(memory, "SoftMMU cannot be null");
        this.privilege = Objects.requireNonNull(privilege, "Privilege cannot be null");
    }

    /** Creates a supervisor-domain bus (boot loader / DMA master). */
    public static VmBus supervisor(SoftMMU memory) {
        return new VmBus(memory, Privilege.SUPERVISOR);
    }

    /** The memory device behind this bus (configuration/inspection access). */
    public SoftMMU memory() {
        return memory;
    }

    /** The privilege level of this bus master. */
    public Privilege privilege() {
        return privilege;
    }

    /** The page table of the memory device (MMU address decoding). */
    public PageTable pageTable() {
        return memory.getPageTableDirect();
    }

    // ===== Instruction fetch (CPU) =====

    public int fetchByte(int ip) {
        return memory.fetchByte(ip);
    }

    public int fetchWord(int ip) {
        return memory.fetchWord(ip);
    }

    /**
     * Instruction prefetch (I-cache line fill): validates EXECUTE on the page and
     * fills {@code dest} from physical memory. The CPU is the master that requests
     * it; the MMU is the slave.
     */
    public void fillCodeWindow(int virtualPageBase, byte[] dest) {
        memory.fillCodeWindow(virtualPageBase, dest);
    }

    // ===== Data transactions =====

    public int readByte(int vaddr) {
        return memory.readByte(vaddr, privilege);
    }

    public int readWord(int vaddr) {
        return memory.readWord(vaddr, privilege);
    }

    public void writeByte(int vaddr, int value) {
        memory.writeByte(vaddr, value, privilege);
    }

    public void writeWord(int vaddr, int value) {
        memory.writeWord(vaddr, value, privilege);
    }

    public void readBlock(int vaddr, byte[] dest, int destOffset, int length) {
        memory.readBlock(vaddr, dest, destOffset, length, privilege);
    }

    public void writeBlock(int vaddr, byte[] src, int srcOffset, int length) {
        memory.writeBlock(vaddr, src, srcOffset, length, privilege);
    }

    public byte[] getRegion(int vaddr, int length) {
        return memory.getRegion(vaddr, length);
    }

    @Override
    public String toString() {
        return "VmBus[" + privilege + " " + memory + "]";
    }
}
