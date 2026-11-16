package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class TimerTest {

    @Test
    void oneShotFiresOnlyOnce() {
        AtomicLong clock = new AtomicLong(1_000);
        Pic pic = new Pic();
        pic.setMask(0);
        Timer timer = new Timer(clock::get, pic);

        timer.writeRegister(Timer.CMP_LO, 1_010);
        timer.writeRegister(Timer.CMP_HI, 0);
        timer.writeRegister(Timer.CTRL, Timer.CTRL_ENABLE | Timer.CTRL_IRQ);

        clock.set(1_010);
        timer.tick();
        assertEquals(1, pic.pending());
        assertEquals(0, pic.takePending());
        pic.acknowledge(1);
        assertEquals(Timer.STATUS_FIRED, timer.readRegister(Timer.STATUS));

        timer.tick();
        assertEquals(0, pic.pending());
        assertEquals(Long.MAX_VALUE, timer.nextDeadline());
    }

    @Test
    void periodicRearmsFromCurrentTime() {
        AtomicLong clock = new AtomicLong(0);
        Pic pic = new Pic();
        pic.setMask(0);
        Timer timer = new Timer(clock::get, pic);

        timer.writeRegister(Timer.PERIOD_LO, 100);
        timer.writeRegister(Timer.PERIOD_HI, 0);
        timer.writeRegister(Timer.CMP_LO, 10);
        timer.writeRegister(Timer.CTRL,
                Timer.CTRL_ENABLE | Timer.CTRL_PERIODIC | Timer.CTRL_IRQ);

        clock.set(250);
        timer.tick();
        assertEquals(1, pic.pending());
        assertEquals(350, timer.nextDeadline());
        assertEquals(0, pic.takePending());
        pic.writeRegister(Pic.ACK, 1);

        timer.tick();
        assertEquals(0, pic.pending());
        clock.set(350);
        timer.tick();
        assertEquals(1, pic.pending());
    }

    @Test
    void timeRegistersLatchTheHighWord() {
        AtomicLong clock = new AtomicLong(0x1_0000_0005L);
        Timer timer = new Timer(clock::get, new Pic());

        assertEquals(5, timer.readRegister(Timer.TIME_LO));
        clock.set(0x2_0000_0007L);
        assertEquals(1, timer.readRegister(Timer.TIME_HI));
        assertEquals(2, timer.readRegister(Timer.TIME_HI));
        assertEquals(0x0000_0007, timer.readRegister(Timer.TIME_LO));
    }

    @Test
    void statusIsWriteOneToClear() {
        AtomicLong clock = new AtomicLong(0);
        Timer timer = new Timer(clock::get, new Pic());
        timer.writeRegister(Timer.CMP_LO, 1);
        timer.writeRegister(Timer.CTRL, Timer.CTRL_ENABLE);
        clock.set(1);
        timer.tick();

        assertEquals(Timer.STATUS_FIRED, timer.readRegister(Timer.STATUS));
        timer.writeRegister(Timer.STATUS, Timer.STATUS_FIRED);
        assertEquals(0, timer.readRegister(Timer.STATUS));
        assertTrue(timer.readRegister(Timer.PERIOD_LO) > 0);
    }

    @Test
    void deadlineComparisonIsUnsigned() {
        AtomicLong clock = new AtomicLong(0);
        Timer timer = new Timer(clock::get, new Pic());
        timer.writeRegister(Timer.CMP_LO, 0xFFFF_FFFF);
        timer.writeRegister(Timer.CMP_HI, 0x7FFF_FFFF);
        timer.writeRegister(Timer.CTRL, Timer.CTRL_ENABLE);
        clock.set(Long.MIN_VALUE);

        timer.tick();

        assertEquals(Timer.STATUS_FIRED, timer.readRegister(Timer.STATUS));
    }
}
