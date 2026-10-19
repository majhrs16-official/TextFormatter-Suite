package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.isa.VmException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * Software Memory Management Unit (SoftMMU) for 16-bit flat address space with 4KB paging.
 * <p>
 * Provides checked memory access through a PageTable. All memory accesses
 * (read/write/execute) go through this unit which validates permissions and
 * translates virtual to physical addresses.
 * </p>
 */
public final class SoftMMU {

    public static final int PAGE_SIZE = PageTable.PAGE_SIZE;      // 4KB
    public static final int PAGE_SHIFT = PageTable.PAGE_SHIFT;    // 12
    public static final int ADDRESS_SPACE_SIZE = PageTable.ADDRESS_SPACE_SIZE; // 64KB

    private final PageTable pageTable;
    private final PhysicalMemory memory;  // Copy-on-write physical backing store

    /**
     * Peripherals attached to the fabric (bus slaves that decode their own
     * address ranges). The interconnect ({@link VmBus}) asks them before RAM, so
     * a device window shadows the RAM underneath it. A fork starts without
     * peripherals: devices belong to the machine, not to an execution snapshot.
     */
    private VmDevice[] devices = new VmDevice[0];

    public SoftMMU() {
        this.memory = PhysicalMemory.blank();
        this.pageTable = new PageTable();
    }

    /**
     * Forks another MMU's state for a new execution. Both the physical memory and
     * the page table are copy-on-write: the child shares the parent's immutable
     * snapshot/mappings and only copies the frames or entries it actually changes,
     * so forking no longer clones 64KB plus 16 page entries per execution
     * (IFLOW-AUD-029).
     */
    public SoftMMU(SoftMMU other) {
        this.memory = other.memory.fork();
        this.pageTable = other.pageTable.fork();
    }

    /**
     * Publishes the current physical memory as the immutable snapshot shared by
     * every future {@link #SoftMMU(SoftMMU) fork}. Call it once after the boot
     * loader has written the image, before using this MMU as a template.
     */
    public void freeze() {
        memory.freeze();
    }

    // ===== Peripherals (bus slaves) =====

    /** Attaches a peripheral that decodes its own address range (D31). */
    public void attach(VmDevice device) {
        Objects.requireNonNull(device, "device");
        VmDevice[] next = Arrays.copyOf(devices, devices.length + 1);
        next[devices.length] = device;
        devices = next;
    }

    /**
     * The peripheral that owns {@code vaddr}, or {@code null} if the address is
     * plain RAM. Decoding happens in attachment order, so a device can be placed
     * in front of another (not used today: the screen is the only device).
     */
    public VmDevice deviceFor(int vaddr) {
        for (VmDevice d : devices) {
            if (d.decodes(vaddr)) {
                return d;
            }
        }
        return null;
    }

    /** True if any attached peripheral decodes {@code vaddr}. */
    public boolean isDeviceAddress(int vaddr) {
        return deviceFor(vaddr) != null;
    }

    /**
     * True if any byte in {@code [vaddr, vaddr + length)} lands on an attached
     * peripheral (IFLOW-AUD-032). Block transactions use this so a range that
     * <em>contains</em> a device window is split correctly even when neither
     * end touches it.
     */
    public boolean isDeviceRange(int vaddr, int length) {
        if (length <= 0) {
            return false;
        }
        for (VmDevice d : devices) {
            if (d.overlaps(vaddr, length)) {
                return true;
            }
        }
        return false;
    }

    /** The attached peripherals (a copy of the list). */
    public VmDevice[] devices() {
        return devices.clone();
    }

    // ===== Page Table Management =====

    /** Gets the page table (single source of truth for mappings). */
    public PageTable getPageTable() {
        return pageTable;
    }

    /** Maps a virtual page to a physical frame. */
    public void mapPage(int virtualPage, int frameBase, int permissions) {
        pageTable.mapPage(virtualPage, frameBase, permissions);
    }

    /** Maps a virtual page as kernel-owned (program-readable, supervisor-writable). */
    public void mapPageKernel(int virtualPage, int frameBase, int permissions) {
        pageTable.mapPageKernel(virtualPage, frameBase, permissions);
    }

    /** Removes a page mapping. */
    public void unmapPage(int virtualPage) {
        pageTable.unmapPage(virtualPage);
    }

    /** The live page table, for address decoding by a bus master. */
    public PageTable getPageTableDirect() {
        return pageTable;
    }

    /**
     * A snapshot of the page table for read-only inspection. Unlike
     * {@link #getPageTable()}, the returned table is a copy: inspecting it cannot
     * mutate the mappings of this MMU.
     */
    public PageTable getPageTableForInspection() {
        return pageTable.copy();
    }

    /** The live page table, for mapping changes (boot loader / supervisor). */
    public PageTable getPageTableForMutation() {
        return pageTable;
    }

    // ===== Memory Access (with translation + permission checks) =====

    /** Reads a byte from virtual address (program domain). */
    public int readByte(int virtualAddr) {
        return readByte(virtualAddr, Privilege.PROGRAM);
    }

    /** Reads a byte from virtual address as the given master privilege. */
    public int readByte(int virtualAddr, Privilege privilege) {
        int physAddr = pageTable.translateRead(virtualAddr);
        return memory.readByte(physAddr);
    }

    /** Reads a 16-bit word (little-endian) from virtual address. */
    public int readWord(int virtualAddr) {
        return readWord(virtualAddr, Privilege.PROGRAM);
    }

    /** Reads a 16-bit word (little-endian) as the given master privilege. */
    public int readWord(int virtualAddr, Privilege privilege) {
        int physAddr = pageTable.translateRead(virtualAddr);
        // Check if word crosses page boundary
        if ((virtualAddr & PageTable.OFFSET_MASK) == PageTable.OFFSET_MASK) {
            // Crosses page boundary - read byte by byte
            int low = readByte(virtualAddr);
            int high = readByte(virtualAddr + 1);
            return low | (high << 8);
        } else {
            return memory.readByte(physAddr) |
                   (memory.readByte(physAddr + 1) << 8);
        }
    }

    /** Reads a block of bytes from virtual address. */
    public void readBlock(int virtualAddr, byte[] dest, int destOffset, int length) {
        readBlock(virtualAddr, dest, destOffset, length, Privilege.PROGRAM);
    }

    /** Reads a block of bytes from virtual address as the given master privilege. */
    public void readBlock(int virtualAddr, byte[] dest, int destOffset, int length, Privilege privilege) {
        // Check full range first
        int endAddr = virtualAddr + length;
        if (endAddr > PageTable.ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Read beyond address space: 0x" + Integer.toHexString(virtualAddr) + "+" + length);
        }
        // Fast path: if contiguous in physical memory, single copy
        int physAddr = pageTable.translateRead(virtualAddr);
        if (isContiguous(virtualAddr, length)) {
            memory.readBlock(physAddr, dest, destOffset, length);
        } else {
            // Non-contiguous: byte-by-byte (slow path)
            for (int i = 0; i < length; i++) {
                dest[destOffset + i] = (byte) readByte(virtualAddr + i);
            }
        }
    }

    /** Writes a byte to virtual address (program domain). */
    public void writeByte(int virtualAddr, int value) {
        writeByte(virtualAddr, value, Privilege.PROGRAM);
    }

    /** Writes a byte to virtual address as the given master privilege. */
    public void writeByte(int virtualAddr, int value, Privilege privilege) {
        int physAddr = pageTable.translateWrite(virtualAddr, privilege);
        memory.writeByte(physAddr, value);
    }

    /** Writes a 16-bit word (little-endian) to virtual address (program domain). */
    public void writeWord(int virtualAddr, int value) {
        writeWord(virtualAddr, value, Privilege.PROGRAM);
    }

    /** Writes a 16-bit word (little-endian) as the given master privilege. */
    public void writeWord(int virtualAddr, int value, Privilege privilege) {
        int physAddr = pageTable.translateWrite(virtualAddr, privilege);
        // Check if word crosses page boundary
        if ((virtualAddr & PageTable.OFFSET_MASK) == PageTable.OFFSET_MASK) {
            // Crosses page boundary - write byte by byte
            writeByte(virtualAddr, value & 0xFF, privilege);
            writeByte(virtualAddr + 1, (value >> 8) & 0xFF, privilege);
        } else {
            memory.writeByte(physAddr, value & 0xFF);
            memory.writeByte(physAddr + 1, (value >> 8) & 0xFF);
        }
    }

    /** Writes a block of bytes to virtual address (program domain). */
    public void writeBlock(int virtualAddr, byte[] src, int srcOffset, int length) {
        writeBlock(virtualAddr, src, srcOffset, length, Privilege.PROGRAM);
    }

    /**
     * Zeroes a block of virtual memory (program domain). Cheaper than writing a
     * zero buffer: a shared frame that is already zero is left untouched.
     */
    public void clearBlock(int virtualAddr, int length) {
        clearBlock(virtualAddr, length, Privilege.PROGRAM);
    }

    /** Zeroes a block of virtual memory as the given master privilege. */
    public void clearBlock(int virtualAddr, int length, Privilege privilege) {
        int endAddr = virtualAddr + length;
        if (endAddr > PageTable.ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Clear beyond address space: 0x" + Integer.toHexString(virtualAddr) + "+" + length);
        }
        validateWriteRange(virtualAddr, length, privilege);
        int physAddr = pageTable.translateWrite(virtualAddr, privilege);
        if (isContiguous(virtualAddr, length)) {
            memory.clearBlock(physAddr, length);
        } else {
            for (int i = 0; i < length; i++) {
                memory.writeByte(pageTable.translateWrite(virtualAddr + i, privilege), 0);
            }
        }
    }

    /** Writes a block of bytes to virtual address as the given master privilege. */
    public void writeBlock(int virtualAddr, byte[] src, int srcOffset, int length, Privilege privilege) {
        int endAddr = virtualAddr + length;
        if (endAddr > PageTable.ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Write beyond address space: 0x" + Integer.toHexString(virtualAddr) + "+" + length);
        }
        
        // Pre-validate all pages for write permission before any modification
        validateWriteRange(virtualAddr, length, privilege);
        
        int physAddr = pageTable.translateWrite(virtualAddr, privilege);
        if (isContiguous(virtualAddr, length)) {
            memory.writeBlock(physAddr, src, srcOffset, length);
        } else {
            for (int i = 0; i < length; i++) {
                writeByte(virtualAddr + i, src[srcOffset + i] & 0xFF, privilege);
            }
        }
    }

    /** Validates that the entire virtual address range is mapped and writable. */
    private void validateWriteRange(int virtualAddr, int length, Privilege privilege) {
        int endAddr = virtualAddr + length;
        int startPage = virtualAddr >> PageTable.PAGE_SHIFT;
        int endPage = (endAddr - 1) >> PageTable.PAGE_SHIFT;
        
        for (int page = startPage; page <= endPage; page++) {
            int pageStart = Math.max(virtualAddr, page << PageTable.PAGE_SHIFT);
            int pageEnd = Math.min(endAddr, (page + 1) << PageTable.PAGE_SHIFT);
            int pageOffset = pageStart - (page << PageTable.PAGE_SHIFT);
            int pageLength = pageEnd - pageStart;
            
            // translateWrite will throw if page not mapped, not writable, or
            // kernel-owned and the master is not the supervisor
            pageTable.translateWrite(pageStart, privilege);
        }
    }

    /** Checks if a virtual address range maps to contiguous physical memory. */
    private boolean isContiguous(int virtualAddr, int length) {
        if (length <= 1) return true;
        int startPage = virtualAddr >> PageTable.PAGE_SHIFT;
        int endPage = (virtualAddr + length - 1) >> PageTable.PAGE_SHIFT;
        if (startPage != endPage) return false; // Crosses page boundary
        return true;
    }

    // ===== Execution Support =====

    /**
     * Fetches an instruction byte at IP. Requires EXECUTE on the page only
     * (not READ): the previous implementation passed the physical address back
     * through {@code readByte}, which re-translated it as a read.
     */
    public int fetchByte(int ip) {
        return memory.readByte(pageTable.translateExecute(ip));
    }

    /**
     * Fetches an instruction word (little-endian) at IP. Each byte is translated
     * with EXECUTE, so a word spanning two pages validates both of them.
     */
    public int fetchWord(int ip) {
        int low = memory.readByte(pageTable.translateExecute(ip));
        int high = memory.readByte(pageTable.translateExecute(ip + 1));
        return low | (high << 8);
    }

    /**
     * Instruction prefetch (I-cache line fill) for the whole page containing
     * {@code virtualPageBase}: translates it with EXECUTE (validating the page's
     * protection) and copies the 4KB page from physical memory into {@code dest}.
     * The CPU issues this through the bus; the MMU is the slave.
     */
    public void fillCodeWindow(int virtualPageBase, byte[] dest) {
        int physBase = pageTable.translateExecute(virtualPageBase & PageTable.PAGE_MASK);
        int len = Math.min(dest.length, PageTable.PAGE_SIZE);
        memory.readBlock(physBase, dest, 0, len);
    }

    // ===== Memory Inspection =====

    /**
     * A flat snapshot of the physical memory (shared snapshot + private overlay).
     * <p>
     * The store is copy-on-write, so there is no single live backing array to
     * return; this materializes the current view. It is used for inspection and
     * snapshots; virtual accesses must still go through the translating methods.
     * </p>
     */
    public byte[] getPhysicalMemory() {
        return memory.snapshot();
    }

    /** Gets a copy of the entire physical memory. */
    public byte[] getMemorySnapshot() {
        return memory.snapshot();
    }

    /** Gets a copy of a memory region. */
    public byte[] getRegion(int virtualAddr, int length) {
        byte[] result = new byte[length];
        readBlock(virtualAddr, result, 0, length);
        return result;
    }

    @Override
    public String toString() {
        return "SoftMMU[memory=" + memory.size() + "B, pages=" + pageTable.validCount() + "/16]";
    }
}
