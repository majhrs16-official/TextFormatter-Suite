package me.majhrs16.suite.iflow.vm.irq;

import me.majhrs16.suite.iflow.vm.memory.VmDevice;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VmPicTest {

    private static final int BASE = 0x0FF0;

    private VmPic pic() {
        return new VmPic(BASE, VmMemoryMap.DEVICE_PIC_SIZE);
    }

    @Test
    void decodesOnlyItsBank() {
        VmPic pic = pic();
        assertTrue(pic.decodes(BASE));
        assertTrue(pic.decodes(BASE + VmPic.REG_COUNT - 1));
        assertFalse(pic.decodes(BASE - 1)); // the NVRAM sits just below
        assertFalse(pic.decodes(BASE + VmPic.REG_COUNT));
    }

    @Test
    void defaultBankMatchesTheMemoryMap() {
        VmPic pic = new VmPic();
        assertEquals(VmMemoryMap.DEVICE_PIC_BASE, pic.base());
        assertTrue(pic.decodes(VmMemoryMap.DEVICE_PIC_BASE));
    }

    @Test
    void rejectsATooSmallBank() {
        assertThrows(IllegalArgumentException.class,
            () -> new VmPic(BASE, VmPic.REG_COUNT - 1));
    }

    @Test
    void linesStartMaskedSoNothingDispatches() {
        VmPic pic = pic();
        pic.raise(3);
        assertTrue((pic.pendingMask() & (1 << 3)) != 0, "the line is latched");
        assertEquals(-1, pic.nextPending(), "but masked lines never dispatch");
    }

    @Test
    void dispatchesTheLowestEnabledPendingLine() {
        VmPic pic = pic();
        pic.setEnabled(2, true);
        pic.setEnabled(5, true);
        pic.raise(5);
        pic.raise(2);
        pic.raise(7); // not enabled

        assertEquals(2, pic.nextPending());
    }

    @Test
    void beginServiceClearsPendingAndBlocksReentry() {
        VmPic pic = pic();
        pic.setEnabled(4, true);
        pic.raise(4);
        pic.raise(4); // idempotent while pending

        pic.beginService(4);
        assertEquals(0, pic.pendingMask() & (1 << 4), "pending is consumed");
        assertTrue((pic.inServiceMask() & (1 << 4)) != 0, "line is in service");
        assertEquals(4, pic.current());
        assertEquals(-1, pic.nextPending(), "an in-service line does not re-enter");
    }

    @Test
    void ackReleasesTheLine() {
        VmPic pic = pic();
        pic.setEnabled(4, true);
        pic.raise(4);
        pic.beginService(4);

        pic.ack(4);
        assertEquals(0, pic.inServiceMask());
        assertEquals(-1, pic.current(), "idle again");

        pic.raise(4);
        assertEquals(4, pic.nextPending(), "the line can fire again after ACK");
    }

    @Test
    void completeDropsALineWithoutHandler() {
        VmPic pic = pic();
        pic.setEnabled(1, true);
        pic.raise(1);
        pic.beginService(1);
        pic.complete(1);
        assertEquals(0, pic.pendingMask());
        assertEquals(0, pic.inServiceMask());
        assertEquals(-1, pic.current());
    }

    @Test
    void outOfRangeLinesAreIgnored() {
        VmPic pic = pic();
        assertDoesNotThrow(() -> pic.raise(-1));
        assertDoesNotThrow(() -> pic.raise(VmPic.IRQ_LINES));
        assertDoesNotThrow(() -> pic.setEnabled(VmPic.IRQ_LINES, true));
        pic.raise(3);
        assertDoesNotThrow(() -> pic.beginService(99));
        assertDoesNotThrow(() -> pic.ack(99));
        assertDoesNotThrow(() -> pic.complete(99));
        assertEquals(0x0008, pic.pendingMask(), "line 3 is untouched by out-of-range calls");
        assertFalse(pic.isEnabled(3));
    }

    @Test
    void enableAndStatusRegistersRoundTripThroughMmio() {
        VmPic pic = pic();
        pic.writeWord(BASE + VmPic.REG_ENABLE, 0x0022);
        assertEquals(0x0022, pic.readWord(BASE + VmPic.REG_ENABLE));
        pic.raise(1);
        pic.raise(5);
        assertEquals(0x0022, pic.readWord(BASE + VmPic.REG_PENDING));
        assertEquals(0xFFFF, pic.readWord(BASE + VmPic.REG_CURRENT), "idle line is all ones");
    }

    @Test
    void raiseAndAckAreWriteTriggeredRegisters() {
        VmPic pic = pic();
        pic.writeWord(BASE + VmPic.REG_ENABLE, 0xFFFF);
        pic.writeWord(BASE + VmPic.REG_RAISE, 6);
        assertEquals(6, pic.nextPending());

        pic.beginService(6);
        assertEquals(6, pic.readWord(BASE + VmPic.REG_CURRENT));
        pic.writeWord(BASE + VmPic.REG_ACK, 6);
        assertEquals(0, pic.inServiceMask());
    }

    @Test
    void readOnlyRegistersIgnoreWrites() {
        VmPic pic = pic();
        pic.raise(2);
        pic.writeWord(BASE + VmPic.REG_PENDING, 0xFFFF);
        assertEquals(0x0004, pic.pendingMask(), "PENDING is read-only");
        pic.writeWord(BASE + VmPic.REG_CURRENT, 3);
        assertEquals(-1, pic.current(), "CURRENT is read-only");
    }

    @Test
    void byteAccessSplitsTheEnableWord() {
        VmPic pic = pic();
        pic.writeByte(BASE + VmPic.REG_ENABLE, 0x22);         // low byte
        pic.writeByte(BASE + VmPic.REG_ENABLE + 1, 0x80);     // high byte
        assertEquals(0x8022, pic.enabledMask());
        assertEquals(0x22, pic.readByte(BASE + VmPic.REG_ENABLE));
        assertEquals(0x80, pic.readByte(BASE + VmPic.REG_ENABLE + 1));
    }

    @Test
    void deviceViewMatchesTheRegistry() {
        VmPic pic = pic();
        VmDevice dev = pic;
        assertTrue(dev.decodes(BASE));
        pic.writeWord(BASE + VmPic.REG_ENABLE, 0x0010);
        assertEquals(0x0010, dev.readWord(BASE + VmPic.REG_ENABLE));
    }
}
