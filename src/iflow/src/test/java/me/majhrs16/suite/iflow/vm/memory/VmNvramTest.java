package me.majhrs16.suite.iflow.vm.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VmNvramTest {

    @Test
    void decodesOnlyItsWindow() {
        VmNvram nvram = new VmNvram(0x0F10, 16);
        assertTrue(nvram.decodes(0x0F10));
        assertTrue(nvram.decodes(0x0F1F));
        assertFalse(nvram.decodes(0x0F0F)); // the screen bank sits just below
        assertFalse(nvram.decodes(0x0F20)); // one past the window
    }

    @Test
    void startsErased() {
        VmNvram nvram = new VmNvram(0x0F10, 4);
        for (int i = 0; i < 4; i++) {
            assertEquals(0, nvram.readByte(0x0F10 + i));
        }
    }

    @Test
    void storesAndReadsBackBytes() {
        VmNvram nvram = new VmNvram(0x0F10, 4);
        nvram.writeByte(0x0F10, 0xAB);
        nvram.writeByte(0x0F13, 0xCD);
        assertEquals(0xAB, nvram.readByte(0x0F10));
        assertEquals(0xCD, nvram.readByte(0x0F13));
    }

    @Test
    void wordAccessIsLittleEndian() {
        VmNvram nvram = new VmNvram(0x0F10, 4);
        nvram.writeWord(0x0F10, 0xB105);
        assertEquals(0x05, nvram.readByte(0x0F10));
        assertEquals(0xB1, nvram.readByte(0x0F11));
        assertEquals(0xB105, nvram.readWord(0x0F10));
    }

    @Test
    void blockAccessRoundTrips() {
        VmNvram nvram = new VmNvram(0x0F10, 8);
        byte[] src = {1, 2, 3, 4, 5, 6, 7, 8};
        nvram.writeBlock(0x0F10, src, 0, src.length);

        byte[] dst = new byte[src.length];
        nvram.readBlock(0x0F10, dst, 0, dst.length);
        assertArrayEquals(src, dst);

        nvram.clearBlock(0x0F10, 4);
        for (int i = 0; i < 4; i++) {
            assertEquals(0, nvram.readByte(0x0F10 + i));
        }
        assertEquals(5, nvram.readByte(0x0F14));
    }

    @Test
    void defaultWindowMatchesTheMemoryMap() {
        VmNvram nvram = new VmNvram();
        assertEquals(VmMemoryMap.DEVICE_NVRAM_BASE, nvram.base());
        assertEquals(VmMemoryMap.DEVICE_NVRAM_SIZE, nvram.size());
        assertTrue(nvram.decodes(VmMemoryMap.DEVICE_NVRAM_BASE));
    }

    @Test
    void rejectsANonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> new VmNvram(0x0F10, 0));
    }
}
