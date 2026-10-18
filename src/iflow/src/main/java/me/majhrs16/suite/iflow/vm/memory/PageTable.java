package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.isa.VmException;

import java.util.Arrays;

/**
 * Page Table for 16-bit address space with 4KB pages.
 * <p>
 * 64KB address space / 4KB pages = 16 pages (indices 0-15).
 * Each entry maps a virtual page to a physical frame with permissions.
 * </p>
 */
public final class PageTable {

    public static final int PAGE_SIZE = PageEntry.PAGE_SIZE;      // 4KB
    public static final int PAGE_SHIFT = PageEntry.PAGE_SHIFT;    // 12
    public static final int PAGE_MASK = PageEntry.PAGE_MASK;      // 0xFFFFF000
    public static final int OFFSET_MASK = PageEntry.OFFSET_MASK;  // 0x0FFF
    public static final int NUM_PAGES = 16;                       // 64KB / 4KB = 16
    public static final int ADDRESS_SPACE_SIZE = NUM_PAGES * PAGE_SIZE; // 64KB

    private final PageEntry[] entries = new PageEntry[NUM_PAGES];

    public PageTable() {
        // Initialize all entries as invalid
        Arrays.fill(entries, PageEntry.invalid());
    }

    /**
     * Maps a virtual page to a physical frame with permissions.
     */
    public void mapPage(int virtualPage, int frameBase, int permissions) {
        if (virtualPage < 0 || virtualPage >= NUM_PAGES) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Invalid page index: " + virtualPage);
        }
        if ((frameBase & ~PageEntry.PAGE_MASK) != 0) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Frame base not page-aligned: 0x" + Integer.toHexString(frameBase));
        }
        entries[virtualPage] = PageEntry.mapped(frameBase, permissions);
    }

    /** Removes a page mapping. */
    public void unmapPage(int virtualPage) {
        if (virtualPage >= 0 && virtualPage < NUM_PAGES) {
            entries[virtualPage] = PageEntry.invalid();
        }
    }

    /**
     * Translates a virtual address to physical address, checking permissions.
     * @param virtualAddr 16-bit virtual address
     * @param requiredPerm Required permission (PageEntry.PERM_READ/WRITE/EXECUTE)
     * @return Physical address (frameBase + offset)
     * @throws VmException if page not mapped, permissions insufficient, or address invalid
     */
    public int translate(int virtualAddr, int requiredPerm) {
        if (virtualAddr < 0 || virtualAddr >= ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Address out of bounds: 0x" + Integer.toHexString(virtualAddr & 0xFFFF));
        }

        int pageIndex = virtualAddr >> PAGE_SHIFT;        // virtualAddr / 4096
        int offset = virtualAddr & OFFSET_MASK;           // virtualAddr % 4096

        PageEntry entry = entries[pageIndex];
        if (!entry.isValid()) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Page not mapped: 0x" + Integer.toHexString(virtualAddr & 0xFFFF));
        }
        if (!entry.hasPermission(requiredPerm)) {
            throw new VmException(VmException.Code.PRIVILEGE_FAULT,
                "Permission denied for 0x" + Integer.toHexString(virtualAddr & 0xFFFF) +
                " (need " + permToString(requiredPerm) + ")");
        }

        return entry.getFrameBase() + offset;
    }

    /** Translates for read access. */
    public int translateRead(int virtualAddr) {
        return translate(virtualAddr, PageEntry.PERM_READ);
    }

    /** Translates for write access. */
    public int translateWrite(int virtualAddr) {
        return translate(virtualAddr, PageEntry.PERM_WRITE);
    }

    /** Translates for execute/fetch access. */
    public int translateExecute(int virtualAddr) {
        return translate(virtualAddr, PageEntry.PERM_EXECUTE);
    }

    /** Gets the page entry for a virtual address (for inspection). */
    public PageEntry getEntry(int virtualAddr) {
        int pageIndex = virtualAddr >> PAGE_SHIFT;
        return entries[pageIndex];
    }

    /** Gets the page entry by page index. */
    public PageEntry getEntryByIndex(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= NUM_PAGES) return PageEntry.invalid();
        return entries[pageIndex];
    }

    /** Copies this page table (for context cloning). */
    public PageTable copy() {
        PageTable copy = new PageTable();
        System.arraycopy(this.entries, 0, copy.entries, 0, NUM_PAGES);
        return copy;
    }

    /** Resets all mappings to invalid. */
    public void clear() {
        Arrays.fill(entries, PageEntry.invalid());
    }

    /** Returns number of valid mappings. */
    public int validCount() {
        int count = 0;
        for (PageEntry e : entries) if (e.isValid()) count++;
        return count;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("PageTable[\n");
        for (int i = 0; i < NUM_PAGES; i++) {
            PageEntry e = entries[i];
            if (e.isValid()) {
                sb.append(String.format("  [%d] 0x%04X -> 0x%04X %s\n",
                    i, i << 12, e.getFrameBase(), e));
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String permToString(int perm) {
        StringBuilder sb = new StringBuilder();
        if ((perm & PageEntry.PERM_READ) != 0) sb.append('R');
        if ((perm & PageEntry.PERM_WRITE) != 0) sb.append('W');
        if ((perm & PageEntry.PERM_EXECUTE) != 0) sb.append('X');
        return sb.toString();
    }
}