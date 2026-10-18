package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.isa.VmException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
    private final byte[] physicalMemory;  // Flat physical memory backing store

    public SoftMMU() {
        this.physicalMemory = new byte[PageTable.ADDRESS_SPACE_SIZE];
        this.pageTable = new PageTable();
    }

    /** Creates an MMU with a copy of another MMU's state (for context cloning). */
    public SoftMMU(SoftMMU other) {
        this.physicalMemory = other.physicalMemory.clone();
        this.pageTable = other.pageTable.copy();
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

    /** Removes a page mapping. */
    public void unmapPage(int virtualPage) {
        pageTable.unmapPage(virtualPage);
    }

    // ===== Memory Access (with translation + permission checks) =====

    /** Reads a byte from virtual address. */
    public int readByte(int virtualAddr) {
        int physAddr = pageTable.translateRead(virtualAddr);
        return physicalMemory[physAddr] & 0xFF;
    }

    /** Reads a 16-bit word (little-endian) from virtual address. */
    public int readWord(int virtualAddr) {
        int physAddr = pageTable.translateRead(virtualAddr);
        // Check if word crosses page boundary
        if ((virtualAddr & PageTable.OFFSET_MASK) == PageTable.OFFSET_MASK) {
            // Crosses page boundary - read byte by byte
            int low = readByte(virtualAddr);
            int high = readByte(virtualAddr + 1);
            return low | (high << 8);
        } else {
            return (physicalMemory[physAddr] & 0xFF) |
                   ((physicalMemory[physAddr + 1] & 0xFF) << 8);
        }
    }

    /** Reads a block of bytes from virtual address. */
    public void readBlock(int virtualAddr, byte[] dest, int destOffset, int length) {
        // Check full range first
        int endAddr = virtualAddr + length;
        if (endAddr > PageTable.ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Read beyond address space: 0x" + Integer.toHexString(virtualAddr) + "+" + length);
        }
        // Fast path: if contiguous in physical memory, single copy
        int physAddr = pageTable.translateRead(virtualAddr);
        if (isContiguous(virtualAddr, length)) {
            System.arraycopy(physicalMemory, physAddr, dest, destOffset, length);
        } else {
            // Non-contiguous: byte-by-byte (slow path)
            for (int i = 0; i < length; i++) {
                dest[destOffset + i] = (byte) readByte(virtualAddr + i);
            }
        }
    }

    /** Writes a byte to virtual address. */
    public void writeByte(int virtualAddr, int value) {
        int physAddr = pageTable.translateWrite(virtualAddr);
        physicalMemory[physAddr] = (byte) (value & 0xFF);
    }

    /** Writes a 16-bit word (little-endian) to virtual address. */
    public void writeWord(int virtualAddr, int value) {
        int physAddr = pageTable.translateWrite(virtualAddr);
        // Check if word crosses page boundary
        if ((virtualAddr & PageTable.OFFSET_MASK) == PageTable.OFFSET_MASK) {
            // Crosses page boundary - write byte by byte
            writeByte(virtualAddr, value & 0xFF);
            writeByte(virtualAddr + 1, (value >> 8) & 0xFF);
        } else {
            physicalMemory[physAddr] = (byte) (value & 0xFF);
            physicalMemory[physAddr + 1] = (byte) ((value >> 8) & 0xFF);
        }
    }

    /** Writes a block of bytes to virtual address. */
    public void writeBlock(int virtualAddr, byte[] src, int srcOffset, int length) {
        int endAddr = virtualAddr + length;
        if (endAddr > PageTable.ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Write beyond address space: 0x" + Integer.toHexString(virtualAddr) + "+" + length);
        }
        
        // Pre-validate all pages for write permission before any modification
        validateWriteRange(virtualAddr, length);
        
        int physAddr = pageTable.translateWrite(virtualAddr);
        if (isContiguous(virtualAddr, length)) {
            System.arraycopy(src, srcOffset, physicalMemory, physAddr, length);
        } else {
            for (int i = 0; i < length; i++) {
                writeByte(virtualAddr + i, src[srcOffset + i] & 0xFF);
            }
        }
    }

    /** Validates that the entire virtual address range is mapped and writable. */
    private void validateWriteRange(int virtualAddr, int length) {
        int endAddr = virtualAddr + length;
        int startPage = virtualAddr >> PageTable.PAGE_SHIFT;
        int endPage = (endAddr - 1) >> PageTable.PAGE_SHIFT;
        
        for (int page = startPage; page <= endPage; page++) {
            int pageStart = Math.max(virtualAddr, page << PageTable.PAGE_SHIFT);
            int pageEnd = Math.min(endAddr, (page + 1) << PageTable.PAGE_SHIFT);
            int pageOffset = pageStart - (page << PageTable.PAGE_SHIFT);
            int pageLength = pageEnd - pageStart;
            
            // translateWrite will throw if page not mapped or not writable
            pageTable.translateWrite(pageStart);
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
        return physicalMemory[pageTable.translateExecute(ip)] & 0xFF;
    }

    /**
     * Fetches an instruction word (little-endian) at IP. Each byte is translated
     * with EXECUTE, so a word spanning two pages validates both of them.
     */
    public int fetchWord(int ip) {
        int low = physicalMemory[pageTable.translateExecute(ip)] & 0xFF;
        int high = physicalMemory[pageTable.translateExecute(ip + 1)] & 0xFF;
        return low | (high << 8);
    }

    // ===== Memory Inspection =====

    /** Gets a copy of the entire physical memory. */
    public byte[] getMemorySnapshot() {
        return physicalMemory.clone();
    }

    /** Gets a copy of a memory region. */
    public byte[] getRegion(int virtualAddr, int length) {
        byte[] result = new byte[length];
        readBlock(virtualAddr, result, 0, length);
        return result;
    }

    @Override
    public String toString() {
        return "SoftMMU[memory=" + physicalMemory.length + "B, pages=" + pageTable.validCount() + "/16]";
    }
}
