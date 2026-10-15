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
        SoftMMU mmu = createMappedMMU();
        // Map page 0 with EXECUTE + WRITE for this test
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE);
        mmu.writeByte(0x0000, 0x60); // Opcode
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
        // Try to fetch word at end of page (would cross into page 1 which is not mapped)
        assertThrows(VmException.class, () -> mmu.fetchWord(0x0FFE));
    }
}