package me.majhrs16.suite.iflow.vm.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PageEntryTest {

    @Test
    void invalidEntryIsNotValid() {
        PageEntry entry = PageEntry.invalid();
        assertFalse(entry.isValid());
        assertFalse(entry.canRead());
        assertFalse(entry.canWrite());
        assertFalse(entry.canExecute());
        assertEquals(0, entry.getPermissions());
    }

    @Test
    void mappedEntryHasCorrectProperties() {
        PageEntry entry = PageEntry.mapped(0x1000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        assertTrue(entry.isValid());
        assertTrue(entry.canRead());
        assertTrue(entry.canWrite());
        assertFalse(entry.canExecute());
        assertEquals(0x1000, entry.getFrameBase());
        assertEquals(PageEntry.PERM_READ | PageEntry.PERM_WRITE, entry.getPermissions());
    }

    @Test
    void frameBaseIsPageAligned() {
        PageEntry entry = PageEntry.mapped(0x1234, PageEntry.PERM_READ);
        assertEquals(0x1000, entry.getFrameBase());
    }

    @Test
    void hasPermissionChecksCorrectly() {
        PageEntry entry = PageEntry.mapped(0x1000, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        assertTrue(entry.hasPermission(PageEntry.PERM_READ));
        assertTrue(entry.hasPermission(PageEntry.PERM_EXECUTE));
        assertFalse(entry.hasPermission(PageEntry.PERM_WRITE));
        assertFalse(entry.hasPermission(PageEntry.PERM_READ | PageEntry.PERM_WRITE));
    }

    @Test
    void invalidEntryHasNoPermissions() {
        PageEntry entry = PageEntry.invalid();
        assertFalse(entry.hasPermission(PageEntry.PERM_READ));
        assertFalse(entry.hasPermission(PageEntry.PERM_WRITE));
        assertFalse(entry.hasPermission(PageEntry.PERM_EXECUTE));
    }

    @Test
    void permissionsAreMasked() {
        PageEntry entry = PageEntry.mapped(0x1000, 0xFF); // Extra bits
        assertEquals(PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE, entry.getPermissions());
    }

    @Test
    void toStringFormatting() {
        PageEntry valid = PageEntry.mapped(0x3000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        String str = valid.toString();
        assertTrue(str.contains("0x3000"));
        assertTrue(str.contains("RW"));

        PageEntry invalid = PageEntry.invalid();
        assertEquals("PageEntry[INVALID]", invalid.toString());
    }

    @Test
    void executePermissionIndependent() {
        PageEntry readOnly = PageEntry.mapped(0x1000, PageEntry.PERM_READ);
        PageEntry executeOnly = PageEntry.mapped(0x2000, PageEntry.PERM_EXECUTE);
        PageEntry readExecute = PageEntry.mapped(0x3000, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);

        assertTrue(readOnly.canRead());
        assertFalse(readOnly.canExecute());
        assertFalse(executeOnly.canRead());
        assertTrue(executeOnly.canExecute());
        assertTrue(readExecute.canRead());
        assertTrue(readExecute.canExecute());
    }
}