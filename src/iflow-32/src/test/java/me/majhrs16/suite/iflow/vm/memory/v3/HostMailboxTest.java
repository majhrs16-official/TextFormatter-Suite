package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HostMailboxTest {

    @Test
    void publishRaisesTheTargetCoreAndAckClearsStatus() {
        Pic pic = new Pic(3);
        HostMailbox mailbox = new HostMailbox(pic);
        mailbox.writeRegister(HostMailbox.TARGET, 1);

        mailbox.publish(HostMailbox.TYPE_MESSAGE, 0x1234);

        assertEquals(0, pic.pending(0));
        assertEquals(1 << HostMailbox.IRQ_LINE, pic.pending(1));
        assertEquals(0, pic.pending(2));
        assertTrue(mailbox.pending());
        assertEquals(HostMailbox.TYPE_MESSAGE,
                mailbox.readRegister(HostMailbox.EVENT_TYPE));
        assertEquals(0x1234, mailbox.readRegister(HostMailbox.MESSAGE_HANDLE));

        mailbox.writeRegister(HostMailbox.ACK, 1);
        assertFalse(mailbox.pending());
        assertEquals(0, mailbox.readRegister(HostMailbox.STATUS));
    }

    @Test
    void targetOutsideCoreCountIsRejected() {
        HostMailbox mailbox = new HostMailbox(new Pic(2));

        assertThrows(IllegalArgumentException.class,
                () -> mailbox.writeRegister(HostMailbox.TARGET, 2));
        assertThrows(IllegalArgumentException.class,
                () -> mailbox.writeRegister(HostMailbox.TARGET, Pic.BROADCAST));
    }
}
