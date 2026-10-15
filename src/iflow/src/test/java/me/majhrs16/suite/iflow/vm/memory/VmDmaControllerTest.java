package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.isa.VmException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VmDmaControllerTest {

    private SoftMMU createMappedMMU() {
        SoftMMU mmu = new SoftMMU();
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
    void readWriteByte() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        dma.writeByte(0x2010, 0xAB);
        assertEquals(0xAB, dma.readByte(0x2010));
    }

    @Test
    void readWriteWordLittleEndian() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        dma.writeWord(0x2020, 0x1234);
        assertEquals(0x1234, dma.readWord(0x2020));
    }

    @Test
    void readWriteBlockContiguous() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        byte[] src = new byte[256];
        for (int i = 0; i < 256; i++) src[i] = (byte) i;
        dma.writeBlock(0x2000, src, 0, 256);
        byte[] dst = new byte[256];
        dma.readBlock(0x2000, dst, 0, 256);
        assertArrayEquals(src, dst);
    }

    @Test
    void writeBlockOutOfBoundsThrows() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        byte[] src = new byte[5000];
        assertThrows(VmException.class, () -> dma.writeBlock(0xF000, src, 0, 5000));
    }

    @Test
    void readBlockOutOfBoundsThrows() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        byte[] dst = new byte[5000];
        assertThrows(VmException.class, () -> dma.readBlock(0xF000, dst, 0, 5000));
    }

    @Test
    void fetchByteUsesExecutePermission() {
        SoftMMU mmu = createMappedMMU();
        // Map page 0 with EXECUTE + WRITE for this test
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE);
        VmDmaController dma = new VmDmaController(mmu);
        mmu.writeByte(0x0000, 0x60);
        assertEquals(0x60, mmu.fetchByte(0x0000));
    }

    @Test
    void fetchByteWithoutExecuteThrows() {
        SoftMMU mmu = new SoftMMU();
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        VmDmaController dma = new VmDmaController(mmu);
        mmu.writeByte(0x0000, 0x60);
        assertThrows(VmException.class, () -> mmu.fetchByte(0x0000));
    }

    @Test
    void fetchWordLittleEndian() {
        SoftMMU mmu = createMappedMMU();
        // Map page 0 with WRITE + EXECUTE for this test
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE);
        VmDmaController dma = new VmDmaController(mmu);
        mmu.writeWord(0x0000, 0x1234);
        assertEquals(0x1234, mmu.fetchWord(0x0000));
    }

    @Test
    void copyBlock() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        // Write source data
        byte[] src = new byte[100];
        for (int i = 0; i < 100; i++) src[i] = (byte) i;
        mmu.writeBlock(0x2000, src, 0, 100);
        // Copy to destination
        dma.copyBlock(0x2000, 0x3000, 100);
        // Verify destination
        byte[] dst = new byte[100];
        dma.readBlock(0x3000, dst, 0, 100);
        byte[] expected = new byte[100];
        for (int i = 0; i < 100; i++) expected[i] = (byte) i;
        assertArrayEquals(expected, dst);
    }

    @Test
    void copyBlockWithOffset() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        byte[] src = new byte[100];
        for (int i = 0; i < 100; i++) src[i] = (byte) (i + 10);
        // Write 62 bytes so that copy from offset 10 (50 bytes) has valid data
        mmu.writeBlock(0x2000, src, 10, 62); // Write 62 bytes at offset 10
        dma.copyBlock(0x2000 + 10, 0x3000, 50); // Copy 50 bytes from offset 10
        byte[] dst = new byte[50];
        dma.readBlock(0x3000, dst, 0, 50);
        byte[] expected = new byte[50];
        for (int i = 0; i < 50; i++) expected[i] = (byte) (i + 30); // src[20] = 30
        assertArrayEquals(expected, dst);
    }

    @Test
    void moveBlock() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        byte[] src = new byte[100];
        for (int i = 0; i < 100; i++) src[i] = (byte) i;
        mmu.writeBlock(0x2000, src, 0, 100);
        // Move with overlap (source 0x2000, dest 0x2010)
        dma.moveBlock(0x2000, 0x2010, 90);
        // Verify memmove semantics
        byte[] dst = new byte[90];
        dma.readBlock(0x2010, dst, 0, 90);
        byte[] expected = new byte[90];
        for (int i = 0; i < 90; i++) expected[i] = (byte) i;
        assertArrayEquals(expected, dst);
    }

    @Test
    void copyBlockOutOfBoundsThrows() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        assertThrows(VmException.class, () -> dma.copyBlock(0xF000, 0x2000, 5000));
    }

    @Test
    void copyBlockPermissionDeniedThrows() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        // Page 1 is read-only, so copying to it should fail (no write permission)
        assertThrows(VmException.class, () -> dma.copyBlock(0x2000, 0x1000, 100));
    }

    @Test
    void copyBlockZeroLength() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        dma.copyBlock(0x2000, 0x3000, 0); // Should not throw
    }

    @Test
    void moveBlockOverlap() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        byte[] src = new byte[100];
        for (int i = 0; i < 100; i++) src[i] = (byte) i;
        mmu.writeBlock(0x2000, src, 0, 100);
        // Move overlapping region
        dma.moveBlock(0x2000, 0x2010, 90);
        // Verify memmove semantics
        byte[] dst = new byte[90];
        dma.readBlock(0x2010, dst, 0, 90);
        byte[] expected = new byte[90];
        for (int i = 0; i < 90; i++) expected[i] = (byte) i;
        assertArrayEquals(expected, dst);
    }

    @Test
    void validateRangeThrowsOnInvalidAddress() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        assertThrows(VmException.class, () -> dma.validateRange(0x10000, 10, PageEntry.PERM_READ, "test"));
        assertThrows(VmException.class, () -> dma.validateRange(-1, 10, PageEntry.PERM_READ, "test"));
    }

    @Test
    void validateRangeThrowsOnOutOfBounds() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        assertThrows(VmException.class, () -> dma.validateRange(0xFFF0, 0x20, PageEntry.PERM_READ, "test"));
    }

    @Test
    void validateRangeThrowsOnUnmappedPage() {
        SoftMMU mmu = new SoftMMU();
        // Only map page 0
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        VmDmaController dma = new VmDmaController(mmu);
        assertThrows(VmException.class, () -> dma.validateRange(0x1000, 10, PageEntry.PERM_READ, "test"));
    }

    @Test
    void validateRangeThrowsOnMissingPermission() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        // Page 1 is read-only
        mmu.getPageTable().mapPage(1, 0x1000, PageEntry.PERM_READ);
        assertThrows(VmException.class, () -> dma.validateRange(0x1000, 10, PageEntry.PERM_WRITE, "test"));
    }

    @Test
    void validateRangeSucceedsOnValidRange() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        assertDoesNotThrow(() -> dma.validateRange(0x2000, 100, PageEntry.PERM_READ | PageEntry.PERM_WRITE, "test"));
    }

    @Test
    void validateRangeHandlesCrossPage() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        // Pages 2 and 3 both have R/W
        assertDoesNotThrow(() -> dma.validateRange(0x2FF0, 0x20, PageEntry.PERM_READ | PageEntry.PERM_WRITE, "test"));
    }

    @Test
    void crossPageReadWrite() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        byte[] src = new byte[5000];
        for (int i = 0; i < src.length; i++) src[i] = (byte) (i & 0xFF);
        dma.writeBlock(0x2F00, src, 0, src.length);
        byte[] dst = new byte[src.length];
        dma.readBlock(0x2F00, dst, 0, dst.length);
        assertArrayEquals(src, dst);
    }

    @Test
    void writeBlockBeyondPageThrows() {
        SoftMMU mmu = createMappedMMU();
        VmDmaController dma = new VmDmaController(mmu);
        // Only map page 15
        mmu.getPageTable().mapPage(15, 0xF000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        byte[] src = new byte[5000];
        assertThrows(VmException.class, () -> dma.writeBlock(0xF000, src, 0, 5000));
    }

    @Test
    void copyConstructorIsolation() {
        SoftMMU original = createMappedMMU();
        VmDmaController originalDma = new VmDmaController(original);
        originalDma.writeWord(0x2000, 0xDEAD);

        SoftMMU copy = new SoftMMU(original);
        VmDmaController copyDma = new VmDmaController(copy);
        copyDma.writeWord(0x2000, 0xBEEF);

        assertEquals(0xDEAD, new VmDmaController(original).readWord(0x2000));
        assertEquals(0xBEEF, copyDma.readWord(0x2000));
    }
}