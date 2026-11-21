package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void wordAccessIsLittleEndianAndRejectsMisalignment() {
        PhysicalMemory memory = new PhysicalMemory(new byte[0], PhysicalMemory.MIN_RAM_SIZE);
        int address = PhysicalMemory.RAM_BASE + 0x1000;

        memory.writeWord(address, 0x1122_3344);

        assertEquals(0x44, memory.readByte(address));
        assertEquals(0x33, memory.readByte(address + 1));
        assertEquals(0x22, memory.readByte(address + 2));
        assertEquals(0x11, memory.readByte(address + 3));
        assertEquals(0x1122_3344, memory.readWord(address));
    }

    @Test
    void alignedWordAccessesDoNotTearBetweenThreads() throws Exception {
        PhysicalMemory memory = new PhysicalMemory(new byte[0], PhysicalMemory.MIN_RAM_SIZE);
        int address = PhysicalMemory.RAM_BASE + 0x2000;
        int first = 0xAAAA_AAAA;
        int second = 0x5555_5555;
        memory.writeWord(address, first);

        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);

        Thread writerOne = worker(start, () -> {
            for (int index = 0; index < 50_000; index++) {
                memory.writeWord(address, first);
            }
        }, failure);
        Thread writerTwo = worker(start, () -> {
            for (int index = 0; index < 50_000; index++) {
                memory.writeWord(address, second);
            }
        }, failure);
        Thread reader = worker(start, () -> {
            while (!stop.get()) {
                int value = memory.readWord(address);
                if (value != first && value != second) {
                    throw new AssertionError(
                            "torn word: 0x" + Integer.toHexString(value));
                }
            }
        }, failure);

        start.countDown();
        writerOne.join();
        writerTwo.join();
        stop.set(true);
        reader.join();

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertTrue(memory.readWord(address) == first || memory.readWord(address) == second);
    }

    private static Thread worker(CountDownLatch start, Runnable body,
                                 AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                start.await();
                body.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        thread.start();
        return thread;
    }
}
