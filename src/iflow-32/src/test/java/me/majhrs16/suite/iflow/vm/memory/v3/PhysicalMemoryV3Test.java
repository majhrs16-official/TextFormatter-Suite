package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PhysicalMemoryV3Test {

    @Test
    void snapshotSharesFramesAndCopiesOnlyTheWrittenFrame() {
        PhysicalMemory memory = new PhysicalMemory(new byte[0], PhysicalMemory.MIN_RAM_SIZE);
        memory.writeByte(PhysicalMemory.RAM_BASE, 0x11);
        memory.writeByte(PhysicalMemory.RAM_BASE + PhysicalMemory.PAGE_SIZE, 0x22);

        PhysicalMemory snapshot = memory.snapshot();

        assertEquals(0x11, snapshot.readByte(PhysicalMemory.RAM_BASE));
        assertEquals(0x22, snapshot.readByte(PhysicalMemory.RAM_BASE + PhysicalMemory.PAGE_SIZE));
        assertEquals(2, snapshot.residentFrameCount());

        memory.writeByte(PhysicalMemory.RAM_BASE, 0x33);
        assertEquals(0x11, snapshot.readByte(PhysicalMemory.RAM_BASE));
        assertEquals(0x33, memory.readByte(PhysicalMemory.RAM_BASE));

        snapshot.writeByte(PhysicalMemory.RAM_BASE + PhysicalMemory.PAGE_SIZE, 0x44);
        assertEquals(0x22, memory.readByte(PhysicalMemory.RAM_BASE + PhysicalMemory.PAGE_SIZE));
        assertEquals(0x44,
                snapshot.readByte(PhysicalMemory.RAM_BASE + PhysicalMemory.PAGE_SIZE));
    }

    @Test
    void clearRamDoesNotEraseASnapshotSharingTheFrames() {
        PhysicalMemory memory = new PhysicalMemory(new byte[0], PhysicalMemory.MIN_RAM_SIZE);
        memory.writeByte(PhysicalMemory.RAM_BASE, 0x7A);
        PhysicalMemory snapshot = memory.snapshot();

        memory.clearRam();

        assertEquals(0, memory.readByte(PhysicalMemory.RAM_BASE));
        assertEquals(0x7A, snapshot.readByte(PhysicalMemory.RAM_BASE));
        assertArrayEquals(new byte[0], memory.romBytes());
    }
}
