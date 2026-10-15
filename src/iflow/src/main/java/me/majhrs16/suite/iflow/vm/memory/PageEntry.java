package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.isa.VmException;

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
    private final boolean user;     // User-mode accessible (vs kernel)

    private PageEntry(int frameBase, int permissions, boolean valid, boolean user) {
        this.frameBase = frameBase & PAGE_MASK;
        this.permissions = permissions & (PERM_READ | PERM_WRITE | PERM_EXECUTE);
        this.valid = valid;
        this.user = user;
    }

    public static PageEntry invalid() {
        return new PageEntry(0, 0, false, false);
    }

    public static PageEntry mapped(int frameBase, int permissions) {
        return new PageEntry(frameBase, permissions, true, true);
    }

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

    public void setPermissions(int permissions) {
        // Not used in immutable design; create new entry instead
        throw new UnsupportedOperationException("PageEntry is immutable");
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