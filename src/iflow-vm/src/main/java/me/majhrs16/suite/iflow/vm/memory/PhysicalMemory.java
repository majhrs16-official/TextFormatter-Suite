package me.majhrs16.suite.iflow.vm.memory;

import java.util.Arrays;

/**
 * Physical backing store for the flat 64KB address space, split into 4KB frames.
 * <p>
 * The store is a read-only {@code shared} snapshot plus a per-frame private
 * overlay. A frame is copied into the overlay the first time it is written
 * (copy-on-write), so every MMU forked from the same loaded program shares the
 * read-only code/constants frames and only ever allocates the frames an
 * execution actually mutates (data/stack/I/O). This replaces the previous full
 * 64KB {@code clone()} per execution (IFLOW-AUD-029).
 * </p>
 * <p>
 * The {@code shared} snapshot is immutable once {@link #freeze()} publishes it;
 * forks only read it, so concurrent executions need no locking. Writes that do
 * not change memory (e.g. clearing an already-zero I/O page) are elided, so a
 * fresh execution pays nothing for the pages it leaves untouched.
 * </p>
 */
final class PhysicalMemory {

    private static final int FRAME_COUNT = PageTable.NUM_PAGES;   // 16
    private static final int FRAME_SIZE = PageTable.PAGE_SIZE;    // 4KB
    private static final int FRAME_SHIFT = PageTable.PAGE_SHIFT;  // 12
    private static final int FRAME_MASK = PageTable.OFFSET_MASK;  // 0x0FFF

    /** Immutable snapshot shared by this store and every fork of it. */
    private byte[] shared;
    /** Which frames of {@code shared} are entirely zero (avoids pointless writes). */
    private final boolean[] zeroShared = new boolean[FRAME_COUNT];
    /** Per-frame copy-on-write overlay; {@code null} means "still shared". */
    private final byte[][] privateFrames = new byte[FRAME_COUNT][];

    private PhysicalMemory(byte[] shared) {
        this.shared = shared;
    }

    /** A blank store (all-zero shared snapshot). */
    static PhysicalMemory blank() {
        PhysicalMemory mem = new PhysicalMemory(new byte[PageTable.ADDRESS_SPACE_SIZE]);
        Arrays.fill(mem.zeroShared, true);
        return mem;
    }

    /**
     * Forks a copy-on-write view: the child shares the parent's immutable
     * snapshot but has its own private overlay, so its writes never affect the
     * parent nor sibling forks.
     */
    PhysicalMemory fork() {
        freeze(); // make the parent's current view shareable (no-op if already frozen)
        PhysicalMemory child = new PhysicalMemory(shared);
        System.arraycopy(zeroShared, 0, child.zeroShared, 0, FRAME_COUNT);
        return child;
    }

    /**
     * Materializes the current view into the shared snapshot and clears the
     * private overlay, so this store and future forks share it read-only.
     * Idempotent: a store with no private frames is already frozen.
     */
    void freeze() {
        boolean hasPrivate = false;
        for (byte[] frame : privateFrames) {
            if (frame != null) {
                hasPrivate = true;
                break;
            }
        }
        if (!hasPrivate) {
            return;
        }
        byte[] flat = shared.clone();
        for (int f = 0; f < FRAME_COUNT; f++) {
            byte[] frame = privateFrames[f];
            if (frame != null) {
                System.arraycopy(frame, 0, flat, f << FRAME_SHIFT, FRAME_SIZE);
            }
        }
        this.shared = flat;
        Arrays.fill(privateFrames, null);
        recomputeZeroFrames();
    }

    /** Size of the physical address space in bytes. */
    int size() {
        return shared.length;
    }

    /** Number of frames currently copied into the private overlay (diagnostics/tests). */
    int privateFrameCount() {
        int count = 0;
        for (byte[] frame : privateFrames) {
            if (frame != null) {
                count++;
            }
        }
        return count;
    }

    int readByte(int addr) {
        int frame = addr >>> FRAME_SHIFT;
        byte[] priv = privateFrames[frame];
        if (priv != null) {
            return priv[addr & FRAME_MASK] & 0xFF;
        }
        return shared[addr] & 0xFF;
    }

    void writeByte(int addr, int value) {
        int frame = addr >>> FRAME_SHIFT;
        byte[] priv = privateFrames[frame];
        if (priv == null) {
            if ((shared[addr] & 0xFF) == (value & 0xFF)) {
                return; // No change: skip the copy-on-write entirely.
            }
            priv = privateFrames[frame] = copyFrame(frame);
        }
        priv[addr & FRAME_MASK] = (byte) value;
    }

    void readBlock(int addr, byte[] dest, int destOffset, int length) {
        int remaining = length;
        int a = addr;
        int d = destOffset;
        while (remaining > 0) {
            int frame = a >>> FRAME_SHIFT;
            int offset = a & FRAME_MASK;
            int chunk = Math.min(remaining, FRAME_SIZE - offset);
            byte[] priv = privateFrames[frame];
            if (priv != null) {
                System.arraycopy(priv, offset, dest, d, chunk);
            } else {
                System.arraycopy(shared, a, dest, d, chunk);
            }
            a += chunk;
            d += chunk;
            remaining -= chunk;
        }
    }

    void writeBlock(int addr, byte[] src, int srcOffset, int length) {
        int remaining = length;
        int a = addr;
        int s = srcOffset;
        while (remaining > 0) {
            int frame = a >>> FRAME_SHIFT;
            int offset = a & FRAME_MASK;
            int chunk = Math.min(remaining, FRAME_SIZE - offset);
            byte[] priv = privateFrames[frame];
            if (priv == null) {
                // If the write would not change anything, skip the copy-on-write.
                if (!regionEqualsShared(a, src, s, chunk)) {
                    priv = privateFrames[frame] = copyFrame(frame);
                }
            }
            if (priv != null) {
                System.arraycopy(src, s, priv, offset, chunk);
            }
            a += chunk;
            s += chunk;
            remaining -= chunk;
        }
    }

    /** A flat copy of the whole physical memory (shared snapshot + overlay). */
    byte[] snapshot() {
        byte[] flat = shared.clone();
        for (int f = 0; f < FRAME_COUNT; f++) {
            byte[] frame = privateFrames[f];
            if (frame != null) {
                System.arraycopy(frame, 0, flat, f << FRAME_SHIFT, FRAME_SIZE);
            }
        }
        return flat;
    }

    /**
     * Zeroes a physical range. A shared frame that is already entirely zero is
     * left untouched (no copy, no compare): this is what makes the per-execution
     * I/O reset essentially free.
     */
    void clearBlock(int addr, int length) {
        int remaining = length;
        int a = addr;
        while (remaining > 0) {
            int frame = a >>> FRAME_SHIFT;
            int offset = a & FRAME_MASK;
            int chunk = Math.min(remaining, FRAME_SIZE - offset);
            byte[] priv = privateFrames[frame];
            if (priv != null) {
                Arrays.fill(priv, offset, offset + chunk, (byte) 0);
            } else if (!zeroShared[frame]) {
                priv = privateFrames[frame] = copyFrame(frame);
                Arrays.fill(priv, offset, offset + chunk, (byte) 0);
            }
            a += chunk;
            remaining -= chunk;
        }
    }

    private byte[] copyFrame(int frame) {
        byte[] copy = new byte[FRAME_SIZE];
        System.arraycopy(shared, frame << FRAME_SHIFT, copy, 0, FRAME_SIZE);
        return copy;
    }

    private boolean regionEqualsShared(int addr, byte[] src, int srcOffset, int length) {
        int end = addr + length;
        int j = srcOffset;
        for (int i = addr; i < end; i++, j++) {
            if (shared[i] != src[j]) {
                return false;
            }
        }
        return true;
    }

    /** Refreshes {@link #zeroShared} after the snapshot changes (freeze). */
    private void recomputeZeroFrames() {
        for (int f = 0; f < FRAME_COUNT; f++) {
            int base = f << FRAME_SHIFT;
            boolean zero = true;
            for (int i = 0; i < FRAME_SIZE; i++) {
                if (shared[base + i] != 0) {
                    zero = false;
                    break;
                }
            }
            zeroShared[f] = zero;
        }
    }
}
