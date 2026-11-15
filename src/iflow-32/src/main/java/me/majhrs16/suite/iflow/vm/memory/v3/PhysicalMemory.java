package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import me.majhrs16.suite.iflow.vm.isa.v3.MemoryFault;

/**
 * Physical address space of the v3 machine.
 *
 * <p>The map is fixed by ADR-037 §5.2: ROM in {@code 0x0000_0000}, MMIO in
 * {@code 0x0100_0000}, and RAM from {@code 0x0200_0000}. The implementation is
 * sparse: RAM frames are allocated on the first write, so a 64 MiB VM does not
 * reserve a flat 64 MiB array. MMIO is never handled here; the bus decodes it
 * before the MMU and before this store.</p>
 *
 * <p>This class deliberately knows nothing about virtual memory, CPU privilege
 * or devices. It reports {@link MemoryFault} with the physical address and lets
 * the MMU translate the cause and the bad address to the virtual side.</p>
 */
public final class PhysicalMemory {

    public static final int PAGE_SIZE = 4096;
    public static final int PAGE_MASK = PAGE_SIZE - 1;
    public static final int PAGE_SHIFT = 12;

    public static final int ROM_BASE = 0x0000_0000;
    public static final int ROM_WINDOW_SIZE = 0x0100_0000;
    public static final int MMIO_BASE = 0x0100_0000;
    public static final int MMIO_END = 0x0200_0000;
    public static final int RAM_BASE = 0x0200_0000;

    public static final int MIN_RAM_SIZE = 16 * 1024 * 1024;
    public static final int MAX_RAM_SIZE = 1024 * 1024 * 1024;

    private final byte[] rom;
    private final int romMappedSize;
    private final int ramSize;
    private final long ramEnd;
    private Map<Integer, byte[]> sharedFrames;
    private final Map<Integer, byte[]> ramFrames = new HashMap<>();

    public PhysicalMemory(byte[] rom, int ramSize) {
        Objects.requireNonNull(rom, "rom");
        if (rom.length > ROM_WINDOW_SIZE) {
            throw new IllegalArgumentException("ROM exceeds the 16 MiB window");
        }
        if (ramSize < MIN_RAM_SIZE || ramSize > MAX_RAM_SIZE) {
            throw new IllegalArgumentException("RAM size must be 16 MiB..1 GiB");
        }
        this.rom = rom.clone();
        this.romMappedSize = roundUpToPage(rom.length);
        this.ramSize = ramSize;
        this.ramEnd = Integer.toUnsignedLong(RAM_BASE) + ramSize;
        this.sharedFrames = Map.of();
    }

    private PhysicalMemory(byte[] rom, int romMappedSize, int ramSize,
                           Map<Integer, byte[]> sharedFrames) {
        this.rom = rom;
        this.romMappedSize = romMappedSize;
        this.ramSize = ramSize;
        this.ramEnd = Integer.toUnsignedLong(RAM_BASE) + ramSize;
        this.sharedFrames = sharedFrames;
    }

    public byte[] romBytes() {
        return rom.clone();
    }

    public int romSize() {
        return rom.length;
    }

    public int romMappedSize() {
        return romMappedSize;
    }

    public int ramSize() {
        return ramSize;
    }

    public long ramEnd() {
        return ramEnd;
    }

    public boolean isRom(int address) {
        return Integer.compareUnsigned(address, ROM_BASE) >= 0
                && Integer.compareUnsigned(address, ROM_BASE + romMappedSize) < 0;
    }

    public boolean isRam(int address) {
        return Integer.compareUnsigned(address, RAM_BASE) >= 0
                && Integer.toUnsignedLong(address) < ramEnd;
    }

    public boolean isMmio(int address) {
        return Integer.compareUnsigned(address, MMIO_BASE) >= 0
                && Integer.compareUnsigned(address, MMIO_END) < 0;
    }

    public boolean isMapped(int address) {
        return isRom(address) || isRam(address);
    }

    public int readByte(int address) {
        if (isRom(address)) {
            int offset = address - ROM_BASE;
            return offset < rom.length ? rom[offset] & 0xFF : 0;
        }
        if (isRam(address)) {
            byte[] frame = frameForRead(address);
            return frame == null ? 0 : frame[address & PAGE_MASK] & 0xFF;
        }
        throw MemoryFault.busError(address, "physical read outside ROM/RAM");
    }

    public void writeByte(int address, int value) {
        if (isRom(address)) {
            throw MemoryFault.store(address, "physical ROM is read-only");
        }
        if (isRam(address)) {
            int frameIndex = ramFrameIndex(address);
            byte[] frame = ramFrames.get(frameIndex);
            if (frame == null) {
                byte[] shared = sharedFrames.get(frameIndex);
                frame = shared == null ? new byte[PAGE_SIZE] : shared.clone();
                ramFrames.put(frameIndex, frame);
            }
            frame[address & PAGE_MASK] = (byte) value;
            return;
        }
        throw MemoryFault.busError(address, "physical write outside RAM");
    }

    /**
     * Copies bytes into RAM, as the loader does with a program segment.
     */
    public void load(int address, byte[] source, int offset, int length) {
        Objects.requireNonNull(source, "source");
        if (offset < 0 || length < 0 || offset > source.length - length) {
            throw new IllegalArgumentException("invalid source range");
        }
        for (int index = 0; index < length; index++) {
            long target = Integer.toUnsignedLong(address) + index;
            if (target > 0xFFFF_FFFFL) {
                throw MemoryFault.busError(address, "load crosses the 32-bit space");
            }
            writeByte((int) target, source[offset + index] & 0xFF);
        }
    }

    public byte[] read(int address, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("negative length");
        }
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            long source = Integer.toUnsignedLong(address) + index;
            if (source > 0xFFFF_FFFFL) {
                throw MemoryFault.busError(address, "read crosses the 32-bit space");
            }
            result[index] = (byte) readByte((int) source);
        }
        return result;
    }

    public int frameCount() {
        return ramFrames.size();
    }

    /**
     * Number of distinct RAM frames visible to this memory, shared or private.
     */
    public int residentFrameCount() {
        java.util.HashSet<Integer> indices = new java.util.HashSet<>(sharedFrames.keySet());
        indices.addAll(ramFrames.keySet());
        return indices.size();
    }

    /**
     * Publishes every currently visible frame as an immutable shared snapshot
     * and returns a second view of it.
     *
     * <p>The returned memory and this one share frame arrays. The first write
     * to a frame on either side copies only that frame, so a snapshot never
     * clones the 4 GiB space and two VMs cannot observe each other's stores.</p>
     */
    public PhysicalMemory snapshot() {
        Map<Integer, byte[]> visible = new HashMap<>(sharedFrames);
        visible.putAll(ramFrames);
        sharedFrames = Map.copyOf(visible);
        ramFrames.clear();
        return new PhysicalMemory(rom, romMappedSize, ramSize, sharedFrames);
    }

    private byte[] frameForRead(int address) {
        int index = ramFrameIndex(address);
        byte[] frame = ramFrames.get(index);
        return frame != null ? frame : sharedFrames.get(index);
    }

    private int ramFrameIndex(int address) {
        long offset = Integer.toUnsignedLong(address) - Integer.toUnsignedLong(RAM_BASE);
        return (int) (offset >>> PAGE_SHIFT);
    }

    private static int roundUpToPage(int value) {
        if (value == 0) {
            return 0;
        }
        return (int) (((value + (long) PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE);
    }

    @Override
    public String toString() {
        return "PhysicalMemory[rom=" + rom.length + "B, ram=" + ramSize
                + "B, frames=" + residentFrameCount() + "]";
    }

    /**
     * Clears all RAM frames visible to this memory. The ROM is immutable and a
     * snapshot that shares the old frames is not affected.
     */
    public void clearRam() {
        ramFrames.clear();
        sharedFrames = Map.of();
    }

    /**
     * Exposes a copy of a visible RAM frame for diagnostics.
     */
    public byte[] ramFrame(int frameIndex) {
        byte[] frame = ramFrames.containsKey(frameIndex)
                ? ramFrames.get(frameIndex)
                : sharedFrames.get(frameIndex);
        return frame == null ? null : Arrays.copyOf(frame, frame.length);
    }
}
