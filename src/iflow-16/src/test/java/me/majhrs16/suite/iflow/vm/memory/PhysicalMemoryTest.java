package me.majhrs16.suite.iflow.vm.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Copy-on-write semantics of the physical backing store (IFLOW-AUD-029). These
 * tests pin the property that the engine relies on: forked MMUs share the loaded
 * image and only copy the frames an execution actually writes.
 */
class PhysicalMemoryTest {

    @Test
    void blankStartsFullyShared() {
        PhysicalMemory mem = PhysicalMemory.blank();
        assertEquals(PageTable.ADDRESS_SPACE_SIZE, mem.size());
        assertEquals(0, mem.privateFrameCount(), "a fresh store shares every frame");
        assertEquals(0, mem.readByte(0x1234));
    }

    @Test
    void firstWriteCopiesOnlyThatFrame() {
        PhysicalMemory mem = PhysicalMemory.blank();
        mem.writeByte(0x3010, 0xAB);
        assertEquals(1, mem.privateFrameCount());
        assertEquals(0xAB, mem.readByte(0x3010));

        // Same frame: still a single private frame.
        mem.writeByte(0x3020, 0xCD);
        assertEquals(1, mem.privateFrameCount());

        // A different frame gets its own copy.
        mem.writeByte(0x5010, 0xEF);
        assertEquals(2, mem.privateFrameCount());
    }

    @Test
    void writeThatChangesNothingDoesNotCopy() {
        PhysicalMemory mem = PhysicalMemory.blank();
        // Writing zero over an already-zero shared byte is a no-op.
        mem.writeByte(0x7000, 0x00);
        assertEquals(0, mem.privateFrameCount());
        // Clearing an already-zero page with a zero block is a no-op (the pool's
        // I/O reset path).
        mem.writeBlock(0x7000, new byte[PageTable.PAGE_SIZE], 0, PageTable.PAGE_SIZE);
        assertEquals(0, mem.privateFrameCount());
        // A real change copies.
        mem.writeByte(0x7000, 0x01);
        assertEquals(1, mem.privateFrameCount());
    }

    @Test
    void forkSharesSnapshotAndIsolatesWrites() {
        PhysicalMemory base = PhysicalMemory.blank();
        base.writeByte(0x3010, 0x11);
        base.freeze();

        PhysicalMemory a = base.fork();
        PhysicalMemory b = base.fork();
        assertEquals(0x11, a.readByte(0x3010), "the fork must see the shared snapshot");
        assertEquals(0x11, b.readByte(0x3010));

        a.writeByte(0x3010, 0x22);
        assertEquals(0x22, a.readByte(0x3010));
        assertEquals(0x11, b.readByte(0x3010), "sibling forks must not see each other's writes");
        assertEquals(0x11, base.readByte(0x3010), "the template must not be mutated");
    }

    @Test
    void freezeMovesPrivateFramesIntoTheSnapshot() {
        PhysicalMemory mem = PhysicalMemory.blank();
        mem.writeByte(0x3010, 0x42);
        assertEquals(1, mem.privateFrameCount());

        mem.freeze();
        assertEquals(0, mem.privateFrameCount());
        assertEquals(0x42, mem.snapshot()[0x3010] & 0xFF);
    }

    @Test
    void snapshotMergesSharedAndPrivateFrames() {
        PhysicalMemory base = PhysicalMemory.blank();
        base.writeByte(0x2000, 0x0A);
        base.freeze();

        PhysicalMemory fork = base.fork();
        fork.writeByte(0x3000, 0x0B);
        byte[] snap = fork.snapshot();
        assertEquals(0x0A, snap[0x2000] & 0xFF, "shared frame");
        assertEquals(0x0B, snap[0x3000] & 0xFF, "private frame");
    }

    @Test
    void blockWriteAcrossFramesCopiesOnlyTouchedFrames() {
        PhysicalMemory mem = PhysicalMemory.blank();
        byte[] src = new byte[PageTable.PAGE_SIZE + 16];
        java.util.Arrays.fill(src, (byte) 0x7E);
        mem.writeBlock(0x2FF0, src, 0, src.length); // spans frames 2 and 3
        assertEquals(2, mem.privateFrameCount());
        assertEquals(0x7E, mem.readByte(0x2FF0));
        assertEquals(0x7E, mem.readByte(0x3000));
    }

    @Test
    void clearBlockOnAlreadyZeroSharedFrameCopiesNothing() {
        PhysicalMemory mem = PhysicalMemory.blank();
        mem.clearBlock(0x7000, PageTable.PAGE_SIZE);
        assertEquals(0, mem.privateFrameCount(),
            "clearing an already-zero shared frame must not allocate/copy anything");
        assertEquals(0, mem.readByte(0x7000));
    }

    @Test
    void clearBlockZeroesPrivateAndNonZeroSharedFrames() {
        PhysicalMemory mem = PhysicalMemory.blank();
        mem.writeByte(0x3010, 0x11);                 // frame 3 becomes private
        mem.clearBlock(0x3000, PageTable.PAGE_SIZE); // zero a private frame
        assertEquals(0, mem.readByte(0x3010));
        assertEquals(1, mem.privateFrameCount());

        mem.writeByte(0x2000, 0x22);                 // frame 2 non-zero
        mem.freeze();
        PhysicalMemory fork = mem.fork();
        assertEquals(0x22, fork.readByte(0x2000));
        fork.clearBlock(0x2000, PageTable.PAGE_SIZE); // zero a non-zero shared frame
        assertEquals(0, fork.readByte(0x2000));
        assertEquals(0x22, mem.readByte(0x2000), "the template must stay untouched");
        assertEquals(1, fork.privateFrameCount());
    }
}
