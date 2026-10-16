package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.isa.RegisterFile;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the two message kinds handled by {@link IfLowVmFormatter}:
 * full {@code VM_CYCLE} dumps and compact, pass-through memory trace lines.
 */
class IfLowVmFormatterTest {

    private final IfLowVmFormatter formatter = new IfLowVmFormatter();

    private ExecutionContext createContext() {
        SoftMMU mmu = new SoftMMU();
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        for (int p = 1; p <= 7; p++) {
            mmu.getPageTable().mapPage(p, p * 0x1000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        return new ExecutionContext()
            .executionId("fmt")
            .fuelBudget(1000)
            .softMMU(mmu)
            .serviceRegistry(new HostServiceRegistry());
    }

    @Test
    void memoryTraceLinePassesThroughUnchanged() {
        // Memory lines must NOT be wrapped as VM_CYCLE; they stay compact.
        String out = formatter.formatDebug(null, "MEM RD16 [0x%04X]=0x%04X -> R%d", 0x1000, 0x1234, 2);
        assertEquals("MEM RD16 [0x1000]=0x1234 -> R2", out);
    }

    @Test
    void memoryWriteLinePassesThroughUnchanged() {
        String out = formatter.formatDebug(null, "MEM WR8  [0x%04X]<-0x%02X (R%d)", 0x1102, 0xAB, 1);
        assertEquals("MEM WR8  [0x1102]<-0xAB (R1)", out);
    }

    @Test
    void vmCycleLineEmitsFullRegisterDump() {
        ExecutionContext ctx = createContext();
        ctx.initialize();
        ctx.registers().set(RegisterFile.R1, 0xABCD);

        String out = formatter.formatDebug(ctx, "VM_CYCLE IP=0x%04X OP=0x%02X(%s) FUEL=%d",
            0x0000, 0x00, "LI", 999L);

        assertTrue(out.startsWith("VM_CYCLE "), out);
        assertTrue(out.contains("OP=0x00(LI)"), out);
        assertTrue(out.contains("R1=0xABCD"), out);
        assertTrue(out.contains("IP=0x0000"), out);
        assertTrue(out.contains("FUEL=999"), out);
    }

    @Test
    void nonVmCycleMessageWithContextIsNotDumpedAsCycle() {
        // A context is present, but the message is not VM_CYCLE -> pass-through.
        ExecutionContext ctx = createContext();
        String out = formatter.formatDebug(ctx, "MEM COPY len=%d sumSrc=0x%04X", 6, 0x00CC);
        assertEquals("MEM COPY len=6 sumSrc=0x00CC", out);
    }
}
