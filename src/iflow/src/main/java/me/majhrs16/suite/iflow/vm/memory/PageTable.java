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

    private PageEntry[] entries = new PageEntry[NUM_PAGES];
    /**
     * True when this table shares its entries with a parent ({@link #fork()}); the
     * entries are deep-copied lazily on the first mutation (copy-on-write), so a
     * forked execution that never remaps pays nothing.
     */
    private boolean shared;

    public PageTable() {
        // Initialize all entries as invalid
        Arrays.fill(entries, PageEntry.invalid());
    }

    private PageTable(PageEntry[] sharedEntries) {
        this.entries = sharedEntries;
        this.shared = true;
    }

    /**
     * Forks a copy-on-write view sharing this table's entries. The child gets its
     * own entries only if it mutates them ({@code mapPage}/{@code protect}/...).
     */
    public PageTable fork() {
        return new PageTable(this.entries);
    }

    /** Deep-copies the entries before a mutation so a shared parent is untouched. */
    private void ensurePrivate() {
        if (!shared) {
            return;
        }
        PageEntry[] copy = new PageEntry[NUM_PAGES];
        for (int i = 0; i < NUM_PAGES; i++) {
            copy[i] = entries[i].copy();
        }
        this.entries = copy;
        this.shared = false;
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
        ensurePrivate();
        entries[virtualPage] = PageEntry.mapped(frameBase, permissions);
    }

    /**
     * Maps a virtual page as kernel-owned: the program domain may read/execute it
     * if the permission bits allow, but only the supervisor may write it.
     */
    public void mapPageKernel(int virtualPage, int frameBase, int permissions) {
        if (virtualPage < 0 || virtualPage >= NUM_PAGES) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Invalid page index: " + virtualPage);
        }
        if ((frameBase & ~PageEntry.PAGE_MASK) != 0) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Frame base not page-aligned: 0x" + Integer.toHexString(frameBase));
        }
        ensurePrivate();
        entries[virtualPage] = PageEntry.mappedKernel(frameBase, permissions);
    }

    /**
     * Changes the protection bits of an already-mapped page (mprotect), without
     * remapping it. Used by the boot loader to drop the temporary write bit.
     *
     * @throws VmException if the page is not mapped or the index is invalid
     */
    public void protect(int virtualPage, int permissions) {
        if (virtualPage < 0 || virtualPage >= NUM_PAGES) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Invalid page index: " + virtualPage);
        }
        if (!entries[virtualPage].isValid()) {
            throw new VmException(VmException.Code.MEMORY_FAULT,
                "Page not mapped: " + virtualPage);
        }
        ensurePrivate();
        entries[virtualPage].setPermissions(permissions);
    }

    /** Removes a page mapping. */
    public void unmapPage(int virtualPage) {
        if (virtualPage >= 0 && virtualPage < NUM_PAGES) {
            ensurePrivate();
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
        return translate(virtualAddr, requiredPerm, Privilege.PROGRAM);
    }

    /**
     * Translates a virtual address to physical address, checking permissions and
     * page ownership for the given master privilege.
     * <p>
     * A kernel-owned page is readable/executable by the program domain but only
     * writable by the supervisor. A program that holds the WRITE bit on a kernel
     * page — e.g. transiently during boot, or via a mapping bug — still fails
     * with {@code PRIVILEGE_FAULT}: the owner of the page decides, not the bit.
     * </p>
     *
     * @param privilege privilege level of the bus master issuing the access
     * @throws VmException if page not mapped, permissions insufficient, or the
     *         master is not allowed to write a kernel-owned page
     */
    public int translate(int virtualAddr, int requiredPerm, Privilege privilege) {
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
        if ((requiredPerm & PageEntry.PERM_WRITE) != 0 && !entry.isUser()
                && !privilege.isSupervisor()) {
            throw new VmException(VmException.Code.PRIVILEGE_FAULT,
                "Kernel page write denied for 0x" + Integer.toHexString(virtualAddr & 0xFFFF) +
                " (supervisor only)");
        }

        return entry.getFrameBase() + offset;
    }

    /** Translates for read access. */
    public int translateRead(int virtualAddr) {
        return translate(virtualAddr, PageEntry.PERM_READ);
    }

    /** Translates for write access from the program domain. */
    public int translateWrite(int virtualAddr) {
        return translate(virtualAddr, PageEntry.PERM_WRITE, Privilege.PROGRAM);
    }

    /** Translates for write access from the given privilege level. */
    public int translateWrite(int virtualAddr, Privilege privilege) {
        return translate(virtualAddr, PageEntry.PERM_WRITE, privilege);
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

    /** Deep copy of this page table (independent, never shared). Entries are
     * copied too, since their protection bits are mutable (mprotect). */
    public PageTable copy() {
        PageTable copy = new PageTable();
        for (int i = 0; i < NUM_PAGES; i++) {
            copy.entries[i] = this.entries[i].copy();
        }
        return copy;
    }

    /** Resets all mappings to invalid. */
    public void clear() {
        ensurePrivate();
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