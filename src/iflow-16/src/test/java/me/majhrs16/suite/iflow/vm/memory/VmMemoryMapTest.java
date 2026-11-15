package me.majhrs16.suite.iflow.vm.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the invariants of the single-source memory map. In particular, the code
 * region must never overlap the constants region: that overlap was the latent bug
 * this map was introduced to prevent.
 */
class VmMemoryMapTest {

    @Test
    void regionsArePageAlignedAndOrdered() {
        int[] bases = {
            VmMemoryMap.CODE_BASE,
            VmMemoryMap.CONSTANTS_BASE,
            VmMemoryMap.DATA_BASE,
            VmMemoryMap.STACK_BASE,
            VmMemoryMap.INPUT_BASE,
            VmMemoryMap.OUTPUT_BASE
        };

        for (int base : bases) {
            assertEquals(0, base % VmMemoryMap.PAGE,
                "0x" + Integer.toHexString(base) + " must be page-aligned");
        }
        for (int i = 1; i < bases.length; i++) {
            assertTrue(bases[i] > bases[i - 1], "memory regions must be ordered and disjoint");
        }
    }

    @Test
    void codeRegionNeverOverlapsConstants() {
        int codeEnd = VmMemoryMap.CODE_BASE + VmMemoryMap.CODE_MAX_SIZE;
        assertTrue(codeEnd <= VmMemoryMap.CONSTANTS_BASE,
            "code region [0x" + Integer.toHexString(VmMemoryMap.CODE_BASE) + ", 0x"
                + Integer.toHexString(codeEnd) + ") overlaps constants at 0x"
                + Integer.toHexString(VmMemoryMap.CONSTANTS_BASE));
    }

    @Test
    void stackTopIsOnePastStackRegion() {
        assertEquals(VmMemoryMap.STACK_BASE + VmMemoryMap.STACK_PAGES * VmMemoryMap.PAGE,
            VmMemoryMap.STACK_TOP);
    }

    @Test
    void ioBuffersAreDistinctPages() {
        assertNotEquals(VmMemoryMap.pageOf(VmMemoryMap.INPUT_BASE),
            VmMemoryMap.pageOf(VmMemoryMap.OUTPUT_BASE));
        assertEquals(VmMemoryMap.PAGE, VmMemoryMap.IO_BUFFER_SIZE);
    }
}
