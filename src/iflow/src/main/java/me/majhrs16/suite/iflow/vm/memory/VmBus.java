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
 * Modeling the interconnection explicitly is what makes the wiring mappable to an
 * HDL and gives a single point to observe or substitute: a test can wire the bus
 * over a different MMU and assert that the masters transact through it. It is
 * deliberately thin — every operation delegates to the {@link SoftMMU} — because
 * the spec warns against abstractions without real utility (design.md §4).
 * </p>
 */
public final class VmBus {

    private final SoftMMU memory;

    public VmBus(SoftMMU memory) {
        this.memory = Objects.requireNonNull(memory, "SoftMMU cannot be null");
    }

    /** The memory device behind this bus (configuration/inspection access). */
    public SoftMMU memory() {
        return memory;
    }

    /** The page table of the memory device (MMU address decoding). */
    public PageTable pageTable() {
        return memory.getPageTable();
    }

    // ===== Instruction fetch (CPU) =====

    public int fetchByte(int ip) {
        return memory.fetchByte(ip);
    }

    public int fetchWord(int ip) {
        return memory.fetchWord(ip);
    }

    // ===== Data transactions =====

    public int readByte(int vaddr) {
        return memory.readByte(vaddr);
    }

    public int readWord(int vaddr) {
        return memory.readWord(vaddr);
    }

    public void writeByte(int vaddr, int value) {
        memory.writeByte(vaddr, value);
    }

    public void writeWord(int vaddr, int value) {
        memory.writeWord(vaddr, value);
    }

    public void readBlock(int vaddr, byte[] dest, int destOffset, int length) {
        memory.readBlock(vaddr, dest, destOffset, length);
    }

    public void writeBlock(int vaddr, byte[] src, int srcOffset, int length) {
        memory.writeBlock(vaddr, src, srcOffset, length);
    }

    public byte[] getRegion(int vaddr, int length) {
        return memory.getRegion(vaddr, length);
    }

    @Override
    public String toString() {
        return "VmBus[" + memory + "]";
    }
}
