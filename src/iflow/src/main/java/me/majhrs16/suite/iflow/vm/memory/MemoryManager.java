package me.majhrs16.suite.iflow.vm.memory;

import me.majhrs16.suite.iflow.vm.isa.VmException;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages memory regions for a VM execution.
 * <p>
 * Regions are identified by 16-bit handles. The manager enforces permissions,
 * bounds, and ownership. Regions can be shared (code, constants, firmware)
 * or private per execution (stack, private data, buffers, journal).
 * </p>
 */
public final class MemoryManager {

    private final Map<Integer, MemoryRegion> regions = new HashMap<>();
    private int nextHandle = 1; // 0 reserved as invalid

    // Limits (configurable)
    private int maxRegions = 64;
    private int maxTotalMemory = 256 * 1024; // 256 KiB default
    private int currentTotalMemory = 0;

    public MemoryManager() {}

    public MemoryManager maxRegions(int max) { this.maxRegions = max; return this; }
    public MemoryManager maxTotalMemory(int max) { this.maxTotalMemory = max; return this; }

    /**
     * Creates a new region and returns its handle.
     */
    public int createRegion(RegionType type, int permissions, String owner,
                            int initialSize, int maxSize, boolean persistent) {
        if (regions.size() >= maxRegions) {
            throw new VmException(VmException.Code.INVALID_REGION,
                "Maximum region count exceeded: " + maxRegions);
        }
        if (currentTotalMemory + initialSize > maxTotalMemory) {
            throw new VmException(VmException.Code.REGION_BOUNDS,
                "Total memory limit exceeded");
        }

        int handle = nextHandle++;
        if (nextHandle > 0xFFFF) nextHandle = 1; // Wrap (avoid 0)

        MemoryRegion region = new MemoryRegion(handle, type, permissions, owner,
            initialSize, maxSize, persistent);
        regions.put(handle, region);
        currentTotalMemory += initialSize;
        return handle;
    }

    /** Creates a shared read-only region (code, constants, firmware). */
    public int createSharedRegion(RegionType type, String owner, byte[] data) {
        int perms = RegionPerm.combine(RegionPerm.READ, 
            type == RegionType.CODE ? RegionPerm.EXECUTE : null);
        int handle = nextHandle++;
        if (nextHandle > 0xFFFF) nextHandle = 1;
        
        MemoryRegion region = new MemoryRegion(handle, type, perms, owner, data, false);
        regions.put(handle, region);
        currentTotalMemory += data.length;
        return handle;
    }

    /** Creates a private data region for an execution. */
    public int createPrivateData(String owner, int initialSize, int maxSize) {
        return createRegion(RegionType.PRIVATE_DATA,
            RegionPerm.combine(RegionPerm.READ, RegionPerm.WRITE),
            owner, initialSize, maxSize, false);
    }

    /** Creates a stack region for an execution. */
    public int createStack(String owner, int size) {
        return createRegion(RegionType.STACK,
            RegionPerm.combine(RegionPerm.READ, RegionPerm.WRITE),
            owner, size, size, false);
    }

    /** Creates an input buffer region. */
    public int createInputBuffer(String owner, int maxSize) {
        return createRegion(RegionType.INPUT_BUFFER,
            RegionPerm.combine(RegionPerm.READ),
            owner, 0, maxSize, false);
    }

    /** Creates an output buffer region. */
    public int createOutputBuffer(String owner, int maxSize) {
        return createRegion(RegionType.OUTPUT_BUFFER,
            RegionPerm.combine(RegionPerm.WRITE),
            owner, 0, maxSize, false);
    }

    /** Creates a journal region. */
    public int createJournal(String owner, int maxSize) {
        return createRegion(RegionType.JOURNAL,
            RegionPerm.combine(RegionPerm.READ, RegionPerm.WRITE),
            owner, 0, maxSize, false);
    }

    /** Creates a persistent state region (requires capability). */
    public int createPersistentState(String owner, int maxSize) {
        return createRegion(RegionType.PERSISTENT_STATE,
            RegionPerm.combine(RegionPerm.READ, RegionPerm.WRITE),
            owner, 0, maxSize, true);
    }

    public MemoryRegion getRegion(int handle) {
        MemoryRegion region = regions.get(handle);
        if (region == null) {
            throw new VmException(VmException.Code.INVALID_REGION, "Region not found: " + handle);
        }
        return region;
    }

    public boolean hasRegion(int handle) {
        return regions.containsKey(handle);
    }

    public void removeRegion(int handle) {
        MemoryRegion region = regions.remove(handle);
        if (region != null) {
            currentTotalMemory -= region.currentSize();
        }
    }

    /** Clears all non-persistent regions (called between executions). */
    public void clearTransient(String executionOwner) {
        regions.entrySet().removeIf(entry -> {
            MemoryRegion r = entry.getValue();
            if (!r.persistent() && r.owner().equals(executionOwner)) {
                currentTotalMemory -= r.currentSize();
                return true;
            }
            return false;
        });
    }

    /** Clears all regions for a given owner. */
    public void clearOwner(String owner) {
        regions.entrySet().removeIf(entry -> {
            if (entry.getValue().owner().equals(owner)) {
                currentTotalMemory -= entry.getValue().currentSize();
                return true;
            }
            return false;
        });
    }

    public int regionCount() { return regions.size(); }
    public int totalMemory() { return currentTotalMemory; }
    public int maxTotalMemory() { return maxTotalMemory; }
    public int maxRegions() { return maxRegions; }

    /** Validates that a region exists, is owned by the given owner, and has the required permission. */
    public MemoryRegion validateAccess(int handle, String owner, RegionPerm requiredPerm) {
        MemoryRegion region = getRegion(handle);
        if (!region.owner().equals(owner)) {
            throw new VmException(VmException.Code.PRIVILEGE_FAULT,
                "Region " + handle + " owned by " + region.owner() + ", not " + owner);
        }
        if (!RegionPerm.has(region.permissions(), requiredPerm)) {
            throw new VmException(VmException.Code.REGION_PERMISSION,
                "Region " + handle + " missing permission: " + requiredPerm);
        }
        return region;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("MemoryManager{regions=").append(regions.size())
            .append(", memory=").append(currentTotalMemory).append("/").append(maxTotalMemory).append("B}\n");
        for (MemoryRegion r : regions.values()) {
            sb.append("  ").append(r).append("\n");
        }
        return sb.toString();
    }
}