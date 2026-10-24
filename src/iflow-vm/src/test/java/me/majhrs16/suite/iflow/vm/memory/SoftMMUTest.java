package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.isa.VmException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoftMMUTest {

    private SoftMMU createMappedMMU() {
        SoftMMU mmu = new SoftMMU();
        // Map all standard pages
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        mmu.getPageTable().mapPage(1, 0x1000, PageEntry.PERM_READ);
        mmu.getPageTable().mapPage(2, 0x2000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(3, 0x3000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(4, 0x4000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(5, 0x5000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(6, 0x6000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(7, 0x7000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        return mmu;
    }

    @Test
    void newMMUHasZeroedMemory() {
        SoftMMU mmu = createMappedMMU();
        // Only check mapped pages (0-7)
        for (int page = 0; page < 8; page++) {
            int base = page * SoftMMU.PAGE_SIZE;
            for (int i = 0; i < SoftMMU.PAGE_SIZE; i++) {
                assertEquals(0, mmu.readByte(base + i) & 0xFF);
            }
        }
    }

    @Test
    void readWriteByte() {
        SoftMMU mmu = createMappedMMU();
        mmu.writeByte(0x2010, 0xAB); // Page 2 has R/W
        assertEquals(0xAB, mmu.readByte(0x2010));
    }

    @Test
    void readWriteWordLittleEndian() {
        SoftMMU mmu = createMappedMMU();
        mmu.writeWord(0x2020, 0x1234); // Page 2 has R/W
        assertEquals(0x1234, mmu.readWord(0x2020));
    }

    @Test
    void readWriteBlockContiguous() {
        SoftMMU mmu = createMappedMMU();
        byte[] src = new byte[256];
        for (int i = 0; i < 256; i++) src[i] = (byte) i;
        mmu.writeBlock(0x2000, src, 0, 256);
        byte[] dst = new byte[256];
        mmu.readBlock(0x2000, dst, 0, 256);
        assertArrayEquals(src, dst);
    }

    @Test
    void writeBlockOutOfBoundsThrows() {
        SoftMMU mmu = createMappedMMU();
        byte[] src = new byte[5000];
        assertThrows(VmException.class, () -> mmu.writeBlock(0xF000, src, 0, 5000));
    }

    @Test
    void readBlockOutOfBoundsThrows() {
        SoftMMU mmu = createMappedMMU();
        byte[] dst = new byte[5000];
        assertThrows(VmException.class, () -> mmu.readBlock(0xF000, dst, 0, 5000));
    }

    @Test
    void fetchByteUsesExecutePermission() {
        SoftMMU mmu = new SoftMMU();
        // Write the byte while the page is writable, then demote to EXECUTE-only:
        // a fetch must need EXECUTE, not READ.
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE);
        mmu.writeByte(0x0000, 0x60); // Opcode
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_EXECUTE);
        assertEquals(0x60, mmu.fetchByte(0x0000));
    }

    @Test
    void fetchByteWithoutExecuteThrows() {
        SoftMMU mmu = new SoftMMU();
        // Map with READ + WRITE but NOT EXECUTE
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.writeByte(0x0000, 0x60);
        assertThrows(VmException.class, () -> mmu.fetchByte(0x0000));
    }

    @Test
    void fetchWordLittleEndian() {
        SoftMMU mmu = createMappedMMU();
        // Map page 0 with WRITE + EXECUTE for this test
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE);
        mmu.writeWord(0x0000, 0x1234);
        assertEquals(0x1234, mmu.fetchWord(0x0000));
    }

    @Test
    void copyConstructorClonesMemoryAndPageTable() {
        SoftMMU original = createMappedMMU();
        original.writeWord(0x2000, 0xDEAD);

        SoftMMU copy = new SoftMMU(original);
        assertEquals(0xDEAD, copy.readWord(0x2000));

        // Verify independence
        copy.writeWord(0x2000, 0xBEEF);
        assertEquals(0xDEAD, original.readWord(0x2000));
        assertEquals(0xBEEF, copy.readWord(0x2000));
    }

    @Test
    void getMemorySnapshotReturnsCopy() {
        SoftMMU mmu = createMappedMMU();
        // Map page 1 with WRITE for this test
        mmu.getPageTable().mapPage(1, 0x1000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.writeWord(0x1000, 0xCAFE);
        byte[] snapshot = mmu.getMemorySnapshot();
        assertEquals(0xCAFE, (snapshot[0x1000] & 0xFF) | ((snapshot[0x1001] & 0xFF) << 8));
        snapshot[0x1000] = 0;
        assertEquals(0xCAFE, mmu.readWord(0x1000));
    }

    @Test
    void getRegionReturnsCopy() {
        SoftMMU mmu = createMappedMMU();
        mmu.writeWord(0x3000, 0xFEED);
        byte[] region = mmu.getRegion(0x3000, 2);
        assertEquals(0xFEED, (region[0] & 0xFF) | ((region[1] & 0xFF) << 8));
    }

    @Test
    void crossPageBlockReadWrite() {
        SoftMMU mmu = createMappedMMU();
        byte[] src = new byte[5000];
        for (int i = 0; i < 5000; i++) src[i] = (byte) (i & 0xFF);
        // Write crossing page boundary (0x4F00 + 5000 > 0x5000)
        mmu.writeBlock(0x4F00, src, 0, 5000);
        byte[] dst = new byte[5000];
        mmu.readBlock(0x4F00, dst, 0, 5000);
        assertArrayEquals(src, dst);
    }

    @Test
    void pageTableMutationsPersist() {
        SoftMMU mmu = createMappedMMU();
        mmu.writeByte(0x6000, 0x42);
        assertEquals(0x42, mmu.readByte(0x6000));
    }

    @Test
    void toStringContainsInfo() {
        SoftMMU mmu = createMappedMMU();
        String str = mmu.toString();
        assertTrue(str.contains("65536"));
    }

    @Test
    void isContiguousDetectsPageCrossing() {
        SoftMMU mmu = createMappedMMU();
        // This should use slow path (byte-by-byte) since it crosses pages
        byte[] src = new byte[5000];
        mmu.writeBlock(0x4F00, src, 0, 5000);
        byte[] dst = new byte[5000];
        mmu.readBlock(0x4F00, dst, 0, 5000);
        assertArrayEquals(src, dst);
    }

    @Test
    void writeThenReadViaDifferentMappings() {
        SoftMMU mmu = createMappedMMU();
        // Two virtual pages mapping to same physical frame
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(1, 0x0000, PageEntry.PERM_READ);
        mmu.writeByte(0x0000, 0x99);
        assertEquals(0x99, mmu.readByte(0x1000)); // Read via page 1
    }

    @Test
    void readWriteWithOffset() {
        SoftMMU mmu = createMappedMMU();
        byte[] src = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        mmu.writeBlock(0x2000, src, 1, 2); // Writes 0x02, 0x03 to 0x2000, 0x2001
        byte[] dst = new byte[2];
        mmu.readBlock(0x2000, dst, 0, 2); // Reads from 0x2000, 0x2001
        assertEquals(0x02, dst[0] & 0xFF);
        assertEquals(0x03, dst[1] & 0xFF);
    }

    @Test
    void writeBlockBeyondPageThrows() {
        SoftMMU mmu = createMappedMMU();
        // Only map page 15
        mmu.getPageTable().mapPage(15, 0xF000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        byte[] src = new byte[5000];
        assertThrows(VmException.class, () -> mmu.writeBlock(0xF000, src, 0, 5000));
    }

    @Test
    void fetchWordCrossPageThrows() {
        SoftMMU mmu = createMappedMMU();
        // Map only page 0
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_EXECUTE);
        // A word at 0x0FFF spans pages 0 and 1; page 1 is not mapped, so the
        // second byte's translation must fault.
        assertThrows(VmException.class, () -> mmu.fetchWord(0x0FFF));
    }

    @Test
    void fetchWordAtEndOfPageStaysWithinIt() {
        SoftMMU mmu = createMappedMMU();
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE);
        mmu.writeWord(0x0FFE, 0xBEEF);
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_EXECUTE);
        // 0x0FFE..0x0FFF is the last word fully inside page 0 (no crossing).
        assertEquals(0xBEEF, mmu.fetchWord(0x0FFE));
    }

    @Test
    void programCannotWriteKernelPage() {
        SoftMMU mmu = new SoftMMU();
        mmu.mapPageKernel(2, 0x2000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        assertThrows(VmException.class, () -> mmu.writeByte(0x2000, 0x42));
        // The supervisor master may write it.
        mmu.writeByte(0x2000, 0x42, Privilege.SUPERVISOR);
        assertEquals(0x42, mmu.readByte(0x2000));
    }

    @Test
    void readsAcceptKernelPagesForBothPrivileges() {
        // IFLOW-AUD-033: the privilege of a read travels through the same ownership
        // check as the write. Reading a kernel page succeeds for both domains today
        // (it is program-readable by design); the point is that it is not dropped.
        SoftMMU mmu = new SoftMMU();
        mmu.mapPageKernel(2, 0x2000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.writeByte(0x2000, 0x5A, Privilege.SUPERVISOR);
        assertEquals(0x5A, mmu.readByte(0x2000, Privilege.PROGRAM));
        assertEquals(0x5A, mmu.readByte(0x2000, Privilege.SUPERVISOR));
        byte[] block = new byte[4];
        mmu.readBlock(0x2000, block, 0, 4, Privilege.SUPERVISOR);
        assertEquals(0x5A, block[0] & 0xFF);
    }

    @Test
    void busCarriesMasterPrivilege() {
        SoftMMU mmu = new SoftMMU();
        mmu.mapPageKernel(2, 0x2000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);

        VmBus program = new VmBus(mmu);
        assertSame(Privilege.PROGRAM, program.privilege());
        assertThrows(VmException.class, () -> program.writeByte(0x2000, 1));

        VmBus supervisor = VmBus.supervisor(mmu);
        assertSame(Privilege.SUPERVISOR, supervisor.privilege());
        supervisor.writeByte(0x2000, 1);
        assertEquals(1, program.readByte(0x2000));
    }

    @Test
    void pageTableForInspectionIsACopy() {
        SoftMMU mmu = createMappedMMU();
        PageTable snapshot = mmu.getPageTableForInspection();
        assertNotSame(mmu.getPageTable(), snapshot);
        snapshot.protect(2, PageEntry.PERM_READ);
        assertTrue(mmu.getPageTable().getEntryByIndex(2).canWrite(),
            "inspecting must not mutate the live page table");
    }

    @Test
    void physicalMemoryBacksSnapshots() {
        SoftMMU mmu = createMappedMMU();
        mmu.writeByte(0x2000, 0x7F);
        assertEquals(0x7F, mmu.getPhysicalMemory()[0x2000] & 0xFF);
        assertArrayEquals(mmu.getMemorySnapshot(), mmu.getPhysicalMemory());
    }

    // ===== Copy-on-write forking (IFLOW-AUD-029) =====

    @Test
    void forkedMMUSharesSnapshotThenIsolatesOnWrite() {
        SoftMMU template = createMappedMMU();
        template.writeByte(0x2000, 0x55); // page 2 is R/W

        SoftMMU a = new SoftMMU(template);
        SoftMMU b = new SoftMMU(template);
        // Both forks see the shared snapshot (no 64KB clone per fork).
        assertEquals(0x55, a.readByte(0x2000));
        assertEquals(0x55, b.readByte(0x2000));

        // Writes are copy-on-write and isolated.
        a.writeByte(0x2000, 0xAA);
        assertEquals(0xAA, a.readByte(0x2000));
        assertEquals(0x55, b.readByte(0x2000), "siblings must not see each other's writes");
        assertEquals(0x55, template.readByte(0x2000), "the template must not be mutated");
    }

    @Test
    void freezePublishesCurrentViewToForks() {
        SoftMMU template = createMappedMMU();
        template.writeWord(0x3000, 0x1234);
        template.freeze();

        SoftMMU fork = new SoftMMU(template);
        assertEquals(0x1234, fork.readWord(0x3000), "the fork must inherit the frozen image");

        fork.writeWord(0x3000, 0x4321);
        assertEquals(0x1234, template.readWord(0x3000), "writing the fork must not touch the template");
    }

    @Test
    void clearBlockZeroesRangeAndEnforcesPermissions() {
        SoftMMU mmu = createMappedMMU();
        mmu.writeWord(0x3000, 0xBEEF);
        mmu.clearBlock(0x3000, 4096);
        assertEquals(0, mmu.readWord(0x3000));

        // A read-only page cannot be cleared (still a write transaction).
        assertThrows(VmException.class, () -> mmu.clearBlock(0x1000, 16));
    }
}