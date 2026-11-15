package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PicTest {

    @Test
    void maskAndPrioritySelectTheLowestEligibleLine() {
        Pic pic = new Pic();

        pic.raise(3);
        assertEquals(Pic.NO_LINE, pic.takePending());

        pic.reset();
        pic.setMask(0);
        pic.raise(5);
        pic.raise(2);

        assertEquals(2, pic.takePending());
        assertEquals(5, pic.takePending());
        assertEquals(Pic.NO_LINE, pic.takePending());
        assertEquals((1 << 2) | (1 << 5), pic.inService());
    }

    @Test
    void newEdgeDuringServiceWaitsForAck() {
        Pic pic = new Pic();
        pic.setMask(0);

        pic.raise(4);
        assertEquals(4, pic.takePending());
        pic.raise(4);

        assertEquals(Pic.NO_LINE, pic.takePending());
        assertEquals(1 << 4, pic.pending());

        pic.acknowledge(1 << 4);

        assertEquals(4, pic.takePending());
        assertEquals(0, pic.pending());
    }

    @Test
    void softwareIrqMaskAndResetFollowTheRegisterContract() {
        Pic pic = new Pic();

        pic.writeRegister(Pic.SOFT_IRQ, 1 << 7);
        assertEquals(1 << 7, pic.pending());
        pic.writeRegister(Pic.MASK, 0xFFFF_FF7F);
        assertEquals(7, pic.takePending());
        pic.writeRegister(Pic.ACK, 1 << 7);
        assertEquals(0, pic.inService());

        pic.raise(1);
        pic.reset();
        assertEquals(0, pic.pending());
        assertEquals(0xFFFF_FFFF, pic.mask());
        assertEquals(0, pic.inService());
    }
}
