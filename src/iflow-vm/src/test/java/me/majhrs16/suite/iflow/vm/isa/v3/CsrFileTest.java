package me.majhrs16.suite.iflow.vm.isa.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CsrFileTest {

    private CsrFile csrs;

    @BeforeEach
    void setUp() {
        csrs = new CsrFile();
        csrs.reset();
    }

    @Test
    void resetUsesDeterministicArchitecturalValues() {
        assertEquals(Status.RESET, csrs.status());
        assertEquals(0, csrs.epc());
        assertEquals(0, csrs.cause());
        assertEquals(0, csrs.badvaddr());
        assertEquals(0, csrs.tvec());
        assertEquals(0, csrs.ptbr());
        assertEquals(0, csrs.scratch());
        assertEquals(CsrFile.NO_IDENTITY, csrs.pident());
        assertEquals(CsrFile.NO_IDENTITY, csrs.mident());
        assertEquals(Flags.CLEAR, csrs.flags());
    }

    @Test
    void readsAndWritesSimpleRegisters() {
        csrs.write(Csr.EPC, 0x1234, 0);
        csrs.write(Csr.CAUSE, 7, 0);
        csrs.write(Csr.BADVADDR, 0x5678, 0);
        csrs.write(Csr.SCRATCH, 0x9ABC, 0);
        csrs.write(Csr.MIDENT, 0x0001_0002, 0);

        assertEquals(0x1234, csrs.read(Csr.EPC));
        assertEquals(7, csrs.read(Csr.CAUSE));
        assertEquals(0x5678, csrs.read(Csr.BADVADDR));
        assertEquals(0x9ABC, csrs.read(Csr.SCRATCH));
        assertEquals(0x0001_0002, csrs.read(Csr.MIDENT));
    }

    @Test
    void statusWritesRejectReservedBits() {
        MachineFault fault = assertThrows(MachineFault.class,
                () -> csrs.write(Csr.STATUS, 0x40, 0x10));

        assertEquals(MachineFault.Code.ILLEGAL_INSTRUCTION, fault.code());
        assertEquals(0x10, fault.pc());
    }

    @Test
    void statusAndFlagsRoundTrip() {
        csrs.write(Csr.STATUS, 0x15, 0);
        csrs.write(Csr.FLAGS, 0x0F, 0);

        assertEquals(0x15, csrs.read(Csr.STATUS));
        assertEquals(0x0F, csrs.read(Csr.FLAGS));
        assertEquals(Status.fromBits(0x15), csrs.status());
        assertEquals(new Flags(true, true, true, true), csrs.flags());
    }

    @Test
    void flagsWriteMasksBitsOutsideTheArchitecturalNibble() {
        csrs.write(Csr.FLAGS, 0xFFFF_FFFF, 0);

        assertEquals(0x0F, csrs.read(Csr.FLAGS));
    }

    @Test
    void tvecMustBeAlignedTo256Bytes() {
        MachineFault fault = assertThrows(MachineFault.class,
                () -> csrs.write(Csr.TVEC, 0x110, 0));

        assertEquals(MachineFault.Code.ILLEGAL_INSTRUCTION, fault.code());
        csrs.write(Csr.TVEC, 0x300, 0);
        assertEquals(0x300, csrs.read(Csr.TVEC));
    }

    @Test
    void ptbrMustBeAlignedTo4KiB() {
        MachineFault fault = assertThrows(MachineFault.class,
                () -> csrs.write(Csr.PTBR, 0x1001, 0));

        assertEquals(MachineFault.Code.ILLEGAL_INSTRUCTION, fault.code());
        csrs.write(Csr.PTBR, 0x2000, 0);
        assertEquals(0x2000, csrs.read(Csr.PTBR));
    }

    @Test
    void pidentAcceptsOnlyProgramIndicesOrNoIdentity() {
        csrs.write(Csr.PIDENT, 63, 0);
        assertEquals(63, csrs.read(Csr.PIDENT));

        csrs.write(Csr.PIDENT, CsrFile.NO_IDENTITY, 0);
        assertEquals(CsrFile.NO_IDENTITY, csrs.read(Csr.PIDENT));

        MachineFault fault = assertThrows(MachineFault.class,
                () -> csrs.write(Csr.PIDENT, 64, 0));
        assertEquals(MachineFault.Code.ILLEGAL_INSTRUCTION, fault.code());
    }

    @Test
    void trapEntryUpdatesStatusAndExceptionRegisters() {
        csrs.write(Csr.STATUS, Status.RESET.bits() | Status.IE | Status.MMU, 0);

        csrs.enterTrap(0x1234, MachineFault.Code.LOAD_FAULT.vector(), 0x5678);

        assertEquals(0x1234, csrs.epc());
        assertEquals(MachineFault.Code.LOAD_FAULT.vector(), csrs.cause());
        assertEquals(0x5678, csrs.badvaddr());
        assertEquals(Status.PIE, csrs.status().bits() & Status.PIE);
        assertEquals(Status.PPRV, csrs.status().bits() & Status.PPRV);
        assertEquals(Status.TRAP, csrs.status().bits() & Status.TRAP);
        assertEquals(Privilege.SUPERVISOR, csrs.status().privilege());
        assertEquals(0, csrs.status().bits() & Status.IE);
    }

    @Test
    void trapEntryClearsBadAddressWhenTheTrapHasNone() {
        csrs.write(Csr.BADVADDR, 0xFFFF, 0);

        csrs.enterTrap(4, MachineFault.Code.BREAKPOINT.vector(), null);

        assertEquals(0, csrs.badvaddr());
    }
}
