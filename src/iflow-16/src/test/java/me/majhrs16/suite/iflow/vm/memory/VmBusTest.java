package me.majhrs16.suite.iflow.vm.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VmBusTest {

    @Test
    void wordWriteSplitsBetweenRamAndDevice() {
        SoftMMU mmu = identityMappedMMU();
        VmNvram nvram = new VmNvram(0x0F10, 16);
        mmu.attach(nvram);
        VmBus bus = VmBus.supervisor(mmu);

        // Low byte stays in RAM, high byte belongs to the NVRAM window.
        bus.writeWord(0x0F0F, 0x1234);

        assertEquals(0x34, mmu.readByte(0x0F0F));
        assertEquals(0x12, nvram.readByte(0x0F10));
        assertEquals(0x1234, bus.readWord(0x0F0F));
    }

    @Test
    void wordAtDeviceEndSplitsAndDoesNotOverrunTheDevice() {
        SoftMMU mmu = identityMappedMMU();
        VmNvram nvram = new VmNvram(0x0F10, 16);
        mmu.attach(nvram);
        VmBus bus = VmBus.supervisor(mmu);

        bus.writeWord(0x0F1F, 0x5678);

        assertEquals(0x78, nvram.readByte(0x0F1F));
        assertEquals(0x56, mmu.readByte(0x0F20));
        assertEquals(0x5678, bus.readWord(0x0F1F));
    }

    private static SoftMMU identityMappedMMU() {
        SoftMMU mmu = new SoftMMU();
        mmu.mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        return mmu;
    }
}
