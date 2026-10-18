package me.majhrs16.suite.iflow.vm.video;

import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VmDisplayTest {

    private static final int CTRL = VmMemoryMap.DEVICE_MMIO_BASE;

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
        assertFalse(d.decodes(0xE000));
        // The register bank is always decoded so it can be programmed.
        assertTrue(d.decodes(CTRL));
    }

    @Test
    void decodesTheConfiguredWindowOnceEnabled() {
        VmDisplay d = new VmDisplay();
        configure(d, 0xE000, 8, 2); // 8*2*2 = 32 bytes
        assertTrue(d.isEnabled());
        assertTrue(d.decodes(0xE000));
        assertTrue(d.decodes(0xE01F));
        assertFalse(d.decodes(0xE020)); // one past the window
    }

    @Test
    void rejectsAWindowThatWouldOverlapTheRegisterBank() {
        VmDisplay d = new VmDisplay();
        // 80*25*2 = 4000 bytes; a base of 0xF000 would run past 0xFF00.
        configure(d, 0xF000, 80, 25);
        assertFalse(d.isEnabled());
    }

    @Test
    void writesAndReadsCharacterAndAttribute() {
        VmDisplay d = new VmDisplay();
        configure(d, 0xE000, 4, 1);
        d.writeByte(0xE000, 'A');
        d.writeByte(0xE001, 0x1E); // ink 1, paper 14
        d.writeByte(0xE002, 'B');
        d.writeByte(0xE003, 0x00);

        assertEquals('A', d.cellChar(0));
        assertEquals(0x1E, d.cellAttribute(0));
        assertEquals('B', d.cellChar(1));
        assertEquals('A', d.readByte(0xE000));
        assertEquals("AB  ", d.renderText());
    }

    @Test
    void blockWriteFillsTheWindow() {
        VmDisplay d = new VmDisplay();
        configure(d, 0xE000, 4, 1);
        byte[] cells = {'O', 0x0F, 'K', 0x0F, '!', 0x00, ' ', 0x00};
        d.writeBlock(0xE000, cells, 0, cells.length);

        byte[] read = new byte[cells.length];
        d.readBlock(0xE000, read, 0, read.length);
        assertArrayEquals(cells, read);
        assertEquals("OK! ", d.renderText());
    }

    @Test
    void clearBitErasesTheScreen() {
        VmDisplay d = new VmDisplay();
        configure(d, 0xE000, 4, 1);
        d.writeByte(0xE000, 'X');
        assertEquals('X', d.cellChar(0));

        d.writeWord(CTRL + VmDisplay.REG_CTRL, VmDisplay.CTRL_ENABLE | VmDisplay.CTRL_CLEAR);
        assertEquals(' ', d.cellChar(0));
        assertTrue(d.isEnabled());
    }

    @Test
    void renderAnsiCarriesInkAndPaperColors() {
        VmDisplay d = new VmDisplay();
        configure(d, 0xE000, 1, 1);
        d.writeByte(0xE000, 'Z');
        d.writeByte(0xE001, 0x21); // ink 2, paper 1
        String ansi = d.renderAnsi();
        assertTrue(ansi.contains("32;41mZ"), "expected 2/1 colors, got: " + ansi);
    }
}
