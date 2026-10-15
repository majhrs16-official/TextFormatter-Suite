package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.isa.VmException;

/**
 * A memory region with handle-based addressing.
 * <p>
 * Each region has a 16-bit handle, a type, permissions, owner, and backing storage.
 * Regions are managed by the MemoryManager and accessed via handle + offset.
 * </p>
 */
public final class MemoryRegion {

    private final int handle;
    private final RegionType type;
    private final int permissions;
    private final String owner;
    private final long maxSize;
    private final byte[] backing;
    private final boolean persistent;

    public MemoryRegion(int handle, RegionType type, int permissions,
                        String owner, int initialSize, int maxSize, boolean persistent) {
        this.handle = handle;
        this.type = type;
        this.permissions = permissions;
        this.owner = owner;
        this.maxSize = maxSize;
        this.backing = new byte[Math.min(initialSize, maxSize)];
        this.persistent = persistent;
    }

    /** Creates a region with pre-initialized data. */
    public MemoryRegion(int handle, RegionType type, int permissions,
                        String owner, byte[] data, boolean persistent) {
        this.handle = handle;
        this.type = type;
        this.permissions = permissions;
        this.owner = owner;
        this.maxSize = data.length;
        this.backing = data.clone();
        this.persistent = persistent;
    }

    public int handle() { return handle; }
    public RegionType type() { return type; }
    public int permissions() { return permissions; }
    public String owner() { return owner; }
    public long maxSize() { return maxSize; }
    public int currentSize() { return backing.length; }
    public boolean persistent() { return persistent; }

    public boolean canRead() { return RegionPerm.has(permissions, RegionPerm.READ); }
    public boolean canWrite() { return RegionPerm.has(permissions, RegionPerm.WRITE); }
    public boolean canExecute() { return RegionPerm.has(permissions, RegionPerm.EXECUTE); }

    /** Reads a byte at the given offset. */
    public int readByte(int offset) {
        checkBounds(offset, 1);
        return backing[offset] & 0xFF;
    }

    /** Reads a 16-bit word (little-endian) at the given offset. */
    public int readWord(int offset) {
        checkBounds(offset, 2);
        return (backing[offset] & 0xFF) | ((backing[offset + 1] & 0xFF) << 8);
    }

    /** Writes a byte at the given offset. */
    public void writeByte(int offset, int value) {
        checkWrite();
        checkBounds(offset, 1);
        backing[offset] = (byte) (value & 0xFF);
    }

    /** Writes a 16-bit word (little-endian) at the given offset. */
    public void writeWord(int offset, int value) {
        checkWrite();
        checkBounds(offset, 2);
        backing[offset] = (byte) (value & 0xFF);
        backing[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /** Copies a block of bytes from this region to a destination array. */
    public void readBlock(int offset, byte[] dest, int destOffset, int length) {
        checkBounds(offset, length);
        System.arraycopy(backing, offset, dest, destOffset, length);
    }

    /** Copies a block of bytes from a source array to this region. */
    public void writeBlock(int offset, byte[] src, int srcOffset, int length) {
        checkWrite();
        checkBounds(offset, length);
        System.arraycopy(src, srcOffset, backing, offset, length);
    }

    /** Grows the region if within maxSize. */
    public void ensureCapacity(int requiredSize) {
        if (requiredSize > maxSize) {
            throw new VmException(VmException.Code.REGION_BOUNDS,
                "Region " + handle + " cannot grow beyond maxSize " + maxSize);
        }
        if (requiredSize > backing.length) {
            byte[] newBacking = new byte[requiredSize];
            System.arraycopy(backing, 0, newBacking, 0, backing.length);
            // Note: can't actually replace backing array since it's final
            // In practice, we'd use a different structure or pre-allocate max
            throw new UnsupportedOperationException("Region growth not yet implemented");
        }
    }

    private void checkBounds(int offset, int length) {
        if (offset < 0 || length < 0) {
            throw new VmException(VmException.Code.REGION_BOUNDS,
                "Negative offset/length: offset=" + offset + " length=" + length);
        }
        long end = (long) offset + length;
        if (end > backing.length) {
            throw new VmException(VmException.Code.REGION_BOUNDS,
                "Access beyond region end: offset=" + offset + " length=" + length + " size=" + backing.length);
        }
    }

    private void checkWrite() {
        if (!canWrite()) {
            throw new VmException(VmException.Code.REGION_PERMISSION,
                "Write to read-only region " + handle);
        }
    }

    /** Returns a copy of the backing array (for snapshots). */
    public byte[] snapshot() {
        return backing.clone();
    }

    /**
     * Safe handle to a memory region, bound to an execution context.
     * <p>
     * Provides checked access to region operations with fuel accounting.
     * </p>
     */
    public static final class Handle {

        private final MemoryRegion region;
        private final ExecutionContext context;

        public Handle(MemoryRegion region, ExecutionContext context) {
            this.region = region;
            this.context = context;
        }

        public int handle() { return region.handle(); }
        public RegionType type() { return region.type(); }
        public int permissions() { return region.permissions(); }
        public String owner() { return region.owner(); }
        public int size() { return region.currentSize(); }
        public int maxSize() { return (int) region.maxSize(); }
        public boolean persistent() { return region.persistent(); }

        public boolean canRead() { return region.canRead(); }
        public boolean canWrite() { return region.canWrite(); }
        public boolean canExecute() { return region.canExecute(); }

        // ===== Read operations =====

        public int readByte(int offset) {
            checkRead();
            return region.readByte(offset);
        }

        public int readWord(int offset) {
            checkRead();
            return region.readWord(offset);
        }

        public void readBlock(int offset, byte[] dest, int destOffset, int length) {
            checkRead();
            region.readBlock(offset, dest, destOffset, length);
        }

        // ===== Write operations =====

        public void writeByte(int offset, int value) {
            checkWrite();
            chargeWrite(1);
            region.writeByte(offset, value);
        }

        public void writeWord(int offset, int value) {
            checkWrite();
            chargeWrite(2);
            region.writeWord(offset, value);
        }

        public void writeBlock(int offset, byte[] src, int srcOffset, int length) {
            checkWrite();
            chargeWrite(length);
            region.writeBlock(offset, src, srcOffset, length);
        }

        // ===== Block operations (COPY/MOVE) =====

        /** Copies from this region to another (non-overlapping). */
        public void copyTo(Handle dest, int srcOffset, int destOffset, int length) {
            checkRead();
            dest.checkWrite();
            chargeWrite(length);
            // Validate non-overlap if same region
            if (this.region == dest.region) {
                long srcEnd = (long) srcOffset + length;
                long destEnd = (long) destOffset + length;
                if (!(srcEnd <= destOffset || destEnd <= srcOffset)) {
                    throw new VmException(VmException.Code.REGION_BOUNDS,
                        "COPY: overlapping regions not allowed");
                }
            }
            byte[] buffer = new byte[length];
            region.readBlock(srcOffset, buffer, 0, length);
            dest.region.writeBlock(destOffset, buffer, 0, length);
        }

        /** Moves from this region to another (allows overlap, memmove semantics). */
        public void moveTo(Handle dest, int srcOffset, int destOffset, int length) {
            checkRead();
            dest.checkWrite();
            chargeWrite(length);
            // Use temporary buffer for memmove semantics
            byte[] buffer = new byte[length];
            region.readBlock(srcOffset, buffer, 0, length);
            dest.region.writeBlock(destOffset, buffer, 0, length);
        }

        private void checkRead() {
            if (!canRead()) {
                throw new VmException(VmException.Code.REGION_PERMISSION,
                    "Read from non-readable region " + region.handle());
            }
        }

        private void checkWrite() {
            if (!canWrite()) {
                throw new VmException(VmException.Code.REGION_PERMISSION,
                    "Write to non-writable region " + region.handle());
            }
        }

        private void chargeWrite(int bytes) {
            // Fuel cost: 1 per 16 bytes (configurable)
            long cost = (bytes + 15) / 16;
            context.consumeFuel(cost);
        }

        @Override
        public String toString() {
            return "Handle#" + region.handle() + "[" + region.type() + "]";
        }
    }

    @Override
    public String toString() {
        StringBuilder perms = new StringBuilder();
        if (canRead()) perms.append('R');
        if (canWrite()) perms.append('W');
        if (canExecute()) perms.append('X');
        return "Region#" + handle + "[" + type + "] " + perms
            + " owner=" + owner + " size=" + backing.length + "/" + maxSize
            + (persistent ? " persistent" : "");
    }
}