package me.majhrs16.suite.iflow.vm.isa;

import me.majhrs16.suite.iflow.vm.memory.PageEntry;

import java.util.Arrays;
import java.util.Objects;

/**
 * One segment described by the ROM TOC.
 * <p>
 * {@link #data()} is the stored payload; {@link #memSize()} is the number of
 * bytes the loader must reserve at {@link #virtualAddress()} (it may be larger
 * than the stored payload, in which case the tail is zero-filled). Metadata
 * segments are never loaded and always have {@code memSize == 0}.
 * </p>
 * <p>
 * Permission flags deliberately use the same bit values as {@link PageEntry}:
 * {@code 1 = read}, {@code 2 = write}, {@code 4 = execute} and {@code 8 = user}.
 * </p>
 */
public final class ProgramSegment {

    public static final int FLAG_READ = PageEntry.PERM_READ;
    public static final int FLAG_WRITE = PageEntry.PERM_WRITE;
    public static final int FLAG_EXECUTE = PageEntry.PERM_EXECUTE;
    public static final int FLAG_USER = 0x8;
    public static final int FLAG_MASK =
        FLAG_READ | FLAG_WRITE | FLAG_EXECUTE | FLAG_USER;

    private final ProgramSegmentType type;
    private final int virtualAddress;
    private final byte[] data;
    private final int memSize;
    private final int flags;

    public ProgramSegment(ProgramSegmentType type, int virtualAddress,
                          byte[] data, int memSize, int flags) {
        if (type == null) {
            throw new IllegalArgumentException("Segment type is required");
        }
        if (virtualAddress < 0 || virtualAddress > 0xFFFF) {
            throw new IllegalArgumentException(
                "Segment virtual address outside the 16-bit space: 0x"
                    + Integer.toHexString(virtualAddress));
        }
        if (memSize < 0 || memSize > 0xFFFF) {
            throw new IllegalArgumentException("Invalid segment memory size: " + memSize);
        }
        if ((flags & ~FLAG_MASK) != 0) {
            throw new IllegalArgumentException(
                "Unknown segment flags: 0x" + Integer.toHexString(flags));
        }
        byte[] stored = data != null ? data.clone() : new byte[0];
        if (type.isLoadable()) {
            if (stored.length == 0) {
                throw new IllegalArgumentException("Loadable segment cannot be empty");
            }
            if (memSize < stored.length) {
                throw new IllegalArgumentException(
                    "Segment memory size " + memSize
                        + " is smaller than its stored payload " + stored.length);
            }
        } else if (memSize != 0) {
            throw new IllegalArgumentException("Metadata segment cannot reserve memory");
        }
        this.type = type;
        this.virtualAddress = virtualAddress;
        this.data = stored;
        this.memSize = memSize;
        this.flags = flags;
    }

    public ProgramSegmentType type() {
        return type;
    }

    public int virtualAddress() {
        return virtualAddress;
    }

    public byte[] data() {
        return data.clone();
    }

    /** Raw payload for the loader; callers must not mutate it. */
    byte[] rawData() {
        return data;
    }

    public int size() {
        return data.length;
    }

    public int memSize() {
        return memSize;
    }

    public int flags() {
        return flags;
    }

    public int permissions() {
        return flags & (FLAG_READ | FLAG_WRITE | FLAG_EXECUTE);
    }

    public boolean isUserOwned() {
        return (flags & FLAG_USER) != 0;
    }

    public boolean isLoadable() {
        return type.isLoadable();
    }

    public boolean isExecutable() {
        return type.isExecutableType();
    }

    /** Exclusive end of the reserved virtual range. */
    public int endAddress() {
        return virtualAddress + memSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProgramSegment that)) return false;
        return virtualAddress == that.virtualAddress
            && memSize == that.memSize
            && flags == that.flags
            && type == that.type
            && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, virtualAddress, memSize, flags);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "ProgramSegment{" + type + ", vaddr=0x"
            + Integer.toHexString(virtualAddress) + ", size=" + data.length
            + ", memSize=" + memSize + ", flags=0x" + Integer.toHexString(flags) + "}";
    }
}
