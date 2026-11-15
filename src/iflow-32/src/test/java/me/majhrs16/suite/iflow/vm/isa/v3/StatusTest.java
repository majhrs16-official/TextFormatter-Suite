package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatusTest {

    @Test
    void resetStartsInSupervisorModeWithEverythingDisabled() {
        assertEquals(0x02, Status.RESET.bits());
        assertTrue(Status.RESET.supervisor());
        assertEquals(Privilege.SUPERVISOR, Status.RESET.privilege());
        assertFalse(Status.RESET.interruptEnable());
        assertFalse(Status.RESET.mmuEnabled());
        assertFalse(Status.RESET.trap());
    }

    @Test
    void bitsRoundTrip() {
        Status status = new Status(true, false, true, false, true, true);

        assertEquals(0x35, status.bits());
        assertEquals(status, Status.fromBits(status.bits()));
        assertEquals(Privilege.USER, status.privilege());
    }

    @Test
    void rejectsReservedBits() {
        assertThrows(IllegalArgumentException.class, () -> Status.fromBits(0x40));
        assertThrows(IllegalArgumentException.class, () -> Status.fromBits(-1));
        assertFalse(Status.isValidBits(0x40));
        assertTrue(Status.isValidBits(Status.MASK));
    }

    @Test
    void trapEntrySavesPreviousPrivilegeAndInterruptEnable() {
        Status before = new Status(true, true, false, false, true, false);

        Status after = before.enterTrap();

        assertFalse(after.interruptEnable());
        assertTrue(after.supervisor());
        assertTrue(after.previousInterruptEnable());
        assertTrue(after.previousSupervisor());
        assertTrue(after.mmuEnabled());
        assertTrue(after.trap());
        assertEquals(0x3E, after.bits());
    }

    @Test
    void eretRestoresInterruptEnableAndPrivilege() {
        Status before = new Status(false, true, true, false, true, true);

        Status after = before.eret();

        assertTrue(after.interruptEnable());
        assertFalse(after.supervisor());
        assertEquals(Privilege.USER, after.privilege());
        assertTrue(after.mmuEnabled());
        assertFalse(after.trap());
        assertEquals(0x15, after.bits());
    }
}
