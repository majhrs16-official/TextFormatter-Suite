package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.Objects;

import me.majhrs16.suite.iflow.vm.isa.v3.AtomicMemory;
import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.DataMemory;
import me.majhrs16.suite.iflow.vm.isa.v3.InstructionReader;
import me.majhrs16.suite.iflow.vm.isa.v3.InterruptController;
import me.majhrs16.suite.iflow.vm.isa.v3.MachineFault;
import me.majhrs16.suite.iflow.vm.isa.v3.MemoryFault;
import me.majhrs16.suite.iflow.vm.isa.v3.MemorySize;

/**
 * Per-core view of the v3 machine memory.
 *
 * <p>The physical bus, ROM/RAM, MMIO devices and the atomic reservation map are
 * shared by every core of the same VM. The architectural CSR file and the TLB
 * belong to the core: two cores may use different page tables, and
 * {@code SFENCE.VMA} only flushes the TLB of the core that executes it.</p>
 *
 * @see MachineMemory#core(int)
 */
public final class CoreMemory
        implements DataMemory, InstructionReader, InterruptController, AtomicMemory {

    private final MachineMemory machine;
    private final CsrFile csrs;
    private final Mmu mmu;

    CoreMemory(MachineMemory machine, CsrFile csrs) {
        this.machine = Objects.requireNonNull(machine, "machine");
        this.csrs = Objects.requireNonNull(csrs, "csrs");
        this.mmu = new Mmu(machine.physical());
    }

    public CsrFile csrs() {
        return csrs;
    }

    public Mmu mmu() {
        return mmu;
    }

    public int coreId() {
        return csrs.coreId();
    }

    @Override
    public int read(int address, MemorySize size) {
        Objects.requireNonNull(size, "size");
        if (machine.physical().isMmio(address)) {
            return machine.readMmio(csrs, address, size);
        }
        if (!csrs.status().mmuEnabled() && !csrs.status().supervisor()) {
            throw MemoryFault.privilege(address, "MMU is disabled and user mode is not allowed");
        }
        int[] physicalAddresses = translateBytes(address, size, Mmu.Access.READ);
        int wordBase = contiguousWordBase(size, physicalAddresses);
        if (wordBase >= 0) {
            synchronized (machine.atomicLock()) {
                return machine.physical().readWord(wordBase);
            }
        }
        int value = 0;
        for (int index = 0; index < physicalAddresses.length; index++) {
            value |= machine.physical().readByte(physicalAddresses[index]) << (8 * index);
        }
        return value;
    }

    @Override
    public void write(int address, MemorySize size, int value) {
        Objects.requireNonNull(size, "size");
        if (machine.physical().isMmio(address)) {
            machine.writeMmio(csrs, address, size, value);
            return;
        }
        if (!csrs.status().mmuEnabled() && !csrs.status().supervisor()) {
            throw MemoryFault.privilege(address, "MMU is disabled and user mode is not allowed");
        }
        int[] physicalAddresses = translateBytes(address, size, Mmu.Access.WRITE);
        int wordBase = contiguousWordBase(size, physicalAddresses);
        if (wordBase >= 0) {
            synchronized (machine.atomicLock()) {
                for (int physicalAddress : physicalAddresses) {
                    machine.invalidateOtherReservations(coreId(), physicalAddress);
                }
                machine.physical().writeWord(wordBase, value);
                for (int physicalAddress : physicalAddresses) {
                    mmu.notePhysicalWrite(physicalAddress);
                }
            }
            return;
        }
        for (int index = 0; index < physicalAddresses.length; index++) {
            int physicalAddress = physicalAddresses[index];
            machine.invalidateOtherReservations(coreId(), physicalAddress);
            machine.physical().writeByte(physicalAddress, value >>> (8 * index));
            mmu.notePhysicalWrite(physicalAddress);
        }
    }

    @Override
    public int readByte(int address) {
        if (machine.physical().isMmio(address)) {
            throw MachineFault.instructionFault(address,
                    "MMIO is not executable");
        }
        try {
            int physicalAddress = csrs.status().mmuEnabled()
                    ? mmu.translate(address, Mmu.Access.EXECUTE, csrs.status(), csrs.ptbr())
                    : address;
            if (!csrs.status().mmuEnabled() && !csrs.status().supervisor()) {
                throw MemoryFault.privilege(address, "MMU is disabled and user mode is not allowed");
            }
            return machine.physical().readByte(physicalAddress);
        } catch (MemoryFault fault) {
            throw new MachineFault(fault.code(), address, fault.badAddress(), fault.detail());
        }
    }

    @Override
    public void fence() {
        machine.timer().tick();
    }

    @Override
    public void flushTlb() {
        mmu.flush();
    }

    @Override
    public void poll() {
        machine.timer().tick();
    }

    @Override
    public int takePending() {
        machine.timer().tick();
        return machine.pic().takePending(coreId());
    }

    // ===== Atomics =====

    @Override
    public int loadLinked(int address) {
        synchronized (machine.atomicLock()) {
            int physicalAddress = atomicAddress(address, Mmu.Access.READ);
            int value = readAtomicWord(physicalAddress, address);
            machine.reservations().put(coreId(), physicalAddress);
            return value;
        }
    }

    @Override
    public boolean storeConditional(int address, int value) {
        synchronized (machine.atomicLock()) {
            int physicalAddress = atomicAddress(address, Mmu.Access.WRITE);
            Integer reserved = machine.reservations().remove(coreId());
            if (reserved == null || reserved != physicalAddress) {
                return false;
            }
            writeAtomicWord(physicalAddress, value, address);
            machine.invalidateOtherReservations(coreId(), physicalAddress);
            return true;
        }
    }

    @Override
    public int atomic(int address, Operation operation, int operand) {
        synchronized (machine.atomicLock()) {
            int physicalAddress = atomicAddress(address, Mmu.Access.READ);
            atomicAddress(address, Mmu.Access.WRITE);
            int previous = readAtomicWord(physicalAddress, address);
            int result = switch (operation) {
                case ADD -> previous + operand;
                case SWAP -> operand;
                case AND -> previous & operand;
                case OR -> previous | operand;
            };
            writeAtomicWord(physicalAddress, result, address);
            machine.invalidateOtherReservations(coreId(), physicalAddress);
            return previous;
        }
    }

    @Override
    public void clearReservation() {
        synchronized (machine.atomicLock()) {
            machine.reservations().remove(coreId());
        }
    }

    public void reset() {
        mmu.flush();
        clearReservation();
    }

    private int atomicAddress(int address, Mmu.Access access) {
        if (machine.physical().isMmio(address)) {
            throw MemoryFault.busError(address, "atomic access to MMIO");
        }
        int physicalAddress = translateBytes(address, MemorySize.WORD, access)[0];
        if (machine.physical().isMmio(physicalAddress)) {
            throw MemoryFault.busError(address, "atomic access to MMIO");
        }
        return physicalAddress;
    }

    private int readAtomicWord(int physicalAddress, int virtualAddress) {
        try {
            return machine.physical().readWord(physicalAddress);
        } catch (MemoryFault fault) {
            throw MemoryFault.busError(virtualAddress,
                    "atomic read failed: " + fault.detail());
        }
    }

    private void writeAtomicWord(int physicalAddress, int value, int virtualAddress) {
        try {
            machine.physical().writeWord(physicalAddress, value);
            for (int index = 0; index < Integer.BYTES; index++) {
                mmu.notePhysicalWrite(physicalAddress + index);
            }
        } catch (MemoryFault fault) {
            throw MemoryFault.busError(virtualAddress,
                    "atomic write failed: " + fault.detail());
        }
    }

    private static int contiguousWordBase(MemorySize size, int[] physicalAddresses) {
        if (size != MemorySize.WORD) {
            return -1;
        }
        int base = physicalAddresses[0];
        for (int index = 1; index < physicalAddresses.length; index++) {
            if (physicalAddresses[index] != base + index) {
                return -1;
            }
        }
        return base;
    }

    private int[] translateBytes(int address, MemorySize size, Mmu.Access access) {
        checkAlignment(address, size);
        int[] physicalAddresses = new int[size.bytes()];
        for (int index = 0; index < physicalAddresses.length; index++) {
            long current = Integer.toUnsignedLong(address) + index;
            if (current > 0xFFFF_FFFFL) {
                throw MemoryFault.busError(address, "access crosses the 32-bit space");
            }
            physicalAddresses[index] = csrs.status().mmuEnabled()
                    ? mmu.translate((int) current, access, csrs.status(), csrs.ptbr())
                    : (int) current;
        }
        return physicalAddresses;
    }

    private static void checkAlignment(int address, MemorySize size) {
        int mask = size.bytes() - 1;
        if ((address & mask) != 0) {
            throw MemoryFault.alignment(address,
                    "unaligned " + size.bytes() + "-byte access");
        }
    }
}
