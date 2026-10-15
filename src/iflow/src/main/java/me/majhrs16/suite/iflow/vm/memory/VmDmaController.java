package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.isa.VmException;

import java.util.Objects;

/**
 * Direct Memory Access Controller for VM <-> Host memory transfers.
 * <p>
 * Encapsulates all memory transfer operations between the host and the VM's
 * flat 64KB address space, enforcing SoftMMU page permissions (R/W/X) and
 * 4KB page boundaries.
 * </p>
 * <p>
 * This component centralizes:
 * <ul>
 *   <li>Bounds checking against the 64KB address space</li>
 *   <li>SoftMMU page permission validation (R/W/X per 4KB page)</li>
 *   <li>Cross-page transfer handling (slow path vs fast path)</li>
 *   <li>Atomic transfer operations (all-or-nothing on failure)</li>
 * </ul>
 * </p>
 */
public final class VmDmaController {

    private final SoftMMU softMMU;

    public VmDmaController(SoftMMU softMMU) {
        this.softMMU = Objects.requireNonNull(softMMU, "SoftMMU cannot be null");
    }

    // ===== Read Operations =====

    /**
     * Reads a single byte from VM virtual address.
     * @param vaddr Virtual address (0x0000 - 0xFFFF)
     * @return Byte value (0-255)
     * @throws VmException if address unmapped, no read permission, or out of bounds
     */
    public int readByte(int vaddr) {
        return softMMU.readByte(vaddr);
    }

    /**
     * Reads a 16-bit word (little-endian) from VM virtual address.
     * @param vaddr Virtual address (0x0000 - 0xFFFE)
     * @return Word value (0-65535)
     * @throws VmException if address unmapped, no read permission, or out of bounds
     */
    public int readWord(int vaddr) {
        return softMMU.readWord(vaddr);
    }

    /**
     * Reads a block of bytes from VM virtual address into destination array.
     * Handles cross-page transfers via slow path.
     * @param vaddr Source virtual address
     * @param dest Destination byte array
     * @param destOffset Offset in destination array
     * @param length Number of bytes to read
     * @throws VmException if range unmapped, no read permission, or out of bounds
     */
    public void readBlock(int vaddr, byte[] dest, int destOffset, int length) {
        softMMU.readBlock(vaddr, dest, destOffset, length);
    }

    /**
     * Reads a region of VM memory as a new byte array.
     * @param vaddr Source virtual address
     * @param length Number of bytes to read
     * @return New byte array containing the data
     * @throws VmException if range unmapped, no read permission, or out of bounds
     */
    public byte[] getRegion(int vaddr, int length) {
        return softMMU.getRegion(vaddr, length);
    }

    // ===== Write Operations =====

    /**
     * Writes a single byte to VM virtual address.
     * @param vaddr Virtual address (0x0000 - 0xFFFF)
     * @param value Byte value (0-255)
     * @throws VmException if address unmapped, no write permission, or out of bounds
     */
    public void writeByte(int vaddr, int value) {
        softMMU.writeByte(vaddr, value);
    }

    /**
     * Writes a 16-bit word (little-endian) to VM virtual address.
     * Handles cross-page writes atomically.
     * @param vaddr Virtual address (0x0000 - 0xFFFE)
     * @param value Word value (0-65535)
     * @throws VmException if address unmapped, no write permission, or out of bounds
     */
    public void writeWord(int vaddr, int value) {
        softMMU.writeWord(vaddr, value);
    }

    /**
     * Writes a block of bytes from source array to VM virtual address.
     * Handles cross-page writes (slow path) and validates all pages have write permission.
     * @param vaddr Destination virtual address
     * @param src Source byte array
     * @param srcOffset Offset in source array
     * @param length Number of bytes to write
     * @throws VmException if range unmapped, no write permission, or out of bounds
     */
    public void writeBlock(int vaddr, byte[] src, int srcOffset, int length) {
        softMMU.writeBlock(vaddr, src, srcOffset, length);
    }

    // ===== Block Transfer Operations =====

    /**
     * Copies a block of memory within VM address space (non-overlapping).
     * Validates both source and destination ranges have appropriate permissions.
     * @param srcVaddr Source virtual address
     * @param dstVaddr Destination virtual address
     * @param length Number of bytes to copy
     * @throws VmException if ranges invalid, permissions missing, or out of bounds
     */
    public void copyBlock(int srcVaddr, int dstVaddr, int length) {
        if (length < 0) {
            throw new VmException(VmException.Code.INVALID_OPERAND, "Negative length: " + length, 0);
        }
        if (length == 0) return;

        // Validate source range (read permission)
        validateRange(srcVaddr, length, PageEntry.PERM_READ, "source");

        // Validate destination range (write permission)
        validateRange(dstVaddr, length, PageEntry.PERM_WRITE, "destination");

        // Use SoftMMU block copy (handles cross-page)
        byte[] buffer = new byte[length];
        softMMU.readBlock(srcVaddr, buffer, 0, length);
        softMMU.writeBlock(dstVaddr, buffer, 0, length);
    }

    /**
     * Moves a block of memory within VM address space (allows overlapping).
     * Uses temporary buffer to ensure memmove semantics.
     * @param srcVaddr Source virtual address
     * @param dstVaddr Destination virtual address
     * @param length Number of bytes to move
     * @throws VmException if ranges invalid, permissions missing, or out of bounds
     */
    public void moveBlock(int srcVaddr, int dstVaddr, int length) {
        if (length < 0) {
            throw new VmException(VmException.Code.INVALID_OPERAND, "Negative length: " + length, 0);
        }
        if (length == 0) return;

        // Validate source range (read permission)
        validateRange(srcVaddr, length, PageEntry.PERM_READ, "source");

        // Validate destination range (write permission)
        validateRange(dstVaddr, length, PageEntry.PERM_WRITE, "destination");

        // Use temporary buffer for overlapping moves (memmove semantics)
        byte[] buffer = new byte[length];
        softMMU.readBlock(srcVaddr, buffer, 0, length);
        softMMU.writeBlock(dstVaddr, buffer, 0, length);
    }

    // ===== Validation Helpers =====

    /**
     * Validates a virtual address range has the required permission on all pages.
     * @param vaddr Start virtual address
     * @param length Number of bytes
     * @param requiredPerm Required permission (PageEntry.PERM_READ/WRITE/EXECUTE)
     * @param rangeName Name for error messages ("source", "destination", etc.)
     * @throws VmException if validation fails
     */
    public void validateRange(int vaddr, int length, int requiredPerm, String rangeName) {
        if (length < 0) {
            throw new VmException(VmException.Code.INVALID_OPERAND, 
                rangeName + " length negative: " + length, 0);
        }
        if (vaddr < 0 || vaddr >= SoftMMU.ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                rangeName + " address out of bounds: 0x" + Integer.toHexString(vaddr & 0xFFFF), 0);
        }
        int endAddr = vaddr + length;
        if (endAddr > SoftMMU.ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                rangeName + " range exceeds address space: 0x" + Integer.toHexString(vaddr & 0xFFFF) + "+" + length, 0);
        }

        // Check each page in range
        int startPage = vaddr >> PageTable.PAGE_SHIFT;
        int endPage = (endAddr - 1) >> PageTable.PAGE_SHIFT;
        
        for (int page = startPage; page <= endPage; page++) {
            PageEntry entry = softMMU.getPageTable().getEntryByIndex(page);
            if (!entry.isValid()) {
                throw new VmException(VmException.Code.MEMORY_FAULT,
                    rangeName + " page not mapped: page " + page, 0);
            }
            if (!entry.hasPermission(requiredPerm)) {
                throw new VmException(VmException.Code.PRIVILEGE_FAULT,
                    rangeName + " page " + page + " missing permission: " + permToString(requiredPerm), 0);
            }
        }
    }

    private static String permToString(int perm) {
        StringBuilder sb = new StringBuilder();
        if ((perm & PageEntry.PERM_READ) != 0) sb.append('R');
        if ((perm & PageEntry.PERM_WRITE) != 0) sb.append('W');
        if ((perm & PageEntry.PERM_EXECUTE) != 0) sb.append('X');
        return sb.toString();
    }

    // ===== Utility =====

    /**
     * Gets the underlying SoftMMU for advanced operations.
     */
    public SoftMMU getSoftMMU() {
        return softMMU;
    }

    @Override
    public String toString() {
        return "VmDmaController[softMMU=" + softMMU + "]";
    }
}