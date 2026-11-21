package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import me.majhrs16.suite.iflow.vm.isa.v3.MemoryFault;
import me.majhrs16.suite.iflow.vm.isa.v3.Status;

/**
 * Two-level, 4 KiB v3 MMU (ADR-037 §5.4).
 *
 * <p>Virtual addresses are split as {@code 10 + 10 + 12} bits. The page walk
 * reads a PDE and a PTE through {@link PhysicalMemory}; it never recurses
 * through the MMU. All faults are reported against the virtual address and the
 * access type, except a page table that lives outside physical memory, which is
 * a {@code BUS_ERROR}.</p>
 *
 * <p>The TLB is a cache, not a second page table. It is flushed when
 * {@code PTBR} changes, by {@code SFENCE.VMA}, and whenever the guest writes a
 * physical frame that has been used as a page table. That last rule keeps a
 * store to a live PTE from leaving a stale translation behind, which ADR-037
 * forbids.</p>
 */
public final class Mmu {

    public static final int PAGE_SIZE = PhysicalMemory.PAGE_SIZE;
    public static final int PAGE_MASK = PhysicalMemory.PAGE_MASK;
    public static final int PAGE_SHIFT = PhysicalMemory.PAGE_SHIFT;

    public static final int PTE_V = 1 << 0;
    public static final int PTE_R = 1 << 2;
    public static final int PTE_W = 1 << 3;
    public static final int PTE_X = 1 << 4;
    public static final int PTE_U = 1 << 5;
    public static final int PTE_A = 1 << 6;
    public static final int PTE_D = 1 << 7;
    public static final int PTE_G = 1 << 8;
    public static final int PTE_C = 1 << 9;
    public static final int PTE_S = 1 << 10;

    public static final int PDE_V = 1 << 0;

    private static final int PTE_RESERVED = 1 << 11 | 1 << 1;
    private static final int PDE_RESERVED = 0x0000_0FFE;

    private final PhysicalMemory physical;
    private final Map<Integer, TlbEntry> tlb = new HashMap<>();
    private final Set<Integer> pageTableFrames = new HashSet<>();
    private int observedPtbr = -1;

    public Mmu(PhysicalMemory physical) {
        this.physical = Objects.requireNonNull(physical, "physical");
    }

    public enum Access {
        READ,
        WRITE,
        EXECUTE
    }

    /**
     * Translates one virtual address.
     *
     * @param virtualAddress unsigned 32-bit virtual address
     * @param access         requested operation
     * @param status         current machine status
     * @param ptbr           current page-table base
     * @return the physical address
     */
    public int translate(int virtualAddress, Access access, Status status, int ptbr) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(status, "status");
        if ((ptbr & PAGE_MASK) != 0) {
            throw accessFault(access, virtualAddress, "PTBR is not page aligned");
        }
        invalidateIfPtbrChanged(ptbr);
        if (!status.mmuEnabled()) {
            return virtualAddress;
        }

        int virtualPage = virtualAddress >>> PAGE_SHIFT;
        TlbEntry cached = tlb.get(virtualPage);
        if (cached != null) {
            checkPermissions(cached.flags, access, status, virtualAddress);
            int flags = cached.flags;
            if ((flags & PTE_A) == 0) {
                flags |= PTE_A;
            }
            if (access == Access.WRITE) {
                flags |= PTE_D;
            }
            if (flags != cached.flags) {
                writePhysicalWord(cached.pteAddress, flags, virtualAddress);
                tlb.put(virtualPage, new TlbEntry(cached.frame, flags, cached.pteAddress));
            }
            return cached.frame | (virtualAddress & PAGE_MASK);
        }

        int vpn1 = (virtualAddress >>> 22) & 0x3FF;
        int vpn2 = (virtualAddress >>> 12) & 0x3FF;
        int pdeAddress = ptbr + vpn1 * Integer.BYTES;
        rememberPageTableFrame(pdeAddress);
        int pde = readPhysicalWord(pdeAddress, virtualAddress);
        if ((pde & PDE_RESERVED) != 0) {
            throw accessFault(access, virtualAddress, "PDE has reserved bits set");
        }
        if ((pde & PDE_V) == 0) {
            throw accessFault(access, virtualAddress, "PDE is not valid");
        }

        int pteAddress = (pde & 0xFFFF_F000) | vpn2 * Integer.BYTES;
        rememberPageTableFrame(pteAddress);
        int pte = readPhysicalWord(pteAddress, virtualAddress);
        if ((pte & PTE_RESERVED) != 0) {
            throw accessFault(access, virtualAddress, "PTE has reserved bits set");
        }
        if ((pte & PTE_V) == 0) {
            throw accessFault(access, virtualAddress, "PTE is not valid");
        }
        if ((pte & PTE_W) != 0 && (pte & PTE_X) != 0) {
            throw accessFault(access, virtualAddress, "W^X page is invalid");
        }

        checkPermissions(pte, access, status, virtualAddress);
        int frame = pte & 0xFFFF_F000;
        if (frame >= PhysicalMemory.MMIO_BASE && frame < PhysicalMemory.MMIO_END) {
            throw MemoryFault.busError(virtualAddress, "PTE maps the MMIO aperture");
        }
        if (!physical.isMapped(frame)) {
            throw MemoryFault.busError(virtualAddress,
                    "PTE frame 0x" + Integer.toHexString(frame) + " is outside ROM/RAM");
        }

        int updated = pte | PTE_A;
        if (access == Access.WRITE) {
            updated |= PTE_D;
        }
        if (updated != pte) {
            writePhysicalWord(pteAddress, updated, virtualAddress);
        }
        tlb.put(virtualPage, new TlbEntry(frame, updated, pteAddress));
        return frame | (virtualAddress & PAGE_MASK);
    }

    /**
     * Invalidates the TLB after a page table mutation.
     */
    public void flush() {
        tlb.clear();
        pageTableFrames.clear();
    }

    /**
     * Called after a guest store to {@code physicalAddress}. If the store hit a
     * frame that has participated in a page walk, the cached translations are no
     * longer trustworthy.
     */
    public void notePhysicalWrite(int physicalAddress) {
        int frame = physicalAddress & 0xFFFF_F000;
        if (pageTableFrames.contains(frame)) {
            tlb.clear();
        }
    }

    public int tlbSize() {
        return tlb.size();
    }

    private void invalidateIfPtbrChanged(int ptbr) {
        if (observedPtbr != ptbr) {
            observedPtbr = ptbr;
            tlb.clear();
            pageTableFrames.clear();
        }
    }

    private void rememberPageTableFrame(int address) {
        pageTableFrames.add(address & 0xFFFF_F000);
    }

    private int readPhysicalWord(int address, int virtualAddress) {
        try {
            return physical.readWord(address);
        } catch (MemoryFault fault) {
            throw MemoryFault.busError(virtualAddress,
                    "page walk failed at physical 0x" + Integer.toHexString(address)
                            + ": " + fault.detail());
        }
    }

    private void writePhysicalWord(int address, int value, int virtualAddress) {
        try {
            physical.writeWord(address, value);
        } catch (MemoryFault fault) {
            throw MemoryFault.busError(virtualAddress,
                    "page table update failed at physical 0x"
                            + Integer.toHexString(address) + ": " + fault.detail());
        }
    }

    private static void checkPermissions(int flags, Access access, Status status,
                                         int virtualAddress) {
        if (!status.supervisor() && (flags & PTE_U) == 0) {
            throw accessFault(access, virtualAddress,
                    "supervisor page is not accessible from user mode");
        }
        switch (access) {
            case READ -> {
                if ((flags & PTE_R) == 0) {
                    throw MemoryFault.load(virtualAddress, "page is not readable");
                }
            }
            case WRITE -> {
                if ((flags & PTE_W) == 0) {
                    throw MemoryFault.store(virtualAddress, "page is not writable");
                }
                if ((flags & (PTE_C | PTE_S)) != 0) {
                    throw MemoryFault.store(virtualAddress,
                            "copy-on-write or snapshot page requires a kernel copy");
                }
            }
            case EXECUTE -> {
                if ((flags & PTE_X) == 0) {
                    throw MemoryFault.instructionFault(virtualAddress,
                            "page is not executable");
                }
            }
            default -> throw new IllegalStateException("Unknown access: " + access);
        }
    }

    private static MemoryFault accessFault(Access access, int address, String detail) {
        return switch (access) {
            case READ -> MemoryFault.load(address, detail);
            case WRITE -> MemoryFault.store(address, detail);
            case EXECUTE -> MemoryFault.instructionFault(address, detail);
        };
    }

    private record TlbEntry(int frame, int flags, int pteAddress) {
    }
}
