package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.iflow.vm.isa.v3.MachineFault;
import me.majhrs16.suite.iflow.vm.isa.v3.MemoryFault;
import me.majhrs16.suite.iflow.vm.isa.v3.MemorySize;
import me.majhrs16.suite.iflow.vm.isa.v3.Status;

class MachineMemoryTest {

    @Test
    void mmioIsDecodedBeforeTheMmu() {
        MachineFixture fixture = new MachineFixture();
        fixture.csrs.setStatus(Status.fromBits(Status.MMU | Status.PRV));

        fixture.memory.write(Pic.BASE + Pic.MASK, MemorySize.WORD, 0xFFFF_FFFE);

        assertEquals(0xFFFF_FFFE, fixture.memory.read(Pic.BASE + Pic.MASK, MemorySize.WORD));
    }

    @Test
    void mmioRequiresSupervisorAndWordAccess() {
        MachineFixture fixture = new MachineFixture();
        fixture.csrs.setStatus(Status.fromBits(0));

        MemoryFault privilege = assertThrows(MemoryFault.class,
                () -> fixture.memory.write(Timer.BASE + Timer.CTRL, MemorySize.WORD, 1));
        assertEquals(MachineFault.Code.PRIVILEGE, privilege.code());

        fixture.csrs.setStatus(Status.RESET);
        MemoryFault half = assertThrows(MemoryFault.class,
                () -> fixture.memory.read(Timer.BASE + Timer.CTRL, MemorySize.HALF));
        assertEquals(MachineFault.Code.ALIGNMENT, half.code());

        MemoryFault unaligned = assertThrows(MemoryFault.class,
                () -> fixture.memory.read(Timer.BASE + Timer.CTRL + 2, MemorySize.WORD));
        assertEquals(MachineFault.Code.ALIGNMENT, unaligned.code());
    }

    @Test
    void reservedMmioAddressIsBusError() {
        MachineFixture fixture = new MachineFixture();

        MemoryFault fault = assertThrows(MemoryFault.class,
                () -> fixture.memory.read(0x0100_7000, MemorySize.WORD));

        assertEquals(MachineFault.Code.BUS_ERROR, fault.code());
    }

    @Test
    void timerFiresOnceAndRaisesIrq0() {
        MachineFixture fixture = new MachineFixture();
        long now = fixture.clock.get();
        fixture.memory.write(Pic.BASE + Pic.MASK, MemorySize.WORD, 0xFFFF_FFFE);
        fixture.memory.write(Timer.BASE + Timer.CMP_LO, MemorySize.WORD, (int) (now + 10));
        fixture.memory.write(Timer.BASE + Timer.CMP_HI, MemorySize.WORD, 0);
        fixture.memory.write(Timer.BASE + Timer.CTRL, MemorySize.WORD,
                Timer.CTRL_ENABLE | Timer.CTRL_IRQ);

        fixture.clock.set(now + 10);

        assertEquals(0, fixture.memory.takePending());
        assertEquals(Timer.STATUS_FIRED,
                fixture.memory.read(Timer.BASE + Timer.STATUS, MemorySize.WORD));
        fixture.memory.write(Timer.BASE + Timer.STATUS, MemorySize.WORD, 1);
        assertEquals(0, fixture.memory.read(Timer.BASE + Timer.STATUS, MemorySize.WORD));
        assertEquals(-1, fixture.memory.takePending());
    }

    @Test
    void uartReceivesTransmitsAndRaisesIrq1() {
        MachineFixture fixture = new MachineFixture();
        AtomicInteger transmitted = new AtomicInteger(-1);
        fixture.memory.uart().transmitSink(transmitted::set);
        fixture.memory.write(Pic.BASE + Pic.MASK, MemorySize.WORD, 0xFFFF_FFFD);
        fixture.memory.write(Uart.BASE + Uart.CTRL, MemorySize.WORD, Uart.CTRL_IRQ_RX);

        fixture.memory.write(Uart.BASE + Uart.TX, MemorySize.WORD, 0x41);
        assertTrue(fixture.memory.uart().receive(0x42));

        assertEquals('A', transmitted.get());
        assertEquals(1, fixture.memory.takePending());
        assertEquals('B', fixture.memory.read(Uart.BASE + Uart.RX, MemorySize.WORD));
        assertEquals(0, fixture.memory.read(Uart.BASE + Uart.RX, MemorySize.WORD));
    }

    @Test
    void uartStatusIsReadOnlyAndReportsOverflow() {
        MachineFixture fixture = new MachineFixture();
        for (int index = 0; index < 64; index++) {
            assertTrue(fixture.memory.uart().receive('a'));
        }
        assertFalse(fixture.memory.uart().receive('b'));

        assertEquals(Uart.STATUS_TX_READY | Uart.STATUS_RX_AVAILABLE | Uart.STATUS_OVERFLOW,
                fixture.memory.read(Uart.BASE + Uart.STATUS, MemorySize.WORD));
        fixture.memory.write(Uart.BASE + Uart.STATUS, MemorySize.WORD, 0);
        assertEquals(Uart.STATUS_TX_READY | Uart.STATUS_RX_AVAILABLE | Uart.STATUS_OVERFLOW,
                fixture.memory.read(Uart.BASE + Uart.STATUS, MemorySize.WORD));
    }

    @Test
    void displayStoresCellsAndClears() {
        MachineFixture fixture = new MachineFixture();

        fixture.memory.write(Display.BASE + Display.BUFFER, MemorySize.WORD, 0x1234_5678);
        assertEquals(0x1234_5678,
                fixture.memory.read(Display.BASE + Display.BUFFER, MemorySize.WORD));

        fixture.memory.write(Display.BASE + Display.CTRL, MemorySize.WORD,
                Display.CTRL_ENABLE | Display.CTRL_CLEAR);

        assertEquals(0, fixture.memory.read(Display.BASE + Display.BUFFER,
                MemorySize.WORD));
        assertArrayEquals(new byte[Display.BUFFER_BYTES],
                fixture.memory.display().snapshot());
        assertEquals(Display.CTRL_ENABLE,
                fixture.memory.read(Display.BASE + Display.CTRL, MemorySize.WORD));
    }

    @Test
    void nvramCommitsAndRestoresPersistentBytes() {
        MachineFixture fixture = new MachineFixture();

        fixture.memory.write(Nvram.BASE + Nvram.ADDR, MemorySize.WORD, 10);
        fixture.memory.write(Nvram.BASE + Nvram.DATA, MemorySize.WORD, 0x5A);
        assertEquals(0x5A, fixture.memory.read(Nvram.BASE + Nvram.DATA, MemorySize.WORD));

        fixture.memory.write(Nvram.BASE + Nvram.COMMIT, MemorySize.WORD, 1);

        assertEquals(0x5A, fixture.memory.nvram().persistentBytes()[10]);
        assertEquals(Nvram.BYTES,
                fixture.memory.read(Nvram.BASE + Nvram.SIZE, MemorySize.WORD));
    }

    @Test
    void sysInfoIsReadOnlyAndReportsAbiVersion() {
        MachineFixture fixture = new MachineFixture();

        assertEquals(3, fixture.memory.read(SysInfo.BASE + SysInfo.ABI_VERSION,
                MemorySize.WORD));
        assertEquals(MachineFixture.RAM_SIZE,
                fixture.memory.read(SysInfo.BASE + SysInfo.RAM_SIZE, MemorySize.WORD));
        assertEquals(1, fixture.memory.read(SysInfo.BASE + SysInfo.CORE_COUNT,
                MemorySize.WORD));
        assertEquals(0, fixture.memory.read(SysInfo.BASE + SysInfo.FEATURES,
                MemorySize.WORD) & SysInfo.FEATURE_MULTICORE);

        MemoryFault fault = assertThrows(MemoryFault.class,
                () -> fixture.memory.write(SysInfo.BASE + SysInfo.RAM_SIZE,
                        MemorySize.WORD, 1));
        assertEquals(MachineFault.Code.BUS_ERROR, fault.code());
    }

    @Test
    void coldResetClearsRamAndDevicesButKeepsCommittedNvram() {
        MachineFixture fixture = new MachineFixture();
        fixture.memory.write(0x0205_0000, MemorySize.WORD, 0x1234_5678);
        fixture.memory.pic().setMask(0);
        fixture.memory.pic().raise(4);
        fixture.memory.timer().writeRegister(Timer.CTRL, Timer.CTRL_ENABLE);
        fixture.memory.write(Display.BASE + Display.BUFFER, MemorySize.WORD, 0x1234);
        fixture.memory.mailbox().publish(HostMailbox.TYPE_MESSAGE, 1);
        fixture.memory.write(Nvram.BASE + Nvram.ADDR, MemorySize.WORD, 3);
        fixture.memory.write(Nvram.BASE + Nvram.DATA, MemorySize.WORD, 0x7E);
        fixture.memory.write(Nvram.BASE + Nvram.COMMIT, MemorySize.WORD, 1);
        fixture.memory.write(Nvram.BASE + Nvram.ADDR, MemorySize.WORD, 4);
        fixture.memory.write(Nvram.BASE + Nvram.DATA, MemorySize.WORD, 0x99);

        fixture.memory.reset();

        assertEquals(0, fixture.memory.read(0x0205_0000, MemorySize.WORD));
        assertEquals(0xFFFF_FFFF, fixture.memory.pic().mask());
        assertEquals(0, fixture.memory.pic().pending());
        assertEquals(0, fixture.memory.read(Timer.BASE + Timer.CTRL, MemorySize.WORD));
        assertEquals(0, fixture.memory.read(Display.BASE + Display.BUFFER, MemorySize.WORD));
        assertFalse(fixture.memory.mailbox().pending());
        assertEquals(0x7E, fixture.memory.nvram().persistentBytes()[3]);
        assertEquals(0x7E, fixture.memory.nvram().workingBytes()[3]);
        assertEquals(0, fixture.memory.nvram().workingBytes()[4]);
    }

    @Test
    void mailboxPublishesEventsAndAcknowledges() {
        MachineFixture fixture = new MachineFixture();
        fixture.memory.write(Pic.BASE + Pic.MASK, MemorySize.WORD, 0xFFFF_FFDF);

        fixture.memory.mailbox().publish(HostMailbox.TYPE_MESSAGE, 0x1234);

        assertEquals(HostMailbox.IRQ_LINE, fixture.memory.takePending());
        assertTrue(fixture.memory.mailbox().pending());
        assertEquals(HostMailbox.TYPE_MESSAGE,
                fixture.memory.read(HostMailbox.BASE + HostMailbox.EVENT_TYPE,
                        MemorySize.WORD));
        assertEquals(0x1234,
                fixture.memory.read(HostMailbox.BASE + HostMailbox.MESSAGE_HANDLE,
                        MemorySize.WORD));

        fixture.memory.write(HostMailbox.BASE + HostMailbox.ACK, MemorySize.WORD, 1);
        assertFalse(fixture.memory.mailbox().pending());
    }
}
