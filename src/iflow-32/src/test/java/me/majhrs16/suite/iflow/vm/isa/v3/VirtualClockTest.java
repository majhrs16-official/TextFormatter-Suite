package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VirtualClockTest {

    @Test
    void advancesByDeltaAndResets() {
        VirtualClock clock = new VirtualClock(1_000);

        clock.advanceNanos(250);

        assertEquals(1_250, clock.timeNanos());
        clock.reset();
        assertEquals(0, clock.timeNanos());
    }

    @Test
    void rejectsNegativeAdvance() {
        VirtualClock clock = new VirtualClock();

        assertThrows(IllegalArgumentException.class, () -> clock.advanceNanos(-1));
    }

    @Test
    void advanceToCrossesTheUnsignedBoundary() {
        VirtualClock clock = new VirtualClock(Long.MAX_VALUE);

        clock.advanceTo(Long.MIN_VALUE);

        assertEquals(Long.MIN_VALUE, clock.timeNanos());
    }

    @Test
    void advanceToRejectsBackwardsMove() {
        VirtualClock clock = new VirtualClock(10);

        assertThrows(IllegalArgumentException.class, () -> clock.advanceTo(9));
    }

    @Test
    void isUsableAsTheRdtimeSource() {
        TimeSource source = new VirtualClock(0x1_0000_0002L);

        assertEquals(0x1_0000_0002L, source.timeNanos());
    }
}
