package me.majhrs16.suite.iflow.vm.memory;

/**
 * Page Table Entry for 4KB page.
 * <p>
 * Each page is 4KB (0x1000 bytes). 64KB address space = 16 pages.
 * Each entry defines the physical frame, permissions, and validity.
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
    private final int permissions;  // Permission bits (R/W/X)
    private final boolean valid;    // Page is mapped

    private PageEntry(int frameBase, int permissions, boolean valid) {
        this.frameBase = frameBase & PAGE_MASK;
        this.permissions = permissions & (PERM_READ | PERM_WRITE | PERM_EXECUTE);
        this.valid = valid;
    }

    public static PageEntry invalid() {
        return new PageEntry(0, 0, false);
    }

    public static PageEntry mapped(int frameBase, int permissions) {
        return new PageEntry(frameBase, permissions, true);
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

    @Override
    public String toString() {
        if (!valid) return "PageEntry[INVALID]";
        StringBuilder perms = new StringBuilder();
        if ((permissions & PERM_READ) != 0) perms.append('R');
        if ((permissions & PERM_WRITE) != 0) perms.append('W');
        if ((permissions & PERM_EXECUTE) != 0) perms.append('X');
        return String.format("PageEntry[frame=0x%04X perms=%s]", frameBase, perms);
    }
}