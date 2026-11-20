package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        pic.writeRegister(Pic.SOFT_IRQ, 7);
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

    @Test
    void eachCoreHasAnIsolatedBank() {
        Pic pic = new Pic(4);

        pic.setMask(1, 0);
        pic.raise(0, 16);
        pic.raise(1, 20);

        assertEquals(1 << 16, pic.pending(0));
        assertEquals(1 << 20, pic.pending(1));
        assertEquals(Pic.NO_LINE, pic.takePending(0));
        assertEquals(20, pic.takePending(1));
        assertEquals(0, pic.inService(0));
        assertEquals(1 << 20, pic.inService(1));

        pic.acknowledge(1, 1 << 20);
        assertEquals(0, pic.inService(1));
    }

    @Test
    void softIrqRoutesToTheTargetCoreAndBroadcast() {
        Pic pic = new Pic(4);

        pic.writeRegister(Pic.SOFT_IRQ, 16 | (2 << 8), 0);
        assertEquals(0, pic.pending(0));
        assertEquals(0, pic.pending(1));
        assertEquals(1 << 16, pic.pending(2));
        assertEquals(0, pic.pending(3));

        pic.writeRegister(Pic.SOFT_IRQ, 17 | (Pic.BROADCAST << 8), 0);
        assertEquals(1 << 17, pic.pending(0));
        assertEquals(1 << 17, pic.pending(1));
        assertEquals((1 << 16) | (1 << 17), pic.pending(2));
        assertEquals(1 << 17, pic.pending(3));
    }

    @Test
    void invalidTargetAndLineAreRejected() {
        Pic pic = new Pic(2);

        assertThrows(IllegalArgumentException.class,
                () -> pic.writeRegister(Pic.SOFT_IRQ, 4 | (2 << 8)));
        assertThrows(IllegalArgumentException.class,
                () -> pic.writeRegister(Pic.SOFT_IRQ, 32));
        assertThrows(IllegalArgumentException.class,
                () -> pic.raise(2, 4));
        assertThrows(IllegalArgumentException.class,
                () -> pic.writeRegister(Pic.SOFT_IRQ, 1 << 16));
    }
}
