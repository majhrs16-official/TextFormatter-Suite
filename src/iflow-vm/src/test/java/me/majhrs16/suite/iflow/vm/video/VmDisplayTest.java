package me.majhrs16.suite.iflow.vm.video;

import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.memory.VmBus;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VmDisplayTest {

    private static final int CTRL = VmMemoryMap.DEVICE_MMIO_BASE;
    private static final int FB = VmMemoryMap.DISPLAY_WINDOW_BASE;

    private void configure(VmDisplay d, int base, int cols, int rows) {
        d.writeWord(CTRL + VmDisplay.REG_BASE, base);
        d.writeWord(CTRL + VmDisplay.REG_COLS, cols);
        d.writeWord(CTRL + VmDisplay.REG_ROWS, rows);
        d.writeWord(CTRL + VmDisplay.REG_CTRL, VmDisplay.CTRL_ENABLE);
    }

    @Test
    void startsDisabledAndDecodesNothing() {
        VmDisplay d = new VmDisplay();
        assertFalse(d.isEnabled());
        assertFalse(d.decodes(FB));
        // The register bank is always decoded so it can be programmed.
        assertTrue(d.decodes(CTRL));
    }

    @Test
    void decodesTheConfiguredWindowOnceEnabled() {
        VmDisplay d = new VmDisplay();
        configure(d, FB, 8, 2); // 8*2*2 = 32 bytes
        assertTrue(d.isEnabled());
        assertTrue(d.decodes(FB));
        assertTrue(d.decodes(FB + 0x1F));
        assertFalse(d.decodes(FB + 0x20)); // one past the window
    }

    @Test
    void rejectsTheRegisterBankAsFramebuffer() {
        VmDisplay d = new VmDisplay();
        // The bank lives at 0x0F00 (inside the BIOS page), outside the screen
        // page: a window starting there would shadow the device's own registers.
        configure(d, CTRL, 80, 25);
        assertFalse(d.isEnabled());
    }

    @Test
    void acceptsAWindowInsideTheDisplayPage() {
        // The screen page is the only legal home for the framebuffer; a smaller
        // window may start anywhere inside it.
        VmDisplay d = new VmDisplay();
        configure(d, FB + 0x800, 2, 2); // 8 bytes at 0xF800-0xF807
        assertTrue(d.isEnabled());
        d.writeByte(FB + 0x800, 'Y');
        assertEquals('Y', d.cellChar(0));
    }

    @Test
    void rejectsAWindowBelowTheDisplayPage() {
        VmDisplay d = new VmDisplay();
        configure(d, 0x0010, 2, 2);
        assertFalse(d.isEnabled());
    }

    @Test
    void rejectsAWindowThatRunsPastTheDisplayPage() {
        VmDisplay d = new VmDisplay();
        // 80*25*2 = 4000 bytes at 0xFF00 would end at 0x10FA0 > 0xFFFF.
        configure(d, 0xFF00, 80, 25);
        assertFalse(d.isEnabled());
    }

    @Test
    void rejectsGeometryThatDoesNotFitTheDisplayPage() {
        VmDisplay d = new VmDisplay();
        // 80*26*2 = 4160 bytes do not fit in the 4096-byte screen page.
        configure(d, FB, 80, 26);
        assertFalse(d.isEnabled());
    }

    @Test
    void writesAndReadsCharacterAndAttribute() {
        VmDisplay d = new VmDisplay();
        configure(d, FB, 4, 1);
        d.writeByte(FB, 'A');
        d.writeByte(FB + 1, 0x1E); // ink 1, paper 14
        d.writeByte(FB + 2, 'B');
        d.writeByte(FB + 3, 0x00);

        assertEquals('A', d.cellChar(0));
        assertEquals(0x1E, d.cellAttribute(0));
        assertEquals('B', d.cellChar(1));
        assertEquals('A', d.readByte(FB));
        assertEquals("AB  ", d.renderText());
    }

    @Test
    void blockWriteFillsTheWindow() {
        VmDisplay d = new VmDisplay();
        configure(d, FB, 4, 1);
        byte[] cells = {'O', 0x0F, 'K', 0x0F, '!', 0x00, ' ', 0x00};
        d.writeBlock(FB, cells, 0, cells.length);

        byte[] read = new byte[cells.length];
        d.readBlock(FB, read, 0, read.length);
        assertArrayEquals(cells, read);
        assertEquals("OK! ", d.renderText());
    }

    @Test
    void clearBitErasesTheScreen() {
        VmDisplay d = new VmDisplay();
        configure(d, FB, 4, 1);
        d.writeByte(FB, 'X');
        assertEquals('X', d.cellChar(0));

        d.writeWord(CTRL + VmDisplay.REG_CTRL, VmDisplay.CTRL_ENABLE | VmDisplay.CTRL_CLEAR);
        assertEquals(' ', d.cellChar(0));
        assertTrue(d.isEnabled());
    }

    @Test
    void renderAnsiCarriesInkAndPaperColors() {
        VmDisplay d = new VmDisplay();
        configure(d, FB, 1, 1);
        d.writeByte(FB, 'Z');
        d.writeByte(FB + 1, 0x21); // ink 2, paper 1
        String ansi = d.renderAnsi();
        assertTrue(ansi.contains("32;41mZ"), "expected 2/1 colors, got: " + ansi);
    }

    @Test
    void pageIsPlainRamWhileTheScreenIsDisabled() {
        // The screen page stays mapped as user RAM: disabling the screen gives
        // the whole page back to the program.
        SoftMMU mmu = new SoftMMU();
        mmu.mapPage(VmMemoryMap.pageOf(FB), FB,
            PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        VmDisplay d = new VmDisplay();
        mmu.attach(d);
        VmBus bus = new VmBus(mmu);

        // Disabled: the address is ordinary RAM.
        bus.writeByte(FB, 'R');
        assertEquals('R', bus.readByte(FB));

        // Enabled: the framebuffer shadows the same address.
        configure(d, FB, 80, 25);
        assertTrue(d.isEnabled());
        assertEquals(' ', bus.readByte(FB));

        // Disabled again: the RAM underneath is back.
        d.writeWord(CTRL + VmDisplay.REG_CTRL, 0);
        assertFalse(d.isEnabled());
        assertEquals('R', bus.readByte(FB));
    }

    @Test
    void blockTransactionIsSplitWhenItStraddlesTheWindow() {
        // IFLOW-AUD-032: a range that contains the window without touching it at
        // either end must still be split, matching byte-wise access.
        SoftMMU mmu = new SoftMMU();
        mmu.mapPage(VmMemoryMap.pageOf(FB), FB,
            PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        VmDisplay d = new VmDisplay();
        mmu.attach(d);
        VmBus bus = new VmBus(mmu);
        configure(d, FB + 0x800, 4, 1); // window 0xF800-0xF807

        byte[] payload = new byte[24];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + i);
        }
        // 0xF7F8..0xF80F: neither end is a device, the window sits in the middle.
        bus.writeBlock(FB + 0x7F8, payload, 0, payload.length);

        // The eight in-window bytes reached the screen...
        for (int i = 0; i < 8; i++) {
            int expected = payload[8 + i] & 0xFF;
            int actual = (i % 2 == 0) ? d.cellChar(i / 2) : d.cellAttribute(i / 2);
            assertEquals(expected, actual, "in-window byte " + i + " must reach the screen");
        }
        // ...and the surrounding bytes went to RAM.
        byte[] before = new byte[8];
        bus.readBlock(FB + 0x7F8, before, 0, before.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(payload, 0, 8), before);
        byte[] after = new byte[8];
        bus.readBlock(FB + 0x808, after, 0, after.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(payload, 16, 24), after);
    }

    @Test
    void programMayDriveDeviceRegistersInsideTheKernelPage() {
        // IFLOW-AUD-031 (fijado el 2026-10-19): la apertura MMIO vive dentro de la
        // página del BIOS (kernel, R/X) y el interconnect la decodifica antes de la
        // MMU, así que el programa puede gobernar sus periféricos por contrato. La
        // propiedad de página sigue protegiendo la *memoria*: escribir RAM en esa
        // misma página sí es falta de privilegio.
        SoftMMU mmu = new SoftMMU();
        mmu.getPageTable().mapPageKernel(0, 0x0000,
            PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        VmDisplay d = new VmDisplay();
        mmu.attach(d);
        VmBus program = new VmBus(mmu); // maestro PROGRAM, como la CPU

        // RAM dentro de la página R/X: la escritura del programa es falta de privilegio...
        VmException fault = assertThrows(VmException.class,
            () -> program.writeByte(0x0100, 0x11));
        assertEquals(VmException.Code.PRIVILEGE_FAULT, fault.code());

        // ...pero la apertura es espacio de dispositivo, no RAM: el programa
        // configura la pantalla a través de ella, igual que el BIOS en el POST.
        assertFalse(d.isEnabled());
        program.writeWord(CTRL + VmDisplay.REG_BASE, FB);
        program.writeWord(CTRL + VmDisplay.REG_COLS, 80);
        program.writeWord(CTRL + VmDisplay.REG_ROWS, 25);
        program.writeWord(CTRL + VmDisplay.REG_CTRL, VmDisplay.CTRL_ENABLE);
        assertTrue(d.isEnabled());

        program.writeByte(FB, 'K');
        assertEquals('K', d.cellChar(0));
    }
}
