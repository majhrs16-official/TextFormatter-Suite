package me.majhrs16.suite.iflow.vm.memory;

/**
 * Page Table Entry for 4KB page.
 * <p>
 * Each page is 4KB (0x1000 bytes). 64KB address space = 16 pages.
 * Each entry defines the physical frame, permissions, ownership and validity.
 * </p>
 * <p>
 * Permissions are mutable at runtime ({@link #setPermissions}) so the host can
 * change a page's protection (mprotect) without remapping it; ownership
 * ({@link #isUser}) is fixed when the page is mapped and decides whether a
 * program-domain master may write it (see {@link Privilege}).
 * </p>
 */
public final class PageEntry {

    public static final int PAGE_SIZE = 0x1000; // 4KB
    public static final int PAGE_SHIFT = 12;    // 2^12 = 4096
    public static final int PAGE_MASK = 0xFFFFF000; // Mask to get page base address
    public static final int OFFSET_MASK = 0x0FFF;   // Mask to get offset within page

    // Permission bits
    public static final int PERM_READ    = 0x1;
    public static final int PERM_WRITE   = 0x2;
    public static final int PERM_EXECUTE = 0x4;

    private final int frameBase;    // Physical frame base address (page-aligned)
    private int permissions;        // Permission bits (R/W/X); mutable via mprotect
    private final boolean valid;    // Page is mapped
    private final boolean user;     // Program domain may write it (vs kernel-owned)

    private PageEntry(int frameBase, int permissions, boolean valid, boolean user) {
        this.frameBase = frameBase & PAGE_MASK;
        this.permissions = permissions & (PERM_READ | PERM_WRITE | PERM_EXECUTE);
        this.valid = valid;
        this.user = user;
    }

    public static PageEntry invalid() {
        return new PageEntry(0, 0, false, false);
    }

    /** Maps a program-owned page (writable by the program domain). */
    public static PageEntry mapped(int frameBase, int permissions) {
        return new PageEntry(frameBase, permissions, true, true);
    }

    /**
     * Maps a kernel-owned page: the program domain may read/execute it if the
     * permission bits allow, but only the supervisor may write it.
     */
    public static PageEntry mappedKernel(int frameBase, int permissions) {
        return new PageEntry(frameBase, permissions, true, false);
    }

    public int getFrameBase() {
        return frameBase;
    }

    public int getPermissions() {
        return permissions;
    }

    public boolean isValid() {
        return valid;
    }

    /** True if the program domain may write this page (ring 3 / user page). */
    public boolean isUser() {
        return user;
    }

    public boolean canRead() {
        return valid && (permissions & PERM_READ) != 0;
    }

    public boolean canWrite() {
        return valid && (permissions & PERM_WRITE) != 0;
    }

    public boolean canExecute() {
        return valid && (permissions & PERM_EXECUTE) != 0;
    }

    public boolean hasPermission(int perm) {
        return valid && (permissions & perm) == perm;
    }

    /**
     * Changes the protection bits of this entry (mprotect).
     *
     * @param permissions new R/W/X bits; unknown bits are masked out
     */
    public void setPermissions(int permissions) {
        this.permissions = permissions & (PERM_READ | PERM_WRITE | PERM_EXECUTE);
    }

    /** Copies this entry so a copied page table does not share mutable state. */
    public PageEntry copy() {
        return new PageEntry(frameBase, permissions, valid, user);
    }

    @Override
    public String toString() {
        if (!valid) return "PageEntry[INVALID]";
        StringBuilder perms = new StringBuilder();
        if ((permissions & PERM_READ) != 0) perms.append('R');
        if ((permissions & PERM_WRITE) != 0) perms.append('W');
        if ((permissions & PERM_EXECUTE) != 0) perms.append('X');
        return String.format("PageEntry[frame=0x%04X perms=%s user=%s]", frameBase, perms, user);
    }
}
