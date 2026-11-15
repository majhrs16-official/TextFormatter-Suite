package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.concurrent.atomic.AtomicLong;

import me.majhrs16.suite.iflow.vm.isa.v3.Csr;
import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.Status;

/**
 * Test helper that builds a sparse but complete v3 machine.
 */
public final class MachineFixture {

    public static final int RAM_SIZE = PhysicalMemory.MIN_RAM_SIZE;
    public static final int PTBR = PhysicalMemory.RAM_BASE;
    public static final int PTE_TABLE_BASE = PhysicalMemory.RAM_BASE + 0x0010_0000;
    public static final int FRAME_A = PhysicalMemory.RAM_BASE + 0x0080_0000;
    public static final int FRAME_B = PhysicalMemory.RAM_BASE + 0x0090_0000;

    public final CsrFile csrs = new CsrFile();
    public final AtomicLong clock = new AtomicLong(1_000_000L);
    public final MachineMemory memory;

    public MachineFixture() {
        this(new byte[0]);
    }

    public MachineFixture(byte[] rom) {
        memory = new MachineMemory(csrs, rom, RAM_SIZE, clock::get);
    }

    public void enableMmu(boolean supervisor) {
        csrs.write(Csr.PTBR, PTBR, 0);
        csrs.setStatus(Status.fromBits(Status.MMU | (supervisor ? Status.PRV : 0)));
    }

    public int mapPage(int virtualAddress, int frame, int flags) {
        int vpn1 = (virtualAddress >>> 22) & 0x3FF;
        int vpn2 = (virtualAddress >>> 12) & 0x3FF;
        int pteTable = pteTableAddress(virtualAddress);
        put32(PTBR + vpn1 * Integer.BYTES, pteTable | Mmu.PDE_V);
        int pteAddress = pteTable + vpn2 * Integer.BYTES;
        put32(pteAddress, frame | flags);
        return pteAddress;
    }

    public int pteTableAddress(int virtualAddress) {
        int vpn1 = (virtualAddress >>> 22) & 0x3FF;
        return PTE_TABLE_BASE + vpn1 * PhysicalMemory.PAGE_SIZE;
    }

    public int pteAddress(int virtualAddress) {
        int vpn1 = (virtualAddress >>> 22) & 0x3FF;
        int vpn2 = (virtualAddress >>> 12) & 0x3FF;
        return PTE_TABLE_BASE + vpn1 * PhysicalMemory.PAGE_SIZE
                + vpn2 * Integer.BYTES;
    }

    public void put32(int physicalAddress, int value) {
        for (int index = 0; index < Integer.BYTES; index++) {
            memory.physical().writeByte(physicalAddress + index, value >>> (8 * index));
        }
    }

    public int get32(int physicalAddress) {
        int value = 0;
        for (int index = 0; index < Integer.BYTES; index++) {
            value |= memory.physical().readByte(physicalAddress + index) << (8 * index);
        }
        return value;
    }

    public void loadPhysical(int physicalAddress, int... bytes) {
        for (int index = 0; index < bytes.length; index++) {
            memory.physical().writeByte(physicalAddress + index, bytes[index]);
        }
    }
}
