package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.iflow.vm.isa.v3.Csr;
import me.majhrs16.suite.iflow.vm.isa.v3.MemorySize;
import me.majhrs16.suite.iflow.vm.isa.v3.Status;

class CoreMemoryTest {

    private static final int TARGET = 0x0040_0000;
    private static final int CORE_ZERO_PTBR = PhysicalMemory.RAM_BASE + 0x0060_0000;
    private static final int CORE_ONE_PTBR = PhysicalMemory.RAM_BASE + 0x00E0_0000;

    @Test
    void coresSharePhysicalRamButKeepIndependentArchitecturalState() {
        MachineFixture fixture = new MachineFixture(2);
        CoreMemory coreZero = fixture.memory.core(0);
        CoreMemory coreOne = fixture.memory.core(1);

        coreZero.write(0x0205_0000, MemorySize.WORD, 0xCAFE_BABE);

        assertEquals(0xCAFE_BABE, coreOne.read(0x0205_0000, MemorySize.WORD));
        assertEquals(0, coreZero.coreId());
        assertEquals(1, coreOne.coreId());
        assertNotSame(coreZero.csrs(), coreOne.csrs());
        assertNotSame(coreZero.mmu(), coreOne.mmu());
    }

    @Test
    void eachCoreTranslatesWithItsOwnPageTableAndTlb() {
        MachineFixture fixture = new MachineFixture(2);
        CoreMemory coreZero = fixture.memory.core(0);
        CoreMemory coreOne = fixture.memory.core(1);
        map(fixture, CORE_ZERO_PTBR, MachineFixture.FRAME_A);
        map(fixture, CORE_ONE_PTBR, MachineFixture.FRAME_B);
        enableMmu(coreZero, CORE_ZERO_PTBR);
        enableMmu(coreOne, CORE_ONE_PTBR);

        coreZero.write(TARGET, MemorySize.WORD, 0x1111_1111);
        coreOne.write(TARGET, MemorySize.WORD, 0x2222_2222);

        assertEquals(0x1111_1111, coreZero.read(TARGET, MemorySize.WORD));
        assertEquals(0x2222_2222, coreOne.read(TARGET, MemorySize.WORD));
        assertEquals(0x1111_1111, fixture.get32(MachineFixture.FRAME_A));
        assertEquals(0x2222_2222, fixture.get32(MachineFixture.FRAME_B));
        assertTrue(coreZero.mmu().tlbSize() > 0);
        assertTrue(coreOne.mmu().tlbSize() > 0);
    }

    @Test
    void flushTlbOnlyInvalidatesTheCallingCore() {
        MachineFixture fixture = new MachineFixture(2);
        CoreMemory coreZero = fixture.memory.core(0);
        CoreMemory coreOne = fixture.memory.core(1);
        map(fixture, CORE_ZERO_PTBR, MachineFixture.FRAME_A);
        map(fixture, CORE_ONE_PTBR, MachineFixture.FRAME_B);
        enableMmu(coreZero, CORE_ZERO_PTBR);
        enableMmu(coreOne, CORE_ONE_PTBR);
        coreZero.write(TARGET, MemorySize.WORD, 0x1111_1111);
        coreOne.write(TARGET, MemorySize.WORD, 0x2222_2222);

        coreZero.flushTlb();

        assertEquals(0, coreZero.mmu().tlbSize());
        assertTrue(coreOne.mmu().tlbSize() > 0);
        assertEquals(0x1111_1111, coreZero.read(TARGET, MemorySize.WORD));
        assertEquals(0x2222_2222, coreOne.read(TARGET, MemorySize.WORD));
    }

    @Test
    void aStoreFromAnotherCoreDropsTheLinkedReservation() {
        MachineFixture fixture = new MachineFixture(2);
        CoreMemory coreZero = fixture.memory.core(0);
        CoreMemory coreOne = fixture.memory.core(1);
        int address = 0x0205_0100;

        assertEquals(0, coreZero.loadLinked(address));
        coreOne.write(address, MemorySize.BYTE, 1);

        assertFalse(coreZero.storeConditional(address, 2));
        assertEquals(1, fixture.memory.physical().readByte(address));
        assertEquals(1, coreZero.loadLinked(address));
        assertTrue(coreZero.storeConditional(address, 2));
        assertEquals(2, fixture.memory.physical().readByte(address));
    }

    @Test
    void coreFactoryRejectsIdsOutsideCoreCount() {
        MachineFixture fixture = new MachineFixture(2);

        assertThrows(IllegalArgumentException.class, () -> fixture.memory.core(-1));
        assertThrows(IllegalArgumentException.class, () -> fixture.memory.core(2));
    }

    @Test
    void changingASharedPageTableDoesNotFlushAnotherCoreTlb() {
        MachineFixture fixture = new MachineFixture(2);
        CoreMemory coreOne = fixture.memory.core(1);
        int pte = map(fixture, CORE_ONE_PTBR, MachineFixture.FRAME_A);
        enableMmu(coreOne, CORE_ONE_PTBR);

        assertEquals(0, coreOne.read(TARGET, MemorySize.WORD));
        assertTrue(coreOne.mmu().tlbSize() > 0);

        fixture.put32(pte, MachineFixture.FRAME_B | Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);
        fixture.put32(MachineFixture.FRAME_B, 0x2222_2222);

        assertEquals(0, coreOne.read(TARGET, MemorySize.WORD));
        coreOne.flushTlb();
        assertEquals(0x2222_2222, coreOne.read(TARGET, MemorySize.WORD));
    }

    private static int map(MachineFixture fixture, int ptbr, int frame) {
        int vpn1 = (TARGET >>> 22) & 0x3FF;
        int vpn2 = (TARGET >>> 12) & 0x3FF;
        int table = ptbr + PhysicalMemory.PAGE_SIZE;
        fixture.put32(ptbr + vpn1 * Integer.BYTES, table | Mmu.PDE_V);
        int pte = table + vpn2 * Integer.BYTES;
        fixture.put32(pte, frame | Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);
        return pte;
    }

    private static void enableMmu(CoreMemory core, int ptbr) {
        core.csrs().write(Csr.PTBR, ptbr, 0);
        core.csrs().setStatus(Status.fromBits(Status.MMU | Status.PRV));
    }
}
