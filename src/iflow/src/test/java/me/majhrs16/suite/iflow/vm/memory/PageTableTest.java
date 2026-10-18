package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.isa.VmException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PageTableTest {

    @Test
    void initialStateAllPagesInvalid() {
        PageTable table = new PageTable();
        for (int i = 0; i < PageTable.NUM_PAGES; i++) {
            assertFalse(table.getEntryByIndex(i).isValid());
        }
        assertEquals(0, table.validCount());
    }

    @Test
    void mapPageCreatesValidEntry() {
        PageTable table = new PageTable();
        table.mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        assertTrue(table.getEntryByIndex(0).isValid());
        assertEquals(1, table.validCount());
    }

    @Test
    void mapPageKernelCreatesNonUserEntry() {
        PageTable table = new PageTable();
        table.mapPageKernel(1, 0x1000, PageEntry.PERM_READ);
        assertTrue(table.getEntryByIndex(1).isValid());
        assertFalse(table.getEntryByIndex(1).isUser());
    }

    @Test
    void programCannotWriteKernelPageEvenWithWriteBit() {
        PageTable table = new PageTable();
        table.mapPageKernel(1, 0x1000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        // The supervisor may write it...
        assertEquals(0x1000, table.translateWrite(0x1000, Privilege.SUPERVISOR));
        // ...but the program domain must not, regardless of the WRITE bit.
        VmException e = assertThrows(VmException.class,
            () -> table.translateWrite(0x1000, Privilege.PROGRAM));
        assertEquals(VmException.Code.PRIVILEGE_FAULT, e.code());
    }

    @Test
    void programMayReadAndExecuteKernelPage() {
        PageTable table = new PageTable();
        table.mapPageKernel(2, 0x2000, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        assertEquals(0x2000, table.translateRead(0x2000));
        assertEquals(0x2000, table.translateExecute(0x2000));
    }

    @Test
    void protectChangesPermissionsInPlace() {
        PageTable table = new PageTable();
        table.mapPage(3, 0x3000,
            PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE);
        table.protect(3, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        assertFalse(table.getEntryByIndex(3).canWrite());
        assertTrue(table.getEntryByIndex(3).canRead());
        assertEquals(0x3000, table.getEntryByIndex(3).getFrameBase());
    }

    @Test
    void copyIsDeep() {
        PageTable table = new PageTable();
        table.mapPage(4, 0x4000, PageEntry.PERM_READ);
        PageTable copy = table.copy();
        copy.protect(4, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        assertFalse(table.getEntryByIndex(4).canWrite());
        assertTrue(copy.getEntryByIndex(4).canWrite());
    }

    @Test
    void unmapPageClearsEntry() {
        PageTable table = new PageTable();
        table.mapPage(5, 0x5000, PageEntry.PERM_READ);
        table.unmapPage(5);
        assertFalse(table.getEntryByIndex(5).isValid());
    }

    @Test
    void translateReadWithValidMapping() {
        PageTable table = new PageTable();
        table.mapPage(2, 0x2000, PageEntry.PERM_READ);
        int phys = table.translateRead(0x2050);
        assertEquals(0x2050, phys);
    }

    @Test
    void translateWriteWithValidMapping() {
        PageTable table = new PageTable();
        table.mapPage(3, 0x3000, PageEntry.PERM_WRITE);
        int phys = table.translateWrite(0x3ABC);
        assertEquals(0x3ABC, phys);
    }

    @Test
    void translateExecuteWithValidMapping() {
        PageTable table = new PageTable();
        table.mapPage(0, 0x0000, PageEntry.PERM_EXECUTE);
        int phys = table.translateExecute(0x0010);
        assertEquals(0x0010, phys);
    }

    @Test
    void translateReadFailsOnUnmappedPage() {
        PageTable table = new PageTable();
        assertThrows(VmException.class, () -> table.translateRead(0x5000));
    }

    @Test
    void translateWriteFailsWithoutWritePermission() {
        PageTable table = new PageTable();
        table.mapPage(1, 0x1000, PageEntry.PERM_READ);
        assertThrows(VmException.class, () -> table.translateWrite(0x1000));
    }

    @Test
    void translateExecuteFailsWithoutExecutePermission() {
        PageTable table = new PageTable();
        table.mapPage(0, 0x0000, PageEntry.PERM_READ);
        assertThrows(VmException.class, () -> table.translateExecute(0x0000));
    }

    @Test
    void translateReadSucceedsOnUserPage() {
        PageTable table = new PageTable();
        table.mapPage(6, 0x6000, PageEntry.PERM_READ);
        int phys = table.translateRead(0x6000);
        assertEquals(0x6000, phys);
    }

    @Test
    void addressOutOfBoundsThrows() {
        PageTable table = new PageTable();
        table.mapPage(15, 0xF000, PageEntry.PERM_READ);
        assertThrows(VmException.class, () -> table.translateRead(0x10000));
        assertThrows(VmException.class, () -> table.translateRead(-1));
    }

    @Test
    void pageBoundaryTranslation() {
        PageTable table = new PageTable();
        table.mapPage(7, 0x7000, PageEntry.PERM_READ);
        // Start of page
        assertEquals(0x7000, table.translateRead(0x7000));
        // End of page
        assertEquals(0x7FFF, table.translateRead(0x7FFF));
    }

    @Test
    void copyCreatesIndependentTable() {
        PageTable original = new PageTable();
        original.mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        original.mapPage(1, 0x1000, PageEntry.PERM_READ);

        PageTable copy = original.copy();
        assertEquals(original.validCount(), copy.validCount());

        // Modify copy
        copy.mapPage(2, 0x2000, PageEntry.PERM_WRITE);
        assertEquals(3, copy.validCount());
        assertEquals(2, original.validCount());
    }

    @Test
    void clearResetsAllPages() {
        PageTable table = new PageTable();
        table.mapPage(0, 0x0000, PageEntry.PERM_READ);
        table.mapPage(1, 0x1000, PageEntry.PERM_READ);
        table.clear();
        assertEquals(0, table.validCount());
        for (int i = 0; i < PageTable.NUM_PAGES; i++) {
            assertFalse(table.getEntryByIndex(i).isValid());
        }
    }

    @Test
    void getEntryReturnsCorrectPage() {
        PageTable table = new PageTable();
        table.mapPage(5, 0x5000, PageEntry.PERM_READ);
        PageEntry entry = table.getEntry(0x5ABC);
        assertTrue(entry.isValid());
        assertEquals(0x5000, entry.getFrameBase());
    }

    @Test
    void getEntryByIndexHandlesInvalidIndex() {
        PageTable table = new PageTable();
        assertFalse(table.getEntryByIndex(-1).isValid());
        assertFalse(table.getEntryByIndex(PageTable.NUM_PAGES).isValid());
    }

    @Test
    void multiplePagesCanMapToSameFrame() {
        PageTable table = new PageTable();
        table.mapPage(0, 0x1000, PageEntry.PERM_READ);
        table.mapPage(1, 0x1000, PageEntry.PERM_READ);
        assertEquals(0x1000, table.getEntryByIndex(0).getFrameBase());
        assertEquals(0x1000, table.getEntryByIndex(1).getFrameBase());
    }

    @Test
    void permissionsAreCheckedPerOperation() {
        PageTable table = new PageTable();
        table.mapPage(2, 0x2000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        assertDoesNotThrow(() -> table.translateRead(0x2000));
        assertDoesNotThrow(() -> table.translateWrite(0x2000));
        assertThrows(VmException.class, () -> table.translateExecute(0x2000));
    }

    @Test
    void frameAlignmentEnforced() {
        PageTable table = new PageTable();
        assertThrows(VmException.class, () -> table.mapPage(0, 0x1234, PageEntry.PERM_READ));
    }
}