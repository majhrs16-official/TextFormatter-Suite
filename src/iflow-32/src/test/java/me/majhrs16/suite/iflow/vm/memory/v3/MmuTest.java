package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.iflow.vm.isa.v3.Csr;
import me.majhrs16.suite.iflow.vm.isa.v3.MachineFault;
import me.majhrs16.suite.iflow.vm.isa.v3.MemoryFault;
import me.majhrs16.suite.iflow.vm.isa.v3.MemorySize;

class MmuTest {

    private static final int TARGET = 0x4000_0000;
    private static final int PTE_VIEW = 0x5000_0000;

    @Test
    void mmuOffUsesPhysicalAddresses() {
        MachineFixture fixture = new MachineFixture();

        fixture.memory.write(0x0205_0000, MemorySize.WORD, 0x1122_3344);

        assertEquals(0x1122_3344, fixture.memory.read(0x0205_0000, MemorySize.WORD));
        assertEquals(0x1122_3344, fixture.get32(0x0205_0000));
    }

    @Test
    void twoLevelWalkTranslatesAndSetsAccessedAndDirty() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        int pte = fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);

        fixture.memory.write(TARGET + 8, MemorySize.WORD, 0xCAFE_BABE);

        assertEquals(0xCAFE_BABE,
                fixture.memory.read(TARGET + 8, MemorySize.WORD));
        assertEquals(0xCAFE_BABE, fixture.get32(MachineFixture.FRAME_A + 8));
        int flags = fixture.get32(pte);
        assertTrue((flags & Mmu.PTE_A) != 0);
        assertTrue((flags & Mmu.PTE_D) != 0);
        assertTrue(fixture.memory.mmu().tlbSize() > 0);
    }

    @Test
    void tlbHitStillMarksDirtyAndAccessedOnThePageTable() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        int pte = fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);

        assertEquals(0, fixture.memory.read(TARGET, MemorySize.WORD));
        assertTrue((fixture.get32(pte) & Mmu.PTE_A) != 0);
        assertFalse((fixture.get32(pte) & Mmu.PTE_D) != 0);

        fixture.memory.write(TARGET, MemorySize.WORD, 0x1234_5678);

        assertEquals(0x1234_5678, fixture.get32(MachineFixture.FRAME_A));
        assertTrue((fixture.get32(pte) & Mmu.PTE_D) != 0);
    }

    @Test
    void userCanAccessOnlyUserPages() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(false);
        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W | Mmu.PTE_U);

        assertEquals(0, fixture.memory.read(TARGET, MemorySize.WORD));

        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);
        fixture.memory.mmu().flush();
        MemoryFault fault = assertThrows(MemoryFault.class,
                () -> fixture.memory.read(TARGET, MemorySize.WORD));
        assertEquals(MachineFault.Code.LOAD_FAULT, fault.code());
    }

    @Test
    void supervisorCanAccessUserPages() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_U);

        assertEquals(0, fixture.memory.read(TARGET, MemorySize.BYTE));
    }

    @Test
    void readWriteAndExecutePermissionsAreIndependent() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        fixture.mapPage(TARGET, MachineFixture.FRAME_A, Mmu.PTE_V | Mmu.PTE_W);

        assertFault(MachineFault.Code.LOAD_FAULT,
                () -> fixture.memory.read(TARGET, MemorySize.WORD));
        assertInstructionFault(
                () -> fixture.memory.readByte(TARGET + 1));

        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R);
        fixture.memory.mmu().flush();
        assertFault(MachineFault.Code.STORE_FAULT,
                () -> fixture.memory.write(TARGET, MemorySize.WORD, 1));
        assertInstructionFault(() -> fixture.memory.readByte(TARGET));
    }

    @Test
    void writeExecutePageIsInvalid() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W | Mmu.PTE_X);

        assertFault(MachineFault.Code.LOAD_FAULT,
                () -> fixture.memory.read(TARGET, MemorySize.WORD));
    }

    @Test
    void reservedPteAndPdeBitsFault() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        int pte = fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R);
        fixture.put32(pte, MachineFixture.FRAME_A | Mmu.PTE_V | Mmu.PTE_R | (1 << 11));
        fixture.memory.mmu().flush();

        assertFault(MachineFault.Code.LOAD_FAULT,
                () -> fixture.memory.read(TARGET, MemorySize.WORD));

        int vpn1 = (TARGET >>> 22) & 0x3FF;
        fixture.put32(MachineFixture.PTBR + vpn1 * 4, (1 << 1));
        fixture.memory.mmu().flush();
        assertFault(MachineFault.Code.LOAD_FAULT,
                () -> fixture.memory.read(TARGET, MemorySize.WORD));
    }

    @Test
    void copyOnWriteAndSnapshotPagesRejectWrites() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W | Mmu.PTE_C);

        assertEquals(0, fixture.memory.read(TARGET, MemorySize.BYTE));
        assertFault(MachineFault.Code.STORE_FAULT,
                () -> fixture.memory.write(TARGET, MemorySize.WORD, 1));

        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W | Mmu.PTE_S);
        fixture.memory.mmu().flush();
        assertFault(MachineFault.Code.STORE_FAULT,
                () -> fixture.memory.write(TARGET, MemorySize.WORD, 1));
    }

    @Test
    void pteCannotMapTheMmioAperture() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        fixture.mapPage(TARGET, PhysicalMemory.MMIO_BASE,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);

        assertFault(MachineFault.Code.BUS_ERROR,
                () -> fixture.memory.read(TARGET, MemorySize.WORD));
    }

    @Test
    void pteFrameOutsidePhysicalMemoryIsBusError() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        fixture.mapPage(TARGET, 0x0500_0000, Mmu.PTE_V | Mmu.PTE_R);

        assertFault(MachineFault.Code.BUS_ERROR,
                () -> fixture.memory.read(TARGET, MemorySize.WORD));
    }

    @Test
    void guestStoreToLivePteInvalidatesTheTlb() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);
        fixture.mapPage(PTE_VIEW, fixture.pteTableAddress(TARGET),
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);
        fixture.memory.write(TARGET, MemorySize.WORD, 0x1111_1111);
        fixture.put32(MachineFixture.FRAME_B, 0x2222_2222);

        int pteOffset = ((TARGET >>> 12) & 0x3FF) * Integer.BYTES;
        fixture.memory.write(PTE_VIEW + pteOffset, MemorySize.WORD,
                MachineFixture.FRAME_B | Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);

        assertEquals(0x2222_2222, fixture.memory.read(TARGET, MemorySize.WORD));
    }

    @Test
    void changingPtbrInvalidatesTheTlb() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);
        fixture.memory.write(TARGET, MemorySize.WORD, 0x1111_1111);

        int newPtbr = MachineFixture.PTE_TABLE_BASE + 0x3FF000;
        int vpn1 = (TARGET >>> 22) & 0x3FF;
        fixture.put32(newPtbr + vpn1 * 4, fixture.pteTableAddress(TARGET) | Mmu.PDE_V);
        fixture.put32(fixture.pteAddress(TARGET),
                MachineFixture.FRAME_B | Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_W);
        fixture.put32(MachineFixture.FRAME_B, 0x2222_2222);

        fixture.csrs.write(Csr.PTBR, newPtbr, 0);

        assertEquals(0x2222_2222, fixture.memory.read(TARGET, MemorySize.WORD));
    }

    @Test
    void fetchRequiresExecuteAndReportsInstructionFault() {
        MachineFixture fixture = new MachineFixture();
        fixture.enableMmu(true);
        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R | Mmu.PTE_X);
        fixture.memory.physical().writeByte(MachineFixture.FRAME_A, 0xAB);

        assertEquals(0xAB, fixture.memory.readByte(TARGET));

        fixture.mapPage(TARGET, MachineFixture.FRAME_A,
                Mmu.PTE_V | Mmu.PTE_R);
        fixture.memory.mmu().flush();
        MachineFault fault = assertThrows(MachineFault.class,
                () -> fixture.memory.readByte(TARGET));
        assertEquals(MachineFault.Code.INSTRUCTION_FAULT, fault.code());
        assertEquals(TARGET, fault.badAddress().intValue());
    }

    @Test
    void romIsReadOnlyAndItsLastPageIsZeroFilled() {
        MachineFixture fixture = new MachineFixture(new byte[] {(byte) 0x5A});

        assertEquals(0x5A, fixture.memory.read(0, MemorySize.BYTE));
        assertEquals(0, fixture.memory.read(1, MemorySize.BYTE));
        assertEquals(0, fixture.memory.read(4095, MemorySize.BYTE));
        assertFault(MachineFault.Code.BUS_ERROR,
                () -> fixture.memory.read(4096, MemorySize.BYTE));
        assertFault(MachineFault.Code.STORE_FAULT,
                () -> fixture.memory.write(0, MemorySize.WORD, 1));
    }

    private static void assertFault(MachineFault.Code code, Runnable action) {
        MemoryFault fault = assertThrows(MemoryFault.class, action::run);
        assertEquals(code, fault.code());
    }

    private static void assertInstructionFault(Runnable action) {
        MachineFault fault = assertThrows(MachineFault.class, action::run);
        assertEquals(MachineFault.Code.INSTRUCTION_FAULT, fault.code());
    }
}
